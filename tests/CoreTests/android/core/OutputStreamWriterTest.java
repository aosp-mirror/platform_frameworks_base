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
import java.io.OutputStreamWriter;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * Tests basic functionality of an OutputStreamWriter.
 */
public class OutputStreamWriterTest extends TestCase {
    
    @SmallTest
    public void testOutputStreamWriter() throws Exception {
        String str = "AbCdEfGhIjKlMnOpQrStUvWxYz";
        ByteArrayOutputStream aa = new ByteArrayOutputStream();
        OutputStreamWriter a = new OutputStreamWriter(aa, "ISO8859_1");
        try {
            a.write(str, 0, 4);
            a.write('A');
            // We have to flush the OutputStreamWriter to guarantee
            // that the results will appear in the underlying OutputStream
            a.flush();
            assertEquals("ISO8859_1", a.getEncoding());
            assertEquals(5, aa.size());
            assertEquals("AbCdA", aa.toString());
        } finally {
            a.close();
        }
    }
}
