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

import android.os.BatteryConsumer;
import android.os.PersistableBundle;

import com.android.internal.os.PowerStats;
import com.android.server.power.stats.format.AmbientDisplayPowerStatsLayout;
import com.android.server.power.stats.format.ScreenPowerStatsLayout;

class AmbientDisplayPowerStatsProcessor extends PowerStatsProcessor {
    private final AmbientDisplayPowerStatsLayout mStatsLayout;
    private final PowerStats.Descriptor mDescriptor;
    private final long[] mTmpDeviceStats;
    private PowerStats.Descriptor mScreenPowerStatsDescriptor;
    private ScreenPowerStatsLayout mScreenPowerStatsLayout;
    private long[] mTmpScreenStats;

    AmbientDisplayPowerStatsProcessor() {
        mStatsLayout = new AmbientDisplayPowerStatsLayout();
        PersistableBundle extras = new PersistableBundle();
        mStatsLayout.toExtras(extras);
        mDescriptor = new PowerStats.Descriptor(BatteryConsumer.POWER_COMPONENT_AMBIENT_DISPLAY,
                mStatsLayout.getDeviceStatsArrayLength(), null, 0, 0, extras);
        mTmpDeviceStats = new long[mDescriptor.statsArrayLength];
    }

    @Override
    void finish(PowerComponentAggregatedPowerStats stats, long timestampMs) {
        stats.setPowerStatsDescriptor(mDescriptor);

        PowerComponentAggregatedPowerStats screenStats =
                stats.getAggregatedPowerStats().getPowerComponentStats(
                        BatteryConsumer.POWER_COMPONENT_SCREEN);
        if (screenStats == null) {
            return;
        }

        if (mScreenPowerStatsDescriptor == null) {
            mScreenPowerStatsDescriptor = screenStats.getPowerStatsDescriptor();
            if (mScreenPowerStatsDescriptor == null) {
                return;
            }

            mScreenPowerStatsLayout = new ScreenPowerStatsLayout(mScreenPowerStatsDescriptor);
            mTmpScreenStats = new long[mScreenPowerStatsDescriptor.statsArrayLength];
        }

        MultiStateStats.States[] deviceStateConfig = screenStats.getConfig().getDeviceStateConfig();

        // Ambient display power estimates have already been calculated by the screen power stats
        // processor. All that remains to be done is copy the estimates over.
        MultiStateStats.States.forEachTrackedStateCombination(deviceStateConfig,
                states -> {
                    screenStats.getDeviceStats(mTmpScreenStats, states);
                    double power =
                            mScreenPowerStatsLayout.getScreenDozePowerEstimate(mTmpScreenStats);
                    mStatsLayout.setDevicePowerEstimate(mTmpDeviceStats, power);
                    stats.setDeviceStats(states, mTmpDeviceStats);
                });
    }
}
