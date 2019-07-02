/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.util.SparseArray;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.KernelSingleUidTimeReader.Injector;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class KernelSingleUidTimeReaderTest {
    private final static int TEST_UID = 2222;
    private final static int TEST_FREQ_COUNT = 5;

    private KernelSingleUidTimeReader mReader;
    private TestInjector mInjector;

    @Before
    public void setUp() {
        mInjector = new TestInjector();
        mReader = new KernelSingleUidTimeReader(TEST_FREQ_COUNT, mInjector);
    }

    @Test
    public void readDelta() {
        final SparseArray<long[]> allLastCpuTimes = mReader.getLastUidCpuTimeMs();
        long[] latestCpuTimes = new long[] {120, 130, 140, 150, 160};
        mInjector.setData(latestCpuTimes);
        long[] deltaCpuTimes = mReader.readDeltaMs(TEST_UID);
        assertCpuTimesEqual(latestCpuTimes, deltaCpuTimes);
        assertCpuTimesEqual(latestCpuTimes, allLastCpuTimes.get(TEST_UID));

        long[] expectedDeltaTimes = new long[] {200, 340, 1230, 490, 4890};
        for (int i = 0; i < latestCpuTimes.length; ++i) {
            latestCpuTimes[i] += expectedDeltaTimes[i];
        }
        mInjector.setData(latestCpuTimes);
        deltaCpuTimes = mReader.readDeltaMs(TEST_UID);
        assertCpuTimesEqual(expectedDeltaTimes, deltaCpuTimes);
        assertCpuTimesEqual(latestCpuTimes, allLastCpuTimes.get(TEST_UID));

        // delta should be null if cpu times haven't changed
        deltaCpuTimes = mReader.readDeltaMs(TEST_UID);
        assertCpuTimesEqual(null, deltaCpuTimes);
        assertCpuTimesEqual(latestCpuTimes, allLastCpuTimes.get(TEST_UID));

        // Malformed data (-ve)
        long[] malformedLatestTimes = new long[latestCpuTimes.length];
        for (int i = 0; i < latestCpuTimes.length; ++i) {
            if (i == 1) {
                malformedLatestTimes[i] = -4;
            } else {
                malformedLatestTimes[i] = latestCpuTimes[i] + i * 42;
            }
        }
        mInjector.setData(malformedLatestTimes);
        deltaCpuTimes = mReader.readDeltaMs(TEST_UID);
        assertCpuTimesEqual(null, deltaCpuTimes);
        assertCpuTimesEqual(latestCpuTimes, allLastCpuTimes.get(TEST_UID));

        // Malformed data (decreased)
        malformedLatestTimes = new long[latestCpuTimes.length];
        for (int i = 0; i < latestCpuTimes.length; ++i) {
            if (i == 1) {
                malformedLatestTimes[i] = latestCpuTimes[i] - 4;
            } else {
                malformedLatestTimes[i] = latestCpuTimes[i] + i * 42;
            }
        }
        mInjector.setData(malformedLatestTimes);
        deltaCpuTimes = mReader.readDeltaMs(TEST_UID);
        assertCpuTimesEqual(null, deltaCpuTimes);
        assertCpuTimesEqual(latestCpuTimes, allLastCpuTimes.get(TEST_UID));
    }

    @Test
    public void readDelta_fileNotAvailable() {
        mInjector.letReadDataThrowException(true);

        for (int i = 0; i < KernelSingleUidTimeReader.TOTAL_READ_ERROR_COUNT; ++i) {
            assertTrue(mReader.singleUidCpuTimesAvailable());
            mReader.readDeltaMs(TEST_UID);
        }
        assertFalse(mReader.singleUidCpuTimesAvailable());
    }

    @Test
    public void readDelta_incorrectCount() {
        assertTrue(mReader.singleUidCpuTimesAvailable());

        long[] cpuTimes = new long[TEST_FREQ_COUNT - 1];
        for (int i = 0; i < cpuTimes.length; ++i) {
            cpuTimes[i] = 111 + i;
        }
        mInjector.setData(cpuTimes);
        assertCpuTimesEqual(null, mReader.readDeltaMs(TEST_UID));
        assertFalse(mReader.singleUidCpuTimesAvailable());

        // Reset
        mReader.setSingleUidCpuTimesAvailable(true);

        cpuTimes = new long[TEST_FREQ_COUNT + 1];
        for (int i = 0; i < cpuTimes.length; ++i) {
            cpuTimes[i] = 222 + i;
        }
        mInjector.setData(cpuTimes);
        assertCpuTimesEqual(null, mReader.readDeltaMs(TEST_UID));
        assertFalse(mReader.singleUidCpuTimesAvailable());
    }

    @Test
    public void testComputeDelta() {
        // proc file not available
        mReader.setSingleUidCpuTimesAvailable(false);
        long[] latestCpuTimes = new long[] {12, 13, 14, 15, 16};
        long[] deltaCpuTimes = mReader.computeDelta(TEST_UID, latestCpuTimes);
        assertCpuTimesEqual(null, deltaCpuTimes);

        // cpu times have changed
        mReader.setSingleUidCpuTimesAvailable(true);
        SparseArray<long[]> allLastCpuTimes = mReader.getLastUidCpuTimeMs();
        long[] lastCpuTimes = new long[] {12, 13, 14, 15, 16};
        allLastCpuTimes.put(TEST_UID, lastCpuTimes);
        long[] expectedDeltaTimes = new long[] {123, 324, 43, 989, 80};
        for (int i = 0; i < latestCpuTimes.length; ++i) {
            latestCpuTimes[i] = lastCpuTimes[i] + expectedDeltaTimes[i];
        }
        deltaCpuTimes = mReader.computeDelta(TEST_UID, latestCpuTimes);
        assertCpuTimesEqual(expectedDeltaTimes, deltaCpuTimes);
        assertCpuTimesEqual(latestCpuTimes, allLastCpuTimes.get(TEST_UID));

        // no change in cpu times
        deltaCpuTimes = mReader.computeDelta(TEST_UID, latestCpuTimes);
        assertCpuTimesEqual(null, deltaCpuTimes);
        assertCpuTimesEqual(latestCpuTimes, allLastCpuTimes.get(TEST_UID));

        // Malformed cpu times (-ve)
        long[] malformedLatestTimes = new long[latestCpuTimes.length];
        for (int i = 0; i < latestCpuTimes.length; ++i) {
            if (i == 1) {
                malformedLatestTimes[i] = -4;
            } else {
                malformedLatestTimes[i] = latestCpuTimes[i] + i * 42;
            }
        }
        deltaCpuTimes = mReader.computeDelta(TEST_UID, malformedLatestTimes);
        assertCpuTimesEqual(null, deltaCpuTimes);
        assertCpuTimesEqual(latestCpuTimes, allLastCpuTimes.get(TEST_UID));

        // Malformed cpu times (decreased)
        for (int i = 0; i < latestCpuTimes.length; ++i) {
            if (i == 1) {
                malformedLatestTimes[i] = latestCpuTimes[i] - 4;
            } else {
                malformedLatestTimes[i] = latestCpuTimes[i] + i * 42;
            }
        }
        deltaCpuTimes = mReader.computeDelta(TEST_UID, malformedLatestTimes);
        assertCpuTimesEqual(null, deltaCpuTimes);
        assertCpuTimesEqual(latestCpuTimes, allLastCpuTimes.get(TEST_UID));
    }

    @Test
    public void testGetDelta() {
        // No last cpu times
        long[] lastCpuTimes = null;
        long[] latestCpuTimes = new long[] {12, 13, 14, 15, 16};
        long[] deltaCpuTimes = mReader.getDeltaLocked(lastCpuTimes, latestCpuTimes);
        assertCpuTimesEqual(latestCpuTimes, deltaCpuTimes);

        // Latest cpu times are -ve
        lastCpuTimes = new long[] {12, 13, 14, 15, 16};
        latestCpuTimes = new long[] {15, -10, 19, 21, 23};
        deltaCpuTimes = mReader.getDeltaLocked(lastCpuTimes, latestCpuTimes);
        assertCpuTimesEqual(null, deltaCpuTimes);

        // Latest cpu times are less than last cpu times
        lastCpuTimes = new long[] {12, 13, 14, 15, 16};
        latestCpuTimes = new long[] {15, 11, 21, 34, 171};
        deltaCpuTimes = mReader.getDeltaLocked(lastCpuTimes, latestCpuTimes);
        assertCpuTimesEqual(null, deltaCpuTimes);

        lastCpuTimes = new long[] {12, 13, 14, 15, 16};
        latestCpuTimes = new long[] {112, 213, 314, 415, 516};
        deltaCpuTimes = mReader.getDeltaLocked(lastCpuTimes, latestCpuTimes);
        assertCpuTimesEqual(new long[] {100, 200, 300, 400, 500}, deltaCpuTimes);
    }

    @Test
    public void testRemoveUid() {
        final SparseArray<long[]> lastUidCpuTimes = mReader.getLastUidCpuTimeMs();
        lastUidCpuTimes.put(12, new long[] {});
        lastUidCpuTimes.put(16, new long[] {});

        mReader.removeUid(12);
        assertFalse("Removal failed, cpuTimes=" + lastUidCpuTimes,
                lastUidCpuTimes.indexOfKey(12) >= 0);
        mReader.removeUid(16);
        assertFalse("Removal failed, cpuTimes=" + lastUidCpuTimes,
                lastUidCpuTimes.indexOfKey(16) >= 0);
    }

    @Test
    public void testRemoveUidsRange() {
        final SparseArray<long[]> lastUidCpuTimes = mReader.getLastUidCpuTimeMs();
        final int startUid = 12;
        final int endUid = 24;

        for (int i = startUid; i <= endUid; ++i) {
            lastUidCpuTimes.put(startUid, new long[] {});
        }
        mReader.removeUidsInRange(startUid, endUid);
        assertEquals("There shouldn't be any items left, cpuTimes=" + lastUidCpuTimes,
                0, lastUidCpuTimes.size());

        for (int i = startUid; i <= endUid; ++i) {
            lastUidCpuTimes.put(startUid, new long[] {});
        }
        mReader.removeUidsInRange(startUid - 1, endUid);
        assertEquals("There shouldn't be any items left, cpuTimes=" + lastUidCpuTimes,
                0, lastUidCpuTimes.size());

        for (int i = startUid; i <= endUid; ++i) {
            lastUidCpuTimes.put(startUid, new long[] {});
        }
        mReader.removeUidsInRange(startUid, endUid + 1);
        assertEquals("There shouldn't be any items left, cpuTimes=" + lastUidCpuTimes,
                0, lastUidCpuTimes.size());

        for (int i = startUid; i <= endUid; ++i) {
            lastUidCpuTimes.put(startUid, new long[] {});
        }
        mReader.removeUidsInRange(startUid - 1, endUid + 1);
        assertEquals("There shouldn't be any items left, cpuTimes=" + lastUidCpuTimes,
                0, lastUidCpuTimes.size());
    }

    private void assertCpuTimesEqual(long[] expected, long[] actual) {
        assertArrayEquals("Expected=" + Arrays.toString(expected)
                + ", Actual=" + Arrays.toString(actual), expected, actual);
    }

    class TestInjector extends Injector {
        private byte[] mData;
        private boolean mThrowExcpetion;

        @Override
        public byte[] readData(String procFile) throws IOException {
            if (mThrowExcpetion) {
                throw new IOException("In the test");
            } else {
                return mData;
            }
        }

        public void setData(long[] cpuTimes) {
            final ByteBuffer buffer = ByteBuffer.allocate(cpuTimes.length * Long.BYTES);
            buffer.order(ByteOrder.nativeOrder());
            for (long time : cpuTimes) {
                buffer.putLong(time / 10);
            }
            mData = buffer.array();
        }

        public void letReadDataThrowException(boolean throwException) {
            mThrowExcpetion = throwException;
        }
    }
}
