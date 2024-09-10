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

package android.hardware.display;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.os.Parcel;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.LocalDate;
import java.util.Arrays;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class AmbientBrightnessDayStatsTest {

    private static final LocalDate LOCAL_DATE = LocalDate.now();
    private static final float[] BUCKET_BOUNDARIES = {0, 1, 10, 100};
    private static final float[] STATS = {1.3f, 2.6f, 5.8f, 10};

    @Test
    public void testParamsMustNotBeNull() {
        assertThrows(NullPointerException.class,
                () -> new AmbientBrightnessDayStats(null, BUCKET_BOUNDARIES));

        assertThrows(NullPointerException.class,
                () -> new AmbientBrightnessDayStats(LOCAL_DATE, null));

        assertThrows(NullPointerException.class,
                () -> new AmbientBrightnessDayStats(null, BUCKET_BOUNDARIES, STATS));

        assertThrows(NullPointerException.class,
                () -> new AmbientBrightnessDayStats(LOCAL_DATE, null, STATS));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBucketBoundariesMustNotBeEmpty() {
        new AmbientBrightnessDayStats(LocalDate.now(), new float[]{});
    }

    @Test(expected = IllegalArgumentException.class)
    public void testStatsAndBoundariesMustHaveSameLength() {
        float[] stats = Arrays.copyOf(STATS, STATS.length + 1);
        stats[stats.length - 1] = 0;
        new AmbientBrightnessDayStats(LOCAL_DATE, BUCKET_BOUNDARIES, stats);
    }

    @Test
    public void testAmbientBrightnessDayStatsAdd() {
        AmbientBrightnessDayStats dayStats = new AmbientBrightnessDayStats(LOCAL_DATE,
                BUCKET_BOUNDARIES);
        dayStats.log(0, 1);
        dayStats.log(0.5f, 1.5f);
        dayStats.log(50, 12.5f);
        dayStats.log(2000, 1.24f);
        dayStats.log(-10, 0.5f);
        assertEquals(4, dayStats.getStats().length);
        assertEquals(2.5f, dayStats.getStats()[0], 0);
        assertEquals(0, dayStats.getStats()[1], 0);
        assertEquals(12.5f, dayStats.getStats()[2], 0);
        assertEquals(1.24f, dayStats.getStats()[3], 0);
    }

    @Test
    public void testGetters() {
        AmbientBrightnessDayStats dayStats = new AmbientBrightnessDayStats(LOCAL_DATE,
                BUCKET_BOUNDARIES, STATS);
        assertEquals(LOCAL_DATE, dayStats.getLocalDate());
        assertArrayEquals(BUCKET_BOUNDARIES, dayStats.getBucketBoundaries(), 0);
        assertArrayEquals(STATS, dayStats.getStats(), 0);
    }

    @Test
    public void testParcelUnparcelAmbientBrightnessDayStats() {
        LocalDate today = LocalDate.now();
        AmbientBrightnessDayStats stats = new AmbientBrightnessDayStats(today,
                new float[]{0, 1, 10, 100}, new float[]{1.3f, 2.6f, 5.8f, 10});
        // Parcel the data
        Parcel parcel = Parcel.obtain();
        stats.writeToParcel(parcel, 0);
        byte[] parceled = parcel.marshall();
        parcel.recycle();
        // Unparcel and check that it has not changed
        parcel = Parcel.obtain();
        parcel.unmarshall(parceled, 0, parceled.length);
        parcel.setDataPosition(0);
        AmbientBrightnessDayStats statsAgain = AmbientBrightnessDayStats.CREATOR.createFromParcel(
                parcel);
        assertEquals(stats, statsAgain);
    }

    @Test
    public void testAmbientBrightnessDayStatsEquals() {
        AmbientBrightnessDayStats emptyDayStats = new AmbientBrightnessDayStats(LOCAL_DATE,
                BUCKET_BOUNDARIES);
        AmbientBrightnessDayStats identicalEmptyDayStats = new AmbientBrightnessDayStats(LOCAL_DATE,
                BUCKET_BOUNDARIES, new float[BUCKET_BOUNDARIES.length]);
        assertEquals(emptyDayStats, identicalEmptyDayStats);
        assertEquals(emptyDayStats.hashCode(), identicalEmptyDayStats.hashCode());

        AmbientBrightnessDayStats dayStats = new AmbientBrightnessDayStats(LOCAL_DATE,
                BUCKET_BOUNDARIES, STATS);
        AmbientBrightnessDayStats identicalDayStats = new AmbientBrightnessDayStats(LOCAL_DATE,
                BUCKET_BOUNDARIES, STATS);
        assertEquals(dayStats, identicalDayStats);
        assertEquals(dayStats.hashCode(), identicalDayStats.hashCode());

        assertNotEquals(emptyDayStats, dayStats);
        assertNotEquals(emptyDayStats.hashCode(), dayStats.hashCode());

        AmbientBrightnessDayStats differentDateDayStats = new AmbientBrightnessDayStats(
                LOCAL_DATE.plusDays(1), BUCKET_BOUNDARIES, STATS);
        assertNotEquals(dayStats, differentDateDayStats);
        assertNotEquals(dayStats.hashCode(), differentDateDayStats.hashCode());

        float[] differentStats = Arrays.copyOf(STATS, STATS.length);
        differentStats[differentStats.length - 1] += 5f;
        AmbientBrightnessDayStats differentStatsDayStats = new AmbientBrightnessDayStats(LOCAL_DATE,
                BUCKET_BOUNDARIES, differentStats);
        assertNotEquals(dayStats, differentDateDayStats);
        assertNotEquals(dayStats.hashCode(), differentStatsDayStats.hashCode());

        float[] differentBucketBoundaries = Arrays.copyOf(BUCKET_BOUNDARIES,
                BUCKET_BOUNDARIES.length);
        differentBucketBoundaries[differentBucketBoundaries.length - 1] += 100f;
        AmbientBrightnessDayStats differentBoundariesDayStats = new AmbientBrightnessDayStats(
                LOCAL_DATE, differentBucketBoundaries, STATS);
        assertNotEquals(dayStats, differentBoundariesDayStats);
        assertNotEquals(dayStats.hashCode(), differentBoundariesDayStats.hashCode());
    }

    private interface ExceptionRunnable {
        void run() throws Exception;
    }

    private static void assertThrows(Class<? extends Throwable> exceptionClass,
            ExceptionRunnable r) {
        try {
            r.run();
        } catch (Throwable e) {
            assertTrue("Expected exception type " + exceptionClass.getName() + " but got "
                    + e.getClass().getName(), exceptionClass.isAssignableFrom(e.getClass()));
            return;
        }
        fail("Expected exception type " + exceptionClass.getName()
                + ", but no exception was thrown");
    }

}
