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

import android.os.Handler;
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

    private final KernelCpuStatsReader mKernelCpuStatsReader;
    private final int[] mScalingStepToPowerBracketMap;
    private final long[] mTempUidStats;
    private final SparseArray<UidStats> mUidStats = new SparseArray<>();
    private final int mUidStatsSize;
    // Reusable instance
    private final PowerStats mCpuPowerStats = new PowerStats();
    private long mLastUpdateTimestampNanos;

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

        int scalingStepCount = cpuScalingPolicies.getScalingStepCount();
        mScalingStepToPowerBracketMap = new int[scalingStepCount];
        int index = 0;
        for (int policy : cpuScalingPolicies.getPolicies()) {
            int[] frequencies = cpuScalingPolicies.getFrequencies(policy);
            for (int step = 0; step < frequencies.length; step++) {
                int bracket = powerProfile.getCpuPowerBracketForScalingStep(policy, step);
                mScalingStepToPowerBracketMap[index++] = bracket;
            }
        }
        mUidStatsSize = powerProfile.getCpuPowerBracketCount();
        mTempUidStats = new long[mUidStatsSize];
    }

    /**
     * Initializes the collector during the boot sequence.
     */
    public void onSystemReady() {
        setEnabled(Flags.streamlinedBatteryStats());
    }

    @Override
    protected PowerStats collectStats() {
        mCpuPowerStats.uidStats.clear();
        long newTimestampNanos = mKernelCpuStatsReader.nativeReadCpuStats(
                this::processUidStats, mScalingStepToPowerBracketMap, mLastUpdateTimestampNanos,
                mTempUidStats);
        mCpuPowerStats.durationMs =
                (newTimestampNanos - mLastUpdateTimestampNanos) / NANOS_PER_MILLIS;
        mLastUpdateTimestampNanos = newTimestampNanos;
        return mCpuPowerStats;
    }

    @VisibleForNative
    interface KernelCpuStatsCallback {
        @Keep // Called from native
        void processUidStats(int uid, long[] stats);
    }

    private void processUidStats(int uid, long[] stats) {
        UidStats uidStats = mUidStats.get(uid);
        if (uidStats == null) {
            uidStats = new UidStats();
            uidStats.stats = new long[mUidStatsSize];
            uidStats.delta = new long[mUidStatsSize];
            mUidStats.put(uid, uidStats);
        }

        boolean nonzero = false;
        for (int i = mUidStatsSize - 1; i >= 0; i--) {
            long delta = uidStats.delta[i] = stats[i] - uidStats.stats[i];
            if (delta != 0) {
                nonzero = true;
            }
            uidStats.stats[i] = stats[i];
        }
        if (nonzero) {
            mCpuPowerStats.uidStats.put(uid, uidStats.delta);
        }
    }

    /**
     * Native class that retrieves CPU stats from the kernel.
     */
    public static class KernelCpuStatsReader {
        protected native long nativeReadCpuStats(KernelCpuStatsCallback callback,
                int[] scalingStepToPowerBracketMap, long lastUpdateTimestampNanos,
                long[] tempForUidStats);
    }

    private static class UidStats {
        public long[] stats;
        public long[] delta;
    }
}
