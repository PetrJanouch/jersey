package org.glassfish.jersey.jdk.connector;

import javax.ws.rs.core.HttpHeaders;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Created by petr on 10/12/14.
 */
class HttpFilter extends Filter<HttpRequest, HttpResponse, ByteBuffer, ByteBuffer> {

    private static String TRANSFER_CODING_HEADER = "transfer-coding";
    private static String TRANSFER_CODING_CHUNKED = "chunked";
    private static String HEAD_METHOD = "HEAD";
    private static String CONNECT_METHOD = "CONNECT";

    private final GrizzlyHttpParser httpParser;
    private boolean expectingReply = false;
    /**
     * Constructor.
     *
     * @param downstreamFilter downstream filter. Accessible directly as {@link #downstreamFilter} protected field.
     */
    HttpFilter(Filter downstreamFilter, int maxHeaderSize) {
        super(downstreamFilter);
        this.httpParser = new GrizzlyHttpParser(maxHeaderSize);
    }

    @Override
    void write(final HttpRequest httpRequest, final CompletionHandler<HttpRequest> completionHandler) {
        addTransportHeaders(httpRequest);
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
                expectingReply = true;
                completionHandler.completed(httpRequest);
                break;
            }

            case CHUNKED: {
                ChunkedResponseOutputStream chunkOutputStream = new ChunkedResponseOutputStream(downstreamFilter, httpRequest.getChunkSize()) {
                    @Override
                    void onClosed() {
                        prepareForReply(httpRequest, completionHandler);
                    }
                };

                httpRequest.setBodyOutputStream(chunkOutputStream);
                break;
            }

            case STREAMING: {
                ResponseOutputStream streamingOutputStream = new ResponseOutputStream(downstreamFilter) {
                    @Override
                    void onClosed() {
                        prepareForReply(httpRequest, completionHandler);
                    }
                };

                httpRequest.setBodyOutputStream(streamingOutputStream);
                break;
            }

            case BUFFERED: {
                downstreamFilter.write(httpRequest.getBufferedBody(), new CompletionHandler<ByteBuffer>() {
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

    private void addTransportHeaders(HttpRequest httpRequest) {
        switch (httpRequest.getBodyMode()) {
            case CHUNKED: {
                httpRequest.addHeader(TRANSFER_CODING_HEADER, TRANSFER_CODING_CHUNKED);
                break;
            }

            case BUFFERED:
            case STREAMING:{
                httpRequest.addHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(httpRequest.getBodySize()));
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
            httpParser.getHttpResponse().getBodyStream().closeQueue();
        }

        return false;
    }
}
