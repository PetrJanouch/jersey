/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2014 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

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
