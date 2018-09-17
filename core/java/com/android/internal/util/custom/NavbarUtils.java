/*
* Copyright (C) 2017 The Pixel Experience Project
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package com.android.internal.util.custom;

import android.content.Context;

import android.os.SystemProperties;
import android.provider.Settings;
import android.os.UserHandle;

public class NavbarUtils {
    public static boolean hasNavbarByDefault(Context context) {
        boolean needsNav = context.getResources().getBoolean(com.android.internal.R.bool.config_showNavigationBar);
        String navBarOverride = SystemProperties.get("qemu.hw.mainkeys");
        if ("1".equals(navBarOverride)) {
            needsNav = false;
        } else if ("0".equals(navBarOverride)) {
            needsNav = true;
        }
        return needsNav;
    }
    public static boolean isEnabled(Context context) {
        return Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.NAVIGATION_BAR_SHOW, hasNavbarByDefault(context) ? 1 : 0, UserHandle.USER_CURRENT) != 0;
    }
    public static void setEnabled(Context context, boolean enabled) {
        if (!canDisable(context)){
            return;
        }
        Settings.System.putIntForUser(context.getContentResolver(),
                Settings.System.NAVIGATION_BAR_SHOW, enabled ? 1 : 0, UserHandle.USER_CURRENT);
    }
    public static boolean canDisable(Context context) {
        boolean canForceDisable = context.getResources().getBoolean(com.android.internal.R.bool.config_canForceDisableNavigationBar);
        if (canForceDisable){
            return true;
        }
        final int deviceKeys = context.getResources().getInteger(
                com.android.internal.R.integer.config_deviceHardwareKeys);
        final boolean hasHomeKey = (deviceKeys & KEY_MASK_HOME) != 0;
        final boolean hasBackKey = (deviceKeys & KEY_MASK_BACK) != 0;
        return hasHomeKey && hasBackKey;
    }
}