/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.os;

import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.UidBatteryConsumer;
import android.os.UserHandle;
import android.util.Log;
import android.util.SparseArray;

import java.util.Arrays;
import java.util.List;

/**
 * Estimates the amount of power consumed by the System Server handling requests from
 * a given app.
 */
public class SystemServicePowerCalculator extends PowerCalculator {
    private static final boolean DEBUG = false;
    private static final String TAG = "SystemServicePowerCalc";

    private static final long MICROSEC_IN_HR = (long) 60 * 60 * 1000 * 1000;

    private final PowerProfile mPowerProfile;

    // Tracks system server CPU [cluster][speed] power in milliAmp-microseconds
    // Data organized like this:
    // {cluster1-speed1, cluster1-speed2, ..., cluster2-speed1, cluster2-speed2, ...}
    private double[] mSystemServicePowerMaUs;

    public SystemServicePowerCalculator(PowerProfile powerProfile) {
        mPowerProfile = powerProfile;
    }

    @Override
    public void calculate(BatteryUsageStats.Builder builder, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {
        calculateSystemServicePower(batteryStats);
        super.calculate(builder, batteryStats, rawRealtimeUs, rawUptimeUs, query);
    }

    @Override
    protected void calculateApp(UidBatteryConsumer.Builder app, BatteryStats.Uid u,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {
        app.setConsumedPower(BatteryConsumer.POWER_COMPONENT_SYSTEM_SERVICES,
                calculateSystemServerCpuPowerMah(u));
    }

    @Override
    public void calculate(List<BatterySipper> sippers, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, int statsType,
            SparseArray<UserHandle> asUsers) {
        calculateSystemServicePower(batteryStats);
        super.calculate(sippers, batteryStats, rawRealtimeUs, rawUptimeUs, statsType, asUsers);
    }

    @Override
    protected void calculateApp(BatterySipper app, BatteryStats.Uid u, long rawRealtimeUs,
            long rawUptimeUs, int statsType) {
        app.systemServiceCpuPowerMah = calculateSystemServerCpuPowerMah(u);
    }

    private void calculateSystemServicePower(BatteryStats batteryStats) {
        final long[] systemServiceTimeAtCpuSpeeds = batteryStats.getSystemServiceTimeAtCpuSpeeds();
        if (systemServiceTimeAtCpuSpeeds == null) {
            return;
        }

        if (mSystemServicePowerMaUs == null) {
            mSystemServicePowerMaUs = new double[systemServiceTimeAtCpuSpeeds.length];
        }
        int index = 0;
        final int numCpuClusters = mPowerProfile.getNumCpuClusters();
        for (int cluster = 0; cluster < numCpuClusters; cluster++) {
            final int numSpeeds = mPowerProfile.getNumSpeedStepsInCpuCluster(cluster);
            for (int speed = 0; speed < numSpeeds; speed++) {
                mSystemServicePowerMaUs[index] =
                        systemServiceTimeAtCpuSpeeds[index]
                                * mPowerProfile.getAveragePowerForCpuCore(cluster, speed);
                index++;
            }
        }

        if (DEBUG) {
            Log.d(TAG, "System service power per CPU cluster and frequency:"
                    + Arrays.toString(mSystemServicePowerMaUs));
        }
    }

    private double calculateSystemServerCpuPowerMah(BatteryStats.Uid u) {
        double cpuPowerMaUs = 0;
        final double proportionalUsage = u.getProportionalSystemServiceUsage();
        if (proportionalUsage > 0 && mSystemServicePowerMaUs != null) {
            for (int i = 0; i < mSystemServicePowerMaUs.length; i++) {
                cpuPowerMaUs += mSystemServicePowerMaUs[i] * proportionalUsage;
            }
        }
        return cpuPowerMaUs / MICROSEC_IN_HR;
    }

    @Override
    public void reset() {
        mSystemServicePowerMaUs = null;
    }
}
