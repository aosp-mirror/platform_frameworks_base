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

package com.android.server.dreams;

import android.annotation.NonNull;
import android.os.Binder;
import android.os.Process;
import android.os.ShellCommand;
import android.text.TextUtils;
import android.util.Slog;

import java.io.PrintWriter;

/**
 * {@link DreamShellCommand} allows accessing dream functionality, including toggling dream state.
 */
public class DreamShellCommand extends ShellCommand {
    private static final boolean DEBUG = true;
    private static final String TAG = "DreamShellCommand";
    private final @NonNull DreamManagerService mService;

    DreamShellCommand(@NonNull DreamManagerService service) {
        mService = service;
    }

    @Override
    public int onCommand(String cmd) {
        if (DEBUG) {
            Slog.d(TAG, "onCommand:" + cmd);
        }

        try {
            switch (cmd) {
                case "start-dreaming":
                    enforceCallerIsRoot();
                    return startDreaming();
                case "stop-dreaming":
                    enforceCallerIsRoot();
                    return stopDreaming();
                default:
                    return super.handleDefaultCommands(cmd);
            }
        } catch (SecurityException e) {
            getOutPrintWriter().println(e);
            return -1;
        }
    }

    private int startDreaming() {
        mService.requestStartDreamFromShell();
        return 0;
    }

    private int stopDreaming() {
        mService.requestStopDreamFromShell();
        return 0;
    }

    private void enforceCallerIsRoot() {
        if (Binder.getCallingUid() != Process.ROOT_UID) {
            throw new SecurityException("Must be root to call Dream shell commands");
        }
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("Dream manager (dreams) commands:");
        pw.println("  help");
        pw.println("      Print this help text.");
        pw.println("  start-dreaming");
        pw.println("      Start the currently configured dream.");
        pw.println("  stop-dreaming");
        pw.println("      Stops any active dream");
    }
}
