package org.glassfish.jersey.jdk.connector;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Created by petr on 12/12/14.
 */
abstract class ChunkedBodyOutputStream extends OutputStream {

    private final static ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);

    private final Filter<ByteBuffer, ?, ?, ?> downstreamFilter;
    private final ByteBuffer chunkBuffer;

    ChunkedBodyOutputStream(Filter<ByteBuffer, ?, ?, ?> downstreamFilter, int chunkSize) {
        this.downstreamFilter = downstreamFilter;
        this.chunkBuffer = ByteBuffer.allocate(chunkSize);
    }

    @Override
    public void write(int i) throws IOException {
        chunkBuffer.put((byte)i);

        if (chunkBuffer.limit() == chunkBuffer.position()) {
            flush();
        }
    }

    @Override
    public void close() throws IOException {
        flush();
        ByteBuffer byteBuffer = HttpRequestEncoder.encodeChunk(EMPTY_BUFFER);
        writeChunk(byteBuffer);
        onClosed();
    }

    @Override
    public void flush() throws IOException {
        if (chunkBuffer.position() == 0) {
            return;
        }
        chunkBuffer.flip();
        ByteBuffer chunk = HttpRequestEncoder.encodeChunk(chunkBuffer);
        chunkBuffer.clear();
        writeChunk(chunk);
    }

    abstract void onClosed();

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

        try {
            writeLatch.await();
        } catch (Exception e) {
            // TODO
        }

        if (error.get() != null) {
            throw new IOException("Writing a chunk failed", error.get());
        }
    }
}
