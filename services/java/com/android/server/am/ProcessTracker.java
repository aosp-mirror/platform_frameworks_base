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
import android.util.ArrayMap;
import android.util.SparseArray;
import android.util.TimeUtils;
import com.android.server.ProcessMap;

import java.io.FileDescriptor;
import java.io.PrintWriter;

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
    public static final int STATE_MEM_FACTOR_MOD = STATE_CACHED+1;
    public static final int STATE_MEM_FACTOR_NORMAL_ADJ = 0;
    public static final int STATE_MEM_FACTOR_MODERATE_ADJ = STATE_MEM_FACTOR_MOD;
    public static final int STATE_MEM_FACTOR_LOW_ADJ = STATE_MEM_FACTOR_MOD*2;
    public static final int STATE_MEM_FACTOR_CRITIAL_ADJ = STATE_MEM_FACTOR_MOD*3;
    public static final int STATE_MEM_FACTOR_COUNT = STATE_MEM_FACTOR_MOD*4;
    public static final int STATE_SCREEN_ON_MOD = STATE_MEM_FACTOR_COUNT;
    public static final int STATE_SCREEN_OFF_ADJ = 0;
    public static final int STATE_SCREEN_ON_ADJ = STATE_SCREEN_ON_MOD;
    public static final int STATE_COUNT = STATE_SCREEN_ON_MOD*2;

    static String[] STATE_NAMES = new String[] {
            "Top        ", "Foreground ", "Visible    ", "Perceptible", "Backup     ",
            "Service    ", "Home       ", "Previous   ", "Cached     "
    };

    public static final class ProcessState {
        final long[] mDurations = new long[STATE_COUNT];
        int mCurState = STATE_NOTHING;
        long mStartTime;

        public void setState(int state, int memFactor, long now) {
            if (state != STATE_NOTHING) {
                state += memFactor;
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
        long mStartedDuration;
        long mStartedTime;
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
        final long[] mMemFactorDurations = new long[STATE_COUNT/STATE_MEM_FACTOR_MOD];
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
        ps = new ProcessState();
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
            memFactor += STATE_SCREEN_ON_MOD;
        }
        if (memFactor != mState.mMemFactor) {
            if (mState.mMemFactor != STATE_NOTHING) {
                mState.mMemFactorDurations[mState.mMemFactor/STATE_MEM_FACTOR_MOD]
                        += now - mState.mStartTime;
            }
            mState.mMemFactor = memFactor;
            mState.mStartTime = now;
            return true;
        }
        return false;
    }

    public int getMemFactor() {
        return mState.mMemFactor != STATE_NOTHING ? mState.mMemFactor : 0;
    }

    private void printScreenLabel(PrintWriter pw, int offset) {
        switch (offset) {
            case STATE_NOTHING:
                pw.print("             ");
                break;
            case STATE_SCREEN_OFF_ADJ:
                pw.print("Screen Off / ");
                break;
            case STATE_SCREEN_ON_ADJ:
                pw.print("Screen On  / ");
                break;
            default:
                pw.print("?????????? / ");
                break;
        }
    }

    private void printMemLabel(PrintWriter pw, int offset) {
        switch (offset) {
            case STATE_NOTHING:
                pw.print("       ");
                break;
            case STATE_MEM_FACTOR_NORMAL_ADJ:
                pw.print("Norm / ");
                break;
            case STATE_MEM_FACTOR_MODERATE_ADJ:
                pw.print("Mod  / ");
                break;
            case STATE_MEM_FACTOR_LOW_ADJ:
                pw.print("Low  / ");
                break;
            case STATE_MEM_FACTOR_CRITIAL_ADJ:
                pw.print("Crit / ");
                break;
            default:
                pw.print("???? / ");
                break;
        }
    }

    public void dumpLocked(FileDescriptor fd, PrintWriter pw, String[] args) {
        final long now = SystemClock.uptimeMillis();
        ArrayMap<String, SparseArray<PackageState>> pmap = mState.mPackages.getMap();
        pw.println("Process Stats:");
        for (int ip=0; ip<pmap.size(); ip++) {
            String procName = pmap.keyAt(ip);
            SparseArray<PackageState> procs = pmap.valueAt(ip);
            for (int iu=0; iu<procs.size(); iu++) {
                int uid = procs.keyAt(iu);
                PackageState state = procs.valueAt(iu);
                pw.print("  "); pw.print(procName); pw.print(" / "); pw.print(uid); pw.println(":");
                for (int iproc=0; iproc<state.mProcesses.size(); iproc++) {
                    pw.print("    Process ");
                    pw.print(state.mProcesses.keyAt(iproc));
                    pw.println(":");
                    long totalTime = 0;
                    ProcessState proc = state.mProcesses.valueAt(iproc);
                    int printedScreen = -1;
                    for (int iscreen=0; iscreen<STATE_COUNT; iscreen+=STATE_SCREEN_ON_MOD) {
                        int printedMem = -1;
                        for (int imem=0; imem<STATE_MEM_FACTOR_COUNT; imem+=STATE_MEM_FACTOR_MOD) {
                            for (int is=0; is<STATE_NAMES.length; is++) {
                                int bucket = is+imem+iscreen;
                                long time = proc.mDurations[bucket];
                                String running = "";
                                if (proc.mCurState == bucket) {
                                    time += now - proc.mStartTime;
                                    running = " (running)";
                                }
                                if (time != 0) {
                                    pw.print("      ");
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
                        pw.print("      ");
                        printScreenLabel(pw, STATE_NOTHING);
                        printMemLabel(pw, STATE_NOTHING);
                        pw.print("TOTAL      : ");
                        TimeUtils.formatDuration(totalTime, pw);
                        pw.println();
                    }
                }
                for (int isvc=0; isvc<state.mServices.size(); isvc++) {
                    pw.print("    Service ");
                    pw.print(state.mServices.keyAt(isvc));
                    pw.println(":");
                    ServiceState svc = state.mServices.valueAt(isvc);
                    long time = svc.mStartedDuration;
                    if (svc.mStartedTime >= 0) {
                        time += now - svc.mStartedTime;
                    }
                    if (time != 0) {
                        pw.print("    Started: ");
                        TimeUtils.formatDuration(time, pw); pw.println();
                    }
                }
            }
        }
        pw.println();
        pw.println("Run time Stats:");
        long totalTime = 0;
        int printedScreen = -1;
        for (int iscreen=0; iscreen<STATE_COUNT; iscreen+=STATE_SCREEN_ON_MOD) {
            int printedMem = -1;
            for (int imem=0; imem<STATE_MEM_FACTOR_COUNT; imem+=STATE_MEM_FACTOR_MOD) {
                int bucket = imem+iscreen;
                long time = mState.mMemFactorDurations[bucket/STATE_MEM_FACTOR_MOD];
                String running = "";
                if (mState.mMemFactor == bucket) {
                    time += now - mState.mStartTime;
                    running = " (running)";
                }
                if (time != 0) {
                    pw.print("  ");
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
            pw.print("  ");
            printScreenLabel(pw, STATE_NOTHING);
            pw.print("TOTAL: ");
            TimeUtils.formatDuration(totalTime, pw);
            pw.println();
        }
    }
}
