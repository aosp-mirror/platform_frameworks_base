/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.permission.persistence;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.SystemApi.Client;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * State of all runtime permissions.
 *
 * TODO(b/147914847): Remove @hide when it becomes the default.
 * @hide
 */
@SystemApi(client = Client.SYSTEM_SERVER)
public final class RuntimePermissionsState {

    /**
     * Special value for {@link #mVersion} to indicate that no version was read.
     */
    public static final int NO_VERSION = -1;

    /**
     * The version of the runtime permissions.
     */
    private final int mVersion;

    /**
     * The fingerprint of the runtime permissions.
     */
    @Nullable
    private final String mFingerprint;

    /**
     * The runtime permissions by packages.
     */
    @NonNull
    private final Map<String, List<PermissionState>> mPackagePermissions;

    /**
     * The runtime permissions by shared users.
     */
    @NonNull
    private final Map<String, List<PermissionState>> mSharedUserPermissions;

    /**
     * Create a new instance of this class.
     *
     * @param version the version of the runtime permissions
     * @param fingerprint the fingerprint of the runtime permissions
     * @param packagePermissions the runtime permissions by packages
     * @param sharedUserPermissions the runtime permissions by shared users
     */
    public RuntimePermissionsState(int version, @Nullable String fingerprint,
            @NonNull Map<String, List<PermissionState>> packagePermissions,
            @NonNull Map<String, List<PermissionState>> sharedUserPermissions) {
        mVersion = version;
        mFingerprint = fingerprint;
        mPackagePermissions = packagePermissions;
        mSharedUserPermissions = sharedUserPermissions;
    }

    /**
     * Get the version of the runtime permissions.
     *
     * @return the version of the runtime permissions
     */
    public int getVersion() {
        return mVersion;
    }

    /**
     * Get the fingerprint of the runtime permissions.
     *
     * @return the fingerprint of the runtime permissions
     */
    @Nullable
    public String getFingerprint() {
        return mFingerprint;
    }

    /**
     * Get the runtime permissions by packages.
     *
     * @return the runtime permissions by packages
     */
    @NonNull
    public Map<String, List<PermissionState>> getPackagePermissions() {
        return mPackagePermissions;
    }

    /**
     * Get the runtime permissions by shared users.
     *
     * @return the runtime permissions by shared users
     */
    @NonNull
    public Map<String, List<PermissionState>> getSharedUserPermissions() {
        return mSharedUserPermissions;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        RuntimePermissionsState that = (RuntimePermissionsState) object;
        return mVersion == that.mVersion
                && Objects.equals(mFingerprint, that.mFingerprint)
                && Objects.equals(mPackagePermissions, that.mPackagePermissions)
                && Objects.equals(mSharedUserPermissions, that.mSharedUserPermissions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mVersion, mFingerprint, mPackagePermissions, mSharedUserPermissions);
    }

    /**
     * State of a single permission.
     */
    public static final class PermissionState {

        /**
         * The name of the permission.
         */
        @NonNull
        private final String mName;

        /**
         * Whether the permission is granted.
         */
        private final boolean mGranted;

        /**
         * The flags of the permission.
         */
        private final int mFlags;

        /**
         * Create a new instance of this class.
         *
         * @param name the name of the permission
         * @param granted whether the permission is granted
         * @param flags the flags of the permission
         */
        public PermissionState(@NonNull String name, boolean granted, int flags) {
            mName = name;
            mGranted = granted;
            mFlags = flags;
        }

        /**
         * Get the name of the permission.
         *
         * @return the name of the permission
         */
        @NonNull
        public String getName() {
            return mName;
        }

        /**
         * Get whether the permission is granted.
         *
         * @return whether the permission is granted
         */
        public boolean isGranted() {
            return mGranted;
        }

        /**
         * Get the flags of the permission.
         *
         * @return the flags of the permission
         */
        public int getFlags() {
            return mFlags;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (object == null || getClass() != object.getClass()) {
                return false;
            }
            PermissionState that = (PermissionState) object;
            return mGranted == that.mGranted
                    && mFlags == that.mFlags
                    && Objects.equals(mName, that.mName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mName, mGranted, mFlags);
        }
    }
}
