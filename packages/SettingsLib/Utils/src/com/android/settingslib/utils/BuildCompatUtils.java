/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.settingslib.utils;

import android.os.Build;

import androidx.annotation.ChecksSdkIntAtLeast;

/**
 * An util class to check whether the current OS version is higher or equal to sdk version of
 * device.
 */
public final class BuildCompatUtils {

    /**
     * Implementation of BuildCompat.isAtLeastS() suitable for use in Settings
     *
     * @return Whether the current OS version is higher or equal to S.
     */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
    public static boolean isAtLeastS() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    /**
     * Implementation of BuildCompat.isAtLeastS() suitable for use in Settings
     *
     * @return Whether the current OS version is higher or equal to Sv2.
     */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S_V2)
    public static boolean isAtLeastSV2() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2;
    }

    /**
     * Implementation of BuildCompat.isAtLeastT() suitable for use in Settings
     *
     * @return Whether the current OS version is higher or equal to T.
     */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    public static boolean isAtLeastT() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    private BuildCompatUtils() {}
}
