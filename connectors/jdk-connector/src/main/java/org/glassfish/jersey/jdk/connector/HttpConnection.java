package org.glassfish.jersey.jdk.connector;

import org.glassfish.jersey.SslConfigurator;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
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
    private final Listener listener;
    private final Key key;
    private final int connectionTimeout;
    private final ScheduledExecutorService scheduler;

    private volatile CompletionHandler<HttpResponse> responseCompletionHandler = null;
    private ScheduledFuture<?> scheduledClose;

    HttpConnection(URI uri, SSLContext sslContext, HostnameVerifier hostnameVerifier, int maxHeaderSize, ThreadPoolConfig threadPoolConfig, Integer containerIdleTimeout, int connectionTimeout, ScheduledExecutorService scheduler, final Listener listener) {
        this.listener = listener;
        this.connectionTimeout = connectionTimeout;
        this.scheduler = scheduler;
        //List<Proxy> proxies = processProxy(properties, uri);
        key = new Key(uri);

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

                CompletionHandler<HttpResponse> completionHandler = responseCompletionHandler;
                responseCompletionHandler = null;

                processReceivedHeaders(response);
                listener.onCompleted(HttpConnection.this);

                completionHandler.completed(response);
                return false;
            }

            @Override
            void processConnectionClosed() {
                if (responseCompletionHandler != null) {
                    IOException closeException = new IOException("Connection closed by the server");
                    responseCompletionHandler.failed(closeException);
                }

                if (scheduledClose != null) {
                    scheduledClose.cancel(true);
                }
                listener.onClose(HttpConnection.this);
            }

            @Override
            void processError(Throwable t) {
                if (responseCompletionHandler != null) {
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
                listener.onConnect(HttpConnection.this);
            }

            @Override
            void write(HttpRequest data, CompletionHandler<HttpRequest> completionHandler) {
                downstreamFilter.write(data, completionHandler);
            }
        };

        filterChain.connect(new InetSocketAddress(uri.getHost(), Utils.getPort(uri)), null);
    }

    void execute(HttpRequest httpRequest, final CompletionHandler<HttpResponse> responseCompletionHandler) {
        this.responseCompletionHandler = responseCompletionHandler;
        if (scheduledClose != null) {
            scheduledClose.cancel(true);
        }
        httpRequest.addHeaderIfNotPresent("Connection", "keep-alive");

        filterChain.write(httpRequest, new CompletionHandler<HttpRequest>() {
            @Override
            public void failed(Throwable throwable) {
                responseCompletionHandler.failed(throwable);
                HttpConnection.this.close();
            }
        });
    }

    void close() {
        filterChain.close();
        if (scheduledClose != null) {
            scheduledClose.cancel(true);
        }

        listener.onClose(this);
    }

    Key getKey() {
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

    static class Key {
        private final String host;
        private final int port;
        private final boolean secure;

        Key(URI uri) {
            host = uri.getHost();
            port = Utils.getPort(uri);
            secure = "https".equalsIgnoreCase(uri.getScheme());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Key that = (Key) o;

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

    interface Listener {

        void onConnect(HttpConnection connection);

        void onClose(HttpConnection connection);

        void onCompleted(HttpConnection connection);
    }
}
