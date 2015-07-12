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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Abstract body output stream that supports both synchronous and asynchronous operations.
 * <p/>
 * By default the stream works like a normal {@link OutputStream} with blocking {@link #write}, but it can switch into
 * asynchronous mode by registering {@link WriteListener} using {@link #setWriteListener(WriteListener)}. Once {@link
 * WriteListener} has been registered, the stream switches into asynchronous mode and any attempt to invoke {@link #write}
 * operation when the stream is not ready to accept more data ({@link #isReady()} returns false) results in an exception.
 * <p/>
 * The asynchronous mode is inspired by servlet 3.1 with analogy in operations {@link #setWriteListener(WriteListener)}, {@link
 * #isReady()} and write callback {@link WriteListener}.
 *
 */
abstract class AsynchronousBodyOutputStream extends BodyOutputStream {

    protected       Filter<ByteBuffer, ?, ?, ?> downstreamFilter;
    protected final ByteBuffer                  dataBuffer;
    protected WriteListener writeListener = null;
    protected CloseListener closeListener;
    protected boolean ready = false;

    private final CountDownLatch initialLatch = new CountDownLatch(1);

    protected AsynchronousBodyOutputStream(int bufferSize) {
        this.dataBuffer = ByteBuffer.allocate(bufferSize);
    }

    @Override
    public synchronized void setWriteListener(WriteListener writeListener) {
        if (this.writeListener != null) {
            throw new IllegalStateException("Write listener can be set only once");
        }

        this.writeListener = writeListener;
        if (ready) {
            writeListener.onWritePossible();
        }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        // input validation borrowed from a parent
        if (b == null) {
            throw new NullPointerException();
        } else if ((off < 0) || (off > b.length) || (len < 0) ||
                ((off + len) > b.length) || ((off + len) < 0)) {
            throw new IndexOutOfBoundsException();
        } else if (len == 0) {
            return;
        }

        assertValidState();
        doInitialBlocking();
        if (len < dataBuffer.remaining()) {
            for (int i = off; i < len; i++) {
                write(b[i]);
            }
        } else {
            ByteBuffer buffer = ByteBuffer.allocate(dataBuffer.position() + len);
            buffer.put(dataBuffer);
            buffer.put(b, off, len);
            write(buffer);
        }
    }

    @Override
    public void flush() throws IOException {
        super.flush();
        write(dataBuffer);
    }

    @Override
    public void write(int b) throws IOException {
        assertValidState();
        doInitialBlocking();
        dataBuffer.put((byte) b);
        if (!dataBuffer.hasRemaining()) {
            write(dataBuffer);
        }
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    private void assertValidState() {
        if (writeListener != null && !ready) {
            // we are in asynchronous mode, but the user called write when the stream in non-ready state
            throw new IllegalStateException("Asynchronous write called when stream is in non-ready state");
        }
    }

    protected void write(ByteBuffer byteBuffer) throws IOException {
        ByteBuffer httpChunk = encodeHttp(byteBuffer);
        if (writeListener == null) {
            final CountDownLatch writeLatch = new CountDownLatch(1);
            final AtomicReference<Throwable> error = new AtomicReference<>();
            downstreamFilter.write(httpChunk, new CompletionHandler<ByteBuffer>() {
                @Override
                public void completed(ByteBuffer result) {
                    writeLatch.countDown();
                }

                @Override
                public void failed(Throwable t) {
                    error.set(t);
                    writeLatch.countDown();
                }
            });

            dataBuffer.clear();

            Throwable t = error.get();
            if (t != null) {
                throw new IOException("Writing data failed", t);
            }
        } else {
            ready = false;
            downstreamFilter.write(httpChunk, new CompletionHandler<ByteBuffer>() {

                @Override
                public void completed(ByteBuffer result) {
                    boolean callListener = false;
                    if (!ready) {
                        callListener = true;
                    }
                    ready = true;
                    dataBuffer.clear();
                    if (callListener) {
                        writeListener.onWritePossible();
                    }
                }

                @Override
                public void failed(Throwable throwable) {
                    writeListener.onError(throwable);
                }
            });
        }
    }

    void open(Filter<ByteBuffer, ?, ?, ?> downstreamFilter) {
        this.downstreamFilter = downstreamFilter;
        initialLatch.countDown();
        ready = true;

        if (writeListener != null) {
            writeListener.onWritePossible();
        }
    }

    void doInitialBlocking() throws IOException {
        if (downstreamFilter != null) {
            return;
        }

        try {
            initialLatch.await();
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }

    /**
     * Set a close listener which will be called when the user closes the stream.
     * <p/>
     * This is used to indicate that the body has been completely written.
     *
     * @param closeListener close listener.
     */
    void setCloseListener(CloseListener closeListener) {
        this.closeListener = closeListener;
    }

    /**
     * Transform raw application data into HTTP body.
     *
     * @param byteBuffer application data.
     * @return http body part.
     */
    protected abstract ByteBuffer encodeHttp(ByteBuffer byteBuffer);

    @Override
    public void close() throws IOException {
        super.close();
        flush();
        if (closeListener != null) {
            closeListener.onClosed();
        }
    }

    /**
     * Set a close listener which will be called when the user closes the stream.
     * <p/>
     * This is used to indicate that the body has been completely written.
     */
    interface CloseListener {

        void onClosed();
    }
}
