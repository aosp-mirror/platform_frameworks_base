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

package com.android.server.location;

import static androidx.test.ext.truth.location.LocationSubject.assertThat;

import static com.android.server.location.LocationUtils.createLocation;

import static com.google.common.truth.Truth.assertThat;

import android.location.Location;
import android.platform.test.annotations.Presubmit;
import android.util.Log;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
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
            (float) Math.sqrt(2 * ACCURACY_M * ACCURACY_M);

    private Random mRandom;

    private LocationFudger mFudger;

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
            assertThat(coarse).isNotSameAs(fine);
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
        // validity, but it serves as a sanity test as least.
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
}
