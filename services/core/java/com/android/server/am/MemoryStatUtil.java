/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.am;

import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_METRICS;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.annotation.Nullable;
import android.os.FileUtils;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static utility methods related to {@link MemoryStat}.
 */
final class MemoryStatUtil {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "MemoryStatUtil" : TAG_AM;

    /** Path to check if device has memcg */
    private static final String MEMCG_TEST_PATH = "/dev/memcg/apps/memory.stat";
    /** Path to memory stat file for logging app start memory state */
    private static final String MEMORY_STAT_FILE_FMT = "/dev/memcg/apps/uid_%d/pid_%d/memory.stat";
    /** Path to procfs stat file for logging app start memory state */
    private static final String PROC_STAT_FILE_FMT = "/proc/%d/stat";

    private static final Pattern PGFAULT = Pattern.compile("total_pgfault (\\d+)");
    private static final Pattern PGMAJFAULT = Pattern.compile("total_pgmajfault (\\d+)");
    private static final Pattern RSS_IN_BYTES = Pattern.compile("total_rss (\\d+)");
    private static final Pattern CACHE_IN_BYTES = Pattern.compile("total_cache (\\d+)");
    private static final Pattern SWAP_IN_BYTES = Pattern.compile("total_swap (\\d+)");

    private static final int PGFAULT_INDEX = 9;
    private static final int PGMAJFAULT_INDEX = 11;
    private static final int RSS_IN_BYTES_INDEX = 23;

    /** True if device has memcg */
    private static volatile Boolean sDeviceHasMemCg;

    private MemoryStatUtil() {}

    /**
     * Reads memory stat for a process.
     *
     * Reads from memcg if available on device, else fallback to procfs.
     * Returns null if no stats can be read.
     */
    @Nullable
    static MemoryStat readMemoryStatFromFilesystem(int uid, int pid) {
        return hasMemcg() ? readMemoryStatFromMemcg(uid, pid) : readMemoryStatFromProcfs(pid);
    }

    /**
     * Reads memory.stat of a process from memcg.
     *
     * Returns null if file is not found in memcg or if file has unrecognized contents.
     */
    @Nullable
    static MemoryStat readMemoryStatFromMemcg(int uid, int pid) {
        final String path = String.format(Locale.US, MEMORY_STAT_FILE_FMT, uid, pid);
        return parseMemoryStatFromMemcg(readFileContents(path));
    }

    /**
     * Reads memory stat of a process from procfs.
     *
     * Returns null if file is not found in procfs or if file has unrecognized contents.
     */
    @Nullable
    static MemoryStat readMemoryStatFromProcfs(int pid) {
        final String path = String.format(Locale.US, PROC_STAT_FILE_FMT, pid);
        return parseMemoryStatFromProcfs(readFileContents(path));
    }

    private static String readFileContents(String path) {
        final File file = new File(path);
        if (!file.exists()) {
            if (DEBUG_METRICS) Slog.i(TAG, path + " not found");
            return null;
        }

        try {
            return FileUtils.readTextFile(file, 0 /* max */, null /* ellipsis */);
        } catch (IOException e) {
            Slog.e(TAG, "Failed to read file:", e);
            return null;
        }
    }

    /**
     * Parses relevant statistics out from the contents of a memory.stat file in memcg.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    @Nullable
    static MemoryStat parseMemoryStatFromMemcg(String memoryStatContents) {
        if (memoryStatContents == null || memoryStatContents.isEmpty()) {
            return null;
        }

        final MemoryStat memoryStat = new MemoryStat();
        Matcher m;
        m = PGFAULT.matcher(memoryStatContents);
        memoryStat.pgfault = m.find() ? Long.valueOf(m.group(1)) : 0;
        m = PGMAJFAULT.matcher(memoryStatContents);
        memoryStat.pgmajfault = m.find() ? Long.valueOf(m.group(1)) : 0;
        m = RSS_IN_BYTES.matcher(memoryStatContents);
        memoryStat.rssInBytes = m.find() ? Long.valueOf(m.group(1)) : 0;
        m = CACHE_IN_BYTES.matcher(memoryStatContents);
        memoryStat.cacheInBytes = m.find() ? Long.valueOf(m.group(1)) : 0;
        m = SWAP_IN_BYTES.matcher(memoryStatContents);
        memoryStat.swapInBytes = m.find() ? Long.valueOf(m.group(1)) : 0;
        return memoryStat;
    }

    /**
     * Parses relevant statistics out from the contents of a /proc/pid/stat file in procfs.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    @Nullable
    static MemoryStat parseMemoryStatFromProcfs(String procStatContents) {
        if (procStatContents == null || procStatContents.isEmpty()) {
            return null;
        }

        final String[] splits = procStatContents.split(" ");
        if (splits.length < 24) {
            return null;
        }

        final MemoryStat memoryStat = new MemoryStat();
        memoryStat.pgfault = Long.valueOf(splits[PGFAULT_INDEX]);
        memoryStat.pgmajfault = Long.valueOf(splits[PGMAJFAULT_INDEX]);
        memoryStat.rssInBytes = Long.valueOf(splits[RSS_IN_BYTES_INDEX]);
        return memoryStat;
    }

    /**
     * Checks if memcg is available on device.
     *
     * Touches the filesystem to do the check.
     */
    static boolean hasMemcg() {
        if (sDeviceHasMemCg == null) {
            sDeviceHasMemCg = (new File(MEMCG_TEST_PATH)).exists();
        }
        return sDeviceHasMemCg;
    }

    static final class MemoryStat {
        /** Number of page faults */
        long pgfault;
        /** Number of major page faults */
        long pgmajfault;
        /** Number of bytes of anonymous and swap cache memory */
        long rssInBytes;
        /** Number of bytes of page cache memory */
        long cacheInBytes;
        /** Number of bytes of swap usage */
        long swapInBytes;
    }
}
