package org.glassfish.jersey.jdk.connector;

import org.glassfish.jersey.SslConfigurator;
import org.junit.Before;
import org.junit.Test;

import javax.net.ServerSocketFactory;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;


/**
 * Created by petr on 12/04/15.
 */
public class NewSslFilterTest {

    @Before
    public void beforeTest() {
        System.setProperty("javax.net.ssl.keyStore", this.getClass().getResource("/keystore_server").getPath());
        System.setProperty("javax.net.ssl.keyStorePassword", "asdfgh");
        System.setProperty("javax.net.ssl.trustStore", this.getClass().getResource("/truststore_server").getPath());
        System.setProperty("javax.net.ssl.trustStorePassword", "asdfgh");

      //  System.setProperty("javax.net.debug", "all");
    }

    @Test
    public void testBasicEcho() throws Throwable {
        CountDownLatch latch = new CountDownLatch(1);
        SslEchoServer server = new SslEchoServer();
        try {
            server.start();
            String message = "Hello world\n";
            ByteBuffer readBuffer = ByteBuffer.allocate(message.length());
            Filter<ByteBuffer, ByteBuffer, ByteBuffer, ByteBuffer> clientSocket = openClientSocket("localhost", readBuffer, latch, null);

            clientSocket.write(stringToBuffer(message), new CompletionHandler<ByteBuffer>() {
                @Override
                public void failed(Throwable throwable) {
                    throwable.printStackTrace();
                    fail();
                }
            });

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            clientSocket.close();
            readBuffer.flip();
            String received = bufferToString(readBuffer);
            assertEquals(message, received);
        } finally {
            server.stop();
        }
    }

    @Test
    public void testEcho100k() throws Throwable {
        CountDownLatch latch = new CountDownLatch(1);
        SslEchoServer server = new SslEchoServer();
        try {
            server.start();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 1000; i++) {
                sb.append("1234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890");
            }
            String message = sb.toString() + "\n";
            ByteBuffer readBuffer = ByteBuffer.allocate(message.length());
            Filter<ByteBuffer, ByteBuffer, ByteBuffer, ByteBuffer> clientSocket = openClientSocket("localhost", readBuffer, latch, null);

            clientSocket.write(stringToBuffer(message), new CompletionHandler<ByteBuffer>() {
                @Override
                public void failed(Throwable throwable) {
                    throwable.printStackTrace();
                    fail();
                }
            });

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            clientSocket.close();
            readBuffer.flip();
            String received = bufferToString(readBuffer);
            assertEquals(message, received);
        } finally {
            server.stop();
        }
    }

    @Test
    public void testCloseServer() throws Throwable {
        CountDownLatch latch = new CountDownLatch(1);
        SslEchoServer server = new SslEchoServer();
        try {
            server.start();
            String message = "Hello world\n";
            ByteBuffer readBuffer = ByteBuffer.allocate(message.length());
            Filter<ByteBuffer, ByteBuffer, ByteBuffer, ByteBuffer> clientSocket = openClientSocket("localhost", readBuffer, latch, null);

            clientSocket.write(stringToBuffer(message), new CompletionHandler<ByteBuffer>() {
                @Override
                public void failed(Throwable throwable) {
                    throwable.printStackTrace();
                    fail();
                }
            });

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            server.stop();
            readBuffer.flip();
            String received = bufferToString(readBuffer);
            assertEquals(message, received);
        } finally {
            server.stop();
        }
    }

    @Test
    public void testRehandshakeServer() throws Throwable {
        CountDownLatch latch = new CountDownLatch(1);
        final SslEchoServer server = new SslEchoServer();
        try {
            server.start();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 1000; i++) {
                sb.append("1234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890");
            }
            String message1 = "Hello";
            String message2 = sb.toString() + "\n";
            ByteBuffer readBuffer = ByteBuffer.allocate(message1.length() + message2.length());
            final CountDownLatch message1Latch = new CountDownLatch(1);
            Filter<ByteBuffer, ByteBuffer, ByteBuffer, ByteBuffer> clientSocket = openClientSocket("localhost", readBuffer, latch, null);


            clientSocket.write(stringToBuffer(message1), new CompletionHandler<ByteBuffer>() {
                @Override
                public void failed(Throwable throwable) {
                    throwable.printStackTrace();
                    fail();
                }

                @Override
                public void completed(ByteBuffer result) {
                    try {
                        message1Latch.countDown();
                        server.rehandshake();

                    } catch (IOException e) {
                        e.printStackTrace();
                        fail();
                    }
                }
            });

            assertTrue(message1Latch.await(5, TimeUnit.SECONDS));

            clientSocket.write(stringToBuffer(message2), new CompletionHandler<ByteBuffer>() {
                @Override
                public void failed(Throwable throwable) {
                    throwable.printStackTrace();
                    fail();
                }

            });

            assertTrue(latch.await(20000, TimeUnit.SECONDS));
            clientSocket.close();
            readBuffer.flip();
            String received = bufferToString(readBuffer);
            assertEquals(message1 + message2, received);
        } finally {
            server.stop();
        }
    }

    @Test
    public void testRehandshakeClient() throws Throwable {
        CountDownLatch latch = new CountDownLatch(1);
        final SslEchoServer server = new SslEchoServer();
        try {
            server.start();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 1000; i++) {
                sb.append("1234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890");
            }
            String message1 = "Hello";
            String message2 = sb.toString() + "\n";
            ByteBuffer readBuffer = ByteBuffer.allocate(message1.length() + message2.length());
            final CountDownLatch message1Latch = new CountDownLatch(1);
            final Filter<ByteBuffer, ByteBuffer, ByteBuffer, ByteBuffer> clientSocket = openClientSocket("localhost", readBuffer, latch, null);


            clientSocket.write(stringToBuffer(message1), new CompletionHandler<ByteBuffer>() {
                @Override
                public void failed(Throwable throwable) {
                    throwable.printStackTrace();
                    fail();
                }

                @Override
                public void completed(ByteBuffer result) {
                    message1Latch.countDown();
                    // startSsl is overloaded in the test so it will start re-handshake, calling startSsl on a filter
                    // for a second time will not normally cause a re-handshake
                    clientSocket.startSsl();
                }
            });

            assertTrue(message1Latch.await(5, TimeUnit.SECONDS));

            clientSocket.write(stringToBuffer(message2), new CompletionHandler<ByteBuffer>() {
                @Override
                public void failed(Throwable throwable) {
                    throwable.printStackTrace();
                    fail();
                }

            });

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            clientSocket.close();
            readBuffer.flip();
            String received = bufferToString(readBuffer);
            assertEquals(message1 + message2, received);
        } finally {
            server.stop();
        }
    }

    @Test
    public void testHostameVerificationFail() throws Throwable {
        CountDownLatch latch = new CountDownLatch(1);
        SslEchoServer server = new SslEchoServer();
        try {
            server.start();
            openClientSocket("127.0.0.1", ByteBuffer.allocate(0), latch, null);
            fail();
        } catch (SSLException e) {
            // expected
        } finally {
            server.stop();
        }
    }

    @Test
    public void testCustomHostameVerificationFail() throws Throwable {
        CountDownLatch latch = new CountDownLatch(1);
        SslEchoServer server = new SslEchoServer();
        try {
            server.start();
            HostnameVerifier verifier = new HostnameVerifier() {
                @Override
                public boolean verify(String s, SSLSession sslSession) {
                    return false;
                }
            };

            openClientSocket("localhost", ByteBuffer.allocate(0), latch, verifier);
            fail();
        } catch (SSLException e) {
            // expected
        } finally {
            server.stop();
        }
    }

    @Test
    public void testCustomHostameVerificationPass() throws Throwable {
        CountDownLatch latch = new CountDownLatch(1);
        SslEchoServer server = new SslEchoServer();
        try {
            server.start();
            HostnameVerifier verifier = new HostnameVerifier() {
                @Override
                public boolean verify(String s, SSLSession sslSession) {
                    return true;
                }
            };

            openClientSocket("127.0.0.1", ByteBuffer.allocate(0), latch, verifier);
        } finally {
            server.stop();
        }
    }

    @Test
    public void testClientAuthentication() throws Throwable {
        CountDownLatch latch = new CountDownLatch(1);
        SslEchoServer server = new SslEchoServer();
        try {
            server.setClientAuthentication();
            server.start();
            String message = "Hello world\n";
            ByteBuffer readBuffer = ByteBuffer.allocate(message.length());
            final Filter<ByteBuffer, ByteBuffer, ByteBuffer, ByteBuffer> clientSocket = openClientSocket("localhost", readBuffer, latch, null);

            clientSocket.write(stringToBuffer(message), new CompletionHandler<ByteBuffer>() {
                @Override
                public void failed(Throwable throwable) {
                    throwable.printStackTrace();
                    fail();
                }
            });

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            clientSocket.close();
            readBuffer.flip();
            String received = bufferToString(readBuffer);
            assertEquals(message, received);
        } finally {
            server.stop();
        }
    }

    private String bufferToString(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return new String(bytes);
    }

    private ByteBuffer stringToBuffer(String string) {
        byte[] bytes = string.getBytes();
        return ByteBuffer.wrap(bytes);
    }

    private Filter<ByteBuffer, ByteBuffer, ByteBuffer, ByteBuffer> openClientSocket(String host, final ByteBuffer readBuffer, final CountDownLatch completionLatch, HostnameVerifier customHostnameVerifier) throws Throwable {
        SslConfigurator sslConfig = SslConfigurator.newInstance()
            .trustStoreFile(this.getClass().getResource("/truststore_client").getPath())
            .trustStorePassword("asdfgh")
            .keyStoreFile(this.getClass().getResource("/keystore_client").getPath())
            .keyStorePassword("asdfgh");

        TransportFilter transportFilter = new TransportFilter(17_000, ThreadPoolConfig.defaultConfig(), 100_000);
        final SslFilter sslFilter = new SslFilter(transportFilter, sslConfig.createSSLContext(), host, customHostnameVerifier);

        final AtomicReference<Throwable> e = new AtomicReference<>();
        Filter<ByteBuffer, ByteBuffer, ByteBuffer, ByteBuffer> clientSocket = new Filter<ByteBuffer, ByteBuffer, ByteBuffer, ByteBuffer>(sslFilter) {

            private CountDownLatch startSslLatch = new CountDownLatch(1);
            private CountDownLatch connectLatch = new CountDownLatch(1);

            @Override
            void connect(SocketAddress address, Filter<?, ?, ByteBuffer, ByteBuffer> upstreamFilter) {
                super.connect(address, upstreamFilter);
                try {
                    connectLatch.await();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    fail();
                }
            }

            @Override
            void processConnect() {
                connectLatch.countDown();
            }

            @Override
            boolean processRead(ByteBuffer data) {
                readBuffer.put(data);
                if (!readBuffer.hasRemaining()) {
                    completionLatch.countDown();
                }
                return false;
            }

            @Override
            void startSsl() {
                if (startSslLatch.getCount() == 1) {
                    downstreamFilter.startSsl();
                    try {
                        startSslLatch.await();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        fail();
                    }
                } else {
                    sslFilter.rehandshake();
                }
            }

            @Override
            void processSslHandshakeCompleted() {
                startSslLatch.countDown();
            }

            @Override
            void processError(Throwable t) {
                if (connectLatch.getCount() == 1 || startSslLatch.getCount() == 1) {
                    e.set(t);
                    connectLatch.countDown();
                    startSslLatch.countDown();
                    return;
                }
                t.printStackTrace();
                fail();
            }

            @Override
            void write(ByteBuffer data, CompletionHandler<ByteBuffer> completionHandler) {
                downstreamFilter.write(data, completionHandler);
            }
        };

        clientSocket.connect(new InetSocketAddress(host, 8321), null);
        clientSocket.startSsl();
        if (e.get() != null) {
            throw e.get();
        }

        return clientSocket;
    }

    private static class SslEchoServer {

        private final ServerSocket serverSocket;
        private final ExecutorService executorService = Executors.newSingleThreadExecutor();

        private volatile SSLSocket socket;
        private volatile boolean stopped = false;
        private volatile boolean exceptionThrown = false;


        SslEchoServer() throws IOException {
            ServerSocketFactory socketFactory = SSLServerSocketFactory.getDefault();
            serverSocket = socketFactory.createServerSocket(8321);

        }

        void setClientAuthentication() {
            ((SSLServerSocket) serverSocket).setNeedClientAuth(true);
        }

        void start() {
            executorService.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        socket = (SSLSocket) serverSocket.accept();
                        InputStream inputStream = socket.getInputStream();

                        OutputStream outputStream = new BufferedOutputStream(socket.getOutputStream(), 100);

                        while (!stopped) {
                            int result = inputStream.read();
                            if (result == -1) {
                                return;
                            }
                            outputStream.write(result);
                            if (result == '\n') {
                                outputStream.flush();
                                return;
                            }
                        }

                    } catch (IOException e) {
                        if (!e.getClass().equals(SocketException.class)) {
                            e.printStackTrace();
                            // this is not a junit thread, calling fail() here is pointless
                            exceptionThrown = true;
                        }
                    }
                }
            });
        }

        void stop() throws IOException {
            executorService.shutdown();
            serverSocket.close();
            if (socket != null) {
                socket.close();
            }

            if (exceptionThrown) {
                fail();
            }
        }

        void rehandshake() throws IOException {
            socket.startHandshake();
        }
    }
}
