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

package com.android.wm.shell.shared.desktopmode;

import android.annotation.NonNull;
import android.content.Context;
import android.os.SystemProperties;
import android.window.flags.DesktopModeFlags;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.window.flags.Flags;

import java.io.PrintWriter;

/**
 * Constants for desktop mode feature
 */
// TODO(b/237575897): Move this file to the `com.android.wm.shell.shared.desktopmode` package
public class DesktopModeStatus {

    private static final String TAG = "DesktopModeStatus";

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
     * Flag to indicate whether to use rounded corners for windows in desktop mode.
     */
    private static final boolean USE_ROUNDED_CORNERS = SystemProperties.getBoolean(
            "persist.wm.debug.desktop_use_rounded_corners", true);

    /**
     * Flag to indicate whether to restrict desktop mode to supported devices.
     */
    private static final boolean ENFORCE_DEVICE_RESTRICTIONS = SystemProperties.getBoolean(
            "persist.wm.debug.desktop_mode_enforce_device_restrictions", true);

    private static final boolean USE_APP_TO_WEB_BUILD_TIME_GENERIC_LINKS =
            SystemProperties.getBoolean(
                    "persist.wm.debug.use_app_to_web_build_time_generic_links", true);

    /** Whether the desktop density override is enabled. */
    public static final boolean DESKTOP_DENSITY_OVERRIDE_ENABLED =
            SystemProperties.getBoolean("persist.wm.debug.desktop_mode_density_enabled", false);

    /** Override density for tasks when they're inside the desktop. */
    public static final int DESKTOP_DENSITY_OVERRIDE =
            SystemProperties.getInt("persist.wm.debug.desktop_mode_density", 284);

    /** The minimum override density allowed for tasks inside the desktop. */
    private static final int DESKTOP_DENSITY_MIN = 100;

    /** The maximum override density allowed for tasks inside the desktop. */
    private static final int DESKTOP_DENSITY_MAX = 1000;

    /**
     * Sysprop declaring whether to enters desktop mode by default when the windowing mode of the
     * display's root TaskDisplayArea is set to WINDOWING_MODE_FREEFORM.
     *
     * <p>If it is not defined, then {@code R.integer.config_enterDesktopByDefaultOnFreeformDisplay}
     * is used.
     */
    public static final String ENTER_DESKTOP_BY_DEFAULT_ON_FREEFORM_DISPLAY_SYS_PROP =
            "persist.wm.debug.enter_desktop_by_default_on_freeform_display";

    /**
     * Sysprop declaring the maximum number of Tasks to show in Desktop Mode at any one time.
     *
     * <p>If it is not defined, then {@code R.integer.config_maxDesktopWindowingActiveTasks} is
     * used.
     *
     * <p>The limit does NOT affect Picture-in-Picture, Bubbles, or System Modals (like a screen
     * recording window, or Bluetooth pairing window).
     */
    private static final String MAX_TASK_LIMIT_SYS_PROP = "persist.wm.debug.desktop_max_task_limit";

    /**
     * Return {@code true} if veiled resizing is active. If false, fluid resizing is used.
     */
    public static boolean isVeiledResizeEnabled() {
        return IS_VEILED_RESIZE_ENABLED;
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
     * Return {@code true} if desktop mode should be restricted to supported devices.
     */
    @VisibleForTesting
    public static boolean enforceDeviceRestrictions() {
        return ENFORCE_DEVICE_RESTRICTIONS;
    }

    /**
     * Return the maximum limit on the number of Tasks to show in Desktop Mode at any one time.
     */
    public static int getMaxTaskLimit(@NonNull Context context) {
        return SystemProperties.getInt(MAX_TASK_LIMIT_SYS_PROP,
                context.getResources().getInteger(R.integer.config_maxDesktopWindowingActiveTasks));
    }

    /**
     * Return {@code true} if the current device supports desktop mode.
     */
    @VisibleForTesting
    public static boolean isDesktopModeSupported(@NonNull Context context) {
        return context.getResources().getBoolean(R.bool.config_isDesktopModeSupported);
    }

    /**
     * Return {@code true} if desktop mode dev option should be shown on current device
     */
    public static boolean canShowDesktopModeDevOption(@NonNull Context context) {
        return isDeviceEligibleForDesktopMode(context) && Flags.showDesktopWindowingDevOption();
    }

    /** Returns if desktop mode dev option should be enabled if there is no user override. */
    public static boolean shouldDevOptionBeEnabledByDefault() {
        return Flags.enableDesktopWindowingMode();
    }

    /**
     * Return {@code true} if desktop mode is enabled and can be entered on the current device.
     */
    public static boolean canEnterDesktopMode(@NonNull Context context) {
        if (!isDeviceEligibleForDesktopMode(context)) return false;

        return DesktopModeFlags.ENABLE_DESKTOP_WINDOWING_MODE.isTrue();
    }

    /**
     * Return {@code true} if the override desktop density is enabled and valid.
     */
    public static boolean useDesktopOverrideDensity() {
        return isDesktopDensityOverrideEnabled() && isValidDesktopDensityOverrideSet();
    }

    /**
     * Returns {@code true} if the app-to-web feature is using the build-time generic links list.
     */
    public static boolean useAppToWebBuildTimeGenericLinks() {
        return USE_APP_TO_WEB_BUILD_TIME_GENERIC_LINKS;
    }

    /**
     * Return {@code true} if the override desktop density is enabled.
     */
    private static boolean isDesktopDensityOverrideEnabled() {
        return DESKTOP_DENSITY_OVERRIDE_ENABLED;
    }

    /**
     * Return {@code true} if the override desktop density is set and within a valid range.
     */
    private static boolean isValidDesktopDensityOverrideSet() {
        return DESKTOP_DENSITY_OVERRIDE >= DESKTOP_DENSITY_MIN
                && DESKTOP_DENSITY_OVERRIDE <= DESKTOP_DENSITY_MAX;
    }

    /**
     * Return {@code true} if desktop mode is unrestricted and is supported in the device.
     */
    private static boolean isDeviceEligibleForDesktopMode(@NonNull Context context) {
        return !enforceDeviceRestrictions() || isDesktopModeSupported(context);
    }

    /**
     * Return {@code true} if a display should enter desktop mode by default when the windowing mode
     * of the display's root [TaskDisplayArea] is set to WINDOWING_MODE_FREEFORM.
     */
    public static boolean enterDesktopByDefaultOnFreeformDisplay(@NonNull Context context) {
        if (!Flags.enterDesktopByDefaultOnFreeformDisplays()) {
            return false;
        }
        return SystemProperties.getBoolean(ENTER_DESKTOP_BY_DEFAULT_ON_FREEFORM_DISPLAY_SYS_PROP,
                context.getResources().getBoolean(
                        R.bool.config_enterDesktopByDefaultOnFreeformDisplay));
    }

    /** Dumps DesktopModeStatus flags and configs. */
    public static void dump(PrintWriter pw, String prefix, Context context) {
        String innerPrefix = prefix + "  ";
        pw.print(prefix); pw.println(TAG);
        pw.print(innerPrefix); pw.print("maxTaskLimit="); pw.println(getMaxTaskLimit(context));

        pw.print(innerPrefix); pw.print("maxTaskLimit config override=");
        pw.println(context.getResources().getInteger(
                R.integer.config_maxDesktopWindowingActiveTasks));

        SystemProperties.Handle maxTaskLimitHandle = SystemProperties.find(MAX_TASK_LIMIT_SYS_PROP);
        pw.print(innerPrefix); pw.print("maxTaskLimit sysprop=");
        pw.println(maxTaskLimitHandle == null ? "null" : maxTaskLimitHandle.getInt(/* def= */ -1));
    }
}
