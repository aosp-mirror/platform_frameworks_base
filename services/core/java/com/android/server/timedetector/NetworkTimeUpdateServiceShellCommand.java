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
import android.util.NtpTrustedTime;

import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Implements the shell command interface for {@link NetworkTimeUpdateService}. */
class NetworkTimeUpdateServiceShellCommand extends ShellCommand {

    /**
     * The name of the service.
     */
    private static final String SHELL_COMMAND_SERVICE_NAME = "network_time_update_service";

    /**
     * A shell command that forces the time signal to be refreshed from the network.
     */
    private static final String SHELL_COMMAND_FORCE_REFRESH = "force_refresh";

    /**
     * A shell command that sets the NTP server config for tests. Config is cleared on reboot or
     * using {@link #SHELL_COMMAND_RESET_SERVER_CONFIG}.
     */
    private static final String SHELL_COMMAND_SET_SERVER_CONFIG = "set_server_config_for_tests";
    private static final String SET_SERVER_CONFIG_SERVER_ARG = "--server";
    private static final String SET_SERVER_CONFIG_TIMEOUT_ARG = "--timeout_millis";

    /**
     * A shell command that resets the NTP server config for tests.
     */
    private static final String SHELL_COMMAND_RESET_SERVER_CONFIG = "reset_server_config_for_tests";

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
            case SHELL_COMMAND_FORCE_REFRESH:
                return runForceRefresh();
            case SHELL_COMMAND_SET_SERVER_CONFIG:
                return runSetServerConfig();
            case SHELL_COMMAND_RESET_SERVER_CONFIG:
                return runResetServerConfig();
            default: {
                return handleDefaultCommands(cmd);
            }
        }
    }

    private int runForceRefresh() {
        boolean success = mNetworkTimeUpdateService.forceRefreshForTests();
        getOutPrintWriter().println(success);
        return 0;
    }

    private int runSetServerConfig() {
        List<URI> serverUris = new ArrayList<>();
        Duration timeout = null;
        String opt;
        while ((opt = getNextArg()) != null) {
            switch (opt) {
                case SET_SERVER_CONFIG_SERVER_ARG: {
                    try {
                        serverUris.add(NtpTrustedTime.parseNtpUriStrict(getNextArgRequired()));
                    } catch (URISyntaxException e) {
                        throw new IllegalArgumentException("Bad NTP server value", e);
                    }
                    break;
                }
                case SET_SERVER_CONFIG_TIMEOUT_ARG: {
                    timeout = Duration.ofMillis(Integer.parseInt(getNextArgRequired()));
                    break;
                }
                default: {
                    throw new IllegalArgumentException("Unknown option: " + opt);
                }
            }
        }

        if (serverUris.isEmpty()) {
            throw new IllegalArgumentException(
                    "Missing required option: --" + SET_SERVER_CONFIG_SERVER_ARG);
        }
        if (timeout == null) {
            throw new IllegalArgumentException(
                    "Missing required option: --" + SET_SERVER_CONFIG_TIMEOUT_ARG);
        }

        NtpTrustedTime.NtpConfig ntpConfig = new NtpTrustedTime.NtpConfig(serverUris, timeout);
        mNetworkTimeUpdateService.setServerConfigForTests(ntpConfig);
        return 0;
    }

    private int runResetServerConfig() {
        mNetworkTimeUpdateService.setServerConfigForTests(null);
        return 0;
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.printf("Network Time Update Service (%s) commands:\n", SHELL_COMMAND_SERVICE_NAME);
        pw.printf("  help\n");
        pw.printf("    Print this help text.\n");
        pw.printf("  %s\n", SHELL_COMMAND_FORCE_REFRESH);
        pw.printf("    Refreshes the latest time. Prints whether it was successful.\n");
        pw.printf("  %s\n", SHELL_COMMAND_SET_SERVER_CONFIG);
        pw.printf("    Sets the NTP server config for tests. The config is not persisted.\n");
        pw.printf("      Options: %s <uri> [%s <additional uris>]+ %s <millis>\n",
                SET_SERVER_CONFIG_SERVER_ARG, SET_SERVER_CONFIG_SERVER_ARG,
                SET_SERVER_CONFIG_TIMEOUT_ARG);
        pw.printf("      NTP server URIs must be in the form \"ntp://hostname\" or"
                + " \"ntp://hostname:port\"\n");
        pw.printf("  %s\n", SHELL_COMMAND_RESET_SERVER_CONFIG);
        pw.printf("    Resets/clears the NTP server config set via %s.\n",
                SHELL_COMMAND_SET_SERVER_CONFIG);
        pw.println();
    }
}
