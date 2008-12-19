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
import android.test.suitebuilder.annotation.SmallTest;

/**
 * Tests for functionality of class Integer to parse integers.
 */
public class ParseIntTest extends TestCase {
    
    @SmallTest
    public void testParseInt() throws Exception {
        assertEquals(0, Integer.parseInt("0", 10));
        assertEquals(473, Integer.parseInt("473", 10));
        assertEquals(0, Integer.parseInt("-0", 10));
        assertEquals(-255, Integer.parseInt("-FF", 16));
        assertEquals(102, Integer.parseInt("1100110", 2));
        assertEquals(2147483647, Integer.parseInt("2147483647", 10));
        assertEquals(-2147483648, Integer.parseInt("-2147483648", 10));

        try {
            Integer.parseInt("2147483648", 10);
            fail();
        } catch (NumberFormatException e) {
            // ok
        }

        try {
            Integer.parseInt("-2147483649", 10);
            fail();
        } catch (NumberFormatException e) {
            // ok
        }

        // One digit too many
        try {
            Integer.parseInt("21474836470", 10);
            fail();
        } catch (NumberFormatException e) {
            // ok
        }

        try {
            Integer.parseInt("-21474836480", 10);
            fail();
        } catch (NumberFormatException e) {
            // ok
        }

        try {
            Integer.parseInt("21474836471", 10);
            fail();
        } catch (NumberFormatException e) {
            // ok
        }

        try {
            Integer.parseInt("-21474836481", 10);
            fail();
        } catch (NumberFormatException e) {
            // ok
        }

        try {
            Integer.parseInt("214748364710", 10);
            fail();
        } catch (NumberFormatException e) {
            // ok
        }

        try {
            Integer.parseInt("-214748364811", 10);
            fail();
        } catch (NumberFormatException e) {
            // ok
        }

        try {
            Integer.parseInt("99", 8);
            fail();
        } catch (NumberFormatException e) {
            // ok
        }

        try {
            Integer.parseInt("Kona", 10);
            fail();
        } catch (NumberFormatException e) {
            // ok
        }

        assertEquals(411787, Integer.parseInt("Kona", 27));
    }
}
