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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by petr on 20/01/15.
 */
class RedirectHandler {

    private static final Logger LOGGER = Logger.getLogger(RedirectHandler.class.getName());
    private static final Set<Integer> REDIRECT_STATUS_CODES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(300, 301, 302, 303, 307, 308)));

    private final int maxRedirects;
    private final boolean followRedirects;
    private final Set<URI> redirectUriHistory;
    private final HttpConnectionPool httpConnectionPool;
    private final String method;
    private final Map<String, List<String>> headers;

    private volatile URI lastRequestUri = null;

    RedirectHandler(int maxRedirects, boolean followRedirects, URI requestUri, HttpConnectionPool httpConnectionPool, String method, Map<String, List<String>> headers) {
        this.followRedirects = followRedirects;
        this.maxRedirects = maxRedirects;
        this.lastRequestUri = requestUri;
        this.httpConnectionPool = httpConnectionPool;
        this.method = method;
        this.headers = headers;

        this.redirectUriHistory = new HashSet<>(maxRedirects);
    }

    boolean handleRedirects(HttpResponse httpResponse, CompletionHandler<HttpResponse> completionHandler) {
        if (!followRedirects) {
            return true;
        }

        if (!REDIRECT_STATUS_CODES.contains(httpResponse.getStatusCode())) {
            return true;
        }

        if (!"HEAD".equals(method) &&  !"GET".equals(method)) {
            return true;
        }

        // get location header
        String locationString = null;
        final List<String> locationHeader = httpResponse.getHeader("Location");
        if (locationHeader != null && !locationHeader.isEmpty()) {
            locationString = locationHeader.get(0);
        }

        if (locationString == null || locationString.equals("")) {
            //TODO
            completionHandler.failed(new RedirectException("Infinite loop in chained redirects detected"));
            return false;
        }

        URI location;
        try {
            location = new URI(locationString);

            if (!location.isAbsolute()) {
                // location is not absolute, we need to resolve it.
                URI baseUri = lastRequestUri;
                location = baseUri.resolve(location.normalize());

                if (LOGGER.isLoggable(Level.FINEST)) {
                    LOGGER.finest("HTTP Redirect - Base URI for resolving target location: " + baseUri);
                    LOGGER.finest("HTTP Redirect - Location URI header: " + locationString);
                    LOGGER.finest("HTTP Redirect - Normalized and resolved Location URI header against base URI: " + location);
                }
            }
        } catch (URISyntaxException e) {
            // TODO
            completionHandler.failed(new RedirectException("Error determining redirect location", e));
            return false;
        }

        // infinite loop detection
        boolean alreadyRequested = !redirectUriHistory.add(location);
        if (alreadyRequested) {
            //TODO
            completionHandler.failed(new RedirectException("Infinite loop in chained redirects detected"));
            return false;
        }

        // maximal number of redirection
        if (redirectUriHistory.size() > maxRedirects) {
            //TODO
            completionHandler.failed(new RedirectException("Max chained redirect limit (" + maxRedirects + ") exceeded"));
            return false;
        }

        HttpRequest httpRequest = HttpRequest.createBodyless(method, location, headers);
        lastRequestUri = location;

        httpConnectionPool.execute(httpRequest, completionHandler);
        return false;
    }

    URI getLastRequestUri() {
        return lastRequestUri;
    }
}
