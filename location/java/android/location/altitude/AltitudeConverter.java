/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.location.altitude;

import android.annotation.NonNull;
import android.annotation.WorkerThread;
import android.content.Context;
import android.location.Location;

import com.android.internal.location.altitude.GeoidHeightMap;
import com.android.internal.location.altitude.S2CellIdUtils;
import com.android.internal.location.altitude.nano.MapParamsProto;
import com.android.internal.util.Preconditions;

import java.io.IOException;

/**
 * Converts altitudes reported above the World Geodetic System 1984 (WGS84) reference ellipsoid
 * into ones above Mean Sea Level.
 *
 * <p>Reference:
 *
 * <pre>
 * Brian Julian and Michael Angermann.
 * "Resource efficient and accurate altitude conversion to Mean Sea Level."
 * To appear in 2023 IEEE/ION Position, Location and Navigation Symposium (PLANS).
 * </pre>
 */
public final class AltitudeConverter {

    private static final double MAX_ABS_VALID_LATITUDE = 90;
    private static final double MAX_ABS_VALID_LONGITUDE = 180;

    /** Manages a mapping of geoid heights associated with S2 cells. */
    private final GeoidHeightMap mGeoidHeightMap = new GeoidHeightMap();

    /**
     * Creates an instance that manages an independent cache to optimized conversions of locations
     * in proximity to one another.
     */
    public AltitudeConverter() {
    }

    /**
     * Throws an {@link IllegalArgumentException} if the {@code location} has an invalid latitude,
     * longitude, or altitude above WGS84.
     */
    private static void validate(@NonNull Location location) {
        Preconditions.checkArgument(
                isFiniteAndAtAbsMost(location.getLatitude(), MAX_ABS_VALID_LATITUDE),
                "Invalid latitude: %f", location.getLatitude());
        Preconditions.checkArgument(
                isFiniteAndAtAbsMost(location.getLongitude(), MAX_ABS_VALID_LONGITUDE),
                "Invalid longitude: %f", location.getLongitude());
        Preconditions.checkArgument(location.hasAltitude(), "Missing altitude above WGS84");
        Preconditions.checkArgument(Double.isFinite(location.getAltitude()),
                "Invalid altitude above WGS84: %f", location.getAltitude());
    }

    private static boolean isFiniteAndAtAbsMost(double value, double rhs) {
        return Double.isFinite(value) && Math.abs(value) <= rhs;
    }

    /**
     * Returns the four S2 cell IDs for the map square associated with the {@code location}.
     *
     * <p>The first map cell contains the location, while the others are located horizontally,
     * vertically, and diagonally, in that order, with respect to the S2 (i,j) coordinate system. If
     * the diagonal map cell does not exist (i.e., the location is near an S2 cube vertex), its
     * corresponding ID is set to zero.
     */
    @NonNull
    private static long[] findMapSquare(@NonNull MapParamsProto params,
            @NonNull Location location) {
        long s2CellId = S2CellIdUtils.fromLatLngDegrees(location.getLatitude(),
                location.getLongitude());

        // Cell-space properties and coordinates.
        int sizeIj = 1 << (S2CellIdUtils.MAX_LEVEL - params.mapS2Level);
        int maxIj = 1 << S2CellIdUtils.MAX_LEVEL;
        long s0 = S2CellIdUtils.getParent(s2CellId, params.mapS2Level);
        int f0 = S2CellIdUtils.getFace(s2CellId);
        int i0 = S2CellIdUtils.getI(s2CellId);
        int j0 = S2CellIdUtils.getJ(s2CellId);
        int i1 = i0 + sizeIj;
        int j1 = j0 + sizeIj;

        // Non-boundary region calculation - simplest and most common case.
        if (i1 < maxIj && j1 < maxIj) {
            return new long[]{
                    s0,
                    S2CellIdUtils.getParent(S2CellIdUtils.fromFij(f0, i1, j0), params.mapS2Level),
                    S2CellIdUtils.getParent(S2CellIdUtils.fromFij(f0, i0, j1), params.mapS2Level),
                    S2CellIdUtils.getParent(S2CellIdUtils.fromFij(f0, i1, j1), params.mapS2Level)
            };
        }

        // Boundary region calculation.
        long[] edgeNeighbors = new long[4];
        S2CellIdUtils.getEdgeNeighbors(s0, edgeNeighbors);
        long s1 = edgeNeighbors[1];
        long s2 = edgeNeighbors[2];
        long s3;
        if (f0 % 2 == 1) {
            S2CellIdUtils.getEdgeNeighbors(s1, edgeNeighbors);
            if (i1 < maxIj) {
                s3 = edgeNeighbors[2];
            } else {
                s3 = s1;
                s1 = edgeNeighbors[1];
            }
        } else {
            S2CellIdUtils.getEdgeNeighbors(s2, edgeNeighbors);
            if (j1 < maxIj) {
                s3 = edgeNeighbors[1];
            } else {
                s3 = s2;
                s2 = edgeNeighbors[3];
            }
        }

        // Reuse edge neighbors' array to avoid an extra allocation.
        edgeNeighbors[0] = s0;
        edgeNeighbors[1] = s1;
        edgeNeighbors[2] = s2;
        edgeNeighbors[3] = s3;
        return edgeNeighbors;
    }

    /**
     * Adds to {@code location} the bilinearly interpolated Mean Sea Level altitude. In addition, a
     * Mean Sea Level altitude accuracy is added if the {@code location} has a valid vertical
     * accuracy; otherwise, does not add a corresponding accuracy.
     */
    private static void addMslAltitude(@NonNull MapParamsProto params,
            @NonNull double[] geoidHeightsMeters, @NonNull Location location) {
        double h0 = geoidHeightsMeters[0];
        double h1 = geoidHeightsMeters[1];
        double h2 = geoidHeightsMeters[2];
        double h3 = geoidHeightsMeters[3];

        // Bilinear interpolation on an S2 square of size equal to that of a map cell. wi and wj
        // are the normalized [0,1] weights in the i and j directions, respectively, allowing us to
        // employ the simplified unit square formulation.
        long s2CellId = S2CellIdUtils.fromLatLngDegrees(location.getLatitude(),
                location.getLongitude());
        double sizeIj = 1 << (S2CellIdUtils.MAX_LEVEL - params.mapS2Level);
        double wi = (S2CellIdUtils.getI(s2CellId) % sizeIj) / sizeIj;
        double wj = (S2CellIdUtils.getJ(s2CellId) % sizeIj) / sizeIj;
        double offsetMeters = h0 + (h1 - h0) * wi + (h2 - h0) * wj + (h3 - h1 - h2 + h0) * wi * wj;

        location.setMslAltitudeMeters(location.getAltitude() - offsetMeters);
        if (location.hasVerticalAccuracy()) {
            double verticalAccuracyMeters = location.getVerticalAccuracyMeters();
            if (Double.isFinite(verticalAccuracyMeters) && verticalAccuracyMeters >= 0) {
                location.setMslAltitudeAccuracyMeters(
                        (float) Math.hypot(verticalAccuracyMeters, params.modelRmseMeters));
            }
        }
    }

    /**
     * Adds a Mean Sea Level altitude to the {@code location}. In addition, adds a Mean Sea Level
     * altitude accuracy if the {@code location} has a finite and non-negative vertical accuracy;
     * otherwise, does not add a corresponding accuracy.
     *
     * <p>Must be called off the main thread as data may be loaded from raw assets.
     *
     * @throws IOException              if an I/O error occurs when loading data from raw assets.
     * @throws IllegalArgumentException if the {@code location} has an invalid latitude, longitude,
     *                                  or altitude above WGS84. Specifically, the latitude must be
     *                                  between -90 and 90 (both inclusive), the longitude must be
     *                                  between -180 and 180 (both inclusive), and the altitude
     *                                  above WGS84 must be finite.
     */
    @WorkerThread
    public void addMslAltitudeToLocation(@NonNull Context context, @NonNull Location location)
            throws IOException {
        validate(location);
        MapParamsProto params = GeoidHeightMap.getParams(context);
        long[] s2CellIds = findMapSquare(params, location);
        double[] geoidHeightsMeters = mGeoidHeightMap.readGeoidHeights(params, context, s2CellIds);
        addMslAltitude(params, geoidHeightsMeters, location);
    }

    /**
     * Same as {@link #addMslAltitudeToLocation(Context, Location)} except that data will not be
     * loaded from raw assets. Returns true if a Mean Sea Level altitude is added to the
     * {@code location}; otherwise, returns false and leaves the {@code location} unchanged.
     *
     * @hide
     */
    public boolean addMslAltitudeToLocation(@NonNull Location location) {
        validate(location);
        MapParamsProto params = GeoidHeightMap.getParams();
        if (params == null) {
            return false;
        }

        long[] s2CellIds = findMapSquare(params, location);
        double[] geoidHeightsMeters = mGeoidHeightMap.readGeoidHeights(params, s2CellIds);
        if (geoidHeightsMeters == null) {
            return false;
        }

        addMslAltitude(params, geoidHeightsMeters, location);
        return true;
    }
}
