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

import static android.service.autofill.AutofillFieldClassificationService.EXTRA_SCORES;
import static android.service.autofill.AutofillService.EXTRA_RESULT;

import static com.android.server.autofill.AutofillManagerService.RECEIVER_BUNDLE_EXTRA_SESSIONS;

import android.os.Bundle;
import android.os.RemoteCallback;
import android.os.ShellCommand;
import android.os.UserHandle;
import android.service.autofill.AutofillFieldClassificationService.Scores;
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
            pw.println("  get max_visible_datasets");
            pw.println("    Gets the maximum number of visible datasets in the UI.");
            pw.println("");
            pw.println("  get full_screen_mode");
            pw.println("    Gets the Fill UI full screen mode");
            pw.println("");
            pw.println("  get fc_score [--algorithm ALGORITHM] value1 value2");
            pw.println("    Gets the field classification score for 2 fields.");
            pw.println("");
            pw.println("  get bind-instant-service-allowed");
            pw.println("    Gets whether binding to services provided by instant apps is allowed");
            pw.println("");
            pw.println("  get saved-password-count");
            pw.println("    Gets the number of saved passwords in the current service.");
            pw.println("");
            pw.println("  set log_level [off | debug | verbose]");
            pw.println("    Sets the Autofill log level.");
            pw.println("");
            pw.println("  set max_partitions number");
            pw.println("    Sets the maximum number of partitions per session.");
            pw.println("");
            pw.println("  set max_visible_datasets number");
            pw.println("    Sets the maximum number of visible datasets in the UI.");
            pw.println("");
            pw.println("  set full_screen_mode [true | false | default]");
            pw.println("    Sets the Fill UI full screen mode");
            pw.println("");
            pw.println("  set bind-instant-service-allowed [true | false]");
            pw.println("    Sets whether binding to services provided by instant apps is allowed");
            pw.println("");
            pw.println("  set temporary-augmented-service USER_ID [COMPONENT_NAME DURATION]");
            pw.println("    Temporarily (for DURATION ms) changes the augmented autofill service "
                    + "implementation.");
            pw.println("    To reset, call with just the USER_ID argument.");
            pw.println("");
            pw.println("  set default-augmented-service-enabled USER_ID [true|false]");
            pw.println("    Enable / disable the default augmented autofill service for the user.");
            pw.println("");
            pw.println("  get default-augmented-service-enabled USER_ID");
            pw.println("    Checks whether the default augmented autofill service is enabled for "
                    + "the user.");
            pw.println("");
            pw.println("  list sessions [--user USER_ID]");
            pw.println("    Lists all pending sessions.");
            pw.println("");
            pw.println("  destroy sessions [--user USER_ID]");
            pw.println("    Destroys all pending sessions.");
            pw.println("");
            pw.println("  reset");
            pw.println("    Resets all pending sessions and cached service connections.");
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
            case "max_visible_datasets":
                return getMaxVisibileDatasets(pw);
            case "fc_score":
                return getFieldClassificationScore(pw);
            case "full_screen_mode":
                return getFullScreenMode(pw);
            case "bind-instant-service-allowed":
                return getBindInstantService(pw);
            case "default-augmented-service-enabled":
                return getDefaultAugmentedServiceEnabled(pw);
            case "saved-password-count":
                return getSavedPasswordCount(pw);
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
            case "max_visible_datasets":
                return setMaxVisibileDatasets();
            case "full_screen_mode":
                return setFullScreenMode(pw);
            case "bind-instant-service-allowed":
                return setBindInstantService(pw);
            case "temporary-augmented-service":
                return setTemporaryAugmentedService(pw);
            case "default-augmented-service-enabled":
                return setDefaultAugmentedServiceEnabled(pw);
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
                mService.setLogLevel(AutofillManager.NO_LOGGING);
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

    private int getMaxVisibileDatasets(PrintWriter pw) {
        pw.println(mService.getMaxVisibleDatasets());
        return 0;
    }

    private int setMaxVisibileDatasets() {
        mService.setMaxVisibleDatasets(Integer.parseInt(getNextArgRequired()));
        return 0;
    }

    private int getFieldClassificationScore(PrintWriter pw) {
        final String nextArg = getNextArgRequired();
        final String algorithm, value1;
        if ("--algorithm".equals(nextArg)) {
            algorithm = getNextArgRequired();
            value1 = getNextArgRequired();
        } else {
            algorithm = null;
            value1 = nextArg;
        }
        final String value2 = getNextArgRequired();

        final CountDownLatch latch = new CountDownLatch(1);
        mService.calculateScore(algorithm, value1, value2, new RemoteCallback((result) -> {
            final Scores scores = result.getParcelable(EXTRA_SCORES);
            if (scores == null) {
                pw.println("no score");
            } else {
                pw.println(scores.scores[0][0]);
            }
            latch.countDown();
        }));

        return waitForLatch(pw, latch);
    }

    private int getFullScreenMode(PrintWriter pw) {
        final Boolean mode = mService.getFullScreenMode();
        if (mode == null) {
            pw.println("default");
        } else if (mode) {
            pw.println("true");
        } else {
            pw.println("false");
        }
        return 0;
    }

    private int setFullScreenMode(PrintWriter pw) {
        final String mode = getNextArgRequired();
        switch (mode.toLowerCase()) {
            case "true":
                mService.setFullScreenMode(Boolean.TRUE);
                return 0;
            case "false":
                mService.setFullScreenMode(Boolean.FALSE);
                return 0;
            case "default":
                mService.setFullScreenMode(null);
                return 0;
            default:
                pw.println("Invalid mode: " + mode);
                return -1;
        }
    }

    private int getBindInstantService(PrintWriter pw) {
        if (mService.getAllowInstantService()) {
            pw.println("true");
        } else {
            pw.println("false");
        }
        return 0;
    }

    private int setBindInstantService(PrintWriter pw) {
        final String mode = getNextArgRequired();
        switch (mode.toLowerCase()) {
            case "true":
                mService.setAllowInstantService(true);
                return 0;
            case "false":
                mService.setAllowInstantService(false);
                return 0;
            default:
                pw.println("Invalid mode: " + mode);
                return -1;
        }
    }

    private int setTemporaryAugmentedService(PrintWriter pw) {
        final int userId = getNextIntArgRequired();
        final String serviceName = getNextArg();
        if (serviceName == null) {
            mService.resetTemporaryAugmentedAutofillService(userId);
            return 0;
        }
        final int duration = getNextIntArgRequired();
        mService.setTemporaryAugmentedAutofillService(userId, serviceName, duration);
        pw.println("AugmentedAutofillService temporarily set to " + serviceName + " for "
                + duration + "ms");
        return 0;
    }

    private int getDefaultAugmentedServiceEnabled(PrintWriter pw) {
        final int userId = getNextIntArgRequired();
        final boolean enabled = mService.isDefaultAugmentedServiceEnabled(userId);
        pw.println(enabled);
        return 0;
    }

    private int setDefaultAugmentedServiceEnabled(PrintWriter pw) {
        final int userId = getNextIntArgRequired();
        final boolean enabled = Boolean.parseBoolean(getNextArgRequired());
        final boolean changed = mService.setDefaultAugmentedServiceEnabled(userId, enabled);
        if (!changed) {
            pw.println("already " + enabled);
        }
        return 0;
    }

    private int getSavedPasswordCount(PrintWriter pw) {
        final int userId = getNextIntArgRequired();
        CountDownLatch latch = new CountDownLatch(1);
        IResultReceiver resultReceiver = new IResultReceiver.Stub() {
            @Override
            public void send(int resultCode, Bundle resultData) {
                pw.println("resultCode=" + resultCode);
                if (resultCode == 0 && resultData != null) {
                    pw.println("value=" + resultData.getInt(EXTRA_RESULT));
                }
                latch.countDown();
            }
        };
        if (mService.requestSavedPasswordCount(userId, resultReceiver)) {
            waitForLatch(pw, latch);
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
        return requestSessionCommon(pw, latch, () -> mService.removeAllSessions(userId, receiver));
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
        return waitForLatch(pw, latch);
    }

    private int waitForLatch(PrintWriter pw, CountDownLatch latch) {
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

    private int getNextIntArgRequired() {
        return Integer.parseInt(getNextArgRequired());
    }
}
