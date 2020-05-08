/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.telephony;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Telephony;
import android.text.TextUtils;
import android.util.Log;

/**
 * This class provides utility functions related to CellBroadcast.
 */
public class CellBroadcastUtils {
    private static final String TAG = "CellBroadcastUtils";
    private static final boolean VDBG = false;

    /**
     * Utility method to query the default CBR's package name.
     */
    public static String getDefaultCellBroadcastReceiverPackageName(Context context) {
        PackageManager packageManager = context.getPackageManager();
        ResolveInfo resolveInfo = packageManager.resolveActivity(
                new Intent(Telephony.Sms.Intents.SMS_CB_RECEIVED_ACTION),
                PackageManager.MATCH_SYSTEM_ONLY);
        String packageName;

        if (resolveInfo == null) {
            Log.e(TAG, "getDefaultCellBroadcastReceiverPackageName: no package found");
            return null;
        }

        packageName = resolveInfo.activityInfo.applicationInfo.packageName;

        if (VDBG) {
            Log.d(TAG, "getDefaultCellBroadcastReceiverPackageName: found package: " + packageName);
        }

        if (TextUtils.isEmpty(packageName) || packageManager.checkPermission(
                android.Manifest.permission.READ_CELL_BROADCASTS, packageName)
                == PackageManager.PERMISSION_DENIED) {
            Log.e(TAG, "getDefaultCellBroadcastReceiverPackageName: returning null; "
                    + "permission check failed for : " + packageName);
            return null;
        }

        return packageName;
    }
}
