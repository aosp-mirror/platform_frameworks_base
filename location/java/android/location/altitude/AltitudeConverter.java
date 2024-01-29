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

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.WorkerThread;
import android.content.Context;
import android.frameworks.location.altitude.GetGeoidHeightRequest;
import android.frameworks.location.altitude.GetGeoidHeightResponse;
import android.location.Location;
import android.location.flags.Flags;

import com.android.internal.location.altitude.GeoidMap;
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
 * 2023 IEEE/ION Position, Location and Navigation Symposium (PLANS).
 * </pre>
 */
public final class AltitudeConverter {

    private static final double MAX_ABS_VALID_LATITUDE = 90;
    private static final double MAX_ABS_VALID_LONGITUDE = 180;

    /** Manages a mapping of geoid heights and expiration distances associated with S2 cells. */
    private final GeoidMap mGeoidMap = new GeoidMap();

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
     * <p>The first map cell, denoted z11 in the appendix of the referenced paper above, contains
     * the location. The others are the map cells denoted z21, z12, and z22, in that order.
     */
    private static long[] findMapSquare(@NonNull MapParamsProto geoidHeightParams,
            @NonNull Location location) {
        long s2CellId = S2CellIdUtils.fromLatLngDegrees(location.getLatitude(),
                location.getLongitude());

        // Cell-space properties and coordinates.
        int sizeIj = 1 << (S2CellIdUtils.MAX_LEVEL - geoidHeightParams.mapS2Level);
        int maxIj = 1 << S2CellIdUtils.MAX_LEVEL;
        long z11 = S2CellIdUtils.getParent(s2CellId, geoidHeightParams.mapS2Level);
        int f11 = S2CellIdUtils.getFace(s2CellId);
        int i1 = S2CellIdUtils.getI(s2CellId);
        int j1 = S2CellIdUtils.getJ(s2CellId);
        int i2 = i1 + sizeIj;
        int j2 = j1 + sizeIj;

        // Non-boundary region calculation - simplest and most common case.
        if (i2 < maxIj && j2 < maxIj) {
            return new long[]{z11, S2CellIdUtils.getParent(S2CellIdUtils.fromFij(f11, i2, j1),
                    geoidHeightParams.mapS2Level), S2CellIdUtils.getParent(
                    S2CellIdUtils.fromFij(f11, i1, j2), geoidHeightParams.mapS2Level),
                    S2CellIdUtils.getParent(S2CellIdUtils.fromFij(f11, i2, j2),
                            geoidHeightParams.mapS2Level)};
        }

        // Boundary region calculation
        long[] edgeNeighbors = new long[4];
        S2CellIdUtils.getEdgeNeighbors(z11, edgeNeighbors);
        long z11W = edgeNeighbors[0];
        long z11S = edgeNeighbors[1];
        long z11E = edgeNeighbors[2];
        long z11N = edgeNeighbors[3];

        long[] otherEdgeNeighbors = new long[4];
        S2CellIdUtils.getEdgeNeighbors(z11W, otherEdgeNeighbors);
        S2CellIdUtils.getEdgeNeighbors(z11S, edgeNeighbors);
        long z11Sw = findCommonNeighbor(edgeNeighbors, otherEdgeNeighbors, z11);
        S2CellIdUtils.getEdgeNeighbors(z11E, otherEdgeNeighbors);
        long z11Se = findCommonNeighbor(edgeNeighbors, otherEdgeNeighbors, z11);
        S2CellIdUtils.getEdgeNeighbors(z11N, edgeNeighbors);
        long z11Ne = findCommonNeighbor(edgeNeighbors, otherEdgeNeighbors, z11);

        long z21 = (f11 % 2 == 1 && i2 >= maxIj) ? z11Sw : z11S;
        long z12 = (f11 % 2 == 0 && j2 >= maxIj) ? z11Ne : z11E;
        long z22 = (z21 == z11Sw) ? z11S : (z12 == z11Ne) ? z11E : z11Se;

        // Reuse edge neighbors' array to avoid an extra allocation.
        edgeNeighbors[0] = z11;
        edgeNeighbors[1] = z21;
        edgeNeighbors[2] = z12;
        edgeNeighbors[3] = z22;
        return edgeNeighbors;
    }

    /**
     * Returns the first common non-z11 neighbor found between the two arrays of edge neighbors. If
     * such a common neighbor does not exist, returns z11.
     */
    private static long findCommonNeighbor(long[] edgeNeighbors, long[] otherEdgeNeighbors,
            long z11) {
        for (long edgeNeighbor : edgeNeighbors) {
            if (edgeNeighbor == z11) {
                continue;
            }
            for (long otherEdgeNeighbor : otherEdgeNeighbors) {
                if (edgeNeighbor == otherEdgeNeighbor) {
                    return edgeNeighbor;
                }
            }
        }
        return z11;
    }

    /**
     * Adds to {@code location} the bilinearly interpolated Mean Sea Level altitude. In addition, a
     * Mean Sea Level altitude accuracy is added if the {@code location} has a valid vertical
     * accuracy; otherwise, does not add a corresponding accuracy.
     */
    private static void addMslAltitude(@NonNull MapParamsProto geoidHeightParams,
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
        double sizeIj = 1 << (S2CellIdUtils.MAX_LEVEL - geoidHeightParams.mapS2Level);
        double wi = (S2CellIdUtils.getI(s2CellId) % sizeIj) / sizeIj;
        double wj = (S2CellIdUtils.getJ(s2CellId) % sizeIj) / sizeIj;
        double offsetMeters = h0 + (h1 - h0) * wi + (h2 - h0) * wj + (h3 - h1 - h2 + h0) * wi * wj;

        location.setMslAltitudeMeters(location.getAltitude() - offsetMeters);
        if (location.hasVerticalAccuracy()) {
            double verticalAccuracyMeters = location.getVerticalAccuracyMeters();
            if (Double.isFinite(verticalAccuracyMeters) && verticalAccuracyMeters >= 0) {
                location.setMslAltitudeAccuracyMeters((float) Math.hypot(verticalAccuracyMeters,
                        geoidHeightParams.modelRmseMeters));
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
        MapParamsProto geoidHeightParams = GeoidMap.getGeoidHeightParams(context);
        long[] mapCells = findMapSquare(geoidHeightParams, location);
        double[] geoidHeightsMeters = mGeoidMap.readGeoidHeights(geoidHeightParams, context,
                mapCells);
        addMslAltitude(geoidHeightParams, geoidHeightsMeters, location);
    }

    /**
     * Same as {@link #addMslAltitudeToLocation(Context, Location)} except that this method can be
     * called on the main thread as data will not be loaded from raw assets. Returns true if a Mean
     * Sea Level altitude is added to the {@code location}; otherwise, returns false and leaves the
     * {@code location} unchanged.
     */
    @FlaggedApi(Flags.FLAG_GEOID_HEIGHTS_VIA_ALTITUDE_HAL)
    public boolean addMslAltitudeToLocation(@NonNull Location location) {
        validate(location);
        MapParamsProto geoidHeightParams = GeoidMap.getGeoidHeightParams();
        if (geoidHeightParams == null) {
            return false;
        }

        long[] mapCells = findMapSquare(geoidHeightParams, location);
        double[] geoidHeightsMeters = mGeoidMap.readGeoidHeights(geoidHeightParams, mapCells);
        if (geoidHeightsMeters == null) {
            return false;
        }

        addMslAltitude(geoidHeightParams, geoidHeightsMeters, location);
        return true;
    }

    /**
     * Returns the geoid height (a.k.a. geoid undulation) at the location specified in {@code
     * request}. The geoid height at a location is defined as the difference between an altitude
     * measured above the World Geodetic System 1984 reference ellipsoid (WGS84) and its
     * corresponding Mean Sea Level altitude.
     *
     * <p>Must be called off the main thread as data may be loaded from raw assets.
     *
     * @throws IOException              if an I/O error occurs when loading data from raw assets.
     * @throws IllegalArgumentException if the {@code request} has an invalid latitude or longitude.
     *                                  Specifically, the latitude must be between -90 and 90 (both
     *                                  inclusive), and the longitude must be between -180 and 180
     *                                  (both inclusive).
     * @hide
     */
    @WorkerThread
    public @NonNull GetGeoidHeightResponse getGeoidHeight(@NonNull Context context,
            @NonNull GetGeoidHeightRequest request) throws IOException {
        // Create a valid location from which the geoid height and its accuracy will be extracted.
        Location location = new Location("");
        location.setLatitude(request.latitudeDegrees);
        location.setLongitude(request.longitudeDegrees);
        location.setAltitude(0.0);
        location.setVerticalAccuracyMeters(0.0f);

        addMslAltitudeToLocation(context, location);
        // The geoid height for a location with zero WGS84 altitude is equal in value to the
        // negative of corresponding MSL altitude.
        double geoidHeightMeters = -location.getMslAltitudeMeters();
        // The geoid height error for a location with zero vertical accuracy is equal in value to
        // the corresponding MSL altitude accuracy.
        float geoidHeightErrorMeters = location.getMslAltitudeAccuracyMeters();

        MapParamsProto expirationDistanceParams = GeoidMap.getExpirationDistanceParams(context);
        long s2CellId = S2CellIdUtils.fromLatLngDegrees(location.getLatitude(),
                location.getLongitude());
        long[] mapCell = {S2CellIdUtils.getParent(s2CellId, expirationDistanceParams.mapS2Level)};
        double expirationDistanceMeters = mGeoidMap.readExpirationDistances(
                expirationDistanceParams, context, mapCell)[0];
        float additionalGeoidHeightErrorMeters = (float) expirationDistanceParams.modelRmseMeters;

        GetGeoidHeightResponse response = new GetGeoidHeightResponse();
        response.geoidHeightMeters = geoidHeightMeters;
        response.geoidHeightErrorMeters = geoidHeightErrorMeters;
        response.expirationDistanceMeters = expirationDistanceMeters;
        response.additionalGeoidHeightErrorMeters = additionalGeoidHeightErrorMeters;
        response.success = true;
        return response;
    }
}
