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

import static com.android.server.am.MemoryStatUtil.BYTES_IN_KILOBYTE;
import static com.android.server.am.MemoryStatUtil.JIFFY_NANOS;
import static com.android.server.am.MemoryStatUtil.MemoryStat;
import static com.android.server.am.MemoryStatUtil.parseCmdlineFromProcfs;
import static com.android.server.am.MemoryStatUtil.parseMemoryStatFromMemcg;
import static com.android.server.am.MemoryStatUtil.parseMemoryStatFromProcfs;
import static com.android.server.am.MemoryStatUtil.parseVmHWMFromProcfs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import androidx.test.filters.SmallTest;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.Collections;

/**
 * Build/Install/Run:
 *  atest FrameworksServicesTests:MemoryStatUtilTest
 */
@SmallTest
public class MemoryStatUtilTest {
    private static final String MEMORY_STAT_CONTENTS = String.join(
            "\n",
            "cache 96", // keep different from total_cache to catch reading wrong value
            "rss 97", // keep different from total_rss to catch reading wrong value
            "rss_huge 0",
            "mapped_file 524288",
            "writeback 0",
            "swap 95", // keep different from total_rss to catch reading wrong value
            "pgpgin 16717",
            "pgpgout 5037",
            "pgfault 99", // keep different from total_pgfault to catch reading wrong value
            "pgmajfault 98", // keep different from total_pgmajfault to catch reading wrong value
            "inactive_anon 503808",
            "active_anon 46309376",
            "inactive_file 876544",
            "active_file 81920",
            "unevictable 0",
            "hierarchical_memory_limit 18446744073709551615",
            "hierarchical_memsw_limit 18446744073709551615",
            "total_cache 4",
            "total_rss 3",
            "total_rss_huge 0",
            "total_mapped_file 524288",
            "total_writeback 0",
            "total_swap 5",
            "total_pgpgin 16717",
            "total_pgpgout 5037",
            "total_pgfault 1",
            "total_pgmajfault 2",
            "total_inactive_anon 503808",
            "total_active_anon 46309376",
            "total_inactive_file 876544",
            "total_active_file 81920",
            "total_unevictable 0");

    private static final String PROC_STAT_CONTENTS = String.join(
            " ",
            "1040",
            "(system_server)",
            "S",
            "544",
            "544",
            "0",
            "0",
            "-1",
            "1077936448",
            "1", // this is pgfault
            "0",
            "2", // this is pgmajfault
            "0",
            "44533",
            "13471",
            "0",
            "0",
            "18",
            "-2",
            "117",
            "0",
            "2222", // this in start time (in ticks per second)
            "1257177088",
            "3",
            "4294967295",
            "2936971264",
            "2936991289",
            "3198888320",
            "3198879848",
            "2903927664",
            "0",
            "4612",
            "0",
            "1073775864",
            "4294967295",
            "0",
            "0",
            "17",
            "0",
            "0",
            "0",
            "0",
            "0",
            "0",
            "2936999088",
            "2936999936",
            "2958692352",
            "3198888595",
            "3198888671",
            "3198888671",
            "3198889956",
            "0");

    private static final String PROC_STATUS_CONTENTS = "Name:\tandroid.youtube\n"
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
    public void testParseMemoryStatFromMemcg_parsesCorrectValues() {
        MemoryStat stat = parseMemoryStatFromMemcg(MEMORY_STAT_CONTENTS);
        assertEquals(1, stat.pgfault);
        assertEquals(2, stat.pgmajfault);
        assertEquals(3, stat.rssInBytes);
        assertEquals(4, stat.cacheInBytes);
        assertEquals(5, stat.swapInBytes);
    }

    @Test
    public void testParseMemoryStatFromMemcg_emptyMemoryStatContents() {
        MemoryStat stat = parseMemoryStatFromMemcg("");
        assertNull(stat);

        stat = parseMemoryStatFromMemcg(null);
        assertNull(stat);
    }

    @Test
    public void testParseMemoryStatFromProcfs_parsesCorrectValues() {
        MemoryStat stat = parseMemoryStatFromProcfs(PROC_STAT_CONTENTS, PROC_STATUS_CONTENTS);
        assertEquals(1, stat.pgfault);
        assertEquals(2, stat.pgmajfault);
        assertEquals(126776 * BYTES_IN_KILOBYTE, stat.rssInBytes);
        assertEquals(0, stat.cacheInBytes);
        assertEquals(22 * BYTES_IN_KILOBYTE, stat.swapInBytes);
        assertEquals(2222 * JIFFY_NANOS, stat.startTimeNanos);
        assertEquals(37860 * BYTES_IN_KILOBYTE, stat.anonRssInBytes);
    }

    @Test
    public void testParseMemoryStatFromProcfs_emptyContents() {
        MemoryStat stat = parseMemoryStatFromProcfs("", PROC_STATUS_CONTENTS);
        assertNull(stat);

        stat = parseMemoryStatFromProcfs(null, PROC_STATUS_CONTENTS);
        assertNull(stat);

        stat = parseMemoryStatFromProcfs(PROC_STAT_CONTENTS, "");
        assertNull(stat);

        stat = parseMemoryStatFromProcfs(PROC_STAT_CONTENTS, null);
        assertNull(stat);
    }

    @Test
    public void testParseMemoryStatFromProcfs_invalidValue() {
        String contents = String.join(" ", Collections.nCopies(24, "memory"));
        assertNull(parseMemoryStatFromProcfs(contents, PROC_STATUS_CONTENTS));
    }

    @Test
    public void testParseVmHWMFromProcfs_parsesCorrectValue() {
        assertEquals(137668, parseVmHWMFromProcfs(PROC_STATUS_CONTENTS));
    }

    @Test
    public void testParseVmHWMFromProcfs_emptyContents() {
        assertEquals(0, parseVmHWMFromProcfs(""));

        assertEquals(0, parseVmHWMFromProcfs(null));
    }

    @Test
    public void testParseCmdlineFromProcfs_invalidValue() {
        byte[] nothing = new byte[] {0x00, 0x74, 0x65, 0x73, 0x74}; // \0test

        assertEquals("", parseCmdlineFromProcfs(bytesToString(nothing)));
    }

    @Test
    public void testParseCmdlineFromProcfs_correctValue_noNullBytes() {
        assertEquals("com.google.app", parseCmdlineFromProcfs("com.google.app"));
    }

    @Test
    public void testParseCmdlineFromProcfs_correctValue_withNullBytes() {
        byte[] trailing = new byte[] {0x74, 0x65, 0x73, 0x74, 0x00, 0x00, 0x00}; // test\0\0\0

        assertEquals("test", parseCmdlineFromProcfs(bytesToString(trailing)));

        // test\0\0test
        byte[] inside = new byte[] {0x74, 0x65, 0x73, 0x74, 0x00, 0x00, 0x74, 0x65, 0x73, 0x74};

        assertEquals("test", parseCmdlineFromProcfs(bytesToString(trailing)));
    }

    @Test
    public void testParseCmdlineFromProcfs_emptyContents() {
        assertEquals("", parseCmdlineFromProcfs(""));

        assertEquals("", parseCmdlineFromProcfs(null));
    }

    private static String bytesToString(byte[] bytes) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(bytes, 0, bytes.length);
        return output.toString();
    }
}
