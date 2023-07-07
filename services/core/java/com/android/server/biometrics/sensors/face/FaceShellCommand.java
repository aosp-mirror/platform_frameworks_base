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

package com.android.server.biometrics.sensors.face;

import android.os.ShellCommand;

import java.io.PrintWriter;

/** Handles shell commands for {@link FaceService}. */
public class FaceShellCommand extends ShellCommand {

    private final FaceService mService;

    public FaceShellCommand(FaceService service) {
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
        pw.println("Face Service commands:");
        pw.println("  help");
        pw.println("      Print this help text.");
        pw.println("  sync");
        pw.println("      Sync enrollments now (virtualized sensors only).");
    }

    private int doHelp() {
        onHelp();
        return 0;
    }

    private int doSync() {
        mService.syncEnrollmentsNow();
        return 0;
    }
}
