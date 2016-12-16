/**
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.server.vr;

import android.annotation.NonNull;
import android.content.ComponentName;

/**
 * Service for accessing the VR mode manager.
 *
 * @hide Only for use within system server.
 */
public abstract class VrManagerInternal {

    /**
     * The error code returned on success.
     */
    public static final int NO_ERROR = 0;

    /**
     * Return {@code true} if the given package is the currently bound VrListenerService for the
     * given user.
     *
     * @param packageName The package name to check.
     * @param userId the user ID to check the package name for.
     *
     * @return {@code true} if the given package is the currently bound VrListenerService.
     */
    public abstract boolean isCurrentVrListener(String packageName, int userId);

    /**
     * Set the current VR mode state.
     * <p/>
     * This may delay the mode change slightly during application transitions to avoid frequently
     * tearing down VrListenerServices unless necessary.
     *
     * @param enabled {@code true} to enable VR mode.
     * @param packageName The package name of the requested VrListenerService to bind.
     * @param userId the user requesting the VrListenerService component.
     * @param calling the component currently using VR mode, or null to leave unchanged.
     */
    public abstract void setVrMode(boolean enabled, @NonNull ComponentName packageName,
            int userId, @NonNull ComponentName calling);

    /**
     * Set whether the system has acquired a sleep token.
     *
     * @param isAsleep is {@code true} if the device is asleep, or {@code false} otherwise.
     */
    public abstract void onSleepStateChanged(boolean isAsleep);

    /**
     * Set whether the display used for VR output is on.
     *
     * @param isScreenOn is {@code true} if the display is on and can receive commands,
     *      or {@code false} otherwise.
     */
    public abstract void onScreenStateChanged(boolean isScreenOn);

    /**
     * Return NO_ERROR if the given package is installed on the device and enabled as a
     * VrListenerService for the given current user, or a negative error code indicating a failure.
     *
     * @param packageName the name of the package to check, or null to select the default package.
     * @return NO_ERROR if the given package is installed and is enabled, or a negative error code
     *       given in {@link android.service.vr.VrModeException} on failure.
     */
    public abstract int hasVrPackage(@NonNull ComponentName packageName, int userId);
}
