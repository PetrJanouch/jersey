package org.glassfish.jersey.jdk.connector;

import javax.ws.rs.core.HttpHeaders;
import java.nio.ByteBuffer;
import java.util.List;

import static org.glassfish.jersey.jdk.connector.GrizzlyHttpParserUtils.isSpaceOrTab;
import static org.glassfish.jersey.jdk.connector.HttpParserUtils.getLine;

/**
 * Created by petr on 09/01/15.
 */
class HttpParser {

    private static String TRANSFER_CODING_HEADER = "Transfer-Encoding";
    private static String TRANSFER_CODING_CHUNKED = "chunked";

    private static final int BUFFER_STEP_SIZE = 256;
    // this is package private because of the test
    static final int BUFFER_MAX_SIZE = 40000;
    static final int INIT_BUFFER_SIZE = 1024;

    private final int maxHeaderSize;

    private ByteBuffer buffer = ByteBuffer.allocate(INIT_BUFFER_SIZE);
    private boolean expectContent;
    private HeaderParsingState headerParsingState;

    private HttpResponse httpResponse;
    private TransferEncodingParser transferEncodingParser;
    private boolean complete;
    private boolean headerParsed;
    private String headerName;
    private HttpParserUtils.MutableInt maxSizeRemaining;

    HttpParser(int maxHeaderSize) {
        this.maxHeaderSize = maxHeaderSize;
    }

    void reset(boolean expectContent) {
        this.expectContent = expectContent;
        buffer.clear();
        buffer.flip();
        complete = false;
        headerParsingState = HeaderParsingState.INITIAL_LINE;
        maxSizeRemaining = new HttpParserUtils.MutableInt(maxHeaderSize);
    }

    boolean isHeaderParsed() {
        return headerParsingState == HeaderParsingState.FINISHED;
    }

    boolean isComplete() {
        return complete;
    }

    HttpResponse getHttpResponse() {
        return httpResponse;
    }

    void parse(ByteBuffer input) throws ParseException {

        if (buffer.remaining() > 0) {
            input = Utils.appendBuffers(buffer, input, BUFFER_MAX_SIZE, BUFFER_STEP_SIZE);
        }

        if (!headerParsed && !parseHeader(input)) {
            saveRemaining(input);
            return;
        }

        if (expectContent) {
            if (transferEncodingParser.parse(input)) {
                complete = true;
            } else {
                saveRemaining(input);
            }
        } else { // We don't expect content
            complete = true;
        }

        if (complete && input.hasRemaining()) {
            throw new ParseException("Unknown data remain in the buffer after the HTTP response has been parsed");
        }

        if (complete) {
            httpResponse.getBodyStream().closeQueue();
        }
    }

    private void saveRemaining(ByteBuffer input) {
        if (input.hasRemaining()) {
            if (input != buffer) {
                buffer.clear();
                buffer.flip();
                buffer = Utils.appendBuffers(buffer, input, BUFFER_MAX_SIZE, BUFFER_STEP_SIZE);
            } else {
                buffer.compact();
                buffer.flip();
            }
        }
    }

    private boolean parseHeader(ByteBuffer input) throws ParseException {

        switch (headerParsingState) {
            case INITIAL_LINE: { // parsing initial line
                if (!decodeInitialLineFromBuffer(input)) {
                 //   headerParsingState.checkOverflow("HTTP packet intial line is too large");
                    return false;
                }

                headerParsingState = HeaderParsingState.HEADERS;
            }

            case HEADERS: { // parsing headers
                if (!parseHeadersFromBuffer(input)) {
                 //   headerParsingState.checkOverflow("HTTP packet header is too large");
                    return false;
                }

                headerParsingState = HeaderParsingState.FINISHED;
            }

            case FINISHED: { // Headers are ready
                // if headers get parsed - set the flag
                headerParsed = true;
                decideTransferEncoding();

                return true;
            }

            default:
                throw new IllegalStateException();
        }
    }

    private boolean decodeInitialLineFromBuffer(ByteBuffer input) throws ParseException {
        HttpParserUtils.Line firstLine = getLine(input, maxSizeRemaining, "HTTP header too long");
        if (firstLine == null) {

            return false;
        }

        if (firstLine.emptyLine) {
            throw new ParseException("Empty first line");
        }

        ByteBuffer lineBuffer = firstLine.lineBuffer;

        int versionEndIdx = findSpace(lineBuffer);
        if (versionEndIdx == -1) {
            throw new ParseException("Unexpected format of the first line of a HTTP response: " + firstLine);
        }

        String version = HttpParserUtils.parseString(lineBuffer, versionEndIdx);
        lineBuffer.position(versionEndIdx);

        HttpParserUtils.skipSpaces(lineBuffer);
        int statusCodeEndIdx = findSpace(lineBuffer);

        int statusCode = HttpParserUtils.parseInt(lineBuffer, statusCodeEndIdx);
        if (statusCodeEndIdx == -1) {
            throw new ParseException("Unexpected format of the first line of a HTTP response: " + firstLine);
        }

        HttpParserUtils.skipSpaces(lineBuffer);

        String reasonPhrase = HttpParserUtils.parseString(lineBuffer, lineBuffer.limit());

        httpResponse = new HttpResponse(version, statusCode, reasonPhrase);

        return true;
    }

    boolean parseHeadersFromBuffer(final ByteBuffer input) throws ParseException {
        while (true) {
            HttpParserUtils.Line line = getLine(input, maxSizeRemaining, "HTTP header too long");
            if (line == null) {
                return false;
            }

            if (line.emptyLine) {
                return true;
            }

            if (line.precededBySpace) {
                parseValueFromBuffer(line.lineBuffer);
            } else {

            parseHeaderFromBuffer(line.lineBuffer);
            }

        }
    }

    private void parseHeaderFromBuffer(ByteBuffer headerLine) throws ParseException {
        int nameEndIdx = HttpParserUtils.findCharacter(headerLine, HttpParserUtils.COLON);
        headerName = HttpParserUtils.parseString(headerLine, nameEndIdx);
        headerLine.position(nameEndIdx + 1);
        HttpParserUtils.skipSpaces(headerLine);
        parseValueFromBuffer(headerLine);

    }

    private void parseValueFromBuffer(ByteBuffer headerLine) throws ParseException {
        while (true) {
            int endIdx = HttpParserUtils.findCharacter(headerLine, HttpParserUtils.COMMA);
            if (endIdx != -1) {
                String value = HttpParserUtils.parseString(headerLine, endIdx);
                headerLine.position(endIdx + 1);
                httpResponse.addHeader(headerName, value);
                HttpParserUtils.skipSpaces(headerLine);
                if (headerLine.remaining() == 0) {
                    throw new ParseException("Empty header value");
                }
            } else {
                String value = HttpParserUtils.parseString(headerLine, headerLine.limit());
                httpResponse.addHeader(headerName, value);
                return;
            }
        }
    }

    private int findSpace(final ByteBuffer input) {
        for (int i = input.position(); i < input.limit(); i++) {
            byte b = input.get(i);
            if (isSpaceOrTab(b)) {
                return i;
            }
        }

        return -1;
    }

    private void decideTransferEncoding() throws ParseException {

        int statusCode = httpResponse.getStatusCode();
        if (statusCode == 204 || statusCode == 205 || statusCode == 304) {
            expectContent = false;
        }

        if (httpResponse.getHeaders().size() == 0) {
            expectContent = false;
        }

        List<String> contentLengths = httpResponse.getHeader(HttpHeaders.CONTENT_LENGTH);
        List<String> transferEncodings = httpResponse.getHeader(TRANSFER_CODING_HEADER);

        if (contentLengths != null && transferEncodings != null) {
            // TODO what now? Fail loudly or choose one?
        }

        if (contentLengths == null && transferEncodings == null) {
            // TODO what not?
        }

        if (contentLengths != null) {
            try {
                int bodyLength = Integer.parseInt(contentLengths.get(0));
                transferEncodingParser = TransferEncodingParser.createFixedLengthParser(httpResponse.getBodyStream(), bodyLength);

            } catch (NumberFormatException e) {
                throw new ParseException("Invalid format of status code");
            }
        }

        if (transferEncodings != null) {
            String transferEncoding = transferEncodings.get(0);
            if (TRANSFER_CODING_CHUNKED.equalsIgnoreCase(transferEncoding)) {
                transferEncodingParser = TransferEncodingParser.createChunkParser(httpResponse.getBodyStream(), this);
            }
        }
    }

    private enum HeaderParsingState{
        INITIAL_LINE,
        HEADERS,
        FINISHED
    }
}
