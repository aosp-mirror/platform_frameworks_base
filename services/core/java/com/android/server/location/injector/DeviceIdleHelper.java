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

package com.android.server.location.injector;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Provides accessors and listeners for device idle status.
 */
public abstract class DeviceIdleHelper {

    /**
     * Listener for device stationary status.
     */
    public interface DeviceIdleListener {
        /**
         * Called when device idle state has changed.
         */
        void onDeviceIdleChanged(boolean deviceIdle);
    }

    private final CopyOnWriteArrayList<DeviceIdleListener> mListeners;

    protected DeviceIdleHelper() {
        mListeners = new CopyOnWriteArrayList<>();
    }

    /**
     * Adds a listener for device idle status.
     */
    public final synchronized void addListener(DeviceIdleListener listener) {
        if (mListeners.add(listener) && mListeners.size() == 1) {
            registerInternal();
        }
    }

    /**
     * Removes a listener for device idle status.
     */
    public final synchronized void removeListener(DeviceIdleListener listener) {
        if (mListeners.remove(listener) && mListeners.isEmpty()) {
            unregisterInternal();
        }
    }

    protected final void notifyDeviceIdleChanged() {
        boolean deviceIdle = isDeviceIdle();

        for (DeviceIdleListener listener : mListeners) {
            listener.onDeviceIdleChanged(deviceIdle);
        }
    }

    protected abstract void registerInternal();

    protected abstract void unregisterInternal();

    /**
     * Returns true if the device is currently idle.
     */
    public abstract boolean isDeviceIdle();
}
