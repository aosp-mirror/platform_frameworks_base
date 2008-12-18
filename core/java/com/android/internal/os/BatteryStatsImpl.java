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
import android.os.Parcel;
import android.os.ParcelFormatException;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * All information we are collecting about things that can happen that impact
 * battery life.  All times are represented in microseconds except where indicated
 * otherwise.
 */
public final class BatteryStatsImpl extends BatteryStats implements Parcelable {

    // In-memory Parcel magic number, used to detect attempts to unmarshall bad data
    private static final int MAGIC = 0xBA757475; // 'BATSTATS' 

    // Current on-disk Parcel version
    private static final int VERSION = 13;

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
    final ArrayList<Timer> mSensorTimers = new ArrayList<Timer>();

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

    /**
     * These provide time bases that discount the time the device is plugged
     * in to power.
     */
    boolean mOnBattery;
    long mTrackBatteryPastUptime;
    long mTrackBatteryUptimeStart;
    long mTrackBatteryPastRealtime;
    long mTrackBatteryRealtimeStart;

    long mLastWriteTime = 0; // Milliseconds

    // For debugging
    public BatteryStatsImpl() {
        mFile = mBackupFile = null;
    }

    /**
     * State for keeping track of timing information.
     */
    public static final class Timer extends BatteryStats.Timer {
        ArrayList<Timer> mTimerPool;
        
        int mType;
        int mNesting;
        
        int mCount;
        int mLoadedCount;
        int mLastCount;
        
        // Times are in microseconds for better accuracy when dividing by the lock count
        
        long mTotalTime; // Add mUnpluggedTotalTime to get true value
        long mLoadedTotalTime;
        long mLastTotalTime;
        long mStartTime;
        long mUpdateTime;
        
        /**
         * The value of mTotalTime when unplug() was last called, initially 0.
         */
        long mTotalTimeAtLastUnplug;

        /** Constructor used for unmarshalling only. */
        Timer() {}

        Timer(int type, ArrayList<Timer> timerPool) {
            mType = type;
            mTimerPool = timerPool;
        }
        
        public void writeToParcel(Parcel out) {
            out.writeInt(mType);
            out.writeInt(mNesting);
            out.writeInt(mCount);
            out.writeInt(mLoadedCount);
            out.writeInt(mLastCount);
            out.writeLong(mTotalTime);
            out.writeLong(mLoadedTotalTime);
            out.writeLong(mLastTotalTime);
            out.writeLong(mStartTime);
            out.writeLong(mUpdateTime);
            out.writeLong(mTotalTimeAtLastUnplug);
        }

        public void readFromParcel(Parcel in) {
            mType = in.readInt();
            mNesting = in.readInt();
            mCount = in.readInt();
            mLoadedCount = in.readInt();
            mLastCount = in.readInt();
            mTotalTime = in.readLong();
            mLoadedTotalTime = in.readLong();
            mLastTotalTime = in.readLong();
            mStartTime = in.readLong();
            mUpdateTime = in.readLong();
            mTotalTimeAtLastUnplug = in.readLong();
        }
        
        private void unplug() {
            mTotalTimeAtLastUnplug += mTotalTime;
            mTotalTime = 0;
        }

        /**
         * Writes a possibly null Timer to a Parcel.
         *
         * @param out the Parcel to be written to.
         * @param timer a Timer, or null.
         */
        public static void writeTimerToParcel(Parcel out, Timer timer) {
            if (timer == null) {
                out.writeInt(0); // indicates null
                return;
            }
            out.writeInt(1); // indicates non-null

            timer.writeToParcel(out);
        }

        @Override
        public long getTotalTime(long now, int which) {
            long val;
            if (which == STATS_LAST) {
                val = mLastTotalTime;
            } else {
                val = computeRunTimeLocked(now);
                if (which != STATS_UNPLUGGED) {
                    val += mTotalTimeAtLastUnplug;
                }
                if ((which == STATS_CURRENT) || (which == STATS_UNPLUGGED)) {
                    val -= mLoadedTotalTime;
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
                if ((which == STATS_CURRENT) || (which == STATS_UNPLUGGED)) {
                    val -= mLoadedCount;
                }
            }

            return val;
        }

        void startRunningLocked(BatteryStatsImpl stats) {
            if (mNesting++ == 0) {
                mStartTime = mUpdateTime =
                    stats.getBatteryUptimeLocked(SystemClock.elapsedRealtime() * 1000);
                // Accumulate time to all other active counters with the current value of mCount
                refreshTimersLocked(stats);
                // Add this timer to the active pool
                mTimerPool.add(this);
                // Increment the count
                mCount++;
            }
        }

        void stopRunningLocked(BatteryStatsImpl stats) {
            // Ignore attempt to stop a timer that isn't running
            if (mNesting == 0) {
                return;
            }
            if (--mNesting == 0) {
                // Accumulate time to all active counters with the current value of mCount
                refreshTimersLocked(stats);
                // Remove this timer from the active pool
                mTimerPool.remove(this);
                // Decrement the count
                mCount--;
            }
        }

        // Update the total time for all other running Timers with the same type as this Timer
        // due to a change in timer count
        private void refreshTimersLocked(BatteryStatsImpl stats) {
            for (Timer t : mTimerPool) {
                t.updateTimeLocked(stats);
            }
        }

        /**
         * Update totalTime and reset updateTime
         * @param stats
         */
        private void updateTimeLocked(BatteryStatsImpl stats) {
            long realtime = SystemClock.elapsedRealtime() * 1000; 
            long heldTime = stats.getBatteryUptimeLocked(realtime) - mUpdateTime;
            if (heldTime > 0) {
                mTotalTime += (heldTime * 1000) / mCount;
            }
            mUpdateTime = stats.getBatteryUptimeLocked(realtime);
        }

        private long computeRunTimeLocked(long curBatteryUptime) {
            return mTotalTime +
                (mNesting > 0 ? ((curBatteryUptime * 1000) - mUpdateTime) / mCount : 0);
        }

        void writeSummaryFromParcelLocked(Parcel out, long curBatteryUptime) {
            long runTime = computeRunTimeLocked(curBatteryUptime);
            // Divide by 1000 for backwards compatibility
            out.writeLong((runTime + 500) / 1000);
            out.writeLong(((runTime - mLoadedTotalTime) + 500) / 1000);
            out.writeInt(mCount);
            out.writeInt(mCount - mLoadedCount);
        }

        void readSummaryFromParcelLocked(Parcel in) {
            // Multiply by 1000 for backwards compatibility
            mTotalTime = mLoadedTotalTime = in.readLong() * 1000;
            mLastTotalTime = in.readLong();
            mCount = mLoadedCount = in.readInt();
            mLastCount = in.readInt();
            mNesting = 0;
        }
    }
    
    public void unplugTimers() {
        ArrayList<Timer> timers;
        
        timers = mPartialTimers;
        for (int i = timers.size() - 1; i >= 0; i--) {
            timers.get(i).unplug();
        }
        timers = mFullTimers;
        for (int i = timers.size() - 1; i >= 0; i--) {
            timers.get(i).unplug();
        }
        timers = mWindowTimers;
        for (int i = timers.size() - 1; i >= 0; i--) {
            timers.get(i).unplug();
        }
        timers = mSensorTimers;
        for (int i = timers.size() - 1; i >= 0; i--) {
            timers.get(i).unplug();
        }
    }
    
    @Override
    public SparseArray<? extends BatteryStats.Uid> getUidStats() {
        return mUidStats;
    }

    /**
     * The statistics associated with a particular uid.
     */
    public final class Uid extends BatteryStats.Uid {

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

        void writeToParcelLocked(Parcel out) {
            out.writeInt(mWakelockStats.size());
            for (Map.Entry<String, Uid.Wakelock> wakelockEntry : mWakelockStats.entrySet()) {
                out.writeString(wakelockEntry.getKey());
                Uid.Wakelock wakelock = wakelockEntry.getValue();
                wakelock.writeToParcelLocked(out);
            }

            out.writeInt(mSensorStats.size());
            for (Map.Entry<Integer, Uid.Sensor> sensorEntry : mSensorStats.entrySet()) {
                out.writeInt(sensorEntry.getKey());
                Uid.Sensor sensor = sensorEntry.getValue();
                sensor.writeToParcelLocked(out);
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
        }

        void readFromParcelLocked(Parcel in) {
            int numWakelocks = in.readInt();
            mWakelockStats.clear();
            for (int j = 0; j < numWakelocks; j++) {
                String wakelockName = in.readString();
                Uid.Wakelock wakelock = new Wakelock();
                wakelock.readFromParcelLocked(in);
                mWakelockStats.put(wakelockName, wakelock);
            }

            int numSensors = in.readInt();
            mSensorStats.clear();
            for (int k = 0; k < numSensors; k++) {
                int sensorNumber = in.readInt();
                Uid.Sensor sensor = new Sensor();
                sensor.readFromParcelLocked(in);
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
        }

        /**
         * The statistics associated with a particular wake lock.
         */
        public final class Wakelock extends BatteryStats.Uid.Wakelock {
            /**
             * How long (in ms) this uid has been keeping the device partially awake.
             */
            Timer wakeTimePartial;

            /**
             * How long (in ms) this uid has been keeping the device fully awake.
             */
            Timer wakeTimeFull;

            /**
             * How long (in ms) this uid has had a window keeping the device awake.
             */
            Timer wakeTimeWindow;

            /**
             * Reads a possibly null Timer from a Parcel.  The timer is associated with the
             * proper timer pool from the given BatteryStatsImpl object.
             *
             * @param in the Parcel to be read from.
             * return a new Timer, or null.
             */
            private Timer readTimerFromParcel(Parcel in) {
                if (in.readInt() == 0) {
                    return null;
                }

                Timer timer = new Timer();
                timer.readFromParcel(in);
                // Set the timer pool for the timer according to its type
                switch (timer.mType) {
                case WAKE_TYPE_PARTIAL:
                    timer.mTimerPool = mPartialTimers;
                    break;
                case WAKE_TYPE_FULL:
                    timer.mTimerPool = mFullTimers;
                    break;
                case WAKE_TYPE_WINDOW:
                    timer.mTimerPool = mWindowTimers;
                    break;
                }
                // If the timer is active, add it to the pool
                if (timer.mNesting > 0) {
                    timer.mTimerPool.add(timer);
                }
                return timer;
            }

            void readFromParcelLocked(Parcel in) {
                wakeTimePartial = readTimerFromParcel(in);
                wakeTimeFull = readTimerFromParcel(in);
                wakeTimeWindow = readTimerFromParcel(in);
            }

            void writeToParcelLocked(Parcel out) {
                Timer.writeTimerToParcel(out, wakeTimePartial);
                Timer.writeTimerToParcel(out, wakeTimeFull);
                Timer.writeTimerToParcel(out, wakeTimeWindow);
            }

            @Override
            public Timer getWakeTime(int type) {
                switch (type) {
                case WAKE_TYPE_FULL: return wakeTimeFull;
                case WAKE_TYPE_PARTIAL: return wakeTimePartial;
                case WAKE_TYPE_WINDOW: return wakeTimeWindow;
                default: throw new IllegalArgumentException("type = " + type);
                }
            }
        }

        public final class Sensor extends BatteryStats.Uid.Sensor {
            Timer sensorTime;

            private Timer readTimerFromParcel(Parcel in) {
                if (in.readInt() == 0) {
                    return null;
                }

                Timer timer = new Timer();
                timer.readFromParcel(in);
                // Set the timer pool for the timer
                timer.mTimerPool = mSensorTimers;

                // If the timer is active, add it to the pool
                if (timer.mNesting > 0) {
                    timer.mTimerPool.add(timer);
                }
                return timer;
            }

            void readFromParcelLocked(Parcel in) {
                sensorTime = readTimerFromParcel(in);
            }

            void writeToParcelLocked(Parcel out) {
                Timer.writeTimerToParcel(out, sensorTime);
            }

            @Override
            public Timer getSensorTime() {
                return sensorTime;
            }
        }

        /**
         * The statistics associated with a particular process.
         */
        public final class Proc extends BatteryStats.Uid.Proc {
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

            void writeToParcelLocked(Parcel out) {
                out.writeLong(mUserTime);
                out.writeLong(mSystemTime);
                out.writeInt(mStarts);
                out.writeLong(mLoadedUserTime);
                out.writeLong(mLoadedSystemTime);
                out.writeInt(mLoadedStarts);
                out.writeLong(mLastUserTime);
                out.writeLong(mLastSystemTime);
                out.writeInt(mLastStarts);
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
                    }
                }
                return val;
            }
        }

        /**
         * The statistics associated with a particular package.
         */
        public final class Pkg extends BatteryStats.Uid.Pkg {
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
             * The statics we have collected for this package's services.
             */
            final HashMap<String, Serv> mServiceStats = new HashMap<String, Serv>();

            void readFromParcelLocked(Parcel in) {
                mWakeups = in.readInt();
                mLoadedWakeups = in.readInt();
                mLastWakeups = in.readInt();

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
                    }
                }

                return val;
            }

            /**
             * The statistics associated with a particular service.
             */
            public final class Serv extends BatteryStats.Uid.Pkg.Serv {
                /**
                 * Total time (ms) the service has been left started.
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
                 * Total time (ms) the service has been left launched.
                 */
                long mLaunchedTime;

                /**
                 * If service has been launched and not yet exited, this is
                 * when it was launched.
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
                 * The amount of time spent started loaded from a previous save.
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
                 * The amount of time spent started as of the last run.
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
                    t = wl.wakeTimePartial;
                    if (t == null) {
                        t = new Timer(WAKE_TYPE_PARTIAL, mPartialTimers);
                        wl.wakeTimePartial = t;
                    }
                    return t;
                case WAKE_TYPE_FULL:
                    t = wl.wakeTimeFull;
                    if (t == null) {
                        t = new Timer(WAKE_TYPE_FULL, mFullTimers);
                        wl.wakeTimeFull = t;
                    }
                    return t;
                case WAKE_TYPE_WINDOW:
                    t = wl.wakeTimeWindow;
                    if (t == null) {
                        t = new Timer(WAKE_TYPE_WINDOW, mWindowTimers);
                        wl.wakeTimeWindow = t;
                    }
                    return t;
                default:
                    throw new IllegalArgumentException("type=" + type);
            }
        }

        public Timer getSensorTimerLocked(int sensor, boolean create) {
            Integer sId = Integer.valueOf(sensor);
            Sensor se = mSensorStats.get(sId);
            if (se == null) {
                if (!create) {
                    return null;
                }
                se = new Sensor();
                mSensorStats.put(sId, se);
            }
            Timer t = se.sensorTime;
            if (t == null) {
                t = new Timer(0, mSensorTimers);
                se.sensorTime = t;
            }
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

        public BatteryStatsImpl getBatteryStats() {
            return BatteryStatsImpl.this;
        }
    }

    public BatteryStatsImpl(String filename) {
        mFile = new File(filename);
        mBackupFile = new File(filename + ".bak");
        mStartCount++;
        mOnBattery = true;
        mTrackBatteryPastUptime = 0;
        mTrackBatteryPastRealtime = 0;
        mUptimeStart = mTrackBatteryUptimeStart = SystemClock.uptimeMillis() * 1000;
        mRealtimeStart = mTrackBatteryRealtimeStart = SystemClock.elapsedRealtime() * 1000;
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

    public void setOnBattery(boolean onBattery) {
        synchronized(this) {
            if (mOnBattery != onBattery) {
                long uptime = SystemClock.uptimeMillis() * 1000;
                long mSecRealtime = SystemClock.elapsedRealtime();
                long realtime = mSecRealtime * 1000;
                if (onBattery) {
                    mTrackBatteryUptimeStart = uptime;
                    mTrackBatteryRealtimeStart = realtime;
                    unplugTimers();
                } else {
                    mTrackBatteryPastUptime += uptime - mTrackBatteryUptimeStart;
                    mTrackBatteryPastRealtime += realtime - mTrackBatteryRealtimeStart;
                }
                mOnBattery = onBattery;
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
        case STATS_UNPLUGGED:
            return getBatteryUptime(curTime);
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
        case STATS_UNPLUGGED:
            return getBatteryRealtimeLocked(curTime);
        }
        return 0;
    }

    long getBatteryUptimeLocked(long curTime) {
        long time = mTrackBatteryPastUptime;
        if (mOnBattery) {
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
        if (mOnBattery) {
            time += curTime - mTrackBatteryRealtimeStart;
        }
        return time;
    }

    @Override
    public long getBatteryRealtime(long curTime) {
        return getBatteryRealtimeLocked(curTime);
    }

    /**
     * Retrieve the statistics object for a particular uid, creating if needed.
     */
    public Uid getUidStatsLocked(int uid) {
        Uid u = mUidStats.get(uid);
        if (u == null) {
            u = new Uid();
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
            Log.e("BatteryStats", "readFromParcel: version got " + version
                + ", expected " + VERSION);
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
        mStartCount++;

        final int NU = in.readInt();
        for (int iu=0; iu<NU; iu++) {
            int uid = in.readInt();
            Uid u = new Uid();
            mUidStats.put(uid, u);

            int NW = in.readInt();
            for (int iw=0; iw<NW; iw++) {
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

            if (version >= 12) {
                int NSE = in.readInt();
                for (int is=0; is<NSE; is++) {
                    int seNumber = in.readInt();
                    if (in.readInt() != 0) {
                        u.getSensorTimerLocked(seNumber, true).readSummaryFromParcelLocked(in);
                    }
                }
            }

            int NP = in.readInt();
            for (int ip=0; ip<NP; ip++) {
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
            for (int ip=0; ip<NP; ip++) {
                String pkgName = in.readString();
                Uid.Pkg p = u.getPackageStatsLocked(pkgName);
                p.mWakeups = p.mLoadedWakeups = in.readInt();
                p.mLastWakeups = in.readInt();
                final int NS = in.readInt();
                for (int is=0; is<NS; is++) {
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
        }
    }

    /**
     * Writes a summary of the statistics to a Parcel, in a format suitable to be written to
     * disk.  This format does not allow a lossless round-trip.
     *
     * @param out the Parcel to be written to.
     */
    public void writeSummaryToParcel(Parcel out) {
        final long NOW = getBatteryUptimeLocked();
        final long NOW_SYS = SystemClock.uptimeMillis() * 1000;
        final long NOWREAL_SYS = SystemClock.elapsedRealtime() * 1000;

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

        final int NU = mUidStats.size();
        out.writeInt(NU);
        for (int iu=0; iu<NU; iu++) {
            out.writeInt(mUidStats.keyAt(iu));
            Uid u = mUidStats.valueAt(iu);

            int NW = u.mWakelockStats.size();
            out.writeInt(NW);
            if (NW > 0) {
                for (Map.Entry<String, BatteryStatsImpl.Uid.Wakelock> ent
                    : u.mWakelockStats.entrySet()) {
                    out.writeString(ent.getKey());
                    Uid.Wakelock wl = ent.getValue();
                    if (wl.wakeTimeFull != null) {
                        out.writeInt(1);
                        wl.wakeTimeFull.writeSummaryFromParcelLocked(out, NOW);
                    } else {
                        out.writeInt(0);
                    }
                    if (wl.wakeTimePartial != null) {
                        out.writeInt(1);
                        wl.wakeTimePartial.writeSummaryFromParcelLocked(out, NOW);
                    } else {
                        out.writeInt(0);
                    }
                    if (wl.wakeTimeWindow != null) {
                        out.writeInt(1);
                        wl.wakeTimeWindow.writeSummaryFromParcelLocked(out, NOW);
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
                    if (se.sensorTime != null) {
                        out.writeInt(1);
                        se.sensorTime.writeSummaryFromParcelLocked(out, NOW);
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
        mUptime = in.readLong();
        mUptimeStart = in.readLong();
        mLastUptime = in.readLong();
        mRealtime = in.readLong();
        mRealtimeStart = in.readLong();
        mLastRealtime = in.readLong();
        mOnBattery = in.readInt() != 0;
        mTrackBatteryPastUptime = in.readLong();
        mTrackBatteryUptimeStart = in.readLong();
        mTrackBatteryPastRealtime = in.readLong();
        mTrackBatteryRealtimeStart = in.readLong();
        mLastWriteTime = in.readLong();

        mPartialTimers.clear();
        mFullTimers.clear();
        mWindowTimers.clear();

        int numUids = in.readInt();
        mUidStats.clear();
        for (int i = 0; i < numUids; i++) {
            int key = in.readInt();
            Uid uid = new Uid();
            uid.readFromParcelLocked(in);
            mUidStats.append(key, uid);
        }
    }

    public void writeToParcel(Parcel out, int flags) {
        writeToParcelLocked(out, flags);
    }
    
    @SuppressWarnings("unused") 
    void writeToParcelLocked(Parcel out, int flags) {
        out.writeInt(MAGIC);
        out.writeInt(mStartCount);
        out.writeLong(mBatteryUptime);
        out.writeLong(mBatteryLastUptime);
        out.writeLong(mBatteryRealtime);
        out.writeLong(mBatteryLastRealtime);
        out.writeLong(mUptime);
        out.writeLong(mUptimeStart);
        out.writeLong(mLastUptime);
        out.writeLong(mRealtime);
        out.writeLong(mRealtimeStart);
        out.writeLong(mLastRealtime);
        out.writeInt(mOnBattery ? 1 : 0);
        out.writeLong(mTrackBatteryPastUptime);
        out.writeLong(mTrackBatteryUptimeStart);
        out.writeLong(mTrackBatteryPastRealtime);
        out.writeLong(mTrackBatteryRealtimeStart);
        out.writeLong(mLastWriteTime);

        int size = mUidStats.size();
        out.writeInt(size);
        for (int i = 0; i < size; i++) {
            out.writeInt(mUidStats.keyAt(i));
            Uid uid = mUidStats.valueAt(i);

            uid.writeToParcelLocked(out);
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
}
