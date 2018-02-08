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
import com.android.settingslib.TestConfig;
import com.android.settingslib.utils.PowerUtil;
import java.time.Duration;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(SettingsLibRobolectricTestRunner.class)
@Config(manifest = TestConfig.MANIFEST_PATH, sdk = TestConfig.SDK_VERSION)
public class PowerUtilTest {
    public static final String TEST_BATTERY_LEVEL_10 = "10%";
    public static final String FIFTEEN_MIN_FORMATTED = "15m";
    public static final long SEVENTEEN_MIN_MILLIS = Duration.ofMinutes(17).toMillis();
    public static final long FIVE_MINUTES_MILLIS = Duration.ofMinutes(5).toMillis();
    public static final long TEN_MINUTES_MILLIS = Duration.ofMinutes(10).toMillis();
    public static final long TWO_DAYS_MILLIS = Duration.ofDays(2).toMillis();
    public static final String ONE_DAY_FORMATTED = "1 day";

    private Context mContext;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mContext = spy(RuntimeEnvironment.application);
    }

    @Test
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
        assertThat(info).isEqualTo(mContext.getString(
                R.string.power_discharging_duration_enhanced,
                TEST_BATTERY_LEVEL_10,
                FIFTEEN_MIN_FORMATTED));
        // shortened string should not have extra text
        assertThat(info2).isEqualTo(mContext.getString(
                R.string.power_discharging_duration,
                TEST_BATTERY_LEVEL_10,
                FIFTEEN_MIN_FORMATTED));
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

        // We only add special mention for the long string
        assertThat(info).isEqualTo(mContext.getString(
                R.string.power_remaining_duration_only_enhanced,
                FIFTEEN_MIN_FORMATTED));
        // shortened string should not have extra text
        assertThat(info2).isEqualTo(mContext.getString(
                R.string.power_remaining_duration_only,
                FIFTEEN_MIN_FORMATTED));
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
        assertThat(info).isEqualTo(mContext.getString(
                R.string.power_remaining_duration_shutdown_imminent,
                TEST_BATTERY_LEVEL_10));
        // shortened string should not have percentage
        assertThat(info2).isEqualTo(mContext.getString(
                R.string.power_remaining_duration_only_shutdown_imminent));
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
        assertThat(info).isEqualTo(mContext.getString(
                R.string.power_remaining_less_than_duration_only,
                FIFTEEN_MIN_FORMATTED));
        // Add percentage to string when provided
        assertThat(info2).isEqualTo(mContext.getString(
                R.string.power_remaining_less_than_duration,
                TEST_BATTERY_LEVEL_10,
                FIFTEEN_MIN_FORMATTED));
    }

    @Test
    public void testGetBatteryRemainingStringFormatted_moreThanOneDay_usesCorrectString() {
        String info = PowerUtil.getBatteryRemainingStringFormatted(mContext,
                TWO_DAYS_MILLIS,
                null /* percentageString */,
                true /* basedOnUsage */);
        String info2 = PowerUtil.getBatteryRemainingStringFormatted(mContext,
                TWO_DAYS_MILLIS,
                TEST_BATTERY_LEVEL_10 /* percentageString */,
                true /* basedOnUsage */);

        // shortened string should not have percentage
        assertThat(info).isEqualTo(mContext.getString(
                R.string.power_remaining_only_more_than_subtext,
                ONE_DAY_FORMATTED));
        // Add percentage to string when provided
        assertThat(info2).isEqualTo(mContext.getString(
                R.string.power_remaining_more_than_subtext,
                TEST_BATTERY_LEVEL_10,
                ONE_DAY_FORMATTED));
    }
}
