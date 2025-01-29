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
import android.os.SystemProperties;
import android.util.Log;

import com.android.window.flags.Flags;

import java.util.function.BooleanSupplier;

/**
 * Checks Desktop Experience flag state.
 *
 * <p>This enum provides a centralized way to control the behavior of flags related to desktop
 * experience features which are aiming for developer preview before their release. It allows
 * developer option to override the default behavior of these flags.
 *
 * <p>The flags here will be controlled by the {@code
 * persist.wm.debug.desktop_experience_devopts} system property.
 *
 * <p>NOTE: Flags should only be added to this enum when they have received Product and UX alignment
 * that the feature is ready for developer preview, otherwise just do a flag check.
 *
 * @hide
 */
public enum DesktopExperienceFlags {
    ENABLE_DISPLAY_CONTENT_MODE_MANAGEMENT(
            com.android.server.display.feature.flags.Flags::enableDisplayContentModeManagement,
            true),
    ACTIVITY_EMBEDDING_SUPPORT_FOR_CONNECTED_DISPLAYS(
            Flags::activityEmbeddingSupportForConnectedDisplays, false),
    BASE_DENSITY_FOR_EXTERNAL_DISPLAYS(
            com.android.server.display.feature.flags.Flags::baseDensityForExternalDisplays, true),
    CONNECTED_DISPLAYS_CURSOR(com.android.input.flags.Flags::connectedDisplaysCursor, true),
    DISPLAY_TOPOLOGY(com.android.server.display.feature.flags.Flags::displayTopology, true),
    ENABLE_BUG_FIXES_FOR_SECONDARY_DISPLAY(Flags::enableBugFixesForSecondaryDisplay, false),
    ENABLE_CONNECTED_DISPLAYS_DND(Flags::enableConnectedDisplaysDnd, false),
    ENABLE_CONNECTED_DISPLAYS_PIP(Flags::enableConnectedDisplaysPip, false),
    ENABLE_CONNECTED_DISPLAYS_WINDOW_DRAG(Flags::enableConnectedDisplaysWindowDrag, false),
    ENABLE_DISPLAY_FOCUS_IN_SHELL_TRANSITIONS(Flags::enableDisplayFocusInShellTransitions, false),
    ENABLE_DISPLAY_WINDOWING_MODE_SWITCHING(Flags::enableDisplayWindowingModeSwitching, false),
    ENABLE_DRAG_TO_MAXIMIZE(Flags::enableDragToMaximize, true),
    ENABLE_MOVE_TO_NEXT_DISPLAY_SHORTCUT(Flags::enableMoveToNextDisplayShortcut, false),
    ENABLE_MULTIPLE_DESKTOPS_BACKEND(Flags::enableMultipleDesktopsBackend, false),
    ENABLE_MULTIPLE_DESKTOPS_FRONTEND(Flags::enableMultipleDesktopsFrontend, false),
    ENABLE_PER_DISPLAY_DESKTOP_WALLPAPER_ACTIVITY(Flags::enablePerDisplayDesktopWallpaperActivity,
            false),
    ENABLE_PER_DISPLAY_PACKAGE_CONTEXT_CACHE_IN_STATUSBAR_NOTIF(
            Flags::enablePerDisplayPackageContextCacheInStatusbarNotif, false),
    ENTER_DESKTOP_BY_DEFAULT_ON_FREEFORM_DISPLAYS(Flags::enterDesktopByDefaultOnFreeformDisplays,
            false),
    REPARENT_WINDOW_TOKEN_API(Flags::reparentWindowTokenApi, true);

    /**
     * Flag class, to be used in case the enum cannot be used because the flag is not accessible.
     *
     * <p>This class will still use the process-wide cache.
     */
    public static class DesktopExperienceFlag {
        // Function called to obtain aconfig flag value.
        private final BooleanSupplier mFlagFunction;
        // Whether the flag state should be affected by developer option.
        private final boolean mShouldOverrideByDevOption;

        public DesktopExperienceFlag(BooleanSupplier flagFunction,
                boolean shouldOverrideByDevOption) {
            this.mFlagFunction = flagFunction;
            this.mShouldOverrideByDevOption = shouldOverrideByDevOption;
        }

        /**
         * Determines state of flag based on the actual flag and desktop experience developer option
         * overrides.
         */
        public boolean isTrue() {
            return isFlagTrue(mFlagFunction, mShouldOverrideByDevOption);
        }
    }

    private static final String TAG = "DesktopExperienceFlags";
    // Function called to obtain aconfig flag value.
    private final BooleanSupplier mFlagFunction;
    // Whether the flag state should be affected by developer option.
    private final boolean mShouldOverrideByDevOption;

    // Local cache for toggle override, which is initialized once on its first access. It needs to
    // be refreshed only on reboots as overridden state is expected to take effect on reboots.
    @Nullable
    private static Boolean sCachedToggleOverride;

    public static final String SYSTEM_PROPERTY_NAME = "persist.wm.debug.desktop_experience_devopts";

    DesktopExperienceFlags(BooleanSupplier flagFunction, boolean shouldOverrideByDevOption) {
        this.mFlagFunction = flagFunction;
        this.mShouldOverrideByDevOption = shouldOverrideByDevOption;
    }

    /**
     * Determines state of flag based on the actual flag and desktop experience developer option
     * overrides.
     */
    public boolean isTrue() {
        return isFlagTrue(mFlagFunction, mShouldOverrideByDevOption);
    }

    private static boolean isFlagTrue(
            BooleanSupplier flagFunction, boolean shouldOverrideByDevOption) {
        if (shouldOverrideByDevOption
                && Flags.showDesktopExperienceDevOption()
                && getToggleOverride()) {
            return true;
        }
        return flagFunction.getAsBoolean();
    }

    private static boolean getToggleOverride() {
        // If cached, return it
        if (sCachedToggleOverride != null) {
            return sCachedToggleOverride;
        }

        // Otherwise, fetch and cache it
        boolean override = getToggleOverrideFromSystem();
        sCachedToggleOverride = override;
        Log.d(TAG, "Toggle override initialized to: " + override);
        return override;
    }

    /** Returns the {@link ToggleOverride} from the system property.. */
    private static boolean getToggleOverrideFromSystem() {
        return SystemProperties.getBoolean(SYSTEM_PROPERTY_NAME, false);
    }
}
