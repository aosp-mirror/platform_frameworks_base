/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.BatteryConsumer;
import android.os.BatteryManager;
import android.os.BatteryUsageStats;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.MonotonicClock;
import com.android.internal.os.PowerProfile;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class PowerStatsSchedulerTest {
    private PowerStatsStore mPowerStatsStore;
    private Handler mHandler;
    private MockClock mClock = new MockClock();
    private MonotonicClock mMonotonicClock = new MonotonicClock(0, mClock);
    private MockBatteryStatsImpl mBatteryStats;
    private BatteryUsageStatsProvider mBatteryUsageStatsProvider;
    private PowerStatsScheduler mPowerStatsScheduler;
    private PowerProfile mPowerProfile;

    @Before
    public void setup() {
        final Context context = InstrumentationRegistry.getContext();

        mClock.currentTime = 1234567;
        mClock.realtime = 7654321;

        HandlerThread bgThread = new HandlerThread("bg thread");
        bgThread.start();
        File systemDir = context.getCacheDir();
        mHandler = new Handler(bgThread.getLooper());
        mPowerStatsStore = new PowerStatsStore(systemDir, mHandler);
        mPowerProfile = mock(PowerProfile.class);
        when(mPowerProfile.getAveragePower(PowerProfile.POWER_FLASHLIGHT)).thenReturn(1000000.0);
        mBatteryStats = new MockBatteryStatsImpl(mClock).setPowerProfile(mPowerProfile);
        mBatteryUsageStatsProvider = new BatteryUsageStatsProvider(context, mBatteryStats);
        mPowerStatsScheduler = new PowerStatsScheduler(mPowerStatsStore, mMonotonicClock, mHandler,
                mBatteryStats, mBatteryUsageStatsProvider);
    }

    @Test
    public void storeBatteryUsageStatsOnReset() {
        mBatteryStats.forceRecordAllHistory();
        synchronized (mBatteryStats) {
            mBatteryStats.setOnBatteryLocked(mClock.realtime, mClock.uptime, true,
                    BatteryManager.BATTERY_STATUS_DISCHARGING, 50, 0);
        }

        mPowerStatsStore.reset();

        assertThat(mPowerStatsStore.getTableOfContents()).isEmpty();

        mPowerStatsScheduler.start();

        synchronized (mBatteryStats) {
            mBatteryStats.noteFlashlightOnLocked(42, mClock.realtime, mClock.uptime);
        }

        mClock.realtime += 60000;
        mClock.currentTime += 60000;

        synchronized (mBatteryStats) {
            mBatteryStats.noteFlashlightOffLocked(42, mClock.realtime, mClock.uptime);
        }

        mClock.realtime += 60000;
        mClock.currentTime += 60000;

        // Battery stats reset should have the side-effect of saving accumulated battery usage stats
        synchronized (mBatteryStats) {
            mBatteryStats.resetAllStatsAndHistoryLocked(BatteryStatsImpl.RESET_REASON_ADB_COMMAND);
        }

        // Await completion
        ConditionVariable done = new ConditionVariable();
        mHandler.post(done::open);
        done.block();

        List<PowerStatsSpan.Metadata> contents = mPowerStatsStore.getTableOfContents();
        assertThat(contents).hasSize(1);

        PowerStatsSpan.Metadata metadata = contents.get(0);

        PowerStatsSpan span = mPowerStatsStore.loadPowerStatsSpan(metadata.getId(),
                BatteryUsageStatsSection.TYPE);
        assertThat(span).isNotNull();

        List<PowerStatsSpan.TimeFrame> timeFrames = span.getMetadata().getTimeFrames();
        assertThat(timeFrames).hasSize(1);
        assertThat(timeFrames.get(0).startTime).isEqualTo(1234567);
        assertThat(timeFrames.get(0).duration).isEqualTo(120000);

        List<PowerStatsSpan.Section> sections = span.getSections();
        assertThat(sections).hasSize(1);

        PowerStatsSpan.Section section = sections.get(0);
        assertThat(section.getType()).isEqualTo(BatteryUsageStatsSection.TYPE);
        BatteryUsageStats bus = ((BatteryUsageStatsSection) section).getBatteryUsageStats();
        assertThat(bus.getAggregateBatteryConsumer(
                        BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE)
                .getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_FLASHLIGHT))
                .isEqualTo(60000);
    }
}
