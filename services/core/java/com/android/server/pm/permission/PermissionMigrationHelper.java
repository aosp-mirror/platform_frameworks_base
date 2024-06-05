/*
 * Copyright (C) 2023 The Android Open Source Project
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
import android.content.pm.PermissionInfo;

import java.util.Map;

/**
 * In-process api for permissions migration.
 *
 * @hide
 */
public interface PermissionMigrationHelper {
    /**
     * Whether legacy permission definitions/trees exist or not.
     */
    boolean hasLegacyPermission();

    /**
     * @return legacy permission definitions.
     */
    @NonNull
    Map<String, LegacyPermission> getLegacyPermissions();

    /**
     * @return legacy permission trees.
     */
    @NonNull
    Map<String, LegacyPermission> getLegacyPermissionTrees();

    /**
     * @return legacy permissions state for a user.
     */
    @NonNull
    Map<Integer, Map<String, LegacyPermissionState>> getLegacyPermissionStates(int userId);

    /**
     * @return permissions file version for the given user.
     */
    int getLegacyPermissionStateVersion(int userId);

    /**
     * @return true if permissions state exists or not.
     */
    boolean hasLegacyPermissionState(int userId);

    /**
     * Legacy permission definition.
     */
    final class LegacyPermission {
        private final PermissionInfo mPermissionInfo;
        private final int mType;

        LegacyPermission(PermissionInfo permissionInfo, int type) {
            mPermissionInfo = permissionInfo;
            mType = type;
        }

        @NonNull
        public PermissionInfo getPermissionInfo() {
            return mPermissionInfo;
        }

        public int getType() {
            return mType;
        }
    }

    /**
     * State of a legacy permission.
     */
    final class LegacyPermissionState {
        private final boolean mGranted;
        private final int mFlags;

        LegacyPermissionState(boolean granted, int flags) {
            mGranted = granted;
            mFlags = flags;
        }

        /**
         * @return Whether the permission is granted or not.
         */
        public boolean isGranted() {
            return mGranted;
        }

        /**
         * @return Permission flags.
         */
        public int getFlags() {
            return mFlags;
        }
    }
}
