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

import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.SparseArray;
import android.util.TimeUtils;
import com.android.server.ProcessMap;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

public final class ProcessTracker {
    public static final int STATE_NOTHING = -1;
    public static final int STATE_TOP = 0;
    public static final int STATE_FOREGROUND = 1;
    public static final int STATE_VISIBLE = 2;
    public static final int STATE_PERCEPTIBLE = 3;
    public static final int STATE_BACKUP = 4;
    public static final int STATE_SERVICE = 5;
    public static final int STATE_HOME = 6;
    public static final int STATE_PREVIOUS = 7;
    public static final int STATE_CACHED = 8;
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

    static String[] STATE_NAMES = new String[] {
            "Top        ", "Foreground ", "Visible    ", "Perceptible", "Backup     ",
            "Service    ", "Home       ", "Previous   ", "Cached     "
    };

    public static final class ProcessState {
        final String mPackage;
        final int mUid;
        final String mName;

        final long[] mDurations = new long[STATE_COUNT*ADJ_COUNT];
        int mCurState = STATE_NOTHING;
        long mStartTime;

        long mTmpTotalTime;

        public ProcessState(String pkg, int uid, String name) {
            mPackage = pkg;
            mUid = uid;
            mName = name;
        }

        public void setState(int state, int memFactor, long now) {
            if (state != STATE_NOTHING) {
                state += memFactor*STATE_COUNT;
            }
            if (mCurState != state) {
                if (mCurState != STATE_NOTHING) {
                    mDurations[mCurState] += now - mStartTime;
                }
                mCurState = state;
                mStartTime = now;
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
        final long[] mMemFactorDurations = new long[ADJ_COUNT];
        int mMemFactor = STATE_NOTHING;
        long mStartTime;
    }

    final State mState = new State();

    public ProcessTracker() {
    }

    private PackageState getPackageStateLocked(String packageName, int uid) {
        PackageState as = mState.mPackages.get(packageName, uid);
        if (as != null) {
            return as;
        }
        as = new PackageState(uid);
        mState.mPackages.put(packageName, uid, as);
        return as;
    }

    public ProcessState getProcessStateLocked(String packageName, int uid, String processName) {
        final PackageState as = getPackageStateLocked(packageName, uid);
        ProcessState ps = as.mProcesses.get(processName);
        if (ps != null) {
            return ps;
        }
        ps = new ProcessState(packageName, uid, processName);
        as.mProcesses.put(processName, ps);
        return ps;
    }

    public ServiceState getServiceStateLocked(String packageName, int uid, String className) {
        final PackageState as = getPackageStateLocked(packageName, uid);
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
            int[] procStates, long now) {
        ArrayList<ProcessState> outProcs = new ArrayList<ProcessState>();
        ArrayMap<String, SparseArray<PackageState>> pmap = mState.mPackages.getMap();
        for (int ip=0; ip<pmap.size(); ip++) {
            SparseArray<PackageState> procs = pmap.valueAt(ip);
            for (int iu=0; iu<procs.size(); iu++) {
                PackageState state = procs.valueAt(iu);
                for (int iproc=0; iproc<state.mProcesses.size(); iproc++) {
                    if (computeProcessTimeLocked(state.mProcesses.valueAt(iproc),
                            screenStates, memStates, procStates, now) > 0) {
                        outProcs.add(state.mProcesses.valueAt(iproc));
                    }
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
                    totalTime += time;
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

    void dumpProcessList(PrintWriter pw, String prefix, ArrayList<ProcessState> procs,
            int[] screenStates, int[] memStates, int[] procStates, long now) {
        String innerPrefix = prefix + "  ";
        for (int i=procs.size()-1; i>=0; i--) {
            ProcessState proc = procs.get(i);
            pw.print(prefix);
            pw.print(proc.mPackage);
            pw.print(" / ");
            UserHandle.formatUid(pw, proc.mUid);
            pw.print(" / ");
            pw.print(proc.mName);
            pw.println(":");
            dumpProcessState(pw, innerPrefix, proc, screenStates, memStates, procStates, now);
        }
    }

    void dumpFilteredProcesses(PrintWriter pw, String header, String prefix,
            int[] screenStates, int[] memStates, int[] procStates, long now) {
        ArrayList<ProcessState> procs = collectProcessesLocked(screenStates, memStates,
                procStates, now);
        if (procs.size() > 0) {
            pw.println();
            pw.println(header);
            dumpProcessList(pw, prefix, procs, screenStates, memStates, procStates, now);
        }
    }

    public void dumpLocked(FileDescriptor fd, PrintWriter pw, String[] args) {
        final long now = SystemClock.uptimeMillis();
        ArrayMap<String, SparseArray<PackageState>> pmap = mState.mPackages.getMap();
        pw.println("Per-Package Process Stats:");
        for (int ip=0; ip<pmap.size(); ip++) {
            String procName = pmap.keyAt(ip);
            SparseArray<PackageState> procs = pmap.valueAt(ip);
            for (int iu=0; iu<procs.size(); iu++) {
                int uid = procs.keyAt(iu);
                PackageState state = procs.valueAt(iu);
                pw.print("  * "); pw.print(procName); pw.print(" / ");
                        UserHandle.formatUid(pw, uid); pw.println(":");
                for (int iproc=0; iproc<state.mProcesses.size(); iproc++) {
                    pw.print("      Process ");
                    pw.print(state.mProcesses.keyAt(iproc));
                    pw.println(":");
                    long totalTime = 0;
                    ProcessState proc = state.mProcesses.valueAt(iproc);
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
                                if (time != 0) {
                                    pw.print("        ");
                                    printScreenLabel(pw, printedScreen != iscreen
                                            ? iscreen : STATE_NOTHING);
                                    printedScreen = iscreen;
                                    printMemLabel(pw, printedMem != imem ? imem : STATE_NOTHING);
                                    printedMem = imem;
                                    pw.print(STATE_NAMES[is]); pw.print(": ");
                                    TimeUtils.formatDuration(time, pw); pw.println(running);
                                    totalTime += time;
                                }
                            }
                        }
                    }
                    if (totalTime != 0) {
                        pw.print("        ");
                        printScreenLabel(pw, STATE_NOTHING);
                        printMemLabel(pw, STATE_NOTHING);
                        pw.print("TOTAL      : ");
                        TimeUtils.formatDuration(totalTime, pw);
                        pw.println();
                    }
                }
                for (int isvc=0; isvc<state.mServices.size(); isvc++) {
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
                }
            }
        }
        dumpFilteredProcesses(pw, "Processes running while critical mem:", "  ",
                new int[] {ADJ_SCREEN_OFF, ADJ_SCREEN_ON},
                new int[] {ADJ_MEM_FACTOR_CRITICAL},
                new int[] {STATE_TOP, STATE_FOREGROUND, STATE_VISIBLE, STATE_PERCEPTIBLE,
                        STATE_BACKUP, STATE_SERVICE, STATE_HOME, STATE_PREVIOUS},
                now);
        dumpFilteredProcesses(pw, "Processes running while low mem:", "  ",
                new int[] {ADJ_SCREEN_OFF, ADJ_SCREEN_ON},
                new int[] {ADJ_MEM_FACTOR_LOW},
                new int[] {STATE_TOP, STATE_FOREGROUND, STATE_VISIBLE, STATE_PERCEPTIBLE,
                        STATE_BACKUP, STATE_SERVICE, STATE_HOME, STATE_PREVIOUS},
                now);
        pw.println();
        pw.println("Run time Stats:");
        dumpSingleTime(pw, "  ", mState.mMemFactorDurations, mState.mMemFactor,
                mState.mStartTime, now);
    }
}
