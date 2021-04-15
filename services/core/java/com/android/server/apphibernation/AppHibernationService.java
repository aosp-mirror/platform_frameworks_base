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
import static android.content.Intent.EXTRA_REMOVED_FOR_ALL_USERS;
import static android.content.Intent.EXTRA_REPLACING;
import static android.content.pm.PackageManager.MATCH_ANY_USER;
import static android.provider.DeviceConfig.NAMESPACE_APP_HIBERNATION;

import static com.android.server.apphibernation.AppHibernationConstants.KEY_APP_HIBERNATION_ENABLED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.IActivityManager;
import android.apphibernation.IAppHibernationService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Environment;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.Properties;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * System service that manages app hibernation state, a state apps can enter that means they are
 * not being actively used and can be optimized for storage. The actual policy for determining
 * if an app should hibernate is managed by PermissionController code.
 */
public final class AppHibernationService extends SystemService {
    private static final String TAG = "AppHibernationService";
    private static final int PACKAGE_MATCH_FLAGS =
            PackageManager.MATCH_DIRECT_BOOT_AWARE
                    | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                    | PackageManager.MATCH_UNINSTALLED_PACKAGES
                    | PackageManager.MATCH_DISABLED_COMPONENTS;

    /**
     * Lock for accessing any in-memory hibernation state
     */
    private final Object mLock = new Object();
    private final Context mContext;
    private final IPackageManager mIPackageManager;
    private final IActivityManager mIActivityManager;
    private final UserManager mUserManager;
    @GuardedBy("mLock")
    private final SparseArray<Map<String, UserLevelState>> mUserStates = new SparseArray<>();
    private final SparseArray<HibernationStateDiskStore<UserLevelState>> mUserDiskStores =
            new SparseArray<>();
    @GuardedBy("mLock")
    private final Map<String, GlobalLevelState> mGlobalHibernationStates = new ArrayMap<>();
    private final HibernationStateDiskStore<GlobalLevelState> mGlobalLevelHibernationDiskStore;
    private final Injector mInjector;
    private final Executor mBackgroundExecutor;

    @VisibleForTesting
    boolean mIsServiceEnabled;

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
        this(new InjectorImpl(context));
    }

    @VisibleForTesting
    AppHibernationService(@NonNull Injector injector) {
        super(injector.getContext());
        mContext = injector.getContext();
        mIPackageManager = injector.getPackageManager();
        mIActivityManager = injector.getActivityManager();
        mUserManager = injector.getUserManager();
        mGlobalLevelHibernationDiskStore = injector.getGlobalLevelDiskStore();
        mBackgroundExecutor = injector.getBackgroundExecutor();
        mInjector = injector;

        final Context userAllContext = mContext.createContextAsUser(UserHandle.ALL, 0 /* flags */);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_PACKAGE_ADDED);
        intentFilter.addAction(ACTION_PACKAGE_REMOVED);
        intentFilter.addDataScheme("package");
        userAllContext.registerReceiver(mBroadcastReceiver, intentFilter);

        LocalServices.addService(AppHibernationManagerInternal.class, mLocalService);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.APP_HIBERNATION_SERVICE, mServiceStub);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_BOOT_COMPLETED) {
            mBackgroundExecutor.execute(() -> {
                List<GlobalLevelState> states =
                        mGlobalLevelHibernationDiskStore.readHibernationStates();
                synchronized (mLock) {
                    initializeGlobalHibernationStates(states);
                }
            });
        }
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            mIsServiceEnabled = isAppHibernationEnabled();
            DeviceConfig.addOnPropertiesChangedListener(
                    NAMESPACE_APP_HIBERNATION,
                    ActivityThread.currentApplication().getMainExecutor(),
                    this::onDeviceConfigChanged);
        }
    }

    /**
     * Whether a package is hibernating for a given user.
     *
     * @param packageName the package to check
     * @param userId the user to check
     * @return true if package is hibernating for the user
     */
    boolean isHibernatingForUser(String packageName, int userId) {
        String methodName = "isHibernatingForUser";
        if (!checkHibernationEnabled(methodName)) {
            return false;
        }
        getContext().enforceCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_APP_HIBERNATION,
                "Caller does not have MANAGE_APP_HIBERNATION permission.");
        userId = handleIncomingUser(userId, methodName);
        if (!checkUserStatesExist(userId, methodName)) {
            return false;
        }
        synchronized (mLock) {
            final Map<String, UserLevelState> packageStates = mUserStates.get(userId);
            final UserLevelState pkgState = packageStates.get(packageName);
            if (pkgState == null) {
                throw new IllegalArgumentException(
                        String.format("Package %s is not installed for user %s",
                                packageName, userId));
            }
            return pkgState.hibernated;
        }
    }

    /**
     * Whether a package is hibernated globally. This only occurs when a package is hibernating for
     * all users and allows us to make optimizations at the package or APK level.
     *
     * @param packageName package to check
     */
    boolean isHibernatingGlobally(String packageName) {
        if (!checkHibernationEnabled("isHibernatingGlobally")) {
            return false;
        }
        getContext().enforceCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_APP_HIBERNATION,
                "Caller does not have MANAGE_APP_HIBERNATION permission.");
        synchronized (mLock) {
            GlobalLevelState state = mGlobalHibernationStates.get(packageName);
            if (state == null) {
                throw new IllegalArgumentException(
                        String.format("Package %s is not installed", packageName));
            }
            return state.hibernated;
        }
    }

    /**
     * Set whether the package is hibernating for the given user.
     *
     * @param packageName package to modify state
     * @param userId user
     * @param isHibernating new hibernation state
     */
    void setHibernatingForUser(String packageName, int userId, boolean isHibernating) {
        String methodName = "setHibernatingForUser";
        if (!checkHibernationEnabled(methodName)) {
            return;
        }
        getContext().enforceCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_APP_HIBERNATION,
                "Caller does not have MANAGE_APP_HIBERNATION permission.");
        userId = handleIncomingUser(userId, methodName);
        if (!checkUserStatesExist(userId, methodName)) {
            return;
        }
        synchronized (mLock) {
            final Map<String, UserLevelState> packageStates = mUserStates.get(userId);
            final UserLevelState pkgState = packageStates.get(packageName);
            if (pkgState == null) {
                throw new IllegalArgumentException(
                        String.format("Package %s is not installed for user %s",
                                packageName, userId));
            }

            if (pkgState.hibernated == isHibernating) {
                return;
            }

            if (isHibernating) {
                hibernatePackageForUser(packageName, userId, pkgState);
            } else {
                unhibernatePackageForUser(packageName, userId, pkgState);
            }
            List<UserLevelState> states = new ArrayList<>(mUserStates.get(userId).values());
            mUserDiskStores.get(userId).scheduleWriteHibernationStates(states);
        }
    }

    /**
     * Set whether the package should be hibernated globally at a package level, allowing the
     * the system to make optimizations at the package or APK level.
     *
     * @param packageName package to hibernate globally
     * @param isHibernating new hibernation state
     */
    void setHibernatingGlobally(String packageName, boolean isHibernating) {
        if (!checkHibernationEnabled("setHibernatingGlobally")) {
            return;
        }
        getContext().enforceCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_APP_HIBERNATION,
                "Caller does not have MANAGE_APP_HIBERNATION permission.");
        synchronized (mLock) {
            GlobalLevelState state = mGlobalHibernationStates.get(packageName);
            if (state == null) {
                throw new IllegalArgumentException(
                        String.format("Package %s is not installed for any user", packageName));
            }
            if (state.hibernated != isHibernating) {
                if (isHibernating) {
                    hibernatePackageGlobally(packageName, state);
                } else {
                    unhibernatePackageGlobally(packageName, state);
                }
                List<GlobalLevelState> states = new ArrayList<>(mGlobalHibernationStates.values());
                mGlobalLevelHibernationDiskStore.scheduleWriteHibernationStates(states);
            }
        }
    }

    /**
     * Get the hibernating packages for the given user. This is equivalent to the list of
     * packages for the user that return true for {@link #isHibernatingForUser}.
     */
    @NonNull List<String> getHibernatingPackagesForUser(int userId) {
        ArrayList<String> hibernatingPackages = new ArrayList<>();
        String methodName = "getHibernatingPackagesForUser";
        if (!checkHibernationEnabled(methodName)) {
            return hibernatingPackages;
        }
        getContext().enforceCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_APP_HIBERNATION,
                "Caller does not have MANAGE_APP_HIBERNATION permission.");
        userId = handleIncomingUser(userId, methodName);
        if (!checkUserStatesExist(userId, methodName)) {
            return hibernatingPackages;
        }
        synchronized (mLock) {
            Map<String, UserLevelState> userStates = mUserStates.get(userId);
            for (UserLevelState state : userStates.values()) {
                if (state.hibernated) {
                    hibernatingPackages.add(state.packageName);
                }
            }
            return hibernatingPackages;
        }
    }

    /**
     * Put an app into hibernation for a given user, allowing user-level optimizations to occur.
     *
     * @param pkgState package hibernation state
     */
    @GuardedBy("mLock")
    private void hibernatePackageForUser(@NonNull String packageName, int userId,
            @NonNull UserLevelState pkgState) {
        Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "hibernatePackage");
        final long caller = Binder.clearCallingIdentity();
        try {
            mIActivityManager.forceStopPackage(packageName, userId);
            mIPackageManager.deleteApplicationCacheFilesAsUser(packageName, userId,
                    null /* observer */);
            pkgState.hibernated = true;
        } catch (RemoteException e) {
            throw new IllegalStateException(
                    "Failed to hibernate due to manager not being available", e);
        } finally {
            Binder.restoreCallingIdentity(caller);
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
        }
    }

    /**
     * Remove a package from hibernation for a given user.
     *
     * @param pkgState package hibernation state
     */
    @GuardedBy("mLock")
    private void unhibernatePackageForUser(@NonNull String packageName, int userId,
            UserLevelState pkgState) {
        Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "unhibernatePackage");
        final long caller = Binder.clearCallingIdentity();
        try {
            mIPackageManager.setPackageStoppedState(packageName, false, userId);
            pkgState.hibernated = false;
        } catch (RemoteException e) {
            throw new IllegalStateException(
                    "Failed to unhibernate due to manager not being available", e);
        } finally {
            Binder.restoreCallingIdentity(caller);
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
        }
    }

    /**
     * Put a package into global hibernation, optimizing its storage at a package / APK level.
     */
    @GuardedBy("mLock")
    private void hibernatePackageGlobally(@NonNull String packageName, GlobalLevelState state) {
        Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "hibernatePackageGlobally");
        // TODO(175830194): Delete vdex/odex when DexManager API is built out
        state.hibernated = true;
        Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
    }

    /**
     * Unhibernate a package from global hibernation.
     */
    @GuardedBy("mLock")
    private void unhibernatePackageGlobally(@NonNull String packageName, GlobalLevelState state) {
        Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "unhibernatePackageGlobally");
        state.hibernated = false;
        Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
    }

    /**
     * Initializes in-memory store of user-level hibernation states for the given user
     *
     * @param userId user id to add installed packages for
     * @param diskStates states pulled from disk, if available
     */
    @GuardedBy("mLock")
    private void initializeUserHibernationStates(int userId,
            @Nullable List<UserLevelState> diskStates) {
        List<PackageInfo> packages;
        try {
            packages = mIPackageManager.getInstalledPackages(PACKAGE_MATCH_FLAGS, userId).getList();
        } catch (RemoteException e) {
            throw new IllegalStateException("Package manager not available", e);
        }

        Map<String, UserLevelState> userLevelStates = new ArrayMap<>();

        for (int i = 0, size = packages.size(); i < size; i++) {
            String packageName = packages.get(i).packageName;
            UserLevelState state = new UserLevelState();
            state.packageName = packageName;
            userLevelStates.put(packageName, state);
        }

        if (diskStates != null) {
            Set<String> installedPackages = new ArraySet<>();
            for (int i = 0, size = packages.size(); i < size; i++) {
                installedPackages.add(packages.get(i).packageName);
            }
            for (int i = 0, size = diskStates.size(); i < size; i++) {
                String packageName = diskStates.get(i).packageName;
                if (!installedPackages.contains(packageName)) {
                    Slog.w(TAG, String.format(
                            "No hibernation state associated with package %s user %d. Maybe"
                                    + "the package was uninstalled? ", packageName, userId));
                    continue;
                }
                userLevelStates.put(packageName, diskStates.get(i));
            }
        }
        mUserStates.put(userId, userLevelStates);
    }

    /**
     * Initialize in-memory store of global level hibernation states.
     *
     * @param diskStates global level hibernation states pulled from disk, if available
     */
    @GuardedBy("mLock")
    private void initializeGlobalHibernationStates(@Nullable List<GlobalLevelState> diskStates) {
        List<PackageInfo> packages;
        try {
            packages = mIPackageManager.getInstalledPackages(
                    PACKAGE_MATCH_FLAGS | MATCH_ANY_USER, 0 /* userId */).getList();
        } catch (RemoteException e) {
            throw new IllegalStateException("Package manager not available", e);
        }

        for (int i = 0, size = packages.size(); i < size; i++) {
            String packageName = packages.get(i).packageName;
            GlobalLevelState state = new GlobalLevelState();
            state.packageName = packageName;
            mGlobalHibernationStates.put(packageName, state);
        }
        if (diskStates != null) {
            Set<String> installedPackages = new ArraySet<>();
            for (int i = 0, size = packages.size(); i < size; i++) {
                installedPackages.add(packages.get(i).packageName);
            }
            for (int i = 0, size = diskStates.size(); i < size; i++) {
                GlobalLevelState state = diskStates.get(i);
                if (!installedPackages.contains(state.packageName)) {
                    Slog.w(TAG, String.format(
                            "No hibernation state associated with package %s. Maybe the "
                                    + "package was uninstalled? ", state.packageName));
                    continue;
                }
                mGlobalHibernationStates.put(state.packageName, state);
            }
        }
    }

    @Override
    public void onUserUnlocking(@NonNull TargetUser user) {
        int userId = user.getUserIdentifier();
        HibernationStateDiskStore<UserLevelState> diskStore =
                mInjector.getUserLevelDiskStore(userId);
        mUserDiskStores.put(userId, diskStore);
        mBackgroundExecutor.execute(() -> {
            List<UserLevelState> storedStates = diskStore.readHibernationStates();
            synchronized (mLock) {
                // Ensure user hasn't stopped in the time to execute.
                if (mUserManager.isUserUnlockingOrUnlocked(userId)) {
                    initializeUserHibernationStates(userId, storedStates);
                }
            }
        });
    }

    @Override
    public void onUserStopping(@NonNull TargetUser user) {
        int userId = user.getUserIdentifier();
        // TODO: Flush any scheduled writes to disk immediately on user stopping / power off.
        synchronized (mLock) {
            mUserDiskStores.remove(userId);
            mUserStates.remove(userId);
        }
    }

    private void onPackageAdded(@NonNull String packageName, int userId) {
        synchronized (mLock) {
            if (!mUserStates.contains(userId)) {
                return;
            }
            UserLevelState userState = new UserLevelState();
            userState.packageName = packageName;
            mUserStates.get(userId).put(packageName, userState);
            if (!mGlobalHibernationStates.containsKey(packageName)) {
                GlobalLevelState globalState = new GlobalLevelState();
                globalState.packageName = packageName;
                mGlobalHibernationStates.put(packageName, globalState);
            }
        }
    }

    private void onPackageRemoved(@NonNull String packageName, int userId) {
        synchronized (mLock) {
            if (!mUserStates.contains(userId)) {
                return;
            }
            mUserStates.get(userId).remove(packageName);
        }
    }

    private void onPackageRemovedForAllUsers(@NonNull String packageName) {
        synchronized (mLock) {
            mGlobalHibernationStates.remove(packageName);
        }
    }

    private void onDeviceConfigChanged(Properties properties) {
        for (String key : properties.getKeyset()) {
            if (TextUtils.equals(KEY_APP_HIBERNATION_ENABLED, key)) {
                mIsServiceEnabled = isAppHibernationEnabled();
                break;
            }
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

    private boolean checkUserStatesExist(int userId, String methodName) {
        if (!mUserManager.isUserUnlockingOrUnlocked(userId)) {
            Slog.e(TAG, String.format(
                    "Attempt to call %s on stopped or nonexistent user %d", methodName, userId));
            return false;
        }
        if (!mUserStates.contains(userId)) {
            Slog.w(TAG, String.format(
                    "Attempt to call %s before states have been read from disk", methodName));
            return false;
        }
        return true;
    }

    private boolean checkHibernationEnabled(String methodName) {
        if (!mIsServiceEnabled) {
            Slog.w(TAG, String.format("Attempted to call %s on unsupported device.", methodName));
        }
        return mIsServiceEnabled;
    }

    private void dump(PrintWriter pw) {
        // Check usage stats permission since hibernation indirectly informs usage.
        if (!DumpUtils.checkDumpAndUsageStatsPermission(getContext(), TAG, pw)) return;

        IndentingPrintWriter idpw = new IndentingPrintWriter(pw, "  ");

        synchronized (mLock) {
            final int userCount = mUserStates.size();
            for (int i = 0; i < userCount; i++) {
                final int userId = mUserStates.keyAt(i);
                idpw.print("User Level Hibernation States, ");
                idpw.printPair("user", userId);
                idpw.println();
                Map<String, UserLevelState> stateMap = mUserStates.get(i);
                idpw.increaseIndent();
                for (UserLevelState state : stateMap.values()) {
                    idpw.print(state);
                    idpw.println();
                }
                idpw.decreaseIndent();
            }
            idpw.println();
            idpw.print("Global Level Hibernation States");
            idpw.println();
            for (GlobalLevelState state : mGlobalHibernationStates.values()) {
                idpw.print(state);
                idpw.println();
            }
        }
    }

    private final AppHibernationManagerInternal mLocalService = new LocalService(this);

    private static final class LocalService extends AppHibernationManagerInternal {
        private final AppHibernationService mService;

        LocalService(AppHibernationService service) {
            mService = service;
        }

        @Override
        public boolean isHibernatingForUser(String packageName, int userId) {
            return mService.isHibernatingForUser(packageName, userId);
        }

        @Override
        public void setHibernatingForUser(String packageName, int userId, boolean isHibernating) {
            mService.setHibernatingForUser(packageName, userId, isHibernating);
        }

        @Override
        public void setHibernatingGlobally(String packageName, boolean isHibernating) {
            mService.setHibernatingGlobally(packageName, isHibernating);
        }

        @Override
        public boolean isHibernatingGlobally(String packageName) {
            return mService.isHibernatingGlobally(packageName);
        }
    }

    private final AppHibernationServiceStub mServiceStub = new AppHibernationServiceStub(this);

    static final class AppHibernationServiceStub extends IAppHibernationService.Stub {
        final AppHibernationService mService;

        AppHibernationServiceStub(AppHibernationService service) {
            mService = service;
        }

        @Override
        public boolean isHibernatingForUser(String packageName, int userId) {
            return mService.isHibernatingForUser(packageName, userId);
        }

        @Override
        public void setHibernatingForUser(String packageName, int userId, boolean isHibernating) {
            mService.setHibernatingForUser(packageName, userId, isHibernating);
        }

        @Override
        public void setHibernatingGlobally(String packageName, boolean isHibernating) {
            mService.setHibernatingGlobally(packageName, isHibernating);
        }

        @Override
        public boolean isHibernatingGlobally(String packageName) {
            return mService.isHibernatingGlobally(packageName);
        }

        @Override
        public List<String> getHibernatingPackagesForUser(int userId) {
            return mService.getHibernatingPackagesForUser(userId);
        }

        @Override
        public void onShellCommand(@Nullable FileDescriptor in, @Nullable FileDescriptor out,
                @Nullable FileDescriptor err, @NonNull String[] args,
                @Nullable ShellCallback callback, @NonNull ResultReceiver resultReceiver) {
            new AppHibernationShellCommand(mService).exec(this, in, out, err, args, callback,
                    resultReceiver);
        }

        @Override
        protected void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter fout,
                @Nullable String[] args) {
            mService.dump(fout);
        }
    }

    // Broadcast receiver for package add/removal events
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
            if (userId == UserHandle.USER_NULL) {
                return;
            }

            final String action = intent.getAction();
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
                    if (intent.getBooleanExtra(EXTRA_REMOVED_FOR_ALL_USERS, false)) {
                        onPackageRemovedForAllUsers(packageName);
                    }
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
                KEY_APP_HIBERNATION_ENABLED,
                false /* defaultValue */);
    }

    /**
     * Dependency injector for {@link #AppHibernationService)}.
     */
    interface Injector {
        Context getContext();

        IPackageManager getPackageManager();

        IActivityManager getActivityManager();

        UserManager getUserManager();

        Executor getBackgroundExecutor();

        HibernationStateDiskStore<GlobalLevelState> getGlobalLevelDiskStore();

        HibernationStateDiskStore<UserLevelState> getUserLevelDiskStore(int userId);
    }

    private static final class InjectorImpl implements Injector {
        private static final String HIBERNATION_DIR_NAME = "hibernation";
        private final Context mContext;
        private final ScheduledExecutorService mScheduledExecutorService;
        private final UserLevelHibernationProto mUserLevelHibernationProto;

        InjectorImpl(Context context) {
            mContext = context;
            mScheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
            mUserLevelHibernationProto = new UserLevelHibernationProto();
        }

        @Override
        public Context getContext() {
            return mContext;
        }

        @Override
        public IPackageManager getPackageManager() {
            return IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
        }

        @Override
        public IActivityManager getActivityManager() {
            return ActivityManager.getService();
        }

        @Override
        public UserManager getUserManager() {
            return mContext.getSystemService(UserManager.class);
        }

        @Override
        public Executor getBackgroundExecutor() {
            return mScheduledExecutorService;
        }

        @Override
        public HibernationStateDiskStore<GlobalLevelState> getGlobalLevelDiskStore() {
            File dir = new File(Environment.getDataSystemDirectory(), HIBERNATION_DIR_NAME);
            return new HibernationStateDiskStore<>(
                    dir, new GlobalLevelHibernationProto(), mScheduledExecutorService);
        }

        @Override
        public HibernationStateDiskStore<UserLevelState> getUserLevelDiskStore(int userId) {
            File dir = new File(Environment.getDataSystemCeDirectory(userId), HIBERNATION_DIR_NAME);
            return new HibernationStateDiskStore<>(
                    dir, mUserLevelHibernationProto, mScheduledExecutorService);
        }
    }
}
