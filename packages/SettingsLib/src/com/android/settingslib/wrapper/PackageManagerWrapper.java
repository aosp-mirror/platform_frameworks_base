/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.settingslib.wrapper;

import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.os.storage.VolumeInfo;

import java.util.List;

/**
 * A thin wrapper class that simplifies testing by putting a mockable layer between the application
 * and the PackageManager. This class only provides access to the minimum number of functions from
 * the PackageManager needed for DeletionHelper to work.
 */
@Deprecated
// Please replace with android.content.pm.PackageManager
public class PackageManagerWrapper {

    private final PackageManager mPm;

    public PackageManagerWrapper(PackageManager pm) {
        mPm = pm;
    }

    /**
     * Returns the real {@code PackageManager} object.
     */
    public PackageManager getPackageManager() {
        return mPm;
    }

    /**
     * Calls {@code PackageManager.getInstalledApplicationsAsUser()}.
     *
     * @see android.content.pm.PackageManager#getInstalledApplicationsAsUser
     */
    public List<ApplicationInfo> getInstalledApplicationsAsUser(int flags, int userId) {
        return mPm.getInstalledApplicationsAsUser(flags, userId);
    }

    /**
     * Calls {@code PackageManager.getInstalledPackagesAsUser}
     */
    public List<PackageInfo> getInstalledPackagesAsUser(int flags, int userId) {
        return mPm.getInstalledPackagesAsUser(flags, userId);
    }

    /**
     * Calls {@code PackageManager.hasSystemFeature()}.
     *
     * @see android.content.pm.PackageManager#hasSystemFeature
     */
    public boolean hasSystemFeature(String name) {
        return mPm.hasSystemFeature(name);
    }

    /**
     * Calls {@code PackageManager.queryIntentActivitiesAsUser()}.
     *
     * @see android.content.pm.PackageManager#queryIntentActivitiesAsUser
     */
    public List<ResolveInfo> queryIntentActivitiesAsUser(Intent intent, int flags, int userId) {
        return mPm.queryIntentActivitiesAsUser(intent, flags, userId);
    }

    /**
     * Calls {@code PackageManager.getInstallReason()}.
     *
     * @see android.content.pm.PackageManager#getInstallReason
     */
    public int getInstallReason(String packageName, UserHandle user) {
        return mPm.getInstallReason(packageName, user);
    }

    /**
     * Calls {@code PackageManager.getApplicationInfoAsUser}
     */
    public ApplicationInfo getApplicationInfoAsUser(String packageName, int i, int userId)
            throws PackageManager.NameNotFoundException {
        return mPm.getApplicationInfoAsUser(packageName, i, userId);
    }

    /**
     * Calls {@code PackageManager.setDefaultBrowserPackageNameAsUser}
     */
    public boolean setDefaultBrowserPackageNameAsUser(String packageName, int userId) {
        return mPm.setDefaultBrowserPackageNameAsUser(packageName, userId);
    }

    /**
     * Calls {@code PackageManager.getDefaultBrowserPackageNameAsUser}
     */
    public String getDefaultBrowserPackageNameAsUser(int userId) {
        return mPm.getDefaultBrowserPackageNameAsUser(userId);
    }

    /**
     * Calls {@code PackageManager.getHomeActivities}
     */
    public ComponentName getHomeActivities(List<ResolveInfo> homeActivities) {
        return mPm.getHomeActivities(homeActivities);
    }

    /**
     * Calls {@code PackageManager.queryIntentServicesAsUser}
     */
    public List<ResolveInfo> queryIntentServicesAsUser(Intent intent, int i, int user) {
        return mPm.queryIntentServicesAsUser(intent, i, user);
    }

    /**
     * Calls {@code PackageManager.queryIntentServices}
     */
    public List<ResolveInfo> queryIntentServices(Intent intent, int i) {
        return mPm.queryIntentServices(intent, i);
    }

    /**
     * Calls {@code PackageManager.replacePreferredActivity}
     */
    public void replacePreferredActivity(IntentFilter homeFilter, int matchCategoryEmpty,
            ComponentName[] componentNames, ComponentName component) {
        mPm.replacePreferredActivity(homeFilter, matchCategoryEmpty, componentNames, component);
    }

    /**
     * Gets information about a particular package from the package manager.
     *
     * @param packageName The name of the package we would like information about.
     * @param i           additional options flags. see javadoc for
     *                    {@link PackageManager#getPackageInfo(String, int)}
     * @return The PackageInfo for the requested package
     */
    public PackageInfo getPackageInfo(String packageName, int i) throws NameNotFoundException {
        return mPm.getPackageInfo(packageName, i);
    }

    /**
     * Retrieves the icon associated with this particular set of ApplicationInfo
     *
     * @param info The ApplicationInfo to retrieve the icon for
     * @return The icon as a drawable.
     */
    public Drawable getUserBadgedIcon(ApplicationInfo info) {
        return mPm.getUserBadgedIcon(mPm.loadUnbadgedItemIcon(info, info),
                new UserHandle(UserHandle.getUserId(info.uid)));
    }

    /**
     * Retrieves the label associated with the particular set of ApplicationInfo
     *
     * @param app The ApplicationInfo to retrieve the label for
     * @return the label as a CharSequence
     */
    public CharSequence loadLabel(ApplicationInfo app) {
        return app.loadLabel(mPm);
    }

    /**
     * Retrieve all activities that can be performed for the given intent.
     */
    public List<ResolveInfo> queryIntentActivities(Intent intent, int flags) {
        return mPm.queryIntentActivities(intent, flags);
    }

    /**
     * Calls {@code PackageManager.getPrimaryStorageCurrentVolume}
     */
    public VolumeInfo getPrimaryStorageCurrentVolume() {
        return mPm.getPrimaryStorageCurrentVolume();
    }

    /**
     * Calls {@code PackageManager.deletePackageAsUser}
     */
    public void deletePackageAsUser(String packageName, IPackageDeleteObserver observer, int flags,
            int userId) {
        mPm.deletePackageAsUser(packageName, observer, flags, userId);
    }

    /**
     * Calls {@code PackageManager.getPackageUidAsUser}
     */
    public int getPackageUidAsUser(String pkg, int userId)
            throws PackageManager.NameNotFoundException {
        return mPm.getPackageUidAsUser(pkg, userId);
    }

    /**
     * Calls {@code PackageManager.setApplicationEnabledSetting}
     */
    public void setApplicationEnabledSetting(String packageName, int newState, int flags) {
        mPm.setApplicationEnabledSetting(packageName, newState, flags);
    }

    /**
     * Calls {@code PackageManager.getApplicationEnabledSetting}
     */
    public int getApplicationEnabledSetting(String packageName) {
        return mPm.getApplicationEnabledSetting(packageName);
    }

    /**
     * Calls {@code PackageManager.setComponentEnabledSetting}
     */
    public void setComponentEnabledSetting(ComponentName componentName, int newState, int flags) {
        mPm.setComponentEnabledSetting(componentName, newState, flags);
    }

    /**
     * Calls {@code PackageManager.getApplicationInfo}
     */
    public ApplicationInfo getApplicationInfo(String packageName, int flags)
            throws NameNotFoundException {
        return mPm.getApplicationInfo(packageName, flags);
    }

    /**
     * Calls {@code PackageManager.getApplicationLabel}
     */
    public CharSequence getApplicationLabel(ApplicationInfo info) {
        return mPm.getApplicationLabel(info);
    }

    /**
     * Calls {@code PackageManager.queryBroadcastReceivers}
     */
    public List<ResolveInfo> queryBroadcastReceivers(Intent intent, int flags) {
        return mPm.queryBroadcastReceivers(intent, flags);
    }
}

