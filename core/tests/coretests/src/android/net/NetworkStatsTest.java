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

import static android.net.NetworkStats.TAG_NONE;

import android.test.suitebuilder.annotation.SmallTest;

import junit.framework.TestCase;

@SmallTest
public class NetworkStatsTest extends TestCase {

    private static final String TEST_IFACE = "test0";
    private static final int TEST_UID = 1001;
    private static final long TEST_START = 1194220800000L;

    public void testFindIndex() throws Exception {
        final NetworkStats stats = new NetworkStats(TEST_START, 3)
                .addValues(TEST_IFACE, 100, TAG_NONE, 1024L, 8L, 0L, 0L)
                .addValues(TEST_IFACE, 101, TAG_NONE, 0L, 0L, 1024L, 8L)
                .addValues(TEST_IFACE, 102, TAG_NONE, 1024L, 8L, 1024L, 8L);

        assertEquals(2, stats.findIndex(TEST_IFACE, 102, TAG_NONE));
        assertEquals(2, stats.findIndex(TEST_IFACE, 102, TAG_NONE));
        assertEquals(0, stats.findIndex(TEST_IFACE, 100, TAG_NONE));
        assertEquals(-1, stats.findIndex(TEST_IFACE, 6, TAG_NONE));
    }

    public void testAddEntryGrow() throws Exception {
        final NetworkStats stats = new NetworkStats(TEST_START, 2);

        assertEquals(0, stats.size());
        assertEquals(2, stats.internalSize());

        stats.addValues(TEST_IFACE, TEST_UID, TAG_NONE, 1L, 1L, 2L, 2L);
        stats.addValues(TEST_IFACE, TEST_UID, TAG_NONE, 2L, 2L, 2L, 2L);

        assertEquals(2, stats.size());
        assertEquals(2, stats.internalSize());

        stats.addValues(TEST_IFACE, TEST_UID, TAG_NONE, 3L, 30L, 4L, 40L);
        stats.addValues(TEST_IFACE, TEST_UID, TAG_NONE, 4L, 40L, 4L, 40L);
        stats.addValues(TEST_IFACE, TEST_UID, TAG_NONE, 5L, 50L, 5L, 50L);

        assertEquals(5, stats.size());
        assertTrue(stats.internalSize() >= 5);

        assertEntry(stats, 0, TEST_IFACE, TEST_UID, TAG_NONE, 1L, 1L, 2L, 2L);
        assertEntry(stats, 1, TEST_IFACE, TEST_UID, TAG_NONE, 2L, 2L, 2L, 2L);
        assertEntry(stats, 2, TEST_IFACE, TEST_UID, TAG_NONE, 3L, 30L, 4L, 40L);
        assertEntry(stats, 3, TEST_IFACE, TEST_UID, TAG_NONE, 4L, 40L, 4L, 40L);
        assertEntry(stats, 4, TEST_IFACE, TEST_UID, TAG_NONE, 5L, 50L, 5L, 50L);
    }

    public void testCombineExisting() throws Exception {
        final NetworkStats stats = new NetworkStats(TEST_START, 10);

        stats.addValues(TEST_IFACE, 1001, TAG_NONE, 512L, 4L, 256L, 2L);
        stats.addValues(TEST_IFACE, 1001, 0xff, 128L, 1L, 128L, 1L);
        stats.combineValues(TEST_IFACE, 1001, TAG_NONE, -128L, -1L, -128L, -1L);

        assertEntry(stats, 0, TEST_IFACE, 1001, TAG_NONE, 384L, 3L, 128L, 1L);
        assertEntry(stats, 1, TEST_IFACE, 1001, 0xff, 128L, 1L, 128L, 1L);

        // now try combining that should create row
        stats.combineValues(TEST_IFACE, 5005, TAG_NONE, 128L, 1L, 128L, 1L);
        assertEntry(stats, 2, TEST_IFACE, 5005, TAG_NONE, 128L, 1L, 128L, 1L);
        stats.combineValues(TEST_IFACE, 5005, TAG_NONE, 128L, 1L, 128L, 1L);
        assertEntry(stats, 2, TEST_IFACE, 5005, TAG_NONE, 256L, 2L, 256L, 2L);
    }

    public void testSubtractIdenticalData() throws Exception {
        final NetworkStats before = new NetworkStats(TEST_START, 2)
                .addValues(TEST_IFACE, 100, TAG_NONE, 1024L, 8L, 0L, 0L)
                .addValues(TEST_IFACE, 101, TAG_NONE, 0L, 0L, 1024L, 8L);

        final NetworkStats after = new NetworkStats(TEST_START, 2)
                .addValues(TEST_IFACE, 100, TAG_NONE, 1024L, 8L, 0L, 0L)
                .addValues(TEST_IFACE, 101, TAG_NONE, 0L, 0L, 1024L, 8L);

        final NetworkStats result = after.subtract(before);

        // identical data should result in zero delta
        assertEntry(result, 0, TEST_IFACE, 100, TAG_NONE, 0L, 0L, 0L, 0L);
        assertEntry(result, 1, TEST_IFACE, 101, TAG_NONE, 0L, 0L, 0L, 0L);
    }

    public void testSubtractIdenticalRows() throws Exception {
        final NetworkStats before = new NetworkStats(TEST_START, 2)
                .addValues(TEST_IFACE, 100, TAG_NONE, 1024L, 8L, 0L, 0L)
                .addValues(TEST_IFACE, 101, TAG_NONE, 0L, 0L, 1024L, 8L);

        final NetworkStats after = new NetworkStats(TEST_START, 2)
                .addValues(TEST_IFACE, 100, TAG_NONE, 1025L, 9L, 2L, 1L)
                .addValues(TEST_IFACE, 101, TAG_NONE, 3L, 1L, 1028L, 9L);

        final NetworkStats result = after.subtract(before);

        // expect delta between measurements
        assertEntry(result, 0, TEST_IFACE, 100, TAG_NONE, 1L, 1L, 2L, 1L);
        assertEntry(result, 1, TEST_IFACE, 101, TAG_NONE, 3L, 1L, 4L, 1L);
    }

    public void testSubtractNewRows() throws Exception {
        final NetworkStats before = new NetworkStats(TEST_START, 2)
                .addValues(TEST_IFACE, 100, TAG_NONE, 1024L, 8L, 0L, 0L)
                .addValues(TEST_IFACE, 101, TAG_NONE, 0L, 0L, 1024L, 8L);

        final NetworkStats after = new NetworkStats(TEST_START, 3)
                .addValues(TEST_IFACE, 100, TAG_NONE, 1024L, 8L, 0L, 0L)
                .addValues(TEST_IFACE, 101, TAG_NONE, 0L, 0L, 1024L, 8L)
                .addValues(TEST_IFACE, 102, TAG_NONE, 1024L, 8L, 1024L, 8L);

        final NetworkStats result = after.subtract(before);

        // its okay to have new rows
        assertEntry(result, 0, TEST_IFACE, 100, TAG_NONE, 0L, 0L, 0L, 0L);
        assertEntry(result, 1, TEST_IFACE, 101, TAG_NONE, 0L, 0L, 0L, 0L);
        assertEntry(result, 2, TEST_IFACE, 102, TAG_NONE, 1024L, 8L, 1024L, 8L);
    }

    private static void assertEntry(NetworkStats stats, int index, String iface, int uid, int tag,
            long rxBytes, long rxPackets, long txBytes, long txPackets) {
        final NetworkStats.Entry entry = stats.getValues(index, null);
        assertEquals(iface, entry.iface);
        assertEquals(uid, entry.uid);
        assertEquals(tag, entry.tag);
        assertEquals(rxBytes, entry.rxBytes);
        assertEquals(rxPackets, entry.rxPackets);
        assertEquals(txBytes, entry.txBytes);
        assertEquals(txPackets, entry.txPackets);
    }

}
