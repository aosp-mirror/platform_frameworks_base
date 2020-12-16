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
import android.annotation.AnyThread;
import android.annotation.CheckResult;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.annotation.WorkerThread;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.role.IOnRoleHoldersChangedListener;
import android.app.role.IRoleManager;
import android.app.role.RoleControllerManager;
import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManagerInternal;
import android.content.pm.Signature;
import android.os.Binder;
import android.os.Handler;
import android.os.RemoteCallback;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.PackageUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.infra.AndroidFuture;
import com.android.internal.infra.ThrottledRunnable;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.BitUtils;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FunctionalUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.pm.UserManagerInternal;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Service for role management.
 *
 * @see RoleManager
 */
public class RoleManagerService extends SystemService implements RoleUserState.Callback {

    private static final String LOG_TAG = RoleManagerService.class.getSimpleName();

    private static final boolean DEBUG = false;

    private static final long GRANT_DEFAULT_ROLES_INTERVAL_MILLIS = 1000;

    @NonNull
    private final UserManagerInternal mUserManagerInternal;
    @NonNull
    private final AppOpsManager mAppOpsManager;

    @NonNull
    private final Object mLock = new Object();

    @NonNull
    private final RoleHoldersResolver mLegacyRoleResolver;

    /** @see #getRoleHolders(String, int) */
    public interface RoleHoldersResolver {
        /** @return a list of packages that hold a given role for a given user */
        @NonNull
        List<String> getRoleHolders(@NonNull String roleName, @UserIdInt int userId);
    }

    /**
     * Maps user id to its state.
     */
    @GuardedBy("mLock")
    @NonNull
    private final SparseArray<RoleUserState> mUserStates = new SparseArray<>();

    /**
     * Maps user id to its controller.
     */
    @GuardedBy("mLock")
    @NonNull
    private final SparseArray<RoleControllerManager> mControllers = new SparseArray<>();

    /**
     * Maps user id to its list of listeners.
     */
    @GuardedBy("mLock")
    @NonNull
    private final SparseArray<RemoteCallbackList<IOnRoleHoldersChangedListener>> mListeners =
            new SparseArray<>();

    @NonNull
    private final Handler mListenerHandler = FgThread.getHandler();

    /**
     * Maps user id to its throttled runnable for granting default roles.
     */
    @GuardedBy("mLock")
    @NonNull
    private final SparseArray<ThrottledRunnable> mGrantDefaultRolesThrottledRunnables =
            new SparseArray<>();

    public RoleManagerService(@NonNull Context context,
            @NonNull RoleHoldersResolver legacyRoleResolver) {
        super(context);

        mLegacyRoleResolver = legacyRoleResolver;

        RoleControllerManager.initializeRemoteServiceComponentName(context);

        mUserManagerInternal = LocalServices.getService(UserManagerInternal.class);
        mAppOpsManager = context.getSystemService(AppOpsManager.class);

        LocalServices.addService(RoleManagerInternal.class, new Internal());

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

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addDataScheme("package");
        intentFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        getContext().registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int userId = UserHandle.getUserId(intent.getIntExtra(Intent.EXTRA_UID, -1));
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Packages changed - re-running initial grants for user "
                            + userId);
                }
                if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                        && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    // Package is being upgraded - we're about to get ACTION_PACKAGE_ADDED
                    return;
                }
                maybeGrantDefaultRolesAsync(userId);
            }
        }, UserHandle.ALL, intentFilter, null, null);
    }

    @Override
    public void onUserStarting(@NonNull TargetUser user) {
        maybeGrantDefaultRolesSync(user.getUserIdentifier());
    }

    @MainThread
    private void maybeGrantDefaultRolesSync(@UserIdInt int userId) {
        AndroidFuture<Void> future = maybeGrantDefaultRolesInternal(userId);
        try {
            future.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            Slog.e(LOG_TAG, "Failed to grant default roles for user " + userId, e);
        }
    }

    private void maybeGrantDefaultRolesAsync(@UserIdInt int userId) {
        ThrottledRunnable runnable;
        synchronized (mLock) {
            runnable = mGrantDefaultRolesThrottledRunnables.get(userId);
            if (runnable == null) {
                runnable = new ThrottledRunnable(FgThread.getHandler(),
                        GRANT_DEFAULT_ROLES_INTERVAL_MILLIS,
                        () -> maybeGrantDefaultRolesInternal(userId));
                mGrantDefaultRolesThrottledRunnables.put(userId, runnable);
            }
        }
        runnable.run();
    }

    @AnyThread
    @NonNull
    private AndroidFuture<Void> maybeGrantDefaultRolesInternal(@UserIdInt int userId) {
        RoleUserState userState = getOrCreateUserState(userId);
        String oldPackagesHash = userState.getPackagesHash();
        String newPackagesHash = computeComponentStateHash(userId);
        if (Objects.equals(oldPackagesHash, newPackagesHash)) {
            if (DEBUG) {
                Slog.i(LOG_TAG, "Already granted default roles for packages hash "
                        + newPackagesHash);
            }
            return AndroidFuture.completedFuture(null);
        }

        //TODO gradually add more role migrations statements here for remaining roles
        // Make sure to implement LegacyRoleResolutionPolicy#getRoleHolders
        // for a given role before adding a migration statement for it here
        maybeMigrateRole(RoleManager.ROLE_ASSISTANT, userId);
        maybeMigrateRole(RoleManager.ROLE_BROWSER, userId);
        maybeMigrateRole(RoleManager.ROLE_DIALER, userId);
        maybeMigrateRole(RoleManager.ROLE_SMS, userId);
        maybeMigrateRole(RoleManager.ROLE_EMERGENCY, userId);
        maybeMigrateRole(RoleManager.ROLE_HOME, userId);

        // Some package state has changed, so grant default roles again.
        Slog.i(LOG_TAG, "Granting default roles...");
        AndroidFuture<Void> future = new AndroidFuture<>();
        getOrCreateController(userId).grantDefaultRoles(FgThread.getExecutor(),
                successful -> {
                    if (successful) {
                        userState.setPackagesHash(newPackagesHash);
                        future.complete(null);
                    } else {
                        future.completeExceptionally(new RuntimeException());
                    }
                });
        return future;
    }

    private void maybeMigrateRole(String role, @UserIdInt int userId) {
        // Any role for which we have a record are already migrated
        RoleUserState userState = getOrCreateUserState(userId);
        if (!userState.isRoleAvailable(role)) {
            List<String> roleHolders = mLegacyRoleResolver.getRoleHolders(role, userId);
            if (roleHolders.isEmpty()) {
                return;
            }
            Slog.i(LOG_TAG, "Migrating " + role + ", legacy holders: " + roleHolders);
            userState.addRoleName(role);
            int size = roleHolders.size();
            for (int i = 0; i < size; i++) {
                userState.addRoleHolder(role, roleHolders.get(i));
            }
        }
    }

    @Nullable
    private static String computeComponentStateHash(@UserIdInt int userId) {
        PackageManagerInternal pm = LocalServices.getService(PackageManagerInternal.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        pm.forEachInstalledPackage(FunctionalUtils.uncheckExceptions(pkg -> {
            out.write(pkg.getPackageName().getBytes());
            out.write(BitUtils.toBytes(pkg.getLongVersionCode()));
            out.write(pm.getApplicationEnabledState(pkg.getPackageName(), userId));

            ArraySet<String> enabledComponents =
                    pm.getEnabledComponents(pkg.getPackageName(), userId);
            int numComponents = CollectionUtils.size(enabledComponents);
            out.write(numComponents);
            for (int i = 0; i < numComponents; i++) {
                out.write(enabledComponents.valueAt(i).getBytes());
            }

            ArraySet<String> disabledComponents =
                    pm.getDisabledComponents(pkg.getPackageName(), userId);
            numComponents = CollectionUtils.size(disabledComponents);
            for (int i = 0; i < numComponents; i++) {
                out.write(disabledComponents.valueAt(i).getBytes());
            }
            for (Signature signature : pkg.getSigningDetails().signatures) {
                out.write(signature.toByteArray());
            }
        }), userId);

        return PackageUtils.computeSha256Digest(out.toByteArray());
    }

    @NonNull
    private RoleUserState getOrCreateUserState(@UserIdInt int userId) {
        synchronized (mLock) {
            RoleUserState userState = mUserStates.get(userId);
            if (userState == null) {
                userState = new RoleUserState(userId, this);
                mUserStates.put(userId, userState);
            }
            return userState;
        }
    }

    @NonNull
    private RoleControllerManager getOrCreateController(@UserIdInt int userId) {
        synchronized (mLock) {
            RoleControllerManager controller = mControllers.get(userId);
            if (controller == null) {
                Context systemContext = getContext();
                Context context;
                try {
                    context = systemContext.createPackageContextAsUser(
                            systemContext.getPackageName(), 0, UserHandle.of(userId));
                } catch (NameNotFoundException e) {
                    throw new RuntimeException(e);
                }
                controller = RoleControllerManager.createWithInitializedRemoteServiceComponentName(
                        FgThread.getHandler(), context);
                mControllers.put(userId, controller);
            }
            return controller;
        }
    }

    @Nullable
    private RemoteCallbackList<IOnRoleHoldersChangedListener> getListeners(@UserIdInt int userId) {
        synchronized (mLock) {
            return mListeners.get(userId);
        }
    }

    @NonNull
    private RemoteCallbackList<IOnRoleHoldersChangedListener> getOrCreateListeners(
            @UserIdInt int userId) {
        synchronized (mLock) {
            RemoteCallbackList<IOnRoleHoldersChangedListener> listeners = mListeners.get(userId);
            if (listeners == null) {
                listeners = new RemoteCallbackList<>();
                mListeners.put(userId, listeners);
            }
            return listeners;
        }
    }

    private void onRemoveUser(@UserIdInt int userId) {
        RemoteCallbackList<IOnRoleHoldersChangedListener> listeners;
        RoleUserState userState;
        synchronized (mLock) {
            mGrantDefaultRolesThrottledRunnables.remove(userId);
            listeners = mListeners.removeReturnOld(userId);
            mControllers.remove(userId);
            userState = mUserStates.removeReturnOld(userId);
        }
        if (listeners != null) {
            listeners.kill();
        }
        if (userState != null) {
            userState.destroy();
        }
    }

    @Override
    public void onRoleHoldersChanged(@NonNull String roleName, @UserIdInt int userId) {
        mListenerHandler.sendMessage(PooledLambda.obtainMessage(
                RoleManagerService::notifyRoleHoldersChanged, this, roleName, userId));
    }

    @WorkerThread
    private void notifyRoleHoldersChanged(@NonNull String roleName, @UserIdInt int userId) {
        RemoteCallbackList<IOnRoleHoldersChangedListener> listeners = getListeners(userId);
        if (listeners != null) {
            notifyRoleHoldersChangedForListeners(listeners, roleName, userId);
        }

        RemoteCallbackList<IOnRoleHoldersChangedListener> allUsersListeners = getListeners(
                UserHandle.USER_ALL);
        if (allUsersListeners != null) {
            notifyRoleHoldersChangedForListeners(allUsersListeners, roleName, userId);
        }
    }

    @WorkerThread
    private void notifyRoleHoldersChangedForListeners(
            @NonNull RemoteCallbackList<IOnRoleHoldersChangedListener> listeners,
            @NonNull String roleName, @UserIdInt int userId) {
        int broadcastCount = listeners.beginBroadcast();
        try {
            for (int i = 0; i < broadcastCount; i++) {
                IOnRoleHoldersChangedListener listener = listeners.getBroadcastItem(i);
                try {
                    listener.onRoleHoldersChanged(roleName, userId);
                } catch (RemoteException e) {
                    Slog.e(LOG_TAG, "Error calling OnRoleHoldersChangedListener", e);
                }
            }
        } finally {
            listeners.finishBroadcast();
        }
    }

    private class Stub extends IRoleManager.Stub {

        @Override
        public boolean isRoleAvailable(@NonNull String roleName) {
            Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");

            int userId = UserHandle.getUserId(getCallingUid());
            return getOrCreateUserState(userId).isRoleAvailable(roleName);
        }

        @Override
        public boolean isRoleHeld(@NonNull String roleName, @NonNull String packageName) {
            int callingUid = getCallingUid();
            mAppOpsManager.checkPackage(callingUid, packageName);

            Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
            Preconditions.checkStringNotEmpty(packageName, "packageName cannot be null or empty");

            int userId = UserHandle.getUserId(callingUid);
            ArraySet<String> roleHolders = getOrCreateUserState(userId).getRoleHolders(roleName);
            if (roleHolders == null) {
                return false;
            }
            return roleHolders.contains(packageName);
        }

        @NonNull
        @Override
        public List<String> getRoleHoldersAsUser(@NonNull String roleName, @UserIdInt int userId) {
            if (!mUserManagerInternal.exists(userId)) {
                Slog.e(LOG_TAG, "user " + userId + " does not exist");
                return Collections.emptyList();
            }
            userId = handleIncomingUser(userId, false, "getRoleHoldersAsUser");
            getContext().enforceCallingOrSelfPermission(Manifest.permission.MANAGE_ROLE_HOLDERS,
                    "getRoleHoldersAsUser");

            Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");

            ArraySet<String> roleHolders = getOrCreateUserState(userId).getRoleHolders(roleName);
            if (roleHolders == null) {
                return Collections.emptyList();
            }
            return new ArrayList<>(roleHolders);
        }

        @Override
        public void addRoleHolderAsUser(@NonNull String roleName, @NonNull String packageName,
                @RoleManager.ManageHoldersFlags int flags, @UserIdInt int userId,
                @NonNull RemoteCallback callback) {
            if (!mUserManagerInternal.exists(userId)) {
                Slog.e(LOG_TAG, "user " + userId + " does not exist");
                return;
            }
            userId = handleIncomingUser(userId, false, "addRoleHolderAsUser");
            getContext().enforceCallingOrSelfPermission(Manifest.permission.MANAGE_ROLE_HOLDERS,
                    "addRoleHolderAsUser");

            Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
            Preconditions.checkStringNotEmpty(packageName, "packageName cannot be null or empty");
            Objects.requireNonNull(callback, "callback cannot be null");

            getOrCreateController(userId).onAddRoleHolder(roleName, packageName, flags,
                    callback);
        }

        @Override
        public void removeRoleHolderAsUser(@NonNull String roleName, @NonNull String packageName,
                @RoleManager.ManageHoldersFlags int flags, @UserIdInt int userId,
                @NonNull RemoteCallback callback) {
            if (!mUserManagerInternal.exists(userId)) {
                Slog.e(LOG_TAG, "user " + userId + " does not exist");
                return;
            }
            userId = handleIncomingUser(userId, false, "removeRoleHolderAsUser");
            getContext().enforceCallingOrSelfPermission(Manifest.permission.MANAGE_ROLE_HOLDERS,
                    "removeRoleHolderAsUser");

            Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
            Preconditions.checkStringNotEmpty(packageName, "packageName cannot be null or empty");
            Objects.requireNonNull(callback, "callback cannot be null");

            getOrCreateController(userId).onRemoveRoleHolder(roleName, packageName, flags,
                    callback);
        }

        @Override
        public void clearRoleHoldersAsUser(@NonNull String roleName,
                @RoleManager.ManageHoldersFlags int flags, @UserIdInt int userId,
                @NonNull RemoteCallback callback) {
            if (!mUserManagerInternal.exists(userId)) {
                Slog.e(LOG_TAG, "user " + userId + " does not exist");
                return;
            }
            userId = handleIncomingUser(userId, false, "clearRoleHoldersAsUser");
            getContext().enforceCallingOrSelfPermission(Manifest.permission.MANAGE_ROLE_HOLDERS,
                    "clearRoleHoldersAsUser");

            Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
            Objects.requireNonNull(callback, "callback cannot be null");

            getOrCreateController(userId).onClearRoleHolders(roleName, flags, callback);
        }

        @Override
        public void addOnRoleHoldersChangedListenerAsUser(
                @NonNull IOnRoleHoldersChangedListener listener, @UserIdInt int userId) {
            if (userId != UserHandle.USER_ALL && !mUserManagerInternal.exists(userId)) {
                Slog.e(LOG_TAG, "user " + userId + " does not exist");
                return;
            }
            userId = handleIncomingUser(userId, true, "addOnRoleHoldersChangedListenerAsUser");
            getContext().enforceCallingOrSelfPermission(Manifest.permission.OBSERVE_ROLE_HOLDERS,
                    "addOnRoleHoldersChangedListenerAsUser");

            Objects.requireNonNull(listener, "listener cannot be null");

            RemoteCallbackList<IOnRoleHoldersChangedListener> listeners = getOrCreateListeners(
                    userId);
            listeners.register(listener);
        }

        @Override
        public void removeOnRoleHoldersChangedListenerAsUser(
                @NonNull IOnRoleHoldersChangedListener listener, @UserIdInt int userId) {
            if (userId != UserHandle.USER_ALL && !mUserManagerInternal.exists(userId)) {
                Slog.e(LOG_TAG, "user " + userId + " does not exist");
                return;
            }
            userId = handleIncomingUser(userId, true, "removeOnRoleHoldersChangedListenerAsUser");
            getContext().enforceCallingOrSelfPermission(Manifest.permission.OBSERVE_ROLE_HOLDERS,
                    "removeOnRoleHoldersChangedListenerAsUser");

            Objects.requireNonNull(listener, "listener cannot be null");

            RemoteCallbackList<IOnRoleHoldersChangedListener> listeners = getListeners(userId);
            if (listener == null) {
                return;
            }
            listeners.unregister(listener);
        }

        @Override
        public void setRoleNamesFromController(@NonNull List<String> roleNames) {
            getContext().enforceCallingOrSelfPermission(
                    RoleManager.PERMISSION_MANAGE_ROLES_FROM_CONTROLLER,
                    "setRoleNamesFromController");

            Objects.requireNonNull(roleNames, "roleNames cannot be null");

            int userId = UserHandle.getCallingUserId();
            getOrCreateUserState(userId).setRoleNames(roleNames);
        }

        @Override
        public boolean addRoleHolderFromController(@NonNull String roleName,
                @NonNull String packageName) {
            getContext().enforceCallingOrSelfPermission(
                    RoleManager.PERMISSION_MANAGE_ROLES_FROM_CONTROLLER,
                    "addRoleHolderFromController");

            Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
            Preconditions.checkStringNotEmpty(packageName, "packageName cannot be null or empty");

            int userId = UserHandle.getCallingUserId();
            return getOrCreateUserState(userId).addRoleHolder(roleName, packageName);
        }

        @Override
        public boolean removeRoleHolderFromController(@NonNull String roleName,
                @NonNull String packageName) {
            getContext().enforceCallingOrSelfPermission(
                    RoleManager.PERMISSION_MANAGE_ROLES_FROM_CONTROLLER,
                    "removeRoleHolderFromController");

            Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
            Preconditions.checkStringNotEmpty(packageName, "packageName cannot be null or empty");

            int userId = UserHandle.getCallingUserId();
            return getOrCreateUserState(userId).removeRoleHolder(roleName, packageName);
        }

        @Override
        public List<String> getHeldRolesFromController(@NonNull String packageName) {
            getContext().enforceCallingOrSelfPermission(
                    RoleManager.PERMISSION_MANAGE_ROLES_FROM_CONTROLLER,
                    "getRolesHeldFromController");

            Preconditions.checkStringNotEmpty(packageName, "packageName cannot be null or empty");

            int userId = UserHandle.getCallingUserId();
            return getOrCreateUserState(userId).getHeldRoles(packageName);
        }

        @CheckResult
        private int handleIncomingUser(@UserIdInt int userId, boolean allowAll,
                @NonNull String name) {
            return ActivityManager.handleIncomingUser(getCallingPid(), getCallingUid(), userId,
                    allowAll, true, name, null);
        }

        @Override
        public void onShellCommand(@Nullable FileDescriptor in, @Nullable FileDescriptor out,
                @Nullable FileDescriptor err, @NonNull String[] args,
                @Nullable ShellCallback callback, @NonNull ResultReceiver resultReceiver) {
            new RoleManagerShellCommand(this).exec(this, in, out, err, args, callback,
                    resultReceiver);
        }

        @Nullable
        @Override
        public String getBrowserRoleHolder(@UserIdInt int userId) {
            final int callingUid = Binder.getCallingUid();
            if (UserHandle.getUserId(callingUid) != userId) {
                getContext().enforceCallingOrSelfPermission(
                        android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
            }
            final PackageManagerInternal packageManager = LocalServices.getService(
                    PackageManagerInternal.class);
            if (packageManager.getInstantAppPackageName(callingUid) != null) {
                return null;
            }

            final long identity = Binder.clearCallingIdentity();
            try {
                return CollectionUtils.firstOrNull(getRoleHoldersAsUser(RoleManager.ROLE_BROWSER,
                        userId));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public boolean setBrowserRoleHolder(@Nullable String packageName, @UserIdInt int userId) {
            final Context context = getContext();
            context.enforceCallingOrSelfPermission(
                    android.Manifest.permission.SET_PREFERRED_APPLICATIONS, null);
            if (UserHandle.getCallingUserId() != userId) {
                context.enforceCallingOrSelfPermission(
                        android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
            }

            if (!mUserManagerInternal.exists(userId)) {
                return false;
            }

            final AndroidFuture<Void> future = new AndroidFuture<>();
            final RemoteCallback callback = new RemoteCallback(result -> {
                boolean successful = result != null;
                if (successful) {
                    future.complete(null);
                } else {
                    future.completeExceptionally(new RuntimeException());
                }
            });
            final long identity = Binder.clearCallingIdentity();
            try {
                if (packageName != null) {
                    addRoleHolderAsUser(RoleManager.ROLE_BROWSER, packageName, 0, userId, callback);
                } else {
                    clearRoleHoldersAsUser(RoleManager.ROLE_BROWSER, 0, userId, callback);
                }
                try {
                    future.get(5, TimeUnit.SECONDS);
                } catch (InterruptedException | ExecutionException | TimeoutException e) {
                    Slog.e(LOG_TAG, "Exception while setting default browser: " + packageName, e);
                    return false;
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }

            return true;
        }

        @Override
        public String getSmsRoleHolder(int userId) {
            final long identity = Binder.clearCallingIdentity();
            try {
                return CollectionUtils.firstOrNull(getRoleHoldersAsUser(RoleManager.ROLE_SMS,
                        userId));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
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

    private class Internal extends RoleManagerInternal {

        @NonNull
        @Override
        public ArrayMap<String, ArraySet<String>> getRolesAndHolders(@UserIdInt int userId) {
            return getOrCreateUserState(userId).getRolesAndHolders();
        }
    }
}
