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
import android.os.BatteryStats;
import android.os.Process;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.BatteryStatsHistoryIterator;
import com.android.internal.os.MonotonicClock;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.Future;

@RunWith(AndroidJUnit4.class)
@SmallTest
@SuppressWarnings("GuardedBy")
public class BatteryStatsHistoryIteratorTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true)
            .build();

    private static final int APP_UID = Process.FIRST_APPLICATION_UID + 42;

    private final MockClock mMockClock = new MockClock();
    private MockBatteryStatsImpl mBatteryStats;
    private final Random mRandom = new Random();
    private final MockExternalStatsSync mExternalStatsSync = new MockExternalStatsSync();

    @Before
    public void setup() {
        final File historyDir = createTemporaryDirectory(getClass().getSimpleName());
        mMockClock.currentTime = 3000;
        mBatteryStats = new MockBatteryStatsImpl(mMockClock, historyDir);
        mBatteryStats.setDummyExternalStatsSync(mExternalStatsSync);
        mBatteryStats.setRecordAllHistoryLocked(true);
        mBatteryStats.forceRecordAllHistory();
        mBatteryStats.setNoAutoReset(true);
    }

    /**
     * Creates a unique new temporary directory under "java.io.tmpdir".
     */
    private File createTemporaryDirectory(String prefix) {
        while (true) {
            String candidateName = prefix + mRandom.nextInt();
            File result = new File(System.getProperty("java.io.tmpdir"), candidateName);
            if (result.mkdir()) {
                return result;
            }
        }
    }

    @Test
    public void unconstrainedIteration() {
        prepareHistory();

        final BatteryStatsHistoryIterator iterator =
                mBatteryStats.iterateBatteryStatsHistory(0, MonotonicClock.UNDEFINED);

        BatteryStats.HistoryItem item;

        assertThat(item = iterator.next()).isNotNull();
        assertThat(item.cmd).isEqualTo(BatteryStats.HistoryItem.CMD_RESET);

        assertThat(item = iterator.next()).isNotNull();
        assertHistoryItem(item,
                BatteryStats.HistoryItem.CMD_UPDATE, BatteryStats.HistoryItem.EVENT_NONE,
                null, 0, -1, 3_600_000, 90, 1_000_000);

        assertThat(item = iterator.next()).isNotNull();
        assertHistoryItem(item,
                BatteryStats.HistoryItem.CMD_UPDATE, BatteryStats.HistoryItem.EVENT_NONE,
                null, 0, 3700, 2_400_000, 80, 2_000_000);

        assertThat(item = iterator.next()).isNotNull();
        assertHistoryItem(item,
                BatteryStats.HistoryItem.CMD_UPDATE,
                BatteryStats.HistoryItem.EVENT_ALARM | BatteryStats.HistoryItem.EVENT_FLAG_START,
                "foo", APP_UID, 3700, 2_400_000, 80, 3_000_000);

        assertThat(item = iterator.next()).isNotNull();
        assertHistoryItem(item,
                BatteryStats.HistoryItem.CMD_UPDATE,
                BatteryStats.HistoryItem.EVENT_ALARM | BatteryStats.HistoryItem.EVENT_FLAG_FINISH,
                "foo", APP_UID, 3700, 2_400_000, 80, 3_001_000);

        assertThat(iterator.hasNext()).isFalse();
        assertThat(iterator.next()).isNull();
    }

    @Test
    public void constrainedIteration() {
        prepareHistory();

        // Initial time is 1000_000
        assertIncludedEvents(mBatteryStats.iterateBatteryStatsHistory(0, MonotonicClock.UNDEFINED),
                1000_000L, 1000_000L, 2000_000L, 3000_000L, 3001_000L);
        assertIncludedEvents(
                mBatteryStats.iterateBatteryStatsHistory(2000_000, MonotonicClock.UNDEFINED),
                2000_000L, 3000_000L, 3001_000L);
        assertIncludedEvents(mBatteryStats.iterateBatteryStatsHistory(0, 3000_000L),
                1000_000L, 1000_000L, 2000_000L);
        assertIncludedEvents(mBatteryStats.iterateBatteryStatsHistory(1003_000L, 2004_000L),
                2000_000L);
    }

    private void prepareHistory() {
        mMockClock.realtime = 1000;
        mMockClock.uptime = 1000;
        mMockClock.currentTime = 3000;

        mBatteryStats.setBatteryStateLocked(BatteryManager.BATTERY_STATUS_DISCHARGING,
                100, /* plugType */ 0, 90, 72, -1, 3_600_000, 4_000_000, 0, 1_000_000,
                1_000_000, 1_000_000);
        mBatteryStats.setBatteryStateLocked(BatteryManager.BATTERY_STATUS_DISCHARGING,
                100, /* plugType */ 0, 80, 72, 3700, 2_400_000, 4_000_000, 0, 2_000_000,
                2_000_000, 2_000_000);
        mBatteryStats.noteAlarmStartLocked("foo", null, APP_UID, 3_000_000, 2_000_000);
        mBatteryStats.noteAlarmFinishLocked("foo", null, APP_UID, 3_001_000, 2_001_000);
    }

    private void assertIncludedEvents(BatteryStatsHistoryIterator iterator,
            Long... expectedTimestamps) {
        ArrayList<Long> actualTimestamps = new ArrayList<>();
        while (iterator.hasNext()) {
            BatteryStats.HistoryItem item = iterator.next();
            actualTimestamps.add(item.time);
        }
        assertThat(actualTimestamps).isEqualTo(Arrays.asList(expectedTimestamps));
    }

    // Test history that spans multiple buffers and uses more than 32k different strings.
    @Test
    public void tagsLongHistory() {
        mMockClock.currentTime = 1_000_000;
        mMockClock.realtime = 1_000_000;
        mMockClock.uptime = 1_000_000;

        mBatteryStats.setBatteryStateLocked(BatteryManager.BATTERY_STATUS_DISCHARGING,
                100, /* plugType */ 0, 90, 72, 3700, 3_600_000, 4_000_000, 0, mMockClock.realtime,
                mMockClock.uptime, mMockClock.currentTime);

        // More than 32k strings
        final int eventCount = 0x7FFF + 100;
        for (int i = 0; i < eventCount; i++) {
            // Names repeat in order to verify de-duping of identical history tags.
            String name = "a" + (i % 10);
            mMockClock.currentTime += 1_000_000;
            mMockClock.realtime += 1_000_000;
            mMockClock.uptime += 1_000_000;
            mBatteryStats.noteAlarmStartLocked(name, null, APP_UID,
                    mMockClock.realtime, mMockClock.uptime);
            mMockClock.currentTime += 500_000;
            mMockClock.realtime += 500_000;
            mMockClock.uptime += 500_000;
            mBatteryStats.noteAlarmFinishLocked(name, null, APP_UID,
                    mMockClock.realtime, mMockClock.uptime);
        }

        final BatteryStatsHistoryIterator iterator =
                mBatteryStats.iterateBatteryStatsHistory(0, MonotonicClock.UNDEFINED);

        BatteryStats.HistoryItem item;
        assertThat(item = iterator.next()).isNotNull();
        assertThat(item.cmd).isEqualTo((int) BatteryStats.HistoryItem.CMD_RESET);
        assertThat(item.eventCode).isEqualTo(BatteryStats.HistoryItem.EVENT_NONE);
        assertThat(item.eventTag).isNull();

        assertThat(item = iterator.next()).isNotNull();
        assertThat(item.cmd).isEqualTo((int) BatteryStats.HistoryItem.CMD_UPDATE);
        assertThat(item.eventCode).isEqualTo(BatteryStats.HistoryItem.EVENT_NONE);
        assertThat(item.eventTag).isNull();
        assertThat(item.time).isEqualTo(1_000_000);

        for (int i = 0; i < eventCount; i++) {
            String name = "a" + (i % 10);
            do {
                assertThat(item = iterator.next()).isNotNull();
                // Skip a blank event inserted at the start of every buffer
            } while (item.cmd != BatteryStats.HistoryItem.CMD_UPDATE
                    || item.eventCode == BatteryStats.HistoryItem.EVENT_NONE);

            assertThat(item.eventCode).isEqualTo(BatteryStats.HistoryItem.EVENT_ALARM
                    | BatteryStats.HistoryItem.EVENT_FLAG_START);
            assertThat(item.eventTag.string).isEqualTo(name);

            do {
                assertThat(item = iterator.next()).isNotNull();
            } while (item.cmd != BatteryStats.HistoryItem.CMD_UPDATE
                    || item.eventCode == BatteryStats.HistoryItem.EVENT_NONE);

            assertThat(item.eventCode).isEqualTo(BatteryStats.HistoryItem.EVENT_ALARM
                    | BatteryStats.HistoryItem.EVENT_FLAG_FINISH);
            assertThat(item.eventTag.string).isEqualTo(name);
        }

        assertThat(iterator.hasNext()).isFalse();
        assertThat(iterator.next()).isNull();
    }

    @Test
    public void cpuSuspendHistoryEvents() {
        mBatteryStats.setBatteryStateLocked(BatteryManager.BATTERY_STATUS_DISCHARGING,
                100, /* plugType */ 0, 90, 72, 3700, 3_600_000, 4_000_000, 0,
                1_000_000, 1_000_000, 1_000_000);

        mExternalStatsSync.updateCpuStats(100, 1_100_000, 1_100_000);

        // Device was suspended for 3_000 seconds, note the difference in elapsed time and uptime
        mBatteryStats.setBatteryStateLocked(BatteryManager.BATTERY_STATUS_DISCHARGING,
                100, /* plugType */ 0, 80, 72, 3700, 2_400_000, 4_000_000, 0,
                5_000_000, 2_000_000, 5_000_000);

        mExternalStatsSync.updateCpuStats(200, 5_100_000, 2_100_000);

        // Battery level is unchanged, so we don't write battery level details in history
        mBatteryStats.noteAlarmStartLocked("wakeup", null, APP_UID, 6_000_000, 3_000_000);

        assertThat(mExternalStatsSync.isSyncScheduled()).isFalse();

        // Battery level drops, so we write the accumulated battery level details
        mBatteryStats.setBatteryStateLocked(BatteryManager.BATTERY_STATUS_DISCHARGING,
                100, /* plugType */ 0, 79, 72, 3700, 2_000_000, 4_000_000, 0,
                7_000_000, 4_000_000, 6_000_000);

        mExternalStatsSync.updateCpuStats(300, 7_100_000, 4_100_000);

        final BatteryStatsHistoryIterator iterator =
                mBatteryStats.iterateBatteryStatsHistory(0, MonotonicClock.UNDEFINED);

        BatteryStats.HistoryItem item;
        assertThat(item = iterator.next()).isNotNull();
        assertThat(item.cmd).isEqualTo((int) BatteryStats.HistoryItem.CMD_RESET);
        assertThat(item.stepDetails).isNull();

        assertThat(item = iterator.next()).isNotNull();
        assertThat(item.batteryLevel).isEqualTo(90);
        assertThat(item.stepDetails).isNull();

        assertThat(item = iterator.next()).isNotNull();
        assertThat(item.batteryLevel).isEqualTo(90);
        assertThat(item.eventCode).isEqualTo(BatteryStats.HistoryItem.EVENT_COLLECT_EXTERNAL_STATS);

        assertThat(item = iterator.next()).isNotNull();
        assertThat(item.batteryLevel).isEqualTo(90);
        assertThat(item.states & BatteryStats.HistoryItem.STATE_CPU_RUNNING_FLAG).isEqualTo(0);
        assertThat(item.stepDetails.userTime).isEqualTo(100);

        assertThat(item = iterator.next()).isNotNull();
        assertThat(item.batteryLevel).isEqualTo(80);
        assertThat(item.states & BatteryStats.HistoryItem.STATE_CPU_RUNNING_FLAG).isNotEqualTo(0);
        assertThat(item.stepDetails.userTime).isEqualTo(0);

        assertThat(item = iterator.next()).isNotNull();
        assertThat(item.batteryLevel).isEqualTo(80);
        assertThat(item.eventCode).isEqualTo(BatteryStats.HistoryItem.EVENT_COLLECT_EXTERNAL_STATS);

        assertThat(item = iterator.next()).isNotNull();
        assertThat(item.batteryLevel).isEqualTo(80);
        assertThat(item.eventCode).isEqualTo(BatteryStats.HistoryItem.EVENT_ALARM_START);
        assertThat(item.stepDetails).isNull();

        assertThat(item = iterator.next()).isNotNull();
        assertThat(item.batteryLevel).isEqualTo(79);
        assertThat(item.states & BatteryStats.HistoryItem.STATE_CPU_RUNNING_FLAG).isNotEqualTo(0);
        assertThat(item.stepDetails.userTime).isEqualTo(200);

        assertThat(item = iterator.next()).isNotNull();
        assertThat(item.batteryLevel).isEqualTo(79);
        assertThat(item.eventCode).isEqualTo(BatteryStats.HistoryItem.EVENT_COLLECT_EXTERNAL_STATS);

        assertThat(item = iterator.next()).isNull();
    }

    private void assertHistoryItem(BatteryStats.HistoryItem item, int command, int eventCode,
            String tag, int uid, int voltageMv, int batteryChargeUah, int batteryLevel,
            long elapsedTimeMs) {
        assertThat(item.cmd).isEqualTo(command);
        assertThat(item.eventCode).isEqualTo(eventCode);
        if (tag == null) {
            assertThat(item.eventTag).isNull();
        } else {
            assertThat(item.eventTag.string).isEqualTo(tag);
            assertThat(item.eventTag.uid).isEqualTo(uid);
        }
        assertThat((int) item.batteryVoltage).isEqualTo(voltageMv);
        assertThat(item.batteryChargeUah).isEqualTo(batteryChargeUah);
        assertThat(item.batteryLevel).isEqualTo(batteryLevel);

        assertThat(item.time).isEqualTo(elapsedTimeMs);
    }

    private class MockExternalStatsSync extends MockBatteryStatsImpl.DummyExternalStatsSync {
        private boolean mSyncScheduled;

        @Override
        public Future<?> scheduleCpuSyncDueToWakelockChange(long delayMillis) {
            mSyncScheduled = true;
            return null;
        }

        public boolean isSyncScheduled() {
            return mSyncScheduled;
        }

        public void updateCpuStats(int totalUTimeMs, long elapsedRealtime, long uptime) {
            assertThat(mExternalStatsSync.mSyncScheduled).isTrue();
            mBatteryStats.recordHistoryEventLocked(elapsedRealtime, uptime,
                    BatteryStats.HistoryItem.EVENT_COLLECT_EXTERNAL_STATS, "wakelock-update", 0);
            mBatteryStats.addCpuStatsLocked(totalUTimeMs, 0, 0, 0, 0, 0, 0, 0);
            mBatteryStats.finishAddingCpuStatsLocked();
            mExternalStatsSync.mSyncScheduled = false;
        }
    }
}
