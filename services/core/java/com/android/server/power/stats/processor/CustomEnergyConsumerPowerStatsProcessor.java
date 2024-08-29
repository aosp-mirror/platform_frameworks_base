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

package com.android.server.power.stats.processor;

import com.android.internal.os.PowerStats;
import com.android.server.power.stats.format.EnergyConsumerPowerStatsLayout;

import java.util.ArrayList;
import java.util.List;

class CustomEnergyConsumerPowerStatsProcessor extends PowerStatsProcessor {
    private static final EnergyConsumerPowerStatsLayout sLayout =
            new EnergyConsumerPowerStatsLayout();
    private long[] mTmpDeviceStatsArray;
    private long[] mTmpUidStatsArray;
    private PowerEstimationPlan mPlan;

    @Override
    void finish(PowerComponentAggregatedPowerStats stats, long timestampMs) {
        PowerStats.Descriptor descriptor = stats.getPowerStatsDescriptor();
        mTmpDeviceStatsArray = new long[descriptor.statsArrayLength];
        mTmpUidStatsArray = new long[descriptor.uidStatsArrayLength];
        if (mPlan == null) {
            mPlan = new PowerEstimationPlan(stats.getConfig());
        }

        computeDevicePowerEstimates(stats);

        List<Integer> uids = new ArrayList<>();
        stats.collectUids(uids);

        if (!uids.isEmpty()) {
            computeUidPowerEstimates(stats, uids);
        }
    }

    private void computeDevicePowerEstimates(PowerComponentAggregatedPowerStats stats) {
        for (int i = mPlan.deviceStateEstimations.size() - 1; i >= 0; i--) {
            DeviceStateEstimation estimation = mPlan.deviceStateEstimations.get(i);
            if (!stats.getDeviceStats(mTmpDeviceStatsArray, estimation.stateValues)) {
                continue;
            }

            sLayout.setDevicePowerEstimate(mTmpDeviceStatsArray,
                    uCtoMah(sLayout.getConsumedEnergy(mTmpDeviceStatsArray, 0)));
            stats.setDeviceStats(estimation.stateValues, mTmpDeviceStatsArray);
        }
    }

    private void computeUidPowerEstimates(PowerComponentAggregatedPowerStats stats,
            List<Integer> uids) {
        for (int i = mPlan.uidStateEstimates.size() - 1; i >= 0; i--) {
            UidStateEstimate uidStateEstimate = mPlan.uidStateEstimates.get(i);
            List<UidStateProportionalEstimate> proportionalEstimates =
                    uidStateEstimate.proportionalEstimates;
            for (int j = proportionalEstimates.size() - 1; j >= 0; j--) {
                UidStateProportionalEstimate proportionalEstimate = proportionalEstimates.get(j);
                for (int k = uids.size() - 1; k >= 0; k--) {
                    int uid = uids.get(k);
                    if (stats.getUidStats(mTmpUidStatsArray, uid,
                            proportionalEstimate.stateValues)) {
                        sLayout.setUidPowerEstimate(mTmpUidStatsArray,
                                uCtoMah(sLayout.getUidConsumedEnergy(mTmpUidStatsArray, 0)));
                        stats.setUidStats(uid, proportionalEstimate.stateValues, mTmpUidStatsArray);
                    }
                }
            }
        }
    }
}
