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
import android.telephony.CellSignalStrength;
import android.util.Log;

public class MobileRadioPowerCalculator extends PowerCalculator {
    private static final String TAG = "MobileRadioPowerController";
    private static final boolean DEBUG = BatteryStatsHelper.DEBUG;
    private final double mPowerRadioOn;
    private final double[] mPowerBins = new double[CellSignalStrength.getNumSignalStrengthLevels()];
    private final double mPowerScan;
    private BatteryStats mStats;
    private long mTotalAppMobileActiveMs = 0;

    /**
     * Return estimated power (in mAs) of sending or receiving a packet with the mobile radio.
     */
    private double getMobilePowerPerPacket(long rawRealtimeUs, int statsType) {
        final long MOBILE_BPS = 200000; // TODO: Extract average bit rates from system
        final double MOBILE_POWER = mPowerRadioOn / 3600;

        final long mobileRx = mStats.getNetworkActivityPackets(BatteryStats.NETWORK_MOBILE_RX_DATA,
                statsType);
        final long mobileTx = mStats.getNetworkActivityPackets(BatteryStats.NETWORK_MOBILE_TX_DATA,
                statsType);
        final long mobileData = mobileRx + mobileTx;

        final long radioDataUptimeMs =
                mStats.getMobileRadioActiveTime(rawRealtimeUs, statsType) / 1000;
        final double mobilePps = (mobileData != 0 && radioDataUptimeMs != 0)
                ? (mobileData / (double)radioDataUptimeMs)
                : (((double)MOBILE_BPS) / 8 / 2048);
        return (MOBILE_POWER / mobilePps) / (60*60);
    }

    public MobileRadioPowerCalculator(PowerProfile profile, BatteryStats stats) {
        double temp =
                profile.getAveragePowerOrDefault(PowerProfile.POWER_RADIO_ACTIVE, -1);
        if (temp != -1) {
            mPowerRadioOn = temp;
        } else {
            double sum = 0;
            sum += profile.getAveragePower(PowerProfile.POWER_MODEM_CONTROLLER_RX);
            for (int i = 0; i < mPowerBins.length; i++) {
                sum += profile.getAveragePower(PowerProfile.POWER_MODEM_CONTROLLER_TX, i);
            }
            mPowerRadioOn = sum / (mPowerBins.length + 1);
        }

        temp = profile.getAveragePowerOrDefault(PowerProfile.POWER_RADIO_ON, -1);
        if (temp != -1 ) {
            for (int i = 0; i < mPowerBins.length; i++) {
                mPowerBins[i] = profile.getAveragePower(PowerProfile.POWER_RADIO_ON, i);
            }
        } else {
            double idle = profile.getAveragePower(PowerProfile.POWER_MODEM_CONTROLLER_IDLE);
            mPowerBins[0] = idle * 25 / 180;
            for (int i = 1; i < mPowerBins.length; i++) {
                mPowerBins[i] = Math.max(1, idle / 256);
            }
        }

        mPowerScan = profile.getAveragePowerOrDefault(PowerProfile.POWER_RADIO_SCANNING, 0);
        mStats = stats;
    }

    @Override
    public void calculateApp(BatterySipper app, BatteryStats.Uid u, long rawRealtimeUs,
                             long rawUptimeUs, int statsType) {
        // Add cost of mobile traffic.
        app.mobileRxPackets = u.getNetworkActivityPackets(BatteryStats.NETWORK_MOBILE_RX_DATA,
                statsType);
        app.mobileTxPackets = u.getNetworkActivityPackets(BatteryStats.NETWORK_MOBILE_TX_DATA,
                statsType);
        app.mobileActive = u.getMobileRadioActiveTime(statsType) / 1000;
        app.mobileActiveCount = u.getMobileRadioActiveCount(statsType);
        app.mobileRxBytes = u.getNetworkActivityBytes(BatteryStats.NETWORK_MOBILE_RX_DATA,
                statsType);
        app.mobileTxBytes = u.getNetworkActivityBytes(BatteryStats.NETWORK_MOBILE_TX_DATA,
                statsType);

        if (app.mobileActive > 0) {
            // We are tracking when the radio is up, so can use the active time to
            // determine power use.
            mTotalAppMobileActiveMs += app.mobileActive;
            app.mobileRadioPowerMah = (app.mobileActive * mPowerRadioOn) / (1000*60*60);
        } else {
            // We are not tracking when the radio is up, so must approximate power use
            // based on the number of packets.
            app.mobileRadioPowerMah = (app.mobileRxPackets + app.mobileTxPackets)
                    * getMobilePowerPerPacket(rawRealtimeUs, statsType);
        }
        if (DEBUG && app.mobileRadioPowerMah != 0) {
            Log.d(TAG, "UID " + u.getUid() + ": mobile packets "
                    + (app.mobileRxPackets + app.mobileTxPackets)
                    + " active time " + app.mobileActive
                    + " power=" + BatteryStatsHelper.makemAh(app.mobileRadioPowerMah));
        }
    }

    @Override
    public void calculateRemaining(BatterySipper app, BatteryStats stats, long rawRealtimeUs,
                                   long rawUptimeUs, int statsType) {
        double power = 0;
        long signalTimeMs = 0;
        long noCoverageTimeMs = 0;
        for (int i = 0; i < mPowerBins.length; i++) {
            long strengthTimeMs = stats.getPhoneSignalStrengthTime(i, rawRealtimeUs, statsType)
                    / 1000;
            final double p = (strengthTimeMs * mPowerBins[i]) / (60*60*1000);
            if (DEBUG && p != 0) {
                Log.d(TAG, "Cell strength #" + i + ": time=" + strengthTimeMs + " power="
                        + BatteryStatsHelper.makemAh(p));
            }
            power += p;
            signalTimeMs += strengthTimeMs;
            if (i == 0) {
                noCoverageTimeMs = strengthTimeMs;
            }
        }

        final long scanningTimeMs = stats.getPhoneSignalScanningTime(rawRealtimeUs, statsType)
                / 1000;
        final double p = (scanningTimeMs * mPowerScan) / (60*60*1000);
        if (DEBUG && p != 0) {
            Log.d(TAG, "Cell radio scanning: time=" + scanningTimeMs
                    + " power=" + BatteryStatsHelper.makemAh(p));
        }
        power += p;
        long radioActiveTimeMs = mStats.getMobileRadioActiveTime(rawRealtimeUs, statsType) / 1000;
        long remainingActiveTimeMs = radioActiveTimeMs - mTotalAppMobileActiveMs;
        if (remainingActiveTimeMs > 0) {
            power += (mPowerRadioOn * remainingActiveTimeMs) / (1000*60*60);
        }

        if (power != 0) {
            if (signalTimeMs != 0) {
                app.noCoveragePercent = noCoverageTimeMs * 100.0 / signalTimeMs;
            }
            app.mobileActive = remainingActiveTimeMs;
            app.mobileActiveCount = stats.getMobileRadioActiveUnknownCount(statsType);
            app.mobileRadioPowerMah = power;
        }
    }

    @Override
    public void reset() {
        mTotalAppMobileActiveMs = 0;
    }

    public void reset(BatteryStats stats) {
        reset();
        mStats = stats;
    }
}
