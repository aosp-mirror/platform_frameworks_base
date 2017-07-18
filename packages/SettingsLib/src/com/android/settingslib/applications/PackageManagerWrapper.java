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
package com.android.settingslib.applications;

import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;

import java.util.List;

/**
 * This interface replicates a subset of the android.content.pm.PackageManager (PM). The interface
 * exists so that we can use a thin wrapper around the PM in production code and a mock in tests.
 * We cannot directly mock or shadow the PM, because some of the methods we rely on are newer than
 * the API version supported by Robolectric.
 */
public interface PackageManagerWrapper {

    /**
     * Returns the real {@code PackageManager} object.
     */
    PackageManager getPackageManager();

    /**
     * Calls {@code PackageManager.getInstalledApplicationsAsUser()}.
     *
     * @see android.content.pm.PackageManager#getInstalledApplicationsAsUser
     */
    List<ApplicationInfo> getInstalledApplicationsAsUser(int flags, int userId);

    /**
     * Calls {@code PackageManager.hasSystemFeature()}.
     *
     * @see android.content.pm.PackageManager#hasSystemFeature
     */
    boolean hasSystemFeature(String name);

    /**
     * Calls {@code PackageManager.queryIntentActivitiesAsUser()}.
     *
     * @see android.content.pm.PackageManager#queryIntentActivitiesAsUser
     */
    List<ResolveInfo> queryIntentActivitiesAsUser(Intent intent, int flags, int userId);

    /**
     * Calls {@code PackageManager.getInstallReason()}.
     *
     * @see android.content.pm.PackageManager#getInstallReason
     */
    int getInstallReason(String packageName, UserHandle user);

    /**
     * Calls {@code PackageManager.getApplicationInfoAsUser}
     */
    ApplicationInfo getApplicationInfoAsUser(String packageName, int i, int userId)
            throws PackageManager.NameNotFoundException;

    /**
     * Calls {@code PackageManager.setDefaultBrowserPackageNameAsUser}
     */
    boolean setDefaultBrowserPackageNameAsUser(String packageName, int userId);

    /**
     * Calls {@code PackageManager.getDefaultBrowserPackageNameAsUser}
     */
    String getDefaultBrowserPackageNameAsUser(int userId);

    /**
     * Calls {@code PackageManager.getHomeActivities}
     */
    ComponentName getHomeActivities(List<ResolveInfo> homeActivities);

    /**
     * Calls {@code PackageManager.queryIntentServicesAsUser}
     */
    List<ResolveInfo> queryIntentServicesAsUser(Intent intent, int i, int user);

    /**
     * Calls {@code PackageManager.replacePreferredActivity}
     */
    void replacePreferredActivity(IntentFilter homeFilter, int matchCategoryEmpty,
            ComponentName[] componentNames, ComponentName component);

    /**
     * Gets information about a particular package from the package manager.
     * @param packageName The name of the package we would like information about.
     * @param i additional options flags. see javadoc for {@link PackageManager#getPackageInfo(String, int)}
     * @return The PackageInfo for the requested package
     * @throws NameNotFoundException
     */
    PackageInfo getPackageInfo(String packageName, int i) throws NameNotFoundException;

    /**
     * Retrieves the icon associated with this particular set of ApplicationInfo
     * @param info The ApplicationInfo to retrieve the icon for
     * @return The icon as a drawable.
     */
    Drawable getUserBadgedIcon(ApplicationInfo info);

    /**
     * Retrieves the label associated with the particular set of ApplicationInfo
     * @param app The ApplicationInfo to retrieve the label for
     * @return the label as a CharSequence
     */
    CharSequence loadLabel(ApplicationInfo app);

    /**
     * Retrieve all activities that can be performed for the given intent.
     */
    List<ResolveInfo> queryIntentActivities(Intent intent, int flags);
}
