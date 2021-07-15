/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.devicestate;

import static android.hardware.devicestate.DeviceStateManager.MAXIMUM_DEVICE_STATE;
import static android.hardware.devicestate.DeviceStateManager.MINIMUM_DEVICE_STATE;

import android.annotation.IntRange;

/**
 * Responsible for providing the set of supported {@link DeviceState device states} as well as the
 * current device state.
 *
 * @see DeviceStatePolicy
 */
public interface DeviceStateProvider {
    /**
     * Registers a listener for changes in provider state.
     * <p>
     * It is <b>required</b> that {@link Listener#onSupportedDeviceStatesChanged(DeviceState[])} be
     * called followed by {@link Listener#onStateChanged(int)} with the initial values on successful
     * registration of the listener.
     */
    void setListener(Listener listener);

    /** Callback for changes in {@link DeviceStateProvider} state. */
    interface Listener {
        /**
         * Called to notify the listener of a change in supported {@link DeviceState device states}.
         * Required to be called once on successful registration of the listener and then once on
         * every subsequent change in supported device states.
         * <p>
         * The set of device states can change based on the current hardware state of the device.
         * For example, if a device state depends on a particular peripheral device (display, etc)
         * it would only be reported as supported when the device is plugged. Otherwise, it should
         * not be included in the set of supported states.
         * <p>
         * The identifier for every provided device state must be unique and greater than or equal
         * to zero and there must always be at least one supported device state.
         *
         * @param newDeviceStates array of supported device states.
         *
         * @throws IllegalArgumentException if the list of device states is empty or if one of the
         * provided states contains an invalid identifier.
         */
        void onSupportedDeviceStatesChanged(DeviceState[] newDeviceStates);

        /**
         * Called to notify the listener of a change in current device state. Required to be called
         * once on successful registration of the listener and then once on every subsequent change
         * in device state. Value must have been included in the set of supported device states
         * provided in the most recent call to
         * {@link #onSupportedDeviceStatesChanged(DeviceState[])}.
         *
         * @param identifier the identifier of the new device state.
         *
         * @throws IllegalArgumentException if the state is less than {@link MINIMUM_DEVICE_STATE}
         * or greater than {@link MAXIMUM_DEVICE_STATE}.
         */
        void onStateChanged(
                @IntRange(from = MINIMUM_DEVICE_STATE, to = MAXIMUM_DEVICE_STATE) int identifier);
    }
}
