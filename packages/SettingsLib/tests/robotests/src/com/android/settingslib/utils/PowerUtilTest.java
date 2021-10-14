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

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.time.Duration;
import java.util.regex.Pattern;

@RunWith(RobolectricTestRunner.class)
public class PowerUtilTest {
    private static final String TEST_BATTERY_LEVEL_10 = "10%";
    private static final long TEN_SEC_MILLIS = Duration.ofSeconds(10).toMillis();
    private static final long SEVENTEEN_MIN_MILLIS = Duration.ofMinutes(17).toMillis();
    private static final long FIVE_MINUTES_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final long TEN_MINUTES_MILLIS = Duration.ofMinutes(10).toMillis();
    private static final long THREE_DAYS_MILLIS = Duration.ofDays(3).toMillis();
    private static final long TEN_HOURS_MILLIS = Duration.ofHours(10).toMillis();
    private static final long THIRTY_HOURS_MILLIS = Duration.ofHours(30).toMillis();
    private static final String NORMAL_CASE_EXPECTED_PREFIX = "Should last until about";
    private static final String ENHANCED_SUFFIX = " based on your usage";
    private static final String BATTERY_RUN_OUT_PREFIX = "Battery may run out by";
    // matches a time (ex: '1:15 PM', '2 AM', '23:00')
    private static final String TIME_OF_DAY_REGEX = " (\\d)+:?(\\d)* ((AM)*)|((PM)*)";
    // matches a percentage with parenthesis (ex: '(10%)')
    private static final String PERCENTAGE_REGEX = " \\(\\d?\\d%\\)";

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
        // ex: Will last about 1:15 PM based on your usage (10%)
        assertThat(info).containsMatch(Pattern.compile(
                NORMAL_CASE_EXPECTED_PREFIX
                        + TIME_OF_DAY_REGEX
                        + ENHANCED_SUFFIX
                        + PERCENTAGE_REGEX));
        // shortened string should not have extra text
        // ex: Will last about 1:15 PM (10%)
        assertThat(info2).containsMatch(Pattern.compile(
                NORMAL_CASE_EXPECTED_PREFIX
                        + TIME_OF_DAY_REGEX
                        + PERCENTAGE_REGEX));
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
        // ex: Will last about 1:15 PM based on your usage
        assertThat(info).containsMatch(Pattern.compile(
                NORMAL_CASE_EXPECTED_PREFIX
                        + TIME_OF_DAY_REGEX
                        + ENHANCED_SUFFIX
                        + "(" + PERCENTAGE_REGEX + "){0}")); // no percentage
        // shortened string should not have extra text
        // ex: Will last about 1:15 PM
        assertThat(info2).containsMatch(Pattern.compile(
                NORMAL_CASE_EXPECTED_PREFIX
                        + TIME_OF_DAY_REGEX
                        + "(" + PERCENTAGE_REGEX + "){0}")); // no percentage
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
        assertThat(info).isEqualTo("Phone may shut down soon (10%)");
        // shortened string should not have percentage
        assertThat(info2).isEqualTo("Phone may shut down soon");
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
        assertThat(info).isEqualTo("Less than 15 min left");
        // Add percentage to string when provided
        assertThat(info2).isEqualTo("Less than 15 min left (10%)");
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
        String info3 = PowerUtil.getBatteryRemainingStringFormatted(mContext,
                THIRTY_HOURS_MILLIS + TEN_MINUTES_MILLIS,
                null /* percentageString */,
                false /* basedOnUsage */);

        // We only add special mention for the long string
        assertThat(info).isEqualTo("About 1 day, 6 hr left based on your usage");
        // shortened string should not have extra text
        assertThat(info2).isEqualTo("About 1 day, 6 hr left (10%)");
        // present 2 time unit at most
        assertThat(info3).isEqualTo("About 1 day, 6 hr left");
    }

    @Test
    public void testGetBatteryRemainingStringFormatted_lessThanOneDay_usesCorrectString() {
        String info = PowerUtil.getBatteryRemainingStringFormatted(mContext,
                TEN_HOURS_MILLIS,
                null /* percentageString */,
                true /* basedOnUsage */);
        String info2 = PowerUtil.getBatteryRemainingStringFormatted(mContext,
                TEN_HOURS_MILLIS,
                TEST_BATTERY_LEVEL_10 /* percentageString */,
                false /* basedOnUsage */);
        String info3 = PowerUtil.getBatteryRemainingStringFormatted(mContext,
                TEN_HOURS_MILLIS + TEN_MINUTES_MILLIS + TEN_SEC_MILLIS,
                null /* percentageString */,
                false /* basedOnUsage */);

        // We only add special mention for the long string
        assertThat(info).isEqualTo("About 10 hr left based on your usage");
        // shortened string should not have extra text
        assertThat(info2).isEqualTo("About 10 hr left (10%)");
        // present 2 time unit at most
        assertThat(info3).isEqualTo("About 10 hr, 10 min left");
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
        assertThat(info).isEqualTo("More than 2 days left");
        // Add percentage to string when provided
        assertThat(info2).isEqualTo("More than 2 days left (10%)");
    }

    @Test
    public void getBatteryTipStringFormatted_moreThanOneDay_usesCorrectString() {
        String info = PowerUtil.getBatteryTipStringFormatted(mContext,
                THREE_DAYS_MILLIS);

        assertThat(info).isEqualTo("More than 3 days left");
    }

    @Test
    public void getBatteryTipStringFormatted_lessThanOneDay_usesCorrectString() {
        String info = PowerUtil.getBatteryTipStringFormatted(mContext,
                SEVENTEEN_MIN_MILLIS);

        // ex: Battery may run out by 1:15 PM
        assertThat(info).containsMatch(Pattern.compile(
                BATTERY_RUN_OUT_PREFIX + TIME_OF_DAY_REGEX));
    }

    @Test
    public void testRoundToNearestThreshold_roundsCorrectly() {
        // test some pretty normal values
        assertThat(PowerUtil.roundTimeToNearestThreshold(1200, 1000)).isEqualTo(1000);
        assertThat(PowerUtil.roundTimeToNearestThreshold(800, 1000)).isEqualTo(1000);
        assertThat(PowerUtil.roundTimeToNearestThreshold(1000, 1000)).isEqualTo(1000);

        // test the weird stuff
        assertThat(PowerUtil.roundTimeToNearestThreshold(80, -200)).isEqualTo(0);
        assertThat(PowerUtil.roundTimeToNearestThreshold(-150, 100)).isEqualTo(200);
        assertThat(PowerUtil.roundTimeToNearestThreshold(-120, 100)).isEqualTo(100);
        assertThat(PowerUtil.roundTimeToNearestThreshold(-200, -75)).isEqualTo(225);
    }
}
