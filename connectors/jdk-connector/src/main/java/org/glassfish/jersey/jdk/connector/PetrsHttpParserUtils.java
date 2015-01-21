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

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;

/**
 * Created by petr on 11/01/15.
 */
class PetrsHttpParserUtils {

    private static final String ENCODING = "ISO-8859-1";

    static final byte CR = (byte) '\r';
    static final byte LF = (byte) '\n';
    static final byte SP = (byte) ' ';
    static final byte HT = (byte) '\t';
    static final byte COMMA = (byte) ',';
    static final byte COLON = (byte) ':';
    static final byte SEMI_COLON = (byte) ';';

    static String parseString(ByteBuffer input, int endIdx) throws ParseException {
        byte[] bytes = new byte[endIdx - input.position()];
        input.get(bytes, 0, endIdx - input.position());
        try {
            return new String(bytes, ENCODING);
        } catch (UnsupportedEncodingException e) {
            throw new ParseException("Unsuported encoding: " + ENCODING, e);
        }

    }

    static int parseInt(ByteBuffer input, int endIdx) throws ParseException {
        String value = parseString(input, endIdx);
        return Integer.valueOf(value);
    }

    static void skipSpaces(final ByteBuffer input) {
        int offset = input.position();
        while (offset < input.limit()) {
            final byte b = input.get(offset);
            if (isNotSpaceAndTab(b)) {
                input.position(offset);
                return;
            }

            offset++;
        }
    }

    private static int getLineEndPosition(ByteBuffer input, MutableInt maxSizeRemaining, String sizeOverflowMessage) throws ParseException {
        LineEndSeekState lineEndSeekState = LineEndSeekState.INIT;

        for (int i = input.position(); i < input.limit(); i++) {

            if (maxSizeRemaining.getValue() - i + input.position() == 0) {
                throw new ParseException(sizeOverflowMessage);
            }

            byte b = input.get(i);
            switch (lineEndSeekState) {
                case INIT: {
                    if (b == CR) {
                        lineEndSeekState = LineEndSeekState.R;
                    }
                    break;
                }
                case R: {
                    if (b == LF) {
                        maxSizeRemaining.setValue(maxSizeRemaining.getValue() - i);
                        return i;
                    } else {
                        lineEndSeekState = LineEndSeekState.INIT;
                        if (b == CR) {
                            lineEndSeekState = LineEndSeekState.R;
                        }
                    }
                    break;
                }
            }
        }

        return -1;
    }

    static Line getLine(ByteBuffer input, MutableInt maxSizeRemaining, String sizeOverflowMessage) throws ParseException {
        int lineEndIdx = getLineEndPosition(input, maxSizeRemaining, sizeOverflowMessage);
        if (lineEndIdx == -1) {
            return null;
        }

        int positionBeforeSkipSpace =input.position();
        PetrsHttpParserUtils.skipSpaces(input);
        if (input.position() + 1 == lineEndIdx) {
            input.position(lineEndIdx + 1);
            return Line.createEmptyLine();
        }

        boolean precededBySpace = false;
        if (positionBeforeSkipSpace != input.position()) {
            precededBySpace = true;
        }

        ByteBuffer lineBuffer = input.slice();
        lineBuffer.limit(lineEndIdx - input.position() - 1);

        input.position(lineEndIdx + 1);

        return Line.createLine(lineBuffer, precededBySpace);
    }

    static boolean isNotSpaceAndTab(final byte b) {
        return (b != SP && b != HT);
    }

    static int findCharacter(ByteBuffer input, byte character) {
        for (int i = input.position(); i < input.limit(); i++) {
            if (input.get(i) == character) {
                return i;
            }
        }

        return -1;
    }

    static class Line {
        ByteBuffer lineBuffer;
        boolean precededBySpace;
        boolean emptyLine;

        private Line(ByteBuffer lineBuffer, boolean precededBySpace, boolean emptyLine) {
            this.lineBuffer = lineBuffer;
            this.precededBySpace = precededBySpace;
            this.emptyLine = emptyLine;
        }

        static Line createEmptyLine() {
            return new Line(null, false, true);
        }

        static Line createLine(ByteBuffer lineBuffer, boolean precededBySpace) {
            return new Line(lineBuffer, precededBySpace, false);
        }
    }

    private enum LineEndSeekState {
        INIT,
        R
    }

    static class MutableInt {
        private int value;

        MutableInt(int value) {
            this.value = value;
        }

        int getValue() {
            return value;
        }

        void setValue(int value) {
            this.value = value;
        }
    }

}
