/*
 * Copyright 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util.custom.palette;

import java.util.List;

import com.android.internal.util.custom.palette.Palette.Swatch;

/**
 * @hide
 */
class DefaultGenerator extends Palette.Generator {

    private static final float TARGET_DARK_LUMA = 0.26f;
    private static final float MAX_DARK_LUMA = 0.45f;

    private static final float MIN_LIGHT_LUMA = 0.55f;
    private static final float TARGET_LIGHT_LUMA = 0.74f;

    private static final float MIN_NORMAL_LUMA = 0.3f;
    private static final float TARGET_NORMAL_LUMA = 0.5f;
    private static final float MAX_NORMAL_LUMA = 0.7f;

    private static final float TARGET_MUTED_SATURATION = 0.3f;
    private static final float MAX_MUTED_SATURATION = 0.4f;

    private static final float TARGET_VIBRANT_SATURATION = 1f;
    private static final float MIN_VIBRANT_SATURATION = 0.35f;

    private static final float WEIGHT_SATURATION = 3f;
    private static final float WEIGHT_LUMA = 6f;
    private static final float WEIGHT_POPULATION = 1f;

    private List<Swatch> mSwatches;

    private int mHighestPopulation;

    private Swatch mVibrantSwatch;
    private Swatch mMutedSwatch;
    private Swatch mDarkVibrantSwatch;
    private Swatch mDarkMutedSwatch;
    private Swatch mLightVibrantSwatch;
    private Swatch mLightMutedSwatch;

    @Override
    public void generate(final List<Swatch> swatches) {
        mSwatches = swatches;

        mHighestPopulation = findMaxPopulation();

        generateVariationColors();

        // Now try and generate any missing colors
        generateEmptySwatches();
    }

    @Override
    public Swatch getVibrantSwatch() {
        return mVibrantSwatch;
    }

    @Override
    public Swatch getLightVibrantSwatch() {
        return mLightVibrantSwatch;
    }

    @Override
    public Swatch getDarkVibrantSwatch() {
        return mDarkVibrantSwatch;
    }

    @Override
    public Swatch getMutedSwatch() {
        return mMutedSwatch;
    }

    @Override
    public Swatch getLightMutedSwatch() {
        return mLightMutedSwatch;
    }

    @Override
    public Swatch getDarkMutedSwatch() {
        return mDarkMutedSwatch;
    }

    private void generateVariationColors() {
        mVibrantSwatch = findColorVariation(TARGET_NORMAL_LUMA, MIN_NORMAL_LUMA, MAX_NORMAL_LUMA,
                TARGET_VIBRANT_SATURATION, MIN_VIBRANT_SATURATION, 1f);

        mLightVibrantSwatch = findColorVariation(TARGET_LIGHT_LUMA, MIN_LIGHT_LUMA, 1f,
                TARGET_VIBRANT_SATURATION, MIN_VIBRANT_SATURATION, 1f);

        mDarkVibrantSwatch = findColorVariation(TARGET_DARK_LUMA, 0f, MAX_DARK_LUMA,
                TARGET_VIBRANT_SATURATION, MIN_VIBRANT_SATURATION, 1f);

        mMutedSwatch = findColorVariation(TARGET_NORMAL_LUMA, MIN_NORMAL_LUMA, MAX_NORMAL_LUMA,
                TARGET_MUTED_SATURATION, 0f, MAX_MUTED_SATURATION);

        mLightMutedSwatch = findColorVariation(TARGET_LIGHT_LUMA, MIN_LIGHT_LUMA, 1f,
                TARGET_MUTED_SATURATION, 0f, MAX_MUTED_SATURATION);

        mDarkMutedSwatch = findColorVariation(TARGET_DARK_LUMA, 0f, MAX_DARK_LUMA,
                TARGET_MUTED_SATURATION, 0f, MAX_MUTED_SATURATION);
    }

    /**
     * Try and generate any missing swatches from the swatches we did find.
     */
    private void generateEmptySwatches() {
        if (mVibrantSwatch == null) {
            // If we do not have a vibrant color...
            if (mDarkVibrantSwatch != null) {
                // ...but we do have a dark vibrant, generate the value by modifying the luma
                final float[] newHsl = copyHslValues(mDarkVibrantSwatch);
                newHsl[2] = TARGET_NORMAL_LUMA;
                mVibrantSwatch = new Swatch(ColorUtils.HSLToColor(newHsl), 0);
            }
        }

        if (mDarkVibrantSwatch == null) {
            // If we do not have a dark vibrant color...
            if (mVibrantSwatch != null) {
                // ...but we do have a vibrant, generate the value by modifying the luma
                final float[] newHsl = copyHslValues(mVibrantSwatch);
                newHsl[2] = TARGET_DARK_LUMA;
                mDarkVibrantSwatch = new Swatch(ColorUtils.HSLToColor(newHsl), 0);
            }
        }
    }

    /**
     * Find the {@link Palette.Swatch} with the highest population value and return the population.
     */
    private int findMaxPopulation() {
        int population = 0;
        for (Swatch swatch : mSwatches) {
            population = Math.max(population, swatch.getPopulation());
        }
        return population;
    }

    private Swatch findColorVariation(float targetLuma, float minLuma, float maxLuma,
            float targetSaturation, float minSaturation, float maxSaturation) {
        Swatch max = null;
        float maxValue = 0f;

        for (Swatch swatch : mSwatches) {
            final float sat = swatch.getHsl()[1];
            final float luma = swatch.getHsl()[2];

            if (sat >= minSaturation && sat <= maxSaturation &&
                    luma >= minLuma && luma <= maxLuma &&
                    !isAlreadySelected(swatch)) {
                float value = createComparisonValue(sat, targetSaturation, luma, targetLuma,
                        swatch.getPopulation(), mHighestPopulation);
                if (max == null || value > maxValue) {
                    max = swatch;
                    maxValue = value;
                }
            }
        }

        return max;
    }

    /**
     * @return true if we have already selected {@code swatch}
     */
    private boolean isAlreadySelected(Swatch swatch) {
        return mVibrantSwatch == swatch || mDarkVibrantSwatch == swatch ||
                mLightVibrantSwatch == swatch || mMutedSwatch == swatch ||
                mDarkMutedSwatch == swatch || mLightMutedSwatch == swatch;
    }

    private static float createComparisonValue(float saturation, float targetSaturation,
            float luma, float targetLuma,
            int population, int maxPopulation) {
        return createComparisonValue(saturation, targetSaturation, WEIGHT_SATURATION,
                luma, targetLuma, WEIGHT_LUMA,
                population, maxPopulation, WEIGHT_POPULATION);
    }

    private static float createComparisonValue(
            float saturation, float targetSaturation, float saturationWeight,
            float luma, float targetLuma, float lumaWeight,
            int population, int maxPopulation, float populationWeight) {
        return weightedMean(
                invertDiff(saturation, targetSaturation), saturationWeight,
                invertDiff(luma, targetLuma), lumaWeight,
                population / (float) maxPopulation, populationWeight
        );
    }

    /**
     * Copy a {@link Swatch}'s HSL values into a new float[].
     */
    private static float[] copyHslValues(Swatch color) {
        final float[] newHsl = new float[3];
        System.arraycopy(color.getHsl(), 0, newHsl, 0, 3);
        return newHsl;
    }

    /**
     * Returns a value in the range 0-1. 1 is returned when {@code value} equals the
     * {@code targetValue} and then decreases as the absolute difference between {@code value} and
     * {@code targetValue} increases.
     *
     * @param value the item's value
     * @param targetValue the value which we desire
     */
    private static float invertDiff(float value, float targetValue) {
        return 1f - Math.abs(value - targetValue);
    }

    private static float weightedMean(float... values) {
        float sum = 0f;
        float sumWeight = 0f;

        for (int i = 0; i < values.length; i += 2) {
            float value = values[i];
            float weight = values[i + 1];

            sum += (value * weight);
            sumWeight += weight;
        }

        return sum / sumWeight;
    }
}
