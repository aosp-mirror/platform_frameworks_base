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

package android.permission;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.IActivityManager;
import android.app.PropertyInvalidatedCache;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.permission.SplitPermissionInfoParcelable;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.annotations.Immutable;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * System level service for accessing the permission capabilities of the platform.
 *
 * @hide
 */
@TestApi
@SystemApi
@SystemService(Context.PERMISSION_SERVICE)
public final class PermissionManager {
    private static final String TAG = PermissionManager.class.getName();

    /** @hide */
    public static final String KILL_APP_REASON_PERMISSIONS_REVOKED =
            "permissions revoked";
    /** @hide */
    public static final String KILL_APP_REASON_GIDS_CHANGED =
            "permission grant or revoke changed gids";

    private final @NonNull Context mContext;

    private final IPackageManager mPackageManager;

    private final IPermissionManager mPermissionManager;

    private List<SplitPermissionInfo> mSplitPermissionInfos;

    /**
     * Creates a new instance.
     *
     * @param context The current context in which to operate.
     * @hide
     */
    public PermissionManager(@NonNull Context context, IPackageManager packageManager)
            throws ServiceManager.ServiceNotFoundException {
        this(context, packageManager, IPermissionManager.Stub.asInterface(
                ServiceManager.getServiceOrThrow("permissionmgr")));
    }

    /**
     * Creates a new instance with the provided instantiation of the IPermissionManager.
     *
     * @param context           the current context in which to operate
     * @param packageManager    package manager service to be used for package related permission
     *                          requests
     * @param permissionManager injectable permission manager service
     * @hide
     */
    @VisibleForTesting
    public PermissionManager(@NonNull Context context, IPackageManager packageManager,
            IPermissionManager permissionManager) {
        mContext = context;
        mPackageManager = packageManager;
        mPermissionManager = permissionManager;
    }

    /**
     * Gets the version of the runtime permission database.
     *
     * @return The database version, -1 when this is an upgrade from pre-Q, 0 when this is a fresh
     * install.
     *
     * @hide
     */
    @TestApi
    @SystemApi
    @RequiresPermission(anyOf = {
            Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY,
            Manifest.permission.UPGRADE_RUNTIME_PERMISSIONS
    })
    public @IntRange(from = 0) int getRuntimePermissionsVersion() {
        try {
            return mPackageManager.getRuntimePermissionsVersion(mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the version of the runtime permission database.
     *
     * @param version The new version.
     *
     * @hide
     */
    @TestApi
    @SystemApi
    @RequiresPermission(anyOf = {
            Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY,
            Manifest.permission.UPGRADE_RUNTIME_PERMISSIONS
    })
    public void setRuntimePermissionsVersion(@IntRange(from = 0) int version) {
        try {
            mPackageManager.setRuntimePermissionsVersion(version, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get set of permissions that have been split into more granular or dependent permissions.
     *
     * <p>E.g. before {@link android.os.Build.VERSION_CODES#Q} an app that was granted
     * {@link Manifest.permission#ACCESS_COARSE_LOCATION} could access he location while it was in
     * foreground and background. On platforms after {@link android.os.Build.VERSION_CODES#Q}
     * the location permission only grants location access while the app is in foreground. This
     * would break apps that target before {@link android.os.Build.VERSION_CODES#Q}. Hence whenever
     * such an old app asks for a location permission (i.e. the
     * {@link SplitPermissionInfo#getSplitPermission()}), then the
     * {@link Manifest.permission#ACCESS_BACKGROUND_LOCATION} permission (inside
     * {@link SplitPermissionInfo#getNewPermissions}) is added.
     *
     * <p>Note: Regular apps do not have to worry about this. The platform and permission controller
     * automatically add the new permissions where needed.
     *
     * @return All permissions that are split.
     */
    public @NonNull List<SplitPermissionInfo> getSplitPermissions() {
        if (mSplitPermissionInfos != null) {
            return mSplitPermissionInfos;
        }

        List<SplitPermissionInfoParcelable> parcelableList;
        try {
            parcelableList = ActivityThread.getPermissionManager().getSplitPermissions();
        } catch (RemoteException e) {
            Slog.e(TAG, "Error getting split permissions", e);
            return Collections.emptyList();
        }

        mSplitPermissionInfos = splitPermissionInfoListToNonParcelableList(parcelableList);

        return mSplitPermissionInfos;
    }

    /**
     * Grant default permissions to currently active LUI app
     * @param packageName The package name for the LUI app
     * @param user The user handle
     * @param executor The executor for the callback
     * @param callback The callback provided by caller to be notified when grant completes
     * @hide
     */
    @RequiresPermission(Manifest.permission.GRANT_RUNTIME_PERMISSIONS_TO_TELEPHONY_DEFAULTS)
    public void grantDefaultPermissionsToLuiApp(
            @NonNull String packageName, @NonNull UserHandle user,
            @NonNull @CallbackExecutor Executor executor, @NonNull Consumer<Boolean> callback) {
        try {
            mPermissionManager.grantDefaultPermissionsToActiveLuiApp(
                    packageName, user.getIdentifier());
            executor.execute(() -> callback.accept(true));
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Revoke default permissions to currently active LUI app
     * @param packageNames The package names for the LUI apps
     * @param user The user handle
     * @param executor The executor for the callback
     * @param callback The callback provided by caller to be notified when grant completes
     * @hide
     */
    @RequiresPermission(Manifest.permission.GRANT_RUNTIME_PERMISSIONS_TO_TELEPHONY_DEFAULTS)
    public void revokeDefaultPermissionsFromLuiApps(
            @NonNull String[] packageNames, @NonNull UserHandle user,
            @NonNull @CallbackExecutor Executor executor, @NonNull Consumer<Boolean> callback) {
        try {
            mPermissionManager.revokeDefaultPermissionsFromLuiApps(
                    packageNames, user.getIdentifier());
            executor.execute(() -> callback.accept(true));
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Grant default permissions to currently active Ims services
     * @param packageNames The package names for the Ims services
     * @param user The user handle
     * @param executor The executor for the callback
     * @param callback The callback provided by caller to be notified when grant completes
     * @hide
     */
    @RequiresPermission(Manifest.permission.GRANT_RUNTIME_PERMISSIONS_TO_TELEPHONY_DEFAULTS)
    public void grantDefaultPermissionsToEnabledImsServices(
            @NonNull String[] packageNames, @NonNull UserHandle user,
            @NonNull @CallbackExecutor Executor executor, @NonNull Consumer<Boolean> callback) {
        try {
            mPermissionManager.grantDefaultPermissionsToEnabledImsServices(
                    packageNames, user.getIdentifier());
            executor.execute(() -> callback.accept(true));
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Grant default permissions to currently enabled telephony data services
     * @param packageNames The package name for the services
     * @param user The user handle
     * @param executor The executor for the callback
     * @param callback The callback provided by caller to be notified when grant completes
     * @hide
     */
    @RequiresPermission(Manifest.permission.GRANT_RUNTIME_PERMISSIONS_TO_TELEPHONY_DEFAULTS)
    public void grantDefaultPermissionsToEnabledTelephonyDataServices(
            @NonNull String[] packageNames, @NonNull UserHandle user,
            @NonNull @CallbackExecutor Executor executor, @NonNull Consumer<Boolean> callback) {
        try {
            mPermissionManager.grantDefaultPermissionsToEnabledTelephonyDataServices(
                    packageNames, user.getIdentifier());
            executor.execute(() -> callback.accept(true));
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Revoke default permissions to currently active telephony data services
     * @param packageNames The package name for the services
     * @param user The user handle
     * @param executor The executor for the callback
     * @param callback The callback provided by caller to be notified when revoke completes
     * @hide
     */
    @RequiresPermission(Manifest.permission.GRANT_RUNTIME_PERMISSIONS_TO_TELEPHONY_DEFAULTS)
    public void revokeDefaultPermissionsFromDisabledTelephonyDataServices(
            @NonNull String[] packageNames, @NonNull UserHandle user,
            @NonNull @CallbackExecutor Executor executor, @NonNull Consumer<Boolean> callback) {
        try {
            mPermissionManager.revokeDefaultPermissionsFromDisabledTelephonyDataServices(
                    packageNames, user.getIdentifier());
            executor.execute(() -> callback.accept(true));
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Grant default permissions to currently enabled carrier apps
     * @param packageNames Package names of the apps to be granted permissions
     * @param user The user handle
     * @param executor The executor for the callback
     * @param callback The callback provided by caller to be notified when grant completes
     * @hide
     */
    @RequiresPermission(Manifest.permission.GRANT_RUNTIME_PERMISSIONS_TO_TELEPHONY_DEFAULTS)
    public void grantDefaultPermissionsToEnabledCarrierApps(@NonNull String[] packageNames,
            @NonNull UserHandle user, @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<Boolean> callback) {
        try {
            mPermissionManager.grantDefaultPermissionsToEnabledCarrierApps(packageNames,
                    user.getIdentifier());
            executor.execute(() -> callback.accept(true));
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the list of packages that have permissions that specified
     * {@code requestDontAutoRevokePermissions=true} in their
     * {@code application} manifest declaration.
     *
     * @return the list of packages for current user
     * @hide
     */
    @SystemApi
    @NonNull
    @RequiresPermission(Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY)
    public Set<String> getAutoRevokeExemptionRequestedPackages() {
        try {
            return CollectionUtils.toSet(mPermissionManager.getAutoRevokeExemptionRequestedPackages(
                    mContext.getUser().getIdentifier()));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the list of packages that have permissions that specified
     * {@code autoRevokePermissions=disallowed} in their
     * {@code application} manifest declaration.
     *
     * @return the list of packages for current user
     * @hide
     */
    @SystemApi
    @NonNull
    @RequiresPermission(Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY)
    public Set<String> getAutoRevokeExemptionGrantedPackages() {
        try {
            return CollectionUtils.toSet(mPermissionManager.getAutoRevokeExemptionGrantedPackages(
                    mContext.getUser().getIdentifier()));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private List<SplitPermissionInfo> splitPermissionInfoListToNonParcelableList(
            List<SplitPermissionInfoParcelable> parcelableList) {
        final int size = parcelableList.size();
        List<SplitPermissionInfo> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(new SplitPermissionInfo(parcelableList.get(i)));
        }
        return list;
    }

    /**
     * Converts a {@link List} of {@link SplitPermissionInfo} into a List of
     * {@link SplitPermissionInfoParcelable} and returns it.
     * @hide
     */
    public static List<SplitPermissionInfoParcelable> splitPermissionInfoListToParcelableList(
            List<SplitPermissionInfo> splitPermissionsList) {
        final int size = splitPermissionsList.size();
        List<SplitPermissionInfoParcelable> outList = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            SplitPermissionInfo info = splitPermissionsList.get(i);
            outList.add(new SplitPermissionInfoParcelable(
                    info.getSplitPermission(), info.getNewPermissions(), info.getTargetSdk()));
        }
        return outList;
    }

    /**
     * A permission that was added in a previous API level might have split into several
     * permissions. This object describes one such split.
     */
    @Immutable
    public static final class SplitPermissionInfo {
        private @NonNull final SplitPermissionInfoParcelable mSplitPermissionInfoParcelable;

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            SplitPermissionInfo that = (SplitPermissionInfo) o;
            return mSplitPermissionInfoParcelable.equals(that.mSplitPermissionInfoParcelable);
        }

        @Override
        public int hashCode() {
            return mSplitPermissionInfoParcelable.hashCode();
        }

        /**
         * Get the permission that is split.
         */
        public @NonNull String getSplitPermission() {
            return mSplitPermissionInfoParcelable.getSplitPermission();
        }

        /**
         * Get the permissions that are added.
         */
        public @NonNull List<String> getNewPermissions() {
            return mSplitPermissionInfoParcelable.getNewPermissions();
        }

        /**
         * Get the target API level when the permission was split.
         */
        public int getTargetSdk() {
            return mSplitPermissionInfoParcelable.getTargetSdk();
        }

        /**
         * Constructs a split permission.
         *
         * @param splitPerm old permission that will be split
         * @param newPerms list of new permissions that {@code rootPerm} will be split into
         * @param targetSdk apps targetting SDK versions below this will have {@code rootPerm}
         * split into {@code newPerms}
         * @hide
         */
        public SplitPermissionInfo(@NonNull String splitPerm, @NonNull List<String> newPerms,
                int targetSdk) {
            this(new SplitPermissionInfoParcelable(splitPerm, newPerms, targetSdk));
        }

        private SplitPermissionInfo(@NonNull SplitPermissionInfoParcelable parcelable) {
            mSplitPermissionInfoParcelable = parcelable;
        }
    }

    /**
     * Starts a one-time permission session for a given package. A one-time permission session is
     * ended if app becomes inactive. Inactivity is defined as the package's uid importance level
     * staying > importanceToResetTimer for timeoutMillis milliseconds. If the package's uid
     * importance level goes <= importanceToResetTimer then the timer is reset and doesn't start
     * until going > importanceToResetTimer.
     * <p>
     * When this timeoutMillis is reached if the importance level is <= importanceToKeepSessionAlive
     * then the session is extended until either the importance goes above
     * importanceToKeepSessionAlive which will end the session or <= importanceToResetTimer which
     * will continue the session and reset the timer.
     * </p>
     * <p>
     * Importance levels are defined in {@link android.app.ActivityManager.RunningAppProcessInfo}.
     * </p>
     * <p>
     * Once the session ends
     * {@link PermissionControllerService#onOneTimePermissionSessionTimeout(String)} is invoked.
     * </p>
     * <p>
     * Note that if there is currently an active session for a package a new one isn't created and
     * the existing one isn't changed.
     * </p>
     * @param packageName The package to start a one-time permission session for
     * @param timeoutMillis Number of milliseconds for an app to be in an inactive state
     * @param importanceToResetTimer The least important level to uid must be to reset the timer
     * @param importanceToKeepSessionAlive The least important level the uid must be to keep the
     *                                    session alive
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MANAGE_ONE_TIME_PERMISSION_SESSIONS)
    public void startOneTimePermissionSession(@NonNull String packageName, long timeoutMillis,
            @ActivityManager.RunningAppProcessInfo.Importance int importanceToResetTimer,
            @ActivityManager.RunningAppProcessInfo.Importance int importanceToKeepSessionAlive) {
        try {
            mPermissionManager.startOneTimePermissionSession(packageName, mContext.getUserId(),
                    timeoutMillis, importanceToResetTimer, importanceToKeepSessionAlive);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Stops the one-time permission session for the package. The callback to the end of session is
     * not invoked. If there is no one-time session for the package then nothing happens.
     *
     * @param packageName Package to stop the one-time permission session for
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MANAGE_ONE_TIME_PERMISSION_SESSIONS)
    public void stopOneTimePermissionSession(@NonNull String packageName) {
        try {
            mPermissionManager.stopOneTimePermissionSession(packageName,
                    mContext.getUserId());
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Checks whether the package with the given pid/uid can read device identifiers.
     *
     * @param packageName      the name of the package to be checked for identifier access
     * @param message          the message to be used for logging during identifier access
     *                         verification
     * @param callingFeatureId the feature in the package
     * @param pid              the process id of the package to be checked
     * @param uid              the uid of the package to be checked
     * @return {@link PackageManager#PERMISSION_GRANTED} if the package is allowed identifier
     * access, {@link PackageManager#PERMISSION_DENIED} otherwise
     * @hide
     */
    @SystemApi
    public int checkDeviceIdentifierAccess(@Nullable String packageName, @Nullable String message,
            @Nullable String callingFeatureId, int pid, int uid) {
        try {
            return mPermissionManager.checkDeviceIdentifierAccess(packageName, message,
                    callingFeatureId, pid, uid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /* @hide */
    private static int checkPermissionUncached(@Nullable String permission, int pid, int uid) {
        final IActivityManager am = ActivityManager.getService();
        if (am == null) {
            // Well this is super awkward; we somehow don't have an active ActivityManager
            // instance. If we're testing a root or system UID, then they totally have whatever
            // permission this is.
            final int appId = UserHandle.getAppId(uid);
            if (appId == Process.ROOT_UID || appId == Process.SYSTEM_UID) {
                Slog.w(TAG, "Missing ActivityManager; assuming " + uid + " holds " + permission);
                return PackageManager.PERMISSION_GRANTED;
            }
            Slog.w(TAG, "Missing ActivityManager; assuming " + uid + " does not hold "
                    + permission);
            return PackageManager.PERMISSION_DENIED;
        }
        try {
            return am.checkPermission(permission, pid, uid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Identifies a permission query.
     *
     * N.B. we include the checking pid for tracking purposes but don't include it in the equality
     * comparison: we use only uid for the actual security check, so comparing pid would result
     * in spurious misses.
     *
     * @hide
     */
    @Immutable
    private static final class PermissionQuery {
        final String permission;
        final int pid;
        final int uid;

        PermissionQuery(@Nullable String permission, int pid, int uid) {
            this.permission = permission;
            this.pid = pid;
            this.uid = uid;
        }

        @Override
        public String toString() {
            return String.format("PermissionQuery(permission=\"%s\", pid=%s, uid=%s)",
                    permission, pid, uid);
        }

        @Override
        public int hashCode() {
            // N.B. pid doesn't count toward equality and therefore shouldn't count for
            // hashing either.
            int hash = Objects.hashCode(permission);
            hash = hash * 13 + Objects.hashCode(uid);
            return hash;
        }

        @Override
        public boolean equals(Object rval) {
            // N.B. pid doesn't count toward equality!
            if (rval == null) {
                return false;
            }
            PermissionQuery other;
            try {
                other = (PermissionQuery) rval;
            } catch (ClassCastException ex) {
                return false;
            }
            return uid == other.uid
                    && Objects.equals(permission, other.permission);
        }
    }

    /** @hide */
    public static final String CACHE_KEY_PACKAGE_INFO = "cache_key.package_info";

    /** @hide */
    private static final PropertyInvalidatedCache<PermissionQuery, Integer> sPermissionCache =
            new PropertyInvalidatedCache<PermissionQuery, Integer>(
                    16, CACHE_KEY_PACKAGE_INFO) {
                @Override
                protected Integer recompute(PermissionQuery query) {
                    return checkPermissionUncached(query.permission, query.pid, query.uid);
                }
            };

    /** @hide */
    public static int checkPermission(@Nullable String permission, int pid, int uid) {
        return sPermissionCache.query(new PermissionQuery(permission, pid, uid));
    }

    /**
     * Make checkPermission() above bypass the permission cache in this process.
     *
     * @hide
     */
    public static void disablePermissionCache() {
        sPermissionCache.disableLocal();
    }

    /**
     * Like PermissionQuery, but for permission checks based on a package name instead of
     * a UID.
     */
    @Immutable
    private static final class PackageNamePermissionQuery {
        final String permName;
        final String pkgName;
        final int uid;

        PackageNamePermissionQuery(@Nullable String permName, @Nullable String pkgName, int uid) {
            this.permName = permName;
            this.pkgName = pkgName;
            this.uid = uid;
        }

        @Override
        public String toString() {
            return String.format(
                    "PackageNamePermissionQuery(pkgName=\"%s\", permName=\"%s, uid=%s\")",
                    pkgName, permName, uid);
        }

        @Override
        public int hashCode() {
            return Objects.hash(permName, pkgName, uid);
        }

        @Override
        public boolean equals(Object rval) {
            if (rval == null) {
                return false;
            }
            PackageNamePermissionQuery other;
            try {
                other = (PackageNamePermissionQuery) rval;
            } catch (ClassCastException ex) {
                return false;
            }
            return Objects.equals(permName, other.permName)
                    && Objects.equals(pkgName, other.pkgName)
                    && uid == other.uid;
        }
    }

    /* @hide */
    private static int checkPackageNamePermissionUncached(
            String permName, String pkgName, int uid) {
        try {
            return ActivityThread.getPermissionManager().checkPermission(
                    permName, pkgName, uid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /* @hide */
    private static PropertyInvalidatedCache<PackageNamePermissionQuery, Integer>
            sPackageNamePermissionCache =
            new PropertyInvalidatedCache<PackageNamePermissionQuery, Integer>(
                    16, CACHE_KEY_PACKAGE_INFO) {
                @Override
                protected Integer recompute(PackageNamePermissionQuery query) {
                    return checkPackageNamePermissionUncached(
                            query.permName, query.pkgName, query.uid);
                }
            };

    /**
     * Check whether a package has a permission.
     *
     * @hide
     */
    public static int checkPackageNamePermission(String permName, String pkgName, int uid) {
        return sPackageNamePermissionCache.query(
                new PackageNamePermissionQuery(permName, pkgName, uid));
    }

    /**
     * Make checkPackageNamePermission() bypass the cache in this process.
     *
     * @hide
     */
    public static void disablePackageNamePermissionCache() {
        sPackageNamePermissionCache.disableLocal();
    }

}
