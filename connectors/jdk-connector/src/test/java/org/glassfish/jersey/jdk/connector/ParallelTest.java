package org.glassfish.jersey.jdk.connector;

import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.junit.Assert;
import org.junit.Test;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.Response;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Created by petr on 15/01/15.
 */
public class ParallelTest extends JerseyTest {
    private static final Logger LOGGER = Logger.getLogger(ParallelTest.class.getName());

    private static final int PARALLEL_CLIENTS = 10;
    private static final String PATH = "test";
    private static final AtomicInteger receivedCounter = new AtomicInteger(0);
    private static final AtomicInteger resourceCounter = new AtomicInteger(0);
    private static final CyclicBarrier startBarrier = new CyclicBarrier(PARALLEL_CLIENTS + 1);
    private static final CountDownLatch doneLatch = new CountDownLatch(PARALLEL_CLIENTS);

    @Path(PATH)
    public static class MyResource {

        @GET
        public String get() {
            sleep();
            resourceCounter.addAndGet(1);
            return "GET";
        }

        private void sleep() {
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
                Logger.getLogger(ParallelTest.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }

    @Override
    protected Application configure() {
        return new ResourceConfig(ParallelTest.MyResource.class);
    }

    @Override
    protected void configureClient(ClientConfig config) {
        config.connectorProvider(new JdkConnectorProvider());
    }

    @Test
    public void testParallel() throws BrokenBarrierException, InterruptedException, TimeoutException {
        final ScheduledExecutorService executor = Executors.newScheduledThreadPool(PARALLEL_CLIENTS);

        try {
            final WebTarget target = target();
            for (int i = 1; i <= PARALLEL_CLIENTS; i++) {
                final int id = i;
                executor.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            startBarrier.await();
                            Response response;
                            response = target.path(PATH).request().get();
                            assertEquals("GET", response.readEntity(String.class));
                            receivedCounter.incrementAndGet();
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            LOGGER.log(Level.WARNING, "Client thread " + id + " interrupted.", ex);
                        } catch (BrokenBarrierException ex) {
                            LOGGER.log(Level.INFO, "Client thread " + id + " failed on broken barrier.", ex);
                        } catch (Throwable t) {
                            t.printStackTrace();
                            LOGGER.log(Level.WARNING, "Client thread " + id + " failed on unexpected exception.", t);
                        } finally {
                            doneLatch.countDown();
                        }
                    }
                });
            }

            startBarrier.await(1, TimeUnit.SECONDS);

            assertTrue("Waiting for clients to finish has timed out.", doneLatch.await(5 * getAsyncTimeoutMultiplier(),
                    TimeUnit.SECONDS));

            assertEquals("Resource counter", PARALLEL_CLIENTS, resourceCounter.get());

            assertEquals("Received counter", PARALLEL_CLIENTS, receivedCounter.get());
        } finally {
            executor.shutdownNow();
            Assert.assertTrue("Executor termination", executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
