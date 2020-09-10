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

import static com.android.server.stats.pull.IonMemoryUtil.parseIonHeapSizeFromDebugfs;
import static com.android.server.stats.pull.IonMemoryUtil.parseProcessIonHeapSizesFromDebugfs;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.filters.SmallTest;

import com.android.server.stats.pull.IonMemoryUtil.IonAllocations;

import org.junit.Test;

/**
 * Build/Install/Run:
 *  atest FrameworksServicesTests:IonMemoryUtilTest
 */
@SmallTest
public class IonMemoryUtilTest {
    // Repeated lines have been removed.
    private static final String DEBUG_SYSTEM_ION_HEAP_CONTENTS = String.join(
            "\n",
            "          client              pid             size",
            "----------------------------------------------------",
            " audio@2.0-servi              765             4096",
            " audio@2.0-servi              765            61440",
            " audio@2.0-servi              765             4096",
            "     voip_client               96             8192",
            "     voip_client               96             4096",
            "   system_server             1232         16728064",
            "  surfaceflinger              611         50642944",
            "----------------------------------------------------",
            "orphaned allocations (info is from last known client):",
            "----------------------------------------------------",
            "  total orphaned                0",
            "          total          55193600",
            "   deferred free                0",
            "----------------------------------------------------",
            "0 order 4 highmem pages in uncached pool = 0 total",
            "0 order 4 lowmem pages in uncached pool = 0 total",
            "1251 order 4 lowmem pages in cached pool = 81985536 total",
            "VMID 8: 0 order 4 highmem pages in secure pool = 0 total",
            "VMID  8: 0 order 4 lowmem pages in secure pool = 0 total",
            "--------------------------------------------",
            "uncached pool = 4096 cached pool = 83566592 secure pool = 0",
            "pool total (uncached + cached + secure) = 83570688",
            "--------------------------------------------");

    // Repeated lines have been removed.
    private static final String DEBUG_SYSTEM_ION_HEAP_CONTENTS_SARGO = String.join(
            "\n",
            "          client              pid             size      page counts"
                    + "--------------------------------------------------       4K       8K      "
                    + "16K      32K      64K     128K     256K     512K       1M       2M       "
                    + "4M      >=8M",
            "   system_server             1705         58097664    13120      532        "
                    + "0        0        0        0        0        0        0        0        "
                    + "0        0M",
            " audio@2.0-servi              851            16384        0        2        0        "
                    + "0        0        0        0        0        0        0        "
                    + "0        0M",
            " audio@2.0-servi              851             4096        1        0        0       "
                    + " 0        0        0        0        0        0        0        0        "
                    + "0M",
            " audio@2.0-servi              851             4096        1        0      "
                    + "  0        0        0        0        0        0        0        0        "
                    + "0        0M",
            "----------------------------------------------------",
            "orphaned allocations (info is from last known client):",
            "----------------------------------------------------",
            "  total orphaned                0",
            "          total         159928320",
            "   deferred free                0",
            "----------------------------------------------------",
            "0 order 4 highmem pages in uncached pool = 0 total",
            "0 order 4 lowmem pages in uncached pool = 0 total",
            "1251 order 4 lowmem pages in cached pool = 81985536 total",
            "VMID 8: 0 order 4 highmem pages in secure pool = 0 total",
            "VMID  8: 0 order 4 lowmem pages in secure pool = 0 total",
            "--------------------------------------------",
            "uncached pool = 4096 cached pool = 83566592 secure pool = 0",
            "pool total (uncached + cached + secure) = 83570688",
            "--------------------------------------------");

    @Test
    public void testParseIonHeapSizeFromDebugfs_emptyContents() {
        assertThat(parseIonHeapSizeFromDebugfs("")).isEqualTo(0);
    }

    @Test
    public void testParseIonHeapSizeFromDebugfs_invalidValue() {
        assertThat(parseIonHeapSizeFromDebugfs("<<no-value>>")).isEqualTo(0);

        assertThat(parseIonHeapSizeFromDebugfs("\ntotal 12345678901234567890\n")).isEqualTo(0);
    }

    @Test
    public void testParseIonHeapSizeFromDebugfs_correctValue() {
        assertThat(parseIonHeapSizeFromDebugfs(DEBUG_SYSTEM_ION_HEAP_CONTENTS))
                .isEqualTo(55193600);

        assertThat(parseIonHeapSizeFromDebugfs(DEBUG_SYSTEM_ION_HEAP_CONTENTS_SARGO))
                .isEqualTo(159928320);
    }

    @Test
    public void testParseProcessIonHeapSizesFromDebugfs_emptyContents() {
        assertThat(parseProcessIonHeapSizesFromDebugfs("")).hasSize(0);
    }

    @Test
    public void testParseProcessIonHeapSizesFromDebugfs_invalidValue() {
        assertThat(parseProcessIonHeapSizesFromDebugfs("<<no-value>>").size()).isEqualTo(0);
    }

    @Test
    public void testParseProcessIonHeapSizesFromDebugfs_correctValue1() {
        assertThat(parseProcessIonHeapSizesFromDebugfs(DEBUG_SYSTEM_ION_HEAP_CONTENTS))
                .containsExactly(
                        createIonAllocations(765, 61440 + 4096 + 4096, 3, 61440),
                        createIonAllocations(96, 8192 + 4096, 2, 8192),
                        createIonAllocations(1232, 16728064, 1, 16728064),
                        createIonAllocations(611, 50642944, 1, 50642944));
    }

    @Test
    public void testParseProcessIonHeapSizesFromDebugfs_correctValue2() {
        assertThat(parseProcessIonHeapSizesFromDebugfs(DEBUG_SYSTEM_ION_HEAP_CONTENTS_SARGO))
                .containsExactly(
                        createIonAllocations(1705, 58097664, 1, 58097664),
                        createIonAllocations(851, 16384 + 4096 + 4096, 3, 16384));
    }

    private static IonAllocations createIonAllocations(int pid, long totalSizeInBytes, int count,
            long maxSizeInBytes) {
        IonAllocations allocations = new IonAllocations();
        allocations.pid = pid;
        allocations.totalSizeInBytes = totalSizeInBytes;
        allocations.count = count;
        allocations.maxSizeInBytes = maxSizeInBytes;
        return allocations;
    }
}
