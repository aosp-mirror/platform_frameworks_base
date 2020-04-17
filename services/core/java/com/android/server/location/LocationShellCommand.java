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

package com.android.server.location;

import android.os.BasicShellCommandHandler;
import android.os.UserHandle;

import java.io.PrintWriter;
import java.util.Objects;

/**
 * Interprets and executes 'adb shell cmd location [args]'.
 */
class LocationShellCommand extends BasicShellCommandHandler {

    private final LocationManagerService mService;

    LocationShellCommand(LocationManagerService service) {
        mService = Objects.requireNonNull(service);
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(null);
        }

        switch (cmd) {
            case "set-location-enabled": {
                int userId = parseUserId();
                boolean enabled = Boolean.parseBoolean(getNextArgRequired());
                mService.setLocationEnabledForUser(enabled, userId);
                return 0;
            }
            case "send-extra-command": {
                String provider = getNextArgRequired();
                String command = getNextArgRequired();
                mService.sendExtraCommand(provider, command, null);
                return 0;
            }
            default:
                return handleDefaultCommands(cmd);
        }
    }

    private int parseUserId() {
        final String option = getNextOption();
        if (option != null) {
            if (option.equals("--user")) {
                return UserHandle.parseUserArg(getNextArgRequired());
            } else {
                throw new IllegalArgumentException(
                        "Expected \"--user\" option, but got \"" + option + "\" instead");
            }
        }

        return UserHandle.USER_CURRENT_OR_SELF;
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("Location service commands:");
        pw.println("  help or -h");
        pw.println("    Print this help text.");
        pw.println("  set-location-enabled [--user <USER_ID>] true|false");
        pw.println("    Sets the master location switch enabled state.");
        pw.println("  send-extra-command <PROVIDER> <COMMAND>");
        pw.println("    Sends the given extra command to the given provider.");
    }
}
