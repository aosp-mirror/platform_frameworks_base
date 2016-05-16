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

package android.content.pm;

import android.Manifest;
import android.app.AppGlobals;
import android.content.Intent;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArraySet;
import android.view.inputmethod.InputMethod;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper class for querying installed applications using multiple criteria.
 *
 * @hide
 */
public class AppsQueryHelper {

    /**
     * Return apps without launcher icon
     */
    public static int GET_NON_LAUNCHABLE_APPS = 1;

    /**
     * Return apps with {@link Manifest.permission#INTERACT_ACROSS_USERS} permission
     */
    public static int GET_APPS_WITH_INTERACT_ACROSS_USERS_PERM = 1 << 1;

    /**
     * Return all input methods available for the current user.
     */
    public static int GET_IMES = 1 << 2;

    /**
     * Return all apps that are flagged as required for the system user.
     */
    public static int GET_REQUIRED_FOR_SYSTEM_USER = 1 << 3;

    private final IPackageManager mPackageManager;
    private List<ApplicationInfo> mAllApps;

    public AppsQueryHelper(IPackageManager packageManager) {
        mPackageManager = packageManager;
    }

    public AppsQueryHelper() {
        this(AppGlobals.getPackageManager());
    }

    /**
     * Return a List of all packages that satisfy a specified criteria.
     * @param flags search flags. Use any combination of {@link #GET_NON_LAUNCHABLE_APPS},
     * {@link #GET_APPS_WITH_INTERACT_ACROSS_USERS_PERM} or {@link #GET_IMES}.
     * @param systemAppsOnly if true, only system apps will be returned
     * @param user user, whose apps are queried
     */
    public List<String> queryApps(int flags, boolean systemAppsOnly, UserHandle user) {
        boolean nonLaunchableApps = (flags & GET_NON_LAUNCHABLE_APPS) > 0;
        boolean interactAcrossUsers = (flags & GET_APPS_WITH_INTERACT_ACROSS_USERS_PERM) > 0;
        boolean imes = (flags & GET_IMES) > 0;
        boolean requiredForSystemUser = (flags & GET_REQUIRED_FOR_SYSTEM_USER) > 0;
        if (mAllApps == null) {
            mAllApps = getAllApps(user.getIdentifier());
        }

        List<String> result = new ArrayList<>();
        if (flags == 0) {
            final int allAppsSize = mAllApps.size();
            for (int i = 0; i < allAppsSize; i++) {
                final ApplicationInfo appInfo = mAllApps.get(i);
                if (systemAppsOnly && !appInfo.isSystemApp()) {
                    continue;
                }
                result.add(appInfo.packageName);
            }
            return result;
        }

        if (nonLaunchableApps) {
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            final List<ResolveInfo> resolveInfos = queryIntentActivitiesAsUser(intent,
                    user.getIdentifier());

            ArraySet<String> appsWithLaunchers = new ArraySet<>();
            final int resolveInfosSize = resolveInfos.size();
            for (int i = 0; i < resolveInfosSize; i++) {
                appsWithLaunchers.add(resolveInfos.get(i).activityInfo.packageName);
            }
            final int allAppsSize = mAllApps.size();
            for (int i = 0; i < allAppsSize; i++) {
                final ApplicationInfo appInfo = mAllApps.get(i);
                if (systemAppsOnly && !appInfo.isSystemApp()) {
                    continue;
                }
                final String packageName = appInfo.packageName;
                if (!appsWithLaunchers.contains(packageName)) {
                    result.add(packageName);
                }
            }
        }
        if (interactAcrossUsers) {
            final List<PackageInfo> packagesHoldingPermissions = getPackagesHoldingPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS, user.getIdentifier());
            final int packagesHoldingPermissionsSize = packagesHoldingPermissions.size();
            for (int i = 0; i < packagesHoldingPermissionsSize; i++) {
                PackageInfo packageInfo = packagesHoldingPermissions.get(i);
                if (systemAppsOnly && !packageInfo.applicationInfo.isSystemApp()) {
                    continue;
                }
                if (!result.contains(packageInfo.packageName)) {
                    result.add(packageInfo.packageName);
                }
            }
        }

        if (imes) {
            final List<ResolveInfo> resolveInfos = queryIntentServicesAsUser(
                    new Intent(InputMethod.SERVICE_INTERFACE), user.getIdentifier());
            final int resolveInfosSize = resolveInfos.size();

            for (int i = 0; i < resolveInfosSize; i++) {
                ServiceInfo serviceInfo = resolveInfos.get(i).serviceInfo;
                if (systemAppsOnly && !serviceInfo.applicationInfo.isSystemApp()) {
                    continue;
                }
                if (!result.contains(serviceInfo.packageName)) {
                    result.add(serviceInfo.packageName);
                }
            }
        }

        if (requiredForSystemUser) {
            final int allAppsSize = mAllApps.size();
            for (int i = 0; i < allAppsSize; i++) {
                final ApplicationInfo appInfo = mAllApps.get(i);
                if (systemAppsOnly && !appInfo.isSystemApp()) {
                    continue;
                }
                if (appInfo.isRequiredForSystemUser()) {
                    result.add(appInfo.packageName);
                }
            }
        }
        return result;
    }

    @VisibleForTesting
    @SuppressWarnings("unchecked")
    protected List<ApplicationInfo> getAllApps(int userId) {
        try {
            return mPackageManager.getInstalledApplications(
                    PackageManager.MATCH_UNINSTALLED_PACKAGES
                            | PackageManager.MATCH_DISABLED_COMPONENTS, userId).getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @VisibleForTesting
    protected List<ResolveInfo> queryIntentActivitiesAsUser(Intent intent, int userId) {
        try {
            return mPackageManager.queryIntentActivities(intent, null,
                    PackageManager.MATCH_DISABLED_COMPONENTS
                            | PackageManager.MATCH_UNINSTALLED_PACKAGES
                            | PackageManager.MATCH_DIRECT_BOOT_AWARE
                            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                    userId).getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @VisibleForTesting
    protected List<ResolveInfo> queryIntentServicesAsUser(Intent intent, int userId) {
        try {
            return mPackageManager.queryIntentServices(intent, null,
                    PackageManager.GET_META_DATA
                            | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
                            | PackageManager.MATCH_DIRECT_BOOT_AWARE
                            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, userId)
                    .getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @VisibleForTesting
    @SuppressWarnings("unchecked")
    protected List<PackageInfo> getPackagesHoldingPermission(String perm, int userId) {
        try {
            return mPackageManager.getPackagesHoldingPermissions(new String[]{perm}, 0,
                    userId).getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
