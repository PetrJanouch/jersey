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

import java.io.IOException;
import java.net.CookieManager;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLContext;

import org.glassfish.jersey.SslConfigurator;

/**
 * Created by petr on 14/08/15.
 */
public class HttpConnection {

    /**
     * Input buffer that is used by {@link org.glassfish.jersey.jdk.connector.TransportFilter} when SSL is turned on.
     * The size cannot be smaller than a maximal size of a SSL packet, which is 16kB for payload + header, because
     * {@link org.glassfish.jersey.jdk.connector.SslFilter} does not have its own buffer for buffering incoming
     * data and therefore the entire SSL packet must fit into {@link org.glassfish.jersey.jdk.connector.SslFilter}
     * input buffer.
     */
    private static final int SSL_INPUT_BUFFER_SIZE = 17_000;
    /**
     * Input buffer that is used by {@link org.glassfish.jersey.jdk.connector.TransportFilter} when SSL is not turned on.
     */
    private static final int INPUT_BUFFER_SIZE = 2048;

    private final Filter<HttpRequest, HttpResponse, HttpRequest, HttpResponse> filterChain;
    private final CookieManager cookieManager;
    private final URI uri;
    private final ConnectionStateListener stateListener;

    private HttpRequest httpRequest;
    private HttpResponse httResponse;
    private Throwable error;
    private State state = State.CREATED;

    public HttpConnection(URI uri, CookieManager cookieManager, ConnectorConfiguration configuration, ConnectionStateListener stateListener) {
        this.uri = uri;
        this.cookieManager = cookieManager;
        this.stateListener = stateListener;
        filterChain = createFilterChain(uri, configuration);
    }

    void connect() {
        changeState(State.CONNECTING);
        filterChain.connect(new InetSocketAddress(uri.getHost(), Utils.getPort(uri)), null);
    }

    void send(HttpRequest httpRequest) {
        if (state != State.IDLE) {
            throw new IllegalStateException("Http request cannot be sent over a connection that is in other state than IDLE. Current state: " + state);
        }

        this.httpRequest = httpRequest;
        // clean state left by previous request
        httResponse = null;
        error = null;
        changeState(State.SENDING_REQUEST);

        addHeaders();

        filterChain.write(httpRequest, new CompletionHandler<HttpRequest>() {
            @Override
            public void failed(Throwable throwable) {
                error = throwable;
                changeState(State.ERROR);
            }

            @Override
            public void completed(HttpRequest result) {
                changeState(State.RECEIVING_HEADER);
            }
        });
    }

    private void addHeaders() {
        httpRequest.addHeaderIfNotPresent("Connection", "keep-alive");
        Map<String, List<String>> cookies;
        try {
            cookies = cookieManager.get(httpRequest.getUri(), httpRequest.getHeaders());
        } catch (IOException e) {
            changeState(State.ERROR);
            return;
        }
        httpRequest.getHeaders().putAll(cookies);
    }

    private ConnectionFilter createFilterChain(URI uri, ConnectorConfiguration configuration) {
        boolean secure = "https".equals(uri.getScheme());
        Filter<ByteBuffer, ByteBuffer, ?, ?> socket;
        if (secure) {
            SSLContext sslContext = configuration.getSslContext();
            TransportFilter transportFilter = new TransportFilter(SSL_INPUT_BUFFER_SIZE, configuration.getThreadPoolConfig(), configuration.getContainerIdleTimeout());
            if (sslContext == null) {
                sslContext = SslConfigurator.getDefaultContext();

            }
            socket = new SslFilter(transportFilter, sslContext, uri.getHost(), configuration.getHostnameVerifier());
        } else {
            socket = new TransportFilter(INPUT_BUFFER_SIZE, configuration.getThreadPoolConfig(), configuration.getContainerIdleTimeout());
        }

        int maxHeaderSize = configuration.getMaxHeaderSize();
        HttpFilter httpFilter = new HttpFilter(socket, maxHeaderSize, maxHeaderSize + INPUT_BUFFER_SIZE);
        return new ConnectionFilter(httpFilter);
    }

    private class ConnectionFilter extends Filter<HttpRequest, HttpResponse, HttpRequest, HttpResponse> {

        ConnectionFilter(Filter<HttpRequest, HttpResponse, ?, ?> downstreamFilter) {
            super(downstreamFilter);
        }

        @Override
        boolean processRead(HttpResponse response) {
            try {
                cookieManager.put(httpRequest.getUri(), response.getHeaders());
            } catch (IOException e) {
                error = e;
                changeState(State.ERROR);
                return false;
            }

            if (expectingBody()) {
                changeState(State.RECEIVING_BODY);
            } else {
                changeState(State.IDLE);
            }
            return false;
        }

        @Override
        void processConnect() {
            downstreamFilter.startSsl();
        }

        @Override
        void processSslHandshakeCompleted() {
            changeState(State.IDLE);
        }

        @Override
        void processConnectionClosed() {
            changeState(State.CLOSED);
        }

        @Override
        void processError(Throwable t) {
            error = t;
            changeState(State.ERROR);
        }
    }

    private boolean expectingBody() {
        // TODO
        return true;
    }

    private void changeState(State newState) {
        State old = state;
        state = newState;
        stateListener.onStateChanged(old, newState);
    }

    public static int getSslInputBufferSize() {
        return SSL_INPUT_BUFFER_SIZE;
    }

    State getState() {
        return state;
    }

    Throwable getError() {
        return error;
    }

    HttpResponse getHttResponse() {
        return httResponse;
    }

    HttpRequest getHttpRequest() {
        return httpRequest;
    }

    enum State {
        CREATED,
        CONNECTING,
        IDLE,
        SENDING_REQUEST,
        RECEIVING_HEADER,
        RECEIVING_BODY,
        CLOSED,
        ERROR
    }

    interface ConnectionStateListener {

        void onStateChanged(State oldState, State newState);
    }
}
