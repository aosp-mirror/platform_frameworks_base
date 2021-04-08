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
public class AmbientDisplayPowerCalculatorTest {
    private static final double PRECISION = 0.00001;
    private static final long MINUTE_IN_MS = 60 * 1000;

    @Rule
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule()
            .setAveragePower(PowerProfile.POWER_AMBIENT_DISPLAY, 10.0);

    @Test
    public void testMeasuredEnergyBasedModel() {
        BatteryStatsImpl stats = mStatsRule.getBatteryStats();

        stats.updateDisplayMeasuredEnergyStatsLocked(300_000_000, Display.STATE_ON, 0);

        stats.noteScreenStateLocked(Display.STATE_DOZE, 30 * MINUTE_IN_MS, 30 * MINUTE_IN_MS,
                30 * MINUTE_IN_MS);

        stats.updateDisplayMeasuredEnergyStatsLocked(200_000_000, Display.STATE_DOZE,
                30 * MINUTE_IN_MS);

        stats.noteScreenStateLocked(Display.STATE_OFF, 120 * MINUTE_IN_MS, 120 * MINUTE_IN_MS,
                120 * MINUTE_IN_MS);

        stats.updateDisplayMeasuredEnergyStatsLocked(100_000_000, Display.STATE_OFF,
                120 * MINUTE_IN_MS);

        AmbientDisplayPowerCalculator calculator =
                new AmbientDisplayPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(calculator);

        SystemBatteryConsumer consumer =
                mStatsRule.getSystemBatteryConsumer(
                        SystemBatteryConsumer.DRAIN_TYPE_AMBIENT_DISPLAY);
        assertThat(consumer.getUsageDurationMillis(BatteryConsumer.TIME_COMPONENT_USAGE))
                .isEqualTo(90 * MINUTE_IN_MS);
        // 100,000,00 uC / 1000 (micro-/milli-) / 360 (seconds/hour) = 27.777778 mAh
        assertThat(consumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_USAGE))
                .isWithin(PRECISION).of(27.777778);
        assertThat(consumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_USAGE))
                .isEqualTo(BatteryConsumer.POWER_MODEL_MEASURED_ENERGY);
    }

    @Test
    public void testPowerProfileBasedModel() {
        BatteryStatsImpl stats = mStatsRule.getBatteryStats();

        stats.noteScreenStateLocked(Display.STATE_DOZE, 30 * MINUTE_IN_MS, 30 * MINUTE_IN_MS,
                30 * MINUTE_IN_MS);
        stats.noteScreenStateLocked(Display.STATE_OFF, 120 * MINUTE_IN_MS, 120 * MINUTE_IN_MS,
                120 * MINUTE_IN_MS);

        AmbientDisplayPowerCalculator calculator =
                new AmbientDisplayPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(BatteryUsageStatsRule.POWER_PROFILE_MODEL_ONLY, calculator);

        SystemBatteryConsumer consumer =
                mStatsRule.getSystemBatteryConsumer(
                        SystemBatteryConsumer.DRAIN_TYPE_AMBIENT_DISPLAY);
        assertThat(consumer.getUsageDurationMillis(BatteryConsumer.TIME_COMPONENT_USAGE))
                .isEqualTo(90 * MINUTE_IN_MS);
        assertThat(consumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_USAGE))
                .isWithin(PRECISION).of(15.0);
        assertThat(consumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_USAGE))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);
    }
}
