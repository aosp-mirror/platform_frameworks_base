/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.core;

import junit.framework.TestCase;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * Checks basic InputStreamReader functionality.
 */
public class InputStreamReaderTest extends TestCase {

    /**
     * Checks if ASCII encoding works with InputStreamReader
     */
    @SmallTest
    public void testAscii() throws Exception {
        String str = "AbCdEfGhIjKlMnOpQrStUvWxYzX";
        ByteArrayInputStream aa = new ByteArrayInputStream(str.getBytes("ISO8859_1"));
        InputStreamReader a = new InputStreamReader(aa, "ISO8859_1");

        try {
            int x = a.read();
            assertEquals('A', x);
            char[] c = new char[26];
            x = a.read(c, 0, 26);
            assertTrue(a.getEncoding().equalsIgnoreCase("ISO8859_1"));
            assertEquals(26, x);
            assertEquals("bCdEfGhIjKlMnOpQrStUvWxYzX", String.valueOf(c));
        } finally {
            a.close();
        }
    }

    /**
     * Checks if Utf8 encoding works with InputStreamReader
     */
    @SmallTest
    public void testUtf8() throws Exception {
        String str = "AbCdEfGhIjKlMnOpQrStUvWxYzX" +
                "\u00a3\u00c5\u00c9";       // total of 30 characters
        ByteArrayInputStream aa =
                new ByteArrayInputStream(str.getBytes());

        InputStreamReader a = new InputStreamReader(aa);

        try {
            assertEquals("UTF8", a.getEncoding());

            int x = a.read();
            assertEquals('A', x);

            char[] c = new char[29];
            x = a.read(c, 0, 3);
            assertEquals(3, x);
            assertEquals("bCd", new String(c, 0, 3));

            x = a.read(c, 3, 26);
            assertEquals(26, x);
            assertEquals("EfGhIjKlMnOpQrStUvWxYzX\u00a3\u00c5\u00c9", new String(c, 3, 26));
        } finally {
            a.close();
        }
    }

    /**
     * Checks if several encodings works with InputStreamReader
     */
    @SmallTest
    public void testStringy() throws Exception {
        String src = "The quick brown fox\u00A0\u00FF" +
                "\uFFFC\uD7C5\uDC03bloof";

        String[] enc = new String[]{
                "utf-8", "us-ascii", "iso-8859-1", "utf-16be", "utf-16le",
                "utf-16",
        };

        for (int i = 0; i < enc.length; i++) {
            byte[] ba = src.getBytes(enc[i]);

            String s1 = new String(ba, enc[i]);

            ByteArrayInputStream bais = new ByteArrayInputStream(ba);
            InputStreamReader r = new InputStreamReader(bais, enc[i]);
            try {
                char[] ca = new char[600];
                int n = r.read(ca, 0, 600);

                String s2 = new String(ca, 0, n);
                assertEquals(s1, s2);
            } finally {
                r.close();
            }
        }
    }
}
