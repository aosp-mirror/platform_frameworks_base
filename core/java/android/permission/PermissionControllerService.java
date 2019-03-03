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

import static android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT;
import static android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED;
import static android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED;
import static android.permission.PermissionControllerManager.COUNT_ONLY_WHEN_GRANTED;
import static android.permission.PermissionControllerManager.COUNT_WHEN_SYSTEM;

import static com.android.internal.util.Preconditions.checkArgument;
import static com.android.internal.util.Preconditions.checkArgumentNonnegative;
import static com.android.internal.util.Preconditions.checkCollectionElementsNotNull;
import static com.android.internal.util.Preconditions.checkFlagsArgument;
import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.internal.util.Preconditions.checkStringNotEmpty;
import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.Manifest;
import android.annotation.BinderThread;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.Service;
import android.app.admin.DevicePolicyManager.PermissionGrantState;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteCallback;
import android.os.UserHandle;
import android.permission.PermissionControllerManager.CountPermissionAppsFlag;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * This service is meant to be implemented by the app controlling permissions.
 *
 * @see PermissionControllerManager
 *
 * @hide
 */
@SystemApi
public abstract class PermissionControllerService extends Service {
    private static final String LOG_TAG = PermissionControllerService.class.getSimpleName();

    /**
     * The {@link Intent} action that must be declared as handled by a service
     * in its manifest for the system to recognize it as a runtime permission
     * presenter service.
     */
    public static final String SERVICE_INTERFACE = "android.permission.PermissionControllerService";

    // No need for locking - always set first and never modified
    private Handler mHandler;

    @Override
    public final void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        mHandler = new Handler(base.getMainLooper());
    }

    /**
     * Revoke a set of runtime permissions for various apps.
     *
     * @param requests The permissions to revoke as {@code Map<packageName, List<permission>>}
     * @param doDryRun Compute the permissions that would be revoked, but not actually revoke them
     * @param reason Why the permission should be revoked
     * @param callerPackageName The package name of the calling app
     *
     * @return the actually removed permissions as {@code Map<packageName, List<permission>>}
     */
    public abstract @NonNull Map<String, List<String>> onRevokeRuntimePermissions(
            @NonNull Map<String, List<String>> requests, boolean doDryRun,
            @PermissionControllerManager.Reason int reason, @NonNull String callerPackageName);

    /**
     * Create a backup of the runtime permissions.
     *
     * @param user The user to back up
     * @param backup The stream to write the backup to
     */
    public abstract void onGetRuntimePermissionsBackup(@NonNull UserHandle user,
            @NonNull OutputStream backup);

    /**
     * Restore a backup of the runtime permissions.
     *
     * @param user The user to restore
     * @param backup The stream to read the backup from
     */
    @BinderThread
    public abstract void onRestoreRuntimePermissionsBackup(@NonNull UserHandle user,
            @NonNull InputStream backup);

    /**
     * Restore a delayed backup of the runtime permissions.
     *
     * @param packageName The app to restore
     * @param user The user to restore
     *
     * @return {@code true} iff there is still delayed backup left
     */
    @BinderThread
    public abstract boolean onRestoreDelayedRuntimePermissionsBackup(@NonNull String packageName,
            @NonNull UserHandle user);

    /**
     * Gets the runtime permissions for an app.
     *
     * @param packageName The package for which to query.
     *
     * @return descriptions of the runtime permissions of the app
     */
    public abstract @NonNull List<RuntimePermissionPresentationInfo> onGetAppPermissions(
            @NonNull String packageName);

    /**
     * Revokes the permission {@code permissionName} for app {@code packageName}
     *
     * @param packageName The package for which to revoke
     * @param permissionName The permission to revoke
     */
    public abstract void onRevokeRuntimePermission(@NonNull String packageName,
            @NonNull String permissionName);

    /**
     * Count how many apps have one of a set of permissions.
     *
     * @param permissionNames The permissions the app might have
     * @param flags Modify which apps to count. By default all non-system apps that request a
     *              permission are counted
     *
     * @return the number of apps that have one of the permissions
     */
    public abstract int onCountPermissionApps(@NonNull List<String> permissionNames,
            @CountPermissionAppsFlag int flags);

    /**
     * Count how many apps have used permissions.
     *
     * @param countSystem Also count system apps
     * @param numMillis The number of milliseconds in the past to check for uses
     *
     * @return descriptions of the users of permissions
     */
    public abstract @NonNull List<RuntimePermissionUsageInfo> onGetPermissionUsages(
            boolean countSystem, long numMillis);

    /**
     * Check whether an application is qualified for a role.
     *
     * @param roleName name of the role to check for
     * @param packageName package name of the application to check for
     *
     * @return whether the application is qualified for the role.
     */
    public abstract boolean onIsApplicationQualifiedForRole(@NonNull String roleName,
            @NonNull String packageName);

    /**
     * Set the runtime permission state from a device admin.
     *
     * @param callerPackageName The package name of the admin requesting the change
     * @param packageName Package the permission belongs to
     * @param permission Permission to change
     * @param grantState State to set the permission into
     */
    public abstract boolean onSetRuntimePermissionGrantStateByDeviceAdmin(
            @NonNull String callerPackageName, @NonNull String packageName,
            @NonNull String permission, @PermissionGrantState int grantState);

    @Override
    public final @NonNull IBinder onBind(Intent intent) {
        return new IPermissionController.Stub() {
            @Override
            public void revokeRuntimePermissions(
                    Bundle bundleizedRequest, boolean doDryRun, int reason,
                    String callerPackageName, RemoteCallback callback) {
                checkNotNull(bundleizedRequest, "bundleizedRequest");
                checkNotNull(callerPackageName);
                checkNotNull(callback);

                Map<String, List<String>> request = new ArrayMap<>();
                for (String packageName : bundleizedRequest.keySet()) {
                    Preconditions.checkNotNull(packageName);

                    ArrayList<String> permissions =
                            bundleizedRequest.getStringArrayList(packageName);
                    Preconditions.checkCollectionElementsNotNull(permissions, "permissions");

                    request.put(packageName, permissions);
                }

                enforceCallingPermission(Manifest.permission.REVOKE_RUNTIME_PERMISSIONS, null);

                // Verify callerPackageName
                try {
                    PackageInfo pkgInfo = getPackageManager().getPackageInfo(callerPackageName, 0);
                    checkArgument(getCallingUid() == pkgInfo.applicationInfo.uid);
                } catch (PackageManager.NameNotFoundException e) {
                    throw new RuntimeException(e);
                }

                mHandler.sendMessage(obtainMessage(
                        PermissionControllerService::revokeRuntimePermissions,
                        PermissionControllerService.this, request, doDryRun, reason,
                        callerPackageName, callback));
            }

            @Override
            public void getRuntimePermissionBackup(UserHandle user, ParcelFileDescriptor pipe) {
                checkNotNull(user);
                checkNotNull(pipe);

                enforceCallingPermission(Manifest.permission.GET_RUNTIME_PERMISSIONS, null);

                mHandler.sendMessage(obtainMessage(
                        PermissionControllerService::getRuntimePermissionsBackup,
                        PermissionControllerService.this, user, pipe));
            }

            @Override
            public void restoreRuntimePermissionBackup(UserHandle user, ParcelFileDescriptor pipe) {
                checkNotNull(user);
                checkNotNull(pipe);

                enforceCallingPermission(Manifest.permission.GRANT_RUNTIME_PERMISSIONS, null);

                try (InputStream backup = new ParcelFileDescriptor.AutoCloseInputStream(pipe)) {
                    onRestoreRuntimePermissionsBackup(user, backup);
                } catch (IOException e) {
                    Log.e(LOG_TAG, "Could not open pipe to read backup from", e);
                }
            }

            @Override
            public void restoreDelayedRuntimePermissionBackup(String packageName, UserHandle user,
                    RemoteCallback callback) {
                checkNotNull(packageName);
                checkNotNull(user);
                checkNotNull(callback);

                enforceCallingPermission(Manifest.permission.GRANT_RUNTIME_PERMISSIONS, null);

                boolean hasMoreBackup = onRestoreDelayedRuntimePermissionsBackup(packageName, user);

                Bundle result = new Bundle();
                result.putBoolean(PermissionControllerManager.KEY_RESULT, hasMoreBackup);
                callback.sendResult(result);
            }

            @Override
            public void getAppPermissions(String packageName, RemoteCallback callback) {
                checkNotNull(packageName, "packageName");
                checkNotNull(callback, "callback");

                enforceCallingPermission(Manifest.permission.GET_RUNTIME_PERMISSIONS, null);

                mHandler.sendMessage(
                        obtainMessage(PermissionControllerService::getAppPermissions,
                                PermissionControllerService.this, packageName, callback));
            }

            @Override
            public void revokeRuntimePermission(String packageName, String permissionName) {
                checkNotNull(packageName, "packageName");
                checkNotNull(permissionName, "permissionName");

                enforceCallingPermission(Manifest.permission.REVOKE_RUNTIME_PERMISSIONS, null);

                mHandler.sendMessage(
                        obtainMessage(PermissionControllerService::onRevokeRuntimePermission,
                                PermissionControllerService.this, packageName, permissionName));
            }

            @Override
            public void countPermissionApps(List<String> permissionNames, int flags,
                    RemoteCallback callback) {
                checkCollectionElementsNotNull(permissionNames, "permissionNames");
                checkFlagsArgument(flags, COUNT_WHEN_SYSTEM | COUNT_ONLY_WHEN_GRANTED);
                checkNotNull(callback, "callback");

                enforceCallingPermission(Manifest.permission.GET_RUNTIME_PERMISSIONS, null);

                mHandler.sendMessage(
                        obtainMessage(PermissionControllerService::countPermissionApps,
                                PermissionControllerService.this, permissionNames, flags,
                                callback));
            }

            @Override
            public void getPermissionUsages(boolean countSystem, long numMillis,
                    RemoteCallback callback) {
                checkArgumentNonnegative(numMillis);
                checkNotNull(callback, "callback");

                enforceCallingPermission(Manifest.permission.GET_RUNTIME_PERMISSIONS, null);

                mHandler.sendMessage(
                        obtainMessage(PermissionControllerService::getPermissionUsages,
                                PermissionControllerService.this, countSystem, numMillis,
                                callback));
            }

            @Override
            public void isApplicationQualifiedForRole(String roleName, String packageName,
                    RemoteCallback callback) {
                checkStringNotEmpty(roleName);
                checkStringNotEmpty(packageName);
                checkNotNull(callback, "callback");

                enforceCallingPermission(Manifest.permission.MANAGE_ROLE_HOLDERS, null);

                mHandler.sendMessage(obtainMessage(
                        PermissionControllerService::isApplicationQualifiedForRole,
                        PermissionControllerService.this, roleName, packageName, callback));
            }

            @Override
            public void setRuntimePermissionGrantStateByDeviceAdmin(String callerPackageName,
                    String packageName, String permission, int grantState,
                    RemoteCallback callback) {
                checkStringNotEmpty(callerPackageName);
                checkStringNotEmpty(packageName);
                checkStringNotEmpty(permission);
                checkArgument(grantState == PERMISSION_GRANT_STATE_GRANTED
                        || grantState == PERMISSION_GRANT_STATE_DENIED
                        || grantState == PERMISSION_GRANT_STATE_DEFAULT);
                checkNotNull(callback);

                if (grantState == PERMISSION_GRANT_STATE_DENIED) {
                    enforceCallingPermission(Manifest.permission.GRANT_RUNTIME_PERMISSIONS, null);
                }

                if (grantState == PERMISSION_GRANT_STATE_DENIED) {
                    enforceCallingPermission(Manifest.permission.REVOKE_RUNTIME_PERMISSIONS, null);
                }

                enforceCallingPermission(Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY,
                        null);

                mHandler.sendMessage(obtainMessage(
                        PermissionControllerService::setRuntimePermissionGrantStateByDeviceAdmin,
                        PermissionControllerService.this, callerPackageName, packageName,
                        permission, grantState, callback));
            }
        };
    }

    private void revokeRuntimePermissions(@NonNull Map<String, List<String>> requests,
            boolean doDryRun, @PermissionControllerManager.Reason int reason,
            @NonNull String callerPackageName, @NonNull RemoteCallback callback) {
        Map<String, List<String>> revoked = onRevokeRuntimePermissions(requests,
                doDryRun, reason, callerPackageName);

        checkNotNull(revoked);
        Bundle bundledizedRevoked = new Bundle();
        for (Map.Entry<String, List<String>> appRevocation : revoked.entrySet()) {
            checkNotNull(appRevocation.getKey());
            checkCollectionElementsNotNull(appRevocation.getValue(), "permissions");

            bundledizedRevoked.putStringArrayList(appRevocation.getKey(),
                    new ArrayList<>(appRevocation.getValue()));
        }

        Bundle result = new Bundle();
        result.putBundle(PermissionControllerManager.KEY_RESULT, bundledizedRevoked);
        callback.sendResult(result);
    }

    private void getRuntimePermissionsBackup(@NonNull UserHandle user,
            @NonNull ParcelFileDescriptor backupFile) {
        try (OutputStream backup = new ParcelFileDescriptor.AutoCloseOutputStream(backupFile)) {
            onGetRuntimePermissionsBackup(user, backup);
        } catch (IOException e) {
            Log.e(LOG_TAG, "Could not open pipe to write backup to", e);
        }
    }

    private void getAppPermissions(@NonNull String packageName, @NonNull RemoteCallback callback) {
        List<RuntimePermissionPresentationInfo> permissions = onGetAppPermissions(packageName);
        if (permissions != null && !permissions.isEmpty()) {
            Bundle result = new Bundle();
            result.putParcelableList(PermissionControllerManager.KEY_RESULT, permissions);
            callback.sendResult(result);
        } else {
            callback.sendResult(null);
        }
    }

    private void countPermissionApps(@NonNull List<String> permissionNames,
            @CountPermissionAppsFlag int flags, @NonNull RemoteCallback callback) {
        int numApps = onCountPermissionApps(permissionNames, flags);

        Bundle result = new Bundle();
        result.putInt(PermissionControllerManager.KEY_RESULT, numApps);
        callback.sendResult(result);
    }

    private void getPermissionUsages(boolean countSystem, long numMillis,
            @NonNull RemoteCallback callback) {
        List<RuntimePermissionUsageInfo> users =
                onGetPermissionUsages(countSystem, numMillis);
        if (users != null && !users.isEmpty()) {
            Bundle result = new Bundle();
            result.putParcelableList(PermissionControllerManager.KEY_RESULT, users);
            callback.sendResult(result);
        } else {
            callback.sendResult(null);
        }
    }

    private void isApplicationQualifiedForRole(@NonNull String roleName,
            @NonNull String packageName, @NonNull RemoteCallback callback) {
        boolean qualified = onIsApplicationQualifiedForRole(roleName, packageName);
        Bundle result = new Bundle();
        result.putBoolean(PermissionControllerManager.KEY_RESULT, qualified);
        callback.sendResult(result);
    }

    private void setRuntimePermissionGrantStateByDeviceAdmin(@NonNull String callerPackageName,
            @NonNull String packageName, @NonNull String permission,
            @PermissionGrantState int grantState, @NonNull RemoteCallback callback) {
        boolean wasSet = onSetRuntimePermissionGrantStateByDeviceAdmin(callerPackageName,
                packageName, permission, grantState);

        Bundle result = new Bundle();
        result.putBoolean(PermissionControllerManager.KEY_RESULT, wasSet);
        callback.sendResult(result);
    }
}
