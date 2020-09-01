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

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.usb.IUsbManager;
import android.net.Uri;
import android.os.Environment;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.Log;

import com.android.settingslib.R;
import com.android.settingslib.applications.instantapps.InstantAppDataProvider;

import java.util.ArrayList;
import java.util.List;

public class AppUtils {
    private static final String TAG = "AppUtils";

    /**
     * This should normally only be set in robolectric tests, to avoid getting a method not found
     * exception when calling the isInstantApp method of the ApplicationInfo class, because
     * robolectric does not yet have an implementation of it.
     */
    private static InstantAppDataProvider sInstantAppDataProvider = null;

    private static final Intent sBrowserIntent;

    static {
        sBrowserIntent = new Intent()
                .setAction(Intent.ACTION_VIEW)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setData(Uri.parse("http:"));
    }

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

    /**
     * Returns a boolean indicating whether the given package should be considered an instant app
     */
    public static boolean isInstant(ApplicationInfo info) {
        if (sInstantAppDataProvider != null) {
            if (sInstantAppDataProvider.isInstantApp(info)) {
                return true;
            }
        } else if (info.isInstantApp()) {
            return true;
        }

        // For debugging/testing, we support setting the following property to a comma-separated
        // list of search terms (typically, but not necessarily, full package names) to match
        // against the package names of the app.
        String propVal = SystemProperties.get("settingsdebug.instant.packages");
        if (propVal != null && !propVal.isEmpty() && info.packageName != null) {
            String[] searchTerms = propVal.split(",");
            if (searchTerms != null) {
                for (String term : searchTerms) {
                    if (info.packageName.contains(term)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Returns the label for a given package. */
    public static CharSequence getApplicationLabel(
            PackageManager packageManager, String packageName) {
        return com.android.settingslib.utils.applications.AppUtils
                .getApplicationLabel(packageManager, packageName);
    }

    /**
     * Returns a boolean indicating whether the given package is a hidden system module
     */
    public static boolean isHiddenSystemModule(Context context, String packageName) {
        return ApplicationsState.getInstance((Application) context.getApplicationContext())
                .isHiddenModule(packageName);
    }

    /**
     * Returns a boolean indicating whether a given package is a system module.
     */
    public static boolean isSystemModule(Context context, String packageName) {
        return ApplicationsState.getInstance((Application) context.getApplicationContext())
                .isSystemModule(packageName);
    }

    /**
     * Returns a boolean indicating whether a given package is a mainline module.
     */
    public static boolean isMainlineModule(PackageManager pm, String packageName) {
        // Check if the package is listed among the system modules.
        try {
            pm.getModuleInfo(packageName, 0 /* flags */);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            //pass
        }

        try {
            final PackageInfo pkg = pm.getPackageInfo(packageName, 0 /* flags */);
            // Check if the package is contained in an APEX. There is no public API to properly
            // check whether a given APK package comes from an APEX registered as module.
            // Therefore we conservatively assume that any package scanned from an /apex path is
            // a system package.
            return pkg.applicationInfo.sourceDir.startsWith(
                    Environment.getApexDirectory().getAbsolutePath());
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Returns a content description of an app name which distinguishes a personal app from a
     * work app for accessibility purpose.
     * If the app is in a work profile, then add a "work" prefix to the app name.
     */
    public static String getAppContentDescription(Context context, String packageName,
            int userId) {
        return com.android.settingslib.utils.applications.AppUtils.getAppContentDescription(context,
                packageName, userId);
    }

    /**
     * Returns a boolean indicating whether a given package is a browser app.
     *
     * An app is a "browser" if it has an activity resolution that wound up
     * marked with the 'handleAllWebDataURI' flag.
     */
    public static boolean isBrowserApp(Context context, String packageName, int userId) {
        sBrowserIntent.setPackage(packageName);
        final List<ResolveInfo> list = context.getPackageManager().queryIntentActivitiesAsUser(
                sBrowserIntent, PackageManager.MATCH_ALL, userId);
        for (ResolveInfo info : list) {
            if (info.activityInfo != null && info.handleAllWebDataURI) {
                return true;
            }
        }
        return false;
    }
}
