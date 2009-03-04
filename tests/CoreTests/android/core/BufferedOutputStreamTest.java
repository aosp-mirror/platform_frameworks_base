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

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * Tests to verify that simple functionality works for BufferedOutputStreams.
 */
public class BufferedOutputStreamTest extends TestCase {

    @SmallTest
    public void testBufferedOutputStream() throws Exception {
        String str = "AbCdEfGhIjKlMnOpQrStUvWxYz";
        ByteArrayOutputStream aa = new ByteArrayOutputStream();
        BufferedOutputStream a = new BufferedOutputStream(aa, 15);
        try {
            a.write(str.getBytes(), 0, 26);
            a.write('A');

            assertEquals(26, aa.size());
            assertEquals(aa.toString(), str);

            a.flush();

            assertEquals(27, aa.size());
            assertEquals("AbCdEfGhIjKlMnOpQrStUvWxYzA", aa.toString());
        } finally {
            a.close();
        }
    }
}
