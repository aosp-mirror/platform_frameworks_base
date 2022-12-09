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
    public static final boolean IS_SUPPORTED = SystemProperties.getBoolean(
            "persist.wm.debug.desktop_mode", false);

    /**
     * Check if desktop mode is active
     *
     * @return {@code true} if active
     */
    public static boolean isActive(Context context) {
        if (!IS_SUPPORTED) {
            return false;
        }
        try {
            int result = Settings.System.getIntForUser(context.getContentResolver(),
                    Settings.System.DESKTOP_MODE, UserHandle.USER_CURRENT);
            ProtoLog.d(WM_SHELL_DESKTOP_MODE, "isDesktopModeEnabled=%s", result);
            return result != 0;
        } catch (Exception e) {
            ProtoLog.e(WM_SHELL_DESKTOP_MODE, "Failed to read DESKTOP_MODE setting %s", e);
            return false;
        }
    }
}
