/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.os;

import static com.google.common.truth.Truth.assertThat;

import android.os.BatteryConsumer;
import android.os.SystemBatteryConsumer;
import android.view.Display;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class ScreenPowerCalculatorTest {
    private static final double PRECISION = 0.00001;

    @Rule
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule()
            .setAveragePower(PowerProfile.POWER_SCREEN_ON, 360.0)
            .setAveragePower(PowerProfile.POWER_SCREEN_FULL, 3600.0);

    @Test
    public void testTimerBasedModel() {
        BatteryStatsImpl stats = mStatsRule.getBatteryStats();

        stats.noteScreenStateLocked(Display.STATE_ON, 1000, 1000, 1000);
        stats.noteScreenBrightnessLocked(100, 1000, 1000);
        stats.noteScreenBrightnessLocked(200, 2000, 2000);
        stats.noteScreenStateLocked(Display.STATE_OFF, 3000, 3000, 3000);

        ScreenPowerCalculator calculator =
                new ScreenPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(calculator);

        SystemBatteryConsumer consumer =
                mStatsRule.getSystemBatteryConsumer(SystemBatteryConsumer.DRAIN_TYPE_SCREEN);
        assertThat(consumer.getUsageDurationMillis(BatteryConsumer.TIME_COMPONENT_USAGE))
                .isEqualTo(2000);
        assertThat(consumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_USAGE))
                .isWithin(PRECISION).of(1.2);
    }
}
