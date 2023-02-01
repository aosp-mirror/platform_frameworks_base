/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.settingslib.users;

import android.app.AppGlobals;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Helper for {@link com.android.settings.users.AppCopyFragment}, for keeping track of which
 * packages a user has chosen to copy to a second user and fulfilling that installation.
 *
 * To test, use
 *   atest SettingsLibTests:com.android.settingslib.users.AppCopyingHelperTest
 */
public class AppCopyHelper {
    private static final boolean DEBUG = false;
    private static final String TAG = "AppCopyHelper";

    private final PackageManager mPackageManager;
    private final IPackageManager mIPm;
    private final UserHandle mUser;
    private boolean mLeanback;

    /** Set of packages to be installed. */
    private final ArraySet<String> mSelectedPackages = new ArraySet<>();
    /** List of installable packages from which the user can choose. */
    private List<SelectableAppInfo> mVisibleApps;

    public AppCopyHelper(Context context, UserHandle user) {
        this(new Injector(context, user));
    }

    @VisibleForTesting
    AppCopyHelper(Injector injector) {
        mPackageManager = injector.getPackageManager();
        mIPm = injector.getIPackageManager();
        mUser = injector.getUser();
    }

    /** Toggles whether the package has been selected. */
    public void setPackageSelected(String packageName, boolean selected) {
        if (selected) {
            mSelectedPackages.add(packageName);
        } else {
            mSelectedPackages.remove(packageName);
        }
    }

    /** Resets all packages as unselected. */
    public void resetSelectedPackages() {
        mSelectedPackages.clear();
    }

    public void setLeanback(boolean isLeanback) {
        mLeanback = isLeanback;
    }

    /** List of installable packages from which the user can choose. */
    public List<SelectableAppInfo> getVisibleApps() {
        return mVisibleApps;
    }

    /** Installs the packages that have been selected using {@link #setPackageSelected} */
    public void installSelectedApps() {
        for (int i = 0; i < mSelectedPackages.size(); i++) {
            final String packageName = mSelectedPackages.valueAt(i);
            installSelectedApp(packageName);
        }
    }

    private void installSelectedApp(String packageName) {
        final int userId = mUser.getIdentifier();
        try {
            final ApplicationInfo info = mIPm.getApplicationInfo(packageName,
                    PackageManager.MATCH_ANY_USER, userId);
            if (info == null || !info.enabled
                    || (info.flags & ApplicationInfo.FLAG_INSTALLED) == 0) {
                Log.i(TAG, "Installing " + packageName);
                mIPm.installExistingPackageAsUser(packageName, mUser.getIdentifier(),
                        PackageManager.INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS,
                        PackageManager.INSTALL_REASON_UNKNOWN, null);
            }
            if (info != null && (info.privateFlags & ApplicationInfo.PRIVATE_FLAG_HIDDEN) != 0
                    && (info.flags & ApplicationInfo.FLAG_INSTALLED) != 0) {
                Log.i(TAG, "Unhiding " + packageName);
                mIPm.setApplicationHiddenSettingAsUser(packageName, false, userId);
            }
        } catch (RemoteException re) {
            // Ignore
        }
    }

    /**
     * Fetches the list of installable packages to display.
     * This list can be obtained from {@link #getVisibleApps}.
     */
    public void fetchAndMergeApps() {
        mVisibleApps = new ArrayList<>();
        addCurrentUsersApps();
        removeSecondUsersApp();
    }

    /**
     * Adds to {@link #mVisibleApps} packages from the current user:
     *  (1) All downloaded apps and
     *  (2) all system apps that have launcher or widgets.
     */
    private void addCurrentUsersApps() {
        // Add system package launchers of the current user
        final Intent launcherIntent = new Intent(Intent.ACTION_MAIN).addCategory(
                mLeanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER);
        addSystemApps(mVisibleApps, launcherIntent);

        // Add system package widgets of the current user
        final Intent widgetIntent = new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        addSystemApps(mVisibleApps, widgetIntent);

        // Add all downloaded apps of the current user
        final List<ApplicationInfo> installedApps = mPackageManager.getInstalledApplications(0);
        for (ApplicationInfo app : installedApps) {
            // If it's not installed, skip
            if ((app.flags & ApplicationInfo.FLAG_INSTALLED) == 0) continue;

            if ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0
                    && (app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) {
                // Downloaded app
                final SelectableAppInfo info = new SelectableAppInfo();
                info.packageName = app.packageName;
                info.appName = app.loadLabel(mPackageManager);
                info.icon = app.loadIcon(mPackageManager);
                mVisibleApps.add(info);
            }
        }

        // Remove dupes
        final Set<String> dedupPackages = new HashSet<>();
        for (int i = mVisibleApps.size() - 1; i >= 0; i--) {
            final SelectableAppInfo info = mVisibleApps.get(i);
            if (DEBUG) Log.i(TAG, info.toString());
            if (!TextUtils.isEmpty(info.packageName) && dedupPackages.contains(info.packageName)) {
                mVisibleApps.remove(i);
            } else {
                dedupPackages.add(info.packageName);
            }
        }

        // Sort the list of visible apps
        mVisibleApps.sort(new AppLabelComparator());
    }

    /** Removes from {@link #mVisibleApps} all packages already installed on mUser. */
    private void removeSecondUsersApp() {
        // Get the set of apps already installed for mUser
        final Set<String> userPackages = new HashSet<>();
        final List<ApplicationInfo> userAppInfos = mPackageManager.getInstalledApplicationsAsUser(
                    PackageManager.MATCH_UNINSTALLED_PACKAGES, mUser.getIdentifier());
        for (int i = userAppInfos.size() - 1; i >= 0; i--) {
            final ApplicationInfo app = userAppInfos.get(i);
            if ((app.flags & ApplicationInfo.FLAG_INSTALLED) == 0) continue;
            userPackages.add(app.packageName);
        }

        for (int i = mVisibleApps.size() - 1; i >= 0; i--) {
            final SelectableAppInfo info = mVisibleApps.get(i);
            if (DEBUG) Log.i(TAG, info.toString());
            if (!TextUtils.isEmpty(info.packageName) && userPackages.contains(info.packageName)) {
                mVisibleApps.remove(i);
            }
        }
    }

    /**
     * Add system apps that match an intent to the list.
     * @param visibleApps list of apps to append the new list to
     * @param intent the intent to match
     */
    private void addSystemApps(List<SelectableAppInfo> visibleApps, Intent intent) {
        final List<ResolveInfo> intentApps = mPackageManager.queryIntentActivities(intent, 0);
        for (ResolveInfo app : intentApps) {
            if (app.activityInfo != null && app.activityInfo.applicationInfo != null) {
                final int flags = app.activityInfo.applicationInfo.flags;
                if ((flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        || (flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {

                    final SelectableAppInfo info = new SelectableAppInfo();
                    info.packageName = app.activityInfo.packageName;
                    info.appName = app.activityInfo.applicationInfo.loadLabel(mPackageManager);
                    info.icon = app.activityInfo.loadIcon(mPackageManager);

                    visibleApps.add(info);
                }
            }
        }
    }

    /** Container for a package, its name, and icon. */
    public static class SelectableAppInfo {
        public String packageName;
        public CharSequence appName;
        public Drawable icon;

        @Override
        public String toString() {
            return packageName + ": appName=" + appName + "; icon=" + icon;
        }
    }

    private static class AppLabelComparator implements Comparator<SelectableAppInfo> {
        @Override
        public int compare(SelectableAppInfo lhs, SelectableAppInfo rhs) {
            String lhsLabel = lhs.appName.toString();
            String rhsLabel = rhs.appName.toString();
            return lhsLabel.toLowerCase().compareTo(rhsLabel.toLowerCase());
        }
    }

    /**
     * Unit test will subclass it to inject mocks.
     */
    @VisibleForTesting
    static class Injector {
        private final Context mContext;
        private final UserHandle mUser;

        Injector(Context context, UserHandle user) {
            mContext = context;
            mUser = user;
        }

        UserHandle getUser() {
            return mUser;
        }

        PackageManager getPackageManager() {
            return mContext.getPackageManager();
        }

        IPackageManager getIPackageManager() {
            return AppGlobals.getPackageManager();
        }
    }
}
