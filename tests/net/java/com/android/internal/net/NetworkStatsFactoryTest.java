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

package com.android.internal.net;

import static android.net.NetworkStats.DEFAULT_NETWORK_NO;
import static android.net.NetworkStats.METERED_NO;
import static android.net.NetworkStats.ROAMING_NO;
import static android.net.NetworkStats.SET_ALL;
import static android.net.NetworkStats.SET_DEFAULT;
import static android.net.NetworkStats.SET_FOREGROUND;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.NetworkStats.UID_ALL;

import static com.android.server.NetworkManagementSocketTagger.kernelToTag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import android.content.res.Resources;
import android.net.NetworkStats;
import android.net.TrafficStats;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.tests.net.R;

import libcore.io.IoUtils;
import libcore.io.Streams;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Tests for {@link NetworkStatsFactory}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class NetworkStatsFactoryTest {
    private File mTestProc;
    private NetworkStatsFactory mFactory;

    @Before
    public void setUp() throws Exception {
        mTestProc = new File(InstrumentationRegistry.getContext().getFilesDir(), "proc");
        if (mTestProc.exists()) {
            IoUtils.deleteContents(mTestProc);
        }

        mFactory = new NetworkStatsFactory(mTestProc, false);
    }

    @After
    public void tearDown() throws Exception {
        mFactory = null;

        if (mTestProc.exists()) {
            IoUtils.deleteContents(mTestProc);
        }
    }

    @Test
    public void testNetworkStatsDetail() throws Exception {
        final NetworkStats stats = parseDetailedStats(R.raw.xt_qtaguid_typical);

        assertEquals(70, stats.size());
        assertStatsEntry(stats, "wlan0", 0, SET_DEFAULT, 0x0, 18621L, 2898L);
        assertStatsEntry(stats, "wlan0", 10011, SET_DEFAULT, 0x0, 35777L, 5718L);
        assertStatsEntry(stats, "wlan0", 10021, SET_DEFAULT, 0x7fffff01, 562386L, 49228L);
        assertStatsEntry(stats, "rmnet1", 10021, SET_DEFAULT, 0x30100000, 219110L, 227423L);
        assertStatsEntry(stats, "rmnet2", 10001, SET_DEFAULT, 0x0, 1125899906842624L, 984L);
    }

    @Test
    public void testKernelTags() throws Exception {
        assertEquals(0, kernelToTag("0x0000000000000000"));
        assertEquals(0x32, kernelToTag("0x0000003200000000"));
        assertEquals(2147483647, kernelToTag("0x7fffffff00000000"));
        assertEquals(0, kernelToTag("0x0000000000000000"));
        assertEquals(2147483136, kernelToTag("0x7FFFFE0000000000"));

        assertEquals(0, kernelToTag("0x0"));
        assertEquals(0, kernelToTag("0xf00d"));
        assertEquals(1, kernelToTag("0x100000000"));
        assertEquals(14438007, kernelToTag("0xdc4e7700000000"));
        assertEquals(TrafficStats.TAG_SYSTEM_DOWNLOAD, kernelToTag("0xffffff0100000000"));
    }

    @Test
    public void testNetworkStatsWithSet() throws Exception {
        final NetworkStats stats = parseDetailedStats(R.raw.xt_qtaguid_typical);
        assertEquals(70, stats.size());
        assertStatsEntry(stats, "rmnet1", 10021, SET_DEFAULT, 0x30100000, 219110L, 578L, 227423L,
                676L);
        assertStatsEntry(stats, "rmnet1", 10021, SET_FOREGROUND, 0x30100000, 742L, 3L, 1265L, 3L);
    }

    @Test
    public void testNetworkStatsSingle() throws Exception {
        stageFile(R.raw.xt_qtaguid_iface_typical, file("net/xt_qtaguid/iface_stat_all"));

        final NetworkStats stats = mFactory.readNetworkStatsSummaryDev();
        assertEquals(6, stats.size());
        assertStatsEntry(stats, "rmnet0", UID_ALL, SET_ALL, TAG_NONE, 2112L, 24L, 700L, 10L);
        assertStatsEntry(stats, "test1", UID_ALL, SET_ALL, TAG_NONE, 6L, 8L, 10L, 12L);
        assertStatsEntry(stats, "test2", UID_ALL, SET_ALL, TAG_NONE, 1L, 2L, 3L, 4L);
    }

    @Test
    public void testNetworkStatsXt() throws Exception {
        stageFile(R.raw.xt_qtaguid_iface_fmt_typical, file("net/xt_qtaguid/iface_stat_fmt"));

        final NetworkStats stats = mFactory.readNetworkStatsSummaryXt();
        assertEquals(3, stats.size());
        assertStatsEntry(stats, "rmnet0", UID_ALL, SET_ALL, TAG_NONE, 6824L, 16L, 5692L, 10L);
        assertStatsEntry(stats, "rmnet1", UID_ALL, SET_ALL, TAG_NONE, 11153922L, 8051L, 190226L,
                2468L);
        assertStatsEntry(stats, "rmnet2", UID_ALL, SET_ALL, TAG_NONE, 4968L, 35L, 3081L, 39L);
    }

    @Test
    public void testDoubleClatAccounting() throws Exception {
        NetworkStatsFactory.noteStackedIface("v4-wlan0", "wlan0");

        // xt_qtaguid_with_clat_simple is a synthetic file that simulates
        //  - 213 received 464xlat packets of size 200 bytes
        //  - 41 sent 464xlat packets of size 100 bytes
        //  - no other traffic on base interface for root uid.
        NetworkStats stats = parseDetailedStats(R.raw.xt_qtaguid_with_clat_simple);
        assertEquals(4, stats.size());

        assertStatsEntry(stats, "v4-wlan0", 10060, SET_DEFAULT, 0x0, 46860L, 4920L);
        assertStatsEntry(stats, "wlan0", 0, SET_DEFAULT, 0x0, 0L, 0L);

        stats = parseDetailedStats(R.raw.xt_qtaguid_with_clat);
        assertEquals(42, stats.size());

        assertStatsEntry(stats, "v4-wlan0", 0, SET_DEFAULT, 0x0, 356L, 276L);
        assertStatsEntry(stats, "v4-wlan0", 1000, SET_DEFAULT, 0x0, 30812L, 2310L);
        assertStatsEntry(stats, "v4-wlan0", 10102, SET_DEFAULT, 0x0, 10022L, 3330L);
        assertStatsEntry(stats, "v4-wlan0", 10060, SET_DEFAULT, 0x0, 9532772L, 254112L);
        assertStatsEntry(stats, "wlan0", 0, SET_DEFAULT, 0x0, 15229L, 0L);
        assertStatsEntry(stats, "wlan0", 1000, SET_DEFAULT, 0x0, 6126L, 2013L);
        assertStatsEntry(stats, "wlan0", 10013, SET_DEFAULT, 0x0, 0L, 144L);
        assertStatsEntry(stats, "wlan0", 10018, SET_DEFAULT, 0x0, 5980263L, 167667L);
        assertStatsEntry(stats, "wlan0", 10060, SET_DEFAULT, 0x0, 134356L, 8705L);
        assertStatsEntry(stats, "wlan0", 10079, SET_DEFAULT, 0x0, 10926L, 1507L);
        assertStatsEntry(stats, "wlan0", 10102, SET_DEFAULT, 0x0, 25038L, 8245L);
        assertStatsEntry(stats, "wlan0", 10103, SET_DEFAULT, 0x0, 0L, 192L);
        assertStatsEntry(stats, "dummy0", 0, SET_DEFAULT, 0x0, 0L, 168L);
        assertStatsEntry(stats, "lo", 0, SET_DEFAULT, 0x0, 1288L, 1288L);

        assertNoStatsEntry(stats, "wlan0", 1029, SET_DEFAULT, 0x0);

        NetworkStatsFactory.clearStackedIfaces();
    }

    @Test
    public void testDoubleClatAccounting100MBDownload() throws Exception {
        // Downloading 100mb from an ipv4 only destination in a foreground activity

        long appRxBytesBefore = 328684029L;
        long appRxBytesAfter = 439237478L;
        assertEquals("App traffic should be ~100MB", 110553449, appRxBytesAfter - appRxBytesBefore);

        long rootRxBytesBefore = 1394011L;
        long rootRxBytesAfter = 1398634L;
        assertEquals("UID 0 traffic should be ~0", 4623, rootRxBytesAfter - rootRxBytesBefore);

        NetworkStatsFactory.noteStackedIface("v4-wlan0", "wlan0");
        NetworkStats stats;

        // Stats snapshot before the download
        stats = parseDetailedStats(R.raw.xt_qtaguid_with_clat_100mb_download_before);
        assertStatsEntry(stats, "v4-wlan0", 10106, SET_FOREGROUND, 0x0, appRxBytesBefore, 5199872L);
        assertStatsEntry(stats, "wlan0", 0, SET_DEFAULT, 0x0, rootRxBytesBefore, 0L);

        // Stats snapshot after the download
        stats = parseDetailedStats(R.raw.xt_qtaguid_with_clat_100mb_download_after);
        assertStatsEntry(stats, "v4-wlan0", 10106, SET_FOREGROUND, 0x0, appRxBytesAfter, 7867488L);
        assertStatsEntry(stats, "wlan0", 0, SET_DEFAULT, 0x0, rootRxBytesAfter, 0L);

        NetworkStatsFactory.clearStackedIfaces();
    }

    /**
     * Copy a {@link Resources#openRawResource(int)} into {@link File} for
     * testing purposes.
     */
    private void stageFile(int rawId, File file) throws Exception {
        new File(file.getParent()).mkdirs();
        InputStream in = null;
        OutputStream out = null;
        try {
            in = InstrumentationRegistry.getContext().getResources().openRawResource(rawId);
            out = new FileOutputStream(file);
            Streams.copy(in, out);
        } finally {
            IoUtils.closeQuietly(in);
            IoUtils.closeQuietly(out);
        }
    }

    private void stageLong(long value, File file) throws Exception {
        new File(file.getParent()).mkdirs();
        FileWriter out = null;
        try {
            out = new FileWriter(file);
            out.write(Long.toString(value));
        } finally {
            IoUtils.closeQuietly(out);
        }
    }

    private File file(String path) throws Exception {
        return new File(mTestProc, path);
    }

    private NetworkStats parseDetailedStats(int resourceId) throws Exception {
        stageFile(resourceId, file("net/xt_qtaguid/stats"));
        return mFactory.readNetworkStatsDetail();
    }

    private static void assertStatsEntry(NetworkStats stats, String iface, int uid, int set,
            int tag, long rxBytes, long txBytes) {
        final int i = stats.findIndex(iface, uid, set, tag, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO);
        if (i < 0) {
            fail(String.format("no NetworkStats for (iface: %s, uid: %d, set: %d, tag: %d)",
                    iface, uid, set, tag));
        }
        final NetworkStats.Entry entry = stats.getValues(i, null);
        assertEquals("unexpected rxBytes", rxBytes, entry.rxBytes);
        assertEquals("unexpected txBytes", txBytes, entry.txBytes);
    }

    private static void assertNoStatsEntry(NetworkStats stats, String iface, int uid, int set,
            int tag) {
        final int i = stats.findIndex(iface, uid, set, tag, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO);
        if (i >= 0) {
            fail("unexpected NetworkStats entry at " + i);
        }
    }

    private static void assertStatsEntry(NetworkStats stats, String iface, int uid, int set,
            int tag, long rxBytes, long rxPackets, long txBytes, long txPackets) {
        final int i = stats.findIndex(iface, uid, set, tag, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_NO);
        if (i < 0) {
            fail(String.format("no NetworkStats for (iface: %s, uid: %d, set: %d, tag: %d)",
                    iface, uid, set, tag));
        }
        final NetworkStats.Entry entry = stats.getValues(i, null);
        assertEquals("unexpected rxBytes", rxBytes, entry.rxBytes);
        assertEquals("unexpected rxPackets", rxPackets, entry.rxPackets);
        assertEquals("unexpected txBytes", txBytes, entry.txBytes);
        assertEquals("unexpected txPackets", txPackets, entry.txPackets);
    }
}
