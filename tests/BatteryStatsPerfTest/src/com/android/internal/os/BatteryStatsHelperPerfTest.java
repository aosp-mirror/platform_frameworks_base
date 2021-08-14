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
import android.os.BatteryStats;
import android.os.Bundle;
import android.os.UserHandle;
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
public class BatteryStatsHelperPerfTest {

    @Rule
    public final PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    /**
     * Measures the performance of {@link BatteryStatsHelper#getStats()}, which triggers
     * a battery stats sync on every iteration.
     */
    @Test
    public void testGetStats_forceUpdate() {
        final Context context = InstrumentationRegistry.getContext();
        final BatteryStatsHelper statsHelper = new BatteryStatsHelper(context,
                true /* collectBatteryBroadcast */);
        statsHelper.create((Bundle) null);
        statsHelper.refreshStats(BatteryStats.STATS_SINCE_CHARGED, UserHandle.myUserId());

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            statsHelper.clearStats();
            state.resumeTiming();

            statsHelper.getStats();

            assertThat(statsHelper.getUsageList()).isNotEmpty();
        }
    }

    /**
     * Measures performance of the {@link BatteryStatsHelper#getStats(boolean)}, which does
     * not trigger a sync and just returns current values.
     */
    @Test
    public void testGetStats_cached() {
        final Context context = InstrumentationRegistry.getContext();
        final BatteryStatsHelper statsHelper = new BatteryStatsHelper(context,
                true /* collectBatteryBroadcast */);
        statsHelper.create((Bundle) null);
        statsHelper.refreshStats(BatteryStats.STATS_SINCE_CHARGED, UserHandle.myUserId());

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            statsHelper.clearStats();
            state.resumeTiming();

            statsHelper.getStats(false /* forceUpdate */);

            assertThat(statsHelper.getUsageList()).isNotEmpty();
        }
    }

    @Test
    public void testPowerCalculation() {
        final Context context = InstrumentationRegistry.getContext();
        final BatteryStatsHelper statsHelper = new BatteryStatsHelper(context,
                true /* collectBatteryBroadcast */);
        statsHelper.create((Bundle) null);
        statsHelper.getStats();

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            // This will use the cached BatteryStatsObject
            statsHelper.refreshStats(BatteryStats.STATS_SINCE_CHARGED, UserHandle.myUserId());

            assertThat(statsHelper.getUsageList()).isNotEmpty();
        }
    }

    @Test
    public void testEndToEnd() {
        final Context context = InstrumentationRegistry.getContext();
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            final BatteryStatsHelper statsHelper = new BatteryStatsHelper(context,
                    true /* collectBatteryBroadcast */);
            statsHelper.create((Bundle) null);
            statsHelper.clearStats();
            statsHelper.refreshStats(BatteryStats.STATS_SINCE_CHARGED, UserHandle.myUserId());

            state.pauseTiming();

            List<BatterySipper> usageList = statsHelper.getUsageList();
            double power = 0;
            for (int i = 0; i < usageList.size(); i++) {
                BatterySipper sipper = usageList.get(i);
                power += sipper.sumPower();
            }

            assertThat(power).isGreaterThan(0.0);

            state.resumeTiming();
        }
    }
}
