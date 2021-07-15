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

import java.io.PrintWriter;

/**
 * Shell command implementation for {@link AppHibernationService}.
 */
final class AppHibernationShellCommand extends ShellCommand {
    private static final String USER_OPT = "--user";
    private static final String GLOBAL_OPT = "--global";
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
        String opt;
        boolean setsGlobal = false;
        int userId = UserHandle.USER_CURRENT;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case USER_OPT:
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;
                case GLOBAL_OPT:
                    setsGlobal = true;
                    break;
                default:
                    getErrPrintWriter().println("Error: Unknown option: " + opt);
            }
        }

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

        if (setsGlobal) {
            mService.setHibernatingGlobally(pkg, newState);
        } else {
            mService.setHibernatingForUser(pkg, userId, newState);
        }
        return SUCCESS;
    }

    private int runGetState() {
        String opt;
        boolean requestsGlobal = false;
        int userId = UserHandle.USER_CURRENT;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case USER_OPT:
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;
                case GLOBAL_OPT:
                    requestsGlobal = true;
                    break;
                default:
                    getErrPrintWriter().println("Error: Unknown option: " + opt);
            }
        }

        String pkg = getNextArgRequired();
        if (pkg == null) {
            getErrPrintWriter().println("Error: No package specified");
            return ERROR;
        }
        boolean isHibernating = requestsGlobal
                ? mService.isHibernatingGlobally(pkg) : mService.isHibernatingForUser(pkg, userId);
        final PrintWriter pw = getOutPrintWriter();
        pw.println(isHibernating);
        return SUCCESS;
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.println("App hibernation (app_hibernation) commands: ");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("");
        pw.println("  set-state [--user USER_ID] [--global] PACKAGE true|false");
        pw.println("    Sets the hibernation state of the package to value specified. Optionally");
        pw.println("    may specify a user id or set global hibernation state.");
        pw.println("");
        pw.println("  get-state [--user USER_ID] [--global] PACKAGE");
        pw.println("    Gets the hibernation state of the package. Optionally may specify a user");
        pw.println("    id or request global hibernation state.");
        pw.println("");
    }
}
