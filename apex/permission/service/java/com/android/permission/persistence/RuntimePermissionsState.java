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

    public RuntimePermissionsState(int version, @Nullable String fingerprint,
            @NonNull Map<String, List<PermissionState>> packagePermissions,
            @NonNull Map<String, List<PermissionState>> sharedUserPermissions) {
        mVersion = version;
        mFingerprint = fingerprint;
        mPackagePermissions = packagePermissions;
        mSharedUserPermissions = sharedUserPermissions;
    }

    public int getVersion() {
        return mVersion;
    }

    @Nullable
    public String getFingerprint() {
        return mFingerprint;
    }

    @NonNull
    public Map<String, List<PermissionState>> getPackagePermissions() {
        return mPackagePermissions;
    }

    @NonNull
    public Map<String, List<PermissionState>> getSharedUserPermissions() {
        return mSharedUserPermissions;
    }

    /**
     * State of a single permission.
     */
    public static class PermissionState {

        /**
         * Name of the permission.
         */
        @NonNull
        private final String mName;

        /**
         * Whether the permission is granted.
         */
        private final boolean mGranted;

        /**
         * Flags of the permission.
         */
        private final int mFlags;

        public PermissionState(@NonNull String name, boolean granted, int flags) {
            mName = name;
            mGranted = granted;
            mFlags = flags;
        }

        @NonNull
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
