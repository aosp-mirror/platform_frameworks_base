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

package com.android.server.cloudsearch;

import android.annotation.NonNull;
import android.os.ShellCommand;

import java.io.PrintWriter;

/**
 * The shell command implementation for the CloudSearchManagerService.
 */
public class CloudSearchManagerServiceShellCommand extends ShellCommand {

    private static final String TAG =
            CloudSearchManagerServiceShellCommand.class.getSimpleName();

    private final CloudSearchManagerService mService;

    public CloudSearchManagerServiceShellCommand(@NonNull CloudSearchManagerService service) {
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
                    case "temporary-service": {
                        final int userId = Integer.parseInt(getNextArgRequired());
                        String serviceName = getNextArg();
                        if (serviceName == null) {
                            mService.resetTemporaryService(userId);
                            pw.println("CloudSearchService temporarily reset. ");
                            return 0;
                        }
                        final int duration = Integer.parseInt(getNextArgRequired());
                        String[] services = serviceName.split(";");
                        if (services.length == 0) {
                            return 0;
                        } else {
                            mService.setTemporaryServices(userId, services, duration);
                        }
                        pw.println("CloudSearchService temporarily set to " + serviceName
                                + " for " + duration + "ms");
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
            pw.println("CloudSearchManagerService commands:");
            pw.println("  help");
            pw.println("    Prints this help text.");
            pw.println("");
            pw.println("  set temporary-service USER_ID [COMPONENT_NAME DURATION]");
            pw.println("    Temporarily (for DURATION ms) changes the service implemtation.");
            pw.println("    To reset, call with just the USER_ID argument.");
            pw.println("");
        }
    }
}
