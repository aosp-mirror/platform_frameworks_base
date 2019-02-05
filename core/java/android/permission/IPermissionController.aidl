/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.permission;

import android.os.RemoteCallback;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;

/**
 * Interface for system apps to communication with the permission controller.
 *
 * @hide
 */
oneway interface IPermissionController {
    void revokeRuntimePermissions(in Bundle request, boolean doDryRun, int reason,
            String callerPackageName, in RemoteCallback callback);
    void getRuntimePermissionBackup(in UserHandle user, in ParcelFileDescriptor pipe);
    void restoreRuntimePermissionBackup(in UserHandle user, in ParcelFileDescriptor pipe);
    void restoreDelayedRuntimePermissionBackup(String packageName, in UserHandle user,
            in RemoteCallback callback);
    void getAppPermissions(String packageName, in RemoteCallback callback);
    void revokeRuntimePermission(String packageName, String permissionName);
    void countPermissionApps(in List<String> permissionNames, int flags,
            in RemoteCallback callback);
    void getPermissionUsages(boolean countSystem, long numMillis, in RemoteCallback callback);
    void isApplicationQualifiedForRole(String roleName, String packageName,
            in RemoteCallback callback);
}
