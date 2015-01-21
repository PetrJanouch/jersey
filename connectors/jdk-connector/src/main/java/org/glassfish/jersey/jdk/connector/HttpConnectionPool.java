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

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import java.net.CookieManager;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

/**
 * Created by petr on 16/01/15.
 */
class HttpConnectionPool {

    // TODO better solution, containers won't like this
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r);
            thread.setName("tyrus-jdk-container-idle-timeout");
            thread.setDaemon(true);
            return thread;
        }
    });

    private final int maxOpenTotal;
    private final int maxOpenPerDestination;
    private final ThreadPoolConfig threadPoolConfig;
    private final Integer containerIdleTimeout;
    private final int maxHeaderSize;
    private final SSLContext sslContext;
    private final HostnameVerifier hostnameVerifier;
    private final int connectionTimeout;
    private final CookieManager cookieManager;

    private final Map<HttpConnection.Key, Deque<HttpConnection>> available = new ConcurrentHashMap<>();
    private final Set<HttpConnection> openConnections = Collections.newSetFromMap(new ConcurrentHashMap<HttpConnection, Boolean>());


    HttpConnectionPool(int maxOpenTotal, int maxOpenPerDestination, ThreadPoolConfig threadPoolConfig, Integer containerIdleTimeout, int maxHeaderSize, SSLContext sslContext, HostnameVerifier hostnameVerifier, int connectionTimeout, CookieManager cookieManager) {
        this.maxOpenTotal = maxOpenTotal;
        this.maxOpenPerDestination = maxOpenPerDestination;
        this.threadPoolConfig = threadPoolConfig;
        this.containerIdleTimeout = containerIdleTimeout;
        this.maxHeaderSize = maxHeaderSize;
        this.sslContext = sslContext;
        this.hostnameVerifier = hostnameVerifier;
        this.connectionTimeout = connectionTimeout;
        this.cookieManager = cookieManager;
    }

    void execute(final HttpRequest request, final CompletionHandler<HttpResponse> completionHandler) {
        HttpConnection.Key key = new HttpConnection.Key(request.getUri());

        Queue<HttpConnection> httpConnections = available.get(key);
        if (httpConnections == null || httpConnections.isEmpty()) {
            if (!canCreateConnection(key)) {
                // TODO

            }

            createConnection(request.getUri(), new ConnectListener() {
                @Override
                public void onConnect(HttpConnection connection) {
                    connection.execute(request, completionHandler);
                }
            });
        } else {
            HttpConnection connection = httpConnections.poll();
            connection.execute(request, completionHandler);
            removeFromPool(connection);
        }
    }

    void shutDown() {
        for (HttpConnection connection : openConnections) {
            connection.close();
        }
    }

    private void createConnection(URI uri, final ConnectListener connectListener) {
        final HttpConnection connection = new HttpConnection(uri, sslContext, hostnameVerifier, maxHeaderSize, threadPoolConfig, containerIdleTimeout, connectionTimeout, scheduler, cookieManager, new HttpConnection.Listener() {
            @Override
            public void onConnect(HttpConnection connection) {
                connectListener.onConnect(connection);
            }

            @Override
            public void onClose(HttpConnection connection) {
                openConnections.remove(connection);
                removeFromPool(connection);
            }

            @Override
            public void onCompleted(HttpConnection connection) {
                Deque<HttpConnection> httpConnections = available.get(connection.getKey());
                if (httpConnections == null) {
                    httpConnections = new LinkedList<>();
                    available.put(connection.getKey(), httpConnections);
                }

                httpConnections.addFirst(connection);
            }
        });

        openConnections.add(connection);
    }

    private void removeFromPool(HttpConnection connection) {
        Queue<HttpConnection> httpConnections = available.get(connection.getKey());
        if (httpConnections != null) {
            httpConnections.remove(connection);
        }
    }

    private boolean canCreateConnection(HttpConnection.Key key) {
        if (openConnections.size() >= maxOpenTotal) {
            return false;
        }

        Queue<HttpConnection> httpConnections = available.get(key);
        if (httpConnections == null) {
            return true;
        }

        if (httpConnections.size() < maxOpenPerDestination) {
            return true;
        }

        return false;
    }

    private static interface ConnectListener {

        void onConnect(HttpConnection connection);
    }
}
