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
 * limitations under the License.
 */

package com.android.server.sensorprivacy;

import static android.hardware.SensorPrivacyManager.StateTypes.DISABLED;
import static android.hardware.SensorPrivacyManager.StateTypes.ENABLED;

import static com.android.server.sensorprivacy.SensorPrivacyService.getCurrentTimeMillis;

class SensorState {

    private int mStateType;
    private long mLastChange;

    SensorState(int stateType) {
        mStateType = stateType;
        mLastChange = getCurrentTimeMillis();
    }

    SensorState(int stateType, long lastChange) {
        mStateType = stateType;
        mLastChange = Math.min(getCurrentTimeMillis(), lastChange);
    }

    SensorState(SensorState sensorState) {
        mStateType = sensorState.getState();
        mLastChange = sensorState.getLastChange();
    }

    boolean setState(int stateType) {
        if (mStateType != stateType) {
            mStateType = stateType;
            mLastChange = getCurrentTimeMillis();
            return true;
        }
        return false;
    }

    int getState() {
        return mStateType;
    }

    long getLastChange() {
        return mLastChange;
    }

    // Below are some convenience members for when dealing with enabled/disabled

    private static int enabledToState(boolean enabled) {
        return enabled ? ENABLED : DISABLED;
    }

    SensorState(boolean enabled) {
        this(enabledToState(enabled));
    }

    boolean setEnabled(boolean enabled) {
        return setState(enabledToState(enabled));
    }

    boolean isEnabled() {
        return getState() == ENABLED;
    }

}
