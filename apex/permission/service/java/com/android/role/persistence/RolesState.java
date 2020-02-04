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

package com.android.role.persistence;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.SystemApi.Client;

import java.util.Map;
import java.util.Set;

/**
 * State of all roles.
 *
 * TODO(b/147914847): Remove @hide when it becomes the default.
 * @hide
 */
@SystemApi(client = Client.SYSTEM_SERVER)
public final class RolesState {

    /**
     * The version of the roles.
     */
    private final int mVersion;

    /**
     * The hash of all packages in the system.
     */
    @Nullable
    private final String mPackagesHash;

    /**
     * The roles.
     */
    @NonNull
    private final Map<String, Set<String>> mRoles;

    public RolesState(int version, @Nullable String packagesHash,
            @NonNull Map<String, Set<String>> roles) {
        mVersion = version;
        mPackagesHash = packagesHash;
        mRoles = roles;
    }

    public int getVersion() {
        return mVersion;
    }

    @Nullable
    public String getPackagesHash() {
        return mPackagesHash;
    }

    @NonNull
    public Map<String, Set<String>> getRoles() {
        return mRoles;
    }
}
