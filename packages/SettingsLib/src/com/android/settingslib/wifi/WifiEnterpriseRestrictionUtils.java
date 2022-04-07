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

package com.android.settingslib.wifi;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.UserManager;
import android.util.Log;

import androidx.annotation.ChecksSdkIntAtLeast;

import com.android.internal.annotations.VisibleForTesting;

/* Utility class is to confirm the Wi-Fi function is available by enterprise restriction */
public class WifiEnterpriseRestrictionUtils {
    private static final String TAG = "WifiEntResUtils";

    /**
     * Confirm Wi-Fi tethering is allowed according to whether user restriction is set
     *
     * @param context A context
     * @return whether the device is permitted to use Wi-Fi Tethering
     */
    public static boolean isWifiTetheringAllowed(Context context) {
        final UserManager userManager = context.getSystemService(UserManager.class);
        final Bundle restrictions = userManager.getUserRestrictions();
        if (isAtLeastT() && restrictions.getBoolean(UserManager.DISALLOW_WIFI_TETHERING)) {
            Log.i(TAG, "Wi-Fi Tethering isn't available due to user restriction.");
            return false;
        }
        return true;
    }

    /**
     * Confirm Wi-Fi Direct is allowed according to whether user restriction is set
     *
     * @param context A context
     * @return whether the device is permitted to use Wi-Fi Direct
     */
    public static boolean isWifiDirectAllowed(Context context) {
        final UserManager userManager = context.getSystemService(UserManager.class);
        final Bundle restrictions = userManager.getUserRestrictions();
        if (isAtLeastT() && restrictions.getBoolean(UserManager.DISALLOW_WIFI_DIRECT)) {
            Log.i(TAG, "Wi-Fi Direct isn't available due to user restriction.");
            return false;
        }
        return true;
    }

    /**
     * Confirm Wi-Fi Config is allowed to add according to whether user restriction is set
     *
     * @param context A context
     * @return whether the device is permitted to add new Wi-Fi config
     */
    public static boolean isAddWifiConfigAllowed(Context context) {
        final UserManager userManager = context.getSystemService(UserManager.class);
        final Bundle restrictions = userManager.getUserRestrictions();
        if (isAtLeastT() && restrictions.getBoolean(UserManager.DISALLOW_ADD_WIFI_CONFIG)) {
            Log.i(TAG, "Wi-Fi Add network isn't available due to user restriction.");
            return false;
        }
        return true;
    }

    /**
     * Confirm Wi-Fi state is allowed to change to whether user restriction is set
     *
     * @param context A context
     * @return whether the device is permitted to change Wi-Fi state
     */
    public static boolean isChangeWifiStateAllowed(Context context) {
        if (!hasUserRestrictionFromT(context, UserManager.DISALLOW_CHANGE_WIFI_STATE)) return true;
        Log.w(TAG, "WI-FI state isn't allowed to change due to user restriction.");
        return false;
    }

    @VisibleForTesting
    static boolean hasUserRestrictionFromT(Context context, String restrictionKey) {
        if (!isAtLeastT()) return false;
        final UserManager userManager = context.getSystemService(UserManager.class);
        if (userManager == null) return false;
        return userManager.hasUserRestriction(restrictionKey);
    }

    @ChecksSdkIntAtLeast(api=Build.VERSION_CODES.TIRAMISU)
    private static boolean isAtLeastT() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }
}
