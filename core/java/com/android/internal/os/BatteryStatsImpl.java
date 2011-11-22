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

import static android.net.NetworkStats.IFACE_ALL;
import static android.net.NetworkStats.UID_ALL;
import static android.text.format.DateUtils.SECOND_IN_MILLIS;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.NetworkStats;
import android.os.BatteryManager;
import android.os.BatteryStats;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Message;
import android.os.Parcel;
import android.os.ParcelFormatException;
import android.os.Parcelable;
import android.os.Process;
import android.os.SystemClock;
import android.os.WorkSource;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.LogWriter;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;

import com.android.internal.R;
import com.android.internal.net.NetworkStatsFactory;
import com.android.internal.util.JournaledFile;
import com.google.android.collect.Sets;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * All information we are collecting about things that can happen that impact
 * battery life.  All times are represented in microseconds except where indicated
 * otherwise.
 */
public final class BatteryStatsImpl extends BatteryStats {
    private static final String TAG = "BatteryStatsImpl";
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_HISTORY = false;
    private static final boolean USE_OLD_HISTORY = false;   // for debugging.

    // TODO: remove "tcp" from network methods, since we measure total stats.

    // In-memory Parcel magic number, used to detect attempts to unmarshall bad data
    private static final int MAGIC = 0xBA757475; // 'BATSTATS'

    // Current on-disk Parcel version
    private static final int VERSION = 61 + (USE_OLD_HISTORY ? 1000 : 0);

    // Maximum number of items we will record in the history.
    private static final int MAX_HISTORY_ITEMS = 2000;

    // No, really, THIS is the maximum number of items we will record in the history.
    private static final int MAX_MAX_HISTORY_ITEMS = 3000;

    // The maximum number of names wakelocks we will keep track of
    // per uid; once the limit is reached, we batch the remaining wakelocks
    // in to one common name.
    private static final int MAX_WAKELOCKS_PER_UID = 30;

    // The system process gets more.  It is special.  Oh so special.
    // With, you know, special needs.  Like this.
    private static final int MAX_WAKELOCKS_PER_UID_IN_SYSTEM = 50;

    private static final String BATCHED_WAKELOCK_NAME = "*overflow*";

    private static int sNumSpeedSteps;

    private final JournaledFile mFile;

    static final int MSG_UPDATE_WAKELOCKS = 1;
    static final int MSG_REPORT_POWER_CHANGE = 2;
    static final long DELAY_UPDATE_WAKELOCKS = 5*1000;

    public interface BatteryCallback {
        public void batteryNeedsCpuUpdate();
        public void batteryPowerChanged(boolean onBattery);
    }

    final class MyHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            BatteryCallback cb = mCallback;
            switch (msg.what) {
                case MSG_UPDATE_WAKELOCKS:
                    if (cb != null) {
                        cb.batteryNeedsCpuUpdate();
                    }
                    break;
                case MSG_REPORT_POWER_CHANGE:
                    if (cb != null) {
                        cb.batteryPowerChanged(msg.arg1 != 0);
                    }
                    break;
            }
        }
    }

    private final MyHandler mHandler;

    private BatteryCallback mCallback;

    /**
     * The statistics we have collected organized by uids.
     */
    final SparseArray<BatteryStatsImpl.Uid> mUidStats =
        new SparseArray<BatteryStatsImpl.Uid>();

    // A set of pools of currently active timers.  When a timer is queried, we will divide the
    // elapsed time by the number of active timers to arrive at that timer's share of the time.
    // In order to do this, we must refresh each timer whenever the number of active timers
    // changes.
    final ArrayList<StopwatchTimer> mPartialTimers = new ArrayList<StopwatchTimer>();
    final ArrayList<StopwatchTimer> mFullTimers = new ArrayList<StopwatchTimer>();
    final ArrayList<StopwatchTimer> mWindowTimers = new ArrayList<StopwatchTimer>();
    final SparseArray<ArrayList<StopwatchTimer>> mSensorTimers
            = new SparseArray<ArrayList<StopwatchTimer>>();
    final ArrayList<StopwatchTimer> mWifiRunningTimers = new ArrayList<StopwatchTimer>();
    final ArrayList<StopwatchTimer> mFullWifiLockTimers = new ArrayList<StopwatchTimer>();
    final ArrayList<StopwatchTimer> mScanWifiLockTimers = new ArrayList<StopwatchTimer>();
    final ArrayList<StopwatchTimer> mWifiMulticastTimers = new ArrayList<StopwatchTimer>();

    // Last partial timers we use for distributing CPU usage.
    final ArrayList<StopwatchTimer> mLastPartialTimers = new ArrayList<StopwatchTimer>();

    // These are the objects that will want to do something when the device
    // is unplugged from power.
    final ArrayList<Unpluggable> mUnpluggables = new ArrayList<Unpluggable>();

    boolean mShuttingDown;

    long mHistoryBaseTime;
    boolean mHaveBatteryLevel = false;
    boolean mRecordingHistory = true;
    int mNumHistoryItems;

    static final int MAX_HISTORY_BUFFER = 128*1024; // 128KB
    static final int MAX_MAX_HISTORY_BUFFER = 144*1024; // 144KB
    final Parcel mHistoryBuffer = Parcel.obtain();
    final HistoryItem mHistoryLastWritten = new HistoryItem();
    final HistoryItem mHistoryLastLastWritten = new HistoryItem();
    final HistoryItem mHistoryReadTmp = new HistoryItem();
    int mHistoryBufferLastPos = -1;
    boolean mHistoryOverflow = false;
    long mLastHistoryTime = 0;

    final HistoryItem mHistoryCur = new HistoryItem();

    HistoryItem mHistory;
    HistoryItem mHistoryEnd;
    HistoryItem mHistoryLastEnd;
    HistoryItem mHistoryCache;

    private HistoryItem mHistoryIterator;
    private boolean mReadOverflow;
    private boolean mIteratingHistory;

    int mStartCount;

    long mBatteryUptime;
    long mBatteryLastUptime;
    long mBatteryRealtime;
    long mBatteryLastRealtime;

    long mUptime;
    long mUptimeStart;
    long mLastUptime;
    long mRealtime;
    long mRealtimeStart;
    long mLastRealtime;

    boolean mScreenOn;
    StopwatchTimer mScreenOnTimer;

    int mScreenBrightnessBin = -1;
    final StopwatchTimer[] mScreenBrightnessTimer = new StopwatchTimer[NUM_SCREEN_BRIGHTNESS_BINS];

    Counter mInputEventCounter;

    boolean mPhoneOn;
    StopwatchTimer mPhoneOnTimer;

    boolean mAudioOn;
    StopwatchTimer mAudioOnTimer;

    boolean mVideoOn;
    StopwatchTimer mVideoOnTimer;

    int mPhoneSignalStrengthBin = -1;
    int mPhoneSignalStrengthBinRaw = -1;
    final StopwatchTimer[] mPhoneSignalStrengthsTimer = 
            new StopwatchTimer[SignalStrength.NUM_SIGNAL_STRENGTH_BINS];

    StopwatchTimer mPhoneSignalScanningTimer;

    int mPhoneDataConnectionType = -1;
    final StopwatchTimer[] mPhoneDataConnectionsTimer =
            new StopwatchTimer[NUM_DATA_CONNECTION_TYPES];

    boolean mWifiOn;
    StopwatchTimer mWifiOnTimer;
    int mWifiOnUid = -1;

    boolean mGlobalWifiRunning;
    StopwatchTimer mGlobalWifiRunningTimer;

    boolean mBluetoothOn;
    StopwatchTimer mBluetoothOnTimer;

    /** Bluetooth headset object */
    BluetoothHeadset mBtHeadset;

    /**
     * These provide time bases that discount the time the device is plugged
     * in to power.
     */
    boolean mOnBattery;
    boolean mOnBatteryInternal;
    long mTrackBatteryPastUptime;
    long mTrackBatteryUptimeStart;
    long mTrackBatteryPastRealtime;
    long mTrackBatteryRealtimeStart;

    long mUnpluggedBatteryUptime;
    long mUnpluggedBatteryRealtime;

    /*
     * These keep track of battery levels (1-100) at the last plug event and the last unplug event.
     */
    int mDischargeStartLevel;
    int mDischargeUnplugLevel;
    int mDischargeCurrentLevel;
    int mLowDischargeAmountSinceCharge;
    int mHighDischargeAmountSinceCharge;
    int mDischargeScreenOnUnplugLevel;
    int mDischargeScreenOffUnplugLevel;
    int mDischargeAmountScreenOn;
    int mDischargeAmountScreenOnSinceCharge;
    int mDischargeAmountScreenOff;
    int mDischargeAmountScreenOffSinceCharge;

    long mLastWriteTime = 0; // Milliseconds

    // Mobile data transferred while on battery
    private long[] mMobileDataTx = new long[4];
    private long[] mMobileDataRx = new long[4];
    private long[] mTotalDataTx = new long[4];
    private long[] mTotalDataRx = new long[4];

    private long mRadioDataUptime;
    private long mRadioDataStart;

    private int mBluetoothPingCount;
    private int mBluetoothPingStart = -1;

    private int mPhoneServiceState = -1;
    private int mPhoneServiceStateRaw = -1;
    private int mPhoneSimStateRaw = -1;

    /*
     * Holds a SamplingTimer associated with each kernel wakelock name being tracked.
     */
    private final HashMap<String, SamplingTimer> mKernelWakelockStats =
            new HashMap<String, SamplingTimer>();

    public Map<String, ? extends SamplingTimer> getKernelWakelockStats() {
        return mKernelWakelockStats;
    }

    private static int sKernelWakelockUpdateVersion = 0;

    private static final int[] PROC_WAKELOCKS_FORMAT = new int[] {
        Process.PROC_TAB_TERM|Process.PROC_OUT_STRING,                // 0: name
        Process.PROC_TAB_TERM|Process.PROC_OUT_LONG,                  // 1: count
        Process.PROC_TAB_TERM,
        Process.PROC_TAB_TERM,
        Process.PROC_TAB_TERM,
        Process.PROC_TAB_TERM|Process.PROC_OUT_LONG,                  // 5: totalTime
    };

    private final String[] mProcWakelocksName = new String[3];
    private final long[] mProcWakelocksData = new long[3];

    /*
     * Used as a buffer for reading in data from /proc/wakelocks before it is processed and added
     * to mKernelWakelockStats.
     */
    private final Map<String, KernelWakelockStats> mProcWakelockFileStats =
            new HashMap<String, KernelWakelockStats>();

    private HashMap<String, Integer> mUidCache = new HashMap<String, Integer>();

    private final NetworkStatsFactory mNetworkStatsFactory = new NetworkStatsFactory();

    /** Network ifaces that {@link ConnectivityManager} has claimed as mobile. */
    private HashSet<String> mMobileIfaces = Sets.newHashSet();

    // For debugging
    public BatteryStatsImpl() {
        mFile = null;
        mHandler = null;
    }

    public static interface Unpluggable {
        void unplug(long batteryUptime, long batteryRealtime);
        void plug(long batteryUptime, long batteryRealtime);
    }

    /**
     * State for keeping track of counting information.
     */
    public static class Counter extends BatteryStats.Counter implements Unpluggable {
        final AtomicInteger mCount = new AtomicInteger();
        final ArrayList<Unpluggable> mUnpluggables;
        int mLoadedCount;
        int mLastCount;
        int mUnpluggedCount;
        int mPluggedCount;

        Counter(ArrayList<Unpluggable> unpluggables, Parcel in) {
            mUnpluggables = unpluggables;
            mPluggedCount = in.readInt();
            mCount.set(mPluggedCount);
            mLoadedCount = in.readInt();
            mLastCount = 0;
            mUnpluggedCount = in.readInt();
            unpluggables.add(this);
        }

        Counter(ArrayList<Unpluggable> unpluggables) {
            mUnpluggables = unpluggables;
            unpluggables.add(this);
        }

        public void writeToParcel(Parcel out) {
            out.writeInt(mCount.get());
            out.writeInt(mLoadedCount);
            out.writeInt(mUnpluggedCount);
        }

        public void unplug(long batteryUptime, long batteryRealtime) {
            mUnpluggedCount = mPluggedCount;
            mCount.set(mPluggedCount);
        }

        public void plug(long batteryUptime, long batteryRealtime) {
            mPluggedCount = mCount.get();
        }

        /**
         * Writes a possibly null Counter to a Parcel.
         *
         * @param out the Parcel to be written to.
         * @param counter a Counter, or null.
         */
        public static void writeCounterToParcel(Parcel out, Counter counter) {
            if (counter == null) {
                out.writeInt(0); // indicates null
                return;
            }
            out.writeInt(1); // indicates non-null

            counter.writeToParcel(out);
        }

        @Override
        public int getCountLocked(int which) {
            int val;
            if (which == STATS_LAST) {
                val = mLastCount;
            } else {
                val = mCount.get();
                if (which == STATS_SINCE_UNPLUGGED) {
                    val -= mUnpluggedCount;
                } else if (which != STATS_SINCE_CHARGED) {
                    val -= mLoadedCount;
                }
            }

            return val;
        }

        public void logState(Printer pw, String prefix) {
            pw.println(prefix + "mCount=" + mCount.get()
                    + " mLoadedCount=" + mLoadedCount + " mLastCount=" + mLastCount
                    + " mUnpluggedCount=" + mUnpluggedCount
                    + " mPluggedCount=" + mPluggedCount);
        }

        void stepAtomic() {
            mCount.incrementAndGet();
        }

        /**
         * Clear state of this counter.
         */
        void reset(boolean detachIfReset) {
            mCount.set(0);
            mLoadedCount = mLastCount = mPluggedCount = mUnpluggedCount = 0;
            if (detachIfReset) {
                detach();
            }
        }

        void detach() {
            mUnpluggables.remove(this);
        }

        void writeSummaryFromParcelLocked(Parcel out) {
            int count = mCount.get();
            out.writeInt(count);
        }

        void readSummaryFromParcelLocked(Parcel in) {
            mLoadedCount = in.readInt();
            mCount.set(mLoadedCount);
            mLastCount = 0;
            mUnpluggedCount = mPluggedCount = mLoadedCount;
        }
    }

    public static class SamplingCounter extends Counter {

        SamplingCounter(ArrayList<Unpluggable> unpluggables, Parcel in) {
            super(unpluggables, in);
        }

        SamplingCounter(ArrayList<Unpluggable> unpluggables) {
            super(unpluggables);
        }

        public void addCountAtomic(long count) {
            mCount.addAndGet((int)count);
        }
    }

    /**
     * State for keeping track of timing information.
     */
    public static abstract class Timer extends BatteryStats.Timer implements Unpluggable {
        final int mType;
        final ArrayList<Unpluggable> mUnpluggables;

        int mCount;
        int mLoadedCount;
        int mLastCount;
        int mUnpluggedCount;

        // Times are in microseconds for better accuracy when dividing by the
        // lock count, and are in "battery realtime" units.

        /**
         * The total time we have accumulated since the start of the original
         * boot, to the last time something interesting happened in the
         * current run.
         */
        long mTotalTime;

        /**
         * The total time we loaded for the previous runs.  Subtract this from
         * mTotalTime to find the time for the current run of the system.
         */
        long mLoadedTime;

        /**
         * The run time of the last run of the system, as loaded from the
         * saved data.
         */
        long mLastTime;

        /**
         * The value of mTotalTime when unplug() was last called.  Subtract
         * this from mTotalTime to find the time since the last unplug from
         * power.
         */
        long mUnpluggedTime;

        /**
         * Constructs from a parcel.
         * @param type
         * @param unpluggables
         * @param powerType
         * @param in
         */
        Timer(int type, ArrayList<Unpluggable> unpluggables, Parcel in) {
            mType = type;
            mUnpluggables = unpluggables;

            mCount = in.readInt();
            mLoadedCount = in.readInt();
            mLastCount = 0;
            mUnpluggedCount = in.readInt();
            mTotalTime = in.readLong();
            mLoadedTime = in.readLong();
            mLastTime = 0;
            mUnpluggedTime = in.readLong();
            unpluggables.add(this);
        }

        Timer(int type, ArrayList<Unpluggable> unpluggables) {
            mType = type;
            mUnpluggables = unpluggables;
            unpluggables.add(this);
        }

        protected abstract long computeRunTimeLocked(long curBatteryRealtime);

        protected abstract int computeCurrentCountLocked();

        /**
         * Clear state of this timer.  Returns true if the timer is inactive
         * so can be completely dropped.
         */
        boolean reset(BatteryStatsImpl stats, boolean detachIfReset) {
            mTotalTime = mLoadedTime = mLastTime = 0;
            mCount = mLoadedCount = mLastCount = 0;
            if (detachIfReset) {
                detach();
            }
            return true;
        }

        void detach() {
            mUnpluggables.remove(this);
        }

        public void writeToParcel(Parcel out, long batteryRealtime) {
            out.writeInt(mCount);
            out.writeInt(mLoadedCount);
            out.writeInt(mUnpluggedCount);
            out.writeLong(computeRunTimeLocked(batteryRealtime));
            out.writeLong(mLoadedTime);
            out.writeLong(mUnpluggedTime);
        }

        public void unplug(long batteryUptime, long batteryRealtime) {
            if (DEBUG && mType < 0) {
                Log.v(TAG, "unplug #" + mType + ": realtime=" + batteryRealtime
                        + " old mUnpluggedTime=" + mUnpluggedTime
                        + " old mUnpluggedCount=" + mUnpluggedCount);
            }
            mUnpluggedTime = computeRunTimeLocked(batteryRealtime);
            mUnpluggedCount = mCount;
            if (DEBUG && mType < 0) {
                Log.v(TAG, "unplug #" + mType
                        + ": new mUnpluggedTime=" + mUnpluggedTime
                        + " new mUnpluggedCount=" + mUnpluggedCount);
            }
        }

        public void plug(long batteryUptime, long batteryRealtime) {
            if (DEBUG && mType < 0) {
                Log.v(TAG, "plug #" + mType + ": realtime=" + batteryRealtime
                        + " old mTotalTime=" + mTotalTime);
            }
            mTotalTime = computeRunTimeLocked(batteryRealtime);
            mCount = computeCurrentCountLocked();
            if (DEBUG && mType < 0) {
                Log.v(TAG, "plug #" + mType
                        + ": new mTotalTime=" + mTotalTime);
            }
        }

        /**
         * Writes a possibly null Timer to a Parcel.
         *
         * @param out the Parcel to be written to.
         * @param timer a Timer, or null.
         */
        public static void writeTimerToParcel(Parcel out, Timer timer,
                long batteryRealtime) {
            if (timer == null) {
                out.writeInt(0); // indicates null
                return;
            }
            out.writeInt(1); // indicates non-null

            timer.writeToParcel(out, batteryRealtime);
        }

        @Override
        public long getTotalTimeLocked(long batteryRealtime, int which) {
            long val;
            if (which == STATS_LAST) {
                val = mLastTime;
            } else {
                val = computeRunTimeLocked(batteryRealtime);
                if (which == STATS_SINCE_UNPLUGGED) {
                    val -= mUnpluggedTime;
                } else if (which != STATS_SINCE_CHARGED) {
                    val -= mLoadedTime;
                }
            }

            return val;
        }

        @Override
        public int getCountLocked(int which) {
            int val;
            if (which == STATS_LAST) {
                val = mLastCount;
            } else {
                val = computeCurrentCountLocked();
                if (which == STATS_SINCE_UNPLUGGED) {
                    val -= mUnpluggedCount;
                } else if (which != STATS_SINCE_CHARGED) {
                    val -= mLoadedCount;
                }
            }

            return val;
        }

        public void logState(Printer pw, String prefix) {
            pw.println(prefix + " mCount=" + mCount
                    + " mLoadedCount=" + mLoadedCount + " mLastCount=" + mLastCount
                    + " mUnpluggedCount=" + mUnpluggedCount);
            pw.println(prefix + "mTotalTime=" + mTotalTime
                    + " mLoadedTime=" + mLoadedTime);
            pw.println(prefix + "mLastTime=" + mLastTime
                    + " mUnpluggedTime=" + mUnpluggedTime);
        }


        void writeSummaryFromParcelLocked(Parcel out, long batteryRealtime) {
            long runTime = computeRunTimeLocked(batteryRealtime);
            // Divide by 1000 for backwards compatibility
            out.writeLong((runTime + 500) / 1000);
            out.writeInt(mCount);
        }

        void readSummaryFromParcelLocked(Parcel in) {
            // Multiply by 1000 for backwards compatibility
            mTotalTime = mLoadedTime = in.readLong() * 1000;
            mLastTime = 0;
            mUnpluggedTime = mTotalTime;
            mCount = mLoadedCount = in.readInt();
            mLastCount = 0;
            mUnpluggedCount = mCount;
        }
    }

    public static final class SamplingTimer extends Timer {

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
        boolean mInDischarge;

        /**
         * Whether we are currently recording reported values.
         */
        boolean mTrackingReportedValues;

        /*
         * A sequnce counter, incremented once for each update of the stats.
         */
        int mUpdateVersion;

        SamplingTimer(ArrayList<Unpluggable> unpluggables, boolean inDischarge, Parcel in) {
            super(0, unpluggables, in);
            mCurrentReportedCount = in.readInt();
            mUnpluggedReportedCount = in.readInt();
            mCurrentReportedTotalTime = in.readLong();
            mUnpluggedReportedTotalTime = in.readLong();
            mTrackingReportedValues = in.readInt() == 1;
            mInDischarge = inDischarge;
        }

        SamplingTimer(ArrayList<Unpluggable> unpluggables, boolean inDischarge,
                boolean trackReportedValues) {
            super(0, unpluggables);
            mTrackingReportedValues = trackReportedValues;
            mInDischarge = inDischarge;
        }

        public void setStale() {
            mTrackingReportedValues = false;
            mUnpluggedReportedTotalTime = 0;
            mUnpluggedReportedCount = 0;
        }

        public void setUpdateVersion(int version) {
            mUpdateVersion = version;
        }

        public int getUpdateVersion() {
            return mUpdateVersion;
        }

        public void updateCurrentReportedCount(int count) {
            if (mInDischarge && mUnpluggedReportedCount == 0) {
                // Updating the reported value for the first time.
                mUnpluggedReportedCount = count;
                // If we are receiving an update update mTrackingReportedValues;
                mTrackingReportedValues = true;
            }
            mCurrentReportedCount = count;
        }

        public void updateCurrentReportedTotalTime(long totalTime) {
            if (mInDischarge && mUnpluggedReportedTotalTime == 0) {
                // Updating the reported value for the first time.
                mUnpluggedReportedTotalTime = totalTime;
                // If we are receiving an update update mTrackingReportedValues;
                mTrackingReportedValues = true;
            }
            mCurrentReportedTotalTime = totalTime;
        }

        public void unplug(long batteryUptime, long batteryRealtime) {
            super.unplug(batteryUptime, batteryRealtime);
            if (mTrackingReportedValues) {
                mUnpluggedReportedTotalTime = mCurrentReportedTotalTime;
                mUnpluggedReportedCount = mCurrentReportedCount;
            }
            mInDischarge = true;
        }

        public void plug(long batteryUptime, long batteryRealtime) {
            super.plug(batteryUptime, batteryRealtime);
            mInDischarge = false;
        }

        public void logState(Printer pw, String prefix) {
            super.logState(pw, prefix);
            pw.println(prefix + "mCurrentReportedCount=" + mCurrentReportedCount
                    + " mUnpluggedReportedCount=" + mUnpluggedReportedCount
                    + " mCurrentReportedTotalTime=" + mCurrentReportedTotalTime
                    + " mUnpluggedReportedTotalTime=" + mUnpluggedReportedTotalTime);
        }

        protected long computeRunTimeLocked(long curBatteryRealtime) {
            return mTotalTime + (mInDischarge && mTrackingReportedValues
                    ? mCurrentReportedTotalTime - mUnpluggedReportedTotalTime : 0);
        }

        protected int computeCurrentCountLocked() {
            return mCount + (mInDischarge && mTrackingReportedValues
                    ? mCurrentReportedCount - mUnpluggedReportedCount : 0);
        }

        public void writeToParcel(Parcel out, long batteryRealtime) {
            super.writeToParcel(out, batteryRealtime);
            out.writeInt(mCurrentReportedCount);
            out.writeInt(mUnpluggedReportedCount);
            out.writeLong(mCurrentReportedTotalTime);
            out.writeLong(mUnpluggedReportedTotalTime);
            out.writeInt(mTrackingReportedValues ? 1 : 0);
        }

        boolean reset(BatteryStatsImpl stats, boolean detachIfReset) {
            super.reset(stats, detachIfReset);
            setStale();
            return true;
        }

        void writeSummaryFromParcelLocked(Parcel out, long batteryRealtime) {
            super.writeSummaryFromParcelLocked(out, batteryRealtime);
            out.writeLong(mCurrentReportedTotalTime);
            out.writeInt(mCurrentReportedCount);
            out.writeInt(mTrackingReportedValues ? 1 : 0);
        }

        void readSummaryFromParcelLocked(Parcel in) {
            super.readSummaryFromParcelLocked(in);
            mUnpluggedReportedTotalTime = mCurrentReportedTotalTime = in.readLong();
            mUnpluggedReportedCount = mCurrentReportedCount = in.readInt();
            mTrackingReportedValues = in.readInt() == 1;
        }
    }

    /**
     * State for keeping track of timing information.
     */
    public static final class StopwatchTimer extends Timer {
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
         * was actually held for an interesting duration.
         */
        long mAcquireTime;

        long mTimeout;

        /**
         * For partial wake locks, keep track of whether we are in the list
         * to consume CPU cycles.
         */
        boolean mInList;

        StopwatchTimer(Uid uid, int type, ArrayList<StopwatchTimer> timerPool,
                ArrayList<Unpluggable> unpluggables, Parcel in) {
            super(type, unpluggables, in);
            mUid = uid;
            mTimerPool = timerPool;
            mUpdateTime = in.readLong();
        }

        StopwatchTimer(Uid uid, int type, ArrayList<StopwatchTimer> timerPool,
                ArrayList<Unpluggable> unpluggables) {
            super(type, unpluggables);
            mUid = uid;
            mTimerPool = timerPool;
        }

        void setTimeout(long timeout) {
            mTimeout = timeout;
        }

        public void writeToParcel(Parcel out, long batteryRealtime) {
            super.writeToParcel(out, batteryRealtime);
            out.writeLong(mUpdateTime);
        }

        public void plug(long batteryUptime, long batteryRealtime) {
            if (mNesting > 0) {
                if (DEBUG && mType < 0) {
                    Log.v(TAG, "old mUpdateTime=" + mUpdateTime);
                }
                super.plug(batteryUptime, batteryRealtime);
                mUpdateTime = batteryRealtime;
                if (DEBUG && mType < 0) {
                    Log.v(TAG, "new mUpdateTime=" + mUpdateTime);
                }
            }
        }

        public void logState(Printer pw, String prefix) {
            super.logState(pw, prefix);
            pw.println(prefix + "mNesting=" + mNesting + "mUpdateTime=" + mUpdateTime
                    + " mAcquireTime=" + mAcquireTime);
        }

        void startRunningLocked(BatteryStatsImpl stats) {
            if (mNesting++ == 0) {
                mUpdateTime = stats.getBatteryRealtimeLocked(
                        SystemClock.elapsedRealtime() * 1000);
                if (mTimerPool != null) {
                    // Accumulate time to all currently active timers before adding
                    // this new one to the pool.
                    refreshTimersLocked(stats, mTimerPool);
                    // Add this timer to the active pool
                    mTimerPool.add(this);
                }
                // Increment the count
                mCount++;
                mAcquireTime = mTotalTime;
                if (DEBUG && mType < 0) {
                    Log.v(TAG, "start #" + mType + ": mUpdateTime=" + mUpdateTime
                            + " mTotalTime=" + mTotalTime + " mCount=" + mCount
                            + " mAcquireTime=" + mAcquireTime);
                }
            }
        }

        boolean isRunningLocked() {
            return mNesting > 0;
        }

        void stopRunningLocked(BatteryStatsImpl stats) {
            // Ignore attempt to stop a timer that isn't running
            if (mNesting == 0) {
                return;
            }
            if (--mNesting == 0) {
                if (mTimerPool != null) {
                    // Accumulate time to all active counters, scaled by the total
                    // active in the pool, before taking this one out of the pool.
                    refreshTimersLocked(stats, mTimerPool);
                    // Remove this timer from the active pool
                    mTimerPool.remove(this);
                } else {
                    final long realtime = SystemClock.elapsedRealtime() * 1000;
                    final long batteryRealtime = stats.getBatteryRealtimeLocked(realtime);
                    mNesting = 1;
                    mTotalTime = computeRunTimeLocked(batteryRealtime);
                    mNesting = 0;
                }

                if (DEBUG && mType < 0) {
                    Log.v(TAG, "stop #" + mType + ": mUpdateTime=" + mUpdateTime
                            + " mTotalTime=" + mTotalTime + " mCount=" + mCount
                            + " mAcquireTime=" + mAcquireTime);
                }

                if (mTotalTime == mAcquireTime) {
                    // If there was no change in the time, then discard this
                    // count.  A somewhat cheezy strategy, but hey.
                    mCount--;
                }
            }
        }

        // Update the total time for all other running Timers with the same type as this Timer
        // due to a change in timer count
        private static void refreshTimersLocked(final BatteryStatsImpl stats,
                final ArrayList<StopwatchTimer> pool) {
            final long realtime = SystemClock.elapsedRealtime() * 1000;
            final long batteryRealtime = stats.getBatteryRealtimeLocked(realtime);
            final int N = pool.size();
            for (int i=N-1; i>= 0; i--) {
                final StopwatchTimer t = pool.get(i);
                long heldTime = batteryRealtime - t.mUpdateTime;
                if (heldTime > 0) {
                    t.mTotalTime += heldTime / N;
                }
                t.mUpdateTime = batteryRealtime;
            }
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

        boolean reset(BatteryStatsImpl stats, boolean detachIfReset) {
            boolean canDetach = mNesting <= 0;
            super.reset(stats, canDetach && detachIfReset);
            if (mNesting > 0) {
                mUpdateTime = stats.getBatteryRealtimeLocked(
                        SystemClock.elapsedRealtime() * 1000);
            }
            mAcquireTime = mTotalTime;
            return canDetach;
        }

        void detach() {
            super.detach();
            if (mTimerPool != null) {
                mTimerPool.remove(this);
            }
        }

        void readSummaryFromParcelLocked(Parcel in) {
            super.readSummaryFromParcelLocked(in);
            mNesting = 0;
        }
    }

    private final Map<String, KernelWakelockStats> readKernelWakelockStats() {

        byte[] buffer = new byte[8192];
        int len;

        try {
            FileInputStream is = new FileInputStream("/proc/wakelocks");
            len = is.read(buffer);
            is.close();

            if (len > 0) {
                int i;
                for (i=0; i<len; i++) {
                    if (buffer[i] == '\0') {
                        len = i;
                        break;
                    }
                }
            }
        } catch (java.io.FileNotFoundException e) {
            return null;
        } catch (java.io.IOException e) {
            return null;
        }

        return parseProcWakelocks(buffer, len);
    }

    private final Map<String, KernelWakelockStats> parseProcWakelocks(
            byte[] wlBuffer, int len) {
        String name;
        int count;
        long totalTime;
        int startIndex;
        int endIndex;
        int numUpdatedWlNames = 0;

        // Advance past the first line.
        int i;
        for (i = 0; i < len && wlBuffer[i] != '\n' && wlBuffer[i] != '\0'; i++);
        startIndex = endIndex = i + 1;

        synchronized(this) {
            Map<String, KernelWakelockStats> m = mProcWakelockFileStats;

            sKernelWakelockUpdateVersion++;
            while (endIndex < len) {
                for (endIndex=startIndex;
                        endIndex < len && wlBuffer[endIndex] != '\n' && wlBuffer[endIndex] != '\0';
                        endIndex++);
                endIndex++; // endIndex is an exclusive upper bound.
                // Don't go over the end of the buffer, Process.parseProcLine might
                // write to wlBuffer[endIndex]
                if (endIndex >= (len - 1) ) {
                    return m;
                }

                String[] nameStringArray = mProcWakelocksName;
                long[] wlData = mProcWakelocksData;
                // Stomp out any bad characters since this is from a circular buffer
                // A corruption is seen sometimes that results in the vm crashing
                // This should prevent crashes and the line will probably fail to parse
                for (int j = startIndex; j < endIndex; j++) {
                    if ((wlBuffer[j] & 0x80) != 0) wlBuffer[j] = (byte) '?';
                }
                boolean parsed = Process.parseProcLine(wlBuffer, startIndex, endIndex,
                        PROC_WAKELOCKS_FORMAT, nameStringArray, wlData, null);

                name = nameStringArray[0];
                count = (int) wlData[1];
                // convert nanoseconds to microseconds with rounding.
                totalTime = (wlData[2] + 500) / 1000;

                if (parsed && name.length() > 0) {
                    if (!m.containsKey(name)) {
                        m.put(name, new KernelWakelockStats(count, totalTime,
                                sKernelWakelockUpdateVersion));
                        numUpdatedWlNames++;
                    } else {
                        KernelWakelockStats kwlStats = m.get(name);
                        if (kwlStats.mVersion == sKernelWakelockUpdateVersion) {
                            kwlStats.mCount += count;
                            kwlStats.mTotalTime += totalTime;
                        } else {
                            kwlStats.mCount = count;
                            kwlStats.mTotalTime = totalTime;
                            kwlStats.mVersion = sKernelWakelockUpdateVersion;
                            numUpdatedWlNames++;
                        }
                    }
                }
                startIndex = endIndex;
            }

            if (m.size() != numUpdatedWlNames) {
                // Don't report old data.
                Iterator<KernelWakelockStats> itr = m.values().iterator();
                while (itr.hasNext()) {
                    if (itr.next().mVersion != sKernelWakelockUpdateVersion) {
                        itr.remove();
                    }
                }
            }
            return m;
        }
    }

    private class KernelWakelockStats {
        public int mCount;
        public long mTotalTime;
        public int mVersion;

        KernelWakelockStats(int count, long totalTime, int version) {
            mCount = count;
            mTotalTime = totalTime;
            mVersion = version;
        }
    }

    /*
     * Get the KernelWakelockTimer associated with name, and create a new one if one
     * doesn't already exist.
     */
    public SamplingTimer getKernelWakelockTimerLocked(String name) {
        SamplingTimer kwlt = mKernelWakelockStats.get(name);
        if (kwlt == null) {
            kwlt = new SamplingTimer(mUnpluggables, mOnBatteryInternal,
                    true /* track reported values */);
            mKernelWakelockStats.put(name, kwlt);
        }
        return kwlt;
    }

    private void doDataPlug(long[] dataTransfer, long currentBytes) {
        dataTransfer[STATS_LAST] = dataTransfer[STATS_SINCE_UNPLUGGED];
        dataTransfer[STATS_SINCE_UNPLUGGED] = -1;
    }

    private void doDataUnplug(long[] dataTransfer, long currentBytes) {
        dataTransfer[STATS_SINCE_UNPLUGGED] = currentBytes;
    }

    /**
     * Radio uptime in microseconds when transferring data. This value is very approximate.
     * @return
     */
    private long getCurrentRadioDataUptime() {
        try {
            File awakeTimeFile = new File("/sys/devices/virtual/net/rmnet0/awake_time_ms");
            if (!awakeTimeFile.exists()) return 0;
            BufferedReader br = new BufferedReader(new FileReader(awakeTimeFile));
            String line = br.readLine();
            br.close();
            return Long.parseLong(line) * 1000;
        } catch (NumberFormatException nfe) {
            // Nothing
        } catch (IOException ioe) {
            // Nothing
        }
        return 0;
    }

    /**
     * @deprecated use getRadioDataUptime
     */
    public long getRadioDataUptimeMs() {
        return getRadioDataUptime() / 1000;
    }

    /**
     * Returns the duration that the cell radio was up for data transfers.
     */
    public long getRadioDataUptime() {
        if (mRadioDataStart == -1) {
            return mRadioDataUptime;
        } else {
            return getCurrentRadioDataUptime() - mRadioDataStart;
        }
    }

    private int getCurrentBluetoothPingCount() {
        if (mBtHeadset != null) {
            List<BluetoothDevice> deviceList = mBtHeadset.getConnectedDevices();
            if (deviceList.size() > 0) {
                return mBtHeadset.getBatteryUsageHint(deviceList.get(0));
            }
        }
        return -1;
    }

    public int getBluetoothPingCount() {
        if (mBluetoothPingStart == -1) {
            return mBluetoothPingCount;
        } else if (mBtHeadset != null) {
            return getCurrentBluetoothPingCount() - mBluetoothPingStart;
        }
        return 0;
    }

    public void setBtHeadset(BluetoothHeadset headset) {
        if (headset != null && mBtHeadset == null && isOnBattery() && mBluetoothPingStart == -1) {
            mBluetoothPingStart = getCurrentBluetoothPingCount();
        }
        mBtHeadset = headset;
    }

    int mChangedBufferStates = 0;

    void addHistoryBufferLocked(long curTime) {
        if (!mHaveBatteryLevel || !mRecordingHistory) {
            return;
        }

        final long timeDiff = (mHistoryBaseTime+curTime) - mHistoryLastWritten.time;
        if (mHistoryBufferLastPos >= 0 && mHistoryLastWritten.cmd == HistoryItem.CMD_UPDATE
                && timeDiff < 2000
                && ((mHistoryLastWritten.states^mHistoryCur.states)&mChangedBufferStates) == 0) {
            // If the current is the same as the one before, then we no
            // longer need the entry.
            mHistoryBuffer.setDataSize(mHistoryBufferLastPos);
            mHistoryBuffer.setDataPosition(mHistoryBufferLastPos);
            mHistoryBufferLastPos = -1;
            if (mHistoryLastLastWritten.cmd == HistoryItem.CMD_UPDATE
                    && timeDiff < 500 && mHistoryLastLastWritten.same(mHistoryCur)) {
                // If this results in us returning to the state written
                // prior to the last one, then we can just delete the last
                // written one and drop the new one.  Nothing more to do.
                mHistoryLastWritten.setTo(mHistoryLastLastWritten);
                mHistoryLastLastWritten.cmd = HistoryItem.CMD_NULL;
                return;
            }
            mChangedBufferStates |= mHistoryLastWritten.states^mHistoryCur.states;
            curTime = mHistoryLastWritten.time - mHistoryBaseTime;
            mHistoryLastWritten.setTo(mHistoryLastLastWritten);
        } else {
            mChangedBufferStates = 0;
        }

        final int dataSize = mHistoryBuffer.dataSize();
        if (dataSize >= MAX_HISTORY_BUFFER) {
            if (!mHistoryOverflow) {
                mHistoryOverflow = true;
                addHistoryBufferLocked(curTime, HistoryItem.CMD_OVERFLOW);
            }

            // Once we've reached the maximum number of items, we only
            // record changes to the battery level and the most interesting states.
            // Once we've reached the maximum maximum number of items, we only
            // record changes to the battery level.
            if (mHistoryLastWritten.batteryLevel == mHistoryCur.batteryLevel &&
                    (dataSize >= MAX_MAX_HISTORY_BUFFER
                            || ((mHistoryLastWritten.states^mHistoryCur.states)
                                    & HistoryItem.MOST_INTERESTING_STATES) == 0)) {
                return;
            }
        }

        addHistoryBufferLocked(curTime, HistoryItem.CMD_UPDATE);
    }

    void addHistoryBufferLocked(long curTime, byte cmd) {
        int origPos = 0;
        if (mIteratingHistory) {
            origPos = mHistoryBuffer.dataPosition();
            mHistoryBuffer.setDataPosition(mHistoryBuffer.dataSize());
        }
        mHistoryBufferLastPos = mHistoryBuffer.dataPosition();
        mHistoryLastLastWritten.setTo(mHistoryLastWritten);
        mHistoryLastWritten.setTo(mHistoryBaseTime + curTime, cmd, mHistoryCur);
        mHistoryLastWritten.writeDelta(mHistoryBuffer, mHistoryLastLastWritten);
        mLastHistoryTime = curTime;
        if (DEBUG_HISTORY) Slog.i(TAG, "Writing history buffer: was " + mHistoryBufferLastPos
                + " now " + mHistoryBuffer.dataPosition()
                + " size is now " + mHistoryBuffer.dataSize());
        if (mIteratingHistory) {
            mHistoryBuffer.setDataPosition(origPos);
        }
    }

    int mChangedStates = 0;

    void addHistoryRecordLocked(long curTime) {
        addHistoryBufferLocked(curTime);

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
                && (mHistoryBaseTime+curTime) < (mHistoryEnd.time+2000)
                && ((mHistoryEnd.states^mHistoryCur.states)&mChangedStates) == 0) {
            // If the current is the same as the one before, then we no
            // longer need the entry.
            if (mHistoryLastEnd != null && mHistoryLastEnd.cmd == HistoryItem.CMD_UPDATE
                    && (mHistoryBaseTime+curTime) < (mHistoryEnd.time+500)
                    && mHistoryLastEnd.same(mHistoryCur)) {
                mHistoryLastEnd.next = null;
                mHistoryEnd.next = mHistoryCache;
                mHistoryCache = mHistoryEnd;
                mHistoryEnd = mHistoryLastEnd;
                mHistoryLastEnd = null;
            } else {
                mChangedStates |= mHistoryEnd.states^mHistoryCur.states;
                mHistoryEnd.setTo(mHistoryEnd.time, HistoryItem.CMD_UPDATE, mHistoryCur);
            }
            return;
        }

        mChangedStates = 0;

        if (mNumHistoryItems == MAX_HISTORY_ITEMS
                || mNumHistoryItems == MAX_MAX_HISTORY_ITEMS) {
            addHistoryRecordLocked(curTime, HistoryItem.CMD_OVERFLOW);
        }

        if (mNumHistoryItems >= MAX_HISTORY_ITEMS) {
            // Once we've reached the maximum number of items, we only
            // record changes to the battery level and the most interesting states.
            // Once we've reached the maximum maximum number of items, we only
            // record changes to the battery level.
            if (mHistoryEnd != null && mHistoryEnd.batteryLevel
                    == mHistoryCur.batteryLevel &&
                    (mNumHistoryItems >= MAX_MAX_HISTORY_ITEMS
                            || ((mHistoryEnd.states^mHistoryCur.states)
                                    & HistoryItem.MOST_INTERESTING_STATES) == 0)) {
                return;
            }
        }

        addHistoryRecordLocked(curTime, HistoryItem.CMD_UPDATE);
    }

    void addHistoryRecordLocked(long curTime, byte cmd) {
        HistoryItem rec = mHistoryCache;
        if (rec != null) {
            mHistoryCache = rec.next;
        } else {
            rec = new HistoryItem();
        }
        rec.setTo(mHistoryBaseTime + curTime, cmd, mHistoryCur);

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
        mLastHistoryTime = 0;

        mHistoryBuffer.setDataSize(0);
        mHistoryBuffer.setDataPosition(0);
        mHistoryBuffer.setDataCapacity(MAX_HISTORY_BUFFER/2);
        mHistoryLastLastWritten.cmd = HistoryItem.CMD_NULL;
        mHistoryLastWritten.cmd = HistoryItem.CMD_NULL;
        mHistoryBufferLastPos = -1;
        mHistoryOverflow = false;
    }

    public void doUnplugLocked(long batteryUptime, long batteryRealtime) {
        NetworkStats.Entry entry = null;

        // Track UID data usage
        final NetworkStats uidStats = getNetworkStatsDetailGroupedByUid();
        final int size = uidStats.size();
        for (int i = 0; i < size; i++) {
            entry = uidStats.getValues(i, entry);

            final Uid u = mUidStats.get(entry.uid);
            if (u == null) continue;

            u.mStartedTcpBytesReceived = entry.rxBytes;
            u.mStartedTcpBytesSent = entry.txBytes;
            u.mTcpBytesReceivedAtLastUnplug = u.mCurrentTcpBytesReceived;
            u.mTcpBytesSentAtLastUnplug = u.mCurrentTcpBytesSent;
        }

        for (int i = mUnpluggables.size() - 1; i >= 0; i--) {
            mUnpluggables.get(i).unplug(batteryUptime, batteryRealtime);
        }

        // Track both mobile and total overall data
        final NetworkStats ifaceStats = getNetworkStatsSummary();
        entry = ifaceStats.getTotal(entry, mMobileIfaces);
        doDataUnplug(mMobileDataRx, entry.rxBytes);
        doDataUnplug(mMobileDataTx, entry.txBytes);
        entry = ifaceStats.getTotal(entry);
        doDataUnplug(mTotalDataRx, entry.rxBytes);
        doDataUnplug(mTotalDataTx, entry.txBytes);

        // Track radio awake time
        mRadioDataStart = getCurrentRadioDataUptime();
        mRadioDataUptime = 0;

        // Track bt headset ping count
        mBluetoothPingStart = getCurrentBluetoothPingCount();
        mBluetoothPingCount = 0;
    }

    public void doPlugLocked(long batteryUptime, long batteryRealtime) {
        NetworkStats.Entry entry = null;

        for (int iu = mUidStats.size() - 1; iu >= 0; iu--) {
            Uid u = mUidStats.valueAt(iu);
            if (u.mStartedTcpBytesReceived >= 0) {
                u.mCurrentTcpBytesReceived = u.computeCurrentTcpBytesReceived();
                u.mStartedTcpBytesReceived = -1;
            }
            if (u.mStartedTcpBytesSent >= 0) {
                u.mCurrentTcpBytesSent = u.computeCurrentTcpBytesSent();
                u.mStartedTcpBytesSent = -1;
            }
        }
        for (int i = mUnpluggables.size() - 1; i >= 0; i--) {
            mUnpluggables.get(i).plug(batteryUptime, batteryRealtime);
        }

        // Track both mobile and total overall data
        final NetworkStats ifaceStats = getNetworkStatsSummary();
        entry = ifaceStats.getTotal(entry, mMobileIfaces);
        doDataPlug(mMobileDataRx, entry.rxBytes);
        doDataPlug(mMobileDataTx, entry.txBytes);
        entry = ifaceStats.getTotal(entry);
        doDataPlug(mTotalDataRx, entry.rxBytes);
        doDataPlug(mTotalDataTx, entry.txBytes);

        // Track radio awake time
        mRadioDataUptime = getRadioDataUptime();
        mRadioDataStart = -1;

        // Track bt headset ping count
        mBluetoothPingCount = getBluetoothPingCount();
        mBluetoothPingStart = -1;
    }

    int mWakeLockNesting;

    public void noteStartWakeLocked(int uid, int pid, String name, int type) {
        if (type == WAKE_TYPE_PARTIAL) {
            // Only care about partial wake locks, since full wake locks
            // will be canceled when the user puts the screen to sleep.
            if (mWakeLockNesting == 0) {
                mHistoryCur.states |= HistoryItem.STATE_WAKE_LOCK_FLAG;
                if (DEBUG_HISTORY) Slog.v(TAG, "Start wake lock to: "
                        + Integer.toHexString(mHistoryCur.states));
                addHistoryRecordLocked(SystemClock.elapsedRealtime());
            }
            mWakeLockNesting++;
        }
        if (uid >= 0) {
            if (!mHandler.hasMessages(MSG_UPDATE_WAKELOCKS)) {
                Message m = mHandler.obtainMessage(MSG_UPDATE_WAKELOCKS);
                mHandler.sendMessageDelayed(m, DELAY_UPDATE_WAKELOCKS);
            }
            getUidStatsLocked(uid).noteStartWakeLocked(pid, name, type);
        }
    }

    public void noteStopWakeLocked(int uid, int pid, String name, int type) {
        if (type == WAKE_TYPE_PARTIAL) {
            mWakeLockNesting--;
            if (mWakeLockNesting == 0) {
                mHistoryCur.states &= ~HistoryItem.STATE_WAKE_LOCK_FLAG;
                if (DEBUG_HISTORY) Slog.v(TAG, "Stop wake lock to: "
                        + Integer.toHexString(mHistoryCur.states));
                addHistoryRecordLocked(SystemClock.elapsedRealtime());
            }
        }
        if (uid >= 0) {
            if (!mHandler.hasMessages(MSG_UPDATE_WAKELOCKS)) {
                Message m = mHandler.obtainMessage(MSG_UPDATE_WAKELOCKS);
                mHandler.sendMessageDelayed(m, DELAY_UPDATE_WAKELOCKS);
            }
            getUidStatsLocked(uid).noteStopWakeLocked(pid, name, type);
        }
    }

    public void noteStartWakeFromSourceLocked(WorkSource ws, int pid, String name, int type) {
        int N = ws.size();
        for (int i=0; i<N; i++) {
            noteStartWakeLocked(ws.get(i), pid, name, type);
        }
    }

    public void noteStopWakeFromSourceLocked(WorkSource ws, int pid, String name, int type) {
        int N = ws.size();
        for (int i=0; i<N; i++) {
            noteStopWakeLocked(ws.get(i), pid, name, type);
        }
    }

    public int startAddingCpuLocked() {
        mHandler.removeMessages(MSG_UPDATE_WAKELOCKS);

        if (mScreenOn) {
            return 0;
        }

        final int N = mPartialTimers.size();
        if (N == 0) {
            mLastPartialTimers.clear();
            return 0;
        }

        // How many timers should consume CPU?  Only want to include ones
        // that have already been in the list.
        for (int i=0; i<N; i++) {
            StopwatchTimer st = mPartialTimers.get(i);
            if (st.mInList) {
                Uid uid = st.mUid;
                // We don't include the system UID, because it so often
                // holds wake locks at one request or another of an app.
                if (uid != null && uid.mUid != Process.SYSTEM_UID) {
                    return 50;
                }
            }
        }

        return 0;
    }

    public void finishAddingCpuLocked(int perc, int utime, int stime, long[] cpuSpeedTimes) {
        final int N = mPartialTimers.size();
        if (perc != 0) {
            int num = 0;
            for (int i=0; i<N; i++) {
                StopwatchTimer st = mPartialTimers.get(i);
                if (st.mInList) {
                    Uid uid = st.mUid;
                    // We don't include the system UID, because it so often
                    // holds wake locks at one request or another of an app.
                    if (uid != null && uid.mUid != Process.SYSTEM_UID) {
                        num++;
                    }
                }
            }
            if (num != 0) {
                for (int i=0; i<N; i++) {
                    StopwatchTimer st = mPartialTimers.get(i);
                    if (st.mInList) {
                        Uid uid = st.mUid;
                        if (uid != null && uid.mUid != Process.SYSTEM_UID) {
                            int myUTime = utime/num;
                            int mySTime = stime/num;
                            utime -= myUTime;
                            stime -= mySTime;
                            num--;
                            Uid.Proc proc = uid.getProcessStatsLocked("*wakelock*");
                            proc.addCpuTimeLocked(myUTime, mySTime);
                            proc.addSpeedStepTimes(cpuSpeedTimes);
                        }
                    }
                }
            }

            // Just in case, collect any lost CPU time.
            if (utime != 0 || stime != 0) {
                Uid uid = getUidStatsLocked(Process.SYSTEM_UID);
                if (uid != null) {
                    Uid.Proc proc = uid.getProcessStatsLocked("*lost*");
                    proc.addCpuTimeLocked(utime, stime);
                    proc.addSpeedStepTimes(cpuSpeedTimes);
                }
            }
        }

        final int NL = mLastPartialTimers.size();
        boolean diff = N != NL;
        for (int i=0; i<NL && !diff; i++) {
            diff |= mPartialTimers.get(i) != mLastPartialTimers.get(i);
        }
        if (!diff) {
            for (int i=0; i<NL; i++) {
                mPartialTimers.get(i).mInList = true;
            }
            return;
        }

        for (int i=0; i<NL; i++) {
            mLastPartialTimers.get(i).mInList = false;
        }
        mLastPartialTimers.clear();
        for (int i=0; i<N; i++) {
            StopwatchTimer st = mPartialTimers.get(i);
            st.mInList = true;
            mLastPartialTimers.add(st);
        }
    }

    public void noteProcessDiedLocked(int uid, int pid) {
        Uid u = mUidStats.get(uid);
        if (u != null) {
            u.mPids.remove(pid);
        }
    }

    public long getProcessWakeTime(int uid, int pid, long realtime) {
        Uid u = mUidStats.get(uid);
        if (u != null) {
            Uid.Pid p = u.mPids.get(pid);
            if (p != null) {
                return p.mWakeSum + (p.mWakeStart != 0 ? (realtime - p.mWakeStart) : 0);
            }
        }
        return 0;
    }

    public void reportExcessiveWakeLocked(int uid, String proc, long overTime, long usedTime) {
        Uid u = mUidStats.get(uid);
        if (u != null) {
            u.reportExcessiveWakeLocked(proc, overTime, usedTime);
        }
    }

    public void reportExcessiveCpuLocked(int uid, String proc, long overTime, long usedTime) {
        Uid u = mUidStats.get(uid);
        if (u != null) {
            u.reportExcessiveCpuLocked(proc, overTime, usedTime);
        }
    }

    int mSensorNesting;

    public void noteStartSensorLocked(int uid, int sensor) {
        if (mSensorNesting == 0) {
            mHistoryCur.states |= HistoryItem.STATE_SENSOR_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Start sensor to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(SystemClock.elapsedRealtime());
        }
        mSensorNesting++;
        getUidStatsLocked(uid).noteStartSensor(sensor);
    }

    public void noteStopSensorLocked(int uid, int sensor) {
        mSensorNesting--;
        if (mSensorNesting == 0) {
            mHistoryCur.states &= ~HistoryItem.STATE_SENSOR_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Stop sensor to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(SystemClock.elapsedRealtime());
        }
        getUidStatsLocked(uid).noteStopSensor(sensor);
    }

    int mGpsNesting;

    public void noteStartGpsLocked(int uid) {
        if (mGpsNesting == 0) {
            mHistoryCur.states |= HistoryItem.STATE_GPS_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Start GPS to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(SystemClock.elapsedRealtime());
        }
        mGpsNesting++;
        getUidStatsLocked(uid).noteStartGps();
    }

    public void noteStopGpsLocked(int uid) {
        mGpsNesting--;
        if (mGpsNesting == 0) {
            mHistoryCur.states &= ~HistoryItem.STATE_GPS_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Stop GPS to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(SystemClock.elapsedRealtime());
        }
        getUidStatsLocked(uid).noteStopGps();
    }

    public void noteScreenOnLocked() {
        if (!mScreenOn) {
            mHistoryCur.states |= HistoryItem.STATE_SCREEN_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Screen on to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(SystemClock.elapsedRealtime());
            mScreenOn = true;
            mScreenOnTimer.startRunningLocked(this);
            if (mScreenBrightnessBin >= 0) {
                mScreenBrightnessTimer[mScreenBrightnessBin].startRunningLocked(this);
            }

            // Fake a wake lock, so we consider the device waked as long
            // as the screen is on.
            noteStartWakeLocked(-1, -1, "dummy", WAKE_TYPE_PARTIAL);
            
            // Update discharge amounts.
            if (mOnBatteryInternal) {
                updateDischargeScreenLevelsLocked(false, true);
            }
        }
    }

    public void noteScreenOffLocked() {
        if (mScreenOn) {
            mHistoryCur.states &= ~HistoryItem.STATE_SCREEN_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Screen off to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(SystemClock.elapsedRealtime());
            mScreenOn = false;
            mScreenOnTimer.stopRunningLocked(this);
            if (mScreenBrightnessBin >= 0) {
                mScreenBrightnessTimer[mScreenBrightnessBin].stopRunningLocked(this);
            }

            noteStopWakeLocked(-1, -1, "dummy", WAKE_TYPE_PARTIAL);
            
            // Update discharge amounts.
            if (mOnBatteryInternal) {
                updateDischargeScreenLevelsLocked(true, false);
            }
        }
    }

    public void noteScreenBrightnessLocked(int brightness) {
        // Bin the brightness.
        int bin = brightness / (256/NUM_SCREEN_BRIGHTNESS_BINS);
        if (bin < 0) bin = 0;
        else if (bin >= NUM_SCREEN_BRIGHTNESS_BINS) bin = NUM_SCREEN_BRIGHTNESS_BINS-1;
        if (mScreenBrightnessBin != bin) {
            mHistoryCur.states = (mHistoryCur.states&~HistoryItem.STATE_BRIGHTNESS_MASK)
                    | (bin << HistoryItem.STATE_BRIGHTNESS_SHIFT);
            if (DEBUG_HISTORY) Slog.v(TAG, "Screen brightness " + bin + " to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(SystemClock.elapsedRealtime());
            if (mScreenOn) {
                if (mScreenBrightnessBin >= 0) {
                    mScreenBrightnessTimer[mScreenBrightnessBin].stopRunningLocked(this);
                }
                mScreenBrightnessTimer[bin].startRunningLocked(this);
            }
            mScreenBrightnessBin = bin;
        }
    }

    public void noteInputEventAtomic() {
        mInputEventCounter.stepAtomic();
    }

    public void noteUserActivityLocked(int uid, int event) {
        getUidStatsLocked(uid).noteUserActivityLocked(event);
    }

    public void notePhoneOnLocked() {
        if (!mPhoneOn) {
            mHistoryCur.states |= HistoryItem.STATE_PHONE_IN_CALL_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Phone on to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(SystemClock.elapsedRealtime());
            mPhoneOn = true;
            mPhoneOnTimer.startRunningLocked(this);
        }
    }

    public void notePhoneOffLocked() {
        if (mPhoneOn) {
            mHistoryCur.states &= ~HistoryItem.STATE_PHONE_IN_CALL_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Phone off to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(SystemClock.elapsedRealtime());
            mPhoneOn = false;
            mPhoneOnTimer.stopRunningLocked(this);
        }
    }

    void stopAllSignalStrengthTimersLocked(int except) {
        for (int i = 0; i < SignalStrength.NUM_SIGNAL_STRENGTH_BINS; i++) {
            if (i == except) {
                continue;
            }
            while (mPhoneSignalStrengthsTimer[i].isRunningLocked()) {
                mPhoneSignalStrengthsTimer[i].stopRunningLocked(this);
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

    private void updateAllPhoneStateLocked(int state, int simState, int bin) {
        boolean scanning = false;
        boolean newHistory = false;

        mPhoneServiceStateRaw = state;
        mPhoneSimStateRaw = simState;
        mPhoneSignalStrengthBinRaw = bin;

        if (simState == TelephonyManager.SIM_STATE_ABSENT) {
            // In this case we will always be STATE_OUT_OF_SERVICE, so need
            // to infer that we are scanning from other data.
            if (state == ServiceState.STATE_OUT_OF_SERVICE
                    && bin > SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN) {
                state = ServiceState.STATE_IN_SERVICE;
            }
        }

        // If the phone is powered off, stop all timers.
        if (state == ServiceState.STATE_POWER_OFF) {
            bin = -1;

        // If we are in service, make sure the correct signal string timer is running.
        } else if (state == ServiceState.STATE_IN_SERVICE) {
            // Bin will be changed below.

        // If we're out of service, we are in the lowest signal strength
        // bin and have the scanning bit set.
        } else if (state == ServiceState.STATE_OUT_OF_SERVICE) {
            scanning = true;
            bin = SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
            if (!mPhoneSignalScanningTimer.isRunningLocked()) {
                mHistoryCur.states |= HistoryItem.STATE_PHONE_SCANNING_FLAG;
                newHistory = true;
                if (DEBUG_HISTORY) Slog.v(TAG, "Phone started scanning to: "
                        + Integer.toHexString(mHistoryCur.states));
                mPhoneSignalScanningTimer.startRunningLocked(this);
            }
        }

        if (!scanning) {
            // If we are no longer scanning, then stop the scanning timer.
            if (mPhoneSignalScanningTimer.isRunningLocked()) {
                mHistoryCur.states &= ~HistoryItem.STATE_PHONE_SCANNING_FLAG;
                if (DEBUG_HISTORY) Slog.v(TAG, "Phone stopped scanning to: "
                        + Integer.toHexString(mHistoryCur.states));
                newHistory = true;
                mPhoneSignalScanningTimer.stopRunningLocked(this);
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

        if (mPhoneSignalStrengthBin != bin) {
            if (mPhoneSignalStrengthBin >= 0) {
                mPhoneSignalStrengthsTimer[mPhoneSignalStrengthBin].stopRunningLocked(this);
            }
            if (bin >= 0) {
                if (!mPhoneSignalStrengthsTimer[bin].isRunningLocked()) {
                    mPhoneSignalStrengthsTimer[bin].startRunningLocked(this);
                }
                mHistoryCur.states = (mHistoryCur.states&~HistoryItem.STATE_SIGNAL_STRENGTH_MASK)
                        | (bin << HistoryItem.STATE_SIGNAL_STRENGTH_SHIFT);
                if (DEBUG_HISTORY) Slog.v(TAG, "Signal strength " + bin + " to: "
                        + Integer.toHexString(mHistoryCur.states));
                newHistory = true;
            } else {
                stopAllSignalStrengthTimersLocked(-1);
            }
            mPhoneSignalStrengthBin = bin;
        }

        if (newHistory) {
            addHistoryRecordLocked(SystemClock.elapsedRealtime());
        }
    }

    /**
     * Telephony stack updates the phone state.
     * @param state phone state from ServiceState.getState()
     */
    public void notePhoneStateLocked(int state, int simState) {
        updateAllPhoneStateLocked(state, simState, mPhoneSignalStrengthBinRaw);
    }

    public void notePhoneSignalStrengthLocked(SignalStrength signalStrength) {
        // Bin the strength.
        int bin = signalStrength.getLevel();
        updateAllPhoneStateLocked(mPhoneServiceStateRaw, mPhoneSimStateRaw, bin);
    }

    public void notePhoneDataConnectionStateLocked(int dataType, boolean hasData) {
        int bin = DATA_CONNECTION_NONE;
        if (hasData) {
            switch (dataType) {
                case TelephonyManager.NETWORK_TYPE_EDGE:
                    bin = DATA_CONNECTION_EDGE;
                    break;
                case TelephonyManager.NETWORK_TYPE_GPRS:
                    bin = DATA_CONNECTION_GPRS;
                    break;
                case TelephonyManager.NETWORK_TYPE_UMTS:
                    bin = DATA_CONNECTION_UMTS;
                    break;
                case TelephonyManager.NETWORK_TYPE_CDMA:
                    bin = DATA_CONNECTION_CDMA;
                    break;
                case TelephonyManager.NETWORK_TYPE_EVDO_0:
                    bin = DATA_CONNECTION_EVDO_0;
                    break;
                case TelephonyManager.NETWORK_TYPE_EVDO_A:
                    bin = DATA_CONNECTION_EVDO_A;
                    break;
                case TelephonyManager.NETWORK_TYPE_1xRTT:
                    bin = DATA_CONNECTION_1xRTT;
                    break;
                case TelephonyManager.NETWORK_TYPE_HSDPA:
                    bin = DATA_CONNECTION_HSDPA;
                    break;
                case TelephonyManager.NETWORK_TYPE_HSUPA:
                    bin = DATA_CONNECTION_HSUPA;
                    break;
                case TelephonyManager.NETWORK_TYPE_HSPA:
                    bin = DATA_CONNECTION_HSPA;
                    break;
                case TelephonyManager.NETWORK_TYPE_IDEN:
                    bin = DATA_CONNECTION_IDEN;
                    break;
                case TelephonyManager.NETWORK_TYPE_EVDO_B:
                    bin = DATA_CONNECTION_EVDO_B;
                    break;
                case TelephonyManager.NETWORK_TYPE_LTE:
                    bin = DATA_CONNECTION_LTE;
                    break;
                case TelephonyManager.NETWORK_TYPE_EHRPD:
                    bin = DATA_CONNECTION_EHRPD;
                    break;
                default:
                    bin = DATA_CONNECTION_OTHER;
                    break;
            }
        }
        if (DEBUG) Log.i(TAG, "Phone Data Connection -> " + dataType + " = " + hasData);
        if (mPhoneDataConnectionType != bin) {
            mHistoryCur.states = (mHistoryCur.states&~HistoryItem.STATE_DATA_CONNECTION_MASK)
                    | (bin << HistoryItem.STATE_DATA_CONNECTION_SHIFT);
            if (DEBUG_HISTORY) Slog.v(TAG, "Data connection " + bin + " to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(SystemClock.elapsedRealtime());
            if (mPhoneDataConnectionType >= 0) {
                mPhoneDataConnectionsTimer[mPhoneDataConnectionType].stopRunningLocked(this);
            }
            mPhoneDataConnectionType = bin;
            mPhoneDataConnectionsTimer[bin].startRunningLocked(this);
        }
    }

    public void noteWifiOnLocked() {
        if (!mWifiOn) {
            mHistoryCur.states |= HistoryItem.STATE_WIFI_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "WIFI on to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(SystemClock.elapsedRealtime());
            mWifiOn = true;
            mWifiOnTimer.startRunningLocked(this);
        }
    }

    public void noteWifiOffLocked() {
        if (mWifiOn) {
            mHistoryCur.states &= ~HistoryItem.STATE_WIFI_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "WIFI off to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(SystemClock.elapsedRealtime());
            mWifiOn = false;
            mWifiOnTimer.stopRunningLocked(this);
        }
        if (mWifiOnUid >= 0) {
            getUidStatsLocked(mWifiOnUid).noteWifiStoppedLocked();
            mWifiOnUid = -1;
        }
    }

    public void noteAudioOnLocked(int uid) {
        if (!mAudioOn) {
            mHistoryCur.states |= HistoryItem.STATE_AUDIO_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Audio on to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(SystemClock.elapsedRealtime());
            mAudioOn = true;
            mAudioOnTimer.startRunningLocked(this);
        }
        getUidStatsLocked(uid).noteAudioTurnedOnLocked();
    }

    public void noteAudioOffLocked(int uid) {
        if (mAudioOn) {
            mHistoryCur.states &= ~HistoryItem.STATE_AUDIO_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Audio off to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(SystemClock.elapsedRealtime());
            mAudioOn = false;
            mAudioOnTimer.stopRunningLocked(this);
        }
        getUidStatsLocked(uid).noteAudioTurnedOffLocked();
    }

    public void noteVideoOnLocked(int uid) {
        if (!mVideoOn) {
            mHistoryCur.states |= HistoryItem.STATE_VIDEO_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Video on to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(SystemClock.elapsedRealtime());
            mVideoOn = true;
            mVideoOnTimer.startRunningLocked(this);
        }
        getUidStatsLocked(uid).noteVideoTurnedOnLocked();
    }

    public void noteVideoOffLocked(int uid) {
        if (mVideoOn) {
            mHistoryCur.states &= ~HistoryItem.STATE_VIDEO_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Video off to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(SystemClock.elapsedRealtime());
            mVideoOn = false;
            mVideoOnTimer.stopRunningLocked(this);
        }
        getUidStatsLocked(uid).noteVideoTurnedOffLocked();
    }

    public void noteWifiRunningLocked(WorkSource ws) {
        if (!mGlobalWifiRunning) {
            mHistoryCur.states |= HistoryItem.STATE_WIFI_RUNNING_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "WIFI running to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(SystemClock.elapsedRealtime());
            mGlobalWifiRunning = true;
            mGlobalWifiRunningTimer.startRunningLocked(this);
            int N = ws.size();
            for (int i=0; i<N; i++) {
                getUidStatsLocked(ws.get(i)).noteWifiRunningLocked();
            }
        } else {
            Log.w(TAG, "noteWifiRunningLocked -- called while WIFI running");
        }
    }

    public void noteWifiRunningChangedLocked(WorkSource oldWs, WorkSource newWs) {
        if (mGlobalWifiRunning) {
            int N = oldWs.size();
            for (int i=0; i<N; i++) {
                getUidStatsLocked(oldWs.get(i)).noteWifiStoppedLocked();
            }
            N = newWs.size();
            for (int i=0; i<N; i++) {
                getUidStatsLocked(newWs.get(i)).noteWifiRunningLocked();
            }
        } else {
            Log.w(TAG, "noteWifiRunningChangedLocked -- called while WIFI not running");
        }
    }

    public void noteWifiStoppedLocked(WorkSource ws) {
        if (mGlobalWifiRunning) {
            mHistoryCur.states &= ~HistoryItem.STATE_WIFI_RUNNING_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "WIFI stopped to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(SystemClock.elapsedRealtime());
            mGlobalWifiRunning = false;
            mGlobalWifiRunningTimer.stopRunningLocked(this);
            int N = ws.size();
            for (int i=0; i<N; i++) {
                getUidStatsLocked(ws.get(i)).noteWifiStoppedLocked();
            }
        } else {
            Log.w(TAG, "noteWifiStoppedLocked -- called while WIFI not running");
        }
    }

    public void noteBluetoothOnLocked() {
        if (!mBluetoothOn) {
            mHistoryCur.states |= HistoryItem.STATE_BLUETOOTH_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Bluetooth on to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(SystemClock.elapsedRealtime());
            mBluetoothOn = true;
            mBluetoothOnTimer.startRunningLocked(this);
        }
    }

    public void noteBluetoothOffLocked() {
        if (mBluetoothOn) {
            mHistoryCur.states &= ~HistoryItem.STATE_BLUETOOTH_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Bluetooth off to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(SystemClock.elapsedRealtime());
            mBluetoothOn = false;
            mBluetoothOnTimer.stopRunningLocked(this);
        }
    }

    int mWifiFullLockNesting = 0;

    public void noteFullWifiLockAcquiredLocked(int uid) {
        if (mWifiFullLockNesting == 0) {
            mHistoryCur.states |= HistoryItem.STATE_WIFI_FULL_LOCK_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "WIFI full lock on to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(SystemClock.elapsedRealtime());
        }
        mWifiFullLockNesting++;
        getUidStatsLocked(uid).noteFullWifiLockAcquiredLocked();
    }

    public void noteFullWifiLockReleasedLocked(int uid) {
        mWifiFullLockNesting--;
        if (mWifiFullLockNesting == 0) {
            mHistoryCur.states &= ~HistoryItem.STATE_WIFI_FULL_LOCK_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "WIFI full lock off to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(SystemClock.elapsedRealtime());
        }
        getUidStatsLocked(uid).noteFullWifiLockReleasedLocked();
    }

    int mWifiScanLockNesting = 0;

    public void noteScanWifiLockAcquiredLocked(int uid) {
        if (mWifiScanLockNesting == 0) {
            mHistoryCur.states |= HistoryItem.STATE_WIFI_SCAN_LOCK_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "WIFI scan lock on to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(SystemClock.elapsedRealtime());
        }
        mWifiScanLockNesting++;
        getUidStatsLocked(uid).noteScanWifiLockAcquiredLocked();
    }

    public void noteScanWifiLockReleasedLocked(int uid) {
        mWifiScanLockNesting--;
        if (mWifiScanLockNesting == 0) {
            mHistoryCur.states &= ~HistoryItem.STATE_WIFI_SCAN_LOCK_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "WIFI scan lock off to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(SystemClock.elapsedRealtime());
        }
        getUidStatsLocked(uid).noteScanWifiLockReleasedLocked();
    }

    int mWifiMulticastNesting = 0;

    public void noteWifiMulticastEnabledLocked(int uid) {
        if (mWifiMulticastNesting == 0) {
            mHistoryCur.states |= HistoryItem.STATE_WIFI_MULTICAST_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "WIFI multicast on to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(SystemClock.elapsedRealtime());
        }
        mWifiMulticastNesting++;
        getUidStatsLocked(uid).noteWifiMulticastEnabledLocked();
    }

    public void noteWifiMulticastDisabledLocked(int uid) {
        mWifiMulticastNesting--;
        if (mWifiMulticastNesting == 0) {
            mHistoryCur.states &= ~HistoryItem.STATE_WIFI_MULTICAST_ON_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "WIFI multicast off to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(SystemClock.elapsedRealtime());
        }
        getUidStatsLocked(uid).noteWifiMulticastDisabledLocked();
    }

    public void noteFullWifiLockAcquiredFromSourceLocked(WorkSource ws) {
        int N = ws.size();
        for (int i=0; i<N; i++) {
            noteFullWifiLockAcquiredLocked(ws.get(i));
        }
    }

    public void noteFullWifiLockReleasedFromSourceLocked(WorkSource ws) {
        int N = ws.size();
        for (int i=0; i<N; i++) {
            noteFullWifiLockReleasedLocked(ws.get(i));
        }
    }

    public void noteScanWifiLockAcquiredFromSourceLocked(WorkSource ws) {
        int N = ws.size();
        for (int i=0; i<N; i++) {
            noteScanWifiLockAcquiredLocked(ws.get(i));
        }
    }

    public void noteScanWifiLockReleasedFromSourceLocked(WorkSource ws) {
        int N = ws.size();
        for (int i=0; i<N; i++) {
            noteScanWifiLockReleasedLocked(ws.get(i));
        }
    }

    public void noteWifiMulticastEnabledFromSourceLocked(WorkSource ws) {
        int N = ws.size();
        for (int i=0; i<N; i++) {
            noteWifiMulticastEnabledLocked(ws.get(i));
        }
    }

    public void noteWifiMulticastDisabledFromSourceLocked(WorkSource ws) {
        int N = ws.size();
        for (int i=0; i<N; i++) {
            noteWifiMulticastDisabledLocked(ws.get(i));
        }
    }

    public void noteNetworkInterfaceTypeLocked(String iface, int networkType) {
        if (ConnectivityManager.isNetworkTypeMobile(networkType)) {
            mMobileIfaces.add(iface);
        } else {
            mMobileIfaces.remove(iface);
        }
    }

    @Override public long getScreenOnTime(long batteryRealtime, int which) {
        return mScreenOnTimer.getTotalTimeLocked(batteryRealtime, which);
    }

    @Override public long getScreenBrightnessTime(int brightnessBin,
            long batteryRealtime, int which) {
        return mScreenBrightnessTimer[brightnessBin].getTotalTimeLocked(
                batteryRealtime, which);
    }

    @Override public int getInputEventCount(int which) {
        return mInputEventCounter.getCountLocked(which);
    }

    @Override public long getPhoneOnTime(long batteryRealtime, int which) {
        return mPhoneOnTimer.getTotalTimeLocked(batteryRealtime, which);
    }

    @Override public long getPhoneSignalStrengthTime(int strengthBin,
            long batteryRealtime, int which) {
        return mPhoneSignalStrengthsTimer[strengthBin].getTotalTimeLocked(
                batteryRealtime, which);
    }

    @Override public long getPhoneSignalScanningTime(
            long batteryRealtime, int which) {
        return mPhoneSignalScanningTimer.getTotalTimeLocked(
                batteryRealtime, which);
    }

    @Override public int getPhoneSignalStrengthCount(int dataType, int which) {
        return mPhoneDataConnectionsTimer[dataType].getCountLocked(which);
    }

    @Override public long getPhoneDataConnectionTime(int dataType,
            long batteryRealtime, int which) {
        return mPhoneDataConnectionsTimer[dataType].getTotalTimeLocked(
                batteryRealtime, which);
    }

    @Override public int getPhoneDataConnectionCount(int dataType, int which) {
        return mPhoneDataConnectionsTimer[dataType].getCountLocked(which);
    }

    @Override public long getWifiOnTime(long batteryRealtime, int which) {
        return mWifiOnTimer.getTotalTimeLocked(batteryRealtime, which);
    }

    @Override public long getGlobalWifiRunningTime(long batteryRealtime, int which) {
        return mGlobalWifiRunningTimer.getTotalTimeLocked(batteryRealtime, which);
    }

    @Override public long getBluetoothOnTime(long batteryRealtime, int which) {
        return mBluetoothOnTimer.getTotalTimeLocked(batteryRealtime, which);
    }

    @Override public boolean getIsOnBattery() {
        return mOnBattery;
    }

    @Override public SparseArray<? extends BatteryStats.Uid> getUidStats() {
        return mUidStats;
    }

    /**
     * The statistics associated with a particular uid.
     */
    public final class Uid extends BatteryStats.Uid {

        final int mUid;
        long mLoadedTcpBytesReceived;
        long mLoadedTcpBytesSent;
        long mCurrentTcpBytesReceived;
        long mCurrentTcpBytesSent;
        long mTcpBytesReceivedAtLastUnplug;
        long mTcpBytesSentAtLastUnplug;

        // These are not saved/restored when parcelling, since we want
        // to return from the parcel with a snapshot of the state.
        long mStartedTcpBytesReceived = -1;
        long mStartedTcpBytesSent = -1;

        boolean mWifiRunning;
        StopwatchTimer mWifiRunningTimer;

        boolean mFullWifiLockOut;
        StopwatchTimer mFullWifiLockTimer;

        boolean mScanWifiLockOut;
        StopwatchTimer mScanWifiLockTimer;

        boolean mWifiMulticastEnabled;
        StopwatchTimer mWifiMulticastTimer;

        boolean mAudioTurnedOn;
        StopwatchTimer mAudioTurnedOnTimer;

        boolean mVideoTurnedOn;
        StopwatchTimer mVideoTurnedOnTimer;

        Counter[] mUserActivityCounters;

        /**
         * The statistics we have collected for this uid's wake locks.
         */
        final HashMap<String, Wakelock> mWakelockStats = new HashMap<String, Wakelock>();

        /**
         * The statistics we have collected for this uid's sensor activations.
         */
        final HashMap<Integer, Sensor> mSensorStats = new HashMap<Integer, Sensor>();

        /**
         * The statistics we have collected for this uid's processes.
         */
        final HashMap<String, Proc> mProcessStats = new HashMap<String, Proc>();

        /**
         * The statistics we have collected for this uid's processes.
         */
        final HashMap<String, Pkg> mPackageStats = new HashMap<String, Pkg>();

        /**
         * The transient wake stats we have collected for this uid's pids.
         */
        final SparseArray<Pid> mPids = new SparseArray<Pid>();

        public Uid(int uid) {
            mUid = uid;
            mWifiRunningTimer = new StopwatchTimer(Uid.this, WIFI_RUNNING,
                    mWifiRunningTimers, mUnpluggables);
            mFullWifiLockTimer = new StopwatchTimer(Uid.this, FULL_WIFI_LOCK,
                    mFullWifiLockTimers, mUnpluggables);
            mScanWifiLockTimer = new StopwatchTimer(Uid.this, SCAN_WIFI_LOCK,
                    mScanWifiLockTimers, mUnpluggables);
            mWifiMulticastTimer = new StopwatchTimer(Uid.this, WIFI_MULTICAST_ENABLED,
                    mWifiMulticastTimers, mUnpluggables);
            mAudioTurnedOnTimer = new StopwatchTimer(Uid.this, AUDIO_TURNED_ON,
                    null, mUnpluggables);
            mVideoTurnedOnTimer = new StopwatchTimer(Uid.this, VIDEO_TURNED_ON,
                    null, mUnpluggables);
        }

        @Override
        public Map<String, ? extends BatteryStats.Uid.Wakelock> getWakelockStats() {
            return mWakelockStats;
        }

        @Override
        public Map<Integer, ? extends BatteryStats.Uid.Sensor> getSensorStats() {
            return mSensorStats;
        }

        @Override
        public Map<String, ? extends BatteryStats.Uid.Proc> getProcessStats() {
            return mProcessStats;
        }

        @Override
        public Map<String, ? extends BatteryStats.Uid.Pkg> getPackageStats() {
            return mPackageStats;
        }

        @Override
        public int getUid() {
            return mUid;
        }

        @Override
        public long getTcpBytesReceived(int which) {
            if (which == STATS_LAST) {
                return mLoadedTcpBytesReceived;
            } else {
                long current = computeCurrentTcpBytesReceived();
                if (which == STATS_SINCE_UNPLUGGED) {
                    current -= mTcpBytesReceivedAtLastUnplug;
                } else if (which == STATS_SINCE_CHARGED) {
                    current += mLoadedTcpBytesReceived;
                }
                return current;
            }
        }

        public long computeCurrentTcpBytesReceived() {
            final long uidRxBytes = getNetworkStatsDetailGroupedByUid().getTotal(
                    null, mUid).rxBytes;
            return mCurrentTcpBytesReceived + (mStartedTcpBytesReceived >= 0
                    ? (uidRxBytes - mStartedTcpBytesReceived) : 0);
        }

        @Override
        public long getTcpBytesSent(int which) {
            if (which == STATS_LAST) {
                return mLoadedTcpBytesSent;
            } else {
                long current = computeCurrentTcpBytesSent();
                if (which == STATS_SINCE_UNPLUGGED) {
                    current -= mTcpBytesSentAtLastUnplug;
                } else if (which == STATS_SINCE_CHARGED) {
                    current += mLoadedTcpBytesSent;
                }
                return current;
            }
        }

        @Override
        public void noteWifiRunningLocked() {
            if (!mWifiRunning) {
                mWifiRunning = true;
                if (mWifiRunningTimer == null) {
                    mWifiRunningTimer = new StopwatchTimer(Uid.this, WIFI_RUNNING,
                            mWifiRunningTimers, mUnpluggables);
                }
                mWifiRunningTimer.startRunningLocked(BatteryStatsImpl.this);
            }
        }

        @Override
        public void noteWifiStoppedLocked() {
            if (mWifiRunning) {
                mWifiRunning = false;
                mWifiRunningTimer.stopRunningLocked(BatteryStatsImpl.this);
            }
        }

        @Override
        public void noteFullWifiLockAcquiredLocked() {
            if (!mFullWifiLockOut) {
                mFullWifiLockOut = true;
                if (mFullWifiLockTimer == null) {
                    mFullWifiLockTimer = new StopwatchTimer(Uid.this, FULL_WIFI_LOCK,
                            mFullWifiLockTimers, mUnpluggables);
                }
                mFullWifiLockTimer.startRunningLocked(BatteryStatsImpl.this);
            }
        }

        @Override
        public void noteFullWifiLockReleasedLocked() {
            if (mFullWifiLockOut) {
                mFullWifiLockOut = false;
                mFullWifiLockTimer.stopRunningLocked(BatteryStatsImpl.this);
            }
        }

        @Override
        public void noteScanWifiLockAcquiredLocked() {
            if (!mScanWifiLockOut) {
                mScanWifiLockOut = true;
                if (mScanWifiLockTimer == null) {
                    mScanWifiLockTimer = new StopwatchTimer(Uid.this, SCAN_WIFI_LOCK,
                            mScanWifiLockTimers, mUnpluggables);
                }
                mScanWifiLockTimer.startRunningLocked(BatteryStatsImpl.this);
            }
        }

        @Override
        public void noteScanWifiLockReleasedLocked() {
            if (mScanWifiLockOut) {
                mScanWifiLockOut = false;
                mScanWifiLockTimer.stopRunningLocked(BatteryStatsImpl.this);
            }
        }

        @Override
        public void noteWifiMulticastEnabledLocked() {
            if (!mWifiMulticastEnabled) {
                mWifiMulticastEnabled = true;
                if (mWifiMulticastTimer == null) {
                    mWifiMulticastTimer = new StopwatchTimer(Uid.this, WIFI_MULTICAST_ENABLED,
                            mWifiMulticastTimers, mUnpluggables);
                }
                mWifiMulticastTimer.startRunningLocked(BatteryStatsImpl.this);
            }
        }

        @Override
        public void noteWifiMulticastDisabledLocked() {
            if (mWifiMulticastEnabled) {
                mWifiMulticastEnabled = false;
                mWifiMulticastTimer.stopRunningLocked(BatteryStatsImpl.this);
            }
        }

        @Override
        public void noteAudioTurnedOnLocked() {
            if (!mAudioTurnedOn) {
                mAudioTurnedOn = true;
                if (mAudioTurnedOnTimer == null) {
                    mAudioTurnedOnTimer = new StopwatchTimer(Uid.this, AUDIO_TURNED_ON,
                            null, mUnpluggables);
                }
                mAudioTurnedOnTimer.startRunningLocked(BatteryStatsImpl.this);
            }
        }

        @Override
        public void noteAudioTurnedOffLocked() {
            if (mAudioTurnedOn) {
                mAudioTurnedOn = false;
                mAudioTurnedOnTimer.stopRunningLocked(BatteryStatsImpl.this);
            }
        }

        @Override
        public void noteVideoTurnedOnLocked() {
            if (!mVideoTurnedOn) {
                mVideoTurnedOn = true;
                if (mVideoTurnedOnTimer == null) {
                    mVideoTurnedOnTimer = new StopwatchTimer(Uid.this, VIDEO_TURNED_ON,
                            null, mUnpluggables);
                }
                mVideoTurnedOnTimer.startRunningLocked(BatteryStatsImpl.this);
            }
        }

        @Override
        public void noteVideoTurnedOffLocked() {
            if (mVideoTurnedOn) {
                mVideoTurnedOn = false;
                mVideoTurnedOnTimer.stopRunningLocked(BatteryStatsImpl.this);
            }
        }

        @Override
        public long getWifiRunningTime(long batteryRealtime, int which) {
            if (mWifiRunningTimer == null) {
                return 0;
            }
            return mWifiRunningTimer.getTotalTimeLocked(batteryRealtime, which);
        }

        @Override
        public long getFullWifiLockTime(long batteryRealtime, int which) {
            if (mFullWifiLockTimer == null) {
                return 0;
            }
            return mFullWifiLockTimer.getTotalTimeLocked(batteryRealtime, which);
        }

        @Override
        public long getScanWifiLockTime(long batteryRealtime, int which) {
            if (mScanWifiLockTimer == null) {
                return 0;
            }
            return mScanWifiLockTimer.getTotalTimeLocked(batteryRealtime, which);
        }

        @Override
        public long getWifiMulticastTime(long batteryRealtime, int which) {
            if (mWifiMulticastTimer == null) {
                return 0;
            }
            return mWifiMulticastTimer.getTotalTimeLocked(batteryRealtime,
                                                          which);
        }

        @Override
        public long getAudioTurnedOnTime(long batteryRealtime, int which) {
            if (mAudioTurnedOnTimer == null) {
                return 0;
            }
            return mAudioTurnedOnTimer.getTotalTimeLocked(batteryRealtime, which);
        }

        @Override
        public long getVideoTurnedOnTime(long batteryRealtime, int which) {
            if (mVideoTurnedOnTimer == null) {
                return 0;
            }
            return mVideoTurnedOnTimer.getTotalTimeLocked(batteryRealtime, which);
        }

        @Override
        public void noteUserActivityLocked(int type) {
            if (mUserActivityCounters == null) {
                initUserActivityLocked();
            }
            if (type < 0) type = 0;
            else if (type >= NUM_USER_ACTIVITY_TYPES) type = NUM_USER_ACTIVITY_TYPES-1;
            mUserActivityCounters[type].stepAtomic();
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

        void initUserActivityLocked() {
            mUserActivityCounters = new Counter[NUM_USER_ACTIVITY_TYPES];
            for (int i=0; i<NUM_USER_ACTIVITY_TYPES; i++) {
                mUserActivityCounters[i] = new Counter(mUnpluggables);
            }
        }

        public long computeCurrentTcpBytesSent() {
            final long uidTxBytes = getNetworkStatsDetailGroupedByUid().getTotal(
                    null, mUid).txBytes;
            return mCurrentTcpBytesSent + (mStartedTcpBytesSent >= 0
                    ? (uidTxBytes - mStartedTcpBytesSent) : 0);
        }

        /**
         * Clear all stats for this uid.  Returns true if the uid is completely
         * inactive so can be dropped.
         */
        boolean reset() {
            boolean active = false;

            if (mWifiRunningTimer != null) {
                active |= !mWifiRunningTimer.reset(BatteryStatsImpl.this, false);
                active |= mWifiRunning;
            }
            if (mFullWifiLockTimer != null) {
                active |= !mFullWifiLockTimer.reset(BatteryStatsImpl.this, false);
                active |= mFullWifiLockOut;
            }
            if (mScanWifiLockTimer != null) {
                active |= !mScanWifiLockTimer.reset(BatteryStatsImpl.this, false);
                active |= mScanWifiLockOut;
            }
            if (mWifiMulticastTimer != null) {
                active |= !mWifiMulticastTimer.reset(BatteryStatsImpl.this, false);
                active |= mWifiMulticastEnabled;
            }
            if (mAudioTurnedOnTimer != null) {
                active |= !mAudioTurnedOnTimer.reset(BatteryStatsImpl.this, false);
                active |= mAudioTurnedOn;
            }
            if (mVideoTurnedOnTimer != null) {
                active |= !mVideoTurnedOnTimer.reset(BatteryStatsImpl.this, false);
                active |= mVideoTurnedOn;
            }

            mLoadedTcpBytesReceived = mLoadedTcpBytesSent = 0;
            mCurrentTcpBytesReceived = mCurrentTcpBytesSent = 0;

            if (mUserActivityCounters != null) {
                for (int i=0; i<NUM_USER_ACTIVITY_TYPES; i++) {
                    mUserActivityCounters[i].reset(false);
                }
            }

            if (mWakelockStats.size() > 0) {
                Iterator<Map.Entry<String, Wakelock>> it = mWakelockStats.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, Wakelock> wakelockEntry = it.next();
                    Wakelock wl = wakelockEntry.getValue();
                    if (wl.reset()) {
                        it.remove();
                    } else {
                        active = true;
                    }
                }
            }
            if (mSensorStats.size() > 0) {
                Iterator<Map.Entry<Integer, Sensor>> it = mSensorStats.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<Integer, Sensor> sensorEntry = it.next();
                    Sensor s = sensorEntry.getValue();
                    if (s.reset()) {
                        it.remove();
                    } else {
                        active = true;
                    }
                }
            }
            if (mProcessStats.size() > 0) {
                Iterator<Map.Entry<String, Proc>> it = mProcessStats.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, Proc> procEntry = it.next();
                    procEntry.getValue().detach();
                }
                mProcessStats.clear();
            }
            if (mPids.size() > 0) {
                for (int i=0; !active && i<mPids.size(); i++) {
                    Pid pid = mPids.valueAt(i);
                    if (pid.mWakeStart != 0) {
                        active = true;
                    }
                }
            }
            if (mPackageStats.size() > 0) {
                Iterator<Map.Entry<String, Pkg>> it = mPackageStats.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, Pkg> pkgEntry = it.next();
                    Pkg p = pkgEntry.getValue();
                    p.detach();
                    if (p.mServiceStats.size() > 0) {
                        Iterator<Map.Entry<String, Pkg.Serv>> it2
                                = p.mServiceStats.entrySet().iterator();
                        while (it2.hasNext()) {
                            Map.Entry<String, Pkg.Serv> servEntry = it2.next();
                            servEntry.getValue().detach();
                        }
                    }
                }
                mPackageStats.clear();
            }

            mPids.clear();

            if (!active) {
                if (mWifiRunningTimer != null) {
                    mWifiRunningTimer.detach();
                }
                if (mFullWifiLockTimer != null) {
                    mFullWifiLockTimer.detach();
                }
                if (mScanWifiLockTimer != null) {
                    mScanWifiLockTimer.detach();
                }
                if (mWifiMulticastTimer != null) {
                    mWifiMulticastTimer.detach();
                }
                if (mAudioTurnedOnTimer != null) {
                    mAudioTurnedOnTimer.detach();
                }
                if (mVideoTurnedOnTimer != null) {
                    mVideoTurnedOnTimer.detach();
                }
                if (mUserActivityCounters != null) {
                    for (int i=0; i<NUM_USER_ACTIVITY_TYPES; i++) {
                        mUserActivityCounters[i].detach();
                    }
                }
            }

            return !active;
        }

        void writeToParcelLocked(Parcel out, long batteryRealtime) {
            out.writeInt(mWakelockStats.size());
            for (Map.Entry<String, Uid.Wakelock> wakelockEntry : mWakelockStats.entrySet()) {
                out.writeString(wakelockEntry.getKey());
                Uid.Wakelock wakelock = wakelockEntry.getValue();
                wakelock.writeToParcelLocked(out, batteryRealtime);
            }

            out.writeInt(mSensorStats.size());
            for (Map.Entry<Integer, Uid.Sensor> sensorEntry : mSensorStats.entrySet()) {
                out.writeInt(sensorEntry.getKey());
                Uid.Sensor sensor = sensorEntry.getValue();
                sensor.writeToParcelLocked(out, batteryRealtime);
            }

            out.writeInt(mProcessStats.size());
            for (Map.Entry<String, Uid.Proc> procEntry : mProcessStats.entrySet()) {
                out.writeString(procEntry.getKey());
                Uid.Proc proc = procEntry.getValue();
                proc.writeToParcelLocked(out);
            }

            out.writeInt(mPackageStats.size());
            for (Map.Entry<String, Uid.Pkg> pkgEntry : mPackageStats.entrySet()) {
                out.writeString(pkgEntry.getKey());
                Uid.Pkg pkg = pkgEntry.getValue();
                pkg.writeToParcelLocked(out);
            }

            out.writeLong(mLoadedTcpBytesReceived);
            out.writeLong(mLoadedTcpBytesSent);
            out.writeLong(computeCurrentTcpBytesReceived());
            out.writeLong(computeCurrentTcpBytesSent());
            out.writeLong(mTcpBytesReceivedAtLastUnplug);
            out.writeLong(mTcpBytesSentAtLastUnplug);
            if (mWifiRunningTimer != null) {
                out.writeInt(1);
                mWifiRunningTimer.writeToParcel(out, batteryRealtime);
            } else {
                out.writeInt(0);
            }
            if (mFullWifiLockTimer != null) {
                out.writeInt(1);
                mFullWifiLockTimer.writeToParcel(out, batteryRealtime);
            } else {
                out.writeInt(0);
            }
            if (mScanWifiLockTimer != null) {
                out.writeInt(1);
                mScanWifiLockTimer.writeToParcel(out, batteryRealtime);
            } else {
                out.writeInt(0);
            }
            if (mWifiMulticastTimer != null) {
                out.writeInt(1);
                mWifiMulticastTimer.writeToParcel(out, batteryRealtime);
            } else {
                out.writeInt(0);
            }
            if (mAudioTurnedOnTimer != null) {
                out.writeInt(1);
                mAudioTurnedOnTimer.writeToParcel(out, batteryRealtime);
            } else {
                out.writeInt(0);
            }
            if (mVideoTurnedOnTimer != null) {
                out.writeInt(1);
                mVideoTurnedOnTimer.writeToParcel(out, batteryRealtime);
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
        }

        void readFromParcelLocked(ArrayList<Unpluggable> unpluggables, Parcel in) {
            int numWakelocks = in.readInt();
            mWakelockStats.clear();
            for (int j = 0; j < numWakelocks; j++) {
                String wakelockName = in.readString();
                Uid.Wakelock wakelock = new Wakelock();
                wakelock.readFromParcelLocked(unpluggables, in);
                // We will just drop some random set of wakelocks if
                // the previous run of the system was an older version
                // that didn't impose a limit.
                mWakelockStats.put(wakelockName, wakelock);
            }

            int numSensors = in.readInt();
            mSensorStats.clear();
            for (int k = 0; k < numSensors; k++) {
                int sensorNumber = in.readInt();
                Uid.Sensor sensor = new Sensor(sensorNumber);
                sensor.readFromParcelLocked(mUnpluggables, in);
                mSensorStats.put(sensorNumber, sensor);
            }

            int numProcs = in.readInt();
            mProcessStats.clear();
            for (int k = 0; k < numProcs; k++) {
                String processName = in.readString();
                Uid.Proc proc = new Proc();
                proc.readFromParcelLocked(in);
                mProcessStats.put(processName, proc);
            }

            int numPkgs = in.readInt();
            mPackageStats.clear();
            for (int l = 0; l < numPkgs; l++) {
                String packageName = in.readString();
                Uid.Pkg pkg = new Pkg();
                pkg.readFromParcelLocked(in);
                mPackageStats.put(packageName, pkg);
            }

            mLoadedTcpBytesReceived = in.readLong();
            mLoadedTcpBytesSent = in.readLong();
            mCurrentTcpBytesReceived = in.readLong();
            mCurrentTcpBytesSent = in.readLong();
            mTcpBytesReceivedAtLastUnplug = in.readLong();
            mTcpBytesSentAtLastUnplug = in.readLong();
            mWifiRunning = false;
            if (in.readInt() != 0) {
                mWifiRunningTimer = new StopwatchTimer(Uid.this, WIFI_RUNNING,
                        mWifiRunningTimers, mUnpluggables, in);
            } else {
                mWifiRunningTimer = null;
            }
            mFullWifiLockOut = false;
            if (in.readInt() != 0) {
                mFullWifiLockTimer = new StopwatchTimer(Uid.this, FULL_WIFI_LOCK,
                        mFullWifiLockTimers, mUnpluggables, in);
            } else {
                mFullWifiLockTimer = null;
            }
            mScanWifiLockOut = false;
            if (in.readInt() != 0) {
                mScanWifiLockTimer = new StopwatchTimer(Uid.this, SCAN_WIFI_LOCK,
                        mScanWifiLockTimers, mUnpluggables, in);
            } else {
                mScanWifiLockTimer = null;
            }
            mWifiMulticastEnabled = false;
            if (in.readInt() != 0) {
                mWifiMulticastTimer = new StopwatchTimer(Uid.this, WIFI_MULTICAST_ENABLED,
                        mWifiMulticastTimers, mUnpluggables, in);
            } else {
                mWifiMulticastTimer = null;
            }
            mAudioTurnedOn = false;
            if (in.readInt() != 0) {
                mAudioTurnedOnTimer = new StopwatchTimer(Uid.this, AUDIO_TURNED_ON,
                        null, mUnpluggables, in);
            } else {
                mAudioTurnedOnTimer = null;
            }
            mVideoTurnedOn = false;
            if (in.readInt() != 0) {
                mVideoTurnedOnTimer = new StopwatchTimer(Uid.this, VIDEO_TURNED_ON,
                        null, mUnpluggables, in);
            } else {
                mVideoTurnedOnTimer = null;
            }
            if (in.readInt() != 0) {
                mUserActivityCounters = new Counter[NUM_USER_ACTIVITY_TYPES];
                for (int i=0; i<NUM_USER_ACTIVITY_TYPES; i++) {
                    mUserActivityCounters[i] = new Counter(mUnpluggables, in);
                }
            } else {
                mUserActivityCounters = null;
            }
        }

        /**
         * The statistics associated with a particular wake lock.
         */
        public final class Wakelock extends BatteryStats.Uid.Wakelock {
            /**
             * How long (in ms) this uid has been keeping the device partially awake.
             */
            StopwatchTimer mTimerPartial;

            /**
             * How long (in ms) this uid has been keeping the device fully awake.
             */
            StopwatchTimer mTimerFull;

            /**
             * How long (in ms) this uid has had a window keeping the device awake.
             */
            StopwatchTimer mTimerWindow;

            /**
             * Reads a possibly null Timer from a Parcel.  The timer is associated with the
             * proper timer pool from the given BatteryStatsImpl object.
             *
             * @param in the Parcel to be read from.
             * return a new Timer, or null.
             */
            private StopwatchTimer readTimerFromParcel(int type, ArrayList<StopwatchTimer> pool,
                    ArrayList<Unpluggable> unpluggables, Parcel in) {
                if (in.readInt() == 0) {
                    return null;
                }

                return new StopwatchTimer(Uid.this, type, pool, unpluggables, in);
            }

            boolean reset() {
                boolean wlactive = false;
                if (mTimerFull != null) {
                    wlactive |= !mTimerFull.reset(BatteryStatsImpl.this, false);
                }
                if (mTimerPartial != null) {
                    wlactive |= !mTimerPartial.reset(BatteryStatsImpl.this, false);
                }
                if (mTimerWindow != null) {
                    wlactive |= !mTimerWindow.reset(BatteryStatsImpl.this, false);
                }
                if (!wlactive) {
                    if (mTimerFull != null) {
                        mTimerFull.detach();
                        mTimerFull = null;
                    }
                    if (mTimerPartial != null) {
                        mTimerPartial.detach();
                        mTimerPartial = null;
                    }
                    if (mTimerWindow != null) {
                        mTimerWindow.detach();
                        mTimerWindow = null;
                    }
                }
                return !wlactive;
            }

            void readFromParcelLocked(ArrayList<Unpluggable> unpluggables, Parcel in) {
                mTimerPartial = readTimerFromParcel(WAKE_TYPE_PARTIAL,
                        mPartialTimers, unpluggables, in);
                mTimerFull = readTimerFromParcel(WAKE_TYPE_FULL,
                        mFullTimers, unpluggables, in);
                mTimerWindow = readTimerFromParcel(WAKE_TYPE_WINDOW,
                        mWindowTimers, unpluggables, in);
            }

            void writeToParcelLocked(Parcel out, long batteryRealtime) {
                Timer.writeTimerToParcel(out, mTimerPartial, batteryRealtime);
                Timer.writeTimerToParcel(out, mTimerFull, batteryRealtime);
                Timer.writeTimerToParcel(out, mTimerWindow, batteryRealtime);
            }

            @Override
            public Timer getWakeTime(int type) {
                switch (type) {
                case WAKE_TYPE_FULL: return mTimerFull;
                case WAKE_TYPE_PARTIAL: return mTimerPartial;
                case WAKE_TYPE_WINDOW: return mTimerWindow;
                default: throw new IllegalArgumentException("type = " + type);
                }
            }
        }

        public final class Sensor extends BatteryStats.Uid.Sensor {
            final int mHandle;
            StopwatchTimer mTimer;

            public Sensor(int handle) {
                mHandle = handle;
            }

            private StopwatchTimer readTimerFromParcel(ArrayList<Unpluggable> unpluggables,
                    Parcel in) {
                if (in.readInt() == 0) {
                    return null;
                }

                ArrayList<StopwatchTimer> pool = mSensorTimers.get(mHandle);
                if (pool == null) {
                    pool = new ArrayList<StopwatchTimer>();
                    mSensorTimers.put(mHandle, pool);
                }
                return new StopwatchTimer(Uid.this, 0, pool, unpluggables, in);
            }

            boolean reset() {
                if (mTimer.reset(BatteryStatsImpl.this, true)) {
                    mTimer = null;
                    return true;
                }
                return false;
            }

            void readFromParcelLocked(ArrayList<Unpluggable> unpluggables, Parcel in) {
                mTimer = readTimerFromParcel(unpluggables, in);
            }

            void writeToParcelLocked(Parcel out, long batteryRealtime) {
                Timer.writeTimerToParcel(out, mTimer, batteryRealtime);
            }

            @Override
            public Timer getSensorTime() {
                return mTimer;
            }

            @Override
            public int getHandle() {
                return mHandle;
            }
        }

        /**
         * The statistics associated with a particular process.
         */
        public final class Proc extends BatteryStats.Uid.Proc implements Unpluggable {
            /**
             * Total time (in 1/100 sec) spent executing in user code.
             */
            long mUserTime;

            /**
             * Total time (in 1/100 sec) spent executing in kernel code.
             */
            long mSystemTime;

            /**
             * Number of times the process has been started.
             */
            int mStarts;

            /**
             * Amount of time the process was running in the foreground.
             */
            long mForegroundTime;

            /**
             * The amount of user time loaded from a previous save.
             */
            long mLoadedUserTime;

            /**
             * The amount of system time loaded from a previous save.
             */
            long mLoadedSystemTime;

            /**
             * The number of times the process has started from a previous save.
             */
            int mLoadedStarts;

            /**
             * The amount of foreground time loaded from a previous save.
             */
            long mLoadedForegroundTime;

            /**
             * The amount of user time loaded from the previous run.
             */
            long mLastUserTime;

            /**
             * The amount of system time loaded from the previous run.
             */
            long mLastSystemTime;

            /**
             * The number of times the process has started from the previous run.
             */
            int mLastStarts;

            /**
             * The amount of foreground time loaded from the previous run
             */
            long mLastForegroundTime;

            /**
             * The amount of user time when last unplugged.
             */
            long mUnpluggedUserTime;

            /**
             * The amount of system time when last unplugged.
             */
            long mUnpluggedSystemTime;

            /**
             * The number of times the process has started before unplugged.
             */
            int mUnpluggedStarts;

            /**
             * The amount of foreground time since unplugged.
             */
            long mUnpluggedForegroundTime;

            SamplingCounter[] mSpeedBins;

            ArrayList<ExcessivePower> mExcessivePower;

            Proc() {
                mUnpluggables.add(this);
                mSpeedBins = new SamplingCounter[getCpuSpeedSteps()];
            }

            public void unplug(long batteryUptime, long batteryRealtime) {
                mUnpluggedUserTime = mUserTime;
                mUnpluggedSystemTime = mSystemTime;
                mUnpluggedStarts = mStarts;
                mUnpluggedForegroundTime = mForegroundTime;
            }

            public void plug(long batteryUptime, long batteryRealtime) {
            }

            void detach() {
                mUnpluggables.remove(this);
                for (int i = 0; i < mSpeedBins.length; i++) {
                    SamplingCounter c = mSpeedBins[i];
                    if (c != null) {
                        mUnpluggables.remove(c);
                        mSpeedBins[i] = null;
                    }
                }
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

            public void addExcessiveWake(long overTime, long usedTime) {
                if (mExcessivePower == null) {
                    mExcessivePower = new ArrayList<ExcessivePower>();
                }
                ExcessivePower ew = new ExcessivePower();
                ew.type = ExcessivePower.TYPE_WAKE;
                ew.overTime = overTime;
                ew.usedTime = usedTime;
                mExcessivePower.add(ew);
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

            boolean readExcessivePowerFromParcelLocked(Parcel in) {
                final int N = in.readInt();
                if (N == 0) {
                    mExcessivePower = null;
                    return true;
                }

                if (N > 10000) {
                    Slog.w(TAG, "File corrupt: too many excessive power entries " + N);
                    return false;
                }
                
                mExcessivePower = new ArrayList<ExcessivePower>();
                for (int i=0; i<N; i++) {
                    ExcessivePower ew = new ExcessivePower();
                    ew.type = in.readInt();
                    ew.overTime = in.readLong();
                    ew.usedTime = in.readLong();
                    mExcessivePower.add(ew);
                }
                return true;
            }

            void writeToParcelLocked(Parcel out) {
                out.writeLong(mUserTime);
                out.writeLong(mSystemTime);
                out.writeLong(mForegroundTime);
                out.writeInt(mStarts);
                out.writeLong(mLoadedUserTime);
                out.writeLong(mLoadedSystemTime);
                out.writeLong(mLoadedForegroundTime);
                out.writeInt(mLoadedStarts);
                out.writeLong(mUnpluggedUserTime);
                out.writeLong(mUnpluggedSystemTime);
                out.writeLong(mUnpluggedForegroundTime);
                out.writeInt(mUnpluggedStarts);

                out.writeInt(mSpeedBins.length);
                for (int i = 0; i < mSpeedBins.length; i++) {
                    SamplingCounter c = mSpeedBins[i];
                    if (c != null) {
                        out.writeInt(1);
                        c.writeToParcel(out);
                    } else {
                        out.writeInt(0);
                    }
                }

                writeExcessivePowerToParcelLocked(out);
            }

            void readFromParcelLocked(Parcel in) {
                mUserTime = in.readLong();
                mSystemTime = in.readLong();
                mForegroundTime = in.readLong();
                mStarts = in.readInt();
                mLoadedUserTime = in.readLong();
                mLoadedSystemTime = in.readLong();
                mLoadedForegroundTime = in.readLong();
                mLoadedStarts = in.readInt();
                mLastUserTime = 0;
                mLastSystemTime = 0;
                mLastForegroundTime = 0;
                mLastStarts = 0;
                mUnpluggedUserTime = in.readLong();
                mUnpluggedSystemTime = in.readLong();
                mUnpluggedForegroundTime = in.readLong();
                mUnpluggedStarts = in.readInt();

                int bins = in.readInt();
                int steps = getCpuSpeedSteps();
                mSpeedBins = new SamplingCounter[bins >= steps ? bins : steps];
                for (int i = 0; i < bins; i++) {
                    if (in.readInt() != 0) {
                        mSpeedBins[i] = new SamplingCounter(mUnpluggables, in);
                    }
                }

                readExcessivePowerFromParcelLocked(in);
            }

            public BatteryStatsImpl getBatteryStats() {
                return BatteryStatsImpl.this;
            }

            public void addCpuTimeLocked(int utime, int stime) {
                mUserTime += utime;
                mSystemTime += stime;
            }

            public void addForegroundTimeLocked(long ttime) {
                mForegroundTime += ttime;
            }

            public void incStartsLocked() {
                mStarts++;
            }

            @Override
            public long getUserTime(int which) {
                long val;
                if (which == STATS_LAST) {
                    val = mLastUserTime;
                } else {
                    val = mUserTime;
                    if (which == STATS_CURRENT) {
                        val -= mLoadedUserTime;
                    } else if (which == STATS_SINCE_UNPLUGGED) {
                        val -= mUnpluggedUserTime;
                    }
                }
                return val;
            }

            @Override
            public long getSystemTime(int which) {
                long val;
                if (which == STATS_LAST) {
                    val = mLastSystemTime;
                } else {
                    val = mSystemTime;
                    if (which == STATS_CURRENT) {
                        val -= mLoadedSystemTime;
                    } else if (which == STATS_SINCE_UNPLUGGED) {
                        val -= mUnpluggedSystemTime;
                    }
                }
                return val;
            }

            @Override
            public long getForegroundTime(int which) {
                long val;
                if (which == STATS_LAST) {
                    val = mLastForegroundTime;
                } else {
                    val = mForegroundTime;
                    if (which == STATS_CURRENT) {
                        val -= mLoadedForegroundTime;
                    } else if (which == STATS_SINCE_UNPLUGGED) {
                        val -= mUnpluggedForegroundTime;
                    }
                }
                return val;
            }

            @Override
            public int getStarts(int which) {
                int val;
                if (which == STATS_LAST) {
                    val = mLastStarts;
                } else {
                    val = mStarts;
                    if (which == STATS_CURRENT) {
                        val -= mLoadedStarts;
                    } else if (which == STATS_SINCE_UNPLUGGED) {
                        val -= mUnpluggedStarts;
                    }
                }
                return val;
            }

            /* Called by ActivityManagerService when CPU times are updated. */
            public void addSpeedStepTimes(long[] values) {
                for (int i = 0; i < mSpeedBins.length && i < values.length; i++) {
                    long amt = values[i];
                    if (amt != 0) {
                        SamplingCounter c = mSpeedBins[i];
                        if (c == null) {
                            mSpeedBins[i] = c = new SamplingCounter(mUnpluggables);
                        }
                        c.addCountAtomic(values[i]);
                    }
                }
            }

            @Override
            public long getTimeAtCpuSpeedStep(int speedStep, int which) {
                if (speedStep < mSpeedBins.length) {
                    SamplingCounter c = mSpeedBins[speedStep];
                    return c != null ? c.getCountLocked(which) : 0;
                } else {
                    return 0;
                }
            }
        }

        /**
         * The statistics associated with a particular package.
         */
        public final class Pkg extends BatteryStats.Uid.Pkg implements Unpluggable {
            /**
             * Number of times this package has done something that could wake up the
             * device from sleep.
             */
            int mWakeups;

            /**
             * Number of things that could wake up the device loaded from a
             * previous save.
             */
            int mLoadedWakeups;

            /**
             * Number of things that could wake up the device as of the
             * last run.
             */
            int mLastWakeups;

            /**
             * Number of things that could wake up the device as of the
             * last run.
             */
            int mUnpluggedWakeups;

            /**
             * The statics we have collected for this package's services.
             */
            final HashMap<String, Serv> mServiceStats = new HashMap<String, Serv>();

            Pkg() {
                mUnpluggables.add(this);
            }

            public void unplug(long batteryUptime, long batteryRealtime) {
                mUnpluggedWakeups = mWakeups;
            }

            public void plug(long batteryUptime, long batteryRealtime) {
            }

            void detach() {
                mUnpluggables.remove(this);
            }

            void readFromParcelLocked(Parcel in) {
                mWakeups = in.readInt();
                mLoadedWakeups = in.readInt();
                mLastWakeups = 0;
                mUnpluggedWakeups = in.readInt();

                int numServs = in.readInt();
                mServiceStats.clear();
                for (int m = 0; m < numServs; m++) {
                    String serviceName = in.readString();
                    Uid.Pkg.Serv serv = new Serv();
                    mServiceStats.put(serviceName, serv);

                    serv.readFromParcelLocked(in);
                }
            }

            void writeToParcelLocked(Parcel out) {
                out.writeInt(mWakeups);
                out.writeInt(mLoadedWakeups);
                out.writeInt(mUnpluggedWakeups);

                out.writeInt(mServiceStats.size());
                for (Map.Entry<String, Uid.Pkg.Serv> servEntry : mServiceStats.entrySet()) {
                    out.writeString(servEntry.getKey());
                    Uid.Pkg.Serv serv = servEntry.getValue();

                    serv.writeToParcelLocked(out);
                }
            }

            @Override
            public Map<String, ? extends BatteryStats.Uid.Pkg.Serv> getServiceStats() {
                return mServiceStats;
            }

            @Override
            public int getWakeups(int which) {
                int val;
                if (which == STATS_LAST) {
                    val = mLastWakeups;
                } else {
                    val = mWakeups;
                    if (which == STATS_CURRENT) {
                        val -= mLoadedWakeups;
                    } else if (which == STATS_SINCE_UNPLUGGED) {
                        val -= mUnpluggedWakeups;
                    }
                }

                return val;
            }

            /**
             * The statistics associated with a particular service.
             */
            public final class Serv extends BatteryStats.Uid.Pkg.Serv implements Unpluggable {
                /**
                 * Total time (ms in battery uptime) the service has been left started.
                 */
                long mStartTime;

                /**
                 * If service has been started and not yet stopped, this is
                 * when it was started.
                 */
                long mRunningSince;

                /**
                 * True if we are currently running.
                 */
                boolean mRunning;

                /**
                 * Total number of times startService() has been called.
                 */
                int mStarts;

                /**
                 * Total time (ms in battery uptime) the service has been left launched.
                 */
                long mLaunchedTime;

                /**
                 * If service has been launched and not yet exited, this is
                 * when it was launched (ms in battery uptime).
                 */
                long mLaunchedSince;

                /**
                 * True if we are currently launched.
                 */
                boolean mLaunched;

                /**
                 * Total number times the service has been launched.
                 */
                int mLaunches;

                /**
                 * The amount of time spent started loaded from a previous save
                 * (ms in battery uptime).
                 */
                long mLoadedStartTime;

                /**
                 * The number of starts loaded from a previous save.
                 */
                int mLoadedStarts;

                /**
                 * The number of launches loaded from a previous save.
                 */
                int mLoadedLaunches;

                /**
                 * The amount of time spent started as of the last run (ms
                 * in battery uptime).
                 */
                long mLastStartTime;

                /**
                 * The number of starts as of the last run.
                 */
                int mLastStarts;

                /**
                 * The number of launches as of the last run.
                 */
                int mLastLaunches;

                /**
                 * The amount of time spent started when last unplugged (ms
                 * in battery uptime).
                 */
                long mUnpluggedStartTime;

                /**
                 * The number of starts when last unplugged.
                 */
                int mUnpluggedStarts;

                /**
                 * The number of launches when last unplugged.
                 */
                int mUnpluggedLaunches;

                Serv() {
                    mUnpluggables.add(this);
                }

                public void unplug(long batteryUptime, long batteryRealtime) {
                    mUnpluggedStartTime = getStartTimeToNowLocked(batteryUptime);
                    mUnpluggedStarts = mStarts;
                    mUnpluggedLaunches = mLaunches;
                }

                public void plug(long batteryUptime, long batteryRealtime) {
                }

                void detach() {
                    mUnpluggables.remove(this);
                }

                void readFromParcelLocked(Parcel in) {
                    mStartTime = in.readLong();
                    mRunningSince = in.readLong();
                    mRunning = in.readInt() != 0;
                    mStarts = in.readInt();
                    mLaunchedTime = in.readLong();
                    mLaunchedSince = in.readLong();
                    mLaunched = in.readInt() != 0;
                    mLaunches = in.readInt();
                    mLoadedStartTime = in.readLong();
                    mLoadedStarts = in.readInt();
                    mLoadedLaunches = in.readInt();
                    mLastStartTime = 0;
                    mLastStarts = 0;
                    mLastLaunches = 0;
                    mUnpluggedStartTime = in.readLong();
                    mUnpluggedStarts = in.readInt();
                    mUnpluggedLaunches = in.readInt();
                }

                void writeToParcelLocked(Parcel out) {
                    out.writeLong(mStartTime);
                    out.writeLong(mRunningSince);
                    out.writeInt(mRunning ? 1 : 0);
                    out.writeInt(mStarts);
                    out.writeLong(mLaunchedTime);
                    out.writeLong(mLaunchedSince);
                    out.writeInt(mLaunched ? 1 : 0);
                    out.writeInt(mLaunches);
                    out.writeLong(mLoadedStartTime);
                    out.writeInt(mLoadedStarts);
                    out.writeInt(mLoadedLaunches);
                    out.writeLong(mUnpluggedStartTime);
                    out.writeInt(mUnpluggedStarts);
                    out.writeInt(mUnpluggedLaunches);
                }

                long getLaunchTimeToNowLocked(long batteryUptime) {
                    if (!mLaunched) return mLaunchedTime;
                    return mLaunchedTime + batteryUptime - mLaunchedSince;
                }

                long getStartTimeToNowLocked(long batteryUptime) {
                    if (!mRunning) return mStartTime;
                    return mStartTime + batteryUptime - mRunningSince;
                }

                public void startLaunchedLocked() {
                    if (!mLaunched) {
                        mLaunches++;
                        mLaunchedSince = getBatteryUptimeLocked();
                        mLaunched = true;
                    }
                }

                public void stopLaunchedLocked() {
                    if (mLaunched) {
                        long time = getBatteryUptimeLocked() - mLaunchedSince;
                        if (time > 0) {
                            mLaunchedTime += time;
                        } else {
                            mLaunches--;
                        }
                        mLaunched = false;
                    }
                }

                public void startRunningLocked() {
                    if (!mRunning) {
                        mStarts++;
                        mRunningSince = getBatteryUptimeLocked();
                        mRunning = true;
                    }
                }

                public void stopRunningLocked() {
                    if (mRunning) {
                        long time = getBatteryUptimeLocked() - mRunningSince;
                        if (time > 0) {
                            mStartTime += time;
                        } else {
                            mStarts--;
                        }
                        mRunning = false;
                    }
                }

                public BatteryStatsImpl getBatteryStats() {
                    return BatteryStatsImpl.this;
                }

                @Override
                public int getLaunches(int which) {
                    int val;

                    if (which == STATS_LAST) {
                        val = mLastLaunches;
                    } else {
                        val = mLaunches;
                        if (which == STATS_CURRENT) {
                            val -= mLoadedLaunches;
                        } else if (which == STATS_SINCE_UNPLUGGED) {
                            val -= mUnpluggedLaunches;
                        }
                    }

                    return val;
                }

                @Override
                public long getStartTime(long now, int which) {
                    long val;
                    if (which == STATS_LAST) {
                        val = mLastStartTime;
                    } else {
                        val = getStartTimeToNowLocked(now);
                        if (which == STATS_CURRENT) {
                            val -= mLoadedStartTime;
                        } else if (which == STATS_SINCE_UNPLUGGED) {
                            val -= mUnpluggedStartTime;
                        }
                    }

                    return val;
                }

                @Override
                public int getStarts(int which) {
                    int val;
                    if (which == STATS_LAST) {
                        val = mLastStarts;
                    } else {
                        val = mStarts;
                        if (which == STATS_CURRENT) {
                            val -= mLoadedStarts;
                        } else if (which == STATS_SINCE_UNPLUGGED) {
                            val -= mUnpluggedStarts;
                        }
                    }

                    return val;
                }
            }

            public BatteryStatsImpl getBatteryStats() {
                return BatteryStatsImpl.this;
            }

            public void incWakeupsLocked() {
                mWakeups++;
            }

            final Serv newServiceStatsLocked() {
                return new Serv();
            }
        }

        /**
         * Retrieve the statistics object for a particular process, creating
         * if needed.
         */
        public Proc getProcessStatsLocked(String name) {
            Proc ps = mProcessStats.get(name);
            if (ps == null) {
                ps = new Proc();
                mProcessStats.put(name, ps);
            }

            return ps;
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
                ps = new Pkg();
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

        public StopwatchTimer getWakeTimerLocked(String name, int type) {
            Wakelock wl = mWakelockStats.get(name);
            if (wl == null) {
                final int N = mWakelockStats.size();
                if (N > MAX_WAKELOCKS_PER_UID && (mUid != Process.SYSTEM_UID
                        || N > MAX_WAKELOCKS_PER_UID_IN_SYSTEM)) {
                    name = BATCHED_WAKELOCK_NAME;
                    wl = mWakelockStats.get(name);
                }
                if (wl == null) {
                    wl = new Wakelock();
                    mWakelockStats.put(name, wl);
                }
            }
            StopwatchTimer t = null;
            switch (type) {
                case WAKE_TYPE_PARTIAL:
                    t = wl.mTimerPartial;
                    if (t == null) {
                        t = new StopwatchTimer(Uid.this, WAKE_TYPE_PARTIAL,
                                mPartialTimers, mUnpluggables);
                        wl.mTimerPartial = t;
                    }
                    return t;
                case WAKE_TYPE_FULL:
                    t = wl.mTimerFull;
                    if (t == null) {
                        t = new StopwatchTimer(Uid.this, WAKE_TYPE_FULL,
                                mFullTimers, mUnpluggables);
                        wl.mTimerFull = t;
                    }
                    return t;
                case WAKE_TYPE_WINDOW:
                    t = wl.mTimerWindow;
                    if (t == null) {
                        t = new StopwatchTimer(Uid.this, WAKE_TYPE_WINDOW,
                                mWindowTimers, mUnpluggables);
                        wl.mTimerWindow = t;
                    }
                    return t;
                default:
                    throw new IllegalArgumentException("type=" + type);
            }
        }

        public StopwatchTimer getSensorTimerLocked(int sensor, boolean create) {
            Sensor se = mSensorStats.get(sensor);
            if (se == null) {
                if (!create) {
                    return null;
                }
                se = new Sensor(sensor);
                mSensorStats.put(sensor, se);
            }
            StopwatchTimer t = se.mTimer;
            if (t != null) {
                return t;
            }
            ArrayList<StopwatchTimer> timers = mSensorTimers.get(sensor);
            if (timers == null) {
                timers = new ArrayList<StopwatchTimer>();
                mSensorTimers.put(sensor, timers);
            }
            t = new StopwatchTimer(Uid.this, BatteryStats.SENSOR, timers, mUnpluggables);
            se.mTimer = t;
            return t;
        }

        public void noteStartWakeLocked(int pid, String name, int type) {
            StopwatchTimer t = getWakeTimerLocked(name, type);
            if (t != null) {
                t.startRunningLocked(BatteryStatsImpl.this);
            }
            if (pid >= 0 && type == WAKE_TYPE_PARTIAL) {
                Pid p = getPidStatsLocked(pid);
                if (p.mWakeStart == 0) {
                    p.mWakeStart = SystemClock.elapsedRealtime();
                }
            }
        }

        public void noteStopWakeLocked(int pid, String name, int type) {
            StopwatchTimer t = getWakeTimerLocked(name, type);
            if (t != null) {
                t.stopRunningLocked(BatteryStatsImpl.this);
            }
            if (pid >= 0 && type == WAKE_TYPE_PARTIAL) {
                Pid p = mPids.get(pid);
                if (p != null && p.mWakeStart != 0) {
                    p.mWakeSum += SystemClock.elapsedRealtime() - p.mWakeStart;
                    p.mWakeStart = 0;
                }
            }
        }

        public void reportExcessiveWakeLocked(String proc, long overTime, long usedTime) {
            Proc p = getProcessStatsLocked(proc);
            if (p != null) {
                p.addExcessiveWake(overTime, usedTime);
            }
        }

        public void reportExcessiveCpuLocked(String proc, long overTime, long usedTime) {
            Proc p = getProcessStatsLocked(proc);
            if (p != null) {
                p.addExcessiveCpu(overTime, usedTime);
            }
        }

        public void noteStartSensor(int sensor) {
            StopwatchTimer t = getSensorTimerLocked(sensor, true);
            if (t != null) {
                t.startRunningLocked(BatteryStatsImpl.this);
            }
        }

        public void noteStopSensor(int sensor) {
            // Don't create a timer if one doesn't already exist
            StopwatchTimer t = getSensorTimerLocked(sensor, false);
            if (t != null) {
                t.stopRunningLocked(BatteryStatsImpl.this);
            }
        }

        public void noteStartGps() {
            StopwatchTimer t = getSensorTimerLocked(Sensor.GPS, true);
            if (t != null) {
                t.startRunningLocked(BatteryStatsImpl.this);
            }
        }

        public void noteStopGps() {
            StopwatchTimer t = getSensorTimerLocked(Sensor.GPS, false);
            if (t != null) {
                t.stopRunningLocked(BatteryStatsImpl.this);
            }
        }

        public BatteryStatsImpl getBatteryStats() {
            return BatteryStatsImpl.this;
        }
    }

    public BatteryStatsImpl(String filename) {
        mFile = new JournaledFile(new File(filename), new File(filename + ".tmp"));
        mHandler = new MyHandler();
        mStartCount++;
        mScreenOnTimer = new StopwatchTimer(null, -1, null, mUnpluggables);
        for (int i=0; i<NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            mScreenBrightnessTimer[i] = new StopwatchTimer(null, -100-i, null, mUnpluggables);
        }
        mInputEventCounter = new Counter(mUnpluggables);
        mPhoneOnTimer = new StopwatchTimer(null, -2, null, mUnpluggables);
        for (int i=0; i<SignalStrength.NUM_SIGNAL_STRENGTH_BINS; i++) {
            mPhoneSignalStrengthsTimer[i] = new StopwatchTimer(null, -200-i, null, mUnpluggables);
        }
        mPhoneSignalScanningTimer = new StopwatchTimer(null, -200+1, null, mUnpluggables);
        for (int i=0; i<NUM_DATA_CONNECTION_TYPES; i++) {
            mPhoneDataConnectionsTimer[i] = new StopwatchTimer(null, -300-i, null, mUnpluggables);
        }
        mWifiOnTimer = new StopwatchTimer(null, -3, null, mUnpluggables);
        mGlobalWifiRunningTimer = new StopwatchTimer(null, -4, null, mUnpluggables);
        mBluetoothOnTimer = new StopwatchTimer(null, -5, null, mUnpluggables);
        mAudioOnTimer = new StopwatchTimer(null, -6, null, mUnpluggables);
        mVideoOnTimer = new StopwatchTimer(null, -7, null, mUnpluggables);
        mOnBattery = mOnBatteryInternal = false;
        initTimes();
        mTrackBatteryPastUptime = 0;
        mTrackBatteryPastRealtime = 0;
        mUptimeStart = mTrackBatteryUptimeStart = SystemClock.uptimeMillis() * 1000;
        mRealtimeStart = mTrackBatteryRealtimeStart = SystemClock.elapsedRealtime() * 1000;
        mUnpluggedBatteryUptime = getBatteryUptimeLocked(mUptimeStart);
        mUnpluggedBatteryRealtime = getBatteryRealtimeLocked(mRealtimeStart);
        mDischargeStartLevel = 0;
        mDischargeUnplugLevel = 0;
        mDischargeCurrentLevel = 0;
        initDischarge();
        clearHistoryLocked();
    }

    public BatteryStatsImpl(Parcel p) {
        mFile = null;
        mHandler = null;
        clearHistoryLocked();
        readFromParcel(p);
    }

    public void setCallback(BatteryCallback cb) {
        mCallback = cb;
    }

    public void setNumSpeedSteps(int steps) {
        if (sNumSpeedSteps == 0) sNumSpeedSteps = steps;
    }

    public void setRadioScanningTimeout(long timeout) {
        if (mPhoneSignalScanningTimer != null) {
            mPhoneSignalScanningTimer.setTimeout(timeout);
        }
    }

    @Override
    public boolean startIteratingOldHistoryLocked() {
        if (DEBUG_HISTORY) Slog.i(TAG, "ITERATING: buff size=" + mHistoryBuffer.dataSize()
                + " pos=" + mHistoryBuffer.dataPosition());
        mHistoryBuffer.setDataPosition(0);
        mHistoryReadTmp.clear();
        mReadOverflow = false;
        mIteratingHistory = true;
        return (mHistoryIterator = mHistory) != null;
    }

    @Override
    public boolean getNextOldHistoryLocked(HistoryItem out) {
        boolean end = mHistoryBuffer.dataPosition() >= mHistoryBuffer.dataSize();
        if (!end) {
            mHistoryReadTmp.readDelta(mHistoryBuffer);
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
                long now = getHistoryBaseTime() + SystemClock.elapsedRealtime();
                PrintWriter pw = new PrintWriter(new LogWriter(android.util.Log.WARN, TAG));
                pw.println("Histories differ!");
                pw.println("Old history:");
                (new HistoryPrinter()).printNextItem(pw, out, now);
                pw.println("New history:");
                (new HistoryPrinter()).printNextItem(pw, mHistoryReadTmp, now);
            }
        }
        return true;
    }

    @Override
    public void finishIteratingOldHistoryLocked() {
        mIteratingHistory = false;
        mHistoryBuffer.setDataPosition(mHistoryBuffer.dataSize());
    }

    @Override
    public boolean startIteratingHistoryLocked() {
        if (DEBUG_HISTORY) Slog.i(TAG, "ITERATING: buff size=" + mHistoryBuffer.dataSize()
                + " pos=" + mHistoryBuffer.dataPosition());
        mHistoryBuffer.setDataPosition(0);
        mReadOverflow = false;
        mIteratingHistory = true;
        return mHistoryBuffer.dataSize() > 0;
    }

    @Override
    public boolean getNextHistoryLocked(HistoryItem out) {
        final int pos = mHistoryBuffer.dataPosition();
        if (pos == 0) {
            out.clear();
        }
        boolean end = pos >= mHistoryBuffer.dataSize();
        if (end) {
            return false;
        }

        out.readDelta(mHistoryBuffer);
        return true;
    }

    @Override
    public void finishIteratingHistoryLocked() {
        mIteratingHistory = false;
        mHistoryBuffer.setDataPosition(mHistoryBuffer.dataSize());
    }

    @Override
    public long getHistoryBaseTime() {
        return mHistoryBaseTime;
    }

    @Override
    public int getStartCount() {
        return mStartCount;
    }

    public boolean isOnBattery() {
        return mOnBattery;
    }

    public boolean isScreenOn() {
        return mScreenOn;
    }

    void initTimes() {
        mBatteryRealtime = mTrackBatteryPastUptime = 0;
        mBatteryUptime = mTrackBatteryPastRealtime = 0;
        mUptimeStart = mTrackBatteryUptimeStart = SystemClock.uptimeMillis() * 1000;
        mRealtimeStart = mTrackBatteryRealtimeStart = SystemClock.elapsedRealtime() * 1000;
        mUnpluggedBatteryUptime = getBatteryUptimeLocked(mUptimeStart);
        mUnpluggedBatteryRealtime = getBatteryRealtimeLocked(mRealtimeStart);
    }

    void initDischarge() {
        mLowDischargeAmountSinceCharge = 0;
        mHighDischargeAmountSinceCharge = 0;
        mDischargeAmountScreenOn = 0;
        mDischargeAmountScreenOnSinceCharge = 0;
        mDischargeAmountScreenOff = 0;
        mDischargeAmountScreenOffSinceCharge = 0;
    }
    
    public void resetAllStatsLocked() {
        mStartCount = 0;
        initTimes();
        mScreenOnTimer.reset(this, false);
        for (int i=0; i<NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            mScreenBrightnessTimer[i].reset(this, false);
        }
        mInputEventCounter.reset(false);
        mPhoneOnTimer.reset(this, false);
        mAudioOnTimer.reset(this, false);
        mVideoOnTimer.reset(this, false);
        for (int i=0; i<SignalStrength.NUM_SIGNAL_STRENGTH_BINS; i++) {
            mPhoneSignalStrengthsTimer[i].reset(this, false);
        }
        mPhoneSignalScanningTimer.reset(this, false);
        for (int i=0; i<NUM_DATA_CONNECTION_TYPES; i++) {
            mPhoneDataConnectionsTimer[i].reset(this, false);
        }
        mWifiOnTimer.reset(this, false);
        mGlobalWifiRunningTimer.reset(this, false);
        mBluetoothOnTimer.reset(this, false);

        for (int i=0; i<mUidStats.size(); i++) {
            if (mUidStats.valueAt(i).reset()) {
                mUidStats.remove(mUidStats.keyAt(i));
                i--;
            }
        }

        if (mKernelWakelockStats.size() > 0) {
            for (SamplingTimer timer : mKernelWakelockStats.values()) {
                mUnpluggables.remove(timer);
            }
            mKernelWakelockStats.clear();
        }
        
        initDischarge();

        clearHistoryLocked();
    }

    void updateDischargeScreenLevelsLocked(boolean oldScreenOn, boolean newScreenOn) {
        if (oldScreenOn) {
            int diff = mDischargeScreenOnUnplugLevel - mDischargeCurrentLevel;
            if (diff > 0) {
                mDischargeAmountScreenOn += diff;
                mDischargeAmountScreenOnSinceCharge += diff;
            }
        } else {
            int diff = mDischargeScreenOffUnplugLevel - mDischargeCurrentLevel;
            if (diff > 0) {
                mDischargeAmountScreenOff += diff;
                mDischargeAmountScreenOffSinceCharge += diff;
            }
        }
        if (newScreenOn) {
            mDischargeScreenOnUnplugLevel = mDischargeCurrentLevel;
            mDischargeScreenOffUnplugLevel = 0;
        } else {
            mDischargeScreenOnUnplugLevel = 0;
            mDischargeScreenOffUnplugLevel = mDischargeCurrentLevel;
        }
    }
    
    void setOnBattery(boolean onBattery, int oldStatus, int level) {
        synchronized(this) {
            setOnBatteryLocked(onBattery, oldStatus, level);
        }
    }

    void setOnBatteryLocked(boolean onBattery, int oldStatus, int level) {
        boolean doWrite = false;
        Message m = mHandler.obtainMessage(MSG_REPORT_POWER_CHANGE);
        m.arg1 = onBattery ? 1 : 0;
        mHandler.sendMessage(m);
        mOnBattery = mOnBatteryInternal = onBattery;

        long uptime = SystemClock.uptimeMillis() * 1000;
        long mSecRealtime = SystemClock.elapsedRealtime();
        long realtime = mSecRealtime * 1000;
        if (onBattery) {
            // We will reset our status if we are unplugging after the
            // battery was last full, or the level is at 100, or
            // we have gone through a significant charge (from a very low
            // level to a now very high level).
            if (oldStatus == BatteryManager.BATTERY_STATUS_FULL
                    || level >= 90
                    || (mDischargeCurrentLevel < 20 && level >= 80)) {
                doWrite = true;
                resetAllStatsLocked();
                mDischargeStartLevel = level;
            }
            updateKernelWakelocksLocked();
            mHistoryCur.batteryLevel = (byte)level;
            mHistoryCur.states &= ~HistoryItem.STATE_BATTERY_PLUGGED_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Battery unplugged to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(mSecRealtime);
            mTrackBatteryUptimeStart = uptime;
            mTrackBatteryRealtimeStart = realtime;
            mUnpluggedBatteryUptime = getBatteryUptimeLocked(uptime);
            mUnpluggedBatteryRealtime = getBatteryRealtimeLocked(realtime);
            mDischargeCurrentLevel = mDischargeUnplugLevel = level;
            if (mScreenOn) {
                mDischargeScreenOnUnplugLevel = level;
                mDischargeScreenOffUnplugLevel = 0;
            } else {
                mDischargeScreenOnUnplugLevel = 0;
                mDischargeScreenOffUnplugLevel = level;
            }
            mDischargeAmountScreenOn = 0;
            mDischargeAmountScreenOff = 0;
            doUnplugLocked(mUnpluggedBatteryUptime, mUnpluggedBatteryRealtime);
        } else {
            updateKernelWakelocksLocked();
            mHistoryCur.batteryLevel = (byte)level;
            mHistoryCur.states |= HistoryItem.STATE_BATTERY_PLUGGED_FLAG;
            if (DEBUG_HISTORY) Slog.v(TAG, "Battery plugged to: "
                    + Integer.toHexString(mHistoryCur.states));
            addHistoryRecordLocked(mSecRealtime);
            mTrackBatteryPastUptime += uptime - mTrackBatteryUptimeStart;
            mTrackBatteryPastRealtime += realtime - mTrackBatteryRealtimeStart;
            mDischargeCurrentLevel = level;
            if (level < mDischargeUnplugLevel) {
                mLowDischargeAmountSinceCharge += mDischargeUnplugLevel-level-1;
                mHighDischargeAmountSinceCharge += mDischargeUnplugLevel-level;
            }
            updateDischargeScreenLevelsLocked(mScreenOn, mScreenOn);
            doPlugLocked(getBatteryUptimeLocked(uptime), getBatteryRealtimeLocked(realtime));
        }
        if (doWrite || (mLastWriteTime + (60 * 1000)) < mSecRealtime) {
            if (mFile != null) {
                writeAsyncLocked();
            }
        }
    }

    // This should probably be exposed in the API, though it's not critical
    private static final int BATTERY_PLUGGED_NONE = 0;

    public void setBatteryState(int status, int health, int plugType, int level,
            int temp, int volt) {
        synchronized(this) {
            boolean onBattery = plugType == BATTERY_PLUGGED_NONE;
            int oldStatus = mHistoryCur.batteryStatus;
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
                oldStatus = status;
            }
            if (onBattery) {
                mDischargeCurrentLevel = level;
                mRecordingHistory = true;
            }
            if (onBattery != mOnBattery) {
                mHistoryCur.batteryLevel = (byte)level;
                mHistoryCur.batteryStatus = (byte)status;
                mHistoryCur.batteryHealth = (byte)health;
                mHistoryCur.batteryPlugType = (byte)plugType;
                mHistoryCur.batteryTemperature = (char)temp;
                mHistoryCur.batteryVoltage = (char)volt;
                setOnBatteryLocked(onBattery, oldStatus, level);
            } else {
                boolean changed = false;
                if (mHistoryCur.batteryLevel != level) {
                    mHistoryCur.batteryLevel = (byte)level;
                    changed = true;
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
                    mHistoryCur.batteryTemperature = (char)temp;
                    changed = true;
                }
                if (volt > (mHistoryCur.batteryVoltage+20)
                        || volt < (mHistoryCur.batteryVoltage-20)) {
                    mHistoryCur.batteryVoltage = (char)volt;
                    changed = true;
                }
                if (changed) {
                    addHistoryRecordLocked(SystemClock.elapsedRealtime());
                }
            }
            if (!onBattery && status == BatteryManager.BATTERY_STATUS_FULL) {
                // We don't record history while we are plugged in and fully charged.
                // The next time we are unplugged, history will be cleared.
                mRecordingHistory = false;
            }
        }
    }

    public void updateKernelWakelocksLocked() {
        Map<String, KernelWakelockStats> m = readKernelWakelockStats();

        if (m == null) {
            // Not crashing might make board bringup easier.
            Slog.w(TAG, "Couldn't get kernel wake lock stats");
            return;
        }

        for (Map.Entry<String, KernelWakelockStats> ent : m.entrySet()) {
            String name = ent.getKey();
            KernelWakelockStats kws = ent.getValue();

            SamplingTimer kwlt = mKernelWakelockStats.get(name);
            if (kwlt == null) {
                kwlt = new SamplingTimer(mUnpluggables, mOnBatteryInternal,
                        true /* track reported values */);
                mKernelWakelockStats.put(name, kwlt);
            }
            kwlt.updateCurrentReportedCount(kws.mCount);
            kwlt.updateCurrentReportedTotalTime(kws.mTotalTime);
            kwlt.setUpdateVersion(sKernelWakelockUpdateVersion);
        }

        if (m.size() != mKernelWakelockStats.size()) {
            // Set timers to stale if they didn't appear in /proc/wakelocks this time.
            for (Map.Entry<String, SamplingTimer> ent : mKernelWakelockStats.entrySet()) {
                SamplingTimer st = ent.getValue();
                if (st.getUpdateVersion() != sKernelWakelockUpdateVersion) {
                    st.setStale();
                }
            }
        }
    }

    public long getAwakeTimeBattery() {
        return computeBatteryUptime(getBatteryUptimeLocked(), STATS_CURRENT);
    }

    public long getAwakeTimePlugged() {
        return (SystemClock.uptimeMillis() * 1000) - getAwakeTimeBattery();
    }

    @Override
    public long computeUptime(long curTime, int which) {
        switch (which) {
            case STATS_SINCE_CHARGED: return mUptime + (curTime-mUptimeStart);
            case STATS_LAST: return mLastUptime;
            case STATS_CURRENT: return (curTime-mUptimeStart);
            case STATS_SINCE_UNPLUGGED: return (curTime-mTrackBatteryUptimeStart);
        }
        return 0;
    }

    @Override
    public long computeRealtime(long curTime, int which) {
        switch (which) {
            case STATS_SINCE_CHARGED: return mRealtime + (curTime-mRealtimeStart);
            case STATS_LAST: return mLastRealtime;
            case STATS_CURRENT: return (curTime-mRealtimeStart);
            case STATS_SINCE_UNPLUGGED: return (curTime-mTrackBatteryRealtimeStart);
        }
        return 0;
    }

    @Override
    public long computeBatteryUptime(long curTime, int which) {
        switch (which) {
            case STATS_SINCE_CHARGED:
                return mBatteryUptime + getBatteryUptime(curTime);
            case STATS_LAST:
                return mBatteryLastUptime;
            case STATS_CURRENT:
                return getBatteryUptime(curTime);
            case STATS_SINCE_UNPLUGGED:
                return getBatteryUptimeLocked(curTime) - mUnpluggedBatteryUptime;
        }
        return 0;
    }

    @Override
    public long computeBatteryRealtime(long curTime, int which) {
        switch (which) {
            case STATS_SINCE_CHARGED:
                return mBatteryRealtime + getBatteryRealtimeLocked(curTime);
            case STATS_LAST:
                return mBatteryLastRealtime;
            case STATS_CURRENT:
                return getBatteryRealtimeLocked(curTime);
            case STATS_SINCE_UNPLUGGED:
                return getBatteryRealtimeLocked(curTime) - mUnpluggedBatteryRealtime;
        }
        return 0;
    }

    long getBatteryUptimeLocked(long curTime) {
        long time = mTrackBatteryPastUptime;
        if (mOnBatteryInternal) {
            time += curTime - mTrackBatteryUptimeStart;
        }
        return time;
    }

    long getBatteryUptimeLocked() {
        return getBatteryUptime(SystemClock.uptimeMillis() * 1000);
    }

    @Override
    public long getBatteryUptime(long curTime) {
        return getBatteryUptimeLocked(curTime);
    }

    long getBatteryRealtimeLocked(long curTime) {
        long time = mTrackBatteryPastRealtime;
        if (mOnBatteryInternal) {
            time += curTime - mTrackBatteryRealtimeStart;
        }
        return time;
    }

    @Override
    public long getBatteryRealtime(long curTime) {
        return getBatteryRealtimeLocked(curTime);
    }

    private long getTcpBytes(long current, long[] dataBytes, int which) {
        if (which == STATS_LAST) {
            return dataBytes[STATS_LAST];
        } else {
            if (which == STATS_SINCE_UNPLUGGED) {
                if (dataBytes[STATS_SINCE_UNPLUGGED] < 0) {
                    return dataBytes[STATS_LAST];
                } else {
                    return current - dataBytes[STATS_SINCE_UNPLUGGED];
                }
            } else if (which == STATS_SINCE_CHARGED) {
                return (current - dataBytes[STATS_CURRENT]) + dataBytes[STATS_SINCE_CHARGED];
            }
            return current - dataBytes[STATS_CURRENT];
        }
    }

    /** Only STATS_UNPLUGGED works properly */
    public long getMobileTcpBytesSent(int which) {
        final long mobileTxBytes = getNetworkStatsSummary().getTotal(null, mMobileIfaces).txBytes;
        return getTcpBytes(mobileTxBytes, mMobileDataTx, which);
    }

    /** Only STATS_UNPLUGGED works properly */
    public long getMobileTcpBytesReceived(int which) {
        final long mobileRxBytes = getNetworkStatsSummary().getTotal(null, mMobileIfaces).rxBytes;
        return getTcpBytes(mobileRxBytes, mMobileDataRx, which);
    }

    /** Only STATS_UNPLUGGED works properly */
    public long getTotalTcpBytesSent(int which) {
        final long totalTxBytes = getNetworkStatsSummary().getTotal(null).txBytes;
        return getTcpBytes(totalTxBytes, mTotalDataTx, which);
    }

    /** Only STATS_UNPLUGGED works properly */
    public long getTotalTcpBytesReceived(int which) {
        final long totalRxBytes = getNetworkStatsSummary().getTotal(null).rxBytes;
        return getTcpBytes(totalRxBytes, mTotalDataRx, which);
    }

    @Override
    public int getDischargeStartLevel() {
        synchronized(this) {
            return getDischargeStartLevelLocked();
        }
    }

    public int getDischargeStartLevelLocked() {
            return mDischargeUnplugLevel;
    }

    @Override
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
    
    public int getDischargeAmountScreenOn() {
        synchronized(this) {
            int val = mDischargeAmountScreenOn;
            if (mOnBattery && mScreenOn
                    && mDischargeCurrentLevel < mDischargeScreenOnUnplugLevel) {
                val += mDischargeScreenOnUnplugLevel-mDischargeCurrentLevel;
            }
            return val;
        }
    }

    public int getDischargeAmountScreenOnSinceCharge() {
        synchronized(this) {
            int val = mDischargeAmountScreenOnSinceCharge;
            if (mOnBattery && mScreenOn
                    && mDischargeCurrentLevel < mDischargeScreenOnUnplugLevel) {
                val += mDischargeScreenOnUnplugLevel-mDischargeCurrentLevel;
            }
            return val;
        }
    }

    public int getDischargeAmountScreenOff() {
        synchronized(this) {
            int val = mDischargeAmountScreenOff;
            if (mOnBattery && !mScreenOn
                    && mDischargeCurrentLevel < mDischargeScreenOffUnplugLevel) {
                val += mDischargeScreenOffUnplugLevel-mDischargeCurrentLevel;
            }
            return val;
        }
    }

    public int getDischargeAmountScreenOffSinceCharge() {
        synchronized(this) {
            int val = mDischargeAmountScreenOffSinceCharge;
            if (mOnBattery && !mScreenOn
                    && mDischargeCurrentLevel < mDischargeScreenOffUnplugLevel) {
                val += mDischargeScreenOffUnplugLevel-mDischargeCurrentLevel;
            }
            return val;
        }
    }

    @Override
    public int getCpuSpeedSteps() {
        return sNumSpeedSteps;
    }

    /**
     * Retrieve the statistics object for a particular uid, creating if needed.
     */
    public Uid getUidStatsLocked(int uid) {
        Uid u = mUidStats.get(uid);
        if (u == null) {
            u = new Uid(uid);
            mUidStats.put(uid, u);
        }
        return u;
    }

    /**
     * Remove the statistics object for a particular uid.
     */
    public void removeUidStatsLocked(int uid) {
        mUidStats.remove(uid);
    }

    /**
     * Retrieve the statistics object for a particular process, creating
     * if needed.
     */
    public Uid.Proc getProcessStatsLocked(int uid, String name) {
        Uid u = getUidStatsLocked(uid);
        return u.getProcessStatsLocked(name);
    }

    /**
     * Retrieve the statistics object for a particular process, given
     * the name of the process.
     * @param name process name
     * @return the statistics object for the process
     */
    public Uid.Proc getProcessStatsLocked(String name, int pid) {
        int uid;
        if (mUidCache.containsKey(name)) {
            uid = mUidCache.get(name);
        } else {
            uid = Process.getUidForPid(pid);
            mUidCache.put(name, uid);
        }
        Uid u = getUidStatsLocked(uid);
        return u.getProcessStatsLocked(name);
    }

    /**
     * Retrieve the statistics object for a particular process, creating
     * if needed.
     */
    public Uid.Pkg getPackageStatsLocked(int uid, String pkg) {
        Uid u = getUidStatsLocked(uid);
        return u.getPackageStatsLocked(pkg);
    }

    /**
     * Retrieve the statistics object for a particular service, creating
     * if needed.
     */
    public Uid.Pkg.Serv getServiceStatsLocked(int uid, String pkg, String name) {
        Uid u = getUidStatsLocked(uid);
        return u.getServiceStatsLocked(pkg, name);
    }

    /**
     * Massage data to distribute any reasonable work down to more specific
     * owners.  Must only be called on a dead BatteryStats object!
     */
    public void distributeWorkLocked(int which) {
        // Aggregate all CPU time associated with WIFI.
        Uid wifiUid = mUidStats.get(Process.WIFI_UID);
        if (wifiUid != null) {
            long uSecTime = computeBatteryRealtime(SystemClock.elapsedRealtime() * 1000, which);
            for (Uid.Proc proc : wifiUid.mProcessStats.values()) {
                long totalRunningTime = getGlobalWifiRunningTime(uSecTime, which);
                for (int i=0; i<mUidStats.size(); i++) {
                    Uid uid = mUidStats.valueAt(i);
                    if (uid.mUid != Process.WIFI_UID) {
                        long uidRunningTime = uid.getWifiRunningTime(uSecTime, which);
                        if (uidRunningTime > 0) {
                            Uid.Proc uidProc = uid.getProcessStatsLocked("*wifi*");
                            long time = proc.getUserTime(which);
                            time = (time*uidRunningTime)/totalRunningTime;
                            uidProc.mUserTime += time;
                            proc.mUserTime -= time;
                            time = proc.getSystemTime(which);
                            time = (time*uidRunningTime)/totalRunningTime;
                            uidProc.mSystemTime += time;
                            proc.mSystemTime -= time;
                            time = proc.getForegroundTime(which);
                            time = (time*uidRunningTime)/totalRunningTime;
                            uidProc.mForegroundTime += time;
                            proc.mForegroundTime -= time;
                            for (int sb=0; sb<proc.mSpeedBins.length; sb++) {
                                SamplingCounter sc = proc.mSpeedBins[sb];
                                if (sc != null) {
                                    time = sc.getCountLocked(which);
                                    time = (time*uidRunningTime)/totalRunningTime;
                                    SamplingCounter uidSc = uidProc.mSpeedBins[sb];
                                    if (uidSc == null) {
                                        uidSc = new SamplingCounter(mUnpluggables);
                                        uidProc.mSpeedBins[sb] = uidSc;
                                    }
                                    uidSc.mCount.addAndGet((int)time);
                                    sc.mCount.addAndGet((int)-time);
                                }
                            }
                            totalRunningTime -= uidRunningTime;
                        }
                    }
                }
            }
        }
    }

    public void shutdownLocked() {
        writeSyncLocked();
        mShuttingDown = true;
    }

    Parcel mPendingWrite = null;
    final ReentrantLock mWriteLock = new ReentrantLock();

    public void writeAsyncLocked() {
        writeLocked(false);
    }

    public void writeSyncLocked() {
        writeLocked(true);
    }

    void writeLocked(boolean sync) {
        if (mFile == null) {
            Slog.w("BatteryStats", "writeLocked: no file associated with this instance");
            return;
        }

        if (mShuttingDown) {
            return;
        }

        Parcel out = Parcel.obtain();
        writeSummaryToParcel(out);
        mLastWriteTime = SystemClock.elapsedRealtime();

        if (mPendingWrite != null) {
            mPendingWrite.recycle();
        }
        mPendingWrite = out;

        if (sync) {
            commitPendingDataToDisk();
        } else {
            Thread thr = new Thread("BatteryStats-Write") {
                @Override
                public void run() {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    commitPendingDataToDisk();
                }
            };
            thr.start();
        }
    }

    public void commitPendingDataToDisk() {
        final Parcel next;
        synchronized (this) {
            next = mPendingWrite;
            mPendingWrite = null;
            if (next == null) {
                return;
            }

            mWriteLock.lock();
        }

        try {
            FileOutputStream stream = new FileOutputStream(mFile.chooseForWrite());
            stream.write(next.marshall());
            stream.flush();
            FileUtils.sync(stream);
            stream.close();
            mFile.commit();
        } catch (IOException e) {
            Slog.w("BatteryStats", "Error writing battery statistics", e);
            mFile.rollback();
        } finally {
            next.recycle();
            mWriteLock.unlock();
        }
    }

    static byte[] readFully(FileInputStream stream) throws java.io.IOException {
        int pos = 0;
        int avail = stream.available();
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

    public void readLocked() {
        if (mFile == null) {
            Slog.w("BatteryStats", "readLocked: no file associated with this instance");
            return;
        }

        mUidStats.clear();

        try {
            File file = mFile.chooseForRead();
            if (!file.exists()) {
                return;
            }
            FileInputStream stream = new FileInputStream(file);

            byte[] raw = readFully(stream);
            Parcel in = Parcel.obtain();
            in.unmarshall(raw, 0, raw.length);
            in.setDataPosition(0);
            stream.close();

            readSummaryFromParcel(in);
        } catch(java.io.IOException e) {
            Slog.e("BatteryStats", "Error reading battery statistics", e);
        }

        long now = SystemClock.elapsedRealtime();
        if (USE_OLD_HISTORY) {
            addHistoryRecordLocked(now, HistoryItem.CMD_START);
        }
        addHistoryBufferLocked(now, HistoryItem.CMD_START);
    }

    public int describeContents() {
        return 0;
    }

    void readHistory(Parcel in, boolean andOldHistory) {
        final long historyBaseTime = in.readLong();

        mHistoryBuffer.setDataSize(0);
        mHistoryBuffer.setDataPosition(0);

        int bufSize = in.readInt();
        int curPos = in.dataPosition();
        if (bufSize >= (MAX_MAX_HISTORY_BUFFER*3)) {
            Slog.w(TAG, "File corrupt: history data buffer too large " + bufSize);
        } else if ((bufSize&~3) != bufSize) {
            Slog.w(TAG, "File corrupt: history data buffer not aligned " + bufSize);
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
            long oldnow = SystemClock.elapsedRealtime();
            mHistoryBaseTime = (mHistoryBaseTime - oldnow) + 60*1000;
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

    void writeHistory(Parcel out, boolean andOldHistory) {
        if (DEBUG_HISTORY) {
            StringBuilder sb = new StringBuilder(128);
            sb.append("****************** WRITING mHistoryBaseTime: ");
            TimeUtils.formatDuration(mHistoryBaseTime, sb);
            sb.append(" mLastHistoryTime: ");
            TimeUtils.formatDuration(mLastHistoryTime, sb);
            Slog.i(TAG, sb.toString());
        }
        out.writeLong(mHistoryBaseTime + mLastHistoryTime);
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

    private void readSummaryFromParcel(Parcel in) {
        final int version = in.readInt();
        if (version != VERSION) {
            Slog.w("BatteryStats", "readFromParcel: version got " + version
                + ", expected " + VERSION + "; erasing old stats");
            return;
        }

        readHistory(in, true);

        mStartCount = in.readInt();
        mBatteryUptime = in.readLong();
        mBatteryRealtime = in.readLong();
        mUptime = in.readLong();
        mRealtime = in.readLong();
        mDischargeUnplugLevel = in.readInt();
        mDischargeCurrentLevel = in.readInt();
        mLowDischargeAmountSinceCharge = in.readInt();
        mHighDischargeAmountSinceCharge = in.readInt();
        mDischargeAmountScreenOnSinceCharge = in.readInt();
        mDischargeAmountScreenOffSinceCharge = in.readInt();

        mStartCount++;

        mScreenOn = false;
        mScreenOnTimer.readSummaryFromParcelLocked(in);
        for (int i=0; i<NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            mScreenBrightnessTimer[i].readSummaryFromParcelLocked(in);
        }
        mInputEventCounter.readSummaryFromParcelLocked(in);
        mPhoneOn = false;
        mPhoneOnTimer.readSummaryFromParcelLocked(in);
        for (int i=0; i<SignalStrength.NUM_SIGNAL_STRENGTH_BINS; i++) {
            mPhoneSignalStrengthsTimer[i].readSummaryFromParcelLocked(in);
        }
        mPhoneSignalScanningTimer.readSummaryFromParcelLocked(in);
        for (int i=0; i<NUM_DATA_CONNECTION_TYPES; i++) {
            mPhoneDataConnectionsTimer[i].readSummaryFromParcelLocked(in);
        }
        mWifiOn = false;
        mWifiOnTimer.readSummaryFromParcelLocked(in);
        mGlobalWifiRunning = false;
        mGlobalWifiRunningTimer.readSummaryFromParcelLocked(in);
        mBluetoothOn = false;
        mBluetoothOnTimer.readSummaryFromParcelLocked(in);

        int NKW = in.readInt();
        if (NKW > 10000) {
            Slog.w(TAG, "File corrupt: too many kernel wake locks " + NKW);
            return;
        }
        for (int ikw = 0; ikw < NKW; ikw++) {
            if (in.readInt() != 0) {
                String kwltName = in.readString();
                getKernelWakelockTimerLocked(kwltName).readSummaryFromParcelLocked(in);
            }
        }

        sNumSpeedSteps = in.readInt();

        final int NU = in.readInt();
        if (NU > 10000) {
            Slog.w(TAG, "File corrupt: too many uids " + NU);
            return;
        }
        for (int iu = 0; iu < NU; iu++) {
            int uid = in.readInt();
            Uid u = new Uid(uid);
            mUidStats.put(uid, u);

            u.mWifiRunning = false;
            if (in.readInt() != 0) {
                u.mWifiRunningTimer.readSummaryFromParcelLocked(in);
            }
            u.mFullWifiLockOut = false;
            if (in.readInt() != 0) {
                u.mFullWifiLockTimer.readSummaryFromParcelLocked(in);
            }
            u.mScanWifiLockOut = false;
            if (in.readInt() != 0) {
                u.mScanWifiLockTimer.readSummaryFromParcelLocked(in);
            }
            u.mWifiMulticastEnabled = false;
            if (in.readInt() != 0) {
                u.mWifiMulticastTimer.readSummaryFromParcelLocked(in);
            }
            u.mAudioTurnedOn = false;
            if (in.readInt() != 0) {
                u.mAudioTurnedOnTimer.readSummaryFromParcelLocked(in);
            }
            u.mVideoTurnedOn = false;
            if (in.readInt() != 0) {
                u.mVideoTurnedOnTimer.readSummaryFromParcelLocked(in);
            }

            if (in.readInt() != 0) {
                if (u.mUserActivityCounters == null) {
                    u.initUserActivityLocked();
                }
                for (int i=0; i<Uid.NUM_USER_ACTIVITY_TYPES; i++) {
                    u.mUserActivityCounters[i].readSummaryFromParcelLocked(in);
                }
            }

            int NW = in.readInt();
            if (NW > 100) {
                Slog.w(TAG, "File corrupt: too many wake locks " + NW);
                return;
            }
            for (int iw = 0; iw < NW; iw++) {
                String wlName = in.readString();
                if (in.readInt() != 0) {
                    u.getWakeTimerLocked(wlName, WAKE_TYPE_FULL).readSummaryFromParcelLocked(in);
                }
                if (in.readInt() != 0) {
                    u.getWakeTimerLocked(wlName, WAKE_TYPE_PARTIAL).readSummaryFromParcelLocked(in);
                }
                if (in.readInt() != 0) {
                    u.getWakeTimerLocked(wlName, WAKE_TYPE_WINDOW).readSummaryFromParcelLocked(in);
                }
            }

            int NP = in.readInt();
            if (NP > 1000) {
                Slog.w(TAG, "File corrupt: too many sensors " + NP);
                return;
            }
            for (int is = 0; is < NP; is++) {
                int seNumber = in.readInt();
                if (in.readInt() != 0) {
                    u.getSensorTimerLocked(seNumber, true)
                            .readSummaryFromParcelLocked(in);
                }
            }

            NP = in.readInt();
            if (NP > 1000) {
                Slog.w(TAG, "File corrupt: too many processes " + NP);
                return;
            }
            for (int ip = 0; ip < NP; ip++) {
                String procName = in.readString();
                Uid.Proc p = u.getProcessStatsLocked(procName);
                p.mUserTime = p.mLoadedUserTime = in.readLong();
                p.mSystemTime = p.mLoadedSystemTime = in.readLong();
                p.mStarts = p.mLoadedStarts = in.readInt();
                int NSB = in.readInt();
                if (NSB > 100) {
                    Slog.w(TAG, "File corrupt: too many speed bins " + NSB);
                    return;
                }
                p.mSpeedBins = new SamplingCounter[NSB];
                for (int i=0; i<NSB; i++) {
                    if (in.readInt() != 0) {
                        p.mSpeedBins[i] = new SamplingCounter(mUnpluggables);
                        p.mSpeedBins[i].readSummaryFromParcelLocked(in);
                    }
                }
                if (!p.readExcessivePowerFromParcelLocked(in)) {
                    return;
                }
            }

            NP = in.readInt();
            if (NP > 10000) {
                Slog.w(TAG, "File corrupt: too many packages " + NP);
                return;
            }
            for (int ip = 0; ip < NP; ip++) {
                String pkgName = in.readString();
                Uid.Pkg p = u.getPackageStatsLocked(pkgName);
                p.mWakeups = p.mLoadedWakeups = in.readInt();
                final int NS = in.readInt();
                if (NS > 1000) {
                    Slog.w(TAG, "File corrupt: too many services " + NS);
                    return;
                }
                for (int is = 0; is < NS; is++) {
                    String servName = in.readString();
                    Uid.Pkg.Serv s = u.getServiceStatsLocked(pkgName, servName);
                    s.mStartTime = s.mLoadedStartTime = in.readLong();
                    s.mStarts = s.mLoadedStarts = in.readInt();
                    s.mLaunches = s.mLoadedLaunches = in.readInt();
                }
            }

            u.mLoadedTcpBytesReceived = in.readLong();
            u.mLoadedTcpBytesSent = in.readLong();
        }
    }

    /**
     * Writes a summary of the statistics to a Parcel, in a format suitable to be written to
     * disk.  This format does not allow a lossless round-trip.
     *
     * @param out the Parcel to be written to.
     */
    public void writeSummaryToParcel(Parcel out) {
        // Need to update with current kernel wake lock counts.
        updateKernelWakelocksLocked();

        final long NOW_SYS = SystemClock.uptimeMillis() * 1000;
        final long NOWREAL_SYS = SystemClock.elapsedRealtime() * 1000;
        final long NOW = getBatteryUptimeLocked(NOW_SYS);
        final long NOWREAL = getBatteryRealtimeLocked(NOWREAL_SYS);

        out.writeInt(VERSION);

        writeHistory(out, true);

        out.writeInt(mStartCount);
        out.writeLong(computeBatteryUptime(NOW_SYS, STATS_SINCE_CHARGED));
        out.writeLong(computeBatteryRealtime(NOWREAL_SYS, STATS_SINCE_CHARGED));
        out.writeLong(computeUptime(NOW_SYS, STATS_SINCE_CHARGED));
        out.writeLong(computeRealtime(NOWREAL_SYS, STATS_SINCE_CHARGED));
        out.writeInt(mDischargeUnplugLevel);
        out.writeInt(mDischargeCurrentLevel);
        out.writeInt(getLowDischargeAmountSinceCharge());
        out.writeInt(getHighDischargeAmountSinceCharge());
        out.writeInt(getDischargeAmountScreenOnSinceCharge());
        out.writeInt(getDischargeAmountScreenOffSinceCharge());
        
        mScreenOnTimer.writeSummaryFromParcelLocked(out, NOWREAL);
        for (int i=0; i<NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            mScreenBrightnessTimer[i].writeSummaryFromParcelLocked(out, NOWREAL);
        }
        mInputEventCounter.writeSummaryFromParcelLocked(out);
        mPhoneOnTimer.writeSummaryFromParcelLocked(out, NOWREAL);
        for (int i=0; i<SignalStrength.NUM_SIGNAL_STRENGTH_BINS; i++) {
            mPhoneSignalStrengthsTimer[i].writeSummaryFromParcelLocked(out, NOWREAL);
        }
        mPhoneSignalScanningTimer.writeSummaryFromParcelLocked(out, NOWREAL);
        for (int i=0; i<NUM_DATA_CONNECTION_TYPES; i++) {
            mPhoneDataConnectionsTimer[i].writeSummaryFromParcelLocked(out, NOWREAL);
        }
        mWifiOnTimer.writeSummaryFromParcelLocked(out, NOWREAL);
        mGlobalWifiRunningTimer.writeSummaryFromParcelLocked(out, NOWREAL);
        mBluetoothOnTimer.writeSummaryFromParcelLocked(out, NOWREAL);

        out.writeInt(mKernelWakelockStats.size());
        for (Map.Entry<String, SamplingTimer> ent : mKernelWakelockStats.entrySet()) {
            Timer kwlt = ent.getValue();
            if (kwlt != null) {
                out.writeInt(1);
                out.writeString(ent.getKey());
                ent.getValue().writeSummaryFromParcelLocked(out, NOWREAL);
            } else {
                out.writeInt(0);
            }
        }

        out.writeInt(sNumSpeedSteps);
        final int NU = mUidStats.size();
        out.writeInt(NU);
        for (int iu = 0; iu < NU; iu++) {
            out.writeInt(mUidStats.keyAt(iu));
            Uid u = mUidStats.valueAt(iu);

            if (u.mWifiRunningTimer != null) {
                out.writeInt(1);
                u.mWifiRunningTimer.writeSummaryFromParcelLocked(out, NOWREAL);
            } else {
                out.writeInt(0);
            }
            if (u.mFullWifiLockTimer != null) {
                out.writeInt(1);
                u.mFullWifiLockTimer.writeSummaryFromParcelLocked(out, NOWREAL);
            } else {
                out.writeInt(0);
            }
            if (u.mScanWifiLockTimer != null) {
                out.writeInt(1);
                u.mScanWifiLockTimer.writeSummaryFromParcelLocked(out, NOWREAL);
            } else {
                out.writeInt(0);
            }
            if (u.mWifiMulticastTimer != null) {
                out.writeInt(1);
                u.mWifiMulticastTimer.writeSummaryFromParcelLocked(out, NOWREAL);
            } else {
                out.writeInt(0);
            }
            if (u.mAudioTurnedOnTimer != null) {
                out.writeInt(1);
                u.mAudioTurnedOnTimer.writeSummaryFromParcelLocked(out, NOWREAL);
            } else {
                out.writeInt(0);
            }
            if (u.mVideoTurnedOnTimer != null) {
                out.writeInt(1);
                u.mVideoTurnedOnTimer.writeSummaryFromParcelLocked(out, NOWREAL);
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

            int NW = u.mWakelockStats.size();
            out.writeInt(NW);
            if (NW > 0) {
                for (Map.Entry<String, BatteryStatsImpl.Uid.Wakelock> ent
                        : u.mWakelockStats.entrySet()) {
                    out.writeString(ent.getKey());
                    Uid.Wakelock wl = ent.getValue();
                    if (wl.mTimerFull != null) {
                        out.writeInt(1);
                        wl.mTimerFull.writeSummaryFromParcelLocked(out, NOWREAL);
                    } else {
                        out.writeInt(0);
                    }
                    if (wl.mTimerPartial != null) {
                        out.writeInt(1);
                        wl.mTimerPartial.writeSummaryFromParcelLocked(out, NOWREAL);
                    } else {
                        out.writeInt(0);
                    }
                    if (wl.mTimerWindow != null) {
                        out.writeInt(1);
                        wl.mTimerWindow.writeSummaryFromParcelLocked(out, NOWREAL);
                    } else {
                        out.writeInt(0);
                    }
                }
            }

            int NSE = u.mSensorStats.size();
            out.writeInt(NSE);
            if (NSE > 0) {
                for (Map.Entry<Integer, BatteryStatsImpl.Uid.Sensor> ent
                        : u.mSensorStats.entrySet()) {
                    out.writeInt(ent.getKey());
                    Uid.Sensor se = ent.getValue();
                    if (se.mTimer != null) {
                        out.writeInt(1);
                        se.mTimer.writeSummaryFromParcelLocked(out, NOWREAL);
                    } else {
                        out.writeInt(0);
                    }
                }
            }

            int NP = u.mProcessStats.size();
            out.writeInt(NP);
            if (NP > 0) {
                for (Map.Entry<String, BatteryStatsImpl.Uid.Proc> ent
                    : u.mProcessStats.entrySet()) {
                    out.writeString(ent.getKey());
                    Uid.Proc ps = ent.getValue();
                    out.writeLong(ps.mUserTime);
                    out.writeLong(ps.mSystemTime);
                    out.writeInt(ps.mStarts);
                    final int N = ps.mSpeedBins.length;
                    out.writeInt(N);
                    for (int i=0; i<N; i++) {
                        if (ps.mSpeedBins[i] != null) {
                            out.writeInt(1);
                            ps.mSpeedBins[i].writeSummaryFromParcelLocked(out);
                        } else {
                            out.writeInt(0);
                        }
                    }
                    ps.writeExcessivePowerToParcelLocked(out);
                }
            }

            NP = u.mPackageStats.size();
            out.writeInt(NP);
            if (NP > 0) {
                for (Map.Entry<String, BatteryStatsImpl.Uid.Pkg> ent
                    : u.mPackageStats.entrySet()) {
                    out.writeString(ent.getKey());
                    Uid.Pkg ps = ent.getValue();
                    out.writeInt(ps.mWakeups);
                    final int NS = ps.mServiceStats.size();
                    out.writeInt(NS);
                    if (NS > 0) {
                        for (Map.Entry<String, BatteryStatsImpl.Uid.Pkg.Serv> sent
                                : ps.mServiceStats.entrySet()) {
                            out.writeString(sent.getKey());
                            BatteryStatsImpl.Uid.Pkg.Serv ss = sent.getValue();
                            long time = ss.getStartTimeToNowLocked(NOW);
                            out.writeLong(time);
                            out.writeInt(ss.mStarts);
                            out.writeInt(ss.mLaunches);
                        }
                    }
                }
            }

            out.writeLong(u.getTcpBytesReceived(STATS_SINCE_CHARGED));
            out.writeLong(u.getTcpBytesSent(STATS_SINCE_CHARGED));
        }
    }

    public void readFromParcel(Parcel in) {
        readFromParcelLocked(in);
    }

    void readFromParcelLocked(Parcel in) {
        int magic = in.readInt();
        if (magic != MAGIC) {
            throw new ParcelFormatException("Bad magic number");
        }

        readHistory(in, false);

        mStartCount = in.readInt();
        mBatteryUptime = in.readLong();
        mBatteryLastUptime = 0;
        mBatteryRealtime = in.readLong();
        mBatteryLastRealtime = 0;
        mScreenOn = false;
        mScreenOnTimer = new StopwatchTimer(null, -1, null, mUnpluggables, in);
        for (int i=0; i<NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            mScreenBrightnessTimer[i] = new StopwatchTimer(null, -100-i,
                    null, mUnpluggables, in);
        }
        mInputEventCounter = new Counter(mUnpluggables, in);
        mPhoneOn = false;
        mPhoneOnTimer = new StopwatchTimer(null, -2, null, mUnpluggables, in);
        for (int i=0; i<SignalStrength.NUM_SIGNAL_STRENGTH_BINS; i++) {
            mPhoneSignalStrengthsTimer[i] = new StopwatchTimer(null, -200-i,
                    null, mUnpluggables, in);
        }
        mPhoneSignalScanningTimer = new StopwatchTimer(null, -200+1, null, mUnpluggables, in);
        for (int i=0; i<NUM_DATA_CONNECTION_TYPES; i++) {
            mPhoneDataConnectionsTimer[i] = new StopwatchTimer(null, -300-i,
                    null, mUnpluggables, in);
        }
        mWifiOn = false;
        mWifiOnTimer = new StopwatchTimer(null, -2, null, mUnpluggables, in);
        mGlobalWifiRunning = false;
        mGlobalWifiRunningTimer = new StopwatchTimer(null, -2, null, mUnpluggables, in);
        mBluetoothOn = false;
        mBluetoothOnTimer = new StopwatchTimer(null, -2, null, mUnpluggables, in);
        mUptime = in.readLong();
        mUptimeStart = in.readLong();
        mLastUptime = 0;
        mRealtime = in.readLong();
        mRealtimeStart = in.readLong();
        mLastRealtime = 0;
        mOnBattery = in.readInt() != 0;
        mOnBatteryInternal = false; // we are no longer really running.
        mTrackBatteryPastUptime = in.readLong();
        mTrackBatteryUptimeStart = in.readLong();
        mTrackBatteryPastRealtime = in.readLong();
        mTrackBatteryRealtimeStart = in.readLong();
        mUnpluggedBatteryUptime = in.readLong();
        mUnpluggedBatteryRealtime = in.readLong();
        mDischargeUnplugLevel = in.readInt();
        mDischargeCurrentLevel = in.readInt();
        mLowDischargeAmountSinceCharge = in.readInt();
        mHighDischargeAmountSinceCharge = in.readInt();
        mDischargeAmountScreenOn = in.readInt();
        mDischargeAmountScreenOnSinceCharge = in.readInt();
        mDischargeAmountScreenOff = in.readInt();
        mDischargeAmountScreenOffSinceCharge = in.readInt();
        mLastWriteTime = in.readLong();

        mMobileDataRx[STATS_LAST] = in.readLong();
        mMobileDataRx[STATS_SINCE_UNPLUGGED] = -1;
        mMobileDataTx[STATS_LAST] = in.readLong();
        mMobileDataTx[STATS_SINCE_UNPLUGGED] = -1;
        mTotalDataRx[STATS_LAST] = in.readLong();
        mTotalDataRx[STATS_SINCE_UNPLUGGED] = -1;
        mTotalDataTx[STATS_LAST] = in.readLong();
        mTotalDataTx[STATS_SINCE_UNPLUGGED] = -1;

        mRadioDataUptime = in.readLong();
        mRadioDataStart = -1;

        mBluetoothPingCount = in.readInt();
        mBluetoothPingStart = -1;

        mKernelWakelockStats.clear();
        int NKW = in.readInt();
        for (int ikw = 0; ikw < NKW; ikw++) {
            if (in.readInt() != 0) {
                String wakelockName = in.readString();
                in.readInt(); // Extra 0/1 written by Timer.writeTimerToParcel
                SamplingTimer kwlt = new SamplingTimer(mUnpluggables, mOnBattery, in);
                mKernelWakelockStats.put(wakelockName, kwlt);
            }
        }

        mPartialTimers.clear();
        mFullTimers.clear();
        mWindowTimers.clear();
        mWifiRunningTimers.clear();
        mFullWifiLockTimers.clear();
        mScanWifiLockTimers.clear();
        mWifiMulticastTimers.clear();

        sNumSpeedSteps = in.readInt();

        int numUids = in.readInt();
        mUidStats.clear();
        for (int i = 0; i < numUids; i++) {
            int uid = in.readInt();
            Uid u = new Uid(uid);
            u.readFromParcelLocked(mUnpluggables, in);
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
        updateKernelWakelocksLocked();

        final long uSecUptime = SystemClock.uptimeMillis() * 1000;
        final long uSecRealtime = SystemClock.elapsedRealtime() * 1000;
        final long batteryUptime = getBatteryUptimeLocked(uSecUptime);
        final long batteryRealtime = getBatteryRealtimeLocked(uSecRealtime);

        out.writeInt(MAGIC);

        writeHistory(out, false);

        out.writeInt(mStartCount);
        out.writeLong(mBatteryUptime);
        out.writeLong(mBatteryRealtime);
        mScreenOnTimer.writeToParcel(out, batteryRealtime);
        for (int i=0; i<NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            mScreenBrightnessTimer[i].writeToParcel(out, batteryRealtime);
        }
        mInputEventCounter.writeToParcel(out);
        mPhoneOnTimer.writeToParcel(out, batteryRealtime);
        for (int i=0; i<SignalStrength.NUM_SIGNAL_STRENGTH_BINS; i++) {
            mPhoneSignalStrengthsTimer[i].writeToParcel(out, batteryRealtime);
        }
        mPhoneSignalScanningTimer.writeToParcel(out, batteryRealtime);
        for (int i=0; i<NUM_DATA_CONNECTION_TYPES; i++) {
            mPhoneDataConnectionsTimer[i].writeToParcel(out, batteryRealtime);
        }
        mWifiOnTimer.writeToParcel(out, batteryRealtime);
        mGlobalWifiRunningTimer.writeToParcel(out, batteryRealtime);
        mBluetoothOnTimer.writeToParcel(out, batteryRealtime);
        out.writeLong(mUptime);
        out.writeLong(mUptimeStart);
        out.writeLong(mRealtime);
        out.writeLong(mRealtimeStart);
        out.writeInt(mOnBattery ? 1 : 0);
        out.writeLong(batteryUptime);
        out.writeLong(mTrackBatteryUptimeStart);
        out.writeLong(batteryRealtime);
        out.writeLong(mTrackBatteryRealtimeStart);
        out.writeLong(mUnpluggedBatteryUptime);
        out.writeLong(mUnpluggedBatteryRealtime);
        out.writeInt(mDischargeUnplugLevel);
        out.writeInt(mDischargeCurrentLevel);
        out.writeInt(mLowDischargeAmountSinceCharge);
        out.writeInt(mHighDischargeAmountSinceCharge);
        out.writeInt(mDischargeAmountScreenOn);
        out.writeInt(mDischargeAmountScreenOnSinceCharge);
        out.writeInt(mDischargeAmountScreenOff);
        out.writeInt(mDischargeAmountScreenOffSinceCharge);
        out.writeLong(mLastWriteTime);

        out.writeLong(getMobileTcpBytesReceived(STATS_SINCE_UNPLUGGED));
        out.writeLong(getMobileTcpBytesSent(STATS_SINCE_UNPLUGGED));
        out.writeLong(getTotalTcpBytesReceived(STATS_SINCE_UNPLUGGED));
        out.writeLong(getTotalTcpBytesSent(STATS_SINCE_UNPLUGGED));

        // Write radio uptime for data
        out.writeLong(getRadioDataUptime());

        out.writeInt(getBluetoothPingCount());

        if (inclUids) {
            out.writeInt(mKernelWakelockStats.size());
            for (Map.Entry<String, SamplingTimer> ent : mKernelWakelockStats.entrySet()) {
                SamplingTimer kwlt = ent.getValue();
                if (kwlt != null) {
                    out.writeInt(1);
                    out.writeString(ent.getKey());
                    Timer.writeTimerToParcel(out, kwlt, batteryRealtime);
                } else {
                    out.writeInt(0);
                }
            }
        } else {
            out.writeInt(0);
        }

        out.writeInt(sNumSpeedSteps);

        if (inclUids) {
            int size = mUidStats.size();
            out.writeInt(size);
            for (int i = 0; i < size; i++) {
                out.writeInt(mUidStats.keyAt(i));
                Uid uid = mUidStats.valueAt(i);

                uid.writeToParcelLocked(out, batteryRealtime);
            }
        } else {
            out.writeInt(0);
        }
    }

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
        updateKernelWakelocksLocked();
    }

    public void dumpLocked(PrintWriter pw) {
        if (DEBUG) {
            Printer pr = new PrintWriterPrinter(pw);
            pr.println("*** Screen timer:");
            mScreenOnTimer.logState(pr, "  ");
            for (int i=0; i<NUM_SCREEN_BRIGHTNESS_BINS; i++) {
                pr.println("*** Screen brightness #" + i + ":");
                mScreenBrightnessTimer[i].logState(pr, "  ");
            }
            pr.println("*** Input event counter:");
            mInputEventCounter.logState(pr, "  ");
            pr.println("*** Phone timer:");
            mPhoneOnTimer.logState(pr, "  ");
            for (int i=0; i<SignalStrength.NUM_SIGNAL_STRENGTH_BINS; i++) {
                pr.println("*** Signal strength #" + i + ":");
                mPhoneSignalStrengthsTimer[i].logState(pr, "  ");
            }
            pr.println("*** Signal scanning :");
            mPhoneSignalScanningTimer.logState(pr, "  ");
            for (int i=0; i<NUM_DATA_CONNECTION_TYPES; i++) {
                pr.println("*** Data connection type #" + i + ":");
                mPhoneDataConnectionsTimer[i].logState(pr, "  ");
            }
            pr.println("*** Wifi timer:");
            mWifiOnTimer.logState(pr, "  ");
            pr.println("*** WifiRunning timer:");
            mGlobalWifiRunningTimer.logState(pr, "  ");
            pr.println("*** Bluetooth timer:");
            mBluetoothOnTimer.logState(pr, "  ");
            pr.println("*** Mobile ifaces:");
            pr.println(mMobileIfaces.toString());
        }
        super.dumpLocked(pw);
    }

    private NetworkStats mNetworkSummaryCache;
    private NetworkStats mNetworkDetailCache;

    private NetworkStats getNetworkStatsSummary() {
        // NOTE: calls from BatteryStatsService already hold this lock
        synchronized (this) {
            if (mNetworkSummaryCache == null
                    || mNetworkSummaryCache.getElapsedRealtimeAge() > SECOND_IN_MILLIS) {
                try {
                    mNetworkSummaryCache = mNetworkStatsFactory.readNetworkStatsSummary();
                } catch (IllegalStateException e) {
                    // log problem and return empty object
                    Log.wtf(TAG, "problem reading network stats", e);
                    mNetworkSummaryCache = new NetworkStats(SystemClock.elapsedRealtime(), 0);
                }
            }
            return mNetworkSummaryCache;
        }
    }

    private NetworkStats getNetworkStatsDetailGroupedByUid() {
        // NOTE: calls from BatteryStatsService already hold this lock
        synchronized (this) {
            if (mNetworkDetailCache == null
                    || mNetworkDetailCache.getElapsedRealtimeAge() > SECOND_IN_MILLIS) {
                try {
                    mNetworkDetailCache = mNetworkStatsFactory
                            .readNetworkStatsDetail().groupedByUid();
                } catch (IllegalStateException e) {
                    // log problem and return empty object
                    Log.wtf(TAG, "problem reading network stats", e);
                    mNetworkDetailCache = new NetworkStats(SystemClock.elapsedRealtime(), 0);
                }
            }
            return mNetworkDetailCache;
        }
    }
}
