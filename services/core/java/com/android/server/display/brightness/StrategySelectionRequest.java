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

import java.util.Objects;

/**
 * A wrapper class to encapsulate the request to select a strategy from
 * DisplayBrightnessStrategySelector
 */
public final class StrategySelectionRequest {
    // The request to change the associated display's state and brightness
    private DisplayManagerInternal.DisplayPowerRequest mDisplayPowerRequest;

    // The display state to which the screen is switching to
    private int mTargetDisplayState;

    // The last brightness that was set by the user and not temporary. Set to
    // PowerManager.BRIGHTNESS_INVALID_FLOAT when a brightness has yet to be recorded.
    private float mLastUserSetScreenBrightness;

    // Represents if the user set screen brightness was changed or not.
    private boolean mUserSetBrightnessChanged;

    private DisplayManagerInternal.DisplayOffloadSession mDisplayOffloadSession;

    private boolean mIsStylusBeingUsed;

    private boolean mIsWearBedtimeModeEnabled;

    public StrategySelectionRequest(DisplayManagerInternal.DisplayPowerRequest displayPowerRequest,
            int targetDisplayState, float lastUserSetScreenBrightness,
            boolean userSetBrightnessChanged,
            DisplayManagerInternal.DisplayOffloadSession displayOffloadSession,
            boolean isStylusBeingUsed,
            boolean isWearBedtimeModeEnabled) {
        mDisplayPowerRequest = displayPowerRequest;
        mTargetDisplayState = targetDisplayState;
        mLastUserSetScreenBrightness = lastUserSetScreenBrightness;
        mUserSetBrightnessChanged = userSetBrightnessChanged;
        mDisplayOffloadSession = displayOffloadSession;
        mIsStylusBeingUsed = isStylusBeingUsed;
        mIsWearBedtimeModeEnabled = isWearBedtimeModeEnabled;
    }

    public DisplayManagerInternal.DisplayPowerRequest getDisplayPowerRequest() {
        return mDisplayPowerRequest;
    }

    public int getTargetDisplayState() {
        return mTargetDisplayState;
    }


    public float getLastUserSetScreenBrightness() {
        return mLastUserSetScreenBrightness;
    }

    public boolean isUserSetBrightnessChanged() {
        return mUserSetBrightnessChanged;
    }

    public boolean isWearBedtimeModeEnabled() {
        return mIsWearBedtimeModeEnabled;
    }

    public DisplayManagerInternal.DisplayOffloadSession getDisplayOffloadSession() {
        return mDisplayOffloadSession;
    }

    public boolean isStylusBeingUsed() {
        return mIsStylusBeingUsed;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof StrategySelectionRequest)) {
            return false;
        }
        StrategySelectionRequest other = (StrategySelectionRequest) obj;
        return Objects.equals(mDisplayPowerRequest, other.getDisplayPowerRequest())
                && mTargetDisplayState == other.getTargetDisplayState()
                && mLastUserSetScreenBrightness == other.getLastUserSetScreenBrightness()
                && mUserSetBrightnessChanged == other.isUserSetBrightnessChanged()
                && mDisplayOffloadSession.equals(other.getDisplayOffloadSession())
                && mIsStylusBeingUsed == other.isStylusBeingUsed()
                && mIsWearBedtimeModeEnabled == other.isWearBedtimeModeEnabled();
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDisplayPowerRequest, mTargetDisplayState,
                mLastUserSetScreenBrightness, mUserSetBrightnessChanged, mDisplayOffloadSession,
                mIsStylusBeingUsed);
    }
}
