/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.os.BatteryStats;
import android.os.Process;
import android.os.UserHandle;
import android.util.Log;
import android.util.SparseArray;

import java.util.List;

/**
 * WiFi power calculator for when BatteryStats supports energy reporting
 * from the WiFi controller.
 */
public class WifiPowerCalculator extends PowerCalculator {
    private static final boolean DEBUG = BatteryStatsHelper.DEBUG;
    private static final String TAG = "WifiPowerCalculator";
    private final double mIdleCurrentMa;
    private final double mTxCurrentMa;
    private final double mRxCurrentMa;
    private final PowerProfile mPowerProfile;
    private final boolean mHasWifiPowerController;
    private double mTotalAppPowerDrain = 0;
    private long mTotalAppRunningTime = 0;
    private WifiPowerEstimator mWifiPowerEstimator;
    private boolean mHasWifiPowerReporting;

    public WifiPowerCalculator(PowerProfile profile) {
        mPowerProfile = profile;

        mIdleCurrentMa = profile.getAveragePower(PowerProfile.POWER_WIFI_CONTROLLER_IDLE);
        mTxCurrentMa = profile.getAveragePower(PowerProfile.POWER_WIFI_CONTROLLER_TX);
        mRxCurrentMa = profile.getAveragePower(PowerProfile.POWER_WIFI_CONTROLLER_RX);

        mHasWifiPowerController = mIdleCurrentMa != 0 && mTxCurrentMa != 0 && mRxCurrentMa != 0;
        mWifiPowerEstimator = new WifiPowerEstimator(mPowerProfile);
    }

    /**
     * We do per-app blaming of WiFi activity. If energy info is reported from the controller,
     * then only the WiFi process gets blamed here since we normalize power calculations and
     * assign all the power drain to apps. If energy info is not reported, we attribute the
     * difference between total running time of WiFi for all apps and the actual running time
     * of WiFi to the WiFi subsystem.
     */
    @Override
    public void calculate(List<BatterySipper> sippers, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, int statsType, SparseArray<UserHandle> asUsers) {

        // batteryStats.hasWifiActivityReporting can change if we get energy data at a later point,
        // so always check this field.
        mHasWifiPowerReporting = mHasWifiPowerController && batteryStats.hasWifiActivityReporting();

        super.calculate(sippers, batteryStats, rawRealtimeUs, rawUptimeUs, statsType, asUsers);

        BatterySipper bs = new BatterySipper(BatterySipper.DrainType.WIFI, null, 0);
        calculateRemaining(bs, batteryStats, rawRealtimeUs, rawUptimeUs, statsType);

        for (int i = sippers.size() - 1; i >= 0; i--) {
            BatterySipper app = sippers.get(i);
            if (app.getUid() == Process.WIFI_UID) {
                if (DEBUG) Log.d(TAG, "WiFi adding sipper " + app + ": cpu=" + app.cpuTimeMs);
                app.isAggregated = true;
                bs.add(app);
            }
        }
        if (bs.sumPower() > 0) {
            sippers.add(bs);
        }
    }

    @Override
    protected void calculateApp(BatterySipper app, BatteryStats.Uid u, long rawRealtimeUs,
            long rawUptimeUs, int statsType) {
        if (!mHasWifiPowerReporting) {
            mWifiPowerEstimator.calculateApp(app, u, rawRealtimeUs, rawUptimeUs, statsType);
            return;
        }

        final BatteryStats.ControllerActivityCounter counter = u.getWifiControllerActivity();
        if (counter == null) {
            return;
        }

        final long idleTime = counter.getIdleTimeCounter().getCountLocked(statsType);
        final long txTime = counter.getTxTimeCounters()[0].getCountLocked(statsType);
        final long rxTime = counter.getRxTimeCounter().getCountLocked(statsType);
        app.wifiRunningTimeMs = idleTime + rxTime + txTime;
        mTotalAppRunningTime += app.wifiRunningTimeMs;

        app.wifiPowerMah =
                ((idleTime * mIdleCurrentMa) + (txTime * mTxCurrentMa) + (rxTime * mRxCurrentMa))
                        / (1000 * 60 * 60);
        mTotalAppPowerDrain += app.wifiPowerMah;

        app.wifiRxPackets = u.getNetworkActivityPackets(BatteryStats.NETWORK_WIFI_RX_DATA,
                statsType);
        app.wifiTxPackets = u.getNetworkActivityPackets(BatteryStats.NETWORK_WIFI_TX_DATA,
                statsType);
        app.wifiRxBytes = u.getNetworkActivityBytes(BatteryStats.NETWORK_WIFI_RX_DATA,
                statsType);
        app.wifiTxBytes = u.getNetworkActivityBytes(BatteryStats.NETWORK_WIFI_TX_DATA,
                statsType);

        if (DEBUG && app.wifiPowerMah != 0) {
            Log.d(TAG, "UID " + u.getUid() + ": idle=" + idleTime + "ms rx=" + rxTime + "ms tx=" +
                    txTime + "ms power=" + formatCharge(app.wifiPowerMah));
        }
    }

    private void calculateRemaining(BatterySipper app, BatteryStats stats, long rawRealtimeUs,
            long rawUptimeUs, int statsType) {
        if (!mHasWifiPowerReporting) {
            mWifiPowerEstimator.calculateRemaining(app, stats, rawRealtimeUs, rawUptimeUs,
                    statsType);
            return;
        }

        final BatteryStats.ControllerActivityCounter counter = stats.getWifiControllerActivity();

        final long idleTimeMs = counter.getIdleTimeCounter().getCountLocked(statsType);
        final long txTimeMs = counter.getTxTimeCounters()[0].getCountLocked(statsType);
        final long rxTimeMs = counter.getRxTimeCounter().getCountLocked(statsType);

        app.wifiRunningTimeMs = Math.max(0,
                (idleTimeMs + rxTimeMs + txTimeMs) - mTotalAppRunningTime);

        double powerDrainMah = counter.getPowerCounter().getCountLocked(statsType)
                / (double) (1000 * 60 * 60);
        if (powerDrainMah == 0) {
            // Some controllers do not report power drain, so we can calculate it here.
            powerDrainMah = ((idleTimeMs * mIdleCurrentMa) + (txTimeMs * mTxCurrentMa)
                    + (rxTimeMs * mRxCurrentMa)) / (1000 * 60 * 60);
        }
        app.wifiPowerMah = Math.max(0, powerDrainMah - mTotalAppPowerDrain);

        if (DEBUG) {
            Log.d(TAG, "left over WiFi power: " + formatCharge(app.wifiPowerMah));
        }
    }

    @Override
    public void reset() {
        mTotalAppPowerDrain = 0;
        mTotalAppRunningTime = 0;
        mWifiPowerEstimator.reset();
    }
}
