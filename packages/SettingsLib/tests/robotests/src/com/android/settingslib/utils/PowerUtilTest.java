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

import android.app.AlarmManager;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

@RunWith(RobolectricTestRunner.class)
public class PowerUtilTest {
    private static final String BATTERY_RUN_OUT_PREFIX = "Battery may run out by";
    // matches a time (ex: '1:15 PM', '2 AM', '23:00')
    private static final String TIME_OF_DAY_REGEX = " (\\d)+:?(\\d)* ((AM)*)|((PM)*)";
    // matches a percentage with parenthesis (ex: '(10%)')
    private static final String PERCENTAGE_REGEX = " \\(\\d?\\d%\\)";

    private Context mContext;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mContext = spy(ApplicationProvider.getApplicationContext());
    }

    @Test
    public void getBatteryTipStringFormatted_moreThanOneDay_usesCorrectString() {
        var threeDayMillis = Duration.ofDays(3).toMillis();

        String batteryTipString = PowerUtil.getBatteryTipStringFormatted(mContext, threeDayMillis);

        assertThat(batteryTipString).isEqualTo("More than 3 days left");
    }

    @Test
    public void getBatteryTipStringFormatted_lessThanOneDay_usesCorrectString() {
        var drainTimeMs = Duration.ofMinutes(17).toMillis();

        String batteryTipString = PowerUtil.getBatteryTipStringFormatted(mContext, drainTimeMs);

        // ex: Battery may run out by 1:15 PM
        assertThat(batteryTipString)
                .containsMatch(Pattern.compile(BATTERY_RUN_OUT_PREFIX + TIME_OF_DAY_REGEX));
    }

    @Test
    public void roundTimeToNearestThreshold_roundsCorrectly() {
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

    @Test
    public void getTargetTimeShortString_lessThan15Minutes_returnsTimeShortStringWithoutRounded() {
        mContext.getSystemService(AlarmManager.class).setTimeZone("UTC");
        mContext.getResources().getConfiguration().setLocale(Locale.US);
        var currentTimeMs = Instant.parse("2024-06-06T15:00:00Z").toEpochMilli();
        var remainingTimeMs = Duration.ofMinutes(15).toMillis() - 1;

        var actualTimeString =
                PowerUtil.getTargetTimeShortString(mContext, remainingTimeMs, currentTimeMs);

        // due to timezone issue in test case, focus on rounded minutes, remove hours part.
        assertThat(actualTimeString).endsWith("14 PM");
    }

    @Test
    public void getTargetTimeShortString_moreThan15Minutes_returnsTimeShortStringWithRounded() {
        mContext.getSystemService(AlarmManager.class).setTimeZone("UTC");
        mContext.getResources().getConfiguration().setLocale(Locale.US);
        var currentTimeMs = Instant.parse("2024-06-06T15:00:00Z").toEpochMilli();
        var remainingTimeMs = Duration.ofMinutes(15).toMillis() + 1;

        var actualTimeString =
                PowerUtil.getTargetTimeShortString(mContext, remainingTimeMs, currentTimeMs);

        // due to timezone issue in test case, focus on rounded minutes, remove hours part.
        assertThat(actualTimeString).endsWith("30 PM");

    }
}
