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

package com.android.server.pm.permission;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.ArrayMap;

/**
 * Data class for OEM and privileged app permission allowlist state.
 */
public final class PermissionAllowlist {
    @NonNull
    private final ArrayMap<String, ArrayMap<String, Boolean>> mOemAppAllowlist = new ArrayMap<>();

    @NonNull
    private final ArrayMap<String, ArrayMap<String, Boolean>> mPrivilegedAppAllowlist =
            new ArrayMap<>();
    @NonNull
    private final ArrayMap<String, ArrayMap<String, Boolean>> mVendorPrivilegedAppAllowlist =
            new ArrayMap<>();
    @NonNull
    private final ArrayMap<String, ArrayMap<String, Boolean>> mProductPrivilegedAppAllowlist =
            new ArrayMap<>();
    @NonNull
    private final ArrayMap<String, ArrayMap<String, Boolean>> mSystemExtPrivilegedAppAllowlist =
            new ArrayMap<>();
    @NonNull
    private final ArrayMap<String, ArrayMap<String, ArrayMap<String, Boolean>>>
            mApexPrivilegedAppAllowlists = new ArrayMap<>();

    @NonNull
    private final ArrayMap<String, ArrayMap<String, Boolean>> mSignatureAppAllowlist =
            new ArrayMap<>();
    @NonNull
    private final ArrayMap<String, ArrayMap<String, Boolean>> mVendorSignatureAppAllowlist =
            new ArrayMap<>();
    @NonNull
    private final ArrayMap<String, ArrayMap<String, Boolean>> mProductSignatureAppAllowlist =
            new ArrayMap<>();
    @NonNull
    private final ArrayMap<String, ArrayMap<String, Boolean>> mSystemExtSignatureAppAllowlist =
            new ArrayMap<>();

    @NonNull
    public ArrayMap<String, ArrayMap<String, Boolean>> getOemAppAllowlist() {
        return mOemAppAllowlist;
    }

    @NonNull
    public ArrayMap<String, ArrayMap<String, Boolean>> getPrivilegedAppAllowlist() {
        return mPrivilegedAppAllowlist;
    }

    @NonNull
    public ArrayMap<String, ArrayMap<String, Boolean>> getVendorPrivilegedAppAllowlist() {
        return mVendorPrivilegedAppAllowlist;
    }

    @NonNull
    public ArrayMap<String, ArrayMap<String, Boolean>> getProductPrivilegedAppAllowlist() {
        return mProductPrivilegedAppAllowlist;
    }

    @NonNull
    public ArrayMap<String, ArrayMap<String, Boolean>> getSystemExtPrivilegedAppAllowlist() {
        return mSystemExtPrivilegedAppAllowlist;
    }

    @NonNull
    public ArrayMap<String, ArrayMap<String, ArrayMap<String, Boolean>>>
            getApexPrivilegedAppAllowlists() {
        return mApexPrivilegedAppAllowlists;
    }

    @NonNull
    public ArrayMap<String, ArrayMap<String, Boolean>> getSignatureAppAllowlist() {
        return mSignatureAppAllowlist;
    }

    @NonNull
    public ArrayMap<String, ArrayMap<String, Boolean>> getVendorSignatureAppAllowlist() {
        return mVendorSignatureAppAllowlist;
    }

    @NonNull
    public ArrayMap<String, ArrayMap<String, Boolean>> getProductSignatureAppAllowlist() {
        return mProductSignatureAppAllowlist;
    }

    @NonNull
    public ArrayMap<String, ArrayMap<String, Boolean>> getSystemExtSignatureAppAllowlist() {
        return mSystemExtSignatureAppAllowlist;
    }

    @Nullable
    public Boolean getOemAppAllowlistState(@NonNull String packageName,
            @NonNull String permissionName) {
        ArrayMap<String, Boolean> permissions = mOemAppAllowlist.get(packageName);
        if (permissions == null) {
            return null;
        }
        return permissions.get(permissionName);
    }

    @Nullable
    public Boolean getPrivilegedAppAllowlistState(@NonNull String packageName,
            @NonNull String permissionName) {
        ArrayMap<String, Boolean> permissions = mPrivilegedAppAllowlist.get(packageName);
        if (permissions == null) {
            return null;
        }
        return permissions.get(permissionName);
    }

    @Nullable
    public Boolean getVendorPrivilegedAppAllowlistState(@NonNull String packageName,
            @NonNull String permissionName) {
        ArrayMap<String, Boolean> permissions = mVendorPrivilegedAppAllowlist.get(packageName);
        if (permissions == null) {
            return null;
        }
        return permissions.get(permissionName);
    }

    @Nullable
    public Boolean getProductPrivilegedAppAllowlistState(@NonNull String packageName,
            @NonNull String permissionName) {
        ArrayMap<String, Boolean> permissions = mProductPrivilegedAppAllowlist.get(packageName);
        if (permissions == null) {
            return null;
        }
        return permissions.get(permissionName);
    }

    @Nullable
    public Boolean getSystemExtPrivilegedAppAllowlistState(@NonNull String packageName,
            @NonNull String permissionName) {
        ArrayMap<String, Boolean> permissions = mSystemExtPrivilegedAppAllowlist.get(packageName);
        if (permissions == null) {
            return null;
        }
        return permissions.get(permissionName);
    }

    @Nullable
    public Boolean getApexPrivilegedAppAllowlistState(@NonNull String moduleName,
            @NonNull String packageName, @NonNull String permissionName) {
        ArrayMap<String, ArrayMap<String, Boolean>> allowlist =
                mApexPrivilegedAppAllowlists.get(moduleName);
        if (allowlist == null) {
            return null;
        }
        ArrayMap<String, Boolean> permissions = allowlist.get(packageName);
        if (permissions == null) {
            return null;
        }
        return permissions.get(permissionName);
    }

    @Nullable
    public Boolean getSignatureAppAllowlistState(@NonNull String packageName,
            @NonNull String permissionName) {
        ArrayMap<String, Boolean> permissions = mSignatureAppAllowlist.get(packageName);
        if (permissions == null) {
            return null;
        }
        return permissions.get(permissionName);
    }

    @Nullable
    public Boolean getVendorSignatureAppAllowlistState(@NonNull String packageName,
            @NonNull String permissionName) {
        ArrayMap<String, Boolean> permissions = mVendorSignatureAppAllowlist.get(packageName);
        if (permissions == null) {
            return null;
        }
        return permissions.get(permissionName);
    }

    @Nullable
    public Boolean getProductSignatureAppAllowlistState(@NonNull String packageName,
            @NonNull String permissionName) {
        ArrayMap<String, Boolean> permissions = mProductSignatureAppAllowlist.get(packageName);
        if (permissions == null) {
            return null;
        }
        return permissions.get(permissionName);
    }

    @Nullable
    public Boolean getSystemExtSignatureAppAllowlistState(@NonNull String packageName,
            @NonNull String permissionName) {
        ArrayMap<String, Boolean> permissions = mSystemExtSignatureAppAllowlist.get(packageName);
        if (permissions == null) {
            return null;
        }
        return permissions.get(permissionName);
    }
}
