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

import android.Manifest;
import android.annotation.BinderThread;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.Service;
import android.app.admin.DevicePolicyManager.PermissionGrantState;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
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
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

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

    /**
     * Revoke a set of runtime permissions for various apps.
     *
     * @param requests The permissions to revoke as {@code Map<packageName, List<permission>>}
     * @param doDryRun Compute the permissions that would be revoked, but not actually revoke them
     * @param reason Why the permission should be revoked
     * @param callerPackageName The package name of the calling app
     * @param callback Callback waiting for the actually removed permissions as
     * {@code Map<packageName, List<permission>>}
     */
    @BinderThread
    public abstract void onRevokeRuntimePermissions(
            @NonNull Map<String, List<String>> requests, boolean doDryRun,
            @PermissionControllerManager.Reason int reason, @NonNull String callerPackageName,
            @NonNull Consumer<Map<String, List<String>>> callback);

    /**
     * Create a backup of the runtime permissions.
     *
     * @param user The user to back up
     * @param backup The stream to write the backup to
     * @param callback Callback waiting for operation to be complete
     */
    @BinderThread
    public abstract void onGetRuntimePermissionsBackup(@NonNull UserHandle user,
            @NonNull OutputStream backup, @NonNull Runnable callback);

    /**
     * Restore a backup of the runtime permissions.
     *
     * <p>If an app mentioned in the backup is not installed the state should be saved to later
     * be restored via {@link #onRestoreDelayedRuntimePermissionsBackup}.
     *
     * @param user The user to restore
     * @param backup The stream to read the backup from
     * @param callback Callback waiting for operation to be complete
     */
    @BinderThread
    public abstract void onRestoreRuntimePermissionsBackup(@NonNull UserHandle user,
            @NonNull InputStream backup, @NonNull Runnable callback);

    /**
     * Restore the permission state of an app that was provided in
     * {@link #onRestoreRuntimePermissionsBackup} but could not be restored back then.
     *
     * @param packageName The app to restore
     * @param user The user to restore
     * @param callback Callback waiting for whether there is still delayed backup left
     */
    @BinderThread
    public abstract void onRestoreDelayedRuntimePermissionsBackup(@NonNull String packageName,
            @NonNull UserHandle user, @NonNull Consumer<Boolean> callback);

    /**
     * Gets the runtime permissions for an app.
     *
     * @param packageName The package for which to query.
     * @param callback Callback waiting for the descriptions of the runtime permissions of the app
     */
    @BinderThread
    public abstract void onGetAppPermissions(@NonNull String packageName,
            @NonNull Consumer<List<RuntimePermissionPresentationInfo>> callback);

    /**
     * Revokes the permission {@code permissionName} for app {@code packageName}
     *
     * @param packageName The package for which to revoke
     * @param permissionName The permission to revoke
     * @param callback Callback waiting for operation to be complete
     */
    @BinderThread
    public abstract void onRevokeRuntimePermission(@NonNull String packageName,
            @NonNull String permissionName, @NonNull Runnable callback);

    /**
     * Count how many apps have one of a set of permissions.
     *
     * @param permissionNames The permissions the app might have
     * @param flags Modify which apps to count. By default all non-system apps that request a
     *              permission are counted
     * @param callback Callback waiting for the number of apps that have one of the permissions
     */
    @BinderThread
    public abstract void onCountPermissionApps(@NonNull List<String> permissionNames,
            @CountPermissionAppsFlag int flags, @NonNull IntConsumer callback);

    /**
     * Count how many apps have used permissions.
     *
     * @param countSystem Also count system apps
     * @param numMillis The number of milliseconds in the past to check for uses
     * @param callback Callback waiting for the descriptions of the users of permissions
     */
    @BinderThread
    public abstract void onGetPermissionUsages(boolean countSystem, long numMillis,
            @NonNull Consumer<List<RuntimePermissionUsageInfo>> callback);

    /**
     * Grant or upgrade runtime permissions. The upgrade could be performed
     * based on whether the device upgraded, whether the permission database
     * version is old, or because the permission policy changed.
     *
     * @param callback Callback waiting for operation to be complete
     *
     * @see PackageManager#isDeviceUpgrading()
     * @see PermissionManager#getRuntimePermissionsVersion()
     * @see PermissionManager#setRuntimePermissionsVersion(int)
     */
    @BinderThread
    public abstract void onGrantOrUpgradeDefaultRuntimePermissions(@NonNull Runnable callback);

    /**
     * Set the runtime permission state from a device admin.
     *
     * @param callerPackageName The package name of the admin requesting the change
     * @param packageName Package the permission belongs to
     * @param permission Permission to change
     * @param grantState State to set the permission into
     * @param callback Callback waiting for whether the state could be set or not
     */
    @BinderThread
    public abstract void onSetRuntimePermissionGrantStateByDeviceAdmin(
            @NonNull String callerPackageName, @NonNull String packageName,
            @NonNull String permission, @PermissionGrantState int grantState,
            @NonNull Consumer<Boolean> callback);

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

                onRevokeRuntimePermissions(request,
                        doDryRun, reason, callerPackageName, revoked -> {
                            checkNotNull(revoked);
                            Bundle bundledizedRevoked = new Bundle();
                            for (Map.Entry<String, List<String>> appRevocation :
                                    revoked.entrySet()) {
                                checkNotNull(appRevocation.getKey());
                                checkCollectionElementsNotNull(appRevocation.getValue(),
                                        "permissions");

                                bundledizedRevoked.putStringArrayList(appRevocation.getKey(),
                                        new ArrayList<>(appRevocation.getValue()));
                            }

                            Bundle result = new Bundle();
                            result.putBundle(PermissionControllerManager.KEY_RESULT,
                                    bundledizedRevoked);
                            callback.sendResult(result);
                        });
            }

            @Override
            public void getRuntimePermissionBackup(UserHandle user, ParcelFileDescriptor pipe) {
                checkNotNull(user);
                checkNotNull(pipe);

                enforceCallingPermission(Manifest.permission.GET_RUNTIME_PERMISSIONS, null);

                try (OutputStream backup = new ParcelFileDescriptor.AutoCloseOutputStream(pipe)) {
                    CountDownLatch latch = new CountDownLatch(1);
                    onGetRuntimePermissionsBackup(user, backup, latch::countDown);
                    latch.await();
                } catch (IOException e) {
                    Log.e(LOG_TAG, "Could not open pipe to write backup to", e);
                } catch (InterruptedException e) {
                    Log.e(LOG_TAG, "getRuntimePermissionBackup timed out", e);
                }
            }

            @Override
            public void restoreRuntimePermissionBackup(UserHandle user, ParcelFileDescriptor pipe) {
                checkNotNull(user);
                checkNotNull(pipe);

                enforceCallingPermission(Manifest.permission.GRANT_RUNTIME_PERMISSIONS, null);

                try (InputStream backup = new ParcelFileDescriptor.AutoCloseInputStream(pipe)) {
                    CountDownLatch latch = new CountDownLatch(1);
                    onRestoreRuntimePermissionsBackup(user, backup, latch::countDown);
                    latch.await();
                } catch (IOException e) {
                    Log.e(LOG_TAG, "Could not open pipe to read backup from", e);
                } catch (InterruptedException e) {
                    Log.e(LOG_TAG, "restoreRuntimePermissionBackup timed out", e);
                }
            }

            @Override
            public void restoreDelayedRuntimePermissionBackup(String packageName, UserHandle user,
                    RemoteCallback callback) {
                checkNotNull(packageName);
                checkNotNull(user);
                checkNotNull(callback);

                enforceCallingPermission(Manifest.permission.GRANT_RUNTIME_PERMISSIONS, null);

                onRestoreDelayedRuntimePermissionsBackup(packageName, user,
                        hasMoreBackup -> {
                            Bundle result = new Bundle();
                            result.putBoolean(PermissionControllerManager.KEY_RESULT,
                                    hasMoreBackup);
                            callback.sendResult(result);
                        });
            }

            @Override
            public void getAppPermissions(String packageName, RemoteCallback callback) {
                checkNotNull(packageName, "packageName");
                checkNotNull(callback, "callback");

                enforceCallingPermission(Manifest.permission.GET_RUNTIME_PERMISSIONS, null);

                onGetAppPermissions(packageName,
                        permissions -> {
                            if (permissions != null && !permissions.isEmpty()) {
                                Bundle result = new Bundle();
                                result.putParcelableList(PermissionControllerManager.KEY_RESULT,
                                        permissions);
                                callback.sendResult(result);
                            } else {
                                callback.sendResult(null);
                            }
                        });
            }

            @Override
            public void revokeRuntimePermission(String packageName, String permissionName) {
                checkNotNull(packageName, "packageName");
                checkNotNull(permissionName, "permissionName");

                enforceCallingPermission(Manifest.permission.REVOKE_RUNTIME_PERMISSIONS, null);

                CountDownLatch latch = new CountDownLatch(1);
                PermissionControllerService.this.onRevokeRuntimePermission(packageName,
                        permissionName, latch::countDown);
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    Log.e(LOG_TAG, "revokeRuntimePermission timed out", e);
                }
            }

            @Override
            public void countPermissionApps(List<String> permissionNames, int flags,
                    RemoteCallback callback) {
                checkCollectionElementsNotNull(permissionNames, "permissionNames");
                checkFlagsArgument(flags, COUNT_WHEN_SYSTEM | COUNT_ONLY_WHEN_GRANTED);
                checkNotNull(callback, "callback");

                enforceCallingPermission(Manifest.permission.GET_RUNTIME_PERMISSIONS, null);

                onCountPermissionApps(permissionNames, flags, numApps -> {
                    Bundle result = new Bundle();
                    result.putInt(PermissionControllerManager.KEY_RESULT, numApps);
                    callback.sendResult(result);
                });
            }

            @Override
            public void getPermissionUsages(boolean countSystem, long numMillis,
                    RemoteCallback callback) {
                checkArgumentNonnegative(numMillis);
                checkNotNull(callback, "callback");

                enforceCallingPermission(Manifest.permission.GET_RUNTIME_PERMISSIONS, null);

                onGetPermissionUsages(countSystem, numMillis, users -> {
                    if (users != null && !users.isEmpty()) {
                        Bundle result = new Bundle();
                        result.putParcelableList(PermissionControllerManager.KEY_RESULT, users);
                        callback.sendResult(result);
                    } else {
                        callback.sendResult(null);
                    }
                });
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

                onSetRuntimePermissionGrantStateByDeviceAdmin(callerPackageName,
                        packageName, permission, grantState, wasSet -> {
                            Bundle result = new Bundle();
                            result.putBoolean(PermissionControllerManager.KEY_RESULT, wasSet);
                            callback.sendResult(result);
                        });
            }

            @Override
            public void grantOrUpgradeDefaultRuntimePermissions(@NonNull RemoteCallback callback) {
                checkNotNull(callback, "callback");

                enforceCallingPermission(Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY,
                        null);

                onGrantOrUpgradeDefaultRuntimePermissions(() -> callback.sendResult(Bundle.EMPTY));
            }
        };
    }
}
