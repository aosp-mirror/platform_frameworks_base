/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;

import com.android.internal.annotations.VisibleForTesting;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * System level service for accessing the permission capabilities of the platform, version 2.
 *
 * @hide
 */
//@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
@SystemService(Context.LEGACY_PERMISSION_SERVICE)
public final class LegacyPermissionManager {
    private final ILegacyPermissionManager mLegacyPermissionManager;

    /**
     * Creates a new instance.
     *
     * @hide
     */
    public LegacyPermissionManager() throws ServiceManager.ServiceNotFoundException {
        this(ILegacyPermissionManager.Stub.asInterface(ServiceManager.getServiceOrThrow(
                "legacy_permission")));
    }

    /**
     * Creates a new instance with the provided instantiation of the ILegacyPermissionManager.
     *
     * @param legacyPermissionManager injectable legacy permission manager service
     *
     * @hide
     */
    @VisibleForTesting
    public LegacyPermissionManager(@NonNull ILegacyPermissionManager legacyPermissionManager) {
        mLegacyPermissionManager = legacyPermissionManager;
    }

    /**
     * Checks whether the package with the given pid/uid can read device identifiers.
     *
     * @param packageName      the name of the package to be checked for identifier access
     * @param message          the message to be used for logging during identifier access
     *                         verification
     * @param callingFeatureId the feature in the package
     * @param pid              the process id of the package to be checked
     * @param uid              the uid of the package to be checked
     * @return {@link PackageManager#PERMISSION_GRANTED} if the package is allowed identifier
     * access, {@link PackageManager#PERMISSION_DENIED} otherwise
     * @hide
     */
    //@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public int checkDeviceIdentifierAccess(@Nullable String packageName, @Nullable String message,
            @Nullable String callingFeatureId, int pid, int uid) {
        try {
            return mLegacyPermissionManager.checkDeviceIdentifierAccess(packageName, message,
                    callingFeatureId, pid, uid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Checks whether the package with the given pid/uid can read the device phone number.
     *
     * @param packageName      the name of the package to be checked for phone number access
     * @param message          the message to be used for logging during phone number access
     *                         verification
     * @param callingFeatureId the feature in the package
     * @param pid              the process id of the package to be checked
     * @param uid              the uid of the package to be checked
     * @return <ul>
     *     <li>{@link PackageManager#PERMISSION_GRANTED} if the package is allowed phone number
     *     access</li>
     *     <li>{@link android.app.AppOpsManager#MODE_IGNORED} if the package does not have phone
     *     number access but for appcompat reasons this should be a silent failure (ie return empty
     *     or null data)</li>
     *     <li>{@link PackageManager#PERMISSION_DENIED} if the package does not have phone number
     *     access</li>
     * </ul>
     * @hide
     */
    public int checkPhoneNumberAccess(@Nullable String packageName, @Nullable String message,
            @Nullable String callingFeatureId, int pid, int uid) {
        try {
            return mLegacyPermissionManager.checkPhoneNumberAccess(packageName, message,
                    callingFeatureId, pid, uid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Grant default permissions to currently active LUI app
     * @param packageName The package name for the LUI app
     * @param user The user handle
     * @param executor The executor for the callback
     * @param callback The callback provided by caller to be notified when grant completes
     * @hide
     */
    @RequiresPermission(Manifest.permission.GRANT_RUNTIME_PERMISSIONS_TO_TELEPHONY_DEFAULTS)
    public void grantDefaultPermissionsToLuiApp(
            @NonNull String packageName, @NonNull UserHandle user,
            @NonNull @CallbackExecutor Executor executor, @NonNull Consumer<Boolean> callback) {
        try {
            mLegacyPermissionManager.grantDefaultPermissionsToActiveLuiApp(
                    packageName, user.getIdentifier());
            executor.execute(() -> callback.accept(true));
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Revoke default permissions to currently active LUI app
     * @param packageNames The package names for the LUI apps
     * @param user The user handle
     * @param executor The executor for the callback
     * @param callback The callback provided by caller to be notified when grant completes
     * @hide
     */
    @RequiresPermission(Manifest.permission.GRANT_RUNTIME_PERMISSIONS_TO_TELEPHONY_DEFAULTS)
    public void revokeDefaultPermissionsFromLuiApps(
            @NonNull String[] packageNames, @NonNull UserHandle user,
            @NonNull @CallbackExecutor Executor executor, @NonNull Consumer<Boolean> callback) {
        try {
            mLegacyPermissionManager.revokeDefaultPermissionsFromLuiApps(
                    packageNames, user.getIdentifier());
            executor.execute(() -> callback.accept(true));
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Grant default permissions to currently active Ims services
     * @param packageNames The package names for the Ims services
     * @param user The user handle
     * @param executor The executor for the callback
     * @param callback The callback provided by caller to be notified when grant completes
     * @hide
     */
    @RequiresPermission(Manifest.permission.GRANT_RUNTIME_PERMISSIONS_TO_TELEPHONY_DEFAULTS)
    public void grantDefaultPermissionsToEnabledImsServices(
            @NonNull String[] packageNames, @NonNull UserHandle user,
            @NonNull @CallbackExecutor Executor executor, @NonNull Consumer<Boolean> callback) {
        try {
            mLegacyPermissionManager.grantDefaultPermissionsToEnabledImsServices(
                    packageNames, user.getIdentifier());
            executor.execute(() -> callback.accept(true));
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Grant default permissions to currently enabled telephony data services
     * @param packageNames The package name for the services
     * @param user The user handle
     * @param executor The executor for the callback
     * @param callback The callback provided by caller to be notified when grant completes
     * @hide
     */
    @RequiresPermission(Manifest.permission.GRANT_RUNTIME_PERMISSIONS_TO_TELEPHONY_DEFAULTS)
    public void grantDefaultPermissionsToEnabledTelephonyDataServices(
            @NonNull String[] packageNames, @NonNull UserHandle user,
            @NonNull @CallbackExecutor Executor executor, @NonNull Consumer<Boolean> callback) {
        try {
            mLegacyPermissionManager.grantDefaultPermissionsToEnabledTelephonyDataServices(
                    packageNames, user.getIdentifier());
            executor.execute(() -> callback.accept(true));
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Revoke default permissions to currently active telephony data services
     * @param packageNames The package name for the services
     * @param user The user handle
     * @param executor The executor for the callback
     * @param callback The callback provided by caller to be notified when revoke completes
     * @hide
     */
    @RequiresPermission(Manifest.permission.GRANT_RUNTIME_PERMISSIONS_TO_TELEPHONY_DEFAULTS)
    public void revokeDefaultPermissionsFromDisabledTelephonyDataServices(
            @NonNull String[] packageNames, @NonNull UserHandle user,
            @NonNull @CallbackExecutor Executor executor, @NonNull Consumer<Boolean> callback) {
        try {
            mLegacyPermissionManager.revokeDefaultPermissionsFromDisabledTelephonyDataServices(
                    packageNames, user.getIdentifier());
            executor.execute(() -> callback.accept(true));
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Grant default permissions to currently enabled carrier apps
     * @param packageNames Package names of the apps to be granted permissions
     * @param user The user handle
     * @param executor The executor for the callback
     * @param callback The callback provided by caller to be notified when grant completes
     * @hide
     */
    @RequiresPermission(Manifest.permission.GRANT_RUNTIME_PERMISSIONS_TO_TELEPHONY_DEFAULTS)
    public void grantDefaultPermissionsToEnabledCarrierApps(@NonNull String[] packageNames,
            @NonNull UserHandle user, @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<Boolean> callback) {
        try {
            mLegacyPermissionManager.grantDefaultPermissionsToEnabledCarrierApps(packageNames,
                    user.getIdentifier());
            executor.execute(() -> callback.accept(true));
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Grant permissions to a newly set Carrier Services app.
     * @param packageName The newly set Carrier Services app
     * @param userId The user for which to grant the permissions.
     * @hide
     */
    public void grantDefaultPermissionsToCarrierServiceApp(@NonNull String packageName,
            @UserIdInt int userId) {
        try {
            mLegacyPermissionManager.grantDefaultPermissionsToCarrierServiceApp(packageName,
                    userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
