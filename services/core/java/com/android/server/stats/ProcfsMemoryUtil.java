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
package com.android.server.stats;

import android.os.FileUtils;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ProcfsMemoryUtil {
    private static final String TAG = "ProcfsMemoryUtil";

    /** Path to procfs status file: /proc/pid/status. */
    private static final String STATUS_FILE_FMT = "/proc/%d/status";

    private static final Pattern RSS_HIGH_WATER_MARK_IN_KILOBYTES =
            Pattern.compile("VmHWM:\\s*(\\d+)\\s*kB");

    private ProcfsMemoryUtil() {}

    /**
     * Reads RSS high-water mark of a process from procfs. Returns value of the VmHWM field in
     * /proc/PID/status in kilobytes or 0 if not available.
     */
    static int readRssHighWaterMarkFromProcfs(int pid) {
        final String statusPath = String.format(Locale.US, STATUS_FILE_FMT, pid);
        return parseVmHWMFromStatus(readFile(statusPath));
    }

    /**
     * Parses RSS high-water mark out from the contents of the /proc/pid/status file in procfs. The
     * returned value is in kilobytes.
     */
    @VisibleForTesting
    static int parseVmHWMFromStatus(String contents) {
        if (contents.isEmpty()) {
            return 0;
        }
        final Matcher matcher = RSS_HIGH_WATER_MARK_IN_KILOBYTES.matcher(contents);
        try {
            return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
        } catch (NumberFormatException e) {
            Slog.e(TAG, "Failed to parse value", e);
            return 0;
        }
    }

    private static String readFile(String path) {
        try {
            final File file = new File(path);
            return FileUtils.readTextFile(file, 0 /* max */, null /* ellipsis */);
        } catch (IOException e) {
            return "";
        }
    }
}
