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
import android.app.Vr2dDisplayProperties;
import android.content.ComponentName;
import android.service.vr.IPersistentVrStateCallbacks;

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
     * @param processId the process the component is running in.
     * @param calling the component currently using VR mode, or null to leave unchanged.
     */
    public abstract void setVrMode(boolean enabled, @NonNull ComponentName packageName,
            int userId, int processId, @NonNull ComponentName calling);

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
     * Set whether the keyguard is currently active/showing.
     *
     * @param isShowing is {@code true} if the keyguard is active/showing.
     */
    public abstract void onKeyguardStateChanged(boolean isShowing);

    /**
     * Return NO_ERROR if the given package is installed on the device and enabled as a
     * VrListenerService for the given current user, or a negative error code indicating a failure.
     *
     * @param packageName the name of the package to check, or null to select the default package.
     * @return NO_ERROR if the given package is installed and is enabled, or a negative error code
     *       given in {@link android.service.vr.VrModeException} on failure.
     */
    public abstract int hasVrPackage(@NonNull ComponentName packageName, int userId);

    /**
     * Sets the resolution and DPI of the vr2d virtual display used to display
     * 2D applications in VR mode.
     *
     * <p>Requires {@link android.Manifest.permission#ACCESS_VR_MANAGER} permission.</p>
     *
     * @param vr2dDisplayProp Properties of the virtual display for 2D applications
     * in VR mode.
     */
    public abstract void setVr2dDisplayProperties(
            Vr2dDisplayProperties vr2dDisplayProp);

    /**
     * Sets the persistent VR mode state of a device. When a device is in persistent VR mode it will
     * remain in VR mode even if the foreground does not specify Vr mode being enabled. Mainly used
     * by VR viewers to indicate that a device is placed in a VR viewer.
     *
     * @param enabled true if the device should be placed in persistent VR mode.
     */
    public abstract void setPersistentVrModeEnabled(boolean enabled);

    /**
     * Return {@link android.view.Display.INVALID_DISPLAY} if there exists no virtual display
     * currently or the display id of the current virtual display.
     *
     * @return {@link android.view.Display.INVALID_DISPLAY} if there is no virtual display
     * currently, else return the display id of the virtual display
     */
    public abstract int getVr2dDisplayId();

    /**
     * Adds listener that reports state changes to persistent VR mode.
     */
    public abstract void addPersistentVrModeStateListener(IPersistentVrStateCallbacks listener);
}
