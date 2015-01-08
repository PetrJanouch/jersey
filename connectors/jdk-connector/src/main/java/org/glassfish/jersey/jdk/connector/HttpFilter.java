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

    private volatile boolean expectingReply = false;
    /**
     * Constructor.
     *
     * @param downstreamFilter downstream filter. Accessible directly as {@link #downstreamFilter} protected field.
     */
    HttpFilter(Filter downstreamFilter) {
        super(downstreamFilter);
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
                    public void close() throws IOException {
                        super.close();
                        expectingReply = true;
                        completionHandler.completed(httpRequest);
                    }
                };

                httpRequest.setBodyOutputStream(chunkOutputStream);
                break;
            }

            case STREAMING: {
                ResponseOutputStream streamingOutputStream = new ResponseOutputStream(downstreamFilter) {
                    @Override
                    public void close() {
                        expectingReply = true;
                        completionHandler.completed(httpRequest);
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
                        expectingReply = true;
                        completionHandler.completed(httpRequest);
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

            case BUFFERED: {
                int bodySize = httpRequest.getBufferedBody().limit();
                httpRequest.addHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(bodySize));
            }
        }
    }

    @Override
    boolean processRead(ByteBuffer data) {
        if (!expectingReply) {
            onError(new IllegalStateException("Received unexpected Response"));
            return false;
        }

       // upstreamFilter.onRead(new HttpResponse());
        return false;
    }
}
