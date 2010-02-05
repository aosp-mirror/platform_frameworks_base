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
    private String encodeString(String in, int flags) throws Exception {
        String b64 = Base64.encodeString(in.getBytes(), flags);
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

        assertEquals("", Base64.encodeString(BYTES, 0, 0, Base64.WEB_SAFE));
        assertEquals("_w==\n", Base64.encodeString(BYTES, 0, 1, Base64.WEB_SAFE));
        assertEquals("_-4=\n", Base64.encodeString(BYTES, 0, 2, Base64.WEB_SAFE));
        assertEquals("_-7d\n", Base64.encodeString(BYTES, 0, 3, Base64.WEB_SAFE));
        assertEquals("_-7dzA==\n", Base64.encodeString(BYTES, 0, 4, Base64.WEB_SAFE));
        assertEquals("_-7dzLs=\n", Base64.encodeString(BYTES, 0, 5, Base64.WEB_SAFE));
        assertEquals("_-7dzLuq\n", Base64.encodeString(BYTES, 0, 6, Base64.WEB_SAFE));
        assertEquals("_-7dzLuqmQ==\n", Base64.encodeString(BYTES, 0, 7, Base64.WEB_SAFE));
        assertEquals("_-7dzLuqmYg=\n", Base64.encodeString(BYTES, 0, 8, Base64.WEB_SAFE));
    }

    public void testFlags() throws Exception {
        assertEquals("YQ==\n",       encodeString("a", 0));
        assertEquals("YQ==",         encodeString("a", Base64.NO_WRAP));
        assertEquals("YQ\n",         encodeString("a", Base64.NO_PADDING));
        assertEquals("YQ",           encodeString("a", Base64.NO_PADDING | Base64.NO_WRAP));
        assertEquals("YQ==\r\n",     encodeString("a", Base64.CRLF));
        assertEquals("YQ\r\n",       encodeString("a", Base64.CRLF | Base64.NO_PADDING));

        assertEquals("YWI=\n",       encodeString("ab", 0));
        assertEquals("YWI=",         encodeString("ab", Base64.NO_WRAP));
        assertEquals("YWI\n",        encodeString("ab", Base64.NO_PADDING));
        assertEquals("YWI",          encodeString("ab", Base64.NO_PADDING | Base64.NO_WRAP));
        assertEquals("YWI=\r\n",     encodeString("ab", Base64.CRLF));
        assertEquals("YWI\r\n",      encodeString("ab", Base64.CRLF | Base64.NO_PADDING));

        assertEquals("YWJj\n",       encodeString("abc", 0));
        assertEquals("YWJj",         encodeString("abc", Base64.NO_WRAP));
        assertEquals("YWJj\n",       encodeString("abc", Base64.NO_PADDING));
        assertEquals("YWJj",         encodeString("abc", Base64.NO_PADDING | Base64.NO_WRAP));
        assertEquals("YWJj\r\n",     encodeString("abc", Base64.CRLF));
        assertEquals("YWJj\r\n",     encodeString("abc", Base64.CRLF | Base64.NO_PADDING));

        assertEquals("YWJjZA==\n",   encodeString("abcd", 0));
        assertEquals("YWJjZA==",     encodeString("abcd", Base64.NO_WRAP));
        assertEquals("YWJjZA\n",     encodeString("abcd", Base64.NO_PADDING));
        assertEquals("YWJjZA",       encodeString("abcd", Base64.NO_PADDING | Base64.NO_WRAP));
        assertEquals("YWJjZA==\r\n", encodeString("abcd", Base64.CRLF));
        assertEquals("YWJjZA\r\n",   encodeString("abcd", Base64.CRLF | Base64.NO_PADDING));
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
        assertEquals("", encodeString("", 0));

        assertEquals(out_56, encodeString(in_56, 0));
        assertEquals(out_57, encodeString(in_57, 0));
        assertEquals(out_58, encodeString(in_58, 0));
        assertEquals(out_59, encodeString(in_59, 0));
        assertEquals(out_60, encodeString(in_60, 0));
        assertEquals(out_61, encodeString(in_61, 0));

        assertEquals(out_56.replaceAll("=", ""), encodeString(in_56, Base64.NO_PADDING));
        assertEquals(out_57.replaceAll("=", ""), encodeString(in_57, Base64.NO_PADDING));
        assertEquals(out_58.replaceAll("=", ""), encodeString(in_58, Base64.NO_PADDING));
        assertEquals(out_59.replaceAll("=", ""), encodeString(in_59, Base64.NO_PADDING));
        assertEquals(out_60.replaceAll("=", ""), encodeString(in_60, Base64.NO_PADDING));
        assertEquals(out_61.replaceAll("=", ""), encodeString(in_61, Base64.NO_PADDING));

        assertEquals(out_56.replaceAll("\n", ""), encodeString(in_56, Base64.NO_WRAP));
        assertEquals(out_57.replaceAll("\n", ""), encodeString(in_57, Base64.NO_WRAP));
        assertEquals(out_58.replaceAll("\n", ""), encodeString(in_58, Base64.NO_WRAP));
        assertEquals(out_59.replaceAll("\n", ""), encodeString(in_59, Base64.NO_WRAP));
        assertEquals(out_60.replaceAll("\n", ""), encodeString(in_60, Base64.NO_WRAP));
        assertEquals(out_61.replaceAll("\n", ""), encodeString(in_61, Base64.NO_WRAP));
    }
}
