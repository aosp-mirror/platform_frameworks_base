/*
 * Copyright (C) 2018-2019 The LineageOS Project
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

package com.android.internal.util.custom;

import android.app.ActivityManager;
import android.app.ActivityManager.StackInfo;
import android.app.ActivityManagerNative;
import android.app.ActivityOptions;
import android.app.IActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import java.util.List;

public class ActionUtils {
    private static final boolean DEBUG = false;
    private static final String TAG = ActionUtils.class.getSimpleName();
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";

    /**
     * Kills the top most / most recent user application, but leaves out the launcher.
     * This is function governed by {@link Settings.Secure.KILL_APP_LONGPRESS_BACK}.
     *
     * @param context the current context, used to retrieve the package manager.
     * @param userId the ID of the currently active user
     * @return {@code true} when a user application was found and closed.
     */
    public static boolean killForegroundApp(Context context, int userId) {
        try {
            return killForegroundAppInternal(context, userId);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not kill foreground app");
        }
        return false;
    }

    private static boolean killForegroundAppInternal(Context context, int userId)
            throws RemoteException {
        final String packageName = getForegroundTaskPackageName(context, userId);

        if (packageName == null) {
            return false;
        }

        final IActivityManager am = ActivityManagerNative.getDefault();
        am.forceStopPackage(packageName, UserHandle.USER_CURRENT);

        return true;
    }

    private static String getForegroundTaskPackageName(Context context, int userId)
            throws RemoteException {
        final String defaultHomePackage = resolveCurrentLauncherPackage(context, userId);
        final IActivityManager am = ActivityManager.getService();
        final StackInfo focusedStack = am.getFocusedStackInfo();

        if (focusedStack == null || focusedStack.topActivity == null) {
            return null;
        }

        final String packageName = focusedStack.topActivity.getPackageName();
        if (!packageName.equals(defaultHomePackage)
                && !packageName.equals(SYSTEMUI_PACKAGE)) {
            return packageName;
        }

        return null;
    }

    private static String resolveCurrentLauncherPackage(Context context, int userId) {
        final Intent launcherIntent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME);
        final PackageManager pm = context.getPackageManager();
        final ResolveInfo launcherInfo = pm.resolveActivityAsUser(launcherIntent, 0, userId);

        if (launcherInfo.activityInfo != null &&
                !launcherInfo.activityInfo.packageName.equals("android")) {
            return launcherInfo.activityInfo.packageName;
        }

        return null;
    }
}