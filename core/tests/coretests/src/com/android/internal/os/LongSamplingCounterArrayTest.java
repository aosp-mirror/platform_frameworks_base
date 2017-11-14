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

import static android.os.BatteryStats.STATS_SINCE_CHARGED;

import static com.android.internal.os.BatteryStatsImpl.LongSamplingCounterArray;
import static com.android.internal.os.BatteryStatsImpl.TimeBase;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.os.Parcel;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;

/**
 * Test class for {@link BatteryStatsImpl.LongSamplingCounterArray}.
 *
 * To run the tests, use
 *
 * runtest -c com.android.internal.os.LongSamplingCounterArrayTest frameworks-core
 *
 * or the following steps:
 *
 * Build: m FrameworksCoreTests
 * Install: adb install -r \
 *     ${ANDROID_PRODUCT_OUT}/data/app/FrameworksCoreTests/FrameworksCoreTests.apk
 * Run: adb shell am instrument -e class com.android.internal.os.LongSamplingCounterArrayTest -w \
 *     com.android.frameworks.coretests/android.support.test.runner.AndroidJUnitRunner
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class LongSamplingCounterArrayTest {

    private static final long[] COUNTS = {1111, 2222, 3333, 4444};
    private static final long[] LOADED_COUNTS = {5555, 6666, 7777, 8888};
    private static final long[] PLUGGED_COUNTS = {9999, 11111, 22222, 33333};
    private static final long[] UNPLUGGED_COUNTS = {44444, 55555, 66666, 77777};
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
        initializeCounterArrayWithDefaultValues();
        LongSamplingCounterArray.writeToParcel(parcel, mCounterArray);
        parcel.setDataPosition(0);

        // Now clear counterArray and verify values are read from parcel correctly.
        updateCounts(null, null, null, null);
        mCounterArray = LongSamplingCounterArray.readFromParcel(parcel, mTimeBase);
        assertArrayEquals(COUNTS, mCounterArray.mCounts, "Unexpected counts");
        assertArrayEquals(LOADED_COUNTS, mCounterArray.mLoadedCounts, "Unexpected loadedCounts");
        assertArrayEquals(COUNTS, mCounterArray.mPluggedCounts, "Unexpected pluggedCounts");
        assertArrayEquals(UNPLUGGED_COUNTS, mCounterArray.mUnpluggedCounts,
                "Unexpected unpluggedCounts");
        parcel.recycle();
    }

    @Test
    public void testReadWriteSummaryParcel() {
        final Parcel parcel = Parcel.obtain();
        initializeCounterArrayWithDefaultValues();
        LongSamplingCounterArray.writeSummaryToParcelLocked(parcel, mCounterArray);
        parcel.setDataPosition(0);

        // Now clear counterArray and verify values are read from parcel correctly.
        updateCounts(null, null, null, null);
        mCounterArray = LongSamplingCounterArray.readSummaryFromParcelLocked(parcel, mTimeBase);
        assertArrayEquals(COUNTS, mCounterArray.mCounts, "Unexpected counts");
        assertArrayEquals(COUNTS, mCounterArray.mLoadedCounts, "Unexpected loadedCounts");
        assertArrayEquals(COUNTS, mCounterArray.mPluggedCounts, "Unexpected pluggedCounts");
        assertArrayEquals(COUNTS, mCounterArray.mUnpluggedCounts, "Unexpected unpluggedCounts");
        parcel.recycle();
    }

    @Test
    public void testOnTimeStarted() {
        initializeCounterArrayWithDefaultValues();
        mCounterArray.onTimeStarted(0, 0, 0);
        assertArrayEquals(COUNTS, mCounterArray.mCounts, "Unexpected counts");
        assertArrayEquals(LOADED_COUNTS, mCounterArray.mLoadedCounts, "Unexpected loadedCounts");
        assertArrayEquals(PLUGGED_COUNTS, mCounterArray.mPluggedCounts, "Unexpected pluggedCounts");
        assertArrayEquals(PLUGGED_COUNTS, mCounterArray.mUnpluggedCounts,
                "Unexpected unpluggedCounts");
    }

    @Test
    public void testOnTimeStopped() {
        initializeCounterArrayWithDefaultValues();
        mCounterArray.onTimeStopped(0, 0, 0);
        assertArrayEquals(COUNTS, mCounterArray.mCounts, "Unexpected counts");
        assertArrayEquals(LOADED_COUNTS, mCounterArray.mLoadedCounts, "Unexpected loadedCounts");
        assertArrayEquals(COUNTS, mCounterArray.mPluggedCounts, "Unexpected pluggedCounts");
        assertArrayEquals(UNPLUGGED_COUNTS, mCounterArray.mUnpluggedCounts,
                "Unexpected unpluggedCounts");
    }

    @Test
    public void testGetCountsLocked() {
        initializeCounterArrayWithDefaultValues();

        when(mTimeBase.isRunning()).thenReturn(false);
        long[] actualVal = mCounterArray.getCountsLocked(STATS_SINCE_CHARGED);
        long[] expectedVal = PLUGGED_COUNTS;
        assertArrayEquals(expectedVal, actualVal, "Unexpected values");

        when(mTimeBase.isRunning()).thenReturn(true);
        actualVal = mCounterArray.getCountsLocked(STATS_SINCE_CHARGED);
        expectedVal = COUNTS;
        assertArrayEquals(expectedVal, actualVal, "Unexpected values");
    }

    @Test
    public void testAddCountLocked() {
        final long[] deltas = {123, 234, 345, 456};
        when(mTimeBase.isRunning()).thenReturn(true);
        mCounterArray.addCountLocked(deltas);
        assertArrayEquals(deltas, mCounterArray.mCounts, "Unexpected counts");
        assertArrayEquals(null, mCounterArray.mLoadedCounts, "Unexpected loadedCounts");
        assertArrayEquals(null, mCounterArray.mPluggedCounts, "Unexpected pluggedCounts");
        assertArrayEquals(null, mCounterArray.mUnpluggedCounts, "Unexpected unpluggedCounts");

        initializeCounterArrayWithDefaultValues();
        final long[] newCounts = new long[deltas.length];
        for (int i = 0; i < deltas.length; ++i) {
            newCounts[i] = COUNTS[i] + deltas[i];
        }
        mCounterArray.addCountLocked(deltas);
        assertArrayEquals(newCounts, mCounterArray.mCounts, "Unexpected counts");
        assertArrayEquals(LOADED_COUNTS, mCounterArray.mLoadedCounts, "Unexpected loadedCounts");
        assertArrayEquals(PLUGGED_COUNTS, mCounterArray.mPluggedCounts, "Unexpected pluggedCounts");
        assertArrayEquals(UNPLUGGED_COUNTS, mCounterArray.mUnpluggedCounts,
                "Unexpected unpluggedCounts");
    }

    @Test
    public void testReset() {
        initializeCounterArrayWithDefaultValues();
        // Test with detachIfReset=false
        mCounterArray.reset(false /* detachIfReset */);
        assertArrayEquals(ZEROES, mCounterArray.mCounts, "Unexpected counts");
        assertArrayEquals(ZEROES, mCounterArray.mLoadedCounts, "Unexpected loadedCounts");
        assertArrayEquals(ZEROES, mCounterArray.mPluggedCounts, "Unexpected pluggedCounts");
        assertArrayEquals(ZEROES, mCounterArray.mUnpluggedCounts, "Unexpected unpluggedCounts");
        verifyZeroInteractions(mTimeBase);

        initializeCounterArrayWithDefaultValues();
        // Test with detachIfReset=true
        mCounterArray.reset(true /* detachIfReset */);
        assertArrayEquals(ZEROES, mCounterArray.mCounts, "Unexpected counts");
        assertArrayEquals(ZEROES, mCounterArray.mLoadedCounts, "Unexpected loadedCounts");
        assertArrayEquals(ZEROES, mCounterArray.mPluggedCounts, "Unexpected pluggedCounts");
        assertArrayEquals(ZEROES, mCounterArray.mUnpluggedCounts, "Unexpected unpluggedCounts");
        verify(mTimeBase).remove(mCounterArray);
        verifyNoMoreInteractions(mTimeBase);
    }

    @Test
    public void testDetach() {
        mCounterArray.detach();
        verify(mTimeBase).remove(mCounterArray);
        verifyNoMoreInteractions(mTimeBase);
    }

    private void initializeCounterArrayWithDefaultValues() {
        updateCounts(COUNTS, LOADED_COUNTS, PLUGGED_COUNTS, UNPLUGGED_COUNTS);
    }

    private void assertArrayEquals(long[] expected, long[] actual, String msg) {
        assertTrue(msg + ", expected: " + Arrays.toString(expected)
                + ", actual: " + Arrays.toString(actual), Arrays.equals(expected, actual));
    }

    private void updateCounts(long[] counts, long[] loadedCounts,
            long[] pluggedCounts, long[] unpluggedCounts) {
        mCounterArray.mCounts = counts;
        mCounterArray.mLoadedCounts = loadedCounts;
        mCounterArray.mPluggedCounts = pluggedCounts;
        mCounterArray.mUnpluggedCounts = unpluggedCounts;
    }
}
