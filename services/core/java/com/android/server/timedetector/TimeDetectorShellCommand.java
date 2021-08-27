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

import static android.app.timedetector.TimeDetector.SHELL_COMMAND_IS_AUTO_DETECTION_ENABLED;
import static android.app.timedetector.TimeDetector.SHELL_COMMAND_SERVICE_NAME;
import static android.provider.DeviceConfig.NAMESPACE_SYSTEM_TIME;

import static com.android.server.timedetector.ServerFlags.KEY_TIME_DETECTOR_LOWER_BOUND_MILLIS_OVERRIDE;
import static com.android.server.timedetector.ServerFlags.KEY_TIME_DETECTOR_ORIGIN_PRIORITIES_OVERRIDE;

import android.os.ShellCommand;

import java.io.PrintWriter;

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
            default: {
                return handleDefaultCommands(cmd);
            }
        }
    }

    private int runIsAutoDetectionEnabled() {
        final PrintWriter pw = getOutPrintWriter();
        boolean enabled = mInterface.getCapabilitiesAndConfig()
                .getTimeConfiguration()
                .isAutoDetectionEnabled();
        pw.println(enabled);
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
