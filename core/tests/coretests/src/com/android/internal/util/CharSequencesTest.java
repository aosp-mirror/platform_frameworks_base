/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.internal.util;

import com.android.internal.util.CharSequences;
import static com.android.internal.util.CharSequences.forAsciiBytes;
import junit.framework.TestCase;
import android.test.suitebuilder.annotation.SmallTest;

public class CharSequencesTest extends TestCase {

    @SmallTest
    public void testCharSequences() {
        String s = "Crazy Bob";
        byte[] bytes = s.getBytes();

        String copy = toString(forAsciiBytes(bytes));
        assertTrue(s.equals(copy));

        copy = toString(forAsciiBytes(bytes, 0, s.length()));
        assertTrue(s.equals(copy));

        String crazy = toString(forAsciiBytes(bytes, 0, 5));
        assertTrue("Crazy".equals(crazy));

        String a = toString(forAsciiBytes(bytes, 0, 3).subSequence(2, 3));
        assertTrue("a".equals(a));

        String empty = toString(forAsciiBytes(bytes, 0, 3).subSequence(3, 3));
        assertTrue("".equals(empty));

        assertTrue(CharSequences.equals("bob", "bob"));
        assertFalse(CharSequences.equals("b", "bob"));
        assertFalse(CharSequences.equals("", "bob"));
    }

    /**
     * Converts a CharSequence to a string the slow way. Useful for testing
     * a CharSequence implementation.
     */
    static String toString(CharSequence charSequence) {
        return new StringBuilder().append(charSequence).toString();
    }
}
