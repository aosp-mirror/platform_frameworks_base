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

import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.hardware.SensorManager;
import android.os.BatteryStats;
import android.os.BatteryStats.Uid;
import android.os.Build;
import android.os.Bundle;
import android.os.MemoryFile;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.telephony.TelephonyManager;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;

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
import java.util.List;

/**
 * A helper class for retrieving the power usage information for all applications and services.
 *
 * The caller must initialize this class as soon as activity object is ready to use (for example, in
 * onAttach() for Fragment), call create() in onCreate() and call destroy() in onDestroy().
 *
 * @deprecated Please use BatteryStatsManager.getBatteryUsageStats instead.
 */
@Deprecated
public class BatteryStatsHelper {
    static final boolean DEBUG = false;

    private static final String TAG = BatteryStatsHelper.class.getSimpleName();

    private static BatteryStats sStatsXfer;
    private static Intent sBatteryBroadcastXfer;
    private static ArrayMap<File, BatteryStats> sFileXfer = new ArrayMap<>();

    final private Context mContext;
    final private boolean mCollectBatteryBroadcast;
    final private boolean mWifiOnly;

    private List<PowerCalculator> mPowerCalculators;

    @UnsupportedAppUsage
    private IBatteryStats mBatteryInfo;
    private BatteryStats mStats;
    private Intent mBatteryBroadcast;
    @UnsupportedAppUsage
    private PowerProfile mPowerProfile;

    private String[] mSystemPackageArray;
    private String[] mServicepackageArray;
    private PackageManager mPackageManager;

    /**
     * List of apps using power.
     */
    @UnsupportedAppUsage
    private final List<BatterySipper> mUsageList = new ArrayList<>();

    private final List<BatterySipper> mMobilemsppList = new ArrayList<>();

    private int mStatsType = BatteryStats.STATS_SINCE_CHARGED;

    long mRawRealtimeUs;
    long mRawUptimeUs;
    long mBatteryRealtimeUs;
    long mBatteryUptimeUs;
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

    public static boolean checkWifiOnly(Context context) {
        final TelephonyManager tm = context.getSystemService(TelephonyManager.class);
        if (tm == null) {
            return false;
        }
        return !tm.isDataCapable();
    }

    @UnsupportedAppUsage
    public BatteryStatsHelper(Context context) {
        this(context, true);
    }

    @UnsupportedAppUsage
    public BatteryStatsHelper(Context context, boolean collectBatteryBroadcast) {
        this(context, collectBatteryBroadcast, checkWifiOnly(context));
    }

    @UnsupportedAppUsage
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
                ServiceManager.getService(BatteryStats.SERVICE_NAME)), true);
    }

    @UnsupportedAppUsage
    public static void dropFile(Context context, String fname) {
        makeFilePath(context, fname).delete();
    }

    private static File makeFilePath(Context context, String fname) {
        return new File(context.getFilesDir(), fname);
    }

    /** Clears the current stats and forces recreating for future use. */
    @UnsupportedAppUsage
    public void clearStats() {
        mStats = null;
    }

    @UnsupportedAppUsage
    public BatteryStats getStats() {
        return getStats(true /* updateAll */);
    }

    /** Retrieves stats from BatteryService, optionally getting updated numbers */
    public BatteryStats getStats(boolean updateAll) {
        if (mStats == null) {
            load(updateAll);
        }
        return mStats;
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
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

    @UnsupportedAppUsage
    public void create(Bundle icicle) {
        if (icicle != null) {
            mStats = sStatsXfer;
            mBatteryBroadcast = sBatteryBroadcastXfer;
        }
        mBatteryInfo = IBatteryStats.Stub.asInterface(
                ServiceManager.getService(BatteryStats.SERVICE_NAME));
        mPowerProfile = new PowerProfile(mContext);
    }

    @UnsupportedAppUsage
    public void storeState() {
        sStatsXfer = mStats;
        sBatteryBroadcastXfer = mBatteryBroadcast;
    }

    public static String makemAh(double power) {
        return PowerCalculator.formatCharge(power);
    }

    /**
     * Refreshes the power usage list.
     */
    @UnsupportedAppUsage
    public void refreshStats(int statsType, int asUser) {
        SparseArray<UserHandle> users = new SparseArray<>(1);
        users.put(asUser, new UserHandle(asUser));
        refreshStats(statsType, users);
    }

    /**
     * Refreshes the power usage list.
     */
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void refreshStats(int statsType, SparseArray<UserHandle> asUsers) {
        refreshStats(statsType, asUsers, SystemClock.elapsedRealtime() * 1000,
                SystemClock.uptimeMillis() * 1000);
    }

    public void refreshStats(int statsType, SparseArray<UserHandle> asUsers, long rawRealtimeUs,
            long rawUptimeUs) {
        if (statsType != BatteryStats.STATS_SINCE_CHARGED) {
            Log.w(TAG, "refreshStats called for statsType " + statsType + " but only "
                    + "STATS_SINCE_CHARGED is supported. Using STATS_SINCE_CHARGED instead.");
        }

        // Initialize mStats if necessary.
        getStats();

        mMaxPower = 0;
        mMaxRealPower = 0;
        mComputedPower = 0;
        mTotalPower = 0;

        mUsageList.clear();
        mMobilemsppList.clear();

        if (mStats == null) {
            return;
        }

        if (mPowerCalculators == null) {
            mPowerCalculators = new ArrayList<>();

            // Power calculators are applied in the order of registration
            mPowerCalculators.add(new CpuPowerCalculator(mPowerProfile));
            mPowerCalculators.add(new MemoryPowerCalculator(mPowerProfile));
            mPowerCalculators.add(new WakelockPowerCalculator(mPowerProfile));
            if (!mWifiOnly) {
                mPowerCalculators.add(new MobileRadioPowerCalculator(mPowerProfile));
            }
            mPowerCalculators.add(new WifiPowerCalculator(mPowerProfile));
            mPowerCalculators.add(new BluetoothPowerCalculator(mPowerProfile));
            mPowerCalculators.add(new SensorPowerCalculator(
                    mContext.getSystemService(SensorManager.class)));
            mPowerCalculators.add(new GnssPowerCalculator(mPowerProfile));
            mPowerCalculators.add(new CameraPowerCalculator(mPowerProfile));
            mPowerCalculators.add(new FlashlightPowerCalculator(mPowerProfile));
            mPowerCalculators.add(new MediaPowerCalculator(mPowerProfile));
            mPowerCalculators.add(new PhonePowerCalculator(mPowerProfile));
            mPowerCalculators.add(new ScreenPowerCalculator(mPowerProfile));
            mPowerCalculators.add(new AmbientDisplayPowerCalculator(mPowerProfile));
            mPowerCalculators.add(new SystemServicePowerCalculator(mPowerProfile));
            mPowerCalculators.add(new IdlePowerCalculator(mPowerProfile));
            mPowerCalculators.add(new CustomMeasuredPowerCalculator(mPowerProfile));

            mPowerCalculators.add(new UserPowerCalculator());
        }

        for (int i = 0, size = mPowerCalculators.size(); i < size; i++) {
            mPowerCalculators.get(i).reset();
        }

        mStatsType = statsType;
        mRawUptimeUs = rawUptimeUs;
        mRawRealtimeUs = rawRealtimeUs;
        mBatteryUptimeUs = mStats.getBatteryUptime(rawUptimeUs);
        mBatteryRealtimeUs = mStats.getBatteryRealtime(rawRealtimeUs);
        mBatteryTimeRemainingUs = mStats.computeBatteryTimeRemaining(rawRealtimeUs);
        mChargeTimeRemainingUs = mStats.computeChargeTimeRemaining(rawRealtimeUs);
        mStatsPeriod = mStats.computeBatteryRealtime(rawRealtimeUs, mStatsType);

        if (DEBUG) {
            Log.d(TAG, "Raw time: realtime=" + (rawRealtimeUs / 1000) + " uptime="
                    + (rawUptimeUs / 1000));
            Log.d(TAG, "Battery time: realtime=" + (mBatteryRealtimeUs / 1000) + " uptime="
                    + (mBatteryUptimeUs / 1000));
            Log.d(TAG, "Battery type time: realtime=" + (mStatsPeriod / 1000) + " uptime="
                    + (mStats.computeBatteryUptime(rawRealtimeUs, mStatsType) / 1000));
        }
        mMinDrainedPower = (mStats.getLowDischargeAmountSinceCharge()
                * mPowerProfile.getBatteryCapacity()) / 100;
        mMaxDrainedPower = (mStats.getHighDischargeAmountSinceCharge()
                * mPowerProfile.getBatteryCapacity()) / 100;

        // Create list of (almost all) sippers, calculate their usage, and put them in mUsageList.
        processAppUsage(asUsers);

        Collections.sort(mUsageList);

        Collections.sort(mMobilemsppList,
                (lhs, rhs) -> Double.compare(rhs.mobilemspp, lhs.mobilemspp));

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
            Log.d(TAG, "Accuracy: total computed=" + PowerCalculator.formatCharge(mComputedPower)
                    + ", min discharge=" + PowerCalculator.formatCharge(mMinDrainedPower)
                    + ", max discharge=" + PowerCalculator.formatCharge(mMaxDrainedPower));
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
        final SparseArray<? extends Uid> uidStats = mStats.getUidStats();

        final ArrayList<BatterySipper> sippers = new ArrayList<>(uidStats.size());

        for (int iu = 0, size = uidStats.size(); iu < size; iu++) {
            final Uid u = uidStats.valueAt(iu);
            sippers.add(new BatterySipper(DrainType.APP, u, 0));
        }

        for (int i = 0, size = mPowerCalculators.size(); i < size; i++) {
            final PowerCalculator calculator = mPowerCalculators.get(i);
            calculator.calculate(sippers, mStats, mRawRealtimeUs, mRawUptimeUs, mStatsType,
                    asUsers);
        }

        for (int i = sippers.size() - 1; i >= 0; i--) {
            final BatterySipper sipper = sippers.get(i);
            final double totalPower = sipper.sumPower();
            if (DEBUG && totalPower != 0) {
                Log.d(TAG, String.format("UID %d: total power=%s", sipper.getUid(),
                        PowerCalculator.formatCharge(totalPower)));
            }

            // Add the sipper to the list if it is consuming power.
            if (totalPower != 0 || sipper.getUid() == 0) {
                if (sipper.drainType == DrainType.APP) {
                    sipper.computeMobilemspp();
                    if (sipper.mobilemspp != 0) {
                        mMobilemsppList.add(sipper);
                    }
                }

                if (!sipper.isAggregated) {
                    mUsageList.add(sipper);
                }
            }
        }
    }

    @UnsupportedAppUsage
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

    @UnsupportedAppUsage
    public double getMaxPower() {
        return mMaxPower;
    }

    public double getMaxRealPower() {
        return mMaxRealPower;
    }

    @UnsupportedAppUsage
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
     * Mark the {@link BatterySipper} that we should hide.
     *
     * @param sippers sipper list that need to check and remove
     * @return the total power of the hidden items of {@link BatterySipper}
     * for proportional smearing
     */
    public double removeHiddenBatterySippers(List<BatterySipper> sippers) {
        double proportionalSmearPowerMah = 0;
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
        }
        return proportionalSmearPowerMah;
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

    @UnsupportedAppUsage
    private void load() {
        load(true);
    }

    private void load(boolean updateAll) {
        if (mBatteryInfo == null) {
            return;
        }
        mStats = getStats(mBatteryInfo, updateAll);
        if (mCollectBatteryBroadcast) {
            mBatteryBroadcast = mContext.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        }
    }

    private static BatteryStatsImpl getStats(IBatteryStats service, boolean updateAll) {
        try {
            ParcelFileDescriptor pfd = service.getStatisticsStream(updateAll);
            if (pfd != null) {
                if (false) {
                    Log.d(TAG, "selinux context: "
                            + SELinux.getFileContext(pfd.getFileDescriptor()));
                }
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
