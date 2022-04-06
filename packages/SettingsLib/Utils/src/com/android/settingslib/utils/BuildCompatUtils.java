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
import android.os.Build.VERSION;

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
     * Implementation of BuildCompat.isAtLeast*() suitable for use in Settings
     *
     * <p>This still should try using BuildCompat.isAtLeastR() as source of truth, but also checking
     * for VERSION_SDK_INT and VERSION.CODENAME in case when BuildCompat implementation returned
     * false. Note that both checks should be >= and not = to make sure that when Android version
     * increases (i.e., from R to S), this does not stop working.
     *
     * <p>Supported configurations:
     *
     * <ul>
     *   <li>For current Android release: when new API is not finalized yet (CODENAME = "Tiramisu",
     *   SDK_INT = 32)
     *   <li>For current Android release: when new API is finalized (CODENAME = "REL", SDK_INT = 33)
     *   <li>For next Android release (CODENAME = "U", SDK_INT = 34+)
     * </ul>
     *
     * <p>Note that Build.VERSION_CODES.S cannot be used here until final SDK is available, because
     * it is equal to Build.VERSION_CODES.CUR_DEVELOPMENT before API finalization.
     *
     * @return Whether the current OS version is higher or equal to T.
     */
    public static boolean isAtLeastT() {
        if (!isAtLeastS()) {
            return false;
        }

        return (VERSION.CODENAME.equals("REL") && VERSION.SDK_INT >= 33)
                || (VERSION.CODENAME.length() >= 1
                && VERSION.CODENAME.toUpperCase().charAt(0) >= 'T'
                && VERSION.CODENAME.toUpperCase().charAt(0) <= 'Z')
                || (Build.VERSION.CODENAME.equals("Tiramisu") && Build.VERSION.SDK_INT >= 32);
    }

    private BuildCompatUtils() {}
}
