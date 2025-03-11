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

package com.android.server.companion.utils;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_SCAN;
import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.Manifest.permission.MANAGE_COMPANION_DEVICES;
import static android.Manifest.permission.REQUEST_COMPANION_SELF_MANAGED;
import static android.Manifest.permission.REQUEST_OBSERVE_DEVICE_UUID_PRESENCE;
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

import static com.android.server.companion.utils.RolesUtils.isRoleHolder;

import static java.util.Collections.unmodifiableMap;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.companion.AssociationRequest;
import android.companion.CompanionDeviceManager;
import android.content.Context;
import android.os.Binder;
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

    /**
     * Require the app to declare necessary permission for creating association.
     */
    public static void enforcePermissionForCreatingAssociation(@NonNull Context context,
            @NonNull AssociationRequest request, int packageUid) {
        enforcePermissionForRequestingProfile(context, request.getDeviceProfile(), packageUid);

        if (request.isSelfManaged() || request.getDeviceIcon() != null) {
            enforcePermissionForRequestingSelfManaged(context, packageUid);
        }
    }

    /**
     * Require the app to declare necessary permission for creating association with profile.
     */
    public static void enforcePermissionForRequestingProfile(
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

    /**
     * Require the app to declare necessary permission for creating self-managed association.
     */
    public static void enforcePermissionForRequestingSelfManaged(@NonNull Context context,
            int packageUid) {
        if (context.checkPermission(REQUEST_COMPANION_SELF_MANAGED, getCallingPid(), packageUid)
                != PERMISSION_GRANTED) {
            throw new SecurityException("Application does not hold "
                    + REQUEST_COMPANION_SELF_MANAGED);
        }
    }

    /**
     * Check if the caller can interact with the user.
     */
    public static boolean checkCallerCanInteractWithUserId(@NonNull Context context, int userId) {
        if (getCallingUserId() == userId) return true;

        return context.checkCallingPermission(INTERACT_ACROSS_USERS) == PERMISSION_GRANTED;
    }

    /**
     * Require the caller to be able to interact with the user.
     */
    public static void enforceCallerCanInteractWithUserId(@NonNull Context context, int userId) {
        if (getCallingUserId() == userId) return;

        context.enforceCallingPermission(INTERACT_ACROSS_USERS, null);
    }

    /**
     * Require the caller to be system UID or to be able to interact with the user.
     */
    public static void enforceCallerIsSystemOrCanInteractWithUserId(@NonNull Context context,
            int userId) {
        if (getCallingUid() == SYSTEM_UID) return;

        enforceCallerCanInteractWithUserId(context, userId);
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

    /**
     * Require the caller to be able to manage the associations for the package.
     */
    public static void enforceCallerCanManageAssociationsForPackage(@NonNull Context context,
            @UserIdInt int userId, @NonNull String packageName,
            @Nullable String actionDescription) {
        final int callingUid = getCallingUid();

        // If the caller is the system
        if (callingUid == SYSTEM_UID) {
            return;
        }

        // If caller can manage the package or has the permissions to manage companion devices
        boolean canInteractAcrossUsers = context.checkCallingPermission(INTERACT_ACROSS_USERS)
                == PERMISSION_GRANTED;
        boolean canManageCompanionDevices = context.checkCallingPermission(MANAGE_COMPANION_DEVICES)
                == PERMISSION_GRANTED;
        if (getCallingUserId() == userId) {
            if (checkPackage(callingUid, packageName) || canManageCompanionDevices) {
                return;
            }
        } else if (canInteractAcrossUsers && canManageCompanionDevices) {
            return;
        }

        throw new SecurityException("Caller (uid=" + getCallingUid() + ") does not have "
                + "permissions to "
                + (actionDescription != null ? actionDescription : "manage associations")
                + " for u" + userId + "/" + packageName);
    }

    /**
     * Require the caller to hold necessary permission to observe device presence by UUID.
     */
    public static void enforceCallerCanObserveDevicePresenceByUuid(@NonNull Context context,
            String packageName, int userId) {
        if (!hasRequirePermissions(context, packageName, userId)) {
            throw new SecurityException("Caller (uid=" + getCallingUid() + ") does not have "
                    + "permissions to request observing device presence base on the UUID");
        }
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

    private static boolean hasRequirePermissions(
            @NonNull Context context, String packageName, int userId) {
        return context.checkCallingPermission(
                REQUEST_OBSERVE_DEVICE_UUID_PRESENCE) == PERMISSION_GRANTED
                && context.checkCallingPermission(BLUETOOTH_SCAN) == PERMISSION_GRANTED
                && context.checkCallingPermission(BLUETOOTH_CONNECT) == PERMISSION_GRANTED
                && Boolean.TRUE.equals(Binder.withCleanCallingIdentity(
                        () -> isRoleHolder(context, userId, packageName,
                                DEVICE_PROFILE_AUTOMOTIVE_PROJECTION)));
    }

    // DO NOT USE DIRECTLY! Access via getAppOpsService().
    private static IAppOpsService sAppOpsService = null;

    private PermissionsUtils() {}
}
