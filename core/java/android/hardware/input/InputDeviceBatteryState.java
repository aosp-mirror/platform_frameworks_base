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

package android.hardware.input;

import static android.os.BatteryManager.BATTERY_STATUS_UNKNOWN;
import static android.os.IInputConstants.INVALID_BATTERY_CAPACITY;

import android.hardware.BatteryState;

/**
 * Battery implementation for input devices.
 *
 * @hide
 */
public final class InputDeviceBatteryState extends BatteryState {
    private static final float NULL_BATTERY_CAPACITY = Float.NaN;

    private final InputManager mInputManager;
    private final int mDeviceId;
    private final boolean mHasBattery;

    InputDeviceBatteryState(InputManager inputManager, int deviceId, boolean hasBattery) {
        mInputManager = inputManager;
        mDeviceId = deviceId;
        mHasBattery = hasBattery;
    }

    @Override
    public boolean isPresent() {
        return mHasBattery;
    }

    @Override
    public int getStatus() {
        if (!mHasBattery) {
            return BATTERY_STATUS_UNKNOWN;
        }
        return mInputManager.getBatteryStatus(mDeviceId);
    }

    @Override
    public float getCapacity() {
        if (mHasBattery) {
            int capacity = mInputManager.getBatteryCapacity(mDeviceId);
            if (capacity != INVALID_BATTERY_CAPACITY) {
                return (float) capacity / 100.0f;
            }
        }
        return NULL_BATTERY_CAPACITY;
    }
}
