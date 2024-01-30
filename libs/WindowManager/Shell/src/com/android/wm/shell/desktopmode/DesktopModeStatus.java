/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.desktopmode;

import android.os.SystemProperties;

import com.android.wm.shell.Flags;

/**
 * Constants for desktop mode feature
 */
public class DesktopModeStatus {

    private static final boolean ENABLE_DESKTOP_WINDOWING = Flags.enableDesktopWindowing();

    /**
     * Flag to indicate whether desktop mode proto is available on the device
     */
    private static final boolean IS_PROTO2_ENABLED = SystemProperties.getBoolean(
            "persist.wm.debug.desktop_mode_2", false);

    /**
     * Flag to indicate whether task resizing is veiled.
     */
    private static final boolean IS_VEILED_RESIZE_ENABLED = SystemProperties.getBoolean(
            "persist.wm.debug.desktop_veiled_resizing", true);

    /**
     * Flag to indicate is moving task to another display is enabled.
     */
    public static final boolean IS_DISPLAY_CHANGE_ENABLED = SystemProperties.getBoolean(
            "persist.wm.debug.desktop_change_display", false);


    /**
     * Flag to indicate that desktop stashing is enabled.
     * When enabled, swiping home from desktop stashes the open apps. Next app that launches,
     * will be added to the desktop.
     */
    private static final boolean IS_STASHING_ENABLED = SystemProperties.getBoolean(
            "persist.wm.debug.desktop_stashing", false);

    /**
     * Return {@code true} is desktop windowing proto 2 is enabled
     */
    public static boolean isEnabled() {
        // Check for aconfig flag first
        if (ENABLE_DESKTOP_WINDOWING) {
            return true;
        }
        // Fall back to sysprop flag
        // TODO(b/304778354): remove sysprop once desktop aconfig flag supports dynamic overriding
        return IS_PROTO2_ENABLED;
    }

    /**
     * Return {@code true} if veiled resizing is active. If false, fluid resizing is used.
     */
    public static boolean isVeiledResizeEnabled() {
        return IS_VEILED_RESIZE_ENABLED;
    }

    /**
     * Return {@code true} if desktop task stashing is enabled when going home.
     * Allows users to use home screen to add tasks to desktop.
     */
    public static boolean isStashingEnabled() {
        return IS_STASHING_ENABLED;
    }
}
