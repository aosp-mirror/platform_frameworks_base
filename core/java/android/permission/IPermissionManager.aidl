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
    String[] getAppOpPermissionPackages(String permName);

    ParceledListSlice getAllPermissionGroups(int flags);

    PermissionGroupInfo getPermissionGroupInfo(String groupName, int flags);

    PermissionInfo getPermissionInfo(String permName, String packageName, int flags);

    ParceledListSlice queryPermissionsByGroup(String groupName, int flags);

    boolean addPermission(in PermissionInfo info, boolean async);

    void removePermission(String name);

    int getPermissionFlags(String permName, String packageName, int userId);

    void updatePermissionFlags(String permName, String packageName, int flagMask,
            int flagValues, boolean checkAdjustPolicyFlagPermission, int userId);

    void updatePermissionFlagsForAllApps(int flagMask, int flagValues, int userId);

    int checkPermission(String permName, String pkgName, int userId);

    int checkUidPermission(String permName, int uid);

    int checkDeviceIdentifierAccess(String packageName, String callingFeatureId, String message, int pid, int uid);

    void addOnPermissionsChangeListener(in IOnPermissionsChangeListener listener);

    void removeOnPermissionsChangeListener(in IOnPermissionsChangeListener listener);

    List<String> getWhitelistedRestrictedPermissions(String packageName,
            int flags, int userId);

    boolean addWhitelistedRestrictedPermission(String packageName, String permName,
            int flags, int userId);

    boolean removeWhitelistedRestrictedPermission(String packageName, String permName,
            int flags, int userId);

    void grantRuntimePermission(String packageName, String permName, int userId);

    void revokeRuntimePermission(String packageName, String permName, int userId, String reason);

    void resetRuntimePermissions();

    boolean setDefaultBrowser(String packageName, int userId);

    String getDefaultBrowser(int userId);

    void grantDefaultPermissionsToEnabledCarrierApps(in String[] packageNames, int userId);

    void grantDefaultPermissionsToEnabledImsServices(in String[] packageNames, int userId);

    void grantDefaultPermissionsToEnabledTelephonyDataServices(
            in String[] packageNames, int userId);

    void revokeDefaultPermissionsFromDisabledTelephonyDataServices(
            in String[] packageNames, int userId);

    void grantDefaultPermissionsToActiveLuiApp(in String packageName, int userId);

    void revokeDefaultPermissionsFromLuiApps(in String[] packageNames, int userId);

    void setPermissionEnforced(String permName, boolean enforced);

    boolean isPermissionEnforced(String permName);

    boolean shouldShowRequestPermissionRationale(String permName,
            String packageName, int userId);

    boolean isPermissionRevokedByPolicy(String permName, String packageName, int userId);

    List<SplitPermissionInfoParcelable> getSplitPermissions();

    void startOneTimePermissionSession(String packageName, int userId, long timeout,
            int importanceToResetTimer, int importanceToKeepSessionAlive);

    void stopOneTimePermissionSession(String packageName, int userId);

    List<String> getAutoRevokeExemptionRequestedPackages(int userId);

    List<String> getAutoRevokeExemptionGrantedPackages(int userId);

    boolean setAutoRevokeWhitelisted(String packageName, boolean whitelisted, int userId);

    boolean isAutoRevokeWhitelisted(String packageName, int userId);
}
