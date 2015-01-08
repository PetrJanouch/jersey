package org.glassfish.jersey.jdk.connector;

/**
 * A callback to notify about asynchronous I/O operations status updates.
 *
 * @author Alexey Stashok
 */
public abstract class CompletionHandler<E> {
    /**
     * The operation was failed.
     *
     * @param throwable error, which occurred during operation execution.
     */
    public void failed(Throwable throwable) {
    }

    /**
     * The operation was completed.
     *
     * @param result the operation result.
     */
    public void completed(E result) {
    }
}
