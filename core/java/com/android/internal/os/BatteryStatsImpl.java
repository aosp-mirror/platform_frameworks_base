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

import android.os.BatteryStats;
import android.os.NetStat;
import android.os.Parcel;
import android.os.ParcelFormatException;
import android.os.Parcelable;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.Printer;
import android.util.SparseArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * All information we are collecting about things that can happen that impact
 * battery life.  All times are represented in microseconds except where indicated
 * otherwise.
 */
public final class BatteryStatsImpl extends BatteryStats {
    private static final String TAG = "BatteryStatsImpl";
    private static final boolean DEBUG = false;

    // In-memory Parcel magic number, used to detect attempts to unmarshall bad data
    private static final int MAGIC = 0xBA757475; // 'BATSTATS' 

    // Current on-disk Parcel version
    private static final int VERSION = 32;

    private final File mFile;
    private final File mBackupFile;

    /**
     * The statistics we have collected organized by uids.
     */
    final SparseArray<BatteryStatsImpl.Uid> mUidStats =
        new SparseArray<BatteryStatsImpl.Uid>();

    // A set of pools of currently active timers.  When a timer is queried, we will divide the
    // elapsed time by the number of active timers to arrive at that timer's share of the time.
    // In order to do this, we must refresh each timer whenever the number of active timers
    // changes.
    final ArrayList<Timer> mPartialTimers = new ArrayList<Timer>();
    final ArrayList<Timer> mFullTimers = new ArrayList<Timer>();
    final ArrayList<Timer> mWindowTimers = new ArrayList<Timer>();
    final SparseArray<ArrayList<Timer>> mSensorTimers
            = new SparseArray<ArrayList<Timer>>();

    // These are the objects that will want to do something when the device
    // is unplugged from power.
    final ArrayList<Unpluggable> mUnpluggables = new ArrayList<Unpluggable>();
    
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
    Timer mScreenOnTimer;
    
    int mScreenBrightnessBin = -1;
    final Timer[] mScreenBrightnessTimer = new Timer[NUM_SCREEN_BRIGHTNESS_BINS];
    
    Counter mInputEventCounter;
    
    boolean mPhoneOn;
    Timer mPhoneOnTimer;
    
    int mPhoneSignalStrengthBin = -1;
    final Timer[] mPhoneSignalStrengthsTimer = new Timer[NUM_SIGNAL_STRENGTH_BINS];
    
    int mPhoneDataConnectionType = -1;
    final Timer[] mPhoneDataConnectionsTimer = new Timer[NUM_DATA_CONNECTION_TYPES];
    
    boolean mWifiOn;
    Timer mWifiOnTimer;
    int mWifiOnUid = -1;

    boolean mWifiRunning;
    Timer mWifiRunningTimer;
    
    boolean mBluetoothOn;
    Timer mBluetoothOnTimer;
    
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
    int mUnpluggedStartLevel;
    int mPluggedStartLevel;
    
    long mLastWriteTime = 0; // Milliseconds

    // For debugging
    public BatteryStatsImpl() {
        mFile = mBackupFile = null;
    }

    public static interface Unpluggable {
        void unplug(long batteryUptime, long batteryRealtime);
        void plug(long batteryUptime, long batteryRealtime);
    }
    
    /**
     * State for keeping track of counting information.
     */
    public static final class Counter extends BatteryStats.Counter implements Unpluggable {
        int mCount;
        int mLoadedCount;
        int mLastCount;
        int mUnpluggedCount;
        int mPluggedCount;
        
        Counter(ArrayList<Unpluggable> unpluggables, Parcel in) {
            mPluggedCount = mCount = in.readInt();
            mLoadedCount = in.readInt();
            mLastCount = in.readInt();
            mUnpluggedCount = in.readInt();
            unpluggables.add(this);
        }

        Counter(ArrayList<Unpluggable> unpluggables) {
            unpluggables.add(this);
        }
        
        public void writeToParcel(Parcel out) {
            out.writeInt(mCount);
            out.writeInt(mLoadedCount);
            out.writeInt(mLastCount);
            out.writeInt(mUnpluggedCount);
        }

        public void unplug(long batteryUptime, long batteryRealtime) {
            mUnpluggedCount = mCount = mPluggedCount;
        }

        public void plug(long batteryUptime, long batteryRealtime) {
            mPluggedCount = mCount;
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
        public int getCount(int which) {
            int val;
            if (which == STATS_LAST) {
                val = mLastCount;
            } else {
                val = mCount;
                if (which == STATS_UNPLUGGED) {
                    val -= mUnpluggedCount;
                } else if (which != STATS_TOTAL) {
                    val -= mLoadedCount;
                }
            }

            return val;
        }

        public void logState(Printer pw, String prefix) {
            pw.println(prefix + "mCount=" + mCount
                    + " mLoadedCount=" + mLoadedCount + " mLastCount=" + mLastCount
                    + " mUnpluggedCount=" + mUnpluggedCount
                    + " mPluggedCount=" + mPluggedCount);
        }
        
        void stepLocked() {
            mCount++;
        }

        void writeSummaryFromParcelLocked(Parcel out) {
            out.writeInt(mCount);
            out.writeInt(mCount - mLoadedCount);
        }

        void readSummaryFromParcelLocked(Parcel in) {
            mCount = mLoadedCount = in.readInt();
            mLastCount = in.readInt();
            mUnpluggedCount = mPluggedCount = mCount;
        }
    }
    
    /**
     * State for keeping track of timing information.
     */
    public static final class Timer extends BatteryStats.Timer implements Unpluggable {
        final int mType;
        final ArrayList<Timer> mTimerPool;
        
        int mNesting;
        
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
         * The last time at which we updated the timer.  If mNesting is > 0,
         * subtract this from the current battery time to find the amount of
         * time we have been running since we last computed an update.
         */
        long mUpdateTime;
        
        /**
         * The total time at which the timer was acquired, to determine if
         * was actually held for an interesting duration.
         */
        long mAcquireTime;
        
        Timer(int type, ArrayList<Timer> timerPool,
                ArrayList<Unpluggable> unpluggables, Parcel in) {
            mType = type;
            mTimerPool = timerPool;
            mCount = in.readInt();
            mLoadedCount = in.readInt();
            mLastCount = in.readInt();
            mUnpluggedCount = in.readInt();
            mTotalTime = in.readLong();
            mLoadedTime = in.readLong();
            mLastTime = in.readLong();
            mUpdateTime = in.readLong();
            mUnpluggedTime = in.readLong();
            unpluggables.add(this);
        }

        Timer(int type, ArrayList<Timer> timerPool,
                ArrayList<Unpluggable> unpluggables) {
            mType = type;
            mTimerPool = timerPool;
            unpluggables.add(this);
        }
        
        public void writeToParcel(Parcel out, long batteryRealtime) {
            out.writeInt(mCount);
            out.writeInt(mLoadedCount);
            out.writeInt(mLastCount);
            out.writeInt(mUnpluggedCount);
            out.writeLong(computeRunTimeLocked(batteryRealtime));
            out.writeLong(mLoadedTime);
            out.writeLong(mLastTime);
            out.writeLong(mUpdateTime);
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
            if (mNesting > 0) {
                if (DEBUG && mType < 0) {
                    Log.v(TAG, "plug #" + mType + ": realtime=" + batteryRealtime
                            + " old mTotalTime=" + mTotalTime
                            + " old mUpdateTime=" + mUpdateTime);
                }
                mTotalTime = computeRunTimeLocked(batteryRealtime);
                mUpdateTime = batteryRealtime;
                if (DEBUG && mType < 0) {
                    Log.v(TAG, "plug #" + mType
                            + ": new mTotalTime=" + mTotalTime
                            + " old mUpdateTime=" + mUpdateTime);
                }
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
        public long getTotalTime(long batteryRealtime, int which) {
            long val;
            if (which == STATS_LAST) {
                val = mLastTime;
            } else {
                val = computeRunTimeLocked(batteryRealtime);
                if (which == STATS_UNPLUGGED) {
                    val -= mUnpluggedTime;
                } else if (which != STATS_TOTAL) {
                    val -= mLoadedTime;
                }
            }

            return val;
        }

        @Override
        public int getCount(int which) {
            int val;
            if (which == STATS_LAST) {
                val = mLastCount;
            } else {
                val = mCount;
                if (which == STATS_UNPLUGGED) {
                    val -= mUnpluggedCount;
                } else if (which != STATS_TOTAL) {
                    val -= mLoadedCount;
                }
            }

            return val;
        }

        public void logState(Printer pw, String prefix) {
            pw.println(prefix + "mNesting=" + mNesting + " mCount=" + mCount
                    + " mLoadedCount=" + mLoadedCount + " mLastCount=" + mLastCount
                    + " mUnpluggedCount=" + mUnpluggedCount);
            pw.println(prefix + "mTotalTime=" + mTotalTime
                    + " mLoadedTime=" + mLoadedTime);
            pw.println(prefix + "mLastTime=" + mLastTime
                    + " mUnpluggedTime=" + mUnpluggedTime);
            pw.println(prefix + "mUpdateTime=" + mUpdateTime
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
                final ArrayList<Timer> pool) {
            final long realtime = SystemClock.elapsedRealtime() * 1000; 
            final long batteryRealtime = stats.getBatteryRealtimeLocked(realtime);
            final int N = pool.size();
            for (int i=N-1; i>= 0; i--) {
                final Timer t = pool.get(i);
                long heldTime = batteryRealtime - t.mUpdateTime;
                if (heldTime > 0) {
                    t.mTotalTime += heldTime / N;
                }
                t.mUpdateTime = batteryRealtime;
            }
        }

        private long computeRunTimeLocked(long curBatteryRealtime) {
            return mTotalTime + (mNesting > 0
                    ? (curBatteryRealtime - mUpdateTime)
                            / (mTimerPool != null ? mTimerPool.size() : 1)
                    : 0);
        }

        void writeSummaryFromParcelLocked(Parcel out, long batteryRealtime) {
            long runTime = computeRunTimeLocked(batteryRealtime);
            // Divide by 1000 for backwards compatibility
            out.writeLong((runTime + 500) / 1000);
            out.writeLong(((runTime - mLoadedTime) + 500) / 1000);
            out.writeInt(mCount);
            out.writeInt(mCount - mLoadedCount);
        }

        void readSummaryFromParcelLocked(Parcel in) {
            // Multiply by 1000 for backwards compatibility
            mTotalTime = mLoadedTime = in.readLong() * 1000;
            mLastTime = in.readLong() * 1000;
            mUnpluggedTime = mTotalTime;
            mCount = mLoadedCount = in.readInt();
            mLastCount = in.readInt();
            mUnpluggedCount = mCount;
            mNesting = 0;
        }
    }
    
    public void doUnplug(long batteryUptime, long batteryRealtime) {
        for (int iu = mUidStats.size() - 1; iu >= 0; iu--) {
            Uid u = mUidStats.valueAt(iu);
            u.mStartedTcpBytesReceived = NetStat.getUidRxBytes(u.mUid);
            u.mStartedTcpBytesSent = NetStat.getUidTxBytes(u.mUid);
            u.mTcpBytesReceivedAtLastUnplug = u.mCurrentTcpBytesReceived;
            u.mTcpBytesSentAtLastUnplug = u.mCurrentTcpBytesSent;
        }
        for (int i = mUnpluggables.size() - 1; i >= 0; i--) {
            mUnpluggables.get(i).unplug(batteryUptime, batteryRealtime);
        }
    }
    
    public void doPlug(long batteryUptime, long batteryRealtime) {
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
    }
    
    public void noteStartGps(int uid) {
        mUidStats.get(uid).noteStartGps();
    }
    
    public void noteStopGps(int uid) {
        mUidStats.get(uid).noteStopGps();
    }
    
    public void noteScreenOnLocked() {
        if (!mScreenOn) {
            mScreenOn = true;
            mScreenOnTimer.startRunningLocked(this);
            if (mScreenBrightnessBin >= 0) {
                mScreenBrightnessTimer[mScreenBrightnessBin].startRunningLocked(this);
            }
        }
    }
    
    public void noteScreenOffLocked() {
        if (mScreenOn) {
            mScreenOn = false;
            mScreenOnTimer.stopRunningLocked(this);
            if (mScreenBrightnessBin >= 0) {
                mScreenBrightnessTimer[mScreenBrightnessBin].stopRunningLocked(this);
            }
        }
    }
    
    public void noteScreenBrightnessLocked(int brightness) {
        // Bin the brightness.
        int bin = brightness / (256/NUM_SCREEN_BRIGHTNESS_BINS);
        if (bin < 0) bin = 0;
        else if (bin >= NUM_SCREEN_BRIGHTNESS_BINS) bin = NUM_SCREEN_BRIGHTNESS_BINS-1;
        if (mScreenBrightnessBin != bin) {
            if (mScreenOn) {
                if (mScreenBrightnessBin >= 0) {
                    mScreenBrightnessTimer[mScreenBrightnessBin].stopRunningLocked(this);
                }
                mScreenBrightnessTimer[bin].startRunningLocked(this);
            }
            mScreenBrightnessBin = bin;
        }
    }
    
    public void noteInputEventLocked() {
        mInputEventCounter.stepLocked();
    }
    
    public void noteUserActivityLocked(int uid, int event) {
        Uid u = mUidStats.get(uid);
        if (u != null) {
            u.noteUserActivityLocked(event);
        }
    }
    
    public void notePhoneOnLocked() {
        if (!mPhoneOn) {
            mPhoneOn = true;
            mPhoneOnTimer.startRunningLocked(this);
        }
    }
    
    public void notePhoneOffLocked() {
        if (mPhoneOn) {
            mPhoneOn = false;
            mPhoneOnTimer.stopRunningLocked(this);
        }
    }
    
    public void notePhoneSignalStrengthLocked(int asu) {
        // Bin the strength.
        int bin;
        if (asu < 0 || asu >= 99) bin = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        else if (asu >= 16) bin = SIGNAL_STRENGTH_GREAT;
        else if (asu >= 8)  bin = SIGNAL_STRENGTH_GOOD;
        else if (asu >= 4)  bin = SIGNAL_STRENGTH_MODERATE;
        else bin = SIGNAL_STRENGTH_POOR;
        if (mPhoneSignalStrengthBin != bin) {
            if (mPhoneSignalStrengthBin >= 0) {
                mPhoneSignalStrengthsTimer[mPhoneSignalStrengthBin].stopRunningLocked(this);
            }
            mPhoneSignalStrengthBin = bin;
            mPhoneSignalStrengthsTimer[bin].startRunningLocked(this);
        }
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
                default:
                    bin = DATA_CONNECTION_OTHER;
                    break;
            }
        }
        if (mPhoneDataConnectionType != bin) {
            if (mPhoneDataConnectionType >= 0) {
                mPhoneDataConnectionsTimer[mPhoneDataConnectionType].stopRunningLocked(this);
            }
            mPhoneDataConnectionType = bin;
            mPhoneDataConnectionsTimer[bin].startRunningLocked(this);
        }
    }
    
    public void noteWifiOnLocked(int uid) {
        if (!mWifiOn) {
            mWifiOn = true;
            mWifiOnTimer.startRunningLocked(this);
        }
        if (mWifiOnUid != uid) {
            if (mWifiOnUid >= 0) {
                Uid u = mUidStats.get(mWifiOnUid);
                if (u != null) {
                    u.noteWifiTurnedOffLocked();
                }
            }
            mWifiOnUid = uid;
            Uid u = mUidStats.get(uid);
            if (u != null) {
                u.noteWifiTurnedOnLocked();
            }
        }
    }
    
    public void noteWifiOffLocked(int uid) {
        if (mWifiOn) {
            mWifiOn = false;
            mWifiOnTimer.stopRunningLocked(this);
        }
        if (mWifiOnUid >= 0) {
            Uid u = mUidStats.get(mWifiOnUid);
            if (u != null) {
                u.noteWifiTurnedOffLocked();
            }
            mWifiOnUid = -1;
        }
    }
    
    public void noteWifiRunningLocked() {
        if (!mWifiRunning) {
            mWifiRunning = true;
            mWifiRunningTimer.startRunningLocked(this);
        }
    }

    public void noteWifiStoppedLocked() {
        if (mWifiRunning) {
            mWifiRunning = false;
            mWifiRunningTimer.stopRunningLocked(this);
        }
    }

    public void noteBluetoothOnLocked() {
        if (!mBluetoothOn) {
            mBluetoothOn = true;
            mBluetoothOnTimer.startRunningLocked(this);
        }
    }
    
    public void noteBluetoothOffLocked() {
        if (mBluetoothOn) {
            mBluetoothOn = false;
            mBluetoothOnTimer.stopRunningLocked(this);
        }
    }
    
    public void noteFullWifiLockAcquiredLocked(int uid) {
        Uid u = mUidStats.get(uid);
        if (u != null) {
            u.noteFullWifiLockAcquiredLocked();
        }
    }

    public void noteFullWifiLockReleasedLocked(int uid) {
        Uid u = mUidStats.get(uid);
        if (u != null) {
            u.noteFullWifiLockReleasedLocked();
        }
    }

    public void noteScanWifiLockAcquiredLocked(int uid) {
        Uid u = mUidStats.get(uid);
        if (u != null) {
            u.noteScanWifiLockAcquiredLocked();
        }
    }

    public void noteScanWifiLockReleasedLocked(int uid) {
        Uid u = mUidStats.get(uid);
        if (u != null) {
            u.noteScanWifiLockReleasedLocked();
        }
    }
    
    @Override public long getScreenOnTime(long batteryRealtime, int which) {
        return mScreenOnTimer.getTotalTime(batteryRealtime, which);
    }
    
    @Override public long getScreenBrightnessTime(int brightnessBin,
            long batteryRealtime, int which) {
        return mScreenBrightnessTimer[brightnessBin].getTotalTime(
                batteryRealtime, which);
    }
    
    @Override public int getInputEventCount(int which) {
        return mInputEventCounter.getCount(which);
    }
    
    @Override public long getPhoneOnTime(long batteryRealtime, int which) {
        return mPhoneOnTimer.getTotalTime(batteryRealtime, which);
    }
    
    @Override public long getPhoneSignalStrengthTime(int strengthBin,
            long batteryRealtime, int which) {
        return mPhoneSignalStrengthsTimer[strengthBin].getTotalTime(
                batteryRealtime, which);
    }
    
    @Override public int getPhoneSignalStrengthCount(int dataType, int which) {
        return mPhoneDataConnectionsTimer[dataType].getCount(which);
    }
    
    @Override public long getPhoneDataConnectionTime(int dataType,
            long batteryRealtime, int which) {
        return mPhoneDataConnectionsTimer[dataType].getTotalTime(
                batteryRealtime, which);
    }
    
    @Override public int getPhoneDataConnectionCount(int dataType, int which) {
        return mPhoneDataConnectionsTimer[dataType].getCount(which);
    }
    
    @Override public long getWifiOnTime(long batteryRealtime, int which) {
        return mWifiOnTimer.getTotalTime(batteryRealtime, which);
    }
    
    @Override public long getWifiRunningTime(long batteryRealtime, int which) {
        return mWifiRunningTimer.getTotalTime(batteryRealtime, which);
    }

    @Override public long getBluetoothOnTime(long batteryRealtime, int which) {
        return mBluetoothOnTimer.getTotalTime(batteryRealtime, which);
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
        
        boolean mWifiTurnedOn;
        Timer mWifiTurnedOnTimer;
        
        boolean mFullWifiLockOut;
        Timer mFullWifiLockTimer;
        
        boolean mScanWifiLockOut;
        Timer mScanWifiLockTimer;
        
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
        
        public Uid(int uid) {
            mUid = uid;
            mWifiTurnedOnTimer = new Timer(WIFI_TURNED_ON, null, mUnpluggables);
            mFullWifiLockTimer = new Timer(FULL_WIFI_LOCK, null, mUnpluggables);
            mScanWifiLockTimer = new Timer(SCAN_WIFI_LOCK, null, mUnpluggables);
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
        
        public int getUid() {
            return mUid;
        }
        
        public long getTcpBytesReceived(int which) {
            if (which == STATS_LAST) {
                return mLoadedTcpBytesReceived;
            } else {
                long current = computeCurrentTcpBytesReceived();
                if (which == STATS_UNPLUGGED) {
                    current -= mTcpBytesReceivedAtLastUnplug;
                } else if (which == STATS_TOTAL) {
                    current += mLoadedTcpBytesReceived;
                }
                return current;
            }
        }
        
        public long computeCurrentTcpBytesReceived() {
            return mCurrentTcpBytesReceived + (mStartedTcpBytesReceived >= 0
                    ? (NetStat.getUidRxBytes(mUid) - mStartedTcpBytesReceived) : 0);
        }
        
        public long getTcpBytesSent(int which) {
            if (which == STATS_LAST) {
                return mLoadedTcpBytesSent;
            } else {
                long current = computeCurrentTcpBytesSent();
                if (which == STATS_UNPLUGGED) {
                    current -= mTcpBytesSentAtLastUnplug;
                } else if (which == STATS_TOTAL) {
                    current += mLoadedTcpBytesSent;
                }
                return current;
            }
        }
        
        @Override
        public void noteWifiTurnedOnLocked() {
            if (!mWifiTurnedOn) {
                mWifiTurnedOn = true;
                mWifiTurnedOnTimer.startRunningLocked(BatteryStatsImpl.this);
            }
        }
        
        @Override
        public void noteWifiTurnedOffLocked() {
            if (mWifiTurnedOn) {
                mWifiTurnedOn = false;
                mWifiTurnedOnTimer.stopRunningLocked(BatteryStatsImpl.this);
            }
        }
        
        @Override
        public void noteFullWifiLockAcquiredLocked() {
            if (!mFullWifiLockOut) {
                mFullWifiLockOut = true;
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
        public long getWifiTurnedOnTime(long batteryRealtime, int which) {
            return mWifiTurnedOnTimer.getTotalTime(batteryRealtime, which);
        }
        
        @Override 
        public long getFullWifiLockTime(long batteryRealtime, int which) {
            return mFullWifiLockTimer.getTotalTime(batteryRealtime, which);
        }
        
        @Override 
        public long getScanWifiLockTime(long batteryRealtime, int which) {
            return mScanWifiLockTimer.getTotalTime(batteryRealtime, which);
        }
        
        @Override
        public void noteUserActivityLocked(int type) {
            if (mUserActivityCounters == null) {
                initUserActivityLocked();
            }
            if (type < 0) type = 0;
            else if (type >= NUM_USER_ACTIVITY_TYPES) type = NUM_USER_ACTIVITY_TYPES-1;
            mUserActivityCounters[type].stepLocked();
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
            return mUserActivityCounters[type].getCount(which);
        }
        
        void initUserActivityLocked() {
            mUserActivityCounters = new Counter[NUM_USER_ACTIVITY_TYPES];
            for (int i=0; i<NUM_USER_ACTIVITY_TYPES; i++) {
                mUserActivityCounters[i] = new Counter(mUnpluggables);
            }
        }
        
        public long computeCurrentTcpBytesSent() {
            return mCurrentTcpBytesSent + (mStartedTcpBytesSent >= 0
                    ? (NetStat.getUidTxBytes(mUid) - mStartedTcpBytesSent) : 0);
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
            mWifiTurnedOnTimer.writeToParcel(out, batteryRealtime);
            mFullWifiLockTimer.writeToParcel(out, batteryRealtime);
            mScanWifiLockTimer.writeToParcel(out, batteryRealtime);
            if (mUserActivityCounters == null) {
                out.writeInt(0);
            } else {
                out.writeInt(1);
                for (int i=0; i<NUM_USER_ACTIVITY_TYPES; i++) {
                    mUserActivityCounters[i].writeToParcel(out);
                }
            }
        }

        void readFromParcelLocked(ArrayList<Unpluggable> unpluggables, Parcel in) {
            int numWakelocks = in.readInt();
            mWakelockStats.clear();
            for (int j = 0; j < numWakelocks; j++) {
                String wakelockName = in.readString();
                Uid.Wakelock wakelock = new Wakelock();
                wakelock.readFromParcelLocked(unpluggables, in);
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
            mWifiTurnedOn = false;
            mWifiTurnedOnTimer = new Timer(WIFI_TURNED_ON, null, mUnpluggables, in);
            mFullWifiLockOut = false;
            mFullWifiLockTimer = new Timer(FULL_WIFI_LOCK, null, mUnpluggables, in);
            mScanWifiLockOut = false;
            mScanWifiLockTimer = new Timer(SCAN_WIFI_LOCK, null, mUnpluggables, in);
            if (in.readInt() == 0) {
                mUserActivityCounters = null;
            } else {
                mUserActivityCounters = new Counter[NUM_USER_ACTIVITY_TYPES];
                for (int i=0; i<NUM_USER_ACTIVITY_TYPES; i++) {
                    mUserActivityCounters[i] = new Counter(mUnpluggables, in);
                }
            }
        }

        /**
         * The statistics associated with a particular wake lock.
         */
        public final class Wakelock extends BatteryStats.Uid.Wakelock {
            /**
             * How long (in ms) this uid has been keeping the device partially awake.
             */
            Timer mTimerPartial;

            /**
             * How long (in ms) this uid has been keeping the device fully awake.
             */
            Timer mTimerFull;

            /**
             * How long (in ms) this uid has had a window keeping the device awake.
             */
            Timer mTimerWindow;

            /**
             * Reads a possibly null Timer from a Parcel.  The timer is associated with the
             * proper timer pool from the given BatteryStatsImpl object.
             *
             * @param in the Parcel to be read from.
             * return a new Timer, or null.
             */
            private Timer readTimerFromParcel(int type, ArrayList<Timer> pool,
                    ArrayList<Unpluggable> unpluggables, Parcel in) {
                if (in.readInt() == 0) {
                    return null;
                }

                return new Timer(type, pool, unpluggables, in);
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
            Timer mTimer;
            
            public Sensor(int handle) {
                mHandle = handle;
            }

            private Timer readTimerFromParcel(ArrayList<Unpluggable> unpluggables,
                    Parcel in) {
                if (in.readInt() == 0) {
                    return null;
                }

                ArrayList<Timer> pool = mSensorTimers.get(mHandle);
                if (pool == null) {
                    pool = new ArrayList<Timer>();
                    mSensorTimers.put(mHandle, pool);
                }
                return new Timer(0, pool, unpluggables, in);
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

            Proc() {
                mUnpluggables.add(this);
            }
            
            public void unplug(long batteryUptime, long batteryRealtime) {
                mUnpluggedUserTime = mUserTime;
                mUnpluggedSystemTime = mSystemTime;
                mUnpluggedStarts = mStarts;
            }

            public void plug(long batteryUptime, long batteryRealtime) {
            }
            
            void writeToParcelLocked(Parcel out) {
                final long uSecRealtime = SystemClock.elapsedRealtime() * 1000;
                final long batteryRealtime = getBatteryRealtimeLocked(uSecRealtime);
                
                out.writeLong(mUserTime);
                out.writeLong(mSystemTime);
                out.writeInt(mStarts);
                out.writeLong(mLoadedUserTime);
                out.writeLong(mLoadedSystemTime);
                out.writeInt(mLoadedStarts);
                out.writeLong(mLastUserTime);
                out.writeLong(mLastSystemTime);
                out.writeInt(mLastStarts);
                out.writeLong(mUnpluggedUserTime);
                out.writeLong(mUnpluggedSystemTime);
                out.writeInt(mUnpluggedStarts);
            }

            void readFromParcelLocked(Parcel in) {
                mUserTime = in.readLong();
                mSystemTime = in.readLong();
                mStarts = in.readInt();
                mLoadedUserTime = in.readLong();
                mLoadedSystemTime = in.readLong();
                mLoadedStarts = in.readInt();
                mLastUserTime = in.readLong();
                mLastSystemTime = in.readLong();
                mLastStarts = in.readInt();
                mUnpluggedUserTime = in.readLong();
                mUnpluggedSystemTime = in.readLong();
                mUnpluggedStarts = in.readInt();
            }

            public BatteryStatsImpl getBatteryStats() {
                return BatteryStatsImpl.this;
            }

            public void addCpuTimeLocked(int utime, int stime) {
                mUserTime += utime;
                mSystemTime += stime;
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
                    } else if (which == STATS_UNPLUGGED) {
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
                    } else if (which == STATS_UNPLUGGED) {
                        val -= mUnpluggedSystemTime;
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
                    } else if (which == STATS_UNPLUGGED) {
                        val -= mUnpluggedStarts;
                    }
                }
                return val;
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
            
            void readFromParcelLocked(Parcel in) {
                mWakeups = in.readInt();
                mLoadedWakeups = in.readInt();
                mLastWakeups = in.readInt();
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
                out.writeInt(mLastWakeups);
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
                    } else if (which == STATS_UNPLUGGED) {
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
                    mLastStartTime = in.readLong();
                    mLastStarts = in.readInt();
                    mLastLaunches = in.readInt();
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
                    out.writeLong(mLastStartTime);
                    out.writeInt(mLastStarts);
                    out.writeInt(mLastLaunches);
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
                        } else if (which == STATS_UNPLUGGED) {
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
                        } else if (which == STATS_UNPLUGGED) {
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
                        } else if (which == STATS_UNPLUGGED) {
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

        public Timer getWakeTimerLocked(String name, int type) {
            Wakelock wl = mWakelockStats.get(name);
            if (wl == null) {
                wl = new Wakelock();
                mWakelockStats.put(name, wl);
            }
            Timer t = null;
            switch (type) {
                case WAKE_TYPE_PARTIAL:
                    t = wl.mTimerPartial;
                    if (t == null) {
                        t = new Timer(WAKE_TYPE_PARTIAL, mPartialTimers, mUnpluggables);
                        wl.mTimerPartial = t;
                    }
                    return t;
                case WAKE_TYPE_FULL:
                    t = wl.mTimerFull;
                    if (t == null) {
                        t = new Timer(WAKE_TYPE_FULL, mFullTimers, mUnpluggables);
                        wl.mTimerFull = t;
                    }
                    return t;
                case WAKE_TYPE_WINDOW:
                    t = wl.mTimerWindow;
                    if (t == null) {
                        t = new Timer(WAKE_TYPE_WINDOW, mWindowTimers, mUnpluggables);
                        wl.mTimerWindow = t;
                    }
                    return t;
                default:
                    throw new IllegalArgumentException("type=" + type);
            }
        }

        public Timer getSensorTimerLocked(int sensor, boolean create) {
            Sensor se = mSensorStats.get(sensor);
            if (se == null) {
                if (!create) {
                    return null;
                }
                se = new Sensor(sensor);
                mSensorStats.put(sensor, se);
            }
            Timer t = se.mTimer;
            if (t != null) {
                return t;
            }
            ArrayList<Timer> timers = mSensorTimers.get(sensor);
            if (timers == null) {
                timers = new ArrayList<Timer>();
                mSensorTimers.put(sensor, timers);
            }
            t = new Timer(BatteryStats.SENSOR, timers, mUnpluggables);
            se.mTimer = t;
            return t;
        }

        public void noteStartWakeLocked(String name, int type) {
            Timer t = getWakeTimerLocked(name, type);
            if (t != null) {
                t.startRunningLocked(BatteryStatsImpl.this);
            }
        }

        public void noteStopWakeLocked(String name, int type) {
            Timer t = getWakeTimerLocked(name, type);
            if (t != null) {
                t.stopRunningLocked(BatteryStatsImpl.this);
            }
        }
        
        public void noteStartSensor(int sensor) {
            Timer t = getSensorTimerLocked(sensor, true);
            if (t != null) {
                t.startRunningLocked(BatteryStatsImpl.this);
            }            
        }

        public void noteStopSensor(int sensor) {
            // Don't create a timer if one doesn't already exist
            Timer t = getSensorTimerLocked(sensor, false);
            if (t != null) {
                t.stopRunningLocked(BatteryStatsImpl.this);
            }            
        }
        
        public void noteStartGps() {
            Timer t = getSensorTimerLocked(Sensor.GPS, true);
            if (t != null) {
                t.startRunningLocked(BatteryStatsImpl.this);
            }  
        }
        
        public void noteStopGps() {
            Timer t = getSensorTimerLocked(Sensor.GPS, false);
            if (t != null) {
                t.stopRunningLocked(BatteryStatsImpl.this);
            }  
        }

        public BatteryStatsImpl getBatteryStats() {
            return BatteryStatsImpl.this;
        }
    }

    public BatteryStatsImpl(String filename) {
        mFile = new File(filename);
        mBackupFile = new File(filename + ".bak");
        mStartCount++;
        mScreenOnTimer = new Timer(-1, null, mUnpluggables);
        for (int i=0; i<NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            mScreenBrightnessTimer[i] = new Timer(-100-i, null, mUnpluggables);
        }
        mInputEventCounter = new Counter(mUnpluggables);
        mPhoneOnTimer = new Timer(-2, null, mUnpluggables);
        for (int i=0; i<NUM_SIGNAL_STRENGTH_BINS; i++) {
            mPhoneSignalStrengthsTimer[i] = new Timer(-200-i, null, mUnpluggables);
        }
        for (int i=0; i<NUM_DATA_CONNECTION_TYPES; i++) {
            mPhoneDataConnectionsTimer[i] = new Timer(-300-i, null, mUnpluggables);
        }
        mWifiOnTimer = new Timer(-3, null, mUnpluggables);
        mWifiRunningTimer = new Timer(-4, null, mUnpluggables);
        mBluetoothOnTimer = new Timer(-5, null, mUnpluggables);
        mOnBattery = mOnBatteryInternal = false;
        mTrackBatteryPastUptime = 0;
        mTrackBatteryPastRealtime = 0;
        mUptimeStart = mTrackBatteryUptimeStart = SystemClock.uptimeMillis() * 1000;
        mRealtimeStart = mTrackBatteryRealtimeStart = SystemClock.elapsedRealtime() * 1000;
        mUnpluggedBatteryUptime = getBatteryUptimeLocked(mUptimeStart);
        mUnpluggedBatteryRealtime = getBatteryRealtimeLocked(mRealtimeStart);
        mUnpluggedStartLevel = 0;
        mPluggedStartLevel = 0;
    }

    public BatteryStatsImpl(Parcel p) {
        mFile = mBackupFile = null;
        readFromParcel(p);
    }

    @Override
    public int getStartCount() {
        return mStartCount;
    }

    public boolean isOnBattery() {
        return mOnBattery;
    }

    public void setOnBattery(boolean onBattery, int level) {
        synchronized(this) {
            if (mOnBattery != onBattery) {
                mOnBattery = mOnBatteryInternal = onBattery;
                
                long uptime = SystemClock.uptimeMillis() * 1000;
                long mSecRealtime = SystemClock.elapsedRealtime();
                long realtime = mSecRealtime * 1000;
                if (onBattery) {
                    mTrackBatteryUptimeStart = uptime;
                    mTrackBatteryRealtimeStart = realtime;
                    mUnpluggedBatteryUptime = getBatteryUptimeLocked(uptime);
                    mUnpluggedBatteryRealtime = getBatteryRealtimeLocked(realtime);
                    mUnpluggedStartLevel = level;
                    doUnplug(mUnpluggedBatteryUptime, mUnpluggedBatteryRealtime);
                } else {
                    mTrackBatteryPastUptime += uptime - mTrackBatteryUptimeStart;
                    mTrackBatteryPastRealtime += realtime - mTrackBatteryRealtimeStart;
                    mPluggedStartLevel = level;
                    doPlug(getBatteryUptimeLocked(uptime), getBatteryRealtimeLocked(realtime));
                }
                if ((mLastWriteTime + (60 * 1000)) < mSecRealtime) {
                    if (mFile != null) {
                        writeLocked();
                    }
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
            case STATS_TOTAL: return mUptime + (curTime-mUptimeStart);
            case STATS_LAST: return mLastUptime;
            case STATS_CURRENT: return (curTime-mUptimeStart);
            case STATS_UNPLUGGED: return (curTime-mTrackBatteryUptimeStart);
        }
        return 0;
    }

    @Override
    public long computeRealtime(long curTime, int which) {
        switch (which) {
            case STATS_TOTAL: return mRealtime + (curTime-mRealtimeStart);
            case STATS_LAST: return mLastRealtime;
            case STATS_CURRENT: return (curTime-mRealtimeStart);
            case STATS_UNPLUGGED: return (curTime-mTrackBatteryRealtimeStart);
        }
        return 0;
    }

    @Override
    public long computeBatteryUptime(long curTime, int which) {
        switch (which) {
            case STATS_TOTAL:
                return mBatteryUptime + getBatteryUptime(curTime);
            case STATS_LAST:
                return mBatteryLastUptime;
            case STATS_CURRENT:
                return getBatteryUptime(curTime);
            case STATS_UNPLUGGED:
                return getBatteryUptimeLocked(curTime) - mUnpluggedBatteryUptime;
        }
        return 0;
    }

    @Override
    public long computeBatteryRealtime(long curTime, int which) {
        switch (which) {
            case STATS_TOTAL:
                return mBatteryRealtime + getBatteryRealtimeLocked(curTime);
            case STATS_LAST:
                return mBatteryLastRealtime;
            case STATS_CURRENT:
                return getBatteryRealtimeLocked(curTime);
            case STATS_UNPLUGGED:
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
    
    @Override
    public int getUnpluggedStartLevel() {
        synchronized(this) {
            return getUnluggedStartLevelLocked();
        }
    }
    
    public int getUnluggedStartLevelLocked() {
            return mUnpluggedStartLevel;
    }
    
    @Override
    public int getPluggedStartLevel() {
        synchronized(this) {
            return getPluggedStartLevelLocked();
        }
    }
    
    public int getPluggedStartLevelLocked() {
            return mPluggedStartLevel;
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

    public void writeLocked() {
        if ((mFile == null) || (mBackupFile == null)) {
            Log.w("BatteryStats", "writeLocked: no file associated with this instance");
            return;
        }

        // Keep the old file around until we know the new one has
        // been successfully written.
        if (mFile.exists()) {
            if (mBackupFile.exists()) {
                mBackupFile.delete();
            }
            mFile.renameTo(mBackupFile);
        }

        try {
            FileOutputStream stream = new FileOutputStream(mFile);
            Parcel out = Parcel.obtain();
            writeSummaryToParcel(out);
            stream.write(out.marshall());
            out.recycle();

            stream.flush();
            stream.close();
            mBackupFile.delete();

            mLastWriteTime = SystemClock.elapsedRealtime();
        } catch (IOException e) {
            Log.e("BatteryStats", "Error writing battery statistics", e);
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
        if ((mFile == null) || (mBackupFile == null)) {
            Log.w("BatteryStats", "readLocked: no file associated with this instance");
            return;
        }

        mUidStats.clear();

        FileInputStream stream = null;
        if (mBackupFile.exists()) {
            try {
                stream = new FileInputStream(mBackupFile);
            } catch (java.io.IOException e) {
                // We'll try for the normal settings file.
            }
        }

        try {
            if (stream == null) {
                if (!mFile.exists()) {
                    return;
                }
                stream = new FileInputStream(mFile);
            }

            byte[] raw = readFully(stream);
            Parcel in = Parcel.obtain();
            in.unmarshall(raw, 0, raw.length);
            in.setDataPosition(0);
            stream.close();

            readSummaryFromParcel(in);
        } catch(java.io.IOException e) {
            Log.e("BatteryStats", "Error reading battery statistics", e);
        }
    }

    public int describeContents() {
        return 0;
    }

    private void readSummaryFromParcel(Parcel in) {
        final int version = in.readInt();
        if (version != VERSION) {
            Log.w("BatteryStats", "readFromParcel: version got " + version
                + ", expected " + VERSION + "; erasing old stats");
            return;
        }

        mStartCount = in.readInt();
        mBatteryUptime = in.readLong();
        mBatteryLastUptime = in.readLong();
        mBatteryRealtime = in.readLong();
        mBatteryLastRealtime = in.readLong();
        mUptime = in.readLong();
        mLastUptime = in.readLong();
        mRealtime = in.readLong();
        mLastRealtime = in.readLong();
        mUnpluggedStartLevel = in.readInt();
        mPluggedStartLevel = in.readInt();
        
        mStartCount++;
        
        mScreenOn = false;
        mScreenOnTimer.readSummaryFromParcelLocked(in);
        for (int i=0; i<NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            mScreenBrightnessTimer[i].readSummaryFromParcelLocked(in);
        }
        mInputEventCounter.readSummaryFromParcelLocked(in);
        mPhoneOn = false;
        mPhoneOnTimer.readSummaryFromParcelLocked(in);
        for (int i=0; i<NUM_SIGNAL_STRENGTH_BINS; i++) {
            mPhoneSignalStrengthsTimer[i].readSummaryFromParcelLocked(in);
        }
        for (int i=0; i<NUM_DATA_CONNECTION_TYPES; i++) {
            mPhoneDataConnectionsTimer[i].readSummaryFromParcelLocked(in);
        }
        mWifiOn = false;
        mWifiOnTimer.readSummaryFromParcelLocked(in);
        mWifiRunning = false;
        mWifiRunningTimer.readSummaryFromParcelLocked(in);
        mBluetoothOn = false;
        mBluetoothOnTimer.readSummaryFromParcelLocked(in);

        final int NU = in.readInt();
        for (int iu = 0; iu < NU; iu++) {
            int uid = in.readInt();
            Uid u = new Uid(uid);
            mUidStats.put(uid, u);

            u.mWifiTurnedOn = false;
            u.mWifiTurnedOnTimer.readSummaryFromParcelLocked(in);
            u.mFullWifiLockOut = false;
            u.mFullWifiLockTimer.readSummaryFromParcelLocked(in);
            u.mScanWifiLockOut = false;
            u.mScanWifiLockTimer.readSummaryFromParcelLocked(in);
            
            if (in.readInt() != 0) {
                if (u.mUserActivityCounters == null) {
                    u.initUserActivityLocked();
                }
                for (int i=0; i<Uid.NUM_USER_ACTIVITY_TYPES; i++) {
                    u.mUserActivityCounters[i].readSummaryFromParcelLocked(in);
                }
            }
            
            int NW = in.readInt();
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
            for (int is = 0; is < NP; is++) {
                int seNumber = in.readInt();
                if (in.readInt() != 0) {
                    u.getSensorTimerLocked(seNumber, true)
                            .readSummaryFromParcelLocked(in);
                }
            }

            NP = in.readInt();
            for (int ip = 0; ip < NP; ip++) {
                String procName = in.readString();
                Uid.Proc p = u.getProcessStatsLocked(procName);
                p.mUserTime = p.mLoadedUserTime = in.readLong();
                p.mLastUserTime = in.readLong();
                p.mSystemTime = p.mLoadedSystemTime = in.readLong();
                p.mLastSystemTime = in.readLong();
                p.mStarts = p.mLoadedStarts = in.readInt();
                p.mLastStarts = in.readInt();
            }

            NP = in.readInt();
            for (int ip = 0; ip < NP; ip++) {
                String pkgName = in.readString();
                Uid.Pkg p = u.getPackageStatsLocked(pkgName);
                p.mWakeups = p.mLoadedWakeups = in.readInt();
                p.mLastWakeups = in.readInt();
                final int NS = in.readInt();
                for (int is = 0; is < NS; is++) {
                    String servName = in.readString();
                    Uid.Pkg.Serv s = u.getServiceStatsLocked(pkgName, servName);
                    s.mStartTime = s.mLoadedStartTime = in.readLong();
                    s.mLastStartTime = in.readLong();
                    s.mStarts = s.mLoadedStarts = in.readInt();
                    s.mLastStarts = in.readInt();
                    s.mLaunches = s.mLoadedLaunches = in.readInt();
                    s.mLastLaunches = in.readInt();
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
        final long NOW_SYS = SystemClock.uptimeMillis() * 1000;
        final long NOWREAL_SYS = SystemClock.elapsedRealtime() * 1000;
        final long NOW = getBatteryUptimeLocked(NOW_SYS);
        final long NOWREAL = getBatteryRealtimeLocked(NOWREAL_SYS);

        out.writeInt(VERSION);

        out.writeInt(mStartCount);
        out.writeLong(computeBatteryUptime(NOW_SYS, STATS_TOTAL));
        out.writeLong(computeBatteryUptime(NOW_SYS, STATS_CURRENT));
        out.writeLong(computeBatteryRealtime(NOWREAL_SYS, STATS_TOTAL));
        out.writeLong(computeBatteryRealtime(NOWREAL_SYS, STATS_CURRENT));
        out.writeLong(computeUptime(NOW_SYS, STATS_TOTAL));
        out.writeLong(computeUptime(NOW_SYS, STATS_CURRENT));
        out.writeLong(computeRealtime(NOWREAL_SYS, STATS_TOTAL));
        out.writeLong(computeRealtime(NOWREAL_SYS, STATS_CURRENT));
        out.writeInt(mUnpluggedStartLevel);
        out.writeInt(mPluggedStartLevel);
        
        
        mScreenOnTimer.writeSummaryFromParcelLocked(out, NOWREAL);
        for (int i=0; i<NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            mScreenBrightnessTimer[i].writeSummaryFromParcelLocked(out, NOWREAL);
        }
        mInputEventCounter.writeSummaryFromParcelLocked(out);
        mPhoneOnTimer.writeSummaryFromParcelLocked(out, NOWREAL);
        for (int i=0; i<NUM_SIGNAL_STRENGTH_BINS; i++) {
            mPhoneSignalStrengthsTimer[i].writeSummaryFromParcelLocked(out, NOWREAL);
        }
        for (int i=0; i<NUM_DATA_CONNECTION_TYPES; i++) {
            mPhoneDataConnectionsTimer[i].writeSummaryFromParcelLocked(out, NOWREAL);
        }
        mWifiOnTimer.writeSummaryFromParcelLocked(out, NOWREAL);
        mWifiRunningTimer.writeSummaryFromParcelLocked(out, NOWREAL);
        mBluetoothOnTimer.writeSummaryFromParcelLocked(out, NOWREAL);

        final int NU = mUidStats.size();
        out.writeInt(NU);
        for (int iu = 0; iu < NU; iu++) {
            out.writeInt(mUidStats.keyAt(iu));
            Uid u = mUidStats.valueAt(iu);
            
            u.mWifiTurnedOnTimer.writeSummaryFromParcelLocked(out, NOWREAL);
            u.mFullWifiLockTimer.writeSummaryFromParcelLocked(out, NOWREAL);
            u.mScanWifiLockTimer.writeSummaryFromParcelLocked(out, NOWREAL);

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
                    out.writeLong(ps.mUserTime - ps.mLoadedUserTime);
                    out.writeLong(ps.mSystemTime);
                    out.writeLong(ps.mSystemTime - ps.mLoadedSystemTime);
                    out.writeInt(ps.mStarts);
                    out.writeInt(ps.mStarts - ps.mLoadedStarts);
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
                    out.writeInt(ps.mWakeups - ps.mLoadedWakeups);
                    final int NS = ps.mServiceStats.size();
                    out.writeInt(NS);
                    if (NS > 0) {
                        for (Map.Entry<String, BatteryStatsImpl.Uid.Pkg.Serv> sent
                                : ps.mServiceStats.entrySet()) {
                            out.writeString(sent.getKey());
                            BatteryStatsImpl.Uid.Pkg.Serv ss = sent.getValue();
                            long time = ss.getStartTimeToNowLocked(NOW);
                            out.writeLong(time);
                            out.writeLong(time - ss.mLoadedStartTime);
                            out.writeInt(ss.mStarts);
                            out.writeInt(ss.mStarts - ss.mLoadedStarts);
                            out.writeInt(ss.mLaunches);
                            out.writeInt(ss.mLaunches - ss.mLoadedLaunches);
                        }
                    }
                }
            }
            
            out.writeLong(u.getTcpBytesReceived(STATS_TOTAL));
            out.writeLong(u.getTcpBytesSent(STATS_TOTAL));
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

        mStartCount = in.readInt();
        mBatteryUptime = in.readLong();
        mBatteryLastUptime = in.readLong();
        mBatteryRealtime = in.readLong();
        mBatteryLastRealtime = in.readLong();
        mScreenOn = false;
        mScreenOnTimer = new Timer(-1, null, mUnpluggables, in);
        for (int i=0; i<NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            mScreenBrightnessTimer[i] = new Timer(-100-i, null, mUnpluggables, in);
        }
        mInputEventCounter = new Counter(mUnpluggables, in);
        mPhoneOn = false;
        mPhoneOnTimer = new Timer(-2, null, mUnpluggables, in);
        for (int i=0; i<NUM_SIGNAL_STRENGTH_BINS; i++) {
            mPhoneSignalStrengthsTimer[i] = new Timer(-200-i, null, mUnpluggables, in);
        }
        for (int i=0; i<NUM_DATA_CONNECTION_TYPES; i++) {
            mPhoneDataConnectionsTimer[i] = new Timer(-300-i, null, mUnpluggables, in);
        }
        mWifiOn = false;
        mWifiOnTimer = new Timer(-2, null, mUnpluggables, in);
        mWifiRunning = false;
        mWifiRunningTimer = new Timer(-2, null, mUnpluggables, in);
        mBluetoothOn = false;
        mBluetoothOnTimer = new Timer(-2, null, mUnpluggables, in);
        mUptime = in.readLong();
        mUptimeStart = in.readLong();
        mLastUptime = in.readLong();
        mRealtime = in.readLong();
        mRealtimeStart = in.readLong();
        mLastRealtime = in.readLong();
        mOnBattery = in.readInt() != 0;
        mOnBatteryInternal = false; // we are no longer really running.
        mTrackBatteryPastUptime = in.readLong();
        mTrackBatteryUptimeStart = in.readLong();
        mTrackBatteryPastRealtime = in.readLong();
        mTrackBatteryRealtimeStart = in.readLong();
        mUnpluggedBatteryUptime = in.readLong();
        mUnpluggedBatteryRealtime = in.readLong();
        mUnpluggedStartLevel = in.readInt();
        mPluggedStartLevel = in.readInt();
        mLastWriteTime = in.readLong();

        mPartialTimers.clear();
        mFullTimers.clear();
        mWindowTimers.clear();

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
        writeToParcelLocked(out, flags);
    }
    
    @SuppressWarnings("unused") 
    void writeToParcelLocked(Parcel out, int flags) {
        final long uSecUptime = SystemClock.uptimeMillis() * 1000;
        final long uSecRealtime = SystemClock.elapsedRealtime() * 1000;
        final long batteryUptime = getBatteryUptimeLocked(uSecUptime);
        final long batteryRealtime = getBatteryRealtimeLocked(uSecRealtime);
        
        out.writeInt(MAGIC);
        out.writeInt(mStartCount);
        out.writeLong(mBatteryUptime);
        out.writeLong(mBatteryLastUptime);
        out.writeLong(mBatteryRealtime);
        out.writeLong(mBatteryLastRealtime);
        mScreenOnTimer.writeToParcel(out, batteryRealtime);
        for (int i=0; i<NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            mScreenBrightnessTimer[i].writeToParcel(out, batteryRealtime);
        }
        mInputEventCounter.writeToParcel(out);
        mPhoneOnTimer.writeToParcel(out, batteryRealtime);
        for (int i=0; i<NUM_SIGNAL_STRENGTH_BINS; i++) {
            mPhoneSignalStrengthsTimer[i].writeToParcel(out, batteryRealtime);
        }
        for (int i=0; i<NUM_DATA_CONNECTION_TYPES; i++) {
            mPhoneDataConnectionsTimer[i].writeToParcel(out, batteryRealtime);
        }
        mWifiOnTimer.writeToParcel(out, batteryRealtime);
        mWifiRunningTimer.writeToParcel(out, batteryRealtime);
        mBluetoothOnTimer.writeToParcel(out, batteryRealtime);
        out.writeLong(mUptime);
        out.writeLong(mUptimeStart);
        out.writeLong(mLastUptime);
        out.writeLong(mRealtime);
        out.writeLong(mRealtimeStart);
        out.writeLong(mLastRealtime);
        out.writeInt(mOnBattery ? 1 : 0);
        out.writeLong(batteryUptime);
        out.writeLong(mTrackBatteryUptimeStart);
        out.writeLong(batteryRealtime);
        out.writeLong(mTrackBatteryRealtimeStart);
        out.writeLong(mUnpluggedBatteryUptime);
        out.writeLong(mUnpluggedBatteryRealtime);
        out.writeInt(mUnpluggedStartLevel);
        out.writeInt(mPluggedStartLevel);
        out.writeLong(mLastWriteTime);

        int size = mUidStats.size();
        out.writeInt(size);
        for (int i = 0; i < size; i++) {
            out.writeInt(mUidStats.keyAt(i));
            Uid uid = mUidStats.valueAt(i);

            uid.writeToParcelLocked(out, batteryRealtime);
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
    
    public void dumpLocked(Printer pw) {
        if (DEBUG) {
            pw.println("*** Screen timer:");
            mScreenOnTimer.logState(pw, "  ");
            for (int i=0; i<NUM_SCREEN_BRIGHTNESS_BINS; i++) {
                pw.println("*** Screen brightness #" + i + ":");
                mScreenBrightnessTimer[i].logState(pw, "  ");
            }
            pw.println("*** Input event counter:");
            mInputEventCounter.logState(pw, "  ");
            pw.println("*** Phone timer:");
            mPhoneOnTimer.logState(pw, "  ");
            for (int i=0; i<NUM_SIGNAL_STRENGTH_BINS; i++) {
                pw.println("*** Signal strength #" + i + ":");
                mPhoneSignalStrengthsTimer[i].logState(pw, "  ");
            }
            for (int i=0; i<NUM_DATA_CONNECTION_TYPES; i++) {
                pw.println("*** Data connection type #" + i + ":");
                mPhoneDataConnectionsTimer[i].logState(pw, "  ");
            }
            pw.println("*** Wifi timer:");
            mWifiOnTimer.logState(pw, "  ");
            pw.println("*** WifiRunning timer:");
            mWifiRunningTimer.logState(pw, "  ");
            pw.println("*** Bluetooth timer:");
            mBluetoothOnTimer.logState(pw, "  ");
        }
        super.dumpLocked(pw);
    }
}
