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
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ExecutorService;

import org.glassfish.jersey.internal.util.collection.ByteBufferInputStream;

/**
 * Created by petr on 08/08/15.
 */
public class AsynchronousBodyInputStream extends BodyInputStream {

    private static final ByteBuffer EOF = ByteBuffer.wrap(new byte[]{});
    private static final ByteBuffer ERROR = ByteBuffer.wrap(new byte[]{});

    private Mode mode = Mode.UNDECIDED;
    private ReadListener readListener = null;
    private boolean callReadListener = false;
    private Throwable t = null;
    private boolean closedForInput;
    private ExecutorService listenerExecutor = null;

    private ByteBufferInputStream synchronousStream = null;
    private Queue<ByteBuffer> data = new LinkedList<>();

    public void setListenerExecutor(ExecutorService listenerExecutor) {
        assertAsynchronousOperation();
        this.listenerExecutor = listenerExecutor;
        commitMode();
    }

    @Override
    public synchronized boolean isReady() {
        assertAsynchronousOperation();

        if (mode == Mode.UNDECIDED) {
            return false;
        }

        ByteBuffer headBuffer = data.peek();
        boolean ready = true;

        if (headBuffer == null) {
            ready = false;
        }

        if (headBuffer == ERROR) {
            ready = false;
            callOnError(t);
        }

        if (headBuffer == EOF) {
            ready = false;
            callOnAllDataRead();
        }

        if (!ready) {
            // returning false automatically enables listener
            callReadListener = true;
        }

        return ready;
    }

    @Override
    public synchronized void setReadListener(ReadListener readListener) {
        if (this.readListener != null) {
            throw new IllegalStateException("Read listener can be set only once");
        }

        assertAsynchronousOperation();
        if (mode == Mode.SYNCHRONOUS) {
            throw new IllegalStateException("Read listener not supported in synchronous mode");
        }

        this.readListener = readListener;
        commitMode();

        // if there is an error or EOF at the head of the data queue, isReady will handle it
        if (isReady()) {
            callDataAvailable();
        }
    }

    @Override
    public int read() throws IOException {
        commitMode();

        if (mode == Mode.SYNCHRONOUS) {
            return synchronousStream.read();
        }

        validateState();
        return doRead();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (mode == Mode.SYNCHRONOUS) {
            return super.read(b, off, len);
        }

        // some validation borrowed from InputStream
        if (b == null) {
            throw new NullPointerException();
        } else if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        } else if (len == 0) {
            return 0;
        }

        validateState();

        for (int i = 0; i < len; i++) {
            if (!hasDataToRead()) {
                return i;
            }

            b[off + i] = doRead();
        }

        // if we are here we were able to fill the entire buffer
        return len;
    }

    private synchronized byte doRead() {
        // if we are here we passed all the validation, so there must be something to read
        ByteBuffer headBuffer = data.peek();
        byte b = headBuffer.get();

        if (!headBuffer.hasRemaining()) {
            // remove empty buffer
            data.poll();
        }

        return b;
    }

    @Override
    public int available() throws IOException {
        commitMode();
        assertSynchronousOperation();
        return synchronousStream.available();
    }

    @Override
    public long skip(long n) throws IOException {
        commitMode();
        assertSynchronousOperation();
        return synchronousStream.skip(n);
    }

    @Override
    public int tryRead() throws IOException {
        commitMode();
        assertSynchronousOperation();
        return synchronousStream.tryRead();
    }

    @Override
    public int tryRead(byte[] b) throws IOException {
        commitMode();
        assertSynchronousOperation();
        return synchronousStream.tryRead(b);
    }

    @Override
    public int tryRead(byte[] b, int off, int len) throws IOException {
        commitMode();
        assertSynchronousOperation();
        return synchronousStream.tryRead(b, off, len);
    }

    synchronized void onData(ByteBuffer buffer) {
        assertClosedForInput();

        if (mode == Mode.SYNCHRONOUS) {
            try {
                synchronousStream.put(buffer);
            } catch (InterruptedException e) {
                synchronousStream.closeQueue(e);
            }
            return;
        }

        data.add(buffer);

        if (readListener != null && callReadListener) {
            callDataAvailable();
        }
    }

    @Override
    public void close() throws IOException {
        if (mode == Mode.SYNCHRONOUS) {
            synchronousStream.close();
        }
    }

    synchronized void onError(Throwable t) {
        assertClosedForInput();

        closedForInput = true;

        if (mode == Mode.SYNCHRONOUS) {
            synchronousStream.closeQueue(t);
            return;
        }

        if (mode == Mode.ASYNCHRONOUS && callReadListener) {
            callOnError(t);
            return;
        }

        this.t = t;
        data.add(ERROR);
    }

    synchronized void onAllDataRead() {
        assertClosedForInput();
        if (mode == Mode.SYNCHRONOUS) {
            synchronousStream.closeQueue();
            return;
        }

        data.add(EOF);

        if (mode == Mode.ASYNCHRONOUS && callReadListener) {
            callOnAllDataRead();
        }
    }

    private synchronized void commitMode() {
        if (mode == Mode.UNDECIDED) {
            if (readListener != null || listenerExecutor != null) {
                mode = Mode.ASYNCHRONOUS;
            } else {
                mode = Mode.SYNCHRONOUS;
                synchronousStream = new ByteBufferInputStream();
                // move all buffered data to synchronous stream
                for (ByteBuffer b : data) {
                    if (b == EOF) {
                        synchronousStream.closeQueue();
                    } else if (b == ERROR) {
                        synchronousStream.closeQueue(t);
                    } else {
                        try {
                            synchronousStream.put(b);
                        } catch (InterruptedException e) {
                            synchronousStream.closeQueue(e);
                        }
                    }
                }
            }
        }
    }

    private void assertAsynchronousOperation() {
        if (mode == Mode.SYNCHRONOUS) {
            throw new UnsupportedOperationException("Operation not supported in synchronous mode");
        }
    }

    private void assertSynchronousOperation() {
        if (mode == Mode.ASYNCHRONOUS) {
            throw new UnsupportedOperationException("Operation not supported in asynchronous mode");
        }
    }


    private void validateState() {
        if (mode == Mode.ASYNCHRONOUS && !hasDataToRead()) {
            throw new IllegalStateException("Asynchronous write called when stream is in non-ready state");
        }
    }

    private void assertClosedForInput() {
        if (closedForInput) {
            throw new IllegalStateException("This stream has already been closed for input");
        }
    }

    private boolean hasDataToRead() {
        ByteBuffer headBuffer = data.peek();
        if (headBuffer == null || headBuffer == EOF || headBuffer == ERROR) {
            return false;
        }

        return true;
    }

    private void callDataAvailable() {
        if (listenerExecutor == null) {
            readListener.onDataAvailable();
        } else {
            listenerExecutor.submit(new Runnable() {
                @Override
                public void run() {
                    readListener.onDataAvailable();
                }
            });
        }
    }

    private void callOnError(final Throwable t) {
        if (listenerExecutor == null) {
            readListener.onError(t);
        } else {
            listenerExecutor.submit(new Runnable() {
                @Override
                public void run() {
                    readListener.onError(t);
                }
            });
        }
    }

    private void callOnAllDataRead() {
        if (listenerExecutor == null) {
            readListener.onAllDataRead();
        } else {
            listenerExecutor.submit(new Runnable() {
                @Override
                public void run() {
                    readListener.onAllDataRead();
                }
            });
        }
    }

    private enum Mode {
        SYNCHRONOUS,
        ASYNCHRONOUS,
        UNDECIDED
    }
}
