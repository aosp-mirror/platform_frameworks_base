/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.bluetooth.BluetoothActivityEnergyInfo;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.UidTraffic;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.power.stats.EnergyConsumerResult;
import android.hardware.power.stats.EnergyConsumerType;
import android.os.BatteryConsumer;
import android.os.Handler;
import android.os.Parcel;
import android.platform.test.ravenwood.RavenwoodRule;
import android.util.IndentingPrintWriter;
import android.util.SparseLongArray;

import com.android.internal.os.Clock;
import com.android.internal.os.PowerStats;
import com.android.server.power.stats.format.BluetoothPowerStatsLayout;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

public class BluetoothPowerStatsCollectorTest {
    private static final int APP_UID1 = 42;
    private static final int APP_UID2 = 24;
    private static final int ISOLATED_UID = 99123;

    @Rule(order = 0)
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true)
            .build();

    @Rule(order = 1)
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule()
            .setPowerStatsThrottlePeriodMillis(BatteryConsumer.POWER_COMPONENT_BLUETOOTH, 1000);

    private MockBatteryStatsImpl mBatteryStats;

    private final MockClock mClock = mStatsRule.getMockClock();

    @Mock
    private Context mContext;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private PowerStatsCollector.ConsumedEnergyRetriever mConsumedEnergyRetriever;
    private final PowerStatsUidResolver mPowerStatsUidResolver = new PowerStatsUidResolver();

    private BluetoothActivityEnergyInfo mBluetoothActivityEnergyInfo;
    private final SparseLongArray mUidScanTimes = new SparseLongArray();

    private final BluetoothPowerStatsCollector.BluetoothStatsRetriever mBluetoothStatsRetriever =
            new BluetoothPowerStatsCollector.BluetoothStatsRetriever() {
                @Override
                public void retrieveBluetoothScanTimes(Callback callback) {
                    for (int i = 0; i < mUidScanTimes.size(); i++) {
                        callback.onBluetoothScanTime(mUidScanTimes.keyAt(i),
                                mUidScanTimes.valueAt(i));
                    }
                }

                @Override
                public boolean requestControllerActivityEnergyInfo(Executor executor,
                        BluetoothAdapter.OnBluetoothActivityEnergyInfoCallback callback) {
                    callback.onBluetoothActivityEnergyInfoAvailable(mBluetoothActivityEnergyInfo);
                    return true;
                }
            };

    private final List<PowerStats> mRecordedPowerStats = new ArrayList<>();

    private BluetoothPowerStatsCollector.Injector mInjector =
            new BluetoothPowerStatsCollector.Injector() {
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
                public BluetoothPowerStatsCollector.BluetoothStatsRetriever
                        getBluetoothStatsRetriever() {
                    return mBluetoothStatsRetriever;
                }
            };

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)).thenReturn(true);
        mPowerStatsUidResolver.noteIsolatedUidAdded(ISOLATED_UID, APP_UID2);
        mBatteryStats = mStatsRule.getBatteryStats();
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void triggering() throws Throwable {
        PowerStatsCollector collector = mBatteryStats.getPowerStatsCollector(
                BatteryConsumer.POWER_COMPONENT_BLUETOOTH);
        collector.addConsumer(mRecordedPowerStats::add);

        mBatteryStats.setPowerStatsCollectorEnabled(BatteryConsumer.POWER_COMPONENT_BLUETOOTH,
                true);

        mBatteryStats.setDummyExternalStatsSync(new MockBatteryStatsImpl.DummyExternalStatsSync(){
            @Override
            public void scheduleSyncDueToProcessStateChange(int flags, long delayMillis) {
                collector.schedule();
            }
        });

        mBluetoothActivityEnergyInfo = mockBluetoothActivityEnergyInfo(1000, 2000, 3000, 600);

        // This should trigger a sample collection to establish a baseline
        mBatteryStats.onSystemReady(mContext);

        mStatsRule.waitForBackgroundThread();
        assertThat(mRecordedPowerStats).hasSize(1);

        mRecordedPowerStats.clear();
        mStatsRule.setTime(70000, 70000);
        mBatteryStats.noteUidProcessStateLocked(APP_UID1, ActivityManager.PROCESS_STATE_TOP,
                mClock.realtime, mClock.uptime);
        mStatsRule.waitForBackgroundThread();
        assertThat(mRecordedPowerStats).hasSize(1);
    }

    @Test
    public void collectStats() {
        PowerStats powerStats = collectPowerStats();
        assertThat(powerStats.durationMs).isEqualTo(7200);

        BluetoothPowerStatsLayout layout = new BluetoothPowerStatsLayout(powerStats.descriptor);
        assertThat(layout.getDeviceRxTime(powerStats.stats)).isEqualTo(6000);
        assertThat(layout.getDeviceTxTime(powerStats.stats)).isEqualTo(1000);
        assertThat(layout.getDeviceIdleTime(powerStats.stats)).isEqualTo(200);
        assertThat(layout.getDeviceScanTime(powerStats.stats)).isEqualTo(800);
        assertThat(layout.getConsumedEnergy(powerStats.stats, 0))
                .isEqualTo((64321 - 10000) * 1000 / 3500);

        assertThat(powerStats.uidStats.size()).isEqualTo(2);
        long[] actual1 = powerStats.uidStats.get(APP_UID1);
        assertThat(layout.getUidRxBytes(actual1)).isEqualTo(1000);
        assertThat(layout.getUidTxBytes(actual1)).isEqualTo(2000);
        assertThat(layout.getUidScanTime(actual1)).isEqualTo(100);

        // Combines APP_UID2 and ISOLATED_UID
        long[] actual2 = powerStats.uidStats.get(APP_UID2);
        assertThat(layout.getUidRxBytes(actual2)).isEqualTo(8000);
        assertThat(layout.getUidTxBytes(actual2)).isEqualTo(10000);
        assertThat(layout.getUidScanTime(actual2)).isEqualTo(700);

        assertThat(powerStats.uidStats.get(ISOLATED_UID)).isNull();
    }

    @Test
    public void dump() throws Throwable {
        PowerStats powerStats = collectPowerStats();
        StringWriter sw = new StringWriter();
        IndentingPrintWriter pw = new IndentingPrintWriter(sw);
        powerStats.dump(pw);
        pw.flush();
        String dump = sw.toString();
        assertThat(dump).contains("duration=7200");
        assertThat(dump).contains(
                "rx: 6000 tx: 1000 idle: 200 scan: 800 energy: " + ((64321 - 10000) * 1000 / 3500));
        assertThat(dump).contains("UID 24: rx-B: 8000 tx-B: 10000 scan: 700");
        assertThat(dump).contains("UID 42: rx-B: 1000 tx-B: 2000 scan: 100");
    }

    private PowerStats collectPowerStats() {
        BluetoothPowerStatsCollector collector = new BluetoothPowerStatsCollector(mInjector);
        collector.setEnabled(true);

        when(mConsumedEnergyRetriever.getVoltageMv()).thenReturn(3500);
        when(mConsumedEnergyRetriever.getEnergyConsumerIds(EnergyConsumerType.BLUETOOTH))
                .thenReturn(new int[]{777});

        mBluetoothActivityEnergyInfo = mockBluetoothActivityEnergyInfo(1000, 600, 100, 2000,
                mockUidTraffic(APP_UID1, 100, 200),
                mockUidTraffic(APP_UID2, 300, 400),
                mockUidTraffic(ISOLATED_UID, 500, 600));

        mUidScanTimes.put(APP_UID1, 100);

        mockConsumedEnergy(777, 10000);

        // Establish a baseline
        collector.collectStats();

        mBluetoothActivityEnergyInfo = mockBluetoothActivityEnergyInfo(1100, 6600, 1100, 2200,
                mockUidTraffic(APP_UID1, 1100, 2200),
                mockUidTraffic(APP_UID2, 3300, 4400),
                mockUidTraffic(ISOLATED_UID, 5500, 6600));

        mUidScanTimes.clear();
        mUidScanTimes.put(APP_UID1, 200);
        mUidScanTimes.put(APP_UID2, 300);
        mUidScanTimes.put(ISOLATED_UID, 400);

        mockConsumedEnergy(777, 64321);

        mStatsRule.setTime(20000, 20000);
        return collector.collectStats();
    }

    private void mockConsumedEnergy(int consumerId, long energyUWs) {
        EnergyConsumerResult ecr = new EnergyConsumerResult();
        ecr.energyUWs = energyUWs;
        when(mConsumedEnergyRetriever.getConsumedEnergy(eq(new int[]{consumerId})))
                .thenReturn(new EnergyConsumerResult[]{ecr});
    }

    private BluetoothActivityEnergyInfo mockBluetoothActivityEnergyInfo(long timestamp,
            long rxTimeMs, long txTimeMs, long idleTimeMs, UidTraffic... uidTraffic) {
        if (RavenwoodRule.isOnRavenwood()) {
            BluetoothActivityEnergyInfo info = mock(BluetoothActivityEnergyInfo.class);
            when(info.getControllerRxTimeMillis()).thenReturn(rxTimeMs);
            when(info.getControllerTxTimeMillis()).thenReturn(txTimeMs);
            when(info.getControllerIdleTimeMillis()).thenReturn(idleTimeMs);
            when(info.getUidTraffic()).thenReturn(List.of(uidTraffic));
            return info;
        } else {
            final Parcel btActivityEnergyInfoParcel = Parcel.obtain();
            btActivityEnergyInfoParcel.writeLong(timestamp);
            btActivityEnergyInfoParcel.writeInt(
                    BluetoothActivityEnergyInfo.BT_STACK_STATE_STATE_ACTIVE);
            btActivityEnergyInfoParcel.writeLong(txTimeMs);
            btActivityEnergyInfoParcel.writeLong(rxTimeMs);
            btActivityEnergyInfoParcel.writeLong(idleTimeMs);
            btActivityEnergyInfoParcel.writeLong(0L);
            btActivityEnergyInfoParcel.writeTypedList(List.of(uidTraffic));
            btActivityEnergyInfoParcel.setDataPosition(0);

            BluetoothActivityEnergyInfo info = BluetoothActivityEnergyInfo.CREATOR
                    .createFromParcel(btActivityEnergyInfoParcel);
            btActivityEnergyInfoParcel.recycle();
            return info;
        }
    }

    private UidTraffic mockUidTraffic(int uid, long rxBytes, long txBytes) {
        if (RavenwoodRule.isOnRavenwood()) {
            UidTraffic traffic = mock(UidTraffic.class);
            when(traffic.getUid()).thenReturn(uid);
            when(traffic.getRxBytes()).thenReturn(rxBytes);
            when(traffic.getTxBytes()).thenReturn(txBytes);
            return traffic;
        } else {
            final Parcel uidTrafficParcel = Parcel.obtain();
            uidTrafficParcel.writeInt(uid);
            uidTrafficParcel.writeLong(rxBytes);
            uidTrafficParcel.writeLong(txBytes);
            uidTrafficParcel.setDataPosition(0);

            UidTraffic traffic = UidTraffic.CREATOR.createFromParcel(uidTrafficParcel);
            uidTrafficParcel.recycle();
            return traffic;
        }
    }
}
