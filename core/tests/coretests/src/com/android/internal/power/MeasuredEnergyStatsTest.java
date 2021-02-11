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
import static com.android.internal.power.MeasuredEnergyStats.NUMBER_STANDARD_ENERGY_BUCKETS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Parcel;
import android.view.Display;

import androidx.test.filters.SmallTest;

import org.junit.Test;

import java.util.Arrays;

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
        final boolean[] supportedStandardBuckets = new boolean[NUMBER_STANDARD_ENERGY_BUCKETS];
        final int numCustomBuckets = 2;
        supportedStandardBuckets[ENERGY_BUCKET_SCREEN_ON] = true;
        supportedStandardBuckets[ENERGY_BUCKET_SCREEN_DOZE] = false;
        supportedStandardBuckets[ENERGY_BUCKET_SCREEN_OTHER] = true;

        final MeasuredEnergyStats stats
                = new MeasuredEnergyStats(supportedStandardBuckets, numCustomBuckets);

        for (int i = 0; i < NUMBER_STANDARD_ENERGY_BUCKETS; i++) {
            if (supportedStandardBuckets[i]) {
                assertTrue(stats.isStandardBucketSupported(i));
                assertEquals(0L, stats.getAccumulatedStandardBucketEnergy(i));
            } else {
                assertFalse(stats.isStandardBucketSupported(i));
                assertEquals(ENERGY_DATA_UNAVAILABLE, stats.getAccumulatedStandardBucketEnergy(i));
            }
        }
        for (int i = 0; i < numCustomBuckets; i++) {
            assertEquals(0L, stats.getAccumulatedCustomBucketEnergy(i));
        }
    }

    @Test
    public void testCreateFromTemplate() {
        final boolean[] supportedStandardBuckets = new boolean[NUMBER_STANDARD_ENERGY_BUCKETS];
        final int numCustomBuckets = 2;
        supportedStandardBuckets[ENERGY_BUCKET_SCREEN_ON] = true;
        supportedStandardBuckets[ENERGY_BUCKET_SCREEN_DOZE] = false;
        supportedStandardBuckets[ENERGY_BUCKET_SCREEN_OTHER] = true;

        final MeasuredEnergyStats stats
                = new MeasuredEnergyStats(supportedStandardBuckets, numCustomBuckets);
        stats.updateStandardBucket(ENERGY_BUCKET_SCREEN_ON, 10, true);
        stats.updateStandardBucket(ENERGY_BUCKET_SCREEN_ON, 5, true);
        stats.updateStandardBucket(ENERGY_BUCKET_SCREEN_OTHER, 40, true);
        stats.updateCustomBucket(0, 50, true);
        stats.updateCustomBucket(1, 60, true);

        final MeasuredEnergyStats newStats = MeasuredEnergyStats.createFromTemplate(stats);

        for (int i = 0; i < NUMBER_STANDARD_ENERGY_BUCKETS; i++) {
            if (supportedStandardBuckets[i]) {
                assertTrue(newStats.isStandardBucketSupported(i));
                assertEquals(0L, newStats.getAccumulatedStandardBucketEnergy(i));
            } else {
                assertFalse(newStats.isStandardBucketSupported(i));
                assertEquals(ENERGY_DATA_UNAVAILABLE,
                        newStats.getAccumulatedStandardBucketEnergy(i));
            }
        }
        for (int i = 0; i < numCustomBuckets; i++) {
            assertEquals(0L, newStats.getAccumulatedCustomBucketEnergy(i));
        }
    }

    @Test
    public void testReadWriteParcel() {
        final boolean[] supportedStandardBuckets = new boolean[NUMBER_STANDARD_ENERGY_BUCKETS];
        final int numCustomBuckets = 2;
        supportedStandardBuckets[ENERGY_BUCKET_SCREEN_ON] = true;
        supportedStandardBuckets[ENERGY_BUCKET_SCREEN_DOZE] = false;
        supportedStandardBuckets[ENERGY_BUCKET_SCREEN_OTHER] = true;

        final MeasuredEnergyStats stats
                = new MeasuredEnergyStats(supportedStandardBuckets, numCustomBuckets);
        stats.updateStandardBucket(ENERGY_BUCKET_SCREEN_ON, 10, true);
        stats.updateStandardBucket(ENERGY_BUCKET_SCREEN_ON, 5, true);
        stats.updateStandardBucket(ENERGY_BUCKET_SCREEN_OTHER, 40, true);
        stats.updateCustomBucket(0, 50, true);
        stats.updateCustomBucket(1, 60, true);

        final Parcel parcel = Parcel.obtain();
        stats.writeToParcel(parcel);

        parcel.setDataPosition(0);
        MeasuredEnergyStats newStats = new MeasuredEnergyStats(parcel);

        for (int i = 0; i < NUMBER_STANDARD_ENERGY_BUCKETS; i++) {
            assertEquals(stats.getAccumulatedStandardBucketEnergy(i),
                    newStats.getAccumulatedStandardBucketEnergy(i));
        }
        for (int i = 0; i < numCustomBuckets; i++) {
            assertEquals(stats.getAccumulatedCustomBucketEnergy(i),
                    newStats.getAccumulatedCustomBucketEnergy(i));
        }
        assertEquals(ENERGY_DATA_UNAVAILABLE,
                newStats.getAccumulatedCustomBucketEnergy(numCustomBuckets + 1));
        parcel.recycle();
    }

    @Test
    public void testCreateAndReadSummaryFromParcel() {
        final boolean[] supportedStandardBuckets = new boolean[NUMBER_STANDARD_ENERGY_BUCKETS];
        final int numCustomBuckets = 2;
        supportedStandardBuckets[ENERGY_BUCKET_SCREEN_ON] = true;
        supportedStandardBuckets[ENERGY_BUCKET_SCREEN_DOZE] = false;
        supportedStandardBuckets[ENERGY_BUCKET_SCREEN_OTHER] = true;

        final MeasuredEnergyStats stats
                = new MeasuredEnergyStats(supportedStandardBuckets, numCustomBuckets);
        stats.updateStandardBucket(ENERGY_BUCKET_SCREEN_ON, 10, true);
        stats.updateStandardBucket(ENERGY_BUCKET_SCREEN_ON, 5, true);
        stats.updateStandardBucket(ENERGY_BUCKET_SCREEN_OTHER, 40, true);
        stats.updateCustomBucket(0, 50, true);
        stats.updateCustomBucket(1, 60, true);

        final Parcel parcel = Parcel.obtain();
        MeasuredEnergyStats.writeSummaryToParcel(stats, parcel, false);
        parcel.setDataPosition(0);
        MeasuredEnergyStats newStats = MeasuredEnergyStats.createAndReadSummaryFromParcel(parcel);

        for (int i = 0; i < NUMBER_STANDARD_ENERGY_BUCKETS; i++) {
            assertEquals(stats.isStandardBucketSupported(i),
                    newStats.isStandardBucketSupported(i));
            assertEquals(stats.getAccumulatedStandardBucketEnergy(i),
                    newStats.getAccumulatedStandardBucketEnergy(i));
        }
        for (int i = 0; i < numCustomBuckets; i++) {
            assertEquals(stats.getAccumulatedCustomBucketEnergy(i),
                    newStats.getAccumulatedCustomBucketEnergy(i));
        }
        assertEquals(ENERGY_DATA_UNAVAILABLE,
                newStats.getAccumulatedCustomBucketEnergy(numCustomBuckets + 1));
        parcel.recycle();
    }

    @Test
    public void testCreateAndReadSummaryFromParcel_existingTemplate() {
        final boolean[] supportedStandardBuckets = new boolean[NUMBER_STANDARD_ENERGY_BUCKETS];
        final int numCustomBuckets = 2;
        supportedStandardBuckets[ENERGY_BUCKET_SCREEN_ON] = true;
        supportedStandardBuckets[ENERGY_BUCKET_SCREEN_DOZE] = false;
        supportedStandardBuckets[ENERGY_BUCKET_SCREEN_OTHER] = true;

        final MeasuredEnergyStats template
                = new MeasuredEnergyStats(supportedStandardBuckets, numCustomBuckets);
        template.updateStandardBucket(ENERGY_BUCKET_SCREEN_ON, 10, true);
        template.updateStandardBucket(ENERGY_BUCKET_SCREEN_ON, 5, true);
        template.updateStandardBucket(ENERGY_BUCKET_SCREEN_OTHER, 40, true);
        template.updateCustomBucket(0, 50, true);

        final MeasuredEnergyStats stats = MeasuredEnergyStats.createFromTemplate(template);
        stats.updateStandardBucket(ENERGY_BUCKET_SCREEN_ON, 200, true);
        stats.updateStandardBucket(ENERGY_BUCKET_SCREEN_ON, 7, true);
        stats.updateStandardBucket(ENERGY_BUCKET_SCREEN_OTHER, 63, true);
        stats.updateCustomBucket(0, 315, true);
        stats.updateCustomBucket(1, 316, true);

        final Parcel parcel = Parcel.obtain();
        MeasuredEnergyStats.writeSummaryToParcel(stats, parcel, false);

        final boolean[] newsupportedStandardBuckets = new boolean[NUMBER_STANDARD_ENERGY_BUCKETS];
        newsupportedStandardBuckets[ENERGY_BUCKET_SCREEN_ON] = true;
        newsupportedStandardBuckets[ENERGY_BUCKET_SCREEN_DOZE] = true; // switched false > true
        newsupportedStandardBuckets[ENERGY_BUCKET_SCREEN_OTHER] = false; // switched true > false
        final MeasuredEnergyStats newTemplate
                = new MeasuredEnergyStats(newsupportedStandardBuckets, numCustomBuckets);
        parcel.setDataPosition(0);

        final MeasuredEnergyStats newStats =
                MeasuredEnergyStats.createAndReadSummaryFromParcel(parcel, newTemplate);

        for (int i = 0; i < NUMBER_STANDARD_ENERGY_BUCKETS; i++) {
            if (!newsupportedStandardBuckets[i]) {
                assertFalse(newStats.isStandardBucketSupported(i));
                assertEquals(ENERGY_DATA_UNAVAILABLE,
                        newStats.getAccumulatedStandardBucketEnergy(i));
            } else if (!supportedStandardBuckets[i]) {
                assertTrue(newStats.isStandardBucketSupported(i));
                assertEquals(0L, newStats.getAccumulatedStandardBucketEnergy(i));
            } else {
                assertTrue(newStats.isStandardBucketSupported(i));
                assertEquals(stats.getAccumulatedStandardBucketEnergy(i),
                        newStats.getAccumulatedStandardBucketEnergy(i));
            }
        }
        for (int i = 0; i < numCustomBuckets; i++) {
            assertEquals(stats.getAccumulatedCustomBucketEnergy(i),
                    newStats.getAccumulatedCustomBucketEnergy(i));
        }
        assertEquals(ENERGY_DATA_UNAVAILABLE,
                newStats.getAccumulatedCustomBucketEnergy(numCustomBuckets + 1));
        parcel.recycle();
    }

    @Test
    public void testCreateAndReadSummaryFromParcel_skipZero() {
        final boolean[] supportedStandardBuckets = new boolean[NUMBER_STANDARD_ENERGY_BUCKETS];
        final int numCustomBuckets = 2;
        Arrays.fill(supportedStandardBuckets, true);

        final MeasuredEnergyStats stats
                = new MeasuredEnergyStats(supportedStandardBuckets, numCustomBuckets);
        // Accumulate energy in one bucket and one custom bucket, the rest should be zero
        stats.updateStandardBucket(ENERGY_BUCKET_SCREEN_ON, 200, true);
        stats.updateCustomBucket(1, 60, true);

        // Let's try parcelling with including zeros
        final Parcel includeZerosParcel = Parcel.obtain();
        MeasuredEnergyStats.writeSummaryToParcel(stats, includeZerosParcel, false);
        includeZerosParcel.setDataPosition(0);

        MeasuredEnergyStats newStats = MeasuredEnergyStats.createAndReadSummaryFromParcel(
                includeZerosParcel);

        for (int i = 0; i < NUMBER_STANDARD_ENERGY_BUCKETS; i++) {
            if (i == ENERGY_BUCKET_SCREEN_ON) {
                assertEquals(stats.isStandardBucketSupported(i),
                        newStats.isStandardBucketSupported(i));
                assertEquals(stats.getAccumulatedStandardBucketEnergy(i),
                        newStats.getAccumulatedStandardBucketEnergy(i));
            } else {
                assertTrue(newStats.isStandardBucketSupported(i));
                assertEquals(0L, newStats.getAccumulatedStandardBucketEnergy(i));
            }
        }
        assertEquals(0L, newStats.getAccumulatedCustomBucketEnergy(0));
        assertEquals(stats.getAccumulatedCustomBucketEnergy(1),
                newStats.getAccumulatedCustomBucketEnergy(1));
        includeZerosParcel.recycle();

        // Now let's try parcelling with skipping zeros
        final Parcel skipZerosParcel = Parcel.obtain();
        MeasuredEnergyStats.writeSummaryToParcel(stats, skipZerosParcel, true);
        skipZerosParcel.setDataPosition(0);

        newStats = MeasuredEnergyStats.createAndReadSummaryFromParcel(skipZerosParcel);

        for (int i = 0; i < NUMBER_STANDARD_ENERGY_BUCKETS; i++) {
            if (i == ENERGY_BUCKET_SCREEN_ON) {
                assertEquals(stats.isStandardBucketSupported(i),
                        newStats.isStandardBucketSupported(i));
                assertEquals(stats.getAccumulatedStandardBucketEnergy(i),
                        newStats.getAccumulatedStandardBucketEnergy(i));
            } else {
                assertFalse(newStats.isStandardBucketSupported(i));
                assertEquals(ENERGY_DATA_UNAVAILABLE,
                        newStats.getAccumulatedStandardBucketEnergy(i));
            }
        }
        assertEquals(0L, newStats.getAccumulatedCustomBucketEnergy(0));
        assertEquals(stats.getAccumulatedCustomBucketEnergy(1),
                newStats.getAccumulatedCustomBucketEnergy(1));
        skipZerosParcel.recycle();
    }

    @Test
    public void testCreateAndReadSummaryFromParcel_nullTemplate() {
        final boolean[] supportedStandardBuckets = new boolean[NUMBER_STANDARD_ENERGY_BUCKETS];
        final int numCustomBuckets = 2;
        supportedStandardBuckets[ENERGY_BUCKET_SCREEN_ON] = true;
        supportedStandardBuckets[ENERGY_BUCKET_SCREEN_DOZE] = false;
        supportedStandardBuckets[ENERGY_BUCKET_SCREEN_OTHER] = true;

        final MeasuredEnergyStats stats
                = new MeasuredEnergyStats(supportedStandardBuckets, numCustomBuckets);
        stats.updateStandardBucket(ENERGY_BUCKET_SCREEN_ON, 10, true);
        stats.updateStandardBucket(ENERGY_BUCKET_SCREEN_ON, 5, true);
        stats.updateStandardBucket(ENERGY_BUCKET_SCREEN_OTHER, 40, true);
        stats.updateCustomBucket(0, 50, true);
        stats.updateCustomBucket(1, 60, true);

        final Parcel parcel = Parcel.obtain();
        MeasuredEnergyStats.writeSummaryToParcel(stats, parcel, false);
        parcel.setDataPosition(0);

        MeasuredEnergyStats newStats
                = MeasuredEnergyStats.createAndReadSummaryFromParcel(parcel, null);
        assertNull(newStats);
        parcel.recycle();
    }

    @Test
    public void testCreateAndReadSummaryFromParcel_boring() {
        final boolean[] supportedStandardBuckets = new boolean[NUMBER_STANDARD_ENERGY_BUCKETS];
        final int numCustomBuckets = 2;
        supportedStandardBuckets[ENERGY_BUCKET_SCREEN_ON] = true;
        supportedStandardBuckets[ENERGY_BUCKET_SCREEN_DOZE] = false;
        supportedStandardBuckets[ENERGY_BUCKET_SCREEN_OTHER] = true;

        final MeasuredEnergyStats template
                = new MeasuredEnergyStats(supportedStandardBuckets, numCustomBuckets);
        template.updateStandardBucket(ENERGY_BUCKET_SCREEN_ON, 10, true);
        template.updateStandardBucket(ENERGY_BUCKET_SCREEN_ON, 5, true);
        template.updateStandardBucket(ENERGY_BUCKET_SCREEN_OTHER, 40, true);
        template.updateCustomBucket(0, 50, true);

        final MeasuredEnergyStats stats = MeasuredEnergyStats.createFromTemplate(template);
        stats.updateStandardBucket(ENERGY_BUCKET_SCREEN_ON, 0L, true);
        stats.updateStandardBucket(ENERGY_BUCKET_SCREEN_OTHER, 7L, true);

        final Parcel parcel = Parcel.obtain();
        MeasuredEnergyStats.writeSummaryToParcel(stats, parcel, false);

        final boolean[] newSupportedStandardBuckets = new boolean[NUMBER_STANDARD_ENERGY_BUCKETS];
        newSupportedStandardBuckets[ENERGY_BUCKET_SCREEN_ON] = true;
        newSupportedStandardBuckets[ENERGY_BUCKET_SCREEN_DOZE] = true; // switched false > true
        newSupportedStandardBuckets[ENERGY_BUCKET_SCREEN_OTHER] = false; // switched true > false
        final MeasuredEnergyStats newTemplate
                = new MeasuredEnergyStats(newSupportedStandardBuckets, numCustomBuckets);
        parcel.setDataPosition(0);

        final MeasuredEnergyStats newStats =
                MeasuredEnergyStats.createAndReadSummaryFromParcel(parcel, newTemplate);
        // The only non-0 entry in stats is no longer supported, so now there's no interesting data.
        assertNull(newStats);
        assertEquals("Parcel was not properly consumed", 0, parcel.dataAvail());
        parcel.recycle();
    }

    @Test
    public void testUpdateBucket() {
        final boolean[] supportedStandardBuckets = new boolean[NUMBER_STANDARD_ENERGY_BUCKETS];
        final int numCustomBuckets = 2;
        supportedStandardBuckets[ENERGY_BUCKET_SCREEN_ON] = true;
        supportedStandardBuckets[ENERGY_BUCKET_SCREEN_DOZE] = false;
        supportedStandardBuckets[ENERGY_BUCKET_SCREEN_OTHER] = true;

        final MeasuredEnergyStats stats
                = new MeasuredEnergyStats(supportedStandardBuckets, numCustomBuckets);
        stats.updateStandardBucket(ENERGY_BUCKET_SCREEN_ON, 10, true);
        stats.updateStandardBucket(ENERGY_BUCKET_SCREEN_DOZE, 30, true);
        stats.updateStandardBucket(ENERGY_BUCKET_SCREEN_OTHER, 40, true);
        stats.updateStandardBucket(ENERGY_BUCKET_SCREEN_ON, 5, true);

        stats.updateCustomBucket(0, 50, true);
        stats.updateCustomBucket(1, 60, true);
        stats.updateCustomBucket(0, 3, true);

        assertEquals(15, stats.getAccumulatedStandardBucketEnergy(ENERGY_BUCKET_SCREEN_ON));
        assertEquals(ENERGY_DATA_UNAVAILABLE,
                stats.getAccumulatedStandardBucketEnergy(ENERGY_BUCKET_SCREEN_DOZE));
        assertEquals(40, stats.getAccumulatedStandardBucketEnergy(ENERGY_BUCKET_SCREEN_OTHER));
        assertEquals(50 + 3, stats.getAccumulatedCustomBucketEnergy(0));
        assertEquals(60, stats.getAccumulatedCustomBucketEnergy(1));
    }

    @Test
    public void testIsValidCustomBucket() {
        final MeasuredEnergyStats stats
                = new MeasuredEnergyStats(new boolean[NUMBER_STANDARD_ENERGY_BUCKETS], 3);
        assertFalse(stats.isValidCustomBucket(-1));
        assertTrue(stats.isValidCustomBucket(0));
        assertTrue(stats.isValidCustomBucket(1));
        assertTrue(stats.isValidCustomBucket(2));
        assertFalse(stats.isValidCustomBucket(3));
        assertFalse(stats.isValidCustomBucket(4));

        final MeasuredEnergyStats boringStats
                = new MeasuredEnergyStats(new boolean[NUMBER_STANDARD_ENERGY_BUCKETS], 0);
        assertFalse(boringStats.isValidCustomBucket(-1));
        assertFalse(boringStats.isValidCustomBucket(0));
        assertFalse(boringStats.isValidCustomBucket(1));
    }

    @Test
    public void testGetAccumulatedCustomBucketEnergies() {
        final MeasuredEnergyStats stats
                = new MeasuredEnergyStats(new boolean[NUMBER_STANDARD_ENERGY_BUCKETS], 3);

        stats.updateCustomBucket(0, 50, true);
        stats.updateCustomBucket(1, 60, true);
        stats.updateCustomBucket(2, 13, true);
        stats.updateCustomBucket(1, 70, true);

        final long[] output = stats.getAccumulatedCustomBucketEnergies();
        assertEquals(3, output.length);

        assertEquals(50, output[0]);
        assertEquals(60 + 70, output[1]);
        assertEquals(13, output[2]);
    }

    @Test
    public void testGetAccumulatedCustomBucketEnergies_empty() {
        final MeasuredEnergyStats stats
                = new MeasuredEnergyStats(new boolean[NUMBER_STANDARD_ENERGY_BUCKETS], 0);

        final long[] output = stats.getAccumulatedCustomBucketEnergies();
        assertEquals(0, output.length);
    }

    @Test
    public void testGetNumberCustomEnergyBuckets() {
        assertEquals(0, new MeasuredEnergyStats(new boolean[NUMBER_STANDARD_ENERGY_BUCKETS], 0)
                .getNumberCustomEnergyBuckets());
        assertEquals(3, new MeasuredEnergyStats(new boolean[NUMBER_STANDARD_ENERGY_BUCKETS], 3)
                .getNumberCustomEnergyBuckets());
    }

    @Test
    public void testReset() {
        final boolean[] supportedStandardBuckets = new boolean[NUMBER_STANDARD_ENERGY_BUCKETS];
        final int numCustomBuckets = 2;
        supportedStandardBuckets[ENERGY_BUCKET_SCREEN_ON] = true;
        supportedStandardBuckets[ENERGY_BUCKET_SCREEN_DOZE] = false;
        supportedStandardBuckets[ENERGY_BUCKET_SCREEN_OTHER] = true;

        final MeasuredEnergyStats stats
                = new MeasuredEnergyStats(supportedStandardBuckets, numCustomBuckets);
        stats.updateStandardBucket(ENERGY_BUCKET_SCREEN_ON, 10, true);
        stats.updateStandardBucket(ENERGY_BUCKET_SCREEN_ON, 5, true);
        stats.updateStandardBucket(ENERGY_BUCKET_SCREEN_OTHER, 40, true);
        stats.updateCustomBucket(0, 50, true);
        stats.updateCustomBucket(1, 60, true);

        MeasuredEnergyStats.resetIfNotNull(stats);
        // All energy should be reset to 0
        for (int i = 0; i < NUMBER_STANDARD_ENERGY_BUCKETS; i++) {
            if (supportedStandardBuckets[i]) {
                assertTrue(stats.isStandardBucketSupported(i));
                assertEquals(0, stats.getAccumulatedStandardBucketEnergy(i));
            } else {
                assertFalse(stats.isStandardBucketSupported(i));
                assertEquals(ENERGY_DATA_UNAVAILABLE, stats.getAccumulatedStandardBucketEnergy(i));
            }
        }
        for (int i = 0; i < numCustomBuckets; i++) {
            assertEquals(0, stats.getAccumulatedCustomBucketEnergy(i));
        }

        // Values should increase as usual.
        stats.updateStandardBucket(ENERGY_BUCKET_SCREEN_ON, 70, true);
        assertEquals(70L, stats.getAccumulatedStandardBucketEnergy(ENERGY_BUCKET_SCREEN_ON));

        stats.updateCustomBucket(1, 12, true);
        assertEquals(12L, stats.getAccumulatedCustomBucketEnergy(1));
    }

    /** Test that states are mapped to the expected energy buckets. Beware of mapping changes. */
    @Test
    public void testStandardBucketMapping() {
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
