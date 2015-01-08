package org.glassfish.jersey.jdk.connector;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Created by petr on 12/12/14.
 */
class ChunkedResponseOutputStream extends OutputStream {

    private final static ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);

    private final Filter<ByteBuffer, ?, ?, ?> downstreamFilter;
    private final ByteBuffer chunkBuffer;

    ChunkedResponseOutputStream(Filter<ByteBuffer, ?, ?, ?> downstreamFilter, int chunkSize) {
        this.downstreamFilter = downstreamFilter;
        this.chunkBuffer = ByteBuffer.allocate(chunkSize);
    }

    @Override
    public void write(int i) throws IOException {
        if (chunkBuffer.limit() == chunkBuffer.position()) {
            flush();
        }

        chunkBuffer.put((byte)i);
    }

    @Override
    public void close() throws IOException {
        flush();
        ByteBuffer byteBuffer = HttpRequestEncoder.encodeChunk(EMPTY_BUFFER);
        writeChunk(byteBuffer);
    }

    @Override
    public void flush() throws IOException {
        ByteBuffer chunk = HttpRequestEncoder.encodeChunk(chunkBuffer);
        chunkBuffer.reset();
        writeChunk(chunk);
    }

    private void writeChunk(ByteBuffer chunk) throws IOException {
        final CountDownLatch writeLatch = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>();
        downstreamFilter.write(chunk, new CompletionHandler<ByteBuffer>() {
            @Override
            public void completed(ByteBuffer result) {
                writeLatch.countDown();
            }

            @Override
            public void failed(Throwable t) {
                error.set(t);
                writeLatch.countDown();
            }
        });

        if (error.get() != null) {
            throw new IOException("Writing a chunk failed", error.get());
        }
    }
}
