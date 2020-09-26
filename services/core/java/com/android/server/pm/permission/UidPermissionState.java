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

    private boolean mMissing;

    @GuardedBy("mLock")
    @Nullable
    private ArrayMap<String, PermissionState> mPermissions;

    private boolean mPermissionReviewRequired;

    @NonNull
    private int[] mGlobalGids = NO_GIDS;

    public UidPermissionState() {}

    public UidPermissionState(@NonNull UidPermissionState other) {
        synchronized (mLock) {
            mMissing = other.mMissing;

            if (other.mPermissions != null) {
                mPermissions = new ArrayMap<>();
                final int permissionsSize = other.mPermissions.size();
                for (int i = 0; i < permissionsSize; i++) {
                    final String name = other.mPermissions.keyAt(i);
                    final PermissionState permissionState = other.mPermissions.valueAt(i);
                    mPermissions.put(name, new PermissionState(permissionState));
                }
            }

            mPermissionReviewRequired = other.mPermissionReviewRequired;

            if (other.mGlobalGids != NO_GIDS) {
                mGlobalGids = other.mGlobalGids.clone();
            }
        }
    }

    /**
     * Reset the internal state of this object.
     */
    public void reset() {
        synchronized (mLock) {
            mMissing = false;
            mPermissions = null;
            mPermissionReviewRequired = false;
            mGlobalGids = NO_GIDS;
            invalidateCache();
        }
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

    /**
     * Get whether there is a permission state for a permission.
     *
     * @deprecated This used to be named hasRequestedPermission() and its usage is confusing
     */
    @Deprecated
    public boolean hasPermissionState(@NonNull String name) {
        synchronized (mLock) {
            return mPermissions != null && mPermissions.containsKey(name);
        }
    }

    /**
     * Get whether there is a permission state for any of the permissions.
     *
     * @deprecated This used to be named hasRequestedPermission() and its usage is confusing
     */
    @Deprecated
    public boolean hasPermissionState(@NonNull ArraySet<String> names) {
        synchronized (mLock) {
            if (mPermissions == null) {
                return false;
            }
            final int namesSize = names.size();
            for (int i = 0; i < namesSize; i++) {
                final String name = names.valueAt(i);
                if (mPermissions.containsKey(name)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Gets the state for a permission or null if none.
     *
     * @param name the permission name.
     * @return the permission state.
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
     * Get all permission states.
     *
     * @return the permission states
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
     * Get whether a permission is granted.
     *
     * @param name the permission name
     * @return whether the permission is granted
     */
    public boolean isPermissionGranted(@NonNull String name) {
        synchronized (mLock) {
            if (mPermissions == null) {
                return false;
            }
            PermissionState permissionState = mPermissions.get(name);
            return permissionState != null && permissionState.isGranted();
        }
    }

    /**
     * Get all the granted permissions.
     *
     * @return the granted permissions
     */
    @NonNull
    public Set<String> getGrantedPermissions() {
        synchronized (mLock) {
            if (mPermissions == null) {
                return Collections.emptySet();
            }

            Set<String> permissions = new ArraySet<>(mPermissions.size());
            final int permissionsSize = mPermissions.size();
            for (int i = 0; i < permissionsSize; i++) {
                PermissionState permissionState = mPermissions.valueAt(i);

                if (permissionState.isGranted()) {
                    permissions.add(permissionState.getName());
                }
            }
            return permissions;
        }
    }

    /**
     * Grant a permission.
     *
     * @param permission the permission to grantt
     * @return the operation result, which is either {@link #PERMISSION_OPERATION_SUCCESS},
     *     or {@link #PERMISSION_OPERATION_SUCCESS_GIDS_CHANGED}, or {@link
     *     #PERMISSION_OPERATION_FAILURE}.
     */
    public int grantPermission(@NonNull BasePermission permission) {
        if (isPermissionGranted(permission.getName())) {
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
     * @param permission the permission to revoke
     * @return the operation result, which is either {@link #PERMISSION_OPERATION_SUCCESS},
     *     or {@link #PERMISSION_OPERATION_SUCCESS_GIDS_CHANGED}, or {@link
     *     #PERMISSION_OPERATION_FAILURE}.
     */
    public int revokePermission(@NonNull BasePermission permission) {
        final String name = permission.getName();
        if (!isPermissionGranted(name)) {
            return PERMISSION_OPERATION_SUCCESS;
        }

        PermissionState permissionState;
        synchronized (mLock) {
            permissionState = mPermissions.get(name);
        }

        if (!permissionState.revoke()) {
            return PERMISSION_OPERATION_FAILURE;
        }

        if (permissionState.isDefault()) {
            ensureNoPermissionState(name);
        }

        return permission.hasGids() ? PERMISSION_OPERATION_SUCCESS_GIDS_CHANGED
                : PERMISSION_OPERATION_SUCCESS;
    }

    /**
     * Get the flags for a permission.
     *
     * @param name the permission name.
     * @return the permission flags
     */
    public int getPermissionFlags(@NonNull String name) {
        final PermissionState permissionState = getPermissionState(name);
        if (permissionState == null) {
            return 0;
        }
        return permissionState.getFlags();
    }

    /**
     * Update the flags for a permission.
     *
     * @param permission the permission name
     * @param flagMask the mask for the flags
     * @param flagValues the new values for the masked flags
     * @return whether the permission flags changed
     */
    public boolean updatePermissionFlags(@NonNull BasePermission permission, int flagMask,
            int flagValues) {
        if (flagMask == 0) {
            return false;
        }

        synchronized (mLock) {
            final PermissionState permissionState = ensurePermissionState(permission);
            final int oldFlags = permissionState.getFlags();

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

    public boolean updatePermissionFlagsForAllPermissions(int flagMask, int flagValues) {
        synchronized (mLock) {
            if (mPermissions == null) {
                return false;
            }
            boolean changed = false;
            final int permissionsSize = mPermissions.size();
            for (int i = 0; i < permissionsSize; i++) {
                final PermissionState permissionState = mPermissions.valueAt(i);
                changed |= permissionState.updateFlags(flagMask, flagValues);
            }
            return changed;
        }
    }

    @NonNull
    private PermissionState ensurePermissionState(@NonNull BasePermission permission) {
        final String name = permission.getName();
        synchronized (mLock) {
            if (mPermissions == null) {
                mPermissions = new ArrayMap<>();
            }
            PermissionState permissionState = mPermissions.get(name);
            if (permissionState == null) {
                permissionState = new PermissionState(permission);
                mPermissions.put(name, permissionState);
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

    public boolean isPermissionReviewRequired() {
        return mPermissionReviewRequired;
    }

    private boolean hasPermissionRequiringReview() {
        synchronized (mLock) {
            final int permissionsSize = mPermissions.size();
            for (int i = 0; i < permissionsSize; i++) {
                final PermissionState permission = mPermissions.valueAt(i);
                if ((permission.getFlags() & PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED) != 0) {
                    return true;
                }
            }
            return false;
        }
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
        } else {
            mGlobalGids = NO_GIDS;
        }
    }

    /**
     * Compute the Linux GIDs from the permissions granted to a user.
     *
     * @param userId the user ID
     * @return the GIDs for the user
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
     * Compute the Linux GIDs from the permissions granted to specified users.
     *
     * @param userIds the user IDs
     * @return the GIDs for the user
     */
    @NonNull
    public int[] computeGids(@NonNull int[] userIds) {
        int[] gids = mGlobalGids;

        for (final int userId : userIds) {
            final int[] userGids = computeGids(userId);
            gids = appendInts(gids, userGids);
        }

        return gids;
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

    static void invalidateCache() {
        PackageManager.invalidatePackageInfoCache();
    }
}
