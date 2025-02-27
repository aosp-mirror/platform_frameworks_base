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

package com.android.server.location.fudger;

import static androidx.test.ext.truth.location.LocationSubject.assertThat;

import static com.android.server.location.LocationUtils.createLocation;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.location.Location;
import android.location.flags.Flags;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;
import android.util.Log;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Random;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class LocationFudgerTest {

    private static final String TAG = "LocationFudgerTest";

    private static final double APPROXIMATE_METERS_PER_DEGREE_AT_EQUATOR = 111_000;
    private static final float ACCURACY_M = 2000;
    private static final float MAX_COARSE_FUDGE_DISTANCE_M =
            (float) Math.sqrt(2 * ACCURACY_M * ACCURACY_M) + ACCURACY_M / 4f;

    private Random mRandom;

    private LocationFudger mFudger;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Before
    public void setUp() {
        long seed = System.currentTimeMillis();
        Log.i(TAG, "location random seed: " + seed);

        mRandom = new Random(seed);
        mFudger = new LocationFudger(
                ACCURACY_M,
                Clock.fixed(Instant.ofEpochMilli(0), ZoneId.systemDefault()),
                mRandom);
    }

    @Test
    public void testCoarsen() {
        // test that the coarsened location is not the same as the fine location and no leaks
        for (int i = 0; i < 100; i++) {
            Location fine = createLocation("test", mRandom);
            fine.setBearing(1);
            fine.setSpeed(1);
            fine.setAltitude(1);

            Location coarse = mFudger.createCoarse(fine);

            assertThat(coarse).isNotNull();
            assertThat(coarse).isNotSameInstanceAs(fine);
            assertThat(coarse.hasBearing()).isFalse();
            assertThat(coarse.hasSpeed()).isFalse();
            assertThat(coarse.hasAltitude()).isFalse();
            assertThat(coarse.getAccuracy()).isEqualTo(ACCURACY_M);
            assertThat(coarse.distanceTo(fine)).isGreaterThan(1F);
            assertThat(coarse).isNearby(fine, MAX_COARSE_FUDGE_DISTANCE_M);
        }
    }

    @Test
    public void testCoarsen_Consistent() {
        // test that coarsening the same location will always return the same coarse location
        // (and thus that averaging to eliminate random noise won't work)
        for (int i = 0; i < 100; i++) {
            Location fine = createLocation("test", mRandom);
            Location coarse = mFudger.createCoarse(fine);
            assertThat(mFudger.createCoarse(new Location(fine))).isEqualTo(coarse);
            assertThat(mFudger.createCoarse(new Location(fine))).isEqualTo(coarse);
        }
    }

    @Test
    public void testCoarsen_AvgMany() {
        // test that a set of locations normally distributed around the user's real location still
        // cannot be easily average to reveal the user's real location

        int passed = 0;
        int iterations = 100;
        for (int j = 0; j < iterations; j++) {
            Location fine = createLocation("test", mRandom);

            // generate a point cloud around a single location
            ArrayList<Location> finePoints = new ArrayList<>(100);
            for (int i = 0; i < 100; i++) {
                finePoints.add(step(fine, mRandom.nextGaussian() * ACCURACY_M));
            }

            // generate the coarsened version of that point cloud
            ArrayList<Location> coarsePoints = new ArrayList<>(100);
            for (int i = 0; i < 100; i++) {
                coarsePoints.add(mFudger.createCoarse(finePoints.get(i)));
            }

            double avgFineLatitude = finePoints.stream().mapToDouble(
                    Location::getLatitude).average()
                    .orElseThrow(IllegalStateException::new);
            double avgFineLongitude = finePoints.stream().mapToDouble(
                    Location::getLongitude).average()
                    .orElseThrow(IllegalStateException::new);
            Location fineAvg = createLocation("test", avgFineLatitude, avgFineLongitude, 0);

            double avgCoarseLatitude = coarsePoints.stream().mapToDouble(
                    Location::getLatitude).average()
                    .orElseThrow(IllegalStateException::new);
            double avgCoarseLongitude = coarsePoints.stream().mapToDouble(
                    Location::getLongitude).average()
                    .orElseThrow(IllegalStateException::new);
            Location coarseAvg = createLocation("test", avgCoarseLatitude, avgCoarseLongitude, 0);

            if (coarseAvg.distanceTo(fine) > fineAvg.distanceTo(fine)) {
                passed++;
            }
        }

        // very generally speaking, the closer the initial fine point is to a grid point, the more
        // accurate the coarsened average will be. we use 70% as a lower bound by -very- roughly
        // taking the area within a grid where we expect a reasonable percentage of points generated
        // by step() to fall in another grid square. this likely doesn't have much mathematical
        // validity, but it serves as a validity test as least.
        assertThat(passed / (double) iterations).isGreaterThan(.70);
    }

    // step in a random direction by distance - assume cartesian
    private Location step(Location input, double distanceM) {
        double radians = mRandom.nextDouble() * 2 * Math.PI;
        double deltaXM = Math.cos(radians) * distanceM;
        double deltaYM = Math.sin(radians) * distanceM;
        return createLocation("test",
                input.getLatitude() + deltaXM / APPROXIMATE_METERS_PER_DEGREE_AT_EQUATOR,
                input.getLongitude() + deltaYM / APPROXIMATE_METERS_PER_DEGREE_AT_EQUATOR,
                0);
    }

    @Test
    public void testDensityBasedCoarsening_ifFeatureIsDisabled_cacheIsNotUsed() {
        mSetFlagsRule.disableFlags(Flags.FLAG_DENSITY_BASED_COARSE_LOCATIONS);
        LocationFudgerCache cache = mock(LocationFudgerCache.class);

        mFudger.setLocationFudgerCache(cache);

        mFudger.createCoarse(createLocation("test", mRandom));

        verify(cache, never()).getCoarseningLevel(anyDouble(), anyDouble());
    }

    @Test
    public void testDensityBasedCoarsening_ifFeatureIsEnabledButNoDefaultValue_cacheIsNotUsed() {
        mSetFlagsRule.enableFlags(Flags.FLAG_DENSITY_BASED_COARSE_LOCATIONS);
        LocationFudgerCache cache = mock(LocationFudgerCache.class);
        doReturn(false).when(cache).hasDefaultValue();

        mFudger.setLocationFudgerCache(cache);

        mFudger.createCoarse(createLocation("test", mRandom));

        verify(cache, never()).getCoarseningLevel(anyDouble(), anyDouble());
    }

    @Test
    public void testDensityBasedCoarsening_ifFeatureIsEnabledAndDefaultIsSet_cacheIsUsed() {
        mSetFlagsRule.enableFlags(Flags.FLAG_DENSITY_BASED_COARSE_LOCATIONS);
        LocationFudgerCache cache = mock(LocationFudgerCache.class);
        doReturn(true).when(cache).hasDefaultValue();

        mFudger.setLocationFudgerCache(cache);

        Location fine = createLocation("test", mRandom);
        mFudger.createCoarse(fine);

        // We can't verify that the coordinatese of "fine" are passed to the API due to the addition
        // of the offset. We must use anyDouble().
        verify(cache).getCoarseningLevel(anyDouble(), anyDouble());
    }

    @Test
    public void testDensityBasedCoarsening_newAlgorithm_snapsToCenterOfS2Cell_testVector() {
        // NB: a complete test vector is in
        // frameworks/base/services/tests/mockingservicestests/src/com/android/server/...
        // location/geometry/S2CellIdUtilsTest.java

        mSetFlagsRule.enableFlags(Flags.FLAG_DENSITY_BASED_COARSE_LOCATIONS);
        // Arbitrary location in Times Square, NYC
        double[] latLng = new double[] {40.758896, -73.985130};
        int s2Level = 1;
        // The level-2 S2 cell around this location is "8c", its center is:
        double[] expected = { 21.037511025421814, -67.38013505195958 };

        double[] center = mFudger.snapToCenterOfS2Cell(latLng[0], latLng[1], s2Level);

        assertThat(center[0]).isEqualTo(expected[0]);
        assertThat(center[1]).isEqualTo(expected[1]);
    }
}
