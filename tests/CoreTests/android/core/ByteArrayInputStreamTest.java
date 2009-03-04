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
import android.test.suitebuilder.annotation.SmallTest;

/**
 * Tests to verify that simple functionality works for ByteArrayInputStreams.
 */
public class ByteArrayInputStreamTest extends TestCase {

    @SmallTest
    public void testByteArrayInputStream() throws Exception {
        String str = "AbCdEfGhIjKlMnOpQrStUvWxYz";

        ByteArrayInputStream a = new ByteArrayInputStream(str.getBytes());
        ByteArrayInputStream b = new ByteArrayInputStream(str.getBytes());
        ByteArrayInputStream c = new ByteArrayInputStream(str.getBytes());
        ByteArrayInputStream d = new ByteArrayInputStream(str.getBytes());

        assertEquals(str, IOUtil.read(a));
        assertEquals("AbCdEfGhIj", IOUtil.read(b, 10));
        assertEquals("bdfhjlnprtvxz", IOUtil.skipRead(c));
        assertEquals("AbCdEfGdEfGhIjKlMnOpQrStUvWxYz", IOUtil.markRead(d, 3, 4));
    }
}
