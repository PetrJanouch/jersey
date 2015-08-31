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
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by petr on 17/08/15.
 */
public class DestinationConnectionPool {

    private final AtomicInteger totalConnectionCounter;
    private final AtomicInteger connectionCounter = new AtomicInteger(0);
    private final ConnectorConfiguration configuration;
    private final ConnectionCloseListener connectionCloseListener;
    private final Queue<HttpConnection> idleConnections = new ConcurrentLinkedDeque<>();
    private final Set<HttpConnection> connections = Collections.newSetFromMap(new ConcurrentHashMap<HttpConnection, Boolean>());
    private final Queue<RequestRecord> pendingRequests = new ConcurrentLinkedDeque<>();
    private final Map<HttpConnection, RequestRecord> requestsInProgress = new HashMap<>();
    private final CookieManager cookieManager;
    private final ScheduledExecutorService scheduler;
    private final ConnectionStateListener connectionStateListener;
    private boolean closed = false;

    public DestinationConnectionPool(AtomicInteger totalConnectionCounter, ConnectorConfiguration configuration, CookieManager cookieManager, ScheduledExecutorService scheduler, ConnectionCloseListener connectionCloseListener) {
        this.totalConnectionCounter = totalConnectionCounter;
        this.configuration = configuration;
        this.connectionCloseListener = connectionCloseListener;
        this.cookieManager = cookieManager;
        this.scheduler = scheduler;
        this.connectionStateListener = new ConnectionStateListener();
    }

    void send(HttpRequest httpRequest, CompletionHandler<HttpResponse> completionHandler) {
        HttpConnection connection = idleConnections.poll();

        if (connection != null) {
            requestsInProgress.put(connection, new RequestRecord(httpRequest, completionHandler));
            connection.send(httpRequest);
            return;
        }

        synchronized (this) {
            pendingRequests.add(new RequestRecord(httpRequest, completionHandler));
            if (configuration.getMaxConnectionsPerDestionation() == connectionCounter.get()) {
                return;
            }

            connection = new HttpConnection(httpRequest.getUri(), cookieManager, configuration, scheduler, connectionStateListener);
            connections.add(connection);
            totalConnectionCounter.incrementAndGet();
            connectionCounter.incrementAndGet();
        }

        connection.connect();
    }

    void close() {
        if (closed) {
            return;
        }

        closed = true;

        for (HttpConnection connection : connections) {
            connection.close();
        }
    }

    private RequestRecord getRequest(HttpConnection connection) {
        RequestRecord requestRecord = requestsInProgress.get(connection);
        if (requestRecord == null) {
            throw new IllegalStateException("TODO");
        }

        return requestRecord;
    }

    private RequestRecord removeRequest(HttpConnection connection) {
        RequestRecord requestRecord = requestsInProgress.get(connection);
        if (requestRecord == null) {
            throw new IllegalStateException("TODO");
        }

        return requestRecord;
    }

    private void handleIdleConnection(HttpConnection connection) {
        RequestRecord pendingRequest;

        synchronized (this) {
            pendingRequest = pendingRequests.poll();

            if (pendingRequest == null) {
                idleConnections.add(connection);
                return;
            }

            requestsInProgress.put(connection, pendingRequest);
        }

        connection.send(pendingRequest.request);
    }

    private synchronized void cleanClosedConnection(HttpConnection connection) {
        idleConnections.remove(connection);
        connections.remove(connection);
        connectionCounter.decrementAndGet();
        totalConnectionCounter.decrementAndGet();

        if (connectionCounter.get() == 0) {
            connectionCloseListener.onLastConnectionClosed();
        }
        connectionCloseListener.onConnectionClosed();
    }

    private void handleIllegalStateTransition(HttpConnection.State oldState, HttpConnection.State newState) {
        throw new IllegalStateException("Illegal state transition, old state: " + oldState + " new state: " + newState);
    }

    private class ConnectionStateListener implements HttpConnection.StateChangeListener {

        @Override
        public void onStateChanged(HttpConnection connection, HttpConnection.State oldState, HttpConnection.State newState) {
            switch (newState) {

                case IDLE: {
                    switch (oldState) {
                        case RECEIVING_HEADER: {
                            RequestRecord request = removeRequest(connection);
                            request.completionHandler.completed(connection.getHttResponse());
                            handleIdleConnection(connection);
                            return;
                        }

                        case RECEIVING_BODY: {
                            removeRequest(connection);
                            handleIdleConnection(connection);
                            return;
                        }

                        case CONNECTING: {
                            handleIdleConnection(connection);
                            return;
                        }

                        default: {
                            handleIllegalStateTransition(oldState, newState);
                        }
                    }
                }

                case RECEIVING_BODY: {
                    switch (oldState) {
                        case RECEIVING_HEADER: {
                            RequestRecord request = getRequest(connection);
                            request.response = connection.getHttResponse();
                            request.completionHandler.completed(connection.getHttResponse());
                            return;
                        }

                        default: {
                            handleIllegalStateTransition(oldState, newState);
                        }
                    }
                }

                case ERROR: {
                    switch (oldState) {
                        case SENDING_REQUEST: {
                            RequestRecord request = removeRequest(connection);
                            request.completionHandler.failed(new IOException("Exception sending request", connection.getError()));
                            return;
                        }

                        case RECEIVING_HEADER: {
                            RequestRecord request = removeRequest(connection);
                            request.completionHandler.failed(new IOException("Exception receiving response", connection.getError()));
                            return;
                        }

                        case RECEIVING_BODY: {
                            requestsInProgress.remove(connection);
                            return;
                        }

                        case CONNECTING: {
                            // TODO
                            for (RequestRecord requestRecord : pendingRequests) {
                                requestRecord.completionHandler.failed(new IOException("Connection error", connection.getError()));
                            }
                        }

                        default: {
                            connection.getError().printStackTrace();
                            handleIllegalStateTransition(oldState, newState);
                        }
                    }
                }


                case RESPONSE_TIMEOUT: {
                    switch (oldState) {
                        case RECEIVING_HEADER: {
                            RequestRecord request = removeRequest(connection);
                            request.completionHandler.failed(new IOException("Timeout receiving response", connection.getError()));
                            return;
                        }

                        case RECEIVING_BODY: {
                            RequestRecord request = requestsInProgress.remove(connection);
                            request.response.getBodyStream().onError(new IOException("Timeout receiving response body", connection.getError()));
                            return;
                        }

                        default: {
                            handleIllegalStateTransition(oldState, newState);
                        }
                    }
                }

                case CLOSED_BY_SERVER: {
                    switch (oldState) {
                        case SENDING_REQUEST: {
                            RequestRecord request = removeRequest(connection);
                            request.completionHandler.failed(new IOException("Connection closed by server while sending request", connection.getError()));
                            return;
                        }

                        case RECEIVING_HEADER: {
                            RequestRecord request = removeRequest(connection);
                            request.completionHandler.failed(new IOException("Connection closed by server while receiving response", connection.getError()));
                            return;
                        }

                        case RECEIVING_BODY: {
                            RequestRecord request = requestsInProgress.remove(connection);
                            request.response.getBodyStream().onError(new IOException("Connection closed by server while receiving response body", connection.getError()));
                            return;
                        }

                        case CONNECTING: {
                            // TODO
                            for (RequestRecord requestRecord : pendingRequests) {
                                requestRecord.completionHandler.failed(new IOException("Connection closed by server"));
                            }
                        }
                    }
                }

                case CLOSED: {
                    switch (oldState) {
                        case SENDING_REQUEST: {
                            RequestRecord request = removeRequest(connection);
                            request.completionHandler.failed(new IOException("Connection closed by client while sending request", connection.getError()));
                            cleanClosedConnection(connection);
                            return;
                        }

                        case RECEIVING_HEADER: {
                            RequestRecord request = removeRequest(connection);
                            request.completionHandler.failed(new IOException("Connection closed by client while receiving response", connection.getError()));
                            cleanClosedConnection(connection);
                            return;
                        }

                        case RECEIVING_BODY: {
                            RequestRecord request = requestsInProgress.remove(connection);
                            request.response.getBodyStream().onError(new IOException("Connection closed by client while receiving response body", connection.getError()));
                            cleanClosedConnection(connection);
                            return;
                        }

                        default: {
                            cleanClosedConnection(connection);
                        }
                    }
                }

                case CONNECT_TIMEOUT: {
                    switch (oldState) {
                        case CONNECTING: {
                            // TODO
                            for (RequestRecord requestRecord : pendingRequests) {
                                requestRecord.completionHandler.failed(new IOException("Connection timed out"));
                            }
                            return;
                        }

                        default: {
                            cleanClosedConnection(connection);
                        }
                    }
                }
            }
        }
    }

    private static class RequestRecord {
        private final HttpRequest request;
        private final CompletionHandler<HttpResponse> completionHandler;
        private HttpResponse response;

        public RequestRecord(HttpRequest request, CompletionHandler<HttpResponse> completionHandler) {
            this.request = request;
            this.completionHandler = completionHandler;
        }
    }

    static class DestinationKey {
        private final String host;
        private final int port;
        private final boolean secure;

        DestinationKey(URI uri) {
            host = uri.getHost();
            port = Utils.getPort(uri);
            secure = "https".equalsIgnoreCase(uri.getScheme());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            DestinationKey that = (DestinationKey) o;

            if (port != that.port) return false;
            if (secure != that.secure) return false;
            return host.equals(that.host);

        }

        @Override
        public int hashCode() {
            int result = host.hashCode();
            result = 31 * result + port;
            result = 31 * result + (secure ? 1 : 0);
            return result;
        }
    }

    interface ConnectionCloseListener {

        void onConnectionClosed();

        void onLastConnectionClosed();
    }
}
