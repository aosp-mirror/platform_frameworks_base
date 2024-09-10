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

import android.app.AppGlobals;
import android.content.pm.PackageManager;

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

    final boolean mWallpaperOffsetAsync = Flags.wallpaperOffsetAsync();

    final boolean mAllowsScreenSizeDecoupledFromStatusBarAndCutout =
            Flags.allowsScreenSizeDecoupledFromStatusBarAndCutout();

    final boolean mInsetsDecoupledConfiguration = Flags.insetsDecoupledConfiguration();

    final boolean mRespectNonTopVisibleFixedOrientation =
            Flags.respectNonTopVisibleFixedOrientation();

    final boolean mEnsureWallpaperInTransitions;

    /* End Available Flags */

    WindowManagerFlags() {
        boolean isWatch;
        try {
            isWatch = AppGlobals.getPackageManager().hasSystemFeature(
                        PackageManager.FEATURE_WATCH, 0 /* version */);
        } catch (Throwable e) {
            isWatch = false;
        }
        /*
         * Wallpaper enablement is separated on Wear vs Phone as the latter appears to still exhibit
         * regressions when enabled (for example b/353870983). These don't exist on Wear likely
         * due to differences in SysUI/transition implementations. Wear enablement is required for
         * 25Q2 while phone doesn't have as pressing a constraint and will wait to resolve any
         * outstanding issues prior to roll-out.
         */
        mEnsureWallpaperInTransitions = (isWatch && Flags.ensureWallpaperInWearTransitions())
                || Flags.ensureWallpaperInTransitions();
    }
}
