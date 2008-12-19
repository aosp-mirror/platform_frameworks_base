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

import java.io.PushbackReader;
import java.io.StringReader;
import android.test.suitebuilder.annotation.SmallTest;

public class PushbackReaderTest extends TestCase {

    @SmallTest
    public void testPushbackReader() throws Exception {
        String str = "AbCdEfGhIjKlMnOpQrStUvWxYz";
        StringReader aa = new StringReader(str);
        StringReader ba = new StringReader(str);
        StringReader ca = new StringReader(str);

        PushbackReader a = new PushbackReader(aa, 5);
        try {
            a.unread("PUSH".toCharArray());
            assertEquals("PUSHAbCdEfGhIjKlMnOpQrStUvWxYz", IOUtil.read(a));
        } finally {
            a.close();
        }

        PushbackReader b = new PushbackReader(ba, 15);
        try {
            b.unread('X');
            assertEquals("XAbCdEfGhI", IOUtil.read(b, 10));
        } finally {
            b.close();
        }

        PushbackReader c = new PushbackReader(ca);
        try {
            assertEquals("bdfhjlnprtvxz", IOUtil.skipRead(c));
        } finally {
            c.close();
        }
    }
}
