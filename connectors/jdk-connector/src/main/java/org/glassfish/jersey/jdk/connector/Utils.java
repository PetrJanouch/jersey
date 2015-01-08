package org.glassfish.jersey.jdk.connector;

import java.nio.ByteBuffer;

/**
 * Created by petr on 11/12/14.
 */
public class Utils {
    /**
     * Concatenates two buffers into one. If buffer given as first argument has enough space for putting
     * the other one, it will be done and the original buffer will be returned. Otherwise new buffer will
     * be created.
     *
     * @param buffer  first buffer.
     * @param buffer1 second buffer.
     * @return concatenation.
     */
    static ByteBuffer appendBuffers(ByteBuffer buffer, ByteBuffer buffer1, int incomingBufferSize, int BUFFER_STEP_SIZE) {

        final int limit = buffer.limit();
        final int capacity = buffer.capacity();
        final int remaining = buffer.remaining();
        final int len = buffer1.remaining();

        // buffer1 will be appended to buffer
        if (len < (capacity - limit)) {

            buffer.mark();
            buffer.position(limit);
            buffer.limit(capacity);
            buffer.put(buffer1);
            buffer.limit(limit + len);
            buffer.reset();
            return buffer;
            // Remaining data is moved to left. Then new data is appended
        } else if (remaining + len < capacity) {
            buffer.compact();
            buffer.put(buffer1);
            buffer.flip();
            return buffer;
            // create new buffer
        } else {
            int newSize = remaining + len;
            if (newSize > incomingBufferSize) {
                throw new IllegalArgumentException("Buffer overflow");
            } else {
                final int roundedSize = (newSize % BUFFER_STEP_SIZE) > 0 ? ((newSize / BUFFER_STEP_SIZE) + 1) * BUFFER_STEP_SIZE : newSize;
                final ByteBuffer result = ByteBuffer.allocate(roundedSize > incomingBufferSize ? newSize : roundedSize);
                result.put(buffer);
                result.put(buffer1);
                result.flip();
                return result;
            }
        }
    }

    static ByteBuffer split(ByteBuffer buffer, int position) {
        int bytesLength = position - buffer.position();
        byte[] bytes = new byte[bytesLength];
        buffer.get(bytes);
        return ByteBuffer.wrap(bytes);
    }
}
