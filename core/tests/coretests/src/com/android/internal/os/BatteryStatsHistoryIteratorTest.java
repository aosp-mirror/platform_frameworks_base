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

import android.os.BatteryManager;
import android.os.BatteryStats;
import android.os.Process;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import libcore.testing.io.TestIoUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

@RunWith(AndroidJUnit4.class)
@SmallTest
@SuppressWarnings("GuardedBy")
public class BatteryStatsHistoryIteratorTest {
    private static final int APP_UID = Process.FIRST_APPLICATION_UID + 42;

    private MockClock mMockClock = new MockClock();
    private MockBatteryStatsImpl mBatteryStats;

    @Before
    public void setup() {
        final File historyDir =
                TestIoUtils.createTemporaryDirectory(getClass().getSimpleName());
        mBatteryStats = new MockBatteryStatsImpl(mMockClock, historyDir);
    }

    @Test
    public void testIterator() {
        mBatteryStats.setRecordAllHistoryLocked(true);
        mBatteryStats.forceRecordAllHistory();

        mMockClock.realtime = 1000;
        mMockClock.uptime = 1000;
        mBatteryStats.setNoAutoReset(true);

        mBatteryStats.setBatteryStateLocked(BatteryManager.BATTERY_STATUS_DISCHARGING, 100,
                /* plugType */ 0, 90, 72, 3700, 3_600_000, 4_000_000, 0, 1_000_000,
                1_000_000, 1_000_000);
        mBatteryStats.setBatteryStateLocked(BatteryManager.BATTERY_STATUS_DISCHARGING, 100,
                /* plugType */ 0, 80, 72, 3700, 2_400_000, 4_000_000, 0, 2_000_000,
                2_000_000, 2_000_000);

        mBatteryStats.noteAlarmStartLocked("foo", null, APP_UID, 3_000_000, 2_000_000);
        mBatteryStats.noteAlarmFinishLocked("foo", null, APP_UID, 3_001_000, 2_001_000);

        final BatteryStatsHistoryIterator iterator =
                mBatteryStats.createBatteryStatsHistoryIterator();

        BatteryStats.HistoryItem item = new BatteryStats.HistoryItem();

        assertThat(iterator.next(item)).isTrue();
        assertHistoryItem(item,
                BatteryStats.HistoryItem.CMD_RESET, BatteryStats.HistoryItem.EVENT_NONE,
                null, 0, 3_600_000, 90, 1_000_000);

        assertThat(iterator.next(item)).isTrue();
        assertHistoryItem(item,
                BatteryStats.HistoryItem.CMD_UPDATE, BatteryStats.HistoryItem.EVENT_NONE,
                null, 0, 3_600_000, 90, 1_000_000);

        assertThat(iterator.next(item)).isTrue();
        assertHistoryItem(item,
                BatteryStats.HistoryItem.CMD_UPDATE, BatteryStats.HistoryItem.EVENT_NONE,
                null, 0, 2_400_000, 80, 2_000_000);

        assertThat(iterator.next(item)).isTrue();
        assertHistoryItem(item,
                BatteryStats.HistoryItem.CMD_UPDATE, BatteryStats.HistoryItem.EVENT_NONE,
                null, 0, 2_400_000, 80, 2_000_000);

        assertThat(iterator.next(item)).isTrue();
        assertHistoryItem(item,
                BatteryStats.HistoryItem.CMD_UPDATE,
                BatteryStats.HistoryItem.EVENT_ALARM | BatteryStats.HistoryItem.EVENT_FLAG_START,
                "foo", APP_UID, 2_400_000, 80, 3_000_000);

        assertThat(iterator.next(item)).isTrue();
        assertHistoryItem(item,
                BatteryStats.HistoryItem.CMD_UPDATE,
                BatteryStats.HistoryItem.EVENT_ALARM | BatteryStats.HistoryItem.EVENT_FLAG_FINISH,
                "foo", APP_UID, 2_400_000, 80, 3_001_000);

        assertThat(iterator.next(item)).isFalse();
    }

    // Test history that spans multiple buffers and uses more than 32k different strings.
    @Test
    public void tagsLongHistory() {
        mBatteryStats.setRecordAllHistoryLocked(true);
        mBatteryStats.forceRecordAllHistory();

        mMockClock.realtime = 1000;
        mMockClock.uptime = 1000;
        mBatteryStats.setNoAutoReset(true);

        mBatteryStats.setBatteryStateLocked(BatteryManager.BATTERY_STATUS_DISCHARGING, 100,
                /* plugType */ 0, 90, 72, 3700, 3_600_000, 4_000_000, 0, 1_000_000,
                1_000_000, 1_000_000);

        // More than 32k strings
        final int eventCount = 0x7FFF + 100;
        for (int i = 0; i < eventCount; i++) {
            // Names repeat in order to verify de-duping of identical history tags.
            String name = "a" + (i % 10);
            mBatteryStats.noteAlarmStartLocked(name, null, APP_UID, 3_000_000, 2_000_000);
            mBatteryStats.noteAlarmFinishLocked(name, null, APP_UID, 3_500_000, 2_500_000);
        }

        final BatteryStatsHistoryIterator iterator =
                mBatteryStats.createBatteryStatsHistoryIterator();

        BatteryStats.HistoryItem item = new BatteryStats.HistoryItem();
        assertThat(iterator.next(item)).isTrue();
        assertThat(item.cmd).isEqualTo((int) BatteryStats.HistoryItem.CMD_RESET);
        assertThat(item.eventCode).isEqualTo(BatteryStats.HistoryItem.EVENT_NONE);
        assertThat(item.eventTag).isNull();

        assertThat(iterator.next(item)).isTrue();
        assertThat(item.cmd).isEqualTo((int) BatteryStats.HistoryItem.CMD_UPDATE);
        assertThat(item.eventCode).isEqualTo(BatteryStats.HistoryItem.EVENT_NONE);
        assertThat(item.eventTag).isNull();
        assertThat(item.time).isEqualTo(1_000_000);

        assertThat(iterator.next(item)).isTrue();
        assertThat(item.cmd).isEqualTo((int) BatteryStats.HistoryItem.CMD_UPDATE);
        assertThat(item.eventCode).isEqualTo(BatteryStats.HistoryItem.EVENT_NONE);
        assertThat(item.eventTag).isNull();
        assertThat(item.time).isEqualTo(2_000_000);

        for (int i = 0; i < eventCount; i++) {
            String name = "a" + (i % 10);
            do {
                assertThat(iterator.next(item)).isTrue();
                // Skip a blank event inserted at the start of every buffer
            } while (item.cmd != BatteryStats.HistoryItem.CMD_UPDATE
                    || item.eventCode == BatteryStats.HistoryItem.EVENT_NONE);

            assertThat(item.eventCode).isEqualTo(BatteryStats.HistoryItem.EVENT_ALARM
                    | BatteryStats.HistoryItem.EVENT_FLAG_START);
            assertThat(item.eventTag.string).isEqualTo(name);

            do {
                assertThat(iterator.next(item)).isTrue();
            } while (item.cmd != BatteryStats.HistoryItem.CMD_UPDATE
                    || item.eventCode == BatteryStats.HistoryItem.EVENT_NONE);

            assertThat(item.eventCode).isEqualTo(BatteryStats.HistoryItem.EVENT_ALARM
                    | BatteryStats.HistoryItem.EVENT_FLAG_FINISH);
            assertThat(item.eventTag.string).isEqualTo(name);
        }

        assertThat(iterator.next(item)).isFalse();
    }

    private void assertHistoryItem(BatteryStats.HistoryItem item, int command, int eventCode,
            String tag, int uid, int batteryChargeUah, int batteryLevel,
            long elapsedTimeMs) {
        assertThat(item.cmd).isEqualTo(command);
        assertThat(item.eventCode).isEqualTo(eventCode);
        if (tag == null) {
            assertThat(item.eventTag).isNull();
        } else {
            assertThat(item.eventTag.string).isEqualTo(tag);
            assertThat(item.eventTag.uid).isEqualTo(uid);
        }
        assertThat(item.batteryChargeUah).isEqualTo(batteryChargeUah);
        assertThat(item.batteryLevel).isEqualTo(batteryLevel);

        assertThat(item.time).isEqualTo(elapsedTimeMs);
    }
}
