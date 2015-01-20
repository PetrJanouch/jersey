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
import org.glassfish.jersey.SslConfigurator;
import org.glassfish.jersey.client.*;
import org.glassfish.jersey.client.spi.AsyncConnectorCallback;
import org.glassfish.jersey.client.spi.Connector;
import org.glassfish.jersey.message.internal.OutboundMessageContext;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.ws.rs.HEAD;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.client.Client;
import javax.ws.rs.core.Configuration;
import javax.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Petr Janouch (petr.janouch at oracle.com)
 */
class JdkConnector implements Connector {

    private static final int DEFAULT_MAX_HEADER_SIZE = 100_000;


    private static final Logger LOGGER = Logger.getLogger(JdkConnector.class.getName());

    private final boolean fixLengthStreaming;
    private final int chunkSize;
    private final HttpConnectionPool httpConnectionPool;
    private final boolean followRedirects = true;

    JdkConnector(Client client, Configuration config, boolean fixLengthStreaming, int chunkSize) {
        this.fixLengthStreaming = fixLengthStreaming;
        this.chunkSize = chunkSize;

        Map<String, Object> properties = config.getProperties();
        ThreadPoolConfig threadPoolConfig = ClientProperties.getValue(properties, JdkConnectorProvider.WORKER_THREAD_POOL_CONFIG, ThreadPoolConfig.defaultConfig(), ThreadPoolConfig.class);
        threadPoolConfig.setCorePoolSize(ClientProperties.getValue(properties, ClientProperties.ASYNC_THREADPOOL_SIZE, threadPoolConfig.getCorePoolSize(), Integer.class));
        Integer containerIdleTimeout = ClientProperties.getValue(properties, JdkConnectorProvider.CONTAINER_IDLE_TIMEOUT, Integer.class);

        Integer maxHeaderSize = ClientProperties.getValue(properties, JdkConnectorProvider.MAX_HEADER_SIZE, DEFAULT_MAX_HEADER_SIZE, Integer.class);

        SSLContext sslContext = client.getSslContext();
        if (sslContext == null) {
            sslContext = SslConfigurator.getDefaultContext();
        }

        HostnameVerifier hostnameVerifier = client.getHostnameVerifier();
        httpConnectionPool = new HttpConnectionPool(100, 20, threadPoolConfig, containerIdleTimeout, maxHeaderSize, sslContext, hostnameVerifier, 30);
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
            throw new ProcessingException(e);
        }
    }

    @Override
    public Future<?> apply(final ClientRequest request, final AsyncConnectorCallback callback) {
        final SettableFuture<ClientResponse> responseFuture = SettableFuture.create();

        final Object entity = request.getEntity();
        HttpRequest httpRequest;
        if (entity != null) {
            HttpRequest.OutputStreamListener outputListener = new HttpRequest.OutputStreamListener() {

                @Override
                public void onReady(final OutputStream outputStream) {
                    request.setStreamProvider(new OutboundMessageContext.StreamProvider() {

                        @Override
                        public OutputStream getOutputStream(int contentLength) throws IOException {
                            return outputStream;
                        }
                    });
                    try {
                        request.writeEntity();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            };

            RequestEntityProcessing entityProcessing = request.resolveProperty(
                    ClientProperties.REQUEST_ENTITY_PROCESSING, RequestEntityProcessing.class);

            final int length = request.getLength();
            if (entityProcessing == null && fixLengthStreaming && length > 0) {
                httpRequest = HttpRequest.createStreamed(request.getMethod(), request.getUri(), translateHeaders(request), length, outputListener);
            } else if (entityProcessing != null && entityProcessing == RequestEntityProcessing.CHUNKED) {
                httpRequest = HttpRequest.createChunked(request.getMethod(), request.getUri(), translateHeaders(request), chunkSize, outputListener);
            } else {
                httpRequest = HttpRequest.createBuffered(request.getMethod(), request.getUri(), translateHeaders(request), outputListener);
            }

        } else {
            httpRequest = HttpRequest.createBodyless(request.getMethod(), request.getUri(), translateHeaders(request));
        }

        final RedirectHandler redirectHandler = new RedirectHandler(followRedirects, request.getUri(), httpConnectionPool, request.getMethod(), translateHeaders(request));
        httpConnectionPool.execute(httpRequest, new CompletionHandler<HttpResponse>() {

            @Override
            public void failed(Throwable throwable) {
                callback.failure(throwable);
                responseFuture.setException(throwable);
            }

            @Override
            public void completed(HttpResponse result) {
                // recursively follow redirects - stop the recursion when the final response arrives
                if (!redirectHandler.handleRedirects(result, this)) {
                    return;
                }
                ClientResponse response = translate(request, result, redirectHandler.getLastRequestUri());
                callback.response(response);
                responseFuture.set(response);
            }
        });

        return responseFuture;
    }

    private Map<String, List<String>> translateHeaders(ClientRequest clientRequest) {
        Map<String, List<String>> headers = new HashMap<>();
        for (Map.Entry<String, List<String>> header : clientRequest.getStringHeaders().entrySet()) {
            List<String> values = new ArrayList<>(header.getValue());
            headers.put(header.getKey(), values);
        }

        return headers;
    }

    private ClientResponse translate(final ClientRequest requestContext, final HttpResponse httpResponse, URI requestUri) {

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
        }, requestContext, requestUri);

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
