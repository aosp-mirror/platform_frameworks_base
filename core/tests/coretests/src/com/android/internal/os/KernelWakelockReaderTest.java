/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.internal.os;

import androidx.test.filters.SmallTest;

import junit.framework.TestCase;

import java.nio.charset.Charset;

import android.system.suspend.WakeLockInfo;

public class KernelWakelockReaderTest extends TestCase {
    /**
     * Helper class that builds the mock Kernel module file /d/wakeup_sources.
     */
    private static class ProcFileBuilder {
        private final static String sHeader = "name\t\tactive_count\tevent_count\twakeup_count\t" +
                "expire_count\tactive_since\ttotal_time\tmax_time\tlast_change\t" +
                "prevent_suspend_time\n";

        private StringBuilder mStringBuilder;

        private void ensureHeader() {
            if (mStringBuilder == null) {
                mStringBuilder = new StringBuilder();
                mStringBuilder.append(sHeader);
            }
        }

        public ProcFileBuilder addLine(String name, int count, long timeMillis) {
            ensureHeader();
            mStringBuilder.append(name).append("\t").append(count).append("\t0\t0\t0\t0\t")
                    .append(timeMillis).append("\t0\t0\t0\n");
            return this;
        }

        public byte[] getBytes() throws Exception {
            ensureHeader();
            byte[] data = mStringBuilder.toString().getBytes(Charset.forName("UTF-8"));

            // The Kernel puts a \0 at the end of the data. Since each of our lines ends with \n,
            // we override the last \n with a \0.
            data[data.length - 1] = 0;
            return data;
        }
    }

    /**
     * Helper method to create WakeLockInfo object.
     * @param totalTime is time in microseconds.
     * @return the created WakeLockInfo object.
     */
    private WakeLockInfo createWakeLockInfo(String name, int activeCount, long totalTime) {
        WakeLockInfo info = new WakeLockInfo();
        info.name = name;
        info.activeCount = activeCount;
        info.totalTime = totalTime;
        return info;
    }

    /**
     * Helper method for KernelWakeLockReader::readKernelWakelockStats(...)
     * @param staleStats existing stats to update.
     * @param buffer representation of mock kernel module file /d/wakeup_sources.
     * @param wlStats mock WakeLockInfo list returned from ISuspendControlService.
     * @return the updated stats.
     */
    private KernelWakelockStats readKernelWakelockStats(KernelWakelockStats staleStats,
                                                        byte[] buffer, WakeLockInfo[] wlStats) {
        mReader.updateVersion(staleStats);
        mReader.parseProcWakelocks(buffer, buffer.length, true, staleStats);
        mReader.updateWakelockStats(wlStats, staleStats);
        return mReader.removeOldStats(staleStats);
    }

    private KernelWakelockReader mReader;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mReader = new KernelWakelockReader();
    }

// ------------------------- Legacy Wakelock Stats Test ------------------------
    @SmallTest
    public void testParseEmptyFile() throws Exception {
        KernelWakelockStats staleStats = mReader.parseProcWakelocks(new byte[0], 0, true,
                new KernelWakelockStats());

        assertTrue(staleStats.isEmpty());
    }

    @SmallTest
    public void testOnlyHeader() throws Exception {
        byte[] buffer = new ProcFileBuilder().getBytes();

        KernelWakelockStats staleStats = mReader.parseProcWakelocks(buffer, buffer.length, true,
                new KernelWakelockStats());

        assertTrue(staleStats.isEmpty());
    }

    @SmallTest
    public void testOneWakelock() throws Exception {
        byte[] buffer = new ProcFileBuilder()
                .addLine("Wakelock", 34, 123) // Milliseconds
                .getBytes();

        KernelWakelockStats staleStats = mReader.parseProcWakelocks(buffer, buffer.length, true,
                new KernelWakelockStats());

        assertEquals(1, staleStats.size());

        assertTrue(staleStats.containsKey("Wakelock"));

        KernelWakelockStats.Entry entry = staleStats.get("Wakelock");
        assertEquals(34, entry.mCount);
        assertEquals(123 * 1000, entry.mTotalTime); // Microseconds
    }

    @SmallTest
    public void testTwoWakelocks() throws Exception {
        byte[] buffer = new ProcFileBuilder()
                .addLine("Wakelock", 1, 10)
                .addLine("Fakelock", 2, 20)
                .getBytes();

        KernelWakelockStats staleStats = mReader.parseProcWakelocks(buffer, buffer.length, true,
                new KernelWakelockStats());

        assertEquals(2, staleStats.size());

        assertTrue(staleStats.containsKey("Wakelock"));
        assertTrue(staleStats.containsKey("Fakelock"));
    }

    @SmallTest
    public void testDuplicateWakelocksAccumulate() throws Exception {
        byte[] buffer = new ProcFileBuilder()
                .addLine("Wakelock", 1, 10) // Milliseconds
                .addLine("Wakelock", 1, 10) // Milliseconds
                .getBytes();

        KernelWakelockStats staleStats = mReader.parseProcWakelocks(buffer, buffer.length, true,
                new KernelWakelockStats());

        assertEquals(1, staleStats.size());
        assertTrue(staleStats.containsKey("Wakelock"));

        KernelWakelockStats.Entry entry = staleStats.get("Wakelock");
        assertEquals(2, entry.mCount);
        assertEquals(20 * 1000, entry.mTotalTime); // Microseconds
    }

    @SmallTest
    public void testWakelocksBecomeStale() throws Exception {
        KernelWakelockStats staleStats = new KernelWakelockStats();

        byte[] buffer = new ProcFileBuilder()
                .addLine("Fakelock", 3, 30)
                .getBytes();

        readKernelWakelockStats(staleStats, buffer, new WakeLockInfo[0]);

        assertEquals(1, staleStats.size());
        assertTrue(staleStats.containsKey("Fakelock"));

        buffer = new ProcFileBuilder()
                .addLine("Wakelock", 1, 10)
                .getBytes();

        readKernelWakelockStats(staleStats, buffer, new WakeLockInfo[0]);

        assertEquals(1, staleStats.size());
        assertTrue(staleStats.containsKey("Wakelock"));
        assertFalse(staleStats.containsKey("Fakelock"));
    }

// -------------------- SystemSuspend Wakelock Stats Test -------------------
    @SmallTest
    public void testEmptyWakeLockInfoList() {
        KernelWakelockStats staleStats = mReader.updateWakelockStats(new WakeLockInfo[0],
                new KernelWakelockStats());

        assertTrue(staleStats.isEmpty());
    }

    @SmallTest
    public void testOneWakeLockInfo() {
        WakeLockInfo[] wlStats = new WakeLockInfo[1];
        wlStats[0] = createWakeLockInfo("WakeLock", 20, 1000);   // Milliseconds

        KernelWakelockStats staleStats = mReader.updateWakelockStats(wlStats,
                new KernelWakelockStats());

        assertEquals(1, staleStats.size());

        assertTrue(staleStats.containsKey("WakeLock"));

        KernelWakelockStats.Entry entry = staleStats.get("WakeLock");
        assertEquals(20, entry.mCount);
        assertEquals(1000 * 1000, entry.mTotalTime);   // Microseconds
    }

    @SmallTest
    public void testTwoWakeLockInfos() {
        WakeLockInfo[] wlStats = new WakeLockInfo[2];
        wlStats[0] = createWakeLockInfo("WakeLock1", 10, 1000); // Milliseconds
        wlStats[1] = createWakeLockInfo("WakeLock2", 20, 2000); // Milliseconds

        KernelWakelockStats staleStats = mReader.updateWakelockStats(wlStats,
                new KernelWakelockStats());

        assertEquals(2, staleStats.size());

        assertTrue(staleStats.containsKey("WakeLock1"));
        assertTrue(staleStats.containsKey("WakeLock2"));

        KernelWakelockStats.Entry entry1 = staleStats.get("WakeLock1");
        assertEquals(10, entry1.mCount);
        assertEquals(1000 * 1000, entry1.mTotalTime); // Microseconds

        KernelWakelockStats.Entry entry2 = staleStats.get("WakeLock2");
        assertEquals(20, entry2.mCount);
        assertEquals(2000 * 1000, entry2.mTotalTime); // Microseconds
    }

    @SmallTest
    public void testWakeLockInfosBecomeStale() {
        WakeLockInfo[] wlStats = new WakeLockInfo[1];
        wlStats[0] = createWakeLockInfo("WakeLock1", 10, 1000); // Milliseconds

        KernelWakelockStats staleStats = new KernelWakelockStats();

        readKernelWakelockStats(staleStats, new byte[0], wlStats);

        assertEquals(1, staleStats.size());

        assertTrue(staleStats.containsKey("WakeLock1"));
        KernelWakelockStats.Entry entry = staleStats.get("WakeLock1");
        assertEquals(10, entry.mCount);
        assertEquals(1000 * 1000, entry.mTotalTime);  // Microseconds

        wlStats[0] = createWakeLockInfo("WakeLock2", 20, 2000); // Milliseconds

        readKernelWakelockStats(staleStats, new byte[0], wlStats);

        assertEquals(1, staleStats.size());

        assertFalse(staleStats.containsKey("WakeLock1"));
        assertTrue(staleStats.containsKey("WakeLock2"));
        entry = staleStats.get("WakeLock2");
        assertEquals(20, entry.mCount);
        assertEquals(2000 * 1000, entry.mTotalTime); // Micro seconds
    }

// -------------------- Aggregate  Wakelock Stats Tests --------------------
    @SmallTest
    public void testAggregateStatsEmpty() throws Exception {
        KernelWakelockStats staleStats = new KernelWakelockStats();

        byte[] buffer = new byte[0];
        WakeLockInfo[] wlStats = new WakeLockInfo[0];

        readKernelWakelockStats(staleStats, buffer, wlStats);

        assertTrue(staleStats.isEmpty());
    }

    @SmallTest
    public void testAggregateStatsNoNativeWakelocks() throws Exception {
        KernelWakelockStats staleStats = new KernelWakelockStats();

        byte[] buffer = new ProcFileBuilder()
                .addLine("Wakelock", 34, 123) // Milliseconds
                .getBytes();
        WakeLockInfo[] wlStats = new WakeLockInfo[0];

        readKernelWakelockStats(staleStats, buffer, wlStats);

        assertEquals(1, staleStats.size());

        assertTrue(staleStats.containsKey("Wakelock"));

        KernelWakelockStats.Entry entry = staleStats.get("Wakelock");
        assertEquals(34, entry.mCount);
        assertEquals(1000 * 123, entry.mTotalTime);  // Microseconds
    }

    @SmallTest
    public void testAggregateStatsNoKernelWakelocks() throws Exception {
        KernelWakelockStats staleStats = new KernelWakelockStats();

        byte[] buffer = new byte[0];
        WakeLockInfo[] wlStats = new WakeLockInfo[1];
        wlStats[0] = createWakeLockInfo("WakeLock", 10, 1000);  // Milliseconds

        readKernelWakelockStats(staleStats, buffer, wlStats);

        assertEquals(1, staleStats.size());

        assertTrue(staleStats.containsKey("WakeLock"));

        KernelWakelockStats.Entry entry = staleStats.get("WakeLock");
        assertEquals(10, entry.mCount);
        assertEquals(1000 * 1000, entry.mTotalTime);  // Microseconds
    }

    @SmallTest
    public void testAggregateStatsBothKernelAndNativeWakelocks() throws Exception {
        KernelWakelockStats staleStats = new KernelWakelockStats();

        byte[] buffer = new ProcFileBuilder()
                .addLine("WakeLock1", 34, 123)  // Milliseconds
                .getBytes();
        WakeLockInfo[] wlStats = new WakeLockInfo[1];
        wlStats[0] = createWakeLockInfo("WakeLock2", 10, 1000); // Milliseconds

        readKernelWakelockStats(staleStats, buffer, wlStats);

        assertEquals(2, staleStats.size());

        assertTrue(staleStats.containsKey("WakeLock1"));
        KernelWakelockStats.Entry entry1 = staleStats.get("WakeLock1");
        assertEquals(34, entry1.mCount);
        assertEquals(123 * 1000, entry1.mTotalTime);  // Microseconds

        assertTrue(staleStats.containsKey("WakeLock2"));
        KernelWakelockStats.Entry entry2 = staleStats.get("WakeLock2");
        assertEquals(10, entry2.mCount);
        assertEquals(1000 * 1000, entry2.mTotalTime);  // Microseconds
    }

    @SmallTest
    public void testAggregateStatsUpdate() throws Exception {
        KernelWakelockStats staleStats = new KernelWakelockStats();

        byte[] buffer = new ProcFileBuilder()
                .addLine("WakeLock1", 34, 123)  // Milliseconds
                .addLine("WakeLock2", 46, 345)  // Milliseconds
                .getBytes();
        WakeLockInfo[] wlStats = new WakeLockInfo[2];
        wlStats[0] = createWakeLockInfo("WakeLock3", 10, 1000); // Milliseconds
        wlStats[1] = createWakeLockInfo("WakeLock4", 20, 2000); // Milliseconds

        readKernelWakelockStats(staleStats, buffer, wlStats);

        assertEquals(4, staleStats.size());

        assertTrue(staleStats.containsKey("WakeLock1"));
        assertTrue(staleStats.containsKey("WakeLock2"));
        assertTrue(staleStats.containsKey("WakeLock3"));
        assertTrue(staleStats.containsKey("WakeLock4"));

        KernelWakelockStats.Entry entry1 = staleStats.get("WakeLock1");
        assertEquals(34, entry1.mCount);
        assertEquals(123 * 1000, entry1.mTotalTime); // Microseconds

        KernelWakelockStats.Entry entry2 = staleStats.get("WakeLock2");
        assertEquals(46, entry2.mCount);
        assertEquals(345 * 1000, entry2.mTotalTime); // Microseconds

        KernelWakelockStats.Entry entry3 = staleStats.get("WakeLock3");
        assertEquals(10, entry3.mCount);
        assertEquals(1000 * 1000, entry3.mTotalTime); // Microseconds

        KernelWakelockStats.Entry entry4 = staleStats.get("WakeLock4");
        assertEquals(20, entry4.mCount);
        assertEquals(2000 * 1000, entry4.mTotalTime); // Microseconds

        buffer = new ProcFileBuilder()
                .addLine("WakeLock1", 45, 789)  // Milliseconds
                .addLine("WakeLock1", 56, 123)  // Milliseconds
                .getBytes();
        wlStats = new WakeLockInfo[1];
        wlStats[0] = createWakeLockInfo("WakeLock4", 40, 4000); // Milliseconds

        readKernelWakelockStats(staleStats, buffer, wlStats);

        assertEquals(2, staleStats.size());

        assertTrue(staleStats.containsKey("WakeLock1"));
        assertTrue(staleStats.containsKey("WakeLock4"));

        assertFalse(staleStats.containsKey("WakeLock2"));
        assertFalse(staleStats.containsKey("WakeLock3"));

        entry1 = staleStats.get("WakeLock1");
        assertEquals(45 + 56, entry1.mCount);
        assertEquals((789 + 123) * 1000, entry1.mTotalTime);  // Microseconds

        entry2 = staleStats.get("WakeLock4");
        assertEquals(40, entry2.mCount);
        assertEquals(4000 * 1000, entry4.mTotalTime); // Microseconds
    }
}
