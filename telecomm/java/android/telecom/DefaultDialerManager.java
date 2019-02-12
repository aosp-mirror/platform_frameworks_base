/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package android.telecom;

import android.app.ActivityManager;
import android.app.role.RoleManager;
import android.app.role.RoleManagerCallback;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Process;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Class for managing the default dialer application that will receive incoming calls, and be
 * allowed to make emergency outgoing calls.
 *
 * @hide
 */
public class DefaultDialerManager {
    private static final String TAG = "DefaultDialerManager";

    /**
     * Sets the specified package name as the default dialer application for the current user.
     * The caller of this method needs to have permission to write to secure settings and
     * manage users on the device.
     *
     * @return {@code true} if the default dialer application was successfully changed,
     *         {@code false} otherwise.
     *
     * @hide
     * */
    public static boolean setDefaultDialerApplication(Context context, String packageName) {
        return setDefaultDialerApplication(context, packageName, ActivityManager.getCurrentUser());
    }

    /**
     * Sets the specified package name as the default dialer application for the specified user.
     * The caller of this method needs to have permission to write to secure settings and
     * manage users on the device.
     *
     * @return {@code true} if the default dialer application was successfully changed,
     *         {@code false} otherwise.
     *
     * @hide
     * */
    public static boolean setDefaultDialerApplication(Context context, String packageName,
            int user) {
        long identity = Binder.clearCallingIdentity();
        try {
            RoleManagerCallback.Future cb = new RoleManagerCallback.Future();
            context.getSystemService(RoleManager.class).addRoleHolderAsUser(
                    RoleManager.ROLE_DIALER, packageName, 0, UserHandle.of(user),
                    AsyncTask.THREAD_POOL_EXECUTOR, cb);
            cb.get(5, TimeUnit.SECONDS);
            return true;
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            Slog.e(TAG, "Failed to set default dialer to " + packageName + " for user " + user, e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Returns the installed dialer application for the current user that will be used to receive
     * incoming calls, and is allowed to make emergency calls.
     *
     * The application will be returned in order of preference:
     * 1) User selected phone application (if still installed)
     * 2) Pre-installed system dialer (if not disabled)
     * 3) Null
     *
     * The caller of this method needs to have permission to manage users on the device.
     *
     * @hide
     * */
    public static String getDefaultDialerApplication(Context context) {
        return getDefaultDialerApplication(context, context.getUserId());
    }

    /**
     * Returns the installed dialer application for the specified user that will be used to receive
     * incoming calls, and is allowed to make emergency calls.
     *
     * The application will be returned in order of preference:
     * 1) User selected phone application (if still installed)
     * 2) Pre-installed system dialer (if not disabled)
     * 3) Null
     *
     * The caller of this method needs to have permission to manage users on the device.
     *
     * @hide
     * */
    public static String getDefaultDialerApplication(Context context, int user) {
        long identity = Binder.clearCallingIdentity();
        try {
            return CollectionUtils.firstOrNull(context.getSystemService(RoleManager.class)
                    .getRoleHoldersAsUser(RoleManager.ROLE_DIALER, UserHandle.of(user)));
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Returns a list of installed and available dialer applications.
     *
     * In order to appear in the list, a dialer application must implement an intent-filter with
     * the DIAL intent for the following schemes:
     *
     * 1) Empty scheme
     * 2) tel Uri scheme
     *
     * @hide
     **/
    public static List<String> getInstalledDialerApplications(Context context, int userId) {
        PackageManager packageManager = context.getPackageManager();

        // Get the list of apps registered for the DIAL intent with empty scheme
        Intent intent = new Intent(Intent.ACTION_DIAL);
        List<ResolveInfo> resolveInfoList =
                packageManager.queryIntentActivitiesAsUser(intent, 0, userId);

        List<String> packageNames = new ArrayList<>();

        for (ResolveInfo resolveInfo : resolveInfoList) {
            final ActivityInfo activityInfo = resolveInfo.activityInfo;
            if (activityInfo != null
                    && !packageNames.contains(activityInfo.packageName)
                    // ignore cross profile intent handler
                    && resolveInfo.targetUserId == UserHandle.USER_CURRENT) {
                packageNames.add(activityInfo.packageName);
            }
        }

        final Intent dialIntentWithTelScheme = new Intent(Intent.ACTION_DIAL);
        dialIntentWithTelScheme.setData(Uri.fromParts(PhoneAccount.SCHEME_TEL, "", null));
        return filterByIntent(context, packageNames, dialIntentWithTelScheme, userId);
    }

    public static List<String> getInstalledDialerApplications(Context context) {
        return getInstalledDialerApplications(context, Process.myUserHandle().getIdentifier());
    }

    /**
     * Determines if the package name belongs to the user-selected default dialer or the preloaded
     * system dialer, and thus should be allowed to perform certain privileged operations.
     *
     * @param context A valid context.
     * @param packageName of the package to check for.
     *
     * @return {@code true} if the provided package name corresponds to the user-selected default
     *         dialer or the preloaded system dialer, {@code false} otherwise.
     *
     * @hide
     */
    public static boolean isDefaultOrSystemDialer(Context context, String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return false;
        }
        final TelecomManager tm = getTelecomManager(context);
        return packageName.equals(tm.getDefaultDialerPackage())
                || packageName.equals(tm.getSystemDialerPackage());
    }

    /**
     * Filter a given list of package names for those packages that contain an activity that has
     * an intent filter for a given intent.
     *
     * @param context A valid context
     * @param packageNames List of package names to filter.
     * @param userId The UserId
     * @return The filtered list.
     */
    private static List<String> filterByIntent(Context context, List<String> packageNames,
            Intent intent, int userId) {
        if (packageNames == null || packageNames.isEmpty()) {
            return new ArrayList<>();
        }

        final List<String> result = new ArrayList<>();
        final List<ResolveInfo> resolveInfoList = context.getPackageManager()
                .queryIntentActivitiesAsUser(intent, 0, userId);
        final int length = resolveInfoList.size();
        for (int i = 0; i < length; i++) {
            final ActivityInfo info = resolveInfoList.get(i).activityInfo;
            if (info != null && packageNames.contains(info.packageName)
                    && !result.contains(info.packageName)) {
                result.add(info.packageName);
            }
        }

        return result;
    }


    private static TelecomManager getTelecomManager(Context context) {
        return (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
    }
}
