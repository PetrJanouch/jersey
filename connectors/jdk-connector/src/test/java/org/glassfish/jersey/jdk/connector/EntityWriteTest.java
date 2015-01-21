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
