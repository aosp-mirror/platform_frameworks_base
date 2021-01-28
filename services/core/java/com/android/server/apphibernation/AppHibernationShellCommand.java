/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.apphibernation;

import android.os.ShellCommand;
import android.os.UserHandle;
import android.text.TextUtils;

import java.io.PrintWriter;

/**
 * Shell command implementation for {@link AppHibernationService}.
 */
final class AppHibernationShellCommand extends ShellCommand {
    private static final String USER_OPT = "--user";
    private static final int SUCCESS = 0;
    private static final int ERROR = -1;
    private final AppHibernationService mService;

    AppHibernationShellCommand(AppHibernationService service) {
        mService = service;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        switch (cmd) {
            case "set-state":
                return runSetState();
            case "get-state":
                return runGetState();
            default:
                return handleDefaultCommands(cmd);
        }
    }

    private int runSetState() {
        int userId = parseUserOption();

        String pkg = getNextArgRequired();
        if (pkg == null) {
            getErrPrintWriter().println("Error: no package specified");
            return ERROR;
        }

        String newStateRaw = getNextArgRequired();
        if (newStateRaw == null) {
            getErrPrintWriter().println("Error: No state to set specified");
            return ERROR;
        }
        boolean newState = Boolean.parseBoolean(newStateRaw);

        mService.setHibernating(pkg, userId, newState);
        return SUCCESS;
    }

    private int runGetState() {
        int userId = parseUserOption();

        String pkg = getNextArgRequired();
        if (pkg == null) {
            getErrPrintWriter().println("Error: No package specified");
            return ERROR;
        }
        boolean isHibernating = mService.isHibernating(pkg, userId);
        final PrintWriter pw = getOutPrintWriter();
        pw.println(isHibernating);
        return SUCCESS;
    }

    private int parseUserOption() {
        String option = getNextOption();
        if (TextUtils.equals(option, USER_OPT)) {
            return UserHandle.parseUserArg(getNextArgRequired());
        }
        return UserHandle.USER_CURRENT;
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.println("App hibernation (app_hibernation) commands: ");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("");
        pw.println("  set-state [--user USER_ID] PACKAGE true|false");
        pw.println("    Sets the hibernation state of the package to value specified");
        pw.println("");
        pw.println("  get-state [--user USER_ID] PACKAGE");
        pw.println("    Gets the hibernation state of the package");
        pw.println("");
    }
}
