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

import static com.android.internal.util.Preconditions.checkArgument;
import static com.android.internal.util.Preconditions.checkArgumentNonnegative;
import static com.android.internal.util.Preconditions.checkCollectionElementsNotNull;
import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.Service;
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
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * This service is meant to be implemented by the app controlling permissions.
 *
 * @see PermissionController
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
     * @param out The stream to write the backup to
     */
    public abstract void onGetRuntimePermissionsBackup(@NonNull UserHandle user,
            @NonNull OutputStream out);

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
     * @param countOnlyGranted Count an app only if the permission is granted to the app
     * @param countSystem Also count system apps
     *
     * @return the number of apps that have one of the permissions
     */
    public abstract int onCountPermissionApps(@NonNull List<String> permissionNames,
            boolean countOnlyGranted, boolean countSystem);

    /**
     * Count how many apps have used permissions.
     *
     * @param countSystem Also count system apps
     * @param numMillis The number of milliseconds in the past to check for uses
     *
     * @return descriptions of the users of permissions
     */
    public abstract @NonNull List<RuntimePermissionUsageInfo>
            onPermissionUsageResult(boolean countSystem, long numMillis);

    @Override
    public final IBinder onBind(Intent intent) {
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
            public void countPermissionApps(List<String> permissionNames, boolean countOnlyGranted,
                    boolean countSystem, RemoteCallback callback) {
                checkCollectionElementsNotNull(permissionNames, "permissionNames");
                checkNotNull(callback, "callback");

                enforceCallingPermission(Manifest.permission.GET_RUNTIME_PERMISSIONS, null);

                mHandler.sendMessage(
                        obtainMessage(PermissionControllerService::countPermissionApps,
                                PermissionControllerService.this, permissionNames, countOnlyGranted,
                                countSystem, callback));
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
            @NonNull ParcelFileDescriptor outFile) {
        try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(outFile)) {
            onGetRuntimePermissionsBackup(user, out);
        } catch (IOException e) {
            Log.e(LOG_TAG, "Could not open pipe to write backup tp", e);
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
            boolean countOnlyGranted, boolean countSystem, @NonNull RemoteCallback callback) {
        int numApps = onCountPermissionApps(permissionNames, countOnlyGranted, countSystem);

        Bundle result = new Bundle();
        result.putInt(PermissionControllerManager.KEY_RESULT, numApps);
        callback.sendResult(result);
    }

    private void getPermissionUsages(boolean countSystem, long numMillis,
            @NonNull RemoteCallback callback) {
        List<RuntimePermissionUsageInfo> users =
                onPermissionUsageResult(countSystem, numMillis);
        if (users != null && !users.isEmpty()) {
            Bundle result = new Bundle();
            result.putParcelableList(PermissionControllerManager.KEY_RESULT, users);
            callback.sendResult(result);
        } else {
            callback.sendResult(null);
        }
    }
}
