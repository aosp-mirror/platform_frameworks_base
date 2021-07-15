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

package com.android.server.location.injector;

import android.location.util.identity.CallerIdentity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * Version of LocationPermissionsHelper for testing. All permissions are granted unless notified
 * otherwise.
 */
public class FakeLocationPermissionsHelper extends LocationPermissionsHelper {

    private final HashMap<String, Set<String>> mRevokedPermissions;

    public FakeLocationPermissionsHelper(AppOpsHelper appOps) {
        super(appOps);
        mRevokedPermissions = new HashMap<>();
    }

    public void grantPermission(String packageName, String permission) {
        getRevokedPermissionsList(packageName).remove(permission);
        notifyLocationPermissionsChanged(packageName);
    }

    public void revokePermission(String packageName, String permission) {
        getRevokedPermissionsList(packageName).add(permission);
        notifyLocationPermissionsChanged(packageName);
    }

    @Override
    protected boolean hasPermission(String permission, CallerIdentity identity) {
        return !getRevokedPermissionsList(identity.getPackageName()).contains(permission);
    }

    private Set<String> getRevokedPermissionsList(String packageName) {
        return mRevokedPermissions.computeIfAbsent(packageName, p -> new HashSet<>());
    }
}
