package org.glassfish.jersey.jdk.connector;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Created by petr on 03/05/15.
 */
public class BufferedBodyOutputStream extends NioOutputStream {

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public void write(int b) throws IOException {
        buffer.write(b);
    }

    ByteBuffer toBuffer() {
        return ByteBuffer.wrap(buffer.toByteArray());
    }
}
