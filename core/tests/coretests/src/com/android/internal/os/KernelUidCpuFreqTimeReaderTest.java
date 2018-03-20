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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.SparseArray;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.BufferedReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Test class for {@link KernelUidCpuFreqTimeReader}.
 *
 * To run the tests, use
 *
 * runtest -c com.android.internal.os.KernelUidCpuFreqTimeReaderTest frameworks-core
 *
 * or the following steps:
 *
 * Build: m FrameworksCoreTests
 * Install: adb install -r \
 * ${ANDROID_PRODUCT_OUT}/data/app/FrameworksCoreTests/FrameworksCoreTests.apk
 * Run: adb shell am instrument -e class com.android.internal.os.KernelUidCpuFreqTimeReaderTest -w \
 * com.android.frameworks.coretests/android.support.test.runner.AndroidJUnitRunner
 *
 * or
 *
 * bit FrameworksCoreTests:com.android.internal.os.KernelUidCpuFreqTimeReaderTest
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class KernelUidCpuFreqTimeReaderTest {
    @Mock
    private BufferedReader mBufferedReader;
    @Mock
    private KernelUidCpuFreqTimeReader.Callback mCallback;
    @Mock
    private PowerProfile mPowerProfile;
    @Mock
    private KernelCpuProcReader mProcReader;

    private KernelUidCpuFreqTimeReader mKernelUidCpuFreqTimeReader;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mKernelUidCpuFreqTimeReader = new KernelUidCpuFreqTimeReader(mProcReader);
        mKernelUidCpuFreqTimeReader.setThrottleInterval(0);
    }

    @Test
    public void testReadFreqs_perClusterTimesNotAvailable() throws Exception {
        final long[][] freqs = {
                {1, 12, 123, 1234},
                {1, 12, 123, 23, 123, 1234, 12345, 123456},
                {1, 12, 123, 23, 123, 1234, 12345, 123456, 12, 123, 12345},
                {1, 12, 123, 23, 2345, 234567}
        };
        final int[] numClusters = {2, 2, 3, 1};
        final int[][] numFreqs = {{3, 6}, {4, 5}, {3, 5, 4}, {3}};
        for (int i = 0; i < freqs.length; ++i) {
            setCpuClusterFreqs(numClusters[i], numFreqs[i]);
            when(mBufferedReader.readLine()).thenReturn(getFreqsLine(freqs[i]));
            long[] actualFreqs = mKernelUidCpuFreqTimeReader.readFreqs(
                    mBufferedReader, mPowerProfile);
            assertArrayEquals(freqs[i], actualFreqs);
            verifyZeroInteractions(mCallback);
            final String errMsg = String.format("Freqs=%s, nClusters=%d, nFreqs=%s",
                    Arrays.toString(freqs[i]), numClusters[i], Arrays.toString(numFreqs[i]));
            assertFalse(errMsg, mKernelUidCpuFreqTimeReader.perClusterTimesAvailable());

            // Verify that a second call won't read the proc file again
            Mockito.reset(mBufferedReader);
            actualFreqs = mKernelUidCpuFreqTimeReader.readFreqs(mPowerProfile);
            assertArrayEquals(freqs[i], actualFreqs);
            assertFalse(errMsg, mKernelUidCpuFreqTimeReader.perClusterTimesAvailable());

            // Prepare for next iteration
            Mockito.reset(mBufferedReader, mPowerProfile);
        }
    }

    @Test
    public void testReadFreqs_perClusterTimesAvailable() throws Exception {
        final long[][] freqs = {
                {1, 12, 123, 1234},
                {1, 12, 123, 23, 123, 1234, 12345, 123456},
                {1, 12, 123, 23, 123, 1234, 12345, 123456, 12, 123, 12345, 1234567}
        };
        final int[] numClusters = {1, 2, 3};
        final int[][] numFreqs = {{4}, {3, 5}, {3, 5, 4}};
        for (int i = 0; i < freqs.length; ++i) {
            setCpuClusterFreqs(numClusters[i], numFreqs[i]);
            when(mBufferedReader.readLine()).thenReturn(getFreqsLine(freqs[i]));
            long[] actualFreqs = mKernelUidCpuFreqTimeReader.readFreqs(
                    mBufferedReader, mPowerProfile);
            assertArrayEquals(freqs[i], actualFreqs);
            verifyZeroInteractions(mCallback);
            final String errMsg = String.format("Freqs=%s, nClusters=%d, nFreqs=%s",
                    Arrays.toString(freqs[i]), numClusters[i], Arrays.toString(numFreqs[i]));
            assertTrue(errMsg, mKernelUidCpuFreqTimeReader.perClusterTimesAvailable());

            // Verify that a second call won't read the proc file again
            Mockito.reset(mBufferedReader);
            actualFreqs = mKernelUidCpuFreqTimeReader.readFreqs(mPowerProfile);
            assertArrayEquals(freqs[i], actualFreqs);
            assertTrue(errMsg, mKernelUidCpuFreqTimeReader.perClusterTimesAvailable());

            // Prepare for next iteration
            Mockito.reset(mBufferedReader, mPowerProfile);
        }
    }

    @Test
    public void testReadDelta_Binary() throws Exception {
        VerifiableCallback cb = new VerifiableCallback();
        final long[] freqs = {110, 123, 145, 167, 289, 997};
        final int[] uids = {1, 22, 333, 444, 555};
        final long[][] times = new long[uids.length][freqs.length];
        for (int i = 0; i < uids.length; ++i) {
            for (int j = 0; j < freqs.length; ++j) {
                times[i][j] = uids[i] * freqs[j] * 10;
            }
        }
        when(mBufferedReader.readLine()).thenReturn(getFreqsLine(freqs));
        long[] actualFreqs = mKernelUidCpuFreqTimeReader.readFreqs(mBufferedReader, mPowerProfile);

        assertArrayEquals(freqs, actualFreqs);
        when(mProcReader.readBytes()).thenReturn(getUidTimesBytes(uids, times));
        mKernelUidCpuFreqTimeReader.readDeltaImpl(cb);
        for (int i = 0; i < uids.length; ++i) {
            cb.verify(uids[i], times[i]);
        }
        cb.verifyNoMoreInteractions();

        // Verify that a second call will only return deltas.
        cb.clear();
        Mockito.reset(mProcReader);
        final long[][] newTimes1 = new long[uids.length][freqs.length];
        for (int i = 0; i < uids.length; ++i) {
            for (int j = 0; j < freqs.length; ++j) {
                newTimes1[i][j] = times[i][j] + (uids[i] + freqs[j]) * 50;
            }
        }
        when(mProcReader.readBytes()).thenReturn(getUidTimesBytes(uids, newTimes1));
        mKernelUidCpuFreqTimeReader.readDeltaImpl(cb);
        for (int i = 0; i < uids.length; ++i) {
            cb.verify(uids[i], subtract(newTimes1[i], times[i]));
        }
        cb.verifyNoMoreInteractions();

        // Verify that there won't be a callback if the proc file values didn't change.
        cb.clear();
        Mockito.reset(mProcReader);
        when(mProcReader.readBytes()).thenReturn(getUidTimesBytes(uids, newTimes1));
        mKernelUidCpuFreqTimeReader.readDeltaImpl(cb);
        cb.verifyNoMoreInteractions();

        // Verify that calling with a null callback doesn't result in any crashes
        cb.clear();
        Mockito.reset(mProcReader);
        final long[][] newTimes2 = new long[uids.length][freqs.length];
        for (int i = 0; i < uids.length; ++i) {
            for (int j = 0; j < freqs.length; ++j) {
                newTimes2[i][j] = newTimes1[i][j] + (uids[i] * freqs[j]) * 30;
            }
        }
        when(mProcReader.readBytes()).thenReturn(getUidTimesBytes(uids, newTimes2));
        mKernelUidCpuFreqTimeReader.readDeltaImpl(null);
        cb.verifyNoMoreInteractions();

        // Verify that the readDelta call will only return deltas when
        // the previous call had null callback.
        cb.clear();
        Mockito.reset(mProcReader);
        final long[][] newTimes3 = new long[uids.length][freqs.length];
        for (int i = 0; i < uids.length; ++i) {
            for (int j = 0; j < freqs.length; ++j) {
                newTimes3[i][j] = newTimes2[i][j] + (uids[i] + freqs[j]) * 40;
            }
        }
        when(mProcReader.readBytes()).thenReturn(getUidTimesBytes(uids, newTimes3));
        mKernelUidCpuFreqTimeReader.readDeltaImpl(cb);
        for (int i = 0; i < uids.length; ++i) {
            cb.verify(uids[i], subtract(newTimes3[i], newTimes2[i]));
        }
        cb.verifyNoMoreInteractions();
    }

    @Test
    public void testReadAbsolute() throws Exception {
        VerifiableCallback cb = new VerifiableCallback();
        final long[] freqs = {110, 123, 145, 167, 289, 997};
        final int[] uids = {1, 22, 333, 444, 555};
        final long[][] times = new long[uids.length][freqs.length];
        for (int i = 0; i < uids.length; ++i) {
            for (int j = 0; j < freqs.length; ++j) {
                times[i][j] = uids[i] * freqs[j] * 10;
            }
        }
        when(mBufferedReader.readLine()).thenReturn(getFreqsLine(freqs));
        long[] actualFreqs = mKernelUidCpuFreqTimeReader.readFreqs(mBufferedReader, mPowerProfile);

        assertArrayEquals(freqs, actualFreqs);
        // Verify that the absolute values are returned
        when(mProcReader.readBytes()).thenReturn(getUidTimesBytes(uids, times));
        mKernelUidCpuFreqTimeReader.readAbsolute(cb);
        for (int i = 0; i < uids.length; ++i) {
            cb.verify(uids[i], times[i]);
        }
        cb.verifyNoMoreInteractions();

        // Verify that a second call should still return absolute values
        cb.clear();
        Mockito.reset(mProcReader);
        final long[][] newTimes1 = new long[uids.length][freqs.length];
        for (int i = 0; i < uids.length; ++i) {
            for (int j = 0; j < freqs.length; ++j) {
                newTimes1[i][j] = times[i][j] + (uids[i] + freqs[j]) * 50;
            }
        }
        when(mProcReader.readBytes()).thenReturn(getUidTimesBytes(uids, newTimes1));
        mKernelUidCpuFreqTimeReader.readAbsolute(cb);
        for (int i = 0; i < uids.length; ++i) {
            cb.verify(uids[i], newTimes1[i]);
        }
        cb.verifyNoMoreInteractions();
    }

    private long[] subtract(long[] a1, long[] a2) {
        long[] val = new long[a1.length];
        for (int i = 0; i < val.length; ++i) {
            val[i] = a1[i] - a2[i];
        }
        return val;
    }

    private String getFreqsLine(long[] freqs) {
        final StringBuilder sb = new StringBuilder();
        sb.append("uid:");
        for (int i = 0; i < freqs.length; ++i) {
            sb.append(" " + freqs[i]);
        }
        return sb.toString();
    }

    private ByteBuffer getUidTimesBytes(int[] uids, long[][] times) {
        int size = (1 + uids.length + uids.length * times[0].length) * 4;
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.order(ByteOrder.nativeOrder());
        buf.putInt(times[0].length);
        for (int i = 0; i < uids.length; i++) {
            buf.putInt(uids[i]);
            for (int j = 0; j < times[i].length; j++) {
                buf.putInt((int) (times[i][j] / 10));
            }
        }
        buf.flip();
        return buf.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    }

    private void setCpuClusterFreqs(int numClusters, int... clusterFreqs) {
        assertEquals(numClusters, clusterFreqs.length);
        when(mPowerProfile.getNumCpuClusters()).thenReturn(numClusters);
        for (int i = 0; i < numClusters; ++i) {
            when(mPowerProfile.getNumSpeedStepsInCpuCluster(i)).thenReturn(clusterFreqs[i]);
        }
    }

    private class VerifiableCallback implements KernelUidCpuFreqTimeReader.Callback {

        SparseArray<long[]> mData = new SparseArray<>();
        int count = 0;

        public void verify(int uid, long[] cpuFreqTimeMs) {
            long[] array = mData.get(uid);
            assertNotNull(array);
            assertArrayEquals(cpuFreqTimeMs, array);
            count++;
        }

        public void clear() {
            mData.clear();
            count = 0;
        }

        @Override
        public void onUidCpuFreqTime(int uid, long[] cpuFreqTimeMs) {
            long[] array = new long[cpuFreqTimeMs.length];
            System.arraycopy(cpuFreqTimeMs, 0, array, 0, array.length);
            mData.put(uid, array);
        }

        public void verifyNoMoreInteractions() {
            assertEquals(mData.size(), count);
        }
    }
}
