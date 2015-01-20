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

import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.client.internal.LocalizationMessages;
import org.glassfish.jersey.client.spi.Connector;
import org.glassfish.jersey.client.spi.ConnectorProvider;

import javax.ws.rs.client.Client;
import javax.ws.rs.core.Configuration;
import java.util.Map;
import java.util.logging.Logger;

/**
 * @author Petr Janouch (petr.janouch at oracle.com)
 */
public class JdkConnectorProvider implements ConnectorProvider {

    public static final String USE_FIXED_LENGTH_STREAMING = "jersey.config.client.JdkConnectorProvider.useFixedLengthStreaming";

    public static final String WORKER_THREAD_POOL_CONFIG = "jersey.config.client.JdkConnectorProvider.workerThreadPoolConfig";

    public static final String CONTAINER_IDLE_TIMEOUT = "jersey.config.client.JdkConnectorProvider.containerIdleTimeout";

    public static final String MAX_HEADER_SIZE = "jersey.config.client.JdkConnectorProvider.maxHeaderSize";

    private static final Logger LOGGER = Logger.getLogger(JdkConnector.class.getName());

    /**
     * Default chunk size in HTTP chunk-encoded messages.
     */
    private static final int DEFAULT_HTTP_CHUNK_SIZE = 4096;

    private int chunkSize = DEFAULT_HTTP_CHUNK_SIZE;
    private boolean useFixedLengthStreaming = false;

    @Override
    public Connector getConnector(Client client, Configuration config) {
        final Map<String, Object> properties = config.getProperties();

        int computedChunkSize = ClientProperties.getValue(properties,
                ClientProperties.CHUNKED_ENCODING_SIZE, chunkSize, Integer.class);
        if (computedChunkSize < 0) {
            LOGGER.warning(LocalizationMessages.NEGATIVE_CHUNK_SIZE(computedChunkSize, chunkSize));
            computedChunkSize = chunkSize;
        }

        final boolean computedUseFixedLengthStreaming = ClientProperties.getValue(properties,
                USE_FIXED_LENGTH_STREAMING, useFixedLengthStreaming, Boolean.class);

        return new JdkConnector(client, config, computedUseFixedLengthStreaming, computedChunkSize);
    }
}
