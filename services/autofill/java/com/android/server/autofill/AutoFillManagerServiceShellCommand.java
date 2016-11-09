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
 * limitations under the License.
 */

package com.android.server.autofill;

import android.app.ActivityManager;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.os.UserHandle;
import android.service.autofill.IAutoFillManagerService;

import java.io.PrintWriter;

public final class AutoFillManagerServiceShellCommand extends ShellCommand {

    private final IAutoFillManagerService.Stub mService;

    public AutoFillManagerServiceShellCommand(IAutoFillManagerService.Stub service) {
        mService = service;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        final PrintWriter pw = getOutPrintWriter();
        try {
            switch (cmd) {
                case "start":
                    return runStart(pw);
                case "finish":
                    return runFinish(pw);
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (RemoteException e) {
            pw.println("error: " + e);
        }
        return -1;
    }

    @Override
    public void onHelp() {
        try (final PrintWriter pw = getOutPrintWriter();) {
            pw.println("AutoFill Service (autofill) commands:");
            pw.println("  help");
            pw.println("    Prints this help text.");
            pw.println("");
            pw.println("  start session [--user USER_ID]");
            pw.println("    Starts an auto-fill session. "
                    + "Prints 'token:SESSION_TOKEN if successful, or error message");
            pw.println("");
            pw.println("  finish session <TOKEN> [--user USER_ID]");
            pw.println("    Finishes a session with the given TOKEN. "
                    + "Prints empty string if successful, or error message.");
            pw.println("");
        }
    }

    private int runStart(PrintWriter pw) throws RemoteException {
        final String type = getNextArg();
        if (type == null) {
            pw.println("Error: didn't specify type of data to start");
            return -1;
        }
        switch (type) {
            case "session":
                return startAutoFillSession(pw);
        }
        pw.println("Error: unknown start type '" + type + "'");
        return -1;
    }

    private int runFinish(PrintWriter pw) throws RemoteException {
        final String type = getNextArg();
        if (type == null) {
            pw.println("Error: didn't specify type of data to finish");
            return -1;
        }
        switch (type) {
            case "session":
                return finishAutoFillSession(pw);
        }
        pw.println("Error: unknown finish type '" + type + "'");
        return -1;
    }

    private int startAutoFillSession(PrintWriter pw) throws RemoteException {
        final int userId = getUserIdFromArgs();
        final String token = mService.startSession(userId, null, 0, null);
        pw.print("token:"); pw.println(token);
        return 0;
    }

    private int finishAutoFillSession(PrintWriter pw) throws RemoteException {
        final String token = getNextArgRequired();
        final int userId = getUserIdFromArgs();

        boolean finished = mService.finishSession(userId, token);
        if (!finished) {
            pw.println("No such session");
            return 1;
        }
        return 0;
    }

    private int getUserIdFromArgs() {
        if ("--user".equals(getNextArg())) {
            return UserHandle.parseUserArg(getNextArgRequired());
        }
        return ActivityManager.getCurrentUser();
    }
}
