/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2015 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package org.glassfish.jersey.jdk.connector;

import javax.ws.rs.core.HttpHeaders;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Created by petr on 10/12/14.
 */
class HttpRequest {

    private static String TRANSFER_ENCODING_HEADER = "Transfer-Encoding";
    private static String TRANSFER_ENCODING_CHUNKED = "chunked";
    private static String HOST_HEADER = "Host";

    private final String method;
    private final URI uri;
    private final Map<String, List<String>> headers;
    private final BodyMode bodyMode;
    private final BodyOutputStream bodyStream;
    private final int bodySize;


    private HttpRequest(String method, URI uri, Map<String, List<String>> headers, BodyMode bodyMode, BodyOutputStream
            bodyStream, int bodySize) {
        this.method = method;
        this.uri = uri;
        this.headers = headers;
        this.bodyMode =bodyMode;
        this.bodyStream = bodyStream;
        this.bodySize = bodySize;

        int port = Utils.getPort(uri);
        addHeaderIfNotPresent(HOST_HEADER, uri.getHost() + ":" + port);
    }

    static HttpRequest createBodyless(String method, URI uri, Map<String, List<String>> headers) {
        HttpRequest httpRequest = new HttpRequest(method, uri, headers, BodyMode.NONE, null, -1);
        httpRequest.addHeaderIfNotPresent(HttpHeaders.CONTENT_LENGTH, Integer.toString(0));
        return httpRequest;
    }

    static HttpRequest createStreamed(String method, URI uri, Map<String, List<String>> headers, int bodySize) {
        StreamedBodyOutputStream bodyStream = new StreamedBodyOutputStream(100, bodySize);
        HttpRequest httpRequest = new HttpRequest(method, uri, headers, BodyMode.STREAMING, bodyStream, bodySize);
        httpRequest.addHeaderIfNotPresent(HttpHeaders.CONTENT_LENGTH, Integer.toString(bodySize));
        return httpRequest;
    }

    static HttpRequest createChunked(String method, URI uri, Map<String, List<String>> headers, int chunkSize) {
        ChunkedBodyOutputStream bodyStream = new ChunkedBodyOutputStream(chunkSize);
        HttpRequest httpRequest = new HttpRequest(method, uri, headers, BodyMode.CHUNKED, bodyStream, -1);
        httpRequest.addHeaderIfNotPresent(TRANSFER_ENCODING_HEADER, TRANSFER_ENCODING_CHUNKED);
        return httpRequest;
    }

    static HttpRequest createBuffered(String method, URI uri, Map<String, List<String>> headers) {
        BufferedBodyOutputStream bodyOutputStream = new BufferedBodyOutputStream();
        HttpRequest httpRequest = new HttpRequest(method, uri, headers, BodyMode.BUFFERED, bodyOutputStream, -1);
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

    BodyOutputStream getBodyStream() {
        if (BodyMode.NONE == bodyMode) {
            throw new IllegalStateException("This HTTP request does not have a body");
        }

        return bodyStream;
    }

    void addHeaderIfNotPresent(String name, String value) {
        List<String> values = headers.get(name);
        if (values == null) {
            values = new ArrayList<>(1);
            headers.put(name, values);
            values.add(value);
        }
    }

    ByteBuffer getBufferedBody() {
        if (BodyMode.BUFFERED != bodyMode) {
            throw new IllegalStateException("Buffered Body is available only in buffered body mode");
        }

        return ((BufferedBodyOutputStream) bodyStream).toBuffer();
    }

    int getBodySize() {
        if (bodyMode == BodyMode.CHUNKED) {
            throw new IllegalStateException("Body size is not available in chunked body mode");
        }

        if (BodyMode.STREAMING == bodyMode) {
            return bodySize;
        }

        return ((BufferedBodyOutputStream) bodyStream).toBuffer().remaining();
    }

    enum BodyMode {
        NONE,
        STREAMING,
        CHUNKED,
        BUFFERED
    }
}
