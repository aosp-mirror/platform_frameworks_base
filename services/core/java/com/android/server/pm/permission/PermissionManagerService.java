/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.pm.permission;

import static android.Manifest.permission.CAMERA;
import static android.Manifest.permission.CAPTURE_AUDIO_HOTWORD;
import static android.Manifest.permission.CAPTURE_AUDIO_OUTPUT;
import static android.Manifest.permission.RECORD_AUDIO;
import static android.Manifest.permission.UPDATE_APP_OPS_STATS;
import static android.app.AppOpsManager.ATTRIBUTION_CHAIN_ID_NONE;
import static android.app.AppOpsManager.ATTRIBUTION_FLAGS_NONE;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_ERRORED;
import static android.app.AppOpsManager.MODE_IGNORED;
import static android.content.pm.ApplicationInfo.AUTO_REVOKE_DISALLOWED;
import static android.content.pm.ApplicationInfo.AUTO_REVOKE_DISCOURAGED;

import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;

import android.Manifest;
import android.annotation.AppIdInt;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.AppOpsManager.AttributionFlags;
import android.app.IActivityManager;
import android.content.AttributionSource;
import android.content.AttributionSourceState;
import android.content.Context;
import android.content.PermissionChecker;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.permission.SplitPermissionInfoParcelable;
import android.health.connect.HealthConnectManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.permission.IOnPermissionsChangeListener;
import android.permission.IPermissionChecker;
import android.permission.IPermissionManager;
import android.permission.PermissionCheckerManager;
import android.permission.PermissionManager;
import android.permission.PermissionManagerInternal;
import android.service.voice.VoiceInteractionManagerInternal;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import com.android.internal.util.function.TriFunction;
import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;
import com.android.server.pm.UserManagerService;
import com.android.server.pm.permission.PermissionManagerServiceInternal.HotwordDetectionServiceProvider;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/**
 * Manages all permissions and handles permissions related tasks.
 */
public class PermissionManagerService extends IPermissionManager.Stub {

    private static final String LOG_TAG = PermissionManagerService.class.getSimpleName();

    /** Map of IBinder -> Running AttributionSource */
    private static final ConcurrentHashMap<IBinder, RegisteredAttribution>
            sRunningAttributionSources = new ConcurrentHashMap<>();

    /** Lock to protect internal data access */
    private final Object mLock = new Object();

    /** Internal connection to the package manager */
    private final PackageManagerInternal mPackageManagerInt;

    /** Internal connection to the user manager */
    private final UserManagerInternal mUserManagerInt;

    /** Map of OneTimePermissionUserManagers keyed by userId */
    @GuardedBy("mLock")
    @NonNull
    private final SparseArray<OneTimePermissionUserManager> mOneTimePermissionUserManagers =
            new SparseArray<>();

    /** App ops manager */
    private final AppOpsManager mAppOpsManager;

    private final Context mContext;
    private final PermissionManagerServiceInterface mPermissionManagerServiceImpl;

    @NonNull
    private final AttributionSourceRegistry mAttributionSourceRegistry;

    @GuardedBy("mLock")
    private CheckPermissionDelegate mCheckPermissionDelegate;

    @Nullable
    private HotwordDetectionServiceProvider mHotwordDetectionServiceProvider;

    PermissionManagerService(@NonNull Context context,
            @NonNull ArrayMap<String, FeatureInfo> availableFeatures) {
        // The package info cache is the cache for package and permission information.
        // Disable the package info and package permission caches locally but leave the
        // checkPermission cache active.
        PackageManager.invalidatePackageInfoCache();
        PermissionManager.disablePackageNamePermissionCache();

        mContext = context;
        mPackageManagerInt = LocalServices.getService(PackageManagerInternal.class);
        mUserManagerInt = LocalServices.getService(UserManagerInternal.class);
        mAppOpsManager = context.getSystemService(AppOpsManager.class);

        mAttributionSourceRegistry = new AttributionSourceRegistry(context);

        PermissionManagerServiceInternalImpl localService =
                new PermissionManagerServiceInternalImpl();
        LocalServices.addService(PermissionManagerServiceInternal.class, localService);
        LocalServices.addService(PermissionManagerInternal.class, localService);

        mPermissionManagerServiceImpl = new PermissionManagerServiceImpl(context,
                availableFeatures);
        //mPermissionManagerServiceImpl = new PermissionManagerServiceLoggingDecorator(
        //        LocalServices.getService(PermissionManagerServiceInterface.class));
    }

    /**
     * Creates and returns an initialized, internal service for use by other components.
     * <p>
     * The object returned is identical to the one returned by the LocalServices class using:
     * {@code LocalServices.getService(PermissionManagerServiceInternal.class);}
     * <p>
     * NOTE: The external lock is temporary and should be removed. This needs to be a
     * lock created by the permission manager itself.
     */
    @NonNull
    public static PermissionManagerServiceInternal create(@NonNull Context context,
            ArrayMap<String, FeatureInfo> availableFeatures) {
        final PermissionManagerServiceInternal permMgrInt =
                LocalServices.getService(PermissionManagerServiceInternal.class);
        if (permMgrInt != null) {
            return permMgrInt;
        }
        PermissionManagerService permissionService =
                (PermissionManagerService) ServiceManager.getService("permissionmgr");
        if (permissionService == null) {
            permissionService = new PermissionManagerService(context, availableFeatures);
            ServiceManager.addService("permissionmgr", permissionService);
            ServiceManager.addService("permission_checker", new PermissionCheckerService(context));
        }
        return LocalServices.getService(PermissionManagerServiceInternal.class);
    }

    /**
     * TODO: theianchen we want to remove this method in the future.
     * There's a complete copy of this method in PermissionManagerServiceImpl
     *
     * This method should typically only be used when granting or revoking
     * permissions, since the app may immediately restart after this call.
     * <p>
     * If you're doing surgery on app code/data, use {@link PackageFreezer} to
     * guard your work against the app being relaunched.
     */
    public static void killUid(int appId, int userId, String reason) {
        final long identity = Binder.clearCallingIdentity();
        try {
            IActivityManager am = ActivityManager.getService();
            if (am != null) {
                try {
                    am.killUidForPermissionChange(appId, userId, reason);
                } catch (RemoteException e) {
                    /* ignore - same process */
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private int checkPermission(String pkgName, String permName, @UserIdInt int userId) {
        // Not using Objects.requireNonNull() here for compatibility reasons.
        if (pkgName == null || permName == null) {
            return PackageManager.PERMISSION_DENIED;
        }

        final CheckPermissionDelegate checkPermissionDelegate;
        synchronized (mLock) {
            checkPermissionDelegate = mCheckPermissionDelegate;
        }

        if (checkPermissionDelegate == null) {
            return mPermissionManagerServiceImpl.checkPermission(pkgName, permName, userId);
        }
        return checkPermissionDelegate.checkPermission(pkgName, permName, userId,
                mPermissionManagerServiceImpl::checkPermission);
    }

    private int checkUidPermission(int uid, String permName) {
        // Not using Objects.requireNonNull() here for compatibility reasons.
        if (permName == null) {
            return PackageManager.PERMISSION_DENIED;
        }

        final CheckPermissionDelegate checkPermissionDelegate;
        synchronized (mLock) {
            checkPermissionDelegate = mCheckPermissionDelegate;
        }

        if (checkPermissionDelegate == null)  {
            return mPermissionManagerServiceImpl.checkUidPermission(uid, permName);
        }
        return checkPermissionDelegate.checkUidPermission(uid, permName,
                mPermissionManagerServiceImpl::checkUidPermission);
    }

    @Override
    public boolean setAutoRevokeExempted(
            @NonNull String packageName, boolean exempted, int userId) {
        Objects.requireNonNull(packageName);

        final AndroidPackage pkg = mPackageManagerInt.getPackage(packageName);
        final int callingUid = Binder.getCallingUid();

        if (!checkAutoRevokeAccess(pkg, callingUid)) {
            return false;
        }

        return setAutoRevokeExemptedInternal(pkg, exempted, userId);
    }

    private boolean setAutoRevokeExemptedInternal(@NonNull AndroidPackage pkg, boolean exempted,
            @UserIdInt int userId) {
        final int packageUid = UserHandle.getUid(userId, pkg.getUid());
        if (mAppOpsManager.checkOpNoThrow(AppOpsManager.OP_AUTO_REVOKE_MANAGED_BY_INSTALLER,
                packageUid, pkg.getPackageName()) != MODE_ALLOWED) {
            // Allowlist user set - don't override
            return false;
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            mAppOpsManager.setMode(AppOpsManager.OP_AUTO_REVOKE_PERMISSIONS_IF_UNUSED, packageUid,
                    pkg.getPackageName(), exempted ? MODE_IGNORED : MODE_ALLOWED);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return true;
    }

    private boolean checkAutoRevokeAccess(AndroidPackage pkg, int callingUid) {
        final boolean isCallerPrivileged = mContext.checkCallingOrSelfPermission(
                Manifest.permission.WHITELIST_AUTO_REVOKE_PERMISSIONS)
                == PackageManager.PERMISSION_GRANTED;
        final boolean isCallerInstallerOnRecord =
                mPackageManagerInt.isCallerInstallerOfRecord(pkg, callingUid);

        if (!isCallerPrivileged && !isCallerInstallerOnRecord) {
            throw new SecurityException("Caller must either hold "
                    + Manifest.permission.WHITELIST_AUTO_REVOKE_PERMISSIONS
                    + " or be the installer on record");
        }

        if (pkg == null || mPackageManagerInt.filterAppAccess(pkg, callingUid,
                UserHandle.getUserId(callingUid))) {
            return false;
        }

        return true;
    }

    @Override
    public boolean isAutoRevokeExempted(@NonNull String packageName, int userId) {
        Objects.requireNonNull(packageName);

        final AndroidPackage pkg = mPackageManagerInt.getPackage(packageName);
        final int callingUid = Binder.getCallingUid();

        if (!checkAutoRevokeAccess(pkg, callingUid)) {
            return false;
        }

        final int packageUid = UserHandle.getUid(userId, pkg.getUid());

        final long identity = Binder.clearCallingIdentity();
        try {
            return mAppOpsManager.checkOpNoThrow(
                    AppOpsManager.OP_AUTO_REVOKE_PERMISSIONS_IF_UNUSED, packageUid, packageName)
                    == MODE_IGNORED;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void startShellPermissionIdentityDelegationInternal(int uid,
            @NonNull String packageName, @Nullable List<String> permissionNames) {
        synchronized (mLock) {
            final CheckPermissionDelegate oldDelegate = mCheckPermissionDelegate;
            if (oldDelegate != null && oldDelegate.getDelegatedUid() != uid) {
                throw new SecurityException(
                        "Shell can delegate permissions only to one UID at a time");
            }
            final ShellDelegate delegate = new ShellDelegate(uid, packageName, permissionNames);
            setCheckPermissionDelegateLocked(delegate);
        }
    }

    private void stopShellPermissionIdentityDelegationInternal() {
        synchronized (mLock) {
            setCheckPermissionDelegateLocked(null);
        }
    }

    @Nullable
    private List<String> getDelegatedShellPermissionsInternal() {
        synchronized (mLock) {
            if (mCheckPermissionDelegate == null) {
                return Collections.EMPTY_LIST;
            }
            return mCheckPermissionDelegate.getDelegatedPermissionNames();
        }
    }

    private void setCheckPermissionDelegateLocked(@Nullable CheckPermissionDelegate delegate) {
        if (delegate != null || mCheckPermissionDelegate != null) {
            PackageManager.invalidatePackageInfoCache();
        }
        mCheckPermissionDelegate = delegate;
    }

    @NonNull
    private OneTimePermissionUserManager getOneTimePermissionUserManager(@UserIdInt int userId) {
        OneTimePermissionUserManager oneTimePermissionUserManager;
        synchronized (mLock) {
            oneTimePermissionUserManager = mOneTimePermissionUserManagers.get(userId);
            if (oneTimePermissionUserManager != null) {
                return oneTimePermissionUserManager;
            }
        }
        // We cannot create a new instance of OneTimePermissionUserManager while holding our own
        // lock, which may lead to a deadlock with the package manager lock. So we do it in a
        // retry-like way, and just discard the newly created instance if someone else managed to be
        // a little bit faster than us when we dropped our own lock.
        final OneTimePermissionUserManager newOneTimePermissionUserManager =
                new OneTimePermissionUserManager(mContext.createContextAsUser(UserHandle.of(userId),
                        /*flags*/ 0));
        synchronized (mLock) {
            oneTimePermissionUserManager = mOneTimePermissionUserManagers.get(userId);
            if (oneTimePermissionUserManager != null) {
                return oneTimePermissionUserManager;
            }
            oneTimePermissionUserManager = newOneTimePermissionUserManager;
            mOneTimePermissionUserManagers.put(userId, oneTimePermissionUserManager);
        }
        oneTimePermissionUserManager.registerUninstallListener();
        return oneTimePermissionUserManager;
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_ONE_TIME_PERMISSION_SESSIONS)
    @Override
    public void startOneTimePermissionSession(String packageName, @UserIdInt int userId,
            long timeoutMillis, long revokeAfterKilledDelayMillis) {
        startOneTimePermissionSession_enforcePermission();
        Objects.requireNonNull(packageName);

        final long token = Binder.clearCallingIdentity();
        try {
            getOneTimePermissionUserManager(userId).startPackageOneTimeSession(packageName,
                    timeoutMillis, revokeAfterKilledDelayMillis);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_ONE_TIME_PERMISSION_SESSIONS)
    @Override
    public void stopOneTimePermissionSession(String packageName, @UserIdInt int userId) {
        super.stopOneTimePermissionSession_enforcePermission();

        Objects.requireNonNull(packageName);

        final long token = Binder.clearCallingIdentity();
        try {
            getOneTimePermissionUserManager(userId).stopPackageOneTimeSession(packageName);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void registerAttributionSource(@NonNull AttributionSourceState source) {
        mAttributionSourceRegistry
                .registerAttributionSource(new AttributionSource(source));
    }

    @Override
    public boolean isRegisteredAttributionSource(@NonNull AttributionSourceState source) {
        return mAttributionSourceRegistry
                .isRegisteredAttributionSource(new AttributionSource(source));
    }

    @Override
    public List<String> getAutoRevokeExemptionRequestedPackages(int userId) {
        return getPackagesWithAutoRevokePolicy(AUTO_REVOKE_DISCOURAGED, userId);
    }

    @Override
    public List<String> getAutoRevokeExemptionGrantedPackages(int userId) {
        return getPackagesWithAutoRevokePolicy(AUTO_REVOKE_DISALLOWED, userId);
    }

    @NonNull
    private List<String> getPackagesWithAutoRevokePolicy(int autoRevokePolicy, int userId) {
        mContext.enforceCallingPermission(Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY,
                "Must hold " + Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY);

        List<String> result = new ArrayList<>();
        mPackageManagerInt.forEachInstalledPackage(pkg -> {
            if (pkg.getAutoRevokePermissions() == autoRevokePolicy) {
                result.add(pkg.getPackageName());
            }
        }, userId);
        return result;
    }

    /* Start of delegate methods to PermissionManagerServiceInterface */

    @Override
    public ParceledListSlice<PermissionGroupInfo> getAllPermissionGroups(int flags) {
        return new ParceledListSlice<>(mPermissionManagerServiceImpl.getAllPermissionGroups(flags));
    }

    @Override
    public PermissionGroupInfo getPermissionGroupInfo(String groupName, int flags) {
        return mPermissionManagerServiceImpl.getPermissionGroupInfo(groupName, flags);
    }

    @Override
    public PermissionInfo getPermissionInfo(String permissionName, String packageName, int flags) {
        return mPermissionManagerServiceImpl.getPermissionInfo(permissionName, flags, packageName);
    }

    @Override
    public ParceledListSlice<PermissionInfo> queryPermissionsByGroup(String groupName, int flags) {
        List<PermissionInfo> permissionInfo =
                mPermissionManagerServiceImpl.queryPermissionsByGroup(groupName, flags);
        if (permissionInfo == null) {
            return null;
        }

        return new ParceledListSlice<>(permissionInfo);
    }

    @Override
    public boolean addPermission(PermissionInfo permissionInfo, boolean async) {
        return mPermissionManagerServiceImpl.addPermission(permissionInfo, async);
    }

    @Override
    public void removePermission(String permissionName) {
        mPermissionManagerServiceImpl.removePermission(permissionName);
    }

    @Override
    public int getPermissionFlags(String packageName, String permissionName, int userId) {
        return mPermissionManagerServiceImpl
                .getPermissionFlags(packageName, permissionName, userId);
    }

    @Override
    public void updatePermissionFlags(String packageName, String permissionName, int flagMask,
            int flagValues, boolean checkAdjustPolicyFlagPermission, int userId) {
        mPermissionManagerServiceImpl.updatePermissionFlags(packageName, permissionName, flagMask,
                flagValues, checkAdjustPolicyFlagPermission, userId);
    }

    @Override
    public void updatePermissionFlagsForAllApps(int flagMask, int flagValues, int userId) {
        mPermissionManagerServiceImpl.updatePermissionFlagsForAllApps(flagMask, flagValues, userId);
    }

    @Override
    public void addOnPermissionsChangeListener(IOnPermissionsChangeListener listener) {
        mPermissionManagerServiceImpl.addOnPermissionsChangeListener(listener);
    }

    @Override
    public void removeOnPermissionsChangeListener(IOnPermissionsChangeListener listener) {
        mPermissionManagerServiceImpl.removeOnPermissionsChangeListener(listener);
    }

    @Override
    public List<String> getAllowlistedRestrictedPermissions(String packageName,
            int flags, int userId) {
        return mPermissionManagerServiceImpl.getAllowlistedRestrictedPermissions(packageName,
                flags, userId);
    }

    @Override
    public boolean addAllowlistedRestrictedPermission(String packageName, String permissionName,
            int flags, int userId) {
        return mPermissionManagerServiceImpl.addAllowlistedRestrictedPermission(packageName,
                permissionName, flags, userId);
    }

    @Override
    public boolean removeAllowlistedRestrictedPermission(String packageName, String permissionName,
            int flags, int userId) {
        return mPermissionManagerServiceImpl.removeAllowlistedRestrictedPermission(packageName,
                permissionName, flags, userId);
    }

    @Override
    public void grantRuntimePermission(String packageName, String permissionName, int userId) {
        mPermissionManagerServiceImpl.grantRuntimePermission(packageName, permissionName, userId);
    }

    @Override
    public void revokeRuntimePermission(String packageName, String permissionName, int userId,
            String reason) {
        mPermissionManagerServiceImpl.revokeRuntimePermission(packageName, permissionName, userId,
                reason);
    }

    @Override
    public void revokePostNotificationPermissionWithoutKillForTest(String packageName, int userId) {
        mPermissionManagerServiceImpl.revokePostNotificationPermissionWithoutKillForTest(
                packageName, userId);
    }

    @Override
    public boolean shouldShowRequestPermissionRationale(String packageName, String permissionName,
            int userId) {
        return mPermissionManagerServiceImpl.shouldShowRequestPermissionRationale(packageName,
                permissionName, userId);
    }

    @Override
    public boolean isPermissionRevokedByPolicy(String packageName, String permissionName,
            int userId) {
        return mPermissionManagerServiceImpl
                .isPermissionRevokedByPolicy(packageName, permissionName, userId);
    }

    @Override
    public List<SplitPermissionInfoParcelable> getSplitPermissions() {
        return mPermissionManagerServiceImpl.getSplitPermissions();
    }

    /* End of delegate methods to PermissionManagerServiceInterface */

    private class PermissionManagerServiceInternalImpl implements PermissionManagerServiceInternal {
        @Override
        public int checkPermission(@NonNull String packageName, @NonNull String permissionName,
                @UserIdInt int userId) {
            return PermissionManagerService.this.checkPermission(packageName, permissionName,
                    userId);
        }

        @Override
        public int checkUidPermission(int uid, @NonNull String permissionName) {
            return PermissionManagerService.this.checkUidPermission(uid, permissionName);
        }

        @Override
        public void startShellPermissionIdentityDelegation(int uid, @NonNull String packageName,
                @Nullable List<String> permissionNames) {
            Objects.requireNonNull(packageName, "packageName");
            startShellPermissionIdentityDelegationInternal(uid, packageName, permissionNames);
        }

        @Override
        public void stopShellPermissionIdentityDelegation() {
            stopShellPermissionIdentityDelegationInternal();
        }

        @Override
        @NonNull
        public List<String> getDelegatedShellPermissions() {
            return getDelegatedShellPermissionsInternal();
        }

        @Override
        public void setHotwordDetectionServiceProvider(HotwordDetectionServiceProvider provider) {
            mHotwordDetectionServiceProvider = provider;
        }

        @Override
        public HotwordDetectionServiceProvider getHotwordDetectionServiceProvider() {
            return mHotwordDetectionServiceProvider;
        }

        /* Start of delegate methods to PermissionManagerServiceInterface */

        @NonNull
        @Override
        public int[] getGidsForUid(int uid) {
            return mPermissionManagerServiceImpl.getGidsForUid(uid);
        }

        @NonNull
        @Override
        public Map<String, Set<String>> getAllAppOpPermissionPackages() {
            return mPermissionManagerServiceImpl.getAllAppOpPermissionPackages();
        }

        @Override
        public void onUserCreated(@UserIdInt int userId) {
            mPermissionManagerServiceImpl.onUserCreated(userId);
        }

        @NonNull
        @Override
        public List<LegacyPermission> getLegacyPermissions() {
            return mPermissionManagerServiceImpl.getLegacyPermissions();
        }

        @NonNull
        @Override
        public LegacyPermissionState getLegacyPermissionState(@AppIdInt int appId) {
            return mPermissionManagerServiceImpl.getLegacyPermissionState(appId);
        }

        @Nullable
        @Override
        public byte[] backupRuntimePermissions(@UserIdInt int userId) {
            return mPermissionManagerServiceImpl.backupRuntimePermissions(userId);
        }

        @Override
        public void restoreRuntimePermissions(@NonNull byte[] backup, @UserIdInt int userId) {
            mPermissionManagerServiceImpl.restoreRuntimePermissions(backup, userId);
        }

        @Override
        public void restoreDelayedRuntimePermissions(@NonNull String packageName,
                @UserIdInt int userId) {
            mPermissionManagerServiceImpl.restoreDelayedRuntimePermissions(packageName, userId);
        }

        @Override
        public void readLegacyPermissionsTEMP(
                @NonNull LegacyPermissionSettings legacyPermissionSettings) {
            mPermissionManagerServiceImpl.readLegacyPermissionsTEMP(legacyPermissionSettings);
        }

        @Override
        public void writeLegacyPermissionsTEMP(
                @NonNull LegacyPermissionSettings legacyPermissionSettings) {
            mPermissionManagerServiceImpl.writeLegacyPermissionsTEMP(legacyPermissionSettings);
        }

        @Override
        public void onPackageAdded(@NonNull PackageState packageState, boolean isInstantApp,
                @Nullable AndroidPackage oldPkg) {
            mPermissionManagerServiceImpl.onPackageAdded(packageState, isInstantApp, oldPkg);
        }

        @Override
        public void onPackageInstalled(@NonNull AndroidPackage pkg, int previousAppId,
                @NonNull PackageInstalledParams params, @UserIdInt int rawUserId) {
            Objects.requireNonNull(pkg, "pkg");
            Objects.requireNonNull(params, "params");
            Preconditions.checkArgument(rawUserId >= UserHandle.USER_SYSTEM
                    || rawUserId == UserHandle.USER_ALL, "userId");

            mPermissionManagerServiceImpl.onPackageInstalled(pkg, previousAppId, params, rawUserId);
            final int[] userIds = rawUserId == UserHandle.USER_ALL ? getAllUserIds()
                    : new int[] { rawUserId };
            for (final int userId : userIds) {
                final int autoRevokePermissionsMode = params.getAutoRevokePermissionsMode();
                if (autoRevokePermissionsMode == AppOpsManager.MODE_ALLOWED
                        || autoRevokePermissionsMode == AppOpsManager.MODE_IGNORED) {
                    setAutoRevokeExemptedInternal(pkg,
                            autoRevokePermissionsMode == AppOpsManager.MODE_IGNORED, userId);
                }
            }
        }

        @Override
        public void onPackageRemoved(@NonNull AndroidPackage pkg) {
            mPermissionManagerServiceImpl.onPackageRemoved(pkg);
        }

        @Override
        public void onPackageUninstalled(@NonNull String packageName, int appId,
                @NonNull PackageState packageState, @Nullable AndroidPackage pkg,
                @NonNull List<AndroidPackage> sharedUserPkgs, @UserIdInt int userId) {
            mPermissionManagerServiceImpl.onPackageUninstalled(packageName, appId, packageState,
                    pkg, sharedUserPkgs, userId);
        }

        @Override
        public void onSystemReady() {
            mPermissionManagerServiceImpl.onSystemReady();
        }

        @Override
        public boolean isPermissionsReviewRequired(@NonNull String packageName,
                @UserIdInt int userId) {
            return mPermissionManagerServiceImpl.isPermissionsReviewRequired(packageName, userId);
        }

        @Override
        public void readLegacyPermissionStateTEMP() {
            mPermissionManagerServiceImpl.readLegacyPermissionStateTEMP();
        }
        @Override
        public void writeLegacyPermissionStateTEMP() {
            mPermissionManagerServiceImpl.writeLegacyPermissionStateTEMP();
        }
        @Override
        public void onUserRemoved(@UserIdInt int userId) {
            mPermissionManagerServiceImpl.onUserRemoved(userId);
        }
        @NonNull
        @Override
        public Set<String> getInstalledPermissions(@NonNull String packageName) {
            return mPermissionManagerServiceImpl.getInstalledPermissions(packageName);
        }
        @NonNull
        @Override
        public Set<String> getGrantedPermissions(@NonNull String packageName,
                @UserIdInt int userId) {
            return mPermissionManagerServiceImpl.getGrantedPermissions(packageName, userId);
        }
        @NonNull
        @Override
        public int[] getPermissionGids(@NonNull String permissionName, @UserIdInt int userId) {
            return mPermissionManagerServiceImpl.getPermissionGids(permissionName, userId);
        }
        @NonNull
        @Override
        public String[] getAppOpPermissionPackages(@NonNull String permissionName) {
            return mPermissionManagerServiceImpl.getAppOpPermissionPackages(permissionName);
        }
        @Override
        public void onStorageVolumeMounted(@Nullable String volumeUuid,
                boolean fingerprintChanged) {
            mPermissionManagerServiceImpl.onStorageVolumeMounted(volumeUuid, fingerprintChanged);
        }
        @Override
        public void resetRuntimePermissions(@NonNull AndroidPackage pkg, @UserIdInt int userId) {
            mPermissionManagerServiceImpl.resetRuntimePermissions(pkg, userId);
        }
        @Override
        public void resetRuntimePermissionsForUser(@UserIdInt int userId) {
            mPermissionManagerServiceImpl.resetRuntimePermissionsForUser(userId);
        }

        @Override
        public Permission getPermissionTEMP(String permName) {
            return mPermissionManagerServiceImpl.getPermissionTEMP(permName);
        }

        @NonNull
        @Override
        public List<PermissionInfo> getAllPermissionsWithProtection(
                @PermissionInfo.Protection int protection) {
            return mPermissionManagerServiceImpl.getAllPermissionsWithProtection(protection);
        }

        @NonNull
        @Override
        public List<PermissionInfo> getAllPermissionsWithProtectionFlags(
                @PermissionInfo.ProtectionFlags int protectionFlags) {
            return mPermissionManagerServiceImpl
                    .getAllPermissionsWithProtectionFlags(protectionFlags);
        }

        /* End of delegate methods to PermissionManagerServiceInterface */
    }

    /**
     * Returns all relevant user ids.  This list include the current set of created user ids as well
     * as pre-created user ids.
     * @return user ids for created users and pre-created users
     */
    private int[] getAllUserIds() {
        return UserManagerService.getInstance().getUserIdsIncludingPreCreated();
    }

    /**
     * Interface to intercept permission checks and optionally pass through to the original
     * implementation.
     */
    private interface CheckPermissionDelegate {
        /**
         * Get the UID whose permission checks is being delegated.
         *
         * @return the UID
         */
        int getDelegatedUid();

        /**
         * Check whether the given package has been granted the specified permission.
         *
         * @param packageName the name of the package to be checked
         * @param permissionName the name of the permission to be checked
         * @param userId the user ID
         * @param superImpl the original implementation that can be delegated to
         * @return {@link android.content.pm.PackageManager#PERMISSION_GRANTED} if the package has
         * the permission, or {@link android.content.pm.PackageManager#PERMISSION_DENIED} otherwise
         *
         * @see android.content.pm.PackageManager#checkPermission(String, String)
         */
        int checkPermission(@NonNull String packageName, @NonNull String permissionName,
                @UserIdInt int userId,
                @NonNull TriFunction<String, String, Integer, Integer> superImpl);

        /**
         * Check whether the given UID has been granted the specified permission.
         *
         * @param uid the UID to be checked
         * @param permissionName the name of the permission to be checked
         * @param superImpl the original implementation that can be delegated to
         * @return {@link android.content.pm.PackageManager#PERMISSION_GRANTED} if the package has
         * the permission, or {@link android.content.pm.PackageManager#PERMISSION_DENIED} otherwise
         */
        int checkUidPermission(int uid, @NonNull String permissionName,
                BiFunction<Integer, String, Integer> superImpl);

        /**
         * @return list of delegated permissions
         */
        List<String> getDelegatedPermissionNames();
    }

    private class ShellDelegate implements CheckPermissionDelegate {
        private final int mDelegatedUid;
        @NonNull
        private final String mDelegatedPackageName;
        @Nullable
        private final List<String> mDelegatedPermissionNames;

        public ShellDelegate(int delegatedUid, @NonNull String delegatedPackageName,
                @Nullable List<String> delegatedPermissionNames) {
            mDelegatedUid = delegatedUid;
            mDelegatedPackageName = delegatedPackageName;
            mDelegatedPermissionNames = delegatedPermissionNames;
        }

        @Override
        public int getDelegatedUid() {
            return mDelegatedUid;
        }

        @Override
        public int checkPermission(@NonNull String packageName, @NonNull String permissionName,
                int userId, @NonNull TriFunction<String, String, Integer, Integer> superImpl) {
            if (mDelegatedPackageName.equals(packageName)
                    && isDelegatedPermission(permissionName)) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    return superImpl.apply("com.android.shell", permissionName, userId);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            return superImpl.apply(packageName, permissionName, userId);
        }

        @Override
        public int checkUidPermission(int uid, @NonNull String permissionName,
                @NonNull BiFunction<Integer, String, Integer> superImpl) {
            if (uid == mDelegatedUid && isDelegatedPermission(permissionName)) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    return superImpl.apply(Process.SHELL_UID, permissionName);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            return superImpl.apply(uid, permissionName);
        }

        @Override
        public List<String> getDelegatedPermissionNames() {
            return mDelegatedPermissionNames == null
                    ? null
                    : new ArrayList<>(mDelegatedPermissionNames);
        }

        private boolean isDelegatedPermission(@NonNull String permissionName) {
            // null permissions means all permissions are targeted
            return mDelegatedPermissionNames == null
                    || mDelegatedPermissionNames.contains(permissionName);
        }
    }

    private static final class AttributionSourceRegistry {
        private final Object mLock = new Object();

        private final Context mContext;

        AttributionSourceRegistry(@NonNull Context context) {
            mContext = context;
        }

        private final WeakHashMap<IBinder, AttributionSource> mAttributions = new WeakHashMap<>();

        public void registerAttributionSource(@NonNull AttributionSource source) {
            //   Here we keep track of attribution sources that were created by an app
            // from an attribution chain that called into the app and the apps's
            // own attribution source. An app can register an attribution chain up
            // to itself inclusive if and only if it is adding a node for itself which
            // optionally points to an attribution chain that was created by each
            // preceding app recursively up to the beginning of the chain.
            //   The only special case is when the first app in the attribution chain
            // creates a source that points to another app (not a chain of apps). We
            // allow this even if the source the app points to is not registered since
            // in app ops we allow every app to blame every other app (untrusted if not
            // holding a special permission).
            //  This technique ensures that a bad actor in the middle of the attribution
            // chain can neither prepend nor append an invalid attribution sequence, i.e.
            // a sequence that is not constructed by trusted sources up to the that bad
            // actor's app.
            //   Note that passing your attribution source to another app means you allow
            // it to blame private data access on your app. This can be mediated by the OS
            // in, which case security is already enforced; by other app's code running in
            // your process calling into the other app, in which case it can already access
            // the private data in your process; or by you explicitly calling to another
            // app passing the source, in which case you must trust the other side;

            final int callingUid = resolveUid(Binder.getCallingUid());
            final int sourceUid = resolveUid(source.getUid());
            if (sourceUid != callingUid && mContext.checkPermission(
                    Manifest.permission.UPDATE_APP_OPS_STATS, /*pid*/ -1, callingUid)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Cannot register attribution source for uid:"
                        + sourceUid + " from uid:" + callingUid);
            }

            final PackageManagerInternal packageManagerInternal = LocalServices.getService(
                    PackageManagerInternal.class);

            // TODO(b/234653108): Clean up this UID/package & cross-user check.
            // If calling from the system process, allow registering attribution for package from
            // any user
            int userId = UserHandle.getUserId((callingUid == Process.SYSTEM_UID ? sourceUid
                    : callingUid));
            if (packageManagerInternal.getPackageUid(source.getPackageName(), 0, userId)
                    != sourceUid) {
                throw new SecurityException("Cannot register attribution source for package:"
                        + source.getPackageName() + " from uid:" + callingUid);
            }

            final AttributionSource next = source.getNext();
            if (next != null && next.getNext() != null
                    && !isRegisteredAttributionSource(next)) {
                throw new SecurityException("Cannot register forged attribution source:"
                        + source);
            }

            synchronized (mLock) {
                mAttributions.put(source.getToken(), source);
            }
        }

        public boolean isRegisteredAttributionSource(@NonNull AttributionSource source) {
            synchronized (mLock) {
                final AttributionSource cachedSource = mAttributions.get(source.getToken());
                if (cachedSource != null) {
                    return cachedSource.equals(source);
                }
                return false;
            }
        }

        private int resolveUid(int uid) {
            final VoiceInteractionManagerInternal vimi = LocalServices
                    .getService(VoiceInteractionManagerInternal.class);
            if (vimi == null) {
                return uid;
            }
            final VoiceInteractionManagerInternal.HotwordDetectionServiceIdentity
                    hotwordDetectionServiceIdentity = vimi.getHotwordDetectionServiceIdentity();
            if (hotwordDetectionServiceIdentity != null
                    && uid == hotwordDetectionServiceIdentity.getIsolatedUid()) {
                return hotwordDetectionServiceIdentity.getOwnerUid();
            }
            return uid;
        }
    }

    /**
     * TODO: We need to consolidate these APIs either on PermissionManager or an extension
     * object or a separate PermissionChecker service in context. The impartant part is to
     * keep a single impl that is exposed to Java and native. We are not sure about the
     * API shape so let is soak a bit.
     */
    private static final class PermissionCheckerService extends IPermissionChecker.Stub {
        // Cache for platform defined runtime permissions to avoid multi lookup (name -> info)
        private static final ConcurrentHashMap<String, PermissionInfo> sPlatformPermissions
                = new ConcurrentHashMap<>();

        private static final AtomicInteger sAttributionChainIds = new AtomicInteger(0);

        private final @NonNull Context mContext;
        private final @NonNull AppOpsManager mAppOpsManager;
        private final @NonNull PermissionManagerServiceInternal mPermissionManagerServiceInternal;

        PermissionCheckerService(@NonNull Context context) {
            mContext = context;
            mAppOpsManager = mContext.getSystemService(AppOpsManager.class);
            mPermissionManagerServiceInternal =
                    LocalServices.getService(PermissionManagerServiceInternal.class);
        }

        @Override
        @PermissionCheckerManager.PermissionResult
        public int checkPermission(@NonNull String permission,
                @NonNull AttributionSourceState attributionSourceState, @Nullable String message,
                boolean forDataDelivery, boolean startDataDelivery, boolean fromDatasource,
                int attributedOp) {
            Objects.requireNonNull(permission);
            Objects.requireNonNull(attributionSourceState);
            final AttributionSource attributionSource = new AttributionSource(
                    attributionSourceState);
            final int result = checkPermission(mContext, mPermissionManagerServiceInternal,
                    permission, attributionSource, message, forDataDelivery, startDataDelivery,
                    fromDatasource, attributedOp);
            // Finish any started op if some step in the attribution chain failed.
            if (startDataDelivery && result != PermissionChecker.PERMISSION_GRANTED
                    && result != PermissionChecker.PERMISSION_SOFT_DENIED) {
                if (attributedOp == AppOpsManager.OP_NONE) {
                    finishDataDelivery(AppOpsManager.permissionToOpCode(permission),
                            attributionSource.asState(), fromDatasource);
                } else {
                    finishDataDelivery(attributedOp, attributionSource.asState(), fromDatasource);
                }
            }
            return result;
        }

        @Override
        public void finishDataDelivery(int op,
                @NonNull AttributionSourceState attributionSourceState, boolean fromDataSource) {
            finishDataDelivery(mContext, op, attributionSourceState,
                    fromDataSource);
        }

        private static void finishDataDelivery(Context context, int op,
                @NonNull AttributionSourceState attributionSourceState, boolean fromDatasource) {
            Objects.requireNonNull(attributionSourceState);
            AppOpsManager appOpsManager = context.getSystemService(AppOpsManager.class);

            if (op == AppOpsManager.OP_NONE) {
                return;
            }

            AttributionSource current = new AttributionSource(attributionSourceState);
            AttributionSource next = null;

            while (true) {
                final boolean skipCurrentFinish = (fromDatasource || next != null);

                next = current.getNext();

                // If the call is from a datasource we need to vet only the chain before it. This
                // way we can avoid the datasource creating an attribution context for every call.
                if (!(fromDatasource && current.asState() == attributionSourceState)
                        && next != null && !current.isTrusted(context)) {
                    return;
                }

                // The access is for oneself if this is the single receiver of data
                // after the data source or if this is the single attribution source
                // in the chain if not from a datasource.
                final boolean singleReceiverFromDatasource = (fromDatasource
                        && current.asState() == attributionSourceState && next != null
                        && next.getNext() == null);
                final boolean selfAccess = singleReceiverFromDatasource || next == null;

                final AttributionSource accessorSource = (!singleReceiverFromDatasource)
                        ? current : next;

                if (selfAccess) {
                    final String resolvedPackageName = resolvePackageName(context, accessorSource);
                    if (resolvedPackageName == null) {
                        return;
                    }
                    appOpsManager.finishOp(attributionSourceState.token, op,
                            accessorSource.getUid(), resolvedPackageName,
                            accessorSource.getAttributionTag());
                } else {
                    final AttributionSource resolvedAttributionSource =
                            resolveAttributionSource(context, accessorSource);
                    if (resolvedAttributionSource.getPackageName() == null) {
                        return;
                    }
                    appOpsManager.finishProxyOp(attributionSourceState.token,
                            AppOpsManager.opToPublicName(op), resolvedAttributionSource,
                            skipCurrentFinish);
                }
                RegisteredAttribution registered =
                        sRunningAttributionSources.remove(current.getToken());
                if (registered != null) {
                    registered.unregister();
                }

                if (next == null || next.getNext() == null) {
                    if (next != null) {
                        registered = sRunningAttributionSources.remove(next.getToken());
                        if (registered != null) {
                            registered.unregister();
                        }
                    }
                    return;
                }
                current = next;
            }
        }

        @Override
        @PermissionCheckerManager.PermissionResult
        public int checkOp(int op, AttributionSourceState attributionSource,
                String message, boolean forDataDelivery, boolean startDataDelivery) {
            int result = checkOp(mContext, op, mPermissionManagerServiceInternal,
                    new AttributionSource(attributionSource), message, forDataDelivery,
                    startDataDelivery);
            if (result != PermissionChecker.PERMISSION_GRANTED && startDataDelivery) {
                // Finish any started op if some step in the attribution chain failed.
                finishDataDelivery(op, attributionSource, /*fromDatasource*/ false);
            }
            return result;
        }

        @PermissionCheckerManager.PermissionResult
        private static int checkPermission(@NonNull Context context,
                @NonNull PermissionManagerServiceInternal permissionManagerServiceInt,
                @NonNull String permission, @NonNull AttributionSource attributionSource,
                @Nullable String message, boolean forDataDelivery, boolean startDataDelivery,
                boolean fromDatasource, int attributedOp) {
            PermissionInfo permissionInfo = sPlatformPermissions.get(permission);

            if (permissionInfo == null) {
                try {
                    permissionInfo = context.getPackageManager().getPermissionInfo(permission, 0);
                    if (PLATFORM_PACKAGE_NAME.equals(permissionInfo.packageName)
                            || HealthConnectManager.isHealthPermission(context, permission)) {
                        // Double addition due to concurrency is fine - the backing
                        // store is concurrent.
                        sPlatformPermissions.put(permission, permissionInfo);
                    }
                } catch (PackageManager.NameNotFoundException ignored) {
                    return PermissionChecker.PERMISSION_HARD_DENIED;
                }
            }

            if (permissionInfo.isAppOp()) {
                return checkAppOpPermission(context, permissionManagerServiceInt, permission,
                        attributionSource, message, forDataDelivery, fromDatasource);
            }
            if (permissionInfo.isRuntime()) {
                return checkRuntimePermission(context, permissionManagerServiceInt, permission,
                        attributionSource, message, forDataDelivery, startDataDelivery,
                        fromDatasource, attributedOp);
            }

            if (!fromDatasource && !checkPermission(context, permissionManagerServiceInt,
                    permission, attributionSource.getUid(),
                    attributionSource.getRenouncedPermissions())) {
                return PermissionChecker.PERMISSION_HARD_DENIED;
            }

            if (attributionSource.getNext() != null) {
                return checkPermission(context, permissionManagerServiceInt, permission,
                        attributionSource.getNext(), message, forDataDelivery, startDataDelivery,
                        /*fromDatasource*/ false, attributedOp);
            }

            return PermissionChecker.PERMISSION_GRANTED;
        }

        @PermissionCheckerManager.PermissionResult
        private static int checkAppOpPermission(@NonNull Context context,
                @NonNull PermissionManagerServiceInternal permissionManagerServiceInt,
                @NonNull String permission, @NonNull AttributionSource attributionSource,
                @Nullable String message, boolean forDataDelivery, boolean fromDatasource) {
            final int op = AppOpsManager.permissionToOpCode(permission);
            if (op < 0) {
                Slog.wtf(LOG_TAG, "Appop permission " + permission + " with no app op defined!");
                return PermissionChecker.PERMISSION_HARD_DENIED;
            }

            AttributionSource current = attributionSource;
            AttributionSource next = null;

            while (true) {
                final boolean skipCurrentChecks = (fromDatasource || next != null);

                next = current.getNext();

                // If the call is from a datasource we need to vet only the chain before it. This
                // way we can avoid the datasource creating an attribution context for every call.
                if (!(fromDatasource && current.equals(attributionSource))
                        && next != null && !current.isTrusted(context)) {
                    return PermissionChecker.PERMISSION_HARD_DENIED;
                }

                // The access is for oneself if this is the single receiver of data
                // after the data source or if this is the single attribution source
                // in the chain if not from a datasource.
                final boolean singleReceiverFromDatasource = (fromDatasource
                        && current.equals(attributionSource) && next != null
                        && next.getNext() == null);
                final boolean selfAccess = singleReceiverFromDatasource || next == null;

                final int opMode = performOpTransaction(context, attributionSource.getToken(), op,
                        current, message, forDataDelivery, /*startDataDelivery*/ false,
                        skipCurrentChecks, selfAccess, singleReceiverFromDatasource,
                        AppOpsManager.OP_NONE, AppOpsManager.ATTRIBUTION_FLAGS_NONE,
                        AppOpsManager.ATTRIBUTION_FLAGS_NONE,
                        AppOpsManager.ATTRIBUTION_CHAIN_ID_NONE);

                switch (opMode) {
                    case AppOpsManager.MODE_IGNORED:
                    case AppOpsManager.MODE_ERRORED: {
                        return PermissionChecker.PERMISSION_HARD_DENIED;
                    }
                    case AppOpsManager.MODE_DEFAULT: {
                        if (!skipCurrentChecks && !checkPermission(context,
                                permissionManagerServiceInt, permission, attributionSource.getUid(),
                                attributionSource.getRenouncedPermissions())) {
                            return PermissionChecker.PERMISSION_HARD_DENIED;
                        }
                        if (next != null && !checkPermission(context, permissionManagerServiceInt,
                                permission, next.getUid(), next.getRenouncedPermissions())) {
                            return PermissionChecker.PERMISSION_HARD_DENIED;
                        }
                    }
                }

                if (next == null || next.getNext() == null) {
                    return PermissionChecker.PERMISSION_GRANTED;
                }

                current = next;
            }
        }

        private static int checkRuntimePermission(@NonNull Context context,
                @NonNull PermissionManagerServiceInternal permissionManagerServiceInt,
                @NonNull String permission, @NonNull AttributionSource attributionSource,
                @Nullable String message, boolean forDataDelivery, boolean startDataDelivery,
                boolean fromDatasource, int attributedOp) {
            // Now let's check the identity chain...
            final int op = AppOpsManager.permissionToOpCode(permission);
            final int attributionChainId =
                    getAttributionChainId(startDataDelivery, attributionSource);
            final boolean hasChain = attributionChainId != ATTRIBUTION_CHAIN_ID_NONE;
            AttributionSource current = attributionSource;
            AttributionSource next = null;
            // We consider the chain trusted if the start node has UPDATE_APP_OPS_STATS, and
            // every attributionSource in the chain is registered with the system.
            final boolean isChainStartTrusted = !hasChain || checkPermission(context,
                    permissionManagerServiceInt, UPDATE_APP_OPS_STATS, current.getUid(),
                    current.getRenouncedPermissions());

            while (true) {
                final boolean skipCurrentChecks = (fromDatasource || next != null);
                next = current.getNext();

                // If the call is from a datasource we need to vet only the chain before it. This
                // way we can avoid the datasource creating an attribution context for every call.
                if (!(fromDatasource && current.equals(attributionSource))
                        && next != null && !current.isTrusted(context)) {
                    return PermissionChecker.PERMISSION_HARD_DENIED;
                }

                // If we already checked the permission for this one, skip the work
                if (!skipCurrentChecks && !checkPermission(context, permissionManagerServiceInt,
                        permission, current.getUid(), current.getRenouncedPermissions())) {
                    return PermissionChecker.PERMISSION_HARD_DENIED;
                }

                if (next != null && !checkPermission(context, permissionManagerServiceInt,
                        permission, next.getUid(), next.getRenouncedPermissions())) {
                    return PermissionChecker.PERMISSION_HARD_DENIED;
                }

                if (op < 0) {
                    // Bg location is one-off runtime modifier permission and has no app op
                    if (sPlatformPermissions.containsKey(permission)
                            && !Manifest.permission.ACCESS_BACKGROUND_LOCATION.equals(permission)
                            && !Manifest.permission.BODY_SENSORS_BACKGROUND.equals(permission)) {
                        Slog.wtf(LOG_TAG, "Platform runtime permission " + permission
                                + " with no app op defined!");
                    }
                    if (next == null) {
                        return PermissionChecker.PERMISSION_GRANTED;
                    }
                    current = next;
                    continue;
                }

                // The access is for oneself if this is the single receiver of data
                // after the data source or if this is the single attribution source
                // in the chain if not from a datasource.
                final boolean singleReceiverFromDatasource = (fromDatasource
                        && current.equals(attributionSource)
                        && next != null && next.getNext() == null);
                final boolean selfAccess = singleReceiverFromDatasource || next == null;
                final boolean isLinkTrusted = isChainStartTrusted
                        && (current.isTrusted(context) || current.equals(attributionSource))
                        && (next == null || next.isTrusted(context));

                final int proxyAttributionFlags = (!skipCurrentChecks && hasChain)
                        ? resolveProxyAttributionFlags(attributionSource, current, fromDatasource,
                                startDataDelivery, selfAccess, isLinkTrusted)
                        : ATTRIBUTION_FLAGS_NONE;
                final int proxiedAttributionFlags = hasChain ? resolveProxiedAttributionFlags(
                        attributionSource, next, fromDatasource, startDataDelivery, selfAccess,
                        isLinkTrusted) : ATTRIBUTION_FLAGS_NONE;

                final int opMode = performOpTransaction(context, attributionSource.getToken(), op,
                        current, message, forDataDelivery, startDataDelivery, skipCurrentChecks,
                        selfAccess, singleReceiverFromDatasource, attributedOp,
                        proxyAttributionFlags, proxiedAttributionFlags, attributionChainId);

                switch (opMode) {
                    case AppOpsManager.MODE_ERRORED: {
                        return PermissionChecker.PERMISSION_HARD_DENIED;
                    }
                    case AppOpsManager.MODE_IGNORED: {
                        return PermissionChecker.PERMISSION_SOFT_DENIED;
                    }
                }

                if (startDataDelivery) {
                    RegisteredAttribution registered = new RegisteredAttribution(context, op,
                            current, fromDatasource);
                    sRunningAttributionSources.put(current.getToken(), registered);
                }

                if (next == null || next.getNext() == null) {
                    return PermissionChecker.PERMISSION_GRANTED;
                }

                current = next;
            }
        }

        private static boolean checkPermission(@NonNull Context context,
                @NonNull PermissionManagerServiceInternal permissionManagerServiceInt,
                @NonNull String permission, int uid, @NonNull Set<String> renouncedPermissions) {
            boolean permissionGranted = context.checkPermission(permission, /*pid*/ -1,
                    uid) == PackageManager.PERMISSION_GRANTED;

            // Override certain permissions checks for the shared isolated process for both
            // HotwordDetectionService and VisualQueryDetectionService, which ordinarily cannot hold
            // any permissions.
            // There's probably a cleaner, more generalizable way to do this. For now, this is
            // the only use case for this, so simply override here.
            if (!permissionGranted
                    && Process.isIsolated(uid) // simple check which fails-fast for the common case
                    && (permission.equals(RECORD_AUDIO) || permission.equals(CAPTURE_AUDIO_HOTWORD)
                    || permission.equals(CAPTURE_AUDIO_OUTPUT) || permission.equals(CAMERA))) {
                HotwordDetectionServiceProvider hotwordServiceProvider =
                        permissionManagerServiceInt.getHotwordDetectionServiceProvider();
                permissionGranted = hotwordServiceProvider != null
                        && uid == hotwordServiceProvider.getUid();
            }

            if (permissionGranted && renouncedPermissions.contains(permission)
                    && context.checkPermission(Manifest.permission.RENOUNCE_PERMISSIONS,
                    /*pid*/ -1, uid) == PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            return permissionGranted;
        }

        private static @AttributionFlags int resolveProxyAttributionFlags(
                @NonNull AttributionSource attributionChain,
                @NonNull AttributionSource current, boolean fromDatasource,
                boolean startDataDelivery, boolean selfAccess, boolean isTrusted) {
            return resolveAttributionFlags(attributionChain, current, fromDatasource,
                    startDataDelivery, selfAccess, isTrusted, /*flagsForProxy*/ true);
        }

        private static @AttributionFlags int resolveProxiedAttributionFlags(
                @NonNull AttributionSource attributionChain,
                @NonNull AttributionSource current, boolean fromDatasource,
                boolean startDataDelivery, boolean selfAccess, boolean isTrusted) {
            return resolveAttributionFlags(attributionChain, current, fromDatasource,
                    startDataDelivery, selfAccess, isTrusted, /*flagsForProxy*/ false);
        }

        private static @AttributionFlags int resolveAttributionFlags(
                @NonNull AttributionSource attributionChain,
                @NonNull AttributionSource current, boolean fromDatasource,
                boolean startDataDelivery, boolean selfAccess, boolean isTrusted,
                boolean flagsForProxy) {
            if (current == null || !startDataDelivery) {
                return AppOpsManager.ATTRIBUTION_FLAGS_NONE;
            }
            int trustedFlag = isTrusted
                    ? AppOpsManager.ATTRIBUTION_FLAG_TRUSTED : AppOpsManager.ATTRIBUTION_FLAGS_NONE;
            if (flagsForProxy) {
                if (selfAccess) {
                    return trustedFlag | AppOpsManager.ATTRIBUTION_FLAG_ACCESSOR;
                } else if (!fromDatasource && current.equals(attributionChain)) {
                    return trustedFlag | AppOpsManager.ATTRIBUTION_FLAG_ACCESSOR;
                }
            } else {
                if (selfAccess) {
                    return trustedFlag | AppOpsManager.ATTRIBUTION_FLAG_RECEIVER;
                } else if (fromDatasource && current.equals(attributionChain.getNext())) {
                    return trustedFlag | AppOpsManager.ATTRIBUTION_FLAG_ACCESSOR;
                } else if (current.getNext() == null) {
                    return trustedFlag | AppOpsManager.ATTRIBUTION_FLAG_RECEIVER;
                }
            }
            if (fromDatasource && current.equals(attributionChain)) {
                return AppOpsManager.ATTRIBUTION_FLAGS_NONE;
            }
            return trustedFlag | AppOpsManager.ATTRIBUTION_FLAG_INTERMEDIARY;
        }

        private static int checkOp(@NonNull Context context, @NonNull int op,
                @NonNull PermissionManagerServiceInternal permissionManagerServiceInt,
                @NonNull AttributionSource attributionSource, @Nullable String message,
                boolean forDataDelivery, boolean startDataDelivery) {
            if (op < 0 || attributionSource.getPackageName() == null) {
                return PermissionChecker.PERMISSION_HARD_DENIED;
            }

            final int attributionChainId =
                    getAttributionChainId(startDataDelivery, attributionSource);
            final boolean hasChain = attributionChainId != ATTRIBUTION_CHAIN_ID_NONE;

            AttributionSource current = attributionSource;
            AttributionSource next = null;

            // We consider the chain trusted if the start node has UPDATE_APP_OPS_STATS, and
            // every attributionSource in the chain is registered with the system.
            final boolean isChainStartTrusted = !hasChain || checkPermission(context,
                    permissionManagerServiceInt, UPDATE_APP_OPS_STATS, current.getUid(),
                    current.getRenouncedPermissions());

            while (true) {
                final boolean skipCurrentChecks = (next != null);
                next = current.getNext();

                // If the call is from a datasource we need to vet only the chain before it. This
                // way we can avoid the datasource creating an attribution context for every call.
                if (next != null && !current.isTrusted(context)) {
                    return PermissionChecker.PERMISSION_HARD_DENIED;
                }

                // The access is for oneself if this is the single attribution source in the chain.
                final boolean selfAccess = (next == null);
                final boolean isLinkTrusted = isChainStartTrusted
                        && (current.isTrusted(context) || current.equals(attributionSource))
                        && (next == null || next.isTrusted(context));

                final int proxyAttributionFlags = (!skipCurrentChecks && hasChain)
                        ? resolveProxyAttributionFlags(attributionSource, current,
                                /*fromDatasource*/ false, startDataDelivery, selfAccess,
                        isLinkTrusted) : ATTRIBUTION_FLAGS_NONE;
                final int proxiedAttributionFlags = hasChain ? resolveProxiedAttributionFlags(
                        attributionSource, next, /*fromDatasource*/ false, startDataDelivery,
                        selfAccess, isLinkTrusted) : ATTRIBUTION_FLAGS_NONE;

                final int opMode = performOpTransaction(context, current.getToken(), op, current,
                        message, forDataDelivery, startDataDelivery, skipCurrentChecks, selfAccess,
                        /*fromDatasource*/ false, AppOpsManager.OP_NONE, proxyAttributionFlags,
                        proxiedAttributionFlags, attributionChainId);

                switch (opMode) {
                    case AppOpsManager.MODE_ERRORED: {
                        return PermissionChecker.PERMISSION_HARD_DENIED;
                    }
                    case AppOpsManager.MODE_IGNORED: {
                        return PermissionChecker.PERMISSION_SOFT_DENIED;
                    }
                }

                if (next == null || next.getNext() == null) {
                    return PermissionChecker.PERMISSION_GRANTED;
                }

                current = next;
            }
        }

        @SuppressWarnings("ConstantConditions")
        private static int performOpTransaction(@NonNull Context context,
                @NonNull IBinder chainStartToken, int op,
                @NonNull AttributionSource attributionSource, @Nullable String message,
                boolean forDataDelivery, boolean startDataDelivery, boolean skipProxyOperation,
                boolean selfAccess, boolean singleReceiverFromDatasource, int attributedOp,
                @AttributionFlags int proxyAttributionFlags,
                @AttributionFlags int proxiedAttributionFlags, int attributionChainId) {
            // We cannot perform app ops transactions without a package name. In all relevant
            // places we pass the package name but just in case there is a bug somewhere we
            // do a best effort to resolve the package from the UID (pick first without a loss
            // of generality - they are in the same security sandbox).
            final AppOpsManager appOpsManager = context.getSystemService(AppOpsManager.class);
            final AttributionSource accessorSource = (!singleReceiverFromDatasource)
                    ? attributionSource : attributionSource.getNext();
            if (!forDataDelivery) {
                final String resolvedAccessorPackageName = resolvePackageName(context,
                        accessorSource);
                if (resolvedAccessorPackageName == null) {
                    return AppOpsManager.MODE_ERRORED;
                }
                final int opMode = appOpsManager.unsafeCheckOpRawNoThrow(op,
                        accessorSource.getUid(), resolvedAccessorPackageName);
                final AttributionSource next = accessorSource.getNext();
                if (!selfAccess && opMode == AppOpsManager.MODE_ALLOWED && next != null) {
                    final String resolvedNextPackageName = resolvePackageName(context, next);
                    if (resolvedNextPackageName == null) {
                        return AppOpsManager.MODE_ERRORED;
                    }
                    return appOpsManager.unsafeCheckOpRawNoThrow(op, next.getUid(),
                            resolvedNextPackageName);
                }
                return opMode;
            } else if (startDataDelivery) {
                final AttributionSource resolvedAttributionSource = resolveAttributionSource(
                        context, accessorSource);
                if (resolvedAttributionSource.getPackageName() == null) {
                    return AppOpsManager.MODE_ERRORED;
                }
                // If the datasource is not in a trusted platform component then in would not
                // have UPDATE_APP_OPS_STATS and the call below would fail. The problem is that
                // an app is exposing runtime permission protected data but cannot blame others
                // in a trusted way which would not properly show in permission usage UIs.
                // As a fallback we note a proxy op that blames the app and the datasource.
                int startedOp = op;
                int checkedOpResult = MODE_ALLOWED;
                int startedOpResult;

                // If the datasource wants to attribute to another app op we need to
                // make sure the op for the permission and the attributed ops allow
                // the operation. We return the less permissive of the two and check
                // the permission op while start the attributed op.
                if (attributedOp != AppOpsManager.OP_NONE && attributedOp != op) {
                    checkedOpResult = appOpsManager.checkOpNoThrow(op,
                            resolvedAttributionSource.getUid(), resolvedAttributionSource
                                    .getPackageName());
                    if (checkedOpResult == MODE_ERRORED) {
                        return checkedOpResult;
                    }
                    startedOp = attributedOp;
                }
                if (selfAccess) {
                    try {
                        startedOpResult = appOpsManager.startOpNoThrow(
                                chainStartToken, startedOp,
                                resolvedAttributionSource.getUid(),
                                resolvedAttributionSource.getPackageName(),
                                /*startIfModeDefault*/ false,
                                resolvedAttributionSource.getAttributionTag(),
                                message, proxyAttributionFlags, attributionChainId);
                    } catch (SecurityException e) {
                        Slog.w(LOG_TAG, "Datasource " + attributionSource + " protecting data with"
                                + " platform defined runtime permission "
                                + AppOpsManager.opToPermission(op) + " while not having "
                                + Manifest.permission.UPDATE_APP_OPS_STATS);
                        startedOpResult = appOpsManager.startProxyOpNoThrow(chainStartToken,
                                attributedOp, attributionSource, message, skipProxyOperation,
                                proxyAttributionFlags, proxiedAttributionFlags, attributionChainId);
                    }
                } else {
                    try {
                        startedOpResult = appOpsManager.startProxyOpNoThrow(chainStartToken,
                                startedOp, resolvedAttributionSource, message, skipProxyOperation,
                                proxyAttributionFlags, proxiedAttributionFlags, attributionChainId);
                    } catch (SecurityException e) {
                        //TODO 195339480: remove
                        String msg = "Security exception for op " + startedOp + " with source "
                                + attributionSource.getUid() + ":"
                                + attributionSource.getPackageName() + ", "
                                + attributionSource.getNextUid() + ":"
                                + attributionSource.getNextPackageName();
                        if (attributionSource.getNext() != null) {
                            AttributionSource next = attributionSource.getNext();
                            msg = msg + ", " + next.getNextPackageName() + ":" + next.getNextUid();
                        }
                        throw new SecurityException(msg + ":" + e.getMessage());
                    }
                }
                return Math.max(checkedOpResult, startedOpResult);
            } else {
                final AttributionSource resolvedAttributionSource = resolveAttributionSource(
                        context, accessorSource);
                if (resolvedAttributionSource.getPackageName() == null) {
                    return AppOpsManager.MODE_ERRORED;
                }
                int notedOp = op;
                int checkedOpResult = MODE_ALLOWED;
                int notedOpResult;

                // If the datasource wants to attribute to another app op we need to
                // make sure the op for the permission and the attributed ops allow
                // the operation. We return the less permissive of the two and check
                // the permission op while start the attributed op.
                if (attributedOp != AppOpsManager.OP_NONE && attributedOp != op) {
                    checkedOpResult = appOpsManager.checkOpNoThrow(op,
                            resolvedAttributionSource.getUid(), resolvedAttributionSource
                                    .getPackageName());
                    if (checkedOpResult == MODE_ERRORED) {
                        return checkedOpResult;
                    }
                    notedOp = attributedOp;
                }
                if (selfAccess) {
                    // If the datasource is not in a trusted platform component then in would not
                    // have UPDATE_APP_OPS_STATS and the call below would fail. The problem is that
                    // an app is exposing runtime permission protected data but cannot blame others
                    // in a trusted way which would not properly show in permission usage UIs.
                    // As a fallback we note a proxy op that blames the app and the datasource.
                    try {
                        notedOpResult = appOpsManager.noteOpNoThrow(notedOp,
                                resolvedAttributionSource.getUid(),
                                resolvedAttributionSource.getPackageName(),
                                resolvedAttributionSource.getAttributionTag(),
                                message);
                    } catch (SecurityException e) {
                        Slog.w(LOG_TAG, "Datasource " + attributionSource + " protecting data with"
                                + " platform defined runtime permission "
                                + AppOpsManager.opToPermission(op) + " while not having "
                                + Manifest.permission.UPDATE_APP_OPS_STATS);
                        notedOpResult = appOpsManager.noteProxyOpNoThrow(notedOp, attributionSource,
                                message, skipProxyOperation);
                    }
                } else {
                    try {
                        notedOpResult = appOpsManager.noteProxyOpNoThrow(notedOp,
                                resolvedAttributionSource, message, skipProxyOperation);
                    } catch (SecurityException e) {
                        //TODO 195339480: remove
                        String msg = "Security exception for op " + notedOp + " with source "
                                + attributionSource.getUid() + ":"
                                + attributionSource.getPackageName() + ", "
                                + attributionSource.getNextUid() + ":"
                                + attributionSource.getNextPackageName();
                        if (attributionSource.getNext() != null) {
                            AttributionSource next = attributionSource.getNext();
                            msg = msg + ", " + next.getNextPackageName() + ":" + next.getNextUid();
                        }
                        throw new SecurityException(msg + ":" + e.getMessage());
                    }
                }
                return Math.max(checkedOpResult, notedOpResult);
            }
        }

        private static int getAttributionChainId(boolean startDataDelivery,
                AttributionSource source) {
            if (source == null || source.getNext() == null || !startDataDelivery) {
                return AppOpsManager.ATTRIBUTION_CHAIN_ID_NONE;
            }
            int attributionChainId = sAttributionChainIds.incrementAndGet();

            // handle overflow
            if (attributionChainId < 0) {
                attributionChainId = 0;
                sAttributionChainIds.set(0);
            }
            return attributionChainId;
        }

        private static @Nullable String resolvePackageName(@NonNull Context context,
                @NonNull AttributionSource attributionSource) {
            if (attributionSource.getPackageName() != null) {
                return attributionSource.getPackageName();
            }
            final String[] packageNames = context.getPackageManager().getPackagesForUid(
                    attributionSource.getUid());
            if (packageNames != null) {
                // This is best effort if the caller doesn't pass a package. The security
                // sandbox is UID, therefore we pick an arbitrary package.
                return packageNames[0];
            }
            // Last resort to handle special UIDs like root, etc.
            return AppOpsManager.resolvePackageName(attributionSource.getUid(),
                    attributionSource.getPackageName());
        }

        private static @NonNull AttributionSource resolveAttributionSource(
                @NonNull Context context, @NonNull AttributionSource attributionSource) {
            if (attributionSource.getPackageName() != null) {
                return attributionSource;
            }
            return attributionSource.withPackageName(resolvePackageName(context,
                    attributionSource));
        }
    }

    private static final class RegisteredAttribution {
        private final DeathRecipient mDeathRecipient;
        private final IBinder mToken;
        private final AtomicBoolean mFinished;

        RegisteredAttribution(Context context, int op, AttributionSource source,
                boolean fromDatasource) {
            mFinished = new AtomicBoolean(false);
            mDeathRecipient = () -> {
                if (unregister()) {
                    PermissionCheckerService
                            .finishDataDelivery(context, op, source.asState(), fromDatasource);
                }
            };
            mToken = source.getToken();
            if (mToken != null) {
                try {
                    mToken.linkToDeath(mDeathRecipient, 0);
                } catch (RemoteException e) {
                    mDeathRecipient.binderDied();
                }
            }
        }

        public boolean unregister() {
            if (mFinished.compareAndSet(false, true)) {
                try {
                    if (mToken != null) {
                        mToken.unlinkToDeath(mDeathRecipient, 0);
                    }
                } catch (NoSuchElementException e) {
                    // do nothing
                }
                return true;
            }
            return false;
        }
    }
}
