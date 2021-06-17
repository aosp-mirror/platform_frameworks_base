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
import android.os.BatteryConsumer;
import android.os.BatteryManager;
import android.os.BatteryStats;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.Process;
import android.os.UidBatteryConsumer;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class BatteryUsageStatsProviderTest {
    private static final int APP_UID = Process.FIRST_APPLICATION_UID + 42;
    private static final long MINUTE_IN_MS = 60 * 1000;

    @Rule
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule(12345)
            .setAveragePower(PowerProfile.POWER_FLASHLIGHT, 360.0);

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
        batteryStats.noteUidProcessStateLocked(APP_UID,
                ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE,
                40 * MINUTE_IN_MS, 40 * MINUTE_IN_MS);
        batteryStats.noteUidProcessStateLocked(APP_UID,
                ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE,
                50 * MINUTE_IN_MS, 50 * MINUTE_IN_MS);
        batteryStats.noteUidProcessStateLocked(APP_UID, ActivityManager.PROCESS_STATE_CACHED_EMPTY,
                80 * MINUTE_IN_MS, 80 * MINUTE_IN_MS);

        mStatsRule.setCurrentTime(54321);

        Context context = InstrumentationRegistry.getContext();
        BatteryUsageStatsProvider provider = new BatteryUsageStatsProvider(context, batteryStats);

        final BatteryUsageStats batteryUsageStats =
                provider.getBatteryUsageStats(BatteryUsageStatsQuery.DEFAULT);

        final List<UidBatteryConsumer> uidBatteryConsumers =
                batteryUsageStats.getUidBatteryConsumers();
        final UidBatteryConsumer uidBatteryConsumer = uidBatteryConsumers.get(0);
        assertThat(uidBatteryConsumer.getTimeInStateMs(UidBatteryConsumer.STATE_FOREGROUND))
                .isEqualTo(60 * MINUTE_IN_MS);
        assertThat(uidBatteryConsumer.getTimeInStateMs(UidBatteryConsumer.STATE_BACKGROUND))
                .isEqualTo(10 * MINUTE_IN_MS);

        assertThat(batteryUsageStats.getStatsStartTimestamp()).isEqualTo(12345);
        assertThat(batteryUsageStats.getStatsEndTimestamp()).isEqualTo(54321);
    }

    @Test
    public void testWriteAndReadHistory() {
        MockBatteryStatsImpl batteryStats = mStatsRule.getBatteryStats();
        batteryStats.setRecordAllHistoryLocked(true);
        batteryStats.forceRecordAllHistory();

        batteryStats.setNoAutoReset(true);

        batteryStats.setBatteryStateLocked(BatteryManager.BATTERY_STATUS_DISCHARGING, 100,
                /* plugType */ 0, 90, 72, 3700, 3_600_000, 4_000_000, 0, 1_000_000,
                1_000_000, 1_000_000);

        batteryStats.noteAlarmStartLocked("foo", null, APP_UID, 3_000_000, 2_000_000);
        batteryStats.noteAlarmFinishLocked("foo", null, APP_UID, 3_001_000, 2_001_000);

        Context context = InstrumentationRegistry.getContext();
        BatteryUsageStatsProvider provider = new BatteryUsageStatsProvider(context, batteryStats);

        final BatteryUsageStats batteryUsageStats =
                provider.getBatteryUsageStats(
                        new BatteryUsageStatsQuery.Builder().includeBatteryHistory().build());

        Parcel in = Parcel.obtain();
        batteryUsageStats.writeToParcel(in, 0);
        final byte[] bytes = in.marshall();

        Parcel out = Parcel.obtain();
        out.unmarshall(bytes, 0, bytes.length);
        out.setDataPosition(0);

        BatteryUsageStats unparceled = BatteryUsageStats.CREATOR.createFromParcel(out);

        final BatteryStatsHistoryIterator iterator =
                unparceled.iterateBatteryStatsHistory();
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
                null, 0, 3_600_000, 90, 2_000_000);

        assertThat(iterator.next(item)).isTrue();
        assertHistoryItem(item,
                BatteryStats.HistoryItem.CMD_UPDATE,
                BatteryStats.HistoryItem.EVENT_ALARM | BatteryStats.HistoryItem.EVENT_FLAG_START,
                "foo", APP_UID, 3_600_000, 90, 3_000_000);

        assertThat(iterator.next(item)).isTrue();
        assertHistoryItem(item,
                BatteryStats.HistoryItem.CMD_UPDATE,
                BatteryStats.HistoryItem.EVENT_ALARM | BatteryStats.HistoryItem.EVENT_FLAG_FINISH,
                "foo", APP_UID, 3_600_000, 90, 3_001_000);

        assertThat(iterator.next(item)).isFalse();
    }

    private void assertHistoryItem(BatteryStats.HistoryItem item, int command, int eventCode,
            String tag, int uid, int batteryChargeUah, int batteryLevel, long elapsedTimeMs) {
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

    @Test
    public void shouldUpdateStats() {
        Context context = InstrumentationRegistry.getContext();
        BatteryUsageStatsProvider provider = new BatteryUsageStatsProvider(context,
                mStatsRule.getBatteryStats());

        final List<BatteryUsageStatsQuery> queries = List.of(
                new BatteryUsageStatsQuery.Builder().setMaxStatsAgeMs(1000).build(),
                new BatteryUsageStatsQuery.Builder().setMaxStatsAgeMs(2000).build()
        );

        mStatsRule.setTime(10500, 0);
        assertThat(provider.shouldUpdateStats(queries, 10000)).isFalse();

        mStatsRule.setTime(11500, 0);
        assertThat(provider.shouldUpdateStats(queries, 10000)).isTrue();
    }

    @Test
    public void testAggregateBatteryStats() {
        Context context = InstrumentationRegistry.getContext();
        BatteryStatsImpl batteryStats = mStatsRule.getBatteryStats();
        mStatsRule.setCurrentTime(5 * MINUTE_IN_MS);
        batteryStats.resetAllStatsCmdLocked();

        BatteryUsageStatsStore batteryUsageStatsStore = new BatteryUsageStatsStore(context,
                batteryStats, new File(context.getCacheDir(), "BatteryUsageStatsProviderTest"),
                new TestHandler(), Integer.MAX_VALUE);

        BatteryUsageStatsProvider provider = new BatteryUsageStatsProvider(context,
                batteryStats, batteryUsageStatsStore);

        batteryStats.noteFlashlightOnLocked(APP_UID,
                10 * MINUTE_IN_MS, 10 * MINUTE_IN_MS);
        batteryStats.noteFlashlightOffLocked(APP_UID,
                20 * MINUTE_IN_MS, 20 * MINUTE_IN_MS);
        mStatsRule.setCurrentTime(25 * MINUTE_IN_MS);
        batteryStats.resetAllStatsCmdLocked();

        batteryStats.noteFlashlightOnLocked(APP_UID,
                30 * MINUTE_IN_MS, 30 * MINUTE_IN_MS);
        batteryStats.noteFlashlightOffLocked(APP_UID,
                50 * MINUTE_IN_MS, 50 * MINUTE_IN_MS);
        mStatsRule.setCurrentTime(55 * MINUTE_IN_MS);
        batteryStats.resetAllStatsCmdLocked();

        // This section should be ignored because the timestamp is out or range
        batteryStats.noteFlashlightOnLocked(APP_UID,
                60 * MINUTE_IN_MS, 60 * MINUTE_IN_MS);
        batteryStats.noteFlashlightOffLocked(APP_UID,
                70 * MINUTE_IN_MS, 70 * MINUTE_IN_MS);
        mStatsRule.setCurrentTime(75 * MINUTE_IN_MS);
        batteryStats.resetAllStatsCmdLocked();

        // This section should be ignored because it represents the current stats session
        batteryStats.noteFlashlightOnLocked(APP_UID,
                80 * MINUTE_IN_MS, 80 * MINUTE_IN_MS);
        batteryStats.noteFlashlightOffLocked(APP_UID,
                90 * MINUTE_IN_MS, 90 * MINUTE_IN_MS);
        mStatsRule.setCurrentTime(95 * MINUTE_IN_MS);

        // Include the first and the second snapshot, but not the third or current
        BatteryUsageStatsQuery query = new BatteryUsageStatsQuery.Builder()
                .aggregateSnapshots(20 * MINUTE_IN_MS, 60 * MINUTE_IN_MS)
                .build();
        final BatteryUsageStats stats = provider.getBatteryUsageStats(query);

        assertThat(stats.getStatsStartTimestamp()).isEqualTo(5 * MINUTE_IN_MS);
        assertThat(stats.getStatsEndTimestamp()).isEqualTo(55 * MINUTE_IN_MS);
        assertThat(stats.getAggregateBatteryConsumer(
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE)
                .getConsumedPower(BatteryConsumer.POWER_COMPONENT_FLASHLIGHT))
                .isWithin(0.0001)
                .of(180.0);  // 360 mA * 0.5 hours
        assertThat(stats.getAggregateBatteryConsumer(
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE)
                .getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_FLASHLIGHT))
                .isEqualTo((10 + 20) * MINUTE_IN_MS);
        final UidBatteryConsumer uidBatteryConsumer = stats.getUidBatteryConsumers().stream()
                .filter(uid -> uid.getUid() == APP_UID).findFirst().get();
        assertThat(uidBatteryConsumer
                .getConsumedPower(BatteryConsumer.POWER_COMPONENT_FLASHLIGHT))
                .isWithin(0.1)
                .of(180.0);
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
