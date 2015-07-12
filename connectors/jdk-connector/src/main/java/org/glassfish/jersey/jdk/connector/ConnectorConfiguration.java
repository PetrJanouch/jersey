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

import org.glassfish.jersey.SslConfigurator;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.client.internal.LocalizationMessages;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.ws.rs.client.Client;
import javax.ws.rs.core.Configuration;
import java.net.CookiePolicy;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A container for connector configuration to make it easier to move around.
 */
class ConnectorConfiguration {

    private static final Logger LOGGER = Logger.getLogger(ConnectorConfiguration.class.getName());

    private final boolean          fixLengthStreaming;
    private final int              chunkSize;
    private final boolean          followRedirects;
    private final int              maxRedirects;
    private final ThreadPoolConfig threadPoolConfig;
    private final int              containerIdleTimeout;
    private final int              maxHeaderSize;
    private final CookiePolicy     cookiePolicy;
    private final int              maxConnectionsPerDestionation;
    private final int              maxConnections;
    private final int              connectionIdleTimeout;
    private final SSLContext sslContext;
    private final HostnameVerifier hostnameVerifier;

    ConnectorConfiguration(Client client, Configuration config) {
        final Map<String, Object> properties = config.getProperties();

        int proposedChunkSize = ClientProperties.getValue(properties,
                ClientProperties.CHUNKED_ENCODING_SIZE, JdkConnectorProvider.DEFAULT_HTTP_CHUNK_SIZE, Integer.class);
        if (proposedChunkSize < 0) {
            LOGGER.warning(LocalizationMessages
                    .NEGATIVE_CHUNK_SIZE(proposedChunkSize, JdkConnectorProvider.DEFAULT_HTTP_CHUNK_SIZE));
            proposedChunkSize = JdkConnectorProvider.DEFAULT_HTTP_CHUNK_SIZE;
        }

        chunkSize = proposedChunkSize;

        fixLengthStreaming = ClientProperties.getValue(properties, JdkConnectorProvider.USE_FIXED_LENGTH_STREAMING,
                JdkConnectorProvider.DEFAULT_USE_FIXED_LENGTH_STREAMING, Boolean.class);
        threadPoolConfig = ClientProperties.getValue(properties, JdkConnectorProvider.WORKER_THREAD_POOL_CONFIG, ThreadPoolConfig
                .defaultConfig(), ThreadPoolConfig.class);
        threadPoolConfig.setCorePoolSize(ClientProperties
                .getValue(properties, ClientProperties.ASYNC_THREADPOOL_SIZE, threadPoolConfig.getCorePoolSize(), Integer.class));
        containerIdleTimeout = ClientProperties.getValue(properties, JdkConnectorProvider.CONTAINER_IDLE_TIMEOUT, Integer.class);

        maxHeaderSize = ClientProperties.getValue(properties, JdkConnectorProvider.MAX_HEADER_SIZE,
                JdkConnectorProvider.DEFAULT_MAX_HEADER_SIZE, Integer.class);
        followRedirects = ClientProperties.getValue(properties, ClientProperties.FOLLOW_REDIRECTS, true, Boolean.class);

        cookiePolicy = ClientProperties.getValue(properties, JdkConnectorProvider.COOKIE_POLICY, JdkConnectorProvider
                .DEFAULT_COOKIE_POLICY, CookiePolicy.class);
        maxRedirects = ClientProperties.getValue(properties, JdkConnectorProvider.MAX_REDIRECTS, JdkConnectorProvider
                .DEFAULT_MAX_REDIRECTS, Integer.class);

        maxConnectionsPerDestionation = ClientProperties
                .getValue(properties, JdkConnectorProvider.MAX_CONECTIONS_PER_DESTINATION, JdkConnectorProvider
                        .DEFAULT_MAX_CONECTIONS_PER_DESTINATION, Integer.class);

        maxConnections = ClientProperties.getValue(properties, JdkConnectorProvider.MAX_CONECTIONS, JdkConnectorProvider
                .DEFAULT_MAX_CONECTIONS, Integer.class);

        connectionIdleTimeout = ClientProperties
                .getValue(properties, JdkConnectorProvider.CONNECTION_IDLE_TIMEOUT, JdkConnectorProvider
                        .DEFAULT_CONNECTION_IDLE_TIMEOUT, Integer.class);

        if (client.getSslContext() == null) {
            sslContext = SslConfigurator.getDefaultContext();
        } else {
            sslContext = client.getSslContext();
        }

        hostnameVerifier = client.getHostnameVerifier();

        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(Level.FINEST, "Connector configuration: " + toString());
        }
    }

    boolean isFixLengthStreaming() {
        return fixLengthStreaming;
    }

    int getChunkSize() {
        return chunkSize;
    }

    boolean getFollowRedirects() {
        return followRedirects;
    }

    int getMaxRedirects() {
        return maxRedirects;
    }

    ThreadPoolConfig getThreadPoolConfig() {
        return threadPoolConfig;
    }

    int getContainerIdleTimeout() {
        return containerIdleTimeout;
    }

    int getMaxHeaderSize() {
        return maxHeaderSize;
    }

    CookiePolicy getCookiePolicy() {
        return cookiePolicy;
    }

    int getMaxConnectionsPerDestionation() {
        return maxConnectionsPerDestionation;
    }

    int getMaxConnections() {
        return maxConnections;
    }

    int getConnectionIdleTimeout() {
        return connectionIdleTimeout;
    }

    public SSLContext getSslContext() {
        return sslContext;
    }

    public HostnameVerifier getHostnameVerifier() {
        return hostnameVerifier;
    }

    @Override
    public String toString() {
        return "ConnectorConfiguration{" +
                "fixLengthStreaming=" + fixLengthStreaming +
                ", chunkSize=" + chunkSize +
                ", followRedirects=" + followRedirects +
                ", maxRedirects=" + maxRedirects +
                ", threadPoolConfig=" + threadPoolConfig +
                ", containerIdleTimeout=" + containerIdleTimeout +
                ", maxHeaderSize=" + maxHeaderSize +
                ", cookiePolicy=" + cookiePolicy +
                ", maxConnectionsPerDestionation=" + maxConnectionsPerDestionation +
                ", maxConnections=" + maxConnections +
                ", connectionIdleTimeout=" + connectionIdleTimeout +
                ", sslContext=" + sslContext +
                ", hostnameVerifier=" + hostnameVerifier +
                '}';
    }
}
