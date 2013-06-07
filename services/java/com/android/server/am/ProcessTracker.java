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
    public static final int STATE_SCREEN_ON_MOD = STATE_CACHED+1;
    public static final int STATE_COUNT = STATE_SCREEN_ON_MOD*2;

    static String[] STATE_NAMES = new String[] {
            "Top        ", "Foreground ", "Visible    ", "Perceptible", "Backup     ",
            "Service    ", "Home       ", "Previous   ", "Cached     "
    };

    public static final class ProcessState {
        final long[] mTimes = new long[STATE_COUNT];
        int mCurState = STATE_NOTHING;
        long mStartTime;

        public void setState(int state, long now) {
            if (mCurState != STATE_NOTHING) {
                mTimes[mCurState] += now - mStartTime;
            }
            mCurState = state;
            mStartTime = now;
        }
    }

    static final class State {
        final ProcessMap<ProcessState> mProcesses = new ProcessMap<ProcessState>();
    }

    final State mState = new State();

    public ProcessTracker() {
    }

    public ProcessState getStateLocked(String name, int uid) {
        ProcessState ps = mState.mProcesses.get(name, uid);
        if (ps != null) {
            return ps;
        }
        ps = new ProcessState();
        mState.mProcesses.put(name, uid, ps);
        return ps;
    }

    public void dumpLocked(FileDescriptor fd, PrintWriter pw, String[] args) {
        final long now = SystemClock.uptimeMillis();
        ArrayMap<String, SparseArray<ProcessState>> pmap = mState.mProcesses.getMap();
        for (int ip=0; ip<pmap.size(); ip++) {
            String procName = pmap.keyAt(ip);
            SparseArray<ProcessState> procs = pmap.valueAt(ip);
            for (int iu=0; iu<procs.size(); iu++) {
                int uid = procs.keyAt(iu);
                ProcessState state = procs.valueAt(iu);
                pw.print("  "); pw.print(procName); pw.print(" / "); pw.print(uid); pw.println(":");
                long totalTime = 0;
                for (int is=0; is<STATE_NAMES.length; is++) {
                    long time = state.mTimes[is];
                    if (state.mCurState == is) {
                        time += now - state.mStartTime;
                    }
                    if (time != 0) {
                        pw.print("    "); pw.print(STATE_NAMES[is]); pw.print(": ");
                        TimeUtils.formatDuration(time, pw); pw.println();
                        totalTime += time;
                    }
                }
                if (totalTime != 0) {
                    pw.print("    TOTAL      : ");
                    TimeUtils.formatDuration(totalTime, pw);
                    pw.println();
                }
            }
        }
    }
}
