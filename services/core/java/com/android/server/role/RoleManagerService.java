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

package com.android.server.role;

import android.Manifest;
import android.annotation.CheckResult;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.role.IRoleManager;
import android.app.role.IRoleManagerCallback;
import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.UserHandle;
import android.os.UserManagerInternal;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Service for role management.
 *
 * @see RoleManager
 */
public class RoleManagerService extends SystemService {

    private static final String LOG_TAG = RoleManagerService.class.getSimpleName();

    @NonNull
    private final UserManagerInternal mUserManagerInternal;
    @NonNull
    private final AppOpsManager mAppOpsManager;

    @NonNull
    private final Object mLock = new Object();

    /**
     * Maps user id to its state.
     */
    @GuardedBy("mLock")
    @NonNull
    private final SparseArray<RoleUserState> mUserStates = new SparseArray<>();

    /**
     * Maps user id to its controller service.
     */
    @GuardedBy("mLock")
    @NonNull
    private final SparseArray<RemoteRoleControllerService> mControllerServices =
            new SparseArray<>();

    public RoleManagerService(@NonNull Context context) {
        super(context);

        mUserManagerInternal = LocalServices.getService(UserManagerInternal.class);
        mAppOpsManager = context.getSystemService(AppOpsManager.class);

        registerUserRemovedReceiver();
    }

    private void registerUserRemovedReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_USER_REMOVED);
        getContext().registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (TextUtils.equals(intent.getAction(), Intent.ACTION_USER_REMOVED)) {
                    int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                    onRemoveUser(userId);
                }
            }
        }, UserHandle.ALL, intentFilter, null, null);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.ROLE_SERVICE, new Stub());
        //TODO add watch for new user creation and run default grants for them
        //TODO add package update watch to detect PermissionController upgrade and run def. grants
    }

    @Override
    public void onStartUser(@UserIdInt int userId) {
        synchronized (mLock) {
            //TODO only call into PermissionController if it or system upgreaded (for boot time)
            getUserStateLocked(userId);
        }
        //TODO consider calling grants only when certain conditions are met
        // such as OS or PermissionController upgrade
        if (RemoteRoleControllerService.DEBUG) {
            Slog.i(LOG_TAG, "Granting default permissions...");
            CompletableFuture<Void> result = new CompletableFuture<>();
            getControllerService(userId).onGrantDefaultRoles(
                    new IRoleManagerCallback.Stub() {
                        @Override
                        public void onSuccess() {
                            result.complete(null);
                        }

                        @Override
                        public void onFailure() {
                            result.completeExceptionally(new RuntimeException());
                        }
                    });
            try {
                result.get(5, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                Slog.e(LOG_TAG, "Failed to grant defaults for user " + userId, e);
            }
        }
    }

    @GuardedBy("mLock")
    @NonNull
    private RoleUserState getUserStateLocked(@UserIdInt int userId) {
        RoleUserState userState = mUserStates.get(userId);
        if (userState == null) {
            userState = new RoleUserState(userId);
            userState.readSyncLocked();
            mUserStates.put(userId, userState);
        }
        return userState;
    }

    @GuardedBy("mLock")
    @NonNull
    private RemoteRoleControllerService getControllerService(@UserIdInt int userId) {
        RemoteRoleControllerService controllerService = mControllerServices.get(userId);
        if (controllerService == null) {
            controllerService = new RemoteRoleControllerService(userId, getContext());
            mControllerServices.put(userId, controllerService);
        }
        return controllerService;
    }

    private void onRemoveUser(@UserIdInt int userId) {
        synchronized (mLock) {
            mControllerServices.remove(userId);
            RoleUserState userState = mUserStates.removeReturnOld(userId);
            if (userState != null) {
                userState.destroySyncLocked();
            }
        }
    }

    private class Stub extends IRoleManager.Stub {

        @Override
        public boolean isRoleAvailable(@NonNull String roleName) {
            Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");

            int userId = UserHandle.getUserId(getCallingUid());
            synchronized (mLock) {
                RoleUserState userState = getUserStateLocked(userId);
                return userState.isRoleAvailableLocked(roleName);
            }
        }

        @Override
        public boolean isRoleHeld(@NonNull String roleName, @NonNull String packageName) {
            Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
            Preconditions.checkStringNotEmpty(packageName, "packageName cannot be null or empty");
            int callingUid = getCallingUid();
            mAppOpsManager.checkPackage(callingUid, packageName);

            int userId = UserHandle.getUserId(callingUid);
            ArraySet<String> roleHolders = getRoleHoldersInternal(roleName, userId);
            if (roleHolders == null) {
                return false;
            }
            return roleHolders.contains(packageName);
        }

        @NonNull
        @Override
        public List<String> getRoleHoldersAsUser(@NonNull String roleName, @UserIdInt int userId) {
            Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
            if (!mUserManagerInternal.exists(userId)) {
                Slog.e(LOG_TAG, "user " + userId + " does not exist");
                return Collections.emptyList();
            }
            userId = handleIncomingUser(userId, "getRoleHoldersAsUser");
            getContext().enforceCallingOrSelfPermission(Manifest.permission.MANAGE_ROLE_HOLDERS,
                    "getRoleHoldersAsUser");

            ArraySet<String> roleHolders = getRoleHoldersInternal(roleName, userId);
            if (roleHolders == null) {
                return Collections.emptyList();
            }
            return new ArrayList<>(roleHolders);
        }

        @Nullable
        private ArraySet<String> getRoleHoldersInternal(@NonNull String roleName,
                @UserIdInt int userId) {
            synchronized (mLock) {
                RoleUserState userState = getUserStateLocked(userId);
                return userState.getRoleHoldersLocked(roleName);
            }
        }

        @Override
        public void addRoleHolderAsUser(@NonNull String roleName, @NonNull String packageName,
                @UserIdInt int userId, @NonNull IRoleManagerCallback callback) {
            Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
            Preconditions.checkStringNotEmpty(packageName, "packageName cannot be null or empty");
            Preconditions.checkNotNull(callback, "callback cannot be null");
            if (!mUserManagerInternal.exists(userId)) {
                Slog.e(LOG_TAG, "user " + userId + " does not exist");
                return;
            }
            userId = handleIncomingUser(userId, "addRoleHolderAsUser");
            getContext().enforceCallingOrSelfPermission(Manifest.permission.MANAGE_ROLE_HOLDERS,
                    "addRoleHolderAsUser");

            getControllerService(userId).onAddRoleHolder(roleName, packageName, callback);
        }

        @Override
        public void removeRoleHolderAsUser(@NonNull String roleName, @NonNull String packageName,
                @UserIdInt int userId, @NonNull IRoleManagerCallback callback) {
            Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
            Preconditions.checkStringNotEmpty(packageName, "packageName cannot be null or empty");
            Preconditions.checkNotNull(callback, "callback cannot be null");
            if (!mUserManagerInternal.exists(userId)) {
                Slog.e(LOG_TAG, "user " + userId + " does not exist");
                return;
            }
            userId = handleIncomingUser(userId, "removeRoleHolderAsUser");
            getContext().enforceCallingOrSelfPermission(Manifest.permission.MANAGE_ROLE_HOLDERS,
                    "removeRoleHolderAsUser");

            getControllerService(userId).onRemoveRoleHolder(roleName, packageName,
                    callback);
        }

        @Override
        public void clearRoleHoldersAsUser(@NonNull String roleName, @UserIdInt int userId,
                @NonNull IRoleManagerCallback callback) {
            Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
            Preconditions.checkNotNull(callback, "callback cannot be null");
            if (!mUserManagerInternal.exists(userId)) {
                Slog.e(LOG_TAG, "user " + userId + " does not exist");
                return;
            }
            userId = handleIncomingUser(userId, "clearRoleHoldersAsUser");
            getContext().enforceCallingOrSelfPermission(Manifest.permission.MANAGE_ROLE_HOLDERS,
                    "clearRoleHoldersAsUser");

            getControllerService(userId).onClearRoleHolders(roleName, callback);
        }

        @Override
        public boolean addRoleHolderFromController(@NonNull String roleName,
                @NonNull String packageName) {
            Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
            Preconditions.checkStringNotEmpty(packageName, "packageName cannot be null or empty");
            getContext().enforceCallingOrSelfPermission(
                    RoleManager.PERMISSION_MANAGE_ROLE_HOLDERS_FROM_CONTROLLER,
                    "addRoleHolderFromController");

            int userId = UserHandle.getCallingUserId();
            synchronized (mLock) {
                RoleUserState userState = getUserStateLocked(userId);
                return userState.addRoleHolderLocked(roleName, packageName);
            }
        }

        @Override
        public boolean removeRoleHolderFromController(@NonNull String roleName,
                @NonNull String packageName) {
            Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
            Preconditions.checkStringNotEmpty(packageName, "packageName cannot be null or empty");
            getContext().enforceCallingOrSelfPermission(
                    RoleManager.PERMISSION_MANAGE_ROLE_HOLDERS_FROM_CONTROLLER,
                    "removeRoleHolderFromController");

            int userId = UserHandle.getCallingUserId();
            synchronized (mLock) {
                RoleUserState userState = getUserStateLocked(userId);
                return userState.removeRoleHolderLocked(roleName, packageName);
            }
        }

        @CheckResult
        private int handleIncomingUser(@UserIdInt int userId, @NonNull String name) {
            return ActivityManager.handleIncomingUser(getCallingPid(), getCallingUid(), userId,
                    false, true, name, null);
        }
    }
}
