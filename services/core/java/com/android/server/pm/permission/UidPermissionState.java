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
import android.util.IntArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Permission state for a UID.
 */
public final class UidPermissionState {
    private boolean mMissing;

    @Nullable
    private ArrayMap<String, PermissionState> mPermissions;

    public UidPermissionState() {}

    public UidPermissionState(@NonNull UidPermissionState other) {
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
    }

    /**
     * Reset the internal state of this object.
     */
    public void reset() {
        mMissing = false;
        mPermissions = null;
        invalidateCache();
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
        return mPermissions != null && mPermissions.containsKey(name);
    }

    /**
     * Get whether there is a permission state for any of the permissions.
     *
     * @deprecated This used to be named hasRequestedPermission() and its usage is confusing
     */
    @Deprecated
    public boolean hasPermissionState(@NonNull ArraySet<String> names) {
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

    /**
     * Gets the state for a permission or null if none.
     *
     * @param name the permission name
     * @return the permission state
     */
    @Nullable
    public PermissionState getPermissionState(@NonNull String name) {
        if (mPermissions == null) {
            return null;
        }
        return mPermissions.get(name);
    }

    @NonNull
    private PermissionState getOrCreatePermissionState(@NonNull Permission permission) {
        if (mPermissions == null) {
            mPermissions = new ArrayMap<>();
        }
        final String name = permission.getName();
        PermissionState permissionState = mPermissions.get(name);
        if (permissionState == null) {
            permissionState = new PermissionState(permission);
            mPermissions.put(name, permissionState);
        }
        return permissionState;
    }

    /**
     * Get all permission states.
     *
     * @return the permission states
     */
    @NonNull
    public List<PermissionState> getPermissionStates() {
        if (mPermissions == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(mPermissions.values());
    }

    /**
     * Put a permission state.
     *
     * @param permission the permission
     * @param granted whether the permission is granted
     * @param flags the permission flags
     */
    public void putPermissionState(@NonNull Permission permission, boolean granted, int flags) {
        final String name = permission.getName();
        if (mPermissions == null) {
            mPermissions = new ArrayMap<>();
        } else {
            mPermissions.remove(name);
        }
        final PermissionState permissionState = new PermissionState(permission);
        if (granted) {
            permissionState.grant();
        }
        permissionState.updateFlags(flags, flags);
        mPermissions.put(name, permissionState);
    }

    /**
     * Remove a permission state.
     *
     * @param name the permission name
     * @return whether the permission state changed
     */
    public boolean removePermissionState(@NonNull String name) {
        if (mPermissions == null) {
            return false;
        }
        final boolean changed = mPermissions.remove(name) != null;
        if (changed && mPermissions.isEmpty()) {
            mPermissions = null;
        }
        return changed;
    }

    /**
     * Get whether a permission is granted.
     *
     * @param name the permission name
     * @return whether the permission is granted
     */
    public boolean isPermissionGranted(@NonNull String name) {
        final PermissionState permissionState = getPermissionState(name);
        return permissionState != null && permissionState.isGranted();
    }

    /**
     * Get all the granted permissions.
     *
     * @return the granted permissions
     */
    @NonNull
    public Set<String> getGrantedPermissions() {
        if (mPermissions == null) {
            return Collections.emptySet();
        }

        final Set<String> permissions = new ArraySet<>(mPermissions.size());
        final int permissionsSize = mPermissions.size();
        for (int i = 0; i < permissionsSize; i++) {
            final PermissionState permissionState = mPermissions.valueAt(i);

            if (permissionState.isGranted()) {
                permissions.add(permissionState.getName());
            }
        }
        return permissions;
    }

    /**
     * Grant a permission.
     *
     * @param permission the permission to grant
     * @return whether the permission grant state changed
     */
    public boolean grantPermission(@NonNull Permission permission) {
        final PermissionState permissionState = getOrCreatePermissionState(permission);
        return permissionState.grant();
    }

    /**
     * Revoke a permission.
     *
     * @param permission the permission to revoke
     * @return whether the permission grant state changed
     */
    public boolean revokePermission(@NonNull Permission permission) {
        final String name = permission.getName();
        final PermissionState permissionState = getPermissionState(name);
        if (permissionState == null) {
            return false;
        }
        final boolean changed = permissionState.revoke();
        if (changed && permissionState.isDefault()) {
            removePermissionState(name);
        }
        return changed;
    }

    /**
     * Get the flags for a permission.
     *
     * @param name the permission name
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
    public boolean updatePermissionFlags(@NonNull Permission permission, int flagMask,
            int flagValues) {
        if (flagMask == 0) {
            return false;
        }
        final PermissionState permissionState = getOrCreatePermissionState(permission);
        final boolean changed = permissionState.updateFlags(flagMask, flagValues);
        if (changed && permissionState.isDefault()) {
            removePermissionState(permission.getName());
        }
        return changed;
    }

    public boolean updatePermissionFlagsForAllPermissions(int flagMask, int flagValues) {
        if (flagMask == 0) {
            return false;
        }
        if (mPermissions == null) {
            return false;
        }
        boolean anyChanged = false;
        for (int i = mPermissions.size() - 1; i >= 0; i--) {
            final PermissionState permissionState = mPermissions.valueAt(i);
            final boolean changed = permissionState.updateFlags(flagMask, flagValues);
            if (changed && permissionState.isDefault()) {
                mPermissions.removeAt(i);
            }
            anyChanged |= changed;
        }
        return anyChanged;
    }

    public boolean isPermissionsReviewRequired() {
        if (mPermissions == null) {
            return false;
        }
        final int permissionsSize = mPermissions.size();
        for (int i = 0; i < permissionsSize; i++) {
            final PermissionState permission = mPermissions.valueAt(i);
            if ((permission.getFlags() & PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED) != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Compute the Linux GIDs from the permissions granted to a user.
     *
     * @param userId the user ID
     * @return the GIDs for the user
     */
    @NonNull
    public int[] computeGids(@NonNull int[] globalGids, @UserIdInt int userId) {
        IntArray gids = IntArray.wrap(globalGids);
        if (mPermissions == null) {
            return gids.toArray();
        }
        final int permissionsSize = mPermissions.size();
        for (int i = 0; i < permissionsSize; i++) {
            PermissionState permissionState = mPermissions.valueAt(i);
            if (!permissionState.isGranted()) {
                continue;
            }
            final int[] permissionGids = permissionState.computeGids(userId);
            if (permissionGids.length != 0) {
                gids.addAll(permissionGids);
            }
        }
        return gids.toArray();
    }

    static void invalidateCache() {
        PackageManager.invalidatePackageInfoCache();
    }
}
