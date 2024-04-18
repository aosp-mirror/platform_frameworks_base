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

import android.os.PersistableBundle;

/**
 * Captures the positions and lengths of sections of the stats array, such as time-in-state,
 * power usage estimates etc.
 */
public class CpuPowerStatsLayout extends PowerStatsLayout {
    private static final String EXTRA_DEVICE_TIME_BY_SCALING_STEP_POSITION = "dt";
    private static final String EXTRA_DEVICE_TIME_BY_SCALING_STEP_COUNT = "dtc";
    private static final String EXTRA_DEVICE_TIME_BY_CLUSTER_POSITION = "dc";
    private static final String EXTRA_DEVICE_TIME_BY_CLUSTER_COUNT = "dcc";
    private static final String EXTRA_UID_BRACKETS_POSITION = "ub";
    private static final String EXTRA_UID_STATS_SCALING_STEP_TO_POWER_BRACKET = "us";

    private int mDeviceCpuTimeByScalingStepPosition;
    private int mDeviceCpuTimeByScalingStepCount;
    private int mDeviceCpuTimeByClusterPosition;
    private int mDeviceCpuTimeByClusterCount;

    private int mUidPowerBracketsPosition;
    private int mUidPowerBracketCount;

    private int[] mScalingStepToPowerBracketMap;

    /**
     * Declare that the stats array has a section capturing CPU time per scaling step
     */
    public void addDeviceSectionCpuTimeByScalingStep(int scalingStepCount) {
        mDeviceCpuTimeByScalingStepPosition = addDeviceSection(scalingStepCount, "steps");
        mDeviceCpuTimeByScalingStepCount = scalingStepCount;
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
        mDeviceCpuTimeByClusterPosition = addDeviceSection(clusterCount, "clusters");
        mDeviceCpuTimeByClusterCount = clusterCount;
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
     * Declare that the UID stats array has a section capturing CPU time per power bracket.
     */
    public void addUidSectionCpuTimeByPowerBracket(int[] scalingStepToPowerBracketMap) {
        mScalingStepToPowerBracketMap = scalingStepToPowerBracketMap;
        updatePowerBracketCount();
        mUidPowerBracketsPosition = addUidSection(mUidPowerBracketCount, "time");
    }

    private void updatePowerBracketCount() {
        mUidPowerBracketCount = 1;
        for (int bracket : mScalingStepToPowerBracketMap) {
            if (bracket >= mUidPowerBracketCount) {
                mUidPowerBracketCount = bracket + 1;
            }
        }
    }

    public int[] getScalingStepToPowerBracketMap() {
        return mScalingStepToPowerBracketMap;
    }

    public int getCpuPowerBracketCount() {
        return mUidPowerBracketCount;
    }

    /**
     * Saves time in <code>bracket</code> in the corresponding section of <code>stats</code>.
     */
    public void setUidTimeByPowerBracket(long[] stats, int bracket, long value) {
        stats[mUidPowerBracketsPosition + bracket] = value;
    }

    /**
     * Extracts the time in <code>bracket</code> from a UID stats array.
     */
    public long getUidTimeByPowerBracket(long[] stats, int bracket) {
        return stats[mUidPowerBracketsPosition + bracket];
    }

    /**
     * Copies the elements of the stats array layout into <code>extras</code>
     */
    public void toExtras(PersistableBundle extras) {
        super.toExtras(extras);
        extras.putInt(EXTRA_DEVICE_TIME_BY_SCALING_STEP_POSITION,
                mDeviceCpuTimeByScalingStepPosition);
        extras.putInt(EXTRA_DEVICE_TIME_BY_SCALING_STEP_COUNT,
                mDeviceCpuTimeByScalingStepCount);
        extras.putInt(EXTRA_DEVICE_TIME_BY_CLUSTER_POSITION,
                mDeviceCpuTimeByClusterPosition);
        extras.putInt(EXTRA_DEVICE_TIME_BY_CLUSTER_COUNT,
                mDeviceCpuTimeByClusterCount);
        extras.putInt(EXTRA_UID_BRACKETS_POSITION, mUidPowerBracketsPosition);
        putIntArray(extras, EXTRA_UID_STATS_SCALING_STEP_TO_POWER_BRACKET,
                mScalingStepToPowerBracketMap);
    }

    /**
     * Retrieves elements of the stats array layout from <code>extras</code>
     */
    public void fromExtras(PersistableBundle extras) {
        super.fromExtras(extras);
        mDeviceCpuTimeByScalingStepPosition =
                extras.getInt(EXTRA_DEVICE_TIME_BY_SCALING_STEP_POSITION);
        mDeviceCpuTimeByScalingStepCount =
                extras.getInt(EXTRA_DEVICE_TIME_BY_SCALING_STEP_COUNT);
        mDeviceCpuTimeByClusterPosition =
                extras.getInt(EXTRA_DEVICE_TIME_BY_CLUSTER_POSITION);
        mDeviceCpuTimeByClusterCount =
                extras.getInt(EXTRA_DEVICE_TIME_BY_CLUSTER_COUNT);
        mUidPowerBracketsPosition = extras.getInt(EXTRA_UID_BRACKETS_POSITION);
        mScalingStepToPowerBracketMap =
                getIntArray(extras, EXTRA_UID_STATS_SCALING_STEP_TO_POWER_BRACKET);
        if (mScalingStepToPowerBracketMap == null) {
            mScalingStepToPowerBracketMap = new int[mDeviceCpuTimeByScalingStepCount];
        }
        updatePowerBracketCount();
    }
}
