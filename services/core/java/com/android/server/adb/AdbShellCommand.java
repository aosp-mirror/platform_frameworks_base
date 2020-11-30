/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.adb;

import com.android.modules.utils.BasicShellCommandHandler;

import java.io.PrintWriter;
import java.util.Objects;

/**
 * Interprets and executes 'adb shell cmd adb [args]'.
 */
class AdbShellCommand extends BasicShellCommandHandler {

    private final AdbService mService;

    AdbShellCommand(AdbService service) {
        mService = Objects.requireNonNull(service);
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(null);
        }

        final PrintWriter pw = getOutPrintWriter();
        switch (cmd) {
            case "is-wifi-supported": {
                pw.println(Boolean.toString(mService.isAdbWifiSupported()));
                return 0;
            }
            case "is-wifi-qr-supported": {
                pw.println(Boolean.toString(mService.isAdbWifiQrSupported()));
                return 0;
            }
            default:
                return handleDefaultCommands(cmd);
        }
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("Adb service commands:");
        pw.println("  help or -h");
        pw.println("    Print this help text.");
        pw.println("  is-wifi-supported");
        pw.println("    Returns \"true\" if adb over wifi is supported.");
        pw.println("  is-wifi-qr-supported");
        pw.println("    Returns \"true\" if adb over wifi + QR pairing is supported.");
        pw.println();
    }
}
