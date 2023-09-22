/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.content.AttributionSourceState;
import android.content.pm.ParceledListSlice;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.permission.SplitPermissionInfoParcelable;
import android.os.UserHandle;
import android.permission.IOnPermissionsChangeListener;

/**
 * Interface to communicate directly with the permission manager service.
 * @see PermissionManager
 * @hide
 */
interface IPermissionManager {
    ParceledListSlice getAllPermissionGroups(int flags);

    PermissionGroupInfo getPermissionGroupInfo(String groupName, int flags);

    PermissionInfo getPermissionInfo(String permissionName, String packageName, int flags);

    ParceledListSlice queryPermissionsByGroup(String groupName, int flags);

    boolean addPermission(in PermissionInfo permissionInfo, boolean async);

    void removePermission(String permissionName);

    int getPermissionFlags(String packageName, String permissionName, int deviceId, int userId);

    void updatePermissionFlags(String packageName, String permissionName, int flagMask,
            int flagValues, boolean checkAdjustPolicyFlagPermission, int deviceId, int userId);

    void updatePermissionFlagsForAllApps(int flagMask, int flagValues, int userId);

    void addOnPermissionsChangeListener(in IOnPermissionsChangeListener listener);

    void removeOnPermissionsChangeListener(in IOnPermissionsChangeListener listener);

    List<String> getAllowlistedRestrictedPermissions(String packageName,
            int flags, int userId);

    boolean addAllowlistedRestrictedPermission(String packageName, String permissionName,
            int flags, int userId);

    boolean removeAllowlistedRestrictedPermission(String packageName, String permissionName,
            int flags, int userId);

    void grantRuntimePermission(String packageName, String permissionName, int deviceId, int userId);

    void revokeRuntimePermission(String packageName, String permissionName, int deviceId,
            int userId, String reason);

    void revokePostNotificationPermissionWithoutKillForTest(String packageName, int userId);

    boolean shouldShowRequestPermissionRationale(String packageName, String permissionName,
            int deviceId, int userId);

    boolean isPermissionRevokedByPolicy(String packageName, String permissionName, int deviceId,
            int userId);

    List<SplitPermissionInfoParcelable> getSplitPermissions();

    @EnforcePermission("MANAGE_ONE_TIME_PERMISSION_SESSIONS")
    void startOneTimePermissionSession(String packageName, int deviceId, int userId, long timeout,
            long revokeAfterKilledDelay);

    @EnforcePermission("MANAGE_ONE_TIME_PERMISSION_SESSIONS")
    void stopOneTimePermissionSession(String packageName, int userId);

    List<String> getAutoRevokeExemptionRequestedPackages(int userId);

    List<String> getAutoRevokeExemptionGrantedPackages(int userId);

    boolean setAutoRevokeExempted(String packageName, boolean exempted, int userId);

    boolean isAutoRevokeExempted(String packageName, int userId);

    void registerAttributionSource(in AttributionSourceState source);

    boolean isRegisteredAttributionSource(in AttributionSourceState source);

    int checkPermission(String packageName, String permissionName, int deviceId, int userId);

    int checkUidPermission(int uid, String permissionName, int deviceId);
}
