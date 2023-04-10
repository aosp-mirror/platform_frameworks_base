/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.display;

import com.android.server.display.brightness.BrightnessReason;

import java.util.Objects;

/**
 * A state class representing a set of brightness related entities that are decided at runtime by
 * the DisplayBrightnessModeStrategies when updating the brightness.
 */
public final class DisplayBrightnessState {
    private final float mBrightness;
    private final float mSdrBrightness;
    private final BrightnessReason mBrightnessReason;
    private final String mDisplayBrightnessStrategyName;

    private DisplayBrightnessState(Builder builder) {
        this.mBrightness = builder.getBrightness();
        this.mSdrBrightness = builder.getSdrBrightness();
        this.mBrightnessReason = builder.getBrightnessReason();
        this.mDisplayBrightnessStrategyName = builder.getDisplayBrightnessStrategyName();
    }

    /**
     * Gets the brightness
     */
    public float getBrightness() {
        return mBrightness;
    }

    /**
     * Gets the sdr brightness
     */
    public float getSdrBrightness() {
        return mSdrBrightness;
    }

    /**
     * Gets the {@link BrightnessReason}
     */
    public BrightnessReason getBrightnessReason() {
        return mBrightnessReason;
    }

    /**
     * Gets the {@link com.android.server.display.brightness.strategy.DisplayBrightnessStrategy}
     * name
     */
    public String getDisplayBrightnessStrategyName() {
        return mDisplayBrightnessStrategyName;
    }

    @Override
    public String toString() {
        StringBuilder stringBuilder = new StringBuilder("DisplayBrightnessState:");
        stringBuilder.append("\n    brightness:");
        stringBuilder.append(getBrightness());
        stringBuilder.append("\n    sdrBrightness:");
        stringBuilder.append(getSdrBrightness());
        stringBuilder.append("\n    brightnessReason:");
        stringBuilder.append(getBrightnessReason());
        return stringBuilder.toString();
    }

    /**
     * Checks whether the two objects have the same values.
     */
    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }

        if (!(other instanceof DisplayBrightnessState)) {
            return false;
        }

        DisplayBrightnessState
                displayBrightnessState = (DisplayBrightnessState) other;

        if (mBrightness != displayBrightnessState.getBrightness()) {
            return false;
        }
        if (mSdrBrightness != displayBrightnessState.getSdrBrightness()) {
            return false;
        }
        if (!mBrightnessReason.equals(displayBrightnessState.getBrightnessReason())) {
            return false;
        }
        if (!mDisplayBrightnessStrategyName.equals(
                displayBrightnessState.getDisplayBrightnessStrategyName())) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mBrightness, mSdrBrightness, mBrightnessReason);
    }

    /**
     * A DisplayBrightnessState's builder class.
     */
    public static class Builder {
        private float mBrightness;
        private float mSdrBrightness;
        private BrightnessReason mBrightnessReason = new BrightnessReason();
        private String mDisplayBrightnessStrategyName;

        /**
         * Gets the brightness
         */
        public float getBrightness() {
            return mBrightness;
        }

        /**
         * Sets the brightness
         *
         * @param brightness The brightness to be associated with DisplayBrightnessState's
         *                   builder
         */
        public Builder setBrightness(float brightness) {
            this.mBrightness = brightness;
            return this;
        }

        /**
         * Gets the sdr brightness
         */
        public float getSdrBrightness() {
            return mSdrBrightness;
        }

        /**
         * Sets the sdr brightness
         *
         * @param sdrBrightness The sdr brightness to be associated with DisplayBrightnessState's
         *                      builder
         */
        public Builder setSdrBrightness(float sdrBrightness) {
            this.mSdrBrightness = sdrBrightness;
            return this;
        }

        /**
         * Gets the {@link BrightnessReason}
         */
        public BrightnessReason getBrightnessReason() {
            return mBrightnessReason;
        }

        /**
         * Sets the {@link BrightnessReason}
         *
         * @param brightnessReason The brightness reason {@link BrightnessReason} to be
         *                         associated with the builder
         */
        public Builder setBrightnessReason(BrightnessReason brightnessReason) {
            this.mBrightnessReason = brightnessReason;
            return this;
        }

        /**
         * Gets the {@link com.android.server.display.brightness.strategy.DisplayBrightnessStrategy}
         * name
         */
        public String getDisplayBrightnessStrategyName() {
            return mDisplayBrightnessStrategyName;
        }

        /**
         * Sets the
         * {@link com.android.server.display.brightness.strategy.DisplayBrightnessStrategy}'s name
         *
         * @param displayBrightnessStrategyName The name of the
         * {@link com.android.server.display.brightness.strategy.DisplayBrightnessStrategy} being
         *                                      used.
         */
        public Builder setDisplayBrightnessStrategyName(String displayBrightnessStrategyName) {
            this.mDisplayBrightnessStrategyName = displayBrightnessStrategyName;
            return this;
        }

        /**
         * This is used to construct an immutable DisplayBrightnessState object from its builder
         */
        public DisplayBrightnessState build() {
            return new DisplayBrightnessState(this);
        }
    }
}
