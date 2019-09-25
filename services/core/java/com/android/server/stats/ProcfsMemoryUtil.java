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

import android.annotation.Nullable;
import android.os.FileUtils;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ProcfsMemoryUtil {
    private static final String TAG = "ProcfsMemoryUtil";

    private static final Pattern STATUS_MEMORY_STATS =
            Pattern.compile(String.join(
                    ".*",
                    "Uid:\\s*(\\d+)\\s*",
                    "VmHWM:\\s*(\\d+)\\s*kB",
                    "VmRSS:\\s*(\\d+)\\s*kB",
                    "RssAnon:\\s*(\\d+)\\s*kB",
                    "VmSwap:\\s*(\\d+)\\s*kB"), Pattern.DOTALL);

    private ProcfsMemoryUtil() {}

    /**
     * Reads memory stats of a process from procfs. Returns values of the VmHWM, VmRss, AnonRSS,
     * VmSwap fields in /proc/pid/status in kilobytes or null if not available.
     */
    @Nullable
    static MemorySnapshot readMemorySnapshotFromProcfs(int pid) {
        return parseMemorySnapshotFromStatus(readFile("/proc/" + pid + "/status"));
    }

    @VisibleForTesting
    @Nullable
    static MemorySnapshot parseMemorySnapshotFromStatus(String contents) {
        if (contents.isEmpty()) {
            return null;
        }
        try {
            final Matcher matcher = STATUS_MEMORY_STATS.matcher(contents);
            if (matcher.find()) {
                final MemorySnapshot snapshot = new MemorySnapshot();
                snapshot.uid = Integer.parseInt(matcher.group(1));
                snapshot.rssHighWaterMarkInKilobytes = Integer.parseInt(matcher.group(2));
                snapshot.rssInKilobytes = Integer.parseInt(matcher.group(3));
                snapshot.anonRssInKilobytes = Integer.parseInt(matcher.group(4));
                snapshot.swapInKilobytes = Integer.parseInt(matcher.group(5));
                return snapshot;
            }
        } catch (NumberFormatException e) {
            Slog.e(TAG, "Failed to parse value", e);
        }
        return null;
    }

    private static String readFile(String path) {
        try {
            final File file = new File(path);
            return FileUtils.readTextFile(file, 0 /* max */, null /* ellipsis */);
        } catch (IOException e) {
            return "";
        }
    }

    static final class MemorySnapshot {
        public int uid;
        public int rssHighWaterMarkInKilobytes;
        public int rssInKilobytes;
        public int anonRssInKilobytes;
        public int swapInKilobytes;
    }
}
