/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.shell;

import android.test.suitebuilder.annotation.SmallTest;
import junit.framework.TestCase;
import static com.android.shell.BugreportProgressService.isValid;

@SmallTest
public class UtilitiesTest extends TestCase {

    public void testIsValidChar_valid() {
        String validChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        for (int i = 0; i < validChars.length(); i++) {
            char c = validChars.charAt(i);
            assertTrue("char '" + c + "' should be valid", isValid(c));
        }
    }

    public void testIsValidChar_invalid() {
        String validChars = "/.<>;:'\'\"\\+=*&^%$#@!`~áéíóúãñÂÊÎÔÛ";
        for (int i = 0; i < validChars.length(); i++) {
            char c = validChars.charAt(i);
            assertFalse("char '" + c + "' should not be valid", isValid(c));
        }
    }
}
