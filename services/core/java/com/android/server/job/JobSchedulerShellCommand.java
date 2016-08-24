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

import android.app.AppGlobals;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.os.UserHandle;

import java.io.PrintWriter;

public class JobSchedulerShellCommand extends ShellCommand {
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
            if ("run".equals(cmd)) {
                return runJob();
            } else {
                return handleDefaultCommands(cmd);
            }
        } catch (Exception e) {
            pw.println("Exception: " + e);
        }
        return -1;
    }

    private int runJob() {
        try {
            final int uid = Binder.getCallingUid();
            final int perm = mPM.checkUidPermission(
                    "android.permission.CHANGE_APP_IDLE_STATE", uid);
            if (perm != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Uid " + uid
                        + " not permitted to force scheduled jobs");
            }
        } catch (RemoteException e) {
            // Can't happen
        }

        final PrintWriter pw = getOutPrintWriter();
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

        int ret = mInternal.executeRunCommand(pkgName, userId, jobId, force);
        switch (ret) {
            case CMD_ERR_NO_PACKAGE:
                pw.print("Package not found: ");
                pw.print(pkgName);
                pw.print(" / user ");
                pw.println(userId);
                break;

            case CMD_ERR_NO_JOB:
                pw.print("Could not find job ");
                pw.print(jobId);
                pw.print(" in package ");
                pw.print(pkgName);
                pw.print(" / user ");
                pw.println(userId);
                break;

            case CMD_ERR_CONSTRAINTS:
                pw.print("Job ");
                pw.print(jobId);
                pw.print(" in package ");
                pw.print(pkgName);
                pw.print(" / user ");
                pw.print(userId);
                pw.println(" has functional constraints but --force not specified");
                break;

            default:
                // success!
                pw.print("Running job");
                if (force) {
                    pw.print(" [FORCED]");
                }
                pw.println();
                break;
        }
        return ret;
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();

        pw.println("Job scheduler (jobscheduler) commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println();
        pw.println("  run [-f | --force] [-u | --user USER_ID] PACKAGE JOB_ID");
        pw.println("    Trigger immediate execution of a specific scheduled job.");
        pw.println("    Options:");
        pw.println("      -f or --force: run the job even if technical constraints such as");
        pw.println("         connectivity are not currently met");
        pw.println("      -u or --user: specify which user's job is to be run; the default is");
        pw.println("         the primary or system user");
        pw.println();
    }

}
