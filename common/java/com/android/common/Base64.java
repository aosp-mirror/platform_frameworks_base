/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.common;

/**
 * Utilities for encoding and decoding the Base64 encoding.  See RFCs
 * 2045 and 3548.
 */
public class Base64 {
    /**
     * Default values for encoder/decoder flags.
     */
    public static final int DEFAULT = 0;

    /**
     * Encoder flag bit to indicate you want the padding '='
     * characters at the end (if any) to be omitted.
     */
    public static final int NO_PADDING = 1;

    /**
     * Encoder flag bit to indicate you want all line terminators to
     * be omitted (ie, the output will be on one long line).
     */
    public static final int NO_WRAP = 2;

    /**
     * Encoder flag bit to indicate you want lines to be ended with
     * CRLF instead of just LF.
     */
    public static final int CRLF = 4;

    /**
     * Encoder/decoder flag bit to indicate using the "web safe"
     * variant of Base64 (see RFC 3548 section 4) where '-' and '_'
     * are used in place of '+' and '/'.
     */
    public static final int WEB_SAFE = 8;

    /**
     * Lookup table for turning bytes into their position in the
     * Base64 alphabet.
     */
    private static final int DECODE[] = {
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 62, -1, -1, -1, 63,
        52, 53, 54, 55, 56, 57, 58, 59, 60, 61, -1, -1, -1, -2, -1, -1,
        -1,  0,  1,  2,  3,  4,  5,  6,  7,  8,  9, 10, 11, 12, 13, 14,
        15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, -1, -1, -1, -1, -1,
        -1, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
        41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    };

    /**
     * Decode lookup table for the "web safe" variant (RFC 3548
     * sec. 4) where - and _ replace + and /.
     */
    private static final int DECODE_WEBSAFE[] = {
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 62, -1, -1,
        52, 53, 54, 55, 56, 57, 58, 59, 60, 61, -1, -1, -1, -2, -1, -1,
        -1,  0,  1,  2,  3,  4,  5,  6,  7,  8,  9, 10, 11, 12, 13, 14,
        15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, -1, -1, -1, -1, 63,
        -1, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
        41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    };

    /** Non-data values in the DECODE arrays. */
    private static final int SKIP = -1;
    private static final int EQUALS = -2;

    /**
     * Decode the Base64-encoded data in input and return the data in
     * a new byte array.
     *
     * The padding '=' characters at the end are considered optional, but
     * if any are present, there must be the correct number of them.
     *
     * @param input the input String to decode, which is converted to
     *               bytes using the default charset
     * @param flags  controls certain features of the decoded output.
     *               Pass {@code DEFAULT} to decode standard Base64.
     *
     * @throws IllegalArgumentException if the input contains
     * incorrect padding
     */
    public static byte[] decode(String str, int flags) {
        return decode(str.getBytes(), flags);
    }

    /**
     * Decode the Base64-encoded data in input and return the data in
     * a new byte array.
     *
     * The padding '=' characters at the end are considered optional, but
     * if any are present, there must be the correct number of them.
     *
     * @param input the input array to decode
     * @param flags  controls certain features of the decoded output.
     *               Pass {@code DEFAULT} to decode standard Base64.
     *
     * @throws IllegalArgumentException if the input contains
     * incorrect padding
     */
    public static byte[] decode(byte[] input, int flags) {
        return decode(input, 0, input.length, flags);
    }

    /**
     * Decode the Base64-encoded data in input and return the data in
     * a new byte array.
     *
     * The padding '=' characters at the end are considered optional, but
     * if any are present, there must be the correct number of them.
     *
     * @param input  the data to decode
     * @param offset the position within the input array at which to start
     * @param len    the number of bytes of input to decode
     * @param flags  controls certain features of the decoded output.
     *               Pass {@code DEFAULT} to decode standard Base64.
     *
     * @throws IllegalArgumentException if the input contains
     * incorrect padding
     */
    public static byte[] decode(byte[] input, int offset, int len, int flags) {
        int p = offset;
        // Allocate space for the most data the input could represent.
        // (It could contain less if it contains whitespace, etc.)
        byte[] output = new byte[len*3/4];
        len += offset;
        int op = 0;

        final int[] decode = ((flags & WEB_SAFE) == 0) ?
            DECODE : DECODE_WEBSAFE;

        int state = 0;
        int value = 0;

        while (p < len) {

            // Try the fast path:  we're starting a new tuple and the
            // next four bytes of the input stream are all data
            // bytes.  This corresponds to going through states
            // 0-1-2-3-0.  We expect to use this method for most of
            // the data.
            //
            // If any of the next four bytes of input are non-data
            // (whitespace, etc.), value will end up negative.  (All
            // the non-data values in decode are small negative
            // numbers, so shifting any of them up and or'ing them
            // together will result in a value with its top bit set.)
            //
            // You can remove this whole block and the output should
            // be the same, just slower.
            if (state == 0 && p+4 <= len &&
                (value = ((decode[input[p] & 0xff] << 18) |
                          (decode[input[p+1] & 0xff] << 12) |
                          (decode[input[p+2] & 0xff] << 6) |
                          (decode[input[p+3] & 0xff]))) >= 0) {
                output[op+2] = (byte) value;
                output[op+1] = (byte) (value >> 8);
                output[op] = (byte) (value >> 16);
                op += 3;
                p += 4;
                continue;
            }

            // The fast path isn't available -- either we've read a
            // partial tuple, or the next four input bytes aren't all
            // data, or whatever.  Fall back to the slower state
            // machine implementation.
            //
            // States 0-3 are reading through the next input tuple.
            // State 4 is having read one '=' and expecting exactly
            // one more.
            // State 5 is expecting no more data or padding characters
            // in the input.

            int d = decode[input[p++] & 0xff];

            switch (state) {
                case 0:
                    if (d >= 0) {
                        value = d;
                        ++state;
                    } else if (d != SKIP) {
                        throw new IllegalArgumentException("bad base-64");
                    }
                    break;

                case 1:
                    if (d >= 0) {
                        value = (value << 6) | d;
                        ++state;
                    } else if (d != SKIP) {
                        throw new IllegalArgumentException("bad base-64");
                    }
                    break;

                case 2:
                    if (d >= 0) {
                        value = (value << 6) | d;
                        ++state;
                    } else if (d == EQUALS) {
                        // Emit the last (partial) output tuple;
                        // expect exactly one more padding character.
                        output[op++] = (byte) (value >> 4);
                        state = 4;
                    } else if (d != SKIP) {
                        throw new IllegalArgumentException("bad base-64");
                    }
                    break;

                case 3:
                    if (d >= 0) {
                        // Emit the output triple and return to state 0.
                        value = (value << 6) | d;
                        output[op+2] = (byte) value;
                        output[op+1] = (byte) (value >> 8);
                        output[op] = (byte) (value >> 16);
                        op += 3;
                        state = 0;
                    } else if (d == EQUALS) {
                        // Emit the last (partial) output tuple;
                        // expect no further data or padding characters.
                        output[op+1] = (byte) (value >> 2);
                        output[op] = (byte) (value >> 10);
                        op += 2;
                        state = 5;
                    } else if (d != SKIP) {
                        throw new IllegalArgumentException("bad base-64");
                    }
                    break;

                case 4:
                    if (d == EQUALS) {
                        ++state;
                    } else if (d != SKIP) {
                        throw new IllegalArgumentException("bad base-64");
                    }
                    break;

                case 5:
                    if (d != SKIP) {
                        throw new IllegalArgumentException("bad base-64");
                    }
                    break;
            }
        }

        // Done reading input.  Now figure out where we are left in
        // the state machine and finish up.

        switch (state) {
            case 0:
                // Output length is a multiple of three.  Fine.
                break;
            case 1:
                // Read one extra input byte, which isn't enough to
                // make another output byte.  Illegal.
                throw new IllegalArgumentException("bad base-64");
            case 2:
                // Read two extra input bytes, enough to emit 1 more
                // output byte.  Fine.
                output[op++] = (byte) (value >> 4);
                break;
            case 3:
                // Read three extra input bytes, enough to emit 2 more
                // output bytes.  Fine.
                output[op+1] = (byte) (value >> 2);
                output[op] = (byte) (value >> 10);
                op += 2;
                break;
            case 4:
                // Read one padding '=' when we expected 2.  Illegal.
                throw new IllegalArgumentException("bad base-64");
            case 5:
                // Read all the padding '='s we expected and no more.
                // Fine.
                break;
        }

        // Maybe we got lucky and allocated exactly enough output space.
        if (op == output.length) {
            return output;
        }

        // Need to shorten the array, so allocate a new one of the
        // right size and copy.
        byte[] temp = new byte[op];
        System.arraycopy(output, 0, temp, 0, op);
        return temp;
    }

    /**
     * Emit a new line every this many output tuples.  Corresponds to
     * a 76-character line length (the maximum allowable according to
     * RFC 2045).
     */
    private static final int LINE_GROUPS = 19;

    /**
     * Lookup table for turning Base64 alphabet positions (6 bits)
     * into output bytes.
     */
    private static final byte ENCODE[] = {
        'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H',
        'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P',
        'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X',
        'Y', 'Z', 'a', 'b', 'c', 'd', 'e', 'f',
        'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n',
        'o', 'p', 'q', 'r', 's', 't', 'u', 'v',
        'w', 'x', 'y', 'z', '0', '1', '2', '3',
        '4', '5', '6', '7', '8', '9', '+', '/',
    };

    /**
     * Lookup table for turning Base64 alphabet positions (6 bits)
     * into output bytes.
     */
    private static final byte ENCODE_WEBSAFE[] = {
        'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H',
        'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P',
        'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X',
        'Y', 'Z', 'a', 'b', 'c', 'd', 'e', 'f',
        'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n',
        'o', 'p', 'q', 'r', 's', 't', 'u', 'v',
        'w', 'x', 'y', 'z', '0', '1', '2', '3',
        '4', '5', '6', '7', '8', '9', '-', '_',
    };

    /**
     * Base64-encode the given data and return a newly allocated
     * String with the result.
     *
     * @param input  the data to encode
     * @param flags  controls certain features of the encoded output.
     *               Passing {@code DEFAULT} results in output that
     *               adheres to RFC 2045.
     */
    public static String encodeToString(byte[] input, int flags) {
        return new String(encode(input, flags));
    }

    /**
     * Base64-encode the given data and return a newly allocated
     * String with the result.
     *
     * @param input  the data to encode
     * @param offset the position within the input array at which to
     *               start
     * @param len    the number of bytes of input to encode
     * @param flags  controls certain features of the encoded output.
     *               Passing {@code DEFAULT} results in output that
     *               adheres to RFC 2045.
     */
    public static String encodeToString(byte[] input, int offset, int len, int flags) {
        return new String(encode(input, offset, len, flags));
    }

    /**
     * Base64-encode the given data and return a newly allocated
     * byte[] with the result.
     *
     * @param input  the data to encode
     * @param flags  controls certain features of the encoded output.
     *               Passing {@code DEFAULT} results in output that
     *               adheres to RFC 2045.
     */
    public static byte[] encode(byte[] input, int flags) {
        return encode(input, 0, input.length, flags);
    }

    /**
     * Base64-encode the given data and return a newly allocated
     * byte[] with the result.
     *
     * @param input  the data to encode
     * @param offset the position within the input array at which to
     *               start
     * @param len    the number of bytes of input to encode
     * @param flags  controls certain features of the encoded output.
     *               Passing {@code DEFAULT} results in output that
     *               adheres to RFC 2045.
     */
    public static byte[] encode(byte[] input, int offset, int len, int flags) {
        final boolean do_padding = (flags & NO_PADDING) == 0;
        final boolean do_newline = (flags & NO_WRAP) == 0;
        final boolean do_cr = (flags & CRLF) != 0;

        final byte[] encode = ((flags & WEB_SAFE) == 0) ? ENCODE : ENCODE_WEBSAFE;

        // Compute the exact length of the array we will produce.
        int output_len = len / 3 * 4;

        // Account for the tail of the data and the padding bytes, if any.
        if (do_padding) {
            if (len % 3 > 0) {
                output_len += 4;
            }
        } else {
            switch (len % 3) {
                case 0: break;
                case 1: output_len += 2; break;
                case 2: output_len += 3; break;
            }
        }

        // Account for the newlines, if any.
        if (do_newline && len > 0) {
            output_len += (((len-1) / (3 * LINE_GROUPS)) + 1) * (do_cr ? 2 : 1);
        }

        int op = 0;
        byte[] output = new byte[output_len];

        // The main loop, turning 3 input bytes into 4 output bytes on
        // each iteration.
        int count = do_newline ? LINE_GROUPS : -1;
        int p = offset;
        len += offset;
        while (p+3 <= len) {
            int v = ((input[p++] & 0xff) << 16) |
                ((input[p++] & 0xff) << 8) |
                (input[p++] & 0xff);
            output[op++] = encode[(v >> 18) & 0x3f];
            output[op++] = encode[(v >> 12) & 0x3f];
            output[op++] = encode[(v >> 6) & 0x3f];
            output[op++] = encode[v & 0x3f];
            if (--count == 0) {
                if (do_cr) output[op++] = '\r';
                output[op++] = '\n';
                count = LINE_GROUPS;
            }
        }

        // Finish up the tail of the input.
        if (p == len-1) {
            int v = (input[p] & 0xff) << 4;
            output[op++] = encode[(v >> 6) & 0x3f];
            output[op++] = encode[v & 0x3f];
            if (do_padding) {
                output[op++] = '=';
                output[op++] = '=';
            }
            if (do_newline) {
                if (do_cr) output[op++] = '\r';
                output[op++] = '\n';
            }
        } else if (p == len-2) {
            int v = ((input[p] & 0xff) << 10) | ((input[p+1] & 0xff) << 2);
            output[op++] = encode[(v >> 12) & 0x3f];
            output[op++] = encode[(v >> 6) & 0x3f];
            output[op++] = encode[v & 0x3f];
            if (do_padding) {
                output[op++] = '=';
            }
            if (do_newline) {
                if (do_cr) output[op++] = '\r';
                output[op++] = '\n';
            }
        } else if (do_newline && op > 0 && count != LINE_GROUPS) {
            if (do_cr) output[op++] = '\r';
            output[op++] = '\n';
        }

        assert op == output.length;
        return output;
    }

    private Base64() { }   // don't instantiate
}
