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

import static com.android.server.am.MemoryStatUtil.parseMemoryStatFromMemcg;
import static com.android.server.am.MemoryStatUtil.parseMemoryStatFromProcfs;
import static com.android.server.am.MemoryStatUtil.MemoryStat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class MemoryStatUtilTest {
    private String MEMORY_STAT_CONTENTS = String.join(
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

    private String PROC_STAT_CONTENTS = String.join(
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
            "2206",
            "1257177088",
            "3", // this is rss in bytes
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

    @Test
    public void testParseMemoryStatFromMemcg_parsesCorrectValues() throws Exception {
        MemoryStat stat = parseMemoryStatFromMemcg(MEMORY_STAT_CONTENTS);
        assertEquals(stat.pgfault, 1);
        assertEquals(stat.pgmajfault, 2);
        assertEquals(stat.rssInBytes, 3);
        assertEquals(stat.cacheInBytes, 4);
        assertEquals(stat.swapInBytes, 5);
    }

    @Test
    public void testParseMemoryStatFromMemcg_emptyMemoryStatContents() throws Exception {
        MemoryStat stat = parseMemoryStatFromMemcg("");
        assertNull(stat);

        stat = parseMemoryStatFromMemcg(null);
        assertNull(stat);
    }

    @Test
    public void testParseMemoryStatFromProcfs_parsesCorrectValues() throws Exception {
        MemoryStat stat = parseMemoryStatFromProcfs(PROC_STAT_CONTENTS);
        assertEquals(1, stat.pgfault);
        assertEquals(2, stat.pgmajfault);
        assertEquals(3, stat.rssInBytes);
        assertEquals(0, stat.cacheInBytes);
        assertEquals(0, stat.swapInBytes);
    }

    @Test
    public void testParseMemoryStatFromProcfs_emptyContents() throws Exception {
        MemoryStat stat = parseMemoryStatFromProcfs("");
        assertNull(stat);

        stat = parseMemoryStatFromProcfs(null);
        assertNull(stat);
    }
}
