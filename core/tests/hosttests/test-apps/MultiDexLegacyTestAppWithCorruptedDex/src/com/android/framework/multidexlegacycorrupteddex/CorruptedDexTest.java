/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.android.framework.multidexlegacycorrupteddex;

import android.test.ActivityInstrumentationTestCase2;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

/**
 * Run the tests with: <code>adb shell am instrument -w
 com.android.framework.multidexlegacycorrupteddex/android.test.InstrumentationTestRunner
</code>
 */
public class CorruptedDexTest extends ActivityInstrumentationTestCase2<MainActivity>
{

    public CorruptedDexTest() {
        super(MainActivity.class);
    }

    /**
     * Tests that when a {@link ClassNotFoundException} is thrown, the message also contains
     * something about the suppressed IOException.
     */
    public void testSupressedExceptions()
    {
        try {
            Class.forName("notapackage.NotAClass");
            throw new AssertionError();
        } catch (ClassNotFoundException e) {
            // expected

//          This the check we should do but API is not yet available in 19
//          Throwable[] suppressed = e.getSuppressed();
//          assertTrue(suppressed.length > 0);
//          boolean ioFound = false;
//          for (Throwable throwable : suppressed) {
//            if (throwable instanceof IOException) {
//              ioFound = true;
//              break;
//            }
//          }
//          assertTrue(ioFound);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream ps = new PrintStream(baos);
            e.printStackTrace(ps);
            ps.close();
            assertTrue("Exception message should mention IOException but is not: "
                  + baos.toString(),
              baos.toString().contains("IOException"));
        }
    }
}
