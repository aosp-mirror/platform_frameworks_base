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

package android.rolecontrollerservice;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.Service;
import android.app.role.IRoleManagerCallback;
import android.app.role.RoleManager;
import android.app.role.RoleManagerCallback;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.util.Preconditions;

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

    private static final String LOG_TAG = RoleControllerService.class.getSimpleName();

    /**
     * The {@link Intent} that must be declared as handled by the service. The service should also
     * require the {@link android.Manifest.permission#BIND_ROLE_CONTROLLER_SERVICE} permission so
     * that other applications can not abuse it.
     */
    public static final String SERVICE_INTERFACE =
            "android.rolecontrollerservice.RoleControllerService";

    @Nullable
    @Override
    public final IBinder onBind(@Nullable Intent intent) {
        return new IRoleControllerService.Stub() {

            @Override
            public void onAddRoleHolder(String roleName, String packageName,
                    IRoleManagerCallback callback) {
                Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
                Preconditions.checkStringNotEmpty(packageName,
                        "packageName cannot be null or empty");
                Preconditions.checkNotNull(callback, "callback cannot be null");
                RoleControllerService.this.onAddRoleHolder(roleName, packageName,
                        new RoleManagerCallbackDelegate(callback));
            }

            @Override
            public void onRemoveRoleHolder(String roleName, String packageName,
                    IRoleManagerCallback callback) {
                Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
                Preconditions.checkStringNotEmpty(packageName,
                        "packageName cannot be null or empty");
                Preconditions.checkNotNull(callback, "callback cannot be null");
                RoleControllerService.this.onRemoveRoleHolder(roleName, packageName,
                        new RoleManagerCallbackDelegate(callback));
            }

            @Override
            public void onClearRoleHolders(String roleName, IRoleManagerCallback callback) {
                Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
                Preconditions.checkNotNull(callback, "callback cannot be null");
                RoleControllerService.this.onClearRoleHolders(roleName,
                        new RoleManagerCallbackDelegate(callback));
            }

            @Override
            public void onGrantDefaultRoles(IRoleManagerCallback callback) {
                Preconditions.checkNotNull(callback, "callback cannot be null");
                RoleControllerService.this.onGrantDefaultRoles(new RoleManagerCallbackDelegate(
                        callback));
            }
        };
    }

    /**
     * Add a specific application to the holders of a role. If the role is exclusive, the previous
     * holder will be replaced.
     * <p>
     * Implementation should enforce the role requirements and grant or revoke the relevant
     * privileges of roles.
     *
     * @param roleName the name of the role to add the role holder for
     * @param packageName the package name of the application to add to the role holders
     * @param callback the callback for whether this call is successful
     *
     * @see RoleManager#addRoleHolderAsUser(String, String, UserHandle, Executor,
     *      RoleManagerCallback)
     */
    public abstract void onAddRoleHolder(@NonNull String roleName, @NonNull String packageName,
            @NonNull RoleManagerCallback callback);

    /**
     * Remove a specific application from the holders of a role.
     *
     * @param roleName the name of the role to remove the role holder for
     * @param packageName the package name of the application to remove from the role holders
     * @param callback the callback for whether this call is successful
     *
     * @see RoleManager#removeRoleHolderAsUser(String, String, UserHandle, Executor,
     *      RoleManagerCallback)
     */
    public abstract void onRemoveRoleHolder(@NonNull String roleName, @NonNull String packageName,
            @NonNull RoleManagerCallback callback);

    /**
     * Remove all holders of a role.
     *
     * @param roleName the name of the role to remove role holders for
     * @param callback the callback for whether this call is successful
     *
     * @see RoleManager#clearRoleHoldersAsUser(String, UserHandle, Executor, RoleManagerCallback)
     */
    public abstract void onClearRoleHolders(@NonNull String roleName,
            @NonNull RoleManagerCallback callback);

    /**
     * Called by system to grant default permissions and roles.
     * <p>
     * This is typically when creating a new user or upgrading either system or
     * permission controller package
     *
     * @param callback the callback for whether this call is successful
     */
    public abstract void onGrantDefaultRoles(@NonNull RoleManagerCallback callback);

    private static class RoleManagerCallbackDelegate implements RoleManagerCallback {

        private IRoleManagerCallback mCallback;

        RoleManagerCallbackDelegate(IRoleManagerCallback callback) {
            mCallback = callback;
        }

        @Override
        public void onSuccess() {
            try {
                mCallback.onSuccess();
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "Error calling onSuccess() callback");
            }
        }

        @Override
        public void onFailure() {
            try {
                mCallback.onFailure();
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "Error calling onFailure() callback");
            }
        }
    }
}
