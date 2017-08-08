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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.BufferedReader;
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
 *     ${ANDROID_PRODUCT_OUT}/data/app/FrameworksCoreTests/FrameworksCoreTests.apk
 * Run: adb shell am instrument -e class com.android.internal.os.KernelUidCpuFreqTimeReaderTest -w \
 *     com.android.frameworks.coretests/android.support.test.runner.AndroidJUnitRunner
 *
 * or
 *
 * bit FrameworksCoreTests:com.android.internal.os.KernelUidCpuFreqTimeReaderTest
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class KernelUidCpuFreqTimeReaderTest {
    @Mock private BufferedReader mBufferedReader;
    @Mock private KernelUidCpuFreqTimeReader.Callback mCallback;
    @Mock private PowerProfile mPowerProfile;

    private KernelUidCpuFreqTimeReader mKernelUidCpuFreqTimeReader;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mKernelUidCpuFreqTimeReader = new KernelUidCpuFreqTimeReader();
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
    public void testReadDelta() throws Exception {
        final long[] freqs = {1, 12, 123, 1234, 12345, 123456};
        final int[] uids = {1, 22, 333, 4444, 5555};
        final long[][] times = new long[uids.length][freqs.length];
        for (int i = 0; i < uids.length; ++i) {
            for (int j = 0; j < freqs.length; ++j) {
                times[i][j] = uids[i] * freqs[j] * 10;
            }
        }
        when(mBufferedReader.readLine()).thenReturn(getFreqsLine(freqs));
        long[] actualFreqs = mKernelUidCpuFreqTimeReader.readFreqs(mBufferedReader, mPowerProfile);

        assertArrayEquals(freqs, actualFreqs);
        when(mBufferedReader.readLine())
                .thenReturn(getFreqsLine(freqs), getUidTimesLines(uids, times));
        mKernelUidCpuFreqTimeReader.readDelta(mBufferedReader, mCallback);
        for (int i = 0; i < uids.length; ++i) {
            verify(mCallback).onUidCpuFreqTime(uids[i], times[i]);
        }
        verifyNoMoreInteractions(mCallback);

        // Verify that a second call will only return deltas.
        Mockito.reset(mCallback, mBufferedReader);
        final long[][] newTimes1 = new long[uids.length][freqs.length];
        for (int i = 0; i < uids.length; ++i) {
            for (int j = 0; j < freqs.length; ++j) {
                newTimes1[i][j] = (times[i][j] + uids[i] + freqs[j]) * 10;
            }
        }
        when(mBufferedReader.readLine())
                .thenReturn(getFreqsLine(freqs), getUidTimesLines(uids, newTimes1));
        mKernelUidCpuFreqTimeReader.readDelta(mBufferedReader, mCallback);
        for (int i = 0; i < uids.length; ++i) {
            verify(mCallback).onUidCpuFreqTime(uids[i], subtract(newTimes1[i], times[i]));
        }
        verifyNoMoreInteractions(mCallback);

        // Verify that there won't be a callback if the proc file values didn't change.
        Mockito.reset(mCallback, mBufferedReader);
        when(mBufferedReader.readLine())
                .thenReturn(getFreqsLine(freqs), getUidTimesLines(uids, newTimes1));
        mKernelUidCpuFreqTimeReader.readDelta(mBufferedReader, mCallback);
        verifyNoMoreInteractions(mCallback);

        // Verify that calling with a null callback doesn't result in any crashes
        Mockito.reset(mCallback, mBufferedReader);
        final long[][] newTimes2 = new long[uids.length][freqs.length];
        for (int i = 0; i < uids.length; ++i) {
            for (int j = 0; j < freqs.length; ++j) {
                newTimes2[i][j] = (newTimes1[i][j] + uids[i] * freqs[j]) * 10;
            }
        }
        when(mBufferedReader.readLine())
                .thenReturn(getFreqsLine(freqs), getUidTimesLines(uids, newTimes2));
        mKernelUidCpuFreqTimeReader.readDelta(mBufferedReader, null);

        // Verify that the readDelta call will only return deltas when
        // the previous call had null callback.
        Mockito.reset(mCallback, mBufferedReader);
        final long[][] newTimes3 = new long[uids.length][freqs.length];
        for (int i = 0; i < uids.length; ++i) {
            for (int j = 0; j < freqs.length; ++j) {
                newTimes3[i][j] = (newTimes2[i][j] * (uids[i] + freqs[j])) * 10;
            }
        }
        when(mBufferedReader.readLine())
                .thenReturn(getFreqsLine(freqs), getUidTimesLines(uids, newTimes3));
        mKernelUidCpuFreqTimeReader.readDelta(mBufferedReader, mCallback);
        for (int i = 0; i < uids.length; ++i) {
            verify(mCallback).onUidCpuFreqTime(uids[i], subtract(newTimes3[i], newTimes2[i]));
        }
        verifyNoMoreInteractions(mCallback);
    }

    @Test
    public void testReadDelta_malformedData() throws Exception {
        final long[] freqs = {1, 12, 123, 1234, 12345, 123456};
        final int[] uids = {1, 22, 333, 4444, 5555};
        final long[][] times = new long[uids.length][freqs.length];
        for (int i = 0; i < uids.length; ++i) {
            for (int j = 0; j < freqs.length; ++j) {
                times[i][j] = uids[i] * freqs[j] * 10;
            }
        }
        when(mBufferedReader.readLine()).thenReturn(getFreqsLine(freqs));
        long[] actualFreqs = mKernelUidCpuFreqTimeReader.readFreqs(mBufferedReader, mPowerProfile);

        assertArrayEquals(freqs, actualFreqs);
        when(mBufferedReader.readLine())
                .thenReturn(getFreqsLine(freqs), getUidTimesLines(uids, times));
        mKernelUidCpuFreqTimeReader.readDelta(mBufferedReader, mCallback);
        for (int i = 0; i < uids.length; ++i) {
            verify(mCallback).onUidCpuFreqTime(uids[i], times[i]);
        }
        verifyNoMoreInteractions(mCallback);

        // Verify that there is no callback if any value in the proc file is -ve.
        Mockito.reset(mCallback, mBufferedReader);
        final long[][] newTimes1 = new long[uids.length][freqs.length];
        for (int i = 0; i < uids.length; ++i) {
            for (int j = 0; j < freqs.length; ++j) {
                newTimes1[i][j] = (times[i][j] + uids[i] + freqs[j]) * 10;
            }
        }
        newTimes1[uids.length - 1][freqs.length - 1] *= -1;
        when(mBufferedReader.readLine())
                .thenReturn(getFreqsLine(freqs), getUidTimesLines(uids, newTimes1));
        mKernelUidCpuFreqTimeReader.readDelta(mBufferedReader, mCallback);
        for (int i = 0; i < uids.length; ++i) {
            if (i == uids.length - 1) {
                continue;
            }
            verify(mCallback).onUidCpuFreqTime(uids[i], subtract(newTimes1[i], times[i]));
        }
        verifyNoMoreInteractions(mCallback);

        // Verify that the internal state was not modified when the proc file had -ve value.
        Mockito.reset(mCallback, mBufferedReader);
        for (int i = 0; i < freqs.length; ++i) {
            newTimes1[uids.length - 1][i] = times[uids.length - 1][i];
        }
        when(mBufferedReader.readLine())
                .thenReturn(getFreqsLine(freqs), getUidTimesLines(uids, newTimes1));
        mKernelUidCpuFreqTimeReader.readDelta(mBufferedReader, mCallback);
        verifyNoMoreInteractions(mCallback);

        // Verify that there is no callback if the values in the proc file are decreased.
        Mockito.reset(mCallback, mBufferedReader);
        final long[][] newTimes2 = new long[uids.length][freqs.length];
        for (int i = 0; i < uids.length; ++i) {
            for (int j = 0; j < freqs.length; ++j) {
                newTimes2[i][j] = (newTimes1[i][j] + uids[i] * freqs[j]) * 10;
            }
        }
        newTimes2[uids.length - 1][freqs.length - 1] =
                newTimes1[uids.length - 1][freqs.length - 1] - 222;
        when(mBufferedReader.readLine())
                .thenReturn(getFreqsLine(freqs), getUidTimesLines(uids, newTimes2));
        mKernelUidCpuFreqTimeReader.readDelta(mBufferedReader, mCallback);
        for (int i = 0; i < uids.length; ++i) {
            if (i == uids.length - 1) {
                continue;
            }
            verify(mCallback).onUidCpuFreqTime(uids[i], subtract(newTimes2[i], newTimes1[i]));
        }
        verifyNoMoreInteractions(mCallback);

        // Verify that the internal state was not modified when the proc file had decreasing values.
        Mockito.reset(mCallback, mBufferedReader);
        for (int i = 0; i < freqs.length; ++i) {
            newTimes2[uids.length - 1][i] = newTimes1[uids.length - 1][i];
        }
        when(mBufferedReader.readLine())
                .thenReturn(getFreqsLine(freqs), getUidTimesLines(uids, newTimes2));
        mKernelUidCpuFreqTimeReader.readDelta(mBufferedReader, mCallback);
        verifyNoMoreInteractions(mCallback);
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

    private String[] getUidTimesLines(int[] uids, long[][] times) {
        final String[] lines = new String[uids.length + 1];
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < uids.length; ++i) {
            sb.setLength(0);
            sb.append(uids[i] + ":");
            for (int j = 0; j < times[i].length; ++j) {
                sb.append(" " + times[i][j] / 10);
            }
            lines[i] = sb.toString();
        }
        lines[uids.length] = null;
        return lines;
    }

    private void setCpuClusterFreqs(int numClusters, int... clusterFreqs) {
        assertEquals(numClusters, clusterFreqs.length);
        when(mPowerProfile.getNumCpuClusters()).thenReturn(numClusters);
        for (int i = 0; i < numClusters; ++i) {
            when(mPowerProfile.getNumSpeedStepsInCpuCluster(i)).thenReturn(clusterFreqs[i]);
        }
    }
}
