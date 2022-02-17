/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.internal.util;

import static androidx.core.graphics.ColorUtils.calculateContrast;

import static com.google.common.truth.Truth.assertThat;

import android.graphics.Color;

import androidx.test.filters.SmallTest;

import junit.framework.TestCase;

public class ContrastColorUtilTest extends TestCase {

    @SmallTest
    public void testEnsureTextContrastAgainstDark() {
        int darkBg = 0xFF35302A;

        int blueContrastColor = ContrastColorUtil.ensureTextContrast(Color.BLUE, darkBg, true);
        assertContrastIsWithinRange(blueContrastColor, darkBg, 4.5, 4.75);

        int redContrastColor = ContrastColorUtil.ensureTextContrast(Color.RED, darkBg, true);
        assertContrastIsWithinRange(redContrastColor, darkBg, 4.5, 4.75);

        final int darkGreen = 0xff008800;
        int greenContrastColor = ContrastColorUtil.ensureTextContrast(darkGreen, darkBg, true);
        assertContrastIsWithinRange(greenContrastColor, darkBg, 4.5, 4.75);

        int grayContrastColor = ContrastColorUtil.ensureTextContrast(Color.DKGRAY, darkBg, true);
        assertContrastIsWithinRange(grayContrastColor, darkBg, 4.5, 4.75);

        int selfContrastColor = ContrastColorUtil.ensureTextContrast(darkBg, darkBg, true);
        assertContrastIsWithinRange(selfContrastColor, darkBg, 4.5, 4.75);
    }

    @SmallTest
    public void testEnsureTextContrastAgainstLight() {
        int lightBg = 0xFFFFF8F2;

        final int lightBlue = 0xff8888ff;
        int blueContrastColor = ContrastColorUtil.ensureTextContrast(lightBlue, lightBg, false);
        assertContrastIsWithinRange(blueContrastColor, lightBg, 4.5, 4.75);

        int redContrastColor = ContrastColorUtil.ensureTextContrast(Color.RED, lightBg, false);
        assertContrastIsWithinRange(redContrastColor, lightBg, 4.5, 4.75);

        int greenContrastColor = ContrastColorUtil.ensureTextContrast(Color.GREEN, lightBg, false);
        assertContrastIsWithinRange(greenContrastColor, lightBg, 4.5, 4.75);

        int grayContrastColor = ContrastColorUtil.ensureTextContrast(Color.LTGRAY, lightBg, false);
        assertContrastIsWithinRange(grayContrastColor, lightBg, 4.5, 4.75);

        int selfContrastColor = ContrastColorUtil.ensureTextContrast(lightBg, lightBg, false);
        assertContrastIsWithinRange(selfContrastColor, lightBg, 4.5, 4.75);
    }

    public static void assertContrastIsWithinRange(int foreground, int background,
            double minContrast, double maxContrast) {
        assertContrastIsAtLeast(foreground, background, minContrast);
        assertContrastIsAtMost(foreground, background, maxContrast);
    }

    public static void assertContrastIsAtLeast(int foreground, int background, double minContrast) {
        try {
            assertThat(calculateContrast(foreground, background)).isAtLeast(minContrast);
        } catch (AssertionError e) {
            throw new AssertionError(
                    String.format("Insufficient contrast: foreground=#%08x background=#%08x",
                            foreground, background), e);
        }
    }

    public static void assertContrastIsAtMost(int foreground, int background, double maxContrast) {
        try {
            assertThat(calculateContrast(foreground, background)).isAtMost(maxContrast);
        } catch (AssertionError e) {
            throw new AssertionError(
                    String.format("Excessive contrast: foreground=#%08x background=#%08x",
                            foreground, background), e);
        }
    }

}
