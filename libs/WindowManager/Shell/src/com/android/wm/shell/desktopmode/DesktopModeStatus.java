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

import static com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE;

import android.content.Context;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.internal.protolog.common.ProtoLog;

/**
 * Constants for desktop mode feature
 */
public class DesktopModeStatus {

    /**
     * Flag to indicate whether desktop mode is available on the device
     */
    private static final boolean IS_SUPPORTED = SystemProperties.getBoolean(
            "persist.wm.debug.desktop_mode", false);

    /**
     * Flag to indicate whether desktop mode proto 2 is available on the device
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
     * Return {@code true} if desktop mode support is enabled
     */
    public static boolean isProto1Enabled() {
        return IS_SUPPORTED;
    }

    /**
     * Return {@code true} is desktop windowing proto 2 is enabled
     */
    public static boolean isProto2Enabled() {
        return IS_PROTO2_ENABLED;
    }

    /**
     * Return {@code true} if proto 1 or 2 is enabled.
     * Can be used to guard logic that is common for both prototypes.
     */
    public static boolean isAnyEnabled() {
        return isProto1Enabled() || isProto2Enabled();
    }

    /**
     * Return {@code true} if veiled resizing is active. If false, fluid resizing is used.
     */
    public static boolean isVeiledResizeEnabled() {
        return IS_VEILED_RESIZE_ENABLED;
    }

    /**
     * Check if desktop mode is active
     *
     * @return {@code true} if active
     */
    public static boolean isActive(Context context) {
        if (!isAnyEnabled()) {
            return false;
        }
        if (isProto2Enabled()) {
            // Desktop mode is always active in prototype 2
            return true;
        }
        try {
            int result = Settings.System.getIntForUser(context.getContentResolver(),
                    Settings.System.DESKTOP_MODE, UserHandle.USER_CURRENT);
            return result != 0;
        } catch (Exception e) {
            ProtoLog.e(WM_SHELL_DESKTOP_MODE, "Failed to read DESKTOP_MODE setting %s", e);
            return false;
        }
    }
}
