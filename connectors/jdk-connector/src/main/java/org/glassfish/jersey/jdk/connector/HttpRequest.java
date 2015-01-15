package org.glassfish.jersey.jdk.connector;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Created by petr on 10/12/14.
 */
public class HttpRequest {

    private final String method;
    private final String uri;
    private final Map<String, List<String>> headers;
    private final OutputStreamListener outputStreamListener;
    private final BodyMode bodyMode;
    private final int chunkSize;
    private final ByteBuffer bufferedBody;
    private final int bodySize;

    private HttpRequest(String method, String uri, Map<String, List<String>> headers, BodyMode bodyMode, OutputStreamListener outputStreamListener, int bodySize, int chunkSize, ByteBuffer bufferedBody) {
        this.method = method;
        this.uri = uri;
        this.headers = headers;
        this.bodyMode =bodyMode;
        this.outputStreamListener = outputStreamListener;
        this.chunkSize = chunkSize;
        this.bufferedBody = bufferedBody;
        this.bodySize = bodySize;
    }

    public static HttpRequest createBodyless(String method, String uri, Map<String, List<String>> headers) {
        return new HttpRequest(method, uri, headers, BodyMode.NONE, null, 0, 0, null);
    }

    public static HttpRequest createStreamed(String method, String uri, Map<String, List<String>> headers, int bodySize, OutputStreamListener outputStreamListener) {
        return new HttpRequest(method, uri, headers, BodyMode.STREAMING, outputStreamListener, bodySize, 0, null);
    }

    public static HttpRequest createChunked(String method, String uri, Map<String, List<String>> headers, int chunkSize, OutputStreamListener outputStreamListener) {
        return new HttpRequest(method, uri, headers, BodyMode.CHUNKED, outputStreamListener, 0, chunkSize, null);
    }

    public static HttpRequest createBuffered(String method, String uri, Map<String, List<String>> headers, ByteBuffer body) {
        return new HttpRequest(method, uri, headers, BodyMode.BUFFERED, null, body.remaining(), 0, body);
    }

    String getMethod() {
        return method;
    }

    String getUri() {
        return uri;
    }

    Map<String, List<String>> getHeaders() {
        return headers;
    }

    BodyMode getBodyMode() {
        return bodyMode;
    }

    int getChunkSize() {
        return chunkSize;
    }

    ByteBuffer getBufferedBody() {
        return bufferedBody;
    }

    void addHeader(String name, String value) {
        List<String> values = headers.get(name);
        if (values == null) {
            values = new ArrayList<>(1);
            headers.put(name, values);
        }

        values.add(value);
    }

    void setBodyOutputStream(OutputStream bodyOutputStream) {
        outputStreamListener.onReady(bodyOutputStream);
    }

    int getBodySize() {
        return bodySize;
    }

    static interface OutputStreamListener {

        void onReady(OutputStream outputStream);
    }

    enum BodyMode {
        NONE,
        STREAMING,
        CHUNKED,
        BUFFERED
    }
}
