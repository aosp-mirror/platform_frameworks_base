/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.power;

import static android.os.BatteryStats.ENERGY_DATA_UNAVAILABLE;

import static com.android.internal.power.MeasuredEnergyStats.ENERGY_BUCKET_SCREEN_DOZE;
import static com.android.internal.power.MeasuredEnergyStats.ENERGY_BUCKET_SCREEN_ON;
import static com.android.internal.power.MeasuredEnergyStats.ENERGY_BUCKET_SCREEN_OTHER;
import static com.android.internal.power.MeasuredEnergyStats.NUMBER_ENERGY_BUCKETS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Parcel;
import android.view.Display;

import androidx.test.filters.SmallTest;

import org.junit.Test;

/**
 * Test class for {@link MeasuredEnergyStats}.
 *
 * To run the tests, use
 * atest FrameworksCoreTests:com.android.internal.power.MeasuredEnergyStatsTest
 */
@SmallTest
public class MeasuredEnergyStatsTest {

    @Test
    public void testConstruction() {
        final boolean[] supportedEnergyBuckets = new boolean[NUMBER_ENERGY_BUCKETS];
        supportedEnergyBuckets[ENERGY_BUCKET_SCREEN_ON] = true;
        supportedEnergyBuckets[ENERGY_BUCKET_SCREEN_DOZE] = false;
        supportedEnergyBuckets[ENERGY_BUCKET_SCREEN_OTHER] = true;

        final MeasuredEnergyStats stats = new MeasuredEnergyStats(supportedEnergyBuckets);

        for (int i = 0; i < NUMBER_ENERGY_BUCKETS; i++) {
            if (supportedEnergyBuckets[i]) {
                assertTrue(stats.isEnergyBucketSupported(i));
                assertEquals(0L, stats.getAccumulatedBucketEnergy(i));
            } else {
                assertFalse(stats.isEnergyBucketSupported(i));
                assertEquals(ENERGY_DATA_UNAVAILABLE, stats.getAccumulatedBucketEnergy(i));
            }
        }
    }

    @Test
    public void testCreateFromTemplate() {
        final boolean[] supportedEnergyBuckets = new boolean[NUMBER_ENERGY_BUCKETS];
        supportedEnergyBuckets[ENERGY_BUCKET_SCREEN_ON] = true;
        supportedEnergyBuckets[ENERGY_BUCKET_SCREEN_DOZE] = false;
        supportedEnergyBuckets[ENERGY_BUCKET_SCREEN_OTHER] = true;

        final MeasuredEnergyStats stats = new MeasuredEnergyStats(supportedEnergyBuckets);
        stats.updateBucket(ENERGY_BUCKET_SCREEN_ON, 10, true);
        stats.updateBucket(ENERGY_BUCKET_SCREEN_ON, 5, true);
        stats.updateBucket(ENERGY_BUCKET_SCREEN_OTHER, 40, true);

        final MeasuredEnergyStats newStats = MeasuredEnergyStats.createFromTemplate(stats);

        for (int i = 0; i < NUMBER_ENERGY_BUCKETS; i++) {
            if (supportedEnergyBuckets[i]) {
                assertTrue(newStats.isEnergyBucketSupported(i));
                assertEquals(0, newStats.getAccumulatedBucketEnergy(i));
            } else {
                assertFalse(newStats.isEnergyBucketSupported(i));
                assertEquals(ENERGY_DATA_UNAVAILABLE, newStats.getAccumulatedBucketEnergy(i));
            }
        }
    }

    @Test
    public void testReadWriteParcel() {
        final boolean[] supportedEnergyBuckets = new boolean[NUMBER_ENERGY_BUCKETS];
        supportedEnergyBuckets[ENERGY_BUCKET_SCREEN_ON] = true;
        supportedEnergyBuckets[ENERGY_BUCKET_SCREEN_DOZE] = false;
        supportedEnergyBuckets[ENERGY_BUCKET_SCREEN_OTHER] = true;

        final MeasuredEnergyStats stats = new MeasuredEnergyStats(supportedEnergyBuckets);
        stats.updateBucket(ENERGY_BUCKET_SCREEN_ON, 10, true);
        stats.updateBucket(ENERGY_BUCKET_SCREEN_ON, 5, true);
        stats.updateBucket(ENERGY_BUCKET_SCREEN_OTHER, 40, true);

        final Parcel parcel = Parcel.obtain();
        stats.writeToParcel(parcel);

        parcel.setDataPosition(0);
        MeasuredEnergyStats newStats = new MeasuredEnergyStats(parcel);

        for (int i = 0; i < NUMBER_ENERGY_BUCKETS; i++) {
            assertEquals(stats.getAccumulatedBucketEnergy(i),
                    newStats.getAccumulatedBucketEnergy(i));
        }
        parcel.recycle();
    }

    @Test
    public void testReadWriteSummaryParcel() {
        final boolean[] supportedEnergyBuckets = new boolean[NUMBER_ENERGY_BUCKETS];
        supportedEnergyBuckets[ENERGY_BUCKET_SCREEN_ON] = true;
        supportedEnergyBuckets[ENERGY_BUCKET_SCREEN_DOZE] = false;
        supportedEnergyBuckets[ENERGY_BUCKET_SCREEN_OTHER] = true;

        final MeasuredEnergyStats stats = new MeasuredEnergyStats(supportedEnergyBuckets);
        stats.updateBucket(ENERGY_BUCKET_SCREEN_ON, 10, true);
        stats.updateBucket(ENERGY_BUCKET_SCREEN_ON, 5, true);
        stats.updateBucket(ENERGY_BUCKET_SCREEN_OTHER, 40, true);

        final Parcel parcel = Parcel.obtain();
        MeasuredEnergyStats.writeSummaryToParcel(stats, parcel);


        final boolean[] newSupportedEnergyBuckets = new boolean[NUMBER_ENERGY_BUCKETS];
        newSupportedEnergyBuckets[ENERGY_BUCKET_SCREEN_ON] = true;
        newSupportedEnergyBuckets[ENERGY_BUCKET_SCREEN_DOZE] = true; // switched from false to true
        newSupportedEnergyBuckets[ENERGY_BUCKET_SCREEN_OTHER] = false; // switched true to false
        MeasuredEnergyStats newStats = new MeasuredEnergyStats(newSupportedEnergyBuckets);
        parcel.setDataPosition(0);
        MeasuredEnergyStats.readSummaryFromParcel(newStats, parcel);

        for (int i = 0; i < NUMBER_ENERGY_BUCKETS; i++) {
            if (!newSupportedEnergyBuckets[i]) {
                assertFalse(newStats.isEnergyBucketSupported(i));
                assertEquals(ENERGY_DATA_UNAVAILABLE, newStats.getAccumulatedBucketEnergy(i));
            } else if (!supportedEnergyBuckets[i]) {
                assertTrue(newStats.isEnergyBucketSupported(i));
                assertEquals(0L, newStats.getAccumulatedBucketEnergy(i));
            } else {
                assertTrue(newStats.isEnergyBucketSupported(i));
                assertEquals(stats.getAccumulatedBucketEnergy(i),
                        newStats.getAccumulatedBucketEnergy(i));
            }
        }
        parcel.recycle();
    }

    @Test
    public void testCreateAndReadSummaryFromParcel() {
        final boolean[] supportedEnergyBuckets = new boolean[NUMBER_ENERGY_BUCKETS];
        supportedEnergyBuckets[ENERGY_BUCKET_SCREEN_ON] = true;
        supportedEnergyBuckets[ENERGY_BUCKET_SCREEN_DOZE] = false;
        supportedEnergyBuckets[ENERGY_BUCKET_SCREEN_OTHER] = true;

        final MeasuredEnergyStats template = new MeasuredEnergyStats(supportedEnergyBuckets);
        template.updateBucket(ENERGY_BUCKET_SCREEN_ON, 10, true);
        template.updateBucket(ENERGY_BUCKET_SCREEN_ON, 5, true);
        template.updateBucket(ENERGY_BUCKET_SCREEN_OTHER, 40, true);

        final MeasuredEnergyStats stats = MeasuredEnergyStats.createFromTemplate(template);
        stats.updateBucket(ENERGY_BUCKET_SCREEN_ON, 200, true);
        stats.updateBucket(ENERGY_BUCKET_SCREEN_ON, 7, true);
        stats.updateBucket(ENERGY_BUCKET_SCREEN_OTHER, 63, true);

        final Parcel parcel = Parcel.obtain();
        MeasuredEnergyStats.writeSummaryToParcel(stats, parcel);

        final boolean[] newSupportedEnergyBuckets = new boolean[NUMBER_ENERGY_BUCKETS];
        newSupportedEnergyBuckets[ENERGY_BUCKET_SCREEN_ON] = true;
        newSupportedEnergyBuckets[ENERGY_BUCKET_SCREEN_DOZE] = true; // switched from false to true
        newSupportedEnergyBuckets[ENERGY_BUCKET_SCREEN_OTHER] = false; // switched true to false
        final MeasuredEnergyStats newTemplate = new MeasuredEnergyStats(newSupportedEnergyBuckets);
        parcel.setDataPosition(0);

        final MeasuredEnergyStats newStats =
                MeasuredEnergyStats.createAndReadSummaryFromParcel(parcel, newTemplate);

        for (int i = 0; i < NUMBER_ENERGY_BUCKETS; i++) {
            if (!newSupportedEnergyBuckets[i]) {
                assertFalse(newStats.isEnergyBucketSupported(i));
                assertEquals(ENERGY_DATA_UNAVAILABLE, newStats.getAccumulatedBucketEnergy(i));
            } else if (!supportedEnergyBuckets[i]) {
                assertTrue(newStats.isEnergyBucketSupported(i));
                assertEquals(0L, newStats.getAccumulatedBucketEnergy(i));
            } else {
                assertTrue(newStats.isEnergyBucketSupported(i));
                assertEquals(stats.getAccumulatedBucketEnergy(i),
                        newStats.getAccumulatedBucketEnergy(i));
            }
        }
        parcel.recycle();
    }

    @Test
    public void testUpdateBucket() {
        final boolean[] supportedEnergyBuckets = new boolean[NUMBER_ENERGY_BUCKETS];
        supportedEnergyBuckets[ENERGY_BUCKET_SCREEN_ON] = true;
        supportedEnergyBuckets[ENERGY_BUCKET_SCREEN_DOZE] = false;
        supportedEnergyBuckets[ENERGY_BUCKET_SCREEN_OTHER] = true;

        final MeasuredEnergyStats stats = new MeasuredEnergyStats(supportedEnergyBuckets);
        stats.updateBucket(ENERGY_BUCKET_SCREEN_ON, 10, true);
        stats.updateBucket(ENERGY_BUCKET_SCREEN_DOZE, 30, true);
        stats.updateBucket(ENERGY_BUCKET_SCREEN_OTHER, 40, true);
        stats.updateBucket(ENERGY_BUCKET_SCREEN_ON, 5, true);

        assertEquals(15, stats.getAccumulatedBucketEnergy(ENERGY_BUCKET_SCREEN_ON));
        assertEquals(ENERGY_DATA_UNAVAILABLE,
                stats.getAccumulatedBucketEnergy(ENERGY_BUCKET_SCREEN_DOZE));
        assertEquals(40, stats.getAccumulatedBucketEnergy(ENERGY_BUCKET_SCREEN_OTHER));
    }

    @Test
    public void testReset() {
        final boolean[] supportedEnergyBuckets = new boolean[NUMBER_ENERGY_BUCKETS];
        supportedEnergyBuckets[ENERGY_BUCKET_SCREEN_ON] = true;
        supportedEnergyBuckets[ENERGY_BUCKET_SCREEN_DOZE] = false;
        supportedEnergyBuckets[ENERGY_BUCKET_SCREEN_OTHER] = true;

        final MeasuredEnergyStats stats = new MeasuredEnergyStats(supportedEnergyBuckets);
        stats.updateBucket(ENERGY_BUCKET_SCREEN_ON, 10, true);
        stats.updateBucket(ENERGY_BUCKET_SCREEN_ON, 5, true);
        stats.updateBucket(ENERGY_BUCKET_SCREEN_OTHER, 40, true);

        MeasuredEnergyStats.resetIfNotNull(stats);
        // All energy should be reset to 0
        for (int i = 0; i < NUMBER_ENERGY_BUCKETS; i++) {
            if (supportedEnergyBuckets[i]) {
                assertTrue(stats.isEnergyBucketSupported(i));
                assertEquals(0, stats.getAccumulatedBucketEnergy(i));
            } else {
                assertFalse(stats.isEnergyBucketSupported(i));
                assertEquals(ENERGY_DATA_UNAVAILABLE, stats.getAccumulatedBucketEnergy(i));
            }
        }

        // Values should increase as usual.
        stats.updateBucket(ENERGY_BUCKET_SCREEN_ON, 70, true);
        assertEquals(70L, stats.getAccumulatedBucketEnergy(ENERGY_BUCKET_SCREEN_ON));
    }

    /** Test that states are mapped to the expected energy buckets. Beware of mapping changes. */
    @Test
    public void testEnergyBucketMapping() {
        int exp;

        exp = ENERGY_BUCKET_SCREEN_ON;
        assertEquals(exp, MeasuredEnergyStats.getDisplayEnergyBucket(Display.STATE_ON));
        assertEquals(exp, MeasuredEnergyStats.getDisplayEnergyBucket(Display.STATE_VR));
        assertEquals(exp, MeasuredEnergyStats.getDisplayEnergyBucket(Display.STATE_ON_SUSPEND));

        exp = ENERGY_BUCKET_SCREEN_DOZE;
        assertEquals(exp, MeasuredEnergyStats.getDisplayEnergyBucket(Display.STATE_DOZE));
        assertEquals(exp, MeasuredEnergyStats.getDisplayEnergyBucket(Display.STATE_DOZE_SUSPEND));
    }
}
