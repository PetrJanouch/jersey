package org.glassfish.jersey.jdk.connector;

import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.client.RequestEntityProcessing;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.junit.Ignore;
import org.junit.Test;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.MessageBodyWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.concurrent.Future;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Created by petr on 15/01/15.
 */

// TODO: Borrowed from Grizzly connector, decide if to keep
    @Ignore
public class HugeEntityTest extends JerseyTest {

    private static final int BUFFER_LENGTH = 1024 * 1024; // 1M
    private static final long HUGE_DATA_LENGTH = 20l*1024l*1024l*1024l; // 20G seems sufficient

    /**
     * JERSEY-2337 reproducer. The resource is used to check the right amount of data
     * is being received from the client and also gives us ability to check we receive
     * correct data.
     */
    @Path("/")
    public static class ConsumerResource {

        /**
         * Return back the count of bytes received.
         * This way, we should be able to consume a huge amount of data.
         */
        @POST
        @Path("size")
        public String post(InputStream in) throws IOException {

            long totalBytesRead = 0l;

            byte[] buffer = new byte[BUFFER_LENGTH];
            int read = -1;
            do {
                read = in.read(buffer);
                if (read > 0) {
                    totalBytesRead += read;
                }
            } while (read != -1);

            return String.valueOf(totalBytesRead);
        }

        @POST
        @Path("echo")
        public String echo(String s) {
            return s;
        }
    }

    @Override
    protected Application configure() {
        return new ResourceConfig(ConsumerResource.class);
    }

    @Override
    protected void configureClient(ClientConfig config) {
        config.register(TestEntityWriter.class);
        config.property(ClientProperties.REQUEST_ENTITY_PROCESSING, RequestEntityProcessing.CHUNKED);
        config.connectorProvider(new JdkConnectorProvider());
    }

    public static class TestEntity {
        final long size;

        public TestEntity(long size) {
            this.size = size;
        }
    }

    /**
     * Utility writer that generates that many zero bytes as given by the input entity size field.
     */
    public static class TestEntityWriter implements MessageBodyWriter<TestEntity> {

        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == TestEntity.class;
        }

        @Override
        public long getSize(TestEntity t, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return -1; // no matter what we return here, the output will get chunk-encoded
        }

        @Override
        public void writeTo(TestEntity t, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {

            final byte[] buffer = new byte[BUFFER_LENGTH];
            final long bufferCount = t.size / BUFFER_LENGTH;
            final int remainder = (int)(t.size % BUFFER_LENGTH);

            for (long b=0; b<bufferCount; b++) {
                entityStream.write(buffer);
            }

            if (remainder > 0) {
                entityStream.write(buffer, 0, remainder);
            }
        }
    }

    /**
     * JERSEY-2337 reproducer. We are going to send huge amount of data over the wire.
     * Should not the data have been chunk-encoded, we would easily run out of memory.
     *
     * @throws Exception in case of a test error.
     */
    @Test
    public void testPost() throws Exception {
        Response response = target("/size").request().post(Entity.entity(new TestEntity(HUGE_DATA_LENGTH), MediaType.APPLICATION_OCTET_STREAM_TYPE));
        String content = response.readEntity(String.class);
        assertThat(Long.parseLong(content), equalTo(HUGE_DATA_LENGTH));

        // just to check the right data have been transfered.
        response = target("/echo").request().post(Entity.text("Hey Sync!"));
        assertThat(response.readEntity(String.class), equalTo("Hey Sync!"));
    }

    /**
     * JERSEY-2337 reproducer. We are going to send huge amount of data over the wire. This time in an async fashion.
     * Should not the data have been chunk-encoded, we would easily run out of memory.
     *
     * @throws Exception in case of a test error.
     */
    @Test
    public void testAsyncPost() throws Exception {
        Future<Response> response = target("/size").request().async().post(Entity.entity(new TestEntity(HUGE_DATA_LENGTH), MediaType.APPLICATION_OCTET_STREAM_TYPE));
        final String content = response.get().readEntity(String.class);
        assertThat(Long.parseLong(content), equalTo(HUGE_DATA_LENGTH));

        // just to check the right data have been transfered.
        response = target("/echo").request().async().post(Entity.text("Hey Async!"));
        assertThat(response.get().readEntity(String.class), equalTo("Hey Async!"));
    }
}
