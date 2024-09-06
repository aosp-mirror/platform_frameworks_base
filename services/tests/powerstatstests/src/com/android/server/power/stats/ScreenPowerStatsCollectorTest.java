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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import android.hardware.power.stats.EnergyConsumerResult;
import android.hardware.power.stats.EnergyConsumerType;
import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.Handler;
import android.platform.test.ravenwood.RavenwoodRule;

import com.android.internal.os.Clock;
import com.android.internal.os.PowerStats;
import com.android.server.power.stats.ScreenPowerStatsCollector.Injector;
import com.android.server.power.stats.format.ScreenPowerStatsLayout;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class ScreenPowerStatsCollectorTest {
    private static final int APP_UID1 = 42;
    private static final int APP_UID2 = 24;
    private static final int ISOLATED_UID = 99123;

    @Rule(order = 0)
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true)
            .build();

    @Rule(order = 1)
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule()
            .setPowerStatsThrottlePeriodMillis(BatteryConsumer.POWER_COMPONENT_SCREEN, 1000);

    @Mock
    private PowerStatsCollector.ConsumedEnergyRetriever mConsumedEnergyRetriever;
    @Mock
    private PowerStatsUidResolver mPowerStatsUidResolver;
    @Mock
    private ScreenPowerStatsCollector.ScreenUsageTimeRetriever mScreenUsageTimeRetriever;

    private final Injector mInjector = new Injector() {
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
        public PowerStatsCollector.ConsumedEnergyRetriever getConsumedEnergyRetriever() {
            return mConsumedEnergyRetriever;
        }

        @Override
        public int getDisplayCount() {
            return 2;
        }

        @Override
        public ScreenPowerStatsCollector.ScreenUsageTimeRetriever getScreenUsageTimeRetriever() {
            return mScreenUsageTimeRetriever;
        }
    };

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mPowerStatsUidResolver.mapUid(anyInt())).thenAnswer(invocation -> {
            int uid = invocation.getArgument(0);
            if (uid == ISOLATED_UID) {
                return APP_UID2;
            } else {
                return uid;
            }
        });
        when(mConsumedEnergyRetriever.getVoltageMv()).thenReturn(3500);
    }

    @Test
    public void collectStats() {
        ScreenPowerStatsCollector collector = new ScreenPowerStatsCollector(mInjector);
        collector.setEnabled(true);

        // Establish a baseline
        when(mConsumedEnergyRetriever.getEnergyConsumerIds(EnergyConsumerType.DISPLAY))
                .thenReturn(new int[]{77});
        when(mConsumedEnergyRetriever.getConsumedEnergy(new int[]{77}))
                .thenReturn(new EnergyConsumerResult[]{mockEnergyConsumer(10_000)});

        doAnswer(inv -> {
            ScreenPowerStatsCollector.ScreenUsageTimeRetriever.Callback callback =
                    inv.getArgument(0);
            callback.onUidTopActivityTime(APP_UID1, 1000);
            callback.onUidTopActivityTime(APP_UID2, 2000);
            return null;
        }).when(mScreenUsageTimeRetriever).retrieveTopActivityTimes(any(
                ScreenPowerStatsCollector.ScreenUsageTimeRetriever.Callback.class));

        collector.collectStats();

        when(mConsumedEnergyRetriever.getConsumedEnergy(new int[]{77}))
                .thenReturn(new EnergyConsumerResult[]{mockEnergyConsumer(45_000)});
        when(mScreenUsageTimeRetriever.getScreenOnTimeMs(0))
                .thenReturn(60_000L);
        when(mScreenUsageTimeRetriever.getBrightnessLevelTimeMs(0,
                BatteryStats.SCREEN_BRIGHTNESS_DARK))
                .thenReturn(10_000L);
        when(mScreenUsageTimeRetriever.getBrightnessLevelTimeMs(0,
                BatteryStats.SCREEN_BRIGHTNESS_MEDIUM))
                .thenReturn(20_000L);
        when(mScreenUsageTimeRetriever.getBrightnessLevelTimeMs(0,
                BatteryStats.SCREEN_BRIGHTNESS_BRIGHT))
                .thenReturn(30_000L);
        when(mScreenUsageTimeRetriever.getScreenOnTimeMs(1))
                .thenReturn(120_000L);
        when(mScreenUsageTimeRetriever.getScreenDozeTimeMs(0))
                .thenReturn(180_000L);
        when(mScreenUsageTimeRetriever.getScreenDozeTimeMs(1))
                .thenReturn(240_000L);
        doAnswer(inv -> {
            ScreenPowerStatsCollector.ScreenUsageTimeRetriever.Callback callback =
                    inv.getArgument(0);
            callback.onUidTopActivityTime(APP_UID1, 3000);
            callback.onUidTopActivityTime(APP_UID2, 5000);
            callback.onUidTopActivityTime(ISOLATED_UID, 7000);
            return null;
        }).when(mScreenUsageTimeRetriever).retrieveTopActivityTimes(any(
                ScreenPowerStatsCollector.ScreenUsageTimeRetriever.Callback.class));


        PowerStats powerStats = collector.collectStats();

        ScreenPowerStatsLayout layout = new ScreenPowerStatsLayout(powerStats.descriptor);

        // (45000 - 10000) / 3500
        assertThat(layout.getConsumedEnergy(powerStats.stats, 0))
                .isEqualTo(10_000);

        assertThat(layout.getScreenOnDuration(powerStats.stats, 0))
                .isEqualTo(60_000);
        assertThat(layout.getBrightnessLevelDuration(powerStats.stats, 0,
                BatteryStats.SCREEN_BRIGHTNESS_DARK))
                .isEqualTo(10_000);
        assertThat(layout.getBrightnessLevelDuration(powerStats.stats, 0,
                BatteryStats.SCREEN_BRIGHTNESS_MEDIUM))
                .isEqualTo(20_000);
        assertThat(layout.getBrightnessLevelDuration(powerStats.stats, 0,
                BatteryStats.SCREEN_BRIGHTNESS_BRIGHT))
                .isEqualTo(30_000);
        assertThat(layout.getScreenOnDuration(powerStats.stats, 1))
                .isEqualTo(120_000);
        assertThat(layout.getScreenDozeDuration(powerStats.stats, 0))
                .isEqualTo(180_000);
        assertThat(layout.getScreenDozeDuration(powerStats.stats, 1))
                .isEqualTo(240_000);

        assertThat(powerStats.uidStats.size()).isEqualTo(2);
        // 3000 - 1000
        assertThat(layout.getUidTopActivityDuration(powerStats.uidStats.get(APP_UID1)))
                .isEqualTo(2000);
        // (5000 - 2000) + 7000
        assertThat(layout.getUidTopActivityDuration(powerStats.uidStats.get(APP_UID2)))
                .isEqualTo(10000);
    }

    private EnergyConsumerResult mockEnergyConsumer(long energyUWs) {
        EnergyConsumerResult ecr = new EnergyConsumerResult();
        ecr.energyUWs = energyUWs;
        return ecr;
    }
}
