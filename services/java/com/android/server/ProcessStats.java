/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.server;

import static android.os.Process.*;

import android.os.Process;
import android.os.SystemClock;
import android.util.Config;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.StringTokenizer;

public class ProcessStats {
    private static final String TAG = "ProcessStats";
    private static final boolean DEBUG = false;
    private static final boolean localLOGV = DEBUG || Config.LOGV;
    
    private static final int[] PROCESS_STATS_FORMAT = new int[] {
        PROC_SPACE_TERM,
        PROC_SPACE_TERM,
        PROC_SPACE_TERM,
        PROC_SPACE_TERM,
        PROC_SPACE_TERM,
        PROC_SPACE_TERM,
        PROC_SPACE_TERM,
        PROC_SPACE_TERM,
        PROC_SPACE_TERM,
        PROC_SPACE_TERM|PROC_OUT_LONG,                  // 9: minor faults
        PROC_SPACE_TERM,
        PROC_SPACE_TERM|PROC_OUT_LONG,                  // 11: major faults
        PROC_SPACE_TERM,
        PROC_SPACE_TERM|PROC_OUT_LONG,                  // 13: utime
        PROC_SPACE_TERM|PROC_OUT_LONG                   // 14: stime
    };

    static final int PROCESS_STAT_MINOR_FAULTS = 0;
    static final int PROCESS_STAT_MAJOR_FAULTS = 1;
    static final int PROCESS_STAT_UTIME = 2;
    static final int PROCESS_STAT_STIME = 3;
    
    /** Stores user time and system time in 100ths of a second. */
    private final long[] mProcessStatsData = new long[4];
    /** Stores user time and system time in 100ths of a second. */
    private final long[] mSinglePidStatsData = new long[4];

    private static final int[] PROCESS_FULL_STATS_FORMAT = new int[] {
        PROC_SPACE_TERM,
        PROC_SPACE_TERM|PROC_PARENS|PROC_OUT_STRING,    // 1: name
        PROC_SPACE_TERM,
        PROC_SPACE_TERM,
        PROC_SPACE_TERM,
        PROC_SPACE_TERM,
        PROC_SPACE_TERM,
        PROC_SPACE_TERM,
        PROC_SPACE_TERM,
        PROC_SPACE_TERM,
        PROC_SPACE_TERM,
        PROC_SPACE_TERM,
        PROC_SPACE_TERM,
        PROC_SPACE_TERM|PROC_OUT_LONG,                  // 13: utime
        PROC_SPACE_TERM|PROC_OUT_LONG                   // 14: stime
    };

    private final String[] mProcessFullStatsStringData = new String[3];
    private final long[] mProcessFullStatsData = new long[3];

    private static final int[] SYSTEM_CPU_FORMAT = new int[] {
        PROC_SPACE_TERM|PROC_COMBINE,
        PROC_SPACE_TERM|PROC_OUT_LONG,                  // 1: user time
        PROC_SPACE_TERM|PROC_OUT_LONG,                  // 2: nice time
        PROC_SPACE_TERM|PROC_OUT_LONG,                  // 3: sys time
        PROC_SPACE_TERM|PROC_OUT_LONG,                  // 4: idle time
        PROC_SPACE_TERM|PROC_OUT_LONG,                  // 5: iowait time
        PROC_SPACE_TERM|PROC_OUT_LONG,                  // 6: irq time
        PROC_SPACE_TERM|PROC_OUT_LONG                   // 7: softirq time
    };

    private final long[] mSystemCpuData = new long[7];

    private static final int[] LOAD_AVERAGE_FORMAT = new int[] {
        PROC_SPACE_TERM|PROC_OUT_FLOAT,                 // 0: 1 min
        PROC_SPACE_TERM|PROC_OUT_FLOAT,                 // 1: 5 mins
        PROC_SPACE_TERM|PROC_OUT_FLOAT                  // 2: 15 mins
    };

    private final float[] mLoadAverageData = new float[3];

    private final boolean mIncludeThreads;
    
    private float mLoad1 = 0;
    private float mLoad5 = 0;
    private float mLoad15 = 0;
    
    private long mCurrentSampleTime;
    private long mLastSampleTime;
    
    private long mBaseUserTime;
    private long mBaseSystemTime;
    private long mBaseIoWaitTime;
    private long mBaseIrqTime;
    private long mBaseSoftIrqTime;
    private long mBaseIdleTime;
    private int mRelUserTime;
    private int mRelSystemTime;
    private int mRelIoWaitTime;
    private int mRelIrqTime;
    private int mRelSoftIrqTime;
    private int mRelIdleTime;

    private int[] mCurPids;
    private int[] mCurThreadPids;
    
    private final ArrayList<Stats> mProcStats = new ArrayList<Stats>();
    private final ArrayList<Stats> mWorkingProcs = new ArrayList<Stats>();
    private boolean mWorkingProcsSorted;

    private boolean mFirst = true;

    private byte[] mBuffer = new byte[256];

    /**
     * The time in microseconds that the CPU has been running at each speed.
     */
    private long[] mCpuSpeedTimes;

    /**
     * The relative time in microseconds that the CPU has been running at each speed.
     */
    private long[] mRelCpuSpeedTimes;

    /**
     * The different speeds that the CPU can be running at.
     */
    private long[] mCpuSpeeds;

    public static class Stats {
        public final int pid;
        final String statFile;
        final String cmdlineFile;
        final String threadsDir;
        final ArrayList<Stats> threadStats;
        final ArrayList<Stats> workingThreads;
        
        public String baseName;
        public String name;
        int nameWidth;

        public long base_utime;
        public long base_stime;
        public int rel_utime;
        public int rel_stime;

        public long base_minfaults;
        public long base_majfaults;
        public int rel_minfaults;
        public int rel_majfaults;
        
        public boolean active;
        public boolean added;
        public boolean removed;
        
        Stats(int _pid, int parentPid, boolean includeThreads) {
            pid = _pid;
            if (parentPid < 0) {
                final File procDir = new File("/proc", Integer.toString(pid));
                statFile = new File(procDir, "stat").toString();
                cmdlineFile = new File(procDir, "cmdline").toString();
                threadsDir = (new File(procDir, "task")).toString();
                if (includeThreads) {
                    threadStats = new ArrayList<Stats>();
                    workingThreads = new ArrayList<Stats>();
                } else {
                    threadStats = null;
                    workingThreads = null;
                }
            } else {
                final File procDir = new File("/proc", Integer.toString(
                        parentPid));
                final File taskDir = new File(
                        new File(procDir, "task"), Integer.toString(pid));
                statFile = new File(taskDir, "stat").toString();
                cmdlineFile = null;
                threadsDir = null;
                threadStats = null;
                workingThreads = null;
            }
        }
    }

    private final static Comparator<Stats> sLoadComparator = new Comparator<Stats>() {
        public final int
        compare(Stats sta, Stats stb)
        {
            int ta = sta.rel_utime + sta.rel_stime;
            int tb = stb.rel_utime + stb.rel_stime;
            if (ta != tb) {
                return ta > tb ? -1 : 1;
            }
            if (sta.added != stb.added) {
                return sta.added ? -1 : 1;
            }
            if (sta.removed != stb.removed) {
                return sta.added ? -1 : 1;
            }
            return 0;
        }
    };


    public ProcessStats(boolean includeThreads) {
        mIncludeThreads = includeThreads;
    }
    
    public void onLoadChanged(float load1, float load5, float load15) {
    }
    
    public int onMeasureProcessName(String name) {
        return 0;
    }
    
    public void init() {
        mFirst = true;
        update();
    }
    
    public void update() {
        mLastSampleTime = mCurrentSampleTime;
        mCurrentSampleTime = SystemClock.uptimeMillis();
        
        final float[] loadAverages = mLoadAverageData;
        if (Process.readProcFile("/proc/loadavg", LOAD_AVERAGE_FORMAT,
                null, null, loadAverages)) {
            float load1 = loadAverages[0];
            float load5 = loadAverages[1];
            float load15 = loadAverages[2];
            if (load1 != mLoad1 || load5 != mLoad5 || load15 != mLoad15) {
                mLoad1 = load1;
                mLoad5 = load5;
                mLoad15 = load15;
                onLoadChanged(load1, load5, load15);
            }
        }

        mCurPids = collectStats("/proc", -1, mFirst, mCurPids,
                mProcStats, mWorkingProcs);
        mFirst = false;
        
        final long[] sysCpu = mSystemCpuData;
        if (Process.readProcFile("/proc/stat", SYSTEM_CPU_FORMAT,
                null, sysCpu, null)) {
            // Total user time is user + nice time.
            final long usertime = sysCpu[0]+sysCpu[1];
            // Total system time is simply system time.
            final long systemtime = sysCpu[2];
            // Total idle time is simply idle time.
            final long idletime = sysCpu[3];
            // Total irq time is iowait + irq + softirq time.
            final long iowaittime = sysCpu[4];
            final long irqtime = sysCpu[5];
            final long softirqtime = sysCpu[6];

            mRelUserTime = (int)(usertime - mBaseUserTime);
            mRelSystemTime = (int)(systemtime - mBaseSystemTime);
            mRelIoWaitTime = (int)(iowaittime - mBaseIoWaitTime);
            mRelIrqTime = (int)(irqtime - mBaseIrqTime);
            mRelSoftIrqTime = (int)(softirqtime - mBaseSoftIrqTime);
            mRelIdleTime = (int)(idletime - mBaseIdleTime);

            if (false) {
                Log.i("Load", "Total U:" + sysCpu[0] + " N:" + sysCpu[1]
                      + " S:" + sysCpu[2] + " I:" + sysCpu[3]
                      + " W:" + sysCpu[4] + " Q:" + sysCpu[5]
                      + " O:" + sysCpu[6]);
                Log.i("Load", "Rel U:" + mRelUserTime + " S:" + mRelSystemTime
                      + " I:" + mRelIdleTime + " Q:" + mRelIrqTime);
            }

            mBaseUserTime = usertime;
            mBaseSystemTime = systemtime;
            mBaseIoWaitTime = iowaittime;
            mBaseIrqTime = irqtime;
            mBaseSoftIrqTime = softirqtime;
            mBaseIdleTime = idletime;
        }

        mWorkingProcsSorted = false;
        mFirst = false;
    }    
    
    private int[] collectStats(String statsFile, int parentPid, boolean first,
            int[] curPids, ArrayList<Stats> allProcs,
            ArrayList<Stats> workingProcs) {
        
        workingProcs.clear();

        int[] pids = Process.getPids(statsFile, curPids);
        int NP = (pids == null) ? 0 : pids.length;
        int NS = allProcs.size();
        int curStatsIndex = 0;
        for (int i=0; i<NP; i++) {
            int pid = pids[i];
            if (pid < 0) {
                NP = pid;
                break;
            }
            Stats st = curStatsIndex < NS ? allProcs.get(curStatsIndex) : null;
            
            if (st != null && st.pid == pid) {
                // Update an existing process...
                st.added = false;
                curStatsIndex++;
                if (localLOGV) Log.v(TAG, "Existing pid " + pid + ": " + st);

                final long[] procStats = mProcessStatsData;
                if (!Process.readProcFile(st.statFile.toString(),
                        PROCESS_STATS_FORMAT, null, procStats, null)) {
                    continue;
                }
                
                final long minfaults = procStats[PROCESS_STAT_MINOR_FAULTS];
                final long majfaults = procStats[PROCESS_STAT_MAJOR_FAULTS];
                final long utime = procStats[PROCESS_STAT_UTIME];
                final long stime = procStats[PROCESS_STAT_STIME];

                if (utime == st.base_utime && stime == st.base_stime) {
                    st.rel_utime = 0;
                    st.rel_stime = 0;
                    st.rel_minfaults = 0;
                    st.rel_majfaults = 0;
                    if (st.active) {
                        st.active = false;
                    }
                    continue;
                }
                    
                if (!st.active) {
                    st.active = true;
                }

                if (parentPid < 0) {
                    getName(st, st.cmdlineFile);
                    if (st.threadStats != null) {
                        mCurThreadPids = collectStats(st.threadsDir, pid, false,
                                mCurThreadPids, st.threadStats,
                                st.workingThreads);
                    }
                }

                st.rel_utime = (int)(utime - st.base_utime);
                st.rel_stime = (int)(stime - st.base_stime);
                st.base_utime = utime;
                st.base_stime = stime;
                st.rel_minfaults = (int)(minfaults - st.base_minfaults);
                st.rel_majfaults = (int)(majfaults - st.base_majfaults);
                st.base_minfaults = minfaults;
                st.base_majfaults = majfaults;
                //Log.i("Load", "Stats changed " + name + " pid=" + st.pid
                //      + " name=" + st.name + " utime=" + utime
                //      + " stime=" + stime);
                workingProcs.add(st);
                continue;
            }
            
            if (st == null || st.pid > pid) {
                // We have a new process!
                st = new Stats(pid, parentPid, mIncludeThreads);
                allProcs.add(curStatsIndex, st);
                curStatsIndex++;
                NS++;
                if (localLOGV) Log.v(TAG, "New pid " + pid + ": " + st);

                final String[] procStatsString = mProcessFullStatsStringData;
                final long[] procStats = mProcessFullStatsData;
                if (Process.readProcFile(st.statFile.toString(),
                        PROCESS_FULL_STATS_FORMAT, procStatsString,
                        procStats, null)) {
                    st.baseName = parentPid < 0
                            ? procStatsString[0] : Integer.toString(pid);
                    st.base_utime = 0; //procStats[1];
                    st.base_stime = 0; //procStats[2];
                    st.base_minfaults = st.base_majfaults = 0;
                } else {
                    st.baseName = "<unknown>";
                    st.base_utime = st.base_stime = 0;
                    st.base_minfaults = st.base_majfaults = 0;
                }

                if (parentPid < 0) {
                    getName(st, st.cmdlineFile);
                } else {
                    st.name = st.baseName;
                    st.nameWidth = onMeasureProcessName(st.name);
                    if (st.threadStats != null) {
                        mCurThreadPids = collectStats(st.threadsDir, pid, true,
                                mCurThreadPids, st.threadStats,
                                st.workingThreads);
                    }
                }
                
                //Log.i("Load", "New process: " + st.pid + " " + st.name);
                st.rel_utime = 0;
                st.rel_stime = 0;
                st.rel_minfaults = 0;
                st.rel_majfaults = 0;
                st.added = true;
                if (!first) {
                    workingProcs.add(st);
                }
                continue;
            }
                
            // This process has gone away!
            st.rel_utime = 0;
            st.rel_stime = 0;
            st.rel_minfaults = 0;
            st.rel_majfaults = 0;
            st.removed = true;
            workingProcs.add(st);
            allProcs.remove(curStatsIndex);
            NS--;
            if (localLOGV) Log.v(TAG, "Removed pid " + st.pid + ": " + st);
            // Decrement the loop counter so that we process the current pid
            // again the next time through the loop.
            i--;
            continue;
        }

        while (curStatsIndex < NS) {
            // This process has gone away!
            final Stats st = allProcs.get(curStatsIndex);
            st.rel_utime = 0;
            st.rel_stime = 0;
            st.rel_minfaults = 0;
            st.rel_majfaults = 0;
            st.removed = true;
            workingProcs.add(st);
            allProcs.remove(curStatsIndex);
            NS--;
            if (localLOGV) Log.v(TAG, "Removed pid " + st.pid + ": " + st);
        }
        
        return pids;
    }

    public long getCpuTimeForPid(int pid) {
        final String statFile = "/proc/" + pid + "/stat";
        final long[] statsData = mSinglePidStatsData;
        if (Process.readProcFile(statFile, PROCESS_STATS_FORMAT,
                null, statsData, null)) {
            long time = statsData[PROCESS_STAT_UTIME]
                    + statsData[PROCESS_STAT_STIME];
            return time;
        }
        return 0;
    }

    /**
     * Returns the times spent at each CPU speed, since the last call to this method. If this
     * is the first time, it will return 1 for each value.
     * @return relative times spent at different speed steps.
     */
    public long[] getLastCpuSpeedTimes() {
        if (mCpuSpeedTimes == null) {
            mCpuSpeedTimes = getCpuSpeedTimes(null);
            mRelCpuSpeedTimes = new long[mCpuSpeedTimes.length];
            for (int i = 0; i < mCpuSpeedTimes.length; i++) {
                mRelCpuSpeedTimes[i] = 1; // Initialize
            }
        } else {
            getCpuSpeedTimes(mRelCpuSpeedTimes);
            for (int i = 0; i < mCpuSpeedTimes.length; i++) {
                long temp = mRelCpuSpeedTimes[i];
                mRelCpuSpeedTimes[i] -= mCpuSpeedTimes[i];
                mCpuSpeedTimes[i] = temp;
            }
        }
        return mRelCpuSpeedTimes;
    }

    private long[] getCpuSpeedTimes(long[] out) {
        long[] tempTimes = out;
        long[] tempSpeeds = mCpuSpeeds;
        final int MAX_SPEEDS = 20;
        if (out == null) {
            tempTimes = new long[MAX_SPEEDS]; // Hopefully no more than that
            tempSpeeds = new long[MAX_SPEEDS];
        }
        int speed = 0;
        String file = readFile("/sys/devices/system/cpu/cpu0/cpufreq/stats/time_in_state", '\0');
        // Note: file may be null on kernels without cpufreq (i.e. the emulator's)
        if (file != null) {
            StringTokenizer st = new StringTokenizer(file, "\n ");
            while (st.hasMoreElements()) {
                String token = st.nextToken();
                try {
                    long val = Long.parseLong(token);
                    tempSpeeds[speed] = val;
                    token = st.nextToken();
                    val = Long.parseLong(token);
                    tempTimes[speed] = val;
                    speed++;
                    if (speed == MAX_SPEEDS) break; // No more
                    if (localLOGV && out == null) {
                        Log.v(TAG, "First time : Speed/Time = " + tempSpeeds[speed - 1]
                              + "\t" + tempTimes[speed - 1]);
                    }
                } catch (NumberFormatException nfe) {
                    Log.i(TAG, "Unable to parse time_in_state");
                }
            }
        }
        if (out == null) {
            out = new long[speed];
            mCpuSpeeds = new long[speed];
            System.arraycopy(tempSpeeds, 0, mCpuSpeeds, 0, speed);
            System.arraycopy(tempTimes, 0, out, 0, speed);
        }
        return out;
    }

    final public int getLastUserTime() {
        return mRelUserTime;
    }
    
    final public int getLastSystemTime() {
        return mRelSystemTime;
    }
    
    final public int getLastIoWaitTime() {
        return mRelIoWaitTime;
    }
    
    final public int getLastIrqTime() {
        return mRelIrqTime;
    }
    
    final public int getLastSoftIrqTime() {
        return mRelSoftIrqTime;
    }
    
    final public int getLastIdleTime() {
        return mRelIdleTime;
    }
    
    final public float getTotalCpuPercent() {
        return ((float)(mRelUserTime+mRelSystemTime+mRelIrqTime)*100)
                / (mRelUserTime+mRelSystemTime+mRelIrqTime+mRelIdleTime);
    }
    
    final public int countWorkingStats() {
        if (!mWorkingProcsSorted) {
            Collections.sort(mWorkingProcs, sLoadComparator);
            mWorkingProcsSorted = true;
        }
        return mWorkingProcs.size();
    }

    final public Stats getWorkingStats(int index) {
        return mWorkingProcs.get(index);
    }
    
    final public String printCurrentState() {
        if (!mWorkingProcsSorted) {
            Collections.sort(mWorkingProcs, sLoadComparator);
            mWorkingProcsSorted = true;
        }
        
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.print("Load: ");
        pw.print(mLoad1);
        pw.print(" / ");
        pw.print(mLoad5);
        pw.print(" / ");
        pw.println(mLoad15);
        
        long now = SystemClock.uptimeMillis();
        
        pw.print("CPU usage from ");
        pw.print(now-mLastSampleTime);
        pw.print("ms to ");
        pw.print(now-mCurrentSampleTime);
        pw.println("ms ago:");
        
        final int totalTime = mRelUserTime + mRelSystemTime + mRelIoWaitTime
                + mRelIrqTime + mRelSoftIrqTime + mRelIdleTime;
        
        int N = mWorkingProcs.size();
        for (int i=0; i<N; i++) {
            Stats st = mWorkingProcs.get(i);
            printProcessCPU(pw, st.added ? " +" : (st.removed ? " -": "  "),
                    st.name, totalTime, st.rel_utime, st.rel_stime, 0, 0, 0,
                    st.rel_minfaults, st.rel_majfaults);
            if (!st.removed && st.workingThreads != null) {
                int M = st.workingThreads.size();
                for (int j=0; j<M; j++) {
                    Stats tst = st.workingThreads.get(j);
                    printProcessCPU(pw,
                            tst.added ? "   +" : (tst.removed ? "   -": "    "),
                            tst.name, totalTime, tst.rel_utime, tst.rel_stime,
                            0, 0, 0, 0, 0);
                }
            }
        }
        
        printProcessCPU(pw, "", "TOTAL", totalTime, mRelUserTime, mRelSystemTime,
                mRelIoWaitTime, mRelIrqTime, mRelSoftIrqTime, 0, 0);
        
        return sw.toString();
    }
    
    private void printProcessCPU(PrintWriter pw, String prefix, String label, int totalTime, 
            int user, int system, int iowait, int irq, int softIrq, int minFaults, int majFaults) {
        pw.print(prefix);
        pw.print(label);
        pw.print(": ");
        if (totalTime == 0) totalTime = 1;
        pw.print(((user+system+iowait+irq+softIrq)*100)/totalTime);
        pw.print("% = ");
        pw.print((user*100)/totalTime);
        pw.print("% user + ");
        pw.print((system*100)/totalTime);
        pw.print("% kernel");
        if (iowait > 0) {
            pw.print(" + ");
            pw.print((iowait*100)/totalTime);
            pw.print("% iowait");
        }
        if (irq > 0) {
            pw.print(" + ");
            pw.print((irq*100)/totalTime);
            pw.print("% irq");
        }
        if (softIrq > 0) {
            pw.print(" + ");
            pw.print((softIrq*100)/totalTime);
            pw.print("% softirq");
        }
        if (minFaults > 0 || majFaults > 0) {
            pw.print(" / faults:");
            if (minFaults > 0) {
                pw.print(" ");
                pw.print(minFaults);
                pw.print(" minor");
            }
            if (majFaults > 0) {
                pw.print(" ");
                pw.print(majFaults);
                pw.print(" major");
            }
        }
        pw.println();
    }
    
    private String readFile(String file, char endChar) {
        try {
            FileInputStream is = new FileInputStream(file);
            int len = is.read(mBuffer);
            is.close();

            if (len > 0) {
                int i;
                for (i=0; i<len; i++) {
                    if (mBuffer[i] == endChar) {
                        break;
                    }
                }
                return new String(mBuffer, 0, i);
            }
        } catch (java.io.FileNotFoundException e) {
        } catch (java.io.IOException e) {
        }
        return null;
    }

    private void getName(Stats st, String cmdlineFile) {
        String newName = st.baseName;
        if (st.baseName == null || st.baseName.equals("app_process")) {
            String cmdName = readFile(cmdlineFile, '\0');
            if (cmdName != null && cmdName.length() > 1) {
                newName = cmdName;
                int i = newName.lastIndexOf("/");
                if (i > 0 && i < newName.length()-1) {
                    newName = newName.substring(i+1);
                }
            }
        }
        if (st.name == null || !newName.equals(st.name)) {
            st.name = newName;
            st.nameWidth = onMeasureProcessName(st.name);
        }
    }
}

