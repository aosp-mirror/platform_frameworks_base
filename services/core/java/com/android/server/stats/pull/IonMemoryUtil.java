/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.server.stats.pull;

import android.os.FileUtils;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility methods for reading ion memory stats.
 * TODO: Consider making package private after puller migration
 */
public final class IonMemoryUtil {
    private static final String TAG = "IonMemoryUtil";

    /** Path to debugfs file for the system ion heap. */
    private static final String DEBUG_SYSTEM_ION_HEAP_FILE = "/sys/kernel/debug/ion/heaps/system";

    private static final Pattern ION_HEAP_SIZE_IN_BYTES =
            Pattern.compile("\n\\s*total\\s*(\\d+)\\s*\n");
    private static final Pattern PROCESS_ION_HEAP_SIZE_IN_BYTES =
            Pattern.compile("\n\\s+\\S+\\s+(\\d+)\\s+(\\d+)");

    private IonMemoryUtil() {}

    /**
     * Reads size of the system ion heap from debugfs.
     *
     * Returns value of the total size in bytes of the system ion heap from
     * /sys/kernel/debug/ion/heaps/system.
     */
    public static long readSystemIonHeapSizeFromDebugfs() {
        return parseIonHeapSizeFromDebugfs(readFile(DEBUG_SYSTEM_ION_HEAP_FILE));
    }

    /**
     * Parses the ion heap size from the contents of a file under /sys/kernel/debug/ion/heaps in
     * debugfs. The returned value is in bytes.
     */
    @VisibleForTesting
    static long parseIonHeapSizeFromDebugfs(String contents) {
        if (contents.isEmpty()) {
            return 0;
        }
        final Matcher matcher = ION_HEAP_SIZE_IN_BYTES.matcher(contents);
        try {
            return matcher.find() ? Long.parseLong(matcher.group(1)) : 0;
        } catch (NumberFormatException e) {
            Slog.e(TAG, "Failed to parse value", e);
            return 0;
        }
    }

    /**
     * Reads process allocation sizes on the system ion heap from debugfs.
     *
     * Returns values of allocation sizes in bytes on the system ion heap from
     * /sys/kernel/debug/ion/heaps/system.
     */
    public static List<IonAllocations> readProcessSystemIonHeapSizesFromDebugfs() {
        return parseProcessIonHeapSizesFromDebugfs(readFile(DEBUG_SYSTEM_ION_HEAP_FILE));
    }

    /**
     * Parses per-process allocation sizes on the ion heap from the contents of a file under
     * /sys/kernel/debug/ion/heaps in debugfs.
     */
    @VisibleForTesting
    static List<IonAllocations> parseProcessIonHeapSizesFromDebugfs(String contents) {
        if (contents.isEmpty()) {
            return Collections.emptyList();
        }

        final Matcher m = PROCESS_ION_HEAP_SIZE_IN_BYTES.matcher(contents);
        final SparseArray<IonAllocations> entries = new SparseArray<>();
        while (m.find()) {
            try {
                final int pid = Integer.parseInt(m.group(1));
                final long sizeInBytes = Long.parseLong(m.group(2));
                IonAllocations allocations = entries.get(pid);
                if (allocations == null) {
                    allocations = new IonAllocations();
                    entries.put(pid, allocations);
                }
                allocations.pid = pid;
                allocations.totalSizeInBytes += sizeInBytes;
                allocations.count += 1;
                allocations.maxSizeInBytes = Math.max(allocations.maxSizeInBytes, sizeInBytes);
            } catch (NumberFormatException e) {
                Slog.e(TAG, "Failed to parse value", e);
            }
        }

        final List<IonAllocations> result = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            result.add(entries.valueAt(i));
        }
        return result;
    }

    private static String readFile(String path) {
        try {
            final File file = new File(path);
            return FileUtils.readTextFile(file, 0 /* max */, null /* ellipsis */);
        } catch (IOException e) {
            Slog.e(TAG, "Failed to read file", e);
            return "";
        }
    }

    /** Summary information about process ion allocations. */
    public static final class IonAllocations {
        /** PID these allocations belong to. */
        public int pid;
        /** Size of all individual allocations added together. */
        public long totalSizeInBytes;
        /** Number of allocations. */
        public int count;
        /** Size of the largest allocation. */
        public long maxSizeInBytes;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            IonAllocations that = (IonAllocations) o;
            return pid == that.pid && totalSizeInBytes == that.totalSizeInBytes
                    && count == that.count && maxSizeInBytes == that.maxSizeInBytes;
        }

        @Override
        public int hashCode() {
            return Objects.hash(pid, totalSizeInBytes, count, maxSizeInBytes);
        }

        @Override
        public String toString() {
            return "IonAllocations{"
                    + "pid=" + pid
                    + ", totalSizeInBytes=" + totalSizeInBytes
                    + ", count=" + count
                    + ", maxSizeInBytes=" + maxSizeInBytes
                    + '}';
        }
    }
}
