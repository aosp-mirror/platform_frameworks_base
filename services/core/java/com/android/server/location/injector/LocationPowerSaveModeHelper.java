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

import static android.os.PowerManager.locationPowerSaveModeToString;

import static com.android.server.location.LocationManagerService.D;
import static com.android.server.location.LocationManagerService.TAG;
import static com.android.server.location.eventlog.LocationEventLog.EVENT_LOG;

import android.os.PowerManager.LocationPowerSaveMode;
import android.util.Log;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Provides accessors and listeners for location power save mode.
 */
public abstract class LocationPowerSaveModeHelper {

    /**
     * Listener for location power save mode changes.
     */
    public interface LocationPowerSaveModeChangedListener {
        /**
         * Called when the location power save mode changes.
         */
        void onLocationPowerSaveModeChanged(@LocationPowerSaveMode int locationPowerSaveMode);
    }

    private final CopyOnWriteArrayList<LocationPowerSaveModeChangedListener> mListeners;

    public LocationPowerSaveModeHelper() {
        mListeners = new CopyOnWriteArrayList<>();
    }

    /**
     * Add a listener for changes to location power save mode. Callbacks occur on an unspecified
     * thread.
     */
    public final void addListener(LocationPowerSaveModeChangedListener listener) {
        mListeners.add(listener);
    }

    /**
     * Removes a listener for changes to location power save mode.
     */
    public final void removeListener(LocationPowerSaveModeChangedListener listener) {
        mListeners.remove(listener);
    }

    protected final void notifyLocationPowerSaveModeChanged(
            @LocationPowerSaveMode int locationPowerSaveMode) {
        if (D) {
            Log.d(TAG, "location power save mode is now " + locationPowerSaveModeToString(
                    locationPowerSaveMode));
        }
        EVENT_LOG.logLocationPowerSaveMode(locationPowerSaveMode);

        for (LocationPowerSaveModeChangedListener listener : mListeners) {
            listener.onLocationPowerSaveModeChanged(locationPowerSaveMode);
        }
    }

    /**
     * Returns the current location power save mode.
     */
    @LocationPowerSaveMode
    public abstract int getLocationPowerSaveMode();
}
