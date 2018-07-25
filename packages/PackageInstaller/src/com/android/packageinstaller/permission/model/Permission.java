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

import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;

import java.util.ArrayList;

/**
 * A permission and it's properties.
 *
 * @see AppPermissionGroup
 */
public final class Permission {
    private final String mName;
    private final String mBackgroundPermissionName;
    private final String mAppOp;

    private boolean mGranted;
    private boolean mAppOpAllowed;
    private int mFlags;
    private boolean mIsEphemeral;
    private boolean mIsRuntimeOnly;
    private Permission mBackgroundPermission;
    private ArrayList<Permission> mForegroundPermissions;

    public Permission(String name, String backgroundPermissionName, boolean granted,
            String appOp, boolean appOpAllowed, int flags, int protectionLevel) {
        mName = name;
        mBackgroundPermissionName = backgroundPermissionName;
        mGranted = granted;
        mAppOp = appOp;
        mAppOpAllowed = appOpAllowed;
        mFlags = flags;
        mIsEphemeral = (protectionLevel & PermissionInfo.PROTECTION_FLAG_INSTANT) != 0;
        mIsRuntimeOnly = (protectionLevel & PermissionInfo.PROTECTION_FLAG_RUNTIME_ONLY) != 0;
    }

    /**
     * Mark this permission as background permission for {@code foregroundPermissions}.
     *
     * @param foregroundPermission The foreground permission
     */
    public void addForegroundPermissions(Permission foregroundPermission) {
        if (mForegroundPermissions == null) {
            mForegroundPermissions = new ArrayList<>(1);
        }
        mForegroundPermissions.add(foregroundPermission);
    }

    /**
     * Mark this permission as foreground permission for {@code backgroundPermission}.
     *
     * @param backgroundPermission The background permission
     */
    public void setBackgroundPermission(Permission backgroundPermission) {
        mBackgroundPermission = backgroundPermission;
    }

    public String getName() {
        return mName;
    }

    public String getAppOp() {
        return mAppOp;
    }

    public int getFlags() {
        return mFlags;
    }

    /**
     * Does this permission affect app ops.
     *
     * <p>I.e. does this permission have a matching app op or is this a background permission. All
     * background permissions affect the app op of it's assigned foreground permission.
     *
     * @return {@code true} if this permission affects app ops
     */
    public boolean affectsAppOp() {
        return mAppOp != null || isBackgroundPermission();
    }

    public boolean isGranted() {
        return mGranted;
    }

    public boolean isReviewRequired() {
        return (mFlags & PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED) != 0;
    }

    public void resetReviewRequired() {
        mFlags &= ~PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED;
    }

    public void setGranted(boolean mGranted) {
        this.mGranted = mGranted;
    }

    public boolean isAppOpAllowed() {
        return mAppOpAllowed;
    }

    public boolean isUserFixed() {
        return (mFlags & PackageManager.FLAG_PERMISSION_USER_FIXED) != 0;
    }

    public void setUserFixed(boolean userFixed) {
        if (userFixed) {
            mFlags |= PackageManager.FLAG_PERMISSION_USER_FIXED;
        } else {
            mFlags &= ~PackageManager.FLAG_PERMISSION_USER_FIXED;
        }
    }

    public boolean isSystemFixed() {
        return (mFlags & PackageManager.FLAG_PERMISSION_SYSTEM_FIXED) != 0;
    }

    public boolean isPolicyFixed() {
        return (mFlags & PackageManager.FLAG_PERMISSION_POLICY_FIXED) != 0;
    }

    public boolean isUserSet() {
        return (mFlags & PackageManager.FLAG_PERMISSION_USER_SET) != 0;
    }

    public boolean isGrantedByDefault() {
        return (mFlags & PackageManager.FLAG_PERMISSION_GRANTED_BY_DEFAULT) != 0;
    }

    /**
     * If this permission is split into a foreground and background permission, this is the name
     * of the background permission.
     *
     * @return The name of the background permission or {@code null} if the permission is not split
     */
    public String getBackgroundPermissionName() {
        return mBackgroundPermissionName;
    }

    /**
     * @return If this permission is split into a foreground and background permission,
     * returns the background permission
     */
    public Permission getBackgroundPermission() {
        return mBackgroundPermission;
    }

    /**
     * @return If this permission is split into a foreground and background permission,
     * returns the foreground permission
     */
    public ArrayList<Permission> getForegroundPermissions() {
        return mForegroundPermissions;
    }

    /**
     * @return {@code true} iff this is the foreground permission of a background-foreground-split
     * permission
     */
    public boolean hasBackgroundPermission() {
        return mBackgroundPermissionName != null;
    }

    /**
     * @return {@code true} iff this is the background permission of a background-foreground-split
     * permission
     */
    public boolean isBackgroundPermission() {
        return mForegroundPermissions != null;
    }

    public void setUserSet(boolean userSet) {
        if (userSet) {
            mFlags |= PackageManager.FLAG_PERMISSION_USER_SET;
        } else {
            mFlags &= ~PackageManager.FLAG_PERMISSION_USER_SET;
        }
    }

    public void setPolicyFixed(boolean policyFixed) {
        if (policyFixed) {
            mFlags |= PackageManager.FLAG_PERMISSION_POLICY_FIXED;
        } else {
            mFlags &= ~PackageManager.FLAG_PERMISSION_POLICY_FIXED;
        }
    }

    public boolean shouldRevokeOnUpgrade() {
        return (mFlags & PackageManager.FLAG_PERMISSION_REVOKE_ON_UPGRADE) != 0;
    }

    public void setRevokeOnUpgrade(boolean revokeOnUpgrade) {
        if (revokeOnUpgrade) {
            mFlags |= PackageManager.FLAG_PERMISSION_REVOKE_ON_UPGRADE;
        } else {
            mFlags &= ~PackageManager.FLAG_PERMISSION_REVOKE_ON_UPGRADE;
        }
    }

    public void setAppOpAllowed(boolean mAppOpAllowed) {
        this.mAppOpAllowed = mAppOpAllowed;
    }

    public boolean isEphemeral() {
        return mIsEphemeral;
    }

    public boolean isRuntimeOnly() {
        return mIsRuntimeOnly;
    }

    public boolean isGrantingAllowed(boolean isEphemeralApp, boolean supportsRuntimePermissions) {
        return (!isEphemeralApp || isEphemeral())
                && (supportsRuntimePermissions || !isRuntimeOnly());
    }
}
