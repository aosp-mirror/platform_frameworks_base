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

import static com.android.server.display.feature.flags.Flags.enableDisplayContentModeManagement;

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
    ENABLE_DISPLAY_CONTENT_MODE_MANAGEMENT(() -> enableDisplayContentModeManagement(), true);

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

        public DesktopExperienceFlag(BooleanSupplier flagFunction, boolean shouldOverrideByDevOption) {
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
    @Nullable private static Boolean sCachedToggleOverride;

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
