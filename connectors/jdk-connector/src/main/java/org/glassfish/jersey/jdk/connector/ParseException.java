package org.glassfish.jersey.jdk.connector;

/**
 * @author Petr Janouch (petr.janouch at oracle.com)
 */
class ParseException extends Exception {

    private static final long serialVersionUID = 689526483137789578L;

    ParseException(String message) {
        super(message);
    }

    public ParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
