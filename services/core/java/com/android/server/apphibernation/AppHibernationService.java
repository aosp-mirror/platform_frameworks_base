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

import static android.app.AppOpsManager.OP_NONE;
import static android.content.Intent.ACTION_PACKAGE_ADDED;
import static android.content.Intent.ACTION_PACKAGE_REMOVED;
import static android.content.Intent.EXTRA_REMOVED_FOR_ALL_USERS;
import static android.content.Intent.EXTRA_REPLACING;
import static android.content.pm.PackageManager.MATCH_ANY_USER;
import static android.provider.DeviceConfig.NAMESPACE_APP_HIBERNATION;

import static com.android.server.apphibernation.AppHibernationConstants.KEY_APP_HIBERNATION_ENABLED;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.IActivityManager;
import android.app.StatsManager;
import android.app.StatsManager.StatsPullAtomCallback;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManagerInternal;
import android.app.usage.UsageStatsManagerInternal.UsageEventListener;
import android.apphibernation.HibernationStats;
import android.apphibernation.IAppHibernationService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.UserInfo;
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
import android.util.StatsEvent;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
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
    private static final long PACKAGE_MATCH_FLAGS =
            PackageManager.MATCH_DIRECT_BOOT_AWARE
                    | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                    | PackageManager.MATCH_UNINSTALLED_PACKAGES
                    | PackageManager.MATCH_DISABLED_COMPONENTS
                    | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
                    | PackageManager.MATCH_HIDDEN_UNTIL_INSTALLED_COMPONENTS;

    /**
     * Lock for accessing any in-memory hibernation state
     */
    private final Object mLock = new Object();
    private final Context mContext;
    private final IPackageManager mIPackageManager;
    private final PackageManagerInternal mPackageManagerInternal;
    private final IActivityManager mIActivityManager;
    private final UserManager mUserManager;
    private final StorageStatsManager mStorageStatsManager;

    @GuardedBy("mLock")
    private final SparseArray<Map<String, UserLevelState>> mUserStates = new SparseArray<>();
    private final SparseArray<HibernationStateDiskStore<UserLevelState>> mUserDiskStores =
            new SparseArray<>();
    @GuardedBy("mLock")
    private final Map<String, GlobalLevelState> mGlobalHibernationStates = new ArrayMap<>();
    private final HibernationStateDiskStore<GlobalLevelState> mGlobalLevelHibernationDiskStore;
    private final Injector mInjector;
    private final Executor mBackgroundExecutor;
    private final boolean mOatArtifactDeletionEnabled;

    @VisibleForTesting
    public static boolean sIsServiceEnabled;

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
        mPackageManagerInternal = injector.getPackageManagerInternal();
        mIActivityManager = injector.getActivityManager();
        mUserManager = injector.getUserManager();
        mStorageStatsManager = injector.getStorageStatsManager();
        mGlobalLevelHibernationDiskStore = injector.getGlobalLevelDiskStore();
        mBackgroundExecutor = injector.getBackgroundExecutor();
        mOatArtifactDeletionEnabled = injector.isOatArtifactDeletionEnabled();
        mInjector = injector;

        final Context userAllContext = mContext.createContextAsUser(UserHandle.ALL, 0 /* flags */);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_PACKAGE_ADDED);
        intentFilter.addAction(ACTION_PACKAGE_REMOVED);
        intentFilter.addDataScheme("package");
        userAllContext.registerReceiver(mBroadcastReceiver, intentFilter);
        LocalServices.addService(AppHibernationManagerInternal.class, mLocalService);
        mInjector.getUsageStatsManagerInternal().registerListener(mUsageEventListener);
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
            sIsServiceEnabled = isDeviceConfigAppHibernationEnabled();
            DeviceConfig.addOnPropertiesChangedListener(
                    NAMESPACE_APP_HIBERNATION,
                    ActivityThread.currentApplication().getMainExecutor(),
                    this::onDeviceConfigChanged);
            final StatsManager statsManager = getContext().getSystemService(StatsManager.class);
            final StatsPullAtomCallbackImpl pullAtomCallback = new StatsPullAtomCallbackImpl();
            statsManager.setPullAtomCallback(
                    FrameworkStatsLog.USER_LEVEL_HIBERNATED_APPS,
                    /* metadata */ null, // use default PullAtomMetadata values
                    mBackgroundExecutor,
                    pullAtomCallback);
            statsManager.setPullAtomCallback(
                    FrameworkStatsLog.GLOBAL_HIBERNATED_APPS,
                    /* metadata */ null, // use default PullAtomMetadata values
                    mBackgroundExecutor,
                    pullAtomCallback);
        }
    }

    /**
     * Whether global hibernation should delete ART ahead-of-time compilation artifacts and prevent
     * package manager from re-optimizing the APK.
     */
    private boolean isOatArtifactDeletionEnabled() {
        getContext().enforceCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_APP_HIBERNATION,
                "Caller does not have MANAGE_APP_HIBERNATION permission.");
        return mOatArtifactDeletionEnabled;
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
        if (!sIsServiceEnabled) {
            return false;
        }
        getContext().enforceCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_APP_HIBERNATION,
                "Caller did not have permission while calling " + methodName);
        userId = handleIncomingUser(userId, methodName);
        synchronized (mLock) {
            // Don't log as this method can be called before user states exist as part of the
            // force-stop check.
            if (!checkUserStatesExist(userId, methodName, /* shouldLog= */ false)) {
                return false;
            }
            final Map<String, UserLevelState> packageStates = mUserStates.get(userId);
            final UserLevelState pkgState = packageStates.get(packageName);
            if (pkgState == null
                    || !mPackageManagerInternal.canQueryPackage(
                            Binder.getCallingUid(), packageName)) {
                return false;
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
        if (!sIsServiceEnabled) {
            return false;
        }
        getContext().enforceCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_APP_HIBERNATION,
                "Caller does not have MANAGE_APP_HIBERNATION permission.");
        synchronized (mLock) {
            GlobalLevelState state = mGlobalHibernationStates.get(packageName);
            if (state == null
                    || !mPackageManagerInternal.canQueryPackage(
                            Binder.getCallingUid(), packageName)) {
                // This API can be legitimately called before installation finishes as part of
                // dex optimization, so we just return false here.
                return false;
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
        if (!sIsServiceEnabled) {
            return;
        }
        getContext().enforceCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_APP_HIBERNATION,
                "Caller does not have MANAGE_APP_HIBERNATION permission.");
        final int realUserId = handleIncomingUser(userId, methodName);
        synchronized (mLock) {
            if (!checkUserStatesExist(realUserId, methodName, /* shouldLog= */ true)) {
                return;
            }
            final Map<String, UserLevelState> packageStates = mUserStates.get(realUserId);
            final UserLevelState pkgState = packageStates.get(packageName);
            if (pkgState == null
                    || !mPackageManagerInternal.canQueryPackage(
                            Binder.getCallingUid(), packageName)) {
                Slog.e(TAG, TextUtils.formatSimple("Package %s is not installed for user %s",
                        packageName, realUserId));
                return;
            }

            if (pkgState.hibernated == isHibernating) {
                return;
            }

            pkgState.hibernated = isHibernating;
            if (isHibernating) {
                mBackgroundExecutor.execute(
                        () -> hibernatePackageForUser(packageName, realUserId, pkgState));
            } else {
                mBackgroundExecutor.execute(
                        () -> unhibernatePackageForUser(packageName, realUserId));
                pkgState.lastUnhibernatedMs = System.currentTimeMillis();
            }

            final UserLevelState stateSnapshot = new UserLevelState(pkgState);
            final int userIdSnapshot = realUserId;
            mBackgroundExecutor.execute(() -> {
                FrameworkStatsLog.write(
                        FrameworkStatsLog.USER_LEVEL_HIBERNATION_STATE_CHANGED,
                        stateSnapshot.packageName,
                        userIdSnapshot,
                        stateSnapshot.hibernated);
            });
            List<UserLevelState> states = new ArrayList<>(mUserStates.get(realUserId).values());
            mUserDiskStores.get(realUserId).scheduleWriteHibernationStates(states);
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
        if (!sIsServiceEnabled) {
            return;
        }
        getContext().enforceCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_APP_HIBERNATION,
                "Caller does not have MANAGE_APP_HIBERNATION permission.");
        synchronized (mLock) {
            GlobalLevelState state = mGlobalHibernationStates.get(packageName);
            if (state == null
                    || !mPackageManagerInternal.canQueryPackage(
                            Binder.getCallingUid(), packageName)) {
                Slog.e(TAG, TextUtils.formatSimple(
                        "Package %s is not installed for any user", packageName));
                return;
            }
            if (state.hibernated != isHibernating) {
                state.hibernated = isHibernating;
                if (isHibernating) {
                    mBackgroundExecutor.execute(() -> hibernatePackageGlobally(packageName, state));
                } else {
                    state.savedByte = 0;
                    state.lastUnhibernatedMs = System.currentTimeMillis();
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
        if (!sIsServiceEnabled) {
            return hibernatingPackages;
        }
        getContext().enforceCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_APP_HIBERNATION,
                "Caller does not have MANAGE_APP_HIBERNATION permission.");
        userId = handleIncomingUser(userId, methodName);
        synchronized (mLock) {
            if (!checkUserStatesExist(userId, methodName, /* shouldLog= */ true)) {
                return hibernatingPackages;
            }
            Map<String, UserLevelState> userStates = mUserStates.get(userId);
            for (UserLevelState state : userStates.values()) {
                String packageName = state.packageName;
                if (!mPackageManagerInternal.canQueryPackage(
                        Binder.getCallingUid(), packageName)) {
                    // Package is not visible to caller
                    continue;
                }
                if (state.hibernated) {
                    hibernatingPackages.add(state.packageName);
                }
            }
            return hibernatingPackages;
        }
    }

    /**
     * Return the stats from app hibernation for each package provided.
     *
     * @param packageNames the set of packages to return stats for. Returns all if null
     * @return map from package to stats for that package
     */
    public Map<String, HibernationStats> getHibernationStatsForUser(
            @Nullable Set<String> packageNames, int userId) {
        Map<String, HibernationStats> statsMap = new ArrayMap<>();
        String methodName = "getHibernationStatsForUser";
        if (!sIsServiceEnabled) {
            return statsMap;
        }
        getContext().enforceCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_APP_HIBERNATION,
                "Caller does not have MANAGE_APP_HIBERNATION permission.");
        userId = handleIncomingUser(userId, methodName);
        synchronized (mLock) {
            if (!checkUserStatesExist(userId, methodName, /* shouldLog= */ true)) {
                return statsMap;
            }
            final Map<String, UserLevelState> userPackageStates = mUserStates.get(userId);
            Set<String> pkgs = packageNames != null ? packageNames : userPackageStates.keySet();
            for (String pkgName : pkgs) {
                if (!mPackageManagerInternal.canQueryPackage(Binder.getCallingUid(), pkgName)) {
                    // Package not visible to caller
                    continue;
                }
                if (!mGlobalHibernationStates.containsKey(pkgName)
                        || !userPackageStates.containsKey(pkgName)) {
                    Slog.w(TAG, TextUtils.formatSimple(
                            "No hibernation state associated with package %s user %d. Maybe"
                                    + "the package was uninstalled? ", pkgName, userId));
                    continue;
                }
                long diskBytesSaved = mGlobalHibernationStates.get(pkgName).savedByte
                        + userPackageStates.get(pkgName).savedByte;
                HibernationStats stats = new HibernationStats(diskBytesSaved);
                statsMap.put(pkgName, stats);
            }
        }
        return statsMap;
    }

    /**
     * Put an app into hibernation for a given user, allowing user-level optimizations to occur. Do
     * not hold {@link #mLock} while calling this to avoid deadlock scenarios.
     */
    private void hibernatePackageForUser(@NonNull String packageName, int userId,
            UserLevelState state) {
        Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "hibernatePackage");
        final long caller = Binder.clearCallingIdentity();
        try {
            ApplicationInfo info = mIPackageManager.getApplicationInfo(
                    packageName, PACKAGE_MATCH_FLAGS, userId);
            StorageStats stats = mStorageStatsManager.queryStatsForPackage(
                    info.storageUuid, packageName, new UserHandle(userId));
            if (android.app.Flags.appRestrictionsApi()) {
                noteHibernationChange(packageName, info.uid, true);
            }
            mIActivityManager.forceStopPackage(packageName, userId);
            mIPackageManager.deleteApplicationCacheFilesAsUser(packageName, userId,
                    null /* observer */);
            synchronized (mLock) {
                state.savedByte = stats.getCacheBytes();
            }
        } catch (RemoteException e) {
            throw new IllegalStateException(
                    "Failed to hibernate due to manager not being available", e);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "Package name not found when querying storage stats", e);
        } catch (IOException e) {
            Slog.e(TAG, "Storage device not found", e);
        } finally {
            Binder.restoreCallingIdentity(caller);
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
        }
    }

    /**
     * Remove a package from hibernation for a given user. Do not hold {@link #mLock} while calling
     * this.
     */
    private void unhibernatePackageForUser(@NonNull String packageName, int userId) {
        Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "unhibernatePackage");
        final long caller = Binder.clearCallingIdentity();
        // Deliver LOCKED_BOOT_COMPLETE AND BOOT_COMPLETE broadcast so app can re-register
        // their alarms/jobs/etc.
        try {
            if (android.app.Flags.appRestrictionsApi()) {
                ApplicationInfo info = mIPackageManager.getApplicationInfo(
                        packageName, PACKAGE_MATCH_FLAGS, userId);
                noteHibernationChange(packageName, info.uid, false);
            }
            Intent lockedBcIntent = new Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED)
                    .setPackage(packageName);
            final String[] requiredPermissions = {Manifest.permission.RECEIVE_BOOT_COMPLETED};
            mIActivityManager.broadcastIntentWithFeature(
                    null /* caller */,
                    null /* callingFeatureId */,
                    lockedBcIntent,
                    null /* resolvedType */,
                    null /* resultTo */,
                    Activity.RESULT_OK,
                    null /* resultData */,
                    null /* resultExtras */,
                    requiredPermissions,
                    null /* excludedPermissions */,
                    null /* excludedPackages */,
                    OP_NONE,
                    null /* bOptions */,
                    false /* serialized */,
                    false /* sticky */,
                    userId);

            Intent bcIntent = new Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(packageName);
            mIActivityManager.broadcastIntentWithFeature(
                    null /* caller */,
                    null /* callingFeatureId */,
                    bcIntent,
                    null /* resolvedType */,
                    null /* resultTo */,
                    Activity.RESULT_OK,
                    null /* resultData */,
                    null /* resultExtras */,
                    requiredPermissions,
                    null /* excludedPermissions */,
                    null /* excludedPackages */,
                    OP_NONE,
                    null /* bOptions */,
                    false /* serialized */,
                    false /* sticky */,
                    userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } finally {
            Binder.restoreCallingIdentity(caller);
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
        }
    }

    /**
     * Put a package into global hibernation, optimizing its storage at a package / APK level. Do
     * not hold {@link #mLock} while calling this.
     */
    private void hibernatePackageGlobally(@NonNull String packageName, GlobalLevelState state) {
        Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "hibernatePackageGlobally");
        long savedBytes = 0;
        if (mOatArtifactDeletionEnabled) {
            savedBytes = Math.max(
                    mPackageManagerInternal.deleteOatArtifactsOfPackage(packageName),
                    0);
        }
        synchronized (mLock) {
            state.savedByte = savedBytes;
        }
        Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
    }

    /** Inform ActivityManager that the app being stopped or unstopped due to hibernation */
    private void noteHibernationChange(String packageName, int uid, boolean hibernated) {
        try {
            if (hibernated) {
                // TODO: Switch to an ActivityManagerInternal API
                mIActivityManager.noteAppRestrictionEnabled(
                        packageName, uid, ActivityManager.RESTRICTION_LEVEL_FORCE_STOPPED,
                        true, ActivityManager.RESTRICTION_REASON_DORMANT, null,
                        /* TODO: fetch actual timeout - 90 days */ 90 * 24 * 60 * 60_000L);
            }
            // No need to log the unhibernate case as an unstop is logged already in ActivityMS
        } catch (RemoteException e) {
            Slog.e(TAG, "Couldn't set restriction state change");
        }
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
            Map<String, PackageInfo> installedPackages = new ArrayMap<>();
            for (int i = 0, size = packages.size(); i < size; i++) {
                installedPackages.put(packages.get(i).packageName, packages.get(i));
            }
            for (int i = 0, size = diskStates.size(); i < size; i++) {
                String packageName = diskStates.get(i).packageName;
                PackageInfo pkgInfo = installedPackages.get(packageName);
                UserLevelState currentState = diskStates.get(i);
                if (pkgInfo == null) {
                    Slog.w(TAG, TextUtils.formatSimple(
                            "No hibernation state associated with package %s user %d. Maybe"
                                    + "the package was uninstalled? ", packageName, userId));
                    continue;
                }
                if (pkgInfo.applicationInfo != null
                        && (pkgInfo.applicationInfo.flags &= ApplicationInfo.FLAG_STOPPED) == 0
                        && currentState.hibernated) {
                    // App is not stopped but is hibernated. Disk state is stale, so unhibernate
                    // the app.
                    currentState.hibernated = false;
                    currentState.lastUnhibernatedMs = System.currentTimeMillis();
                }
                userLevelStates.put(packageName, currentState);
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
                    Slog.w(TAG, TextUtils.formatSimple(
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
                    // Globally unhibernate a package if the unlocked user does not have it
                    // hibernated.
                    for (UserLevelState userState : mUserStates.get(userId).values()) {
                        String pkgName = userState.packageName;
                        GlobalLevelState globalState = mGlobalHibernationStates.get(pkgName);
                        if (globalState.hibernated && !userState.hibernated) {
                            setHibernatingGlobally(pkgName, false);
                        }
                    }
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
                sIsServiceEnabled = isDeviceConfigAppHibernationEnabled();
                Slog.d(TAG, "App hibernation changed to enabled=" + sIsServiceEnabled);
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

    /**
     * Check that user states exist.
     *
     * @param userId user to check
     * @param methodName method name that is calling. Used for logging purposes.
     * @param shouldLog whether we should log why the user state doesn't exist
     * @return true if user states exist
     */
    @GuardedBy("mLock")
    private boolean checkUserStatesExist(int userId, String methodName, boolean shouldLog) {
        if (!mUserManager.isUserUnlockingOrUnlocked(userId)) {
            if (shouldLog) {
                Slog.w(TAG, TextUtils.formatSimple(
                        "Attempt to call %s on stopped or nonexistent user %d",
                        methodName, userId));
            }
            return false;
        }
        if (!mUserStates.contains(userId)) {
            if (shouldLog) {
                Slog.w(TAG, TextUtils.formatSimple(
                        "Attempt to call %s before states have been read from disk", methodName));
            }
            return false;
        }
        return true;
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
                Map<String, UserLevelState> stateMap = mUserStates.get(userId);
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

        @Override
        public boolean isOatArtifactDeletionEnabled() {
            return mService.isOatArtifactDeletionEnabled();
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
        public Map<String, HibernationStats> getHibernationStatsForUser(
                @Nullable List<String> packageNames, int userId) {
            Set<String> pkgsSet = packageNames != null ? new ArraySet<>(packageNames) : null;
            return mService.getHibernationStatsForUser(pkgsSet, userId);
        }

        @Override
        public boolean isOatArtifactDeletionEnabled() {
            return mService.isOatArtifactDeletionEnabled();
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

    private final UsageEventListener mUsageEventListener = (userId, event) -> {
        if (!isAppHibernationEnabled()) {
            return;
        }
        final int eventType = event.mEventType;
        if (eventType == UsageEvents.Event.USER_INTERACTION
                || eventType == UsageEvents.Event.ACTIVITY_RESUMED
                || eventType == UsageEvents.Event.APP_COMPONENT_USED) {
            final String pkgName = event.mPackage;
            setHibernatingForUser(pkgName, userId, false);
            setHibernatingGlobally(pkgName, false);
        }
    };

    /**
     * Whether app hibernation is enabled on this device.
     *
     * @return true if enabled, false otherwise
     */
    public static boolean isAppHibernationEnabled() {
        return sIsServiceEnabled;
    }

    private static boolean isDeviceConfigAppHibernationEnabled() {
        return DeviceConfig.getBoolean(
                NAMESPACE_APP_HIBERNATION,
                KEY_APP_HIBERNATION_ENABLED,
                true /* defaultValue */);
    }

    /**
     * Dependency injector for {@link #AppHibernationService)}.
     */
    interface Injector {
        Context getContext();

        IPackageManager getPackageManager();

        PackageManagerInternal getPackageManagerInternal();

        IActivityManager getActivityManager();

        UserManager getUserManager();

        StorageStatsManager getStorageStatsManager();

        Executor getBackgroundExecutor();

        UsageStatsManagerInternal getUsageStatsManagerInternal();

        HibernationStateDiskStore<GlobalLevelState> getGlobalLevelDiskStore();

        HibernationStateDiskStore<UserLevelState> getUserLevelDiskStore(int userId);

        boolean isOatArtifactDeletionEnabled();
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
        public PackageManagerInternal getPackageManagerInternal() {
            return LocalServices.getService(PackageManagerInternal.class);
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
        public StorageStatsManager getStorageStatsManager() {
            return mContext.getSystemService(StorageStatsManager.class);
        }

        @Override
        public Executor getBackgroundExecutor() {
            return mScheduledExecutorService;
        }

        @Override
        public UsageStatsManagerInternal getUsageStatsManagerInternal() {
            return LocalServices.getService(UsageStatsManagerInternal.class);
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

        @Override
        public boolean isOatArtifactDeletionEnabled() {
            return mContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_hibernationDeletesOatArtifactsEnabled);
        }
    }

    private final class StatsPullAtomCallbackImpl implements StatsPullAtomCallback {

        private static final int MEGABYTE_IN_BYTES = 1000000;

        @Override
        public int onPullAtom(int atomTag, @NonNull List<StatsEvent> data) {
            if (!isAppHibernationEnabled()
                    && (atomTag == FrameworkStatsLog.USER_LEVEL_HIBERNATED_APPS
                    || atomTag == FrameworkStatsLog.GLOBAL_HIBERNATED_APPS)) {
                return StatsManager.PULL_SUCCESS;
            }

            switch (atomTag) {
                case FrameworkStatsLog.USER_LEVEL_HIBERNATED_APPS:
                    List<UserInfo> userInfos = mUserManager.getAliveUsers();
                    final int numUsers = userInfos.size();
                    for (int i = 0; i < numUsers; ++i) {
                        final int userId = userInfos.get(i).id;
                        if (mUserManager.isUserUnlockingOrUnlocked(userId)) {
                            data.add(
                                    FrameworkStatsLog.buildStatsEvent(
                                            atomTag,
                                            getHibernatingPackagesForUser(userId).size(),
                                            userId)
                            );
                        }
                    }
                    break;
                case FrameworkStatsLog.GLOBAL_HIBERNATED_APPS:
                    int hibernatedAppCount = 0;
                    long storage_saved_byte = 0;
                    synchronized (mLock) {
                        for (GlobalLevelState state : mGlobalHibernationStates.values()) {
                            if (state.hibernated) {
                                hibernatedAppCount++;
                                storage_saved_byte += state.savedByte;
                            }
                        }
                    }
                    data.add(
                            FrameworkStatsLog.buildStatsEvent(
                                    atomTag,
                                    hibernatedAppCount,
                                    storage_saved_byte / MEGABYTE_IN_BYTES)
                    );
                    break;
                default:
                    return StatsManager.PULL_SKIP;
            }
            return StatsManager.PULL_SUCCESS;
        }
    }
}
