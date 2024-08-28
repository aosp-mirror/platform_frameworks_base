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
    DYNAMIC_INITIAL_BOUNDS(Flags::enableWindowingDynamicInitialBounds, false);

    private static final String TAG = "DesktopModeFlagsUtil";
    // Function called to obtain aconfig flag value.
    private final Supplier<Boolean> mFlagFunction;
    // Whether the flag state should be affected by developer option.
    private final boolean mShouldOverrideByDevOption;

    // Local cache for toggle override, which is initialized once on its first access. It needs to
    // be refreshed only on reboots as overridden state is expected to take effect on reboots.
    private static ToggleOverride sCachedToggleOverride;

    DesktopModeFlagsUtil(Supplier<Boolean> flagFunction, boolean shouldOverrideByDevOption) {
        this.mFlagFunction = flagFunction;
        this.mShouldOverrideByDevOption = shouldOverrideByDevOption;
    }

    /**
     * Determines state of flag based on the actual flag and desktop mode developer option
     * overrides.
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
     *  Returns {@link ToggleOverride} from Settings.Global set by toggle.
     */
    private ToggleOverride getToggleOverrideFromSystem(Context context) {
        int settingValue = Settings.Global.getInt(
                context.getContentResolver(),
                Settings.Global.DEVELOPMENT_OVERRIDE_DESKTOP_MODE_FEATURES,
                OVERRIDE_UNSET.getSetting()
        );
        return ToggleOverride.fromSetting(settingValue, OVERRIDE_UNSET);
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
