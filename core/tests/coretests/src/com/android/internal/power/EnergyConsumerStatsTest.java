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

import static com.android.internal.power.EnergyConsumerStats.NUMBER_STANDARD_POWER_BUCKETS;
import static com.android.internal.power.EnergyConsumerStats.POWER_BUCKET_BLUETOOTH;
import static com.android.internal.power.EnergyConsumerStats.POWER_BUCKET_CPU;
import static com.android.internal.power.EnergyConsumerStats.POWER_BUCKET_SCREEN_DOZE;
import static com.android.internal.power.EnergyConsumerStats.POWER_BUCKET_SCREEN_ON;
import static com.android.internal.power.EnergyConsumerStats.POWER_BUCKET_SCREEN_OTHER;
import static com.android.internal.power.EnergyConsumerStats.POWER_BUCKET_WIFI;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Parcel;
import android.platform.test.ravenwood.RavenwoodRule;
import android.view.Display;

import androidx.test.filters.SmallTest;

import org.junit.Rule;
import org.junit.Test;

import java.util.Arrays;

/**
 * Test class for {@link EnergyConsumerStats}.
 */
@SmallTest
public class EnergyConsumerStatsTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    @Test
    public void testConstruction() {
        final boolean[] supportedStandardBuckets = new boolean[NUMBER_STANDARD_POWER_BUCKETS];
        final String[] customBucketNames = {"A", "B"};
        supportedStandardBuckets[POWER_BUCKET_SCREEN_ON] = true;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_DOZE] = false;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_OTHER] = true;

        final EnergyConsumerStats.Config config =
                new EnergyConsumerStats.Config(supportedStandardBuckets, customBucketNames,
                        new int[]{POWER_BUCKET_SCREEN_ON, POWER_BUCKET_WIFI},
                        new String[]{"state0", "state1", "state3"});
        final EnergyConsumerStats stats = new EnergyConsumerStats(config);

        for (int bucket = 0; bucket < NUMBER_STANDARD_POWER_BUCKETS; bucket++) {
            if (supportedStandardBuckets[bucket]) {
                assertTrue(stats.isStandardBucketSupported(bucket));
                assertEquals(0L, stats.getAccumulatedStandardBucketCharge(bucket));
            } else {
                assertFalse(stats.isStandardBucketSupported(bucket));
                assertEquals(POWER_DATA_UNAVAILABLE,
                        stats.getAccumulatedStandardBucketCharge(bucket));
            }
            if (bucket == POWER_BUCKET_SCREEN_ON) {
                assertThat(config.isSupportedMultiStateBucket(bucket)).isTrue();
            } else {
                assertThat(config.isSupportedMultiStateBucket(bucket)).isFalse();
            }
        }
        for (int i = 0; i < customBucketNames.length; i++) {
            assertEquals(0L, stats.getAccumulatedCustomBucketCharge(i));
        }
        assertThat(config.getCustomBucketNames()).asList().containsExactly("A", "B");
        assertThat(config.getStateNames()).asList().containsExactly("state0", "state1", "state3");
    }

    @Test
    public void testReadWriteParcel() {
        final boolean[] supportedStandardBuckets = new boolean[NUMBER_STANDARD_POWER_BUCKETS];
        final String[] customBucketNames = {"A", "B"};
        supportedStandardBuckets[POWER_BUCKET_SCREEN_ON] = true;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_DOZE] = false;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_OTHER] = true;

        final EnergyConsumerStats.Config config =
                new EnergyConsumerStats.Config(supportedStandardBuckets, customBucketNames,
                        new int[]{POWER_BUCKET_SCREEN_ON}, new String[]{"s0", "s1"});
        final EnergyConsumerStats stats = new EnergyConsumerStats(config);

        stats.setState(0, 1000);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_ON, 10, 2000);
        stats.setState(1, 3000);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_ON, 5, 4000);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_OTHER, 40, 5000);
        stats.updateCustomBucket(0, 50, 6000);
        stats.updateCustomBucket(1, 60, 7000);

        final Parcel parcel = Parcel.obtain();
        stats.writeToParcel(parcel);

        parcel.setDataPosition(0);
        EnergyConsumerStats newStats = new EnergyConsumerStats(config, parcel);

        for (int bucket = 0; bucket < NUMBER_STANDARD_POWER_BUCKETS; bucket++) {
            assertEquals(stats.getAccumulatedStandardBucketCharge(bucket),
                    newStats.getAccumulatedStandardBucketCharge(bucket));
            for (int state = 0; state < 2; state++) {
                assertEquals(stats.getAccumulatedStandardBucketCharge(bucket, state),
                        newStats.getAccumulatedStandardBucketCharge(bucket, state));
            }
        }
        for (int i = 0; i < customBucketNames.length; i++) {
            assertEquals(stats.getAccumulatedCustomBucketCharge(i),
                    newStats.getAccumulatedCustomBucketCharge(i));
        }
        assertEquals(POWER_DATA_UNAVAILABLE,
                newStats.getAccumulatedCustomBucketCharge(customBucketNames.length + 1));
        parcel.recycle();
    }

    @Test
    public void testCreateAndReadConfigFromParcel() {
        final boolean[] supportedStandardBuckets = new boolean[NUMBER_STANDARD_POWER_BUCKETS];
        final String[] customBucketNames = {"A", "B"};
        supportedStandardBuckets[POWER_BUCKET_SCREEN_ON] = true;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_DOZE] = false;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_OTHER] = true;

        final EnergyConsumerStats.Config config =
                new EnergyConsumerStats.Config(supportedStandardBuckets, customBucketNames,
                        new int[]{POWER_BUCKET_SCREEN_ON, POWER_BUCKET_WIFI},
                        new String[] {"state0", "state1", "state2"});

        final Parcel parcel = Parcel.obtain();
        EnergyConsumerStats.Config.writeToParcel(config, parcel);

        parcel.setDataPosition(0);

        final EnergyConsumerStats.Config newConfig = EnergyConsumerStats.Config.createFromParcel(
                parcel);

        assertThat(newConfig).isNotNull();
        for (int bucket = 0; bucket < NUMBER_STANDARD_POWER_BUCKETS; bucket++) {
            if (bucket == POWER_BUCKET_SCREEN_ON || bucket == POWER_BUCKET_SCREEN_OTHER) {
                assertThat(newConfig.isSupportedBucket(bucket)).isTrue();
            } else {
                assertThat(newConfig.isSupportedBucket(bucket)).isFalse();
            }
            if (bucket == POWER_BUCKET_SCREEN_ON) {
                assertThat(newConfig.isSupportedMultiStateBucket(bucket)).isTrue();
            } else {
                assertThat(newConfig.isSupportedMultiStateBucket(bucket)).isFalse();
            }
        }
        assertThat(newConfig.getCustomBucketNames()).isEqualTo(new String[]{"A", "B"});
        assertThat(newConfig.getStateNames()).isEqualTo(new String[]{"state0", "state1", "state2"});
    }

    @Test
    public void testCreateAndReadConfigFromParcel_nullConfig() {
        final Parcel parcel = Parcel.obtain();
        EnergyConsumerStats.Config.writeToParcel(null, parcel);

        parcel.setDataPosition(0);

        final EnergyConsumerStats.Config newConfig = EnergyConsumerStats.Config.createFromParcel(
                parcel);

        assertThat(newConfig).isNull();
    }

    @Test
    public void testCreateAndReadSummaryFromParcel() {
        final boolean[] supportedStandardBuckets = new boolean[NUMBER_STANDARD_POWER_BUCKETS];
        final String[] customBucketNames = {"A", "B"};
        supportedStandardBuckets[POWER_BUCKET_SCREEN_ON] = true;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_DOZE] = false;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_OTHER] = true;

        final EnergyConsumerStats.Config config =
                new EnergyConsumerStats.Config(supportedStandardBuckets, customBucketNames,
                        new int[0], new String[]{"s"});
        final EnergyConsumerStats stats = new EnergyConsumerStats(config);

        stats.updateStandardBucket(POWER_BUCKET_SCREEN_ON, 10);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_ON, 5);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_OTHER, 40);
        stats.updateCustomBucket(0, 50);
        stats.updateCustomBucket(1, 60);

        final Parcel parcel = Parcel.obtain();
        EnergyConsumerStats.writeSummaryToParcel(stats, parcel);

        parcel.setDataPosition(0);

        EnergyConsumerStats newStats =
                EnergyConsumerStats.createAndReadSummaryFromParcel(config, parcel);

        for (int i = 0; i < NUMBER_STANDARD_POWER_BUCKETS; i++) {
            assertEquals(stats.isStandardBucketSupported(i),
                    newStats.isStandardBucketSupported(i));
            assertEquals(stats.getAccumulatedStandardBucketCharge(i),
                    newStats.getAccumulatedStandardBucketCharge(i));
        }
        for (int i = 0; i < customBucketNames.length; i++) {
            assertEquals(stats.getAccumulatedCustomBucketCharge(i),
                    newStats.getAccumulatedCustomBucketCharge(i));
        }
        assertEquals(POWER_DATA_UNAVAILABLE,
                newStats.getAccumulatedCustomBucketCharge(customBucketNames.length + 1));
        parcel.recycle();
    }

    @Test
    public void testCreateAndReadSummaryFromParcel_configChange() {
        final boolean[] supportedStandardBuckets = new boolean[NUMBER_STANDARD_POWER_BUCKETS];
        final String[] customBucketNames = {"A", "B"};
        supportedStandardBuckets[POWER_BUCKET_SCREEN_ON] = true;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_DOZE] = false;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_OTHER] = true;

        final EnergyConsumerStats.Config config =
                new EnergyConsumerStats.Config(supportedStandardBuckets, customBucketNames,
                        new int[0], new String[]{"s"});
        final EnergyConsumerStats stats = new EnergyConsumerStats(config);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_ON, 10);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_ON, 5);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_OTHER, 40);
        stats.updateCustomBucket(0, 50);

        final Parcel parcel = Parcel.obtain();
        EnergyConsumerStats.writeSummaryToParcel(stats, parcel);
        parcel.setDataPosition(0);

        final boolean[] newSupportedStandardBuckets = new boolean[NUMBER_STANDARD_POWER_BUCKETS];
        newSupportedStandardBuckets[POWER_BUCKET_SCREEN_ON] = true;
        newSupportedStandardBuckets[POWER_BUCKET_SCREEN_DOZE] = true; // switched false > true
        newSupportedStandardBuckets[POWER_BUCKET_SCREEN_OTHER] = false; // switched true > false

        final EnergyConsumerStats.Config newConfig =
                new EnergyConsumerStats.Config(newSupportedStandardBuckets, customBucketNames,
                        new int[0], new String[]{"s"});

        final EnergyConsumerStats newStats =
                EnergyConsumerStats.createAndReadSummaryFromParcel(newConfig, parcel);

        for (int i = 0; i < NUMBER_STANDARD_POWER_BUCKETS; i++) {
            if (!newSupportedStandardBuckets[i]) {
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
        for (int i = 0; i < customBucketNames.length; i++) {
            assertEquals(stats.getAccumulatedCustomBucketCharge(i),
                    newStats.getAccumulatedCustomBucketCharge(i));
        }
        assertEquals(POWER_DATA_UNAVAILABLE,
                newStats.getAccumulatedCustomBucketCharge(customBucketNames.length + 1));
        parcel.recycle();
    }

    @Test
    public void testCreateAndReadSummaryFromParcel_nullConfig() {
        final boolean[] supportedStandardBuckets = new boolean[NUMBER_STANDARD_POWER_BUCKETS];
        final String[] customBucketNames = {"A", "B"};
        supportedStandardBuckets[POWER_BUCKET_SCREEN_ON] = true;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_DOZE] = false;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_OTHER] = true;

        final EnergyConsumerStats.Config config =
                new EnergyConsumerStats.Config(supportedStandardBuckets, customBucketNames,
                        new int[0], new String[]{"s"});
        final EnergyConsumerStats stats = new EnergyConsumerStats(config);

        stats.updateStandardBucket(POWER_BUCKET_SCREEN_ON, 10);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_ON, 5);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_OTHER, 40);
        stats.updateCustomBucket(0, 50);
        stats.updateCustomBucket(1, 60);

        final Parcel parcel = Parcel.obtain();
        EnergyConsumerStats.writeSummaryToParcel(stats, parcel);
        parcel.setDataPosition(0);

        EnergyConsumerStats newStats =
                EnergyConsumerStats.createAndReadSummaryFromParcel(null, parcel);
        assertNull(newStats);
        parcel.recycle();
    }

    @Test
    public void testCreateAndReadSummaryFromParcel_boring() {
        final boolean[] supportedStandardBuckets = new boolean[NUMBER_STANDARD_POWER_BUCKETS];
        final String[] customBucketNames = {"A", "B"};
        supportedStandardBuckets[POWER_BUCKET_SCREEN_ON] = true;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_DOZE] = false;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_OTHER] = true;

        final EnergyConsumerStats.Config config =
                new EnergyConsumerStats.Config(supportedStandardBuckets, customBucketNames,
                        new int[0], new String[]{"s"});
        final EnergyConsumerStats stats = new EnergyConsumerStats(config);

        stats.updateStandardBucket(POWER_BUCKET_SCREEN_ON, 0);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_OTHER, 40);

        final Parcel parcel = Parcel.obtain();
        EnergyConsumerStats.writeSummaryToParcel(stats, parcel);

        final boolean[] newSupportedStandardBuckets = new boolean[NUMBER_STANDARD_POWER_BUCKETS];
        newSupportedStandardBuckets[POWER_BUCKET_SCREEN_ON] = true;
        newSupportedStandardBuckets[POWER_BUCKET_SCREEN_DOZE] = true; // switched false > true
        newSupportedStandardBuckets[POWER_BUCKET_SCREEN_OTHER] = false; // switched true > false
        final EnergyConsumerStats.Config newConfig =
                new EnergyConsumerStats.Config(newSupportedStandardBuckets, customBucketNames,
                        new int[0], new String[]{"s"});

        parcel.setDataPosition(0);

        final EnergyConsumerStats newStats =
                EnergyConsumerStats.createAndReadSummaryFromParcel(newConfig, parcel);

        // The only non-0 entry in stats is no longer supported, so now there's no interesting data.
        assertNull(newStats);
        assertEquals("Parcel was not properly consumed", 0, parcel.dataAvail());
        parcel.recycle();
    }

    @Test
    public void testUpdateBucket() {
        final boolean[] supportedStandardBuckets = new boolean[NUMBER_STANDARD_POWER_BUCKETS];
        final String[] customBucketNames = {"A", "B"};
        supportedStandardBuckets[POWER_BUCKET_SCREEN_ON] = true;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_DOZE] = false;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_OTHER] = true;

        final EnergyConsumerStats.Config config =
                new EnergyConsumerStats.Config(supportedStandardBuckets, customBucketNames,
                        new int[]{POWER_BUCKET_SCREEN_ON, POWER_BUCKET_SCREEN_OTHER},
                        new String[]{"s0", "s1"});
        final EnergyConsumerStats stats = new EnergyConsumerStats(config);

        stats.setState(0, 1000);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_ON, 10, 2000);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_DOZE, 30, 3000);
        stats.setState(1,  4000);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_OTHER, 40, 5000);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_ON, 6, 6000);

        stats.updateCustomBucket(0, 50, 7000);
        stats.updateCustomBucket(1, 60, 8000);
        stats.updateCustomBucket(0, 3, 9000);

        assertEquals(16, stats.getAccumulatedStandardBucketCharge(POWER_BUCKET_SCREEN_ON));
        assertEquals(POWER_DATA_UNAVAILABLE,
                stats.getAccumulatedStandardBucketCharge(POWER_BUCKET_SCREEN_DOZE));
        assertEquals(40, stats.getAccumulatedStandardBucketCharge(POWER_BUCKET_SCREEN_OTHER));
        assertEquals(50 + 3, stats.getAccumulatedCustomBucketCharge(0));
        assertEquals(60, stats.getAccumulatedCustomBucketCharge(1));

        // 10 + 6 * (4000-2000)/(6000-2000)
        assertEquals(13, stats.getAccumulatedStandardBucketCharge(POWER_BUCKET_SCREEN_ON, 0));
        // 6 * (6000-4000)/(6000-2000)
        assertEquals(3, stats.getAccumulatedStandardBucketCharge(POWER_BUCKET_SCREEN_ON, 1));
        // 40 * (4000-1000)/(5000-1000)
        assertEquals(30, stats.getAccumulatedStandardBucketCharge(POWER_BUCKET_SCREEN_OTHER, 0));
        // 40 * (5000-4000)/(5000-1000)
        assertEquals(10, stats.getAccumulatedStandardBucketCharge(POWER_BUCKET_SCREEN_OTHER, 1));
    }

    @Test
    public void testIsValidCustomBucket() {
        final EnergyConsumerStats.Config config =
                new EnergyConsumerStats.Config(new boolean[NUMBER_STANDARD_POWER_BUCKETS],
                        new String[]{"A", "B", "C"},
                        new int[0], new String[]{"s"});
        final EnergyConsumerStats stats = new EnergyConsumerStats(config);
        assertFalse(stats.isValidCustomBucket(-1));
        assertTrue(stats.isValidCustomBucket(0));
        assertTrue(stats.isValidCustomBucket(1));
        assertTrue(stats.isValidCustomBucket(2));
        assertFalse(stats.isValidCustomBucket(3));
        assertFalse(stats.isValidCustomBucket(4));

        final EnergyConsumerStats.Config boringConfig =
                new EnergyConsumerStats.Config(new boolean[NUMBER_STANDARD_POWER_BUCKETS],
                        new String[0], new int[0], new String[]{"s"});
        final EnergyConsumerStats boringStats = new EnergyConsumerStats(boringConfig);
        assertFalse(boringStats.isValidCustomBucket(-1));
        assertFalse(boringStats.isValidCustomBucket(0));
        assertFalse(boringStats.isValidCustomBucket(1));
    }

    @Test
    public void testGetAccumulatedCustomBucketCharges() {
        final EnergyConsumerStats.Config config =
                new EnergyConsumerStats.Config(new boolean[NUMBER_STANDARD_POWER_BUCKETS],
                        new String[]{"A", "B", "C"},
                        new int[0], new String[]{"s"});
        final EnergyConsumerStats stats = new EnergyConsumerStats(config);
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
        final EnergyConsumerStats.Config config =
                new EnergyConsumerStats.Config(new boolean[NUMBER_STANDARD_POWER_BUCKETS],
                        new String[0], new int[0], new String[]{"s"});
        final EnergyConsumerStats stats = new EnergyConsumerStats(config);

        final long[] output = stats.getAccumulatedCustomBucketCharges();
        assertEquals(0, output.length);
    }

    @Test
    public void testGetNumberCustomChargeBuckets() {
        assertEquals(0,
                new EnergyConsumerStats(
                        new EnergyConsumerStats.Config(new boolean[NUMBER_STANDARD_POWER_BUCKETS],
                                new String[0], new int[0], new String[]{"s"}))
                        .getNumberCustomPowerBuckets());
        assertEquals(3,
                new EnergyConsumerStats(
                        new EnergyConsumerStats.Config(new boolean[NUMBER_STANDARD_POWER_BUCKETS],
                                new String[]{"A", "B", "C"}, new int[0], new String[]{"s"}))
                        .getNumberCustomPowerBuckets());
    }

    @Test
    public void testReset() {
        final boolean[] supportedStandardBuckets = new boolean[NUMBER_STANDARD_POWER_BUCKETS];
        final String[] customBucketNames = {"A", "B"};
        supportedStandardBuckets[POWER_BUCKET_SCREEN_ON] = true;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_DOZE] = false;
        supportedStandardBuckets[POWER_BUCKET_SCREEN_OTHER] = true;

        final int[] supportedMultiStateBuckets = new int[]{POWER_BUCKET_SCREEN_ON};
        final EnergyConsumerStats.Config config =
                new EnergyConsumerStats.Config(supportedStandardBuckets, customBucketNames,
                        supportedMultiStateBuckets, new String[]{"s1", "s2"});
        final EnergyConsumerStats stats = new EnergyConsumerStats(config);
        stats.setState(1, 0);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_ON, 10, 1000);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_ON, 5, 2000);
        stats.updateStandardBucket(POWER_BUCKET_SCREEN_OTHER, 40);
        stats.updateCustomBucket(0, 50);
        stats.updateCustomBucket(1, 60);

        assertThat(stats.getAccumulatedStandardBucketCharge(POWER_BUCKET_SCREEN_ON, 0))
                .isEqualTo(0);
        assertThat(stats.getAccumulatedStandardBucketCharge(POWER_BUCKET_SCREEN_ON, 1))
                .isEqualTo(15);

        EnergyConsumerStats.resetIfNotNull(stats);
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
        assertThat(stats.getAccumulatedStandardBucketCharge(POWER_BUCKET_SCREEN_ON, 0))
                .isEqualTo(0);
        assertThat(stats.getAccumulatedStandardBucketCharge(POWER_BUCKET_SCREEN_ON, 1))
                .isEqualTo(0);

        for (int i = 0; i < customBucketNames.length; i++) {
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
        assertEquals(exp, EnergyConsumerStats.getDisplayPowerBucket(Display.STATE_ON));
        assertEquals(exp, EnergyConsumerStats.getDisplayPowerBucket(Display.STATE_VR));
        assertEquals(exp, EnergyConsumerStats.getDisplayPowerBucket(Display.STATE_ON_SUSPEND));

        exp = POWER_BUCKET_SCREEN_DOZE;
        assertEquals(exp, EnergyConsumerStats.getDisplayPowerBucket(Display.STATE_DOZE));
        assertEquals(exp, EnergyConsumerStats.getDisplayPowerBucket(Display.STATE_DOZE_SUSPEND));
    }

    @Test
    public void testConfig_isCompatible() {
        final boolean[] supportedStandardBuckets = new boolean[NUMBER_STANDARD_POWER_BUCKETS];
        Arrays.fill(supportedStandardBuckets, true);
        final String[] customBucketNames = {"A", "B"};
        final int[] supportedMultiStateBuckets = {POWER_BUCKET_CPU, POWER_BUCKET_WIFI};
        final String[] stateNames = {"s"};

        final EnergyConsumerStats.Config config = new EnergyConsumerStats.Config(
                supportedStandardBuckets,
                customBucketNames,
                supportedMultiStateBuckets,
                stateNames);
        assertTrue(
                "All standard and custom bucket supports match",
                config.isCompatible(
                        new EnergyConsumerStats.Config(
                                supportedStandardBuckets,
                                customBucketNames,
                                supportedMultiStateBuckets,
                                stateNames)));

        boolean[] differentSupportedStandardBuckets = supportedStandardBuckets.clone();
        differentSupportedStandardBuckets[0] = !differentSupportedStandardBuckets[0];

        assertFalse(
                "Standard bucket support mismatch",
                config.isCompatible(
                        new EnergyConsumerStats.Config(
                                differentSupportedStandardBuckets,
                                customBucketNames,
                                supportedMultiStateBuckets,
                                stateNames)));
        assertFalse(
                "Custom bucket support mismatch",
                config.isCompatible(
                        new EnergyConsumerStats.Config(
                                supportedStandardBuckets,
                                new String[]{"C", "B"},
                                supportedMultiStateBuckets,
                                stateNames)));
        assertFalse(
                "Multi-state bucket mismatch",
                config.isCompatible(
                        new EnergyConsumerStats.Config(
                                supportedStandardBuckets,
                                new String[]{"A"},
                                new int[] {POWER_BUCKET_CPU, POWER_BUCKET_BLUETOOTH},
                                stateNames)));
        assertFalse(
                "Multi-state bucket state list mismatch",
                config.isCompatible(
                        new EnergyConsumerStats.Config(
                                supportedStandardBuckets,
                                new String[]{"A"},
                                supportedMultiStateBuckets,
                                new String[]{"s1", "s2"})));
    }
}
