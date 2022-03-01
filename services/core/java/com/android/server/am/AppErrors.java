/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.am;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.content.pm.ApplicationInfo.FLAG_SYSTEM;

import static com.android.server.am.ActivityManagerConstants.PROCESS_CRASH_COUNT_LIMIT;
import static com.android.server.am.ActivityManagerConstants.PROCESS_CRASH_COUNT_RESET_INTERVAL;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.ActivityManagerService.MY_PID;
import static com.android.server.wm.ActivityTaskManagerService.RELAUNCH_REASON_FREE_RESIZE;
import static com.android.server.wm.ActivityTaskManagerService.RELAUNCH_REASON_NONE;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.AnrController;
import android.app.ApplicationErrorReport;
import android.app.ApplicationExitInfo;
import android.app.RemoteServiceException.CrashedByAdbException;
import android.app.usage.UsageStatsManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.VersionedPackage;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.ProcessMap;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.server.LocalServices;
import com.android.server.PackageWatchdog;
import com.android.server.usage.AppStandbyInternal;
import com.android.server.wm.WindowProcessController;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.List;

/**
 * Controls error conditions in applications.
 */
class AppErrors {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "AppErrors" : TAG_AM;

    private final ActivityManagerService mService;
    private final ActivityManagerGlobalLock mProcLock;
    private final Context mContext;
    private final PackageWatchdog mPackageWatchdog;

    @GuardedBy("mBadProcessLock")
    private ArraySet<String> mAppsNotReportingCrashes;

    /**
     * The last time that various processes have crashed since they were last explicitly started.
     */
    @GuardedBy("mBadProcessLock")
    private final ProcessMap<Long> mProcessCrashTimes = new ProcessMap<>();

    /**
     * The last time that various processes have crashed (not reset even when explicitly started).
     */
    @GuardedBy("mBadProcessLock")
    private final ProcessMap<Long> mProcessCrashTimesPersistent = new ProcessMap<>();

    /**
     * The last time that various processes have crashed and shown an error dialog.
     */
    @GuardedBy("mBadProcessLock")
    private final ProcessMap<Long> mProcessCrashShowDialogTimes = new ProcessMap<>();

    /**
     * A pairing between how many times various processes have crashed since a given time.
     * Entry and exit conditions for this map are similar to mProcessCrashTimes.
     */
    @GuardedBy("mBadProcessLock")
    private final ProcessMap<Pair<Long, Integer>> mProcessCrashCounts = new ProcessMap<>();

    /**
     * Set of applications that we consider to be bad, and will reject
     * incoming broadcasts from (which the user has no control over).
     * Processes are added to this set when they have crashed twice within
     * a minimum amount of time; they are removed from it when they are
     * later restarted (hopefully due to some user action).  The value is the
     * time it was added to the list.
     *
     * Read access is UNLOCKED, and must either be based on a single lookup
     * call on the current mBadProcesses instance, or a local copy of that
     * reference must be made and the local copy treated as the source of
     * truth.  Mutations are performed by synchronizing on mBadProcessLock,
     * cloning the existing mBadProcesses instance, performing the mutation,
     * then changing the volatile "live" mBadProcesses reference to point to the
     * mutated version.  These operations are very rare compared to lookups:
     * we intentionally trade additional cost for mutations for eliminating
     * lock operations from the simple lookup cases.
     */
    private volatile ProcessMap<BadProcessInfo> mBadProcesses = new ProcessMap<>();

    /**
     * Dedicated lock for {@link #mAppsNotReportingCrashes}, {@link #mProcessCrashTimes},
     * {@link #mProcessCrashTimesPersistent}, {@link #mProcessCrashShowDialogTimes},
     * {@link #mProcessCrashCounts} and {@link #mBadProcesses}.
     *
     * <p>The naming convention of the function with this lock should be "-LBp"</b>
     *
     * @See mBadProcesses
     */
    private final Object mBadProcessLock = new Object();

    AppErrors(Context context, ActivityManagerService service, PackageWatchdog watchdog) {
        context.assertRuntimeOverlayThemable();
        mService = service;
        mProcLock = service.mProcLock;
        mContext = context;
        mPackageWatchdog = watchdog;
    }

    /** Resets the current state but leaves the constructor-provided fields unchanged. */
    public void resetState() {
        Slog.i(TAG, "Resetting AppErrors");
        synchronized (mBadProcessLock) {
            mAppsNotReportingCrashes.clear();
            mProcessCrashTimes.clear();
            mProcessCrashTimesPersistent.clear();
            mProcessCrashShowDialogTimes.clear();
            mProcessCrashCounts.clear();
            mBadProcesses = new ProcessMap<>();
        }
    }

    @GuardedBy("mProcLock")
    void dumpDebugLPr(ProtoOutputStream proto, long fieldId, String dumpPackage) {
        final ProcessMap<BadProcessInfo> badProcesses = mBadProcesses;
        if (mProcessCrashTimes.getMap().isEmpty() && badProcesses.getMap().isEmpty()) {
            return;
        }

        final long token = proto.start(fieldId);
        final long now = SystemClock.uptimeMillis();
        proto.write(AppErrorsProto.NOW_UPTIME_MS, now);

        if (!badProcesses.getMap().isEmpty()) {
            final ArrayMap<String, SparseArray<BadProcessInfo>> pmap = badProcesses.getMap();
            final int processCount = pmap.size();
            for (int ip = 0; ip < processCount; ip++) {
                final long btoken = proto.start(AppErrorsProto.BAD_PROCESSES);
                final String pname = pmap.keyAt(ip);
                final SparseArray<BadProcessInfo> uids = pmap.valueAt(ip);
                final int uidCount = uids.size();

                proto.write(AppErrorsProto.BadProcess.PROCESS_NAME, pname);
                for (int i = 0; i < uidCount; i++) {
                    final int puid = uids.keyAt(i);
                    final ProcessRecord r = mService.getProcessNamesLOSP().get(pname, puid);
                    if (dumpPackage != null && (r == null
                            || !r.getPkgList().containsKey(dumpPackage))) {
                        continue;
                    }
                    final BadProcessInfo info = uids.valueAt(i);
                    final long etoken = proto.start(AppErrorsProto.BadProcess.ENTRIES);
                    proto.write(AppErrorsProto.BadProcess.Entry.UID, puid);
                    proto.write(AppErrorsProto.BadProcess.Entry.CRASHED_AT_MS, info.time);
                    proto.write(AppErrorsProto.BadProcess.Entry.SHORT_MSG, info.shortMsg);
                    proto.write(AppErrorsProto.BadProcess.Entry.LONG_MSG, info.longMsg);
                    proto.write(AppErrorsProto.BadProcess.Entry.STACK, info.stack);
                    proto.end(etoken);
                }
                proto.end(btoken);
            }
        }

        synchronized (mBadProcessLock) {
            if (!mProcessCrashTimes.getMap().isEmpty()) {
                final ArrayMap<String, SparseArray<Long>> pmap = mProcessCrashTimes.getMap();
                final int procCount = pmap.size();
                for (int ip = 0; ip < procCount; ip++) {
                    final long ctoken = proto.start(AppErrorsProto.PROCESS_CRASH_TIMES);
                    final String pname = pmap.keyAt(ip);
                    final SparseArray<Long> uids = pmap.valueAt(ip);
                    final int uidCount = uids.size();

                    proto.write(AppErrorsProto.ProcessCrashTime.PROCESS_NAME, pname);
                    for (int i = 0; i < uidCount; i++) {
                        final int puid = uids.keyAt(i);
                        final ProcessRecord r = mService.getProcessNamesLOSP().get(pname, puid);
                        if (dumpPackage != null
                                && (r == null || !r.getPkgList().containsKey(dumpPackage))) {
                            continue;
                        }
                        final long etoken = proto.start(AppErrorsProto.ProcessCrashTime.ENTRIES);
                        proto.write(AppErrorsProto.ProcessCrashTime.Entry.UID, puid);
                        proto.write(AppErrorsProto.ProcessCrashTime.Entry.LAST_CRASHED_AT_MS,
                                uids.valueAt(i));
                        proto.end(etoken);
                    }
                    proto.end(ctoken);
                }
            }
        }

        proto.end(token);
    }

    @GuardedBy("mProcLock")
    boolean dumpLPr(FileDescriptor fd, PrintWriter pw, boolean needSep, String dumpPackage) {
        final long now = SystemClock.uptimeMillis();
        synchronized (mBadProcessLock) {
            if (!mProcessCrashTimes.getMap().isEmpty()) {
                boolean printed = false;
                final ArrayMap<String, SparseArray<Long>> pmap = mProcessCrashTimes.getMap();
                final int processCount = pmap.size();
                for (int ip = 0; ip < processCount; ip++) {
                    final String pname = pmap.keyAt(ip);
                    final SparseArray<Long> uids = pmap.valueAt(ip);
                    final int uidCount = uids.size();
                    for (int i = 0; i < uidCount; i++) {
                        final int puid = uids.keyAt(i);
                        final ProcessRecord r = mService.getProcessNamesLOSP().get(pname, puid);
                        if (dumpPackage != null
                                && (r == null || !r.getPkgList().containsKey(dumpPackage))) {
                            continue;
                        }
                        if (!printed) {
                            if (needSep) pw.println();
                            needSep = true;
                            pw.println("  Time since processes crashed:");
                            printed = true;
                        }
                        pw.print("    Process "); pw.print(pname);
                        pw.print(" uid "); pw.print(puid);
                        pw.print(": last crashed ");
                        TimeUtils.formatDuration(now - uids.valueAt(i), pw);
                        pw.println(" ago");
                    }
                }
            }

            if (!mProcessCrashCounts.getMap().isEmpty()) {
                boolean printed = false;
                final ArrayMap<String, SparseArray<Pair<Long, Integer>>> pmap =
                        mProcessCrashCounts.getMap();
                final int processCount = pmap.size();
                for (int ip = 0; ip < processCount; ip++) {
                    final String pname = pmap.keyAt(ip);
                    final SparseArray<Pair<Long, Integer>> uids = pmap.valueAt(ip);
                    final int uidCount = uids.size();
                    for (int i = 0; i < uidCount; i++) {
                        final int puid = uids.keyAt(i);
                        final ProcessRecord r = mService.getProcessNamesLOSP().get(pname, puid);
                        if (dumpPackage != null
                                && (r == null || !r.getPkgList().containsKey(dumpPackage))) {
                            continue;
                        }
                        if (!printed) {
                            if (needSep) pw.println();
                            needSep = true;
                            pw.println("  First time processes crashed and counts:");
                            printed = true;
                        }
                        pw.print("    Process "); pw.print(pname);
                        pw.print(" uid "); pw.print(puid);
                        pw.print(": first crashed ");
                        TimeUtils.formatDuration(now - uids.valueAt(i).first, pw);
                        pw.print(" ago; crashes since then: "); pw.println(uids.valueAt(i).second);
                    }
                }
            }
        }

        final ProcessMap<BadProcessInfo> badProcesses = mBadProcesses;
        if (!badProcesses.getMap().isEmpty()) {
            boolean printed = false;
            final ArrayMap<String, SparseArray<BadProcessInfo>> pmap = badProcesses.getMap();
            final int processCount = pmap.size();
            for (int ip = 0; ip < processCount; ip++) {
                final String pname = pmap.keyAt(ip);
                final SparseArray<BadProcessInfo> uids = pmap.valueAt(ip);
                final int uidCount = uids.size();
                for (int i = 0; i < uidCount; i++) {
                    final int puid = uids.keyAt(i);
                    final ProcessRecord r = mService.getProcessNamesLOSP().get(pname, puid);
                    if (dumpPackage != null && (r == null
                            || !r.getPkgList().containsKey(dumpPackage))) {
                        continue;
                    }
                    if (!printed) {
                        if (needSep) pw.println();
                        needSep = true;
                        pw.println("  Bad processes:");
                        printed = true;
                    }
                    final BadProcessInfo info = uids.valueAt(i);
                    pw.print("    Bad process "); pw.print(pname);
                    pw.print(" uid "); pw.print(puid);
                    pw.print(": crashed at time "); pw.println(info.time);
                    if (info.shortMsg != null) {
                        pw.print("      Short msg: "); pw.println(info.shortMsg);
                    }
                    if (info.longMsg != null) {
                        pw.print("      Long msg: "); pw.println(info.longMsg);
                    }
                    if (info.stack != null) {
                        pw.println("      Stack:");
                        int lastPos = 0;
                        for (int pos = 0; pos < info.stack.length(); pos++) {
                            if (info.stack.charAt(pos) == '\n') {
                                pw.print("        ");
                                pw.write(info.stack, lastPos, pos - lastPos);
                                pw.println();
                                lastPos = pos + 1;
                            }
                        }
                        if (lastPos < info.stack.length()) {
                            pw.print("        ");
                            pw.write(info.stack, lastPos, info.stack.length() - lastPos);
                            pw.println();
                        }
                    }
                }
            }
        }
        return needSep;
    }

    boolean isBadProcess(final String processName, final int uid) {
        // NO LOCKING for the simple lookup
        return mBadProcesses.get(processName, uid) != null;
    }

    void clearBadProcess(final String processName, final int uid) {
        synchronized (mBadProcessLock) {
            final ProcessMap<BadProcessInfo> badProcesses = new ProcessMap<>();
            badProcesses.putAll(mBadProcesses);
            badProcesses.remove(processName, uid);
            mBadProcesses = badProcesses;
        }
    }

    void markBadProcess(final String processName, final int uid, BadProcessInfo info) {
        synchronized (mBadProcessLock) {
            final ProcessMap<BadProcessInfo> badProcesses = new ProcessMap<>();
            badProcesses.putAll(mBadProcesses);
            badProcesses.put(processName, uid, info);
            mBadProcesses = badProcesses;
        }
    }

    void resetProcessCrashTime(final String processName, final int uid) {
        synchronized (mBadProcessLock) {
            mProcessCrashTimes.remove(processName, uid);
            mProcessCrashCounts.remove(processName, uid);
        }
    }

    void resetProcessCrashTime(boolean resetEntireUser, int appId, int userId) {
        synchronized (mBadProcessLock) {
            final ArrayMap<String, SparseArray<Long>> pTimeMap = mProcessCrashTimes.getMap();
            for (int ip = pTimeMap.size() - 1; ip >= 0; ip--) {
                SparseArray<Long> ba = pTimeMap.valueAt(ip);
                resetProcessCrashMapLBp(ba, resetEntireUser, appId, userId);
                if (ba.size() == 0) {
                    pTimeMap.removeAt(ip);
                }
            }

            final ArrayMap<String, SparseArray<Pair<Long, Integer>>> pCountMap =
                    mProcessCrashCounts.getMap();
            for (int ip = pCountMap.size() - 1; ip >= 0; ip--) {
                SparseArray<Pair<Long, Integer>> ba = pCountMap.valueAt(ip);
                resetProcessCrashMapLBp(ba, resetEntireUser, appId, userId);
                if (ba.size() == 0) {
                    pCountMap.removeAt(ip);
                }
            }
        }
    }

    @GuardedBy("mBadProcessLock")
    private void resetProcessCrashMapLBp(SparseArray<?> ba, boolean resetEntireUser,
            int appId, int userId) {
        for (int i = ba.size() - 1; i >= 0; i--) {
            boolean remove = false;
            final int entUid = ba.keyAt(i);
            if (!resetEntireUser) {
                if (userId == UserHandle.USER_ALL) {
                    if (UserHandle.getAppId(entUid) == appId) {
                        remove = true;
                    }
                } else {
                    if (entUid == UserHandle.getUid(userId, appId)) {
                        remove = true;
                    }
                }
            } else if (UserHandle.getUserId(entUid) == userId) {
                remove = true;
            }
            if (remove) {
                ba.removeAt(i);
            }
        }
    }

    void loadAppsNotReportingCrashesFromConfig(String appsNotReportingCrashesConfig) {
        if (appsNotReportingCrashesConfig != null) {
            final String[] split = appsNotReportingCrashesConfig.split(",");
            if (split.length > 0) {
                synchronized (mBadProcessLock) {
                    mAppsNotReportingCrashes = new ArraySet<>();
                    Collections.addAll(mAppsNotReportingCrashes, split);
                }
            }
        }
    }

    @GuardedBy("mService")
    void killAppAtUserRequestLocked(ProcessRecord app) {
        ErrorDialogController controller = app.mErrorState.getDialogController();

        int reasonCode = ApplicationExitInfo.REASON_ANR;
        int subReason = ApplicationExitInfo.SUBREASON_UNKNOWN;
        synchronized (mProcLock) {
            if (controller.hasDebugWaitingDialog()) {
                reasonCode = ApplicationExitInfo.REASON_OTHER;
                subReason = ApplicationExitInfo.SUBREASON_WAIT_FOR_DEBUGGER;
            }
            controller.clearAllErrorDialogs();
            killAppImmediateLSP(app, reasonCode, subReason,
                    "user-terminated", "user request after error");
        }
    }

    @GuardedBy({"mService", "mProcLock"})
    private void killAppImmediateLSP(ProcessRecord app, int reasonCode, int subReason,
            String reason, String killReason) {
        final ProcessErrorStateRecord errState = app.mErrorState;
        errState.setCrashing(false);
        errState.setCrashingReport(null);
        errState.setNotResponding(false);
        errState.setNotRespondingReport(null);
        final int pid = errState.mApp.getPid();
        if (pid > 0 && pid != MY_PID) {
            synchronized (mBadProcessLock) {
                handleAppCrashLSPB(app, reason,
                        null /*shortMsg*/, null /*longMsg*/, null /*stackTrace*/, null /*data*/);
            }
            app.killLocked(killReason, reasonCode, subReason, true);
        }
    }

    /**
     * Induce a crash in the given app.
     *
     * @param uid if nonnegative, the required matching uid of the target to crash
     * @param initialPid fast-path match for the target to crash
     * @param packageName fallback match if the stated pid is not found or doesn't match uid
     * @param userId If nonnegative, required to identify a match by package name
     * @param message
     */
    void scheduleAppCrashLocked(int uid, int initialPid, String packageName, int userId,
            String message, boolean force, int exceptionTypeId, @Nullable Bundle extras) {
        ProcessRecord proc = null;

        // Figure out which process to kill.  We don't trust that initialPid
        // still has any relation to current pids, so must scan through the
        // list.

        synchronized (mService.mPidsSelfLocked) {
            for (int i=0; i<mService.mPidsSelfLocked.size(); i++) {
                ProcessRecord p = mService.mPidsSelfLocked.valueAt(i);
                if (uid >= 0 && p.uid != uid) {
                    continue;
                }
                if (p.getPid() == initialPid) {
                    proc = p;
                    break;
                }
                if (p.getPkgList().containsKey(packageName)
                        && (userId < 0 || p.userId == userId)) {
                    proc = p;
                }
            }
        }

        if (proc == null) {
            Slog.w(TAG, "crashApplication: nothing for uid=" + uid
                    + " initialPid=" + initialPid
                    + " packageName=" + packageName
                    + " userId=" + userId);
            return;
        }

        if (exceptionTypeId == CrashedByAdbException.TYPE_ID) {
            String[] packages = proc.getPackageList();
            for (int i = 0; i < packages.length; i++) {
                if (mService.mPackageManagerInt.isPackageStateProtected(packages[i], proc.userId)) {
                    Slog.w(TAG, "crashApplication: Can not crash protected package " + packages[i]);
                    return;
                }
            }
        }

        proc.scheduleCrashLocked(message, exceptionTypeId, extras);
        if (force) {
            // If the app is responsive, the scheduled crash will happen as expected
            // and then the delayed summary kill will be a no-op.
            final ProcessRecord p = proc;
            mService.mHandler.postDelayed(
                    () -> {
                        synchronized (mService) {
                            synchronized (mProcLock) {
                                killAppImmediateLSP(p, ApplicationExitInfo.REASON_OTHER,
                                        ApplicationExitInfo.SUBREASON_INVALID_STATE,
                                        "forced", "killed for invalid state");
                            }
                        }
                    },
                    5000L);
        }
    }

    /**
     * Bring up the "unexpected error" dialog box for a crashing app.
     * Deal with edge cases (intercepts from instrumented applications,
     * ActivityController, error intent receivers, that sort of thing).
     * @param r the application crashing
     * @param crashInfo describing the failure
     */
    void crashApplication(ProcessRecord r, ApplicationErrorReport.CrashInfo crashInfo) {
        final int callingPid = Binder.getCallingPid();
        final int callingUid = Binder.getCallingUid();

        final long origId = Binder.clearCallingIdentity();
        try {
            crashApplicationInner(r, crashInfo, callingPid, callingUid);
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    private void crashApplicationInner(ProcessRecord r, ApplicationErrorReport.CrashInfo crashInfo,
            int callingPid, int callingUid) {
        long timeMillis = System.currentTimeMillis();
        String shortMsg = crashInfo.exceptionClassName;
        String longMsg = crashInfo.exceptionMessage;
        String stackTrace = crashInfo.stackTrace;
        if (shortMsg != null && longMsg != null) {
            longMsg = shortMsg + ": " + longMsg;
        } else if (shortMsg != null) {
            longMsg = shortMsg;
        }

        if (r != null) {
            mPackageWatchdog.onPackageFailure(r.getPackageListWithVersionCode(),
                    PackageWatchdog.FAILURE_REASON_APP_CRASH);

            mService.mProcessList.noteAppKill(r, (crashInfo != null
                      && "Native crash".equals(crashInfo.exceptionClassName))
                      ? ApplicationExitInfo.REASON_CRASH_NATIVE
                      : ApplicationExitInfo.REASON_CRASH,
                      ApplicationExitInfo.SUBREASON_UNKNOWN,
                    "crash");
        }

        final int relaunchReason = r != null
                ? r.getWindowProcessController().computeRelaunchReason() : RELAUNCH_REASON_NONE;

        AppErrorResult result = new AppErrorResult();
        int taskId;
        synchronized (mService) {
            /**
             * If crash is handled by instance of {@link android.app.IActivityController},
             * finish now and don't show the app error dialog.
             */
            if (handleAppCrashInActivityController(r, crashInfo, shortMsg, longMsg, stackTrace,
                    timeMillis, callingPid, callingUid)) {
                return;
            }

            // Suppress crash dialog if the process is being relaunched due to a crash during a free
            // resize.
            if (relaunchReason == RELAUNCH_REASON_FREE_RESIZE) {
                return;
            }

            /**
             * If this process was running instrumentation, finish now - it will be handled in
             * {@link ActivityManagerService#handleAppDiedLocked}.
             */
            if (r != null && r.getActiveInstrumentation() != null) {
                return;
            }

            // Log crash in battery stats.
            if (r != null) {
                mService.mBatteryStatsService.noteProcessCrash(r.processName, r.uid);
            }

            AppErrorDialog.Data data = new AppErrorDialog.Data();
            data.result = result;
            data.proc = r;

            // If we can't identify the process or it's already exceeded its crash quota,
            // quit right away without showing a crash dialog.
            if (r == null || !makeAppCrashingLocked(r, shortMsg, longMsg, stackTrace, data)) {
                return;
            }

            final Message msg = Message.obtain();
            msg.what = ActivityManagerService.SHOW_ERROR_UI_MSG;

            taskId = data.taskId;
            msg.obj = data;
            mService.mUiHandler.sendMessage(msg);
        }

        int res = result.get();

        Intent appErrorIntent = null;
        MetricsLogger.action(mContext, MetricsProto.MetricsEvent.ACTION_APP_CRASH, res);
        if (res == AppErrorDialog.TIMEOUT || res == AppErrorDialog.CANCEL) {
            res = AppErrorDialog.FORCE_QUIT;
        }
        switch (res) {
            case AppErrorDialog.MUTE:
                synchronized (mBadProcessLock) {
                    stopReportingCrashesLBp(r);
                }
                break;
            case AppErrorDialog.RESTART:
                synchronized (mService) {
                    mService.mProcessList.removeProcessLocked(r, false, true,
                            ApplicationExitInfo.REASON_CRASH, "crash");
                }
                if (taskId != INVALID_TASK_ID) {
                    try {
                        mService.startActivityFromRecents(taskId,
                                ActivityOptions.makeBasic().toBundle());
                    } catch (IllegalArgumentException e) {
                        // Hmm...that didn't work. Task should either be in recents or associated
                        // with a stack.
                        Slog.e(TAG, "Could not restart taskId=" + taskId, e);
                    }
                }
                break;
            case AppErrorDialog.FORCE_QUIT:
                final long orig = Binder.clearCallingIdentity();
                try {
                    // Kill it with fire!
                    mService.mAtmInternal.onHandleAppCrash(r.getWindowProcessController());
                    if (!r.isPersistent()) {
                        synchronized (mService) {
                            mService.mProcessList.removeProcessLocked(r, false, false,
                                    ApplicationExitInfo.REASON_CRASH, "crash");
                        }
                        mService.mAtmInternal.resumeTopActivities(false /* scheduleIdle */);
                    }
                } finally {
                    Binder.restoreCallingIdentity(orig);
                }
                break;
            case AppErrorDialog.APP_INFO:
                appErrorIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                appErrorIntent.setData(Uri.parse("package:" + r.info.packageName));
                appErrorIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                break;
            case AppErrorDialog.FORCE_QUIT_AND_REPORT:
                synchronized (mProcLock) {
                    appErrorIntent = createAppErrorIntentLOSP(r, timeMillis, crashInfo);
                }
                break;
        }

        if (appErrorIntent != null) {
            try {
                mContext.startActivityAsUser(appErrorIntent, new UserHandle(r.userId));
            } catch (ActivityNotFoundException e) {
                Slog.w(TAG, "bug report receiver dissappeared", e);
            }
        }
    }

    @GuardedBy("mService")
    private boolean handleAppCrashInActivityController(ProcessRecord r,
                                                       ApplicationErrorReport.CrashInfo crashInfo,
                                                       String shortMsg, String longMsg,
                                                       String stackTrace, long timeMillis,
                                                       int callingPid, int callingUid) {
        String name = r != null ? r.processName : null;
        int pid = r != null ? r.getPid() : callingPid;
        int uid = r != null ? r.info.uid : callingUid;

        return mService.mAtmInternal.handleAppCrashInActivityController(
                name, pid, shortMsg, longMsg, timeMillis, crashInfo.stackTrace, () -> {
                if (Build.IS_DEBUGGABLE
                        && "Native crash".equals(crashInfo.exceptionClassName)) {
                    Slog.w(TAG, "Skip killing native crashed app " + name
                            + "(" + pid + ") during testing");
                } else {
                    Slog.w(TAG, "Force-killing crashed app " + name + " at watcher's request");
                    if (r != null) {
                        if (!makeAppCrashingLocked(r, shortMsg, longMsg, stackTrace, null)) {
                            r.killLocked("crash", ApplicationExitInfo.REASON_CRASH, true);
                        }
                    } else {
                        // Huh.
                        Process.killProcess(pid);
                        ProcessList.killProcessGroup(uid, pid);
                        mService.mProcessList.noteAppKill(pid, uid,
                                ApplicationExitInfo.REASON_CRASH,
                                ApplicationExitInfo.SUBREASON_UNKNOWN,
                                "crash");
                    }
                }
        });
    }

    @GuardedBy("mService")
    private boolean makeAppCrashingLocked(ProcessRecord app,
            String shortMsg, String longMsg, String stackTrace, AppErrorDialog.Data data) {
        synchronized (mProcLock) {
            final ProcessErrorStateRecord errState = app.mErrorState;
            errState.setCrashing(true);
            errState.setCrashingReport(generateProcessError(app,
                    ActivityManager.ProcessErrorStateInfo.CRASHED,
                    null, shortMsg, longMsg, stackTrace));
            errState.startAppProblemLSP();
            app.getWindowProcessController().stopFreezingActivities();
            synchronized (mBadProcessLock) {
                return handleAppCrashLSPB(app, "force-crash" /*reason*/, shortMsg, longMsg,
                        stackTrace, data);
            }
        }
    }

    /**
     * Generate a process error record, suitable for attachment to a ProcessRecord.
     *
     * @param app The ProcessRecord in which the error occurred.
     * @param condition Crashing, Application Not Responding, etc.  Values are defined in
     *                      ActivityManager.ProcessErrorStateInfo
     * @param activity The activity associated with the crash, if known.
     * @param shortMsg Short message describing the crash.
     * @param longMsg Long message describing the crash.
     * @param stackTrace Full crash stack trace, may be null.
     *
     * @return Returns a fully-formed ProcessErrorStateInfo record.
     */
    ActivityManager.ProcessErrorStateInfo generateProcessError(ProcessRecord app,
            int condition, String activity, String shortMsg, String longMsg, String stackTrace) {
        ActivityManager.ProcessErrorStateInfo report = new ActivityManager.ProcessErrorStateInfo();

        report.condition = condition;
        report.processName = app.processName;
        report.pid = app.getPid();
        report.uid = app.info.uid;
        report.tag = activity;
        report.shortMsg = shortMsg;
        report.longMsg = longMsg;
        report.stackTrace = stackTrace;

        return report;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    Intent createAppErrorIntentLOSP(ProcessRecord r,
            long timeMillis, ApplicationErrorReport.CrashInfo crashInfo) {
        ApplicationErrorReport report = createAppErrorReportLOSP(r, timeMillis, crashInfo);
        if (report == null) {
            return null;
        }
        Intent result = new Intent(Intent.ACTION_APP_ERROR);
        result.setComponent(r.mErrorState.getErrorReportReceiver());
        result.putExtra(Intent.EXTRA_BUG_REPORT, report);
        result.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return result;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    private ApplicationErrorReport createAppErrorReportLOSP(ProcessRecord r,
            long timeMillis, ApplicationErrorReport.CrashInfo crashInfo) {
        final ProcessErrorStateRecord errState = r.mErrorState;
        if (errState.getErrorReportReceiver() == null) {
            return null;
        }

        if (!errState.isCrashing() && !errState.isNotResponding()
                && !errState.isForceCrashReport()) {
            return null;
        }

        ApplicationErrorReport report = new ApplicationErrorReport();
        report.packageName = r.info.packageName;
        report.installerPackageName = errState.getErrorReportReceiver().getPackageName();
        report.processName = r.processName;
        report.time = timeMillis;
        report.systemApp = (r.info.flags & FLAG_SYSTEM) != 0;

        if (errState.isCrashing() || errState.isForceCrashReport()) {
            report.type = ApplicationErrorReport.TYPE_CRASH;
            report.crashInfo = crashInfo;
        } else if (errState.isNotResponding()) {
            report.type = ApplicationErrorReport.TYPE_ANR;
            report.anrInfo = new ApplicationErrorReport.AnrInfo();

            report.anrInfo.activity = errState.getNotRespondingReport().tag;
            report.anrInfo.cause = errState.getNotRespondingReport().shortMsg;
            report.anrInfo.info = errState.getNotRespondingReport().longMsg;
        }

        return report;
    }

    @GuardedBy({"mService", "mProcLock", "mBadProcessLock"})
    private boolean handleAppCrashLSPB(ProcessRecord app, String reason,
            String shortMsg, String longMsg, String stackTrace, AppErrorDialog.Data data) {
        final long now = SystemClock.uptimeMillis();
        final boolean showBackground = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.ANR_SHOW_BACKGROUND, 0,
                mService.mUserController.getCurrentUserId()) != 0;

        Long crashTime;
        Long crashTimePersistent;
        final String processName = app.processName;
        final int uid = app.uid;
        final int userId = app.userId;
        final boolean isolated = app.isolated;
        final boolean persistent = app.isPersistent();
        final WindowProcessController proc = app.getWindowProcessController();
        final ProcessErrorStateRecord errState = app.mErrorState;

        if (!app.isolated) {
            crashTime = mProcessCrashTimes.get(processName, uid);
            crashTimePersistent = mProcessCrashTimesPersistent.get(processName, uid);
        } else {
            crashTime = crashTimePersistent = null;
        }

        // Bump up the crash count of any services currently running in the proc.
        boolean tryAgain = app.mServices.incServiceCrashCountLocked(now);

        final boolean quickCrash = crashTime != null
                && now < crashTime + ActivityManagerConstants.MIN_CRASH_INTERVAL;
        if (quickCrash || isProcOverCrashLimitLBp(app, now)) {
            // The process either crashed again very quickly or has been crashing periodically in
            // the last few hours. If it was a bound foreground service, let's try to restart again
            // in a while, otherwise the process loses!
            Slog.w(TAG, "Process " + processName + " has crashed too many times, killing!"
                    + " Reason: " + (quickCrash ? "crashed quickly" : "over process crash limit"));
            EventLog.writeEvent(EventLogTags.AM_PROCESS_CRASHED_TOO_MUCH,
                    userId, processName, uid);
            mService.mAtmInternal.onHandleAppCrash(proc);
            if (!persistent) {
                // We don't want to start this process again until the user
                // explicitly does so...  but for persistent process, we really
                // need to keep it running.  If a persistent process is actually
                // repeatedly crashing, then badness for everyone.
                EventLog.writeEvent(EventLogTags.AM_PROC_BAD, userId, uid,
                        processName);
                if (!isolated) {
                    // XXX We don't have a way to mark isolated processes
                    // as bad, since they don't have a persistent identity.
                    markBadProcess(processName, app.uid,
                            new BadProcessInfo(now, shortMsg, longMsg, stackTrace));
                    mProcessCrashTimes.remove(processName, app.uid);
                    mProcessCrashCounts.remove(processName, app.uid);
                }
                errState.setBad(true);
                app.setRemoved(true);
                final AppStandbyInternal appStandbyInternal =
                        LocalServices.getService(AppStandbyInternal.class);
                if (appStandbyInternal != null) {
                    appStandbyInternal.restrictApp(
                            // Sometimes the processName is the same as the package name, so use
                            // that if we don't have the ApplicationInfo object.
                            // AppStandbyController will just return if it can't find the app.
                            app.info != null ? app.info.packageName : processName,
                            userId, UsageStatsManager.REASON_SUB_FORCED_SYSTEM_FLAG_BUGGY);
                }
                // Don't let services in this process be restarted and potentially
                // annoy the user repeatedly.  Unless it is persistent, since those
                // processes run critical code.
                mService.mProcessList.removeProcessLocked(app, false, tryAgain,
                        ApplicationExitInfo.REASON_CRASH, "crash");
                mService.mAtmInternal.resumeTopActivities(false /* scheduleIdle */);
                if (!showBackground) {
                    return false;
                }
            }
            mService.mAtmInternal.resumeTopActivities(false /* scheduleIdle */);
        } else {
            final int affectedTaskId = mService.mAtmInternal.finishTopCrashedActivities(
                            proc, reason);
            if (data != null) {
                data.taskId = affectedTaskId;
            }
            if (data != null && crashTimePersistent != null
                    && now < crashTimePersistent + ActivityManagerConstants.MIN_CRASH_INTERVAL) {
                data.repeating = true;
            }
        }

        if (data != null && tryAgain) {
            data.isRestartableForService = true;
        }

        // If the crashing process is what we consider to be the "home process" and it has been
        // replaced by a third-party app, clear the package preferred activities from packages
        // with a home activity running in the process to prevent a repeatedly crashing app
        // from blocking the user to manually clear the list.
        if (proc.isHomeProcess() && proc.hasActivities() && (app.info.flags & FLAG_SYSTEM) == 0) {
            proc.clearPackagePreferredForHomeActivities();
        }

        if (!isolated) {
            // XXX Can't keep track of crash times for isolated processes,
            // because they don't have a persistent identity.
            mProcessCrashTimes.put(processName, uid, now);
            mProcessCrashTimesPersistent.put(processName, uid, now);
            updateProcessCrashCountLBp(processName, uid, now);
        }

        if (errState.getCrashHandler() != null) {
            mService.mHandler.post(errState.getCrashHandler());
        }
        return true;
    }

    @GuardedBy("mBadProcessLock")
    private void updateProcessCrashCountLBp(String processName, int uid, long now) {
        Pair<Long, Integer> count = mProcessCrashCounts.get(processName, uid);
        if (count == null || (count.first + PROCESS_CRASH_COUNT_RESET_INTERVAL) < now) {
            count = new Pair<>(now, 1);
        } else {
            count = new Pair<>(count.first, count.second + 1);
        }
        mProcessCrashCounts.put(processName, uid, count);
    }

    @GuardedBy("mBadProcessLock")
    private boolean isProcOverCrashLimitLBp(ProcessRecord app, long now) {
        final Pair<Long, Integer> crashCount = mProcessCrashCounts.get(app.processName, app.uid);
        return !app.isolated && crashCount != null
                && now < (crashCount.first + PROCESS_CRASH_COUNT_RESET_INTERVAL)
                && crashCount.second >= PROCESS_CRASH_COUNT_LIMIT;
    }

    void handleShowAppErrorUi(Message msg) {
        AppErrorDialog.Data data = (AppErrorDialog.Data) msg.obj;
        boolean showBackground = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.ANR_SHOW_BACKGROUND, 0,
                mService.mUserController.getCurrentUserId()) != 0;

        final int userId;
        synchronized (mProcLock) {
            final ProcessRecord proc = data.proc;
            final AppErrorResult res = data.result;
            if (proc == null) {
                Slog.e(TAG, "handleShowAppErrorUi: proc is null");
                return;
            }
            final ProcessErrorStateRecord errState = proc.mErrorState;
            userId = proc.userId;
            if (errState.getDialogController().hasCrashDialogs()) {
                Slog.e(TAG, "App already has crash dialog: " + proc);
                if (res != null) {
                    res.set(AppErrorDialog.ALREADY_SHOWING);
                }
                return;
            }
            boolean isBackground = (UserHandle.getAppId(proc.uid)
                    >= Process.FIRST_APPLICATION_UID
                    && proc.getPid() != MY_PID);
            for (int profileId : mService.mUserController.getCurrentProfileIds()) {
                isBackground &= (userId != profileId);
            }
            if (isBackground && !showBackground) {
                Slog.w(TAG, "Skipping crash dialog of " + proc + ": background");
                if (res != null) {
                    res.set(AppErrorDialog.BACKGROUND_USER);
                }
                return;
            }
            Long crashShowErrorTime = null;
            synchronized (mBadProcessLock) {
                if (!proc.isolated) {
                    crashShowErrorTime = mProcessCrashShowDialogTimes.get(proc.processName,
                            proc.uid);
                }
                final boolean showFirstCrash = Settings.Global.getInt(
                        mContext.getContentResolver(),
                        Settings.Global.SHOW_FIRST_CRASH_DIALOG, 0) != 0;
                final boolean showFirstCrashDevOption = Settings.Secure.getIntForUser(
                        mContext.getContentResolver(),
                        Settings.Secure.SHOW_FIRST_CRASH_DIALOG_DEV_OPTION,
                        0,
                        mService.mUserController.getCurrentUserId()) != 0;
                final String packageName = proc.info.packageName;
                final boolean crashSilenced = mAppsNotReportingCrashes != null
                        && mAppsNotReportingCrashes.contains(proc.info.packageName);
                final long now = SystemClock.uptimeMillis();
                final boolean shouldThottle = crashShowErrorTime != null
                        && now < crashShowErrorTime + ActivityManagerConstants.MIN_CRASH_INTERVAL;
                if ((mService.mAtmInternal.canShowErrorDialogs() || showBackground)
                        && !crashSilenced && !shouldThottle
                        && (showFirstCrash || showFirstCrashDevOption || data.repeating)) {
                    Slog.i(TAG, "Showing crash dialog for package " + packageName + " u" + userId);
                    errState.getDialogController().showCrashDialogs(data);
                    if (!proc.isolated) {
                        mProcessCrashShowDialogTimes.put(proc.processName, proc.uid, now);
                    }
                } else {
                    // The device is asleep, so just pretend that the user
                    // saw a crash dialog and hit "force quit".
                    if (res != null) {
                        res.set(AppErrorDialog.CANT_SHOW);
                    }
                }
            }
        }
    }

    @GuardedBy("mBadProcessLock")
    private void stopReportingCrashesLBp(ProcessRecord proc) {
        if (mAppsNotReportingCrashes == null) {
            mAppsNotReportingCrashes = new ArraySet<>();
        }
        mAppsNotReportingCrashes.add(proc.info.packageName);
    }

    void handleShowAnrUi(Message msg) {
        List<VersionedPackage> packageList = null;
        boolean doKill = false;
        AppNotRespondingDialog.Data data = (AppNotRespondingDialog.Data) msg.obj;
        final ProcessRecord proc = data.proc;
        if (proc == null) {
            Slog.e(TAG, "handleShowAnrUi: proc is null");
            return;
        }
        synchronized (mProcLock) {
            final ProcessErrorStateRecord errState = proc.mErrorState;
            errState.setAnrData(data);
            if (!proc.isPersistent()) {
                packageList = proc.getPackageListWithVersionCode();
            }
            if (errState.getDialogController().hasAnrDialogs()) {
                Slog.e(TAG, "App already has anr dialog: " + proc);
                MetricsLogger.action(mContext, MetricsProto.MetricsEvent.ACTION_APP_ANR,
                        AppNotRespondingDialog.ALREADY_SHOWING);
                return;
            }

            boolean showBackground = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.ANR_SHOW_BACKGROUND, 0,
                    mService.mUserController.getCurrentUserId()) != 0;
            if (mService.mAtmInternal.canShowErrorDialogs() || showBackground) {
                AnrController anrController = errState.getDialogController().getAnrController();
                if (anrController == null) {
                    errState.getDialogController().showAnrDialogs(data);
                } else {
                    String packageName = proc.info.packageName;
                    int uid = proc.info.uid;
                    boolean showDialog = anrController.onAnrDelayCompleted(packageName, uid);

                    if (showDialog) {
                        Slog.d(TAG, "ANR delay completed. Showing ANR dialog for package: "
                                + packageName);
                        errState.getDialogController().showAnrDialogs(data);
                    } else {
                        Slog.d(TAG, "ANR delay completed. Cancelling ANR dialog for package: "
                                + packageName);
                        errState.setNotResponding(false);
                        errState.setNotRespondingReport(null);
                        errState.getDialogController().clearAnrDialogs();
                    }
                }
            } else {
                MetricsLogger.action(mContext, MetricsProto.MetricsEvent.ACTION_APP_ANR,
                        AppNotRespondingDialog.CANT_SHOW);
                // Just kill the app if there is no dialog to be shown.
                doKill = true;
            }
        }
        if (doKill) {
            mService.killAppAtUsersRequest(proc);
        }
        // Notify PackageWatchdog without the lock held
        if (packageList != null) {
            mPackageWatchdog.onPackageFailure(packageList,
                    PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING);
        }
    }

    void handleDismissAnrDialogs(ProcessRecord proc) {
        synchronized (mProcLock) {
            final ProcessErrorStateRecord errState = proc.mErrorState;

            // Cancel any rescheduled ANR dialogs
            mService.mUiHandler.removeMessages(
                    ActivityManagerService.SHOW_NOT_RESPONDING_UI_MSG, errState.getAnrData());

            // Dismiss any ANR dialogs currently visible
            if (errState.getDialogController().hasAnrDialogs()) {
                errState.setNotResponding(false);
                errState.setNotRespondingReport(null);
                errState.getDialogController().clearAnrDialogs();
            }
            proc.mErrorState.setAnrData(null);
        }
    }

    /**
     * Information about a process that is currently marked as bad.
     */
    static final class BadProcessInfo {
        BadProcessInfo(long time, String shortMsg, String longMsg, String stack) {
            this.time = time;
            this.shortMsg = shortMsg;
            this.longMsg = longMsg;
            this.stack = stack;
        }

        final long time;
        final String shortMsg;
        final String longMsg;
        final String stack;
    }

}
