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

import android.content.Context;
import android.os.SystemProperties;
import androidx.annotation.VisibleForTesting;
import android.telephony.CarrierConfigManager;

public class TetherUtil {

    @VisibleForTesting
    static boolean isEntitlementCheckRequired(Context context) {
        final CarrierConfigManager configManager = (CarrierConfigManager) context
             .getSystemService(Context.CARRIER_CONFIG_SERVICE);
        if (configManager == null || configManager.getConfig() == null) {
            // return service default
            return true;
        }
        return configManager.getConfig().getBoolean(CarrierConfigManager
             .KEY_REQUIRE_ENTITLEMENT_CHECKS_BOOL);
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
        // Check carrier config for entitlement checks
        if (isEntitlementCheckRequired(context) == false) {
            return false;
        }
        return (provisionApp.length == 2);
    }
}
