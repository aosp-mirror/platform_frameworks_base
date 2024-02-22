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

package com.android.server.biometrics.sensors.fingerprint;

import android.content.Context;
import android.os.ShellCommand;

import java.io.PrintWriter;

/** Handles shell commands for {@link FingerprintService}. */
public class FingerprintShellCommand extends ShellCommand {

    private final Context mContext;
    private final FingerprintService mService;

    public FingerprintShellCommand(Context context, FingerprintService service) {
        mContext = context;
        mService = service;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            onHelp();
            return 1;
        }

        try {
            switch (cmd) {
                case "help":
                    return doHelp();
                case "sync":
                    return doSync();
                case "fingerdown":
                    return doSimulateVhalFingerDown();
                case "notification":
                    return doNotify();
                default:
                    getOutPrintWriter().println("Unrecognized command: " + cmd);
            }
        } catch (Exception e) {
            getOutPrintWriter().println("Exception: " + e);
        }
        return -1;
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("Fingerprint Service commands:");
        pw.println("  help");
        pw.println("      Print this help text.");
        pw.println("  sync");
        pw.println("      Sync enrollments now (virtualized sensors only).");
        pw.println("  fingerdown");
        pw.println("      Simulate finger down event (virtualized sensors only).");
        pw.println("  notification");
        pw.println("     Sends a Fingerprint re-enrollment notification");
    }

    private int doHelp() {
        onHelp();
        return 0;
    }

    private int doSync() {
        mService.syncEnrollmentsNow();
        return 0;
    }

    private int doSimulateVhalFingerDown() {
        mService.simulateVhalFingerDown();
        return 0;
    }

    private int doNotify() {
        mService.sendFingerprintReEnrollNotification();
        return 0;
    }
}
