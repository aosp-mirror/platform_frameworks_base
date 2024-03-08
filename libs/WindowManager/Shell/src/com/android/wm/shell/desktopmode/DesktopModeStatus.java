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

import static android.content.res.Configuration.SCREENLAYOUT_SIZE_XLARGE;

import android.annotation.NonNull;
import android.app.ActivityManager.RunningTaskInfo;
import android.os.SystemProperties;

import com.android.window.flags.Flags;

/**
 * Constants for desktop mode feature
 */
public class DesktopModeStatus {

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
     * Flag to indicate whether to apply shadows to windows in desktop mode.
     */
    private static final boolean USE_WINDOW_SHADOWS = SystemProperties.getBoolean(
            "persist.wm.debug.desktop_use_window_shadows", true);

    /**
     * Flag to indicate whether to apply shadows to the focused window in desktop mode.
     *
     * Note: this flag is only relevant if USE_WINDOW_SHADOWS is false.
     */
    private static final boolean USE_WINDOW_SHADOWS_FOCUSED_WINDOW = SystemProperties.getBoolean(
            "persist.wm.debug.desktop_use_window_shadows_focused_window", false);

    /**
     * Flag to indicate whether to apply shadows to windows in desktop mode.
     */
    private static final boolean USE_ROUNDED_CORNERS = SystemProperties.getBoolean(
            "persist.wm.debug.desktop_use_rounded_corners", true);

    private static final boolean ENFORCE_DISPLAY_RESTRICTIONS = SystemProperties.getBoolean(
            "persist.wm.debug.desktop_mode_enforce_display_restrictions", true);

    /**
     * Return {@code true} if desktop windowing is enabled
     */
    public static boolean isEnabled() {
        return Flags.enableDesktopWindowingMode();
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

    /**
     * Return whether to use window shadows.
     *
     * @param isFocusedWindow whether the window to apply shadows to is focused
     */
    public static boolean useWindowShadow(boolean isFocusedWindow) {
        return USE_WINDOW_SHADOWS
            || (USE_WINDOW_SHADOWS_FOCUSED_WINDOW && isFocusedWindow);
    }

    /**
     * Return whether to use rounded corners for windows.
     */
    public static boolean useRoundedCorners() {
        return USE_ROUNDED_CORNERS;
    }

    /**
     * Return whether the display size restrictions should be enforced.
     */
    public static boolean enforceDisplayRestrictions() {
        return ENFORCE_DISPLAY_RESTRICTIONS;
    }

    /**
     * Return {@code true} if the display associated with the task is at least of size
     * {@link android.content.res.Configuration#SCREENLAYOUT_SIZE_XLARGE} or has been overridden to
     * ignore the size constraint.
     */
    public static boolean meetsMinimumDisplayRequirements(@NonNull RunningTaskInfo taskInfo) {
        return !enforceDisplayRestrictions()
                || taskInfo.configuration.isLayoutSizeAtLeast(SCREENLAYOUT_SIZE_XLARGE);
    }
}
