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

package android.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class Base64Test {
    private static final String TAG = "Base64Test";

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
    private void assertPartialEquals(byte[] expected, int len, byte[] actual) {
        assertEquals(len, actual.length);
        for (int i = 0; i < len; ++i) {
            assertEquals(expected[i], actual[i]);
        }
    }

    /** Assert that actual equals the first len bytes of expected. */
    private void assertPartialEquals(byte[] expected, int len, byte[] actual, int alen) {
        assertEquals(len, alen);
        for (int i = 0; i < len; ++i) {
            assertEquals(expected[i], actual[i]);
        }
    }

    @Test
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

    @Test
    public void testBinaryDecode() throws Exception {
        assertPartialEquals(BYTES, 0, Base64.decode("", 0));
        assertPartialEquals(BYTES, 1, Base64.decode("/w==", 0));
        assertPartialEquals(BYTES, 2, Base64.decode("/+4=", 0));
        assertPartialEquals(BYTES, 3, Base64.decode("/+7d", 0));
        assertPartialEquals(BYTES, 4, Base64.decode("/+7dzA==", 0));
        assertPartialEquals(BYTES, 5, Base64.decode("/+7dzLs=", 0));
        assertPartialEquals(BYTES, 6, Base64.decode("/+7dzLuq", 0));
        assertPartialEquals(BYTES, 7, Base64.decode("/+7dzLuqmQ==", 0));
        assertPartialEquals(BYTES, 8, Base64.decode("/+7dzLuqmYg=", 0));
    }

    @Test
    public void testWebSafe() throws Exception {
        assertPartialEquals(BYTES, 0, Base64.decode("", Base64.URL_SAFE));
        assertPartialEquals(BYTES, 1, Base64.decode("_w==", Base64.URL_SAFE));
        assertPartialEquals(BYTES, 2, Base64.decode("_-4=", Base64.URL_SAFE));
        assertPartialEquals(BYTES, 3, Base64.decode("_-7d", Base64.URL_SAFE));
        assertPartialEquals(BYTES, 4, Base64.decode("_-7dzA==", Base64.URL_SAFE));
        assertPartialEquals(BYTES, 5, Base64.decode("_-7dzLs=", Base64.URL_SAFE));
        assertPartialEquals(BYTES, 6, Base64.decode("_-7dzLuq", Base64.URL_SAFE));
        assertPartialEquals(BYTES, 7, Base64.decode("_-7dzLuqmQ==", Base64.URL_SAFE));
        assertPartialEquals(BYTES, 8, Base64.decode("_-7dzLuqmYg=", Base64.URL_SAFE));

        assertEquals("", Base64.encodeToString(BYTES, 0, 0, Base64.URL_SAFE));
        assertEquals("_w==\n", Base64.encodeToString(BYTES, 0, 1, Base64.URL_SAFE));
        assertEquals("_-4=\n", Base64.encodeToString(BYTES, 0, 2, Base64.URL_SAFE));
        assertEquals("_-7d\n", Base64.encodeToString(BYTES, 0, 3, Base64.URL_SAFE));
        assertEquals("_-7dzA==\n", Base64.encodeToString(BYTES, 0, 4, Base64.URL_SAFE));
        assertEquals("_-7dzLs=\n", Base64.encodeToString(BYTES, 0, 5, Base64.URL_SAFE));
        assertEquals("_-7dzLuq\n", Base64.encodeToString(BYTES, 0, 6, Base64.URL_SAFE));
        assertEquals("_-7dzLuqmQ==\n", Base64.encodeToString(BYTES, 0, 7, Base64.URL_SAFE));
        assertEquals("_-7dzLuqmYg=\n", Base64.encodeToString(BYTES, 0, 8, Base64.URL_SAFE));
    }

    @Test
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

    @Test
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
     * Tests that Base64.Encoder.encode() does correct handling of the
     * tail for each call.
     *
     * This test is disabled because while it passes if you can get it
     * to run, android's test infrastructure currently doesn't allow
     * us to get at package-private members (Base64.Encoder in
     * this case).
     */
    @Test
    @Ignore
    public void testEncodeInternal() throws Exception {
        byte[] input = { (byte) 0x61, (byte) 0x62, (byte) 0x63 };
        byte[] output = new byte[100];

        Base64.Encoder encoder = new Base64.Encoder(Base64.NO_PADDING | Base64.NO_WRAP,
                                                    output);

        encoder.process(input, 0, 3, false);
        assertPartialEquals("YWJj".getBytes(), 4, encoder.output, encoder.op);
        assertEquals(0, encoder.tailLen);

        encoder.process(input, 0, 3, false);
        assertPartialEquals("YWJj".getBytes(), 4, encoder.output, encoder.op);
        assertEquals(0, encoder.tailLen);

        encoder.process(input, 0, 1, false);
        assertEquals(0, encoder.op);
        assertEquals(1, encoder.tailLen);

        encoder.process(input, 0, 1, false);
        assertEquals(0, encoder.op);
        assertEquals(2, encoder.tailLen);

        encoder.process(input, 0, 1, false);
        assertPartialEquals("YWFh".getBytes(), 4, encoder.output, encoder.op);
        assertEquals(0, encoder.tailLen);

        encoder.process(input, 0, 2, false);
        assertEquals(0, encoder.op);
        assertEquals(2, encoder.tailLen);

        encoder.process(input, 0, 2, false);
        assertPartialEquals("YWJh".getBytes(), 4, encoder.output, encoder.op);
        assertEquals(1, encoder.tailLen);

        encoder.process(input, 0, 2, false);
        assertPartialEquals("YmFi".getBytes(), 4, encoder.output, encoder.op);
        assertEquals(0, encoder.tailLen);

        encoder.process(input, 0, 1, true);
        assertPartialEquals("YQ".getBytes(), 2, encoder.output, encoder.op);
    }

    private static final String lipsum =
            "Lorem ipsum dolor sit amet, consectetur adipiscing elit. " +
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
            "semper non sed orci.";

    @Test
    public void testInputStream() throws Exception {
        int[] flagses = { Base64.DEFAULT,
                          Base64.NO_PADDING,
                          Base64.NO_WRAP,
                          Base64.NO_PADDING | Base64.NO_WRAP,
                          Base64.CRLF,
                          Base64.URL_SAFE };
        int[] writeLengths = { -10, -5, -1, 0, 1, 1, 2, 2, 3, 10, 100 };
        Random rng = new Random(32176L);

        // Test input needs to be at least 2048 bytes to fill up the
        // read buffer of Base64InputStream.
        byte[] plain = (lipsum + lipsum + lipsum + lipsum + lipsum).getBytes();

        for (int flags: flagses) {
            byte[] encoded = Base64.encode(plain, flags);

            ByteArrayInputStream bais;
            Base64InputStream b64is;
            byte[] actual = new byte[plain.length * 2];
            int ap;
            int b;

            // ----- test decoding ("encoded" -> "plain") -----

            // read as much as it will give us in one chunk
            bais = new ByteArrayInputStream(encoded);
            b64is = new Base64InputStream(bais, flags);
            ap = 0;
            while ((b = b64is.read(actual, ap, actual.length-ap)) != -1) {
                ap += b;
            }
            assertPartialEquals(actual, ap, plain);

            // read individual bytes
            bais = new ByteArrayInputStream(encoded);
            b64is = new Base64InputStream(bais, flags);
            ap = 0;
            while ((b = b64is.read()) != -1) {
                actual[ap++] = (byte) b;
            }
            assertPartialEquals(actual, ap, plain);

            // mix reads of variously-sized arrays with one-byte reads
            bais = new ByteArrayInputStream(encoded);
            b64is = new Base64InputStream(bais, flags);
            ap = 0;
            readloop: while (true) {
                int l = writeLengths[rng.nextInt(writeLengths.length)];
                if (l >= 0) {
                    b = b64is.read(actual, ap, l);
                    if (b == -1) break readloop;
                    ap += b;
                } else {
                    for (int i = 0; i < -l; ++i) {
                        if ((b = b64is.read()) == -1) break readloop;
                        actual[ap++] = (byte) b;
                    }
                }
            }
            assertPartialEquals(actual, ap, plain);

            // ----- test encoding ("plain" -> "encoded") -----

            // read as much as it will give us in one chunk
            bais = new ByteArrayInputStream(plain);
            b64is = new Base64InputStream(bais, flags, true);
            ap = 0;
            while ((b = b64is.read(actual, ap, actual.length-ap)) != -1) {
                ap += b;
            }
            assertPartialEquals(actual, ap, encoded);

            // read individual bytes
            bais = new ByteArrayInputStream(plain);
            b64is = new Base64InputStream(bais, flags, true);
            ap = 0;
            while ((b = b64is.read()) != -1) {
                actual[ap++] = (byte) b;
            }
            assertPartialEquals(actual, ap, encoded);

            // mix reads of variously-sized arrays with one-byte reads
            bais = new ByteArrayInputStream(plain);
            b64is = new Base64InputStream(bais, flags, true);
            ap = 0;
            readloop: while (true) {
                int l = writeLengths[rng.nextInt(writeLengths.length)];
                if (l >= 0) {
                    b = b64is.read(actual, ap, l);
                    if (b == -1) break readloop;
                    ap += b;
                } else {
                    for (int i = 0; i < -l; ++i) {
                        if ((b = b64is.read()) == -1) break readloop;
                        actual[ap++] = (byte) b;
                    }
                }
            }
            assertPartialEquals(actual, ap, encoded);
        }
    }

    /** http://b/3026478 */
    @Test
    public void testSingleByteReads() throws IOException {
        InputStream in = new Base64InputStream(
                new ByteArrayInputStream("/v8=".getBytes()), Base64.DEFAULT);
        assertEquals(254, in.read());
        assertEquals(255, in.read());
    }

    /**
     * Tests that Base64OutputStream produces exactly the same results
     * as calling Base64.encode/.decode on an in-memory array.
     */
    @Test
    public void testOutputStream() throws Exception {
        int[] flagses = { Base64.DEFAULT,
                          Base64.NO_PADDING,
                          Base64.NO_WRAP,
                          Base64.NO_PADDING | Base64.NO_WRAP,
                          Base64.CRLF,
                          Base64.URL_SAFE };
        int[] writeLengths = { -10, -5, -1, 0, 1, 1, 2, 2, 3, 10, 100 };
        Random rng = new Random(32176L);

        // Test input needs to be at least 1024 bytes to test filling
        // up the write(int) buffer of Base64OutputStream.
        byte[] plain = (lipsum + lipsum).getBytes();

        for (int flags: flagses) {
            byte[] encoded = Base64.encode(plain, flags);

            ByteArrayOutputStream baos;
            Base64OutputStream b64os;
            byte[] actual;
            int p;

            // ----- test encoding ("plain" -> "encoded") -----

            // one large write(byte[]) of the whole input
            baos = new ByteArrayOutputStream();
            b64os = new Base64OutputStream(baos, flags);
            b64os.write(plain);
            b64os.close();
            actual = baos.toByteArray();
            assertArrayEquals(encoded, actual);

            // many calls to write(int)
            baos = new ByteArrayOutputStream();
            b64os = new Base64OutputStream(baos, flags);
            for (int i = 0; i < plain.length; ++i) {
                b64os.write(plain[i]);
            }
            b64os.close();
            actual = baos.toByteArray();
            assertArrayEquals(encoded, actual);

            // intermixed sequences of write(int) with
            // write(byte[],int,int) of various lengths.
            baos = new ByteArrayOutputStream();
            b64os = new Base64OutputStream(baos, flags);
            p = 0;
            while (p < plain.length) {
                int l = writeLengths[rng.nextInt(writeLengths.length)];
                l = Math.min(l, plain.length-p);
                if (l >= 0) {
                    b64os.write(plain, p, l);
                    p += l;
                } else {
                    l = Math.min(-l, plain.length-p);
                    for (int i = 0; i < l; ++i) {
                        b64os.write(plain[p+i]);
                    }
                    p += l;
                }
            }
            b64os.close();
            actual = baos.toByteArray();
            assertArrayEquals(encoded, actual);

            // ----- test decoding ("encoded" -> "plain") -----

            // one large write(byte[]) of the whole input
            baos = new ByteArrayOutputStream();
            b64os = new Base64OutputStream(baos, flags, false);
            b64os.write(encoded);
            b64os.close();
            actual = baos.toByteArray();
            assertArrayEquals(plain, actual);

            // many calls to write(int)
            baos = new ByteArrayOutputStream();
            b64os = new Base64OutputStream(baos, flags, false);
            for (int i = 0; i < encoded.length; ++i) {
                b64os.write(encoded[i]);
            }
            b64os.close();
            actual = baos.toByteArray();
            assertArrayEquals(plain, actual);

            // intermixed sequences of write(int) with
            // write(byte[],int,int) of various lengths.
            baos = new ByteArrayOutputStream();
            b64os = new Base64OutputStream(baos, flags, false);
            p = 0;
            while (p < encoded.length) {
                int l = writeLengths[rng.nextInt(writeLengths.length)];
                l = Math.min(l, encoded.length-p);
                if (l >= 0) {
                    b64os.write(encoded, p, l);
                    p += l;
                } else {
                    l = Math.min(-l, encoded.length-p);
                    for (int i = 0; i < l; ++i) {
                        b64os.write(encoded[p+i]);
                    }
                    p += l;
                }
            }
            b64os.close();
            actual = baos.toByteArray();
            assertArrayEquals(plain, actual);
        }
    }

    @Test
    public void testOutputStream_ioExceptionDuringClose() {
        OutputStream out = new OutputStream() {
            @Override public void write(int b) throws IOException { }
            @Override public void close() throws IOException {
                throw new IOException("close()");
            }
        };
        OutputStream out2 = new Base64OutputStream(out, Base64.DEFAULT);
        try {
            out2.close();
            fail();
        } catch (IOException expected) {
        }
    }

    @Test
    public void testOutputStream_ioExceptionDuringCloseAndWrite() {
        OutputStream out = new OutputStream() {
            @Override public void write(int b) throws IOException {
                throw new IOException("write()");
            }
            @Override public void write(byte[] b) throws IOException {
                throw new IOException("write()");
            }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("write()");
            }
            @Override public void close() throws IOException {
                throw new IOException("close()");
            }
        };
        OutputStream out2 = new Base64OutputStream(out, Base64.DEFAULT);
        try {
            out2.close();
            fail();
        } catch (IOException expected) {
            // Base64OutputStream write()s pending (possibly empty) data
            // before close(), so the IOE from write() should be thrown and
            // any later exception suppressed.
            assertEquals("write()", expected.getMessage());
            Throwable[] suppressed = expected.getSuppressed();
            List<String> suppressedMessages = Arrays.asList(suppressed).stream()
                    .map((e) -> e.getMessage())
                    .collect(Collectors.toList());
            assertEquals(Collections.singletonList("close()"), suppressedMessages);
        }
    }

    @Test
    public void testOutputStream_ioExceptionDuringWrite() {
        OutputStream out = new OutputStream() {
            @Override public void write(int b) throws IOException {
                throw new IOException("write()");
            }
            @Override public void write(byte[] b) throws IOException {
                throw new IOException("write()");
            }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("write()");
            }
        };
        // Base64OutputStream write()s pending (possibly empty) data
        // before close(), so the IOE from write() should be thrown.
        OutputStream out2 = new Base64OutputStream(out, Base64.DEFAULT);
        try {
            out2.close();
            fail();
        } catch (IOException expected) {
        }
    }

}
