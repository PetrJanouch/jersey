/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2014 Oracle and/or its affiliates. All rights reserved.
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

import org.glassfish.jersey.internal.util.collection.ByteBufferInputStream;

import java.nio.ByteBuffer;

/**
 * Created by petr on 11/01/15.
 */
abstract class PetrsTransferEncodingParser {
    abstract boolean parse(ByteBuffer input) throws ParseException;

    static PetrsTransferEncodingParser createFixedLengthParser(ByteBufferInputStream responseBody, int expectedLength) {
        return new FixedLengthEncodingParser(responseBody, expectedLength);
    }

    static PetrsTransferEncodingParser createChunkParser(ByteBufferInputStream responseBody, PetrsHttpParser httpParser) {
        return new ChunkedEncodingParser(responseBody, httpParser);
    }

    private static class FixedLengthEncodingParser extends PetrsTransferEncodingParser {

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

    private static class ChunkedEncodingParser extends PetrsTransferEncodingParser {

        private static final int MAX_CHUNK_HEADER_SIZE = 100;

        private final ByteBufferInputStream responseBody;
        private final PetrsHttpParser httpParser;

        private ParsingState parsingState = ParsingState.BODY;
        private ChunkParsingState chunkParsingState = ChunkParsingState.HEADER;
        private boolean lastChunk = false;

        private int chunkRemainder = -1;
        private PetrsHttpParserUtils.MutableInt maxChunkHeaderSizeRemaining;


        ChunkedEncodingParser(ByteBufferInputStream responseBody, PetrsHttpParser httpParser) {
            this.responseBody = responseBody;
            this.httpParser = httpParser;
            this.maxChunkHeaderSizeRemaining = new PetrsHttpParserUtils.MutableInt(MAX_CHUNK_HEADER_SIZE);
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
                    PetrsHttpParserUtils.Line line = PetrsHttpParserUtils.getLine(input, new PetrsHttpParserUtils.MutableInt(2), "Unexpected chunk format.");
                    if (line == null) {
                        return false;
                    }

                    if (!line.emptyLine) {
                        throw new ParseException("Unexpected chunk format");
                    }

                    chunkParsingState = ChunkParsingState.HEADER;
                    this.maxChunkHeaderSizeRemaining = new PetrsHttpParserUtils.MutableInt(MAX_CHUNK_HEADER_SIZE);
                    return true;
                }
            }

            throw new IllegalStateException("Should never et here");
        }

        private boolean parseChunkHeader(ByteBuffer input) throws ParseException {
            PetrsHttpParserUtils.Line line = PetrsHttpParserUtils.getLine(input, maxChunkHeaderSizeRemaining, "Chunk header overflow");
            if (line == null) {
                return false;
            }

            int lengthEndIdx = line.lineBuffer.limit();
            int extensionIdx = PetrsHttpParserUtils.findCharacter(line.lineBuffer, PetrsHttpParserUtils.SEMI_COLON);
            if (extensionIdx != -1) {
                lengthEndIdx = extensionIdx;
            }
            String lengthHex = PetrsHttpParserUtils.parseString(line.lineBuffer, lengthEndIdx);
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
