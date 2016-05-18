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

import android.support.test.filters.SmallTest;

import junit.framework.TestCase;

import java.nio.charset.Charset;

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

    private KernelWakelockReader mReader;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mReader = new KernelWakelockReader();
    }

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
        byte[] buffer = new ProcFileBuilder()
                .addLine("Fakelock", 3, 30)
                .getBytes();
        KernelWakelockStats staleStats = new KernelWakelockStats();

        staleStats = mReader.parseProcWakelocks(buffer, buffer.length, true, staleStats);
        assertEquals(1, staleStats.size());
        assertTrue(staleStats.containsKey("Fakelock"));

        buffer = new ProcFileBuilder()
                .addLine("Wakelock", 1, 10)
                .getBytes();

        staleStats = mReader.parseProcWakelocks(buffer, buffer.length, true, staleStats);
        assertEquals(1, staleStats.size());
        assertTrue(staleStats.containsKey("Wakelock"));
        assertFalse(staleStats.containsKey("Fakelock"));
    }
}
