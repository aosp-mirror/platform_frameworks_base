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
package com.android.server.timezonedetector;

import static android.app.timezonedetector.TimeZoneDetector.SHELL_COMMAND_IS_AUTO_DETECTION_ENABLED;
import static android.app.timezonedetector.TimeZoneDetector.SHELL_COMMAND_IS_GEO_DETECTION_ENABLED;
import static android.app.timezonedetector.TimeZoneDetector.SHELL_COMMAND_IS_GEO_DETECTION_SUPPORTED;
import static android.app.timezonedetector.TimeZoneDetector.SHELL_COMMAND_SET_AUTO_DETECTION_ENABLED;
import static android.app.timezonedetector.TimeZoneDetector.SHELL_COMMAND_SET_GEO_DETECTION_ENABLED;
import static android.app.timezonedetector.TimeZoneDetector.SHELL_COMMAND_SUGGEST_GEO_LOCATION_TIME_ZONE;
import static android.app.timezonedetector.TimeZoneDetector.SHELL_COMMAND_SUGGEST_MANUAL_TIME_ZONE;
import static android.app.timezonedetector.TimeZoneDetector.SHELL_COMMAND_SUGGEST_TELEPHONY_TIME_ZONE;

import android.app.time.TimeZoneConfiguration;
import android.app.timezonedetector.ManualTimeZoneSuggestion;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion;
import android.os.ShellCommand;
import android.os.UserHandle;

import java.io.PrintWriter;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Implements the shell command interface for {@link TimeZoneDetectorService}. */
class TimeZoneDetectorShellCommand extends ShellCommand {

    private final TimeZoneDetectorService mInterface;

    TimeZoneDetectorShellCommand(TimeZoneDetectorService timeZoneDetectorService) {
        mInterface = timeZoneDetectorService;
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
            case SHELL_COMMAND_IS_GEO_DETECTION_SUPPORTED:
                return runIsGeoDetectionSupported();
            case SHELL_COMMAND_IS_GEO_DETECTION_ENABLED:
                return runIsGeoDetectionEnabled();
            case SHELL_COMMAND_SET_GEO_DETECTION_ENABLED:
                return runSetGeoDetectionEnabled();
            case SHELL_COMMAND_SUGGEST_GEO_LOCATION_TIME_ZONE:
                return runSuggestGeolocationTimeZone();
            case SHELL_COMMAND_SUGGEST_MANUAL_TIME_ZONE:
                return runSuggestManualTimeZone();
            case SHELL_COMMAND_SUGGEST_TELEPHONY_TIME_ZONE:
                return runSuggestTelephonyTimeZone();
            default: {
                return handleDefaultCommands(cmd);
            }
        }
    }

    private int runIsAutoDetectionEnabled() {
        final PrintWriter pw = getOutPrintWriter();
        int userId = UserHandle.USER_CURRENT;
        boolean enabled = mInterface.getCapabilitiesAndConfig(userId)
                .getConfiguration()
                .isAutoDetectionEnabled();
        pw.println(enabled);
        return 0;
    }

    private int runIsGeoDetectionSupported() {
        final PrintWriter pw = getOutPrintWriter();
        boolean enabled = mInterface.isGeoTimeZoneDetectionSupported();
        pw.println(enabled);
        return 0;
    }

    private int runIsGeoDetectionEnabled() {
        final PrintWriter pw = getOutPrintWriter();
        int userId = UserHandle.USER_CURRENT;
        boolean enabled = mInterface.getCapabilitiesAndConfig(userId)
                .getConfiguration()
                .isGeoDetectionEnabled();
        pw.println(enabled);
        return 0;
    }

    private int runSetAutoDetectionEnabled() {
        boolean enabled = Boolean.parseBoolean(getNextArgRequired());
        int userId = UserHandle.USER_CURRENT;
        TimeZoneConfiguration configuration = new TimeZoneConfiguration.Builder()
                .setAutoDetectionEnabled(enabled)
                .build();
        return mInterface.updateConfiguration(userId, configuration) ? 0 : 1;
    }

    private int runSetGeoDetectionEnabled() {
        boolean enabled = Boolean.parseBoolean(getNextArgRequired());
        int userId = UserHandle.USER_CURRENT;
        TimeZoneConfiguration configuration = new TimeZoneConfiguration.Builder()
                .setGeoDetectionEnabled(enabled)
                .build();
        return mInterface.updateConfiguration(userId, configuration) ? 0 : 1;
    }

    private int runSuggestGeolocationTimeZone() {
        return runSuggestTimeZone(
                () -> GeolocationTimeZoneSuggestion.parseCommandLineArg(this),
                mInterface::suggestGeolocationTimeZone);
    }

    private int runSuggestManualTimeZone() {
        return runSuggestTimeZone(
                () -> ManualTimeZoneSuggestion.parseCommandLineArg(this),
                mInterface::suggestManualTimeZone);
    }

    private int runSuggestTelephonyTimeZone() {
        return runSuggestTimeZone(
                () -> TelephonyTimeZoneSuggestion.parseCommandLineArg(this),
                mInterface::suggestTelephonyTimeZone);
    }

    private <T> int runSuggestTimeZone(Supplier<T> suggestionParser, Consumer<T> invoker) {
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
            pw.println(e.toString());
            return 1;
        }
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.println("Time Zone Detector (time_zone_detector) commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.printf("  %s\n", SHELL_COMMAND_IS_AUTO_DETECTION_ENABLED);
        pw.println("    Prints true/false according to the automatic tz detection setting");
        pw.printf("  %s true|false\n", SHELL_COMMAND_SET_AUTO_DETECTION_ENABLED);
        pw.println("    Sets the automatic tz detection setting.");
        pw.printf("  %s\n", SHELL_COMMAND_IS_GEO_DETECTION_SUPPORTED);
        pw.println("    Prints true/false according to whether geolocation time zone detection is"
                + " supported on this device");
        pw.printf("  %s\n", SHELL_COMMAND_IS_GEO_DETECTION_ENABLED);
        pw.println("    Prints true/false according to the geolocation tz detection setting");
        pw.printf("  %s true|false\n", SHELL_COMMAND_SET_GEO_DETECTION_ENABLED);
        pw.println("    Sets the geolocation tz detection setting.");
        pw.printf("  %s <geolocation suggestion opts>\n",
                SHELL_COMMAND_SUGGEST_GEO_LOCATION_TIME_ZONE);
        pw.printf("  %s <manual suggestion opts>\n",
                SHELL_COMMAND_SUGGEST_MANUAL_TIME_ZONE);
        pw.printf("  %s <telephony suggestion opts>\n",
                SHELL_COMMAND_SUGGEST_TELEPHONY_TIME_ZONE);
        pw.println();
        GeolocationTimeZoneSuggestion.printCommandLineOpts(pw);
        pw.println();
        ManualTimeZoneSuggestion.printCommandLineOpts(pw);
        pw.println();
        TelephonyTimeZoneSuggestion.printCommandLineOpts(pw);
        pw.println();
    }
}
