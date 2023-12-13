/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.usage;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.os.ShellCommand;
import android.os.UserHandle;

import java.io.PrintWriter;

class UsageStatsShellCommand extends ShellCommand {
    private final UsageStatsService mService;

    UsageStatsShellCommand(UsageStatsService usageStatsService) {
        mService = usageStatsService;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(null);
        }
        switch (cmd) {
            case "clear-last-used-timestamps":
                return runClearLastUsedTimestamps();
            case "delete-package-data":
                return deletePackageData();
            default:
                return handleDefaultCommands(cmd);
        }
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.println("UsageStats service (usagestats) commands:");
        pw.println("help");
        pw.println("    Print this help text.");
        pw.println();
        pw.println("clear-last-used-timestamps PACKAGE_NAME [-u | --user USER_ID]");
        pw.println("    Clears the last used timestamps for the given package.");
        pw.println();
        pw.println("delete-package-data PACKAGE_NAME [-u | --user USER_ID]");
        pw.println("    Deletes all the usage stats for the given package.");
        pw.println();
    }

    @SuppressLint("AndroidFrameworkRequiresPermission")
    private int runClearLastUsedTimestamps() {
        final String packageName = getNextArgRequired();
        final int userId = getUserId();
        if (userId == -1) {
            return -1;
        }

        mService.clearLastUsedTimestamps(packageName, userId);
        return 0;
    }

    @SuppressLint("AndroidFrameworkRequiresPermission")
    private int deletePackageData() {
        final String packageName = getNextArgRequired();
        final int userId = getUserId();
        if (userId == -1) {
            return -1;
        }

        mService.deletePackageData(packageName, userId);
        return 0;
    }

    private int getUserId() {
        int userId = UserHandle.USER_CURRENT;
        String opt;
        while ((opt = getNextOption()) != null) {
            if ("-u".equals(opt) || "--user".equals(opt)) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
            } else {
                getErrPrintWriter().println("Error: unknown option: " + opt);
                return -1;
            }
        }
        if (userId == UserHandle.USER_CURRENT) {
            userId = ActivityManager.getCurrentUser();
        }
        return userId;
    }
}
