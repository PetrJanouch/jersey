package org.glassfish.jersey.jdk.connector;

/**
 * This exception is set as a cause of TODO {@link DeploymentException} thrown from {@link WebSocketContainer}.connectToServer(...)
 * when any of the Redirect HTTP response status codes (300, 301, 302, 303, 307, 308) is received as a handshake response and:
 * <ul>
 * <li>
 * the chained redirection count exceeds the value of {@link JdkConnectorProvider#MAX_REDIRECTS}
 * </li>
 * <li>
 * or an infinite redirection loop is detected
 * </li>
 * <li>
 * or Location response header is missing, empty or does not contain a valid {@link java.net.URI}.
 * </li>
 * </ul>
 *
 * @author Ondrej Kosatka (ondrej.kosatka at oracle.com)
 * @see RedirectHandler
 */
public class RedirectException extends Exception {

    private static final long serialVersionUID = 4357724300486801294L;

    /**
     * Constructor.
     *
     * @param message        the detail message. The detail message is saved for
     *                       later retrieval by the {@link #getMessage()} method.
     */
    public RedirectException(String message) {
        super(message);
    }

    /**
     * Constructor.
     *
     * @param message        the detail message. The detail message is saved for
     *                       later retrieval by the {@link #getMessage()} method.
     *                       @param
     */
    public RedirectException(String message, Throwable t) {
        super(message, t);
    }
}
