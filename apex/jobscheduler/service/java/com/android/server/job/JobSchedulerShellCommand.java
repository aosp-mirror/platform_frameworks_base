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

import android.app.ActivityManager;
import android.app.AppGlobals;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.ShellCommand;
import android.os.UserHandle;

import java.io.PrintWriter;

public final class JobSchedulerShellCommand extends ShellCommand {
    public static final int CMD_ERR_NO_PACKAGE = -1000;
    public static final int CMD_ERR_NO_JOB = -1001;
    public static final int CMD_ERR_CONSTRAINTS = -1002;

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
                case "get-battery-seq":
                    return getBatterySeq(pw);
                case "get-battery-charging":
                    return getBatteryCharging(pw);
                case "get-battery-not-low":
                    return getBatteryNotLow(pw);
                case "get-storage-seq":
                    return getStorageSeq(pw);
                case "get-storage-not-low":
                    return getStorageNotLow(pw);
                case "get-job-state":
                    return getJobState(pw);
                case "heartbeat":
                    return doHeartbeat(pw);
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

    private boolean printError(int errCode, String pkgName, int userId, int jobId) {
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
                pw.print(" / user ");
                pw.println(userId);
                return true;

            case CMD_ERR_CONSTRAINTS:
                pw = getErrPrintWriter();
                pw.print("Job ");
                pw.print(jobId);
                pw.print(" in package ");
                pw.print(pkgName);
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
        int userId = UserHandle.USER_SYSTEM;

        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "-f":
                case "--force":
                    force = true;
                    break;

                case "-u":
                case "--user":
                    userId = Integer.parseInt(getNextArgRequired());
                    break;

                default:
                    pw.println("Error: unknown option '" + opt + "'");
                    return -1;
            }
        }

        final String pkgName = getNextArgRequired();
        final int jobId = Integer.parseInt(getNextArgRequired());

        final long ident = Binder.clearCallingIdentity();
        try {
            int ret = mInternal.executeRunCommand(pkgName, userId, jobId, force);
            if (printError(ret, pkgName, userId, jobId)) {
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

        final String pkgName = getNextArg();
        final String jobIdStr = getNextArg();
        final int jobId = jobIdStr != null ? Integer.parseInt(jobIdStr) : -1;

        final long ident = Binder.clearCallingIdentity();
        try {
            return mInternal.executeTimeoutCommand(pw, pkgName, userId, jobIdStr != null, jobId);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private int cancelJob(PrintWriter pw) throws Exception {
        checkPermission("cancel jobs");

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

        if (userId < 0) {
            pw.println("Error: must specify a concrete user ID");
            return -1;
        }

        final String pkgName = getNextArg();
        final String jobIdStr = getNextArg();
        final int jobId = jobIdStr != null ? Integer.parseInt(jobIdStr) : -1;

        final long ident = Binder.clearCallingIdentity();
        try {
            return mInternal.executeCancelCommand(pw, pkgName, userId, jobIdStr != null, jobId);
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

    private int getBatterySeq(PrintWriter pw) {
        int seq = mInternal.getBatterySeq();
        pw.println(seq);
        return 0;
    }

    private int getBatteryCharging(PrintWriter pw) {
        boolean val = mInternal.getBatteryCharging();
        pw.println(val);
        return 0;
    }

    private int getBatteryNotLow(PrintWriter pw) {
        boolean val = mInternal.getBatteryNotLow();
        pw.println(val);
        return 0;
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

    private int getJobState(PrintWriter pw) throws Exception {
        checkPermission("force timeout jobs");

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
        final String jobIdStr = getNextArgRequired();
        final int jobId = Integer.parseInt(jobIdStr);

        final long ident = Binder.clearCallingIdentity();
        try {
            int ret = mInternal.getJobState(pw, pkgName, userId, jobId);
            printError(ret, pkgName, userId, jobId);
            return ret;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private int doHeartbeat(PrintWriter pw) throws Exception {
        checkPermission("manipulate scheduler heartbeat");

        final String arg = getNextArg();
        final int numBeats = (arg != null) ? Integer.parseInt(arg) : 0;

        final long ident = Binder.clearCallingIdentity();
        try {
            return mInternal.executeHeartbeatCommand(pw, numBeats);
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
        pw.println("  run [-f | --force] [-u | --user USER_ID] PACKAGE JOB_ID");
        pw.println("    Trigger immediate execution of a specific scheduled job.");
        pw.println("    Options:");
        pw.println("      -f or --force: run the job even if technical constraints such as");
        pw.println("         connectivity are not currently met");
        pw.println("      -u or --user: specify which user's job is to be run; the default is");
        pw.println("         the primary or system user");
        pw.println("  timeout [-u | --user USER_ID] [PACKAGE] [JOB_ID]");
        pw.println("    Trigger immediate timeout of currently executing jobs, as if their.");
        pw.println("    execution timeout had expired.");
        pw.println("    Options:");
        pw.println("      -u or --user: specify which user's job is to be run; the default is");
        pw.println("         all users");
        pw.println("  cancel [-u | --user USER_ID] PACKAGE [JOB_ID]");
        pw.println("    Cancel a scheduled job.  If a job ID is not supplied, all jobs scheduled");
        pw.println("    by that package will be canceled.  USE WITH CAUTION.");
        pw.println("    Options:");
        pw.println("      -u or --user: specify which user's job is to be run; the default is");
        pw.println("         the primary or system user");
        pw.println("  heartbeat [num]");
        pw.println("    With no argument, prints the current standby heartbeat.  With a positive");
        pw.println("    argument, advances the standby heartbeat by that number.");
        pw.println("  monitor-battery [on|off]");
        pw.println("    Control monitoring of all battery changes.  Off by default.  Turning");
        pw.println("    on makes get-battery-seq useful.");
        pw.println("  get-battery-seq");
        pw.println("    Return the last battery update sequence number that was received.");
        pw.println("  get-battery-charging");
        pw.println("    Return whether the battery is currently considered to be charging.");
        pw.println("  get-battery-not-low");
        pw.println("    Return whether the battery is currently considered to not be low.");
        pw.println("  get-storage-seq");
        pw.println("    Return the last storage update sequence number that was received.");
        pw.println("  get-storage-not-low");
        pw.println("    Return whether storage is currently considered to not be low.");
        pw.println("  get-job-state [-u | --user USER_ID] PACKAGE JOB_ID");
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
        pw.println("  trigger-dock-state [idle|active]");
        pw.println("    Trigger wireless charging dock state.  Active by default.");
        pw.println();
    }

}
