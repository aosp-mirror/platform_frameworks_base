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

import static android.net.NetworkStats.DEFAULT_NETWORK_NO;
import static android.net.NetworkStats.METERED_NO;
import static android.net.NetworkStats.ROAMING_NO;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.power.stats.EnergyConsumerType;
import android.net.NetworkStats;
import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.Handler;
import android.os.OutcomeReceiver;
import android.platform.test.ravenwood.RavenwoodRule;
import android.telephony.AccessNetworkConstants;
import android.telephony.ActivityStatsTechSpecificInfo;
import android.telephony.DataConnectionRealTimeInfo;
import android.telephony.ModemActivityInfo;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.util.IndentingPrintWriter;

import com.android.internal.os.Clock;
import com.android.internal.os.PowerStats;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public class MobileRadioPowerStatsCollectorTest {
    private static final int APP_UID1 = 42;
    private static final int APP_UID2 = 24;
    private static final int APP_UID3 = 44;
    private static final int ISOLATED_UID = 99123;

    @Rule(order = 0)
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true)
            .build();

    @Rule(order = 1)
    public final BatteryUsageStatsRule mStatsRule =
            new BatteryUsageStatsRule().setPowerStatsThrottlePeriodMillis(
                    BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO, 10000);

    private MockBatteryStatsImpl mBatteryStats;

    private final MockClock mClock = mStatsRule.getMockClock();

    @Mock
    private Context mContext;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private TelephonyManager mTelephony;
    @Mock
    private PowerStatsCollector.ConsumedEnergyRetriever mConsumedEnergyRetriever;
    @Mock
    private Supplier<NetworkStats> mNetworkStatsSupplier;
    @Mock
    private PowerStatsUidResolver mPowerStatsUidResolver;
    @Mock
    private LongSupplier mCallDurationSupplier;
    @Mock
    private LongSupplier mScanDurationSupplier;

    private final List<PowerStats> mRecordedPowerStats = new ArrayList<>();

    private MobileRadioPowerStatsCollector.Injector mInjector =
            new MobileRadioPowerStatsCollector.Injector() {
        @Override
        public Handler getHandler() {
            return mStatsRule.getHandler();
        }

        @Override
        public Clock getClock() {
            return mStatsRule.getMockClock();
        }

        @Override
        public PowerStatsUidResolver getUidResolver() {
            return mPowerStatsUidResolver;
        }

        @Override
        public long getPowerStatsCollectionThrottlePeriod(String powerComponentName) {
            return 0;
        }

        @Override
        public PackageManager getPackageManager() {
            return mPackageManager;
        }

        @Override
        public PowerStatsCollector.ConsumedEnergyRetriever getConsumedEnergyRetriever() {
            return mConsumedEnergyRetriever;
        }

        @Override
        public IntSupplier getVoltageSupplier() {
            return () -> 3500;
        }

        @Override
        public Supplier<NetworkStats> getMobileNetworkStatsSupplier() {
            return mNetworkStatsSupplier;
        }

        @Override
        public TelephonyManager getTelephonyManager() {
            return mTelephony;
        }

        @Override
        public LongSupplier getCallDurationSupplier() {
            return mCallDurationSupplier;
        }

        @Override
        public LongSupplier getPhoneSignalScanDurationSupplier() {
            return mScanDurationSupplier;
        }
    };

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)).thenReturn(true);
        when(mPowerStatsUidResolver.mapUid(anyInt())).thenAnswer(invocation -> {
            int uid = invocation.getArgument(0);
            if (uid == ISOLATED_UID) {
                return APP_UID2;
            } else {
                return uid;
            }
        });
        mBatteryStats = mStatsRule.getBatteryStats();
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void triggering() throws Throwable {
        PowerStatsCollector collector = mBatteryStats.getPowerStatsCollector(
                BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO);
        collector.addConsumer(mRecordedPowerStats::add);

        mBatteryStats.setPowerStatsCollectorEnabled(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO,
                true);

        mockModemActivityInfo(1000, 2000, 3000, 600, new int[]{100, 200, 300, 400, 500});

        // This should trigger a sample collection
        mBatteryStats.onSystemReady(mContext);

        mStatsRule.waitForBackgroundThread();
        assertThat(mRecordedPowerStats).hasSize(1);

        mRecordedPowerStats.clear();
        mStatsRule.setTime(20000, 20000);
        mBatteryStats.notePhoneOnLocked(mClock.realtime, mClock.uptime);
        mStatsRule.waitForBackgroundThread();
        assertThat(mRecordedPowerStats).hasSize(1);

        mRecordedPowerStats.clear();
        mStatsRule.setTime(40000, 40000);
        mBatteryStats.notePhoneOffLocked(mClock.realtime, mClock.uptime);
        mStatsRule.waitForBackgroundThread();
        assertThat(mRecordedPowerStats).hasSize(1);

        mRecordedPowerStats.clear();
        mStatsRule.setTime(45000, 55000);
        mBatteryStats.noteMobileRadioPowerStateLocked(
                DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH, 0, APP_UID1, mClock.realtime,
                mClock.uptime);
        mStatsRule.setTime(50001, 50001);
        // Elapsed time under the throttling threshold - shouldn't trigger stats collection
        mBatteryStats.noteMobileRadioPowerStateLocked(DataConnectionRealTimeInfo.DC_POWER_STATE_LOW,
                0, APP_UID1, mClock.realtime, mClock.uptime);
        mStatsRule.waitForBackgroundThread();
        assertThat(mRecordedPowerStats).hasSize(1);

        mRecordedPowerStats.clear();
        mStatsRule.setTime(50002, 50002);
        mBatteryStats.noteMobileRadioPowerStateLocked(
                DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH, 0, APP_UID1, mClock.realtime,
                mClock.uptime);
        mStatsRule.setTime(55000, 50000);
        // Elapsed time under the throttling threshold - shouldn't trigger stats collection
        mBatteryStats.noteMobileRadioPowerStateLocked(DataConnectionRealTimeInfo.DC_POWER_STATE_LOW,
                0, APP_UID1, mClock.realtime, mClock.uptime);
        mStatsRule.waitForBackgroundThread();
        assertThat(mRecordedPowerStats).isEmpty();
    }

    @Test
    public void collectStats() throws Throwable {
        PowerStats powerStats = collectPowerStats(true);
        assertThat(powerStats.durationMs).isEqualTo(100);

        PowerStats.Descriptor descriptor = powerStats.descriptor;
        MobileRadioPowerStatsLayout layout =
                new MobileRadioPowerStatsLayout(descriptor);
        assertThat(layout.getDeviceSleepTime(powerStats.stats)).isEqualTo(200);
        assertThat(layout.getDeviceIdleTime(powerStats.stats)).isEqualTo(300);
        assertThat(layout.getDeviceCallTime(powerStats.stats)).isEqualTo(40000);
        assertThat(layout.getDeviceScanTime(powerStats.stats)).isEqualTo(60000);
        assertThat(layout.getConsumedEnergy(powerStats.stats, 0))
                .isEqualTo((64321 - 10000) * 1000 / 3500);

        assertThat(powerStats.stateStats.size()).isEqualTo(2);
        long[] state1 = powerStats.stateStats.get(MobileRadioPowerStatsCollector.makeStateKey(
                BatteryStats.RADIO_ACCESS_TECHNOLOGY_NR,
                ServiceState.FREQUENCY_RANGE_MMWAVE
        ));
        assertThat(layout.getStateRxTime(state1)).isEqualTo(6000);
        assertThat(layout.getStateTxTime(state1, 0)).isEqualTo(1000);
        assertThat(layout.getStateTxTime(state1, 1)).isEqualTo(2000);
        assertThat(layout.getStateTxTime(state1, 2)).isEqualTo(3000);
        assertThat(layout.getStateTxTime(state1, 3)).isEqualTo(4000);
        assertThat(layout.getStateTxTime(state1, 4)).isEqualTo(5000);

        long[] state2 = powerStats.stateStats.get(MobileRadioPowerStatsCollector.makeStateKey(
                BatteryStats.RADIO_ACCESS_TECHNOLOGY_LTE,
                ServiceState.FREQUENCY_RANGE_LOW
        ));
        assertThat(layout.getStateRxTime(state2)).isEqualTo(7000);
        assertThat(layout.getStateTxTime(state2, 0)).isEqualTo(8000);
        assertThat(layout.getStateTxTime(state2, 1)).isEqualTo(9000);
        assertThat(layout.getStateTxTime(state2, 2)).isEqualTo(1000);
        assertThat(layout.getStateTxTime(state2, 3)).isEqualTo(2000);
        assertThat(layout.getStateTxTime(state2, 4)).isEqualTo(3000);

        assertThat(powerStats.uidStats.size()).isEqualTo(2);
        long[] actual1 = powerStats.uidStats.get(APP_UID1);
        assertThat(layout.getUidRxBytes(actual1)).isEqualTo(1000);
        assertThat(layout.getUidTxBytes(actual1)).isEqualTo(2000);
        assertThat(layout.getUidRxPackets(actual1)).isEqualTo(100);
        assertThat(layout.getUidTxPackets(actual1)).isEqualTo(200);

        // Combines APP_UID2 and ISOLATED_UID
        long[] actual2 = powerStats.uidStats.get(APP_UID2);
        assertThat(layout.getUidRxBytes(actual2)).isEqualTo(6000);
        assertThat(layout.getUidTxBytes(actual2)).isEqualTo(3000);
        assertThat(layout.getUidRxPackets(actual2)).isEqualTo(60);
        assertThat(layout.getUidTxPackets(actual2)).isEqualTo(30);

        assertThat(powerStats.uidStats.get(ISOLATED_UID)).isNull();
        assertThat(powerStats.uidStats.get(APP_UID3)).isNull();
    }

    @Test
    public void collectStats_noPerNetworkTypeData() throws Throwable {
        PowerStats powerStats = collectPowerStats(false);
        assertThat(powerStats.durationMs).isEqualTo(100);

        PowerStats.Descriptor descriptor = powerStats.descriptor;
        MobileRadioPowerStatsLayout layout =
                new MobileRadioPowerStatsLayout(descriptor);
        assertThat(layout.getDeviceSleepTime(powerStats.stats)).isEqualTo(200);
        assertThat(layout.getDeviceIdleTime(powerStats.stats)).isEqualTo(300);
        assertThat(layout.getConsumedEnergy(powerStats.stats, 0))
                .isEqualTo((64321 - 10000) * 1000 / 3500);

        assertThat(powerStats.stateStats.size()).isEqualTo(1);
        long[] stateStats = powerStats.stateStats.get(MobileRadioPowerStatsCollector.makeStateKey(
                AccessNetworkConstants.AccessNetworkType.UNKNOWN,
                ServiceState.FREQUENCY_RANGE_UNKNOWN
        ));
        assertThat(layout.getStateRxTime(stateStats)).isEqualTo(6000);
        assertThat(layout.getStateTxTime(stateStats, 0)).isEqualTo(1000);
        assertThat(layout.getStateTxTime(stateStats, 1)).isEqualTo(2000);
        assertThat(layout.getStateTxTime(stateStats, 2)).isEqualTo(3000);
        assertThat(layout.getStateTxTime(stateStats, 3)).isEqualTo(4000);
        assertThat(layout.getStateTxTime(stateStats, 4)).isEqualTo(5000);

        assertThat(powerStats.uidStats.size()).isEqualTo(2);
        long[] actual1 = powerStats.uidStats.get(APP_UID1);
        assertThat(layout.getUidRxBytes(actual1)).isEqualTo(1000);
        assertThat(layout.getUidTxBytes(actual1)).isEqualTo(2000);
        assertThat(layout.getUidRxPackets(actual1)).isEqualTo(100);
        assertThat(layout.getUidTxPackets(actual1)).isEqualTo(200);

        // Combines APP_UID2 and ISOLATED_UID
        long[] actual2 = powerStats.uidStats.get(APP_UID2);
        assertThat(layout.getUidRxBytes(actual2)).isEqualTo(6000);
        assertThat(layout.getUidTxBytes(actual2)).isEqualTo(3000);
        assertThat(layout.getUidRxPackets(actual2)).isEqualTo(60);
        assertThat(layout.getUidTxPackets(actual2)).isEqualTo(30);

        assertThat(powerStats.uidStats.get(ISOLATED_UID)).isNull();
        assertThat(powerStats.uidStats.get(APP_UID3)).isNull();
    }

    @Test
    public void dump() throws Throwable {
        PowerStats powerStats = collectPowerStats(true);
        StringWriter sw = new StringWriter();
        IndentingPrintWriter pw = new IndentingPrintWriter(sw);
        powerStats.dump(pw);
        pw.flush();
        String dump = sw.toString();
        assertThat(dump).contains("duration=100");
        assertThat(dump).contains("sleep: 200 idle: 300 scan: 60000 call: 40000 energy: "
                + ((64321 - 10000) * 1000 / 3500));
        assertThat(dump).contains("(LTE) rx: 7000 tx: [8000, 9000, 1000, 2000, 3000]");
        assertThat(dump).contains("(NR MMWAVE) rx: 6000 tx: [1000, 2000, 3000, 4000, 5000]");
        assertThat(dump).contains(
                "UID 24: rx-pkts: 60 rx-B: 6000 tx-pkts: 30 tx-B: 3000");
        assertThat(dump).contains(
                "UID 42: rx-pkts: 100 rx-B: 1000 tx-pkts: 200 tx-B: 2000");
    }

    private PowerStats collectPowerStats(boolean perNetworkTypeData) throws Throwable {
        MobileRadioPowerStatsCollector collector = new MobileRadioPowerStatsCollector(mInjector);
        collector.setEnabled(true);

        when(mConsumedEnergyRetriever.getEnergyConsumerIds(
                EnergyConsumerType.MOBILE_RADIO)).thenReturn(new int[]{777});

        if (perNetworkTypeData) {
            mockModemActivityInfo(1000, 2000, 3000,
                    AccessNetworkConstants.AccessNetworkType.NGRAN,
                    ServiceState.FREQUENCY_RANGE_MMWAVE,
                    600, new int[]{100, 200, 300, 400, 500},
                    AccessNetworkConstants.AccessNetworkType.EUTRAN,
                    ServiceState.FREQUENCY_RANGE_LOW,
                    700, new int[]{800, 900, 100, 200, 300});
        } else {
            mockModemActivityInfo(1000, 2000, 3000, 600, new int[]{100, 200, 300, 400, 500});
        }
        mockNetworkStats(1000,
                4321, 321, 1234, 23,
                4000, 40, 2000, 20);

        when(mConsumedEnergyRetriever.getConsumedEnergyUws(eq(new int[]{777})))
                .thenReturn(new long[]{10000});

        when(mCallDurationSupplier.getAsLong()).thenReturn(10000L);
        when(mScanDurationSupplier.getAsLong()).thenReturn(20000L);

        collector.collectStats();

        if (perNetworkTypeData) {
            mockModemActivityInfo(1100, 2200, 3300,
                    AccessNetworkConstants.AccessNetworkType.NGRAN,
                    ServiceState.FREQUENCY_RANGE_MMWAVE,
                    6600, new int[]{1100, 2200, 3300, 4400, 5500},
                    AccessNetworkConstants.AccessNetworkType.EUTRAN,
                    ServiceState.FREQUENCY_RANGE_LOW,
                    7700, new int[]{8800, 9900, 1100, 2200, 3300});
        } else {
            mockModemActivityInfo(1100, 2200, 3300, 6600, new int[]{1100, 2200, 3300, 4400, 5500});
        }
        mockNetworkStats(1100,
                5321, 421, 3234, 223,
                8000, 80, 4000, 40);

        when(mConsumedEnergyRetriever.getConsumedEnergyUws(eq(new int[]{777})))
                .thenReturn(new long[]{64321});
        when(mCallDurationSupplier.getAsLong()).thenReturn(50000L);
        when(mScanDurationSupplier.getAsLong()).thenReturn(80000L);

        mStatsRule.setTime(20000, 20000);
        return collector.collectStats();
    }

    private void mockModemActivityInfo(long timestamp, int sleepTimeMs, int idleTimeMs,
            int networkType1, int freqRange1, int rxTimeMs1, @NonNull int[] txTimeMs1,
            int networkType2, int freqRange2, int rxTimeMs2, @NonNull int[] txTimeMs2) {
        ModemActivityInfo info = new ModemActivityInfo(timestamp, sleepTimeMs, idleTimeMs,
                new ActivityStatsTechSpecificInfo[]{
                        new ActivityStatsTechSpecificInfo(networkType1, freqRange1, txTimeMs1,
                                rxTimeMs1),
                        new ActivityStatsTechSpecificInfo(networkType2, freqRange2, txTimeMs2,
                                rxTimeMs2)});
        doAnswer(invocation -> {
            OutcomeReceiver<ModemActivityInfo, TelephonyManager.ModemActivityInfoException>
                    receiver = invocation.getArgument(1);
            receiver.onResult(info);
            return null;
        }).when(mTelephony).requestModemActivityInfo(any(), any());
    }

    private void mockModemActivityInfo(long timestamp, int sleepTimeMs, int idleTimeMs,
            int rxTimeMs, @NonNull int[] txTimeMs) {
        ModemActivityInfo info = new ModemActivityInfo(timestamp, sleepTimeMs, idleTimeMs, txTimeMs,
                rxTimeMs);
        doAnswer(invocation -> {
            OutcomeReceiver<ModemActivityInfo, TelephonyManager.ModemActivityInfoException>
                    receiver = invocation.getArgument(1);
            receiver.onResult(info);
            return null;
        }).when(mTelephony).requestModemActivityInfo(any(), any());
    }

    private void mockNetworkStats(long elapsedRealtime,
            long rxBytes1, long rxPackets1, long txBytes1, long txPackets1,
            long rxBytes2, long rxPackets2, long txBytes2, long txPackets2) {
        NetworkStats stats;
        if (RavenwoodRule.isOnRavenwood()) {
            stats = mock(NetworkStats.class);
            List<NetworkStats.Entry> entries = List.of(
                    mockNetworkStatsEntry(APP_UID1, rxBytes1, rxPackets1, txBytes1, txPackets1),
                    mockNetworkStatsEntry(APP_UID2, rxBytes2, rxPackets2, txBytes2, txPackets2),
                    mockNetworkStatsEntry(ISOLATED_UID, rxBytes2 / 2, rxPackets2 / 2, txBytes2 / 2,
                            txPackets2 / 2),
                    mockNetworkStatsEntry(APP_UID3, 314, 281, 314, 281));
            when(stats.iterator()).thenAnswer(inv -> entries.iterator());
        } else {
            stats = new NetworkStats(elapsedRealtime, 1)
                    .addEntry(new NetworkStats.Entry("mobile", APP_UID1, 0, 0,
                            METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, rxBytes1, rxPackets1,
                            txBytes1, txPackets1, 100))
                    .addEntry(new NetworkStats.Entry("mobile", APP_UID2, 0, 0,
                            METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, rxBytes2, rxPackets2,
                            txBytes2, txPackets2, 111))
                    .addEntry(new NetworkStats.Entry("mobile", ISOLATED_UID, 0, 0, METERED_NO,
                            ROAMING_NO, DEFAULT_NETWORK_NO, rxBytes2 / 2, rxPackets2 / 2,
                            txBytes2 / 2, txPackets2 / 2, 111))
                    .addEntry(new NetworkStats.Entry("mobile", APP_UID3, 0, 0, METERED_NO,
                            ROAMING_NO, DEFAULT_NETWORK_NO, 314, 281, 314, 281, 111));
        }
        when(mNetworkStatsSupplier.get()).thenReturn(stats);
    }

    private static NetworkStats.Entry mockNetworkStatsEntry(int uid, long rxBytes, long rxPackets,
            long txBytes, long txPackets) {
        NetworkStats.Entry entry = mock(NetworkStats.Entry.class);
        when(entry.getUid()).thenReturn(uid);
        when(entry.getMetered()).thenReturn(METERED_NO);
        when(entry.getRoaming()).thenReturn(ROAMING_NO);
        when(entry.getDefaultNetwork()).thenReturn(DEFAULT_NETWORK_NO);
        when(entry.getRxBytes()).thenReturn(rxBytes);
        when(entry.getRxPackets()).thenReturn(rxPackets);
        when(entry.getTxBytes()).thenReturn(txBytes);
        when(entry.getTxPackets()).thenReturn(txPackets);
        when(entry.getOperations()).thenReturn(100L);
        return entry;
    }

    @Test
    public void networkTypeConstants() throws Throwable {
        Class<AccessNetworkConstants.AccessNetworkType> clazz =
                AccessNetworkConstants.AccessNetworkType.class;
        for (Field field : clazz.getDeclaredFields()) {
            final int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers)
                    && field.getType().equals(int.class)) {
                boolean found = false;
                int value = field.getInt(null);
                for (int i = 0; i < MobileRadioPowerStatsCollector.NETWORK_TYPES.length; i++) {
                    if (MobileRadioPowerStatsCollector.NETWORK_TYPES[i] == value) {
                        found = true;
                        break;
                    }
                }
                assertWithMessage("New network type, " + field.getName() + " not represented in "
                        + MobileRadioPowerStatsCollector.class).that(found).isTrue();
            }
        }
    }
}
