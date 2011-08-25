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

import static android.net.NetworkStats.SET_DEFAULT;
import static android.net.NetworkStats.SET_FOREGROUND;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.NetworkStats.UID_ALL;

import android.test.suitebuilder.annotation.SmallTest;

import junit.framework.TestCase;

@SmallTest
public class NetworkStatsTest extends TestCase {

    private static final String TEST_IFACE = "test0";
    private static final String TEST_IFACE2 = "test2";
    private static final int TEST_UID = 1001;
    private static final long TEST_START = 1194220800000L;

    public void testFindIndex() throws Exception {
        final NetworkStats stats = new NetworkStats(TEST_START, 3)
                .addValues(TEST_IFACE, 100, SET_DEFAULT, TAG_NONE, 1024L, 8L, 0L, 0L, 10)
                .addValues(TEST_IFACE, 101, SET_DEFAULT, TAG_NONE, 0L, 0L, 1024L, 8L, 11)
                .addValues(TEST_IFACE, 102, SET_DEFAULT, TAG_NONE, 1024L, 8L, 1024L, 8L, 12);

        assertEquals(2, stats.findIndex(TEST_IFACE, 102, SET_DEFAULT, TAG_NONE));
        assertEquals(2, stats.findIndex(TEST_IFACE, 102, SET_DEFAULT, TAG_NONE));
        assertEquals(0, stats.findIndex(TEST_IFACE, 100, SET_DEFAULT, TAG_NONE));
        assertEquals(-1, stats.findIndex(TEST_IFACE, 6, SET_DEFAULT, TAG_NONE));
    }

    public void testAddEntryGrow() throws Exception {
        final NetworkStats stats = new NetworkStats(TEST_START, 2);

        assertEquals(0, stats.size());
        assertEquals(2, stats.internalSize());

        stats.addValues(TEST_IFACE, TEST_UID, SET_DEFAULT, TAG_NONE, 1L, 1L, 2L, 2L, 3);
        stats.addValues(TEST_IFACE, TEST_UID, SET_DEFAULT, TAG_NONE, 2L, 2L, 2L, 2L, 4);

        assertEquals(2, stats.size());
        assertEquals(2, stats.internalSize());

        stats.addValues(TEST_IFACE, TEST_UID, SET_DEFAULT, TAG_NONE, 3L, 30L, 4L, 40L, 7);
        stats.addValues(TEST_IFACE, TEST_UID, SET_DEFAULT, TAG_NONE, 4L, 40L, 4L, 40L, 8);
        stats.addValues(TEST_IFACE, TEST_UID, SET_DEFAULT, TAG_NONE, 5L, 50L, 5L, 50L, 10);

        assertEquals(5, stats.size());
        assertTrue(stats.internalSize() >= 5);

        assertValues(stats, 0, TEST_IFACE, TEST_UID, SET_DEFAULT, TAG_NONE, 1L, 1L, 2L, 2L, 3);
        assertValues(stats, 1, TEST_IFACE, TEST_UID, SET_DEFAULT, TAG_NONE, 2L, 2L, 2L, 2L, 4);
        assertValues(stats, 2, TEST_IFACE, TEST_UID, SET_DEFAULT, TAG_NONE, 3L, 30L, 4L, 40L, 7);
        assertValues(stats, 3, TEST_IFACE, TEST_UID, SET_DEFAULT, TAG_NONE, 4L, 40L, 4L, 40L, 8);
        assertValues(stats, 4, TEST_IFACE, TEST_UID, SET_DEFAULT, TAG_NONE, 5L, 50L, 5L, 50L, 10);
    }

    public void testCombineExisting() throws Exception {
        final NetworkStats stats = new NetworkStats(TEST_START, 10);

        stats.addValues(TEST_IFACE, 1001, SET_DEFAULT, TAG_NONE, 512L, 4L, 256L, 2L, 10);
        stats.addValues(TEST_IFACE, 1001, SET_DEFAULT, 0xff, 128L, 1L, 128L, 1L, 2);
        stats.combineValues(TEST_IFACE, 1001, SET_DEFAULT, TAG_NONE, -128L, -1L, -128L, -1L, -1);

        assertValues(stats, 0, TEST_IFACE, 1001, SET_DEFAULT, TAG_NONE, 384L, 3L, 128L, 1L, 9);
        assertValues(stats, 1, TEST_IFACE, 1001, SET_DEFAULT, 0xff, 128L, 1L, 128L, 1L, 2);

        // now try combining that should create row
        stats.combineValues(TEST_IFACE, 5005, SET_DEFAULT, TAG_NONE, 128L, 1L, 128L, 1L, 3);
        assertValues(stats, 2, TEST_IFACE, 5005, SET_DEFAULT, TAG_NONE, 128L, 1L, 128L, 1L, 3);
        stats.combineValues(TEST_IFACE, 5005, SET_DEFAULT, TAG_NONE, 128L, 1L, 128L, 1L, 3);
        assertValues(stats, 2, TEST_IFACE, 5005, SET_DEFAULT, TAG_NONE, 256L, 2L, 256L, 2L, 6);
    }

    public void testSubtractIdenticalData() throws Exception {
        final NetworkStats before = new NetworkStats(TEST_START, 2)
                .addValues(TEST_IFACE, 100, SET_DEFAULT, TAG_NONE, 1024L, 8L, 0L, 0L, 11)
                .addValues(TEST_IFACE, 101, SET_DEFAULT, TAG_NONE, 0L, 0L, 1024L, 8L, 12);

        final NetworkStats after = new NetworkStats(TEST_START, 2)
                .addValues(TEST_IFACE, 100, SET_DEFAULT, TAG_NONE, 1024L, 8L, 0L, 0L, 11)
                .addValues(TEST_IFACE, 101, SET_DEFAULT, TAG_NONE, 0L, 0L, 1024L, 8L, 12);

        final NetworkStats result = after.subtract(before);

        // identical data should result in zero delta
        assertValues(result, 0, TEST_IFACE, 100, SET_DEFAULT, TAG_NONE, 0L, 0L, 0L, 0L, 0);
        assertValues(result, 1, TEST_IFACE, 101, SET_DEFAULT, TAG_NONE, 0L, 0L, 0L, 0L, 0);
    }

    public void testSubtractIdenticalRows() throws Exception {
        final NetworkStats before = new NetworkStats(TEST_START, 2)
                .addValues(TEST_IFACE, 100, SET_DEFAULT, TAG_NONE, 1024L, 8L, 0L, 0L, 11)
                .addValues(TEST_IFACE, 101, SET_DEFAULT, TAG_NONE, 0L, 0L, 1024L, 8L, 12);

        final NetworkStats after = new NetworkStats(TEST_START, 2)
                .addValues(TEST_IFACE, 100, SET_DEFAULT, TAG_NONE, 1025L, 9L, 2L, 1L, 15)
                .addValues(TEST_IFACE, 101, SET_DEFAULT, TAG_NONE, 3L, 1L, 1028L, 9L, 20);

        final NetworkStats result = after.subtract(before);

        // expect delta between measurements
        assertValues(result, 0, TEST_IFACE, 100, SET_DEFAULT, TAG_NONE, 1L, 1L, 2L, 1L, 4);
        assertValues(result, 1, TEST_IFACE, 101, SET_DEFAULT, TAG_NONE, 3L, 1L, 4L, 1L, 8);
    }

    public void testSubtractNewRows() throws Exception {
        final NetworkStats before = new NetworkStats(TEST_START, 2)
                .addValues(TEST_IFACE, 100, SET_DEFAULT, TAG_NONE, 1024L, 8L, 0L, 0L, 11)
                .addValues(TEST_IFACE, 101, SET_DEFAULT, TAG_NONE, 0L, 0L, 1024L, 8L, 12);

        final NetworkStats after = new NetworkStats(TEST_START, 3)
                .addValues(TEST_IFACE, 100, SET_DEFAULT, TAG_NONE, 1024L, 8L, 0L, 0L, 11)
                .addValues(TEST_IFACE, 101, SET_DEFAULT, TAG_NONE, 0L, 0L, 1024L, 8L, 12)
                .addValues(TEST_IFACE, 102, SET_DEFAULT, TAG_NONE, 1024L, 8L, 1024L, 8L, 20);

        final NetworkStats result = after.subtract(before);

        // its okay to have new rows
        assertValues(result, 0, TEST_IFACE, 100, SET_DEFAULT, TAG_NONE, 0L, 0L, 0L, 0L, 0);
        assertValues(result, 1, TEST_IFACE, 101, SET_DEFAULT, TAG_NONE, 0L, 0L, 0L, 0L, 0);
        assertValues(result, 2, TEST_IFACE, 102, SET_DEFAULT, TAG_NONE, 1024L, 8L, 1024L, 8L, 20);
    }

    public void testSubtractMissingRows() throws Exception {
        final NetworkStats before = new NetworkStats(TEST_START, 2)
                .addValues(TEST_IFACE, UID_ALL, SET_DEFAULT, TAG_NONE, 1024L, 0L, 0L, 0L, 0)
                .addValues(TEST_IFACE2, UID_ALL, SET_DEFAULT, TAG_NONE, 2048L, 0L, 0L, 0L, 0);

        final NetworkStats after = new NetworkStats(TEST_START, 1)
                .addValues(TEST_IFACE2, UID_ALL, SET_DEFAULT, TAG_NONE, 2049L, 2L, 3L, 4L, 0);

        final NetworkStats result = after.subtract(before);

        // should silently drop omitted rows
        assertEquals(1, result.size());
        assertValues(result, 0, TEST_IFACE2, UID_ALL, SET_DEFAULT, TAG_NONE, 1L, 2L, 3L, 4L, 0);
        assertEquals(4L, result.getTotalBytes());
    }

    public void testTotalBytes() throws Exception {
        final NetworkStats iface = new NetworkStats(TEST_START, 2)
                .addValues(TEST_IFACE, UID_ALL, SET_DEFAULT, TAG_NONE, 128L, 0L, 0L, 0L, 0L)
                .addValues(TEST_IFACE2, UID_ALL, SET_DEFAULT, TAG_NONE, 256L, 0L, 0L, 0L, 0L);
        assertEquals(384L, iface.getTotalBytes());

        final NetworkStats uidSet = new NetworkStats(TEST_START, 3)
                .addValues(TEST_IFACE, 100, SET_DEFAULT, TAG_NONE, 32L, 0L, 0L, 0L, 0L)
                .addValues(TEST_IFACE, 101, SET_DEFAULT, TAG_NONE, 32L, 0L, 0L, 0L, 0L)
                .addValues(TEST_IFACE, 101, SET_FOREGROUND, TAG_NONE, 32L, 0L, 0L, 0L, 0L);
        assertEquals(96L, uidSet.getTotalBytes());

        final NetworkStats uidTag = new NetworkStats(TEST_START, 3)
                .addValues(TEST_IFACE, 100, SET_DEFAULT, TAG_NONE, 16L, 0L, 0L, 0L, 0L)
                .addValues(TEST_IFACE2, 100, SET_DEFAULT, TAG_NONE, 16L, 0L, 0L, 0L, 0L)
                .addValues(TEST_IFACE2, 100, SET_DEFAULT, 0xF00D, 8L, 0L, 0L, 0L, 0L)
                .addValues(TEST_IFACE2, 100, SET_FOREGROUND, TAG_NONE, 16L, 0L, 0L, 0L, 0L)
                .addValues(TEST_IFACE, 101, SET_DEFAULT, TAG_NONE, 16L, 0L, 0L, 0L, 0L)
                .addValues(TEST_IFACE, 101, SET_DEFAULT, 0xF00D, 8L, 0L, 0L, 0L, 0L);
        assertEquals(64L, uidTag.getTotalBytes());
    }

    private static void assertValues(NetworkStats stats, int index, String iface, int uid, int set,
            int tag, long rxBytes, long rxPackets, long txBytes, long txPackets, int operations) {
        final NetworkStats.Entry entry = stats.getValues(index, null);
        assertEquals(iface, entry.iface);
        assertEquals(uid, entry.uid);
        assertEquals(set, entry.set);
        assertEquals(tag, entry.tag);
        assertEquals(rxBytes, entry.rxBytes);
        assertEquals(rxPackets, entry.rxPackets);
        assertEquals(txBytes, entry.txBytes);
        assertEquals(txPackets, entry.txPackets);
        assertEquals(operations, entry.operations);
    }

}
