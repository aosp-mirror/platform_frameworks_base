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

import com.android.internal.app.ProcessMap;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto;
import com.android.internal.os.ProcessCpuTracker;
import com.android.server.Watchdog;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityThread;
import android.app.AppOpsManager;
import android.app.ApplicationErrorReport;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Set;

import static com.android.server.Watchdog.NATIVE_STACKS_OF_INTEREST;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_ANR;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.ActivityManagerService.MY_PID;
import static com.android.server.am.ActivityManagerService.SYSTEM_DEBUGGABLE;

/**
 * Controls error conditions in applications.
 */
class AppErrors {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "AppErrors" : TAG_AM;

    private final ActivityManagerService mService;
    private final Context mContext;

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


    AppErrors(Context context, ActivityManagerService service) {
        mService = service;
        mContext = context;
    }

    boolean dumpLocked(FileDescriptor fd, PrintWriter pw, boolean needSep,
            String dumpPackage) {
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
                    final ProcessRecord r = mService.mProcessNames.get(pname, puid);
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
                    final ProcessRecord r = mService.mProcessNames.get(pname, puid);
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
        app.crashing = false;
        app.crashingReport = null;
        app.notResponding = false;
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

    void scheduleAppCrashLocked(int uid, int initialPid, String packageName,
            String message) {
        ProcessRecord proc = null;

        // Figure out which process to kill.  We don't trust that initialPid
        // still has any relation to current pids, so must scan through the
        // list.

        synchronized (mService.mPidsSelfLocked) {
            for (int i=0; i<mService.mPidsSelfLocked.size(); i++) {
                ProcessRecord p = mService.mPidsSelfLocked.valueAt(i);
                if (p.uid != uid) {
                    continue;
                }
                if (p.pid == initialPid) {
                    proc = p;
                    break;
                }
                if (p.pkgList.containsKey(packageName)) {
                    proc = p;
                }
            }
        }

        if (proc == null) {
            Slog.w(TAG, "crashApplication: nothing for uid=" + uid
                    + " initialPid=" + initialPid
                    + " packageName=" + packageName);
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

        AppErrorResult result = new AppErrorResult();
        TaskRecord task;
        synchronized (mService) {
            /**
             * If crash is handled by instance of {@link android.app.IActivityController},
             * finish now and don't show the app error dialog.
             */
            if (handleAppCrashInActivityController(r, crashInfo, shortMsg, longMsg, stackTrace,
                    timeMillis, callingPid, callingUid)) {
                return;
            }

            /**
             * If this process was running instrumentation, finish now - it will be handled in
             * {@link ActivityManagerService#handleAppDiedLocked}.
             */
            if (r != null && r.instrumentationClass != null) {
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

            task = data.task;
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
                mService.removeProcessLocked(r, false, true, "crash");
                if (task != null) {
                    try {
                        mService.startActivityFromRecents(task.taskId,
                                ActivityOptions.makeBasic().toBundle());
                    } catch (IllegalArgumentException e) {
                        // Hmm, that didn't work, app might have crashed before creating a
                        // recents entry. Let's see if we have a safe-to-restart intent.
                        final Set<String> cats = task.intent.getCategories();
                        if (cats != null && cats.contains(Intent.CATEGORY_LAUNCHER)) {
                            mService.startActivityInPackage(task.mCallingUid,
                                    task.mCallingPackage, task.intent,
                                    null, null, null, 0, 0,
                                    ActivityOptions.makeBasic().toBundle(),
                                    task.userId, null, null);
                        }
                    }
                }
            }
            if (res == AppErrorDialog.FORCE_QUIT) {
                long orig = Binder.clearCallingIdentity();
                try {
                    // Kill it with fire!
                    mService.mStackSupervisor.handleAppCrashLocked(r);
                    if (!r.persistent) {
                        mService.removeProcessLocked(r, false, false, "crash");
                        mService.mStackSupervisor.resumeFocusedStackTopActivityLocked();
                    }
                } finally {
                    Binder.restoreCallingIdentity(orig);
                }
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
        if (mService.mController == null) {
            return false;
        }

        try {
            String name = r != null ? r.processName : null;
            int pid = r != null ? r.pid : callingPid;
            int uid = r != null ? r.info.uid : callingUid;
            if (!mService.mController.appCrashed(name, pid,
                    shortMsg, longMsg, timeMillis, crashInfo.stackTrace)) {
                if ("1".equals(SystemProperties.get(SYSTEM_DEBUGGABLE, "0"))
                        && "Native crash".equals(crashInfo.exceptionClassName)) {
                    Slog.w(TAG, "Skip killing native crashed app " + name
                            + "(" + pid + ") during testing");
                } else {
                    Slog.w(TAG, "Force-killing crashed app " + name
                            + " at watcher's request");
                    if (r != null) {
                        if (!makeAppCrashingLocked(r, shortMsg, longMsg, stackTrace, null))
                        {
                            r.kill("crash", true);
                        }
                    } else {
                        // Huh.
                        Process.killProcess(pid);
                        ActivityManagerService.killProcessGroup(uid, pid);
                    }
                }
                return true;
            }
        } catch (RemoteException e) {
            mService.mController = null;
            Watchdog.getInstance().setActivityController(null);
        }
        return false;
    }

    private boolean makeAppCrashingLocked(ProcessRecord app,
            String shortMsg, String longMsg, String stackTrace, AppErrorDialog.Data data) {
        app.crashing = true;
        app.crashingReport = generateProcessError(app,
                ActivityManager.ProcessErrorStateInfo.CRASHED, null, shortMsg, longMsg, stackTrace);
        startAppProblemLocked(app);
        app.stopFreezingAllLocked();
        return handleAppCrashLocked(app, "force-crash" /*reason*/, shortMsg, longMsg, stackTrace,
                data);
    }

    void startAppProblemLocked(ProcessRecord app) {
        // If this app is not running under the current user, then we
        // can't give it a report button because that would require
        // launching the report UI under a different user.
        app.errorReportReceiver = null;

        for (int userId : mService.mUserController.getCurrentProfileIdsLocked()) {
            if (app.userId == userId) {
                app.errorReportReceiver = ApplicationErrorReport.getErrorReportReceiver(
                        mContext, app.info.packageName, app.info.flags);
            }
        }
        mService.skipCurrentReceiverLocked(app);
    }

    /**
     * Generate a process error record, suitable for attachment to a ProcessRecord.
     *
     * @param app The ProcessRecord in which the error occurred.
     * @param condition Crashing, Application Not Responding, etc.  Values are defined in
     *                      ActivityManager.AppErrorStateInfo
     * @param activity The activity associated with the crash, if known.
     * @param shortMsg Short message describing the crash.
     * @param longMsg Long message describing the crash.
     * @param stackTrace Full crash stack trace, may be null.
     *
     * @return Returns a fully-formed AppErrorStateInfo record.
     */
    private ActivityManager.ProcessErrorStateInfo generateProcessError(ProcessRecord app,
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

        if (!r.crashing && !r.notResponding && !r.forceCrashReport) {
            return null;
        }

        ApplicationErrorReport report = new ApplicationErrorReport();
        report.packageName = r.info.packageName;
        report.installerPackageName = r.errorReportReceiver.getPackageName();
        report.processName = r.processName;
        report.time = timeMillis;
        report.systemApp = (r.info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;

        if (r.crashing || r.forceCrashReport) {
            report.type = ApplicationErrorReport.TYPE_CRASH;
            report.crashInfo = crashInfo;
        } else if (r.notResponding) {
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
        long now = SystemClock.uptimeMillis();
        boolean showBackground = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.ANR_SHOW_BACKGROUND, 0) != 0;

        Long crashTime;
        Long crashTimePersistent;
        if (!app.isolated) {
            crashTime = mProcessCrashTimes.get(app.info.processName, app.uid);
            crashTimePersistent = mProcessCrashTimesPersistent.get(app.info.processName, app.uid);
        } else {
            crashTime = crashTimePersistent = null;
        }
        if (crashTime != null && now < crashTime+ProcessList.MIN_CRASH_INTERVAL) {
            // This process loses!
            Slog.w(TAG, "Process " + app.info.processName
                    + " has crashed too many times: killing!");
            EventLog.writeEvent(EventLogTags.AM_PROCESS_CRASHED_TOO_MUCH,
                    app.userId, app.info.processName, app.uid);
            mService.mStackSupervisor.handleAppCrashLocked(app);
            if (!app.persistent) {
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
                mService.removeProcessLocked(app, false, false, "crash");
                mService.mStackSupervisor.resumeFocusedStackTopActivityLocked();
                if (!showBackground) {
                    return false;
                }
            }
            mService.mStackSupervisor.resumeFocusedStackTopActivityLocked();
        } else {
            TaskRecord affectedTask =
                    mService.mStackSupervisor.finishTopRunningActivityLocked(app, reason);
            if (data != null) {
                data.task = affectedTask;
            }
            if (data != null && crashTimePersistent != null
                    && now < crashTimePersistent + ProcessList.MIN_CRASH_INTERVAL) {
                data.repeating = true;
            }
        }

        boolean procIsBoundForeground =
                (app.curProcState == ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE);
        // Bump up the crash count of any services currently running in the proc.
        for (int i=app.services.size()-1; i>=0; i--) {
            // Any services running in the application need to be placed
            // back in the pending list.
            ServiceRecord sr = app.services.valueAt(i);
            sr.crashCount++;

            // Allow restarting for started or bound foreground services that are crashing the
            // first time. This includes wallpapers.
            if ((data != null) && (sr.crashCount <= 1)
                    && (sr.isForeground || procIsBoundForeground)) {
                data.isRestartableForService = true;
            }
        }

        // If the crashing process is what we consider to be the "home process" and it has been
        // replaced by a third-party app, clear the package preferred activities from packages
        // with a home activity running in the process to prevent a repeatedly crashing app
        // from blocking the user to manually clear the list.
        final ArrayList<ActivityRecord> activities = app.activities;
        if (app == mService.mHomeProcess && activities.size() > 0
                && (mService.mHomeProcess.info.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                final ActivityRecord r = activities.get(activityNdx);
                if (r.isHomeActivity()) {
                    Log.i(TAG, "Clearing package preferred activities from " + r.packageName);
                    try {
                        ActivityThread.getPackageManager()
                                .clearPackagePreferredActivities(r.packageName);
                    } catch (RemoteException c) {
                        // pm is in same process, this will never happen.
                    }
                }
            }
        }

        if (!app.isolated) {
            // XXX Can't keep track of crash times for isolated processes,
            // because they don't have a perisistent identity.
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
        synchronized (mService) {
            ProcessRecord proc = data.proc;
            AppErrorResult res = data.result;
            if (proc != null && proc.crashDialog != null) {
                Slog.e(TAG, "App already has crash dialog: " + proc);
                if (res != null) {
                    res.set(AppErrorDialog.ALREADY_SHOWING);
                }
                return;
            }
            boolean isBackground = (UserHandle.getAppId(proc.uid)
                    >= Process.FIRST_APPLICATION_UID
                    && proc.pid != MY_PID);
            for (int userId : mService.mUserController.getCurrentProfileIdsLocked()) {
                isBackground &= (proc.userId != userId);
            }
            if (isBackground && !showBackground) {
                Slog.w(TAG, "Skipping crash dialog of " + proc + ": background");
                if (res != null) {
                    res.set(AppErrorDialog.BACKGROUND_USER);
                }
                return;
            }
            final boolean crashSilenced = mAppsNotReportingCrashes != null &&
                    mAppsNotReportingCrashes.contains(proc.info.packageName);
            if ((mService.canShowErrorDialogs() || showBackground) && !crashSilenced) {
                proc.crashDialog = new AppErrorDialog(mContext, mService, data);
            } else {
                // The device is asleep, so just pretend that the user
                // saw a crash dialog and hit "force quit".
                if (res != null) {
                    res.set(AppErrorDialog.CANT_SHOW);
                }
            }
        }
        // If we've created a crash dialog, show it without the lock held
        if(data.proc.crashDialog != null) {
            data.proc.crashDialog.show();
        }
    }

    void stopReportingCrashesLocked(ProcessRecord proc) {
        if (mAppsNotReportingCrashes == null) {
            mAppsNotReportingCrashes = new ArraySet<>();
        }
        mAppsNotReportingCrashes.add(proc.info.packageName);
    }

    final void appNotResponding(ProcessRecord app, ActivityRecord activity,
            ActivityRecord parent, boolean aboveSystem, final String annotation) {
        ArrayList<Integer> firstPids = new ArrayList<Integer>(5);
        SparseArray<Boolean> lastPids = new SparseArray<Boolean>(20);

        if (mService.mController != null) {
            try {
                // 0 == continue, -1 = kill process immediately
                int res = mService.mController.appEarlyNotResponding(
                        app.processName, app.pid, annotation);
                if (res < 0 && app.pid != MY_PID) {
                    app.kill("anr", true);
                }
            } catch (RemoteException e) {
                mService.mController = null;
                Watchdog.getInstance().setActivityController(null);
            }
        }

        long anrTime = SystemClock.uptimeMillis();
        if (ActivityManagerService.MONITOR_CPU_USAGE) {
            mService.updateCpuStatsNow();
        }

        // Unless configured otherwise, swallow ANRs in background processes & kill the process.
        boolean showBackground = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.ANR_SHOW_BACKGROUND, 0) != 0;

        boolean isSilentANR;

        synchronized (mService) {
            // PowerManager.reboot() can block for a long time, so ignore ANRs while shutting down.
            if (mService.mShuttingDown) {
                Slog.i(TAG, "During shutdown skipping ANR: " + app + " " + annotation);
                return;
            } else if (app.notResponding) {
                Slog.i(TAG, "Skipping duplicate ANR: " + app + " " + annotation);
                return;
            } else if (app.crashing) {
                Slog.i(TAG, "Crashing app skipping ANR: " + app + " " + annotation);
                return;
            } else if (app.killedByAm) {
                Slog.i(TAG, "App already killed by AM skipping ANR: " + app + " " + annotation);
                return;
            } else if (app.killed) {
                Slog.i(TAG, "Skipping died app ANR: " + app + " " + annotation);
                return;
            }

            // In case we come through here for the same app before completing
            // this one, mark as anring now so we will bail out.
            app.notResponding = true;

            // Log the ANR to the event log.
            EventLog.writeEvent(EventLogTags.AM_ANR, app.userId, app.pid,
                    app.processName, app.info.flags, annotation);

            // Dump thread traces as quickly as we can, starting with "interesting" processes.
            firstPids.add(app.pid);

            // Don't dump other PIDs if it's a background ANR
            isSilentANR = !showBackground && !app.isInterestingToUserLocked() && app.pid != MY_PID;
            if (!isSilentANR) {
                int parentPid = app.pid;
                if (parent != null && parent.app != null && parent.app.pid > 0) {
                    parentPid = parent.app.pid;
                }
                if (parentPid != app.pid) firstPids.add(parentPid);

                if (MY_PID != app.pid && MY_PID != parentPid) firstPids.add(MY_PID);

                for (int i = mService.mLruProcesses.size() - 1; i >= 0; i--) {
                    ProcessRecord r = mService.mLruProcesses.get(i);
                    if (r != null && r.thread != null) {
                        int pid = r.pid;
                        if (pid > 0 && pid != app.pid && pid != parentPid && pid != MY_PID) {
                            if (r.persistent) {
                                firstPids.add(pid);
                                if (DEBUG_ANR) Slog.i(TAG, "Adding persistent proc: " + r);
                            } else {
                                lastPids.put(pid, Boolean.TRUE);
                                if (DEBUG_ANR) Slog.i(TAG, "Adding ANR proc: " + r);
                            }
                        }
                    }
                }
            }
        }

        // Log the ANR to the main log.
        StringBuilder info = new StringBuilder();
        info.setLength(0);
        info.append("ANR in ").append(app.processName);
        if (activity != null && activity.shortComponentName != null) {
            info.append(" (").append(activity.shortComponentName).append(")");
        }
        info.append("\n");
        info.append("PID: ").append(app.pid).append("\n");
        if (annotation != null) {
            info.append("Reason: ").append(annotation).append("\n");
        }
        if (parent != null && parent != activity) {
            info.append("Parent: ").append(parent.shortComponentName).append("\n");
        }

        ProcessCpuTracker processCpuTracker = new ProcessCpuTracker(true);

        String[] nativeProcs = NATIVE_STACKS_OF_INTEREST;
        // don't dump native PIDs for background ANRs
        File tracesFile = null;
        if (isSilentANR) {
            tracesFile = mService.dumpStackTraces(true, firstPids, null, lastPids,
                null);
        } else {
            tracesFile = mService.dumpStackTraces(true, firstPids, processCpuTracker, lastPids,
                nativeProcs);
        }

        String cpuInfo = null;
        if (ActivityManagerService.MONITOR_CPU_USAGE) {
            mService.updateCpuStatsNow();
            synchronized (mService.mProcessCpuTracker) {
                cpuInfo = mService.mProcessCpuTracker.printCurrentState(anrTime);
            }
            info.append(processCpuTracker.printCurrentLoad());
            info.append(cpuInfo);
        }

        info.append(processCpuTracker.printCurrentState(anrTime));

        Slog.e(TAG, info.toString());
        if (tracesFile == null) {
            // There is no trace file, so dump (only) the alleged culprit's threads to the log
            Process.sendSignal(app.pid, Process.SIGNAL_QUIT);
        }

        mService.addErrorToDropBox("anr", app, app.processName, activity, parent, annotation,
                cpuInfo, tracesFile, null);

        if (mService.mController != null) {
            try {
                // 0 == show dialog, 1 = keep waiting, -1 = kill process immediately
                int res = mService.mController.appNotResponding(
                        app.processName, app.pid, info.toString());
                if (res != 0) {
                    if (res < 0 && app.pid != MY_PID) {
                        app.kill("anr", true);
                    } else {
                        synchronized (mService) {
                            mService.mServices.scheduleServiceTimeoutLocked(app);
                        }
                    }
                    return;
                }
            } catch (RemoteException e) {
                mService.mController = null;
                Watchdog.getInstance().setActivityController(null);
            }
        }

        synchronized (mService) {
            mService.mBatteryStatsService.noteProcessAnr(app.processName, app.uid);

            if (isSilentANR) {
                app.kill("bg anr", true);
                return;
            }

            // Set the app's notResponding state, and look up the errorReportReceiver
            makeAppNotRespondingLocked(app,
                    activity != null ? activity.shortComponentName : null,
                    annotation != null ? "ANR " + annotation : "ANR",
                    info.toString());

            // Bring up the infamous App Not Responding dialog
            Message msg = Message.obtain();
            HashMap<String, Object> map = new HashMap<String, Object>();
            msg.what = ActivityManagerService.SHOW_NOT_RESPONDING_UI_MSG;
            msg.obj = map;
            msg.arg1 = aboveSystem ? 1 : 0;
            map.put("app", app);
            if (activity != null) {
                map.put("activity", activity);
            }

            mService.mUiHandler.sendMessage(msg);
        }
    }

    private void makeAppNotRespondingLocked(ProcessRecord app,
            String activity, String shortMsg, String longMsg) {
        app.notResponding = true;
        app.notRespondingReport = generateProcessError(app,
                ActivityManager.ProcessErrorStateInfo.NOT_RESPONDING,
                activity, shortMsg, longMsg, null);
        startAppProblemLocked(app);
        app.stopFreezingAllLocked();
    }

    void handleShowAnrUi(Message msg) {
        Dialog d = null;
        synchronized (mService) {
            HashMap<String, Object> data = (HashMap<String, Object>) msg.obj;
            ProcessRecord proc = (ProcessRecord)data.get("app");
            if (proc != null && proc.anrDialog != null) {
                Slog.e(TAG, "App already has anr dialog: " + proc);
                MetricsLogger.action(mContext, MetricsProto.MetricsEvent.ACTION_APP_ANR,
                        AppNotRespondingDialog.ALREADY_SHOWING);
                return;
            }

            Intent intent = new Intent("android.intent.action.ANR");
            if (!mService.mProcessesReady) {
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                        | Intent.FLAG_RECEIVER_FOREGROUND);
            }
            mService.broadcastIntentLocked(null, null, intent,
                    null, null, 0, null, null, null, AppOpsManager.OP_NONE,
                    null, false, false, MY_PID, Process.SYSTEM_UID, 0 /* TODO: Verify */);

            boolean showBackground = Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.ANR_SHOW_BACKGROUND, 0) != 0;
            if (mService.canShowErrorDialogs() || showBackground) {
                d = new AppNotRespondingDialog(mService,
                        mContext, proc, (ActivityRecord)data.get("activity"),
                        msg.arg1 != 0);
                proc.anrDialog = d;
            } else {
                MetricsLogger.action(mContext, MetricsProto.MetricsEvent.ACTION_APP_ANR,
                        AppNotRespondingDialog.CANT_SHOW);
                // Just kill the app if there is no dialog to be shown.
                mService.killAppAtUsersRequest(proc, null);
            }
        }
        // If we've created a crash dialog, show it without the lock held
        if (d != null) {
            d.show();
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
