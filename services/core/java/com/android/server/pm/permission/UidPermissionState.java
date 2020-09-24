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

package com.android.server.pm.permission;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.pm.PackageManager;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Permission state for a UID.
 * <p>
 * This class is also responsible for keeping track of the Linux GIDs per
 * user for a package or a shared user. The GIDs are computed as a set of
 * the GIDs for all granted permissions' GIDs on a per user basis.
 */
public final class UidPermissionState {
    /** The permission operation failed. */
    public static final int PERMISSION_OPERATION_FAILURE = -1;

    /** The permission operation succeeded and no gids changed. */
    public static final int PERMISSION_OPERATION_SUCCESS = 0;

    /** The permission operation succeeded and gids changed. */
    public static final int PERMISSION_OPERATION_SUCCESS_GIDS_CHANGED = 1;

    private static final int[] NO_GIDS = {};

    @NonNull
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private ArrayMap<String, PermissionState> mPermissions;

    @NonNull
    private int[] mGlobalGids = NO_GIDS;

    private boolean mMissing;

    private boolean mPermissionReviewRequired;

    public UidPermissionState() {
        /* do nothing */
    }

    public UidPermissionState(@NonNull UidPermissionState prototype) {
        copyFrom(prototype);
    }

    /**
     * Gets the global gids, applicable to all users.
     */
    @NonNull
    public int[] getGlobalGids() {
        return mGlobalGids;
    }

    /**
     * Sets the global gids, applicable to all users.
     *
     * @param globalGids The global gids.
     */
    public void setGlobalGids(@NonNull int[] globalGids) {
        if (!ArrayUtils.isEmpty(globalGids)) {
            mGlobalGids = Arrays.copyOf(globalGids, globalGids.length);
        }
    }

    static void invalidateCache() {
        PackageManager.invalidatePackageInfoCache();
    }

    /**
     * Initialized this instance from another one.
     *
     * @param other The other instance.
     */
    public void copyFrom(@NonNull UidPermissionState other) {
        if (other == this) {
            return;
        }

        synchronized (mLock) {
            if (mPermissions != null) {
                if (other.mPermissions == null) {
                    mPermissions = null;
                } else {
                    mPermissions.clear();
                }
            }
            if (other.mPermissions != null) {
                if (mPermissions == null) {
                    mPermissions = new ArrayMap<>();
                }
                final int permissionCount = other.mPermissions.size();
                for (int i = 0; i < permissionCount; i++) {
                    String name = other.mPermissions.keyAt(i);
                    PermissionState permissionState = other.mPermissions.valueAt(i);
                    mPermissions.put(name, new PermissionState(permissionState));
                }
            }
        }

        mGlobalGids = NO_GIDS;
        if (other.mGlobalGids != NO_GIDS) {
            mGlobalGids = other.mGlobalGids.clone();
        }

        mMissing = other.mMissing;

        mPermissionReviewRequired = other.mPermissionReviewRequired;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final UidPermissionState other = (UidPermissionState) obj;

        synchronized (mLock) {
            if (mPermissions == null) {
                if (other.mPermissions != null) {
                    return false;
                }
            } else if (!mPermissions.equals(other.mPermissions)) {
                return false;
            }
        }

        if (mMissing != other.mMissing) {
            return false;
        }

        if (mPermissionReviewRequired != other.mPermissionReviewRequired) {
            return false;
        }
        return Arrays.equals(mGlobalGids, other.mGlobalGids);
    }

    /**
     * Check whether the permissions state is missing for a user. This can happen if permission
     * state is rolled back and we'll need to generate a reasonable default state to keep the app
     * usable.
     */
    public boolean isMissing() {
        return mMissing;
    }

    /**
     * Set whether the permissions state is missing for a user. This can happen if permission state
     * is rolled back and we'll need to generate a reasonable default state to keep the app usable.
     */
    public void setMissing(boolean missing) {
        mMissing = missing;
    }

    public boolean isPermissionReviewRequired() {
        return mPermissionReviewRequired;
    }

    /**
     * Gets whether the state has a given permission.
     *
     * @param name The permission name.
     * @return Whether the state has the permission.
     */
    public boolean hasPermission(@NonNull String name) {
        synchronized (mLock) {
            if (mPermissions == null) {
                return false;
            }
            PermissionState permissionState = mPermissions.get(name);
            return permissionState != null && permissionState.isGranted();
        }
    }

    /**
     * Returns whether the state has any known request for the given permission name,
     * whether or not it has been granted.
     *
     * @deprecated Not all requested permissions may be here.
     */
    @Deprecated
    public boolean hasRequestedPermission(@NonNull ArraySet<String> names) {
        synchronized (mLock) {
            if (mPermissions == null) {
                return false;
            }
            for (int i = names.size() - 1; i >= 0; i--) {
                if (mPermissions.get(names.valueAt(i)) != null) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Returns whether the state has any known request for the given permission name,
     * whether or not it has been granted.
     *
     * @deprecated Not all requested permissions may be here.
     */
    @Deprecated
    public boolean hasRequestedPermission(@NonNull String name) {
        return mPermissions != null && (mPermissions.get(name) != null);
    }

    /**
     * Gets all permissions for a given device user id regardless if they
     * are install time or runtime permissions.
     *
     * @return The permissions or an empty set.
     */
    @NonNull
    public Set<String> getPermissions() {
        synchronized (mLock) {
            if (mPermissions == null) {
                return Collections.emptySet();
            }

            Set<String> permissions = new ArraySet<>(mPermissions.size());

            final int permissionCount = mPermissions.size();
            for (int i = 0; i < permissionCount; i++) {
                String permission = mPermissions.keyAt(i);

                if (hasPermission(permission)) {
                    permissions.add(permission);
                }
            }

            return permissions;
        }
    }

    /**
     * Gets the flags for a permission.
     *
     * @param name The permission name.
     * @return The permission state or null if no such.
     */
    public int getPermissionFlags(@NonNull String name) {
        PermissionState permState = getPermissionState(name);
        if (permState != null) {
            return permState.getFlags();
        }
        return 0;
    }

    /**
     * Update the flags associated with a given permission.
     * @param permission The permission whose flags to update.
     * @param flagMask Mask for which flags to change.
     * @param flagValues New values for the mask flags.
     * @return Whether the permission flags changed.
     */
    public boolean updatePermissionFlags(@NonNull BasePermission permission, int flagMask,
            int flagValues) {
        if (flagMask == 0) {
            return false;
        }

        PermissionState permissionState = ensurePermissionState(permission);

        final int oldFlags = permissionState.getFlags();

        synchronized (mLock) {
            final boolean updated = permissionState.updateFlags(flagMask, flagValues);
            if (updated) {
                final int newFlags = permissionState.getFlags();
                if ((oldFlags & PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED) == 0
                        && (newFlags & PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED) != 0) {
                    mPermissionReviewRequired = true;
                } else if ((oldFlags & PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED) != 0
                        && (newFlags & PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED) == 0) {
                    if (mPermissionReviewRequired && !hasPermissionRequiringReview()) {
                        mPermissionReviewRequired = false;
                    }
                }
            }
            return updated;
        }
    }

    private boolean hasPermissionRequiringReview() {
        synchronized (mLock) {
            final int permissionCount = mPermissions.size();
            for (int i = 0; i < permissionCount; i++) {
                final PermissionState permission = mPermissions.valueAt(i);
                if ((permission.getFlags() & PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean updatePermissionFlagsForAllPermissions(int flagMask, int flagValues) {
        synchronized (mLock) {
            if (mPermissions == null) {
                return false;
            }
            boolean changed = false;
            final int permissionCount = mPermissions.size();
            for (int i = 0; i < permissionCount; i++) {
                PermissionState permissionState = mPermissions.valueAt(i);
                changed |= permissionState.updateFlags(flagMask, flagValues);
            }
            return changed;
        }
    }

    /**
     * Compute the Linux gids for a given device user from the permissions
     * granted to this user. Note that these are computed to avoid additional
     * state as they are rarely accessed.
     *
     * @param userId The device user id.
     * @return The gids for the device user.
     */
    @NonNull
    public int[] computeGids(@UserIdInt int userId) {
        int[] gids = mGlobalGids;

        synchronized (mLock) {
            if (mPermissions != null) {
                final int permissionCount = mPermissions.size();
                for (int i = 0; i < permissionCount; i++) {
                    PermissionState permissionState = mPermissions.valueAt(i);
                    if (!permissionState.isGranted()) {
                        continue;
                    }
                    final int[] permGids = permissionState.computeGids(userId);
                    if (permGids != NO_GIDS) {
                        gids = appendInts(gids, permGids);
                    }
                }
            }
        }

        return gids;
    }

    /**
     * Compute the Linux gids for all device users from the permissions
     * granted to these users.
     *
     * @return The gids for all device users.
     */
    @NonNull
    public int[] computeGids(@NonNull int[] userIds) {
        int[] gids = mGlobalGids;

        for (int userId : userIds) {
            final int[] userGids = computeGids(userId);
            gids = appendInts(gids, userGids);
        }

        return gids;
    }

    /**
     * Resets the internal state of this object.
     */
    public void reset() {
        mGlobalGids = NO_GIDS;

        synchronized (mLock) {
            mPermissions = null;
            invalidateCache();
        }

        mMissing = false;
        mPermissionReviewRequired = false;
    }

    /**
     * Gets the state for a permission or null if no such.
     *
     * @param name The permission name.
     * @return The permission state.
     */
    @Nullable
    public PermissionState getPermissionState(@NonNull String name) {
        synchronized (mLock) {
            if (mPermissions == null) {
                return null;
            }
            return mPermissions.get(name);
        }
    }

    /**
     * Gets all permission states.
     *
     * @return The permission states or an empty set.
     */
    @NonNull
    public List<PermissionState> getPermissionStates() {
        synchronized (mLock) {
            if (mPermissions == null) {
                return Collections.emptyList();
            }
            return new ArrayList<>(mPermissions.values());
        }
    }

    /**
     * Put a permission state.
     */
    public void putPermissionState(@NonNull BasePermission permission, boolean isGranted,
            int flags) {
        synchronized (mLock) {
            ensureNoPermissionState(permission.name);
            PermissionState permissionState = ensurePermissionState(permission);
            if (isGranted) {
                permissionState.grant();
            }
            permissionState.updateFlags(flags, flags);
            if ((flags & PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED) != 0) {
                mPermissionReviewRequired = true;
            }
        }
    }

    /**
     * Grant a permission.
     *
     * @param permission The permission to grant.
     * @return The operation result which is either {@link #PERMISSION_OPERATION_SUCCESS},
     *     or {@link #PERMISSION_OPERATION_SUCCESS_GIDS_CHANGED}, or {@link
     *     #PERMISSION_OPERATION_FAILURE}.
     */
    public int grantPermission(@NonNull BasePermission permission) {
        if (hasPermission(permission.getName())) {
            return PERMISSION_OPERATION_SUCCESS;
        }

        PermissionState permissionState = ensurePermissionState(permission);

        if (!permissionState.grant()) {
            return PERMISSION_OPERATION_FAILURE;
        }

        return permission.hasGids() ? PERMISSION_OPERATION_SUCCESS_GIDS_CHANGED
                : PERMISSION_OPERATION_SUCCESS;
    }

    /**
     * Revoke a permission.
     *
     * @param permission The permission to revoke.
     * @return The operation result which is either {@link #PERMISSION_OPERATION_SUCCESS},
     *     or {@link #PERMISSION_OPERATION_SUCCESS_GIDS_CHANGED}, or {@link
     *     #PERMISSION_OPERATION_FAILURE}.
     */
    public int revokePermission(@NonNull BasePermission permission) {
        final String permissionName = permission.getName();
        if (!hasPermission(permissionName)) {
            return PERMISSION_OPERATION_SUCCESS;
        }

        PermissionState permissionState;
        synchronized (mLock) {
            permissionState = mPermissions.get(permissionName);
        }

        if (!permissionState.revoke()) {
            return PERMISSION_OPERATION_FAILURE;
        }

        if (permissionState.isDefault()) {
            ensureNoPermissionState(permissionName);
        }

        return permission.hasGids() ? PERMISSION_OPERATION_SUCCESS_GIDS_CHANGED
                : PERMISSION_OPERATION_SUCCESS;
    }

    // TODO: fix this to use arraycopy and append all ints in one go
    private static int[] appendInts(int[] current, int[] added) {
        if (current != null && added != null) {
            for (int guid : added) {
                current = ArrayUtils.appendInt(current, guid);
            }
        }
        return current;
    }

    @NonNull
    private PermissionState ensurePermissionState(@NonNull BasePermission permission) {
        final String permissionName = permission.getName();
        synchronized (mLock) {
            if (mPermissions == null) {
                mPermissions = new ArrayMap<>();
            }
            PermissionState permissionState = mPermissions.get(permissionName);
            if (permissionState == null) {
                permissionState = new PermissionState(permission);
                mPermissions.put(permissionName, permissionState);
            }
            return permissionState;
        }
    }

    private void ensureNoPermissionState(@NonNull String name) {
        synchronized (mLock) {
            if (mPermissions == null) {
                return;
            }
            mPermissions.remove(name);
            if (mPermissions.isEmpty()) {
                mPermissions = null;
            }
        }
    }
}
