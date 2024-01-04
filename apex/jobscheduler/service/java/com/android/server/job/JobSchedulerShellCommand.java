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

package com.android.server.job;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AppGlobals;
import android.app.job.JobParameters;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.UserHandle;

import com.android.modules.utils.BasicShellCommandHandler;

import java.io.PrintWriter;

public final class JobSchedulerShellCommand extends BasicShellCommandHandler {
    public static final int CMD_ERR_NO_PACKAGE = -1000;
    public static final int CMD_ERR_NO_JOB = -1001;
    public static final int CMD_ERR_CONSTRAINTS = -1002;

    static final int BYTE_OPTION_DOWNLOAD = 0;
    static final int BYTE_OPTION_UPLOAD = 1;

    JobSchedulerService mInternal;
    IPackageManager mPM;

    JobSchedulerShellCommand(JobSchedulerService service) {
        mInternal = service;
        mPM = AppGlobals.getPackageManager();
    }

    @Override
    public int onCommand(String cmd) {
        final PrintWriter pw = getOutPrintWriter();
        try {
            switch (cmd != null ? cmd : "") {
                case "run":
                    return runJob(pw);
                case "timeout":
                    return timeout(pw);
                case "cancel":
                    return cancelJob(pw);
                case "monitor-battery":
                    return monitorBattery(pw);
                case "get-aconfig-flag-state":
                    return getAconfigFlagState(pw);
                case "get-battery-seq":
                    return getBatterySeq(pw);
                case "get-battery-charging":
                    return getBatteryCharging(pw);
                case "get-battery-not-low":
                    return getBatteryNotLow(pw);
                case "get-estimated-download-bytes":
                    return getEstimatedNetworkBytes(pw, BYTE_OPTION_DOWNLOAD);
                case "get-estimated-upload-bytes":
                    return getEstimatedNetworkBytes(pw, BYTE_OPTION_UPLOAD);
                case "get-storage-seq":
                    return getStorageSeq(pw);
                case "get-storage-not-low":
                    return getStorageNotLow(pw);
                case "get-transferred-download-bytes":
                    return getTransferredNetworkBytes(pw, BYTE_OPTION_DOWNLOAD);
                case "get-transferred-upload-bytes":
                    return getTransferredNetworkBytes(pw, BYTE_OPTION_UPLOAD);
                case "get-job-state":
                    return getJobState(pw);
                case "heartbeat":
                    return doHeartbeat(pw);
                case "reset-execution-quota":
                    return resetExecutionQuota(pw);
                case "reset-schedule-quota":
                    return resetScheduleQuota(pw);
                case "stop":
                    return stop(pw);
                case "trigger-dock-state":
                    return triggerDockState(pw);
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (Exception e) {
            pw.println("Exception: " + e);
        }
        return -1;
    }

    private void checkPermission(String operation) throws Exception {
        final int uid = Binder.getCallingUid();
        if (uid == 0) {
            // Root can do anything.
            return;
        }
        final int perm = mPM.checkUidPermission(
                "android.permission.CHANGE_APP_IDLE_STATE", uid);
        if (perm != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Uid " + uid
                    + " not permitted to " + operation);
        }
    }

    private boolean printError(int errCode, String pkgName, int userId, @Nullable String namespace,
            int jobId) {
        PrintWriter pw;
        switch (errCode) {
            case CMD_ERR_NO_PACKAGE:
                pw = getErrPrintWriter();
                pw.print("Package not found: ");
                pw.print(pkgName);
                pw.print(" / user ");
                pw.println(userId);
                return true;

            case CMD_ERR_NO_JOB:
                pw = getErrPrintWriter();
                pw.print("Could not find job ");
                pw.print(jobId);
                pw.print(" in package ");
                pw.print(pkgName);
                if (namespace != null) {
                    pw.print(" / namespace ");
                    pw.print(namespace);
                }
                pw.print(" / user ");
                pw.println(userId);
                return true;

            case CMD_ERR_CONSTRAINTS:
                pw = getErrPrintWriter();
                pw.print("Job ");
                pw.print(jobId);
                pw.print(" in package ");
                pw.print(pkgName);
                if (namespace != null) {
                    pw.print(" / namespace ");
                    pw.print(namespace);
                }
                pw.print(" / user ");
                pw.print(userId);
                pw.println(" has functional constraints but --force not specified");
                return true;

            default:
                return false;
        }
    }

    private int runJob(PrintWriter pw) throws Exception {
        checkPermission("force scheduled jobs");

        boolean force = false;
        boolean satisfied = false;
        int userId = UserHandle.USER_SYSTEM;
        String namespace = null;

        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "-f":
                case "--force":
                    force = true;
                    break;

                case "-s":
                case "--satisfied":
                    satisfied = true;
                    break;

                case "-u":
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;

                case "-n":
                case "--namespace":
                    namespace = getNextArgRequired();
                    break;

                default:
                    pw.println("Error: unknown option '" + opt + "'");
                    return -1;
            }
        }

        if (force && satisfied) {
            pw.println("Cannot specify both --force and --satisfied");
            return -1;
        }

        if (userId == UserHandle.USER_CURRENT) {
            userId = ActivityManager.getCurrentUser();
        }

        final String pkgName = getNextArgRequired();
        final int jobId = Integer.parseInt(getNextArgRequired());

        final long ident = Binder.clearCallingIdentity();
        try {
            int ret = mInternal.executeRunCommand(pkgName, userId, namespace,
                    jobId, satisfied, force);
            if (printError(ret, pkgName, userId, namespace, jobId)) {
                return ret;
            }

            // success!
            pw.print("Running job");
            if (force) {
                pw.print(" [FORCED]");
            }
            pw.println();

            return ret;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private int timeout(PrintWriter pw) throws Exception {
        checkPermission("force timeout jobs");

        int userId = UserHandle.USER_ALL;
        String namespace = null;

        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "-u":
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;

                case "-n":
                case "--namespace":
                    namespace = getNextArgRequired();
                    break;

                default:
                    pw.println("Error: unknown option '" + opt + "'");
                    return -1;
            }
        }

        if (userId == UserHandle.USER_CURRENT) {
            userId = ActivityManager.getCurrentUser();
        }

        final String pkgName = getNextArg();
        final String jobIdStr = getNextArg();
        final int jobId = jobIdStr != null ? Integer.parseInt(jobIdStr) : -1;

        final long ident = Binder.clearCallingIdentity();
        try {
            return mInternal.executeStopCommand(pw, pkgName, userId, namespace,
                    jobIdStr != null, jobId,
                    JobParameters.STOP_REASON_TIMEOUT, JobParameters.INTERNAL_STOP_REASON_TIMEOUT);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private int cancelJob(PrintWriter pw) throws Exception {
        checkPermission("cancel jobs");

        int userId = UserHandle.USER_SYSTEM;
        String namespace = null;

        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "-u":
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;

                case "-n":
                case "--namespace":
                    namespace = getNextArgRequired();
                    break;

                default:
                    pw.println("Error: unknown option '" + opt + "'");
                    return -1;
            }
        }

        if (userId < 0) {
            pw.println("Error: must specify a concrete user ID");
            return -1;
        }

        final String pkgName = getNextArg();
        final String jobIdStr = getNextArg();
        final int jobId = jobIdStr != null ? Integer.parseInt(jobIdStr) : -1;

        final long ident = Binder.clearCallingIdentity();
        try {
            return mInternal.executeCancelCommand(pw, pkgName, userId, namespace,
                    jobIdStr != null, jobId);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private int monitorBattery(PrintWriter pw) throws Exception {
        checkPermission("change battery monitoring");
        String opt = getNextArgRequired();
        boolean enabled;
        if ("on".equals(opt)) {
            enabled = true;
        } else if ("off".equals(opt)) {
            enabled = false;
        } else {
            getErrPrintWriter().println("Error: unknown option " + opt);
            return 1;
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            mInternal.setMonitorBattery(enabled);
            if (enabled) pw.println("Battery monitoring enabled");
            else pw.println("Battery monitoring disabled");
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        return 0;
    }

    private int getAconfigFlagState(PrintWriter pw) throws Exception {
        checkPermission("get aconfig flag state");

        final String flagName = getNextArgRequired();

        switch (flagName) {
            case android.app.job.Flags.FLAG_JOB_DEBUG_INFO_APIS:
                pw.println(android.app.job.Flags.jobDebugInfoApis());
                break;
            case android.app.job.Flags.FLAG_ENFORCE_MINIMUM_TIME_WINDOWS:
                pw.println(android.app.job.Flags.enforceMinimumTimeWindows());
                break;
            case com.android.server.job.Flags.FLAG_THROW_ON_UNSUPPORTED_BIAS_USAGE:
                pw.println(com.android.server.job.Flags.throwOnUnsupportedBiasUsage());
                break;
            case android.app.job.Flags.FLAG_BACKUP_JOBS_EXEMPTION:
                pw.println(android.app.job.Flags.backupJobsExemption());
                break;
            default:
                pw.println("Unknown flag: " + flagName);
                break;
        }
        return 0;
    }

    private int getBatterySeq(PrintWriter pw) {
        int seq = mInternal.getBatterySeq();
        pw.println(seq);
        return 0;
    }

    private int getBatteryCharging(PrintWriter pw) {
        boolean val = mInternal.isBatteryCharging();
        pw.println(val);
        return 0;
    }

    private int getBatteryNotLow(PrintWriter pw) {
        boolean val = mInternal.isBatteryNotLow();
        pw.println(val);
        return 0;
    }

    private int getEstimatedNetworkBytes(PrintWriter pw, int byteOption) throws Exception {
        checkPermission("get estimated bytes");

        int userId = UserHandle.USER_SYSTEM;
        String namespace = null;

        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "-u":
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;

                case "-n":
                case "--namespace":
                    namespace = getNextArgRequired();
                    break;

                default:
                    pw.println("Error: unknown option '" + opt + "'");
                    return -1;
            }
        }

        if (userId == UserHandle.USER_CURRENT) {
            userId = ActivityManager.getCurrentUser();
        }

        final String pkgName = getNextArgRequired();
        final String jobIdStr = getNextArgRequired();
        final int jobId = Integer.parseInt(jobIdStr);

        final long ident = Binder.clearCallingIdentity();
        try {
            int ret = mInternal.getEstimatedNetworkBytes(pw, pkgName, userId, namespace,
                    jobId, byteOption);
            printError(ret, pkgName, userId, namespace, jobId);
            return ret;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private int getStorageSeq(PrintWriter pw) {
        int seq = mInternal.getStorageSeq();
        pw.println(seq);
        return 0;
    }

    private int getStorageNotLow(PrintWriter pw) {
        boolean val = mInternal.getStorageNotLow();
        pw.println(val);
        return 0;
    }

    private int getTransferredNetworkBytes(PrintWriter pw, int byteOption) throws Exception {
        checkPermission("get transferred bytes");

        int userId = UserHandle.USER_SYSTEM;
        String namespace = null;

        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "-u":
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;

                case "-n":
                case "--namespace":
                    namespace = getNextArgRequired();
                    break;

                default:
                    pw.println("Error: unknown option '" + opt + "'");
                    return -1;
            }
        }

        if (userId == UserHandle.USER_CURRENT) {
            userId = ActivityManager.getCurrentUser();
        }

        final String pkgName = getNextArgRequired();
        final String jobIdStr = getNextArgRequired();
        final int jobId = Integer.parseInt(jobIdStr);

        final long ident = Binder.clearCallingIdentity();
        try {
            int ret = mInternal.getTransferredNetworkBytes(pw, pkgName, userId, namespace,
                    jobId, byteOption);
            printError(ret, pkgName, userId, namespace, jobId);
            return ret;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private int getJobState(PrintWriter pw) throws Exception {
        checkPermission("get job state");

        int userId = UserHandle.USER_SYSTEM;
        String namespace = null;

        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "-u":
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;

                case "-n":
                case "--namespace":
                    namespace = getNextArgRequired();
                    break;

                default:
                    pw.println("Error: unknown option '" + opt + "'");
                    return -1;
            }
        }

        if (userId == UserHandle.USER_CURRENT) {
            userId = ActivityManager.getCurrentUser();
        }

        final String pkgName = getNextArgRequired();
        final String jobIdStr = getNextArgRequired();
        final int jobId = Integer.parseInt(jobIdStr);

        final long ident = Binder.clearCallingIdentity();
        try {
            int ret = mInternal.getJobState(pw, pkgName, userId, namespace, jobId);
            printError(ret, pkgName, userId, namespace, jobId);
            return ret;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private int doHeartbeat(PrintWriter pw) throws Exception {
        checkPermission("manipulate scheduler heartbeat");

        pw.println("Heartbeat command is no longer supported");
        return -1;
    }

    private int resetExecutionQuota(PrintWriter pw) throws Exception {
        checkPermission("reset execution quota");

        int userId = UserHandle.USER_SYSTEM;

        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "-u":
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;

                default:
                    pw.println("Error: unknown option '" + opt + "'");
                    return -1;
            }
        }

        if (userId == UserHandle.USER_CURRENT) {
            userId = ActivityManager.getCurrentUser();
        }

        final String pkgName = getNextArgRequired();

        final long ident = Binder.clearCallingIdentity();
        try {
            mInternal.resetExecutionQuota(pkgName, userId);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        return 0;
    }

    private int resetScheduleQuota(PrintWriter pw) throws Exception {
        checkPermission("reset schedule quota");

        final long ident = Binder.clearCallingIdentity();
        try {
            mInternal.resetScheduleQuota();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        return 0;
    }

    private int stop(PrintWriter pw) throws Exception {
        checkPermission("stop jobs");

        int userId = UserHandle.USER_ALL;
        String namespace = null;
        int stopReason = JobParameters.STOP_REASON_USER;
        int internalStopReason = JobParameters.INTERNAL_STOP_REASON_UNKNOWN;

        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "-u":
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;

                case "-n":
                case "--namespace":
                    namespace = getNextArgRequired();
                    break;

                case "-s":
                case "--stop-reason":
                    stopReason = Integer.parseInt(getNextArgRequired());
                    break;

                case "-i":
                case "--internal-stop-reason":
                    internalStopReason = Integer.parseInt(getNextArgRequired());
                    break;

                default:
                    pw.println("Error: unknown option '" + opt + "'");
                    return -1;
            }
        }

        if (userId == UserHandle.USER_CURRENT) {
            userId = ActivityManager.getCurrentUser();
        }

        final String pkgName = getNextArg();
        final String jobIdStr = getNextArg();
        final int jobId = jobIdStr != null ? Integer.parseInt(jobIdStr) : -1;

        final long ident = Binder.clearCallingIdentity();
        try {
            return mInternal.executeStopCommand(pw, pkgName, userId, namespace,
                    jobIdStr != null, jobId, stopReason, internalStopReason);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private int triggerDockState(PrintWriter pw) throws Exception {
        checkPermission("trigger wireless charging dock state");

        final String opt = getNextArgRequired();
        boolean idleState;
        if ("idle".equals(opt)) {
            idleState = true;
        } else if ("active".equals(opt)) {
            idleState = false;
        } else {
            getErrPrintWriter().println("Error: unknown option " + opt);
            return 1;
        }

        final long ident = Binder.clearCallingIdentity();
        try {
            mInternal.triggerDockState(idleState);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        return 0;
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();

        pw.println("Job scheduler (jobscheduler) commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("  run [-f | --force] [-s | --satisfied] [-u | --user USER_ID]"
                + " [-n | --namespace NAMESPACE] PACKAGE JOB_ID");
        pw.println("    Trigger immediate execution of a specific scheduled job. For historical");
        pw.println("    reasons, some constraints, such as battery, are ignored when this");
        pw.println("    command is called. If you don't want any constraints to be ignored,");
        pw.println("    include the -s flag.");
        pw.println("    Options:");
        pw.println("      -f or --force: run the job even if technical constraints such as");
        pw.println("         connectivity are not currently met. This is incompatible with -f ");
        pw.println("         and so an error will be reported if both are given.");
        pw.println("      -n or --namespace: specify the namespace this job sits in; the default");
        pw.println("         is null (no namespace).");
        pw.println("      -s or --satisfied: run the job only if all constraints are met.");
        pw.println("         This is incompatible with -f and so an error will be reported");
        pw.println("         if both are given.");
        pw.println("      -u or --user: specify which user's job is to be run; the default is");
        pw.println("         the primary or system user");
        pw.println("  stop [-u | --user USER_ID] [-n | --namespace NAMESPACE]"
                + " [-s | --stop-reason STOP_REASON] [-i | --internal-stop-reason STOP_REASON]"
                + " [PACKAGE] [JOB_ID]");
        pw.println("    Trigger immediate stop of currently executing jobs using the specified");
        pw.println("    stop reasons.");
        pw.println("    Options:");
        pw.println("      -u or --user: specify which user's job is to be run; the default is");
        pw.println("         all users");
        pw.println("      -n or --namespace: specify the namespace this job sits in; the default");
        pw.println("         is null (no namespace).");
        pw.println("      -s or --stop-reason: specify the stop reason given to the job.");
        pw.println("         Valid values are those that can be returned from");
        pw.println("         JobParameters.getStopReason().");
        pw.println("          The default value is STOP_REASON_USER.");
        pw.println("      -i or --internal-stop-reason: specify the internal stop reason.");
        pw.println("         JobScheduler will use for internal processing.");
        pw.println("         Valid values are those that can be returned from");
        pw.println("         JobParameters.getInternalStopReason().");
        pw.println("          The default value is INTERNAL_STOP_REASON_UNDEFINED.");
        pw.println("  timeout [-u | --user USER_ID] [-n | --namespace NAMESPACE]"
                + " [PACKAGE] [JOB_ID]");
        pw.println("    Trigger immediate timeout of currently executing jobs, as if their");
        pw.println("    execution timeout had expired.");
        pw.println("    This is the equivalent of calling `stop -s 3 -i 3`.");
        pw.println("    Options:");
        pw.println("      -u or --user: specify which user's job is to be run; the default is");
        pw.println("         all users");
        pw.println("      -n or --namespace: specify the namespace this job sits in; the default");
        pw.println("         is null (no namespace).");
        pw.println("  cancel [-u | --user USER_ID] [-n | --namespace NAMESPACE] PACKAGE [JOB_ID]");
        pw.println("    Cancel a scheduled job.  If a job ID is not supplied, all jobs scheduled");
        pw.println("    by that package will be canceled.  USE WITH CAUTION.");
        pw.println("    Options:");
        pw.println("      -u or --user: specify which user's job is to be run; the default is");
        pw.println("         the primary or system user");
        pw.println("      -n or --namespace: specify the namespace this job sits in; the default");
        pw.println("         is null (no namespace).");
        pw.println("  heartbeat [num]");
        pw.println("    No longer used.");
        pw.println("  monitor-battery [on|off]");
        pw.println("    Control monitoring of all battery changes.  Off by default.  Turning");
        pw.println("    on makes get-battery-seq useful.");
        pw.println("  get-aconfig-flag-state FULL_FLAG_NAME");
        pw.println("    Return the state of the specified aconfig flag, if known. The flag name");
        pw.println("         must be fully qualified.");
        pw.println("  get-battery-seq");
        pw.println("    Return the last battery update sequence number that was received.");
        pw.println("  get-battery-charging");
        pw.println("    Return whether the battery is currently considered to be charging.");
        pw.println("  get-battery-not-low");
        pw.println("    Return whether the battery is currently considered to not be low.");
        pw.println("  get-estimated-download-bytes [-u | --user USER_ID]"
                + " [-n | --namespace NAMESPACE] PACKAGE JOB_ID");
        pw.println("    Return the most recent estimated download bytes for the job.");
        pw.println("    Options:");
        pw.println("      -u or --user: specify which user's job is to be run; the default is");
        pw.println("         the primary or system user");
        pw.println("  get-estimated-upload-bytes [-u | --user USER_ID]"
                + " [-n | --namespace NAMESPACE] PACKAGE JOB_ID");
        pw.println("    Return the most recent estimated upload bytes for the job.");
        pw.println("    Options:");
        pw.println("      -u or --user: specify which user's job is to be run; the default is");
        pw.println("         the primary or system user");
        pw.println("  get-storage-seq");
        pw.println("    Return the last storage update sequence number that was received.");
        pw.println("  get-storage-not-low");
        pw.println("    Return whether storage is currently considered to not be low.");
        pw.println("  get-transferred-download-bytes [-u | --user USER_ID]"
                + " [-n | --namespace NAMESPACE] PACKAGE JOB_ID");
        pw.println("    Return the most recent transferred download bytes for the job.");
        pw.println("    Options:");
        pw.println("      -u or --user: specify which user's job is to be run; the default is");
        pw.println("         the primary or system user");
        pw.println("  get-transferred-upload-bytes [-u | --user USER_ID]"
                + " [-n | --namespace NAMESPACE] PACKAGE JOB_ID");
        pw.println("    Return the most recent transferred upload bytes for the job.");
        pw.println("    Options:");
        pw.println("      -u or --user: specify which user's job is to be run; the default is");
        pw.println("         the primary or system user");
        pw.println("  get-job-state [-u | --user USER_ID] [-n | --namespace NAMESPACE]"
                + " PACKAGE JOB_ID");
        pw.println("    Return the current state of a job, may be any combination of:");
        pw.println("      pending: currently on the pending list, waiting to be active");
        pw.println("      active: job is actively running");
        pw.println("      user-stopped: job can't run because its user is stopped");
        pw.println("      backing-up: job can't run because app is currently backing up its data");
        pw.println("      no-component: job can't run because its component is not available");
        pw.println("      ready: job is ready to run (all constraints satisfied or bypassed)");
        pw.println("      waiting: if nothing else above is printed, job not ready to run");
        pw.println("    Options:");
        pw.println("      -u or --user: specify which user's job is to be run; the default is");
        pw.println("         the primary or system user");
        pw.println("      -n or --namespace: specify the namespace this job sits in; the default");
        pw.println("         is null (no namespace).");
        pw.println("  trigger-dock-state [idle|active]");
        pw.println("    Trigger wireless charging dock state.  Active by default.");
        pw.println();
    }
}
