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

package com.android.server.supervision;

import android.os.ShellCommand;

import java.io.PrintWriter;

public class SupervisionServiceShellCommand extends ShellCommand {
    private final SupervisionService mService;

    public SupervisionServiceShellCommand(SupervisionService mService) {
        this.mService = mService;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(null);
        }
        final PrintWriter pw = getOutPrintWriter();
        switch (cmd) {
            case "help": return help(pw);
            case "is-enabled": return isEnabled(pw);
            default: return handleDefaultCommands(cmd);
        }
    }

    private int help(PrintWriter pw) {
        pw.println("Supervision service commands:");
        pw.println("  help");
        pw.println("      Prints this help text");
        pw.println("  is-enabled");
        pw.println("      Is supervision enabled");
        return 0;
    }

    private int isEnabled(PrintWriter pw) {
        pw.println(mService.isSupervisionEnabled());
        return 0;
    }

    @Override
    public void onHelp() {
        help(getOutPrintWriter());
    }
}
