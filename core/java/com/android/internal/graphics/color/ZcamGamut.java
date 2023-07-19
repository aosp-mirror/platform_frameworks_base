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

public final class ZcamGamut {
    private static final double EPSILON = 0.0001;

    private ZcamGamut() { }

    private static double evalLine(double slope, double intercept, double x) {
        return slope * x + intercept;
    }

    private static int clip(double l1, double c1, double hue, double l0) {
        CieXyzAbs result = Zcam.toXyzAbs(l1, c1, hue);
        if (result.isInGamut()) {
            return result.toRgb8();
        }

        // Avoid searching black and white for performance
        if (l1 <= EPSILON) {
            return 0x000000;
        } else if (l1 >= 100.0 - EPSILON) {
            return 0xffffff;
        }

        // Chroma is always 0 so the reference point is guaranteed to be within gamut
        double c0 = 0.0;

        // Create a line - x=C, y=L - intersecting a hue plane
        // In theory, we could have a divide-by-zero error here if c1=0. However, that's not a problem because
        // all colors with chroma = 0 should be in gamut, so this loop never runs. Even if this loop somehow
        // ends up running for such a color, it would just result in a slow search that doesn't converge because
        // the NaN causes isInGamut() to return false.
        double slope = (l1 - l0) / (c1 - c0);
        double intercept = l0 - slope * c0;

        double lo = 0.0;
        double hi = c1;

        while (Math.abs(hi - lo) > EPSILON) {
            double midC = (lo + hi) / 2.0;
            double midL = evalLine(slope, intercept, midC);

            result = Zcam.toXyzAbs(midL, midC, hue);

            if (!result.isInGamut()) {
                // If this color isn't in gamut, pivot left to get an in-gamut color.
                hi = midC;
            } else {
                // If this color is in gamut, test a point to the right that should be just outside the gamut.
                // If the test point is *not* in gamut, we know that this color is right at the edge of the gamut.
                double midC2 = midC + EPSILON;
                double midL2 = evalLine(slope, intercept, midC2);

                CieXyzAbs ptOutside = Zcam.toXyzAbs(midL2, midC2, hue);
                if (ptOutside.isInGamut()) {
                    lo = midC;
                } else {
                    break;
                }
            }
        }

        return result.toRgb8();
    }

    public static int clipToRgb8(Zcam color) {
        return clip(color.lightness, color.chroma, color.hue, color.lightness);
    }
}
