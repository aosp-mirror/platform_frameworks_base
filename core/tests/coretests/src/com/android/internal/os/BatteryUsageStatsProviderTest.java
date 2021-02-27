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

import android.app.ActivityManager;
import android.content.Context;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.Process;
import android.os.UidBatteryConsumer;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class BatteryUsageStatsProviderTest {
    private static final int APP_UID = Process.FIRST_APPLICATION_UID + 42;
    private static final long MINUTE_IN_MS = 60 * 1000;

    @Rule
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule();

    @Test
    public void test_getBatteryUsageStats() {
        BatteryStatsImpl batteryStats = mStatsRule.getBatteryStats();

        batteryStats.noteActivityResumedLocked(APP_UID,
                10 * MINUTE_IN_MS, 10 * MINUTE_IN_MS);
        batteryStats.noteUidProcessStateLocked(APP_UID, ActivityManager.PROCESS_STATE_TOP,
                10 * MINUTE_IN_MS, 10 * MINUTE_IN_MS);
        batteryStats.noteActivityPausedLocked(APP_UID,
                30 * MINUTE_IN_MS, 30 * MINUTE_IN_MS);
        batteryStats.noteUidProcessStateLocked(APP_UID, ActivityManager.PROCESS_STATE_SERVICE,
                30 * MINUTE_IN_MS, 30 * MINUTE_IN_MS);
        batteryStats.noteUidProcessStateLocked(APP_UID, ActivityManager.PROCESS_STATE_CACHED_EMPTY,
                40 * MINUTE_IN_MS, 40 * MINUTE_IN_MS);

        Context context = InstrumentationRegistry.getContext();
        BatteryUsageStatsProvider provider = new BatteryUsageStatsProvider(context, batteryStats);

        final BatteryUsageStats batteryUsageStats =
                provider.getBatteryUsageStats(BatteryUsageStatsQuery.DEFAULT);

        final List<UidBatteryConsumer> uidBatteryConsumers =
                batteryUsageStats.getUidBatteryConsumers();
        final UidBatteryConsumer uidBatteryConsumer = uidBatteryConsumers.get(0);
        assertThat(uidBatteryConsumer.getTimeInStateMs(UidBatteryConsumer.STATE_FOREGROUND))
                .isEqualTo(20 * MINUTE_IN_MS);
        assertThat(uidBatteryConsumer.getTimeInStateMs(UidBatteryConsumer.STATE_BACKGROUND))
                .isEqualTo(10 * MINUTE_IN_MS);
    }
}
