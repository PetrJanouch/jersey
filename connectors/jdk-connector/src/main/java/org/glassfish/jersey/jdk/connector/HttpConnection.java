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

import org.glassfish.jersey.SslConfigurator;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.CookieManager;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Created by petr on 16/01/15.
 */
class HttpConnection {

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
    private final CloseListener listener;
    private final EndpointKey key;
    private final int connectionTimeout;
    private final ScheduledExecutorService scheduler;
    private final CookieManager cookieManager;

    private CompletionHandler<HttpConnection> connectCompletionHandler = null;
    private CompletionHandler<HttpResponse> responseCompletionHandler = null;
    private ScheduledFuture<?> scheduledClose;
    private URI requestUri;

    HttpConnection(URI uri, SSLContext sslContext, HostnameVerifier hostnameVerifier, int maxHeaderSize, ThreadPoolConfig threadPoolConfig, Integer containerIdleTimeout, int connectionTimeout, ScheduledExecutorService scheduler, final CookieManager cookieManager, final CloseListener listener) {
        this.listener = listener;
        this.connectionTimeout = connectionTimeout;
        this.scheduler = scheduler;
        this.cookieManager = cookieManager;
        //List<Proxy> proxies = processProxy(properties, uri);
        key = new EndpointKey(uri);

        boolean secure = "https".equals(uri.getScheme());
        Filter<ByteBuffer, ByteBuffer, ?, ?> socket;
        if (secure) {
            TransportFilter transportFilter = new TransportFilter(SSL_INPUT_BUFFER_SIZE, threadPoolConfig, containerIdleTimeout);
            if (sslContext == null) {
                sslContext = SslConfigurator.getDefaultContext();

            }
            socket = new SslFilter(transportFilter, sslContext, uri.getHost(), hostnameVerifier);
        } else {
            socket = new TransportFilter(INPUT_BUFFER_SIZE, threadPoolConfig, containerIdleTimeout);
        }

        final HttpFilter httpFilter = new HttpFilter(socket, maxHeaderSize, maxHeaderSize + INPUT_BUFFER_SIZE);

        filterChain = new Filter<HttpRequest, HttpResponse, HttpRequest, HttpResponse>(httpFilter) {

            @Override
            boolean processRead(HttpResponse response) {
                scheduleTimeout();

                try {
                    cookieManager.put(requestUri, response.getHeaders());
                } catch (IOException e) {
                    responseCompletionHandler.failed(e);
                }

                CompletionHandler<HttpResponse> completionHandler = responseCompletionHandler;
                responseCompletionHandler = null;

                processReceivedHeaders(response);
               // listener.onTaskCompleted(HttpConnection.this);

                completionHandler.completed(response);
                return false;
            }

            @Override
            void processConnectionClosed() {
                IOException closeException = new IOException("Connection closed by the server");

                if (connectCompletionHandler != null) {
                    connectCompletionHandler.failed(closeException);
                } else if (responseCompletionHandler != null) {
                    responseCompletionHandler.failed(closeException);
                }

                if (scheduledClose != null) {
                    scheduledClose.cancel(true);
                }
                listener.onClose(HttpConnection.this);
            }

            @Override
            void processError(Throwable t) {
                if (connectCompletionHandler != null) {
                    connectCompletionHandler.failed(t);
                } else if (responseCompletionHandler != null) {
                    responseCompletionHandler.failed(t);
                }

                HttpConnection.this.close();
            }

            @Override
            void processConnect() {
                downstreamFilter.startSsl();
            }

            @Override
            void processSslHandshakeCompleted() {
                connectCompletionHandler.completed(HttpConnection.this);
                connectCompletionHandler = null;
            }

            @Override
            void write(HttpRequest data, CompletionHandler<HttpRequest> completionHandler) {
                downstreamFilter.write(data, completionHandler);
            }
        };
    }

    void execute(HttpRequest httpRequest, final CompletionHandler<HttpResponse> responseCompletionHandler) {
        this.responseCompletionHandler = responseCompletionHandler;
        this.requestUri = httpRequest.getUri();
        if (scheduledClose != null) {
            scheduledClose.cancel(true);
        }
        httpRequest.addHeaderIfNotPresent("Connection", "keep-alive");
        Map<String, List<String>> cookies;
        try {
            cookies = cookieManager.get(requestUri, httpRequest.getHeaders());
        } catch (IOException e) {
            responseCompletionHandler.failed(e);
            return;
        }
        httpRequest.getHeaders().putAll(cookies);

        filterChain.write(httpRequest, new CompletionHandler<HttpRequest>() {
            @Override
            public void failed(Throwable throwable) {

                responseCompletionHandler.failed(throwable);
                HttpConnection.this.close();
            }
        });
    }

    void connect(URI uri, CompletionHandler<HttpConnection> completionHandler) {
        filterChain.connect(new InetSocketAddress(uri.getHost(), Utils.getPort(uri)), null);
        this.connectCompletionHandler = completionHandler;
    }

    void close() {
        filterChain.close();
        if (scheduledClose != null) {
            scheduledClose.cancel(true);
        }

        listener.onClose(this);
    }

    EndpointKey getKey() {
        return key;
    }

    private void processReceivedHeaders(HttpResponse response) {
        List<String> connectionValues = response.removeHeader("Connection");

        if (connectionValues == null) {
            return;
        }

        for (String value : connectionValues) {
            if ("close".equalsIgnoreCase(value)) {
                close();
                return;
            }

            //TODO Keep-Alive: timeout:60
        }

    }

    private void scheduleTimeout() {
        if (scheduledClose != null) {
            scheduledClose.cancel(true);
        }
        scheduledClose = scheduler.schedule(new Runnable() {

            @Override
            public void run() {
                close();
                listener.onClose(HttpConnection.this);
            }
        }, connectionTimeout, TimeUnit.SECONDS);
    }

    static class EndpointKey {
        private final String host;
        private final int port;
        private final boolean secure;

        EndpointKey(URI uri) {
            host = uri.getHost();
            port = Utils.getPort(uri);
            secure = "https".equalsIgnoreCase(uri.getScheme());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            EndpointKey that = (EndpointKey) o;

            if (port != that.port) return false;
            if (secure != that.secure) return false;
            if (host != null ? !host.equals(that.host) : that.host != null) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = host != null ? host.hashCode() : 0;
            result = 31 * result + port;
            result = 31 * result + (secure ? 1 : 0);
            return result;
        }
    }

    interface CloseListener {

        void onClose(HttpConnection connection);
    }
}
