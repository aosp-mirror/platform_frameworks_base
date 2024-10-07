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

package com.android.server.wm;

import android.annotation.NonNull;
import android.content.Context;
import android.os.SystemProperties;
import android.window.flags.DesktopModeFlags;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;

/**
 * Constants for desktop mode feature
 */
public final class DesktopModeHelper {
    /**
     * Flag to indicate whether to restrict desktop mode to supported devices.
     */
    private static final boolean ENFORCE_DEVICE_RESTRICTIONS = SystemProperties.getBoolean(
            "persist.wm.debug.desktop_mode_enforce_device_restrictions", true);

    /** Whether desktop mode is enabled. */
    static boolean isDesktopModeEnabled() {
        return DesktopModeFlags.ENABLE_DESKTOP_WINDOWING_MODE.isTrue();
    }

    /**
     * Return {@code true} if desktop mode should be restricted to supported devices.
     */
    @VisibleForTesting
    static boolean shouldEnforceDeviceRestrictions() {
        return ENFORCE_DEVICE_RESTRICTIONS;
    }

    /**
     * Return {@code true} if the current device supports desktop mode.
     */
    // TODO(b/337819319): use a companion object instead.
    @VisibleForTesting
    static boolean isDesktopModeSupported(@NonNull Context context) {
        return context.getResources().getBoolean(R.bool.config_isDesktopModeSupported);
    }

    /**
     * Return {@code true} if desktop mode can be entered on the current device.
     */
    static boolean canEnterDesktopMode(@NonNull Context context) {
        return isDesktopModeEnabled()
                && (!shouldEnforceDeviceRestrictions() || isDesktopModeSupported(context));
    }
}
