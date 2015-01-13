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

import jersey.repackaged.com.google.common.util.concurrent.SettableFuture;
import org.glassfish.jersey.client.*;
import org.glassfish.jersey.client.spi.AsyncConnectorCallback;
import org.glassfish.jersey.client.spi.Connector;
import org.glassfish.jersey.message.internal.OutboundMessageContext;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.ws.rs.client.Client;
import javax.ws.rs.core.Configuration;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Petr Janouch (petr.janouch at oracle.com)
 */
class JdkConnector implements Connector {

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

    private static final int DEFAULT_MAX_HEADER_SIZE = Integer.MAX_VALUE;

    private static final Logger LOGGER = Logger.getLogger(JdkConnector.class.getName());

    private final boolean fixLengthStreaming;
    private final int chunkSize;
    private final Configuration config;

    JdkConnector(Configuration config, boolean fixLengthStreaming, int chunkSize) {
        this.fixLengthStreaming = fixLengthStreaming;
        this.chunkSize = chunkSize;
        this.config = config;
    }

    @Override
    public ClientResponse apply(ClientRequest request) {

        Future<?> future = apply(request, new AsyncConnectorCallback() {
            @Override
            public void response(ClientResponse response) {

            }

            @Override
            public void failure(Throwable failure) {

            }
        });

        try {
            return (ClientResponse)future.get();
        } catch (Exception e) {
            // TODO
            throw new RuntimeException(e);
        }
    }

    @Override
    public Future<?> apply(final ClientRequest request, AsyncConnectorCallback callback) {
        SettableFuture<ClientResponse> responseFuture = SettableFuture.create();
        Filter<HttpRequest, HttpResponse, ?, ?> httpConnection = createHttpConnection(request, callback, responseFuture);

        final Object entity = request.getEntity();
        HttpRequest httpRequest;
        if (entity != null) {
            final CountDownLatch writeLatch = new CountDownLatch(1);
            HttpRequest.OutputStreamListener outputListener = new HttpRequest.OutputStreamListener() {

                @Override
                public void onReady(final OutputStream outputStream) {
                    request.setStreamProvider(new OutboundMessageContext.StreamProvider() {

                        @Override
                        public OutputStream getOutputStream(int contentLength) throws IOException {
                            return outputStream;
                        }
                    });
                    writeLatch.countDown();
                }
            };

            RequestEntityProcessing entityProcessing = request.resolveProperty(
                    ClientProperties.REQUEST_ENTITY_PROCESSING, RequestEntityProcessing.class);


            if (entityProcessing == null || entityProcessing != RequestEntityProcessing.BUFFERED) {
                final int length = request.getLength();
                if (fixLengthStreaming && length > 0) {
                    httpRequest = HttpRequest.createStreamed(request.getMethod(), request.getUri().toString(), request.getStringHeaders(), length, outputListener);
                } else if (entityProcessing == RequestEntityProcessing.CHUNKED) {
                    httpRequest = HttpRequest.createChunked(request.getMethod(), request.getUri().toString(), request.getStringHeaders(), chunkSize, outputListener);
                } else {
                    throw new IllegalStateException();
                }
            } else {
                httpRequest = HttpRequest.createBuffered(request.getMethod(), request.getUri().toString(), request.getStringHeaders(), outputListener);
            }

            httpConnection.write(httpRequest, new CompletionHandler<HttpRequest>() {
                @Override
                public void failed(Throwable throwable) {
                    super.failed(throwable);
                }
            });

            try {
                writeLatch.await();
                request.writeEntity();
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (Exception e) {
                //
            }


        } else {
            httpRequest = HttpRequest.createBodyless(request.getMethod(), request.getUri().toString(), request.getStringHeaders());
            httpConnection.write(httpRequest, new CompletionHandler<HttpRequest>() {
                @Override
                public void failed(Throwable throwable) {
                    super.failed(throwable);
                }
            });
        }

        return null;
    }

    private Filter<HttpRequest, HttpResponse, ?, ?> createHttpConnection(final ClientRequest request, final AsyncConnectorCallback callback, final SettableFuture<ClientResponse> responseFuture) {

        Map<String, Object> properties = config.getProperties();
        ThreadPoolConfig threadPoolConfig = ClientProperties.getValue(properties, JdkConnectorProvider.WORKER_THREAD_POOL_CONFIG, ThreadPoolConfig.defaultConfig(), ThreadPoolConfig.class);
        threadPoolConfig.setCorePoolSize(ClientProperties.getValue(properties, ClientProperties.ASYNC_THREADPOOL_SIZE, threadPoolConfig.getCorePoolSize(), Integer.class));
        Integer containerIdleTimeout = ClientProperties.getValue(properties, JdkConnectorProvider.CONTAINER_IDLE_TIMEOUT, Integer.class);


        URI uri = request.getUri();

        List<Proxy> proxies = processProxy(properties, uri);
        boolean secure = "https".equalsIgnoreCase(uri.getScheme());

        Filter<ByteBuffer, ByteBuffer, ?, ?> socket;
        if (secure) {
            TransportFilter transportFilter = new TransportFilter(SSL_INPUT_BUFFER_SIZE, threadPoolConfig, containerIdleTimeout);
            JerseyClient client = request.getClient();
            SSLContext sslContext = client.getSslContext();;
            if (sslContext == null) {
                //TODO
            }
            HostnameVerifier hostnameVerifier = client.getHostnameVerifier();
            socket = new SslFilter(transportFilter, sslContext, uri.getHost(), hostnameVerifier);
        } else {
            socket = new TransportFilter(INPUT_BUFFER_SIZE, threadPoolConfig, containerIdleTimeout);
        }

        Integer maxHeaderSize = ClientProperties.getValue(properties, JdkConnectorProvider.CONTAINER_IDLE_TIMEOUT, DEFAULT_MAX_HEADER_SIZE, Integer.class);
        final HttpFilter httpFilter = new HttpFilter(socket, maxHeaderSize + INPUT_BUFFER_SIZE);

        final AtomicBoolean waitingForReply = new AtomicBoolean(true);
        final Filter<?, ?, HttpRequest, HttpResponse> connection = new Filter<Void, Void, HttpRequest, HttpResponse>(httpFilter) {

            @Override
            boolean processRead(HttpResponse data) {
                waitingForReply.set(false);
                ClientResponse clientResponse = translate(request, data);
                callback.response(clientResponse);
                responseFuture.set(clientResponse);
                return false;
            }

            @Override
            void processConnectionClosed() {
                if (waitingForReply.get()) {
                    IOException closeException = new IOException("Connection closed by the server");
                    callback.failure(closeException);
                    responseFuture.setException(closeException);
                }

                waitingForReply.set(false);
            }

            @Override
            void processError(Throwable t) {
                waitingForReply.set(false);
                callback.failure(t);
                responseFuture.setException(t);
            }
        };

        connection.connect(new InetSocketAddress(request.getUri().getHost(), 8080), null);
        return httpFilter;
    }

    private ClientResponse translate(final ClientRequest requestContext, final HttpResponse httpResponse) {

        final ClientResponse responseContext = new ClientResponse(new Response.StatusType() {
            @Override
            public int getStatusCode() {
                return httpResponse.getStatusCode();
            }

            @Override
            public Response.Status.Family getFamily() {
                return Response.Status.Family.familyOf(httpResponse.getStatusCode());
            }

            @Override
            public String getReasonPhrase() {
                return httpResponse.getReasonPhrase();
            }
        }, requestContext);

        Map<String, List<String>> headers = httpResponse.getHeaders();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            for (String value : entry.getValue()) {
                responseContext.getHeaders().add(entry.getKey(), value);
            }
        }

        responseContext.setEntityStream(httpResponse.getBodyStream());

        return responseContext;
    }

    @Override
    public String getName() {
        return "JDK connector";
    }

    @Override
    public void close() {

    }
}
