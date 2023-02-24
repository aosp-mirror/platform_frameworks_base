/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static androidx.test.InstrumentationRegistry.getContext;

import static org.junit.Assert.fail;

import android.os.BatteryConsumer;
import android.os.BatteryStatsManager;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.UidBatteryConsumer;

import org.junit.Test;

/**
 * Test BatteryStatsManager and CellularBatteryStats to ensure that valid data is being reported
 * and that invalid data is not reported.
 */
public class BatteryStatsManagerTest {

    @Test
    public void testBatteryUsageStatsDataConsistency() {
        BatteryStatsManager bsm = getContext().getSystemService(BatteryStatsManager.class);
        BatteryUsageStats stats = bsm.getBatteryUsageStats(
                new BatteryUsageStatsQuery.Builder().setMaxStatsAgeMs(
                        0).includeProcessStateData().build());
        final int[] components =
                {BatteryConsumer.POWER_COMPONENT_CPU,
                        BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO,
                        BatteryConsumer.POWER_COMPONENT_WIFI,
                        BatteryConsumer.POWER_COMPONENT_BLUETOOTH};
        final int[] states =
                {BatteryConsumer.PROCESS_STATE_FOREGROUND,
                        BatteryConsumer.PROCESS_STATE_BACKGROUND,
                        BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE,
                        BatteryConsumer.PROCESS_STATE_CACHED};
        for (UidBatteryConsumer ubc : stats.getUidBatteryConsumers()) {
            for (int component : components) {
                double consumedPower = ubc.getConsumedPower(ubc.getKey(component));
                double sumStates = 0;
                for (int state : states) {
                    sumStates += ubc.getConsumedPower(ubc.getKey(component, state));
                }
                if (sumStates > consumedPower + 0.1) {
                    fail("Sum of states exceeds total. UID = " + ubc.getUid() + " "
                            + BatteryConsumer.powerComponentIdToString(component)
                            + " total = " + consumedPower + " states = " + sumStates);
                }
            }
        }
    }
}
