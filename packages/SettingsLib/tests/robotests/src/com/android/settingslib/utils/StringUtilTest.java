/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.settingslib.utils;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.spy;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.format.DateUtils;
import android.text.style.TtsSpan;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class StringUtilTest {
    private Context mContext;

    @Before
    public void setUp() {
        mContext = spy(RuntimeEnvironment.application);
    }

    @Test
    public void testFormatElapsedTime_WithSeconds_ShowSeconds() {
        final double testMillis = 5 * DateUtils.MINUTE_IN_MILLIS + 30 * DateUtils.SECOND_IN_MILLIS;
        final String expectedTime = "5 min, 30 sec";

        assertThat(StringUtil.formatElapsedTime(mContext, testMillis, true).toString())
                .isEqualTo(expectedTime);
    }

    @Test
    public void testFormatElapsedTime_NoSeconds_DoNotShowSeconds() {
        final double testMillis = 5 * DateUtils.MINUTE_IN_MILLIS + 30 * DateUtils.SECOND_IN_MILLIS;
        final String expectedTime = "6 min";

        assertThat(StringUtil.formatElapsedTime(mContext, testMillis, false).toString())
                .isEqualTo(expectedTime);
    }

    @Test
    public void testFormatElapsedTime_TimeMoreThanOneDay_ShowCorrectly() {
        final double testMillis = 2 * DateUtils.DAY_IN_MILLIS
                + 4 * DateUtils.HOUR_IN_MILLIS + 15 * DateUtils.MINUTE_IN_MILLIS;
        final String expectedTime = "2 days, 4 hr, 15 min";

        assertThat(StringUtil.formatElapsedTime(mContext, testMillis, false).toString())
                .isEqualTo(expectedTime);
    }

    @Test
    public void testFormatElapsedTime_ZeroFieldsInTheMiddleDontShow() {
        final double testMillis = 2 * DateUtils.DAY_IN_MILLIS + 15 * DateUtils.MINUTE_IN_MILLIS;
        final String expectedTime = "2 days, 15 min";

        assertThat(StringUtil.formatElapsedTime(mContext, testMillis, false).toString())
                .isEqualTo(expectedTime);
    }

    @Test
    public void testFormatElapsedTime_FormatZero_WithSeconds() {
        final double testMillis = 0;
        final String expectedTime = "0 sec";

        assertThat(StringUtil.formatElapsedTime(mContext, testMillis, true).toString())
                .isEqualTo(expectedTime);
    }

    @Test
    public void testFormatElapsedTime_FormatZero_NoSeconds() {
        final double testMillis = 0;
        final String expectedTime = "0 min";

        assertThat(StringUtil.formatElapsedTime(mContext, testMillis, false).toString())
                .isEqualTo(expectedTime);
    }

    @Test
    public void testFormatElapsedTime_onlyContainsMinute_hasTtsSpan() {
        final double testMillis = 15 * DateUtils.MINUTE_IN_MILLIS;

        final CharSequence charSequence =
                StringUtil.formatElapsedTime(mContext, testMillis, false);
        assertThat(charSequence).isInstanceOf(SpannableStringBuilder.class);

        final SpannableStringBuilder expectedString = (SpannableStringBuilder) charSequence;
        final TtsSpan[] ttsSpans = expectedString.getSpans(0, expectedString.length(),
                TtsSpan.class);

        assertThat(ttsSpans).asList().hasSize(1);
        assertThat(ttsSpans[0].getType()).isEqualTo(TtsSpan.TYPE_MEASURE);
    }

    @Test
    public void testFormatRelativeTime_WithSeconds_ShowSeconds() {
        final double testMillis = 40 * DateUtils.SECOND_IN_MILLIS;
        final String expectedTime = "Just now";

        assertThat(StringUtil.formatRelativeTime(mContext, testMillis, true).toString()).isEqualTo(
                expectedTime);
    }

    @Test
    public void testFormatRelativeTime_NoSeconds_DoNotShowSeconds() {
        final double testMillis = 40 * DateUtils.SECOND_IN_MILLIS;
        final String expectedTime = "1 minute ago";

        assertThat(StringUtil.formatRelativeTime(mContext, testMillis, false).toString()).isEqualTo(
                expectedTime);
    }

    @Test
    public void testFormatRelativeTime_LessThanTwoMinutes_withSeconds() {
        final double testMillis = 119 * DateUtils.SECOND_IN_MILLIS;
        final String expectedTime = "Just now";

        assertThat(StringUtil.formatRelativeTime(mContext, testMillis, true).toString()).isEqualTo(
                expectedTime);
    }

    @Test
    public void testFormatRelativeTime_LessThanTwoMinutes_NoSeconds() {
        final double testMillis = 119 * DateUtils.SECOND_IN_MILLIS;
        final String expectedTime = "2 minutes ago";

        assertThat(StringUtil.formatRelativeTime(mContext, testMillis, false).toString()).isEqualTo(
                expectedTime);
    }

    @Test
    public void testFormatRelativeTime_TwoMinutes_withSeconds() {
        final double testMillis = 2 * DateUtils.MINUTE_IN_MILLIS;
        final String expectedTime = "2 minutes ago";

        assertThat(StringUtil.formatRelativeTime(mContext, testMillis, true).toString()).isEqualTo(
                expectedTime);
    }

    @Test
    public void testFormatRelativeTime_LessThanTwoHours_withSeconds() {
        final double testMillis = 119 * DateUtils.MINUTE_IN_MILLIS;
        final String expectedTime = "119 minutes ago";

        assertThat(StringUtil.formatRelativeTime(mContext, testMillis, true).toString()).isEqualTo(
                expectedTime);
    }

    @Test
    public void testFormatRelativeTime_TwoHours_withSeconds() {
        final double testMillis = 2 * DateUtils.HOUR_IN_MILLIS;
        final String expectedTime = "2 hours ago";

        assertThat(StringUtil.formatRelativeTime(mContext, testMillis, true).toString()).isEqualTo(
                expectedTime);
    }

    @Test
    public void testFormatRelativeTime_LessThanTwoDays_withSeconds() {
        final double testMillis = 47 * DateUtils.HOUR_IN_MILLIS;
        final String expectedTime = "47 hours ago";

        assertThat(StringUtil.formatRelativeTime(mContext, testMillis, true).toString()).isEqualTo(
                expectedTime);
    }

    @Test
    public void testFormatRelativeTime_TwoDays_withSeconds() {
        final double testMillis = 2 * DateUtils.DAY_IN_MILLIS;
        final String expectedTime = "2 days ago";

        assertThat(StringUtil.formatRelativeTime(mContext, testMillis, true).toString()).isEqualTo(
                expectedTime);
    }

    @Test
    public void testFormatRelativeTime_FormatZero_WithSeconds() {
        final double testMillis = 0;
        final String expectedTime = "Just now";

        assertThat(StringUtil.formatRelativeTime(mContext, testMillis, true).toString()).isEqualTo(
                expectedTime);
    }

    @Test
    public void testFormatRelativeTime_FormatZero_NoSeconds() {
        final double testMillis = 0;
        final String expectedTime = "0 minutes ago";

        assertThat(StringUtil.formatRelativeTime(mContext, testMillis, false).toString()).isEqualTo(
                expectedTime);
    }
}
