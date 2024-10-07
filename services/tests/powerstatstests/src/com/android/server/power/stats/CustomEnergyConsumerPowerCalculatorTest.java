/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import android.os.BatteryConsumer;
import android.os.Process;
import android.os.UidBatteryConsumer;
import android.platform.test.ravenwood.RavenwoodRule;
import android.util.SparseLongArray;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
@SuppressWarnings("GuardedBy")
public class CustomEnergyConsumerPowerCalculatorTest {
    @Rule(order = 0)
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true)
            .build();

    private static final double PRECISION = 0.00001;

    private static final int APP_UID = Process.FIRST_APPLICATION_UID + 42;

    @Rule(order = 1)
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule()
            .initMeasuredEnergyStatsLocked(new String[]{"CUSTOM_COMPONENT1", "CUSTOM_COMPONENT2"});

    @Test
    public void testMeasuredEnergyCopiedIntoBatteryConsumers() {
        final BatteryStatsImpl batteryStats = mStatsRule.getBatteryStats();

        // For side-effect of creating a BatteryStats.Uid
        batteryStats.getUidStatsLocked(APP_UID);

        SparseLongArray uidEnergies = new SparseLongArray();
        uidEnergies.put(APP_UID, 30_000_000);
        batteryStats.updateCustomEnergyConsumerStatsLocked(0, 100_000_000, uidEnergies);

        uidEnergies.put(APP_UID, 120_000_000);
        batteryStats.updateCustomEnergyConsumerStatsLocked(1, 200_000_000, uidEnergies);

        CustomEnergyConsumerPowerCalculator calculator =
                new CustomEnergyConsumerPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(calculator);

        UidBatteryConsumer uid = mStatsRule.getUidBatteryConsumer(APP_UID);
        assertThat(uid.getConsumedPower(
                BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID))
                .isWithin(PRECISION).of(8.333333);
        assertThat(uid.getConsumedPower(
                BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID + 1))
                .isWithin(PRECISION).of(33.33333);

        final BatteryConsumer deviceBatteryConsumer = mStatsRule.getDeviceBatteryConsumer();
        assertThat(deviceBatteryConsumer.getConsumedPower(
                BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID))
                .isWithin(PRECISION).of(27.77777);
        assertThat(deviceBatteryConsumer.getConsumedPower(
                BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID + 1))
                .isWithin(PRECISION).of(55.55555);

        final BatteryConsumer appsBatteryConsumer = mStatsRule.getDeviceBatteryConsumer();
        assertThat(appsBatteryConsumer.getConsumedPower(
                BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID))
                .isWithin(PRECISION).of(27.77777);
        assertThat(appsBatteryConsumer.getConsumedPower(
                BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID + 1))
                .isWithin(PRECISION).of(55.55555);
    }
}
