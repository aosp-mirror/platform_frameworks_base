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
        final int callingUid = Binder.getCallingUid();
        if (callingUid != Process.ROOT_UID) {
            Slog.e(TAG, "Must be root before calling Dream shell commands");
            return -1;
        }

        if (TextUtils.isEmpty(cmd)) {
            return super.handleDefaultCommands(cmd);
        }
        if (DEBUG) {
            Slog.d(TAG, "onCommand:" + cmd);
        }

        switch (cmd) {
            case "start-dreaming":
                return startDreaming();
            case "stop-dreaming":
                return stopDreaming();
            default:
                return super.handleDefaultCommands(cmd);
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
