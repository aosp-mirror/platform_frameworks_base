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

import android.content.Context;
import android.os.BatteryStatsManager;
import android.os.BatteryUsageStats;
import android.os.UidBatteryConsumer;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class BatteryUsageStatsPerfTest {

    @Rule
    public final PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    /**
     * Measures the performance of {@link BatteryStatsManager#getBatteryUsageStats()},
     * which triggers a battery stats sync on every iteration.
     */
    @Test
    public void testGetBatteryUsageStats() {
        final Context context = InstrumentationRegistry.getContext();
        final BatteryStatsManager batteryStatsManager =
                context.getSystemService(BatteryStatsManager.class);

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            BatteryUsageStats batteryUsageStats = batteryStatsManager.getBatteryUsageStats();

            state.pauseTiming();

            List<UidBatteryConsumer> uidBatteryConsumers =
                    batteryUsageStats.getUidBatteryConsumers();
            double power = 0;
            for (int i = 0; i < uidBatteryConsumers.size(); i++) {
                UidBatteryConsumer uidBatteryConsumer = uidBatteryConsumers.get(i);
                power += uidBatteryConsumer.getConsumedPower();
            }

            assertThat(power).isGreaterThan(0.0);

            state.resumeTiming();
        }
    }
}
