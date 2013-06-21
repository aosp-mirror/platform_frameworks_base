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

package com.android.server.am;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.TimeUtils;
import com.android.server.ProcessMap;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;

public final class ProcessTracker {
    public static final int STATE_NOTHING = -1;
    public static final int STATE_PERSISTENT = 0;
    public static final int STATE_TOP = 1;
    public static final int STATE_FOREGROUND = 2;
    public static final int STATE_VISIBLE = 3;
    public static final int STATE_PERCEPTIBLE = 4;
    public static final int STATE_BACKUP = 5;
    public static final int STATE_SERVICE = 6;
    public static final int STATE_HOME = 7;
    public static final int STATE_PREVIOUS = 8;
    public static final int STATE_CACHED = 9;
    public static final int STATE_COUNT = STATE_CACHED+1;

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

    static final String[] STATE_NAMES = new String[] {
            "Persistent ", "Top        ", "Foreground ", "Visible    ", "Perceptible",
            "Backup     ", "Service    ", "Home       ", "Previous   ", "Cached     "
    };

    static final String[] ADJ_SCREEN_NAMES_CSV = new String[] {
            "off", "on"
    };

    static final String[] ADJ_MEM_NAMES_CSV = new String[] {
            "norm", "mod",  "low", "crit"
    };

    static final String[] STATE_NAMES_CSV = new String[] {
            "pers", "top", "fore", "vis", "percept",
            "backup", "service", "home", "prev", "cached"
    };

    static final String CSV_SEP = "\t";

    final Context mContext;
    final State mState = new State();

    public static final class ProcessState {
        final State mState;
        final ProcessState mCommonProcess;
        final String mPackage;
        final int mUid;
        final String mName;

        final long[] mDurations = new long[STATE_COUNT*ADJ_COUNT];
        int mCurState = STATE_NOTHING;
        long mStartTime;

        boolean mMultiPackage;

        long mTmpTotalTime;

        /**
         * Create a new top-level process state, for the initial case where there is only
         * a single package running in a process.  The initial state is not running.
         */
        public ProcessState(State state, String pkg, int uid, String name) {
            mState = state;
            mCommonProcess = null;
            mPackage = pkg;
            mUid = uid;
            mName = name;
        }

        /**
         * Create a new per-package process state for an existing top-level process
         * state.  The current running state of the top-level process is also copied,
         * marked as started running at 'now'.
         */
        public ProcessState(ProcessState commonProcess, String pkg, int uid, String name,
                long now) {
            mState = commonProcess.mState;
            mCommonProcess = commonProcess;
            mPackage = pkg;
            mUid = uid;
            mName = name;
            mCurState = commonProcess.mCurState;
            mStartTime = now;
        }

        ProcessState clone(String pkg, long now) {
            ProcessState pnew = new ProcessState(this, pkg, mUid, mName, now);
            pnew.add(this, now);
            return pnew;
        }

        public void setState(int state, int memFactor, long now,
                ArrayMap<String, ProcessTracker.ProcessState> pkgList) {
            if (state != STATE_NOTHING) {
                state += memFactor*STATE_COUNT;
            }

            if (mCommonProcess != null) {
                // First update the common process.
                mCommonProcess.setState(state, now);
                if (!mCommonProcess.mMultiPackage) {
                    // This common process is for a single package, so it is shared
                    // with the per-package state.  Nothing more to do.
                    return;
                }
            }

            for (int ip=pkgList.size()-1; ip>=0; ip--) {
                ProcessState proc = pkgList.valueAt(ip);
                if (proc.mMultiPackage) {
                    // The array map is still pointing to a common process state
                    // that is now shared across packages.  Update it to point to
                    // the new per-package state.
                    proc = mState.mPackages.get(pkgList.keyAt(ip),
                            proc.mUid).mProcesses.get(proc.mName);
                    if (proc == null) {
                        throw new IllegalStateException("Didn't create per-package process");
                    }
                    pkgList.setValueAt(ip, proc);
                }
                proc.setState(state, now);
            }
        }

        void setState(int state, long now) {
            if (mCurState != state) {
                if (mCurState != STATE_NOTHING) {
                    long dur = now - mStartTime;
                    mDurations[mCurState] += dur;
                }
                mCurState = state;
                mStartTime = now;
            }
        }

        void add(ProcessState other, long now) {
            for (int i=0; i<(STATE_COUNT*ADJ_COUNT); i++) {
                mDurations[i] += other.mDurations[i];
                if (other.mCurState == i) {
                    mDurations[i] += now - other.mStartTime;
                }
            }
        }
    }

    public static final class ServiceState {
        final long[] mStartedDurations = new long[ADJ_COUNT];
        int mStartedCount;
        int mStartedState = STATE_NOTHING;
        long mStartedStartTime;

        final long[] mBoundDurations = new long[ADJ_COUNT];
        int mBoundCount;
        int mBoundState = STATE_NOTHING;
        long mBoundStartTime;

        final long[] mExecDurations = new long[ADJ_COUNT];
        int mExecCount;
        int mExecState = STATE_NOTHING;
        long mExecStartTime;

        public void setStarted(boolean started, int memFactor, long now) {
            int state = started ? memFactor : STATE_NOTHING;
            if (mStartedState != state) {
                if (mStartedState != STATE_NOTHING) {
                    mStartedDurations[mStartedState] += now - mStartedStartTime;
                } else if (started) {
                    mStartedCount++;
                }
                mStartedState = state;
                mStartedStartTime = now;
            }
        }

        public void setBound(boolean bound, int memFactor, long now) {
            int state = bound ? memFactor : STATE_NOTHING;
            if (mBoundState != state) {
                if (mBoundState != STATE_NOTHING) {
                    mBoundDurations[mBoundState] += now - mBoundStartTime;
                } else if (bound) {
                    mBoundCount++;
                }
                mBoundState = state;
                mBoundStartTime = now;
            }
        }

        public void setExecuting(boolean executing, int memFactor, long now) {
            int state = executing ? memFactor : STATE_NOTHING;
            if (mExecState != state) {
                if (mExecState != STATE_NOTHING) {
                    mExecDurations[mExecState] += now - mExecStartTime;
                } else if (executing) {
                    mExecCount++;
                }
                mExecState = state;
                mExecStartTime = now;
            }
        }
    }

    public static final class PackageState {
        final ArrayMap<String, ProcessState> mProcesses = new ArrayMap<String, ProcessState>();
        final ArrayMap<String, ServiceState> mServices = new ArrayMap<String, ServiceState>();
        final int mUid;

        public PackageState(int uid) {
            mUid = uid;
        }
    }

    static final class State {
        final ProcessMap<PackageState> mPackages = new ProcessMap<PackageState>();
        final ProcessMap<ProcessState> mProcesses = new ProcessMap<ProcessState>();
        final long[] mMemFactorDurations = new long[ADJ_COUNT];
        int mMemFactor = STATE_NOTHING;
        long mStartTime;

        PackageState getPackageStateLocked(String packageName, int uid) {
            PackageState as = mPackages.get(packageName, uid);
            if (as != null) {
                return as;
            }
            as = new PackageState(uid);
            mPackages.put(packageName, uid, as);
            return as;
        }

        ProcessState getProcessStateLocked(String packageName, int uid, String processName) {
            final PackageState pkgState = getPackageStateLocked(packageName, uid);
            ProcessState ps = pkgState.mProcesses.get(processName);
            if (ps != null) {
                return ps;
            }
            ProcessState commonProc = mProcesses.get(processName, uid);
            if (commonProc == null) {
                commonProc = new ProcessState(this, packageName, uid, processName);
                mProcesses.put(processName, uid, commonProc);
            }
            if (!commonProc.mMultiPackage) {
                if (packageName.equals(commonProc.mPackage)) {
                    // This common process is not in use by multiple packages, and
                    // is for the calling package, so we can just use it directly.
                    ps = commonProc;
                } else {
                    // This common process has not been in use by multiple packages,
                    // but it was created for a different package than the caller.
                    // We need to convert it to a multi-package process.
                    commonProc.mMultiPackage = true;
                    // The original package it was created for now needs to point
                    // to its own copy.
                    long now = SystemClock.uptimeMillis();
                    pkgState.mProcesses.put(commonProc.mPackage, commonProc.clone(
                            commonProc.mPackage, now));
                    ps = new ProcessState(commonProc, packageName, uid, processName, now);
                }
            } else {
                // The common process is for multiple packages, we need to create a
                // separate object for the per-package data.
                ps = new ProcessState(commonProc, packageName, uid, processName,
                        SystemClock.uptimeMillis());
            }
            pkgState.mProcesses.put(processName, ps);
            return ps;
        }

        void reset() {
            mPackages.getMap().clear();
            mProcesses.getMap().clear();
            Arrays.fill(mMemFactorDurations, 0);
            mMemFactor = STATE_NOTHING;
            mStartTime = 0;
        }
    }

    public ProcessTracker(Context context) {
        mContext = context;
    }

    public ProcessState getProcessStateLocked(String packageName, int uid, String processName) {
        return mState.getProcessStateLocked(packageName, uid, processName);
    }

    public ServiceState getServiceStateLocked(String packageName, int uid, String className) {
        final PackageState as = mState.getPackageStateLocked(packageName, uid);
        ServiceState ss = as.mServices.get(className);
        if (ss != null) {
            return ss;
        }
        ss = new ServiceState();
        as.mServices.put(className, ss);
        return ss;
    }

    public boolean setMemFactor(int memFactor, boolean screenOn, long now) {
        if (screenOn) {
            memFactor += ADJ_SCREEN_ON;
        }
        if (memFactor != mState.mMemFactor) {
            if (mState.mMemFactor != STATE_NOTHING) {
                mState.mMemFactorDurations[mState.mMemFactor] += now - mState.mStartTime;
            }
            mState.mMemFactor = memFactor;
            mState.mStartTime = now;
            ArrayMap<String, SparseArray<PackageState>> pmap = mState.mPackages.getMap();
            for (int i=0; i<pmap.size(); i++) {
                SparseArray<PackageState> uids = pmap.valueAt(i);
                for (int j=0; j<uids.size(); j++) {
                    PackageState pkg = uids.valueAt(j);
                    ArrayMap<String, ServiceState> services = pkg.mServices;
                    for (int k=0; k<services.size(); k++) {
                        ServiceState service = services.valueAt(k);
                        if (service.mStartedState != STATE_NOTHING) {
                            service.setStarted(true, memFactor, now);
                        }
                        if (service.mBoundState != STATE_NOTHING) {
                            service.setBound(true, memFactor, now);
                        }
                    }
                }
            }
            return true;
        }
        return false;
    }

    public int getMemFactor() {
        return mState.mMemFactor != STATE_NOTHING ? mState.mMemFactor : 0;
    }

    static private void printScreenLabel(PrintWriter pw, int offset) {
        switch (offset) {
            case ADJ_NOTHING:
                pw.print("             ");
                break;
            case ADJ_SCREEN_OFF:
                pw.print("Screen Off / ");
                break;
            case ADJ_SCREEN_ON:
                pw.print("Screen On  / ");
                break;
            default:
                pw.print("?????????? / ");
                break;
        }
    }

    static private void printScreenLabelCsv(PrintWriter pw, int offset) {
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

    static private void printMemLabel(PrintWriter pw, int offset) {
        switch (offset) {
            case ADJ_NOTHING:
                pw.print("       ");
                break;
            case ADJ_MEM_FACTOR_NORMAL:
                pw.print("Norm / ");
                break;
            case ADJ_MEM_FACTOR_MODERATE:
                pw.print("Mod  / ");
                break;
            case ADJ_MEM_FACTOR_LOW:
                pw.print("Low  / ");
                break;
            case ADJ_MEM_FACTOR_CRITICAL:
                pw.print("Crit / ");
                break;
            default:
                pw.print("???? / ");
                break;
        }
    }

    static private void printMemLabelCsv(PrintWriter pw, int offset) {
        if (offset >= ADJ_MEM_FACTOR_NORMAL) {
            if (offset <= ADJ_MEM_FACTOR_CRITICAL) {
                pw.print(ADJ_MEM_NAMES_CSV[offset]);
            } else {
                pw.print("???");
            }
        }
    }

    static void dumpSingleTime(PrintWriter pw, String prefix, long[] durations,
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
                    running = " (running)";
                }
                if (time != 0) {
                    pw.print(prefix);
                    printScreenLabel(pw, printedScreen != iscreen
                            ? iscreen : STATE_NOTHING);
                    printedScreen = iscreen;
                    printMemLabel(pw, printedMem != imem ? imem : STATE_NOTHING);
                    printedMem = imem;
                    TimeUtils.formatDuration(time, pw); pw.println(running);
                    totalTime += time;
                }
            }
        }
        if (totalTime != 0) {
            pw.print(prefix);
            printScreenLabel(pw, STATE_NOTHING);
            pw.print("TOTAL: ");
            TimeUtils.formatDuration(totalTime, pw);
            pw.println();
        }
    }

    static void dumpSingleTimeCsv(PrintWriter pw, String sep, long[] durations,
            int curState, long curStartTime, long now) {
        for (int iscreen=0; iscreen<ADJ_COUNT; iscreen+=ADJ_SCREEN_MOD) {
            for (int imem=0; imem<ADJ_MEM_FACTOR_COUNT; imem++) {
                int state = imem+iscreen;
                long time = durations[state];
                if (curState == state) {
                    time += now - curStartTime;
                }
                pw.print(sep);
                if (time != 0) {
                    pw.print(time);
                } else {
                    pw.print("0");
                }
            }
        }
    }

    static void dumpServiceTimeCheckin(PrintWriter pw, String label, String packageName,
            int uid, String serviceName, ServiceState svc, int opCount, long[] durations,
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
        pw.print(opCount);
        dumpSingleTimeCsv(pw, ",", durations, curState, curStartTime, now);
        pw.println();
    }

    long computeProcessTimeLocked(ProcessState proc, int[] screenStates, int[] memStates,
                int[] procStates, long now) {
        long totalTime = 0;
        for (int is=0; is<screenStates.length; is++) {
            for (int im=0; im<memStates.length; im++) {
                for (int ip=0; ip<procStates.length; ip++) {
                    int bucket = ((screenStates[is]+ memStates[im]) * STATE_COUNT)
                            + procStates[ip];
                    totalTime += proc.mDurations[bucket];
                    if (proc.mCurState == bucket) {
                        totalTime += now - proc.mStartTime;
                    }
                }
            }
        }
        proc.mTmpTotalTime = totalTime;
        return totalTime;
    }

    ArrayList<ProcessState> collectProcessesLocked(int[] screenStates, int[] memStates,
            int[] procStates, long now, String reqPackage) {
        ArraySet<ProcessState> foundProcs = new ArraySet<ProcessState>();
        ArrayMap<String, SparseArray<PackageState>> pkgMap = mState.mPackages.getMap();
        for (int ip=0; ip<pkgMap.size(); ip++) {
            if (reqPackage != null && !reqPackage.equals(pkgMap.keyAt(ip))) {
                continue;
            }
            SparseArray<PackageState> procs = pkgMap.valueAt(ip);
            for (int iu=0; iu<procs.size(); iu++) {
                PackageState state = procs.valueAt(iu);
                for (int iproc=0; iproc<state.mProcesses.size(); iproc++) {
                    ProcessState proc = state.mProcesses.valueAt(iproc);
                    if (proc.mCommonProcess != null) {
                        proc = proc.mCommonProcess;
                    }
                    foundProcs.add(proc);
                }
            }
        }
        ArrayList<ProcessState> outProcs = new ArrayList<ProcessState>(foundProcs.size());
        for (int i=0; i<foundProcs.size(); i++) {
            ProcessState proc = foundProcs.valueAt(i);
            if (computeProcessTimeLocked(proc, screenStates, memStates,
                    procStates, now) > 0) {
                outProcs.add(proc);
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

    void dumpProcessState(PrintWriter pw, String prefix, ProcessState proc, int[] screenStates,
            int[] memStates, int[] procStates, long now) {
        long totalTime = 0;
        int printedScreen = -1;
        for (int is=0; is<screenStates.length; is++) {
            int printedMem = -1;
            for (int im=0; im<memStates.length; im++) {
                for (int ip=0; ip<procStates.length; ip++) {
                    final int iscreen = screenStates[is];
                    final int imem = memStates[im];
                    final int bucket = ((iscreen + imem) * STATE_COUNT) + procStates[ip];
                    long time = proc.mDurations[bucket];
                    String running = "";
                    if (proc.mCurState == bucket) {
                        time += now - proc.mStartTime;
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
                            printMemLabel(pw, printedMem != imem ? imem : STATE_NOTHING);
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
                printMemLabel(pw, STATE_NOTHING);
            }
            pw.print("TOTAL      : ");
            TimeUtils.formatDuration(totalTime, pw);
            pw.println();
        }
    }

    void dumpStateHeadersCsv(PrintWriter pw, String sep, int[] screenStates,
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

    void dumpProcessStateCsv(PrintWriter pw, ProcessState proc,
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
                                totalTime += proc.mDurations[bucket];
                                if (proc.mCurState == bucket) {
                                    totalTime += now - proc.mStartTime;
                                }
                            }
                        }
                    }
                    pw.print(CSV_SEP);
                    if (totalTime != 0) {
                        pw.print(totalTime);
                    } else {
                        pw.print("0");
                    }
                }
            }
        }
    }

    void dumpProcessList(PrintWriter pw, String prefix, ArrayList<ProcessState> procs,
            int[] screenStates, int[] memStates, int[] procStates, long now) {
        String innerPrefix = prefix + "  ";
        for (int i=procs.size()-1; i>=0; i--) {
            ProcessState proc = procs.get(i);
            pw.print(prefix);
            pw.print(proc.mName);
            pw.print(" / ");
            UserHandle.formatUid(pw, proc.mUid);
            pw.println(":");
            dumpProcessState(pw, innerPrefix, proc, screenStates, memStates, procStates, now);
        }
    }

    void dumpProcessListCsv(PrintWriter pw, ArrayList<ProcessState> procs,
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

    void dumpFilteredProcesses(PrintWriter pw, String header, String prefix,
            int[] screenStates, int[] memStates, int[] procStates, long now, String reqPackage) {
        ArrayList<ProcessState> procs = collectProcessesLocked(screenStates, memStates,
                procStates, now, reqPackage);
        if (procs.size() > 0) {
            pw.println();
            pw.println(header);
            dumpProcessList(pw, prefix, procs, screenStates, memStates, procStates, now);
        }
    }

    boolean dumpFilteredProcessesCsv(PrintWriter pw, String header,
            boolean sepScreenStates, int[] screenStates, boolean sepMemStates, int[] memStates,
            boolean sepProcStates, int[] procStates, long now, String reqPackage) {
        ArrayList<ProcessState> procs = collectProcessesLocked(screenStates, memStates,
                procStates, now, reqPackage);
        if (procs.size() > 0) {
            if (header != null) {
                pw.println(header);
            }
            dumpProcessListCsv(pw, procs, sepScreenStates, screenStates,
                    sepMemStates, memStates, sepProcStates, procStates, now);
            return true;
        }
        return false;
    }

    void dumpAllProcessState(PrintWriter pw, String prefix, boolean isCheckin,
            ProcessState proc, long now) {
        long totalTime = 0;
        int printedScreen = -1;
        for (int iscreen=0; iscreen<ADJ_COUNT; iscreen+=ADJ_SCREEN_MOD) {
            int printedMem = -1;
            for (int imem=0; imem<ADJ_MEM_FACTOR_COUNT; imem++) {
                for (int is=0; is<STATE_NAMES.length; is++) {
                    int bucket = is+(STATE_COUNT*(imem+iscreen));
                    long time = proc.mDurations[bucket];
                    String running = "";
                    if (proc.mCurState == bucket) {
                        time += now - proc.mStartTime;
                        running = " (running)";
                    }
                    if (!isCheckin) {
                        if (time != 0) {
                            pw.print(prefix);
                            printScreenLabel(pw, printedScreen != iscreen
                                    ? iscreen : STATE_NOTHING);
                            printedScreen = iscreen;
                            printMemLabel(pw, printedMem != imem
                                    ? imem : STATE_NOTHING);
                            printedMem = imem;
                            pw.print(STATE_NAMES[is]); pw.print(": ");
                            TimeUtils.formatDuration(time, pw); pw.println(running);
                            totalTime += time;
                        }
                    } else {
                        pw.print(",");
                        if (time != 0) {
                            pw.print(time);
                        } else {
                            pw.print("0");
                        }
                    }
                }
            }
        }
        if (!isCheckin) {
            if (totalTime != 0) {
                pw.print(prefix);
                printScreenLabel(pw, STATE_NOTHING);
                printMemLabel(pw, STATE_NOTHING);
                pw.print("TOTAL      : ");
                TimeUtils.formatDuration(totalTime, pw);
                pw.println();
            }
        } else {
            pw.println();
        }
    }

    static int[] parseStateList(String[] states, int mult, String arg, boolean[] outSep,
            String[] outError) {
        ArrayList<Integer> res = new ArrayList<Integer>();
        int lastPos = 0;
        for (int i=0; i<=arg.length(); i++) {
            char c = i < arg.length() ? arg.charAt(i) : 0;
            if (c != ',' && c != '+' && c != ' ' && c != 0) {
                continue;
            }
            boolean isSep = c == ',';
            if (lastPos == 0) {
                // We now know the type of op.
                outSep[0] = isSep;
            } else if (c != 0 && outSep[0] != isSep) {
                outError[0] = "inconsistent separators (can't mix ',' with '+')";
                return null;
            }
            if (lastPos < (i-1)) {
                String str = arg.substring(lastPos, i);
                for (int j=0; j<states.length; j++) {
                    if (str.equals(states[j])) {
                        res.add(j);
                        str = null;
                        break;
                    }
                }
                if (str != null) {
                    outError[0] = "invalid word \"" + str + "\"";
                    return null;
                }
            }
            lastPos = i + 1;
        }

        int[] finalRes = new int[res.size()];
        for (int i=0; i<res.size(); i++) {
            finalRes[i] = res.get(i) * mult;
        }
        return finalRes;
    }

    private void dumpHelp(PrintWriter pw) {
        pw.println("Process stats (procstats) dump options:");
        pw.println("    [--checkin|--csv] [csv-screen] [csv-proc] [csv-mem]");
        pw.println("    [--reset] [-h] [<package.name>]");
        pw.println("  --checkin: format output for a checkin report.");
        pw.println("  --csv: output data suitable for putting in a spreadsheet.");
        pw.println("  --csv-screen: on, off.");
        pw.println("  --csv-mem: norm, mod, low, crit.");
        pw.println("  --csv-proc: pers, top, fore, vis, precept, backup,");
        pw.println("    service, home, prev, cached");
        pw.println("  --reset: reset the stats, clearing all current data.");
        pw.println("  -h: print this help text.");
        pw.println("  <package.name>: optional name of package to filter output by.");
    }

    public void dumpLocked(FileDescriptor fd, PrintWriter pw, String[] args) {
        final long now = SystemClock.uptimeMillis();

        boolean isCheckin = false;
        boolean isCsv = false;
        String reqPackage = null;
        boolean csvSepScreenStats = false;
        int[] csvScreenStats = new int[] {ADJ_SCREEN_OFF, ADJ_SCREEN_ON};
        boolean csvSepMemStats = false;
        int[] csvMemStats = new int[] {ADJ_MEM_FACTOR_CRITICAL};
        boolean csvSepProcStats = true;
        int[] csvProcStats = new int[] {
                STATE_PERSISTENT, STATE_TOP, STATE_FOREGROUND, STATE_VISIBLE,
                STATE_PERCEPTIBLE, STATE_BACKUP, STATE_SERVICE, STATE_HOME,
                STATE_PREVIOUS, STATE_CACHED };
        if (args != null) {
            for (int i=0; i<args.length; i++) {
                String arg = args[i];
                if ("--checkin".equals(arg)) {
                    isCheckin = true;
                } else if ("--csv".equals(arg)) {
                    isCsv = true;
                } else if ("--csv-screen".equals(arg)) {
                    i++;
                    if (i >= args.length) {
                        pw.println("Error: argument required for --csv-screen");
                        dumpHelp(pw);
                        return;
                    }
                    boolean[] sep = new boolean[1];
                    String[] error = new String[1];
                    csvScreenStats = parseStateList(ADJ_SCREEN_NAMES_CSV, ADJ_SCREEN_MOD,
                            args[i], sep, error);
                    if (csvScreenStats == null) {
                        pw.println("Error in \"" + args[i] + "\": " + error[0]);
                        dumpHelp(pw);
                        return;
                    }
                    csvSepScreenStats = sep[0];
                } else if ("--csv-mem".equals(arg)) {
                    i++;
                    if (i >= args.length) {
                        pw.println("Error: argument required for --csv-mem");
                        dumpHelp(pw);
                        return;
                    }
                    boolean[] sep = new boolean[1];
                    String[] error = new String[1];
                    csvMemStats = parseStateList(ADJ_MEM_NAMES_CSV, 1, args[i], sep, error);
                    if (csvMemStats == null) {
                        pw.println("Error in \"" + args[i] + "\": " + error[0]);
                        dumpHelp(pw);
                        return;
                    }
                    csvSepMemStats = sep[0];
                } else if ("--csv-proc".equals(arg)) {
                    i++;
                    if (i >= args.length) {
                        pw.println("Error: argument required for --csv-proc");
                        dumpHelp(pw);
                        return;
                    }
                    boolean[] sep = new boolean[1];
                    String[] error = new String[1];
                    csvProcStats = parseStateList(STATE_NAMES_CSV, 1, args[i], sep, error);
                    if (csvProcStats == null) {
                        pw.println("Error in \"" + args[i] + "\": " + error[0]);
                        dumpHelp(pw);
                        return;
                    }
                    csvSepProcStats = sep[0];
                } else if ("--reset".equals(arg)) {
                    mState.reset();
                    pw.println("Process stats reset.");
                    return;
                } else if ("-h".equals(arg)) {
                    dumpHelp(pw);
                    return;
                } else if ("-a".equals(arg)) {
                    // ignore
                } else if (arg.length() > 0 && arg.charAt(0) == '-'){
                    pw.println("Unknown option: " + arg);
                    dumpHelp(pw);
                    return;
                } else {
                    // Not an option, last argument must be a package name.
                    try {
                        mContext.getPackageManager().getPackageUid(arg,
                                UserHandle.getCallingUserId());
                        reqPackage = arg;
                    } catch (PackageManager.NameNotFoundException e) {
                        pw.println("Unknown package: " + arg);
                        dumpHelp(pw);
                        return;
                    }
                }
            }
        }

        if (isCsv) {
            pw.print("Processes running summed over");
            if (!csvSepScreenStats) {
                for (int i=0; i<csvScreenStats.length; i++) {
                    pw.print(" ");
                    printScreenLabelCsv(pw, csvScreenStats[i]);
                }
            }
            if (!csvSepMemStats) {
                for (int i=0; i<csvMemStats.length; i++) {
                    pw.print(" ");
                    printMemLabelCsv(pw, csvMemStats[i]);
                }
            }
            if (!csvSepProcStats) {
                for (int i=0; i<csvProcStats.length; i++) {
                    pw.print(" ");
                    pw.print(STATE_NAMES_CSV[csvProcStats[i]]);
                }
            }
            pw.println();
            dumpFilteredProcessesCsv(pw, null,
                    csvSepScreenStats, csvScreenStats, csvSepMemStats, csvMemStats,
                    csvSepProcStats, csvProcStats, now, reqPackage);
            /*
            dumpFilteredProcessesCsv(pw, "Processes running while critical mem:",
                    false, new int[] {ADJ_SCREEN_OFF, ADJ_SCREEN_ON},
                    true, new int[] {ADJ_MEM_FACTOR_CRITICAL},
                    true, new int[] {STATE_PERSISTENT, STATE_TOP, STATE_FOREGROUND, STATE_VISIBLE,
                            STATE_PERCEPTIBLE, STATE_BACKUP, STATE_SERVICE, STATE_HOME,
                            STATE_PREVIOUS, STATE_CACHED},
                    now, reqPackage);
            dumpFilteredProcessesCsv(pw, "Processes running over all mem:",
                    false, new int[] {ADJ_SCREEN_OFF, ADJ_SCREEN_ON},
                    false, new int[] {ADJ_MEM_FACTOR_CRITICAL, ADJ_MEM_FACTOR_LOW,
                            ADJ_MEM_FACTOR_MODERATE, ADJ_MEM_FACTOR_MODERATE},
                    true, new int[] {STATE_PERSISTENT, STATE_TOP, STATE_FOREGROUND, STATE_VISIBLE,
                            STATE_PERCEPTIBLE, STATE_BACKUP, STATE_SERVICE, STATE_HOME,
                            STATE_PREVIOUS, STATE_CACHED},
                    now, reqPackage);
            */
            return;
        }

        ArrayMap<String, SparseArray<PackageState>> pkgMap = mState.mPackages.getMap();
        boolean printedHeader = false;
        if (isCheckin) {
            pw.println("vers,1");
        }
        for (int ip=0; ip<pkgMap.size(); ip++) {
            String pkgName = pkgMap.keyAt(ip);
            if (reqPackage != null && !reqPackage.equals(pkgName)) {
                continue;
            }
            SparseArray<PackageState> uids = pkgMap.valueAt(ip);
            for (int iu=0; iu<uids.size(); iu++) {
                int uid = uids.keyAt(iu);
                PackageState state = uids.valueAt(iu);
                final int NPROCS = state.mProcesses.size();
                final int NSRVS = state.mServices.size();
                if (!isCheckin) {
                    if (NPROCS > 0 || NSRVS > 0) {
                        if (!printedHeader) {
                            pw.println("Per-Package Process Stats:");
                            printedHeader = true;
                        }
                        pw.print("  * "); pw.print(pkgName); pw.print(" / ");
                                UserHandle.formatUid(pw, uid); pw.println(":");
                    }
                }
                for (int iproc=0; iproc<NPROCS; iproc++) {
                    if (!isCheckin) {
                        pw.print("      Process ");
                        pw.print(state.mProcesses.keyAt(iproc));
                        pw.println(":");
                    } else {
                        pw.print("pkgproc,");
                        pw.print(pkgName);
                        pw.print(",");
                        pw.print(uid);
                        pw.print(",");
                        pw.print(state.mProcesses.keyAt(iproc));
                    }
                    dumpAllProcessState(pw, "        ", isCheckin,
                            state.mProcesses.valueAt(iproc), now);
                }
                for (int isvc=0; isvc<NSRVS; isvc++) {
                    if (!isCheckin) {
                        pw.print("      Service ");
                        pw.print(state.mServices.keyAt(isvc));
                        pw.println(":");
                        ServiceState svc = state.mServices.valueAt(isvc);
                        if (svc.mStartedCount != 0) {
                            pw.print("        Started op count "); pw.print(svc.mStartedCount);
                            pw.println(":");
                            dumpSingleTime(pw, "          ", svc.mStartedDurations, svc.mStartedState,
                                    svc.mStartedStartTime, now);
                        }
                        if (svc.mBoundCount != 0) {
                            pw.print("        Bound op count "); pw.print(svc.mBoundCount);
                            pw.println(":");
                            dumpSingleTime(pw, "          ", svc.mBoundDurations, svc.mBoundState,
                                    svc.mBoundStartTime, now);
                        }
                        if (svc.mExecCount != 0) {
                            pw.print("        Executing op count "); pw.print(svc.mExecCount);
                            pw.println(":");
                            dumpSingleTime(pw, "          ", svc.mExecDurations, svc.mExecState,
                                    svc.mExecStartTime, now);
                        }
                    } else {
                        String serviceName = state.mServices.keyAt(isvc);
                        ServiceState svc = state.mServices.valueAt(isvc);
                        dumpServiceTimeCheckin(pw, "pkgsvc-start", pkgName, uid, serviceName,
                                svc, svc.mStartedCount, svc.mStartedDurations, svc.mStartedState,
                                svc.mStartedStartTime, now);
                        dumpServiceTimeCheckin(pw, "pkgsvc-bound", pkgName, uid, serviceName,
                                svc, svc.mBoundCount, svc.mBoundDurations, svc.mBoundState,
                                svc.mBoundStartTime, now);
                        dumpServiceTimeCheckin(pw, "pkgsvc-exec", pkgName, uid, serviceName,
                                svc, svc.mExecCount, svc.mExecDurations, svc.mExecState,
                                svc.mExecStartTime, now);
                    }
                }
            }
        }

        if (!isCheckin) {
            dumpFilteredProcesses(pw, "Processes running while critical mem:", "  ",
                    new int[] {ADJ_SCREEN_OFF, ADJ_SCREEN_ON},
                    new int[] {ADJ_MEM_FACTOR_CRITICAL},
                    new int[] {STATE_PERSISTENT, STATE_TOP, STATE_FOREGROUND, STATE_VISIBLE,
                            STATE_PERCEPTIBLE, STATE_BACKUP, STATE_SERVICE, STATE_HOME, STATE_PREVIOUS},
                    now, reqPackage);
            dumpFilteredProcesses(pw, "Processes running while low mem:", "  ",
                    new int[] {ADJ_SCREEN_OFF, ADJ_SCREEN_ON},
                    new int[] {ADJ_MEM_FACTOR_LOW},
                    new int[] {STATE_PERSISTENT, STATE_TOP, STATE_FOREGROUND, STATE_VISIBLE,
                            STATE_PERCEPTIBLE, STATE_BACKUP, STATE_SERVICE, STATE_HOME, STATE_PREVIOUS},
                    now, reqPackage);
            dumpFilteredProcesses(pw, "Processes running while moderate mem:", "  ",
                    new int[] {ADJ_SCREEN_OFF, ADJ_SCREEN_ON},
                    new int[] {ADJ_MEM_FACTOR_MODERATE},
                    new int[] {STATE_PERSISTENT, STATE_TOP, STATE_FOREGROUND, STATE_VISIBLE,
                            STATE_PERCEPTIBLE, STATE_BACKUP, STATE_SERVICE, STATE_HOME, STATE_PREVIOUS},
                    now, reqPackage);
            dumpFilteredProcesses(pw, "Processes running while normal mem:", "  ",
                    new int[] {ADJ_SCREEN_OFF, ADJ_SCREEN_ON},
                    new int[] {ADJ_MEM_FACTOR_NORMAL},
                    new int[] {STATE_PERSISTENT, STATE_TOP, STATE_FOREGROUND, STATE_VISIBLE,
                            STATE_PERCEPTIBLE, STATE_BACKUP, STATE_SERVICE, STATE_HOME, STATE_PREVIOUS},
                    now, reqPackage);
            pw.println();
            pw.println("Run time Stats:");
            dumpSingleTime(pw, "  ", mState.mMemFactorDurations, mState.mMemFactor,
                    mState.mStartTime, now);
        } else {
            ArrayMap<String, SparseArray<ProcessState>> procMap = mState.mProcesses.getMap();
            for (int ip=0; ip<procMap.size(); ip++) {
                String procName = procMap.keyAt(ip);
                SparseArray<ProcessState> uids = procMap.valueAt(ip);
                for (int iu=0; iu<uids.size(); iu++) {
                    int uid = uids.keyAt(iu);
                    ProcessState state = uids.valueAt(iu);
                    pw.print("proc,");
                    pw.print(procName);
                    pw.print(",");
                    pw.print(uid);
                    dumpAllProcessState(pw, "        ", isCheckin, state, now);
                }
            }
            pw.print("total,");
            dumpSingleTimeCsv(pw, ",", mState.mMemFactorDurations, mState.mMemFactor,
                    mState.mStartTime, now);
            pw.println();
        }
    }
}
