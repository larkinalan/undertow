package io.undertow.server.handlers.encoding;

import io.undertow.UndertowLogger;
import io.undertow.predicate.Predicate;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.resource.CachingResourceManager;
import io.undertow.server.handlers.resource.Resource;
import io.undertow.util.ImmediateConduitFactory;
import org.xnio.FileAccess;
import org.xnio.IoUtils;
import org.xnio.XnioIoThread;
import org.xnio.XnioWorker;
import org.xnio.channels.StreamSourceChannel;
import org.xnio.conduits.ConduitStreamSinkChannel;
import org.xnio.conduits.StreamSinkConduit;
import org.xnio.conduits.WriteReadyHandler;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * Class that provides a way of serving pre-encoded resources.
 *
 * @author Stuart Douglas
 */
public class ContentEncodedResourceManager {


    private final File encodedResourcesRoot;
    private final CachingResourceManager encoded;
    private final ContentEncodingRepository contentEncodingRepository;
    private final int minResourceSize;
    private final int maxResourceSize;
    private final Predicate encodingAllowed;

    private final ConcurrentMap<LockKey, Object> fileLocks = new ConcurrentHashMap<LockKey, Object>();

    public ContentEncodedResourceManager(File encodedResourcesRoot, CachingResourceManager encodedResourceManager, ContentEncodingRepository contentEncodingRepository, int minResourceSize, int maxResourceSize, Predicate encodingAllowed) {
        this.encodedResourcesRoot = encodedResourcesRoot;
        this.encoded = encodedResourceManager;
        this.contentEncodingRepository = contentEncodingRepository;
        this.minResourceSize = minResourceSize;
        this.maxResourceSize = maxResourceSize;
        this.encodingAllowed = encodingAllowed;
    }

    /**
     * Gets a pre-encoded resource.
     * <p/>
     * TODO: blocking / non-blocking semantics
     *
     * @param resource
     * @param exchange
     * @return
     * @throws IOException
     */
    public ContentEncodedResource getResource(final Resource resource, final HttpServerExchange exchange) throws IOException {
        final String path = resource.getPath();
        File file = resource.getFile();
        if (file == null) {
            return null;
        }
        if (minResourceSize > 0 && resource.getContentLength() < minResourceSize ||
                maxResourceSize > 0 && resource.getContentLength() > maxResourceSize ||
                !(encodingAllowed == null || encodingAllowed.resolve(exchange))) {
            return null;
        }
        AllowedContentEncodings encodings = contentEncodingRepository.getContentEncodings(exchange);
        if (encodings == null || encodings.isNoEncodingsAllowed()) {
            return null;
        }
        EncodingMapping encoding = encodings.getEncoding();
        if (encoding == null || encoding.getName().equals(ContentEncodingRepository.IDENTITY)) {
            return null;
        }
        String newPath = path + ".undertow.encoding." + encoding.getName();
        Resource preCompressed = encoded.getResource(newPath);
        if (preCompressed != null) {
            return new ContentEncodedResource(preCompressed, encoding.getName());
        }
        final LockKey key = new LockKey(path, encoding.getName());
        if (fileLocks.putIfAbsent(key, this) != null) {
            //another thread is already compressing
            //we don't do anything fancy here, just return and serve non-compressed content
            return null;
        }
        FileChannel targetFileChannel = null;
        FileChannel sourceFileChannel = null;
        try {
            //double check, the compressing thread could have finished just before we aquired the lock
            preCompressed = encoded.getResource(newPath);
            if (preCompressed != null) {
                return new ContentEncodedResource(preCompressed, encoding.getName());
            }

            final File finalTarget = new File(encodedResourcesRoot, newPath);
            final File tempTarget = new File(encodedResourcesRoot, newPath);

            //horrible hack to work around XNIO issue
            FileOutputStream tmp = new FileOutputStream(tempTarget);
            try {
                tmp.close();
            } finally {
                IoUtils.safeClose(tmp);
            }

            targetFileChannel = exchange.getConnection().getWorker().getXnio().openFile(tempTarget, FileAccess.READ_WRITE);
            sourceFileChannel = exchange.getConnection().getWorker().getXnio().openFile(file, FileAccess.READ_ONLY);

            StreamSinkConduit conduit = encoding.getEncoding().getResponseWrapper().wrap(new ImmediateConduitFactory<StreamSinkConduit>(new FileConduitTarget(targetFileChannel, exchange)), exchange);
            final ConduitStreamSinkChannel targetChannel = new ConduitStreamSinkChannel(null, conduit);
            long transfered = sourceFileChannel.transferTo(0, resource.getContentLength(), targetChannel);
            targetChannel.shutdownWrites();
            org.xnio.channels.Channels.flushBlocking(targetChannel);
            if (transfered != resource.getContentLength()) {
                UndertowLogger.REQUEST_LOGGER.error("Failed to write pre-cached file");
            }
            tempTarget.renameTo(finalTarget);
            encoded.invalidate(newPath);
            final Resource encodedResource = encoded.getResource(newPath);
            return new ContentEncodedResource(encodedResource, encoding.getName());
        } finally {
            IoUtils.safeClose(targetFileChannel);
            IoUtils.safeClose(sourceFileChannel);
            fileLocks.remove(key);
        }
    }

    private final class LockKey {
        private final String path;
        private final String encoding;

        private LockKey(String path, String encoding) {
            this.path = path;
            this.encoding = encoding;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            LockKey lockKey = (LockKey) o;

            if (encoding != null ? !encoding.equals(lockKey.encoding) : lockKey.encoding != null) return false;
            if (path != null ? !path.equals(lockKey.path) : lockKey.path != null) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = path != null ? path.hashCode() : 0;
            result = 31 * result + (encoding != null ? encoding.hashCode() : 0);
            return result;
        }
    }

    private static final class FileConduitTarget implements StreamSinkConduit {
        private final FileChannel fileChannel;
        private final HttpServerExchange exchange;
        private WriteReadyHandler writeReadyHandler;
        private boolean writesResumed = false;

        private FileConduitTarget(FileChannel fileChannel, HttpServerExchange exchange) {
            this.fileChannel = fileChannel;
            this.exchange = exchange;
        }

        @Override
        public long transferFrom(FileChannel fileChannel, long l, long l2) throws IOException {
            return this.fileChannel.transferFrom(fileChannel, l, l2);
        }

        @Override
        public long transferFrom(StreamSourceChannel streamSourceChannel, long l, ByteBuffer byteBuffer) throws IOException {
            return IoUtils.transfer(streamSourceChannel, l, byteBuffer, fileChannel);
        }

        @Override
        public int write(ByteBuffer byteBuffer) throws IOException {
            return fileChannel.write(byteBuffer);
        }

        @Override
        public long write(ByteBuffer[] byteBuffers, int i, int i2) throws IOException {
            return fileChannel.write(byteBuffers, i, i2);
        }

        @Override
        public void terminateWrites() throws IOException {
            fileChannel.close();
        }

        @Override
        public boolean isWriteShutdown() {
            return !fileChannel.isOpen();
        }

        @Override
        public void resumeWrites() {
            wakeupWrites();
        }

        @Override
        public void suspendWrites() {
            writesResumed = false;
        }

        @Override
        public void wakeupWrites() {
            if (writeReadyHandler != null) {
                writesResumed = true;
                while (writesResumed && writeReadyHandler != null) {
                    writeReadyHandler.writeReady();
                }
            }
        }

        @Override
        public boolean isWriteResumed() {
            return writesResumed;
        }

        @Override
        public void awaitWritable() throws IOException {
        }

        @Override
        public void awaitWritable(long l, TimeUnit timeUnit) throws IOException {
        }

        @Override
        public XnioIoThread getWriteThread() {
            return exchange.getIoThread();
        }

        @Override
        public void setWriteReadyHandler(WriteReadyHandler writeReadyHandler) {
            this.writeReadyHandler = writeReadyHandler;
        }

        @Override
        public void truncateWrites() throws IOException {
            fileChannel.close();
        }

        @Override
        public boolean flush() throws IOException {
            return true;
        }

        @Override
        public XnioWorker getWorker() {
            return exchange.getConnection().getWorker();
        }
    }
}
