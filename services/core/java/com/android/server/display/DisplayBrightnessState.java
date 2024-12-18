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

import android.hardware.display.BrightnessInfo;
import android.os.PowerManager;
import android.text.TextUtils;

import com.android.server.display.brightness.BrightnessEvent;
import com.android.server.display.brightness.BrightnessReason;

import java.util.Objects;

/**
 * A state class representing a set of brightness related entities that are decided at runtime by
 * the DisplayBrightnessModeStrategies when updating the brightness.
 */
public final class DisplayBrightnessState {
    public static final float CUSTOM_ANIMATION_RATE_NOT_SET = -1f;
    public static final float BRIGHTNESS_NOT_SET = -1f;

    private final float mBrightness;
    private final float mHdrBrightness;

    private final float mMaxBrightness;
    private final float mMinBrightness;
    private final BrightnessReason mBrightnessReason;
    private final String mDisplayBrightnessStrategyName;
    private final boolean mShouldUseAutoBrightness;

    private final boolean mIsSlowChange;
    private final boolean mShouldUpdateScreenBrightnessSetting;

    private final float mCustomAnimationRate;

    private final BrightnessEvent mBrightnessEvent;
    private final int mBrightnessAdjustmentFlag;

    private final boolean mIsUserInitiatedChange;

    private @BrightnessInfo.BrightnessMaxReason int mBrightnessMaxReason;

    private DisplayBrightnessState(Builder builder) {
        mBrightness = builder.getBrightness();
        mHdrBrightness = builder.getHdrBrightness();
        mBrightnessReason = builder.getBrightnessReason();
        mDisplayBrightnessStrategyName = builder.getDisplayBrightnessStrategyName();
        mShouldUseAutoBrightness = builder.getShouldUseAutoBrightness();
        mIsSlowChange = builder.isSlowChange();
        mMaxBrightness = builder.getMaxBrightness();
        mMinBrightness = builder.getMinBrightness();
        mCustomAnimationRate = builder.getCustomAnimationRate();
        mShouldUpdateScreenBrightnessSetting = builder.shouldUpdateScreenBrightnessSetting();
        mBrightnessEvent = builder.getBrightnessEvent();
        mBrightnessAdjustmentFlag = builder.getBrightnessAdjustmentFlag();
        mIsUserInitiatedChange = builder.isUserInitiatedChange();
        mBrightnessMaxReason = builder.getBrightnessMaxReason();
    }

    /**
     * Gets the brightness
     */
    public float getBrightness() {
        return mBrightness;
    }

    /**
     * Gets the hdr brightness
     */
    public float getHdrBrightness() {
        return mHdrBrightness;
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

    /**
     * @return {@code true} if the device is set up to run auto-brightness.
     */
    public boolean getShouldUseAutoBrightness() {
        return mShouldUseAutoBrightness;
    }

    /**
     * @return {@code true} if the should transit to new state slowly
     */
    public boolean isSlowChange() {
        return mIsSlowChange;
    }

    /**
     * @return maximum allowed brightness
     */
    public float getMaxBrightness() {
        return mMaxBrightness;
    }

    /**
     * @return minimum allowed brightness
     */
    public float getMinBrightness() {
        return mMinBrightness;
    }

    /**
     * @return custom animation rate
     */
    public float getCustomAnimationRate() {
        return mCustomAnimationRate;
    }

    /**
     * @return {@code true} if the screen brightness setting should be updated
     */
    public boolean shouldUpdateScreenBrightnessSetting() {
        return mShouldUpdateScreenBrightnessSetting;
    }

    /**
     * @return The BrightnessEvent object
     */
    public BrightnessEvent getBrightnessEvent() {
        return mBrightnessEvent;
    }

    /**
     * Gets the flag representing the reason for the brightness adjustment. This can be
     * automatic(e.g. because of the change in the lux), or user initiated(e.g. moving the slider)
     */
    public int getBrightnessAdjustmentFlag() {
        return mBrightnessAdjustmentFlag;
    }

    /**
     * Gets if the current brightness changes are because of a user initiated change
     */
    public boolean isUserInitiatedChange() {
        return mIsUserInitiatedChange;
    }

    /**
     * Gets reason for max brightness restriction
     */
    public @BrightnessInfo.BrightnessMaxReason int getBrightnessMaxReason() {
        return mBrightnessMaxReason;
    }

    @Override
    public String toString() {
        StringBuilder stringBuilder = new StringBuilder("DisplayBrightnessState:");
        stringBuilder.append("\n    brightness:");
        stringBuilder.append(getBrightness());
        stringBuilder.append("\n    hdrBrightness:");
        stringBuilder.append(getHdrBrightness());
        stringBuilder.append("\n    brightnessReason:");
        stringBuilder.append(getBrightnessReason());
        stringBuilder.append("\n    shouldUseAutoBrightness:");
        stringBuilder.append(getShouldUseAutoBrightness());
        stringBuilder.append("\n    isSlowChange:").append(mIsSlowChange);
        stringBuilder.append("\n    maxBrightness:").append(mMaxBrightness);
        stringBuilder.append("\n    minBrightness:").append(mMinBrightness);
        stringBuilder.append("\n    customAnimationRate:").append(mCustomAnimationRate);
        stringBuilder.append("\n    shouldUpdateScreenBrightnessSetting:")
                .append(mShouldUpdateScreenBrightnessSetting);
        stringBuilder.append("\n    mBrightnessEvent:")
                .append(Objects.toString(mBrightnessEvent, "null"));
        stringBuilder.append("\n    mBrightnessAdjustmentFlag:").append(mBrightnessAdjustmentFlag);
        stringBuilder.append("\n    mIsUserInitiatedChange:").append(mIsUserInitiatedChange);
        stringBuilder.append("\n    mBrightnessMaxReason:")
                .append(BrightnessInfo.briMaxReasonToString(mBrightnessMaxReason));
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

        DisplayBrightnessState otherState = (DisplayBrightnessState) other;

        return mBrightness == otherState.getBrightness()
                && mHdrBrightness == otherState.getHdrBrightness()
                && mBrightnessReason.equals(otherState.getBrightnessReason())
                && TextUtils.equals(mDisplayBrightnessStrategyName,
                        otherState.getDisplayBrightnessStrategyName())
                && mShouldUseAutoBrightness == otherState.getShouldUseAutoBrightness()
                && mIsSlowChange == otherState.isSlowChange()
                && mMaxBrightness == otherState.getMaxBrightness()
                && mMinBrightness == otherState.getMinBrightness()
                && mCustomAnimationRate == otherState.getCustomAnimationRate()
                && mShouldUpdateScreenBrightnessSetting
                    == otherState.shouldUpdateScreenBrightnessSetting()
                && Objects.equals(mBrightnessEvent, otherState.getBrightnessEvent())
                && mBrightnessAdjustmentFlag == otherState.getBrightnessAdjustmentFlag()
                && mIsUserInitiatedChange == otherState.isUserInitiatedChange()
                && mBrightnessMaxReason == otherState.getBrightnessMaxReason();
    }

    @Override
    public int hashCode() {
        return Objects.hash(mBrightness, mHdrBrightness, mBrightnessReason,
                mShouldUseAutoBrightness, mIsSlowChange, mMaxBrightness, mMinBrightness,
                mCustomAnimationRate,
                mShouldUpdateScreenBrightnessSetting, mBrightnessEvent, mBrightnessAdjustmentFlag,
                mIsUserInitiatedChange, mBrightnessMaxReason);
    }

    /**
     * Helper methods to create builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * A DisplayBrightnessState's builder class.
     */
    public static class Builder {
        private float mBrightness;
        private float mHdrBrightness = BRIGHTNESS_NOT_SET;
        private BrightnessReason mBrightnessReason = new BrightnessReason();
        private String mDisplayBrightnessStrategyName;
        private boolean mShouldUseAutoBrightness;
        private boolean mIsSlowChange;
        private float mMaxBrightness = PowerManager.BRIGHTNESS_MAX;
        private float mMinBrightness;
        private float mCustomAnimationRate = CUSTOM_ANIMATION_RATE_NOT_SET;
        private boolean mShouldUpdateScreenBrightnessSetting;
        private BrightnessEvent mBrightnessEvent;
        private int mBrightnessAdjustmentFlag = 0;
        private boolean mIsUserInitiatedChange;
        private @BrightnessInfo.BrightnessMaxReason int mBrightnessMaxReason =
                BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE;

        /**
         * Create a builder starting with the values from the specified {@link
         * DisplayBrightnessState}.
         *
         * @param state The state from which to initialize.
         */
        public static Builder from(DisplayBrightnessState state) {
            Builder builder = new Builder();
            builder.setBrightness(state.getBrightness());
            builder.setHdrBrightness(state.getHdrBrightness());
            builder.setBrightnessReason(state.getBrightnessReason());
            builder.setDisplayBrightnessStrategyName(state.getDisplayBrightnessStrategyName());
            builder.setShouldUseAutoBrightness(state.getShouldUseAutoBrightness());
            builder.setIsSlowChange(state.isSlowChange());
            builder.setMaxBrightness(state.getMaxBrightness());
            builder.setMinBrightness(state.getMinBrightness());
            builder.setCustomAnimationRate(state.getCustomAnimationRate());
            builder.setShouldUpdateScreenBrightnessSetting(
                    state.shouldUpdateScreenBrightnessSetting());
            builder.setBrightnessEvent(state.getBrightnessEvent());
            builder.setBrightnessAdjustmentFlag(state.getBrightnessAdjustmentFlag());
            builder.setIsUserInitiatedChange(state.isUserInitiatedChange());
            builder.setBrightnessMaxReason(state.getBrightnessMaxReason());
            return builder;
        }

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
         * Gets the hdr brightness
         */
        public float getHdrBrightness() {
            return mHdrBrightness;
        }

        /**
         * Sets the hdr brightness
         *
         * @param hdrBrightness The hdr brightness to be associated with DisplayBrightnessState's
         *                      builder
         */
        public Builder setHdrBrightness(float hdrBrightness) {
            this.mHdrBrightness = hdrBrightness;
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
         * Sets the {@link BrightnessReason} using the int-based reason enum. This is a convenience
         * function so we don't have to type out the constructor syntax everywhere.
         *
         * @param brightnessReason The int-based brightness enum.
         */
        public Builder setBrightnessReason(int brightnessReason) {
            return setBrightnessReason(new BrightnessReason(brightnessReason));
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
         * See {@link DisplayBrightnessState#getShouldUseAutoBrightness}.
         */
        public Builder setShouldUseAutoBrightness(boolean shouldUseAutoBrightness) {
            this.mShouldUseAutoBrightness = shouldUseAutoBrightness;
            return this;
        }

        /**
         * See {@link DisplayBrightnessState#getShouldUseAutoBrightness}.
         */
        public boolean getShouldUseAutoBrightness() {
            return mShouldUseAutoBrightness;
        }

        /**
         * See {@link DisplayBrightnessState#isSlowChange()}.
         */
        public Builder setIsSlowChange(boolean isSlowChange) {
            this.mIsSlowChange = isSlowChange;
            return this;
        }

        /**
         * See {@link DisplayBrightnessState#isSlowChange()}.
         */
        public boolean isSlowChange() {
            return mIsSlowChange;
        }

        /**
         * See {@link DisplayBrightnessState#getMaxBrightness()}.
         */
        public Builder setMaxBrightness(float maxBrightness) {
            this.mMaxBrightness = maxBrightness;
            return this;
        }

        /**
         * See {@link DisplayBrightnessState#getMaxBrightness()}.
         */
        public float getMaxBrightness() {
            return mMaxBrightness;
        }

        /**
         * See {@link DisplayBrightnessState#getMinBrightness()}.
         */
        public Builder setMinBrightness(float minBrightness) {
            this.mMinBrightness = minBrightness;
            return this;
        }

        /**
         * See {@link DisplayBrightnessState#getMinBrightness()}.
         */
        public float getMinBrightness() {
            return mMinBrightness;
        }

        /**
         * See {@link DisplayBrightnessState#getCustomAnimationRate()}.
         */
        public Builder setCustomAnimationRate(float animationRate) {
            this.mCustomAnimationRate = animationRate;
            return this;
        }

        /**
         * See {@link DisplayBrightnessState#getCustomAnimationRate()}.
         */
        public float getCustomAnimationRate() {
            return mCustomAnimationRate;
        }

        /**
         * See {@link DisplayBrightnessState#shouldUpdateScreenBrightnessSetting()}.
         */
        public boolean shouldUpdateScreenBrightnessSetting() {
            return mShouldUpdateScreenBrightnessSetting;
        }

        /**
         * See {@link DisplayBrightnessState#shouldUpdateScreenBrightnessSetting()}.
         */
        public Builder setShouldUpdateScreenBrightnessSetting(
                boolean shouldUpdateScreenBrightnessSetting) {
            mShouldUpdateScreenBrightnessSetting = shouldUpdateScreenBrightnessSetting;
            return this;
        }

        /**
         * This is used to construct an immutable DisplayBrightnessState object from its builder
         */
        public DisplayBrightnessState build() {
            return new DisplayBrightnessState(this);
        }

        /**
         * This is used to get the BrightnessEvent object from its builder
         */
        public BrightnessEvent getBrightnessEvent() {
            return mBrightnessEvent;
        }


        /**
         * This is used to set the BrightnessEvent object
         */
        public Builder setBrightnessEvent(BrightnessEvent brightnessEvent) {
            mBrightnessEvent = brightnessEvent;
            return this;
        }

        /**
         * This is used to get the brightness adjustment flag from its builder
         */
        public int getBrightnessAdjustmentFlag() {
            return mBrightnessAdjustmentFlag;
        }


        /**
         * This is used to set the brightness adjustment flag
         */
        public Builder setBrightnessAdjustmentFlag(int brightnessAdjustmentFlag) {
            mBrightnessAdjustmentFlag = brightnessAdjustmentFlag;
            return this;
        }

        /**
         * Gets if the current change is a user initiated change
         */
        public boolean isUserInitiatedChange() {
            return mIsUserInitiatedChange;
        }

        /**
         * This is used to set if the current change is a user initiated change
         */
        public Builder setIsUserInitiatedChange(boolean isUserInitiatedChange) {
            mIsUserInitiatedChange = isUserInitiatedChange;
            return this;
        }

        /**
         * Gets reason for max brightness restriction
         */
        public @BrightnessInfo.BrightnessMaxReason int getBrightnessMaxReason() {
            return mBrightnessMaxReason;
        }

        /**
         * Sets reason for max brightness restriction
         */
        public Builder setBrightnessMaxReason(
                @BrightnessInfo.BrightnessMaxReason int brightnessMaxReason) {
            mBrightnessMaxReason = brightnessMaxReason;
            return this;
        }
    }
}
