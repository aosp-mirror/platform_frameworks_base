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

package android.window;

import android.annotation.Nullable;
import android.app.ActivityThread;
import android.app.Application;
import android.content.ContentResolver;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;

import com.android.window.flags.Flags;

import java.util.function.BooleanSupplier;

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
    ENABLE_TILE_RESIZING(Flags::enableTileResizing, true),
    ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY(
            Flags::enableDesktopWindowingWallpaperActivity, true),
    ENABLE_DESKTOP_WINDOWING_MODALS_POLICY(Flags::enableDesktopWindowingModalsPolicy, true),
    ENABLE_THEMED_APP_HEADERS(Flags::enableThemedAppHeaders, true),
    ENABLE_HOLD_TO_DRAG_APP_HANDLE(Flags::enableHoldToDragAppHandle, true),
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
    ENABLE_WINDOWING_TRANSITION_HANDLERS_OBSERVERS(
            Flags::enableWindowingTransitionHandlersObservers, false),
    ENABLE_DESKTOP_WINDOWING_PERSISTENCE(Flags::enableDesktopWindowingPersistence, false),
    ENABLE_HANDLE_INPUT_FIX(Flags::enableHandleInputFix, true),
    ENABLE_DESKTOP_WINDOWING_ENTER_TRANSITIONS_BUGFIX(
            Flags::enableDesktopWindowingEnterTransitionBugfix, true),
    ENABLE_DESKTOP_WINDOWING_EXIT_TRANSITIONS_BUGFIX(
            Flags::enableDesktopWindowingExitTransitionsBugfix, true),
    ENABLE_DESKTOP_APP_LAUNCH_ALTTAB_TRANSITIONS_BUGFIX(
            Flags::enableDesktopAppLaunchAlttabTransitionsBugfix, true),
    ENABLE_DESKTOP_APP_LAUNCH_TRANSITIONS_BUGFIX(
            Flags::enableDesktopAppLaunchTransitionsBugfix, true),
    ENABLE_DESKTOP_COMPAT_UI_VISIBILITY_STATUS(
            Flags::enableCompatUiVisibilityStatus, true),
    ENABLE_DESKTOP_SKIP_COMPAT_UI_EDUCATION_IN_DESKTOP_MODE_BUGFIX(
            Flags::skipCompatUiEducationInDesktopMode, true),
    INCLUDE_TOP_TRANSPARENT_FULLSCREEN_TASK_IN_DESKTOP_HEURISTIC(
            Flags::includeTopTransparentFullscreenTaskInDesktopHeuristic, true),
    ENABLE_DESKTOP_WINDOWING_HSUM(Flags::enableDesktopWindowingHsum, true),
    ENABLE_MINIMIZE_BUTTON(Flags::enableMinimizeButton, true),
    ENABLE_RESIZING_METRICS(Flags::enableResizingMetrics, true),
    ENABLE_TASK_RESIZING_KEYBOARD_SHORTCUTS(Flags::enableTaskResizingKeyboardShortcuts, true),
    ENABLE_DESKTOP_WALLPAPER_ACTIVITY_FOR_SYSTEM_USER(
        Flags::enableDesktopWallpaperActivityForSystemUser, true),
    ENABLE_TOP_VISIBLE_ROOT_TASK_PER_USER_TRACKING(
        Flags::enableTopVisibleRootTaskPerUserTracking, true),
    ENABLE_DESKTOP_RECENTS_TRANSITIONS_CORNERS_BUGFIX(
            Flags::enableDesktopRecentsTransitionsCornersBugfix, false),
    ENABLE_DESKTOP_SYSTEM_DIALOGS_TRANSITIONS(Flags::enableDesktopSystemDialogsTransitions, true),
    ENABLE_DESKTOP_WINDOWING_MULTI_INSTANCE_FEATURES(
        Flags::enableDesktopWindowingMultiInstanceFeatures, true);

    /**
     * Flag class, to be used in case the enum cannot be used because the flag is not accessible.
     *
     * <p> This class will still use the process-wide cache.
     */
    public static class DesktopModeFlag {
        // Function called to obtain aconfig flag value.
        private final BooleanSupplier mFlagFunction;
        // Whether the flag state should be affected by developer option.
        private final boolean mShouldOverrideByDevOption;

        public DesktopModeFlag(BooleanSupplier flagFunction, boolean shouldOverrideByDevOption) {
            this.mFlagFunction = flagFunction;
            this.mShouldOverrideByDevOption = shouldOverrideByDevOption;
        }

        /**
         * Determines state of flag based on the actual flag and desktop mode developer option
         * overrides.
         */
        public boolean isTrue() {
            return isFlagTrue(mFlagFunction, mShouldOverrideByDevOption);
        }

    }

    private static final String TAG = "DesktopModeFlags";
    // Function called to obtain aconfig flag value.
    private final BooleanSupplier mFlagFunction;
    // Whether the flag state should be affected by developer option.
    private final boolean mShouldOverrideByDevOption;

    // Local cache for toggle override, which is initialized once on its first access. It needs to
    // be refreshed only on reboots as overridden state is expected to take effect on reboots.
    private static ToggleOverride sCachedToggleOverride;

    public static final String SYSTEM_PROPERTY_NAME = "persist.wm.debug.desktop_experience_devopts";

    DesktopModeFlags(BooleanSupplier flagFunction, boolean shouldOverrideByDevOption) {
        this.mFlagFunction = flagFunction;
        this.mShouldOverrideByDevOption = shouldOverrideByDevOption;
    }

    /**
     * Determines state of flag based on the actual flag and desktop mode developer option
     * overrides.
     */
    public boolean isTrue() {
        return isFlagTrue(mFlagFunction, mShouldOverrideByDevOption);
    }

    public static boolean isDesktopModeForcedEnabled() {
        return getToggleOverride() == ToggleOverride.OVERRIDE_ON;
    }

    private static boolean isFlagTrue(BooleanSupplier flagFunction,
            boolean shouldOverrideByDevOption) {
        if (!shouldOverrideByDevOption) return flagFunction.getAsBoolean();
        if (Flags.showDesktopExperienceDevOption()) {
            return switch (getToggleOverride()) {
                case OVERRIDE_UNSET, OVERRIDE_OFF -> flagFunction.getAsBoolean();
                case OVERRIDE_ON -> true;
            };
        }
        if (Flags.showDesktopWindowingDevOption()) {
            boolean shouldToggleBeEnabledByDefault = Flags.enableDesktopWindowingMode();
            return switch (getToggleOverride()) {
                case OVERRIDE_UNSET -> flagFunction.getAsBoolean();
                // When toggle override matches its default state, don't override flags. This
                // helps users reset their feature overrides.
                case OVERRIDE_OFF -> !shouldToggleBeEnabledByDefault && flagFunction.getAsBoolean();
                case OVERRIDE_ON -> !shouldToggleBeEnabledByDefault || flagFunction.getAsBoolean();
            };
        }
        return flagFunction.getAsBoolean();
    }

    private static ToggleOverride getToggleOverride() {
        // If cached, return it
        if (sCachedToggleOverride != null) {
            return sCachedToggleOverride;
        }
        // Otherwise, fetch and cache it
        ToggleOverride override = getToggleOverrideFromSystem();
        sCachedToggleOverride = override;
        Log.d(TAG, "Toggle override initialized to: " + override);
        return override;
    }

    /**
     *  Returns {@link ToggleOverride} from Settings.Global set by toggle.
     */
    private static ToggleOverride getToggleOverrideFromSystem() {
        int settingValue;
        if (Flags.showDesktopExperienceDevOption()) {
            settingValue = SystemProperties.getInt(
                    SYSTEM_PROPERTY_NAME,
                    ToggleOverride.OVERRIDE_UNSET.getSetting()
            );
        } else {
            final Application application = ActivityThread.currentApplication();
            if (application == null) {
                Log.w(TAG, "Could not get the current application.");
                return ToggleOverride.OVERRIDE_UNSET;
            }
            final ContentResolver contentResolver = application.getContentResolver();
            if (contentResolver == null) {
                Log.w(TAG, "Could not get the content resolver for the application.");
                return ToggleOverride.OVERRIDE_UNSET;
            }
            settingValue = Settings.Global.getInt(
                    contentResolver,
                    Settings.Global.DEVELOPMENT_OVERRIDE_DESKTOP_MODE_FEATURES,
                    ToggleOverride.OVERRIDE_UNSET.getSetting()
            );
        }
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
