/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.os.BatteryStats.STATS_SINCE_CHARGED;

import static com.android.internal.os.BatteryStatsImpl.LongSamplingCounter;
import static com.android.internal.os.BatteryStatsImpl.TimeBase;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
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
 * Test class for {@link LongSamplingCounter}.
 *
 * To run the tests, use
 *
 * bit FrameworksCoreTests:com.android.internal.os.LongSamplingCounterTest
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class LongSamplingCounterTest {

    private static final long COUNT = 1111;
    private static final long CURRENT_COUNT = 5555;

    @Mock
    private TimeBase mTimeBase;
    private LongSamplingCounter mCounter;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mCounter = new LongSamplingCounter(mTimeBase);
        Mockito.reset(mTimeBase);
    }

    @Test
    public void testReadWriteParcel() {
        final Parcel parcel = Parcel.obtain();
        updateCounts(COUNT, CURRENT_COUNT);
        mCounter.writeToParcel(parcel);
        parcel.setDataPosition(0);

        // Now clear counterArray and verify values are read from parcel correctly.
        updateCounts(0, 0);

        mCounter = new LongSamplingCounter(mTimeBase, parcel);
        assertEquals(COUNT, mCounter.mCount);
        assertEquals(CURRENT_COUNT, mCounter.mCurrentCount);
        parcel.recycle();
    }

    @Test
    public void testReadWriteSummaryParcel() {
        final Parcel parcel = Parcel.obtain();
        updateCounts(COUNT, CURRENT_COUNT);
        mCounter.writeSummaryFromParcelLocked(parcel);
        parcel.setDataPosition(0);

        // Now clear counterArray and verify values are read from parcel correctly.
        updateCounts(0, 0);

        mCounter.readSummaryFromParcelLocked(parcel);
        assertEquals(COUNT, mCounter.mCount);
        parcel.recycle();
    }

    @Test
    public void testOnTimeStarted() {
        updateCounts(COUNT, CURRENT_COUNT);
        mCounter.onTimeStarted(0, 0, 0);
        assertEquals(COUNT, mCounter.mCount);
        assertEquals(COUNT, mCounter.mUnpluggedCount);
    }

    @Test
    public void testOnTimeStopped() {
        updateCounts(COUNT, CURRENT_COUNT);
        mCounter.onTimeStopped(0, 0, 0);
        assertEquals(COUNT, mCounter.mCount);
    }

    @Test
    public void testAddCountLocked() {
        updateCounts(0, 0);
        assertEquals(0, mCounter.getCountLocked(0));
        when(mTimeBase.isRunning()).thenReturn(true);
        mCounter.addCountLocked(111);
        assertEquals(111, mCounter.getCountLocked(STATS_SINCE_CHARGED));
        assertEquals(111, mCounter.mCurrentCount);
        mCounter.addCountLocked(222);
        assertEquals(333, mCounter.getCountLocked(STATS_SINCE_CHARGED));
        assertEquals(333, mCounter.mCurrentCount);

        when(mTimeBase.isRunning()).thenReturn(false);
        mCounter.addCountLocked(456);
        assertEquals(333, mCounter.getCountLocked(STATS_SINCE_CHARGED));
        assertEquals(789, mCounter.mCurrentCount);

        mCounter.addCountLocked(444, true);
        assertEquals(777, mCounter.getCountLocked(STATS_SINCE_CHARGED));
        assertEquals(1233, mCounter.mCurrentCount);
        mCounter.addCountLocked(567, false);
        assertEquals(777, mCounter.getCountLocked(STATS_SINCE_CHARGED));
        assertEquals(1800, mCounter.mCurrentCount);
    }

    @Test
    public void testUpdate() {
        updateCounts(0, 0);
        assertEquals(0, mCounter.getCountLocked(0));
        when(mTimeBase.isRunning()).thenReturn(true);
        mCounter.update(111);
        assertEquals(111, mCounter.getCountLocked(STATS_SINCE_CHARGED));
        assertEquals(111, mCounter.mCurrentCount);
        mCounter.update(333);
        assertEquals(333, mCounter.getCountLocked(STATS_SINCE_CHARGED));
        assertEquals(333, mCounter.mCurrentCount);

        when(mTimeBase.isRunning()).thenReturn(false);
        mCounter.update(789);
        assertEquals(333, mCounter.getCountLocked(STATS_SINCE_CHARGED));
        assertEquals(789, mCounter.mCurrentCount);
        mCounter.update(100);
        assertEquals(333, mCounter.getCountLocked(STATS_SINCE_CHARGED));
        assertEquals(100, mCounter.mCurrentCount);

        mCounter.update(544, true);
        assertEquals(777, mCounter.getCountLocked(STATS_SINCE_CHARGED));
        assertEquals(544, mCounter.mCurrentCount);
        mCounter.update(1544, false);
        assertEquals(777, mCounter.getCountLocked(STATS_SINCE_CHARGED));
        assertEquals(1544, mCounter.mCurrentCount);
    }

    @Test
    public void testReset() {
        updateCounts(COUNT, CURRENT_COUNT);
        // Test with detachIfReset=false
        mCounter.reset(false /* detachIfReset */);
        assertEquals(0, mCounter.mCount);
        assertEquals(CURRENT_COUNT, mCounter.mCurrentCount);
        verifyZeroInteractions(mTimeBase);

        updateCounts(COUNT, CURRENT_COUNT);
        // Test with detachIfReset=true
        mCounter.reset(true /* detachIfReset */);
        assertEquals(0, mCounter.mCount);
        assertEquals(CURRENT_COUNT, mCounter.mCurrentCount);
        verify(mTimeBase).remove(mCounter);
        verifyNoMoreInteractions(mTimeBase);
    }

    @Test
    public void testDetach() {
        mCounter.detach();
        verify(mTimeBase).remove(mCounter);
        verifyNoMoreInteractions(mTimeBase);
    }

    private void updateCounts(long total, long current) {
        mCounter.mCount = total;
        mCounter.mCurrentCount = current;
    }
}
