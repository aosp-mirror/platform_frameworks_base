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

import android.content.Context;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.os.BatteryStats;
import android.os.BatteryUsageStats;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.UidBatteryConsumer;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.List;

/**
 * Uses accumulated battery stats data and PowerCalculators to produce power
 * usage data attributed to subsystems and UIDs.
 */
public class BatteryUsageStatsProvider {
    private final Context mContext;
    private final BatteryStatsImpl mStats;
    private final PowerProfile mPowerProfile;
    private final Object mLock = new Object();
    private List<PowerCalculator> mPowerCalculators;

    public BatteryUsageStatsProvider(Context context, BatteryStatsImpl stats) {
        mContext = context;
        mStats = stats;
        mPowerProfile = new PowerProfile(mContext);
    }

    private List<PowerCalculator> getPowerCalculators() {
        synchronized (mLock) {
            if (mPowerCalculators == null) {
                mPowerCalculators = new ArrayList<>();

                // Power calculators are applied in the order of registration
                mPowerCalculators.add(new CpuPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new MemoryPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new WakelockPowerCalculator(mPowerProfile));
                if (!isWifiOnlyDevice(mContext)) {
                    mPowerCalculators.add(new MobileRadioPowerCalculator(mPowerProfile));
                }
                mPowerCalculators.add(new WifiPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new BluetoothPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new SensorPowerCalculator(mPowerProfile,
                        mContext.getSystemService(SensorManager.class)));
                mPowerCalculators.add(new CameraPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new FlashlightPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new MediaPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new PhonePowerCalculator(mPowerProfile));
                mPowerCalculators.add(new ScreenPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new AmbientDisplayPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new SystemServicePowerCalculator(mPowerProfile));
                mPowerCalculators.add(new IdlePowerCalculator(mPowerProfile));

                mPowerCalculators.add(new UserPowerCalculator());
            }
        }
        return mPowerCalculators;
    }

    private static boolean isWifiOnlyDevice(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null) {
            return false;
        }
        return !cm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE);
    }

    /**
     * Returns a snapshot of battery attribution data.
     */
    public BatteryUsageStats getBatteryUsageStats() {

        // TODO(b/174186345): instead of BatteryStatsHelper, use PowerCalculators directly.
        final BatteryStatsHelper batteryStatsHelper = new BatteryStatsHelper(mContext,
                false /* collectBatteryBroadcast */);
        batteryStatsHelper.create((Bundle) null);
        final UserManager userManager = mContext.getSystemService(UserManager.class);
        final List<UserHandle> asUsers = userManager.getUserProfiles();
        final int n = asUsers.size();
        SparseArray<UserHandle> users = new SparseArray<>(n);
        for (int i = 0; i < n; ++i) {
            UserHandle userHandle = asUsers.get(i);
            users.put(userHandle.getIdentifier(), userHandle);
        }

        batteryStatsHelper.refreshStats(BatteryStats.STATS_SINCE_CHARGED, users);

        // TODO(b/174186358): read extra power component number from configuration
        final int customPowerComponentCount = 0;
        final int customTimeComponentCount = 0;
        final BatteryUsageStats.Builder batteryUsageStatsBuilder = new BatteryUsageStats.Builder()
                .setDischargePercentage(batteryStatsHelper.getStats().getDischargeAmount(0))
                .setConsumedPower(batteryStatsHelper.getTotalPower());

        final List<BatterySipper> usageList = batteryStatsHelper.getUsageList();
        for (int i = 0; i < usageList.size(); i++) {
            final BatterySipper sipper = usageList.get(i);
            if (sipper.drainType == BatterySipper.DrainType.APP) {
                batteryUsageStatsBuilder.addUidBatteryConsumerBuilder(
                        new UidBatteryConsumer.Builder(customPowerComponentCount,
                                customTimeComponentCount, sipper.uidObj)
                                .setPackageWithHighestDrain(sipper.packageWithHighestDrain)
                                .setConsumedPower(sipper.sumPower()));
            }
        }

        final long realtimeUs = SystemClock.elapsedRealtime() * 1000;
        final long uptimeUs = SystemClock.uptimeMillis() * 1000;

        final List<PowerCalculator> powerCalculators = getPowerCalculators();
        for (PowerCalculator powerCalculator : powerCalculators) {
            powerCalculator.calculate(batteryUsageStatsBuilder, mStats, realtimeUs, uptimeUs,
                    BatteryStats.STATS_SINCE_CHARGED, users);
        }

        return batteryUsageStatsBuilder.build();
    }
}
