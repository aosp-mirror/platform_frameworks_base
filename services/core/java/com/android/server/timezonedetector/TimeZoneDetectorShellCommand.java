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

import android.app.timezonedetector.ManualTimeZoneSuggestion;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion;
import android.os.ShellCommand;

import java.io.PrintWriter;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Implemented the shell command interface for {@link TimeZoneDetectorService}. */
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
            case "suggest_geo_location_time_zone":
                return runSuggestGeolocationTimeZone();
            case "suggest_manual_time_zone":
                return runSuggestManualTimeZone();
            case "suggest_telephony_time_zone":
                return runSuggestTelephonyTimeZone();
            default: {
                return handleDefaultCommands(cmd);
            }
        }
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
            T suggestion = null;
            String opt;
            while ((opt = getNextArg()) != null) {
                if ("--suggestion".equals(opt)) {
                    suggestion = suggestionParser.get();
                } else {
                    pw.println("Error: Unknown option: " + opt);
                    return 1;
                }
            }
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
        pw.println("  suggest_geolocation_time_zone");
        pw.println("    --suggestion <geolocation suggestion opts>");
        pw.println("  suggest_manual_time_zone");
        pw.println("    --suggestion <manual suggestion opts>");
        pw.println("  suggest_telephony_time_zone");
        pw.println("    --suggestion <telephony suggestion opts>");
        pw.println();
        GeolocationTimeZoneSuggestion.printCommandLineOpts(pw);
        pw.println();
        ManualTimeZoneSuggestion.printCommandLineOpts(pw);
        pw.println();
        TelephonyTimeZoneSuggestion.printCommandLineOpts(pw);
        pw.println();
    }
}
