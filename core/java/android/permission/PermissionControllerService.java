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
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.app.Service;
import android.app.admin.DevicePolicyManager.PermissionGrantState;
import android.compat.annotation.ChangeId;
import android.compat.annotation.Disabled;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.permission.PermissionControllerManager.CountPermissionAppsFlag;
import android.permission.flags.Flags;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.infra.AndroidFuture;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.Preconditions;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
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
     * A ChangeId indicating that this device supports camera and mic indicators. Will be "false"
     * if present, because the CompatChanges#isChangeEnabled method returns true if the change id
     * is not present.
     */
    @ChangeId
    @Disabled
    private static final long CAMERA_MIC_INDICATORS_NOT_PRESENT = 162547999L;

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
     * @deprecated Implement {@link #onStageAndApplyRuntimePermissionsBackup} instead
     */
    @Deprecated
    @BinderThread
    public void onRestoreRuntimePermissionsBackup(@NonNull UserHandle user,
            @NonNull InputStream backup, @NonNull Runnable callback) {
    }

    /**
     * Restore a backup of the runtime permissions.
     *
     * <p>If an app mentioned in the backup is not installed the state should be saved to later
     * be restored via {@link #onApplyStagedRuntimePermissionBackup}.
     *
     * @param user The user to restore
     * @param backup The stream to read the backup from
     * @param callback Callback waiting for operation to be complete
     */
    @BinderThread
    public void onStageAndApplyRuntimePermissionsBackup(@NonNull UserHandle user,
            @NonNull InputStream backup, @NonNull Runnable callback) {
        onRestoreRuntimePermissionsBackup(user, backup, callback);
    }

    /**
     * @deprecated Implement {@link #onApplyStagedRuntimePermissionBackup} instead
     */
    @Deprecated
    @BinderThread
    public void onRestoreDelayedRuntimePermissionsBackup(@NonNull String packageName,
            @NonNull UserHandle user, @NonNull Consumer<Boolean> callback) {
    }

    /**
     * Restore the permission state of an app that was provided in
     * {@link #onStageAndApplyRuntimePermissionsBackup} but could not be restored back then.
     *
     * @param packageName The app to restore
     * @param user The user to restore
     * @param callback Callback waiting for whether there is still delayed backup left
     */
    @BinderThread
    public void onApplyStagedRuntimePermissionBackup(@NonNull String packageName,
            @NonNull UserHandle user, @NonNull Consumer<Boolean> callback) {
        onRestoreDelayedRuntimePermissionsBackup(packageName, user, callback);
    }

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
     * version is old, because the permission policy changed, or because the
     * permission controller has updated.
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
     * Called by system to update the
     * {@link PackageManager}{@code .FLAG_PERMISSION_USER_SENSITIVE_WHEN_*} flags for permissions.
     * <p>
     *
     * If uid is -1, updates the permission flags for all packages.
     *
     * Typically called by the system when a new app is installed or updated or when creating a
     * new user or upgrading either system or permission controller package.
     *
     * The callback will be executed by the provided Executor.
     */
    @BinderThread
    public void onUpdateUserSensitivePermissionFlags(int uid, @NonNull Executor executor,
            @NonNull Runnable callback) {
        throw new AbstractMethodError("Must be overridden in implementing class");
    }

    /**
     * Runs {@link #onUpdateUserSensitivePermissionFlags(int, Executor, Runnable)} with the main
     * executor.
     */
    @BinderThread
    public void onUpdateUserSensitivePermissionFlags(int uid, @NonNull Runnable callback) {
        onUpdateUserSensitivePermissionFlags(uid, getMainExecutor(), callback);
    }

    /**
     * @deprecated See {@link #onSetRuntimePermissionGrantStateByDeviceAdmin(String,
     * AdminPermissionControlParams, Consumer)}.
     * Set the runtime permission state from a device admin.
     *
     * @param callerPackageName The package name of the admin requesting the change
     * @param packageName Package the permission belongs to
     * @param permission Permission to change
     * @param grantState State to set the permission into
     * @param callback Callback waiting for whether the state could be set or not
     */
    @Deprecated
    @BinderThread
    public abstract void onSetRuntimePermissionGrantStateByDeviceAdmin(
            @NonNull String callerPackageName, @NonNull String packageName,
            @NonNull String permission, @PermissionGrantState int grantState,
            @NonNull Consumer<Boolean> callback);

    /**
     * Set the runtime permission state from a device admin.
     *
     * @param callerPackageName The package name of the admin requesting the change
     * @param params Parameters of admin request.
     * @param callback Callback waiting for whether the state could be set or not
     */
    @BinderThread
    public void onSetRuntimePermissionGrantStateByDeviceAdmin(
            @NonNull String callerPackageName, @NonNull AdminPermissionControlParams params,
            @NonNull Consumer<Boolean> callback) {
        throw new AbstractMethodError("Must be overridden in implementing class");
    }

    /**
     * Called when a package is considered inactive based on the criteria given by
     * {@link PermissionManager#startOneTimePermissionSession(String, long, long, int, int)}.
     * This method is called at the end of a one-time permission session
     *
     * @param packageName The package that has been inactive
     *
     * @deprecated Implement {@link #onOneTimePermissionSessionTimeout(String, int)} instead.
     */
    @Deprecated
    @BinderThread
    public void onOneTimePermissionSessionTimeout(@NonNull String packageName) {
        throw new AbstractMethodError("Must be overridden in implementing class");
    }

    /**
     * Called when a package is considered inactive based on the criteria given by
     * {@link PermissionManager#startOneTimePermissionSession(String, long, long, int, int)}.
     * This method is called at the end of a one-time permission session
     *
     * @param packageName The package that has been inactive
     * @param deviceId The device ID refers either the primary device i.e. the phone or
     *                 a virtual device. See {@link Context#DEVICE_ID_DEFAULT}
     */
    @BinderThread
    @FlaggedApi(Flags.FLAG_DEVICE_AWARE_PERMISSION_APIS_ENABLED)
    public void onOneTimePermissionSessionTimeout(@NonNull String packageName,
            int deviceId) {
        onOneTimePermissionSessionTimeout(packageName);
    }

    /**
     * Get the platform permissions which belong to a particular permission group
     *
     * @param permissionGroupName The permission group whose permissions are desired
     * @param callback A callback the permission names will be passed to
     */
    @BinderThread
    public void onGetPlatformPermissionsForGroup(@NonNull String permissionGroupName,
            @NonNull Consumer<List<String>> callback) {
        throw new AbstractMethodError("Must be overridden in implementing class");
    }

    /**
     * Get the platform group of a particular permission, if the permission is a platform permission
     *
     * @param permissionName The permission name whose group is desired
     * @param callback A callback the group name will be passed to
     */
    @BinderThread
    public void onGetGroupOfPlatformPermission(@NonNull String permissionName,
            @NonNull Consumer<String> callback) {
        throw new AbstractMethodError("Must be overridden in implementing class");
    }

    /**
     * Triggers the revocation of one or more permissions for a package. This should only be called
     * at the request of {@code packageName}.
     * <p>
     * Background permissions which have no corresponding foreground permission still granted once
     * the revocation is effective will also be revoked.
     * <p>
     * This revocation happens asynchronously and kills all processes running in the same UID as
     * {@code packageName}. It will be triggered once it is safe to do so.
     *
     * @param packageName The name of the package for which the permissions will be revoked.
     * @param permissions List of permissions to be revoked.
     * @param callback Callback waiting for operation to be complete.
     *
     * @see android.content.Context#revokeSelfPermissionsOnKill(java.util.Collection)
     *
     * @deprecated Implement {@link #onRevokeSelfPermissionsOnKill(String, List, int, Runnable)}
     * instead.
     */
    @Deprecated
    @BinderThread
    public void onRevokeSelfPermissionsOnKill(@NonNull String packageName,
            @NonNull List<String> permissions, @NonNull Runnable callback) {
        throw new AbstractMethodError("Must be overridden in implementing class");
    }

    /**
     * Triggers the revocation of one or more permissions for a package and device.
     * This should only be called at the request of {@code packageName}.
     * <p>
     * Background permissions which have no corresponding foreground permission still granted once
     * the revocation is effective will also be revoked.
     * <p>
     * This revocation happens asynchronously and kills all processes running in the same UID as
     * {@code packageName}. It will be triggered once it is safe to do so.
     *
     * @param packageName The name of the package for which the permissions will be revoked.
     * @param permissions List of permissions to be revoked.
     * @param deviceId The device ID refers either the primary device i.e. the phone or
     *                 a virtual device. See {@link Context#DEVICE_ID_DEFAULT}
     * @param callback Callback waiting for operation to be complete.
     *
     * @see android.content.Context#revokeSelfPermissionsOnKill(java.util.Collection)
     */
    @BinderThread
    @FlaggedApi(Flags.FLAG_DEVICE_AWARE_PERMISSION_APIS_ENABLED)
    public void onRevokeSelfPermissionsOnKill(@NonNull String packageName,
            @NonNull List<String> permissions, int deviceId, @NonNull Runnable callback) {
        onRevokeSelfPermissionsOnKill(packageName, permissions, callback);
    }

    // TODO(b/272129940): Remove this API and device profile role description when we drop T
    //  support.
    /**
     * Get a user-readable sentence, describing the set of privileges that are to be granted to a
     * companion app managing a device of the given profile.
     *
     * @param deviceProfileName the
     *      {@link android.companion.AssociationRequest.DeviceProfile device profile} name
     *
     * @deprecated Device profile privilege descriptions have been bundled in CDM APK since T.
     *
     * @hide
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(Manifest.permission.MANAGE_COMPANION_DEVICES)
    @NonNull
    public String getPrivilegesDescriptionStringForProfile(@NonNull String deviceProfileName) {
        throw new AbstractMethodError("Must be overridden in implementing class");
    }

    /**
     * Get the count of unused, hibernating apps on the device.
     *
     * @param callback callback after count is retrieved
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MANAGE_APP_HIBERNATION)
    @NonNull
    public void onGetUnusedAppCount(@NonNull IntConsumer callback) {
        throw new AbstractMethodError("Must be overridden in implementing class");
    }

    /**
     * Get the hibernation eligibility of the app. See
     * {@link android.permission.PermissionControllerManager.HibernationEligibilityFlag}.
     *
     * @param packageName package to check eligibility
     * @param callback callback after eligibility is returned
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MANAGE_APP_HIBERNATION)
    public void onGetHibernationEligibility(@NonNull String packageName,
            @NonNull IntConsumer callback) {
        throw new AbstractMethodError("Must be overridden in implementing class");
    }

    @Override
    public final @NonNull IBinder onBind(Intent intent) {
        return new IPermissionController.Stub() {
            @Override
            public void revokeRuntimePermissions(
                    Bundle bundleizedRequest, boolean doDryRun, int reason,
                    String callerPackageName, AndroidFuture callback) {
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

                enforceSomePermissionsGrantedToCaller(
                        Manifest.permission.REVOKE_RUNTIME_PERMISSIONS);

                // Verify callerPackageName
                try {
                    PackageInfo pkgInfo = getPackageManager().getPackageInfo(callerPackageName, 0);
                    checkArgument(getCallingUid() == pkgInfo.applicationInfo.uid);
                } catch (PackageManager.NameNotFoundException e) {
                    throw new RuntimeException(e);
                }

                onRevokeRuntimePermissions(request,
                        doDryRun, reason, callerPackageName, revoked -> {
                            CollectionUtils.forEach(revoked, (pkg, perms) -> {
                                Preconditions.checkNotNull(pkg);
                                Preconditions.checkCollectionElementsNotNull(perms, "permissions");
                            });
                            callback.complete(revoked);
                        });
            }

            /**
             * Throw a {@link SecurityException} if not at least one of the permissions is granted.
             *
             * @param requiredPermissions A list of permissions. Any of of them if sufficient to
             *                            pass the check
             */
            private void enforceSomePermissionsGrantedToCaller(
                    @NonNull String... requiredPermissions) {
                for (String requiredPermission : requiredPermissions) {
                    if (checkCallingPermission(requiredPermission)
                            == PackageManager.PERMISSION_GRANTED) {
                        return;
                    }
                }

                throw new SecurityException(
                        "At lest one of the following permissions is required: " + Arrays.toString(
                                requiredPermissions));
            }


            @Override
            public void getRuntimePermissionBackup(UserHandle user, ParcelFileDescriptor pipe) {
                checkNotNull(user);
                checkNotNull(pipe);

                enforceSomePermissionsGrantedToCaller(Manifest.permission.GET_RUNTIME_PERMISSIONS);

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
            public void stageAndApplyRuntimePermissionsBackup(UserHandle user,
                    ParcelFileDescriptor pipe) {
                checkNotNull(user);
                checkNotNull(pipe);

                enforceSomePermissionsGrantedToCaller(Manifest.permission.GRANT_RUNTIME_PERMISSIONS,
                        Manifest.permission.RESTORE_RUNTIME_PERMISSIONS);

                try (InputStream backup = new ParcelFileDescriptor.AutoCloseInputStream(pipe)) {
                    CountDownLatch latch = new CountDownLatch(1);
                    onStageAndApplyRuntimePermissionsBackup(user, backup, latch::countDown);
                    latch.await();
                } catch (IOException e) {
                    Log.e(LOG_TAG, "Could not open pipe to read backup from", e);
                } catch (InterruptedException e) {
                    Log.e(LOG_TAG, "restoreRuntimePermissionBackup timed out", e);
                }
            }

            @Override
            public void applyStagedRuntimePermissionBackup(String packageName, UserHandle user,
                    AndroidFuture callback) {
                checkNotNull(packageName);
                checkNotNull(user);
                checkNotNull(callback);

                enforceSomePermissionsGrantedToCaller(Manifest.permission.GRANT_RUNTIME_PERMISSIONS,
                        Manifest.permission.RESTORE_RUNTIME_PERMISSIONS);

                onApplyStagedRuntimePermissionBackup(packageName, user, callback::complete);
            }

            @Override
            public void getAppPermissions(String packageName, AndroidFuture callback) {
                checkNotNull(packageName, "packageName");
                checkNotNull(callback, "callback");

                enforceSomePermissionsGrantedToCaller(Manifest.permission.GET_RUNTIME_PERMISSIONS);

                onGetAppPermissions(packageName, callback::complete);
            }

            @Override
            public void revokeRuntimePermission(String packageName, String permissionName) {
                checkNotNull(packageName, "packageName");
                checkNotNull(permissionName, "permissionName");

                enforceSomePermissionsGrantedToCaller(
                        Manifest.permission.REVOKE_RUNTIME_PERMISSIONS);

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
                    AndroidFuture callback) {
                checkCollectionElementsNotNull(permissionNames, "permissionNames");
                checkFlagsArgument(flags, COUNT_WHEN_SYSTEM | COUNT_ONLY_WHEN_GRANTED);
                checkNotNull(callback, "callback");

                enforceSomePermissionsGrantedToCaller(Manifest.permission.GET_RUNTIME_PERMISSIONS);

                onCountPermissionApps(permissionNames, flags, callback::complete);
            }

            @Override
            public void getPermissionUsages(boolean countSystem, long numMillis,
                    AndroidFuture callback) {
                checkArgumentNonnegative(numMillis);
                checkNotNull(callback, "callback");

                enforceSomePermissionsGrantedToCaller(Manifest.permission.GET_RUNTIME_PERMISSIONS);

                onGetPermissionUsages(countSystem, numMillis, callback::complete);
            }

            @Override
            public void setRuntimePermissionGrantStateByDeviceAdminFromParams(
                    String callerPackageName, AdminPermissionControlParams params,
                    AndroidFuture callback) {
                checkStringNotEmpty(callerPackageName);
                if (params.getGrantState() == PERMISSION_GRANT_STATE_GRANTED) {
                    enforceSomePermissionsGrantedToCaller(
                            Manifest.permission.GRANT_RUNTIME_PERMISSIONS);
                }

                if (params.getGrantState() == PERMISSION_GRANT_STATE_DENIED) {
                    enforceSomePermissionsGrantedToCaller(
                            Manifest.permission.REVOKE_RUNTIME_PERMISSIONS);
                }

                enforceSomePermissionsGrantedToCaller(
                        Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY);
                checkNotNull(callback);

                onSetRuntimePermissionGrantStateByDeviceAdmin(callerPackageName,
                        params, callback::complete);
            }

            @Override
            public void grantOrUpgradeDefaultRuntimePermissions(@NonNull AndroidFuture callback) {
                checkNotNull(callback, "callback");

                enforceSomePermissionsGrantedToCaller(
                        Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY);

                onGrantOrUpgradeDefaultRuntimePermissions(() -> callback.complete(true));
            }

            @Override
            public void updateUserSensitiveForApp(int uid, @NonNull AndroidFuture callback) {
                Preconditions.checkNotNull(callback, "callback cannot be null");

                enforceSomePermissionsGrantedToCaller(
                        Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY);

                try {
                    onUpdateUserSensitivePermissionFlags(uid, () -> callback.complete(null));
                } catch (Exception e) {
                    callback.completeExceptionally(e);
                }
            }

            @Override
            public void notifyOneTimePermissionSessionTimeout(String packageName, int deviceId) {
                enforceSomePermissionsGrantedToCaller(
                        Manifest.permission.REVOKE_RUNTIME_PERMISSIONS);
                packageName = Preconditions.checkNotNull(packageName,
                        "packageName cannot be null");
                onOneTimePermissionSessionTimeout(packageName, deviceId);
            }

            @Override
            protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
                checkNotNull(fd, "fd");
                checkNotNull(writer, "writer");

                enforceSomePermissionsGrantedToCaller(Manifest.permission.GET_RUNTIME_PERMISSIONS);

                PermissionControllerService.this.dump(fd, writer, args);
            }

            @Override
            public void getPrivilegesDescriptionStringForProfile(
                    @NonNull String deviceProfileName,
                    @NonNull AndroidFuture<String> callback) {
                try {
                    checkStringNotEmpty(deviceProfileName);
                    Objects.requireNonNull(callback);

                    enforceSomePermissionsGrantedToCaller(
                            Manifest.permission.MANAGE_COMPANION_DEVICES);

                    callback.complete(PermissionControllerService
                            .this
                            .getPrivilegesDescriptionStringForProfile(deviceProfileName));
                } catch (Throwable t) {
                    callback.completeExceptionally(t);
                }
            }

            @Override
            public void getPlatformPermissionsForGroup(
                    @NonNull String permissionName,
                    @NonNull AndroidFuture<List<String>> callback) {
                try {
                    Objects.requireNonNull(permissionName);
                    Objects.requireNonNull(callback);
                    PermissionControllerService.this.onGetPlatformPermissionsForGroup(
                            permissionName, callback::complete);
                } catch (Throwable t) {
                    callback.completeExceptionally(t);
                }
            }

            @Override
            public void getGroupOfPlatformPermission(
                    @NonNull String permissionGroupName,
                    @NonNull AndroidFuture<String> callback) {
                try {
                    Objects.requireNonNull(permissionGroupName);
                    Objects.requireNonNull(callback);
                    PermissionControllerService.this.onGetGroupOfPlatformPermission(
                            permissionGroupName, callback::complete);
                } catch (Throwable t) {
                    callback.completeExceptionally(t);
                }
            }

            @Override
            public void getUnusedAppCount(@NonNull AndroidFuture callback) {
                try {
                    Objects.requireNonNull(callback);

                    enforceSomePermissionsGrantedToCaller(
                            Manifest.permission.MANAGE_APP_HIBERNATION);

                    PermissionControllerService.this.onGetUnusedAppCount(callback::complete);
                } catch (Throwable t) {
                    callback.completeExceptionally(t);
                }
            }

            @Override
            public void getHibernationEligibility(@NonNull String packageName,
                    @NonNull AndroidFuture callback) {
                try {
                    Objects.requireNonNull(callback);

                    enforceSomePermissionsGrantedToCaller(
                            Manifest.permission.MANAGE_APP_HIBERNATION);

                    PermissionControllerService.this.onGetHibernationEligibility(packageName,
                            callback::complete);
                } catch (Throwable t) {
                    callback.completeExceptionally(t);
                }
            }

            @Override
            public void revokeSelfPermissionsOnKill(@NonNull String packageName,
                    @NonNull List<String> permissions, int deviceId,
                    @NonNull AndroidFuture callback) {
                try {
                    Objects.requireNonNull(callback);

                    final int callingUid = Binder.getCallingUid();
                    int targetPackageUid = getPackageManager().getPackageUid(packageName,
                            PackageManager.PackageInfoFlags.of(0));
                    if (targetPackageUid != callingUid) {
                        enforceSomePermissionsGrantedToCaller(
                                Manifest.permission.REVOKE_RUNTIME_PERMISSIONS);
                    }
                    onRevokeSelfPermissionsOnKill(packageName, permissions, deviceId,
                            () -> callback.complete(null));
                } catch (Throwable t) {
                    callback.completeExceptionally(t);
                }
            }
        };
    }
}
