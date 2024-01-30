/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.companion;

import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.Manifest.permission.MANAGE_COMPANION_DEVICES;
import static android.Manifest.permission.REQUEST_COMPANION_SELF_MANAGED;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.companion.AssociationRequest.DEVICE_PROFILE_APP_STREAMING;
import static android.companion.AssociationRequest.DEVICE_PROFILE_AUTOMOTIVE_PROJECTION;
import static android.companion.AssociationRequest.DEVICE_PROFILE_COMPUTER;
import static android.companion.AssociationRequest.DEVICE_PROFILE_GLASSES;
import static android.companion.AssociationRequest.DEVICE_PROFILE_NEARBY_DEVICE_STREAMING;
import static android.companion.AssociationRequest.DEVICE_PROFILE_WATCH;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Binder.getCallingPid;
import static android.os.Binder.getCallingUid;
import static android.os.Process.SYSTEM_UID;
import static android.os.UserHandle.getCallingUserId;

import static java.util.Collections.unmodifiableMap;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.CompanionDeviceManager;
import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.ArrayMap;

import com.android.internal.app.IAppOpsService;

import java.util.Map;

/**
 * Utility methods for checking permissions required for accessing {@link CompanionDeviceManager}
 * APIs (such as {@link Manifest.permission#REQUEST_COMPANION_PROFILE_WATCH},
 * {@link Manifest.permission#REQUEST_COMPANION_PROFILE_APP_STREAMING},
 * {@link Manifest.permission#REQUEST_COMPANION_SELF_MANAGED} etc.)
 */
public final class PermissionsUtils {

    private static final Map<String, String> DEVICE_PROFILE_TO_PERMISSION;
    static {
        final Map<String, String> map = new ArrayMap<>();
        map.put(DEVICE_PROFILE_WATCH, Manifest.permission.REQUEST_COMPANION_PROFILE_WATCH);
        map.put(DEVICE_PROFILE_APP_STREAMING,
                Manifest.permission.REQUEST_COMPANION_PROFILE_APP_STREAMING);
        map.put(DEVICE_PROFILE_AUTOMOTIVE_PROJECTION,
                Manifest.permission.REQUEST_COMPANION_PROFILE_AUTOMOTIVE_PROJECTION);
        map.put(DEVICE_PROFILE_COMPUTER, Manifest.permission.REQUEST_COMPANION_PROFILE_COMPUTER);
        map.put(DEVICE_PROFILE_GLASSES, Manifest.permission.REQUEST_COMPANION_PROFILE_GLASSES);
        map.put(DEVICE_PROFILE_NEARBY_DEVICE_STREAMING,
                Manifest.permission.REQUEST_COMPANION_PROFILE_NEARBY_DEVICE_STREAMING);

        DEVICE_PROFILE_TO_PERMISSION = unmodifiableMap(map);
    }

    static void enforcePermissionsForAssociation(@NonNull Context context,
            @NonNull AssociationRequest request, int packageUid) {
        enforceRequestDeviceProfilePermissions(context, request.getDeviceProfile(), packageUid);

        if (request.isSelfManaged()) {
            enforceRequestSelfManagedPermission(context, packageUid);
        }
    }

    static void enforceRequestDeviceProfilePermissions(
            @NonNull Context context, @Nullable String deviceProfile, int packageUid) {
        // Device profile can be null.
        if (deviceProfile == null) return;

        if (!DEVICE_PROFILE_TO_PERMISSION.containsKey(deviceProfile)) {
            throw new IllegalArgumentException("Unsupported device profile: " + deviceProfile);
        }

        final String permission = DEVICE_PROFILE_TO_PERMISSION.get(deviceProfile);
        if (context.checkPermission(permission, getCallingPid(), packageUid)
                != PERMISSION_GRANTED) {
            throw new SecurityException("Application must hold " + permission + " to associate "
                    + "with a device with " + deviceProfile + " profile.");
        }
    }

    static void enforceRequestSelfManagedPermission(@NonNull Context context, int packageUid) {
        if (context.checkPermission(REQUEST_COMPANION_SELF_MANAGED, getCallingPid(), packageUid)
                != PERMISSION_GRANTED) {
            throw new SecurityException("Application does not hold "
                    + REQUEST_COMPANION_SELF_MANAGED);
        }
    }

    static boolean checkCallerCanInteractWithUserId(@NonNull Context context, int userId) {
        if (getCallingUserId() == userId) return true;

        return context.checkCallingPermission(INTERACT_ACROSS_USERS) == PERMISSION_GRANTED;
    }

    static void enforceCallerCanInteractWithUserId(@NonNull Context context, int userId) {
        if (getCallingUserId() == userId) return;

        context.enforceCallingPermission(INTERACT_ACROSS_USERS, null);
    }

    static void enforceCallerIsSystemOrCanInteractWithUserId(@NonNull Context context, int userId) {
        if (getCallingUid() == SYSTEM_UID) return;

        enforceCallerCanInteractWithUserId(context, userId);
    }

    static boolean checkCallerIsSystemOr(@UserIdInt int userId, @NonNull String packageName) {
        final int callingUid = getCallingUid();
        if (callingUid == SYSTEM_UID) return true;

        if (getCallingUserId() != userId) return false;

        if (!checkPackage(callingUid, packageName)) return false;

        return true;
    }

    /**
     * Check if the calling user id matches the userId, and if the package belongs to
     * the calling uid.
     */
    public static void enforceCallerIsSystemOr(@UserIdInt int userId, @NonNull String packageName) {
        final int callingUid = getCallingUid();
        if (callingUid == SYSTEM_UID) return;

        final int callingUserId = getCallingUserId();
        if (getCallingUserId() != userId) {
            throw new SecurityException("Calling UserId (" + callingUserId + ") does not match "
                    + "the expected UserId (" + userId + ")");
        }

        if (!checkPackage(callingUid, packageName)) {
            throw new SecurityException(packageName + " doesn't belong to calling uid ("
                    + callingUid + ")");
        }
    }

    static boolean checkCallerCanManageCompanionDevice(@NonNull Context context) {
        if (getCallingUid() == SYSTEM_UID) return true;

        return context.checkCallingPermission(MANAGE_COMPANION_DEVICES) == PERMISSION_GRANTED;
    }

    static void enforceCallerCanManageAssociationsForPackage(@NonNull Context context,
            @UserIdInt int userId, @NonNull String packageName,
            @Nullable String actionDescription) {
        if (checkCallerCanManageAssociationsForPackage(context, userId, packageName)) return;

        throw new SecurityException("Caller (uid=" + getCallingUid() + ") does not have "
                + "permissions to "
                + (actionDescription != null ? actionDescription : "manage associations")
                + " for u" + userId + "/" + packageName);
    }

    /**
     * Check if the caller is either:
     * <ul>
     * <li> the package itself
     * <li> the System ({@link android.os.Process#SYSTEM_UID})
     * <li> holds {@link Manifest.permission#MANAGE_COMPANION_DEVICES} and, if belongs to a
     * different user, also holds {@link Manifest.permission#INTERACT_ACROSS_USERS}.
     * </ul>
     * @return whether the caller is one of the above.
     */
    static boolean checkCallerCanManageAssociationsForPackage(@NonNull Context context,
            @UserIdInt int userId, @NonNull String packageName) {
        if (checkCallerIsSystemOr(userId, packageName)) return true;

        if (!checkCallerCanInteractWithUserId(context, userId)) return false;

        return checkCallerCanManageCompanionDevice(context);
    }

    /**
     * Check if CDM can trust the context to process the association.
     */
    @Nullable
    public static AssociationInfo sanitizeWithCallerChecks(@NonNull Context context,
            @Nullable AssociationInfo association) {
        if (association == null) return null;

        final int userId = association.getUserId();
        final String packageName = association.getPackageName();
        if (!checkCallerCanManageAssociationsForPackage(context, userId, packageName)) {
            return null;
        }

        return association;
    }

    private static boolean checkPackage(@UserIdInt int uid, @NonNull String packageName) {
        try {
            return getAppOpsService().checkPackage(uid, packageName) == MODE_ALLOWED;
        } catch (RemoteException e) {
            // Can't happen: AppOpsManager is running in the same process.
            return true;
        }
    }

    private static IAppOpsService getAppOpsService() {
        if (sAppOpsService == null) {
            synchronized (PermissionsUtils.class) {
                if (sAppOpsService == null) {
                    sAppOpsService = IAppOpsService.Stub.asInterface(
                            ServiceManager.getService(Context.APP_OPS_SERVICE));
                }
            }
        }
        return sAppOpsService;
    }

    // DO NOT USE DIRECTLY! Access via getAppOpsService().
    private static IAppOpsService sAppOpsService = null;

    private PermissionsUtils() {}
}
