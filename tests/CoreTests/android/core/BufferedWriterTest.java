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

import java.io.BufferedWriter;
import java.io.StringWriter;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * Some basic tests for BufferedWriter.
 */
public class BufferedWriterTest extends TestCase {

    @SmallTest
    public void testBufferedWriter() throws Exception {
        String str = "AbCdEfGhIjKlMnOpQrStUvWxYz";
        StringWriter aa = new StringWriter();

        BufferedWriter a = new BufferedWriter(aa, 20);
        try {
            a.write(str.toCharArray(), 0, 26);
            a.write('X');
            a.flush();
            assertEquals("AbCdEfGhIjKlMnOpQrStUvWxYzX", aa.toString());

            a.write("alphabravodelta", 5, 5);
            a.flush();
            assertEquals("AbCdEfGhIjKlMnOpQrStUvWxYzXbravo", aa.toString());
            a.newLine();
            a.write("I'm on a new line.");
            a.flush();
            assertEquals("AbCdEfGhIjKlMnOpQrStUvWxYzXbravo\nI\'m on a new line.", aa.toString());
        } finally {
            a.close();
        }
    }
}
