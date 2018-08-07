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

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/**
 * Test class for {@link KernelUidCpuActiveTimeReader}.
 *
 * To run it:
 * bit FrameworksCoreTests:com.android.internal.os.KernelUidCpuActiveTimeReaderTest
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class KernelUidCpuActiveTimeReaderTest {
    @Mock
    private KernelCpuProcReader mProcReader;
    @Mock
    private KernelUidCpuActiveTimeReader.Callback mCallback;
    private KernelUidCpuActiveTimeReader mReader;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mReader = new KernelUidCpuActiveTimeReader(mProcReader);
        mReader.setThrottleInterval(0);
    }

    @Test
    public void testReadDelta() {
        final int cores = 8;
        final int[] uids = {1, 22, 333, 4444, 5555};

        final long[][] times = increaseTime(new long[uids.length][cores]);
        when(mProcReader.readBytes()).thenReturn(getUidTimesBytes(uids, times));
        mReader.readDelta(mCallback);
        for (int i = 0; i < uids.length; i++) {
            verify(mCallback).onUidCpuActiveTime(uids[i], getTotal(times[i]));
        }
        verifyNoMoreInteractions(mCallback);

        // Verify that a second call will only return deltas.
        Mockito.reset(mCallback);
        final long[][] times1 = increaseTime(times);
        when(mProcReader.readBytes()).thenReturn(getUidTimesBytes(uids, times1));
        mReader.readDelta(mCallback);
        for (int i = 0; i < uids.length; i++) {
            verify(mCallback).onUidCpuActiveTime(uids[i], getTotal(subtract(times1[i], times[i])));
        }
        verifyNoMoreInteractions(mCallback);

        // Verify that there won't be a callback if the proc file values didn't change.
        Mockito.reset(mCallback);
        when(mProcReader.readBytes()).thenReturn(getUidTimesBytes(uids, times1));
        mReader.readDelta(mCallback);
        verifyNoMoreInteractions(mCallback);

        // Verify that calling with a null callback doesn't result in any crashes
        Mockito.reset(mCallback);
        final long[][] times2 = increaseTime(times1);
        when(mProcReader.readBytes()).thenReturn(getUidTimesBytes(uids, times2));
        mReader.readDelta(null);

        // Verify that the readDelta call will only return deltas when
        // the previous call had null callback.
        Mockito.reset(mCallback);
        final long[][] times3 = increaseTime(times2);
        when(mProcReader.readBytes()).thenReturn(getUidTimesBytes(uids, times3));
        mReader.readDelta(mCallback);
        for (int i = 0; i < uids.length; ++i) {
            verify(mCallback).onUidCpuActiveTime(uids[i], getTotal(subtract(times3[i], times2[i])));
        }
        verifyNoMoreInteractions(mCallback);
    }

    @Test
    public void testReadAbsolute() {
        final int cores = 8;
        final int[] uids = {1, 22, 333, 4444, 5555};

        final long[][] times = increaseTime(new long[uids.length][cores]);
        when(mProcReader.readBytes()).thenReturn(getUidTimesBytes(uids, times));
        mReader.readAbsolute(mCallback);
        for (int i = 0; i < uids.length; i++) {
            verify(mCallback).onUidCpuActiveTime(uids[i], getTotal(times[i]));
        }
        verifyNoMoreInteractions(mCallback);

        // Verify that a second call still returns absolute values
        Mockito.reset(mCallback);
        final long[][] times1 = increaseTime(times);
        when(mProcReader.readBytes()).thenReturn(getUidTimesBytes(uids, times1));
        mReader.readAbsolute(mCallback);
        for (int i = 0; i < uids.length; i++) {
            verify(mCallback).onUidCpuActiveTime(uids[i], getTotal(times1[i]));
        }
        verifyNoMoreInteractions(mCallback);
    }

    @Test
    public void testReadDelta_malformedData() {
        final int cores = 8;
        final int[] uids = {1, 22, 333, 4444, 5555};
        final long[][] times = increaseTime(new long[uids.length][cores]);
        when(mProcReader.readBytes()).thenReturn(getUidTimesBytes(uids, times));
        mReader.readDelta(mCallback);
        for (int i = 0; i < uids.length; i++) {
            verify(mCallback).onUidCpuActiveTime(uids[i], getTotal(times[i]));
        }
        verifyNoMoreInteractions(mCallback);

        // Verify that there is no callback if subsequent call is in wrong format.
        Mockito.reset(mCallback);
        final long[][] times1 = increaseTime(times);
        when(mProcReader.readBytes()).thenReturn(getUidTimesBytes(uids, times1).putInt(0, 5));
        mReader.readDelta(mCallback);
        verifyNoMoreInteractions(mCallback);

        // Verify that the internal state was not modified if the given core count does not match
        // the following # of entries.
        Mockito.reset(mCallback);
        final long[][] times2 = increaseTime(times);
        when(mProcReader.readBytes()).thenReturn(getUidTimesBytes(uids, times2));
        mReader.readDelta(mCallback);
        for (int i = 0; i < uids.length; i++) {
            verify(mCallback).onUidCpuActiveTime(uids[i], getTotal(subtract(times2[i], times[i])));
        }
        verifyNoMoreInteractions(mCallback);

        // Verify that there is no callback if any value in the proc file is -ve.
        Mockito.reset(mCallback);
        final long[][] times3 = increaseTime(times2);
        times3[uids.length - 1][cores - 1] *= -1;
        when(mProcReader.readBytes()).thenReturn(getUidTimesBytes(uids, times3));
        mReader.readDelta(mCallback);
        for (int i = 0; i < uids.length - 1; ++i) {
            verify(mCallback).onUidCpuActiveTime(uids[i], getTotal(subtract(times3[i], times2[i])));
        }
        verifyNoMoreInteractions(mCallback);

        // Verify that the internal state was not modified when the proc file had -ve value.
        Mockito.reset(mCallback);
        for (int i = 0; i < cores; i++) {
            times3[uids.length - 1][i] = times2[uids.length - 1][i] + uids[uids.length - 1] * 2520;
        }
        when(mProcReader.readBytes()).thenReturn(getUidTimesBytes(uids, times3));
        mReader.readDelta(mCallback);
        verify(mCallback).onUidCpuActiveTime(uids[uids.length - 1],
                getTotal(subtract(times3[uids.length - 1], times2[uids.length - 1])));
        verifyNoMoreInteractions(mCallback);

        // Verify that there is no callback if the values in the proc file are decreased.
        Mockito.reset(mCallback);
        final long[][] times4 = increaseTime(times3);
        System.arraycopy(times3[uids.length - 1], 0, times4[uids.length - 1], 0, cores);
        times4[uids.length - 1][cores - 1] -= 100;
        when(mProcReader.readBytes()).thenReturn(getUidTimesBytes(uids, times4));
        mReader.readDelta(mCallback);
        for (int i = 0; i < uids.length - 1; ++i) {
            verify(mCallback).onUidCpuActiveTime(uids[i], getTotal(subtract(times4[i], times3[i])));
        }
        verifyNoMoreInteractions(mCallback);

        // Verify that the internal state was not modified when the proc file had decreased values.
        Mockito.reset(mCallback);
        for (int i = 0; i < cores; i++) {
            times4[uids.length - 1][i] = times3[uids.length - 1][i] + uids[uids.length - 1] * 2520;
        }
        when(mProcReader.readBytes()).thenReturn(getUidTimesBytes(uids, times4));
        mReader.readDelta(mCallback);
        verify(mCallback).onUidCpuActiveTime(uids[uids.length - 1],
                getTotal(subtract(times4[uids.length - 1], times3[uids.length - 1])));
        verifyNoMoreInteractions(mCallback);
    }

    private long[] subtract(long[] a1, long[] a2) {
        long[] val = new long[a1.length];
        for (int i = 0; i < val.length; ++i) {
            val[i] = a1[i] - a2[i];
        }
        return val;
    }

    /**
     * Unit of original and return value is 10ms. What's special about 2520? 2520 is LCM of 1, 2, 3,
     * ..., 10. So that when wedivide shared cpu time by concurrent thread count, we always get a
     * nice integer, avoiding rounding errors.
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

    // Unit of times is 10ms
    private long getTotal(long[] times) {
        long sum = 0;
        for (int i = 0; i < times.length; i++) {
            sum += times[i] * 10 / (i + 1);
        }
        return sum;
    }

    /**
     * Format uids and times (in 10ms) into the following format:
     * [n, uid0, time0a, time0b, ..., time0n,
     * uid1, time1a, time1b, ..., time1n,
     * uid2, time2a, time2b, ..., time2n, etc.]
     * where n is the total number of cpus (num_possible_cpus)
     */
    private ByteBuffer getUidTimesBytes(int[] uids, long[][] times) {
        int size = (1 + uids.length * (times[0].length + 1)) * 4;
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.order(ByteOrder.nativeOrder());
        buf.putInt(times[0].length);
        for (int i = 0; i < uids.length; i++) {
            buf.putInt(uids[i]);
            for (int j = 0; j < times[i].length; j++) {
                buf.putInt((int) times[i][j]);
            }
        }
        buf.flip();
        return buf.order(ByteOrder.nativeOrder());
    }
}
