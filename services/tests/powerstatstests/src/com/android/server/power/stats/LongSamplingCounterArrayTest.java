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

package com.android.server.power.stats;

import static android.os.BatteryStats.STATS_SINCE_CHARGED;

import static com.android.server.power.stats.BatteryStatsImpl.LongSamplingCounterArray;
import static com.android.server.power.stats.BatteryStatsImpl.TimeBase;

import static org.junit.Assert.assertArrayEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.os.Parcel;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/**
 * Test class for {@link BatteryStatsImpl.LongSamplingCounterArray}.
 *
 * atest FrameworksServiceTests:com.android.server.power.stats.LongSamplingCounterArrayTest
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class LongSamplingCounterArrayTest {

    private static final long[] COUNTS = {1111, 2222, 3333, 4444};
    private static final long[] ZEROES = {0, 0, 0, 0};

    @Mock private TimeBase mTimeBase;
    private LongSamplingCounterArray mCounterArray;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mCounterArray = new LongSamplingCounterArray(mTimeBase);
        Mockito.reset(mTimeBase);
    }

    @Test
    public void testReadWriteParcel() {
        final Parcel parcel = Parcel.obtain();
        updateCounts(COUNTS);
        LongSamplingCounterArray.writeToParcel(parcel, mCounterArray);
        parcel.setDataPosition(0);

        // Now clear counterArray and verify values are read from parcel correctly.
        updateCounts(null);
        mCounterArray = LongSamplingCounterArray.readFromParcel(parcel, mTimeBase);
        assertArrayEquals(COUNTS, mCounterArray.mCounts);
        parcel.recycle();
    }

    @Test
    public void testReadWriteSummaryParcel() {
        final Parcel parcel = Parcel.obtain();
        updateCounts(COUNTS);
        LongSamplingCounterArray.writeSummaryToParcelLocked(parcel, mCounterArray);
        parcel.setDataPosition(0);

        // Now clear counterArray and verify values are read from parcel correctly.
        updateCounts(null);
        mCounterArray = LongSamplingCounterArray.readSummaryFromParcelLocked(parcel, mTimeBase);
        assertArrayEquals(COUNTS, mCounterArray.mCounts);
        parcel.recycle();
    }

    @Test
    public void testOnTimeStarted() {
        updateCounts(COUNTS);
        mCounterArray.onTimeStarted(0, 0, 0);
        assertArrayEquals(COUNTS, mCounterArray.mCounts);
    }

    @Test
    public void testOnTimeStopped() {
        updateCounts(COUNTS);
        mCounterArray.onTimeStopped(0, 0, 0);
        assertArrayEquals(COUNTS, mCounterArray.mCounts);
    }

    @Test
    public void testGetCountsLocked() {
        updateCounts(COUNTS);

        when(mTimeBase.isRunning()).thenReturn(false);
        assertArrayEquals(COUNTS, mCounterArray.getCountsLocked(STATS_SINCE_CHARGED));

        when(mTimeBase.isRunning()).thenReturn(true);
        assertArrayEquals(COUNTS, mCounterArray.getCountsLocked(STATS_SINCE_CHARGED));
    }

    private long[] subtract(long[] val, long[] toSubtract) {
        final long[] result = val.clone();
        if (toSubtract != null) {
            for (int i = val.length - 1; i >= 0; --i) {
                result[i] -= toSubtract[i];
            }
        }
        return result;
    }

    @Test
    public void testAddCountLocked() {
        updateCounts(null);
        final long[] deltas = {123, 234, 345, 456};
        when(mTimeBase.isRunning()).thenReturn(true);
        mCounterArray.addCountLocked(deltas);
        assertArrayEquals(deltas, mCounterArray.mCounts);

        updateCounts(null);
        mCounterArray.addCountLocked(deltas, false);
        assertArrayEquals(null, mCounterArray.mCounts);
        mCounterArray.addCountLocked(deltas, true);
        assertArrayEquals(deltas, mCounterArray.mCounts);

        updateCounts(COUNTS);
        final long[] newCounts = new long[deltas.length];
        for (int i = 0; i < deltas.length; ++i) {
            newCounts[i] = COUNTS[i] + deltas[i];
        }
        mCounterArray.addCountLocked(deltas);
        assertArrayEquals(newCounts, mCounterArray.mCounts);

        updateCounts(COUNTS);
        mCounterArray.addCountLocked(deltas, false);
        assertArrayEquals(COUNTS, mCounterArray.mCounts);
        mCounterArray.addCountLocked(deltas, true);
        assertArrayEquals(newCounts, mCounterArray.mCounts);
    }

    @Test
    public void testReset() {
        updateCounts(COUNTS);
        // Test with detachIfReset=false
        mCounterArray.reset(false /* detachIfReset */);
        assertArrayEquals(ZEROES, mCounterArray.mCounts);
        verifyNoMoreInteractions(mTimeBase);

        updateCounts(COUNTS);
        // Test with detachIfReset=true
        mCounterArray.reset(true /* detachIfReset */);
        assertArrayEquals(ZEROES, mCounterArray.mCounts);
        verify(mTimeBase).remove(mCounterArray);
        verifyNoMoreInteractions(mTimeBase);
    }

    @Test
    public void testDetach() {
        mCounterArray.detach();
        verify(mTimeBase).remove(mCounterArray);
        verifyNoMoreInteractions(mTimeBase);
    }

    private void updateCounts(long[] counts) {
        mCounterArray.mCounts = counts == null ? null : counts.clone();
    }
}
