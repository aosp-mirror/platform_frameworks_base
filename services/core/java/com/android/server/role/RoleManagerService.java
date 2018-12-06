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
import android.content.pm.PackageManagerInternal;
import android.content.pm.Signature;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.os.UserManagerInternal;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.PackageUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.BitUtils;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FunctionalUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
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
            public void onReceive(@NonNull Context context, @NonNull Intent intent) {
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
        RoleUserState userState;
        userState = getOrCreateUserState(userId);
        String packagesHash = computeComponentStateHash(userId);
        String oldPackagesHash = userState.getPackagesHash();
        boolean needGrant = !Objects.equals(packagesHash, oldPackagesHash);
        if (needGrant) {
            // Some vital packages state has changed since last role grant
            // Run grants again
            Slog.i(LOG_TAG, "Granting default permissions...");
            CompletableFuture<Void> result = new CompletableFuture<>();
            getOrCreateControllerService(userId).onGrantDefaultRoles(
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
                userState.setPackagesHash(packagesHash);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                Slog.e(LOG_TAG, "Failed to grant defaults for user " + userId, e);
            }
        } else if (RemoteRoleControllerService.DEBUG) {
            Slog.i(LOG_TAG, "Already ran grants for package state " + packagesHash);
        }
    }

    @Nullable
    private String computeComponentStateHash(@UserIdInt int userId) {
        PackageManagerInternal pm = LocalServices.getService(PackageManagerInternal.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        pm.forEachPackage(FunctionalUtils.uncheckExceptions(pkg -> {
            out.write(pkg.packageName.getBytes());
            out.write(BitUtils.toBytes(pkg.getLongVersionCode()));
            out.write(pm.getApplicationEnabledState(pkg.packageName, userId));

            ArraySet<String> enabledComponents =
                    pm.getEnabledComponents(pkg.packageName, userId);
            int numComponents = CollectionUtils.size(enabledComponents);
            for (int i = 0; i < numComponents; i++) {
                out.write(enabledComponents.valueAt(i).getBytes());
            }

            ArraySet<String> disabledComponents =
                    pm.getDisabledComponents(pkg.packageName, userId);
            numComponents = CollectionUtils.size(disabledComponents);
            for (int i = 0; i < numComponents; i++) {
                out.write(disabledComponents.valueAt(i).getBytes());
            }
            for (Signature signature : pkg.mSigningDetails.signatures) {
                out.write(signature.toByteArray());
            }
        }));

        return PackageUtils.computeSha256Digest(out.toByteArray());
    }

    @NonNull
    private RoleUserState getOrCreateUserState(@UserIdInt int userId) {
        synchronized (mLock) {
            RoleUserState userState = mUserStates.get(userId);
            if (userState == null) {
                userState = new RoleUserState(userId);
                mUserStates.put(userId, userState);
            }
            return userState;
        }
    }

    @NonNull
    private RemoteRoleControllerService getOrCreateControllerService(@UserIdInt int userId) {
        synchronized (mLock) {
            RemoteRoleControllerService controllerService = mControllerServices.get(userId);
            if (controllerService == null) {
                controllerService = new RemoteRoleControllerService(userId, getContext());
                mControllerServices.put(userId, controllerService);
            }
            return controllerService;
        }
    }

    private void onRemoveUser(@UserIdInt int userId) {
        RoleUserState userState;
        synchronized (mLock) {
            mControllerServices.remove(userId);
            userState = mUserStates.removeReturnOld(userId);
        }
        if (userState != null) {
            userState.destroy();
        }
    }

    private class Stub extends IRoleManager.Stub {

        @Override
        public boolean isRoleAvailable(@NonNull String roleName) {
            Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");

            int userId = UserHandle.getUserId(getCallingUid());
            RoleUserState userState = getOrCreateUserState(userId);
            return userState.isRoleAvailable(roleName);
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
            RoleUserState userState = getOrCreateUserState(userId);
            return userState.getRoleHolders(roleName);
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

            getOrCreateControllerService(userId).onAddRoleHolder(roleName, packageName, callback);
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

            getOrCreateControllerService(userId).onRemoveRoleHolder(roleName, packageName,
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

            getOrCreateControllerService(userId).onClearRoleHolders(roleName, callback);
        }

        @Override
        public void setRoleNamesFromController(@NonNull List<String> roleNames) {
            Preconditions.checkNotNull(roleNames, "roleNames cannot be null");
            getContext().enforceCallingOrSelfPermission(
                    RoleManager.PERMISSION_MANAGE_ROLES_FROM_CONTROLLER,
                    "setRoleNamesFromController");

            int userId = UserHandle.getCallingUserId();
            RoleUserState userState = getOrCreateUserState(userId);
            userState.setRoleNames(roleNames);
        }

        @Override
        public boolean addRoleHolderFromController(@NonNull String roleName,
                @NonNull String packageName) {
            Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
            Preconditions.checkStringNotEmpty(packageName, "packageName cannot be null or empty");
            getContext().enforceCallingOrSelfPermission(
                    RoleManager.PERMISSION_MANAGE_ROLES_FROM_CONTROLLER,
                    "addRoleHolderFromController");

            int userId = UserHandle.getCallingUserId();
            RoleUserState userState = getOrCreateUserState(userId);
            return userState.addRoleHolder(roleName, packageName);
        }

        @Override
        public boolean removeRoleHolderFromController(@NonNull String roleName,
                @NonNull String packageName) {
            Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
            Preconditions.checkStringNotEmpty(packageName, "packageName cannot be null or empty");
            getContext().enforceCallingOrSelfPermission(
                    RoleManager.PERMISSION_MANAGE_ROLES_FROM_CONTROLLER,
                    "removeRoleHolderFromController");

            int userId = UserHandle.getCallingUserId();
            RoleUserState userState = getOrCreateUserState(userId);
            return userState.removeRoleHolder(roleName, packageName);
        }

        @CheckResult
        private int handleIncomingUser(@UserIdInt int userId, @NonNull String name) {
            return ActivityManager.handleIncomingUser(getCallingPid(), getCallingUid(), userId,
                    false, true, name, null);
        }

        @Override
        public void onShellCommand(@Nullable FileDescriptor in, @Nullable FileDescriptor out,
                @Nullable FileDescriptor err, @NonNull String[] args,
                @Nullable ShellCallback callback, @NonNull ResultReceiver resultReceiver) {
            new RoleManagerShellCommand(this).exec(this, in, out, err, args, callback,
                    resultReceiver);
        }

        @Override
        protected void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter fout,
                @Nullable String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), LOG_TAG, fout)) {
                return;
            }

            boolean dumpAsProto = args != null && ArrayUtils.contains(args, "--proto");
            DualDumpOutputStream dumpOutputStream;
            if (dumpAsProto) {
                dumpOutputStream = new DualDumpOutputStream(new ProtoOutputStream(fd));
            } else {
                fout.println("ROLE MANAGER STATE (dumpsys role):");
                dumpOutputStream = new DualDumpOutputStream(new IndentingPrintWriter(fout, "  "));
            }

            int[] userIds = mUserManagerInternal.getUserIds();
            int userIdsLength = userIds.length;
            for (int i = 0; i < userIdsLength; i++) {
                int userId = userIds[i];

                RoleUserState userState = getOrCreateUserState(userId);
                userState.dump(dumpOutputStream, "user_states",
                        RoleManagerServiceDumpProto.USER_STATES);
            }

            dumpOutputStream.flush();
        }
    }
}
