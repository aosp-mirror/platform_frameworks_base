/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.utils;


import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;

import androidx.annotation.Nullable;

import java.util.List;


/**
 * Utility class for find out when to show WorkPolicyInfo
 */
public class WorkPolicyUtils {

    private final Context mContext;
    private final PackageManager mPackageManager;
    private final UserManager mUserManager;
    private final DevicePolicyManager mDevicePolicyManager;

    private static final int USER_NULL = -10000;

    public WorkPolicyUtils(
            Context context
    ) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        mUserManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        mDevicePolicyManager =
                (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    /**
     * Returns {@code true} if it is possilbe to resolve an Intent to launch the "Your work policy
     * info" page provided by the active Device Owner or Profile Owner app if it exists, {@code
     * false} otherwise.
     */
    public boolean hasWorkPolicy() {
        return getWorkPolicyInfoIntentDO() != null || getWorkPolicyInfoIntentPO() != null;
    }

    /**
     * Launches the Device Owner or Profile Owner's activity that displays the "Your work policy
     * info" page. Returns {@code true} if the activity has indeed been launched.
     */
    public boolean showWorkPolicyInfo(Context activityContext) {
        Intent intent = getWorkPolicyInfoIntentDO();
        if (intent != null) {
            activityContext.startActivity(intent);
            return true;
        }

        intent = getWorkPolicyInfoIntentPO();
        final int userId = getManagedProfileUserId();
        if (intent != null && userId != USER_NULL) {
            activityContext.startActivityAsUser(intent, UserHandle.of(userId));
            return true;
        }

        return false;
    }

    /**
     * Returns the work policy info intent if the device owner component exists,
     * and returns {@code null} otherwise
     */
    @Nullable
    public Intent getWorkPolicyInfoIntentDO() {
        final ComponentName ownerComponent = getDeviceOwnerComponent();
        if (ownerComponent == null) {
            return null;
        }

        // Only search for the required action in the Device Owner's package
        final Intent intent =
                new Intent(Settings.ACTION_SHOW_WORK_POLICY_INFO)
                        .setPackage(ownerComponent.getPackageName());
        final List<ResolveInfo> activities = mPackageManager.queryIntentActivities(intent, 0);
        if (activities.size() != 0) {
            return intent;
        }

        return null;
    }

    @Nullable
    private ComponentName getManagedProfileOwnerComponent(int managedUserId) {
        if (managedUserId == USER_NULL) {
            return null;
        }
        Context managedProfileContext;
        try {
            managedProfileContext =
                    mContext.createPackageContextAsUser(
                            mContext.getPackageName(), 0, UserHandle.of(managedUserId)
                    );
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }

        DevicePolicyManager managedProfileDevicePolicyManager =
                (DevicePolicyManager)
                        managedProfileContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName ownerComponent = managedProfileDevicePolicyManager.getProfileOwner();
        return ownerComponent;
    }

    /**
     * Returns the work policy info intent if the profile owner component exists,
     * and returns {@code null} otherwise
     */
    @Nullable
    public Intent getWorkPolicyInfoIntentPO() {
        final int managedUserId = getManagedProfileUserId();
        ComponentName ownerComponent = getManagedProfileOwnerComponent(managedUserId);
        if (ownerComponent == null) {
            return null;
        }

        // Only search for the required action in the Profile Owner's package
        final Intent intent =
                new Intent(Settings.ACTION_SHOW_WORK_POLICY_INFO)
                        .setPackage(ownerComponent.getPackageName());
        final List<ResolveInfo> activities =
                mPackageManager.queryIntentActivitiesAsUser(
                        intent, 0, UserHandle.of(managedUserId));
        if (activities.size() != 0) {
            return intent;
        }

        return null;
    }

    @Nullable
    private ComponentName getDeviceOwnerComponent() {
        if (!mPackageManager.hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN)) {
            return null;
        }
        return mDevicePolicyManager.getDeviceOwnerComponentOnAnyUser();
    }

    /**
     * Returns the user id of the managed profile, and returns {@code USER_NULL} otherwise
     */
    public int getManagedProfileUserId() {
        List<UserHandle> allProfiles = mUserManager.getAllProfiles();
        for (UserHandle uh : allProfiles) {
            int id = uh.getIdentifier();
            if (mUserManager.isManagedProfile(id)) {
                return id;
            }
        }
        return USER_NULL;
    }

}
