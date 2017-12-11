/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.statsd.loadtest;

import android.annotation.Nullable;
import android.util.Log;
import java.text.ParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BatteryStatsParser implements PerfParser {

    private static final Pattern LINE_PATTERN =
        Pattern.compile("\\s*\\+*(\\S*)\\s\\(\\d+\\)\\s(\\d\\d\\d)\\s.*");
    private static final Pattern TIME_PATTERN =
        Pattern.compile("(\\d+)?(h)?(\\d+)?(m)?(\\d+)?(s)?(\\d+)?(ms)?");
    private static final String TAG = "loadtest.BatteryStatsParser";

    private boolean mHistoryStarted;
    private boolean mHistoryEnded;

    public BatteryStatsParser() {
    }

    @Override
    @Nullable
    public String parseLine(String line) {
        if (mHistoryEnded) {
            return null;
        }
        if (!mHistoryStarted) {
            if (line.contains("Battery History")) {
                mHistoryStarted = true;
            }
            return null;
        }
        if (line.isEmpty()) {
            mHistoryEnded = true;
            return null;
        }
        Matcher lineMatcher = LINE_PATTERN.matcher(line);
        if (lineMatcher.find() && lineMatcher.group(1) != null && lineMatcher.group(2) != null) {
            if (lineMatcher.group(1).equals("0")) {
                return "0," + lineMatcher.group(2) + "\n";
            } else {
                Matcher timeMatcher = TIME_PATTERN.matcher(lineMatcher.group(1));
                if (timeMatcher.find()) {
                    Long time = getTime(lineMatcher.group(1));
                    if (time != null) {
                        return time + "," + lineMatcher.group(2) + "\n";
                      } else {
                        return null; // bad time
                    }
                } else {
                  return null;  // bad or no time
                }
            }
        }
        return null;
    }

    @Nullable
    private Long getTime(String group) {
        if ("0".equals(group)) {
            return 0L;
        }
        Matcher timeMatcher = TIME_PATTERN.matcher(group);
        if (!timeMatcher.find()) {
            return null;
        }

        // Get rid of "ms".
        String[] matches = group.split("ms", -1);
        if (matches.length > 1) {
            group = matches[0];
        }

        long time = 0L;
        matches = group.split("h");
        if (matches.length > 1) {
            time += Long.parseLong(matches[0]) * 60 * 60 * 1000;  // hours
            group = matches[1];
        }
        matches = group.split("m");
        if (matches.length > 1) {
            time += Long.parseLong(matches[0]) * 60 * 1000;  // minutes
            group = matches[1];
        }
        matches = group.split("s");
        if (matches.length > 1) {
            time += Long.parseLong(matches[0]) * 1000; // seconds
            group = matches[1];
        }

        if (!group.isEmpty()) {
            time += Long.parseLong(group); // milliseconds
        }
        return time;
    }
}
