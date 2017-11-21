/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wm;

import static android.os.Build.IS_USER;

import android.os.ShellCommand;

import java.io.PrintWriter;

/**
 * ShellCommands for WindowManagerService.
 *
 * Use with {@code adb shell cmd window ...}.
 */
public class WindowManagerShellCommand extends ShellCommand {

    private final WindowManagerService mService;

    public WindowManagerShellCommand(WindowManagerService service) {
        mService = service;
    }

    @Override
    public int onCommand(String cmd) {
        switch (cmd) {
            case "tracing":
                return mService.mWindowTracing.onShellCommand(this, getNextArgRequired());
            default:
                return handleDefaultCommands(cmd);
        }
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("Window Manager (window) commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println();
        if (!IS_USER){
            pw.println("  tracing (start | stop)");
            pw.println("    start or stop window tracing");
            pw.println();
        }
    }
}
