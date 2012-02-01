/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.connectivitymanagertest;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

public class UtilHelper {

    private static Boolean mIsWifiOnly = null;
    private static final Object sLock = new Object();

    /**
     * Return true if device is a wifi only device.
     */
    public static boolean isWifiOnly(Context context) {
        synchronized (sLock) {
            // cache the result from pkgMgr statically. It will never change, since its a
            // device configuration setting
            if (mIsWifiOnly == null) {
                PackageManager pkgMgr = context.getPackageManager();
                mIsWifiOnly = Boolean.valueOf(!pkgMgr
                        .hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
                        && pkgMgr.hasSystemFeature(PackageManager.FEATURE_WIFI));
                String deviceType = mIsWifiOnly ? "wifi-only" : "telephony";
                Log.d("ConnectivityManagerTest", String.format("detected a %s device", deviceType));
            }
        }
        return mIsWifiOnly;
    }
}
