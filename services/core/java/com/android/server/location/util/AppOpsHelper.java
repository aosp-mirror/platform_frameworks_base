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

package com.android.server.location.util;

import static android.app.AppOpsManager.OP_MONITOR_HIGH_POWER_LOCATION;
import static android.app.AppOpsManager.OP_MONITOR_LOCATION;

import static com.android.server.location.LocationManagerService.D;
import static com.android.server.location.LocationManagerService.TAG;

import android.app.AppOpsManager;
import android.location.util.identity.CallerIdentity;
import android.util.Log;

import com.android.server.location.LocationPermissions;
import com.android.server.location.LocationPermissions.PermissionLevel;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Provides helpers and listeners for appops.
 */
public abstract class AppOpsHelper {

    /**
     * Listener for current user changes.
     */
    public interface LocationAppOpListener {

        /**
         * Called when something has changed about a location appop for the given package.
         */
        void onAppOpsChanged(String packageName);
    }

    private final CopyOnWriteArrayList<LocationAppOpListener> mListeners;

    public AppOpsHelper() {
        mListeners = new CopyOnWriteArrayList<>();
    }

    protected final void notifyAppOpChanged(String packageName) {
        if (D) {
            Log.v(TAG, "location appop changed for " + packageName);
        }

        for (LocationAppOpListener listener : mListeners) {
            listener.onAppOpsChanged(packageName);
        }
    }

    /**
     * Adds a listener for app ops events. Callbacks occur on an unspecified thread.
     */
    public final void addListener(LocationAppOpListener listener) {
        mListeners.add(listener);
    }

    /**
     * Removes a listener for app ops events.
     */
    public final void removeListener(LocationAppOpListener listener) {
        mListeners.remove(listener);
    }

    /**
     * Checks if the given identity may have locations delivered without noting that a location is
     * being delivered. This is a looser guarantee than
     * {@link #noteLocationAccess(CallerIdentity, int)}, and this function does not validate package
     * arguments and so should not be used with unvalidated arguments or before actually delivering
     * locations.
     *
     * @see AppOpsManager#checkOpNoThrow(int, int, String)
     */
    public final boolean checkLocationAccess(CallerIdentity callerIdentity,
            @PermissionLevel int permissionLevel) {
        if (permissionLevel == LocationPermissions.PERMISSION_NONE) {
            return false;
        }

        return checkOpNoThrow(LocationPermissions.asAppOp(permissionLevel), callerIdentity);
    }

    /**
     * Notes location access to the given identity, ie, location delivery. This method should be
     * called right before a location is delivered, and if it returns false, the location should not
     * be delivered.
     */
    public final boolean noteLocationAccess(CallerIdentity identity,
            @PermissionLevel int permissionLevel) {
        if (permissionLevel == LocationPermissions.PERMISSION_NONE) {
            return false;
        }

        return noteOpNoThrow(LocationPermissions.asAppOp(permissionLevel), identity);
    }

    /**
     * Notifies app ops that the given identity is using location at normal/low power levels. If
     * this function returns false, do not later call
     * {@link #stopLocationMonitoring(CallerIdentity)}.
     */
    public final boolean startLocationMonitoring(CallerIdentity identity) {
        return startOpNoThrow(OP_MONITOR_LOCATION, identity);
    }

    /**
     * Notifies app ops that the given identity is no longer using location at normal/low power
     * levels.
     */
    public final void stopLocationMonitoring(CallerIdentity identity) {
        finishOp(OP_MONITOR_LOCATION, identity);
    }

    /**
     * Notifies app ops that the given identity is using location at high levels. If this function
     * returns false, do not later call {@link #stopLocationMonitoring(CallerIdentity)}.
     */
    public final boolean startHighPowerLocationMonitoring(CallerIdentity identity) {
        return startOpNoThrow(OP_MONITOR_HIGH_POWER_LOCATION, identity);
    }

    /**
     * Notifies app ops that the given identity is no longer using location at high power levels.
     */
    public final void stopHighPowerLocationMonitoring(CallerIdentity identity) {
        finishOp(OP_MONITOR_HIGH_POWER_LOCATION, identity);
    }

    /**
     * Notes access to any mock location APIs. If this call returns false, access to the APIs should
     * silently fail.
     */
    public final boolean noteMockLocationAccess(CallerIdentity callerIdentity) {
        return noteOp(AppOpsManager.OP_MOCK_LOCATION, callerIdentity);
    }

    protected abstract boolean startOpNoThrow(int appOp, CallerIdentity callerIdentity);

    protected abstract void finishOp(int appOp, CallerIdentity callerIdentity);

    protected abstract boolean checkOpNoThrow(int appOp, CallerIdentity callerIdentity);

    protected abstract boolean noteOp(int appOp, CallerIdentity callerIdentity);

    protected abstract boolean noteOpNoThrow(int appOp, CallerIdentity callerIdentity);
}
