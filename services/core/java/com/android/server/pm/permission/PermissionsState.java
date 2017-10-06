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

import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;

import android.util.SparseArray;
import android.util.SparseBooleanArray;
import com.android.internal.util.ArrayUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * This class encapsulates the permissions for a package or a shared user.
 * <p>
 * There are two types of permissions: install (granted at installation)
 * and runtime (granted at runtime). Install permissions are granted to
 * all device users while runtime permissions are granted explicitly to
 * specific users.
 * </p>
 * <p>
 * The permissions are kept on a per device user basis. For example, an
 * application may have some runtime permissions granted under the device
 * owner but not granted under the secondary user.
 * <p>
 * This class is also responsible for keeping track of the Linux gids per
 * user for a package or a shared user. The gids are computed as a set of
 * the gids for all granted permissions' gids on a per user basis.
 * </p>
 */
public final class PermissionsState {

    /** The permission operation failed. */
    public static final int PERMISSION_OPERATION_FAILURE = -1;

    /** The permission operation succeeded and no gids changed. */
    public static final int PERMISSION_OPERATION_SUCCESS = 0;

    /** The permission operation succeeded and gids changed. */
    public static final int PERMISSION_OPERATION_SUCCESS_GIDS_CHANGED = 1;

    private static final int[] NO_GIDS = {};

    private ArrayMap<String, PermissionData> mPermissions;

    private int[] mGlobalGids = NO_GIDS;

    private SparseBooleanArray mPermissionReviewRequired;

    public PermissionsState() {
        /* do nothing */
    }

    public PermissionsState(PermissionsState prototype) {
        copyFrom(prototype);
    }

    /**
     * Sets the global gids, applicable to all users.
     *
     * @param globalGids The global gids.
     */
    public void setGlobalGids(int[] globalGids) {
        if (!ArrayUtils.isEmpty(globalGids)) {
            mGlobalGids = Arrays.copyOf(globalGids, globalGids.length);
        }
    }

    /**
     * Initialized this instance from another one.
     *
     * @param other The other instance.
     */
    public void copyFrom(PermissionsState other) {
        if (other == this) {
            return;
        }
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
                PermissionData permissionData = other.mPermissions.valueAt(i);
                mPermissions.put(name, new PermissionData(permissionData));
            }
        }

        mGlobalGids = NO_GIDS;
        if (other.mGlobalGids != NO_GIDS) {
            mGlobalGids = Arrays.copyOf(other.mGlobalGids,
                    other.mGlobalGids.length);
        }

        if (mPermissionReviewRequired != null) {
            if (other.mPermissionReviewRequired == null) {
                mPermissionReviewRequired = null;
            } else {
                mPermissionReviewRequired.clear();
            }
        }
        if (other.mPermissionReviewRequired != null) {
            if (mPermissionReviewRequired == null) {
                mPermissionReviewRequired = new SparseBooleanArray();
            }
            final int userCount = other.mPermissionReviewRequired.size();
            for (int i = 0; i < userCount; i++) {
                final boolean reviewRequired = other.mPermissionReviewRequired.valueAt(i);
                mPermissionReviewRequired.put(i, reviewRequired);
            }
        }
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
        final PermissionsState other = (PermissionsState) obj;

        if (mPermissions == null) {
            if (other.mPermissions != null) {
                return false;
            }
        } else if (!mPermissions.equals(other.mPermissions)) {
            return false;
        }
        if (mPermissionReviewRequired == null) {
            if (other.mPermissionReviewRequired != null) {
                return false;
            }
        } else if (!mPermissionReviewRequired.equals(other.mPermissionReviewRequired)) {
            return false;
        }
        return Arrays.equals(mGlobalGids, other.mGlobalGids);
    }

    public boolean isPermissionReviewRequired(int userId) {
        return mPermissionReviewRequired != null && mPermissionReviewRequired.get(userId);
    }

    /**
     * Grant an install permission.
     *
     * @param permission The permission to grant.
     * @return The operation result which is either {@link #PERMISSION_OPERATION_SUCCESS},
     *     or {@link #PERMISSION_OPERATION_SUCCESS_GIDS_CHANGED}, or {@link
     *     #PERMISSION_OPERATION_FAILURE}.
     */
    public int grantInstallPermission(BasePermission permission) {
        return grantPermission(permission, UserHandle.USER_ALL);
    }

    /**
     * Revoke an install permission.
     *
     * @param permission The permission to revoke.
     * @return The operation result which is either {@link #PERMISSION_OPERATION_SUCCESS},
     *     or {@link #PERMISSION_OPERATION_SUCCESS_GIDS_CHANGED}, or {@link
     *     #PERMISSION_OPERATION_FAILURE}.
     */
    public int revokeInstallPermission(BasePermission permission) {
        return revokePermission(permission, UserHandle.USER_ALL);
    }

    /**
     * Grant a runtime permission for a given device user.
     *
     * @param permission The permission to grant.
     * @param userId The device user id.
     * @return The operation result which is either {@link #PERMISSION_OPERATION_SUCCESS},
     *     or {@link #PERMISSION_OPERATION_SUCCESS_GIDS_CHANGED}, or {@link
     *     #PERMISSION_OPERATION_FAILURE}.
     */
    public int grantRuntimePermission(BasePermission permission, int userId) {
        enforceValidUserId(userId);
        if (userId == UserHandle.USER_ALL) {
            return PERMISSION_OPERATION_FAILURE;
        }
        return grantPermission(permission, userId);
    }

    /**
     *  Revoke a runtime permission for a given device user.
     *
     * @param permission The permission to revoke.
     * @param userId The device user id.
     * @return The operation result which is either {@link #PERMISSION_OPERATION_SUCCESS},
     *     or {@link #PERMISSION_OPERATION_SUCCESS_GIDS_CHANGED}, or {@link
     *     #PERMISSION_OPERATION_FAILURE}.
     */
    public int revokeRuntimePermission(BasePermission permission, int userId) {
        enforceValidUserId(userId);
        if (userId == UserHandle.USER_ALL) {
            return PERMISSION_OPERATION_FAILURE;
        }
        return revokePermission(permission, userId);
    }

    /**
     * Gets whether this state has a given runtime permission for a
     * given device user id.
     *
     * @param name The permission name.
     * @param userId The device user id.
     * @return Whether this state has the permission.
     */
    public boolean hasRuntimePermission(String name, int userId) {
        enforceValidUserId(userId);
        return !hasInstallPermission(name) && hasPermission(name, userId);
    }

    /**
     * Gets whether this state has a given install permission.
     *
     * @param name The permission name.
     * @return Whether this state has the permission.
     */
    public boolean hasInstallPermission(String name) {
        return hasPermission(name, UserHandle.USER_ALL);
    }

    /**
     * Gets whether the state has a given permission for the specified
     * user, regardless if this is an install or a runtime permission.
     *
     * @param name The permission name.
     * @param userId The device user id.
     * @return Whether the user has the permission.
     */
    public boolean hasPermission(String name, int userId) {
        enforceValidUserId(userId);

        if (mPermissions == null) {
            return false;
        }

        PermissionData permissionData = mPermissions.get(name);
        return permissionData != null && permissionData.isGranted(userId);
    }

    /**
     * Returns whether the state has any known request for the given permission name,
     * whether or not it has been granted.
     */
    public boolean hasRequestedPermission(ArraySet<String> names) {
        if (mPermissions == null) {
            return false;
        }
        for (int i=names.size()-1; i>=0; i--) {
            if (mPermissions.get(names.valueAt(i)) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gets all permissions for a given device user id regardless if they
     * are install time or runtime permissions.
     *
     * @param userId The device user id.
     * @return The permissions or an empty set.
     */
    public Set<String> getPermissions(int userId) {
        enforceValidUserId(userId);

        if (mPermissions == null) {
            return Collections.emptySet();
        }

        Set<String> permissions = new ArraySet<>(mPermissions.size());

        final int permissionCount = mPermissions.size();
        for (int i = 0; i < permissionCount; i++) {
            String permission = mPermissions.keyAt(i);

            if (hasInstallPermission(permission)) {
                permissions.add(permission);
                continue;
            }

            if (userId != UserHandle.USER_ALL) {
                if (hasRuntimePermission(permission, userId)) {
                    permissions.add(permission);
                }
            }
        }

        return permissions;
    }

    /**
     * Gets the state for an install permission or null if no such.
     *
     * @param name The permission name.
     * @return The permission state.
     */
    public PermissionState getInstallPermissionState(String name) {
        return getPermissionState(name, UserHandle.USER_ALL);
    }

    /**
     * Gets the state for a runtime permission or null if no such.
     *
     * @param name The permission name.
     * @param userId The device user id.
     * @return The permission state.
     */
    public PermissionState getRuntimePermissionState(String name, int userId) {
        enforceValidUserId(userId);
        return getPermissionState(name, userId);
    }

    /**
     * Gets all install permission states.
     *
     * @return The permission states or an empty set.
     */
    public List<PermissionState> getInstallPermissionStates() {
        return getPermissionStatesInternal(UserHandle.USER_ALL);
    }

    /**
     * Gets all runtime permission states.
     *
     * @return The permission states or an empty set.
     */
    public List<PermissionState> getRuntimePermissionStates(int userId) {
        enforceValidUserId(userId);
        return getPermissionStatesInternal(userId);
    }

    /**
     * Gets the flags for a permission regardless if it is install or
     * runtime permission.
     *
     * @param name The permission name.
     * @return The permission state or null if no such.
     */
    public int getPermissionFlags(String name, int userId) {
        PermissionState installPermState = getInstallPermissionState(name);
        if (installPermState != null) {
            return installPermState.getFlags();
        }
        PermissionState runtimePermState = getRuntimePermissionState(name, userId);
        if (runtimePermState != null) {
            return runtimePermState.getFlags();
        }
        return 0;
    }

    /**
     * Update the flags associated with a given permission.
     * @param permission The permission whose flags to update.
     * @param userId The user for which to update.
     * @param flagMask Mask for which flags to change.
     * @param flagValues New values for the mask flags.
     * @return Whether the permission flags changed.
     */
    public boolean updatePermissionFlags(BasePermission permission, int userId,
            int flagMask, int flagValues) {
        enforceValidUserId(userId);

        final boolean mayChangeFlags = flagValues != 0 || flagMask != 0;

        if (mPermissions == null) {
            if (!mayChangeFlags) {
                return false;
            }
            ensurePermissionData(permission);
        }

        PermissionData permissionData = mPermissions.get(permission.getName());
        if (permissionData == null) {
            if (!mayChangeFlags) {
                return false;
            }
            permissionData = ensurePermissionData(permission);
        }

        final int oldFlags = permissionData.getFlags(userId);

        final boolean updated = permissionData.updateFlags(userId, flagMask, flagValues);
        if (updated) {
            final int newFlags = permissionData.getFlags(userId);
            if ((oldFlags & PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED) == 0
                    && (newFlags & PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED) != 0) {
                if (mPermissionReviewRequired == null) {
                    mPermissionReviewRequired = new SparseBooleanArray();
                }
                mPermissionReviewRequired.put(userId, true);
            } else if ((oldFlags & PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED) != 0
                    && (newFlags & PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED) == 0) {
                if (mPermissionReviewRequired != null && !hasPermissionRequiringReview(userId)) {
                    mPermissionReviewRequired.delete(userId);
                    if (mPermissionReviewRequired.size() <= 0) {
                        mPermissionReviewRequired = null;
                    }
                }
            }
        }
        return updated;
    }

    private boolean hasPermissionRequiringReview(int userId) {
        final int permissionCount = mPermissions.size();
        for (int i = 0; i < permissionCount; i++) {
            final PermissionData permission = mPermissions.valueAt(i);
            if ((permission.getFlags(userId)
                    & PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED) != 0) {
                return true;
            }
        }
        return false;
    }

    public boolean updatePermissionFlagsForAllPermissions(
            int userId, int flagMask, int flagValues) {
        enforceValidUserId(userId);

        if (mPermissions == null) {
            return false;
        }
        boolean changed = false;
        final int permissionCount = mPermissions.size();
        for (int i = 0; i < permissionCount; i++) {
            PermissionData permissionData = mPermissions.valueAt(i);
            changed |= permissionData.updateFlags(userId, flagMask, flagValues);
        }
        return changed;
    }

    /**
     * Compute the Linux gids for a given device user from the permissions
     * granted to this user. Note that these are computed to avoid additional
     * state as they are rarely accessed.
     *
     * @param userId The device user id.
     * @return The gids for the device user.
     */
    public int[] computeGids(int userId) {
        enforceValidUserId(userId);

        int[] gids = mGlobalGids;

        if (mPermissions != null) {
            final int permissionCount = mPermissions.size();
            for (int i = 0; i < permissionCount; i++) {
                String permission = mPermissions.keyAt(i);
                if (!hasPermission(permission, userId)) {
                    continue;
                }
                PermissionData permissionData = mPermissions.valueAt(i);
                final int[] permGids = permissionData.computeGids(userId);
                if (permGids != NO_GIDS) {
                    gids = appendInts(gids, permGids);
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
    public int[] computeGids(int[] userIds) {
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
        mPermissions = null;
        mPermissionReviewRequired = null;
    }

    private PermissionState getPermissionState(String name, int userId) {
        if (mPermissions == null) {
            return null;
        }
        PermissionData permissionData = mPermissions.get(name);
        if (permissionData == null) {
            return null;
        }
        return permissionData.getPermissionState(userId);
    }

    private List<PermissionState> getPermissionStatesInternal(int userId) {
        enforceValidUserId(userId);

        if (mPermissions == null) {
            return Collections.emptyList();
        }

        List<PermissionState> permissionStates = new ArrayList<>();

        final int permissionCount = mPermissions.size();
        for (int i = 0; i < permissionCount; i++) {
            PermissionData permissionData = mPermissions.valueAt(i);

            PermissionState permissionState = permissionData.getPermissionState(userId);
            if (permissionState != null) {
                permissionStates.add(permissionState);
            }
        }

        return permissionStates;
    }

    private int grantPermission(BasePermission permission, int userId) {
        if (hasPermission(permission.getName(), userId)) {
            return PERMISSION_OPERATION_FAILURE;
        }

        final boolean hasGids = !ArrayUtils.isEmpty(permission.computeGids(userId));
        final int[] oldGids = hasGids ? computeGids(userId) : NO_GIDS;

        PermissionData permissionData = ensurePermissionData(permission);

        if (!permissionData.grant(userId)) {
            return PERMISSION_OPERATION_FAILURE;
        }

        if (hasGids) {
            final int[] newGids = computeGids(userId);
            if (oldGids.length != newGids.length) {
                return PERMISSION_OPERATION_SUCCESS_GIDS_CHANGED;
            }
        }

        return PERMISSION_OPERATION_SUCCESS;
    }

    private int revokePermission(BasePermission permission, int userId) {
        final String permName = permission.getName();
        if (!hasPermission(permName, userId)) {
            return PERMISSION_OPERATION_FAILURE;
        }

        final boolean hasGids = !ArrayUtils.isEmpty(permission.computeGids(userId));
        final int[] oldGids = hasGids ? computeGids(userId) : NO_GIDS;

        PermissionData permissionData = mPermissions.get(permName);

        if (!permissionData.revoke(userId)) {
            return PERMISSION_OPERATION_FAILURE;
        }

        if (permissionData.isDefault()) {
            ensureNoPermissionData(permName);
        }

        if (hasGids) {
            final int[] newGids = computeGids(userId);
            if (oldGids.length != newGids.length) {
                return PERMISSION_OPERATION_SUCCESS_GIDS_CHANGED;
            }
        }

        return PERMISSION_OPERATION_SUCCESS;
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

    private static void enforceValidUserId(int userId) {
        if (userId != UserHandle.USER_ALL && userId < 0) {
            throw new IllegalArgumentException("Invalid userId:" + userId);
        }
    }

    private PermissionData ensurePermissionData(BasePermission permission) {
        final String permName = permission.getName();
        if (mPermissions == null) {
            mPermissions = new ArrayMap<>();
        }
        PermissionData permissionData = mPermissions.get(permName);
        if (permissionData == null) {
            permissionData = new PermissionData(permission);
            mPermissions.put(permName, permissionData);
        }
        return permissionData;
    }

    private void ensureNoPermissionData(String name) {
        if (mPermissions == null) {
            return;
        }
        mPermissions.remove(name);
        if (mPermissions.isEmpty()) {
            mPermissions = null;
        }
    }

    private static final class PermissionData {
        private final BasePermission mPerm;
        private SparseArray<PermissionState> mUserStates = new SparseArray<>();

        public PermissionData(BasePermission perm) {
            mPerm = perm;
        }

        public PermissionData(PermissionData other) {
            this(other.mPerm);
            final int otherStateCount = other.mUserStates.size();
            for (int i = 0; i < otherStateCount; i++) {
                final int otherUserId = other.mUserStates.keyAt(i);
                PermissionState otherState = other.mUserStates.valueAt(i);
                mUserStates.put(otherUserId, new PermissionState(otherState));
            }
        }

        public int[] computeGids(int userId) {
            return mPerm.computeGids(userId);
        }

        public boolean isGranted(int userId) {
            if (isInstallPermission()) {
                userId = UserHandle.USER_ALL;
            }

            PermissionState userState = mUserStates.get(userId);
            if (userState == null) {
                return false;
            }

            return userState.mGranted;
        }

        public boolean grant(int userId) {
            if (!isCompatibleUserId(userId)) {
                return false;
            }

            if (isGranted(userId)) {
                return false;
            }

            PermissionState userState = mUserStates.get(userId);
            if (userState == null) {
                userState = new PermissionState(mPerm.getName());
                mUserStates.put(userId, userState);
            }

            userState.mGranted = true;

            return true;
        }

        public boolean revoke(int userId) {
            if (!isCompatibleUserId(userId)) {
                return false;
            }

            if (!isGranted(userId)) {
                return false;
            }

            PermissionState userState = mUserStates.get(userId);
            userState.mGranted = false;

            if (userState.isDefault()) {
                mUserStates.remove(userId);
            }

            return true;
        }

        public PermissionState getPermissionState(int userId) {
            return mUserStates.get(userId);
        }

        public int getFlags(int userId) {
            PermissionState userState = mUserStates.get(userId);
            if (userState != null) {
                return userState.mFlags;
            }
            return 0;
        }

        public boolean isDefault() {
            return mUserStates.size() <= 0;
        }

        public static boolean isInstallPermissionKey(int userId) {
            return userId == UserHandle.USER_ALL;
        }

        public boolean updateFlags(int userId, int flagMask, int flagValues) {
            if (isInstallPermission()) {
                userId = UserHandle.USER_ALL;
            }

            if (!isCompatibleUserId(userId)) {
                return false;
            }

            final int newFlags = flagValues & flagMask;

            PermissionState userState = mUserStates.get(userId);
            if (userState != null) {
                final int oldFlags = userState.mFlags;
                userState.mFlags = (userState.mFlags & ~flagMask) | newFlags;
                if (userState.isDefault()) {
                    mUserStates.remove(userId);
                }
                return userState.mFlags != oldFlags;
            } else if (newFlags != 0) {
                userState = new PermissionState(mPerm.getName());
                userState.mFlags = newFlags;
                mUserStates.put(userId, userState);
                return true;
            }

            return false;
        }

        private boolean isCompatibleUserId(int userId) {
            return isDefault() || !(isInstallPermission() ^ isInstallPermissionKey(userId));
        }

        private boolean isInstallPermission() {
            return mUserStates.size() == 1
                    && mUserStates.get(UserHandle.USER_ALL) != null;
        }
    }

    public static final class PermissionState {
        private final String mName;
        private boolean mGranted;
        private int mFlags;

        public PermissionState(String name) {
            mName = name;
        }

        public PermissionState(PermissionState other) {
            mName = other.mName;
            mGranted = other.mGranted;
            mFlags = other.mFlags;
        }

        public boolean isDefault() {
            return !mGranted && mFlags == 0;
        }

        public String getName() {
            return mName;
        }

        public boolean isGranted() {
            return mGranted;
        }

        public int getFlags() {
            return mFlags;
        }
    }
}
