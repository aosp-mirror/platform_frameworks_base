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

import java.io.StringWriter;
import android.test.suitebuilder.annotation.SmallTest;

public class StringWriterTest extends TestCase {

    @SmallTest
    public void testStringWriter() throws Exception {
        String str = "AbCdEfGhIjKlMnOpQrStUvWxYz";
        StringWriter a = new StringWriter(10);

        a.write(str, 0, 26);
        a.write('X');

        assertEquals("AbCdEfGhIjKlMnOpQrStUvWxYzX", a.toString());

        a.write("alphabravodelta", 5, 5);
        a.append('X');
        assertEquals("AbCdEfGhIjKlMnOpQrStUvWxYzXbravoX", a.toString());
        a.append("omega");
        assertEquals("AbCdEfGhIjKlMnOpQrStUvWxYzXbravoXomega", a.toString());
    }
}
