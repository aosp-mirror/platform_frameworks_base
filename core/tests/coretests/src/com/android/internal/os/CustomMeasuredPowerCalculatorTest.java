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

package com.android.internal.os;

import static com.google.common.truth.Truth.assertThat;

import android.os.BatteryConsumer;
import android.os.Process;
import android.os.SystemBatteryConsumer;
import android.os.UidBatteryConsumer;
import android.util.SparseLongArray;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class CustomMeasuredPowerCalculatorTest {
    private static final double PRECISION = 0.00001;

    private static final int APP_UID = Process.FIRST_APPLICATION_UID + 42;

    @Rule
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule();

    @Test
    @SkipPresubmit("b/180015146")
    public void testMeasuredEnergyCopiedIntoBatteryConsumers() {
        final BatteryStatsImpl batteryStats = mStatsRule.getBatteryStats();
        SparseLongArray uidEnergies = new SparseLongArray();
        uidEnergies.put(APP_UID, 30_000_000);
        batteryStats.updateCustomMeasuredEnergyStatsLocked(0, 100_000_000, uidEnergies);

        uidEnergies.put(APP_UID, 120_000_000);
        batteryStats.updateCustomMeasuredEnergyStatsLocked(1, 200_000_000, uidEnergies);

        CustomMeasuredPowerCalculator calculator =
                new CustomMeasuredPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(calculator);

        UidBatteryConsumer uid = mStatsRule.getUidBatteryConsumer(APP_UID);
        assertThat(uid.getConsumedPowerForCustomComponent(
                BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID))
                .isWithin(PRECISION).of(2.252252);
        assertThat(uid.getConsumedPowerForCustomComponent(
                BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID + 1))
                .isWithin(PRECISION).of(9.009009);

        SystemBatteryConsumer systemConsumer = mStatsRule.getSystemBatteryConsumer(
                SystemBatteryConsumer.DRAIN_TYPE_CUSTOM);
        assertThat(systemConsumer.getConsumedPowerForCustomComponent(
                BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID))
                .isWithin(PRECISION).of(7.5075075);
        assertThat(systemConsumer.getConsumedPowerForCustomComponent(
                BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID + 1))
                .isWithin(PRECISION).of(15.015015);
    }
}
