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
import com.android.server.power.stats.format.MobileRadioPowerStatsLayout;
import com.android.server.power.stats.format.PhoneCallPowerStatsLayout;

class PhoneCallPowerStatsProcessor extends PowerStatsProcessor {
    private final PhoneCallPowerStatsLayout mStatsLayout;
    private final PowerStats.Descriptor mDescriptor;
    private final long[] mTmpDeviceStats;
    private PowerStats.Descriptor mMobileRadioStatsDescriptor;
    private MobileRadioPowerStatsLayout mMobileRadioStatsLayout;
    private long[] mTmpMobileRadioDeviceStats;

    PhoneCallPowerStatsProcessor() {
        mStatsLayout = new PhoneCallPowerStatsLayout();
        PersistableBundle extras = new PersistableBundle();
        mStatsLayout.toExtras(extras);
        mDescriptor = new PowerStats.Descriptor(BatteryConsumer.POWER_COMPONENT_PHONE,
                mStatsLayout.getDeviceStatsArrayLength(), null, 0, 0, extras);
        mTmpDeviceStats = new long[mDescriptor.statsArrayLength];
    }

    @Override
    void finish(PowerComponentAggregatedPowerStats stats, long timestampMs) {
        stats.setPowerStatsDescriptor(mDescriptor);

        PowerComponentAggregatedPowerStats mobileRadioStats =
                stats.getAggregatedPowerStats().getPowerComponentStats(
                        BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO);
        if (mobileRadioStats == null) {
            return;
        }

        if (mMobileRadioStatsDescriptor == null) {
            mMobileRadioStatsDescriptor = mobileRadioStats.getPowerStatsDescriptor();
            if (mMobileRadioStatsDescriptor == null) {
                return;
            }

            mMobileRadioStatsLayout =
                    new MobileRadioPowerStatsLayout(
                            mMobileRadioStatsDescriptor);
            mTmpMobileRadioDeviceStats = new long[mMobileRadioStatsDescriptor.statsArrayLength];
        }

        MultiStateStats.States[] deviceStateConfig =
                mobileRadioStats.getConfig().getDeviceStateConfig();

        // Phone call power estimates have already been calculated by the mobile radio stats
        // processor. All that remains to be done is copy the estimates over.
        MultiStateStats.States.forEachTrackedStateCombination(deviceStateConfig,
                states -> {
                    mobileRadioStats.getDeviceStats(mTmpMobileRadioDeviceStats, states);
                    double callPowerEstimate =
                            mMobileRadioStatsLayout.getDeviceCallPowerEstimate(
                                    mTmpMobileRadioDeviceStats);
                    mStatsLayout.setDevicePowerEstimate(mTmpDeviceStats, callPowerEstimate);
                    stats.setDeviceStats(states, mTmpDeviceStats);
                });
    }
}
