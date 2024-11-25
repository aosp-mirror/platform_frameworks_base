/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.location.geometry;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.internal.location.geometry.S2CellIdUtils;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class S2CellIdUtilsTest {

    // S2 cell ID of a level-30 cell in Times Square.
    private static final long TIMES_SQUARE_S2_ID =
            S2CellIdUtils.fromLatLngDegrees(40.758896, -73.985130);

    // Position of the Eiffel tower (outside of any parent cell from Times Square).
    private static final double[] EIFFEL_TOWER_LATLNG = {48.858093, 2.294694};

    // Test vector around TIMES_SQUARE_S2_ID: Cell IDs and the centers for levels 0 to 30.
    // This test vector has been computed using the public S2 library in
    // external/s2-geometry-library-java
    private static final CellAndCenter[] TIMES_SQUARE_CELLS = {
            new CellAndCenter("9", -0.0, -90.0),
            new CellAndCenter("8c", 21.037511025421814, -67.38013505195958),
            new CellAndCenter("89", 34.04786296943431, -79.38034472384487),
            new CellAndCenter("89c", 38.79459515585768, -73.46516214265485),
            new CellAndCenter("89d", 41.74704688465104, -76.45630866778862),
            new CellAndCenter("89c4", 40.29416073145462, -74.96763653470293),
            new CellAndCenter("89c3", 40.827706513259564, -74.21793256064282),
            new CellAndCenter("89c24", 40.45771021423038, -73.84190634077625),
            new CellAndCenter("89c25", 40.64307662867646, -74.03001224983848),
            new CellAndCenter("89c25c", 40.708880489804564, -73.93598211433742),
            new CellAndCenter("89c259", 40.75509755935301, -73.9830029344863),
            new CellAndCenter("89c2584", 40.7781887758716, -74.00650903621303),
            new CellAndCenter("89c2585", 40.766644611813284, -73.99475634561863),
            new CellAndCenter("89c25854", 40.76087144655763, -73.98887973002674),
            new CellAndCenter("89c25855", 40.75798459318946, -73.98594135473846),
            new CellAndCenter("89c25855c", 40.75901097797799, -73.98447215023141),
            new CellAndCenter("89c25855b", 40.758497791893824, -73.98520675388987),
            new CellAndCenter("89c25855bc", 40.75875438651343, -73.98483945241185),
            new CellAndCenter("89c25855b9", 40.758934819692875, -73.98502310323867),
            new CellAndCenter("89c25855b9c", 40.75887067137071, -73.98511492858621),
            new CellAndCenter("89c25855b9d", 40.75891577956465, -73.98516084124353),
            new CellAndCenter("89c25855b9c4", 40.758893225473194, -73.98513788491626),
            new CellAndCenter("89c25855b9c7", 40.75890124402366, -73.98512640675159),
            new CellAndCenter("89c25855b9c6c", 40.758897234748815, -73.985132145834),
            new CellAndCenter("89c25855b9c6d", 40.75889441548664, -73.98512927629281),
            new CellAndCenter("89c25855b9c6c4", 40.75889582511775, -73.98513071106342),
            new CellAndCenter("89c25855b9c6c3", 40.758896326277146, -73.98512999367811),
            new CellAndCenter("89c25855b9c6c3c", 40.75889607569745, -73.98513035237076),
            new CellAndCenter("89c25855b9c6c39", 40.75889589949357, -73.98513017302443),
            new CellAndCenter("89c25855b9c6c39c", 40.75889596213849, -73.98513008335128),
            new CellAndCenter("89c25855b9c6c39f", 40.75889599346095, -73.9851300385147)};

    @Test
    public void toLatLngDegrees_matchesTestVector() {
        for (int level = 0; level <= 30; level++) {
            double[] expected = TIMES_SQUARE_CELLS[level].mCenter;
            long cellId = S2CellIdUtils.getParent(TIMES_SQUARE_S2_ID, level);

            double[] centerPoint = {0.0, 0.0};
            S2CellIdUtils.toLatLngDegrees(cellId, centerPoint);

            assertThat(approxEquals(centerPoint[0], expected[0])).isTrue();
            assertThat(approxEquals(centerPoint[1], expected[1])).isTrue();
        }
    }

    private static boolean approxEquals(double a, double b) {
        return Math.abs(a - b) <= 1e-14;
    }

    @Test
    public void containsLatLngDegrees_eachCellContainsItsCenter_works() {
        for (int level = 0; level <= 30; level++) {
            long cellId = TIMES_SQUARE_CELLS[level].toCellId();
            double[] center = TIMES_SQUARE_CELLS[level].mCenter;

            boolean isContained = S2CellIdUtils.containsLatLngDegrees(cellId, center[0], center[1]);

            assertThat(isContained).isTrue();
        }
    }

    @Test
    public void containsLatLngDegrees_testWithOutsidePoint() {
        for (int level = 0; level <= 30; level++) {
            long cellId = TIMES_SQUARE_CELLS[level].toCellId();

            assertThat(S2CellIdUtils.containsLatLngDegrees(cellId, EIFFEL_TOWER_LATLNG[0],
                  EIFFEL_TOWER_LATLNG[1])).isFalse();
        }
    }

    // A tuple with a S2 cell id, and a S2LatLng representing its center.
    private static class CellAndCenter {
        public String mToken;
        public double[] mCenter;

        CellAndCenter(String token, double latDegrees, double lngDegrees) {
            this.mToken = token;
            this.mCenter = new double[] {latDegrees, lngDegrees};
        }

        // Converts from hex representation to long format.
        long toCellId() {
            long value = 0;
            for (int pos = 0; pos < mToken.length(); pos++) {
                int digitValue = Character.digit(mToken.charAt(pos), 16);
                if (digitValue == -1) {
                    return -1;
                }
                value = value * 16 + digitValue;
            }
            value = value << (4 * (16 - mToken.length()));  // remove implicit zeros
            return value;
        }
    }
}
