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

import java.io.BufferedReader;
import java.io.StringReader;
import android.test.suitebuilder.annotation.MediumTest;

/**
 * Tests to verify that simple functionality works for BufferedReaders.
 */
public class BufferedReaderTest extends TestCase {

    @MediumTest
    public void testBufferedReader() throws Exception {
        String str = "AbCdEfGhIjKlMnOpQrStUvWxYz";
        StringReader aa = new StringReader(str);
        StringReader ba = new StringReader(str);
        StringReader ca = new StringReader(str);
        StringReader da = new StringReader(str);

        BufferedReader a = new BufferedReader(aa, 5);
        try {
            assertEquals(str, IOUtil.read(a));
        } finally {
            a.close();
        }

        BufferedReader b = new BufferedReader(ba, 15);
        try {
            assertEquals("AbCdEfGhIj", IOUtil.read(b, 10));
        } finally {
            b.close();
        }

        BufferedReader c = new BufferedReader(ca);
        try {
            assertEquals("bdfhjlnprtvxz", IOUtil.skipRead(c));
        } finally {
            c.close();
        }

        BufferedReader d = new BufferedReader(da);
        try {
            assertEquals("AbCdEfGdEfGhIjKlMnOpQrStUvWxYz", IOUtil.markRead(d, 3, 4));
        } finally {
            d.close();
        }
    }
}
