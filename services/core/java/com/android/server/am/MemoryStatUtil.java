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

    /** Path to memory stat file for logging app start memory state */
    private static final String MEMORY_STAT_FILE_FMT = "/dev/memcg/apps/uid_%d/pid_%d/memory.stat";

    private static final Pattern PGFAULT = Pattern.compile("total_pgfault (\\d+)");
    private static final Pattern PGMAJFAULT = Pattern.compile("total_pgmajfault (\\d+)");
    private static final Pattern RSS_IN_BYTES = Pattern.compile("total_rss (\\d+)");
    private static final Pattern CACHE_IN_BYTES = Pattern.compile("total_cache (\\d+)");
    private static final Pattern SWAP_IN_BYTES = Pattern.compile("total_swap (\\d+)");

    private MemoryStatUtil() {}

    /**
     * Reads memory.stat of a process from memcg.
     */
    @Nullable
    static MemoryStat readMemoryStatFromMemcg(int uid, int pid) {
        final String memoryStatPath = String.format(Locale.US, MEMORY_STAT_FILE_FMT, uid, pid);
        final File memoryStatFile = new File(memoryStatPath);
        if (!memoryStatFile.exists()) {
            if (DEBUG_METRICS) Slog.i(TAG, memoryStatPath + " not found");
            return null;
        }

        try {
            final String memoryStatContents = FileUtils.readTextFile(
                    memoryStatFile, 0 /* max */, null /* ellipsis */);
            return parseMemoryStat(memoryStatContents);
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
    static MemoryStat parseMemoryStat(String memoryStatContents) {
        MemoryStat memoryStat = new MemoryStat();
        if (memoryStatContents == null) {
            return memoryStat;
        }

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
