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
 * limitations under the License.
 */

package com.android.packageinstaller.permission.model;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.text.BidiFormatter;
import android.util.ArrayMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An app that requests permissions.
 *
 * <p>Allows to query all permission groups of the app and which permission belongs to which group.
 */
public final class AppPermissions {
    /**
     * All permission groups the app requests. Background permission groups are attached to their
     * foreground groups.
     */
    private final ArrayList<AppPermissionGroup> mGroups = new ArrayList<>();

    /** Cache: group name -> group */
    private final ArrayMap<String, AppPermissionGroup> mGroupNameToGroup = new ArrayMap<>();

    /** Cache: permission name -> group. Might point to background group */
    private final ArrayMap<String, AppPermissionGroup> mPermissionNameToGroup = new ArrayMap<>();

    private final Context mContext;

    private final CharSequence mAppLabel;

    private final Runnable mOnErrorCallback;

    private final boolean mSortGroups;

    private PackageInfo mPackageInfo;

    public AppPermissions(Context context, PackageInfo packageInfo, boolean sortGroups,
            Runnable onErrorCallback) {
        mContext = context;
        mPackageInfo = packageInfo;
        mAppLabel = BidiFormatter.getInstance().unicodeWrap(
                packageInfo.applicationInfo.loadSafeLabel(context.getPackageManager(),
                        PackageItemInfo.DEFAULT_MAX_LABEL_SIZE_PX,
                        PackageItemInfo.SAFE_LABEL_FLAG_TRIM
                                | PackageItemInfo.SAFE_LABEL_FLAG_FIRST_LINE)
                        .toString());
        mSortGroups = sortGroups;
        mOnErrorCallback = onErrorCallback;
        loadPermissionGroups();
    }

    public PackageInfo getPackageInfo() {
        return mPackageInfo;
    }

    public void refresh() {
        loadPackageInfo();
        loadPermissionGroups();
    }

    public CharSequence getAppLabel() {
        return mAppLabel;
    }

    public AppPermissionGroup getPermissionGroup(String name) {
        return mGroupNameToGroup.get(name);
    }

    public List<AppPermissionGroup> getPermissionGroups() {
        return mGroups;
    }

    public boolean isReviewRequired() {
        final int groupCount = mGroups.size();
        for (int i = 0; i < groupCount; i++) {
            AppPermissionGroup group = mGroups.get(i);
            if (group.isReviewRequired()) {
                return true;
            }
        }
        return false;
    }

    private void loadPackageInfo() {
        try {
            mPackageInfo = mContext.getPackageManager().getPackageInfo(
                    mPackageInfo.packageName, PackageManager.GET_PERMISSIONS);
        } catch (PackageManager.NameNotFoundException e) {
            if (mOnErrorCallback != null) {
                mOnErrorCallback.run();
            }
        }
    }

    /**
     * Add all individual permissions of the {@code group} to the {@link #mPermissionNameToGroup}
     * lookup table.
     *
     * @param group The group of permissions to add
     */
    private void addAllPermissions(AppPermissionGroup group) {
        ArrayList<Permission> perms = group.getPermissions();

        int numPerms = perms.size();
        for (int permNum = 0; permNum < numPerms; permNum++) {
            mPermissionNameToGroup.put(perms.get(permNum).getName(), group);
        }
    }

    private void loadPermissionGroups() {
        mGroups.clear();
        mGroupNameToGroup.clear();
        mPermissionNameToGroup.clear();

        if (mPackageInfo.requestedPermissions != null) {
            for (String requestedPerm : mPackageInfo.requestedPermissions) {
                if (getGroupForPermission(requestedPerm) == null) {
                    AppPermissionGroup group = AppPermissionGroup.create(mContext, mPackageInfo,
                            requestedPerm);
                    if (group == null) {
                        continue;
                    }

                    mGroups.add(group);
                    mGroupNameToGroup.put(group.getName(), group);

                    addAllPermissions(group);

                    AppPermissionGroup backgroundGroup = group.getBackgroundPermissions();
                    if (backgroundGroup != null) {
                        addAllPermissions(backgroundGroup);
                    }
                }
            }

            if (mSortGroups) {
                Collections.sort(mGroups);
            }
        }
    }

    /**
     * Find the group a permission belongs to.
     *
     * <p>The group found might be a background group.
     *
     * @param permission The name of the permission
     *
     * @return The group the permission belongs to
     */
    public AppPermissionGroup getGroupForPermission(String permission) {
        return mPermissionNameToGroup.get(permission);
    }
}
