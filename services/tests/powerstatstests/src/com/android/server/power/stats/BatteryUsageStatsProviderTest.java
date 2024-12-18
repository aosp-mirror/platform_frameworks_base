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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.content.Context;
import android.hardware.SensorManager;
import android.os.AggregateBatteryConsumer;
import android.os.BatteryConsumer;
import android.os.BatteryManager;
import android.os.BatteryStats;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.ConditionVariable;
import android.os.Parcel;
import android.os.Process;
import android.os.UidBatteryConsumer;
import android.platform.test.ravenwood.RavenwoodRule;
import android.util.SparseLongArray;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.BatteryStatsHistoryIterator;
import com.android.internal.os.PowerProfile;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class BatteryUsageStatsProviderTest {
    @Rule(order = 0)
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true)
            .build();

    private static final int APP_UID = Process.FIRST_APPLICATION_UID + 42;
    private static final long MINUTE_IN_MS = 60 * 1000;
    private static final double PRECISION = 0.00001;

    @Rule(order = 1)
    public final BatteryUsageStatsRule mStatsRule =
            new BatteryUsageStatsRule(12345)
                    .createTempDirectory()
                    .setAveragePower(PowerProfile.POWER_FLASHLIGHT, 360.0)
                    .setAveragePower(PowerProfile.POWER_AUDIO, 720.0);

    private MockClock mMockClock = mStatsRule.getMockClock();
    private Context mContext;

    @Before
    public void setup() throws IOException {
        if (RavenwoodRule.isUnderRavenwood()) {
            mContext = mock(Context.class);
            SensorManager sensorManager = mock(SensorManager.class);
            when(mContext.getSystemService(SensorManager.class)).thenReturn(sensorManager);
        } else {
            mContext = InstrumentationRegistry.getContext();
        }
    }

    @Test
    public void test_getBatteryUsageStats() {
        BatteryStatsImpl batteryStats = prepareBatteryStats();

        BatteryUsageStatsProvider provider = new BatteryUsageStatsProvider(mContext,
                mock(PowerAttributor.class), mStatsRule.getPowerProfile(),
                mStatsRule.getCpuScalingPolicies(), mock(PowerStatsStore.class), mMockClock);

        final BatteryUsageStats batteryUsageStats =
                provider.getBatteryUsageStats(batteryStats, BatteryUsageStatsQuery.DEFAULT);

        final List<UidBatteryConsumer> uidBatteryConsumers =
                batteryUsageStats.getUidBatteryConsumers();
        final UidBatteryConsumer uidBatteryConsumer = uidBatteryConsumers.get(0);
        assertThat(uidBatteryConsumer.getTimeInStateMs(UidBatteryConsumer.STATE_FOREGROUND))
                .isEqualTo(20 * MINUTE_IN_MS);
        assertThat(uidBatteryConsumer.getTimeInStateMs(UidBatteryConsumer.STATE_BACKGROUND))
                .isEqualTo(40 * MINUTE_IN_MS);
        assertThat(uidBatteryConsumer
                .getTimeInProcessStateMs(UidBatteryConsumer.PROCESS_STATE_FOREGROUND))
                .isEqualTo(20 * MINUTE_IN_MS);
        assertThat(uidBatteryConsumer
                .getTimeInProcessStateMs(UidBatteryConsumer.PROCESS_STATE_BACKGROUND))
                .isEqualTo(20 * MINUTE_IN_MS);
        assertThat(uidBatteryConsumer
                .getTimeInProcessStateMs(UidBatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE))
                .isEqualTo(20 * MINUTE_IN_MS);
        assertThat(uidBatteryConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_AUDIO))
                .isWithin(PRECISION).of(2.0);
        assertThat(
                uidBatteryConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_FLASHLIGHT))
                .isWithin(PRECISION).of(0.4);

        assertThat(batteryUsageStats.getStatsStartTimestamp()).isEqualTo(12345);
        assertThat(batteryUsageStats.getStatsEndTimestamp()).isEqualTo(54321);
    }

    @Test
    public void test_selectPowerComponents() {
        BatteryStatsImpl batteryStats = prepareBatteryStats();

        BatteryUsageStatsProvider provider = new BatteryUsageStatsProvider(mContext,
                mock(PowerAttributor.class), mStatsRule.getPowerProfile(),
                mStatsRule.getCpuScalingPolicies(), mock(PowerStatsStore.class), mMockClock);

        final BatteryUsageStats batteryUsageStats =
                provider.getBatteryUsageStats(batteryStats,
                        new BatteryUsageStatsQuery.Builder()
                                .includePowerComponents(
                                        new int[]{BatteryConsumer.POWER_COMPONENT_AUDIO})
                                .build()
                );

        final List<UidBatteryConsumer> uidBatteryConsumers =
                batteryUsageStats.getUidBatteryConsumers();
        final UidBatteryConsumer uidBatteryConsumer = uidBatteryConsumers.get(0);
        assertThat(uidBatteryConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_AUDIO))
                .isWithin(PRECISION).of(2.0);

        // FLASHLIGHT power estimation not requested, so the returned value is 0
        assertThat(
                uidBatteryConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_FLASHLIGHT))
                .isEqualTo(0);
    }

    private BatteryStatsImpl prepareBatteryStats() {
        BatteryStatsImpl batteryStats = mStatsRule.getBatteryStats();

        mStatsRule.setTime(10 * MINUTE_IN_MS, 10 * MINUTE_IN_MS);
        synchronized (batteryStats) {
            batteryStats.noteActivityResumedLocked(APP_UID);
        }

        mStatsRule.setTime(10 * MINUTE_IN_MS, 10 * MINUTE_IN_MS);
        synchronized (batteryStats) {
            batteryStats.noteUidProcessStateLocked(APP_UID, ActivityManager.PROCESS_STATE_TOP);
        }
        mStatsRule.setTime(30 * MINUTE_IN_MS, 30 * MINUTE_IN_MS);
        synchronized (batteryStats) {
            batteryStats.noteActivityPausedLocked(APP_UID);
        }
        mStatsRule.setTime(30 * MINUTE_IN_MS, 30 * MINUTE_IN_MS);
        synchronized (batteryStats) {
            batteryStats.noteUidProcessStateLocked(APP_UID,
                    ActivityManager.PROCESS_STATE_SERVICE);
        }
        mStatsRule.setTime(40 * MINUTE_IN_MS, 40 * MINUTE_IN_MS);
        synchronized (batteryStats) {
            batteryStats.noteUidProcessStateLocked(APP_UID,
                    ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
        }
        mStatsRule.setTime(50 * MINUTE_IN_MS, 50 * MINUTE_IN_MS);
        synchronized (batteryStats) {
            batteryStats.noteUidProcessStateLocked(APP_UID,
                    ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE);
        }
        mStatsRule.setTime(60 * MINUTE_IN_MS, 60 * MINUTE_IN_MS);
        synchronized (batteryStats) {
            batteryStats.noteUidProcessStateLocked(APP_UID,
                    ActivityManager.PROCESS_STATE_BOUND_TOP);
        }
        mStatsRule.setTime(70 * MINUTE_IN_MS, 70 * MINUTE_IN_MS);
        synchronized (batteryStats) {
            batteryStats.noteUidProcessStateLocked(APP_UID,
                    ActivityManager.PROCESS_STATE_CACHED_EMPTY);
        }
        synchronized (batteryStats) {
            batteryStats.noteFlashlightOnLocked(APP_UID, 1000, 1000);
        }
        synchronized (batteryStats) {
            batteryStats.noteFlashlightOffLocked(APP_UID, 5000, 5000);
        }

        synchronized (batteryStats) {
            batteryStats.noteAudioOnLocked(APP_UID, 10000, 10000);
        }
        synchronized (batteryStats) {
            batteryStats.noteAudioOffLocked(APP_UID, 20000, 20000);
        }

        mStatsRule.setCurrentTime(54321);
        return batteryStats;
    }

    @Test
    public void testWriteAndReadHistory() {
        MockBatteryStatsImpl batteryStats = mStatsRule.getBatteryStats();
        synchronized (batteryStats) {
            batteryStats.setRecordAllHistoryLocked(true);
        }
        batteryStats.forceRecordAllHistory();

        batteryStats.setNoAutoReset(true);

        synchronized (batteryStats) {
            batteryStats.setBatteryStateLocked(BatteryManager.BATTERY_STATUS_DISCHARGING,
                    100, /* plugType */ 0, 90, 72, 3700, 3_600_000, 4_000_000, 0, 1_000_000,
                    1_000_000, 1_000_000);
        }

        synchronized (batteryStats) {
            batteryStats.noteAlarmStartLocked("foo", null, APP_UID, 3_000_000, 2_000_000);
        }
        synchronized (batteryStats) {
            batteryStats.noteAlarmFinishLocked("foo", null, APP_UID, 3_001_000, 2_001_000);
        }

        BatteryUsageStatsProvider provider = new BatteryUsageStatsProvider(mContext,
                mock(PowerAttributor.class), mStatsRule.getPowerProfile(),
                mStatsRule.getCpuScalingPolicies(), mock(PowerStatsStore.class), mMockClock);

        final BatteryUsageStats batteryUsageStats =
                provider.getBatteryUsageStats(batteryStats,
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
        BatteryStats.HistoryItem item;

        assertThat(item = iterator.next()).isNotNull();
        assertHistoryItem(item,
                BatteryStats.HistoryItem.CMD_RESET, BatteryStats.HistoryItem.EVENT_NONE,
                null, 0, 3_600_000, 90, 1_000_000);

        assertThat(item = iterator.next()).isNotNull();
        assertHistoryItem(item,
                BatteryStats.HistoryItem.CMD_UPDATE, BatteryStats.HistoryItem.EVENT_NONE,
                null, 0, 3_600_000, 90, 1_000_000);
        assertThat(item.states & BatteryStats.HistoryItem.STATE_CPU_RUNNING_FLAG).isNotEqualTo(0);

        assertThat(item = iterator.next()).isNotNull();
        assertHistoryItem(item,
                BatteryStats.HistoryItem.CMD_UPDATE, BatteryStats.HistoryItem.EVENT_NONE,
                null, 0, 3_600_000, 90, 2_000_000);
        assertThat(item.states & BatteryStats.HistoryItem.STATE_CPU_RUNNING_FLAG).isEqualTo(0);

        assertThat(item = iterator.next()).isNotNull();
        assertHistoryItem(item,
                BatteryStats.HistoryItem.CMD_UPDATE,
                BatteryStats.HistoryItem.EVENT_ALARM | BatteryStats.HistoryItem.EVENT_FLAG_START,
                "foo", APP_UID, 3_600_000, 90, 3_000_000);

        assertThat(item = iterator.next()).isNotNull();
        assertHistoryItem(item,
                BatteryStats.HistoryItem.CMD_UPDATE,
                BatteryStats.HistoryItem.EVENT_ALARM | BatteryStats.HistoryItem.EVENT_FLAG_FINISH,
                "foo", APP_UID, 3_600_000, 90, 3_001_000);

        assertThat(iterator.hasNext()).isFalse();
        assertThat(iterator.next()).isNull();
    }

    @Test
    public void testWriteAndReadHistoryTags() {
        MockBatteryStatsImpl batteryStats = mStatsRule.getBatteryStats();
        synchronized (batteryStats) {
            batteryStats.setRecordAllHistoryLocked(true);
        }
        batteryStats.forceRecordAllHistory();

        batteryStats.setNoAutoReset(true);

        mStatsRule.setTime(1_000_000, 1_000_000);

        synchronized (batteryStats) {
            batteryStats.setBatteryStateLocked(BatteryManager.BATTERY_STATUS_DISCHARGING,
                    100, /* plugType */ 0, 90, 72, 3700, 3_600_000, 4_000_000, 0, 1_000_000,
                    1_000_000, 1_000_000);
        }

        // Add a large number of different history tags with strings of increasing length.
        // These long strings will overflow the history buffer, at which point
        // history will be written to disk and a new buffer started.
        // As a result, we will only see a tail end of the sequence of events included
        // in history.
        for (int i = 1; i < 200; i++) {
            StringBuilder sb = new StringBuilder().append(i).append(" ");
            for (int j = 0; j <= i; j++) {
                sb.append("word ");
            }
            final int uid = i;
            synchronized (batteryStats) {
                batteryStats.noteJobStartLocked(sb.toString(), uid);
            }
        }

        BatteryUsageStatsProvider provider = new BatteryUsageStatsProvider(mContext,
                mock(PowerAttributor.class), mStatsRule.getPowerProfile(),
                mStatsRule.getCpuScalingPolicies(), mock(PowerStatsStore.class), mMockClock);

        final BatteryUsageStats batteryUsageStats =
                provider.getBatteryUsageStats(batteryStats,
                        new BatteryUsageStatsQuery.Builder().includeBatteryHistory().build());

        Parcel parcel = Parcel.obtain();
        parcel.writeParcelable(batteryUsageStats, 0);

        if (!RavenwoodRule.isUnderRavenwood()) {
            assertThat(parcel.dataSize()).isAtMost(128_000);
        }

        parcel.setDataPosition(0);

        BatteryUsageStats unparceled = parcel.readParcelable(getClass().getClassLoader(),
                BatteryUsageStats.class);

        BatteryStatsHistoryIterator iterator = unparceled.iterateBatteryStatsHistory();
        BatteryStats.HistoryItem item;

        assertThat(item = iterator.next()).isNotNull();
        assertThat(item.cmd).isEqualTo((int) BatteryStats.HistoryItem.CMD_RESET);

        int expectedUid = 1;
        while ((item = iterator.next()) != null) {
            while (item.cmd != BatteryStats.HistoryItem.CMD_UPDATE
                    || item.eventCode == BatteryStats.HistoryItem.EVENT_NONE) {
                assertThat(item = iterator.next()).isNotNull();
            }
            int uid = item.eventTag.uid;
            assertThat(uid).isEqualTo(expectedUid++);
            assertThat(item.eventCode).isEqualTo(
                    BatteryStats.HistoryItem.EVENT_JOB | BatteryStats.HistoryItem.EVENT_FLAG_START);
            assertThat(item.eventTag.string).startsWith(uid + " ");
            assertThat(item.batteryChargeUah).isEqualTo(3_600_000);
            assertThat(item.batteryLevel).isEqualTo(90);
            assertThat(item.time).isEqualTo((long) 1_000_000);
        }

        assertThat(expectedUid).isEqualTo(200);
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
        final List<BatteryUsageStatsQuery> queries = List.of(
                new BatteryUsageStatsQuery.Builder().setMaxStatsAgeMs(1000).build(),
                new BatteryUsageStatsQuery.Builder().setMaxStatsAgeMs(2000).build()
        );

        assertThat(BatteryUsageStatsProvider.shouldUpdateStats(queries,
                10500, 10000)).isFalse();

        assertThat(BatteryUsageStatsProvider.shouldUpdateStats(queries,
                11500, 10000)).isTrue();
    }

    @Test
    public void testAggregateBatteryStats() {
        BatteryStatsImpl batteryStats = mStatsRule.getBatteryStats();

        setTime(5 * MINUTE_IN_MS);
        synchronized (batteryStats) {
            batteryStats.resetAllStatsAndHistoryLocked(BatteryStatsImpl.RESET_REASON_ADB_COMMAND);
        }

        PowerStatsStore powerStatsStore = new PowerStatsStore(
                new File(mStatsRule.getHistoryDir(), "powerstatsstore"),
                mStatsRule.getHandler());
        powerStatsStore.reset();

        BatteryUsageStatsProvider provider = new BatteryUsageStatsProvider(mContext,
                mock(PowerAttributor.class), mStatsRule.getPowerProfile(),
                mStatsRule.getCpuScalingPolicies(), powerStatsStore, mMockClock);

        batteryStats.saveBatteryUsageStatsOnReset(provider, powerStatsStore);
        synchronized (batteryStats) {
            batteryStats.resetAllStatsAndHistoryLocked(BatteryStatsImpl.RESET_REASON_ADB_COMMAND);
        }

        synchronized (batteryStats) {
            batteryStats.noteFlashlightOnLocked(APP_UID,
                    10 * MINUTE_IN_MS, 10 * MINUTE_IN_MS);
        }
        synchronized (batteryStats) {
            batteryStats.noteFlashlightOffLocked(APP_UID,
                    20 * MINUTE_IN_MS, 20 * MINUTE_IN_MS);
        }
        setTime(25 * MINUTE_IN_MS);
        synchronized (batteryStats) {
            batteryStats.resetAllStatsAndHistoryLocked(BatteryStatsImpl.RESET_REASON_ADB_COMMAND);
        }

        synchronized (batteryStats) {
            batteryStats.noteFlashlightOnLocked(APP_UID,
                    30 * MINUTE_IN_MS, 30 * MINUTE_IN_MS);
        }
        synchronized (batteryStats) {
            batteryStats.noteFlashlightOffLocked(APP_UID,
                    50 * MINUTE_IN_MS, 50 * MINUTE_IN_MS);
        }
        setTime(55 * MINUTE_IN_MS);
        synchronized (batteryStats) {
            batteryStats.resetAllStatsAndHistoryLocked(BatteryStatsImpl.RESET_REASON_ADB_COMMAND);
        }

        // This section should be ignored because the timestamp is out or range
        synchronized (batteryStats) {
            batteryStats.noteFlashlightOnLocked(APP_UID,
                    60 * MINUTE_IN_MS, 60 * MINUTE_IN_MS);
        }
        synchronized (batteryStats) {
            batteryStats.noteFlashlightOffLocked(APP_UID,
                    70 * MINUTE_IN_MS, 70 * MINUTE_IN_MS);
        }
        setTime(75 * MINUTE_IN_MS);
        synchronized (batteryStats) {
            batteryStats.resetAllStatsAndHistoryLocked(BatteryStatsImpl.RESET_REASON_ADB_COMMAND);
        }

        // This section should be ignored because it represents the current stats session
        synchronized (batteryStats) {
            batteryStats.noteFlashlightOnLocked(APP_UID,
                    80 * MINUTE_IN_MS, 80 * MINUTE_IN_MS);
        }
        synchronized (batteryStats) {
            batteryStats.noteFlashlightOffLocked(APP_UID,
                    90 * MINUTE_IN_MS, 90 * MINUTE_IN_MS);
        }
        setTime(95 * MINUTE_IN_MS);

        // Await completion
        ConditionVariable done = new ConditionVariable();
        mStatsRule.getHandler().post(done::open);
        done.block();

        // Include the first and the second snapshot, but not the third or current
        BatteryUsageStatsQuery query = new BatteryUsageStatsQuery.Builder()
                .aggregateSnapshots(20 * MINUTE_IN_MS, 60 * MINUTE_IN_MS)
                .build();
        final BatteryUsageStats stats = provider.getBatteryUsageStats(batteryStats, query);

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

    private void setTime(long timeMs) {
        mMockClock.currentTime = timeMs;
        mMockClock.realtime = timeMs;
    }

    @Test
    public void saveBatteryUsageStatsOnReset_incompatibleEnergyConsumers() throws Throwable {
        MockBatteryStatsImpl batteryStats = mStatsRule.getBatteryStats();
        batteryStats.initMeasuredEnergyStats(new String[]{"FOO", "BAR"});
        int componentId0 = BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID;
        int componentId1 = BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID + 1;

        synchronized (batteryStats) {
            batteryStats.getUidStatsLocked(APP_UID);

            SparseLongArray uidEnergies = new SparseLongArray();
            uidEnergies.put(APP_UID, 30_000_000);
            batteryStats.updateCustomEnergyConsumerStatsLocked(0, 100_000_000, uidEnergies);
            batteryStats.updateCustomEnergyConsumerStatsLocked(1, 200_000_000, uidEnergies);
        }

        BatteryUsageStatsProvider provider = new BatteryUsageStatsProvider(mContext,
                mock(PowerAttributor.class), mStatsRule.getPowerProfile(),
                mStatsRule.getCpuScalingPolicies(), mock(PowerStatsStore.class), mMockClock);

        PowerStatsStore powerStatsStore = mock(PowerStatsStore.class);
        doAnswer(invocation -> {
            BatteryUsageStats stats = invocation.getArgument(1);
            AggregateBatteryConsumer device = stats.getAggregateBatteryConsumer(
                    BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE);
            assertThat(device.getCustomPowerComponentName(componentId0)).isEqualTo("FOO");
            assertThat(device.getCustomPowerComponentName(componentId1)).isEqualTo("BAR");
            assertThat(device.getConsumedPower(componentId0))
                    .isWithin(PRECISION).of(27.77777);
            assertThat(device.getConsumedPower(componentId1))
                    .isWithin(PRECISION).of(55.55555);

            UidBatteryConsumer uid = stats.getUidBatteryConsumers().get(0);
            assertThat(uid.getConsumedPower(componentId0))
                    .isWithin(PRECISION).of(8.33333);
            assertThat(uid.getConsumedPower(componentId1))
                    .isWithin(PRECISION).of(8.33333);
            return null;
        }).when(powerStatsStore).storeBatteryUsageStats(anyLong(), any());

        mStatsRule.getBatteryStats().saveBatteryUsageStatsOnReset(provider, powerStatsStore);

        // Make an incompatible change of supported energy components.  This will trigger
        // a BatteryStats reset, which will generate a snapshot of battery stats.
        mStatsRule.initMeasuredEnergyStatsLocked(
                new String[]{"COMPONENT1"});

        mStatsRule.waitForBackgroundThread();

        verify(powerStatsStore).storeBatteryUsageStats(anyLong(), any());
    }

    @Test
    public void testAggregateBatteryStats_incompatibleSnapshot() {
        MockBatteryStatsImpl batteryStats = mStatsRule.getBatteryStats();
        batteryStats.initMeasuredEnergyStats(new String[]{"FOO", "BAR"});

        PowerStatsStore powerStatsStore = mock(PowerStatsStore.class);

        PowerStatsSpan span0 = new PowerStatsSpan(0);
        span0.addTimeFrame(0, 1000, 1234);
        span0.addSection(new BatteryUsageStatsSection(
                new BatteryUsageStats.Builder(batteryStats.getCustomEnergyConsumerNames())
                        .setStatsDuration(1234).build()));

        PowerStatsSpan span1 = new PowerStatsSpan(1);
        span1.addTimeFrame(0, 2000, 4321);
        span1.addSection(new BatteryUsageStatsSection(
                new BatteryUsageStats.Builder(new String[]{"different"})
                        .setStatsDuration(4321).build()));

        when(powerStatsStore.getTableOfContents()).thenReturn(
                List.of(span0.getMetadata(), span1.getMetadata()));

        when(powerStatsStore.loadPowerStatsSpan(0, BatteryUsageStatsSection.TYPE))
                .thenReturn(span0);
        when(powerStatsStore.loadPowerStatsSpan(1, BatteryUsageStatsSection.TYPE))
                .thenReturn(span1);

        BatteryUsageStatsProvider provider = new BatteryUsageStatsProvider(mContext,
                mock(PowerAttributor.class), mStatsRule.getPowerProfile(),
                mStatsRule.getCpuScalingPolicies(), powerStatsStore, mMockClock);

        BatteryUsageStatsQuery query = new BatteryUsageStatsQuery.Builder()
                .aggregateSnapshots(0, 3000)
                .build();
        final BatteryUsageStats stats = provider.getBatteryUsageStats(batteryStats, query);
        assertThat(stats.getCustomPowerComponentNames())
                .isEqualTo(batteryStats.getCustomEnergyConsumerNames());
        assertThat(stats.getStatsDuration()).isEqualTo(1234);
    }
}
