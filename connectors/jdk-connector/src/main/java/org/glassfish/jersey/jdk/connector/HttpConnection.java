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
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
    private final StateChangeListener stateListener;
    private final ScheduledExecutorService scheduler;
    private final ConnectorConfiguration configuration;

    private HttpRequest httpRequest;
    private HttpResponse httResponse;
    private Throwable error;
    private State state = State.CREATED;

    private Future<?> responseTimeout;
    private Future<?> idleTimeout;
    private Future<?> connectTimeout;

    public HttpConnection(URI uri, CookieManager cookieManager, ConnectorConfiguration configuration, ScheduledExecutorService scheduler, StateChangeListener stateListener) {
        this.uri = uri;
        this.cookieManager = cookieManager;
        this.stateListener = stateListener;
        this.configuration = configuration;
        this.scheduler = scheduler;
        filterChain = createFilterChain(uri, configuration);
    }

    void connect() {
        if (state != State.CREATED) {
            throw new IllegalStateException("Cannot try to establish connection if the connection is in other than CREATED state, current state: " + state);
        }

        changeState(State.CONNECTING);
        scheduleConnectTimeout();
        filterChain.connect(new InetSocketAddress(uri.getHost(), Utils.getPort(uri)), null);
    }

    void send(HttpRequest httpRequest) {
        if (state != State.IDLE) {
            throw new IllegalStateException("Http request cannot be sent over a connection that is in other state than IDLE. Current state: " + state);
        }

        cancelIdleTimeout();

        this.httpRequest = httpRequest;
        // clean state left by previous request
        httResponse = null;
        error = null;
        changeState(State.SENDING_REQUEST);

        addHeaders();

        filterChain.write(httpRequest, new CompletionHandler<HttpRequest>() {
            @Override
            public void failed(Throwable throwable) {
                handleError(throwable);
            }

            @Override
            public void completed(HttpRequest result) {
                scheduleResponseTimeout();
                changeState(State.RECEIVING_HEADER);
            }
        });
    }

    void close() {
        if (state == State.CLOSED) {
            return;
        }

        cancelAllTimeouts();
        filterChain.close();
        changeState(State.CLOSED);
    }

    private void addHeaders() {
        httpRequest.addHeaderIfNotPresent("Connection", "keep-alive");
        Map<String, List<String>> cookies;
        try {
            cookies = cookieManager.get(httpRequest.getUri(), httpRequest.getHeaders());
        } catch (IOException e) {
            handleError(e);
            return;
        }
        httpRequest.getHeaders().putAll(cookies);
    }

    protected Filter<HttpRequest, HttpResponse, HttpRequest, HttpResponse> createFilterChain(URI uri, ConnectorConfiguration configuration) {
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

    private boolean expectingBody() {
        // TODO
        return true;
    }

    private void changeState(State newState) {
        State old = state;
        state = newState;
        stateListener.onStateChanged(this, old, newState);
    }

    private void scheduleResponseTimeout() {
        if (configuration.getResponseTimeout() == 0) {
            return;
        }

        responseTimeout = scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                if (state != State.RECEIVING_HEADER && state != State.RECEIVING_BODY) {
                    return;
                }

                responseTimeout = null;
                changeState(State.RESPONSE_TIMEOUT);

            }
        }, configuration.getResponseTimeout(), TimeUnit.MILLISECONDS);
    }

    private void cancelResponseTimeout() {
        if (responseTimeout != null) {
            responseTimeout.cancel(true);
            responseTimeout = null;
        }
    }

    private void scheduleConnectTimeout() {
        if (configuration.getConnectTimeout() == 0) {
            return;
        }

        connectTimeout = scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                if (state != State.CONNECTING) {
                    return;
                }

                connectTimeout = null;
                changeState(State.CONNECT_TIMEOUT);
            }
        }, configuration.getConnectTimeout(), TimeUnit.MILLISECONDS);
    }

    private void cancelConnectTimeout() {
        if (connectTimeout != null) {
            connectTimeout.cancel(true);
            connectTimeout = null;
        }
    }

    private void scheduleIdleTimeout() {
        if (configuration.getConnectionIdleTimeout() == 0) {
            return;
        }

        idleTimeout = scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                if (state != State.IDLE) {
                    return;
                }

                idleTimeout = null;
                changeState(State.IDLE_TIMEOUT);
            }
        }, configuration.getConnectionIdleTimeout(), TimeUnit.MILLISECONDS);
    }

    private void cancelIdleTimeout() {
        if (idleTimeout != null) {
            idleTimeout.cancel(true);
            idleTimeout = null;
        }
    }

    private void cancelAllTimeouts() {
        cancelConnectTimeout();
        cancelIdleTimeout();
        cancelResponseTimeout();
    }

    private void handleError(Throwable t) {
        cancelConnectTimeout();
        error = t;
        changeState(State.ERROR);
        changeState(State.CLOSED);
    }

    private void changeStateToIdle() {
        scheduleIdleTimeout();
        changeState(State.IDLE);
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

    private class ConnectionFilter extends Filter<HttpRequest, HttpResponse, HttpRequest, HttpResponse> {

        ConnectionFilter(Filter<HttpRequest, HttpResponse, ?, ?> downstreamFilter) {
            super(downstreamFilter);
        }

        @Override
        boolean processRead(HttpResponse response) {
            if (state != State.RECEIVING_HEADER) {
                return false;
            }

            httResponse = response;

            try {
                cookieManager.put(httpRequest.getUri(), httResponse.getHeaders());
            } catch (IOException e) {
                handleError(e);
                return false;
            }

            if (expectingBody()) {
                AsynchronousBodyInputStream bodyStream = httResponse.getBodyStream();
                bodyStream.setStateChangeLister(new AsynchronousBodyInputStream.StateChangeLister() {
                    @Override
                    public void onError(Throwable t) {
                        handleError(t);
                    }

                    @Override
                    public void onAllDataRead() {
                        cancelResponseTimeout();
                        changeStateToIdle();
                    }
                });
                changeState(State.RECEIVING_BODY);
            } else {
                changeStateToIdle();
            }
            return false;
        }

        @Override
        void processConnect() {
            if (state != State.CONNECTING) {
                return;
            }
            downstreamFilter.startSsl();
        }

        @Override
        void processSslHandshakeCompleted() {
            if (state != State.CONNECTING) {
                return;
            }

            cancelConnectTimeout();
            changeStateToIdle();
        }

        @Override
        void processConnectionClosed() {
            cancelAllTimeouts();
            changeState(State.CLOSED_BY_SERVER);
            changeState(State.CLOSED);
        }

        @Override
        void processError(Throwable t) {
            handleError(t);
        }

        @Override
        void write(HttpRequest data, CompletionHandler<HttpRequest> completionHandler) {
            downstreamFilter.write(data, completionHandler);
        }
    }

    enum State {
        CREATED,
        CONNECTING,
        CONNECT_TIMEOUT,
        IDLE,
        SENDING_REQUEST,
        RECEIVING_HEADER,
        RECEIVING_BODY,
        RESPONSE_TIMEOUT,
        CLOSED_BY_SERVER,
        CLOSED,
        ERROR,
        IDLE_TIMEOUT
    }

    interface StateChangeListener {
        void onStateChanged(HttpConnection connection, State oldState, State newState);
    }
}
