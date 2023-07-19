/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.internal.graphics.color;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Minimal implementation of ZCAM that only includes lightness, chroma, and hue.
 *
 * This implements the bare minimum of ZCAM necessary for ColorStateList modulation.
 * It has fewer object-oriented abstractions and lacks the more sophisticated
 * architecture and organization of colorkt; however, this can be called many times
 * during UI view inflation, so performance is imperative.
 */
public final class Zcam {
    private static final double B = 1.15;
    private static final double G = 0.66;
    private static final double C1 = 3424.0 / 4096;
    private static final double C2 = 2413.0 / 128;
    private static final double C3 = 2392.0 / 128;
    private static final double ETA = 2610.0 / 16384;
    private static final double RHO = 1.7 * 2523.0 / 32;
    private static final double EPSILON = 3.7035226210190005e-11;

    public double lightness;
    public double chroma;
    public double hue;

    public final ViewingConditions viewingConditions;

    public Zcam(double lightness, double chroma, double hue) {
        this(lightness, chroma, hue, ViewingConditions.DEFAULT);
    }

    public Zcam(CieXyzAbs xyz) {
        this(xyz, ViewingConditions.DEFAULT);
    }

    public Zcam(double lightness, double chroma, double hue, ViewingConditions cond) {
        this.lightness = lightness;
        this.chroma = chroma;
        this.hue = hue;
        this.viewingConditions = cond;
    }

    public Zcam(CieXyzAbs xyz, ViewingConditions cond) {
        /* Step 2 */
        // Achromatic response
        double[] Izazbz = xyzToIzazbz(xyz);
        double Iz = Izazbz[0];
        double az = Izazbz[1];
        double bz = Izazbz[2];

        /* Step 3 */
        // Hue angle
        double hz = Math.toDegrees(Math.atan2(bz, az));
        double hp = (hz < 0) ? hz + 360.0 : hz;

        /* Step 4 */
        // Eccentricity factor
        double ez = hpToEz(hp);

        /* Step 5 */
        // Brightness
        double Qz = izToQz(Iz, cond);
        double Qz_w = cond.Qz_w;

        // Lightness
        double Jz = 100.0 * (Qz / Qz_w);

        // Colorfulness
        double Mz = 100.0 * Math.pow(square(az) + square(bz), 0.37) *
                ((Math.pow(ez, 0.068) * cond.ez_coeff) / cond.Mz_denom);

        // Chroma
        double Cz = 100.0 * (Mz / Qz_w);

        /* Step 6 is missing because this implementation doesn't support 2D attributes */

        this.lightness = Jz;
        this.chroma = Cz;
        this.hue = hp;
        this.viewingConditions = cond;
    }

    public CieXyzAbs toXyzAbs() {
        return toXyzAbs(lightness, chroma, hue, viewingConditions);
    }

    /*package-private*/ static CieXyzAbs toXyzAbs(double lightness, double chroma, double hue) {
        return toXyzAbs(lightness, chroma, hue, ViewingConditions.DEFAULT);
    }

    private static CieXyzAbs toXyzAbs(double lightness, double chroma, double hue, ViewingConditions cond) {
        double Qz_w = cond.Qz_w;

        /* Step 1 */
        // Achromatic response
        double Iz = Math.pow((lightness * Qz_w) / (cond.Iz_coeff * 100.0),
                cond.Qz_denom / (1.6 * cond.surroundFactor));

        /* Step 2 is missing because we use chroma as the input */

        /* Step 3 is missing because hue composition is not supported */

        /* Step 4 */
        double Mz = (chroma * Qz_w) / 100.0;
        double ez = hpToEz(hue);
        double Cz_p = Math.pow((Mz * cond.Mz_denom) /
                // Paper specifies pow(1.3514) but this extra precision is necessary for accurate inversion
                (100.0 * Math.pow(ez, 0.068) * cond.ez_coeff), 1.0 / 0.37 / 2);
        double hueRad = Math.toRadians(hue);
        double az = Cz_p * Math.cos(hueRad);
        double bz = Cz_p * Math.sin(hueRad);

        /* Step 5 */
        double I = Iz + EPSILON;

        double r = pq(I + 0.2772100865*az +  0.1160946323*bz);
        double g = pq(I);
        double b = pq(I + 0.0425858012*az + -0.7538445799*bz);

        double xp =  1.9242264358*r + -1.0047923126*g +  0.0376514040*b;
        double yp =  0.3503167621*r +  0.7264811939*g + -0.0653844229*b;
        double z  = -0.0909828110*r + -0.3127282905*g +  1.5227665613*b;

        double x = (xp + (B - 1)*z) / B;
        double y = (yp + (G - 1)*x) / G;

        return new CieXyzAbs(x, y, z);
    }

    // Transfer function and inverse
    private static double pq(double x) {
        double num = C1 - Math.pow(x, 1.0/RHO);
        double denom = C3*Math.pow(x, 1.0/RHO) - C2;

        return 10000.0 * Math.pow(num / denom, 1.0/ETA);
    }
    private static double pqInv(double x) {
        double num = C1 + C2*Math.pow(x / 10000, ETA);
        double denom = 1.0 + C3*Math.pow(x / 10000, ETA);

        return Math.pow(num / denom, RHO);
    }

    // Intermediate conversion, also used in ViewingConditions
    private static double[] xyzToIzazbz(CieXyzAbs xyz) {
        // This equation (#4) is wrong in the paper; below is the correct version.
        // It can be derived from the inverse model (supplementary paper) or the original Jzazbz paper.
        double xp = B*xyz.x - (B-1)*xyz.z;
        double yp = G*xyz.y - (G-1)*xyz.x;

        double rp = pqInv( 0.41478972*xp + 0.579999*yp + 0.0146480*xyz.z);
        double gp = pqInv(-0.20151000*xp + 1.120649*yp + 0.0531008*xyz.z);
        double bp = pqInv(-0.01660080*xp + 0.264800*yp + 0.6684799*xyz.z);

        double az = 3.524000*rp + -4.066708*gp +  0.542708*bp;
        double bz = 0.199076*rp +  1.096799*gp + -1.295875*bp;
        double Iz = gp - EPSILON;

        return new double[]{Iz, az, bz};
    }

    // Shared between forward and inverse models
    private static double hpToEz(double hp) {
        return 1.015 + Math.cos(Math.toRadians(89.038 + hp));
    }

    private static double izToQz(double Iz, ViewingConditions cond) {
        return cond.Iz_coeff * Math.pow(Iz, (1.6 * cond.surroundFactor) / cond.Qz_denom);
    }

    private static double square(double x) {
        return x * x;
    }

    public static class ViewingConditions {
        public static final double SURROUND_DARK = 0.525;
        public static final double SURROUND_DIM = 0.59;
        public static final double SURROUND_AVERAGE = 0.69;

        public static final ViewingConditions DEFAULT = new ViewingConditions(
                SURROUND_AVERAGE,
                0.4 * CieXyzAbs.DEFAULT_SDR_WHITE_LUMINANCE,
                // Mid-gray background: CIELAB L = 50
                0.18418651851244416 * CieXyzAbs.DEFAULT_SDR_WHITE_LUMINANCE,
                Illuminants.getD65Abs(CieXyzAbs.DEFAULT_SDR_WHITE_LUMINANCE)
        );

        public final double surroundFactor;
        public final double adaptingLuminance;
        public final double backgroundLuminance;
        public final CieXyzAbs referenceWhite;

        @VisibleForTesting
        public final double Iz_coeff;
        @VisibleForTesting
        public final double ez_coeff;
        @VisibleForTesting
        public final double Qz_denom;
        @VisibleForTesting
        public final double Mz_denom;
        @VisibleForTesting
        public final double Qz_w;

        public ViewingConditions(double surroundFactor, double adaptingLuminance,
                double backgroundLuminance, CieXyzAbs referenceWhite) {
            this.surroundFactor = surroundFactor;
            this.adaptingLuminance = adaptingLuminance;
            this.backgroundLuminance = backgroundLuminance;
            this.referenceWhite = referenceWhite;

            double F_b = Math.sqrt(backgroundLuminance / referenceWhite.y);
            double F_l = 0.171 * Math.cbrt(adaptingLuminance) * (1.0 - Math.exp(-48.0 / 9.0 * adaptingLuminance));

            this.Iz_coeff = 2700.0 * Math.pow(surroundFactor, 2.2) * Math.pow(F_b, 0.5) * Math.pow(F_l, 0.2);
            this.ez_coeff = Math.pow(F_l, 0.2);
            this.Qz_denom = Math.pow(F_b, 0.12);

            double Iz_w = xyzToIzazbz(referenceWhite)[0];
            this.Mz_denom = Math.pow(Iz_w, 0.78) * Math.pow(F_b, 0.1);

            // Depends on coefficients computed above
            this.Qz_w = izToQz(Iz_w, this);
        }
    }
}
