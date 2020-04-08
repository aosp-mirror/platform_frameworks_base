/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.telephony;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.text.TextUtils;

import com.android.internal.telephony.util.TelephonyUtils;
import com.android.telephony.Rlog;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * This utils class is used for geo-fencing of CellBroadcast messages and is used by the cell
 * broadcast module.
 *
 * The coordinates used by this utils class are latitude and longitude, but some algorithms in this
 * class only use them as coordinates on plane, so the calculation will be inaccurate. So don't use
 * this class for anything other then geo-targeting of cellbroadcast messages.
 *
 * More information regarding cell broadcast geo-fencing logic is laid out in 3GPP TS 23.041 and
 * ATIS-0700041.
 * @hide
 */
@SystemApi
public class CbGeoUtils {

    /**
     * This class is never instantiated
     * @hide
     */
    private CbGeoUtils() {}

    /** Geometric interface. */
    public interface Geometry {
        /**
         * Determines if the given point {@code p} is inside the geometry.
         * @param p point in latitude, longitude format.
         * @return {@code True} if the given point is inside the geometry.
         */
        boolean contains(@NonNull LatLng p);
    }

    /**
     * Tolerance for determining if the value is 0. If the absolute value of a value is less than
     * this tolerance, it will be treated as 0.
     * @hide
     */
    public static final double EPS = 1e-7;

    /**
     * The radius of earth.
     * @hide
     */
    public static final int EARTH_RADIUS_METER = 6371 * 1000;

    private static final String TAG = "CbGeoUtils";

    // The TLV tags of WAC, defined in ATIS-0700041 5.2.3 WAC tag coding.
    /** @hide */
    public static final int GEO_FENCING_MAXIMUM_WAIT_TIME = 0x01;
    /** @hide */
    public static final int GEOMETRY_TYPE_POLYGON = 0x02;
    /** @hide */
    public static final int GEOMETRY_TYPE_CIRCLE = 0x03;

    // The identifier of geometry in the encoded string.
    /** @hide */
    private static final String CIRCLE_SYMBOL = "circle";
    /** @hide */
    private static final String POLYGON_SYMBOL = "polygon";

    /** A point represented by (latitude, longitude). */
    public static class LatLng {
        public final double lat;
        public final double lng;

        /**
         * Constructor.
         * @param lat latitude, range [-90, 90]
         * @param lng longitude, range [-180, 180]
         */
        public LatLng(double lat, double lng) {
            this.lat = lat;
            this.lng = lng;
        }

        /**
         * @param p the point to subtract
         * @return the result of the subtraction
         */
        @NonNull
        public LatLng subtract(@NonNull LatLng p) {
            return new LatLng(lat - p.lat, lng - p.lng);
        }

        /**
         * Calculate the distance in meters between this point and the given point {@code p}.
         * @param p the point used to calculate the distance.
         * @return the distance in meters.
         */
        public double distance(@NonNull LatLng p) {
            double dlat = Math.sin(0.5 * Math.toRadians(lat - p.lat));
            double dlng = Math.sin(0.5 * Math.toRadians(lng - p.lng));
            double x = dlat * dlat
                    + dlng * dlng * Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(p.lat));
            return 2 * Math.atan2(Math.sqrt(x), Math.sqrt(1 - x)) * EARTH_RADIUS_METER;
        }

        @Override
        public String toString() {
            return "(" + lat + "," + lng + ")";
        }

        /**
         * @hide
         */
        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            }

            if (!(o instanceof LatLng)) {
                return false;
            }

            LatLng l = (LatLng) o;
            return lat == l.lat && lng == l.lng;
        }
    }

    /**
     * A class representing a simple polygon with at least 3 points. This is used for geo-fencing
     * logic with cell broadcasts. More information regarding cell broadcast geo-fencing logic is
     * laid out in 3GPP TS 23.041 and ATIS-0700041.
     */
    public static class Polygon implements Geometry {
        /**
         * In order to reduce the loss of precision in floating point calculations, all vertices
         * of the polygon are scaled. Set the value of scale to 1000 can take into account the
         * actual distance accuracy of 1 meter if the EPS is 1e-7 during the calculation.
         */
        private static final double SCALE = 1000.0;

        private final List<LatLng> mVertices;
        private final List<Point> mScaledVertices;
        private final LatLng mOrigin;

        /**
         * Constructs a simple polygon from the given vertices. The adjacent two vertices are
         * connected to form an edge of the polygon. The polygon has at least 3 vertices, and the
         * last vertices and the first vertices must be adjacent.
         *
         * The longitude difference in the vertices should be less than 180 degrees.
         */
        public Polygon(@NonNull List<LatLng> vertices) {
            mVertices = vertices;

            // Find the point with smallest longitude as the mOrigin point.
            int idx = 0;
            for (int i = 1; i < vertices.size(); i++) {
                if (vertices.get(i).lng < vertices.get(idx).lng) {
                    idx = i;
                }
            }
            mOrigin = vertices.get(idx);

            mScaledVertices = vertices.stream()
                    .map(latLng -> convertAndScaleLatLng(latLng))
                    .collect(Collectors.toList());
        }

        /**
         * Return the list of vertices which compose the polygon.
         */
        public @NonNull List<LatLng> getVertices() {
            return mVertices;
        }

        /**
         * Check if the given LatLng is inside the polygon.
         *
         * If a LatLng is on the edge of the polygon, it is also considered to be inside the
         * polygon.
         */
        @Override
        public boolean contains(@NonNull LatLng latLng) {
            // This method counts the number of times the polygon winds around the point P, A.K.A
            // "winding number". The point is outside only when this "winding number" is 0.

            Point p = convertAndScaleLatLng(latLng);

            int n = mScaledVertices.size();
            int windingNumber = 0;
            for (int i = 0; i < n; i++) {
                Point a = mScaledVertices.get(i);
                Point b = mScaledVertices.get((i + 1) % n);

                // CCW is counterclockwise
                // CCW = ab x ap
                // CCW > 0 -> ap is on the left side of ab
                // CCW == 0 -> ap is on the same line of ab
                // CCW < 0 -> ap is on the right side of ab
                int ccw = sign(crossProduct(b.subtract(a), p.subtract(a)));

                if (ccw == 0) {
                    if (Math.min(a.x, b.x) <= p.x && p.x <= Math.max(a.x, b.x)
                            && Math.min(a.y, b.y) <= p.y && p.y <= Math.max(a.y, b.y)) {
                        return true;
                    }
                } else {
                    if (sign(a.y - p.y) <= 0) {
                        // upward crossing
                        if (ccw > 0 && sign(b.y - p.y) > 0) {
                            ++windingNumber;
                        }
                    } else {
                        // downward crossing
                        if (ccw < 0 && sign(b.y - p.y) <= 0) {
                            --windingNumber;
                        }
                    }
                }
            }
            return windingNumber != 0;
        }

        /**
         * Move the given point {@code latLng} to the coordinate system with {@code mOrigin} as the
         * origin and scale it. {@code mOrigin} is selected from the vertices of a polygon, it has
         * the smallest longitude value among all of the polygon vertices.
         *
         * @param latLng the point need to be converted and scaled.
         * @Return a {@link Point} object.
         */
        private Point convertAndScaleLatLng(LatLng latLng) {
            double x = latLng.lat - mOrigin.lat;
            double y = latLng.lng - mOrigin.lng;

            // If the point is in different hemispheres(western/eastern) than the mOrigin, and the
            // edge between them cross the 180th meridian, then its relative coordinates will be
            // extended.
            // For example, suppose the longitude of the mOrigin is -178, and the longitude of the
            // point to be converted is 175, then the longitude after the conversion is -8.
            // calculation: (-178 - 8) - (-178).
            if (sign(mOrigin.lng) != 0 && sign(mOrigin.lng) != sign(latLng.lng)) {
                double distCross0thMeridian = Math.abs(mOrigin.lng) + Math.abs(latLng.lng);
                if (sign(distCross0thMeridian * 2 - 360) > 0) {
                    y = sign(mOrigin.lng) * (360 - distCross0thMeridian);
                }
            }
            return new Point(x * SCALE, y * SCALE);
        }

        private static double crossProduct(Point a, Point b) {
            return a.x * b.y - a.y * b.x;
        }

        /** @hide */
        static final class Point {
            public final double x;
            public final double y;

            Point(double x, double y) {
                this.x = x;
                this.y = y;
            }

            public Point subtract(Point p) {
                return new Point(x - p.x, y - p.y);
            }
        }

        @Override
        public String toString() {
            String str = "Polygon: ";
            if (TelephonyUtils.IS_DEBUGGABLE) {
                str += mVertices;
            }
            return str;
        }

        /**
         * @hide
         */
        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            }

            if (!(o instanceof Polygon)) {
                return false;
            }

            Polygon p = (Polygon) o;
            if (mVertices.size() != p.mVertices.size()) {
                return false;
            }
            for (int i = 0; i < mVertices.size(); i++) {
                if (!mVertices.get(i).equals(p.mVertices.get(i))) {
                    return false;
                }
            }

            return true;
        }
    }

    /**
     * A class represents a {@link Geometry} in the shape of a Circle. This is used for handling
     * geo-fenced cell broadcasts. More information regarding cell broadcast geo-fencing logic is
     * laid out in 3GPP TS 23.041 and ATIS-0700041.
     */
    public static class Circle implements Geometry {
        private final LatLng mCenter;
        private final double mRadiusMeter;

        /**
         * Construct a Circle given a center point and a radius in meters.
         *
         * @param center the latitude and longitude of the center of the circle
         * @param radiusInMeters the radius of the circle in meters
         */
        public Circle(@NonNull LatLng center, double radiusInMeters) {
            this.mCenter = center;
            this.mRadiusMeter = radiusInMeters;
        }

        /**
         * Return the latitude and longitude of the center of the circle;
         */
        public @NonNull LatLng getCenter() {
            return mCenter;
        }

        /**
         * Return the radius of the circle in meters.
         */
        public double getRadius() {
            return mRadiusMeter;
        }

        /**
         * Check if the given LatLng is inside the circle.
         *
         * If a LatLng is on the edge of the circle, it is also considered to be inside the circle.
         */
        @Override
        public boolean contains(@NonNull LatLng latLng) {
            return mCenter.distance(latLng) <= mRadiusMeter;
        }

        @Override
        public String toString() {
            String str = "Circle: ";
            if (TelephonyUtils.IS_DEBUGGABLE) {
                str += mCenter + ", radius = " + mRadiusMeter;
            }

            return str;
        }

        /**
         * @hide
         */
        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            }

            if (!(o instanceof Circle)) {
                return false;
            }

            Circle c = (Circle) o;
            return mCenter.equals(c.mCenter)
                    && Double.compare(mRadiusMeter, c.mRadiusMeter) == 0;
        }
    }

    /**
     * Parse the geometries from the encoded string {@code str}. The string must follow the
     * geometry encoding specified by {@link android.provider.Telephony.CellBroadcasts#GEOMETRIES}.
     * @hide
     */
    @NonNull
    public static List<Geometry> parseGeometriesFromString(@NonNull String str) {
        List<Geometry> geometries = new ArrayList<>();
        for (String geometryStr : str.split("\\s*;\\s*")) {
            String[] geoParameters = geometryStr.split("\\s*\\|\\s*");
            switch (geoParameters[0]) {
                case CIRCLE_SYMBOL:
                    geometries.add(new Circle(parseLatLngFromString(geoParameters[1]),
                            Double.parseDouble(geoParameters[2])));
                    break;
                case POLYGON_SYMBOL:
                    List<LatLng> vertices = new ArrayList<>(geoParameters.length - 1);
                    for (int i = 1; i < geoParameters.length; i++) {
                        vertices.add(parseLatLngFromString(geoParameters[i]));
                    }
                    geometries.add(new Polygon(vertices));
                    break;
                default:
                    Rlog.e(TAG, "Invalid geometry format " + geometryStr);
            }
        }
        return geometries;
    }

    /**
     * Encode a list of geometry objects to string. The encoding format is specified by
     * {@link android.provider.Telephony.CellBroadcasts#GEOMETRIES}.
     *
     * @param geometries the list of geometry objects need to be encoded.
     * @return the encoded string.
     * @hide
     */
    @NonNull
    public static String encodeGeometriesToString(List<Geometry> geometries) {
        if (geometries == null || geometries.isEmpty()) return "";
        return geometries.stream()
                .map(geometry -> encodeGeometryToString(geometry))
                .filter(encodedStr -> !TextUtils.isEmpty(encodedStr))
                .collect(Collectors.joining(";"));
    }


    /**
     * Encode the geometry object to string. The encoding format is specified by
     * {@link android.provider.Telephony.CellBroadcasts#GEOMETRIES}.
     * @param geometry the geometry object need to be encoded.
     * @return the encoded string.
     * @hide
     */
    @NonNull
    private static String encodeGeometryToString(@NonNull Geometry geometry) {
        StringBuilder sb = new StringBuilder();
        if (geometry instanceof Polygon) {
            sb.append(POLYGON_SYMBOL);
            for (LatLng latLng : ((Polygon) geometry).getVertices()) {
                sb.append("|");
                sb.append(latLng.lat);
                sb.append(",");
                sb.append(latLng.lng);
            }
        } else if (geometry instanceof Circle) {
            sb.append(CIRCLE_SYMBOL);
            Circle circle = (Circle) geometry;

            // Center
            sb.append("|");
            sb.append(circle.getCenter().lat);
            sb.append(",");
            sb.append(circle.getCenter().lng);

            // Radius
            sb.append("|");
            sb.append(circle.getRadius());
        } else {
            Rlog.e(TAG, "Unsupported geometry object " + geometry);
            return null;
        }
        return sb.toString();
    }

    /**
     * Parse {@link LatLng} from {@link String}. Latitude and longitude are separated by ",".
     * Example: "13.56,-55.447".
     *
     * @param str encoded lat/lng string.
     * @Return {@link LatLng} object.
     * @hide
     */
    @NonNull
    public static LatLng parseLatLngFromString(@NonNull String str) {
        String[] latLng = str.split("\\s*,\\s*");
        return new LatLng(Double.parseDouble(latLng[0]), Double.parseDouble(latLng[1]));
    }

    /**
     * @Return the sign of the given value {@code value} with the specified tolerance. Return 1
     * means the sign is positive, -1 means negative, 0 means the value will be treated as 0.
     * @hide
     */
    public static int sign(double value) {
        if (value > EPS) return 1;
        if (value < -EPS) return -1;
        return 0;
    }
}
