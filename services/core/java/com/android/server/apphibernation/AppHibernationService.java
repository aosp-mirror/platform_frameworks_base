/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.apphibernation;

import static android.content.Intent.ACTION_PACKAGE_ADDED;
import static android.content.Intent.ACTION_PACKAGE_REMOVED;
import static android.content.Intent.ACTION_USER_ADDED;
import static android.content.Intent.ACTION_USER_REMOVED;
import static android.content.Intent.EXTRA_REPLACING;
import static android.content.pm.PackageManager.MATCH_ALL;
import static android.provider.DeviceConfig.NAMESPACE_APP_HIBERNATION;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.IActivityManager;
import android.apphibernation.IAppHibernationService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.util.ArrayMap;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.util.List;
import java.util.Map;

/**
 * System service that manages app hibernation state, a state apps can enter that means they are
 * not being actively used and can be optimized for storage. The actual policy for determining
 * if an app should hibernate is managed by PermissionController code.
 */
public final class AppHibernationService extends SystemService {
    private static final String TAG = "AppHibernationService";

    /**
     * Lock for accessing any in-memory hibernation state
     */
    private final Object mLock = new Object();
    private final Context mContext;
    private final IPackageManager mIPackageManager;
    private final IActivityManager mIActivityManager;
    private final UserManager mUserManager;
    @GuardedBy("mLock")
    private final SparseArray<Map<String, UserPackageState>> mUserStates = new SparseArray<>();

    /**
     * Initializes the system service.
     * <p>
     * Subclasses must define a single argument constructor that accepts the context
     * and passes it to super.
     * </p>
     *
     * @param context The system server context.
     */
    public AppHibernationService(@NonNull Context context) {
        this(context, IPackageManager.Stub.asInterface(ServiceManager.getService("package")),
                ActivityManager.getService(),
                context.getSystemService(UserManager.class));
    }

    @VisibleForTesting
    AppHibernationService(@NonNull Context context, IPackageManager packageManager,
            IActivityManager activityManager, UserManager userManager) {
        super(context);
        mContext = context;
        mIPackageManager = packageManager;
        mIActivityManager = activityManager;
        mUserManager = userManager;

        final Context userAllContext = mContext.createContextAsUser(UserHandle.ALL, 0 /* flags */);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_USER_ADDED);
        intentFilter.addAction(ACTION_USER_REMOVED);
        userAllContext.registerReceiver(mBroadcastReceiver, intentFilter);

        intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_PACKAGE_ADDED);
        intentFilter.addAction(ACTION_PACKAGE_REMOVED);
        intentFilter.addDataScheme("package");
        userAllContext.registerReceiver(mBroadcastReceiver, intentFilter);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.APP_HIBERNATION_SERVICE, mServiceStub);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_BOOT_COMPLETED) {
            synchronized (mLock) {
                final List<UserInfo> users = mUserManager.getUsers();
                // TODO: Pull from persistent disk storage. For now, just make from scratch.
                for (UserInfo user : users) {
                    addUserPackageStatesL(user.id);
                }
            }
        }
    }

    /**
     * Whether a package is hibernating for a given user.
     *
     * @param packageName the package to check
     * @param userId the user to check
     * @return true if package is hibernating for the user
     */
    public boolean isHibernating(String packageName, int userId) {
        userId = handleIncomingUser(userId, "isHibernating");
        synchronized (mLock) {
            final Map<String, UserPackageState> packageStates = mUserStates.get(userId);
            if (packageStates == null) {
                throw new IllegalArgumentException("No user associated with user id " + userId);
            }
            final UserPackageState pkgState = packageStates.get(packageName);
            if (pkgState == null) {
                throw new IllegalArgumentException(
                        String.format("Package %s is not installed for user %s",
                                packageName, userId));
            }
            return pkgState != null ? pkgState.hibernated : null;
        }
    }

    /**
     * Set whether the package is hibernating for the given user.
     *
     * @param packageName package to modify state
     * @param userId user
     * @param isHibernating new hibernation state
     */
    public void setHibernating(String packageName, int userId, boolean isHibernating) {
        userId = handleIncomingUser(userId, "setHibernating");
        synchronized (mLock) {
            if (!mUserStates.contains(userId)) {
                throw new IllegalArgumentException("No user associated with user id " + userId);
            }
            Map<String, UserPackageState> packageStates = mUserStates.get(userId);
            UserPackageState pkgState = packageStates.get(packageName);
            if (pkgState == null) {
                throw new IllegalArgumentException(
                        String.format("Package %s is not installed for user %s",
                                packageName, userId));
            }

            if (pkgState.hibernated == isHibernating) {
                return;
            }


            final long caller = Binder.clearCallingIdentity();
            try {
                if (isHibernating) {
                    Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "hibernatePackage");
                    mIActivityManager.forceStopPackage(packageName, userId);
                    mIPackageManager.deleteApplicationCacheFilesAsUser(packageName, userId,
                            null /* observer */);
                } else {
                    Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "unhibernatePackage");
                    mIPackageManager.setPackageStoppedState(packageName, false, userId);
                }
                pkgState.hibernated = isHibernating;
            } catch (RemoteException e) {
                throw new IllegalStateException(
                        "Failed to hibernate due to manager not being available", e);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
                Binder.restoreCallingIdentity(caller);
            }

            // TODO: Support package level hibernation when package is hibernating for all users
        }
    }

    /**
     * Populates {@link #mUserStates} with the users installed packages. The caller should hold
     * {@link #mLock}.
     *
     * @param userId user id to add installed packages for
     */
    private void addUserPackageStatesL(int userId) {
        Map<String, UserPackageState> packages = new ArrayMap<>();
        List<PackageInfo> packageList;
        try {
            packageList = mIPackageManager.getInstalledPackages(MATCH_ALL, userId).getList();
        } catch (RemoteException e) {
            throw new IllegalStateException("Package manager not available.", e);
        }

        for (PackageInfo pkg : packageList) {
            packages.put(pkg.packageName, new UserPackageState());
        }
        mUserStates.put(userId, packages);
    }

    private void onUserAdded(int userId) {
        synchronized (mLock) {
            addUserPackageStatesL(userId);
        }
    }

    private void onUserRemoved(int userId) {
        synchronized (mLock) {
            mUserStates.remove(userId);
        }
    }

    private void onPackageAdded(@NonNull String packageName, int userId) {
        synchronized (mLock) {
            mUserStates.get(userId).put(packageName, new UserPackageState());
        }
    }

    private void onPackageRemoved(@NonNull String packageName, int userId) {
        synchronized (mLock) {
            mUserStates.get(userId).remove(packageName);
        }
    }

    /**
     * Private helper method to get the real user id and enforce permission checks.
     *
     * @param userId user id to handle
     * @param name name to use for exceptions
     * @return real user id
     */
    private int handleIncomingUser(int userId, @NonNull String name) {
        int callingUid = Binder.getCallingUid();
        try {
            return mIActivityManager.handleIncomingUser(Binder.getCallingPid(), callingUid, userId,
                    false /* allowAll */, true /* requireFull */, name, null);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    private final AppHibernationServiceStub mServiceStub = new AppHibernationServiceStub(this);

    static final class AppHibernationServiceStub extends IAppHibernationService.Stub {
        final AppHibernationService mService;

        AppHibernationServiceStub(AppHibernationService service) {
            mService = service;
        }

        @Override
        public boolean isHibernating(String packageName, int userId) {
            return mService.isHibernating(packageName, userId);
        }

        @Override
        public void setHibernating(String packageName, int userId, boolean isHibernating) {
            mService.setHibernating(packageName, userId, isHibernating);
        }

        @Override
        public void onShellCommand(@Nullable FileDescriptor in, @Nullable FileDescriptor out,
                @Nullable FileDescriptor err, @NonNull String[] args,
                @Nullable ShellCallback callback, @NonNull ResultReceiver resultReceiver) {
            new AppHibernationShellCommand(mService).exec(this, in, out, err, args, callback,
                    resultReceiver);
        }
    }

    // Broadcast receiver for user and package add/removal events
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
            if (userId == UserHandle.USER_NULL) {
                return;
            }

            final String action = intent.getAction();
            if (ACTION_USER_ADDED.equals(action)) {
                onUserAdded(userId);
            }
            if (ACTION_USER_REMOVED.equals(action)) {
                onUserRemoved(userId);
            }
            if (ACTION_PACKAGE_ADDED.equals(action) || ACTION_PACKAGE_REMOVED.equals(action)) {
                final String packageName = intent.getData().getSchemeSpecificPart();
                if (intent.getBooleanExtra(EXTRA_REPLACING, false)) {
                    // Package removal/add is part of an update, so no need to modify package state.
                    return;
                }

                if (ACTION_PACKAGE_ADDED.equals(action)) {
                    onPackageAdded(packageName, userId);
                } else if (ACTION_PACKAGE_REMOVED.equals(action)) {
                    onPackageRemoved(packageName, userId);
                }
            }
        }
    };

    /**
     * Whether app hibernation is enabled on this device.
     *
     * @return true if enabled, false otherwise
     */
    public static boolean isAppHibernationEnabled() {
        return DeviceConfig.getBoolean(
                NAMESPACE_APP_HIBERNATION,
                AppHibernationConstants.KEY_APP_HIBERNATION_ENABLED,
                false /* defaultValue */);
    }

    /**
     * Data class that contains hibernation state info of a package for a user.
     */
    private static final class UserPackageState {
        public boolean hibernated;
        // TODO: Track whether hibernation is exempted by the user
    }
}
