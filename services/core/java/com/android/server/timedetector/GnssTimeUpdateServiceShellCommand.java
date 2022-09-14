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

package com.android.server.timedetector;

import android.annotation.NonNull;
import android.os.ShellCommand;

import java.io.PrintWriter;
import java.util.Objects;

/** Implements the shell command interface for {@link GnssTimeUpdateService}. */
class GnssTimeUpdateServiceShellCommand extends ShellCommand {

    /**
     * The name of the service.
     */
    private static final String SHELL_COMMAND_SERVICE_NAME = "gnss_time_update_service";

    /**
     * A shell command that forces the service in to GNSS listening mode if it isn't already.
     */
    private static final String SHELL_COMMAND_START_GNSS_LISTENING = "start_gnss_listening";

    @NonNull
    private final GnssTimeUpdateService mGnssTimeUpdateService;

    GnssTimeUpdateServiceShellCommand(GnssTimeUpdateService gnssTimeUpdateService) {
        mGnssTimeUpdateService = Objects.requireNonNull(gnssTimeUpdateService);
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }

        switch (cmd) {
            case SHELL_COMMAND_START_GNSS_LISTENING:
                return runStartGnssListening();
            default: {
                return handleDefaultCommands(cmd);
            }
        }
    }

    private int runStartGnssListening() {
        boolean success = mGnssTimeUpdateService.startGnssListening();
        getOutPrintWriter().println(success);
        return 0;
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.printf("Network Time Update Service (%s) commands:\n", SHELL_COMMAND_SERVICE_NAME);
        pw.printf("  help\n");
        pw.printf("    Print this help text.\n");
        pw.printf("  %s\n", SHELL_COMMAND_START_GNSS_LISTENING);
        pw.printf("    Forces the service in to GNSS listening mode (if it isn't already).\n");
        pw.printf("    Prints true if the service is listening after this command.\n");
        pw.println();
    }
}
