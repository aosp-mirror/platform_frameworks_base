/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.settingslib;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;

public class TetherUtil {

    // Types of tethering.
    public static final int TETHERING_INVALID   = -1;
    public static final int TETHERING_WIFI      = 0;
    public static final int TETHERING_USB       = 1;
    public static final int TETHERING_BLUETOOTH = 2;

    // Extras used for communicating with the TetherService.
    public static final String EXTRA_ADD_TETHER_TYPE = "extraAddTetherType";
    public static final String EXTRA_REM_TETHER_TYPE = "extraRemTetherType";
    public static final String EXTRA_SET_ALARM = "extraSetAlarm";
    /**
     * Tells the service to run a provision check now.
     */
    public static final String EXTRA_RUN_PROVISION = "extraRunProvision";
    /**
     * Enables wifi tethering if the provision check is successful. Used by
     * QS to enable tethering.
     */
    public static final String EXTRA_ENABLE_WIFI_TETHER = "extraEnableWifiTether";

    public static ComponentName TETHER_SERVICE = ComponentName.unflattenFromString(Resources
            .getSystem().getString(com.android.internal.R.string.config_wifi_tether_enable));

    public static boolean setWifiTethering(boolean enable, Context context) {
        final WifiManager wifiManager =
                (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        final ContentResolver cr = context.getContentResolver();
        /**
         * Disable Wifi if enabling tethering
         */
        int wifiState = wifiManager.getWifiState();
        if (enable && ((wifiState == WifiManager.WIFI_STATE_ENABLING) ||
                    (wifiState == WifiManager.WIFI_STATE_ENABLED))) {
            wifiManager.setWifiEnabled(false);
            Settings.Global.putInt(cr, Settings.Global.WIFI_SAVED_STATE, 1);
        }

        boolean success = wifiManager.setWifiApEnabled(null, enable);
        /**
         *  If needed, restore Wifi on tether disable
         */
        if (!enable) {
            int wifiSavedState = Settings.Global.getInt(cr, Settings.Global.WIFI_SAVED_STATE, 0);
            if (wifiSavedState == 1) {
                wifiManager.setWifiEnabled(true);
                Settings.Global.putInt(cr, Settings.Global.WIFI_SAVED_STATE, 0);
            }
        }
        return success;
    }

    public static boolean isWifiTetherEnabled(Context context) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        return wifiManager.getWifiApState() == WifiManager.WIFI_AP_STATE_ENABLED;
    }

    public static boolean isProvisioningNeeded(Context context) {
        // Keep in sync with other usage of config_mobile_hotspot_provision_app.
        // ConnectivityManager#enforceTetherChangePermission
        String[] provisionApp = context.getResources().getStringArray(
                com.android.internal.R.array.config_mobile_hotspot_provision_app);
        if (SystemProperties.getBoolean("net.tethering.noprovisioning", false)
                || provisionApp == null) {
            return false;
        }
        return (provisionApp.length == 2);
    }

    public static boolean isTetheringSupported(Context context) {
        final ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        final boolean isSecondaryUser = ActivityManager.getCurrentUser() != UserHandle.USER_OWNER;
        return !isSecondaryUser && cm.isTetheringSupported();
    }

}
