/*
 * Copyright (C) 2009 The Android Open Source Project
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
package android.pim.vcard;

import android.text.TextUtils;

import junit.framework.TestCase;

import java.util.List;

public class VCardUtilsTests extends TestCase {
    public void testContainsOnlyPrintableAscii() {
        assertTrue(VCardUtils.containsOnlyPrintableAscii((String)null));
        assertTrue(VCardUtils.containsOnlyPrintableAscii((String[])null));
        assertTrue(VCardUtils.containsOnlyPrintableAscii((List<String>)null));
        assertTrue(VCardUtils.containsOnlyPrintableAscii(""));
        assertTrue(VCardUtils.containsOnlyPrintableAscii("abcdefghijklmnopqrstuvwxyz"));
        assertTrue(VCardUtils.containsOnlyPrintableAscii("ABCDEFGHIJKLMNOPQRSTUVWXYZ"));
        StringBuilder builder = new StringBuilder();
        for (int i = 0x20; i < 0x7F; i++) {
            builder.append((char)i);
        }
        assertTrue(VCardUtils.containsOnlyPrintableAscii(builder.toString()));
        assertTrue(VCardUtils.containsOnlyPrintableAscii("\r\n"));
        assertFalse(VCardUtils.containsOnlyPrintableAscii("\u0019"));
        assertFalse(VCardUtils.containsOnlyPrintableAscii("\u007F"));
    }

    public void testContainsOnlyNonCrLfPrintableAscii() {
        assertTrue(VCardUtils.containsOnlyNonCrLfPrintableAscii((String)null));
        assertTrue(VCardUtils.containsOnlyNonCrLfPrintableAscii((String[])null));
        assertTrue(VCardUtils.containsOnlyNonCrLfPrintableAscii((List<String>)null));
        assertTrue(VCardUtils.containsOnlyNonCrLfPrintableAscii(""));
        assertTrue(VCardUtils.containsOnlyNonCrLfPrintableAscii("abcdefghijklmnopqrstuvwxyz"));
        assertTrue(VCardUtils.containsOnlyNonCrLfPrintableAscii("ABCDEFGHIJKLMNOPQRSTUVWXYZ"));
        StringBuilder builder = new StringBuilder();
        for (int i = 0x20; i < 0x7F; i++) {
            builder.append((char)i);
        }
        assertTrue(VCardUtils.containsOnlyNonCrLfPrintableAscii(builder.toString()));
        assertFalse(VCardUtils.containsOnlyNonCrLfPrintableAscii("\u0019"));
        assertFalse(VCardUtils.containsOnlyNonCrLfPrintableAscii("\u007F"));
        assertFalse(VCardUtils.containsOnlyNonCrLfPrintableAscii("\r"));
        assertFalse(VCardUtils.containsOnlyNonCrLfPrintableAscii("\n"));
    }

    public void testContainsOnlyAlphaDigitHyphen() {
        assertTrue(VCardUtils.containsOnlyAlphaDigitHyphen((String)null));
        assertTrue(VCardUtils.containsOnlyAlphaDigitHyphen((String[])null));
        assertTrue(VCardUtils.containsOnlyAlphaDigitHyphen((List<String>)null));
        assertTrue(VCardUtils.containsOnlyAlphaDigitHyphen(""));
        assertTrue(VCardUtils.containsOnlyNonCrLfPrintableAscii("abcdefghijklmnopqrstuvwxyz"));
        assertTrue(VCardUtils.containsOnlyNonCrLfPrintableAscii("ABCDEFGHIJKLMNOPQRSTUVWXYZ"));
        assertTrue(VCardUtils.containsOnlyNonCrLfPrintableAscii("0123456789-"));
        for (int i = 0; i < 0x30; i++) {
            if (i == 0x2D) {  // -
                continue;
            }
            assertFalse(VCardUtils.containsOnlyAlphaDigitHyphen(String.valueOf((char)i)));
        }
        for (int i = 0x3A; i < 0x41; i++) {
            assertFalse(VCardUtils.containsOnlyAlphaDigitHyphen(String.valueOf((char)i)));
        }
        for (int i = 0x5B; i < 0x61; i++) {
            assertFalse(VCardUtils.containsOnlyAlphaDigitHyphen(String.valueOf((char)i)));
        }
        for (int i = 0x7B; i < 0x100; i++) {
            assertFalse(VCardUtils.containsOnlyAlphaDigitHyphen(String.valueOf((char)i)));
        }
    }

    public void testToStringAvailableAsV30ParamValue() {
        // Smoke tests.
        assertEquals("HOME", VCardUtils.toStringAsV30ParamValue("HOME"));
        assertEquals("TEL", VCardUtils.toStringAsV30ParamValue("TEL"));
        assertEquals("PAGER", VCardUtils.toStringAsV30ParamValue("PAGER"));

        assertTrue(TextUtils.isEmpty(VCardUtils.toStringAsV30ParamValue("")));
        assertTrue(TextUtils.isEmpty(VCardUtils.toStringAsV30ParamValue(null)));
        assertTrue(TextUtils.isEmpty(VCardUtils.toStringAsV30ParamValue(" \t")));

        // non-Ascii must be allowed
        assertEquals("\u4E8B\u52D9\u6240",
                VCardUtils.toStringAsV30ParamValue("\u4E8B\u52D9\u6240"));
        // Reported as bug report.
        assertEquals("\u8D39", VCardUtils.toStringAsV30ParamValue("\u8D39"));
        assertEquals("\"comma,separated\"",
                VCardUtils.toStringAsV30ParamValue("comma,separated"));
        assertEquals("\"colon:aware\"",
                VCardUtils.toStringAsV30ParamValue("colon:aware"));
        // CTL characters.
        assertEquals("CTLExample",
                VCardUtils.toStringAsV30ParamValue("CTL\u0001Example"));
        assertTrue(TextUtils.isEmpty(
                VCardUtils.toStringAsV30ParamValue("\u0001\u0002\u0003")));
        // DQUOTE must be removed.
        assertEquals("quoted",
                VCardUtils.toStringAsV30ParamValue("\"quoted\""));
        // DQUOTE must be removed basically, but we should detect a space, which
        // require us to use DQUOTE again.
        // Right-side has one more illegal dquote to test quote-handle code thoroughly.
        assertEquals("\"Already quoted\"",
                VCardUtils.toStringAsV30ParamValue("\"Already quoted\"\""));
    }
}
