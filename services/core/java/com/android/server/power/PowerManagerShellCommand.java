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

package com.android.server.power;

import android.content.Intent;
import android.os.IPowerManager;
import android.os.RemoteException;
import android.os.ShellCommand;

import java.io.PrintWriter;

class PowerManagerShellCommand extends ShellCommand {
    private static final int LOW_POWER_MODE_ON = 1;

    final IPowerManager mInterface;

    PowerManagerShellCommand(IPowerManager service) {
        mInterface = service;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }

        final PrintWriter pw = getOutPrintWriter();
        try {
            switch(cmd) {
                case "set-mode":
                    return runSetMode();
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (RemoteException e) {
            pw.println("Remote exception: " + e);
        }
        return -1;
    }

    private int runSetMode() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        int mode = -1;
        try {
            mode = Integer.parseInt(getNextArgRequired());
        } catch (RuntimeException ex) {
            pw.println("Error: " + ex.toString());
            return -1;
        }
        mInterface.setPowerSaveMode(mode == LOW_POWER_MODE_ON);
        return 0;
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.println("Power manager (power) commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("");
        pw.println("  set-mode MODE");
        pw.println("    sets the power mode of the device to MODE.");
        pw.println("    1 turns low power mode on and 0 turns low power mode off.");
        pw.println();
        Intent.printIntentArgsHelp(pw , "");
    }
}
