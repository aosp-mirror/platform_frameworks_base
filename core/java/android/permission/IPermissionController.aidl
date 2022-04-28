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
import android.permission.AdminPermissionControlParams;
import com.android.internal.infra.AndroidFuture;

/**
 * Interface for system apps to communication with the permission controller.
 *
 * @hide
 */
oneway interface IPermissionController {
    void revokeRuntimePermissions(in Bundle request, boolean doDryRun, int reason,
            String callerPackageName, in AndroidFuture callback);
    void getRuntimePermissionBackup(in UserHandle user, in ParcelFileDescriptor pipe);
    void stageAndApplyRuntimePermissionsBackup(in UserHandle user, in ParcelFileDescriptor pipe);
    void applyStagedRuntimePermissionBackup(String packageName, in UserHandle user,
            in AndroidFuture callback);
    void getAppPermissions(String packageName, in AndroidFuture callback);
    void revokeRuntimePermission(String packageName, String permissionName);
    void countPermissionApps(in List<String> permissionNames, int flags,
            in AndroidFuture callback);
    void getPermissionUsages(boolean countSystem, long numMillis, in AndroidFuture callback);
    void setRuntimePermissionGrantStateByDeviceAdminFromParams(String callerPackageName,
            in AdminPermissionControlParams params, in AndroidFuture callback);
    void grantOrUpgradeDefaultRuntimePermissions(in AndroidFuture callback);
    void notifyOneTimePermissionSessionTimeout(String packageName);
    void updateUserSensitiveForApp(int uid, in AndroidFuture callback);
    void getPrivilegesDescriptionStringForProfile(
            in String deviceProfileName,
            in AndroidFuture<String> callback);
    void getPlatformPermissionsForGroup(
            in String permissionGroupName,
            in AndroidFuture<List<String>> callback);
    void getGroupOfPlatformPermission(
            in String permissionName,
            in AndroidFuture<String> callback);
    void getUnusedAppCount(
            in AndroidFuture callback);
    void getHibernationEligibility(
                in String packageName,
                in AndroidFuture callback);
    void revokeSelfPermissionsOnKill(in String packageName, in List<String> permissions,
            in AndroidFuture callback);
}
