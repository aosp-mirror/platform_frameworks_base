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
import android.util.ArrayMap;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;

/**
 * Legacy permission state that was associated with packages or shared users.
 */
//@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
public final class LegacyPermissionState {
    // Maps from user IDs to user states.
    @NonNull
    private final SparseArray<UserState> mUserStates = new SparseArray<>();

    // Keyed by user IDs.
    @NonNull
    private final SparseBooleanArray mMissing = new SparseBooleanArray();

    /**
     * Copy from another permission state.
     *
     * @param other the other permission state.
     *
     * @hide
     */
    public void copyFrom(@NonNull LegacyPermissionState other) {
        if (other == this) {
            return;
        }

        mUserStates.clear();
        final int userStatesSize = other.mUserStates.size();
        for (int i = 0; i < userStatesSize; i++) {
            mUserStates.put(other.mUserStates.keyAt(i),
                    new UserState(other.mUserStates.valueAt(i)));
        }

        mMissing.clear();
        final int missingSize = other.mMissing.size();
        for (int i = 0; i < missingSize; i++) {
            mMissing.put(other.mMissing.keyAt(i), other.mMissing.valueAt(i));
        }
    }

    /**
     * Reset this permission state.
     *
     * @hide
     */
    public void reset() {
        mUserStates.clear();
        mMissing.clear();
    }

    @Override
    public boolean equals(@Nullable Object object) {
        if (this == object) {
            return true;
        }
        if (object == null) {
            return false;
        }
        if (getClass() != object.getClass()) {
            return false;
        }
        final LegacyPermissionState other = (LegacyPermissionState) object;
        // Hand-code equals() for mUserStates, since SparseArray only has the
        // default equals() method.
        final int userStatesSize = mUserStates.size();
        if (userStatesSize != other.mUserStates.size()) {
            return false;
        }
        for (int i = 0; i < userStatesSize; i++) {
            final int userId = mUserStates.keyAt(i);
            if (!Objects.equals(mUserStates.get(userId), other.mUserStates.get(userId))) {
                return false;
            }
        }
        return Objects.equals(mMissing, other.mMissing);
    }

    /**
     * Get the permission state for a permission and a user.
     *
     * @param permissionName the permission name
     * @param userId the user ID
     * @return the permission state
     *
     * @hide
     */
    @Nullable
    public PermissionState getPermissionState(@NonNull String permissionName,
            @UserIdInt int userId) {
        checkUserId(userId);
        UserState userState = mUserStates.get(userId);
        if (userState == null) {
            return null;
        }
        return userState.getPermissionState(permissionName);
    }

    /**
     * Put a permission state for a user.
     *
     * @param permissionState the permission state
     * @param userId the user ID
     */
    public void putPermissionState(@NonNull PermissionState permissionState,
            @UserIdInt int userId) {
        checkUserId(userId);
        UserState userState = mUserStates.get(userId);
        if (userState == null) {
            userState = new UserState();
            mUserStates.put(userId, userState);
        }
        userState.putPermissionState(permissionState);
    }

    /**
     * Check whether there are any permission states for the given permissions.
     *
     * @param permissionNames the permission names
     * @return whether there are any permission states
     *
     * @hide
     */
    public boolean hasPermissionState(@NonNull Collection<String> permissionNames) {
        final int userStatesSize = mUserStates.size();
        for (int i = 0; i < userStatesSize; i++) {
            final UserState userState = mUserStates.valueAt(i);
            for (final String permissionName : permissionNames) {
                if (userState.getPermissionState(permissionName) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Get all the permission states for a user.
     *
     * @param userId the user ID
     * @return the permission states
     */
    @NonNull
    public Collection<PermissionState> getPermissionStates(@UserIdInt int userId) {
        checkUserId(userId);
        final UserState userState = mUserStates.get(userId);
        if (userState == null) {
            return Collections.emptyList();
        }
        return userState.getPermissionStates();
    }

    /**
     * Check whether the permission state is missing for a user.
     * <p>
     * This can happen if permission state is rolled back and we'll need to generate a reasonable
     * default state to keep the app usable.
     *
     * @param userId the user ID
     * @return whether the permission state is missing
     */
    public boolean isMissing(@UserIdInt int userId) {
        checkUserId(userId);
        return mMissing.get(userId);
    }

    /**
     * Set whether the permission state is missing for a user.
     * <p>
     * This can happen if permission state is rolled back and we'll need to generate a reasonable
     * default state to keep the app usable.
     *
     * @param missing whether the permission state is missing
     * @param userId the user ID
     */
    public void setMissing(boolean missing, @UserIdInt int userId) {
        checkUserId(userId);
        if (missing) {
            mMissing.put(userId, true);
        } else {
            mMissing.delete(userId);
        }
    }

    private static void checkUserId(@UserIdInt int userId) {
        if (userId < 0) {
            throw new IllegalArgumentException("Invalid user ID " + userId);
        }
    }

    /**
     * Legacy state for permissions for a user.
     */
    private static final class UserState {
        // Maps from permission names to permission states.
        @NonNull
        private final ArrayMap<String, PermissionState> mPermissionStates = new ArrayMap<>();

        public UserState() {}

        public UserState(@NonNull UserState other) {
            final int permissionStatesSize = other.mPermissionStates.size();
            for (int i = 0; i < permissionStatesSize; i++) {
                mPermissionStates.put(other.mPermissionStates.keyAt(i),
                        new PermissionState(other.mPermissionStates.valueAt(i)));
            }
        }

        @Nullable
        public PermissionState getPermissionState(@NonNull String permissionName) {
            return mPermissionStates.get(permissionName);
        }

        public void putPermissionState(@NonNull PermissionState permissionState) {
            mPermissionStates.put(permissionState.getName(), permissionState);
        }

        @NonNull
        public Collection<PermissionState> getPermissionStates() {
            return Collections.unmodifiableCollection(mPermissionStates.values());
        }
    }

    /**
     * Legacy state for a single permission.
     */
    public static final class PermissionState {
        @NonNull
        private final String mName;

        private final boolean mRuntime;

        private final boolean mGranted;

        private final int mFlags;

        /**
         * Create a new instance of this class.
         *
         * @param name the name of the permission
         * @param runtime whether the permission is runtime
         * @param granted whether the permission is granted
         * @param flags the permission flags
         */
        public PermissionState(@NonNull String name, boolean runtime, boolean granted, int flags) {
            mName = name;
            mRuntime = runtime;
            mGranted = granted;
            mFlags = flags;
        }

        private PermissionState(@NonNull PermissionState other) {
            mName = other.mName;
            mRuntime = other.mRuntime;
            mGranted = other.mGranted;
            mFlags = other.mFlags;
        }

        /**
         * Get the permission name.
         *
         * @return the permission name
         */
        @NonNull
        public String getName() {
            return mName;
        }

        /**
         * Get whether the permission is a runtime permission.
         *
         * @return whether the permission is a runtime permission.
         */
        public boolean isRuntime() {
            return mRuntime;
        }

        /**
         * Get whether the permission is granted.
         *
         * @return whether the permission is granted
         */
        @NonNull
        public boolean isGranted() {
            return mGranted;
        }

        /**
         * Get the permission flags.
         *
         * @return the permission flags
         */
        @NonNull
        public int getFlags() {
            return mFlags;
        }

        @Override
        public boolean equals(@Nullable Object object) {
            if (this == object) {
                return true;
            }
            if (object == null || getClass() != object.getClass()) {
                return false;
            }
            PermissionState that = (PermissionState) object;
            return mRuntime == that.mRuntime
                    && mGranted == that.mGranted
                    && mFlags == that.mFlags
                    && Objects.equals(mName, that.mName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mName, mRuntime, mGranted, mFlags);
        }
    }
}
