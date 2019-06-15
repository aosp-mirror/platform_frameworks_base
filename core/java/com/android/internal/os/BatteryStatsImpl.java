/*
 * Copyright (C) 2006-2007 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UnsupportedAppUsage;
import android.app.ActivityManager;
import android.bluetooth.BluetoothActivityEnergyInfo;
import android.bluetooth.UidTraffic;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.INetworkStatsService;
import android.net.NetworkStats;
import android.net.Uri;
import android.net.wifi.WifiActivityEnergyInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.BatteryStats;
import android.os.Build;
import android.os.Handler;
import android.os.IBatteryPropertiesRegistrar;
import android.os.Looper;
import android.os.Message;
import android.os.OsProtoEnums;
import android.os.Parcel;
import android.os.ParcelFormatException;
import android.os.Parcelable;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.WorkSource;
import android.os.WorkSource.WorkChain;
import android.os.connectivity.CellularBatteryStats;
import android.os.connectivity.GpsBatteryStats;
import android.os.connectivity.WifiBatteryStats;
import android.provider.Settings;
import android.telephony.DataConnectionRealTimeInfo;
import android.telephony.ModemActivityInfo;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.IntArray;
import android.util.KeyValueListParser;
import android.util.Log;
import android.util.LogWriter;
import android.util.LongSparseArray;
import android.util.LongSparseLongArray;
import android.util.MutableInt;
import android.util.Pools;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.SparseLongArray;
import android.util.StatsLog;
import android.util.TimeUtils;
import android.util.Xml;
import android.view.Display;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.location.gnssmetrics.GnssMetrics;
import com.android.internal.os.KernelCpuUidTimeReader.KernelCpuUidActiveTimeReader;
import com.android.internal.os.KernelCpuUidTimeReader.KernelCpuUidClusterTimeReader;
import com.android.internal.os.KernelCpuUidTimeReader.KernelCpuUidFreqTimeReader;
import com.android.internal.os.KernelCpuUidTimeReader.KernelCpuUidUserSysTimeReader;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FastPrintWriter;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;

import libcore.util.EmptyArray;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * All information we are collecting about things that can happen that impact
 * battery life.  All times are represented in microseconds except where indicated
 * otherwise.
 */
public class BatteryStatsImpl extends BatteryStats {
    private static final String TAG = "BatteryStatsImpl";
    private static final boolean DEBUG = false;
    public static final boolean DEBUG_ENERGY = false;
    private static final boolean DEBUG_ENERGY_CPU = DEBUG_ENERGY;
    private static final boolean DEBUG_MEMORY = false;
    private static final boolean DEBUG_HISTORY = false;
    private static final boolean USE_OLD_HISTORY = false;   // for debugging.

    // TODO: remove "tcp" from network methods, since we measure total stats.

    // In-memory Parcel magic number, used to detect attempts to unmarshall bad data
    private static final int MAGIC = 0xBA757475; // 'BATSTATS'

    // Current on-disk Parcel version
    static final int VERSION = 186 + (USE_OLD_HISTORY ? 1000 : 0);

    // The maximum number of names wakelocks we will keep track of
    // per uid; once the limit is reached, we batch the remaining wakelocks
    // in to one common name.
    private static final int MAX_WAKELOCKS_PER_UID;

    static {
        if (ActivityManager.isLowRamDeviceStatic()) {
            MAX_WAKELOCKS_PER_UID = 40;
        } else {
            MAX_WAKELOCKS_PER_UID = 200;
        }
    }

    // Number of transmit power states the Wifi controller can be in.
    private static final int NUM_WIFI_TX_LEVELS = 1;

    // Number of transmit power states the Bluetooth controller can be in.
    private static final int NUM_BT_TX_LEVELS = 1;

    /**
     * Holding a wakelock costs more than just using the cpu.
     * Currently, we assign only half the cpu time to an app that is running but
     * not holding a wakelock. The apps holding wakelocks get the rest of the blame.
     * If no app is holding a wakelock, then the distribution is normal.
     */
    @VisibleForTesting
    public static final int WAKE_LOCK_WEIGHT = 50;

    protected Clocks mClocks;

    private final AtomicFile mStatsFile;
    public final AtomicFile mCheckinFile;
    public final AtomicFile mDailyFile;

    static final int MSG_REPORT_CPU_UPDATE_NEEDED = 1;
    static final int MSG_REPORT_POWER_CHANGE = 2;
    static final int MSG_REPORT_CHARGING = 3;
    static final int MSG_REPORT_RESET_STATS = 4;
    static final long DELAY_UPDATE_WAKELOCKS = 5*1000;

    private static final double MILLISECONDS_IN_HOUR = 3600 * 1000;
    private static final long MILLISECONDS_IN_YEAR = 365 * 24 * 3600 * 1000L;

    private final KernelWakelockReader mKernelWakelockReader = new KernelWakelockReader();
    private final KernelWakelockStats mTmpWakelockStats = new KernelWakelockStats();

    @VisibleForTesting
    protected KernelCpuUidUserSysTimeReader mCpuUidUserSysTimeReader =
            new KernelCpuUidUserSysTimeReader(true);
    @VisibleForTesting
    protected KernelCpuSpeedReader[] mKernelCpuSpeedReaders;
    @VisibleForTesting
    protected KernelCpuUidFreqTimeReader mCpuUidFreqTimeReader =
            new KernelCpuUidFreqTimeReader(true);
    @VisibleForTesting
    protected KernelCpuUidActiveTimeReader mCpuUidActiveTimeReader =
            new KernelCpuUidActiveTimeReader(true);
    @VisibleForTesting
    protected KernelCpuUidClusterTimeReader mCpuUidClusterTimeReader =
            new KernelCpuUidClusterTimeReader(true);
    @VisibleForTesting
    protected KernelSingleUidTimeReader mKernelSingleUidTimeReader;

    private final KernelMemoryBandwidthStats mKernelMemoryBandwidthStats
            = new KernelMemoryBandwidthStats();
    private final LongSparseArray<SamplingTimer> mKernelMemoryStats = new LongSparseArray<>();
    public LongSparseArray<SamplingTimer> getKernelMemoryStats() {
        return mKernelMemoryStats;
    }

    @GuardedBy("this")
    public boolean mPerProcStateCpuTimesAvailable = true;

    /**
     * When per process state cpu times tracking is off, cpu times in KernelSingleUidTimeReader are
     * not updated. So, when the setting is turned on later, we would end up with huge cpu time
     * deltas. This flag tracks the case where tracking is turned on from off so that we won't
     * end up attributing the huge deltas to wrong buckets.
     */
    @GuardedBy("this")
    private boolean mIsPerProcessStateCpuDataStale;

    /**
     * Uids for which per-procstate cpu times need to be updated.
     *
     * Contains uid -> procState mappings.
     */
    @GuardedBy("this")
    @VisibleForTesting
    protected final SparseIntArray mPendingUids = new SparseIntArray();

    @GuardedBy("this")
    private long mNumSingleUidCpuTimeReads;
    @GuardedBy("this")
    private long mNumBatchedSingleUidCpuTimeReads;
    @GuardedBy("this")
    private long mCpuTimeReadsTrackingStartTime = SystemClock.uptimeMillis();
    @GuardedBy("this")
    private int mNumUidsRemoved;
    @GuardedBy("this")
    private int mNumAllUidCpuTimeReads;

    /** Container for Resource Power Manager stats. Updated by updateRpmStatsLocked. */
    private final RpmStats mTmpRpmStats = new RpmStats();
    /** The soonest the RPM stats can be updated after it was last updated. */
    private static final long RPM_STATS_UPDATE_FREQ_MS = 1000;
    /** Last time that RPM stats were updated by updateRpmStatsLocked. */
    private long mLastRpmStatsUpdateTimeMs = -RPM_STATS_UPDATE_FREQ_MS;

    /** Container for Rail Energy Data stats. */
    private final RailStats mTmpRailStats = new RailStats();
    /**
     * Use a queue to delay removing UIDs from {@link KernelCpuUidUserSysTimeReader},
     * {@link KernelCpuUidActiveTimeReader}, {@link KernelCpuUidClusterTimeReader},
     * {@link KernelCpuUidFreqTimeReader} and from the Kernel.
     *
     * Isolated and invalid UID info must be removed to conserve memory. However, STATSD and
     * Batterystats both need to access UID cpu time. To resolve this race condition, only
     * Batterystats shall remove UIDs, and a delay {@link Constants#UID_REMOVE_DELAY_MS} is
     * implemented so that STATSD can capture those UID times before they are deleted.
     */
    @GuardedBy("this")
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    protected Queue<UidToRemove> mPendingRemovedUids = new LinkedList<>();

    @VisibleForTesting
    public final class UidToRemove {
        int startUid;
        int endUid;
        long timeAddedInQueue;

        /** Remove just one UID */
        public UidToRemove(int uid, long timestamp) {
            this(uid, uid, timestamp);
        }

        /** Remove a range of UIDs, startUid must be smaller than endUid. */
        public UidToRemove(int startUid, int endUid, long timestamp) {
            this.startUid = startUid;
            this.endUid = endUid;
            timeAddedInQueue = timestamp;
        }

        void remove() {
            if (startUid == endUid) {
                mCpuUidUserSysTimeReader.removeUid(startUid);
                mCpuUidFreqTimeReader.removeUid(startUid);
                if (mConstants.TRACK_CPU_ACTIVE_CLUSTER_TIME) {
                    mCpuUidActiveTimeReader.removeUid(startUid);
                    mCpuUidClusterTimeReader.removeUid(startUid);
                }
                if (mKernelSingleUidTimeReader != null) {
                    mKernelSingleUidTimeReader.removeUid(startUid);
                }
                mNumUidsRemoved++;
            } else if (startUid < endUid) {
                mCpuUidFreqTimeReader.removeUidsInRange(startUid, endUid);
                mCpuUidUserSysTimeReader.removeUidsInRange(startUid, endUid);
                if (mConstants.TRACK_CPU_ACTIVE_CLUSTER_TIME) {
                    mCpuUidActiveTimeReader.removeUidsInRange(startUid, endUid);
                    mCpuUidClusterTimeReader.removeUidsInRange(startUid, endUid);
                }
                if (mKernelSingleUidTimeReader != null) {
                    mKernelSingleUidTimeReader.removeUidsInRange(startUid, endUid);
                }
                // Treat as one. We don't know how many uids there are in between.
                mNumUidsRemoved++;
            } else {
                Slog.w(TAG, "End UID " + endUid + " is smaller than start UID " + startUid);
            }
        }
    }

    public interface BatteryCallback {
        public void batteryNeedsCpuUpdate();
        public void batteryPowerChanged(boolean onBattery);
        public void batterySendBroadcast(Intent intent);
        public void batteryStatsReset();
    }

    public interface PlatformIdleStateCallback {
        public void fillLowPowerStats(RpmStats rpmStats);
        public String getPlatformLowPowerStats();
        public String getSubsystemLowPowerStats();
    }

    /** interface to update rail information for power monitor */
    public interface RailEnergyDataCallback {
        /** Function to fill the map for the rail data stats
         * Used for power monitoring feature
         * @param railStats
         */
        void fillRailDataStats(RailStats railStats);
    }

    public static abstract class UserInfoProvider {
        private int[] userIds;
        protected abstract @Nullable int[] getUserIds();
        @VisibleForTesting
        public final void refreshUserIds() {
            userIds = getUserIds();
        }
        @VisibleForTesting
        public boolean exists(int userId) {
            return userIds != null ? ArrayUtils.contains(userIds, userId) : true;
        }
    }

    private final PlatformIdleStateCallback mPlatformIdleStateCallback;

    private final Runnable mDeferSetCharging = new Runnable() {
        @Override
        public void run() {
            synchronized (BatteryStatsImpl.this) {
                if (mOnBattery) {
                    // if the device gets unplugged in the time between this runnable being
                    // executed and the lock being taken, we don't want to set charging state
                    return;
                }
                boolean changed = setChargingLocked(true);
                if (changed) {
                    final long uptime = mClocks.uptimeMillis();
                    final long elapsedRealtime = mClocks.elapsedRealtime();
                    addHistoryRecordLocked(elapsedRealtime, uptime);
                }
            }
        }
    };

    public final RailEnergyDataCallback mRailEnergyDataCallback;

    /**
     * This handler is running on {@link BackgroundThread}.
     */
    final class MyHandler extends Handler {
        public MyHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override
        public void handleMessage(Message msg) {
            BatteryCallback cb = mCallback;
            switch (msg.what) {
                case MSG_REPORT_CPU_UPDATE_NEEDED:
                    if (cb != null) {
                        cb.batteryNeedsCpuUpdate();
                    }
                    break;
                case MSG_REPORT_POWER_CHANGE:
                    if (cb != null) {
                        cb.batteryPowerChanged(msg.arg1 != 0);
                    }
                    break;
                case MSG_REPORT_CHARGING:
                    if (cb != null) {
                        final String action;
                        synchronized (BatteryStatsImpl.this) {
                            action = mCharging ? BatteryManager.ACTION_CHARGING
                                    : BatteryManager.ACTION_DISCHARGING;
                        }
                        Intent intent = new Intent(action);
                        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                        cb.batterySendBroadcast(intent);
                    }
                    break;
                case MSG_REPORT_RESET_STATS:
                    if (cb != null) {
                        cb.batteryStatsReset();
                    }
                }
        }
    }

    public void postBatteryNeedsCpuUpdateMsg() {
        mHandler.sendEmptyMessage(MSG_REPORT_CPU_UPDATE_NEEDED);
    }

    /**
     * Update per-freq cpu times for all the uids in {@link #mPendingUids}.
     */
    public void updateProcStateCpuTimes(boolean onBattery, boolean onBatteryScreenOff) {
        final SparseIntArray uidStates;
        synchronized (BatteryStatsImpl.this) {
            if (!mConstants.TRACK_CPU_TIMES_BY_PROC_STATE) {
                return;
            }
            if(!initKernelSingleUidTimeReaderLocked()) {
                return;
            }
            // If the KernelSingleUidTimeReader has stale cpu times, then we shouldn't try to
            // compute deltas since it might result in mis-attributing cpu times to wrong states.
            if (mIsPerProcessStateCpuDataStale) {
                mPendingUids.clear();
                return;
            }

            if (mPendingUids.size() == 0) {
                return;
            }
            uidStates = mPendingUids.clone();
            mPendingUids.clear();
        }
        for (int i = uidStates.size() - 1; i >= 0; --i) {
            final int uid = uidStates.keyAt(i);
            final int procState = uidStates.valueAt(i);
            final int[] isolatedUids;
            final Uid u;
            synchronized (BatteryStatsImpl.this) {
                // It's possible that uid no longer exists and any internal references have
                // already been deleted, so using {@link #getAvailableUidStatsLocked} to avoid
                // creating an UidStats object if it doesn't already exist.
                u = getAvailableUidStatsLocked(uid);
                if (u == null) {
                    continue;
                }
                if (u.mChildUids == null) {
                    isolatedUids = null;
                } else {
                    isolatedUids = u.mChildUids.toArray();
                    for (int j = isolatedUids.length - 1; j >= 0; --j) {
                        isolatedUids[j] = u.mChildUids.get(j);
                    }
                }
            }
            long[] cpuTimesMs = mKernelSingleUidTimeReader.readDeltaMs(uid);
            if (isolatedUids != null) {
                for (int j = isolatedUids.length - 1; j >= 0; --j) {
                    cpuTimesMs = addCpuTimes(cpuTimesMs,
                            mKernelSingleUidTimeReader.readDeltaMs(isolatedUids[j]));
                }
            }
            if (onBattery && cpuTimesMs != null) {
                synchronized (BatteryStatsImpl.this) {
                    u.addProcStateTimesMs(procState, cpuTimesMs, onBattery);
                    u.addProcStateScreenOffTimesMs(procState, cpuTimesMs, onBatteryScreenOff);
                }
            }
        }
    }

    public void clearPendingRemovedUids() {
        long cutOffTime = mClocks.elapsedRealtime() - mConstants.UID_REMOVE_DELAY_MS;
        while (!mPendingRemovedUids.isEmpty()
                && mPendingRemovedUids.peek().timeAddedInQueue < cutOffTime) {
            mPendingRemovedUids.poll().remove();
        }
    }

    public void copyFromAllUidsCpuTimes() {
        synchronized (BatteryStatsImpl.this) {
            copyFromAllUidsCpuTimes(
                    mOnBatteryTimeBase.isRunning(), mOnBatteryScreenOffTimeBase.isRunning());
        }
    }

    /**
     * When the battery/screen state changes, we don't attribute the cpu times to any process
     * but we still need to snapshots of all uids to get correct deltas later on. Since we
     * already read this data for updating per-freq cpu times, we can use the same data for
     * per-procstate cpu times.
     */
    public void copyFromAllUidsCpuTimes(boolean onBattery, boolean onBatteryScreenOff) {
        synchronized (BatteryStatsImpl.this) {
            if (!mConstants.TRACK_CPU_TIMES_BY_PROC_STATE) {
                return;
            }
            if(!initKernelSingleUidTimeReaderLocked()) {
                return;
            }

            final SparseArray<long[]> allUidCpuFreqTimesMs =
                    mCpuUidFreqTimeReader.getAllUidCpuFreqTimeMs();
            // If the KernelSingleUidTimeReader has stale cpu times, then we shouldn't try to
            // compute deltas since it might result in mis-attributing cpu times to wrong states.
            if (mIsPerProcessStateCpuDataStale) {
                mKernelSingleUidTimeReader.setAllUidsCpuTimesMs(allUidCpuFreqTimesMs);
                mIsPerProcessStateCpuDataStale = false;
                mPendingUids.clear();
                return;
            }
            for (int i = allUidCpuFreqTimesMs.size() - 1; i >= 0; --i) {
                final int uid = allUidCpuFreqTimesMs.keyAt(i);
                final Uid u = getAvailableUidStatsLocked(mapUid(uid));
                if (u == null) {
                    continue;
                }
                final long[] cpuTimesMs = allUidCpuFreqTimesMs.valueAt(i);
                if (cpuTimesMs == null) {
                    continue;
                }
                final long[] deltaTimesMs = mKernelSingleUidTimeReader.computeDelta(
                        uid, cpuTimesMs.clone());
                if (onBattery && deltaTimesMs != null) {
                    final int procState;
                    final int idx = mPendingUids.indexOfKey(uid);
                    if (idx >= 0) {
                        procState = mPendingUids.valueAt(idx);
                        mPendingUids.removeAt(idx);
                    } else {
                        procState = u.mProcessState;
                    }
                    if (procState >= 0 && procState < Uid.NUM_PROCESS_STATE) {
                        u.addProcStateTimesMs(procState, deltaTimesMs, onBattery);
                        u.addProcStateScreenOffTimesMs(procState, deltaTimesMs, onBatteryScreenOff);
                    }
                }
            }
        }
    }

    @VisibleForTesting
    public long[] addCpuTimes(long[] timesA, long[] timesB) {
        if (timesA != null && timesB != null) {
            for (int i = timesA.length - 1; i >= 0; --i) {
                timesA[i] += timesB[i];
            }
            return timesA;
        }
        return timesA == null ? (timesB == null ? null : timesB) : timesA;
    }

    @GuardedBy("this")
    private boolean initKernelSingleUidTimeReaderLocked() {
        if (mKernelSingleUidTimeReader == null) {
            if (mPowerProfile == null) {
                return false;
            }
            if (mCpuFreqs == null) {
                mCpuFreqs = mCpuUidFreqTimeReader.readFreqs(mPowerProfile);
            }
            if (mCpuFreqs != null) {
                mKernelSingleUidTimeReader = new KernelSingleUidTimeReader(mCpuFreqs.length);
            } else {
                mPerProcStateCpuTimesAvailable = mCpuUidFreqTimeReader.allUidTimesAvailable();
                return false;
            }
        }
        mPerProcStateCpuTimesAvailable = mCpuUidFreqTimeReader.allUidTimesAvailable()
                && mKernelSingleUidTimeReader.singleUidCpuTimesAvailable();
        return true;
    }

    public interface Clocks {
        public long elapsedRealtime();
        public long uptimeMillis();
    }

    public static class SystemClocks implements Clocks {
        public long elapsedRealtime() {
            return SystemClock.elapsedRealtime();
        }

        public long uptimeMillis() {
            return SystemClock.uptimeMillis();
        }
    }

    public interface ExternalStatsSync {
        int UPDATE_CPU = 0x01;
        int UPDATE_WIFI = 0x02;
        int UPDATE_RADIO = 0x04;
        int UPDATE_BT = 0x08;
        int UPDATE_RPM = 0x10; // 16
        int UPDATE_ALL = UPDATE_CPU | UPDATE_WIFI | UPDATE_RADIO | UPDATE_BT | UPDATE_RPM;

        Future<?> scheduleSync(String reason, int flags);
        Future<?> scheduleCpuSyncDueToRemovedUid(int uid);
        Future<?> scheduleReadProcStateCpuTimes(boolean onBattery, boolean onBatteryScreenOff,
                long delayMillis);
        Future<?> scheduleCopyFromAllUidsCpuTimes(boolean onBattery, boolean onBatteryScreenOff);
        Future<?> scheduleCpuSyncDueToSettingChange();
        Future<?> scheduleCpuSyncDueToScreenStateChange(boolean onBattery,
                boolean onBatteryScreenOff);
        Future<?> scheduleCpuSyncDueToWakelockChange(long delayMillis);
        void cancelCpuSyncDueToWakelockChange();
        Future<?> scheduleSyncDueToBatteryLevelChange(long delayMillis);
    }

    public Handler mHandler;
    private ExternalStatsSync mExternalSync = null;
    @VisibleForTesting
    protected UserInfoProvider mUserInfoProvider = null;

    private BatteryCallback mCallback;

    /**
     * Mapping isolated uids to the actual owning app uid.
     */
    final SparseIntArray mIsolatedUids = new SparseIntArray();

    /**
     * The statistics we have collected organized by uids.
     */
    final SparseArray<BatteryStatsImpl.Uid> mUidStats = new SparseArray<>();

    // A set of pools of currently active timers.  When a timer is queried, we will divide the
    // elapsed time by the number of active timers to arrive at that timer's share of the time.
    // In order to do this, we must refresh each timer whenever the number of active timers
    // changes.
    @VisibleForTesting
    @UnsupportedAppUsage
    protected ArrayList<StopwatchTimer> mPartialTimers = new ArrayList<>();
    @UnsupportedAppUsage
    final ArrayList<StopwatchTimer> mFullTimers = new ArrayList<>();
    @UnsupportedAppUsage
    final ArrayList<StopwatchTimer> mWindowTimers = new ArrayList<>();
    final ArrayList<StopwatchTimer> mDrawTimers = new ArrayList<>();
    final SparseArray<ArrayList<StopwatchTimer>> mSensorTimers = new SparseArray<>();
    final ArrayList<StopwatchTimer> mWifiRunningTimers = new ArrayList<>();
    final ArrayList<StopwatchTimer> mFullWifiLockTimers = new ArrayList<>();
    final ArrayList<StopwatchTimer> mWifiMulticastTimers = new ArrayList<>();
    final ArrayList<StopwatchTimer> mWifiScanTimers = new ArrayList<>();
    final SparseArray<ArrayList<StopwatchTimer>> mWifiBatchedScanTimers = new SparseArray<>();
    final ArrayList<StopwatchTimer> mAudioTurnedOnTimers = new ArrayList<>();
    final ArrayList<StopwatchTimer> mVideoTurnedOnTimers = new ArrayList<>();
    final ArrayList<StopwatchTimer> mFlashlightTurnedOnTimers = new ArrayList<>();
    final ArrayList<StopwatchTimer> mCameraTurnedOnTimers = new ArrayList<>();
    final ArrayList<StopwatchTimer> mBluetoothScanOnTimers = new ArrayList<>();

    // Last partial timers we use for distributing CPU usage.
    @VisibleForTesting
    protected ArrayList<StopwatchTimer> mLastPartialTimers = new ArrayList<>();

    // These are the objects that will want to do something when the device
    // is unplugged from power.
    protected final TimeBase mOnBatteryTimeBase = new TimeBase(true);

    // These are the objects that will want to do something when the device
    // is unplugged from power *and* the screen is off or doze.
    protected final TimeBase mOnBatteryScreenOffTimeBase = new TimeBase(true);

    // Set to true when we want to distribute CPU across wakelocks for the next
    // CPU update, even if we aren't currently running wake locks.
    boolean mDistributeWakelockCpu;

    boolean mShuttingDown;

    final HistoryEventTracker mActiveEvents = new HistoryEventTracker();

    long mHistoryBaseTime;
    protected boolean mHaveBatteryLevel = false;
    protected boolean mRecordingHistory = false;
    int mNumHistoryItems;

    final Parcel mHistoryBuffer = Parcel.obtain();
    final HistoryItem mHistoryLastWritten = new HistoryItem();
    final HistoryItem mHistoryLastLastWritten = new HistoryItem();
    final HistoryItem mHistoryReadTmp = new HistoryItem();
    final HistoryItem mHistoryAddTmp = new HistoryItem();
    final HashMap<HistoryTag, Integer> mHistoryTagPool = new HashMap<>();
    String[] mReadHistoryStrings;
    int[] mReadHistoryUids;
    int mReadHistoryChars;
    int mNextHistoryTagIdx = 0;
    int mNumHistoryTagChars = 0;
    int mHistoryBufferLastPos = -1;
    int mActiveHistoryStates = 0xffffffff;
    int mActiveHistoryStates2 = 0xffffffff;
    long mLastHistoryElapsedRealtime = 0;
    long mTrackRunningHistoryElapsedRealtime = 0;
    long mTrackRunningHistoryUptime = 0;

    final BatteryStatsHistory mBatteryStatsHistory;

    final HistoryItem mHistoryCur = new HistoryItem();

    HistoryItem mHistory;
    HistoryItem mHistoryEnd;
    HistoryItem mHistoryLastEnd;
    HistoryItem mHistoryCache;

    // Used by computeHistoryStepDetails
    HistoryStepDetails mLastHistoryStepDetails = null;
    byte mLastHistoryStepLevel = 0;
    final HistoryStepDetails mCurHistoryStepDetails = new HistoryStepDetails();
    final HistoryStepDetails mReadHistoryStepDetails = new HistoryStepDetails();
    final HistoryStepDetails mTmpHistoryStepDetails = new HistoryStepDetails();

    /**
     * Total time (in milliseconds) spent executing in user code.
     */
    long mLastStepCpuUserTime;
    long mCurStepCpuUserTime;
    /**
     * Total time (in milliseconds) spent executing in kernel code.
     */
    long mLastStepCpuSystemTime;
    long mCurStepCpuSystemTime;
    /**
     * Times from /proc/stat (but measured in milliseconds).
     */
    long mLastStepStatUserTime;
    long mLastStepStatSystemTime;
    long mLastStepStatIOWaitTime;
    long mLastStepStatIrqTime;
    long mLastStepStatSoftIrqTime;
    long mLastStepStatIdleTime;
    long mCurStepStatUserTime;
    long mCurStepStatSystemTime;
    long mCurStepStatIOWaitTime;
    long mCurStepStatIrqTime;
    long mCurStepStatSoftIrqTime;
    long mCurStepStatIdleTime;

    private HistoryItem mHistoryIterator;
    private boolean mReadOverflow;
    private boolean mIteratingHistory;

    int mStartCount;

    long mStartClockTime;
    String mStartPlatformVersion;
    String mEndPlatformVersion;

    long mUptime;
    long mUptimeStart;
    long mRealtime;
    long mRealtimeStart;

    int mWakeLockNesting;
    boolean mWakeLockImportant;
    public boolean mRecordAllHistory;
    boolean mNoAutoReset;

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    protected int mScreenState = Display.STATE_UNKNOWN;
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    protected StopwatchTimer mScreenOnTimer;
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    protected StopwatchTimer mScreenDozeTimer;

    int mScreenBrightnessBin = -1;
    final StopwatchTimer[] mScreenBrightnessTimer = new StopwatchTimer[NUM_SCREEN_BRIGHTNESS_BINS];

    boolean mPretendScreenOff;

    boolean mInteractive;
    StopwatchTimer mInteractiveTimer;

    boolean mPowerSaveModeEnabled;
    StopwatchTimer mPowerSaveModeEnabledTimer;

    boolean mDeviceIdling;
    StopwatchTimer mDeviceIdlingTimer;

    boolean mDeviceLightIdling;
    StopwatchTimer mDeviceLightIdlingTimer;

    int mDeviceIdleMode;
    long mLastIdleTimeStart;
    long mLongestLightIdleTime;
    long mLongestFullIdleTime;
    StopwatchTimer mDeviceIdleModeLightTimer;
    StopwatchTimer mDeviceIdleModeFullTimer;

    boolean mPhoneOn;
    StopwatchTimer mPhoneOnTimer;

    int mAudioOnNesting;
    StopwatchTimer mAudioOnTimer;

    int mVideoOnNesting;
    StopwatchTimer mVideoOnTimer;

    int mFlashlightOnNesting;
    StopwatchTimer mFlashlightOnTimer;

    int mCameraOnNesting;
    StopwatchTimer mCameraOnTimer;

    private static final int USB_DATA_UNKNOWN = 0;
    private static final int USB_DATA_DISCONNECTED = 1;
    private static final int USB_DATA_CONNECTED = 2;
    int mUsbDataState = USB_DATA_UNKNOWN;

    int mGpsSignalQualityBin = -1;
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    protected final StopwatchTimer[] mGpsSignalQualityTimer =
        new StopwatchTimer[GnssMetrics.NUM_GPS_SIGNAL_QUALITY_LEVELS];

    int mPhoneSignalStrengthBin = -1;
    int mPhoneSignalStrengthBinRaw = -1;
    final StopwatchTimer[] mPhoneSignalStrengthsTimer =
            new StopwatchTimer[SignalStrength.NUM_SIGNAL_STRENGTH_BINS];

    StopwatchTimer mPhoneSignalScanningTimer;

    int mPhoneDataConnectionType = -1;
    final StopwatchTimer[] mPhoneDataConnectionsTimer =
            new StopwatchTimer[NUM_DATA_CONNECTION_TYPES];

    final LongSamplingCounter[] mNetworkByteActivityCounters =
            new LongSamplingCounter[NUM_NETWORK_ACTIVITY_TYPES];
    final LongSamplingCounter[] mNetworkPacketActivityCounters =
            new LongSamplingCounter[NUM_NETWORK_ACTIVITY_TYPES];

    /**
     * The WiFi Overall wakelock timer
     * This timer tracks the actual aggregate time for which MC wakelocks are enabled
     * since addition of per UID timers would not result in an accurate value due to overlapp of
     * per uid wakelock timers
     */
    StopwatchTimer mWifiMulticastWakelockTimer;

    /**
     * The WiFi controller activity (time in tx, rx, idle, and power consumed) for the device.
     */
    ControllerActivityCounterImpl mWifiActivity;

    /**
     * The Bluetooth controller activity (time in tx, rx, idle, and power consumed) for the device.
     */
    ControllerActivityCounterImpl mBluetoothActivity;

    /**
     * The Modem controller activity (time in tx, rx, idle, and power consumed) for the device.
     */
    ControllerActivityCounterImpl mModemActivity;

    /**
     * Whether the device supports WiFi controller energy reporting. This is set to true on
     * the first WiFi energy report. See {@link #mWifiActivity}.
     */
    boolean mHasWifiReporting = false;

    /**
     * Whether the device supports Bluetooth controller energy reporting. This is set to true on
     * the first Bluetooth energy report. See {@link #mBluetoothActivity}.
     */
    boolean mHasBluetoothReporting = false;

    /**
     * Whether the device supports Modem controller energy reporting. This is set to true on
     * the first Modem energy report. See {@link #mModemActivity}.
     */
    boolean mHasModemReporting = false;

    boolean mWifiOn;
    StopwatchTimer mWifiOnTimer;

    boolean mGlobalWifiRunning;
    StopwatchTimer mGlobalWifiRunningTimer;

    int mWifiState = -1;
    final StopwatchTimer[] mWifiStateTimer = new StopwatchTimer[NUM_WIFI_STATES];

    int mWifiSupplState = -1;
    final StopwatchTimer[] mWifiSupplStateTimer = new StopwatchTimer[NUM_WIFI_SUPPL_STATES];

    int mWifiSignalStrengthBin = -1;
    final StopwatchTimer[] mWifiSignalStrengthsTimer =
            new StopwatchTimer[NUM_WIFI_SIGNAL_STRENGTH_BINS];

    StopwatchTimer mWifiActiveTimer;

    int mBluetoothScanNesting;
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    protected StopwatchTimer mBluetoothScanTimer;

    int mMobileRadioPowerState = DataConnectionRealTimeInfo.DC_POWER_STATE_LOW;
    long mMobileRadioActiveStartTime;
    StopwatchTimer mMobileRadioActiveTimer;
    StopwatchTimer mMobileRadioActivePerAppTimer;
    LongSamplingCounter mMobileRadioActiveAdjustedTime;
    LongSamplingCounter mMobileRadioActiveUnknownTime;
    LongSamplingCounter mMobileRadioActiveUnknownCount;

    int mWifiRadioPowerState = DataConnectionRealTimeInfo.DC_POWER_STATE_LOW;

    /**
     * These provide time bases that discount the time the device is plugged
     * in to power.
     */
    boolean mOnBattery;
    @VisibleForTesting
    protected boolean mOnBatteryInternal;

    /**
     * External reporting of whether the device is actually charging.
     */
    boolean mCharging = true;
    int mLastChargingStateLevel;

    /*
     * These keep track of battery levels (1-100) at the last plug event and the last unplug event.
     */
    int mDischargeStartLevel;
    int mDischargeUnplugLevel;
    int mDischargePlugLevel;
    int mDischargeCurrentLevel;
    int mCurrentBatteryLevel;
    int mLowDischargeAmountSinceCharge;
    int mHighDischargeAmountSinceCharge;
    int mDischargeScreenOnUnplugLevel;
    int mDischargeScreenOffUnplugLevel;
    int mDischargeScreenDozeUnplugLevel;
    int mDischargeAmountScreenOn;
    int mDischargeAmountScreenOnSinceCharge;
    int mDischargeAmountScreenOff;
    int mDischargeAmountScreenOffSinceCharge;
    int mDischargeAmountScreenDoze;
    int mDischargeAmountScreenDozeSinceCharge;

    private LongSamplingCounter mDischargeScreenOffCounter;
    private LongSamplingCounter mDischargeScreenDozeCounter;
    private LongSamplingCounter mDischargeCounter;
    private LongSamplingCounter mDischargeLightDozeCounter;
    private LongSamplingCounter mDischargeDeepDozeCounter;

    static final int MAX_LEVEL_STEPS = 200;

    int mInitStepMode = 0;
    int mCurStepMode = 0;
    int mModStepMode = 0;

    int mLastDischargeStepLevel;
    int mMinDischargeStepLevel;
    final LevelStepTracker mDischargeStepTracker = new LevelStepTracker(MAX_LEVEL_STEPS);
    final LevelStepTracker mDailyDischargeStepTracker = new LevelStepTracker(MAX_LEVEL_STEPS*2);
    ArrayList<PackageChange> mDailyPackageChanges;

    int mLastChargeStepLevel;
    int mMaxChargeStepLevel;
    final LevelStepTracker mChargeStepTracker = new LevelStepTracker(MAX_LEVEL_STEPS);
    final LevelStepTracker mDailyChargeStepTracker = new LevelStepTracker(MAX_LEVEL_STEPS*2);

    static final int MAX_DAILY_ITEMS = 10;

    long mDailyStartTime = 0;
    long mNextMinDailyDeadline = 0;
    long mNextMaxDailyDeadline = 0;

    final ArrayList<DailyItem> mDailyItems = new ArrayList<>();

    long mLastWriteTime = 0; // Milliseconds

    private int mPhoneServiceState = -1;
    private int mPhoneServiceStateRaw = -1;
    private int mPhoneSimStateRaw = -1;

    private int mNumConnectivityChange;

    private int mEstimatedBatteryCapacity = -1;

    private int mMinLearnedBatteryCapacity = -1;
    private int mMaxLearnedBatteryCapacity = -1;

    private long[] mCpuFreqs;

    @VisibleForTesting
    protected PowerProfile mPowerProfile;

    @GuardedBy("this")
    final Constants mConstants;

    /*
     * Holds a SamplingTimer associated with each Resource Power Manager state and voter,
     * recording their times when on-battery (regardless of screen state).
     */
    private final HashMap<String, SamplingTimer> mRpmStats = new HashMap<>();
    /** Times for each Resource Power Manager state and voter when screen-off and on-battery. */
    private final HashMap<String, SamplingTimer> mScreenOffRpmStats = new HashMap<>();

    @Override
    public Map<String, ? extends Timer> getRpmStats() {
        return mRpmStats;
    }

    // TODO: Note: screenOffRpmStats has been disabled via SCREEN_OFF_RPM_STATS_ENABLED.
    @Override
    public Map<String, ? extends Timer> getScreenOffRpmStats() {
        return mScreenOffRpmStats;
    }

    /*
     * Holds a SamplingTimer associated with each kernel wakelock name being tracked.
     */
    private final HashMap<String, SamplingTimer> mKernelWakelockStats = new HashMap<>();

    @UnsupportedAppUsage
    public Map<String, ? extends Timer> getKernelWakelockStats() {
        return mKernelWakelockStats;
    }

    String mLastWakeupReason = null;
    long mLastWakeupUptimeMs = 0;
    private final HashMap<String, SamplingTimer> mWakeupReasonStats = new HashMap<>();

    public Map<String, ? extends Timer> getWakeupReasonStats() {
        return mWakeupReasonStats;
    }

    @Override
    public long getUahDischarge(int which) {
        return mDischargeCounter.getCountLocked(which);
    }

    @Override
    public long getUahDischargeScreenOff(int which) {
        return mDischargeScreenOffCounter.getCountLocked(which);
    }

    @Override
    public long getUahDischargeScreenDoze(int which) {
        return mDischargeScreenDozeCounter.getCountLocked(which);
    }

    @Override
    public long getUahDischargeLightDoze(int which) {
        return mDischargeLightDozeCounter.getCountLocked(which);
    }

    @Override
    public long getUahDischargeDeepDoze(int which) {
        return mDischargeDeepDozeCounter.getCountLocked(which);
    }

    @Override
    public int getEstimatedBatteryCapacity() {
        return mEstimatedBatteryCapacity;
    }

    @Override
    public int getMinLearnedBatteryCapacity() {
        return mMinLearnedBatteryCapacity;
    }

    @Override
    public int getMaxLearnedBatteryCapacity() {
        return mMaxLearnedBatteryCapacity;
    }

    public BatteryStatsImpl() {
        this(new SystemClocks());
    }

    public BatteryStatsImpl(Clocks clocks) {
        init(clocks);
        mStatsFile = null;
        mCheckinFile = null;
        mDailyFile = null;
        mBatteryStatsHistory = null;
        mHandler = null;
        mPlatformIdleStateCallback = null;
        mRailEnergyDataCallback = null;
        mUserInfoProvider = null;
        mConstants = new Constants(mHandler);
        clearHistoryLocked();
    }

    private void init(Clocks clocks) {
        mClocks = clocks;
    }

    /**
     * TimeBase observer.
     */
    public interface TimeBaseObs {
        void onTimeStarted(long elapsedRealtime, long baseUptime, long baseRealtime);
        void onTimeStopped(long elapsedRealtime, long baseUptime, long baseRealtime);

        /**
         * Reset the observer's state, returns true if the timer/counter is inactive
         * so it can be destroyed.
         * @param detachIfReset detach if true, no-op if false.
         * @return Returns true if the timer/counter is inactive and can be destroyed.
         */
        boolean reset(boolean detachIfReset);
        /**
         * Detach the observer from TimeBase.
         */
        void detach();
    }

    // methods are protected not private to be VisibleForTesting
    public static class TimeBase {
        protected final Collection<TimeBaseObs> mObservers;
        protected long mUptime;
        protected long mRealtime;

        protected boolean mRunning;

        protected long mPastUptime;
        protected long mUptimeStart;
        protected long mPastRealtime;
        protected long mRealtimeStart;
        protected long mUnpluggedUptime;
        protected long mUnpluggedRealtime;

        public void dump(PrintWriter pw, String prefix) {
            StringBuilder sb = new StringBuilder(128);
            pw.print(prefix); pw.print("mRunning="); pw.println(mRunning);
            sb.setLength(0);
            sb.append(prefix);
                    sb.append("mUptime=");
                    formatTimeMs(sb, mUptime / 1000);
            pw.println(sb.toString());
            sb.setLength(0);
            sb.append(prefix);
                    sb.append("mRealtime=");
                    formatTimeMs(sb, mRealtime / 1000);
            pw.println(sb.toString());
            sb.setLength(0);
            sb.append(prefix);
                    sb.append("mPastUptime=");
                    formatTimeMs(sb, mPastUptime / 1000); sb.append("mUptimeStart=");
                    formatTimeMs(sb, mUptimeStart / 1000);
                    sb.append("mUnpluggedUptime="); formatTimeMs(sb, mUnpluggedUptime / 1000);
            pw.println(sb.toString());
            sb.setLength(0);
            sb.append(prefix);
                    sb.append("mPastRealtime=");
                    formatTimeMs(sb, mPastRealtime / 1000); sb.append("mRealtimeStart=");
                    formatTimeMs(sb, mRealtimeStart / 1000);
                    sb.append("mUnpluggedRealtime="); formatTimeMs(sb, mUnpluggedRealtime / 1000);
            pw.println(sb.toString());
        }
        /**
         * The mObservers of TimeBase in BatteryStatsImpl object can contain up to 20k entries.
         * The mObservers of TimeBase in BatteryStatsImpl.Uid object only contains a few or tens of
         * entries.
         * mObservers must have good performance on add(), remove(), also be memory efficient.
         * This is why we provide isLongList parameter for long and short list user cases.
         * @param isLongList If true, use HashSet for mObservers list.
         *                   If false, use ArrayList for mObservers list.
        */
        public TimeBase(boolean isLongList) {
            mObservers = isLongList ? new HashSet<>() : new ArrayList<>();
        }

        public TimeBase() {
            this(false);
        }

        public void add(TimeBaseObs observer) {
            mObservers.add(observer);
        }

        public void remove(TimeBaseObs observer) {
            mObservers.remove(observer);
        }

        public boolean hasObserver(TimeBaseObs observer) {
            return mObservers.contains(observer);
        }

        public void init(long uptime, long realtime) {
            mRealtime = 0;
            mUptime = 0;
            mPastUptime = 0;
            mPastRealtime = 0;
            mUptimeStart = uptime;
            mRealtimeStart = realtime;
            mUnpluggedUptime = getUptime(mUptimeStart);
            mUnpluggedRealtime = getRealtime(mRealtimeStart);
        }

        public void reset(long uptime, long realtime) {
            if (!mRunning) {
                mPastUptime = 0;
                mPastRealtime = 0;
            } else {
                mUptimeStart = uptime;
                mRealtimeStart = realtime;
                // TODO: Since mUptimeStart was just reset and we are running, getUptime will
                // just return mPastUptime. Also, are we sure we don't want to reset that?
                mUnpluggedUptime = getUptime(uptime);
                // TODO: likewise.
                mUnpluggedRealtime = getRealtime(realtime);
            }
        }

        public long computeUptime(long curTime, int which) {
            return mUptime + getUptime(curTime);
        }

        public long computeRealtime(long curTime, int which) {
            return mRealtime + getRealtime(curTime);
        }

        public long getUptime(long curTime) {
            long time = mPastUptime;
            if (mRunning) {
                time += curTime - mUptimeStart;
            }
            return time;
        }

        public long getRealtime(long curTime) {
            long time = mPastRealtime;
            if (mRunning) {
                time += curTime - mRealtimeStart;
            }
            return time;
        }

        public long getUptimeStart() {
            return mUptimeStart;
        }

        public long getRealtimeStart() {
            return mRealtimeStart;
        }

        public boolean isRunning() {
            return mRunning;
        }

        public boolean setRunning(boolean running, long uptime, long realtime) {
            if (mRunning != running) {
                mRunning = running;
                if (running) {
                    mUptimeStart = uptime;
                    mRealtimeStart = realtime;
                    long batteryUptime = mUnpluggedUptime = getUptime(uptime);
                    long batteryRealtime = mUnpluggedRealtime = getRealtime(realtime);
                    // Normally we do not use Iterator in framework code to avoid alloc/dealloc
                    // Iterator object, here is an exception because mObservers' type is Collection
                    // instead of list.
                    final Iterator<TimeBaseObs> iter = mObservers.iterator();
                    while (iter.hasNext()) {
                        iter.next().onTimeStarted(realtime, batteryUptime, batteryRealtime);
                    }
                } else {
                    mPastUptime += uptime - mUptimeStart;
                    mPastRealtime += realtime - mRealtimeStart;
                    long batteryUptime = getUptime(uptime);
                    long batteryRealtime = getRealtime(realtime);
                    // Normally we do not use Iterator in framework code to avoid alloc/dealloc
                    // Iterator object, here is an exception because mObservers' type is Collection
                    // instead of list.
                    final Iterator<TimeBaseObs> iter = mObservers.iterator();
                    while (iter.hasNext()) {
                        iter.next().onTimeStopped(realtime, batteryUptime, batteryRealtime);
                    }
                }
                return true;
            }
            return false;
        }

        public void readSummaryFromParcel(Parcel in) {
            mUptime = in.readLong();
            mRealtime = in.readLong();
        }

        public void writeSummaryToParcel(Parcel out, long uptime, long realtime) {
            out.writeLong(computeUptime(uptime, STATS_SINCE_CHARGED));
            out.writeLong(computeRealtime(realtime, STATS_SINCE_CHARGED));
        }

        public void readFromParcel(Parcel in) {
            mRunning = false;
            mUptime = in.readLong();
            mPastUptime = in.readLong();
            mUptimeStart = in.readLong();
            mRealtime = in.readLong();
            mPastRealtime = in.readLong();
            mRealtimeStart = in.readLong();
            mUnpluggedUptime = in.readLong();
            mUnpluggedRealtime = in.readLong();
        }

        public void writeToParcel(Parcel out, long uptime, long realtime) {
            final long runningUptime = getUptime(uptime);
            final long runningRealtime = getRealtime(realtime);
            out.writeLong(mUptime);
            out.writeLong(runningUptime);
            out.writeLong(mUptimeStart);
            out.writeLong(mRealtime);
            out.writeLong(runningRealtime);
            out.writeLong(mRealtimeStart);
            out.writeLong(mUnpluggedUptime);
            out.writeLong(mUnpluggedRealtime);
        }
    }

    /**
     * State for keeping track of counting information.
     */
    public static class Counter extends BatteryStats.Counter implements TimeBaseObs {
        @UnsupportedAppUsage
        final AtomicInteger mCount = new AtomicInteger();
        final TimeBase mTimeBase;

        public Counter(TimeBase timeBase, Parcel in) {
            mTimeBase = timeBase;
            mCount.set(in.readInt());
            timeBase.add(this);
        }

        public Counter(TimeBase timeBase) {
            mTimeBase = timeBase;
            timeBase.add(this);
        }

        public void writeToParcel(Parcel out) {
            out.writeInt(mCount.get());
        }

        @Override
        public void onTimeStarted(long elapsedRealtime, long baseUptime, long baseRealtime) {
        }

        @Override
        public void onTimeStopped(long elapsedRealtime, long baseUptime, long baseRealtime) {
        }

        /**
         * Writes a possibly null Counter to a Parcel.
         *
         * @param out the Parcel to be written to.
         * @param counter a Counter, or null.
         */
        public static void writeCounterToParcel(Parcel out, @Nullable Counter counter) {
            if (counter == null) {
                out.writeInt(0); // indicates null
                return;
            }
            out.writeInt(1); // indicates non-null

            counter.writeToParcel(out);
        }

        /**
         * Reads a Counter that was written using {@link #writeCounterToParcel(Parcel, Counter)}.
         * @param timeBase the timebase to assign to the Counter
         * @param in the parcel to read from
         * @return the Counter or null.
         */
        public static @Nullable Counter readCounterFromParcel(TimeBase timeBase, Parcel in) {
            if (in.readInt() == 0) {
                return null;
            }
            return new Counter(timeBase, in);
        }

        @Override
        public int getCountLocked(int which) {
            return mCount.get();
        }

        public void logState(Printer pw, String prefix) {
            pw.println(prefix + "mCount=" + mCount.get());
        }

        @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
        public void stepAtomic() {
            if (mTimeBase.isRunning()) {
                mCount.incrementAndGet();
            }
        }

        void addAtomic(int delta) {
            if (mTimeBase.isRunning()) {
                mCount.addAndGet(delta);
            }
        }

        /**
         * Clear state of this counter.
         */
        @Override
        public boolean reset(boolean detachIfReset) {
            mCount.set(0);
            if (detachIfReset) {
                detach();
            }
            return true;
        }

        @Override
        public void detach() {
            mTimeBase.remove(this);
        }

        @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
        public void writeSummaryFromParcelLocked(Parcel out) {
            out.writeInt(mCount.get());
        }

        @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
        public void readSummaryFromParcelLocked(Parcel in) {
            mCount.set(in.readInt());
        }
    }

    @VisibleForTesting
    public static class LongSamplingCounterArray extends LongCounterArray implements TimeBaseObs {
        final TimeBase mTimeBase;
        public long[] mCounts;

        private LongSamplingCounterArray(TimeBase timeBase, Parcel in) {
            mTimeBase = timeBase;
            mCounts = in.createLongArray();
            timeBase.add(this);
        }

        public LongSamplingCounterArray(TimeBase timeBase) {
            mTimeBase = timeBase;
            timeBase.add(this);
        }

        private void writeToParcel(Parcel out) {
            out.writeLongArray(mCounts);
        }

        @Override
        public void onTimeStarted(long elapsedRealTime, long baseUptime, long baseRealtime) {
        }

        @Override
        public void onTimeStopped(long elapsedRealtime, long baseUptime, long baseRealtime) {
        }

        @Override
        public long[] getCountsLocked(int which) {
            return mCounts == null ? null : Arrays.copyOf(mCounts, mCounts.length);
        }

        @Override
        public void logState(Printer pw, String prefix) {
            pw.println(prefix + "mCounts=" + Arrays.toString(mCounts));
        }

        public void addCountLocked(long[] counts) {
            addCountLocked(counts, mTimeBase.isRunning());
        }

        public void addCountLocked(long[] counts, boolean isRunning) {
            if (counts == null) {
                return;
            }
            if (isRunning) {
                if (mCounts == null) {
                    mCounts = new long[counts.length];
                }
                for (int i = 0; i < counts.length; ++i) {
                    mCounts[i] += counts[i];
                }
            }
        }

        public int getSize() {
            return mCounts == null ? 0 : mCounts.length;
        }

        /**
         * Clear state of this counter.
         */
        @Override
        public boolean reset(boolean detachIfReset) {
            if (mCounts != null) {
                Arrays.fill(mCounts, 0);
            }
            if (detachIfReset) {
                detach();
            }
            return true;
        }

        @Override
        public void detach() {
            mTimeBase.remove(this);
        }

        private void writeSummaryToParcelLocked(Parcel out) {
            out.writeLongArray(mCounts);
        }

        private void readSummaryFromParcelLocked(Parcel in) {
            mCounts = in.createLongArray();
        }

        public static void writeToParcel(Parcel out, LongSamplingCounterArray counterArray) {
            if (counterArray != null) {
                out.writeInt(1);
                counterArray.writeToParcel(out);
            } else {
                out.writeInt(0);
            }
        }

        public static LongSamplingCounterArray readFromParcel(Parcel in, TimeBase timeBase) {
            if (in.readInt() != 0) {
                return new LongSamplingCounterArray(timeBase, in);
            } else {
                return null;
            }
        }

        public static void writeSummaryToParcelLocked(Parcel out,
                LongSamplingCounterArray counterArray) {
            if (counterArray != null) {
                out.writeInt(1);
                counterArray.writeSummaryToParcelLocked(out);
            } else {
                out.writeInt(0);
            }
        }

        public static LongSamplingCounterArray readSummaryFromParcelLocked(Parcel in,
                TimeBase timeBase) {
            if (in.readInt() != 0) {
                final LongSamplingCounterArray counterArray
                        = new LongSamplingCounterArray(timeBase);
                counterArray.readSummaryFromParcelLocked(in);
                return counterArray;
            } else {
                return null;
            }
        }
    }

    @VisibleForTesting
    public static class LongSamplingCounter extends LongCounter implements TimeBaseObs {
        final TimeBase mTimeBase;
        private long mCount;

        public LongSamplingCounter(TimeBase timeBase, Parcel in) {
            mTimeBase = timeBase;
            mCount = in.readLong();
            timeBase.add(this);
        }

        public LongSamplingCounter(TimeBase timeBase) {
            mTimeBase = timeBase;
            timeBase.add(this);
        }

        public void writeToParcel(Parcel out) {
            out.writeLong(mCount);
        }

        @Override
        public void onTimeStarted(long elapsedRealtime, long baseUptime, long baseRealtime) {
        }

        @Override
        public void onTimeStopped(long elapsedRealtime, long baseUptime, long baseRealtime) {
        }

        public long getCountLocked(int which) {
            return mCount;
        }

        @Override
        public void logState(Printer pw, String prefix) {
            pw.println(prefix + "mCount=" + mCount);
        }

        public void addCountLocked(long count) {
            addCountLocked(count, mTimeBase.isRunning());
        }

        public void addCountLocked(long count, boolean isRunning) {
            if (isRunning) {
                mCount += count;
            }
        }

        /**
         * Clear state of this counter.
         */
        @Override
        public boolean reset(boolean detachIfReset) {
            mCount = 0;
            if (detachIfReset) {
                detach();
            }
            return true;
        }

        @Override
        public void detach() {
            mTimeBase.remove(this);
        }

        public void writeSummaryFromParcelLocked(Parcel out) {
            out.writeLong(mCount);
        }

        public void readSummaryFromParcelLocked(Parcel in) {
            mCount = in.readLong();
        }
    }

    /**
     * State for keeping track of timing information.
     */
    public static abstract class Timer extends BatteryStats.Timer implements TimeBaseObs {
        protected final Clocks mClocks;
        protected final int mType;
        protected final TimeBase mTimeBase;

        protected int mCount;

        // Times are in microseconds for better accuracy when dividing by the
        // lock count, and are in "battery realtime" units.

        /**
         * The total time we have accumulated since the start of the original
         * boot, to the last time something interesting happened in the
         * current run.
         */
        protected long mTotalTime;

        /**
         * The total time this timer has been running until the latest mark has been set.
         * Subtract this from mTotalTime to get the time spent running since the mark was set.
         */
        protected long mTimeBeforeMark;

        /**
         * Constructs from a parcel.
         * @param type
         * @param timeBase
         * @param in
         */
        public Timer(Clocks clocks, int type, TimeBase timeBase, Parcel in) {
            mClocks = clocks;
            mType = type;
            mTimeBase = timeBase;

            mCount = in.readInt();
            mTotalTime = in.readLong();
            mTimeBeforeMark = in.readLong();
            timeBase.add(this);
            if (DEBUG) Log.i(TAG, "**** READ TIMER #" + mType + ": mTotalTime=" + mTotalTime);
        }

        public Timer(Clocks clocks, int type, TimeBase timeBase) {
            mClocks = clocks;
            mType = type;
            mTimeBase = timeBase;
            timeBase.add(this);
        }

        public void writeToParcel(Parcel out, long elapsedRealtimeUs) {
            if (DEBUG) {
                Log.i(TAG, "**** WRITING TIMER #" + mType + ": mTotalTime="
                        + computeRunTimeLocked(mTimeBase.getRealtime(elapsedRealtimeUs)));
            }
            out.writeInt(computeCurrentCountLocked());
            out.writeLong(computeRunTimeLocked(mTimeBase.getRealtime(elapsedRealtimeUs)));
            out.writeLong(mTimeBeforeMark);
        }

        protected abstract long computeRunTimeLocked(long curBatteryRealtime);

        protected abstract int computeCurrentCountLocked();

        /**
         * Clear state of this timer.  Returns true if the timer is inactive
         * so can be completely dropped.
         */
        @Override
        public boolean reset(boolean detachIfReset) {
            mTotalTime = mTimeBeforeMark = 0;
            mCount = 0;
            if (detachIfReset) {
                detach();
            }
            return true;
        }

        @Override
        public void detach() {
            mTimeBase.remove(this);
        }

        @Override
        public void onTimeStarted(long elapsedRealtime, long timeBaseUptime, long baseRealtime) {
        }

        @Override
        public void onTimeStopped(long elapsedRealtime, long baseUptime, long baseRealtime) {
            if (DEBUG && mType < 0) {
                Log.v(TAG, "plug #" + mType + ": realtime=" + baseRealtime
                        + " old mTotalTime=" + mTotalTime);
            }
            mTotalTime = computeRunTimeLocked(baseRealtime);
            mCount = computeCurrentCountLocked();
            if (DEBUG && mType < 0) {
                Log.v(TAG, "plug #" + mType + ": new mTotalTime=" + mTotalTime);
            }
        }

        /**
         * Writes a possibly null Timer to a Parcel.
         *
         * @param out the Parcel to be written to.
         * @param timer a Timer, or null.
         */
        @UnsupportedAppUsage
        public static void writeTimerToParcel(Parcel out, Timer timer, long elapsedRealtimeUs) {
            if (timer == null) {
                out.writeInt(0); // indicates null
                return;
            }
            out.writeInt(1); // indicates non-null
            timer.writeToParcel(out, elapsedRealtimeUs);
        }

        @Override
        @UnsupportedAppUsage
        public long getTotalTimeLocked(long elapsedRealtimeUs, int which) {
            return computeRunTimeLocked(mTimeBase.getRealtime(elapsedRealtimeUs));
        }

        @Override
        @UnsupportedAppUsage
        public int getCountLocked(int which) {
            return computeCurrentCountLocked();
        }

        @Override
        public long getTimeSinceMarkLocked(long elapsedRealtimeUs) {
            long val = computeRunTimeLocked(mTimeBase.getRealtime(elapsedRealtimeUs));
            return val - mTimeBeforeMark;
        }

        @Override
        public void logState(Printer pw, String prefix) {
            pw.println(prefix + "mCount=" + mCount);
            pw.println(prefix + "mTotalTime=" + mTotalTime);
        }


        public void writeSummaryFromParcelLocked(Parcel out, long elapsedRealtimeUs) {
            long runTime = computeRunTimeLocked(mTimeBase.getRealtime(elapsedRealtimeUs));
            out.writeLong(runTime);
            out.writeInt(computeCurrentCountLocked());
        }

        public void readSummaryFromParcelLocked(Parcel in) {
            // Multiply by 1000 for backwards compatibility
            mTotalTime = in.readLong();
            mCount = in.readInt();
            // When reading the summary, we set the mark to be the latest information.
            mTimeBeforeMark = mTotalTime;
        }
    }

    /**
     * A counter meant to accept monotonically increasing values to its {@link #update(long, int)}
     * method. The state of the timer according to its {@link TimeBase} will determine how much
     * of the value is recorded.
     *
     * If the value being recorded resets, {@link #endSample()} can be called in order to
     * account for the change. If the value passed in to {@link #update(long, int)} decreased
     * between calls, the {@link #endSample()} is automatically called and the new value is
     * expected to increase monotonically from that point on.
     */
    public static class SamplingTimer extends Timer {

        /**
         * The most recent reported count from /proc/wakelocks.
         */
        int mCurrentReportedCount;

        /**
         * The reported count from /proc/wakelocks when unplug() was last
         * called.
         */
        int mUnpluggedReportedCount;

        /**
         * The most recent reported total_time from /proc/wakelocks.
         */
        long mCurrentReportedTotalTime;


        /**
         * The reported total_time from /proc/wakelocks when unplug() was last
         * called.
         */
        long mUnpluggedReportedTotalTime;

        /**
         * Whether we are currently in a discharge cycle.
         */
        boolean mTimeBaseRunning;

        /**
         * Whether we are currently recording reported values.
         */
        boolean mTrackingReportedValues;

        /*
         * A sequence counter, incremented once for each update of the stats.
         */
        int mUpdateVersion;

        @VisibleForTesting
        public SamplingTimer(Clocks clocks, TimeBase timeBase, Parcel in) {
            super(clocks, 0, timeBase, in);
            mCurrentReportedCount = in.readInt();
            mUnpluggedReportedCount = in.readInt();
            mCurrentReportedTotalTime = in.readLong();
            mUnpluggedReportedTotalTime = in.readLong();
            mTrackingReportedValues = in.readInt() == 1;
            mTimeBaseRunning = timeBase.isRunning();
        }

        @VisibleForTesting
        public SamplingTimer(Clocks clocks, TimeBase timeBase) {
            super(clocks, 0, timeBase);
            mTrackingReportedValues = false;
            mTimeBaseRunning = timeBase.isRunning();
        }

        /**
         * Ends the current sample, allowing subsequent values to {@link #update(long, int)} to
         * be less than the values used for a previous invocation.
         */
        public void endSample() {
            mTotalTime = computeRunTimeLocked(0 /* unused by us */);
            mCount = computeCurrentCountLocked();
            mUnpluggedReportedTotalTime = mCurrentReportedTotalTime = 0;
            mUnpluggedReportedCount = mCurrentReportedCount = 0;
        }

        public void setUpdateVersion(int version) {
            mUpdateVersion = version;
        }

        public int getUpdateVersion() {
            return mUpdateVersion;
        }

        /**
         * Updates the current recorded values. These are meant to be monotonically increasing
         * and cumulative. If you are dealing with deltas, use {@link #add(long, int)}.
         *
         * If the values being recorded have been reset, the monotonically increasing requirement
         * will be broken. In this case, {@link #endSample()} is automatically called and
         * the total value of totalTime and count are recorded, starting a new monotonically
         * increasing sample.
         *
         * @param totalTime total time of sample in microseconds.
         * @param count total number of times the event being sampled occurred.
         */
        public void update(long totalTime, int count) {
            if (mTimeBaseRunning && !mTrackingReportedValues) {
                // Updating the reported value for the first time.
                mUnpluggedReportedTotalTime = totalTime;
                mUnpluggedReportedCount = count;
            }

            mTrackingReportedValues = true;

            if (totalTime < mCurrentReportedTotalTime || count < mCurrentReportedCount) {
                endSample();
            }

            mCurrentReportedTotalTime = totalTime;
            mCurrentReportedCount = count;
        }

        /**
         * Adds deltaTime and deltaCount to the current sample.
         *
         * @param deltaTime additional time recorded since the last sampled event, in microseconds.
         * @param deltaCount additional number of times the event being sampled occurred.
         */
        public void add(long deltaTime, int deltaCount) {
            update(mCurrentReportedTotalTime + deltaTime, mCurrentReportedCount + deltaCount);
        }

        @Override
        public void onTimeStarted(long elapsedRealtime, long baseUptime, long baseRealtime) {
            super.onTimeStarted(elapsedRealtime, baseUptime, baseRealtime);
            if (mTrackingReportedValues) {
                mUnpluggedReportedTotalTime = mCurrentReportedTotalTime;
                mUnpluggedReportedCount = mCurrentReportedCount;
            }
            mTimeBaseRunning = true;
        }

        @Override
        public void onTimeStopped(long elapsedRealtime, long baseUptime, long baseRealtime) {
            super.onTimeStopped(elapsedRealtime, baseUptime, baseRealtime);
            mTimeBaseRunning = false;
        }

        @Override
        public void logState(Printer pw, String prefix) {
            super.logState(pw, prefix);
            pw.println(prefix + "mCurrentReportedCount=" + mCurrentReportedCount
                    + " mUnpluggedReportedCount=" + mUnpluggedReportedCount
                    + " mCurrentReportedTotalTime=" + mCurrentReportedTotalTime
                    + " mUnpluggedReportedTotalTime=" + mUnpluggedReportedTotalTime);
        }

        @Override
        protected long computeRunTimeLocked(long curBatteryRealtime) {
            return mTotalTime + (mTimeBaseRunning && mTrackingReportedValues
                    ? mCurrentReportedTotalTime - mUnpluggedReportedTotalTime : 0);
        }

        @Override
        protected int computeCurrentCountLocked() {
            return mCount + (mTimeBaseRunning && mTrackingReportedValues
                    ? mCurrentReportedCount - mUnpluggedReportedCount : 0);
        }

        @Override
        public void writeToParcel(Parcel out, long elapsedRealtimeUs) {
            super.writeToParcel(out, elapsedRealtimeUs);
            out.writeInt(mCurrentReportedCount);
            out.writeInt(mUnpluggedReportedCount);
            out.writeLong(mCurrentReportedTotalTime);
            out.writeLong(mUnpluggedReportedTotalTime);
            out.writeInt(mTrackingReportedValues ? 1 : 0);
        }

        @Override
        public boolean reset(boolean detachIfReset) {
            super.reset(detachIfReset);
            mTrackingReportedValues = false;
            mUnpluggedReportedTotalTime = 0;
            mUnpluggedReportedCount = 0;
            return true;
        }
    }

    /**
     * A timer that increments in batches.  It does not run for durations, but just jumps
     * for a pre-determined amount.
     */
    public static class BatchTimer extends Timer {
        final Uid mUid;

        /**
         * The last time at which we updated the timer.  This is in elapsed realtime microseconds.
         */
        long mLastAddedTime;

        /**
         * The last duration that we added to the timer.  This is in microseconds.
         */
        long mLastAddedDuration;

        /**
         * Whether we are currently in a discharge cycle.
         */
        boolean mInDischarge;

        BatchTimer(Clocks clocks, Uid uid, int type, TimeBase timeBase, Parcel in) {
            super(clocks, type, timeBase, in);
            mUid = uid;
            mLastAddedTime = in.readLong();
            mLastAddedDuration = in.readLong();
            mInDischarge = timeBase.isRunning();
        }

        BatchTimer(Clocks clocks, Uid uid, int type, TimeBase timeBase) {
            super(clocks, type, timeBase);
            mUid = uid;
            mInDischarge = timeBase.isRunning();
        }

        @Override
        public void writeToParcel(Parcel out, long elapsedRealtimeUs) {
            super.writeToParcel(out, elapsedRealtimeUs);
            out.writeLong(mLastAddedTime);
            out.writeLong(mLastAddedDuration);
        }

        @Override
        public void onTimeStopped(long elapsedRealtime, long baseUptime, long baseRealtime) {
            recomputeLastDuration(mClocks.elapsedRealtime() * 1000, false);
            mInDischarge = false;
            super.onTimeStopped(elapsedRealtime, baseUptime, baseRealtime);
        }

        @Override
        public void onTimeStarted(long elapsedRealtime, long baseUptime, long baseRealtime) {
            recomputeLastDuration(elapsedRealtime, false);
            mInDischarge = true;
            // If we are still within the last added duration, then re-added whatever remains.
            if (mLastAddedTime == elapsedRealtime) {
                mTotalTime += mLastAddedDuration;
            }
            super.onTimeStarted(elapsedRealtime, baseUptime, baseRealtime);
        }

        @Override
        public void logState(Printer pw, String prefix) {
            super.logState(pw, prefix);
            pw.println(prefix + "mLastAddedTime=" + mLastAddedTime
                    + " mLastAddedDuration=" + mLastAddedDuration);
        }

        private long computeOverage(long curTime) {
            if (mLastAddedTime > 0) {
                return mLastAddedDuration - curTime;
            }
            return 0;
        }

        private void recomputeLastDuration(long curTime, boolean abort) {
            final long overage = computeOverage(curTime);
            if (overage > 0) {
                // Aborting before the duration ran out -- roll back the remaining
                // duration.  Only do this if currently discharging; otherwise we didn't
                // actually add the time.
                if (mInDischarge) {
                    mTotalTime -= overage;
                }
                if (abort) {
                    mLastAddedTime = 0;
                } else {
                    mLastAddedTime = curTime;
                    mLastAddedDuration -= overage;
                }
            }
        }

        public void addDuration(BatteryStatsImpl stats, long durationMillis) {
            final long now = mClocks.elapsedRealtime() * 1000;
            recomputeLastDuration(now, true);
            mLastAddedTime = now;
            mLastAddedDuration = durationMillis * 1000;
            if (mInDischarge) {
                mTotalTime += mLastAddedDuration;
                mCount++;
            }
        }

        public void abortLastDuration(BatteryStatsImpl stats) {
            final long now = mClocks.elapsedRealtime() * 1000;
            recomputeLastDuration(now, true);
        }

        @Override
        protected int computeCurrentCountLocked() {
            return mCount;
        }

        @Override
        protected long computeRunTimeLocked(long curBatteryRealtime) {
            final long overage = computeOverage(mClocks.elapsedRealtime() * 1000);
            if (overage > 0) {
                return mTotalTime = overage;
            }
            return mTotalTime;
        }

        @Override
        public boolean reset(boolean detachIfReset) {
            final long now = mClocks.elapsedRealtime() * 1000;
            recomputeLastDuration(now, true);
            boolean stillActive = mLastAddedTime == now;
            super.reset(!stillActive && detachIfReset);
            return !stillActive;
        }
    }


    /**
     * A StopwatchTimer that also tracks the total and max individual
     * time spent active according to the given timebase.  Whereas
     * StopwatchTimer apportions the time amongst all in the pool,
     * the total and max durations are not apportioned.
     */
    public static class DurationTimer extends StopwatchTimer {
        /**
         * The time (in ms) that the timer was last acquired or the time base
         * last (re-)started. Increasing the nesting depth does not reset this time.
         *
         * -1 if the timer is currently not running or the time base is not running.
         *
         * If written to a parcel, the start time is reset, as is mNesting in the base class
         * StopwatchTimer.
         */
        long mStartTimeMs = -1;

        /**
         * The longest time period (in ms) that the timer has been active. Not pooled.
         */
        long mMaxDurationMs;

        /**
         * The time (in ms) that that the timer has been active since most recent
         * stopRunningLocked() or reset(). Not pooled.
         */
        long mCurrentDurationMs;

        /**
         * The total time (in ms) that that the timer has been active since most recent reset()
         * prior to the current startRunningLocked. This is the sum of all past currentDurations
         * (but not including the present currentDuration) since reset. Not pooled.
         */
        long mTotalDurationMs;

        public DurationTimer(Clocks clocks, Uid uid, int type, ArrayList<StopwatchTimer> timerPool,
                TimeBase timeBase, Parcel in) {
            super(clocks, uid, type, timerPool, timeBase, in);
            mMaxDurationMs = in.readLong();
            mTotalDurationMs = in.readLong();
            mCurrentDurationMs = in.readLong();
        }

        public DurationTimer(Clocks clocks, Uid uid, int type, ArrayList<StopwatchTimer> timerPool,
                TimeBase timeBase) {
            super(clocks, uid, type, timerPool, timeBase);
        }

        @Override
        public void writeToParcel(Parcel out, long elapsedRealtimeUs) {
            super.writeToParcel(out, elapsedRealtimeUs);
            out.writeLong(getMaxDurationMsLocked(elapsedRealtimeUs / 1000));
            out.writeLong(mTotalDurationMs);
            out.writeLong(getCurrentDurationMsLocked(elapsedRealtimeUs / 1000));
        }

        /**
         * Write the summary to the parcel.
         *
         * Since the time base is probably meaningless after we come back, reading
         * from this will have the effect of stopping the timer. So here all we write
         * is the max and total durations.
         */
        @Override
        public void writeSummaryFromParcelLocked(Parcel out, long elapsedRealtimeUs) {
            super.writeSummaryFromParcelLocked(out, elapsedRealtimeUs);
            out.writeLong(getMaxDurationMsLocked(elapsedRealtimeUs / 1000));
            out.writeLong(getTotalDurationMsLocked(elapsedRealtimeUs / 1000));
        }

        /**
         * Read the summary parcel.
         *
         * Has the side effect of stopping the timer.
         */
        @Override
        public void readSummaryFromParcelLocked(Parcel in) {
            super.readSummaryFromParcelLocked(in);
            mMaxDurationMs = in.readLong();
            mTotalDurationMs = in.readLong();
            mStartTimeMs = -1;
            mCurrentDurationMs = 0;
        }

        /**
         * The TimeBase time started (again).
         *
         * If the timer is also running, store the start time.
         */
        public void onTimeStarted(long elapsedRealtimeUs, long baseUptime, long baseRealtime) {
            super.onTimeStarted(elapsedRealtimeUs, baseUptime, baseRealtime);
            if (mNesting > 0) {
                mStartTimeMs = baseRealtime / 1000;
            }
        }

        /**
         * The TimeBase stopped running.
         *
         * If the timer is running, add the duration into mCurrentDurationMs.
         */
        @Override
        public void onTimeStopped(long elapsedRealtimeUs, long baseUptime, long baseRealtimeUs) {
            super.onTimeStopped(elapsedRealtimeUs, baseUptime, baseRealtimeUs);
            if (mNesting > 0) {
                // baseRealtimeUs has already been converted to the timebase's realtime.
                mCurrentDurationMs += (baseRealtimeUs / 1000) - mStartTimeMs;
            }
            mStartTimeMs = -1;
        }

        @Override
        public void logState(Printer pw, String prefix) {
            super.logState(pw, prefix);
        }

        @Override
        public void startRunningLocked(long elapsedRealtimeMs) {
            super.startRunningLocked(elapsedRealtimeMs);
            if (mNesting == 1 && mTimeBase.isRunning()) {
                // Just started
                mStartTimeMs = mTimeBase.getRealtime(elapsedRealtimeMs * 1000) / 1000;
            }
        }

        /**
         * Decrements the mNesting ref-count on this timer.
         *
         * If it actually stopped (mNesting went to 0), then possibly update
         * mMaxDuration if the current duration was the longest ever.
         */
        @Override
        public void stopRunningLocked(long elapsedRealtimeMs) {
            if (mNesting == 1) {
                final long durationMs = getCurrentDurationMsLocked(elapsedRealtimeMs);
                mTotalDurationMs += durationMs;
                if (durationMs > mMaxDurationMs) {
                    mMaxDurationMs = durationMs;
                }
                mStartTimeMs = -1;
                mCurrentDurationMs = 0;
            }
            // super method decrements mNesting, which getCurrentDurationMsLocked relies on,
            // so call super.stopRunningLocked after calling getCurrentDurationMsLocked.
            super.stopRunningLocked(elapsedRealtimeMs);
        }

        @Override
        public boolean reset(boolean detachIfReset) {
            boolean result = super.reset(detachIfReset);
            mMaxDurationMs = 0;
            mTotalDurationMs = 0;
            mCurrentDurationMs = 0;
            if (mNesting > 0) {
                mStartTimeMs = mTimeBase.getRealtime(mClocks.elapsedRealtime() * 1000) / 1000;
            } else {
                mStartTimeMs = -1;
            }
            return result;
        }

        /**
         * Returns the max duration that this timer has ever seen.
         *
         * Note that this time is NOT split between the timers in the timer group that
         * this timer is attached to.  It is the TOTAL time.
         */
        @Override
        public long getMaxDurationMsLocked(long elapsedRealtimeMs) {
            if (mNesting > 0) {
                final long durationMs = getCurrentDurationMsLocked(elapsedRealtimeMs);
                if (durationMs > mMaxDurationMs) {
                    return durationMs;
                }
            }
            return mMaxDurationMs;
        }

        /**
         * Returns the time since the timer was started.
         * Returns 0 if the timer is not currently running.
         *
         * Note that this time is NOT split between the timers in the timer group that
         * this timer is attached to.  It is the TOTAL time.
         *
         * Note that if running timer is parceled and unparceled, this method will return
         * current duration value at the time of parceling even though timer may not be
         * currently running.
         */
        @Override
        public long getCurrentDurationMsLocked(long elapsedRealtimeMs) {
            long durationMs = mCurrentDurationMs;
            if (mNesting > 0 && mTimeBase.isRunning()) {
                durationMs += (mTimeBase.getRealtime(elapsedRealtimeMs * 1000) / 1000)
                        - mStartTimeMs;
            }
            return durationMs;
        }

        /**
         * Returns the total cumulative duration that this timer has been on since reset().
         * If mTimerPool == null, this should be the same
         * as getTotalTimeLocked(elapsedRealtimeMs*1000, STATS_SINCE_CHARGED)/1000.
         *
         * Note that this time is NOT split between the timers in the timer group that
         * this timer is attached to.  It is the TOTAL time. For this reason, if mTimerPool != null,
         * the result will not be equivalent to getTotalTimeLocked.
         */
        @Override
        public long getTotalDurationMsLocked(long elapsedRealtimeMs) {
            return mTotalDurationMs + getCurrentDurationMsLocked(elapsedRealtimeMs);
        }
    }

    /**
     * State for keeping track of timing information.
     */
    public static class StopwatchTimer extends Timer {
        final Uid mUid;
        final ArrayList<StopwatchTimer> mTimerPool;

        int mNesting;

        /**
         * The last time at which we updated the timer.  If mNesting is > 0,
         * subtract this from the current battery time to find the amount of
         * time we have been running since we last computed an update.
         */
        long mUpdateTime;

        /**
         * The total time at which the timer was acquired, to determine if it
         * was actually held for an interesting duration. If time base was not running when timer
         * was acquired, will be -1.
         */
        long mAcquireTime = -1;

        long mTimeout;

        /**
         * For partial wake locks, keep track of whether we are in the list
         * to consume CPU cycles.
         */
        @VisibleForTesting
        public boolean mInList;

        public StopwatchTimer(Clocks clocks, Uid uid, int type, ArrayList<StopwatchTimer> timerPool,
                TimeBase timeBase, Parcel in) {
            super(clocks, type, timeBase, in);
            mUid = uid;
            mTimerPool = timerPool;
            mUpdateTime = in.readLong();
        }

        public StopwatchTimer(Clocks clocks, Uid uid, int type, ArrayList<StopwatchTimer> timerPool,
                TimeBase timeBase) {
            super(clocks, type, timeBase);
            mUid = uid;
            mTimerPool = timerPool;
        }

        public void setTimeout(long timeout) {
            mTimeout = timeout;
        }

        public void writeToParcel(Parcel out, long elapsedRealtimeUs) {
            super.writeToParcel(out, elapsedRealtimeUs);
            out.writeLong(mUpdateTime);
        }

        public void onTimeStopped(long elapsedRealtime, long baseUptime, long baseRealtime) {
            if (mNesting > 0) {
                if (DEBUG && mType < 0) {
                    Log.v(TAG, "old mUpdateTime=" + mUpdateTime);
                }
                super.onTimeStopped(elapsedRealtime, baseUptime, baseRealtime);
                mUpdateTime = baseRealtime;
                if (DEBUG && mType < 0) {
                    Log.v(TAG, "new mUpdateTime=" + mUpdateTime);
                }
            }
        }

        public void logState(Printer pw, String prefix) {
            super.logState(pw, prefix);
            pw.println(prefix + "mNesting=" + mNesting + " mUpdateTime=" + mUpdateTime
                    + " mAcquireTime=" + mAcquireTime);
        }

        public void startRunningLocked(long elapsedRealtimeMs) {
            if (mNesting++ == 0) {
                final long batteryRealtime = mTimeBase.getRealtime(elapsedRealtimeMs * 1000);
                mUpdateTime = batteryRealtime;
                if (mTimerPool != null) {
                    // Accumulate time to all currently active timers before adding
                    // this new one to the pool.
                    refreshTimersLocked(batteryRealtime, mTimerPool, null);
                    // Add this timer to the active pool
                    mTimerPool.add(this);
                }
                if (mTimeBase.isRunning()) {
                    // Increment the count
                    mCount++;
                    mAcquireTime = mTotalTime;
                } else {
                    mAcquireTime = -1;
                }
                if (DEBUG && mType < 0) {
                    Log.v(TAG, "start #" + mType + ": mUpdateTime=" + mUpdateTime
                            + " mTotalTime=" + mTotalTime + " mCount=" + mCount
                            + " mAcquireTime=" + mAcquireTime);
                }
            }
        }

        public boolean isRunningLocked() {
            return mNesting > 0;
        }

        public void stopRunningLocked(long elapsedRealtimeMs) {
            // Ignore attempt to stop a timer that isn't running
            if (mNesting == 0) {
                return;
            }
            if (--mNesting == 0) {
                final long batteryRealtime = mTimeBase.getRealtime(elapsedRealtimeMs * 1000);
                if (mTimerPool != null) {
                    // Accumulate time to all active counters, scaled by the total
                    // active in the pool, before taking this one out of the pool.
                    refreshTimersLocked(batteryRealtime, mTimerPool, null);
                    // Remove this timer from the active pool
                    mTimerPool.remove(this);
                } else {
                    mNesting = 1;
                    mTotalTime = computeRunTimeLocked(batteryRealtime);
                    mNesting = 0;
                }

                if (DEBUG && mType < 0) {
                    Log.v(TAG, "stop #" + mType + ": mUpdateTime=" + mUpdateTime
                            + " mTotalTime=" + mTotalTime + " mCount=" + mCount
                            + " mAcquireTime=" + mAcquireTime);
                }

                if (mAcquireTime >= 0 && mTotalTime == mAcquireTime) {
                    // If there was no change in the time, then discard this
                    // count.  A somewhat cheezy strategy, but hey.
                    mCount--;
                }
            }
        }

        public void stopAllRunningLocked(long elapsedRealtimeMs) {
            if (mNesting > 0) {
                mNesting = 1;
                stopRunningLocked(elapsedRealtimeMs);
            }
        }

        // Update the total time for all other running Timers with the same type as this Timer
        // due to a change in timer count
        private static long refreshTimersLocked(long batteryRealtime,
                final ArrayList<StopwatchTimer> pool, StopwatchTimer self) {
            long selfTime = 0;
            final int N = pool.size();
            for (int i=N-1; i>= 0; i--) {
                final StopwatchTimer t = pool.get(i);
                long heldTime = batteryRealtime - t.mUpdateTime;
                if (heldTime > 0) {
                    final long myTime = heldTime / N;
                    if (t == self) {
                        selfTime = myTime;
                    }
                    t.mTotalTime += myTime;
                }
                t.mUpdateTime = batteryRealtime;
            }
            return selfTime;
        }

        @Override
        protected long computeRunTimeLocked(long curBatteryRealtime) {
            if (mTimeout > 0 && curBatteryRealtime > mUpdateTime + mTimeout) {
                curBatteryRealtime = mUpdateTime + mTimeout;
            }
            return mTotalTime + (mNesting > 0
                    ? (curBatteryRealtime - mUpdateTime)
                            / (mTimerPool != null ? mTimerPool.size() : 1)
                    : 0);
        }

        @Override
        protected int computeCurrentCountLocked() {
            return mCount;
        }

        @Override
        public boolean reset(boolean detachIfReset) {
            boolean canDetach = mNesting <= 0;
            super.reset(canDetach && detachIfReset);
            if (mNesting > 0) {
                mUpdateTime = mTimeBase.getRealtime(mClocks.elapsedRealtime() * 1000);
            }
            mAcquireTime = -1; // to ensure mCount isn't decreased to -1 if timer is stopped later.
            return canDetach;
        }

        @Override
        @UnsupportedAppUsage
        public void detach() {
            super.detach();
            if (mTimerPool != null) {
                mTimerPool.remove(this);
            }
        }

        @Override
        public void readSummaryFromParcelLocked(Parcel in) {
            super.readSummaryFromParcelLocked(in);
            mNesting = 0;
        }

        /**
         * Set the mark so that we can query later for the total time the timer has
         * accumulated since this point. The timer can be running or not.
         *
         * @param elapsedRealtimeMs the current elapsed realtime in milliseconds.
         */
        public void setMark(long elapsedRealtimeMs) {
            final long batteryRealtime = mTimeBase.getRealtime(elapsedRealtimeMs * 1000);
            if (mNesting > 0) {
                // We are running.
                if (mTimerPool != null) {
                    refreshTimersLocked(batteryRealtime, mTimerPool, this);
                } else {
                    mTotalTime += batteryRealtime - mUpdateTime;
                    mUpdateTime = batteryRealtime;
                }
            }
            mTimeBeforeMark = mTotalTime;
        }
    }

    /**
     * State for keeping track of two DurationTimers with different TimeBases, presumably where one
     * TimeBase is effectively a subset of the other.
     */
    public static class DualTimer extends DurationTimer {
        // This class both is a DurationTimer and also holds a second DurationTimer.
        // The main timer (this) typically tracks the total time. It may be pooled (but since it's a
        // durationTimer, it also has the unpooled getTotalDurationMsLocked() for
        // STATS_SINCE_CHARGED).
        // mSubTimer typically tracks only part of the total time, such as background time, as
        // determined by a subTimeBase. It is NOT pooled.
        private final DurationTimer mSubTimer;

        /**
         * Creates a DualTimer to hold a main timer (this) and a mSubTimer.
         * The main timer (this) is based on the given timeBase and timerPool.
         * The mSubTimer is based on the given subTimeBase. The mSubTimer is not pooled, even if
         * the main timer is.
         */
        public DualTimer(Clocks clocks, Uid uid, int type, ArrayList<StopwatchTimer> timerPool,
                TimeBase timeBase, TimeBase subTimeBase, Parcel in) {
            super(clocks, uid, type, timerPool, timeBase, in);
            mSubTimer = new DurationTimer(clocks, uid, type, null, subTimeBase, in);
        }

        /**
         * Creates a DualTimer to hold a main timer (this) and a mSubTimer.
         * The main timer (this) is based on the given timeBase and timerPool.
         * The mSubTimer is based on the given subTimeBase. The mSubTimer is not pooled, even if
         * the main timer is.
         */
        public DualTimer(Clocks clocks, Uid uid, int type, ArrayList<StopwatchTimer> timerPool,
                TimeBase timeBase, TimeBase subTimeBase) {
            super(clocks, uid, type, timerPool, timeBase);
            mSubTimer = new DurationTimer(clocks, uid, type, null, subTimeBase);
        }

        /** Get the secondary timer. */
        @Override
        public DurationTimer getSubTimer() {
            return mSubTimer;
        }

        @Override
        public void startRunningLocked(long elapsedRealtimeMs) {
            super.startRunningLocked(elapsedRealtimeMs);
            mSubTimer.startRunningLocked(elapsedRealtimeMs);
        }

        @Override
        public void stopRunningLocked(long elapsedRealtimeMs) {
            super.stopRunningLocked(elapsedRealtimeMs);
            mSubTimer.stopRunningLocked(elapsedRealtimeMs);
        }

        @Override
        public void stopAllRunningLocked(long elapsedRealtimeMs) {
            super.stopAllRunningLocked(elapsedRealtimeMs);
            mSubTimer.stopAllRunningLocked(elapsedRealtimeMs);
        }

        @Override
        public boolean reset(boolean detachIfReset) {
            boolean active = false;
            // Do not detach the subTimer explicitly since that'll be done by DualTimer.detach().
            active |= !mSubTimer.reset(false);
            active |= !super.reset(detachIfReset);
            return !active;
        }

        @Override
        public void detach() {
            mSubTimer.detach();
            super.detach();
        }

        @Override
        public void writeToParcel(Parcel out, long elapsedRealtimeUs) {
            super.writeToParcel(out, elapsedRealtimeUs);
            mSubTimer.writeToParcel(out, elapsedRealtimeUs);
        }

        @Override
        public void writeSummaryFromParcelLocked(Parcel out, long elapsedRealtimeUs) {
            super.writeSummaryFromParcelLocked(out, elapsedRealtimeUs);
            mSubTimer.writeSummaryFromParcelLocked(out, elapsedRealtimeUs);
        }

        @Override
        public void readSummaryFromParcelLocked(Parcel in) {
            super.readSummaryFromParcelLocked(in);
            mSubTimer.readSummaryFromParcelLocked(in);
        }
    }


    public abstract class OverflowArrayMap<T> {
        private static final String OVERFLOW_NAME = "*overflow*";

        final int mUid;
        final ArrayMap<String, T> mMap = new ArrayMap<>();
        T mCurOverflow;
        ArrayMap<String, MutableInt> mActiveOverflow;
        long mLastOverflowTime;
        long mLastOverflowFinishTime;
        long mLastClearTime;
        long mLastCleanupTime;

        public OverflowArrayMap(int uid) {
            mUid = uid;
        }

        public ArrayMap<String, T> getMap() {
            return mMap;
        }

        public void clear() {
            mLastClearTime = SystemClock.elapsedRealtime();
            mMap.clear();
            mCurOverflow = null;
            mActiveOverflow = null;
        }

        public void add(String name, T obj) {
            if (name == null) {
                name = "";
            }
            mMap.put(name, obj);
            if (OVERFLOW_NAME.equals(name)) {
                mCurOverflow = obj;
            }
        }

        public void cleanup() {
            mLastCleanupTime = SystemClock.elapsedRealtime();
            if (mActiveOverflow != null) {
                if (mActiveOverflow.size() == 0) {
                    mActiveOverflow = null;
                }
            }
            if (mActiveOverflow == null) {
                // There is no currently active overflow, so we should no longer have
                // an overflow entry.
                if (mMap.containsKey(OVERFLOW_NAME)) {
                    Slog.wtf(TAG, "Cleaning up with no active overflow, but have overflow entry "
                            + mMap.get(OVERFLOW_NAME));
                    mMap.remove(OVERFLOW_NAME);
                }
                mCurOverflow = null;
            } else {
                // There is currently active overflow, so we should still have an overflow entry.
                if (mCurOverflow == null || !mMap.containsKey(OVERFLOW_NAME)) {
                    Slog.wtf(TAG, "Cleaning up with active overflow, but no overflow entry: cur="
                            + mCurOverflow + " map=" + mMap.get(OVERFLOW_NAME));
                }
            }
        }

        public T startObject(String name) {
            if (name == null) {
                name = "";
            }
            T obj = mMap.get(name);
            if (obj != null) {
                return obj;
            }

            // No object exists for the given name, but do we currently have it
            // running as part of the overflow?
            if (mActiveOverflow != null) {
                MutableInt over = mActiveOverflow.get(name);
                if (over != null) {
                    // We are already actively counting this name in the overflow object.
                    obj = mCurOverflow;
                    if (obj == null) {
                        // Shouldn't be here, but we'll try to recover.
                        Slog.wtf(TAG, "Have active overflow " + name + " but null overflow");
                        obj = mCurOverflow = instantiateObject();
                        mMap.put(OVERFLOW_NAME, obj);
                    }
                    over.value++;
                    return obj;
                }
            }

            // No object exists for given name nor in the overflow; we need to make
            // a new one.
            final int N = mMap.size();
            if (N >= MAX_WAKELOCKS_PER_UID) {
                // Went over the limit on number of objects to track; this one goes
                // in to the overflow.
                obj = mCurOverflow;
                if (obj == null) {
                    // Need to start overflow now...
                    obj = mCurOverflow = instantiateObject();
                    mMap.put(OVERFLOW_NAME, obj);
                }
                if (mActiveOverflow == null) {
                    mActiveOverflow = new ArrayMap<>();
                }
                mActiveOverflow.put(name, new MutableInt(1));
                mLastOverflowTime = SystemClock.elapsedRealtime();
                return obj;
            }

            // Normal case where we just need to make a new object.
            obj = instantiateObject();
            mMap.put(name, obj);
            return obj;
        }

        public T stopObject(String name) {
            if (name == null) {
                name = "";
            }
            T obj = mMap.get(name);
            if (obj != null) {
                return obj;
            }

            // No object exists for the given name, but do we currently have it
            // running as part of the overflow?
            if (mActiveOverflow != null) {
                MutableInt over = mActiveOverflow.get(name);
                if (over != null) {
                    // We are already actively counting this name in the overflow object.
                    obj = mCurOverflow;
                    if (obj != null) {
                        over.value--;
                        if (over.value <= 0) {
                            mActiveOverflow.remove(name);
                            mLastOverflowFinishTime = SystemClock.elapsedRealtime();
                        }
                        return obj;
                    }
                }
            }

            // Huh, they are stopping an active operation but we can't find one!
            // That's not good.
            StringBuilder sb = new StringBuilder();
            sb.append("Unable to find object for ");
            sb.append(name);
            sb.append(" in uid ");
            sb.append(mUid);
            sb.append(" mapsize=");
            sb.append(mMap.size());
            sb.append(" activeoverflow=");
            sb.append(mActiveOverflow);
            sb.append(" curoverflow=");
            sb.append(mCurOverflow);
            long now = SystemClock.elapsedRealtime();
            if (mLastOverflowTime != 0) {
                sb.append(" lastOverflowTime=");
                TimeUtils.formatDuration(mLastOverflowTime-now, sb);
            }
            if (mLastOverflowFinishTime != 0) {
                sb.append(" lastOverflowFinishTime=");
                TimeUtils.formatDuration(mLastOverflowFinishTime-now, sb);
            }
            if (mLastClearTime != 0) {
                sb.append(" lastClearTime=");
                TimeUtils.formatDuration(mLastClearTime-now, sb);
            }
            if (mLastCleanupTime != 0) {
                sb.append(" lastCleanupTime=");
                TimeUtils.formatDuration(mLastCleanupTime-now, sb);
            }
            Slog.wtf(TAG, sb.toString());
            return null;
        }

        public abstract T instantiateObject();
    }

    public static class ControllerActivityCounterImpl extends ControllerActivityCounter
            implements Parcelable {
        private final LongSamplingCounter mIdleTimeMillis;
        private final LongSamplingCounter mScanTimeMillis;
        private final LongSamplingCounter mSleepTimeMillis;
        private final LongSamplingCounter mRxTimeMillis;
        private final LongSamplingCounter[] mTxTimeMillis;
        private final LongSamplingCounter mPowerDrainMaMs;
        private final LongSamplingCounter mMonitoredRailChargeConsumedMaMs;

        public ControllerActivityCounterImpl(TimeBase timeBase, int numTxStates) {
            mIdleTimeMillis = new LongSamplingCounter(timeBase);
            mScanTimeMillis = new LongSamplingCounter(timeBase);
            mSleepTimeMillis = new LongSamplingCounter(timeBase);
            mRxTimeMillis = new LongSamplingCounter(timeBase);
            mTxTimeMillis = new LongSamplingCounter[numTxStates];
            for (int i = 0; i < numTxStates; i++) {
                mTxTimeMillis[i] = new LongSamplingCounter(timeBase);
            }
            mPowerDrainMaMs = new LongSamplingCounter(timeBase);
            mMonitoredRailChargeConsumedMaMs = new LongSamplingCounter(timeBase);
        }

        public ControllerActivityCounterImpl(TimeBase timeBase, int numTxStates, Parcel in) {
            mIdleTimeMillis = new LongSamplingCounter(timeBase, in);
            mScanTimeMillis = new LongSamplingCounter(timeBase, in);
            mSleepTimeMillis = new LongSamplingCounter(timeBase, in);
            mRxTimeMillis = new LongSamplingCounter(timeBase, in);
            final int recordedTxStates = in.readInt();
            if (recordedTxStates != numTxStates) {
                throw new ParcelFormatException("inconsistent tx state lengths");
            }

            mTxTimeMillis = new LongSamplingCounter[numTxStates];
            for (int i = 0; i < numTxStates; i++) {
                mTxTimeMillis[i] = new LongSamplingCounter(timeBase, in);
            }
            mPowerDrainMaMs = new LongSamplingCounter(timeBase, in);
            mMonitoredRailChargeConsumedMaMs = new LongSamplingCounter(timeBase, in);
        }

        public void readSummaryFromParcel(Parcel in) {
            mIdleTimeMillis.readSummaryFromParcelLocked(in);
            mScanTimeMillis.readSummaryFromParcelLocked(in);
            mSleepTimeMillis.readSummaryFromParcelLocked(in);
            mRxTimeMillis.readSummaryFromParcelLocked(in);
            final int recordedTxStates = in.readInt();
            if (recordedTxStates != mTxTimeMillis.length) {
                throw new ParcelFormatException("inconsistent tx state lengths");
            }
            for (LongSamplingCounter counter : mTxTimeMillis) {
                counter.readSummaryFromParcelLocked(in);
            }
            mPowerDrainMaMs.readSummaryFromParcelLocked(in);
            mMonitoredRailChargeConsumedMaMs.readSummaryFromParcelLocked(in);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public void writeSummaryToParcel(Parcel dest) {
            mIdleTimeMillis.writeSummaryFromParcelLocked(dest);
            mScanTimeMillis.writeSummaryFromParcelLocked(dest);
            mSleepTimeMillis.writeSummaryFromParcelLocked(dest);
            mRxTimeMillis.writeSummaryFromParcelLocked(dest);
            dest.writeInt(mTxTimeMillis.length);
            for (LongSamplingCounter counter : mTxTimeMillis) {
                counter.writeSummaryFromParcelLocked(dest);
            }
            mPowerDrainMaMs.writeSummaryFromParcelLocked(dest);
            mMonitoredRailChargeConsumedMaMs.writeSummaryFromParcelLocked(dest);
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            mIdleTimeMillis.writeToParcel(dest);
            mScanTimeMillis.writeToParcel(dest);
            mSleepTimeMillis.writeToParcel(dest);
            mRxTimeMillis.writeToParcel(dest);
            dest.writeInt(mTxTimeMillis.length);
            for (LongSamplingCounter counter : mTxTimeMillis) {
                counter.writeToParcel(dest);
            }
            mPowerDrainMaMs.writeToParcel(dest);
            mMonitoredRailChargeConsumedMaMs.writeToParcel(dest);
        }

        public void reset(boolean detachIfReset) {
            mIdleTimeMillis.reset(detachIfReset);
            mScanTimeMillis.reset(detachIfReset);
            mSleepTimeMillis.reset(detachIfReset);
            mRxTimeMillis.reset(detachIfReset);
            for (LongSamplingCounter counter : mTxTimeMillis) {
                counter.reset(detachIfReset);
            }
            mPowerDrainMaMs.reset(detachIfReset);
            mMonitoredRailChargeConsumedMaMs.reset(detachIfReset);
        }

        public void detach() {
            mIdleTimeMillis.detach();
            mScanTimeMillis.detach();
            mSleepTimeMillis.detach();
            mRxTimeMillis.detach();
            for (LongSamplingCounter counter : mTxTimeMillis) {
                counter.detach();
            }
            mPowerDrainMaMs.detach();
            mMonitoredRailChargeConsumedMaMs.detach();
        }

        /**
         * @return a LongSamplingCounter, measuring time spent in the idle state in
         * milliseconds.
         */
        @Override
        public LongSamplingCounter getIdleTimeCounter() {
            return mIdleTimeMillis;
        }

        /**
         * @return a LongSamplingCounter, measuring time spent in the scan state in
         * milliseconds.
         */
        @Override
        public LongSamplingCounter getScanTimeCounter() {
            return mScanTimeMillis;
        }

        /**
         * @return a LongSamplingCounter, measuring time spent in the sleep state in
         * milliseconds.
         */
        @Override
        public LongSamplingCounter getSleepTimeCounter() {
            return mSleepTimeMillis;
        }

        /**
         * @return a LongSamplingCounter, measuring time spent in the receive state in
         * milliseconds.
         */
        @Override
        public LongSamplingCounter getRxTimeCounter() {
            return mRxTimeMillis;
        }

        /**
         * @return a LongSamplingCounter[], measuring time spent in various transmit states in
         * milliseconds.
         */
        @Override
        public LongSamplingCounter[] getTxTimeCounters() {
            return mTxTimeMillis;
        }

        /**
         * @return a LongSamplingCounter, measuring power use in milli-ampere milliseconds (mAmS).
         */
        @Override
        public LongSamplingCounter getPowerCounter() {
            return mPowerDrainMaMs;
        }

        /**
         * @return a LongSamplingCounter, measuring actual monitored rail energy consumed
         * milli-ampere milli-seconds (mAmS).
         */
        @Override
        public LongSamplingCounter getMonitoredRailChargeConsumedMaMs() {
            return mMonitoredRailChargeConsumedMaMs;
        }
    }

    /** Get Resource Power Manager stats. Create a new one if it doesn't already exist. */
    public SamplingTimer getRpmTimerLocked(String name) {
        SamplingTimer rpmt = mRpmStats.get(name);
        if (rpmt == null) {
            rpmt = new SamplingTimer(mClocks, mOnBatteryTimeBase);
            mRpmStats.put(name, rpmt);
        }
        return rpmt;
    }

    /** Get Screen-off Resource Power Manager stats. Create new one if it doesn't already exist. */
    public SamplingTimer getScreenOffRpmTimerLocked(String name) {
        SamplingTimer rpmt = mScreenOffRpmStats.get(name);
        if (rpmt == null) {
            rpmt = new SamplingTimer(mClocks, mOnBatteryScreenOffTimeBase);
            mScreenOffRpmStats.put(name, rpmt);
        }
        return rpmt;
    }

    /*
     * Get the wakeup reason counter, and create a new one if one
     * doesn't already exist.
     */
    public SamplingTimer getWakeupReasonTimerLocked(String name) {
        SamplingTimer timer = mWakeupReasonStats.get(name);
        if (timer == null) {
            timer = new SamplingTimer(mClocks, mOnBatteryTimeBase);
            mWakeupReasonStats.put(name, timer);
        }
        return timer;
    }

    /*
     * Get the KernelWakelockTimer associated with name, and create a new one if one
     * doesn't already exist.
     */
    public SamplingTimer getKernelWakelockTimerLocked(String name) {
        SamplingTimer kwlt = mKernelWakelockStats.get(name);
        if (kwlt == null) {
            kwlt = new SamplingTimer(mClocks, mOnBatteryScreenOffTimeBase);
            mKernelWakelockStats.put(name, kwlt);
        }
        return kwlt;
    }

    public SamplingTimer getKernelMemoryTimerLocked(long bucket) {
        SamplingTimer kmt = mKernelMemoryStats.get(bucket);
        if (kmt == null) {
            kmt = new SamplingTimer(mClocks, mOnBatteryTimeBase);
            mKernelMemoryStats.put(bucket, kmt);
        }
        return kmt;
    }

    private int writeHistoryTag(HistoryTag tag) {
        Integer idxObj = mHistoryTagPool.get(tag);
        int idx;
        if (idxObj != null) {
            idx = idxObj;
        } else {
            idx = mNextHistoryTagIdx;
            HistoryTag key = new HistoryTag();
            key.setTo(tag);
            tag.poolIdx = idx;
            mHistoryTagPool.put(key, idx);
            mNextHistoryTagIdx++;
            mNumHistoryTagChars += key.string.length() + 1;
        }
        return idx;
    }

    private void readHistoryTag(int index, HistoryTag tag) {
        if (index < mReadHistoryStrings.length) {
            tag.string = mReadHistoryStrings[index];
            tag.uid = mReadHistoryUids[index];
        } else {
            tag.string = null;
            tag.uid = 0;
        }
        tag.poolIdx = index;
    }

    /*
        The history delta format uses flags to denote further data in subsequent ints in the parcel.

        There is always the first token, which may contain the delta time, or an indicator of
        the length of the time (int or long) following this token.

        First token: always present,
        31              23              15               7             0
        █M|L|K|J|I|H|G|F█E|D|C|B|A|T|T|T█T|T|T|T|T|T|T|T█T|T|T|T|T|T|T|T█

        T: the delta time if it is <= 0x7fffd. Otherwise 0x7fffe indicates an int immediately
           follows containing the time, and 0x7ffff indicates a long immediately follows with the
           delta time.
        A: battery level changed and an int follows with battery data.
        B: state changed and an int follows with state change data.
        C: state2 has changed and an int follows with state2 change data.
        D: wakelock/wakereason has changed and an wakelock/wakereason struct follows.
        E: event data has changed and an event struct follows.
        F: battery charge in coulombs has changed and an int with the charge follows.
        G: state flag denoting that the mobile radio was active.
        H: state flag denoting that the wifi radio was active.
        I: state flag denoting that a wifi scan occurred.
        J: state flag denoting that a wifi full lock was held.
        K: state flag denoting that the gps was on.
        L: state flag denoting that a wakelock was held.
        M: state flag denoting that the cpu was running.

        Time int/long: if T in the first token is 0x7ffff or 0x7fffe, then an int or long follows
        with the time delta.

        Battery level int: if A in the first token is set,
        31              23              15               7             0
        █L|L|L|L|L|L|L|T█T|T|T|T|T|T|T|T█T|V|V|V|V|V|V|V█V|V|V|V|V|V|V|D█

        D: indicates that extra history details follow.
        V: the battery voltage.
        T: the battery temperature.
        L: the battery level (out of 100).

        State change int: if B in the first token is set,
        31              23              15               7             0
        █S|S|S|H|H|H|P|P█F|E|D|C|B| | |A█ | | | | | | | █ | | | | | | | █

        A: wifi multicast was on.
        B: battery was plugged in.
        C: screen was on.
        D: phone was scanning for signal.
        E: audio was on.
        F: a sensor was active.

        State2 change int: if C in the first token is set,
        31              23              15               7             0
        █M|L|K|J|I|H|H|G█F|E|D|C| | | | █ | | | | | | | █ |B|B|B|A|A|A|A█

        A: 4 bits indicating the wifi supplicant state: {@link BatteryStats#WIFI_SUPPL_STATE_NAMES}.
        B: 3 bits indicating the wifi signal strength: 0, 1, 2, 3, 4.
        C: a bluetooth scan was active.
        D: the camera was active.
        E: bluetooth was on.
        F: a phone call was active.
        G: the device was charging.
        H: 2 bits indicating the device-idle (doze) state: off, light, full
        I: the flashlight was on.
        J: wifi was on.
        K: wifi was running.
        L: video was playing.
        M: power save mode was on.

        Wakelock/wakereason struct: if D in the first token is set,
        TODO(adamlesinski): describe wakelock/wakereason struct.

        Event struct: if E in the first token is set,
        TODO(adamlesinski): describe the event struct.

        History step details struct: if D in the battery level int is set,
        TODO(adamlesinski): describe the history step details struct.

        Battery charge int: if F in the first token is set, an int representing the battery charge
        in coulombs follows.
     */

    // Part of initial delta int that specifies the time delta.
    static final int DELTA_TIME_MASK = 0x7ffff;
    static final int DELTA_TIME_LONG = 0x7ffff;   // The delta is a following long
    static final int DELTA_TIME_INT = 0x7fffe;    // The delta is a following int
    static final int DELTA_TIME_ABS = 0x7fffd;    // Following is an entire abs update.
    // Flag in delta int: a new battery level int follows.
    static final int DELTA_BATTERY_LEVEL_FLAG               = 0x00080000;
    // Flag in delta int: a new full state and battery status int follows.
    static final int DELTA_STATE_FLAG                       = 0x00100000;
    // Flag in delta int: a new full state2 int follows.
    static final int DELTA_STATE2_FLAG                      = 0x00200000;
    // Flag in delta int: contains a wakelock or wakeReason tag.
    static final int DELTA_WAKELOCK_FLAG                    = 0x00400000;
    // Flag in delta int: contains an event description.
    static final int DELTA_EVENT_FLAG                       = 0x00800000;
    // Flag in delta int: contains the battery charge count in uAh.
    static final int DELTA_BATTERY_CHARGE_FLAG              = 0x01000000;
    // These upper bits are the frequently changing state bits.
    static final int DELTA_STATE_MASK                       = 0xfe000000;

    // These are the pieces of battery state that are packed in to the upper bits of
    // the state int that have been packed in to the first delta int.  They must fit
    // in STATE_BATTERY_MASK.
    static final int STATE_BATTERY_MASK         = 0xff000000;
    static final int STATE_BATTERY_STATUS_MASK  = 0x00000007;
    static final int STATE_BATTERY_STATUS_SHIFT = 29;
    static final int STATE_BATTERY_HEALTH_MASK  = 0x00000007;
    static final int STATE_BATTERY_HEALTH_SHIFT = 26;
    static final int STATE_BATTERY_PLUG_MASK    = 0x00000003;
    static final int STATE_BATTERY_PLUG_SHIFT   = 24;

    // We use the low bit of the battery state int to indicate that we have full details
    // from a battery level change.
    static final int BATTERY_DELTA_LEVEL_FLAG   = 0x00000001;

    public void writeHistoryDelta(Parcel dest, HistoryItem cur, HistoryItem last) {
        if (last == null || cur.cmd != HistoryItem.CMD_UPDATE) {
            dest.writeInt(DELTA_TIME_ABS);
            cur.writeToParcel(dest, 0);
            return;
        }

        final long deltaTime = cur.time - last.time;
        final int lastBatteryLevelInt = buildBatteryLevelInt(last);
        final int lastStateInt = buildStateInt(last);

        int deltaTimeToken;
        if (deltaTime < 0 || deltaTime > Integer.MAX_VALUE) {
            deltaTimeToken = DELTA_TIME_LONG;
        } else if (deltaTime >= DELTA_TIME_ABS) {
            deltaTimeToken = DELTA_TIME_INT;
        } else {
            deltaTimeToken = (int)deltaTime;
        }
        int firstToken = deltaTimeToken | (cur.states&DELTA_STATE_MASK);
        final int includeStepDetails = mLastHistoryStepLevel > cur.batteryLevel
                ? BATTERY_DELTA_LEVEL_FLAG : 0;
        final boolean computeStepDetails = includeStepDetails != 0
                || mLastHistoryStepDetails == null;
        final int batteryLevelInt = buildBatteryLevelInt(cur) | includeStepDetails;
        final boolean batteryLevelIntChanged = batteryLevelInt != lastBatteryLevelInt;
        if (batteryLevelIntChanged) {
            firstToken |= DELTA_BATTERY_LEVEL_FLAG;
        }
        final int stateInt = buildStateInt(cur);
        final boolean stateIntChanged = stateInt != lastStateInt;
        if (stateIntChanged) {
            firstToken |= DELTA_STATE_FLAG;
        }
        final boolean state2IntChanged = cur.states2 != last.states2;
        if (state2IntChanged) {
            firstToken |= DELTA_STATE2_FLAG;
        }
        if (cur.wakelockTag != null || cur.wakeReasonTag != null) {
            firstToken |= DELTA_WAKELOCK_FLAG;
        }
        if (cur.eventCode != HistoryItem.EVENT_NONE) {
            firstToken |= DELTA_EVENT_FLAG;
        }

        final boolean batteryChargeChanged = cur.batteryChargeUAh != last.batteryChargeUAh;
        if (batteryChargeChanged) {
            firstToken |= DELTA_BATTERY_CHARGE_FLAG;
        }
        dest.writeInt(firstToken);
        if (DEBUG) Slog.i(TAG, "WRITE DELTA: firstToken=0x" + Integer.toHexString(firstToken)
                + " deltaTime=" + deltaTime);

        if (deltaTimeToken >= DELTA_TIME_INT) {
            if (deltaTimeToken == DELTA_TIME_INT) {
                if (DEBUG) Slog.i(TAG, "WRITE DELTA: int deltaTime=" + (int)deltaTime);
                dest.writeInt((int)deltaTime);
            } else {
                if (DEBUG) Slog.i(TAG, "WRITE DELTA: long deltaTime=" + deltaTime);
                dest.writeLong(deltaTime);
            }
        }
        if (batteryLevelIntChanged) {
            dest.writeInt(batteryLevelInt);
            if (DEBUG) Slog.i(TAG, "WRITE DELTA: batteryToken=0x"
                    + Integer.toHexString(batteryLevelInt)
                    + " batteryLevel=" + cur.batteryLevel
                    + " batteryTemp=" + cur.batteryTemperature
                    + " batteryVolt=" + (int)cur.batteryVoltage);
        }
        if (stateIntChanged) {
            dest.writeInt(stateInt);
            if (DEBUG) Slog.i(TAG, "WRITE DELTA: stateToken=0x"
                    + Integer.toHexString(stateInt)
                    + " batteryStatus=" + cur.batteryStatus
                    + " batteryHealth=" + cur.batteryHealth
                    + " batteryPlugType=" + cur.batteryPlugType
                    + " states=0x" + Integer.toHexString(cur.states));
        }
        if (state2IntChanged) {
            dest.writeInt(cur.states2);
            if (DEBUG) Slog.i(TAG, "WRITE DELTA: states2=0x"
                    + Integer.toHexString(cur.states2));
        }
        if (cur.wakelockTag != null || cur.wakeReasonTag != null) {
            int wakeLockIndex;
            int wakeReasonIndex;
            if (cur.wakelockTag != null) {
                wakeLockIndex = writeHistoryTag(cur.wakelockTag);
                if (DEBUG) Slog.i(TAG, "WRITE DELTA: wakelockTag=#" + cur.wakelockTag.poolIdx
                    + " " + cur.wakelockTag.uid + ":" + cur.wakelockTag.string);
            } else {
                wakeLockIndex = 0xffff;
            }
            if (cur.wakeReasonTag != null) {
                wakeReasonIndex = writeHistoryTag(cur.wakeReasonTag);
                if (DEBUG) Slog.i(TAG, "WRITE DELTA: wakeReasonTag=#" + cur.wakeReasonTag.poolIdx
                    + " " + cur.wakeReasonTag.uid + ":" + cur.wakeReasonTag.string);
            } else {
                wakeReasonIndex = 0xffff;
            }
            dest.writeInt((wakeReasonIndex<<16) | wakeLockIndex);
        }
        if (cur.eventCode != HistoryItem.EVENT_NONE) {
            int index = writeHistoryTag(cur.eventTag);
            int codeAndIndex = (cur.eventCode&0xffff) | (index<<16);
            dest.writeInt(codeAndIndex);
            if (DEBUG) Slog.i(TAG, "WRITE DELTA: event=" + cur.eventCode + " tag=#"
                    + cur.eventTag.poolIdx + " " + cur.eventTag.uid + ":"
                    + cur.eventTag.string);
        }
        if (computeStepDetails) {
            if (mPlatformIdleStateCallback != null) {
                mCurHistoryStepDetails.statPlatformIdleState =
                        mPlatformIdleStateCallback.getPlatformLowPowerStats();
                if (DEBUG) Slog.i(TAG, "WRITE PlatformIdleState:" +
                        mCurHistoryStepDetails.statPlatformIdleState);

                mCurHistoryStepDetails.statSubsystemPowerState =
                        mPlatformIdleStateCallback.getSubsystemLowPowerStats();
                if (DEBUG) Slog.i(TAG, "WRITE SubsystemPowerState:" +
                        mCurHistoryStepDetails.statSubsystemPowerState);

            }
            computeHistoryStepDetails(mCurHistoryStepDetails, mLastHistoryStepDetails);
            if (includeStepDetails != 0) {
                mCurHistoryStepDetails.writeToParcel(dest);
            }
            cur.stepDetails = mCurHistoryStepDetails;
            mLastHistoryStepDetails = mCurHistoryStepDetails;
        } else {
            cur.stepDetails = null;
        }
        if (mLastHistoryStepLevel < cur.batteryLevel) {
            mLastHistoryStepDetails = null;
        }
        mLastHistoryStepLevel = cur.batteryLevel;

        if (batteryChargeChanged) {
            if (DEBUG) Slog.i(TAG, "WRITE DELTA: batteryChargeUAh=" + cur.batteryChargeUAh);
            dest.writeInt(cur.batteryChargeUAh);
        }
        dest.writeDouble(cur.modemRailChargeMah);
        dest.writeDouble(cur.wifiRailChargeMah);
    }

    private int buildBatteryLevelInt(HistoryItem h) {
        return ((((int)h.batteryLevel)<<25)&0xfe000000)
                | ((((int)h.batteryTemperature)<<15)&0x01ff8000)
                | ((((int)h.batteryVoltage)<<1)&0x00007ffe);
    }

    private void readBatteryLevelInt(int batteryLevelInt, HistoryItem out) {
        out.batteryLevel = (byte)((batteryLevelInt & 0xfe000000) >>> 25);
        out.batteryTemperature = (short)((batteryLevelInt & 0x01ff8000) >>> 15);
        out.batteryVoltage = (char)((batteryLevelInt & 0x00007ffe) >>> 1);
    }

    private int buildStateInt(HistoryItem h) {
        int plugType = 0;
        if ((h.batteryPlugType&BatteryManager.BATTERY_PLUGGED_AC) != 0) {
            plugType = 1;
        } else if ((h.batteryPlugType&BatteryManager.BATTERY_PLUGGED_USB) != 0) {
            plugType = 2;
        } else if ((h.batteryPlugType&BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0) {
            plugType = 3;
        }
        return ((h.batteryStatus&STATE_BATTERY_STATUS_MASK)<<STATE_BATTERY_STATUS_SHIFT)
                | ((h.batteryHealth&STATE_BATTERY_HEALTH_MASK)<<STATE_BATTERY_HEALTH_SHIFT)
                | ((plugType&STATE_BATTERY_PLUG_MASK)<<STATE_BATTERY_PLUG_SHIFT)
                | (h.states&(~STATE_BATTERY_MASK));
    }

    private void computeHistoryStepDetails(final HistoryStepDetails out,
            final HistoryStepDetails last) {
        final HistoryStepDetails tmp = last != null ? mTmpHistoryStepDetails : out;

        // Perform a CPU update right after we do this collection, so we have started
        // collecting good data for the next step.
        requestImmediateCpuUpdate();

        if (last == null) {
            // We are not generating a delta, so all we need to do is reset the stats
            // we will later be doing a delta from.
            final int NU = mUidStats.size();
            for (int i=0; i<NU; i++) {
                final BatteryStatsImpl.Uid uid = mUidStats.valueAt(i);
                uid.mLastStepUserTime = uid.mCurStepUserTime;
                uid.mLastStepSystemTime = uid.mCurStepSystemTime;
            }
            mLastStepCpuUserTime = mCurStepCpuUserTime;
            mLastStepCpuSystemTime = mCurStepCpuSystemTime;
            mLastStepStatUserTime = mCurStepStatUserTime;
            mLastStepStatSystemTime = mCurStepStatSystemTime;
            mLastStepStatIOWaitTime = mCurStepStatIOWaitTime;
            mLastStepStatIrqTime = mCurStepStatIrqTime;
            mLastStepStatSoftIrqTime = mCurStepStatSoftIrqTime;
            mLastStepStatIdleTime = mCurStepStatIdleTime;
            tmp.clear();
            return;
        }
        if (DEBUG) {
            Slog.d(TAG, "Step stats last: user=" + mLastStepCpuUserTime + " sys="
                    + mLastStepStatSystemTime + " io=" + mLastStepStatIOWaitTime
                    + " irq=" + mLastStepStatIrqTime + " sirq="
                    + mLastStepStatSoftIrqTime + " idle=" + mLastStepStatIdleTime);
            Slog.d(TAG, "Step stats cur: user=" + mCurStepCpuUserTime + " sys="
                    + mCurStepStatSystemTime + " io=" + mCurStepStatIOWaitTime
                    + " irq=" + mCurStepStatIrqTime + " sirq="
                    + mCurStepStatSoftIrqTime + " idle=" + mCurStepStatIdleTime);
        }
        out.userTime = (int)(mCurStepCpuUserTime - mLastStepCpuUserTime);
        out.systemTime = (int)(mCurStepCpuSystemTime - mLastStepCpuSystemTime);
        out.statUserTime = (int)(mCurStepStatUserTime - mLastStepStatUserTime);
        out.statSystemTime = (int)(mCurStepStatSystemTime - mLastStepStatSystemTime);
        out.statIOWaitTime = (int)(mCurStepStatIOWaitTime - mLastStepStatIOWaitTime);
        out.statIrqTime = (int)(mCurStepStatIrqTime - mLastStepStatIrqTime);
        out.statSoftIrqTime = (int)(mCurStepStatSoftIrqTime - mLastStepStatSoftIrqTime);
        out.statIdlTime = (int)(mCurStepStatIdleTime - mLastStepStatIdleTime);
        out.appCpuUid1 = out.appCpuUid2 = out.appCpuUid3 = -1;
        out.appCpuUTime1 = out.appCpuUTime2 = out.appCpuUTime3 = 0;
        out.appCpuSTime1 = out.appCpuSTime2 = out.appCpuSTime3 = 0;
        final int NU = mUidStats.size();
        for (int i=0; i<NU; i++) {
            final BatteryStatsImpl.Uid uid = mUidStats.valueAt(i);
            final int totalUTime = (int)(uid.mCurStepUserTime - uid.mLastStepUserTime);
            final int totalSTime = (int)(uid.mCurStepSystemTime - uid.mLastStepSystemTime);
            final int totalTime = totalUTime + totalSTime;
            uid.mLastStepUserTime = uid.mCurStepUserTime;
            uid.mLastStepSystemTime = uid.mCurStepSystemTime;
            if (totalTime <= (out.appCpuUTime3+out.appCpuSTime3)) {
                continue;
            }
            if (totalTime <= (out.appCpuUTime2+out.appCpuSTime2)) {
                out.appCpuUid3 = uid.mUid;
                out.appCpuUTime3 = totalUTime;
                out.appCpuSTime3 = totalSTime;
            } else {
                out.appCpuUid3 = out.appCpuUid2;
                out.appCpuUTime3 = out.appCpuUTime2;
                out.appCpuSTime3 = out.appCpuSTime2;
                if (totalTime <= (out.appCpuUTime1+out.appCpuSTime1)) {
                    out.appCpuUid2 = uid.mUid;
                    out.appCpuUTime2 = totalUTime;
                    out.appCpuSTime2 = totalSTime;
                } else {
                    out.appCpuUid2 = out.appCpuUid1;
                    out.appCpuUTime2 = out.appCpuUTime1;
                    out.appCpuSTime2 = out.appCpuSTime1;
                    out.appCpuUid1 = uid.mUid;
                    out.appCpuUTime1 = totalUTime;
                    out.appCpuSTime1 = totalSTime;
                }
            }
        }
        mLastStepCpuUserTime = mCurStepCpuUserTime;
        mLastStepCpuSystemTime = mCurStepCpuSystemTime;
        mLastStepStatUserTime = mCurStepStatUserTime;
        mLastStepStatSystemTime = mCurStepStatSystemTime;
        mLastStepStatIOWaitTime = mCurStepStatIOWaitTime;
        mLastStepStatIrqTime = mCurStepStatIrqTime;
        mLastStepStatSoftIrqTime = mCurStepStatSoftIrqTime;
        mLastStepStatIdleTime = mCurStepStatIdleTime;
    }

    public void readHistoryDelta(Parcel src, HistoryItem cur) {
        int firstToken = src.readInt();
        int deltaTimeToken = firstToken&DELTA_TIME_MASK;
        cur.cmd = HistoryItem.CMD_UPDATE;
        cur.numReadInts = 1;
        if (DEBUG) Slog.i(TAG, "READ DELTA: firstToken=0x" + Integer.toHexString(firstToken)
                + " deltaTimeToken=" + deltaTimeToken);

        if (deltaTimeToken < DELTA_TIME_ABS) {
            cur.time += deltaTimeToken;
        } else if (deltaTimeToken == DELTA_TIME_ABS) {
            cur.time = src.readLong();
            cur.numReadInts += 2;
            if (DEBUG) Slog.i(TAG, "READ DELTA: ABS time=" + cur.time);
            cur.readFromParcel(src);
            return;
        } else if (deltaTimeToken == DELTA_TIME_INT) {
            int delta = src.readInt();
            cur.time += delta;
            cur.numReadInts += 1;
            if (DEBUG) Slog.i(TAG, "READ DELTA: time delta=" + delta + " new time=" + cur.time);
        } else {
            long delta = src.readLong();
            if (DEBUG) Slog.i(TAG, "READ DELTA: time delta=" + delta + " new time=" + cur.time);
            cur.time += delta;
            cur.numReadInts += 2;
        }

        final int batteryLevelInt;
        if ((firstToken&DELTA_BATTERY_LEVEL_FLAG) != 0) {
            batteryLevelInt = src.readInt();
            readBatteryLevelInt(batteryLevelInt, cur);
            cur.numReadInts += 1;
            if (DEBUG) Slog.i(TAG, "READ DELTA: batteryToken=0x"
                    + Integer.toHexString(batteryLevelInt)
                    + " batteryLevel=" + cur.batteryLevel
                    + " batteryTemp=" + cur.batteryTemperature
                    + " batteryVolt=" + (int)cur.batteryVoltage);
        } else {
            batteryLevelInt = 0;
        }

        if ((firstToken&DELTA_STATE_FLAG) != 0) {
            int stateInt = src.readInt();
            cur.states = (firstToken&DELTA_STATE_MASK) | (stateInt&(~STATE_BATTERY_MASK));
            cur.batteryStatus = (byte)((stateInt>>STATE_BATTERY_STATUS_SHIFT)
                    & STATE_BATTERY_STATUS_MASK);
            cur.batteryHealth = (byte)((stateInt>>STATE_BATTERY_HEALTH_SHIFT)
                    & STATE_BATTERY_HEALTH_MASK);
            cur.batteryPlugType = (byte)((stateInt>>STATE_BATTERY_PLUG_SHIFT)
                    & STATE_BATTERY_PLUG_MASK);
            switch (cur.batteryPlugType) {
                case 1:
                    cur.batteryPlugType = BatteryManager.BATTERY_PLUGGED_AC;
                    break;
                case 2:
                    cur.batteryPlugType = BatteryManager.BATTERY_PLUGGED_USB;
                    break;
                case 3:
                    cur.batteryPlugType = BatteryManager.BATTERY_PLUGGED_WIRELESS;
                    break;
            }
            cur.numReadInts += 1;
            if (DEBUG) Slog.i(TAG, "READ DELTA: stateToken=0x"
                    + Integer.toHexString(stateInt)
                    + " batteryStatus=" + cur.batteryStatus
                    + " batteryHealth=" + cur.batteryHealth
                    + " batteryPlugType=" + cur.batteryPlugType
                    + " states=0x" + Integer.toHexString(cur.states));
        } else {
            cur.states = (firstToken&DELTA_STATE_MASK) | (cur.states&(~STATE_BATTERY_MASK));
        }

        if ((firstToken&DELTA_STATE2_FLAG) != 0) {
            cur.states2 = src.readInt();
            if (DEBUG) Slog.i(TAG, "READ DELTA: states2=0x"
                    + Integer.toHexString(cur.states2));
        }

        if ((firstToken&DELTA_WAKELOCK_FLAG) != 0) {
            int indexes = src.readInt();
            int wakeLockIndex = indexes&0xffff;
            int wakeReasonIndex = (indexes>>16)&0xffff;
            if (wakeLockIndex != 0xffff) {
                cur.wakelockTag = cur.localWakelockTag;
                readHistoryTag(wakeLockIndex, cur.wakelockTag);
                if (DEBUG) Slog.i(TAG, "READ DELTA: wakelockTag=#" + cur.wakelockTag.poolIdx
                    + " " + cur.wakelockTag.uid + ":" + cur.wakelockTag.string);
            } else {
                cur.wakelockTag = null;
            }
            if (wakeReasonIndex != 0xffff) {
                cur.wakeReasonTag = cur.localWakeReasonTag;
                readHistoryTag(wakeReasonIndex, cur.wakeReasonTag);
                if (DEBUG) Slog.i(TAG, "READ DELTA: wakeReasonTag=#" + cur.wakeReasonTag.poolIdx
                    + " " + cur.wakeReasonTag.uid + ":" + cur.wakeReasonTag.string);
            } else {
                cur.wakeReasonTag = null;
            }
            cur.numReadInts += 1;
        } else {
            cur.wakelockTag = null;
            cur.wakeReasonTag = null;
        }

        if ((firstToken&DELTA_EVENT_FLAG) != 0) {
            cur.eventTag = cur.localEventTag;
            final int codeAndIndex = src.readInt();
            cur.eventCode = (codeAndIndex&0xffff);
            final int index = ((codeAndIndex>>16)&0xffff);
            readHistoryTag(index, cur.eventTag);
            cur.numReadInts += 1;
            if (DEBUG) Slog.i(TAG, "READ DELTA: event=" + cur.eventCode + " tag=#"
                    + cur.eventTag.poolIdx + " " + cur.eventTag.uid + ":"
                    + cur.eventTag.string);
        } else {
            cur.eventCode = HistoryItem.EVENT_NONE;
        }

        if ((batteryLevelInt&BATTERY_DELTA_LEVEL_FLAG) != 0) {
            cur.stepDetails = mReadHistoryStepDetails;
            cur.stepDetails.readFromParcel(src);
        } else {
            cur.stepDetails = null;
        }

        if ((firstToken&DELTA_BATTERY_CHARGE_FLAG) != 0) {
            cur.batteryChargeUAh = src.readInt();
        }
        cur.modemRailChargeMah = src.readDouble();
        cur.wifiRailChargeMah = src.readDouble();
    }

    @Override
    public void commitCurrentHistoryBatchLocked() {
        mHistoryLastWritten.cmd = HistoryItem.CMD_NULL;
    }

    public void createFakeHistoryEvents(long numEvents) {
        for(long i = 0; i < numEvents; i++) {
            noteLongPartialWakelockStart("name1", "historyName1", 1000);
            noteLongPartialWakelockFinish("name1", "historyName1", 1000);
        }
    }

    void addHistoryBufferLocked(long elapsedRealtimeMs, HistoryItem cur) {
        if (!mHaveBatteryLevel || !mRecordingHistory) {
            return;
        }

        final long timeDiff = (mHistoryBaseTime+elapsedRealtimeMs) - mHistoryLastWritten.time;
        final int diffStates = mHistoryLastWritten.states^(cur.states&mActiveHistoryStates);
        final int diffStates2 = mHistoryLastWritten.states2^(cur.states2&mActiveHistoryStates2);
        final int lastDiffStates = mHistoryLastWritten.states^mHistoryLastLastWritten.states;
        final int lastDiffStates2 = mHistoryLastWritten.states2^mHistoryLastLastWritten.states2;
        if (DEBUG) Slog.i(TAG, "ADD: tdelta=" + timeDiff + " diff="
                + Integer.toHexString(diffStates) + " lastDiff="
                + Integer.toHexString(lastDiffStates) + " diff2="
                + Integer.toHexString(diffStates2) + " lastDiff2="
                + Integer.toHexString(lastDiffStates2));
        if (mHistoryBufferLastPos >= 0 && mHistoryLastWritten.cmd == HistoryItem.CMD_UPDATE
                && timeDiff < 1000 && (diffStates&lastDiffStates) == 0
                && (diffStates2&lastDiffStates2) == 0
                && (mHistoryLastWritten.wakelockTag == null || cur.wakelockTag == null)
                && (mHistoryLastWritten.wakeReasonTag == null || cur.wakeReasonTag == null)
                && mHistoryLastWritten.stepDetails == null
                && (mHistoryLastWritten.eventCode == HistoryItem.EVENT_NONE
                        || cur.eventCode == HistoryItem.EVENT_NONE)
                && mHistoryLastWritten.batteryLevel == cur.batteryLevel
                && mHistoryLastWritten.batteryStatus == cur.batteryStatus
                && mHistoryLastWritten.batteryHealth == cur.batteryHealth
                && mHistoryLastWritten.batteryPlugType == cur.batteryPlugType
                && mHistoryLastWritten.batteryTemperature == cur.batteryTemperature
                && mHistoryLastWritten.batteryVoltage == cur.batteryVoltage) {
            // We can merge this new change in with the last one.  Merging is
            // allowed as long as only the states have changed, and within those states
            // as long as no bit has changed both between now and the last entry, as
            // well as the last entry and the one before it (so we capture any toggles).
            if (DEBUG) Slog.i(TAG, "ADD: rewinding back to " + mHistoryBufferLastPos);
            mHistoryBuffer.setDataSize(mHistoryBufferLastPos);
            mHistoryBuffer.setDataPosition(mHistoryBufferLastPos);
            mHistoryBufferLastPos = -1;
            elapsedRealtimeMs = mHistoryLastWritten.time - mHistoryBaseTime;
            // If the last written history had a wakelock tag, we need to retain it.
            // Note that the condition above made sure that we aren't in a case where
            // both it and the current history item have a wakelock tag.
            if (mHistoryLastWritten.wakelockTag != null) {
                cur.wakelockTag = cur.localWakelockTag;
                cur.wakelockTag.setTo(mHistoryLastWritten.wakelockTag);
            }
            // If the last written history had a wake reason tag, we need to retain it.
            // Note that the condition above made sure that we aren't in a case where
            // both it and the current history item have a wakelock tag.
            if (mHistoryLastWritten.wakeReasonTag != null) {
                cur.wakeReasonTag = cur.localWakeReasonTag;
                cur.wakeReasonTag.setTo(mHistoryLastWritten.wakeReasonTag);
            }
            // If the last written history had an event, we need to retain it.
            // Note that the condition above made sure that we aren't in a case where
            // both it and the current history item have an event.
            if (mHistoryLastWritten.eventCode != HistoryItem.EVENT_NONE) {
                cur.eventCode = mHistoryLastWritten.eventCode;
                cur.eventTag = cur.localEventTag;
                cur.eventTag.setTo(mHistoryLastWritten.eventTag);
            }
            mHistoryLastWritten.setTo(mHistoryLastLastWritten);
        }
        final int dataSize = mHistoryBuffer.dataSize();

        if (dataSize >= mConstants.MAX_HISTORY_BUFFER) {
            //open a new history file.
            final long start = SystemClock.uptimeMillis();
            writeHistoryLocked(true);
            if (DEBUG) {
                Slog.d(TAG, "addHistoryBufferLocked writeHistoryLocked takes ms:"
                        + (SystemClock.uptimeMillis() - start));
            }
            mBatteryStatsHistory.startNextFile();
            mHistoryBuffer.setDataSize(0);
            mHistoryBuffer.setDataPosition(0);
            mHistoryBuffer.setDataCapacity(mConstants.MAX_HISTORY_BUFFER / 2);
            mHistoryBufferLastPos = -1;
            final long elapsedRealtime = mClocks.elapsedRealtime();
            final long uptime = mClocks.uptimeMillis();
            HistoryItem newItem = new HistoryItem();
            newItem.setTo(cur);
            startRecordingHistory(elapsedRealtime, uptime, false);
            addHistoryBufferLocked(elapsedRealtimeMs, HistoryItem.CMD_UPDATE, newItem);
            return;
        }

        if (dataSize == 0) {
            // The history is currently empty; we need it to start with a time stamp.
            cur.currentTime = System.currentTimeMillis();
            addHistoryBufferLocked(elapsedRealtimeMs, HistoryItem.CMD_RESET, cur);
        }
        addHistoryBufferLocked(elapsedRealtimeMs, HistoryItem.CMD_UPDATE, cur);
    }

    private void addHistoryBufferLocked(long elapsedRealtimeMs, byte cmd, HistoryItem cur) {
        if (mIteratingHistory) {
            throw new IllegalStateException("Can't do this while iterating history!");
        }
        mHistoryBufferLastPos = mHistoryBuffer.dataPosition();
        mHistoryLastLastWritten.setTo(mHistoryLastWritten);
        mHistoryLastWritten.setTo(mHistoryBaseTime + elapsedRealtimeMs, cmd, cur);
        mHistoryLastWritten.states &= mActiveHistoryStates;
        mHistoryLastWritten.states2 &= mActiveHistoryStates2;
        writeHistoryDelta(mHistoryBuffer, mHistoryLastWritten, mHistoryLastLastWritten);
        mLastHistoryElapsedRealtime = elapsedRealtimeMs;
        cur.wakelockTag = null;
        cur.wakeReasonTag = null;
        cur.eventCode = HistoryItem.EVENT_NONE;
        cur.eventTag = null;
        if (DEBUG_HISTORY) Slog.i(TAG, "Writing history buffer: was " + mHistoryBufferLastPos
                + " now " + mHistoryBuffer.dataPosition()
                + " size is now " + mHistoryBuffer.dataSize());
    }

    int mChangedStates = 0;
    int mChangedStates2 = 0;

    void addHistoryRecordLocked(long elapsedRealtimeMs, long uptimeMs) {
        if (mTrackRunningHistoryElapsedRealtime != 0) {
            final long diffElapsed = elapsedRealtimeMs - mTrackRunningHistoryElapsedRealtime;
            final long diffUptime = uptimeMs - mTrackRunningHistoryUptime;
            if (diffUptime < (diffElapsed-20)) {
                final long wakeElapsedTime = elapsedRealtimeMs - (diffElapsed - diffUptime);
                mHistoryAddTmp.setTo(mHistoryLastWritten);
                mHistoryAddTmp.wakelockTag = null;
                mHistoryAddTmp.wakeReasonTag = null;
                mHistoryAddTmp.eventCode = HistoryItem.EVENT_NONE;
                mHistoryAddTmp.states &= ~HistoryItem.STATE_CPU_RUNNING_FLAG;
                addHistoryRecordInnerLocked(wakeElapsedTime, mHistoryAddTmp);
            }
        }
        mHistoryCur.states |= HistoryItem.STATE_CPU_RUNNING_FLAG;
        mTrackRunningHistoryElapsedRealtime = elapsedRealtimeMs;
        mTrackRunningHistoryUptime = uptimeMs;
        addHistoryRecordInnerLocked(elapsedRealtimeMs, mHistoryCur);
    }

    void addHistoryRecordInnerLocked(long elapsedRealtimeMs, HistoryItem cur) {
        addHistoryBufferLocked(elapsedRealtimeMs, cur);

        if (!USE_OLD_HISTORY) {
            return;
        }

        if (!mHaveBatteryLevel || !mRecordingHistory) {
            return;
        }

        // If the current time is basically the same as the last time,
        // and no states have since the last recorded entry changed and
        // are now resetting back to their original value, then just collapse
        // into one record.
        if (mHistoryEnd != null && mHistoryEnd.cmd == HistoryItem.CMD_UPDATE
                && (mHistoryBaseTime+elapsedRealtimeMs) < (mHistoryEnd.time+1000)
                && ((mHistoryEnd.states^cur.states)&mChangedStates&mActiveHistoryStates) == 0
                && ((mHistoryEnd.states2^cur.states2)&mChangedStates2&mActiveHistoryStates2) == 0) {
            // If the current is the same as the one before, then we no
            // longer need the entry.
            if (mHistoryLastEnd != null && mHistoryLastEnd.cmd == HistoryItem.CMD_UPDATE
                    && (mHistoryBaseTime+elapsedRealtimeMs) < (mHistoryEnd.time+500)
                    && mHistoryLastEnd.sameNonEvent(cur)) {
                mHistoryLastEnd.next = null;
                mHistoryEnd.next = mHistoryCache;
                mHistoryCache = mHistoryEnd;
                mHistoryEnd = mHistoryLastEnd;
                mHistoryLastEnd = null;
            } else {
                mChangedStates |= mHistoryEnd.states^(cur.states&mActiveHistoryStates);
                mChangedStates2 |= mHistoryEnd.states^(cur.states2&mActiveHistoryStates2);
                mHistoryEnd.setTo(mHistoryEnd.time, HistoryItem.CMD_UPDATE, cur);
            }
            return;
        }

        mChangedStates = 0;
        mChangedStates2 = 0;
        addHistoryBufferLocked(elapsedRealtimeMs, HistoryItem.CMD_UPDATE, cur);
    }

    public void addHistoryEventLocked(long elapsedRealtimeMs, long uptimeMs, int code,
            String name, int uid) {
        mHistoryCur.eventCode = code;
        mHistoryCur.eventTag = mHistoryCur.localEventTag;
        mHistoryCur.eventTag.string = name;
        mHistoryCur.eventTag.uid = uid;
        addHistoryRecordLocked(elapsedRealtimeMs, uptimeMs);
    }

    void addHistoryRecordLocked(long elapsedRealtimeMs, long uptimeMs, byte cmd, HistoryItem cur) {
        HistoryItem rec = mHistoryCache;
        if (rec != null) {
            mHistoryCache = rec.next;
        } else {
            rec = new HistoryItem();
        }
        rec.setTo(mHistoryBaseTime + elapsedRealtimeMs, cmd, cur);

        addHistoryRecordLocked(rec);
    }

    void addHistoryRecordLocked(HistoryItem rec) {
        mNumHistoryItems++;
        rec.next = null;
        mHistoryLastEnd = mHistoryEnd;
        if (mHistoryEnd != null) {
            mHistoryEnd.next = rec;
            mHistoryEnd = rec;
        } else {
            mHistory = mHistoryEnd = rec;
        }
    }

    void clearHistoryLocked() {
        if (DEBUG_HISTORY) Slog.i(TAG, "********** CLEARING HISTORY!");
        if (USE_OLD_HISTORY) {
            if (mHistory != null) {
                mHistoryEnd.next = mHistoryCache;
                mHistoryCache = mHistory;
                mHistory = mHistoryLastEnd = mHistoryEnd = null;
            }
            mNumHistoryItems = 0;
        }

        mHistoryBaseTime = 0;
        mLastHistoryElapsedRealtime = 0;
        mTrackRunningHistoryElapsedRealtime = 0;
        mTrackRunningHistoryUptime = 0;

        mHistoryBuffer.setDataSize(0);
        mHistoryBuffer.setDataPosition(0);
        mHistoryBuffer.setDataCapacity(mConstants.MAX_HISTORY_BUFFER / 2);
        mHistoryLastLastWritten.clear();
        mHistoryLastWritten.clear();
        mHistoryTagPool.clear();
        mNextHistoryTagIdx = 0;
        mNumHistoryTagChars = 0;
        mHistoryBufferLastPos = -1;
        mActiveHistoryStates = 0xffffffff;
        mActiveHistoryStates2 = 0xffffffff;
    }

    @GuardedBy("this")
    public void updateTimeBasesLocked(boolean unplugged, int screenState, long uptime,
            long realtime) {
        final boolean screenOff = !isScreenOn(screenState);
        final boolean updateOnBatteryTimeBase = unplugged != mOnBatteryTimeBase.isRunning();
        final boolean updateOnBatteryScreenOffTimeBase =
                (unplugged && screenOff) != mOnBatteryScreenOffTimeBase.isRunning();

        if (updateOnBatteryScreenOffTimeBase || updateOnBatteryTimeBase) {
            if (updateOnBatteryScreenOffTimeBase) {
                updateKernelWakelocksLocked();
                updateBatteryPropertiesLocked();
            }
            // This if{} is only necessary due to SCREEN_OFF_RPM_STATS_ENABLED, which exists because
            // updateRpmStatsLocked is too slow to run each screen change. When the speed is
            // improved, remove the surrounding if{}.
            if (SCREEN_OFF_RPM_STATS_ENABLED || updateOnBatteryTimeBase) {
                updateRpmStatsLocked(); // if either OnBattery or OnBatteryScreenOff timebase changes.
            }
            if (DEBUG_ENERGY_CPU) {
                Slog.d(TAG, "Updating cpu time because screen is now "
                        + Display.stateToString(screenState)
                        + " and battery is " + (unplugged ? "on" : "off"));
            }

            mOnBatteryTimeBase.setRunning(unplugged, uptime, realtime);
            if (updateOnBatteryTimeBase) {
                for (int i = mUidStats.size() - 1; i >= 0; --i) {
                    mUidStats.valueAt(i).updateOnBatteryBgTimeBase(uptime, realtime);
                }
            }
            if (updateOnBatteryScreenOffTimeBase) {
                mOnBatteryScreenOffTimeBase.setRunning(unplugged && screenOff, uptime, realtime);
                for (int i = mUidStats.size() - 1; i >= 0; --i) {
                    mUidStats.valueAt(i).updateOnBatteryScreenOffBgTimeBase(uptime, realtime);
                }
            }
        }
    }

    private void updateBatteryPropertiesLocked() {
        try {
            IBatteryPropertiesRegistrar registrar = IBatteryPropertiesRegistrar.Stub.asInterface(
                    ServiceManager.getService("batteryproperties"));
            if (registrar != null) {
                registrar.scheduleUpdate();
            }
        } catch (RemoteException e) {
            // Ignore.
        }
    }

    public void addIsolatedUidLocked(int isolatedUid, int appUid) {
        mIsolatedUids.put(isolatedUid, appUid);
        final Uid u = getUidStatsLocked(appUid);
        u.addIsolatedUid(isolatedUid);
    }

    /**
     * Schedules a read of the latest cpu times before removing the isolated UID.
     * @see #removeIsolatedUidLocked(int)
     */
    public void scheduleRemoveIsolatedUidLocked(int isolatedUid, int appUid) {
        int curUid = mIsolatedUids.get(isolatedUid, -1);
        if (curUid == appUid) {
            if (mExternalSync != null) {
                mExternalSync.scheduleCpuSyncDueToRemovedUid(isolatedUid);
            }
        }
    }

    /**
     * This should only be called after the cpu times have been read.
     * @see #scheduleRemoveIsolatedUidLocked(int, int)
     */
    @GuardedBy("this")
    public void removeIsolatedUidLocked(int isolatedUid) {
        final int idx = mIsolatedUids.indexOfKey(isolatedUid);
        if (idx >= 0) {
            final int ownerUid = mIsolatedUids.valueAt(idx);
            final Uid u = getUidStatsLocked(ownerUid);
            u.removeIsolatedUid(isolatedUid);
            mIsolatedUids.removeAt(idx);
        }
        mPendingRemovedUids.add(new UidToRemove(isolatedUid, mClocks.elapsedRealtime()));
    }

    public int mapUid(int uid) {
        int isolated = mIsolatedUids.get(uid, -1);
        return isolated > 0 ? isolated : uid;
    }

    public void noteEventLocked(int code, String name, int uid) {
        uid = mapUid(uid);
        if (!mActiveEvents.updateState(code, name, uid, 0)) {
            return;
        }
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        addHistoryEventLocked(elapsedRealtime, uptime, code, name, uid);
    }

    public void noteCurrentTimeChangedLocked() {
        final long currentTime = System.currentTimeMillis();
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        recordCurrentTimeChangeLocked(currentTime, elapsedRealtime, uptime);
    }

    public void noteProcessStartLocked(String name, int uid) {
        uid = mapUid(uid);
        if (isOnBattery()) {
            Uid u = getUidStatsLocked(uid);
            u.getProcessStatsLocked(name).incStartsLocked();
        }
        if (!mActiveEvents.updateState(HistoryItem.EVENT_PROC_START, name, uid, 0)) {
            return;
        }
        if (!mRecordAllHistory) {
            return;
        }
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        addHistoryEventLocked(elapsedRealtime, uptime, HistoryItem.EVENT_PROC_START, name, uid);
    }

    public void noteProcessCrashLocked(String name, int uid) {
        uid = mapUid(uid);
        if (isOnBattery()) {
            Uid u = getUidStatsLocked(uid);
            u.getProcessStatsLocked(name).incNumCrashesLocked();
        }
    }

    public void noteProcessAnrLocked(String name, int uid) {
        uid = mapUid(uid);
        if (isOnBattery()) {
            Uid u = getUidStatsLocked(uid);
            u.getProcessStatsLocked(name).incNumAnrsLocked();
        }
    }

    public void noteUidProcessStateLocked(int uid, int state) {
        int parentUid = mapUid(uid);
        if (uid != parentUid) {
            // Isolated UIDs process state is already rolled up into parent, so no need to track
            // Otherwise the parent's process state will get downgraded incorrectly
            return;
        }
        getUidStatsLocked(uid).updateUidProcessStateLocked(state);
    }

    public void noteProcessFinishLocked(String name, int uid) {
        uid = mapUid(uid);
        if (!mActiveEvents.updateState(HistoryItem.EVENT_PROC_FINISH, name, uid, 0)) {
            return;
        }
        if (!mRecordAllHistory) {
            return;
        }
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        addHistoryEventLocked(elapsedRealtime, uptime, HistoryItem.EVENT_PROC_FINISH, name, uid);
    }

    public void noteSyncStartLocked(String name, int uid) {
        uid = mapUid(uid);
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        getUidStatsLocked(uid).noteStartSyncLocked(name, elapsedRealtime);
        if (!mActiveEvents.updateState(HistoryItem.EVENT_SYNC_START, name, uid, 0)) {
            return;
        }
        addHistoryEventLocked(elapsedRealtime, uptime, HistoryItem.EVENT_SYNC_START, name, uid);
    }

    public void noteSyncFinishLocked(String name, int uid) {
        uid = mapUid(uid);
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        getUidStatsLocked(uid).noteStopSyncLocked(name, elapsedRealtime);
        if (!mActiveEvents.updateState(HistoryItem.EVENT_SYNC_FINISH, name, uid, 0)) {
            return;
        }
        addHistoryEventLocked(elapsedRealtime, uptime, HistoryItem.EVENT_SYNC_FINISH, name, uid);
    }

    public void noteJobStartLocked(String name, int uid) {
        uid = mapUid(uid);
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        getUidStatsLocked(uid).noteStartJobLocked(name, elapsedRealtime);
        if (!mActiveEvents.updateState(HistoryItem.EVENT_JOB_START, name, uid, 0)) {
            return;
        }
        addHistoryEventLocked(elapsedRealtime, uptime, HistoryItem.EVENT_JOB_START, name, uid);
    }

    public void noteJobFinishLocked(String name, int uid, int stopReason) {
        uid = mapUid(uid);
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        getUidStatsLocked(uid).noteStopJobLocked(name, elapsedRealtime, stopReason);
        if (!mActiveEvents.updateState(HistoryItem.EVENT_JOB_FINISH, name, uid, 0)) {
            return;
        }
        addHistoryEventLocked(elapsedRealtime, uptime, HistoryItem.EVENT_JOB_FINISH, name, uid);
    }

    public void noteJobsDeferredLocked(int uid, int numDeferred, long sinceLast) {
        uid = mapUid(uid);
        getUidStatsLocked(uid).noteJobsDeferredLocked(numDeferred, sinceLast);
    }

    public void noteAlarmStartLocked(String name, WorkSource workSource, int uid) {
        noteAlarmStartOrFinishLocked(HistoryItem.EVENT_ALARM_START, name, workSource, uid);
    }

    public void noteAlarmFinishLocked(String name, WorkSource workSource, int uid) {
        noteAlarmStartOrFinishLocked(HistoryItem.EVENT_ALARM_FINISH, name, workSource, uid);
    }

    private void noteAlarmStartOrFinishLocked(int historyItem, String name, WorkSource workSource,
            int uid) {
        if (!mRecordAllHistory) {
            return;
        }

        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();

        if (workSource != null) {
            for (int i = 0; i < workSource.size(); ++i) {
                uid = mapUid(workSource.get(i));
                if (mActiveEvents.updateState(historyItem, name, uid, 0)) {
                    addHistoryEventLocked(elapsedRealtime, uptime, historyItem, name, uid);
                }
            }

            List<WorkChain> workChains = workSource.getWorkChains();
            if (workChains != null) {
                for (int i = 0; i < workChains.size(); ++i) {
                    uid = mapUid(workChains.get(i).getAttributionUid());
                    if (mActiveEvents.updateState(historyItem, name, uid, 0)) {
                        addHistoryEventLocked(elapsedRealtime, uptime, historyItem, name, uid);
                    }
                }
            }
        } else {
            uid = mapUid(uid);

            if (mActiveEvents.updateState(historyItem, name, uid, 0)) {
                addHistoryEventLocked(elapsedRealtime, uptime, historyItem, name, uid);
            }
        }
    }

    public void noteWakupAlarmLocked(String packageName, int uid, WorkSource workSource,
            String tag) {
        if (workSource != null) {
            for (int i = 0; i < workSource.size(); ++i) {
                uid = workSource.get(i);
                final String workSourceName = workSource.getName(i);

                if (isOnBattery()) {
                    BatteryStatsImpl.Uid.Pkg pkg = getPackageStatsLocked(uid,
                            workSourceName != null ? workSourceName : packageName);
                    pkg.noteWakeupAlarmLocked(tag);
                }
            }

            ArrayList<WorkChain> workChains = workSource.getWorkChains();
            if (workChains != null) {
                for (int i = 0; i < workChains.size(); ++i) {
                    final WorkChain wc = workChains.get(i);
                    uid = wc.getAttributionUid();

                    if (isOnBattery()) {
                        BatteryStatsImpl.Uid.Pkg pkg = getPackageStatsLocked(uid, packageName);
                        pkg.noteWakeupAlarmLocked(tag);
                    }
                }
            }
        } else {
            if (isOnBattery()) {
                BatteryStatsImpl.Uid.Pkg pkg = getPackageStatsLocked(uid, packageName);
                pkg.noteWakeupAlarmLocked(tag);
            }
        }
    }

    private void requestWakelockCpuUpdate() {
        mExternalSync.scheduleCpuSyncDueToWakelockChange(DELAY_UPDATE_WAKELOCKS);
    }

    private void requestImmediateCpuUpdate() {
        mExternalSync.scheduleCpuSyncDueToWakelockChange(0 /* delayMillis */);
    }

    public void setRecordAllHistoryLocked(boolean enabled) {
        mRecordAllHistory = enabled;
        if (!enabled) {
            // Clear out any existing state.
            mActiveEvents.removeEvents(HistoryItem.EVENT_WAKE_LOCK);
            mActiveEvents.removeEvents(HistoryItem.EVENT_ALARM);
            // Record the currently running processes as stopping, now that we are no
            // longer tracking them.
            HashMap<String, SparseIntArray> active = mActiveEvents.getStateForEvent(
                    HistoryItem.EVENT_PROC);
            if (active != null) {
                long mSecRealtime = mClocks.elapsedRealtime();
                final long mSecUptime = mClocks.uptimeMillis();
                for (HashMap.Entry<String, SparseIntArray> ent : active.entrySet()) {
                    SparseIntArray uids = ent.getValue();
                    for (int j=0; j<uids.size(); j++) {
                        addHistoryEventLocked(mSecRealtime, mSecUptime,
                                HistoryItem.EVENT_PROC_FINISH, ent.getKey(), uids.keyAt(j));
                    }
                }
            }
        } else {
            // Record the currently running processes as starting, now that we are tracking them.
            HashMap<String, SparseIntArray> active = mActiveEvents.getStateForEvent(
                    HistoryItem.EVENT_PROC);
            if (active != null) {
                long mSecRealtime = mClocks.elapsedRealtime();
                final long mSecUptime = mClocks.uptimeMillis();
                for (HashMap.Entry<String, SparseIntArray> ent : active.entrySet()) {
                    SparseIntArray uids = ent.getValue();
                    for (int j=0; j<uids.size(); j++) {
                        addHistoryEventLocked(mSecRealtime, mSecUptime,
                                HistoryItem.EVENT_PROC_START, ent.getKey(), uids.keyAt(j));
                    }
                }
            }
        }
    }

    public void setNoAutoReset(boolean enabled) {
        mNoAutoReset = enabled;
    }

    public void setPretendScreenOff(boolean pretendScreenOff) {
        if (mPretendScreenOff != pretendScreenOff) {
            mPretendScreenOff = pretendScreenOff;
            noteScreenStateLocked(pretendScreenOff ? Display.STATE_OFF : Display.STATE_ON);
        }
    }

    private String mInitialAcquireWakeName;
    private int mInitialAcquireWakeUid = -1;

    public void noteStartWakeLocked(int uid, int pid, WorkChain wc, String name, String historyName,
        int type, boolean unimportantForLogging, long elapsedRealtime, long uptime) {
        uid = mapUid(uid);
        if (type == WAKE_TYPE_PARTIAL) {
            // Only care about partial wake locks, since full wake locks
            // will be canceled when the user puts the screen to sleep.
            aggregateLastWakeupUptimeLocked(uptime);
            if (historyName == null) {
                historyName = name;
            }
            if (mRecordAllHistory) {
                if (mActiveEvents.updateState(HistoryItem.EVENT_WAKE_LOCK_START, historyName,
                        uid, 0)) {
                    addHistoryEventLocked(elapsedRealtime, uptime,
                            HistoryItem.EVENT_WAKE_LOCK_START, historyName, uid);
                }
            }
            if (mWakeLockNesting == 0) {
                mHistoryCur.states |= HistoryItem.STATE_WAKE_LOCK_FLAG;
                if (DEBUG_HISTORY) Slog.v(TAG, "Start wake lock to: "
                        + Integer.toHexString(mHistoryCur.states));
                mHistoryCur.wakelockTag = mHistoryCur.localWakelockTag;
                mHistoryCur.wakelockTag.string = mInitialAcquireWakeName = historyName;
                mHistoryCur.wakelockTag.uid = mInitialAcquireWakeUid = uid;
                mWakeLockImportant = !unimportantForLogging;
                addHistoryRecordLocked(elapsedRealtime, uptime);
            } else if (!mWakeLockImportant && !unimportantForLogging
                    && mHistoryLastWritten.cmd == HistoryItem.CMD_UPDATE) {
                if (mHistoryLastWritten.wakelockTag != null) {
                    // We'll try to update the last tag.
                    mHistoryLastWritten.wakelockTag = null;
                    mHistoryCur.wakelockTag = mHistoryCur.localWakelockTag;
                    mHistoryCur.wakelockTag.string = mInitialAcquireWakeName = historyName;
                    mHistoryCur.wakelockTag.uid = mInitialAcquireWakeUid = uid;
                    addHistoryRecordLocked(elapsedRealtime, uptime);
                }
                mWakeLockImportant = true;
            }
            mWakeLockNesting++;
        }
        if (uid >= 0) {
            if (mOnBatteryScreenOffTimeBase.isRunning()) {
                // We only update the cpu time when a wake lock is acquired if the screen is off.
                // If the screen is on, we don't distribute the power amongst partial wakelocks.
                if (DEBUG_ENERGY_CPU) {
                    Slog.d(TAG, "Updating cpu time because of +wake_lock");
                }
                requestWakelockCpuUpdate();
            }

            getUidStatsLocked(uid).noteStartWakeLocked(pid, name, type, elapsedRealtime);

            if (wc != null) {
                StatsLog.write(StatsLog.WAKELOCK_STATE_CHANGED, wc.getUids(), wc.getTags(),
                        getPowerManagerWakeLockLevel(type), name,
                        StatsLog.WAKELOCK_STATE_CHANGED__STATE__ACQUIRE);
            } else {
                StatsLog.write_non_chained(StatsLog.WAKELOCK_STATE_CHANGED, uid, null,
                        getPowerManagerWakeLockLevel(type), name,
                        StatsLog.WAKELOCK_STATE_CHANGED__STATE__ACQUIRE);
            }
        }
    }

    public void noteStopWakeLocked(int uid, int pid, WorkChain wc, String name, String historyName,
            int type, long elapsedRealtime, long uptime) {
        uid = mapUid(uid);
        if (type == WAKE_TYPE_PARTIAL) {
            mWakeLockNesting--;
            if (mRecordAllHistory) {
                if (historyName == null) {
                    historyName = name;
                }
                if (mActiveEvents.updateState(HistoryItem.EVENT_WAKE_LOCK_FINISH, historyName,
                        uid, 0)) {
                    addHistoryEventLocked(elapsedRealtime, uptime,
                            HistoryItem.EVENT_WAKE_LOCK_FINISH, historyName, uid);
                }
            }
            if (mWakeLockNesting == 0) {
                mHistoryCur.states &= ~HistoryItem.STATE_WAKE_LOCK_FLAG;
                if (DEBUG_HISTORY) Slog.v(TAG, "Stop wake lock to: "
                        + Integer.toHexString(mHistoryCur.states));
                mInitialAcquireWakeName = null;
                mInitialAcquireWakeUid = -1;
                addHistoryRecordLocked(elapsedRealtime, uptime);
            }
        }
        if (uid >= 0) {
            if (mOnBatteryScreenOffTimeBase.isRunning()) {
                if (DEBUG_ENERGY_CPU) {
                    Slog.d(TAG, "Updating cpu time because of -wake_lock");
                }
                requestWakelockCpuUpdate();
            }

            getUidStatsLocked(uid).noteStopWakeLocked(pid, name, type, elapsedRealtime);
            if (wc != null) {
                StatsLog.write(StatsLog.WAKELOCK_STATE_CHANGED, wc.getUids(), wc.getTags(),
                        getPowerManagerWakeLockLevel(type), name,
                        StatsLog.WAKELOCK_STATE_CHANGED__STATE__RELEASE);
            } else {
                StatsLog.write_non_chained(StatsLog.WAKELOCK_STATE_CHANGED, uid, null,
                        getPowerManagerWakeLockLevel(type), name,
                        StatsLog.WAKELOCK_STATE_CHANGED__STATE__RELEASE);
            }
        }
    }

    /**
     * Converts BatteryStats wakelock types back into PowerManager wakelock levels.
     * This is the inverse map of Notifier.getBatteryStatsWakeLockMonitorType().
     * These are estimations, since batterystats loses some of the original data.
     * TODO: Delete this. Instead, StatsLog.write should be called from PowerManager's Notifier.
     */
    private int getPowerManagerWakeLockLevel(int battertStatsWakelockType) {
        switch (battertStatsWakelockType) {
            // PowerManager.PARTIAL_WAKE_LOCK or PROXIMITY_SCREEN_OFF_WAKE_LOCK
            case BatteryStats.WAKE_TYPE_PARTIAL:
                return PowerManager.PARTIAL_WAKE_LOCK;

            // PowerManager.SCREEN_DIM_WAKE_LOCK or SCREEN_BRIGHT_WAKE_LOCK
            case BatteryStats.WAKE_TYPE_FULL:
                return PowerManager.FULL_WAKE_LOCK;

            case BatteryStats.WAKE_TYPE_DRAW:
                return PowerManager.DRAW_WAKE_LOCK;

            // It appears that nothing can ever make a Window and PowerManager lacks an equivalent.
            case BatteryStats.WAKE_TYPE_WINDOW:
                Slog.e(TAG, "Illegal window wakelock type observed in batterystats.");
                return -1;

            default:
                Slog.e(TAG, "Illegal wakelock type in batterystats: " + battertStatsWakelockType);
                return -1;
        }
    }

    public void noteStartWakeFromSourceLocked(WorkSource ws, int pid, String name,
            String historyName, int type, boolean unimportantForLogging) {
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        final int N = ws.size();
        for (int i=0; i<N; i++) {
            noteStartWakeLocked(ws.get(i), pid, null, name, historyName, type,
                    unimportantForLogging, elapsedRealtime, uptime);
        }

        List<WorkChain> wcs = ws.getWorkChains();
        if (wcs != null) {
            for (int i = 0; i < wcs.size(); ++i) {
                final WorkChain wc = wcs.get(i);
                noteStartWakeLocked(wc.getAttributionUid(), pid, wc, name, historyName, type,
                        unimportantForLogging, elapsedRealtime, uptime);
            }
        }
    }

    public void noteChangeWakelockFromSourceLocked(WorkSource ws, int pid, String name,
            String historyName, int type, WorkSource newWs, int newPid, String newName,
            String newHistoryName, int newType, boolean newUnimportantForLogging) {
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();

        List<WorkChain>[] wcs = WorkSource.diffChains(ws, newWs);

        // For correct semantics, we start the need worksources first, so that we won't
        // make inappropriate history items as if all wake locks went away and new ones
        // appeared.  This is okay because tracking of wake locks allows nesting.
        //
        // First the starts :
        final int NN = newWs.size();
        for (int i=0; i<NN; i++) {
            noteStartWakeLocked(newWs.get(i), newPid, null, newName, newHistoryName, newType,
                    newUnimportantForLogging, elapsedRealtime, uptime);
        }
        if (wcs != null) {
            List<WorkChain> newChains = wcs[0];
            if (newChains != null) {
                for (int i = 0; i < newChains.size(); ++i) {
                    final WorkChain newChain = newChains.get(i);
                    noteStartWakeLocked(newChain.getAttributionUid(), newPid, newChain, newName,
                        newHistoryName, newType, newUnimportantForLogging, elapsedRealtime,
                        uptime);
                }
            }
        }

        // Then the stops :
        final int NO = ws.size();
        for (int i=0; i<NO; i++) {
            noteStopWakeLocked(ws.get(i), pid, null, name, historyName, type, elapsedRealtime,
                    uptime);
        }
        if (wcs != null) {
            List<WorkChain> goneChains = wcs[1];
            if (goneChains != null) {
                for (int i = 0; i < goneChains.size(); ++i) {
                    final WorkChain goneChain = goneChains.get(i);
                    noteStopWakeLocked(goneChain.getAttributionUid(), pid, goneChain, name,
                            historyName, type, elapsedRealtime, uptime);
                }
            }
        }
    }

    public void noteStopWakeFromSourceLocked(WorkSource ws, int pid, String name,
            String historyName, int type) {
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        final int N = ws.size();
        for (int i=0; i<N; i++) {
            noteStopWakeLocked(ws.get(i), pid, null, name, historyName, type, elapsedRealtime,
                    uptime);
        }

        List<WorkChain> wcs = ws.getWorkChains();
        if (wcs != null) {
            for (int i = 0; i < wcs.size(); ++i) {
                final WorkChain wc = wcs.get(i);
                noteStopWakeLocked(wc.getAttributionUid(), pid, wc, name, historyName, type,
                        elapsedRealtime, uptime);
            }
        }
    }

    public void noteLongPartialWakelockStart(String name, String historyName, int uid) {
        uid = mapUid(uid);
        noteLongPartialWakeLockStartInternal(name, historyName, uid);
    }

    public void noteLongPartialWakelockStartFromSource(String name, String historyName,
            WorkSource workSource) {
        final int N = workSource.size();
        for (int i = 0; i < N; ++i) {
            final int uid = mapUid(workSource.get(i));
            noteLongPartialWakeLockStartInternal(name, historyName, uid);
        }

        final ArrayList<WorkChain> workChains = workSource.getWorkChains();
        if (workChains != null) {
            for (int i = 0; i < workChains.size(); ++i) {
                final WorkChain workChain = workChains.get(i);
                final int uid = workChain.getAttributionUid();
                noteLongPartialWakeLockStartInternal(name, historyName, uid);
            }
        }
    }

    private void noteLongPartialWakeLockStartInternal(String name, String historyName, int uid) {
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        if (historyName == null) {
            historyName = name;
        }
        if (!mActiveEvents.updateState(HistoryItem.EVENT_LONG_WAKE_LOCK_START, historyName, uid,
                0)) {
            return;
        }
        addHistoryEventLocked(elapsedRealtime, uptime, HistoryItem.EVENT_LONG_WAKE_LOCK_START,
                historyName, uid);
    }

    public void noteLongPartialWakelockFinish(String name, String historyName, int uid) {
        uid = mapUid(uid);
        noteLongPartialWakeLockFinishInternal(name, historyName, uid);
    }

    public void noteLongPartialWakelockFinishFromSource(String name, String historyName,
            WorkSource workSource) {
        final int N = workSource.size();
        for (int i = 0; i < N; ++i) {
            final int uid = mapUid(workSource.get(i));
            noteLongPartialWakeLockFinishInternal(name, historyName, uid);
        }

        final ArrayList<WorkChain> workChains = workSource.getWorkChains();
        if (workChains != null) {
            for (int i = 0; i < workChains.size(); ++i) {
                final WorkChain workChain = workChains.get(i);
                final int uid = workChain.getAttributionUid();
                noteLongPartialWakeLockFinishInternal(name, historyName, uid);
            }
        }
    }

    private void noteLongPartialWakeLockFinishInternal(String name, String historyName, int uid) {
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        if (historyName == null) {
            historyName = name;
        }
        if (!mActiveEvents.updateState(HistoryItem.EVENT_LONG_WAKE_LOCK_FINISH, historyName, uid,
                0)) {
            return;
        }
        addHistoryEventLocked(elapsedRealtime, uptime, HistoryItem.EVENT_LONG_WAKE_LOCK_FINISH,
                historyName, uid);
    }

    void aggregateLastWakeupUptimeLocked(long uptimeMs) {
        if (mLastWakeupReason != null) {
            long deltaUptime = uptimeMs - mLastWakeupUptimeMs;
            SamplingTimer timer = getWakeupReasonTimerLocked(mLastWakeupReason);
            timer.add(deltaUptime * 1000, 1); // time in in microseconds
            StatsLog.write(StatsLog.KERNEL_WAKEUP_REPORTED, mLastWakeupReason,
                    /* duration_usec */ deltaUptime * 1000);
            mLastWakeupReason = null;
        }
    }

    public void noteWakeupReasonLocked(String reason) {
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        if (DEBUG_HISTORY) Slog.v(TAG, "Wakeup reason \"" + reason +"\": "
                + Integer.toHexString(mHistoryCur.states));
        aggregateLastWakeupUptimeLocked(uptime);
        mHistoryCur.wakeReasonTag = mHistoryCur.localWakeReasonTag;
        mHistoryCur.wakeReasonTag.string = reason;
        mHistoryCur.wakeReasonTag.uid = 0;
        mLastWakeupReason = reason;
        mLastWakeupUptimeMs = uptime;
        addHistoryRecordLocked(elapsedRealtime, uptime);
    }

    public boolean startAddingCpuLocked() {
        mExternalSync.cancelCpuSyncDueToWakelockChange();
        return mOnBatteryInternal;
    }

    public void finishAddingCpuLocked(int totalUTime, int totalSTime, int statUserTime,
                                      int statSystemTime, int statIOWaitTime, int statIrqTime,
                                      int statSoftIrqTime, int statIdleTime) {
        if (DEBUG) Slog.d(TAG, "Adding cpu: tuser=" + totalUTime + " tsys=" + totalSTime
                + " user=" + statUserTime + " sys=" + statSystemTime
                + " io=" + statIOWaitTime + " irq=" + statIrqTime
                + " sirq=" + statSoftIrqTime + " idle=" + statIdleTime);
        mCurStepCpuUserTime += totalUTime;
        mCurStepCpuSystemTime += totalSTime;
        mCurStepStatUserTime += statUserTime;
        mCurStepStatSystemTime += statSystemTime;
        mCurStepStatIOWaitTime += statIOWaitTime;
        mCurStepStatIrqTime += statIrqTime;
        mCurStepStatSoftIrqTime += statSoftIrqTime;
        mCurStepStatIdleTime += statIdleTime;
    }

    public void noteProcessDiedLocked(int uid, int pid) {
        uid = mapUid(uid);
        Uid u = mUidStats.get(uid);
        if (u != null) {
            u.mPids.remove(pid);
        }
    }

    public long getProcessWakeTime(int uid, int pid, long realtime) {
        uid = mapUid(uid);
        Uid u = mUidStats.get(uid);
        if (u != null) {
            Uid.Pid p = u.mPids.get(pid);
            if (p != null) {
                return p.mWakeSumMs + (p.mWakeNesting > 0 ? (realtime - p.mWakeStartMs) : 0);
            }
        }
        return 0;
    }

    public void reportExcessiveCpuLocked(int uid, String proc, long overTime, long usedTime) {
        uid = mapUid(uid);
        Uid u = mUidStats.get(uid);
        if (u != null) {
            u.reportExcessiveCpuLocked(proc, overTime, usedTime);
        }
    }

    int mSensorNesting;

    public void noteStartSensorLocked(int uid, int sensor) {
        uid = mapUid(uid);
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        if (mSensorNesting == 0) {
            mHistoryCur.states |= HistoryItem.STATE_SENSOR_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Start sensor to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtime, uptime);
        }
        mSensorNesting++;
        getUidStatsLocked(uid).noteStartSensor(sensor, elapsedRealtime);
    }

    public void noteStopSensorLocked(int uid, int sensor) {
        uid = mapUid(uid);
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        mSensorNesting--;
        if (mSensorNesting == 0) {
            mHistoryCur.states &= ~HistoryItem.STATE_SENSOR_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Stop sensor to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtime, uptime);
        }
        getUidStatsLocked(uid).noteStopSensor(sensor, elapsedRealtime);
    }

    int mGpsNesting;

    public void noteGpsChangedLocked(WorkSource oldWs, WorkSource newWs) {
        for (int i = 0; i < newWs.size(); ++i) {
            noteStartGpsLocked(newWs.get(i), null);
        }

        for (int i = 0; i < oldWs.size(); ++i) {
            noteStopGpsLocked((oldWs.get(i)), null);
        }

        List<WorkChain>[] wcs = WorkSource.diffChains(oldWs, newWs);
        if (wcs != null) {
            if (wcs[0] != null) {
                final List<WorkChain> newChains = wcs[0];
                for (int i = 0; i < newChains.size(); ++i) {
                    noteStartGpsLocked(-1, newChains.get(i));
                }
            }

            if (wcs[1] != null) {
                final List<WorkChain> goneChains = wcs[1];
                for (int i = 0; i < goneChains.size(); ++i) {
                    noteStopGpsLocked(-1, goneChains.get(i));
                }
            }
        }
    }

    private void noteStartGpsLocked(int uid, WorkChain workChain) {
        uid = getAttributionUid(uid, workChain);
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        if (mGpsNesting == 0) {
            mHistoryCur.states |= HistoryItem.STATE_GPS_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Start GPS to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtime, uptime);
        }
        mGpsNesting++;

        if (workChain == null) {
            StatsLog.write_non_chained(StatsLog.GPS_SCAN_STATE_CHANGED, uid, null,
                    StatsLog.GPS_SCAN_STATE_CHANGED__STATE__ON);
        } else {
            StatsLog.write(StatsLog.GPS_SCAN_STATE_CHANGED,
                    workChain.getUids(), workChain.getTags(),
                    StatsLog.GPS_SCAN_STATE_CHANGED__STATE__ON);
        }

        getUidStatsLocked(uid).noteStartGps(elapsedRealtime);
    }

    private void noteStopGpsLocked(int uid, WorkChain workChain) {
        uid = getAttributionUid(uid, workChain);
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        mGpsNesting--;
        if (mGpsNesting == 0) {
            mHistoryCur.states &= ~HistoryItem.STATE_GPS_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Stop GPS to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtime, uptime);
            stopAllGpsSignalQualityTimersLocked(-1);
            mGpsSignalQualityBin = -1;
        }

        if (workChain == null) {
            StatsLog.write_non_chained(StatsLog.GPS_SCAN_STATE_CHANGED, uid, null,
                    StatsLog.GPS_SCAN_STATE_CHANGED__STATE__OFF);
        } else {
            StatsLog.write(StatsLog.GPS_SCAN_STATE_CHANGED, workChain.getUids(),
                    workChain.getTags(), StatsLog.GPS_SCAN_STATE_CHANGED__STATE__OFF);
        }

        getUidStatsLocked(uid).noteStopGps(elapsedRealtime);
    }

    public void noteGpsSignalQualityLocked(int signalLevel) {
        if (mGpsNesting == 0) {
            return;
        }
        if (signalLevel < 0 || signalLevel >= GnssMetrics.NUM_GPS_SIGNAL_QUALITY_LEVELS) {
            stopAllGpsSignalQualityTimersLocked(-1);
            return;
        }
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        if (mGpsSignalQualityBin != signalLevel) {
            if (mGpsSignalQualityBin >= 0) {
                mGpsSignalQualityTimer[mGpsSignalQualityBin].stopRunningLocked(elapsedRealtime);
            }
            if(!mGpsSignalQualityTimer[signalLevel].isRunningLocked()) {
                mGpsSignalQualityTimer[signalLevel].startRunningLocked(elapsedRealtime);
            }
            mHistoryCur.states2 = (mHistoryCur.states2&~HistoryItem.STATE2_GPS_SIGNAL_QUALITY_MASK)
                    | (signalLevel << HistoryItem.STATE2_GPS_SIGNAL_QUALITY_SHIFT);
            addHistoryRecordLocked(elapsedRealtime, uptime);
            mGpsSignalQualityBin = signalLevel;
        }
        return;
    }

    @GuardedBy("this")
    public void noteScreenStateLocked(int state) {
        state = mPretendScreenOff ? Display.STATE_OFF : state;

        // Battery stats relies on there being 4 states. To accommodate this, new states beyond the
        // original 4 are mapped to one of the originals.
        if (state > MAX_TRACKED_SCREEN_STATE) {
            switch (state) {
                case Display.STATE_VR:
                    state = Display.STATE_ON;
                    break;
                default:
                    Slog.wtf(TAG, "Unknown screen state (not mapped): " + state);
                    break;
            }
        }

        if (mScreenState != state) {
            recordDailyStatsIfNeededLocked(true);
            final int oldState = mScreenState;
            mScreenState = state;
            if (DEBUG) Slog.v(TAG, "Screen state: oldState=" + Display.stateToString(oldState)
                    + ", newState=" + Display.stateToString(state));

            if (state != Display.STATE_UNKNOWN) {
                int stepState = state-1;
                if ((stepState & STEP_LEVEL_MODE_SCREEN_STATE) == stepState) {
                    mModStepMode |= (mCurStepMode & STEP_LEVEL_MODE_SCREEN_STATE) ^ stepState;
                    mCurStepMode = (mCurStepMode & ~STEP_LEVEL_MODE_SCREEN_STATE) | stepState;
                } else {
                    Slog.wtf(TAG, "Unexpected screen state: " + state);
                }
            }

            final long elapsedRealtime = mClocks.elapsedRealtime();
            final long uptime = mClocks.uptimeMillis();

            boolean updateHistory = false;
            if (isScreenDoze(state)) {
                mHistoryCur.states |= HistoryItem.STATE_SCREEN_DOZE_FLAG;
                mScreenDozeTimer.startRunningLocked(elapsedRealtime);
                updateHistory = true;
            } else if (isScreenDoze(oldState)) {
                mHistoryCur.states &= ~HistoryItem.STATE_SCREEN_DOZE_FLAG;
                mScreenDozeTimer.stopRunningLocked(elapsedRealtime);
                updateHistory = true;
            }
            if (isScreenOn(state)) {
                mHistoryCur.states |= HistoryItem.STATE_SCREEN_ON_FLAG;
                if (DEBUG_HISTORY) Slog.v(TAG, "Screen on to: "
                        + Integer.toHexString(mHistoryCur.states));
                mScreenOnTimer.startRunningLocked(elapsedRealtime);
                if (mScreenBrightnessBin >= 0) {
                    mScreenBrightnessTimer[mScreenBrightnessBin].startRunningLocked(elapsedRealtime);
                }
                updateHistory = true;
            } else if (isScreenOn(oldState)) {
                mHistoryCur.states &= ~HistoryItem.STATE_SCREEN_ON_FLAG;
                if (DEBUG_HISTORY) Slog.v(TAG, "Screen off to: "
                        + Integer.toHexString(mHistoryCur.states));
                mScreenOnTimer.stopRunningLocked(elapsedRealtime);
                if (mScreenBrightnessBin >= 0) {
                    mScreenBrightnessTimer[mScreenBrightnessBin].stopRunningLocked(elapsedRealtime);
                }
                updateHistory = true;
            }
            if (updateHistory) {
                if (DEBUG_HISTORY) Slog.v(TAG, "Screen state to: "
                        + Display.stateToString(state));
                addHistoryRecordLocked(elapsedRealtime, uptime);
            }
            mExternalSync.scheduleCpuSyncDueToScreenStateChange(
                    mOnBatteryTimeBase.isRunning(), mOnBatteryScreenOffTimeBase.isRunning());
            if (isScreenOn(state)) {
                updateTimeBasesLocked(mOnBatteryTimeBase.isRunning(), state,
                        mClocks.uptimeMillis() * 1000, elapsedRealtime * 1000);
                // Fake a wake lock, so we consider the device waked as long as the screen is on.
                noteStartWakeLocked(-1, -1, null, "screen", null, WAKE_TYPE_PARTIAL, false,
                        elapsedRealtime, uptime);
            } else if (isScreenOn(oldState)) {
                noteStopWakeLocked(-1, -1, null, "screen", "screen", WAKE_TYPE_PARTIAL,
                        elapsedRealtime, uptime);
                updateTimeBasesLocked(mOnBatteryTimeBase.isRunning(), state,
                        mClocks.uptimeMillis() * 1000, elapsedRealtime * 1000);
            }
            // Update discharge amounts.
            if (mOnBatteryInternal) {
                updateDischargeScreenLevelsLocked(oldState, state);
            }
        }
    }

    @UnsupportedAppUsage
    public void noteScreenBrightnessLocked(int brightness) {
        // Bin the brightness.
        int bin = brightness / (256/NUM_SCREEN_BRIGHTNESS_BINS);
        if (bin < 0) bin = 0;
        else if (bin >= NUM_SCREEN_BRIGHTNESS_BINS) bin = NUM_SCREEN_BRIGHTNESS_BINS-1;
        if (mScreenBrightnessBin != bin) {
            final long elapsedRealtime = mClocks.elapsedRealtime();
            final long uptime = mClocks.uptimeMillis();
            mHistoryCur.states = (mHistoryCur.states&~HistoryItem.STATE_BRIGHTNESS_MASK)
                    | (bin << HistoryItem.STATE_BRIGHTNESS_SHIFT);
            if (DEBUG_HISTORY) Slog.v(TAG, "Screen brightness " + bin + " to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtime, uptime);
            if (mScreenState == Display.STATE_ON) {
                if (mScreenBrightnessBin >= 0) {
                    mScreenBrightnessTimer[mScreenBrightnessBin].stopRunningLocked(elapsedRealtime);
                }
                mScreenBrightnessTimer[bin].startRunningLocked(elapsedRealtime);
            }
            mScreenBrightnessBin = bin;
        }
    }

    @UnsupportedAppUsage
    public void noteUserActivityLocked(int uid, int event) {
        if (mOnBatteryInternal) {
            uid = mapUid(uid);
            getUidStatsLocked(uid).noteUserActivityLocked(event);
        }
    }

    public void noteWakeUpLocked(String reason, int reasonUid) {
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        addHistoryEventLocked(elapsedRealtime, uptime, HistoryItem.EVENT_SCREEN_WAKE_UP,
                reason, reasonUid);
    }

    public void noteInteractiveLocked(boolean interactive) {
        if (mInteractive != interactive) {
            final long elapsedRealtime = mClocks.elapsedRealtime();
            mInteractive = interactive;
            if (DEBUG) Slog.v(TAG, "Interactive: " + interactive);
            if (interactive) {
                mInteractiveTimer.startRunningLocked(elapsedRealtime);
            } else {
                mInteractiveTimer.stopRunningLocked(elapsedRealtime);
            }
        }
    }

    public void noteConnectivityChangedLocked(int type, String extra) {
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        addHistoryEventLocked(elapsedRealtime, uptime, HistoryItem.EVENT_CONNECTIVITY_CHANGED,
                extra, type);
        mNumConnectivityChange++;
    }

    private void noteMobileRadioApWakeupLocked(final long elapsedRealtimeMillis,
            final long uptimeMillis, int uid) {
        uid = mapUid(uid);
        addHistoryEventLocked(elapsedRealtimeMillis, uptimeMillis, HistoryItem.EVENT_WAKEUP_AP, "",
                uid);
        getUidStatsLocked(uid).noteMobileRadioApWakeupLocked();
    }

    /**
     * Updates the radio power state and returns true if an external stats collection should occur.
     */
    public boolean noteMobileRadioPowerStateLocked(int powerState, long timestampNs, int uid) {
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        if (mMobileRadioPowerState != powerState) {
            long realElapsedRealtimeMs;
            final boolean active =
                    powerState == DataConnectionRealTimeInfo.DC_POWER_STATE_MEDIUM
                            || powerState == DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH;
            if (active) {
                if (uid > 0) {
                    noteMobileRadioApWakeupLocked(elapsedRealtime, uptime, uid);
                }

                mMobileRadioActiveStartTime = realElapsedRealtimeMs = timestampNs / (1000 * 1000);
                mHistoryCur.states |= HistoryItem.STATE_MOBILE_RADIO_ACTIVE_FLAG;
            } else {
                realElapsedRealtimeMs = timestampNs / (1000*1000);
                long lastUpdateTimeMs = mMobileRadioActiveStartTime;
                if (realElapsedRealtimeMs < lastUpdateTimeMs) {
                    Slog.wtf(TAG, "Data connection inactive timestamp " + realElapsedRealtimeMs
                            + " is before start time " + lastUpdateTimeMs);
                    realElapsedRealtimeMs = elapsedRealtime;
                } else if (realElapsedRealtimeMs < elapsedRealtime) {
                    mMobileRadioActiveAdjustedTime.addCountLocked(elapsedRealtime
                            - realElapsedRealtimeMs);
                }
                mHistoryCur.states &= ~HistoryItem.STATE_MOBILE_RADIO_ACTIVE_FLAG;
            }
            if (DEBUG_HISTORY) Slog.v(TAG, "Mobile network active " + active + " to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtime, uptime);
            mMobileRadioPowerState = powerState;
            if (active) {
                mMobileRadioActiveTimer.startRunningLocked(elapsedRealtime);
                mMobileRadioActivePerAppTimer.startRunningLocked(elapsedRealtime);
            } else {
                mMobileRadioActiveTimer.stopRunningLocked(realElapsedRealtimeMs);
                mMobileRadioActivePerAppTimer.stopRunningLocked(realElapsedRealtimeMs);
                // Tell the caller to collect radio network/power stats.
                return true;
            }
        }
        return false;
    }

    public void notePowerSaveModeLocked(boolean enabled) {
        if (mPowerSaveModeEnabled != enabled) {
            int stepState = enabled ? STEP_LEVEL_MODE_POWER_SAVE : 0;
            mModStepMode |= (mCurStepMode&STEP_LEVEL_MODE_POWER_SAVE) ^ stepState;
            mCurStepMode = (mCurStepMode&~STEP_LEVEL_MODE_POWER_SAVE) | stepState;
            final long elapsedRealtime = mClocks.elapsedRealtime();
            final long uptime = mClocks.uptimeMillis();
            mPowerSaveModeEnabled = enabled;
            if (enabled) {
                mHistoryCur.states2 |= HistoryItem.STATE2_POWER_SAVE_FLAG;
                if (DEBUG_HISTORY) Slog.v(TAG, "Power save mode enabled to: "
                        + Integer.toHexString(mHistoryCur.states2));
                mPowerSaveModeEnabledTimer.startRunningLocked(elapsedRealtime);
            } else {
                mHistoryCur.states2 &= ~HistoryItem.STATE2_POWER_SAVE_FLAG;
                if (DEBUG_HISTORY) Slog.v(TAG, "Power save mode disabled to: "
                        + Integer.toHexString(mHistoryCur.states2));
                mPowerSaveModeEnabledTimer.stopRunningLocked(elapsedRealtime);
            }
            addHistoryRecordLocked(elapsedRealtime, uptime);
            StatsLog.write(StatsLog.BATTERY_SAVER_MODE_STATE_CHANGED, enabled ?
                    StatsLog.BATTERY_SAVER_MODE_STATE_CHANGED__STATE__ON :
                    StatsLog.BATTERY_SAVER_MODE_STATE_CHANGED__STATE__OFF);
        }
    }

    public void noteDeviceIdleModeLocked(final int mode, String activeReason, int activeUid) {
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        boolean nowIdling = mode == DEVICE_IDLE_MODE_DEEP;
        if (mDeviceIdling && !nowIdling && activeReason == null) {
            // We don't go out of general idling mode until explicitly taken out of
            // device idle through going active or significant motion.
            nowIdling = true;
        }
        boolean nowLightIdling = mode == DEVICE_IDLE_MODE_LIGHT;
        if (mDeviceLightIdling && !nowLightIdling && !nowIdling && activeReason == null) {
            // We don't go out of general light idling mode until explicitly taken out of
            // device idle through going active or significant motion.
            nowLightIdling = true;
        }
        if (activeReason != null && (mDeviceIdling || mDeviceLightIdling)) {
            addHistoryEventLocked(elapsedRealtime, uptime, HistoryItem.EVENT_ACTIVE,
                    activeReason, activeUid);
        }
        if (mDeviceIdling != nowIdling || mDeviceLightIdling != nowLightIdling) {
            int statsmode;
            if (nowIdling)           statsmode = DEVICE_IDLE_MODE_DEEP;
            else if (nowLightIdling) statsmode = DEVICE_IDLE_MODE_LIGHT;
            else                     statsmode = DEVICE_IDLE_MODE_OFF;
            StatsLog.write(StatsLog.DEVICE_IDLING_MODE_STATE_CHANGED, statsmode);
        }
        if (mDeviceIdling != nowIdling) {
            mDeviceIdling = nowIdling;
            int stepState = nowIdling ? STEP_LEVEL_MODE_DEVICE_IDLE : 0;
            mModStepMode |= (mCurStepMode&STEP_LEVEL_MODE_DEVICE_IDLE) ^ stepState;
            mCurStepMode = (mCurStepMode&~STEP_LEVEL_MODE_DEVICE_IDLE) | stepState;
            if (nowIdling) {
                mDeviceIdlingTimer.startRunningLocked(elapsedRealtime);
            } else {
                mDeviceIdlingTimer.stopRunningLocked(elapsedRealtime);
            }
        }
        if (mDeviceLightIdling != nowLightIdling) {
            mDeviceLightIdling = nowLightIdling;
            if (nowLightIdling) {
                mDeviceLightIdlingTimer.startRunningLocked(elapsedRealtime);
            } else {
                mDeviceLightIdlingTimer.stopRunningLocked(elapsedRealtime);
            }
        }
        if (mDeviceIdleMode != mode) {
            mHistoryCur.states2 = (mHistoryCur.states2 & ~HistoryItem.STATE2_DEVICE_IDLE_MASK)
                    | (mode << HistoryItem.STATE2_DEVICE_IDLE_SHIFT);
            if (DEBUG_HISTORY) Slog.v(TAG, "Device idle mode changed to: "
                    + Integer.toHexString(mHistoryCur.states2));
            addHistoryRecordLocked(elapsedRealtime, uptime);
            long lastDuration = elapsedRealtime - mLastIdleTimeStart;
            mLastIdleTimeStart = elapsedRealtime;
            if (mDeviceIdleMode == DEVICE_IDLE_MODE_LIGHT) {
                if (lastDuration > mLongestLightIdleTime) {
                    mLongestLightIdleTime = lastDuration;
                }
                mDeviceIdleModeLightTimer.stopRunningLocked(elapsedRealtime);
            } else if (mDeviceIdleMode == DEVICE_IDLE_MODE_DEEP) {
                if (lastDuration > mLongestFullIdleTime) {
                    mLongestFullIdleTime = lastDuration;
                }
                mDeviceIdleModeFullTimer.stopRunningLocked(elapsedRealtime);
            }
            if (mode == DEVICE_IDLE_MODE_LIGHT) {
                mDeviceIdleModeLightTimer.startRunningLocked(elapsedRealtime);
            } else if (mode == DEVICE_IDLE_MODE_DEEP) {
                mDeviceIdleModeFullTimer.startRunningLocked(elapsedRealtime);
            }
            mDeviceIdleMode = mode;
            StatsLog.write(StatsLog.DEVICE_IDLE_MODE_STATE_CHANGED, mode);
        }
    }

    public void notePackageInstalledLocked(String pkgName, long versionCode) {
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        // XXX need to figure out what to do with long version codes.
        addHistoryEventLocked(elapsedRealtime, uptime, HistoryItem.EVENT_PACKAGE_INSTALLED,
                pkgName, (int)versionCode);
        PackageChange pc = new PackageChange();
        pc.mPackageName = pkgName;
        pc.mUpdate = true;
        pc.mVersionCode = versionCode;
        addPackageChange(pc);
    }

    public void notePackageUninstalledLocked(String pkgName) {
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        addHistoryEventLocked(elapsedRealtime, uptime, HistoryItem.EVENT_PACKAGE_UNINSTALLED,
                pkgName, 0);
        PackageChange pc = new PackageChange();
        pc.mPackageName = pkgName;
        pc.mUpdate = true;
        addPackageChange(pc);
    }

    private void addPackageChange(PackageChange pc) {
        if (mDailyPackageChanges == null) {
            mDailyPackageChanges = new ArrayList<>();
        }
        mDailyPackageChanges.add(pc);
    }

    void stopAllGpsSignalQualityTimersLocked(int except) {
        final long elapsedRealtime = mClocks.elapsedRealtime();
        for (int i = 0; i < GnssMetrics.NUM_GPS_SIGNAL_QUALITY_LEVELS; i++) {
            if (i == except) {
                continue;
            }
            while (mGpsSignalQualityTimer[i].isRunningLocked()) {
                mGpsSignalQualityTimer[i].stopRunningLocked(elapsedRealtime);
            }
        }
    }

    @UnsupportedAppUsage
    public void notePhoneOnLocked() {
        if (!mPhoneOn) {
            final long elapsedRealtime = mClocks.elapsedRealtime();
            final long uptime = mClocks.uptimeMillis();
            mHistoryCur.states2 |= HistoryItem.STATE2_PHONE_IN_CALL_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Phone on to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtime, uptime);
            mPhoneOn = true;
            mPhoneOnTimer.startRunningLocked(elapsedRealtime);
        }
    }

    @UnsupportedAppUsage
    public void notePhoneOffLocked() {
        if (mPhoneOn) {
            final long elapsedRealtime = mClocks.elapsedRealtime();
            final long uptime = mClocks.uptimeMillis();
            mHistoryCur.states2 &= ~HistoryItem.STATE2_PHONE_IN_CALL_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Phone off to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtime, uptime);
            mPhoneOn = false;
            mPhoneOnTimer.stopRunningLocked(elapsedRealtime);
        }
    }

    private void registerUsbStateReceiver(Context context) {
        final IntentFilter usbStateFilter = new IntentFilter();
        usbStateFilter.addAction(UsbManager.ACTION_USB_STATE);
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final boolean state = intent.getBooleanExtra(UsbManager.USB_CONNECTED, false);
                synchronized (BatteryStatsImpl.this) {
                    noteUsbConnectionStateLocked(state);
                }
            }
        }, usbStateFilter);
        synchronized (this) {
            if (mUsbDataState == USB_DATA_UNKNOWN) {
                final Intent usbState = context.registerReceiver(null, usbStateFilter);
                final boolean initState = usbState != null && usbState.getBooleanExtra(
                        UsbManager.USB_CONNECTED, false);
                noteUsbConnectionStateLocked(initState);
            }
        }
    }

    private void noteUsbConnectionStateLocked(boolean connected) {
        int newState = connected ? USB_DATA_CONNECTED : USB_DATA_DISCONNECTED;
        if (mUsbDataState != newState) {
            mUsbDataState = newState;
            if (connected) {
                mHistoryCur.states2 |= HistoryItem.STATE2_USB_DATA_LINK_FLAG;
            } else {
                mHistoryCur.states2 &= ~HistoryItem.STATE2_USB_DATA_LINK_FLAG;
            }
            addHistoryRecordLocked(mClocks.elapsedRealtime(), mClocks.uptimeMillis());
        }
    }

    void stopAllPhoneSignalStrengthTimersLocked(int except) {
        final long elapsedRealtime = mClocks.elapsedRealtime();
        for (int i = 0; i < SignalStrength.NUM_SIGNAL_STRENGTH_BINS; i++) {
            if (i == except) {
                continue;
            }
            while (mPhoneSignalStrengthsTimer[i].isRunningLocked()) {
                mPhoneSignalStrengthsTimer[i].stopRunningLocked(elapsedRealtime);
            }
        }
    }

    private int fixPhoneServiceState(int state, int signalBin) {
        if (mPhoneSimStateRaw == TelephonyManager.SIM_STATE_ABSENT) {
            // In this case we will always be STATE_OUT_OF_SERVICE, so need
            // to infer that we are scanning from other data.
            if (state == ServiceState.STATE_OUT_OF_SERVICE
                    && signalBin > SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN) {
                state = ServiceState.STATE_IN_SERVICE;
            }
        }

        return state;
    }

    private void updateAllPhoneStateLocked(int state, int simState, int strengthBin) {
        boolean scanning = false;
        boolean newHistory = false;

        mPhoneServiceStateRaw = state;
        mPhoneSimStateRaw = simState;
        mPhoneSignalStrengthBinRaw = strengthBin;

        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();

        if (simState == TelephonyManager.SIM_STATE_ABSENT) {
            // In this case we will always be STATE_OUT_OF_SERVICE, so need
            // to infer that we are scanning from other data.
            if (state == ServiceState.STATE_OUT_OF_SERVICE
                    && strengthBin > SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN) {
                state = ServiceState.STATE_IN_SERVICE;
            }
        }

        // If the phone is powered off, stop all timers.
        if (state == ServiceState.STATE_POWER_OFF) {
            strengthBin = -1;

        // If we are in service, make sure the correct signal string timer is running.
        } else if (state == ServiceState.STATE_IN_SERVICE) {
            // Bin will be changed below.

        // If we're out of service, we are in the lowest signal strength
        // bin and have the scanning bit set.
        } else if (state == ServiceState.STATE_OUT_OF_SERVICE) {
            scanning = true;
            strengthBin = SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
            if (!mPhoneSignalScanningTimer.isRunningLocked()) {
                mHistoryCur.states |= HistoryItem.STATE_PHONE_SCANNING_FLAG;
                newHistory = true;
                if (DEBUG_HISTORY) Slog.v(TAG, "Phone started scanning to: "
                        + Integer.toHexString(mHistoryCur.states));
                mPhoneSignalScanningTimer.startRunningLocked(elapsedRealtime);
                StatsLog.write(StatsLog.PHONE_SERVICE_STATE_CHANGED, state, simState, strengthBin);
            }
        }

        if (!scanning) {
            // If we are no longer scanning, then stop the scanning timer.
            if (mPhoneSignalScanningTimer.isRunningLocked()) {
                mHistoryCur.states &= ~HistoryItem.STATE_PHONE_SCANNING_FLAG;
                if (DEBUG_HISTORY) Slog.v(TAG, "Phone stopped scanning to: "
                        + Integer.toHexString(mHistoryCur.states));
                newHistory = true;
                mPhoneSignalScanningTimer.stopRunningLocked(elapsedRealtime);
                StatsLog.write(StatsLog.PHONE_SERVICE_STATE_CHANGED, state, simState, strengthBin);
            }
        }

        if (mPhoneServiceState != state) {
            mHistoryCur.states = (mHistoryCur.states&~HistoryItem.STATE_PHONE_STATE_MASK)
                    | (state << HistoryItem.STATE_PHONE_STATE_SHIFT);
            if (DEBUG_HISTORY) Slog.v(TAG, "Phone state " + state + " to: "
                    + Integer.toHexString(mHistoryCur.states));
            newHistory = true;
            mPhoneServiceState = state;
        }

        if (mPhoneSignalStrengthBin != strengthBin) {
            if (mPhoneSignalStrengthBin >= 0) {
                mPhoneSignalStrengthsTimer[mPhoneSignalStrengthBin].stopRunningLocked(
                        elapsedRealtime);
            }
            if (strengthBin >= 0) {
                if (!mPhoneSignalStrengthsTimer[strengthBin].isRunningLocked()) {
                    mPhoneSignalStrengthsTimer[strengthBin].startRunningLocked(elapsedRealtime);
                }
                mHistoryCur.states = (mHistoryCur.states&~HistoryItem.STATE_PHONE_SIGNAL_STRENGTH_MASK)
                        | (strengthBin << HistoryItem.STATE_PHONE_SIGNAL_STRENGTH_SHIFT);
                if (DEBUG_HISTORY) Slog.v(TAG, "Signal strength " + strengthBin + " to: "
                        + Integer.toHexString(mHistoryCur.states));
                newHistory = true;
                StatsLog.write(StatsLog.PHONE_SIGNAL_STRENGTH_CHANGED, strengthBin);
            } else {
                stopAllPhoneSignalStrengthTimersLocked(-1);
            }
            mPhoneSignalStrengthBin = strengthBin;
        }

        if (newHistory) {
            addHistoryRecordLocked(elapsedRealtime, uptime);
        }
    }

    /**
     * Telephony stack updates the phone state.
     * @param state phone state from ServiceState.getState()
     */
    public void notePhoneStateLocked(int state, int simState) {
        updateAllPhoneStateLocked(state, simState, mPhoneSignalStrengthBinRaw);
    }

    @UnsupportedAppUsage
    public void notePhoneSignalStrengthLocked(SignalStrength signalStrength) {
        // Bin the strength.
        int bin = signalStrength.getLevel();
        updateAllPhoneStateLocked(mPhoneServiceStateRaw, mPhoneSimStateRaw, bin);
    }

    @UnsupportedAppUsage
    public void notePhoneDataConnectionStateLocked(int dataType, boolean hasData, int serviceType) {
        // BatteryStats uses 0 to represent no network type.
        // Telephony does not have a concept of no network type, and uses 0 to represent unknown.
        // Unknown is included in DATA_CONNECTION_OTHER.
        int bin = DATA_CONNECTION_OUT_OF_SERVICE;
        if (hasData) {
            if (dataType > 0 && dataType <= TelephonyManager.MAX_NETWORK_TYPE) {
                bin = dataType;
            } else {
                switch (serviceType) {
                    case ServiceState.STATE_OUT_OF_SERVICE:
                        bin = DATA_CONNECTION_OUT_OF_SERVICE;
                        break;
                    case ServiceState.STATE_EMERGENCY_ONLY:
                        bin = DATA_CONNECTION_EMERGENCY_SERVICE;
                        break;
                    default:
                        bin = DATA_CONNECTION_OTHER;
                        break;
                }
            }
        }
        if (DEBUG) Log.i(TAG, "Phone Data Connection -> " + dataType + " = " + hasData);
        if (mPhoneDataConnectionType != bin) {
            final long elapsedRealtime = mClocks.elapsedRealtime();
            final long uptime = mClocks.uptimeMillis();
            mHistoryCur.states = (mHistoryCur.states&~HistoryItem.STATE_DATA_CONNECTION_MASK)
                    | (bin << HistoryItem.STATE_DATA_CONNECTION_SHIFT);
            if (DEBUG_HISTORY) Slog.v(TAG, "Data connection " + bin + " to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtime, uptime);
            if (mPhoneDataConnectionType >= 0) {
                mPhoneDataConnectionsTimer[mPhoneDataConnectionType].stopRunningLocked(
                        elapsedRealtime);
            }
            mPhoneDataConnectionType = bin;
            mPhoneDataConnectionsTimer[bin].startRunningLocked(elapsedRealtime);
        }
    }

    public void noteWifiOnLocked() {
        if (!mWifiOn) {
            final long elapsedRealtime = mClocks.elapsedRealtime();
            final long uptime = mClocks.uptimeMillis();
            mHistoryCur.states2 |= HistoryItem.STATE2_WIFI_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "WIFI on to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtime, uptime);
            mWifiOn = true;
            mWifiOnTimer.startRunningLocked(elapsedRealtime);
            scheduleSyncExternalStatsLocked("wifi-off", ExternalStatsSync.UPDATE_WIFI);
        }
    }

    public void noteWifiOffLocked() {
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        if (mWifiOn) {
            mHistoryCur.states2 &= ~HistoryItem.STATE2_WIFI_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "WIFI off to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtime, uptime);
            mWifiOn = false;
            mWifiOnTimer.stopRunningLocked(elapsedRealtime);
            scheduleSyncExternalStatsLocked("wifi-on", ExternalStatsSync.UPDATE_WIFI);
        }
    }

    @UnsupportedAppUsage
    public void noteAudioOnLocked(int uid) {
        uid = mapUid(uid);
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        if (mAudioOnNesting == 0) {
            mHistoryCur.states |= HistoryItem.STATE_AUDIO_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Audio on to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtime, uptime);
            mAudioOnTimer.startRunningLocked(elapsedRealtime);
        }
        mAudioOnNesting++;
        getUidStatsLocked(uid).noteAudioTurnedOnLocked(elapsedRealtime);
    }

    @UnsupportedAppUsage
    public void noteAudioOffLocked(int uid) {
        if (mAudioOnNesting == 0) {
            return;
        }
        uid = mapUid(uid);
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        if (--mAudioOnNesting == 0) {
            mHistoryCur.states &= ~HistoryItem.STATE_AUDIO_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Audio off to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtime, uptime);
            mAudioOnTimer.stopRunningLocked(elapsedRealtime);
        }
        getUidStatsLocked(uid).noteAudioTurnedOffLocked(elapsedRealtime);
    }

    @UnsupportedAppUsage
    public void noteVideoOnLocked(int uid) {
        uid = mapUid(uid);
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        if (mVideoOnNesting == 0) {
            mHistoryCur.states2 |= HistoryItem.STATE2_VIDEO_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Video on to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtime, uptime);
            mVideoOnTimer.startRunningLocked(elapsedRealtime);
        }
        mVideoOnNesting++;
        getUidStatsLocked(uid).noteVideoTurnedOnLocked(elapsedRealtime);
    }

    @UnsupportedAppUsage
    public void noteVideoOffLocked(int uid) {
        if (mVideoOnNesting == 0) {
            return;
        }
        uid = mapUid(uid);
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        if (--mVideoOnNesting == 0) {
            mHistoryCur.states2 &= ~HistoryItem.STATE2_VIDEO_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Video off to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtime, uptime);
            mVideoOnTimer.stopRunningLocked(elapsedRealtime);
        }
        getUidStatsLocked(uid).noteVideoTurnedOffLocked(elapsedRealtime);
    }

    public void noteResetAudioLocked() {
        if (mAudioOnNesting > 0) {
            final long elapsedRealtime = mClocks.elapsedRealtime();
            final long uptime = mClocks.uptimeMillis();
            mAudioOnNesting = 0;
            mHistoryCur.states &= ~HistoryItem.STATE_AUDIO_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Audio off to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtime, uptime);
            mAudioOnTimer.stopAllRunningLocked(elapsedRealtime);
            for (int i=0; i<mUidStats.size(); i++) {
                BatteryStatsImpl.Uid uid = mUidStats.valueAt(i);
                uid.noteResetAudioLocked(elapsedRealtime);
            }
        }
    }

    public void noteResetVideoLocked() {
        if (mVideoOnNesting > 0) {
            final long elapsedRealtime = mClocks.elapsedRealtime();
            final long uptime = mClocks.uptimeMillis();
            mAudioOnNesting = 0;
            mHistoryCur.states2 &= ~HistoryItem.STATE2_VIDEO_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Video off to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtime, uptime);
            mVideoOnTimer.stopAllRunningLocked(elapsedRealtime);
            for (int i=0; i<mUidStats.size(); i++) {
                BatteryStatsImpl.Uid uid = mUidStats.valueAt(i);
                uid.noteResetVideoLocked(elapsedRealtime);
            }
        }
    }

    public void noteActivityResumedLocked(int uid) {
        uid = mapUid(uid);
        getUidStatsLocked(uid).noteActivityResumedLocked(mClocks.elapsedRealtime());
    }

    public void noteActivityPausedLocked(int uid) {
        uid = mapUid(uid);
        getUidStatsLocked(uid).noteActivityPausedLocked(mClocks.elapsedRealtime());
    }

    public void noteVibratorOnLocked(int uid, long durationMillis) {
        uid = mapUid(uid);
        getUidStatsLocked(uid).noteVibratorOnLocked(durationMillis);
    }

    public void noteVibratorOffLocked(int uid) {
        uid = mapUid(uid);
        getUidStatsLocked(uid).noteVibratorOffLocked();
    }

    public void noteFlashlightOnLocked(int uid) {
        uid = mapUid(uid);
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        if (mFlashlightOnNesting++ == 0) {
            mHistoryCur.states2 |= HistoryItem.STATE2_FLASHLIGHT_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Flashlight on to: "
                    + Integer.toHexString(mHistoryCur.states2));
            addHistoryRecordLocked(elapsedRealtime, uptime);
            mFlashlightOnTimer.startRunningLocked(elapsedRealtime);
        }
        getUidStatsLocked(uid).noteFlashlightTurnedOnLocked(elapsedRealtime);
    }

    public void noteFlashlightOffLocked(int uid) {
        if (mFlashlightOnNesting == 0) {
            return;
        }
        uid = mapUid(uid);
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        if (--mFlashlightOnNesting == 0) {
            mHistoryCur.states2 &= ~HistoryItem.STATE2_FLASHLIGHT_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Flashlight off to: "
                    + Integer.toHexString(mHistoryCur.states2));
            addHistoryRecordLocked(elapsedRealtime, uptime);
            mFlashlightOnTimer.stopRunningLocked(elapsedRealtime);
        }
        getUidStatsLocked(uid).noteFlashlightTurnedOffLocked(elapsedRealtime);
    }

    public void noteCameraOnLocked(int uid) {
        uid = mapUid(uid);
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        if (mCameraOnNesting++ == 0) {
            mHistoryCur.states2 |= HistoryItem.STATE2_CAMERA_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Camera on to: "
                    + Integer.toHexString(mHistoryCur.states2));
            addHistoryRecordLocked(elapsedRealtime, uptime);
            mCameraOnTimer.startRunningLocked(elapsedRealtime);
        }
        getUidStatsLocked(uid).noteCameraTurnedOnLocked(elapsedRealtime);
    }

    public void noteCameraOffLocked(int uid) {
        if (mCameraOnNesting == 0) {
            return;
        }
        uid = mapUid(uid);
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        if (--mCameraOnNesting == 0) {
            mHistoryCur.states2 &= ~HistoryItem.STATE2_CAMERA_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Camera off to: "
                    + Integer.toHexString(mHistoryCur.states2));
            addHistoryRecordLocked(elapsedRealtime, uptime);
            mCameraOnTimer.stopRunningLocked(elapsedRealtime);
        }
        getUidStatsLocked(uid).noteCameraTurnedOffLocked(elapsedRealtime);
    }

    public void noteResetCameraLocked() {
        if (mCameraOnNesting > 0) {
            final long elapsedRealtime = mClocks.elapsedRealtime();
            final long uptime = mClocks.uptimeMillis();
            mCameraOnNesting = 0;
            mHistoryCur.states2 &= ~HistoryItem.STATE2_CAMERA_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Camera off to: "
                    + Integer.toHexString(mHistoryCur.states2));
            addHistoryRecordLocked(elapsedRealtime, uptime);
            mCameraOnTimer.stopAllRunningLocked(elapsedRealtime);
            for (int i=0; i<mUidStats.size(); i++) {
                BatteryStatsImpl.Uid uid = mUidStats.valueAt(i);
                uid.noteResetCameraLocked(elapsedRealtime);
            }
        }
    }

    public void noteResetFlashlightLocked() {
        if (mFlashlightOnNesting > 0) {
            final long elapsedRealtime = mClocks.elapsedRealtime();
            final long uptime = mClocks.uptimeMillis();
            mFlashlightOnNesting = 0;
            mHistoryCur.states2 &= ~HistoryItem.STATE2_FLASHLIGHT_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Flashlight off to: "
                    + Integer.toHexString(mHistoryCur.states2));
            addHistoryRecordLocked(elapsedRealtime, uptime);
            mFlashlightOnTimer.stopAllRunningLocked(elapsedRealtime);
            for (int i=0; i<mUidStats.size(); i++) {
                BatteryStatsImpl.Uid uid = mUidStats.valueAt(i);
                uid.noteResetFlashlightLocked(elapsedRealtime);
            }
        }
    }

    private void noteBluetoothScanStartedLocked(WorkChain workChain, int uid,
            boolean isUnoptimized) {
        uid = getAttributionUid(uid, workChain);
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        if (mBluetoothScanNesting == 0) {
            mHistoryCur.states2 |= HistoryItem.STATE2_BLUETOOTH_SCAN_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "BLE scan started for: "
                    + Integer.toHexString(mHistoryCur.states2));
            addHistoryRecordLocked(elapsedRealtime, uptime);
            mBluetoothScanTimer.startRunningLocked(elapsedRealtime);
        }
        mBluetoothScanNesting++;
        getUidStatsLocked(uid).noteBluetoothScanStartedLocked(elapsedRealtime, isUnoptimized);
    }

    public void noteBluetoothScanStartedFromSourceLocked(WorkSource ws, boolean isUnoptimized) {
        final int N = ws.size();
        for (int i = 0; i < N; i++) {
            noteBluetoothScanStartedLocked(null, ws.get(i), isUnoptimized);
        }

        final List<WorkChain> workChains = ws.getWorkChains();
        if (workChains != null) {
            for (int i = 0; i < workChains.size(); ++i) {
                noteBluetoothScanStartedLocked(workChains.get(i), -1, isUnoptimized);
            }
        }
    }

    private void noteBluetoothScanStoppedLocked(WorkChain workChain, int uid,
            boolean isUnoptimized) {
        uid = getAttributionUid(uid, workChain);
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        mBluetoothScanNesting--;
        if (mBluetoothScanNesting == 0) {
            mHistoryCur.states2 &= ~HistoryItem.STATE2_BLUETOOTH_SCAN_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "BLE scan stopped for: "
                    + Integer.toHexString(mHistoryCur.states2));
            addHistoryRecordLocked(elapsedRealtime, uptime);
            mBluetoothScanTimer.stopRunningLocked(elapsedRealtime);
        }
        getUidStatsLocked(uid).noteBluetoothScanStoppedLocked(elapsedRealtime, isUnoptimized);
    }

    private int getAttributionUid(int uid, WorkChain workChain) {
        if (workChain != null) {
            return mapUid(workChain.getAttributionUid());
        }

        return mapUid(uid);
    }

    public void noteBluetoothScanStoppedFromSourceLocked(WorkSource ws, boolean isUnoptimized) {
        final int N = ws.size();
        for (int i = 0; i < N; i++) {
            noteBluetoothScanStoppedLocked(null, ws.get(i), isUnoptimized);
        }

        final List<WorkChain> workChains = ws.getWorkChains();
        if (workChains != null) {
            for (int i = 0; i < workChains.size(); ++i) {
                noteBluetoothScanStoppedLocked(workChains.get(i), -1, isUnoptimized);
            }
        }
    }

    public void noteResetBluetoothScanLocked() {
        if (mBluetoothScanNesting > 0) {
            final long elapsedRealtime = mClocks.elapsedRealtime();
            final long uptime = mClocks.uptimeMillis();
            mBluetoothScanNesting = 0;
            mHistoryCur.states2 &= ~HistoryItem.STATE2_BLUETOOTH_SCAN_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "BLE can stopped for: "
                    + Integer.toHexString(mHistoryCur.states2));
            addHistoryRecordLocked(elapsedRealtime, uptime);
            mBluetoothScanTimer.stopAllRunningLocked(elapsedRealtime);
            for (int i=0; i<mUidStats.size(); i++) {
                BatteryStatsImpl.Uid uid = mUidStats.valueAt(i);
                uid.noteResetBluetoothScanLocked(elapsedRealtime);
            }
        }
    }

    public void noteBluetoothScanResultsFromSourceLocked(WorkSource ws, int numNewResults) {
        final int N = ws.size();
        for (int i = 0; i < N; i++) {
            int uid = mapUid(ws.get(i));
            getUidStatsLocked(uid).noteBluetoothScanResultsLocked(numNewResults);
        }

        final List<WorkChain> workChains = ws.getWorkChains();
        if (workChains != null) {
            for (int i = 0; i < workChains.size(); ++i) {
                final WorkChain wc = workChains.get(i);
                int uid = mapUid(wc.getAttributionUid());
                getUidStatsLocked(uid).noteBluetoothScanResultsLocked(numNewResults);
            }
        }
    }

    private void noteWifiRadioApWakeupLocked(final long elapsedRealtimeMillis,
            final long uptimeMillis, int uid) {
        uid = mapUid(uid);
        addHistoryEventLocked(elapsedRealtimeMillis, uptimeMillis, HistoryItem.EVENT_WAKEUP_AP, "",
                uid);
        getUidStatsLocked(uid).noteWifiRadioApWakeupLocked();
    }

    public void noteWifiRadioPowerState(int powerState, long timestampNs, int uid) {
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        if (mWifiRadioPowerState != powerState) {
            final boolean active =
                    powerState == DataConnectionRealTimeInfo.DC_POWER_STATE_MEDIUM
                            || powerState == DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH;
            if (active) {
                if (uid > 0) {
                    noteWifiRadioApWakeupLocked(elapsedRealtime, uptime, uid);
                }
                mHistoryCur.states |= HistoryItem.STATE_WIFI_RADIO_ACTIVE_FLAG;
                mWifiActiveTimer.startRunningLocked(elapsedRealtime);
            } else {
                mHistoryCur.states &= ~HistoryItem.STATE_WIFI_RADIO_ACTIVE_FLAG;
                mWifiActiveTimer.stopRunningLocked(
                    timestampNs / (1000 * 1000));
            }
            if (DEBUG_HISTORY) Slog.v(TAG, "Wifi network active " + active + " to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtime, uptime);
            mWifiRadioPowerState = powerState;
        }
    }

    public void noteWifiRunningLocked(WorkSource ws) {
        if (!mGlobalWifiRunning) {
            final long elapsedRealtime = mClocks.elapsedRealtime();
            final long uptime = mClocks.uptimeMillis();
            mHistoryCur.states2 |= HistoryItem.STATE2_WIFI_RUNNING_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "WIFI running to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtime, uptime);
            mGlobalWifiRunning = true;
            mGlobalWifiRunningTimer.startRunningLocked(elapsedRealtime);
            int N = ws.size();
            for (int i=0; i<N; i++) {
                int uid = mapUid(ws.get(i));
                getUidStatsLocked(uid).noteWifiRunningLocked(elapsedRealtime);
            }

            List<WorkChain> workChains = ws.getWorkChains();
            if (workChains != null) {
                for (int i = 0; i < workChains.size(); ++i) {
                    int uid = mapUid(workChains.get(i).getAttributionUid());
                    getUidStatsLocked(uid).noteWifiRunningLocked(elapsedRealtime);
                }
            }

            scheduleSyncExternalStatsLocked("wifi-running", ExternalStatsSync.UPDATE_WIFI);
        } else {
            Log.w(TAG, "noteWifiRunningLocked -- called while WIFI running");
        }
    }

    public void noteWifiRunningChangedLocked(WorkSource oldWs, WorkSource newWs) {
        if (mGlobalWifiRunning) {
            final long elapsedRealtime = mClocks.elapsedRealtime();
            int N = oldWs.size();
            for (int i=0; i<N; i++) {
                int uid = mapUid(oldWs.get(i));
                getUidStatsLocked(uid).noteWifiStoppedLocked(elapsedRealtime);
            }

            List<WorkChain> workChains = oldWs.getWorkChains();
            if (workChains != null) {
                for (int i = 0; i < workChains.size(); ++i) {
                    int uid = mapUid(workChains.get(i).getAttributionUid());
                    getUidStatsLocked(uid).noteWifiStoppedLocked(elapsedRealtime);
                }
            }

            N = newWs.size();
            for (int i=0; i<N; i++) {
                int uid = mapUid(newWs.get(i));
                getUidStatsLocked(uid).noteWifiRunningLocked(elapsedRealtime);
            }

            workChains = newWs.getWorkChains();
            if (workChains != null) {
                for (int i = 0; i < workChains.size(); ++i) {
                    int uid = mapUid(workChains.get(i).getAttributionUid());
                    getUidStatsLocked(uid).noteWifiRunningLocked(elapsedRealtime);
                }
            }
        } else {
            Log.w(TAG, "noteWifiRunningChangedLocked -- called while WIFI not running");
        }
    }

    public void noteWifiStoppedLocked(WorkSource ws) {
        if (mGlobalWifiRunning) {
            final long elapsedRealtime = mClocks.elapsedRealtime();
            final long uptime = mClocks.uptimeMillis();
            mHistoryCur.states2 &= ~HistoryItem.STATE2_WIFI_RUNNING_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "WIFI stopped to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtime, uptime);
            mGlobalWifiRunning = false;
            mGlobalWifiRunningTimer.stopRunningLocked(elapsedRealtime);
            int N = ws.size();
            for (int i=0; i<N; i++) {
                int uid = mapUid(ws.get(i));
                getUidStatsLocked(uid).noteWifiStoppedLocked(elapsedRealtime);
            }

            List<WorkChain> workChains = ws.getWorkChains();
            if (workChains != null) {
                for (int i = 0; i < workChains.size(); ++i) {
                    int uid = mapUid(workChains.get(i).getAttributionUid());
                    getUidStatsLocked(uid).noteWifiStoppedLocked(elapsedRealtime);
                }
            }

            scheduleSyncExternalStatsLocked("wifi-stopped", ExternalStatsSync.UPDATE_WIFI);
        } else {
            Log.w(TAG, "noteWifiStoppedLocked -- called while WIFI not running");
        }
    }

    public void noteWifiStateLocked(int wifiState, String accessPoint) {
        if (DEBUG) Log.i(TAG, "WiFi state -> " + wifiState);
        if (mWifiState != wifiState) {
            final long elapsedRealtime = mClocks.elapsedRealtime();
            if (mWifiState >= 0) {
                mWifiStateTimer[mWifiState].stopRunningLocked(elapsedRealtime);
            }
            mWifiState = wifiState;
            mWifiStateTimer[wifiState].startRunningLocked(elapsedRealtime);
            scheduleSyncExternalStatsLocked("wifi-state", ExternalStatsSync.UPDATE_WIFI);
        }
    }

    public void noteWifiSupplicantStateChangedLocked(int supplState, boolean failedAuth) {
        if (DEBUG) Log.i(TAG, "WiFi suppl state -> " + supplState);
        if (mWifiSupplState != supplState) {
            final long elapsedRealtime = mClocks.elapsedRealtime();
            final long uptime = mClocks.uptimeMillis();
            if (mWifiSupplState >= 0) {
                mWifiSupplStateTimer[mWifiSupplState].stopRunningLocked(elapsedRealtime);
            }
            mWifiSupplState = supplState;
            mWifiSupplStateTimer[supplState].startRunningLocked(elapsedRealtime);
            mHistoryCur.states2 =
                    (mHistoryCur.states2&~HistoryItem.STATE2_WIFI_SUPPL_STATE_MASK)
                    | (supplState << HistoryItem.STATE2_WIFI_SUPPL_STATE_SHIFT);
            if (DEBUG_HISTORY) Slog.v(TAG, "Wifi suppl state " + supplState + " to: "
                    + Integer.toHexString(mHistoryCur.states2));
            addHistoryRecordLocked(elapsedRealtime, uptime);
        }
    }

    void stopAllWifiSignalStrengthTimersLocked(int except) {
        final long elapsedRealtime = mClocks.elapsedRealtime();
        for (int i = 0; i < NUM_WIFI_SIGNAL_STRENGTH_BINS; i++) {
            if (i == except) {
                continue;
            }
            while (mWifiSignalStrengthsTimer[i].isRunningLocked()) {
                mWifiSignalStrengthsTimer[i].stopRunningLocked(elapsedRealtime);
            }
        }
    }

    public void noteWifiRssiChangedLocked(int newRssi) {
        int strengthBin = WifiManager.calculateSignalLevel(newRssi, NUM_WIFI_SIGNAL_STRENGTH_BINS);
        if (DEBUG) Log.i(TAG, "WiFi rssi -> " + newRssi + " bin=" + strengthBin);
        if (mWifiSignalStrengthBin != strengthBin) {
            final long elapsedRealtime = mClocks.elapsedRealtime();
            final long uptime = mClocks.uptimeMillis();
            if (mWifiSignalStrengthBin >= 0) {
                mWifiSignalStrengthsTimer[mWifiSignalStrengthBin].stopRunningLocked(
                        elapsedRealtime);
            }
            if (strengthBin >= 0) {
                if (!mWifiSignalStrengthsTimer[strengthBin].isRunningLocked()) {
                    mWifiSignalStrengthsTimer[strengthBin].startRunningLocked(elapsedRealtime);
                }
                mHistoryCur.states2 =
                        (mHistoryCur.states2&~HistoryItem.STATE2_WIFI_SIGNAL_STRENGTH_MASK)
                        | (strengthBin << HistoryItem.STATE2_WIFI_SIGNAL_STRENGTH_SHIFT);
                if (DEBUG_HISTORY) Slog.v(TAG, "Wifi signal strength " + strengthBin + " to: "
                        + Integer.toHexString(mHistoryCur.states2));
                addHistoryRecordLocked(elapsedRealtime, uptime);
            } else {
                stopAllWifiSignalStrengthTimersLocked(-1);
            }
            mWifiSignalStrengthBin = strengthBin;
        }
    }

    int mWifiFullLockNesting = 0;

    @UnsupportedAppUsage
    public void noteFullWifiLockAcquiredLocked(int uid) {
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        if (mWifiFullLockNesting == 0) {
            mHistoryCur.states |= HistoryItem.STATE_WIFI_FULL_LOCK_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "WIFI full lock on to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtime, uptime);
        }
        mWifiFullLockNesting++;
        getUidStatsLocked(uid).noteFullWifiLockAcquiredLocked(elapsedRealtime);
    }

    @UnsupportedAppUsage
    public void noteFullWifiLockReleasedLocked(int uid) {
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        mWifiFullLockNesting--;
        if (mWifiFullLockNesting == 0) {
            mHistoryCur.states &= ~HistoryItem.STATE_WIFI_FULL_LOCK_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "WIFI full lock off to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtime, uptime);
        }
        getUidStatsLocked(uid).noteFullWifiLockReleasedLocked(elapsedRealtime);
    }

    int mWifiScanNesting = 0;

    public void noteWifiScanStartedLocked(int uid) {
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        if (mWifiScanNesting == 0) {
            mHistoryCur.states |= HistoryItem.STATE_WIFI_SCAN_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "WIFI scan started for: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtime, uptime);
        }
        mWifiScanNesting++;
        getUidStatsLocked(uid).noteWifiScanStartedLocked(elapsedRealtime);
    }

    public void noteWifiScanStoppedLocked(int uid) {
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        mWifiScanNesting--;
        if (mWifiScanNesting == 0) {
            mHistoryCur.states &= ~HistoryItem.STATE_WIFI_SCAN_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "WIFI scan stopped for: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtime, uptime);
        }
        getUidStatsLocked(uid).noteWifiScanStoppedLocked(elapsedRealtime);
    }

    public void noteWifiBatchedScanStartedLocked(int uid, int csph) {
        uid = mapUid(uid);
        final long elapsedRealtime = mClocks.elapsedRealtime();
        getUidStatsLocked(uid).noteWifiBatchedScanStartedLocked(csph, elapsedRealtime);
    }

    public void noteWifiBatchedScanStoppedLocked(int uid) {
        uid = mapUid(uid);
        final long elapsedRealtime = mClocks.elapsedRealtime();
        getUidStatsLocked(uid).noteWifiBatchedScanStoppedLocked(elapsedRealtime);
    }

    int mWifiMulticastNesting = 0;

    @UnsupportedAppUsage
    public void noteWifiMulticastEnabledLocked(int uid) {
        uid = mapUid(uid);
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        if (mWifiMulticastNesting == 0) {
            mHistoryCur.states |= HistoryItem.STATE_WIFI_MULTICAST_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "WIFI multicast on to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtime, uptime);

            // Start Wifi Multicast overall timer
            if (!mWifiMulticastWakelockTimer.isRunningLocked()) {
                if (DEBUG_HISTORY) Slog.v(TAG, "WiFi Multicast Overall Timer Started");
                mWifiMulticastWakelockTimer.startRunningLocked(elapsedRealtime);
            }
        }
        mWifiMulticastNesting++;
        getUidStatsLocked(uid).noteWifiMulticastEnabledLocked(elapsedRealtime);
    }

    @UnsupportedAppUsage
    public void noteWifiMulticastDisabledLocked(int uid) {
        uid = mapUid(uid);
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        mWifiMulticastNesting--;
        if (mWifiMulticastNesting == 0) {
            mHistoryCur.states &= ~HistoryItem.STATE_WIFI_MULTICAST_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "WIFI multicast off to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(elapsedRealtime, uptime);

            // Stop Wifi Multicast overall timer
            if (mWifiMulticastWakelockTimer.isRunningLocked()) {
                if (DEBUG_HISTORY) Slog.v(TAG, "Multicast Overall Timer Stopped");
                mWifiMulticastWakelockTimer.stopRunningLocked(elapsedRealtime);
            }
        }
        getUidStatsLocked(uid).noteWifiMulticastDisabledLocked(elapsedRealtime);
    }

    public void noteFullWifiLockAcquiredFromSourceLocked(WorkSource ws) {
        int N = ws.size();
        for (int i=0; i<N; i++) {
            final int uid = mapUid(ws.get(i));
            noteFullWifiLockAcquiredLocked(uid);
        }

        final List<WorkChain> workChains = ws.getWorkChains();
        if (workChains != null) {
            for (int i = 0; i < workChains.size(); ++i) {
                final WorkChain workChain = workChains.get(i);
                final int uid = mapUid(workChain.getAttributionUid());
                noteFullWifiLockAcquiredLocked(uid);
            }
        }
    }

    public void noteFullWifiLockReleasedFromSourceLocked(WorkSource ws) {
        int N = ws.size();
        for (int i=0; i<N; i++) {
            final int uid = mapUid(ws.get(i));
            noteFullWifiLockReleasedLocked(uid);
        }

        final List<WorkChain> workChains = ws.getWorkChains();
        if (workChains != null) {
            for (int i = 0; i < workChains.size(); ++i) {
                final WorkChain workChain = workChains.get(i);
                final int uid = mapUid(workChain.getAttributionUid());
                noteFullWifiLockReleasedLocked(uid);
            }
        }
    }

    public void noteWifiScanStartedFromSourceLocked(WorkSource ws) {
        int N = ws.size();
        for (int i=0; i<N; i++) {
            final int uid = mapUid(ws.get(i));
            noteWifiScanStartedLocked(uid);
        }

        final List<WorkChain> workChains = ws.getWorkChains();
        if (workChains != null) {
            for (int i = 0; i < workChains.size(); ++i) {
                final WorkChain workChain = workChains.get(i);
                final int uid = mapUid(workChain.getAttributionUid());
                noteWifiScanStartedLocked(uid);
            }
        }
    }

    public void noteWifiScanStoppedFromSourceLocked(WorkSource ws) {
        int N = ws.size();
        for (int i=0; i<N; i++) {
            final int uid = mapUid(ws.get(i));
            noteWifiScanStoppedLocked(uid);
        }

        final List<WorkChain> workChains = ws.getWorkChains();
        if (workChains != null) {
            for (int i = 0; i < workChains.size(); ++i) {
                final WorkChain workChain = workChains.get(i);
                final int uid = mapUid(workChain.getAttributionUid());
                noteWifiScanStoppedLocked(uid);
            }
        }
    }

    public void noteWifiBatchedScanStartedFromSourceLocked(WorkSource ws, int csph) {
        int N = ws.size();
        for (int i=0; i<N; i++) {
            noteWifiBatchedScanStartedLocked(ws.get(i), csph);
        }

        final List<WorkChain> workChains = ws.getWorkChains();
        if (workChains != null) {
            for (int i = 0; i < workChains.size(); ++i) {
                noteWifiBatchedScanStartedLocked(workChains.get(i).getAttributionUid(), csph);
            }
        }
    }

    public void noteWifiBatchedScanStoppedFromSourceLocked(WorkSource ws) {
        int N = ws.size();
        for (int i=0; i<N; i++) {
            noteWifiBatchedScanStoppedLocked(ws.get(i));
        }

        final List<WorkChain> workChains = ws.getWorkChains();
        if (workChains != null) {
            for (int i = 0; i < workChains.size(); ++i) {
                noteWifiBatchedScanStoppedLocked(workChains.get(i).getAttributionUid());
            }
        }
    }

    private static String[] includeInStringArray(String[] array, String str) {
        if (ArrayUtils.indexOf(array, str) >= 0) {
            return array;
        }
        String[] newArray = new String[array.length+1];
        System.arraycopy(array, 0, newArray, 0, array.length);
        newArray[array.length] = str;
        return newArray;
    }

    private static String[] excludeFromStringArray(String[] array, String str) {
        int index = ArrayUtils.indexOf(array, str);
        if (index >= 0) {
            String[] newArray = new String[array.length-1];
            if (index > 0) {
                System.arraycopy(array, 0, newArray, 0, index);
            }
            if (index < array.length-1) {
                System.arraycopy(array, index+1, newArray, index, array.length-index-1);
            }
            return newArray;
        }
        return array;
    }

    public void noteNetworkInterfaceTypeLocked(String iface, int networkType) {
        if (TextUtils.isEmpty(iface)) return;

        synchronized (mModemNetworkLock) {
            if (ConnectivityManager.isNetworkTypeMobile(networkType)) {
                mModemIfaces = includeInStringArray(mModemIfaces, iface);
                if (DEBUG) Slog.d(TAG, "Note mobile iface " + iface + ": " + mModemIfaces);
            } else {
                mModemIfaces = excludeFromStringArray(mModemIfaces, iface);
                if (DEBUG) Slog.d(TAG, "Note non-mobile iface " + iface + ": " + mModemIfaces);
            }
        }

        synchronized (mWifiNetworkLock) {
            if (ConnectivityManager.isNetworkTypeWifi(networkType)) {
                mWifiIfaces = includeInStringArray(mWifiIfaces, iface);
                if (DEBUG) Slog.d(TAG, "Note wifi iface " + iface + ": " + mWifiIfaces);
            } else {
                mWifiIfaces = excludeFromStringArray(mWifiIfaces, iface);
                if (DEBUG) Slog.d(TAG, "Note non-wifi iface " + iface + ": " + mWifiIfaces);
            }
        }
    }

    public String[] getWifiIfaces() {
        synchronized (mWifiNetworkLock) {
            return mWifiIfaces;
        }
    }

    public String[] getMobileIfaces() {
        synchronized (mModemNetworkLock) {
            return mModemIfaces;
        }
    }

    @UnsupportedAppUsage
    @Override public long getScreenOnTime(long elapsedRealtimeUs, int which) {
        return mScreenOnTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
    }

    @Override public int getScreenOnCount(int which) {
        return mScreenOnTimer.getCountLocked(which);
    }

    @Override public long getScreenDozeTime(long elapsedRealtimeUs, int which) {
        return mScreenDozeTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
    }

    @Override public int getScreenDozeCount(int which) {
        return mScreenDozeTimer.getCountLocked(which);
    }

    @UnsupportedAppUsage
    @Override public long getScreenBrightnessTime(int brightnessBin,
            long elapsedRealtimeUs, int which) {
        return mScreenBrightnessTimer[brightnessBin].getTotalTimeLocked(
                elapsedRealtimeUs, which);
    }

    @Override public Timer getScreenBrightnessTimer(int brightnessBin) {
        return mScreenBrightnessTimer[brightnessBin];
    }

    @Override public long getInteractiveTime(long elapsedRealtimeUs, int which) {
        return mInteractiveTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
    }

    @Override public long getPowerSaveModeEnabledTime(long elapsedRealtimeUs, int which) {
        return mPowerSaveModeEnabledTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
    }

    @Override public int getPowerSaveModeEnabledCount(int which) {
        return mPowerSaveModeEnabledTimer.getCountLocked(which);
    }

    @Override public long getDeviceIdleModeTime(int mode, long elapsedRealtimeUs,
            int which) {
        switch (mode) {
            case DEVICE_IDLE_MODE_LIGHT:
                return mDeviceIdleModeLightTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
            case DEVICE_IDLE_MODE_DEEP:
                return mDeviceIdleModeFullTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
        }
        return 0;
    }

    @Override public int getDeviceIdleModeCount(int mode, int which) {
        switch (mode) {
            case DEVICE_IDLE_MODE_LIGHT:
                return mDeviceIdleModeLightTimer.getCountLocked(which);
            case DEVICE_IDLE_MODE_DEEP:
                return mDeviceIdleModeFullTimer.getCountLocked(which);
        }
        return 0;
    }

    @Override public long getLongestDeviceIdleModeTime(int mode) {
        switch (mode) {
            case DEVICE_IDLE_MODE_LIGHT:
                return mLongestLightIdleTime;
            case DEVICE_IDLE_MODE_DEEP:
                return mLongestFullIdleTime;
        }
        return 0;
    }

    @Override public long getDeviceIdlingTime(int mode, long elapsedRealtimeUs, int which) {
        switch (mode) {
            case DEVICE_IDLE_MODE_LIGHT:
                return mDeviceLightIdlingTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
            case DEVICE_IDLE_MODE_DEEP:
                return mDeviceIdlingTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
        }
        return 0;
    }

    @Override public int getDeviceIdlingCount(int mode, int which) {
        switch (mode) {
            case DEVICE_IDLE_MODE_LIGHT:
                return mDeviceLightIdlingTimer.getCountLocked(which);
            case DEVICE_IDLE_MODE_DEEP:
                return mDeviceIdlingTimer.getCountLocked(which);
        }
        return 0;
    }

    @Override public int getNumConnectivityChange(int which) {
        return mNumConnectivityChange;
    }

    @Override public long getGpsSignalQualityTime(int strengthBin,
        long elapsedRealtimeUs, int which) {
        if (strengthBin < 0 || strengthBin >= GnssMetrics.NUM_GPS_SIGNAL_QUALITY_LEVELS) {
            return 0;
        }
        return mGpsSignalQualityTimer[strengthBin].getTotalTimeLocked(
            elapsedRealtimeUs, which);
    }

    @Override public long getGpsBatteryDrainMaMs() {
        final double opVolt = mPowerProfile.getAveragePower(
            PowerProfile.POWER_GPS_OPERATING_VOLTAGE) / 1000.0;
        if (opVolt == 0) {
            return 0;
        }
        double energyUsedMaMs = 0.0;
        final int which = STATS_SINCE_CHARGED;
        final long rawRealtime = SystemClock.elapsedRealtime() * 1000;
        for(int i=0; i < GnssMetrics.NUM_GPS_SIGNAL_QUALITY_LEVELS; i++) {
            energyUsedMaMs
                += mPowerProfile.getAveragePower(PowerProfile.POWER_GPS_SIGNAL_QUALITY_BASED, i)
                * (getGpsSignalQualityTime(i, rawRealtime, which) / 1000);
        }
        return (long) energyUsedMaMs;
    }

    @UnsupportedAppUsage
    @Override public long getPhoneOnTime(long elapsedRealtimeUs, int which) {
        return mPhoneOnTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
    }

    @Override public int getPhoneOnCount(int which) {
        return mPhoneOnTimer.getCountLocked(which);
    }

    @UnsupportedAppUsage
    @Override public long getPhoneSignalStrengthTime(int strengthBin,
            long elapsedRealtimeUs, int which) {
        return mPhoneSignalStrengthsTimer[strengthBin].getTotalTimeLocked(
                elapsedRealtimeUs, which);
    }

    @UnsupportedAppUsage
    @Override public long getPhoneSignalScanningTime(
            long elapsedRealtimeUs, int which) {
        return mPhoneSignalScanningTimer.getTotalTimeLocked(
                elapsedRealtimeUs, which);
    }

    @Override public Timer getPhoneSignalScanningTimer() {
        return mPhoneSignalScanningTimer;
    }

    @UnsupportedAppUsage
    @Override public int getPhoneSignalStrengthCount(int strengthBin, int which) {
        return mPhoneSignalStrengthsTimer[strengthBin].getCountLocked(which);
    }

    @Override public Timer getPhoneSignalStrengthTimer(int strengthBin) {
        return mPhoneSignalStrengthsTimer[strengthBin];
    }

    @UnsupportedAppUsage
    @Override public long getPhoneDataConnectionTime(int dataType,
            long elapsedRealtimeUs, int which) {
        return mPhoneDataConnectionsTimer[dataType].getTotalTimeLocked(
                elapsedRealtimeUs, which);
    }

    @UnsupportedAppUsage
    @Override public int getPhoneDataConnectionCount(int dataType, int which) {
        return mPhoneDataConnectionsTimer[dataType].getCountLocked(which);
    }

    @Override public Timer getPhoneDataConnectionTimer(int dataType) {
        return mPhoneDataConnectionsTimer[dataType];
    }

    @UnsupportedAppUsage
    @Override public long getMobileRadioActiveTime(long elapsedRealtimeUs, int which) {
        return mMobileRadioActiveTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
    }

    @Override public int getMobileRadioActiveCount(int which) {
        return mMobileRadioActiveTimer.getCountLocked(which);
    }

    @Override public long getMobileRadioActiveAdjustedTime(int which) {
        return mMobileRadioActiveAdjustedTime.getCountLocked(which);
    }

    @Override public long getMobileRadioActiveUnknownTime(int which) {
        return mMobileRadioActiveUnknownTime.getCountLocked(which);
    }

    @Override public int getMobileRadioActiveUnknownCount(int which) {
        return (int)mMobileRadioActiveUnknownCount.getCountLocked(which);
    }

    @Override public long getWifiMulticastWakelockTime(
            long elapsedRealtimeUs, int which) {
        return mWifiMulticastWakelockTimer.getTotalTimeLocked(
                elapsedRealtimeUs, which);
    }

    @Override public int getWifiMulticastWakelockCount(int which) {
        return mWifiMulticastWakelockTimer.getCountLocked(which);
    }

    @UnsupportedAppUsage
    @Override public long getWifiOnTime(long elapsedRealtimeUs, int which) {
        return mWifiOnTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
    }

    @Override public long getWifiActiveTime(long elapsedRealtimeUs, int which) {
        return mWifiActiveTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
    }

    @UnsupportedAppUsage
    @Override public long getGlobalWifiRunningTime(long elapsedRealtimeUs, int which) {
        return mGlobalWifiRunningTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
    }

    @Override public long getWifiStateTime(int wifiState,
            long elapsedRealtimeUs, int which) {
        return mWifiStateTimer[wifiState].getTotalTimeLocked(
                elapsedRealtimeUs, which);
    }

    @Override public int getWifiStateCount(int wifiState, int which) {
        return mWifiStateTimer[wifiState].getCountLocked(which);
    }

    @Override public Timer getWifiStateTimer(int wifiState) {
        return mWifiStateTimer[wifiState];
    }

    @Override public long getWifiSupplStateTime(int state,
            long elapsedRealtimeUs, int which) {
        return mWifiSupplStateTimer[state].getTotalTimeLocked(
                elapsedRealtimeUs, which);
    }

    @Override public int getWifiSupplStateCount(int state, int which) {
        return mWifiSupplStateTimer[state].getCountLocked(which);
    }

    @Override public Timer getWifiSupplStateTimer(int state) {
        return mWifiSupplStateTimer[state];
    }

    @Override public long getWifiSignalStrengthTime(int strengthBin,
            long elapsedRealtimeUs, int which) {
        return mWifiSignalStrengthsTimer[strengthBin].getTotalTimeLocked(
                elapsedRealtimeUs, which);
    }

    @Override public int getWifiSignalStrengthCount(int strengthBin, int which) {
        return mWifiSignalStrengthsTimer[strengthBin].getCountLocked(which);
    }

    @Override public Timer getWifiSignalStrengthTimer(int strengthBin) {
        return mWifiSignalStrengthsTimer[strengthBin];
    }

    @Override
    public ControllerActivityCounter getBluetoothControllerActivity() {
        return mBluetoothActivity;
    }

    @Override
    public ControllerActivityCounter getWifiControllerActivity() {
        return mWifiActivity;
    }

    @Override
    public ControllerActivityCounter getModemControllerActivity() {
        return mModemActivity;
    }

    @Override
    public boolean hasBluetoothActivityReporting() {
        return mHasBluetoothReporting;
    }

    @Override
    public boolean hasWifiActivityReporting() {
        return mHasWifiReporting;
    }

    @Override
    public boolean hasModemActivityReporting() {
        return mHasModemReporting;
    }

    @Override
    public long getFlashlightOnTime(long elapsedRealtimeUs, int which) {
        return mFlashlightOnTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
    }

    @Override
    public long getFlashlightOnCount(int which) {
        return mFlashlightOnTimer.getCountLocked(which);
    }

    @Override
    public long getCameraOnTime(long elapsedRealtimeUs, int which) {
        return mCameraOnTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
    }

    @Override
    public long getBluetoothScanTime(long elapsedRealtimeUs, int which) {
        return mBluetoothScanTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
    }

    @Override
    @UnsupportedAppUsage
    public long getNetworkActivityBytes(int type, int which) {
        if (type >= 0 && type < mNetworkByteActivityCounters.length) {
            return mNetworkByteActivityCounters[type].getCountLocked(which);
        } else {
            return 0;
        }
    }

    @Override
    public long getNetworkActivityPackets(int type, int which) {
        if (type >= 0 && type < mNetworkPacketActivityCounters.length) {
            return mNetworkPacketActivityCounters[type].getCountLocked(which);
        } else {
            return 0;
        }
    }

    @Override public long getStartClockTime() {
        final long currentTime = System.currentTimeMillis();
        if ((currentTime > MILLISECONDS_IN_YEAR
                && mStartClockTime < (currentTime - MILLISECONDS_IN_YEAR))
                || (mStartClockTime > currentTime)) {
            // If the start clock time has changed by more than a year, then presumably
            // the previous time was completely bogus.  So we are going to figure out a
            // new time based on how much time has elapsed since we started counting.
            recordCurrentTimeChangeLocked(currentTime, mClocks.elapsedRealtime(),
                    mClocks.uptimeMillis());
            return currentTime - (mClocks.elapsedRealtime() - (mRealtimeStart / 1000));
        }
        return mStartClockTime;
    }

    @Override public String getStartPlatformVersion() {
        return mStartPlatformVersion;
    }

    @Override public String getEndPlatformVersion() {
        return mEndPlatformVersion;
    }

    @Override public int getParcelVersion() {
        return VERSION;
    }

    @Override public boolean getIsOnBattery() {
        return mOnBattery;
    }

    @UnsupportedAppUsage
    @Override public SparseArray<? extends BatteryStats.Uid> getUidStats() {
        return mUidStats;
    }

    private static <T extends TimeBaseObs> boolean resetIfNotNull(T t, boolean detachIfReset) {
        if (t != null) {
            return t.reset(detachIfReset);
        }
        return true;
    }

    private static <T extends TimeBaseObs> boolean resetIfNotNull(T[] t, boolean detachIfReset) {
        if (t != null) {
            boolean ret = true;
            for (int i = 0; i < t.length; i++) {
                ret &= resetIfNotNull(t[i], detachIfReset);
            }
            return ret;
        }
        return true;
    }

    private static <T extends TimeBaseObs> boolean resetIfNotNull(T[][] t, boolean detachIfReset) {
        if (t != null) {
            boolean ret = true;
            for (int i = 0; i < t.length; i++) {
                ret &= resetIfNotNull(t[i], detachIfReset);
            }
            return ret;
        }
        return true;
    }

    private static boolean resetIfNotNull(ControllerActivityCounterImpl counter,
            boolean detachIfReset) {
        if (counter != null) {
            counter.reset(detachIfReset);
        }
        return true;
    }

    private static <T extends TimeBaseObs> void detachIfNotNull(T t) {
        if (t != null) {
            t.detach();
        }
    }

    private static <T extends TimeBaseObs> void detachIfNotNull(T[] t) {
        if (t != null) {
            for (int i = 0; i < t.length; i++) {
                detachIfNotNull(t[i]);
            }
        }
    }

    private static <T extends TimeBaseObs> void detachIfNotNull(T[][] t) {
        if (t != null) {
            for (int i = 0; i < t.length; i++) {
                detachIfNotNull(t[i]);
            }
        }
    }

    private static void detachIfNotNull(ControllerActivityCounterImpl counter) {
        if (counter != null) {
            counter.detach();
        }
    }

    /**
     * The statistics associated with a particular uid.
     */
    public static class Uid extends BatteryStats.Uid {
        /**
         * BatteryStatsImpl that we are associated with.
         */
        protected BatteryStatsImpl mBsi;

        final int mUid;

        /** TimeBase for when uid is in background and device is on battery. */
        @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
        public final TimeBase mOnBatteryBackgroundTimeBase;
        @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
        public final TimeBase mOnBatteryScreenOffBackgroundTimeBase;

        boolean mWifiRunning;
        StopwatchTimer mWifiRunningTimer;

        boolean mFullWifiLockOut;
        StopwatchTimer mFullWifiLockTimer;

        boolean mWifiScanStarted;
        DualTimer mWifiScanTimer;

        static final int NO_BATCHED_SCAN_STARTED = -1;
        int mWifiBatchedScanBinStarted = NO_BATCHED_SCAN_STARTED;
        StopwatchTimer[] mWifiBatchedScanTimer;

        int mWifiMulticastWakelockCount;
        StopwatchTimer mWifiMulticastTimer;

        StopwatchTimer mAudioTurnedOnTimer;
        StopwatchTimer mVideoTurnedOnTimer;
        StopwatchTimer mFlashlightTurnedOnTimer;
        StopwatchTimer mCameraTurnedOnTimer;
        StopwatchTimer mForegroundActivityTimer;
        StopwatchTimer mForegroundServiceTimer;
        /** Total time spent by the uid holding any partial wakelocks. */
        DualTimer mAggregatedPartialWakelockTimer;
        DualTimer mBluetoothScanTimer;
        DualTimer mBluetoothUnoptimizedScanTimer;
        Counter mBluetoothScanResultCounter;
        Counter mBluetoothScanResultBgCounter;

        int mProcessState = ActivityManager.PROCESS_STATE_NONEXISTENT;
        StopwatchTimer[] mProcessStateTimer;

        boolean mInForegroundService = false;

        BatchTimer mVibratorOnTimer;

        Counter[] mUserActivityCounters;

        LongSamplingCounter[] mNetworkByteActivityCounters;
        LongSamplingCounter[] mNetworkPacketActivityCounters;
        LongSamplingCounter mMobileRadioActiveTime;
        LongSamplingCounter mMobileRadioActiveCount;

        /**
         * How many times this UID woke up the Application Processor due to a Mobile radio packet.
         */
        private LongSamplingCounter mMobileRadioApWakeupCount;

        /**
         * How many times this UID woke up the Application Processor due to a Wifi packet.
         */
        private LongSamplingCounter mWifiRadioApWakeupCount;

        /**
         * The amount of time this uid has kept the WiFi controller in idle, tx, and rx mode.
         * Can be null if the UID has had no such activity.
         */
        private ControllerActivityCounterImpl mWifiControllerActivity;

        /**
         * The amount of time this uid has kept the Bluetooth controller in idle, tx, and rx mode.
         * Can be null if the UID has had no such activity.
         */
        private ControllerActivityCounterImpl mBluetoothControllerActivity;

        /**
         * The amount of time this uid has kept the Modem controller in idle, tx, and rx mode.
         * Can be null if the UID has had no such activity.
         */
        private ControllerActivityCounterImpl mModemControllerActivity;

        /**
         * The CPU times we had at the last history details update.
         */
        long mLastStepUserTime;
        long mLastStepSystemTime;
        long mCurStepUserTime;
        long mCurStepSystemTime;

        LongSamplingCounter mUserCpuTime;
        LongSamplingCounter mSystemCpuTime;
        LongSamplingCounter[][] mCpuClusterSpeedTimesUs;
        LongSamplingCounter mCpuActiveTimeMs;

        LongSamplingCounterArray mCpuFreqTimeMs;
        LongSamplingCounterArray mScreenOffCpuFreqTimeMs;
        LongSamplingCounterArray mCpuClusterTimesMs;

        LongSamplingCounterArray[] mProcStateTimeMs;
        LongSamplingCounterArray[] mProcStateScreenOffTimeMs;

        IntArray mChildUids;

        /**
         * The statistics we have collected for this uid's wake locks.
         */
        final OverflowArrayMap<Wakelock> mWakelockStats;

        /**
         * The statistics we have collected for this uid's syncs.
         */
        final OverflowArrayMap<DualTimer> mSyncStats;

        /**
         * The statistics we have collected for this uid's jobs.
         */
        final OverflowArrayMap<DualTimer> mJobStats;

        /**
         * Count of the jobs that have completed and the reasons why they completed.
         */
        final ArrayMap<String, SparseIntArray> mJobCompletions = new ArrayMap<>();

        /**
         * Count of app launch events that had associated deferred job counts or info about
         * last time a job was run.
         */
        Counter mJobsDeferredEventCount;

        /**
         * Count of deferred jobs that were pending when the app was launched or brought to
         * the foreground through a user interaction.
         */
        Counter mJobsDeferredCount;

        /**
         * Sum of time since the last time a job was run for this app before it was launched.
         */
        LongSamplingCounter mJobsFreshnessTimeMs;

        /**
         * Array of counts of instances where the time since the last job was run for the app
         * fell within one of the thresholds in {@link #JOB_FRESHNESS_BUCKETS}.
         */
        final Counter[] mJobsFreshnessBuckets;

        /**
         * The statistics we have collected for this uid's sensor activations.
         */
        final SparseArray<Sensor> mSensorStats = new SparseArray<>();

        /**
         * The statistics we have collected for this uid's processes.
         */
        final ArrayMap<String, Proc> mProcessStats = new ArrayMap<>();

        /**
         * The statistics we have collected for this uid's processes.
         */
        final ArrayMap<String, Pkg> mPackageStats = new ArrayMap<>();

        /**
         * The transient wake stats we have collected for this uid's pids.
         */
        final SparseArray<Pid> mPids = new SparseArray<>();

        public Uid(BatteryStatsImpl bsi, int uid) {
            mBsi = bsi;
            mUid = uid;

            /* Observer list of TimeBase object in Uid is short */
            mOnBatteryBackgroundTimeBase = new TimeBase(false);
            mOnBatteryBackgroundTimeBase.init(mBsi.mClocks.uptimeMillis() * 1000,
                    mBsi.mClocks.elapsedRealtime() * 1000);
            /* Observer list of TimeBase object in Uid is short */
            mOnBatteryScreenOffBackgroundTimeBase = new TimeBase(false);
            mOnBatteryScreenOffBackgroundTimeBase.init(mBsi.mClocks.uptimeMillis() * 1000,
                    mBsi.mClocks.elapsedRealtime() * 1000);

            mUserCpuTime = new LongSamplingCounter(mBsi.mOnBatteryTimeBase);
            mSystemCpuTime = new LongSamplingCounter(mBsi.mOnBatteryTimeBase);
            mCpuActiveTimeMs = new LongSamplingCounter(mBsi.mOnBatteryTimeBase);
            mCpuClusterTimesMs = new LongSamplingCounterArray(mBsi.mOnBatteryTimeBase);

            mWakelockStats = mBsi.new OverflowArrayMap<Wakelock>(uid) {
                @Override public Wakelock instantiateObject() {
                    return new Wakelock(mBsi, Uid.this);
                }
            };
            mSyncStats = mBsi.new OverflowArrayMap<DualTimer>(uid) {
                @Override public DualTimer instantiateObject() {
                    return new DualTimer(mBsi.mClocks, Uid.this, SYNC, null,
                            mBsi.mOnBatteryTimeBase, mOnBatteryBackgroundTimeBase);
                }
            };
            mJobStats = mBsi.new OverflowArrayMap<DualTimer>(uid) {
                @Override public DualTimer instantiateObject() {
                    return new DualTimer(mBsi.mClocks, Uid.this, JOB, null,
                            mBsi.mOnBatteryTimeBase, mOnBatteryBackgroundTimeBase);
                }
            };

            mWifiRunningTimer = new StopwatchTimer(mBsi.mClocks, this, WIFI_RUNNING,
                    mBsi.mWifiRunningTimers, mBsi.mOnBatteryTimeBase);
            mFullWifiLockTimer = new StopwatchTimer(mBsi.mClocks, this, FULL_WIFI_LOCK,
                    mBsi.mFullWifiLockTimers, mBsi.mOnBatteryTimeBase);
            mWifiScanTimer = new DualTimer(mBsi.mClocks, this, WIFI_SCAN,
                    mBsi.mWifiScanTimers, mBsi.mOnBatteryTimeBase, mOnBatteryBackgroundTimeBase);
            mWifiBatchedScanTimer = new StopwatchTimer[NUM_WIFI_BATCHED_SCAN_BINS];
            mWifiMulticastTimer = new StopwatchTimer(mBsi.mClocks, this, WIFI_MULTICAST_ENABLED,
                    mBsi.mWifiMulticastTimers, mBsi.mOnBatteryTimeBase);
            mProcessStateTimer = new StopwatchTimer[NUM_PROCESS_STATE];
            mJobsDeferredEventCount = new Counter(mBsi.mOnBatteryTimeBase);
            mJobsDeferredCount = new Counter(mBsi.mOnBatteryTimeBase);
            mJobsFreshnessTimeMs = new LongSamplingCounter(mBsi.mOnBatteryTimeBase);
            mJobsFreshnessBuckets = new Counter[JOB_FRESHNESS_BUCKETS.length];
        }

        @VisibleForTesting
        public void setProcessStateForTest(int procState) {
            mProcessState = procState;
        }

        @Override
        public long[] getCpuFreqTimes(int which) {
            return nullIfAllZeros(mCpuFreqTimeMs, which);
        }

        @Override
        public long[] getScreenOffCpuFreqTimes(int which) {
            return nullIfAllZeros(mScreenOffCpuFreqTimeMs, which);
        }

        @Override
        public long getCpuActiveTime() {
            return mCpuActiveTimeMs.getCountLocked(STATS_SINCE_CHARGED);
        }

        @Override
        public long[] getCpuClusterTimes() {
            return nullIfAllZeros(mCpuClusterTimesMs, STATS_SINCE_CHARGED);
        }


        @Override
        public long[] getCpuFreqTimes(int which, int procState) {
            if (which < 0 || which >= NUM_PROCESS_STATE) {
                return null;
            }
            if (mProcStateTimeMs == null) {
                return null;
            }
            if (!mBsi.mPerProcStateCpuTimesAvailable) {
                mProcStateTimeMs = null;
                return null;
            }
            return nullIfAllZeros(mProcStateTimeMs[procState], which);
        }

        @Override
        public long[] getScreenOffCpuFreqTimes(int which, int procState) {
            if (which < 0 || which >= NUM_PROCESS_STATE) {
                return null;
            }
            if (mProcStateScreenOffTimeMs == null) {
                return null;
            }
            if (!mBsi.mPerProcStateCpuTimesAvailable) {
                mProcStateScreenOffTimeMs = null;
                return null;
            }
            return nullIfAllZeros(mProcStateScreenOffTimeMs[procState], which);
        }

        public void addIsolatedUid(int isolatedUid) {
            if (mChildUids == null) {
                mChildUids = new IntArray();
            } else if (mChildUids.indexOf(isolatedUid) >= 0) {
                return;
            }
            mChildUids.add(isolatedUid);
        }

        public void removeIsolatedUid(int isolatedUid) {
            final int idx = mChildUids == null ? -1 : mChildUids.indexOf(isolatedUid);
            if (idx < 0) {
                return;
            }
            mChildUids.remove(idx);
        }

        private long[] nullIfAllZeros(LongSamplingCounterArray cpuTimesMs, int which) {
            if (cpuTimesMs == null) {
                return null;
            }
            final long[] counts = cpuTimesMs.getCountsLocked(which);
            if (counts == null) {
                return null;
            }
            // Return counts only if at least one of the elements is non-zero.
            for (int i = counts.length - 1; i >= 0; --i) {
                if (counts[i] != 0) {
                    return counts;
                }
            }
            return null;
        }

        private void addProcStateTimesMs(int procState, long[] cpuTimesMs, boolean onBattery) {
            if (mProcStateTimeMs == null) {
                mProcStateTimeMs = new LongSamplingCounterArray[NUM_PROCESS_STATE];
            }
            if (mProcStateTimeMs[procState] == null
                    || mProcStateTimeMs[procState].getSize() != cpuTimesMs.length) {
                detachIfNotNull(mProcStateTimeMs[procState]);
                mProcStateTimeMs[procState] = new LongSamplingCounterArray(
                        mBsi.mOnBatteryTimeBase);
            }
            mProcStateTimeMs[procState].addCountLocked(cpuTimesMs, onBattery);
        }

        private void addProcStateScreenOffTimesMs(int procState, long[] cpuTimesMs,
                boolean onBatteryScreenOff) {
            if (mProcStateScreenOffTimeMs == null) {
                mProcStateScreenOffTimeMs = new LongSamplingCounterArray[NUM_PROCESS_STATE];
            }
            if (mProcStateScreenOffTimeMs[procState] == null
                    || mProcStateScreenOffTimeMs[procState].getSize() != cpuTimesMs.length) {
                detachIfNotNull(mProcStateScreenOffTimeMs[procState]);
                mProcStateScreenOffTimeMs[procState] = new LongSamplingCounterArray(
                        mBsi.mOnBatteryScreenOffTimeBase);
            }
            mProcStateScreenOffTimeMs[procState].addCountLocked(cpuTimesMs, onBatteryScreenOff);
        }

        @Override
        public Timer getAggregatedPartialWakelockTimer() {
            return mAggregatedPartialWakelockTimer;
        }

        @Override
        @UnsupportedAppUsage
        public ArrayMap<String, ? extends BatteryStats.Uid.Wakelock> getWakelockStats() {
            return mWakelockStats.getMap();
        }

        @Override
        public Timer getMulticastWakelockStats() {
            return mWifiMulticastTimer;
        }

        @Override
        public ArrayMap<String, ? extends BatteryStats.Timer> getSyncStats() {
            return mSyncStats.getMap();
        }

        @Override
        public ArrayMap<String, ? extends BatteryStats.Timer> getJobStats() {
            return mJobStats.getMap();
        }

        @Override
        public ArrayMap<String, SparseIntArray> getJobCompletionStats() {
            return mJobCompletions;
        }

        @Override
        @UnsupportedAppUsage
        public SparseArray<? extends BatteryStats.Uid.Sensor> getSensorStats() {
            return mSensorStats;
        }

        @Override
        @UnsupportedAppUsage
        public ArrayMap<String, ? extends BatteryStats.Uid.Proc> getProcessStats() {
            return mProcessStats;
        }

        @Override
        public ArrayMap<String, ? extends BatteryStats.Uid.Pkg> getPackageStats() {
            return mPackageStats;
        }

        @Override
        @UnsupportedAppUsage
        public int getUid() {
            return mUid;
        }

        @Override
        public void noteWifiRunningLocked(long elapsedRealtimeMs) {
            if (!mWifiRunning) {
                mWifiRunning = true;
                if (mWifiRunningTimer == null) {
                    mWifiRunningTimer = new StopwatchTimer(mBsi.mClocks, Uid.this, WIFI_RUNNING,
                            mBsi.mWifiRunningTimers, mBsi.mOnBatteryTimeBase);
                }
                mWifiRunningTimer.startRunningLocked(elapsedRealtimeMs);
            }
        }

        @Override
        public void noteWifiStoppedLocked(long elapsedRealtimeMs) {
            if (mWifiRunning) {
                mWifiRunning = false;
                mWifiRunningTimer.stopRunningLocked(elapsedRealtimeMs);
            }
        }

        @Override
        public void noteFullWifiLockAcquiredLocked(long elapsedRealtimeMs) {
            if (!mFullWifiLockOut) {
                mFullWifiLockOut = true;
                if (mFullWifiLockTimer == null) {
                    mFullWifiLockTimer = new StopwatchTimer(mBsi.mClocks, Uid.this, FULL_WIFI_LOCK,
                            mBsi.mFullWifiLockTimers, mBsi.mOnBatteryTimeBase);
                }
                mFullWifiLockTimer.startRunningLocked(elapsedRealtimeMs);
            }
        }

        @Override
        public void noteFullWifiLockReleasedLocked(long elapsedRealtimeMs) {
            if (mFullWifiLockOut) {
                mFullWifiLockOut = false;
                mFullWifiLockTimer.stopRunningLocked(elapsedRealtimeMs);
            }
        }

        @Override
        public void noteWifiScanStartedLocked(long elapsedRealtimeMs) {
            if (!mWifiScanStarted) {
                mWifiScanStarted = true;
                if (mWifiScanTimer == null) {
                    mWifiScanTimer = new DualTimer(mBsi.mClocks, Uid.this, WIFI_SCAN,
                            mBsi.mWifiScanTimers, mBsi.mOnBatteryTimeBase,
                            mOnBatteryBackgroundTimeBase);
                }
                mWifiScanTimer.startRunningLocked(elapsedRealtimeMs);
            }
        }

        @Override
        public void noteWifiScanStoppedLocked(long elapsedRealtimeMs) {
            if (mWifiScanStarted) {
                mWifiScanStarted = false;
                mWifiScanTimer.stopRunningLocked(elapsedRealtimeMs);
            }
        }

        @Override
        public void noteWifiBatchedScanStartedLocked(int csph, long elapsedRealtimeMs) {
            int bin = 0;
            while (csph > 8 && bin < NUM_WIFI_BATCHED_SCAN_BINS-1) {
                csph = csph >> 3;
                bin++;
            }

            if (mWifiBatchedScanBinStarted == bin) return;

            if (mWifiBatchedScanBinStarted != NO_BATCHED_SCAN_STARTED) {
                mWifiBatchedScanTimer[mWifiBatchedScanBinStarted].
                        stopRunningLocked(elapsedRealtimeMs);
            }
            mWifiBatchedScanBinStarted = bin;
            if (mWifiBatchedScanTimer[bin] == null) {
                makeWifiBatchedScanBin(bin, null);
            }
            mWifiBatchedScanTimer[bin].startRunningLocked(elapsedRealtimeMs);
        }

        @Override
        public void noteWifiBatchedScanStoppedLocked(long elapsedRealtimeMs) {
            if (mWifiBatchedScanBinStarted != NO_BATCHED_SCAN_STARTED) {
                mWifiBatchedScanTimer[mWifiBatchedScanBinStarted].
                        stopRunningLocked(elapsedRealtimeMs);
                mWifiBatchedScanBinStarted = NO_BATCHED_SCAN_STARTED;
            }
        }

        @Override
        public void noteWifiMulticastEnabledLocked(long elapsedRealtimeMs) {
            if (mWifiMulticastWakelockCount == 0) {
                if (mWifiMulticastTimer == null) {
                    mWifiMulticastTimer = new StopwatchTimer(mBsi.mClocks, Uid.this,
                            WIFI_MULTICAST_ENABLED, mBsi.mWifiMulticastTimers, mBsi.mOnBatteryTimeBase);
                }
                mWifiMulticastTimer.startRunningLocked(elapsedRealtimeMs);
            }
            mWifiMulticastWakelockCount++;
        }

        @Override
        public void noteWifiMulticastDisabledLocked(long elapsedRealtimeMs) {
            if (mWifiMulticastWakelockCount == 0) {
                return;
            }

            mWifiMulticastWakelockCount--;
            if (mWifiMulticastWakelockCount == 0) {
                mWifiMulticastTimer.stopRunningLocked(elapsedRealtimeMs);
            }
        }

        @Override
        public ControllerActivityCounter getWifiControllerActivity() {
            return mWifiControllerActivity;
        }

        @Override
        public ControllerActivityCounter getBluetoothControllerActivity() {
            return mBluetoothControllerActivity;
        }

        @Override
        public ControllerActivityCounter getModemControllerActivity() {
            return mModemControllerActivity;
        }

        public ControllerActivityCounterImpl getOrCreateWifiControllerActivityLocked() {
            if (mWifiControllerActivity == null) {
                mWifiControllerActivity = new ControllerActivityCounterImpl(mBsi.mOnBatteryTimeBase,
                        NUM_BT_TX_LEVELS);
            }
            return mWifiControllerActivity;
        }

        public ControllerActivityCounterImpl getOrCreateBluetoothControllerActivityLocked() {
            if (mBluetoothControllerActivity == null) {
                mBluetoothControllerActivity = new ControllerActivityCounterImpl(mBsi.mOnBatteryTimeBase,
                        NUM_BT_TX_LEVELS);
            }
            return mBluetoothControllerActivity;
        }

        public ControllerActivityCounterImpl getOrCreateModemControllerActivityLocked() {
            if (mModemControllerActivity == null) {
                mModemControllerActivity = new ControllerActivityCounterImpl(mBsi.mOnBatteryTimeBase,
                        ModemActivityInfo.TX_POWER_LEVELS);
            }
            return mModemControllerActivity;
        }

        public StopwatchTimer createAudioTurnedOnTimerLocked() {
            if (mAudioTurnedOnTimer == null) {
                mAudioTurnedOnTimer = new StopwatchTimer(mBsi.mClocks, Uid.this, AUDIO_TURNED_ON,
                        mBsi.mAudioTurnedOnTimers, mBsi.mOnBatteryTimeBase);
            }
            return mAudioTurnedOnTimer;
        }

        public void noteAudioTurnedOnLocked(long elapsedRealtimeMs) {
            createAudioTurnedOnTimerLocked().startRunningLocked(elapsedRealtimeMs);
        }

        public void noteAudioTurnedOffLocked(long elapsedRealtimeMs) {
            if (mAudioTurnedOnTimer != null) {
                mAudioTurnedOnTimer.stopRunningLocked(elapsedRealtimeMs);
            }
        }

        public void noteResetAudioLocked(long elapsedRealtimeMs) {
            if (mAudioTurnedOnTimer != null) {
                mAudioTurnedOnTimer.stopAllRunningLocked(elapsedRealtimeMs);
            }
        }

        public StopwatchTimer createVideoTurnedOnTimerLocked() {
            if (mVideoTurnedOnTimer == null) {
                mVideoTurnedOnTimer = new StopwatchTimer(mBsi.mClocks, Uid.this, VIDEO_TURNED_ON,
                        mBsi.mVideoTurnedOnTimers, mBsi.mOnBatteryTimeBase);
            }
            return mVideoTurnedOnTimer;
        }

        public void noteVideoTurnedOnLocked(long elapsedRealtimeMs) {
            createVideoTurnedOnTimerLocked().startRunningLocked(elapsedRealtimeMs);
        }

        public void noteVideoTurnedOffLocked(long elapsedRealtimeMs) {
            if (mVideoTurnedOnTimer != null) {
                mVideoTurnedOnTimer.stopRunningLocked(elapsedRealtimeMs);
            }
        }

        public void noteResetVideoLocked(long elapsedRealtimeMs) {
            if (mVideoTurnedOnTimer != null) {
                mVideoTurnedOnTimer.stopAllRunningLocked(elapsedRealtimeMs);
            }
        }

        public StopwatchTimer createFlashlightTurnedOnTimerLocked() {
            if (mFlashlightTurnedOnTimer == null) {
                mFlashlightTurnedOnTimer = new StopwatchTimer(mBsi.mClocks, Uid.this,
                        FLASHLIGHT_TURNED_ON, mBsi.mFlashlightTurnedOnTimers, mBsi.mOnBatteryTimeBase);
            }
            return mFlashlightTurnedOnTimer;
        }

        public void noteFlashlightTurnedOnLocked(long elapsedRealtimeMs) {
            createFlashlightTurnedOnTimerLocked().startRunningLocked(elapsedRealtimeMs);
        }

        public void noteFlashlightTurnedOffLocked(long elapsedRealtimeMs) {
            if (mFlashlightTurnedOnTimer != null) {
                mFlashlightTurnedOnTimer.stopRunningLocked(elapsedRealtimeMs);
            }
        }

        public void noteResetFlashlightLocked(long elapsedRealtimeMs) {
            if (mFlashlightTurnedOnTimer != null) {
                mFlashlightTurnedOnTimer.stopAllRunningLocked(elapsedRealtimeMs);
            }
        }

        public StopwatchTimer createCameraTurnedOnTimerLocked() {
            if (mCameraTurnedOnTimer == null) {
                mCameraTurnedOnTimer = new StopwatchTimer(mBsi.mClocks, Uid.this, CAMERA_TURNED_ON,
                        mBsi.mCameraTurnedOnTimers, mBsi.mOnBatteryTimeBase);
            }
            return mCameraTurnedOnTimer;
        }

        public void noteCameraTurnedOnLocked(long elapsedRealtimeMs) {
            createCameraTurnedOnTimerLocked().startRunningLocked(elapsedRealtimeMs);
        }

        public void noteCameraTurnedOffLocked(long elapsedRealtimeMs) {
            if (mCameraTurnedOnTimer != null) {
                mCameraTurnedOnTimer.stopRunningLocked(elapsedRealtimeMs);
            }
        }

        public void noteResetCameraLocked(long elapsedRealtimeMs) {
            if (mCameraTurnedOnTimer != null) {
                mCameraTurnedOnTimer.stopAllRunningLocked(elapsedRealtimeMs);
            }
        }

        public StopwatchTimer createForegroundActivityTimerLocked() {
            if (mForegroundActivityTimer == null) {
                mForegroundActivityTimer = new StopwatchTimer(mBsi.mClocks, Uid.this,
                        FOREGROUND_ACTIVITY, null, mBsi.mOnBatteryTimeBase);
            }
            return mForegroundActivityTimer;
        }

        public StopwatchTimer createForegroundServiceTimerLocked() {
            if (mForegroundServiceTimer == null) {
                mForegroundServiceTimer = new StopwatchTimer(mBsi.mClocks, Uid.this,
                        FOREGROUND_SERVICE, null, mBsi.mOnBatteryTimeBase);
            }
            return mForegroundServiceTimer;
        }

        public DualTimer createAggregatedPartialWakelockTimerLocked() {
            if (mAggregatedPartialWakelockTimer == null) {
                mAggregatedPartialWakelockTimer = new DualTimer(mBsi.mClocks, this,
                        AGGREGATED_WAKE_TYPE_PARTIAL, null,
                        mBsi.mOnBatteryScreenOffTimeBase, mOnBatteryScreenOffBackgroundTimeBase);
            }
            return mAggregatedPartialWakelockTimer;
        }

        public DualTimer createBluetoothScanTimerLocked() {
            if (mBluetoothScanTimer == null) {
                mBluetoothScanTimer = new DualTimer(mBsi.mClocks, Uid.this, BLUETOOTH_SCAN_ON,
                        mBsi.mBluetoothScanOnTimers, mBsi.mOnBatteryTimeBase,
                        mOnBatteryBackgroundTimeBase);
            }
            return mBluetoothScanTimer;
        }

        public DualTimer createBluetoothUnoptimizedScanTimerLocked() {
            if (mBluetoothUnoptimizedScanTimer == null) {
                mBluetoothUnoptimizedScanTimer = new DualTimer(mBsi.mClocks, Uid.this,
                        BLUETOOTH_UNOPTIMIZED_SCAN_ON, null,
                        mBsi.mOnBatteryTimeBase, mOnBatteryBackgroundTimeBase);
            }
            return mBluetoothUnoptimizedScanTimer;
        }

        public void noteBluetoothScanStartedLocked(long elapsedRealtimeMs,
                boolean isUnoptimized) {
            createBluetoothScanTimerLocked().startRunningLocked(elapsedRealtimeMs);
            if (isUnoptimized) {
                createBluetoothUnoptimizedScanTimerLocked().startRunningLocked(elapsedRealtimeMs);
            }
        }

        public void noteBluetoothScanStoppedLocked(long elapsedRealtimeMs, boolean isUnoptimized) {
            if (mBluetoothScanTimer != null) {
                mBluetoothScanTimer.stopRunningLocked(elapsedRealtimeMs);
            }
            if (isUnoptimized && mBluetoothUnoptimizedScanTimer != null) {
                mBluetoothUnoptimizedScanTimer.stopRunningLocked(elapsedRealtimeMs);
            }
        }

        public void noteResetBluetoothScanLocked(long elapsedRealtimeMs) {
            if (mBluetoothScanTimer != null) {
                mBluetoothScanTimer.stopAllRunningLocked(elapsedRealtimeMs);
            }
            if (mBluetoothUnoptimizedScanTimer != null) {
                mBluetoothUnoptimizedScanTimer.stopAllRunningLocked(elapsedRealtimeMs);
            }
        }

        public Counter createBluetoothScanResultCounterLocked() {
            if (mBluetoothScanResultCounter == null) {
                mBluetoothScanResultCounter = new Counter(mBsi.mOnBatteryTimeBase);
            }
            return mBluetoothScanResultCounter;
        }

        public Counter createBluetoothScanResultBgCounterLocked() {
            if (mBluetoothScanResultBgCounter == null) {
                mBluetoothScanResultBgCounter = new Counter(mOnBatteryBackgroundTimeBase);
            }
            return mBluetoothScanResultBgCounter;
        }

        public void noteBluetoothScanResultsLocked(int numNewResults) {
            createBluetoothScanResultCounterLocked().addAtomic(numNewResults);
            // Uses background timebase, so the count will only be incremented if uid in background.
            createBluetoothScanResultBgCounterLocked().addAtomic(numNewResults);
        }

        @Override
        public void noteActivityResumedLocked(long elapsedRealtimeMs) {
            // We always start, since we want multiple foreground PIDs to nest
            createForegroundActivityTimerLocked().startRunningLocked(elapsedRealtimeMs);
        }

        @Override
        public void noteActivityPausedLocked(long elapsedRealtimeMs) {
            if (mForegroundActivityTimer != null) {
                mForegroundActivityTimer.stopRunningLocked(elapsedRealtimeMs);
            }
        }

        public void noteForegroundServiceResumedLocked(long elapsedRealtimeMs) {
            createForegroundServiceTimerLocked().startRunningLocked(elapsedRealtimeMs);
        }

        public void noteForegroundServicePausedLocked(long elapsedRealtimeMs) {
            if (mForegroundServiceTimer != null) {
                mForegroundServiceTimer.stopRunningLocked(elapsedRealtimeMs);
            }
        }

        public BatchTimer createVibratorOnTimerLocked() {
            if (mVibratorOnTimer == null) {
                mVibratorOnTimer = new BatchTimer(mBsi.mClocks, Uid.this, VIBRATOR_ON,
                        mBsi.mOnBatteryTimeBase);
            }
            return mVibratorOnTimer;
        }

        public void noteVibratorOnLocked(long durationMillis) {
            createVibratorOnTimerLocked().addDuration(mBsi, durationMillis);
        }

        public void noteVibratorOffLocked() {
            if (mVibratorOnTimer != null) {
                mVibratorOnTimer.abortLastDuration(mBsi);
            }
        }

        @Override
        @UnsupportedAppUsage
        public long getWifiRunningTime(long elapsedRealtimeUs, int which) {
            if (mWifiRunningTimer == null) {
                return 0;
            }
            return mWifiRunningTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
        }

        @Override
        public long getFullWifiLockTime(long elapsedRealtimeUs, int which) {
            if (mFullWifiLockTimer == null) {
                return 0;
            }
            return mFullWifiLockTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
        }

        @Override
        @UnsupportedAppUsage
        public long getWifiScanTime(long elapsedRealtimeUs, int which) {
            if (mWifiScanTimer == null) {
                return 0;
            }
            return mWifiScanTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
        }

        @Override
        public int getWifiScanCount(int which) {
            if (mWifiScanTimer == null) {
                return 0;
            }
            return mWifiScanTimer.getCountLocked(which);
        }

        @Override
        public Timer getWifiScanTimer() {
            return mWifiScanTimer;
        }

        @Override
        public int getWifiScanBackgroundCount(int which) {
            if (mWifiScanTimer == null || mWifiScanTimer.getSubTimer() == null) {
                return 0;
            }
            return mWifiScanTimer.getSubTimer().getCountLocked(which);
        }

        @Override
        public long getWifiScanActualTime(final long elapsedRealtimeUs) {
            if (mWifiScanTimer == null) {
                return 0;
            }
            final long elapsedRealtimeMs = (elapsedRealtimeUs + 500) / 1000;
            return mWifiScanTimer.getTotalDurationMsLocked(elapsedRealtimeMs) * 1000;
        }

        @Override
        public long getWifiScanBackgroundTime(final long elapsedRealtimeUs) {
            if (mWifiScanTimer == null || mWifiScanTimer.getSubTimer() == null) {
                return 0;
            }
            final long elapsedRealtimeMs = (elapsedRealtimeUs + 500) / 1000;
            return mWifiScanTimer.getSubTimer().getTotalDurationMsLocked(elapsedRealtimeMs) * 1000;
        }

        @Override
        public Timer getWifiScanBackgroundTimer() {
            if (mWifiScanTimer == null) {
                return null;
            }
            return mWifiScanTimer.getSubTimer();
        }

        @Override
        public long getWifiBatchedScanTime(int csphBin, long elapsedRealtimeUs, int which) {
            if (csphBin < 0 || csphBin >= NUM_WIFI_BATCHED_SCAN_BINS) return 0;
            if (mWifiBatchedScanTimer[csphBin] == null) {
                return 0;
            }
            return mWifiBatchedScanTimer[csphBin].getTotalTimeLocked(elapsedRealtimeUs, which);
        }

        @Override
        public int getWifiBatchedScanCount(int csphBin, int which) {
            if (csphBin < 0 || csphBin >= NUM_WIFI_BATCHED_SCAN_BINS) return 0;
            if (mWifiBatchedScanTimer[csphBin] == null) {
                return 0;
            }
            return mWifiBatchedScanTimer[csphBin].getCountLocked(which);
        }

        @Override
        public long getWifiMulticastTime(long elapsedRealtimeUs, int which) {
            if (mWifiMulticastTimer == null) {
                return 0;
            }
            return mWifiMulticastTimer.getTotalTimeLocked(elapsedRealtimeUs, which);
        }

        @Override
        public Timer getAudioTurnedOnTimer() {
            return mAudioTurnedOnTimer;
        }

        @Override
        public Timer getVideoTurnedOnTimer() {
            return mVideoTurnedOnTimer;
        }

        @Override
        public Timer getFlashlightTurnedOnTimer() {
            return mFlashlightTurnedOnTimer;
        }

        @Override
        public Timer getCameraTurnedOnTimer() {
            return mCameraTurnedOnTimer;
        }

        @Override
        public Timer getForegroundActivityTimer() {
            return mForegroundActivityTimer;
        }

        @Override
        public Timer getForegroundServiceTimer() {
            return mForegroundServiceTimer;
        }

        @Override
        public Timer getBluetoothScanTimer() {
            return mBluetoothScanTimer;
        }

        @Override
        public Timer getBluetoothScanBackgroundTimer() {
            if (mBluetoothScanTimer == null) {
                return null;
            }
            return mBluetoothScanTimer.getSubTimer();
        }

        @Override
        public Timer getBluetoothUnoptimizedScanTimer() {
            return mBluetoothUnoptimizedScanTimer;
        }

        @Override
        public Timer getBluetoothUnoptimizedScanBackgroundTimer() {
            if (mBluetoothUnoptimizedScanTimer == null) {
                return null;
            }
            return mBluetoothUnoptimizedScanTimer.getSubTimer();
        }

        @Override
        public Counter getBluetoothScanResultCounter() {
            return mBluetoothScanResultCounter;
        }

        @Override
        public Counter getBluetoothScanResultBgCounter() {
            return mBluetoothScanResultBgCounter;
        }

        void makeProcessState(int i, Parcel in) {
            if (i < 0 || i >= NUM_PROCESS_STATE) return;

            detachIfNotNull(mProcessStateTimer[i]);
            if (in == null) {
                mProcessStateTimer[i] = new StopwatchTimer(mBsi.mClocks, this, PROCESS_STATE, null,
                        mBsi.mOnBatteryTimeBase);
            } else {
                mProcessStateTimer[i] = new StopwatchTimer(mBsi.mClocks, this, PROCESS_STATE, null,
                        mBsi.mOnBatteryTimeBase, in);
            }
        }

        @Override
        public long getProcessStateTime(int state, long elapsedRealtimeUs, int which) {
            if (state < 0 || state >= NUM_PROCESS_STATE) return 0;
            if (mProcessStateTimer[state] == null) {
                return 0;
            }
            return mProcessStateTimer[state].getTotalTimeLocked(elapsedRealtimeUs, which);
        }

        @Override
        public Timer getProcessStateTimer(int state) {
            if (state < 0 || state >= NUM_PROCESS_STATE) return null;
            return mProcessStateTimer[state];
        }

        @Override
        public Timer getVibratorOnTimer() {
            return mVibratorOnTimer;
        }

        @Override
        public void noteUserActivityLocked(int type) {
            if (mUserActivityCounters == null) {
                initUserActivityLocked();
            }
            if (type >= 0 && type < NUM_USER_ACTIVITY_TYPES) {
                mUserActivityCounters[type].stepAtomic();
            } else {
                Slog.w(TAG, "Unknown user activity type " + type + " was specified.",
                        new Throwable());
            }
        }

        @Override
        public boolean hasUserActivity() {
            return mUserActivityCounters != null;
        }

        @Override
        public int getUserActivityCount(int type, int which) {
            if (mUserActivityCounters == null) {
                return 0;
            }
            return mUserActivityCounters[type].getCountLocked(which);
        }

        void makeWifiBatchedScanBin(int i, Parcel in) {
            if (i < 0 || i >= NUM_WIFI_BATCHED_SCAN_BINS) return;

            ArrayList<StopwatchTimer> collected = mBsi.mWifiBatchedScanTimers.get(i);
            if (collected == null) {
                collected = new ArrayList<StopwatchTimer>();
                mBsi.mWifiBatchedScanTimers.put(i, collected);
            }
            detachIfNotNull(mWifiBatchedScanTimer[i]);
            if (in == null) {
                mWifiBatchedScanTimer[i] = new StopwatchTimer(mBsi.mClocks, this, WIFI_BATCHED_SCAN,
                        collected, mBsi.mOnBatteryTimeBase);
            } else {
                mWifiBatchedScanTimer[i] = new StopwatchTimer(mBsi.mClocks, this, WIFI_BATCHED_SCAN,
                        collected, mBsi.mOnBatteryTimeBase, in);
            }
        }


        void initUserActivityLocked() {
            detachIfNotNull(mUserActivityCounters);
            mUserActivityCounters = new Counter[NUM_USER_ACTIVITY_TYPES];
            for (int i=0; i<NUM_USER_ACTIVITY_TYPES; i++) {
                mUserActivityCounters[i] = new Counter(mBsi.mOnBatteryTimeBase);
            }
        }

        void noteNetworkActivityLocked(int type, long deltaBytes, long deltaPackets) {
            if (mNetworkByteActivityCounters == null) {
                initNetworkActivityLocked();
            }
            if (type >= 0 && type < NUM_NETWORK_ACTIVITY_TYPES) {
                mNetworkByteActivityCounters[type].addCountLocked(deltaBytes);
                mNetworkPacketActivityCounters[type].addCountLocked(deltaPackets);
            } else {
                Slog.w(TAG, "Unknown network activity type " + type + " was specified.",
                        new Throwable());
            }
        }

        void noteMobileRadioActiveTimeLocked(long batteryUptime) {
            if (mNetworkByteActivityCounters == null) {
                initNetworkActivityLocked();
            }
            mMobileRadioActiveTime.addCountLocked(batteryUptime);
            mMobileRadioActiveCount.addCountLocked(1);
        }

        @Override
        public boolean hasNetworkActivity() {
            return mNetworkByteActivityCounters != null;
        }

        @Override
        public long getNetworkActivityBytes(int type, int which) {
            if (mNetworkByteActivityCounters != null && type >= 0
                    && type < mNetworkByteActivityCounters.length) {
                return mNetworkByteActivityCounters[type].getCountLocked(which);
            } else {
                return 0;
            }
        }

        @Override
        public long getNetworkActivityPackets(int type, int which) {
            if (mNetworkPacketActivityCounters != null && type >= 0
                    && type < mNetworkPacketActivityCounters.length) {
                return mNetworkPacketActivityCounters[type].getCountLocked(which);
            } else {
                return 0;
            }
        }

        @Override
        public long getMobileRadioActiveTime(int which) {
            return mMobileRadioActiveTime != null
                    ? mMobileRadioActiveTime.getCountLocked(which) : 0;
        }

        @Override
        public int getMobileRadioActiveCount(int which) {
            return mMobileRadioActiveCount != null
                    ? (int)mMobileRadioActiveCount.getCountLocked(which) : 0;
        }

        @Override
        public long getUserCpuTimeUs(int which) {
            return mUserCpuTime.getCountLocked(which);
        }

        @Override
        public long getSystemCpuTimeUs(int which) {
            return mSystemCpuTime.getCountLocked(which);
        }

        @Override
        public long getTimeAtCpuSpeed(int cluster, int step, int which) {
            if (mCpuClusterSpeedTimesUs != null) {
                if (cluster >= 0 && cluster < mCpuClusterSpeedTimesUs.length) {
                    final LongSamplingCounter[] cpuSpeedTimesUs = mCpuClusterSpeedTimesUs[cluster];
                    if (cpuSpeedTimesUs != null) {
                        if (step >= 0 && step < cpuSpeedTimesUs.length) {
                            final LongSamplingCounter c = cpuSpeedTimesUs[step];
                            if (c != null) {
                                return c.getCountLocked(which);
                            }
                        }
                    }
                }
            }
            return 0;
        }

        public void noteMobileRadioApWakeupLocked() {
            if (mMobileRadioApWakeupCount == null) {
                mMobileRadioApWakeupCount = new LongSamplingCounter(mBsi.mOnBatteryTimeBase);
            }
            mMobileRadioApWakeupCount.addCountLocked(1);
        }

        @Override
        public long getMobileRadioApWakeupCount(int which) {
            if (mMobileRadioApWakeupCount != null) {
                return mMobileRadioApWakeupCount.getCountLocked(which);
            }
            return 0;
        }

        public void noteWifiRadioApWakeupLocked() {
            if (mWifiRadioApWakeupCount == null) {
                mWifiRadioApWakeupCount = new LongSamplingCounter(mBsi.mOnBatteryTimeBase);
            }
            mWifiRadioApWakeupCount.addCountLocked(1);
        }

        @Override
        public long getWifiRadioApWakeupCount(int which) {
            if (mWifiRadioApWakeupCount != null) {
                return mWifiRadioApWakeupCount.getCountLocked(which);
            }
            return 0;
        }

        @Override
        public void getDeferredJobsCheckinLineLocked(StringBuilder sb, int which) {
            sb.setLength(0);
            final int deferredEventCount = mJobsDeferredEventCount.getCountLocked(which);
            if (deferredEventCount == 0) {
                return;
            }
            final int deferredCount = mJobsDeferredCount.getCountLocked(which);
            final long totalLatency = mJobsFreshnessTimeMs.getCountLocked(which);
            sb.append(deferredEventCount); sb.append(',');
            sb.append(deferredCount); sb.append(',');
            sb.append(totalLatency);
            for (int i = 0; i < JOB_FRESHNESS_BUCKETS.length; i++) {
                if (mJobsFreshnessBuckets[i] == null) {
                    sb.append(",0");
                } else {
                    sb.append(",");
                    sb.append(mJobsFreshnessBuckets[i].getCountLocked(which));
                }
            }
        }

        @Override
        public void getDeferredJobsLineLocked(StringBuilder sb, int which) {
            sb.setLength(0);
            final int deferredEventCount = mJobsDeferredEventCount.getCountLocked(which);
            if (deferredEventCount == 0) {
                return;
            }
            final int deferredCount = mJobsDeferredCount.getCountLocked(which);
            final long totalLatency = mJobsFreshnessTimeMs.getCountLocked(which);
            sb.append("times="); sb.append(deferredEventCount); sb.append(", ");
            sb.append("count="); sb.append(deferredCount); sb.append(", ");
            sb.append("totalLatencyMs="); sb.append(totalLatency); sb.append(", ");
            for (int i = 0; i < JOB_FRESHNESS_BUCKETS.length; i++) {
                sb.append("<"); sb.append(JOB_FRESHNESS_BUCKETS[i]); sb.append("ms=");
                if (mJobsFreshnessBuckets[i] == null) {
                    sb.append("0");
                } else {
                    sb.append(mJobsFreshnessBuckets[i].getCountLocked(which));
                }
                sb.append(" ");
            }
        }

        void initNetworkActivityLocked() {
            detachIfNotNull(mNetworkByteActivityCounters);
            mNetworkByteActivityCounters = new LongSamplingCounter[NUM_NETWORK_ACTIVITY_TYPES];
            detachIfNotNull(mNetworkPacketActivityCounters);
            mNetworkPacketActivityCounters = new LongSamplingCounter[NUM_NETWORK_ACTIVITY_TYPES];
            for (int i = 0; i < NUM_NETWORK_ACTIVITY_TYPES; i++) {
                mNetworkByteActivityCounters[i] = new LongSamplingCounter(mBsi.mOnBatteryTimeBase);
                mNetworkPacketActivityCounters[i] = new LongSamplingCounter(mBsi.mOnBatteryTimeBase);
            }
            detachIfNotNull(mMobileRadioActiveTime);
            mMobileRadioActiveTime = new LongSamplingCounter(mBsi.mOnBatteryTimeBase);
            detachIfNotNull(mMobileRadioActiveCount);
            mMobileRadioActiveCount = new LongSamplingCounter(mBsi.mOnBatteryTimeBase);
        }

        /**
         * Clear all stats for this uid.  Returns true if the uid is completely
         * inactive so can be dropped.
         */
        @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
        public boolean reset(long uptime, long realtime) {
            boolean active = false;

            mOnBatteryBackgroundTimeBase.init(uptime, realtime);
            mOnBatteryScreenOffBackgroundTimeBase.init(uptime, realtime);

            if (mWifiRunningTimer != null) {
                active |= !mWifiRunningTimer.reset(false);
                active |= mWifiRunning;
            }
            if (mFullWifiLockTimer != null) {
                active |= !mFullWifiLockTimer.reset(false);
                active |= mFullWifiLockOut;
            }
            if (mWifiScanTimer != null) {
                active |= !mWifiScanTimer.reset(false);
                active |= mWifiScanStarted;
            }
            if (mWifiBatchedScanTimer != null) {
                for (int i = 0; i < NUM_WIFI_BATCHED_SCAN_BINS; i++) {
                    if (mWifiBatchedScanTimer[i] != null) {
                        active |= !mWifiBatchedScanTimer[i].reset(false);
                    }
                }
                active |= (mWifiBatchedScanBinStarted != NO_BATCHED_SCAN_STARTED);
            }
            if (mWifiMulticastTimer != null) {
                active |= !mWifiMulticastTimer.reset(false);
                active |= (mWifiMulticastWakelockCount > 0);
            }

            active |= !resetIfNotNull(mAudioTurnedOnTimer, false);
            active |= !resetIfNotNull(mVideoTurnedOnTimer, false);
            active |= !resetIfNotNull(mFlashlightTurnedOnTimer, false);
            active |= !resetIfNotNull(mCameraTurnedOnTimer, false);
            active |= !resetIfNotNull(mForegroundActivityTimer, false);
            active |= !resetIfNotNull(mForegroundServiceTimer, false);
            active |= !resetIfNotNull(mAggregatedPartialWakelockTimer, false);
            active |= !resetIfNotNull(mBluetoothScanTimer, false);
            active |= !resetIfNotNull(mBluetoothUnoptimizedScanTimer, false);

            resetIfNotNull(mBluetoothScanResultCounter, false);
            resetIfNotNull(mBluetoothScanResultBgCounter, false);

            if (mProcessStateTimer != null) {
                for (int i = 0; i < NUM_PROCESS_STATE; i++) {
                    active |= !resetIfNotNull(mProcessStateTimer[i], false);
                }
                active |= (mProcessState != ActivityManager.PROCESS_STATE_NONEXISTENT);
            }
            if (mVibratorOnTimer != null) {
                if (mVibratorOnTimer.reset(false)) {
                    mVibratorOnTimer.detach();
                    mVibratorOnTimer = null;
                } else {
                    active = true;
                }
            }

            resetIfNotNull(mUserActivityCounters, false);

            resetIfNotNull(mNetworkByteActivityCounters, false);
            resetIfNotNull(mNetworkPacketActivityCounters, false);
            resetIfNotNull(mMobileRadioActiveTime, false);
            resetIfNotNull(mMobileRadioActiveCount, false);

            resetIfNotNull(mWifiControllerActivity, false);
            resetIfNotNull(mBluetoothControllerActivity, false);
            resetIfNotNull(mModemControllerActivity, false);

            resetIfNotNull(mUserCpuTime, false);
            resetIfNotNull(mSystemCpuTime, false);

            resetIfNotNull(mCpuClusterSpeedTimesUs, false);

            resetIfNotNull(mCpuFreqTimeMs, false);
            resetIfNotNull(mScreenOffCpuFreqTimeMs, false);


            resetIfNotNull(mCpuActiveTimeMs, false);
            resetIfNotNull(mCpuClusterTimesMs, false);

            resetIfNotNull(mProcStateTimeMs, false);

            resetIfNotNull(mProcStateScreenOffTimeMs, false);

            resetIfNotNull(mMobileRadioApWakeupCount, false);

            resetIfNotNull(mWifiRadioApWakeupCount, false);


            final ArrayMap<String, Wakelock> wakeStats = mWakelockStats.getMap();
            for (int iw=wakeStats.size()-1; iw>=0; iw--) {
                Wakelock wl = wakeStats.valueAt(iw);
                if (wl.reset()) {
                    wakeStats.removeAt(iw);
                } else {
                    active = true;
                }
            }
            mWakelockStats.cleanup();
            final ArrayMap<String, DualTimer> syncStats = mSyncStats.getMap();
            for (int is=syncStats.size()-1; is>=0; is--) {
                DualTimer timer = syncStats.valueAt(is);
                if (timer.reset(false)) {
                    syncStats.removeAt(is);
                    timer.detach();
                } else {
                    active = true;
                }
            }
            mSyncStats.cleanup();
            final ArrayMap<String, DualTimer> jobStats = mJobStats.getMap();
            for (int ij=jobStats.size()-1; ij>=0; ij--) {
                DualTimer timer = jobStats.valueAt(ij);
                if (timer.reset(false)) {
                    jobStats.removeAt(ij);
                    timer.detach();
                } else {
                    active = true;
                }
            }
            mJobStats.cleanup();
            mJobCompletions.clear();

            resetIfNotNull(mJobsDeferredEventCount, false);
            resetIfNotNull(mJobsDeferredCount, false);
            resetIfNotNull(mJobsFreshnessTimeMs, false);
            resetIfNotNull(mJobsFreshnessBuckets, false);

            for (int ise = mSensorStats.size() - 1; ise >= 0; ise--) {
                Sensor s = mSensorStats.valueAt(ise);
                if (s.reset()) {
                    mSensorStats.removeAt(ise);
                } else {
                    active = true;
                }
            }

            for (int ip = mProcessStats.size() - 1; ip >= 0; ip--) {
                Proc proc = mProcessStats.valueAt(ip);
                proc.detach();
            }
            mProcessStats.clear();

            for (int i = mPids.size() - 1; i >= 0; i--) {
                Pid pid = mPids.valueAt(i);
                if (pid.mWakeNesting > 0) {
                    active = true;
                } else {
                    mPids.removeAt(i);
                }
            }


            for(int i = mPackageStats.size() - 1; i >= 0; i--) {
                Pkg p = mPackageStats.valueAt(i);
                p.detach();
            }
            mPackageStats.clear();

            mLastStepUserTime = mLastStepSystemTime = 0;
            mCurStepUserTime = mCurStepSystemTime = 0;

            return !active;
        }

        /**
         * This method MUST be called whenever the Uid object is destructed, otherwise it is a
         * memory leak in {@link TimeBase#mObservers} list.
         * Typically the Uid object is destructed when it is removed from
         * {@link BatteryStatsImpl#mUidStats}
         */
        void detachFromTimeBase() {
            detachIfNotNull(mWifiRunningTimer);
            detachIfNotNull(mFullWifiLockTimer);
            detachIfNotNull(mWifiScanTimer);
            detachIfNotNull(mWifiBatchedScanTimer);
            detachIfNotNull(mWifiMulticastTimer);
            detachIfNotNull(mAudioTurnedOnTimer);
            detachIfNotNull(mVideoTurnedOnTimer);
            detachIfNotNull(mFlashlightTurnedOnTimer);

            detachIfNotNull(mCameraTurnedOnTimer);
            detachIfNotNull(mForegroundActivityTimer);
            detachIfNotNull(mForegroundServiceTimer);

            detachIfNotNull(mAggregatedPartialWakelockTimer);

            detachIfNotNull(mBluetoothScanTimer);
            detachIfNotNull(mBluetoothUnoptimizedScanTimer);
            detachIfNotNull(mBluetoothScanResultCounter);
            detachIfNotNull(mBluetoothScanResultBgCounter);

            detachIfNotNull(mProcessStateTimer);

            detachIfNotNull(mVibratorOnTimer);

            detachIfNotNull(mUserActivityCounters);

            detachIfNotNull(mNetworkByteActivityCounters);
            detachIfNotNull(mNetworkPacketActivityCounters);

            detachIfNotNull(mMobileRadioActiveTime);
            detachIfNotNull(mMobileRadioActiveCount);
            detachIfNotNull(mMobileRadioApWakeupCount);
            detachIfNotNull(mWifiRadioApWakeupCount);

            detachIfNotNull(mWifiControllerActivity);
            detachIfNotNull(mBluetoothControllerActivity);
            detachIfNotNull(mModemControllerActivity);

            mPids.clear();

            detachIfNotNull(mUserCpuTime);
            detachIfNotNull(mSystemCpuTime);

            detachIfNotNull(mCpuClusterSpeedTimesUs);

            detachIfNotNull(mCpuActiveTimeMs);
            detachIfNotNull(mCpuFreqTimeMs);

            detachIfNotNull(mScreenOffCpuFreqTimeMs);

            detachIfNotNull(mCpuClusterTimesMs);

            detachIfNotNull(mProcStateTimeMs);

            detachIfNotNull(mProcStateScreenOffTimeMs);

            final ArrayMap<String, Wakelock> wakeStats = mWakelockStats.getMap();
            for (int iw = wakeStats.size() - 1; iw >= 0; iw--) {
                Wakelock wl = wakeStats.valueAt(iw);
                wl.detachFromTimeBase();
            }
            final ArrayMap<String, DualTimer> syncStats = mSyncStats.getMap();
            for (int is = syncStats.size() - 1; is >= 0; is--) {
                DualTimer timer = syncStats.valueAt(is);
                detachIfNotNull(timer);
            }
            final ArrayMap<String, DualTimer> jobStats = mJobStats.getMap();
            for (int ij = jobStats.size() - 1; ij >= 0; ij--) {
                DualTimer timer = jobStats.valueAt(ij);
                detachIfNotNull(timer);
            }

            detachIfNotNull(mJobsDeferredEventCount);
            detachIfNotNull(mJobsDeferredCount);
            detachIfNotNull(mJobsFreshnessTimeMs);
            detachIfNotNull(mJobsFreshnessBuckets);


            for (int ise = mSensorStats.size() - 1; ise >= 0; ise--) {
                Sensor s = mSensorStats.valueAt(ise);
                s.detachFromTimeBase();
            }

            for (int ip= mProcessStats.size() - 1; ip >= 0; ip--) {
                Proc proc = mProcessStats.valueAt(ip);
                proc.detach();
            }
            mProcessStats.clear();

            for(int i = mPackageStats.size() - 1; i >= 0; i--) {
                Pkg p = mPackageStats.valueAt(i);
                p.detach();
            }
            mPackageStats.clear();
        }

        void writeJobCompletionsToParcelLocked(Parcel out) {
            int NJC = mJobCompletions.size();
            out.writeInt(NJC);
            for (int ijc=0; ijc<NJC; ijc++) {
                out.writeString(mJobCompletions.keyAt(ijc));
                SparseIntArray types = mJobCompletions.valueAt(ijc);
                int NT = types.size();
                out.writeInt(NT);
                for (int it=0; it<NT; it++) {
                    out.writeInt(types.keyAt(it));
                    out.writeInt(types.valueAt(it));
                }
            }
        }

        void writeToParcelLocked(Parcel out, long uptimeUs, long elapsedRealtimeUs) {
            mOnBatteryBackgroundTimeBase.writeToParcel(out, uptimeUs, elapsedRealtimeUs);
            mOnBatteryScreenOffBackgroundTimeBase.writeToParcel(out, uptimeUs, elapsedRealtimeUs);

            final ArrayMap<String, Wakelock> wakeStats = mWakelockStats.getMap();
            int NW = wakeStats.size();
            out.writeInt(NW);
            for (int iw=0; iw<NW; iw++) {
                out.writeString(wakeStats.keyAt(iw));
                Uid.Wakelock wakelock = wakeStats.valueAt(iw);
                wakelock.writeToParcelLocked(out, elapsedRealtimeUs);
            }

            final ArrayMap<String, DualTimer> syncStats = mSyncStats.getMap();
            int NS = syncStats.size();
            out.writeInt(NS);
            for (int is=0; is<NS; is++) {
                out.writeString(syncStats.keyAt(is));
                DualTimer timer = syncStats.valueAt(is);
                Timer.writeTimerToParcel(out, timer, elapsedRealtimeUs);
            }

            final ArrayMap<String, DualTimer> jobStats = mJobStats.getMap();
            int NJ = jobStats.size();
            out.writeInt(NJ);
            for (int ij=0; ij<NJ; ij++) {
                out.writeString(jobStats.keyAt(ij));
                DualTimer timer = jobStats.valueAt(ij);
                Timer.writeTimerToParcel(out, timer, elapsedRealtimeUs);
            }

            writeJobCompletionsToParcelLocked(out);

            mJobsDeferredEventCount.writeToParcel(out);
            mJobsDeferredCount.writeToParcel(out);
            mJobsFreshnessTimeMs.writeToParcel(out);
            for (int i = 0; i < JOB_FRESHNESS_BUCKETS.length; i++) {
                Counter.writeCounterToParcel(out, mJobsFreshnessBuckets[i]);
            }

            int NSE = mSensorStats.size();
            out.writeInt(NSE);
            for (int ise=0; ise<NSE; ise++) {
                out.writeInt(mSensorStats.keyAt(ise));
                Uid.Sensor sensor = mSensorStats.valueAt(ise);
                sensor.writeToParcelLocked(out, elapsedRealtimeUs);
            }

            int NP = mProcessStats.size();
            out.writeInt(NP);
            for (int ip=0; ip<NP; ip++) {
                out.writeString(mProcessStats.keyAt(ip));
                Uid.Proc proc = mProcessStats.valueAt(ip);
                proc.writeToParcelLocked(out);
            }

            out.writeInt(mPackageStats.size());
            for (Map.Entry<String, Uid.Pkg> pkgEntry : mPackageStats.entrySet()) {
                out.writeString(pkgEntry.getKey());
                Uid.Pkg pkg = pkgEntry.getValue();
                pkg.writeToParcelLocked(out);
            }

            if (mWifiRunningTimer != null) {
                out.writeInt(1);
                mWifiRunningTimer.writeToParcel(out, elapsedRealtimeUs);
            } else {
                out.writeInt(0);
            }
            if (mFullWifiLockTimer != null) {
                out.writeInt(1);
                mFullWifiLockTimer.writeToParcel(out, elapsedRealtimeUs);
            } else {
                out.writeInt(0);
            }
            if (mWifiScanTimer != null) {
                out.writeInt(1);
                mWifiScanTimer.writeToParcel(out, elapsedRealtimeUs);
            } else {
                out.writeInt(0);
            }
            for (int i = 0; i < NUM_WIFI_BATCHED_SCAN_BINS; i++) {
                if (mWifiBatchedScanTimer[i] != null) {
                    out.writeInt(1);
                    mWifiBatchedScanTimer[i].writeToParcel(out, elapsedRealtimeUs);
                } else {
                    out.writeInt(0);
                }
            }
            if (mWifiMulticastTimer != null) {
                out.writeInt(1);
                mWifiMulticastTimer.writeToParcel(out, elapsedRealtimeUs);
            } else {
                out.writeInt(0);
            }

            if (mAudioTurnedOnTimer != null) {
                out.writeInt(1);
                mAudioTurnedOnTimer.writeToParcel(out, elapsedRealtimeUs);
            } else {
                out.writeInt(0);
            }
            if (mVideoTurnedOnTimer != null) {
                out.writeInt(1);
                mVideoTurnedOnTimer.writeToParcel(out, elapsedRealtimeUs);
            } else {
                out.writeInt(0);
            }
            if (mFlashlightTurnedOnTimer != null) {
                out.writeInt(1);
                mFlashlightTurnedOnTimer.writeToParcel(out, elapsedRealtimeUs);
            } else {
                out.writeInt(0);
            }
            if (mCameraTurnedOnTimer != null) {
                out.writeInt(1);
                mCameraTurnedOnTimer.writeToParcel(out, elapsedRealtimeUs);
            } else {
                out.writeInt(0);
            }
            if (mForegroundActivityTimer != null) {
                out.writeInt(1);
                mForegroundActivityTimer.writeToParcel(out, elapsedRealtimeUs);
            } else {
                out.writeInt(0);
            }
            if (mForegroundServiceTimer != null) {
                out.writeInt(1);
                mForegroundServiceTimer.writeToParcel(out, elapsedRealtimeUs);
            } else {
                out.writeInt(0);
            }
            if (mAggregatedPartialWakelockTimer != null) {
                out.writeInt(1);
                mAggregatedPartialWakelockTimer.writeToParcel(out, elapsedRealtimeUs);
            } else {
                out.writeInt(0);
            }
            if (mBluetoothScanTimer != null) {
                out.writeInt(1);
                mBluetoothScanTimer.writeToParcel(out, elapsedRealtimeUs);
            } else {
                out.writeInt(0);
            }
            if (mBluetoothUnoptimizedScanTimer != null) {
                out.writeInt(1);
                mBluetoothUnoptimizedScanTimer.writeToParcel(out, elapsedRealtimeUs);
            } else {
                out.writeInt(0);
            }
            if (mBluetoothScanResultCounter != null) {
                out.writeInt(1);
                mBluetoothScanResultCounter.writeToParcel(out);
            } else {
                out.writeInt(0);
            }
            if (mBluetoothScanResultBgCounter != null) {
                out.writeInt(1);
                mBluetoothScanResultBgCounter.writeToParcel(out);
            } else {
                out.writeInt(0);
            }
            for (int i = 0; i < NUM_PROCESS_STATE; i++) {
                if (mProcessStateTimer[i] != null) {
                    out.writeInt(1);
                    mProcessStateTimer[i].writeToParcel(out, elapsedRealtimeUs);
                } else {
                    out.writeInt(0);
                }
            }
            if (mVibratorOnTimer != null) {
                out.writeInt(1);
                mVibratorOnTimer.writeToParcel(out, elapsedRealtimeUs);
            } else {
                out.writeInt(0);
            }
            if (mUserActivityCounters != null) {
                out.writeInt(1);
                for (int i=0; i<NUM_USER_ACTIVITY_TYPES; i++) {
                    mUserActivityCounters[i].writeToParcel(out);
                }
            } else {
                out.writeInt(0);
            }
            if (mNetworkByteActivityCounters != null) {
                out.writeInt(1);
                for (int i = 0; i < NUM_NETWORK_ACTIVITY_TYPES; i++) {
                    mNetworkByteActivityCounters[i].writeToParcel(out);
                    mNetworkPacketActivityCounters[i].writeToParcel(out);
                }
                mMobileRadioActiveTime.writeToParcel(out);
                mMobileRadioActiveCount.writeToParcel(out);
            } else {
                out.writeInt(0);
            }

            if (mWifiControllerActivity != null) {
                out.writeInt(1);
                mWifiControllerActivity.writeToParcel(out, 0);
            } else {
                out.writeInt(0);
            }

            if (mBluetoothControllerActivity != null) {
                out.writeInt(1);
                mBluetoothControllerActivity.writeToParcel(out, 0);
            } else {
                out.writeInt(0);
            }

            if (mModemControllerActivity != null) {
                out.writeInt(1);
                mModemControllerActivity.writeToParcel(out, 0);
            } else {
                out.writeInt(0);
            }

            mUserCpuTime.writeToParcel(out);
            mSystemCpuTime.writeToParcel(out);

            if (mCpuClusterSpeedTimesUs != null) {
                out.writeInt(1);
                out.writeInt(mCpuClusterSpeedTimesUs.length);
                for (LongSamplingCounter[] cpuSpeeds : mCpuClusterSpeedTimesUs) {
                    if (cpuSpeeds != null) {
                        out.writeInt(1);
                        out.writeInt(cpuSpeeds.length);
                        for (LongSamplingCounter c : cpuSpeeds) {
                            if (c != null) {
                                out.writeInt(1);
                                c.writeToParcel(out);
                            } else {
                                out.writeInt(0);
                            }
                        }
                    } else {
                        out.writeInt(0);
                    }
                }
            } else {
                out.writeInt(0);
            }

            LongSamplingCounterArray.writeToParcel(out, mCpuFreqTimeMs);
            LongSamplingCounterArray.writeToParcel(out, mScreenOffCpuFreqTimeMs);

            mCpuActiveTimeMs.writeToParcel(out);
            mCpuClusterTimesMs.writeToParcel(out);

            if (mProcStateTimeMs != null) {
                out.writeInt(mProcStateTimeMs.length);
                for (LongSamplingCounterArray counters : mProcStateTimeMs) {
                    LongSamplingCounterArray.writeToParcel(out, counters);
                }
            } else {
                out.writeInt(0);
            }
            if (mProcStateScreenOffTimeMs != null) {
                out.writeInt(mProcStateScreenOffTimeMs.length);
                for (LongSamplingCounterArray counters : mProcStateScreenOffTimeMs) {
                    LongSamplingCounterArray.writeToParcel(out, counters);
                }
            } else {
                out.writeInt(0);
            }

            if (mMobileRadioApWakeupCount != null) {
                out.writeInt(1);
                mMobileRadioApWakeupCount.writeToParcel(out);
            } else {
                out.writeInt(0);
            }

            if (mWifiRadioApWakeupCount != null) {
                out.writeInt(1);
                mWifiRadioApWakeupCount.writeToParcel(out);
            } else {
                out.writeInt(0);
            }
        }

        void readJobCompletionsFromParcelLocked(Parcel in) {
            int numJobCompletions = in.readInt();
            mJobCompletions.clear();
            for (int j = 0; j < numJobCompletions; j++) {
                String jobName = in.readString();
                int numTypes = in.readInt();
                if (numTypes > 0) {
                    SparseIntArray types = new SparseIntArray();
                    for (int k = 0; k < numTypes; k++) {
                        int type = in.readInt();
                        int count = in.readInt();
                        types.put(type, count);
                    }
                    mJobCompletions.put(jobName, types);
                }
            }
        }

        void readFromParcelLocked(TimeBase timeBase, TimeBase screenOffTimeBase, Parcel in) {
            mOnBatteryBackgroundTimeBase.readFromParcel(in);
            mOnBatteryScreenOffBackgroundTimeBase.readFromParcel(in);

            int numWakelocks = in.readInt();
            mWakelockStats.clear();
            for (int j = 0; j < numWakelocks; j++) {
                String wakelockName = in.readString();
                Uid.Wakelock wakelock = new Wakelock(mBsi, this);
                wakelock.readFromParcelLocked(
                        timeBase, screenOffTimeBase, mOnBatteryScreenOffBackgroundTimeBase, in);
                mWakelockStats.add(wakelockName, wakelock);
            }

            int numSyncs = in.readInt();
            mSyncStats.clear();
            for (int j = 0; j < numSyncs; j++) {
                String syncName = in.readString();
                if (in.readInt() != 0) {
                    mSyncStats.add(syncName, new DualTimer(mBsi.mClocks, Uid.this, SYNC, null,
                            mBsi.mOnBatteryTimeBase, mOnBatteryBackgroundTimeBase, in));
                }
            }

            int numJobs = in.readInt();
            mJobStats.clear();
            for (int j = 0; j < numJobs; j++) {
                String jobName = in.readString();
                if (in.readInt() != 0) {
                    mJobStats.add(jobName, new DualTimer(mBsi.mClocks, Uid.this, JOB, null,
                            mBsi.mOnBatteryTimeBase, mOnBatteryBackgroundTimeBase, in));
                }
            }

            readJobCompletionsFromParcelLocked(in);

            mJobsDeferredEventCount = new Counter(mBsi.mOnBatteryTimeBase, in);
            mJobsDeferredCount = new Counter(mBsi.mOnBatteryTimeBase, in);
            mJobsFreshnessTimeMs = new LongSamplingCounter(mBsi.mOnBatteryTimeBase, in);
            for (int i = 0; i < JOB_FRESHNESS_BUCKETS.length; i++) {
                mJobsFreshnessBuckets[i] = Counter.readCounterFromParcel(mBsi.mOnBatteryTimeBase,
                        in);
            }

            int numSensors = in.readInt();
            mSensorStats.clear();
            for (int k = 0; k < numSensors; k++) {
                int sensorNumber = in.readInt();
                Uid.Sensor sensor = new Sensor(mBsi, this, sensorNumber);
                sensor.readFromParcelLocked(mBsi.mOnBatteryTimeBase, mOnBatteryBackgroundTimeBase,
                        in);
                mSensorStats.put(sensorNumber, sensor);
            }

            int numProcs = in.readInt();
            mProcessStats.clear();
            for (int k = 0; k < numProcs; k++) {
                String processName = in.readString();
                Uid.Proc proc = new Proc(mBsi, processName);
                proc.readFromParcelLocked(in);
                mProcessStats.put(processName, proc);
            }

            int numPkgs = in.readInt();
            mPackageStats.clear();
            for (int l = 0; l < numPkgs; l++) {
                String packageName = in.readString();
                Uid.Pkg pkg = new Pkg(mBsi);
                pkg.readFromParcelLocked(in);
                mPackageStats.put(packageName, pkg);
            }

            mWifiRunning = false;
            if (in.readInt() != 0) {
                mWifiRunningTimer = new StopwatchTimer(mBsi.mClocks, Uid.this, WIFI_RUNNING,
                        mBsi.mWifiRunningTimers, mBsi.mOnBatteryTimeBase, in);
            } else {
                mWifiRunningTimer = null;
            }
            mFullWifiLockOut = false;
            if (in.readInt() != 0) {
                mFullWifiLockTimer = new StopwatchTimer(mBsi.mClocks, Uid.this, FULL_WIFI_LOCK,
                        mBsi.mFullWifiLockTimers, mBsi.mOnBatteryTimeBase, in);
            } else {
                mFullWifiLockTimer = null;
            }
            mWifiScanStarted = false;
            if (in.readInt() != 0) {
                mWifiScanTimer = new DualTimer(mBsi.mClocks, Uid.this, WIFI_SCAN,
                        mBsi.mWifiScanTimers, mBsi.mOnBatteryTimeBase, mOnBatteryBackgroundTimeBase,
                        in);
            } else {
                mWifiScanTimer = null;
            }
            mWifiBatchedScanBinStarted = NO_BATCHED_SCAN_STARTED;
            for (int i = 0; i < NUM_WIFI_BATCHED_SCAN_BINS; i++) {
                if (in.readInt() != 0) {
                    makeWifiBatchedScanBin(i, in);
                } else {
                    mWifiBatchedScanTimer[i] = null;
                }
            }
            mWifiMulticastWakelockCount = 0;
            if (in.readInt() != 0) {
                mWifiMulticastTimer = new StopwatchTimer(mBsi.mClocks, Uid.this, WIFI_MULTICAST_ENABLED,
                        mBsi.mWifiMulticastTimers, mBsi.mOnBatteryTimeBase, in);
            } else {
                mWifiMulticastTimer = null;
            }
            if (in.readInt() != 0) {
                mAudioTurnedOnTimer = new StopwatchTimer(mBsi.mClocks, Uid.this, AUDIO_TURNED_ON,
                        mBsi.mAudioTurnedOnTimers, mBsi.mOnBatteryTimeBase, in);
            } else {
                mAudioTurnedOnTimer = null;
            }
            if (in.readInt() != 0) {
                mVideoTurnedOnTimer = new StopwatchTimer(mBsi.mClocks, Uid.this, VIDEO_TURNED_ON,
                        mBsi.mVideoTurnedOnTimers, mBsi.mOnBatteryTimeBase, in);
            } else {
                mVideoTurnedOnTimer = null;
            }
            if (in.readInt() != 0) {
                mFlashlightTurnedOnTimer = new StopwatchTimer(mBsi.mClocks, Uid.this,
                        FLASHLIGHT_TURNED_ON, mBsi.mFlashlightTurnedOnTimers, mBsi.mOnBatteryTimeBase, in);
            } else {
                mFlashlightTurnedOnTimer = null;
            }
            if (in.readInt() != 0) {
                mCameraTurnedOnTimer = new StopwatchTimer(mBsi.mClocks, Uid.this, CAMERA_TURNED_ON,
                        mBsi.mCameraTurnedOnTimers, mBsi.mOnBatteryTimeBase, in);
            } else {
                mCameraTurnedOnTimer = null;
            }
            if (in.readInt() != 0) {
                mForegroundActivityTimer = new StopwatchTimer(mBsi.mClocks, Uid.this,
                        FOREGROUND_ACTIVITY, null, mBsi.mOnBatteryTimeBase, in);
            } else {
                mForegroundActivityTimer = null;
            }
            if (in.readInt() != 0) {
                mForegroundServiceTimer = new StopwatchTimer(mBsi.mClocks, Uid.this,
                        FOREGROUND_SERVICE, null, mBsi.mOnBatteryTimeBase, in);
            } else {
                mForegroundServiceTimer = null;
            }
            if (in.readInt() != 0) {
                mAggregatedPartialWakelockTimer = new DualTimer(mBsi.mClocks, this,
                        AGGREGATED_WAKE_TYPE_PARTIAL, null,
                        mBsi.mOnBatteryScreenOffTimeBase, mOnBatteryScreenOffBackgroundTimeBase,
                        in);
            } else {
                mAggregatedPartialWakelockTimer = null;
            }
            if (in.readInt() != 0) {
                mBluetoothScanTimer = new DualTimer(mBsi.mClocks, Uid.this, BLUETOOTH_SCAN_ON,
                        mBsi.mBluetoothScanOnTimers, mBsi.mOnBatteryTimeBase,
                        mOnBatteryBackgroundTimeBase, in);
            } else {
                mBluetoothScanTimer = null;
            }
            if (in.readInt() != 0) {
                mBluetoothUnoptimizedScanTimer = new DualTimer(mBsi.mClocks, Uid.this,
                        BLUETOOTH_UNOPTIMIZED_SCAN_ON, null,
                        mBsi.mOnBatteryTimeBase, mOnBatteryBackgroundTimeBase, in);
            } else {
                mBluetoothUnoptimizedScanTimer = null;
            }
            if (in.readInt() != 0) {
                mBluetoothScanResultCounter = new Counter(mBsi.mOnBatteryTimeBase, in);
            } else {
                mBluetoothScanResultCounter = null;
            }
            if (in.readInt() != 0) {
                mBluetoothScanResultBgCounter = new Counter(mOnBatteryBackgroundTimeBase, in);
            } else {
                mBluetoothScanResultBgCounter = null;
            }
            mProcessState = ActivityManager.PROCESS_STATE_NONEXISTENT;
            for (int i = 0; i < NUM_PROCESS_STATE; i++) {
                if (in.readInt() != 0) {
                    makeProcessState(i, in);
                } else {
                    mProcessStateTimer[i] = null;
                }
            }
            if (in.readInt() != 0) {
                mVibratorOnTimer = new BatchTimer(mBsi.mClocks, Uid.this, VIBRATOR_ON,
                        mBsi.mOnBatteryTimeBase, in);
            } else {
                mVibratorOnTimer = null;
            }
            if (in.readInt() != 0) {
                mUserActivityCounters = new Counter[NUM_USER_ACTIVITY_TYPES];
                for (int i=0; i<NUM_USER_ACTIVITY_TYPES; i++) {
                    mUserActivityCounters[i] = new Counter(mBsi.mOnBatteryTimeBase, in);
                }
            } else {
                mUserActivityCounters = null;
            }
            if (in.readInt() != 0) {
                mNetworkByteActivityCounters = new LongSamplingCounter[NUM_NETWORK_ACTIVITY_TYPES];
                mNetworkPacketActivityCounters
                        = new LongSamplingCounter[NUM_NETWORK_ACTIVITY_TYPES];
                for (int i = 0; i < NUM_NETWORK_ACTIVITY_TYPES; i++) {
                    mNetworkByteActivityCounters[i]
                            = new LongSamplingCounter(mBsi.mOnBatteryTimeBase, in);
                    mNetworkPacketActivityCounters[i]
                            = new LongSamplingCounter(mBsi.mOnBatteryTimeBase, in);
                }
                mMobileRadioActiveTime = new LongSamplingCounter(mBsi.mOnBatteryTimeBase, in);
                mMobileRadioActiveCount = new LongSamplingCounter(mBsi.mOnBatteryTimeBase, in);
            } else {
                mNetworkByteActivityCounters = null;
                mNetworkPacketActivityCounters = null;
            }

            if (in.readInt() != 0) {
                mWifiControllerActivity = new ControllerActivityCounterImpl(mBsi.mOnBatteryTimeBase,
                        NUM_WIFI_TX_LEVELS, in);
            } else {
                mWifiControllerActivity = null;
            }

            if (in.readInt() != 0) {
                mBluetoothControllerActivity = new ControllerActivityCounterImpl(mBsi.mOnBatteryTimeBase,
                        NUM_BT_TX_LEVELS, in);
            } else {
                mBluetoothControllerActivity = null;
            }

            if (in.readInt() != 0) {
                mModemControllerActivity = new ControllerActivityCounterImpl(mBsi.mOnBatteryTimeBase,
                        ModemActivityInfo.TX_POWER_LEVELS, in);
            } else {
                mModemControllerActivity = null;
            }

            mUserCpuTime = new LongSamplingCounter(mBsi.mOnBatteryTimeBase, in);
            mSystemCpuTime = new LongSamplingCounter(mBsi.mOnBatteryTimeBase, in);

            if (in.readInt() != 0) {
                int numCpuClusters = in.readInt();
                if (mBsi.mPowerProfile != null && mBsi.mPowerProfile.getNumCpuClusters() != numCpuClusters) {
                    throw new ParcelFormatException("Incompatible number of cpu clusters");
                }

                mCpuClusterSpeedTimesUs = new LongSamplingCounter[numCpuClusters][];
                for (int cluster = 0; cluster < numCpuClusters; cluster++) {
                    if (in.readInt() != 0) {
                        int numSpeeds = in.readInt();
                        if (mBsi.mPowerProfile != null &&
                                mBsi.mPowerProfile.getNumSpeedStepsInCpuCluster(cluster) != numSpeeds) {
                            throw new ParcelFormatException("Incompatible number of cpu speeds");
                        }

                        final LongSamplingCounter[] cpuSpeeds = new LongSamplingCounter[numSpeeds];
                        mCpuClusterSpeedTimesUs[cluster] = cpuSpeeds;
                        for (int speed = 0; speed < numSpeeds; speed++) {
                            if (in.readInt() != 0) {
                                cpuSpeeds[speed] = new LongSamplingCounter(
                                        mBsi.mOnBatteryTimeBase, in);
                            }
                        }
                    } else {
                        mCpuClusterSpeedTimesUs[cluster] = null;
                    }
                }
            } else {
                mCpuClusterSpeedTimesUs = null;
            }

            mCpuFreqTimeMs = LongSamplingCounterArray.readFromParcel(in, mBsi.mOnBatteryTimeBase);
            mScreenOffCpuFreqTimeMs = LongSamplingCounterArray.readFromParcel(
                    in, mBsi.mOnBatteryScreenOffTimeBase);

            mCpuActiveTimeMs = new LongSamplingCounter(mBsi.mOnBatteryTimeBase, in);
            mCpuClusterTimesMs = new LongSamplingCounterArray(mBsi.mOnBatteryTimeBase, in);

            int length = in.readInt();
            if (length == NUM_PROCESS_STATE) {
                mProcStateTimeMs = new LongSamplingCounterArray[length];
                for (int procState = 0; procState < length; ++procState) {
                    mProcStateTimeMs[procState] = LongSamplingCounterArray.readFromParcel(
                            in, mBsi.mOnBatteryTimeBase);
                }
            } else {
                mProcStateTimeMs = null;
            }
            length = in.readInt();
            if (length == NUM_PROCESS_STATE) {
                mProcStateScreenOffTimeMs = new LongSamplingCounterArray[length];
                for (int procState = 0; procState < length; ++procState) {
                    mProcStateScreenOffTimeMs[procState] = LongSamplingCounterArray.readFromParcel(
                            in, mBsi.mOnBatteryScreenOffTimeBase);
                }
            } else {
                mProcStateScreenOffTimeMs = null;
            }

            if (in.readInt() != 0) {
                mMobileRadioApWakeupCount = new LongSamplingCounter(mBsi.mOnBatteryTimeBase, in);
            } else {
                mMobileRadioApWakeupCount = null;
            }

            if (in.readInt() != 0) {
                mWifiRadioApWakeupCount = new LongSamplingCounter(mBsi.mOnBatteryTimeBase, in);
            } else {
                mWifiRadioApWakeupCount = null;
            }
        }

        public void noteJobsDeferredLocked(int numDeferred, long sinceLast) {
            mJobsDeferredEventCount.addAtomic(1);
            mJobsDeferredCount.addAtomic(numDeferred);
            if (sinceLast != 0) {
                // Add the total time, which can be divided by the event count to get an average
                mJobsFreshnessTimeMs.addCountLocked(sinceLast);
                // Also keep track of how many times there were in these different buckets.
                for (int i = 0; i < JOB_FRESHNESS_BUCKETS.length; i++) {
                    if (sinceLast < JOB_FRESHNESS_BUCKETS[i]) {
                        if (mJobsFreshnessBuckets[i] == null) {
                            mJobsFreshnessBuckets[i] = new Counter(
                                    mBsi.mOnBatteryTimeBase);
                        }
                        mJobsFreshnessBuckets[i].addAtomic(1);
                        break;
                    }
                }
            }
        }

        /**
         * The statistics associated with a particular wake lock.
         */
        public static class Wakelock extends BatteryStats.Uid.Wakelock {
            /**
             * BatteryStatsImpl that we are associated with.
             */
            protected BatteryStatsImpl mBsi;

            /**
             * BatteryStatsImpl that we are associated with.
             */
            protected Uid mUid;

            /**
             * How long (in ms) this uid has been keeping the device partially awake.
             * Tracks both the total time and the time while the app was in the background.
             */
            DualTimer mTimerPartial;

            /**
             * How long (in ms) this uid has been keeping the device fully awake.
             */
            StopwatchTimer mTimerFull;

            /**
             * How long (in ms) this uid has had a window keeping the device awake.
             */
            StopwatchTimer mTimerWindow;

            /**
             * How long (in ms) this uid has had a draw wake lock.
             */
            StopwatchTimer mTimerDraw;

            public Wakelock(BatteryStatsImpl bsi, Uid uid) {
                mBsi = bsi;
                mUid = uid;
            }

            /**
             * Reads a possibly null Timer from a Parcel.  The timer is associated with the
             * proper timer pool from the given BatteryStatsImpl object.
             *
             * @param in the Parcel to be read from.
             * return a new Timer, or null.
             */
            private StopwatchTimer readStopwatchTimerFromParcel(int type,
                    ArrayList<StopwatchTimer> pool, TimeBase timeBase, Parcel in) {
                if (in.readInt() == 0) {
                    return null;
                }

                return new StopwatchTimer(mBsi.mClocks, mUid, type, pool, timeBase, in);
            }

            /**
             * Reads a possibly null Timer from a Parcel.  The timer is associated with the
             * proper timer pool from the given BatteryStatsImpl object.
             *
             * @param in the Parcel to be read from.
             * return a new Timer, or null.
             */
            private DualTimer readDualTimerFromParcel(int type, ArrayList<StopwatchTimer> pool,
                    TimeBase timeBase, TimeBase bgTimeBase, Parcel in) {
                if (in.readInt() == 0) {
                    return null;
                }

                return new DualTimer(mBsi.mClocks, mUid, type, pool, timeBase, bgTimeBase, in);
            }

            boolean reset() {
                boolean wlactive = false;

                wlactive |= !resetIfNotNull(mTimerFull,false);
                wlactive |= !resetIfNotNull(mTimerPartial,false);
                wlactive |= !resetIfNotNull(mTimerWindow,false);
                wlactive |= !resetIfNotNull(mTimerDraw,false);

                if (!wlactive) {
                    detachIfNotNull(mTimerFull);
                    mTimerFull = null;

                    detachIfNotNull(mTimerPartial);
                    mTimerPartial = null;

                    detachIfNotNull(mTimerWindow);
                    mTimerWindow = null;

                    detachIfNotNull(mTimerDraw);
                    mTimerDraw = null;
                }
                return !wlactive;
            }

            void readFromParcelLocked(TimeBase timeBase, TimeBase screenOffTimeBase,
                    TimeBase screenOffBgTimeBase, Parcel in) {
                mTimerPartial = readDualTimerFromParcel(WAKE_TYPE_PARTIAL,
                        mBsi.mPartialTimers, screenOffTimeBase, screenOffBgTimeBase, in);
                mTimerFull = readStopwatchTimerFromParcel(WAKE_TYPE_FULL,
                        mBsi.mFullTimers, timeBase, in);
                mTimerWindow = readStopwatchTimerFromParcel(WAKE_TYPE_WINDOW,
                        mBsi.mWindowTimers, timeBase, in);
                mTimerDraw = readStopwatchTimerFromParcel(WAKE_TYPE_DRAW,
                        mBsi.mDrawTimers, timeBase, in);
            }

            void writeToParcelLocked(Parcel out, long elapsedRealtimeUs) {
                Timer.writeTimerToParcel(out, mTimerPartial, elapsedRealtimeUs);
                Timer.writeTimerToParcel(out, mTimerFull, elapsedRealtimeUs);
                Timer.writeTimerToParcel(out, mTimerWindow, elapsedRealtimeUs);
                Timer.writeTimerToParcel(out, mTimerDraw, elapsedRealtimeUs);
            }

            @Override
            @UnsupportedAppUsage
            public Timer getWakeTime(int type) {
                switch (type) {
                case WAKE_TYPE_FULL: return mTimerFull;
                case WAKE_TYPE_PARTIAL: return mTimerPartial;
                case WAKE_TYPE_WINDOW: return mTimerWindow;
                case WAKE_TYPE_DRAW: return mTimerDraw;
                default: throw new IllegalArgumentException("type = " + type);
                }
            }

            public void detachFromTimeBase() {
                detachIfNotNull(mTimerPartial);
                detachIfNotNull(mTimerFull);
                detachIfNotNull(mTimerWindow);
                detachIfNotNull(mTimerDraw);
            }
        }

        public static class Sensor extends BatteryStats.Uid.Sensor {
            /**
             * BatteryStatsImpl that we are associated with.
             */
            protected BatteryStatsImpl mBsi;

            /**
             * Uid that we are associated with.
             */
            protected Uid mUid;

            final int mHandle;
            DualTimer mTimer;

            public Sensor(BatteryStatsImpl bsi, Uid uid, int handle) {
                mBsi = bsi;
                mUid = uid;
                mHandle = handle;
            }

            private DualTimer readTimersFromParcel(
                    TimeBase timeBase, TimeBase bgTimeBase, Parcel in) {
                if (in.readInt() == 0) {
                    return null;
                }

                ArrayList<StopwatchTimer> pool = mBsi.mSensorTimers.get(mHandle);
                if (pool == null) {
                    pool = new ArrayList<StopwatchTimer>();
                    mBsi.mSensorTimers.put(mHandle, pool);
                }
                return new DualTimer(mBsi.mClocks, mUid, 0, pool, timeBase, bgTimeBase, in);
            }

            boolean reset() {
                if (mTimer.reset(true)) {
                    mTimer = null;
                    return true;
                }
                return false;
            }

            void readFromParcelLocked(TimeBase timeBase, TimeBase bgTimeBase, Parcel in) {
                mTimer = readTimersFromParcel(timeBase, bgTimeBase, in);
            }

            void writeToParcelLocked(Parcel out, long elapsedRealtimeUs) {
                Timer.writeTimerToParcel(out, mTimer, elapsedRealtimeUs);
            }

            @Override
            @UnsupportedAppUsage
            public Timer getSensorTime() {
                return mTimer;
            }

            @Override
            public Timer getSensorBackgroundTime() {
                if (mTimer == null) {
                    return null;
                }
                return mTimer.getSubTimer();
            }

            @Override
            @UnsupportedAppUsage
            public int getHandle() {
                return mHandle;
            }

            public void  detachFromTimeBase() {
                detachIfNotNull(mTimer);
            }
        }

        /**
         * The statistics associated with a particular process.
         */
        public static class Proc extends BatteryStats.Uid.Proc implements TimeBaseObs {
            /**
             * BatteryStatsImpl that we are associated with.
             */
            protected BatteryStatsImpl mBsi;

            /**
             * The name of this process.
             */
            final String mName;

            /**
             * Remains true until removed from the stats.
             */
            boolean mActive = true;

            /**
             * Total time (in ms) spent executing in user code.
             */
            long mUserTime;

            /**
             * Total time (in ms) spent executing in kernel code.
             */
            long mSystemTime;

            /**
             * Amount of time (in ms) the process was running in the foreground.
             */
            long mForegroundTime;

            /**
             * Number of times the process has been started.
             */
            int mStarts;

            /**
             * Number of times the process has crashed.
             */
            int mNumCrashes;

            /**
             * Number of times the process has had an ANR.
             */
            int mNumAnrs;

            ArrayList<ExcessivePower> mExcessivePower;

            public Proc(BatteryStatsImpl bsi, String name) {
                mBsi = bsi;
                mName = name;
                mBsi.mOnBatteryTimeBase.add(this);
            }

            public void onTimeStarted(long elapsedRealtime, long baseUptime, long baseRealtime) {
            }

            public void onTimeStopped(long elapsedRealtime, long baseUptime, long baseRealtime) {
            }

            @Override
            public boolean reset(boolean detachIfReset) {
                if (detachIfReset) {
                    this.detach();
                }
                return true;
            }

            @Override
            public void detach() {
                mActive = false;
                mBsi.mOnBatteryTimeBase.remove(this);
            }

            public int countExcessivePowers() {
                return mExcessivePower != null ? mExcessivePower.size() : 0;
            }

            public ExcessivePower getExcessivePower(int i) {
                if (mExcessivePower != null) {
                    return mExcessivePower.get(i);
                }
                return null;
            }

            public void addExcessiveCpu(long overTime, long usedTime) {
                if (mExcessivePower == null) {
                    mExcessivePower = new ArrayList<ExcessivePower>();
                }
                ExcessivePower ew = new ExcessivePower();
                ew.type = ExcessivePower.TYPE_CPU;
                ew.overTime = overTime;
                ew.usedTime = usedTime;
                mExcessivePower.add(ew);
            }

            void writeExcessivePowerToParcelLocked(Parcel out) {
                if (mExcessivePower == null) {
                    out.writeInt(0);
                    return;
                }

                final int N = mExcessivePower.size();
                out.writeInt(N);
                for (int i=0; i<N; i++) {
                    ExcessivePower ew = mExcessivePower.get(i);
                    out.writeInt(ew.type);
                    out.writeLong(ew.overTime);
                    out.writeLong(ew.usedTime);
                }
            }

            void readExcessivePowerFromParcelLocked(Parcel in) {
                final int N = in.readInt();
                if (N == 0) {
                    mExcessivePower = null;
                    return;
                }

                if (N > 10000) {
                    throw new ParcelFormatException(
                            "File corrupt: too many excessive power entries " + N);
                }

                mExcessivePower = new ArrayList<>();
                for (int i=0; i<N; i++) {
                    ExcessivePower ew = new ExcessivePower();
                    ew.type = in.readInt();
                    ew.overTime = in.readLong();
                    ew.usedTime = in.readLong();
                    mExcessivePower.add(ew);
                }
            }

            void writeToParcelLocked(Parcel out) {
                out.writeLong(mUserTime);
                out.writeLong(mSystemTime);
                out.writeLong(mForegroundTime);
                out.writeInt(mStarts);
                out.writeInt(mNumCrashes);
                out.writeInt(mNumAnrs);
                writeExcessivePowerToParcelLocked(out);
            }

            void readFromParcelLocked(Parcel in) {
                mUserTime = in.readLong();
                mSystemTime = in.readLong();
                mForegroundTime = in.readLong();
                mStarts = in.readInt();
                mNumCrashes = in.readInt();
                mNumAnrs = in.readInt();
                readExcessivePowerFromParcelLocked(in);
            }

            @UnsupportedAppUsage
            public void addCpuTimeLocked(int utime, int stime) {
                addCpuTimeLocked(utime, stime, mBsi.mOnBatteryTimeBase.isRunning());
            }

            public void addCpuTimeLocked(int utime, int stime, boolean isRunning) {
                if (isRunning) {
                    mUserTime += utime;
                    mSystemTime += stime;
                }
            }

            @UnsupportedAppUsage
            public void addForegroundTimeLocked(long ttime) {
                mForegroundTime += ttime;
            }

            @UnsupportedAppUsage
            public void incStartsLocked() {
                mStarts++;
            }

            public void incNumCrashesLocked() {
                mNumCrashes++;
            }

            public void incNumAnrsLocked() {
                mNumAnrs++;
            }

            @Override
            public boolean isActive() {
                return mActive;
            }

            @Override
            @UnsupportedAppUsage
            public long getUserTime(int which) {
                return mUserTime;
            }

            @Override
            @UnsupportedAppUsage
            public long getSystemTime(int which) {
                return mSystemTime;
            }

            @Override
            @UnsupportedAppUsage
            public long getForegroundTime(int which) {
                return mForegroundTime;
            }

            @Override
            @UnsupportedAppUsage
            public int getStarts(int which) {
                return mStarts;
            }

            @Override
            public int getNumCrashes(int which) {
                return mNumCrashes;
            }

            @Override
            public int getNumAnrs(int which) {
                return mNumAnrs;
            }
        }

        /**
         * The statistics associated with a particular package.
         */
        public static class Pkg extends BatteryStats.Uid.Pkg implements TimeBaseObs {
            /**
             * BatteryStatsImpl that we are associated with.
             */
            protected BatteryStatsImpl mBsi;

            /**
             * Number of times wakeup alarms have occurred for this app.
             * On screen-off timebase starting in report v25.
             */
            ArrayMap<String, Counter> mWakeupAlarms = new ArrayMap<>();

            /**
             * The statics we have collected for this package's services.
             */
            final ArrayMap<String, Serv> mServiceStats = new ArrayMap<>();

            public Pkg(BatteryStatsImpl bsi) {
                mBsi = bsi;
                mBsi.mOnBatteryScreenOffTimeBase.add(this);
            }

            public void onTimeStarted(long elapsedRealtime, long baseUptime, long baseRealtime) {
            }

            public void onTimeStopped(long elapsedRealtime, long baseUptime, long baseRealtime) {
            }

            @Override
            public boolean reset(boolean detachIfReset) {
                if (detachIfReset) {
                    this.detach();
                }
                return true;
            }

            @Override
            public void detach() {
                mBsi.mOnBatteryScreenOffTimeBase.remove(this);
                for (int j = mWakeupAlarms.size() - 1; j >= 0; j--) {
                    detachIfNotNull(mWakeupAlarms.valueAt(j));
                }
                for (int j = mServiceStats.size() - 1; j >= 0; j--) {
                    detachIfNotNull(mServiceStats.valueAt(j));
                }
            }

            void readFromParcelLocked(Parcel in) {
                int numWA = in.readInt();
                mWakeupAlarms.clear();
                for (int i=0; i<numWA; i++) {
                    String tag = in.readString();
                    mWakeupAlarms.put(tag, new Counter(mBsi.mOnBatteryScreenOffTimeBase, in));
                }

                int numServs = in.readInt();
                mServiceStats.clear();
                for (int m = 0; m < numServs; m++) {
                    String serviceName = in.readString();
                    Uid.Pkg.Serv serv = new Serv(mBsi);
                    mServiceStats.put(serviceName, serv);

                    serv.readFromParcelLocked(in);
                }
            }

            void writeToParcelLocked(Parcel out) {
                int numWA = mWakeupAlarms.size();
                out.writeInt(numWA);
                for (int i=0; i<numWA; i++) {
                    out.writeString(mWakeupAlarms.keyAt(i));
                    mWakeupAlarms.valueAt(i).writeToParcel(out);
                }

                final int NS = mServiceStats.size();
                out.writeInt(NS);
                for (int i=0; i<NS; i++) {
                    out.writeString(mServiceStats.keyAt(i));
                    Uid.Pkg.Serv serv = mServiceStats.valueAt(i);
                    serv.writeToParcelLocked(out);
                }
            }

            @Override
            public ArrayMap<String, ? extends BatteryStats.Counter> getWakeupAlarmStats() {
                return mWakeupAlarms;
            }

            public void noteWakeupAlarmLocked(String tag) {
                Counter c = mWakeupAlarms.get(tag);
                if (c == null) {
                    c = new Counter(mBsi.mOnBatteryScreenOffTimeBase);
                    mWakeupAlarms.put(tag, c);
                }
                c.stepAtomic();
            }

            @Override
            public ArrayMap<String, ? extends BatteryStats.Uid.Pkg.Serv> getServiceStats() {
                return mServiceStats;
            }

            /**
             * The statistics associated with a particular service.
             */
            public static class Serv extends BatteryStats.Uid.Pkg.Serv implements TimeBaseObs {
                /**
                 * BatteryStatsImpl that we are associated with.
                 */
                protected BatteryStatsImpl mBsi;

                /**
                 * The android package in which this service resides.
                 */
                protected Pkg mPkg;

                /**
                 * Total time (ms in battery uptime) the service has been left started.
                 */
                protected long mStartTime;

                /**
                 * If service has been started and not yet stopped, this is
                 * when it was started.
                 */
                protected long mRunningSince;

                /**
                 * True if we are currently running.
                 */
                protected boolean mRunning;

                /**
                 * Total number of times startService() has been called.
                 */
                protected int mStarts;

                /**
                 * Total time (ms in battery uptime) the service has been left launched.
                 */
                protected long mLaunchedTime;

                /**
                 * If service has been launched and not yet exited, this is
                 * when it was launched (ms in battery uptime).
                 */
                protected long mLaunchedSince;

                /**
                 * True if we are currently launched.
                 */
                protected boolean mLaunched;

                /**
                 * Total number times the service has been launched.
                 */
                protected int mLaunches;

                /**
                 * Construct a Serv. Also adds it to the on-battery time base as a listener.
                 */
                public Serv(BatteryStatsImpl bsi) {
                    mBsi = bsi;
                    mBsi.mOnBatteryTimeBase.add(this);
                }

                public void onTimeStarted(long elapsedRealtime, long baseUptime,
                        long baseRealtime) {
                }

                public void onTimeStopped(long elapsedRealtime, long baseUptime,
                        long baseRealtime) {
                }

                @Override
                public boolean reset(boolean detachIfReset) {
                    if (detachIfReset) {
                        this.detach();
                    }
                    return true;
                }

                /**
                 * Remove this Serv as a listener from the time base.
                 */
                @Override
                public void detach() {
                    mBsi.mOnBatteryTimeBase.remove(this);
                }

                public void readFromParcelLocked(Parcel in) {
                    mStartTime = in.readLong();
                    mRunningSince = in.readLong();
                    mRunning = in.readInt() != 0;
                    mStarts = in.readInt();
                    mLaunchedTime = in.readLong();
                    mLaunchedSince = in.readLong();
                    mLaunched = in.readInt() != 0;
                    mLaunches = in.readInt();
                }

                public void writeToParcelLocked(Parcel out) {
                    out.writeLong(mStartTime);
                    out.writeLong(mRunningSince);
                    out.writeInt(mRunning ? 1 : 0);
                    out.writeInt(mStarts);
                    out.writeLong(mLaunchedTime);
                    out.writeLong(mLaunchedSince);
                    out.writeInt(mLaunched ? 1 : 0);
                    out.writeInt(mLaunches);
                }

                public long getLaunchTimeToNowLocked(long batteryUptime) {
                    if (!mLaunched) return mLaunchedTime;
                    return mLaunchedTime + batteryUptime - mLaunchedSince;
                }

                public long getStartTimeToNowLocked(long batteryUptime) {
                    if (!mRunning) return mStartTime;
                    return mStartTime + batteryUptime - mRunningSince;
                }

                @UnsupportedAppUsage
                public void startLaunchedLocked() {
                    if (!mLaunched) {
                        mLaunches++;
                        mLaunchedSince = mBsi.getBatteryUptimeLocked();
                        mLaunched = true;
                    }
                }

                @UnsupportedAppUsage
                public void stopLaunchedLocked() {
                    if (mLaunched) {
                        long time = mBsi.getBatteryUptimeLocked() - mLaunchedSince;
                        if (time > 0) {
                            mLaunchedTime += time;
                        } else {
                            mLaunches--;
                        }
                        mLaunched = false;
                    }
                }

                @UnsupportedAppUsage
                public void startRunningLocked() {
                    if (!mRunning) {
                        mStarts++;
                        mRunningSince = mBsi.getBatteryUptimeLocked();
                        mRunning = true;
                    }
                }

                @UnsupportedAppUsage
                public void stopRunningLocked() {
                    if (mRunning) {
                        long time = mBsi.getBatteryUptimeLocked() - mRunningSince;
                        if (time > 0) {
                            mStartTime += time;
                        } else {
                            mStarts--;
                        }
                        mRunning = false;
                    }
                }

                @UnsupportedAppUsage
                public BatteryStatsImpl getBatteryStats() {
                    return mBsi;
                }

                @Override
                public int getLaunches(int which) {
                    return mLaunches;
                }

                @Override
                public long getStartTime(long now, int which) {
                    return getStartTimeToNowLocked(now);
                }

                @Override
                public int getStarts(int which) {
                    return mStarts;
                }
            }

            final Serv newServiceStatsLocked() {
                return new Serv(mBsi);
            }
        }

        /**
         * Retrieve the statistics object for a particular process, creating
         * if needed.
         */
        public Proc getProcessStatsLocked(String name) {
            Proc ps = mProcessStats.get(name);
            if (ps == null) {
                ps = new Proc(mBsi, name);
                mProcessStats.put(name, ps);
            }

            return ps;
        }

        @GuardedBy("mBsi")
        public void updateUidProcessStateLocked(int procState) {
            int uidRunningState;
            // Make special note of Foreground Services
            final boolean userAwareService =
                    (ActivityManager.isForegroundService(procState));
            uidRunningState = BatteryStats.mapToInternalProcessState(procState);

            if (mProcessState == uidRunningState && userAwareService == mInForegroundService) {
                return;
            }

            final long elapsedRealtimeMs = mBsi.mClocks.elapsedRealtime();
            if (mProcessState != uidRunningState) {
                final long uptimeMs = mBsi.mClocks.uptimeMillis();

                if (mProcessState != ActivityManager.PROCESS_STATE_NONEXISTENT) {
                    mProcessStateTimer[mProcessState].stopRunningLocked(elapsedRealtimeMs);

                    if (mBsi.trackPerProcStateCpuTimes()) {
                        if (mBsi.mPendingUids.size() == 0) {
                            mBsi.mExternalSync.scheduleReadProcStateCpuTimes(
                                    mBsi.mOnBatteryTimeBase.isRunning(),
                                    mBsi.mOnBatteryScreenOffTimeBase.isRunning(),
                                    mBsi.mConstants.PROC_STATE_CPU_TIMES_READ_DELAY_MS);
                            mBsi.mNumSingleUidCpuTimeReads++;
                        } else {
                            mBsi.mNumBatchedSingleUidCpuTimeReads++;
                        }
                        if (mBsi.mPendingUids.indexOfKey(mUid) < 0
                                || ArrayUtils.contains(CRITICAL_PROC_STATES, mProcessState)) {
                            mBsi.mPendingUids.put(mUid, mProcessState);
                        }
                    } else {
                        mBsi.mPendingUids.clear();
                    }
                }
                mProcessState = uidRunningState;
                if (uidRunningState != ActivityManager.PROCESS_STATE_NONEXISTENT) {
                    if (mProcessStateTimer[uidRunningState] == null) {
                        makeProcessState(uidRunningState, null);
                    }
                    mProcessStateTimer[uidRunningState].startRunningLocked(elapsedRealtimeMs);
                }

                updateOnBatteryBgTimeBase(uptimeMs * 1000, elapsedRealtimeMs * 1000);
                updateOnBatteryScreenOffBgTimeBase(uptimeMs * 1000, elapsedRealtimeMs * 1000);
            }

            if (userAwareService != mInForegroundService) {
                if (userAwareService) {
                    noteForegroundServiceResumedLocked(elapsedRealtimeMs);
                } else {
                    noteForegroundServicePausedLocked(elapsedRealtimeMs);
                }
                mInForegroundService = userAwareService;
            }
        }

        /** Whether to consider Uid to be in the background for background timebase purposes. */
        public boolean isInBackground() {
            // Note that PROCESS_STATE_CACHED and ActivityManager.PROCESS_STATE_NONEXISTENT is
            // also considered to be 'background' for our purposes, because it's not foreground.
            return mProcessState >= PROCESS_STATE_BACKGROUND;
        }

        public boolean updateOnBatteryBgTimeBase(long uptimeUs, long realtimeUs) {
            boolean on = mBsi.mOnBatteryTimeBase.isRunning() && isInBackground();
            return mOnBatteryBackgroundTimeBase.setRunning(on, uptimeUs, realtimeUs);
        }

        public boolean updateOnBatteryScreenOffBgTimeBase(long uptimeUs, long realtimeUs) {
            boolean on = mBsi.mOnBatteryScreenOffTimeBase.isRunning() && isInBackground();
            return mOnBatteryScreenOffBackgroundTimeBase.setRunning(on, uptimeUs, realtimeUs);
        }

        public SparseArray<? extends Pid> getPidStats() {
            return mPids;
        }

        public Pid getPidStatsLocked(int pid) {
            Pid p = mPids.get(pid);
            if (p == null) {
                p = new Pid();
                mPids.put(pid, p);
            }
            return p;
        }

        /**
         * Retrieve the statistics object for a particular service, creating
         * if needed.
         */
        public Pkg getPackageStatsLocked(String name) {
            Pkg ps = mPackageStats.get(name);
            if (ps == null) {
                ps = new Pkg(mBsi);
                mPackageStats.put(name, ps);
            }

            return ps;
        }

        /**
         * Retrieve the statistics object for a particular service, creating
         * if needed.
         */
        public Pkg.Serv getServiceStatsLocked(String pkg, String serv) {
            Pkg ps = getPackageStatsLocked(pkg);
            Pkg.Serv ss = ps.mServiceStats.get(serv);
            if (ss == null) {
                ss = ps.newServiceStatsLocked();
                ps.mServiceStats.put(serv, ss);
            }

            return ss;
        }

        public void readSyncSummaryFromParcelLocked(String name, Parcel in) {
            DualTimer timer = mSyncStats.instantiateObject();
            timer.readSummaryFromParcelLocked(in);
            mSyncStats.add(name, timer);
        }

        public void readJobSummaryFromParcelLocked(String name, Parcel in) {
            DualTimer timer = mJobStats.instantiateObject();
            timer.readSummaryFromParcelLocked(in);
            mJobStats.add(name, timer);
        }

        public void readWakeSummaryFromParcelLocked(String wlName, Parcel in) {
            Wakelock wl = new Wakelock(mBsi, this);
            mWakelockStats.add(wlName, wl);
            if (in.readInt() != 0) {
                getWakelockTimerLocked(wl, WAKE_TYPE_FULL).readSummaryFromParcelLocked(in);
            }
            if (in.readInt() != 0) {
                getWakelockTimerLocked(wl, WAKE_TYPE_PARTIAL).readSummaryFromParcelLocked(in);
            }
            if (in.readInt() != 0) {
                getWakelockTimerLocked(wl, WAKE_TYPE_WINDOW).readSummaryFromParcelLocked(in);
            }
            if (in.readInt() != 0) {
                getWakelockTimerLocked(wl, WAKE_TYPE_DRAW).readSummaryFromParcelLocked(in);
            }
        }

        public DualTimer getSensorTimerLocked(int sensor, boolean create) {
            Sensor se = mSensorStats.get(sensor);
            if (se == null) {
                if (!create) {
                    return null;
                }
                se = new Sensor(mBsi, this, sensor);
                mSensorStats.put(sensor, se);
            }
            DualTimer t = se.mTimer;
            if (t != null) {
                return t;
            }
            ArrayList<StopwatchTimer> timers = mBsi.mSensorTimers.get(sensor);
            if (timers == null) {
                timers = new ArrayList<StopwatchTimer>();
                mBsi.mSensorTimers.put(sensor, timers);
            }
            t = new DualTimer(mBsi.mClocks, this, BatteryStats.SENSOR, timers,
                    mBsi.mOnBatteryTimeBase, mOnBatteryBackgroundTimeBase);
            se.mTimer = t;
            return t;
        }

        public void noteStartSyncLocked(String name, long elapsedRealtimeMs) {
            DualTimer t = mSyncStats.startObject(name);
            if (t != null) {
                t.startRunningLocked(elapsedRealtimeMs);
            }
        }

        public void noteStopSyncLocked(String name, long elapsedRealtimeMs) {
            DualTimer t = mSyncStats.stopObject(name);
            if (t != null) {
                t.stopRunningLocked(elapsedRealtimeMs);
            }
        }

        public void noteStartJobLocked(String name, long elapsedRealtimeMs) {
            DualTimer t = mJobStats.startObject(name);
            if (t != null) {
                t.startRunningLocked(elapsedRealtimeMs);
            }
        }

        public void noteStopJobLocked(String name, long elapsedRealtimeMs, int stopReason) {
            DualTimer t = mJobStats.stopObject(name);
            if (t != null) {
                t.stopRunningLocked(elapsedRealtimeMs);
            }
            if (mBsi.mOnBatteryTimeBase.isRunning()) {
                SparseIntArray types = mJobCompletions.get(name);
                if (types == null) {
                    types = new SparseIntArray();
                    mJobCompletions.put(name, types);
                }
                int last = types.get(stopReason, 0);
                types.put(stopReason, last + 1);
            }
        }

        public StopwatchTimer getWakelockTimerLocked(Wakelock wl, int type) {
            if (wl == null) {
                return null;
            }
            switch (type) {
                case WAKE_TYPE_PARTIAL: {
                    DualTimer t = wl.mTimerPartial;
                    if (t == null) {
                        t = new DualTimer(mBsi.mClocks, this, WAKE_TYPE_PARTIAL,
                                mBsi.mPartialTimers, mBsi.mOnBatteryScreenOffTimeBase,
                                mOnBatteryScreenOffBackgroundTimeBase);
                        wl.mTimerPartial = t;
                    }
                    return t;
                }
                case WAKE_TYPE_FULL: {
                    StopwatchTimer t = wl.mTimerFull;
                    if (t == null) {
                        t = new StopwatchTimer(mBsi.mClocks, this, WAKE_TYPE_FULL,
                                mBsi.mFullTimers, mBsi.mOnBatteryTimeBase);
                        wl.mTimerFull = t;
                    }
                    return t;
                }
                case WAKE_TYPE_WINDOW: {
                    StopwatchTimer t = wl.mTimerWindow;
                    if (t == null) {
                        t = new StopwatchTimer(mBsi.mClocks, this, WAKE_TYPE_WINDOW,
                                mBsi.mWindowTimers, mBsi.mOnBatteryTimeBase);
                        wl.mTimerWindow = t;
                    }
                    return t;
                }
                case WAKE_TYPE_DRAW: {
                    StopwatchTimer t = wl.mTimerDraw;
                    if (t == null) {
                        t = new StopwatchTimer(mBsi.mClocks, this, WAKE_TYPE_DRAW,
                                mBsi.mDrawTimers, mBsi.mOnBatteryTimeBase);
                        wl.mTimerDraw = t;
                    }
                    return t;
                }
                default:
                    throw new IllegalArgumentException("type=" + type);
            }
        }

        public void noteStartWakeLocked(int pid, String name, int type, long elapsedRealtimeMs) {
            Wakelock wl = mWakelockStats.startObject(name);
            if (wl != null) {
                getWakelockTimerLocked(wl, type).startRunningLocked(elapsedRealtimeMs);
            }
            if (type == WAKE_TYPE_PARTIAL) {
                createAggregatedPartialWakelockTimerLocked().startRunningLocked(elapsedRealtimeMs);
                if (pid >= 0) {
                    Pid p = getPidStatsLocked(pid);
                    if (p.mWakeNesting++ == 0) {
                        p.mWakeStartMs = elapsedRealtimeMs;
                    }
                }
            }
        }

        public void noteStopWakeLocked(int pid, String name, int type, long elapsedRealtimeMs) {
            Wakelock wl = mWakelockStats.stopObject(name);
            if (wl != null) {
                StopwatchTimer wlt = getWakelockTimerLocked(wl, type);
                wlt.stopRunningLocked(elapsedRealtimeMs);
            }
            if (type == WAKE_TYPE_PARTIAL) {
                if (mAggregatedPartialWakelockTimer != null) {
                    mAggregatedPartialWakelockTimer.stopRunningLocked(elapsedRealtimeMs);
                }
                if (pid >= 0) {
                    Pid p = mPids.get(pid);
                    if (p != null && p.mWakeNesting > 0) {
                        if (p.mWakeNesting-- == 1) {
                            p.mWakeSumMs += elapsedRealtimeMs - p.mWakeStartMs;
                            p.mWakeStartMs = 0;
                        }
                    }
                }
            }
        }

        public void reportExcessiveCpuLocked(String proc, long overTime, long usedTime) {
            Proc p = getProcessStatsLocked(proc);
            if (p != null) {
                p.addExcessiveCpu(overTime, usedTime);
            }
        }

        public void noteStartSensor(int sensor, long elapsedRealtimeMs) {
            DualTimer t = getSensorTimerLocked(sensor, /* create= */ true);
            t.startRunningLocked(elapsedRealtimeMs);
        }

        public void noteStopSensor(int sensor, long elapsedRealtimeMs) {
            // Don't create a timer if one doesn't already exist
            DualTimer t = getSensorTimerLocked(sensor, false);
            if (t != null) {
                t.stopRunningLocked(elapsedRealtimeMs);
            }
        }

        public void noteStartGps(long elapsedRealtimeMs) {
            noteStartSensor(Sensor.GPS, elapsedRealtimeMs);
        }

        public void noteStopGps(long elapsedRealtimeMs) {
            noteStopSensor(Sensor.GPS, elapsedRealtimeMs);
        }

        public BatteryStatsImpl getBatteryStats() {
            return mBsi;
        }
    }

    public long[] getCpuFreqs() {
        return mCpuFreqs;
    }

    public BatteryStatsImpl(File systemDir, Handler handler, PlatformIdleStateCallback cb,
            RailEnergyDataCallback railStatsCb, UserInfoProvider userInfoProvider) {
        this(new SystemClocks(), systemDir, handler, cb, railStatsCb, userInfoProvider);
    }

    private BatteryStatsImpl(Clocks clocks, File systemDir, Handler handler,
            PlatformIdleStateCallback cb, RailEnergyDataCallback railStatsCb,
            UserInfoProvider userInfoProvider) {
        init(clocks);


        if (systemDir == null) {
            mStatsFile = null;
            mBatteryStatsHistory = new BatteryStatsHistory(this, mHistoryBuffer);
        } else {
            mStatsFile = new AtomicFile(new File(systemDir, "batterystats.bin"));
            mBatteryStatsHistory = new BatteryStatsHistory(this, systemDir, mHistoryBuffer);
        }
        mCheckinFile = new AtomicFile(new File(systemDir, "batterystats-checkin.bin"));
        mDailyFile = new AtomicFile(new File(systemDir, "batterystats-daily.xml"));
        mHandler = new MyHandler(handler.getLooper());
        mConstants = new Constants(mHandler);
        mStartCount++;
        mScreenOnTimer = new StopwatchTimer(mClocks, null, -1, null, mOnBatteryTimeBase);
        mScreenDozeTimer = new StopwatchTimer(mClocks, null, -1, null, mOnBatteryTimeBase);
        for (int i=0; i<NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            mScreenBrightnessTimer[i] = new StopwatchTimer(mClocks, null, -100-i, null,
                    mOnBatteryTimeBase);
        }
        mInteractiveTimer = new StopwatchTimer(mClocks, null, -10, null, mOnBatteryTimeBase);
        mPowerSaveModeEnabledTimer = new StopwatchTimer(mClocks, null, -2, null,
                mOnBatteryTimeBase);
        mDeviceIdleModeLightTimer = new StopwatchTimer(mClocks, null, -11, null,
                mOnBatteryTimeBase);
        mDeviceIdleModeFullTimer = new StopwatchTimer(mClocks, null, -14, null, mOnBatteryTimeBase);
        mDeviceLightIdlingTimer = new StopwatchTimer(mClocks, null, -15, null, mOnBatteryTimeBase);
        mDeviceIdlingTimer = new StopwatchTimer(mClocks, null, -12, null, mOnBatteryTimeBase);
        mPhoneOnTimer = new StopwatchTimer(mClocks, null, -3, null, mOnBatteryTimeBase);
        for (int i=0; i<SignalStrength.NUM_SIGNAL_STRENGTH_BINS; i++) {
            mPhoneSignalStrengthsTimer[i] = new StopwatchTimer(mClocks, null, -200-i, null,
                    mOnBatteryTimeBase);
        }
        mPhoneSignalScanningTimer = new StopwatchTimer(mClocks, null, -200+1, null,
                mOnBatteryTimeBase);
        for (int i=0; i<NUM_DATA_CONNECTION_TYPES; i++) {
            mPhoneDataConnectionsTimer[i] = new StopwatchTimer(mClocks, null, -300-i, null,
                    mOnBatteryTimeBase);
        }
        for (int i = 0; i < NUM_NETWORK_ACTIVITY_TYPES; i++) {
            mNetworkByteActivityCounters[i] = new LongSamplingCounter(mOnBatteryTimeBase);
            mNetworkPacketActivityCounters[i] = new LongSamplingCounter(mOnBatteryTimeBase);
        }
        mWifiActivity = new ControllerActivityCounterImpl(mOnBatteryTimeBase, NUM_WIFI_TX_LEVELS);
        mBluetoothActivity = new ControllerActivityCounterImpl(mOnBatteryTimeBase,
                NUM_BT_TX_LEVELS);
        mModemActivity = new ControllerActivityCounterImpl(mOnBatteryTimeBase,
                ModemActivityInfo.TX_POWER_LEVELS);
        mMobileRadioActiveTimer = new StopwatchTimer(mClocks, null, -400, null, mOnBatteryTimeBase);
        mMobileRadioActivePerAppTimer = new StopwatchTimer(mClocks, null, -401, null,
                mOnBatteryTimeBase);
        mMobileRadioActiveAdjustedTime = new LongSamplingCounter(mOnBatteryTimeBase);
        mMobileRadioActiveUnknownTime = new LongSamplingCounter(mOnBatteryTimeBase);
        mMobileRadioActiveUnknownCount = new LongSamplingCounter(mOnBatteryTimeBase);
        mWifiMulticastWakelockTimer = new StopwatchTimer(mClocks, null,
                WIFI_AGGREGATE_MULTICAST_ENABLED, null, mOnBatteryTimeBase);
        mWifiOnTimer = new StopwatchTimer(mClocks, null, -4, null, mOnBatteryTimeBase);
        mGlobalWifiRunningTimer = new StopwatchTimer(mClocks, null, -5, null, mOnBatteryTimeBase);
        for (int i=0; i<NUM_WIFI_STATES; i++) {
            mWifiStateTimer[i] = new StopwatchTimer(mClocks, null, -600-i, null,
                    mOnBatteryTimeBase);
        }
        for (int i=0; i<NUM_WIFI_SUPPL_STATES; i++) {
            mWifiSupplStateTimer[i] = new StopwatchTimer(mClocks, null, -700-i, null,
                    mOnBatteryTimeBase);
        }
        for (int i=0; i<NUM_WIFI_SIGNAL_STRENGTH_BINS; i++) {
            mWifiSignalStrengthsTimer[i] = new StopwatchTimer(mClocks, null, -800-i, null,
                    mOnBatteryTimeBase);
        }
        mWifiActiveTimer = new StopwatchTimer(mClocks, null, -900, null, mOnBatteryTimeBase);
        for (int i=0; i< GnssMetrics.NUM_GPS_SIGNAL_QUALITY_LEVELS; i++) {
            mGpsSignalQualityTimer[i] = new StopwatchTimer(mClocks, null, -1000-i, null,
                mOnBatteryTimeBase);
        }
        mAudioOnTimer = new StopwatchTimer(mClocks, null, -7, null, mOnBatteryTimeBase);
        mVideoOnTimer = new StopwatchTimer(mClocks, null, -8, null, mOnBatteryTimeBase);
        mFlashlightOnTimer = new StopwatchTimer(mClocks, null, -9, null, mOnBatteryTimeBase);
        mCameraOnTimer = new StopwatchTimer(mClocks, null, -13, null, mOnBatteryTimeBase);
        mBluetoothScanTimer = new StopwatchTimer(mClocks, null, -14, null, mOnBatteryTimeBase);
        mDischargeScreenOffCounter = new LongSamplingCounter(mOnBatteryScreenOffTimeBase);
        mDischargeScreenDozeCounter = new LongSamplingCounter(mOnBatteryTimeBase);
        mDischargeLightDozeCounter = new LongSamplingCounter(mOnBatteryTimeBase);
        mDischargeDeepDozeCounter = new LongSamplingCounter(mOnBatteryTimeBase);
        mDischargeCounter = new LongSamplingCounter(mOnBatteryTimeBase);
        mOnBattery = mOnBatteryInternal = false;
        long uptime = mClocks.uptimeMillis() * 1000;
        long realtime = mClocks.elapsedRealtime() * 1000;
        initTimes(uptime, realtime);
        mStartPlatformVersion = mEndPlatformVersion = Build.ID;
        mDischargeStartLevel = 0;
        mDischargeUnplugLevel = 0;
        mDischargePlugLevel = -1;
        mDischargeCurrentLevel = 0;
        mCurrentBatteryLevel = 0;
        initDischarge();
        clearHistoryLocked();
        updateDailyDeadlineLocked();
        mPlatformIdleStateCallback = cb;
        mRailEnergyDataCallback = railStatsCb;
        mUserInfoProvider = userInfoProvider;
    }

    @UnsupportedAppUsage
    public BatteryStatsImpl(Parcel p) {
        this(new SystemClocks(), p);
    }

    public BatteryStatsImpl(Clocks clocks, Parcel p) {
        init(clocks);
        mStatsFile = null;
        mCheckinFile = null;
        mDailyFile = null;
        mHandler = null;
        mExternalSync = null;
        mConstants = new Constants(mHandler);
        clearHistoryLocked();
        mBatteryStatsHistory = new BatteryStatsHistory(this, mHistoryBuffer);
        readFromParcel(p);
        mPlatformIdleStateCallback = null;
        mRailEnergyDataCallback = null;
    }

    public void setPowerProfileLocked(PowerProfile profile) {
        mPowerProfile = profile;

        // We need to initialize the KernelCpuSpeedReaders to read from
        // the first cpu of each core. Once we have the PowerProfile, we have access to this
        // information.
        final int numClusters = mPowerProfile.getNumCpuClusters();
        mKernelCpuSpeedReaders = new KernelCpuSpeedReader[numClusters];
        int firstCpuOfCluster = 0;
        for (int i = 0; i < numClusters; i++) {
            final int numSpeedSteps = mPowerProfile.getNumSpeedStepsInCpuCluster(i);
            mKernelCpuSpeedReaders[i] = new KernelCpuSpeedReader(firstCpuOfCluster,
                    numSpeedSteps);
            firstCpuOfCluster += mPowerProfile.getNumCoresInCpuCluster(i);
        }

        if (mEstimatedBatteryCapacity == -1) {
            // Initialize the estimated battery capacity to a known preset one.
            mEstimatedBatteryCapacity = (int) mPowerProfile.getBatteryCapacity();
        }
    }

    public void setCallback(BatteryCallback cb) {
        mCallback = cb;
    }

    public void setRadioScanningTimeoutLocked(long timeout) {
        if (mPhoneSignalScanningTimer != null) {
            mPhoneSignalScanningTimer.setTimeout(timeout);
        }
    }

    public void setExternalStatsSyncLocked(ExternalStatsSync sync) {
        mExternalSync = sync;
    }

    public void updateDailyDeadlineLocked() {
        // Get the current time.
        long currentTime = mDailyStartTime = System.currentTimeMillis();
        Calendar calDeadline = Calendar.getInstance();
        calDeadline.setTimeInMillis(currentTime);

        // Move time up to the next day, ranging from 1am to 3pm.
        calDeadline.set(Calendar.DAY_OF_YEAR, calDeadline.get(Calendar.DAY_OF_YEAR) + 1);
        calDeadline.set(Calendar.MILLISECOND, 0);
        calDeadline.set(Calendar.SECOND, 0);
        calDeadline.set(Calendar.MINUTE, 0);
        calDeadline.set(Calendar.HOUR_OF_DAY, 1);
        mNextMinDailyDeadline = calDeadline.getTimeInMillis();
        calDeadline.set(Calendar.HOUR_OF_DAY, 3);
        mNextMaxDailyDeadline = calDeadline.getTimeInMillis();
    }

    public void recordDailyStatsIfNeededLocked(boolean settled) {
        long currentTime = System.currentTimeMillis();
        if (currentTime >= mNextMaxDailyDeadline) {
            recordDailyStatsLocked();
        } else if (settled && currentTime >= mNextMinDailyDeadline) {
            recordDailyStatsLocked();
        } else if (currentTime < (mDailyStartTime-(1000*60*60*24))) {
            recordDailyStatsLocked();
        }
    }

    public void recordDailyStatsLocked() {
        DailyItem item = new DailyItem();
        item.mStartTime = mDailyStartTime;
        item.mEndTime = System.currentTimeMillis();
        boolean hasData = false;
        if (mDailyDischargeStepTracker.mNumStepDurations > 0) {
            hasData = true;
            item.mDischargeSteps = new LevelStepTracker(
                    mDailyDischargeStepTracker.mNumStepDurations,
                    mDailyDischargeStepTracker.mStepDurations);
        }
        if (mDailyChargeStepTracker.mNumStepDurations > 0) {
            hasData = true;
            item.mChargeSteps = new LevelStepTracker(
                    mDailyChargeStepTracker.mNumStepDurations,
                    mDailyChargeStepTracker.mStepDurations);
        }
        if (mDailyPackageChanges != null) {
            hasData = true;
            item.mPackageChanges = mDailyPackageChanges;
            mDailyPackageChanges = null;
        }
        mDailyDischargeStepTracker.init();
        mDailyChargeStepTracker.init();
        updateDailyDeadlineLocked();

        if (hasData) {
            final long startTime = SystemClock.uptimeMillis();
            mDailyItems.add(item);
            while (mDailyItems.size() > MAX_DAILY_ITEMS) {
                mDailyItems.remove(0);
            }
            final ByteArrayOutputStream memStream = new ByteArrayOutputStream();
            try {
                XmlSerializer out = new FastXmlSerializer();
                out.setOutput(memStream, StandardCharsets.UTF_8.name());
                writeDailyItemsLocked(out);
                final long initialTime = SystemClock.uptimeMillis() - startTime;
                BackgroundThread.getHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        synchronized (mCheckinFile) {
                            final long startTime2 = SystemClock.uptimeMillis();
                            FileOutputStream stream = null;
                            try {
                                stream = mDailyFile.startWrite();
                                memStream.writeTo(stream);
                                stream.flush();
                                mDailyFile.finishWrite(stream);
                                com.android.internal.logging.EventLogTags.writeCommitSysConfigFile(
                                        "batterystats-daily",
                                        initialTime + SystemClock.uptimeMillis() - startTime2);
                            } catch (IOException e) {
                                Slog.w("BatteryStats",
                                        "Error writing battery daily items", e);
                                mDailyFile.failWrite(stream);
                            }
                        }
                    }
                });
            } catch (IOException e) {
            }
        }
    }

    private void writeDailyItemsLocked(XmlSerializer out) throws IOException {
        StringBuilder sb = new StringBuilder(64);
        out.startDocument(null, true);
        out.startTag(null, "daily-items");
        for (int i=0; i<mDailyItems.size(); i++) {
            final DailyItem dit = mDailyItems.get(i);
            out.startTag(null, "item");
            out.attribute(null, "start", Long.toString(dit.mStartTime));
            out.attribute(null, "end", Long.toString(dit.mEndTime));
            writeDailyLevelSteps(out, "dis", dit.mDischargeSteps, sb);
            writeDailyLevelSteps(out, "chg", dit.mChargeSteps, sb);
            if (dit.mPackageChanges != null) {
                for (int j=0; j<dit.mPackageChanges.size(); j++) {
                    PackageChange pc = dit.mPackageChanges.get(j);
                    if (pc.mUpdate) {
                        out.startTag(null, "upd");
                        out.attribute(null, "pkg", pc.mPackageName);
                        out.attribute(null, "ver", Long.toString(pc.mVersionCode));
                        out.endTag(null, "upd");
                    } else {
                        out.startTag(null, "rem");
                        out.attribute(null, "pkg", pc.mPackageName);
                        out.endTag(null, "rem");
                    }
                }
            }
            out.endTag(null, "item");
        }
        out.endTag(null, "daily-items");
        out.endDocument();
    }

    private void writeDailyLevelSteps(XmlSerializer out, String tag, LevelStepTracker steps,
            StringBuilder tmpBuilder) throws IOException {
        if (steps != null) {
            out.startTag(null, tag);
            out.attribute(null, "n", Integer.toString(steps.mNumStepDurations));
            for (int i=0; i<steps.mNumStepDurations; i++) {
                out.startTag(null, "s");
                tmpBuilder.setLength(0);
                steps.encodeEntryAt(i, tmpBuilder);
                out.attribute(null, "v", tmpBuilder.toString());
                out.endTag(null, "s");
            }
            out.endTag(null, tag);
        }
    }

    public void readDailyStatsLocked() {
        Slog.d(TAG, "Reading daily items from " + mDailyFile.getBaseFile());
        mDailyItems.clear();
        FileInputStream stream;
        try {
            stream = mDailyFile.openRead();
        } catch (FileNotFoundException e) {
            return;
        }
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(stream, StandardCharsets.UTF_8.name());
            readDailyItemsLocked(parser);
        } catch (XmlPullParserException e) {
        } finally {
            try {
                stream.close();
            } catch (IOException e) {
            }
        }
    }

    private void readDailyItemsLocked(XmlPullParser parser) {
        try {
            int type;
            while ((type = parser.next()) != XmlPullParser.START_TAG
                    && type != XmlPullParser.END_DOCUMENT) {
                ;
            }

            if (type != XmlPullParser.START_TAG) {
                throw new IllegalStateException("no start tag found");
            }

            int outerDepth = parser.getDepth();
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                    && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                    continue;
                }

                String tagName = parser.getName();
                if (tagName.equals("item")) {
                    readDailyItemTagLocked(parser);
                } else {
                    Slog.w(TAG, "Unknown element under <daily-items>: "
                            + parser.getName());
                    XmlUtils.skipCurrentTag(parser);
                }
            }

        } catch (IllegalStateException e) {
            Slog.w(TAG, "Failed parsing daily " + e);
        } catch (NullPointerException e) {
            Slog.w(TAG, "Failed parsing daily " + e);
        } catch (NumberFormatException e) {
            Slog.w(TAG, "Failed parsing daily " + e);
        } catch (XmlPullParserException e) {
            Slog.w(TAG, "Failed parsing daily " + e);
        } catch (IOException e) {
            Slog.w(TAG, "Failed parsing daily " + e);
        } catch (IndexOutOfBoundsException e) {
            Slog.w(TAG, "Failed parsing daily " + e);
        }
    }

    void readDailyItemTagLocked(XmlPullParser parser) throws NumberFormatException,
            XmlPullParserException, IOException {
        DailyItem dit = new DailyItem();
        String attr = parser.getAttributeValue(null, "start");
        if (attr != null) {
            dit.mStartTime = Long.parseLong(attr);
        }
        attr = parser.getAttributeValue(null, "end");
        if (attr != null) {
            dit.mEndTime = Long.parseLong(attr);
        }
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals("dis")) {
                readDailyItemTagDetailsLocked(parser, dit, false, "dis");
            } else if (tagName.equals("chg")) {
                readDailyItemTagDetailsLocked(parser, dit, true, "chg");
            } else if (tagName.equals("upd")) {
                if (dit.mPackageChanges == null) {
                    dit.mPackageChanges = new ArrayList<>();
                }
                PackageChange pc = new PackageChange();
                pc.mUpdate = true;
                pc.mPackageName = parser.getAttributeValue(null, "pkg");
                String verStr = parser.getAttributeValue(null, "ver");
                pc.mVersionCode = verStr != null ? Long.parseLong(verStr) : 0;
                dit.mPackageChanges.add(pc);
                XmlUtils.skipCurrentTag(parser);
            } else if (tagName.equals("rem")) {
                if (dit.mPackageChanges == null) {
                    dit.mPackageChanges = new ArrayList<>();
                }
                PackageChange pc = new PackageChange();
                pc.mUpdate = false;
                pc.mPackageName = parser.getAttributeValue(null, "pkg");
                dit.mPackageChanges.add(pc);
                XmlUtils.skipCurrentTag(parser);
            } else {
                Slog.w(TAG, "Unknown element under <item>: "
                        + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
        mDailyItems.add(dit);
    }

    void readDailyItemTagDetailsLocked(XmlPullParser parser, DailyItem dit, boolean isCharge,
            String tag)
            throws NumberFormatException, XmlPullParserException, IOException {
        final String numAttr = parser.getAttributeValue(null, "n");
        if (numAttr == null) {
            Slog.w(TAG, "Missing 'n' attribute at " + parser.getPositionDescription());
            XmlUtils.skipCurrentTag(parser);
            return;
        }
        final int num = Integer.parseInt(numAttr);
        LevelStepTracker steps = new LevelStepTracker(num);
        if (isCharge) {
            dit.mChargeSteps = steps;
        } else {
            dit.mDischargeSteps = steps;
        }
        int i = 0;
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if ("s".equals(tagName)) {
                if (i < num) {
                    String valueAttr = parser.getAttributeValue(null, "v");
                    if (valueAttr != null) {
                        steps.decodeEntryAt(i, valueAttr);
                        i++;
                    }
                }
            } else {
                Slog.w(TAG, "Unknown element under <" + tag + ">: "
                        + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
        steps.mNumStepDurations = i;
    }

    @Override
    public DailyItem getDailyItemLocked(int daysAgo) {
        int index = mDailyItems.size()-1-daysAgo;
        return index >= 0 ? mDailyItems.get(index) : null;
    }

    @Override
    public long getCurrentDailyStartTime() {
        return mDailyStartTime;
    }

    @Override
    public long getNextMinDailyDeadline() {
        return mNextMinDailyDeadline;
    }

    @Override
    public long getNextMaxDailyDeadline() {
        return mNextMaxDailyDeadline;
    }

    @Override
    public boolean startIteratingOldHistoryLocked() {
        if (DEBUG_HISTORY) Slog.i(TAG, "ITERATING: buff size=" + mHistoryBuffer.dataSize()
                + " pos=" + mHistoryBuffer.dataPosition());
        if ((mHistoryIterator = mHistory) == null) {
            return false;
        }
        mHistoryBuffer.setDataPosition(0);
        mHistoryReadTmp.clear();
        mReadOverflow = false;
        mIteratingHistory = true;
        return true;
    }

    @Override
    public boolean getNextOldHistoryLocked(HistoryItem out) {
        boolean end = mHistoryBuffer.dataPosition() >= mHistoryBuffer.dataSize();
        if (!end) {
            readHistoryDelta(mHistoryBuffer, mHistoryReadTmp);
            mReadOverflow |= mHistoryReadTmp.cmd == HistoryItem.CMD_OVERFLOW;
        }
        HistoryItem cur = mHistoryIterator;
        if (cur == null) {
            if (!mReadOverflow && !end) {
                Slog.w(TAG, "Old history ends before new history!");
            }
            return false;
        }
        out.setTo(cur);
        mHistoryIterator = cur.next;
        if (!mReadOverflow) {
            if (end) {
                Slog.w(TAG, "New history ends before old history!");
            } else if (!out.same(mHistoryReadTmp)) {
                PrintWriter pw = new FastPrintWriter(new LogWriter(android.util.Log.WARN, TAG));
                pw.println("Histories differ!");
                pw.println("Old history:");
                (new HistoryPrinter()).printNextItem(pw, out, 0, false, true);
                pw.println("New history:");
                (new HistoryPrinter()).printNextItem(pw, mHistoryReadTmp, 0, false,
                        true);
                pw.flush();
            }
        }
        return true;
    }

    @Override
    public void finishIteratingOldHistoryLocked() {
        mIteratingHistory = false;
        mHistoryBuffer.setDataPosition(mHistoryBuffer.dataSize());
        mHistoryIterator = null;
    }

    public int getHistoryTotalSize() {
        return mConstants.MAX_HISTORY_BUFFER * mConstants.MAX_HISTORY_FILES;
    }

    public int getHistoryUsedSize() {
        return mBatteryStatsHistory.getHistoryUsedSize();
    }

    @Override
    @UnsupportedAppUsage
    public boolean startIteratingHistoryLocked() {
        mBatteryStatsHistory.startIteratingHistory();
        mReadOverflow = false;
        mIteratingHistory = true;
        mReadHistoryStrings = new String[mHistoryTagPool.size()];
        mReadHistoryUids = new int[mHistoryTagPool.size()];
        mReadHistoryChars = 0;
        for (HashMap.Entry<HistoryTag, Integer> ent : mHistoryTagPool.entrySet()) {
            final HistoryTag tag = ent.getKey();
            final int idx = ent.getValue();
            mReadHistoryStrings[idx] = tag.string;
            mReadHistoryUids[idx] = tag.uid;
            mReadHistoryChars += tag.string.length() + 1;
        }
        return true;
    }

    @Override
    public int getHistoryStringPoolSize() {
        return mReadHistoryStrings.length;
    }

    @Override
    public int getHistoryStringPoolBytes() {
        // Each entry is a fixed 12 bytes: 4 for index, 4 for uid, 4 for string size
        // Each string character is 2 bytes.
        return (mReadHistoryStrings.length * 12) + (mReadHistoryChars * 2);
    }

    @Override
    public String getHistoryTagPoolString(int index) {
        return mReadHistoryStrings[index];
    }

    @Override
    public int getHistoryTagPoolUid(int index) {
        return mReadHistoryUids[index];
    }

    @Override
    @UnsupportedAppUsage
    public boolean getNextHistoryLocked(HistoryItem out) {
        Parcel p = mBatteryStatsHistory.getNextParcel(out);
        if (p == null) {
            return false;
        }
        final long lastRealtime = out.time;
        final long lastWalltime = out.currentTime;
        readHistoryDelta(p, out);
        if (out.cmd != HistoryItem.CMD_CURRENT_TIME
                && out.cmd != HistoryItem.CMD_RESET && lastWalltime != 0) {
            out.currentTime = lastWalltime + (out.time - lastRealtime);
        }
        return true;
    }

    @Override
    public void finishIteratingHistoryLocked() {
        mBatteryStatsHistory.finishIteratingHistory();
        mIteratingHistory = false;
        mReadHistoryStrings = null;
        mReadHistoryUids = null;
    }

    @Override
    public long getHistoryBaseTime() {
        return mHistoryBaseTime;
    }

    @Override
    public int getStartCount() {
        return mStartCount;
    }

    @UnsupportedAppUsage
    public boolean isOnBattery() {
        return mOnBattery;
    }

    public boolean isCharging() {
        return mCharging;
    }

    public boolean isScreenOn(int state) {
        return state == Display.STATE_ON || state == Display.STATE_VR
            || state == Display.STATE_ON_SUSPEND;
    }

    public boolean isScreenOff(int state) {
        return state == Display.STATE_OFF;
    }

    public boolean isScreenDoze(int state) {
        return state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND;
    }

    void initTimes(long uptime, long realtime) {
        mStartClockTime = System.currentTimeMillis();
        mOnBatteryTimeBase.init(uptime, realtime);
        mOnBatteryScreenOffTimeBase.init(uptime, realtime);
        mRealtime = 0;
        mUptime = 0;
        mRealtimeStart = realtime;
        mUptimeStart = uptime;
    }

    void initDischarge() {
        mLowDischargeAmountSinceCharge = 0;
        mHighDischargeAmountSinceCharge = 0;
        mDischargeAmountScreenOn = 0;
        mDischargeAmountScreenOnSinceCharge = 0;
        mDischargeAmountScreenOff = 0;
        mDischargeAmountScreenOffSinceCharge = 0;
        mDischargeAmountScreenDoze = 0;
        mDischargeAmountScreenDozeSinceCharge = 0;
        mDischargeStepTracker.init();
        mChargeStepTracker.init();
        mDischargeScreenOffCounter.reset(false);
        mDischargeScreenDozeCounter.reset(false);
        mDischargeLightDozeCounter.reset(false);
        mDischargeDeepDozeCounter.reset(false);
        mDischargeCounter.reset(false);
    }

    public void resetAllStatsCmdLocked() {
        resetAllStatsLocked();
        final long mSecUptime = mClocks.uptimeMillis();
        long uptime = mSecUptime * 1000;
        long mSecRealtime = mClocks.elapsedRealtime();
        long realtime = mSecRealtime * 1000;
        mDischargeStartLevel = mHistoryCur.batteryLevel;
        pullPendingStateUpdatesLocked();
        addHistoryRecordLocked(mSecRealtime, mSecUptime);
        mDischargeCurrentLevel = mDischargeUnplugLevel = mDischargePlugLevel
                = mCurrentBatteryLevel = mHistoryCur.batteryLevel;
        mOnBatteryTimeBase.reset(uptime, realtime);
        mOnBatteryScreenOffTimeBase.reset(uptime, realtime);
        if ((mHistoryCur.states&HistoryItem.STATE_BATTERY_PLUGGED_FLAG) == 0) {
            if (isScreenOn(mScreenState)) {
                mDischargeScreenOnUnplugLevel = mHistoryCur.batteryLevel;
                mDischargeScreenDozeUnplugLevel = 0;
                mDischargeScreenOffUnplugLevel = 0;
            } else if (isScreenDoze(mScreenState)) {
                mDischargeScreenOnUnplugLevel = 0;
                mDischargeScreenDozeUnplugLevel = mHistoryCur.batteryLevel;
                mDischargeScreenOffUnplugLevel = 0;
            } else {
                mDischargeScreenOnUnplugLevel = 0;
                mDischargeScreenDozeUnplugLevel = 0;
                mDischargeScreenOffUnplugLevel = mHistoryCur.batteryLevel;
            }
            mDischargeAmountScreenOn = 0;
            mDischargeAmountScreenOff = 0;
            mDischargeAmountScreenDoze = 0;
        }
        initActiveHistoryEventsLocked(mSecRealtime, mSecUptime);
    }

    private void resetAllStatsLocked() {
        final long uptimeMillis = mClocks.uptimeMillis();
        final long elapsedRealtimeMillis = mClocks.elapsedRealtime();
        mStartCount = 0;
        initTimes(uptimeMillis * 1000, elapsedRealtimeMillis * 1000);
        mScreenOnTimer.reset(false);
        mScreenDozeTimer.reset(false);
        for (int i=0; i<NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            mScreenBrightnessTimer[i].reset(false);
        }

        if (mPowerProfile != null) {
            mEstimatedBatteryCapacity = (int) mPowerProfile.getBatteryCapacity();
        } else {
            mEstimatedBatteryCapacity = -1;
        }
        mMinLearnedBatteryCapacity = -1;
        mMaxLearnedBatteryCapacity = -1;
        mInteractiveTimer.reset(false);
        mPowerSaveModeEnabledTimer.reset(false);
        mLastIdleTimeStart = elapsedRealtimeMillis;
        mLongestLightIdleTime = 0;
        mLongestFullIdleTime = 0;
        mDeviceIdleModeLightTimer.reset(false);
        mDeviceIdleModeFullTimer.reset(false);
        mDeviceLightIdlingTimer.reset(false);
        mDeviceIdlingTimer.reset(false);
        mPhoneOnTimer.reset(false);
        mAudioOnTimer.reset(false);
        mVideoOnTimer.reset(false);
        mFlashlightOnTimer.reset(false);
        mCameraOnTimer.reset(false);
        mBluetoothScanTimer.reset(false);
        for (int i=0; i<SignalStrength.NUM_SIGNAL_STRENGTH_BINS; i++) {
            mPhoneSignalStrengthsTimer[i].reset(false);
        }
        mPhoneSignalScanningTimer.reset(false);
        for (int i=0; i<NUM_DATA_CONNECTION_TYPES; i++) {
            mPhoneDataConnectionsTimer[i].reset(false);
        }
        for (int i = 0; i < NUM_NETWORK_ACTIVITY_TYPES; i++) {
            mNetworkByteActivityCounters[i].reset(false);
            mNetworkPacketActivityCounters[i].reset(false);
        }
        mMobileRadioActiveTimer.reset(false);
        mMobileRadioActivePerAppTimer.reset(false);
        mMobileRadioActiveAdjustedTime.reset(false);
        mMobileRadioActiveUnknownTime.reset(false);
        mMobileRadioActiveUnknownCount.reset(false);
        mWifiOnTimer.reset(false);
        mGlobalWifiRunningTimer.reset(false);
        for (int i=0; i<NUM_WIFI_STATES; i++) {
            mWifiStateTimer[i].reset(false);
        }
        for (int i=0; i<NUM_WIFI_SUPPL_STATES; i++) {
            mWifiSupplStateTimer[i].reset(false);
        }
        for (int i=0; i<NUM_WIFI_SIGNAL_STRENGTH_BINS; i++) {
            mWifiSignalStrengthsTimer[i].reset(false);
        }
        mWifiMulticastWakelockTimer.reset(false);
        mWifiActiveTimer.reset(false);
        mWifiActivity.reset(false);
        for (int i=0; i< GnssMetrics.NUM_GPS_SIGNAL_QUALITY_LEVELS; i++) {
            mGpsSignalQualityTimer[i].reset(false);
        }
        mBluetoothActivity.reset(false);
        mModemActivity.reset(false);
        mNumConnectivityChange = 0;

        for (int i=0; i<mUidStats.size(); i++) {
            if (mUidStats.valueAt(i).reset(uptimeMillis * 1000, elapsedRealtimeMillis * 1000)) {
                mUidStats.valueAt(i).detachFromTimeBase();
                mUidStats.remove(mUidStats.keyAt(i));
                i--;
            }
        }

        if (mRpmStats.size() > 0) {
            for (SamplingTimer timer : mRpmStats.values()) {
                mOnBatteryTimeBase.remove(timer);
            }
            mRpmStats.clear();
        }
        if (mScreenOffRpmStats.size() > 0) {
            for (SamplingTimer timer : mScreenOffRpmStats.values()) {
                mOnBatteryScreenOffTimeBase.remove(timer);
            }
            mScreenOffRpmStats.clear();
        }

        if (mKernelWakelockStats.size() > 0) {
            for (SamplingTimer timer : mKernelWakelockStats.values()) {
                mOnBatteryScreenOffTimeBase.remove(timer);
            }
            mKernelWakelockStats.clear();
        }

        if (mKernelMemoryStats.size() > 0) {
            for (int i = 0; i < mKernelMemoryStats.size(); i++) {
                mOnBatteryTimeBase.remove(mKernelMemoryStats.valueAt(i));
            }
            mKernelMemoryStats.clear();
        }

        if (mWakeupReasonStats.size() > 0) {
            for (SamplingTimer timer : mWakeupReasonStats.values()) {
                mOnBatteryTimeBase.remove(timer);
            }
            mWakeupReasonStats.clear();
        }

        mTmpRailStats.reset();

        mLastHistoryStepDetails = null;
        mLastStepCpuUserTime = mLastStepCpuSystemTime = 0;
        mCurStepCpuUserTime = mCurStepCpuSystemTime = 0;
        mLastStepCpuUserTime = mCurStepCpuUserTime = 0;
        mLastStepCpuSystemTime = mCurStepCpuSystemTime = 0;
        mLastStepStatUserTime = mCurStepStatUserTime = 0;
        mLastStepStatSystemTime = mCurStepStatSystemTime = 0;
        mLastStepStatIOWaitTime = mCurStepStatIOWaitTime = 0;
        mLastStepStatIrqTime = mCurStepStatIrqTime = 0;
        mLastStepStatSoftIrqTime = mCurStepStatSoftIrqTime = 0;
        mLastStepStatIdleTime = mCurStepStatIdleTime = 0;

        mNumAllUidCpuTimeReads = 0;
        mNumUidsRemoved = 0;

        initDischarge();

        clearHistoryLocked();
        mBatteryStatsHistory.resetAllFiles();

        mHandler.sendEmptyMessage(MSG_REPORT_RESET_STATS);
    }

    private void initActiveHistoryEventsLocked(long elapsedRealtimeMs, long uptimeMs) {
        for (int i=0; i<HistoryItem.EVENT_COUNT; i++) {
            if (!mRecordAllHistory && i == HistoryItem.EVENT_PROC) {
                // Not recording process starts/stops.
                continue;
            }
            HashMap<String, SparseIntArray> active = mActiveEvents.getStateForEvent(i);
            if (active == null) {
                continue;
            }
            for (HashMap.Entry<String, SparseIntArray> ent : active.entrySet()) {
                SparseIntArray uids = ent.getValue();
                for (int j=0; j<uids.size(); j++) {
                    addHistoryEventLocked(elapsedRealtimeMs, uptimeMs, i, ent.getKey(),
                            uids.keyAt(j));
                }
            }
        }
    }

    void updateDischargeScreenLevelsLocked(int oldState, int newState) {
        updateOldDischargeScreenLevelLocked(oldState);
        updateNewDischargeScreenLevelLocked(newState);
    }

    private void updateOldDischargeScreenLevelLocked(int state) {
        if (isScreenOn(state)) {
            int diff = mDischargeScreenOnUnplugLevel - mDischargeCurrentLevel;
            if (diff > 0) {
                mDischargeAmountScreenOn += diff;
                mDischargeAmountScreenOnSinceCharge += diff;
            }
        } else if (isScreenDoze(state)) {
            int diff = mDischargeScreenDozeUnplugLevel - mDischargeCurrentLevel;
            if (diff > 0) {
                mDischargeAmountScreenDoze += diff;
                mDischargeAmountScreenDozeSinceCharge += diff;
            }
        } else if (isScreenOff(state)){
            int diff = mDischargeScreenOffUnplugLevel - mDischargeCurrentLevel;
            if (diff > 0) {
                mDischargeAmountScreenOff += diff;
                mDischargeAmountScreenOffSinceCharge += diff;
            }
        }
    }

    private void updateNewDischargeScreenLevelLocked(int state) {
        if (isScreenOn(state)) {
            mDischargeScreenOnUnplugLevel = mDischargeCurrentLevel;
            mDischargeScreenOffUnplugLevel = 0;
            mDischargeScreenDozeUnplugLevel = 0;
        } else if (isScreenDoze(state)){
            mDischargeScreenOnUnplugLevel = 0;
            mDischargeScreenDozeUnplugLevel = mDischargeCurrentLevel;
            mDischargeScreenOffUnplugLevel = 0;
        } else if (isScreenOff(state)) {
            mDischargeScreenOnUnplugLevel = 0;
            mDischargeScreenDozeUnplugLevel = 0;
            mDischargeScreenOffUnplugLevel = mDischargeCurrentLevel;
        }
    }

    public void pullPendingStateUpdatesLocked() {
        if (mOnBatteryInternal) {
            updateDischargeScreenLevelsLocked(mScreenState, mScreenState);
        }
    }

    private final Pools.Pool<NetworkStats> mNetworkStatsPool = new Pools.SynchronizedPool<>(6);

    private final Object mWifiNetworkLock = new Object();

    @GuardedBy("mWifiNetworkLock")
    private String[] mWifiIfaces = EmptyArray.STRING;

    @GuardedBy("mWifiNetworkLock")
    private NetworkStats mLastWifiNetworkStats = new NetworkStats(0, -1);

    private final Object mModemNetworkLock = new Object();

    @GuardedBy("mModemNetworkLock")
    private String[] mModemIfaces = EmptyArray.STRING;

    @GuardedBy("mModemNetworkLock")
    private NetworkStats mLastModemNetworkStats = new NetworkStats(0, -1);

    private NetworkStats readNetworkStatsLocked(String[] ifaces) {
        try {
            if (!ArrayUtils.isEmpty(ifaces)) {
                INetworkStatsService statsService = INetworkStatsService.Stub.asInterface(
                        ServiceManager.getService(Context.NETWORK_STATS_SERVICE));
                if (statsService != null) {
                    return statsService.getDetailedUidStats(ifaces);
                } else {
                    Slog.e(TAG, "Failed to get networkStatsService ");
                }
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "failed to read network stats for ifaces: " + Arrays.toString(ifaces) + e);
        }
        return null;
    }

   /**
     * Distribute WiFi energy info and network traffic to apps.
     * @param info The energy information from the WiFi controller.
     */
    public void updateWifiState(@Nullable final WifiActivityEnergyInfo info) {
        if (DEBUG_ENERGY) {
            Slog.d(TAG, "Updating wifi stats: " + Arrays.toString(mWifiIfaces));
        }

        // Grab a separate lock to acquire the network stats, which may do I/O.
        NetworkStats delta = null;
        synchronized (mWifiNetworkLock) {
            final NetworkStats latestStats = readNetworkStatsLocked(mWifiIfaces);
            if (latestStats != null) {
                delta = NetworkStats.subtract(latestStats, mLastWifiNetworkStats, null, null,
                        mNetworkStatsPool.acquire());
                mNetworkStatsPool.release(mLastWifiNetworkStats);
                mLastWifiNetworkStats = latestStats;
            }
        }

        synchronized (this) {
            if (!mOnBatteryInternal) {
                if (delta != null) {
                    mNetworkStatsPool.release(delta);
                }
                return;
            }

            final long elapsedRealtimeMs = mClocks.elapsedRealtime();
            SparseLongArray rxPackets = new SparseLongArray();
            SparseLongArray txPackets = new SparseLongArray();
            long totalTxPackets = 0;
            long totalRxPackets = 0;
            if (delta != null) {
                NetworkStats.Entry entry = new NetworkStats.Entry();
                final int size = delta.size();
                for (int i = 0; i < size; i++) {
                    entry = delta.getValues(i, entry);

                    if (DEBUG_ENERGY) {
                        Slog.d(TAG, "Wifi uid " + entry.uid + ": delta rx=" + entry.rxBytes
                                + " tx=" + entry.txBytes + " rxPackets=" + entry.rxPackets
                                + " txPackets=" + entry.txPackets);
                    }

                    if (entry.rxBytes == 0 && entry.txBytes == 0) {
                        // Skip the lookup below since there is no work to do.
                        continue;
                    }

                    final Uid u = getUidStatsLocked(mapUid(entry.uid));
                    if (entry.rxBytes != 0) {
                        u.noteNetworkActivityLocked(NETWORK_WIFI_RX_DATA, entry.rxBytes,
                                entry.rxPackets);
                        if (entry.set == NetworkStats.SET_DEFAULT) { // Background transfers
                            u.noteNetworkActivityLocked(NETWORK_WIFI_BG_RX_DATA, entry.rxBytes,
                                    entry.rxPackets);
                        }
                        mNetworkByteActivityCounters[NETWORK_WIFI_RX_DATA].addCountLocked(
                                entry.rxBytes);
                        mNetworkPacketActivityCounters[NETWORK_WIFI_RX_DATA].addCountLocked(
                                entry.rxPackets);

                        rxPackets.put(u.getUid(), entry.rxPackets);

                        // Sum the total number of packets so that the Rx Power can
                        // be evenly distributed amongst the apps.
                        totalRxPackets += entry.rxPackets;
                    }

                    if (entry.txBytes != 0) {
                        u.noteNetworkActivityLocked(NETWORK_WIFI_TX_DATA, entry.txBytes,
                                entry.txPackets);
                        if (entry.set == NetworkStats.SET_DEFAULT) { // Background transfers
                            u.noteNetworkActivityLocked(NETWORK_WIFI_BG_TX_DATA, entry.txBytes,
                                    entry.txPackets);
                        }
                        mNetworkByteActivityCounters[NETWORK_WIFI_TX_DATA].addCountLocked(
                                entry.txBytes);
                        mNetworkPacketActivityCounters[NETWORK_WIFI_TX_DATA].addCountLocked(
                                entry.txPackets);

                        txPackets.put(u.getUid(), entry.txPackets);

                        // Sum the total number of packets so that the Tx Power can
                        // be evenly distributed amongst the apps.
                        totalTxPackets += entry.txPackets;
                    }
                }
                mNetworkStatsPool.release(delta);
                delta = null;
            }

            if (info != null) {
                mHasWifiReporting = true;

                // Measured in mAms
                final long txTimeMs = info.getControllerTxTimeMillis();
                final long rxTimeMs = info.getControllerRxTimeMillis();
                final long scanTimeMs = info.getControllerScanTimeMillis();
                final long idleTimeMs = info.getControllerIdleTimeMillis();
                final long totalTimeMs = txTimeMs + rxTimeMs + idleTimeMs;

                long leftOverRxTimeMs = rxTimeMs;
                long leftOverTxTimeMs = txTimeMs;

                if (DEBUG_ENERGY) {
                    Slog.d(TAG, "------ BEGIN WiFi power blaming ------");
                    Slog.d(TAG, "  Tx Time:    " + txTimeMs + " ms");
                    Slog.d(TAG, "  Rx Time:    " + rxTimeMs + " ms");
                    Slog.d(TAG, "  Idle Time:  " + idleTimeMs + " ms");
                    Slog.d(TAG, "  Total Time: " + totalTimeMs + " ms");
                    Slog.d(TAG, "  Scan Time:  " + scanTimeMs + " ms");
                }

                long totalWifiLockTimeMs = 0;
                long totalScanTimeMs = 0;

                // On the first pass, collect some totals so that we can normalize power
                // calculations if we need to.
                final int uidStatsSize = mUidStats.size();
                for (int i = 0; i < uidStatsSize; i++) {
                    final Uid uid = mUidStats.valueAt(i);

                    // Sum the total scan power for all apps.
                    totalScanTimeMs += uid.mWifiScanTimer.getTimeSinceMarkLocked(
                            elapsedRealtimeMs * 1000) / 1000;

                    // Sum the total time holding wifi lock for all apps.
                    totalWifiLockTimeMs += uid.mFullWifiLockTimer.getTimeSinceMarkLocked(
                            elapsedRealtimeMs * 1000) / 1000;
                }

                if (DEBUG_ENERGY && totalScanTimeMs > rxTimeMs) {
                    Slog.d(TAG,
                            "  !Estimated scan time > Actual rx time (" + totalScanTimeMs + " ms > "
                                    + rxTimeMs + " ms). Normalizing scan time.");
                }
                if (DEBUG_ENERGY && totalScanTimeMs > txTimeMs) {
                    Slog.d(TAG,
                            "  !Estimated scan time > Actual tx time (" + totalScanTimeMs + " ms > "
                                    + txTimeMs + " ms). Normalizing scan time.");
                }

                // Actually assign and distribute power usage to apps.
                for (int i = 0; i < uidStatsSize; i++) {
                    final Uid uid = mUidStats.valueAt(i);

                    long scanTimeSinceMarkMs = uid.mWifiScanTimer.getTimeSinceMarkLocked(
                            elapsedRealtimeMs * 1000) / 1000;
                    if (scanTimeSinceMarkMs > 0) {
                        // Set the new mark so that next time we get new data since this point.
                        uid.mWifiScanTimer.setMark(elapsedRealtimeMs);

                        long scanRxTimeSinceMarkMs = scanTimeSinceMarkMs;
                        long scanTxTimeSinceMarkMs = scanTimeSinceMarkMs;

                        // Our total scan time is more than the reported Tx/Rx time.
                        // This is possible because the cost of a scan is approximate.
                        // Let's normalize the result so that we evenly blame each app
                        // scanning.
                        //
                        // This means that we may have apps that transmitted/received packets not be
                        // blamed for this, but this is fine as scans are relatively more expensive.
                        if (totalScanTimeMs > rxTimeMs) {
                            scanRxTimeSinceMarkMs = (rxTimeMs * scanRxTimeSinceMarkMs) /
                                    totalScanTimeMs;
                        }
                        if (totalScanTimeMs > txTimeMs) {
                            scanTxTimeSinceMarkMs = (txTimeMs * scanTxTimeSinceMarkMs) /
                                    totalScanTimeMs;
                        }

                        if (DEBUG_ENERGY) {
                            Slog.d(TAG, "  ScanTime for UID " + uid.getUid() + ": Rx:"
                                    + scanRxTimeSinceMarkMs + " ms  Tx:"
                                    + scanTxTimeSinceMarkMs + " ms)");
                        }

                        ControllerActivityCounterImpl activityCounter =
                                uid.getOrCreateWifiControllerActivityLocked();
                        activityCounter.getRxTimeCounter().addCountLocked(scanRxTimeSinceMarkMs);
                        activityCounter.getTxTimeCounters()[0].addCountLocked(
                                scanTxTimeSinceMarkMs);
                        leftOverRxTimeMs -= scanRxTimeSinceMarkMs;
                        leftOverTxTimeMs -= scanTxTimeSinceMarkMs;
                    }

                    // Distribute evenly the power consumed while Idle to each app holding a WiFi
                    // lock.
                    final long wifiLockTimeSinceMarkMs =
                            uid.mFullWifiLockTimer.getTimeSinceMarkLocked(
                                    elapsedRealtimeMs * 1000) / 1000;
                    if (wifiLockTimeSinceMarkMs > 0) {
                        // Set the new mark so that next time we get new data since this point.
                        uid.mFullWifiLockTimer.setMark(elapsedRealtimeMs);

                        final long myIdleTimeMs = (wifiLockTimeSinceMarkMs * idleTimeMs)
                                / totalWifiLockTimeMs;
                        if (DEBUG_ENERGY) {
                            Slog.d(TAG, "  IdleTime for UID " + uid.getUid() + ": "
                                    + myIdleTimeMs + " ms");
                        }
                        uid.getOrCreateWifiControllerActivityLocked().getIdleTimeCounter()
                                .addCountLocked(myIdleTimeMs);
                    }
                }

                if (DEBUG_ENERGY) {
                    Slog.d(TAG, "  New RxPower: " + leftOverRxTimeMs + " ms");
                    Slog.d(TAG, "  New TxPower: " + leftOverTxTimeMs + " ms");
                }

                // Distribute the remaining Tx power appropriately between all apps that transmitted
                // packets.
                for (int i = 0; i < txPackets.size(); i++) {
                    final Uid uid = getUidStatsLocked(txPackets.keyAt(i));
                    final long myTxTimeMs = (txPackets.valueAt(i) * leftOverTxTimeMs)
                            / totalTxPackets;
                    if (DEBUG_ENERGY) {
                        Slog.d(TAG, "  TxTime for UID " + uid.getUid() + ": " + myTxTimeMs + " ms");
                    }
                    uid.getOrCreateWifiControllerActivityLocked().getTxTimeCounters()[0]
                            .addCountLocked(myTxTimeMs);
                }

                // Distribute the remaining Rx power appropriately between all apps that received
                // packets.
                for (int i = 0; i < rxPackets.size(); i++) {
                    final Uid uid = getUidStatsLocked(rxPackets.keyAt(i));
                    final long myRxTimeMs = (rxPackets.valueAt(i) * leftOverRxTimeMs)
                            / totalRxPackets;
                    if (DEBUG_ENERGY) {
                        Slog.d(TAG, "  RxTime for UID " + uid.getUid() + ": " + myRxTimeMs + " ms");
                    }
                    uid.getOrCreateWifiControllerActivityLocked().getRxTimeCounter()
                            .addCountLocked(myRxTimeMs);
                }

                // Any left over power use will be picked up by the WiFi category in BatteryStatsHelper.


                // Update WiFi controller stats.
                mWifiActivity.getRxTimeCounter().addCountLocked(info.getControllerRxTimeMillis());
                mWifiActivity.getTxTimeCounters()[0].addCountLocked(
                        info.getControllerTxTimeMillis());
                mWifiActivity.getScanTimeCounter().addCountLocked(
                    info.getControllerScanTimeMillis());
                mWifiActivity.getIdleTimeCounter().addCountLocked(
                        info.getControllerIdleTimeMillis());

                // POWER_WIFI_CONTROLLER_OPERATING_VOLTAGE is measured in mV, so convert to V.
                final double opVolt = mPowerProfile.getAveragePower(
                        PowerProfile.POWER_WIFI_CONTROLLER_OPERATING_VOLTAGE) / 1000.0;
                if (opVolt != 0) {
                    // We store the power drain as mAms.
                    mWifiActivity.getPowerCounter().addCountLocked(
                            (long) (info.getControllerEnergyUsed() / opVolt));
                }
                // Converting uWs to mAms.
                // Conversion: (uWs * (1000ms / 1s) * (1mW / 1000uW)) / mV = mAms
                long monitoredRailChargeConsumedMaMs =
                        (long) (mTmpRailStats.getWifiTotalEnergyUseduWs() / opVolt);
                mWifiActivity.getMonitoredRailChargeConsumedMaMs().addCountLocked(
                        monitoredRailChargeConsumedMaMs);
                mHistoryCur.wifiRailChargeMah +=
                        (monitoredRailChargeConsumedMaMs / MILLISECONDS_IN_HOUR);
                addHistoryRecordLocked(mClocks.elapsedRealtime(), mClocks.uptimeMillis());
                mTmpRailStats.resetWifiTotalEnergyUsed();
            }
        }
    }

    private ModemActivityInfo mLastModemActivityInfo =
            new ModemActivityInfo(0, 0, 0, new int[0], 0, 0);

    private ModemActivityInfo getDeltaModemActivityInfo(ModemActivityInfo activityInfo) {
        if (activityInfo == null) {
            return null;
        }
        int[] txTimeMs = new int[ModemActivityInfo.TX_POWER_LEVELS];
        for (int i = 0; i < ModemActivityInfo.TX_POWER_LEVELS; i++) {
            txTimeMs[i] = activityInfo.getTxTimeMillis()[i]
                    - mLastModemActivityInfo.getTxTimeMillis()[i];
        }
        ModemActivityInfo deltaInfo = new ModemActivityInfo(activityInfo.getTimestamp(),
                activityInfo.getSleepTimeMillis() - mLastModemActivityInfo.getSleepTimeMillis(),
                activityInfo.getIdleTimeMillis() - mLastModemActivityInfo.getIdleTimeMillis(),
                txTimeMs,
                activityInfo.getRxTimeMillis() - mLastModemActivityInfo.getRxTimeMillis(),
                activityInfo.getEnergyUsed() - mLastModemActivityInfo.getEnergyUsed());
        mLastModemActivityInfo = activityInfo;
        return deltaInfo;
    }

    /**
     * Distribute Cell radio energy info and network traffic to apps.
     */
    public void updateMobileRadioState(@Nullable final ModemActivityInfo activityInfo) {
        if (DEBUG_ENERGY) {
            Slog.d(TAG, "Updating mobile radio stats with " + activityInfo);
        }
        ModemActivityInfo deltaInfo = getDeltaModemActivityInfo(activityInfo);

        // Add modem tx power to history.
        addModemTxPowerToHistory(deltaInfo);

        // Grab a separate lock to acquire the network stats, which may do I/O.
        NetworkStats delta = null;
        synchronized (mModemNetworkLock) {
            final NetworkStats latestStats = readNetworkStatsLocked(mModemIfaces);
            if (latestStats != null) {
                delta = NetworkStats.subtract(latestStats, mLastModemNetworkStats, null, null,
                        mNetworkStatsPool.acquire());
                mNetworkStatsPool.release(mLastModemNetworkStats);
                mLastModemNetworkStats = latestStats;
            }
        }

        synchronized (this) {
            if (!mOnBatteryInternal) {
                if (delta != null) {
                    mNetworkStatsPool.release(delta);
                }
                return;
            }

            if (deltaInfo != null) {
                mHasModemReporting = true;
                mModemActivity.getIdleTimeCounter().addCountLocked(
                        deltaInfo.getIdleTimeMillis());
                mModemActivity.getSleepTimeCounter().addCountLocked(
                        deltaInfo.getSleepTimeMillis());
                mModemActivity.getRxTimeCounter().addCountLocked(deltaInfo.getRxTimeMillis());
                for (int lvl = 0; lvl < ModemActivityInfo.TX_POWER_LEVELS; lvl++) {
                    mModemActivity.getTxTimeCounters()[lvl]
                        .addCountLocked(deltaInfo.getTxTimeMillis()[lvl]);
                }

                // POWER_MODEM_CONTROLLER_OPERATING_VOLTAGE is measured in mV, so convert to V.
                final double opVolt = mPowerProfile.getAveragePower(
                    PowerProfile.POWER_MODEM_CONTROLLER_OPERATING_VOLTAGE) / 1000.0;
                if (opVolt != 0) {
                    double energyUsed =
                            deltaInfo.getSleepTimeMillis() *
                            mPowerProfile.getAveragePower(PowerProfile.POWER_MODEM_CONTROLLER_SLEEP)
                            + deltaInfo.getIdleTimeMillis() *
                            mPowerProfile.getAveragePower(PowerProfile.POWER_MODEM_CONTROLLER_IDLE)
                            + deltaInfo.getRxTimeMillis() *
                            mPowerProfile.getAveragePower(PowerProfile.POWER_MODEM_CONTROLLER_RX);
                    int[] txTimeMs = deltaInfo.getTxTimeMillis();
                    for (int i = 0; i < Math.min(txTimeMs.length,
                            SignalStrength.NUM_SIGNAL_STRENGTH_BINS); i++) {
                        energyUsed += txTimeMs[i] * mPowerProfile.getAveragePower(
                                PowerProfile.POWER_MODEM_CONTROLLER_TX, i);
                    }

                    // We store the power drain as mAms.
                    mModemActivity.getPowerCounter().addCountLocked((long) energyUsed);
                    // Converting uWs to mAms.
                    // Conversion: (uWs * (1000ms / 1s) * (1mW / 1000uW)) / mV = mAms
                    long monitoredRailChargeConsumedMaMs =
                            (long) (mTmpRailStats.getCellularTotalEnergyUseduWs() / opVolt);
                    mModemActivity.getMonitoredRailChargeConsumedMaMs().addCountLocked(
                            monitoredRailChargeConsumedMaMs);
                    mHistoryCur.modemRailChargeMah +=
                            (monitoredRailChargeConsumedMaMs / MILLISECONDS_IN_HOUR);
                    addHistoryRecordLocked(mClocks.elapsedRealtime(), mClocks.uptimeMillis());
                    mTmpRailStats.resetCellularTotalEnergyUsed();
                }
            }
            final long elapsedRealtimeMs = mClocks.elapsedRealtime();
            long radioTime = mMobileRadioActivePerAppTimer.getTimeSinceMarkLocked(
                    elapsedRealtimeMs * 1000);
            mMobileRadioActivePerAppTimer.setMark(elapsedRealtimeMs);

            long totalRxPackets = 0;
            long totalTxPackets = 0;
            if (delta != null) {
                NetworkStats.Entry entry = new NetworkStats.Entry();
                final int size = delta.size();
                for (int i = 0; i < size; i++) {
                    entry = delta.getValues(i, entry);
                    if (entry.rxPackets == 0 && entry.txPackets == 0) {
                        continue;
                    }

                    if (DEBUG_ENERGY) {
                        Slog.d(TAG, "Mobile uid " + entry.uid + ": delta rx=" + entry.rxBytes
                                + " tx=" + entry.txBytes + " rxPackets=" + entry.rxPackets
                                + " txPackets=" + entry.txPackets);
                    }

                    totalRxPackets += entry.rxPackets;
                    totalTxPackets += entry.txPackets;

                    final Uid u = getUidStatsLocked(mapUid(entry.uid));
                    u.noteNetworkActivityLocked(NETWORK_MOBILE_RX_DATA, entry.rxBytes,
                            entry.rxPackets);
                    u.noteNetworkActivityLocked(NETWORK_MOBILE_TX_DATA, entry.txBytes,
                            entry.txPackets);
                    if (entry.set == NetworkStats.SET_DEFAULT) { // Background transfers
                        u.noteNetworkActivityLocked(NETWORK_MOBILE_BG_RX_DATA,
                                entry.rxBytes, entry.rxPackets);
                        u.noteNetworkActivityLocked(NETWORK_MOBILE_BG_TX_DATA,
                                entry.txBytes, entry.txPackets);
                    }

                    mNetworkByteActivityCounters[NETWORK_MOBILE_RX_DATA].addCountLocked(
                            entry.rxBytes);
                    mNetworkByteActivityCounters[NETWORK_MOBILE_TX_DATA].addCountLocked(
                            entry.txBytes);
                    mNetworkPacketActivityCounters[NETWORK_MOBILE_RX_DATA].addCountLocked(
                            entry.rxPackets);
                    mNetworkPacketActivityCounters[NETWORK_MOBILE_TX_DATA].addCountLocked(
                            entry.txPackets);
                }

                // Now distribute proportional blame to the apps that did networking.
                long totalPackets = totalRxPackets + totalTxPackets;
                if (totalPackets > 0) {
                    for (int i = 0; i < size; i++) {
                        entry = delta.getValues(i, entry);
                        if (entry.rxPackets == 0 && entry.txPackets == 0) {
                            continue;
                        }

                        final Uid u = getUidStatsLocked(mapUid(entry.uid));

                        // Distribute total radio active time in to this app.
                        final long appPackets = entry.rxPackets + entry.txPackets;
                        final long appRadioTime = (radioTime * appPackets) / totalPackets;
                        u.noteMobileRadioActiveTimeLocked(appRadioTime);

                        // Remove this app from the totals, so that we don't lose any time
                        // due to rounding.
                        radioTime -= appRadioTime;
                        totalPackets -= appPackets;

                        if (deltaInfo != null) {
                            ControllerActivityCounterImpl activityCounter =
                                    u.getOrCreateModemControllerActivityLocked();
                            if (totalRxPackets > 0 && entry.rxPackets > 0) {
                                final long rxMs = (entry.rxPackets * deltaInfo.getRxTimeMillis())
                                        / totalRxPackets;
                                activityCounter.getRxTimeCounter().addCountLocked(rxMs);
                            }

                            if (totalTxPackets > 0 && entry.txPackets > 0) {
                                for (int lvl = 0; lvl < ModemActivityInfo.TX_POWER_LEVELS; lvl++) {
                                    long txMs =
                                            entry.txPackets * deltaInfo.getTxTimeMillis()[lvl];
                                    txMs /= totalTxPackets;
                                    activityCounter.getTxTimeCounters()[lvl].addCountLocked(txMs);
                                }
                            }
                        }
                    }
                }

                if (radioTime > 0) {
                    // Whoops, there is some radio time we can't blame on an app!
                    mMobileRadioActiveUnknownTime.addCountLocked(radioTime);
                    mMobileRadioActiveUnknownCount.addCountLocked(1);
                }

                mNetworkStatsPool.release(delta);
                delta = null;
            }
        }
    }

    /**
     * Add modem tx power to history
     * Device is said to be in high cellular transmit power when it has spent most of the transmit
     * time at the highest power level.
     * @param activityInfo
     */
    private synchronized void addModemTxPowerToHistory(final ModemActivityInfo activityInfo) {
        if (activityInfo == null) {
            return;
        }
        int[] txTimeMs = activityInfo.getTxTimeMillis();
        if (txTimeMs == null || txTimeMs.length != ModemActivityInfo.TX_POWER_LEVELS) {
            return;
        }
        final long elapsedRealtime = mClocks.elapsedRealtime();
        final long uptime = mClocks.uptimeMillis();
        int levelMaxTimeSpent = 0;
        for (int i = 1; i < txTimeMs.length; i++) {
            if (txTimeMs[i] > txTimeMs[levelMaxTimeSpent]) {
                levelMaxTimeSpent = i;
            }
        }
        if (levelMaxTimeSpent == ModemActivityInfo.TX_POWER_LEVELS - 1) {
            mHistoryCur.states2 |= HistoryItem.STATE2_CELLULAR_HIGH_TX_POWER_FLAG;
            addHistoryRecordLocked(elapsedRealtime, uptime);
        }
    }

    private final class BluetoothActivityInfoCache {
        long idleTimeMs;
        long rxTimeMs;
        long txTimeMs;
        long energy;

        SparseLongArray uidRxBytes = new SparseLongArray();
        SparseLongArray uidTxBytes = new SparseLongArray();

        void set(BluetoothActivityEnergyInfo info) {
            idleTimeMs = info.getControllerIdleTimeMillis();
            rxTimeMs = info.getControllerRxTimeMillis();
            txTimeMs = info.getControllerTxTimeMillis();
            energy = info.getControllerEnergyUsed();
            if (info.getUidTraffic() != null) {
                for (UidTraffic traffic : info.getUidTraffic()) {
                    uidRxBytes.put(traffic.getUid(), traffic.getRxBytes());
                    uidTxBytes.put(traffic.getUid(), traffic.getTxBytes());
                }
            }
        }
    }

    private final BluetoothActivityInfoCache mLastBluetoothActivityInfo
            = new BluetoothActivityInfoCache();

    /**
     * Distribute Bluetooth energy info and network traffic to apps.
     *
     * @param info The energy information from the bluetooth controller.
     */
    public void updateBluetoothStateLocked(@Nullable final BluetoothActivityEnergyInfo info) {
        if (DEBUG_ENERGY) {
            Slog.d(TAG, "Updating bluetooth stats: " + info);
        }

        if (info == null || !mOnBatteryInternal) {
            return;
        }

        mHasBluetoothReporting = true;

        final long elapsedRealtimeMs = mClocks.elapsedRealtime();
        final long rxTimeMs =
                info.getControllerRxTimeMillis() - mLastBluetoothActivityInfo.rxTimeMs;
        final long txTimeMs =
                info.getControllerTxTimeMillis() - mLastBluetoothActivityInfo.txTimeMs;
        final long idleTimeMs =
                info.getControllerIdleTimeMillis() - mLastBluetoothActivityInfo.idleTimeMs;

        if (DEBUG_ENERGY) {
            Slog.d(TAG, "------ BEGIN BLE power blaming ------");
            Slog.d(TAG, "  Tx Time:    " + txTimeMs + " ms");
            Slog.d(TAG, "  Rx Time:    " + rxTimeMs + " ms");
            Slog.d(TAG, "  Idle Time:  " + idleTimeMs + " ms");
        }

        long totalScanTimeMs = 0;

        final int uidCount = mUidStats.size();
        for (int i = 0; i < uidCount; i++) {
            final Uid u = mUidStats.valueAt(i);
            if (u.mBluetoothScanTimer == null) {
                continue;
            }

            totalScanTimeMs += u.mBluetoothScanTimer.getTimeSinceMarkLocked(
                    elapsedRealtimeMs * 1000) / 1000;
        }

        final boolean normalizeScanRxTime = (totalScanTimeMs > rxTimeMs);
        final boolean normalizeScanTxTime = (totalScanTimeMs > txTimeMs);

        if (DEBUG_ENERGY) {
            Slog.d(TAG, "Normalizing scan power for RX=" + normalizeScanRxTime
                    + " TX=" + normalizeScanTxTime);
        }

        long leftOverRxTimeMs = rxTimeMs;
        long leftOverTxTimeMs = txTimeMs;

        for (int i = 0; i < uidCount; i++) {
            final Uid u = mUidStats.valueAt(i);
            if (u.mBluetoothScanTimer == null) {
                continue;
            }

            long scanTimeSinceMarkMs = u.mBluetoothScanTimer.getTimeSinceMarkLocked(
                    elapsedRealtimeMs * 1000) / 1000;
            if (scanTimeSinceMarkMs > 0) {
                // Set the new mark so that next time we get new data since this point.
                u.mBluetoothScanTimer.setMark(elapsedRealtimeMs);

                long scanTimeRxSinceMarkMs = scanTimeSinceMarkMs;
                long scanTimeTxSinceMarkMs = scanTimeSinceMarkMs;

                if (normalizeScanRxTime) {
                    // Scan time is longer than the total rx time in the controller,
                    // so distribute the scan time proportionately. This means regular traffic
                    // will not blamed, but scans are more expensive anyways.
                    scanTimeRxSinceMarkMs = (rxTimeMs * scanTimeRxSinceMarkMs) / totalScanTimeMs;
                }

                if (normalizeScanTxTime) {
                    // Scan time is longer than the total tx time in the controller,
                    // so distribute the scan time proportionately. This means regular traffic
                    // will not blamed, but scans are more expensive anyways.
                    scanTimeTxSinceMarkMs = (txTimeMs * scanTimeTxSinceMarkMs) / totalScanTimeMs;
                }

                final ControllerActivityCounterImpl counter =
                        u.getOrCreateBluetoothControllerActivityLocked();
                counter.getRxTimeCounter().addCountLocked(scanTimeRxSinceMarkMs);
                counter.getTxTimeCounters()[0].addCountLocked(scanTimeTxSinceMarkMs);

                leftOverRxTimeMs -= scanTimeRxSinceMarkMs;
                leftOverTxTimeMs -= scanTimeTxSinceMarkMs;
            }
        }

        if (DEBUG_ENERGY) {
            Slog.d(TAG, "Left over time for traffic RX=" + leftOverRxTimeMs + " TX="
                    + leftOverTxTimeMs);
        }

        //
        // Now distribute blame to apps that did bluetooth traffic.
        //

        long totalTxBytes = 0;
        long totalRxBytes = 0;

        final UidTraffic[] uidTraffic = info.getUidTraffic();
        final int numUids = uidTraffic != null ? uidTraffic.length : 0;
        for (int i = 0; i < numUids; i++) {
            final UidTraffic traffic = uidTraffic[i];
            final long rxBytes = traffic.getRxBytes() - mLastBluetoothActivityInfo.uidRxBytes.get(
                    traffic.getUid());
            final long txBytes = traffic.getTxBytes() - mLastBluetoothActivityInfo.uidTxBytes.get(
                    traffic.getUid());

            // Add to the global counters.
            mNetworkByteActivityCounters[NETWORK_BT_RX_DATA].addCountLocked(rxBytes);
            mNetworkByteActivityCounters[NETWORK_BT_TX_DATA].addCountLocked(txBytes);

            // Add to the UID counters.
            final Uid u = getUidStatsLocked(mapUid(traffic.getUid()));
            u.noteNetworkActivityLocked(NETWORK_BT_RX_DATA, rxBytes, 0);
            u.noteNetworkActivityLocked(NETWORK_BT_TX_DATA, txBytes, 0);

            // Calculate the total traffic.
            totalRxBytes += rxBytes;
            totalTxBytes += txBytes;
        }

        if ((totalTxBytes != 0 || totalRxBytes != 0) && (leftOverRxTimeMs != 0
                || leftOverTxTimeMs != 0)) {
            for (int i = 0; i < numUids; i++) {
                final UidTraffic traffic = uidTraffic[i];
                final int uid = traffic.getUid();
                final long rxBytes =
                        traffic.getRxBytes() - mLastBluetoothActivityInfo.uidRxBytes.get(uid);
                final long txBytes =
                        traffic.getTxBytes() - mLastBluetoothActivityInfo.uidTxBytes.get(uid);

                final Uid u = getUidStatsLocked(mapUid(uid));
                final ControllerActivityCounterImpl counter =
                        u.getOrCreateBluetoothControllerActivityLocked();

                if (totalRxBytes > 0 && rxBytes > 0) {
                    final long timeRxMs = (leftOverRxTimeMs * rxBytes) / totalRxBytes;
                    if (DEBUG_ENERGY) {
                        Slog.d(TAG, "UID=" + uid + " rx_bytes=" + rxBytes + " rx_time=" + timeRxMs);
                    }
                    counter.getRxTimeCounter().addCountLocked(timeRxMs);
                }

                if (totalTxBytes > 0 && txBytes > 0) {
                    final long timeTxMs = (leftOverTxTimeMs * txBytes) / totalTxBytes;
                    if (DEBUG_ENERGY) {
                        Slog.d(TAG, "UID=" + uid + " tx_bytes=" + txBytes + " tx_time=" + timeTxMs);
                    }
                    counter.getTxTimeCounters()[0].addCountLocked(timeTxMs);
                }
            }
        }

        mBluetoothActivity.getRxTimeCounter().addCountLocked(rxTimeMs);
        mBluetoothActivity.getTxTimeCounters()[0].addCountLocked(txTimeMs);
        mBluetoothActivity.getIdleTimeCounter().addCountLocked(idleTimeMs);

        // POWER_BLUETOOTH_CONTROLLER_OPERATING_VOLTAGE is measured in mV, so convert to V.
        final double opVolt = mPowerProfile.getAveragePower(
                PowerProfile.POWER_BLUETOOTH_CONTROLLER_OPERATING_VOLTAGE) / 1000.0;
        if (opVolt != 0) {
            // We store the power drain as mAms.
            mBluetoothActivity.getPowerCounter().addCountLocked(
                    (long) ((info.getControllerEnergyUsed() - mLastBluetoothActivityInfo.energy)
                            / opVolt));
        }
        mLastBluetoothActivityInfo.set(info);
    }

    /**
     * Read and record Resource Power Manager (RPM) state and voter times.
     * If RPM stats were fetched more recently than RPM_STATS_UPDATE_FREQ_MS ago, uses the old data
     * instead of fetching it anew.
     */
    public void updateRpmStatsLocked() {
        if (mPlatformIdleStateCallback == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - mLastRpmStatsUpdateTimeMs >= RPM_STATS_UPDATE_FREQ_MS) {
            mPlatformIdleStateCallback.fillLowPowerStats(mTmpRpmStats);
            mLastRpmStatsUpdateTimeMs = now;
        }

        for (Map.Entry<String, RpmStats.PowerStatePlatformSleepState> pstate
                : mTmpRpmStats.mPlatformLowPowerStats.entrySet()) {

            // Update values for this platform state.
            final String pName = pstate.getKey();
            final long pTimeUs = pstate.getValue().mTimeMs * 1000;
            final int pCount = pstate.getValue().mCount;
            getRpmTimerLocked(pName).update(pTimeUs, pCount);
            if (SCREEN_OFF_RPM_STATS_ENABLED) {
                getScreenOffRpmTimerLocked(pName).update(pTimeUs, pCount);
            }

            // Update values for each voter of this platform state.
            for (Map.Entry<String, RpmStats.PowerStateElement> voter
                    : pstate.getValue().mVoters.entrySet()) {
                final String vName = pName + "." + voter.getKey();
                final long vTimeUs = voter.getValue().mTimeMs * 1000;
                final int vCount = voter.getValue().mCount;
                getRpmTimerLocked(vName).update(vTimeUs, vCount);
                if (SCREEN_OFF_RPM_STATS_ENABLED) {
                    getScreenOffRpmTimerLocked(vName).update(vTimeUs, vCount);
                }
            }
        }

        for (Map.Entry<String, RpmStats.PowerStateSubsystem> subsys
                : mTmpRpmStats.mSubsystemLowPowerStats.entrySet()) {

            final String subsysName = subsys.getKey();
            for (Map.Entry<String, RpmStats.PowerStateElement> sstate
                    : subsys.getValue().mStates.entrySet()) {
                final String name = subsysName + "." + sstate.getKey();
                final long timeUs = sstate.getValue().mTimeMs * 1000;
                final int count = sstate.getValue().mCount;
                getRpmTimerLocked(name).update(timeUs, count);
                if (SCREEN_OFF_RPM_STATS_ENABLED) {
                    getScreenOffRpmTimerLocked(name).update(timeUs, count);
                }
            }
        }
    }

    /**
     * Read and record Rail Energy data.
     */
    public void updateRailStatsLocked() {
        if (mRailEnergyDataCallback == null || !mTmpRailStats.isRailStatsAvailable()) {
            return;
        }
        mRailEnergyDataCallback.fillRailDataStats(mTmpRailStats);
    }

    /**
     * Read and distribute kernel wake lock use across apps.
     */
    public void updateKernelWakelocksLocked() {
        final KernelWakelockStats wakelockStats = mKernelWakelockReader.readKernelWakelockStats(
                mTmpWakelockStats);
        if (wakelockStats == null) {
            // Not crashing might make board bringup easier.
            Slog.w(TAG, "Couldn't get kernel wake lock stats");
            return;
        }

        for (Map.Entry<String, KernelWakelockStats.Entry> ent : wakelockStats.entrySet()) {
            String name = ent.getKey();
            KernelWakelockStats.Entry kws = ent.getValue();

            SamplingTimer kwlt = mKernelWakelockStats.get(name);
            if (kwlt == null) {
                kwlt = new SamplingTimer(mClocks, mOnBatteryScreenOffTimeBase);
                mKernelWakelockStats.put(name, kwlt);
            }

            kwlt.update(kws.mTotalTime, kws.mCount);
            kwlt.setUpdateVersion(kws.mVersion);
        }

        int numWakelocksSetStale = 0;
        // Set timers to stale if they didn't appear in /d/wakeup_sources (or /proc/wakelocks)
        // this time.
        for (Map.Entry<String, SamplingTimer> ent : mKernelWakelockStats.entrySet()) {
            SamplingTimer st = ent.getValue();
            if (st.getUpdateVersion() != wakelockStats.kernelWakelockVersion) {
                st.endSample();
                numWakelocksSetStale++;
            }
        }

        // Record whether we've seen a non-zero time (for debugging b/22716723).
        if (wakelockStats.isEmpty()) {
            Slog.wtf(TAG, "All kernel wakelocks had time of zero");
        }

        if (numWakelocksSetStale == mKernelWakelockStats.size()) {
            Slog.wtf(TAG, "All kernel wakelocks were set stale. new version=" +
                    wakelockStats.kernelWakelockVersion);
        }
    }

    // We use an anonymous class to access these variables,
    // so they can't live on the stack or they'd have to be
    // final MutableLong objects (more allocations).
    // Used in updateCpuTimeLocked().
    long mTempTotalCpuUserTimeUs;
    long mTempTotalCpuSystemTimeUs;
    long[][] mWakeLockAllocationsUs;

    /**
     * Reads the newest memory stats from the kernel.
     */
    public void updateKernelMemoryBandwidthLocked() {
        mKernelMemoryBandwidthStats.updateStats();
        LongSparseLongArray bandwidthEntries = mKernelMemoryBandwidthStats.getBandwidthEntries();
        final int bandwidthEntryCount = bandwidthEntries.size();
        int index;
        for (int i = 0; i < bandwidthEntryCount; i++) {
            SamplingTimer timer;
            if ((index = mKernelMemoryStats.indexOfKey(bandwidthEntries.keyAt(i))) >= 0) {
                timer = mKernelMemoryStats.valueAt(index);
            } else {
                timer = new SamplingTimer(mClocks, mOnBatteryTimeBase);
                mKernelMemoryStats.put(bandwidthEntries.keyAt(i), timer);
            }
            timer.update(bandwidthEntries.valueAt(i), 1);
            if (DEBUG_MEMORY) {
                Slog.d(TAG, String.format("Added entry %d and updated timer to: "
                        + "mUnpluggedReportedTotalTime %d size %d", bandwidthEntries.keyAt(i),
                        mKernelMemoryStats.get(
                                bandwidthEntries.keyAt(i)).mUnpluggedReportedTotalTime,
                        mKernelMemoryStats.size()));
            }
        }
    }

    public boolean isOnBatteryLocked() {
        return mOnBatteryTimeBase.isRunning();
    }

    public boolean isOnBatteryScreenOffLocked() {
        return mOnBatteryScreenOffTimeBase.isRunning();
    }

    /**
     * Read and distribute CPU usage across apps. If their are partial wakelocks being held
     * and we are on battery with screen off, we give more of the cpu time to those apps holding
     * wakelocks. If the screen is on, we just assign the actual cpu time an app used.
     * It's possible this will be invoked after the internal battery/screen states are updated, so
     * passing the appropriate battery/screen states to try attribute the cpu times to correct
     * buckets.
     */
    @GuardedBy("this")
    public void updateCpuTimeLocked(boolean onBattery, boolean onBatteryScreenOff) {
        if (mPowerProfile == null) {
            return;
        }

        if (DEBUG_ENERGY_CPU) {
            Slog.d(TAG, "!Cpu updating!");
        }

        if (mCpuFreqs == null) {
            mCpuFreqs = mCpuUidFreqTimeReader.readFreqs(mPowerProfile);
        }

        // Calculate the wakelocks we have to distribute amongst. The system is excluded as it is
        // usually holding the wakelock on behalf of an app.
        // And Only distribute cpu power to wakelocks if the screen is off and we're on battery.
        ArrayList<StopwatchTimer> partialTimersToConsider = null;
        if (onBatteryScreenOff) {
            partialTimersToConsider = new ArrayList<>();
            for (int i = mPartialTimers.size() - 1; i >= 0; --i) {
                final StopwatchTimer timer = mPartialTimers.get(i);
                // Since the collection and blaming of wakelocks can be scheduled to run after
                // some delay, the mPartialTimers list may have new entries. We can't blame
                // the newly added timer for past cpu time, so we only consider timers that
                // were present for one round of collection. Once a timer has gone through
                // a round of collection, its mInList field is set to true.
                if (timer.mInList && timer.mUid != null && timer.mUid.mUid != Process.SYSTEM_UID) {
                    partialTimersToConsider.add(timer);
                }
            }
        }
        markPartialTimersAsEligible();

        // When the battery is not on, we don't attribute the cpu times to any timers but we still
        // need to take the snapshots.
        if (!onBattery) {
            mCpuUidUserSysTimeReader.readDelta(null);
            mCpuUidFreqTimeReader.readDelta(null);
            mNumAllUidCpuTimeReads += 2;
            if (mConstants.TRACK_CPU_ACTIVE_CLUSTER_TIME) {
                mCpuUidActiveTimeReader.readDelta(null);
                mCpuUidClusterTimeReader.readDelta(null);
                mNumAllUidCpuTimeReads += 2;
            }
            for (int cluster = mKernelCpuSpeedReaders.length - 1; cluster >= 0; --cluster) {
                mKernelCpuSpeedReaders[cluster].readDelta();
            }
            return;
        }

        mUserInfoProvider.refreshUserIds();
        final SparseLongArray updatedUids = mCpuUidFreqTimeReader.perClusterTimesAvailable()
                ? null : new SparseLongArray();
        readKernelUidCpuTimesLocked(partialTimersToConsider, updatedUids, onBattery);
        // updatedUids=null means /proc/uid_time_in_state provides snapshots of per-cluster cpu
        // freqs, so no need to approximate these values.
        if (updatedUids != null) {
            updateClusterSpeedTimes(updatedUids, onBattery);
        }
        readKernelUidCpuFreqTimesLocked(partialTimersToConsider, onBattery, onBatteryScreenOff);
        mNumAllUidCpuTimeReads += 2;
        if (mConstants.TRACK_CPU_ACTIVE_CLUSTER_TIME) {
            readKernelUidCpuActiveTimesLocked(onBattery);
            readKernelUidCpuClusterTimesLocked(onBattery);
            mNumAllUidCpuTimeReads += 2;
        }
    }

    /**
     * Mark the current partial timers as gone through a collection so that they will be
     * considered in the next cpu times distribution to wakelock holders.
     */
    @VisibleForTesting
    public void markPartialTimersAsEligible() {
        if (ArrayUtils.referenceEquals(mPartialTimers, mLastPartialTimers)) {
            // No difference, so each timer is now considered for the next collection.
            for (int i = mPartialTimers.size() - 1; i >= 0; --i) {
                mPartialTimers.get(i).mInList = true;
            }
        } else {
            // The lists are different, meaning we added (or removed a timer) since the last
            // collection.
            for (int i = mLastPartialTimers.size() - 1; i >= 0; --i) {
                mLastPartialTimers.get(i).mInList = false;
            }
            mLastPartialTimers.clear();

            // Mark the current timers as gone through a collection.
            final int numPartialTimers = mPartialTimers.size();
            for (int i = 0; i < numPartialTimers; ++i) {
                final StopwatchTimer timer = mPartialTimers.get(i);
                timer.mInList = true;
                mLastPartialTimers.add(timer);
            }
        }
    }

    /**
     * Take snapshot of cpu times (aggregated over all uids) at different frequencies and
     * calculate cpu times spent by each uid at different frequencies.
     *
     * @param updatedUids The uids for which times spent at different frequencies are calculated.
     */
    @VisibleForTesting
    public void updateClusterSpeedTimes(@NonNull SparseLongArray updatedUids, boolean onBattery) {
        long totalCpuClustersTimeMs = 0;
        // Read the time spent for each cluster at various cpu frequencies.
        final long[][] clusterSpeedTimesMs = new long[mKernelCpuSpeedReaders.length][];
        for (int cluster = 0; cluster < mKernelCpuSpeedReaders.length; cluster++) {
            clusterSpeedTimesMs[cluster] = mKernelCpuSpeedReaders[cluster].readDelta();
            if (clusterSpeedTimesMs[cluster] != null) {
                for (int speed = clusterSpeedTimesMs[cluster].length - 1; speed >= 0; --speed) {
                    totalCpuClustersTimeMs += clusterSpeedTimesMs[cluster][speed];
                }
            }
        }
        if (totalCpuClustersTimeMs != 0) {
            // We have cpu times per freq aggregated over all uids but we need the times per uid.
            // So, we distribute total time spent by an uid to different cpu freqs based on the
            // amount of time cpu was running at that freq.
            final int updatedUidsCount = updatedUids.size();
            for (int i = 0; i < updatedUidsCount; ++i) {
                final Uid u = getUidStatsLocked(updatedUids.keyAt(i));
                final long appCpuTimeUs = updatedUids.valueAt(i);
                // Add the cpu speeds to this UID.
                final int numClusters = mPowerProfile.getNumCpuClusters();
                if (u.mCpuClusterSpeedTimesUs == null ||
                        u.mCpuClusterSpeedTimesUs.length != numClusters) {
                    u.mCpuClusterSpeedTimesUs = new LongSamplingCounter[numClusters][];
                }

                for (int cluster = 0; cluster < clusterSpeedTimesMs.length; cluster++) {
                    final int speedsInCluster = clusterSpeedTimesMs[cluster].length;
                    if (u.mCpuClusterSpeedTimesUs[cluster] == null || speedsInCluster !=
                            u.mCpuClusterSpeedTimesUs[cluster].length) {
                        u.mCpuClusterSpeedTimesUs[cluster]
                                = new LongSamplingCounter[speedsInCluster];
                    }

                    final LongSamplingCounter[] cpuSpeeds = u.mCpuClusterSpeedTimesUs[cluster];
                    for (int speed = 0; speed < speedsInCluster; speed++) {
                        if (cpuSpeeds[speed] == null) {
                            cpuSpeeds[speed] = new LongSamplingCounter(mOnBatteryTimeBase);
                        }
                        cpuSpeeds[speed].addCountLocked(appCpuTimeUs
                                * clusterSpeedTimesMs[cluster][speed]
                                / totalCpuClustersTimeMs, onBattery);
                    }
                }
            }
        }
    }

    /**
     * Take a snapshot of the cpu times spent by each uid and update the corresponding counters.
     * If {@param partialTimers} is not null and empty, then we assign a portion of cpu times to
     * wakelock holders.
     *
     * @param partialTimers The wakelock holders among which the cpu times will be distributed.
     * @param updatedUids If not null, then the uids found in the snapshot will be added to this.
     */
    @VisibleForTesting
    public void readKernelUidCpuTimesLocked(@Nullable ArrayList<StopwatchTimer> partialTimers,
            @Nullable SparseLongArray updatedUids, boolean onBattery) {
        mTempTotalCpuUserTimeUs = mTempTotalCpuSystemTimeUs = 0;
        final int numWakelocks = partialTimers == null ? 0 : partialTimers.size();
        final long startTimeMs = mClocks.uptimeMillis();

        mCpuUidUserSysTimeReader.readDelta((uid, timesUs) -> {
            long userTimeUs = timesUs[0], systemTimeUs = timesUs[1];

            uid = mapUid(uid);
            if (Process.isIsolated(uid)) {
                // This could happen if the isolated uid mapping was removed before that process
                // was actually killed.
                mCpuUidUserSysTimeReader.removeUid(uid);
                Slog.d(TAG, "Got readings for an isolated uid with no mapping: " + uid);
                return;
            }
            if (!mUserInfoProvider.exists(UserHandle.getUserId(uid))) {
                Slog.d(TAG, "Got readings for an invalid user's uid " + uid);
                mCpuUidUserSysTimeReader.removeUid(uid);
                return;
            }
            final Uid u = getUidStatsLocked(uid);

            // Accumulate the total system and user time.
            mTempTotalCpuUserTimeUs += userTimeUs;
            mTempTotalCpuSystemTimeUs += systemTimeUs;

            StringBuilder sb = null;
            if (DEBUG_ENERGY_CPU) {
                sb = new StringBuilder();
                sb.append("  got time for uid=").append(u.mUid).append(": u=");
                TimeUtils.formatDuration(userTimeUs / 1000, sb);
                sb.append(" s=");
                TimeUtils.formatDuration(systemTimeUs / 1000, sb);
                sb.append("\n");
            }

            if (numWakelocks > 0) {
                // We have wakelocks being held, so only give a portion of the
                // time to the process. The rest will be distributed among wakelock
                // holders.
                userTimeUs = (userTimeUs * WAKE_LOCK_WEIGHT) / 100;
                systemTimeUs = (systemTimeUs * WAKE_LOCK_WEIGHT) / 100;
            }

            if (sb != null) {
                sb.append("  adding to uid=").append(u.mUid).append(": u=");
                TimeUtils.formatDuration(userTimeUs / 1000, sb);
                sb.append(" s=");
                TimeUtils.formatDuration(systemTimeUs / 1000, sb);
                Slog.d(TAG, sb.toString());
            }

            u.mUserCpuTime.addCountLocked(userTimeUs, onBattery);
            u.mSystemCpuTime.addCountLocked(systemTimeUs, onBattery);
            if (updatedUids != null) {
                updatedUids.put(u.getUid(), userTimeUs + systemTimeUs);
            }
        });

        final long elapsedTimeMs = mClocks.uptimeMillis() - startTimeMs;
        if (DEBUG_ENERGY_CPU || elapsedTimeMs >= 100) {
            Slog.d(TAG, "Reading cpu stats took " + elapsedTimeMs + "ms");
        }

        if (numWakelocks > 0) {
            // Distribute a portion of the total cpu time to wakelock holders.
            mTempTotalCpuUserTimeUs = (mTempTotalCpuUserTimeUs * (100 - WAKE_LOCK_WEIGHT)) / 100;
            mTempTotalCpuSystemTimeUs =
                    (mTempTotalCpuSystemTimeUs * (100 - WAKE_LOCK_WEIGHT)) / 100;

            for (int i = 0; i < numWakelocks; ++i) {
                final StopwatchTimer timer = partialTimers.get(i);
                final int userTimeUs = (int) (mTempTotalCpuUserTimeUs / (numWakelocks - i));
                final int systemTimeUs = (int) (mTempTotalCpuSystemTimeUs / (numWakelocks - i));

                if (DEBUG_ENERGY_CPU) {
                    final StringBuilder sb = new StringBuilder();
                    sb.append("  Distributing wakelock uid=").append(timer.mUid.mUid)
                            .append(": u=");
                    TimeUtils.formatDuration(userTimeUs / 1000, sb);
                    sb.append(" s=");
                    TimeUtils.formatDuration(systemTimeUs / 1000, sb);
                    Slog.d(TAG, sb.toString());
                }

                timer.mUid.mUserCpuTime.addCountLocked(userTimeUs, onBattery);
                timer.mUid.mSystemCpuTime.addCountLocked(systemTimeUs, onBattery);
                if (updatedUids != null) {
                    final int uid = timer.mUid.getUid();
                    updatedUids.put(uid, updatedUids.get(uid, 0) + userTimeUs + systemTimeUs);
                }

                final Uid.Proc proc = timer.mUid.getProcessStatsLocked("*wakelock*");
                proc.addCpuTimeLocked(userTimeUs / 1000, systemTimeUs / 1000, onBattery);

                mTempTotalCpuUserTimeUs -= userTimeUs;
                mTempTotalCpuSystemTimeUs -= systemTimeUs;
            }
        }
    }

    /**
     * Take a snapshot of the cpu times spent by each uid in each freq and update the
     * corresponding counters.
     *
     * @param partialTimers The wakelock holders among which the cpu freq times will be distributed.
     */
    @VisibleForTesting
    public void readKernelUidCpuFreqTimesLocked(@Nullable ArrayList<StopwatchTimer> partialTimers,
            boolean onBattery, boolean onBatteryScreenOff) {
        final boolean perClusterTimesAvailable =
                mCpuUidFreqTimeReader.perClusterTimesAvailable();
        final int numWakelocks = partialTimers == null ? 0 : partialTimers.size();
        final int numClusters = mPowerProfile.getNumCpuClusters();
        mWakeLockAllocationsUs = null;
        final long startTimeMs = mClocks.uptimeMillis();
        mCpuUidFreqTimeReader.readDelta((uid, cpuFreqTimeMs) -> {
            uid = mapUid(uid);
            if (Process.isIsolated(uid)) {
                mCpuUidFreqTimeReader.removeUid(uid);
                Slog.d(TAG, "Got freq readings for an isolated uid with no mapping: " + uid);
                return;
            }
            if (!mUserInfoProvider.exists(UserHandle.getUserId(uid))) {
                Slog.d(TAG, "Got freq readings for an invalid user's uid " + uid);
                mCpuUidFreqTimeReader.removeUid(uid);
                return;
            }
            final Uid u = getUidStatsLocked(uid);
            if (u.mCpuFreqTimeMs == null || u.mCpuFreqTimeMs.getSize() != cpuFreqTimeMs.length) {
                detachIfNotNull(u.mCpuFreqTimeMs);
                u.mCpuFreqTimeMs = new LongSamplingCounterArray(mOnBatteryTimeBase);
            }
            u.mCpuFreqTimeMs.addCountLocked(cpuFreqTimeMs, onBattery);
            if (u.mScreenOffCpuFreqTimeMs == null ||
                    u.mScreenOffCpuFreqTimeMs.getSize() != cpuFreqTimeMs.length) {
                detachIfNotNull(u.mScreenOffCpuFreqTimeMs);
                u.mScreenOffCpuFreqTimeMs = new LongSamplingCounterArray(
                        mOnBatteryScreenOffTimeBase);
            }
            u.mScreenOffCpuFreqTimeMs.addCountLocked(cpuFreqTimeMs, onBatteryScreenOff);

            if (perClusterTimesAvailable) {
                if (u.mCpuClusterSpeedTimesUs == null ||
                        u.mCpuClusterSpeedTimesUs.length != numClusters) {
                    detachIfNotNull(u.mCpuClusterSpeedTimesUs);
                    u.mCpuClusterSpeedTimesUs = new LongSamplingCounter[numClusters][];
                }
                if (numWakelocks > 0 && mWakeLockAllocationsUs == null) {
                    mWakeLockAllocationsUs = new long[numClusters][];
                }

                int freqIndex = 0;
                for (int cluster = 0; cluster < numClusters; ++cluster) {
                    final int speedsInCluster = mPowerProfile.getNumSpeedStepsInCpuCluster(cluster);
                    if (u.mCpuClusterSpeedTimesUs[cluster] == null ||
                            u.mCpuClusterSpeedTimesUs[cluster].length != speedsInCluster) {
                        detachIfNotNull(u.mCpuClusterSpeedTimesUs[cluster]);
                        u.mCpuClusterSpeedTimesUs[cluster]
                                = new LongSamplingCounter[speedsInCluster];
                    }
                    if (numWakelocks > 0 && mWakeLockAllocationsUs[cluster] == null) {
                        mWakeLockAllocationsUs[cluster] = new long[speedsInCluster];
                    }
                    final LongSamplingCounter[] cpuTimesUs = u.mCpuClusterSpeedTimesUs[cluster];
                    for (int speed = 0; speed < speedsInCluster; ++speed) {
                        if (cpuTimesUs[speed] == null) {
                            cpuTimesUs[speed] = new LongSamplingCounter(mOnBatteryTimeBase);
                        }
                        final long appAllocationUs;
                        if (mWakeLockAllocationsUs != null) {
                            appAllocationUs =
                                    (cpuFreqTimeMs[freqIndex] * 1000 * WAKE_LOCK_WEIGHT) / 100;
                            mWakeLockAllocationsUs[cluster][speed] +=
                                    (cpuFreqTimeMs[freqIndex] * 1000 - appAllocationUs);
                        } else {
                            appAllocationUs = cpuFreqTimeMs[freqIndex] * 1000;
                        }
                        cpuTimesUs[speed].addCountLocked(appAllocationUs, onBattery);
                        freqIndex++;
                    }
                }
            }
        });

        final long elapsedTimeMs = mClocks.uptimeMillis() - startTimeMs;
        if (DEBUG_ENERGY_CPU || elapsedTimeMs >= 100) {
            Slog.d(TAG, "Reading cpu freq times took " + elapsedTimeMs + "ms");
        }

        if (mWakeLockAllocationsUs != null) {
            for (int i = 0; i < numWakelocks; ++i) {
                final Uid u = partialTimers.get(i).mUid;
                if (u.mCpuClusterSpeedTimesUs == null ||
                        u.mCpuClusterSpeedTimesUs.length != numClusters) {
                    detachIfNotNull(u.mCpuClusterSpeedTimesUs);
                    u.mCpuClusterSpeedTimesUs = new LongSamplingCounter[numClusters][];
                }

                for (int cluster = 0; cluster < numClusters; ++cluster) {
                    final int speedsInCluster = mPowerProfile.getNumSpeedStepsInCpuCluster(cluster);
                    if (u.mCpuClusterSpeedTimesUs[cluster] == null ||
                            u.mCpuClusterSpeedTimesUs[cluster].length != speedsInCluster) {
                        detachIfNotNull(u.mCpuClusterSpeedTimesUs[cluster]);
                        u.mCpuClusterSpeedTimesUs[cluster]
                                = new LongSamplingCounter[speedsInCluster];
                    }
                    final LongSamplingCounter[] cpuTimeUs = u.mCpuClusterSpeedTimesUs[cluster];
                    for (int speed = 0; speed < speedsInCluster; ++speed) {
                        if (cpuTimeUs[speed] == null) {
                            cpuTimeUs[speed] = new LongSamplingCounter(mOnBatteryTimeBase);
                        }
                        final long allocationUs =
                                mWakeLockAllocationsUs[cluster][speed] / (numWakelocks - i);
                        cpuTimeUs[speed].addCountLocked(allocationUs, onBattery);
                        mWakeLockAllocationsUs[cluster][speed] -= allocationUs;
                    }
                }
            }
        }
    }

    /**
     * Take a snapshot of the cpu active times spent by each uid and update the corresponding
     * counters.
     */
    @VisibleForTesting
    public void readKernelUidCpuActiveTimesLocked(boolean onBattery) {
        final long startTimeMs = mClocks.uptimeMillis();
        mCpuUidActiveTimeReader.readDelta((uid, cpuActiveTimesMs) -> {
            uid = mapUid(uid);
            if (Process.isIsolated(uid)) {
                mCpuUidActiveTimeReader.removeUid(uid);
                Slog.w(TAG, "Got active times for an isolated uid with no mapping: " + uid);
                return;
            }
            if (!mUserInfoProvider.exists(UserHandle.getUserId(uid))) {
                Slog.w(TAG, "Got active times for an invalid user's uid " + uid);
                mCpuUidActiveTimeReader.removeUid(uid);
                return;
            }
            final Uid u = getUidStatsLocked(uid);
            u.mCpuActiveTimeMs.addCountLocked(cpuActiveTimesMs, onBattery);
        });

        final long elapsedTimeMs = mClocks.uptimeMillis() - startTimeMs;
        if (DEBUG_ENERGY_CPU || elapsedTimeMs >= 100) {
            Slog.d(TAG, "Reading cpu active times took " + elapsedTimeMs + "ms");
        }
    }

    /**
     * Take a snapshot of the cpu cluster times spent by each uid and update the corresponding
     * counters.
     */
    @VisibleForTesting
    public void readKernelUidCpuClusterTimesLocked(boolean onBattery) {
        final long startTimeMs = mClocks.uptimeMillis();
        mCpuUidClusterTimeReader.readDelta((uid, cpuClusterTimesMs) -> {
            uid = mapUid(uid);
            if (Process.isIsolated(uid)) {
                mCpuUidClusterTimeReader.removeUid(uid);
                Slog.w(TAG, "Got cluster times for an isolated uid with no mapping: " + uid);
                return;
            }
            if (!mUserInfoProvider.exists(UserHandle.getUserId(uid))) {
                Slog.w(TAG, "Got cluster times for an invalid user's uid " + uid);
                mCpuUidClusterTimeReader.removeUid(uid);
                return;
            }
            final Uid u = getUidStatsLocked(uid);
            u.mCpuClusterTimesMs.addCountLocked(cpuClusterTimesMs, onBattery);
        });

        final long elapsedTimeMs = mClocks.uptimeMillis() - startTimeMs;
        if (DEBUG_ENERGY_CPU || elapsedTimeMs >= 100) {
            Slog.d(TAG, "Reading cpu cluster times took " + elapsedTimeMs + "ms");
        }
    }

    boolean setChargingLocked(boolean charging) {
        // if the device is no longer charging, remove the callback
        // if the device is now charging, it means that this is either called
        // 1. directly when level >= 90
        // 2. or from within the runnable that we deferred
        // For 1. if we have an existing callback, remove it, since we will immediately send a
        // ACTION_CHARGING
        // For 2. we remove existing callback so we don't send multiple ACTION_CHARGING
        mHandler.removeCallbacks(mDeferSetCharging);
        if (mCharging != charging) {
            mCharging = charging;
            if (charging) {
                mHistoryCur.states2 |= HistoryItem.STATE2_CHARGING_FLAG;
            } else {
                mHistoryCur.states2 &= ~HistoryItem.STATE2_CHARGING_FLAG;
            }
            mHandler.sendEmptyMessage(MSG_REPORT_CHARGING);
            return true;
        }
        return false;
    }

    @GuardedBy("this")
    protected void setOnBatteryLocked(final long mSecRealtime, final long mSecUptime,
            final boolean onBattery, final int oldStatus, final int level, final int chargeUAh) {
        boolean doWrite = false;
        Message m = mHandler.obtainMessage(MSG_REPORT_POWER_CHANGE);
        m.arg1 = onBattery ? 1 : 0;
        mHandler.sendMessage(m);

        final long uptime = mSecUptime * 1000;
        final long realtime = mSecRealtime * 1000;
        final int screenState = mScreenState;
        if (onBattery) {
            // We will reset our status if we are unplugging after the
            // battery was last full, or the level is at 100, or
            // we have gone through a significant charge (from a very low
            // level to a now very high level).
            boolean reset = false;
            if (!mNoAutoReset && (oldStatus == BatteryManager.BATTERY_STATUS_FULL
                    || level >= 90
                    || (mDischargeCurrentLevel < 20 && level >= 80))) {
                Slog.i(TAG, "Resetting battery stats: level=" + level + " status=" + oldStatus
                        + " dischargeLevel=" + mDischargeCurrentLevel
                        + " lowAmount=" + getLowDischargeAmountSinceCharge()
                        + " highAmount=" + getHighDischargeAmountSinceCharge());
                // Before we write, collect a snapshot of the final aggregated
                // stats to be reported in the next checkin.  Only do this if we have
                // a sufficient amount of data to make it interesting.
                if (getLowDischargeAmountSinceCharge() >= 20) {
                    final long startTime = SystemClock.uptimeMillis();
                    final Parcel parcel = Parcel.obtain();
                    writeSummaryToParcel(parcel, true);
                    final long initialTime = SystemClock.uptimeMillis() - startTime;
                    BackgroundThread.getHandler().post(new Runnable() {
                        @Override public void run() {
                            synchronized (mCheckinFile) {
                                final long startTime2 = SystemClock.uptimeMillis();
                                FileOutputStream stream = null;
                                try {
                                    stream = mCheckinFile.startWrite();
                                    stream.write(parcel.marshall());
                                    stream.flush();
                                    mCheckinFile.finishWrite(stream);
                                    com.android.internal.logging.EventLogTags.writeCommitSysConfigFile(
                                            "batterystats-checkin",
                                            initialTime + SystemClock.uptimeMillis() - startTime2);
                                } catch (IOException e) {
                                    Slog.w("BatteryStats",
                                            "Error writing checkin battery statistics", e);
                                    mCheckinFile.failWrite(stream);
                                } finally {
                                    parcel.recycle();
                                }
                            }
                        }
                    });
                }
                doWrite = true;
                resetAllStatsLocked();
                if (chargeUAh > 0 && level > 0) {
                    // Only use the reported coulomb charge value if it is supported and reported.
                    mEstimatedBatteryCapacity = (int) ((chargeUAh / 1000) / (level / 100.0));
                }
                mDischargeStartLevel = level;
                reset = true;
                mDischargeStepTracker.init();
            }
            if (mCharging) {
                setChargingLocked(false);
            }
            mLastChargingStateLevel = level;
            mOnBattery = mOnBatteryInternal = true;
            mLastDischargeStepLevel = level;
            mMinDischargeStepLevel = level;
            mDischargeStepTracker.clearTime();
            mDailyDischargeStepTracker.clearTime();
            mInitStepMode = mCurStepMode;
            mModStepMode = 0;
            pullPendingStateUpdatesLocked();
            mHistoryCur.batteryLevel = (byte)level;
            mHistoryCur.states &= ~HistoryItem.STATE_BATTERY_PLUGGED_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Battery unplugged to: "
                    + Integer.toHexString(mHistoryCur.states));
            if (reset) {
                mRecordingHistory = true;
                startRecordingHistory(mSecRealtime, mSecUptime, reset);
            }
            addHistoryRecordLocked(mSecRealtime, mSecUptime);
            mDischargeCurrentLevel = mDischargeUnplugLevel = level;
            if (isScreenOn(screenState)) {
                mDischargeScreenOnUnplugLevel = level;
                mDischargeScreenDozeUnplugLevel = 0;
                mDischargeScreenOffUnplugLevel = 0;
            } else if (isScreenDoze(screenState)) {
                mDischargeScreenOnUnplugLevel = 0;
                mDischargeScreenDozeUnplugLevel = level;
                mDischargeScreenOffUnplugLevel = 0;
            } else {
                mDischargeScreenOnUnplugLevel = 0;
                mDischargeScreenDozeUnplugLevel = 0;
                mDischargeScreenOffUnplugLevel = level;
            }
            mDischargeAmountScreenOn = 0;
            mDischargeAmountScreenDoze = 0;
            mDischargeAmountScreenOff = 0;
            updateTimeBasesLocked(true, screenState, uptime, realtime);
        } else {
            mLastChargingStateLevel = level;
            mOnBattery = mOnBatteryInternal = false;
            pullPendingStateUpdatesLocked();
            mHistoryCur.batteryLevel = (byte)level;
            mHistoryCur.states |= HistoryItem.STATE_BATTERY_PLUGGED_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Battery plugged to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(mSecRealtime, mSecUptime);
            mDischargeCurrentLevel = mDischargePlugLevel = level;
            if (level < mDischargeUnplugLevel) {
                mLowDischargeAmountSinceCharge += mDischargeUnplugLevel-level-1;
                mHighDischargeAmountSinceCharge += mDischargeUnplugLevel-level;
            }
            updateDischargeScreenLevelsLocked(screenState, screenState);
            updateTimeBasesLocked(false, screenState, uptime, realtime);
            mChargeStepTracker.init();
            mLastChargeStepLevel = level;
            mMaxChargeStepLevel = level;
            mInitStepMode = mCurStepMode;
            mModStepMode = 0;
        }
        if (doWrite || (mLastWriteTime + (60 * 1000)) < mSecRealtime) {
            if (mStatsFile != null && mBatteryStatsHistory.getActiveFile() != null) {
                writeAsyncLocked();
            }
        }
    }

    private void startRecordingHistory(final long elapsedRealtimeMs, final long uptimeMs,
            boolean reset) {
        mRecordingHistory = true;
        mHistoryCur.currentTime = System.currentTimeMillis();
        addHistoryBufferLocked(elapsedRealtimeMs,
                reset ? HistoryItem.CMD_RESET : HistoryItem.CMD_CURRENT_TIME,
                mHistoryCur);
        mHistoryCur.currentTime = 0;
        if (reset) {
            initActiveHistoryEventsLocked(elapsedRealtimeMs, uptimeMs);
        }
    }

    private void recordCurrentTimeChangeLocked(final long currentTime, final long elapsedRealtimeMs,
            final long uptimeMs) {
        if (mRecordingHistory) {
            mHistoryCur.currentTime = currentTime;
            addHistoryBufferLocked(elapsedRealtimeMs, HistoryItem.CMD_CURRENT_TIME, mHistoryCur);
            mHistoryCur.currentTime = 0;
        }
    }

    private void recordShutdownLocked(final long elapsedRealtimeMs, final long uptimeMs) {
        if (mRecordingHistory) {
            mHistoryCur.currentTime = System.currentTimeMillis();
            addHistoryBufferLocked(elapsedRealtimeMs, HistoryItem.CMD_SHUTDOWN, mHistoryCur);
            mHistoryCur.currentTime = 0;
        }
    }

    private void scheduleSyncExternalStatsLocked(String reason, int updateFlags) {
        if (mExternalSync != null) {
            mExternalSync.scheduleSync(reason, updateFlags);
        }
    }

    // This should probably be exposed in the API, though it's not critical
    public static final int BATTERY_PLUGGED_NONE = OsProtoEnums.BATTERY_PLUGGED_NONE; // = 0

    @GuardedBy("this")
    public void setBatteryStateLocked(final int status, final int health, final int plugType,
            final int level, /* not final */ int temp, final int volt, final int chargeUAh,
            final int chargeFullUAh) {
        // Temperature is encoded without the signed bit, so clamp any negative temperatures to 0.
        temp = Math.max(0, temp);

        reportChangesToStatsLog(mHaveBatteryLevel ? mHistoryCur : null,
                status, plugType, level);

        final boolean onBattery = isOnBattery(plugType, status);
        final long uptime = mClocks.uptimeMillis();
        final long elapsedRealtime = mClocks.elapsedRealtime();
        if (!mHaveBatteryLevel) {
            mHaveBatteryLevel = true;
            // We start out assuming that the device is plugged in (not
            // on battery).  If our first report is now that we are indeed
            // plugged in, then twiddle our state to correctly reflect that
            // since we won't be going through the full setOnBattery().
            if (onBattery == mOnBattery) {
                if (onBattery) {
                    mHistoryCur.states &= ~HistoryItem.STATE_BATTERY_PLUGGED_FLAG;
                } else {
                    mHistoryCur.states |= HistoryItem.STATE_BATTERY_PLUGGED_FLAG;
                }
            }
            // Always start out assuming charging, that will be updated later.
            mHistoryCur.states2 |= HistoryItem.STATE2_CHARGING_FLAG;
            mHistoryCur.batteryStatus = (byte)status;
            mHistoryCur.batteryLevel = (byte)level;
            mHistoryCur.batteryChargeUAh = chargeUAh;
            mMaxChargeStepLevel = mMinDischargeStepLevel =
                    mLastChargeStepLevel = mLastDischargeStepLevel = level;
            mLastChargingStateLevel = level;
        } else if (mCurrentBatteryLevel != level || mOnBattery != onBattery) {
            recordDailyStatsIfNeededLocked(level >= 100 && onBattery);
        }
        int oldStatus = mHistoryCur.batteryStatus;
        if (onBattery) {
            mDischargeCurrentLevel = level;
            if (!mRecordingHistory) {
                mRecordingHistory = true;
                startRecordingHistory(elapsedRealtime, uptime, true);
            }
        } else if (level < 96 &&
                status != BatteryManager.BATTERY_STATUS_UNKNOWN) {
            if (!mRecordingHistory) {
                mRecordingHistory = true;
                startRecordingHistory(elapsedRealtime, uptime, true);
            }
        }
        mCurrentBatteryLevel = level;
        if (mDischargePlugLevel < 0) {
            mDischargePlugLevel = level;
        }

        if (onBattery != mOnBattery) {
            mHistoryCur.batteryLevel = (byte)level;
            mHistoryCur.batteryStatus = (byte)status;
            mHistoryCur.batteryHealth = (byte)health;
            mHistoryCur.batteryPlugType = (byte)plugType;
            mHistoryCur.batteryTemperature = (short)temp;
            mHistoryCur.batteryVoltage = (char)volt;
            if (chargeUAh < mHistoryCur.batteryChargeUAh) {
                // Only record discharges
                final long chargeDiff = mHistoryCur.batteryChargeUAh - chargeUAh;
                mDischargeCounter.addCountLocked(chargeDiff);
                mDischargeScreenOffCounter.addCountLocked(chargeDiff);
                if (isScreenDoze(mScreenState)) {
                    mDischargeScreenDozeCounter.addCountLocked(chargeDiff);
                }
                if (mDeviceIdleMode == DEVICE_IDLE_MODE_LIGHT) {
                    mDischargeLightDozeCounter.addCountLocked(chargeDiff);
                } else if (mDeviceIdleMode == DEVICE_IDLE_MODE_DEEP) {
                    mDischargeDeepDozeCounter.addCountLocked(chargeDiff);
                }
            }
            mHistoryCur.batteryChargeUAh = chargeUAh;
            setOnBatteryLocked(elapsedRealtime, uptime, onBattery, oldStatus, level, chargeUAh);
        } else {
            boolean changed = false;
            if (mHistoryCur.batteryLevel != level) {
                mHistoryCur.batteryLevel = (byte)level;
                changed = true;

                // TODO(adamlesinski): Schedule the creation of a HistoryStepDetails record
                // which will pull external stats.
                mExternalSync.scheduleSyncDueToBatteryLevelChange(
                        mConstants.BATTERY_LEVEL_COLLECTION_DELAY_MS);
            }
            if (mHistoryCur.batteryStatus != status) {
                mHistoryCur.batteryStatus = (byte)status;
                changed = true;
            }
            if (mHistoryCur.batteryHealth != health) {
                mHistoryCur.batteryHealth = (byte)health;
                changed = true;
            }
            if (mHistoryCur.batteryPlugType != plugType) {
                mHistoryCur.batteryPlugType = (byte)plugType;
                changed = true;
            }
            if (temp >= (mHistoryCur.batteryTemperature+10)
                    || temp <= (mHistoryCur.batteryTemperature-10)) {
                mHistoryCur.batteryTemperature = (short)temp;
                changed = true;
            }
            if (volt > (mHistoryCur.batteryVoltage+20)
                    || volt < (mHistoryCur.batteryVoltage-20)) {
                mHistoryCur.batteryVoltage = (char)volt;
                changed = true;
            }
            if (chargeUAh >= (mHistoryCur.batteryChargeUAh+10)
                    || chargeUAh <= (mHistoryCur.batteryChargeUAh-10)) {
                if (chargeUAh < mHistoryCur.batteryChargeUAh) {
                    // Only record discharges
                    final long chargeDiff = mHistoryCur.batteryChargeUAh - chargeUAh;
                    mDischargeCounter.addCountLocked(chargeDiff);
                    mDischargeScreenOffCounter.addCountLocked(chargeDiff);
                    if (isScreenDoze(mScreenState)) {
                        mDischargeScreenDozeCounter.addCountLocked(chargeDiff);
                    }
                    if (mDeviceIdleMode == DEVICE_IDLE_MODE_LIGHT) {
                        mDischargeLightDozeCounter.addCountLocked(chargeDiff);
                    } else if (mDeviceIdleMode == DEVICE_IDLE_MODE_DEEP) {
                        mDischargeDeepDozeCounter.addCountLocked(chargeDiff);
                    }
                }
                mHistoryCur.batteryChargeUAh = chargeUAh;
                changed = true;
            }
            long modeBits = (((long)mInitStepMode) << STEP_LEVEL_INITIAL_MODE_SHIFT)
                    | (((long)mModStepMode) << STEP_LEVEL_MODIFIED_MODE_SHIFT)
                    | (((long)(level&0xff)) << STEP_LEVEL_LEVEL_SHIFT);
            if (onBattery) {
                changed |= setChargingLocked(false);
                if (mLastDischargeStepLevel != level && mMinDischargeStepLevel > level) {
                    mDischargeStepTracker.addLevelSteps(mLastDischargeStepLevel - level,
                            modeBits, elapsedRealtime);
                    mDailyDischargeStepTracker.addLevelSteps(mLastDischargeStepLevel - level,
                            modeBits, elapsedRealtime);
                    mLastDischargeStepLevel = level;
                    mMinDischargeStepLevel = level;
                    mInitStepMode = mCurStepMode;
                    mModStepMode = 0;
                }
            } else {
                if (level >= 90) {
                    // If the battery level is at least 90%, always consider the device to be
                    // charging even if it happens to go down a level.
                    changed |= setChargingLocked(true);
                } else if (!mCharging) {
                    if (mLastChargeStepLevel < level) {
                        // We have not reported that we are charging, but the level has gone up,
                        // but we would like to not have tons of activity from charging-constraint
                        // jobs, so instead of reporting ACTION_CHARGING immediately, we defer it.
                        if (!mHandler.hasCallbacks(mDeferSetCharging)) {
                            mHandler.postDelayed(
                                    mDeferSetCharging,
                                    mConstants.BATTERY_CHARGED_DELAY_MS);
                        }
                    } else if (mLastChargeStepLevel > level) {
                        // if we had deferred a runnable due to charge level increasing, but then
                        // later the charge level drops (could be due to thermal issues), we don't
                        // want to trigger the deferred runnable, so remove it here
                        mHandler.removeCallbacks(mDeferSetCharging);
                    }
                } else {
                    if (mLastChargeStepLevel > level) {
                        // We had reported that the device was charging, but here we are with
                        // power connected and the level going down.  Looks like the current
                        // power supplied isn't enough, so consider the device to now be
                        // discharging.
                        changed |= setChargingLocked(false);
                    }
                }
                if (mLastChargeStepLevel != level && mMaxChargeStepLevel < level) {
                    mChargeStepTracker.addLevelSteps(level - mLastChargeStepLevel,
                            modeBits, elapsedRealtime);
                    mDailyChargeStepTracker.addLevelSteps(level - mLastChargeStepLevel,
                            modeBits, elapsedRealtime);
                    mMaxChargeStepLevel = level;
                    mInitStepMode = mCurStepMode;
                    mModStepMode = 0;
                }
                mLastChargeStepLevel = level;
            }
            if (changed) {
                addHistoryRecordLocked(elapsedRealtime, uptime);
            }
        }
        if (!onBattery &&
                (status == BatteryManager.BATTERY_STATUS_FULL ||
                        status == BatteryManager.BATTERY_STATUS_UNKNOWN)) {
            // We don't record history while we are plugged in and fully charged
            // (or when battery is not present).  The next time we are
            // unplugged, history will be cleared.
            mRecordingHistory = DEBUG;
        }

        if (mMinLearnedBatteryCapacity == -1) {
            mMinLearnedBatteryCapacity = chargeFullUAh;
        } else {
            mMinLearnedBatteryCapacity = Math.min(mMinLearnedBatteryCapacity, chargeFullUAh);
        }
        mMaxLearnedBatteryCapacity = Math.max(mMaxLearnedBatteryCapacity, chargeFullUAh);
    }

    public static boolean isOnBattery(int plugType, int status) {
        return plugType == BATTERY_PLUGGED_NONE && status != BatteryManager.BATTERY_STATUS_UNKNOWN;
    }

    // Inform StatsLog of setBatteryState changes.
    // If this is the first reporting, pass in recentPast == null.
    private void reportChangesToStatsLog(HistoryItem recentPast,
            final int status, final int plugType, final int level) {

        if (recentPast == null || recentPast.batteryStatus != status) {
            StatsLog.write(StatsLog.CHARGING_STATE_CHANGED, status);
        }
        if (recentPast == null || recentPast.batteryPlugType != plugType) {
            StatsLog.write(StatsLog.PLUGGED_STATE_CHANGED, plugType);
        }
        if (recentPast == null || recentPast.batteryLevel != level) {
            StatsLog.write(StatsLog.BATTERY_LEVEL_CHANGED, level);
        }
    }

    @UnsupportedAppUsage
    public long getAwakeTimeBattery() {
        // This previously evaluated to mOnBatteryTimeBase.getUptime(getBatteryUptimeLocked());
        // for over a decade, but surely that was a mistake.
        return getBatteryUptimeLocked();
    }

    @UnsupportedAppUsage
    public long getAwakeTimePlugged() {
        return (mClocks.uptimeMillis() * 1000) - getAwakeTimeBattery();
    }

    @Override
    public long computeUptime(long curTime, int which) {
        return mUptime + (curTime - mUptimeStart);
    }

    @Override
    public long computeRealtime(long curTime, int which) {
        return mRealtime + (curTime - mRealtimeStart);
    }

    @Override
    @UnsupportedAppUsage
    public long computeBatteryUptime(long curTime, int which) {
        return mOnBatteryTimeBase.computeUptime(curTime, which);
    }

    @Override
    @UnsupportedAppUsage
    public long computeBatteryRealtime(long curTime, int which) {
        return mOnBatteryTimeBase.computeRealtime(curTime, which);
    }

    @Override
    public long computeBatteryScreenOffUptime(long curTime, int which) {
        return mOnBatteryScreenOffTimeBase.computeUptime(curTime, which);
    }

    @Override
    public long computeBatteryScreenOffRealtime(long curTime, int which) {
        return mOnBatteryScreenOffTimeBase.computeRealtime(curTime, which);
    }

    private long computeTimePerLevel(long[] steps, int numSteps) {
        // For now we'll do a simple average across all steps.
        if (numSteps <= 0) {
            return -1;
        }
        long total = 0;
        for (int i=0; i<numSteps; i++) {
            total += steps[i] & STEP_LEVEL_TIME_MASK;
        }
        return total / numSteps;
        /*
        long[] buckets = new long[numSteps];
        int numBuckets = 0;
        int numToAverage = 4;
        int i = 0;
        while (i < numSteps) {
            long totalTime = 0;
            int num = 0;
            for (int j=0; j<numToAverage && (i+j)<numSteps; j++) {
                totalTime += steps[i+j] & STEP_LEVEL_TIME_MASK;
                num++;
            }
            buckets[numBuckets] = totalTime / num;
            numBuckets++;
            numToAverage *= 2;
            i += num;
        }
        if (numBuckets < 1) {
            return -1;
        }
        long averageTime = buckets[numBuckets-1];
        for (i=numBuckets-2; i>=0; i--) {
            averageTime = (averageTime + buckets[i]) / 2;
        }
        return averageTime;
        */
    }

    @Override
    @UnsupportedAppUsage
    public long computeBatteryTimeRemaining(long curTime) {
        if (!mOnBattery) {
            return -1;
        }
        /* Simple implementation just looks at the average discharge per level across the
           entire sample period.
        int discharge = (getLowDischargeAmountSinceCharge()+getHighDischargeAmountSinceCharge())/2;
        if (discharge < 2) {
            return -1;
        }
        long duration = computeBatteryRealtime(curTime, STATS_SINCE_CHARGED);
        if (duration < 1000*1000) {
            return -1;
        }
        long usPerLevel = duration/discharge;
        return usPerLevel * mCurrentBatteryLevel;
        */
        if (mDischargeStepTracker.mNumStepDurations < 1) {
            return -1;
        }
        long msPerLevel = mDischargeStepTracker.computeTimePerLevel();
        if (msPerLevel <= 0) {
            return -1;
        }
        return (msPerLevel * mCurrentBatteryLevel) * 1000;
    }

    @Override
    public LevelStepTracker getDischargeLevelStepTracker() {
        return mDischargeStepTracker;
    }

    @Override
    public LevelStepTracker getDailyDischargeLevelStepTracker() {
        return mDailyDischargeStepTracker;
    }

    @Override
    public long computeChargeTimeRemaining(long curTime) {
        if (mOnBattery) {
            // Not yet working.
            return -1;
        }
        /* Broken
        int curLevel = mCurrentBatteryLevel;
        int plugLevel = mDischargePlugLevel;
        if (plugLevel < 0 || curLevel < (plugLevel+1)) {
            return -1;
        }
        long duration = computeBatteryRealtime(curTime, STATS_SINCE_UNPLUGGED);
        if (duration < 1000*1000) {
            return -1;
        }
        long usPerLevel = duration/(curLevel-plugLevel);
        return usPerLevel * (100-curLevel);
        */
        if (mChargeStepTracker.mNumStepDurations < 1) {
            return -1;
        }
        long msPerLevel = mChargeStepTracker.computeTimePerLevel();
        if (msPerLevel <= 0) {
            return -1;
        }
        return (msPerLevel * (100-mCurrentBatteryLevel)) * 1000;
    }

    /*@hide */
    public CellularBatteryStats getCellularBatteryStats() {
        CellularBatteryStats s = new CellularBatteryStats();
        final int which = STATS_SINCE_CHARGED;
        final long rawRealTime = SystemClock.elapsedRealtime() * 1000;
        final ControllerActivityCounter counter = getModemControllerActivity();
        final long sleepTimeMs = counter.getSleepTimeCounter().getCountLocked(which);
        final long idleTimeMs = counter.getIdleTimeCounter().getCountLocked(which);
        final long rxTimeMs = counter.getRxTimeCounter().getCountLocked(which);
        final long energyConsumedMaMs = counter.getPowerCounter().getCountLocked(which);
        final long monitoredRailChargeConsumedMaMs =
                counter.getMonitoredRailChargeConsumedMaMs().getCountLocked(which);
        long[] timeInRatMs = new long[BatteryStats.NUM_DATA_CONNECTION_TYPES];
        for (int i = 0; i < timeInRatMs.length; i++) {
           timeInRatMs[i] = getPhoneDataConnectionTime(i, rawRealTime, which) / 1000;
        }
        long[] timeInRxSignalStrengthLevelMs = new long[SignalStrength.NUM_SIGNAL_STRENGTH_BINS];
        for (int i = 0; i < timeInRxSignalStrengthLevelMs.length; i++) {
           timeInRxSignalStrengthLevelMs[i]
               = getPhoneSignalStrengthTime(i, rawRealTime, which) / 1000;
        }
        long[] txTimeMs = new long[Math.min(ModemActivityInfo.TX_POWER_LEVELS,
            counter.getTxTimeCounters().length)];
        long totalTxTimeMs = 0;
        for (int i = 0; i < txTimeMs.length; i++) {
            txTimeMs[i] = counter.getTxTimeCounters()[i].getCountLocked(which);
            totalTxTimeMs += txTimeMs[i];
        }
        s.setLoggingDurationMs(computeBatteryRealtime(rawRealTime, which) / 1000);
        s.setKernelActiveTimeMs(getMobileRadioActiveTime(rawRealTime, which) / 1000);
        s.setNumPacketsTx(getNetworkActivityPackets(NETWORK_MOBILE_TX_DATA, which));
        s.setNumBytesTx(getNetworkActivityBytes(NETWORK_MOBILE_TX_DATA, which));
        s.setNumPacketsRx(getNetworkActivityPackets(NETWORK_MOBILE_RX_DATA, which));
        s.setNumBytesRx(getNetworkActivityBytes(NETWORK_MOBILE_RX_DATA, which));
        s.setSleepTimeMs(sleepTimeMs);
        s.setIdleTimeMs(idleTimeMs);
        s.setRxTimeMs(rxTimeMs);
        s.setEnergyConsumedMaMs(energyConsumedMaMs);
        s.setTimeInRatMs(timeInRatMs);
        s.setTimeInRxSignalStrengthLevelMs(timeInRxSignalStrengthLevelMs);
        s.setTxTimeMs(txTimeMs);
        s.setMonitoredRailChargeConsumedMaMs(monitoredRailChargeConsumedMaMs);
        return s;
    }

    /*@hide */
    public WifiBatteryStats getWifiBatteryStats() {
        WifiBatteryStats s = new WifiBatteryStats();
        final int which = STATS_SINCE_CHARGED;
        final long rawRealTime = SystemClock.elapsedRealtime() * 1000;
        final ControllerActivityCounter counter = getWifiControllerActivity();
        final long idleTimeMs = counter.getIdleTimeCounter().getCountLocked(which);
        final long scanTimeMs = counter.getScanTimeCounter().getCountLocked(which);
        final long rxTimeMs = counter.getRxTimeCounter().getCountLocked(which);
        final long txTimeMs = counter.getTxTimeCounters()[0].getCountLocked(which);
        final long totalControllerActivityTimeMs
                = computeBatteryRealtime(SystemClock.elapsedRealtime() * 1000, which) / 1000;
        final long sleepTimeMs
                = totalControllerActivityTimeMs - (idleTimeMs + rxTimeMs + txTimeMs);
        final long energyConsumedMaMs = counter.getPowerCounter().getCountLocked(which);
        final long monitoredRailChargeConsumedMaMs =
                counter.getMonitoredRailChargeConsumedMaMs().getCountLocked(which);
        long numAppScanRequest = 0;
        for (int i = 0; i < mUidStats.size(); i++) {
            numAppScanRequest += mUidStats.valueAt(i).mWifiScanTimer.getCountLocked(which);
        }
        long[] timeInStateMs = new long[NUM_WIFI_STATES];
        for (int i=0; i<NUM_WIFI_STATES; i++) {
            timeInStateMs[i] = getWifiStateTime(i, rawRealTime, which) / 1000;
        }
        long[] timeInSupplStateMs = new long[NUM_WIFI_SUPPL_STATES];
        for (int i=0; i<NUM_WIFI_SUPPL_STATES; i++) {
            timeInSupplStateMs[i] = getWifiSupplStateTime(i, rawRealTime, which) / 1000;
        }
        long[] timeSignalStrengthTimeMs = new long[NUM_WIFI_SIGNAL_STRENGTH_BINS];
        for (int i=0; i<NUM_WIFI_SIGNAL_STRENGTH_BINS; i++) {
            timeSignalStrengthTimeMs[i] = getWifiSignalStrengthTime(i, rawRealTime, which) / 1000;
        }
        s.setLoggingDurationMs(computeBatteryRealtime(rawRealTime, which) / 1000);
        s.setKernelActiveTimeMs(getWifiActiveTime(rawRealTime, which) / 1000);
        s.setNumPacketsTx(getNetworkActivityPackets(NETWORK_WIFI_TX_DATA, which));
        s.setNumBytesTx(getNetworkActivityBytes(NETWORK_WIFI_TX_DATA, which));
        s.setNumPacketsRx(getNetworkActivityPackets(NETWORK_WIFI_RX_DATA, which));
        s.setNumBytesRx(getNetworkActivityBytes(NETWORK_WIFI_RX_DATA, which));
        s.setSleepTimeMs(sleepTimeMs);
        s.setIdleTimeMs(idleTimeMs);
        s.setRxTimeMs(rxTimeMs);
        s.setTxTimeMs(txTimeMs);
        s.setScanTimeMs(scanTimeMs);
        s.setEnergyConsumedMaMs(energyConsumedMaMs);
        s.setNumAppScanRequest(numAppScanRequest);
        s.setTimeInStateMs(timeInStateMs);
        s.setTimeInSupplicantStateMs(timeInSupplStateMs);
        s.setTimeInRxSignalStrengthLevelMs(timeSignalStrengthTimeMs);
        s.setMonitoredRailChargeConsumedMaMs(monitoredRailChargeConsumedMaMs);
        return s;
    }

    /*@hide */
    public GpsBatteryStats getGpsBatteryStats() {
        GpsBatteryStats s = new GpsBatteryStats();
        final int which = STATS_SINCE_CHARGED;
        final long rawRealTime = SystemClock.elapsedRealtime() * 1000;
        s.setLoggingDurationMs(computeBatteryRealtime(rawRealTime, which) / 1000);
        s.setEnergyConsumedMaMs(getGpsBatteryDrainMaMs());
        long[] time = new long[GnssMetrics.NUM_GPS_SIGNAL_QUALITY_LEVELS];
        for (int i=0; i<time.length; i++) {
            time[i] = getGpsSignalQualityTime(i, rawRealTime, which) / 1000;
        }
        s.setTimeInGpsSignalQualityLevel(time);
        return s;
    }

    @Override
    public LevelStepTracker getChargeLevelStepTracker() {
        return mChargeStepTracker;
    }

    @Override
    public LevelStepTracker getDailyChargeLevelStepTracker() {
        return mDailyChargeStepTracker;
    }

    @Override
    public ArrayList<PackageChange> getDailyPackageChanges() {
        return mDailyPackageChanges;
    }

    protected long getBatteryUptimeLocked() {
        return mOnBatteryTimeBase.getUptime(mClocks.uptimeMillis() * 1000);
    }

    @Override
    public long getBatteryUptime(long curTime) {
        return mOnBatteryTimeBase.getUptime(curTime);
    }

    @Override
    @UnsupportedAppUsage
    public long getBatteryRealtime(long curTime) {
        return mOnBatteryTimeBase.getRealtime(curTime);
    }

    @Override
    @UnsupportedAppUsage
    public int getDischargeStartLevel() {
        synchronized(this) {
            return getDischargeStartLevelLocked();
        }
    }

    public int getDischargeStartLevelLocked() {
            return mDischargeUnplugLevel;
    }

    @Override
    @UnsupportedAppUsage
    public int getDischargeCurrentLevel() {
        synchronized(this) {
            return getDischargeCurrentLevelLocked();
        }
    }

    public int getDischargeCurrentLevelLocked() {
        return mDischargeCurrentLevel;
    }

    @Override
    public int getLowDischargeAmountSinceCharge() {
        synchronized(this) {
            int val = mLowDischargeAmountSinceCharge;
            if (mOnBattery && mDischargeCurrentLevel < mDischargeUnplugLevel) {
                val += mDischargeUnplugLevel-mDischargeCurrentLevel-1;
            }
            return val;
        }
    }

    @Override
    public int getHighDischargeAmountSinceCharge() {
        synchronized(this) {
            int val = mHighDischargeAmountSinceCharge;
            if (mOnBattery && mDischargeCurrentLevel < mDischargeUnplugLevel) {
                val += mDischargeUnplugLevel-mDischargeCurrentLevel;
            }
            return val;
        }
    }

    @Override
    @UnsupportedAppUsage
    public int getDischargeAmount(int which) {
        int dischargeAmount = which == STATS_SINCE_CHARGED
                ? getHighDischargeAmountSinceCharge()
                : (getDischargeStartLevel() - getDischargeCurrentLevel());
        if (dischargeAmount < 0) {
            dischargeAmount = 0;
        }
        return dischargeAmount;
    }

    @Override
    @UnsupportedAppUsage
    public int getDischargeAmountScreenOn() {
        synchronized(this) {
            int val = mDischargeAmountScreenOn;
            if (mOnBattery && isScreenOn(mScreenState)
                    && mDischargeCurrentLevel < mDischargeScreenOnUnplugLevel) {
                val += mDischargeScreenOnUnplugLevel-mDischargeCurrentLevel;
            }
            return val;
        }
    }

    @Override
    public int getDischargeAmountScreenOnSinceCharge() {
        synchronized(this) {
            int val = mDischargeAmountScreenOnSinceCharge;
            if (mOnBattery && isScreenOn(mScreenState)
                    && mDischargeCurrentLevel < mDischargeScreenOnUnplugLevel) {
                val += mDischargeScreenOnUnplugLevel-mDischargeCurrentLevel;
            }
            return val;
        }
    }

    @Override
    @UnsupportedAppUsage
    public int getDischargeAmountScreenOff() {
        synchronized(this) {
            int val = mDischargeAmountScreenOff;
            if (mOnBattery && isScreenOff(mScreenState)
                    && mDischargeCurrentLevel < mDischargeScreenOffUnplugLevel) {
                val += mDischargeScreenOffUnplugLevel-mDischargeCurrentLevel;
            }
            // For backward compatibility, doze discharge is counted into screen off.
            return val + getDischargeAmountScreenDoze();
        }
    }

    @Override
    public int getDischargeAmountScreenOffSinceCharge() {
        synchronized(this) {
            int val = mDischargeAmountScreenOffSinceCharge;
            if (mOnBattery && isScreenOff(mScreenState)
                    && mDischargeCurrentLevel < mDischargeScreenOffUnplugLevel) {
                val += mDischargeScreenOffUnplugLevel-mDischargeCurrentLevel;
            }
            // For backward compatibility, doze discharge is counted into screen off.
            return val + getDischargeAmountScreenDozeSinceCharge();
        }
    }

    @Override
    public int getDischargeAmountScreenDoze() {
        synchronized(this) {
            int val = mDischargeAmountScreenDoze;
            if (mOnBattery && isScreenDoze(mScreenState)
                    && mDischargeCurrentLevel < mDischargeScreenDozeUnplugLevel) {
                val += mDischargeScreenDozeUnplugLevel-mDischargeCurrentLevel;
            }
            return val;
        }
    }

    @Override
    public int getDischargeAmountScreenDozeSinceCharge() {
        synchronized(this) {
            int val = mDischargeAmountScreenDozeSinceCharge;
            if (mOnBattery && isScreenDoze(mScreenState)
                    && mDischargeCurrentLevel < mDischargeScreenDozeUnplugLevel) {
                val += mDischargeScreenDozeUnplugLevel-mDischargeCurrentLevel;
            }
            return val;
        }
    }

    /**
     * Retrieve the statistics object for a particular uid, creating if needed.
     */
    @UnsupportedAppUsage
    public Uid getUidStatsLocked(int uid) {
        Uid u = mUidStats.get(uid);
        if (u == null) {
            u = new Uid(this, uid);
            mUidStats.put(uid, u);
        }
        return u;
    }

    /**
     * Retrieve the statistics object for a particular uid. Returns null if the object is not
     * available.
     */
    public Uid getAvailableUidStatsLocked(int uid) {
        Uid u = mUidStats.get(uid);
        return u;
    }

    public void onCleanupUserLocked(int userId) {
        final int firstUidForUser = UserHandle.getUid(userId, 0);
        final int lastUidForUser = UserHandle.getUid(userId, UserHandle.PER_USER_RANGE - 1);
        mPendingRemovedUids.add(
                new UidToRemove(firstUidForUser, lastUidForUser, mClocks.elapsedRealtime()));
    }

    public void onUserRemovedLocked(int userId) {
        final int firstUidForUser = UserHandle.getUid(userId, 0);
        final int lastUidForUser = UserHandle.getUid(userId, UserHandle.PER_USER_RANGE - 1);
        mUidStats.put(firstUidForUser, null);
        mUidStats.put(lastUidForUser, null);
        final int firstIndex = mUidStats.indexOfKey(firstUidForUser);
        final int lastIndex = mUidStats.indexOfKey(lastUidForUser);
        for (int i = firstIndex; i <= lastIndex; i++) {
            final Uid uid = mUidStats.valueAt(i);
            if (uid != null) {
                uid.detachFromTimeBase();
            }
        }
        mUidStats.removeAtRange(firstIndex, lastIndex - firstIndex + 1);
    }

    /**
     * Remove the statistics object for a particular uid.
     */
    @UnsupportedAppUsage
    public void removeUidStatsLocked(int uid) {
        final Uid u = mUidStats.get(uid);
        if (u != null) {
            u.detachFromTimeBase();
        }
        mUidStats.remove(uid);
        mPendingRemovedUids.add(new UidToRemove(uid, mClocks.elapsedRealtime()));
    }

    /**
     * Retrieve the statistics object for a particular process, creating
     * if needed.
     */
    @UnsupportedAppUsage
    public Uid.Proc getProcessStatsLocked(int uid, String name) {
        uid = mapUid(uid);
        Uid u = getUidStatsLocked(uid);
        return u.getProcessStatsLocked(name);
    }

    /**
     * Retrieve the statistics object for a particular process, creating
     * if needed.
     */
    @UnsupportedAppUsage
    public Uid.Pkg getPackageStatsLocked(int uid, String pkg) {
        uid = mapUid(uid);
        Uid u = getUidStatsLocked(uid);
        return u.getPackageStatsLocked(pkg);
    }

    /**
     * Retrieve the statistics object for a particular service, creating
     * if needed.
     */
    @UnsupportedAppUsage
    public Uid.Pkg.Serv getServiceStatsLocked(int uid, String pkg, String name) {
        uid = mapUid(uid);
        Uid u = getUidStatsLocked(uid);
        return u.getServiceStatsLocked(pkg, name);
    }

    public void shutdownLocked() {
        recordShutdownLocked(mClocks.elapsedRealtime(), mClocks.uptimeMillis());
        writeSyncLocked();
        mShuttingDown = true;
    }

    public boolean trackPerProcStateCpuTimes() {
        return mConstants.TRACK_CPU_TIMES_BY_PROC_STATE && mPerProcStateCpuTimesAvailable;
    }

    public void systemServicesReady(Context context) {
        mConstants.startObserving(context.getContentResolver());
        registerUsbStateReceiver(context);
    }

    @VisibleForTesting
    public final class Constants extends ContentObserver {
        public static final String KEY_TRACK_CPU_TIMES_BY_PROC_STATE
                = "track_cpu_times_by_proc_state";
        public static final String KEY_TRACK_CPU_ACTIVE_CLUSTER_TIME
                = "track_cpu_active_cluster_time";
        public static final String KEY_PROC_STATE_CPU_TIMES_READ_DELAY_MS
                = "proc_state_cpu_times_read_delay_ms";
        public static final String KEY_KERNEL_UID_READERS_THROTTLE_TIME
                = "kernel_uid_readers_throttle_time";
        public static final String KEY_UID_REMOVE_DELAY_MS
                = "uid_remove_delay_ms";
        public static final String KEY_EXTERNAL_STATS_COLLECTION_RATE_LIMIT_MS
                = "external_stats_collection_rate_limit_ms";
        public static final String KEY_BATTERY_LEVEL_COLLECTION_DELAY_MS
                = "battery_level_collection_delay_ms";
        public static final String KEY_MAX_HISTORY_FILES = "max_history_files";
        public static final String KEY_MAX_HISTORY_BUFFER_KB = "max_history_buffer_kb";
        public static final String KEY_BATTERY_CHARGED_DELAY_MS =
                "battery_charged_delay_ms";

        private static final boolean DEFAULT_TRACK_CPU_TIMES_BY_PROC_STATE = false;
        private static final boolean DEFAULT_TRACK_CPU_ACTIVE_CLUSTER_TIME = true;
        private static final long DEFAULT_PROC_STATE_CPU_TIMES_READ_DELAY_MS = 5_000;
        private static final long DEFAULT_KERNEL_UID_READERS_THROTTLE_TIME = 1_000;
        private static final long DEFAULT_UID_REMOVE_DELAY_MS = 5L * 60L * 1000L;
        private static final long DEFAULT_EXTERNAL_STATS_COLLECTION_RATE_LIMIT_MS = 600_000;
        private static final long DEFAULT_BATTERY_LEVEL_COLLECTION_DELAY_MS = 300_000;
        private static final int DEFAULT_MAX_HISTORY_FILES = 32;
        private static final int DEFAULT_MAX_HISTORY_BUFFER_KB = 128; /*Kilo Bytes*/
        private static final int DEFAULT_MAX_HISTORY_FILES_LOW_RAM_DEVICE = 64;
        private static final int DEFAULT_MAX_HISTORY_BUFFER_LOW_RAM_DEVICE_KB = 64; /*Kilo Bytes*/
        private static final int DEFAULT_BATTERY_CHARGED_DELAY_MS = 900000; /* 15 min */

        public boolean TRACK_CPU_TIMES_BY_PROC_STATE = DEFAULT_TRACK_CPU_TIMES_BY_PROC_STATE;
        public boolean TRACK_CPU_ACTIVE_CLUSTER_TIME = DEFAULT_TRACK_CPU_ACTIVE_CLUSTER_TIME;
        public long PROC_STATE_CPU_TIMES_READ_DELAY_MS = DEFAULT_PROC_STATE_CPU_TIMES_READ_DELAY_MS;
        /* Do not set default value for KERNEL_UID_READERS_THROTTLE_TIME. Need to trigger an
         * update when startObserving. */
        public long KERNEL_UID_READERS_THROTTLE_TIME;
        public long UID_REMOVE_DELAY_MS = DEFAULT_UID_REMOVE_DELAY_MS;
        public long EXTERNAL_STATS_COLLECTION_RATE_LIMIT_MS
                = DEFAULT_EXTERNAL_STATS_COLLECTION_RATE_LIMIT_MS;
        public long BATTERY_LEVEL_COLLECTION_DELAY_MS
                = DEFAULT_BATTERY_LEVEL_COLLECTION_DELAY_MS;
        public int MAX_HISTORY_FILES;
        public int MAX_HISTORY_BUFFER; /*Bytes*/
        public int BATTERY_CHARGED_DELAY_MS = DEFAULT_BATTERY_CHARGED_DELAY_MS;

        private ContentResolver mResolver;
        private final KeyValueListParser mParser = new KeyValueListParser(',');

        public Constants(Handler handler) {
            super(handler);
            if (ActivityManager.isLowRamDeviceStatic()) {
                MAX_HISTORY_FILES = DEFAULT_MAX_HISTORY_FILES_LOW_RAM_DEVICE;
                MAX_HISTORY_BUFFER = DEFAULT_MAX_HISTORY_BUFFER_LOW_RAM_DEVICE_KB * 1024;
            } else {
                MAX_HISTORY_FILES = DEFAULT_MAX_HISTORY_FILES;
                MAX_HISTORY_BUFFER = DEFAULT_MAX_HISTORY_BUFFER_KB * 1024;
            }
        }

        public void startObserving(ContentResolver resolver) {
            mResolver = resolver;
            mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BATTERY_STATS_CONSTANTS),
                    false /* notifyForDescendants */, this);
            mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BATTERY_CHARGING_STATE_UPDATE_DELAY),
                    false /* notifyForDescendants */, this);
            updateConstants();
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (uri.equals(
                    Settings.Global.getUriFor(
                            Settings.Global.BATTERY_CHARGING_STATE_UPDATE_DELAY))) {
                synchronized (BatteryStatsImpl.this) {
                    updateBatteryChargedDelayMsLocked();
                }
                return;
            }
            updateConstants();
        }

        private void updateConstants() {
            synchronized (BatteryStatsImpl.this) {
                try {
                    mParser.setString(Settings.Global.getString(mResolver,
                            Settings.Global.BATTERY_STATS_CONSTANTS));
                } catch (IllegalArgumentException e) {
                    // Failed to parse the settings string, log this and move on
                    // with defaults.
                    Slog.e(TAG, "Bad batterystats settings", e);
                }

                updateTrackCpuTimesByProcStateLocked(TRACK_CPU_TIMES_BY_PROC_STATE,
                        mParser.getBoolean(KEY_TRACK_CPU_TIMES_BY_PROC_STATE,
                                DEFAULT_TRACK_CPU_TIMES_BY_PROC_STATE));
                TRACK_CPU_ACTIVE_CLUSTER_TIME = mParser.getBoolean(
                        KEY_TRACK_CPU_ACTIVE_CLUSTER_TIME, DEFAULT_TRACK_CPU_ACTIVE_CLUSTER_TIME);
                updateProcStateCpuTimesReadDelayMs(PROC_STATE_CPU_TIMES_READ_DELAY_MS,
                        mParser.getLong(KEY_PROC_STATE_CPU_TIMES_READ_DELAY_MS,
                                DEFAULT_PROC_STATE_CPU_TIMES_READ_DELAY_MS));
                updateKernelUidReadersThrottleTime(KERNEL_UID_READERS_THROTTLE_TIME,
                        mParser.getLong(KEY_KERNEL_UID_READERS_THROTTLE_TIME,
                                DEFAULT_KERNEL_UID_READERS_THROTTLE_TIME));
                updateUidRemoveDelay(
                        mParser.getLong(KEY_UID_REMOVE_DELAY_MS, DEFAULT_UID_REMOVE_DELAY_MS));
                EXTERNAL_STATS_COLLECTION_RATE_LIMIT_MS = mParser.getLong(
                        KEY_EXTERNAL_STATS_COLLECTION_RATE_LIMIT_MS,
                        DEFAULT_EXTERNAL_STATS_COLLECTION_RATE_LIMIT_MS);
                BATTERY_LEVEL_COLLECTION_DELAY_MS = mParser.getLong(
                        KEY_BATTERY_LEVEL_COLLECTION_DELAY_MS,
                        DEFAULT_BATTERY_LEVEL_COLLECTION_DELAY_MS);

                MAX_HISTORY_FILES = mParser.getInt(KEY_MAX_HISTORY_FILES,
                        ActivityManager.isLowRamDeviceStatic() ?
                                DEFAULT_MAX_HISTORY_FILES_LOW_RAM_DEVICE
                        : DEFAULT_MAX_HISTORY_FILES);
                MAX_HISTORY_BUFFER = mParser.getInt(KEY_MAX_HISTORY_BUFFER_KB,
                        ActivityManager.isLowRamDeviceStatic() ?
                                DEFAULT_MAX_HISTORY_BUFFER_LOW_RAM_DEVICE_KB
                                : DEFAULT_MAX_HISTORY_BUFFER_KB)
                        * 1024;
                updateBatteryChargedDelayMsLocked();
            }
        }

        private void updateBatteryChargedDelayMsLocked() {
            // a negative value indicates that we should ignore this override
            final int delay = Settings.Global.getInt(mResolver,
                    Settings.Global.BATTERY_CHARGING_STATE_UPDATE_DELAY,
                    -1);

            BATTERY_CHARGED_DELAY_MS = delay >= 0 ? delay : mParser.getInt(
                    KEY_BATTERY_CHARGED_DELAY_MS,
                    DEFAULT_BATTERY_CHARGED_DELAY_MS);
        }

        private void updateTrackCpuTimesByProcStateLocked(boolean wasEnabled, boolean isEnabled) {
            TRACK_CPU_TIMES_BY_PROC_STATE = isEnabled;
            if (isEnabled && !wasEnabled) {
                mIsPerProcessStateCpuDataStale = true;
                mExternalSync.scheduleCpuSyncDueToSettingChange();

                mNumSingleUidCpuTimeReads = 0;
                mNumBatchedSingleUidCpuTimeReads = 0;
                mCpuTimeReadsTrackingStartTime = mClocks.uptimeMillis();
            }
        }

        private void updateProcStateCpuTimesReadDelayMs(long oldDelayMillis, long newDelayMillis) {
            PROC_STATE_CPU_TIMES_READ_DELAY_MS = newDelayMillis;
            if (oldDelayMillis != newDelayMillis) {
                mNumSingleUidCpuTimeReads = 0;
                mNumBatchedSingleUidCpuTimeReads = 0;
                mCpuTimeReadsTrackingStartTime = mClocks.uptimeMillis();
            }
        }

        private void updateKernelUidReadersThrottleTime(long oldTimeMs, long newTimeMs) {
            KERNEL_UID_READERS_THROTTLE_TIME = newTimeMs;
            if (oldTimeMs != newTimeMs) {
                mCpuUidUserSysTimeReader.setThrottle(KERNEL_UID_READERS_THROTTLE_TIME);
                mCpuUidFreqTimeReader.setThrottle(KERNEL_UID_READERS_THROTTLE_TIME);
                mCpuUidActiveTimeReader.setThrottle(KERNEL_UID_READERS_THROTTLE_TIME);
                mCpuUidClusterTimeReader
                        .setThrottle(KERNEL_UID_READERS_THROTTLE_TIME);
            }
        }

        private void updateUidRemoveDelay(long newTimeMs) {
            UID_REMOVE_DELAY_MS = newTimeMs;
            clearPendingRemovedUids();
        }

        public void dumpLocked(PrintWriter pw) {
            pw.print(KEY_TRACK_CPU_TIMES_BY_PROC_STATE); pw.print("=");
            pw.println(TRACK_CPU_TIMES_BY_PROC_STATE);
            pw.print(KEY_TRACK_CPU_ACTIVE_CLUSTER_TIME); pw.print("=");
            pw.println(TRACK_CPU_ACTIVE_CLUSTER_TIME);
            pw.print(KEY_PROC_STATE_CPU_TIMES_READ_DELAY_MS); pw.print("=");
            pw.println(PROC_STATE_CPU_TIMES_READ_DELAY_MS);
            pw.print(KEY_KERNEL_UID_READERS_THROTTLE_TIME); pw.print("=");
            pw.println(KERNEL_UID_READERS_THROTTLE_TIME);
            pw.print(KEY_EXTERNAL_STATS_COLLECTION_RATE_LIMIT_MS); pw.print("=");
            pw.println(EXTERNAL_STATS_COLLECTION_RATE_LIMIT_MS);
            pw.print(KEY_BATTERY_LEVEL_COLLECTION_DELAY_MS); pw.print("=");
            pw.println(BATTERY_LEVEL_COLLECTION_DELAY_MS);
            pw.print(KEY_MAX_HISTORY_FILES); pw.print("=");
            pw.println(MAX_HISTORY_FILES);
            pw.print(KEY_MAX_HISTORY_BUFFER_KB); pw.print("=");
            pw.println(MAX_HISTORY_BUFFER/1024);
            pw.print(KEY_BATTERY_CHARGED_DELAY_MS); pw.print("=");
            pw.println(BATTERY_CHARGED_DELAY_MS);
        }
    }

    public long getExternalStatsCollectionRateLimitMs() {
        synchronized (this) {
            return mConstants.EXTERNAL_STATS_COLLECTION_RATE_LIMIT_MS;
        }
    }

    @GuardedBy("this")
    public void dumpConstantsLocked(PrintWriter pw) {
        mConstants.dumpLocked(pw);
    }

    @GuardedBy("this")
    public void dumpCpuStatsLocked(PrintWriter pw) {
        int size = mUidStats.size();
        pw.println("Per UID CPU user & system time in ms:");
        for (int i = 0; i < size; i++) {
            int u = mUidStats.keyAt(i);
            Uid uid = mUidStats.get(u);
            pw.print("  "); pw.print(u); pw.print(": ");
            pw.print(uid.getUserCpuTimeUs(STATS_SINCE_CHARGED) / 1000); pw.print(" ");
            pw.println(uid.getSystemCpuTimeUs(STATS_SINCE_CHARGED) / 1000);
        }
        pw.println("Per UID CPU active time in ms:");
        for (int i = 0; i < size; i++) {
            int u = mUidStats.keyAt(i);
            Uid uid = mUidStats.get(u);
            if (uid.getCpuActiveTime() > 0) {
                pw.print("  "); pw.print(u); pw.print(": "); pw.println(uid.getCpuActiveTime());
            }
        }
        pw.println("Per UID CPU cluster time in ms:");
        for (int i = 0; i < size; i++) {
            int u = mUidStats.keyAt(i);
            long[] times = mUidStats.get(u).getCpuClusterTimes();
            if (times != null) {
                pw.print("  "); pw.print(u); pw.print(": "); pw.println(Arrays.toString(times));
            }
        }
        pw.println("Per UID CPU frequency time in ms:");
        for (int i = 0; i < size; i++) {
            int u = mUidStats.keyAt(i);
            long[] times = mUidStats.get(u).getCpuFreqTimes(STATS_SINCE_CHARGED);
            if (times != null) {
                pw.print("  "); pw.print(u); pw.print(": "); pw.println(Arrays.toString(times));
            }
        }
    }

    final ReentrantLock mWriteLock = new ReentrantLock();

    public void writeAsyncLocked() {
        writeStatsLocked(false);
        writeHistoryLocked(false);
    }

    public void writeSyncLocked() {
        writeStatsLocked(true);
        writeHistoryLocked(true);
    }

    void writeStatsLocked(boolean sync) {
        if (mStatsFile == null) {
            Slog.w(TAG,
                    "writeStatsLocked: no file associated with this instance");
            return;
        }

        if (mShuttingDown) {
            return;
        }

        final Parcel p = Parcel.obtain();
        final long start = SystemClock.uptimeMillis();
        writeSummaryToParcel(p, false/*history is in separate file*/);
        if (DEBUG) {
            Slog.d(TAG, "writeSummaryToParcel duration ms:"
                    + (SystemClock.uptimeMillis() - start) + " bytes:" + p.dataSize());
        }
        mLastWriteTime = mClocks.elapsedRealtime();
        writeParcelToFileLocked(p, mStatsFile, sync);
    }

    void writeHistoryLocked(boolean sync) {
        if (mBatteryStatsHistory.getActiveFile() == null) {
            Slog.w(TAG,
                    "writeHistoryLocked: no history file associated with this instance");
            return;
        }

        if (mShuttingDown) {
            return;
        }

        Parcel p = Parcel.obtain();
        final long start = SystemClock.uptimeMillis();
        writeHistoryBuffer(p, true, true);
        if (DEBUG) {
            Slog.d(TAG, "writeHistoryBuffer duration ms:"
                    + (SystemClock.uptimeMillis() - start) + " bytes:" + p.dataSize());
        }
        writeParcelToFileLocked(p, mBatteryStatsHistory.getActiveFile(), sync);
    }

    void writeParcelToFileLocked(Parcel p, AtomicFile file, boolean sync) {
        if (sync) {
            commitPendingDataToDisk(p, file);
        } else {
            BackgroundThread.getHandler().post(new Runnable() {
                @Override public void run() {
                    commitPendingDataToDisk(p, file);
                }
            });
        }
    }

    private void commitPendingDataToDisk(Parcel p, AtomicFile file) {
        mWriteLock.lock();
        FileOutputStream fos = null;
        try {
            final long startTime = SystemClock.uptimeMillis();
            fos = file.startWrite();
            fos.write(p.marshall());
            fos.flush();
            file.finishWrite(fos);
            if (DEBUG) {
                Slog.d(TAG, "commitPendingDataToDisk file:" + file.getBaseFile().getPath()
                        + " duration ms:" + (SystemClock.uptimeMillis() - startTime)
                        + " bytes:" + p.dataSize());
            }
            com.android.internal.logging.EventLogTags.writeCommitSysConfigFile(
                    "batterystats", SystemClock.uptimeMillis() - startTime);
        } catch (IOException e) {
            Slog.w(TAG, "Error writing battery statistics", e);
            file.failWrite(fos);
        } finally {
            p.recycle();
            mWriteLock.unlock();
        }
    }

    @UnsupportedAppUsage
    public void readLocked() {
        if (mDailyFile != null) {
            readDailyStatsLocked();
        }

        if (mStatsFile == null) {
            Slog.w(TAG, "readLocked: no file associated with this instance");
            return;
        }

        if (mBatteryStatsHistory.getActiveFile() == null) {
            Slog.w(TAG,
                    "readLocked: no history file associated with this instance");
            return;
        }

        mUidStats.clear();

        Parcel stats = Parcel.obtain();
        try {
            final long start = SystemClock.uptimeMillis();
            byte[] raw = mStatsFile.readFully();
            stats.unmarshall(raw, 0, raw.length);
            stats.setDataPosition(0);
            readSummaryFromParcel(stats);
            if (DEBUG) {
                Slog.d(TAG, "readLocked stats file:" + mStatsFile.getBaseFile().getPath()
                        + " bytes:" + raw.length + " takes ms:" + (SystemClock.uptimeMillis()
                        - start));
            }
        } catch (Exception e) {
            Slog.e(TAG, "Error reading battery statistics", e);
            resetAllStatsLocked();
        } finally {
            stats.recycle();
        }

        Parcel history = Parcel.obtain();
        try {
            final long start = SystemClock.uptimeMillis();
            byte[] raw = mBatteryStatsHistory.getActiveFile().readFully();
            if (raw.length > 0) {
                history.unmarshall(raw, 0, raw.length);
                history.setDataPosition(0);
                readHistoryBuffer(history, true);
            }
            if (DEBUG) {
                Slog.d(TAG, "readLocked history file::"
                        + mBatteryStatsHistory.getActiveFile().getBaseFile().getPath()
                        + " bytes:" + raw.length + " takes ms:" + (SystemClock.uptimeMillis()
                        - start));
            }
        } catch (Exception e) {
            Slog.e(TAG, "Error reading battery history", e);
            clearHistoryLocked();
            mBatteryStatsHistory.resetAllFiles();
        } finally {
            history.recycle();
        }

        mEndPlatformVersion = Build.ID;

        if (mHistoryBuffer.dataPosition() > 0
                || mBatteryStatsHistory.getFilesNumbers().size() > 1) {
            mRecordingHistory = true;
            final long elapsedRealtime = mClocks.elapsedRealtime();
            final long uptime = mClocks.uptimeMillis();
            if (USE_OLD_HISTORY) {
                addHistoryRecordLocked(elapsedRealtime, uptime, HistoryItem.CMD_START, mHistoryCur);
            }
            addHistoryBufferLocked(elapsedRealtime, HistoryItem.CMD_START, mHistoryCur);
            startRecordingHistory(elapsedRealtime, uptime, false);
        }

        recordDailyStatsIfNeededLocked(false);
    }

    public int describeContents() {
        return 0;
    }

    void  readHistoryBuffer(Parcel in, boolean andOldHistory) throws ParcelFormatException {
        final int version = in.readInt();
        if (version != VERSION) {
            Slog.w("BatteryStats", "readHistoryBuffer: version got " + version
                    + ", expected " + VERSION + "; erasing old stats");
            return;
        }

        final long historyBaseTime = in.readLong();

        mHistoryBuffer.setDataSize(0);
        mHistoryBuffer.setDataPosition(0);

        int bufSize = in.readInt();
        int curPos = in.dataPosition();
        if (bufSize >= (mConstants.MAX_HISTORY_BUFFER*100)) {
            throw new ParcelFormatException("File corrupt: history data buffer too large " +
                    bufSize);
        } else if ((bufSize&~3) != bufSize) {
            throw new ParcelFormatException("File corrupt: history data buffer not aligned " +
                    bufSize);
        } else {
            if (DEBUG_HISTORY) Slog.i(TAG, "***************** READING NEW HISTORY: " + bufSize
                    + " bytes at " + curPos);
            mHistoryBuffer.appendFrom(in, curPos, bufSize);
            in.setDataPosition(curPos + bufSize);
        }

        if (andOldHistory) {
            readOldHistory(in);
        }

        if (DEBUG_HISTORY) {
            StringBuilder sb = new StringBuilder(128);
            sb.append("****************** OLD mHistoryBaseTime: ");
            TimeUtils.formatDuration(mHistoryBaseTime, sb);
            Slog.i(TAG, sb.toString());
        }
        mHistoryBaseTime = historyBaseTime;
        if (DEBUG_HISTORY) {
            StringBuilder sb = new StringBuilder(128);
            sb.append("****************** NEW mHistoryBaseTime: ");
            TimeUtils.formatDuration(mHistoryBaseTime, sb);
            Slog.i(TAG, sb.toString());
        }

        // We are just arbitrarily going to insert 1 minute from the sample of
        // the last run until samples in this run.
        if (mHistoryBaseTime > 0) {
            long oldnow = mClocks.elapsedRealtime();
            mHistoryBaseTime = mHistoryBaseTime - oldnow + 1;
            if (DEBUG_HISTORY) {
                StringBuilder sb = new StringBuilder(128);
                sb.append("****************** ADJUSTED mHistoryBaseTime: ");
                TimeUtils.formatDuration(mHistoryBaseTime, sb);
                Slog.i(TAG, sb.toString());
            }
        }
    }

    void readOldHistory(Parcel in) {
        if (!USE_OLD_HISTORY) {
            return;
        }
        mHistory = mHistoryEnd = mHistoryCache = null;
        long time;
        while (in.dataAvail() > 0 && (time=in.readLong()) >= 0) {
            HistoryItem rec = new HistoryItem(time, in);
            addHistoryRecordLocked(rec);
        }
    }

    void writeHistoryBuffer(Parcel out, boolean inclData, boolean andOldHistory) {
        if (DEBUG_HISTORY) {
            StringBuilder sb = new StringBuilder(128);
            sb.append("****************** WRITING mHistoryBaseTime: ");
            TimeUtils.formatDuration(mHistoryBaseTime, sb);
            sb.append(" mLastHistoryElapsedRealtime: ");
            TimeUtils.formatDuration(mLastHistoryElapsedRealtime, sb);
            Slog.i(TAG, sb.toString());
        }
        out.writeInt(VERSION);
        out.writeLong(mHistoryBaseTime + mLastHistoryElapsedRealtime);
        if (!inclData) {
            out.writeInt(0);
            out.writeInt(0);
            return;
        }

        out.writeInt(mHistoryBuffer.dataSize());
        if (DEBUG_HISTORY) Slog.i(TAG, "***************** WRITING HISTORY: "
                + mHistoryBuffer.dataSize() + " bytes at " + out.dataPosition());
        out.appendFrom(mHistoryBuffer, 0, mHistoryBuffer.dataSize());

        if (andOldHistory) {
            writeOldHistory(out);
        }
    }

    void writeOldHistory(Parcel out) {
        if (!USE_OLD_HISTORY) {
            return;
        }
        HistoryItem rec = mHistory;
        while (rec != null) {
            if (rec.time >= 0) rec.writeToParcel(out, 0);
            rec = rec.next;
        }
        out.writeLong(-1);
    }

    public void readSummaryFromParcel(Parcel in) throws ParcelFormatException {
        final int version = in.readInt();
        if (version != VERSION) {
            Slog.w("BatteryStats", "readFromParcel: version got " + version
                + ", expected " + VERSION + "; erasing old stats");
            return;
        }

        boolean inclHistory = in.readBoolean();
        if (inclHistory) {
            readHistoryBuffer(in, true);
            mBatteryStatsHistory.readFromParcel(in);
        }

        mHistoryTagPool.clear();
        mNextHistoryTagIdx = 0;
        mNumHistoryTagChars = 0;

        int numTags = in.readInt();
        for (int i=0; i<numTags; i++) {
            int idx = in.readInt();
            String str = in.readString();
            if (str == null) {
                throw new ParcelFormatException("null history tag string");
            }
            int uid = in.readInt();
            HistoryTag tag = new HistoryTag();
            tag.string = str;
            tag.uid = uid;
            tag.poolIdx = idx;
            mHistoryTagPool.put(tag, idx);
            if (idx >= mNextHistoryTagIdx) {
                mNextHistoryTagIdx = idx+1;
            }
            mNumHistoryTagChars += tag.string.length() + 1;
        }

        mStartCount = in.readInt();
        mUptime = in.readLong();
        mRealtime = in.readLong();
        mStartClockTime = in.readLong();
        mStartPlatformVersion = in.readString();
        mEndPlatformVersion = in.readString();
        mOnBatteryTimeBase.readSummaryFromParcel(in);
        mOnBatteryScreenOffTimeBase.readSummaryFromParcel(in);
        mDischargeUnplugLevel = in.readInt();
        mDischargePlugLevel = in.readInt();
        mDischargeCurrentLevel = in.readInt();
        mCurrentBatteryLevel = in.readInt();
        mEstimatedBatteryCapacity = in.readInt();
        mMinLearnedBatteryCapacity = in.readInt();
        mMaxLearnedBatteryCapacity = in.readInt();
        mLowDischargeAmountSinceCharge = in.readInt();
        mHighDischargeAmountSinceCharge = in.readInt();
        mDischargeAmountScreenOnSinceCharge = in.readInt();
        mDischargeAmountScreenOffSinceCharge = in.readInt();
        mDischargeAmountScreenDozeSinceCharge = in.readInt();
        mDischargeStepTracker.readFromParcel(in);
        mChargeStepTracker.readFromParcel(in);
        mDailyDischargeStepTracker.readFromParcel(in);
        mDailyChargeStepTracker.readFromParcel(in);
        mDischargeCounter.readSummaryFromParcelLocked(in);
        mDischargeScreenOffCounter.readSummaryFromParcelLocked(in);
        mDischargeScreenDozeCounter.readSummaryFromParcelLocked(in);
        mDischargeLightDozeCounter.readSummaryFromParcelLocked(in);
        mDischargeDeepDozeCounter.readSummaryFromParcelLocked(in);
        int NPKG = in.readInt();
        if (NPKG > 0) {
            mDailyPackageChanges = new ArrayList<>(NPKG);
            while (NPKG > 0) {
                NPKG--;
                PackageChange pc = new PackageChange();
                pc.mPackageName = in.readString();
                pc.mUpdate = in.readInt() != 0;
                pc.mVersionCode = in.readLong();
                mDailyPackageChanges.add(pc);
            }
        } else {
            mDailyPackageChanges = null;
        }
        mDailyStartTime = in.readLong();
        mNextMinDailyDeadline = in.readLong();
        mNextMaxDailyDeadline = in.readLong();

        mStartCount++;

        mScreenState = Display.STATE_UNKNOWN;
        mScreenOnTimer.readSummaryFromParcelLocked(in);
        mScreenDozeTimer.readSummaryFromParcelLocked(in);
        for (int i=0; i<NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            mScreenBrightnessTimer[i].readSummaryFromParcelLocked(in);
        }
        mInteractive = false;
        mInteractiveTimer.readSummaryFromParcelLocked(in);
        mPhoneOn = false;
        mPowerSaveModeEnabledTimer.readSummaryFromParcelLocked(in);
        mLongestLightIdleTime = in.readLong();
        mLongestFullIdleTime = in.readLong();
        mDeviceIdleModeLightTimer.readSummaryFromParcelLocked(in);
        mDeviceIdleModeFullTimer.readSummaryFromParcelLocked(in);
        mDeviceLightIdlingTimer.readSummaryFromParcelLocked(in);
        mDeviceIdlingTimer.readSummaryFromParcelLocked(in);
        mPhoneOnTimer.readSummaryFromParcelLocked(in);
        for (int i=0; i<SignalStrength.NUM_SIGNAL_STRENGTH_BINS; i++) {
            mPhoneSignalStrengthsTimer[i].readSummaryFromParcelLocked(in);
        }
        mPhoneSignalScanningTimer.readSummaryFromParcelLocked(in);
        for (int i=0; i<NUM_DATA_CONNECTION_TYPES; i++) {
            mPhoneDataConnectionsTimer[i].readSummaryFromParcelLocked(in);
        }
        for (int i = 0; i < NUM_NETWORK_ACTIVITY_TYPES; i++) {
            mNetworkByteActivityCounters[i].readSummaryFromParcelLocked(in);
            mNetworkPacketActivityCounters[i].readSummaryFromParcelLocked(in);
        }
        mMobileRadioPowerState = DataConnectionRealTimeInfo.DC_POWER_STATE_LOW;
        mMobileRadioActiveTimer.readSummaryFromParcelLocked(in);
        mMobileRadioActivePerAppTimer.readSummaryFromParcelLocked(in);
        mMobileRadioActiveAdjustedTime.readSummaryFromParcelLocked(in);
        mMobileRadioActiveUnknownTime.readSummaryFromParcelLocked(in);
        mMobileRadioActiveUnknownCount.readSummaryFromParcelLocked(in);
        mWifiMulticastWakelockTimer.readSummaryFromParcelLocked(in);
        mWifiRadioPowerState = DataConnectionRealTimeInfo.DC_POWER_STATE_LOW;
        mWifiOn = false;
        mWifiOnTimer.readSummaryFromParcelLocked(in);
        mGlobalWifiRunning = false;
        mGlobalWifiRunningTimer.readSummaryFromParcelLocked(in);
        for (int i=0; i<NUM_WIFI_STATES; i++) {
            mWifiStateTimer[i].readSummaryFromParcelLocked(in);
        }
        for (int i=0; i<NUM_WIFI_SUPPL_STATES; i++) {
            mWifiSupplStateTimer[i].readSummaryFromParcelLocked(in);
        }
        for (int i=0; i<NUM_WIFI_SIGNAL_STRENGTH_BINS; i++) {
            mWifiSignalStrengthsTimer[i].readSummaryFromParcelLocked(in);
        }
        mWifiActiveTimer.readSummaryFromParcelLocked(in);
        mWifiActivity.readSummaryFromParcel(in);
        for (int i=0; i<GnssMetrics.NUM_GPS_SIGNAL_QUALITY_LEVELS; i++) {
            mGpsSignalQualityTimer[i].readSummaryFromParcelLocked(in);
        }
        mBluetoothActivity.readSummaryFromParcel(in);
        mModemActivity.readSummaryFromParcel(in);
        mHasWifiReporting = in.readInt() != 0;
        mHasBluetoothReporting = in.readInt() != 0;
        mHasModemReporting = in.readInt() != 0;

        mNumConnectivityChange = in.readInt();
        mFlashlightOnNesting = 0;
        mFlashlightOnTimer.readSummaryFromParcelLocked(in);
        mCameraOnNesting = 0;
        mCameraOnTimer.readSummaryFromParcelLocked(in);
        mBluetoothScanNesting = 0;
        mBluetoothScanTimer.readSummaryFromParcelLocked(in);

        int NRPMS = in.readInt();
        if (NRPMS > 10000) {
            throw new ParcelFormatException("File corrupt: too many rpm stats " + NRPMS);
        }
        for (int irpm = 0; irpm < NRPMS; irpm++) {
            if (in.readInt() != 0) {
                String rpmName = in.readString();
                getRpmTimerLocked(rpmName).readSummaryFromParcelLocked(in);
            }
        }
        int NSORPMS = in.readInt();
        if (NSORPMS > 10000) {
            throw new ParcelFormatException("File corrupt: too many screen-off rpm stats " + NSORPMS);
        }
        for (int irpm = 0; irpm < NSORPMS; irpm++) {
            if (in.readInt() != 0) {
                String rpmName = in.readString();
                getScreenOffRpmTimerLocked(rpmName).readSummaryFromParcelLocked(in);
            }
        }

        int NKW = in.readInt();
        if (NKW > 10000) {
            throw new ParcelFormatException("File corrupt: too many kernel wake locks " + NKW);
        }
        for (int ikw = 0; ikw < NKW; ikw++) {
            if (in.readInt() != 0) {
                String kwltName = in.readString();
                getKernelWakelockTimerLocked(kwltName).readSummaryFromParcelLocked(in);
            }
        }

        int NWR = in.readInt();
        if (NWR > 10000) {
            throw new ParcelFormatException("File corrupt: too many wakeup reasons " + NWR);
        }
        for (int iwr = 0; iwr < NWR; iwr++) {
            if (in.readInt() != 0) {
                String reasonName = in.readString();
                getWakeupReasonTimerLocked(reasonName).readSummaryFromParcelLocked(in);
            }
        }

        int NMS = in.readInt();
        for (int ims = 0; ims < NMS; ims++) {
            if (in.readInt() != 0) {
                long kmstName = in.readLong();
                getKernelMemoryTimerLocked(kmstName).readSummaryFromParcelLocked(in);
            }
        }

        final int NU = in.readInt();
        if (NU > 10000) {
            throw new ParcelFormatException("File corrupt: too many uids " + NU);
        }
        for (int iu = 0; iu < NU; iu++) {
            int uid = in.readInt();
            Uid u = new Uid(this, uid);
            mUidStats.put(uid, u);

            u.mOnBatteryBackgroundTimeBase.readSummaryFromParcel(in);
            u.mOnBatteryScreenOffBackgroundTimeBase.readSummaryFromParcel(in);

            u.mWifiRunning = false;
            if (in.readInt() != 0) {
                u.mWifiRunningTimer.readSummaryFromParcelLocked(in);
            }
            u.mFullWifiLockOut = false;
            if (in.readInt() != 0) {
                u.mFullWifiLockTimer.readSummaryFromParcelLocked(in);
            }
            u.mWifiScanStarted = false;
            if (in.readInt() != 0) {
                u.mWifiScanTimer.readSummaryFromParcelLocked(in);
            }
            u.mWifiBatchedScanBinStarted = Uid.NO_BATCHED_SCAN_STARTED;
            for (int i = 0; i < Uid.NUM_WIFI_BATCHED_SCAN_BINS; i++) {
                if (in.readInt() != 0) {
                    u.makeWifiBatchedScanBin(i, null);
                    u.mWifiBatchedScanTimer[i].readSummaryFromParcelLocked(in);
                }
            }
            u.mWifiMulticastWakelockCount = 0;
            if (in.readInt() != 0) {
                u.mWifiMulticastTimer.readSummaryFromParcelLocked(in);
            }
            if (in.readInt() != 0) {
                u.createAudioTurnedOnTimerLocked().readSummaryFromParcelLocked(in);
            }
            if (in.readInt() != 0) {
                u.createVideoTurnedOnTimerLocked().readSummaryFromParcelLocked(in);
            }
            if (in.readInt() != 0) {
                u.createFlashlightTurnedOnTimerLocked().readSummaryFromParcelLocked(in);
            }
            if (in.readInt() != 0) {
                u.createCameraTurnedOnTimerLocked().readSummaryFromParcelLocked(in);
            }
            if (in.readInt() != 0) {
                u.createForegroundActivityTimerLocked().readSummaryFromParcelLocked(in);
            }
            if (in.readInt() != 0) {
                u.createForegroundServiceTimerLocked().readSummaryFromParcelLocked(in);
            }
            if (in.readInt() != 0) {
                u.createAggregatedPartialWakelockTimerLocked().readSummaryFromParcelLocked(in);
            }
            if (in.readInt() != 0) {
                u.createBluetoothScanTimerLocked().readSummaryFromParcelLocked(in);
            }
            if (in.readInt() != 0) {
                u.createBluetoothUnoptimizedScanTimerLocked().readSummaryFromParcelLocked(in);
            }
            if (in.readInt() != 0) {
                u.createBluetoothScanResultCounterLocked().readSummaryFromParcelLocked(in);
            }
            if (in.readInt() != 0) {
                u.createBluetoothScanResultBgCounterLocked().readSummaryFromParcelLocked(in);
            }
            u.mProcessState = ActivityManager.PROCESS_STATE_NONEXISTENT;
            for (int i = 0; i < Uid.NUM_PROCESS_STATE; i++) {
                if (in.readInt() != 0) {
                    u.makeProcessState(i, null);
                    u.mProcessStateTimer[i].readSummaryFromParcelLocked(in);
                }
            }
            if (in.readInt() != 0) {
                u.createVibratorOnTimerLocked().readSummaryFromParcelLocked(in);
            }

            if (in.readInt() != 0) {
                if (u.mUserActivityCounters == null) {
                    u.initUserActivityLocked();
                }
                for (int i=0; i<Uid.NUM_USER_ACTIVITY_TYPES; i++) {
                    u.mUserActivityCounters[i].readSummaryFromParcelLocked(in);
                }
            }

            if (in.readInt() != 0) {
                if (u.mNetworkByteActivityCounters == null) {
                    u.initNetworkActivityLocked();
                }
                for (int i = 0; i < NUM_NETWORK_ACTIVITY_TYPES; i++) {
                    u.mNetworkByteActivityCounters[i].readSummaryFromParcelLocked(in);
                    u.mNetworkPacketActivityCounters[i].readSummaryFromParcelLocked(in);
                }
                u.mMobileRadioActiveTime.readSummaryFromParcelLocked(in);
                u.mMobileRadioActiveCount.readSummaryFromParcelLocked(in);
            }

            u.mUserCpuTime.readSummaryFromParcelLocked(in);
            u.mSystemCpuTime.readSummaryFromParcelLocked(in);

            if (in.readInt() != 0) {
                final int numClusters = in.readInt();
                if (mPowerProfile != null && mPowerProfile.getNumCpuClusters() != numClusters) {
                    throw new ParcelFormatException("Incompatible cpu cluster arrangement");
                }
                detachIfNotNull(u.mCpuClusterSpeedTimesUs);
                u.mCpuClusterSpeedTimesUs = new LongSamplingCounter[numClusters][];
                for (int cluster = 0; cluster < numClusters; cluster++) {
                    if (in.readInt() != 0) {
                        final int NSB = in.readInt();
                        if (mPowerProfile != null &&
                                mPowerProfile.getNumSpeedStepsInCpuCluster(cluster) != NSB) {
                            throw new ParcelFormatException("File corrupt: too many speed bins " +
                                    NSB);
                        }

                        u.mCpuClusterSpeedTimesUs[cluster] = new LongSamplingCounter[NSB];
                        for (int speed = 0; speed < NSB; speed++) {
                            if (in.readInt() != 0) {
                                u.mCpuClusterSpeedTimesUs[cluster][speed] = new LongSamplingCounter(
                                        mOnBatteryTimeBase);
                                u.mCpuClusterSpeedTimesUs[cluster][speed].readSummaryFromParcelLocked(in);
                            }
                        }
                    } else {
                        u.mCpuClusterSpeedTimesUs[cluster] = null;
                    }
                }
            } else {
                detachIfNotNull(u.mCpuClusterSpeedTimesUs);
                u.mCpuClusterSpeedTimesUs = null;
            }

            detachIfNotNull(u.mCpuFreqTimeMs);
            u.mCpuFreqTimeMs = LongSamplingCounterArray.readSummaryFromParcelLocked(
                    in, mOnBatteryTimeBase);
            detachIfNotNull(u.mScreenOffCpuFreqTimeMs);
            u.mScreenOffCpuFreqTimeMs = LongSamplingCounterArray.readSummaryFromParcelLocked(
                    in, mOnBatteryScreenOffTimeBase);

            u.mCpuActiveTimeMs.readSummaryFromParcelLocked(in);
            u.mCpuClusterTimesMs.readSummaryFromParcelLocked(in);

            int length = in.readInt();
            if (length == Uid.NUM_PROCESS_STATE) {
                detachIfNotNull(u.mProcStateTimeMs);
                u.mProcStateTimeMs = new LongSamplingCounterArray[length];
                for (int procState = 0; procState < length; ++procState) {
                    u.mProcStateTimeMs[procState]
                            = LongSamplingCounterArray.readSummaryFromParcelLocked(
                                    in, mOnBatteryTimeBase);
                }
            } else {
                detachIfNotNull(u.mProcStateTimeMs);
                u.mProcStateTimeMs = null;
            }
            length = in.readInt();
            if (length == Uid.NUM_PROCESS_STATE) {
                detachIfNotNull(u.mProcStateScreenOffTimeMs);
                u.mProcStateScreenOffTimeMs = new LongSamplingCounterArray[length];
                for (int procState = 0; procState < length; ++procState) {
                    u.mProcStateScreenOffTimeMs[procState]
                            = LongSamplingCounterArray.readSummaryFromParcelLocked(
                                    in, mOnBatteryScreenOffTimeBase);
                }
            } else {
                detachIfNotNull(u.mProcStateScreenOffTimeMs);
                u.mProcStateScreenOffTimeMs = null;
            }

            if (in.readInt() != 0) {
                detachIfNotNull(u.mMobileRadioApWakeupCount);
                u.mMobileRadioApWakeupCount = new LongSamplingCounter(mOnBatteryTimeBase);
                u.mMobileRadioApWakeupCount.readSummaryFromParcelLocked(in);
            } else {
                detachIfNotNull(u.mMobileRadioApWakeupCount);
                u.mMobileRadioApWakeupCount = null;
            }

            if (in.readInt() != 0) {
                detachIfNotNull(u.mWifiRadioApWakeupCount);
                u.mWifiRadioApWakeupCount = new LongSamplingCounter(mOnBatteryTimeBase);
                u.mWifiRadioApWakeupCount.readSummaryFromParcelLocked(in);
            } else {
                detachIfNotNull(u.mWifiRadioApWakeupCount);
                u.mWifiRadioApWakeupCount = null;
            }

            int NW = in.readInt();
            if (NW > (MAX_WAKELOCKS_PER_UID+1)) {
                throw new ParcelFormatException("File corrupt: too many wake locks " + NW);
            }
            for (int iw = 0; iw < NW; iw++) {
                String wlName = in.readString();
                u.readWakeSummaryFromParcelLocked(wlName, in);
            }

            int NS = in.readInt();
            if (NS > (MAX_WAKELOCKS_PER_UID+1)) {
                throw new ParcelFormatException("File corrupt: too many syncs " + NS);
            }
            for (int is = 0; is < NS; is++) {
                String name = in.readString();
                u.readSyncSummaryFromParcelLocked(name, in);
            }

            int NJ = in.readInt();
            if (NJ > (MAX_WAKELOCKS_PER_UID+1)) {
                throw new ParcelFormatException("File corrupt: too many job timers " + NJ);
            }
            for (int ij = 0; ij < NJ; ij++) {
                String name = in.readString();
                u.readJobSummaryFromParcelLocked(name, in);
            }

            u.readJobCompletionsFromParcelLocked(in);

            u.mJobsDeferredEventCount.readSummaryFromParcelLocked(in);
            u.mJobsDeferredCount.readSummaryFromParcelLocked(in);
            u.mJobsFreshnessTimeMs.readSummaryFromParcelLocked(in);
            detachIfNotNull(u.mJobsFreshnessBuckets);
            for (int i = 0; i < JOB_FRESHNESS_BUCKETS.length; i++) {
                if (in.readInt() != 0) {
                    u.mJobsFreshnessBuckets[i] = new Counter(u.mBsi.mOnBatteryTimeBase);
                    u.mJobsFreshnessBuckets[i].readSummaryFromParcelLocked(in);
                }
            }

            int NP = in.readInt();
            if (NP > 1000) {
                throw new ParcelFormatException("File corrupt: too many sensors " + NP);
            }
            for (int is = 0; is < NP; is++) {
                int seNumber = in.readInt();
                if (in.readInt() != 0) {
                    u.getSensorTimerLocked(seNumber, true).readSummaryFromParcelLocked(in);
                }
            }

            NP = in.readInt();
            if (NP > 1000) {
                throw new ParcelFormatException("File corrupt: too many processes " + NP);
            }
            for (int ip = 0; ip < NP; ip++) {
                String procName = in.readString();
                Uid.Proc p = u.getProcessStatsLocked(procName);
                p.mUserTime = in.readLong();
                p.mSystemTime = in.readLong();
                p.mForegroundTime = in.readLong();
                p.mStarts = in.readInt();
                p.mNumCrashes = in.readInt();
                p.mNumAnrs = in.readInt();
                p.readExcessivePowerFromParcelLocked(in);
            }

            NP = in.readInt();
            if (NP > 10000) {
                throw new ParcelFormatException("File corrupt: too many packages " + NP);
            }
            for (int ip = 0; ip < NP; ip++) {
                String pkgName = in.readString();
                detachIfNotNull(u.mPackageStats.get(pkgName));
                Uid.Pkg p = u.getPackageStatsLocked(pkgName);
                final int NWA = in.readInt();
                if (NWA > 10000) {
                    throw new ParcelFormatException("File corrupt: too many wakeup alarms " + NWA);
                }
                p.mWakeupAlarms.clear();
                for (int iwa = 0; iwa < NWA; iwa++) {
                    String tag = in.readString();
                    Counter c = new Counter(mOnBatteryScreenOffTimeBase);
                    c.readSummaryFromParcelLocked(in);
                    p.mWakeupAlarms.put(tag, c);
                }
                NS = in.readInt();
                if (NS > 10000) {
                    throw new ParcelFormatException("File corrupt: too many services " + NS);
                }
                for (int is = 0; is < NS; is++) {
                    String servName = in.readString();
                    Uid.Pkg.Serv s = u.getServiceStatsLocked(pkgName, servName);
                    s.mStartTime = in.readLong();
                    s.mStarts = in.readInt();
                    s.mLaunches = in.readInt();
                }
            }
        }
    }

    /**
     * Writes a summary of the statistics to a Parcel, in a format suitable to be written to
     * disk.  This format does not allow a lossless round-trip.
     *
     * @param out the Parcel to be written to.
     */
    public void writeSummaryToParcel(Parcel out, boolean inclHistory) {
        pullPendingStateUpdatesLocked();

        // Pull the clock time.  This may update the time and make a new history entry
        // if we had originally pulled a time before the RTC was set.
        getStartClockTime();

        final long NOW_SYS = mClocks.uptimeMillis() * 1000;
        final long NOWREAL_SYS = mClocks.elapsedRealtime() * 1000;

        out.writeInt(VERSION);

        out.writeBoolean(inclHistory);
        if (inclHistory) {
            writeHistoryBuffer(out, true, true);
            mBatteryStatsHistory.writeToParcel(out);
        }

        out.writeInt(mHistoryTagPool.size());
        for (HashMap.Entry<HistoryTag, Integer> ent : mHistoryTagPool.entrySet()) {
            HistoryTag tag = ent.getKey();
            out.writeInt(ent.getValue());
            out.writeString(tag.string);
            out.writeInt(tag.uid);
        }

        out.writeInt(mStartCount);
        out.writeLong(computeUptime(NOW_SYS, STATS_SINCE_CHARGED));
        out.writeLong(computeRealtime(NOWREAL_SYS, STATS_SINCE_CHARGED));
        out.writeLong(mStartClockTime);
        out.writeString(mStartPlatformVersion);
        out.writeString(mEndPlatformVersion);
        mOnBatteryTimeBase.writeSummaryToParcel(out, NOW_SYS, NOWREAL_SYS);
        mOnBatteryScreenOffTimeBase.writeSummaryToParcel(out, NOW_SYS, NOWREAL_SYS);
        out.writeInt(mDischargeUnplugLevel);
        out.writeInt(mDischargePlugLevel);
        out.writeInt(mDischargeCurrentLevel);
        out.writeInt(mCurrentBatteryLevel);
        out.writeInt(mEstimatedBatteryCapacity);
        out.writeInt(mMinLearnedBatteryCapacity);
        out.writeInt(mMaxLearnedBatteryCapacity);
        out.writeInt(getLowDischargeAmountSinceCharge());
        out.writeInt(getHighDischargeAmountSinceCharge());
        out.writeInt(getDischargeAmountScreenOnSinceCharge());
        out.writeInt(getDischargeAmountScreenOffSinceCharge());
        out.writeInt(getDischargeAmountScreenDozeSinceCharge());
        mDischargeStepTracker.writeToParcel(out);
        mChargeStepTracker.writeToParcel(out);
        mDailyDischargeStepTracker.writeToParcel(out);
        mDailyChargeStepTracker.writeToParcel(out);
        mDischargeCounter.writeSummaryFromParcelLocked(out);
        mDischargeScreenOffCounter.writeSummaryFromParcelLocked(out);
        mDischargeScreenDozeCounter.writeSummaryFromParcelLocked(out);
        mDischargeLightDozeCounter.writeSummaryFromParcelLocked(out);
        mDischargeDeepDozeCounter.writeSummaryFromParcelLocked(out);
        if (mDailyPackageChanges != null) {
            final int NPKG = mDailyPackageChanges.size();
            out.writeInt(NPKG);
            for (int i=0; i<NPKG; i++) {
                PackageChange pc = mDailyPackageChanges.get(i);
                out.writeString(pc.mPackageName);
                out.writeInt(pc.mUpdate ? 1 : 0);
                out.writeLong(pc.mVersionCode);
            }
        } else {
            out.writeInt(0);
        }
        out.writeLong(mDailyStartTime);
        out.writeLong(mNextMinDailyDeadline);
        out.writeLong(mNextMaxDailyDeadline);

        mScreenOnTimer.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
        mScreenDozeTimer.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
        for (int i=0; i<NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            mScreenBrightnessTimer[i].writeSummaryFromParcelLocked(out, NOWREAL_SYS);
        }
        mInteractiveTimer.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
        mPowerSaveModeEnabledTimer.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
        out.writeLong(mLongestLightIdleTime);
        out.writeLong(mLongestFullIdleTime);
        mDeviceIdleModeLightTimer.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
        mDeviceIdleModeFullTimer.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
        mDeviceLightIdlingTimer.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
        mDeviceIdlingTimer.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
        mPhoneOnTimer.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
        for (int i=0; i<SignalStrength.NUM_SIGNAL_STRENGTH_BINS; i++) {
            mPhoneSignalStrengthsTimer[i].writeSummaryFromParcelLocked(out, NOWREAL_SYS);
        }
        mPhoneSignalScanningTimer.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
        for (int i=0; i<NUM_DATA_CONNECTION_TYPES; i++) {
            mPhoneDataConnectionsTimer[i].writeSummaryFromParcelLocked(out, NOWREAL_SYS);
        }
        for (int i = 0; i < NUM_NETWORK_ACTIVITY_TYPES; i++) {
            mNetworkByteActivityCounters[i].writeSummaryFromParcelLocked(out);
            mNetworkPacketActivityCounters[i].writeSummaryFromParcelLocked(out);
        }
        mMobileRadioActiveTimer.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
        mMobileRadioActivePerAppTimer.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
        mMobileRadioActiveAdjustedTime.writeSummaryFromParcelLocked(out);
        mMobileRadioActiveUnknownTime.writeSummaryFromParcelLocked(out);
        mMobileRadioActiveUnknownCount.writeSummaryFromParcelLocked(out);
        mWifiMulticastWakelockTimer.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
        mWifiOnTimer.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
        mGlobalWifiRunningTimer.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
        for (int i=0; i<NUM_WIFI_STATES; i++) {
            mWifiStateTimer[i].writeSummaryFromParcelLocked(out, NOWREAL_SYS);
        }
        for (int i=0; i<NUM_WIFI_SUPPL_STATES; i++) {
            mWifiSupplStateTimer[i].writeSummaryFromParcelLocked(out, NOWREAL_SYS);
        }
        for (int i=0; i<NUM_WIFI_SIGNAL_STRENGTH_BINS; i++) {
            mWifiSignalStrengthsTimer[i].writeSummaryFromParcelLocked(out, NOWREAL_SYS);
        }
        mWifiActiveTimer.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
        mWifiActivity.writeSummaryToParcel(out);
        for (int i=0; i< GnssMetrics.NUM_GPS_SIGNAL_QUALITY_LEVELS; i++) {
            mGpsSignalQualityTimer[i].writeSummaryFromParcelLocked(out, NOWREAL_SYS);
        }
        mBluetoothActivity.writeSummaryToParcel(out);
        mModemActivity.writeSummaryToParcel(out);
        out.writeInt(mHasWifiReporting ? 1 : 0);
        out.writeInt(mHasBluetoothReporting ? 1 : 0);
        out.writeInt(mHasModemReporting ? 1 : 0);

        out.writeInt(mNumConnectivityChange);
        mFlashlightOnTimer.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
        mCameraOnTimer.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
        mBluetoothScanTimer.writeSummaryFromParcelLocked(out, NOWREAL_SYS);

        out.writeInt(mRpmStats.size());
        for (Map.Entry<String, SamplingTimer> ent : mRpmStats.entrySet()) {
            Timer rpmt = ent.getValue();
            if (rpmt != null) {
                out.writeInt(1);
                out.writeString(ent.getKey());
                rpmt.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
            } else {
                out.writeInt(0);
            }
        }
        out.writeInt(mScreenOffRpmStats.size());
        for (Map.Entry<String, SamplingTimer> ent : mScreenOffRpmStats.entrySet()) {
            Timer rpmt = ent.getValue();
            if (rpmt != null) {
                out.writeInt(1);
                out.writeString(ent.getKey());
                rpmt.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
            } else {
                out.writeInt(0);
            }
        }

        out.writeInt(mKernelWakelockStats.size());
        for (Map.Entry<String, SamplingTimer> ent : mKernelWakelockStats.entrySet()) {
            Timer kwlt = ent.getValue();
            if (kwlt != null) {
                out.writeInt(1);
                out.writeString(ent.getKey());
                kwlt.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
            } else {
                out.writeInt(0);
            }
        }

        out.writeInt(mWakeupReasonStats.size());
        for (Map.Entry<String, SamplingTimer> ent : mWakeupReasonStats.entrySet()) {
            SamplingTimer timer = ent.getValue();
            if (timer != null) {
                out.writeInt(1);
                out.writeString(ent.getKey());
                timer.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
            } else {
                out.writeInt(0);
            }
        }

        out.writeInt(mKernelMemoryStats.size());
        for (int i = 0; i < mKernelMemoryStats.size(); i++) {
            Timer kmt = mKernelMemoryStats.valueAt(i);
            if (kmt != null) {
                out.writeInt(1);
                out.writeLong(mKernelMemoryStats.keyAt(i));
                kmt.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
            } else {
                out.writeInt(0);
            }
        }

        final int NU = mUidStats.size();
        out.writeInt(NU);
        for (int iu = 0; iu < NU; iu++) {
            out.writeInt(mUidStats.keyAt(iu));
            Uid u = mUidStats.valueAt(iu);

            u.mOnBatteryBackgroundTimeBase.writeSummaryToParcel(out, NOW_SYS, NOWREAL_SYS);
            u.mOnBatteryScreenOffBackgroundTimeBase.writeSummaryToParcel(out, NOW_SYS, NOWREAL_SYS);

            if (u.mWifiRunningTimer != null) {
                out.writeInt(1);
                u.mWifiRunningTimer.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
            } else {
                out.writeInt(0);
            }
            if (u.mFullWifiLockTimer != null) {
                out.writeInt(1);
                u.mFullWifiLockTimer.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
            } else {
                out.writeInt(0);
            }
            if (u.mWifiScanTimer != null) {
                out.writeInt(1);
                u.mWifiScanTimer.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
            } else {
                out.writeInt(0);
            }
            for (int i = 0; i < Uid.NUM_WIFI_BATCHED_SCAN_BINS; i++) {
                if (u.mWifiBatchedScanTimer[i] != null) {
                    out.writeInt(1);
                    u.mWifiBatchedScanTimer[i].writeSummaryFromParcelLocked(out, NOWREAL_SYS);
                } else {
                    out.writeInt(0);
                }
            }
            if (u.mWifiMulticastTimer != null) {
                out.writeInt(1);
                u.mWifiMulticastTimer.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
            } else {
                out.writeInt(0);
            }
            if (u.mAudioTurnedOnTimer != null) {
                out.writeInt(1);
                u.mAudioTurnedOnTimer.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
            } else {
                out.writeInt(0);
            }
            if (u.mVideoTurnedOnTimer != null) {
                out.writeInt(1);
                u.mVideoTurnedOnTimer.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
            } else {
                out.writeInt(0);
            }
            if (u.mFlashlightTurnedOnTimer != null) {
                out.writeInt(1);
                u.mFlashlightTurnedOnTimer.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
            } else {
                out.writeInt(0);
            }
            if (u.mCameraTurnedOnTimer != null) {
                out.writeInt(1);
                u.mCameraTurnedOnTimer.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
            } else {
                out.writeInt(0);
            }
            if (u.mForegroundActivityTimer != null) {
                out.writeInt(1);
                u.mForegroundActivityTimer.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
            } else {
                out.writeInt(0);
            }
            if (u.mForegroundServiceTimer != null) {
                out.writeInt(1);
                u.mForegroundServiceTimer.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
            } else {
                out.writeInt(0);
            }
            if (u.mAggregatedPartialWakelockTimer != null) {
                out.writeInt(1);
                u.mAggregatedPartialWakelockTimer.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
            } else {
                out.writeInt(0);
            }
            if (u.mBluetoothScanTimer != null) {
                out.writeInt(1);
                u.mBluetoothScanTimer.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
            } else {
                out.writeInt(0);
            }
            if (u.mBluetoothUnoptimizedScanTimer != null) {
                out.writeInt(1);
                u.mBluetoothUnoptimizedScanTimer.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
            } else {
                out.writeInt(0);
            }
            if (u.mBluetoothScanResultCounter != null) {
                out.writeInt(1);
                u.mBluetoothScanResultCounter.writeSummaryFromParcelLocked(out);
            } else {
                out.writeInt(0);
            }
            if (u.mBluetoothScanResultBgCounter != null) {
                out.writeInt(1);
                u.mBluetoothScanResultBgCounter.writeSummaryFromParcelLocked(out);
            } else {
                out.writeInt(0);
            }
            for (int i = 0; i < Uid.NUM_PROCESS_STATE; i++) {
                if (u.mProcessStateTimer[i] != null) {
                    out.writeInt(1);
                    u.mProcessStateTimer[i].writeSummaryFromParcelLocked(out, NOWREAL_SYS);
                } else {
                    out.writeInt(0);
                }
            }
            if (u.mVibratorOnTimer != null) {
                out.writeInt(1);
                u.mVibratorOnTimer.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
            } else {
                out.writeInt(0);
            }

            if (u.mUserActivityCounters == null) {
                out.writeInt(0);
            } else {
                out.writeInt(1);
                for (int i=0; i<Uid.NUM_USER_ACTIVITY_TYPES; i++) {
                    u.mUserActivityCounters[i].writeSummaryFromParcelLocked(out);
                }
            }

            if (u.mNetworkByteActivityCounters == null) {
                out.writeInt(0);
            } else {
                out.writeInt(1);
                for (int i = 0; i < NUM_NETWORK_ACTIVITY_TYPES; i++) {
                    u.mNetworkByteActivityCounters[i].writeSummaryFromParcelLocked(out);
                    u.mNetworkPacketActivityCounters[i].writeSummaryFromParcelLocked(out);
                }
                u.mMobileRadioActiveTime.writeSummaryFromParcelLocked(out);
                u.mMobileRadioActiveCount.writeSummaryFromParcelLocked(out);
            }

            u.mUserCpuTime.writeSummaryFromParcelLocked(out);
            u.mSystemCpuTime.writeSummaryFromParcelLocked(out);

            if (u.mCpuClusterSpeedTimesUs != null) {
                out.writeInt(1);
                out.writeInt(u.mCpuClusterSpeedTimesUs.length);
                for (LongSamplingCounter[] cpuSpeeds : u.mCpuClusterSpeedTimesUs) {
                    if (cpuSpeeds != null) {
                        out.writeInt(1);
                        out.writeInt(cpuSpeeds.length);
                        for (LongSamplingCounter c : cpuSpeeds) {
                            if (c != null) {
                                out.writeInt(1);
                                c.writeSummaryFromParcelLocked(out);
                            } else {
                                out.writeInt(0);
                            }
                        }
                    } else {
                        out.writeInt(0);
                    }
                }
            } else {
                out.writeInt(0);
            }

            LongSamplingCounterArray.writeSummaryToParcelLocked(out, u.mCpuFreqTimeMs);
            LongSamplingCounterArray.writeSummaryToParcelLocked(out, u.mScreenOffCpuFreqTimeMs);

            u.mCpuActiveTimeMs.writeSummaryFromParcelLocked(out);
            u.mCpuClusterTimesMs.writeSummaryToParcelLocked(out);

            if (u.mProcStateTimeMs != null) {
                out.writeInt(u.mProcStateTimeMs.length);
                for (LongSamplingCounterArray counters : u.mProcStateTimeMs) {
                    LongSamplingCounterArray.writeSummaryToParcelLocked(out, counters);
                }
            } else {
                out.writeInt(0);
            }
            if (u.mProcStateScreenOffTimeMs != null) {
                out.writeInt(u.mProcStateScreenOffTimeMs.length);
                for (LongSamplingCounterArray counters : u.mProcStateScreenOffTimeMs) {
                    LongSamplingCounterArray.writeSummaryToParcelLocked(out, counters);
                }
            } else {
                out.writeInt(0);
            }

            if (u.mMobileRadioApWakeupCount != null) {
                out.writeInt(1);
                u.mMobileRadioApWakeupCount.writeSummaryFromParcelLocked(out);
            } else {
                out.writeInt(0);
            }

            if (u.mWifiRadioApWakeupCount != null) {
                out.writeInt(1);
                u.mWifiRadioApWakeupCount.writeSummaryFromParcelLocked(out);
            } else {
                out.writeInt(0);
            }

            final ArrayMap<String, Uid.Wakelock> wakeStats = u.mWakelockStats.getMap();
            int NW = wakeStats.size();
            out.writeInt(NW);
            for (int iw=0; iw<NW; iw++) {
                out.writeString(wakeStats.keyAt(iw));
                Uid.Wakelock wl = wakeStats.valueAt(iw);
                if (wl.mTimerFull != null) {
                    out.writeInt(1);
                    wl.mTimerFull.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
                } else {
                    out.writeInt(0);
                }
                if (wl.mTimerPartial != null) {
                    out.writeInt(1);
                    wl.mTimerPartial.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
                } else {
                    out.writeInt(0);
                }
                if (wl.mTimerWindow != null) {
                    out.writeInt(1);
                    wl.mTimerWindow.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
                } else {
                    out.writeInt(0);
                }
                if (wl.mTimerDraw != null) {
                    out.writeInt(1);
                    wl.mTimerDraw.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
                } else {
                    out.writeInt(0);
                }
            }

            final ArrayMap<String, DualTimer> syncStats = u.mSyncStats.getMap();
            int NS = syncStats.size();
            out.writeInt(NS);
            for (int is=0; is<NS; is++) {
                out.writeString(syncStats.keyAt(is));
                syncStats.valueAt(is).writeSummaryFromParcelLocked(out, NOWREAL_SYS);
            }

            final ArrayMap<String, DualTimer> jobStats = u.mJobStats.getMap();
            int NJ = jobStats.size();
            out.writeInt(NJ);
            for (int ij=0; ij<NJ; ij++) {
                out.writeString(jobStats.keyAt(ij));
                jobStats.valueAt(ij).writeSummaryFromParcelLocked(out, NOWREAL_SYS);
            }

            u.writeJobCompletionsToParcelLocked(out);

            u.mJobsDeferredEventCount.writeSummaryFromParcelLocked(out);
            u.mJobsDeferredCount.writeSummaryFromParcelLocked(out);
            u.mJobsFreshnessTimeMs.writeSummaryFromParcelLocked(out);
            for (int i = 0; i < JOB_FRESHNESS_BUCKETS.length; i++) {
                if (u.mJobsFreshnessBuckets[i] != null) {
                    out.writeInt(1);
                    u.mJobsFreshnessBuckets[i].writeSummaryFromParcelLocked(out);
                } else {
                    out.writeInt(0);
                }
            }

            int NSE = u.mSensorStats.size();
            out.writeInt(NSE);
            for (int ise=0; ise<NSE; ise++) {
                out.writeInt(u.mSensorStats.keyAt(ise));
                Uid.Sensor se = u.mSensorStats.valueAt(ise);
                if (se.mTimer != null) {
                    out.writeInt(1);
                    se.mTimer.writeSummaryFromParcelLocked(out, NOWREAL_SYS);
                } else {
                    out.writeInt(0);
                }
            }

            int NP = u.mProcessStats.size();
            out.writeInt(NP);
            for (int ip=0; ip<NP; ip++) {
                out.writeString(u.mProcessStats.keyAt(ip));
                Uid.Proc ps = u.mProcessStats.valueAt(ip);
                out.writeLong(ps.mUserTime);
                out.writeLong(ps.mSystemTime);
                out.writeLong(ps.mForegroundTime);
                out.writeInt(ps.mStarts);
                out.writeInt(ps.mNumCrashes);
                out.writeInt(ps.mNumAnrs);
                ps.writeExcessivePowerToParcelLocked(out);
            }

            NP = u.mPackageStats.size();
            out.writeInt(NP);
            if (NP > 0) {
                for (Map.Entry<String, BatteryStatsImpl.Uid.Pkg> ent
                    : u.mPackageStats.entrySet()) {
                    out.writeString(ent.getKey());
                    Uid.Pkg ps = ent.getValue();
                    final int NWA = ps.mWakeupAlarms.size();
                    out.writeInt(NWA);
                    for (int iwa=0; iwa<NWA; iwa++) {
                        out.writeString(ps.mWakeupAlarms.keyAt(iwa));
                        ps.mWakeupAlarms.valueAt(iwa).writeSummaryFromParcelLocked(out);
                    }
                    NS = ps.mServiceStats.size();
                    out.writeInt(NS);
                    for (int is=0; is<NS; is++) {
                        out.writeString(ps.mServiceStats.keyAt(is));
                        BatteryStatsImpl.Uid.Pkg.Serv ss = ps.mServiceStats.valueAt(is);
                        long time = ss.getStartTimeToNowLocked(
                                mOnBatteryTimeBase.getUptime(NOW_SYS));
                        out.writeLong(time);
                        out.writeInt(ss.mStarts);
                        out.writeInt(ss.mLaunches);
                    }
                }
            }
        }
    }

    public void readFromParcel(Parcel in) {
        readFromParcelLocked(in);
    }

    void readFromParcelLocked(Parcel in) {
        int magic = in.readInt();
        if (magic != MAGIC) {
            throw new ParcelFormatException("Bad magic number: #" + Integer.toHexString(magic));
        }

        readHistoryBuffer(in, false);
        mBatteryStatsHistory.readFromParcel(in);

        mStartCount = in.readInt();
        mStartClockTime = in.readLong();
        mStartPlatformVersion = in.readString();
        mEndPlatformVersion = in.readString();
        mUptime = in.readLong();
        mUptimeStart = in.readLong();
        mRealtime = in.readLong();
        mRealtimeStart = in.readLong();
        mOnBattery = in.readInt() != 0;
        mEstimatedBatteryCapacity = in.readInt();
        mMinLearnedBatteryCapacity = in.readInt();
        mMaxLearnedBatteryCapacity = in.readInt();
        mOnBatteryInternal = false; // we are no longer really running.
        mOnBatteryTimeBase.readFromParcel(in);
        mOnBatteryScreenOffTimeBase.readFromParcel(in);

        mScreenState = Display.STATE_UNKNOWN;
        mScreenOnTimer = new StopwatchTimer(mClocks, null, -1, null, mOnBatteryTimeBase, in);
        mScreenDozeTimer = new StopwatchTimer(mClocks, null, -1, null, mOnBatteryTimeBase, in);
        for (int i=0; i<NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            mScreenBrightnessTimer[i] = new StopwatchTimer(mClocks, null, -100-i, null,
                    mOnBatteryTimeBase, in);
        }
        mInteractive = false;
        mInteractiveTimer = new StopwatchTimer(mClocks, null, -10, null, mOnBatteryTimeBase, in);
        mPhoneOn = false;
        mPowerSaveModeEnabledTimer = new StopwatchTimer(mClocks, null, -2, null,
                mOnBatteryTimeBase, in);
        mLongestLightIdleTime = in.readLong();
        mLongestFullIdleTime = in.readLong();
        mDeviceIdleModeLightTimer = new StopwatchTimer(mClocks, null, -14, null,
                mOnBatteryTimeBase, in);
        mDeviceIdleModeFullTimer = new StopwatchTimer(mClocks, null, -11, null,
                mOnBatteryTimeBase, in);
        mDeviceLightIdlingTimer = new StopwatchTimer(mClocks, null, -15, null,
                mOnBatteryTimeBase, in);
        mDeviceIdlingTimer = new StopwatchTimer(mClocks, null, -12, null, mOnBatteryTimeBase, in);
        mPhoneOnTimer = new StopwatchTimer(mClocks, null, -3, null, mOnBatteryTimeBase, in);
        for (int i=0; i<SignalStrength.NUM_SIGNAL_STRENGTH_BINS; i++) {
            mPhoneSignalStrengthsTimer[i] = new StopwatchTimer(mClocks, null, -200-i,
                    null, mOnBatteryTimeBase, in);
        }
        mPhoneSignalScanningTimer = new StopwatchTimer(mClocks, null, -200+1, null,
                mOnBatteryTimeBase, in);
        for (int i=0; i<NUM_DATA_CONNECTION_TYPES; i++) {
            mPhoneDataConnectionsTimer[i] = new StopwatchTimer(mClocks, null, -300-i,
                    null, mOnBatteryTimeBase, in);
        }
        for (int i = 0; i < NUM_NETWORK_ACTIVITY_TYPES; i++) {
            mNetworkByteActivityCounters[i] = new LongSamplingCounter(mOnBatteryTimeBase, in);
            mNetworkPacketActivityCounters[i] = new LongSamplingCounter(mOnBatteryTimeBase, in);
        }
        mMobileRadioPowerState = DataConnectionRealTimeInfo.DC_POWER_STATE_LOW;
        mMobileRadioActiveTimer = new StopwatchTimer(mClocks, null, -400, null,
                mOnBatteryTimeBase, in);
        mMobileRadioActivePerAppTimer = new StopwatchTimer(mClocks, null, -401, null,
                mOnBatteryTimeBase, in);
        mMobileRadioActiveAdjustedTime = new LongSamplingCounter(mOnBatteryTimeBase, in);
        mMobileRadioActiveUnknownTime = new LongSamplingCounter(mOnBatteryTimeBase, in);
        mMobileRadioActiveUnknownCount = new LongSamplingCounter(mOnBatteryTimeBase, in);
        mWifiMulticastWakelockTimer = new StopwatchTimer(mClocks, null, -4, null,
                mOnBatteryTimeBase, in);
        mWifiRadioPowerState = DataConnectionRealTimeInfo.DC_POWER_STATE_LOW;
        mWifiOn = false;
        mWifiOnTimer = new StopwatchTimer(mClocks, null, -4, null, mOnBatteryTimeBase, in);
        mGlobalWifiRunning = false;
        mGlobalWifiRunningTimer = new StopwatchTimer(mClocks, null, -5, null,
                mOnBatteryTimeBase, in);
        for (int i=0; i<NUM_WIFI_STATES; i++) {
            mWifiStateTimer[i] = new StopwatchTimer(mClocks, null, -600-i,
                    null, mOnBatteryTimeBase, in);
        }
        for (int i=0; i<NUM_WIFI_SUPPL_STATES; i++) {
            mWifiSupplStateTimer[i] = new StopwatchTimer(mClocks, null, -700-i,
                    null, mOnBatteryTimeBase, in);
        }
        for (int i=0; i<NUM_WIFI_SIGNAL_STRENGTH_BINS; i++) {
            mWifiSignalStrengthsTimer[i] = new StopwatchTimer(mClocks, null, -800-i,
                    null, mOnBatteryTimeBase, in);
        }
        mWifiActiveTimer = new StopwatchTimer(mClocks, null, -900, null,
            mOnBatteryTimeBase, in);
        mWifiActivity = new ControllerActivityCounterImpl(mOnBatteryTimeBase,
                NUM_WIFI_TX_LEVELS, in);
        for (int i=0; i<GnssMetrics.NUM_GPS_SIGNAL_QUALITY_LEVELS; i++) {
            mGpsSignalQualityTimer[i] = new StopwatchTimer(mClocks, null, -1000-i,
                null, mOnBatteryTimeBase, in);
        }
        mBluetoothActivity = new ControllerActivityCounterImpl(mOnBatteryTimeBase,
                NUM_BT_TX_LEVELS, in);
        mModemActivity = new ControllerActivityCounterImpl(mOnBatteryTimeBase,
                ModemActivityInfo.TX_POWER_LEVELS, in);
        mHasWifiReporting = in.readInt() != 0;
        mHasBluetoothReporting = in.readInt() != 0;
        mHasModemReporting = in.readInt() != 0;

        mNumConnectivityChange = in.readInt();
        mAudioOnNesting = 0;
        // TODO: It's likely a mistake that mAudioOnTimer/mVideoOnTimer don't write/read to parcel!
        mAudioOnTimer = new StopwatchTimer(mClocks, null, -7, null, mOnBatteryTimeBase);
        mVideoOnNesting = 0;
        mVideoOnTimer = new StopwatchTimer(mClocks, null, -8, null, mOnBatteryTimeBase);
        mFlashlightOnNesting = 0;
        mFlashlightOnTimer = new StopwatchTimer(mClocks, null, -9, null, mOnBatteryTimeBase, in);
        mCameraOnNesting = 0;
        mCameraOnTimer = new StopwatchTimer(mClocks, null, -13, null, mOnBatteryTimeBase, in);
        mBluetoothScanNesting = 0;
        mBluetoothScanTimer = new StopwatchTimer(mClocks, null, -14, null, mOnBatteryTimeBase, in);
        mDischargeUnplugLevel = in.readInt();
        mDischargePlugLevel = in.readInt();
        mDischargeCurrentLevel = in.readInt();
        mCurrentBatteryLevel = in.readInt();
        mLowDischargeAmountSinceCharge = in.readInt();
        mHighDischargeAmountSinceCharge = in.readInt();
        mDischargeAmountScreenOn = in.readInt();
        mDischargeAmountScreenOnSinceCharge = in.readInt();
        mDischargeAmountScreenOff = in.readInt();
        mDischargeAmountScreenOffSinceCharge = in.readInt();
        mDischargeAmountScreenDoze = in.readInt();
        mDischargeAmountScreenDozeSinceCharge = in.readInt();
        mDischargeStepTracker.readFromParcel(in);
        mChargeStepTracker.readFromParcel(in);
        mDischargeCounter = new LongSamplingCounter(mOnBatteryTimeBase, in);
        mDischargeScreenOffCounter = new LongSamplingCounter(mOnBatteryScreenOffTimeBase, in);
        mDischargeScreenDozeCounter = new LongSamplingCounter(mOnBatteryTimeBase, in);
        mDischargeLightDozeCounter = new LongSamplingCounter(mOnBatteryTimeBase, in);
        mDischargeDeepDozeCounter = new LongSamplingCounter(mOnBatteryTimeBase, in);
        mLastWriteTime = in.readLong();

        mRpmStats.clear();
        int NRPMS = in.readInt();
        for (int irpm = 0; irpm < NRPMS; irpm++) {
            if (in.readInt() != 0) {
                String rpmName = in.readString();
                SamplingTimer rpmt = new SamplingTimer(mClocks, mOnBatteryTimeBase, in);
                mRpmStats.put(rpmName, rpmt);
            }
        }
        mScreenOffRpmStats.clear();
        int NSORPMS = in.readInt();
        for (int irpm = 0; irpm < NSORPMS; irpm++) {
            if (in.readInt() != 0) {
                String rpmName = in.readString();
                SamplingTimer rpmt = new SamplingTimer(mClocks, mOnBatteryScreenOffTimeBase, in);
                mScreenOffRpmStats.put(rpmName, rpmt);
            }
        }

        mKernelWakelockStats.clear();
        int NKW = in.readInt();
        for (int ikw = 0; ikw < NKW; ikw++) {
            if (in.readInt() != 0) {
                String wakelockName = in.readString();
                SamplingTimer kwlt = new SamplingTimer(mClocks, mOnBatteryScreenOffTimeBase, in);
                mKernelWakelockStats.put(wakelockName, kwlt);
            }
        }

        mWakeupReasonStats.clear();
        int NWR = in.readInt();
        for (int iwr = 0; iwr < NWR; iwr++) {
            if (in.readInt() != 0) {
                String reasonName = in.readString();
                SamplingTimer timer = new SamplingTimer(mClocks, mOnBatteryTimeBase, in);
                mWakeupReasonStats.put(reasonName, timer);
            }
        }

        mKernelMemoryStats.clear();
        int nmt = in.readInt();
        for (int imt = 0; imt < nmt; imt++) {
            if (in.readInt() != 0) {
                Long bucket = in.readLong();
                SamplingTimer kmt = new SamplingTimer(mClocks, mOnBatteryTimeBase, in);
                mKernelMemoryStats.put(bucket, kmt);
            }
        }

        mPartialTimers.clear();
        mFullTimers.clear();
        mWindowTimers.clear();
        mWifiRunningTimers.clear();
        mFullWifiLockTimers.clear();
        mWifiScanTimers.clear();
        mWifiBatchedScanTimers.clear();
        mWifiMulticastTimers.clear();
        mAudioTurnedOnTimers.clear();
        mVideoTurnedOnTimers.clear();
        mFlashlightTurnedOnTimers.clear();
        mCameraTurnedOnTimers.clear();

        int numUids = in.readInt();
        mUidStats.clear();
        for (int i = 0; i < numUids; i++) {
            int uid = in.readInt();
            Uid u = new Uid(this, uid);
            u.readFromParcelLocked(mOnBatteryTimeBase, mOnBatteryScreenOffTimeBase, in);
            mUidStats.append(uid, u);
        }
    }

    public void writeToParcel(Parcel out, int flags) {
        writeToParcelLocked(out, true, flags);
    }

    public void writeToParcelWithoutUids(Parcel out, int flags) {
        writeToParcelLocked(out, false, flags);
    }

    @SuppressWarnings("unused")
    void writeToParcelLocked(Parcel out, boolean inclUids, int flags) {
        // Need to update with current kernel wake lock counts.
        pullPendingStateUpdatesLocked();

        // Pull the clock time.  This may update the time and make a new history entry
        // if we had originally pulled a time before the RTC was set.
        getStartClockTime();

        final long uSecUptime = mClocks.uptimeMillis() * 1000;
        final long uSecRealtime = mClocks.elapsedRealtime() * 1000;
        final long batteryRealtime = mOnBatteryTimeBase.getRealtime(uSecRealtime);
        final long batteryScreenOffRealtime = mOnBatteryScreenOffTimeBase.getRealtime(uSecRealtime);

        out.writeInt(MAGIC);

        writeHistoryBuffer(out, true, false);
        mBatteryStatsHistory.writeToParcel(out);

        out.writeInt(mStartCount);
        out.writeLong(mStartClockTime);
        out.writeString(mStartPlatformVersion);
        out.writeString(mEndPlatformVersion);
        out.writeLong(mUptime);
        out.writeLong(mUptimeStart);
        out.writeLong(mRealtime);
        out.writeLong(mRealtimeStart);
        out.writeInt(mOnBattery ? 1 : 0);
        out.writeInt(mEstimatedBatteryCapacity);
        out.writeInt(mMinLearnedBatteryCapacity);
        out.writeInt(mMaxLearnedBatteryCapacity);
        mOnBatteryTimeBase.writeToParcel(out, uSecUptime, uSecRealtime);
        mOnBatteryScreenOffTimeBase.writeToParcel(out, uSecUptime, uSecRealtime);

        mScreenOnTimer.writeToParcel(out, uSecRealtime);
        mScreenDozeTimer.writeToParcel(out, uSecRealtime);
        for (int i=0; i<NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            mScreenBrightnessTimer[i].writeToParcel(out, uSecRealtime);
        }
        mInteractiveTimer.writeToParcel(out, uSecRealtime);
        mPowerSaveModeEnabledTimer.writeToParcel(out, uSecRealtime);
        out.writeLong(mLongestLightIdleTime);
        out.writeLong(mLongestFullIdleTime);
        mDeviceIdleModeLightTimer.writeToParcel(out, uSecRealtime);
        mDeviceIdleModeFullTimer.writeToParcel(out, uSecRealtime);
        mDeviceLightIdlingTimer.writeToParcel(out, uSecRealtime);
        mDeviceIdlingTimer.writeToParcel(out, uSecRealtime);
        mPhoneOnTimer.writeToParcel(out, uSecRealtime);
        for (int i=0; i<SignalStrength.NUM_SIGNAL_STRENGTH_BINS; i++) {
            mPhoneSignalStrengthsTimer[i].writeToParcel(out, uSecRealtime);
        }
        mPhoneSignalScanningTimer.writeToParcel(out, uSecRealtime);
        for (int i=0; i<NUM_DATA_CONNECTION_TYPES; i++) {
            mPhoneDataConnectionsTimer[i].writeToParcel(out, uSecRealtime);
        }
        for (int i = 0; i < NUM_NETWORK_ACTIVITY_TYPES; i++) {
            mNetworkByteActivityCounters[i].writeToParcel(out);
            mNetworkPacketActivityCounters[i].writeToParcel(out);
        }
        mMobileRadioActiveTimer.writeToParcel(out, uSecRealtime);
        mMobileRadioActivePerAppTimer.writeToParcel(out, uSecRealtime);
        mMobileRadioActiveAdjustedTime.writeToParcel(out);
        mMobileRadioActiveUnknownTime.writeToParcel(out);
        mMobileRadioActiveUnknownCount.writeToParcel(out);
        mWifiMulticastWakelockTimer.writeToParcel(out, uSecRealtime);
        mWifiOnTimer.writeToParcel(out, uSecRealtime);
        mGlobalWifiRunningTimer.writeToParcel(out, uSecRealtime);
        for (int i=0; i<NUM_WIFI_STATES; i++) {
            mWifiStateTimer[i].writeToParcel(out, uSecRealtime);
        }
        for (int i=0; i<NUM_WIFI_SUPPL_STATES; i++) {
            mWifiSupplStateTimer[i].writeToParcel(out, uSecRealtime);
        }
        for (int i=0; i<NUM_WIFI_SIGNAL_STRENGTH_BINS; i++) {
            mWifiSignalStrengthsTimer[i].writeToParcel(out, uSecRealtime);
        }
        mWifiActiveTimer.writeToParcel(out, uSecRealtime);
        mWifiActivity.writeToParcel(out, 0);
        for (int i=0; i< GnssMetrics.NUM_GPS_SIGNAL_QUALITY_LEVELS; i++) {
            mGpsSignalQualityTimer[i].writeToParcel(out, uSecRealtime);
        }
        mBluetoothActivity.writeToParcel(out, 0);
        mModemActivity.writeToParcel(out, 0);
        out.writeInt(mHasWifiReporting ? 1 : 0);
        out.writeInt(mHasBluetoothReporting ? 1 : 0);
        out.writeInt(mHasModemReporting ? 1 : 0);

        out.writeInt(mNumConnectivityChange);
        mFlashlightOnTimer.writeToParcel(out, uSecRealtime);
        mCameraOnTimer.writeToParcel(out, uSecRealtime);
        mBluetoothScanTimer.writeToParcel(out, uSecRealtime);
        out.writeInt(mDischargeUnplugLevel);
        out.writeInt(mDischargePlugLevel);
        out.writeInt(mDischargeCurrentLevel);
        out.writeInt(mCurrentBatteryLevel);
        out.writeInt(mLowDischargeAmountSinceCharge);
        out.writeInt(mHighDischargeAmountSinceCharge);
        out.writeInt(mDischargeAmountScreenOn);
        out.writeInt(mDischargeAmountScreenOnSinceCharge);
        out.writeInt(mDischargeAmountScreenOff);
        out.writeInt(mDischargeAmountScreenOffSinceCharge);
        out.writeInt(mDischargeAmountScreenDoze);
        out.writeInt(mDischargeAmountScreenDozeSinceCharge);
        mDischargeStepTracker.writeToParcel(out);
        mChargeStepTracker.writeToParcel(out);
        mDischargeCounter.writeToParcel(out);
        mDischargeScreenOffCounter.writeToParcel(out);
        mDischargeScreenDozeCounter.writeToParcel(out);
        mDischargeLightDozeCounter.writeToParcel(out);
        mDischargeDeepDozeCounter.writeToParcel(out);
        out.writeLong(mLastWriteTime);

        out.writeInt(mRpmStats.size());
        for (Map.Entry<String, SamplingTimer> ent : mRpmStats.entrySet()) {
            SamplingTimer rpmt = ent.getValue();
            if (rpmt != null) {
                out.writeInt(1);
                out.writeString(ent.getKey());
                rpmt.writeToParcel(out, uSecRealtime);
            } else {
                out.writeInt(0);
            }
        }
        out.writeInt(mScreenOffRpmStats.size());
        for (Map.Entry<String, SamplingTimer> ent : mScreenOffRpmStats.entrySet()) {
            SamplingTimer rpmt = ent.getValue();
            if (rpmt != null) {
                out.writeInt(1);
                out.writeString(ent.getKey());
                rpmt.writeToParcel(out, uSecRealtime);
            } else {
                out.writeInt(0);
            }
        }

        if (inclUids) {
            out.writeInt(mKernelWakelockStats.size());
            for (Map.Entry<String, SamplingTimer> ent : mKernelWakelockStats.entrySet()) {
                SamplingTimer kwlt = ent.getValue();
                if (kwlt != null) {
                    out.writeInt(1);
                    out.writeString(ent.getKey());
                    kwlt.writeToParcel(out, uSecRealtime);
                } else {
                    out.writeInt(0);
                }
            }
            out.writeInt(mWakeupReasonStats.size());
            for (Map.Entry<String, SamplingTimer> ent : mWakeupReasonStats.entrySet()) {
                SamplingTimer timer = ent.getValue();
                if (timer != null) {
                    out.writeInt(1);
                    out.writeString(ent.getKey());
                    timer.writeToParcel(out, uSecRealtime);
                } else {
                    out.writeInt(0);
                }
            }
        } else {
            out.writeInt(0);
            out.writeInt(0);
        }

        out.writeInt(mKernelMemoryStats.size());
        for (int i = 0; i < mKernelMemoryStats.size(); i++) {
            SamplingTimer kmt = mKernelMemoryStats.valueAt(i);
            if (kmt != null) {
                out.writeInt(1);
                out.writeLong(mKernelMemoryStats.keyAt(i));
                kmt.writeToParcel(out, uSecRealtime);
            } else {
                out.writeInt(0);
            }
        }

        if (inclUids) {
            int size = mUidStats.size();
            out.writeInt(size);
            for (int i = 0; i < size; i++) {
                out.writeInt(mUidStats.keyAt(i));
                Uid uid = mUidStats.valueAt(i);

                uid.writeToParcelLocked(out, uSecUptime, uSecRealtime);
            }
        } else {
            out.writeInt(0);
        }
    }

    @UnsupportedAppUsage
    public static final Parcelable.Creator<BatteryStatsImpl> CREATOR =
        new Parcelable.Creator<BatteryStatsImpl>() {
        public BatteryStatsImpl createFromParcel(Parcel in) {
            return new BatteryStatsImpl(in);
        }

        public BatteryStatsImpl[] newArray(int size) {
            return new BatteryStatsImpl[size];
        }
    };

    public void prepareForDumpLocked() {
        // Need to retrieve current kernel wake lock stats before printing.
        pullPendingStateUpdatesLocked();

        // Pull the clock time.  This may update the time and make a new history entry
        // if we had originally pulled a time before the RTC was set.
        getStartClockTime();
    }

    public void dumpLocked(Context context, PrintWriter pw, int flags, int reqUid, long histStart) {
        if (DEBUG) {
            pw.println("mOnBatteryTimeBase:");
            mOnBatteryTimeBase.dump(pw, "  ");
            pw.println("mOnBatteryScreenOffTimeBase:");
            mOnBatteryScreenOffTimeBase.dump(pw, "  ");
            Printer pr = new PrintWriterPrinter(pw);
            pr.println("*** Screen on timer:");
            mScreenOnTimer.logState(pr, "  ");
            pr.println("*** Screen doze timer:");
            mScreenDozeTimer.logState(pr, "  ");
            for (int i=0; i<NUM_SCREEN_BRIGHTNESS_BINS; i++) {
                pr.println("*** Screen brightness #" + i + ":");
                mScreenBrightnessTimer[i].logState(pr, "  ");
            }
            pr.println("*** Interactive timer:");
            mInteractiveTimer.logState(pr, "  ");
            pr.println("*** Power save mode timer:");
            mPowerSaveModeEnabledTimer.logState(pr, "  ");
            pr.println("*** Device idle mode light timer:");
            mDeviceIdleModeLightTimer.logState(pr, "  ");
            pr.println("*** Device idle mode full timer:");
            mDeviceIdleModeFullTimer.logState(pr, "  ");
            pr.println("*** Device light idling timer:");
            mDeviceLightIdlingTimer.logState(pr, "  ");
            pr.println("*** Device idling timer:");
            mDeviceIdlingTimer.logState(pr, "  ");
            pr.println("*** Phone timer:");
            mPhoneOnTimer.logState(pr, "  ");
            for (int i=0; i<SignalStrength.NUM_SIGNAL_STRENGTH_BINS; i++) {
                pr.println("*** Phone signal strength #" + i + ":");
                mPhoneSignalStrengthsTimer[i].logState(pr, "  ");
            }
            pr.println("*** Signal scanning :");
            mPhoneSignalScanningTimer.logState(pr, "  ");
            for (int i=0; i<NUM_DATA_CONNECTION_TYPES; i++) {
                pr.println("*** Data connection type #" + i + ":");
                mPhoneDataConnectionsTimer[i].logState(pr, "  ");
            }
            pr.println("*** mMobileRadioPowerState=" + mMobileRadioPowerState);
            pr.println("*** Mobile network active timer:");
            mMobileRadioActiveTimer.logState(pr, "  ");
            pr.println("*** Mobile network active adjusted timer:");
            mMobileRadioActiveAdjustedTime.logState(pr, "  ");
            pr.println("*** Wifi Multicast WakeLock Timer:");
            mWifiMulticastWakelockTimer.logState(pr, "  ");
            pr.println("*** mWifiRadioPowerState=" + mWifiRadioPowerState);
            pr.println("*** Wifi timer:");
            mWifiOnTimer.logState(pr, "  ");
            pr.println("*** WifiRunning timer:");
            mGlobalWifiRunningTimer.logState(pr, "  ");
            for (int i=0; i<NUM_WIFI_STATES; i++) {
                pr.println("*** Wifi state #" + i + ":");
                mWifiStateTimer[i].logState(pr, "  ");
            }
            for (int i=0; i<NUM_WIFI_SUPPL_STATES; i++) {
                pr.println("*** Wifi suppl state #" + i + ":");
                mWifiSupplStateTimer[i].logState(pr, "  ");
            }
            for (int i=0; i<NUM_WIFI_SIGNAL_STRENGTH_BINS; i++) {
                pr.println("*** Wifi signal strength #" + i + ":");
                mWifiSignalStrengthsTimer[i].logState(pr, "  ");
            }
            for (int i=0; i<GnssMetrics.NUM_GPS_SIGNAL_QUALITY_LEVELS; i++) {
                pr.println("*** GPS signal quality #" + i + ":");
                mGpsSignalQualityTimer[i].logState(pr, "  ");
            }
            pr.println("*** Flashlight timer:");
            mFlashlightOnTimer.logState(pr, "  ");
            pr.println("*** Camera timer:");
            mCameraOnTimer.logState(pr, "  ");
        }
        super.dumpLocked(context, pw, flags, reqUid, histStart);
        pw.print("Total cpu time reads: ");
        pw.println(mNumSingleUidCpuTimeReads);
        pw.print("Batched cpu time reads: ");
        pw.println(mNumBatchedSingleUidCpuTimeReads);
        pw.print("Batching Duration (min): ");
        pw.println((mClocks.uptimeMillis() - mCpuTimeReadsTrackingStartTime) / (60 * 1000));
        pw.print("All UID cpu time reads since the later of device start or stats reset: ");
        pw.println(mNumAllUidCpuTimeReads);
        pw.print("UIDs removed since the later of device start or stats reset: ");
        pw.println(mNumUidsRemoved);
    }
}
