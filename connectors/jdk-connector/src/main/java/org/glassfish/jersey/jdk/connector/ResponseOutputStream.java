package org.glassfish.jersey.jdk.connector;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Created by petr on 12/12/14.
 */
abstract class ResponseOutputStream extends OutputStream {

    private final Filter<ByteBuffer, ?, ?, ?> downstreamFilter;

    ResponseOutputStream(Filter<ByteBuffer, ?, ?, ?> downstreamFilter) {
        this.downstreamFilter = downstreamFilter;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return;
        }

        ByteBuffer data = ByteBuffer.wrap(b, off, len);
        final CountDownLatch writeLatch = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>();
        downstreamFilter.write(data, new CompletionHandler<ByteBuffer>() {
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

    @Override
    public void write(int b) throws IOException {
        byte[] byteArray = new byte[]{(byte) b};

        write(byteArray, 0, 1);
    }

    @Override
    abstract public void close();
}
