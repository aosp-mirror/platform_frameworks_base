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

import android.annotation.NonNull;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;

import java.util.ArrayList;
import java.util.Objects;

/**
 * A permission and its properties.
 *
 * @see AppPermissionGroup
 */
public final class Permission {
    private final @NonNull PermissionInfo mPermissionInfo;
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
    private boolean mWhitelisted;

    public Permission(String name, @NonNull PermissionInfo permissionInfo, boolean granted,
            String appOp, boolean appOpAllowed, int flags) {
        mPermissionInfo = permissionInfo;
        mName = name;
        mBackgroundPermissionName = permissionInfo.backgroundPermission;
        mGranted = granted;
        mAppOp = appOp;
        mAppOpAllowed = appOpAllowed;
        mFlags = flags;
        mIsEphemeral =
                (permissionInfo.protectionLevel & PermissionInfo.PROTECTION_FLAG_INSTANT) != 0;
        mIsRuntimeOnly =
                (permissionInfo.protectionLevel & PermissionInfo.PROTECTION_FLAG_RUNTIME_ONLY) != 0;
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

    public PermissionInfo getPermissionInfo() {
        return mPermissionInfo;
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

    boolean isHardRestricted() {
        return (mPermissionInfo.flags & PermissionInfo.FLAG_HARD_RESTRICTED) != 0;
    }

    boolean isSoftRestricted() {
        return (mPermissionInfo.flags & PermissionInfo.FLAG_SOFT_RESTRICTED) != 0;
    }

    /**
     * Does this permission affect app ops.
     *
     * <p>I.e. does this permission have a matching app op or is this a background permission. All
     * background permissions affect the app op of its assigned foreground permission.
     *
     * @return {@code true} if this permission affects app ops
     */
    public boolean affectsAppOp() {
        return mAppOp != null || isBackgroundPermission();
    }

    /**
     * Check if the permission is granted.
     *
     * <p>This ignores the state of the app-op. I.e. for apps not handling runtime permissions, this
     * always returns {@code true}.
     *
     * @return If the permission is granted
     */
    public boolean isGranted() {
        return mGranted;
    }

    /**
     * Check if the permission is granted, also considering the state of the app-op.
     *
     * <p>For the UI, check the grant state of the whole group via
     * {@link AppPermissionGroup#areRuntimePermissionsGranted}.
     *
     * @return {@code true} if the permission (and the app-op) is granted.
     */
    public boolean isGrantedIncludingAppOp() {
        return mGranted && (!affectsAppOp() || isAppOpAllowed()) && !isReviewRequired();
    }

    public boolean isReviewRequired() {
        return (mFlags & PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED) != 0;
    }

    /**
     * Unset review required flag.
     */
    public void unsetReviewRequired() {
        mFlags &= ~PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED;
    }

    public void setGranted(boolean mGranted) {
        this.mGranted = mGranted;
    }

    public boolean isAppOpAllowed() {
        return mAppOpAllowed;
    }

    /**
     * Check if it's user fixed.
     */
    public boolean isUserFixed() {
        return (mFlags & PackageManager.FLAG_PERMISSION_USER_FIXED) != 0;
    }

    /**
     * Set user fixed flag.
     */
    public void setUserFixed(boolean userFixed) {
        if (userFixed) {
            mFlags |= PackageManager.FLAG_PERMISSION_USER_FIXED;
        } else {
            mFlags &= ~PackageManager.FLAG_PERMISSION_USER_FIXED;
        }
    }

    /**
     * Sets the one-time permission flag
     * @param oneTime true to set the flag, false to unset it
     */
    public void setOneTime(boolean oneTime) {
        if (oneTime) {
            mFlags |= PackageManager.FLAG_PERMISSION_ONE_TIME;
        } else {
            mFlags &= ~PackageManager.FLAG_PERMISSION_ONE_TIME;
        }
    }

    public boolean isSelectedLocationAccuracy() {
        return (mFlags & PackageManager.FLAG_PERMISSION_SELECTED_LOCATION_ACCURACY) != 0;
    }

    /**
     * Sets the selected-location-accuracy permission flag
     * @param selectedLocationAccuracy true to set the flag, false to unset it
     */
    public void setSelectedLocationAccuracy(boolean selectedLocationAccuracy) {
        if (selectedLocationAccuracy) {
            mFlags |= PackageManager.FLAG_PERMISSION_SELECTED_LOCATION_ACCURACY;
        } else {
            mFlags &= ~PackageManager.FLAG_PERMISSION_SELECTED_LOCATION_ACCURACY;
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
     * Is the permission user sensitive, i.e. should it always be shown to the user.
     *
     * <p>Non-sensitive permission are usually hidden behind a setting in an overflow menu or
     * some other kind of flag.
     *
     * @return {@code true} if the permission is user sensitive.
     */
    public boolean isUserSensitive() {
        if (isGrantedIncludingAppOp()) {
            return (mFlags & PackageManager.FLAG_PERMISSION_USER_SENSITIVE_WHEN_GRANTED) != 0;
        } else {
            return (mFlags & PackageManager.FLAG_PERMISSION_USER_SENSITIVE_WHEN_DENIED) != 0;
        }
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

    /**
     * @see PackageManager#FLAG_PERMISSION_ONE_TIME
     */
    public boolean isOneTime() {
        return (mFlags & PackageManager.FLAG_PERMISSION_ONE_TIME) != 0;
    }

    /**
     * Set userSet flag.
     */
    public void setUserSet(boolean userSet) {
        if (userSet) {
            mFlags |= PackageManager.FLAG_PERMISSION_USER_SET;
        } else {
            mFlags &= ~PackageManager.FLAG_PERMISSION_USER_SET;
        }
    }

    /**
     * Set policy fixed flag.
     */
    public void setPolicyFixed(boolean policyFixed) {
        if (policyFixed) {
            mFlags |= PackageManager.FLAG_PERMISSION_POLICY_FIXED;
        } else {
            mFlags &= ~PackageManager.FLAG_PERMISSION_POLICY_FIXED;
        }
    }

    /**
     * Check if the permission is revoke compat.
     */
    public boolean isRevokedCompat() {
        return (mFlags & PackageManager.FLAG_PERMISSION_REVOKED_COMPAT) != 0;
    }

    /**
     * Set revoke compat flag.
     */
    public void setRevokedCompat(boolean revokedCompat) {
        if (revokedCompat) {
            mFlags |= PackageManager.FLAG_PERMISSION_REVOKED_COMPAT;
        } else {
            mFlags &= ~PackageManager.FLAG_PERMISSION_REVOKED_COMPAT;
        }
    }

    /**
     * Set app op allowed flag.
     */
    public void setAppOpAllowed(boolean mAppOpAllowed) {
        this.mAppOpAllowed = mAppOpAllowed;
    }

    /**
     * Check if it's ephemeral.
     */
    public boolean isEphemeral() {
        return mIsEphemeral;
    }

    /**
     * Check if it's runtime only.
     */
    public boolean isRuntimeOnly() {
        return mIsRuntimeOnly;
    }

    /**
     * Check if it's granting allowed.
     */
    public boolean isGrantingAllowed(boolean isEphemeralApp, boolean supportsRuntimePermissions) {
        return (!isEphemeralApp || isEphemeral())
                && (supportsRuntimePermissions || !isRuntimeOnly());
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Permission)) {
            return false;
        }

        Permission other = (Permission) o;

        if (!Objects.equals(getName(), other.getName()) || getFlags() != other.getFlags()
                || isGranted() != other.isGranted()) {
            return false;
        }


        // Only compare permission names, in order to avoid recursion
        if (getBackgroundPermission() != null && other.getBackgroundPermission() != null) {
            if (!Objects.equals(getBackgroundPermissionName(),
                    other.getBackgroundPermissionName())) {
                return false;
            }
        } else if (getBackgroundPermission() != other.getBackgroundPermission()) {
            return false;
        }

        if (getForegroundPermissions() != null && other.getForegroundPermissions() != null) {
            ArrayList<Permission> others = other.getForegroundPermissions();
            if (getForegroundPermissions().size() != others.size()) {
                return false;
            }
            for (int i = 0; i < others.size(); i++) {
                if (!getForegroundPermissions().get(i).getName().equals(others.get(i).getName())) {
                    return false;
                }
            }
        } else if (getForegroundPermissions() != null || other.getForegroundPermissions() != null) {
            return false;
        }

        return Objects.equals(getAppOp(), other.getAppOp())
                && isAppOpAllowed() == other.isAppOpAllowed();
    }

    @Override
    public int hashCode() {
        ArrayList<String> linkedPermissionNames = new ArrayList<>();
        if (mBackgroundPermission != null) {
            linkedPermissionNames.add(mBackgroundPermission.getName());
        }
        if (mForegroundPermissions != null) {
            for (Permission linkedPermission: mForegroundPermissions) {
                if (linkedPermission != null) {
                    linkedPermissionNames.add(linkedPermission.getName());
                }
            }
        }
        return Objects.hash(mName, mFlags, mGranted, mAppOp, mAppOpAllowed, linkedPermissionNames);
    }
}
