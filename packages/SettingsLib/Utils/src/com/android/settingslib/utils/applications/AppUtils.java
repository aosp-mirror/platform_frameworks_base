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

package com.android.settingslib.utils.applications;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.UserManager;
import android.util.Log;

import com.android.settingslib.utils.R;

public class AppUtils {

    private static final String TAG = AppUtils.class.getSimpleName();

    /** Returns the label for a given package. */
    public static CharSequence getApplicationLabel(
            PackageManager packageManager, String packageName) {
        try {
            final ApplicationInfo appInfo =
                    packageManager.getApplicationInfo(
                            packageName,
                            PackageManager.MATCH_DISABLED_COMPONENTS
                                    | PackageManager.MATCH_ANY_USER);
            return appInfo.loadLabel(packageManager);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Unable to find info for package: " + packageName);
        }
        return null;
    }

    /**
     * Returns a content description of an app name which distinguishes a personal app from a
     * work app for accessibility purpose.
     * If the app is in a work profile, then add a "work" prefix to the app name.
     */
    public static String getAppContentDescription(Context context, String packageName,
            int userId) {
        final CharSequence appLabel = getApplicationLabel(context.getPackageManager(), packageName);
        return context.getSystemService(UserManager.class).isManagedProfile(userId)
                ? context.getString(R.string.accessibility_work_profile_app_description, appLabel)
                : appLabel.toString();
    }
}
