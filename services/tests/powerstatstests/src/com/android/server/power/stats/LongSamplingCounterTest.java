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

package com.android.server.power.stats;

import static android.os.BatteryStats.STATS_SINCE_CHARGED;

import static com.android.server.power.stats.BatteryStatsImpl.LongSamplingCounter;
import static com.android.server.power.stats.BatteryStatsImpl.TimeBase;

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
 * atest FrameworksServiceTests:com.android.server.power.stats.LongSamplingCounterTest
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class LongSamplingCounterTest {

    private static final long COUNT = 1111;

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
        mCounter.addCountLocked(COUNT, true);
        assertEquals(COUNT, getCount());
        mCounter.writeToParcel(parcel);
        parcel.setDataPosition(0);

        // Now change count but verify values are read from parcel correctly.
        mCounter.addCountLocked(7 * COUNT, true);
        assertEquals(8 * COUNT, getCount());

        mCounter = new LongSamplingCounter(mTimeBase, parcel);
        assertEquals(COUNT, getCount());
        parcel.recycle();
    }

    @Test
    public void testReadWriteSummaryParcel() {
        final Parcel parcel = Parcel.obtain();
        mCounter.addCountLocked(COUNT, true);
        assertEquals(COUNT, getCount());
        mCounter.writeSummaryFromParcelLocked(parcel);
        parcel.setDataPosition(0);

        // Now change count but verify values are read from parcel correctly.
        mCounter.addCountLocked(7 * COUNT, true);
        assertEquals(8 * COUNT, getCount());

        mCounter.readSummaryFromParcelLocked(parcel);
        assertEquals(COUNT, getCount());
        parcel.recycle();
    }

    @Test
    public void testOnTimeStarted() {
        mCounter.addCountLocked(COUNT, true);
        assertEquals(COUNT, getCount());
        mCounter.onTimeStarted(0, 0, 0);
        assertEquals(COUNT, getCount());
    }

    @Test
    public void testOnTimeStopped() {
        mCounter.addCountLocked(COUNT, true);
        assertEquals(COUNT, getCount());
        mCounter.onTimeStopped(0, 0, 0);
        assertEquals(COUNT, getCount());
    }

    @Test
    public void testAddCountLocked() {
        assertEquals(0, getCount());
        when(mTimeBase.isRunning()).thenReturn(true);
        mCounter.addCountLocked(111);
        assertEquals(111, mCounter.getCountLocked(STATS_SINCE_CHARGED));
        mCounter.addCountLocked(222);
        assertEquals(333, mCounter.getCountLocked(STATS_SINCE_CHARGED));

        when(mTimeBase.isRunning()).thenReturn(false);
        mCounter.addCountLocked(456);
        assertEquals(333, mCounter.getCountLocked(STATS_SINCE_CHARGED));

        mCounter.addCountLocked(444, true);
        assertEquals(777, mCounter.getCountLocked(STATS_SINCE_CHARGED));
        mCounter.addCountLocked(567, false);
        assertEquals(777, mCounter.getCountLocked(STATS_SINCE_CHARGED));
    }


    @Test
    public void testReset() {
        mCounter.addCountLocked(COUNT, true);
        assertEquals(COUNT, getCount());
        // Test with detachIfReset=false
        mCounter.reset(false /* detachIfReset */);
        assertEquals(0, getCount());
        verifyZeroInteractions(mTimeBase);

        mCounter.addCountLocked(COUNT, true);
        assertEquals(COUNT, getCount());
        // Test with detachIfReset=true
        mCounter.reset(true /* detachIfReset */);
        assertEquals(0, getCount());
        verify(mTimeBase).remove(mCounter);
        verifyNoMoreInteractions(mTimeBase);
    }

    @Test
    public void testDetach() {
        mCounter.detach();
        verify(mTimeBase).remove(mCounter);
        verifyNoMoreInteractions(mTimeBase);
    }

    private long getCount() {
        return mCounter.getCountLocked(STATS_SINCE_CHARGED);
    }
}
