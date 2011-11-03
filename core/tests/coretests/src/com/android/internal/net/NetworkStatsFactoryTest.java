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

import static android.net.NetworkStats.SET_DEFAULT;
import static android.net.NetworkStats.SET_FOREGROUND;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.NetworkStats.UID_ALL;
import static com.android.server.NetworkManagementSocketTagger.kernelToTag;

import android.content.res.Resources;
import android.net.NetworkStats;
import android.test.AndroidTestCase;

import com.android.frameworks.coretests.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;

import libcore.io.IoUtils;
import libcore.io.Streams;

/**
 * Tests for {@link NetworkStatsFactory}.
 */
public class NetworkStatsFactoryTest extends AndroidTestCase {
    private File mTestProc;
    private NetworkStatsFactory mFactory;

    @Override
    public void setUp() throws Exception {
        super.setUp();

        mTestProc = new File(getContext().getFilesDir(), "proc");
        if (mTestProc.exists()) {
            IoUtils.deleteContents(mTestProc);
        }

        mFactory = new NetworkStatsFactory(mTestProc);
    }

    @Override
    public void tearDown() throws Exception {
        mFactory = null;

        if (mTestProc.exists()) {
            IoUtils.deleteContents(mTestProc);
        }

        super.tearDown();
    }

    public void testNetworkStatsDetail() throws Exception {
        stageFile(R.raw.xt_qtaguid_typical, new File(mTestProc, "net/xt_qtaguid/stats"));

        final NetworkStats stats = mFactory.readNetworkStatsDetail();
        assertEquals(70, stats.size());
        assertStatsEntry(stats, "wlan0", 0, SET_DEFAULT, 0x0, 18621L, 2898L);
        assertStatsEntry(stats, "wlan0", 10011, SET_DEFAULT, 0x0, 35777L, 5718L);
        assertStatsEntry(stats, "wlan0", 10021, SET_DEFAULT, 0x7fffff01, 562386L, 49228L);
        assertStatsEntry(stats, "rmnet1", 10021, SET_DEFAULT, 0x30100000, 219110L, 227423L);
        assertStatsEntry(stats, "rmnet2", 10001, SET_DEFAULT, 0x0, 1125899906842624L, 984L);
    }

    public void testNetworkStatsSummary() throws Exception {
        stageFile(R.raw.net_dev_typical, new File(mTestProc, "net/dev"));

        final NetworkStats stats = mFactory.readNetworkStatsSummary();
        assertEquals(6, stats.size());
        assertStatsEntry(stats, "lo", UID_ALL, SET_DEFAULT, TAG_NONE, 8308L, 8308L);
        assertStatsEntry(stats, "rmnet0", UID_ALL, SET_DEFAULT, TAG_NONE, 1507570L, 489339L);
        assertStatsEntry(stats, "ifb0", UID_ALL, SET_DEFAULT, TAG_NONE, 52454L, 0L);
        assertStatsEntry(stats, "ifb1", UID_ALL, SET_DEFAULT, TAG_NONE, 52454L, 0L);
        assertStatsEntry(stats, "sit0", UID_ALL, SET_DEFAULT, TAG_NONE, 0L, 0L);
        assertStatsEntry(stats, "ip6tnl0", UID_ALL, SET_DEFAULT, TAG_NONE, 0L, 0L);
    }

    public void testNetworkStatsSummaryDown() throws Exception {
        stageFile(R.raw.net_dev_typical, new File(mTestProc, "net/dev"));
        stageLong(1L, new File(mTestProc, "net/xt_qtaguid/iface_stat/wlan0/active"));
        stageLong(1024L, new File(mTestProc, "net/xt_qtaguid/iface_stat/wlan0/rx_bytes"));
        stageLong(128L, new File(mTestProc, "net/xt_qtaguid/iface_stat/wlan0/rx_packets"));
        stageLong(2048L, new File(mTestProc, "net/xt_qtaguid/iface_stat/wlan0/tx_bytes"));
        stageLong(256L, new File(mTestProc, "net/xt_qtaguid/iface_stat/wlan0/tx_packets"));

        final NetworkStats stats = mFactory.readNetworkStatsSummary();
        assertEquals(7, stats.size());
        assertStatsEntry(stats, "rmnet0", UID_ALL, SET_DEFAULT, TAG_NONE, 1507570L, 489339L);
        assertStatsEntry(stats, "wlan0", UID_ALL, SET_DEFAULT, TAG_NONE, 1024L, 2048L);
    }

    public void testNetworkStatsCombined() throws Exception {
        stageFile(R.raw.net_dev_typical, new File(mTestProc, "net/dev"));
        stageLong(1L, new File(mTestProc, "net/xt_qtaguid/iface_stat/rmnet0/active"));
        stageLong(10L, new File(mTestProc, "net/xt_qtaguid/iface_stat/rmnet0/rx_bytes"));
        stageLong(20L, new File(mTestProc, "net/xt_qtaguid/iface_stat/rmnet0/rx_packets"));
        stageLong(30L, new File(mTestProc, "net/xt_qtaguid/iface_stat/rmnet0/tx_bytes"));
        stageLong(40L, new File(mTestProc, "net/xt_qtaguid/iface_stat/rmnet0/tx_packets"));

        final NetworkStats stats = mFactory.readNetworkStatsSummary();
        assertStatsEntry(stats, "rmnet0", UID_ALL, SET_DEFAULT, TAG_NONE, 1507570L + 10L,
                2205L + 20L, 489339L + 30L, 2237L + 40L);
    }

    public void testNetworkStatsCombinedInactive() throws Exception {
        stageFile(R.raw.net_dev_typical, new File(mTestProc, "net/dev"));
        stageLong(0L, new File(mTestProc, "net/xt_qtaguid/iface_stat/rmnet0/active"));
        stageLong(10L, new File(mTestProc, "net/xt_qtaguid/iface_stat/rmnet0/rx_bytes"));
        stageLong(20L, new File(mTestProc, "net/xt_qtaguid/iface_stat/rmnet0/rx_packets"));
        stageLong(30L, new File(mTestProc, "net/xt_qtaguid/iface_stat/rmnet0/tx_bytes"));
        stageLong(40L, new File(mTestProc, "net/xt_qtaguid/iface_stat/rmnet0/tx_packets"));

        final NetworkStats stats = mFactory.readNetworkStatsSummary();
        assertStatsEntry(stats, "rmnet0", UID_ALL, SET_DEFAULT, TAG_NONE, 10L, 20L, 30L, 40L);
    }

    public void testKernelTags() throws Exception {
        assertEquals(0, kernelToTag("0x0000000000000000"));
        assertEquals(0x32, kernelToTag("0x0000003200000000"));
        assertEquals(2147483647, kernelToTag("0x7fffffff00000000"));
        assertEquals(0, kernelToTag("0x0000000000000000"));
        assertEquals(2147483136, kernelToTag("0x7FFFFE0000000000"));
    }

    public void testNetworkStatsWithSet() throws Exception {
        stageFile(R.raw.xt_qtaguid_typical, new File(mTestProc, "net/xt_qtaguid/stats"));

        final NetworkStats stats = mFactory.readNetworkStatsDetail();
        assertEquals(70, stats.size());
        assertStatsEntry(stats, "rmnet1", 10021, SET_DEFAULT, 0x30100000, 219110L, 578L, 227423L, 676L);
        assertStatsEntry(stats, "rmnet1", 10021, SET_FOREGROUND, 0x30100000, 742L, 3L, 1265L, 3L);
    }

    public void testNetworkStatsSingle() throws Exception {
        stageFile(R.raw.xt_qtaguid_iface_typical, new File(mTestProc, "net/xt_qtaguid/iface_stat_all"));

        final NetworkStats stats = mFactory.readNetworkStatsSummary();
        assertEquals(6, stats.size());
        assertStatsEntry(stats, "rmnet0", UID_ALL, SET_DEFAULT, TAG_NONE, 2112L, 24L, 700L, 10L);
        assertStatsEntry(stats, "test1", UID_ALL, SET_DEFAULT, TAG_NONE, 6L, 8L, 10L, 12L);
        assertStatsEntry(stats, "test2", UID_ALL, SET_DEFAULT, TAG_NONE, 1L, 2L, 3L, 4L);
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
            in = getContext().getResources().openRawResource(rawId);
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

    private static void assertStatsEntry(NetworkStats stats, String iface, int uid, int set,
            int tag, long rxBytes, long txBytes) {
        final int i = stats.findIndex(iface, uid, set, tag);
        final NetworkStats.Entry entry = stats.getValues(i, null);
        assertEquals("unexpected rxBytes", rxBytes, entry.rxBytes);
        assertEquals("unexpected txBytes", txBytes, entry.txBytes);
    }

    private static void assertStatsEntry(NetworkStats stats, String iface, int uid, int set,
            int tag, long rxBytes, long rxPackets, long txBytes, long txPackets) {
        final int i = stats.findIndex(iface, uid, set, tag);
        final NetworkStats.Entry entry = stats.getValues(i, null);
        assertEquals("unexpected rxBytes", rxBytes, entry.rxBytes);
        assertEquals("unexpected rxPackets", rxPackets, entry.rxPackets);
        assertEquals("unexpected txBytes", txBytes, entry.txBytes);
        assertEquals("unexpected txPackets", txPackets, entry.txPackets);
    }

}
