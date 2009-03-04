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

import java.io.PrintWriter;
import java.io.StringWriter;
import android.test.suitebuilder.annotation.SmallTest;

public class PrintWriterTest extends TestCase {

    @SmallTest
    public void testPrintWriter() throws Exception {
        String str = "AbCdEfGhIjKlMnOpQrStUvWxYz";
        StringWriter aa = new StringWriter();
        PrintWriter a = new PrintWriter(aa);

        try {
            a.write(str, 0, 26);
            a.write('X');

            assertEquals("AbCdEfGhIjKlMnOpQrStUvWxYzX", aa.toString());

            a.write("alphabravodelta", 5, 5);
            a.append('X');
            assertEquals("AbCdEfGhIjKlMnOpQrStUvWxYzXbravoX", aa.toString());
            a.append("omega");
            assertEquals("AbCdEfGhIjKlMnOpQrStUvWxYzXbravoXomega", aa.toString());
            a.print("ZZZ");
            assertEquals("AbCdEfGhIjKlMnOpQrStUvWxYzXbravoXomegaZZZ", aa.toString());
        } finally {
            a.close();
        }

        StringWriter ba = new StringWriter();
        PrintWriter b = new PrintWriter(ba);
        try {
            b.print(true);
            b.print((char) 'A');
            b.print("BCD".toCharArray());
            b.print((double) 1.2);
            b.print((float) 3);
            b.print((int) 4);
            b.print((long) 5);
            assertEquals("trueABCD1.23.045", ba.toString());
            b.println();
            b.println(true);
            b.println((char) 'A');
            b.println("BCD".toCharArray());
            b.println((double) 1.2);
            b.println((float) 3);
            b.println((int) 4);
            b.println((long) 5);
            b.print("THE END");
            assertEquals("trueABCD1.23.045\ntrue\nA\nBCD\n1.2\n3.0\n4\n5\nTHE END", ba.toString());
        } finally {
            b.close();
        }
    }
}
