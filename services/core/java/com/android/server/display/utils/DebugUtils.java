/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.display.utils;

import android.util.Log;

public class DebugUtils {

    public static final boolean DEBUG_ALL = Log.isLoggable("DisplayManager_All", Log.DEBUG);

    /**
     * Returns whether the specified tag has logging enabled. Use the tag name specified in the
     * calling class, or DisplayManager_All to globally enable all tags in display.
     * To enable:
     * adb shell setprop persist.log.tag.DisplayManager_All DEBUG
     * To disable:
     * adb shell setprop persist.log.tag.DisplayManager_All \"\"
     */
    public static boolean isDebuggable(String tag) {
        return Log.isLoggable(tag, Log.DEBUG) || DEBUG_ALL;
    }
}
