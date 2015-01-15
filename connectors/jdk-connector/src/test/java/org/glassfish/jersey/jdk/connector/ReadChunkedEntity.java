package org.glassfish.jersey.jdk.connector;

import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.server.ChunkedOutput;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.junit.Test;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import java.io.IOException;

import static org.junit.Assert.assertEquals;

/**
 * Created by petr on 14/01/15.
 */
public class ReadChunkedEntity extends JerseyTest {

    @Path("/chunkedEntity")
    public static class ChunkedResource {

        @POST
        public ChunkedOutput<String> get(final String entity) {
            final ChunkedOutput<String> output = new ChunkedOutput<>(String.class);

            new Thread() {
                public void run() {
                    try {
                        int startIdx = 0;
                        int remaining =entity.length();
                        while (remaining >= 0) {
                            int chunkLength = 10;
                            int endIdx = startIdx + chunkLength;
                            if (endIdx > entity.length()) {
                               endIdx = entity.length();
                            }
                            String chunk = entity.substring(startIdx, endIdx);
                            output.write(chunk);
                            remaining -= chunkLength;
                            startIdx = endIdx;
                        }
                    } catch (IOException e) {
                        //
                    } finally {
                        try {
                            output.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }

                    }
                }
            }.start();

            return output;
        }
    }

    @Override
    protected Application configure() {
        return new ResourceConfig(ChunkedResource.class);
    }

    @Test
    public void testChunked() {
        String message = generateBody(500);
        Response response = target("chunkedEntity").request().post(Entity.entity(message, MediaType.TEXT_PLAIN));
        assertEquals(message, response.readEntity(String.class));
    }

    @Override
    protected void configureClient(ClientConfig config) {
        config.property(JdkConnectorProvider.USE_FIXED_LENGTH_STREAMING, true);
        config.property(ClientProperties.CHUNKED_ENCODING_SIZE, 20);
        config.connectorProvider(new JdkConnectorProvider());
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
