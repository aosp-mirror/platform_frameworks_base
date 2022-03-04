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

package com.android.server;

import android.annotation.NonNull;
import android.os.ShellCommand;

import java.io.PrintWriter;
import java.util.Objects;

/** Implements the shell command interface for {@link NetworkTimeUpdateService}. */
class NetworkTimeUpdateServiceShellCommand extends ShellCommand {

    /**
     * The name of the service.
     */
    private static final String SHELL_COMMAND_SERVICE_NAME = "network_time_update_service";

    /**
     * A shell command that clears the time signal received from the network.
     */
    private static final String SHELL_COMMAND_CLEAR_TIME = "clear_time";

    /**
     * A shell command that forces the time signal to be refreshed from the network.
     */
    private static final String SHELL_COMMAND_FORCE_REFRESH = "force_refresh";

    @NonNull
    private final NetworkTimeUpdateService mNetworkTimeUpdateService;

    NetworkTimeUpdateServiceShellCommand(NetworkTimeUpdateService networkTimeUpdateService) {
        mNetworkTimeUpdateService = Objects.requireNonNull(networkTimeUpdateService);
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }

        switch (cmd) {
            case SHELL_COMMAND_CLEAR_TIME:
                return runClearTime();
            case SHELL_COMMAND_FORCE_REFRESH:
                return runForceRefresh();
            default: {
                return handleDefaultCommands(cmd);
            }
        }
    }

    private int runClearTime() {
        mNetworkTimeUpdateService.clearTimeForTests();
        return 0;
    }

    private int runForceRefresh() {
        boolean success = mNetworkTimeUpdateService.forceRefreshForTests();
        getOutPrintWriter().println(success);
        return 0;
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.printf("Network Time Update Service (%s) commands:\n", SHELL_COMMAND_SERVICE_NAME);
        pw.printf("  help\n");
        pw.printf("    Print this help text.\n");
        pw.printf("  %s\n", SHELL_COMMAND_CLEAR_TIME);
        pw.printf("    Clears the latest time.\n");
        pw.printf("  %s\n", SHELL_COMMAND_FORCE_REFRESH);
        pw.printf("    Refreshes the latest time. Prints whether it was successful.\n");
        pw.println();
    }
}
