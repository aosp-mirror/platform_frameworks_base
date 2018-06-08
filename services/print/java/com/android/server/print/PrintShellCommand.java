/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.print;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.os.UserHandle;
import android.print.IPrintManager;

import java.io.PrintWriter;

/**
 * Shell command implementation for the print manager service
 */
final class PrintShellCommand extends ShellCommand {
    final @NonNull IPrintManager mService;

    PrintShellCommand(@NonNull IPrintManager service) {
        mService = service;
    }

    @Override
    public int onCommand(@Nullable String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        switch (cmd) {
            case "get-bind-instant-service-allowed": {
                return runGetBindInstantServiceAllowed();
            }
            case "set-bind-instant-service-allowed": {
                return runSetBindInstantServiceAllowed();
            }
        }
        return -1;
    }

    private int runGetBindInstantServiceAllowed() {
        final Integer userId = parseUserId();
        if (userId == null) {
            return -1;
        }
        try {
            getOutPrintWriter().println(
                    Boolean.toString(mService.getBindInstantServiceAllowed(userId)));
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return 0;
    }

    private int runSetBindInstantServiceAllowed() {
        final Integer userId = parseUserId();
        if (userId == null) {
            return -1;
        }
        final String allowed = getNextArgRequired();
        if (allowed == null) {
            getErrPrintWriter().println("Error: no true/false specified");
            return -1;
        }
        try {
            mService.setBindInstantServiceAllowed(userId, Boolean.parseBoolean(allowed));
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return 0;
    }

    private @Nullable Integer parseUserId() {
        final String option = getNextOption();
        if (option != null) {
            if (option.equals("--user")) {
                return UserHandle.parseUserArg(getNextArgRequired());
            } else {
                getErrPrintWriter().println("Unknown option: " + option);
                return null;
            }
        }
        return UserHandle.USER_SYSTEM;
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("Print service commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("  set-bind-instant-service-allowed [--user <USER_ID>] true|false ");
        pw.println("    Set whether binding to print services provided by instant apps is "
                + "allowed.");
        pw.println("  get-bind-instant-service-allowed [--user <USER_ID>]");
        pw.println("    Get whether binding to print services provided by instant apps is "
                + "allowed.");
    }
}
