package org.glassfish.jersey.jdk.connector;

import java.nio.ByteBuffer;
import java.util.*;

/**
 * Created by petr on 07/01/15.
 */
class GrizzlyHttpParserUtils {

    static final byte CR = (byte) '\r';
    static final byte LF = (byte) '\n';
    static final byte SP = (byte) ' ';
    static final byte HT = (byte) '\t';
    static final byte COMMA = (byte) ',';
    static final byte COLON = (byte) ':';
    static final byte SEMI_COLON = (byte) ';';
    static final byte A = (byte) 'A';
    static final byte Z = (byte) 'Z';
    static final byte a = (byte) 'a';
    static final byte LC_OFFSET = A - a;

    static int skipSpaces(final ByteBuffer input, int offset,
                          final int packetLimit) {
        final int limit = Math.min(input.limit(), packetLimit);
        while (offset < limit) {
            final byte b = input.get(offset);
            if (isNotSpaceAndTab(b)) {
                return offset;
            }

            offset++;
        }

        return -1;
    }

    static boolean isNotSpaceAndTab(final byte b) {
        return (b != GrizzlyHttpParserUtils.SP && b != GrizzlyHttpParserUtils.HT);
    }

    static boolean isSpaceOrTab(final byte b) {
        return (b == GrizzlyHttpParserUtils.SP || b == GrizzlyHttpParserUtils.HT);
    }


    static class HeaderParsingState {
        int packetLimit;

        int state;
        int subState;

        int start;
        int offset;
        int checkpoint = -1; // extra parsing state field
        int checkpoint2 = -1; // extra parsing state field

        String headerName;

        long parsingNumericValue;

        int contentLengthHeadersCount;   // number of Content-Length headers in the HTTP header
        boolean contentLengthsDiffer;

        HeaderParsingState(int maxHeaderSize) {
            packetLimit = offset + maxHeaderSize;
        }

        void recycle() {
            state = 0;
            subState = 0;
            start = 0;
            offset = 0;
            checkpoint = -1;
            checkpoint2 = -1;
            parsingNumericValue = 0;
            contentLengthHeadersCount = 0;
            contentLengthsDiffer = false;
        }

        void checkOverflow(final String errorDescriptionIfOverflow) throws ParseException {
            if (offset < packetLimit) {
                return;
            }

            throw new ParseException(errorDescriptionIfOverflow);
        }
    }

    static class ContentParsingState {
        boolean isLastChunk;
        int chunkContentStart = -1;
        long chunkLength = -1;
        long chunkRemainder = -1;

        // the payload bytes read after processing was complete
        long remainderBytesRead;

        final Map<String, List<String>> trailerHeaders = new HashMap<>(0);

        ByteBuffer[] contentDecodingRemainders = new ByteBuffer[1];

        void recycle() {
            isLastChunk = false;
            chunkContentStart = -1;
            chunkLength = -1;
            chunkRemainder = -1;
            remainderBytesRead = 0;
            trailerHeaders.clear();
            contentDecodingRemainders = null;
        }

    }
}
