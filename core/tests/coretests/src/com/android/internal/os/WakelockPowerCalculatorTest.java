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
import android.os.BatteryStats;
import android.os.Process;
import android.os.UidBatteryConsumer;
import android.os.WorkSource;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class WakelockPowerCalculatorTest {
    private static final double PRECISION = 0.00001;

    private static final int APP_UID = Process.FIRST_APPLICATION_UID + 42;
    private static final int APP_PID = 3145;

    @Rule
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule()
            .setAveragePower(PowerProfile.POWER_CPU_IDLE, 360.0);

    @Test
    public void testTimerBasedModel() {
        mStatsRule.getUidStats(Process.ROOT_UID);

        BatteryStatsImpl batteryStats = mStatsRule.getBatteryStats();

        batteryStats.noteStartWakeFromSourceLocked(new WorkSource(APP_UID), APP_PID, "awake", "",
                BatteryStats.WAKE_TYPE_PARTIAL, true, 1000, 1000);
        batteryStats.noteStopWakeFromSourceLocked(new WorkSource(APP_UID), APP_PID, "awake", "",
                BatteryStats.WAKE_TYPE_PARTIAL, 2000, 2000);

        mStatsRule.setTime(10_000_000, 6_000_000);

        WakelockPowerCalculator calculator =
                new WakelockPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(calculator);

        UidBatteryConsumer consumer = mStatsRule.getUidBatteryConsumer(APP_UID);
        assertThat(consumer.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_WAKELOCK))
                .isEqualTo(1000);
        assertThat(consumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_WAKELOCK))
                .isWithin(PRECISION).of(0.1);

        UidBatteryConsumer osConsumer = mStatsRule.getUidBatteryConsumer(Process.ROOT_UID);
        assertThat(osConsumer.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_WAKELOCK))
                .isEqualTo(5000);
        assertThat(osConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_WAKELOCK))
                .isWithin(PRECISION).of(0.5);

        BatteryConsumer deviceConsumer = mStatsRule.getDeviceBatteryConsumer();
        assertThat(deviceConsumer.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_WAKELOCK))
                .isEqualTo(6000);
        assertThat(deviceConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_WAKELOCK))
                .isWithin(PRECISION).of(0.6);

        BatteryConsumer appConsumer = mStatsRule.getAppsBatteryConsumer();
        assertThat(appConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_WAKELOCK))
                .isWithin(PRECISION).of(0.1);
    }
}
