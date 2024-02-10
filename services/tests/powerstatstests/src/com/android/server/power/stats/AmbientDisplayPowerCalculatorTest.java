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

package com.android.server.power.stats;

import static com.android.internal.os.PowerProfile.POWER_GROUP_DISPLAY_AMBIENT;

import static com.google.common.truth.Truth.assertThat;

import android.os.BatteryConsumer;
import android.platform.test.ravenwood.RavenwoodRule;
import android.view.Display;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
@SuppressWarnings("GuardedBy")
public class AmbientDisplayPowerCalculatorTest {
    @Rule(order = 0)
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true)
            .build();

    private static final double PRECISION = 0.00001;
    private static final long MINUTE_IN_MS = 60 * 1000;

    @Rule(order = 1)
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule()
            .setAveragePowerForOrdinal(POWER_GROUP_DISPLAY_AMBIENT, 0, 10.0)
            .setNumDisplays(1);

    @Test
    public void testMeasuredEnergyBasedModel() {
        mStatsRule.initMeasuredEnergyStatsLocked();
        BatteryStatsImpl stats = mStatsRule.getBatteryStats();

        stats.updateDisplayEnergyConsumerStatsLocked(new long[]{300_000_000},
                new int[]{Display.STATE_ON}, 0);

        stats.noteScreenStateLocked(0, Display.STATE_DOZE, 30 * MINUTE_IN_MS, 30 * MINUTE_IN_MS,
                30 * MINUTE_IN_MS);

        stats.updateDisplayEnergyConsumerStatsLocked(new long[]{200_000_000},
                new int[]{Display.STATE_DOZE}, 30 * MINUTE_IN_MS);

        stats.noteScreenStateLocked(0, Display.STATE_OFF, 120 * MINUTE_IN_MS, 120 * MINUTE_IN_MS,
                120 * MINUTE_IN_MS);

        stats.updateDisplayEnergyConsumerStatsLocked(new long[]{100_000_000},
                new int[]{Display.STATE_OFF}, 120 * MINUTE_IN_MS);

        AmbientDisplayPowerCalculator calculator =
                new AmbientDisplayPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(calculator);

        BatteryConsumer consumer = mStatsRule.getDeviceBatteryConsumer();
        assertThat(consumer.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_AMBIENT_DISPLAY))
                .isEqualTo(90 * MINUTE_IN_MS);
        // 100,000,00 uC / 1000 (micro-/milli-) / 360 (seconds/hour) = 27.777778 mAh
        assertThat(consumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_AMBIENT_DISPLAY))
                .isWithin(PRECISION).of(27.777778);
        assertThat(consumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_AMBIENT_DISPLAY))
                .isEqualTo(BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION);
    }

    @Test
    public void testMeasuredEnergyBasedModel_multiDisplay() {
        mStatsRule.initMeasuredEnergyStatsLocked()
                .setAveragePowerForOrdinal(POWER_GROUP_DISPLAY_AMBIENT, 1, 20.0)
                .setNumDisplays(2);
        BatteryStatsImpl stats = mStatsRule.getBatteryStats();


        final int[] screenStates = new int[] {Display.STATE_OFF, Display.STATE_OFF};

        stats.noteScreenStateLocked(0, screenStates[0], 0, 0, 0);
        stats.noteScreenStateLocked(1, screenStates[1], 0, 0, 0);
        stats.updateDisplayEnergyConsumerStatsLocked(new long[]{300, 400}, screenStates, 0);

        // Switch display0 to doze
        screenStates[0] = Display.STATE_DOZE;
        stats.noteScreenStateLocked(0, screenStates[0], 30 * MINUTE_IN_MS, 30 * MINUTE_IN_MS,
                30 * MINUTE_IN_MS);
        stats.updateDisplayEnergyConsumerStatsLocked(new long[]{200, 300},
                screenStates, 30 * MINUTE_IN_MS);

        // Switch display1 to doze
        screenStates[1] = Display.STATE_DOZE;
        stats.noteScreenStateLocked(1, Display.STATE_DOZE, 90 * MINUTE_IN_MS, 90 * MINUTE_IN_MS,
                90 * MINUTE_IN_MS);
        // 100,000,000 uC should be attributed to display 0 doze here.
        stats.updateDisplayEnergyConsumerStatsLocked(new long[]{100_000_000, 700_000_000},
                screenStates, 90 * MINUTE_IN_MS);

        // Switch display0 to off
        screenStates[0] = Display.STATE_OFF;
        stats.noteScreenStateLocked(0, screenStates[0], 120 * MINUTE_IN_MS, 120 * MINUTE_IN_MS,
                120 * MINUTE_IN_MS);
        // 40,000,000 and 70,000,000 uC should be attributed to display 0 and 1 doze here.
        stats.updateDisplayEnergyConsumerStatsLocked(new long[]{40_000_000, 70_000_000},
                screenStates, 120 * MINUTE_IN_MS);

        // Switch display1 to off
        screenStates[1] = Display.STATE_OFF;
        stats.noteScreenStateLocked(1, screenStates[1], 150 * MINUTE_IN_MS, 150 * MINUTE_IN_MS,
                150 * MINUTE_IN_MS);
        stats.updateDisplayEnergyConsumerStatsLocked(new long[]{100, 90_000_000}, screenStates,
                150 * MINUTE_IN_MS);
        // 90,000,000 uC should be attributed to display 1 doze here.

        AmbientDisplayPowerCalculator calculator =
                new AmbientDisplayPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(calculator);

        BatteryConsumer consumer = mStatsRule.getDeviceBatteryConsumer();
        assertThat(consumer.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_AMBIENT_DISPLAY))
                .isEqualTo(120 * MINUTE_IN_MS);
        // 100,000,000 + 40,000,000 + 70,000,000 + 90,000,000 uC / 1000 (micro-/milli-) / 3600
        // (seconds/hour) = 27.777778 mAh
        assertThat(consumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_AMBIENT_DISPLAY))
                .isWithin(PRECISION).of(83.33333);
        assertThat(consumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_AMBIENT_DISPLAY))
                .isEqualTo(BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION);
    }

    @Test
    public void testPowerProfileBasedModel() {
        BatteryStatsImpl stats = mStatsRule.getBatteryStats();

        stats.noteScreenStateLocked(0, Display.STATE_DOZE, 30 * MINUTE_IN_MS, 30 * MINUTE_IN_MS,
                30 * MINUTE_IN_MS);
        stats.noteScreenStateLocked(0, Display.STATE_OFF, 120 * MINUTE_IN_MS, 120 * MINUTE_IN_MS,
                120 * MINUTE_IN_MS);

        AmbientDisplayPowerCalculator calculator =
                new AmbientDisplayPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(BatteryUsageStatsRule.POWER_PROFILE_MODEL_ONLY, calculator);

        BatteryConsumer consumer = mStatsRule.getDeviceBatteryConsumer();
        assertThat(consumer.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_AMBIENT_DISPLAY))
                .isEqualTo(90 * MINUTE_IN_MS);
        assertThat(consumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_AMBIENT_DISPLAY))
                .isWithin(PRECISION).of(15.0);
        assertThat(consumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_AMBIENT_DISPLAY))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);
    }

    @Test
    public void testPowerProfileBasedModel_multiDisplay() {
        mStatsRule.setAveragePowerForOrdinal(POWER_GROUP_DISPLAY_AMBIENT, 1, 20.0)
                .setNumDisplays(2);

        BatteryStatsImpl stats = mStatsRule.getBatteryStats();

        stats.noteScreenStateLocked(1, Display.STATE_OFF, 0, 0, 0);
        stats.noteScreenStateLocked(0, Display.STATE_DOZE, 30 * MINUTE_IN_MS, 30 * MINUTE_IN_MS,
                30 * MINUTE_IN_MS);
        stats.noteScreenStateLocked(1, Display.STATE_DOZE, 90 * MINUTE_IN_MS, 90 * MINUTE_IN_MS,
                90 * MINUTE_IN_MS);
        stats.noteScreenStateLocked(0, Display.STATE_OFF, 120 * MINUTE_IN_MS, 120 * MINUTE_IN_MS,
                120 * MINUTE_IN_MS);
        stats.noteScreenStateLocked(1, Display.STATE_OFF, 150 * MINUTE_IN_MS, 150 * MINUTE_IN_MS,
                150 * MINUTE_IN_MS);

        AmbientDisplayPowerCalculator calculator =
                new AmbientDisplayPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(BatteryUsageStatsRule.POWER_PROFILE_MODEL_ONLY, calculator);

        BatteryConsumer consumer = mStatsRule.getDeviceBatteryConsumer();
        // Duration should only be the union of
        assertThat(consumer.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_AMBIENT_DISPLAY))
                .isEqualTo(120 * MINUTE_IN_MS);
        assertThat(consumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_AMBIENT_DISPLAY))
                .isWithin(PRECISION).of(35.0);
        assertThat(consumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_AMBIENT_DISPLAY))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);
    }
}
