/*
 * Copyright (C) 2015 The CyanogenMod Project
 * Copyright (C) 2018 The LineageOS Project
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

package com.android.internal.custom.hardware;

/**
 * Display Modes API
 *
 * A device may implement a list of preset display modes for different
 * viewing intents, such as movies, photos, or extra vibrance. These
 * modes may have multiple components such as gamma correction, white
 * point adjustment, etc, but are activated by a single control point.
 *
 * This API provides support for enumerating and selecting the
 * modes supported by the hardware.
 */

public class DisplayModeControl {

    /**
     * All HAF classes should export this boolean.
     * Real implementations must, of course, return true
     */
    public static boolean isSupported() {
        return false;
    }

    /**
     * Get the list of available modes. A mode has an integer
     * identifier and a string name.
     *
     * It is the responsibility of the upper layers to
     * map the name to a human-readable format or perform translation.
     */
    public static DisplayMode[] getAvailableModes() {
        return new DisplayMode[0];
    }

    /**
     * Get the name of the currently selected mode. This can return
     * null if no mode is selected.
     */
    public static DisplayMode getCurrentMode() {
        return null;
    }

    /**
     * Selects a mode from the list of available modes by it's
     * string identifier. Returns true on success, false for
     * failure. It is up to the implementation to determine
     * if this mode is valid.
     */
    public static boolean setMode(DisplayMode mode, boolean makeDefault) {
        return false;
    }

    /**
     * Gets the preferred default mode for this device by it's
     * string identifier. Can return null if there is no default.
     */
    public static DisplayMode getDefaultMode() {
        return null;
    }
}
