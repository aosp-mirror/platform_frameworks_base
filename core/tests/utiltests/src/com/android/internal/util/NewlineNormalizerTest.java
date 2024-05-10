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
 * Test for {@link NewlineNormalizer}
 * @hide
 */
@DisabledOnRavenwood(blockedBy = NewlineNormalizer.class)
@RunWith(AndroidJUnit4.class)
public class NewlineNormalizerTest {

    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    @Test
    public void testEmptyInput() {
        assertEquals("", NewlineNormalizer.normalizeNewlines(""));
    }

    @Test
    public void testSingleNewline() {
        assertEquals("\n", NewlineNormalizer.normalizeNewlines("\n"));
    }

    @Test
    public void testMultipleConsecutiveNewlines() {
        assertEquals("\n", NewlineNormalizer.normalizeNewlines("\n\n\n\n\n"));
    }

    @Test
    public void testNewlinesWithSpacesAndTabs() {
        String input = "Line 1\n  \n \t \n\tLine 2";
        // Adjusted expected output to include the tab character
        String expected = "Line 1\n\tLine 2";
        assertEquals(expected, NewlineNormalizer.normalizeNewlines(input));
    }

    @Test
    public void testMixedNewlineCharacters() {
        String input = "Line 1\r\nLine 2\u000BLine 3\fLine 4\u2028Line 5\u2029Line 6";
        String expected = "Line 1\nLine 2\nLine 3\nLine 4\nLine 5\nLine 6";
        assertEquals(expected, NewlineNormalizer.normalizeNewlines(input));
    }
}
