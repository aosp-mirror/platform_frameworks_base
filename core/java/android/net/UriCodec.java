/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net;

import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;

/**
 * Decodes “application/x-www-form-urlencoded” content.
 *
 * @hide
 */
public final class UriCodec {

    private UriCodec() {}

    /**
     * Interprets a char as hex digits, returning a number from -1 (invalid char) to 15 ('f').
     */
    private static int hexCharToValue(char c) {
        if ('0' <= c && c <= '9') {
            return c - '0';
        }
        if ('a' <= c && c <= 'f') {
            return 10 + c - 'a';
        }
        if ('A' <= c && c <= 'F') {
            return 10 + c - 'A';
        }
        return -1;
    }

    private static URISyntaxException unexpectedCharacterException(
            String uri, String name, char unexpected, int index) {
        String nameString = (name == null) ? "" :  " in [" + name + "]";
        return new URISyntaxException(
                uri, "Unexpected character" + nameString + ": " + unexpected, index);
    }

    private static char getNextCharacter(String uri, int index, int end, String name)
             throws URISyntaxException {
        if (index >= end) {
            String nameString = (name == null) ? "" :  " in [" + name + "]";
            throw new URISyntaxException(
                    uri, "Unexpected end of string" + nameString, index);
        }
        return uri.charAt(index);
    }

    /**
     * Decode a string according to the rules of this decoder.
     *
     * - if {@code convertPlus == true} all ‘+’ chars in the decoded output are converted to ‘ ‘
     *   (white space)
     * - if {@code throwOnFailure == true}, an {@link IllegalArgumentException} is thrown for
     *   invalid inputs. Else, U+FFFd is emitted to the output in place of invalid input octets.
     */
    public static String decode(
            String s, boolean convertPlus, Charset charset, boolean throwOnFailure) {
        StringBuilder builder = new StringBuilder(s.length());
        appendDecoded(builder, s, convertPlus, charset, throwOnFailure);
        return builder.toString();
    }

    /**
     * Character to be output when there's an error decoding an input.
     */
    private static final char INVALID_INPUT_CHARACTER = '\ufffd';

    private static void appendDecoded(
            StringBuilder builder,
            String s,
            boolean convertPlus,
            Charset charset,
            boolean throwOnFailure) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .replaceWith("\ufffd")
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        // Holds the bytes corresponding to the escaped chars being read (empty if the last char
        // wasn't a escaped char).
        ByteBuffer byteBuffer = ByteBuffer.allocate(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            i++;
            switch (c) {
                case '+':
                    flushDecodingByteAccumulator(
                            builder, decoder, byteBuffer, throwOnFailure);
                    builder.append(convertPlus ? ' ' : '+');
                    break;
                case '%':
                    // Expect two characters representing a number in hex.
                    byte hexValue = 0;
                    for (int j = 0; j < 2; j++) {
                        try {
                            c = getNextCharacter(s, i, s.length(), null /* name */);
                        } catch (URISyntaxException e) {
                            // Unexpected end of input.
                            if (throwOnFailure) {
                                throw new IllegalArgumentException(e);
                            } else {
                                flushDecodingByteAccumulator(
                                        builder, decoder, byteBuffer, throwOnFailure);
                                builder.append(INVALID_INPUT_CHARACTER);
                                return;
                            }
                        }
                        i++;
                        int newDigit = hexCharToValue(c);
                        if (newDigit < 0) {
                            if (throwOnFailure) {
                                throw new IllegalArgumentException(
                                        unexpectedCharacterException(s, null /* name */, c, i - 1));
                            } else {
                                flushDecodingByteAccumulator(
                                        builder, decoder, byteBuffer, throwOnFailure);
                                builder.append(INVALID_INPUT_CHARACTER);
                                break;
                            }
                        }
                        hexValue = (byte) (hexValue * 0x10 + newDigit);
                    }
                    byteBuffer.put(hexValue);
                    break;
                default:
                    flushDecodingByteAccumulator(builder, decoder, byteBuffer, throwOnFailure);
                    builder.append(c);
            }
        }
        flushDecodingByteAccumulator(builder, decoder, byteBuffer, throwOnFailure);
    }

    private static void flushDecodingByteAccumulator(
            StringBuilder builder,
            CharsetDecoder decoder,
            ByteBuffer byteBuffer,
            boolean throwOnFailure) {
        if (byteBuffer.position() == 0) {
            return;
        }
        byteBuffer.flip();
        try {
            builder.append(decoder.decode(byteBuffer));
        } catch (CharacterCodingException e) {
            if (throwOnFailure) {
                throw new IllegalArgumentException(e);
            } else {
                builder.append(INVALID_INPUT_CHARACTER);
            }
        } finally {
            // Use the byte buffer to write again.
            byteBuffer.flip();
            byteBuffer.limit(byteBuffer.capacity());
        }
    }
}
