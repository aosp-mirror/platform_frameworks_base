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
import static android.permission.PermissionControllerService.SERVICE_INTERFACE;

import static com.android.internal.util.Preconditions.checkArgument;
import static com.android.internal.util.Preconditions.checkArgumentNonnegative;
import static com.android.internal.util.Preconditions.checkCollectionElementsNotNull;
import static com.android.internal.util.Preconditions.checkFlagsArgument;
import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.internal.util.Preconditions.checkStringNotEmpty;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.app.ActivityThread;
import android.app.admin.DevicePolicyManager.PermissionGrantState;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.infra.AndroidFuture;
import com.android.internal.infra.RemoteStream;
import com.android.internal.infra.ServiceConnector;
import com.android.internal.util.CollectionUtils;

import libcore.util.EmptyArray;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Interface for communicating with the permission controller.
 *
 * @hide
 */
@TestApi
@SystemApi
@SystemService(Context.PERMISSION_CONTROLLER_SERVICE)
public final class PermissionControllerManager {
    private static final String TAG = PermissionControllerManager.class.getSimpleName();

    private static final long UNBIND_TIMEOUT_MILLIS = 10000;
    private static final int CHUNK_SIZE = 4 * 1024;

    private static final Object sLock = new Object();

    /**
     * Global remote services (per user) used by all {@link PermissionControllerManager managers}
     */
    @GuardedBy("sLock")
    private static ArrayMap<Pair<Integer, Thread>, ServiceConnector<IPermissionController>>
            sRemoteServices = new ArrayMap<>(1);

    /** @hide */
    @IntDef(prefix = { "REASON_" }, value = {
            REASON_MALWARE,
            REASON_INSTALLER_POLICY_VIOLATION,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Reason {}

    /** The permissions are revoked because the apps holding the permissions are malware */
    public static final int REASON_MALWARE = 1;

    /**
     * The permissions are revoked because the apps holding the permissions violate a policy of the
     * app that installed it.
     *
     * <p>If this reason is used only permissions of apps that are installed by the caller of the
     * API can be revoked.
     */
    public static final int REASON_INSTALLER_POLICY_VIOLATION = 2;

    /** @hide */
    @IntDef(prefix = { "COUNT_" }, value = {
            COUNT_ONLY_WHEN_GRANTED,
            COUNT_WHEN_SYSTEM,
    }, flag = true)
    @Retention(RetentionPolicy.SOURCE)
    public @interface CountPermissionAppsFlag {}

    /** Count an app only if the permission is granted to the app. */
    public static final int COUNT_ONLY_WHEN_GRANTED = 1;

    /** Count and app even if it is a system app. */
    public static final int COUNT_WHEN_SYSTEM = 2;

    /**
     * Callback for delivering the result of {@link #revokeRuntimePermissions}.
     */
    public abstract static class OnRevokeRuntimePermissionsCallback {
        /**
         * The result for {@link #revokeRuntimePermissions}.
         *
         * @param revoked The actually revoked permissions as
         *                {@code Map<packageName, List<permission>>}
         */
        public abstract void onRevokeRuntimePermissions(@NonNull Map<String, List<String>> revoked);
    }

    /**
     * Callback for delivering the result of {@link #getRuntimePermissionBackup}.
     *
     * @hide
     */
    public interface OnGetRuntimePermissionBackupCallback {
        /**
         * The result for {@link #getRuntimePermissionBackup}.
         *
         * @param backup The backup file
         */
        void onGetRuntimePermissionsBackup(@NonNull byte[] backup);
    }

    /**
     * Callback for delivering the result of {@link #getAppPermissions}.
     *
     * @hide
     */
    @TestApi
    public interface OnGetAppPermissionResultCallback {
        /**
         * The result for {@link #getAppPermissions(String, OnGetAppPermissionResultCallback,
         * Handler)}.
         *
         * @param permissions The permissions list.
         */
        void onGetAppPermissions(@NonNull List<RuntimePermissionPresentationInfo> permissions);
    }

    /**
     * Callback for delivering the result of {@link #countPermissionApps}.
     *
     * @hide
     */
    public interface OnCountPermissionAppsResultCallback {
        /**
         * The result for {@link #countPermissionApps(List, int,
         * OnCountPermissionAppsResultCallback, Handler)}.
         *
         * @param numApps The number of apps that have one of the permissions
         */
        void onCountPermissionApps(int numApps);
    }

    /**
     * Callback for delivering the result of {@link #getPermissionUsages}.
     *
     * @hide
     */
    public interface OnPermissionUsageResultCallback {
        /**
         * The result for {@link #getPermissionUsages}.
         *
         * @param users The users list.
         */
        void onPermissionUsageResult(@NonNull List<RuntimePermissionUsageInfo> users);
    }

    private final @NonNull Context mContext;
    private final @NonNull ServiceConnector<IPermissionController> mRemoteService;
    private final @NonNull Handler mHandler;

    /**
     * Create a new {@link PermissionControllerManager}.
     *
     * @param context to create the manager for
     * @param handler handler to schedule work
     *
     * @hide
     */
    public PermissionControllerManager(@NonNull Context context, @NonNull Handler handler) {
        synchronized (sLock) {
            Pair<Integer, Thread> key = new Pair<>(context.getUserId(),
                    handler.getLooper().getThread());
            ServiceConnector<IPermissionController> remoteService = sRemoteServices.get(key);
            if (remoteService == null) {
                Intent intent = new Intent(SERVICE_INTERFACE);
                intent.setPackage(context.getPackageManager().getPermissionControllerPackageName());
                ResolveInfo serviceInfo = context.getPackageManager().resolveService(intent, 0);
                remoteService = new ServiceConnector.Impl<IPermissionController>(
                        ActivityThread.currentApplication() /* context */,
                        new Intent(SERVICE_INTERFACE)
                                .setComponent(serviceInfo.getComponentInfo().getComponentName()),
                        0 /* bindingFlags */, context.getUserId(),
                        IPermissionController.Stub::asInterface) {

                    @Override
                    protected Handler getJobHandler() {
                        return handler;
                    }

                    @Override
                    protected long getAutoDisconnectTimeoutMs() {
                        return UNBIND_TIMEOUT_MILLIS;
                    }
                };
                sRemoteServices.put(key, remoteService);
            }

            mRemoteService = remoteService;
        }

        mContext = context;
        mHandler = handler;
    }

    /**
     * Revoke a set of runtime permissions for various apps.
     *
     * @param request The permissions to revoke as {@code Map<packageName, List<permission>>}
     * @param doDryRun Compute the permissions that would be revoked, but not actually revoke them
     * @param reason Why the permission should be revoked
     * @param executor Executor on which to invoke the callback
     * @param callback Callback to receive the result
     */
    @RequiresPermission(Manifest.permission.REVOKE_RUNTIME_PERMISSIONS)
    public void revokeRuntimePermissions(@NonNull Map<String, List<String>> request,
            boolean doDryRun, @Reason int reason, @NonNull @CallbackExecutor Executor executor,
            @NonNull OnRevokeRuntimePermissionsCallback callback) {
        // Check input to fail immediately instead of inside the async request
        checkNotNull(executor);
        checkNotNull(callback);
        checkNotNull(request);
        for (Map.Entry<String, List<String>> appRequest : request.entrySet()) {
            checkNotNull(appRequest.getKey());
            checkCollectionElementsNotNull(appRequest.getValue(), "permissions");
        }

        // Check required permission to fail immediately instead of inside the oneway binder call
        if (mContext.checkSelfPermission(Manifest.permission.REVOKE_RUNTIME_PERMISSIONS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(Manifest.permission.REVOKE_RUNTIME_PERMISSIONS
                    + " required");
        }

        mRemoteService.postAsync(service -> {
            Bundle bundledizedRequest = new Bundle();
            for (Map.Entry<String, List<String>> appRequest : request.entrySet()) {
                bundledizedRequest.putStringArrayList(appRequest.getKey(),
                        new ArrayList<>(appRequest.getValue()));
            }

            AndroidFuture<Map<String, List<String>>> revokeRuntimePermissionsResult =
                    new AndroidFuture<>();
            service.revokeRuntimePermissions(bundledizedRequest, doDryRun, reason,
                    mContext.getPackageName(),
                    revokeRuntimePermissionsResult);
            return revokeRuntimePermissionsResult;
        }).whenCompleteAsync((revoked, err) -> {
            long token = Binder.clearCallingIdentity();
            try {
                if (err != null) {
                    Log.e(TAG, "Failure when revoking runtime permissions " + revoked, err);
                    callback.onRevokeRuntimePermissions(Collections.emptyMap());
                } else {
                    callback.onRevokeRuntimePermissions(revoked);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }, executor);
    }

    /**
     * Set the runtime permission state from a device admin.
     *
     * @param callerPackageName The package name of the admin requesting the change
     * @param packageName Package the permission belongs to
     * @param permission Permission to change
     * @param grantState State to set the permission into
     * @param executor Executor to run the {@code callback} on
     * @param callback The callback
     *
     * @hide
     */
    @RequiresPermission(allOf = {Manifest.permission.GRANT_RUNTIME_PERMISSIONS,
            Manifest.permission.REVOKE_RUNTIME_PERMISSIONS,
            Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY},
            conditional = true)
    public void setRuntimePermissionGrantStateByDeviceAdmin(@NonNull String callerPackageName,
            @NonNull String packageName, @NonNull String permission,
            @PermissionGrantState int grantState, @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<Boolean> callback) {
        checkStringNotEmpty(callerPackageName);
        checkStringNotEmpty(packageName);
        checkStringNotEmpty(permission);
        checkArgument(grantState == PERMISSION_GRANT_STATE_GRANTED
                || grantState == PERMISSION_GRANT_STATE_DENIED
                || grantState == PERMISSION_GRANT_STATE_DEFAULT);
        checkNotNull(executor);
        checkNotNull(callback);

        mRemoteService.postAsync(service -> {
            AndroidFuture<Boolean> setRuntimePermissionGrantStateResult = new AndroidFuture<>();
            service.setRuntimePermissionGrantStateByDeviceAdmin(
                    callerPackageName, packageName, permission, grantState,
                    setRuntimePermissionGrantStateResult);
            return setRuntimePermissionGrantStateResult;
        }).whenCompleteAsync((setRuntimePermissionGrantStateResult, err) -> {
            long token = Binder.clearCallingIdentity();
            try {
                if (err != null) {
                    Log.e(TAG, "Error setting permissions state for device admin " + packageName,
                            err);
                    callback.accept(false);
                } else {
                    callback.accept(Boolean.TRUE.equals(setRuntimePermissionGrantStateResult));
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }, executor);
    }

    /**
     * Create a backup of the runtime permissions.
     *
     * @param user The user to be backed up
     * @param executor Executor on which to invoke the callback
     * @param callback Callback to receive the result
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.GET_RUNTIME_PERMISSIONS)
    public void getRuntimePermissionBackup(@NonNull UserHandle user,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnGetRuntimePermissionBackupCallback callback) {
        checkNotNull(user);
        checkNotNull(executor);
        checkNotNull(callback);

        mRemoteService.postAsync(service -> RemoteStream.receiveBytes(remotePipe -> {
            service.getRuntimePermissionBackup(user, remotePipe);
        })).whenCompleteAsync((bytes, err) -> {
            if (err != null) {
                Log.e(TAG, "Error getting permission backup", err);
                callback.onGetRuntimePermissionsBackup(EmptyArray.BYTE);
            } else {
                callback.onGetRuntimePermissionsBackup(bytes);
            }
        }, executor);
    }

    /**
     * Restore a backup of the runtime permissions.
     *
     * @param backup the backup to restore. The backup is sent asynchronously, hence it should not
     *               be modified after calling this method.
     * @param user The user to be restore
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.GRANT_RUNTIME_PERMISSIONS)
    public void restoreRuntimePermissionBackup(@NonNull byte[] backup, @NonNull UserHandle user) {
        checkNotNull(backup);
        checkNotNull(user);

        mRemoteService.postAsync(service -> RemoteStream.sendBytes(remotePipe -> {
            service.restoreRuntimePermissionBackup(user, remotePipe);
        }, backup))
                .whenComplete((nullResult, err) -> {
                    if (err != null) {
                        Log.e(TAG, "Error sending permission backup", err);
                    }
                });
    }

    /**
     * Restore a backup of the runtime permissions that has been delayed.
     *
     * @param packageName The package that is ready to have it's permissions restored.
     * @param user The user to restore
     * @param executor Executor to execute the callback on
     * @param callback Is called with {@code true} iff there is still more delayed backup left
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.GRANT_RUNTIME_PERMISSIONS)
    public void restoreDelayedRuntimePermissionBackup(@NonNull String packageName,
            @NonNull UserHandle user,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<Boolean> callback) {
        checkNotNull(packageName);
        checkNotNull(user);
        checkNotNull(executor);
        checkNotNull(callback);

        mRemoteService.postAsync(service -> {
            AndroidFuture<Boolean> restoreDelayedRuntimePermissionBackupResult =
                    new AndroidFuture<>();
            service.restoreDelayedRuntimePermissionBackup(packageName, user,
                    restoreDelayedRuntimePermissionBackupResult);
            return restoreDelayedRuntimePermissionBackupResult;
        }).whenCompleteAsync((restoreDelayedRuntimePermissionBackupResult, err) -> {
            long token = Binder.clearCallingIdentity();
            try {
                if (err != null) {
                    Log.e(TAG, "Error restoring delayed permissions for " + packageName, err);
                    callback.accept(true);
                } else {
                    callback.accept(
                            Boolean.TRUE.equals(restoreDelayedRuntimePermissionBackupResult));
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }, executor);
    }

    /**
     * Gets the runtime permissions for an app.
     *
     * @param packageName The package for which to query.
     * @param callback Callback to receive the result.
     * @param handler Handler on which to invoke the callback.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.GET_RUNTIME_PERMISSIONS)
    public void getAppPermissions(@NonNull String packageName,
            @NonNull OnGetAppPermissionResultCallback callback, @Nullable Handler handler) {
        checkNotNull(packageName);
        checkNotNull(callback);
        Handler finalHandler = handler != null ? handler : mHandler;

        mRemoteService.postAsync(service -> {
            AndroidFuture<List<RuntimePermissionPresentationInfo>> getAppPermissionsResult =
                    new AndroidFuture<>();
            service.getAppPermissions(packageName, getAppPermissionsResult);
            return getAppPermissionsResult;
        }).whenComplete((getAppPermissionsResult, err) -> finalHandler.post(() -> {
            if (err != null) {
                Log.e(TAG, "Error getting app permission", err);
                callback.onGetAppPermissions(Collections.emptyList());
            } else {
                callback.onGetAppPermissions(CollectionUtils.emptyIfNull(getAppPermissionsResult));
            }
        }));
    }

    /**
     * Revoke the permission {@code permissionName} for app {@code packageName}
     *
     * @param packageName The package for which to revoke
     * @param permissionName The permission to revoke
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.REVOKE_RUNTIME_PERMISSIONS)
    public void revokeRuntimePermission(@NonNull String packageName,
            @NonNull String permissionName) {
        checkNotNull(packageName);
        checkNotNull(permissionName);

        mRemoteService.run(service -> service.revokeRuntimePermission(packageName, permissionName));
    }

    /**
     * Count how many apps have one of a set of permissions.
     *
     * @param permissionNames The permissions the app might have
     * @param flags Modify which apps to count. By default all non-system apps that request a
     *              permission are counted
     * @param callback Callback to receive the result
     * @param handler Handler on which to invoke the callback
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.GET_RUNTIME_PERMISSIONS)
    public void countPermissionApps(@NonNull List<String> permissionNames,
            @CountPermissionAppsFlag int flags,
            @NonNull OnCountPermissionAppsResultCallback callback, @Nullable Handler handler) {
        checkCollectionElementsNotNull(permissionNames, "permissionNames");
        checkFlagsArgument(flags, COUNT_WHEN_SYSTEM | COUNT_ONLY_WHEN_GRANTED);
        checkNotNull(callback);
        Handler finalHandler = handler != null ? handler : mHandler;

        mRemoteService.postAsync(service -> {
            AndroidFuture<Integer> countPermissionAppsResult = new AndroidFuture<>();
            service.countPermissionApps(permissionNames, flags, countPermissionAppsResult);
            return countPermissionAppsResult;
        }).whenComplete((countPermissionAppsResult, err) -> finalHandler.post(() -> {
            if (err != null) {
                Log.e(TAG, "Error counting permission apps", err);
                callback.onCountPermissionApps(0);
            } else {
                callback.onCountPermissionApps(countPermissionAppsResult);
            }
        }));
    }

    /**
     * Count how many apps have used permissions.
     *
     * @param countSystem Also count system apps
     * @param numMillis The number of milliseconds in the past to check for uses
     * @param executor Executor on which to invoke the callback
     * @param callback Callback to receive the result
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.GET_RUNTIME_PERMISSIONS)
    public void getPermissionUsages(boolean countSystem, long numMillis,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnPermissionUsageResultCallback callback) {
        checkArgumentNonnegative(numMillis);
        checkNotNull(executor);
        checkNotNull(callback);


        mRemoteService.postAsync(service -> {
            AndroidFuture<List<RuntimePermissionUsageInfo>> getPermissionUsagesResult =
                    new AndroidFuture<>();
            service.getPermissionUsages(countSystem, numMillis, getPermissionUsagesResult);
            return getPermissionUsagesResult;
        }).whenCompleteAsync((getPermissionUsagesResult, err) -> {
            if (err != null) {
                Log.e(TAG, "Error getting permission usages", err);
                callback.onPermissionUsageResult(Collections.emptyList());
            } else {
                long token = Binder.clearCallingIdentity();
                try {
                    callback.onPermissionUsageResult(
                            CollectionUtils.emptyIfNull(getPermissionUsagesResult));
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        }, executor);
    }

    /**
     * Grant or upgrade runtime permissions. The upgrade could be performed
     * based on whether the device upgraded, whether the permission database
     * version is old, or because the permission policy changed.
     *
     * @param executor Executor on which to invoke the callback
     * @param callback Callback to receive the result
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY)
    public void grantOrUpgradeDefaultRuntimePermissions(
            @NonNull @CallbackExecutor Executor executor, @NonNull Consumer<Boolean> callback) {
        mRemoteService.postAsync(service -> {
            AndroidFuture<Boolean> grantOrUpgradeDefaultRuntimePermissionsResult =
                    new AndroidFuture<>();
            service.grantOrUpgradeDefaultRuntimePermissions(
                    grantOrUpgradeDefaultRuntimePermissionsResult);
            return grantOrUpgradeDefaultRuntimePermissionsResult;
        }).whenCompleteAsync((grantOrUpgradeDefaultRuntimePermissionsResult, err) -> {
            if (err != null) {
                Log.e(TAG, "Error granting or upgrading runtime permissions", err);
                callback.accept(false);
            } else {
                callback.accept(Boolean.TRUE.equals(grantOrUpgradeDefaultRuntimePermissionsResult));
            }
        }, executor);
    }

    /**
     * @see PermissionControllerService#onUpdateUserSensitive()
     * @hide
     */
    public void updateUserSensitive() {
        mRemoteService.postAsync(service -> {
            AndroidFuture<Void> future = new AndroidFuture<>();
            service.updateUserSensitive(future);
            return future;
        });
    }
}
