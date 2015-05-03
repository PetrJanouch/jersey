package org.glassfish.jersey.jdk.connector;

import org.glassfish.jersey.SslConfigurator;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.junit.Test;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CountDownLatch;

import static junit.framework.Assert.fail;
import static org.junit.Assert.assertEquals;

/**
 * Created by petr on 19/04/15.
 */
public class PublicSitesTest extends JerseyTest {

    @Test
    public void testGoolgeCom() throws InterruptedException {
        client().target("https://www.google.com").request().get();
        Thread.sleep(5000);
    }

    @Test
    public void testSeznam() throws InterruptedException {
        client().target("https://www.seznam.cz").request().get();
        Thread.sleep(5000);
    }

    @Test
    public void testGoogleUK() throws InterruptedException {
        client().target("https://www.google.co.uk").request().get();
        Thread.sleep(5000);
    }

    @Test
    public void testServis24() throws InterruptedException {
        client().target("https://www.servis24.cz").request().get();
        Thread.sleep(5000);
    }

    @Path("/echo")
    public static class EchoResource {

        @POST
        public String post(String entity) {
            return entity;
        }
    }

    @Override
    protected Application configure() {
        return new ResourceConfig(EchoResource.class);
    }


    @Override
    protected void configureClient(ClientConfig config) {
        config.connectorProvider(new JdkConnectorProvider());
    }

    @Test
    public void testEcho() {
        String message = "My awesome message";
        Response response = target("echo").request().post(Entity.entity("My awesome message", MediaType.TEXT_PLAIN));
        assertEquals(message, response.readEntity(String.class));
    }
}
