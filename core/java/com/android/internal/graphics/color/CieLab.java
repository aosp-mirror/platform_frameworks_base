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

public final class CieLab {
    private CieLab() { }

    public static double lToZcamJz(double l) {
        double fInvLp = fInv((l + 16.0) / 116.0);

        double xRel = Illuminants.D65_X * fInvLp;
        double yRel = Illuminants.D65_Y * fInvLp;
        double zRel = Illuminants.D65_Z * fInvLp;

        double x = xRel * CieXyzAbs.DEFAULT_SDR_WHITE_LUMINANCE;
        double y = yRel * CieXyzAbs.DEFAULT_SDR_WHITE_LUMINANCE;
        double z = zRel * CieXyzAbs.DEFAULT_SDR_WHITE_LUMINANCE;

        Zcam zcam = new Zcam(new CieXyzAbs(x, y, z));
        return zcam.lightness;
    }

    private static double fInv(double x) {
        if (x > 6.0/29.0) {
            return x * x * x;
        } else {
            return (108.0/841.0) * (x - 4.0/29.0);
        }
    }
}
