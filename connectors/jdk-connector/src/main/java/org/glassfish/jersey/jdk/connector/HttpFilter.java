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

import java.nio.ByteBuffer;

/**
 * Created by petr on 10/12/14.
 */
class HttpFilter extends Filter<HttpRequest, HttpResponse, ByteBuffer, ByteBuffer> {


    private static String HEAD_METHOD = "HEAD";
    private static String CONNECT_METHOD = "CONNECT";

    private final GrizzlyHttpParser httpParser;
    private boolean expectingReply = false;
    /**
     * Constructor.
     *
     * @param downstreamFilter downstream filter. Accessible directly as {@link #downstreamFilter} protected field.
     */
    HttpFilter(Filter downstreamFilter, int maxHeaderSize, int maxBufferSize) {
        super(downstreamFilter);
        this.httpParser = new GrizzlyHttpParser(maxHeaderSize, maxBufferSize);
    }

    @Override
    void write(final HttpRequest httpRequest, final CompletionHandler<HttpRequest> completionHandler) {
        ByteBuffer header = HttpRequestEncoder.encodeHeader(httpRequest);
        downstreamFilter.write(header, new CompletionHandler<ByteBuffer>() {
            @Override
            public void failed(Throwable throwable) {
                completionHandler.failed(throwable);
            }

            @Override
            public void completed(ByteBuffer result) {
                writeBody(httpRequest, completionHandler);
            }
        });
    }

    private void writeBody(final HttpRequest httpRequest, final CompletionHandler<HttpRequest> completionHandler) {
        switch (httpRequest.getBodyMode()) {
            case NONE: {
                prepareForReply(httpRequest, completionHandler);
                break;
            }

            case CHUNKED:
            case STREAMING: {
                AsynchronousBodyOutputStream bodyStream = (AsynchronousBodyOutputStream)httpRequest.getBodyStream();
                bodyStream.open(downstreamFilter);
                bodyStream.setCloseListener(new AsynchronousBodyOutputStream.CloseListener() {
                    @Override
                    public void onClosed() {
                        prepareForReply(httpRequest, completionHandler);
                    }
                });
                break;
            }

            case BUFFERED: {
                ByteBuffer body = httpRequest.getBufferedBody();
                downstreamFilter.write(body, new CompletionHandler<ByteBuffer>() {
                    @Override
                    public void failed(Throwable throwable) {
                        completionHandler.failed(throwable);
                    }

                    @Override
                    public void completed(ByteBuffer result) {
                        prepareForReply(httpRequest, completionHandler);
                    }
                });

                break;
            }
        }
    }

    private void prepareForReply(HttpRequest httpRequest, CompletionHandler<HttpRequest> completionHandler) {
        expectingReply = true;
        completionHandler.completed(httpRequest);

        boolean expectResponseBody = true;

        if(HEAD_METHOD.equals(httpRequest.getMethod()) || CONNECT_METHOD.equals(httpRequest.getMethod())) {
            expectResponseBody = false;
        }

        httpParser.reset(expectResponseBody);
    }

    @Override
    boolean processRead(ByteBuffer data) {

        if (!expectingReply) {
            onError(new IllegalStateException("Received unexpected Response"));
            return false;
        }

        boolean headerParsed = httpParser.isHeaderParsed();
        try {
            httpParser.parse(data);
        } catch (ParseException e) {
            onError(e);
        }

        if (!headerParsed && httpParser.isHeaderParsed()) {
            upstreamFilter.onRead(httpParser.getHttpResponse());
        }

        if (httpParser.isComplete()) {
            httpParser.getHttpResponse().getBodyStream().onAllDataRead();
        }

        return false;
    }
}
