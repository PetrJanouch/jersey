package org.glassfish.jersey.jdk.connector;

import java.io.IOException;
import java.util.EventListener;

/**
 * <p>
 * This class represents a call-back mechanism that will notify implementations
 * as HTTP request data becomes available to be read without blocking.
 * </p>
 *
 * Taken from Servlet 3.1
 */
public interface ReadListener extends EventListener {

    /**
     * When an instance of the <code>ReadListener</code> is registered with a {@link ServletInputStream},
     * this method will be invoked by the container the first time when it is possible
     * to read data. Subsequently the container will invoke this method if and only
     * if {@link javax.servlet.ServletInputStream#isReady()} method
     * has been called and has returned <code>false</code>.
     *
     * @throws java.io.IOException if an I/O related error has occurred during processing
     */
    public void onDataAvailable() throws IOException;

    /**
     * Invoked when all data for the current request has been read.
     *
     * @throws IOException if an I/O related error has occurred during processing
     */

    public void onAllDataRead() throws IOException;

    /**
     * Invoked when an error occurs processing the request.
     */
    public void onError(Throwable t);
}
