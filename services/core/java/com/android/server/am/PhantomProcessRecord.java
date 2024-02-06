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

package com.android.server.am;

import static android.os.Process.PROC_NEWLINE_TERM;
import static android.os.Process.PROC_OUT_LONG;

import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.os.Handler;
import android.os.Process;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Slog;
import android.util.TimeUtils;

import com.android.internal.annotations.GuardedBy;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.function.Consumer;

/**
 * The "phantom" app processes, which are forked by app processes so we are not aware of
 * them until we walk through the process list in /proc.
 */
public final class PhantomProcessRecord {
    static final String TAG = TAG_WITH_CLASS_NAME ? "PhantomProcessRecord" : TAG_AM;

    static final long[] LONG_OUT = new long[1];
    static final int[] LONG_FORMAT = new int[] {PROC_NEWLINE_TERM | PROC_OUT_LONG};

    final String mProcessName;   // name of the process
    final int mUid;              // uid of the process
    final int mPid;              // The id of the process
    final int mPpid;             // Ancestor (managed app process) pid of the process
    final long mKnownSince;      // The timestamp when we're aware of the process
    final FileDescriptor mPidFd; // The fd to monitor the termination of this process

    long mLastCputime;           // How long proc has run CPU at last check
    long mCurrentCputime;        // How long proc has run CPU most recently
    int mUpdateSeq;              // Seq no, indicating the last check on this process
    int mAdj;                    // The last known oom adj score
    boolean mKilled;             // Whether it has been killed by us or not
    boolean mZombie;             // Whether it was signaled to be killed but timed out
    String mStringName;          // Caching of the toString() result

    final ActivityManagerService mService;
    final Object mLock;
    final Consumer<PhantomProcessRecord> mOnKillListener;
    final Handler mKillHandler;

    PhantomProcessRecord(final String processName, final int uid, final int pid,
            final int ppid, final ActivityManagerService service,
            final Consumer<PhantomProcessRecord> onKillListener) throws IllegalStateException {
        mProcessName = processName;
        mUid = uid;
        mPid = pid;
        mPpid = ppid;
        mKilled = false;
        mAdj = ProcessList.NATIVE_ADJ;
        mKnownSince = SystemClock.elapsedRealtime();
        mService = service;
        mLock = service.mPhantomProcessList.mLock;
        mOnKillListener = onKillListener;
        mKillHandler = service.mProcessList.sKillHandler;
        if (Process.supportsPidFd()) {
            StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskReads();
            try {
                mPidFd = Process.openPidFd(pid, 0);
                if (mPidFd == null) {
                    throw new IllegalStateException();
                }
            } catch (IOException e) {
                // Maybe a race condition, the process is gone.
                Slog.w(TAG, "Unable to open process " + pid + ", it might be gone");
                IllegalStateException ex = new IllegalStateException();
                ex.initCause(e);
                throw ex;
            } finally {
                StrictMode.setThreadPolicy(oldPolicy);
            }
        } else {
            mPidFd = null;
        }
    }

    public long getRss(int pid) {
        long[] rss = Process.getRss(pid);
        return (rss != null && rss.length > 0) ? rss[0] : 0;
    }

    @GuardedBy("mLock")
    void killLocked(String reason, boolean noisy) {
        if (!mKilled) {
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "kill");
            if (noisy || mUid == mService.mCurOomAdjUid) {
                mService.reportUidInfoMessageLocked(TAG,
                        "Killing " + toString() + ": " + reason, mUid);
            }
            if (mPid > 0) {
                EventLog.writeEvent(EventLogTags.AM_KILL, UserHandle.getUserId(mUid),
                        mPid, mProcessName, mAdj, reason, getRss(mPid));
                if (!Process.supportsPidFd()) {
                    onProcDied(false);
                } else {
                    // We'll notify the listener when we're notified it's dead.
                    // Meanwhile, we'd also need handle the case of zombie processes.
                    mKillHandler.postDelayed(mProcKillTimer, this,
                            mService.mConstants.mProcessKillTimeoutMs);
                }
                Process.killProcessQuiet(mPid);
                ProcessList.killProcessGroup(mUid, mPid);
            }
            mKilled = true;
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    private Runnable mProcKillTimer = new Runnable() {
        @Override
        public void run() {
            synchronized (mLock) {
                // The process is maybe in either D or Z state.
                Slog.w(TAG, "Process " + toString() + " is still alive after "
                        + mService.mConstants.mProcessKillTimeoutMs + "ms");
                // Force a cleanup as we can't keep the fd open forever
                mZombie = true;
                onProcDied(false);
                // But still bookkeep it, so it won't be added as a new one if it's spotted again.
            }
        }
    };

    @GuardedBy("mLock")
    void updateAdjLocked() {
        if (Process.readProcFile("/proc/" + mPid + "/oom_score_adj",
                LONG_FORMAT, null, LONG_OUT, null)) {
            mAdj = (int) LONG_OUT[0];
        }
    }

    @GuardedBy("mLock")
    void onProcDied(boolean reallyDead) {
        if (reallyDead) {
            Slog.i(TAG, "Process " + toString() + " died");
        }
        mKillHandler.removeCallbacks(mProcKillTimer, this);
        if (mOnKillListener != null) {
            mOnKillListener.accept(this);
        }
    }

    @Override
    public String toString() {
        if (mStringName != null) {
            return mStringName;
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append("PhantomProcessRecord {");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(' ');
        sb.append(mPid);
        sb.append(':');
        sb.append(mPpid);
        sb.append(':');
        sb.append(mProcessName);
        sb.append('/');
        if (mUid < Process.FIRST_APPLICATION_UID) {
            sb.append(mUid);
        } else {
            sb.append('u');
            sb.append(UserHandle.getUserId(mUid));
            int appId = UserHandle.getAppId(mUid);
            if (appId >= Process.FIRST_APPLICATION_UID) {
                sb.append('a');
                sb.append(appId - Process.FIRST_APPLICATION_UID);
            } else {
                sb.append('s');
                sb.append(appId);
            }
            if (appId >= Process.FIRST_ISOLATED_UID && appId <= Process.LAST_ISOLATED_UID) {
                sb.append('i');
                sb.append(appId - Process.FIRST_ISOLATED_UID);
            }
        }
        sb.append('}');
        return mStringName = sb.toString();
    }

    void dump(PrintWriter pw, String prefix) {
        final long now = SystemClock.elapsedRealtime();
        pw.print(prefix);
        pw.print("user #");
        pw.print(UserHandle.getUserId(mUid));
        pw.print(" uid=");
        pw.print(mUid);
        pw.print(" pid=");
        pw.print(mPid);
        pw.print(" ppid=");
        pw.print(mPpid);
        pw.print(" knownSince=");
        TimeUtils.formatDuration(mKnownSince, now, pw);
        pw.print(" killed=");
        pw.println(mKilled);
        pw.print(prefix);
        pw.print("lastCpuTime=");
        pw.print(mLastCputime);
        if (mLastCputime > 0) {
            pw.print(" timeUsed=");
            TimeUtils.formatDuration(mCurrentCputime - mLastCputime, pw);
        }
        pw.print(" oom adj=");
        pw.print(mAdj);
        pw.print(" seq=");
        pw.println(mUpdateSeq);
    }

    boolean equals(final String processName, final int uid, final int pid) {
        return mUid == uid && mPid == pid && TextUtils.equals(mProcessName, processName);
    }
}
