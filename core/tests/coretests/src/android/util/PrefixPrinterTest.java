/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.util;

import static org.junit.Assert.assertEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PrefixPrinterTest {
    @Test
    public void testSimple() throws Exception {
        final StringBuilder builder = new StringBuilder();
        final Printer printer = new Printer() {
            @Override
            public void println(String x) {
                builder.append(x).append('\n');
            }
        };

        final Printer prefixed = PrefixPrinter.create(printer, "  ");
        prefixed.println("Test");
        prefixed.println("Test");
        assertEquals("  Test\n  Test\n", builder.toString());
    }
}
