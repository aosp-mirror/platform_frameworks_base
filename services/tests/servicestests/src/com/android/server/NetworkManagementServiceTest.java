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

package com.android.server;

import static android.net.NetworkStats.SET_DEFAULT;
import static android.net.NetworkStats.SET_FOREGROUND;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.NetworkStats.UID_ALL;
import static com.android.server.NetworkManagementSocketTagger.kernelToTag;
import static com.android.server.NetworkManagementSocketTagger.tagToKernel;

import android.content.res.Resources;
import android.net.NetworkStats;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.LargeTest;

import com.android.frameworks.servicestests.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;

import libcore.io.IoUtils;
import libcore.io.Streams;

/**
 * Tests for {@link NetworkManagementService}.
 */
@LargeTest
public class NetworkManagementServiceTest extends AndroidTestCase {
    private File mTestProc;
    private NetworkManagementService mService;

    @Override
    public void setUp() throws Exception {
        super.setUp();

        mTestProc = new File(getContext().getFilesDir(), "proc");
        if (mTestProc.exists()) {
            IoUtils.deleteContents(mTestProc);
        }

        mService = NetworkManagementService.createForTest(mContext, mTestProc, true);
    }

    @Override
    public void tearDown() throws Exception {
        mService = null;

        if (mTestProc.exists()) {
            IoUtils.deleteContents(mTestProc);
        }

        super.tearDown();
    }

    public void testNetworkStatsDetail() throws Exception {
        stageFile(R.raw.xt_qtaguid_typical, new File(mTestProc, "net/xt_qtaguid/stats"));

        final NetworkStats stats = mService.getNetworkStatsDetail();
        assertEquals(31, stats.size());
        assertStatsEntry(stats, "wlan0", 0, SET_DEFAULT, 0, 14615L, 4270L);
        assertStatsEntry(stats, "wlan0", 10004, SET_DEFAULT, 0, 333821L, 53558L);
        assertStatsEntry(stats, "wlan0", 10004, SET_DEFAULT, 1947740890, 18725L, 1066L);
        assertStatsEntry(stats, "rmnet0", 10037, SET_DEFAULT, 0, 31184994L, 684122L);
        assertStatsEntry(stats, "rmnet0", 10037, SET_DEFAULT, 1947740890, 28507378L, 437004L);
    }

    public void testNetworkStatsDetailExtended() throws Exception {
        stageFile(R.raw.xt_qtaguid_extended, new File(mTestProc, "net/xt_qtaguid/stats"));

        final NetworkStats stats = mService.getNetworkStatsDetail();
        assertEquals(2, stats.size());
        assertStatsEntry(stats, "test0", 1000, SET_DEFAULT, 0, 1024L, 2048L);
        assertStatsEntry(stats, "test0", 1000, SET_DEFAULT, 0xF00D, 512L, 512L);
    }

    public void testNetworkStatsSummary() throws Exception {
        stageFile(R.raw.net_dev_typical, new File(mTestProc, "net/dev"));

        final NetworkStats stats = mService.getNetworkStatsSummary();
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
        stageLong(1024L, new File(mTestProc, "net/xt_qtaguid/iface_stat/wlan0/rx_bytes"));
        stageLong(128L, new File(mTestProc, "net/xt_qtaguid/iface_stat/wlan0/rx_packets"));
        stageLong(2048L, new File(mTestProc, "net/xt_qtaguid/iface_stat/wlan0/tx_bytes"));
        stageLong(256L, new File(mTestProc, "net/xt_qtaguid/iface_stat/wlan0/tx_packets"));

        final NetworkStats stats = mService.getNetworkStatsSummary();
        assertEquals(7, stats.size());
        assertStatsEntry(stats, "rmnet0", UID_ALL, SET_DEFAULT, TAG_NONE, 1507570L, 489339L);
        assertStatsEntry(stats, "wlan0", UID_ALL, SET_DEFAULT, TAG_NONE, 1024L, 2048L);
    }

    public void testKernelTags() throws Exception {
        assertEquals("0", tagToKernel(0x0));
        assertEquals("214748364800", tagToKernel(0x32));
        assertEquals("9223372032559808512", tagToKernel(Integer.MAX_VALUE));
        assertEquals("0", tagToKernel(Integer.MIN_VALUE));
        assertEquals("9223369837831520256", tagToKernel(Integer.MIN_VALUE - 512));

        assertEquals(0, kernelToTag("0x0000000000000000"));
        assertEquals(0x32, kernelToTag("0x0000003200000000"));
        assertEquals(2147483647, kernelToTag("0x7fffffff00000000"));
        assertEquals(0, kernelToTag("0x0000000000000000"));
        assertEquals(2147483136, kernelToTag("0x7FFFFE0000000000"));
    }

    public void testNetworkStatsWithSet() throws Exception {
        stageFile(R.raw.xt_qtaguid_typical_with_set, new File(mTestProc, "net/xt_qtaguid/stats"));

        final NetworkStats stats = mService.getNetworkStatsDetail();
        assertEquals(12, stats.size());
        assertStatsEntry(stats, "rmnet0", 1000, SET_DEFAULT, 0, 278102L, 253L, 10487L, 182L);
        assertStatsEntry(stats, "rmnet0", 1000, SET_FOREGROUND, 0, 26033L, 30L, 1401L, 26L);
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
