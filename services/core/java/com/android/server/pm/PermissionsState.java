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

package com.android.server.pm;

import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.util.ArrayUtils;

import java.util.Arrays;
import java.util.Collections;
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

    /** The permission operation succeeded and no gids changed. */
    public static final int PERMISSION_OPERATION_SUCCESS = 1;

    /** The permission operation succeeded and gids changed. */
    public static final int PERMISSION_OPERATION_SUCCESS_GIDS_CHANGED = 2;

    /** The permission operation failed. */
    public static final int PERMISSION_OPERATION_FAILURE = 3;

    public static final int[] USERS_ALL = {UserHandle.USER_ALL};

    public static final int[] USERS_NONE = {};

    private static final int[] NO_GIDS = {};

    private ArrayMap<String, PermissionData> mPermissions;

    private int[] mGlobalGids = NO_GIDS;

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
     * Grant a runtime permission.
     *
     * @param permission The permission to grant.
     * @return The operation result which is either {@link #PERMISSION_OPERATION_SUCCESS},
     *     or {@link #PERMISSION_OPERATION_SUCCESS_GIDS_CHANGED}, or {@link
     *     #PERMISSION_OPERATION_FAILURE}.
     */
    public int grantRuntimePermission(BasePermission permission, int userId) {
        if (userId == UserHandle.USER_ALL) {
            return PERMISSION_OPERATION_FAILURE;
        }
        return grantPermission(permission, userId);
    }

    /**
     * Revoke a runtime permission for a given device user.
     *
     * @param permission The permission to revoke.
     * @param userId The device user id.
     * @return The operation result which is either {@link #PERMISSION_OPERATION_SUCCESS},
     *     or {@link #PERMISSION_OPERATION_SUCCESS_GIDS_CHANGED}, or {@link
     *     #PERMISSION_OPERATION_FAILURE}.
     */
    public int revokeRuntimePermission(BasePermission permission, int userId) {
        if (userId == UserHandle.USER_ALL) {
            return PERMISSION_OPERATION_FAILURE;
        }
        return revokePermission(permission, userId);
    }

    /**
     * Gets whether this state has a given permission, regardless if
     * it is install time or runtime one.
     *
     * @param name The permission name.
     * @return Whether this state has the permission.
     */
    public boolean hasPermission(String name) {
        return mPermissions != null && mPermissions.get(name) != null;
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
     * Revokes a permission for all users regardless if it is an install or
     * a runtime permission.
     *
     * @param permission The permission to revoke.
     * @return The operation result which is either {@link #PERMISSION_OPERATION_SUCCESS},
     *     or {@link #PERMISSION_OPERATION_SUCCESS_GIDS_CHANGED}, or {@link
     *     #PERMISSION_OPERATION_FAILURE}.
     */
    public int revokePermission(BasePermission permission) {
        if (!hasPermission(permission.name)) {
            return PERMISSION_OPERATION_FAILURE;
        }

        int result = PERMISSION_OPERATION_SUCCESS;

        PermissionData permissionData = mPermissions.get(permission.name);
        for (int userId : permissionData.getUserIds()) {
            if (revokePermission(permission, userId)
                    == PERMISSION_OPERATION_SUCCESS_GIDS_CHANGED) {
                result = PERMISSION_OPERATION_SUCCESS_GIDS_CHANGED;
                break;
            }
        }

        mPermissions.remove(permission.name);

        return result;
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
        return permissionData != null && permissionData.hasUserId(userId);
    }

    /**
     * Gets all permissions regardless if they are install or runtime.
     *
     * @return The permissions or an empty set.
     */
    public Set<String> getPermissions() {
        if (mPermissions != null) {
            return mPermissions.keySet();
        }

        return Collections.emptySet();
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

        Set<String> permissions = new ArraySet<>();

        final int permissionCount = mPermissions.size();
        for (int i = 0; i < permissionCount; i++) {
            String permission = mPermissions.keyAt(i);
            if (userId == UserHandle.USER_ALL) {
                if (hasInstallPermission(permission)) {
                    permissions.add(permission);
                }
            } else {
                if (hasRuntimePermission(permission, userId)) {
                    permissions.add(permission);
                }
            }
        }

        return  permissions;
    }

    /**
     * Gets all runtime permissions.
     *
     * @return The permissions or an empty set.
     */
    public Set<String> getRuntimePermissions(int userId) {
        return getPermissions(userId);
    }

    /**
     * Gets all install permissions.
     *
     * @return The permissions or an empty set.
     */
    public Set<String> getInstallPermissions() {
        return getPermissions(UserHandle.USER_ALL);
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
    public int[] computeGids() {
        int[] gids = mGlobalGids;

        for (int userId : UserManagerService.getInstance().getUserIds()) {
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
    }

    private int grantPermission(BasePermission permission, int userId) {
        if (hasPermission(permission.name, userId)) {
            return PERMISSION_OPERATION_FAILURE;
        }

        final boolean hasGids = !ArrayUtils.isEmpty(permission.computeGids(userId));
        final int[] oldGids = hasGids ? computeGids(userId) : NO_GIDS;

        if (mPermissions == null) {
            mPermissions = new ArrayMap<>();
        }

        PermissionData permissionData = mPermissions.get(permission.name);
        if (permissionData == null) {
            permissionData = new PermissionData(permission);
            mPermissions.put(permission.name, permissionData);
        }

        if (!permissionData.addUserId(userId)) {
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
        if (!hasPermission(permission.name, userId)) {
            return PERMISSION_OPERATION_FAILURE;
        }

        final boolean hasGids = !ArrayUtils.isEmpty(permission.computeGids(userId));
        final int[] oldGids = hasGids ? computeGids(userId) : NO_GIDS;

        PermissionData permissionData = mPermissions.get(permission.name);

        if (!permissionData.removeUserId(userId)) {
            return PERMISSION_OPERATION_FAILURE;
        }

        if (permissionData.getUserIds() == USERS_NONE) {
            mPermissions.remove(permission.name);
        }

        if (mPermissions.isEmpty()) {
            mPermissions = null;
        }

        if (hasGids) {
            final int[] newGids = computeGids(userId);
            if (oldGids.length != newGids.length) {
                return PERMISSION_OPERATION_SUCCESS_GIDS_CHANGED;
            }
        }

        return PERMISSION_OPERATION_SUCCESS;
    }

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

    private static final class PermissionData {
        private final BasePermission mPerm;
        private int[] mUserIds = USERS_NONE;

        public PermissionData(BasePermission perm) {
            mPerm = perm;
        }

        public PermissionData(PermissionData other) {
            this(other.mPerm);

            if (other.mUserIds == USERS_ALL || other.mUserIds == USERS_NONE) {
                mUserIds = other.mUserIds;
            } else {
                mUserIds = Arrays.copyOf(other.mUserIds, other.mUserIds.length);
            }
        }

        public int[] computeGids(int userId) {
            return mPerm.computeGids(userId);
        }

        public int[] getUserIds() {
            return mUserIds;
        }

        public boolean hasUserId(int userId) {
            if (mUserIds == USERS_ALL) {
                return true;
            }

            if (userId != UserHandle.USER_ALL) {
                return ArrayUtils.contains(mUserIds, userId);
            }

            return false;
        }

        public boolean addUserId(int userId) {
            if (hasUserId(userId)) {
                return false;
            }

            if (userId == UserHandle.USER_ALL) {
                mUserIds = USERS_ALL;
                return true;
            }

            mUserIds = ArrayUtils.appendInt(mUserIds, userId);

            return true;
        }

        public boolean removeUserId(int userId) {
            if (!hasUserId(userId)) {
                return false;
            }

            if (mUserIds == USERS_ALL) {
                mUserIds = UserManagerService.getInstance().getUserIds();
            }

            mUserIds = ArrayUtils.removeInt(mUserIds, userId);

            if (mUserIds.length == 0) {
                mUserIds = USERS_NONE;
            }

            return true;
        }
    }
}
