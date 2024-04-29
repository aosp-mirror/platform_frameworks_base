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

package com.android.settingslib.fuelgauge;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemProperties;
import android.os.UserManager;
import android.provider.Settings;
import android.util.ArraySet;
import android.view.accessibility.AccessibilityManager;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.util.List;

public final class BatteryUtils {

    /** The key to get the time to full from Settings.Global */
    public static final String GLOBAL_TIME_TO_FULL_MILLIS = "time_to_full_millis";

    /** The system property key to check whether the charging string v2 is enabled or not. */
    public static final String PROPERTY_CHARGING_STRING_V2_KEY = "charging_string.apply_v2";

    /** Gets the latest sticky battery intent from the Android system. */
    public static Intent getBatteryIntent(Context context) {
        return context.registerReceiver(
                /*receiver=*/ null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    /** Gets the current active accessibility related packages. */
    public static ArraySet<String> getA11yPackageNames(Context context) {
        context = context.getApplicationContext();
        final ArraySet<String> packageNames = new ArraySet<>();
        final String defaultTtsPackageName = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.TTS_DEFAULT_SYNTH);
        if (defaultTtsPackageName != null) {
            packageNames.add(defaultTtsPackageName);
        }
        // Checks the current active packages.
        final AccessibilityManager accessibilityManager =
                context.getSystemService(AccessibilityManager.class);
        if (!accessibilityManager.isEnabled()) {
            return packageNames;
        }
        final List<AccessibilityServiceInfo> serviceInfoList =
                accessibilityManager.getEnabledAccessibilityServiceList(
                        AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        if (serviceInfoList == null || serviceInfoList.isEmpty()) {
            return packageNames;
        }
        for (AccessibilityServiceInfo serviceInfo : serviceInfoList) {
            final ComponentName serviceComponent = ComponentName.unflattenFromString(
                    serviceInfo.getId());
            if (serviceComponent != null) {
                packageNames.add(serviceComponent.getPackageName());
            }
        }
        return packageNames;
    }

    /** Returns true if current user is a work profile user. */
    public static boolean isWorkProfile(Context context) {
        final UserManager userManager = context.getSystemService(UserManager.class);
        return userManager.isManagedProfile() && !userManager.isSystemUser();
    }

    private static Boolean sChargingStringV2Enabled = null;

    /** Returns {@code true} if the charging string v2 is enabled. */
    public static boolean isChargingStringV2Enabled() {
        if (sChargingStringV2Enabled == null) {
            sChargingStringV2Enabled =
                    SystemProperties.getBoolean(PROPERTY_CHARGING_STRING_V2_KEY, false);
        }
        return sChargingStringV2Enabled;
    }


    /** Used to override the system property to enable or reset for charging string V2. */
    @VisibleForTesting
    public static void setChargingStringV2Enabled(Boolean enabled) {
        setChargingStringV2Enabled(enabled, true /* updateProperty */);
    }

    /** Used to override the system property to enable or reset for charging string V2. */
    @VisibleForTesting
    public static void setChargingStringV2Enabled(
            @Nullable Boolean enabled, boolean updateProperty) {
        if (updateProperty) {
            SystemProperties.set(
                    BatteryUtils.PROPERTY_CHARGING_STRING_V2_KEY,
                    enabled == null ? "" : String.valueOf(enabled));
        }
        BatteryUtils.sChargingStringV2Enabled = enabled;
    }
}
