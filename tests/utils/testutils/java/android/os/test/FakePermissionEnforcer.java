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

package android.os.test;

import static android.permission.PermissionManager.PERMISSION_GRANTED;
import static android.permission.PermissionManager.PERMISSION_HARD_DENIED;

import android.annotation.NonNull;
import android.content.AttributionSource;
import android.os.PermissionEnforcer;

import java.util.HashSet;
import java.util.Set;

/**
 * Fake for {@link PermissionEnforcer}. Useful for tests wanting to mock the
 * permission checks of an AIDL service. FakePermissionEnforcer may be passed
 * to the constructor of the AIDL-generated Stub class.
 *
 */
public class FakePermissionEnforcer extends PermissionEnforcer {
    private Set<String> mGranted;

    public FakePermissionEnforcer() {
        mGranted = new HashSet();
    }

    public void grant(String permission) {
        mGranted.add(permission);
    }

    public void revoke(String permission) {
        mGranted.remove(permission);
    }

    public void revokeAll() {
        mGranted.clear();
    }

    private boolean granted(String permission) {
        return mGranted.contains(permission);
    }

    @Override
    protected int checkPermission(@NonNull String permission,
              @NonNull AttributionSource source) {
        return granted(permission) ? PERMISSION_GRANTED : PERMISSION_HARD_DENIED;
    }

    @Override
    protected int checkPermission(@NonNull String permission, int pid, int uid) {
        return granted(permission) ? PERMISSION_GRANTED : PERMISSION_HARD_DENIED;
    }
}
