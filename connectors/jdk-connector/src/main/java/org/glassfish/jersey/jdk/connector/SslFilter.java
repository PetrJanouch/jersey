package org.glassfish.jersey.jdk.connector;

import javax.net.ssl.*;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;

/**
 * A filter that adds SSL support to the transport.
 * <p/>
 * {@link #write(java.nio.ByteBuffer, org.glassfish.tyrus.spi.CompletionHandler)} and {@link #onRead(java.nio.ByteBuffer)}
 * calls are passed through until {@link #startSsl()} method is called, after which SSL handshake is started.
 * When SSL handshake is being initiated, all data passed in {@link #write(java.nio.ByteBuffer, org.glassfish.tyrus.spi.CompletionHandler)}
 * method are stored until SSL handshake completes, after which they will be encrypted and passed to a downstream filter.
 * After SSL handshake has completed, all data passed in write method will be encrypted and data passed in
 * {@link #onRead(java.nio.ByteBuffer)} method will be decrypted.
 *
 * @author Petr Janouch (petr.janouch at oracle.com)
 */
class SslFilter extends Filter <ByteBuffer, ByteBuffer, ByteBuffer, ByteBuffer>{

    private final ByteBuffer applicationInputBuffer;
    private final ByteBuffer networkOutputBuffer;
    private final SSLEngine sslEngine;
    private final HostnameVerifier customHostnameVerifier;
    private final String serverHost;

    /**
     * Lock to ensure that only one thread will work with {@link #sslEngine} state machine during the handshake phase.
     */
    private final Object handshakeLock = new Object();

    private volatile boolean sslStarted = false;
    private volatile boolean handshakeCompleted = false;

    /**
     * SSL Filter constructor, takes upstream filter as a parameter.
     *
     * @param downstreamFilter      a filter that is positioned under the SSL filter.
     * @param sslEngineConfigurator configuration of SSL engine.
     * @param serverHost            server host (hostname or IP address), which will be used to verify authenticity of
     *                              the server (the provided host will be compared against the host in the certificate
     *                              provided by the server). IP address and hostname cannot be used interchangeably -
     *                              if a certificate contains hostname and an IP address of the server is provided here,
     *                              the verification will fail.
     */
    SslFilter(Filter downstreamFilter, SSLContext sslContext, String serverHost, HostnameVerifier customHostnameVerifier) {
        super(downstreamFilter);
        this.serverHost = serverHost;
        sslEngine = sslContext.createSSLEngine(serverHost, -1);
        this.customHostnameVerifier = customHostnameVerifier;

        /**
         * Enable server host verification.
         * This can be moved to {@link SslEngineConfigurator} with the rest of {@link SSLEngine} configuration
         * when {@link SslEngineConfigurator} supports Java 7.
         */
        if (customHostnameVerifier == null) {
            SSLParameters sslParameters = sslEngine.getSSLParameters();
            sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
            sslEngine.setSSLParameters(sslParameters);
        }

        applicationInputBuffer = ByteBuffer.allocate(sslEngine.getSession().getApplicationBufferSize());
        networkOutputBuffer = ByteBuffer.allocate(sslEngine.getSession().getPacketBufferSize());
    }

    @Override
    void write(final ByteBuffer applicationData, final CompletionHandler<ByteBuffer> completionHandler) {
        // before SSL is started write just passes through
        if (!sslStarted) {
            downstreamFilter.write(applicationData, completionHandler);
            return;
        }

        handleWrite(networkOutputBuffer, applicationData, downstreamFilter, completionHandler);
    }

    private void handleWrite(final ByteBuffer networkOutputBuffer, final ByteBuffer applicationData, final Filter downstreamFilter,
                             final CompletionHandler<ByteBuffer> completionHandler) {
        try {
            networkOutputBuffer.clear();
            // TODO: check the result
            sslEngine.wrap(applicationData, networkOutputBuffer);
            networkOutputBuffer.flip();
            downstreamFilter.write(networkOutputBuffer, new CompletionHandler<ByteBuffer>() {
                @Override
                public void completed(ByteBuffer result) {
                    if (applicationData.hasRemaining()) {
                        handleWrite(networkOutputBuffer, applicationData, downstreamFilter, completionHandler);
                    } else {
                        completionHandler.completed(applicationData);
                    }
                }

                @Override
                public void failed(Throwable throwable) {
                    completionHandler.failed(throwable);
                }
            });
        } catch (SSLException e) {
            handleSslError(e);
        }
    }

    @Override
    void close() {
        if (!sslStarted) {
            downstreamFilter.close();
            return;
        }
        sslEngine.closeOutbound();
        write(networkOutputBuffer, new CompletionHandler<ByteBuffer>() {

            @Override
            public void completed(ByteBuffer result) {
                downstreamFilter.close();
                upstreamFilter = null;
            }

            @Override
            public void failed(Throwable throwable) {
                downstreamFilter.close();
                upstreamFilter = null;
            }
        });
    }

    @Override
    boolean processRead(ByteBuffer networkData) {

        // before SSL is started read just passes through
        if (!sslStarted) {
            return true;
        }
        SSLEngineResult.HandshakeStatus hs = sslEngine.getHandshakeStatus();
        try {
            // SSL handshake logic
            if (hs != SSLEngineResult.HandshakeStatus.FINISHED && hs != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                synchronized (handshakeLock) {

                    if (hs != SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
                        return false;
                    }

                    applicationInputBuffer.clear();

                    SSLEngineResult result;
                    while (true) {
                        result = sslEngine.unwrap(networkData, applicationInputBuffer);
                        if (result.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                            // needs more data from the network
                            return false;
                        }
                        if (result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED) {
                            handshakeCompleted = true;

                            // apply a custom host verifier if present
                            if (customHostnameVerifier != null && !customHostnameVerifier.verify(serverHost, sslEngine.getSession())) {
                                handleSslError(new SSLException("Server host name verification using " + customHostnameVerifier.getClass() + " has failed"));
                            }

                            onSslHandshakeCompleted();
                            return false;
                        }
                        if (!networkData.hasRemaining() || result.getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
                            // all data has been read or the engine needs to do something else than read
                            break;
                        }
                    }
                }
                // write or do tasks (for instance validating certificates)
                doHandshakeStep(downstreamFilter);

            } else {
                // Encrypting received data
                SSLEngineResult result;
                do {
                    applicationInputBuffer.clear();
                    result = sslEngine.unwrap(networkData, applicationInputBuffer);
                    // other statuses are OK or cannot be returned from unwrap.
                    if (result.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                        // needs more data from the network
                        return false;
                    }
                    applicationInputBuffer.flip();
                    if (upstreamFilter != null) {
                        upstreamFilter.onRead(applicationInputBuffer);
                    }
                } while (networkData.hasRemaining());
            }
        } catch (SSLException e) {
            handleSslError(e);
        }

        return false;
    }

    private void doHandshakeStep(final Filter filter) {
        try {
            synchronized (handshakeLock) {
                while (true) {
                    SSLEngineResult.HandshakeStatus hs = sslEngine.getHandshakeStatus();

                    if (handshakeCompleted || (hs != SSLEngineResult.HandshakeStatus.NEED_WRAP && hs != SSLEngineResult.HandshakeStatus.NEED_TASK)) {
                        return;
                    }

                    switch (hs) {
                        // needs to write data to the network
                        case NEED_WRAP: {
                            networkOutputBuffer.clear();
                            sslEngine.wrap(networkOutputBuffer, networkOutputBuffer);
                            networkOutputBuffer.flip();
                            /**
                             *  Latch to make the write operation synchronous. If it was asynchronous, the {@link #handshakeLock}
                             *  will be released before the write is completed and another thread arriving form
                             *  {@link #onRead(Filter, java.nio.ByteBuffer)} will be allowed to write resulting in
                             *  {@link java.nio.channels.WritePendingException}. This is only concern during the handshake
                             *  phase as {@link org.glassfish.tyrus.container.jdk.client.TaskQueueFilter} ensures that
                             *  only one write operation is allowed at a time during "data transfer" phase.
                             */
                            final CountDownLatch writeLatch = new CountDownLatch(1);
                            filter.write(networkOutputBuffer, new CompletionHandler<ByteBuffer>() {
                                @Override
                                public void failed(Throwable throwable) {
                                    writeLatch.countDown();
                                    handleSslError(throwable);
                                }

                                @Override
                                public void completed(ByteBuffer result) {
                                    writeLatch.countDown();
                                }
                            });

                            writeLatch.await();
                            break;
                        }
                        // needs to execute long running task (for instance validating certificates)
                        case NEED_TASK: {
                            Runnable delegatedTask;
                            while ((delegatedTask = sslEngine.getDelegatedTask()) != null) {
                                delegatedTask.run();
                            }
                            if (sslEngine.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
                                handleSslError(new SSLException("SSL handshake error has occurred - more data needed for validating the certificate"));
                                return;
                            }
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            handleSslError(e);
        }
    }

    private void handleSslError(Throwable t) {
        onError(t);
    }

    @Override
    void startSsl() {
        try {
            sslStarted = true;
            sslEngine.beginHandshake();
            doHandshakeStep(downstreamFilter);
        } catch (SSLException e) {
            handleSslError(e);
        }
    }
}
