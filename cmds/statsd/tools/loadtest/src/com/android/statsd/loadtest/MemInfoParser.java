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
import android.os.SystemClock;
import android.util.Log;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses PSS info from dumpsys meminfo */
public class MemInfoParser implements PerfParser {

    private static final Pattern LINE_PATTERN =
        Pattern.compile("\\s*(\\d*,*\\d*)K:\\s(\\S*)\\s\\.*");
    private static final String PSS_BY_PROCESS = "Total PSS by process:";
    private static final String TAG = "loadtest.MemInfoParser";

    private boolean mPssStarted;
    private boolean mPssEnded;
    private final long mStartTimeMillis;

    public MemInfoParser(long startTimeMillis) {
        mStartTimeMillis = startTimeMillis;
    }

    @Override
    @Nullable
    public String parseLine(String line) {
        if (mPssEnded) {
            return null;
        }
        if (!mPssStarted) {
            if (line.contains(PSS_BY_PROCESS)) {
                mPssStarted = true;
            }
            return null;
        }
        if (line.isEmpty()) {
            mPssEnded = true;
            return null;
        }
        Matcher lineMatcher = LINE_PATTERN.matcher(line);
        if (lineMatcher.find() && lineMatcher.group(1) != null && lineMatcher.group(2) != null) {
            if (lineMatcher.group(2).equals("statsd")) {
                long timeDeltaMillis = SystemClock.elapsedRealtime() - mStartTimeMillis;
                return timeDeltaMillis + "," + convertToPss(lineMatcher.group(1));
            }
        }
        return null;
    }

    private String convertToPss(String input) {
        return input.replace(",", "");
    }
}
