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

import com.android.settingslib.R;
import com.android.settingslib.SettingsLibRobolectricTestRunner;

import java.time.Clock;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RuntimeEnvironment;

import java.time.Duration;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSettings.ShadowSystem;
import org.robolectric.shadows.ShadowSystemClock;

@RunWith(SettingsLibRobolectricTestRunner.class)
public class PowerUtilTest {
    public static final String TEST_BATTERY_LEVEL_10 = "10%";
    public static final String FIFTEEN_MIN_FORMATTED = "15m";
    public static final long SEVENTEEN_MIN_MILLIS = Duration.ofMinutes(17).toMillis();
    public static final long FIVE_MINUTES_MILLIS = Duration.ofMinutes(5).toMillis();
    public static final long TEN_MINUTES_MILLIS = Duration.ofMinutes(10).toMillis();
    public static final long THREE_DAYS_MILLIS = Duration.ofDays(3).toMillis();
    public static final long THIRTY_HOURS_MILLIS = Duration.ofHours(30).toMillis();
    public static final String TWO_DAYS_FORMATTED = "2 days";
    public static final String THIRTY_HOURS_FORMATTED = "1d 6h";
    public static final String NORMAL_CASE_EXPECTED_PREFIX = "Will last until about";
    public static final String ENHANCED_SUFFIX = "based on your usage";

    private Context mContext;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mContext = spy(RuntimeEnvironment.application);
    }

    @Test
    @Config(shadows = {ShadowSystemClock.class})
    public void testGetBatteryRemainingStringFormatted_moreThanFifteenMinutes_withPercentage() {
        String info = PowerUtil.getBatteryRemainingStringFormatted(mContext,
                SEVENTEEN_MIN_MILLIS,
                TEST_BATTERY_LEVEL_10,
                true /* basedOnUsage */);
        String info2 = PowerUtil.getBatteryRemainingStringFormatted(mContext,
                SEVENTEEN_MIN_MILLIS,
                TEST_BATTERY_LEVEL_10,
                false /* basedOnUsage */);

        // We only add special mention for the long string
        assertThat(info).contains(NORMAL_CASE_EXPECTED_PREFIX);
        assertThat(info).contains(ENHANCED_SUFFIX);
        assertThat(info).contains("%");
        // shortened string should not have extra text
        assertThat(info2).contains(NORMAL_CASE_EXPECTED_PREFIX);
        assertThat(info2).doesNotContain(ENHANCED_SUFFIX);
        assertThat(info2).contains("%");
    }

    @Test
    public void testGetBatteryRemainingStringFormatted_moreThanFifteenMinutes_noPercentage() {
        String info = PowerUtil.getBatteryRemainingStringFormatted(mContext,
                SEVENTEEN_MIN_MILLIS,
                null /* percentageString */,
                true /* basedOnUsage */);
        String info2 = PowerUtil.getBatteryRemainingStringFormatted(mContext,
                SEVENTEEN_MIN_MILLIS,
                null /* percentageString */,
                false /* basedOnUsage */);

        // We only have % when it is provided
        assertThat(info).contains(NORMAL_CASE_EXPECTED_PREFIX);
        assertThat(info).contains(ENHANCED_SUFFIX);
        assertThat(info).doesNotContain("%");
        // shortened string should not have extra text
        assertThat(info2).contains(NORMAL_CASE_EXPECTED_PREFIX);
        assertThat(info2).doesNotContain(ENHANCED_SUFFIX);
        assertThat(info2).doesNotContain("%");
    }


    @Test
    public void testGetBatteryRemainingStringFormatted_lessThanSevenMinutes_usesCorrectString() {
        String info = PowerUtil.getBatteryRemainingStringFormatted(mContext,
                FIVE_MINUTES_MILLIS,
                TEST_BATTERY_LEVEL_10 /* percentageString */,
                true /* basedOnUsage */);
        String info2 = PowerUtil.getBatteryRemainingStringFormatted(mContext,
                FIVE_MINUTES_MILLIS,
                null /* percentageString */,
                true /* basedOnUsage */);

        // additional battery percentage in this string
        assertThat(info).isEqualTo("Phone may shutdown soon (10%)");
        // shortened string should not have percentage
        assertThat(info2).isEqualTo("Phone may shutdown soon");
    }

    @Test
    public void testGetBatteryRemainingStringFormatted_betweenSevenAndFifteenMinutes_usesCorrectString() {
        String info = PowerUtil.getBatteryRemainingStringFormatted(mContext,
                TEN_MINUTES_MILLIS,
                null /* percentageString */,
                true /* basedOnUsage */);
        String info2 = PowerUtil.getBatteryRemainingStringFormatted(mContext,
                TEN_MINUTES_MILLIS,
                TEST_BATTERY_LEVEL_10 /* percentageString */,
                true /* basedOnUsage */);

        // shortened string should not have percentage
        assertThat(info).isEqualTo("Less than 15m remaining");
        // Add percentage to string when provided
        assertThat(info2).isEqualTo("Less than 15m remaining (10%)");
    }

    @Test
    public void testGetBatteryRemainingStringFormatted_betweenOneAndTwoDays_usesCorrectString() {
        String info = PowerUtil.getBatteryRemainingStringFormatted(mContext,
                THIRTY_HOURS_MILLIS,
                null /* percentageString */,
                true /* basedOnUsage */);
        String info2 = PowerUtil.getBatteryRemainingStringFormatted(mContext,
                THIRTY_HOURS_MILLIS,
                TEST_BATTERY_LEVEL_10 /* percentageString */,
                false /* basedOnUsage */);

        // We only add special mention for the long string
        assertThat(info).isEqualTo("About 1d 6h left based on your usage");
        // shortened string should not have extra text
        assertThat(info2).isEqualTo("About 1d 6h left (10%)");
    }

    @Test
    public void testGetBatteryRemainingStringFormatted_moreThanTwoDays_usesCorrectString() {
        String info = PowerUtil.getBatteryRemainingStringFormatted(mContext,
                THREE_DAYS_MILLIS,
                null /* percentageString */,
                true /* basedOnUsage */);
        String info2 = PowerUtil.getBatteryRemainingStringFormatted(mContext,
                THREE_DAYS_MILLIS,
                TEST_BATTERY_LEVEL_10 /* percentageString */,
                true /* basedOnUsage */);

        // shortened string should not have percentage
        assertThat(info).isEqualTo("More than 2 days remaining");
        // Add percentage to string when provided
        assertThat(info2).isEqualTo("More than 2 days remaining (10%)");
    }
}
