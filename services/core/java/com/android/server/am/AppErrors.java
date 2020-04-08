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

import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.ActivityManagerService.MY_PID;
import static com.android.server.am.ActivityManagerService.SYSTEM_DEBUGGABLE;
import static com.android.server.wm.ActivityTaskManagerService.RELAUNCH_REASON_FREE_RESIZE;
import static com.android.server.wm.ActivityTaskManagerService.RELAUNCH_REASON_NONE;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ApplicationErrorReport;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ModuleInfo;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.net.Uri;
import android.os.Binder;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import com.android.internal.app.ProcessMap;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.server.PackageWatchdog;
import com.android.server.RescueParty;
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
    private final Context mContext;
    private final PackageWatchdog mPackageWatchdog;

    private ArraySet<String> mAppsNotReportingCrashes;

    /**
     * The last time that various processes have crashed since they were last explicitly started.
     */
    private final ProcessMap<Long> mProcessCrashTimes = new ProcessMap<>();

    /**
     * The last time that various processes have crashed (not reset even when explicitly started).
     */
    private final ProcessMap<Long> mProcessCrashTimesPersistent = new ProcessMap<>();

    /**
     * Set of applications that we consider to be bad, and will reject
     * incoming broadcasts from (which the user has no control over).
     * Processes are added to this set when they have crashed twice within
     * a minimum amount of time; they are removed from it when they are
     * later restarted (hopefully due to some user action).  The value is the
     * time it was added to the list.
     */
    private final ProcessMap<BadProcessInfo> mBadProcesses = new ProcessMap<>();


    AppErrors(Context context, ActivityManagerService service, PackageWatchdog watchdog) {
        context.assertRuntimeOverlayThemable();
        mService = service;
        mContext = context;
        mPackageWatchdog = watchdog;
    }

    void writeToProto(ProtoOutputStream proto, long fieldId, String dumpPackage) {
        if (mProcessCrashTimes.getMap().isEmpty() && mBadProcesses.getMap().isEmpty()) {
            return;
        }

        final long token = proto.start(fieldId);
        final long now = SystemClock.uptimeMillis();
        proto.write(AppErrorsProto.NOW_UPTIME_MS, now);

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
                    final ProcessRecord r = mService.getProcessNames().get(pname, puid);
                    if (dumpPackage != null && (r == null || !r.pkgList.containsKey(dumpPackage))) {
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

        if (!mBadProcesses.getMap().isEmpty()) {
            final ArrayMap<String, SparseArray<BadProcessInfo>> pmap = mBadProcesses.getMap();
            final int processCount = pmap.size();
            for (int ip = 0; ip < processCount; ip++) {
                final long btoken = proto.start(AppErrorsProto.BAD_PROCESSES);
                final String pname = pmap.keyAt(ip);
                final SparseArray<BadProcessInfo> uids = pmap.valueAt(ip);
                final int uidCount = uids.size();

                proto.write(AppErrorsProto.BadProcess.PROCESS_NAME, pname);
                for (int i = 0; i < uidCount; i++) {
                    final int puid = uids.keyAt(i);
                    final ProcessRecord r = mService.getProcessNames().get(pname, puid);
                    if (dumpPackage != null && (r == null
                            || !r.pkgList.containsKey(dumpPackage))) {
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

        proto.end(token);
    }

    boolean dumpLocked(FileDescriptor fd, PrintWriter pw, boolean needSep, String dumpPackage) {
        if (!mProcessCrashTimes.getMap().isEmpty()) {
            boolean printed = false;
            final long now = SystemClock.uptimeMillis();
            final ArrayMap<String, SparseArray<Long>> pmap = mProcessCrashTimes.getMap();
            final int processCount = pmap.size();
            for (int ip = 0; ip < processCount; ip++) {
                final String pname = pmap.keyAt(ip);
                final SparseArray<Long> uids = pmap.valueAt(ip);
                final int uidCount = uids.size();
                for (int i = 0; i < uidCount; i++) {
                    final int puid = uids.keyAt(i);
                    final ProcessRecord r = mService.getProcessNames().get(pname, puid);
                    if (dumpPackage != null && (r == null
                            || !r.pkgList.containsKey(dumpPackage))) {
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
                    TimeUtils.formatDuration(now-uids.valueAt(i), pw);
                    pw.println(" ago");
                }
            }
        }

        if (!mBadProcesses.getMap().isEmpty()) {
            boolean printed = false;
            final ArrayMap<String, SparseArray<BadProcessInfo>> pmap = mBadProcesses.getMap();
            final int processCount = pmap.size();
            for (int ip = 0; ip < processCount; ip++) {
                final String pname = pmap.keyAt(ip);
                final SparseArray<BadProcessInfo> uids = pmap.valueAt(ip);
                final int uidCount = uids.size();
                for (int i = 0; i < uidCount; i++) {
                    final int puid = uids.keyAt(i);
                    final ProcessRecord r = mService.getProcessNames().get(pname, puid);
                    if (dumpPackage != null && (r == null
                            || !r.pkgList.containsKey(dumpPackage))) {
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
                                pw.write(info.stack, lastPos, pos-lastPos);
                                pw.println();
                                lastPos = pos+1;
                            }
                        }
                        if (lastPos < info.stack.length()) {
                            pw.print("        ");
                            pw.write(info.stack, lastPos, info.stack.length()-lastPos);
                            pw.println();
                        }
                    }
                }
            }
        }
        return needSep;
    }

    boolean isBadProcessLocked(ApplicationInfo info) {
        return mBadProcesses.get(info.processName, info.uid) != null;
    }

    void clearBadProcessLocked(ApplicationInfo info) {
        mBadProcesses.remove(info.processName, info.uid);
    }

    void resetProcessCrashTimeLocked(ApplicationInfo info) {
        mProcessCrashTimes.remove(info.processName, info.uid);
    }

    void resetProcessCrashTimeLocked(boolean resetEntireUser, int appId, int userId) {
        final ArrayMap<String, SparseArray<Long>> pmap = mProcessCrashTimes.getMap();
        for (int ip = pmap.size() - 1; ip >= 0; ip--) {
            SparseArray<Long> ba = pmap.valueAt(ip);
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
            if (ba.size() == 0) {
                pmap.removeAt(ip);
            }
        }
    }

    void loadAppsNotReportingCrashesFromConfigLocked(String appsNotReportingCrashesConfig) {
        if (appsNotReportingCrashesConfig != null) {
            final String[] split = appsNotReportingCrashesConfig.split(",");
            if (split.length > 0) {
                mAppsNotReportingCrashes = new ArraySet<>();
                Collections.addAll(mAppsNotReportingCrashes, split);
            }
        }
    }

    void killAppAtUserRequestLocked(ProcessRecord app, Dialog fromDialog) {
        app.setCrashing(false);
        app.crashingReport = null;
        app.setNotResponding(false);
        app.notRespondingReport = null;
        if (app.anrDialog == fromDialog) {
            app.anrDialog = null;
        }
        if (app.waitDialog == fromDialog) {
            app.waitDialog = null;
        }
        if (app.pid > 0 && app.pid != MY_PID) {
            handleAppCrashLocked(app, "user-terminated" /*reason*/,
                    null /*shortMsg*/, null /*longMsg*/, null /*stackTrace*/, null /*data*/);
            app.kill("user request after error", true);
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
            String message) {
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
                if (p.pid == initialPid) {
                    proc = p;
                    break;
                }
                if (p.pkgList.containsKey(packageName)
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

        proc.scheduleCrash(message);
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

    void crashApplicationInner(ProcessRecord r, ApplicationErrorReport.CrashInfo crashInfo,
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
            boolean isApexModule = false;
            try {
                for (String androidPackage : r.getPackageList()) {
                    ModuleInfo moduleInfo = mContext.getPackageManager().getModuleInfo(
                            androidPackage, /*flags=*/ 0);
                    if (moduleInfo != null) {
                        isApexModule = true;
                        break;
                    }
                }
            } catch (IllegalStateException | PackageManager.NameNotFoundException e) {
                // Call to PackageManager#getModuleInfo() can result in NameNotFoundException or
                // IllegalStateException. In case they are thrown, there isn't much we can do
                // other than proceed with app crash handling.
            }

            if (r.isPersistent() || isApexModule) {
                // If a persistent app or apex module is stuck in a crash loop, the device isn't
                // very usable, so we want to consider sending out a rescue party.
                RescueParty.noteAppCrash(mContext, r.uid);
            }

            mPackageWatchdog.onPackageFailure(r.getPackageListWithVersionCode(),
                    PackageWatchdog.FAILURE_REASON_APP_CRASH);
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

            // Add paste content for dogbin option
            data.paste = "time: " + timeMillis + "\n" +
            "msg: " + longMsg + "\n" +
            "stacktrace: " + stackTrace;
          
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
        synchronized (mService) {
            if (res == AppErrorDialog.MUTE) {
                stopReportingCrashesLocked(r);
            }
            if (res == AppErrorDialog.RESTART) {
                mService.mProcessList.removeProcessLocked(r, false, true, "crash");
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
            }
            if (res == AppErrorDialog.FORCE_QUIT) {
                long orig = Binder.clearCallingIdentity();
                try {
                    // Kill it with fire!
                    mService.mAtmInternal.onHandleAppCrash(r.getWindowProcessController());
                    if (!r.isPersistent()) {
                        mService.mProcessList.removeProcessLocked(r, false, false, "crash");
                        mService.mAtmInternal.resumeTopActivities(false /* scheduleIdle */);
                    }
                } finally {
                    Binder.restoreCallingIdentity(orig);
                }
            }
            if (res == AppErrorDialog.APP_INFO) {
                appErrorIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                appErrorIntent.setData(Uri.parse("package:" + r.info.packageName));
                appErrorIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            if (res == AppErrorDialog.FORCE_QUIT_AND_REPORT) {
                appErrorIntent = createAppErrorIntentLocked(r, timeMillis, crashInfo);
            }
            if (r != null && !r.isolated && res != AppErrorDialog.RESTART) {
                // XXX Can't keep track of crash time for isolated processes,
                // since they don't have a persistent identity.
                mProcessCrashTimes.put(r.info.processName, r.uid,
                        SystemClock.uptimeMillis());
            }
        }

        if (appErrorIntent != null) {
            try {
                mContext.startActivityAsUser(appErrorIntent, new UserHandle(r.userId));
            } catch (ActivityNotFoundException e) {
                Slog.w(TAG, "bug report receiver dissappeared", e);
            }
        }
    }

    private boolean handleAppCrashInActivityController(ProcessRecord r,
                                                       ApplicationErrorReport.CrashInfo crashInfo,
                                                       String shortMsg, String longMsg,
                                                       String stackTrace, long timeMillis,
                                                       int callingPid, int callingUid) {
        String name = r != null ? r.processName : null;
        int pid = r != null ? r.pid : callingPid;
        int uid = r != null ? r.info.uid : callingUid;

        return mService.mAtmInternal.handleAppCrashInActivityController(
                name, pid, shortMsg, longMsg, timeMillis, crashInfo.stackTrace, () -> {
            if ("1".equals(SystemProperties.get(SYSTEM_DEBUGGABLE, "0"))
                    && "Native crash".equals(crashInfo.exceptionClassName)) {
                Slog.w(TAG, "Skip killing native crashed app " + name
                        + "(" + pid + ") during testing");
            } else {
                Slog.w(TAG, "Force-killing crashed app " + name + " at watcher's request");
                if (r != null) {
                    if (!makeAppCrashingLocked(r, shortMsg, longMsg, stackTrace, null)) {
                        r.kill("crash", true);
                    }
                } else {
                    // Huh.
                    Process.killProcess(pid);
                    ProcessList.killProcessGroup(uid, pid);
                }
            }
        });
    }

    private boolean makeAppCrashingLocked(ProcessRecord app,
            String shortMsg, String longMsg, String stackTrace, AppErrorDialog.Data data) {
        app.setCrashing(true);
        app.crashingReport = generateProcessError(app,
                ActivityManager.ProcessErrorStateInfo.CRASHED, null, shortMsg, longMsg, stackTrace);
        app.startAppProblemLocked();
        app.getWindowProcessController().stopFreezingActivities();
        return handleAppCrashLocked(app, "force-crash" /*reason*/, shortMsg, longMsg, stackTrace,
                data);
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
        report.pid = app.pid;
        report.uid = app.info.uid;
        report.tag = activity;
        report.shortMsg = shortMsg;
        report.longMsg = longMsg;
        report.stackTrace = stackTrace;

        return report;
    }

    Intent createAppErrorIntentLocked(ProcessRecord r,
            long timeMillis, ApplicationErrorReport.CrashInfo crashInfo) {
        ApplicationErrorReport report = createAppErrorReportLocked(r, timeMillis, crashInfo);
        if (report == null) {
            return null;
        }
        Intent result = new Intent(Intent.ACTION_APP_ERROR);
        result.setComponent(r.errorReportReceiver);
        result.putExtra(Intent.EXTRA_BUG_REPORT, report);
        result.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return result;
    }

    private ApplicationErrorReport createAppErrorReportLocked(ProcessRecord r,
            long timeMillis, ApplicationErrorReport.CrashInfo crashInfo) {
        if (r.errorReportReceiver == null) {
            return null;
        }

        if (!r.isCrashing() && !r.isNotResponding() && !r.forceCrashReport) {
            return null;
        }

        ApplicationErrorReport report = new ApplicationErrorReport();
        report.packageName = r.info.packageName;
        report.installerPackageName = r.errorReportReceiver.getPackageName();
        report.processName = r.processName;
        report.time = timeMillis;
        report.systemApp = (r.info.flags & FLAG_SYSTEM) != 0;

        if (r.isCrashing() || r.forceCrashReport) {
            report.type = ApplicationErrorReport.TYPE_CRASH;
            report.crashInfo = crashInfo;
        } else if (r.isNotResponding()) {
            report.type = ApplicationErrorReport.TYPE_ANR;
            report.anrInfo = new ApplicationErrorReport.AnrInfo();

            report.anrInfo.activity = r.notRespondingReport.tag;
            report.anrInfo.cause = r.notRespondingReport.shortMsg;
            report.anrInfo.info = r.notRespondingReport.longMsg;
        }

        return report;
    }

    boolean handleAppCrashLocked(ProcessRecord app, String reason,
            String shortMsg, String longMsg, String stackTrace, AppErrorDialog.Data data) {
        final long now = SystemClock.uptimeMillis();
        final boolean showBackground = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.ANR_SHOW_BACKGROUND, 0) != 0;

        final boolean procIsBoundForeground =
            (app.getCurProcState() == ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE);

        Long crashTime;
        Long crashTimePersistent;
        boolean tryAgain = false;

        if (!app.isolated) {
            crashTime = mProcessCrashTimes.get(app.info.processName, app.uid);
            crashTimePersistent = mProcessCrashTimesPersistent.get(app.info.processName, app.uid);
        } else {
            crashTime = crashTimePersistent = null;
        }

        // Bump up the crash count of any services currently running in the proc.
        for (int i = app.services.size() - 1; i >= 0; i--) {
            // Any services running in the application need to be placed
            // back in the pending list.
            ServiceRecord sr = app.services.valueAt(i);
            // If the service was restarted a while ago, then reset crash count, else increment it.
            if (now > sr.restartTime + ProcessList.MIN_CRASH_INTERVAL) {
                sr.crashCount = 1;
            } else {
                sr.crashCount++;
            }
            // Allow restarting for started or bound foreground services that are crashing.
            // This includes wallpapers.
            if (sr.crashCount < mService.mConstants.BOUND_SERVICE_MAX_CRASH_RETRY
                    && (sr.isForeground || procIsBoundForeground)) {
                tryAgain = true;
            }
        }

        if (crashTime != null && now < crashTime + ProcessList.MIN_CRASH_INTERVAL) {
            // The process crashed again very quickly. If it was a bound foreground service, let's
            // try to restart again in a while, otherwise the process loses!
            Slog.w(TAG, "Process " + app.info.processName
                    + " has crashed too many times: killing!");
            EventLog.writeEvent(EventLogTags.AM_PROCESS_CRASHED_TOO_MUCH,
                    app.userId, app.info.processName, app.uid);
            mService.mAtmInternal.onHandleAppCrash(app.getWindowProcessController());
            if (!app.isPersistent()) {
                // We don't want to start this process again until the user
                // explicitly does so...  but for persistent process, we really
                // need to keep it running.  If a persistent process is actually
                // repeatedly crashing, then badness for everyone.
                EventLog.writeEvent(EventLogTags.AM_PROC_BAD, app.userId, app.uid,
                        app.info.processName);
                if (!app.isolated) {
                    // XXX We don't have a way to mark isolated processes
                    // as bad, since they don't have a peristent identity.
                    mBadProcesses.put(app.info.processName, app.uid,
                            new BadProcessInfo(now, shortMsg, longMsg, stackTrace));
                    mProcessCrashTimes.remove(app.info.processName, app.uid);
                }
                app.bad = true;
                app.removed = true;
                // Don't let services in this process be restarted and potentially
                // annoy the user repeatedly.  Unless it is persistent, since those
                // processes run critical code.
                mService.mProcessList.removeProcessLocked(app, false, tryAgain, "crash");
                mService.mAtmInternal.resumeTopActivities(false /* scheduleIdle */);
                if (!showBackground) {
                    return false;
                }
            }
            mService.mAtmInternal.resumeTopActivities(false /* scheduleIdle */);
        } else {
            final int affectedTaskId = mService.mAtmInternal.finishTopCrashedActivities(
                            app.getWindowProcessController(), reason);
            if (data != null) {
                data.taskId = affectedTaskId;
            }
            if (data != null && crashTimePersistent != null
                    && now < crashTimePersistent + ProcessList.MIN_CRASH_INTERVAL) {
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
        final WindowProcessController proc = app.getWindowProcessController();
        final WindowProcessController homeProc = mService.mAtmInternal.getHomeProcess();
        if (proc == homeProc && proc.hasActivities()
                && (((ProcessRecord) homeProc.mOwner).info.flags & FLAG_SYSTEM) == 0) {
            proc.clearPackagePreferredForHomeActivities();
        }

        if (!app.isolated) {
            // XXX Can't keep track of crash times for isolated processes,
            // because they don't have a persistent identity.
            mProcessCrashTimes.put(app.info.processName, app.uid, now);
            mProcessCrashTimesPersistent.put(app.info.processName, app.uid, now);
        }

        if (app.crashHandler != null) mService.mHandler.post(app.crashHandler);
        return true;
    }

    void handleShowAppErrorUi(Message msg) {
        AppErrorDialog.Data data = (AppErrorDialog.Data) msg.obj;
        boolean showBackground = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.ANR_SHOW_BACKGROUND, 0) != 0;

        AppErrorDialog dialogToShow = null;
        final String packageName;
        final int userId;
        synchronized (mService) {
            final ProcessRecord proc = data.proc;
            final AppErrorResult res = data.result;
            if (proc == null) {
                Slog.e(TAG, "handleShowAppErrorUi: proc is null");
                return;
            }
            packageName = proc.info.packageName;
            userId = proc.userId;
            if (proc.crashDialog != null) {
                Slog.e(TAG, "App already has crash dialog: " + proc);
                if (res != null) {
                    res.set(AppErrorDialog.ALREADY_SHOWING);
                }
                return;
            }
            boolean isBackground = (UserHandle.getAppId(proc.uid)
                    >= Process.FIRST_APPLICATION_UID
                    && proc.pid != MY_PID);
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
            final boolean showFirstCrash = Settings.Global.getInt(
                    mContext.getContentResolver(),
                    Settings.Global.SHOW_FIRST_CRASH_DIALOG, 0) != 0;
            final boolean showFirstCrashDevOption = Settings.Secure.getIntForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.SHOW_FIRST_CRASH_DIALOG_DEV_OPTION,
                    0,
                    mService.mUserController.getCurrentUserId()) != 0;
            final boolean crashSilenced = mAppsNotReportingCrashes != null &&
                    mAppsNotReportingCrashes.contains(proc.info.packageName);
            if ((mService.mAtmInternal.canShowErrorDialogs() || showBackground)
                    && !crashSilenced
                    && (showFirstCrash || showFirstCrashDevOption || data.repeating)) {
                proc.crashDialog = dialogToShow = new AppErrorDialog(mContext, mService, data);
            } else {
                // The device is asleep, so just pretend that the user
                // saw a crash dialog and hit "force quit".
                if (res != null) {
                    res.set(AppErrorDialog.CANT_SHOW);
                }
            }
        }
        // If we've created a crash dialog, show it without the lock held
        if (dialogToShow != null) {
            Slog.i(TAG, "Showing crash dialog for package " + packageName + " u" + userId);
            dialogToShow.show();
        }
    }

    private void stopReportingCrashesLocked(ProcessRecord proc) {
        if (mAppsNotReportingCrashes == null) {
            mAppsNotReportingCrashes = new ArraySet<>();
        }
        mAppsNotReportingCrashes.add(proc.info.packageName);
    }

    void handleShowAnrUi(Message msg) {
        Dialog dialogToShow = null;
        List<VersionedPackage> packageList = null;
        synchronized (mService) {
            AppNotRespondingDialog.Data data = (AppNotRespondingDialog.Data) msg.obj;
            final ProcessRecord proc = data.proc;
            if (proc == null) {
                Slog.e(TAG, "handleShowAnrUi: proc is null");
                return;
            }
            if (!proc.isPersistent()) {
                packageList = proc.getPackageListWithVersionCode();
            }
            if (proc.anrDialog != null) {
                Slog.e(TAG, "App already has anr dialog: " + proc);
                MetricsLogger.action(mContext, MetricsProto.MetricsEvent.ACTION_APP_ANR,
                        AppNotRespondingDialog.ALREADY_SHOWING);
                return;
            }

            boolean showBackground = Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.ANR_SHOW_BACKGROUND, 0) != 0;
            if (mService.mAtmInternal.canShowErrorDialogs() || showBackground) {
                dialogToShow = new AppNotRespondingDialog(mService, mContext, data);
                proc.anrDialog = dialogToShow;
            } else {
                MetricsLogger.action(mContext, MetricsProto.MetricsEvent.ACTION_APP_ANR,
                        AppNotRespondingDialog.CANT_SHOW);
                // Just kill the app if there is no dialog to be shown.
                mService.killAppAtUsersRequest(proc, null);
            }
        }
        // If we've created a crash dialog, show it without the lock held
        if (dialogToShow != null) {
            dialogToShow.show();
        }
        // Notify PackageWatchdog without the lock held
        if (packageList != null) {
            mPackageWatchdog.onPackageFailure(packageList,
                    PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING);
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
