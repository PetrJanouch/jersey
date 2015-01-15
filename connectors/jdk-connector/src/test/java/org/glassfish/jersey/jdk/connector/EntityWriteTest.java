package org.glassfish.jersey.jdk.connector;

import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.client.HttpUrlConnectorProvider;
import org.glassfish.jersey.client.RequestEntityProcessing;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.junit.Test;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import static org.junit.Assert.assertEquals;

/**
 * Created by petr on 14/01/15.
 */
public class EntityWriteTest extends JerseyTest {

    private static final String target= "entityWrite";

    @Path("/entityWrite")
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
        config.property(JdkConnectorProvider.USE_FIXED_LENGTH_STREAMING, true);
        config.property(ClientProperties.CHUNKED_ENCODING_SIZE, 20);
        config.connectorProvider(new JdkConnectorProvider());
    }

    @Test
    public void testBuffered() {
        String message = generateBody(5000);
        Response response = target(target).request().post(Entity.entity(message, MediaType.TEXT_PLAIN));
        assertEquals(message, response.readEntity(String.class));
    }

    @Test
    public void testStreamed() {
        String message = generateBody(5000);
        Response response = target(target).request().header("Content-Length", Integer.toString(message.length())).post(Entity.entity(message, MediaType.TEXT_PLAIN));
        assertEquals(message, response.readEntity(String.class));
    }

    @Test
    public void testChunked() {
        String message = generateBody(5000);
        Response response = target(target).request().property(ClientProperties.REQUEST_ENTITY_PROCESSING, RequestEntityProcessing.CHUNKED).post(Entity.entity(message, MediaType.TEXT_PLAIN));
        assertEquals(message, response.readEntity(String.class));
    }

    private String generateBody(int size) {
        String pattern = "ABCDEFG";
        StringBuilder bodyBuilder = new StringBuilder();

        int fullIterations = size / pattern.length();
        for (int i = 0; i < fullIterations; i++) {
            bodyBuilder.append(pattern);
        }

        String remaining = pattern.substring(0, size - pattern.length() * fullIterations);
        bodyBuilder.append(remaining);
        return bodyBuilder.toString();
    }
}
