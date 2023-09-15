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

import android.annotation.NonNull;
import android.os.BatteryConsumer;
import android.os.Handler;
import android.os.PersistableBundle;
import android.util.SparseArray;

import com.android.internal.annotations.Keep;
import com.android.internal.annotations.VisibleForNative;
import com.android.internal.os.Clock;
import com.android.internal.os.CpuScalingPolicies;
import com.android.internal.os.PowerProfile;
import com.android.internal.os.PowerStats;
import com.android.server.power.optimization.Flags;

/**
 * Collects snapshots of power-related system statistics.
 * <p>
 * The class is intended to be used in a serialized fashion using the handler supplied in the
 * constructor. Thus the object is not thread-safe except where noted.
 */
public class CpuPowerStatsCollector extends PowerStatsCollector {
    private static final long NANOS_PER_MILLIS = 1000000;

    private boolean mIsInitialized;
    private final KernelCpuStatsReader mKernelCpuStatsReader;
    private final long[] mCpuTimeByScalingStep;
    private final long[] mTempCpuTimeByScalingStep;
    private final int[] mScalingStepToPowerBracketMap;
    private final long[] mTempUidStats;
    private final SparseArray<UidStats> mUidStats = new SparseArray<>();
    private boolean mIsSupportedFeature;
    // Reusable instance
    private final PowerStats mCpuPowerStats;
    private final StatsArrayLayout mLayout;
    private long mLastUpdateTimestampNanos;
    private long mLastUpdateUptimeMillis;

    /**
     * Captures the positions and lengths of sections of the stats array, such as time-in-state,
     * power usage estimates etc.
     */
    public static class StatsArrayLayout {
        private static final String EXTRA_DEVICE_TIME_BY_SCALING_STEP_POSITION = "dt";
        private static final String EXTRA_DEVICE_TIME_BY_SCALING_STEP_COUNT = "dtc";
        private static final String EXTRA_DEVICE_TIME_BY_CLUSTER_POSITION = "dc";
        private static final String EXTRA_DEVICE_TIME_BY_CLUSTER_COUNT = "dcc";
        private static final String EXTRA_DEVICE_POWER_POSITION = "dp";
        private static final String EXTRA_DEVICE_UPTIME_POSITION = "du";
        private static final String EXTRA_UID_BRACKETS_POSITION = "ub";
        private static final String EXTRA_UID_BRACKET_COUNT = "ubc";
        private static final String EXTRA_UID_POWER_POSITION = "up";

        private static final double MILLI_TO_NANO_MULTIPLIER = 1000000.0;

        private int mDeviceStatsArrayLength;
        private int mUidStatsArrayLength;

        private int mDeviceCpuTimeByScalingStepPosition;
        private int mDeviceCpuTimeByScalingStepCount;
        private int mDeviceCpuTimeByClusterPosition;
        private int mDeviceCpuTimeByClusterCount;
        private int mDeviceCpuUptimePosition;
        private int mDevicePowerEstimatePosition;

        private int mUidPowerBracketsPosition;
        private int mUidPowerBracketCount;
        private int mUidPowerEstimatePosition;

        public int getDeviceStatsArrayLength() {
            return mDeviceStatsArrayLength;
        }

        public int getUidStatsArrayLength() {
            return mUidStatsArrayLength;
        }

        /**
         * Declare that the stats array has a section capturing CPU time per scaling step
         */
        public void addDeviceSectionCpuTimeByScalingStep(int scalingStepCount) {
            mDeviceCpuTimeByScalingStepPosition = mDeviceStatsArrayLength;
            mDeviceCpuTimeByScalingStepCount = scalingStepCount;
            mDeviceStatsArrayLength += scalingStepCount;
        }

        public int getCpuScalingStepCount() {
            return mDeviceCpuTimeByScalingStepCount;
        }

        /**
         * Saves the time duration in the <code>stats</code> element
         * corresponding to the CPU scaling <code>state</code>.
         */
        public void setTimeByScalingStep(long[] stats, int step, long value) {
            stats[mDeviceCpuTimeByScalingStepPosition + step] = value;
        }

        /**
         * Extracts the time duration from the <code>stats</code> element
         * corresponding to the CPU scaling <code>step</code>.
         */
        public long getTimeByScalingStep(long[] stats, int step) {
            return stats[mDeviceCpuTimeByScalingStepPosition + step];
        }

        /**
         * Declare that the stats array has a section capturing CPU time in each cluster
         */
        public void addDeviceSectionCpuTimeByCluster(int clusterCount) {
            mDeviceCpuTimeByClusterCount = clusterCount;
            mDeviceCpuTimeByClusterPosition = mDeviceStatsArrayLength;
            mDeviceStatsArrayLength += clusterCount;
        }

        public int getCpuClusterCount() {
            return mDeviceCpuTimeByClusterCount;
        }

        /**
         * Saves the time duration in the <code>stats</code> element
         * corresponding to the CPU <code>cluster</code>.
         */
        public void setTimeByCluster(long[] stats, int cluster, long value) {
            stats[mDeviceCpuTimeByClusterPosition + cluster] = value;
        }

        /**
         * Extracts the time duration from the <code>stats</code> element
         * corresponding to the CPU <code>cluster</code>.
         */
        public long getTimeByCluster(long[] stats, int cluster) {
            return stats[mDeviceCpuTimeByClusterPosition + cluster];
        }

        /**
         * Declare that the stats array has a section capturing CPU uptime
         */
        public void addDeviceSectionUptime() {
            mDeviceCpuUptimePosition = mDeviceStatsArrayLength++;
        }

        /**
         * Saves the CPU uptime duration in the corresponding <code>stats</code> element.
         */
        public void setUptime(long[] stats, long value) {
            stats[mDeviceCpuUptimePosition] = value;
        }

        /**
         * Extracts the CPU uptime duration from the corresponding <code>stats</code> element.
         */
        public long getUptime(long[] stats) {
            return stats[mDeviceCpuUptimePosition];
        }

        /**
         * Declare that the stats array has a section capturing a power estimate
         */
        public void addDeviceSectionPowerEstimate() {
            mDevicePowerEstimatePosition = mDeviceStatsArrayLength++;
        }

        /**
         * Converts the supplied mAh power estimate to a long and saves it in the corresponding
         * element of <code>stats</code>.
         */
        public void setDevicePowerEstimate(long[] stats, double power) {
            stats[mDevicePowerEstimatePosition] = (long) (power * MILLI_TO_NANO_MULTIPLIER);
        }

        /**
         * Extracts the power estimate from a device stats array and converts it to mAh.
         */
        public double getDevicePowerEstimate(long[] stats) {
            return stats[mDevicePowerEstimatePosition] / MILLI_TO_NANO_MULTIPLIER;
        }

        /**
         * Declare that the UID stats array has a section capturing CPU time per power bracket.
         */
        public void addUidSectionCpuTimeByPowerBracket(int cpuPowerBracketCount) {
            mUidPowerBracketsPosition = mUidStatsArrayLength;
            mUidPowerBracketCount = cpuPowerBracketCount;
            mUidStatsArrayLength += cpuPowerBracketCount;
        }

        public int getCpuPowerBracketCount() {
            return mUidPowerBracketCount;
        }

        /**
         * Saves time in <code>bracket</code> in the corresponding section of <code>stats</code>.
         */
        public void setTimeByPowerBracket(long[] stats, int bracket, long value) {
            stats[mUidPowerBracketsPosition + bracket] = value;
        }

        /**
         * Extracts the time in <code>bracket</code> from a UID stats array.
         */
        public long getTimeByPowerBracket(long[] stats, int bracket) {
            return stats[mUidPowerBracketsPosition + bracket];
        }

        /**
         * Declare that the UID stats array has a section capturing a power estimate
         */
        public void addUidSectionPowerEstimate() {
            mUidPowerEstimatePosition = mUidStatsArrayLength++;
        }

        /**
         * Converts the supplied mAh power estimate to a long and saves it in the corresponding
         * element of <code>stats</code>.
         */
        public void setUidPowerEstimate(long[] stats, double power) {
            stats[mUidPowerEstimatePosition] = (long) (power * MILLI_TO_NANO_MULTIPLIER);
        }

        /**
         * Extracts the power estimate from a UID stats array and converts it to mAh.
         */
        public double getUidPowerEstimate(long[] stats) {
            return stats[mUidPowerEstimatePosition] / MILLI_TO_NANO_MULTIPLIER;
        }

        /**
         * Copies the elements of the stats array layout into <code>extras</code>
         */
        public void toExtras(PersistableBundle extras) {
            extras.putInt(EXTRA_DEVICE_TIME_BY_SCALING_STEP_POSITION,
                    mDeviceCpuTimeByScalingStepPosition);
            extras.putInt(EXTRA_DEVICE_TIME_BY_SCALING_STEP_COUNT,
                    mDeviceCpuTimeByScalingStepCount);
            extras.putInt(EXTRA_DEVICE_TIME_BY_CLUSTER_POSITION,
                    mDeviceCpuTimeByClusterPosition);
            extras.putInt(EXTRA_DEVICE_TIME_BY_CLUSTER_COUNT,
                    mDeviceCpuTimeByClusterCount);
            extras.putInt(EXTRA_DEVICE_UPTIME_POSITION, mDeviceCpuUptimePosition);
            extras.putInt(EXTRA_DEVICE_POWER_POSITION, mDevicePowerEstimatePosition);
            extras.putInt(EXTRA_UID_BRACKETS_POSITION, mUidPowerBracketsPosition);
            extras.putInt(EXTRA_UID_BRACKET_COUNT, mUidPowerBracketCount);
            extras.putInt(EXTRA_UID_POWER_POSITION, mUidPowerEstimatePosition);
        }

        /**
         * Retrieves elements of the stats array layout from <code>extras</code>
         */
        public void fromExtras(PersistableBundle extras) {
            mDeviceCpuTimeByScalingStepPosition =
                    extras.getInt(EXTRA_DEVICE_TIME_BY_SCALING_STEP_POSITION);
            mDeviceCpuTimeByScalingStepCount =
                    extras.getInt(EXTRA_DEVICE_TIME_BY_SCALING_STEP_COUNT);
            mDeviceCpuTimeByClusterPosition =
                    extras.getInt(EXTRA_DEVICE_TIME_BY_CLUSTER_POSITION);
            mDeviceCpuTimeByClusterCount =
                    extras.getInt(EXTRA_DEVICE_TIME_BY_CLUSTER_COUNT);
            mDeviceCpuUptimePosition = extras.getInt(EXTRA_DEVICE_UPTIME_POSITION);
            mDevicePowerEstimatePosition = extras.getInt(EXTRA_DEVICE_POWER_POSITION);
            mUidPowerBracketsPosition = extras.getInt(EXTRA_UID_BRACKETS_POSITION);
            mUidPowerBracketCount = extras.getInt(EXTRA_UID_BRACKET_COUNT);
            mUidPowerEstimatePosition = extras.getInt(EXTRA_UID_POWER_POSITION);
        }
    }

    public CpuPowerStatsCollector(CpuScalingPolicies cpuScalingPolicies, PowerProfile powerProfile,
                                  Handler handler, long throttlePeriodMs) {
        this(cpuScalingPolicies, powerProfile, handler, new KernelCpuStatsReader(),
                throttlePeriodMs, Clock.SYSTEM_CLOCK);
    }

    public CpuPowerStatsCollector(CpuScalingPolicies cpuScalingPolicies, PowerProfile powerProfile,
                                  Handler handler, KernelCpuStatsReader kernelCpuStatsReader,
                                  long throttlePeriodMs, Clock clock) {
        super(handler, throttlePeriodMs, clock);
        mKernelCpuStatsReader = kernelCpuStatsReader;

        int cpuScalingStepCount = cpuScalingPolicies.getScalingStepCount();
        mCpuTimeByScalingStep = new long[cpuScalingStepCount];
        mTempCpuTimeByScalingStep = new long[cpuScalingStepCount];
        mScalingStepToPowerBracketMap =
                getScalingStepToPowerBracketMap(powerProfile, cpuScalingPolicies);

        int cpuPowerBracketCount = powerProfile.getCpuPowerBracketCount();
        mTempUidStats = new long[cpuPowerBracketCount];

        mLayout = new StatsArrayLayout();
        mLayout.addDeviceSectionCpuTimeByScalingStep(cpuScalingStepCount);
        mLayout.addDeviceSectionCpuTimeByCluster(cpuScalingPolicies.getPolicies().length);
        mLayout.addDeviceSectionUptime();
        mLayout.addDeviceSectionPowerEstimate();
        mLayout.addUidSectionCpuTimeByPowerBracket(cpuPowerBracketCount);
        mLayout.addUidSectionPowerEstimate();

        PersistableBundle extras = new PersistableBundle();
        mLayout.toExtras(extras);

        mCpuPowerStats = new PowerStats(
                new PowerStats.Descriptor(BatteryConsumer.POWER_COMPONENT_CPU,
                        mLayout.getDeviceStatsArrayLength(),
                        mLayout.getUidStatsArrayLength(), extras));
    }

    @NonNull
    static int[] getScalingStepToPowerBracketMap(PowerProfile powerProfile,
            CpuScalingPolicies cpuScalingPolicies) {
        final int[] map = new int[cpuScalingPolicies.getScalingStepCount()];
        int index = 0;
        for (int policy : cpuScalingPolicies.getPolicies()) {
            int[] frequencies = cpuScalingPolicies.getFrequencies(policy);
            for (int step = 0; step < frequencies.length; step++) {
                int bracket = powerProfile.getCpuPowerBracketForScalingStep(policy, step);
                map[index++] = bracket;
            }
        }
        return map;
    }

    /**
     * Initializes the collector during the boot sequence.
     */
    public void onSystemReady() {
        setEnabled(Flags.streamlinedBatteryStats());
    }

    private void ensureInitialized() {
        if (mIsInitialized) {
            return;
        }

        mIsSupportedFeature = mKernelCpuStatsReader.nativeIsSupportedFeature();
        mIsInitialized = true;
    }

    @Override
    protected PowerStats collectStats() {
        ensureInitialized();

        if (!mIsSupportedFeature) {
            return null;
        }

        mCpuPowerStats.uidStats.clear();
        // TODO(b/305120724): additionally retrieve time-in-cluster for each CPU cluster
        long newTimestampNanos = mKernelCpuStatsReader.nativeReadCpuStats(
                this::processUidStats, mScalingStepToPowerBracketMap, mLastUpdateTimestampNanos,
                mTempCpuTimeByScalingStep, mTempUidStats);
        for (int step = mLayout.getCpuScalingStepCount() - 1; step >= 0; step--) {
            mLayout.setTimeByScalingStep(mCpuPowerStats.stats, step,
                    mTempCpuTimeByScalingStep[step] - mCpuTimeByScalingStep[step]);
            mCpuTimeByScalingStep[step] = mTempCpuTimeByScalingStep[step];
        }

        mCpuPowerStats.durationMs =
                (newTimestampNanos - mLastUpdateTimestampNanos) / NANOS_PER_MILLIS;
        mLastUpdateTimestampNanos = newTimestampNanos;

        long uptimeMillis = mClock.uptimeMillis();
        long uptimeDelta = uptimeMillis - mLastUpdateUptimeMillis;
        mLastUpdateUptimeMillis = uptimeMillis;

        if (uptimeDelta > mCpuPowerStats.durationMs) {
            uptimeDelta = mCpuPowerStats.durationMs;
        }
        mLayout.setUptime(mCpuPowerStats.stats, uptimeDelta);

        return mCpuPowerStats;
    }

    @VisibleForNative
    interface KernelCpuStatsCallback {
        @Keep // Called from native
        void processUidStats(int uid, long[] timeByPowerBracket);
    }

    private void processUidStats(int uid, long[] timeByPowerBracket) {
        int powerBracketCount = mLayout.getCpuPowerBracketCount();

        UidStats uidStats = mUidStats.get(uid);
        if (uidStats == null) {
            uidStats = new UidStats();
            uidStats.timeByPowerBracket = new long[powerBracketCount];
            uidStats.stats = new long[mLayout.getUidStatsArrayLength()];
            mUidStats.put(uid, uidStats);
        }

        boolean nonzero = false;
        for (int bracket = powerBracketCount - 1; bracket >= 0; bracket--) {
            long delta = timeByPowerBracket[bracket] - uidStats.timeByPowerBracket[bracket];
            if (delta != 0) {
                nonzero = true;
            }
            mLayout.setTimeByPowerBracket(uidStats.stats, bracket, delta);
            uidStats.timeByPowerBracket[bracket] = timeByPowerBracket[bracket];
        }
        if (nonzero) {
            mCpuPowerStats.uidStats.put(uid, uidStats.stats);
        }
    }

    /**
     * Native class that retrieves CPU stats from the kernel.
     */
    public static class KernelCpuStatsReader {
        protected native boolean nativeIsSupportedFeature();

        protected native long nativeReadCpuStats(KernelCpuStatsCallback callback,
                int[] scalingStepToPowerBracketMap, long lastUpdateTimestampNanos,
                long[] outCpuTimeByScalingStep, long[] tempForUidStats);
    }

    private static class UidStats {
        public long[] stats;
        public long[] timeByPowerBracket;
    }
}
