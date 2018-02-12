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

import static com.android.server.am.MemoryStatUtil.parseMemoryStat;
import static com.android.server.am.MemoryStatUtil.MemoryStat;

import static org.junit.Assert.assertEquals;

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


  @Test
  public void testParseMemoryStat_parsesCorrectValues() throws Exception {
    MemoryStat stat = parseMemoryStat(MEMORY_STAT_CONTENTS);
    assertEquals(stat.pgfault, 1);
    assertEquals(stat.pgmajfault, 2);
    assertEquals(stat.rssInBytes, 3);
    assertEquals(stat.cacheInBytes, 4);
    assertEquals(stat.swapInBytes, 5);
  }

  @Test
  public void testParseMemoryStat_emptyMemoryStatContents() throws Exception {
    MemoryStat stat = parseMemoryStat("");
    assertEquals(stat.pgfault, 0);
    assertEquals(stat.pgmajfault, 0);
    assertEquals(stat.rssInBytes, 0);
    assertEquals(stat.cacheInBytes, 0);
    assertEquals(stat.swapInBytes, 0);

    stat = parseMemoryStat(null);
    assertEquals(stat.pgfault, 0);
    assertEquals(stat.pgmajfault, 0);
    assertEquals(stat.rssInBytes, 0);
    assertEquals(stat.cacheInBytes, 0);
    assertEquals(stat.swapInBytes, 0);
  }
}
