/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.app.timedetector.TimeDetector.SHELL_COMMAND_CLEAR_NETWORK_TIME;
import static android.app.timedetector.TimeDetector.SHELL_COMMAND_CONFIRM_TIME;
import static android.app.timedetector.TimeDetector.SHELL_COMMAND_GET_NETWORK_TIME;
import static android.app.timedetector.TimeDetector.SHELL_COMMAND_GET_TIME_STATE;
import static android.app.timedetector.TimeDetector.SHELL_COMMAND_IS_AUTO_DETECTION_ENABLED;
import static android.app.timedetector.TimeDetector.SHELL_COMMAND_SERVICE_NAME;
import static android.app.timedetector.TimeDetector.SHELL_COMMAND_SET_AUTO_DETECTION_ENABLED;
import static android.app.timedetector.TimeDetector.SHELL_COMMAND_SET_TIME_STATE;
import static android.app.timedetector.TimeDetector.SHELL_COMMAND_SUGGEST_EXTERNAL_TIME;
import static android.app.timedetector.TimeDetector.SHELL_COMMAND_SUGGEST_GNSS_TIME;
import static android.app.timedetector.TimeDetector.SHELL_COMMAND_SUGGEST_MANUAL_TIME;
import static android.app.timedetector.TimeDetector.SHELL_COMMAND_SUGGEST_NETWORK_TIME;
import static android.app.timedetector.TimeDetector.SHELL_COMMAND_SUGGEST_TELEPHONY_TIME;
import static android.provider.DeviceConfig.NAMESPACE_SYSTEM_TIME;

import static com.android.server.timedetector.ServerFlags.KEY_TIME_DETECTOR_LOWER_BOUND_MILLIS_OVERRIDE;
import static com.android.server.timedetector.ServerFlags.KEY_TIME_DETECTOR_ORIGIN_PRIORITIES_OVERRIDE;

import android.app.time.ExternalTimeSuggestion;
import android.app.time.TimeConfiguration;
import android.app.time.TimeState;
import android.app.time.UnixEpochTime;
import android.app.timedetector.ManualTimeSuggestion;
import android.app.timedetector.TelephonyTimeSuggestion;
import android.os.ShellCommand;
import android.os.UserHandle;

import java.io.PrintWriter;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Implements the shell command interface for {@link TimeDetectorService}. */
class TimeDetectorShellCommand extends ShellCommand {

    private final TimeDetectorService mInterface;

    TimeDetectorShellCommand(TimeDetectorService timeDetectorService) {
        mInterface = timeDetectorService;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }

        switch (cmd) {
            case SHELL_COMMAND_IS_AUTO_DETECTION_ENABLED:
                return runIsAutoDetectionEnabled();
            case SHELL_COMMAND_SET_AUTO_DETECTION_ENABLED:
                return runSetAutoDetectionEnabled();
            case SHELL_COMMAND_SUGGEST_MANUAL_TIME:
                return runSuggestManualTime();
            case SHELL_COMMAND_SUGGEST_TELEPHONY_TIME:
                return runSuggestTelephonyTime();
            case SHELL_COMMAND_SUGGEST_NETWORK_TIME:
                return runSuggestNetworkTime();
            case SHELL_COMMAND_GET_NETWORK_TIME:
                return runGetNetworkTime();
            case SHELL_COMMAND_CLEAR_NETWORK_TIME:
                return runClearNetworkTime();
            case SHELL_COMMAND_SUGGEST_GNSS_TIME:
                return runSuggestGnssTime();
            case SHELL_COMMAND_SUGGEST_EXTERNAL_TIME:
                return runSuggestExternalTime();
            case SHELL_COMMAND_GET_TIME_STATE:
                return runGetTimeState();
            case SHELL_COMMAND_SET_TIME_STATE:
                return runSetTimeState();
            case SHELL_COMMAND_CONFIRM_TIME:
                return runConfirmTime();
            default: {
                return handleDefaultCommands(cmd);
            }
        }
    }

    private int runIsAutoDetectionEnabled() {
        final PrintWriter pw = getOutPrintWriter();
        boolean enabled = mInterface.getCapabilitiesAndConfig()
                .getConfiguration()
                .isAutoDetectionEnabled();
        pw.println(enabled);
        return 0;
    }

    private int runSetAutoDetectionEnabled() {
        boolean enabled = Boolean.parseBoolean(getNextArgRequired());
        int userId = UserHandle.USER_CURRENT;
        TimeConfiguration configuration = new TimeConfiguration.Builder()
                .setAutoDetectionEnabled(enabled)
                .build();
        return mInterface.updateConfiguration(userId, configuration) ? 0 : 1;
    }

    private int runSuggestManualTime() {
        return runSuggestTime(
                () -> ManualTimeSuggestion.parseCommandLineArg(this),
                mInterface::suggestManualTime);
    }

    private int runSuggestTelephonyTime() {
        return runSuggestTime(
                () -> TelephonyTimeSuggestion.parseCommandLineArg(this),
                mInterface::suggestTelephonyTime);
    }

    private int runSuggestNetworkTime() {
        return runSuggestTime(
                () -> NetworkTimeSuggestion.parseCommandLineArg(this),
                mInterface::suggestNetworkTime);
    }

    private int runGetNetworkTime() {
        NetworkTimeSuggestion networkTimeSuggestion = mInterface.getLatestNetworkSuggestion();
        final PrintWriter pw = getOutPrintWriter();
        pw.println(networkTimeSuggestion);
        return 0;
    }

    private int runClearNetworkTime() {
        mInterface.clearNetworkTime();
        return 0;
    }

    private int runSuggestGnssTime() {
        return runSuggestTime(
                () -> GnssTimeSuggestion.parseCommandLineArg(this),
                mInterface::suggestGnssTime);
    }

    private int runSuggestExternalTime() {
        return runSuggestTime(
                () -> ExternalTimeSuggestion.parseCommandLineArg(this),
                mInterface::suggestExternalTime);
    }

    private <T> int runSuggestTime(Supplier<T> suggestionParser, Consumer<T> invoker) {
        final PrintWriter pw = getOutPrintWriter();
        try {
            T suggestion = suggestionParser.get();
            if (suggestion == null) {
                pw.println("Error: suggestion not specified");
                return 1;
            }
            invoker.accept(suggestion);
            pw.println("Suggestion " + suggestion + " injected.");
            return 0;
        } catch (RuntimeException e) {
            pw.println(e);
            return 1;
        }
    }

    private int runGetTimeState() {
        TimeState timeState = mInterface.getTimeState();
        getOutPrintWriter().println(timeState);
        return 0;
    }

    private int runSetTimeState() {
        TimeState timeState = TimeState.parseCommandLineArgs(this);
        mInterface.setTimeState(timeState);
        return 0;
    }

    private int runConfirmTime() {
        UnixEpochTime unixEpochTime = UnixEpochTime.parseCommandLineArgs(this);
        getOutPrintWriter().println(mInterface.confirmTime(unixEpochTime));
        return 0;
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.printf("Time Detector (%s) commands:\n", SHELL_COMMAND_SERVICE_NAME);
        pw.printf("  help\n");
        pw.printf("    Print this help text.\n");
        pw.printf("  %s\n", SHELL_COMMAND_IS_AUTO_DETECTION_ENABLED);
        pw.printf("    Prints true/false according to the automatic time detection setting.\n");
        pw.printf("  %s true|false\n", SHELL_COMMAND_SET_AUTO_DETECTION_ENABLED);
        pw.printf("    Sets the automatic time detection setting.\n");
        pw.println();
        pw.printf("  %s <manual suggestion opts>\n", SHELL_COMMAND_SUGGEST_MANUAL_TIME);
        pw.printf("    Suggests a time as if via the \"manual\" origin.\n");
        pw.printf("  %s <telephony suggestion opts>\n", SHELL_COMMAND_SUGGEST_TELEPHONY_TIME);
        pw.printf("    Suggests a time as if via the \"telephony\" origin.\n");
        pw.printf("  %s <network suggestion opts>\n", SHELL_COMMAND_SUGGEST_NETWORK_TIME);
        pw.printf("    Suggests a time as if via the \"network\" origin.\n");
        pw.printf("  %s <gnss suggestion opts>\n", SHELL_COMMAND_SUGGEST_GNSS_TIME);
        pw.printf("    Suggests a time as if via the \"gnss\" origin.\n");
        pw.printf("  %s <external suggestion opts>\n", SHELL_COMMAND_SUGGEST_EXTERNAL_TIME);
        pw.printf("    Suggests a time as if via the \"external\" origin.\n");
        pw.printf("  %s\n", SHELL_COMMAND_GET_TIME_STATE);
        pw.printf("    Returns the current time setting state.\n");
        pw.printf("  %s <time state options>\n", SHELL_COMMAND_SET_TIME_STATE);
        pw.printf("    Sets the current time state for tests.\n");
        pw.printf("  %s <unix epoch time options>\n", SHELL_COMMAND_CONFIRM_TIME);
        pw.printf("    Tries to confirms the time, raising the confidence.\n");
        pw.printf("  %s\n", SHELL_COMMAND_GET_NETWORK_TIME);
        pw.printf("    Prints the network time information held by the detector.\n");
        pw.printf("  %s\n", SHELL_COMMAND_CLEAR_NETWORK_TIME);
        pw.printf("    Clears the network time information held by the detector.\n");
        pw.println();
        ManualTimeSuggestion.printCommandLineOpts(pw);
        pw.println();
        TelephonyTimeSuggestion.printCommandLineOpts(pw);
        pw.println();
        NetworkTimeSuggestion.printCommandLineOpts(pw);
        pw.println();
        GnssTimeSuggestion.printCommandLineOpts(pw);
        pw.println();
        ExternalTimeSuggestion.printCommandLineOpts(pw);
        pw.println();
        TimeState.printCommandLineOpts(pw);
        pw.println();
        UnixEpochTime.printCommandLineOpts(pw);
        pw.println();
        pw.printf("This service is also affected by the following device_config flags in the"
                + " %s namespace:\n", NAMESPACE_SYSTEM_TIME);
        pw.printf("  %s\n", KEY_TIME_DETECTOR_LOWER_BOUND_MILLIS_OVERRIDE);
        pw.printf("    The lower bound used to validate time suggestions when they are received."
                + "\n");
        pw.printf("    Specified in milliseconds since the start of the Unix epoch.\n");
        pw.printf("  %s\n", KEY_TIME_DETECTOR_ORIGIN_PRIORITIES_OVERRIDE);
        pw.printf("    A comma separated list of origins. See TimeDetectorStrategy for details.\n");
        pw.println();
        pw.printf("See \"adb shell cmd device_config\" for more information on setting flags.\n");
        pw.println();
    }
}
