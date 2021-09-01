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

import static org.mockito.Mockito.mock;

import android.content.Context;
import android.os.BatteryManager;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public class BatteryUsageStatsStoreTest {
    private static final long MAX_BATTERY_STATS_SNAPSHOT_STORAGE_BYTES = 2 * 1024;

    private final MockClocks mMockClocks = new MockClocks();
    private MockBatteryStatsImpl mBatteryStats;
    private BatteryUsageStatsStore mBatteryUsageStatsStore;
    private BatteryUsageStatsProvider mBatteryUsageStatsProvider;
    private File mStoreDirectory;

    @Before
    public void setup() {
        mMockClocks.currentTime = 123;
        mBatteryStats = new MockBatteryStatsImpl(mMockClocks);
        mBatteryStats.setNoAutoReset(true);
        mBatteryStats.setPowerProfile(mock(PowerProfile.class));
        mBatteryStats.onSystemReady();

        Context context = InstrumentationRegistry.getContext();

        mStoreDirectory = new File(context.getCacheDir(), "BatteryUsageStatsStoreTest");
        clearDirectory(mStoreDirectory);

        mBatteryUsageStatsStore = new BatteryUsageStatsStore(context, mBatteryStats,
                mStoreDirectory, new TestHandler(), MAX_BATTERY_STATS_SNAPSHOT_STORAGE_BYTES);
        mBatteryUsageStatsStore.onSystemReady();

        mBatteryUsageStatsProvider = new BatteryUsageStatsProvider(context, mBatteryStats);
    }

    @Test
    public void testStoreSnapshot() {
        mMockClocks.currentTime = 1_600_000;

        prepareBatteryStats();
        mBatteryStats.resetAllStatsCmdLocked();

        final long[] timestamps = mBatteryUsageStatsStore.listBatteryUsageStatsTimestamps();
        assertThat(timestamps).hasLength(1);
        assertThat(timestamps[0]).isEqualTo(1_600_000);

        final BatteryUsageStats batteryUsageStats = mBatteryUsageStatsStore.loadBatteryUsageStats(
                1_600_000);
        assertThat(batteryUsageStats.getStatsStartTimestamp()).isEqualTo(123);
        assertThat(batteryUsageStats.getStatsEndTimestamp()).isEqualTo(1_600_000);
        assertThat(batteryUsageStats.getBatteryCapacity()).isEqualTo(4000);
        assertThat(batteryUsageStats.getDischargePercentage()).isEqualTo(5);
        assertThat(batteryUsageStats.getAggregateBatteryConsumer(
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE).getConsumedPower())
                .isEqualTo(600);  // (3_600_000 - 3_000_000) / 1000
    }

    @Test
    public void testGarbageCollectOldSnapshots() throws Exception {
        prepareBatteryStats();

        mMockClocks.realtime = 10_000_000;
        mMockClocks.uptime = 10_000_000;
        mMockClocks.currentTime = 10_000_000;

        final int snapshotFileSize = getSnapshotFileSize();
        final int numberOfSnapshots =
                (int) (MAX_BATTERY_STATS_SNAPSHOT_STORAGE_BYTES / snapshotFileSize);
        for (int i = 0; i < numberOfSnapshots + 2; i++) {
            mBatteryStats.resetAllStatsCmdLocked();

            mMockClocks.realtime += 10_000_000;
            mMockClocks.uptime += 10_000_000;
            mMockClocks.currentTime += 10_000_000;
            prepareBatteryStats();
        }

        final long[] timestamps = mBatteryUsageStatsStore.listBatteryUsageStatsTimestamps();
        Arrays.sort(timestamps);
        assertThat(timestamps).hasLength(numberOfSnapshots);
        // Two snapshots (10_000_000 and 20_000_000) should have been discarded
        assertThat(timestamps[0]).isEqualTo(30_000_000);
        assertThat(getDirectorySize(mStoreDirectory))
                .isAtMost(MAX_BATTERY_STATS_SNAPSHOT_STORAGE_BYTES);
    }

    @Test
    public void testSavingStatsdAtomPullTimestamp() {
        mBatteryUsageStatsStore.setLastBatteryUsageStatsBeforeResetAtomPullTimestamp(1234);
        assertThat(mBatteryUsageStatsStore.getLastBatteryUsageStatsBeforeResetAtomPullTimestamp())
                .isEqualTo(1234);
        mBatteryUsageStatsStore.setLastBatteryUsageStatsBeforeResetAtomPullTimestamp(5478);
        assertThat(mBatteryUsageStatsStore.getLastBatteryUsageStatsBeforeResetAtomPullTimestamp())
                .isEqualTo(5478);
    }

    private void prepareBatteryStats() {
        mBatteryStats.setBatteryStateLocked(BatteryManager.BATTERY_STATUS_DISCHARGING, 100,
                /* plugType */ 0, 90, 72, 3700, 3_600_000, 4_000_000, 0,
                mMockClocks.realtime, mMockClocks.uptime, mMockClocks.currentTime);
        mBatteryStats.setBatteryStateLocked(BatteryManager.BATTERY_STATUS_DISCHARGING, 100,
                /* plugType */ 0, 85, 72, 3700, 3_000_000, 4_000_000, 0,
                mMockClocks.realtime + 500_000, mMockClocks.uptime + 500_000,
                mMockClocks.currentTime + 500_000);
    }

    private void clearDirectory(File dir) {
        if (dir.exists()) {
            for (File child : dir.listFiles()) {
                if (child.isDirectory()) {
                    clearDirectory(child);
                }
                child.delete();
            }
        }
    }

    private long getDirectorySize(File dir) {
        long size = 0;
        if (dir.exists()) {
            for (File child : dir.listFiles()) {
                if (child.isDirectory()) {
                    size += getDirectorySize(child);
                } else {
                    size += child.length();
                }
            }
        }
        return size;
    }

    private int getSnapshotFileSize() throws IOException {
        BatteryUsageStats stats = mBatteryUsageStatsProvider.getBatteryUsageStats(
                new BatteryUsageStatsQuery.Builder()
                        .setMaxStatsAgeMs(0)
                        .includePowerModels()
                        .build());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TypedXmlSerializer serializer = Xml.newBinarySerializer();
        serializer.setOutput(out, StandardCharsets.UTF_8.name());
        serializer.startDocument(null, true);
        stats.writeXml(serializer);
        serializer.endDocument();
        return out.toByteArray().length;
    }

    private static class TestHandler extends Handler {
        TestHandler() {
            super(Looper.getMainLooper());
        }

        @Override
        public boolean sendMessageAtTime(Message msg, long uptimeMillis) {
            msg.getCallback().run();
            return true;
        }
    }
}
