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

import static com.android.internal.util.Preconditions.checkCollectionElementsNotNull;
import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteCallback;

import java.util.List;

/**
 * This service is meant to be implemented by the app controlling permissions.
 *
 * @see PermissionController
 *
 * @hide
 */
@SystemApi
public abstract class PermissionControllerService extends Service {

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

    @Override
    public final IBinder onBind(Intent intent) {
        return new IPermissionController.Stub() {
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
        };
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
}
