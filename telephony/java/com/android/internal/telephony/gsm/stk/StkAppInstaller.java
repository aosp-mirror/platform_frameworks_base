/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.internal.telephony.gsm.stk;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.util.Log;

/**
 * Application installer for SIM Toolkit.
 *
 */
public class StkAppInstaller {
    // Application state actions: install, uninstall used by StkAppStateReceiver.    
    static final String STK_APP_INSTALL_ACTION = "com.android.stk.action.INSTALL";
    static final String STK_APP_UNINSTALL_ACTION = "com.android.stk.action.UNINSTALL";

    public static void installApp(Context context) {
        setAppState(context, PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
    }

    public static void unInstallApp(Context context) {
        setAppState(context, PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
    }

    private static void setAppState(Context context, int state) {
        if (context == null) {
            return;
        }
        PackageManager pm = context.getPackageManager();
        if (pm == null) {
            return;
        }
        // check that STK app package is known to the PackageManager
        ComponentName cName = new ComponentName("com.android.stk",
                "com.android.stk.StkActivity");

        try {
            pm.setComponentEnabledSetting(cName, state,
                    PackageManager.DONT_KILL_APP);
        } catch (Exception e) {
            Log.w("StkAppInstaller", "Could not change STK app state");
        }
    }
}
