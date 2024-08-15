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

package com.android.internal.util;

import static junit.framework.Assert.assertEquals;


import android.platform.test.annotations.DisabledOnRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test for {@link NotificationBigTextNormalizer}
 * @hide
 */
@DisabledOnRavenwood(blockedBy = NotificationBigTextNormalizer.class)
@RunWith(AndroidJUnit4.class)
public class NotificationBigTextNormalizerTest {

    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();


    @Test
    public void testEmptyInput() {
        assertEquals("", NotificationBigTextNormalizer.normalizeBigText(""));
    }

    @Test
    public void testSingleNewline() {
        assertEquals("", NotificationBigTextNormalizer.normalizeBigText("\n"));
    }

    @Test
    public void testMultipleConsecutiveNewlines() {
        assertEquals("", NotificationBigTextNormalizer.normalizeBigText("\n\n\n\n\n"));
    }

    @Test
    public void testNewlinesWithSpacesAndTabs() {
        String input = "Line 1\n  \n \t \n\tLine 2";
        // Adjusted expected output to include the tab character
        String expected = "Line 1\nLine 2";
        assertEquals(expected, NotificationBigTextNormalizer.normalizeBigText(input));
    }

    @Test
    public void testMixedNewlineCharacters() {
        String input = "Line 1\r\nLine 2\u000BLine 3\fLine 4\u2028Line 5\u2029Line 6";
        String expected = "Line 1\nLine 2\nLine 3\nLine 4\nLine 5\nLine 6";
        assertEquals(expected, NotificationBigTextNormalizer.normalizeBigText(input));
    }

    @Test
    public void testConsecutiveSpaces() {
        // Only spaces
        assertEquals("This is a test.", NotificationBigTextNormalizer.normalizeBigText("This"
                + "              is   a                         test."));
        // Zero width characters bw spaces.
        assertEquals("This is a test.", NotificationBigTextNormalizer.normalizeBigText("This"
                + "\u200B \u200B \u200B \u200B \u200B \u200B \u200B \u200Bis\uFEFF \uFEFF \uFEFF"
                + " \uFEFFa \u034F \u034F \u034F \u034F \u034F \u034Ftest."));

        // Invisible formatting characters bw spaces.
        assertEquals("This is a test.", NotificationBigTextNormalizer.normalizeBigText("This"
                + "\u2061 \u2061 \u2061 \u2061 \u2061 \u2061 \u2061 \u2061is\u206E \u206E \u206E"
                + " \u206Ea \uFFFB \uFFFB \uFFFB \uFFFB \uFFFB \uFFFBtest."));
        // Non breakable spaces
        assertEquals("This is a test.", NotificationBigTextNormalizer.normalizeBigText("This"
                + "\u00A0\u00A0\u00A0\u00A0\u00A0\u00A0is\u2005 \u2005 \u2005"
                + " \u2005a\u2005\u2005\u2005 \u2005\u2005\u2005test."));
    }

    @Test
    public void testZeroWidthCharRemoval() {
        // Test each character individually
        char[] zeroWidthChars = { '\u200B', '\u200C', '\u200D', '\uFEFF', '\u034F' };

        for (char c : zeroWidthChars) {
            String input = "Test" + c + "string";
            String expected = "Teststring";
            assertEquals(expected, NotificationBigTextNormalizer.normalizeBigText(input));
        }
    }

    @Test
    public void testWhitespaceReplacement() {
        assertEquals("This text has horizontal whitespace.",
                NotificationBigTextNormalizer.normalizeBigText(
                        "This\ttext\thas\thorizontal\twhitespace."));
        assertEquals("This text has mixed whitespace.",
                NotificationBigTextNormalizer.normalizeBigText(
                        "This  text  has \u00A0 mixed\u2009whitespace."));
        assertEquals("This text has leading and trailing whitespace.",
                NotificationBigTextNormalizer.normalizeBigText(
                        "\t This text has leading and trailing whitespace. \n"));
    }

    @Test
    public void testInvisibleFormattingCharacterRemoval() {
        // Test each character individually
        char[] invisibleFormattingChars = {
                '\u2060', '\u2061', '\u2062', '\u2063', '\u2064', '\u2065',
                '\u206A', '\u206B', '\u206C', '\u206D', '\u206E', '\u206F',
                '\uFFF9', '\uFFFA', '\uFFFB'
        };

        for (char c : invisibleFormattingChars) {
            String input = "Test " + c + "string";
            String expected = "Test string";
            assertEquals(expected, NotificationBigTextNormalizer.normalizeBigText(input));
        }
    }
    @Test
    public void testNonBreakSpaceReplacement() {
        // Test each character individually
        char[] nonBreakSpaces = {
                '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
                '\u2003', '\u2004', '\u2005', '\u2006', '\u2007',
                '\u2008', '\u2009', '\u200A', '\u202F', '\u205F', '\u3000'
        };

        for (char c : nonBreakSpaces) {
            String input = "Test" + c + "string";
            String expected = "Test string";
            assertEquals(expected, NotificationBigTextNormalizer.normalizeBigText(input));
        }
    }
}
