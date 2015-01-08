package org.glassfish.jersey.jdk.connector;

import org.glassfish.jersey.internal.util.collection.ByteBufferInputStream;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by petr on 10/12/14.
 */
public class HttpResponse {

    private final String protocolVersion;
    private final int statusCode;
    private final String reasonPhrase;
    private final Map<String, List<String>> headers = new HashMap<>();
    private final ByteBufferInputStream bodyStream = new ByteBufferInputStream();

    HttpResponse(String protocolVersion, int statusCode, String reasonPhrase) {
        this.protocolVersion = protocolVersion;
        this.statusCode = statusCode;
        this.reasonPhrase = reasonPhrase;
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getReasonPhrase() {
        return reasonPhrase;
    }

    public Map<String, List<String>> getHeaders() {
        return headers;
    }

    List<String> getHeader(String name) {
        for (String headerName : headers.keySet()) {
            if (headerName.equalsIgnoreCase(name)) {
                return headers.get(headerName);
            }
        }

        return null;
    }

    List<String> removeHeader(String name) {
        String storedName = null;
        for (String headerName : headers.keySet()) {
            if (headerName.equalsIgnoreCase(name)) {
                storedName = headerName;
                break;
            }
        }

        if (storedName != null) {
            return headers.remove(storedName);
        }

        return null;
    }

    void addHeader(String name, String value) {
        List<String> values = getHeader(name);
        if (values == null) {
            values = new ArrayList<>(1);
            headers.put(name, values);
        }

        values.add(value);
    }

    public ByteBufferInputStream getBodyStream() {
        return bodyStream;
    }
}
