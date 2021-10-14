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

import static android.os.MessageQueue.OnFileDescriptorEventListener.EVENT_ERROR;
import static android.os.MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT;

import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PROCESSES;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.app.ApplicationExitInfo.Reason;
import android.app.ApplicationExitInfo.SubReason;
import android.os.Handler;
import android.os.Process;
import android.os.StrictMode;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.ProcStatsUtil;
import com.android.internal.os.ProcessCpuTracker;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.function.Function;

/**
 * Activity manager code dealing with phantom processes.
 */
public final class PhantomProcessList {
    static final String TAG = TAG_WITH_CLASS_NAME ? "PhantomProcessList" : TAG_AM;

    final Object mLock = new Object();

    /**
     * All of the phantom process record we track, key is the pid of the process.
     */
    @GuardedBy("mLock")
    final SparseArray<PhantomProcessRecord> mPhantomProcesses = new SparseArray<>();

    /**
     * The mapping between app processes and their phantom processess, outer key is the pid of
     * the app process, while the inner key is the pid of the phantom process.
     */
    @GuardedBy("mLock")
    final SparseArray<SparseArray<PhantomProcessRecord>> mAppPhantomProcessMap =
            new SparseArray<>();

    /**
     * The mapping of the pidfd to PhantomProcessRecord.
     */
    @GuardedBy("mLock")
    final SparseArray<PhantomProcessRecord> mPhantomProcessesPidFds = new SparseArray<>();

    /**
     * The list of phantom processes tha's being signaled to be killed but still undead yet.
     */
    @GuardedBy("mLock")
    final SparseArray<PhantomProcessRecord> mZombiePhantomProcesses = new SparseArray<>();

    @GuardedBy("mLock")
    private final ArrayList<PhantomProcessRecord> mTempPhantomProcesses = new ArrayList<>();

    /**
     * The mapping between a phantom process ID to its parent process (an app process)
     */
    @GuardedBy("mLock")
    private final SparseArray<ProcessRecord> mPhantomToAppProcessMap = new SparseArray<>();

    @GuardedBy("mLock")
    private final SparseArray<InputStream> mCgroupProcsFds = new SparseArray<>();

    @GuardedBy("mLock")
    private final byte[] mDataBuffer = new byte[4096];

    @GuardedBy("mLock")
    private boolean mTrimPhantomProcessScheduled = false;

    @GuardedBy("mLock")
    int mUpdateSeq;

    @VisibleForTesting
    Injector mInjector;

    private final ActivityManagerService mService;
    private final Handler mKillHandler;

    private static final int CGROUP_V1 = 0;
    private static final int CGROUP_V2 = 1;
    private static final String[] CGROUP_PATH_PREFIXES = {
        "/acct/uid_" /* cgroup v1 */,
        "/sys/fs/cgroup/uid_" /* cgroup v2 */
    };
    private static final String CGROUP_PID_PREFIX = "/pid_";
    private static final String CGROUP_PROCS = "/cgroup.procs";

    @VisibleForTesting
    int mCgroupVersion = CGROUP_V1;

    PhantomProcessList(final ActivityManagerService service) {
        mService = service;
        mKillHandler = service.mProcessList.sKillHandler;
        mInjector = new Injector();
        probeCgroupVersion();
    }

    @VisibleForTesting
    @GuardedBy("mLock")
    void lookForPhantomProcessesLocked() {
        mPhantomToAppProcessMap.clear();
        StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskReads();
        try {
            synchronized (mService.mPidsSelfLocked) {
                for (int i = mService.mPidsSelfLocked.size() - 1; i >= 0; i--) {
                    final ProcessRecord app = mService.mPidsSelfLocked.valueAt(i);
                    lookForPhantomProcessesLocked(app);
                }
            }
        } finally {
            StrictMode.setThreadPolicy(oldPolicy);
        }
    }

    @GuardedBy({"mLock", "mService.mPidsSelfLocked"})
    private void lookForPhantomProcessesLocked(ProcessRecord app) {
        if (app.appZygote || app.isKilled() || app.isKilledByAm()) {
            // process forked from app zygote doesn't have its own acct entry
            return;
        }
        final int appPid = app.getPid();
        InputStream input = mCgroupProcsFds.get(appPid);
        if (input == null) {
            final String path = getCgroupFilePath(app.info.uid, appPid);
            try {
                input = mInjector.openCgroupProcs(path);
            } catch (FileNotFoundException | SecurityException e) {
                if (DEBUG_PROCESSES) {
                    Slog.w(TAG, "Unable to open " + path, e);
                }
                return;
            }
            // Keep the FD open for better performance
            mCgroupProcsFds.put(appPid, input);
        }
        final byte[] buf = mDataBuffer;
        try {
            int read = 0;
            int pid = 0;
            long totalRead = 0;
            do {
                read = mInjector.readCgroupProcs(input, buf, 0, buf.length);
                if (read == -1) {
                    break;
                }
                totalRead += read;
                for (int i = 0; i < read; i++) {
                    final byte b = buf[i];
                    if (b == '\n') {
                        addChildPidLocked(app, pid, appPid);
                        pid = 0;
                    } else {
                        pid = pid * 10 + (b - '0');
                    }
                }
                if (read < buf.length) {
                    // we may break from here safely as sysfs reading should return the whole page
                    // if the remaining data is larger than a page
                    break;
                }
            } while (true);
            if (pid != 0) {
                addChildPidLocked(app, pid, appPid);
            }
            // rewind the fd for the next read
            input.skip(-totalRead);
        } catch (IOException e) {
            Slog.e(TAG, "Error in reading cgroup procs from " + app, e);
            IoUtils.closeQuietly(input);
            mCgroupProcsFds.delete(appPid);
        }
    }

    private void probeCgroupVersion() {
        for (int i = CGROUP_PATH_PREFIXES.length - 1; i >= 0; i--) {
            if ((new File(CGROUP_PATH_PREFIXES[i] + Process.SYSTEM_UID)).exists()) {
                mCgroupVersion = i;
                break;
            }
        }
    }

    @VisibleForTesting
    String getCgroupFilePath(int uid, int pid) {
        return CGROUP_PATH_PREFIXES[mCgroupVersion] + uid + CGROUP_PID_PREFIX + pid + CGROUP_PROCS;
    }

    static String getProcessName(int pid) {
        String procName = ProcStatsUtil.readTerminatedProcFile(
                "/proc/" + pid + "/cmdline", (byte) '\0');
        if (procName == null) {
            return null;
        }
        int l = procName.lastIndexOf('/');
        if (l > 0 && l < procName.length() - 1) {
            procName = procName.substring(l + 1);
        }
        return procName;
    }

    @GuardedBy({"mLock", "mService.mPidsSelfLocked"})
    private void addChildPidLocked(final ProcessRecord app, final int pid, final int appPid) {
        if (appPid != pid) {
            // That's something else...
            final ProcessRecord r = mService.mPidsSelfLocked.get(pid);
            if (r != null) {
                // Is this a process forked via app zygote?
                if (!r.appZygote) {
                    // Unexpected...
                    if (DEBUG_PROCESSES) {
                        Slog.w(TAG, "Unexpected: " + r + " appears in the cgroup.procs of " + app);
                    }
                } else {
                    // Just a child process of app zygote, no worries
                }
            } else {
                final int index = mPhantomToAppProcessMap.indexOfKey(pid);
                if (index >= 0) { // unlikely since we cleared the map at the beginning
                    final ProcessRecord current = mPhantomToAppProcessMap.valueAt(index);
                    if (app == current) {
                        // Okay it's unchanged
                        return;
                    }
                    mPhantomToAppProcessMap.setValueAt(index, app);
                } else {
                    mPhantomToAppProcessMap.put(pid, app);
                }
                // Its UID isn't necessarily to be the same as the app.info.uid, since it could be
                // forked from child processes of app zygote
                final int uid = Process.getUidForPid(pid);
                String procName = mInjector.getProcessName(pid);
                if (procName == null || uid < 0) {
                    mPhantomToAppProcessMap.delete(pid);
                    return;
                }
                getOrCreatePhantomProcessIfNeededLocked(procName, uid, pid, true);
            }
        }
    }

    void onAppDied(final int pid) {
        synchronized (mLock) {
            final int index = mCgroupProcsFds.indexOfKey(pid);
            if (index >= 0) {
                final InputStream inputStream = mCgroupProcsFds.valueAt(index);
                mCgroupProcsFds.removeAt(index);
                IoUtils.closeQuietly(inputStream);
            }
        }
    }

    /**
     * Get the existing phantom process record, or create if it's not existing yet;
     * however, before creating it, we'll check if this is really a phantom process
     * and we'll return null if it's not.
     */
    @GuardedBy("mLock")
    PhantomProcessRecord getOrCreatePhantomProcessIfNeededLocked(final String processName,
            final int uid, final int pid, boolean createIfNeeded) {
        // First check if it's actually an app process we know
        if (isAppProcess(pid)) {
            return null;
        }

        // Have we already been aware of this?
        final int index = mPhantomProcesses.indexOfKey(pid);
        if (index >= 0) {
            final PhantomProcessRecord proc = mPhantomProcesses.valueAt(index);
            if (proc.equals(processName, uid, pid)) {
                return proc;
            }
            // Somehow our record doesn't match, remove it anyway
            Slog.w(TAG, "Stale " + proc + ", removing");
            onPhantomProcessKilledLocked(proc);
        } else {
            // Is this one of the zombie processes we've known?
            final int idx = mZombiePhantomProcesses.indexOfKey(pid);
            if (idx >= 0) {
                final PhantomProcessRecord proc = mZombiePhantomProcesses.valueAt(idx);
                if (proc.equals(processName, uid, pid)) {
                    return proc;
                }
                // Our zombie process information is outdated, let's remove this one, it should
                // have been gone.
                mZombiePhantomProcesses.removeAt(idx);
            }
        }

        if (!createIfNeeded) {
            return null;
        }

        final ProcessRecord r = mPhantomToAppProcessMap.get(pid);

        if (r != null) {
            // It's a phantom process, bookkeep it
            try {
                final int appPid = r.getPid();
                final PhantomProcessRecord proc = new PhantomProcessRecord(
                        processName, uid, pid, appPid, mService,
                        this::onPhantomProcessKilledLocked);
                proc.mUpdateSeq = mUpdateSeq;
                mPhantomProcesses.put(pid, proc);
                SparseArray<PhantomProcessRecord> array = mAppPhantomProcessMap.get(appPid);
                if (array == null) {
                    array = new SparseArray<>();
                    mAppPhantomProcessMap.put(appPid, array);
                }
                array.put(pid, proc);
                if (proc.mPidFd != null) {
                    mKillHandler.getLooper().getQueue().addOnFileDescriptorEventListener(
                            proc.mPidFd, EVENT_INPUT | EVENT_ERROR,
                            this::onPhantomProcessFdEvent);
                    mPhantomProcessesPidFds.put(proc.mPidFd.getInt$(), proc);
                }
                scheduleTrimPhantomProcessesLocked();
                return proc;
            } catch (IllegalStateException e) {
                return null;
            }
        }
        return null;
    }

    private boolean isAppProcess(int pid) {
        synchronized (mService.mPidsSelfLocked) {
            return mService.mPidsSelfLocked.get(pid) != null;
        }
    }

    private int onPhantomProcessFdEvent(FileDescriptor fd, int events) {
        synchronized (mLock) {
            final PhantomProcessRecord proc = mPhantomProcessesPidFds.get(fd.getInt$());
            if (proc == null) {
                return 0;
            }
            if ((events & EVENT_INPUT) != 0) {
                proc.onProcDied(true);
            } else {
                // EVENT_ERROR, kill the process
                proc.killLocked("Process error", true);
            }
        }
        return 0;
    }

    @GuardedBy("mLock")
    private void onPhantomProcessKilledLocked(final PhantomProcessRecord proc) {
        if (proc.mPidFd != null && proc.mPidFd.valid()) {
            mKillHandler.getLooper().getQueue()
                    .removeOnFileDescriptorEventListener(proc.mPidFd);
            mPhantomProcessesPidFds.remove(proc.mPidFd.getInt$());
            IoUtils.closeQuietly(proc.mPidFd);
        }
        mPhantomProcesses.remove(proc.mPid);
        final int index = mAppPhantomProcessMap.indexOfKey(proc.mPpid);
        if (index < 0) {
            return;
        }
        SparseArray<PhantomProcessRecord> array = mAppPhantomProcessMap.valueAt(index);
        array.remove(proc.mPid);
        if (array.size() == 0) {
            mAppPhantomProcessMap.removeAt(index);
        }
        if (proc.mZombie) {
            // If it's not really dead, bookkeep it
            mZombiePhantomProcesses.put(proc.mPid, proc);
        } else {
            // In case of race condition, let's try to remove it from zombie list
            mZombiePhantomProcesses.remove(proc.mPid);
        }
    }

    @GuardedBy("mLock")
    private void scheduleTrimPhantomProcessesLocked() {
        if (!mTrimPhantomProcessScheduled) {
            mTrimPhantomProcessScheduled = true;
            mService.mHandler.post(this::trimPhantomProcessesIfNecessary);
        }
    }

    /**
     * Clamp the number of phantom processes to
     * {@link ActivityManagerConstants#MAX_PHANTOM_PROCESSE}, kills those surpluses in the
     * order of the oom adjs of their parent process.
     */
    void trimPhantomProcessesIfNecessary() {
        synchronized (mService.mProcLock) {
            synchronized (mLock) {
                mTrimPhantomProcessScheduled = false;
                if (mService.mConstants.MAX_PHANTOM_PROCESSES < mPhantomProcesses.size()) {
                    for (int i = mPhantomProcesses.size() - 1; i >= 0; i--) {
                        mTempPhantomProcesses.add(mPhantomProcesses.valueAt(i));
                    }
                    synchronized (mService.mPidsSelfLocked) {
                        Collections.sort(mTempPhantomProcesses, (a, b) -> {
                            final ProcessRecord ra = mService.mPidsSelfLocked.get(a.mPpid);
                            if (ra == null) {
                                // parent is gone, this process should have been killed too
                                return 1;
                            }
                            final ProcessRecord rb = mService.mPidsSelfLocked.get(b.mPpid);
                            if (rb == null) {
                                // parent is gone, this process should have been killed too
                                return -1;
                            }
                            if (ra.mState.getCurAdj() != rb.mState.getCurAdj()) {
                                return ra.mState.getCurAdj() - rb.mState.getCurAdj();
                            }
                            if (a.mKnownSince != b.mKnownSince) {
                                // In case of identical oom adj, younger one first
                                return a.mKnownSince < b.mKnownSince ? 1 : -1;
                            }
                            return 0;
                        });
                    }
                    for (int i = mTempPhantomProcesses.size() - 1;
                            i >= mService.mConstants.MAX_PHANTOM_PROCESSES; i--) {
                        final PhantomProcessRecord proc = mTempPhantomProcesses.get(i);
                        proc.killLocked("Trimming phantom processes", true);
                    }
                    mTempPhantomProcesses.clear();
                }
            }
        }
    }

    /**
     * Remove all entries with outdated seq num.
     */
    @GuardedBy("mLock")
    void pruneStaleProcessesLocked() {
        for (int i = mPhantomProcesses.size() - 1; i >= 0; i--) {
            final PhantomProcessRecord proc = mPhantomProcesses.valueAt(i);
            if (proc.mUpdateSeq < mUpdateSeq) {
                if (DEBUG_PROCESSES) {
                    Slog.v(TAG, "Pruning " + proc + " as it should have been dead.");
                }
                proc.killLocked("Stale process", true);
            }
        }
        for (int i = mZombiePhantomProcesses.size() - 1; i >= 0; i--) {
            final PhantomProcessRecord proc = mZombiePhantomProcesses.valueAt(i);
            if (proc.mUpdateSeq < mUpdateSeq) {
                if (DEBUG_PROCESSES) {
                    Slog.v(TAG, "Pruning " + proc + " as it should have been dead.");
                }
            }
        }
    }

    /**
     * Kill the given phantom process, all its siblings (if any) and their parent process
     */
    @GuardedBy("mService")
    void killPhantomProcessGroupLocked(ProcessRecord app, PhantomProcessRecord proc,
            @Reason int reasonCode, @SubReason int subReason, String msg) {
        synchronized (mLock) {
            int index = mAppPhantomProcessMap.indexOfKey(proc.mPpid);
            if (index >= 0) {
                final SparseArray<PhantomProcessRecord> array =
                        mAppPhantomProcessMap.valueAt(index);
                for (int i = array.size() - 1; i >= 0; i--) {
                    final PhantomProcessRecord r = array.valueAt(i);
                    if (r == proc) {
                        r.killLocked(msg, true);
                    } else {
                        r.killLocked("Caused by siling process: " + msg, false);
                    }
                }
            }
        }
        // Lastly, kill the parent process too
        app.killLocked("Caused by child process: " + msg, reasonCode, subReason, true);
    }

    /**
     * Iterate all phantom process belonging to the given app, and invokve callback
     * for each of them.
     */
    void forEachPhantomProcessOfApp(final ProcessRecord app,
            final Function<PhantomProcessRecord, Boolean> callback) {
        synchronized (mLock) {
            int index = mAppPhantomProcessMap.indexOfKey(app.getPid());
            if (index >= 0) {
                final SparseArray<PhantomProcessRecord> array =
                        mAppPhantomProcessMap.valueAt(index);
                for (int i = array.size() - 1; i >= 0; i--) {
                    final PhantomProcessRecord r = array.valueAt(i);
                    if (!callback.apply(r)) {
                        break;
                    }
                }
            }
        }
    }

    @GuardedBy("tracker")
    void updateProcessCpuStatesLocked(ProcessCpuTracker tracker) {
        synchronized (mLock) {
            // refresh the phantom process list with the latest cpu stats results.
            mUpdateSeq++;

            // Scan app process's accounting procs
            lookForPhantomProcessesLocked();

            for (int i = tracker.countStats() - 1; i >= 0; i--) {
                final ProcessCpuTracker.Stats st = tracker.getStats(i);
                final PhantomProcessRecord r =
                        getOrCreatePhantomProcessIfNeededLocked(st.name, st.uid, st.pid, false);
                if (r != null) {
                    r.mUpdateSeq = mUpdateSeq;
                    r.mCurrentCputime += st.rel_utime + st.rel_stime;
                    if (r.mLastCputime == 0) {
                        r.mLastCputime = r.mCurrentCputime;
                    }
                    r.updateAdjLocked();
                }
            }
            // remove the stale ones
            pruneStaleProcessesLocked();
        }
    }

    void dump(PrintWriter pw, String prefix) {
        synchronized (mLock) {
            dumpPhantomeProcessLocked(pw, prefix, "All Active App Child Processes:",
                    mPhantomProcesses);
            dumpPhantomeProcessLocked(pw, prefix, "All Zombie App Child Processes:",
                    mZombiePhantomProcesses);
        }
    }

    void dumpPhantomeProcessLocked(PrintWriter pw, String prefix, String headline,
            SparseArray<PhantomProcessRecord> list) {
        final int size = list.size();
        if (size == 0) {
            return;
        }
        pw.println();
        pw.print(prefix);
        pw.println(headline);
        for (int i = 0; i < size; i++) {
            final PhantomProcessRecord proc = list.valueAt(i);
            pw.print(prefix);
            pw.print("  proc #");
            pw.print(i);
            pw.print(": ");
            pw.println(proc.toString());
            proc.dump(pw, prefix + "    ");
        }
    }

    @VisibleForTesting
    static class Injector {
        InputStream openCgroupProcs(String path) throws FileNotFoundException, SecurityException {
            return new FileInputStream(path);
        }

        int readCgroupProcs(InputStream input, byte[] buf, int offset, int len) throws IOException {
            return input.read(buf, offset, len);
        }

        String getProcessName(final int pid) {
            return PhantomProcessList.getProcessName(pid);
        }
    }
}
