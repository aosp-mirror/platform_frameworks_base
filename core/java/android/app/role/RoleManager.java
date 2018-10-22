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

package android.app.role;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;

import com.android.internal.util.Preconditions;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * This class provides information about and manages roles.
 * <p>
 * A role is a unique name within the system associated with certain privileges. The list of
 * available roles might change with a system app update, so apps should not make assumption about
 * the availability of roles. Instead, they should always query if the role is available using
 * {@link #isRoleAvailable(String)} before trying to do anything with it. Some predefined role names
 * are available as constants in this class, and a list of possibly available roles can be found in
 * the AndroidX Libraries.
 * <p>
 * There can be multiple applications qualifying for a role, but only a subset of them can become
 * role holders. To qualify for a role, an application must meet certain requirements, including
 * defining certain components in its manifest. These requirements can be found in the AndroidX
 * Libraries. Then the application will need user consent to become a role holder, which can be
 * requested using {@link android.app.Activity#startActivityForResult(Intent, int)} with the
 * {@code Intent} obtained from {@link #createRequestRoleIntent(String)}.
 * <p>
 * Upon becoming a role holder, the application may be granted certain privileges that are role
 * specific. When the application loses its role, these privileges will also be revoked.
 */
@SystemService(Context.ROLE_SERVICE)
public final class RoleManager {

    private static final String LOG_TAG = RoleManager.class.getSimpleName();

    /**
     * The name of the dialer role.
     */
    public static final String ROLE_DIALER = "android.app.role.DIALER";

    /**
     * The name of the SMS role.
     */
    public static final String ROLE_SMS = "android.app.role.SMS";

    /**
     * The action used to request user approval of a role for an application.
     *
     * @hide
     */
    public static final String ACTION_REQUEST_ROLE = "android.app.role.action.REQUEST_ROLE";

    /**
     * The name of the requested role.
     * <p>
     * <strong>Type:</strong> String
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_REQUEST_ROLE_NAME = "android.app.role.extra.REQUEST_ROLE_NAME";

    /**
     * The permission required to manage records of role holders in {@link RoleManager} directly.
     *
     * @hide
     */
    public static final String PERMISSION_MANAGE_ROLE_HOLDERS_FROM_CONTROLLER =
            "com.android.permissioncontroller.permission.MANAGE_ROLE_HOLDERS_FROM_CONTROLLER";

    @NonNull
    private final Context mContext;

    @NonNull
    private final IRoleManager mService;

    /**
     * @hide
     */
    public RoleManager(@NonNull Context context) throws ServiceManager.ServiceNotFoundException {
        mContext = context;
        mService = IRoleManager.Stub.asInterface(ServiceManager.getServiceOrThrow(
                Context.ROLE_SERVICE));
    }

    /**
     * Returns an {@code Intent} suitable for passing to
     * {@link android.app.Activity#startActivityForResult(Intent, int)} which prompts the user to
     * grant a role to this application.
     * <p>
     * If the role is granted, the {@code resultCode} will be
     * {@link android.app.Activity#RESULT_OK}, otherwise it will be
     * {@link android.app.Activity#RESULT_CANCELED}.
     *
     * @param roleName the name of requested role
     *
     * @return the {@code Intent} to prompt user to grant the role
     *
     * @throws IllegalArgumentException if {@code role} is {@code null} or empty
     */
    @NonNull
    public Intent createRequestRoleIntent(@NonNull String roleName) {
        Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
        Intent intent = new Intent(ACTION_REQUEST_ROLE);
        intent.setPackage(mContext.getPackageManager().getPermissionControllerPackageName());
        intent.putExtra(EXTRA_REQUEST_ROLE_NAME, roleName);
        return intent;
    }

    /**
     * Check whether a role is available in the system.
     *
     * @param roleName the name of role to checking for
     *
     * @return whether the role is available in the system
     *
     * @throws IllegalArgumentException if the role name is {@code null} or empty
     */
    public boolean isRoleAvailable(@NonNull String roleName) {
        Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
        try {
            return mService.isRoleAvailable(roleName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Check whether the calling application is holding a particular role.
     *
     * @param roleName the name of the role to check for
     *
     * @return whether the calling application is holding the role
     *
     * @throws IllegalArgumentException if the role name is {@code null} or empty.
     */
    public boolean isRoleHeld(@NonNull String roleName) {
        Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
        try {
            return mService.isRoleHeld(roleName, mContext.getPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get package names of the applications holding the role.
     * <p>
     * <strong>Note:</strong> Using this API requires holding
     * {@code android.permission.MANAGE_ROLE_HOLDERS}.
     *
     * @param roleName the name of the role to get the role holder for
     *
     * @return a list of package names of the role holders, or an empty list if none.
     *
     * @throws IllegalArgumentException if the role name is {@code null} or empty.
     *
     * @see #getRoleHoldersAsUser(String, UserHandle)
     *
     * @hide
     */
    @NonNull
    @RequiresPermission(Manifest.permission.MANAGE_ROLE_HOLDERS)
    @SystemApi
    public List<String> getRoleHolders(@NonNull String roleName) {
        return getRoleHoldersAsUser(roleName, UserHandle.of(UserHandle.getCallingUserId()));
    }

    /**
     * Get package names of the applications holding the role.
     * <p>
     * <strong>Note:</strong> Using this API requires holding
     * {@code android.permission.MANAGE_ROLE_HOLDERS} and if the user id is not the current user
     * {@code android.permission.INTERACT_ACROSS_USERS_FULL}.
     *
     * @param roleName the name of the role to get the role holder for
     * @param user the user to get the role holder for
     *
     * @return a list of package names of the role holders, or an empty list if none.
     *
     * @throws IllegalArgumentException if the role name is {@code null} or empty.
     *
     * @see #addRoleHolderAsUser(String, String, UserHandle, Executor, RoleManagerCallback)
     * @see #removeRoleHolderAsUser(String, String, UserHandle, Executor, RoleManagerCallback)
     * @see #clearRoleHoldersAsUser(String, UserHandle, Executor, RoleManagerCallback)
     *
     * @hide
     */
    @NonNull
    @RequiresPermission(Manifest.permission.MANAGE_ROLE_HOLDERS)
    @SystemApi
    public List<String> getRoleHoldersAsUser(@NonNull String roleName, @NonNull UserHandle user) {
        Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
        Preconditions.checkNotNull(user, "user cannot be null");
        try {
            return mService.getRoleHoldersAsUser(roleName, user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Add a specific application to the holders of a role. If the role is exclusive, the previous
     * holder will be replaced.
     * <p>
     * <strong>Note:</strong> Using this API requires holding
     * {@code android.permission.MANAGE_ROLE_HOLDERS} and if the user id is not the current user
     * {@code android.permission.INTERACT_ACROSS_USERS_FULL}.
     *
     * @param roleName the name of the role to add the role holder for
     * @param packageName the package name of the application to add to the role holders
     * @param user the user to add the role holder for
     * @param executor the {@code Executor} to run the callback on.
     * @param callback the callback for whether this call is successful
     *
     * @throws IllegalArgumentException if the role name or package name is {@code null} or empty.
     *
     * @see #getRoleHoldersAsUser(String, UserHandle)
     * @see #removeRoleHolderAsUser(String, String, UserHandle, Executor, RoleManagerCallback)
     * @see #clearRoleHoldersAsUser(String, UserHandle, Executor, RoleManagerCallback)
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_ROLE_HOLDERS)
    @SystemApi
    public void addRoleHolderAsUser(@NonNull String roleName, @NonNull String packageName,
            @NonNull UserHandle user, @CallbackExecutor @NonNull Executor executor,
            @NonNull RoleManagerCallback callback) {
        Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
        Preconditions.checkStringNotEmpty(packageName, "packageName cannot be null or empty");
        Preconditions.checkNotNull(user, "user cannot be null");
        Preconditions.checkNotNull(executor, "executor cannot be null");
        Preconditions.checkNotNull(callback, "callback cannot be null");
        try {
            mService.addRoleHolderAsUser(roleName, packageName, user.getIdentifier(),
                    new RoleManagerCallbackDelegate(executor, callback));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Remove a specific application from the holders of a role.
     * <p>
     * <strong>Note:</strong> Using this API requires holding
     * {@code android.permission.MANAGE_ROLE_HOLDERS} and if the user id is not the current user
     * {@code android.permission.INTERACT_ACROSS_USERS_FULL}.
     *
     * @param roleName the name of the role to remove the role holder for
     * @param packageName the package name of the application to remove from the role holders
     * @param user the user to remove the role holder for
     * @param executor the {@code Executor} to run the callback on.
     * @param callback the callback for whether this call is successful
     *
     * @throws IllegalArgumentException if the role name or package name is {@code null} or empty.
     *
     * @see #getRoleHoldersAsUser(String, UserHandle)
     * @see #addRoleHolderAsUser(String, String, UserHandle, Executor, RoleManagerCallback)
     * @see #clearRoleHoldersAsUser(String, UserHandle, Executor, RoleManagerCallback)
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_ROLE_HOLDERS)
    @SystemApi
    public void removeRoleHolderAsUser(@NonNull String roleName, @NonNull String packageName,
            @NonNull UserHandle user, @CallbackExecutor @NonNull Executor executor,
            @NonNull RoleManagerCallback callback) {
        Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
        Preconditions.checkStringNotEmpty(packageName, "packageName cannot be null or empty");
        Preconditions.checkNotNull(user, "user cannot be null");
        Preconditions.checkNotNull(executor, "executor cannot be null");
        Preconditions.checkNotNull(callback, "callback cannot be null");
        try {
            mService.removeRoleHolderAsUser(roleName, packageName, user.getIdentifier(),
                    new RoleManagerCallbackDelegate(executor, callback));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Remove all holders of a role.
     * <p>
     * <strong>Note:</strong> Using this API requires holding
     * {@code android.permission.MANAGE_ROLE_HOLDERS} and if the user id is not the current user
     * {@code android.permission.INTERACT_ACROSS_USERS_FULL}.
     *
     * @param roleName the name of the role to remove role holders for
     * @param user the user to remove role holders for
     * @param executor the {@code Executor} to run the callback on.
     * @param callback the callback for whether this call is successful
     *
     * @throws IllegalArgumentException if the role name is {@code null} or empty.
     *
     * @see #getRoleHoldersAsUser(String, UserHandle)
     * @see #addRoleHolderAsUser(String, String, UserHandle, Executor, RoleManagerCallback)
     * @see #removeRoleHolderAsUser(String, String, UserHandle, Executor, RoleManagerCallback)
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_ROLE_HOLDERS)
    @SystemApi
    public void clearRoleHoldersAsUser(@NonNull String roleName, @NonNull UserHandle user,
            @CallbackExecutor @NonNull Executor executor, @NonNull RoleManagerCallback callback) {
        Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
        Preconditions.checkNotNull(user, "user cannot be null");
        Preconditions.checkNotNull(executor, "executor cannot be null");
        Preconditions.checkNotNull(callback, "callback cannot be null");
        try {
            mService.clearRoleHoldersAsUser(roleName, user.getIdentifier(),
                    new RoleManagerCallbackDelegate(executor, callback));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Add a specific application to the holders of a role, only modifying records inside
     * {@link RoleManager}. Should only be called from
     * {@link android.rolecontrollerservice.RoleControllerService}.
     * <p>
     * <strong>Note:</strong> Using this API requires holding
     * {@link #PERMISSION_MANAGE_ROLE_HOLDERS_FROM_CONTROLLER}.
     *
     * @param roleName the name of the role to add the role holder for
     * @param packageName the package name of the application to add to the role holders
     *
     * @return whether the operation was successful, and will also be {@code true} if a matching
     *         role holder is already found.
     *
     * @throws IllegalArgumentException if the role name or package name is {@code null} or empty.
     *
     * @see #getRoleHolders(String)
     * @see #removeRoleHolderFromController(String, String)
     *
     * @hide
     */
    @RequiresPermission(PERMISSION_MANAGE_ROLE_HOLDERS_FROM_CONTROLLER)
    @SystemApi
    public boolean addRoleHolderFromController(@NonNull String roleName,
            @NonNull String packageName) {
        Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
        Preconditions.checkStringNotEmpty(packageName, "packageName cannot be null or empty");
        try {
            return mService.addRoleHolderFromController(roleName, packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Remove a specific application from the holders of a role, only modifying records inside
     * {@link RoleManager}. Should only be called from
     * {@link android.rolecontrollerservice.RoleControllerService}.
     * <p>
     * <strong>Note:</strong> Using this API requires holding
     * {@link #PERMISSION_MANAGE_ROLE_HOLDERS_FROM_CONTROLLER}.
     *
     * @param roleName the name of the role to remove the role holder for
     * @param packageName the package name of the application to remove from the role holders
     *
     * @return whether the operation was successful, and will also be {@code true} if no matching
     *         role holder was found to remove.
     *
     * @throws IllegalArgumentException if the role name or package name is {@code null} or empty.
     *
     * @see #getRoleHolders(String)
     * @see #addRoleHolderFromController(String, String)
     *
     * @hide
     */
    @RequiresPermission(PERMISSION_MANAGE_ROLE_HOLDERS_FROM_CONTROLLER)
    @SystemApi
    public boolean removeRoleHolderFromController(@NonNull String roleName,
            @NonNull String packageName) {
        Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
        Preconditions.checkStringNotEmpty(packageName, "packageName cannot be null or empty");
        try {
            return mService.removeRoleHolderFromController(roleName, packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private static class RoleManagerCallbackDelegate extends IRoleManagerCallback.Stub {

        @NonNull
        private final Executor mExecutor;
        @NonNull
        private final RoleManagerCallback mCallback;

        RoleManagerCallbackDelegate(@NonNull Executor executor,
                @NonNull RoleManagerCallback callback) {
            mExecutor = executor;
            mCallback = callback;
        }

        @Override
        public void onSuccess() {
            long token = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(mCallback::onSuccess);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void onFailure() {
            long token = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(mCallback::onFailure);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }
}
