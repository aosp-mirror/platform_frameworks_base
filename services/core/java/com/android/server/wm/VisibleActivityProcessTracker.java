/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.wm;

import android.util.ArrayMap;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;

import java.io.PrintWriter;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

/**
 * A quick lookup for all processes with visible activities. It also tracks the CPU usage of
 * host process with foreground (resumed) activity.
 */
class VisibleActivityProcessTracker {
    @GuardedBy("mProcMap")
    private final ArrayMap<WindowProcessController, CpuTimeRecord> mProcMap = new ArrayMap<>();
    final Executor mBgExecutor = BackgroundThread.getExecutor();
    final ActivityTaskManagerService mAtms;

    VisibleActivityProcessTracker(ActivityTaskManagerService atms) {
        mAtms = atms;
    }

    /** Called when any activity is visible in the process that didn't have one. */
    void onAnyActivityVisible(WindowProcessController wpc) {
        final CpuTimeRecord r = new CpuTimeRecord(wpc);
        synchronized (mProcMap) {
            mProcMap.put(wpc, r);
        }
        if (wpc.hasResumedActivity()) {
            r.mShouldGetCpuTime = true;
            mBgExecutor.execute(r);
        }
    }

    /** Called when all visible activities of the process are no longer visible. */
    void onAllActivitiesInvisible(WindowProcessController wpc) {
        final CpuTimeRecord r = removeProcess(wpc);
        if (r != null && r.mShouldGetCpuTime) {
            mBgExecutor.execute(r);
        }
    }

    /** Called when an activity is resumed on a process which is known to have visible activity. */
    void onActivityResumedWhileVisible(WindowProcessController wpc) {
        final CpuTimeRecord r;
        synchronized (mProcMap) {
            r = mProcMap.get(wpc);
        }
        if (r != null && !r.mShouldGetCpuTime) {
            r.mShouldGetCpuTime = true;
            mBgExecutor.execute(r);
        }
    }

    boolean hasResumedActivity(int uid) {
        return match(uid, WindowProcessController::hasResumedActivity);
    }

    /**
     * Returns {@code true} if the uid has a process that contains an activity with
     * {@link ActivityRecord#mVisibleRequested} or {@link ActivityRecord#isVisible()} is true.
     */
    boolean hasVisibleActivity(int uid) {
        return match(uid, null /* predicate */);
    }

    private boolean match(int uid, Predicate<WindowProcessController> predicate) {
        synchronized (mProcMap) {
            for (int i = mProcMap.size() - 1; i >= 0; i--) {
                final WindowProcessController wpc = mProcMap.keyAt(i);
                if (wpc.mUid == uid && (predicate == null || predicate.test(wpc))) {
                    return true;
                }
            }
        }
        return false;
    }

    CpuTimeRecord removeProcess(WindowProcessController wpc) {
        synchronized (mProcMap) {
            return mProcMap.remove(wpc);
        }
    }

    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix + "VisibleActivityProcess:[");
        synchronized (mProcMap) {
            for (int i = mProcMap.size() - 1; i >= 0; i--) {
                pw.print(" " + mProcMap.keyAt(i));
            }
        }
        pw.println("]");
    }

    /**
     * Get CPU time in background thread because it will access proc files or the lock of cpu
     * tracker is held by a background thread.
     */
    private class CpuTimeRecord implements Runnable {
        private final WindowProcessController mProc;
        private long mCpuTime;
        private boolean mHasStartCpuTime;
        boolean mShouldGetCpuTime;

        CpuTimeRecord(WindowProcessController wpc) {
            mProc = wpc;
        }

        @Override
        public void run() {
            if (mProc.getPid() == 0) {
                // The process is dead.
                return;
            }
            if (!mHasStartCpuTime) {
                mHasStartCpuTime = true;
                mCpuTime = mProc.getCpuTime();
            } else {
                final long diff = mProc.getCpuTime() - mCpuTime;
                if (diff > 0) {
                    mAtms.mAmInternal.updateForegroundTimeIfOnBattery(
                            mProc.mInfo.packageName, mProc.mInfo.uid, diff);
                }
            }
        }
    }
}
