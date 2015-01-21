package org.glassfish.jersey.jdk.connector;

import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.junit.Test;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;

import static org.junit.Assert.fail;
import static org.junit.Assert.assertEquals;

/**
 * Created by petr on 20/01/15.
 */
public class RedirectTest extends JerseyTest {

    @Path("/redirecting")
    public static class RedirectingResource {

        @GET
        public Response get() {
            return  Response.seeOther(UriBuilder.fromResource(RedirectingResource.class).path("target").build()).build();
        }

        @POST
        public Response post(String entity) {
            return  Response.seeOther(UriBuilder.fromResource(RedirectingResource.class).path("target").build()).build();
        }

        @Path("target")
        @GET
        public String target() {
            return "Target";
        }

        @Path("target")
        @POST
        public String target(String entity) {
            return "Target";
        }

        @Path("cycle")
        @GET
        public Response cycle() {
            return  Response.seeOther(UriBuilder.fromResource(RedirectingResource.class).path("cycleNode2").build()).build();
        }

        @Path("cycleNode2")
        @GET
        public Response cycleNode2() {
            return  Response.seeOther(UriBuilder.fromResource(RedirectingResource.class).path("cycleNode3").build()).build();
        }

        @Path("cycleNode3")
        @GET
        public Response cycleNode3() {
            return  Response.seeOther(UriBuilder.fromResource(RedirectingResource.class).path("cycle").build()).build();
        }

        @Path("maxRedirect")
        @GET
        public Response maxRedirect() {
            return  Response.seeOther(UriBuilder.fromResource(RedirectingResource.class).path("maxRedirectNode2").build()).build();
        }

        @Path("maxRedirectNode2")
        @GET
        public Response maxRedirectNode2() {
            return  Response.seeOther(UriBuilder.fromResource(RedirectingResource.class).build()).build();
        }
    }

    @Override
    protected Application configure() {
        return new ResourceConfig(RedirectingResource.class);
    }

    @Override
    protected void configureClient(ClientConfig config) {
        config.connectorProvider(new JdkConnectorProvider());
    }

    @Test
    public void testBasicGet() {
        Response response = target("redirecting").request().get();
        assertEquals(200, response.getStatus());
        assertEquals("Target", response.readEntity(String.class));
    }

    @Test
    public void testDisableRedirect() {
        Response response = target("redirecting").property(ClientProperties.FOLLOW_REDIRECTS, false).request().get();
        assertEquals(303, response.getStatus());
    }

    @Test
    public void testPost() {
        Response response = target("redirecting").request().post(Entity.entity("My awesome message", MediaType.TEXT_PLAIN));
        assertEquals(303, response.getStatus());
    }

    @Test
    public void testCycle() {
        try {
            target("redirecting/cycle").request().get();
            fail();
        } catch (Throwable t) {
           assertEquals(RedirectException.class.getName(), t.getCause().getCause().getClass().getName());
        }
    }

    @Test
    public void testMaxRedirects() {
        try {
            target("redirecting/maxRedirect").property(JdkConnectorProvider.MAX_REDIRECTS, 2).request().get();
            fail();
        } catch (Throwable t) {
            assertEquals(RedirectException.class.getName(), t.getCause().getCause().getClass().getName());
        }
    }
}
