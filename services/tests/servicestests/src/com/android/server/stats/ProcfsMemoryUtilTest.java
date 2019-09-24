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
package com.android.server.stats;

import static com.android.server.stats.ProcfsMemoryUtil.parseMemorySnapshotFromStatus;
import static com.android.server.stats.ProcfsMemoryUtil.parseVmHWMFromStatus;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.filters.SmallTest;

import com.android.server.stats.ProcfsMemoryUtil.MemorySnapshot;

import org.junit.Test;

/**
 * Build/Install/Run:
 *  atest FrameworksServicesTests:ProcfsMemoryUtilTest
 */
@SmallTest
public class ProcfsMemoryUtilTest {
    private static final String STATUS_CONTENTS = "Name:\tandroid.youtube\n"
            + "State:\tS (sleeping)\n"
            + "Tgid:\t12088\n"
            + "Pid:\t12088\n"
            + "PPid:\t723\n"
            + "TracerPid:\t0\n"
            + "Uid:\t10083\t10083\t10083\t10083\n"
            + "Gid:\t10083\t10083\t10083\t10083\n"
            + "Ngid:\t0\n"
            + "FDSize:\t128\n"
            + "Groups:\t3003 9997 20083 50083 \n"
            + "VmPeak:\t 4546844 kB\n"
            + "VmSize:\t 4542636 kB\n"
            + "VmLck:\t       0 kB\n"
            + "VmPin:\t       0 kB\n"
            + "VmHWM:\t  137668 kB\n" // RSS high-water mark
            + "VmRSS:\t  126776 kB\n" // RSS
            + "RssAnon:\t   37860 kB\n"
            + "RssFile:\t   88764 kB\n"
            + "RssShmem:\t     152 kB\n"
            + "VmData:\t 4125112 kB\n"
            + "VmStk:\t    8192 kB\n"
            + "VmExe:\t      24 kB\n"
            + "VmLib:\t  102432 kB\n"
            + "VmPTE:\t    1300 kB\n"
            + "VmPMD:\t      36 kB\n"
            + "VmSwap:\t      22 kB\n" // Swap
            + "Threads:\t95\n"
            + "SigQ:\t0/13641\n"
            + "SigPnd:\t0000000000000000\n"
            + "ShdPnd:\t0000000000000000\n"
            + "SigBlk:\t0000000000001204\n"
            + "SigIgn:\t0000000000000001\n"
            + "SigCgt:\t00000006400084f8\n"
            + "CapInh:\t0000000000000000\n"
            + "CapPrm:\t0000000000000000\n"
            + "CapEff:\t0000000000000000\n"
            + "CapBnd:\t0000000000000000\n"
            + "CapAmb:\t0000000000000000\n"
            + "Seccomp:\t2\n"
            + "Cpus_allowed:\tff\n"
            + "Cpus_allowed_list:\t0-7\n"
            + "Mems_allowed:\t1\n"
            + "Mems_allowed_list:\t0\n"
            + "voluntary_ctxt_switches:\t903\n"
            + "nonvoluntary_ctxt_switches:\t104\n";

    @Test
    public void testParseVmHWMFromStatus_parsesCorrectValue() {
        assertThat(parseVmHWMFromStatus(STATUS_CONTENTS)).isEqualTo(137668);
    }

    @Test
    public void testParseVmHWMFromStatus_invalidValue() {
        assertThat(parseVmHWMFromStatus("test\nVmHWM: x0x0x\ntest")).isEqualTo(0);
    }

    @Test
    public void testParseVmHWMFromStatus_emptyContents() {
        assertThat(parseVmHWMFromStatus("")).isEqualTo(0);
    }

    @Test
    public void testParseMemorySnapshotFromStatus_parsesCorrectValue() {
        MemorySnapshot snapshot = parseMemorySnapshotFromStatus(STATUS_CONTENTS);
        assertThat(snapshot.rssInKilobytes).isEqualTo(126776);
        assertThat(snapshot.anonRssInKilobytes).isEqualTo(37860);
        assertThat(snapshot.swapInKilobytes).isEqualTo(22);
        assertThat(snapshot.isEmpty()).isFalse();
    }

    @Test
    public void testParseMemorySnapshotFromStatus_invalidValue() {
        MemorySnapshot snapshot =
                parseMemorySnapshotFromStatus("test\nVmRSS:\tx0x0x\nVmSwap:\t1 kB\ntest");
        assertThat(snapshot.rssInKilobytes).isEqualTo(0);
        assertThat(snapshot.anonRssInKilobytes).isEqualTo(0);
        assertThat(snapshot.swapInKilobytes).isEqualTo(1);
        assertThat(snapshot.isEmpty()).isFalse();
    }

    @Test
    public void testParseMemorySnapshotFromStatus_emptyContents() {
        MemorySnapshot snapshot = parseMemorySnapshotFromStatus("");
        assertThat(snapshot.rssInKilobytes).isEqualTo(0);
        assertThat(snapshot.anonRssInKilobytes).isEqualTo(0);
        assertThat(snapshot.swapInKilobytes).isEqualTo(0);
        assertThat(snapshot.isEmpty()).isTrue();
    }
}
