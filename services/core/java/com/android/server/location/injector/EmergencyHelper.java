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

package com.android.server.location.injector;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Provides helpers for emergency sessions.
 */
public abstract class EmergencyHelper {

    private final CopyOnWriteArrayList<EmergencyStateChangedListener> mListeners;

    protected EmergencyHelper() {
        mListeners = new CopyOnWriteArrayList<>();
    }

    /**
     * Listener for emergency state changes.
     */
    public interface EmergencyStateChangedListener {
        /**
         * Called when state changes.
         */
        void onStateChanged();
    }

    /**
     * Returns true if the device is in an emergency session, or if an emergency session ended
     * within the given extension time.
     */
    public abstract boolean isInEmergency(long extensionTimeMs);

    /**
     * Add a listener for changes to the emergency location state.
     */
    public void addOnEmergencyStateChangedListener(EmergencyStateChangedListener listener) {
        mListeners.add(listener);
    }

    /**
     * Remove a listener for changes to the emergency location state.
     */
    public void removeOnEmergencyStateChangedListener(EmergencyStateChangedListener listener) {
        mListeners.remove(listener);
    }

    /**
     * Notify listeners for emergency state of state change
     */
    protected final void dispatchEmergencyStateChanged() {
        for (EmergencyStateChangedListener listener : mListeners) {
            listener.onStateChanged();
        }
    }
}
