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

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
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
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseLongArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.os.BatterySipper.DrainType;
import com.android.internal.util.ArrayUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * A helper class for retrieving the power usage information for all applications and services.
 *
 * The caller must initialize this class as soon as activity object is ready to use (for example, in
 * onAttach() for Fragment), call create() in onCreate() and call destroy() in onDestroy().
 */
public class BatteryStatsHelper {
    static final boolean DEBUG = false;

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

    private String[] mSystemPackageArray;
    private String[] mServicepackageArray;
    private PackageManager mPackageManager;

    /**
     * List of apps using power.
     */
    private final List<BatterySipper> mUsageList = new ArrayList<>();

    /**
     * List of apps using wifi power.
     */
    private final List<BatterySipper> mWifiSippers = new ArrayList<>();

    /**
     * List of apps using bluetooth power.
     */
    private final List<BatterySipper> mBluetoothSippers = new ArrayList<>();

    private final SparseArray<List<BatterySipper>> mUserSippers = new SparseArray<>();

    private final List<BatterySipper> mMobilemsppList = new ArrayList<>();

    private int mStatsType = BatteryStats.STATS_SINCE_CHARGED;

    long mRawRealtimeUs;
    long mRawUptimeUs;
    long mBatteryRealtimeUs;
    long mBatteryUptimeUs;
    long mTypeBatteryRealtimeUs;
    long mTypeBatteryUptimeUs;
    long mBatteryTimeRemainingUs;
    long mChargeTimeRemainingUs;

    private long mStatsPeriod = 0;

    // The largest entry by power.
    private double mMaxPower = 1;

    // The largest real entry by power (not undercounted or overcounted).
    private double mMaxRealPower = 1;

    // Total computed power.
    private double mComputedPower;
    private double mTotalPower;
    private double mMinDrainedPower;
    private double mMaxDrainedPower;

    PowerCalculator mCpuPowerCalculator;
    PowerCalculator mWakelockPowerCalculator;
    MobileRadioPowerCalculator mMobileRadioPowerCalculator;
    PowerCalculator mWifiPowerCalculator;
    PowerCalculator mBluetoothPowerCalculator;
    PowerCalculator mSensorPowerCalculator;
    PowerCalculator mCameraPowerCalculator;
    PowerCalculator mFlashlightPowerCalculator;
    PowerCalculator mMemoryPowerCalculator;
    PowerCalculator mMediaPowerCalculator;

    boolean mHasWifiPowerReporting = false;
    boolean mHasBluetoothPowerReporting = false;

    public static boolean checkWifiOnly(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }
        return !cm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE);
    }

    public static boolean checkHasWifiPowerReporting(BatteryStats stats, PowerProfile profile) {
        return stats.hasWifiActivityReporting() &&
                profile.getAveragePower(PowerProfile.POWER_WIFI_CONTROLLER_IDLE) != 0 &&
                profile.getAveragePower(PowerProfile.POWER_WIFI_CONTROLLER_RX) != 0 &&
                profile.getAveragePower(PowerProfile.POWER_WIFI_CONTROLLER_TX) != 0;
    }

    public static boolean checkHasBluetoothPowerReporting(BatteryStats stats,
            PowerProfile profile) {
        return stats.hasBluetoothActivityReporting() &&
                profile.getAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_IDLE) != 0 &&
                profile.getAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_RX) != 0 &&
                profile.getAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_TX) != 0;
    }

    public BatteryStatsHelper(Context context) {
        this(context, true);
    }

    public BatteryStatsHelper(Context context, boolean collectBatteryBroadcast) {
        this(context, collectBatteryBroadcast, checkWifiOnly(context));
    }

    public BatteryStatsHelper(Context context, boolean collectBatteryBroadcast, boolean wifiOnly) {
        mContext = context;
        mCollectBatteryBroadcast = collectBatteryBroadcast;
        mWifiOnly = wifiOnly;
        mPackageManager = context.getPackageManager();

        final Resources resources = context.getResources();
        mSystemPackageArray = resources.getStringArray(
                com.android.internal.R.array.config_batteryPackageTypeSystem);
        mServicepackageArray = resources.getStringArray(
                com.android.internal.R.array.config_batteryPackageTypeService);
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
        if (power == 0) return "0";

        final String format;
        if (power < .00001) {
            format = "%.8f";
        } else if (power < .0001) {
            format = "%.7f";
        } else if (power < .001) {
            format = "%.6f";
        } else if (power < .01) {
            format = "%.5f";
        } else if (power < .1) {
            format = "%.4f";
        } else if (power < 1) {
            format = "%.3f";
        } else if (power < 10) {
            format = "%.2f";
        } else if (power < 100) {
            format = "%.1f";
        } else {
            format = "%.0f";
        }

        // Use English locale because this is never used in UI (only in checkin and dump).
        return String.format(Locale.ENGLISH, format, power);
    }

    /**
     * Refreshes the power usage list.
     */
    public void refreshStats(int statsType, int asUser) {
        SparseArray<UserHandle> users = new SparseArray<>(1);
        users.put(asUser, new UserHandle(asUser));
        refreshStats(statsType, users);
    }

    /**
     * Refreshes the power usage list.
     */
    public void refreshStats(int statsType, List<UserHandle> asUsers) {
        final int n = asUsers.size();
        SparseArray<UserHandle> users = new SparseArray<>(n);
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

        mUsageList.clear();
        mWifiSippers.clear();
        mBluetoothSippers.clear();
        mUserSippers.clear();
        mMobilemsppList.clear();

        if (mStats == null) {
            return;
        }

        if (mCpuPowerCalculator == null) {
            mCpuPowerCalculator = new CpuPowerCalculator(mPowerProfile);
        }
        mCpuPowerCalculator.reset();

        if (mMemoryPowerCalculator == null) {
            mMemoryPowerCalculator = new MemoryPowerCalculator(mPowerProfile);
        }
        mMemoryPowerCalculator.reset();

        if (mWakelockPowerCalculator == null) {
            mWakelockPowerCalculator = new WakelockPowerCalculator(mPowerProfile);
        }
        mWakelockPowerCalculator.reset();

        if (mMobileRadioPowerCalculator == null) {
            mMobileRadioPowerCalculator = new MobileRadioPowerCalculator(mPowerProfile, mStats);
        }
        mMobileRadioPowerCalculator.reset(mStats);

        // checkHasWifiPowerReporting can change if we get energy data at a later point, so
        // always check this field.
        final boolean hasWifiPowerReporting = checkHasWifiPowerReporting(mStats, mPowerProfile);
        if (mWifiPowerCalculator == null || hasWifiPowerReporting != mHasWifiPowerReporting) {
            mWifiPowerCalculator = hasWifiPowerReporting ?
                    new WifiPowerCalculator(mPowerProfile) :
                    new WifiPowerEstimator(mPowerProfile);
            mHasWifiPowerReporting = hasWifiPowerReporting;
        }
        mWifiPowerCalculator.reset();

        final boolean hasBluetoothPowerReporting = checkHasBluetoothPowerReporting(mStats,
                mPowerProfile);
        if (mBluetoothPowerCalculator == null ||
                hasBluetoothPowerReporting != mHasBluetoothPowerReporting) {
            mBluetoothPowerCalculator = new BluetoothPowerCalculator(mPowerProfile);
            mHasBluetoothPowerReporting = hasBluetoothPowerReporting;
        }
        mBluetoothPowerCalculator.reset();

        mSensorPowerCalculator = new SensorPowerCalculator(mPowerProfile,
                (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE),
                mStats, rawRealtimeUs, statsType);
        mSensorPowerCalculator.reset();

        if (mCameraPowerCalculator == null) {
            mCameraPowerCalculator = new CameraPowerCalculator(mPowerProfile);
        }
        mCameraPowerCalculator.reset();

        if (mFlashlightPowerCalculator == null) {
            mFlashlightPowerCalculator = new FlashlightPowerCalculator(mPowerProfile);
        }
        mFlashlightPowerCalculator.reset();

        if (mMediaPowerCalculator == null) {
            mMediaPowerCalculator = new MediaPowerCalculator(mPowerProfile);
        }
        mMediaPowerCalculator.reset();

        mStatsType = statsType;
        mRawUptimeUs = rawUptimeUs;
        mRawRealtimeUs = rawRealtimeUs;
        mBatteryUptimeUs = mStats.getBatteryUptime(rawUptimeUs);
        mBatteryRealtimeUs = mStats.getBatteryRealtime(rawRealtimeUs);
        mTypeBatteryUptimeUs = mStats.computeBatteryUptime(rawUptimeUs, mStatsType);
        mTypeBatteryRealtimeUs = mStats.computeBatteryRealtime(rawRealtimeUs, mStatsType);
        mBatteryTimeRemainingUs = mStats.computeBatteryTimeRemaining(rawRealtimeUs);
        mChargeTimeRemainingUs = mStats.computeChargeTimeRemaining(rawRealtimeUs);

        if (DEBUG) {
            Log.d(TAG, "Raw time: realtime=" + (rawRealtimeUs / 1000) + " uptime="
                    + (rawUptimeUs / 1000));
            Log.d(TAG, "Battery time: realtime=" + (mBatteryRealtimeUs / 1000) + " uptime="
                    + (mBatteryUptimeUs / 1000));
            Log.d(TAG, "Battery type time: realtime=" + (mTypeBatteryRealtimeUs / 1000) + " uptime="
                    + (mTypeBatteryUptimeUs / 1000));
        }
        mMinDrainedPower = (mStats.getLowDischargeAmountSinceCharge()
                * mPowerProfile.getBatteryCapacity()) / 100;
        mMaxDrainedPower = (mStats.getHighDischargeAmountSinceCharge()
                * mPowerProfile.getBatteryCapacity()) / 100;

        processAppUsage(asUsers);

        // Before aggregating apps in to users, collect all apps to sort by their ms per packet.
        for (int i = 0; i < mUsageList.size(); i++) {
            BatterySipper bs = mUsageList.get(i);
            bs.computeMobilemspp();
            if (bs.mobilemspp != 0) {
                mMobilemsppList.add(bs);
            }
        }

        for (int i = 0; i < mUserSippers.size(); i++) {
            List<BatterySipper> user = mUserSippers.valueAt(i);
            for (int j = 0; j < user.size(); j++) {
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
                return Double.compare(rhs.mobilemspp, lhs.mobilemspp);
            }
        });

        processMiscUsage();

        Collections.sort(mUsageList);

        // At this point, we've sorted the list so we are guaranteed the max values are at the top.
        // We have only added real powers so far.
        if (!mUsageList.isEmpty()) {
            mMaxRealPower = mMaxPower = mUsageList.get(0).totalPowerMah;
            final int usageListCount = mUsageList.size();
            for (int i = 0; i < usageListCount; i++) {
                mComputedPower += mUsageList.get(i).totalPowerMah;
            }
        }

        if (DEBUG) {
            Log.d(TAG, "Accuracy: total computed=" + makemAh(mComputedPower) + ", min discharge="
                    + makemAh(mMinDrainedPower) + ", max discharge=" + makemAh(mMaxDrainedPower));
        }

        mTotalPower = mComputedPower;
        if (mStats.getLowDischargeAmountSinceCharge() > 1) {
            if (mMinDrainedPower > mComputedPower) {
                double amount = mMinDrainedPower - mComputedPower;
                mTotalPower = mMinDrainedPower;
                BatterySipper bs = new BatterySipper(DrainType.UNACCOUNTED, null, amount);

                // Insert the BatterySipper in its sorted position.
                int index = Collections.binarySearch(mUsageList, bs);
                if (index < 0) {
                    index = -(index + 1);
                }
                mUsageList.add(index, bs);
                mMaxPower = Math.max(mMaxPower, amount);
            } else if (mMaxDrainedPower < mComputedPower) {
                double amount = mComputedPower - mMaxDrainedPower;

                // Insert the BatterySipper in its sorted position.
                BatterySipper bs = new BatterySipper(DrainType.OVERCOUNTED, null, amount);
                int index = Collections.binarySearch(mUsageList, bs);
                if (index < 0) {
                    index = -(index + 1);
                }
                mUsageList.add(index, bs);
                mMaxPower = Math.max(mMaxPower, amount);
            }
        }

        // Smear it!
        final double hiddenPowerMah = removeHiddenBatterySippers(mUsageList);
        final double totalRemainingPower = getTotalPower() - hiddenPowerMah;
        if (Math.abs(totalRemainingPower) > 1e-3) {
            for (int i = 0, size = mUsageList.size(); i < size; i++) {
                final BatterySipper sipper = mUsageList.get(i);
                if (!sipper.shouldHide) {
                    sipper.proportionalSmearMah = hiddenPowerMah
                            * ((sipper.totalPowerMah + sipper.screenPowerMah)
                            / totalRemainingPower);
                    sipper.sumPower();
                }
            }
        }
    }

    private void processAppUsage(SparseArray<UserHandle> asUsers) {
        final boolean forAllUsers = (asUsers.get(UserHandle.USER_ALL) != null);
        mStatsPeriod = mTypeBatteryRealtimeUs;

        BatterySipper osSipper = null;
        final SparseArray<? extends Uid> uidStats = mStats.getUidStats();
        final int NU = uidStats.size();
        for (int iu = 0; iu < NU; iu++) {
            final Uid u = uidStats.valueAt(iu);
            final BatterySipper app = new BatterySipper(BatterySipper.DrainType.APP, u, 0);

            mCpuPowerCalculator.calculateApp(app, u, mRawRealtimeUs, mRawUptimeUs, mStatsType);
            mWakelockPowerCalculator.calculateApp(app, u, mRawRealtimeUs, mRawUptimeUs, mStatsType);
            mMobileRadioPowerCalculator.calculateApp(app, u, mRawRealtimeUs, mRawUptimeUs,
                    mStatsType);
            mWifiPowerCalculator.calculateApp(app, u, mRawRealtimeUs, mRawUptimeUs, mStatsType);
            mBluetoothPowerCalculator.calculateApp(app, u, mRawRealtimeUs, mRawUptimeUs,
                    mStatsType);
            mSensorPowerCalculator.calculateApp(app, u, mRawRealtimeUs, mRawUptimeUs, mStatsType);
            mCameraPowerCalculator.calculateApp(app, u, mRawRealtimeUs, mRawUptimeUs, mStatsType);
            mFlashlightPowerCalculator.calculateApp(app, u, mRawRealtimeUs, mRawUptimeUs,
                    mStatsType);
            mMediaPowerCalculator.calculateApp(app, u, mRawRealtimeUs, mRawUptimeUs, mStatsType);

            final double totalPower = app.sumPower();
            if (DEBUG && totalPower != 0) {
                Log.d(TAG, String.format("UID %d: total power=%s", u.getUid(),
                        makemAh(totalPower)));
            }

            // Add the app to the list if it is consuming power.
            if (totalPower != 0 || u.getUid() == 0) {
                //
                // Add the app to the app list, WiFi, Bluetooth, etc, or into "Other Users" list.
                //
                final int uid = app.getUid();
                final int userId = UserHandle.getUserId(uid);
                if (uid == Process.WIFI_UID) {
                    mWifiSippers.add(app);
                } else if (uid == Process.BLUETOOTH_UID) {
                    mBluetoothSippers.add(app);
                } else if (!forAllUsers && asUsers.get(userId) == null
                        && UserHandle.getAppId(uid) >= Process.FIRST_APPLICATION_UID) {
                    // We are told to just report this user's apps as one large entry.
                    List<BatterySipper> list = mUserSippers.get(userId);
                    if (list == null) {
                        list = new ArrayList<>();
                        mUserSippers.put(userId, list);
                    }
                    list.add(app);
                } else {
                    mUsageList.add(app);
                }

                if (uid == 0) {
                    osSipper = app;
                }
            }
        }

        if (osSipper != null) {
            // The device has probably been awake for longer than the screen on
            // time and application wake lock time would account for.  Assign
            // this remainder to the OS, if possible.
            mWakelockPowerCalculator.calculateRemaining(osSipper, mStats, mRawRealtimeUs,
                    mRawUptimeUs, mStatsType);
            osSipper.sumPower();
        }
    }

    private void addPhoneUsage() {
        long phoneOnTimeMs = mStats.getPhoneOnTime(mRawRealtimeUs, mStatsType) / 1000;
        double phoneOnPower = mPowerProfile.getAveragePower(PowerProfile.POWER_RADIO_ACTIVE)
                * phoneOnTimeMs / (60 * 60 * 1000);
        if (phoneOnPower != 0) {
            addEntry(BatterySipper.DrainType.PHONE, phoneOnTimeMs, phoneOnPower);
        }
    }

    /**
     * Screen power is the additional power the screen takes while the device is running.
     */
    private void addScreenUsage() {
        double power = 0;
        long screenOnTimeMs = mStats.getScreenOnTime(mRawRealtimeUs, mStatsType) / 1000;
        power += screenOnTimeMs * mPowerProfile.getAveragePower(PowerProfile.POWER_SCREEN_ON);
        final double screenFullPower =
                mPowerProfile.getAveragePower(PowerProfile.POWER_SCREEN_FULL);
        for (int i = 0; i < BatteryStats.NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            double screenBinPower = screenFullPower * (i + 0.5f)
                    / BatteryStats.NUM_SCREEN_BRIGHTNESS_BINS;
            long brightnessTime = mStats.getScreenBrightnessTime(i, mRawRealtimeUs, mStatsType)
                    / 1000;
            double p = screenBinPower * brightnessTime;
            if (DEBUG && p != 0) {
                Log.d(TAG, "Screen bin #" + i + ": time=" + brightnessTime
                        + " power=" + makemAh(p / (60 * 60 * 1000)));
            }
            power += p;
        }
        power /= (60 * 60 * 1000); // To hours
        if (power != 0) {
            addEntry(BatterySipper.DrainType.SCREEN, screenOnTimeMs, power);
        }
    }

    /**
     * Ambient display power is the additional power the screen takes while in ambient display/
     * screen doze/ always-on display (interchangeable terms) mode. Ambient display power should
     * be hidden {@link #shouldHideSipper(BatterySipper)}, but should not be included in smearing
     * {@link #removeHiddenBatterySippers(List)}.
     */
    private void addAmbientDisplayUsage() {
        long ambientDisplayMs = mStats.getScreenDozeTime(mRawRealtimeUs, mStatsType) / 1000;
        double power = mPowerProfile.getAveragePower(PowerProfile.POWER_AMBIENT_DISPLAY)
                * ambientDisplayMs / (60 * 60 * 1000);
        if (power > 0) {
            addEntry(DrainType.AMBIENT_DISPLAY, ambientDisplayMs, power);
        }
    }

    private void addRadioUsage() {
        BatterySipper radio = new BatterySipper(BatterySipper.DrainType.CELL, null, 0);
        mMobileRadioPowerCalculator.calculateRemaining(radio, mStats, mRawRealtimeUs, mRawUptimeUs,
                mStatsType);
        radio.sumPower();
        if (radio.totalPowerMah > 0) {
            mUsageList.add(radio);
        }
    }

    private void aggregateSippers(BatterySipper bs, List<BatterySipper> from, String tag) {
        for (int i = 0; i < from.size(); i++) {
            BatterySipper wbs = from.get(i);
            if (DEBUG) Log.d(TAG, tag + " adding sipper " + wbs + ": cpu=" + wbs.cpuTimeMs);
            bs.add(wbs);
        }
        bs.computeMobilemspp();
        bs.sumPower();
    }

    /**
     * Calculate the baseline power usage for the device when it is in suspend and idle.
     * The device is drawing POWER_CPU_SUSPEND power at its lowest power state.
     * The device is drawing POWER_CPU_SUSPEND + POWER_CPU_IDLE power when a wakelock is held.
     */
    private void addIdleUsage() {
        final double suspendPowerMaMs = (mTypeBatteryRealtimeUs / 1000) *
                mPowerProfile.getAveragePower(PowerProfile.POWER_CPU_SUSPEND);
        final double idlePowerMaMs = (mTypeBatteryUptimeUs / 1000) *
                mPowerProfile.getAveragePower(PowerProfile.POWER_CPU_IDLE);
        final double totalPowerMah = (suspendPowerMaMs + idlePowerMaMs) / (60 * 60 * 1000);
        if (DEBUG && totalPowerMah != 0) {
            Log.d(TAG, "Suspend: time=" + (mTypeBatteryRealtimeUs / 1000)
                    + " power=" + makemAh(suspendPowerMaMs / (60 * 60 * 1000)));
            Log.d(TAG, "Idle: time=" + (mTypeBatteryUptimeUs / 1000)
                    + " power=" + makemAh(idlePowerMaMs / (60 * 60 * 1000)));
        }

        if (totalPowerMah != 0) {
            addEntry(BatterySipper.DrainType.IDLE, mTypeBatteryRealtimeUs / 1000, totalPowerMah);
        }
    }

    /**
     * We do per-app blaming of WiFi activity. If energy info is reported from the controller,
     * then only the WiFi process gets blamed here since we normalize power calculations and
     * assign all the power drain to apps. If energy info is not reported, we attribute the
     * difference between total running time of WiFi for all apps and the actual running time
     * of WiFi to the WiFi subsystem.
     */
    private void addWiFiUsage() {
        BatterySipper bs = new BatterySipper(DrainType.WIFI, null, 0);
        mWifiPowerCalculator.calculateRemaining(bs, mStats, mRawRealtimeUs, mRawUptimeUs,
                mStatsType);
        aggregateSippers(bs, mWifiSippers, "WIFI");
        if (bs.totalPowerMah > 0) {
            mUsageList.add(bs);
        }
    }

    /**
     * Bluetooth usage is not attributed to any apps yet, so the entire blame goes to the
     * Bluetooth Category.
     */
    private void addBluetoothUsage() {
        BatterySipper bs = new BatterySipper(BatterySipper.DrainType.BLUETOOTH, null, 0);
        mBluetoothPowerCalculator.calculateRemaining(bs, mStats, mRawRealtimeUs, mRawUptimeUs,
                mStatsType);
        aggregateSippers(bs, mBluetoothSippers, "Bluetooth");
        if (bs.totalPowerMah > 0) {
            mUsageList.add(bs);
        }
    }

    private void addUserUsage() {
        for (int i = 0; i < mUserSippers.size(); i++) {
            final int userId = mUserSippers.keyAt(i);
            BatterySipper bs = new BatterySipper(DrainType.USER, null, 0);
            bs.userId = userId;
            aggregateSippers(bs, mUserSippers.valueAt(i), "User");
            mUsageList.add(bs);
        }
    }

    private void addMemoryUsage() {
        BatterySipper memory = new BatterySipper(DrainType.MEMORY, null, 0);
        mMemoryPowerCalculator.calculateRemaining(memory, mStats, mRawRealtimeUs, mRawUptimeUs,
                mStatsType);
        memory.sumPower();
        if (memory.totalPowerMah > 0) {
            mUsageList.add(memory);
        }
    }

    private void processMiscUsage() {
        addUserUsage();
        addPhoneUsage();
        addScreenUsage();
        addAmbientDisplayUsage();
        addWiFiUsage();
        addBluetoothUsage();
        addMemoryUsage();
        addIdleUsage(); // Not including cellular idle power
        // Don't compute radio usage if it's a wifi-only device
        if (!mWifiOnly) {
            addRadioUsage();
        }
    }

    private BatterySipper addEntry(DrainType drainType, long time, double power) {
        BatterySipper bs = new BatterySipper(drainType, null, 0);
        bs.usagePowerMah = power;
        bs.usageTimeMs = time;
        bs.sumPower();
        mUsageList.add(bs);
        return bs;
    }

    public List<BatterySipper> getUsageList() {
        return mUsageList;
    }

    public List<BatterySipper> getMobilemsppList() {
        return mMobilemsppList;
    }

    public long getStatsPeriod() {
        return mStatsPeriod;
    }

    public int getStatsType() {
        return mStatsType;
    }

    public double getMaxPower() {
        return mMaxPower;
    }

    public double getMaxRealPower() {
        return mMaxRealPower;
    }

    public double getTotalPower() {
        return mTotalPower;
    }

    public double getComputedPower() {
        return mComputedPower;
    }

    public double getMinDrainedPower() {
        return mMinDrainedPower;
    }

    public double getMaxDrainedPower() {
        return mMaxDrainedPower;
    }

    public static byte[] readFully(FileInputStream stream) throws java.io.IOException {
        return readFully(stream, stream.available());
    }

    public static byte[] readFully(FileInputStream stream, int avail) throws java.io.IOException {
        int pos = 0;
        byte[] data = new byte[avail];
        while (true) {
            int amt = stream.read(data, pos, data.length - pos);
            //Log.i("foo", "Read " + amt + " bytes at " + pos
            //        + " of avail " + data.length);
            if (amt <= 0) {
                //Log.i("foo", "**** FINISHED READING: pos=" + pos
                //        + " len=" + data.length);
                return data;
            }
            pos += amt;
            avail = stream.available();
            if (avail > data.length - pos) {
                byte[] newData = new byte[pos + avail];
                System.arraycopy(data, 0, newData, 0, pos);
                data = newData;
            }
        }
    }

    /**
     * Mark the {@link BatterySipper} that we should hide and smear the screen usage based on
     * foreground activity time.
     *
     * @param sippers sipper list that need to check and remove
     * @return the total power of the hidden items of {@link BatterySipper}
     * for proportional smearing
     */
    public double removeHiddenBatterySippers(List<BatterySipper> sippers) {
        double proportionalSmearPowerMah = 0;
        BatterySipper screenSipper = null;
        for (int i = sippers.size() - 1; i >= 0; i--) {
            final BatterySipper sipper = sippers.get(i);
            sipper.shouldHide = shouldHideSipper(sipper);
            if (sipper.shouldHide) {
                if (sipper.drainType != DrainType.OVERCOUNTED
                        && sipper.drainType != DrainType.SCREEN
                        && sipper.drainType != DrainType.AMBIENT_DISPLAY
                        && sipper.drainType != DrainType.UNACCOUNTED
                        && sipper.drainType != DrainType.BLUETOOTH
                        && sipper.drainType != DrainType.WIFI
                        && sipper.drainType != DrainType.IDLE) {
                    // Don't add it if it is overcounted, unaccounted or screen
                    proportionalSmearPowerMah += sipper.totalPowerMah;
                }
            }

            if (sipper.drainType == BatterySipper.DrainType.SCREEN) {
                screenSipper = sipper;
            }
        }

        smearScreenBatterySipper(sippers, screenSipper);

        return proportionalSmearPowerMah;
    }

    /**
     * Smear the screen on power usage among {@code sippers}, based on ratio of foreground activity
     * time.
     */
    public void smearScreenBatterySipper(List<BatterySipper> sippers, BatterySipper screenSipper) {
        long totalActivityTimeMs = 0;
        final SparseLongArray activityTimeArray = new SparseLongArray();
        for (int i = 0, size = sippers.size(); i < size; i++) {
            final BatteryStats.Uid uid = sippers.get(i).uidObj;
            if (uid != null) {
                final long timeMs = getProcessForegroundTimeMs(uid,
                        BatteryStats.STATS_SINCE_CHARGED);
                activityTimeArray.put(uid.getUid(), timeMs);
                totalActivityTimeMs += timeMs;
            }
        }

        if (screenSipper != null && totalActivityTimeMs >= 10 * DateUtils.MINUTE_IN_MILLIS) {
            final double screenPowerMah = screenSipper.totalPowerMah;
            for (int i = 0, size = sippers.size(); i < size; i++) {
                final BatterySipper sipper = sippers.get(i);
                sipper.screenPowerMah = screenPowerMah * activityTimeArray.get(sipper.getUid(), 0)
                        / totalActivityTimeMs;
            }
        }
    }

    /**
     * Check whether we should hide the battery sipper.
     */
    public boolean shouldHideSipper(BatterySipper sipper) {
        final DrainType drainType = sipper.drainType;

        return drainType == DrainType.IDLE
                || drainType == DrainType.CELL
                || drainType == DrainType.SCREEN
                || drainType == DrainType.AMBIENT_DISPLAY
                || drainType == DrainType.UNACCOUNTED
                || drainType == DrainType.OVERCOUNTED
                || isTypeService(sipper)
                || isTypeSystem(sipper);
    }

    /**
     * Check whether {@code sipper} is type service
     */
    public boolean isTypeService(BatterySipper sipper) {
        final String[] packages = mPackageManager.getPackagesForUid(sipper.getUid());
        if (packages == null) {
            return false;
        }

        for (String packageName : packages) {
            if (ArrayUtils.contains(mServicepackageArray, packageName)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check whether {@code sipper} is type system
     */
    public boolean isTypeSystem(BatterySipper sipper) {
        final int uid = sipper.uidObj == null ? -1 : sipper.getUid();
        sipper.mPackages = mPackageManager.getPackagesForUid(uid);
        // Classify all the sippers to type system if the range of uid is 0...FIRST_APPLICATION_UID
        if (uid >= Process.ROOT_UID && uid < Process.FIRST_APPLICATION_UID) {
            return true;
        } else if (sipper.mPackages != null) {
            for (final String packageName : sipper.mPackages) {
                if (ArrayUtils.contains(mSystemPackageArray, packageName)) {
                    return true;
                }
            }
        }

        return false;
    }

    public long convertUsToMs(long timeUs) {
        return timeUs / 1000;
    }

    public long convertMsToUs(long timeMs) {
        return timeMs * 1000;
    }

    @VisibleForTesting
    public long getForegroundActivityTotalTimeUs(BatteryStats.Uid uid, long rawRealtimeUs) {
        final BatteryStats.Timer timer = uid.getForegroundActivityTimer();
        if (timer != null) {
            return timer.getTotalTimeLocked(rawRealtimeUs, BatteryStats.STATS_SINCE_CHARGED);
        }

        return 0;
    }

    @VisibleForTesting
    public long getProcessForegroundTimeMs(BatteryStats.Uid uid, int which) {
        final long rawRealTimeUs = convertMsToUs(SystemClock.elapsedRealtime());
        final int foregroundTypes[] = {BatteryStats.Uid.PROCESS_STATE_TOP};

        long timeUs = 0;
        for (int type : foregroundTypes) {
            final long localTime = uid.getProcessStateTime(type, rawRealTimeUs, which);
            timeUs += localTime;
        }

        // Return the min value of STATE_TOP time and foreground activity time, since both of these
        // time have some errors.
        return convertUsToMs(
                Math.min(timeUs, getForegroundActivityTotalTimeUs(uid, rawRealTimeUs)));
    }

    @VisibleForTesting
    public void setPackageManager(PackageManager packageManager) {
        mPackageManager = packageManager;
    }

    @VisibleForTesting
    public void setSystemPackageArray(String[] array) {
        mSystemPackageArray = array;
    }

    @VisibleForTesting
    public void setServicePackageArray(String[] array) {
        mServicepackageArray = array;
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
                try (FileInputStream fis = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
                    byte[] data = readFully(fis, MemoryFile.getSize(pfd.getFileDescriptor()));
                    Parcel parcel = Parcel.obtain();
                    parcel.unmarshall(data, 0, data.length);
                    parcel.setDataPosition(0);
                    BatteryStatsImpl stats = com.android.internal.os.BatteryStatsImpl.CREATOR
                            .createFromParcel(parcel);
                    return stats;
                } catch (IOException e) {
                    Log.w(TAG, "Unable to read statistics stream", e);
                }
            }
        } catch (RemoteException e) {
            Log.w(TAG, "RemoteException:", e);
        }
        return new BatteryStatsImpl();
    }
}
