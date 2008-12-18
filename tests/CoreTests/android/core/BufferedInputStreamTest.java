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

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * Tests to verify that simple functionality works for BufferedInputStreams.
 */
public class BufferedInputStreamTest extends TestCase {

    @SmallTest
    public void testBufferedInputStream() throws Exception {
        String str = "AbCdEfGhIjKlM\nOpQrStUvWxYz";
        ByteArrayInputStream aa = new ByteArrayInputStream(str.getBytes());
        ByteArrayInputStream ba = new ByteArrayInputStream(str.getBytes());
        ByteArrayInputStream ca = new ByteArrayInputStream(str.getBytes());
        ByteArrayInputStream da = new ByteArrayInputStream(str.getBytes());
        ByteArrayInputStream ea = new ByteArrayInputStream(str.getBytes());

        BufferedInputStream a = new BufferedInputStream(aa, 6);
        try {
            assertEquals(str, IOUtil.read(a));
        } finally {
            a.close();
        }

        BufferedInputStream b = new BufferedInputStream(ba, 7);
        try {
            assertEquals("AbCdEfGhIj", IOUtil.read(b, 10));
        } finally {
            b.close();
        }

        BufferedInputStream c = new BufferedInputStream(ca, 9);
        try {
            assertEquals("bdfhjl\nprtvxz", IOUtil.skipRead(c));
        } finally {
            c.close();
        }

        BufferedInputStream d = new BufferedInputStream(da, 9);
        try {
            assertEquals('A', d.read());
            d.mark(15);
            assertEquals('b', d.read());
            assertEquals('C', d.read());
            d.reset();
            assertEquals('b', d.read());
        } finally {
            d.close();
        }

        BufferedInputStream e = new BufferedInputStream(ea, 11);
        try {
            // test that we can ask for more than is present, and that we'll get
            // back only what is there.
            assertEquals(str, IOUtil.read(e, 10000));
        } finally {
            e.close();
        }
    }
}
