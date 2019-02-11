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

import static android.net.NetworkStats.DEFAULT_NETWORK_ALL;
import static android.net.NetworkStats.DEFAULT_NETWORK_NO;
import static android.net.NetworkStats.DEFAULT_NETWORK_YES;
import static android.net.NetworkStats.INTERFACES_ALL;
import static android.net.NetworkStats.METERED_ALL;
import static android.net.NetworkStats.METERED_NO;
import static android.net.NetworkStats.METERED_YES;
import static android.net.NetworkStats.ROAMING_ALL;
import static android.net.NetworkStats.ROAMING_NO;
import static android.net.NetworkStats.ROAMING_YES;
import static android.net.NetworkStats.SET_DEFAULT;
import static android.net.NetworkStats.SET_FOREGROUND;
import static android.net.NetworkStats.SET_DBG_VPN_IN;
import static android.net.NetworkStats.SET_DBG_VPN_OUT;
import static android.net.NetworkStats.SET_ALL;
import static android.net.NetworkStats.IFACE_ALL;
import static android.net.NetworkStats.TAG_ALL;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.NetworkStats.UID_ALL;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.net.NetworkStats.Entry;
import android.os.Process;
import android.support.test.runner.AndroidJUnit4;
import android.support.test.filters.SmallTest;
import android.util.ArrayMap;

import com.google.android.collect.Sets;

import java.util.HashSet;

import org.junit.runner.RunWith;
import org.junit.Test;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class NetworkStatsTest {

    private static final String TEST_IFACE = "test0";
    private static final String TEST_IFACE2 = "test2";
    private static final int TEST_UID = 1001;
    private static final long TEST_START = 1194220800000L;

    @Test
    public void testFindIndex() throws Exception {
        final NetworkStats stats = new NetworkStats(TEST_START, 5)
                .addValues(TEST_IFACE, 100, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                        DEFAULT_NETWORK_YES, 1024L, 8L, 0L, 0L, 10)
                .addValues(TEST_IFACE, 101, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                        DEFAULT_NETWORK_NO, 0L, 0L, 1024L, 8L, 11)
                .addValues(TEST_IFACE, 101, SET_DEFAULT, TAG_NONE, METERED_YES, ROAMING_NO,
                        DEFAULT_NETWORK_YES, 0L, 0L, 1024L, 8L, 11)
                .addValues(TEST_IFACE, 102, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                        DEFAULT_NETWORK_NO, 1024L, 8L, 1024L, 8L, 12)
                .addValues(TEST_IFACE, 102, SET_DEFAULT, TAG_NONE, METERED_YES, ROAMING_YES,
                        DEFAULT_NETWORK_YES, 1024L, 8L, 1024L, 8L, 12);

        assertEquals(4, stats.findIndex(TEST_IFACE, 102, SET_DEFAULT, TAG_NONE, METERED_YES,
                ROAMING_YES, DEFAULT_NETWORK_YES));
        assertEquals(3, stats.findIndex(TEST_IFACE, 102, SET_DEFAULT, TAG_NONE, METERED_NO,
                ROAMING_NO, DEFAULT_NETWORK_NO));
        assertEquals(2, stats.findIndex(TEST_IFACE, 101, SET_DEFAULT, TAG_NONE, METERED_YES,
                ROAMING_NO, DEFAULT_NETWORK_YES));
        assertEquals(1, stats.findIndex(TEST_IFACE, 101, SET_DEFAULT, TAG_NONE, METERED_NO,
                ROAMING_NO, DEFAULT_NETWORK_NO));
        assertEquals(0, stats.findIndex(TEST_IFACE, 100, SET_DEFAULT, TAG_NONE, METERED_NO,
                ROAMING_NO, DEFAULT_NETWORK_YES));
        assertEquals(-1, stats.findIndex(TEST_IFACE, 6, SET_DEFAULT, TAG_NONE, METERED_NO,
                ROAMING_NO, DEFAULT_NETWORK_NO));
        assertEquals(-1, stats.findIndex(TEST_IFACE, 100, SET_DEFAULT, TAG_NONE, METERED_NO,
                ROAMING_NO, DEFAULT_NETWORK_NO));
    }

    @Test
    public void testFindIndexHinted() {
        final NetworkStats stats = new NetworkStats(TEST_START, 3)
                .addValues(TEST_IFACE, 100, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                        DEFAULT_NETWORK_YES, 1024L, 8L, 0L, 0L, 10)
                .addValues(TEST_IFACE, 101, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                        DEFAULT_NETWORK_NO, 0L, 0L, 1024L, 8L, 11)
                .addValues(TEST_IFACE, 102, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                        DEFAULT_NETWORK_YES, 1024L, 8L, 1024L, 8L, 12)
                .addValues(TEST_IFACE2, 100, SET_FOREGROUND, TAG_NONE, METERED_NO, ROAMING_NO,
                        DEFAULT_NETWORK_NO, 1024L, 8L, 0L, 0L, 10)
                .addValues(TEST_IFACE2, 101, SET_DEFAULT, 0xF00D, METERED_NO, ROAMING_NO,
                        DEFAULT_NETWORK_YES, 0L, 0L, 1024L, 8L, 11)
                .addValues(TEST_IFACE2, 101, SET_DEFAULT, 0xF00D, METERED_YES, ROAMING_NO,
                        DEFAULT_NETWORK_NO, 0L, 0L, 1024L, 8L, 11)
                .addValues(TEST_IFACE2, 102, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                        DEFAULT_NETWORK_YES, 1024L, 8L, 1024L, 8L, 12)
                .addValues(TEST_IFACE2, 102, SET_DEFAULT, TAG_NONE, METERED_YES, ROAMING_YES,
                        DEFAULT_NETWORK_NO, 1024L, 8L, 1024L, 8L, 12);

        // verify that we correctly find across regardless of hinting
        for (int hint = 0; hint < stats.size(); hint++) {
            assertEquals(0, stats.findIndexHinted(TEST_IFACE, 100, SET_DEFAULT, TAG_NONE,
                    METERED_NO, ROAMING_NO, DEFAULT_NETWORK_YES, hint));
            assertEquals(1, stats.findIndexHinted(TEST_IFACE, 101, SET_DEFAULT, TAG_NONE,
                    METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, hint));
            assertEquals(2, stats.findIndexHinted(TEST_IFACE, 102, SET_DEFAULT, TAG_NONE,
                    METERED_NO, ROAMING_NO, DEFAULT_NETWORK_YES, hint));
            assertEquals(3, stats.findIndexHinted(TEST_IFACE2, 100, SET_FOREGROUND, TAG_NONE,
                    METERED_NO, ROAMING_NO, DEFAULT_NETWORK_NO, hint));
            assertEquals(4, stats.findIndexHinted(TEST_IFACE2, 101, SET_DEFAULT, 0xF00D,
                    METERED_NO, ROAMING_NO, DEFAULT_NETWORK_YES, hint));
            assertEquals(5, stats.findIndexHinted(TEST_IFACE2, 101, SET_DEFAULT, 0xF00D,
                    METERED_YES, ROAMING_NO, DEFAULT_NETWORK_NO, hint));
            assertEquals(6, stats.findIndexHinted(TEST_IFACE2, 102, SET_DEFAULT, TAG_NONE,
                    METERED_NO, ROAMING_NO, DEFAULT_NETWORK_YES, hint));
            assertEquals(7, stats.findIndexHinted(TEST_IFACE2, 102, SET_DEFAULT, TAG_NONE,
                    METERED_YES, ROAMING_YES, DEFAULT_NETWORK_NO, hint));
            assertEquals(-1, stats.findIndexHinted(TEST_IFACE, 6, SET_DEFAULT, TAG_NONE,
                    METERED_NO, ROAMING_NO, DEFAULT_NETWORK_YES, hint));
            assertEquals(-1, stats.findIndexHinted(TEST_IFACE2, 102, SET_DEFAULT, TAG_NONE,
                    METERED_YES, ROAMING_YES, DEFAULT_NETWORK_YES, hint));
        }
    }

    @Test
    public void testAddEntryGrow() throws Exception {
        final NetworkStats stats = new NetworkStats(TEST_START, 4);

        assertEquals(0, stats.size());
        assertEquals(4, stats.internalSize());

        stats.addValues(TEST_IFACE, TEST_UID, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_YES, 1L, 1L, 2L, 2L, 3);
        stats.addValues(TEST_IFACE, TEST_UID, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 2L, 2L, 2L, 2L, 4);
        stats.addValues(TEST_IFACE, TEST_UID, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_YES,
                DEFAULT_NETWORK_YES, 3L, 3L, 2L, 2L, 5);
        stats.addValues(TEST_IFACE, TEST_UID, SET_DEFAULT, TAG_NONE, METERED_YES, ROAMING_YES,
                DEFAULT_NETWORK_NO, 3L, 3L, 2L, 2L, 5);

        assertEquals(4, stats.size());
        assertEquals(4, stats.internalSize());

        stats.addValues(TEST_IFACE, TEST_UID, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 4L, 40L, 4L, 40L, 7);
        stats.addValues(TEST_IFACE, TEST_UID, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_YES, 5L, 50L, 4L, 40L, 8);
        stats.addValues(TEST_IFACE, TEST_UID, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 6L, 60L, 5L, 50L, 10);
        stats.addValues(TEST_IFACE, TEST_UID, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_YES,
                DEFAULT_NETWORK_YES, 7L, 70L, 5L, 50L, 11);
        stats.addValues(TEST_IFACE, TEST_UID, SET_DEFAULT, TAG_NONE, METERED_YES, ROAMING_YES,
                DEFAULT_NETWORK_NO, 7L, 70L, 5L, 50L, 11);

        assertEquals(9, stats.size());
        assertTrue(stats.internalSize() >= 9);

        assertValues(stats, 0, TEST_IFACE, TEST_UID, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_YES, 1L, 1L, 2L, 2L, 3);
        assertValues(stats, 1, TEST_IFACE, TEST_UID, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 2L, 2L, 2L, 2L, 4);
        assertValues(stats, 2, TEST_IFACE, TEST_UID, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_YES,
                DEFAULT_NETWORK_YES, 3L, 3L, 2L, 2L, 5);
        assertValues(stats, 3, TEST_IFACE, TEST_UID, SET_DEFAULT, TAG_NONE, METERED_YES,
                ROAMING_YES, DEFAULT_NETWORK_NO, 3L, 3L, 2L, 2L, 5);
        assertValues(stats, 4, TEST_IFACE, TEST_UID, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 4L, 40L, 4L, 40L, 7);
        assertValues(stats, 5, TEST_IFACE, TEST_UID, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_YES, 5L, 50L, 4L, 40L, 8);
        assertValues(stats, 6, TEST_IFACE, TEST_UID, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 6L, 60L, 5L, 50L, 10);
        assertValues(stats, 7, TEST_IFACE, TEST_UID, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_YES,
                DEFAULT_NETWORK_YES, 7L, 70L, 5L, 50L, 11);
        assertValues(stats, 8, TEST_IFACE, TEST_UID, SET_DEFAULT, TAG_NONE, METERED_YES,
                ROAMING_YES, DEFAULT_NETWORK_NO, 7L, 70L, 5L, 50L, 11);
    }

    @Test
    public void testCombineExisting() throws Exception {
        final NetworkStats stats = new NetworkStats(TEST_START, 10);

        stats.addValues(TEST_IFACE, 1001, SET_DEFAULT, TAG_NONE, 512L, 4L, 256L, 2L, 10);
        stats.addValues(TEST_IFACE, 1001, SET_DEFAULT, 0xff, 128L, 1L, 128L, 1L, 2);
        stats.combineValues(TEST_IFACE, 1001, SET_DEFAULT, TAG_NONE, -128L, -1L,
                -128L, -1L, -1);

        assertValues(stats, 0, TEST_IFACE, 1001, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 384L, 3L, 128L, 1L, 9);
        assertValues(stats, 1, TEST_IFACE, 1001, SET_DEFAULT, 0xff, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 128L, 1L, 128L, 1L, 2);

        // now try combining that should create row
        stats.combineValues(TEST_IFACE, 5005, SET_DEFAULT, TAG_NONE, 128L, 1L, 128L, 1L, 3);
        assertValues(stats, 2, TEST_IFACE, 5005, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 128L, 1L, 128L, 1L, 3);
        stats.combineValues(TEST_IFACE, 5005, SET_DEFAULT, TAG_NONE, 128L, 1L, 128L, 1L, 3);
        assertValues(stats, 2, TEST_IFACE, 5005, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 256L, 2L, 256L, 2L, 6);
    }

    @Test
    public void testSubtractIdenticalData() throws Exception {
        final NetworkStats before = new NetworkStats(TEST_START, 2)
                .addValues(TEST_IFACE, 100, SET_DEFAULT, TAG_NONE, 1024L, 8L, 0L, 0L, 11)
                .addValues(TEST_IFACE, 101, SET_DEFAULT, TAG_NONE, 0L, 0L, 1024L, 8L, 12);

        final NetworkStats after = new NetworkStats(TEST_START, 2)
                .addValues(TEST_IFACE, 100, SET_DEFAULT, TAG_NONE, 1024L, 8L, 0L, 0L, 11)
                .addValues(TEST_IFACE, 101, SET_DEFAULT, TAG_NONE, 0L, 0L, 1024L, 8L, 12);

        final NetworkStats result = after.subtract(before);

        // identical data should result in zero delta
        assertValues(result, 0, TEST_IFACE, 100, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 0L, 0L, 0L, 0L, 0);
        assertValues(result, 1, TEST_IFACE, 101, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 0L, 0L, 0L, 0L, 0);
    }

    @Test
    public void testSubtractIdenticalRows() throws Exception {
        final NetworkStats before = new NetworkStats(TEST_START, 2)
                .addValues(TEST_IFACE, 100, SET_DEFAULT, TAG_NONE, 1024L, 8L, 0L, 0L, 11)
                .addValues(TEST_IFACE, 101, SET_DEFAULT, TAG_NONE, 0L, 0L, 1024L, 8L, 12);

        final NetworkStats after = new NetworkStats(TEST_START, 2)
                .addValues(TEST_IFACE, 100, SET_DEFAULT, TAG_NONE, 1025L, 9L, 2L, 1L, 15)
                .addValues(TEST_IFACE, 101, SET_DEFAULT, TAG_NONE, 3L, 1L, 1028L, 9L, 20);

        final NetworkStats result = after.subtract(before);

        // expect delta between measurements
        assertValues(result, 0, TEST_IFACE, 100, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 1L, 1L, 2L, 1L, 4);
        assertValues(result, 1, TEST_IFACE, 101, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 3L, 1L, 4L, 1L, 8);
    }

    @Test
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
        assertValues(result, 0, TEST_IFACE, 100, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 0L, 0L, 0L, 0L, 0);
        assertValues(result, 1, TEST_IFACE, 101, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 0L, 0L, 0L, 0L, 0);
        assertValues(result, 2, TEST_IFACE, 102, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 1024L, 8L, 1024L, 8L, 20);
    }

    @Test
    public void testSubtractMissingRows() throws Exception {
        final NetworkStats before = new NetworkStats(TEST_START, 2)
                .addValues(TEST_IFACE, UID_ALL, SET_DEFAULT, TAG_NONE, 1024L, 0L, 0L, 0L, 0)
                .addValues(TEST_IFACE2, UID_ALL, SET_DEFAULT, TAG_NONE, 2048L, 0L, 0L, 0L, 0);

        final NetworkStats after = new NetworkStats(TEST_START, 1)
                .addValues(TEST_IFACE2, UID_ALL, SET_DEFAULT, TAG_NONE, 2049L, 2L, 3L, 4L, 0);

        final NetworkStats result = after.subtract(before);

        // should silently drop omitted rows
        assertEquals(1, result.size());
        assertValues(result, 0, TEST_IFACE2, UID_ALL, SET_DEFAULT, TAG_NONE, METERED_NO,
                ROAMING_NO, DEFAULT_NETWORK_NO, 1L, 2L, 3L, 4L, 0);
        assertEquals(4L, result.getTotalBytes());
    }

    @Test
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

        final NetworkStats uidTag = new NetworkStats(TEST_START, 6)
                .addValues(TEST_IFACE, 100, SET_DEFAULT, TAG_NONE, 16L, 0L, 0L, 0L, 0L)
                .addValues(TEST_IFACE2, 100, SET_DEFAULT, TAG_NONE, 16L, 0L, 0L, 0L, 0L)
                .addValues(TEST_IFACE2, 100, SET_DEFAULT, 0xF00D, 8L, 0L, 0L, 0L, 0L)
                .addValues(TEST_IFACE2, 100, SET_FOREGROUND, TAG_NONE, 16L, 0L, 0L, 0L, 0L)
                .addValues(TEST_IFACE, 101, SET_DEFAULT, TAG_NONE, 16L, 0L, 0L, 0L, 0L)
                .addValues(TEST_IFACE, 101, SET_DEFAULT, 0xF00D, 8L, 0L, 0L, 0L, 0L);
        assertEquals(64L, uidTag.getTotalBytes());

        final NetworkStats uidMetered = new NetworkStats(TEST_START, 3)
                .addValues(TEST_IFACE, 100, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                        DEFAULT_NETWORK_YES, 32L, 0L, 0L, 0L, 0L)
                .addValues(TEST_IFACE, 101, SET_DEFAULT, TAG_NONE, METERED_YES, ROAMING_NO,
                        DEFAULT_NETWORK_NO, 32L, 0L, 0L, 0L, 0L)
                .addValues(TEST_IFACE, 101, SET_DEFAULT, TAG_NONE, METERED_YES, ROAMING_NO,
                        DEFAULT_NETWORK_YES, 32L, 0L, 0L, 0L, 0L);
        assertEquals(96L, uidMetered.getTotalBytes());

        final NetworkStats uidRoaming = new NetworkStats(TEST_START, 3)
                .addValues(TEST_IFACE, 100, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                        DEFAULT_NETWORK_YES, 32L, 0L, 0L, 0L, 0L)
                .addValues(TEST_IFACE, 101, SET_DEFAULT, TAG_NONE, METERED_YES, ROAMING_NO,
                        DEFAULT_NETWORK_NO, 32L, 0L, 0L, 0L, 0L)
                .addValues(TEST_IFACE, 101, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_YES,
                        DEFAULT_NETWORK_YES, 32L, 0L, 0L, 0L, 0L);
        assertEquals(96L, uidRoaming.getTotalBytes());
    }

    @Test
    public void testGroupedByIfaceEmpty() throws Exception {
        final NetworkStats uidStats = new NetworkStats(TEST_START, 3);
        final NetworkStats grouped = uidStats.groupedByIface();

        assertEquals(0, uidStats.size());
        assertEquals(0, grouped.size());
    }

    @Test
    public void testGroupedByIfaceAll() throws Exception {
        final NetworkStats uidStats = new NetworkStats(TEST_START, 3)
                .addValues(IFACE_ALL, 100, SET_ALL, TAG_NONE, METERED_NO, ROAMING_NO,
                        DEFAULT_NETWORK_YES, 128L, 8L, 0L, 2L, 20L)
                .addValues(IFACE_ALL, 101, SET_FOREGROUND, TAG_NONE, METERED_YES, ROAMING_NO,
                        DEFAULT_NETWORK_NO, 128L, 8L, 0L, 2L, 20L)
                .addValues(IFACE_ALL, 101, SET_ALL, TAG_NONE, METERED_NO, ROAMING_YES,
                        DEFAULT_NETWORK_YES, 128L, 8L, 0L, 2L, 20L);
        final NetworkStats grouped = uidStats.groupedByIface();

        assertEquals(3, uidStats.size());
        assertEquals(1, grouped.size());

        assertValues(grouped, 0, IFACE_ALL, UID_ALL, SET_ALL, TAG_NONE, METERED_ALL, ROAMING_ALL,
                DEFAULT_NETWORK_ALL, 384L, 24L, 0L, 6L, 0L);
    }

    @Test
    public void testGroupedByIface() throws Exception {
        final NetworkStats uidStats = new NetworkStats(TEST_START, 7)
                .addValues(TEST_IFACE, 100, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                        DEFAULT_NETWORK_YES, 128L, 8L, 0L, 2L, 20L)
                .addValues(TEST_IFACE2, 100, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                        DEFAULT_NETWORK_NO, 512L, 32L, 0L, 0L, 0L)
                .addValues(TEST_IFACE2, 100, SET_DEFAULT, 0xF00D, METERED_NO, ROAMING_NO,
                        DEFAULT_NETWORK_YES, 64L, 4L, 0L, 0L, 0L)
                .addValues(TEST_IFACE2, 100, SET_FOREGROUND, TAG_NONE, METERED_NO, ROAMING_NO,
                        DEFAULT_NETWORK_NO, 512L, 32L, 0L, 0L, 0L)
                .addValues(TEST_IFACE, 101, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                        DEFAULT_NETWORK_YES, 128L, 8L, 0L, 0L, 0L)
                .addValues(TEST_IFACE, 101, SET_DEFAULT, 0xF00D, METERED_YES, ROAMING_NO,
                        DEFAULT_NETWORK_NO, 128L, 8L, 0L, 0L, 0L)
                .addValues(TEST_IFACE, 101, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_YES,
                        DEFAULT_NETWORK_YES, 128L, 8L, 0L, 0L, 0L);

        final NetworkStats grouped = uidStats.groupedByIface();

        assertEquals(7, uidStats.size());

        assertEquals(2, grouped.size());
        assertValues(grouped, 0, TEST_IFACE, UID_ALL, SET_ALL, TAG_NONE, METERED_ALL, ROAMING_ALL,
                DEFAULT_NETWORK_ALL, 384L, 24L, 0L, 2L, 0L);
        assertValues(grouped, 1, TEST_IFACE2, UID_ALL, SET_ALL, TAG_NONE, METERED_ALL, ROAMING_ALL,
                DEFAULT_NETWORK_ALL, 1024L, 64L, 0L, 0L, 0L);
    }

    @Test
    public void testAddAllValues() {
        final NetworkStats first = new NetworkStats(TEST_START, 5)
                .addValues(TEST_IFACE, 100, SET_DEFAULT, TAG_NONE, METERED_YES, ROAMING_NO,
                        DEFAULT_NETWORK_YES, 32L, 0L, 0L, 0L, 0L)
                .addValues(TEST_IFACE, 100, SET_FOREGROUND, TAG_NONE, METERED_NO, ROAMING_NO,
                        DEFAULT_NETWORK_NO, 32L, 0L, 0L, 0L, 0L)
                .addValues(TEST_IFACE, 100, SET_FOREGROUND, TAG_NONE, METERED_YES, ROAMING_YES,
                        DEFAULT_NETWORK_YES, 32L, 0L, 0L, 0L, 0L);

        final NetworkStats second = new NetworkStats(TEST_START, 2)
                .addValues(TEST_IFACE, 100, SET_DEFAULT, TAG_NONE, METERED_YES, ROAMING_NO,
                        DEFAULT_NETWORK_YES, 32L, 0L, 0L, 0L, 0L)
                .addValues(TEST_IFACE2, UID_ALL, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                        DEFAULT_NETWORK_NO, 32L, 0L, 0L, 0L, 0L)
                .addValues(TEST_IFACE, 100, SET_FOREGROUND, TAG_NONE, METERED_YES, ROAMING_YES,
                        DEFAULT_NETWORK_YES, 32L, 0L, 0L, 0L, 0L);

        first.combineAllValues(second);

        assertEquals(4, first.size());
        assertValues(first, 0, TEST_IFACE, 100, SET_DEFAULT, TAG_NONE, METERED_YES, ROAMING_NO,
                DEFAULT_NETWORK_YES, 64L, 0L, 0L, 0L, 0L);
        assertValues(first, 1, TEST_IFACE, 100, SET_FOREGROUND, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 32L, 0L, 0L, 0L, 0L);
        assertValues(first, 2, TEST_IFACE, 100, SET_FOREGROUND, TAG_NONE, METERED_YES, ROAMING_YES,
                DEFAULT_NETWORK_YES, 64L, 0L, 0L, 0L, 0L);
        assertValues(first, 3, TEST_IFACE2, UID_ALL, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 32L, 0L, 0L, 0L, 0L);
    }

    @Test
    public void testGetTotal() {
        final NetworkStats stats = new NetworkStats(TEST_START, 7)
                .addValues(TEST_IFACE, 100, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                        DEFAULT_NETWORK_YES, 128L, 8L, 0L, 2L, 20L)
                .addValues(TEST_IFACE2, 100, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                        DEFAULT_NETWORK_NO, 512L, 32L, 0L, 0L, 0L)
                .addValues(TEST_IFACE2, 100, SET_DEFAULT, 0xF00D, METERED_NO, ROAMING_NO,
                        DEFAULT_NETWORK_YES, 64L, 4L, 0L, 0L, 0L)
                .addValues(TEST_IFACE2, 100, SET_FOREGROUND, TAG_NONE, METERED_NO, ROAMING_NO,
                        DEFAULT_NETWORK_NO, 512L,32L, 0L, 0L, 0L)
                .addValues(TEST_IFACE, 101, SET_DEFAULT, TAG_NONE, METERED_YES, ROAMING_NO,
                        DEFAULT_NETWORK_YES, 128L, 8L, 0L, 0L, 0L)
                .addValues(TEST_IFACE, 101, SET_DEFAULT, 0xF00D, METERED_NO, ROAMING_NO,
                        DEFAULT_NETWORK_NO, 128L, 8L, 0L, 0L, 0L)
                .addValues(TEST_IFACE, 100, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_YES,
                        DEFAULT_NETWORK_NO, 128L, 8L, 0L, 0L, 0L);

        assertValues(stats.getTotal(null), 1408L, 88L, 0L, 2L, 20L);
        assertValues(stats.getTotal(null, 100), 1280L, 80L, 0L, 2L, 20L);
        assertValues(stats.getTotal(null, 101), 128L, 8L, 0L, 0L, 0L);

        final HashSet<String> ifaces = Sets.newHashSet();
        assertValues(stats.getTotal(null, ifaces), 0L, 0L, 0L, 0L, 0L);

        ifaces.add(TEST_IFACE2);
        assertValues(stats.getTotal(null, ifaces), 1024L, 64L, 0L, 0L, 0L);
    }

    @Test
    public void testWithoutUid() throws Exception {
        final NetworkStats before = new NetworkStats(TEST_START, 3)
                .addValues(TEST_IFACE, 100, SET_DEFAULT, TAG_NONE, 128L, 8L, 0L, 2L, 20L)
                .addValues(TEST_IFACE2, 100, SET_DEFAULT, TAG_NONE, 512L, 32L, 0L, 0L, 0L)
                .addValues(TEST_IFACE2, 100, SET_DEFAULT, 0xF00D, 64L, 4L, 0L, 0L, 0L)
                .addValues(TEST_IFACE2, 100, SET_FOREGROUND, TAG_NONE, 512L, 32L, 0L, 0L, 0L)
                .addValues(TEST_IFACE, 101, SET_DEFAULT, TAG_NONE, 128L, 8L, 0L, 0L, 0L)
                .addValues(TEST_IFACE, 101, SET_DEFAULT, 0xF00D, 128L, 8L, 0L, 0L, 0L);

        final NetworkStats after = before.withoutUids(new int[] { 100 });
        assertEquals(6, before.size());
        assertEquals(2, after.size());
        assertValues(after, 0, TEST_IFACE, 101, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 128L, 8L, 0L, 0L, 0L);
        assertValues(after, 1, TEST_IFACE, 101, SET_DEFAULT, 0xF00D, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 128L, 8L, 0L, 0L, 0L);
    }

    @Test
    public void testClone() throws Exception {
        final NetworkStats original = new NetworkStats(TEST_START, 5)
                .addValues(TEST_IFACE, 100, SET_DEFAULT, TAG_NONE, 128L, 8L, 0L, 2L, 20L)
                .addValues(TEST_IFACE2, 100, SET_DEFAULT, TAG_NONE, 512L, 32L, 0L, 0L, 0L);

        // make clone and mutate original
        final NetworkStats clone = original.clone();
        original.addValues(TEST_IFACE, 101, SET_DEFAULT, TAG_NONE, 128L, 8L, 0L, 0L, 0L);

        assertEquals(3, original.size());
        assertEquals(2, clone.size());

        assertEquals(128L + 512L + 128L, original.getTotalBytes());
        assertEquals(128L + 512L, clone.getTotalBytes());
    }

    @Test
    public void testAddWhenEmpty() throws Exception {
        final NetworkStats red = new NetworkStats(TEST_START, -1);
        final NetworkStats blue = new NetworkStats(TEST_START, 5)
                .addValues(TEST_IFACE, 100, SET_DEFAULT, TAG_NONE, 128L, 8L, 0L, 2L, 20L)
                .addValues(TEST_IFACE2, 100, SET_DEFAULT, TAG_NONE, 512L, 32L, 0L, 0L, 0L);

        // We're mostly checking that we don't crash
        red.combineAllValues(blue);
    }

    @Test
    public void testMigrateTun() throws Exception {
        final int tunUid = 10030;
        final String tunIface = "tun0";
        final String underlyingIface = "wlan0";
        final int testTag1 = 8888;
        NetworkStats delta = new NetworkStats(TEST_START, 17)
            .addValues(tunIface, 10100, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                    DEFAULT_NETWORK_NO, 39605L, 46L, 12259L, 55L, 0L)
            .addValues(tunIface, 10100, SET_FOREGROUND, TAG_NONE, METERED_NO, ROAMING_NO,
                    DEFAULT_NETWORK_NO, 0L, 0L, 0L, 0L, 0L)
            .addValues(tunIface, 10120, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                    DEFAULT_NETWORK_NO, 72667L, 197L, 43909L, 241L, 0L)
            .addValues(tunIface, 10120, SET_FOREGROUND, TAG_NONE, METERED_NO, ROAMING_NO,
                    DEFAULT_NETWORK_NO, 9297L, 17L, 4128L, 21L, 0L)
            // VPN package also uses some traffic through unprotected network.
            .addValues(tunIface, tunUid, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                    DEFAULT_NETWORK_NO, 4983L, 10L, 1801L, 12L, 0L)
            .addValues(tunIface, tunUid, SET_FOREGROUND, TAG_NONE, METERED_NO, ROAMING_NO,
                    DEFAULT_NETWORK_NO, 0L, 0L, 0L, 0L, 0L)
            // Tag entries
            .addValues(tunIface, 10120, SET_DEFAULT, testTag1, METERED_NO, ROAMING_NO,
                    DEFAULT_NETWORK_NO, 21691L, 41L, 13820L, 51L, 0L)
            .addValues(tunIface, 10120, SET_FOREGROUND, testTag1, METERED_NO, ROAMING_NO,
                    DEFAULT_NETWORK_NO, 1281L, 2L, 665L, 2L, 0L)
            // Irrelevant entries
            .addValues(TEST_IFACE, 10100, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                    DEFAULT_NETWORK_NO, 1685L, 5L, 2070L, 6L, 0L)
            // Underlying Iface entries
            .addValues(underlyingIface, 10100, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                    DEFAULT_NETWORK_NO, 5178L, 8L, 2139L, 11L, 0L)
            .addValues(underlyingIface, 10100, SET_FOREGROUND, TAG_NONE, METERED_NO, ROAMING_NO,
                    DEFAULT_NETWORK_NO, 0L, 0L, 0L, 0L, 0L)
            .addValues(underlyingIface, tunUid, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                    DEFAULT_NETWORK_NO, 149873L, 287L, 59217L /* smaller than sum(tun0) */,
                    299L /* smaller than sum(tun0) */, 0L)
            .addValues(underlyingIface, tunUid, SET_FOREGROUND, TAG_NONE, METERED_NO, ROAMING_NO,
                    DEFAULT_NETWORK_NO, 0L, 0L, 0L, 0L, 0L);

        assertTrue(delta.toString(), delta.migrateTun(tunUid, tunIface, underlyingIface));
        assertEquals(20, delta.size());

        // tunIface and TEST_IFACE entries are not changed.
        assertValues(delta, 0, tunIface, 10100, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 39605L, 46L, 12259L, 55L, 0L);
        assertValues(delta, 1, tunIface, 10100, SET_FOREGROUND, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 0L, 0L, 0L, 0L, 0L);
        assertValues(delta, 2, tunIface, 10120, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 72667L, 197L, 43909L, 241L, 0L);
        assertValues(delta, 3, tunIface, 10120, SET_FOREGROUND, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 9297L, 17L, 4128L, 21L, 0L);
        assertValues(delta, 4, tunIface, tunUid, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 4983L, 10L, 1801L, 12L, 0L);
        assertValues(delta, 5, tunIface, tunUid, SET_FOREGROUND, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 0L, 0L, 0L, 0L, 0L);
        assertValues(delta, 6, tunIface, 10120, SET_DEFAULT, testTag1, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 21691L, 41L, 13820L, 51L, 0L);
        assertValues(delta, 7, tunIface, 10120, SET_FOREGROUND, testTag1, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 1281L, 2L, 665L, 2L, 0L);
        assertValues(delta, 8, TEST_IFACE, 10100, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 1685L, 5L, 2070L, 6L, 0L);

        // Existing underlying Iface entries are updated
        assertValues(delta, 9, underlyingIface, 10100, SET_DEFAULT, TAG_NONE, METERED_NO,
                ROAMING_NO, DEFAULT_NETWORK_NO, 44783L, 54L, 14178L, 62L, 0L);
        assertValues(delta, 10, underlyingIface, 10100, SET_FOREGROUND, TAG_NONE, METERED_NO,
                ROAMING_NO, DEFAULT_NETWORK_NO, 0L, 0L, 0L, 0L, 0L);

        // VPN underlying Iface entries are updated
        assertValues(delta, 11, underlyingIface, tunUid, SET_DEFAULT, TAG_NONE, METERED_NO,
                ROAMING_NO, DEFAULT_NETWORK_NO, 28304L, 27L, 1L, 2L, 0L);
        assertValues(delta, 12, underlyingIface, tunUid, SET_FOREGROUND, TAG_NONE, METERED_NO,
                ROAMING_NO, DEFAULT_NETWORK_NO, 0L, 0L, 0L, 0L, 0L);

        // New entries are added for new application's underlying Iface traffic
        assertContains(delta, underlyingIface, 10120, SET_DEFAULT, TAG_NONE, METERED_NO,
                ROAMING_NO, DEFAULT_NETWORK_NO, 72667L, 197L, 43123L, 227L, 0L);
        assertContains(delta, underlyingIface, 10120, SET_FOREGROUND, TAG_NONE, METERED_NO,
                ROAMING_NO, DEFAULT_NETWORK_NO, 9297L, 17L, 4054, 19L, 0L);
        assertContains(delta, underlyingIface, 10120, SET_DEFAULT, testTag1, METERED_NO,
                ROAMING_NO, DEFAULT_NETWORK_NO, 21691L, 41L, 13572L, 48L, 0L);
        assertContains(delta, underlyingIface, 10120, SET_FOREGROUND, testTag1, METERED_NO,
                ROAMING_NO, DEFAULT_NETWORK_NO, 1281L, 2L, 653L, 1L, 0L);

        // New entries are added for debug purpose
        assertContains(delta, underlyingIface, 10100, SET_DBG_VPN_IN, TAG_NONE, METERED_NO,
                ROAMING_NO, DEFAULT_NETWORK_NO, 39605L, 46L, 12039, 51, 0);
        assertContains(delta, underlyingIface, 10120, SET_DBG_VPN_IN, TAG_NONE, METERED_NO,
                ROAMING_NO, DEFAULT_NETWORK_NO, 81964, 214, 47177, 246, 0);
        assertContains(delta, underlyingIface, tunUid, SET_DBG_VPN_OUT, TAG_NONE, METERED_ALL,
                ROAMING_ALL, DEFAULT_NETWORK_ALL, 121569, 260, 59216, 297, 0);

    }

    // Tests a case where all of the data received by the tun0 interface is echo back into the tun0
    // interface by the vpn app before it's sent out of the underlying interface. The VPN app should
    // not be charged for the echoed data but it should still be charged for any extra data it sends
    // via the underlying interface.
    @Test
    public void testMigrateTun_VpnAsLoopback() {
        final int tunUid = 10030;
        final String tunIface = "tun0";
        final String underlyingIface = "wlan0";
        NetworkStats delta = new NetworkStats(TEST_START, 9)
            // 2 different apps sent/receive data via tun0.
            .addValues(tunIface, 10100, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                    DEFAULT_NETWORK_NO, 50000L, 25L, 100000L, 50L, 0L)
            .addValues(tunIface, 20100, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                    DEFAULT_NETWORK_NO, 500L, 2L, 200L, 5L, 0L)
            // VPN package resends data through the tunnel (with exaggerated overhead)
            .addValues(tunIface, tunUid, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                    DEFAULT_NETWORK_NO, 240000, 100L, 120000L, 60L, 0L)
            // 1 app already has some traffic on the underlying interface, the other doesn't yet
            .addValues(underlyingIface, 10100, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                    DEFAULT_NETWORK_NO, 1000L, 10L, 2000L, 20L, 0L)
            // Traffic through the underlying interface via the vpn app.
            // This test should redistribute this data correctly.
            .addValues(underlyingIface, tunUid, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                    DEFAULT_NETWORK_NO,  75500L, 37L, 130000L, 70L, 0L);

        assertTrue(delta.migrateTun(tunUid, tunIface, underlyingIface));
        assertEquals(9, delta.size());

        // tunIface entries should not be changed.
        assertValues(delta, 0, tunIface, 10100, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 50000L, 25L, 100000L, 50L, 0L);
        assertValues(delta, 1, tunIface, 20100, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 500L, 2L, 200L, 5L, 0L);
        assertValues(delta, 2, tunIface, tunUid, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 240000L, 100L, 120000L, 60L, 0L);

        // Existing underlying Iface entries are updated
        assertValues(delta, 3, underlyingIface, 10100, SET_DEFAULT, TAG_NONE, METERED_NO,
                ROAMING_NO, DEFAULT_NETWORK_NO, 51000L, 35L, 102000L, 70L, 0L);

        // VPN underlying Iface entries are updated
        assertValues(delta, 4, underlyingIface, tunUid, SET_DEFAULT, TAG_NONE, METERED_NO,
                ROAMING_NO, DEFAULT_NETWORK_NO, 25000L, 10L, 29800L, 15L, 0L);

        // New entries are added for new application's underlying Iface traffic
        assertContains(delta, underlyingIface, 20100, SET_DEFAULT, TAG_NONE, METERED_NO,
                ROAMING_NO, DEFAULT_NETWORK_NO, 500L, 2L, 200L, 5L, 0L);

        // New entries are added for debug purpose
        assertContains(delta, underlyingIface, 10100, SET_DBG_VPN_IN, TAG_NONE, METERED_NO,
                ROAMING_NO, DEFAULT_NETWORK_NO, 50000L, 25L, 100000L, 50L, 0L);
        assertContains(delta, underlyingIface, 20100, SET_DBG_VPN_IN, TAG_NONE, METERED_NO,
                ROAMING_NO, DEFAULT_NETWORK_NO, 500, 2L, 200L, 5L, 0L);
        assertContains(delta, underlyingIface, tunUid, SET_DBG_VPN_OUT, TAG_NONE, METERED_ALL,
                ROAMING_ALL, DEFAULT_NETWORK_ALL, 50500L, 27L, 100200L, 55, 0);
    }

    @Test
    public void testFilter_NoFilter() {
        NetworkStats.Entry entry1 = new NetworkStats.Entry(
                "test1", 10100, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 50000L, 25L, 100000L, 50L, 0L);

        NetworkStats.Entry entry2 = new NetworkStats.Entry(
                "test2", 10101, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 50000L, 25L, 100000L, 50L, 0L);

        NetworkStats.Entry entry3 = new NetworkStats.Entry(
                "test2", 10101, SET_DEFAULT, 123, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 50000L, 25L, 100000L, 50L, 0L);

        NetworkStats stats = new NetworkStats(TEST_START, 3)
                .addValues(entry1)
                .addValues(entry2)
                .addValues(entry3);

        stats.filter(UID_ALL, INTERFACES_ALL, TAG_ALL);
        assertEquals(3, stats.size());
        assertEquals(entry1, stats.getValues(0, null));
        assertEquals(entry2, stats.getValues(1, null));
        assertEquals(entry3, stats.getValues(2, null));
    }

    @Test
    public void testFilter_UidFilter() {
        final int testUid = 10101;
        NetworkStats.Entry entry1 = new NetworkStats.Entry(
                "test1", 10100, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 50000L, 25L, 100000L, 50L, 0L);

        NetworkStats.Entry entry2 = new NetworkStats.Entry(
                "test2", testUid, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 50000L, 25L, 100000L, 50L, 0L);

        NetworkStats.Entry entry3 = new NetworkStats.Entry(
                "test2", testUid, SET_DEFAULT, 123, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 50000L, 25L, 100000L, 50L, 0L);

        NetworkStats stats = new NetworkStats(TEST_START, 3)
                .addValues(entry1)
                .addValues(entry2)
                .addValues(entry3);

        stats.filter(testUid, INTERFACES_ALL, TAG_ALL);
        assertEquals(2, stats.size());
        assertEquals(entry2, stats.getValues(0, null));
        assertEquals(entry3, stats.getValues(1, null));
    }

    @Test
    public void testFilter_InterfaceFilter() {
        final String testIf1 = "testif1";
        final String testIf2 = "testif2";
        NetworkStats.Entry entry1 = new NetworkStats.Entry(
                testIf1, 10100, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 50000L, 25L, 100000L, 50L, 0L);

        NetworkStats.Entry entry2 = new NetworkStats.Entry(
                "otherif", 10101, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 50000L, 25L, 100000L, 50L, 0L);

        NetworkStats.Entry entry3 = new NetworkStats.Entry(
                testIf1, 10101, SET_DEFAULT, 123, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 50000L, 25L, 100000L, 50L, 0L);

        NetworkStats.Entry entry4 = new NetworkStats.Entry(
                testIf2, 10101, SET_DEFAULT, 123, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 50000L, 25L, 100000L, 50L, 0L);

        NetworkStats stats = new NetworkStats(TEST_START, 4)
                .addValues(entry1)
                .addValues(entry2)
                .addValues(entry3)
                .addValues(entry4);

        stats.filter(UID_ALL, new String[] { testIf1, testIf2 }, TAG_ALL);
        assertEquals(3, stats.size());
        assertEquals(entry1, stats.getValues(0, null));
        assertEquals(entry3, stats.getValues(1, null));
        assertEquals(entry4, stats.getValues(2, null));
    }

    @Test
    public void testFilter_EmptyInterfaceFilter() {
        NetworkStats.Entry entry1 = new NetworkStats.Entry(
                "if1", 10100, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 50000L, 25L, 100000L, 50L, 0L);

        NetworkStats.Entry entry2 = new NetworkStats.Entry(
                "if2", 10101, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 50000L, 25L, 100000L, 50L, 0L);

        NetworkStats stats = new NetworkStats(TEST_START, 3)
                .addValues(entry1)
                .addValues(entry2);

        stats.filter(UID_ALL, new String[] { }, TAG_ALL);
        assertEquals(0, stats.size());
    }

    @Test
    public void testFilter_TagFilter() {
        final int testTag = 123;
        final int otherTag = 456;
        NetworkStats.Entry entry1 = new NetworkStats.Entry(
                "test1", 10100, SET_DEFAULT, testTag, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 50000L, 25L, 100000L, 50L, 0L);

        NetworkStats.Entry entry2 = new NetworkStats.Entry(
                "test2", 10101, SET_DEFAULT, testTag, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 50000L, 25L, 100000L, 50L, 0L);

        NetworkStats.Entry entry3 = new NetworkStats.Entry(
                "test2", 10101, SET_DEFAULT, otherTag, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO, 50000L, 25L, 100000L, 50L, 0L);

        NetworkStats stats = new NetworkStats(TEST_START, 3)
                .addValues(entry1)
                .addValues(entry2)
                .addValues(entry3);

        stats.filter(UID_ALL, INTERFACES_ALL, testTag);
        assertEquals(2, stats.size());
        assertEquals(entry1, stats.getValues(0, null));
        assertEquals(entry2, stats.getValues(1, null));
    }

    @Test
    public void testApply464xlatAdjustments() {
        final String v4Iface = "v4-wlan0";
        final String baseIface = "wlan0";
        final String otherIface = "other";
        final int appUid = 10001;
        final int rootUid = Process.ROOT_UID;
        ArrayMap<String, String> stackedIface = new ArrayMap<>();
        stackedIface.put(v4Iface, baseIface);

        // Ipv4 traffic sent/received by an app on stacked interface.
        final NetworkStats.Entry appEntry = new NetworkStats.Entry(
                v4Iface, appUid, SET_DEFAULT, TAG_NONE,
                30501490  /* rxBytes */,
                22401 /* rxPackets */,
                876235 /* txBytes */,
                13805 /* txPackets */,
                0 /* operations */);

        // Traffic measured for the root uid on the base interface if eBPF is in use.
        // Incorrectly includes appEntry's bytes and packets, plus IPv4-IPv6 translation
        // overhead (20 bytes per packet), only for TX traffic.
        final NetworkStats.Entry ebpfRootUidEntry = new NetworkStats.Entry(
                baseIface, rootUid, SET_DEFAULT, TAG_NONE,
                163577 /* rxBytes */,
                187 /* rxPackets */,
                1169942 /* txBytes */,
                13902 /* txPackets */,
                0 /* operations */);

        // Traffic measured for the root uid on the base interface if xt_qtaguid is in use.
        // Incorrectly includes appEntry's bytes and packets, plus IPv4-IPv6 translation
        // overhead (20 bytes per packet), in both directions.
        final NetworkStats.Entry xtRootUidEntry = new NetworkStats.Entry(
                baseIface, rootUid, SET_DEFAULT, TAG_NONE,
                31113087 /* rxBytes */,
                22588 /* rxPackets */,
                1169942 /* txBytes */,
                13902 /* txPackets */,
                0 /* operations */);

        final NetworkStats.Entry otherEntry = new NetworkStats.Entry(
                otherIface, appUid, SET_DEFAULT, TAG_NONE,
                2600  /* rxBytes */,
                2 /* rxPackets */,
                3800 /* txBytes */,
                3 /* txPackets */,
                0 /* operations */);

        final NetworkStats statsXt = new NetworkStats(TEST_START, 3)
                .addValues(appEntry)
                .addValues(xtRootUidEntry)
                .addValues(otherEntry);

        final NetworkStats statsEbpf = new NetworkStats(TEST_START, 3)
                .addValues(appEntry)
                .addValues(ebpfRootUidEntry)
                .addValues(otherEntry);

        statsXt.apply464xlatAdjustments(stackedIface, false);
        statsEbpf.apply464xlatAdjustments(stackedIface, true);

        assertEquals(3, statsXt.size());
        assertEquals(3, statsEbpf.size());
        final NetworkStats.Entry expectedAppUid = new NetworkStats.Entry(
                v4Iface, appUid, SET_DEFAULT, TAG_NONE,
                30949510,
                22401,
                1152335,
                13805,
                0);
        final NetworkStats.Entry expectedRootUid = new NetworkStats.Entry(
                baseIface, 0, SET_DEFAULT, TAG_NONE,
                163577,
                187,
                17607,
                97,
                0);
        assertEquals(expectedAppUid, statsXt.getValues(0, null));
        assertEquals(expectedRootUid, statsXt.getValues(1, null));
        assertEquals(otherEntry, statsXt.getValues(2, null));
        assertEquals(expectedAppUid, statsEbpf.getValues(0, null));
        assertEquals(expectedRootUid, statsEbpf.getValues(1, null));
        assertEquals(otherEntry, statsEbpf.getValues(2, null));
    }

    @Test
    public void testApply464xlatAdjustments_noStackedIface() {
        NetworkStats.Entry firstEntry = new NetworkStats.Entry(
                "if1", 10002, SET_DEFAULT, TAG_NONE,
                2600  /* rxBytes */,
                2 /* rxPackets */,
                3800 /* txBytes */,
                3 /* txPackets */,
                0 /* operations */);
        NetworkStats.Entry secondEntry = new NetworkStats.Entry(
                "if2", 10002, SET_DEFAULT, TAG_NONE,
                5000  /* rxBytes */,
                3 /* rxPackets */,
                6000 /* txBytes */,
                4 /* txPackets */,
                0 /* operations */);

        NetworkStats stats = new NetworkStats(TEST_START, 2)
                .addValues(firstEntry)
                .addValues(secondEntry);

        // Empty map: no adjustment
        stats.apply464xlatAdjustments(new ArrayMap<>(), false);

        assertEquals(2, stats.size());
        assertEquals(firstEntry, stats.getValues(0, null));
        assertEquals(secondEntry, stats.getValues(1, null));
    }

    private static void assertContains(NetworkStats stats,  String iface, int uid, int set,
            int tag, int metered, int roaming, int defaultNetwork, long rxBytes, long rxPackets,
            long txBytes, long txPackets, long operations) {
        int index = stats.findIndex(iface, uid, set, tag, metered, roaming, defaultNetwork);
        assertTrue(index != -1);
        assertValues(stats, index, iface, uid, set, tag, metered, roaming, defaultNetwork,
                rxBytes, rxPackets, txBytes, txPackets, operations);
    }

    private static void assertValues(NetworkStats stats, int index, String iface, int uid, int set,
            int tag, int metered, int roaming, int defaultNetwork, long rxBytes, long rxPackets,
            long txBytes, long txPackets, long operations) {
        final NetworkStats.Entry entry = stats.getValues(index, null);
        assertValues(entry, iface, uid, set, tag, metered, roaming, defaultNetwork);
        assertValues(entry, rxBytes, rxPackets, txBytes, txPackets, operations);
    }

    private static void assertValues(
            NetworkStats.Entry entry, String iface, int uid, int set, int tag, int metered,
            int roaming, int defaultNetwork) {
        assertEquals(iface, entry.iface);
        assertEquals(uid, entry.uid);
        assertEquals(set, entry.set);
        assertEquals(tag, entry.tag);
        assertEquals(metered, entry.metered);
        assertEquals(roaming, entry.roaming);
        assertEquals(defaultNetwork, entry.defaultNetwork);
    }

    private static void assertValues(NetworkStats.Entry entry, long rxBytes, long rxPackets,
            long txBytes, long txPackets, long operations) {
        assertEquals(rxBytes, entry.rxBytes);
        assertEquals(rxPackets, entry.rxPackets);
        assertEquals(txBytes, entry.txBytes);
        assertEquals(txPackets, entry.txPackets);
        assertEquals(operations, entry.operations);
    }

}
