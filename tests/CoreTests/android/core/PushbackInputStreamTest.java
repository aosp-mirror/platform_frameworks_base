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
import java.io.PushbackInputStream;
import android.test.suitebuilder.annotation.SmallTest;

public class PushbackInputStreamTest extends TestCase {

    @SmallTest
    public void testPushbackInputStream() throws Exception {
        String str = "AbCdEfGhIjKlM\nOpQrStUvWxYz";
        ByteArrayInputStream aa = new ByteArrayInputStream(str.getBytes());
        ByteArrayInputStream ba = new ByteArrayInputStream(str.getBytes());
        ByteArrayInputStream ca = new ByteArrayInputStream(str.getBytes());

        PushbackInputStream a = new PushbackInputStream(aa, 7);
        try {
            a.unread("push".getBytes());
            assertEquals("pushAbCdEfGhIjKlM\nOpQrStUvWxYz", IOUtil.read(a));
        } finally {
            a.close();
        }

        PushbackInputStream b = new PushbackInputStream(ba, 9);
        try {
            b.unread('X');
            assertEquals("XAbCdEfGhI", IOUtil.read(b, 10));
        } finally {
            b.close();
        }

        PushbackInputStream c = new PushbackInputStream(ca);
        try {
            assertEquals("bdfhjl\nprtvxz", IOUtil.skipRead(c));
        } finally {
            c.close();
        }
    }
}
