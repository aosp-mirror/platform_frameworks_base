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

package com.android.server.power.stats.processor;

import static android.os.BatteryConsumer.PROCESS_STATE_BACKGROUND;
import static android.os.BatteryConsumer.PROCESS_STATE_CACHED;
import static android.os.BatteryConsumer.PROCESS_STATE_FOREGROUND;
import static android.os.BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE;

import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.POWER_STATE_OTHER;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.SCREEN_STATE_ON;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.SCREEN_STATE_OTHER;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.STATE_POWER;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.STATE_PROCESS_STATE;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.STATE_SCREEN;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import android.os.Process;
import android.platform.test.ravenwood.RavenwoodRule;
import android.util.SparseLongArray;

import com.android.internal.os.Clock;
import com.android.internal.os.PowerProfile;
import com.android.server.power.stats.BatteryUsageStatsRule;
import com.android.server.power.stats.BluetoothPowerStatsCollector;
import com.android.server.power.stats.BluetoothPowerStatsCollector.BluetoothStatsRetriever;
import com.android.server.power.stats.PowerStatsCollector;
import com.android.server.power.stats.PowerStatsUidResolver;
import com.android.server.power.stats.format.BluetoothPowerStatsLayout;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

public class BluetoothPowerStatsProcessorTest {

    @Rule(order = 0)
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true)
            .build();

    private static final double PRECISION = 0.00001;
    private static final int APP_UID1 = Process.FIRST_APPLICATION_UID + 42;
    private static final int APP_UID2 = Process.FIRST_APPLICATION_UID + 101;
    private static final int BLUETOOTH_ENERGY_CONSUMER_ID = 1;
    private static final int VOLTAGE_MV = 3500;

    @Rule(order = 1)
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule()
            .setAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_RX, 50.0)
            .setAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_TX, 100.0)
            .setAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_IDLE, 10.0)
            .initMeasuredEnergyStatsLocked();

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

    private final BluetoothPowerStatsCollector.Injector mInjector =
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
                public BluetoothStatsRetriever getBluetoothStatsRetriever() {
                    return mBluetoothStatsRetriever;
                }
            };

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)).thenReturn(true);
    }

    @Test
    public void powerProfileModel_mostlyDataTransfer() {
        // No power monitoring hardware
        when(mConsumedEnergyRetriever.getEnergyConsumerIds(EnergyConsumerType.BLUETOOTH))
                .thenReturn(new int[0]);

        PowerComponentAggregatedPowerStats aggregatedStats = createAggregatedPowerStats(
                () -> new BluetoothPowerStatsProcessor(mStatsRule.getPowerProfile()));

        BluetoothPowerStatsCollector collector = new BluetoothPowerStatsCollector(mInjector);
        collector.setEnabled(true);
        mBluetoothActivityEnergyInfo = mockBluetoothActivityEnergyInfo(1000, 600, 100, 200,
                mockUidTraffic(APP_UID1, 100, 200),
                mockUidTraffic(APP_UID2, 300, 400));

        mUidScanTimes.put(APP_UID1, 100);

        aggregatedStats.start(0);

        // Establish a baseline
        aggregatedStats.addPowerStats(collector.collectStats(), 0);

        // Turn the screen off after 2.5 seconds
        aggregatedStats.setState(STATE_SCREEN, SCREEN_STATE_OTHER, 2500);
        aggregatedStats.setUidState(APP_UID1, STATE_PROCESS_STATE, PROCESS_STATE_BACKGROUND, 2500);
        aggregatedStats.setUidState(APP_UID1, STATE_PROCESS_STATE, PROCESS_STATE_FOREGROUND_SERVICE,
                5000);

        mBluetoothActivityEnergyInfo = mockBluetoothActivityEnergyInfo(1100, 6600, 1100, 2200,
                mockUidTraffic(APP_UID1, 1100, 2200),
                mockUidTraffic(APP_UID2, 3300, 4400));

        mUidScanTimes.clear();
        mUidScanTimes.put(APP_UID1, 200);
        mUidScanTimes.put(APP_UID2, 300);

        mStatsRule.setTime(10_000, 10_000);

        aggregatedStats.addPowerStats(collector.collectStats(), 10_000);

        aggregatedStats.finish(10_000);

        BluetoothPowerStatsLayout statsLayout =
                new BluetoothPowerStatsLayout(aggregatedStats.getPowerStatsDescriptor());

        // RX power = 'rx-duration * PowerProfile[bluetooth.controller.rx]`
        //        RX power = 6000 * 50 = 300000 mA-ms = 0.083333 mAh
        // TX power = 'tx-duration * PowerProfile[bluetooth.controller.tx]`
        //        TX power = 1000 * 100 = 100000 mA-ms = 0.02777 mAh
        // Idle power = 'idle-duration * PowerProfile[bluetooth.controller.idle]`
        //        Idle power = 2000 * 10 = 20000 mA-ms = 0.00555 mAh
        // Total power = RX + TX + Idle = 0.116666
        // Screen-on  - 25%
        // Screen-off - 75%
        double expectedPower = 0.116666;
        long[] deviceStats = new long[aggregatedStats.getPowerStatsDescriptor().statsArrayLength];
        aggregatedStats.getDeviceStats(deviceStats, states(POWER_STATE_OTHER, SCREEN_STATE_ON));
        assertThat(statsLayout.getDevicePowerEstimate(deviceStats))
                .isWithin(PRECISION).of(expectedPower * 0.25);

        aggregatedStats.getDeviceStats(deviceStats, states(POWER_STATE_OTHER, SCREEN_STATE_OTHER));
        assertThat(statsLayout.getDevicePowerEstimate(deviceStats))
                .isWithin(PRECISION).of(expectedPower * 0.75);

        // UID1 =
        //     (1000 / 4000) * 0.083333        // rx
        //   + (2000 / 6000) * 0.027777        // tx
        //   = 0.030092 mAh
        double expectedPower1 = 0.030092;
        long[] uidStats = new long[aggregatedStats.getPowerStatsDescriptor().uidStatsArrayLength];
        aggregatedStats.getUidStats(uidStats, APP_UID1,
                states(POWER_STATE_OTHER, SCREEN_STATE_ON, PROCESS_STATE_FOREGROUND));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(expectedPower1 * 0.25);

        aggregatedStats.getUidStats(uidStats, APP_UID1,
                states(POWER_STATE_OTHER, SCREEN_STATE_OTHER, PROCESS_STATE_BACKGROUND));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(expectedPower1 * 0.25);

        aggregatedStats.getUidStats(uidStats, APP_UID1,
                states(POWER_STATE_OTHER, SCREEN_STATE_OTHER, PROCESS_STATE_FOREGROUND_SERVICE));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(expectedPower1 * 0.5);

        // UID2 =
        //     (3000 / 4000) * 0.083333        // rx
        //   + (4000 / 6000) * 0.027777        // tx
        //   = 0.08102 mAh
        double expectedPower2 = 0.08102;
        aggregatedStats.getUidStats(uidStats, APP_UID2,
                states(POWER_STATE_OTHER, SCREEN_STATE_ON, PROCESS_STATE_CACHED));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(expectedPower2 * 0.25);

        aggregatedStats.getUidStats(uidStats, APP_UID2,
                states(POWER_STATE_OTHER, SCREEN_STATE_OTHER, PROCESS_STATE_CACHED));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(expectedPower2 * 0.75);
    }

    @Test
    public void powerProfileModel_mostlyScan() {
        // No power monitoring hardware
        when(mConsumedEnergyRetriever.getEnergyConsumerIds(EnergyConsumerType.BLUETOOTH))
                .thenReturn(new int[0]);

        PowerComponentAggregatedPowerStats aggregatedStats = createAggregatedPowerStats(
                () -> new BluetoothPowerStatsProcessor(mStatsRule.getPowerProfile()));

        BluetoothPowerStatsCollector collector = new BluetoothPowerStatsCollector(mInjector);
        collector.setEnabled(true);
        mBluetoothActivityEnergyInfo = mockBluetoothActivityEnergyInfo(1000, 600, 100, 200,
                mockUidTraffic(APP_UID1, 100, 200),
                mockUidTraffic(APP_UID2, 300, 400));

        mUidScanTimes.put(APP_UID1, 100);

        aggregatedStats.start(0);

        // Establish a baseline
        aggregatedStats.addPowerStats(collector.collectStats(), 0);

        aggregatedStats.setState(STATE_SCREEN, SCREEN_STATE_OTHER, 2500);
        aggregatedStats.setUidState(APP_UID1, STATE_PROCESS_STATE, PROCESS_STATE_BACKGROUND, 2500);
        aggregatedStats.setUidState(APP_UID1, STATE_PROCESS_STATE, PROCESS_STATE_FOREGROUND_SERVICE,
                5000);

        mBluetoothActivityEnergyInfo = mockBluetoothActivityEnergyInfo(1100, 6600, 1100, 2200,
                mockUidTraffic(APP_UID1, 1100, 2200),
                mockUidTraffic(APP_UID2, 3300, 4400));

        // Total scan time exceeding data transfer times
        mUidScanTimes.clear();
        mUidScanTimes.put(APP_UID1, 3100);
        mUidScanTimes.put(APP_UID2, 5000);

        mStatsRule.setTime(10_000, 10_000);

        aggregatedStats.addPowerStats(collector.collectStats(), 10_000);

        aggregatedStats.finish(10_000);

        BluetoothPowerStatsLayout statsLayout =
                new BluetoothPowerStatsLayout(aggregatedStats.getPowerStatsDescriptor());

        // RX power = 'rx-duration * PowerProfile[bluetooth.controller.rx]`
        //        RX power = 6000 * 50 = 300000 mA-ms = 0.083333 mAh
        // TX power = 'tx-duration * PowerProfile[bluetooth.controller.tx]`
        //        TX power = 1000 * 100 = 100000 mA-ms = 0.02777 mAh
        // Idle power = 'idle-duration * PowerProfile[bluetooth.controller.idle]`
        //        Idle power = 2000 * 10 = 20000 mA-ms = 0.00555 mAh
        // Total power = RX + TX + Idle = 0.116666
        double expectedPower = 0.116666;
        long[] deviceStats = new long[aggregatedStats.getPowerStatsDescriptor().statsArrayLength];
        aggregatedStats.getDeviceStats(deviceStats, states(POWER_STATE_OTHER, SCREEN_STATE_ON));
        assertThat(statsLayout.getDevicePowerEstimate(deviceStats))
                .isWithin(PRECISION).of(expectedPower * 0.25);

        aggregatedStats.getDeviceStats(deviceStats, states(POWER_STATE_OTHER, SCREEN_STATE_OTHER));
        assertThat(statsLayout.getDevicePowerEstimate(deviceStats))
                .isWithin(PRECISION).of(expectedPower * 0.75);

        // UID1 =
        //     (3000 / 8000) * 0.083333        // rx
        //   + (3000 / 8000) * 0.027777        // tx
        //   = 0.041666 mAh
        double expectedPower1 = 0.041666;
        long[] uidStats = new long[aggregatedStats.getPowerStatsDescriptor().uidStatsArrayLength];
        aggregatedStats.getUidStats(uidStats, APP_UID1,
                states(POWER_STATE_OTHER, SCREEN_STATE_ON, PROCESS_STATE_FOREGROUND));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(expectedPower1 * 0.25);

        aggregatedStats.getUidStats(uidStats, APP_UID1,
                states(POWER_STATE_OTHER, SCREEN_STATE_OTHER, PROCESS_STATE_BACKGROUND));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(expectedPower1 * 0.25);

        aggregatedStats.getUidStats(uidStats, APP_UID1,
                states(POWER_STATE_OTHER, SCREEN_STATE_OTHER, PROCESS_STATE_FOREGROUND_SERVICE));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(expectedPower1 * 0.5);

        // UID2 =
        //     (5000 / 8000) * 0.083333        // rx
        //   + (5000 / 8000) * 0.027777        // tx
        //   = 0.069443 mAh
        double expectedPower2 = 0.069443;
        aggregatedStats.getUidStats(uidStats, APP_UID2,
                states(POWER_STATE_OTHER, SCREEN_STATE_ON, PROCESS_STATE_CACHED));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(expectedPower2 * 0.25);

        aggregatedStats.getUidStats(uidStats, APP_UID2,
                states(POWER_STATE_OTHER, SCREEN_STATE_OTHER, PROCESS_STATE_CACHED));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(expectedPower2 * 0.75);
    }

    @Test
    public void consumedEnergyModel() {
        when(mConsumedEnergyRetriever.getVoltageMv()).thenReturn(VOLTAGE_MV);
        // Power monitoring hardware exists
        when(mConsumedEnergyRetriever.getEnergyConsumerIds(EnergyConsumerType.BLUETOOTH))
                .thenReturn(new int[]{BLUETOOTH_ENERGY_CONSUMER_ID});

        PowerComponentAggregatedPowerStats aggregatedStats = createAggregatedPowerStats(
                () -> new BluetoothPowerStatsProcessor(mStatsRule.getPowerProfile()));

        BluetoothPowerStatsCollector collector = new BluetoothPowerStatsCollector(mInjector);
        collector.setEnabled(true);
        mBluetoothActivityEnergyInfo = mockBluetoothActivityEnergyInfo(1000, 600, 100, 200,
                mockUidTraffic(APP_UID1, 100, 200),
                mockUidTraffic(APP_UID2, 300, 400));

        mUidScanTimes.put(APP_UID1, 100);

        when(mConsumedEnergyRetriever.getConsumedEnergy(new int[]{BLUETOOTH_ENERGY_CONSUMER_ID}))
                .thenReturn(new EnergyConsumerResult[]{mockEnergyConsumer(0)});

        aggregatedStats.start(0);

        // Establish a baseline
        aggregatedStats.addPowerStats(collector.collectStats(), 0);

        // Turn the screen off after 2.5 seconds
        aggregatedStats.setState(STATE_SCREEN, SCREEN_STATE_OTHER, 2500);
        aggregatedStats.setUidState(APP_UID1, STATE_PROCESS_STATE, PROCESS_STATE_BACKGROUND, 2500);
        aggregatedStats.setUidState(APP_UID1, STATE_PROCESS_STATE, PROCESS_STATE_FOREGROUND_SERVICE,
                5000);

        mBluetoothActivityEnergyInfo = mockBluetoothActivityEnergyInfo(1100, 6600, 1100, 2200,
                mockUidTraffic(APP_UID1, 1100, 2200),
                mockUidTraffic(APP_UID2, 3300, 4400));

        mUidScanTimes.clear();
        mUidScanTimes.put(APP_UID1, 200);
        mUidScanTimes.put(APP_UID2, 300);

        mStatsRule.setTime(10_000, 10_000);

        // 10 mAh represented as microWattSeconds
        long energyUws = 10 * 3600 * VOLTAGE_MV;
        when(mConsumedEnergyRetriever.getConsumedEnergy(new int[]{BLUETOOTH_ENERGY_CONSUMER_ID}))
                .thenReturn(new EnergyConsumerResult[]{mockEnergyConsumer(energyUws)});

        aggregatedStats.addPowerStats(collector.collectStats(), 10_000);

        aggregatedStats.finish(10_000);

        BluetoothPowerStatsLayout statsLayout =
                new BluetoothPowerStatsLayout(aggregatedStats.getPowerStatsDescriptor());

        // All estimates are computed as in the #powerProfileModel_mostlyDataTransfer test,
        // except they are all scaled by the same ratio to ensure that the total estimated
        // energy is equal to the measured energy
        double expectedPower = 10;
        long[] deviceStats = new long[aggregatedStats.getPowerStatsDescriptor().statsArrayLength];
        aggregatedStats.getDeviceStats(deviceStats, states(POWER_STATE_OTHER, SCREEN_STATE_ON));
        assertThat(statsLayout.getDevicePowerEstimate(deviceStats))
                .isWithin(PRECISION).of(expectedPower * 0.25);

        aggregatedStats.getDeviceStats(deviceStats, states(POWER_STATE_OTHER, SCREEN_STATE_OTHER));
        assertThat(statsLayout.getDevicePowerEstimate(deviceStats))
                .isWithin(PRECISION).of(expectedPower * 0.75);

        // UID1
        //   0.030092           // power profile model estimate
        //   0.116666           // power profile model estimate for total power
        //   10                 // total consumed energy
        //   = 0.030092 * (10 / 0.116666) = 2.579365
        double expectedPower1 = 2.579365;
        long[] uidStats = new long[aggregatedStats.getPowerStatsDescriptor().uidStatsArrayLength];
        aggregatedStats.getUidStats(uidStats, APP_UID1,
                states(POWER_STATE_OTHER, SCREEN_STATE_ON, PROCESS_STATE_FOREGROUND));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(expectedPower1 * 0.25);

        aggregatedStats.getUidStats(uidStats, APP_UID1,
                states(POWER_STATE_OTHER, SCREEN_STATE_OTHER, PROCESS_STATE_BACKGROUND));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(expectedPower1 * 0.25);

        aggregatedStats.getUidStats(uidStats, APP_UID1,
                states(POWER_STATE_OTHER, SCREEN_STATE_OTHER, PROCESS_STATE_FOREGROUND_SERVICE));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(expectedPower1 * 0.5);

        // UID2 =
        //   0.08102            // power profile model estimate
        //   0.116666           // power profile model estimate for total power
        //   10                 // total consumed energy
        //   = 0.08102 * (10 / 0.116666) = 6.944444
        double expectedPower2 = 6.944444;
        aggregatedStats.getUidStats(uidStats, APP_UID2,
                states(POWER_STATE_OTHER, SCREEN_STATE_ON, PROCESS_STATE_CACHED));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(expectedPower2 * 0.25);

        aggregatedStats.getUidStats(uidStats, APP_UID2,
                states(POWER_STATE_OTHER, SCREEN_STATE_OTHER, PROCESS_STATE_CACHED));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(expectedPower2 * 0.75);
    }

    private static PowerComponentAggregatedPowerStats createAggregatedPowerStats(
            Supplier<PowerStatsProcessor> processorSupplier) {
        AggregatedPowerStatsConfig config = new AggregatedPowerStatsConfig();
        config.trackPowerComponent(BatteryConsumer.POWER_COMPONENT_BLUETOOTH)
                .trackDeviceStates(STATE_POWER, STATE_SCREEN)
                .trackUidStates(STATE_POWER, STATE_SCREEN, STATE_PROCESS_STATE)
                .setProcessorSupplier(processorSupplier);

        PowerComponentAggregatedPowerStats aggregatedStats =
                new AggregatedPowerStats(config).getPowerComponentStats(
                        BatteryConsumer.POWER_COMPONENT_BLUETOOTH);

        aggregatedStats.setState(STATE_POWER, POWER_STATE_OTHER, 0);
        aggregatedStats.setState(STATE_SCREEN, SCREEN_STATE_ON, 0);
        aggregatedStats.setUidState(APP_UID1, STATE_PROCESS_STATE, PROCESS_STATE_FOREGROUND, 0);
        aggregatedStats.setUidState(APP_UID2, STATE_PROCESS_STATE, PROCESS_STATE_CACHED, 0);

        return aggregatedStats;
    }

    private int[] states(int... states) {
        return states;
    }

    private EnergyConsumerResult mockEnergyConsumer(long energyUWs) {
        EnergyConsumerResult ecr = new EnergyConsumerResult();
        ecr.energyUWs = energyUWs;
        return ecr;
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
