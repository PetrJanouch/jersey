package org.glassfish.jersey.jdk.connector;

import javax.ws.rs.core.HttpHeaders;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Created by petr on 10/12/14.
 */
public class HttpRequest {

    private static String TRANSFER_ENCODING_HEADER = "Transfer-Encoding";
    private static String TRANSFER_ENCODING_CHUNKED = "chunked";
    private static String HOST_HEADER = "Host";


    private final String method;
    private final URI uri;
    private final Map<String, List<String>> headers;
    private final OutputStreamListener outputStreamListener;
    private final BodyMode bodyMode;
    private final int chunkSize;
    private final ByteBuffer bufferedBody;
    private final int bodySize;

    private HttpRequest(String method, URI uri, Map<String, List<String>> headers, BodyMode bodyMode, OutputStreamListener outputStreamListener, int bodySize, int chunkSize, ByteBuffer bufferedBody) {
        this.method = method;
        this.uri = uri;
        this.headers = headers;
        this.bodyMode =bodyMode;
        this.outputStreamListener = outputStreamListener;
        this.chunkSize = chunkSize;
        this.bufferedBody = bufferedBody;
        this.bodySize = bodySize;

        int port = Utils.getPort(uri);
        addHeaderIfNotPresent(HOST_HEADER, uri.getHost() + ":" + port);
    }

    public static HttpRequest createBodyless(String method, URI uri, Map<String, List<String>> headers) {
        HttpRequest httpRequest = new HttpRequest(method, uri, headers, BodyMode.NONE, null, 0, 0, null);
        httpRequest.addHeaderIfNotPresent(HttpHeaders.CONTENT_LENGTH, Integer.toString(0));
        return httpRequest;
    }

    public static HttpRequest createStreamed(String method, URI uri, Map<String, List<String>> headers, int bodySize, OutputStreamListener outputStreamListener) {
        return new HttpRequest(method, uri, headers, BodyMode.STREAMING, outputStreamListener, bodySize, 0, null);
    }

    public static HttpRequest createChunked(String method, URI uri, Map<String, List<String>> headers, int chunkSize, OutputStreamListener outputStreamListener) {
        HttpRequest httpRequest = new HttpRequest(method, uri, headers, BodyMode.CHUNKED, outputStreamListener, 0, chunkSize, null);
        httpRequest.addHeaderIfNotPresent(TRANSFER_ENCODING_HEADER, TRANSFER_ENCODING_CHUNKED);
        return httpRequest;
    }

    public static HttpRequest createBuffered(String method, URI uri, Map<String, List<String>> headers, OutputStreamListener outputStreamListener) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        outputStreamListener.onReady(byteArrayOutputStream);
        ByteBuffer bufferedBody = ByteBuffer.wrap(byteArrayOutputStream.toByteArray());
        HttpRequest httpRequest = new HttpRequest(method, uri, headers, BodyMode.BUFFERED, null, bufferedBody.remaining(), 0, bufferedBody);
        httpRequest.addHeaderIfNotPresent(HttpHeaders.CONTENT_LENGTH, Integer.toString(httpRequest.getBodySize()));

        return httpRequest;
    }

    String getMethod() {
        return method;
    }

    URI getUri() {
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

    void addHeaderIfNotPresent(String name, String value) {
        List<String> values = headers.get(name);
        if (values == null) {
            values = new ArrayList<>(1);
            headers.put(name, values);
            values.add(value);
        }
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
