/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.net.wifi.util;

import static android.net.wifi.util.HexEncoding.decode;
import static android.net.wifi.util.HexEncoding.encode;
import static android.net.wifi.util.HexEncoding.encodeToString;

import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/** Copied from {@link libcore.libcore.util.HexEncodingTest}. */
public class HexEncodingTest extends TestCase {

    public void testEncodeByte() {
        Object[][] testCases = new Object[][]{
                {0x01, "01"},
                {0x09, "09"},
                {0x0A, "0A"},
                {0x0F, "0F"},
                {0x10, "10"},
                {0x1F, "1F"},
                {0x20, "20"},
                {0x7F, "7F"},
                {0x80, "80"},
                {0xFF, "FF"},
        };
        for (Object[] testCase : testCases) {
            Number toEncode = (Number) testCase[0];
            String expected = (String) testCase[1];

            String actualUpper = encodeToString(toEncode.byteValue(), true /* upperCase */);
            assertEquals(upper(expected), actualUpper);

            String actualLower = encodeToString(toEncode.byteValue(), false /* upperCase */);
            assertEquals(lower(expected), actualLower);
        }
    }

    public void testEncodeBytes() {
        Object[][] testCases = new Object[][]{
                {"avocados".getBytes(StandardCharsets.UTF_8), "61766F6361646F73"},
        };

        for (Object[] testCase : testCases) {
            byte[] bytes = (byte[]) testCase[0];
            String encodedLower = lower((String) testCase[1]);
            String encodedUpper = upper((String) testCase[1]);

            assertArraysEqual(encodedUpper.toCharArray(), encode(bytes));
            assertArraysEqual(encodedUpper.toCharArray(), encode(bytes, true /* upperCase */));
            assertArraysEqual(encodedLower.toCharArray(), encode(bytes, false /* upperCase */));

            assertArraysEqual(bytes, decode(encode(bytes), false /* allowSingleChar */));

            // Make sure we can handle lower case hex encodings as well.
            assertArraysEqual(bytes,
                    decode(encodedLower.toCharArray(), false /* allowSingleChar */));
        }
    }

    public void testDecode_allow4Bit() {
        assertArraysEqual(new byte[]{6}, decode("6".toCharArray(), true));
        assertArraysEqual(new byte[]{6, 0x76}, decode("676".toCharArray(), true));
    }

    public void testDecode_disallow4Bit() {
        try {
            decode("676".toCharArray(), false /* allowSingleChar */);
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    public void testDecode_invalid() {
        try {
            decode("DEADBARD".toCharArray(), false /* allowSingleChar */);
            fail();
        } catch (IllegalArgumentException expected) {
        }

        // This demonstrates a difference in behaviour from apache commons : apache
        // commons uses Character.isDigit and would successfully decode a string with
        // arabic and devanagari characters.
        try {
            decode("६१٧٥٥F6361646F73".toCharArray(), false /* allowSingleChar */);
            fail();
        } catch (IllegalArgumentException expected) {
        }

        try {
            decode("#%6361646F73".toCharArray(), false /* allowSingleChar */);
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    private static void assertArraysEqual(char[] lhs, char[] rhs) {
        assertEquals(new String(lhs), new String(rhs));
    }

    private static void assertArraysEqual(byte[] lhs, byte[] rhs) {
        assertEquals(Arrays.toString(lhs), Arrays.toString(rhs));
    }

    private static String lower(String string) {
        return string.toLowerCase(Locale.ROOT);
    }

    private static String upper(String string) {
        return string.toUpperCase(Locale.ROOT);
    }
}
