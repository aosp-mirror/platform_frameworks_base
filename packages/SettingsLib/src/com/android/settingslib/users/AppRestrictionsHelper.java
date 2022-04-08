/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.settingslib.users;

import android.app.AppGlobals;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AppRestrictionsHelper {
    private static final boolean DEBUG = false;
    private static final String TAG = "AppRestrictionsHelper";

    private final Injector mInjector;
    private final Context mContext;
    private final PackageManager mPackageManager;
    private final IPackageManager mIPm;
    private final UserManager mUserManager;
    private final UserHandle mUser;
    private final boolean mRestrictedProfile;
    private boolean mLeanback;

    HashMap<String,Boolean> mSelectedPackages = new HashMap<>();
    private List<SelectableAppInfo> mVisibleApps;

    public AppRestrictionsHelper(Context context, UserHandle user) {
        this(new Injector(context, user));
    }

    @VisibleForTesting
    AppRestrictionsHelper(Injector injector) {
        mInjector = injector;
        mContext = mInjector.getContext();
        mPackageManager = mInjector.getPackageManager();
        mIPm = mInjector.getIPackageManager();
        mUser = mInjector.getUser();
        mUserManager = mInjector.getUserManager();
        mRestrictedProfile = mUserManager.getUserInfo(mUser.getIdentifier()).isRestricted();
    }

    public void setPackageSelected(String packageName, boolean selected) {
        mSelectedPackages.put(packageName, selected);
    }

    public boolean isPackageSelected(String packageName) {
        return mSelectedPackages.get(packageName);
    }

    public void setLeanback(boolean isLeanback) {
        mLeanback = isLeanback;
    }

    public List<SelectableAppInfo> getVisibleApps() {
        return mVisibleApps;
    }

    public void applyUserAppsStates(OnDisableUiForPackageListener listener) {
        if (!mRestrictedProfile && mUser.getIdentifier() != UserHandle.myUserId()) {
            Log.e(TAG, "Cannot apply application restrictions on another user!");
            return;
        }
        for (Map.Entry<String,Boolean> entry : mSelectedPackages.entrySet()) {
            String packageName = entry.getKey();
            boolean enabled = entry.getValue();
            applyUserAppState(packageName, enabled, listener);
        }
    }

    public void applyUserAppState(String packageName, boolean enabled,
            OnDisableUiForPackageListener listener) {
        final int userId = mUser.getIdentifier();
        if (enabled) {
            // Enable selected apps
            try {
                ApplicationInfo info = mIPm.getApplicationInfo(packageName,
                        PackageManager.MATCH_ANY_USER, userId);
                if (info == null || !info.enabled
                        || (info.flags&ApplicationInfo.FLAG_INSTALLED) == 0) {
                    mIPm.installExistingPackageAsUser(packageName, mUser.getIdentifier(),
                            PackageManager.INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS,
                            PackageManager.INSTALL_REASON_UNKNOWN, null);
                    if (DEBUG) {
                        Log.d(TAG, "Installing " + packageName);
                    }
                }
                if (info != null && (info.privateFlags&ApplicationInfo.PRIVATE_FLAG_HIDDEN) != 0
                        && (info.flags&ApplicationInfo.FLAG_INSTALLED) != 0) {
                    listener.onDisableUiForPackage(packageName);
                    mIPm.setApplicationHiddenSettingAsUser(packageName, false, userId);
                    if (DEBUG) {
                        Log.d(TAG, "Unhiding " + packageName);
                    }
                }
            } catch (RemoteException re) {
                // Ignore
            }
        } else {
            // Blacklist all other apps, system or downloaded
            try {
                ApplicationInfo info = mIPm.getApplicationInfo(packageName, 0, userId);
                if (info != null) {
                    if (mRestrictedProfile) {
                        mPackageManager.deletePackageAsUser(packageName, null,
                                PackageManager.DELETE_SYSTEM_APP, mUser.getIdentifier());
                        if (DEBUG) {
                            Log.d(TAG, "Uninstalling " + packageName);
                        }
                    } else {
                        listener.onDisableUiForPackage(packageName);
                        mIPm.setApplicationHiddenSettingAsUser(packageName, true, userId);
                        if (DEBUG) {
                            Log.d(TAG, "Hiding " + packageName);
                        }
                    }
                }
            } catch (RemoteException re) {
                // Ignore
            }
        }
    }

    public void fetchAndMergeApps() {
        mVisibleApps = new ArrayList<>();
        final PackageManager pm = mPackageManager;
        final IPackageManager ipm = mIPm;

        final HashSet<String> excludePackages = new HashSet<>();
        addSystemImes(excludePackages);

        // Add launchers
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
        if (mLeanback) {
            launcherIntent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        } else {
            launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        }
        addSystemApps(mVisibleApps, launcherIntent, excludePackages);

        // Add widgets
        Intent widgetIntent = new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        addSystemApps(mVisibleApps, widgetIntent, excludePackages);

        List<ApplicationInfo> installedApps = pm.getInstalledApplications(
                PackageManager.MATCH_ANY_USER);
        for (ApplicationInfo app : installedApps) {
            // If it's not installed, skip
            if ((app.flags & ApplicationInfo.FLAG_INSTALLED) == 0) continue;

            if ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0
                    && (app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) {
                // Downloaded app
                SelectableAppInfo info = new SelectableAppInfo();
                info.packageName = app.packageName;
                info.appName = app.loadLabel(pm);
                info.activityName = info.appName;
                info.icon = app.loadIcon(pm);
                mVisibleApps.add(info);
            } else {
                try {
                    PackageInfo pi = pm.getPackageInfo(app.packageName, 0);
                    // If it's a system app that requires an account and doesn't see restricted
                    // accounts, mark for removal. It might get shown in the UI if it has an icon
                    // but will still be marked as false and immutable.
                    if (mRestrictedProfile
                            && pi.requiredAccountType != null && pi.restrictedAccountType == null) {
                        mSelectedPackages.put(app.packageName, false);
                    }
                } catch (PackageManager.NameNotFoundException re) {
                    // Skip
                }
            }
        }

        // Get the list of apps already installed for the user
        List<ApplicationInfo> userApps = null;
        try {
            ParceledListSlice<ApplicationInfo> listSlice = ipm.getInstalledApplications(
                    PackageManager.MATCH_UNINSTALLED_PACKAGES, mUser.getIdentifier());
            if (listSlice != null) {
                userApps = listSlice.getList();
            }
        } catch (RemoteException re) {
            // Ignore
        }

        if (userApps != null) {
            for (ApplicationInfo app : userApps) {
                if ((app.flags & ApplicationInfo.FLAG_INSTALLED) == 0) continue;

                if ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0
                        && (app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) {
                    // Downloaded app
                    SelectableAppInfo info = new SelectableAppInfo();
                    info.packageName = app.packageName;
                    info.appName = app.loadLabel(pm);
                    info.activityName = info.appName;
                    info.icon = app.loadIcon(pm);
                    mVisibleApps.add(info);
                }
            }
        }

        // Sort the list of visible apps
        Collections.sort(mVisibleApps, new AppLabelComparator());

        // Remove dupes
        Set<String> dedupPackageSet = new HashSet<String>();
        for (int i = mVisibleApps.size() - 1; i >= 0; i--) {
            SelectableAppInfo info = mVisibleApps.get(i);
            if (DEBUG) Log.i(TAG, info.toString());
            String both = info.packageName + "+" + info.activityName;
            if (!TextUtils.isEmpty(info.packageName)
                    && !TextUtils.isEmpty(info.activityName)
                    && dedupPackageSet.contains(both)) {
                mVisibleApps.remove(i);
            } else {
                dedupPackageSet.add(both);
            }
        }

        // Establish master/slave relationship for entries that share a package name
        HashMap<String,SelectableAppInfo> packageMap = new HashMap<String,SelectableAppInfo>();
        for (SelectableAppInfo info : mVisibleApps) {
            if (packageMap.containsKey(info.packageName)) {
                info.masterEntry = packageMap.get(info.packageName);
            } else {
                packageMap.put(info.packageName, info);
            }
        }
    }

    /**
     * Find all pre-installed input methods that are marked as default
     * and add them to an exclusion list so that they aren't
     * presented to the user for toggling.
     * Don't add non-default ones, as they may include other stuff that we
     * don't need to auto-include.
     * @param excludePackages the set of package names to append to
     */
    private void addSystemImes(Set<String> excludePackages) {
        List<InputMethodInfo> imis = mInjector.getInputMethodList();
        for (InputMethodInfo imi : imis) {
            try {
                if (imi.isDefault(mContext) && isSystemPackage(imi.getPackageName())) {
                    excludePackages.add(imi.getPackageName());
                }
            } catch (Resources.NotFoundException rnfe) {
                // Not default
            }
        }
    }

    /**
     * Add system apps that match an intent to the list, excluding any packages in the exclude list.
     * @param visibleApps list of apps to append the new list to
     * @param intent the intent to match
     * @param excludePackages the set of package names to be excluded, since they're required
     */
    private void addSystemApps(List<SelectableAppInfo> visibleApps, Intent intent,
            Set<String> excludePackages) {
        final PackageManager pm = mPackageManager;
        List<ResolveInfo> launchableApps = pm.queryIntentActivities(intent,
                PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_UNINSTALLED_PACKAGES);
        for (ResolveInfo app : launchableApps) {
            if (app.activityInfo != null && app.activityInfo.applicationInfo != null) {
                final String packageName = app.activityInfo.packageName;
                int flags = app.activityInfo.applicationInfo.flags;
                if ((flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        || (flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
                    // System app
                    // Skip excluded packages
                    if (excludePackages.contains(packageName)) continue;
                    int enabled = pm.getApplicationEnabledSetting(packageName);
                    if (enabled == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
                            || enabled == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                        // Check if the app is already enabled for the target user
                        ApplicationInfo targetUserAppInfo = getAppInfoForUser(packageName,
                                0, mUser);
                        if (targetUserAppInfo == null
                                || (targetUserAppInfo.flags&ApplicationInfo.FLAG_INSTALLED) == 0) {
                            continue;
                        }
                    }
                    SelectableAppInfo info = new SelectableAppInfo();
                    info.packageName = app.activityInfo.packageName;
                    info.appName = app.activityInfo.applicationInfo.loadLabel(pm);
                    info.icon = app.activityInfo.loadIcon(pm);
                    info.activityName = app.activityInfo.loadLabel(pm);
                    if (info.activityName == null) info.activityName = info.appName;

                    visibleApps.add(info);
                }
            }
        }
    }

    private boolean isSystemPackage(String packageName) {
        try {
            final PackageInfo pi = mPackageManager.getPackageInfo(packageName, 0);
            if (pi.applicationInfo == null) return false;
            final int flags = pi.applicationInfo.flags;
            if ((flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    || (flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
                return true;
            }
        } catch (PackageManager.NameNotFoundException nnfe) {
            // Missing package?
        }
        return false;
    }

    private ApplicationInfo getAppInfoForUser(String packageName, int flags, UserHandle user) {
        try {
            return mIPm.getApplicationInfo(packageName, flags, user.getIdentifier());
        } catch (RemoteException re) {
            return null;
        }
    }

    public interface OnDisableUiForPackageListener {
        void onDisableUiForPackage(String packageName);
    }

    public static class SelectableAppInfo {
        public String packageName;
        public CharSequence appName;
        public CharSequence activityName;
        public Drawable icon;
        public SelectableAppInfo masterEntry;

        @Override
        public String toString() {
            return packageName + ": appName=" + appName + "; activityName=" + activityName
                    + "; icon=" + icon + "; masterEntry=" + masterEntry;
        }
    }

    private static class AppLabelComparator implements Comparator<SelectableAppInfo> {

        @Override
        public int compare(SelectableAppInfo lhs, SelectableAppInfo rhs) {
            String lhsLabel = lhs.activityName.toString();
            String rhsLabel = rhs.activityName.toString();
            return lhsLabel.toLowerCase().compareTo(rhsLabel.toLowerCase());
        }
    }

    /**
     * Unit test will subclass it to inject mocks.
     */
    @VisibleForTesting
    static class Injector {
        private Context mContext;
        private UserHandle mUser;

        Injector(Context context, UserHandle user) {
            mContext = context;
            mUser = user;
        }

        Context getContext() {
            return mContext;
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

        UserManager getUserManager() {
            return mContext.getSystemService(UserManager.class);
        }

        List<InputMethodInfo> getInputMethodList() {
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(
                    Context.INPUT_METHOD_SERVICE);
            return imm.getInputMethodListAsUser(mUser.getIdentifier());
        }
    }
}
