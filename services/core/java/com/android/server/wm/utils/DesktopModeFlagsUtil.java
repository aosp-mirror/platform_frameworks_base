/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.wm.utils;

import static com.android.server.wm.utils.DesktopModeFlagsUtil.ToggleOverride.OVERRIDE_UNSET;

import android.annotation.Nullable;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import com.android.window.flags.Flags;

import java.util.function.Supplier;

/**
 * Util to check desktop mode flags state.
 *
 * This utility is used to allow developer option toggles to override flags related to desktop
 * windowing.
 *
 * Computes whether Desktop Windowing related flags should be enabled by using the aconfig flag
 * value and the developer option override state (if applicable).
 *
 * This is a partial copy of {@link com.android.wm.shell.shared.desktopmode.DesktopModeFlags} which
 * is to be used in WM core.
 */
public enum DesktopModeFlagsUtil {
    // All desktop mode related flags to be overridden by developer option toggle will be added here
    DESKTOP_WINDOWING_MODE(
            Flags::enableDesktopWindowingMode, /* shouldOverrideByDevOption= */ true),
    WALLPAPER_ACTIVITY(
            Flags::enableDesktopWindowingWallpaperActivity, /* shouldOverrideByDevOption= */ true);

    private static final String TAG = "DesktopModeFlagsUtil";
    private static final String SYSTEM_PROPERTY_OVERRIDE_KEY =
            "sys.wmshell.desktopmode.dev_toggle_override";

    // Function called to obtain aconfig flag value.
    private final Supplier<Boolean> mFlagFunction;
    // Whether the flag state should be affected by developer option.
    private final boolean mShouldOverrideByDevOption;

    // Local cache for toggle override, which is initialized once on its first access. It needs to
    // be refreshed only on reboots as overridden state takes effect on reboots.
    private static ToggleOverride sCachedToggleOverride;

    DesktopModeFlagsUtil(Supplier<Boolean> flagFunction, boolean shouldOverrideByDevOption) {
        this.mFlagFunction = flagFunction;
        this.mShouldOverrideByDevOption = shouldOverrideByDevOption;
    }

    /**
     * Determines state of flag based on the actual flag and desktop mode developer option
     * overrides.
     *
     * Note: this method makes sure that a constant developer toggle overrides is read until
     * reboot.
     */
    public boolean isEnabled(Context context) {
        if (!Flags.showDesktopWindowingDevOption()
                || !mShouldOverrideByDevOption
                || context.getContentResolver() == null) {
            return mFlagFunction.get();
        } else {
            boolean shouldToggleBeEnabledByDefault = Flags.enableDesktopWindowingMode();
            return switch (getToggleOverride(context)) {
                case OVERRIDE_UNSET -> mFlagFunction.get();
                // When toggle override matches its default state, don't override flags. This
                // helps users reset their feature overrides.
                case OVERRIDE_OFF -> !shouldToggleBeEnabledByDefault && mFlagFunction.get();
                case OVERRIDE_ON -> shouldToggleBeEnabledByDefault ? mFlagFunction.get() : true;
            };
        }
    }

    private ToggleOverride getToggleOverride(Context context) {
        // If cached, return it
        if (sCachedToggleOverride != null) {
            return sCachedToggleOverride;
        }

        // Otherwise, fetch and cache it
        ToggleOverride override = getToggleOverrideFromSystem(context);
        sCachedToggleOverride = override;
        Log.d(TAG, "Toggle override initialized to: " + override);
        return override;
    }

    /**
     *  Returns {@link ToggleOverride} from a non-persistent system property if present. Otherwise
     *  initializes the system property by reading Settings.Global.
     */
    private ToggleOverride getToggleOverrideFromSystem(Context context) {
        // A non-persistent System Property is used to store override to ensure it remains
        // constant till reboot.
        String overrideProperty = System.getProperty(SYSTEM_PROPERTY_OVERRIDE_KEY, null);
        ToggleOverride overrideFromSystemProperties = convertToToggleOverride(overrideProperty);

        // If valid system property, return it
        if (overrideFromSystemProperties != null) {
            return overrideFromSystemProperties;
        }

        // Fallback when System Property is not present (just after reboot) or not valid (user
        // manually changed the value): Read from Settings.Global
        int settingValue = Settings.Global.getInt(
                context.getContentResolver(),
                Settings.Global.DEVELOPMENT_OVERRIDE_DESKTOP_MODE_FEATURES,
                OVERRIDE_UNSET.getSetting()
        );
        ToggleOverride overrideFromSettingsGlobal =
                ToggleOverride.fromSetting(settingValue, OVERRIDE_UNSET);
        // Initialize System Property
        System.setProperty(SYSTEM_PROPERTY_OVERRIDE_KEY, String.valueOf(settingValue));
        return overrideFromSettingsGlobal;
    }

    /**
     * Converts {@code intString} into {@link ToggleOverride}. Return {@code null} if
     * {@code intString} does not correspond to a {@link ToggleOverride}.
     */
    private static @Nullable ToggleOverride convertToToggleOverride(
            @Nullable String intString
    ) {
        if (intString == null) return null;
        try {
            int intValue = Integer.parseInt(intString);
            return ToggleOverride.fromSetting(intValue, null);
        } catch (NumberFormatException e) {
            Log.w(TAG, "Unknown toggleOverride int " + intString);
            return null;
        }
    }

    /** Override state of desktop mode developer option toggle. */
    enum ToggleOverride {
        OVERRIDE_UNSET,
        OVERRIDE_OFF,
        OVERRIDE_ON;

        int getSetting() {
            return switch (this) {
                case OVERRIDE_ON -> 1;
                case OVERRIDE_OFF -> 0;
                case OVERRIDE_UNSET -> -1;
            };
        }

        static ToggleOverride fromSetting(int setting, @Nullable ToggleOverride fallback) {
            return switch (setting) {
                case 1 -> OVERRIDE_ON;
                case 0 -> OVERRIDE_OFF;
                case -1 -> OVERRIDE_UNSET;
                default -> fallback;
            };
        }
    }
}
