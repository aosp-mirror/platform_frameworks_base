/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.os.BatteryStats;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.os.CpuScalingPolicies;
import com.android.internal.os.PowerProfile;
import com.android.internal.os.PowerStats;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class CpuAggregatedPowerStatsProcessor extends AggregatedPowerStatsProcessor {
    private static final String TAG = "CpuAggregatedPowerStatsProcessor";

    private static final double HOUR_IN_MILLIS = TimeUnit.HOURS.toMillis(1);
    private static final int UNKNOWN = -1;

    private final CpuScalingPolicies mCpuScalingPolicies;
    // Number of CPU core clusters
    private final int mCpuClusterCount;
    // Total number of CPU scaling steps across all clusters
    private final int mCpuScalingStepCount;
    // Map of scaling step to the corresponding core cluster mScalingStepToCluster[step]->cluster
    private final int[] mScalingStepToCluster;
    // Average power consumed by the CPU when it is powered up (per power_profile.xml)
    private final double mPowerMultiplierForCpuActive;
    // Average power consumed by each cluster when it is powered up (per power_profile.xml)
    private final double[] mPowerMultipliersByCluster;
    // Average power consumed by each scaling step when running code (per power_profile.xml)
    private final double[] mPowerMultipliersByScalingStep;
    // A map used to combine energy consumers into a smaller set, in case power brackets
    // are defined in a way that does not allow an unambiguous mapping of energy consumers to
    // brackets
    private int[] mEnergyConsumerToCombinedEnergyConsumerMap;
    // A map of combined energy consumers to the corresponding collections of power brackets.
    // For example, if there are two CPU_CLUSTER rails and each maps to three brackets,
    // this map will look like this:
    //     0 : [0, 1, 2]
    //     1 : [3, 4, 5]
    private int[][] mCombinedEnergyConsumerToPowerBracketMap;

    // Cached reference to a PowerStats descriptor. Almost never changes in practice,
    // helping to avoid reparsing the descriptor for every PowerStats span.
    private PowerStats.Descriptor mLastUsedDescriptor;
    // Cached results of parsing of current PowerStats.Descriptor. Only refreshed when
    // mLastUsedDescriptor changes
    private CpuPowerStatsCollector.CpuStatsArrayLayout mStatsLayout;
    // Sequence of steps for power estimation and intermediate results.
    private PowerEstimationPlan mPlan;

    // Temp array for retrieval of device power stats, to avoid repeated allocations
    private long[] mTmpDeviceStatsArray;
    // Temp array for retrieval of UID power stats, to avoid repeated allocations
    private long[] mTmpUidStatsArray;

    public CpuAggregatedPowerStatsProcessor(PowerProfile powerProfile,
            CpuScalingPolicies scalingPolicies) {
        mCpuScalingPolicies = scalingPolicies;
        mCpuScalingStepCount = scalingPolicies.getScalingStepCount();
        mScalingStepToCluster = new int[mCpuScalingStepCount];
        mPowerMultipliersByScalingStep = new double[mCpuScalingStepCount];

        int step = 0;
        int[] policies = scalingPolicies.getPolicies();
        mCpuClusterCount = policies.length;
        mPowerMultipliersByCluster = new double[mCpuClusterCount];
        for (int cluster = 0; cluster < mCpuClusterCount; cluster++) {
            int policy = policies[cluster];
            mPowerMultipliersByCluster[cluster] =
                    powerProfile.getAveragePowerForCpuScalingPolicy(policy) / HOUR_IN_MILLIS;
            int[] frequencies = scalingPolicies.getFrequencies(policy);
            for (int i = 0; i < frequencies.length; i++) {
                mScalingStepToCluster[step] = cluster;
                mPowerMultipliersByScalingStep[step] =
                        powerProfile.getAveragePowerForCpuScalingStep(policy, i) / HOUR_IN_MILLIS;
                step++;
            }
        }
        mPowerMultiplierForCpuActive =
                powerProfile.getAveragePower(PowerProfile.POWER_CPU_ACTIVE) / HOUR_IN_MILLIS;
    }

    private void unpackPowerStatsDescriptor(PowerStats.Descriptor descriptor) {
        if (descriptor.equals(mLastUsedDescriptor)) {
            return;
        }

        mLastUsedDescriptor = descriptor;
        mStatsLayout = new CpuPowerStatsCollector.CpuStatsArrayLayout();
        mStatsLayout.fromExtras(descriptor.extras);

        mTmpDeviceStatsArray = new long[descriptor.statsArrayLength];
        mTmpUidStatsArray = new long[descriptor.uidStatsArrayLength];
    }

    /**
     * Temporary struct to capture intermediate results of power estimation.
     */
    private static final class Intermediates {
        public long uptime;
        // Sum of all time-in-step values, combining all time-in-step durations across all cores.
        public long cumulativeTime;
        // CPU activity durations per cluster
        public long[] timeByCluster;
        // Sums of time-in-step values, aggregated by cluster, combining all cores in the cluster.
        public long[] cumulativeTimeByCluster;
        public long[] timeByScalingStep;
        public double[] powerByCluster;
        public double[] powerByScalingStep;
        public long[] powerByEnergyConsumer;
    }

    /**
     * Temporary struct to capture intermediate results of power estimation.
     */
    private static class DeviceStatsIntermediates {
        public double power;
        public long[] timeByBracket;
        public double[] powerByBracket;
    }

    @Override
    public void finish(PowerComponentAggregatedPowerStats stats) {
        if (stats.getPowerStatsDescriptor() == null) {
            return;
        }

        unpackPowerStatsDescriptor(stats.getPowerStatsDescriptor());

        if (mPlan == null) {
            mPlan = new PowerEstimationPlan(stats.getConfig());
            if (mStatsLayout.getEnergyConsumerCount() != 0) {
                initEnergyConsumerToPowerBracketMaps();
            }
        }

        Intermediates intermediates = new Intermediates();

        int cpuScalingStepCount = mStatsLayout.getCpuScalingStepCount();
        if (cpuScalingStepCount != mCpuScalingStepCount) {
            Log.e(TAG, "Mismatched CPU scaling step count in PowerStats: " + cpuScalingStepCount
                       + ", expected: " + mCpuScalingStepCount);
            return;
        }

        int clusterCount = mStatsLayout.getCpuClusterCount();
        if (clusterCount != mCpuClusterCount) {
            Log.e(TAG, "Mismatched CPU cluster count in PowerStats: " + clusterCount
                       + ", expected: " + mCpuClusterCount);
            return;
        }

        computeTotals(stats, intermediates);
        if (intermediates.cumulativeTime == 0) {
            return;
        }

        estimatePowerByScalingStep(intermediates);
        estimatePowerByDeviceState(stats, intermediates);
        combineDeviceStateEstimates();

        ArrayList<Integer> uids = new ArrayList<>();
        stats.collectUids(uids);
        if (!uids.isEmpty()) {
            for (int uid : uids) {
                for (int i = 0; i < mPlan.uidStateEstimates.size(); i++) {
                    estimateUidPowerConsumption(stats, uid, mPlan.uidStateEstimates.get(i));
                }
            }
        }
        mPlan.resetIntermediates();
    }

    /*
     * Populate data structures (two maps) needed to use power rail data, aka energy consumers,
     * to attribute power usage to apps.
     *
     * At this point, the algorithm covers only the most basic cases:
     * - Each cluster is mapped to unique power brackets (possibly multiple for each cluster):
     *          CL_0: [bracket0, bracket1]
     *          CL_1: [bracket3]
     *      In this case, the consumed energy is distributed  to the corresponding brackets
     *      proportionally.
     * - Brackets span multiple clusters:
     *          CL_0: [bracket0, bracket1]
     *          CL_1: [bracket1, bracket2]
     *          CL_2: [bracket3, bracket4]
     *      In this case, we combine energy consumers into groups unambiguously mapped to
     *      brackets. In the above example, consumed energy for CL_0 and CL_1 will be combined
     *      because they both map to the same power bracket (bracket1):
     *          (CL_0+CL_1): [bracket0, bracket1, bracket2]
     *          CL_2: [bracket3, bracket4]
     */
    private void initEnergyConsumerToPowerBracketMaps() {
        int energyConsumerCount = mStatsLayout.getEnergyConsumerCount();
        int powerBracketCount = mStatsLayout.getCpuPowerBracketCount();

        mEnergyConsumerToCombinedEnergyConsumerMap = new int[energyConsumerCount];
        mCombinedEnergyConsumerToPowerBracketMap = new int[energyConsumerCount][];

        int[] policies = mCpuScalingPolicies.getPolicies();
        if (energyConsumerCount == policies.length) {
            int[] scalingStepToPowerBracketMap = mStatsLayout.getScalingStepToPowerBracketMap();
            ArraySet<Integer>[] clusterToBrackets = new ArraySet[policies.length];
            int step = 0;
            for (int cluster = 0; cluster < policies.length; cluster++) {
                int[] freqs = mCpuScalingPolicies.getFrequencies(policies[cluster]);
                clusterToBrackets[cluster] = new ArraySet<>(freqs.length);
                for (int j = 0; j < freqs.length; j++) {
                    clusterToBrackets[cluster].add(scalingStepToPowerBracketMap[step++]);
                }
            }

            ArraySet<Integer>[] combinedEnergyConsumers = new ArraySet[policies.length];
            int combinedEnergyConsumersCount = 0;

            for (int cluster = 0; cluster < clusterToBrackets.length; cluster++) {
                int combineWith = UNKNOWN;
                for (int i = 0; i < combinedEnergyConsumersCount; i++) {
                    if (containsAny(combinedEnergyConsumers[i], clusterToBrackets[cluster])) {
                        combineWith = i;
                        break;
                    }
                }
                if (combineWith != UNKNOWN) {
                    mEnergyConsumerToCombinedEnergyConsumerMap[cluster] = combineWith;
                    combinedEnergyConsumers[combineWith].addAll(clusterToBrackets[cluster]);
                } else {
                    mEnergyConsumerToCombinedEnergyConsumerMap[cluster] =
                            combinedEnergyConsumersCount;
                    combinedEnergyConsumers[combinedEnergyConsumersCount++] =
                            clusterToBrackets[cluster];
                }
            }

            for (int i = combinedEnergyConsumers.length - 1; i >= 0; i--) {
                mCombinedEnergyConsumerToPowerBracketMap[i] =
                        new int[combinedEnergyConsumers[i].size()];
                for (int j = combinedEnergyConsumers[i].size() - 1; j >= 0; j--) {
                    mCombinedEnergyConsumerToPowerBracketMap[i][j] =
                            combinedEnergyConsumers[i].valueAt(j);
                }
            }
        } else {
            // All CPU cluster energy consumers are combined into one, which is
            // distributed proportionally to all power brackets.
            int[] map = new int[powerBracketCount];
            for (int i = 0; i < map.length; i++) {
                map[i] = i;
            }
            mCombinedEnergyConsumerToPowerBracketMap[0] = map;
        }
    }

    private static boolean containsAny(ArraySet<Integer> set1, ArraySet<Integer> set2) {
        for (int i = 0; i < set2.size(); i++) {
            if (set1.contains(set2.valueAt(i))) {
                return true;
            }
        }
        return false;
    }

    private void computeTotals(PowerComponentAggregatedPowerStats stats,
            Intermediates intermediates) {
        intermediates.timeByScalingStep = new long[mCpuScalingStepCount];
        intermediates.timeByCluster = new long[mCpuClusterCount];
        intermediates.cumulativeTimeByCluster = new long[mCpuClusterCount];

        List<DeviceStateEstimation> deviceStateEstimations = mPlan.deviceStateEstimations;
        for (int i = deviceStateEstimations.size() - 1; i >= 0; i--) {
            DeviceStateEstimation deviceStateEstimation = deviceStateEstimations.get(i);
            if (!stats.getDeviceStats(mTmpDeviceStatsArray, deviceStateEstimation.stateValues)) {
                continue;
            }

            intermediates.uptime += mStatsLayout.getUsageDuration(mTmpDeviceStatsArray);

            for (int cluster = 0; cluster < mCpuClusterCount; cluster++) {
                intermediates.timeByCluster[cluster] +=
                        mStatsLayout.getTimeByCluster(mTmpDeviceStatsArray, cluster);
            }

            for (int step = 0; step < mCpuScalingStepCount; step++) {
                long timeInStep = mStatsLayout.getTimeByScalingStep(mTmpDeviceStatsArray, step);
                intermediates.cumulativeTime += timeInStep;
                intermediates.timeByScalingStep[step] += timeInStep;
                intermediates.cumulativeTimeByCluster[mScalingStepToCluster[step]] += timeInStep;
            }
        }
    }

    private void estimatePowerByScalingStep(Intermediates intermediates) {
        // CPU consumes some power when it's on - no matter which cores are running.
        double cpuActivePower = mPowerMultiplierForCpuActive * intermediates.uptime;

        // Additionally, every cluster consumes some power when any of its cores are running
        intermediates.powerByCluster = new double[mCpuClusterCount];
        for (int cluster = 0; cluster < mCpuClusterCount; cluster++) {
            intermediates.powerByCluster[cluster] =
                    mPowerMultipliersByCluster[cluster] * intermediates.timeByCluster[cluster];
        }

        // Finally, additional power is consumed depending on the frequency scaling
        intermediates.powerByScalingStep = new double[mCpuScalingStepCount];
        for (int step = 0; step < mCpuScalingStepCount; step++) {
            int cluster = mScalingStepToCluster[step];

            double power;

            // Distribute base power proportionally
            power = cpuActivePower * intermediates.timeByScalingStep[step]
                    / intermediates.cumulativeTime;

            // Distribute per-cluster power proportionally
            long cumulativeTimeInCluster = intermediates.cumulativeTimeByCluster[cluster];
            if (cumulativeTimeInCluster != 0) {
                power += intermediates.powerByCluster[cluster]
                         * intermediates.timeByScalingStep[step]
                         / cumulativeTimeInCluster;
            }

            power += mPowerMultipliersByScalingStep[step] * intermediates.timeByScalingStep[step];

            intermediates.powerByScalingStep[step] = power;
        }
    }

    private void estimatePowerByDeviceState(PowerComponentAggregatedPowerStats stats,
            Intermediates intermediates) {
        int cpuScalingStepCount = mStatsLayout.getCpuScalingStepCount();
        int powerBracketCount = mStatsLayout.getCpuPowerBracketCount();
        int[] scalingStepToBracketMap = mStatsLayout.getScalingStepToPowerBracketMap();
        int energyConsumerCount = mStatsLayout.getEnergyConsumerCount();
        List<DeviceStateEstimation> deviceStateEstimations = mPlan.deviceStateEstimations;
        for (int dse = deviceStateEstimations.size() - 1; dse >= 0; dse--) {
            DeviceStateEstimation deviceStateEstimation = deviceStateEstimations.get(dse);
            deviceStateEstimation.intermediates = new DeviceStatsIntermediates();
            DeviceStatsIntermediates deviceStatsIntermediates =
                    (DeviceStatsIntermediates) deviceStateEstimation.intermediates;
            deviceStatsIntermediates.timeByBracket = new long[powerBracketCount];
            deviceStatsIntermediates.powerByBracket = new double[powerBracketCount];

            stats.getDeviceStats(mTmpDeviceStatsArray, deviceStateEstimation.stateValues);
            for (int step = 0; step < cpuScalingStepCount; step++) {
                if (intermediates.timeByScalingStep[step] == 0) {
                    continue;
                }

                long timeInStep = mStatsLayout.getTimeByScalingStep(mTmpDeviceStatsArray, step);
                double stepPower = intermediates.powerByScalingStep[step] * timeInStep
                                   / intermediates.timeByScalingStep[step];

                int bracket = scalingStepToBracketMap[step];
                deviceStatsIntermediates.timeByBracket[bracket] += timeInStep;
                deviceStatsIntermediates.powerByBracket[bracket] += stepPower;
            }

            if (energyConsumerCount != 0) {
                adjustEstimatesUsingEnergyConsumers(intermediates, deviceStatsIntermediates);
            }

            double power = 0;
            for (int i = deviceStatsIntermediates.powerByBracket.length - 1; i >= 0; i--) {
                power += deviceStatsIntermediates.powerByBracket[i];
            }
            deviceStatsIntermediates.power = power;
            mStatsLayout.setDevicePowerEstimate(mTmpDeviceStatsArray, power);
            stats.setDeviceStats(deviceStateEstimation.stateValues, mTmpDeviceStatsArray);
        }
    }

    private void adjustEstimatesUsingEnergyConsumers(
            Intermediates intermediates, DeviceStatsIntermediates deviceStatsIntermediates) {
        int energyConsumerCount = mStatsLayout.getEnergyConsumerCount();
        if (energyConsumerCount == 0) {
            return;
        }

        if (intermediates.powerByEnergyConsumer == null) {
            intermediates.powerByEnergyConsumer = new long[energyConsumerCount];
        } else {
            Arrays.fill(intermediates.powerByEnergyConsumer, 0);
        }
        for (int i = 0; i < energyConsumerCount; i++) {
            intermediates.powerByEnergyConsumer[mEnergyConsumerToCombinedEnergyConsumerMap[i]] +=
                    mStatsLayout.getConsumedEnergy(mTmpDeviceStatsArray, i);
        }

        for (int combinedConsumer = mCombinedEnergyConsumerToPowerBracketMap.length - 1;
                combinedConsumer >= 0; combinedConsumer--) {
            int[] combinedEnergyConsumerToPowerBracketMap =
                    mCombinedEnergyConsumerToPowerBracketMap[combinedConsumer];
            if (combinedEnergyConsumerToPowerBracketMap == null) {
                continue;
            }

            double consumedEnergy = uCtoMah(intermediates.powerByEnergyConsumer[combinedConsumer]);

            double totalModeledPower = 0;
            for (int bracket : combinedEnergyConsumerToPowerBracketMap) {
                totalModeledPower += deviceStatsIntermediates.powerByBracket[bracket];
            }
            if (totalModeledPower == 0) {
                continue;
            }

            for (int bracket : combinedEnergyConsumerToPowerBracketMap) {
                deviceStatsIntermediates.powerByBracket[bracket] =
                        consumedEnergy * deviceStatsIntermediates.powerByBracket[bracket]
                        / totalModeledPower;
            }
        }
    }

    private void combineDeviceStateEstimates() {
        for (int i = mPlan.combinedDeviceStateEstimations.size() - 1; i >= 0; i--) {
            CombinedDeviceStateEstimate cdse = mPlan.combinedDeviceStateEstimations.get(i);
            DeviceStatsIntermediates cdseIntermediates = new DeviceStatsIntermediates();
            cdse.intermediates = cdseIntermediates;
            int bracketCount = mStatsLayout.getCpuPowerBracketCount();
            cdseIntermediates.timeByBracket = new long[bracketCount];
            cdseIntermediates.powerByBracket = new double[bracketCount];
            List<DeviceStateEstimation> deviceStateEstimations = cdse.deviceStateEstimations;
            for (int j = deviceStateEstimations.size() - 1; j >= 0; j--) {
                DeviceStateEstimation dse = deviceStateEstimations.get(j);
                DeviceStatsIntermediates intermediates =
                        (DeviceStatsIntermediates) dse.intermediates;
                cdseIntermediates.power += intermediates.power;
                for (int k = 0; k < bracketCount; k++) {
                    cdseIntermediates.timeByBracket[k] += intermediates.timeByBracket[k];
                    cdseIntermediates.powerByBracket[k] += intermediates.powerByBracket[k];
                }
            }
        }
    }

    private void estimateUidPowerConsumption(PowerComponentAggregatedPowerStats stats, int uid,
            UidStateEstimate uidStateEstimate) {
        CombinedDeviceStateEstimate combinedDeviceStateEstimate =
                uidStateEstimate.combinedDeviceStateEstimate;
        DeviceStatsIntermediates cdsIntermediates =
                (DeviceStatsIntermediates) combinedDeviceStateEstimate.intermediates;
        for (int i = 0; i < uidStateEstimate.proportionalEstimates.size(); i++) {
            UidStateProportionalEstimate proportionalEstimate =
                    uidStateEstimate.proportionalEstimates.get(i);
            if (!stats.getUidStats(mTmpUidStatsArray, uid, proportionalEstimate.stateValues)) {
                continue;
            }

            double power = 0;
            for (int bracket = 0; bracket < mStatsLayout.getCpuPowerBracketCount(); bracket++) {
                if (cdsIntermediates.timeByBracket[bracket] == 0) {
                    continue;
                }

                long timeInBracket = mStatsLayout.getUidTimeByPowerBracket(mTmpUidStatsArray,
                        bracket);
                if (timeInBracket == 0) {
                    continue;
                }

                power += cdsIntermediates.powerByBracket[bracket] * timeInBracket
                            / cdsIntermediates.timeByBracket[bracket];
            }

            mStatsLayout.setUidPowerEstimate(mTmpUidStatsArray, power);
            stats.setUidStats(uid, proportionalEstimate.stateValues, mTmpUidStatsArray);
        }
    }

    @Override
    public String deviceStatsToString(PowerStats.Descriptor descriptor, long[] stats) {
        unpackPowerStatsDescriptor(descriptor);
        StringBuilder sb = new StringBuilder();
        int cpuScalingStepCount = mStatsLayout.getCpuScalingStepCount();
        sb.append("steps: [");
        for (int step = 0; step < cpuScalingStepCount; step++) {
            if (step != 0) {
                sb.append(", ");
            }
            sb.append(mStatsLayout.getTimeByScalingStep(stats, step));
        }
        int clusterCount = mStatsLayout.getCpuClusterCount();
        sb.append("] clusters: [");
        for (int cluster = 0; cluster < clusterCount; cluster++) {
            if (cluster != 0) {
                sb.append(", ");
            }
            sb.append(mStatsLayout.getTimeByCluster(stats, cluster));
        }
        sb.append("] uptime: ").append(mStatsLayout.getUsageDuration(stats));
        int energyConsumerCount = mStatsLayout.getEnergyConsumerCount();
        if (energyConsumerCount > 0) {
            sb.append(" energy: [");
            for (int i = 0; i < energyConsumerCount; i++) {
                if (i != 0) {
                    sb.append(", ");
                }
                sb.append(mStatsLayout.getConsumedEnergy(stats, i));
            }
            sb.append("]");
        }
        sb.append(" power: ").append(
                BatteryStats.formatCharge(mStatsLayout.getDevicePowerEstimate(stats)));
        return sb.toString();
    }

    @Override
    public String uidStatsToString(PowerStats.Descriptor descriptor, long[] stats) {
        unpackPowerStatsDescriptor(descriptor);
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        int powerBracketCount = mStatsLayout.getCpuPowerBracketCount();
        for (int bracket = 0; bracket < powerBracketCount; bracket++) {
            if (bracket != 0) {
                sb.append(", ");
            }
            sb.append(mStatsLayout.getUidTimeByPowerBracket(stats, bracket));
        }
        sb.append("] power: ").append(
                BatteryStats.formatCharge(mStatsLayout.getUidPowerEstimate(stats)));
        return sb.toString();
    }
}
