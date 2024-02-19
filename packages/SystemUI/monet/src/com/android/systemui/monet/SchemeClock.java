/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.monet;

import static com.google.ux.material.libmonet.utils.MathUtils.clampDouble;

import static java.lang.Double.max;

import com.google.ux.material.libmonet.hct.Hct;
import com.google.ux.material.libmonet.palettes.TonalPalette;
import com.google.ux.material.libmonet.scheme.DynamicScheme;
import com.google.ux.material.libmonet.scheme.Variant;

public class SchemeClock extends DynamicScheme {
    public SchemeClock(Hct sourceColorHct, boolean isDark, double contrastLevel) {
        super(
                sourceColorHct,
                Variant.MONOCHROME,
                isDark,
                contrastLevel,
                /*primary*/
                TonalPalette.fromHueAndChroma(
                        /*hue*/ sourceColorHct.getHue(),
                        /*chroma*/ max(sourceColorHct.getChroma(), 20)
                ),
                /*secondary*/
                TonalPalette.fromHueAndChroma(
                        /*hue*/ sourceColorHct.getHue() + 10.0,
                        /*chroma*/ clampDouble(17, 40, sourceColorHct.getChroma() * 0.85)
                ),
                /*tertiary*/
                TonalPalette.fromHueAndChroma(
                        /*hue*/ sourceColorHct.getHue() + 20.0,
                        /*chroma*/ max(sourceColorHct.getChroma() + 20, 50)
                ),

                //not used
                TonalPalette.fromHueAndChroma(sourceColorHct.getHue(), 0.0),
                TonalPalette.fromHueAndChroma(sourceColorHct.getHue(), 0.0));
    }
}
