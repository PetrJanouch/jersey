package org.glassfish.jersey.jdk.connector;

import java.io.IOException;
import java.util.EventListener;

/**
 *
 * Callback notification mechanism that signals to the developer it's possible
 * to write content without blocking.
 * <p/>
 * Taken from servlet 3.1
 */
public interface WriteListener extends EventListener {

    /**
     * When an instance of the WriteListener is registered with a {@link ServletOutputStream},
     * this method will be invoked by the container the first time when it is possible
     * to write data. Subsequently the container will invoke this method if and only
     * if {@link javax.servlet.ServletOutputStream#isReady()} method
     * has been called and has returned <code>false</code>.
     *
     * @throws java.io.IOException if an I/O related error has occurred during processing
     */
    public void onWritePossible() throws IOException;

    /**
     * Invoked when an error occurs writing data using the non-blocking APIs.
     */
    public void onError(final Throwable t);

}
