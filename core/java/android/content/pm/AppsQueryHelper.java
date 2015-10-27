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
import android.content.Context;
import android.content.Intent;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArraySet;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

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
     * Return apps with {@link android.Manifest.permission#INTERACT_ACROSS_USERS} permission
     */
    public static int GET_APPS_WITH_INTERACT_ACROSS_USERS_PERM = 1 << 1;

    /**
     * Return all input methods that are marked as default.
     * <p>When this flag is set, {@code user} specified in
     * {@link #queryApps(int, boolean, UserHandle)} must be
     * {@link UserHandle#myUserId user of the current process}.
     */
    public static int GET_DEFAULT_IMES = 1 << 2;

    private final Context mContext;
    private List<ApplicationInfo> mAllApps;

    public AppsQueryHelper(Context context) {
        mContext = context;
    }

    /**
     * Return a List of all packages that satisfy a specified criteria.
     * @param flags search flags. Use any combination of {@link #GET_NON_LAUNCHABLE_APPS},
     * {@link #GET_APPS_WITH_INTERACT_ACROSS_USERS_PERM} or {@link #GET_DEFAULT_IMES}.
     * @param systemAppsOnly if true, only system apps will be returned
     * @param user user, whose apps are queried
     */
    public List<String> queryApps(int flags, boolean systemAppsOnly, UserHandle user) {
        boolean nonLaunchableApps = (flags & GET_NON_LAUNCHABLE_APPS) > 0;
        boolean interactAcrossUsers = (flags & GET_APPS_WITH_INTERACT_ACROSS_USERS_PERM) > 0;
        boolean defaultImes = (flags & GET_DEFAULT_IMES) > 0;
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

        if (defaultImes) {
            if (UserHandle.myUserId() != user.getIdentifier()) {
                throw new IllegalArgumentException("Specified user handle " + user
                        + " is not a user of the current process.");
            }
            List<InputMethodInfo> imis = getInputMethodList();
            int imisSize = imis.size();
            ArraySet<String> defaultImePackages = new ArraySet<>();
            for (int i = 0; i < imisSize; i++) {
                InputMethodInfo imi = imis.get(i);
                if (imi.isDefault(mContext)) {
                    defaultImePackages.add(imi.getPackageName());
                }
            }
            final int allAppsSize = mAllApps.size();
            for (int i = 0; i < allAppsSize; i++) {
                final ApplicationInfo appInfo = mAllApps.get(i);
                if (systemAppsOnly && !appInfo.isSystemApp()) {
                    continue;
                }
                final String packageName = appInfo.packageName;
                if (defaultImePackages.contains(packageName)) {
                    result.add(packageName);
                }
            }
        }

        return result;
    }

    @VisibleForTesting
    @SuppressWarnings("unchecked")
    protected List<ApplicationInfo> getAllApps(int userId) {
        try {
            return AppGlobals.getPackageManager().getInstalledApplications(
                    PackageManager.GET_UNINSTALLED_PACKAGES
                            | PackageManager.GET_DISABLED_COMPONENTS, userId).getList();
        } catch (RemoteException e) {
            throw new IllegalStateException("Package manager has died", e);
        }
    }

    @VisibleForTesting
    protected List<ResolveInfo> queryIntentActivitiesAsUser(Intent intent, int userId) {
        return mContext.getPackageManager()
                .queryIntentActivitiesAsUser(intent, PackageManager.GET_DISABLED_COMPONENTS
                        | PackageManager.GET_UNINSTALLED_PACKAGES, userId);
    }

    @VisibleForTesting
    @SuppressWarnings("unchecked")
    protected List<PackageInfo> getPackagesHoldingPermission(String perm, int userId) {
        try {
            return AppGlobals.getPackageManager().getPackagesHoldingPermissions(new String[]{perm},
                    0, userId).getList();
        } catch (RemoteException e) {
            throw new IllegalStateException("Package manager has died", e);
        }
    }

    @VisibleForTesting
    @SuppressWarnings("unchecked")
    protected List<InputMethodInfo> getInputMethodList() {
        InputMethodManager imm = (InputMethodManager)
                mContext.getSystemService(Context.INPUT_METHOD_SERVICE);
        return imm.getInputMethodList();
    }
}
