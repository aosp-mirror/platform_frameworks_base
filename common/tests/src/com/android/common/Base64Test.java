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

import junit.framework.TestCase;

import java.io.ByteArrayOutputStream;
import java.util.Random;

public class Base64Test extends TestCase {
    private static final String TAG = "B64Test";

    /** Decodes a string, returning a string. */
    private String decodeString(String in) throws Exception {
        byte[] out = Base64.decode(in, 0);
        return new String(out);
    }

    /**
     * Encodes the string 'in' using 'flags'.  Asserts that decoding
     * gives the same string.  Returns the encoded string.
     */
    private String encodeToString(String in, int flags) throws Exception {
        String b64 = Base64.encodeToString(in.getBytes(), flags);
        String dec = decodeString(b64);
        assertEquals(in, dec);
        return b64;
    }

    /** Assert that decoding 'in' throws IllegalArgumentException. */
    private void assertBad(String in) throws Exception {
        try {
            byte[] out = Base64.decode(in, 0);
            fail("should have failed to decode");
        } catch (IllegalArgumentException e) {
        }
    }

    /** Assert that actual equals the first len bytes of expected. */
    private void assertEquals(byte[] expected, int len, byte[] actual) {
        assertEquals(len, actual.length);
        for (int i = 0; i < len; ++i) {
            assertEquals(expected[i], actual[i]);
        }
    }

    /** Assert that actual equals the first len bytes of expected. */
    private void assertEquals(byte[] expected, int len, byte[] actual, int alen) {
        assertEquals(len, alen);
        for (int i = 0; i < len; ++i) {
            assertEquals(expected[i], actual[i]);
        }
    }

    /** Assert that actual equals the first len bytes of expected. */
    private void assertEquals(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; ++i) {
            assertEquals(expected[i], actual[i]);
        }
    }

    public void testDecodeExtraChars() throws Exception {
        // padding 0
        assertEquals("hello, world", decodeString("aGVsbG8sIHdvcmxk"));
        assertBad("aGVsbG8sIHdvcmxk=");
        assertBad("aGVsbG8sIHdvcmxk==");
        assertBad("aGVsbG8sIHdvcmxk =");
        assertBad("aGVsbG8sIHdvcmxk = = ");
        assertEquals("hello, world", decodeString(" aGVs bG8s IHdv cmxk  "));
        assertEquals("hello, world", decodeString(" aGV sbG8 sIHd vcmx k "));
        assertEquals("hello, world", decodeString(" aG VsbG 8sIH dvcm xk "));
        assertEquals("hello, world", decodeString(" a GVsb G8sI Hdvc mxk "));
        assertEquals("hello, world", decodeString(" a G V s b G 8 s I H d v c m x k "));
        assertEquals("hello, world", decodeString("_a*G_V*s_b*G_8*s_I*H_d*v_c*m_x*k_"));
        assertEquals("hello, world", decodeString("aGVsbG8sIHdvcmxk"));

        // padding 1
        assertEquals("hello, world?!", decodeString("aGVsbG8sIHdvcmxkPyE="));
        assertEquals("hello, world?!", decodeString("aGVsbG8sIHdvcmxkPyE"));
        assertBad("aGVsbG8sIHdvcmxkPyE==");
        assertBad("aGVsbG8sIHdvcmxkPyE ==");
        assertBad("aGVsbG8sIHdvcmxkPyE = = ");
        assertEquals("hello, world?!", decodeString("aGVsbG8sIHdvcmxkPy E="));
        assertEquals("hello, world?!", decodeString("aGVsbG8sIHdvcmxkPy E"));
        assertEquals("hello, world?!", decodeString("aGVsbG8sIHdvcmxkPy E ="));
        assertEquals("hello, world?!", decodeString("aGVsbG8sIHdvcmxkPy E "));
        assertEquals("hello, world?!", decodeString("aGVsbG8sIHdvcmxkPy E = "));
        assertEquals("hello, world?!", decodeString("aGVsbG8sIHdvcmxkPy E   "));

        // padding 2
        assertEquals("hello, world.", decodeString("aGVsbG8sIHdvcmxkLg=="));
        assertEquals("hello, world.", decodeString("aGVsbG8sIHdvcmxkLg"));
        assertBad("aGVsbG8sIHdvcmxkLg=");
        assertBad("aGVsbG8sIHdvcmxkLg =");
        assertBad("aGVsbG8sIHdvcmxkLg = ");
        assertEquals("hello, world.", decodeString("aGVsbG8sIHdvcmxkL g=="));
        assertEquals("hello, world.", decodeString("aGVsbG8sIHdvcmxkL g"));
        assertEquals("hello, world.", decodeString("aGVsbG8sIHdvcmxkL g =="));
        assertEquals("hello, world.", decodeString("aGVsbG8sIHdvcmxkL g "));
        assertEquals("hello, world.", decodeString("aGVsbG8sIHdvcmxkL g = = "));
        assertEquals("hello, world.", decodeString("aGVsbG8sIHdvcmxkL g   "));
    }

    private static final byte[] BYTES = { (byte) 0xff, (byte) 0xee, (byte) 0xdd,
                                          (byte) 0xcc, (byte) 0xbb, (byte) 0xaa,
                                          (byte) 0x99, (byte) 0x88, (byte) 0x77 };

    public void testBinaryDecode() throws Exception {
        assertEquals(BYTES, 0, Base64.decode("", 0));
        assertEquals(BYTES, 1, Base64.decode("/w==", 0));
        assertEquals(BYTES, 2, Base64.decode("/+4=", 0));
        assertEquals(BYTES, 3, Base64.decode("/+7d", 0));
        assertEquals(BYTES, 4, Base64.decode("/+7dzA==", 0));
        assertEquals(BYTES, 5, Base64.decode("/+7dzLs=", 0));
        assertEquals(BYTES, 6, Base64.decode("/+7dzLuq", 0));
        assertEquals(BYTES, 7, Base64.decode("/+7dzLuqmQ==", 0));
        assertEquals(BYTES, 8, Base64.decode("/+7dzLuqmYg=", 0));
    }

    public void testWebSafe() throws Exception {
        assertEquals(BYTES, 0, Base64.decode("", Base64.WEB_SAFE));
        assertEquals(BYTES, 1, Base64.decode("_w==", Base64.WEB_SAFE));
        assertEquals(BYTES, 2, Base64.decode("_-4=", Base64.WEB_SAFE));
        assertEquals(BYTES, 3, Base64.decode("_-7d", Base64.WEB_SAFE));
        assertEquals(BYTES, 4, Base64.decode("_-7dzA==", Base64.WEB_SAFE));
        assertEquals(BYTES, 5, Base64.decode("_-7dzLs=", Base64.WEB_SAFE));
        assertEquals(BYTES, 6, Base64.decode("_-7dzLuq", Base64.WEB_SAFE));
        assertEquals(BYTES, 7, Base64.decode("_-7dzLuqmQ==", Base64.WEB_SAFE));
        assertEquals(BYTES, 8, Base64.decode("_-7dzLuqmYg=", Base64.WEB_SAFE));

        assertEquals("", Base64.encodeToString(BYTES, 0, 0, Base64.WEB_SAFE));
        assertEquals("_w==\n", Base64.encodeToString(BYTES, 0, 1, Base64.WEB_SAFE));
        assertEquals("_-4=\n", Base64.encodeToString(BYTES, 0, 2, Base64.WEB_SAFE));
        assertEquals("_-7d\n", Base64.encodeToString(BYTES, 0, 3, Base64.WEB_SAFE));
        assertEquals("_-7dzA==\n", Base64.encodeToString(BYTES, 0, 4, Base64.WEB_SAFE));
        assertEquals("_-7dzLs=\n", Base64.encodeToString(BYTES, 0, 5, Base64.WEB_SAFE));
        assertEquals("_-7dzLuq\n", Base64.encodeToString(BYTES, 0, 6, Base64.WEB_SAFE));
        assertEquals("_-7dzLuqmQ==\n", Base64.encodeToString(BYTES, 0, 7, Base64.WEB_SAFE));
        assertEquals("_-7dzLuqmYg=\n", Base64.encodeToString(BYTES, 0, 8, Base64.WEB_SAFE));
    }

    public void testFlags() throws Exception {
        assertEquals("YQ==\n",       encodeToString("a", 0));
        assertEquals("YQ==",         encodeToString("a", Base64.NO_WRAP));
        assertEquals("YQ\n",         encodeToString("a", Base64.NO_PADDING));
        assertEquals("YQ",           encodeToString("a", Base64.NO_PADDING | Base64.NO_WRAP));
        assertEquals("YQ==\r\n",     encodeToString("a", Base64.CRLF));
        assertEquals("YQ\r\n",       encodeToString("a", Base64.CRLF | Base64.NO_PADDING));

        assertEquals("YWI=\n",       encodeToString("ab", 0));
        assertEquals("YWI=",         encodeToString("ab", Base64.NO_WRAP));
        assertEquals("YWI\n",        encodeToString("ab", Base64.NO_PADDING));
        assertEquals("YWI",          encodeToString("ab", Base64.NO_PADDING | Base64.NO_WRAP));
        assertEquals("YWI=\r\n",     encodeToString("ab", Base64.CRLF));
        assertEquals("YWI\r\n",      encodeToString("ab", Base64.CRLF | Base64.NO_PADDING));

        assertEquals("YWJj\n",       encodeToString("abc", 0));
        assertEquals("YWJj",         encodeToString("abc", Base64.NO_WRAP));
        assertEquals("YWJj\n",       encodeToString("abc", Base64.NO_PADDING));
        assertEquals("YWJj",         encodeToString("abc", Base64.NO_PADDING | Base64.NO_WRAP));
        assertEquals("YWJj\r\n",     encodeToString("abc", Base64.CRLF));
        assertEquals("YWJj\r\n",     encodeToString("abc", Base64.CRLF | Base64.NO_PADDING));

        assertEquals("YWJjZA==\n",   encodeToString("abcd", 0));
        assertEquals("YWJjZA==",     encodeToString("abcd", Base64.NO_WRAP));
        assertEquals("YWJjZA\n",     encodeToString("abcd", Base64.NO_PADDING));
        assertEquals("YWJjZA",       encodeToString("abcd", Base64.NO_PADDING | Base64.NO_WRAP));
        assertEquals("YWJjZA==\r\n", encodeToString("abcd", Base64.CRLF));
        assertEquals("YWJjZA\r\n",   encodeToString("abcd", Base64.CRLF | Base64.NO_PADDING));
    }

    public void testLineLength() throws Exception {
        String in_56 = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcd";
        String in_57 = in_56 + "e";
        String in_58 = in_56 + "ef";
        String in_59 = in_56 + "efg";
        String in_60 = in_56 + "efgh";
        String in_61 = in_56 + "efghi";

        String prefix = "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXphYmNkZWZnaGlqa2xtbm9wcXJzdHV2d3h5emFi";
        String out_56 = prefix + "Y2Q=\n";
        String out_57 = prefix + "Y2Rl\n";
        String out_58 = prefix + "Y2Rl\nZg==\n";
        String out_59 = prefix + "Y2Rl\nZmc=\n";
        String out_60 = prefix + "Y2Rl\nZmdo\n";
        String out_61 = prefix + "Y2Rl\nZmdoaQ==\n";

        // no newline for an empty input array.
        assertEquals("", encodeToString("", 0));

        assertEquals(out_56, encodeToString(in_56, 0));
        assertEquals(out_57, encodeToString(in_57, 0));
        assertEquals(out_58, encodeToString(in_58, 0));
        assertEquals(out_59, encodeToString(in_59, 0));
        assertEquals(out_60, encodeToString(in_60, 0));
        assertEquals(out_61, encodeToString(in_61, 0));

        assertEquals(out_56.replaceAll("=", ""), encodeToString(in_56, Base64.NO_PADDING));
        assertEquals(out_57.replaceAll("=", ""), encodeToString(in_57, Base64.NO_PADDING));
        assertEquals(out_58.replaceAll("=", ""), encodeToString(in_58, Base64.NO_PADDING));
        assertEquals(out_59.replaceAll("=", ""), encodeToString(in_59, Base64.NO_PADDING));
        assertEquals(out_60.replaceAll("=", ""), encodeToString(in_60, Base64.NO_PADDING));
        assertEquals(out_61.replaceAll("=", ""), encodeToString(in_61, Base64.NO_PADDING));

        assertEquals(out_56.replaceAll("\n", ""), encodeToString(in_56, Base64.NO_WRAP));
        assertEquals(out_57.replaceAll("\n", ""), encodeToString(in_57, Base64.NO_WRAP));
        assertEquals(out_58.replaceAll("\n", ""), encodeToString(in_58, Base64.NO_WRAP));
        assertEquals(out_59.replaceAll("\n", ""), encodeToString(in_59, Base64.NO_WRAP));
        assertEquals(out_60.replaceAll("\n", ""), encodeToString(in_60, Base64.NO_WRAP));
        assertEquals(out_61.replaceAll("\n", ""), encodeToString(in_61, Base64.NO_WRAP));
    }

    /**
     * Tests that Base64.encodeInternal does correct handling of the
     * tail for each call.
     */
    public void testEncodeInternal() throws Exception {
        byte[] input = { (byte) 0x61, (byte) 0x62, (byte) 0x63 };
        byte[] output = new byte[100];

        Base64.EncoderState state = new Base64.EncoderState(Base64.NO_PADDING | Base64.NO_WRAP,
                                                            output);

        Base64.encodeInternal(input, 0, 3, state, false);
        assertEquals("YWJj".getBytes(), 4, state.output, state.op);
        assertEquals(0, state.tailLen);

        Base64.encodeInternal(input, 0, 3, state, false);
        assertEquals("YWJj".getBytes(), 4, state.output, state.op);
        assertEquals(0, state.tailLen);

        Base64.encodeInternal(input, 0, 1, state, false);
        assertEquals(0, state.op);
        assertEquals(1, state.tailLen);

        Base64.encodeInternal(input, 0, 1, state, false);
        assertEquals(0, state.op);
        assertEquals(2, state.tailLen);

        Base64.encodeInternal(input, 0, 1, state, false);
        assertEquals("YWFh".getBytes(), 4, state.output, state.op);
        assertEquals(0, state.tailLen);

        Base64.encodeInternal(input, 0, 2, state, false);
        assertEquals(0, state.op);
        assertEquals(2, state.tailLen);

        Base64.encodeInternal(input, 0, 2, state, false);
        assertEquals("YWJh".getBytes(), 4, state.output, state.op);
        assertEquals(1, state.tailLen);

        Base64.encodeInternal(input, 0, 2, state, false);
        assertEquals("YmFi".getBytes(), 4, state.output, state.op);
        assertEquals(0, state.tailLen);

        Base64.encodeInternal(input, 0, 1, state, true);
        assertEquals("YQ".getBytes(), 2, state.output, state.op);
    }

    /**
     * Tests that Base64OutputStream produces exactly the same results
     * as calling Base64.encode/.decode on an in-memory array.
     */
    public void testOutputStream() throws Exception {
        int[] flagses = { Base64.DEFAULT,
                          Base64.NO_PADDING,
                          Base64.NO_WRAP,
                          Base64.NO_PADDING | Base64.NO_WRAP,
                          Base64.CRLF,
                          Base64.WEB_SAFE };
        int[] writeLengths = { -10, -5, -1, 0, 1, 1, 2, 2, 3, 10, 100 };
        Random rng = new Random(32176L);

        // input needs to be at least 1024 bytes to test filling up
        // the write(int) buffer.
        byte[] input = ("Lorem ipsum dolor sit amet, consectetur adipiscing elit. " +
                        "Quisque congue eleifend odio, eu ornare nulla facilisis eget. " +
                        "Integer eget elit diam, sit amet laoreet nibh. Quisque enim " +
                        "urna, pharetra vitae consequat eget, adipiscing eu ante. " +
                        "Aliquam venenatis arcu nec nibh imperdiet tempor. In id dui " +
                        "eget lorem aliquam rutrum vel vitae eros. In placerat ornare " +
                        "pretium. Curabitur non fringilla mi. Fusce ultricies, turpis " +
                        "eu ultrices suscipit, ligula nisi consectetur eros, dapibus " +
                        "aliquet dui sapien a turpis. Donec ultricies varius ligula, " +
                        "ut hendrerit arcu malesuada at. Praesent sed elit pretium " +
                        "eros luctus gravida. In ac dolor lorem. Cras condimentum " +
                        "convallis elementum. Phasellus vel felis in nulla ultrices " +
                        "venenatis. Nam non tortor non orci convallis convallis. " +
                        "Nam tristique lacinia hendrerit. Pellentesque habitant morbi " +
                        "tristique senectus et netus et malesuada fames ac turpis " +
                        "egestas. Vivamus cursus, nibh eu imperdiet porta, magna " +
                        "ipsum mollis mauris, sit amet fringilla mi nisl eu mi. " +
                        "Phasellus posuere, leo at ultricies vehicula, massa risus " +
                        "volutpat sapien, eu tincidunt diam ipsum eget nulla. Cras " +
                        "molestie dapibus commodo. Ut vel tellus at massa gravida " +
                        "semper non sed orci.").getBytes();

        for (int f = 0; f < flagses.length; ++f) {
            int flags = flagses[f];

            byte[] expected = Base64.encode(input, flags);

            ByteArrayOutputStream baos;
            Base64OutputStream b64os;
            byte[] actual;
            int p;

            // ----- test encoding ("input" -> "expected") -----

            // one large write(byte[]) of the whole input
            baos = new ByteArrayOutputStream();
            b64os = new Base64OutputStream(baos, flags);
            b64os.write(input);
            b64os.close();
            actual = baos.toByteArray();
            assertEquals(expected, actual);

            // many calls to write(int)
            baos = new ByteArrayOutputStream();
            b64os = new Base64OutputStream(baos, flags);
            for (int i = 0; i < input.length; ++i) {
                b64os.write(input[i]);
            }
            b64os.close();
            actual = baos.toByteArray();
            assertEquals(expected, actual);

            // intermixed sequences of write(int) with
            // write(byte[],int,int) of various lengths.
            baos = new ByteArrayOutputStream();
            b64os = new Base64OutputStream(baos, flags);
            p = 0;
            while (p < input.length) {
                int l = writeLengths[rng.nextInt(writeLengths.length)];
                l = Math.min(l, input.length-p);
                if (l >= 0) {
                    b64os.write(input, p, l);
                    p += l;
                } else {
                    l = Math.min(-l, input.length-p);
                    for (int i = 0; i < l; ++i) {
                        b64os.write(input[p+i]);
                    }
                    p += l;
                }
            }
            b64os.close();
            actual = baos.toByteArray();
            assertEquals(expected, actual);

            // ----- test decoding ("expected" -> "input") -----

            // one large write(byte[]) of the whole input
            baos = new ByteArrayOutputStream();
            b64os = new Base64OutputStream(baos, flags, false);
            b64os.write(expected);
            b64os.close();
            actual = baos.toByteArray();
            assertEquals(input, actual);

            // many calls to write(int)
            baos = new ByteArrayOutputStream();
            b64os = new Base64OutputStream(baos, flags, false);
            for (int i = 0; i < expected.length; ++i) {
                b64os.write(expected[i]);
            }
            b64os.close();
            actual = baos.toByteArray();
            assertEquals(input, actual);

            // intermixed sequences of write(int) with
            // write(byte[],int,int) of various lengths.
            baos = new ByteArrayOutputStream();
            b64os = new Base64OutputStream(baos, flags, false);
            p = 0;
            while (p < expected.length) {
                int l = writeLengths[rng.nextInt(writeLengths.length)];
                l = Math.min(l, expected.length-p);
                if (l >= 0) {
                    b64os.write(expected, p, l);
                    p += l;
                } else {
                    l = Math.min(-l, expected.length-p);
                    for (int i = 0; i < l; ++i) {
                        b64os.write(expected[p+i]);
                    }
                    p += l;
                }
            }
            b64os.close();
            actual = baos.toByteArray();
            assertEquals(input, actual);
        }
    }
}
