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

package com.android.server.display.brightness;

import android.hardware.display.DisplayManagerInternal;

import com.android.server.display.brightness.strategy.DisplayBrightnessStrategy;

import java.util.Objects;

/**
 * A wrapper class to encapsulate the request to notify the strategies about the selection of a
 * DisplayBrightnessStrategy
 */
public final class StrategySelectionNotifyRequest {
    // The request to change the associated display's state and brightness
    private DisplayManagerInternal.DisplayPowerRequest mDisplayPowerRequest;

    // The display state to which the screen is switching to
    private int mTargetDisplayState;

    // The strategy that was selected with the current request
    private final DisplayBrightnessStrategy mSelectedDisplayBrightnessStrategy;

    // The last brightness that was set by the user and not temporary. Set to
    // PowerManager.BRIGHTNESS_INVALID_FLOAT when a brightness has yet to be recorded.
    private float mLastUserSetScreenBrightness;

    // Represents if the user set screen brightness was changed or not.
    private boolean mUserSetBrightnessChanged;

    // True if light sensor is to be used to automatically determine doze screen brightness.
    private final boolean mAllowAutoBrightnessWhileDozingConfig;
    // True if the auto brightness is enabled in the settings
    private final boolean mIsAutoBrightnessEnabled;

    public StrategySelectionNotifyRequest(
            DisplayManagerInternal.DisplayPowerRequest displayPowerRequest, int targetDisplayState,
            DisplayBrightnessStrategy displayBrightnessStrategy,
            float lastUserSetScreenBrightness,
            boolean userSetBrightnessChanged, boolean allowAutoBrightnessWhileDozingConfig,
            boolean isAutoBrightnessEnabled) {
        mDisplayPowerRequest = displayPowerRequest;
        mTargetDisplayState = targetDisplayState;
        mSelectedDisplayBrightnessStrategy = displayBrightnessStrategy;
        mLastUserSetScreenBrightness = lastUserSetScreenBrightness;
        mUserSetBrightnessChanged = userSetBrightnessChanged;
        mAllowAutoBrightnessWhileDozingConfig = allowAutoBrightnessWhileDozingConfig;
        mIsAutoBrightnessEnabled = isAutoBrightnessEnabled;
    }

    public DisplayBrightnessStrategy getSelectedDisplayBrightnessStrategy() {
        return mSelectedDisplayBrightnessStrategy;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof StrategySelectionNotifyRequest)) {
            return false;
        }
        StrategySelectionNotifyRequest other = (StrategySelectionNotifyRequest) obj;
        return other.getSelectedDisplayBrightnessStrategy()
                == getSelectedDisplayBrightnessStrategy()
                && Objects.equals(mDisplayPowerRequest, other.getDisplayPowerRequest())
                && mTargetDisplayState == other.getTargetDisplayState()
                && mUserSetBrightnessChanged == other.isUserSetBrightnessChanged()
                && mLastUserSetScreenBrightness == other.getLastUserSetScreenBrightness()
                && mAllowAutoBrightnessWhileDozingConfig
                == other.isAllowAutoBrightnessWhileDozingConfig()
                && mIsAutoBrightnessEnabled == other.isAutoBrightnessEnabled();
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSelectedDisplayBrightnessStrategy, mDisplayPowerRequest,
                mTargetDisplayState, mUserSetBrightnessChanged, mLastUserSetScreenBrightness,
                mAllowAutoBrightnessWhileDozingConfig, mIsAutoBrightnessEnabled);
    }

    public float getLastUserSetScreenBrightness() {
        return mLastUserSetScreenBrightness;
    }

    public boolean isUserSetBrightnessChanged() {
        return mUserSetBrightnessChanged;
    }

    public DisplayManagerInternal.DisplayPowerRequest getDisplayPowerRequest() {
        return mDisplayPowerRequest;
    }

    public int getTargetDisplayState() {
        return mTargetDisplayState;
    }

    public boolean isAllowAutoBrightnessWhileDozingConfig() {
        return mAllowAutoBrightnessWhileDozingConfig;
    }

    public boolean isAutoBrightnessEnabled() {
        return mIsAutoBrightnessEnabled;
    }

    /**
     * A utility to stringify a StrategySelectionNotifyRequest
     */
    public String toString() {
        return "StrategySelectionNotifyRequest:"
                + " mDisplayPowerRequest=" + mDisplayPowerRequest
                + " mTargetDisplayState=" + mTargetDisplayState
                + " mSelectedDisplayBrightnessStrategy=" + mSelectedDisplayBrightnessStrategy
                + " mLastUserSetScreenBrightness=" + mLastUserSetScreenBrightness
                + " mUserSetBrightnessChanged=" + mUserSetBrightnessChanged
                + " mAllowAutoBrightnessWhileDozingConfig=" + mAllowAutoBrightnessWhileDozingConfig
                + " mIsAutoBrightnessEnabled=" + mIsAutoBrightnessEnabled;
    }
}
