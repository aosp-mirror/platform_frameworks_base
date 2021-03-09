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

import static android.os.BatteryStats.POWER_DATA_UNAVAILABLE;

import static com.android.internal.power.MeasuredEnergyStats.NUMBER_STANDARD_POWER_BUCKETS;
import static com.android.internal.power.MeasuredEnergyStats.POWER_BUCKET_SCREEN_DOZE;
import static com.android.internal.power.MeasuredEnergyStats.POWER_BUCKET_SCREEN_ON;
import static com.android.internal.power.MeasuredEnergyStats.POWER_BUCKET_SCREEN_OTHER;

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
        final boolean[] supportedStandardBuckets = new boolean[NUMBER_STANDARD_POWER_BUCKETS];
        final int numCustomBuckets = 2;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_ON] = true;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_DOZE] = false;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_OTHER] = true;

        final MeasuredEnergyStats stats =
                new MeasuredEnergyStats(supportedStandardBuckets, numCustomBuckets);

        for (int i = 0; i < NUMBER_STANDARD_POWER_BUCKETS; i++) {
            if (supportedStandardBuckets[i]) {
                assertTrue(stats.isStandardBucketSupported(i));
                assertEquals(0L, stats.getAccumulatedStandardBucketCharge(i));
            } else {
                assertFalse(stats.isStandardBucketSupported(i));
                assertEquals(POWER_DATA_UNAVAILABLE, stats.getAccumulatedStandardBucketCharge(i));
            }
        }
        for (int i = 0; i < numCustomBuckets; i++) {
            assertEquals(0L, stats.getAccumulatedCustomBucketCharge(i));
        }
    }

    @Test
    public void testCreateFromTemplate() {
        final boolean[] supportedStandardBuckets = new boolean[NUMBER_STANDARD_POWER_BUCKETS];
        final int numCustomBuckets = 2;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_ON] = true;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_DOZE] = false;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_OTHER] = true;

        final MeasuredEnergyStats stats =
                new MeasuredEnergyStats(supportedStandardBuckets, numCustomBuckets);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_ON, 10);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_ON, 5);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_OTHER, 40);
        stats.updateCustomBucket(0, 50);
        stats.updateCustomBucket(1, 60);

        final MeasuredEnergyStats newStats = MeasuredEnergyStats.createFromTemplate(stats);

        for (int i = 0; i < NUMBER_STANDARD_POWER_BUCKETS; i++) {
            if (supportedStandardBuckets[i]) {
                assertTrue(newStats.isStandardBucketSupported(i));
                assertEquals(0L, newStats.getAccumulatedStandardBucketCharge(i));
            } else {
                assertFalse(newStats.isStandardBucketSupported(i));
                assertEquals(POWER_DATA_UNAVAILABLE,
                        newStats.getAccumulatedStandardBucketCharge(i));
            }
        }
        for (int i = 0; i < numCustomBuckets; i++) {
            assertEquals(0L, newStats.getAccumulatedCustomBucketCharge(i));
        }
    }

    @Test
    public void testReadWriteParcel() {
        final boolean[] supportedStandardBuckets = new boolean[NUMBER_STANDARD_POWER_BUCKETS];
        final int numCustomBuckets = 2;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_ON] = true;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_DOZE] = false;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_OTHER] = true;

        final MeasuredEnergyStats stats =
                new MeasuredEnergyStats(supportedStandardBuckets, numCustomBuckets);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_ON, 10);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_ON, 5);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_OTHER, 40);
        stats.updateCustomBucket(0, 50);
        stats.updateCustomBucket(1, 60);

        final Parcel parcel = Parcel.obtain();
        stats.writeToParcel(parcel);

        parcel.setDataPosition(0);
        MeasuredEnergyStats newStats = new MeasuredEnergyStats(parcel);

        for (int i = 0; i < NUMBER_STANDARD_POWER_BUCKETS; i++) {
            assertEquals(stats.getAccumulatedStandardBucketCharge(i),
                    newStats.getAccumulatedStandardBucketCharge(i));
        }
        for (int i = 0; i < numCustomBuckets; i++) {
            assertEquals(stats.getAccumulatedCustomBucketCharge(i),
                    newStats.getAccumulatedCustomBucketCharge(i));
        }
        assertEquals(POWER_DATA_UNAVAILABLE,
                newStats.getAccumulatedCustomBucketCharge(numCustomBuckets + 1));
        parcel.recycle();
    }

    @Test
    public void testCreateAndReadSummaryFromParcel() {
        final boolean[] supportedStandardBuckets = new boolean[NUMBER_STANDARD_POWER_BUCKETS];
        final int numCustomBuckets = 2;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_ON] = true;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_DOZE] = false;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_OTHER] = true;

        final MeasuredEnergyStats stats =
                new MeasuredEnergyStats(supportedStandardBuckets, numCustomBuckets);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_ON, 10);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_ON, 5);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_OTHER, 40);
        stats.updateCustomBucket(0, 50);
        stats.updateCustomBucket(1, 60);

        final Parcel parcel = Parcel.obtain();
        MeasuredEnergyStats.writeSummaryToParcel(stats, parcel, false);
        parcel.setDataPosition(0);
        MeasuredEnergyStats newStats = MeasuredEnergyStats.createAndReadSummaryFromParcel(parcel);

        for (int i = 0; i < NUMBER_STANDARD_POWER_BUCKETS; i++) {
            assertEquals(stats.isStandardBucketSupported(i),
                    newStats.isStandardBucketSupported(i));
            assertEquals(stats.getAccumulatedStandardBucketCharge(i),
                    newStats.getAccumulatedStandardBucketCharge(i));
        }
        for (int i = 0; i < numCustomBuckets; i++) {
            assertEquals(stats.getAccumulatedCustomBucketCharge(i),
                    newStats.getAccumulatedCustomBucketCharge(i));
        }
        assertEquals(POWER_DATA_UNAVAILABLE,
                newStats.getAccumulatedCustomBucketCharge(numCustomBuckets + 1));
        parcel.recycle();
    }

    @Test
    public void testCreateAndReadSummaryFromParcel_existingTemplate() {
        final boolean[] supportedStandardBuckets = new boolean[NUMBER_STANDARD_POWER_BUCKETS];
        final int numCustomBuckets = 2;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_ON] = true;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_DOZE] = false;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_OTHER] = true;

        final MeasuredEnergyStats template =
                new MeasuredEnergyStats(supportedStandardBuckets, numCustomBuckets);
        template.updateStandardBucket(POWER_BUCKET_SCREEN_ON, 10);
        template.updateStandardBucket(POWER_BUCKET_SCREEN_ON, 5);
        template.updateStandardBucket(POWER_BUCKET_SCREEN_OTHER, 40);
        template.updateCustomBucket(0, 50);

        final MeasuredEnergyStats stats = MeasuredEnergyStats.createFromTemplate(template);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_ON, 200);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_ON, 7);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_OTHER, 63);
        stats.updateCustomBucket(0, 315);
        stats.updateCustomBucket(1, 316);

        final Parcel parcel = Parcel.obtain();
        MeasuredEnergyStats.writeSummaryToParcel(stats, parcel, false);

        final boolean[] newsupportedStandardBuckets = new boolean[NUMBER_STANDARD_POWER_BUCKETS];
        newsupportedStandardBuckets[POWER_BUCKET_SCREEN_ON] = true;
        newsupportedStandardBuckets[POWER_BUCKET_SCREEN_DOZE] = true; // switched false > true
        newsupportedStandardBuckets[POWER_BUCKET_SCREEN_OTHER] = false; // switched true > false
        final MeasuredEnergyStats newTemplate =
                new MeasuredEnergyStats(newsupportedStandardBuckets, numCustomBuckets);
        parcel.setDataPosition(0);

        final MeasuredEnergyStats newStats =
                MeasuredEnergyStats.createAndReadSummaryFromParcel(parcel, newTemplate);

        for (int i = 0; i < NUMBER_STANDARD_POWER_BUCKETS; i++) {
            if (!newsupportedStandardBuckets[i]) {
                assertFalse(newStats.isStandardBucketSupported(i));
                assertEquals(POWER_DATA_UNAVAILABLE,
                        newStats.getAccumulatedStandardBucketCharge(i));
            } else if (!supportedStandardBuckets[i]) {
                assertTrue(newStats.isStandardBucketSupported(i));
                assertEquals(0L, newStats.getAccumulatedStandardBucketCharge(i));
            } else {
                assertTrue(newStats.isStandardBucketSupported(i));
                assertEquals(stats.getAccumulatedStandardBucketCharge(i),
                        newStats.getAccumulatedStandardBucketCharge(i));
            }
        }
        for (int i = 0; i < numCustomBuckets; i++) {
            assertEquals(stats.getAccumulatedCustomBucketCharge(i),
                    newStats.getAccumulatedCustomBucketCharge(i));
        }
        assertEquals(POWER_DATA_UNAVAILABLE,
                newStats.getAccumulatedCustomBucketCharge(numCustomBuckets + 1));
        parcel.recycle();
    }

    @Test
    public void testCreateAndReadSummaryFromParcel_skipZero() {
        final boolean[] supportedStandardBuckets = new boolean[NUMBER_STANDARD_POWER_BUCKETS];
        final int numCustomBuckets = 2;
        Arrays.fill(supportedStandardBuckets, true);

        final MeasuredEnergyStats stats =
                new MeasuredEnergyStats(supportedStandardBuckets, numCustomBuckets);
        // Accumulate charge in one bucket and one custom bucket, the rest should be zero
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_ON, 200);
        stats.updateCustomBucket(1, 60);

        // Let's try parcelling with including zeros
        final Parcel includeZerosParcel = Parcel.obtain();
        MeasuredEnergyStats.writeSummaryToParcel(stats, includeZerosParcel, false);
        includeZerosParcel.setDataPosition(0);

        MeasuredEnergyStats newStats = MeasuredEnergyStats.createAndReadSummaryFromParcel(
                includeZerosParcel);

        for (int i = 0; i < NUMBER_STANDARD_POWER_BUCKETS; i++) {
            if (i == POWER_BUCKET_SCREEN_ON) {
                assertEquals(stats.isStandardBucketSupported(i),
                        newStats.isStandardBucketSupported(i));
                assertEquals(stats.getAccumulatedStandardBucketCharge(i),
                        newStats.getAccumulatedStandardBucketCharge(i));
            } else {
                assertTrue(newStats.isStandardBucketSupported(i));
                assertEquals(0L, newStats.getAccumulatedStandardBucketCharge(i));
            }
        }
        assertEquals(0L, newStats.getAccumulatedCustomBucketCharge(0));
        assertEquals(stats.getAccumulatedCustomBucketCharge(1),
                newStats.getAccumulatedCustomBucketCharge(1));
        includeZerosParcel.recycle();

        // Now let's try parcelling with skipping zeros
        final Parcel skipZerosParcel = Parcel.obtain();
        MeasuredEnergyStats.writeSummaryToParcel(stats, skipZerosParcel, true);
        skipZerosParcel.setDataPosition(0);

        newStats = MeasuredEnergyStats.createAndReadSummaryFromParcel(skipZerosParcel);

        for (int i = 0; i < NUMBER_STANDARD_POWER_BUCKETS; i++) {
            if (i == POWER_BUCKET_SCREEN_ON) {
                assertEquals(stats.isStandardBucketSupported(i),
                        newStats.isStandardBucketSupported(i));
                assertEquals(stats.getAccumulatedStandardBucketCharge(i),
                        newStats.getAccumulatedStandardBucketCharge(i));
            } else {
                assertFalse(newStats.isStandardBucketSupported(i));
                assertEquals(POWER_DATA_UNAVAILABLE,
                        newStats.getAccumulatedStandardBucketCharge(i));
            }
        }
        assertEquals(0L, newStats.getAccumulatedCustomBucketCharge(0));
        assertEquals(stats.getAccumulatedCustomBucketCharge(1),
                newStats.getAccumulatedCustomBucketCharge(1));
        skipZerosParcel.recycle();
    }

    @Test
    public void testCreateAndReadSummaryFromParcel_nullTemplate() {
        final boolean[] supportedStandardBuckets = new boolean[NUMBER_STANDARD_POWER_BUCKETS];
        final int numCustomBuckets = 2;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_ON] = true;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_DOZE] = false;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_OTHER] = true;

        final MeasuredEnergyStats stats =
                new MeasuredEnergyStats(supportedStandardBuckets, numCustomBuckets);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_ON, 10);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_ON, 5);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_OTHER, 40);
        stats.updateCustomBucket(0, 50);
        stats.updateCustomBucket(1, 60);

        final Parcel parcel = Parcel.obtain();
        MeasuredEnergyStats.writeSummaryToParcel(stats, parcel, false);
        parcel.setDataPosition(0);

        MeasuredEnergyStats newStats =
                MeasuredEnergyStats.createAndReadSummaryFromParcel(parcel, null);
        assertNull(newStats);
        parcel.recycle();
    }

    @Test
    public void testCreateAndReadSummaryFromParcel_boring() {
        final boolean[] supportedStandardBuckets = new boolean[NUMBER_STANDARD_POWER_BUCKETS];
        final int numCustomBuckets = 2;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_ON] = true;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_DOZE] = false;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_OTHER] = true;

        final MeasuredEnergyStats template =
                new MeasuredEnergyStats(supportedStandardBuckets, numCustomBuckets);
        template.updateStandardBucket(POWER_BUCKET_SCREEN_ON, 10);
        template.updateStandardBucket(POWER_BUCKET_SCREEN_ON, 5);
        template.updateStandardBucket(POWER_BUCKET_SCREEN_OTHER, 40);
        template.updateCustomBucket(0, 50);

        final MeasuredEnergyStats stats = MeasuredEnergyStats.createFromTemplate(template);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_ON, 0L);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_OTHER, 7L);

        final Parcel parcel = Parcel.obtain();
        MeasuredEnergyStats.writeSummaryToParcel(stats, parcel, false);

        final boolean[] newSupportedStandardBuckets = new boolean[NUMBER_STANDARD_POWER_BUCKETS];
        newSupportedStandardBuckets[POWER_BUCKET_SCREEN_ON] = true;
        newSupportedStandardBuckets[POWER_BUCKET_SCREEN_DOZE] = true; // switched false > true
        newSupportedStandardBuckets[POWER_BUCKET_SCREEN_OTHER] = false; // switched true > false
        final MeasuredEnergyStats newTemplate =
                new MeasuredEnergyStats(newSupportedStandardBuckets, numCustomBuckets);
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
        final boolean[] supportedStandardBuckets = new boolean[NUMBER_STANDARD_POWER_BUCKETS];
        final int numCustomBuckets = 2;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_ON] = true;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_DOZE] = false;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_OTHER] = true;

        final MeasuredEnergyStats stats =
                new MeasuredEnergyStats(supportedStandardBuckets, numCustomBuckets);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_ON, 10);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_DOZE, 30);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_OTHER, 40);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_ON, 5);

        stats.updateCustomBucket(0, 50);
        stats.updateCustomBucket(1, 60);
        stats.updateCustomBucket(0, 3);

        assertEquals(15, stats.getAccumulatedStandardBucketCharge(POWER_BUCKET_SCREEN_ON));
        assertEquals(POWER_DATA_UNAVAILABLE,
                stats.getAccumulatedStandardBucketCharge(POWER_BUCKET_SCREEN_DOZE));
        assertEquals(40, stats.getAccumulatedStandardBucketCharge(POWER_BUCKET_SCREEN_OTHER));
        assertEquals(50 + 3, stats.getAccumulatedCustomBucketCharge(0));
        assertEquals(60, stats.getAccumulatedCustomBucketCharge(1));
    }

    @Test
    public void testIsValidCustomBucket() {
        final MeasuredEnergyStats stats =
                new MeasuredEnergyStats(new boolean[NUMBER_STANDARD_POWER_BUCKETS], 3);
        assertFalse(stats.isValidCustomBucket(-1));
        assertTrue(stats.isValidCustomBucket(0));
        assertTrue(stats.isValidCustomBucket(1));
        assertTrue(stats.isValidCustomBucket(2));
        assertFalse(stats.isValidCustomBucket(3));
        assertFalse(stats.isValidCustomBucket(4));

        final MeasuredEnergyStats boringStats =
                new MeasuredEnergyStats(new boolean[NUMBER_STANDARD_POWER_BUCKETS], 0);
        assertFalse(boringStats.isValidCustomBucket(-1));
        assertFalse(boringStats.isValidCustomBucket(0));
        assertFalse(boringStats.isValidCustomBucket(1));
    }

    @Test
    public void testGetAccumulatedCustomBucketCharges() {
        final MeasuredEnergyStats stats =
                new MeasuredEnergyStats(new boolean[NUMBER_STANDARD_POWER_BUCKETS], 3);

        stats.updateCustomBucket(0, 50);
        stats.updateCustomBucket(1, 60);
        stats.updateCustomBucket(2, 13);
        stats.updateCustomBucket(1, 70);

        final long[] output = stats.getAccumulatedCustomBucketCharges();
        assertEquals(3, output.length);

        assertEquals(50, output[0]);
        assertEquals(60 + 70, output[1]);
        assertEquals(13, output[2]);
    }

    @Test
    public void testGetAccumulatedCustomBucketCharges_empty() {
        final MeasuredEnergyStats stats =
                new MeasuredEnergyStats(new boolean[NUMBER_STANDARD_POWER_BUCKETS], 0);

        final long[] output = stats.getAccumulatedCustomBucketCharges();
        assertEquals(0, output.length);
    }

    @Test
    public void testGetNumberCustomChargeBuckets() {
        assertEquals(0, new MeasuredEnergyStats(new boolean[NUMBER_STANDARD_POWER_BUCKETS], 0)
                .getNumberCustomPowerBuckets());
        assertEquals(3, new MeasuredEnergyStats(new boolean[NUMBER_STANDARD_POWER_BUCKETS], 3)
                .getNumberCustomPowerBuckets());
    }

    @Test
    public void testReset() {
        final boolean[] supportedStandardBuckets = new boolean[NUMBER_STANDARD_POWER_BUCKETS];
        final int numCustomBuckets = 2;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_ON] = true;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_DOZE] = false;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_OTHER] = true;

        final MeasuredEnergyStats stats =
                new MeasuredEnergyStats(supportedStandardBuckets, numCustomBuckets);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_ON, 10);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_ON, 5);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_OTHER, 40);
        stats.updateCustomBucket(0, 50);
        stats.updateCustomBucket(1, 60);

        MeasuredEnergyStats.resetIfNotNull(stats);
        // All charges should be reset to 0
        for (int i = 0; i < NUMBER_STANDARD_POWER_BUCKETS; i++) {
            if (supportedStandardBuckets[i]) {
                assertTrue(stats.isStandardBucketSupported(i));
                assertEquals(0, stats.getAccumulatedStandardBucketCharge(i));
            } else {
                assertFalse(stats.isStandardBucketSupported(i));
                assertEquals(POWER_DATA_UNAVAILABLE, stats.getAccumulatedStandardBucketCharge(i));
            }
        }
        for (int i = 0; i < numCustomBuckets; i++) {
            assertEquals(0, stats.getAccumulatedCustomBucketCharge(i));
        }

        // Values should increase as usual.
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_ON, 70);
        assertEquals(70L, stats.getAccumulatedStandardBucketCharge(POWER_BUCKET_SCREEN_ON));

        stats.updateCustomBucket(1, 12);
        assertEquals(12L, stats.getAccumulatedCustomBucketCharge(1));
    }

    /** Test that states are mapped to the expected power buckets. Beware of mapping changes. */
    @Test
    public void testStandardBucketMapping() {
        int exp;

        exp = POWER_BUCKET_SCREEN_ON;
        assertEquals(exp, MeasuredEnergyStats.getDisplayPowerBucket(Display.STATE_ON));
        assertEquals(exp, MeasuredEnergyStats.getDisplayPowerBucket(Display.STATE_VR));
        assertEquals(exp, MeasuredEnergyStats.getDisplayPowerBucket(Display.STATE_ON_SUSPEND));

        exp = POWER_BUCKET_SCREEN_DOZE;
        assertEquals(exp, MeasuredEnergyStats.getDisplayPowerBucket(Display.STATE_DOZE));
        assertEquals(exp, MeasuredEnergyStats.getDisplayPowerBucket(Display.STATE_DOZE_SUSPEND));
    }
}
