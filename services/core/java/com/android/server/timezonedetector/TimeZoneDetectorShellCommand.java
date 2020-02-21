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
            case "suggestTelephonyTimeZone":
                return runSuggestTelephonyTimeZone();
            case "suggestManualTimeZone":
                return runSuggestManualTimeZone();
            default: {
                return handleDefaultCommands(cmd);
            }
        }
    }

    private int runSuggestTelephonyTimeZone() {
        final PrintWriter pw = getOutPrintWriter();
        try {
            TelephonyTimeZoneSuggestion suggestion = null;
            String opt;
            while ((opt = getNextArg()) != null) {
                if ("--suggestion".equals(opt)) {
                    suggestion = TelephonyTimeZoneSuggestion.parseCommandLineArg(this);
                } else {
                    pw.println("Error: Unknown option: " + opt);
                    return 1;
                }
            }
            if (suggestion == null) {
                pw.println("Error: suggestion not specified");
                return 1;
            }
            mInterface.suggestTelephonyTimeZone(suggestion);
            pw.println("Suggestion " + suggestion + " injected.");
            return 0;
        } catch (RuntimeException e) {
            pw.println(e.toString());
            return 1;
        }
    }

    private int runSuggestManualTimeZone() {
        final PrintWriter pw = getOutPrintWriter();
        try {
            ManualTimeZoneSuggestion suggestion = null;
            String opt;
            while ((opt = getNextArg()) != null) {
                if ("--suggestion".equals(opt)) {
                    suggestion = ManualTimeZoneSuggestion.parseCommandLineArg(this);
                } else {
                    pw.println("Error: Unknown option: " + opt);
                    return 1;
                }
            }
            if (suggestion == null) {
                pw.println("Error: suggestion not specified");
                return 1;
            }
            mInterface.suggestManualTimeZone(suggestion);
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
        pw.println("  suggestTelephonyTimeZone");
        pw.println("    --suggestion <telephony suggestion opts>");
        pw.println("  suggestManualTimeZone");
        pw.println("    --suggestion <manual suggestion opts>");
        pw.println();
        ManualTimeZoneSuggestion.printCommandLineOpts(pw);
        pw.println();
        TelephonyTimeZoneSuggestion.printCommandLineOpts(pw);
        pw.println();
    }
}
