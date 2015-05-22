/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2014 Oracle and/or its affiliates. All rights reserved.
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
public class HttpRequest {

    private static String TRANSFER_ENCODING_HEADER = "Transfer-Encoding";
    private static String TRANSFER_ENCODING_CHUNKED = "chunked";
    private static String HOST_HEADER = "Host";

    private final String method;
    private final URI uri;
    private final Map<String, List<String>> headers;
    private final BodyMode bodyMode;
    private final int chunkSize;
    private final WriteListener writeListener;
    private final NioOutputStream bodyStream;
    private final int bodySize;

    private HttpRequest(String method, URI uri, Map<String, List<String>> headers, BodyMode bodyMode, WriteListener writeListener, int bodySize, int chunkSize, NioOutputStream bodyStream) {
        this.method = method;
        this.uri = uri;
        this.headers = headers;
        this.bodyMode =bodyMode;
        this.writeListener = writeListener;
        this.chunkSize = chunkSize;
        this.bodySize = bodySize;
        this.bodyStream = bodyStream;

        int port = Utils.getPort(uri);
        addHeaderIfNotPresent(HOST_HEADER, uri.getHost() + ":" + port);
    }

    public static HttpRequest createBodyless(String method, URI uri, Map<String, List<String>> headers) {
        HttpRequest httpRequest = new HttpRequest(method, uri, headers, BodyMode.NONE, null, 0, 0, null);
        httpRequest.addHeaderIfNotPresent(HttpHeaders.CONTENT_LENGTH, Integer.toString(0));
        return httpRequest;
    }

    public static HttpRequest createStreamed(String method, URI uri, Map<String, List<String>> headers, int bodySize, WriteListener writeListener) {
        return new HttpRequest(method, uri, headers, BodyMode.STREAMING, writeListener, bodySize, 0, null);
    }

    public static HttpRequest createChunked(String method, URI uri, Map<String, List<String>> headers, int chunkSize, WriteListener writeListener) {
        HttpRequest httpRequest = new HttpRequest(method, uri, headers, BodyMode.CHUNKED, writeListener, 0, chunkSize, null);
        httpRequest.addHeaderIfNotPresent(TRANSFER_ENCODING_HEADER, TRANSFER_ENCODING_CHUNKED);
        return httpRequest;
    }

    public static HttpRequest createBuffered(String method, URI uri, Map<String, List<String>> headers, WriteListener writeListener) throws IOException {
        HttpRequest httpRequest = new HttpRequest(method, uri, headers, BodyMode.BUFFERED, writeListener, 0, 0, new BufferedBodyOutputStream());
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
        return ((BufferedBodyOutputStream)bodyStream).toBuffer();
    }

    public WriteListener getWriteListener() {
        return writeListener;
    }

    public NioOutputStream getBodyStream() {
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

    int getBodySize() {
        return bodySize;
    }

    enum BodyMode {
        NONE,
        STREAMING,
        CHUNKED,
        BUFFERED
    }
}
