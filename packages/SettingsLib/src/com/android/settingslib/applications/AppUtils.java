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
 * limitations under the License
 */

package com.android.settingslib.applications;

import android.content.ComponentName;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.IUsbManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import com.android.settingslib.R;

import java.util.ArrayList;
import java.util.List;

public class AppUtils {
    private static final String TAG = "AppUtils";

    public static CharSequence getLaunchByDefaultSummary(ApplicationsState.AppEntry appEntry,
            IUsbManager usbManager, PackageManager pm, Context context) {
        String packageName = appEntry.info.packageName;
        boolean hasPreferred = hasPreferredActivities(pm, packageName)
                || hasUsbDefaults(usbManager, packageName);
        int status = pm.getIntentVerificationStatusAsUser(packageName, UserHandle.myUserId());
        // consider a visible current link-handling state to be any explicitly designated behavior
        boolean hasDomainURLsPreference =
                status != PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED;
        return context.getString(hasPreferred || hasDomainURLsPreference
                ? R.string.launch_defaults_some
                : R.string.launch_defaults_none);
    }

    public static boolean hasUsbDefaults(IUsbManager usbManager, String packageName) {
        try {
            if (usbManager != null) {
                return usbManager.hasDefaults(packageName, UserHandle.myUserId());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "mUsbManager.hasDefaults", e);
        }
        return false;
    }

    public static boolean hasPreferredActivities(PackageManager pm, String packageName) {
        // Get list of preferred activities
        List<ComponentName> prefActList = new ArrayList<>();
        // Intent list cannot be null. so pass empty list
        List<IntentFilter> intentList = new ArrayList<>();
        pm.getPreferredActivities(intentList, prefActList, packageName);
        Log.d(TAG, "Have " + prefActList.size() + " number of activities in preferred list");
        return prefActList.size() > 0;
    }

}
