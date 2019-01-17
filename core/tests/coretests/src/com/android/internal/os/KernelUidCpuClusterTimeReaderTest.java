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
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.when;

import android.util.SparseArray;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;

/**
 * Test class for {@link KernelUidCpuClusterTimeReader}.
 *
 * To run it:
 * bit FrameworksCoreTests:com.android.internal.os.KernelUidCpuClusterTimeReaderTest
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class KernelUidCpuClusterTimeReaderTest {
    @Mock
    private KernelCpuProcReader mProcReader;
    private KernelUidCpuClusterTimeReader mReader;
    private VerifiableCallback mCallback;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mReader = new KernelUidCpuClusterTimeReader(mProcReader);
        mCallback = new VerifiableCallback();
        mReader.setThrottleInterval(0);
    }

    @Test
    public void testReadDelta() throws Exception {
        VerifiableCallback cb = new VerifiableCallback();
        final int cores = 6;
        final int[] clusters = {2, 4};
        final int[] uids = {1, 22, 333, 4444, 5555};

        // Verify initial call
        final long[][] times = increaseTime(new long[uids.length][cores]);
        when(mProcReader.readBytes()).thenReturn(getUidTimesBytes(uids, clusters, times));
        mReader.readDelta(cb);
        for (int i = 0; i < uids.length; i++) {
            cb.verify(uids[i], getTotal(clusters, times[i]));
        }
        cb.verifyNoMoreInteractions();

        // Verify that a second call will only return deltas.
        cb.clear();
        Mockito.reset(mProcReader);
        final long[][] times1 = increaseTime(times);
        when(mProcReader.readBytes()).thenReturn(getUidTimesBytes(uids, clusters, times1));
        mReader.readDelta(cb);
        for (int i = 0; i < uids.length; i++) {
            cb.verify(uids[i], getTotal(clusters, subtract(times1[i], times[i])));
        }
        cb.verifyNoMoreInteractions();

        // Verify that there won't be a callback if the proc file values didn't change.
        cb.clear();
        Mockito.reset(mProcReader);
        when(mProcReader.readBytes()).thenReturn(getUidTimesBytes(uids, clusters, times1));
        mReader.readDelta(cb);
        cb.verifyNoMoreInteractions();

        // Verify that calling with a null callback doesn't result in any crashes
        Mockito.reset(mProcReader);
        final long[][] times2 = increaseTime(times1);
        when(mProcReader.readBytes()).thenReturn(getUidTimesBytes(uids, clusters, times2));
        mReader.readDelta(null);

        // Verify that the readDelta call will only return deltas when
        // the previous call had null callback.
        cb.clear();
        Mockito.reset(mProcReader);
        final long[][] times3 = increaseTime(times2);
        when(mProcReader.readBytes()).thenReturn(getUidTimesBytes(uids, clusters, times3));
        mReader.readDelta(cb);
        for (int i = 0; i < uids.length; i++) {
            cb.verify(uids[i], getTotal(clusters, subtract(times3[i], times2[i])));
        }
        cb.verifyNoMoreInteractions();

    }

    @Test
    public void testReadAbsolute() throws Exception {
        VerifiableCallback cb = new VerifiableCallback();
        final int cores = 6;
        final int[] clusters = {2, 4};
        final int[] uids = {1, 22, 333, 4444, 5555};

        // Verify return absolute value
        final long[][] times = increaseTime(new long[uids.length][cores]);
        when(mProcReader.readBytes()).thenReturn(getUidTimesBytes(uids, clusters, times));
        mReader.readAbsolute(cb);
        for (int i = 0; i < uids.length; i++) {
            cb.verify(uids[i], getTotal(clusters, times[i]));
        }
        cb.verifyNoMoreInteractions();

        // Verify that a second call should return the same absolute value
        cb.clear();
        Mockito.reset(mProcReader);
        final long[][] times1 = increaseTime(times);
        when(mProcReader.readBytes()).thenReturn(getUidTimesBytes(uids, clusters, times1));
        mReader.readAbsolute(cb);
        for (int i = 0; i < uids.length; i++) {
            cb.verify(uids[i], getTotal(clusters, times1[i]));
        }
        cb.verifyNoMoreInteractions();
    }

    @Test
    public void testReadDelta_malformedData() throws Exception {
        final int cores = 6;
        final int[] clusters = {2, 4};
        final int[] uids = {1, 22, 333, 4444, 5555};

        // Verify initial call
        final long[][] times = increaseTime(new long[uids.length][cores]);
        when(mProcReader.readBytes()).thenReturn(getUidTimesBytes(uids, clusters, times));
        mReader.readDelta(mCallback);
        for (int i = 0; i < uids.length; i++) {
            mCallback.verify(uids[i], getTotal(clusters, times[i]));
        }
        mCallback.verifyNoMoreInteractions();

        // Verify that there is no callback if a call has wrong format
        mCallback.clear();
        Mockito.reset(mProcReader);
        final long[][] temp = increaseTime(times);
        final long[][] times1 = new long[uids.length][];
        for (int i = 0; i < temp.length; i++) {
            times1[i] = Arrays.copyOfRange(temp[i], 0, 4);
        }
        when(mProcReader.readBytes()).thenReturn(getUidTimesBytes(uids, clusters, times1));
        mReader.readDelta(mCallback);
        mCallback.verifyNoMoreInteractions();

        // Verify that the internal state was not modified if the given core count does not match
        // the following # of entries.
        mCallback.clear();
        Mockito.reset(mProcReader);
        final long[][] times2 = increaseTime(times);
        when(mProcReader.readBytes()).thenReturn(getUidTimesBytes(uids, clusters, times2));
        mReader.readDelta(mCallback);
        for (int i = 0; i < uids.length; i++) {
            mCallback.verify(uids[i], getTotal(clusters, subtract(times2[i], times[i])));
        }
        mCallback.verifyNoMoreInteractions();

        // Verify that there is no callback if any value in the proc file is -ve.
        mCallback.clear();
        Mockito.reset(mProcReader);
        final long[][] times3 = increaseTime(times2);
        times3[uids.length - 1][cores - 1] *= -1;
        when(mProcReader.readBytes()).thenReturn(getUidTimesBytes(uids, clusters, times3));
        mReader.readDelta(mCallback);
        for (int i = 0; i < uids.length - 1; i++) {
            mCallback.verify(uids[i], getTotal(clusters, subtract(times3[i], times2[i])));
        }
        mCallback.verifyNoMoreInteractions();

        // Verify that the internal state was not modified when the proc file had -ve value.
        mCallback.clear();
        Mockito.reset(mProcReader);
        for (int i = 0; i < cores; i++) {
            times3[uids.length - 1][i] = times2[uids.length - 1][i] + uids[uids.length - 1] * 2520;
        }
        when(mProcReader.readBytes()).thenReturn(getUidTimesBytes(uids, clusters, times3));
        mReader.readDelta(mCallback);
        mCallback.verify(uids[uids.length - 1],
                getTotal(clusters, subtract(times3[uids.length - 1], times2[uids.length - 1])));

        // Verify that there is no callback if the values in the proc file are decreased.
        mCallback.clear();
        Mockito.reset(mProcReader);
        final long[][] times4 = increaseTime(times3);
        System.arraycopy(times3[uids.length - 1], 0, times4[uids.length - 1], 0, cores);
        times4[uids.length - 1][cores - 1] -= 100;
        when(mProcReader.readBytes()).thenReturn(getUidTimesBytes(uids, clusters, times4));
        mReader.readDelta(mCallback);
        for (int i = 0; i < uids.length - 1; i++) {
            mCallback.verify(uids[i], getTotal(clusters, subtract(times4[i], times3[i])));
        }
        mCallback.verifyNoMoreInteractions();

        // Verify that the internal state was not modified when the proc file had decreased values.
        mCallback.clear();
        Mockito.reset(mProcReader);
        for (int i = 0; i < cores; i++) {
            times4[uids.length - 1][i] = times3[uids.length - 1][i] + uids[uids.length - 1] * 2520;
        }
        when(mProcReader.readBytes()).thenReturn(getUidTimesBytes(uids, clusters, times4));
        mReader.readDelta(mCallback);
        mCallback.verify(uids[uids.length - 1],
                getTotal(clusters, subtract(times3[uids.length - 1], times2[uids.length - 1])));
        mCallback.verifyNoMoreInteractions();
    }


    private long[] subtract(long[] a1, long[] a2) {
        long[] val = new long[a1.length];
        for (int i = 0; i < val.length; ++i) {
            val[i] = a1[i] - a2[i];
        }
        return val;
    }

    /**
     * Unit is 10ms. What's special about 2520? 2520 is LCM of 1, 2, 3, ..., 10. So that when we
     * divide shared cpu time by concurrent thread count, we always get a nice integer, avoiding
     * rounding errors.
     */
    private long[][] increaseTime(long[][] original) {
        long[][] newTime = new long[original.length][original[0].length];
        Random rand = new Random();
        for (int i = 0; i < original.length; i++) {
            for (int j = 0; j < original[0].length; j++) {
                newTime[i][j] = original[i][j] + rand.nextInt(1000) * 2520 + 2520;
            }
        }
        return newTime;
    }

    // Format an array of cluster times according to the algorithm in KernelUidCpuClusterTimeReader
    private long[] getTotal(int[] cluster, long[] times) {
        int core = 0;
        long[] sumTimes = new long[cluster.length];
        for (int i = 0; i < cluster.length; i++) {
            double sum = 0;
            for (int j = 0; j < cluster[i]; j++) {
                sum += (double) times[core++] * 10 / (j + 1);
            }
            sumTimes[i] = (long) sum;
        }
        return sumTimes;
    }

    private class VerifiableCallback implements KernelUidCpuClusterTimeReader.Callback {

        SparseArray<long[]> mData = new SparseArray<>();
        int count = 0;

        public void verify(int uid, long[] cpuClusterTimeMs) {
            long[] array = mData.get(uid);
            assertNotNull(array);
            assertArrayEquals(cpuClusterTimeMs, array);
            count++;
        }

        public void clear() {
            mData.clear();
            count = 0;
        }

        @Override
        public void onUidCpuPolicyTime(int uid, long[] cpuClusterTimeMs) {
            long[] array = new long[cpuClusterTimeMs.length];
            System.arraycopy(cpuClusterTimeMs, 0, array, 0, array.length);
            mData.put(uid, array);
        }

        public void verifyNoMoreInteractions() {
            assertEquals(mData.size(), count);
        }
    }

    /**
     * Format uids and times (in 10ms) into the following format:
     * [n, x0, ..., xn, uid0, time0a, time0b, ..., time0n,
     * uid1, time1a, time1b, ..., time1n,
     * uid2, time2a, time2b, ..., time2n, etc.]
     * where n is the number of policies
     * xi is the number cpus on a particular policy
     */
    private ByteBuffer getUidTimesBytes(int[] uids, int[] clusters, long[][] times) {
        int size = (1 + clusters.length + uids.length * (times[0].length + 1)) * 4;
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.order(ByteOrder.nativeOrder());
        buf.putInt(clusters.length);
        for (int i = 0; i < clusters.length; i++) {
            buf.putInt(clusters[i]);
        }
        for (int i = 0; i < uids.length; i++) {
            buf.putInt(uids[i]);
            for (int j = 0; j < times[i].length; j++) {
                buf.putInt((int) (times[i][j]));
            }
        }
        buf.flip();
        return buf.order(ByteOrder.nativeOrder());
    }
}
