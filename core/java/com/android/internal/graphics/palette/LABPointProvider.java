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

package com.android.internal.graphics.palette;

import android.graphics.Color;
import android.graphics.ColorSpace;

/**
 *  Allows quantizers to operate in the L*a*b* colorspace.
 *  L*a*b* is a good choice for measuring distance between colors.
 *  Better spaces, and better distance calculations even in L*a*b* exist, but measuring distance
 *  in L*a*b* space, also known as deltaE, is a universally accepted standard across industries
 *  and worldwide.
 */
public class LABPointProvider implements PointProvider {
    final ColorSpace.Connector mRgbToLab;
    final ColorSpace.Connector mLabToRgb;

    public LABPointProvider() {
        mRgbToLab = ColorSpace.connect(
                ColorSpace.get(ColorSpace.Named.SRGB),
                ColorSpace.get(ColorSpace.Named.CIE_LAB));
        mLabToRgb = ColorSpace.connect(ColorSpace.get(ColorSpace.Named.CIE_LAB),
                ColorSpace.get(ColorSpace.Named.SRGB));
    }

    @Override
    public float[] fromInt(int color) {
        float r = Color.red(color) / 255.f;
        float g =  Color.green(color) / 255.f;
        float b = Color.blue(color) / 255.f;

        float[] transform = mRgbToLab.transform(r, g, b);
        return transform;
    }

    @Override
    public int toInt(float[] centroid) {
        float[] rgb = mLabToRgb.transform(centroid);
        int color = Color.rgb(rgb[0], rgb[1], rgb[2]);
        return color;
    }

    @Override
    public float distance(float[] a, float[] b) {
        // Standard v1 CIELAB deltaE formula, 1976 - easily improved upon, however,
        // improvements do not significantly impact the Palette algorithm's results.
        double dL = a[0] - b[0];
        double dA = a[1] - b[1];
        double dB = a[2] - b[2];
        return (float) (dL * dL + dA * dA + dB * dB);
    }
}
