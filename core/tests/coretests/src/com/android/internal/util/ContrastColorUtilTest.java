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

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.TextAppearanceSpan;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.R;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@IgnoreUnderRavenwood(blockedBy = Color.class)
public class ContrastColorUtilTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    @Test
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

    @Test
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

    @Test
    public void testBuilder_ensureColorSpanContrast_removesAllFullLengthColorSpans() {
        Context context = InstrumentationRegistry.getContext();

        Spannable text = new SpannableString("blue text with yellow and green");
        text.setSpan(new ForegroundColorSpan(Color.YELLOW), 15, 21,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(Color.BLUE), 0, text.length(),
                Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        TextAppearanceSpan taSpan = new TextAppearanceSpan(context,
                R.style.TextAppearance_DeviceDefault_Notification_Title);
        assertThat(taSpan.getTextColor()).isNotNull();  // it must be set to prove it is cleared.
        text.setSpan(taSpan, 0, text.length(),
                Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        text.setSpan(new ForegroundColorSpan(Color.GREEN), 26, 31,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        Spannable result = (Spannable) ContrastColorUtil.ensureColorSpanContrast(text, Color.BLACK);
        Object[] spans = result.getSpans(0, result.length(), Object.class);
        assertThat(spans).hasLength(3);

        assertThat(result.getSpanStart(spans[0])).isEqualTo(15);
        assertThat(result.getSpanEnd(spans[0])).isEqualTo(21);
        assertThat(((ForegroundColorSpan) spans[0]).getForegroundColor()).isEqualTo(Color.YELLOW);

        assertThat(result.getSpanStart(spans[1])).isEqualTo(0);
        assertThat(result.getSpanEnd(spans[1])).isEqualTo(31);
        assertThat(spans[1]).isNotSameInstanceAs(taSpan);  // don't mutate the existing span
        assertThat(((TextAppearanceSpan) spans[1]).getFamily()).isEqualTo(taSpan.getFamily());
        assertThat(((TextAppearanceSpan) spans[1]).getTextColor()).isNull();

        assertThat(result.getSpanStart(spans[2])).isEqualTo(26);
        assertThat(result.getSpanEnd(spans[2])).isEqualTo(31);
        assertThat(((ForegroundColorSpan) spans[2]).getForegroundColor()).isEqualTo(Color.GREEN);
    }

    @Test
    public void testBuilder_ensureColorSpanContrast_partialLength_adjusted() {
        int background = 0xFFFF0101;  // Slightly lighter red
        CharSequence text = new SpannableStringBuilder()
                .append("text with ")
                .append("some red", new ForegroundColorSpan(Color.RED),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        CharSequence result = ContrastColorUtil.ensureColorSpanContrast(text, background);

        // ensure the span has been updated to have > 1.3:1 contrast ratio with fill color
        Object[] spans = ((Spannable) result).getSpans(0, result.length(), Object.class);
        assertThat(spans).hasLength(1);
        int foregroundColor = ((ForegroundColorSpan) spans[0]).getForegroundColor();
        assertContrastIsWithinRange(foregroundColor, background, 3, 3.2);
    }

    @Test
    public void testBuilder_ensureColorSpanContrast_worksWithComplexInput() {
        Context context = InstrumentationRegistry.getContext();

        Spannable text = new SpannableString("blue text with yellow and green and cyan");
        text.setSpan(new ForegroundColorSpan(Color.YELLOW), 15, 21,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(Color.BLUE), 0, text.length(),
                Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        // cyan TextAppearanceSpan
        TextAppearanceSpan taSpan = new TextAppearanceSpan(context,
                R.style.TextAppearance_DeviceDefault_Notification_Title);
        taSpan = new TextAppearanceSpan(taSpan.getFamily(), taSpan.getTextStyle(),
                taSpan.getTextSize(), ColorStateList.valueOf(Color.CYAN), null);
        text.setSpan(taSpan, 36, 40,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(Color.GREEN), 26, 31,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        Spannable result = (Spannable) ContrastColorUtil.ensureColorSpanContrast(text, Color.GRAY);
        Object[] spans = result.getSpans(0, result.length(), Object.class);
        assertThat(spans).hasLength(3);

        assertThat(result.getSpanStart(spans[0])).isEqualTo(15);
        assertThat(result.getSpanEnd(spans[0])).isEqualTo(21);
        assertThat(((ForegroundColorSpan) spans[0]).getForegroundColor()).isEqualTo(Color.YELLOW);

        assertThat(result.getSpanStart(spans[1])).isEqualTo(36);
        assertThat(result.getSpanEnd(spans[1])).isEqualTo(40);
        assertThat(spans[1]).isNotSameInstanceAs(taSpan);  // don't mutate the existing span
        assertThat(((TextAppearanceSpan) spans[1]).getFamily()).isEqualTo(taSpan.getFamily());
        ColorStateList newCyanList = ((TextAppearanceSpan) spans[1]).getTextColor();
        assertThat(newCyanList).isNotNull();
        assertContrastIsWithinRange(newCyanList.getDefaultColor(), Color.GRAY, 3, 3.2);

        assertThat(result.getSpanStart(spans[2])).isEqualTo(26);
        assertThat(result.getSpanEnd(spans[2])).isEqualTo(31);
        int newGreen = ((ForegroundColorSpan) spans[2]).getForegroundColor();
        assertThat(newGreen).isNotEqualTo(Color.GREEN);
        assertContrastIsWithinRange(newGreen, Color.GRAY, 3, 3.2);
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
