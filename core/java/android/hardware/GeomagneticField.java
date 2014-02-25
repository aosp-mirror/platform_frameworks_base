/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.hardware;

import java.util.GregorianCalendar;

/**
 * Estimates magnetic field at a given point on
 * Earth, and in particular, to compute the magnetic declination from true
 * north.
 *
 * <p>This uses the World Magnetic Model produced by the United States National
 * Geospatial-Intelligence Agency.  More details about the model can be found at
 * <a href="http://www.ngdc.noaa.gov/geomag/WMM/DoDWMM.shtml">http://www.ngdc.noaa.gov/geomag/WMM/DoDWMM.shtml</a>.
 * This class currently uses WMM-2010 which is valid until 2015, but should
 * produce acceptable results for several years after that. Future versions of
 * Android may use a newer version of the model.
 */
public class GeomagneticField {
    // The magnetic field at a given point, in nonoteslas in geodetic
    // coordinates.
    private float mX;
    private float mY;
    private float mZ;

    // Geocentric coordinates -- set by computeGeocentricCoordinates.
    private float mGcLatitudeRad;
    private float mGcLongitudeRad;
    private float mGcRadiusKm;

    // Constants from WGS84 (the coordinate system used by GPS)
    static private final float EARTH_SEMI_MAJOR_AXIS_KM = 6378.137f;
    static private final float EARTH_SEMI_MINOR_AXIS_KM = 6356.7523142f;
    static private final float EARTH_REFERENCE_RADIUS_KM = 6371.2f;

    // These coefficients and the formulae used below are from:
    // NOAA Technical Report: The US/UK World Magnetic Model for 2010-2015
    static private final float[][] G_COEFF = new float[][] {
        { 0.0f },
        { -29496.6f, -1586.3f },
        { -2396.6f, 3026.1f, 1668.6f },
        { 1340.1f, -2326.2f, 1231.9f, 634.0f },
        { 912.6f, 808.9f, 166.7f, -357.1f, 89.4f },
        { -230.9f, 357.2f, 200.3f, -141.1f, -163.0f, -7.8f },
        { 72.8f, 68.6f, 76.0f, -141.4f, -22.8f, 13.2f, -77.9f },
        { 80.5f, -75.1f, -4.7f, 45.3f, 13.9f, 10.4f, 1.7f, 4.9f },
        { 24.4f, 8.1f, -14.5f, -5.6f, -19.3f, 11.5f, 10.9f, -14.1f, -3.7f },
        { 5.4f, 9.4f, 3.4f, -5.2f, 3.1f, -12.4f, -0.7f, 8.4f, -8.5f, -10.1f },
        { -2.0f, -6.3f, 0.9f, -1.1f, -0.2f, 2.5f, -0.3f, 2.2f, 3.1f, -1.0f, -2.8f },
        { 3.0f, -1.5f, -2.1f, 1.7f, -0.5f, 0.5f, -0.8f, 0.4f, 1.8f, 0.1f, 0.7f, 3.8f },
        { -2.2f, -0.2f, 0.3f, 1.0f, -0.6f, 0.9f, -0.1f, 0.5f, -0.4f, -0.4f, 0.2f, -0.8f, 0.0f } };

    static private final float[][] H_COEFF = new float[][] {
        { 0.0f },
        { 0.0f, 4944.4f },
        { 0.0f, -2707.7f, -576.1f },
        { 0.0f, -160.2f, 251.9f, -536.6f },
        { 0.0f, 286.4f, -211.2f, 164.3f, -309.1f },
        { 0.0f, 44.6f, 188.9f, -118.2f, 0.0f, 100.9f },
        { 0.0f, -20.8f, 44.1f, 61.5f, -66.3f, 3.1f, 55.0f },
        { 0.0f, -57.9f, -21.1f, 6.5f, 24.9f, 7.0f, -27.7f, -3.3f },
        { 0.0f, 11.0f, -20.0f, 11.9f, -17.4f, 16.7f, 7.0f, -10.8f, 1.7f },
        { 0.0f, -20.5f, 11.5f, 12.8f, -7.2f, -7.4f, 8.0f, 2.1f, -6.1f, 7.0f },
        { 0.0f, 2.8f, -0.1f, 4.7f, 4.4f, -7.2f, -1.0f, -3.9f, -2.0f, -2.0f, -8.3f },
        { 0.0f, 0.2f, 1.7f, -0.6f, -1.8f, 0.9f, -0.4f, -2.5f, -1.3f, -2.1f, -1.9f, -1.8f },
        { 0.0f, -0.9f, 0.3f, 2.1f, -2.5f, 0.5f, 0.6f, 0.0f, 0.1f, 0.3f, -0.9f, -0.2f, 0.9f } };

    static private final float[][] DELTA_G = new float[][] {
        { 0.0f },
        { 11.6f, 16.5f },
        { -12.1f, -4.4f, 1.9f },
        { 0.4f, -4.1f, -2.9f, -7.7f },
        { -1.8f, 2.3f, -8.7f, 4.6f, -2.1f },
        { -1.0f, 0.6f, -1.8f, -1.0f, 0.9f, 1.0f },
        { -0.2f, -0.2f, -0.1f, 2.0f, -1.7f, -0.3f, 1.7f },
        { 0.1f, -0.1f, -0.6f, 1.3f, 0.4f, 0.3f, -0.7f, 0.6f },
        { -0.1f, 0.1f, -0.6f, 0.2f, -0.2f, 0.3f, 0.3f, -0.6f, 0.2f },
        { 0.0f, -0.1f, 0.0f, 0.3f, -0.4f, -0.3f, 0.1f, -0.1f, -0.4f, -0.2f },
        { 0.0f, 0.0f, -0.1f, 0.2f, 0.0f, -0.1f, -0.2f, 0.0f, -0.1f, -0.2f, -0.2f },
        { 0.0f, 0.0f, 0.0f, 0.1f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, -0.1f, 0.0f },
        { 0.0f, 0.0f, 0.1f, 0.1f, -0.1f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, -0.1f, 0.1f } };

    static private final float[][] DELTA_H = new float[][] {
        { 0.0f },
        { 0.0f, -25.9f },
        { 0.0f, -22.5f, -11.8f },
        { 0.0f, 7.3f, -3.9f, -2.6f },
        { 0.0f, 1.1f, 2.7f, 3.9f, -0.8f },
        { 0.0f, 0.4f, 1.8f, 1.2f, 4.0f, -0.6f },
        { 0.0f, -0.2f, -2.1f, -0.4f, -0.6f, 0.5f, 0.9f },
        { 0.0f, 0.7f, 0.3f, -0.1f, -0.1f, -0.8f, -0.3f, 0.3f },
        { 0.0f, -0.1f, 0.2f, 0.4f, 0.4f, 0.1f, -0.1f, 0.4f, 0.3f },
        { 0.0f, 0.0f, -0.2f, 0.0f, -0.1f, 0.1f, 0.0f, -0.2f, 0.3f, 0.2f },
        { 0.0f, 0.1f, -0.1f, 0.0f, -0.1f, -0.1f, 0.0f, -0.1f, -0.2f, 0.0f, -0.1f },
        { 0.0f, 0.0f, 0.1f, 0.0f, 0.1f, 0.0f, 0.1f, 0.0f, -0.1f, -0.1f, 0.0f, -0.1f },
        { 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.1f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f } };

    static private final long BASE_TIME =
        new GregorianCalendar(2010, 1, 1).getTimeInMillis();

    // The ratio between the Gauss-normalized associated Legendre functions and
    // the Schmid quasi-normalized ones. Compute these once staticly since they
    // don't depend on input variables at all.
    static private final float[][] SCHMIDT_QUASI_NORM_FACTORS =
        computeSchmidtQuasiNormFactors(G_COEFF.length);

    /**
     * Estimate the magnetic field at a given point and time.
     *
     * @param gdLatitudeDeg
     *            Latitude in WGS84 geodetic coordinates -- positive is east.
     * @param gdLongitudeDeg
     *            Longitude in WGS84 geodetic coordinates -- positive is north.
     * @param altitudeMeters
     *            Altitude in WGS84 geodetic coordinates, in meters.
     * @param timeMillis
     *            Time at which to evaluate the declination, in milliseconds
     *            since January 1, 1970. (approximate is fine -- the declination
     *            changes very slowly).
     */
    public GeomagneticField(float gdLatitudeDeg,
                            float gdLongitudeDeg,
                            float altitudeMeters,
                            long timeMillis) {
        final int MAX_N = G_COEFF.length; // Maximum degree of the coefficients.

        // We don't handle the north and south poles correctly -- pretend that
        // we're not quite at them to avoid crashing.
        gdLatitudeDeg = Math.min(90.0f - 1e-5f,
                                 Math.max(-90.0f + 1e-5f, gdLatitudeDeg));
        computeGeocentricCoordinates(gdLatitudeDeg,
                                     gdLongitudeDeg,
                                     altitudeMeters);

        assert G_COEFF.length == H_COEFF.length;

        // Note: LegendreTable computes associated Legendre functions for
        // cos(theta).  We want the associated Legendre functions for
        // sin(latitude), which is the same as cos(PI/2 - latitude), except the
        // derivate will be negated.
        LegendreTable legendre =
            new LegendreTable(MAX_N - 1,
                              (float) (Math.PI / 2.0 - mGcLatitudeRad));

        // Compute a table of (EARTH_REFERENCE_RADIUS_KM / radius)^n for i in
        // 0..MAX_N-2 (this is much faster than calling Math.pow MAX_N+1 times).
        float[] relativeRadiusPower = new float[MAX_N + 2];
        relativeRadiusPower[0] = 1.0f;
        relativeRadiusPower[1] = EARTH_REFERENCE_RADIUS_KM / mGcRadiusKm;
        for (int i = 2; i < relativeRadiusPower.length; ++i) {
            relativeRadiusPower[i] = relativeRadiusPower[i - 1] *
                relativeRadiusPower[1];
        }

        // Compute tables of sin(lon * m) and cos(lon * m) for m = 0..MAX_N --
        // this is much faster than calling Math.sin and Math.com MAX_N+1 times.
        float[] sinMLon = new float[MAX_N];
        float[] cosMLon = new float[MAX_N];
        sinMLon[0] = 0.0f;
        cosMLon[0] = 1.0f;
        sinMLon[1] = (float) Math.sin(mGcLongitudeRad);
        cosMLon[1] = (float) Math.cos(mGcLongitudeRad);

        for (int m = 2; m < MAX_N; ++m) {
            // Standard expansions for sin((m-x)*theta + x*theta) and
            // cos((m-x)*theta + x*theta).
            int x = m >> 1;
            sinMLon[m] = sinMLon[m-x] * cosMLon[x] + cosMLon[m-x] * sinMLon[x];
            cosMLon[m] = cosMLon[m-x] * cosMLon[x] - sinMLon[m-x] * sinMLon[x];
        }

        float inverseCosLatitude = 1.0f / (float) Math.cos(mGcLatitudeRad);
        float yearsSinceBase =
            (timeMillis - BASE_TIME) / (365f * 24f * 60f * 60f * 1000f);

        // We now compute the magnetic field strength given the geocentric
        // location. The magnetic field is the derivative of the potential
        // function defined by the model. See NOAA Technical Report: The US/UK
        // World Magnetic Model for 2010-2015 for the derivation.
        float gcX = 0.0f;  // Geocentric northwards component.
        float gcY = 0.0f;  // Geocentric eastwards component.
        float gcZ = 0.0f;  // Geocentric downwards component.

        for (int n = 1; n < MAX_N; n++) {
            for (int m = 0; m <= n; m++) {
                // Adjust the coefficients for the current date.
                float g = G_COEFF[n][m] + yearsSinceBase * DELTA_G[n][m];
                float h = H_COEFF[n][m] + yearsSinceBase * DELTA_H[n][m];

                // Negative derivative with respect to latitude, divided by
                // radius.  This looks like the negation of the version in the
                // NOAA Techincal report because that report used
                // P_n^m(sin(theta)) and we use P_n^m(cos(90 - theta)), so the
                // derivative with respect to theta is negated.
                gcX += relativeRadiusPower[n+2]
                    * (g * cosMLon[m] + h * sinMLon[m])
                    * legendre.mPDeriv[n][m]
                    * SCHMIDT_QUASI_NORM_FACTORS[n][m];

                // Negative derivative with respect to longitude, divided by
                // radius.
                gcY += relativeRadiusPower[n+2] * m
                    * (g * sinMLon[m] - h * cosMLon[m])
                    * legendre.mP[n][m]
                    * SCHMIDT_QUASI_NORM_FACTORS[n][m]
                    * inverseCosLatitude;

                // Negative derivative with respect to radius.
                gcZ -= (n + 1) * relativeRadiusPower[n+2]
                    * (g * cosMLon[m] + h * sinMLon[m])
                    * legendre.mP[n][m]
                    * SCHMIDT_QUASI_NORM_FACTORS[n][m];
            }
        }

        // Convert back to geodetic coordinates.  This is basically just a
        // rotation around the Y-axis by the difference in latitudes between the
        // geocentric frame and the geodetic frame.
        double latDiffRad = Math.toRadians(gdLatitudeDeg) - mGcLatitudeRad;
        mX = (float) (gcX * Math.cos(latDiffRad)
                      + gcZ * Math.sin(latDiffRad));
        mY = gcY;
        mZ = (float) (- gcX * Math.sin(latDiffRad)
                      + gcZ * Math.cos(latDiffRad));
    }

    /**
     * @return The X (northward) component of the magnetic field in nanoteslas.
     */
    public float getX() {
        return mX;
    }

    /**
     * @return The Y (eastward) component of the magnetic field in nanoteslas.
     */
    public float getY() {
        return mY;
    }

    /**
     * @return The Z (downward) component of the magnetic field in nanoteslas.
     */
    public float getZ() {
        return mZ;
    }

    /**
     * @return The declination of the horizontal component of the magnetic
     *         field from true north, in degrees (i.e. positive means the
     *         magnetic field is rotated east that much from true north).
     */
    public float getDeclination() {
        return (float) Math.toDegrees(Math.atan2(mY, mX));
    }

    /**
     * @return The inclination of the magnetic field in degrees -- positive
     *         means the magnetic field is rotated downwards.
     */
    public float getInclination() {
        return (float) Math.toDegrees(Math.atan2(mZ,
                                                 getHorizontalStrength()));
    }

    /**
     * @return  Horizontal component of the field strength in nonoteslas.
     */
    public float getHorizontalStrength() {
        return (float) Math.sqrt(mX * mX + mY * mY);
    }

    /**
     * @return  Total field strength in nanoteslas.
     */
    public float getFieldStrength() {
        return (float) Math.sqrt(mX * mX + mY * mY + mZ * mZ);
    }

    /**
     * @param gdLatitudeDeg
     *            Latitude in WGS84 geodetic coordinates.
     * @param gdLongitudeDeg
     *            Longitude in WGS84 geodetic coordinates.
     * @param altitudeMeters
     *            Altitude above sea level in WGS84 geodetic coordinates.
     * @return Geocentric latitude (i.e. angle between closest point on the
     *         equator and this point, at the center of the earth.
     */
    private void computeGeocentricCoordinates(float gdLatitudeDeg,
                                              float gdLongitudeDeg,
                                              float altitudeMeters) {
        float altitudeKm = altitudeMeters / 1000.0f;
        float a2 = EARTH_SEMI_MAJOR_AXIS_KM * EARTH_SEMI_MAJOR_AXIS_KM;
        float b2 = EARTH_SEMI_MINOR_AXIS_KM * EARTH_SEMI_MINOR_AXIS_KM;
        double gdLatRad = Math.toRadians(gdLatitudeDeg);
        float clat = (float) Math.cos(gdLatRad);
        float slat = (float) Math.sin(gdLatRad);
        float tlat = slat / clat;
        float latRad =
            (float) Math.sqrt(a2 * clat * clat + b2 * slat * slat);

        mGcLatitudeRad = (float) Math.atan(tlat * (latRad * altitudeKm + b2)
                                           / (latRad * altitudeKm + a2));

        mGcLongitudeRad = (float) Math.toRadians(gdLongitudeDeg);

        float radSq = altitudeKm * altitudeKm
            + 2 * altitudeKm * (float) Math.sqrt(a2 * clat * clat +
                                                 b2 * slat * slat)
            + (a2 * a2 * clat * clat + b2 * b2 * slat * slat)
            / (a2 * clat * clat + b2 * slat * slat);
        mGcRadiusKm = (float) Math.sqrt(radSq);
    }


    /**
     * Utility class to compute a table of Gauss-normalized associated Legendre
     * functions P_n^m(cos(theta))
     */
    static private class LegendreTable {
        // These are the Gauss-normalized associated Legendre functions -- that
        // is, they are normal Legendre functions multiplied by
        // (n-m)!/(2n-1)!! (where (2n-1)!! = 1*3*5*...*2n-1)
        public final float[][] mP;

        // Derivative of mP, with respect to theta.
        public final float[][] mPDeriv;

        /**
         * @param maxN
         *            The maximum n- and m-values to support
         * @param thetaRad
         *            Returned functions will be Gauss-normalized
         *            P_n^m(cos(thetaRad)), with thetaRad in radians.
         */
        public LegendreTable(int maxN, float thetaRad) {
            // Compute the table of Gauss-normalized associated Legendre
            // functions using standard recursion relations. Also compute the
            // table of derivatives using the derivative of the recursion
            // relations.
            float cos = (float) Math.cos(thetaRad);
            float sin = (float) Math.sin(thetaRad);

            mP = new float[maxN + 1][];
            mPDeriv = new float[maxN + 1][];
            mP[0] = new float[] { 1.0f };
            mPDeriv[0] = new float[] { 0.0f };
            for (int n = 1; n <= maxN; n++) {
                mP[n] = new float[n + 1];
                mPDeriv[n] = new float[n + 1];
                for (int m = 0; m <= n; m++) {
                    if (n == m) {
                        mP[n][m] = sin * mP[n - 1][m - 1];
                        mPDeriv[n][m] = cos * mP[n - 1][m - 1]
                            + sin * mPDeriv[n - 1][m - 1];
                    } else if (n == 1 || m == n - 1) {
                        mP[n][m] = cos * mP[n - 1][m];
                        mPDeriv[n][m] = -sin * mP[n - 1][m]
                            + cos * mPDeriv[n - 1][m];
                    } else {
                        assert n > 1 && m < n - 1;
                        float k = ((n - 1) * (n - 1) - m * m)
                            / (float) ((2 * n - 1) * (2 * n - 3));
                        mP[n][m] = cos * mP[n - 1][m] - k * mP[n - 2][m];
                        mPDeriv[n][m] = -sin * mP[n - 1][m]
                            + cos * mPDeriv[n - 1][m] - k * mPDeriv[n - 2][m];
                    }
                }
            }
        }
    }

    /**
     * Compute the ration between the Gauss-normalized associated Legendre
     * functions and the Schmidt quasi-normalized version. This is equivalent to
     * sqrt((m==0?1:2)*(n-m)!/(n+m!))*(2n-1)!!/(n-m)!
     */
    private static float[][] computeSchmidtQuasiNormFactors(int maxN) {
        float[][] schmidtQuasiNorm = new float[maxN + 1][];
        schmidtQuasiNorm[0] = new float[] { 1.0f };
        for (int n = 1; n <= maxN; n++) {
            schmidtQuasiNorm[n] = new float[n + 1];
            schmidtQuasiNorm[n][0] =
                schmidtQuasiNorm[n - 1][0] * (2 * n - 1) / (float) n;
            for (int m = 1; m <= n; m++) {
                schmidtQuasiNorm[n][m] = schmidtQuasiNorm[n][m - 1]
                    * (float) Math.sqrt((n - m + 1) * (m == 1 ? 2 : 1)
                                / (float) (n + m));
            }
        }
        return schmidtQuasiNorm;
    }
}
