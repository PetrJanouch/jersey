package org.glassfish.jersey.jdk.connector;

import org.glassfish.jersey.internal.util.collection.ByteBufferInputStream;

import javax.ws.rs.core.HttpHeaders;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * @author Petr Janouch (petr.janouch at oracle.com)
 */
class HttpResponseParser {

    private static String TRANSFER_CODING_HEADER = "transfer-coding";
    private static String TRANSFER_CODING_CHUNKED = "chunked";
    private static final String ENCODING = "ISO-8859-1";
    private static final String LINE_SEPARATOR = "\r\n";
    private static final int BUFFER_STEP_SIZE = 256;
    // this is package private because of the test
    static final int BUFFER_MAX_SIZE = 16384;
    static final int INIT_BUFFER_SIZE = 1024;
    private static final byte SPACE_BYTE = (byte) ' ';
    private static final byte TAB_BYTE = (byte) '\t';
    private static final byte COLON_BYTE = (byte) ':';
    private static final byte COMA_BYTE = (byte) ',';

    // parser state
    private volatile boolean headerComplete;
    private volatile boolean complete;
    private volatile ByteBuffer buffer;
    private volatile LineEndSeekState findLineEndState;
    private volatile boolean bodyChunked;
    private volatile ParsingState parsingState;
    private volatile HttpResponse response;

    // body parser state
    private volatile int remainingBodyBytes;

    // chunked body state
    private volatile int remainingChunkBytes;
    private volatile boolean lastChunk;

    void reset() {
        buffer.clear();
        buffer.flip();
        headerComplete = false;
        findLineEndState = LineEndSeekState.INIT;
        remainingBodyBytes = -1;
    }

    HttpResponseParser() {
        buffer = ByteBuffer.allocate(INIT_BUFFER_SIZE);
        reset();
    }

    boolean isHeaderComplete() {
        return headerComplete;
    }

    void parse(ByteBuffer data) throws ParseException {
        checkResponseSize(data);
        buffer = Utils.appendBuffers(buffer, data, BUFFER_MAX_SIZE, BUFFER_STEP_SIZE);

        boolean underflow = false;

        while (!(complete || underflow)) {
            switch (parsingState) {
                case FIRST_LINE: {
                    underflow = parseFirstLine();
                    break;
                }

                case HEADERS: {
                    underflow = parseHeader();
                    break;
                }

                case BODY: {
                    if (bodyChunked) {
                        parseChunkedBody();
                    } else {
                        parseUnchunkedBody();
                    }
                    break;
                }
            }

        }

        buffer.compact();
    }

    private boolean parseFirstLine() throws ParseException {

        byte[] firstLine = getLine();
        if (firstLine == null) {
            return false;
        }

        int versionEndIndex = findCharacter(firstLine, SPACE_BYTE, 0, firstLine.length);
        if (versionEndIndex == -1) {
            throw new ParseException("Unexpected format of the first line of a HTTP response: " + firstLine);
        }
        int statusCodeBegin = findNonSpace(firstLine, versionEndIndex, firstLine.length);
        int statusCodeEndIndex = findCharacter(firstLine, SPACE_BYTE, statusCodeBegin, firstLine.length);
        if (statusCodeEndIndex == -1) {
            throw new ParseException("Unexpected format of the first line of a HTTP response: " + firstLine);
        }
        int reasonBeginIndex = findNonSpace(firstLine, statusCodeEndIndex, firstLine.length);

        try {
            String version = new String(firstLine, 0, versionEndIndex, ENCODING);
            String statusCode = new String(firstLine, statusCodeBegin, statusCodeEndIndex, ENCODING);
            String reasonPhrase = new String(firstLine, reasonBeginIndex, firstLine.length, ENCODING);
            int status = Integer.parseInt(statusCode);
            response = new HttpResponse(version, status, reasonPhrase);
            parsingState = ParsingState.HEADERS;

        } catch (UnsupportedEncodingException e) {
            throw new ParseException("Encoding " + ENCODING + " not supported", e);
        } catch (NumberFormatException e) {
            throw new ParseException("Invalid format of status code");
        }

        return true;
    }

    private boolean parseHeader() throws ParseException {
        byte[] header = getLine();

        if (header == null) {
            return false;
        }

        if (header.length == 0) {
            processKnownHeaders();
            parsingState = ParsingState.BODY;
            return true;
        }

        try {

            int separatorIndex = findCharacter(header, COLON_BYTE, 0, header.length);
            if (separatorIndex == -1) {
                throw new ParseException("Unexpected header format: " + new String(header, ENCODING));
            }

            String headerKey = new String(header, 0, separatorIndex, ENCODING);

            int lastValueSeparatorIndex = separatorIndex + 1;

            while (true) {

                int valueSeparatorIndex = findCharacter(header, COMA_BYTE, lastValueSeparatorIndex + 2, header.length);
                if (valueSeparatorIndex == -1) {
                    response.addHeader(headerKey, new String(header, lastValueSeparatorIndex + 2, header.length - 2, ENCODING));
                    break;
                }

                response.addHeader(headerKey, new String(header, lastValueSeparatorIndex + 2, valueSeparatorIndex, ENCODING));
                lastValueSeparatorIndex = valueSeparatorIndex;
            }
        } catch (UnsupportedEncodingException e) {
            throw new ParseException("Encoding " + ENCODING + " not supported", e);
        }

        return true;
    }

    private void processKnownHeaders() throws ParseException {

        List<String> contentLengths = response.getHeader(HttpHeaders.CONTENT_LENGTH);
        List<String> transferEncodings = response.getHeader(ENCODING);

        if (contentLengths != null &&  transferEncodings != null) {
            // TODO what now? Fail loudly or choose one?
        }

        if (contentLengths == null && transferEncodings == null) {
            // TODO what not?
        }

        if (contentLengths != null) {
            try {
                remainingBodyBytes = Integer.parseInt(contentLengths.get(0));
                if (remainingBodyBytes == 0) {
                    response.getBodyStream().closeQueue();
                    complete = true;
                }
            } catch (NumberFormatException e) {
                throw new ParseException("Invalid format of status code");
            }
        }

        if (transferEncodings != null) {
            String transferEncoding = transferEncodings.get(0);
            if (TRANSFER_CODING_CHUNKED.equalsIgnoreCase(transferEncoding)) {
                bodyChunked = true;
            }
        }
    }

    private void parseUnchunkedBody() throws ParseException {
        if (buffer.limit() > remainingBodyBytes) {
            throw new ParseException("Body size exceeds declaredSize");
        }

        ByteBuffer data = ByteBuffer.allocate(buffer.limit());
        data = Utils.appendBuffers(data, buffer, BUFFER_MAX_SIZE, BUFFER_STEP_SIZE);

        ByteBufferInputStream bodyStream = response.getBodyStream();
        try {
            bodyStream.put(data);
        }catch (InterruptedException e) {
            // TODO
        }
    }

    private boolean parseChunkedBody() throws ParseException {
        if (remainingChunkBytes == -1) {
            return parseChunkSize();
        }

        if (lastChunk) {
            int lineEnd = getLineEndPosition(buffer);
            if (lineEnd == -1) {
                return false;
            }

            if (lineEnd != 0 ) {
                // TODO
                throw new ParseException("");
            }

            buffer.position(buffer.position() + 2);
            return true;
        }

        if (remainingChunkBytes == 0) {
            remainingChunkBytes = -1;
        }

        int consumedBytes;
        if (remainingChunkBytes > buffer.remaining()) {
            consumedBytes = buffer.remaining();

        } else {
            consumedBytes = remainingChunkBytes;
        }

        byte[] data = new byte[consumedBytes];
        buffer.get(data);
        ByteBuffer chunkData = ByteBuffer.wrap(data);
      //  response.getBodyStream().put(chunkData);
        return false;

    }

    private boolean parseChunkSize() throws ParseException {
        byte[] line = getLine();

        if (line == null) {
            return false;
        }
        String sizeHex = parseString(line, 0, line.length);
        sizeHex = sizeHex.trim();
        try {
            remainingChunkBytes = Integer.parseInt(sizeHex, 16);
        }catch (Exception e) {
            throw new ParseException("Chunk size parsing failed", e);
        }

        return false;

    }

    private byte[] getLine() {
        int lineEndPosition = getLineEndPosition(buffer);
        if (lineEndPosition == -1) {
            return null;
        }
        byte[] result = new byte[buffer.position() + lineEndPosition - 2];
        buffer.get(result);
        // remove \r\n
        buffer.position(buffer.position() + 2);
        return result;
    }

    private int findCharacter(byte[] data, byte character, int fromIndex, int toIndex) {
        for (int i = fromIndex; i < toIndex; i++) {
            if (data[i] == character) {
                return i;
            }
        }

        return -1;
    }

    private int findNonSpace(byte[] data, int fromIndex, int toIndex) {
        for (int i = fromIndex; i < toIndex; i++) {
            if (data[i] != SPACE_BYTE && data[i] != TAB_BYTE) {
                return i;
            }
        }

        return -1;
    }


    private void checkResponseSize(ByteBuffer partToBeAppended) throws ParseException {
        if (buffer.remaining() + partToBeAppended.remaining() > BUFFER_MAX_SIZE) {
            throw new ParseException("Upgrade response too big, sizes only up to " + BUFFER_MAX_SIZE + "B are supported.");
        }
    }

    private int getLineEndPosition(ByteBuffer buffer) {
        byte[] bytes = buffer.array();

        for (int i = buffer.position(); i < buffer.limit(); i++) {
            byte b = bytes[i];
            switch (findLineEndState) {
                case INIT: {
                    if (b == '\r') {
                        findLineEndState = LineEndSeekState.R;
                    }
                    break;
                }
                case R: {
                    if (b == '\n') {
                        return i;
                    } else {
                        findLineEndState = LineEndSeekState.INIT;
                        if (b == '\r') {
                            findLineEndState = LineEndSeekState.R;
                        }
                    }
                    break;
                }
            }
        }
        return -1;
    }

    private String parseString(byte[] data, int startIndex, int endIndex) throws ParseException {
        try {
            new String(data, startIndex, endIndex, ENCODING);
        } catch (UnsupportedEncodingException e) {
            throw new ParseException("Unsupported encoding: " + ENCODING, e);
        }

        return "";
    }

    private enum LineEndSeekState {
        INIT,
        R,
    }

    private enum ParsingState {
        FIRST_LINE,
        HEADERS,
        BODY
    }
}
