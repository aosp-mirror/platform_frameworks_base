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

import java.io.ByteArrayOutputStream;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * A basic test for ByteArrayOutputStraem.
 */
public class ByteArrayOutputStreamTest extends TestCase {

    @SmallTest
    public void testByteArrayOutputStream() throws Exception {
        String str = "AbCdEfGhIjKlMnOpQrStUvWxYz";
        ByteArrayOutputStream a = new ByteArrayOutputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream(10);

        a.write(str.getBytes(), 0, 26);
        a.write('X');
        a.writeTo(b);

        assertEquals(27, a.size());
        assertEquals("AbCdEfGhIjKlMnOpQrStUvWxYzX", a.toString());
        assertEquals("AbCdEfGhIjKlMnOpQrStUvWxYzX", b.toString());
    }
}
