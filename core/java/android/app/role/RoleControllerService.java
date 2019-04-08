/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.app.role;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.WorkerThread;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.UserHandle;

import com.android.internal.util.Preconditions;
import com.android.internal.util.function.pooled.PooledLambda;

import java.util.concurrent.Executor;

/**
 * Abstract base class for the role controller service.
 * <p>
 * Subclass should implement the business logic for role management, including enforcing role
 * requirements and granting or revoking relevant privileges of roles. This class can only be
 * implemented by the permission controller app which is registered in {@code PackageManager}.
 *
 * @hide
 */
@SystemApi
public abstract class RoleControllerService extends Service {

    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    public static final String SERVICE_INTERFACE = "android.app.role.RoleControllerService";

    private HandlerThread mWorkerThread;
    private Handler mWorkerHandler;

    @Override
    public void onCreate() {
        super.onCreate();

        mWorkerThread = new HandlerThread(RoleControllerService.class.getSimpleName());
        mWorkerThread.start();
        mWorkerHandler = new Handler(mWorkerThread.getLooper());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        mWorkerThread.quitSafely();
    }

    @Nullable
    @Override
    public final IBinder onBind(@Nullable Intent intent) {
        return new IRoleController.Stub() {

            @Override
            public void grantDefaultRoles(RemoteCallback callback) {
                enforceCallerSystemUid("grantDefaultRoles");

                Preconditions.checkNotNull(callback, "callback cannot be null");

                mWorkerHandler.sendMessage(PooledLambda.obtainMessage(
                        RoleControllerService::grantDefaultRoles, RoleControllerService.this,
                        callback));
            }

            @Override
            public void onAddRoleHolder(String roleName, String packageName, int flags,
                    RemoteCallback callback) {
                enforceCallerSystemUid("onAddRoleHolder");

                Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
                Preconditions.checkStringNotEmpty(packageName,
                        "packageName cannot be null or empty");
                Preconditions.checkNotNull(callback, "callback cannot be null");

                mWorkerHandler.sendMessage(PooledLambda.obtainMessage(
                        RoleControllerService::onAddRoleHolder, RoleControllerService.this,
                        roleName, packageName, flags, callback));
            }

            @Override
            public void onRemoveRoleHolder(String roleName, String packageName, int flags,
                    RemoteCallback callback) {
                enforceCallerSystemUid("onRemoveRoleHolder");

                Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
                Preconditions.checkStringNotEmpty(packageName,
                        "packageName cannot be null or empty");
                Preconditions.checkNotNull(callback, "callback cannot be null");

                mWorkerHandler.sendMessage(PooledLambda.obtainMessage(
                        RoleControllerService::onRemoveRoleHolder, RoleControllerService.this,
                        roleName, packageName, flags, callback));
            }

            @Override
            public void onClearRoleHolders(String roleName, int flags, RemoteCallback callback) {
                enforceCallerSystemUid("onClearRoleHolders");

                Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
                Preconditions.checkNotNull(callback, "callback cannot be null");

                mWorkerHandler.sendMessage(PooledLambda.obtainMessage(
                        RoleControllerService::onClearRoleHolders, RoleControllerService.this,
                        roleName, flags, callback));
            }

            private void enforceCallerSystemUid(@NonNull String methodName) {
                if (Binder.getCallingUid() != Process.SYSTEM_UID) {
                    throw new SecurityException("Only the system process can call " + methodName
                            + "()");
                }
            }

            @Override
            public void isApplicationQualifiedForRole(String roleName, String packageName,
                    RemoteCallback callback) {
                enforceCallingPermission(Manifest.permission.MANAGE_ROLE_HOLDERS, null);

                Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
                Preconditions.checkStringNotEmpty(packageName,
                        "packageName cannot be null or empty");
                Preconditions.checkNotNull(callback, "callback cannot be null");

                boolean qualified = onIsApplicationQualifiedForRole(roleName, packageName);
                callback.sendResult(qualified ? Bundle.EMPTY : null);
            }

            @Override
            public void isRoleVisible(String roleName, RemoteCallback callback) {
                enforceCallingPermission(Manifest.permission.MANAGE_ROLE_HOLDERS, null);

                Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
                Preconditions.checkNotNull(callback, "callback cannot be null");

                boolean visible = onIsRoleVisible(roleName);
                callback.sendResult(visible ? Bundle.EMPTY : null);
            }
        };
    }

    private void grantDefaultRoles(@NonNull RemoteCallback callback) {
        boolean successful = onGrantDefaultRoles();
        callback.sendResult(successful ? Bundle.EMPTY : null);
    }

    private void onAddRoleHolder(@NonNull String roleName, @NonNull String packageName,
            @RoleManager.ManageHoldersFlags int flags, @NonNull RemoteCallback callback) {
        boolean successful = onAddRoleHolder(roleName, packageName, flags);
        callback.sendResult(successful ? Bundle.EMPTY : null);
    }

    private void onRemoveRoleHolder(@NonNull String roleName, @NonNull String packageName,
            @RoleManager.ManageHoldersFlags int flags, @NonNull RemoteCallback callback) {
        boolean successful = onRemoveRoleHolder(roleName, packageName, flags);
        callback.sendResult(successful ? Bundle.EMPTY : null);
    }

    private void onClearRoleHolders(@NonNull String roleName,
            @RoleManager.ManageHoldersFlags int flags, @NonNull RemoteCallback callback) {
        boolean successful = onClearRoleHolders(roleName, flags);
        callback.sendResult(successful ? Bundle.EMPTY : null);
    }

    /**
     * Called by system to grant default permissions and roles.
     * <p>
     * This is typically when creating a new user or upgrading either system or
     * permission controller package
     *
     * @return whether this call was successful
     */
    @WorkerThread
    public abstract boolean onGrantDefaultRoles();

    /**
     * Add a specific application to the holders of a role. If the role is exclusive, the previous
     * holder will be replaced.
     * <p>
     * Implementation should enforce the role requirements and grant or revoke the relevant
     * privileges of roles.
     *
     * @param roleName the name of the role to add the role holder for
     * @param packageName the package name of the application to add to the role holders
     * @param flags optional behavior flags
     *
     * @return whether this call was successful
     *
     * @see RoleManager#addRoleHolderAsUser(String, String, int, UserHandle, Executor,
     *      RemoteCallback)
     */
    @WorkerThread
    public abstract boolean onAddRoleHolder(@NonNull String roleName, @NonNull String packageName,
            @RoleManager.ManageHoldersFlags int flags);

    /**
     * Remove a specific application from the holders of a role.
     *
     * @param roleName the name of the role to remove the role holder for
     * @param packageName the package name of the application to remove from the role holders
     * @param flags optional behavior flags
     *
     * @return whether this call was successful
     *
     * @see RoleManager#removeRoleHolderAsUser(String, String, int, UserHandle, Executor,
     *      RemoteCallback)
     */
    @WorkerThread
    public abstract boolean onRemoveRoleHolder(@NonNull String roleName,
            @NonNull String packageName, @RoleManager.ManageHoldersFlags int flags);

    /**
     * Remove all holders of a role.
     *
     * @param roleName the name of the role to remove role holders for
     * @param flags optional behavior flags
     *
     * @return whether this call was successful
     *
     * @see RoleManager#clearRoleHoldersAsUser(String, int, UserHandle, Executor, RemoteCallback)
     */
    @WorkerThread
    public abstract boolean onClearRoleHolders(@NonNull String roleName,
            @RoleManager.ManageHoldersFlags int flags);

    /**
     * Check whether an application is qualified for a role.
     *
     * @param roleName name of the role to check for
     * @param packageName package name of the application to check for
     *
     * @return whether the application is qualified for the role
     */
    public abstract boolean onIsApplicationQualifiedForRole(@NonNull String roleName,
            @NonNull String packageName);

    /**
     * Check whether a role should be visible to user.
     *
     * @param roleName name of the role to check for
     *
     * @return whether the role should be visible to user
     */
    public abstract boolean onIsRoleVisible(@NonNull String roleName);
}
