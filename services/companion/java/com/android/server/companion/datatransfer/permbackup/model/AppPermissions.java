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

package com.android.server.companion.datatransfer.permbackup.model;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.util.ArrayMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

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

    /** Do not actually commit changes to the platform until {@link #persistChanges} is called */
    private final boolean mDelayChanges;

    private PackageInfo mPackageInfo;

    public AppPermissions(Context context, PackageInfo packageInfo, boolean sortGroups,
            Runnable onErrorCallback) {
        this(context, packageInfo, sortGroups, false, onErrorCallback);
    }

    public AppPermissions(Context context, PackageInfo packageInfo, boolean sortGroups,
            boolean delayChanges, Runnable onErrorCallback) {
        mContext = context;
        mPackageInfo = packageInfo;
        mAppLabel = null; // doesn't matter for CDM
        mSortGroups = sortGroups;
        mDelayChanges = delayChanges;
        mOnErrorCallback = onErrorCallback;
        loadPermissionGroups();
    }

    public PackageInfo getPackageInfo() {
        return mPackageInfo;
    }

    /**
     * Refresh package info and permission groups.
     */
    public void refresh() {
        loadPackageInfo();
        loadPermissionGroups();
    }

    public CharSequence getAppLabel() {
        return mAppLabel;
    }

    /**
     * Get permission group by name.
     */
    public AppPermissionGroup getPermissionGroup(String name) {
        return mGroupNameToGroup.get(name);
    }

    public List<AppPermissionGroup> getPermissionGroups() {
        return mGroups;
    }

    /**
     * Check if the group is review required.
     */
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
            mPackageInfo = mContext.createPackageContextAsUser(mPackageInfo.packageName, 0,
                            UserHandle.getUserHandleForUid(mPackageInfo.applicationInfo.uid))
                    .getPackageManager().getPackageInfo(mPackageInfo.packageName,
                            PackageManager.GET_PERMISSIONS);
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
                            requestedPerm, mDelayChanges);
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

    /**
     * If the changes to the permission groups were delayed, persist them now.
     *
     * @param mayKillBecauseOfAppOpsChange If the app may be killed if app ops change. If this is
     *                                     set to {@code false} the caller has to make sure to kill
     *                                     the app if needed.
     */
    public void persistChanges(boolean mayKillBecauseOfAppOpsChange) {
        persistChanges(mayKillBecauseOfAppOpsChange, null);
    }

    /**
     * If the changes to the permission groups were delayed, persist them now.
     *
     * @param mayKillBecauseOfAppOpsChange If the app may be killed if app ops change. If this is
     *                                     set to {@code false} the caller has to make sure to kill
     *                                     the app if needed.
     * @param filterPermissions If provided, only persist state for the given permissions
     */
    public void persistChanges(boolean mayKillBecauseOfAppOpsChange,
            Set<String> filterPermissions) {
        if (mDelayChanges) {
            int numGroups = mGroups.size();

            for (int i = 0; i < numGroups; i++) {
                AppPermissionGroup group = mGroups.get(i);
                group.persistChanges(mayKillBecauseOfAppOpsChange, null, filterPermissions);

                AppPermissionGroup backgroundGroup = group.getBackgroundPermissions();
                if (backgroundGroup != null) {
                    backgroundGroup.persistChanges(mayKillBecauseOfAppOpsChange, null,
                            filterPermissions);
                }
            }
        }
    }
}
