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

import android.os.BatteryManager;
import android.os.BatteryUsageStats;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.PowerProfile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class BatteryChargeCalculatorTest {
    @Rule(order = 0)
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true)
            .build();

    private static final double PRECISION = 0.00001;

    @Rule(order = 1)
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule()
                    .setAveragePower(PowerProfile.POWER_BATTERY_CAPACITY, 4000.0);

    @Test
    public void testDischargeTotals() {
        // Nominal battery capacity should be ignored

        final BatteryStatsImpl batteryStats = mStatsRule.getBatteryStats();

        synchronized (batteryStats) {
            batteryStats.setBatteryStateLocked(BatteryManager.BATTERY_STATUS_DISCHARGING, 100,
                    /* plugType */ 0, 90, 72, 3700, 3_600_000, 4_000_000, 0,
                    1_000_000, 1_000_000, 1_000_000);
            batteryStats.setBatteryStateLocked(BatteryManager.BATTERY_STATUS_DISCHARGING, 100,
                    /* plugType */ 0, 85, 72, 3700, 3_000_000, 4_000_000, 0,
                    1_500_000, 1_500_000, 1_500_000);
            batteryStats.setBatteryStateLocked(BatteryManager.BATTERY_STATUS_DISCHARGING, 100,
                    /* plugType */ 0, 80, 72, 3700, 2_400_000, 4_000_000, 0,
                    2_000_000, 2_000_000, 2_000_000);
        }

        mStatsRule.setTime(5_000_000, 5_000_000);
        BatteryChargeCalculator calculator = new BatteryChargeCalculator();
        BatteryUsageStats batteryUsageStats = mStatsRule.apply(calculator);

        assertThat(batteryUsageStats.getConsumedPower())
                .isWithin(PRECISION).of(1200.0);        // 3,600 - 2,400
        assertThat(batteryUsageStats.getDischargePercentage()).isEqualTo(10);
        assertThat(batteryUsageStats.getDischargedPowerRange().getLower())
                .isWithin(PRECISION).of(360.0);
        assertThat(batteryUsageStats.getDischargedPowerRange().getUpper())
                .isWithin(PRECISION).of(400.0);
        // 5_000_000 (current time) - 1_000_000 (started discharging)
        assertThat(batteryUsageStats.getDischargeDurationMs()).isEqualTo(4_000_000);
        assertThat(batteryUsageStats.getBatteryTimeRemainingMs()).isEqualTo(8_000_000);
        assertThat(batteryUsageStats.getChargeTimeRemainingMs()).isEqualTo(-1);

        // Plug in
        synchronized (batteryStats) {
            batteryStats.setBatteryStateLocked(BatteryManager.BATTERY_STATUS_CHARGING, 100,
                    BatteryManager.BATTERY_PLUGGED_USB, 80, 72, 3700, 2_400_000, 4_000_000, 100,
                    4_000_000, 4_000_000, 4_000_000);
        }
        batteryUsageStats = mStatsRule.apply(calculator);

        assertThat(batteryUsageStats.getChargeTimeRemainingMs()).isEqualTo(100_000);
    }

    @Test
    public void testDischargeTotals_chargeUahUnavailable() {
        final BatteryStatsImpl batteryStats = mStatsRule.getBatteryStats();

        synchronized (batteryStats) {
            batteryStats.setBatteryStateLocked(BatteryManager.BATTERY_STATUS_DISCHARGING, 100,
                    /* plugType */ 0, 90, 72, 3700, 0, 0, 0,
                    1_000_000, 1_000_000, 1_000_000);
            batteryStats.setBatteryStateLocked(BatteryManager.BATTERY_STATUS_DISCHARGING, 100,
                    /* plugType */ 0, 85, 72, 3700, 0, 0, 0,
                    1_500_000, 1_500_000, 1_500_000);
            batteryStats.setBatteryStateLocked(BatteryManager.BATTERY_STATUS_DISCHARGING, 100,
                    /* plugType */ 0, 80, 72, 3700, 0, 0, 0,
                    2_000_000, 2_000_000, 2_000_000);
        }

        BatteryChargeCalculator calculator = new BatteryChargeCalculator();
        BatteryUsageStats batteryUsageStats = mStatsRule.apply(calculator);

        assertThat(batteryUsageStats.getConsumedPower())
                .isWithin(PRECISION).of(380.0);  // 9.5% of 4,000.
        assertThat(batteryUsageStats.getDischargePercentage()).isEqualTo(10);
        assertThat(batteryUsageStats.getDischargedPowerRange().getLower())
                .isWithin(PRECISION).of(360.0);  // 9% of 4,000
        assertThat(batteryUsageStats.getDischargedPowerRange().getUpper())
                .isWithin(PRECISION).of(400.0);  // 10% of 4,000
        assertThat(batteryUsageStats.getBatteryTimeRemainingMs()).isEqualTo(8_000_000);
        assertThat(batteryUsageStats.getChargeTimeRemainingMs()).isEqualTo(-1);
    }
}
