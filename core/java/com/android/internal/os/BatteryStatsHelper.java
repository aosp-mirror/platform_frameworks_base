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
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.os.BatteryStats;
import android.os.BatteryStats.Uid;
import android.os.Bundle;
import android.os.MemoryFile;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.telephony.SignalStrength;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.app.IBatteryStats;
import com.android.internal.os.BatterySipper.DrainType;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * A helper class for retrieving the power usage information for all applications and services.
 *
 * The caller must initialize this class as soon as activity object is ready to use (for example, in
 * onAttach() for Fragment), call create() in onCreate() and call destroy() in onDestroy().
 */
public final class BatteryStatsHelper {

    private static final boolean DEBUG = false;

    private static final String TAG = BatteryStatsHelper.class.getSimpleName();

    private static BatteryStats sStatsXfer;
    private static Intent sBatteryBroadcastXfer;
    private static ArrayMap<File, BatteryStats> sFileXfer = new ArrayMap<>();

    final private Context mContext;
    final private boolean mCollectBatteryBroadcast;
    final private boolean mWifiOnly;

    private IBatteryStats mBatteryInfo;
    private BatteryStats mStats;
    private Intent mBatteryBroadcast;
    private PowerProfile mPowerProfile;

    private final List<BatterySipper> mUsageList = new ArrayList<BatterySipper>();
    private final List<BatterySipper> mWifiSippers = new ArrayList<BatterySipper>();
    private final List<BatterySipper> mBluetoothSippers = new ArrayList<BatterySipper>();
    private final SparseArray<List<BatterySipper>> mUserSippers
            = new SparseArray<List<BatterySipper>>();
    private final SparseArray<Double> mUserPower = new SparseArray<Double>();

    private final List<BatterySipper> mMobilemsppList = new ArrayList<BatterySipper>();

    private int mStatsType = BatteryStats.STATS_SINCE_CHARGED;

    long mRawRealtime;
    long mRawUptime;
    long mBatteryRealtime;
    long mBatteryUptime;
    long mTypeBatteryRealtime;
    long mTypeBatteryUptime;
    long mBatteryTimeRemaining;
    long mChargeTimeRemaining;

    private long mStatsPeriod = 0;
    private double mMaxPower = 1;
    private double mMaxRealPower = 1;
    private double mComputedPower;
    private double mTotalPower;
    private double mWifiPower;
    private double mBluetoothPower;
    private double mMinDrainedPower;
    private double mMaxDrainedPower;

    // How much the apps together have kept the mobile radio active.
    private long mAppMobileActive;

    // How much the apps together have left WIFI running.
    private long mAppWifiRunning;

    public BatteryStatsHelper(Context context) {
        this(context, true);
    }

    public BatteryStatsHelper(Context context, boolean collectBatteryBroadcast) {
        mContext = context;
        mCollectBatteryBroadcast = collectBatteryBroadcast;
        mWifiOnly = checkWifiOnly(context);
    }

    public BatteryStatsHelper(Context context, boolean collectBatteryBroadcast, boolean wifiOnly) {
        mContext = context;
        mCollectBatteryBroadcast = collectBatteryBroadcast;
        mWifiOnly = wifiOnly;
    }

    public static boolean checkWifiOnly(Context context) {
        ConnectivityManager cm = (ConnectivityManager)context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        return !cm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE);
    }

    public void storeStatsHistoryInFile(String fname) {
        synchronized (sFileXfer) {
            File path = makeFilePath(mContext, fname);
            sFileXfer.put(path, this.getStats());
            FileOutputStream fout = null;
            try {
                fout = new FileOutputStream(path);
                Parcel hist = Parcel.obtain();
                getStats().writeToParcelWithoutUids(hist, 0);
                byte[] histData = hist.marshall();
                fout.write(histData);
            } catch (IOException e) {
                Log.w(TAG, "Unable to write history to file", e);
            } finally {
                if (fout != null) {
                    try {
                        fout.close();
                    } catch (IOException e) {
                    }
                }
            }
        }
    }

    public static BatteryStats statsFromFile(Context context, String fname) {
        synchronized (sFileXfer) {
            File path = makeFilePath(context, fname);
            BatteryStats stats = sFileXfer.get(path);
            if (stats != null) {
                return stats;
            }
            FileInputStream fin = null;
            try {
                fin = new FileInputStream(path);
                byte[] data = readFully(fin);
                Parcel parcel = Parcel.obtain();
                parcel.unmarshall(data, 0, data.length);
                parcel.setDataPosition(0);
                return com.android.internal.os.BatteryStatsImpl.CREATOR.createFromParcel(parcel);
            } catch (IOException e) {
                Log.w(TAG, "Unable to read history to file", e);
            } finally {
                if (fin != null) {
                    try {
                        fin.close();
                    } catch (IOException e) {
                    }
                }
            }
        }
        return getStats(IBatteryStats.Stub.asInterface(
                        ServiceManager.getService(BatteryStats.SERVICE_NAME)));
    }

    public static void dropFile(Context context, String fname) {
        makeFilePath(context, fname).delete();
    }

    private static File makeFilePath(Context context, String fname) {
        return new File(context.getFilesDir(), fname);
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

    public Intent getBatteryBroadcast() {
        if (mBatteryBroadcast == null && mCollectBatteryBroadcast) {
            load();
        }
        return mBatteryBroadcast;
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
            mBatteryBroadcast = sBatteryBroadcastXfer;
        }
        mBatteryInfo = IBatteryStats.Stub.asInterface(
                ServiceManager.getService(BatteryStats.SERVICE_NAME));
        mPowerProfile = new PowerProfile(mContext);
    }

    public void storeState() {
        sStatsXfer = mStats;
        sBatteryBroadcastXfer = mBatteryBroadcast;
    }

    public static String makemAh(double power) {
        if (power < .00001) return String.format("%.8f", power);
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
        SparseArray<UserHandle> users = new SparseArray<UserHandle>(1);
        users.put(asUser, new UserHandle(asUser));
        refreshStats(statsType, users);
    }

    /**
     * Refreshes the power usage list.
     */
    public void refreshStats(int statsType, List<UserHandle> asUsers) {
        final int n = asUsers.size();
        SparseArray<UserHandle> users = new SparseArray<UserHandle>(n);
        for (int i = 0; i < n; ++i) {
            UserHandle userHandle = asUsers.get(i);
            users.put(userHandle.getIdentifier(), userHandle);
        }
        refreshStats(statsType, users);
    }

    /**
     * Refreshes the power usage list.
     */
    public void refreshStats(int statsType, SparseArray<UserHandle> asUsers) {
        refreshStats(statsType, asUsers, SystemClock.elapsedRealtime() * 1000,
                SystemClock.uptimeMillis() * 1000);
    }

    public void refreshStats(int statsType, SparseArray<UserHandle> asUsers, long rawRealtimeUs,
            long rawUptimeUs) {
        // Initialize mStats if necessary.
        getStats();

        mMaxPower = 0;
        mMaxRealPower = 0;
        mComputedPower = 0;
        mTotalPower = 0;
        mWifiPower = 0;
        mBluetoothPower = 0;
        mAppMobileActive = 0;
        mAppWifiRunning = 0;

        mUsageList.clear();
        mWifiSippers.clear();
        mBluetoothSippers.clear();
        mUserSippers.clear();
        mUserPower.clear();
        mMobilemsppList.clear();

        if (mStats == null) {
            return;
        }

        mStatsType = statsType;
        mRawUptime = rawUptimeUs;
        mRawRealtime = rawRealtimeUs;
        mBatteryUptime = mStats.getBatteryUptime(rawUptimeUs);
        mBatteryRealtime = mStats.getBatteryRealtime(rawRealtimeUs);
        mTypeBatteryUptime = mStats.computeBatteryUptime(rawUptimeUs, mStatsType);
        mTypeBatteryRealtime = mStats.computeBatteryRealtime(rawRealtimeUs, mStatsType);
        mBatteryTimeRemaining = mStats.computeBatteryTimeRemaining(rawRealtimeUs);
        mChargeTimeRemaining = mStats.computeChargeTimeRemaining(rawRealtimeUs);

        if (DEBUG) {
            Log.d(TAG, "Raw time: realtime=" + (rawRealtimeUs/1000) + " uptime="
                    + (rawUptimeUs/1000));
            Log.d(TAG, "Battery time: realtime=" + (mBatteryRealtime/1000) + " uptime="
                    + (mBatteryUptime/1000));
            Log.d(TAG, "Battery type time: realtime=" + (mTypeBatteryRealtime/1000) + " uptime="
                    + (mTypeBatteryUptime/1000));
        }
        mMinDrainedPower = (mStats.getLowDischargeAmountSinceCharge()
                * mPowerProfile.getBatteryCapacity()) / 100;
        mMaxDrainedPower = (mStats.getHighDischargeAmountSinceCharge()
                * mPowerProfile.getBatteryCapacity()) / 100;

        processAppUsage(asUsers);

        // Before aggregating apps in to users, collect all apps to sort by their ms per packet.
        for (int i=0; i<mUsageList.size(); i++) {
            BatterySipper bs = mUsageList.get(i);
            bs.computeMobilemspp();
            if (bs.mobilemspp != 0) {
                mMobilemsppList.add(bs);
            }
        }
        for (int i=0; i<mUserSippers.size(); i++) {
            List<BatterySipper> user = mUserSippers.valueAt(i);
            for (int j=0; j<user.size(); j++) {
                BatterySipper bs = user.get(j);
                bs.computeMobilemspp();
                if (bs.mobilemspp != 0) {
                    mMobilemsppList.add(bs);
                }
            }
        }
        Collections.sort(mMobilemsppList, new Comparator<BatterySipper>() {
            @Override
            public int compare(BatterySipper lhs, BatterySipper rhs) {
                if (lhs.mobilemspp < rhs.mobilemspp) {
                    return 1;
                } else if (lhs.mobilemspp > rhs.mobilemspp) {
                    return -1;
                }
                return 0;
            }
        });

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

    private void processAppUsage(SparseArray<UserHandle> asUsers) {
        final boolean forAllUsers = (asUsers.get(UserHandle.USER_ALL) != null);
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
        final double mobilePowerPerMs = getMobilePowerPerMs();
        final double wifiPowerPerPacket = getWifiPowerPerPacket();
        long appWakelockTimeUs = 0;
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
                    wakelockTime += timer.getTotalTimeLocked(mRawRealtime, which);
                }
            }
            appWakelockTimeUs += wakelockTime;
            wakelockTime /= 1000; // convert to millis

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
            final long mobileActive = u.getMobileRadioActiveTime(mStatsType);
            if (mobileActive > 0) {
                // We are tracking when the radio is up, so can use the active time to
                // determine power use.
                mAppMobileActive += mobileActive;
                p = (mobilePowerPerMs * mobileActive) / 1000;
            } else {
                // We are not tracking when the radio is up, so must approximate power use
                // based on the number of packets.
                p = (mobileRx + mobileTx) * mobilePowerPerPacket;
            }
            if (DEBUG && p != 0) Log.d(TAG, "UID " + u.getUid() + ": mobile packets "
                    + (mobileRx+mobileTx) + " active time " + mobileActive
                    + " power=" + makemAh(p));
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
            long wifiRunningTimeMs = u.getWifiRunningTime(mRawRealtime, which) / 1000;
            mAppWifiRunning += wifiRunningTimeMs;
            p = (wifiRunningTimeMs
                    * mPowerProfile.getAveragePower(PowerProfile.POWER_WIFI_ON)) / (60*60*1000);
            if (DEBUG && p != 0) Log.d(TAG, "UID " + u.getUid() + ": wifi running "
                    + wifiRunningTimeMs + " power=" + makemAh(p));
            power += p;

            // Add cost of WIFI scans
            long wifiScanTimeMs = u.getWifiScanTime(mRawRealtime, which) / 1000;
            p = (wifiScanTimeMs
                    * mPowerProfile.getAveragePower(PowerProfile.POWER_WIFI_SCAN)) / (60*60*1000);
            if (DEBUG) Log.d(TAG, "UID " + u.getUid() + ": wifi scan " + wifiScanTimeMs
                    + " power=" + makemAh(p));
            power += p;
            for (int bin = 0; bin < BatteryStats.Uid.NUM_WIFI_BATCHED_SCAN_BINS; bin++) {
                long batchScanTimeMs = u.getWifiBatchedScanTime(bin, mRawRealtime, which) / 1000;
                p = ((batchScanTimeMs
                        * mPowerProfile.getAveragePower(PowerProfile.POWER_WIFI_BATCHED_SCAN, bin))
                    ) / (60*60*1000);
                if (DEBUG && p != 0) Log.d(TAG, "UID " + u.getUid() + ": wifi batched scan # " + bin
                        + " time=" + batchScanTimeMs + " power=" + makemAh(p));
                power += p;
            }

            // Process Sensor usage
            SparseArray<? extends BatteryStats.Uid.Sensor> sensorStats = u.getSensorStats();
            int NSE = sensorStats.size();
            for (int ise=0; ise<NSE; ise++) {
                Uid.Sensor sensor = sensorStats.valueAt(ise);
                int sensorHandle = sensorStats.keyAt(ise);
                BatteryStats.Timer timer = sensor.getSensorTime();
                long sensorTime = timer.getTotalTimeLocked(mRawRealtime, which) / 1000;
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
                app.mobileActive = mobileActive / 1000;
                app.mobileActiveCount = u.getMobileRadioActiveCount(mStatsType);
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
                } else if (!forAllUsers && asUsers.get(userId) == null
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
                    if (power > mMaxRealPower) mMaxRealPower = power;
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
            wakeTimeMillis -= (appWakelockTimeUs / 1000)
                    + (mStats.getScreenOnTime(mRawRealtime, which) / 1000);
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
                if (osApp.value > mMaxRealPower) mMaxRealPower = osApp.value;
                mComputedPower += power;
            }
        }
    }

    private void addPhoneUsage() {
        long phoneOnTimeMs = mStats.getPhoneOnTime(mRawRealtime, mStatsType) / 1000;
        double phoneOnPower = mPowerProfile.getAveragePower(PowerProfile.POWER_RADIO_ACTIVE)
                * phoneOnTimeMs / (60*60*1000);
        if (phoneOnPower != 0) {
            BatterySipper bs = addEntry(BatterySipper.DrainType.PHONE, phoneOnTimeMs, phoneOnPower);
        }
    }

    private void addScreenUsage() {
        double power = 0;
        long screenOnTimeMs = mStats.getScreenOnTime(mRawRealtime, mStatsType) / 1000;
        power += screenOnTimeMs * mPowerProfile.getAveragePower(PowerProfile.POWER_SCREEN_ON);
        final double screenFullPower =
                mPowerProfile.getAveragePower(PowerProfile.POWER_SCREEN_FULL);
        for (int i = 0; i < BatteryStats.NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            double screenBinPower = screenFullPower * (i + 0.5f)
                    / BatteryStats.NUM_SCREEN_BRIGHTNESS_BINS;
            long brightnessTime = mStats.getScreenBrightnessTime(i, mRawRealtime, mStatsType)
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
            long strengthTimeMs = mStats.getPhoneSignalStrengthTime(i, mRawRealtime, mStatsType)
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
        long scanningTimeMs = mStats.getPhoneSignalScanningTime(mRawRealtime, mStatsType)
                / 1000;
        double p = (scanningTimeMs * mPowerProfile.getAveragePower(
                        PowerProfile.POWER_RADIO_SCANNING))
                        / (60*60*1000);
        if (DEBUG && p != 0) {
            Log.d(TAG, "Cell radio scanning: time=" + scanningTimeMs + " power=" + makemAh(p));
        }
        power += p;
        long radioActiveTimeUs = mStats.getMobileRadioActiveTime(mRawRealtime, mStatsType);
        long remainingActiveTime = (radioActiveTimeUs - mAppMobileActive) / 1000;
        if (remainingActiveTime > 0) {
            power += getMobilePowerPerMs() * remainingActiveTime;
        }
        if (power != 0) {
            BatterySipper bs =
                    addEntry(BatterySipper.DrainType.CELL, signalTimeMs, power);
            if (signalTimeMs != 0) {
                bs.noCoveragePercent = noCoverageTimeMs * 100.0 / signalTimeMs;
            }
            bs.mobileActive = remainingActiveTime;
            bs.mobileActiveCount = mStats.getMobileRadioActiveUnknownCount(mStatsType);
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
            bs.mobileActive += wbs.mobileActive;
            bs.mobileActiveCount += wbs.mobileActiveCount;
            bs.wifiRxPackets += wbs.wifiRxPackets;
            bs.wifiTxPackets += wbs.wifiTxPackets;
            bs.mobileRxBytes += wbs.mobileRxBytes;
            bs.mobileTxBytes += wbs.mobileTxBytes;
            bs.wifiRxBytes += wbs.wifiRxBytes;
            bs.wifiTxBytes += wbs.wifiTxBytes;
        }
        bs.computeMobilemspp();
    }

    private void addWiFiUsage() {
        long onTimeMs = mStats.getWifiOnTime(mRawRealtime, mStatsType) / 1000;
        long runningTimeMs = mStats.getGlobalWifiRunningTime(mRawRealtime, mStatsType) / 1000;
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
                - mStats.getScreenOnTime(mRawRealtime, mStatsType)) / 1000;
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
        long btOnTimeMs = mStats.getBluetoothOnTime(mRawRealtime, mStatsType) / 1000;
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

    private void addFlashlightUsage() {
        long flashlightOnTimeMs = mStats.getFlashlightOnTime(mRawRealtime, mStatsType) / 1000;
        double flashlightPower = flashlightOnTimeMs
                * mPowerProfile.getAveragePower(PowerProfile.POWER_FLASHLIGHT) / (60*60*1000);
        if (flashlightPower != 0) {
            addEntry(BatterySipper.DrainType.FLASHLIGHT, flashlightOnTimeMs, flashlightPower);
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
                = mStats.getMobileRadioActiveTime(mRawRealtime, mStatsType) / 1000;
        final double mobilePps = (mobileData != 0 && radioDataUptimeMs != 0)
                ? (mobileData / (double)radioDataUptimeMs)
                : (((double)MOBILE_BPS) / 8 / 2048);

        return (MOBILE_POWER / mobilePps) / (60*60);
    }

    /**
     * Return estimated power (in mAs) of keeping the radio up
     */
    private double getMobilePowerPerMs() {
        return mPowerProfile.getAveragePower(PowerProfile.POWER_RADIO_ACTIVE) / (60*60*1000);
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
        addFlashlightUsage();
        addWiFiUsage();
        addBluetoothUsage();
        addIdleUsage(); // Not including cellular idle power
        // Don't compute radio usage if it's a wifi-only device
        if (!mWifiOnly) {
            addRadioUsage();
        }
    }

    private BatterySipper addEntry(DrainType drainType, long time, double power) {
        mComputedPower += power;
        if (power > mMaxRealPower) mMaxRealPower = power;
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

    public List<BatterySipper> getMobilemsppList() {
        return mMobilemsppList;
    }

    public long getStatsPeriod() { return mStatsPeriod; }

    public int getStatsType() { return mStatsType; };

    public double getMaxPower() { return mMaxPower; }

    public double getMaxRealPower() { return mMaxRealPower; }

    public double getTotalPower() { return mTotalPower; }

    public double getComputedPower() { return mComputedPower; }

    public double getMinDrainedPower() {
        return mMinDrainedPower;
    }

    public double getMaxDrainedPower() {
        return mMaxDrainedPower;
    }

    public long getBatteryTimeRemaining() { return mBatteryTimeRemaining; }

    public long getChargeTimeRemaining() { return mChargeTimeRemaining; }

    public static byte[] readFully(FileInputStream stream) throws java.io.IOException {
        return readFully(stream, stream.available());
    }

    public static byte[] readFully(FileInputStream stream, int avail) throws java.io.IOException {
        int pos = 0;
        byte[] data = new byte[avail];
        while (true) {
            int amt = stream.read(data, pos, data.length-pos);
            //Log.i("foo", "Read " + amt + " bytes at " + pos
            //        + " of avail " + data.length);
            if (amt <= 0) {
                //Log.i("foo", "**** FINISHED READING: pos=" + pos
                //        + " len=" + data.length);
                return data;
            }
            pos += amt;
            avail = stream.available();
            if (avail > data.length-pos) {
                byte[] newData = new byte[pos+avail];
                System.arraycopy(data, 0, newData, 0, pos);
                data = newData;
            }
        }
    }

    private void load() {
        if (mBatteryInfo == null) {
            return;
        }
        mStats = getStats(mBatteryInfo);
        if (mCollectBatteryBroadcast) {
            mBatteryBroadcast = mContext.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        }
    }

    private static BatteryStatsImpl getStats(IBatteryStats service) {
        try {
            ParcelFileDescriptor pfd = service.getStatisticsStream();
            if (pfd != null) {
                FileInputStream fis = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
                try {
                    byte[] data = readFully(fis, MemoryFile.getSize(pfd.getFileDescriptor()));
                    Parcel parcel = Parcel.obtain();
                    parcel.unmarshall(data, 0, data.length);
                    parcel.setDataPosition(0);
                    BatteryStatsImpl stats = com.android.internal.os.BatteryStatsImpl.CREATOR
                            .createFromParcel(parcel);
                    stats.distributeWorkLocked(BatteryStats.STATS_SINCE_CHARGED);
                    return stats;
                } catch (IOException e) {
                    Log.w(TAG, "Unable to read statistics stream", e);
                }
            }
        } catch (RemoteException e) {
            Log.w(TAG, "RemoteException:", e);
        }
        return null;
    }
}
