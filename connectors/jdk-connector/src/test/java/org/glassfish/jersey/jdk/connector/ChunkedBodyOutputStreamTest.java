package org.glassfish.jersey.jdk.connector;

import org.glassfish.jersey.internal.util.collection.ByteBufferInputStream;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;

import static junit.framework.Assert.assertEquals;
import static junit.framework.TestCase.fail;

/**
 * Created by petr on 15/01/15.
 */
public class ChunkedBodyOutputStreamTest {

    @Test
    public void testBasic() throws IOException {
        ByteBufferInputStream responseBody= new ByteBufferInputStream();
        ChunkedBodyOutputStream chunkedStream = new ChunkedBodyOutputStream(createMockTransportFilter(responseBody), 20) {
            @Override
            void onClosed() {

            }
        };

        String sentBody = TestUtils.generateBody(500);
        byte[] sentBytes = sentBody.getBytes("ASCII");
        for (byte b : sentBytes) {
            chunkedStream.write(b);
        }

        chunkedStream.close();

        byte[] receivedBytes = new byte[sentBytes.length];

        for (int i = 0; i < sentBytes.length; i ++) {
            int b = responseBody.tryRead();
            if (b == -1) {
                fail();
            }

            receivedBytes[i] = (byte)b;
        }

        if (responseBody.tryRead() != -1) {
            fail();
        }

        String receivedBody = new String(receivedBytes, "ASCII");
        assertEquals(sentBody, receivedBody);
    }

    private Filter<ByteBuffer, ?, ?, ?> createMockTransportFilter(final ByteBufferInputStream responseBody) {
        GrizzlyHttpParser parser = new GrizzlyHttpParser(Integer.MAX_VALUE, Integer.MAX_VALUE);
        parser.reset(true);
        final GrizzlyTransferEncodingParser transferEncodingParser = GrizzlyTransferEncodingParser.createChunkParser(responseBody, parser);
        return new Filter<ByteBuffer, Void, Void, Void> (null){

            @Override
            public void write(ByteBuffer chunk, CompletionHandler<ByteBuffer> completionHandler) {
                try {
                    if(transferEncodingParser.parse(chunk)) {
                        responseBody.closeQueue();
                    }
                    completionHandler.completed(chunk);
                } catch (ParseException e) {
                    completionHandler.failed(e);
                }
            }
        };
    }
}
