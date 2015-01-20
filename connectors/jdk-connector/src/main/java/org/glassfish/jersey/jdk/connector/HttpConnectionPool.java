package org.glassfish.jersey.jdk.connector;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
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

    private final Map<HttpConnection.Key, Deque<HttpConnection>> available = new ConcurrentHashMap<>();
    private final Set<HttpConnection> openConnections = Collections.newSetFromMap(new ConcurrentHashMap<HttpConnection, Boolean>());


    HttpConnectionPool(int maxOpenTotal, int maxOpenPerDestination, ThreadPoolConfig threadPoolConfig, Integer containerIdleTimeout, int maxHeaderSize, SSLContext sslContext, HostnameVerifier hostnameVerifier, int connectionTimeout) {
        this.maxOpenTotal = maxOpenTotal;
        this.maxOpenPerDestination = maxOpenPerDestination;
        this.threadPoolConfig = threadPoolConfig;
        this.containerIdleTimeout = containerIdleTimeout;
        this.maxHeaderSize = maxHeaderSize;
        this.sslContext = sslContext;
        this.hostnameVerifier = hostnameVerifier;
        this.connectionTimeout = connectionTimeout;
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
        final HttpConnection connection = new HttpConnection(uri, sslContext, hostnameVerifier, maxHeaderSize, threadPoolConfig, containerIdleTimeout, connectionTimeout, scheduler, new HttpConnection.Listener() {
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
