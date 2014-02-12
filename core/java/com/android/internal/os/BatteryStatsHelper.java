/*
 * Copyright (C) 2009 The Android Open Source Project
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

import static android.os.BatteryStats.NETWORK_MOBILE_RX_DATA;
import static android.os.BatteryStats.NETWORK_MOBILE_TX_DATA;
import static android.os.BatteryStats.NETWORK_WIFI_RX_DATA;
import static android.os.BatteryStats.NETWORK_WIFI_TX_DATA;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.os.BatteryStats;
import android.os.BatteryStats.Uid;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.telephony.SignalStrength;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.app.IBatteryStats;
import com.android.internal.os.BatterySipper.DrainType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A helper class for retrieving the power usage information for all applications and services.
 *
 * The caller must initialize this class as soon as activity object is ready to use (for example, in
 * onAttach() for Fragment), call create() in onCreate() and call destroy() in onDestroy().
 */
public class BatteryStatsHelper {

    private static final boolean DEBUG = false;

    private static final String TAG = BatteryStatsHelper.class.getSimpleName();

    private static BatteryStats sStatsXfer;

    final private Context mContext;

    private IBatteryStats mBatteryInfo;
    private BatteryStats mStats;
    private PowerProfile mPowerProfile;

    private final List<BatterySipper> mUsageList = new ArrayList<BatterySipper>();
    private final List<BatterySipper> mWifiSippers = new ArrayList<BatterySipper>();
    private final List<BatterySipper> mBluetoothSippers = new ArrayList<BatterySipper>();
    private final SparseArray<List<BatterySipper>> mUserSippers
            = new SparseArray<List<BatterySipper>>();
    private final SparseArray<Double> mUserPower = new SparseArray<Double>();

    private int mStatsType = BatteryStats.STATS_SINCE_CHARGED;
    private int mAsUser = 0;

    long mBatteryRealtime;
    long mBatteryUptime;
    long mTypeBatteryRealtime;
    long mTypeBatteryUptime;

    private long mStatsPeriod = 0;
    private double mMaxPower = 1;
    private double mComputedPower;
    private double mTotalPower;
    private double mWifiPower;
    private double mBluetoothPower;
    private double mMinDrainedPower;
    private double mMaxDrainedPower;

    // How much the apps together have left WIFI running.
    private long mAppWifiRunning;

    public BatteryStatsHelper(Context context) {
        mContext = context;
    }

    /** Clears the current stats and forces recreating for future use. */
    public void clearStats() {
        mStats = null;
    }

    public BatteryStats getStats() {
        if (mStats == null) {
            load();
        }
        return mStats;
    }

    public PowerProfile getPowerProfile() {
        return mPowerProfile;
    }

    public void create(BatteryStats stats) {
        mPowerProfile = new PowerProfile(mContext);
        mStats = stats;
    }

    public void create(Bundle icicle) {
        if (icicle != null) {
            mStats = sStatsXfer;
        }
        mBatteryInfo = IBatteryStats.Stub.asInterface(
                ServiceManager.getService(BatteryStats.SERVICE_NAME));
        mPowerProfile = new PowerProfile(mContext);
    }

    public void storeState() {
        sStatsXfer = mStats;
    }

    public static String makemAh(double power) {
        if (power < .0001) return String.format("%.8f", power);
        else if (power < .0001) return String.format("%.7f", power);
        else if (power < .001) return String.format("%.6f", power);
        else if (power < .01) return String.format("%.5f", power);
        else if (power < .1) return String.format("%.4f", power);
        else if (power < 1) return String.format("%.3f", power);
        else if (power < 10) return String.format("%.2f", power);
        else if (power < 100) return String.format("%.1f", power);
        else return String.format("%.0f", power);
    }

    /**
     * Refreshes the power usage list.
     */
    public void refreshStats(int statsType, int asUser) {
        refreshStats(statsType, asUser, SystemClock.elapsedRealtime() * 1000,
                SystemClock.uptimeMillis() * 1000);
    }

    public void refreshStats(int statsType, int asUser, long rawRealtimeNano, long rawUptimeNano) {
        // Initialize mStats if necessary.
        getStats();

        mMaxPower = 0;
        mComputedPower = 0;
        mTotalPower = 0;
        mWifiPower = 0;
        mBluetoothPower = 0;
        mAppWifiRunning = 0;

        mUsageList.clear();
        mWifiSippers.clear();
        mBluetoothSippers.clear();
        mUserSippers.clear();
        mUserPower.clear();

        if (mStats == null) {
            return;
        }

        mStatsType = statsType;
        mAsUser = asUser;
        mBatteryUptime = mStats.getBatteryUptime(rawUptimeNano);
        mBatteryRealtime = mStats.getBatteryRealtime(rawRealtimeNano);
        mTypeBatteryUptime = mStats.computeBatteryUptime(rawUptimeNano, mStatsType);
        mTypeBatteryRealtime = mStats.computeBatteryRealtime(rawRealtimeNano, mStatsType);

        if (DEBUG) {
            Log.d(TAG, "Raw time: realtime=" + (rawRealtimeNano/1000) + " uptime="
                    + (rawUptimeNano/1000));
            Log.d(TAG, "Battery time: realtime=" + (mBatteryRealtime/1000) + " uptime="
                    + (mBatteryUptime/1000));
            Log.d(TAG, "Battery type time: realtime=" + (mTypeBatteryRealtime/1000) + " uptime="
                    + (mTypeBatteryUptime/1000));
        }
        mMinDrainedPower = (mStats.getLowDischargeAmountSinceCharge()
                * mPowerProfile.getBatteryCapacity()) / 100;
        mMaxDrainedPower = (mStats.getHighDischargeAmountSinceCharge()
                * mPowerProfile.getBatteryCapacity()) / 100;

        processAppUsage();
        processMiscUsage();

        if (DEBUG) {
            Log.d(TAG, "Accuracy: total computed=" + makemAh(mComputedPower) + ", min discharge="
                    + makemAh(mMinDrainedPower) + ", max discharge=" + makemAh(mMaxDrainedPower));
        }
        mTotalPower = mComputedPower;
        if (mStats.getLowDischargeAmountSinceCharge() > 1) {
            if (mMinDrainedPower > mComputedPower) {
                double amount = mMinDrainedPower - mComputedPower;
                mTotalPower = mMinDrainedPower;
                addEntryNoTotal(BatterySipper.DrainType.UNACCOUNTED, 0, amount);
            } else if (mMaxDrainedPower < mComputedPower) {
                double amount = mComputedPower - mMaxDrainedPower;
                addEntryNoTotal(BatterySipper.DrainType.OVERCOUNTED, 0, amount);
            }
        }

        Collections.sort(mUsageList);
    }

    private void processAppUsage() {
        SensorManager sensorManager = (SensorManager) mContext.getSystemService(
                Context.SENSOR_SERVICE);
        final int which = mStatsType;
        final int speedSteps = mPowerProfile.getNumSpeedSteps();
        final double[] powerCpuNormal = new double[speedSteps];
        final long[] cpuSpeedStepTimes = new long[speedSteps];
        for (int p = 0; p < speedSteps; p++) {
            powerCpuNormal[p] = mPowerProfile.getAveragePower(PowerProfile.POWER_CPU_ACTIVE, p);
        }
        final double mobilePowerPerPacket = getMobilePowerPerPacket();
        final double wifiPowerPerPacket = getWifiPowerPerPacket();
        long appWakelockTime = 0;
        BatterySipper osApp = null;
        mStatsPeriod = mTypeBatteryRealtime;
        SparseArray<? extends Uid> uidStats = mStats.getUidStats();
        final int NU = uidStats.size();
        for (int iu = 0; iu < NU; iu++) {
            Uid u = uidStats.valueAt(iu);
            double p; // in mAs
            double power = 0; // in mAs
            double highestDrain = 0;
            String packageWithHighestDrain = null;
            Map<String, ? extends BatteryStats.Uid.Proc> processStats = u.getProcessStats();
            long cpuTime = 0;
            long cpuFgTime = 0;
            long wakelockTime = 0;
            long gpsTime = 0;
            if (processStats.size() > 0) {
                // Process CPU time
                for (Map.Entry<String, ? extends BatteryStats.Uid.Proc> ent
                        : processStats.entrySet()) {
                    Uid.Proc ps = ent.getValue();
                    final long userTime = ps.getUserTime(which);
                    final long systemTime = ps.getSystemTime(which);
                    final long foregroundTime = ps.getForegroundTime(which);
                    cpuFgTime += foregroundTime * 10; // convert to millis
                    final long tmpCpuTime = (userTime + systemTime) * 10; // convert to millis
                    int totalTimeAtSpeeds = 0;
                    // Get the total first
                    for (int step = 0; step < speedSteps; step++) {
                        cpuSpeedStepTimes[step] = ps.getTimeAtCpuSpeedStep(step, which);
                        totalTimeAtSpeeds += cpuSpeedStepTimes[step];
                    }
                    if (totalTimeAtSpeeds == 0) totalTimeAtSpeeds = 1;
                    // Then compute the ratio of time spent at each speed
                    double processPower = 0;
                    for (int step = 0; step < speedSteps; step++) {
                        double ratio = (double) cpuSpeedStepTimes[step] / totalTimeAtSpeeds;
                        if (DEBUG && ratio != 0) Log.d(TAG, "UID " + u.getUid() + ": CPU step #"
                                + step + " ratio=" + makemAh(ratio) + " power="
                                + makemAh(ratio*tmpCpuTime*powerCpuNormal[step] / (60*60*1000)));
                        processPower += ratio * tmpCpuTime * powerCpuNormal[step];
                    }
                    cpuTime += tmpCpuTime;
                    if (DEBUG && processPower != 0) {
                        Log.d(TAG, String.format("process %s, cpu power=%s",
                                ent.getKey(), makemAh(processPower / (60*60*1000))));
                    }
                    power += processPower;
                    if (packageWithHighestDrain == null
                            || packageWithHighestDrain.startsWith("*")) {
                        highestDrain = processPower;
                        packageWithHighestDrain = ent.getKey();
                    } else if (highestDrain < processPower
                            && !ent.getKey().startsWith("*")) {
                        highestDrain = processPower;
                        packageWithHighestDrain = ent.getKey();
                    }
                }
            }
            if (cpuFgTime > cpuTime) {
                if (DEBUG && cpuFgTime > cpuTime + 10000) {
                    Log.d(TAG, "WARNING! Cputime is more than 10 seconds behind Foreground time");
                }
                cpuTime = cpuFgTime; // Statistics may not have been gathered yet.
            }
            power /= (60*60*1000);

            // Process wake lock usage
            Map<String, ? extends BatteryStats.Uid.Wakelock> wakelockStats = u.getWakelockStats();
            for (Map.Entry<String, ? extends BatteryStats.Uid.Wakelock> wakelockEntry
                    : wakelockStats.entrySet()) {
                Uid.Wakelock wakelock = wakelockEntry.getValue();
                // Only care about partial wake locks since full wake locks
                // are canceled when the user turns the screen off.
                BatteryStats.Timer timer = wakelock.getWakeTime(BatteryStats.WAKE_TYPE_PARTIAL);
                if (timer != null) {
                    wakelockTime += timer.getTotalTimeLocked(mBatteryRealtime, which);
                }
            }
            wakelockTime /= 1000; // convert to millis
            appWakelockTime += wakelockTime;

            // Add cost of holding a wake lock
            p = (wakelockTime
                    * mPowerProfile.getAveragePower(PowerProfile.POWER_CPU_AWAKE)) / (60*60*1000);
            if (DEBUG && p != 0) Log.d(TAG, "UID " + u.getUid() + ": wake "
                    + wakelockTime + " power=" + makemAh(p));
            power += p;

            // Add cost of mobile traffic
            final long mobileRx = u.getNetworkActivityPackets(NETWORK_MOBILE_RX_DATA, mStatsType);
            final long mobileTx = u.getNetworkActivityPackets(NETWORK_MOBILE_TX_DATA, mStatsType);
            final long mobileRxB = u.getNetworkActivityBytes(NETWORK_MOBILE_RX_DATA, mStatsType);
            final long mobileTxB = u.getNetworkActivityBytes(NETWORK_MOBILE_TX_DATA, mStatsType);
            p = (mobileRx + mobileTx) * mobilePowerPerPacket;
            if (DEBUG && p != 0) Log.d(TAG, "UID " + u.getUid() + ": mobile packets "
                    + (mobileRx+mobileTx) + " power=" + makemAh(p));
            power += p;

            // Add cost of wifi traffic
            final long wifiRx = u.getNetworkActivityPackets(NETWORK_WIFI_RX_DATA, mStatsType);
            final long wifiTx = u.getNetworkActivityPackets(NETWORK_WIFI_TX_DATA, mStatsType);
            final long wifiRxB = u.getNetworkActivityBytes(NETWORK_WIFI_RX_DATA, mStatsType);
            final long wifiTxB = u.getNetworkActivityBytes(NETWORK_WIFI_TX_DATA, mStatsType);
            p = (wifiRx + wifiTx) * wifiPowerPerPacket;
            if (DEBUG && p != 0) Log.d(TAG, "UID " + u.getUid() + ": wifi packets "
                    + (mobileRx+mobileTx) + " power=" + makemAh(p));
            power += p;

            // Add cost of keeping WIFI running.
            long wifiRunningTimeMs = u.getWifiRunningTime(mBatteryRealtime, which) / 1000;
            mAppWifiRunning += wifiRunningTimeMs;
            p = (wifiRunningTimeMs
                    * mPowerProfile.getAveragePower(PowerProfile.POWER_WIFI_ON)) / (60*60*1000);
            if (DEBUG && p != 0) Log.d(TAG, "UID " + u.getUid() + ": wifi running "
                    + wifiRunningTimeMs + " power=" + makemAh(p));
            power += p;

            // Add cost of WIFI scans
            long wifiScanTimeMs = u.getWifiScanTime(mBatteryRealtime, which) / 1000;
            p = (wifiScanTimeMs
                    * mPowerProfile.getAveragePower(PowerProfile.POWER_WIFI_SCAN)) / (60*60*1000);
            if (DEBUG) Log.d(TAG, "UID " + u.getUid() + ": wifi scan " + wifiScanTimeMs
                    + " power=" + makemAh(p));
            power += p;
            for (int bin = 0; bin < BatteryStats.Uid.NUM_WIFI_BATCHED_SCAN_BINS; bin++) {
                long batchScanTimeMs = u.getWifiBatchedScanTime(bin, mBatteryRealtime, which) / 1000;
                p = ((batchScanTimeMs
                        * mPowerProfile.getAveragePower(PowerProfile.POWER_WIFI_BATCHED_SCAN, bin))
                    ) / (60*60*1000);
                if (DEBUG && p != 0) Log.d(TAG, "UID " + u.getUid() + ": wifi batched scan # " + bin
                        + " time=" + batchScanTimeMs + " power=" + makemAh(p));
                power += p;
            }

            // Process Sensor usage
            Map<Integer, ? extends BatteryStats.Uid.Sensor> sensorStats = u.getSensorStats();
            for (Map.Entry<Integer, ? extends BatteryStats.Uid.Sensor> sensorEntry
                    : sensorStats.entrySet()) {
                Uid.Sensor sensor = sensorEntry.getValue();
                int sensorHandle = sensor.getHandle();
                BatteryStats.Timer timer = sensor.getSensorTime();
                long sensorTime = timer.getTotalTimeLocked(mBatteryRealtime, which) / 1000;
                double multiplier = 0;
                switch (sensorHandle) {
                    case Uid.Sensor.GPS:
                        multiplier = mPowerProfile.getAveragePower(PowerProfile.POWER_GPS_ON);
                        gpsTime = sensorTime;
                        break;
                    default:
                        List<Sensor> sensorList = sensorManager.getSensorList(
                                android.hardware.Sensor.TYPE_ALL);
                        for (android.hardware.Sensor s : sensorList) {
                            if (s.getHandle() == sensorHandle) {
                                multiplier = s.getPower();
                                break;
                            }
                        }
                }
                p = (multiplier * sensorTime) / (60*60*1000);
                if (DEBUG && p != 0) Log.d(TAG, "UID " + u.getUid() + ": sensor #" + sensorHandle
                        + " time=" + sensorTime + " power=" + makemAh(p));
                power += p;
            }

            if (DEBUG && power != 0) Log.d(TAG, String.format("UID %d: total power=%s",
                    u.getUid(), makemAh(power)));

            // Add the app to the list if it is consuming power
            final int userId = UserHandle.getUserId(u.getUid());
            if (power != 0 || u.getUid() == 0) {
                BatterySipper app = new BatterySipper(BatterySipper.DrainType.APP, u,
                        new double[] {power});
                app.cpuTime = cpuTime;
                app.gpsTime = gpsTime;
                app.wifiRunningTime = wifiRunningTimeMs;
                app.cpuFgTime = cpuFgTime;
                app.wakeLockTime = wakelockTime;
                app.mobileRxPackets = mobileRx;
                app.mobileTxPackets = mobileTx;
                app.wifiRxPackets = wifiRx;
                app.wifiTxPackets = wifiTx;
                app.mobileRxBytes = mobileRxB;
                app.mobileTxBytes = mobileTxB;
                app.wifiRxBytes = wifiRxB;
                app.wifiTxBytes = wifiTxB;
                app.packageWithHighestDrain = packageWithHighestDrain;
                if (u.getUid() == Process.WIFI_UID) {
                    mWifiSippers.add(app);
                    mWifiPower += power;
                } else if (u.getUid() == Process.BLUETOOTH_UID) {
                    mBluetoothSippers.add(app);
                    mBluetoothPower += power;
                } else if (mAsUser != UserHandle.USER_ALL && userId != mAsUser
                        && UserHandle.getAppId(u.getUid()) >= Process.FIRST_APPLICATION_UID) {
                    List<BatterySipper> list = mUserSippers.get(userId);
                    if (list == null) {
                        list = new ArrayList<BatterySipper>();
                        mUserSippers.put(userId, list);
                    }
                    list.add(app);
                    if (power != 0) {
                        Double userPower = mUserPower.get(userId);
                        if (userPower == null) {
                            userPower = power;
                        } else {
                            userPower += power;
                        }
                        mUserPower.put(userId, userPower);
                    }
                } else {
                    mUsageList.add(app);
                    if (power > mMaxPower) mMaxPower = power;
                    mComputedPower += power;
                }
                if (u.getUid() == 0) {
                    osApp = app;
                }
            }
        }

        // The device has probably been awake for longer than the screen on
        // time and application wake lock time would account for.  Assign
        // this remainder to the OS, if possible.
        if (osApp != null) {
            long wakeTimeMillis = mBatteryUptime / 1000;
            wakeTimeMillis -= appWakelockTime
                    + (mStats.getScreenOnTime(mBatteryRealtime, which) / 1000);
            if (wakeTimeMillis > 0) {
                double power = (wakeTimeMillis
                        * mPowerProfile.getAveragePower(PowerProfile.POWER_CPU_AWAKE))
                        /  (60*60*1000);
                if (DEBUG) Log.d(TAG, "OS wakeLockTime " + wakeTimeMillis + " power "
                        + makemAh(power));
                osApp.wakeLockTime += wakeTimeMillis;
                osApp.value += power;
                osApp.values[0] += power;
                if (osApp.value > mMaxPower) mMaxPower = osApp.value;
                mComputedPower += power;
            }
        }
    }

    private void addPhoneUsage() {
        long phoneOnTimeMs = mStats.getPhoneOnTime(mBatteryRealtime, mStatsType) / 1000;
        double phoneOnPower = mPowerProfile.getAveragePower(PowerProfile.POWER_RADIO_ACTIVE)
                * phoneOnTimeMs / (60*60*1000);
        if (phoneOnPower != 0) {
            addEntry(BatterySipper.DrainType.PHONE, phoneOnTimeMs, phoneOnPower);
        }
    }

    private void addScreenUsage() {
        double power = 0;
        long screenOnTimeMs = mStats.getScreenOnTime(mBatteryRealtime, mStatsType) / 1000;
        power += screenOnTimeMs * mPowerProfile.getAveragePower(PowerProfile.POWER_SCREEN_ON);
        final double screenFullPower =
                mPowerProfile.getAveragePower(PowerProfile.POWER_SCREEN_FULL);
        for (int i = 0; i < BatteryStats.NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            double screenBinPower = screenFullPower * (i + 0.5f)
                    / BatteryStats.NUM_SCREEN_BRIGHTNESS_BINS;
            long brightnessTime = mStats.getScreenBrightnessTime(i, mBatteryRealtime, mStatsType)
                    / 1000;
            double p = screenBinPower*brightnessTime;
            if (DEBUG && p != 0) {
                Log.d(TAG, "Screen bin #" + i + ": time=" + brightnessTime
                        + " power=" + makemAh(p / (60 * 60 * 1000)));
            }
            power += p;
        }
        power /= (60*60*1000); // To hours
        if (power != 0) {
            addEntry(BatterySipper.DrainType.SCREEN, screenOnTimeMs, power);
        }
    }

    private void addRadioUsage() {
        double power = 0;
        final int BINS = SignalStrength.NUM_SIGNAL_STRENGTH_BINS;
        long signalTimeMs = 0;
        long noCoverageTimeMs = 0;
        for (int i = 0; i < BINS; i++) {
            long strengthTimeMs = mStats.getPhoneSignalStrengthTime(i, mBatteryRealtime, mStatsType)
                    / 1000;
            double p = (strengthTimeMs * mPowerProfile.getAveragePower(PowerProfile.POWER_RADIO_ON, i))
                        / (60*60*1000);
            if (DEBUG && p != 0) {
                Log.d(TAG, "Cell strength #" + i + ": time=" + strengthTimeMs + " power="
                        + makemAh(p));
            }
            power += p;
            signalTimeMs += strengthTimeMs;
            if (i == 0) {
                noCoverageTimeMs = strengthTimeMs;
            }
        }
        long scanningTimeMs = mStats.getPhoneSignalScanningTime(mBatteryRealtime, mStatsType)
                / 1000;
        double p = (scanningTimeMs * mPowerProfile.getAveragePower(
                        PowerProfile.POWER_RADIO_SCANNING))
                        / (60*60*1000);
        if (DEBUG && p != 0) {
            Log.d(TAG, "Cell radio scanning: time=" + scanningTimeMs + " power=" + makemAh(p));
        }
        power += p;
        if (power != 0) {
            BatterySipper bs =
                    addEntry(BatterySipper.DrainType.CELL, signalTimeMs, power);
            if (signalTimeMs != 0) {
                bs.noCoveragePercent = noCoverageTimeMs * 100.0 / signalTimeMs;
            }
        }
    }

    private void aggregateSippers(BatterySipper bs, List<BatterySipper> from, String tag) {
        for (int i=0; i<from.size(); i++) {
            BatterySipper wbs = from.get(i);
            if (DEBUG) Log.d(TAG, tag + " adding sipper " + wbs + ": cpu=" + wbs.cpuTime);
            bs.cpuTime += wbs.cpuTime;
            bs.gpsTime += wbs.gpsTime;
            bs.wifiRunningTime += wbs.wifiRunningTime;
            bs.cpuFgTime += wbs.cpuFgTime;
            bs.wakeLockTime += wbs.wakeLockTime;
            bs.mobileRxPackets += wbs.mobileRxPackets;
            bs.mobileTxPackets += wbs.mobileTxPackets;
            bs.wifiRxPackets += wbs.wifiRxPackets;
            bs.wifiTxPackets += wbs.wifiTxPackets;
            bs.mobileRxBytes += wbs.mobileRxBytes;
            bs.mobileTxBytes += wbs.mobileTxBytes;
            bs.wifiRxBytes += wbs.wifiRxBytes;
            bs.wifiTxBytes += wbs.wifiTxBytes;
        }
    }

    private void addWiFiUsage() {
        long onTimeMs = mStats.getWifiOnTime(mBatteryRealtime, mStatsType) / 1000;
        long runningTimeMs = mStats.getGlobalWifiRunningTime(mBatteryRealtime, mStatsType) / 1000;
        if (DEBUG) Log.d(TAG, "WIFI runningTime=" + runningTimeMs
                + " app runningTime=" + mAppWifiRunning);
        runningTimeMs -= mAppWifiRunning;
        if (runningTimeMs < 0) runningTimeMs = 0;
        double wifiPower = (onTimeMs * 0 /* TODO */
                    * mPowerProfile.getAveragePower(PowerProfile.POWER_WIFI_ON)
                + runningTimeMs * mPowerProfile.getAveragePower(PowerProfile.POWER_WIFI_ON))
                / (60*60*1000);
        if (DEBUG && wifiPower != 0) {
            Log.d(TAG, "Wifi: time=" + runningTimeMs + " power=" + makemAh(wifiPower));
        }
        if ((wifiPower+mWifiPower) != 0) {
            BatterySipper bs = addEntry(BatterySipper.DrainType.WIFI, runningTimeMs,
                    wifiPower + mWifiPower);
            aggregateSippers(bs, mWifiSippers, "WIFI");
        }
    }

    private void addIdleUsage() {
        long idleTimeMs = (mTypeBatteryRealtime
                - mStats.getScreenOnTime(mBatteryRealtime, mStatsType)) / 1000;
        double idlePower = (idleTimeMs * mPowerProfile.getAveragePower(PowerProfile.POWER_CPU_IDLE))
                / (60*60*1000);
        if (DEBUG && idlePower != 0) {
            Log.d(TAG, "Idle: time=" + idleTimeMs + " power=" + makemAh(idlePower));
        }
        if (idlePower != 0) {
            addEntry(BatterySipper.DrainType.IDLE, idleTimeMs, idlePower);
        }
    }

    private void addBluetoothUsage() {
        long btOnTimeMs = mStats.getBluetoothOnTime(mBatteryRealtime, mStatsType) / 1000;
        double btPower = btOnTimeMs * mPowerProfile.getAveragePower(PowerProfile.POWER_BLUETOOTH_ON)
                / (60*60*1000);
        if (DEBUG && btPower != 0) {
            Log.d(TAG, "Bluetooth: time=" + btOnTimeMs + " power=" + makemAh(btPower));
        }
        int btPingCount = mStats.getBluetoothPingCount();
        double pingPower = (btPingCount
                * mPowerProfile.getAveragePower(PowerProfile.POWER_BLUETOOTH_AT_CMD))
                / (60*60*1000);
        if (DEBUG && pingPower != 0) {
            Log.d(TAG, "Bluetooth ping: count=" + btPingCount + " power=" + makemAh(pingPower));
        }
        btPower += pingPower;
        if ((btPower+mBluetoothPower) != 0) {
            BatterySipper bs = addEntry(BatterySipper.DrainType.BLUETOOTH, btOnTimeMs,
                    btPower + mBluetoothPower);
            aggregateSippers(bs, mBluetoothSippers, "Bluetooth");
        }
    }

    private void addUserUsage() {
        for (int i=0; i<mUserSippers.size(); i++) {
            final int userId = mUserSippers.keyAt(i);
            final List<BatterySipper> sippers = mUserSippers.valueAt(i);
            Double userPower = mUserPower.get(userId);
            double power = (userPower != null) ? userPower : 0.0;
            BatterySipper bs = addEntry(BatterySipper.DrainType.USER, 0, power);
            bs.userId = userId;
            aggregateSippers(bs, sippers, "User");
        }
    }

    /**
     * Return estimated power (in mAs) of sending or receiving a packet with the mobile radio.
     */
    private double getMobilePowerPerPacket() {
        final long MOBILE_BPS = 200000; // TODO: Extract average bit rates from system
        final double MOBILE_POWER = mPowerProfile.getAveragePower(PowerProfile.POWER_RADIO_ACTIVE)
                / 3600;

        final long mobileRx = mStats.getNetworkActivityPackets(NETWORK_MOBILE_RX_DATA, mStatsType);
        final long mobileTx = mStats.getNetworkActivityPackets(NETWORK_MOBILE_TX_DATA, mStatsType);
        final long mobileData = mobileRx + mobileTx;

        final long radioDataUptimeMs
                = mStats.getMobileRadioActiveTime(mBatteryRealtime, mStatsType) / 1000;
        final double mobilePps = radioDataUptimeMs != 0
                ? mobileData / (double)radioDataUptimeMs
                : (((double)MOBILE_BPS) / 8 / 2048);

        return (MOBILE_POWER / mobilePps) / (60*60);
    }

    /**
     * Return estimated power (in mAs) of sending a byte with the Wi-Fi radio.
     */
    private double getWifiPowerPerPacket() {
        final long WIFI_BPS = 1000000; // TODO: Extract average bit rates from system
        final double WIFI_POWER = mPowerProfile.getAveragePower(PowerProfile.POWER_WIFI_ACTIVE)
                / 3600;
        return (WIFI_POWER / (((double)WIFI_BPS) / 8 / 2048)) / (60*60);
    }

    private void processMiscUsage() {
        addUserUsage();
        addPhoneUsage();
        addScreenUsage();
        addWiFiUsage();
        addBluetoothUsage();
        addIdleUsage(); // Not including cellular idle power
        // Don't compute radio usage if it's a wifi-only device
        ConnectivityManager cm = (ConnectivityManager)mContext.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        if (cm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE)) {
            addRadioUsage();
        }
    }

    private BatterySipper addEntry(DrainType drainType, long time, double power) {
        mComputedPower += power;
        return addEntryNoTotal(drainType, time, power);
    }

    private BatterySipper addEntryNoTotal(DrainType drainType, long time, double power) {
        if (power > mMaxPower) mMaxPower = power;
        BatterySipper bs = new BatterySipper(drainType, null, new double[] {power});
        bs.usageTime = time;
        mUsageList.add(bs);
        return bs;
    }

    public List<BatterySipper> getUsageList() {
        return mUsageList;
    }

    public long getStatsPeriod() { return mStatsPeriod; }

    public int getStatsType() { return mStatsType; };

    public double getMaxPower() { return mMaxPower; }

    public double getTotalPower() { return mTotalPower; }

    public double getComputedPower() { return mComputedPower; }

    public double getMinDrainedPower() {
        return mMinDrainedPower;
    }

    public double getMaxDrainedPower() {
        return mMaxDrainedPower;
    }

    private void load() {
        if (mBatteryInfo == null) {
            return;
        }
        try {
            byte[] data = mBatteryInfo.getStatistics();
            Parcel parcel = Parcel.obtain();
            parcel.unmarshall(data, 0, data.length);
            parcel.setDataPosition(0);
            BatteryStatsImpl stats = com.android.internal.os.BatteryStatsImpl.CREATOR
                    .createFromParcel(parcel);
            stats.distributeWorkLocked(BatteryStats.STATS_SINCE_CHARGED);
            mStats = stats;
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException:", e);
        }
    }
}
