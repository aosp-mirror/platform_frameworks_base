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
 * A wrapper class to encapsulate the request to execute the selected strategy
 */
public final class StrategyExecutionRequest {
    // The request to change the associated display's state and brightness
    private final DisplayManagerInternal.DisplayPowerRequest mDisplayPowerRequest;

    private final float mCurrentScreenBrightness;

    // Represents if the user set screen brightness was changed or not.
    private boolean mUserSetBrightnessChanged;

    public StrategyExecutionRequest(DisplayManagerInternal.DisplayPowerRequest displayPowerRequest,
            float currentScreenBrightness, boolean userSetBrightnessChanged) {
        mDisplayPowerRequest = displayPowerRequest;
        mCurrentScreenBrightness = currentScreenBrightness;
        mUserSetBrightnessChanged = userSetBrightnessChanged;
    }

    public DisplayManagerInternal.DisplayPowerRequest getDisplayPowerRequest() {
        return mDisplayPowerRequest;
    }

    public float getCurrentScreenBrightness() {
        return mCurrentScreenBrightness;
    }

    public boolean isUserSetBrightnessChanged() {
        return mUserSetBrightnessChanged;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof StrategyExecutionRequest)) {
            return false;
        }
        StrategyExecutionRequest other = (StrategyExecutionRequest) obj;
        return Objects.equals(mDisplayPowerRequest, other.getDisplayPowerRequest())
                && mCurrentScreenBrightness == other.getCurrentScreenBrightness()
                && mUserSetBrightnessChanged == other.isUserSetBrightnessChanged();
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDisplayPowerRequest, mCurrentScreenBrightness,
                mUserSetBrightnessChanged);
    }
}
