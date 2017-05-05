/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.google.android.colorextraction.types;

import android.app.WallpaperColors;
import android.graphics.Color;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.graphics.ColorUtils;
import android.util.Log;
import android.util.MathUtils;
import android.util.Pair;

import com.google.android.colorextraction.ColorExtractor;

/**
 * Implementation of tonal color extraction
 */
public class Tonal implements ExtractionType {
    private static final String TAG = "Tonal";

    // Used for tonal palette fitting
    private static final float FIT_WEIGHT_H = 1.0f;
    private static final float FIT_WEIGHT_S = 1.0f;
    private static final float FIT_WEIGHT_L = 10.0f;

    private static final float MIN_COLOR_OCCURRENCE = 0.1f;
    private static final float MIN_LUMINOSITY = 0.5f;

    public void extractInto(WallpaperColors wallpaperColors,
            ColorExtractor.GradientColors gradientColors) {

        if (wallpaperColors.getColors().size() == 0) {
            return;
        }
        // Tonal is not really a sort, it takes a color from the extracted
        // palette and finds a best fit amongst a collection of pre-defined
        // palettes. The best fit is tweaked to be closer to the source color
        // and replaces the original palette

        // First find the most representative color in the image
        populationSort(wallpaperColors);
        // Calculate total
        int total = 0;
        for (Pair<Color, Integer> weightedColor : wallpaperColors.getColors()) {
            total += weightedColor.second;
        }

        // Get bright colors that occur often enough in this image
        Pair<Color, Integer> bestColor = null;
        float[] hsl = new float[3];
        for (Pair<Color, Integer> weightedColor : wallpaperColors.getColors()) {
            float colorOccurrence = weightedColor.second / (float) total;
            if (colorOccurrence < MIN_COLOR_OCCURRENCE) {
                break;
            }

            int colorValue = weightedColor.first.toArgb();
            ColorUtils.RGBToHSL(Color.red(colorValue), Color.green(colorValue),
                    Color.blue(colorValue), hsl);
            if (hsl[2] > MIN_LUMINOSITY) {
                bestColor = weightedColor;
            }
        }

        // Fallback to first color
        if (bestColor == null) {
            bestColor = wallpaperColors.getColors().get(0);
        }

        int colorValue = bestColor.first.toArgb();
        ColorUtils.RGBToHSL(Color.red(colorValue), Color.green(colorValue), Color.blue(colorValue),
                hsl);
        hsl[0] /= 360.0f; // normalize

        TonalPalette palette = findTonalPalette(hsl[0]);

        // Fall back to population sort if we couldn't find a tonal palette
        if (palette == null) {
            Log.w(TAG, "Could not find a tonal palette!");
            return;
        }

        int fitIndex = bestFit(palette, hsl[0], hsl[1], hsl[2]);
        if (fitIndex == -1) {
            Log.w(TAG, "Could not find best fit!");
            return;
        }
        float[] h = fit(palette.h, hsl[0], fitIndex,
                Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY);
        float[] s = fit(palette.s, hsl[1], fitIndex, 0.0f, 1.0f);
        float[] l = fit(palette.l, hsl[2], fitIndex, 0.0f, 1.0f);

        final int firstColorIndex = fitIndex;
        final int secondColorIndex = Math.min(fitIndex + 2, h.length - 1);

        hsl[0] = fract(h[firstColorIndex]) * 360.0f;
        hsl[1] = s[firstColorIndex];
        hsl[2] = l[firstColorIndex];
        gradientColors.setMainColor(ColorUtils.HSLToColor(hsl));

        hsl[0] = fract(h[secondColorIndex]) * 360.0f;
        hsl[1] = s[secondColorIndex];
        hsl[2] = l[secondColorIndex];
        gradientColors.setSecondaryColor(ColorUtils.HSLToColor(hsl));
    }

    private static void populationSort(@NonNull WallpaperColors wallpaperColors) {
        wallpaperColors.getColors().sort((a, b) -> b.second - a.second);
    }

    /**
     * Offsets all colors by a delta, clamping values that go beyond what's
     * supported on the color space.
     * @param data what you want to fit
     * @param v how big should be the offset
     * @param index which index to calculate the delta against
     * @param min minimum accepted value (clamp)
     * @param max maximum accepted value (clamp)
     * @return
     */
    private static float[] fit(float[] data, float v, int index, float min, float max) {
        float[] fitData = new float[data.length];
        float delta = v - data[index];

        for (int i = 0; i < data.length; i++) {
            fitData[i] = MathUtils.constrain(data[i] + delta, min, max);
        }

        return fitData;
    }

    /*function adjustSatLumForFit(val, points, fitIndex) {
        var fitValue = lerpBetweenPoints(points, fitIndex);
        var diff = val - fitValue;

        var newPoints = [];
        for (var ii=0; ii<points.length; ii++) {
            var point = [points[ii][0], points[ii][1]];
            point[1] += diff;
            if (point[1] > 1) point[1] = 1;
            if (point[1] < 0) point[1] = 0;
            newPoints[ii] = point;
        }
        return newPoints;
    }*/

    /**
     * Finds the closest color in a palette, given another HSL color
     *
     * @param palette where to search
     * @param h hue
     * @param s saturation
     * @param l lightness
     * @return closest index or -1 if palette is empty.
     */
    private static int bestFit(@NonNull TonalPalette palette, float h, float s, float l) {
        int minErrorIndex = -1;
        float minError = Float.POSITIVE_INFINITY;

        for (int i = 0; i < palette.h.length; i++) {
            float error =
                    FIT_WEIGHT_H * Math.abs(h - palette.h[i])
                            + FIT_WEIGHT_S * Math.abs(s - palette.s[i])
                            + FIT_WEIGHT_L * Math.abs(l - palette.l[i]);
            if (error < minError) {
                minError = error;
                minErrorIndex = i;
            }
        }

        return minErrorIndex;
    }

    @Nullable
    private static TonalPalette findTonalPalette(float h) {
        TonalPalette best = null;
        float error = Float.POSITIVE_INFINITY;

        for (TonalPalette candidate : TONAL_PALETTES) {
            if (h >= candidate.minHue && h <= candidate.maxHue) {
                best = candidate;
                break;
            }

            if (candidate.maxHue > 1.0f && h >= 0.0f && h <= fract(candidate.maxHue)) {
                best = candidate;
                break;
            }

            if (candidate.minHue < 0.0f && h >= fract(candidate.minHue) && h <= 1.0f) {
                best = candidate;
                break;
            }

            if (h <= candidate.minHue && candidate.minHue - h < error) {
                best = candidate;
                error = candidate.minHue - h;
            } else if (h >= candidate.maxHue && h - candidate.maxHue < error) {
                best = candidate;
                error = h - candidate.maxHue;
            } else if (candidate.maxHue > 1.0f && h >= fract(candidate.maxHue)
                    && h - fract(candidate.maxHue) < error) {
                best = candidate;
                error = h - fract(candidate.maxHue);
            } else if (candidate.minHue < 0.0f && h <= fract(candidate.minHue)
                    && fract(candidate.minHue) - h < error) {
                best = candidate;
                error = fract(candidate.minHue) - h;
            }
        }

        return best;
    }

    private static float fract(float v) {
        return v - (float) Math.floor(v);
    }

    static class TonalPalette {
        final float[] h;
        final float[] s;
        final float[] l;
        final float minHue;
        final float maxHue;

        TonalPalette(float[] h, float[] s, float[] l) {
            this.h = h;
            this.s = s;
            this.l = l;

            float minHue = Float.POSITIVE_INFINITY;
            float maxHue = Float.NEGATIVE_INFINITY;

            for (float v : h) {
                minHue = Math.min(v, minHue);
                maxHue = Math.max(v, maxHue);
            }

            this.minHue = minHue;
            this.maxHue = maxHue;
        }
    }

    // Data definition of Material Design tonal palettes
    // When the sort type is set to TONAL, these palettes are used to find
    // a best fit. Each palette is defined as 22 HSL colors
    private static final TonalPalette[] TONAL_PALETTES = {
            new TonalPalette(
                    new float[]{0, 0.991f, 1, 0.9833333333333333f, 2, 0f, 3, 0f, 4, 0f, 5,
                            0.01134380453752181f, 6, 0.015625000000000003f, 7,
                            0.024193548387096798f,
                            8, 0.027397260273972573f, 9, 0.017543859649122865f},
                    new float[]{0, 1f, 1, 1f, 2, 1f, 3, 1f, 4, 0.8434782608695652f, 5, 1f, 6, 1f, 7,
                            1f, 8, 1f, 9, 1f},
                    new float[]{0, 0.2f, 1, 0.27450980392156865f, 2, 0.34901960784313724f, 3,
                            0.4235294117647059f, 4, 0.5490196078431373f, 5, 0.6254901960784314f, 6,
                            0.6862745098039216f, 7, 0.7568627450980392f, 8, 0.8568627450980393f, 9,
                            0.9254901960784314f}
            ),
            new TonalPalette(
                    new float[]{0, 0.6385767790262171f, 1, 0.6301169590643275f, 2,
                            0.6223958333333334f, 3, 0.6151079136690647f, 4, 0.6065400843881856f, 5,
                            0.5986964618249534f, 6, 0.5910746812386157f, 7, 0.5833333333333334f, 8,
                            0.5748031496062993f, 9, 0.5582010582010583f},
                    new float[]{0, 1f, 1, 1f, 2, 0.9014084507042253f, 3, 0.8128654970760234f, 4,
                            0.7979797979797981f, 5, 0.7816593886462883f, 6, 0.778723404255319f, 7,
                            1f, 8, 1f, 9, 1f},
                    new float[]{0, 0.17450980392156862f, 1, 0.2235294117647059f, 2,
                            0.2784313725490196f, 3, 0.3352941176470588f, 4, 0.388235294117647f, 5,
                            0.44901960784313727f, 6, 0.5392156862745098f, 7, 0.6509803921568628f, 8,
                            0.7509803921568627f, 9, 0.8764705882352941f}
            ),
            new TonalPalette(
                    new float[]{0, 0.5669934640522876f, 1, 0.5748031496062993f, 2,
                            0.5595238095238095f, 3, 0.5473118279569893f, 4, 0.5393258426966292f, 5,
                            0.5315955766192734f, 6, 0.524031007751938f, 7, 0.5154711673699016f, 8,
                            0.508080808080808f, 9, 0.5f},
                    new float[]{0, 1f, 1, 1f, 2, 1f, 3, 1f, 4, 1f, 5, 1f, 6, 0.8847736625514403f, 7,
                            1f, 8, 1f, 9, 1f},
                    new float[]{0, 0.2f, 1, 0.24901960784313726f, 2, 0.27450980392156865f, 3,
                            0.30392156862745096f, 4, 0.34901960784313724f, 5, 0.4137254901960784f,
                            6, 0.47647058823529415f, 7, 0.5352941176470588f, 8, 0.6764705882352942f,
                            9, 0.8f}
            ),
            new TonalPalette(
                    new float[]{0, 0.5082304526748972f, 1, 0.5069444444444444f, 2, 0.5f, 3, 0.5f, 4,
                            0.5f, 5, 0.48724954462659376f, 6, 0.4800347222222222f, 7,
                            0.4755134281200632f, 8, 0.4724409448818897f, 9, 0.4671052631578947f},
                    new float[]{0, 1f, 1, 0.8888888888888887f, 2, 0.9242424242424242f, 3, 1f, 4, 1f,
                            5, 0.8133333333333332f, 6, 0.7868852459016393f, 7, 1f, 8, 1f, 9, 1f},
                    new float[]{0, 0.1588235294117647f, 1, 0.21176470588235297f, 2,
                            0.25882352941176473f, 3, 0.3f, 4, 0.34901960784313724f, 5,
                            0.44117647058823534f, 6, 0.5215686274509804f, 7, 0.5862745098039216f, 8,
                            0.7509803921568627f, 9, 0.8509803921568627f}
            ),
            new TonalPalette(
                    new float[]{0, 0.3333333333333333f, 1, 0.3333333333333333f, 2,
                            0.34006734006734f, 3, 0.34006734006734f, 4, 0.34006734006734f, 5,
                            0.34259259259259256f, 6, 0.3475783475783476f, 7, 0.34767025089605735f,
                            8, 0.3467741935483871f, 9, 0.3703703703703704f},
                    new float[]{0, 0.6703296703296703f, 1, 0.728813559322034f, 2,
                            0.5657142857142856f, 3, 0.5076923076923077f, 4, 0.3944223107569721f, 5,
                            0.6206896551724138f, 6, 0.8931297709923666f, 7, 1f, 8, 1f, 9, 1f},
                    new float[]{0, 0.1784313725490196f, 1, 0.23137254901960785f, 2,
                            0.3431372549019608f, 3, 0.38235294117647056f, 4, 0.49215686274509807f,
                            5, 0.6588235294117647f, 6, 0.7431372549019608f, 7, 0.8176470588235294f,
                            8, 0.8784313725490196f, 9, 0.9294117647058824f}
            ),
            new TonalPalette(
                    new float[]{0, 0.162280701754386f, 1, 0.15032679738562088f, 2,
                            0.15879265091863518f, 3, 0.16236559139784948f, 4, 0.17443868739205526f,
                            5, 0.17824074074074076f, 6, 0.18674698795180725f, 7,
                            0.18692449355432778f, 8, 0.1946778711484594f, 9, 0.18604651162790695f},
                    new float[]{0, 1f, 1, 1f, 2, 1f, 3, 1f, 4, 1f, 5, 1f, 6, 1f, 7, 1f, 8, 1f, 9,
                            1f},
                    new float[]{0, 0.14901960784313725f, 1, 0.2f, 2, 0.24901960784313726f, 3,
                            0.30392156862745096f, 4, 0.3784313725490196f, 5, 0.4235294117647059f, 6,
                            0.48823529411764705f, 7, 0.6450980392156863f, 8, 0.7666666666666666f, 9,
                            0.8313725490196078f}
            ),
            new TonalPalette(
                    new float[]{0, 0.10619469026548674f, 1, 0.11924686192468618f, 2,
                            0.13046448087431692f, 3, 0.14248366013071895f, 4, 0.1506024096385542f,
                            5, 0.16220238095238093f, 6, 0.16666666666666666f, 7,
                            0.16666666666666666f, 8, 0.162280701754386f, 9, 0.15686274509803924f},
                    new float[]{0, 1f, 1, 1f, 2, 1f, 3, 1f, 4, 1f, 5, 1f, 6, 1f, 7, 1f, 8, 1f, 9,
                            1f},
                    new float[]{0, 0.44313725490196076f, 1, 0.46862745098039216f, 2,
                            0.47843137254901963f, 3, 0.5f, 4, 0.5117647058823529f, 5,
                            0.5607843137254902f, 6, 0.6509803921568628f, 7, 0.7509803921568627f, 8,
                            0.8509803921568627f, 9, 0.9f}
            ),
            new TonalPalette(
                    new float[]{0, 0.03561253561253561f, 1, 0.05098039215686275f, 2,
                            0.07516339869281045f, 3, 0.09477124183006536f, 4, 0.1150326797385621f,
                            5, 0.134640522875817f, 6, 0.14640522875816991f, 7, 0.1582397003745319f,
                            8, 0.15773809523809523f, 9, 0.15359477124183002f},
                    new float[]{0, 1f, 1, 1f, 2, 1f, 3, 1f, 4, 1f, 5, 1f, 6, 1f, 7, 1f, 8, 1f, 9,
                            1f},
                    new float[]{0, 0.4588235294117647f, 1, 0.5f, 2, 0.5f, 3, 0.5f, 4, 0.5f, 5, 0.5f,
                            6, 0.5f, 7, 0.6509803921568628f, 8, 0.7803921568627451f, 9, 0.9f}
            ),
            new TonalPalette(
                    new float[]{0, 0.9596491228070175f, 1, 0.9593837535014005f, 2,
                            0.9514767932489452f, 3, 0.943859649122807f, 4, 0.9396825396825397f, 5,
                            0.9395424836601307f, 6, 0.9393939393939394f, 7, 0.9362745098039216f, 8,
                            0.9754098360655739f, 9, 0.9824561403508771f},
                    new float[]{0, 0.84070796460177f, 1, 0.8206896551724138f, 2,
                            0.7979797979797981f, 3, 0.7661290322580644f, 4, 0.9051724137931036f, 5,
                            1f, 6, 1f, 7, 1f, 8, 1f, 9, 1f},
                    new float[]{0, 0.22156862745098038f, 1, 0.2843137254901961f, 2,
                            0.388235294117647f, 3, 0.48627450980392156f, 4, 0.5450980392156863f, 5,
                            0.6f, 6, 0.6764705882352942f, 7, 0.8f, 8, 0.8803921568627451f, 9,
                            0.9254901960784314f}
            ),
            new TonalPalette(
                    new float[]{0, 0.841025641025641f, 1, 0.8333333333333334f, 2,
                            0.8285256410256411f, 3, 0.821522309711286f, 4, 0.8083333333333333f, 5,
                            0.8046594982078853f, 6, 0.8005822416302766f, 7, 0.7842377260981912f, 8,
                            0.7771084337349398f, 9, 0.7747747747747749f},
                    new float[]{0, 1f, 1, 1f, 2, 1f, 3, 1f, 4, 1f, 5, 1f, 6, 1f, 7,
                            0.737142857142857f, 8, 0.6434108527131781f, 9, 0.46835443037974644f},
                    new float[]{0, 0.12745098039215685f, 1, 0.15490196078431373f, 2,
                            0.20392156862745098f, 3, 0.24901960784313726f, 4, 0.3137254901960784f,
                            5, 0.36470588235294116f, 6, 0.44901960784313727f, 7,
                            0.6568627450980392f, 8, 0.7470588235294118f, 9, 0.8450980392156863f}
            ),
            new TonalPalette(
                    new float[]{0, 0f, 1, 0f, 2, 0f, 3, 0f, 4, 0f, 5, 0f, 6, 0f, 7, 0f, 8, 0f, 9,
                            0f},
                    new float[]{0, 0f, 1, 0f, 2, 0f, 3, 0f, 4, 0f, 5, 0f, 6, 0f, 7, 0f, 8, 0f, 9,
                            0f},
                    new float[]{0, 0.14901960784313725f, 1, 0.2f, 2, 0.2980392156862745f, 3, 0.4f,
                            4, 0.4980392156862745f, 5, 0.6196078431372549f, 6, 0.7176470588235294f,
                            7, 0.8196078431372549f, 8, 0.9176470588235294f, 9, 0.9490196078431372f}
            ),
            new TonalPalette(
                    new float[]{0, 0.955952380952381f, 1, 0.9681069958847737f, 2,
                            0.9760479041916167f, 3, 0.9873563218390804f, 4, 0f, 5, 0f, 6,
                            0.009057971014492771f, 7, 0.026748971193415648f, 8,
                            0.041666666666666616f, 9, 0.05303030303030304f},
                    new float[]{0, 1f, 1, 0.8350515463917526f, 2, 0.6929460580912863f, 3,
                            0.6387665198237885f, 4, 0.6914893617021276f, 5, 0.7583892617449666f, 6,
                            0.8070175438596495f, 7, 0.9310344827586209f, 8, 1f, 9, 1f},
                    new float[]{0, 0.27450980392156865f, 1, 0.3803921568627451f, 2,
                            0.4725490196078432f, 3, 0.5549019607843138f, 4, 0.6313725490196078f, 5,
                            0.707843137254902f, 6, 0.7764705882352941f, 7, 0.8294117647058823f, 8,
                            0.9058823529411765f, 9, 0.9568627450980391f}
            ),
            new TonalPalette(
                    new float[]{0, 0.7514619883040936f, 1, 0.7679738562091503f, 2,
                            0.7802083333333333f, 3, 0.7844311377245509f, 4, 0.796875f, 5,
                            0.8165618448637316f, 6, 0.8487179487179487f, 7, 0.8582375478927203f, 8,
                            0.8562091503267975f, 9, 0.8666666666666667f},
                    new float[]{0, 1f, 1, 1f, 2, 0.8163265306122449f, 3, 0.6653386454183268f, 4,
                            0.7547169811320753f, 5, 0.929824561403509f, 6, 0.9558823529411766f, 7,
                            0.9560439560439562f, 8, 1f, 9, 1f},
                    new float[]{0, 0.2235294117647059f, 1, 0.3f, 2, 0.38431372549019605f, 3,
                            0.492156862745098f, 4, 0.5843137254901961f, 5, 0.6647058823529411f, 6,
                            0.7333333333333334f, 7, 0.8215686274509804f, 8, 0.9f, 9,
                            0.9411764705882353f}
            ),
            new TonalPalette(
                    new float[]{0, 0.6666666666666666f, 1, 0.6666666666666666f, 2,
                            0.6666666666666666f, 3, 0.6666666666666666f, 4, 0.6666666666666666f, 5,
                            0.6666666666666666f, 6, 0.6666666666666666f, 7, 0.6666666666666666f, 8,
                            0.6666666666666666f, 9, 0.6666666666666666f},
                    new float[]{0, 0.24590163934426232f, 1, 0.17880794701986752f, 2,
                            0.14606741573033713f, 3, 0.13761467889908252f, 4, 0.14893617021276592f,
                            5, 0.16756756756756758f, 6, 0.20312500000000017f, 7,
                            0.26086956521739135f, 8, 0.29999999999999966f, 9, 0.5000000000000004f},
                    new float[]{0, 0.2392156862745098f, 1, 0.296078431372549f, 2,
                            0.34901960784313724f, 3, 0.4274509803921569f, 4, 0.5392156862745098f, 5,
                            0.6372549019607843f, 6, 0.7490196078431373f, 7, 0.8196078431372549f, 8,
                            0.8823529411764706f, 9, 0.9372549019607843f}
            ),
            new TonalPalette(
                    new float[]{0, 0.9678571428571429f, 1, 0.9944812362030905f, 2, 0f, 3, 0f, 4,
                            0.0047348484848484815f, 5, 0.00316455696202532f, 6, 0f, 7,
                            0.9980392156862745f, 8, 0.9814814814814816f, 9, 0.9722222222222221f},
                    new float[]{0, 1f, 1, 0.7023255813953488f, 2, 0.6638655462184874f, 3,
                            0.6521739130434782f, 4, 0.7719298245614035f, 5, 0.8315789473684211f, 6,
                            0.6867469879518071f, 7, 0.7264957264957265f, 8, 0.8181818181818182f, 9,
                            0.8181818181818189f},
                    new float[]{0, 0.27450980392156865f, 1, 0.4215686274509804f, 2,
                            0.4666666666666667f, 3, 0.503921568627451f, 4, 0.5529411764705883f, 5,
                            0.6274509803921569f, 6, 0.6745098039215687f, 7, 0.7705882352941176f, 8,
                            0.892156862745098f, 9, 0.9568627450980391f}
            ),
            new TonalPalette(
                    new float[]{0, 0.9052287581699346f, 1, 0.9112021857923498f, 2,
                            0.9270152505446624f, 3, 0.9343137254901961f, 4, 0.9391534391534391f, 5,
                            0.9437984496124031f, 6, 0.943661971830986f, 7, 0.9438943894389439f, 8,
                            0.9426229508196722f, 9, 0.9444444444444444f},
                    new float[]{0, 1f, 1, 0.8133333333333332f, 2, 0.7927461139896375f, 3,
                            0.7798165137614679f, 4, 0.7777777777777779f, 5, 0.8190476190476191f, 6,
                            0.8255813953488372f, 7, 0.8211382113821142f, 8, 0.8133333333333336f, 9,
                            0.8000000000000006f},
                    new float[]{0, 0.2f, 1, 0.29411764705882354f, 2, 0.3784313725490196f, 3,
                            0.42745098039215684f, 4, 0.4764705882352941f, 5, 0.5882352941176471f, 6,
                            0.6627450980392157f, 7, 0.7588235294117647f, 8, 0.8529411764705882f, 9,
                            0.9411764705882353f}
            ),
            new TonalPalette(
                    new float[]{0, 0.6884057971014492f, 1, 0.6974789915966387f, 2,
                            0.7079889807162534f, 3, 0.7154471544715447f, 4, 0.7217741935483872f, 5,
                            0.7274143302180687f, 6, 0.7272727272727273f, 7, 0.7258064516129031f, 8,
                            0.7252252252252251f, 9, 0.7333333333333333f},
                    new float[]{0, 0.8214285714285715f, 1, 0.6878612716763006f, 2,
                            0.6080402010050251f, 3, 0.5774647887323943f, 4, 0.5391304347826086f, 5,
                            0.46724890829694316f, 6, 0.4680851063829788f, 7, 0.462686567164179f, 8,
                            0.45679012345678977f, 9, 0.4545454545454551f},
                    new float[]{0, 0.2196078431372549f, 1, 0.33921568627450976f, 2,
                            0.39019607843137255f, 3, 0.4176470588235294f, 4, 0.45098039215686275f,
                            5, 0.5509803921568628f, 6, 0.6313725490196078f, 7, 0.7372549019607844f,
                            8, 0.8411764705882353f, 9, 0.9352941176470588f}
            ),
            new TonalPalette(
                    new float[]{0, 0.6470588235294118f, 1, 0.6516666666666667f, 2,
                            0.6464174454828661f, 3, 0.6441441441441442f, 4, 0.6432748538011696f, 5,
                            0.6416666666666667f, 6, 0.6402439024390243f, 7, 0.6412429378531074f, 8,
                            0.6435185185185186f, 9, 0.6428571428571429f},
                    new float[]{0, 0.8095238095238095f, 1, 0.6578947368421053f, 2,
                            0.5721925133689839f, 3, 0.5362318840579711f, 4, 0.5f, 5,
                            0.4424778761061947f, 6, 0.44086021505376327f, 7, 0.44360902255639095f,
                            8, 0.4499999999999997f, 9, 0.4375000000000006f},
                    new float[]{0, 0.16470588235294117f, 1, 0.2980392156862745f, 2,
                            0.36666666666666664f, 3, 0.40588235294117647f, 4, 0.44705882352941173f,
                            5, 0.5568627450980392f, 6, 0.6352941176470588f, 7, 0.7392156862745098f,
                            8, 0.8431372549019608f, 9, 0.9372549019607843f}
            ),
            new TonalPalette(
                    new float[]{0, 0.46732026143790845f, 1, 0.4718614718614719f, 2,
                            0.4793650793650794f, 3, 0.48071625344352614f, 4, 0.4829683698296837f, 5,
                            0.484375f, 6, 0.4841269841269842f, 7, 0.48444444444444457f, 8,
                            0.48518518518518516f, 9, 0.4907407407407408f},
                    new float[]{0, 1f, 1, 1f, 2, 1f, 3, 1f, 4, 1f, 5, 0.6274509803921569f, 6,
                            0.41832669322709176f, 7, 0.41899441340782106f, 8, 0.4128440366972478f,
                            9, 0.4090909090909088f},
                    new float[]{0, 0.1f, 1, 0.15098039215686274f, 2, 0.20588235294117646f, 3,
                            0.2372549019607843f, 4, 0.26862745098039215f, 5, 0.4f, 6,
                            0.5078431372549019f, 7, 0.6490196078431372f, 8, 0.7862745098039216f, 9,
                            0.9137254901960784f}
            ),
            new TonalPalette(
                    new float[]{0, 0.5444444444444444f, 1, 0.5555555555555556f, 2,
                            0.5555555555555556f, 3, 0.553763440860215f, 4, 0.5526315789473684f, 5,
                            0.5555555555555556f, 6, 0.5555555555555555f, 7, 0.5555555555555556f, 8,
                            0.5512820512820514f, 9, 0.5666666666666667f},
                    new float[]{0, 0.24590163934426232f, 1, 0.19148936170212766f, 2,
                            0.1791044776119403f, 3, 0.18343195266272191f, 4, 0.18446601941747576f,
                            5, 0.1538461538461539f, 6, 0.15625000000000003f, 7,
                            0.15328467153284678f, 8, 0.15662650602409653f, 9, 0.151515151515151f},
                    new float[]{0, 0.1196078431372549f, 1, 0.1843137254901961f, 2,
                            0.2627450980392157f, 3, 0.33137254901960783f, 4, 0.403921568627451f, 5,
                            0.5411764705882354f, 6, 0.6235294117647059f, 7, 0.7313725490196079f, 8,
                            0.8372549019607843f, 9, 0.9352941176470588f}
            ),
            new TonalPalette(
                    new float[]{0, 0.022222222222222223f, 1, 0.02469135802469136f, 2,
                            0.031249999999999997f, 3, 0.03947368421052631f, 4, 0.04166666666666668f,
                            5, 0.043650793650793655f, 6, 0.04411764705882352f, 7,
                            0.04166666666666652f, 8, 0.04444444444444459f, 9, 0.05555555555555529f},
                    new float[]{0, 0.33333333333333337f, 1, 0.2783505154639175f, 2,
                            0.2580645161290323f, 3, 0.25675675675675674f, 4, 0.2528735632183908f, 5,
                            0.17500000000000002f, 6, 0.15315315315315312f, 7, 0.15189873417721522f,
                            8, 0.15789473684210534f, 9, 0.15789473684210542f},
                    new float[]{0, 0.08823529411764705f, 1, 0.19019607843137254f, 2,
                            0.2431372549019608f, 3, 0.2901960784313725f, 4, 0.3411764705882353f, 5,
                            0.47058823529411764f, 6, 0.5647058823529412f, 7, 0.6901960784313725f, 8,
                            0.8137254901960784f, 9, 0.9254901960784314f}
            ),
            new TonalPalette(
                    new float[]{0, 0.050884955752212385f, 1, 0.07254901960784313f, 2,
                            0.0934640522875817f, 3, 0.10457516339869281f, 4, 0.11699346405228758f,
                            5, 0.1255813953488372f, 6, 0.1268939393939394f, 7, 0.12533333333333332f,
                            8, 0.12500000000000003f, 9, 0.12777777777777777f},
                    new float[]{0, 1f, 1, 1f, 2, 1f, 3, 1f, 4, 1f, 5, 1f, 6, 1f, 7, 1f, 8, 1f, 9,
                            1f},
                    new float[]{0, 0.44313725490196076f, 1, 0.5f, 2, 0.5f, 3, 0.5f, 4, 0.5f, 5,
                            0.5784313725490196f, 6, 0.6549019607843137f, 7, 0.7549019607843137f, 8,
                            0.8509803921568627f, 9, 0.9411764705882353f}
            )
    };
}
