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

import static com.android.server.location.LocationPermissions.PERMISSION_NONE;

import android.location.util.identity.CallerIdentity;

import com.android.server.location.LocationPermissions;
import com.android.server.location.LocationPermissions.PermissionLevel;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Provides helpers and listeners for appops.
 */
public abstract class LocationPermissionsHelper {

    /**
     * Listener for current user changes.
     */
    public interface LocationPermissionsListener {

        /**
         * Called when something has changed about location permissions for the given package.
         */
        void onLocationPermissionsChanged(String packageName);

        /**
         * Called when something has changed about location permissions for the given uid.
         */
        void onLocationPermissionsChanged(int uid);
    }

    private final CopyOnWriteArrayList<LocationPermissionsListener> mListeners;
    private final AppOpsHelper mAppOps;

    public LocationPermissionsHelper(AppOpsHelper appOps) {
        mListeners = new CopyOnWriteArrayList<>();
        mAppOps = appOps;

        mAppOps.addListener(this::onAppOpsChanged);
    }

    protected final void notifyLocationPermissionsChanged(String packageName) {
        for (LocationPermissionsListener listener : mListeners) {
            listener.onLocationPermissionsChanged(packageName);
        }
    }

    protected final void notifyLocationPermissionsChanged(int uid) {
        for (LocationPermissionsListener listener : mListeners) {
            listener.onLocationPermissionsChanged(uid);
        }
    }

    private void onAppOpsChanged(String packageName) {
        notifyLocationPermissionsChanged(packageName);
    }

    /**
     * Adds a listener for location permissions events. Callbacks occur on an unspecified thread.
     */
    public final void addListener(LocationPermissionsListener listener) {
        mListeners.add(listener);
    }

    /**
     * Removes a listener for location permissions events.
     */
    public final void removeListener(LocationPermissionsListener listener) {
        mListeners.remove(listener);
    }

    /**
     * Returns true if the given identity may access location at the given permissions level, taking
     * into account both permissions and appops.
     */
    public final boolean hasLocationPermissions(@PermissionLevel int permissionLevel,
            CallerIdentity identity) {
        if (permissionLevel == PERMISSION_NONE) {
            return false;
        }

        if (!hasPermission(LocationPermissions.asPermission(permissionLevel), identity)) {
            return false;
        }

        return mAppOps.checkOpNoThrow(permissionLevel, identity);
    }

    protected abstract boolean hasPermission(String permission, CallerIdentity callerIdentity);
}
