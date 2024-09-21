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

package android.window.flags;

import android.annotation.Nullable;
import android.app.ActivityThread;
import android.app.Application;
import android.content.ContentResolver;
import android.provider.Settings;
import android.util.Log;

import com.android.window.flags.Flags;

import java.util.function.Supplier;

/**
 * Checks desktop mode flag state.
 *
 * <p>This enum provides a centralized way to control the behavior of flags related to desktop
 * windowing features which are aiming for developer preview before their release. It allows
 * developer option to override the default behavior of these flags.
 *
 * <p>NOTE: Flags should only be added to this enum when they have received Product and UX
 * alignment that the feature is ready for developer preview, otherwise just do a flag check.
 *
 * @hide
 */
public enum DesktopModeFlags {
    // All desktop mode related flags to be overridden by developer option toggle will be added here
    ENABLE_DESKTOP_WINDOWING_MODE(
            Flags::enableDesktopWindowingMode, /* shouldOverrideByDevOption= */ true),
    ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS(Flags::enableWindowingDynamicInitialBounds, true),
    ENABLE_CAPTION_COMPAT_INSET_FORCE_CONSUMPTION(
            Flags::enableCaptionCompatInsetForceConsumption, true),
    ENABLE_CAPTION_COMPAT_INSET_FORCE_CONSUMPTION_ALWAYS(
            Flags::enableCaptionCompatInsetForceConsumptionAlways, true),
    ENABLE_CASCADING_WINDOWS(Flags::enableCascadingWindows, true),
    ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY(
            Flags::enableDesktopWindowingWallpaperActivity, true),
    ENABLE_DESKTOP_WINDOWING_MODALS_POLICY(Flags::enableDesktopWindowingModalsPolicy, true),
    ENABLE_THEMED_APP_HEADERS(Flags::enableThemedAppHeaders, true),
    ENABLE_DESKTOP_WINDOWING_QUICK_SWITCH(Flags::enableDesktopWindowingQuickSwitch, true),
    ENABLE_APP_HEADER_WITH_TASK_DENSITY(Flags::enableAppHeaderWithTaskDensity, true),
    ENABLE_TASK_STACK_OBSERVER_IN_SHELL(Flags::enableTaskStackObserverInShell, true),
    ENABLE_DESKTOP_WINDOWING_SIZE_CONSTRAINTS(Flags::enableDesktopWindowingSizeConstraints, true),
    DISABLE_NON_RESIZABLE_APP_SNAP_RESIZE(Flags::disableNonResizableAppSnapResizing, true),
    ENABLE_WINDOWING_SCALED_RESIZING(Flags::enableWindowingScaledResizing, true),
    ENABLE_DESKTOP_WINDOWING_TASK_LIMIT(Flags::enableDesktopWindowingTaskLimit, true),
    ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION(Flags::enableDesktopWindowingBackNavigation, true),
    ENABLE_WINDOWING_EDGE_DRAG_RESIZE(Flags::enableWindowingEdgeDragResize, true),
    ENABLE_DESKTOP_WINDOWING_TASKBAR_RUNNING_APPS(
            Flags::enableDesktopWindowingTaskbarRunningApps, true),
    ENABLE_DESKTOP_WINDOWING_TRANSITIONS(Flags::enableDesktopWindowingTransitions, false);

    private static final String TAG = "DesktopModeFlagsUtil";
    // Function called to obtain aconfig flag value.
    private final Supplier<Boolean> mFlagFunction;
    // Whether the flag state should be affected by developer option.
    private final boolean mShouldOverrideByDevOption;

    // Local cache for toggle override, which is initialized once on its first access. It needs to
    // be refreshed only on reboots as overridden state is expected to take effect on reboots.
    private static ToggleOverride sCachedToggleOverride;

    DesktopModeFlags(Supplier<Boolean> flagFunction, boolean shouldOverrideByDevOption) {
        this.mFlagFunction = flagFunction;
        this.mShouldOverrideByDevOption = shouldOverrideByDevOption;
    }

    /**
     * Determines state of flag based on the actual flag and desktop mode developer option
     * overrides.
     */
    public boolean isTrue() {
        Application application = ActivityThread.currentApplication();
        if (!Flags.showDesktopWindowingDevOption()
                || !mShouldOverrideByDevOption
                || application == null) {
            return mFlagFunction.get();
        } else {
            boolean shouldToggleBeEnabledByDefault = Flags.enableDesktopWindowingMode();
            return switch (getToggleOverride(application.getContentResolver())) {
                case OVERRIDE_UNSET -> mFlagFunction.get();
                // When toggle override matches its default state, don't override flags. This
                // helps users reset their feature overrides.
                case OVERRIDE_OFF -> !shouldToggleBeEnabledByDefault && mFlagFunction.get();
                case OVERRIDE_ON -> shouldToggleBeEnabledByDefault ? mFlagFunction.get() : true;
            };
        }
    }

    private ToggleOverride getToggleOverride(ContentResolver contentResolver) {
        // If cached, return it
        if (sCachedToggleOverride != null) {
            return sCachedToggleOverride;
        }

        // Otherwise, fetch and cache it
        ToggleOverride override = getToggleOverrideFromSystem(contentResolver);
        sCachedToggleOverride = override;
        Log.d(TAG, "Toggle override initialized to: " + override);
        return override;
    }

    /**
     *  Returns {@link ToggleOverride} from Settings.Global set by toggle.
     */
    private ToggleOverride getToggleOverrideFromSystem(ContentResolver contentResolver) {
        int settingValue = Settings.Global.getInt(
                contentResolver,
                Settings.Global.DEVELOPMENT_OVERRIDE_DESKTOP_MODE_FEATURES,
                ToggleOverride.OVERRIDE_UNSET.getSetting()
        );
        return ToggleOverride.fromSetting(settingValue, ToggleOverride.OVERRIDE_UNSET);
    }

    /** Override state of desktop mode developer option toggle. */
    public enum ToggleOverride {
        OVERRIDE_UNSET,
        OVERRIDE_OFF,
        OVERRIDE_ON;

        /** Returns the integer representation of this {@code ToggleOverride}. */
        public int getSetting() {
            return switch (this) {
                case OVERRIDE_ON -> 1;
                case OVERRIDE_OFF -> 0;
                case OVERRIDE_UNSET -> -1;
            };
        }

        /** Returns the {@code ToggleOverride} corresponding to a given integer setting. */
        public static ToggleOverride fromSetting(int setting, @Nullable ToggleOverride fallback) {
            return switch (setting) {
                case 1 -> OVERRIDE_ON;
                case 0 -> OVERRIDE_OFF;
                case -1 -> OVERRIDE_UNSET;
                default -> fallback;
            };
        }
    }
}
