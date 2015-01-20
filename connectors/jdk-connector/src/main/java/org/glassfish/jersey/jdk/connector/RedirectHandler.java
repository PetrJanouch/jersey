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
    private static final int DEFAULT_REDIRECT_THRESHOLD = 5;

    private final boolean followRedirects;
    private final Set<URI> redirectUriHistory = new HashSet<>(DEFAULT_REDIRECT_THRESHOLD);
    private final HttpConnectionPool httpConnectionPool;
    private final String method;
    private final Map<String, List<String>> headers;

    private volatile URI lastRequestUri = null;

    RedirectHandler(boolean followRedirects, URI requestUri, HttpConnectionPool httpConnectionPool, String method, Map<String, List<String>> headers) {
        this.followRedirects = followRedirects;
        this.lastRequestUri = requestUri;
        this.httpConnectionPool = httpConnectionPool;
        this.method = method;
        this.headers = headers;
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
            throw new IllegalStateException("");
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
            throw new IllegalStateException(e);
        }

        // infinite loop detection
        boolean alreadyRequested = !redirectUriHistory.add(location);
        if (alreadyRequested) {
            //TODO
            throw new IllegalStateException("");
        }

        // maximal number of redirection
        if (redirectUriHistory.size() > DEFAULT_REDIRECT_THRESHOLD) {
            //TODO
            throw new IllegalStateException("");
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
