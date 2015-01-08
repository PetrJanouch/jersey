package org.glassfish.jersey.jdk.connector;

import org.glassfish.jersey.internal.util.collection.ByteBufferInputStream;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Created by petr on 07/01/15.
 */
public class HttpParserTest {

    private static final Charset responseEncoding = Charset.forName("ISO-8859-1");

    private final GrizzlyHttpParser httpParser = new GrizzlyHttpParser();

    @Test
    public void testResponseLine() throws ParseException {
        httpParser.reset(false);
        StringBuilder request  = new StringBuilder();
        request.append("HTTP/1.1 123 A meaningful code\r\n\r\n");
        feedParser(request, 1000);

        assertTrue(httpParser.isHeaderParsed());
        assertTrue(httpParser.isComplete());

        HttpResponse httpResponse = httpParser.getHttpResponse();
        assertNotNull(httpResponse);

        assertEquals("HTTP/1.1", httpResponse.getProtocolVersion());
        assertEquals(123, httpResponse.getStatusCode());
        assertEquals("A meaningful code", httpResponse.getReasonPhrase());
    }

    private void feedParser(StringBuilder request, int segmentSize) throws ParseException {
        List<ByteBuffer> serializedResponse = new ArrayList<>();
        byte[] bytes = request.toString().getBytes(responseEncoding);
        int segmentStartIdx = 0;
        while (segmentStartIdx < bytes.length) {
            int segmentEndIdx = segmentStartIdx + segmentSize < bytes.length ? segmentStartIdx + segmentSize : bytes.length;
            ByteBuffer buff = ByteBuffer.wrap(bytes, segmentStartIdx, segmentEndIdx);
            serializedResponse.add(buff);
            segmentEndIdx  = segmentEndIdx + 1;
        }

        for (ByteBuffer input : serializedResponse) {
            httpParser.parse(input);
        }
    }
}
