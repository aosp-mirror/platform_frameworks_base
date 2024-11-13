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

import static android.net.NetworkStats.DEFAULT_NETWORK_NO;
import static android.net.NetworkStats.METERED_NO;
import static android.net.NetworkStats.ROAMING_NO;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.power.stats.EnergyConsumerResult;
import android.hardware.power.stats.EnergyConsumerType;
import android.net.NetworkStats;
import android.os.BatteryConsumer;
import android.os.Handler;
import android.os.OutcomeReceiver;
import android.os.Process;
import android.platform.test.ravenwood.RavenwoodRule;
import android.telephony.ModemActivityInfo;
import android.telephony.TelephonyManager;

import com.android.internal.os.Clock;
import com.android.internal.os.PowerStats;
import com.android.server.power.stats.BatteryUsageStatsRule;
import com.android.server.power.stats.MobileRadioPowerStatsCollector;
import com.android.server.power.stats.PowerStatsCollector;
import com.android.server.power.stats.PowerStatsUidResolver;
import com.android.server.power.stats.format.MobileRadioPowerStatsLayout;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public class MobileRadioPowerStatsProcessorTest {
    @Rule(order = 0)
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true)
            .build();

    private static final double PRECISION = 0.00001;
    private static final int APP_UID = Process.FIRST_APPLICATION_UID + 42;
    private static final int APP_UID2 = Process.FIRST_APPLICATION_UID + 101;
    private static final int MOBILE_RADIO_ENERGY_CONSUMER_ID = 1;
    private static final int VOLTAGE_MV = 3500;

    @Rule(order = 1)
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule();
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
    private TelephonyManager mTelephonyManager;
    @Mock
    private LongSupplier mCallDurationSupplier;
    @Mock
    private LongSupplier mScanDurationSupplier;

    private final MobileRadioPowerStatsCollector.Injector mInjector =
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
                public Supplier<NetworkStats> getMobileNetworkStatsSupplier() {
                    return mNetworkStatsSupplier;
                }

                @Override
                public TelephonyManager getTelephonyManager() {
                    return mTelephonyManager;
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
        when(mPowerStatsUidResolver.mapUid(anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    public void powerProfileModel() {
        // No power monitoring hardware
        when(mConsumedEnergyRetriever.getEnergyConsumerIds(EnergyConsumerType.MOBILE_RADIO))
                .thenReturn(new int[0]);

        mStatsRule.setTestPowerProfile("power_profile_test_modem_calculator");

        AggregatedPowerStatsConfig config = new AggregatedPowerStatsConfig();
        config.trackPowerComponent(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO)
                .trackDeviceStates(STATE_POWER, STATE_SCREEN)
                .trackUidStates(STATE_POWER, STATE_SCREEN, STATE_PROCESS_STATE)
                .setProcessorSupplier(
                        () -> new MobileRadioPowerStatsProcessor(mStatsRule.getPowerProfile()));

        PowerComponentAggregatedPowerStats aggregatedStats = new AggregatedPowerStats(config)
                .getPowerComponentStats(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO);

        aggregatedStats.setState(STATE_POWER, POWER_STATE_OTHER, 0);
        aggregatedStats.setState(STATE_SCREEN, SCREEN_STATE_ON, 0);
        aggregatedStats.setUidState(APP_UID, STATE_PROCESS_STATE, PROCESS_STATE_FOREGROUND, 0);
        aggregatedStats.setUidState(APP_UID2, STATE_PROCESS_STATE, PROCESS_STATE_CACHED, 0);

        MobileRadioPowerStatsCollector collector =
                new MobileRadioPowerStatsCollector(mInjector, null);
        collector.setEnabled(true);

        // Initial empty ModemActivityInfo.
        mockModemActivityInfo(new ModemActivityInfo(0L, 0L, 0L, new int[5], 0L));

        aggregatedStats.start(0);

        // Establish a baseline
        aggregatedStats.addPowerStats(collector.collectStats(), 0);

        // Turn the screen off after 2.5 seconds
        aggregatedStats.setState(STATE_SCREEN, SCREEN_STATE_OTHER, 2500);
        aggregatedStats.setUidState(APP_UID, STATE_PROCESS_STATE, PROCESS_STATE_BACKGROUND, 2500);
        aggregatedStats.setUidState(APP_UID, STATE_PROCESS_STATE, PROCESS_STATE_FOREGROUND_SERVICE,
                5000);

        // Note application network activity
        NetworkStats networkStats = mockNetworkStats(10000, 1,
                mockNetworkStatsEntry("cellular", APP_UID, 0, 0,
                        METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 10000, 1500, 20000, 300, 100),
                mockNetworkStatsEntry("cellular", APP_UID2, 0, 0,
                        METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 5000, 500, 3000, 100, 111));

        when(mNetworkStatsSupplier.get()).thenReturn(networkStats);

        ModemActivityInfo mai = new ModemActivityInfo(10000, 2000, 3000,
                new int[]{100, 200, 300, 400, 500}, 600);
        mockModemActivityInfo(mai);

        when(mCallDurationSupplier.getAsLong()).thenReturn(200L);
        when(mScanDurationSupplier.getAsLong()).thenReturn(5555L);

        mStatsRule.setTime(10_000, 10_000);

        PowerStats powerStats = collector.collectStats();

        aggregatedStats.addPowerStats(powerStats, 10_000);

        aggregatedStats.finish(10_000);

        MobileRadioPowerStatsLayout statsLayout =
                new MobileRadioPowerStatsLayout(aggregatedStats.getPowerStatsDescriptor());

        //    720 mA * 100 ms  (level 0 TX drain rate * level 0 TX duration)
        // + 1080 mA * 200 ms  (level 1 TX drain rate * level 1 TX duration)
        // + 1440 mA * 300 ms  (level 2 TX drain rate * level 2 TX duration)
        // + 1800 mA * 400 ms  (level 3 TX drain rate * level 3 TX duration)
        // + 2160 mA * 500 ms  (level 4 TX drain rate * level 4 TX duration)
        // + 1440 mA * 600 ms  (RX drain rate * RX duration)
        // +  360 mA * 3000 ms (idle drain rate * idle duration)
        // +   70 mA * 2000 ms (sleep drain rate * sleep duration)
        // _________________
        // =    4604000 mA-ms or 1.27888 mA-h
        //   25% of 1.27888 = 0.319722
        //   75% of 1.27888 = 0.959166
        double totalPower = 0;
        long[] deviceStats = new long[aggregatedStats.getPowerStatsDescriptor().statsArrayLength];
        aggregatedStats.getDeviceStats(deviceStats, states(POWER_STATE_OTHER, SCREEN_STATE_ON));
        assertThat(statsLayout.getDevicePowerEstimate(deviceStats))
                .isWithin(PRECISION).of(0.319722);
        totalPower += statsLayout.getDevicePowerEstimate(deviceStats);

        aggregatedStats.getDeviceStats(deviceStats, states(POWER_STATE_OTHER, SCREEN_STATE_OTHER));
        assertThat(statsLayout.getDevicePowerEstimate(deviceStats))
                .isWithin(PRECISION).of(0.959166);
        totalPower += statsLayout.getDevicePowerEstimate(deviceStats);

        assertThat(totalPower).isWithin(PRECISION).of(1.27888);

        //    720 mA * 100 ms  (level 0 TX drain rate * level 0 TX duration)
        // + 1080 mA * 200 ms  (level 1 TX drain rate * level 1 TX duration)
        // + 1440 mA * 300 ms  (level 2 TX drain rate * level 2 TX duration)
        // + 1800 mA * 400 ms  (level 3 TX drain rate * level 3 TX duration)
        // + 2160 mA * 500 ms  (level 4 TX drain rate * level 4 TX duration)
        // + 1440 mA * 600 ms  (RX drain rate * RX duration)
        // _________________
        // =    3384000 mA-ms or 0.94 mA-h
        double uidPower1 = 0;
        long[] uidStats = new long[aggregatedStats.getPowerStatsDescriptor().uidStatsArrayLength];
        aggregatedStats.getUidStats(uidStats, APP_UID,
                states(POWER_STATE_OTHER, SCREEN_STATE_ON, PROCESS_STATE_FOREGROUND));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(0.17625);
        uidPower1 += statsLayout.getUidPowerEstimate(uidStats);

        aggregatedStats.getUidStats(uidStats, APP_UID,
                states(POWER_STATE_OTHER, SCREEN_STATE_OTHER, PROCESS_STATE_BACKGROUND));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(0.17625);
        uidPower1 += statsLayout.getUidPowerEstimate(uidStats);

        aggregatedStats.getUidStats(uidStats, APP_UID,
                states(POWER_STATE_OTHER, SCREEN_STATE_OTHER, PROCESS_STATE_FOREGROUND_SERVICE));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(0.3525);
        uidPower1 += statsLayout.getUidPowerEstimate(uidStats);

        double uidPower2 = 0;
        aggregatedStats.getUidStats(uidStats, APP_UID2,
                states(POWER_STATE_OTHER, SCREEN_STATE_ON, PROCESS_STATE_CACHED));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(0.05875);
        uidPower2 += statsLayout.getUidPowerEstimate(uidStats);

        aggregatedStats.getUidStats(uidStats, APP_UID2,
                states(POWER_STATE_OTHER, SCREEN_STATE_OTHER, PROCESS_STATE_CACHED));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(0.17625);
        uidPower2 += statsLayout.getUidPowerEstimate(uidStats);

        assertThat(uidPower1 + uidPower2)
                .isWithin(PRECISION).of(0.94);

        // 3/4 of total packets were sent by APP_UID so 75% of total
        assertThat(uidPower1 / (uidPower1 + uidPower2))
                .isWithin(PRECISION).of(0.75);
    }

    @Test
    public void energyConsumerModel() {
        PowerComponentAggregatedPowerStats aggregatedStats =
                prepareAggregatedStats_energyConsumerModel();

        MobileRadioPowerStatsLayout statsLayout =
                new MobileRadioPowerStatsLayout(aggregatedStats.getPowerStatsDescriptor());

        // 10_000_000 micro-Coulomb * 1/1000 milli/micro * 1/3600 hour/second = 2.77778 mAh
        double totalPower = 0;
        long[] deviceStats = new long[aggregatedStats.getPowerStatsDescriptor().statsArrayLength];
        aggregatedStats.getDeviceStats(deviceStats, states(POWER_STATE_OTHER, SCREEN_STATE_ON));
        assertThat(statsLayout.getDevicePowerEstimate(deviceStats))
                .isWithin(PRECISION).of(0.671837);
        totalPower += statsLayout.getDevicePowerEstimate(deviceStats);
        assertThat(statsLayout.getDeviceCallPowerEstimate(deviceStats))
                .isWithin(PRECISION).of(0.022494);
        totalPower += statsLayout.getDeviceCallPowerEstimate(deviceStats);

        aggregatedStats.getDeviceStats(deviceStats, states(POWER_STATE_OTHER, SCREEN_STATE_OTHER));
        assertThat(statsLayout.getDevicePowerEstimate(deviceStats))
                .isWithin(PRECISION).of(2.01596);
        totalPower += statsLayout.getDevicePowerEstimate(deviceStats);
        assertThat(statsLayout.getDeviceCallPowerEstimate(deviceStats))
                .isWithin(PRECISION).of(0.067484);
        totalPower += statsLayout.getDeviceCallPowerEstimate(deviceStats);

        // These estimates are supposed to add up to the measured energy, 2.77778 mAh
        assertThat(totalPower).isWithin(PRECISION).of(2.77778);

        double uidPower1 = 0;
        long[] uidStats = new long[aggregatedStats.getPowerStatsDescriptor().uidStatsArrayLength];
        aggregatedStats.getUidStats(uidStats, APP_UID,
                states(POWER_STATE_OTHER, SCREEN_STATE_ON, PROCESS_STATE_FOREGROUND));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(0.198236);
        uidPower1 += statsLayout.getUidPowerEstimate(uidStats);

        aggregatedStats.getUidStats(uidStats, APP_UID,
                states(POWER_STATE_OTHER, SCREEN_STATE_OTHER, PROCESS_STATE_BACKGROUND));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(0.198236);
        uidPower1 += statsLayout.getUidPowerEstimate(uidStats);

        aggregatedStats.getUidStats(uidStats, APP_UID,
                states(POWER_STATE_OTHER, SCREEN_STATE_OTHER, PROCESS_STATE_FOREGROUND_SERVICE));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(0.396473);
        uidPower1 += statsLayout.getUidPowerEstimate(uidStats);

        double uidPower2 = 0;
        aggregatedStats.getUidStats(uidStats, APP_UID2,
                states(POWER_STATE_OTHER, SCREEN_STATE_ON, PROCESS_STATE_CACHED));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(0.066078);
        uidPower2 += statsLayout.getUidPowerEstimate(uidStats);

        aggregatedStats.getUidStats(uidStats, APP_UID2,
                states(POWER_STATE_OTHER, SCREEN_STATE_OTHER, PROCESS_STATE_CACHED));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(0.198236);
        uidPower2 += statsLayout.getUidPowerEstimate(uidStats);

        // Total power attributed to apps is significantly less than the grand total,
        // because we only attribute TX/RX to apps but not maintaining a connection with the cell.
        assertThat(uidPower1 + uidPower2)
                .isWithin(PRECISION).of(1.057259);

        // 3/4 of total packets were sent by APP_UID so 75% of total RX/TX power is attributed to it
        assertThat(uidPower1 / (uidPower1 + uidPower2))
                .isWithin(PRECISION).of(0.75);
    }

    @Test
    public void test_toString() {
        PowerComponentAggregatedPowerStats stats = prepareAggregatedStats_energyConsumerModel();
        String string = stats.toString();
        assertThat(string).contains("(pwr-other scr-on)"
                + " sleep: 500 idle: 750 scan: 1388 call: 50 energy: 2500000 power: 0.672");
        assertThat(string).contains("(pwr-other scr-other)"
                + " sleep: 1500 idle: 2250 scan: 4166 call: 150 energy: 7500000 power: 2.02");
        assertThat(string).contains("(pwr-other scr-on other)"
                + " rx: 150 tx: [25, 50, 75, 100, 125]");
        assertThat(string).contains("(pwr-other scr-other other)"
                + " rx: 450 tx: [75, 150, 225, 300, 375]");
        assertThat(string).contains("(pwr-other scr-on fg)"
                + " rx-pkts: 375 rx-B: 2500 tx-pkts: 75 tx-B: 5000 power: 0.198");
        assertThat(string).contains("(pwr-other scr-other bg)"
                + " rx-pkts: 375 rx-B: 2500 tx-pkts: 75 tx-B: 5000 power: 0.198");
        assertThat(string).contains("(pwr-other scr-other fgs)"
                + " rx-pkts: 750 rx-B: 5000 tx-pkts: 150 tx-B: 10000 power: 0.396");
    }

    private PowerComponentAggregatedPowerStats prepareAggregatedStats_energyConsumerModel() {
        // PowerStats hardware is available
        when(mConsumedEnergyRetriever.getVoltageMv()).thenReturn(VOLTAGE_MV);
        when(mConsumedEnergyRetriever.getEnergyConsumerIds(EnergyConsumerType.MOBILE_RADIO))
                .thenReturn(new int[] {MOBILE_RADIO_ENERGY_CONSUMER_ID});

        mStatsRule.setTestPowerProfile("power_profile_test_legacy_modem")
                .initMeasuredEnergyStatsLocked();

        AggregatedPowerStatsConfig config = new AggregatedPowerStatsConfig();
        config.trackPowerComponent(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO)
                .trackDeviceStates(STATE_POWER, STATE_SCREEN)
                .trackUidStates(STATE_POWER, STATE_SCREEN, STATE_PROCESS_STATE)
                .setProcessorSupplier(
                        () -> new MobileRadioPowerStatsProcessor(mStatsRule.getPowerProfile()));

        PowerComponentAggregatedPowerStats aggregatedStats = new AggregatedPowerStats(config)
                .getPowerComponentStats(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO);

        aggregatedStats.setState(STATE_POWER, POWER_STATE_OTHER, 0);
        aggregatedStats.setState(STATE_SCREEN, SCREEN_STATE_ON, 0);
        aggregatedStats.setUidState(APP_UID, STATE_PROCESS_STATE, PROCESS_STATE_FOREGROUND, 0);
        aggregatedStats.setUidState(APP_UID2, STATE_PROCESS_STATE, PROCESS_STATE_CACHED, 0);

        MobileRadioPowerStatsCollector collector =
                new MobileRadioPowerStatsCollector(mInjector, null);
        collector.setEnabled(true);

        // Initial empty ModemActivityInfo.
        mockModemActivityInfo(new ModemActivityInfo(0L, 0L, 0L, new int[5], 0L));

        when(mConsumedEnergyRetriever.getConsumedEnergy(
                new int[]{MOBILE_RADIO_ENERGY_CONSUMER_ID}))
                .thenReturn(new EnergyConsumerResult[]{mockEnergyConsumer(0)});

        aggregatedStats.start(0);

        // Establish a baseline
        aggregatedStats.addPowerStats(collector.collectStats(), 0);

        // Turn the screen off after 2.5 seconds
        aggregatedStats.setState(STATE_SCREEN, SCREEN_STATE_OTHER, 2500);
        aggregatedStats.setUidState(APP_UID, STATE_PROCESS_STATE, PROCESS_STATE_BACKGROUND, 2500);
        aggregatedStats.setUidState(APP_UID, STATE_PROCESS_STATE, PROCESS_STATE_FOREGROUND_SERVICE,
                5000);

        // Note application network activity
        NetworkStats networkStats = mockNetworkStats(10000, 1,
                mockNetworkStatsEntry("cellular", APP_UID, 0, 0,
                        METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 10000, 1500, 20000, 300, 100),
                mockNetworkStatsEntry("cellular", APP_UID2, 0, 0,
                        METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, 5000, 500, 3000, 100, 111));

        when(mNetworkStatsSupplier.get()).thenReturn(networkStats);

        ModemActivityInfo mai = new ModemActivityInfo(10000, 2000, 3000,
                new int[]{100, 200, 300, 400, 500}, 600);
        mockModemActivityInfo(mai);

        mStatsRule.setTime(10_000, 10_000);

        long energyUws = 10_000_000L * VOLTAGE_MV / 1000L;
        when(mConsumedEnergyRetriever.getConsumedEnergy(new int[]{MOBILE_RADIO_ENERGY_CONSUMER_ID}))
                .thenReturn(new EnergyConsumerResult[]{mockEnergyConsumer(energyUws)});

        when(mCallDurationSupplier.getAsLong()).thenReturn(200L);
        when(mScanDurationSupplier.getAsLong()).thenReturn(5555L);

        PowerStats powerStats = collector.collectStats();

        aggregatedStats.addPowerStats(powerStats, 10_000);

        aggregatedStats.finish(10_000);
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

    private void mockModemActivityInfo(ModemActivityInfo emptyMai) {
        doAnswer(invocation -> {
            OutcomeReceiver<ModemActivityInfo, TelephonyManager.ModemActivityInfoException>
                    receiver = invocation.getArgument(1);
            receiver.onResult(emptyMai);
            return null;
        }).when(mTelephonyManager).requestModemActivityInfo(any(), any());
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
}
