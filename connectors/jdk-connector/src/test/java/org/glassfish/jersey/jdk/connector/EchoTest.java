package org.glassfish.jersey.jdk.connector;

import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.HttpUrlConnectorProvider;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.junit.Test;

import javax.ws.rs.*;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.SyncInvoker;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import static org.junit.Assert.assertEquals;

/**
 * Created by petr on 14/01/15.
 */
public class EchoTest extends JerseyTest {

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
        Response response = target("echo").request().post(Entity.entity("My awesome message",MediaType.TEXT_PLAIN));
        assertEquals(message, response.readEntity(String.class));
    }
}
