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

package android.hardware.devicestate;

import android.annotation.NonNull;
import android.annotation.SystemService;
import android.content.Context;

import java.util.concurrent.Executor;

/**
 * Manages the state of the system for devices with user-configurable hardware like a foldable
 * phone.
 *
 * @hide
 */
@SystemService(Context.DEVICE_STATE_SERVICE)
public final class DeviceStateManager {
    /** Invalid device state. */
    public static final int INVALID_DEVICE_STATE = -1;

    private DeviceStateManagerGlobal mGlobal;

    public DeviceStateManager() {
        DeviceStateManagerGlobal global = DeviceStateManagerGlobal.getInstance();
        if (global == null) {
            throw new IllegalStateException(
                    "Failed to get instance of global device state manager.");
        }
        mGlobal = global;
    }

    /**
     * Registers a listener to receive notifications about changes in device state.
     *
     * @param listener the listener to register.
     * @param executor the executor to process notifications.
     *
     * @see DeviceStateListener
     */
    public void registerDeviceStateListener(@NonNull DeviceStateListener listener,
            @NonNull Executor executor) {
        mGlobal.registerDeviceStateListener(listener, executor);
    }

    /**
     * Unregisters a listener previously registered with
     * {@link #registerDeviceStateListener(DeviceStateListener, Executor)}.
     */
    public void unregisterDeviceStateListener(@NonNull DeviceStateListener listener) {
        mGlobal.unregisterDeviceStateListener(listener);
    }

    /**
     * Listens for changes in device states.
     */
    public interface DeviceStateListener {
        /**
         * Called in response to device state changes.
         * <p>
         * Guaranteed to be called once on registration of the listener with the
         * initial value and then on every subsequent change in device state.
         *
         * @param deviceState the new device state.
         */
        void onDeviceStateChanged(int deviceState);
    }
}
