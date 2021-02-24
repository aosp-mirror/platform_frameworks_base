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

package com.android.internal.graphics.palette;

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

import android.annotation.FloatRange;

/**
 * Copied from: frameworks/support/v7/palette/src/main/java/android/support/v7/graphics/Target.java
 *
 * A class which allows custom selection of colors in a {@link Palette}'s generation. Instances
 * can be created via the {@link android.support.v7.graphics.Target.Builder} class.
 *
 * <p>To use the target, use the {@link Palette.Builder#addTarget(Target)} API when building a
 * Palette.</p>
 */
public final class Target {

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

    private static final float WEIGHT_SATURATION = 0.24f;
    private static final float WEIGHT_LUMA = 0.52f;
    private static final float WEIGHT_POPULATION = 0.24f;

    static final int INDEX_MIN = 0;
    static final int INDEX_TARGET = 1;
    static final int INDEX_MAX = 2;

    static final int INDEX_WEIGHT_SAT = 0;
    static final int INDEX_WEIGHT_LUMA = 1;
    static final int INDEX_WEIGHT_POP = 2;

    /**
     * A target which has the characteristics of a vibrant color which is light in luminance.
     */
    public static final Target LIGHT_VIBRANT;

    /**
     * A target which has the characteristics of a vibrant color which is neither light or dark.
     */
    public static final Target VIBRANT;

    /**
     * A target which has the characteristics of a vibrant color which is dark in luminance.
     */
    public static final Target DARK_VIBRANT;

    /**
     * A target which has the characteristics of a muted color which is light in luminance.
     */
    public static final Target LIGHT_MUTED;

    /**
     * A target which has the characteristics of a muted color which is neither light or dark.
     */
    public static final Target MUTED;

    /**
     * A target which has the characteristics of a muted color which is dark in luminance.
     */
    public static final Target DARK_MUTED;

    static {
        LIGHT_VIBRANT = new Target();
        setDefaultLightLightnessValues(LIGHT_VIBRANT);
        setDefaultVibrantSaturationValues(LIGHT_VIBRANT);

        VIBRANT = new Target();
        setDefaultNormalLightnessValues(VIBRANT);
        setDefaultVibrantSaturationValues(VIBRANT);

        DARK_VIBRANT = new Target();
        setDefaultDarkLightnessValues(DARK_VIBRANT);
        setDefaultVibrantSaturationValues(DARK_VIBRANT);

        LIGHT_MUTED = new Target();
        setDefaultLightLightnessValues(LIGHT_MUTED);
        setDefaultMutedSaturationValues(LIGHT_MUTED);

        MUTED = new Target();
        setDefaultNormalLightnessValues(MUTED);
        setDefaultMutedSaturationValues(MUTED);

        DARK_MUTED = new Target();
        setDefaultDarkLightnessValues(DARK_MUTED);
        setDefaultMutedSaturationValues(DARK_MUTED);
    }

    final float[] mSaturationTargets = new float[3];
    final float[] mLightnessTargets = new float[3];
    final float[] mWeights = new float[3];
    boolean mIsExclusive = true; // default to true

    Target() {
        setTargetDefaultValues(mSaturationTargets);
        setTargetDefaultValues(mLightnessTargets);
        setDefaultWeights();
    }

    Target(Target from) {
        System.arraycopy(from.mSaturationTargets, 0, mSaturationTargets, 0,
                mSaturationTargets.length);
        System.arraycopy(from.mLightnessTargets, 0, mLightnessTargets, 0,
                mLightnessTargets.length);
        System.arraycopy(from.mWeights, 0, mWeights, 0, mWeights.length);
    }

    /**
     * The minimum saturation value for this target.
     */
    @FloatRange(from = 0, to = 1)
    public float getMinimumSaturation() {
        return mSaturationTargets[INDEX_MIN];
    }

    /**
     * The target saturation value for this target.
     */
    @FloatRange(from = 0, to = 1)
    public float getTargetSaturation() {
        return mSaturationTargets[INDEX_TARGET];
    }

    /**
     * The maximum saturation value for this target.
     */
    @FloatRange(from = 0, to = 1)
    public float getMaximumSaturation() {
        return mSaturationTargets[INDEX_MAX];
    }

    /**
     * The minimum lightness value for this target.
     */
    @FloatRange(from = 0, to = 1)
    public float getMinimumLightness() {
        return mLightnessTargets[INDEX_MIN];
    }

    /**
     * The target lightness value for this target.
     */
    @FloatRange(from = 0, to = 1)
    public float getTargetLightness() {
        return mLightnessTargets[INDEX_TARGET];
    }

    /**
     * The maximum lightness value for this target.
     */
    @FloatRange(from = 0, to = 1)
    public float getMaximumLightness() {
        return mLightnessTargets[INDEX_MAX];
    }

    /**
     * Returns the weight of importance that this target places on a color's saturation within
     * the image.
     *
     * <p>The larger the weight, relative to the other weights, the more important that a color
     * being close to the target value has on selection.</p>
     *
     * @see #getTargetSaturation()
     */
    public float getSaturationWeight() {
        return mWeights[INDEX_WEIGHT_SAT];
    }

    /**
     * Returns the weight of importance that this target places on a color's lightness within
     * the image.
     *
     * <p>The larger the weight, relative to the other weights, the more important that a color
     * being close to the target value has on selection.</p>
     *
     * @see #getTargetLightness()
     */
    public float getLightnessWeight() {
        return mWeights[INDEX_WEIGHT_LUMA];
    }

    /**
     * Returns the weight of importance that this target places on a color's population within
     * the image.
     *
     * <p>The larger the weight, relative to the other weights, the more important that a
     * color's population being close to the most populous has on selection.</p>
     */
    public float getPopulationWeight() {
        return mWeights[INDEX_WEIGHT_POP];
    }

    /**
     * Returns whether any color selected for this target is exclusive for this target only.
     *
     * <p>If false, then the color can be selected for other targets.</p>
     */
    public boolean isExclusive() {
        return mIsExclusive;
    }

    private static void setTargetDefaultValues(final float[] values) {
        values[INDEX_MIN] = 0f;
        values[INDEX_TARGET] = 0.5f;
        values[INDEX_MAX] = 1f;
    }

    private void setDefaultWeights() {
        mWeights[INDEX_WEIGHT_SAT] = WEIGHT_SATURATION;
        mWeights[INDEX_WEIGHT_LUMA] = WEIGHT_LUMA;
        mWeights[INDEX_WEIGHT_POP] = WEIGHT_POPULATION;
    }

    void normalizeWeights() {
        float sum = 0;
        for (int i = 0, z = mWeights.length; i < z; i++) {
            float weight = mWeights[i];
            if (weight > 0) {
                sum += weight;
            }
        }
        if (sum != 0) {
            for (int i = 0, z = mWeights.length; i < z; i++) {
                if (mWeights[i] > 0) {
                    mWeights[i] /= sum;
                }
            }
        }
    }

    private static void setDefaultDarkLightnessValues(Target target) {
        target.mLightnessTargets[INDEX_TARGET] = TARGET_DARK_LUMA;
        target.mLightnessTargets[INDEX_MAX] = MAX_DARK_LUMA;
    }

    private static void setDefaultNormalLightnessValues(Target target) {
        target.mLightnessTargets[INDEX_MIN] = MIN_NORMAL_LUMA;
        target.mLightnessTargets[INDEX_TARGET] = TARGET_NORMAL_LUMA;
        target.mLightnessTargets[INDEX_MAX] = MAX_NORMAL_LUMA;
    }

    private static void setDefaultLightLightnessValues(Target target) {
        target.mLightnessTargets[INDEX_MIN] = MIN_LIGHT_LUMA;
        target.mLightnessTargets[INDEX_TARGET] = TARGET_LIGHT_LUMA;
    }

    private static void setDefaultVibrantSaturationValues(Target target) {
        target.mSaturationTargets[INDEX_MIN] = MIN_VIBRANT_SATURATION;
        target.mSaturationTargets[INDEX_TARGET] = TARGET_VIBRANT_SATURATION;
    }

    private static void setDefaultMutedSaturationValues(Target target) {
        target.mSaturationTargets[INDEX_TARGET] = TARGET_MUTED_SATURATION;
        target.mSaturationTargets[INDEX_MAX] = MAX_MUTED_SATURATION;
    }

    /**
     * Builder class for generating custom {@link Target} instances.
     */
    public final static class Builder {
        private final Target mTarget;

        /**
         * Create a new {@link Target} builder from scratch.
         */
        public Builder() {
            mTarget = new Target();
        }

        /**
         * Create a new builder based on an existing {@link Target}.
         */
        public Builder(Target target) {
            mTarget = new Target(target);
        }

        /**
         * Set the minimum saturation value for this target.
         */
        public Target.Builder setMinimumSaturation(@FloatRange(from = 0, to = 1) float value) {
            mTarget.mSaturationTargets[INDEX_MIN] = value;
            return this;
        }

        /**
         * Set the target/ideal saturation value for this target.
         */
        public Target.Builder setTargetSaturation(@FloatRange(from = 0, to = 1) float value) {
            mTarget.mSaturationTargets[INDEX_TARGET] = value;
            return this;
        }

        /**
         * Set the maximum saturation value for this target.
         */
        public Target.Builder setMaximumSaturation(@FloatRange(from = 0, to = 1) float value) {
            mTarget.mSaturationTargets[INDEX_MAX] = value;
            return this;
        }

        /**
         * Set the minimum lightness value for this target.
         */
        public Target.Builder setMinimumLightness(@FloatRange(from = 0, to = 1) float value) {
            mTarget.mLightnessTargets[INDEX_MIN] = value;
            return this;
        }

        /**
         * Set the target/ideal lightness value for this target.
         */
        public Target.Builder setTargetLightness(@FloatRange(from = 0, to = 1) float value) {
            mTarget.mLightnessTargets[INDEX_TARGET] = value;
            return this;
        }

        /**
         * Set the maximum lightness value for this target.
         */
        public Target.Builder setMaximumLightness(@FloatRange(from = 0, to = 1) float value) {
            mTarget.mLightnessTargets[INDEX_MAX] = value;
            return this;
        }

        /**
         * Set the weight of importance that this target will place on saturation values.
         *
         * <p>The larger the weight, relative to the other weights, the more important that a color
         * being close to the target value has on selection.</p>
         *
         * <p>A weight of 0 means that it has no weight, and thus has no
         * bearing on the selection.</p>
         *
         * @see #setTargetSaturation(float)
         */
        public Target.Builder setSaturationWeight(@FloatRange(from = 0) float weight) {
            mTarget.mWeights[INDEX_WEIGHT_SAT] = weight;
            return this;
        }

        /**
         * Set the weight of importance that this target will place on lightness values.
         *
         * <p>The larger the weight, relative to the other weights, the more important that a color
         * being close to the target value has on selection.</p>
         *
         * <p>A weight of 0 means that it has no weight, and thus has no
         * bearing on the selection.</p>
         *
         * @see #setTargetLightness(float)
         */
        public Target.Builder setLightnessWeight(@FloatRange(from = 0) float weight) {
            mTarget.mWeights[INDEX_WEIGHT_LUMA] = weight;
            return this;
        }

        /**
         * Set the weight of importance that this target will place on a color's population within
         * the image.
         *
         * <p>The larger the weight, relative to the other weights, the more important that a
         * color's population being close to the most populous has on selection.</p>
         *
         * <p>A weight of 0 means that it has no weight, and thus has no
         * bearing on the selection.</p>
         */
        public Target.Builder setPopulationWeight(@FloatRange(from = 0) float weight) {
            mTarget.mWeights[INDEX_WEIGHT_POP] = weight;
            return this;
        }

        /**
         * Set whether any color selected for this target is exclusive to this target only.
         * Defaults to true.
         *
         * @param exclusive true if any the color is exclusive to this target, or false is the
         *                  color can be selected for other targets.
         */
        public Target.Builder setExclusive(boolean exclusive) {
            mTarget.mIsExclusive = exclusive;
            return this;
        }

        /**
         * Builds and returns the resulting {@link Target}.
         */
        public Target build() {
            return mTarget;
        }
    }

}