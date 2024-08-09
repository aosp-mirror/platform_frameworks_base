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

package com.android.internal.graphics.cam;


import android.annotation.NonNull;
import android.graphics.Color;
import android.ravenwood.annotation.RavenwoodKeepWholeClass;

import com.android.internal.graphics.ColorUtils;

/**
 * Collection of methods for transforming between color spaces.
 *
 * <p>Methods are named $xFrom$Y. For example, lstarFromInt() returns L* from an ARGB integer.
 *
 * <p>These methods, generally, convert colors between the L*a*b*, XYZ, and sRGB spaces.
 *
 * <p>L*a*b* is a perceptually accurate color space. This is particularly important in the L*
 * dimension: it measures luminance and unlike lightness measures traditionally used in UI work via
 * RGB or HSL, this luminance transitions smoothly, permitting creation of pleasing shades of a
 * color, and more pleasing transitions between colors.
 *
 * <p>XYZ is commonly used as an intermediate color space for converting between one color space to
 * another. For example, to convert RGB to L*a*b*, first RGB is converted to XYZ, then XYZ is
 * convered to L*a*b*.
 *
 * <p>sRGB is a "specification originated from work in 1990s through cooperation by Hewlett-Packard
 * and Microsoft, and it was designed to be a standard definition of RGB for the internet, which it
 * indeed became...The standard is based on a sampling of computer monitors at the time...The whole
 * idea of sRGB is that if everyone assumed that RGB meant the same thing, then the results would be
 * consistent, and reasonably good. It worked." - Fairchild, Color Models and Systems: Handbook of
 * Color Psychology, 2015
 */
@RavenwoodKeepWholeClass
public final class CamUtils {
    private CamUtils() {
    }

    // Transforms XYZ color space coordinates to 'cone'/'RGB' responses in CAM16.
    static final float[][] XYZ_TO_CAM16RGB = {
            {0.401288f, 0.650173f, -0.051461f},
            {-0.250268f, 1.204414f, 0.045854f},
            {-0.002079f, 0.048952f, 0.953127f}
    };

    // Transforms 'cone'/'RGB' responses in CAM16 to XYZ color space coordinates.
    static final float[][] CAM16RGB_TO_XYZ = {
            {1.86206786f, -1.01125463f, 0.14918677f},
            {0.38752654f, 0.62144744f, -0.00897398f},
            {-0.01584150f, -0.03412294f, 1.04996444f}
    };

    // Need this, XYZ coordinates in internal ColorUtils are private

    // sRGB specification has D65 whitepoint - Stokes, Anderson, Chandrasekar, Motta - A Standard
    // Default Color Space for the Internet: sRGB, 1996
    static final float[] WHITE_POINT_D65 = {95.047f, 100.0f, 108.883f};

    // This is a more precise sRGB to XYZ transformation matrix than traditionally
    // used. It was derived using Schlomer's technique of transforming the xyY
    // primaries to XYZ, then applying a correction to ensure mapping from sRGB
    // 1, 1, 1 to the reference white point, D65.
    static final double[][] SRGB_TO_XYZ =
            new double[][] {
                    new double[] {0.41233895, 0.35762064, 0.18051042},
                    new double[] {0.2126, 0.7152, 0.0722},
                    new double[] {0.01932141, 0.11916382, 0.95034478},
            };

    static final double[][] XYZ_TO_SRGB =
            new double[][] {
                    new double[] {
                            3.2413774792388685, -1.5376652402851851, -0.49885366846268053,
                    },
                    new double[] {
                            -0.9691452513005321, 1.8758853451067872, 0.04156585616912061,
                    },
                    new double[] {
                            0.05562093689691305, -0.20395524564742123, 1.0571799111220335,
                    },
            };

    /**
     * The signum function.
     *
     * @return 1 if num > 0, -1 if num < 0, and 0 if num = 0
     */
    public static int signum(double num) {
        if (num < 0) {
            return -1;
        } else if (num == 0) {
            return 0;
        } else {
            return 1;
        }
    }

    /**
     * Converts an L* value to an ARGB representation.
     *
     * @param lstar L* in L*a*b*
     * @return ARGB representation of grayscale color with lightness matching L*
     */
    public static int argbFromLstar(double lstar) {
        double fy = (lstar + 16.0) / 116.0;
        double fz = fy;
        double fx = fy;
        double kappa = 24389.0 / 27.0;
        double epsilon = 216.0 / 24389.0;
        boolean lExceedsEpsilonKappa = lstar > 8.0;
        double y = lExceedsEpsilonKappa ? fy * fy * fy : lstar / kappa;
        boolean cubeExceedEpsilon = fy * fy * fy > epsilon;
        double x = cubeExceedEpsilon ? fx * fx * fx : lstar / kappa;
        double z = cubeExceedEpsilon ? fz * fz * fz : lstar / kappa;
        float[] whitePoint = WHITE_POINT_D65;
        return argbFromXyz(x * whitePoint[0], y * whitePoint[1], z * whitePoint[2]);
    }

    /** Converts a color from ARGB to XYZ. */
    public static int argbFromXyz(double x, double y, double z) {
        double[][] matrix = XYZ_TO_SRGB;
        double linearR = matrix[0][0] * x + matrix[0][1] * y + matrix[0][2] * z;
        double linearG = matrix[1][0] * x + matrix[1][1] * y + matrix[1][2] * z;
        double linearB = matrix[2][0] * x + matrix[2][1] * y + matrix[2][2] * z;
        int r = delinearized(linearR);
        int g = delinearized(linearG);
        int b = delinearized(linearB);
        return argbFromRgb(r, g, b);
    }

    /** Converts a color from linear RGB components to ARGB format. */
    public static int argbFromLinrgb(double[] linrgb) {
        int r = delinearized(linrgb[0]);
        int g = delinearized(linrgb[1]);
        int b = delinearized(linrgb[2]);
        return argbFromRgb(r, g, b);
    }

    /** Converts a color from linear RGB components to ARGB format. */
    public static int argbFromLinrgbComponents(double r, double g, double b) {
        return argbFromRgb(delinearized(r), delinearized(g), delinearized(b));
    }

    /**
     * Delinearizes an RGB component.
     *
     * @param rgbComponent 0.0 <= rgb_component <= 100.0, represents linear R/G/B channel
     * @return 0 <= output <= 255, color channel converted to regular RGB space
     */
    public static int delinearized(double rgbComponent) {
        double normalized = rgbComponent / 100.0;
        double delinearized = 0.0;
        if (normalized <= 0.0031308) {
            delinearized = normalized * 12.92;
        } else {
            delinearized = 1.055 * Math.pow(normalized, 1.0 / 2.4) - 0.055;
        }
        return clampInt(0, 255, (int) Math.round(delinearized * 255.0));
    }

    /**
     * Clamps an integer between two integers.
     *
     * @return input when min <= input <= max, and either min or max otherwise.
     */
    public static int clampInt(int min, int max, int input) {
        if (input < min) {
            return min;
        } else if (input > max) {
            return max;
        }

        return input;
    }

    /** Converts a color from RGB components to ARGB format. */
    public static int argbFromRgb(int red, int green, int blue) {
        return (255 << 24) | ((red & 255) << 16) | ((green & 255) << 8) | (blue & 255);
    }

    static int intFromLstar(float lstar) {
        if (lstar < 1) {
            return 0xff000000;
        } else if (lstar > 99) {
            return 0xffffffff;
        }

        // XYZ to LAB conversion routine, assume a and b are 0.
        float fy = (lstar + 16.0f) / 116.0f;

        // fz = fx = fy because a and b are 0
        float fz = fy;
        float fx = fy;

        float kappa = 24389f / 27f;
        float epsilon = 216f / 24389f;
        boolean lExceedsEpsilonKappa = (lstar > 8.0f);
        float yT = lExceedsEpsilonKappa ? fy * fy * fy : lstar / kappa;
        boolean cubeExceedEpsilon = (fy * fy * fy) > epsilon;
        float xT = cubeExceedEpsilon ? fx * fx * fx : (116f * fx - 16f) / kappa;
        float zT = cubeExceedEpsilon ? fz * fz * fz : (116f * fx - 16f) / kappa;

        return ColorUtils.XYZToColor(xT * CamUtils.WHITE_POINT_D65[0],
                yT * CamUtils.WHITE_POINT_D65[1], zT * CamUtils.WHITE_POINT_D65[2]);
    }

    /** Returns L* from L*a*b*, perceptual luminance, from an ARGB integer (ColorInt). */
    public static float lstarFromInt(int argb) {
        return lstarFromY(yFromInt(argb));
    }

    static float lstarFromY(float y) {
        y = y / 100.0f;
        final float e = 216.f / 24389.f;
        float yIntermediate;
        if (y <= e) {
            return ((24389.f / 27.f) * y);
        } else {
            yIntermediate = (float) Math.cbrt(y);
        }
        return 116.f * yIntermediate - 16.f;
    }

    static float yFromInt(int argb) {
        final float r = linearized(Color.red(argb));
        final float g = linearized(Color.green(argb));
        final float b = linearized(Color.blue(argb));
        double[][] matrix = SRGB_TO_XYZ;
        double y = (r * matrix[1][0]) + (g * matrix[1][1]) + (b * matrix[1][2]);
        return (float) y;
    }

    @NonNull
    static float[] xyzFromInt(int argb) {
        final float r = linearized(Color.red(argb));
        final float g = linearized(Color.green(argb));
        final float b = linearized(Color.blue(argb));

        double[][] matrix = SRGB_TO_XYZ;
        double x = (r * matrix[0][0]) + (g * matrix[0][1]) + (b * matrix[0][2]);
        double y = (r * matrix[1][0]) + (g * matrix[1][1]) + (b * matrix[1][2]);
        double z = (r * matrix[2][0]) + (g * matrix[2][1]) + (b * matrix[2][2]);
        return new float[]{(float) x, (float) y, (float) z};
    }

    /**
     * Converts an L* value to a Y value.
     *
     * <p>L* in L*a*b* and Y in XYZ measure the same quantity, luminance.
     *
     * <p>L* measures perceptual luminance, a linear scale. Y in XYZ measures relative luminance, a
     * logarithmic scale.
     *
     * @param lstar L* in L*a*b*
     * @return Y in XYZ
     */
    public static double yFromLstar(double lstar) {
        double ke = 8.0;
        if (lstar > ke) {
            return Math.pow((lstar + 16.0) / 116.0, 3.0) * 100.0;
        } else {
            return lstar / (24389.0 / 27.0) * 100.0;
        }
    }

    static float linearized(int rgbComponent) {
        float normalized = (float) rgbComponent / 255.0f;

        if (normalized <= 0.04045f) {
            return (normalized / 12.92f) * 100.0f;
        } else {
            return (float) Math.pow(((normalized + 0.055f) / 1.055f), 2.4f) * 100.0f;
        }
    }
}
