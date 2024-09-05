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
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.power.stats.EnergyConsumerType;
import android.net.NetworkStats;
import android.os.BatteryConsumer;
import android.os.Handler;
import android.os.OutcomeReceiver;
import android.platform.test.ravenwood.RavenwoodRule;
import android.telephony.ModemActivityInfo;
import android.telephony.TelephonyManager;

import com.android.internal.os.Clock;
import com.android.server.power.stats.BatteryUsageStatsRule;
import com.android.server.power.stats.MobileRadioPowerStatsCollector;
import com.android.server.power.stats.PowerStatsCollector;
import com.android.server.power.stats.PowerStatsUidResolver;
import com.android.server.power.stats.format.PowerStatsLayout;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

public class PhoneCallPowerStatsProcessorTest {
    @Rule(order = 0)
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true)
            .build();

    private static final double PRECISION = 0.00001;
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

        // No power monitoring hardware
        when(mConsumedEnergyRetriever.getEnergyConsumerIds(EnergyConsumerType.MOBILE_RADIO))
                .thenReturn(new int[0]);

        mStatsRule.setTestPowerProfile("power_profile_test_legacy_modem");
    }

    @Test
    public void copyEstimatesFromMobileRadioPowerStats() {
        AggregatedPowerStatsConfig config = new AggregatedPowerStatsConfig();
        config.trackPowerComponent(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO)
                .trackDeviceStates(STATE_POWER, STATE_SCREEN)
                .trackUidStates(STATE_POWER, STATE_SCREEN, STATE_PROCESS_STATE)
                .setProcessorSupplier(
                        () -> new MobileRadioPowerStatsProcessor(mStatsRule.getPowerProfile()));
        config.trackPowerComponent(BatteryConsumer.POWER_COMPONENT_PHONE,
                        BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO)
                .setProcessorSupplier(PhoneCallPowerStatsProcessor::new);

        AggregatedPowerStats aggregatedPowerStats = new AggregatedPowerStats(config);
        PowerComponentAggregatedPowerStats mobileRadioStats =
                aggregatedPowerStats.getPowerComponentStats(
                        BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO);

        aggregatedPowerStats.start(0);

        aggregatedPowerStats.setDeviceState(STATE_POWER, POWER_STATE_OTHER, 0);
        aggregatedPowerStats.setDeviceState(STATE_SCREEN, SCREEN_STATE_ON, 0);

        MobileRadioPowerStatsCollector collector =
                new MobileRadioPowerStatsCollector(mInjector, null);
        collector.setEnabled(true);

        // Initial empty ModemActivityInfo.
        mockModemActivityInfo(new ModemActivityInfo(0L, 0L, 0L, new int[5], 0L));

        // Establish a baseline
        aggregatedPowerStats.addPowerStats(collector.collectStats(), 0);

        // Turn the screen off after 2.5 seconds
        aggregatedPowerStats.setDeviceState(STATE_SCREEN, SCREEN_STATE_OTHER, 2500);

        ModemActivityInfo mai = new ModemActivityInfo(10000, 2000, 3000,
                new int[]{100, 200, 300, 400, 500}, 600);
        mockModemActivityInfo(mai);

        // A phone call was made
        when(mCallDurationSupplier.getAsLong()).thenReturn(7000L);

        mStatsRule.setTime(10_000, 10_000);

        aggregatedPowerStats.addPowerStats(collector.collectStats(), 10_000);

        mobileRadioStats.finish(10_000);

        PowerComponentAggregatedPowerStats stats =
                aggregatedPowerStats.getPowerComponentStats(BatteryConsumer.POWER_COMPONENT_PHONE);
        stats.finish(10_000);

        PowerStatsLayout statsLayout = new PowerStatsLayout(stats.getPowerStatsDescriptor());

        long[] deviceStats = new long[stats.getPowerStatsDescriptor().statsArrayLength];
        stats.getDeviceStats(deviceStats, states(POWER_STATE_OTHER, SCREEN_STATE_ON));
        assertThat(statsLayout.getDevicePowerEstimate(deviceStats))
                .isWithin(PRECISION).of(0.7);
        stats.getDeviceStats(deviceStats, states(POWER_STATE_OTHER, SCREEN_STATE_OTHER));
        assertThat(statsLayout.getDevicePowerEstimate(deviceStats))
                .isWithin(PRECISION).of(2.1);
    }

    private void mockModemActivityInfo(ModemActivityInfo emptyMai) {
        doAnswer(invocation -> {
            OutcomeReceiver<ModemActivityInfo, TelephonyManager.ModemActivityInfoException>
                    receiver = invocation.getArgument(1);
            receiver.onResult(emptyMai);
            return null;
        }).when(mTelephonyManager).requestModemActivityInfo(any(), any());
    }

    private int[] states(int... states) {
        return states;
    }
}
