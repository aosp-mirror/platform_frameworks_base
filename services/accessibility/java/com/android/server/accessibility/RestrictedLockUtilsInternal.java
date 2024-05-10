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

package com.android.server.accessibility;

import static com.android.settingslib.RestrictedLockUtils.EnforcedAdmin;

import android.annotation.UserIdInt;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.UserHandle;
import android.os.UserManager;

import com.android.settingslib.RestrictedLockUtils;

import java.util.List;

/**
 * Utility class to host methods usable to return {@link EnforcedAdmin} instances based on device
 * admin policy.
 */
public class RestrictedLockUtilsInternal {

    /**
     * Disables accessibility service that are not permitted.
     */
    public static EnforcedAdmin checkIfAccessibilityServiceDisallowed(Context context,
            String packageName, int userId) {
        final DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        if (dpm == null) {
            return null;
        }
        final EnforcedAdmin admin =
                RestrictedLockUtils.getProfileOrDeviceOwner(context, getUserHandleOf(userId));
        boolean permitted = true;
        if (admin != null) {
            permitted = dpm.isAccessibilityServicePermittedByAdmin(admin.component,
                    packageName, userId);
        }
        int managedProfileId = getManagedProfileId(context, userId);
        final EnforcedAdmin profileAdmin = RestrictedLockUtils.getProfileOrDeviceOwner(context,
                getUserHandleOf(managedProfileId));
        boolean permittedByProfileAdmin = true;
        if (profileAdmin != null) {
            permittedByProfileAdmin = dpm.isAccessibilityServicePermittedByAdmin(
                    profileAdmin.component, packageName, managedProfileId);
        }
        if (!permitted && !permittedByProfileAdmin) {
            return EnforcedAdmin.MULTIPLE_ENFORCED_ADMIN;
        } else if (!permitted) {
            return admin;
        } else if (!permittedByProfileAdmin) {
            return profileAdmin;
        }
        return null;
    }

    /**
     * Disables input method that are not permitted.
     */
    public static EnforcedAdmin checkIfInputMethodDisallowed(Context context, String packageName,
            int userId) {
        final DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        if (dpm == null) {
            return null;
        }
        final EnforcedAdmin admin =
                RestrictedLockUtils.getProfileOrDeviceOwner(context, getUserHandleOf(userId));
        boolean permitted = true;
        if (admin != null) {
            permitted = dpm.isInputMethodPermittedByAdmin(admin.component,
                    packageName, userId);
        }

        boolean permittedByParentAdmin = true;
        EnforcedAdmin profileAdmin = null;
        int managedProfileId = getManagedProfileId(context, userId);
        if (managedProfileId != UserHandle.USER_NULL) {
            profileAdmin = RestrictedLockUtils.getProfileOrDeviceOwner(
                    context, getUserHandleOf(managedProfileId));
            // If the device is an organization-owned device with a managed profile, the
            // managedProfileId will be used instead of the affected userId. This is because
            // isInputMethodPermittedByAdmin is called on the parent DPM instance, which will
            // return results affecting the personal profile.
            if (profileAdmin != null && dpm.isOrganizationOwnedDeviceWithManagedProfile()) {
                final DevicePolicyManager parentDpm = dpm.getParentProfileInstance(
                        UserManager.get(context).getUserInfo(managedProfileId));
                permittedByParentAdmin = parentDpm.isInputMethodPermittedByAdmin(
                        profileAdmin.component, packageName, managedProfileId);
            }
        }
        if (!permitted && !permittedByParentAdmin) {
            return EnforcedAdmin.MULTIPLE_ENFORCED_ADMIN;
        } else if (!permitted) {
            return admin;
        } else if (!permittedByParentAdmin) {
            return profileAdmin;
        }
        return null;
    }

    private static int getManagedProfileId(Context context, int userId) {
        final UserManager um = context.getSystemService(UserManager.class);
        final List<UserInfo> userProfiles = um.getProfiles(userId);
        for (UserInfo uInfo : userProfiles) {
            if (uInfo.id == userId) {
                continue;
            }
            if (uInfo.isManagedProfile()) {
                return uInfo.id;
            }
        }
        return UserHandle.USER_NULL;
    }

    /**
     * @return the UserHandle for a userId. Return null for USER_NULL
     */
    private static UserHandle getUserHandleOf(@UserIdInt int userId) {
        if (userId == UserHandle.USER_NULL) {
            return null;
        } else {
            return UserHandle.of(userId);
        }
    }
}
