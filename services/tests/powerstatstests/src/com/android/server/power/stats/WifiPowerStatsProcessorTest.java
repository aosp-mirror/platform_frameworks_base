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

import static android.net.NetworkStats.DEFAULT_NETWORK_NO;
import static android.net.NetworkStats.METERED_NO;
import static android.net.NetworkStats.ROAMING_NO;
import static android.os.BatteryConsumer.PROCESS_STATE_BACKGROUND;
import static android.os.BatteryConsumer.PROCESS_STATE_CACHED;
import static android.os.BatteryConsumer.PROCESS_STATE_FOREGROUND;
import static android.os.BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE;

import static com.android.server.power.stats.AggregatedPowerStatsConfig.POWER_STATE_OTHER;
import static com.android.server.power.stats.AggregatedPowerStatsConfig.SCREEN_STATE_ON;
import static com.android.server.power.stats.AggregatedPowerStatsConfig.SCREEN_STATE_OTHER;
import static com.android.server.power.stats.AggregatedPowerStatsConfig.STATE_POWER;
import static com.android.server.power.stats.AggregatedPowerStatsConfig.STATE_PROCESS_STATE;
import static com.android.server.power.stats.AggregatedPowerStatsConfig.STATE_SCREEN;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.power.stats.EnergyConsumerType;
import android.net.NetworkStats;
import android.net.wifi.WifiManager;
import android.os.BatteryConsumer;
import android.os.Handler;
import android.os.Process;
import android.os.connectivity.WifiActivityEnergyInfo;
import android.platform.test.ravenwood.RavenwoodRule;
import android.util.SparseArray;

import com.android.internal.os.Clock;
import com.android.internal.os.PowerProfile;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public class WifiPowerStatsProcessorTest {
    @Rule(order = 0)
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true)
            .build();

    private static final double PRECISION = 0.00001;
    private static final int APP_UID1 = Process.FIRST_APPLICATION_UID + 42;
    private static final int APP_UID2 = Process.FIRST_APPLICATION_UID + 101;
    private static final int WIFI_ENERGY_CONSUMER_ID = 1;
    private static final int VOLTAGE_MV = 3500;

    @Rule(order = 1)
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule()
            .setAveragePower(PowerProfile.POWER_WIFI_CONTROLLER_IDLE, 360.0)
            .setAveragePower(PowerProfile.POWER_WIFI_CONTROLLER_RX, 480.0)
            .setAveragePower(PowerProfile.POWER_WIFI_CONTROLLER_TX, 720.0)
            .setAveragePower(PowerProfile.POWER_WIFI_ACTIVE, 360.0)
            .setAveragePower(PowerProfile.POWER_WIFI_SCAN, 480.0)
            .setAveragePower(PowerProfile.POWER_WIFI_BATCHED_SCAN, 720.0)
            .initMeasuredEnergyStatsLocked();

    @Mock
    private Context mContext;
    @Mock
    private PowerStatsUidResolver mPowerStatsUidResolver;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private PowerStatsCollector.ConsumedEnergyRetriever mConsumedEnergyRetriever;
    @Mock
    private Supplier<NetworkStats> mNetworkStatsSupplier;
    @Mock
    private WifiManager mWifiManager;

    private static class ScanTimes {
        public long scanTimeMs;
        public long batchScanTimeMs;
    }

    private final SparseArray<ScanTimes> mScanTimes = new SparseArray<>();
    private long mWifiActiveDuration;

    private final WifiPowerStatsCollector.WifiStatsRetriever mWifiStatsRetriever =
            new WifiPowerStatsCollector.WifiStatsRetriever() {
        @Override
        public void retrieveWifiScanTimes(Callback callback) {
            for (int i = 0; i < mScanTimes.size(); i++) {
                int uid = mScanTimes.keyAt(i);
                ScanTimes scanTimes = mScanTimes.valueAt(i);
                callback.onWifiScanTime(uid, scanTimes.scanTimeMs, scanTimes.batchScanTimeMs);
            }
        }

        @Override
        public long getWifiActiveDuration() {
            return mWifiActiveDuration;
        }
    };

    private final WifiPowerStatsCollector.Injector mInjector =
            new WifiPowerStatsCollector.Injector() {
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
                    return () -> VOLTAGE_MV;
                }

                @Override
                public Supplier<NetworkStats> getWifiNetworkStatsSupplier() {
                    return mNetworkStatsSupplier;
                }

                @Override
                public WifiManager getWifiManager() {
                    return mWifiManager;
                }

                @Override
                public WifiPowerStatsCollector.WifiStatsRetriever getWifiStatsRetriever() {
                    return mWifiStatsRetriever;
                }
            };

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)).thenReturn(true);
        when(mPowerStatsUidResolver.mapUid(anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    public void powerProfileModel_powerController() {
        when(mWifiManager.isEnhancedPowerReportingSupported()).thenReturn(true);

        // No power monitoring hardware
        when(mConsumedEnergyRetriever.getEnergyConsumerIds(EnergyConsumerType.WIFI))
                .thenReturn(new int[0]);

        WifiPowerStatsProcessor processor =
                new WifiPowerStatsProcessor(mStatsRule.getPowerProfile());

        PowerComponentAggregatedPowerStats aggregatedStats = createAggregatedPowerStats(processor);

        WifiPowerStatsCollector collector = new WifiPowerStatsCollector(mInjector);
        collector.setEnabled(true);

        // Initial empty WifiActivityEnergyInfo.
        mockWifiActivityEnergyInfo(new WifiActivityEnergyInfo(0L,
                WifiActivityEnergyInfo.STACK_STATE_INVALID, 0L, 0L, 0L, 0L));

        // Establish a baseline
        aggregatedStats.addPowerStats(collector.collectStats(), 0);

        // Turn the screen off after 2.5 seconds
        aggregatedStats.setState(STATE_SCREEN, SCREEN_STATE_OTHER, 2500);
        aggregatedStats.setUidState(APP_UID1, STATE_PROCESS_STATE, PROCESS_STATE_BACKGROUND, 2500);
        aggregatedStats.setUidState(APP_UID1, STATE_PROCESS_STATE, PROCESS_STATE_FOREGROUND_SERVICE,
                5000);

        // Note application network activity
        NetworkStats networkStats = mockNetworkStats(10000, 1,
                mockNetworkStatsEntry("wifi", APP_UID1, 0, 0,
                        METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 10000, 1500, 20000, 300, 100),
                mockNetworkStatsEntry("wifi", APP_UID2, 0, 0,
                        METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 5000, 500, 3000, 100, 111));
        when(mNetworkStatsSupplier.get()).thenReturn(networkStats);

        mockWifiScanTimes(APP_UID1, 300, 400);
        mockWifiScanTimes(APP_UID2, 100, 200);

        mockWifiActivityEnergyInfo(new WifiActivityEnergyInfo(10000,
                WifiActivityEnergyInfo.STACK_STATE_STATE_ACTIVE, 2000, 3000, 100, 600));

        mStatsRule.setTime(10_000, 10_000);

        aggregatedStats.addPowerStats(collector.collectStats(), 10_000);

        processor.finish(aggregatedStats, 10_000);

        WifiPowerStatsLayout statsLayout =
                new WifiPowerStatsLayout(aggregatedStats.getPowerStatsDescriptor());

        // RX power = 'rx-duration * PowerProfile[wifi.controller.rx]`
        //        RX power = 3000 * 480 = 1440000 mA-ms = 0.4 mAh
        // TX power = 'tx-duration * PowerProfile[wifi.controller.tx]`
        //        TX power = 2000 * 720 = 1440000 mA-ms = 0.4 mAh
        // Scan power = 'scan-duration * PowerProfile[wifi.scan]`
        //        Scan power = 100 * 480 = 48000 mA-ms = 0.013333 mAh
        // Idle power = 'idle-duration * PowerProfile[wifi.idle]`
        //        Idle power = 600 * 360 = 216000 mA-ms = 0.06 mAh
        // Total power = RX + TX + Scan + Idle = 0.873333
        // Screen-on  - 25%
        // Screen-off - 75%
        double expectedPower = 0.873333;
        long[] deviceStats = new long[aggregatedStats.getPowerStatsDescriptor().statsArrayLength];
        aggregatedStats.getDeviceStats(deviceStats, states(POWER_STATE_OTHER, SCREEN_STATE_ON));
        assertThat(statsLayout.getDevicePowerEstimate(deviceStats))
                .isWithin(PRECISION).of(expectedPower * 0.25);

        aggregatedStats.getDeviceStats(deviceStats, states(POWER_STATE_OTHER, SCREEN_STATE_OTHER));
        assertThat(statsLayout.getDevicePowerEstimate(deviceStats))
                .isWithin(PRECISION).of(expectedPower * 0.75);

        // UID1 =
        //     (1500 / 2000) * 0.4        // rx
        //     + (300 / 400) * 0.4        // tx
        //     + (700 / 1000) * 0.013333  // scan (basic + batched)
        //   = 0.609333 mAh
        double expectedPower1 = 0.609333;
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
        //     (500 / 2000) * 0.4         // rx
        //     + (100 / 400) * 0.4        // tx
        //     + (300 / 1000) * 0.013333  // scan (basic + batched)
        //   = 0.204 mAh
        double expectedPower2 = 0.204;
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
    public void consumedEnergyModel_powerController() {
        when(mWifiManager.isEnhancedPowerReportingSupported()).thenReturn(true);

        // PowerStats hardware is available
        when(mConsumedEnergyRetriever.getEnergyConsumerIds(EnergyConsumerType.WIFI))
                .thenReturn(new int[] {WIFI_ENERGY_CONSUMER_ID});

        WifiPowerStatsProcessor processor =
                new WifiPowerStatsProcessor(mStatsRule.getPowerProfile());

        PowerComponentAggregatedPowerStats aggregatedStats = createAggregatedPowerStats(processor);

        WifiPowerStatsCollector collector = new WifiPowerStatsCollector(mInjector);
        collector.setEnabled(true);

        // Initial empty WifiActivityEnergyInfo.
        mockWifiActivityEnergyInfo(new WifiActivityEnergyInfo(0L,
                WifiActivityEnergyInfo.STACK_STATE_INVALID, 0L, 0L, 0L, 0L));

        when(mConsumedEnergyRetriever.getConsumedEnergyUws(
                new int[]{WIFI_ENERGY_CONSUMER_ID}))
                .thenReturn(new long[]{0});

        // Establish a baseline
        aggregatedStats.addPowerStats(collector.collectStats(), 0);

        // Turn the screen off after 2.5 seconds
        aggregatedStats.setState(STATE_SCREEN, SCREEN_STATE_OTHER, 2500);
        aggregatedStats.setUidState(APP_UID1, STATE_PROCESS_STATE, PROCESS_STATE_BACKGROUND, 2500);
        aggregatedStats.setUidState(APP_UID1, STATE_PROCESS_STATE, PROCESS_STATE_FOREGROUND_SERVICE,
                5000);

        // Note application network activity
        NetworkStats networkStats = mockNetworkStats(10000, 1,
                mockNetworkStatsEntry("wifi", APP_UID1, 0, 0,
                        METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 10000, 1500, 20000, 300, 100),
                mockNetworkStatsEntry("wifi", APP_UID2, 0, 0,
                        METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 5000, 500, 3000, 100, 111));
        when(mNetworkStatsSupplier.get()).thenReturn(networkStats);

        mockWifiScanTimes(APP_UID1, 300, 400);
        mockWifiScanTimes(APP_UID2, 100, 200);

        mockWifiActivityEnergyInfo(new WifiActivityEnergyInfo(10000,
                WifiActivityEnergyInfo.STACK_STATE_STATE_ACTIVE, 2000, 3000, 100, 600));

        mStatsRule.setTime(10_000, 10_000);

        // 10 mAh represented as microWattSeconds
        long energyUws = 10 * 3600 * VOLTAGE_MV;
        when(mConsumedEnergyRetriever.getConsumedEnergyUws(
                new int[]{WIFI_ENERGY_CONSUMER_ID})).thenReturn(new long[]{energyUws});

        aggregatedStats.addPowerStats(collector.collectStats(), 10_000);

        processor.finish(aggregatedStats, 10_000);

        WifiPowerStatsLayout statsLayout =
                new WifiPowerStatsLayout(aggregatedStats.getPowerStatsDescriptor());

        // All estimates are computed as in the #powerProfileModel_powerController test,
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
        //   0.609333           // power profile model estimate
        //   0.873333           // power profile model estimate for total power
        //   10                 // total consumed energy
        //   = 0.609333 * (10 / 0.873333) = 6.9771
        double expectedPower1 = 6.9771;
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

        // UID2
        //   0.204              // power profile model estimate
        //   0.873333           // power profile model estimate for total power
        //   10                 // total consumed energy
        //   = 0.204 * (10 / 0.873333) = 2.33588
        double expectedPower2 = 2.33588;
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
    public void powerProfileModel_noPowerController() {
        when(mWifiManager.isEnhancedPowerReportingSupported()).thenReturn(false);

        // No power monitoring hardware
        when(mConsumedEnergyRetriever.getEnergyConsumerIds(EnergyConsumerType.WIFI))
                .thenReturn(new int[0]);

        WifiPowerStatsProcessor processor =
                new WifiPowerStatsProcessor(mStatsRule.getPowerProfile());

        PowerComponentAggregatedPowerStats aggregatedStats = createAggregatedPowerStats(processor);

        WifiPowerStatsCollector collector = new WifiPowerStatsCollector(mInjector);
        collector.setEnabled(true);

        // Establish a baseline
        aggregatedStats.addPowerStats(collector.collectStats(), 0);

        // Turn the screen off after 2.5 seconds
        aggregatedStats.setState(STATE_SCREEN, SCREEN_STATE_OTHER, 2500);
        aggregatedStats.setUidState(APP_UID1, STATE_PROCESS_STATE, PROCESS_STATE_BACKGROUND, 2500);
        aggregatedStats.setUidState(APP_UID1, STATE_PROCESS_STATE, PROCESS_STATE_FOREGROUND_SERVICE,
                5000);

        // Note application network activity
        NetworkStats networkStats = mockNetworkStats(10000, 1,
                mockNetworkStatsEntry("wifi", APP_UID1, 0, 0,
                        METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 10000, 1500, 20000, 300, 100),
                mockNetworkStatsEntry("wifi", APP_UID2, 0, 0,
                        METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 5000, 500, 3000, 100, 111));
        when(mNetworkStatsSupplier.get()).thenReturn(networkStats);

        mScanTimes.clear();
        mWifiActiveDuration = 8000;
        mockWifiScanTimes(APP_UID1, 300, 400);
        mockWifiScanTimes(APP_UID2, 100, 200);

        mStatsRule.setTime(10_000, 10_000);

        aggregatedStats.addPowerStats(collector.collectStats(), 10_000);

        processor.finish(aggregatedStats, 10_000);

        WifiPowerStatsLayout statsLayout =
                new WifiPowerStatsLayout(aggregatedStats.getPowerStatsDescriptor());

        // Total active power = 'active-duration * PowerProfile[wifi.on]`
        //        active = 8000 * 360 = 2880000 mA-ms = 0.8 mAh
        // UID1 rxPackets + txPackets = 1800
        // UID2 rxPackets + txPackets = 600
        // Total rx+tx packets = 2400
        // Total scan power = `scan-duration * PowerProfile[wifi.scan]`
        //        scan = (100 + 300) * 480 = 192000 mA-ms = 0.05333 mAh
        // Total batch scan power = `(200 + 400) * PowerProfile[wifi.batchedscan]`
        //        bscan = (200 + 400) * 720 = 432000 mA-ms = 0.12 mAh
        //
        // Expected power = active + scan + bscan = 0.97333
        double expectedPower = 0.97333;
        long[] deviceStats = new long[aggregatedStats.getPowerStatsDescriptor().statsArrayLength];
        aggregatedStats.getDeviceStats(deviceStats, states(POWER_STATE_OTHER, SCREEN_STATE_ON));
        assertThat(statsLayout.getDevicePowerEstimate(deviceStats))
                .isWithin(PRECISION).of(expectedPower * 0.25);

        aggregatedStats.getDeviceStats(deviceStats, states(POWER_STATE_OTHER, SCREEN_STATE_OTHER));
        assertThat(statsLayout.getDevicePowerEstimate(deviceStats))
                .isWithin(PRECISION).of(expectedPower * 0.75);

        // UID1 =
        //     (1800 / 2400) * 0.8      // active
        //     + (300 / 400) * 0.05333  // scan
        //     + (400 / 600) * 0.12     // batched scan
        //   = 0.72 mAh
        double expectedPower1 = 0.72;
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
        //     (600 / 2400) * 0.8       // active
        //     + (100 / 400) * 0.05333  // scan
        //     + (200 / 600) * 0.12     // batched scan
        //   = 0.253333 mAh
        double expectedPower2 = 0.25333;
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
            WifiPowerStatsProcessor processor) {
        AggregatedPowerStatsConfig.PowerComponent config =
                new AggregatedPowerStatsConfig.PowerComponent(BatteryConsumer.POWER_COMPONENT_WIFI)
                        .trackDeviceStates(STATE_POWER, STATE_SCREEN)
                        .trackUidStates(STATE_POWER, STATE_SCREEN, STATE_PROCESS_STATE)
                        .setProcessor(processor);

        PowerComponentAggregatedPowerStats aggregatedStats =
                new PowerComponentAggregatedPowerStats(
                        new AggregatedPowerStats(mock(AggregatedPowerStatsConfig.class)), config);

        aggregatedStats.setState(STATE_POWER, POWER_STATE_OTHER, 0);
        aggregatedStats.setState(STATE_SCREEN, SCREEN_STATE_ON, 0);
        aggregatedStats.setUidState(APP_UID1, STATE_PROCESS_STATE, PROCESS_STATE_FOREGROUND, 0);
        aggregatedStats.setUidState(APP_UID2, STATE_PROCESS_STATE, PROCESS_STATE_CACHED, 0);

        return aggregatedStats;
    }

    private int[] states(int... states) {
        return states;
    }

    private void mockWifiActivityEnergyInfo(WifiActivityEnergyInfo waei) {
        doAnswer(invocation -> {
            WifiManager.OnWifiActivityEnergyInfoListener
                    listener = invocation.getArgument(1);
            listener.onWifiActivityEnergyInfo(waei);
            return null;
        }).when(mWifiManager).getWifiActivityEnergyInfoAsync(any(), any());
    }

    private NetworkStats mockNetworkStats(int elapsedTime, int initialSize,
            NetworkStats.Entry... entries) {
        NetworkStats stats;
        if (RavenwoodRule.isOnRavenwood()) {
            stats = mock(NetworkStats.class);
            when(stats.iterator()).thenAnswer(inv -> List.of(entries).iterator());
        } else {
            stats = new NetworkStats(elapsedTime, initialSize);
            for (NetworkStats.Entry entry : entries) {
                stats = stats.addEntry(entry);
            }
        }
        return stats;
    }

    private static NetworkStats.Entry mockNetworkStatsEntry(@Nullable String iface, int uid,
            int set, int tag, int metered, int roaming, int defaultNetwork, long rxBytes,
            long rxPackets, long txBytes, long txPackets, long operations) {
        if (RavenwoodRule.isOnRavenwood()) {
            NetworkStats.Entry entry = mock(NetworkStats.Entry.class);
            when(entry.getUid()).thenReturn(uid);
            when(entry.getMetered()).thenReturn(metered);
            when(entry.getRoaming()).thenReturn(roaming);
            when(entry.getDefaultNetwork()).thenReturn(defaultNetwork);
            when(entry.getRxBytes()).thenReturn(rxBytes);
            when(entry.getRxPackets()).thenReturn(rxPackets);
            when(entry.getTxBytes()).thenReturn(txBytes);
            when(entry.getTxPackets()).thenReturn(txPackets);
            when(entry.getOperations()).thenReturn(operations);
            return entry;
        } else {
            return new NetworkStats.Entry(iface, uid, set, tag, metered,
                    roaming, defaultNetwork, rxBytes, rxPackets, txBytes, txPackets, operations);
        }
    }

    private void mockWifiScanTimes(int uid, long scanTimeMs, long batchScanTimeMs) {
        ScanTimes scanTimes = new ScanTimes();
        scanTimes.scanTimeMs = scanTimeMs;
        scanTimes.batchScanTimeMs = batchScanTimeMs;
        mScanTimes.put(uid, scanTimes);
    }
}
