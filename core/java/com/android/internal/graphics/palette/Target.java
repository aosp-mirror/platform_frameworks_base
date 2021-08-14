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
 * limitations under the License
 */

package com.android.internal.graphics.palette;


import android.annotation.FloatRange;
import android.annotation.IntRange;
import android.annotation.NonNull;

/**
 * A class which allows custom selection of colors in a {@link Palette}'s generation. Instances can
 * be created via the {@link Builder} class.
 */

public final class Target {
    private static final float WEIGHT_CHROMA = 0.5f;
    private static final float WEIGHT_RELATIVE_LUMINANCE = 0.5f;
    private static final float WEIGHT_POPULATION = 0.3f;
    private static final float WEIGHT_HUE = 0.2f;

    // Arbitrarily chosen, except max - CAM16 chroma has a ceiling of 130, based on unit testing.
    private static final float DEFAULT_CHROMA_MIN = 0.f;
    private static final float DEFAULT_CHROMA_MAX = 130.f;
    private static final float DEFAULT_CHROMA_TARGET = 30.f;

    private float mTargetRelativeLuminance = -1.0f;
    private float mChromaWeight;
    private float mChromaTarget;
    private float mChromaMin;
    private float mChromaMax;
    private float mRelativeLuminanceWeight;
    private float mPopulationWeight;
    private float mHueWeight;
    private float mTargetHue;

    Target() {
        mChromaMax = DEFAULT_CHROMA_MAX;
        mChromaMin = DEFAULT_CHROMA_MIN;
        mChromaTarget = DEFAULT_CHROMA_TARGET;
        mChromaWeight =   WEIGHT_CHROMA;
        mRelativeLuminanceWeight = WEIGHT_RELATIVE_LUMINANCE;
        mPopulationWeight = WEIGHT_POPULATION;
        mHueWeight = WEIGHT_HUE;
    }

    Target(@NonNull Target from) {
        mTargetRelativeLuminance = from.mTargetRelativeLuminance;
        mChromaWeight = from.mChromaWeight;
        mRelativeLuminanceWeight = from.mRelativeLuminanceWeight;
        mPopulationWeight = from.mPopulationWeight;
        mHueWeight = from.mHueWeight;
        mChromaTarget = from.mChromaTarget;
        mChromaMin = from.mChromaMin;
        mChromaMax = from.mChromaMax;
    }

    /** The relative luminance value for this target. */
    @FloatRange(from = 0, to = 100)
    public float getTargetRelativeLuminance() {
        return mTargetRelativeLuminance;
    }

    /** The relative luminance value for this target. */
    @FloatRange(from = 0, to = 100)
    public float getTargetPerceptualLuminance() {
        return Contrast.yToLstar(mTargetRelativeLuminance);
    }

    /** The minimum chroma value for this target. */
    @FloatRange(from = 0, to = 100)
    public float getMinimumChroma() {
        return mChromaMin;
    }

    /** The target chroma value for this target. */
    @FloatRange(from = 0, to = 100)
    public float getTargetChroma() {
        return mChromaTarget;
    }

    /** The maximum chroma value for this target. */
    @FloatRange(from = 0, to = 130)
    public float getMaximumChroma() {
        return mChromaMax;
    }

    /** The target hue value for this target. */
    @FloatRange(from = 0, to = 100)
    public float getTargetHue() {
        return mTargetHue;
    }

    /**
     * Returns the weight of importance that this target places on a color's chroma within the
     * image.
     *
     * <p>The larger the weight, relative to the other weights, the more important that a color
     * being
     * close to the target value has on selection.
     *
     * @see #getTargetChroma()
     */
    public float getChromaWeight() {
        return mChromaWeight;
    }

    /**
     * Returns the weight of importance that this target places on a color's lightness within the
     * image.
     *
     * <p>The larger the weight, relative to the other weights, the more important that a color
     * being
     * close to the target value has on selection.
     *
     * @see #getTargetRelativeLuminance()
     */
    public float getLightnessWeight() {
        return mRelativeLuminanceWeight;
    }

    /**
     * Returns the weight of importance that this target places on a color's population within the
     * image.
     *
     * <p>The larger the weight, relative to the other weights, the more important that a color's
     * population being close to the most populous has on selection.
     */
    public float getPopulationWeight() {
        return mPopulationWeight;
    }

    /**
     * Returns the weight of importance that this target places on a color's hue.
     *
     * <p>The larger the weight, relative to the other weights, the more important that a color's
     * hue being close to the desired hue has on selection.
     */
    public float getHueWeight() {
        return mHueWeight;
    }


    /** Builder class for generating custom {@link Target} instances. */
    public static class Builder {
        private final Target mTarget;

        /** Create a new {@link Target} builder from scratch. */
        public Builder() {
            mTarget = new Target();
        }

        /** Create a new builder based on an existing {@link Target}. */
        public Builder(@NonNull Target target) {
            mTarget = new Target(target);
        }

        /** Set the minimum chroma value for this target. */
        @NonNull
        public Builder setMinimumChroma(@FloatRange(from = 0, to = 100) float value) {
            mTarget.mChromaMin = value;
            return this;
        }

        /** Set the target/ideal chroma value for this target. */
        @NonNull
        public Builder setTargetChroma(@FloatRange(from = 0, to = 100) float value) {
            mTarget.mChromaTarget = value;
            return this;
        }

        /** Set the maximum chroma value for this target. */
        @NonNull
        public Builder setMaximumChroma(@FloatRange(from = 0, to = 100) float value) {
            mTarget.mChromaMax = value;
            return this;
        }

        /** Set the minimum lightness value for this target, using Y in XYZ color space. */
        @NonNull
        public Builder setTargetRelativeLuminance(@FloatRange(from = 0, to = 100) float value) {
            mTarget.mTargetRelativeLuminance = value;
            return this;
        }

        /** Set the minimum lightness value for this target, using L* in LAB color space. */
        @NonNull
        public Builder setTargetPerceptualLuminance(@FloatRange(from = 0, to = 100) float value) {
            mTarget.mTargetRelativeLuminance = Contrast.lstarToY(value);
            return this;
        }

        /**
         * Set the hue desired from the target. This hue is not enforced, the only consequence
         * is points will be awarded to seed colors the closer they are to this hue.
         */
        @NonNull
        public Builder setTargetHue(@IntRange(from = 0, to = 360) int hue) {
            mTarget.mTargetHue = hue;
            return this;
        }

        /** Sets lightness value for this target. */
        @NonNull
        public Builder setContrastRatio(
                @FloatRange(from = 1, to = 21) float value,
                @FloatRange(from = 0, to = 100) float relativeLuminance) {
            float counterpartY = relativeLuminance;
            float lstar = Contrast.yToLstar(counterpartY);

            float targetY;
            if (lstar < 50) {
                targetY = Contrast.lighterY(counterpartY, value);
            } else {
                targetY = Contrast.darkerY(counterpartY, value);
            }
            mTarget.mTargetRelativeLuminance = targetY;
            return this;
        }

        /**
         * Set the weight of importance that this target will place on chroma values.
         *
         * <p>The larger the weight, relative to the other weights, the more important that a color
         * being close to the target value has on selection.
         *
         * <p>A weight of 0 means that it has no weight, and thus has no bearing on the selection.
         *
         * @see #setTargetChroma(float)
         */
        @NonNull
        public Builder setChromaWeight(@FloatRange(from = 0) float weight) {
            mTarget.mChromaWeight = weight;
            return this;
        }

        /**
         * Set the weight of importance that this target will place on lightness values.
         *
         * <p>The larger the weight, relative to the other weights, the more important that a color
         * being close to the target value has on selection.
         *
         * <p>A weight of 0 means that it has no weight, and thus has no bearing on the selection.
         *
         * @see #setTargetRelativeLuminance(float)
         */
        @NonNull
        public Builder setLightnessWeight(@FloatRange(from = 0) float weight) {
            mTarget.mRelativeLuminanceWeight = weight;
            return this;
        }

        /**
         * Set the weight of importance that this target will place on a color's population within
         * the
         * image.
         *
         * <p>The larger the weight, relative to the other weights, the more important that a
         * color's
         * population being close to the most populous has on selection.
         *
         * <p>A weight of 0 means that it has no weight, and thus has no bearing on the selection.
         */
        @NonNull
        public Builder setPopulationWeight(@FloatRange(from = 0) float weight) {
            mTarget.mPopulationWeight = weight;
            return this;
        }


        /** Builds and returns the resulting {@link Target}. */
        @NonNull
        public Target build() {
            return mTarget;
        }
    }
}
