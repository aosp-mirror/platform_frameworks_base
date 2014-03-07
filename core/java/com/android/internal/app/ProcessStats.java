/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.internal.app;

import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.text.format.DateFormat;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.webkit.WebViewFactory;
import com.android.internal.util.ArrayUtils;
import dalvik.system.VMRuntime;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Objects;

public final class ProcessStats implements Parcelable {
    static final String TAG = "ProcessStats";
    static final boolean DEBUG = false;
    static final boolean DEBUG_PARCEL = false;

    public static final String SERVICE_NAME = "procstats";

    // How often the service commits its data, giving the minimum batching
    // that is done.
    public static long COMMIT_PERIOD = 3*60*60*1000;  // Commit current stats every 3 hours

    public static final int STATE_NOTHING = -1;
    public static final int STATE_PERSISTENT = 0;
    public static final int STATE_TOP = 1;
    public static final int STATE_IMPORTANT_FOREGROUND = 2;
    public static final int STATE_IMPORTANT_BACKGROUND = 3;
    public static final int STATE_BACKUP = 4;
    public static final int STATE_HEAVY_WEIGHT = 5;
    public static final int STATE_SERVICE = 6;
    public static final int STATE_SERVICE_RESTARTING = 7;
    public static final int STATE_RECEIVER = 8;
    public static final int STATE_HOME = 9;
    public static final int STATE_LAST_ACTIVITY = 10;
    public static final int STATE_CACHED_ACTIVITY = 11;
    public static final int STATE_CACHED_ACTIVITY_CLIENT = 12;
    public static final int STATE_CACHED_EMPTY = 13;
    public static final int STATE_COUNT = STATE_CACHED_EMPTY+1;

    public static final int PSS_SAMPLE_COUNT = 0;
    public static final int PSS_MINIMUM = 1;
    public static final int PSS_AVERAGE = 2;
    public static final int PSS_MAXIMUM = 3;
    public static final int PSS_USS_MINIMUM = 4;
    public static final int PSS_USS_AVERAGE = 5;
    public static final int PSS_USS_MAXIMUM = 6;
    public static final int PSS_COUNT = PSS_USS_MAXIMUM+1;

    public static final int ADJ_NOTHING = -1;
    public static final int ADJ_MEM_FACTOR_NORMAL = 0;
    public static final int ADJ_MEM_FACTOR_MODERATE = 1;
    public static final int ADJ_MEM_FACTOR_LOW = 2;
    public static final int ADJ_MEM_FACTOR_CRITICAL = 3;
    public static final int ADJ_MEM_FACTOR_COUNT = ADJ_MEM_FACTOR_CRITICAL+1;
    public static final int ADJ_SCREEN_MOD = ADJ_MEM_FACTOR_COUNT;
    public static final int ADJ_SCREEN_OFF = 0;
    public static final int ADJ_SCREEN_ON = ADJ_SCREEN_MOD;
    public static final int ADJ_COUNT = ADJ_SCREEN_ON*2;

    public static final int FLAG_COMPLETE = 1<<0;
    public static final int FLAG_SHUTDOWN = 1<<1;
    public static final int FLAG_SYSPROPS = 1<<2;

    public static final int[] ALL_MEM_ADJ = new int[] { ADJ_MEM_FACTOR_NORMAL,
            ADJ_MEM_FACTOR_MODERATE, ADJ_MEM_FACTOR_LOW, ADJ_MEM_FACTOR_CRITICAL };

    public static final int[] ALL_SCREEN_ADJ = new int[] { ADJ_SCREEN_OFF, ADJ_SCREEN_ON };

    public static final int[] NON_CACHED_PROC_STATES = new int[] {
            STATE_PERSISTENT, STATE_TOP, STATE_IMPORTANT_FOREGROUND,
            STATE_IMPORTANT_BACKGROUND, STATE_BACKUP, STATE_HEAVY_WEIGHT,
            STATE_SERVICE, STATE_SERVICE_RESTARTING, STATE_RECEIVER
    };

    public static final int[] BACKGROUND_PROC_STATES = new int[] {
            STATE_IMPORTANT_FOREGROUND, STATE_IMPORTANT_BACKGROUND, STATE_BACKUP,
            STATE_HEAVY_WEIGHT, STATE_SERVICE, STATE_SERVICE_RESTARTING, STATE_RECEIVER
    };

    // Map from process states to the states we track.
    static final int[] PROCESS_STATE_TO_STATE = new int[] {
            STATE_PERSISTENT,               // ActivityManager.PROCESS_STATE_PERSISTENT
            STATE_PERSISTENT,               // ActivityManager.PROCESS_STATE_PERSISTENT_UI
            STATE_TOP,                      // ActivityManager.PROCESS_STATE_TOP
            STATE_IMPORTANT_FOREGROUND,     // ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND
            STATE_IMPORTANT_BACKGROUND,     // ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND
            STATE_BACKUP,                   // ActivityManager.PROCESS_STATE_BACKUP
            STATE_HEAVY_WEIGHT,             // ActivityManager.PROCESS_STATE_HEAVY_WEIGHT
            STATE_SERVICE,                  // ActivityManager.PROCESS_STATE_SERVICE
            STATE_RECEIVER,                 // ActivityManager.PROCESS_STATE_RECEIVER
            STATE_HOME,                     // ActivityManager.PROCESS_STATE_HOME
            STATE_LAST_ACTIVITY,            // ActivityManager.PROCESS_STATE_LAST_ACTIVITY
            STATE_CACHED_ACTIVITY,          // ActivityManager.PROCESS_STATE_CACHED_ACTIVITY
            STATE_CACHED_ACTIVITY_CLIENT,   // ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT
            STATE_CACHED_EMPTY,             // ActivityManager.PROCESS_STATE_CACHED_EMPTY
    };

    public static final int[] ALL_PROC_STATES = new int[] { STATE_PERSISTENT,
            STATE_TOP, STATE_IMPORTANT_FOREGROUND, STATE_IMPORTANT_BACKGROUND, STATE_BACKUP,
            STATE_HEAVY_WEIGHT, STATE_SERVICE, STATE_SERVICE_RESTARTING, STATE_RECEIVER,
            STATE_HOME, STATE_LAST_ACTIVITY, STATE_CACHED_ACTIVITY,
            STATE_CACHED_ACTIVITY_CLIENT, STATE_CACHED_EMPTY
    };

    static final String[] STATE_NAMES = new String[] {
            "Persist", "Top    ", "ImpFg  ", "ImpBg  ",
            "Backup ", "HeavyWt", "Service", "ServRst",
            "Receivr", "Home   ",
            "LastAct", "CchAct ", "CchCAct", "CchEmty"
    };

    public static final String[] ADJ_SCREEN_NAMES_CSV = new String[] {
            "off", "on"
    };

    public static final String[] ADJ_MEM_NAMES_CSV = new String[] {
            "norm", "mod",  "low", "crit"
    };

    public static final String[] STATE_NAMES_CSV = new String[] {
            "pers", "top", "impfg", "impbg", "backup", "heavy",
            "service", "service-rs", "receiver", "home", "lastact",
            "cch-activity", "cch-aclient", "cch-empty"
    };

    static final String[] ADJ_SCREEN_TAGS = new String[] {
            "0", "1"
    };

    static final String[] ADJ_MEM_TAGS = new String[] {
            "n", "m",  "l", "c"
    };

    static final String[] STATE_TAGS = new String[] {
            "p", "t", "f", "b", "u", "w",
            "s", "x", "r", "h", "l", "a", "c", "e"
    };

    static final String CSV_SEP = "\t";

    // Current version of the parcel format.
    private static final int PARCEL_VERSION = 13;
    // In-memory Parcel magic number, used to detect attempts to unmarshall bad data
    private static final int MAGIC = 0x50535453;

    // Where the "type"/"state" part of the data appears in an offset integer.
    static int OFFSET_TYPE_SHIFT = 0;
    static int OFFSET_TYPE_MASK = 0xff;
    // Where the "which array" part of the data appears in an offset integer.
    static int OFFSET_ARRAY_SHIFT = 8;
    static int OFFSET_ARRAY_MASK = 0xff;
    // Where the "index into array" part of the data appears in an offset integer.
    static int OFFSET_INDEX_SHIFT = 16;
    static int OFFSET_INDEX_MASK = 0xffff;

    public String mReadError;
    public String mTimePeriodStartClockStr;
    public int mFlags;

    public final ProcessMap<PackageState> mPackages = new ProcessMap<PackageState>();
    public final ProcessMap<ProcessState> mProcesses = new ProcessMap<ProcessState>();

    public final long[] mMemFactorDurations = new long[ADJ_COUNT];
    public int mMemFactor = STATE_NOTHING;
    public long mStartTime;

    public long mTimePeriodStartClock;
    public long mTimePeriodStartRealtime;
    public long mTimePeriodEndRealtime;
    String mRuntime;
    String mWebView;
    boolean mRunning;

    static final int LONGS_SIZE = 4096;
    final ArrayList<long[]> mLongs = new ArrayList<long[]>();
    int mNextLong;

    int[] mAddLongTable;
    int mAddLongTableSize;

    // For writing parcels.
    ArrayMap<String, Integer> mCommonStringToIndex;

    // For reading parcels.
    ArrayList<String> mIndexToCommonString;

    public ProcessStats(boolean running) {
        mRunning = running;
        reset();
    }

    public ProcessStats(Parcel in) {
        reset();
        readFromParcel(in);
    }

    public void add(ProcessStats other) {
        ArrayMap<String, SparseArray<PackageState>> pkgMap = other.mPackages.getMap();
        for (int ip=0; ip<pkgMap.size(); ip++) {
            String pkgName = pkgMap.keyAt(ip);
            SparseArray<PackageState> uids = pkgMap.valueAt(ip);
            for (int iu=0; iu<uids.size(); iu++) {
                int uid = uids.keyAt(iu);
                PackageState otherState = uids.valueAt(iu);
                final int NPROCS = otherState.mProcesses.size();
                final int NSRVS = otherState.mServices.size();
                for (int iproc=0; iproc<NPROCS; iproc++) {
                    ProcessState otherProc = otherState.mProcesses.valueAt(iproc);
                    if (otherProc.mCommonProcess != otherProc) {
                        if (DEBUG) Slog.d(TAG, "Adding pkg " + pkgName + " uid " + uid
                                + " proc " + otherProc.mName);
                        ProcessState thisProc = getProcessStateLocked(pkgName, uid,
                                otherProc.mName);
                        if (thisProc.mCommonProcess == thisProc) {
                            if (DEBUG) Slog.d(TAG, "Existing process is single-package, splitting");
                            thisProc.mMultiPackage = true;
                            long now = SystemClock.uptimeMillis();
                            final PackageState pkgState = getPackageStateLocked(pkgName, uid);
                            thisProc = thisProc.clone(thisProc.mPackage, now);
                            pkgState.mProcesses.put(thisProc.mName, thisProc);
                        }
                        thisProc.add(otherProc);
                    }
                }
                for (int isvc=0; isvc<NSRVS; isvc++) {
                    ServiceState otherSvc = otherState.mServices.valueAt(isvc);
                    if (DEBUG) Slog.d(TAG, "Adding pkg " + pkgName + " uid " + uid
                            + " service " + otherSvc.mName);
                    ServiceState thisSvc = getServiceStateLocked(pkgName, uid,
                            otherSvc.mProcessName, otherSvc.mName);
                    thisSvc.add(otherSvc);
                }
            }
        }

        ArrayMap<String, SparseArray<ProcessState>> procMap = other.mProcesses.getMap();
        for (int ip=0; ip<procMap.size(); ip++) {
            SparseArray<ProcessState> uids = procMap.valueAt(ip);
            for (int iu=0; iu<uids.size(); iu++) {
                int uid = uids.keyAt(iu);
                ProcessState otherProc = uids.valueAt(iu);
                ProcessState thisProc = mProcesses.get(otherProc.mName, uid);
                if (DEBUG) Slog.d(TAG, "Adding uid " + uid + " proc " + otherProc.mName);
                if (thisProc == null) {
                    if (DEBUG) Slog.d(TAG, "Creating new process!");
                    thisProc = new ProcessState(this, otherProc.mPackage, uid, otherProc.mName);
                    mProcesses.put(otherProc.mName, uid, thisProc);
                    PackageState thisState = getPackageStateLocked(otherProc.mPackage, uid);
                    if (!thisState.mProcesses.containsKey(otherProc.mName)) {
                        thisState.mProcesses.put(otherProc.mName, thisProc);
                    }
                }
                thisProc.add(otherProc);
            }
        }

        for (int i=0; i<ADJ_COUNT; i++) {
            if (DEBUG) Slog.d(TAG, "Total duration #" + i + " inc by "
                    + other.mMemFactorDurations[i] + " from "
                    + mMemFactorDurations[i]);
            mMemFactorDurations[i] += other.mMemFactorDurations[i];
        }

        if (other.mTimePeriodStartClock < mTimePeriodStartClock) {
            mTimePeriodStartClock = other.mTimePeriodStartClock;
            mTimePeriodStartClockStr = other.mTimePeriodStartClockStr;
        }
        mTimePeriodEndRealtime += other.mTimePeriodEndRealtime - other.mTimePeriodStartRealtime;
    }

    public static final Parcelable.Creator<ProcessStats> CREATOR
            = new Parcelable.Creator<ProcessStats>() {
        public ProcessStats createFromParcel(Parcel in) {
            return new ProcessStats(in);
        }

        public ProcessStats[] newArray(int size) {
            return new ProcessStats[size];
        }
    };

    private static void printScreenLabel(PrintWriter pw, int offset) {
        switch (offset) {
            case ADJ_NOTHING:
                pw.print("     ");
                break;
            case ADJ_SCREEN_OFF:
                pw.print("SOff/");
                break;
            case ADJ_SCREEN_ON:
                pw.print("SOn /");
                break;
            default:
                pw.print("????/");
                break;
        }
    }

    public static void printScreenLabelCsv(PrintWriter pw, int offset) {
        switch (offset) {
            case ADJ_NOTHING:
                break;
            case ADJ_SCREEN_OFF:
                pw.print(ADJ_SCREEN_NAMES_CSV[0]);
                break;
            case ADJ_SCREEN_ON:
                pw.print(ADJ_SCREEN_NAMES_CSV[1]);
                break;
            default:
                pw.print("???");
                break;
        }
    }

    private static void printMemLabel(PrintWriter pw, int offset, char sep) {
        switch (offset) {
            case ADJ_NOTHING:
                pw.print("    ");
                if (sep != 0) pw.print(' ');
                break;
            case ADJ_MEM_FACTOR_NORMAL:
                pw.print("Norm");
                if (sep != 0) pw.print(sep);
                break;
            case ADJ_MEM_FACTOR_MODERATE:
                pw.print("Mod ");
                if (sep != 0) pw.print(sep);
                break;
            case ADJ_MEM_FACTOR_LOW:
                pw.print("Low ");
                if (sep != 0) pw.print(sep);
                break;
            case ADJ_MEM_FACTOR_CRITICAL:
                pw.print("Crit");
                if (sep != 0) pw.print(sep);
                break;
            default:
                pw.print("????");
                if (sep != 0) pw.print(sep);
                break;
        }
    }

    public static void printMemLabelCsv(PrintWriter pw, int offset) {
        if (offset >= ADJ_MEM_FACTOR_NORMAL) {
            if (offset <= ADJ_MEM_FACTOR_CRITICAL) {
                pw.print(ADJ_MEM_NAMES_CSV[offset]);
            } else {
                pw.print("???");
            }
        }
    }

    public static long dumpSingleTime(PrintWriter pw, String prefix, long[] durations,
            int curState, long curStartTime, long now) {
        long totalTime = 0;
        int printedScreen = -1;
        for (int iscreen=0; iscreen<ADJ_COUNT; iscreen+=ADJ_SCREEN_MOD) {
            int printedMem = -1;
            for (int imem=0; imem<ADJ_MEM_FACTOR_COUNT; imem++) {
                int state = imem+iscreen;
                long time = durations[state];
                String running = "";
                if (curState == state) {
                    time += now - curStartTime;
                    if (pw != null) {
                        running = " (running)";
                    }
                }
                if (time != 0) {
                    if (pw != null) {
                        pw.print(prefix);
                        printScreenLabel(pw, printedScreen != iscreen
                                ? iscreen : STATE_NOTHING);
                        printedScreen = iscreen;
                        printMemLabel(pw, printedMem != imem ? imem : STATE_NOTHING, (char)0);
                        printedMem = imem;
                        pw.print(": ");
                        TimeUtils.formatDuration(time, pw); pw.println(running);
                    }
                    totalTime += time;
                }
            }
        }
        if (totalTime != 0 && pw != null) {
            pw.print(prefix);
            pw.print("    TOTAL: ");
            TimeUtils.formatDuration(totalTime, pw);
            pw.println();
        }
        return totalTime;
    }

    static void dumpAdjTimesCheckin(PrintWriter pw, String sep, long[] durations,
            int curState, long curStartTime, long now) {
        for (int iscreen=0; iscreen<ADJ_COUNT; iscreen+=ADJ_SCREEN_MOD) {
            for (int imem=0; imem<ADJ_MEM_FACTOR_COUNT; imem++) {
                int state = imem+iscreen;
                long time = durations[state];
                if (curState == state) {
                    time += now - curStartTime;
                }
                if (time != 0) {
                    printAdjTagAndValue(pw, state, time);
                }
            }
        }
    }

    static void dumpServiceTimeCheckin(PrintWriter pw, String label, String packageName,
            int uid, String serviceName, ServiceState svc, int serviceType, int opCount,
            int curState, long curStartTime, long now) {
        if (opCount <= 0) {
            return;
        }
        pw.print(label);
        pw.print(",");
        pw.print(packageName);
        pw.print(",");
        pw.print(uid);
        pw.print(",");
        pw.print(serviceName);
        pw.print(",");
        pw.print(opCount);
        boolean didCurState = false;
        for (int i=0; i<svc.mDurationsTableSize; i++) {
            int off = svc.mDurationsTable[i];
            int type = (off>>OFFSET_TYPE_SHIFT)&OFFSET_TYPE_MASK;
            int memFactor = type / ServiceState.SERVICE_COUNT;
            type %= ServiceState.SERVICE_COUNT;
            if (type != serviceType) {
                continue;
            }
            long time = svc.mStats.getLong(off, 0);
            if (curState == memFactor) {
                didCurState = true;
                time += now - curStartTime;
            }
            printAdjTagAndValue(pw, memFactor, time);
        }
        if (!didCurState && curState != STATE_NOTHING) {
            printAdjTagAndValue(pw, curState, now - curStartTime);
        }
        pw.println();
    }

    public static void computeProcessData(ProcessState proc, ProcessDataCollection data, long now) {
        data.totalTime = 0;
        data.numPss = data.minPss = data.avgPss = data.maxPss =
                data.minUss = data.avgUss = data.maxUss = 0;
        for (int is=0; is<data.screenStates.length; is++) {
            for (int im=0; im<data.memStates.length; im++) {
                for (int ip=0; ip<data.procStates.length; ip++) {
                    int bucket = ((data.screenStates[is] + data.memStates[im]) * STATE_COUNT)
                            + data.procStates[ip];
                    data.totalTime += proc.getDuration(bucket, now);
                    long samples = proc.getPssSampleCount(bucket);
                    if (samples > 0) {
                        long minPss = proc.getPssMinimum(bucket);
                        long avgPss = proc.getPssAverage(bucket);
                        long maxPss = proc.getPssMaximum(bucket);
                        long minUss = proc.getPssUssMinimum(bucket);
                        long avgUss = proc.getPssUssAverage(bucket);
                        long maxUss = proc.getPssUssMaximum(bucket);
                        if (data.numPss == 0) {
                            data.minPss = minPss;
                            data.avgPss = avgPss;
                            data.maxPss = maxPss;
                            data.minUss = minUss;
                            data.avgUss = avgUss;
                            data.maxUss = maxUss;
                        } else {
                            if (minPss < data.minPss) {
                                data.minPss = minPss;
                            }
                            data.avgPss = (long)( ((data.avgPss*(double)data.numPss)
                                    + (avgPss*(double)samples)) / (data.numPss+samples) );
                            if (maxPss > data.maxPss) {
                                data.maxPss = maxPss;
                            }
                            if (minUss < data.minUss) {
                                data.minUss = minUss;
                            }
                            data.avgUss = (long)( ((data.avgUss*(double)data.numPss)
                                    + (avgUss*(double)samples)) / (data.numPss+samples) );
                            if (maxUss > data.maxUss) {
                                data.maxUss = maxUss;
                            }
                        }
                        data.numPss += samples;
                    }
                }
            }
        }
    }

    static long computeProcessTimeLocked(ProcessState proc, int[] screenStates, int[] memStates,
                int[] procStates, long now) {
        long totalTime = 0;
        /*
        for (int i=0; i<proc.mDurationsTableSize; i++) {
            int val = proc.mDurationsTable[i];
            totalTime += proc.mState.getLong(val, 0);
            if ((val&0xff) == proc.mCurState) {
                totalTime += now - proc.mStartTime;
            }
        }
        */
        for (int is=0; is<screenStates.length; is++) {
            for (int im=0; im<memStates.length; im++) {
                for (int ip=0; ip<procStates.length; ip++) {
                    int bucket = ((screenStates[is] + memStates[im]) * STATE_COUNT)
                            + procStates[ip];
                    totalTime += proc.getDuration(bucket, now);
                }
            }
        }
        proc.mTmpTotalTime = totalTime;
        return totalTime;
    }

    static void dumpProcessState(PrintWriter pw, String prefix, ProcessState proc,
            int[] screenStates, int[] memStates, int[] procStates, long now) {
        long totalTime = 0;
        int printedScreen = -1;
        for (int is=0; is<screenStates.length; is++) {
            int printedMem = -1;
            for (int im=0; im<memStates.length; im++) {
                for (int ip=0; ip<procStates.length; ip++) {
                    final int iscreen = screenStates[is];
                    final int imem = memStates[im];
                    final int bucket = ((iscreen + imem) * STATE_COUNT) + procStates[ip];
                    long time = proc.getDuration(bucket, now);
                    String running = "";
                    if (proc.mCurState == bucket) {
                        running = " (running)";
                    }
                    if (time != 0) {
                        pw.print(prefix);
                        if (screenStates.length > 1) {
                            printScreenLabel(pw, printedScreen != iscreen
                                    ? iscreen : STATE_NOTHING);
                            printedScreen = iscreen;
                        }
                        if (memStates.length > 1) {
                            printMemLabel(pw, printedMem != imem ? imem : STATE_NOTHING, '/');
                            printedMem = imem;
                        }
                        pw.print(STATE_NAMES[procStates[ip]]); pw.print(": ");
                        TimeUtils.formatDuration(time, pw); pw.println(running);
                        totalTime += time;
                    }
                }
            }
        }
        if (totalTime != 0) {
            pw.print(prefix);
            if (screenStates.length > 1) {
                printScreenLabel(pw, STATE_NOTHING);
            }
            if (memStates.length > 1) {
                printMemLabel(pw, STATE_NOTHING, '/');
            }
            pw.print("TOTAL  : ");
            TimeUtils.formatDuration(totalTime, pw);
            pw.println();
        }
    }

    static void dumpProcessPss(PrintWriter pw, String prefix, ProcessState proc, int[] screenStates,
            int[] memStates, int[] procStates) {
        boolean printedHeader = false;
        int printedScreen = -1;
        for (int is=0; is<screenStates.length; is++) {
            int printedMem = -1;
            for (int im=0; im<memStates.length; im++) {
                for (int ip=0; ip<procStates.length; ip++) {
                    final int iscreen = screenStates[is];
                    final int imem = memStates[im];
                    final int bucket = ((iscreen + imem) * STATE_COUNT) + procStates[ip];
                    long count = proc.getPssSampleCount(bucket);
                    if (count > 0) {
                        if (!printedHeader) {
                            pw.print(prefix);
                            pw.print("PSS/USS (");
                            pw.print(proc.mPssTableSize);
                            pw.println(" entries):");
                            printedHeader = true;
                        }
                        pw.print(prefix);
                        pw.print("  ");
                        if (screenStates.length > 1) {
                            printScreenLabel(pw, printedScreen != iscreen
                                    ? iscreen : STATE_NOTHING);
                            printedScreen = iscreen;
                        }
                        if (memStates.length > 1) {
                            printMemLabel(pw, printedMem != imem ? imem : STATE_NOTHING, '/');
                            printedMem = imem;
                        }
                        pw.print(STATE_NAMES[procStates[ip]]); pw.print(": ");
                        pw.print(count);
                        pw.print(" samples ");
                        printSizeValue(pw, proc.getPssMinimum(bucket) * 1024);
                        pw.print(" ");
                        printSizeValue(pw, proc.getPssAverage(bucket) * 1024);
                        pw.print(" ");
                        printSizeValue(pw, proc.getPssMaximum(bucket) * 1024);
                        pw.print(" / ");
                        printSizeValue(pw, proc.getPssUssMinimum(bucket) * 1024);
                        pw.print(" ");
                        printSizeValue(pw, proc.getPssUssAverage(bucket) * 1024);
                        pw.print(" ");
                        printSizeValue(pw, proc.getPssUssMaximum(bucket) * 1024);
                        pw.println();
                    }
                }
            }
        }
        if (proc.mNumExcessiveWake != 0) {
            pw.print(prefix); pw.print("Killed for excessive wake locks: ");
                    pw.print(proc.mNumExcessiveWake); pw.println(" times");
        }
        if (proc.mNumExcessiveCpu != 0) {
            pw.print(prefix); pw.print("Killed for excessive CPU use: ");
                    pw.print(proc.mNumExcessiveCpu); pw.println(" times");
        }
        if (proc.mNumCachedKill != 0) {
            pw.print(prefix); pw.print("Killed from cached state: ");
                    pw.print(proc.mNumCachedKill); pw.print(" times from pss ");
                    printSizeValue(pw, proc.mMinCachedKillPss * 1024); pw.print("-");
                    printSizeValue(pw, proc.mAvgCachedKillPss * 1024); pw.print("-");
                    printSizeValue(pw, proc.mMaxCachedKillPss * 1024); pw.println();
        }
    }

    static void dumpStateHeadersCsv(PrintWriter pw, String sep, int[] screenStates,
            int[] memStates, int[] procStates) {
        final int NS = screenStates != null ? screenStates.length : 1;
        final int NM = memStates != null ? memStates.length : 1;
        final int NP = procStates != null ? procStates.length : 1;
        for (int is=0; is<NS; is++) {
            for (int im=0; im<NM; im++) {
                for (int ip=0; ip<NP; ip++) {
                    pw.print(sep);
                    boolean printed = false;
                    if (screenStates != null && screenStates.length > 1) {
                        printScreenLabelCsv(pw, screenStates[is]);
                        printed = true;
                    }
                    if (memStates != null && memStates.length > 1) {
                        if (printed) {
                            pw.print("-");
                        }
                        printMemLabelCsv(pw, memStates[im]);
                        printed = true;
                    }
                    if (procStates != null && procStates.length > 1) {
                        if (printed) {
                            pw.print("-");
                        }
                        pw.print(STATE_NAMES_CSV[procStates[ip]]);
                    }
                }
            }
        }
    }

    static void dumpProcessStateCsv(PrintWriter pw, ProcessState proc,
            boolean sepScreenStates, int[] screenStates, boolean sepMemStates, int[] memStates,
            boolean sepProcStates, int[] procStates, long now) {
        final int NSS = sepScreenStates ? screenStates.length : 1;
        final int NMS = sepMemStates ? memStates.length : 1;
        final int NPS = sepProcStates ? procStates.length : 1;
        for (int iss=0; iss<NSS; iss++) {
            for (int ims=0; ims<NMS; ims++) {
                for (int ips=0; ips<NPS; ips++) {
                    final int vsscreen = sepScreenStates ? screenStates[iss] : 0;
                    final int vsmem = sepMemStates ? memStates[ims] : 0;
                    final int vsproc = sepProcStates ? procStates[ips] : 0;
                    final int NSA = sepScreenStates ? 1 : screenStates.length;
                    final int NMA = sepMemStates ? 1 : memStates.length;
                    final int NPA = sepProcStates ? 1 : procStates.length;
                    long totalTime = 0;
                    for (int isa=0; isa<NSA; isa++) {
                        for (int ima=0; ima<NMA; ima++) {
                            for (int ipa=0; ipa<NPA; ipa++) {
                                final int vascreen = sepScreenStates ? 0 : screenStates[isa];
                                final int vamem = sepMemStates ? 0 : memStates[ima];
                                final int vaproc = sepProcStates ? 0 : procStates[ipa];
                                final int bucket = ((vsscreen + vascreen + vsmem + vamem)
                                        * STATE_COUNT) + vsproc + vaproc;
                                totalTime += proc.getDuration(bucket, now);
                            }
                        }
                    }
                    pw.print(CSV_SEP);
                    pw.print(totalTime);
                }
            }
        }
    }

    static void dumpProcessList(PrintWriter pw, String prefix, ArrayList<ProcessState> procs,
            int[] screenStates, int[] memStates, int[] procStates, long now) {
        String innerPrefix = prefix + "  ";
        for (int i=procs.size()-1; i>=0; i--) {
            ProcessState proc = procs.get(i);
            pw.print(prefix);
            pw.print(proc.mName);
            pw.print(" / ");
            UserHandle.formatUid(pw, proc.mUid);
            pw.print(" (");
            pw.print(proc.mDurationsTableSize);
            pw.print(" entries)");
            pw.println(":");
            dumpProcessState(pw, innerPrefix, proc, screenStates, memStates, procStates, now);
            if (proc.mPssTableSize > 0) {
                dumpProcessPss(pw, innerPrefix, proc, screenStates, memStates, procStates);
            }
        }
    }

    static void dumpProcessSummaryDetails(PrintWriter pw, ProcessState proc, String prefix,
            String label, int[] screenStates, int[] memStates, int[] procStates,
            long now, long totalTime, boolean full) {
        ProcessDataCollection totals = new ProcessDataCollection(screenStates,
                memStates, procStates);
        computeProcessData(proc, totals, now);
        if (totals.totalTime != 0 || totals.numPss != 0) {
            if (prefix != null) {
                pw.print(prefix);
            }
            if (label != null) {
                pw.print(label);
            }
            totals.print(pw, totalTime, full);
            if (prefix != null) {
                pw.println();
            }
        }
    }

    static void dumpProcessSummaryLocked(PrintWriter pw, String prefix,
            ArrayList<ProcessState> procs, int[] screenStates, int[] memStates, int[] procStates,
            long now, long totalTime) {
        for (int i=procs.size()-1; i>=0; i--) {
            ProcessState proc = procs.get(i);
            pw.print(prefix);
            pw.print("* ");
            pw.print(proc.mName);
            pw.print(" / ");
            UserHandle.formatUid(pw, proc.mUid);
            pw.println(":");
            dumpProcessSummaryDetails(pw, proc, prefix, "         TOTAL: ", screenStates, memStates,
                    procStates, now, totalTime, true);
            dumpProcessSummaryDetails(pw, proc, prefix, "    Persistent: ", screenStates, memStates,
                    new int[] { STATE_PERSISTENT }, now, totalTime, true);
            dumpProcessSummaryDetails(pw, proc, prefix, "           Top: ", screenStates, memStates,
                    new int[] {STATE_TOP}, now, totalTime, true);
            dumpProcessSummaryDetails(pw, proc, prefix, "        Imp Fg: ", screenStates, memStates,
                    new int[] { STATE_IMPORTANT_FOREGROUND }, now, totalTime, true);
            dumpProcessSummaryDetails(pw, proc, prefix, "        Imp Bg: ", screenStates, memStates,
                    new int[] {STATE_IMPORTANT_BACKGROUND}, now, totalTime, true);
            dumpProcessSummaryDetails(pw, proc, prefix, "        Backup: ", screenStates, memStates,
                    new int[] {STATE_BACKUP}, now, totalTime, true);
            dumpProcessSummaryDetails(pw, proc, prefix, "     Heavy Wgt: ", screenStates, memStates,
                    new int[] {STATE_HEAVY_WEIGHT}, now, totalTime, true);
            dumpProcessSummaryDetails(pw, proc, prefix, "       Service: ", screenStates, memStates,
                    new int[] {STATE_SERVICE}, now, totalTime, true);
            dumpProcessSummaryDetails(pw, proc, prefix, "    Service Rs: ", screenStates, memStates,
                    new int[] {STATE_SERVICE_RESTARTING}, now, totalTime, true);
            dumpProcessSummaryDetails(pw, proc, prefix, "      Receiver: ", screenStates, memStates,
                    new int[] {STATE_RECEIVER}, now, totalTime, true);
            dumpProcessSummaryDetails(pw, proc, prefix, "        (Home): ", screenStates, memStates,
                    new int[] {STATE_HOME}, now, totalTime, true);
            dumpProcessSummaryDetails(pw, proc, prefix, "    (Last Act): ", screenStates, memStates,
                    new int[] {STATE_LAST_ACTIVITY}, now, totalTime, true);
            dumpProcessSummaryDetails(pw, proc, prefix, "      (Cached): ", screenStates, memStates,
                    new int[] {STATE_CACHED_ACTIVITY, STATE_CACHED_ACTIVITY_CLIENT,
                            STATE_CACHED_EMPTY}, now, totalTime, true);
        }
    }

    static void printPercent(PrintWriter pw, double fraction) {
        fraction *= 100;
        if (fraction < 1) {
            pw.print(String.format("%.2f", fraction));
        } else if (fraction < 10) {
            pw.print(String.format("%.1f", fraction));
        } else {
            pw.print(String.format("%.0f", fraction));
        }
        pw.print("%");
    }

    static void printSizeValue(PrintWriter pw, long number) {
        float result = number;
        String suffix = "";
        if (result > 900) {
            suffix = "KB";
            result = result / 1024;
        }
        if (result > 900) {
            suffix = "MB";
            result = result / 1024;
        }
        if (result > 900) {
            suffix = "GB";
            result = result / 1024;
        }
        if (result > 900) {
            suffix = "TB";
            result = result / 1024;
        }
        if (result > 900) {
            suffix = "PB";
            result = result / 1024;
        }
        String value;
        if (result < 1) {
            value = String.format("%.2f", result);
        } else if (result < 10) {
            value = String.format("%.1f", result);
        } else if (result < 100) {
            value = String.format("%.0f", result);
        } else {
            value = String.format("%.0f", result);
        }
        pw.print(value);
        pw.print(suffix);
    }

    public static void dumpProcessListCsv(PrintWriter pw, ArrayList<ProcessState> procs,
            boolean sepScreenStates, int[] screenStates, boolean sepMemStates, int[] memStates,
            boolean sepProcStates, int[] procStates, long now) {
        pw.print("process");
        pw.print(CSV_SEP);
        pw.print("uid");
        dumpStateHeadersCsv(pw, CSV_SEP, sepScreenStates ? screenStates : null,
                sepMemStates ? memStates : null,
                sepProcStates ? procStates : null);
        pw.println();
        for (int i=procs.size()-1; i>=0; i--) {
            ProcessState proc = procs.get(i);
            pw.print(proc.mName);
            pw.print(CSV_SEP);
            UserHandle.formatUid(pw, proc.mUid);
            dumpProcessStateCsv(pw, proc, sepScreenStates, screenStates,
                    sepMemStates, memStates, sepProcStates, procStates, now);
            pw.println();
        }
    }

    static int printArrayEntry(PrintWriter pw, String[] array, int value, int mod) {
        int index = value/mod;
        if (index >= 0 && index < array.length) {
            pw.print(array[index]);
        } else {
            pw.print('?');
        }
        return value - index*mod;
    }

    static void printProcStateTag(PrintWriter pw, int state) {
        state = printArrayEntry(pw, ADJ_SCREEN_TAGS,  state, ADJ_SCREEN_MOD*STATE_COUNT);
        state = printArrayEntry(pw, ADJ_MEM_TAGS,  state, STATE_COUNT);
        printArrayEntry(pw, STATE_TAGS,  state, 1);
    }

    static void printAdjTag(PrintWriter pw, int state) {
        state = printArrayEntry(pw, ADJ_SCREEN_TAGS,  state, ADJ_SCREEN_MOD);
        printArrayEntry(pw, ADJ_MEM_TAGS, state, 1);
    }

    static void printProcStateTagAndValue(PrintWriter pw, int state, long value) {
        pw.print(',');
        printProcStateTag(pw, state);
        pw.print(':');
        pw.print(value);
    }

    static void printAdjTagAndValue(PrintWriter pw, int state, long value) {
        pw.print(',');
        printAdjTag(pw, state);
        pw.print(':');
        pw.print(value);
    }

    static void dumpAllProcessStateCheckin(PrintWriter pw, ProcessState proc, long now) {
        boolean didCurState = false;
        for (int i=0; i<proc.mDurationsTableSize; i++) {
            int off = proc.mDurationsTable[i];
            int type = (off>>OFFSET_TYPE_SHIFT)&OFFSET_TYPE_MASK;
            long time = proc.mStats.getLong(off, 0);
            if (proc.mCurState == type) {
                didCurState = true;
                time += now - proc.mStartTime;
            }
            printProcStateTagAndValue(pw, type, time);
        }
        if (!didCurState && proc.mCurState != STATE_NOTHING) {
            printProcStateTagAndValue(pw, proc.mCurState, now - proc.mStartTime);
        }
    }

    static void dumpAllProcessPssCheckin(PrintWriter pw, ProcessState proc) {
        for (int i=0; i<proc.mPssTableSize; i++) {
            int off = proc.mPssTable[i];
            int type = (off>>OFFSET_TYPE_SHIFT)&OFFSET_TYPE_MASK;
            long count = proc.mStats.getLong(off, PSS_SAMPLE_COUNT);
            long min = proc.mStats.getLong(off, PSS_MINIMUM);
            long avg = proc.mStats.getLong(off, PSS_AVERAGE);
            long max = proc.mStats.getLong(off, PSS_MAXIMUM);
            long umin = proc.mStats.getLong(off, PSS_USS_MINIMUM);
            long uavg = proc.mStats.getLong(off, PSS_USS_AVERAGE);
            long umax = proc.mStats.getLong(off, PSS_USS_MAXIMUM);
            pw.print(',');
            printProcStateTag(pw, type);
            pw.print(':');
            pw.print(count);
            pw.print(':');
            pw.print(min);
            pw.print(':');
            pw.print(avg);
            pw.print(':');
            pw.print(max);
            pw.print(':');
            pw.print(umin);
            pw.print(':');
            pw.print(uavg);
            pw.print(':');
            pw.print(umax);
        }
    }

    public void reset() {
        if (DEBUG) Slog.d(TAG, "Resetting state of " + mTimePeriodStartClockStr);
        resetCommon();
        mPackages.getMap().clear();
        mProcesses.getMap().clear();
        mMemFactor = STATE_NOTHING;
        mStartTime = 0;
        if (DEBUG) Slog.d(TAG, "State reset; now " + mTimePeriodStartClockStr);
    }

    public void resetSafely() {
        if (DEBUG) Slog.d(TAG, "Safely resetting state of " + mTimePeriodStartClockStr);
        resetCommon();
        long now = SystemClock.uptimeMillis();
        ArrayMap<String, SparseArray<ProcessState>> procMap = mProcesses.getMap();
        for (int ip=procMap.size()-1; ip>=0; ip--) {
            SparseArray<ProcessState> uids = procMap.valueAt(ip);
            for (int iu=uids.size()-1; iu>=0; iu--) {
                ProcessState ps = uids.valueAt(iu);
                if (ps.isInUse()) {
                    uids.valueAt(iu).resetSafely(now);
                } else {
                    uids.valueAt(iu).makeDead();
                    uids.removeAt(iu);
                }
            }
            if (uids.size() <= 0) {
                procMap.removeAt(ip);
            }
        }
        ArrayMap<String, SparseArray<PackageState>> pkgMap = mPackages.getMap();
        for (int ip=pkgMap.size()-1; ip>=0; ip--) {
            SparseArray<PackageState> uids = pkgMap.valueAt(ip);
            for (int iu=uids.size()-1; iu>=0; iu--) {
                PackageState pkgState = uids.valueAt(iu);
                for (int iproc=pkgState.mProcesses.size()-1; iproc>=0; iproc--) {
                    ProcessState ps = pkgState.mProcesses.valueAt(iproc);
                    if (ps.isInUse() || ps.mCommonProcess.isInUse()) {
                        ps.resetSafely(now);
                    } else {
                        pkgState.mProcesses.valueAt(iproc).makeDead();
                        pkgState.mProcesses.removeAt(iproc);
                    }
                }
                for (int isvc=pkgState.mServices.size()-1; isvc>=0; isvc--) {
                    ServiceState ss = pkgState.mServices.valueAt(isvc);
                    if (ss.isInUse()) {
                        ss.resetSafely(now);
                    } else {
                        pkgState.mServices.removeAt(isvc);
                    }
                }
                if (pkgState.mProcesses.size() <= 0 && pkgState.mServices.size() <= 0) {
                    uids.removeAt(iu);
                }
            }
            if (uids.size() <= 0) {
                pkgMap.removeAt(ip);
            }
        }
        mStartTime = now;
        if (DEBUG) Slog.d(TAG, "State reset; now " + mTimePeriodStartClockStr);
    }

    private void resetCommon() {
        mTimePeriodStartClock = System.currentTimeMillis();
        buildTimePeriodStartClockStr();
        mTimePeriodStartRealtime = mTimePeriodEndRealtime = SystemClock.elapsedRealtime();
        mLongs.clear();
        mLongs.add(new long[LONGS_SIZE]);
        mNextLong = 0;
        Arrays.fill(mMemFactorDurations, 0);
        mStartTime = 0;
        mReadError = null;
        mFlags = 0;
        evaluateSystemProperties(true);
    }

    public boolean evaluateSystemProperties(boolean update) {
        boolean changed = false;
        String runtime = SystemProperties.get("persist.sys.dalvik.vm.lib",
                VMRuntime.getRuntime().vmLibrary());
        if (!Objects.equals(runtime, mRuntime)) {
            changed = true;
            if (update) {
                mRuntime = runtime;
            }
        }
        String webview = WebViewFactory.useExperimentalWebView() ? "chromeview" : "webview";
        if (!Objects.equals(webview, mWebView)) {
            changed = true;
            if (update) {
                mWebView = webview;
            }
        }
        return changed;
    }

    private void buildTimePeriodStartClockStr() {
        mTimePeriodStartClockStr = DateFormat.format("yyyy-MM-dd-HH-mm-ss",
                mTimePeriodStartClock).toString();
    }

    static final int[] BAD_TABLE = new int[0];

    private int[] readTableFromParcel(Parcel in, String name, String what) {
        final int size = in.readInt();
        if (size < 0) {
            Slog.w(TAG, "Ignoring existing stats; bad " + what + " table size: " + size);
            return BAD_TABLE;
        }
        if (size == 0) {
            return null;
        }
        final int[] table = new int[size];
        for (int i=0; i<size; i++) {
            table[i] = in.readInt();
            if (DEBUG_PARCEL) Slog.i(TAG, "Reading in " + name + " table #" + i + ": "
                    + ProcessStats.printLongOffset(table[i]));
            if (!validateLongOffset(table[i])) {
                Slog.w(TAG, "Ignoring existing stats; bad " + what + " table entry: "
                        + ProcessStats.printLongOffset(table[i]));
                return null;
            }
        }
        return table;
    }

    private void writeCompactedLongArray(Parcel out, long[] array, int num) {
        for (int i=0; i<num; i++) {
            long val = array[i];
            if (val < 0) {
                Slog.w(TAG, "Time val negative: " + val);
                val = 0;
            }
            if (val <= Integer.MAX_VALUE) {
                out.writeInt((int)val);
            } else {
                int top = ~((int)((val>>32)&0x7fffffff));
                int bottom = (int)(val&0xfffffff);
                out.writeInt(top);
                out.writeInt(bottom);
            }
        }
    }

    private void readCompactedLongArray(Parcel in, int version, long[] array, int num) {
        if (version <= 10) {
            in.readLongArray(array);
            return;
        }
        final int alen = array.length;
        if (num > alen) {
            throw new RuntimeException("bad array lengths: got " + num + " array is " + alen);
        }
        int i;
        for (i=0; i<num; i++) {
            int val = in.readInt();
            if (val >= 0) {
                array[i] = val;
            } else {
                int bottom = in.readInt();
                array[i] = (((long)~val)<<32) | bottom;
            }
        }
        while (i < alen) {
            array[i] = 0;
            i++;
        }
    }

    private void writeCommonString(Parcel out, String name) {
        Integer index = mCommonStringToIndex.get(name);
        if (index != null) {
            out.writeInt(index);
            return;
        }
        index = mCommonStringToIndex.size();
        mCommonStringToIndex.put(name, index);
        out.writeInt(~index);
        out.writeString(name);
    }

    private String readCommonString(Parcel in, int version) {
        if (version <= 9) {
            return in.readString();
        }
        int index = in.readInt();
        if (index >= 0) {
            return mIndexToCommonString.get(index);
        }
        index = ~index;
        String name = in.readString();
        while (mIndexToCommonString.size() <= index) {
            mIndexToCommonString.add(null);
        }
        mIndexToCommonString.set(index, name);
        return name;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        long now = SystemClock.uptimeMillis();
        out.writeInt(MAGIC);
        out.writeInt(PARCEL_VERSION);
        out.writeInt(STATE_COUNT);
        out.writeInt(ADJ_COUNT);
        out.writeInt(PSS_COUNT);
        out.writeInt(LONGS_SIZE);

        mCommonStringToIndex = new ArrayMap<String, Integer>(mProcesses.mMap.size());

        // First commit all running times.
        ArrayMap<String, SparseArray<ProcessState>> procMap = mProcesses.getMap();
        final int NPROC = procMap.size();
        for (int ip=0; ip<NPROC; ip++) {
            SparseArray<ProcessState> uids = procMap.valueAt(ip);
            final int NUID = uids.size();
            for (int iu=0; iu<NUID; iu++) {
                uids.valueAt(iu).commitStateTime(now);
            }
        }
        ArrayMap<String, SparseArray<PackageState>> pkgMap = mPackages.getMap();
        final int NPKG = pkgMap.size();
        for (int ip=0; ip<NPKG; ip++) {
            SparseArray<PackageState> uids = pkgMap.valueAt(ip);
            final int NUID = uids.size();
            for (int iu=0; iu<NUID; iu++) {
                PackageState pkgState = uids.valueAt(iu);
                final int NPROCS = pkgState.mProcesses.size();
                for (int iproc=0; iproc<NPROCS; iproc++) {
                    ProcessState proc = pkgState.mProcesses.valueAt(iproc);
                    if (proc.mCommonProcess != proc) {
                        proc.commitStateTime(now);
                    }
                }
                final int NSRVS = pkgState.mServices.size();
                for (int isvc=0; isvc<NSRVS; isvc++) {
                    pkgState.mServices.valueAt(isvc).commitStateTime(now);
                }
            }
        }

        out.writeLong(mTimePeriodStartClock);
        out.writeLong(mTimePeriodStartRealtime);
        out.writeLong(mTimePeriodEndRealtime);
        out.writeString(mRuntime);
        out.writeString(mWebView);
        out.writeInt(mFlags);

        out.writeInt(mLongs.size());
        out.writeInt(mNextLong);
        for (int i=0; i<(mLongs.size()-1); i++) {
            long[] array = mLongs.get(i);
            writeCompactedLongArray(out, array, array.length);
        }
        long[] lastLongs = mLongs.get(mLongs.size() - 1);
        writeCompactedLongArray(out, lastLongs, mNextLong);

        if (mMemFactor != STATE_NOTHING) {
            mMemFactorDurations[mMemFactor] += now - mStartTime;
            mStartTime = now;
        }
        writeCompactedLongArray(out, mMemFactorDurations, mMemFactorDurations.length);

        out.writeInt(NPROC);
        for (int ip=0; ip<NPROC; ip++) {
            writeCommonString(out, procMap.keyAt(ip));
            SparseArray<ProcessState> uids = procMap.valueAt(ip);
            final int NUID = uids.size();
            out.writeInt(NUID);
            for (int iu=0; iu<NUID; iu++) {
                out.writeInt(uids.keyAt(iu));
                ProcessState proc = uids.valueAt(iu);
                writeCommonString(out, proc.mPackage);
                proc.writeToParcel(out, now);
            }
        }
        out.writeInt(NPKG);
        for (int ip=0; ip<NPKG; ip++) {
            writeCommonString(out, pkgMap.keyAt(ip));
            SparseArray<PackageState> uids = pkgMap.valueAt(ip);
            final int NUID = uids.size();
            out.writeInt(NUID);
            for (int iu=0; iu<NUID; iu++) {
                out.writeInt(uids.keyAt(iu));
                PackageState pkgState = uids.valueAt(iu);
                final int NPROCS = pkgState.mProcesses.size();
                out.writeInt(NPROCS);
                for (int iproc=0; iproc<NPROCS; iproc++) {
                    writeCommonString(out, pkgState.mProcesses.keyAt(iproc));
                    ProcessState proc = pkgState.mProcesses.valueAt(iproc);
                    if (proc.mCommonProcess == proc) {
                        // This is the same as the common process we wrote above.
                        out.writeInt(0);
                    } else {
                        // There is separate data for this package's process.
                        out.writeInt(1);
                        proc.writeToParcel(out, now);
                    }
                }
                final int NSRVS = pkgState.mServices.size();
                out.writeInt(NSRVS);
                for (int isvc=0; isvc<NSRVS; isvc++) {
                    out.writeString(pkgState.mServices.keyAt(isvc));
                    ServiceState svc = pkgState.mServices.valueAt(isvc);
                    writeCommonString(out, svc.mProcessName);
                    svc.writeToParcel(out, now);
                }
            }
        }

        mCommonStringToIndex = null;
    }

    private boolean readCheckedInt(Parcel in, int val, String what) {
        int got;
        if ((got=in.readInt()) != val) {
            mReadError = "bad " + what + ": " + got;
            return false;
        }
        return true;
    }

    static byte[] readFully(InputStream stream, int[] outLen) throws IOException {
        int pos = 0;
        final int initialAvail = stream.available();
        byte[] data = new byte[initialAvail > 0 ? (initialAvail+1) : 16384];
        while (true) {
            int amt = stream.read(data, pos, data.length-pos);
            if (DEBUG_PARCEL) Slog.i("foo", "Read " + amt + " bytes at " + pos
                    + " of avail " + data.length);
            if (amt < 0) {
                if (DEBUG_PARCEL) Slog.i("foo", "**** FINISHED READING: pos=" + pos
                        + " len=" + data.length);
                outLen[0] = pos;
                return data;
            }
            pos += amt;
            if (pos >= data.length) {
                byte[] newData = new byte[pos+16384];
                if (DEBUG_PARCEL) Slog.i(TAG, "Copying " + pos + " bytes to new array len "
                        + newData.length);
                System.arraycopy(data, 0, newData, 0, pos);
                data = newData;
            }
        }
    }

    public void read(InputStream stream) {
        try {
            int[] len = new int[1];
            byte[] raw = readFully(stream, len);
            Parcel in = Parcel.obtain();
            in.unmarshall(raw, 0, len[0]);
            in.setDataPosition(0);
            stream.close();

            readFromParcel(in);
        } catch (IOException e) {
            mReadError = "caught exception: " + e;
        }
    }

    public void readFromParcel(Parcel in) {
        final boolean hadData = mPackages.getMap().size() > 0
                || mProcesses.getMap().size() > 0;
        if (hadData) {
            resetSafely();
        }

        if (!readCheckedInt(in, MAGIC, "magic number")) {
            return;
        }
        int version = in.readInt();
        if (version != PARCEL_VERSION) {
            mReadError = "bad version: " + version;
            return;
        }
        if (!readCheckedInt(in, STATE_COUNT, "state count")) {
            return;
        }
        if (!readCheckedInt(in, ADJ_COUNT, "adj count")) {
            return;
        }
        if (!readCheckedInt(in, PSS_COUNT, "pss count")) {
            return;
        }
        if (!readCheckedInt(in, LONGS_SIZE, "longs size")) {
            return;
        }

        mIndexToCommonString = new ArrayList<String>();

        mTimePeriodStartClock = in.readLong();
        buildTimePeriodStartClockStr();
        mTimePeriodStartRealtime = in.readLong();
        mTimePeriodEndRealtime = in.readLong();
        mRuntime = in.readString();
        mWebView = in.readString();
        mFlags = in.readInt();

        final int NLONGS = in.readInt();
        final int NEXTLONG = in.readInt();
        mLongs.clear();
        for (int i=0; i<(NLONGS-1); i++) {
            while (i >= mLongs.size()) {
                mLongs.add(new long[LONGS_SIZE]);
            }
            readCompactedLongArray(in, version, mLongs.get(i), LONGS_SIZE);
        }
        long[] longs = new long[LONGS_SIZE];
        mNextLong = NEXTLONG;
        readCompactedLongArray(in, version, longs, NEXTLONG);
        mLongs.add(longs);

        readCompactedLongArray(in, version, mMemFactorDurations, mMemFactorDurations.length);

        int NPROC = in.readInt();
        if (NPROC < 0) {
            mReadError = "bad process count: " + NPROC;
            return;
        }
        while (NPROC > 0) {
            NPROC--;
            String procName = readCommonString(in, version);
            if (procName == null) {
                mReadError = "bad process name";
                return;
            }
            int NUID = in.readInt();
            if (NUID < 0) {
                mReadError = "bad uid count: " + NUID;
                return;
            }
            while (NUID > 0) {
                NUID--;
                int uid = in.readInt();
                if (uid < 0) {
                    mReadError = "bad uid: " + uid;
                    return;
                }
                String pkgName = readCommonString(in, version);
                if (pkgName == null) {
                    mReadError = "bad process package name";
                    return;
                }
                ProcessState proc = hadData ? mProcesses.get(procName, uid) : null;
                if (proc != null) {
                    if (!proc.readFromParcel(in, false)) {
                        return;
                    }
                } else {
                    proc = new ProcessState(this, pkgName, uid, procName);
                    if (!proc.readFromParcel(in, true)) {
                        return;
                    }
                }
                if (DEBUG_PARCEL) Slog.d(TAG, "Adding process: " + procName + " " + uid
                        + " " + proc);
                mProcesses.put(procName, uid, proc);
            }
        }

        if (DEBUG_PARCEL) Slog.d(TAG, "Read " + mProcesses.getMap().size() + " processes");

        int NPKG = in.readInt();
        if (NPKG < 0) {
            mReadError = "bad package count: " + NPKG;
            return;
        }
        while (NPKG > 0) {
            NPKG--;
            String pkgName = readCommonString(in, version);
            if (pkgName == null) {
                mReadError = "bad package name";
                return;
            }
            int NUID = in.readInt();
            if (NUID < 0) {
                mReadError = "bad uid count: " + NUID;
                return;
            }
            while (NUID > 0) {
                NUID--;
                int uid = in.readInt();
                if (uid < 0) {
                    mReadError = "bad uid: " + uid;
                    return;
                }
                PackageState pkgState = new PackageState(pkgName, uid);
                mPackages.put(pkgName, uid, pkgState);
                int NPROCS = in.readInt();
                if (NPROCS < 0) {
                    mReadError = "bad package process count: " + NPROCS;
                    return;
                }
                while (NPROCS > 0) {
                    NPROCS--;
                    String procName = readCommonString(in, version);
                    if (procName == null) {
                        mReadError = "bad package process name";
                        return;
                    }
                    int hasProc = in.readInt();
                    if (DEBUG_PARCEL) Slog.d(TAG, "Reading package " + pkgName + " " + uid
                            + " process " + procName + " hasProc=" + hasProc);
                    ProcessState commonProc = mProcesses.get(procName, uid);
                    if (DEBUG_PARCEL) Slog.d(TAG, "Got common proc " + procName + " " + uid
                            + ": " + commonProc);
                    if (commonProc == null) {
                        mReadError = "no common proc: " + procName;
                        return;
                    }
                    if (hasProc != 0) {
                        // The process for this package is unique to the package; we
                        // need to load it.  We don't need to do anything about it if
                        // it is not unique because if someone later looks for it
                        // they will find and use it from the global procs.
                        ProcessState proc = hadData ? pkgState.mProcesses.get(procName) : null;
                        if (proc != null) {
                            if (!proc.readFromParcel(in, false)) {
                                return;
                            }
                        } else {
                            proc = new ProcessState(commonProc, pkgName, uid, procName, 0);
                            if (!proc.readFromParcel(in, true)) {
                                return;
                            }
                        }
                        if (DEBUG_PARCEL) Slog.d(TAG, "Adding package " + pkgName + " process: "
                                + procName + " " + uid + " " + proc);
                        pkgState.mProcesses.put(procName, proc);
                    } else {
                        if (DEBUG_PARCEL) Slog.d(TAG, "Adding package " + pkgName + " process: "
                                + procName + " " + uid + " " + commonProc);
                        pkgState.mProcesses.put(procName, commonProc);
                    }
                }
                int NSRVS = in.readInt();
                if (NSRVS < 0) {
                    mReadError = "bad package service count: " + NSRVS;
                    return;
                }
                while (NSRVS > 0) {
                    NSRVS--;
                    String serviceName = in.readString();
                    if (serviceName == null) {
                        mReadError = "bad package service name";
                        return;
                    }
                    String processName = version > 9 ? readCommonString(in, version) : null;
                    ServiceState serv = hadData ? pkgState.mServices.get(serviceName) : null;
                    if (serv == null) {
                        serv = new ServiceState(this, pkgName, serviceName, processName, null);
                    }
                    if (!serv.readFromParcel(in)) {
                        return;
                    }
                    if (DEBUG_PARCEL) Slog.d(TAG, "Adding package " + pkgName + " service: "
                            + serviceName + " " + uid + " " + serv);
                    pkgState.mServices.put(serviceName, serv);
                }
            }
        }

        mIndexToCommonString = null;

        if (DEBUG_PARCEL) Slog.d(TAG, "Successfully read procstats!");
    }

    int addLongData(int index, int type, int num) {
        int tableLen = mAddLongTable != null ? mAddLongTable.length : 0;
        if (mAddLongTableSize >= tableLen) {
            int newSize = ArrayUtils.idealIntArraySize(tableLen + 1);
            int[] newTable = new int[newSize];
            if (tableLen > 0) {
                System.arraycopy(mAddLongTable, 0, newTable, 0, tableLen);
            }
            mAddLongTable = newTable;
        }
        if (mAddLongTableSize > 0 && mAddLongTableSize - index != 0) {
            System.arraycopy(mAddLongTable, index, mAddLongTable, index + 1,
                    mAddLongTableSize - index);
        }
        int off = allocLongData(num);
        mAddLongTable[index] = type | off;
        mAddLongTableSize++;
        return off;
    }

    int allocLongData(int num) {
        int whichLongs = mLongs.size()-1;
        long[] longs = mLongs.get(whichLongs);
        if (mNextLong + num > longs.length) {
            longs = new long[LONGS_SIZE];
            mLongs.add(longs);
            whichLongs++;
            mNextLong = 0;
        }
        int off = (whichLongs<<OFFSET_ARRAY_SHIFT) | (mNextLong<<OFFSET_INDEX_SHIFT);
        mNextLong += num;
        return off;
    }

    boolean validateLongOffset(int off) {
        int arr = (off>>OFFSET_ARRAY_SHIFT)&OFFSET_ARRAY_MASK;
        if (arr >= mLongs.size()) {
            return false;
        }
        int idx = (off>>OFFSET_INDEX_SHIFT)&OFFSET_INDEX_MASK;
        if (idx >= LONGS_SIZE) {
            return false;
        }
        if (DEBUG_PARCEL) Slog.d(TAG, "Validated long " + printLongOffset(off)
                + ": " + getLong(off, 0));
        return true;
    }

    static String printLongOffset(int off) {
        StringBuilder sb = new StringBuilder(16);
        sb.append("a"); sb.append((off>>OFFSET_ARRAY_SHIFT)&OFFSET_ARRAY_MASK);
        sb.append("i"); sb.append((off>>OFFSET_INDEX_SHIFT)&OFFSET_INDEX_MASK);
        sb.append("t"); sb.append((off>>OFFSET_TYPE_SHIFT)&OFFSET_TYPE_MASK);
        return sb.toString();
    }

    void setLong(int off, int index, long value) {
        long[] longs = mLongs.get((off>>OFFSET_ARRAY_SHIFT)&OFFSET_ARRAY_MASK);
        longs[index + ((off>>OFFSET_INDEX_SHIFT)&OFFSET_INDEX_MASK)] = value;
    }

    long getLong(int off, int index) {
        long[] longs = mLongs.get((off>>OFFSET_ARRAY_SHIFT)&OFFSET_ARRAY_MASK);
        return longs[index + ((off>>OFFSET_INDEX_SHIFT)&OFFSET_INDEX_MASK)];
    }

    static int binarySearch(int[] array, int size, int value) {
        int lo = 0;
        int hi = size - 1;

        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int midVal = (array[mid] >> OFFSET_TYPE_SHIFT) & OFFSET_TYPE_MASK;

            if (midVal < value) {
                lo = mid + 1;
            } else if (midVal > value) {
                hi = mid - 1;
            } else {
                return mid;  // value found
            }
        }
        return ~lo;  // value not present
    }

    public PackageState getPackageStateLocked(String packageName, int uid) {
        PackageState as = mPackages.get(packageName, uid);
        if (as != null) {
            return as;
        }
        as = new PackageState(packageName, uid);
        mPackages.put(packageName, uid, as);
        return as;
    }

    public ProcessState getProcessStateLocked(String packageName, int uid, String processName) {
        final PackageState pkgState = getPackageStateLocked(packageName, uid);
        ProcessState ps = pkgState.mProcesses.get(processName);
        if (ps != null) {
            return ps;
        }
        ProcessState commonProc = mProcesses.get(processName, uid);
        if (commonProc == null) {
            commonProc = new ProcessState(this, packageName, uid, processName);
            mProcesses.put(processName, uid, commonProc);
            if (DEBUG) Slog.d(TAG, "GETPROC created new common " + commonProc);
        }
        if (!commonProc.mMultiPackage) {
            if (packageName.equals(commonProc.mPackage)) {
                // This common process is not in use by multiple packages, and
                // is for the calling package, so we can just use it directly.
                ps = commonProc;
                if (DEBUG) Slog.d(TAG, "GETPROC also using for pkg " + commonProc);
            } else {
                if (DEBUG) Slog.d(TAG, "GETPROC need to split common proc!");
                // This common process has not been in use by multiple packages,
                // but it was created for a different package than the caller.
                // We need to convert it to a multi-package process.
                commonProc.mMultiPackage = true;
                // To do this, we need to make two new process states, one a copy
                // of the current state for the process under the original package
                // name, and the second a free new process state for it as the
                // new package name.
                long now = SystemClock.uptimeMillis();
                // First let's make a copy of the current process state and put
                // that under the now unique state for its original package name.
                final PackageState commonPkgState = getPackageStateLocked(commonProc.mPackage, uid);
                if (commonPkgState != null) {
                    ProcessState cloned = commonProc.clone(commonProc.mPackage, now);
                    if (DEBUG) Slog.d(TAG, "GETPROC setting clone to pkg " + commonProc.mPackage
                            + ": " + cloned);
                    commonPkgState.mProcesses.put(commonProc.mName, cloned);
                    // If this has active services, we need to update their process pointer
                    // to point to the new package-specific process state.
                    for (int i=commonPkgState.mServices.size()-1; i>=0; i--) {
                        ServiceState ss = commonPkgState.mServices.valueAt(i);
                        if (ss.mProc == commonProc) {
                            if (DEBUG) Slog.d(TAG, "GETPROC switching service to cloned: "
                                    + ss);
                            ss.mProc = cloned;
                        } else if (DEBUG) {
                            Slog.d(TAG, "GETPROC leaving proc of " + ss);
                        }
                    }
                } else {
                    Slog.w(TAG, "Cloning proc state: no package state " + commonProc.mPackage
                            + "/" + uid + " for proc " + commonProc.mName);
                }
                // And now make a fresh new process state for the new package name.
                ps = new ProcessState(commonProc, packageName, uid, processName, now);
                if (DEBUG) Slog.d(TAG, "GETPROC created new pkg " + ps);
            }
        } else {
            // The common process is for multiple packages, we need to create a
            // separate object for the per-package data.
            ps = new ProcessState(commonProc, packageName, uid, processName,
                    SystemClock.uptimeMillis());
            if (DEBUG) Slog.d(TAG, "GETPROC created new pkg " + ps);
        }
        pkgState.mProcesses.put(processName, ps);
        if (DEBUG) Slog.d(TAG, "GETPROC adding new pkg " + ps);
        return ps;
    }

    public ProcessStats.ServiceState getServiceStateLocked(String packageName, int uid,
            String processName, String className) {
        final ProcessStats.PackageState as = getPackageStateLocked(packageName, uid);
        ProcessStats.ServiceState ss = as.mServices.get(className);
        if (ss != null) {
            if (DEBUG) Slog.d(TAG, "GETSVC: returning existing " + ss);
            return ss;
        }
        final ProcessStats.ProcessState ps = processName != null
                ? getProcessStateLocked(packageName, uid, processName) : null;
        ss = new ProcessStats.ServiceState(this, packageName, className, processName, ps);
        as.mServices.put(className, ss);
        if (DEBUG) Slog.d(TAG, "GETSVC: creating " + ss + " in " + ps);
        return ss;
    }

    private void dumpProcessInternalLocked(PrintWriter pw, String prefix, ProcessState proc,
            boolean dumpAll) {
        if (dumpAll) {
            pw.print(prefix); pw.print("myID=");
                    pw.print(Integer.toHexString(System.identityHashCode(proc)));
                    pw.print(" mCommonProcess=");
                    pw.print(Integer.toHexString(System.identityHashCode(proc.mCommonProcess)));
                    pw.print(" mPackage="); pw.println(proc.mPackage);
            if (proc.mMultiPackage) {
                pw.print(prefix); pw.print("mMultiPackage="); pw.println(proc.mMultiPackage);
            }
            if (proc != proc.mCommonProcess) {
                pw.print(prefix); pw.print("Common Proc: "); pw.print(proc.mCommonProcess.mName);
                        pw.print("/"); pw.print(proc.mCommonProcess.mUid);
                        pw.print(" pkg="); pw.println(proc.mCommonProcess.mPackage);
            }
        }
        if (proc.mActive) {
            pw.print(prefix); pw.print("mActive="); pw.println(proc.mActive);
        }
        if (proc.mDead) {
            pw.print(prefix); pw.print("mDead="); pw.println(proc.mDead);
        }
        if (proc.mNumActiveServices != 0 || proc.mNumStartedServices != 0) {
            pw.print(prefix); pw.print("mNumActiveServices="); pw.print(proc.mNumActiveServices);
                    pw.print(" mNumStartedServices=");
                    pw.println(proc.mNumStartedServices);
        }
    }

    public void dumpLocked(PrintWriter pw, String reqPackage, long now, boolean dumpSummary,
            boolean dumpAll, boolean activeOnly) {
        long totalTime = dumpSingleTime(null, null, mMemFactorDurations, mMemFactor,
                mStartTime, now);
        ArrayMap<String, SparseArray<PackageState>> pkgMap = mPackages.getMap();
        boolean printedHeader = false;
        boolean sepNeeded = false;
        for (int ip=0; ip<pkgMap.size(); ip++) {
            final String pkgName = pkgMap.keyAt(ip);
            final SparseArray<PackageState> uids = pkgMap.valueAt(ip);
            for (int iu=0; iu<uids.size(); iu++) {
                final int uid = uids.keyAt(iu);
                final PackageState pkgState = uids.valueAt(iu);
                final int NPROCS = pkgState.mProcesses.size();
                final int NSRVS = pkgState.mServices.size();
                final boolean pkgMatch = reqPackage == null || reqPackage.equals(pkgName);
                if (!pkgMatch) {
                    boolean procMatch = false;
                    for (int iproc=0; iproc<NPROCS; iproc++) {
                        ProcessState proc = pkgState.mProcesses.valueAt(iproc);
                        if (reqPackage.equals(proc.mName)) {
                            procMatch = true;
                            break;
                        }
                    }
                    if (!procMatch) {
                        continue;
                    }
                }
                if (NPROCS > 0 || NSRVS > 0) {
                    if (!printedHeader) {
                        pw.println("Per-Package Stats:");
                        printedHeader = true;
                        sepNeeded = true;
                    }
                    pw.print("  * "); pw.print(pkgName); pw.print(" / ");
                            UserHandle.formatUid(pw, uid); pw.println(":");
                }
                if (!dumpSummary || dumpAll) {
                    for (int iproc=0; iproc<NPROCS; iproc++) {
                        ProcessState proc = pkgState.mProcesses.valueAt(iproc);
                        if (!pkgMatch && !reqPackage.equals(proc.mName)) {
                            continue;
                        }
                        if (activeOnly && !proc.isInUse()) {
                            pw.print("      (Not active: ");
                                    pw.print(pkgState.mProcesses.keyAt(iproc)); pw.println(")");
                            continue;
                        }
                        pw.print("      Process ");
                        pw.print(pkgState.mProcesses.keyAt(iproc));
                        if (proc.mCommonProcess.mMultiPackage) {
                            pw.print(" (multi, ");
                        } else {
                            pw.print(" (unique, ");
                        }
                        pw.print(proc.mDurationsTableSize);
                        pw.print(" entries)");
                        pw.println(":");
                        dumpProcessState(pw, "        ", proc, ALL_SCREEN_ADJ, ALL_MEM_ADJ,
                                ALL_PROC_STATES, now);
                        dumpProcessPss(pw, "        ", proc, ALL_SCREEN_ADJ, ALL_MEM_ADJ,
                                ALL_PROC_STATES);
                        dumpProcessInternalLocked(pw, "        ", proc, dumpAll);
                    }
                } else {
                    ArrayList<ProcessState> procs = new ArrayList<ProcessState>();
                    for (int iproc=0; iproc<NPROCS; iproc++) {
                        ProcessState proc = pkgState.mProcesses.valueAt(iproc);
                        if (!pkgMatch && !reqPackage.equals(proc.mName)) {
                            continue;
                        }
                        if (activeOnly && !proc.isInUse()) {
                            continue;
                        }
                        procs.add(proc);
                    }
                    dumpProcessSummaryLocked(pw, "      ", procs, ALL_SCREEN_ADJ, ALL_MEM_ADJ,
                            NON_CACHED_PROC_STATES, now, totalTime);
                }
                for (int isvc=0; isvc<NSRVS; isvc++) {
                    ServiceState svc = pkgState.mServices.valueAt(isvc);
                    if (!pkgMatch && !reqPackage.equals(svc.mProcessName)) {
                        continue;
                    }
                    if (activeOnly && !svc.isInUse()) {
                        pw.print("      (Not active: ");
                                pw.print(pkgState.mServices.keyAt(isvc)); pw.println(")");
                        continue;
                    }
                    if (dumpAll) {
                        pw.print("      Service ");
                    } else {
                        pw.print("      * ");
                    }
                    pw.print(pkgState.mServices.keyAt(isvc));
                    pw.println(":");
                    pw.print("        Process: "); pw.println(svc.mProcessName);
                    dumpServiceStats(pw, "        ", "          ", "    ", "Running", svc,
                            svc.mRunCount, ServiceState.SERVICE_RUN, svc.mRunState,
                            svc.mRunStartTime, now, totalTime, !dumpSummary || dumpAll);
                    dumpServiceStats(pw, "        ", "          ", "    ", "Started", svc,
                            svc.mStartedCount, ServiceState.SERVICE_STARTED, svc.mStartedState,
                            svc.mStartedStartTime, now, totalTime, !dumpSummary || dumpAll);
                    dumpServiceStats(pw, "        ", "          ", "      ", "Bound", svc,
                            svc.mBoundCount, ServiceState.SERVICE_BOUND, svc.mBoundState,
                            svc.mBoundStartTime, now, totalTime, !dumpSummary || dumpAll);
                    dumpServiceStats(pw, "        ", "          ", "  ", "Executing", svc,
                            svc.mExecCount, ServiceState.SERVICE_EXEC, svc.mExecState,
                            svc.mExecStartTime, now, totalTime, !dumpSummary || dumpAll);
                    if (dumpAll) {
                        if (svc.mOwner != null) {
                            pw.print("        mOwner="); pw.println(svc.mOwner);
                        }
                        if (svc.mStarted || svc.mRestarting) {
                            pw.print("        mStarted="); pw.print(svc.mStarted);
                            pw.print(" mRestarting="); pw.println(svc.mRestarting);
                        }
                    }
                }
            }
        }

        ArrayMap<String, SparseArray<ProcessState>> procMap = mProcesses.getMap();
        printedHeader = false;
        int numShownProcs = 0, numTotalProcs = 0;
        for (int ip=0; ip<procMap.size(); ip++) {
            String procName = procMap.keyAt(ip);
            SparseArray<ProcessState> uids = procMap.valueAt(ip);
            for (int iu=0; iu<uids.size(); iu++) {
                int uid = uids.keyAt(iu);
                numTotalProcs++;
                ProcessState proc = uids.valueAt(iu);
                if (proc.mDurationsTableSize == 0 && proc.mCurState == STATE_NOTHING
                        && proc.mPssTableSize == 0) {
                    continue;
                }
                if (!proc.mMultiPackage) {
                    continue;
                }
                if (reqPackage != null && !reqPackage.equals(procName)
                        && !reqPackage.equals(proc.mPackage)) {
                    continue;
                }
                numShownProcs++;
                if (sepNeeded) {
                    pw.println();
                }
                sepNeeded = true;
                if (!printedHeader) {
                    pw.println("Multi-Package Common Processes:");
                    printedHeader = true;
                }
                if (activeOnly && !proc.isInUse()) {
                    pw.print("      (Not active: "); pw.print(procName); pw.println(")");
                    continue;
                }
                pw.print("  * "); pw.print(procName); pw.print(" / ");
                        UserHandle.formatUid(pw, uid);
                        pw.print(" ("); pw.print(proc.mDurationsTableSize);
                        pw.print(" entries)"); pw.println(":");
                dumpProcessState(pw, "        ", proc, ALL_SCREEN_ADJ, ALL_MEM_ADJ,
                        ALL_PROC_STATES, now);
                dumpProcessPss(pw, "        ", proc, ALL_SCREEN_ADJ, ALL_MEM_ADJ,
                        ALL_PROC_STATES);
                dumpProcessInternalLocked(pw, "        ", proc, dumpAll);
            }
        }
        if (dumpAll) {
            pw.println();
            pw.print("  Total procs: "); pw.print(numShownProcs);
                    pw.print(" shown of "); pw.print(numTotalProcs); pw.println(" total");
        }

        if (sepNeeded) {
            pw.println();
        }
        if (dumpSummary) {
            pw.println("Summary:");
            dumpSummaryLocked(pw, reqPackage, now, activeOnly);
        } else {
            dumpTotalsLocked(pw, now);
        }

        if (dumpAll) {
            pw.println();
            pw.println("Internal state:");
            pw.print("  Num long arrays: "); pw.println(mLongs.size());
            pw.print("  Next long entry: "); pw.println(mNextLong);
            pw.print("  mRunning="); pw.println(mRunning);
        }
    }

    public static long dumpSingleServiceTime(PrintWriter pw, String prefix, ServiceState service,
            int serviceType, int curState, long curStartTime, long now) {
        long totalTime = 0;
        int printedScreen = -1;
        for (int iscreen=0; iscreen<ADJ_COUNT; iscreen+=ADJ_SCREEN_MOD) {
            int printedMem = -1;
            for (int imem=0; imem<ADJ_MEM_FACTOR_COUNT; imem++) {
                int state = imem+iscreen;
                long time = service.getDuration(serviceType, curState, curStartTime,
                        state, now);
                String running = "";
                if (curState == state && pw != null) {
                    running = " (running)";
                }
                if (time != 0) {
                    if (pw != null) {
                        pw.print(prefix);
                        printScreenLabel(pw, printedScreen != iscreen
                                ? iscreen : STATE_NOTHING);
                        printedScreen = iscreen;
                        printMemLabel(pw, printedMem != imem ? imem : STATE_NOTHING, (char)0);
                        printedMem = imem;
                        pw.print(": ");
                        TimeUtils.formatDuration(time, pw); pw.println(running);
                    }
                    totalTime += time;
                }
            }
        }
        if (totalTime != 0 && pw != null) {
            pw.print(prefix);
            pw.print("    TOTAL: ");
            TimeUtils.formatDuration(totalTime, pw);
            pw.println();
        }
        return totalTime;
    }

    void dumpServiceStats(PrintWriter pw, String prefix, String prefixInner,
            String headerPrefix, String header, ServiceState service,
            int count, int serviceType, int state, long startTime, long now, long totalTime,
            boolean dumpAll) {
        if (count != 0) {
            if (dumpAll) {
                pw.print(prefix); pw.print(header);
                pw.print(" op count "); pw.print(count); pw.println(":");
                dumpSingleServiceTime(pw, prefixInner, service, serviceType, state, startTime,
                        now);
            } else {
                long myTime = dumpSingleServiceTime(null, null, service, serviceType, state,
                        startTime, now);
                pw.print(prefix); pw.print(headerPrefix); pw.print(header);
                pw.print(" count "); pw.print(count);
                pw.print(" / time ");
                printPercent(pw, (double)myTime/(double)totalTime);
                pw.println();
            }
        }
    }

    public void dumpSummaryLocked(PrintWriter pw, String reqPackage, long now, boolean activeOnly) {
        long totalTime = dumpSingleTime(null, null, mMemFactorDurations, mMemFactor,
                mStartTime, now);
        dumpFilteredSummaryLocked(pw, null, "  ", ALL_SCREEN_ADJ, ALL_MEM_ADJ,
                ALL_PROC_STATES, NON_CACHED_PROC_STATES, now, totalTime, reqPackage, activeOnly);
        pw.println();
        dumpTotalsLocked(pw, now);
    }

    void dumpTotalsLocked(PrintWriter pw, long now) {
        pw.println("Run time Stats:");
        dumpSingleTime(pw, "  ", mMemFactorDurations, mMemFactor, mStartTime, now);
        pw.println();
        pw.print("          Start time: ");
        pw.print(DateFormat.format("yyyy-MM-dd HH:mm:ss", mTimePeriodStartClock));
        pw.println();
        pw.print("  Total elapsed time: ");
        TimeUtils.formatDuration(
                (mRunning ? SystemClock.elapsedRealtime() : mTimePeriodEndRealtime)
                        - mTimePeriodStartRealtime, pw);
        boolean partial = true;
        if ((mFlags&FLAG_SHUTDOWN) != 0) {
            pw.print(" (shutdown)");
            partial = false;
        }
        if ((mFlags&FLAG_SYSPROPS) != 0) {
            pw.print(" (sysprops)");
            partial = false;
        }
        if ((mFlags&FLAG_COMPLETE) != 0) {
            pw.print(" (complete)");
            partial = false;
        }
        if (partial) {
            pw.print(" (partial)");
        }
        pw.print(' ');
        pw.print(mRuntime);
        pw.print(' ');
        pw.print(mWebView);
        pw.println();
    }

    void dumpFilteredSummaryLocked(PrintWriter pw, String header, String prefix,
            int[] screenStates, int[] memStates, int[] procStates,
            int[] sortProcStates, long now, long totalTime, String reqPackage, boolean activeOnly) {
        ArrayList<ProcessState> procs = collectProcessesLocked(screenStates, memStates,
                procStates, sortProcStates, now, reqPackage, activeOnly);
        if (procs.size() > 0) {
            if (header != null) {
                pw.println();
                pw.println(header);
            }
            dumpProcessSummaryLocked(pw, prefix, procs, screenStates, memStates,
                    sortProcStates, now, totalTime);
        }
    }

    public ArrayList<ProcessState> collectProcessesLocked(int[] screenStates, int[] memStates,
            int[] procStates, int sortProcStates[], long now, String reqPackage,
            boolean activeOnly) {
        final ArraySet<ProcessState> foundProcs = new ArraySet<ProcessState>();
        final ArrayMap<String, SparseArray<PackageState>> pkgMap = mPackages.getMap();
        for (int ip=0; ip<pkgMap.size(); ip++) {
            final String pkgName = pkgMap.keyAt(ip);
            final SparseArray<PackageState> procs = pkgMap.valueAt(ip);
            for (int iu=0; iu<procs.size(); iu++) {
                final PackageState state = procs.valueAt(iu);
                final int NPROCS = state.mProcesses.size();
                final boolean pkgMatch = reqPackage == null || reqPackage.equals(pkgName);
                for (int iproc=0; iproc<NPROCS; iproc++) {
                    final ProcessState proc = state.mProcesses.valueAt(iproc);
                    if (!pkgMatch && !reqPackage.equals(proc.mName)) {
                        continue;
                    }
                    if (activeOnly && !proc.isInUse()) {
                        continue;
                    }
                    foundProcs.add(proc.mCommonProcess);
                }
            }
        }
        ArrayList<ProcessState> outProcs = new ArrayList<ProcessState>(foundProcs.size());
        for (int i=0; i<foundProcs.size(); i++) {
            ProcessState proc = foundProcs.valueAt(i);
            if (computeProcessTimeLocked(proc, screenStates, memStates, procStates, now) > 0) {
                outProcs.add(proc);
                if (procStates != sortProcStates) {
                    computeProcessTimeLocked(proc, screenStates, memStates, sortProcStates, now);
                }
            }
        }
        Collections.sort(outProcs, new Comparator<ProcessState>() {
            @Override
            public int compare(ProcessState lhs, ProcessState rhs) {
                if (lhs.mTmpTotalTime < rhs.mTmpTotalTime) {
                    return -1;
                } else if (lhs.mTmpTotalTime > rhs.mTmpTotalTime) {
                    return 1;
                }
                return 0;
            }
        });
        return outProcs;
    }

    String collapseString(String pkgName, String itemName) {
        if (itemName.startsWith(pkgName)) {
            final int ITEMLEN = itemName.length();
            final int PKGLEN = pkgName.length();
            if (ITEMLEN == PKGLEN) {
                return "";
            } else if (ITEMLEN >= PKGLEN) {
                if (itemName.charAt(PKGLEN) == '.') {
                    return itemName.substring(PKGLEN);
                }
            }
        }
        return itemName;
    }

    public void dumpCheckinLocked(PrintWriter pw, String reqPackage) {
        final long now = SystemClock.uptimeMillis();
        ArrayMap<String, SparseArray<PackageState>> pkgMap = mPackages.getMap();
        pw.println("vers,3");
        pw.print("period,"); pw.print(mTimePeriodStartClockStr);
        pw.print(","); pw.print(mTimePeriodStartRealtime); pw.print(",");
        pw.print(mRunning ? SystemClock.elapsedRealtime() : mTimePeriodEndRealtime);
        boolean partial = true;
        if ((mFlags&FLAG_SHUTDOWN) != 0) {
            pw.print(",shutdown");
            partial = false;
        }
        if ((mFlags&FLAG_SYSPROPS) != 0) {
            pw.print(",sysprops");
            partial = false;
        }
        if ((mFlags&FLAG_COMPLETE) != 0) {
            pw.print(",complete");
            partial = false;
        }
        if (partial) {
            pw.print(",partial");
        }
        pw.println();
        pw.print("config,"); pw.print(mRuntime); pw.print(','); pw.println(mWebView);
        for (int ip=0; ip<pkgMap.size(); ip++) {
            String pkgName = pkgMap.keyAt(ip);
            if (reqPackage != null && !reqPackage.equals(pkgName)) {
                continue;
            }
            SparseArray<PackageState> uids = pkgMap.valueAt(ip);
            for (int iu=0; iu<uids.size(); iu++) {
                int uid = uids.keyAt(iu);
                PackageState pkgState = uids.valueAt(iu);
                final int NPROCS = pkgState.mProcesses.size();
                final int NSRVS = pkgState.mServices.size();
                for (int iproc=0; iproc<NPROCS; iproc++) {
                    ProcessState proc = pkgState.mProcesses.valueAt(iproc);
                    pw.print("pkgproc,");
                    pw.print(pkgName);
                    pw.print(",");
                    pw.print(uid);
                    pw.print(",");
                    pw.print(collapseString(pkgName, pkgState.mProcesses.keyAt(iproc)));
                    dumpAllProcessStateCheckin(pw, proc, now);
                    pw.println();
                    if (proc.mPssTableSize > 0) {
                        pw.print("pkgpss,");
                        pw.print(pkgName);
                        pw.print(",");
                        pw.print(uid);
                        pw.print(",");
                        pw.print(collapseString(pkgName, pkgState.mProcesses.keyAt(iproc)));
                        dumpAllProcessPssCheckin(pw, proc);
                        pw.println();
                    }
                    if (proc.mNumExcessiveWake > 0 || proc.mNumExcessiveCpu > 0
                            || proc.mNumCachedKill > 0) {
                        pw.print("pkgkills,");
                        pw.print(pkgName);
                        pw.print(",");
                        pw.print(uid);
                        pw.print(",");
                        pw.print(collapseString(pkgName, pkgState.mProcesses.keyAt(iproc)));
                        pw.print(",");
                        pw.print(proc.mNumExcessiveWake);
                        pw.print(",");
                        pw.print(proc.mNumExcessiveCpu);
                        pw.print(",");
                        pw.print(proc.mNumCachedKill);
                        pw.print(",");
                        pw.print(proc.mMinCachedKillPss);
                        pw.print(":");
                        pw.print(proc.mAvgCachedKillPss);
                        pw.print(":");
                        pw.print(proc.mMaxCachedKillPss);
                        pw.println();
                    }
                }
                for (int isvc=0; isvc<NSRVS; isvc++) {
                    String serviceName = collapseString(pkgName,
                            pkgState.mServices.keyAt(isvc));
                    ServiceState svc = pkgState.mServices.valueAt(isvc);
                    dumpServiceTimeCheckin(pw, "pkgsvc-run", pkgName, uid, serviceName,
                            svc, ServiceState.SERVICE_RUN, svc.mRunCount,
                            svc.mRunState, svc.mRunStartTime, now);
                    dumpServiceTimeCheckin(pw, "pkgsvc-start", pkgName, uid, serviceName,
                            svc, ServiceState.SERVICE_STARTED, svc.mStartedCount,
                            svc.mStartedState, svc.mStartedStartTime, now);
                    dumpServiceTimeCheckin(pw, "pkgsvc-bound", pkgName, uid, serviceName,
                            svc, ServiceState.SERVICE_BOUND, svc.mBoundCount,
                            svc.mBoundState, svc.mBoundStartTime, now);
                    dumpServiceTimeCheckin(pw, "pkgsvc-exec", pkgName, uid, serviceName,
                            svc, ServiceState.SERVICE_EXEC, svc.mExecCount,
                            svc.mExecState, svc.mExecStartTime, now);
                }
            }
        }

        ArrayMap<String, SparseArray<ProcessState>> procMap = mProcesses.getMap();
        for (int ip=0; ip<procMap.size(); ip++) {
            String procName = procMap.keyAt(ip);
            SparseArray<ProcessState> uids = procMap.valueAt(ip);
            for (int iu=0; iu<uids.size(); iu++) {
                int uid = uids.keyAt(iu);
                ProcessState procState = uids.valueAt(iu);
                if (procState.mDurationsTableSize > 0) {
                    pw.print("proc,");
                    pw.print(procName);
                    pw.print(",");
                    pw.print(uid);
                    dumpAllProcessStateCheckin(pw, procState, now);
                    pw.println();
                }
                if (procState.mPssTableSize > 0) {
                    pw.print("pss,");
                    pw.print(procName);
                    pw.print(",");
                    pw.print(uid);
                    dumpAllProcessPssCheckin(pw, procState);
                    pw.println();
                }
                if (procState.mNumExcessiveWake > 0 || procState.mNumExcessiveCpu > 0
                        || procState.mNumCachedKill > 0) {
                    pw.print("kills,");
                    pw.print(procName);
                    pw.print(",");
                    pw.print(uid);
                    pw.print(",");
                    pw.print(procState.mNumExcessiveWake);
                    pw.print(",");
                    pw.print(procState.mNumExcessiveCpu);
                    pw.print(",");
                    pw.print(procState.mNumCachedKill);
                    pw.print(",");
                    pw.print(procState.mMinCachedKillPss);
                    pw.print(":");
                    pw.print(procState.mAvgCachedKillPss);
                    pw.print(":");
                    pw.print(procState.mMaxCachedKillPss);
                    pw.println();
                }
            }
        }
        pw.print("total");
        dumpAdjTimesCheckin(pw, ",", mMemFactorDurations, mMemFactor,
                mStartTime, now);
        pw.println();
    }

    public static class DurationsTable {
        public final ProcessStats mStats;
        public final String mName;
        public int[] mDurationsTable;
        public int mDurationsTableSize;

        public DurationsTable(ProcessStats stats, String name) {
            mStats = stats;
            mName = name;
        }

        void copyDurationsTo(DurationsTable other) {
            if (mDurationsTable != null) {
                mStats.mAddLongTable = new int[mDurationsTable.length];
                mStats.mAddLongTableSize = 0;
                for (int i=0; i<mDurationsTableSize; i++) {
                    int origEnt = mDurationsTable[i];
                    int type = (origEnt>>OFFSET_TYPE_SHIFT)&OFFSET_TYPE_MASK;
                    int newOff = mStats.addLongData(i, type, 1);
                    mStats.mAddLongTable[i] = newOff | type;
                    mStats.setLong(newOff, 0, mStats.getLong(origEnt, 0));
                }
                other.mDurationsTable = mStats.mAddLongTable;
                other.mDurationsTableSize = mStats.mAddLongTableSize;
            } else {
                other.mDurationsTable = null;
                other.mDurationsTableSize = 0;
            }
        }

        void addDurations(DurationsTable other) {
            for (int i=0; i<other.mDurationsTableSize; i++) {
                int ent = other.mDurationsTable[i];
                int state = (ent>>OFFSET_TYPE_SHIFT)&OFFSET_TYPE_MASK;
                if (DEBUG) Slog.d(TAG, "Adding state " + state + " duration "
                        + other.mStats.getLong(ent, 0));
                addDuration(state, other.mStats.getLong(ent, 0));
            }
        }

        void resetDurationsSafely() {
            mDurationsTable = null;
            mDurationsTableSize = 0;
        }

        void writeDurationsToParcel(Parcel out) {
            out.writeInt(mDurationsTableSize);
            for (int i=0; i<mDurationsTableSize; i++) {
                if (DEBUG_PARCEL) Slog.i(TAG, "Writing in " + mName + " dur #" + i + ": "
                        + printLongOffset(mDurationsTable[i]));
                out.writeInt(mDurationsTable[i]);
            }
        }

        boolean readDurationsFromParcel(Parcel in) {
            mDurationsTable = mStats.readTableFromParcel(in, mName, "durations");
            if (mDurationsTable == BAD_TABLE) {
                return false;
            }
            mDurationsTableSize = mDurationsTable != null ? mDurationsTable.length : 0;
            return true;
        }

        void addDuration(int state, long dur) {
            int idx = binarySearch(mDurationsTable, mDurationsTableSize, state);
            int off;
            if (idx >= 0) {
                off = mDurationsTable[idx];
            } else {
                mStats.mAddLongTable = mDurationsTable;
                mStats.mAddLongTableSize = mDurationsTableSize;
                off = mStats.addLongData(~idx, state, 1);
                mDurationsTable = mStats.mAddLongTable;
                mDurationsTableSize = mStats.mAddLongTableSize;
            }
            long[] longs = mStats.mLongs.get((off>>OFFSET_ARRAY_SHIFT)&OFFSET_ARRAY_MASK);
            if (DEBUG) Slog.d(TAG, "Duration of " + mName + " state " + state + " inc by " + dur
                    + " from " + longs[(off>>OFFSET_INDEX_SHIFT)&OFFSET_INDEX_MASK]);
            longs[(off>>OFFSET_INDEX_SHIFT)&OFFSET_INDEX_MASK] += dur;
        }

        long getDuration(int state, long now) {
            int idx = binarySearch(mDurationsTable, mDurationsTableSize, state);
            return idx >= 0 ? mStats.getLong(mDurationsTable[idx], 0) : 0;
        }
    }

    public static final class ProcessState extends DurationsTable {
        public final ProcessState mCommonProcess;
        public final String mPackage;
        public final int mUid;

        //final long[] mDurations = new long[STATE_COUNT*ADJ_COUNT];
        int mCurState = STATE_NOTHING;
        long mStartTime;

        int mLastPssState = STATE_NOTHING;
        long mLastPssTime;
        int[] mPssTable;
        int mPssTableSize;

        boolean mActive;
        int mNumActiveServices;
        int mNumStartedServices;

        int mNumExcessiveWake;
        int mNumExcessiveCpu;

        int mNumCachedKill;
        long mMinCachedKillPss;
        long mAvgCachedKillPss;
        long mMaxCachedKillPss;

        boolean mMultiPackage;
        boolean mDead;

        public long mTmpTotalTime;

        /**
         * Create a new top-level process state, for the initial case where there is only
         * a single package running in a process.  The initial state is not running.
         */
        public ProcessState(ProcessStats processStats, String pkg, int uid, String name) {
            super(processStats, name);
            mCommonProcess = this;
            mPackage = pkg;
            mUid = uid;
        }

        /**
         * Create a new per-package process state for an existing top-level process
         * state.  The current running state of the top-level process is also copied,
         * marked as started running at 'now'.
         */
        public ProcessState(ProcessState commonProcess, String pkg, int uid, String name,
                long now) {
            super(commonProcess.mStats, name);
            mCommonProcess = commonProcess;
            mPackage = pkg;
            mUid = uid;
            mCurState = commonProcess.mCurState;
            mStartTime = now;
        }

        ProcessState clone(String pkg, long now) {
            ProcessState pnew = new ProcessState(this, pkg, mUid, mName, now);
            copyDurationsTo(pnew);
            if (mPssTable != null) {
                mStats.mAddLongTable = new int[mPssTable.length];
                mStats.mAddLongTableSize = 0;
                for (int i=0; i<mPssTableSize; i++) {
                    int origEnt = mPssTable[i];
                    int type = (origEnt>>OFFSET_TYPE_SHIFT)&OFFSET_TYPE_MASK;
                    int newOff = mStats.addLongData(i, type, PSS_COUNT);
                    mStats.mAddLongTable[i] = newOff | type;
                    for (int j=0; j<PSS_COUNT; j++) {
                        mStats.setLong(newOff, j, mStats.getLong(origEnt, j));
                    }
                }
                pnew.mPssTable = mStats.mAddLongTable;
                pnew.mPssTableSize = mStats.mAddLongTableSize;
            }
            pnew.mNumExcessiveWake = mNumExcessiveWake;
            pnew.mNumExcessiveCpu = mNumExcessiveCpu;
            pnew.mNumCachedKill = mNumCachedKill;
            pnew.mMinCachedKillPss = mMinCachedKillPss;
            pnew.mAvgCachedKillPss = mAvgCachedKillPss;
            pnew.mMaxCachedKillPss = mMaxCachedKillPss;
            pnew.mActive = mActive;
            pnew.mNumActiveServices = mNumActiveServices;
            pnew.mNumStartedServices = mNumStartedServices;
            return pnew;
        }

        void add(ProcessState other) {
            addDurations(other);
            for (int i=0; i<other.mPssTableSize; i++) {
                int ent = other.mPssTable[i];
                int state = (ent>>OFFSET_TYPE_SHIFT)&OFFSET_TYPE_MASK;
                addPss(state, (int) other.mStats.getLong(ent, PSS_SAMPLE_COUNT),
                        other.mStats.getLong(ent, PSS_MINIMUM),
                        other.mStats.getLong(ent, PSS_AVERAGE),
                        other.mStats.getLong(ent, PSS_MAXIMUM),
                        other.mStats.getLong(ent, PSS_USS_MINIMUM),
                        other.mStats.getLong(ent, PSS_USS_AVERAGE),
                        other.mStats.getLong(ent, PSS_USS_MAXIMUM));
            }
            mNumExcessiveWake += other.mNumExcessiveWake;
            mNumExcessiveCpu += other.mNumExcessiveCpu;
            if (other.mNumCachedKill > 0) {
                addCachedKill(other.mNumCachedKill, other.mMinCachedKillPss,
                        other.mAvgCachedKillPss, other.mMaxCachedKillPss);
            }
        }

        void resetSafely(long now) {
            resetDurationsSafely();
            mStartTime = now;
            mLastPssState = STATE_NOTHING;
            mLastPssTime = 0;
            mPssTable = null;
            mPssTableSize = 0;
            mNumExcessiveWake = 0;
            mNumExcessiveCpu = 0;
            mNumCachedKill = 0;
            mMinCachedKillPss = mAvgCachedKillPss = mMaxCachedKillPss = 0;
        }

        void makeDead() {
            mDead = true;
        }

        private void ensureNotDead() {
            if (!mDead) {
                return;
            }
            Slog.wtfStack(TAG, "ProcessState dead: name=" + mName
                    + " pkg=" + mPackage + " uid=" + mUid + " common.name=" + mCommonProcess.mName);
        }

        void writeToParcel(Parcel out, long now) {
            out.writeInt(mMultiPackage ? 1 : 0);
            writeDurationsToParcel(out);
            out.writeInt(mPssTableSize);
            for (int i=0; i<mPssTableSize; i++) {
                if (DEBUG_PARCEL) Slog.i(TAG, "Writing in " + mName + " pss #" + i + ": "
                        + printLongOffset(mPssTable[i]));
                out.writeInt(mPssTable[i]);
            }
            out.writeInt(mNumExcessiveWake);
            out.writeInt(mNumExcessiveCpu);
            out.writeInt(mNumCachedKill);
            if (mNumCachedKill > 0) {
                out.writeLong(mMinCachedKillPss);
                out.writeLong(mAvgCachedKillPss);
                out.writeLong(mMaxCachedKillPss);
            }
        }

        boolean readFromParcel(Parcel in, boolean fully) {
            boolean multiPackage = in.readInt() != 0;
            if (fully) {
                mMultiPackage = multiPackage;
            }
            if (DEBUG_PARCEL) Slog.d(TAG, "Reading durations table...");
            if (!readDurationsFromParcel(in)) {
                return false;
            }
            if (DEBUG_PARCEL) Slog.d(TAG, "Reading pss table...");
            mPssTable = mStats.readTableFromParcel(in, mName, "pss");
            if (mPssTable == BAD_TABLE) {
                return false;
            }
            mPssTableSize = mPssTable != null ? mPssTable.length : 0;
            mNumExcessiveWake = in.readInt();
            mNumExcessiveCpu = in.readInt();
            mNumCachedKill = in.readInt();
            if (mNumCachedKill > 0) {
                mMinCachedKillPss = in.readLong();
                mAvgCachedKillPss = in.readLong();
                mMaxCachedKillPss = in.readLong();
            } else {
                mMinCachedKillPss = mAvgCachedKillPss = mMaxCachedKillPss = 0;
            }
            return true;
        }

        public void makeActive() {
            ensureNotDead();
            mActive = true;
        }

        public void makeInactive() {
            mActive = false;
        }

        public boolean isInUse() {
            return mActive || mNumActiveServices > 0 || mNumStartedServices > 0
                    || mCurState != STATE_NOTHING;
        }

        /**
         * Update the current state of the given list of processes.
         *
         * @param state Current ActivityManager.PROCESS_STATE_*
         * @param memFactor Current mem factor constant.
         * @param now Current time.
         * @param pkgList Processes to update.
         */
        public void setState(int state, int memFactor, long now,
                ArrayMap<String, ProcessState> pkgList) {
            if (state < 0) {
                state = mNumStartedServices > 0
                        ? (STATE_SERVICE_RESTARTING+(memFactor*STATE_COUNT)) : STATE_NOTHING;
            } else {
                state = PROCESS_STATE_TO_STATE[state] + (memFactor*STATE_COUNT);
            }

            // First update the common process.
            mCommonProcess.setState(state, now);

            // If the common process is not multi-package, there is nothing else to do.
            if (!mCommonProcess.mMultiPackage) {
                return;
            }

            if (pkgList != null) {
                for (int ip=pkgList.size()-1; ip>=0; ip--) {
                    pullFixedProc(pkgList, ip).setState(state, now);
                }
            }
        }

        void setState(int state, long now) {
            ensureNotDead();
            if (mCurState != state) {
                //Slog.i(TAG, "Setting state in " + mName + "/" + mPackage + ": " + state);
                commitStateTime(now);
                mCurState = state;
            }
        }

        void commitStateTime(long now) {
            if (mCurState != STATE_NOTHING) {
                long dur = now - mStartTime;
                if (dur > 0) {
                    addDuration(mCurState, dur);
                }
            }
            mStartTime = now;
        }

        void incActiveServices(String serviceName) {
            if (DEBUG && "".equals(mName)) {
                RuntimeException here = new RuntimeException("here");
                here.fillInStackTrace();
                Slog.d(TAG, "incActiveServices: " + this + " service=" + serviceName
                        + " to " + (mNumActiveServices+1), here);
            }
            if (mCommonProcess != this) {
                mCommonProcess.incActiveServices(serviceName);
            }
            mNumActiveServices++;
        }

        void decActiveServices(String serviceName) {
            if (DEBUG && "".equals(mName)) {
                RuntimeException here = new RuntimeException("here");
                here.fillInStackTrace();
                Slog.d(TAG, "decActiveServices: " + this + " service=" + serviceName
                        + " to " + (mNumActiveServices-1), here);
            }
            if (mCommonProcess != this) {
                mCommonProcess.decActiveServices(serviceName);
            }
            mNumActiveServices--;
            if (mNumActiveServices < 0) {
                Slog.wtfStack(TAG, "Proc active services underrun: pkg=" + mPackage
                        + " uid=" + mUid + " proc=" + mName + " service=" + serviceName);
                mNumActiveServices = 0;
            }
        }

        void incStartedServices(int memFactor, long now, String serviceName) {
            if (false) {
                RuntimeException here = new RuntimeException("here");
                here.fillInStackTrace();
                Slog.d(TAG, "incStartedServices: " + this + " service=" + serviceName
                        + " to " + (mNumStartedServices+1), here);
            }
            if (mCommonProcess != this) {
                mCommonProcess.incStartedServices(memFactor, now, serviceName);
            }
            mNumStartedServices++;
            if (mNumStartedServices == 1 && mCurState == STATE_NOTHING) {
                setState(STATE_SERVICE_RESTARTING + (memFactor*STATE_COUNT), now);
            }
        }

        void decStartedServices(int memFactor, long now, String serviceName) {
            if (false) {
                RuntimeException here = new RuntimeException("here");
                here.fillInStackTrace();
                Slog.d(TAG, "decActiveServices: " + this + " service=" + serviceName
                        + " to " + (mNumStartedServices-1), here);
            }
            if (mCommonProcess != this) {
                mCommonProcess.decStartedServices(memFactor, now, serviceName);
            }
            mNumStartedServices--;
            if (mNumStartedServices == 0 && (mCurState%STATE_COUNT) == STATE_SERVICE_RESTARTING) {
                setState(STATE_NOTHING, now);
            } else if (mNumStartedServices < 0) {
                Slog.wtfStack(TAG, "Proc started services underrun: pkg="
                        + mPackage + " uid=" + mUid + " name=" + mName);
                mNumStartedServices = 0;
            }
        }

        public void addPss(long pss, long uss, boolean always,
                ArrayMap<String, ProcessState> pkgList) {
            ensureNotDead();
            if (!always) {
                if (mLastPssState == mCurState && SystemClock.uptimeMillis()
                        < (mLastPssTime+(30*1000))) {
                    return;
                }
            }
            mLastPssState = mCurState;
            mLastPssTime = SystemClock.uptimeMillis();
            if (mCurState != STATE_NOTHING) {
                // First update the common process.
                mCommonProcess.addPss(mCurState, 1, pss, pss, pss, uss, uss, uss);

                // If the common process is not multi-package, there is nothing else to do.
                if (!mCommonProcess.mMultiPackage) {
                    return;
                }

                if (pkgList != null) {
                    for (int ip=pkgList.size()-1; ip>=0; ip--) {
                        pullFixedProc(pkgList, ip).addPss(mCurState, 1,
                                pss, pss, pss, uss, uss, uss);
                    }
                }
            }
        }

        void addPss(int state, int inCount, long minPss, long avgPss, long maxPss, long minUss,
                long avgUss, long maxUss) {
            int idx = binarySearch(mPssTable, mPssTableSize, state);
            int off;
            if (idx >= 0) {
                off = mPssTable[idx];
            } else {
                mStats.mAddLongTable = mPssTable;
                mStats.mAddLongTableSize = mPssTableSize;
                off = mStats.addLongData(~idx, state, PSS_COUNT);
                mPssTable = mStats.mAddLongTable;
                mPssTableSize = mStats.mAddLongTableSize;
            }
            long[] longs = mStats.mLongs.get((off>>OFFSET_ARRAY_SHIFT)&OFFSET_ARRAY_MASK);
            idx = (off>>OFFSET_INDEX_SHIFT)&OFFSET_INDEX_MASK;
            long count = longs[idx+PSS_SAMPLE_COUNT];
            if (count == 0) {
                longs[idx+PSS_SAMPLE_COUNT] = inCount;
                longs[idx+PSS_MINIMUM] = minPss;
                longs[idx+PSS_AVERAGE] = avgPss;
                longs[idx+PSS_MAXIMUM] = maxPss;
                longs[idx+PSS_USS_MINIMUM] = minUss;
                longs[idx+PSS_USS_AVERAGE] = avgUss;
                longs[idx+PSS_USS_MAXIMUM] = maxUss;
            } else {
                longs[idx+PSS_SAMPLE_COUNT] = count+inCount;
                if (longs[idx+PSS_MINIMUM] > minPss) {
                    longs[idx+PSS_MINIMUM] = minPss;
                }
                longs[idx+PSS_AVERAGE] = (long)(
                        ((longs[idx+PSS_AVERAGE]*(double)count)+(avgPss*(double)inCount))
                                / (count+inCount) );
                if (longs[idx+PSS_MAXIMUM] < maxPss) {
                    longs[idx+PSS_MAXIMUM] = maxPss;
                }
                if (longs[idx+PSS_USS_MINIMUM] > minUss) {
                    longs[idx+PSS_USS_MINIMUM] = minUss;
                }
                longs[idx+PSS_USS_AVERAGE] = (long)(
                        ((longs[idx+PSS_USS_AVERAGE]*(double)count)+(avgUss*(double)inCount))
                                / (count+inCount) );
                if (longs[idx+PSS_USS_MAXIMUM] < maxUss) {
                    longs[idx+PSS_USS_MAXIMUM] = maxUss;
                }
            }
        }

        public void reportExcessiveWake(ArrayMap<String, ProcessState> pkgList) {
            ensureNotDead();
            mCommonProcess.mNumExcessiveWake++;
            if (!mCommonProcess.mMultiPackage) {
                return;
            }

            for (int ip=pkgList.size()-1; ip>=0; ip--) {
                pullFixedProc(pkgList, ip).mNumExcessiveWake++;
            }
        }

        public void reportExcessiveCpu(ArrayMap<String, ProcessState> pkgList) {
            ensureNotDead();
            mCommonProcess.mNumExcessiveCpu++;
            if (!mCommonProcess.mMultiPackage) {
                return;
            }

            for (int ip=pkgList.size()-1; ip>=0; ip--) {
                pullFixedProc(pkgList, ip).mNumExcessiveCpu++;
            }
        }

        private void addCachedKill(int num, long minPss, long avgPss, long maxPss) {
            if (mNumCachedKill <= 0) {
                mNumCachedKill = num;
                mMinCachedKillPss = minPss;
                mAvgCachedKillPss = avgPss;
                mMaxCachedKillPss = maxPss;
            } else {
                if (minPss < mMinCachedKillPss) {
                    mMinCachedKillPss = minPss;
                }
                if (maxPss > mMaxCachedKillPss) {
                    mMaxCachedKillPss = maxPss;
                }
                mAvgCachedKillPss = (long)( ((mAvgCachedKillPss*(double)mNumCachedKill) + avgPss)
                        / (mNumCachedKill+num) );
                mNumCachedKill += num;
            }
        }

        public void reportCachedKill(ArrayMap<String, ProcessState> pkgList, long pss) {
            ensureNotDead();
            mCommonProcess.addCachedKill(1, pss, pss, pss);
            if (!mCommonProcess.mMultiPackage) {
                return;
            }

            for (int ip=pkgList.size()-1; ip>=0; ip--) {
                pullFixedProc(pkgList, ip).addCachedKill(1, pss, pss, pss);
            }
        }

        ProcessState pullFixedProc(String pkgName) {
            if (mMultiPackage) {
                // The array map is still pointing to a common process state
                // that is now shared across packages.  Update it to point to
                // the new per-package state.
                ProcessState proc = mStats.mPackages.get(pkgName, mUid).mProcesses.get(mName);
                if (proc == null) {
                    throw new IllegalStateException("Didn't create per-package process");
                }
                return proc;
            }
            return this;
        }

        private ProcessState pullFixedProc(ArrayMap<String, ProcessState> pkgList, int index) {
            ProcessState proc = pkgList.valueAt(index);
            if (mDead && proc.mCommonProcess != proc) {
                // Somehow we are contining to use a process state that is dead, because
                // it was not being told it was active during the last commit.  We can recover
                // from this by generating a fresh new state, but this is bad because we
                // are losing whatever data we had in the old process state.
                Log.wtf(TAG, "Pulling dead proc: name=" + mName + " pkg=" + mPackage
                        + " uid=" + mUid + " common.name=" + mCommonProcess.mName);
                proc = mStats.getProcessStateLocked(proc.mPackage, proc.mUid, proc.mName);
            }
            if (proc.mMultiPackage) {
                // The array map is still pointing to a common process state
                // that is now shared across packages.  Update it to point to
                // the new per-package state.
                PackageState pkg = mStats.mPackages.get(pkgList.keyAt(index), proc.mUid);
                if (pkg == null) {
                    throw new IllegalStateException("No existing package "
                            + pkgList.keyAt(index) + "/" + proc.mUid
                            + " for multi-proc " + proc.mName);
                }
                proc = pkg.mProcesses.get(proc.mName);
                if (proc == null) {
                    throw new IllegalStateException("Didn't create per-package process "
                            + proc.mName + " in pkg " + pkg.mPackageName + "/" + pkg.mUid);
                }
                pkgList.setValueAt(index, proc);
            }
            return proc;
        }

        long getDuration(int state, long now) {
            long time = super.getDuration(state, now);
            if (mCurState == state) {
                time += now - mStartTime;
            }
            return time;
        }

        long getPssSampleCount(int state) {
            int idx = binarySearch(mPssTable, mPssTableSize, state);
            return idx >= 0 ? mStats.getLong(mPssTable[idx], PSS_SAMPLE_COUNT) : 0;
        }

        long getPssMinimum(int state) {
            int idx = binarySearch(mPssTable, mPssTableSize, state);
            return idx >= 0 ? mStats.getLong(mPssTable[idx], PSS_MINIMUM) : 0;
        }

        long getPssAverage(int state) {
            int idx = binarySearch(mPssTable, mPssTableSize, state);
            return idx >= 0 ? mStats.getLong(mPssTable[idx], PSS_AVERAGE) : 0;
        }

        long getPssMaximum(int state) {
            int idx = binarySearch(mPssTable, mPssTableSize, state);
            return idx >= 0 ? mStats.getLong(mPssTable[idx], PSS_MAXIMUM) : 0;
        }

        long getPssUssMinimum(int state) {
            int idx = binarySearch(mPssTable, mPssTableSize, state);
            return idx >= 0 ? mStats.getLong(mPssTable[idx], PSS_USS_MINIMUM) : 0;
        }

        long getPssUssAverage(int state) {
            int idx = binarySearch(mPssTable, mPssTableSize, state);
            return idx >= 0 ? mStats.getLong(mPssTable[idx], PSS_USS_AVERAGE) : 0;
        }

        long getPssUssMaximum(int state) {
            int idx = binarySearch(mPssTable, mPssTableSize, state);
            return idx >= 0 ? mStats.getLong(mPssTable[idx], PSS_USS_MAXIMUM) : 0;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("ProcessState{").append(Integer.toHexString(System.identityHashCode(this)))
                    .append(" ").append(mName).append("/").append(mUid)
                    .append(" pkg=").append(mPackage);
            if (mMultiPackage) sb.append(" (multi)");
            if (mCommonProcess != this) sb.append(" (sub)");
            sb.append("}");
            return sb.toString();
        }
    }

    public static final class ServiceState extends DurationsTable {
        public final String mPackage;
        public final String mProcessName;
        ProcessState mProc;

        Object mOwner;

        public static final int SERVICE_RUN = 0;
        public static final int SERVICE_STARTED = 1;
        public static final int SERVICE_BOUND = 2;
        public static final int SERVICE_EXEC = 3;
        static final int SERVICE_COUNT = 4;

        int mRunCount;
        public int mRunState = STATE_NOTHING;
        long mRunStartTime;

        boolean mStarted;
        boolean mRestarting;
        int mStartedCount;
        public int mStartedState = STATE_NOTHING;
        long mStartedStartTime;

        int mBoundCount;
        public int mBoundState = STATE_NOTHING;
        long mBoundStartTime;

        int mExecCount;
        public int mExecState = STATE_NOTHING;
        long mExecStartTime;

        public ServiceState(ProcessStats processStats, String pkg, String name,
                String processName, ProcessState proc) {
            super(processStats, name);
            mPackage = pkg;
            mProcessName = processName;
            mProc = proc;
        }

        public void applyNewOwner(Object newOwner) {
            if (mOwner != newOwner) {
                if (mOwner == null) {
                    mOwner = newOwner;
                    mProc.incActiveServices(mName);
                } else {
                    // There was already an old owner, reset this object for its
                    // new owner.
                    mOwner = newOwner;
                    if (mStarted || mBoundState != STATE_NOTHING || mExecState != STATE_NOTHING) {
                        long now = SystemClock.uptimeMillis();
                        if (mStarted) {
                            if (DEBUG) Slog.d(TAG, "Service has new owner " + newOwner
                                    + " from " + mOwner + " while started: pkg="
                                    + mPackage + " service=" + mName + " proc=" + mProc);
                            setStarted(false, 0, now);
                        }
                        if (mBoundState != STATE_NOTHING) {
                            if (DEBUG) Slog.d(TAG, "Service has new owner " + newOwner
                                    + " from " + mOwner + " while bound: pkg="
                                    + mPackage + " service=" + mName + " proc=" + mProc);
                            setBound(false, 0, now);
                        }
                        if (mExecState != STATE_NOTHING) {
                            if (DEBUG) Slog.d(TAG, "Service has new owner " + newOwner
                                    + " from " + mOwner + " while executing: pkg="
                                    + mPackage + " service=" + mName + " proc=" + mProc);
                            setExecuting(false, 0, now);
                        }
                    }
                }
            }
        }

        public void clearCurrentOwner(Object owner, boolean silently) {
            if (mOwner == owner) {
                mProc.decActiveServices(mName);
                if (mStarted || mBoundState != STATE_NOTHING || mExecState != STATE_NOTHING) {
                    long now = SystemClock.uptimeMillis();
                    if (mStarted) {
                        if (!silently) {
                            Slog.wtfStack(TAG, "Service owner " + owner
                                    + " cleared while started: pkg=" + mPackage + " service="
                                    + mName + " proc=" + mProc);
                        }
                        setStarted(false, 0, now);
                    }
                    if (mBoundState != STATE_NOTHING) {
                        if (!silently) {
                            Slog.wtfStack(TAG, "Service owner " + owner
                                    + " cleared while bound: pkg=" + mPackage + " service="
                                    + mName + " proc=" + mProc);
                        }
                        setBound(false, 0, now);
                    }
                    if (mExecState != STATE_NOTHING) {
                        if (!silently) {
                            Slog.wtfStack(TAG, "Service owner " + owner
                                    + " cleared while exec: pkg=" + mPackage + " service="
                                    + mName + " proc=" + mProc);
                        }
                        setExecuting(false, 0, now);
                    }
                }
                mOwner = null;
            }
        }

        public boolean isInUse() {
            return mOwner != null || mRestarting;
        }

        void add(ServiceState other) {
            addDurations(other);
            mRunCount += other.mRunCount;
            mStartedCount += other.mStartedCount;
            mBoundCount += other.mBoundCount;
            mExecCount += other.mExecCount;
        }

        void resetSafely(long now) {
            resetDurationsSafely();
            mRunCount = mRunState != STATE_NOTHING ? 1 : 0;
            mStartedCount = mStartedState != STATE_NOTHING ? 1 : 0;
            mBoundCount = mBoundState != STATE_NOTHING ? 1 : 0;
            mExecCount = mExecState != STATE_NOTHING ? 1 : 0;
            mRunStartTime = mStartedStartTime = mBoundStartTime = mExecStartTime = now;
        }

        void writeToParcel(Parcel out, long now) {
            writeDurationsToParcel(out);
            out.writeInt(mRunCount);
            out.writeInt(mStartedCount);
            out.writeInt(mBoundCount);
            out.writeInt(mExecCount);
        }

        boolean readFromParcel(Parcel in) {
            if (!readDurationsFromParcel(in)) {
                return false;
            }
            mRunCount = in.readInt();
            mStartedCount = in.readInt();
            mBoundCount = in.readInt();
            mExecCount = in.readInt();
            return true;
        }

        void commitStateTime(long now) {
            if (mRunState != STATE_NOTHING) {
                addDuration(SERVICE_RUN + (mRunState*SERVICE_COUNT), now - mRunStartTime);
                mRunStartTime = now;
            }
            if (mStartedState != STATE_NOTHING) {
                addDuration(SERVICE_STARTED + (mStartedState*SERVICE_COUNT),
                        now - mStartedStartTime);
                mStartedStartTime = now;
            }
            if (mBoundState != STATE_NOTHING) {
                addDuration(SERVICE_BOUND + (mBoundState*SERVICE_COUNT), now - mBoundStartTime);
                mBoundStartTime = now;
            }
            if (mExecState != STATE_NOTHING) {
                addDuration(SERVICE_EXEC + (mExecState*SERVICE_COUNT), now - mExecStartTime);
                mExecStartTime = now;
            }
        }

        private void updateRunning(int memFactor, long now) {
            final int state = (mStartedState != STATE_NOTHING || mBoundState != STATE_NOTHING
                    || mExecState != STATE_NOTHING) ? memFactor : STATE_NOTHING;
            if (mRunState != state) {
                if (mRunState != STATE_NOTHING) {
                    addDuration(SERVICE_RUN + (mRunState*SERVICE_COUNT),
                            now - mRunStartTime);
                } else if (state != STATE_NOTHING) {
                    mRunCount++;
                }
                mRunState = state;
                mRunStartTime = now;
            }
        }

        public void setStarted(boolean started, int memFactor, long now) {
            if (mOwner == null) {
                Slog.wtf(TAG, "Starting service " + this + " without owner");
            }
            mStarted = started;
            updateStartedState(memFactor, now);
        }

        public void setRestarting(boolean restarting, int memFactor, long now) {
            mRestarting = restarting;
            updateStartedState(memFactor, now);
        }

        void updateStartedState(int memFactor, long now) {
            final boolean wasStarted = mStartedState != STATE_NOTHING;
            final boolean started = mStarted || mRestarting;
            final int state = started ? memFactor : STATE_NOTHING;
            if (mStartedState != state) {
                if (mStartedState != STATE_NOTHING) {
                    addDuration(SERVICE_STARTED + (mStartedState*SERVICE_COUNT),
                            now - mStartedStartTime);
                } else if (started) {
                    mStartedCount++;
                }
                mStartedState = state;
                mStartedStartTime = now;
                mProc = mProc.pullFixedProc(mPackage);
                if (wasStarted != started) {
                    if (started) {
                        mProc.incStartedServices(memFactor, now, mName);
                    } else {
                        mProc.decStartedServices(memFactor, now, mName);
                    }
                }
                updateRunning(memFactor, now);
            }
        }

        public void setBound(boolean bound, int memFactor, long now) {
            if (mOwner == null) {
                Slog.wtf(TAG, "Binding service " + this + " without owner");
            }
            final int state = bound ? memFactor : STATE_NOTHING;
            if (mBoundState != state) {
                if (mBoundState != STATE_NOTHING) {
                    addDuration(SERVICE_BOUND + (mBoundState*SERVICE_COUNT),
                            now - mBoundStartTime);
                } else if (bound) {
                    mBoundCount++;
                }
                mBoundState = state;
                mBoundStartTime = now;
                updateRunning(memFactor, now);
            }
        }

        public void setExecuting(boolean executing, int memFactor, long now) {
            if (mOwner == null) {
                Slog.wtf(TAG, "Executing service " + this + " without owner");
            }
            final int state = executing ? memFactor : STATE_NOTHING;
            if (mExecState != state) {
                if (mExecState != STATE_NOTHING) {
                    addDuration(SERVICE_EXEC + (mExecState*SERVICE_COUNT), now - mExecStartTime);
                } else if (executing) {
                    mExecCount++;
                }
                mExecState = state;
                mExecStartTime = now;
                updateRunning(memFactor, now);
            }
        }

        private long getDuration(int opType, int curState, long startTime, int memFactor,
                long now) {
            int state = opType + (memFactor*SERVICE_COUNT);
            long time = getDuration(state, now);
            if (curState == memFactor) {
                time += now - startTime;
            }
            return time;
        }

        public String toString() {
            return "ServiceState{" + Integer.toHexString(System.identityHashCode(this))
                    + " " + mName + " pkg=" + mPackage + " proc="
                    + Integer.toHexString(System.identityHashCode(this)) + "}";
        }
    }

    public static final class PackageState {
        public final ArrayMap<String, ProcessState> mProcesses
                = new ArrayMap<String, ProcessState>();
        public final ArrayMap<String, ServiceState> mServices
                = new ArrayMap<String, ServiceState>();
        public final String mPackageName;
        public final int mUid;

        public PackageState(String packageName, int uid) {
            mUid = uid;
            mPackageName = packageName;
        }
    }

    public static final class ProcessDataCollection {
        final int[] screenStates;
        final int[] memStates;
        final int[] procStates;

        public long totalTime;
        public long numPss;
        public long minPss;
        public long avgPss;
        public long maxPss;
        public long minUss;
        public long avgUss;
        public long maxUss;

        public ProcessDataCollection(int[] _screenStates, int[] _memStates, int[] _procStates) {
            screenStates = _screenStates;
            memStates = _memStates;
            procStates = _procStates;
        }

        void print(PrintWriter pw, long overallTime, boolean full) {
            if (totalTime > overallTime) {
                pw.print("*");
            }
            printPercent(pw, (double) totalTime / (double) overallTime);
            if (numPss > 0) {
                pw.print(" (");
                printSizeValue(pw, minPss * 1024);
                pw.print("-");
                printSizeValue(pw, avgPss * 1024);
                pw.print("-");
                printSizeValue(pw, maxPss * 1024);
                pw.print("/");
                printSizeValue(pw, minUss * 1024);
                pw.print("-");
                printSizeValue(pw, avgUss * 1024);
                pw.print("-");
                printSizeValue(pw, maxUss * 1024);
                if (full) {
                    pw.print(" over ");
                    pw.print(numPss);
                }
                pw.print(")");
            }
        }
    }
}
