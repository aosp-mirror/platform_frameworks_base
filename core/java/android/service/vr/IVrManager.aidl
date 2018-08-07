/**
 * Copyright (c) 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.service.vr;

import android.app.Vr2dDisplayProperties;
import android.content.ComponentName;
import android.service.vr.IVrStateCallbacks;
import android.service.vr.IPersistentVrStateCallbacks;

/** @hide */
interface IVrManager {

    /**
     * Add a callback to be notified when VR mode state changes.
     *
     * @param cb the callback instance to add.
     */
    void registerListener(in IVrStateCallbacks cb);

    /**
     * Remove the callack from the current set of registered callbacks.
     *
     * @param cb the callback to remove.
     */
    void unregisterListener(in IVrStateCallbacks cb);

    /**
     * Add a callback to be notified when persistent VR mode state changes.
     *
     * @param cb the callback instance to add.
     */
    void registerPersistentVrStateListener(in IPersistentVrStateCallbacks cb);

    /**
     * Remove the callack from the current set of registered callbacks.
     *
     * @param cb the callback to remove.
     */
    void unregisterPersistentVrStateListener(in IPersistentVrStateCallbacks cb);

    /**
     * Return current VR mode state.
     *
     * @return {@code true} if VR mode is enabled.
     */
    boolean getVrModeState();

    /**
     * Returns the current Persistent VR mode state.
     *
     * @return {@code true} if Persistent VR mode is enabled.
     */
    boolean getPersistentVrModeEnabled();

    /**
     * Sets the persistent VR mode state of a device. When a device is in persistent VR mode it will
     * remain in VR mode even if the foreground does not specify VR mode being enabled. Mainly used
     * by VR viewers to indicate that a device is placed in a VR viewer.
     *
     * @param enabled true if the device should be placed in persistent VR mode.
     */
    void setPersistentVrModeEnabled(in boolean enabled);

    /**
     * Sets the resolution and DPI of the vr2d virtual display used to display
     * 2D applications in VR mode.
     *
     * <p>Requires {@link android.Manifest.permission#ACCESS_VR_MANAGER} permission.</p>
     *
     * @param vr2dDisplayProperties Vr2d display properties to be set for
     * the VR virtual display
     */
    void setVr2dDisplayProperties(
            in Vr2dDisplayProperties vr2dDisplayProperties);

    /**
     * Return current virtual display id.
     *
     * @return {@link android.view.Display.INVALID_DISPLAY} if there is no virtual display
     * currently, else return the display id of the virtual display
     */
    int getVr2dDisplayId();

    /**
     * Set the component name of the compositor service to bind.
     *
     * @param componentName flattened string representing a ComponentName of a Service in the
     * application's compositor process to bind to, or null to clear the current binding.
     */
    void setAndBindCompositor(in String componentName);

    /**
     * Sets the current standby status of the VR device. Standby mode is only used on standalone vr
     * devices. Standby mode is a deep sleep state where it's appropriate to turn off vr mode.
     *
     * @param standy True if the device is entering standby, false if it's exiting standby.
     */
    void setStandbyEnabled(boolean standby);

    /**
     * Start VR Input method for the given packageName in {@param componentName}.
     * This method notifies InputMethodManagerService to use VR IME instead of
     * regular phone IME.
     */
    void setVrInputMethod(in ComponentName componentName);

}

