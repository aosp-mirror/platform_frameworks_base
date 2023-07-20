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

public final class Illuminants {
    // D65 as defined in ASTM E308
    public static double D65_X = 0.95047;
    public static double D65_Y = 1.0;
    public static double D65_Z = 1.08883;

    private Illuminants() { }

    public static CieXyzAbs getD65Abs(double luminance) {
        return new CieXyzAbs(D65_X * luminance, D65_Y * luminance, D65_Z * luminance);
    }
}
