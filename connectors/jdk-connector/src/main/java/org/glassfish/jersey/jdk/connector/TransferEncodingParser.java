package org.glassfish.jersey.jdk.connector;

import org.glassfish.jersey.internal.util.collection.ByteBufferInputStream;

import java.nio.ByteBuffer;

/**
 * Created by petr on 11/01/15.
 */
abstract class TransferEncodingParser {
    abstract boolean parse(ByteBuffer input) throws ParseException;

    static TransferEncodingParser createFixedLengthParser(ByteBufferInputStream responseBody, int expectedLength) {
        return new FixedLengthEncodingParser(responseBody, expectedLength);
    }

    static TransferEncodingParser createChunkParser(ByteBufferInputStream responseBody, HttpParser httpParser) {
        return new ChunkedEncodingParser(responseBody, httpParser);
    }

    private static class FixedLengthEncodingParser extends TransferEncodingParser {

        private final int expectedLength;
        private final ByteBufferInputStream responseBody;
        private volatile int consumedLength = 0;

        FixedLengthEncodingParser(ByteBufferInputStream responseBody, int expectedLength) {
            this.expectedLength = expectedLength;
            this.responseBody = responseBody;
        }

        @Override
        boolean parse(ByteBuffer input) throws ParseException {
            if (input.remaining() + consumedLength > expectedLength) {
                throw new ParseException("Body size exceeds declaredSize");
            }

            byte[] data = new byte[input.remaining()];
            input.get(data);
            ByteBuffer parsed = ByteBuffer.wrap(data);

            try {
                responseBody.put(parsed);
            } catch (InterruptedException e) {
                // TODO
            }

            consumedLength += data.length;
            if (consumedLength == expectedLength) {
                return true;
            }

            return false;
        }
    }

    private static class ChunkedEncodingParser extends TransferEncodingParser {

        private static final int MAX_CHUNK_HEADER_SIZE = 100;

        private final ByteBufferInputStream responseBody;
        private final HttpParser httpParser;

        private ParsingState parsingState = ParsingState.BODY;
        private ChunkParsingState chunkParsingState = ChunkParsingState.HEADER;
        private boolean lastChunk = false;

        private int chunkRemainder = -1;
        private HttpParserUtils.MutableInt maxChunkHeaderSizeRemaining;


        ChunkedEncodingParser(ByteBufferInputStream responseBody, HttpParser httpParser) {
            this.responseBody = responseBody;
            this.httpParser = httpParser;
            this.maxChunkHeaderSizeRemaining = new HttpParserUtils.MutableInt(MAX_CHUNK_HEADER_SIZE);
        }

        @Override
        boolean parse(ByteBuffer input) throws ParseException {
            switch (parsingState) {
                case BODY: {
                    while (true) {
                        if (!parseChunk(input)) {
                            return false;
                        }

                        if (lastChunk) {
                            parsingState = ParsingState.TRAILER;
                            break;
                        }
                    }
                }

                case TRAILER: {
                    return httpParser.parseHeadersFromBuffer(input);
                }
            }

            throw new IllegalStateException("Should never et here");
        }

        private boolean parseChunk(ByteBuffer input) throws ParseException {
            switch (chunkParsingState) {
                case HEADER: {
                    if (!parseChunkHeader(input)) {
                        return false;
                    }

                    if (chunkRemainder == 0) {
                        lastChunk = true;
                        return true;
                    }

                    chunkParsingState = ChunkParsingState.BODY;
                }

                case BODY: {
                    if (!parseChunkBody(input)) {
                        return false;
                    }

                    chunkParsingState = ChunkParsingState.CRLF;
                }

                case CRLF: {
                    HttpParserUtils.Line line = HttpParserUtils.getLine(input, new HttpParserUtils.MutableInt(2), "Unexpected chunk format.");
                    if (line == null) {
                        return false;
                    }

                    if (!line.emptyLine) {
                        throw new ParseException("Unexpected chunk format");
                    }

                    chunkParsingState = ChunkParsingState.HEADER;
                    this.maxChunkHeaderSizeRemaining = new HttpParserUtils.MutableInt(MAX_CHUNK_HEADER_SIZE);
                    return true;
                }
            }

            throw new IllegalStateException("Should never et here");
        }

        private boolean parseChunkHeader(ByteBuffer input) throws ParseException {
            HttpParserUtils.Line line = HttpParserUtils.getLine(input, maxChunkHeaderSizeRemaining, "Chunk header overflow");
            if (line == null) {
                return false;
            }

            int lengthEndIdx = line.lineBuffer.limit();
            int extensionIdx = HttpParserUtils.findCharacter(line.lineBuffer, HttpParserUtils.SEMI_COLON);
            if (extensionIdx != -1) {
                lengthEndIdx = extensionIdx;
            }
            String lengthHex = HttpParserUtils.parseString(line.lineBuffer, lengthEndIdx);
            chunkRemainder = Integer.parseInt(lengthHex, 16);
            return true;
        }

        private boolean parseChunkBody(ByteBuffer input) throws ParseException {
            ByteBuffer data;
            if (input.remaining() > chunkRemainder) {
                // If input Buffer has part of the next message - slice it
                data = Utils.split(input, input.position() + chunkRemainder);

            } else {
                data = Utils.split(input, input.position() + input.remaining());
            }

            try {
                chunkRemainder -= data.remaining();
                responseBody.put(data);
            } catch (InterruptedException e) {
                //
            }

            return chunkRemainder == 0;
        }
    }

    private static enum ParsingState {
        BODY,
        TRAILER,
    }

    private static enum ChunkParsingState {
        HEADER,
        BODY,
        CRLF,
    }
}
