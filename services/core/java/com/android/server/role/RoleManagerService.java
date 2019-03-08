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
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.annotation.WorkerThread;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.role.IOnRoleHoldersChangedListener;
import android.app.role.IRoleManager;
import android.app.role.IRoleManagerCallback;
import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.PermissionChecker;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManagerInternal;
import android.content.pm.Signature;
import android.database.ContentObserver;
import android.database.CursorWindow;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteCallback;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.os.UserManagerInternal;
import android.provider.Settings;
import android.service.sms.FinancialSmsService;
import android.telephony.IFinancialSmsCallback;
import android.text.TextUtils;
import android.util.ArrayMap;
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
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.FgThread;
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
public class RoleManagerService extends SystemService implements RoleUserState.Callback {

    private static final String LOG_TAG = RoleManagerService.class.getSimpleName();

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
     * Maps user id to its controller service.
     */
    @GuardedBy("mLock")
    @NonNull
    private final SparseArray<RemoteRoleControllerService> mControllerServices =
            new SparseArray<>();

    /**
     * Maps user id to its list of listeners.
     */
    @GuardedBy("mLock")
    @NonNull
    private final SparseArray<RemoteCallbackList<IOnRoleHoldersChangedListener>> mListeners =
            new SparseArray<>();

    @NonNull
    private final Handler mListenerHandler = FgThread.getHandler();

    public RoleManagerService(@NonNull Context context,
            @NonNull RoleHoldersResolver legacyRoleResolver) {
        super(context);

        mLegacyRoleResolver = legacyRoleResolver;

        mUserManagerInternal = LocalServices.getService(UserManagerInternal.class);
        mAppOpsManager = context.getSystemService(AppOpsManager.class);

        LocalServices.addService(RoleManagerInternal.class, new Internal());

        PackageManagerInternal packageManagerInternal = LocalServices.getService(
                PackageManagerInternal.class);
        packageManagerInternal.setDefaultBrowserProvider(new DefaultBrowserProvider());
        packageManagerInternal.setDefaultHomeProvider(new DefaultHomeProvider());

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
                if (RemoteRoleControllerService.DEBUG) {
                    Slog.i(LOG_TAG,
                            "Packages changed - re-running initial grants for user " + userId);
                }
                performInitialGrantsIfNecessary(userId);
            }
        }, UserHandle.ALL, intentFilter, null, null);

        getContext().getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.SMS_ACCESS_RESTRICTION_ENABLED), false,
                new ContentObserver(getContext().getMainThreadHandler()) {
                    @Override
                    public void onChange(boolean selfChange, Uri uri, int userId) {
                        boolean killSwitchEnabled = Settings.Global.getInt(
                                getContext().getContentResolver(),
                                Settings.Global.SMS_ACCESS_RESTRICTION_ENABLED, 0) == 1;
                        for (int user : mUserManagerInternal.getUserIds()) {
                            if (mUserManagerInternal.isUserRunning(user)) {
                                getOrCreateControllerService(user)
                                        .onSmsKillSwitchToggled(killSwitchEnabled);
                            }
                        }
                    }
                }, UserHandle.USER_ALL);
    }

    @Override
    public void onStartUser(@UserIdInt int userId) {
        performInitialGrantsIfNecessary(userId);
    }

    @MainThread
    private void performInitialGrantsIfNecessary(@UserIdInt int userId) {
        RoleUserState userState;
        userState = getOrCreateUserState(userId);

        String packagesHash = computeComponentStateHash(userId);
        String oldPackagesHash = userState.getPackagesHash();
        boolean needGrant = !Objects.equals(packagesHash, oldPackagesHash);
        if (needGrant) {

            //TODO gradually add more role migrations statements here for remaining roles
            // Make sure to implement LegacyRoleResolutionPolicy#getRoleHolders
            // for a given role before adding a migration statement for it here
            migrateRoleIfNecessary(RoleManager.ROLE_SMS, userId);
            migrateRoleIfNecessary(RoleManager.ROLE_ASSISTANT, userId);
            migrateRoleIfNecessary(RoleManager.ROLE_DIALER, userId);
            migrateRoleIfNecessary(RoleManager.ROLE_EMERGENCY, userId);

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
                result.get(30, TimeUnit.SECONDS);
                userState.setPackagesHash(packagesHash);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                Slog.e(LOG_TAG, "Failed to grant defaults for user " + userId, e);
            }
        } else if (RemoteRoleControllerService.DEBUG) {
            Slog.i(LOG_TAG, "Already ran grants for package state " + packagesHash);
        }
    }

    private void migrateRoleIfNecessary(String role, @UserIdInt int userId) {
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
                userState = new RoleUserState(userId, this);
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
            listeners = mListeners.removeReturnOld(userId);
            mControllerServices.remove(userId);
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
            Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
            Preconditions.checkStringNotEmpty(packageName, "packageName cannot be null or empty");
            int callingUid = getCallingUid();
            mAppOpsManager.checkPackage(callingUid, packageName);

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
            Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
            if (!mUserManagerInternal.exists(userId)) {
                Slog.e(LOG_TAG, "user " + userId + " does not exist");
                return Collections.emptyList();
            }
            userId = handleIncomingUser(userId, false, "getRoleHoldersAsUser");
            getContext().enforceCallingOrSelfPermission(Manifest.permission.MANAGE_ROLE_HOLDERS,
                    "getRoleHoldersAsUser");

            ArraySet<String> roleHolders = getOrCreateUserState(userId).getRoleHolders(roleName);
            if (roleHolders == null) {
                return Collections.emptyList();
            }
            return new ArrayList<>(roleHolders);
        }

        @Override
        public void addRoleHolderAsUser(@NonNull String roleName, @NonNull String packageName,
                @RoleManager.ManageHoldersFlags int flags, @UserIdInt int userId,
                @NonNull IRoleManagerCallback callback) {
            Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
            Preconditions.checkStringNotEmpty(packageName, "packageName cannot be null or empty");
            Preconditions.checkNotNull(callback, "callback cannot be null");
            if (!mUserManagerInternal.exists(userId)) {
                Slog.e(LOG_TAG, "user " + userId + " does not exist");
                return;
            }
            userId = handleIncomingUser(userId, false, "addRoleHolderAsUser");
            getContext().enforceCallingOrSelfPermission(Manifest.permission.MANAGE_ROLE_HOLDERS,
                    "addRoleHolderAsUser");

            getOrCreateControllerService(userId).onAddRoleHolder(roleName, packageName, flags,
                    callback);
        }

        @Override
        public void removeRoleHolderAsUser(@NonNull String roleName, @NonNull String packageName,
                @RoleManager.ManageHoldersFlags int flags, @UserIdInt int userId,
                @NonNull IRoleManagerCallback callback) {
            Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
            Preconditions.checkStringNotEmpty(packageName, "packageName cannot be null or empty");
            Preconditions.checkNotNull(callback, "callback cannot be null");
            if (!mUserManagerInternal.exists(userId)) {
                Slog.e(LOG_TAG, "user " + userId + " does not exist");
                return;
            }
            userId = handleIncomingUser(userId, false, "removeRoleHolderAsUser");
            getContext().enforceCallingOrSelfPermission(Manifest.permission.MANAGE_ROLE_HOLDERS,
                    "removeRoleHolderAsUser");

            getOrCreateControllerService(userId).onRemoveRoleHolder(roleName, packageName, flags,
                    callback);
        }

        @Override
        public void clearRoleHoldersAsUser(@NonNull String roleName,
                @RoleManager.ManageHoldersFlags int flags, @UserIdInt int userId,
                @NonNull IRoleManagerCallback callback) {
            Preconditions.checkStringNotEmpty(roleName, "roleName cannot be null or empty");
            Preconditions.checkNotNull(callback, "callback cannot be null");
            if (!mUserManagerInternal.exists(userId)) {
                Slog.e(LOG_TAG, "user " + userId + " does not exist");
                return;
            }
            userId = handleIncomingUser(userId, false, "clearRoleHoldersAsUser");
            getContext().enforceCallingOrSelfPermission(Manifest.permission.MANAGE_ROLE_HOLDERS,
                    "clearRoleHoldersAsUser");

            getOrCreateControllerService(userId).onClearRoleHolders(roleName, flags, callback);
        }

        @Override
        public void addOnRoleHoldersChangedListenerAsUser(
                @NonNull IOnRoleHoldersChangedListener listener, @UserIdInt int userId) {
            Preconditions.checkNotNull(listener, "listener cannot be null");
            if (userId != UserHandle.USER_ALL && !mUserManagerInternal.exists(userId)) {
                Slog.e(LOG_TAG, "user " + userId + " does not exist");
                return;
            }
            userId = handleIncomingUser(userId, true, "addOnRoleHoldersChangedListenerAsUser");
            getContext().enforceCallingOrSelfPermission(Manifest.permission.OBSERVE_ROLE_HOLDERS,
                    "addOnRoleHoldersChangedListenerAsUser");

            RemoteCallbackList<IOnRoleHoldersChangedListener> listeners = getOrCreateListeners(
                    userId);
            listeners.register(listener);
        }

        @Override
        public void removeOnRoleHoldersChangedListenerAsUser(
                @NonNull IOnRoleHoldersChangedListener listener, @UserIdInt int userId) {
            Preconditions.checkNotNull(listener, "listener cannot be null");
            if (userId != UserHandle.USER_ALL && !mUserManagerInternal.exists(userId)) {
                Slog.e(LOG_TAG, "user " + userId + " does not exist");
                return;
            }
            userId = handleIncomingUser(userId, true, "removeOnRoleHoldersChangedListenerAsUser");
            getContext().enforceCallingOrSelfPermission(Manifest.permission.OBSERVE_ROLE_HOLDERS,
                    "removeOnRoleHoldersChangedListenerAsUser");

            RemoteCallbackList<IOnRoleHoldersChangedListener> listeners = getListeners(userId);
            if (listener == null) {
                return;
            }
            listeners.unregister(listener);
        }

        @Override
        public void setRoleNamesFromController(@NonNull List<String> roleNames) {
            Preconditions.checkNotNull(roleNames, "roleNames cannot be null");
            getContext().enforceCallingOrSelfPermission(
                    RoleManager.PERMISSION_MANAGE_ROLES_FROM_CONTROLLER,
                    "setRoleNamesFromController");

            int userId = UserHandle.getCallingUserId();
            getOrCreateUserState(userId).setRoleNames(roleNames);
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
            return getOrCreateUserState(userId).addRoleHolder(roleName, packageName);
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
            return getOrCreateUserState(userId).removeRoleHolder(roleName, packageName);
        }

        @Override
        public List<String> getHeldRolesFromController(@NonNull String packageName) {
            Preconditions.checkStringNotEmpty(packageName, "packageName cannot be null or empty");
            getContext().enforceCallingOrSelfPermission(
                    RoleManager.PERMISSION_MANAGE_ROLES_FROM_CONTROLLER,
                    "getRolesHeldFromController");

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

        @Override
        public String getDefaultSmsPackage(int userId) {
            long identity = Binder.clearCallingIdentity();
            try {
                return CollectionUtils.firstOrNull(
                        getRoleHoldersAsUser(RoleManager.ROLE_SMS, userId));
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

        /**
         * Get filtered SMS messages for financial app.
         */
        @Override
        public void getSmsMessagesForFinancialApp(
                String callingPkg, Bundle params, IFinancialSmsCallback callback) {
            int mode = PermissionChecker.checkCallingOrSelfPermission(
                    getContext(),
                    AppOpsManager.OPSTR_SMS_FINANCIAL_TRANSACTIONS);

            if (mode == PermissionChecker.PERMISSION_GRANTED) {
                FinancialSmsManager financialSmsManager = new FinancialSmsManager(getContext());
                financialSmsManager.getSmsMessages(new RemoteCallback((result) -> {
                    CursorWindow messages = null;
                    if (result == null) {
                        Slog.w(LOG_TAG, "result is null.");
                    } else {
                        messages = result.getParcelable(FinancialSmsService.EXTRA_SMS_MSGS);
                    }
                    try {
                        callback.onGetSmsMessagesForFinancialApp(messages);
                    } catch (RemoteException e) {
                        // do nothing
                    }
                }), params);
            } else {
                try {
                    callback.onGetSmsMessagesForFinancialApp(null);
                } catch (RemoteException e) {
                    // do nothing
                }
            }
        }

        private int getUidForPackage(String packageName) {
            long ident = Binder.clearCallingIdentity();
            try {
                return getContext().getPackageManager().getApplicationInfo(packageName,
                        PackageManager.MATCH_ANY_USER).uid;
            } catch (NameNotFoundException nnfe) {
                return -1;
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    private class Internal extends RoleManagerInternal {

        @NonNull
        @Override
        public ArrayMap<String, ArraySet<String>> getRolesAndHolders(@UserIdInt int userId) {
            return getOrCreateUserState(userId).getRolesAndHolders();
        }
    }

    private class DefaultBrowserProvider implements PackageManagerInternal.DefaultBrowserProvider {

        @Nullable
        @Override
        public String getDefaultBrowser(@UserIdInt int userId) {
            return CollectionUtils.firstOrNull(getOrCreateUserState(userId).getRoleHolders(
                    RoleManager.ROLE_BROWSER));
        }

        @Override
        public boolean setDefaultBrowser(@Nullable String packageName, @UserIdInt int userId) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            IRoleManagerCallback callback = new IRoleManagerCallback.Stub() {
                @Override
                public void onSuccess() {
                    future.complete(null);
                }
                @Override
                public void onFailure() {
                    future.completeExceptionally(new RuntimeException());
                }
            };
            if (packageName != null) {
                getOrCreateControllerService(userId).onAddRoleHolder(RoleManager.ROLE_BROWSER,
                        packageName, 0, callback);
            } else {
                getOrCreateControllerService(userId).onClearRoleHolders(RoleManager.ROLE_BROWSER, 0,
                        callback);
            }
            try {
                future.get(5, TimeUnit.SECONDS);
                return true;
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                Slog.e(LOG_TAG, "Exception while setting default browser: " + packageName, e);
                return false;
            }
        }

        @Override
        public void setDefaultBrowserAsync(@Nullable String packageName, @UserIdInt int userId) {
            IRoleManagerCallback callback = new IRoleManagerCallback.Stub() {
                @Override
                public void onSuccess() {}
                @Override
                public void onFailure() {
                    Slog.e(LOG_TAG, "Failed to set default browser: " + packageName);
                }
            };
            if (packageName != null) {
                getOrCreateControllerService(userId).onAddRoleHolder(RoleManager.ROLE_BROWSER,
                        packageName, 0, callback);
            } else {
                getOrCreateControllerService(userId).onClearRoleHolders(RoleManager.ROLE_BROWSER, 0,
                        callback);
            }
        }
    }

    private class DefaultHomeProvider implements PackageManagerInternal.DefaultHomeProvider {

        @Nullable
        @Override
        public String getDefaultHome(@UserIdInt int userId) {
            return CollectionUtils.firstOrNull(getOrCreateUserState(userId).getRoleHolders(
                    RoleManager.ROLE_HOME));
        }

        @Override
        public void setDefaultHomeAsync(@Nullable String packageName, @UserIdInt int userId) {
            IRoleManagerCallback callback = new IRoleManagerCallback.Stub() {
                @Override
                public void onSuccess() {}
                @Override
                public void onFailure() {
                    Slog.e(LOG_TAG, "Failed to set default home: " + packageName);
                }
            };
            if (packageName != null) {
                getOrCreateControllerService(userId).onAddRoleHolder(RoleManager.ROLE_HOME,
                        packageName, 0, callback);
            } else {
                getOrCreateControllerService(userId).onClearRoleHolders(RoleManager.ROLE_HOME, 0,
                        callback);
            }
        }
    }
}
