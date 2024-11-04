/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.security.advancedprotection;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.os.RemoteException;
import android.os.ShellCommand;

import java.io.PrintWriter;

class AdvancedProtectionShellCommand extends ShellCommand {
    private AdvancedProtectionService mService;

    AdvancedProtectionShellCommand(@NonNull AdvancedProtectionService service) {
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
                case "help":
                    onHelp();
                    return 0;
                case "set-protection-enabled":
                    return setProtectionEnabled();
                case "is-protection-enabled":
                    return isProtectionEnabled(pw);
            }
        } catch (RemoteException e) {
            pw.println("Remote exception: " + e);
        }
        return -1;
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        dumpHelp(pw);
    }

    private void dumpHelp(@NonNull PrintWriter pw) {
        pw.println("Advanced Protection Mode commands:");
        pw.println("  help");
        pw.println("      Print this help text.");
        pw.println("  set-protection-enabled [true|false]");
        pw.println("  is-protection-enabled");
    }

    @SuppressLint("AndroidFrameworkRequiresPermission")
    private int setProtectionEnabled() throws RemoteException {
        String protectionMode = getNextArgRequired();
        mService.setAdvancedProtectionEnabled(Boolean.parseBoolean(protectionMode));
        return 0;
    }

    @SuppressLint("AndroidFrameworkRequiresPermission")
    private int isProtectionEnabled(@NonNull PrintWriter pw) throws RemoteException {
        boolean protectionMode = mService.isAdvancedProtectionEnabled();
        pw.println(protectionMode);
        return 0;
    }
}
