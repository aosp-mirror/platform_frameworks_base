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

package com.android.server.contextualsearch;

import android.annotation.NonNull;
import android.os.ShellCommand;

import java.io.PrintWriter;

public class ContextualSearchManagerShellCommand extends ShellCommand {

    private final ContextualSearchManagerService mService;

    ContextualSearchManagerShellCommand(@NonNull ContextualSearchManagerService service) {
        mService = service;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        final PrintWriter pw = getOutPrintWriter();
        switch (cmd) {
            case "set": {
                final String what = getNextArgRequired();
                switch (what) {
                    case "temporary-package": {
                        String packageName = getNextArg();
                        if (packageName == null) {
                            mService.resetTemporaryPackage();
                            pw.println("ContextualSearchManagerService reset.");
                            return 0;
                        }
                        final int duration = Integer.parseInt(getNextArgRequired());
                        mService.setTemporaryPackage(packageName, duration);
                        pw.println("ContextualSearchManagerService temporarily set to "
                                + packageName + " for " + duration + "ms");
                        break;
                    }
                    case "token-duration": {
                        String durationStr = getNextArg();
                        if (durationStr == null) {
                            mService.resetTokenValidDurationMs();
                            pw.println("ContextualSearchManagerService token duration reset.");
                            return 0;
                        }
                        final int durationMs = Integer.parseInt(durationStr);
                        mService.setTokenValidDurationMs(durationMs);
                        pw.println("ContextualSearchManagerService temporarily set token duration"
                                + " to " + durationMs + "ms");
                        break;
                    }
                }
            }
            break;
            default:
                return handleDefaultCommands(cmd);
        }
        return 0;
    }

    @Override
    public void onHelp() {
        try (PrintWriter pw = getOutPrintWriter()) {
            pw.println("ContextualSearchService commands:");
            pw.println("  help");
            pw.println("    Prints this help text.");
            pw.println("");
            pw.println("  set temporary-package [PACKAGE_NAME DURATION]");
            pw.println("    Temporarily (for DURATION ms) changes the Contextual Search "
                    + "implementation.");
            pw.println("    To reset, call without any arguments.");
            pw.println("  set token-duration [DURATION]");
            pw.println("    Changes the Contextual Search token duration to DURATION ms.");
            pw.println("    To reset, call without any arguments.");
            pw.println("");
        }
    }
}
