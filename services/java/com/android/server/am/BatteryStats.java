/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.server.am;

import com.android.internal.app.IBatteryStats;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Config;
import android.util.Log;
import android.util.SparseArray;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * All information we are collecting about things that can happen that impact
 * battery life.
 */
public final class BatteryStats extends IBatteryStats.Stub {
    public static final int WAKE_TYPE_PARTIAL = 0;
    public static final int WAKE_TYPE_FULL = 1;
    public static final int WAKE_TYPE_WINDOW = 2;
    
    /**
     * Include all of the loaded data in the stats.
     */
    public static final int STATS_LOADED = 0;
    
    /**
     * Include only the last run in the stats.
     */
    public static final int STATS_LAST = 1;
    
    /**
     * Include only the current run in the stats.
     */
    public static final int STATS_CURRENT = 2;
    
    static final int VERSION = 11;
    
    static IBatteryStats sService;
    
    final File mFile;
    final File mBackupFile;
    
    Context mContext;
    
    /**
     * The statistics we have collected organized by uids.
     */
    final SparseArray<Uid> uidStats = new SparseArray<Uid>();

    int mStartCount;
    
    long mBatteryUptime;
    long mBatteryUptimeStart;
    long mBatteryLastUptime;
    long mBatteryRealtime;
    long mBatteryRealtimeStart;
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
    boolean mOnBattery = true;
    long mTrackBatteryPastUptime = 0;
    long mTrackBatteryUptimeStart = 0;
    long mTrackBatteryPastRealtime = 0;
    long mTrackBatteryRealtimeStart = 0;
    
    /**
     * State for keeping track of timing information.
     */
    final static class Timer {
        long totalTime;
        long startTime;
        int nesting;
        int count;
        long loadedTotalTime;
        int loadedCount;
        long lastTotalTime;
        int lastCount;
        
        void startRunningLocked(BatteryStats stats) {
            if (nesting == 0) {
                nesting = 1;
                startTime = stats.getBatteryUptimeLocked();
                count++;
            } else {
                nesting++;
            }
        }
        
        void stopRunningLocked(BatteryStats stats) {
            if (nesting == 1) {
                nesting = 0;
                long heldTime = stats.getBatteryUptimeLocked() - startTime;
                if (heldTime != 0) {
                    totalTime += heldTime;
                } else {
                    count--;
                }
            } else {
                nesting--;
            }
        }
        
        long computeRunTimeLocked(long curTime) {
            return totalTime + (nesting > 0 ? (curTime-startTime) : 0);
        }
        
        void writeLocked(Parcel out, long curTime) throws java.io.IOException {
            long runTime = computeRunTimeLocked(curTime);
            out.writeLong(runTime);
            out.writeLong(runTime - loadedTotalTime);
            out.writeInt(count);
            out.writeInt(count - loadedCount);
        }
        
        void readLocked(Parcel in) throws java.io.IOException {
            totalTime = loadedTotalTime = in.readLong();
            lastTotalTime = in.readLong();
            count = loadedCount = in.readInt();
            lastCount = in.readInt();
            nesting = 0;
        }
    }
    
    /**
     * The statistics associated with a particular uid.
     */
    final class Uid {
        /**
         * The statics we have collected for this uid's wake locks.
         */
        final HashMap<String, Wakelock> wakelockStats = new HashMap<String, Wakelock>();
        
        /**
         * The statics we have collected for this uid's processes.
         */
        final HashMap<String, Proc> processStats = new HashMap<String, Proc>();
        
        /**
         * The statics we have collected for this uid's processes.
         */
        final HashMap<String, Pkg> packageStats = new HashMap<String, Pkg>();
        
        /**
         * The statistics associated with a particular wake lock.
         */
        final class Wakelock {
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
        }
        
        /**
         * The statistics associated with a particular process.
         */
        final class Proc {
            /**
             * Total time (in 1/100 sec) spent executing in user code.
             */
            long userTime;
            
            /**
             * Total time (in 1/100 sec) spent executing in kernel code.
             */
            long systemTime;
            
            /**
             * Number of times the process has been started.
             */
            int starts;
            
            /**
             * The amount of user time loaded from a previous save.
             */
            long loadedUserTime;
            
            /**
             * The amount of system time loaded from a previous save.
             */
            long loadedSystemTime;
            
            /**
             * The number of times the process has started from a previous save.
             */
            int loadedStarts;
            
            /**
             * The amount of user time loaded from the previous run.
             */
            long lastUserTime;
            
            /**
             * The amount of system time loaded from the previous run.
             */
            long lastSystemTime;
            
            /**
             * The number of times the process has started from the previous run.
             */
            int lastStarts;
            
            BatteryStats getBatteryStats() {
                return BatteryStats.this;
            }
        }
        
        /**
         * The statistics associated with a particular package.
         */
        final class Pkg {
            /**
             * Number of times this package has done something that could wake up the
             * device from sleep.
             */
            int wakeups;
            
            /**
             * Number of things that could wake up the device loaded from a
             * previous save.
             */
            int loadedWakeups;
            
            /**
             * Number of things that could wake up the device as of the
             * last run.
             */
            int lastWakeups;
            
            /**
             * The statics we have collected for this package's services.
             */
            final HashMap<String, Serv> serviceStats = new HashMap<String, Serv>();
            
            /**
             * The statistics associated with a particular service.
             */
            final class Serv {
                /**
                 * Total time (ms) the service has been left started.
                 */
                long startTime;
                
                /**
                 * If service has been started and not yet stopped, this is
                 * when it was started.
                 */
                long runningSince;
                
                /**
                 * True if we are currently running.
                 */
                boolean running;
                
                /**
                 * Total number of times startService() has been called.
                 */
                int starts;
                
                /**
                 * Total time (ms) the service has been left launched.
                 */
                long launchedTime;
                
                /**
                 * If service has been launched and not yet exited, this is
                 * when it was launched.
                 */
                long launchedSince;
                
                /**
                 * True if we are currently launched.
                 */
                boolean launched;
                
                /**
                 * Total number times the service has been launched.
                 */
                int launches;
                
                /**
                 * The amount of time spent started loaded from a previous save.
                 */
                long loadedStartTime;
                
                /**
                 * The number of starts loaded from a previous save.
                 */
                int loadedStarts;
                
                /**
                 * The number of launches loaded from a previous save.
                 */
                int loadedLaunches;
                
                /**
                 * The amount of time spent started as of the last run.
                 */
                long lastStartTime;
                
                /**
                 * The number of starts as of the last run.
                 */
                int lastStarts;
                
                /**
                 * The number of launches as of the last run.
                 */
                int lastLaunches;
                
                long getLaunchTimeToNowLocked(long now) {
                    if (!launched) return launchedTime;
                    return launchedTime + now - launchedSince;
                }
                
                long getStartTimeToNowLocked(long now) {
                    if (!running) return startTime;
                    return startTime + now - runningSince;
                }
                
                void startLaunchedLocked() {
                    if (!launched) {
                        launches++;
                        launchedSince = getBatteryUptimeLocked();
                        launched = true;
                    }
                }
                
                void stopLaunchedLocked() {
                    if (launched) {
                        long time = getBatteryUptimeLocked() - launchedSince;
                        if (time > 0) {
                            launchedTime += time;
                        } else {
                            launches--;
                        }
                        launched = false;
                    }
                }
                
                void startRunningLocked() {
                    if (!running) {
                        starts++;
                        runningSince = getBatteryUptimeLocked();
                        running = true;
                    }
                }
                
                void stopRunningLocked() {
                    if (running) {
                        long time = getBatteryUptimeLocked() - runningSince;
                        if (time > 0) {
                            startTime += time;
                        } else {
                            starts--;
                        }
                        running = false;
                    }
                }
                
                BatteryStats getBatteryStats() {
                    return BatteryStats.this;
                }
            }
            
            BatteryStats getBatteryStats() {
                return BatteryStats.this;
            }
            
            private final Serv newServiceStatsLocked() {
                return new Serv();
            }
        }
        
        /**
         * Retrieve the statistics object for a particular process, creating
         * if needed.
         */
        Proc getProcessStatsLocked(String name) {
            Proc ps = processStats.get(name);
            if (ps == null) {
                ps = new Proc();
                processStats.put(name, ps);
            }
            
            return ps;
        }
        
        /**
         * Retrieve the statistics object for a particular service, creating
         * if needed.
         */
        Pkg getPackageStatsLocked(String name) {
            Pkg ps = packageStats.get(name);
            if (ps == null) {
                ps = new Pkg();
                packageStats.put(name, ps);
            }
            
            return ps;
        }
        
        /**
         * Retrieve the statistics object for a particular service, creating
         * if needed.
         */
        Pkg.Serv getServiceStatsLocked(String pkg, String serv) {
            Pkg ps = getPackageStatsLocked(pkg);
            Pkg.Serv ss = ps.serviceStats.get(serv);
            if (ss == null) {
                ss = ps.newServiceStatsLocked();
                ps.serviceStats.put(serv, ss);
            }
            
            return ss;
        }
        
        Timer getWakeTimerLocked(String name, int type) {
            Wakelock wl = wakelockStats.get(name);
            if (wl == null) {
                wl = new Wakelock();
                wakelockStats.put(name, wl);
            }
            Timer t = null;
            switch (type) {
                case WAKE_TYPE_PARTIAL:
                    t = wl.wakeTimePartial;
                    if (t == null) {
                        t = new Timer();
                        wl.wakeTimePartial = t;
                    }
                    return t;
                case WAKE_TYPE_FULL:
                    t = wl.wakeTimeFull;
                    if (t == null) {
                        t = new Timer();
                        wl.wakeTimeFull = t;
                    }
                    return t;
                case WAKE_TYPE_WINDOW:
                    t = wl.wakeTimeWindow;
                    if (t == null) {
                        t = new Timer();
                        wl.wakeTimeWindow = t;
                    }
                    return t;
            }
            return t;
        }
        
        void noteStartWakeLocked(String name, int type) {
            Timer t = getWakeTimerLocked(name, type);
            if (t != null) {
                t.startRunningLocked(BatteryStats.this);
            }
        }
        
        void noteStopWakeLocked(String name, int type) {
            Timer t = getWakeTimerLocked(name, type);
            if (t != null) {
                t.stopRunningLocked(BatteryStats.this);
            }
        }
        
        BatteryStats getBatteryStats() {
            return BatteryStats.this;
        }
    }
    
    BatteryStats(String filename) {
        mFile = new File(filename);
        mBackupFile = new File(filename + ".bak");
        mStartCount++;
        mUptimeStart = SystemClock.uptimeMillis();
        mRealtimeStart = SystemClock.elapsedRealtime();
    }
    
    public void publish(Context context) {
        mContext = context;
        ServiceManager.addService("batteryinfo", asBinder());
    }
    
    public static IBatteryStats getService() {
        if (sService != null) {
            return sService;
        }
        IBinder b = ServiceManager.getService("batteryinfo");
        sService = asInterface(b);
        return sService;
    }
    
    public void noteStartWakelock(int uid, String name, int type) {
        enforceCallingPermission();
        synchronized(this) {
            getUidStatsLocked(uid).noteStartWakeLocked(name, type);
        }
    }

    public void noteStopWakelock(int uid, String name, int type) {
        enforceCallingPermission();
        synchronized(this) {
            getUidStatsLocked(uid).noteStopWakeLocked(name, type);
        }
    }

    public boolean isOnBattery() {
        return mOnBattery;
    }
    
    public void setOnBattery(boolean onBattery) {
        enforceCallingPermission();
        synchronized(this) {
            if (mOnBattery != onBattery) {
                if (onBattery) {
                    mTrackBatteryUptimeStart = SystemClock.uptimeMillis();
                    mTrackBatteryRealtimeStart = SystemClock.elapsedRealtime();
                } else {
                    mTrackBatteryPastUptime +=
                            SystemClock.uptimeMillis() - mTrackBatteryUptimeStart;
                    mTrackBatteryPastRealtime +=
                            SystemClock.elapsedRealtime() - mTrackBatteryRealtimeStart;
                }
                mOnBattery = onBattery;
            }
        }
    }
    
    public long getAwakeTimeBattery() {
        return computeBatteryUptime(getBatteryUptimeLocked(), STATS_CURRENT); 
    }
    
    public long getAwakeTimePlugged() {
        return SystemClock.uptimeMillis() - getAwakeTimeBattery();
    }
    
    long getBatteryUptimeLocked() {
        long time = mTrackBatteryPastUptime;
        if (mOnBattery) {
            time += SystemClock.uptimeMillis() - mTrackBatteryUptimeStart;
        }
        return time;
    }
    
    long getBatteryRealtimeLocked() {
        long time = mTrackBatteryPastRealtime;
        if (mOnBattery) {
            time += SystemClock.elapsedRealtime() - mTrackBatteryRealtimeStart;
        }
        return time;
    }
    
    long computeUptime(long curTime, int which) {
        switch (which) {
            case STATS_LOADED: return mUptime + (curTime-mUptimeStart);
            case STATS_LAST: return mLastUptime;
            case STATS_CURRENT: return (curTime-mUptimeStart);
        }
        return 0;
    }
    
    long computeRealtime(long curTime, int which) {
        switch (which) {
            case STATS_LOADED: return mRealtime + (curTime-mRealtimeStart);
            case STATS_LAST: return mLastRealtime;
            case STATS_CURRENT: return (curTime-mRealtimeStart);
        }
        return 0;
    }
    
    long computeBatteryUptime(long curTime, int which) {
        switch (which) {
            case STATS_LOADED: return mBatteryUptime + (curTime-mBatteryUptimeStart);
            case STATS_LAST: return mBatteryLastUptime;
            case STATS_CURRENT: return (curTime-mBatteryUptimeStart);
        }
        return 0;
    }
    
    long computeBatteryRealtime(long curTime, int which) {
        switch (which) {
            case STATS_LOADED: return mBatteryRealtime + (curTime-mBatteryRealtimeStart);
            case STATS_LAST: return mBatteryLastRealtime;
            case STATS_CURRENT: return (curTime-mBatteryRealtimeStart);
        }
        return 0;
    }
    
    public void enforceCallingPermission() {
        if (Binder.getCallingPid() == Process.myPid()) {
            return;
        }
        mContext.enforcePermission(android.Manifest.permission.BATTERY_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), null);
    }
    
    /**
     * Retrieve the statistics object for a particular uid, creating if needed.
     */
    Uid getUidStatsLocked(int uid) {
        Uid u = uidStats.get(uid);
        if (u == null) {
            u = new Uid();
            uidStats.put(uid, u);
        }
        return u;
    }
    
    /**
     * Retrieve the statistics object for a particular process, creating
     * if needed.
     */
    Uid.Proc getProcessStatsLocked(int uid, String name) {
        Uid u = getUidStatsLocked(uid);
        return u.getProcessStatsLocked(name);
    }
    
    /**
     * Retrieve the statistics object for a particular process, creating
     * if needed.
     */
    Uid.Pkg getPackageStatsLocked(int uid, String pkg) {
        Uid u = getUidStatsLocked(uid);
        return u.getPackageStatsLocked(pkg);
    }
    
    /**
     * Retrieve the statistics object for a particular service, creating
     * if needed.
     */
    Uid.Pkg.Serv getServiceStatsLocked(int uid, String pkg, String name) {
        Uid u = getUidStatsLocked(uid);
        return u.getServiceStatsLocked(pkg, name);
    }
    
    private final static String formatTime(long time) {
        long sec = time/100;
        StringBuilder sb = new StringBuilder();
        sb.append(sec);
        if (time != 0) {
            sb.append('.');
            sb.append((char)(((time/10)%10)+'0'));
            sb.append((char)((time%10)+'0'));
        }
        sb.append(" sec");
        return sb.toString();
    }
    
    private final static String formatTimeMs(long time) {
        long sec = time/1000;
        StringBuilder sb = new StringBuilder();
        sb.append(sec);
        if (time != 0) {
            sb.append('.');
            sb.append((char)(((time/100)%10)+'0'));
            sb.append((char)(((time/10)%10)+'0'));
            sb.append((char)((time%10)+'0'));
        }
        sb.append(" sec");
        return sb.toString();
    }
    
    final String printWakeLock(StringBuilder sb, Timer timer, long now,
            String name, int which, String linePrefix) {
        if (timer != null) {
            long totalTime;
            int count;
            if (which == STATS_LAST) {
                totalTime = timer.lastTotalTime;
                count = timer.lastCount;
            } else {
                totalTime = timer.computeRunTimeLocked(now);
                count = timer.count;
                if (which == STATS_CURRENT) {
                    totalTime -= timer.loadedTotalTime;
                    count -= timer.loadedCount;
                }
            }
            if (totalTime != 0) {
                sb.append(linePrefix);
                sb.append(formatTimeMs(totalTime));
                sb.append(' ');
                sb.append(name);
                sb.append(' ');
                sb.append('(');
                sb.append(count);
                sb.append(" times)");
                return ", ";
            }
        }
        return linePrefix;
    }
    
    final void dumpLocked(FileDescriptor fd, PrintWriter pw, String prefix,
            int which) {
        final long NOW = getBatteryUptimeLocked();
        
        StringBuilder sb = new StringBuilder();
        if (which == STATS_LOADED) {
            pw.println(prefix + "Current and Historic Battery Usage Statistics:");
            pw.println(prefix + "  System starts: " + mStartCount);
        } else if (which == STATS_LAST) {
            pw.println(prefix + "Last Battery Usage Statistics:");
        } else {
            pw.println(prefix + "Current Battery Usage Statistics:");
        }
        pw.println(prefix
                + "  On battery: "
                + formatTimeMs(computeBatteryUptime(NOW, which))
                + " uptime, "
                + formatTimeMs(computeBatteryRealtime(getBatteryRealtimeLocked(),
                        which))
                + " realtime");
        pw.println(prefix
                + "  Total: "
                + formatTimeMs(computeUptime(SystemClock.uptimeMillis(), which))
                + " uptime, "
                + formatTimeMs(computeRealtime(SystemClock.elapsedRealtime(),
                        which))
                + " realtime");
        
        pw.println(" ");
        final int NU = uidStats.size();
        for (int iu=0; iu<NU; iu++) {
            final int uid = uidStats.keyAt(iu);
            Uid u = uidStats.valueAt(iu);
            pw.println(prefix + "  #" + uid + ":");
            boolean uidActivity = false;
            if (u.wakelockStats.size() > 0) {
                for (Map.Entry<String, BatteryStats.Uid.Wakelock> ent
                        : u.wakelockStats.entrySet()) {
                    Uid.Wakelock wl = ent.getValue();
                    String linePrefix = ": ";
                    sb.setLength(0);
                    sb.append(prefix);
                    sb.append("    Wake lock ");
                    sb.append(ent.getKey());
                    linePrefix = printWakeLock(sb, wl.wakeTimeFull, NOW,
                            "full", which, linePrefix);
                    linePrefix = printWakeLock(sb, wl.wakeTimePartial, NOW,
                            "partial", which, linePrefix);
                    linePrefix = printWakeLock(sb, wl.wakeTimeWindow, NOW,
                            "window", which, linePrefix);
                    if (linePrefix.equals(": ")) {
                        sb.append(": (nothing executed)");
                    }
                    pw.println(sb.toString());
                    uidActivity = true;
                }
            }
            if (u.processStats.size() > 0) {
                for (Map.Entry<String, BatteryStats.Uid.Proc> ent
                        : u.processStats.entrySet()) {
                    BatteryStats.Uid.Proc ps = ent.getValue();
                    long userTime;
                    long systemTime;
                    int starts;
                    if (which == STATS_LAST) {
                        userTime = ps.lastUserTime;
                        systemTime = ps.lastSystemTime;
                        starts = ps.lastStarts;
                    } else {
                        userTime = ps.userTime;
                        systemTime = ps.systemTime;
                        starts = ps.starts;
                        if (which == STATS_CURRENT) {
                            userTime -= ps.loadedUserTime;
                            systemTime -= ps.loadedSystemTime;
                            starts -= ps.loadedStarts;
                        }
                    }
                    if (userTime != 0 || systemTime != 0 || starts != 0) {
                        pw.println(prefix + "    Proc " + ent.getKey() + ":");
                        pw.println(prefix + "      CPU: " + formatTime(userTime) + " user + "
                                + formatTime(systemTime) + " kernel");
                        pw.println(prefix + "      " + starts + " process starts");
                        uidActivity = true;
                    }
                }
            }
            if (u.packageStats.size() > 0) {
                for (Map.Entry<String, BatteryStats.Uid.Pkg> ent
                        : u.packageStats.entrySet()) {
                    pw.println(prefix + "    Apk " + ent.getKey() + ":");
                    boolean apkActivity = false;
                    BatteryStats.Uid.Pkg ps = ent.getValue();
                    int wakeups;
                    if (which == STATS_LAST) {
                        wakeups = ps.lastWakeups;
                    } else {
                        wakeups = ps.wakeups;
                        if (which == STATS_CURRENT) {
                            wakeups -= ps.loadedWakeups;
                        }
                    }
                    if (wakeups != 0) {
                        pw.println(prefix + "      " + wakeups + " wakeup alarms");
                        apkActivity = true;
                    }
                    if (ps.serviceStats.size() > 0) {
                        for (Map.Entry<String, BatteryStats.Uid.Pkg.Serv> sent
                                : ps.serviceStats.entrySet()) {
                            BatteryStats.Uid.Pkg.Serv ss = sent.getValue();
                            long time;
                            int starts;
                            int launches;
                            if (which == STATS_LAST) {
                                time = ss.lastStartTime;
                                starts = ss.lastStarts;
                                launches = ss.lastLaunches;
                            } else {
                                time = ss.getStartTimeToNowLocked(NOW);
                                starts = ss.starts;
                                launches = ss.launches;
                                if (which == STATS_CURRENT) {
                                    time -= ss.loadedStartTime;
                                    starts -= ss.loadedStarts;
                                    launches -= ss.loadedLaunches;
                                }
                            }
                            if (time != 0 || starts != 0 || launches != 0) {
                                pw.println(prefix + "      Service " + sent.getKey() + ":");
                                pw.println(prefix + "        Time spent started: "
                                        + formatTimeMs(time));
                                pw.println(prefix + "        Starts: " + starts
                                        + ", launches: " + launches);
                                apkActivity = true;
                            }
                        }
                    }
                    if (!apkActivity) {
                        pw.println(prefix + "      (nothing executed)");
                    }
                    uidActivity = true;
                }
            }
            if (!uidActivity) {
                pw.println(prefix + "    (nothing executed)");
            }
        }
    }
    
    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (this) {
            dumpLocked(fd, pw, "", STATS_LOADED);
            pw.println("");
            dumpLocked(fd, pw, "", STATS_LAST);
            pw.println("");
            dumpLocked(fd, pw, "", STATS_CURRENT);
        }
    }
    
    void writeLocked() {
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
            
            final long NOW = getBatteryUptimeLocked();
            final long NOWREAL = getBatteryRealtimeLocked();
            final long NOW_SYS = SystemClock.uptimeMillis();
            final long NOWREAL_SYS = SystemClock.elapsedRealtime();
            
            Parcel out = Parcel.obtain();
            out.writeInt(VERSION);
            out.writeInt(mStartCount);
            out.writeLong(computeBatteryUptime(NOW, STATS_LOADED));
            out.writeLong(computeBatteryUptime(NOW, STATS_CURRENT));
            out.writeLong(computeBatteryRealtime(NOWREAL, STATS_LOADED));
            out.writeLong(computeBatteryRealtime(NOWREAL, STATS_CURRENT));
            out.writeLong(computeUptime(NOW_SYS, STATS_LOADED));
            out.writeLong(computeUptime(NOW_SYS, STATS_CURRENT));
            out.writeLong(computeRealtime(NOWREAL_SYS, STATS_LOADED));
            out.writeLong(computeRealtime(NOWREAL_SYS, STATS_CURRENT));
            
            final int NU = uidStats.size();
            out.writeInt(NU);
            for (int iu=0; iu<NU; iu++) {
                out.writeInt(uidStats.keyAt(iu));
                Uid u = uidStats.valueAt(iu);
                int NW = u.wakelockStats.size();
                out.writeInt(NW);
                if (NW > 0) {
                    for (Map.Entry<String, BatteryStats.Uid.Wakelock> ent
                            : u.wakelockStats.entrySet()) {
                        out.writeString(ent.getKey());
                        Uid.Wakelock wl = ent.getValue();
                        if (wl.wakeTimeFull != null) {
                            out.writeInt(1);
                            wl.wakeTimeFull.writeLocked(out, NOW);
                        } else {
                            out.writeInt(0);
                        }
                        if (wl.wakeTimePartial != null) {
                            out.writeInt(1);
                            wl.wakeTimePartial.writeLocked(out, NOW);
                        } else {
                            out.writeInt(0);
                        }
                        if (wl.wakeTimeWindow != null) {
                            out.writeInt(1);
                            wl.wakeTimeWindow.writeLocked(out, NOW);
                        } else {
                            out.writeInt(0);
                        }
                    }
                }
                
                int NP = u.processStats.size();
                out.writeInt(NP);
                if (NP > 0) {
                    for (Map.Entry<String, BatteryStats.Uid.Proc> ent
                            : u.processStats.entrySet()) {
                        out.writeString(ent.getKey());
                        BatteryStats.Uid.Proc ps = ent.getValue();
                        out.writeLong(ps.userTime);
                        out.writeLong(ps.userTime - ps.loadedUserTime);
                        out.writeLong(ps.systemTime);
                        out.writeLong(ps.systemTime - ps.loadedSystemTime);
                        out.writeInt(ps.starts);
                        out.writeInt(ps.starts - ps.loadedStarts);
                    }
                }
                
                NP = u.packageStats.size();
                out.writeInt(NP);
                if (NP > 0) {
                    for (Map.Entry<String, BatteryStats.Uid.Pkg> ent
                            : u.packageStats.entrySet()) {
                        out.writeString(ent.getKey());
                        BatteryStats.Uid.Pkg ps = ent.getValue();
                        out.writeInt(ps.wakeups);
                        out.writeInt(ps.wakeups - ps.loadedWakeups);
                        final int NS = ps.serviceStats.size();
                        out.writeInt(NS);
                        if (NS > 0) {
                            for (Map.Entry<String, BatteryStats.Uid.Pkg.Serv> sent
                                    : ps.serviceStats.entrySet()) {
                                out.writeString(sent.getKey());
                                BatteryStats.Uid.Pkg.Serv ss = sent.getValue();
                                long time = ss.getStartTimeToNowLocked(NOW);
                                out.writeLong(time);
                                out.writeLong(time - ss.loadedStartTime);
                                out.writeInt(ss.starts);
                                out.writeInt(ss.starts - ss.loadedStarts);
                                out.writeInt(ss.launches);
                                out.writeInt(ss.launches - ss.loadedLaunches);
                            }
                        }
                    }
                }
            }
            
            stream.write(out.marshall());
            out.recycle();
            
            stream.flush();
            stream.close();
            mBackupFile.delete();
            
        } catch(java.io.IOException e) {
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
    
    void readLocked() {
        uidStats.clear();
        
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
            
            final int version = in.readInt();
            //Log.i("foo", "Read version: got " + version + ", expecting " + VERSION);
            if (version != VERSION) {
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
            //Log.i("foo", "Start count: " + mStartCount);
            mStartCount++;
            
            final int NU = in.readInt();
            //Log.i("foo", "Number uids: " + NU);
            for (int iu=0; iu<NU; iu++) {
                int uid = in.readInt();
                //Log.i("foo", "Uid #" + iu + ": " + uid);
                Uid u = new Uid();
                uidStats.put(uid, u);
                int NW = in.readInt();
                for (int iw=0; iw<NW; iw++) {
                    String wlName = in.readString();
                    if (in.readInt() != 0) {
                        u.getWakeTimerLocked(wlName, WAKE_TYPE_FULL).readLocked(in);
                    }
                    if (in.readInt() != 0) {
                        u.getWakeTimerLocked(wlName, WAKE_TYPE_PARTIAL).readLocked(in);
                    }
                    if (in.readInt() != 0) {
                        u.getWakeTimerLocked(wlName, WAKE_TYPE_WINDOW).readLocked(in);
                    }
                }
                
                int NP = in.readInt();
                for (int ip=0; ip<NP; ip++) {
                    String procName = in.readString();
                    Uid.Proc p = u.getProcessStatsLocked(procName);
                    p.userTime = p.loadedUserTime = in.readLong();
                    p.lastUserTime = in.readLong();
                    p.systemTime = p.loadedSystemTime = in.readLong();
                    p.lastSystemTime = in.readLong();
                    p.starts = p.loadedStarts = in.readInt();
                    p.lastStarts = in.readInt();
                }
                
                NP = in.readInt();
                for (int ip=0; ip<NP; ip++) {
                    String pkgName = in.readString();
                    Uid.Pkg p = u.getPackageStatsLocked(pkgName);
                    p.wakeups = p.loadedWakeups = in.readInt();
                    p.lastWakeups = in.readInt();
                    final int NS = in.readInt();
                    for (int is=0; is<NS; is++) {
                        String servName = in.readString();
                        Uid.Pkg.Serv s = u.getServiceStatsLocked(pkgName, servName);
                        s.startTime = s.loadedStartTime = in.readLong();
                        s.lastStartTime = in.readLong();
                        s.starts = s.loadedStarts = in.readInt();
                        s.lastStarts = in.readInt();
                        s.launches = s.loadedLaunches = in.readInt();
                        s.lastLaunches = in.readInt();
                    }
                }
            }
            
        } catch(java.io.IOException e) {
            Log.e("BatteryStats", "Error reading battery statistics", e);
        }
    }
}
