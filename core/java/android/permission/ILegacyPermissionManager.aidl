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
 * Interface to communicate directly with the legacy permission manager service.
 *
 * @see PermissionManager
 * @hide
 */
interface ILegacyPermissionManager {
    int checkDeviceIdentifierAccess(String packageName, String message, String callingFeatureId,
            int pid, int uid);

    int checkPhoneNumberAccess(String packageName, String message, String callingFeatureId,
            int pid, int uid);

    void grantDefaultPermissionsToEnabledCarrierApps(in String[] packageNames, int userId);

    void grantDefaultPermissionsToEnabledImsServices(in String[] packageNames, int userId);

    void grantDefaultPermissionsToEnabledTelephonyDataServices(
            in String[] packageNames, int userId);

    void revokeDefaultPermissionsFromDisabledTelephonyDataServices(
            in String[] packageNames, int userId);

    void grantDefaultPermissionsToActiveLuiApp(in String packageName, int userId);

    void revokeDefaultPermissionsFromLuiApps(in String[] packageNames, int userId);
}
