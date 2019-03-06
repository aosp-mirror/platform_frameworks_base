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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.Service;
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
     * The {@link Intent} that must be declared as handled by the service.
     */
    public static final String SERVICE_INTERFACE = "android.app.role.RoleControllerService";

    @Nullable
    @Override
    public final IBinder onBind(@Nullable Intent intent) {
        return new IRoleController.Stub() {

            @Override
            public void onGrantDefaultRoles(IRoleManagerCallback callback) {
                Preconditions.checkNotNull(callback, "callback cannot be null");
                RoleControllerService.this.onGrantDefaultRoles(new RoleManagerCallbackDelegate(
                        callback));
            }

            @Override
            public void onAddRoleHolder(String roleName, String packageName, int flags,
                    IRoleManagerCallback callback) {
                Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
                Preconditions.checkStringNotEmpty(packageName,
                        "packageName cannot be null or empty");
                Preconditions.checkNotNull(callback, "callback cannot be null");
                RoleControllerService.this.onAddRoleHolder(roleName, packageName, flags,
                        new RoleManagerCallbackDelegate(callback));
            }

            @Override
            public void onRemoveRoleHolder(String roleName, String packageName, int flags,
                    IRoleManagerCallback callback) {
                Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
                Preconditions.checkStringNotEmpty(packageName,
                        "packageName cannot be null or empty");
                Preconditions.checkNotNull(callback, "callback cannot be null");
                RoleControllerService.this.onRemoveRoleHolder(roleName, packageName, flags,
                        new RoleManagerCallbackDelegate(callback));
            }

            @Override
            public void onClearRoleHolders(String roleName, int flags,
                    IRoleManagerCallback callback) {
                Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
                Preconditions.checkNotNull(callback, "callback cannot be null");
                RoleControllerService.this.onClearRoleHolders(roleName, flags,
                        new RoleManagerCallbackDelegate(callback));
            }

            @Override
            public void onSmsKillSwitchToggled(boolean smsRestrictionEnabled) {
                RoleControllerService.this.onSmsKillSwitchToggled(smsRestrictionEnabled);
            }
        };
    }

    /**
     * Called by system to grant default permissions and roles.
     * <p>
     * This is typically when creating a new user or upgrading either system or
     * permission controller package
     *
     * @param callback the callback for whether this call is successful
     */
    public abstract void onGrantDefaultRoles(@NonNull RoleManagerCallback callback);

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
     * @param callback the callback for whether this call is successful
     *
     * @see RoleManager#addRoleHolderAsUser(String, String, int, UserHandle, Executor,
     *      RoleManagerCallback)
     */
    public abstract void onAddRoleHolder(@NonNull String roleName, @NonNull String packageName,
            @RoleManager.ManageHoldersFlags int flags, @NonNull RoleManagerCallback callback);

    /**
     * Remove a specific application from the holders of a role.
     *
     * @param roleName the name of the role to remove the role holder for
     * @param packageName the package name of the application to remove from the role holders
     * @param flags optional behavior flags
     * @param callback the callback for whether this call is successful
     *
     * @see RoleManager#removeRoleHolderAsUser(String, String, int, UserHandle, Executor,
     *      RoleManagerCallback)
     */
    public abstract void onRemoveRoleHolder(@NonNull String roleName, @NonNull String packageName,
            @RoleManager.ManageHoldersFlags int flags, @NonNull RoleManagerCallback callback);

    /**
     * Remove all holders of a role.
     *
     * @param roleName the name of the role to remove role holders for
     * @param flags optional behavior flags
     * @param callback the callback for whether this call is successful
     *
     * @see RoleManager#clearRoleHoldersAsUser(String, int, UserHandle, Executor,
     *      RoleManagerCallback)
     */
    public abstract void onClearRoleHolders(@NonNull String roleName,
            @RoleManager.ManageHoldersFlags int flags, @NonNull RoleManagerCallback callback);

    /**
     * Cleanup appop/permissions state in response to sms kill switch toggle
     *
     * @param enabled whether kill switch was turned on
     */
    //STOPSHIP: remove this api before shipping a final version
    public abstract void onSmsKillSwitchToggled(boolean enabled);

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
