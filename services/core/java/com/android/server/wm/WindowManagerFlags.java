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

package com.android.server.wm;

import com.android.window.flags.Flags;

/**
 * Utility class to read the flags used in the WindowManager server.
 *
 * It is not very cheap to read trunk stable flag, so having a centralized place to cache the flag
 * values in the system server side.
 *
 * Flags should be defined in `core.java.android.window.flags` to allow access from client side.
 *
 * To override flag:
 *   adb shell device_config put [namespace] [package].[name] [true/false]
 *   adb reboot
 *
 * To access in wm:
 *   {@link WindowManagerService#mFlags}
 *
 * Notes:
 *   The system may use flags at anytime, so changing flags will only take effect after device
 *   reboot. Otherwise, it may result unexpected behavior, such as broken transition.
 *   When a flag needs to be read from both the server side and the client side, changing the flag
 *   value will result difference in server and client until device reboot.
 */
class WindowManagerFlags {

    /* Start Available Flags */

    final boolean mWindowStateResizeItemFlag = Flags.windowStateResizeItemFlag();

    final boolean mWallpaperOffsetAsync = Flags.wallpaperOffsetAsync();

    final boolean mAllowsScreenSizeDecoupledFromStatusBarAndCutout =
            Flags.allowsScreenSizeDecoupledFromStatusBarAndCutout();

    /* End Available Flags */
}
