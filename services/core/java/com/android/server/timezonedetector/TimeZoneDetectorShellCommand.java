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

import static android.app.timezonedetector.TimeZoneDetector.SHELL_COMMAND_CONFIRM_TIME_ZONE;
import static android.app.timezonedetector.TimeZoneDetector.SHELL_COMMAND_DUMP_METRICS;
import static android.app.timezonedetector.TimeZoneDetector.SHELL_COMMAND_ENABLE_TELEPHONY_FALLBACK;
import static android.app.timezonedetector.TimeZoneDetector.SHELL_COMMAND_GET_TIME_ZONE_STATE;
import static android.app.timezonedetector.TimeZoneDetector.SHELL_COMMAND_HANDLE_LOCATION_ALGORITHM_EVENT;
import static android.app.timezonedetector.TimeZoneDetector.SHELL_COMMAND_IS_AUTO_DETECTION_ENABLED;
import static android.app.timezonedetector.TimeZoneDetector.SHELL_COMMAND_IS_GEO_DETECTION_ENABLED;
import static android.app.timezonedetector.TimeZoneDetector.SHELL_COMMAND_IS_GEO_DETECTION_SUPPORTED;
import static android.app.timezonedetector.TimeZoneDetector.SHELL_COMMAND_IS_TELEPHONY_DETECTION_SUPPORTED;
import static android.app.timezonedetector.TimeZoneDetector.SHELL_COMMAND_SERVICE_NAME;
import static android.app.timezonedetector.TimeZoneDetector.SHELL_COMMAND_SET_AUTO_DETECTION_ENABLED;
import static android.app.timezonedetector.TimeZoneDetector.SHELL_COMMAND_SET_GEO_DETECTION_ENABLED;
import static android.app.timezonedetector.TimeZoneDetector.SHELL_COMMAND_SET_TIME_ZONE_STATE;
import static android.app.timezonedetector.TimeZoneDetector.SHELL_COMMAND_SUGGEST_MANUAL_TIME_ZONE;
import static android.app.timezonedetector.TimeZoneDetector.SHELL_COMMAND_SUGGEST_TELEPHONY_TIME_ZONE;
import static android.provider.DeviceConfig.NAMESPACE_SYSTEM_TIME;

import static com.android.server.timedetector.ServerFlags.KEY_ENHANCED_METRICS_COLLECTION_ENABLED;
import static com.android.server.timedetector.ServerFlags.KEY_LOCATION_TIME_ZONE_DETECTION_FEATURE_SUPPORTED;
import static com.android.server.timedetector.ServerFlags.KEY_LOCATION_TIME_ZONE_DETECTION_RUN_IN_BACKGROUND_ENABLED;
import static com.android.server.timedetector.ServerFlags.KEY_LOCATION_TIME_ZONE_DETECTION_SETTING_ENABLED_DEFAULT;
import static com.android.server.timedetector.ServerFlags.KEY_LOCATION_TIME_ZONE_DETECTION_SETTING_ENABLED_OVERRIDE;
import static com.android.server.timedetector.ServerFlags.KEY_TIME_ZONE_DETECTOR_TELEPHONY_FALLBACK_SUPPORTED;

import android.app.time.LocationTimeZoneManager;
import android.app.time.TimeZoneConfiguration;
import android.app.time.TimeZoneState;
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
            case SHELL_COMMAND_IS_TELEPHONY_DETECTION_SUPPORTED:
                return runIsTelephonyDetectionSupported();
            case SHELL_COMMAND_IS_GEO_DETECTION_SUPPORTED:
                return runIsGeoDetectionSupported();
            case SHELL_COMMAND_IS_GEO_DETECTION_ENABLED:
                return runIsGeoDetectionEnabled();
            case SHELL_COMMAND_SET_GEO_DETECTION_ENABLED:
                return runSetGeoDetectionEnabled();
            case SHELL_COMMAND_HANDLE_LOCATION_ALGORITHM_EVENT:
                return runHandleLocationEvent();
            case SHELL_COMMAND_SUGGEST_MANUAL_TIME_ZONE:
                return runSuggestManualTimeZone();
            case SHELL_COMMAND_SUGGEST_TELEPHONY_TIME_ZONE:
                return runSuggestTelephonyTimeZone();
            case SHELL_COMMAND_ENABLE_TELEPHONY_FALLBACK:
                return runEnableTelephonyFallback();
            case SHELL_COMMAND_GET_TIME_ZONE_STATE:
                return runGetTimeZoneState();
            case SHELL_COMMAND_SET_TIME_ZONE_STATE:
                return runSetTimeZoneState();
            case SHELL_COMMAND_CONFIRM_TIME_ZONE:
                return runConfirmTimeZone();
            case SHELL_COMMAND_DUMP_METRICS:
                return runDumpMetrics();
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

    private int runIsTelephonyDetectionSupported() {
        final PrintWriter pw = getOutPrintWriter();
        boolean enabled = mInterface.isTelephonyTimeZoneDetectionSupported();
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

    private int runHandleLocationEvent() {
        return runSingleArgMethod(
                () -> LocationAlgorithmEvent.parseCommandLineArg(this),
                mInterface::handleLocationAlgorithmEvent);
    }

    private int runSuggestManualTimeZone() {
        return runSingleArgMethod(
                () -> ManualTimeZoneSuggestion.parseCommandLineArg(this),
                mInterface::suggestManualTimeZone);
    }

    private int runSuggestTelephonyTimeZone() {
        return runSingleArgMethod(
                () -> TelephonyTimeZoneSuggestion.parseCommandLineArg(this),
                mInterface::suggestTelephonyTimeZone);
    }

    private <T> int runSingleArgMethod(Supplier<T> argParser, Consumer<T> invoker) {
        final PrintWriter pw = getOutPrintWriter();
        try {
            T arg = argParser.get();
            if (arg == null) {
                pw.println("Error: arg not specified");
                return 1;
            }
            invoker.accept(arg);
            pw.println("Arg " + arg + " injected.");
            return 0;
        } catch (RuntimeException e) {
            pw.println(e);
            return 1;
        }
    }

    private int runEnableTelephonyFallback() {
        mInterface.enableTelephonyFallback("Command line");
        return 0;
    }

    private int runGetTimeZoneState() {
        TimeZoneState timeZoneState = mInterface.getTimeZoneState();
        getOutPrintWriter().println(timeZoneState);
        return 0;
    }

    private int runSetTimeZoneState() {
        TimeZoneState timeZoneState = TimeZoneState.parseCommandLineArgs(this);
        mInterface.setTimeZoneState(timeZoneState);
        return 0;
    }

    private int runConfirmTimeZone() {
        String timeZoneId = parseTimeZoneIdArg(this);
        getOutPrintWriter().println(mInterface.confirmTimeZone(timeZoneId));
        return 0;
    }

    private static String parseTimeZoneIdArg(ShellCommand cmd) {
        String zoneId = null;
        String opt;
        while ((opt = cmd.getNextArg()) != null) {
            switch (opt) {
                case "--zone_id": {
                    zoneId = cmd.getNextArgRequired();
                    break;
                }
                default: {
                    throw new IllegalArgumentException("Unknown option: " + opt);
                }
            }
        }

        if (zoneId == null) {
            throw new IllegalArgumentException("No zoneId specified.");
        }
        return zoneId;
    }

    private int runDumpMetrics() {
        final PrintWriter pw = getOutPrintWriter();
        MetricsTimeZoneDetectorState metricsState = mInterface.generateMetricsState();
        pw.println("MetricsTimeZoneDetectorState:");
        pw.println(metricsState.toString());
        return 0;
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.printf("Time Zone Detector (%s) commands:\n", SHELL_COMMAND_SERVICE_NAME);
        pw.printf("  help\n");
        pw.printf("    Print this help text.\n");
        pw.printf("  %s\n", SHELL_COMMAND_IS_AUTO_DETECTION_ENABLED);
        pw.printf("    Prints true/false according to the automatic time zone detection setting\n");
        pw.printf("  %s true|false\n", SHELL_COMMAND_SET_AUTO_DETECTION_ENABLED);
        pw.printf("    Sets the automatic time zone detection setting.\n");
        pw.printf("  %s\n", SHELL_COMMAND_IS_TELEPHONY_DETECTION_SUPPORTED);
        pw.printf("    Prints true/false according to whether telephony time zone detection is"
                + " supported on this device.\n");
        pw.printf("  %s\n", SHELL_COMMAND_IS_GEO_DETECTION_SUPPORTED);
        pw.printf("    Prints true/false according to whether geolocation time zone detection is"
                + " supported on this device.\n");
        pw.printf("  %s\n", SHELL_COMMAND_IS_GEO_DETECTION_ENABLED);
        pw.printf("    Prints true/false according to the geolocation time zone detection setting."
                + "\n");
        pw.printf("  %s true|false\n", SHELL_COMMAND_SET_GEO_DETECTION_ENABLED);
        pw.printf("    Sets the geolocation time zone detection enabled setting.\n");
        pw.printf("  %s\n", SHELL_COMMAND_ENABLE_TELEPHONY_FALLBACK);
        pw.printf("    Signals that telephony time zone detection fall back can be used if"
                + " geolocation detection is supported and enabled.\n)");
        pw.printf("    This is a temporary state until geolocation detection becomes \"certain\"."
                + "\n");
        pw.printf("    To have an effect this requires that the telephony fallback feature is"
                + " supported on the device, see below for device_config flags.\n");
        pw.printf("  %s <location event opts>\n", SHELL_COMMAND_HANDLE_LOCATION_ALGORITHM_EVENT);
        pw.printf("    Simulates an event from the location time zone detection algorithm.\n");
        pw.printf("  %s <manual suggestion opts>\n", SHELL_COMMAND_SUGGEST_MANUAL_TIME_ZONE);
        pw.printf("    Suggests a time zone as if supplied by a user manually.\n");
        pw.printf("  %s <telephony suggestion opts>\n", SHELL_COMMAND_SUGGEST_TELEPHONY_TIME_ZONE);
        pw.printf("    Simulates a time zone suggestion from the telephony time zone detection"
                + " algorithm.\n");
        pw.printf("  %s\n", SHELL_COMMAND_GET_TIME_ZONE_STATE);
        pw.printf("    Returns the current time zone setting state.\n");
        pw.printf("  %s <time zone state options>\n", SHELL_COMMAND_SET_TIME_ZONE_STATE);
        pw.printf("    Sets the current time zone state for tests.\n");
        pw.printf("  %s <--zone_id Olson ID>\n", SHELL_COMMAND_CONFIRM_TIME_ZONE);
        pw.printf("    Tries to confirms the time zone, raising the confidence.\n");
        pw.printf("  %s\n", SHELL_COMMAND_DUMP_METRICS);
        pw.printf("    Dumps the service metrics to stdout for inspection.\n");
        pw.println();
        LocationAlgorithmEvent.printCommandLineOpts(pw);
        pw.println();
        ManualTimeZoneSuggestion.printCommandLineOpts(pw);
        pw.println();
        TelephonyTimeZoneSuggestion.printCommandLineOpts(pw);
        pw.println();
        TimeZoneState.printCommandLineOpts(pw);
        pw.println();
        pw.printf("This service is also affected by the following device_config flags in the"
                + " %s namespace:\n", NAMESPACE_SYSTEM_TIME);
        pw.printf("  %s\n", KEY_LOCATION_TIME_ZONE_DETECTION_FEATURE_SUPPORTED);
        pw.printf("    Only observed if the geolocation time zone detection feature is enabled in"
                + " config.\n");
        pw.printf("    Set this to false to disable the feature.\n");
        pw.printf("  %s\n", KEY_LOCATION_TIME_ZONE_DETECTION_RUN_IN_BACKGROUND_ENABLED);
        pw.printf("    Runs geolocation time zone detection even when it not enabled by the user."
                + " The result is not used to set the device's time zone [*]\n");
        pw.printf("  %s\n", KEY_LOCATION_TIME_ZONE_DETECTION_SETTING_ENABLED_DEFAULT);
        pw.printf("    Only used if the device does not have an explicit 'geolocation time zone"
                + " detection enabled' setting stored [*].\n");
        pw.printf("    The default is when unset is false.\n");
        pw.printf("  %s\n", KEY_LOCATION_TIME_ZONE_DETECTION_SETTING_ENABLED_OVERRIDE);
        pw.printf("    Used to override the device's 'geolocation time zone detection enabled'"
                + " setting [*].\n");
        pw.printf("  %s\n", KEY_TIME_ZONE_DETECTOR_TELEPHONY_FALLBACK_SUPPORTED);
        pw.printf("    Used to enable / disable support for telephony detection fallback. Also see"
                + " the %s command.\n", SHELL_COMMAND_ENABLE_TELEPHONY_FALLBACK);
        pw.printf("  %s\n", KEY_ENHANCED_METRICS_COLLECTION_ENABLED);
        pw.printf("    Used to increase the detail of metrics collected / reported.\n");
        pw.println();
        pw.printf("[*] To be enabled, the user must still have location = on / auto time zone"
                + " detection = on.\n");
        pw.println();
        pw.printf("See \"adb shell cmd device_config\" for more information on setting flags.\n");
        pw.println();
        pw.printf("Also see \"adb shell cmd %s help\" for lower-level location time zone"
                        + " commands / settings.\n", LocationTimeZoneManager.SERVICE_NAME);
        pw.println();
    }
}
