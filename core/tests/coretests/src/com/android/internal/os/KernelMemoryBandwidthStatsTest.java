/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.internal.os;

import android.util.LongSparseLongArray;

import androidx.test.filters.SmallTest;

import junit.framework.TestCase;

import org.mockito.Mockito;

import java.io.BufferedReader;

/**
 * Tests for KernelMemoryBandwidthStats parsing and delta calculation, based on memory_state_time.
 */
public class KernelMemoryBandwidthStatsTest extends TestCase {

    /**
     * Standard example of parsing stats.
     * @throws Exception
     */
    @SmallTest
    public void testParseStandard() throws Exception {
        KernelMemoryBandwidthStats stats = new KernelMemoryBandwidthStats();
        BufferedReader mockStandardReader = Mockito.mock(BufferedReader.class);
        Mockito.when(mockStandardReader.readLine()).thenReturn(
                "99000000 0 0 0 0 0 0 0 0 0 0 0 0",
                "149000000 0 0 0 0 0 0 0 0 0 0 0 0",
                "199884800 7301000000 0 2000000 1000000 0 0 0 0 0 0 0 0",
                "299892736 674000000 0 21000000 0 0 0 0 0 0 0 0 0",
                "411959296 1146000000 0 221000000 1000000 0 0 0 0 0 0 0 0",
                "546963456 744000000 0 420000000 0 0 0 1000000 0 0 0 0 0",
                "680919040 182000000 0 1839000000 207000000 1000000 1000000 0 0 0 0 0 0",
                "767950848 0 0 198000000 33000000 4000000 0 1000000 0 0 0 0 0",
                "1016987648 0 0 339000000 362000000 3000000 0 0 0 0 0 0 16000000",
                "1295908864 0 0 20000000 870000000 244000000 0 0 0 0 0 0 33000000",
                "1554907136 0 0 6000000 32000000 631000000 115000000 0 0 0 1000000 0 0",
                "1803943936 2496000000 0 17000000 2000000 377000000 1505000000 278000000 183000000 141000000 486000000 154000000 113000000", null);
        stats.parseStats(mockStandardReader);
        long[] expected = new long[] {12543L, 0L, 3083L, 1508L, 1260L, 1621L, 280L, 183L,
                141L, 487L, 154L, 162L};
        LongSparseLongArray array = stats.getBandwidthEntries();
        for (int i = 2; i < array.size(); i++) {
            assertEquals(i, array.keyAt(i));
            assertEquals(expected[i], array.valueAt(i));
        }
        Mockito.verify(mockStandardReader, Mockito.times(13)).readLine();
    }

    /**
     * When the stats are populated with zeroes (unsupported device), checks that the stats are
     * zero.
     * @throws Exception
     */
    @SmallTest
    public void testParseBackwards() throws Exception {
        KernelMemoryBandwidthStats zeroStats = new KernelMemoryBandwidthStats();
        BufferedReader mockZeroReader = Mockito.mock(BufferedReader.class);
        Mockito.when(mockZeroReader.readLine()).thenReturn(
                "99000000 0 0 0 0 0 0 0 0 0 0 0 0",
                "149000000 0 0 0 0 0 0 0 0 0 0 0 0",
                "199884800 0 0 0 0 0 0 0 0 0 0 0 0",
                "299892736 0 0 0 0 0 0 0 0 0 0 0 0",
                "411959296 0 0 0 0 0 0 0 0 0 0 0 0",
                "546963456 0 0 0 0 0 0 0 0 0 0 0 0",
                "680919040 0 0 0 0 0 0 0 0 0 0 0 0",
                "767950848 0 0 0 0 0 0 0 0 0 0 0 0",
                "1016987648 0 0 0 0 0 0 0 0 0 0 0 0",
                "1295908864 0 0 0 0 0 0 0 0 0 0 0 0",
                "1554907136 0 0 0 0 0 0 0 0 0 0 0 0",
                "1803943936 0 0 0 0 0 0 0 0 0 0 0 0", null);
        zeroStats.parseStats(mockZeroReader);
        long[] expected = new long[] {0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L};
        LongSparseLongArray array = zeroStats.getBandwidthEntries();
        for (int i = 0; i < array.size(); i++) {
            assertEquals(expected[i], array.valueAt(i));
        }
        Mockito.verify(mockZeroReader, Mockito.times(13)).readLine();
    }
}
