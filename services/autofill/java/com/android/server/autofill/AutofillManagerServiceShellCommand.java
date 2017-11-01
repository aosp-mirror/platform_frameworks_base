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

import static com.android.server.autofill.AutofillManagerService.RECEIVER_BUNDLE_EXTRA_SESSIONS;

import android.os.Bundle;
import android.os.ShellCommand;
import android.os.UserHandle;
import android.view.autofill.AutofillManager;

import com.android.internal.os.IResultReceiver;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class AutofillManagerServiceShellCommand extends ShellCommand {

    private final AutofillManagerService mService;

    public AutofillManagerServiceShellCommand(AutofillManagerService service) {
        mService = service;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        final PrintWriter pw = getOutPrintWriter();
        switch (cmd) {
            case "list":
                return requestList(pw);
            case "destroy":
                return requestDestroy(pw);
            case "reset":
                return requestReset();
            case "get":
                return requestGet(pw);
            case "set":
                return requestSet(pw);
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
            pw.println("  get log_level ");
            pw.println("    Gets the Autofill log level (off | debug | verbose).");
            pw.println("");
            pw.println("  get max_partitions");
            pw.println("    Gets the maximum number of partitions per session.");
            pw.println("");
            pw.println("  set log_level [off | debug | verbose]");
            pw.println("    Sets the Autofill log level.");
            pw.println("");
            pw.println("  set max_partitions number");
            pw.println("    Sets the maximum number of partitions per session.");
            pw.println("");
            pw.println("  list sessions [--user USER_ID]");
            pw.println("    List all pending sessions.");
            pw.println("");
            pw.println("  destroy sessions [--user USER_ID]");
            pw.println("    Destroy all pending sessions.");
            pw.println("");
            pw.println("  reset");
            pw.println("    Reset all pending sessions and cached service connections.");
            pw.println("");
        }
    }

    private int requestGet(PrintWriter pw) {
        final String what = getNextArgRequired();
        switch(what) {
            case "log_level":
                return getLogLevel(pw);
            case "max_partitions":
                return getMaxPartitions(pw);
            default:
                pw.println("Invalid set: " + what);
                return -1;
        }
    }

    private int requestSet(PrintWriter pw) {
        final String what = getNextArgRequired();

        switch(what) {
            case "log_level":
                return setLogLevel(pw);
            case "max_partitions":
                return setMaxPartitions();
            default:
                pw.println("Invalid set: " + what);
                return -1;
        }
    }

    private int getLogLevel(PrintWriter pw) {
        final int logLevel = mService.getLogLevel();
        switch (logLevel) {
            case AutofillManager.FLAG_ADD_CLIENT_VERBOSE:
                pw.println("verbose");
                return 0;
            case AutofillManager.FLAG_ADD_CLIENT_DEBUG:
                pw.println("debug");
                return 0;
            case 0:
                pw.println("off");
                return 0;
            default:
                pw.println("unknow (" + logLevel + ")");
                return 0;
        }
    }

    private int setLogLevel(PrintWriter pw) {
        final String logLevel = getNextArgRequired();
        switch (logLevel.toLowerCase()) {
            case "verbose":
                mService.setLogLevel(AutofillManager.FLAG_ADD_CLIENT_VERBOSE);
                return 0;
            case "debug":
                mService.setLogLevel(AutofillManager.FLAG_ADD_CLIENT_DEBUG);
                return 0;
            case "off":
                mService.setLogLevel(0);
                return 0;
            default:
                pw.println("Invalid level: " + logLevel);
                return -1;
        }
    }

    private int getMaxPartitions(PrintWriter pw) {
        pw.println(mService.getMaxPartitions());
        return 0;
    }

    private int setMaxPartitions() {
        mService.setMaxPartitions(Integer.parseInt(getNextArgRequired()));
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

    private boolean isNextArgLogLevel(PrintWriter pw, String cmd) {
        final String type = getNextArgRequired();
        if (!type.equals("log_level")) {
            pw.println("Error: invalid " + cmd + " type: " + type);
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

    private int getUserIdFromArgsOrAllUsers() {
        if ("--user".equals(getNextArg())) {
            return UserHandle.parseUserArg(getNextArgRequired());
        }
        return UserHandle.USER_ALL;
    }
}
