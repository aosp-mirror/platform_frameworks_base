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

import java.io.LineNumberReader;
import java.io.StringReader;
import android.test.suitebuilder.annotation.MediumTest;

/**
 * Checks basic functionality for LineNumberReader.
 */
public class LineNumberReaderTest extends TestCase {

    @MediumTest
    public void testLineNumberReader() throws Exception {
        String str = "AbCdEfGhIjKlM\nOpQrStUvWxYz";

        StringReader aa = new StringReader(str);
        StringReader ba = new StringReader(str);
        StringReader ca = new StringReader(str);
        StringReader da = new StringReader(str);
        StringReader ea = new StringReader(str);

        LineNumberReader a = new LineNumberReader(aa);
        try {
            assertEquals(0, a.getLineNumber());
            assertEquals(str, IOUtil.read(a));
            assertEquals(1, a.getLineNumber());
            a.setLineNumber(5);
            assertEquals(5, a.getLineNumber());
        } finally {
            a.close();
        }

        LineNumberReader b = new LineNumberReader(ba);
        try {
            assertEquals("AbCdEfGhIj", IOUtil.read(b, 10));
        } finally {
            b.close();
        }

        LineNumberReader c = new LineNumberReader(ca);
        try {
            assertEquals("bdfhjl\nprtvxz", IOUtil.skipRead(c));
        } finally {
            c.close();
        }

        LineNumberReader d = new LineNumberReader(da);
        try {
            assertEquals("AbCdEfGdEfGhIjKlM\nOpQrStUvWxYz", IOUtil.markRead(d, 3, 4));
        } finally {
            d.close();
        }

        LineNumberReader e = new LineNumberReader(ea);
        try {
            assertEquals("AbCdEfGhIjKlM", e.readLine());
        } finally {
            e.close();
        }
    }
}
