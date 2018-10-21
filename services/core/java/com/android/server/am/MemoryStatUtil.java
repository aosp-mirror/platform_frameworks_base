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

import static com.android.server.am.ActivityTaskManagerDebugConfig.DEBUG_METRICS;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.annotation.Nullable;
import android.os.FileUtils;
import android.os.SystemProperties;
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
    /**
     * Which native processes to create {@link MemoryStat} for.
     *
     * <p>Processes are matched by their cmdline in procfs. Example: cat /proc/pid/cmdline returns
     * /system/bin/statsd for the stats daemon.
     */
    static final String[] MEMORY_STAT_INTERESTING_NATIVE_PROCESSES = new String[]{
            "/system/bin/statsd",  // Stats daemon.
            "/system/bin/surfaceflinger",
            "/system/bin/apexd",  // APEX daemon.
            "/system/bin/audioserver",
            "/system/bin/cameraserver",
            "/system/bin/drmserver",
            "/system/bin/healthd",
            "/system/bin/incidentd",
            "/system/bin/installd",
            "/system/bin/lmkd",  // Low memory killer daemon.
            "/system/bin/logd",
            "media.codec",
            "media.extractor",
            "media.metrics",
            "/system/bin/mediadrmserver",
            "/system/bin/mediaserver",
            "/system/bin/performanced",
            "/system/bin/tombstoned",
            "/system/bin/traced",  // Perfetto.
            "/system/bin/traced_probes",  // Perfetto.
            "webview_zygote",
            "zygote",
            "zygote64",
    };

    static final int BYTES_IN_KILOBYTE = 1024;
    static final int PAGE_SIZE = 4096;

    private static final String TAG = TAG_WITH_CLASS_NAME ? "MemoryStatUtil" : TAG_AM;

    /** True if device has per-app memcg */
    private static final boolean DEVICE_HAS_PER_APP_MEMCG =
            SystemProperties.getBoolean("ro.config.per_app_memcg", false);

    /** Path to check if device has memcg */
    private static final String MEMCG_TEST_PATH = "/dev/memcg/apps/memory.stat";
    /** Path to memory stat file for logging app start memory state */
    private static final String MEMORY_STAT_FILE_FMT = "/dev/memcg/apps/uid_%d/pid_%d/memory.stat";
    /** Path to memory max usage file for logging app memory state */
    private static final String MEMORY_MAX_USAGE_FILE_FMT =
            "/dev/memcg/apps/uid_%d/pid_%d/memory.max_usage_in_bytes";
    /** Path to procfs stat file for logging app start memory state */
    private static final String PROC_STAT_FILE_FMT = "/proc/%d/stat";
    /** Path to procfs status file for logging app memory state */
    private static final String PROC_STATUS_FILE_FMT = "/proc/%d/status";
    /** Path to procfs cmdline file. Used with pid: /proc/pid/cmdline. */
    private static final String PROC_CMDLINE_FILE_FMT = "/proc/%d/cmdline";

    private static final Pattern PGFAULT = Pattern.compile("total_pgfault (\\d+)");
    private static final Pattern PGMAJFAULT = Pattern.compile("total_pgmajfault (\\d+)");
    private static final Pattern RSS_IN_BYTES = Pattern.compile("total_rss (\\d+)");
    private static final Pattern CACHE_IN_BYTES = Pattern.compile("total_cache (\\d+)");
    private static final Pattern SWAP_IN_BYTES = Pattern.compile("total_swap (\\d+)");

    private static final Pattern RSS_HIGH_WATERMARK_IN_BYTES =
            Pattern.compile("VmHWM:\\s*(\\d+)\\s*kB");

    private static final int PGFAULT_INDEX = 9;
    private static final int PGMAJFAULT_INDEX = 11;
    private static final int RSS_IN_PAGES_INDEX = 23;

    private MemoryStatUtil() {}

    /**
     * Reads memory stat for a process.
     *
     * Reads from per-app memcg if available on device, else fallback to procfs.
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
        final String statPath = String.format(Locale.US, MEMORY_STAT_FILE_FMT, uid, pid);
        MemoryStat stat = parseMemoryStatFromMemcg(readFileContents(statPath));
        if (stat == null) {
            return null;
        }
        String maxUsagePath = String.format(Locale.US, MEMORY_MAX_USAGE_FILE_FMT, uid, pid);
        stat.rssHighWatermarkInBytes = parseMemoryMaxUsageFromMemCg(
                readFileContents(maxUsagePath));
        return stat;
    }

    /**
     * Reads memory stat of a process from procfs.
     *
     * Returns null if file is not found in procfs or if file has unrecognized contents.
     */
    @Nullable
    static MemoryStat readMemoryStatFromProcfs(int pid) {
        final String statPath = String.format(Locale.US, PROC_STAT_FILE_FMT, pid);
        MemoryStat stat = parseMemoryStatFromProcfs(readFileContents(statPath));
        if (stat == null) {
            return null;
        }
        final String statusPath = String.format(Locale.US, PROC_STATUS_FILE_FMT, pid);
        stat.rssHighWatermarkInBytes = parseVmHWMFromProcfs(readFileContents(statusPath));
        return stat;
    }

    /**
     * Reads cmdline of a process from procfs.
     *
     * Returns content of /proc/pid/cmdline (e.g. /system/bin/statsd) or an empty string
     * if the file is not available.
     */
    static String readCmdlineFromProcfs(int pid) {
        String path = String.format(Locale.US, PROC_CMDLINE_FILE_FMT, pid);
        String cmdline = readFileContents(path);
        return cmdline != null ? cmdline : "";
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
    @VisibleForTesting
    @Nullable
    static MemoryStat parseMemoryStatFromMemcg(String memoryStatContents) {
        if (memoryStatContents == null || memoryStatContents.isEmpty()) {
            return null;
        }

        final MemoryStat memoryStat = new MemoryStat();
        Matcher m;
        m = PGFAULT.matcher(memoryStatContents);
        memoryStat.pgfault = m.find() ? Long.parseLong(m.group(1)) : 0;
        m = PGMAJFAULT.matcher(memoryStatContents);
        memoryStat.pgmajfault = m.find() ? Long.parseLong(m.group(1)) : 0;
        m = RSS_IN_BYTES.matcher(memoryStatContents);
        memoryStat.rssInBytes = m.find() ? Long.parseLong(m.group(1)) : 0;
        m = CACHE_IN_BYTES.matcher(memoryStatContents);
        memoryStat.cacheInBytes = m.find() ? Long.parseLong(m.group(1)) : 0;
        m = SWAP_IN_BYTES.matcher(memoryStatContents);
        memoryStat.swapInBytes = m.find() ? Long.parseLong(m.group(1)) : 0;
        return memoryStat;
    }

    @VisibleForTesting
    static long parseMemoryMaxUsageFromMemCg(String memoryMaxUsageContents) {
        if (memoryMaxUsageContents == null || memoryMaxUsageContents.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(memoryMaxUsageContents);
        } catch (NumberFormatException e) {
            Slog.e(TAG, "Failed to parse value", e);
            return 0;
        }
    }

    /**
     * Parses relevant statistics out from the contents of the /proc/pid/stat file in procfs.
     */
    @VisibleForTesting
    @Nullable
    static MemoryStat parseMemoryStatFromProcfs(String procStatContents) {
        if (procStatContents == null || procStatContents.isEmpty()) {
            return null;
        }

        final String[] splits = procStatContents.split(" ");
        if (splits.length < 24) {
            return null;
        }

        try {
            final MemoryStat memoryStat = new MemoryStat();
            memoryStat.pgfault = Long.parseLong(splits[PGFAULT_INDEX]);
            memoryStat.pgmajfault = Long.parseLong(splits[PGMAJFAULT_INDEX]);
            memoryStat.rssInBytes = Long.parseLong(splits[RSS_IN_PAGES_INDEX]) * PAGE_SIZE;
            return memoryStat;
        } catch (NumberFormatException e) {
            Slog.e(TAG, "Failed to parse value", e);
            return null;
        }
    }

    /**
     * Parses RSS high watermark out from the contents of the /proc/pid/status file in procfs. The
     * returned value is in bytes.
     */
    @VisibleForTesting
    static long parseVmHWMFromProcfs(String procStatusContents) {
        if (procStatusContents == null || procStatusContents.isEmpty()) {
            return 0;
        }
        Matcher m = RSS_HIGH_WATERMARK_IN_BYTES.matcher(procStatusContents);
        // Convert value read from /proc/pid/status from kilobytes to bytes.
        return m.find() ? Long.parseLong(m.group(1)) * BYTES_IN_KILOBYTE : 0;
    }

    /**
     * Returns whether per-app memcg is available on device.
     */
    static boolean hasMemcg() {
        return DEVICE_HAS_PER_APP_MEMCG;
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
        /** Number of bytes of peak anonymous and swap cache memory */
        long rssHighWatermarkInBytes;
    }
}
