package org.glassfish.jersey.jdk.connector;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

/**
 * @author Petr Janouch (petr.janouch at oracle.com)
 */
public class HttpRequestEncoder {

    private static final String ENCODING = "US-ASCII";
    private static final String LINE_SEPARATOR = "\r\n";
    private static final byte[] LAST_CHUNK = "0\r\n\r\n".getBytes(Charset.forName(ENCODING));
    private static final String HTTP_VERSION = "HTTP/1.1";

    private static void appendUpgradeHeaders(StringBuilder request, Map<String, List<String>> headers) {
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            StringBuilder value = new StringBuilder();
            for (String valuePart : header.getValue()) {
                if (value.length() != 0) {
                    value.append(", ");
                }
                value.append(valuePart);
            }
            appendHeader(request, header.getKey(), value.toString());
        }

        request.append(LINE_SEPARATOR);
    }

    private static void appendHeader(StringBuilder request, String key, String value) {
        request.append(key);
        request.append(": ");
        request.append(value);
        request.append(LINE_SEPARATOR);
    }

    private static void appendFirstLine(StringBuilder request, HttpRequest httpRequest) {
        request.append(httpRequest.getMethod());
        request.append(" ");
        URI uri = httpRequest.getUri();
        String path = uri.getPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }

        if (uri.getQuery() != null) {
            path += "?" + uri.getQuery();
        }
        request.append(path);
        request.append(" ");
        request.append(HTTP_VERSION);
        request.append(LINE_SEPARATOR);
    }

    static ByteBuffer encodeHeader(HttpRequest httpRequest) {
        StringBuilder request = new StringBuilder();
        appendFirstLine(request, httpRequest);
        appendUpgradeHeaders(request, httpRequest.getHeaders());
        String requestStr = request.toString();
        byte[] bytes = requestStr.getBytes(Charset.forName(ENCODING));
        return ByteBuffer.wrap(bytes);
    }

    static ByteBuffer encodeChunk(ByteBuffer data) {
        if (data.remaining() == 0) {
            return ByteBuffer.wrap(LAST_CHUNK);
        }
        String chunkStart = Integer.toHexString(data.limit()) + LINE_SEPARATOR;
        byte[] startBytes = chunkStart.getBytes(Charset.forName(ENCODING));
        ByteBuffer chunkBuffer = ByteBuffer.allocate(startBytes.length + data.limit() + 2);
        chunkBuffer.put(startBytes);
        chunkBuffer.put(data);
        chunkBuffer.put(LINE_SEPARATOR.getBytes(Charset.forName(ENCODING)));
        chunkBuffer.flip();
        return chunkBuffer;
    }
}
