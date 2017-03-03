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

import static com.android.server.autofill.AutoFillManagerService.RECEIVER_BUNDLE_EXTRA_SESSIONS;

import android.app.ActivityManager;
import android.os.Bundle;
import android.os.ShellCommand;
import android.os.UserHandle;

import com.android.internal.os.IResultReceiver;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class AutoFillManagerServiceShellCommand extends ShellCommand {

    private final AutoFillManagerService mService;

    public AutoFillManagerServiceShellCommand(AutoFillManagerService service) {
        mService = service;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        final PrintWriter pw = getOutPrintWriter();
        switch (cmd) {
            case "save":
                return requestSave();
            case "set":
                return requestSet();
            case "list":
                return requestList(pw);
            case "destroy":
                return requestDestroy(pw);
            case "reset":
                return requestReset();
            default:
                return handleDefaultCommands(cmd);
        }
    }

    @Override
    public void onHelp() {
        try (final PrintWriter pw = getOutPrintWriter();) {
            pw.println("AutoFill Service (autofill) commands:");
            pw.println("  help");
            pw.println("    Prints this help text.");
            pw.println("");
            pw.println("  list sessions [--user USER_ID]");
            pw.println("    List all pending sessions.");
            pw.println("");
            pw.println("  destroy sessions [--user USER_ID]");
            pw.println("    Destroy all pending sessions.");
            pw.println("");
            pw.println("  save [--user USER_ID]");
            pw.println("    Request provider to save contents of the top activity.");
            pw.println("");
            pw.println("  set save_timeout MS");
            pw.println("    Sets how long (in ms) the save snack bar is shown.");
            pw.println("");
            pw.println("  reset");
            pw.println("    Reset all pending sessions and cached service connections.");
            pw.println("");
        }
    }

    private int requestSave() {
        final int userId = getUserIdFromArgsOrCurrentUser();
        mService.requestSaveForUser(userId);
        return 0;
    }

    private int requestSet() {
        final String type = getNextArgRequired();
        switch (type) {
            case "save_timeout":
                mService.setSaveTimeout(Integer.parseInt(getNextArgRequired()));
                break;
            default:
                throw new IllegalArgumentException("Invalid 'set' type: " + type);
        }
        return 0;
    }

    private int requestDestroy(PrintWriter pw) {
        if (!isNextArgSessions(pw)) {
            return -1;
        }

        final int userId = getUserIdFromArgsOrAllUsers();
        final CountDownLatch latch = new CountDownLatch(1);
        final IResultReceiver receiver = new IResultReceiver.Stub() {
            @Override
            public void send(int resultCode, Bundle resultData) {
                latch.countDown();
            }
        };
        return requestSessionCommon(pw, latch, () -> mService.destroySessions(userId, receiver));
    }

    private int requestList(PrintWriter pw) {
        if (!isNextArgSessions(pw)) {
            return -1;
        }

        final int userId = getUserIdFromArgsOrAllUsers();
        final CountDownLatch latch = new CountDownLatch(1);
        final IResultReceiver receiver = new IResultReceiver.Stub() {
            @Override
            public void send(int resultCode, Bundle resultData) {
                final ArrayList<String> sessions = resultData
                        .getStringArrayList(RECEIVER_BUNDLE_EXTRA_SESSIONS);
                for (String session : sessions) {
                    pw.println(session);
                }
                latch.countDown();
            }
        };
        return requestSessionCommon(pw, latch, () -> mService.listSessions(userId, receiver));
    }

    private boolean isNextArgSessions(PrintWriter pw) {
        final String type = getNextArgRequired();
        if (!type.equals("sessions")) {
            pw.println("Error: invalid list type");
            return false;
        }
        return true;
    }

    private int requestSessionCommon(PrintWriter pw, CountDownLatch latch,
            Runnable command) {
        command.run();

        try {
            final boolean received = latch.await(5, TimeUnit.SECONDS);
            if (!received) {
                pw.println("Timed out after 5 seconds");
                return -1;
            }
        } catch (InterruptedException e) {
            pw.println("System call interrupted");
            Thread.currentThread().interrupt();
            return -1;
        }
        return 0;
    }

    private int requestReset() {
        mService.reset();
        return 0;
    }

    private int getUserIdFromArgsOrCurrentUser() {
        if ("--user".equals(getNextArg())) {
            return UserHandle.parseUserArg(getNextArgRequired());
        }
        return ActivityManager.getCurrentUser();
    }

    private int getUserIdFromArgsOrAllUsers() {
        if ("--user".equals(getNextArg())) {
            return UserHandle.parseUserArg(getNextArgRequired());
        }
        return UserHandle.USER_ALL;
    }
}
