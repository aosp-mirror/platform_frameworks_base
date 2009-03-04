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

import java.io.CharArrayReader;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * Basic tests for CharArrayReader.
 */
public class CharArrayReaderTest extends TestCase {

    @SmallTest
    public void testCharArrayReader() throws Exception {
        String str = "AbCdEfGhIjKlMnOpQrStUvWxYz";
        CharArrayReader a = new CharArrayReader(str.toCharArray());
        CharArrayReader b = new CharArrayReader(str.toCharArray());
        CharArrayReader c = new CharArrayReader(str.toCharArray());
        CharArrayReader d = new CharArrayReader(str.toCharArray());

        assertEquals(str, IOUtil.read(a));
        assertEquals("AbCdEfGhIj", IOUtil.read(b, 10));
        assertEquals("bdfhjlnprtvxz", IOUtil.skipRead(c));
        assertEquals("AbCdEfGdEfGhIjKlMnOpQrStUvWxYz", IOUtil.markRead(d, 3, 4));
    }
}
