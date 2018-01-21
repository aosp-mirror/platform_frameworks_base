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

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.BufferedReader;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Test class for {@link KernelUidCpuActiveTimeReader}.
 *
 * To run it:
 * bit FrameworksCoreTests:com.android.internal.os.KernelUidCpuActiveTimeReaderTest
 *
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class KernelUidCpuActiveTimeReaderTest {
    @Mock private BufferedReader mBufferedReader;
    @Mock private KernelUidCpuActiveTimeReader.Callback mCallback;

    private KernelUidCpuActiveTimeReader mReader;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mReader = new KernelUidCpuActiveTimeReader();
    }

    public class Temp {

        public void method() {
            method1(new long[][]{{1,2,3}, {2,3,4}});
            method1(new long[][]{{2,2,3}, {2,3,4}});
        }
        public int method1(long[][] array) {
            return array.length * array[0].length;
        }
    }

    @Test
    public void testReadDelta() throws Exception {
        final int cores = 8;
        final String info = "active: 8";
        final int[] uids = {1, 22, 333, 4444, 5555};

        final long[][] times = increaseTime(new long[uids.length][cores]);
        when(mBufferedReader.readLine()).thenReturn(info, formatTime(uids, times));
        mReader.readDeltaInternal(mBufferedReader, mCallback);
        for(int i=0;i<uids.length;i++){
            verify(mCallback).onUidCpuActiveTime(uids[i], getTotal(times[i]));
        }
        verifyNoMoreInteractions(mCallback);

        // Verify that a second call will only return deltas.
        Mockito.reset(mCallback, mBufferedReader);
        final long[][] times1 = increaseTime(times);
        when(mBufferedReader.readLine()).thenReturn(info, formatTime(uids, times1));
        mReader.readDeltaInternal(mBufferedReader, mCallback);
        for(int i=0;i<uids.length;i++){
            verify(mCallback).onUidCpuActiveTime(uids[i], getTotal(subtract(times1[i], times[i])));
        }
        verifyNoMoreInteractions(mCallback);

        // Verify that there won't be a callback if the proc file values didn't change.
        Mockito.reset(mCallback, mBufferedReader);
        when(mBufferedReader.readLine()).thenReturn(info, formatTime(uids, times1));
        mReader.readDeltaInternal(mBufferedReader, mCallback);
        verifyNoMoreInteractions(mCallback);

        // Verify that calling with a null callback doesn't result in any crashes
        Mockito.reset(mCallback, mBufferedReader);
        final long[][] times2 = increaseTime(times1);
        when(mBufferedReader.readLine()).thenReturn(info, formatTime(uids, times2));
        mReader.readDeltaInternal(mBufferedReader, null);

        // Verify that the readDelta call will only return deltas when
        // the previous call had null callback.
        Mockito.reset(mCallback, mBufferedReader);
        final long[][] times3 = increaseTime(times2);
        when(mBufferedReader.readLine()).thenReturn(info, formatTime(uids, times3));
        mReader.readDeltaInternal(mBufferedReader, mCallback);
        for (int i = 0; i < uids.length; ++i) {
            verify(mCallback).onUidCpuActiveTime(uids[i], getTotal(subtract(times3[i], times2[i])));
        }
        verifyNoMoreInteractions(mCallback);
    }

    @Test
    public void testReadDelta_malformedData() throws Exception {
        final int cores = 8;
        final String info = "active: 8";
        final int[] uids = {1, 22, 333, 4444, 5555};
        final long[][] times = increaseTime(new long[uids.length][cores]);
        when(mBufferedReader.readLine()).thenReturn(info, formatTime(uids, times));
        mReader.readDeltaInternal(mBufferedReader, mCallback);
        for(int i=0;i<uids.length;i++){
            verify(mCallback).onUidCpuActiveTime(uids[i], getTotal(times[i]));
        }
        verifyNoMoreInteractions(mCallback);

        // Verify that there is no callback if subsequent call provides wrong # of entries.
        Mockito.reset(mCallback, mBufferedReader);
        final long[][] temp = increaseTime(times);
        final long[][] times1 = new long[uids.length][];
        for(int i=0;i<temp.length;i++){
            times1[i] = Arrays.copyOfRange(temp[i], 0, 6);
        }
        when(mBufferedReader.readLine()).thenReturn(info, formatTime(uids, times1));
        mReader.readDeltaInternal(mBufferedReader, mCallback);
        verifyNoMoreInteractions(mCallback);

        // Verify that the internal state was not modified if the given core count does not match
        // the following # of entries.
        Mockito.reset(mCallback, mBufferedReader);
        final long[][] times2 = increaseTime(times);
        when(mBufferedReader.readLine()).thenReturn(info, formatTime(uids, times2));
        mReader.readDeltaInternal(mBufferedReader, mCallback);
        for(int i=0;i<uids.length;i++){
            verify(mCallback).onUidCpuActiveTime(uids[i], getTotal(subtract(times2[i], times[i])));
        }
        verifyNoMoreInteractions(mCallback);

        // Verify that there is no callback if any value in the proc file is -ve.
        Mockito.reset(mCallback, mBufferedReader);
        final long[][] times3 = increaseTime(times2);
        times3[uids.length - 1][cores - 1] *= -1;
        when(mBufferedReader.readLine()).thenReturn(info, formatTime(uids, times3));
        mReader.readDeltaInternal(mBufferedReader, mCallback);
        for (int i = 0; i < uids.length - 1; ++i) {
            verify(mCallback).onUidCpuActiveTime(uids[i], getTotal(subtract(times3[i], times2[i])));
        }
        verifyNoMoreInteractions(mCallback);

        // Verify that the internal state was not modified when the proc file had -ve value.
        Mockito.reset(mCallback, mBufferedReader);
        for (int i = 0; i < cores; i++) {
            times3[uids.length - 1][i] = times2[uids.length - 1][i] + uids[uids.length - 1] * 1000;
        }
        when(mBufferedReader.readLine()).thenReturn(info, formatTime(uids, times3));
        mReader.readDeltaInternal(mBufferedReader, mCallback);
        verify(mCallback).onUidCpuActiveTime(uids[uids.length - 1], getTotal(subtract(times3[uids.length - 1], times2[uids.length - 1])));
        verifyNoMoreInteractions(mCallback);

        // Verify that there is no callback if the values in the proc file are decreased.
        Mockito.reset(mCallback, mBufferedReader);
        final long[][] times4 = increaseTime(times3);
        times4[uids.length - 1][cores - 1] = times3[uids.length - 1][cores - 1] - 1;
        when(mBufferedReader.readLine()).thenReturn(info, formatTime(uids, times4));
        mReader.readDeltaInternal(mBufferedReader, mCallback);
        for (int i = 0; i < uids.length - 1; ++i) {
            verify(mCallback).onUidCpuActiveTime(uids[i], getTotal(subtract(times4[i], times3[i])));
        }
        verifyNoMoreInteractions(mCallback);

        // Verify that the internal state was not modified when the proc file had decreased values.
        Mockito.reset(mCallback, mBufferedReader);
        for (int i = 0; i < cores; i++) {
            times4[uids.length - 1][i] = times3[uids.length - 1][i] + uids[uids.length - 1] * 1000;
        }
        when(mBufferedReader.readLine()).thenReturn(info, formatTime(uids, times4));
        mReader.readDeltaInternal(mBufferedReader, mCallback);
        verify(mCallback).onUidCpuActiveTime(uids[uids.length - 1], getTotal(subtract(times4[uids.length - 1], times3[uids.length - 1])));
        verifyNoMoreInteractions(mCallback);
    }

    private long[] subtract(long[] a1, long[] a2) {
        long[] val = new long[a1.length];
        for (int i = 0; i < val.length; ++i) {
            val[i] = a1[i] - a2[i];
        }
        return val;
    }

    private String[] formatTime(int[] uids, long[][] times) {
        String[] lines = new String[uids.length + 1];
        for (int i=0;i<uids.length;i++){
            StringBuilder sb = new StringBuilder();
            sb.append(uids[i]).append(':');
            for(int j=0;j<times[i].length;j++){
                sb.append(' ').append(times[i][j]);
            }
            lines[i] = sb.toString();
        }
        lines[uids.length] = null;
        return lines;
    }

    private long[][] increaseTime(long[][] original) {
        long[][] newTime = new long[original.length][original[0].length];
        Random rand = new Random();
        for(int i = 0;i<original.length;i++){
            for(int j=0;j<original[0].length;j++){
                newTime[i][j] = original[i][j] + rand.nextInt(1000_000) + 10000;
            }
        }
        return newTime;
    }

    private long getTotal(long[] times) {
        long sum = 0;
        for(int i=0;i<times.length;i++){
            sum+=times[i] * 10 / (i+1);
        }
        return sum;
    }
}
