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

import java.io.CharArrayWriter;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * Basic tests for CharArrayWriter.
 */
public class CharArrayWriterTest extends TestCase {

    @SmallTest
    public void testCharArrayWriter() throws Exception {
        String str = "AbCdEfGhIjKlMnOpQrStUvWxYz";
        CharArrayWriter a = new CharArrayWriter();
        CharArrayWriter b = new CharArrayWriter();

        a.write(str, 0, 26);
        a.write('X');
        a.writeTo(b);

        assertEquals(27, a.size());
        assertEquals("AbCdEfGhIjKlMnOpQrStUvWxYzX", a.toString());

        b.write("alphabravodelta", 5, 5);
        b.append('X');
        assertEquals("AbCdEfGhIjKlMnOpQrStUvWxYzXbravoX", b.toString());
        b.append("omega");
        assertEquals("AbCdEfGhIjKlMnOpQrStUvWxYzXbravoXomega", b.toString());
    }
}
