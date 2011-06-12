/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.net;

import android.test.suitebuilder.annotation.SmallTest;

import junit.framework.TestCase;

@SmallTest
public class NetworkStatsTest extends TestCase {

    private static final String TEST_IFACE = "test0";
    private static final int TEST_UID = 1001;
    private static final long TEST_START = 1194220800000L;

    public void testFindIndex() throws Exception {
        final NetworkStats stats = new NetworkStats(TEST_START, 3)
                .addEntry(TEST_IFACE, 100, 1024, 0)
                .addEntry(TEST_IFACE, 101, 0, 1024)
                .addEntry(TEST_IFACE, 102, 1024, 1024);

        assertEquals(2, stats.findIndex(TEST_IFACE, 102));
        assertEquals(2, stats.findIndex(TEST_IFACE, 102));
        assertEquals(0, stats.findIndex(TEST_IFACE, 100));
        assertEquals(-1, stats.findIndex(TEST_IFACE, 6));
    }

    public void testAddEntryGrow() throws Exception {
        final NetworkStats stats = new NetworkStats(TEST_START, 2);

        assertEquals(0, stats.size);
        assertEquals(2, stats.iface.length);

        stats.addEntry(TEST_IFACE, TEST_UID, 1L, 2L);
        stats.addEntry(TEST_IFACE, TEST_UID, 2L, 2L);

        assertEquals(2, stats.size);
        assertEquals(2, stats.iface.length);

        stats.addEntry(TEST_IFACE, TEST_UID, 3L, 4L);
        stats.addEntry(TEST_IFACE, TEST_UID, 4L, 4L);
        stats.addEntry(TEST_IFACE, TEST_UID, 5L, 5L);

        assertEquals(5, stats.size);
        assertTrue(stats.iface.length >= 5);

        assertEquals(1L, stats.rx[0]);
        assertEquals(2L, stats.rx[1]);
        assertEquals(3L, stats.rx[2]);
        assertEquals(4L, stats.rx[3]);
        assertEquals(5L, stats.rx[4]);
    }

    public void testSubtractIdenticalData() throws Exception {
        final NetworkStats before = new NetworkStats(TEST_START, 2)
                .addEntry(TEST_IFACE, 100, 1024, 0)
                .addEntry(TEST_IFACE, 101, 0, 1024);

        final NetworkStats after = new NetworkStats(TEST_START, 2)
                .addEntry(TEST_IFACE, 100, 1024, 0)
                .addEntry(TEST_IFACE, 101, 0, 1024);

        final NetworkStats result = after.subtract(before);

        // identical data should result in zero delta
        assertEquals(0, result.rx[0]);
        assertEquals(0, result.tx[0]);
        assertEquals(0, result.rx[1]);
        assertEquals(0, result.tx[1]);
    }

    public void testSubtractIdenticalRows() throws Exception {
        final NetworkStats before = new NetworkStats(TEST_START, 2)
                .addEntry(TEST_IFACE, 100, 1024, 0)
                .addEntry(TEST_IFACE, 101, 0, 1024);

        final NetworkStats after = new NetworkStats(TEST_START, 2)
                .addEntry(TEST_IFACE, 100, 1025, 2)
                .addEntry(TEST_IFACE, 101, 3, 1028);

        final NetworkStats result = after.subtract(before);

        // expect delta between measurements
        assertEquals(1, result.rx[0]);
        assertEquals(2, result.tx[0]);
        assertEquals(3, result.rx[1]);
        assertEquals(4, result.tx[1]);
    }

    public void testSubtractNewRows() throws Exception {
        final NetworkStats before = new NetworkStats(TEST_START, 2)
                .addEntry(TEST_IFACE, 100, 1024, 0)
                .addEntry(TEST_IFACE, 101, 0, 1024);

        final NetworkStats after = new NetworkStats(TEST_START, 3)
                .addEntry(TEST_IFACE, 100, 1024, 0)
                .addEntry(TEST_IFACE, 101, 0, 1024)
                .addEntry(TEST_IFACE, 102, 1024, 1024);

        final NetworkStats result = after.subtract(before);

        // its okay to have new rows
        assertEquals(0, result.rx[0]);
        assertEquals(0, result.tx[0]);
        assertEquals(0, result.rx[1]);
        assertEquals(0, result.tx[1]);
        assertEquals(1024, result.rx[2]);
        assertEquals(1024, result.tx[2]);
    }

}
