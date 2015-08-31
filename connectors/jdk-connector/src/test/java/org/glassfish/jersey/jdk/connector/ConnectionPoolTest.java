/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2015 Oracle and/or its affiliates. All rights reserved.
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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.InvocationCallback;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Created by petr on 31/08/15.
 */
public class ConnectionPoolTest extends JerseyTest {
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
        config.property(JdkConnectorProvider.MAX_CONNECTIONS_PER_DESTINATION, 1);
        config.property(JdkConnectorProvider.CONNECTION_IDLE_TIMEOUT, 10_000);
    }

    @Test
    public void testEcho() throws InterruptedException {
        String msg1 = "message 1";
        String msg2 = "message 2";
        CountDownLatch latch = new CountDownLatch(2);
        sendMessage(msg1, latch);
        sendMessage(msg2, latch);

        /* the idle timeout is 10s and only 1 connection is allowed, so the test should fail unless the pool reuses
        the connection for both requests */
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    private void sendMessage(String messasge, final CountDownLatch latch) {
        target("echo").request().async().post(Entity.entity(messasge, MediaType.TEXT_PLAIN), new InvocationCallback<String>() {
            @Override
            public void completed(String response) {
                System.out.println("#Received: " + response);
                latch.countDown();
            }

            @Override
            public void failed(Throwable throwable) {
                throwable.printStackTrace();
            }
        });
    }
}
