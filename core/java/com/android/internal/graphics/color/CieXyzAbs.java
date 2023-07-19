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

/**
 * CIE 1931 XYZ interchange color with absolute luminance specified in nits (cd/m^2).
 * This is similar to colorkt's CieXyzAbs class, but it also does the jobs of CieXyz, LinearSrgb,
 * and Srgb in order to reduce garbage object creation.
 */
public final class CieXyzAbs {
    public static final double DEFAULT_SDR_WHITE_LUMINANCE = 200.0; // cd/m^2

    public double x;
    public double y;
    public double z;

    public CieXyzAbs(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public CieXyzAbs(int rgb8) {
        int r8 = (rgb8 >> 16) & 0xff;
        int g8 = (rgb8 >> 8) & 0xff;
        int b8 = rgb8 & 0xff;

        double r = srgbFInv(((double) r8) / 255.0);
        double g = srgbFInv(((double) g8) / 255.0);
        double b = srgbFInv(((double) b8) / 255.0);

        double xRel = 0.41245643908969226  * r + 0.357576077643909   * g + 0.18043748326639897 * b;
        double yRel = 0.21267285140562256  * r + 0.715152155287818   * g + 0.07217499330655959 * b;
        double zRel = 0.019333895582329303 * r + 0.11919202588130297 * g + 0.950304078536368   * b;

        this.x = xRel * DEFAULT_SDR_WHITE_LUMINANCE;
        this.y = yRel * DEFAULT_SDR_WHITE_LUMINANCE;
        this.z = zRel * DEFAULT_SDR_WHITE_LUMINANCE;
    }

    public int toRgb8() {
        double xRel = x / DEFAULT_SDR_WHITE_LUMINANCE;
        double yRel = y / DEFAULT_SDR_WHITE_LUMINANCE;
        double zRel = z / DEFAULT_SDR_WHITE_LUMINANCE;

        double r = xyzRelToR(xRel, yRel, zRel);
        double g = xyzRelToG(xRel, yRel, zRel);
        double b = xyzRelToB(xRel, yRel, zRel);

        int r8 = ((int) Math.round(srgbF(r) * 255.0) & 0xff) << 16;
        int g8 = ((int) Math.round(srgbF(g) * 255.0) & 0xff) << 8;
        int b8 = ((int) Math.round(srgbF(b) * 255.0) & 0xff);

        return r8 | g8 | b8;
    }

    /*package-private*/ boolean isInGamut() {
        // I don't like this duplicated code, but the alternative would be to create lots of unnecessary
        // garbage array objects for gamut mapping every time a color is processed.
        double xRel = x / DEFAULT_SDR_WHITE_LUMINANCE;
        double yRel = y / DEFAULT_SDR_WHITE_LUMINANCE;
        double zRel = z / DEFAULT_SDR_WHITE_LUMINANCE;

        double r = xyzRelToR(xRel, yRel, zRel);
        double g = xyzRelToG(xRel, yRel, zRel);
        double b = xyzRelToB(xRel, yRel, zRel);

        return inGamut(r) && inGamut(g) && inGamut(b);
    }

    // This matrix (along with the inverse above) has been optimized to minimize chroma in CIELCh
    // when converting neutral sRGB colors to CIELAB. The maximum chroma for sRGB neutral colors 0-255 is
    // 5.978733960281817e-14.
    //
    // Calculated with https://github.com/facelessuser/coloraide/blob/master/tools/calc_xyz_transform.py
    // Using D65 xy chromaticities from the sRGB spec: x = 0.3127, y = 0.3290
    // Always keep in sync with Illuminants.D65.
    private static double xyzRelToR(double x, double y, double z) {
        return 3.2404541621141045 * x + -1.5371385127977162 * y + -0.4985314095560159 * z;
    }

    private static double xyzRelToG(double x, double y, double z) {
        return -0.969266030505187 * x + 1.8760108454466944 * y + 0.04155601753034983 * z;
    }

    private static double xyzRelToB(double x, double y, double z) {
        return 0.05564343095911474 * x + -0.2040259135167538 * y + 1.0572251882231787  * z;
    }

    // Linear -> sRGB
    private static double srgbF(double x) {
        if (x >= 0.0031308) {
            return 1.055 * Math.pow(x, 1.0 / 2.4) - 0.055;
        } else {
            return 12.92 * x;
        }
    }

    // sRGB -> linear
    private static double srgbFInv(double x) {
        if (x >= 0.04045) {
            return Math.pow((x + 0.055) / 1.055, 2.4);
        } else {
            return x / 12.92;
        }
    }

    private static boolean inGamut(double x) {
        return x >= 0.0 && x <= 1.0;
    }
}
