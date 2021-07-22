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

import static android.Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY;
import static android.Manifest.permission.CAPTURE_AUDIO_HOTWORD;
import static android.Manifest.permission.READ_EXTERNAL_STORAGE;
import static android.Manifest.permission.RECORD_AUDIO;
import static android.Manifest.permission.UPDATE_APP_OPS_STATS;
import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
import static android.app.AppOpsManager.ATTRIBUTION_CHAIN_ID_NONE;
import static android.app.AppOpsManager.ATTRIBUTION_FLAGS_NONE;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_ERRORED;
import static android.app.AppOpsManager.MODE_IGNORED;
import static android.content.pm.ApplicationInfo.AUTO_REVOKE_DISALLOWED;
import static android.content.pm.ApplicationInfo.AUTO_REVOKE_DISCOURAGED;
import static android.content.pm.PackageManager.FLAGS_PERMISSION_RESTRICTION_ANY_EXEMPT;
import static android.content.pm.PackageManager.FLAG_PERMISSION_APPLY_RESTRICTION;
import static android.content.pm.PackageManager.FLAG_PERMISSION_GRANTED_BY_DEFAULT;
import static android.content.pm.PackageManager.FLAG_PERMISSION_GRANTED_BY_ROLE;
import static android.content.pm.PackageManager.FLAG_PERMISSION_ONE_TIME;
import static android.content.pm.PackageManager.FLAG_PERMISSION_POLICY_FIXED;
import static android.content.pm.PackageManager.FLAG_PERMISSION_RESTRICTION_INSTALLER_EXEMPT;
import static android.content.pm.PackageManager.FLAG_PERMISSION_RESTRICTION_SYSTEM_EXEMPT;
import static android.content.pm.PackageManager.FLAG_PERMISSION_RESTRICTION_UPGRADE_EXEMPT;
import static android.content.pm.PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED;
import static android.content.pm.PackageManager.FLAG_PERMISSION_REVOKED_COMPAT;
import static android.content.pm.PackageManager.FLAG_PERMISSION_REVOKE_WHEN_REQUESTED;
import static android.content.pm.PackageManager.FLAG_PERMISSION_SELECTED_LOCATION_ACCURACY;
import static android.content.pm.PackageManager.FLAG_PERMISSION_SYSTEM_FIXED;
import static android.content.pm.PackageManager.FLAG_PERMISSION_USER_FIXED;
import static android.content.pm.PackageManager.FLAG_PERMISSION_USER_SET;
import static android.content.pm.PackageManager.FLAG_PERMISSION_WHITELIST_INSTALLER;
import static android.content.pm.PackageManager.FLAG_PERMISSION_WHITELIST_SYSTEM;
import static android.content.pm.PackageManager.FLAG_PERMISSION_WHITELIST_UPGRADE;
import static android.content.pm.PackageManager.MASK_PERMISSION_FLAGS_ALL;
import static android.content.pm.PackageManager.MATCH_DEBUG_TRIAGED_MISSING;
import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;
import static android.permission.PermissionManager.KILL_APP_REASON_GIDS_CHANGED;
import static android.permission.PermissionManager.KILL_APP_REASON_PERMISSIONS_REVOKED;

import static com.android.server.pm.ApexManager.MATCH_ACTIVE_PACKAGE;
import static com.android.server.pm.PackageManagerService.DEBUG_INSTALL;
import static com.android.server.pm.PackageManagerService.DEBUG_PACKAGE_SCANNING;
import static com.android.server.pm.PackageManagerService.DEBUG_PERMISSIONS;
import static com.android.server.pm.PackageManagerService.DEBUG_REMOVE;
import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;

import static java.util.concurrent.TimeUnit.SECONDS;

import android.Manifest;
import android.annotation.AppIdInt;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.AppOpsManager.AttributionFlags;
import android.app.IActivityManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.content.AttributionSource;
import android.content.AttributionSourceState;
import android.content.Context;
import android.content.PermissionChecker;
import android.content.pm.ApplicationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.PermissionGroupInfoFlags;
import android.content.pm.PackageManager.PermissionInfoFlags;
import android.content.pm.PackageManager.PermissionWhitelistFlags;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.SigningDetails;
import android.content.pm.parsing.component.ParsedPermission;
import android.content.pm.parsing.component.ParsedPermissionGroup;
import android.content.pm.permission.CompatibilityPermissionInfo;
import android.content.pm.permission.SplitPermissionInfoParcelable;
import android.metrics.LogMaker;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.permission.IOnPermissionsChangeListener;
import android.permission.IPermissionChecker;
import android.permission.IPermissionManager;
import android.permission.PermissionCheckerManager;
import android.permission.PermissionControllerManager;
import android.permission.PermissionManager;
import android.permission.PermissionManagerInternal;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DebugUtils;
import android.util.EventLog;
import android.util.IntArray;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.compat.IPlatformCompat;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.os.RoSystemProperties;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IntPair;
import com.android.internal.util.Preconditions;
import com.android.internal.util.function.TriFunction;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemConfig;
import com.android.server.Watchdog;
import com.android.server.pm.ApexManager;
import com.android.server.pm.PackageSetting;
import com.android.server.pm.UserManagerInternal;
import com.android.server.pm.UserManagerService;
import com.android.server.pm.parsing.PackageInfoUtils;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.permission.PermissionManagerServiceInternal.HotwordDetectionServiceProvider;
import com.android.server.pm.permission.PermissionManagerServiceInternal.OnRuntimePermissionStateChangedListener;
import com.android.server.policy.PermissionPolicyInternal;
import com.android.server.policy.SoftRestrictedPermissionPolicy;

import libcore.util.EmptyArray;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/**
 * Manages all permissions and handles permissions related tasks.
 */
public class PermissionManagerService extends IPermissionManager.Stub {
    private static final String TAG = "PackageManager";
    private static final String LOG_TAG = PermissionManagerService.class.getSimpleName();

    private static final long BACKUP_TIMEOUT_MILLIS = SECONDS.toMillis(60);

    // For automotive products, CarService enforces allow-listing of the privileged permissions
    // com.android.car is the package name which declares auto specific permissions
    private static final String CAR_PACKAGE_NAME = "com.android.car";

    /** Cap the size of permission trees that 3rd party apps can define; in characters of text */
    private static final int MAX_PERMISSION_TREE_FOOTPRINT = 32768;
    /** Empty array to avoid allocations */
    private static final int[] EMPTY_INT_ARRAY = new int[0];

    /**
     * When these flags are set, the system should not automatically modify the permission grant
     * state.
     */
    private static final int BLOCKING_PERMISSION_FLAGS = FLAG_PERMISSION_SYSTEM_FIXED
            | FLAG_PERMISSION_POLICY_FIXED
            | FLAG_PERMISSION_GRANTED_BY_DEFAULT;

    /** Permission flags set by the user */
    private static final int USER_PERMISSION_FLAGS = FLAG_PERMISSION_USER_SET
            | FLAG_PERMISSION_USER_FIXED;

    /** All storage permissions */
    private static final List<String> STORAGE_PERMISSIONS = new ArrayList<>();

    /** If the permission of the value is granted, so is the key */
    private static final Map<String, String> FULLER_PERMISSION_MAP = new HashMap<>();

    /** Map of IBinder -> Running AttributionSource */
    private static final ConcurrentHashMap<IBinder, RegisteredAttribution>
            sRunningAttributionSources = new ConcurrentHashMap<>();

    static {
        FULLER_PERMISSION_MAP.put(Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION);
        FULLER_PERMISSION_MAP.put(Manifest.permission.INTERACT_ACROSS_USERS,
                Manifest.permission.INTERACT_ACROSS_USERS_FULL);
        STORAGE_PERMISSIONS.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        STORAGE_PERMISSIONS.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        STORAGE_PERMISSIONS.add(Manifest.permission.ACCESS_MEDIA_LOCATION);
    }

    /** Set of source package names for Privileged Permission Allowlist */
    private final ArraySet<String> mPrivilegedPermissionAllowlistSourcePackageNames =
            new ArraySet<>();

    /** Lock to protect internal data access */
    private final Object mLock = new Object();

    /** Internal connection to the package manager */
    private final PackageManagerInternal mPackageManagerInt;

    /** Internal connection to the user manager */
    private final UserManagerInternal mUserManagerInt;

    @GuardedBy("mLock")
    @NonNull
    private final DevicePermissionState mState = new DevicePermissionState();

    /** Permission controller: User space permission management */
    private PermissionControllerManager mPermissionControllerManager;

    /** Map of OneTimePermissionUserManagers keyed by userId */
    @GuardedBy("mLock")
    @NonNull
    private final SparseArray<OneTimePermissionUserManager> mOneTimePermissionUserManagers =
            new SparseArray<>();

    /** App ops manager */
    private final AppOpsManager mAppOpsManager;

    /**
     * Built-in permissions. Read from system configuration files. Mapping is from
     * UID to permission name.
     */
    private final SparseArray<ArraySet<String>> mSystemPermissions;

    /** Built-in group IDs given to all packages. Read from system configuration files. */
    @NonNull
    private final int[] mGlobalGids;

    private final HandlerThread mHandlerThread;
    private final Handler mHandler;
    private final Context mContext;
    private final MetricsLogger mMetricsLogger = new MetricsLogger();
    private final IPlatformCompat mPlatformCompat = IPlatformCompat.Stub.asInterface(
            ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE));

    /** Internal storage for permissions and related settings */
    @GuardedBy("mLock")
    @NonNull
    private final PermissionRegistry mRegistry = new PermissionRegistry();

    @NonNull
    private final AttributionSourceRegistry mAttributionSourceRegistry;

    @GuardedBy("mLock")
    @Nullable
    private ArraySet<String> mPrivappPermissionsViolations;

    @GuardedBy("mLock")
    private boolean mSystemReady;

    @GuardedBy("mLock")
    private PermissionPolicyInternal mPermissionPolicyInternal;

    /**
     * A permission backup might contain apps that are not installed. In this case we delay the
     * restoration until the app is installed.
     *
     * <p>This array ({@code userId -> noDelayedBackupLeft}) is {@code true} for all the users where
     * there is <u>no more</u> delayed backup left.
     */
    @GuardedBy("mLock")
    private final SparseBooleanArray mHasNoDelayedPermBackup = new SparseBooleanArray();

    /** Listeners for permission state (granting and flags) changes */
    @GuardedBy("mLock")
    final private ArrayList<OnRuntimePermissionStateChangedListener>
            mRuntimePermissionStateChangedListeners = new ArrayList<>();

    @GuardedBy("mLock")
    private CheckPermissionDelegate mCheckPermissionDelegate;

    @NonNull
    private final OnPermissionChangeListeners mOnPermissionChangeListeners;

    @Nullable
    private HotwordDetectionServiceProvider mHotwordDetectionServiceProvider;

    // TODO: Take a look at the methods defined in the callback.
    // The callback was initially created to support the split between permission
    // manager and the package manager. However, it's started to be used for other
    // purposes. It may make sense to keep as an abstraction, but, the methods
    // necessary to be overridden may be different than what was initially needed
    // for the split.
    private final PermissionCallback mDefaultPermissionCallback = new PermissionCallback() {
        @Override
        public void onGidsChanged(int appId, int userId) {
            mHandler.post(() -> killUid(appId, userId, KILL_APP_REASON_GIDS_CHANGED));
        }
        @Override
        public void onPermissionGranted(int uid, int userId) {
            mOnPermissionChangeListeners.onPermissionsChanged(uid);

            // Not critical; if this is lost, the application has to request again.
            mPackageManagerInt.writeSettings(true);
        }
        @Override
        public void onInstallPermissionGranted() {
            mPackageManagerInt.writeSettings(true);
        }
        @Override
        public void onPermissionRevoked(int uid, int userId, String reason) {
            mOnPermissionChangeListeners.onPermissionsChanged(uid);

            // Critical; after this call the application should never have the permission
            mPackageManagerInt.writeSettings(false);
            final int appId = UserHandle.getAppId(uid);
            if (reason == null) {
                mHandler.post(() -> killUid(appId, userId, KILL_APP_REASON_PERMISSIONS_REVOKED));
            } else {
                mHandler.post(() -> killUid(appId, userId, reason));
            }
        }
        @Override
        public void onInstallPermissionRevoked() {
            mPackageManagerInt.writeSettings(true);
        }
        @Override
        public void onPermissionUpdated(int[] userIds, boolean sync) {
            mPackageManagerInt.writePermissionSettings(userIds, !sync);
        }
        @Override
        public void onInstallPermissionUpdated() {
            mPackageManagerInt.writeSettings(true);
        }
        @Override
        public void onPermissionRemoved() {
            mPackageManagerInt.writeSettings(false);
        }
        public void onPermissionUpdatedNotifyListener(@UserIdInt int[] updatedUserIds, boolean sync,
                int uid) {
            onPermissionUpdated(updatedUserIds, sync);
            for (int i = 0; i < updatedUserIds.length; i++) {
                int userUid = UserHandle.getUid(updatedUserIds[i], UserHandle.getAppId(uid));
                mOnPermissionChangeListeners.onPermissionsChanged(userUid);
            }
        }
        public void onInstallPermissionUpdatedNotifyListener(int uid) {
            onInstallPermissionUpdated();
            mOnPermissionChangeListeners.onPermissionsChanged(uid);
        }
    };

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

        mPrivilegedPermissionAllowlistSourcePackageNames.add(PLATFORM_PACKAGE_NAME);
        // PackageManager.hasSystemFeature() is not used here because PackageManagerService
        // isn't ready yet.
        if (availableFeatures.containsKey(PackageManager.FEATURE_AUTOMOTIVE)) {
            mPrivilegedPermissionAllowlistSourcePackageNames.add(CAR_PACKAGE_NAME);
        }

        mHandlerThread = new ServiceThread(TAG,
                Process.THREAD_PRIORITY_BACKGROUND, true /*allowIo*/);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        Watchdog.getInstance().addThread(mHandler);

        SystemConfig systemConfig = SystemConfig.getInstance();
        mSystemPermissions = systemConfig.getSystemPermissions();
        mGlobalGids = systemConfig.getGlobalGids();
        mOnPermissionChangeListeners = new OnPermissionChangeListeners(FgThread.get().getLooper());
        mAttributionSourceRegistry = new AttributionSourceRegistry(context);

        // propagate permission configuration
        final ArrayMap<String, SystemConfig.PermissionEntry> permConfig =
                SystemConfig.getInstance().getPermissions();
        synchronized (mLock) {
            for (int i=0; i<permConfig.size(); i++) {
                final SystemConfig.PermissionEntry perm = permConfig.valueAt(i);
                Permission bp = mRegistry.getPermission(perm.name);
                if (bp == null) {
                    bp = new Permission(perm.name, "android", Permission.TYPE_CONFIG);
                    mRegistry.addPermission(bp);
                }
                if (perm.gids != null) {
                    bp.setGids(perm.gids, perm.perUser);
                }
            }
        }

        PermissionManagerServiceInternalImpl localService =
                new PermissionManagerServiceInternalImpl();
        LocalServices.addService(PermissionManagerServiceInternal.class, localService);
        LocalServices.addService(PermissionManagerInternal.class, localService);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) {
            return;
        }

        mContext.getSystemService(PermissionControllerManager.class).dump(fd, args);
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

    @NonNull
    private String[] getAppOpPermissionPackagesInternal(@NonNull String permissionName) {
        synchronized (mLock) {
            final ArraySet<String> packageNames = mRegistry.getAppOpPermissionPackages(
                    permissionName);
            if (packageNames == null) {
                return EmptyArray.STRING;
            }
            return packageNames.toArray(new String[0]);
        }
    }

    @Override
    @NonNull
    public ParceledListSlice<PermissionGroupInfo> getAllPermissionGroups(
            @PermissionGroupInfoFlags int flags) {
        final int callingUid = getCallingUid();
        if (mPackageManagerInt.getInstantAppPackageName(callingUid) != null) {
            return ParceledListSlice.emptyList();
        }

        final List<PermissionGroupInfo> out = new ArrayList<>();
        synchronized (mLock) {
            for (ParsedPermissionGroup pg : mRegistry.getPermissionGroups()) {
                out.add(PackageInfoUtils.generatePermissionGroupInfo(pg, flags));
            }
        }

        final int callingUserId = UserHandle.getUserId(callingUid);
        out.removeIf(it -> mPackageManagerInt.filterAppAccess(it.packageName, callingUid,
                callingUserId));
        return new ParceledListSlice<>(out);
    }


    @Override
    @Nullable
    public PermissionGroupInfo getPermissionGroupInfo(String groupName,
            @PermissionGroupInfoFlags int flags) {
        final int callingUid = getCallingUid();
        if (mPackageManagerInt.getInstantAppPackageName(callingUid) != null) {
            return null;
        }

        final PermissionGroupInfo permissionGroupInfo;
        synchronized (mLock) {
            final ParsedPermissionGroup permissionGroup = mRegistry.getPermissionGroup(groupName);
            if (permissionGroup == null) {
                return null;
            }
            permissionGroupInfo = PackageInfoUtils.generatePermissionGroupInfo(permissionGroup,
                    flags);
        }

        final int callingUserId = UserHandle.getUserId(callingUid);
        if (mPackageManagerInt.filterAppAccess(permissionGroupInfo.packageName, callingUid,
                callingUserId)) {
            EventLog.writeEvent(0x534e4554, "186113473", callingUid, groupName);
            return null;
        }
        return permissionGroupInfo;
    }


    @Override
    @Nullable
    public PermissionInfo getPermissionInfo(@NonNull String permName, @NonNull String opPackageName,
            @PermissionInfoFlags int flags) {
        final int callingUid = getCallingUid();
        if (mPackageManagerInt.getInstantAppPackageName(callingUid) != null) {
            return null;
        }

        final AndroidPackage opPackage = mPackageManagerInt.getPackage(opPackageName);
        final int targetSdkVersion = getPermissionInfoCallingTargetSdkVersion(opPackage,
                callingUid);
        final PermissionInfo permissionInfo;
        synchronized (mLock) {
            final Permission bp = mRegistry.getPermission(permName);
            if (bp == null) {
                return null;
            }
            permissionInfo = bp.generatePermissionInfo(flags, targetSdkVersion);
        }

        final int callingUserId = UserHandle.getUserId(callingUid);
        if (mPackageManagerInt.filterAppAccess(permissionInfo.packageName, callingUid,
                callingUserId)) {
            EventLog.writeEvent(0x534e4554, "183122164", callingUid, permName);
            return null;
        }
        return permissionInfo;
    }

    private int getPermissionInfoCallingTargetSdkVersion(@Nullable AndroidPackage pkg, int uid) {
        final int appId = UserHandle.getAppId(uid);
        if (appId == Process.ROOT_UID || appId == Process.SYSTEM_UID
                || appId == Process.SHELL_UID) {
            // System sees all flags.
            return Build.VERSION_CODES.CUR_DEVELOPMENT;
        }
        if (pkg == null) {
            return Build.VERSION_CODES.CUR_DEVELOPMENT;
        }
        return pkg.getTargetSdkVersion();
    }

    @Override
    @Nullable
    public ParceledListSlice<PermissionInfo> queryPermissionsByGroup(String groupName,
            @PermissionInfoFlags int flags) {
        final int callingUid = getCallingUid();
        if (mPackageManagerInt.getInstantAppPackageName(callingUid) != null) {
            return null;
        }

        final List<PermissionInfo> out = new ArrayList<>(10);
        synchronized (mLock) {
            if (groupName != null && mRegistry.getPermissionGroup(groupName) == null) {
                return null;
            }
            for (Permission bp : mRegistry.getPermissions()) {
                if (Objects.equals(bp.getGroup(), groupName)) {
                    out.add(bp.generatePermissionInfo(flags));
                }
            }
        }

        final int callingUserId = UserHandle.getUserId(callingUid);
        out.removeIf(it -> mPackageManagerInt.filterAppAccess(it.packageName, callingUid,
                callingUserId));
        return new ParceledListSlice<>(out);
    }

    @Override
    public boolean addPermission(PermissionInfo info, boolean async) {
        final int callingUid = getCallingUid();
        if (mPackageManagerInt.getInstantAppPackageName(callingUid) != null) {
            throw new SecurityException("Instant apps can't add permissions");
        }
        if (info.labelRes == 0 && info.nonLocalizedLabel == null) {
            throw new SecurityException("Label must be specified in permission");
        }
        final boolean added;
        final boolean changed;
        synchronized (mLock) {
            final Permission tree = mRegistry.enforcePermissionTree(info.name, callingUid);
            Permission bp = mRegistry.getPermission(info.name);
            added = bp == null;
            int fixedLevel = PermissionInfo.fixProtectionLevel(info.protectionLevel);
            if (added) {
                enforcePermissionCapLocked(info, tree);
                bp = new Permission(info.name, tree.getPackageName(), Permission.TYPE_DYNAMIC);
            } else if (!bp.isDynamic()) {
                throw new SecurityException("Not allowed to modify non-dynamic permission "
                        + info.name);
            }
            changed = bp.addToTree(fixedLevel, info, tree);
            if (added) {
                mRegistry.addPermission(bp);
            }
        }
        if (changed) {
            mPackageManagerInt.writeSettings(async);
        }
        return added;
    }

    @Override
    public void removePermission(String permName) {
        final int callingUid = getCallingUid();
        if (mPackageManagerInt.getInstantAppPackageName(callingUid) != null) {
            throw new SecurityException("Instant applications don't have access to this method");
        }
        synchronized (mLock) {
            mRegistry.enforcePermissionTree(permName, callingUid);
            final Permission bp = mRegistry.getPermission(permName);
            if (bp == null) {
                return;
            }
            if (bp.isDynamic()) {
                // TODO: switch this back to SecurityException
                Slog.wtf(TAG, "Not allowed to modify non-dynamic permission "
                        + permName);
            }
            mRegistry.removePermission(permName);
        }
        mPackageManagerInt.writeSettings(false);
    }

    @Override
    public int getPermissionFlags(String packageName, String permName, int userId) {
        final int callingUid = getCallingUid();
        return getPermissionFlagsInternal(packageName, permName, callingUid, userId);
    }

    private int getPermissionFlagsInternal(
            String packageName, String permName, int callingUid, int userId) {
        if (!mUserManagerInt.exists(userId)) {
            return 0;
        }

        enforceGrantRevokeGetRuntimePermissionPermissions("getPermissionFlags");
        enforceCrossUserPermission(callingUid, userId,
                true,  // requireFullPermission
                false, // checkShell
                "getPermissionFlags");

        final AndroidPackage pkg = mPackageManagerInt.getPackage(packageName);
        if (pkg == null) {
            return 0;
        }
        if (mPackageManagerInt.filterAppAccess(pkg, callingUid, userId)) {
            return 0;
        }

        synchronized (mLock) {
            if (mRegistry.getPermission(permName) == null) {
                return 0;
            }

            final UidPermissionState uidState = getUidStateLocked(pkg, userId);
            if (uidState == null) {
                Slog.e(TAG, "Missing permissions state for " + packageName + " and user " + userId);
                return 0;
            }

            return uidState.getPermissionFlags(permName);
        }
    }

    @Override
    public void updatePermissionFlags(String packageName, String permName, int flagMask,
            int flagValues, boolean checkAdjustPolicyFlagPermission, int userId) {
        final int callingUid = getCallingUid();
        boolean overridePolicy = false;

        if (callingUid != Process.SYSTEM_UID && callingUid != Process.ROOT_UID) {
            final long callingIdentity = Binder.clearCallingIdentity();
            try {
                if ((flagMask & FLAG_PERMISSION_POLICY_FIXED) != 0) {
                    if (checkAdjustPolicyFlagPermission) {
                        mContext.enforceCallingOrSelfPermission(
                                Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY,
                                "Need " + Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY
                                        + " to change policy flags");
                    } else if (mPackageManagerInt.getUidTargetSdkVersion(callingUid)
                            >= Build.VERSION_CODES.Q) {
                        throw new IllegalArgumentException(
                                Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY + " needs "
                                        + " to be checked for packages targeting "
                                        + Build.VERSION_CODES.Q + " or later when changing policy "
                                        + "flags");
                    }
                    overridePolicy = true;
                }
            } finally {
                Binder.restoreCallingIdentity(callingIdentity);
            }
        }

        updatePermissionFlagsInternal(
                packageName, permName, flagMask, flagValues, callingUid, userId,
                overridePolicy, mDefaultPermissionCallback);
    }

    private void updatePermissionFlagsInternal(String packageName, String permName, int flagMask,
            int flagValues, int callingUid, int userId, boolean overridePolicy,
            PermissionCallback callback) {
        if (PermissionManager.DEBUG_TRACE_PERMISSION_UPDATES
                && PermissionManager.shouldTraceGrant(packageName, permName, userId)) {
            Log.i(TAG, "System is updating flags for " + packageName + " "
                            + permName + " for user " + userId  + " "
                            + DebugUtils.flagsToString(
                                    PackageManager.class, "FLAG_PERMISSION_", flagMask)
                            + " := "
                            + DebugUtils.flagsToString(
                                    PackageManager.class, "FLAG_PERMISSION_", flagValues)
                            + " on behalf of uid " + callingUid
                            + " " + mPackageManagerInt.getNameForUid(callingUid),
                    new RuntimeException());
        }

        if (!mUserManagerInt.exists(userId)) {
            return;
        }

        enforceGrantRevokeRuntimePermissionPermissions("updatePermissionFlags");

        enforceCrossUserPermission(callingUid, userId,
                true,  // requireFullPermission
                true,  // checkShell
                "updatePermissionFlags");

        if ((flagMask & FLAG_PERMISSION_POLICY_FIXED) != 0 && !overridePolicy) {
            throw new SecurityException("updatePermissionFlags requires "
                    + Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY);
        }

        // Only the system can change these flags and nothing else.
        if (callingUid != Process.SYSTEM_UID) {
            flagMask &= ~PackageManager.FLAG_PERMISSION_SYSTEM_FIXED;
            flagValues &= ~PackageManager.FLAG_PERMISSION_SYSTEM_FIXED;
            flagMask &= ~PackageManager.FLAG_PERMISSION_GRANTED_BY_DEFAULT;
            flagValues &= ~PackageManager.FLAG_PERMISSION_GRANTED_BY_DEFAULT;
            flagValues &= ~PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED;
            flagValues &= ~FLAG_PERMISSION_RESTRICTION_SYSTEM_EXEMPT;
            flagValues &= ~FLAG_PERMISSION_RESTRICTION_INSTALLER_EXEMPT;
            flagValues &= ~FLAG_PERMISSION_RESTRICTION_UPGRADE_EXEMPT;
            flagValues &= ~PackageManager.FLAG_PERMISSION_APPLY_RESTRICTION;
        }

        final AndroidPackage pkg = mPackageManagerInt.getPackage(packageName);
        if (pkg == null) {
            Log.e(TAG, "Unknown package: " + packageName);
            return;
        }
        if (mPackageManagerInt.filterAppAccess(pkg, callingUid, userId)) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }

        boolean isRequested = false;
        // Fast path, the current package has requested the permission.
        if (pkg.getRequestedPermissions().contains(permName)) {
            isRequested = true;
        }
        if (!isRequested) {
            // Slow path, go through all shared user packages.
            String[] sharedUserPackageNames =
                    mPackageManagerInt.getSharedUserPackagesForPackage(packageName, userId);
            for (String sharedUserPackageName : sharedUserPackageNames) {
                AndroidPackage sharedUserPkg = mPackageManagerInt.getPackage(
                        sharedUserPackageName);
                if (sharedUserPkg != null
                        && sharedUserPkg.getRequestedPermissions().contains(permName)) {
                    isRequested = true;
                    break;
                }
            }
        }

        final boolean isRuntimePermission;
        final boolean permissionUpdated;
        synchronized (mLock) {
            final Permission bp = mRegistry.getPermission(permName);
            if (bp == null) {
                throw new IllegalArgumentException("Unknown permission: " + permName);
            }

            isRuntimePermission = bp.isRuntime();

            final UidPermissionState uidState = getUidStateLocked(pkg, userId);
            if (uidState == null) {
                Slog.e(TAG, "Missing permissions state for " + packageName + " and user " + userId);
                return;
            }

            if (!uidState.hasPermissionState(permName) && !isRequested) {
                Log.e(TAG, "Permission " + permName + " isn't requested by package " + packageName);
                return;
            }

            permissionUpdated = uidState.updatePermissionFlags(bp, flagMask, flagValues);
        }

        if (permissionUpdated && isRuntimePermission) {
            notifyRuntimePermissionStateChanged(packageName, userId);
        }
        if (permissionUpdated && callback != null) {
            // Install and runtime permissions are stored in different places,
            // so figure out what permission changed and persist the change.
            if (!isRuntimePermission) {
                int userUid = UserHandle.getUid(userId, pkg.getUid());
                callback.onInstallPermissionUpdatedNotifyListener(userUid);
            } else {
                callback.onPermissionUpdatedNotifyListener(new int[]{userId}, false, pkg.getUid());
            }
        }
    }

    /**
     * Update the permission flags for all packages and runtime permissions of a user in order
     * to allow device or profile owner to remove POLICY_FIXED.
     */
    @Override
    public void updatePermissionFlagsForAllApps(int flagMask, int flagValues,
            final int userId) {
        final int callingUid = getCallingUid();
        if (!mUserManagerInt.exists(userId)) {
            return;
        }

        enforceGrantRevokeRuntimePermissionPermissions(
                "updatePermissionFlagsForAllApps");
        enforceCrossUserPermission(callingUid, userId,
                true,  // requireFullPermission
                true,  // checkShell
                "updatePermissionFlagsForAllApps");

        // Only the system can change system fixed flags.
        final int effectiveFlagMask = (callingUid != Process.SYSTEM_UID)
                ? flagMask : flagMask & ~PackageManager.FLAG_PERMISSION_SYSTEM_FIXED;
        final int effectiveFlagValues = (callingUid != Process.SYSTEM_UID)
                ? flagValues : flagValues & ~PackageManager.FLAG_PERMISSION_SYSTEM_FIXED;

        final boolean[] changed = new boolean[1];
        mPackageManagerInt.forEachPackage(pkg -> {
            synchronized (mLock) {
                final UidPermissionState uidState = getUidStateLocked(pkg, userId);
                if (uidState == null) {
                    Slog.e(TAG,
                            "Missing permissions state for " + pkg.getPackageName() + " and user "
                                    + userId);
                    return;
                }
                changed[0] |= uidState.updatePermissionFlagsForAllPermissions(
                        effectiveFlagMask, effectiveFlagValues);
            }
            mOnPermissionChangeListeners.onPermissionsChanged(pkg.getUid());
        });

        if (changed[0]) {
            mPackageManagerInt.writePermissionSettings(new int[] { userId }, true);
        }
    }

    private int checkPermission(String pkgName, String permName, @UserIdInt int userId) {
        // Not using Objects.requireNonNull() here for compatibility reasons.
        if (pkgName == null || permName == null) {
            return PackageManager.PERMISSION_DENIED;
        }
        if (!mUserManagerInt.exists(userId)) {
            return PackageManager.PERMISSION_DENIED;
        }

        final CheckPermissionDelegate checkPermissionDelegate;
        synchronized (mLock) {
            checkPermissionDelegate = mCheckPermissionDelegate;
        }
        if (checkPermissionDelegate == null) {
            return checkPermissionImpl(pkgName, permName, userId);
        }
        return checkPermissionDelegate.checkPermission(pkgName, permName, userId,
                this::checkPermissionImpl);
    }

    private int checkPermissionImpl(String pkgName, String permName, int userId) {
        final AndroidPackage pkg = mPackageManagerInt.getPackage(pkgName);
        if (pkg == null) {
            return PackageManager.PERMISSION_DENIED;
        }
        return checkPermissionInternal(pkg, true, permName, userId);
    }

    private int checkPermissionInternal(@NonNull AndroidPackage pkg, boolean isPackageExplicit,
            @NonNull String permissionName, @UserIdInt int userId) {
        final int callingUid = getCallingUid();
        if (isPackageExplicit || pkg.getSharedUserId() == null) {
            if (mPackageManagerInt.filterAppAccess(pkg, callingUid, userId)) {
                return PackageManager.PERMISSION_DENIED;
            }
        } else {
            if (mPackageManagerInt.getInstantAppPackageName(callingUid) != null) {
                return PackageManager.PERMISSION_DENIED;
            }
        }

        final int uid = UserHandle.getUid(userId, pkg.getUid());
        final boolean isInstantApp = mPackageManagerInt.getInstantAppPackageName(uid) != null;

        synchronized (mLock) {
            final UidPermissionState uidState = getUidStateLocked(pkg, userId);
            if (uidState == null) {
                Slog.e(TAG, "Missing permissions state for " + pkg.getPackageName() + " and user "
                        + userId);
                return PackageManager.PERMISSION_DENIED;
            }

            if (checkSinglePermissionInternalLocked(uidState, permissionName, isInstantApp)) {
                return PackageManager.PERMISSION_GRANTED;
            }

            final String fullerPermissionName = FULLER_PERMISSION_MAP.get(permissionName);
            if (fullerPermissionName != null && checkSinglePermissionInternalLocked(uidState,
                    fullerPermissionName, isInstantApp)) {
                return PackageManager.PERMISSION_GRANTED;
            }
        }

        return PackageManager.PERMISSION_DENIED;
    }

    @GuardedBy("mLock")
    private boolean checkSinglePermissionInternalLocked(@NonNull UidPermissionState uidState,
            @NonNull String permissionName, boolean isInstantApp) {
        if (!uidState.isPermissionGranted(permissionName)) {
            return false;
        }

        if (isInstantApp) {
            final Permission permission = mRegistry.getPermission(permissionName);
            return permission != null && permission.isInstant();
        }

        return true;
    }

    private int checkUidPermission(int uid, String permName) {
        // Not using Objects.requireNonNull() here for compatibility reasons.
        if (permName == null) {
            return PackageManager.PERMISSION_DENIED;
        }
        final int userId = UserHandle.getUserId(uid);
        if (!mUserManagerInt.exists(userId)) {
            return PackageManager.PERMISSION_DENIED;
        }

        final CheckPermissionDelegate checkPermissionDelegate;
        synchronized (mLock) {
            checkPermissionDelegate = mCheckPermissionDelegate;
        }
        if (checkPermissionDelegate == null)  {
            return checkUidPermissionImpl(uid, permName);
        }
        return checkPermissionDelegate.checkUidPermission(uid, permName,
                this::checkUidPermissionImpl);
    }

    private int checkUidPermissionImpl(int uid, String permName) {
        final AndroidPackage pkg = mPackageManagerInt.getPackage(uid);
        return checkUidPermissionInternal(pkg, uid, permName);
    }

    /**
     * Checks whether or not the given package has been granted the specified
     * permission. If the given package is {@code null}, we instead check the
     * system permissions for the given UID.
     *
     * @see SystemConfig#getSystemPermissions()
     */
    private int checkUidPermissionInternal(@Nullable AndroidPackage pkg, int uid,
            @NonNull String permissionName) {
        if (pkg != null) {
            final int userId = UserHandle.getUserId(uid);
            return checkPermissionInternal(pkg, false, permissionName, userId);
        }

        synchronized (mLock) {
            if (checkSingleUidPermissionInternalLocked(uid, permissionName)) {
                return PackageManager.PERMISSION_GRANTED;
            }

            final String fullerPermissionName = FULLER_PERMISSION_MAP.get(permissionName);
            if (fullerPermissionName != null
                    && checkSingleUidPermissionInternalLocked(uid, fullerPermissionName)) {
                return PackageManager.PERMISSION_GRANTED;
            }
        }

        return PackageManager.PERMISSION_DENIED;
    }

    @GuardedBy("mLock")
    private boolean checkSingleUidPermissionInternalLocked(int uid,
            @NonNull String permissionName) {
        ArraySet<String> permissions = mSystemPermissions.get(uid);
        return permissions != null && permissions.contains(permissionName);
    }

    @Override
    public void addOnPermissionsChangeListener(IOnPermissionsChangeListener listener) {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.OBSERVE_GRANT_REVOKE_PERMISSIONS,
                "addOnPermissionsChangeListener");

        mOnPermissionChangeListeners.addListener(listener);
    }

    @Override
    public void removeOnPermissionsChangeListener(IOnPermissionsChangeListener listener) {
        if (mPackageManagerInt.getInstantAppPackageName(Binder.getCallingUid()) != null) {
            throw new SecurityException("Instant applications don't have access to this method");
        }
        mOnPermissionChangeListeners.removeListener(listener);
    }

    @Nullable
    @Override
    public List<String> getAllowlistedRestrictedPermissions(@NonNull String packageName,
            @PermissionWhitelistFlags int flags, @UserIdInt int userId) {
        Objects.requireNonNull(packageName);
        Preconditions.checkFlagsArgument(flags,
                PackageManager.FLAG_PERMISSION_WHITELIST_UPGRADE
                        | PackageManager.FLAG_PERMISSION_WHITELIST_SYSTEM
                        | PackageManager.FLAG_PERMISSION_WHITELIST_INSTALLER);
        Preconditions.checkArgumentNonNegative(userId, null);

        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS,
                    "getAllowlistedRestrictedPermissions for user " + userId);
        }

        final AndroidPackage pkg = mPackageManagerInt.getPackage(packageName);
        if (pkg == null) {
            return null;
        }

        final int callingUid = Binder.getCallingUid();
        if (mPackageManagerInt.filterAppAccess(pkg, callingUid, UserHandle.getCallingUserId())) {
            return null;
        }
        final boolean isCallerPrivileged = mContext.checkCallingOrSelfPermission(
                Manifest.permission.WHITELIST_RESTRICTED_PERMISSIONS)
                        == PackageManager.PERMISSION_GRANTED;
        final boolean isCallerInstallerOnRecord =
                mPackageManagerInt.isCallerInstallerOfRecord(pkg, callingUid);

        if ((flags & PackageManager.FLAG_PERMISSION_WHITELIST_SYSTEM) != 0
                && !isCallerPrivileged) {
            throw new SecurityException("Querying system allowlist requires "
                    + Manifest.permission.WHITELIST_RESTRICTED_PERMISSIONS);
        }

        if ((flags & (PackageManager.FLAG_PERMISSION_WHITELIST_UPGRADE
                | PackageManager.FLAG_PERMISSION_WHITELIST_INSTALLER)) != 0) {
            if (!isCallerPrivileged && !isCallerInstallerOnRecord) {
                throw new SecurityException("Querying upgrade or installer allowlist"
                        + " requires being installer on record or "
                        + Manifest.permission.WHITELIST_RESTRICTED_PERMISSIONS);
            }
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            return getAllowlistedRestrictedPermissionsInternal(pkg, flags, userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Nullable
    private List<String> getAllowlistedRestrictedPermissionsInternal(@NonNull AndroidPackage pkg,
            @PermissionWhitelistFlags int flags, @UserIdInt int userId) {
        synchronized (mLock) {
            final UidPermissionState uidState = getUidStateLocked(pkg, userId);
            if (uidState == null) {
                Slog.e(TAG, "Missing permissions state for " + pkg.getPackageName() + " and user "
                        + userId);
                return null;
            }

            int queryFlags = 0;
            if ((flags & PackageManager.FLAG_PERMISSION_WHITELIST_SYSTEM) != 0) {
                queryFlags |= FLAG_PERMISSION_RESTRICTION_SYSTEM_EXEMPT;
            }
            if ((flags & PackageManager.FLAG_PERMISSION_WHITELIST_UPGRADE) != 0) {
                queryFlags |= FLAG_PERMISSION_RESTRICTION_UPGRADE_EXEMPT;
            }
            if ((flags & PackageManager.FLAG_PERMISSION_WHITELIST_INSTALLER) != 0) {
                queryFlags |= FLAG_PERMISSION_RESTRICTION_INSTALLER_EXEMPT;
            }

            ArrayList<String> allowlistedPermissions = null;

            final int permissionCount = ArrayUtils.size(pkg.getRequestedPermissions());
            for (int i = 0; i < permissionCount; i++) {
                final String permissionName = pkg.getRequestedPermissions().get(i);
                final int currentFlags =
                        uidState.getPermissionFlags(permissionName);
                if ((currentFlags & queryFlags) != 0) {
                    if (allowlistedPermissions == null) {
                        allowlistedPermissions = new ArrayList<>();
                    }
                    allowlistedPermissions.add(permissionName);
                }
            }

            return allowlistedPermissions;
        }
    }

    @Override
    public boolean addAllowlistedRestrictedPermission(@NonNull String packageName,
            @NonNull String permName, @PermissionWhitelistFlags int flags,
            @UserIdInt int userId) {
        // Other argument checks are done in get/setAllowlistedRestrictedPermissions
        Objects.requireNonNull(permName);

        if (!checkExistsAndEnforceCannotModifyImmutablyRestrictedPermission(permName)) {
            return false;
        }

        List<String> permissions =
                getAllowlistedRestrictedPermissions(packageName, flags, userId);
        if (permissions == null) {
            permissions = new ArrayList<>(1);
        }
        if (permissions.indexOf(permName) < 0) {
            permissions.add(permName);
            return setAllowlistedRestrictedPermissions(packageName, permissions,
                    flags, userId);
        }
        return false;
    }

    private boolean checkExistsAndEnforceCannotModifyImmutablyRestrictedPermission(
            @NonNull String permName) {
        final boolean isImmutablyRestrictedPermission;
        synchronized (mLock) {
            final Permission bp = mRegistry.getPermission(permName);
            if (bp == null) {
                Slog.w(TAG, "No such permissions: " + permName);
                return false;
            }
            isImmutablyRestrictedPermission = bp.isHardOrSoftRestricted()
                    && bp.isImmutablyRestricted();
        }
        if (isImmutablyRestrictedPermission && mContext.checkCallingOrSelfPermission(
                Manifest.permission.WHITELIST_RESTRICTED_PERMISSIONS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Cannot modify allowlisting of an immutably "
                    + "restricted permission: " + permName);
        }
        return true;
    }

    @Override
    public boolean removeAllowlistedRestrictedPermission(@NonNull String packageName,
            @NonNull String permName, @PermissionWhitelistFlags int flags,
            @UserIdInt int userId) {
        // Other argument checks are done in get/setAllowlistedRestrictedPermissions
        Objects.requireNonNull(permName);

        if (!checkExistsAndEnforceCannotModifyImmutablyRestrictedPermission(permName)) {
            return false;
        }

        final List<String> permissions =
                getAllowlistedRestrictedPermissions(packageName, flags, userId);
        if (permissions != null && permissions.remove(permName)) {
            return setAllowlistedRestrictedPermissions(packageName, permissions,
                    flags, userId);
        }
        return false;
    }

    private boolean setAllowlistedRestrictedPermissions(@NonNull String packageName,
            @Nullable List<String> permissions, @PermissionWhitelistFlags int flags,
            @UserIdInt int userId) {
        Objects.requireNonNull(packageName);
        Preconditions.checkFlagsArgument(flags,
                PackageManager.FLAG_PERMISSION_WHITELIST_UPGRADE
                        | PackageManager.FLAG_PERMISSION_WHITELIST_SYSTEM
                        | PackageManager.FLAG_PERMISSION_WHITELIST_INSTALLER);
        Preconditions.checkArgument(Integer.bitCount(flags) == 1);
        Preconditions.checkArgumentNonNegative(userId, null);

        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS,
                    "setAllowlistedRestrictedPermissions for user " + userId);
        }

        final AndroidPackage pkg = mPackageManagerInt.getPackage(packageName);
        if (pkg == null) {
            return false;
        }

        final int callingUid = Binder.getCallingUid();
        if (mPackageManagerInt.filterAppAccess(pkg, callingUid, UserHandle.getCallingUserId())) {
            return false;
        }

        final boolean isCallerPrivileged = mContext.checkCallingOrSelfPermission(
                Manifest.permission.WHITELIST_RESTRICTED_PERMISSIONS)
                        == PackageManager.PERMISSION_GRANTED;
        final boolean isCallerInstallerOnRecord =
                mPackageManagerInt.isCallerInstallerOfRecord(pkg, callingUid);

        if ((flags & PackageManager.FLAG_PERMISSION_WHITELIST_SYSTEM) != 0 && !isCallerPrivileged) {
            throw new SecurityException("Modifying system allowlist requires "
                    + Manifest.permission.WHITELIST_RESTRICTED_PERMISSIONS);
        }

        if ((flags & PackageManager.FLAG_PERMISSION_WHITELIST_UPGRADE) != 0) {
            if (!isCallerPrivileged && !isCallerInstallerOnRecord) {
                throw new SecurityException("Modifying upgrade allowlist requires"
                        + " being installer on record or "
                        + Manifest.permission.WHITELIST_RESTRICTED_PERMISSIONS);
            }
            final List<String> allowlistedPermissions =
                    getAllowlistedRestrictedPermissions(pkg.getPackageName(), flags, userId);
            if (permissions == null || permissions.isEmpty()) {
                if (allowlistedPermissions == null || allowlistedPermissions.isEmpty()) {
                    return true;
                }
            } else {
                // Only the system can add and remove while the installer can only remove.
                final int permissionCount = permissions.size();
                for (int i = 0; i < permissionCount; i++) {
                    if ((allowlistedPermissions == null
                            || !allowlistedPermissions.contains(permissions.get(i)))
                            && !isCallerPrivileged) {
                        throw new SecurityException("Adding to upgrade allowlist requires"
                                + Manifest.permission.WHITELIST_RESTRICTED_PERMISSIONS);
                    }
                }
            }

            if ((flags & PackageManager.FLAG_PERMISSION_WHITELIST_INSTALLER) != 0) {
                if (!isCallerPrivileged && !isCallerInstallerOnRecord) {
                    throw new SecurityException("Modifying installer allowlist requires"
                            + " being installer on record or "
                            + Manifest.permission.WHITELIST_RESTRICTED_PERMISSIONS);
                }
            }
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            setAllowlistedRestrictedPermissionsInternal(pkg, permissions, flags, userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }

        return true;
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
        if (pkg == null) {
            return false;
        }

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
        return true;
    }

    @Override
    public boolean isAutoRevokeExempted(@NonNull String packageName, int userId) {
        Objects.requireNonNull(packageName);

        final AndroidPackage pkg = mPackageManagerInt.getPackage(packageName);
        final int callingUid = Binder.getCallingUid();
        if (mPackageManagerInt.filterAppAccess(packageName, callingUid, userId)) {
            return false;
        }

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

    @Override
    public void grantRuntimePermission(String packageName, String permName, final int userId) {
        final int callingUid = Binder.getCallingUid();
        final boolean overridePolicy =
                checkUidPermission(callingUid, ADJUST_RUNTIME_PERMISSIONS_POLICY)
                        == PackageManager.PERMISSION_GRANTED;

        grantRuntimePermissionInternal(packageName, permName, overridePolicy,
                callingUid, userId, mDefaultPermissionCallback);
    }

    private void grantRuntimePermissionInternal(String packageName, String permName,
            boolean overridePolicy, int callingUid, final int userId, PermissionCallback callback) {
        if (PermissionManager.DEBUG_TRACE_GRANTS
                && PermissionManager.shouldTraceGrant(packageName, permName, userId)) {
            Log.i(PermissionManager.LOG_TAG_TRACE_GRANTS, "System is granting " + packageName + " "
                    + permName + " for user " + userId + " on behalf of uid " + callingUid
                    + " " + mPackageManagerInt.getNameForUid(callingUid),
                    new RuntimeException());
        }
        if (!mUserManagerInt.exists(userId)) {
            Log.e(TAG, "No such user:" + userId);
            return;
        }

        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.GRANT_RUNTIME_PERMISSIONS,
                "grantRuntimePermission");

        enforceCrossUserPermission(callingUid, userId,
                true,  // requireFullPermission
                true,  // checkShell
                "grantRuntimePermission");

        final AndroidPackage pkg = mPackageManagerInt.getPackage(packageName);
        final PackageSetting ps = mPackageManagerInt.getPackageSetting(packageName);
        if (pkg == null || ps == null) {
            Log.e(TAG, "Unknown package: " + packageName);
            return;
        }
        if (mPackageManagerInt.filterAppAccess(pkg, callingUid, userId)) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }

        final boolean isRolePermission;
        final boolean isSoftRestrictedPermission;
        synchronized (mLock) {
            final Permission permission = mRegistry.getPermission(permName);
            if (permission == null) {
                throw new IllegalArgumentException("Unknown permission: " + permName);
            }
            isRolePermission = permission.isRole();
            isSoftRestrictedPermission = permission.isSoftRestricted();
        }
        final boolean mayGrantRolePermission = isRolePermission
                && mayManageRolePermission(callingUid);
        final boolean mayGrantSoftRestrictedPermission = isSoftRestrictedPermission
                && SoftRestrictedPermissionPolicy.forPermission(mContext,
                        pkg.toAppInfoWithoutState(), pkg, UserHandle.of(userId), permName)
                        .mayGrantPermission();

        final boolean isRuntimePermission;
        final boolean permissionHasGids;
        synchronized (mLock) {
            final Permission bp = mRegistry.getPermission(permName);
            if (bp == null) {
                throw new IllegalArgumentException("Unknown permission: " + permName);
            }

            isRuntimePermission = bp.isRuntime();
            permissionHasGids = bp.hasGids();
            if (isRuntimePermission || bp.isDevelopment()) {
                // Good.
            } else if (bp.isRole()) {
                if (!mayGrantRolePermission) {
                    throw new SecurityException("Permission " + permName + " is managed by role");
                }
            } else {
                throw new SecurityException("Permission " + permName + " requested by "
                        + pkg.getPackageName() + " is not a changeable permission type");
            }

            final UidPermissionState uidState = getUidStateLocked(pkg, userId);
            if (uidState == null) {
                Slog.e(TAG, "Missing permissions state for " + pkg.getPackageName() + " and user "
                        + userId);
                return;
            }

            if (!(uidState.hasPermissionState(permName)
                    || pkg.getRequestedPermissions().contains(permName))) {
                throw new SecurityException("Package " + pkg.getPackageName()
                        + " has not requested permission " + permName);
            }

            // If a permission review is required for legacy apps we represent
            // their permissions as always granted runtime ones since we need
            // to keep the review required permission flag per user while an
            // install permission's state is shared across all users.
            if (pkg.getTargetSdkVersion() < Build.VERSION_CODES.M && bp.isRuntime()) {
                return;
            }

            final int flags = uidState.getPermissionFlags(permName);
            if ((flags & PackageManager.FLAG_PERMISSION_SYSTEM_FIXED) != 0) {
                Log.e(TAG, "Cannot grant system fixed permission "
                        + permName + " for package " + packageName);
                return;
            }
            if (!overridePolicy && (flags & PackageManager.FLAG_PERMISSION_POLICY_FIXED) != 0) {
                Log.e(TAG, "Cannot grant policy fixed permission "
                        + permName + " for package " + packageName);
                return;
            }

            if (bp.isHardRestricted()
                    && (flags & PackageManager.FLAGS_PERMISSION_RESTRICTION_ANY_EXEMPT) == 0) {
                Log.e(TAG, "Cannot grant hard restricted non-exempt permission "
                        + permName + " for package " + packageName);
                return;
            }

            if (bp.isSoftRestricted() && !mayGrantSoftRestrictedPermission) {
                Log.e(TAG, "Cannot grant soft restricted permission " + permName + " for package "
                        + packageName);
                return;
            }

            if (bp.isDevelopment() || bp.isRole()) {
                // Development permissions must be handled specially, since they are not
                // normal runtime permissions.  For now they apply to all users.
                // TODO(zhanghai): We are breaking the behavior above by making all permission state
                //  per-user. It isn't documented behavior and relatively rarely used anyway.
                if (!uidState.grantPermission(bp)) {
                    return;
                }
            } else {
                if (ps.getInstantApp(userId) && !bp.isInstant()) {
                    throw new SecurityException("Cannot grant non-ephemeral permission" + permName
                            + " for package " + packageName);
                }

                if (pkg.getTargetSdkVersion() < Build.VERSION_CODES.M) {
                    Slog.w(TAG, "Cannot grant runtime permission to a legacy app");
                    return;
                }

                if (!uidState.grantPermission(bp)) {
                    return;
                }
            }
        }

        if (isRuntimePermission) {
            logPermission(MetricsEvent.ACTION_PERMISSION_GRANTED, permName, packageName);
        }

        final int uid = UserHandle.getUid(userId, pkg.getUid());
        if (callback != null) {
            if (isRuntimePermission) {
                callback.onPermissionGranted(uid, userId);
            } else {
                callback.onInstallPermissionGranted();
            }
            if (permissionHasGids) {
                callback.onGidsChanged(UserHandle.getAppId(pkg.getUid()), userId);
            }
        }

        if (isRuntimePermission) {
            notifyRuntimePermissionStateChanged(packageName, userId);
        }
    }

    @Override
    public void revokeRuntimePermission(String packageName, String permName, int userId,
            String reason) {
        final int callingUid = Binder.getCallingUid();
        final boolean overridePolicy =
                checkUidPermission(callingUid, ADJUST_RUNTIME_PERMISSIONS_POLICY)
                        == PackageManager.PERMISSION_GRANTED;

        revokeRuntimePermissionInternal(packageName, permName, overridePolicy, callingUid, userId,
                reason, mDefaultPermissionCallback);
    }

    private void revokeRuntimePermissionInternal(String packageName, String permName,
            boolean overridePolicy, int callingUid, final int userId, String reason,
            PermissionCallback callback) {
        if (PermissionManager.DEBUG_TRACE_PERMISSION_UPDATES
                && PermissionManager.shouldTraceGrant(packageName, permName, userId)) {
            Log.i(TAG, "System is revoking " + packageName + " "
                            + permName + " for user " + userId + " on behalf of uid " + callingUid
                            + " " + mPackageManagerInt.getNameForUid(callingUid),
                    new RuntimeException());
        }
        if (!mUserManagerInt.exists(userId)) {
            Log.e(TAG, "No such user:" + userId);
            return;
        }

        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.REVOKE_RUNTIME_PERMISSIONS,
                "revokeRuntimePermission");

        enforceCrossUserPermission(callingUid, userId,
                true,  // requireFullPermission
                true,  // checkShell
                "revokeRuntimePermission");

        final AndroidPackage pkg = mPackageManagerInt.getPackage(packageName);
        if (pkg == null) {
            Log.e(TAG, "Unknown package: " + packageName);
            return;
        }
        if (mPackageManagerInt.filterAppAccess(pkg, callingUid, userId)) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }

        final boolean isRolePermission;
        synchronized (mLock) {
            final Permission permission = mRegistry.getPermission(permName);
            if (permission == null) {
                throw new IllegalArgumentException("Unknown permission: " + permName);
            }
            isRolePermission = permission.isRole();
        }
        final boolean mayRevokeRolePermission = isRolePermission
                && mayManageRolePermission(callingUid);

        final boolean isRuntimePermission;
        synchronized (mLock) {
            final Permission bp = mRegistry.getPermission(permName);
            if (bp == null) {
                throw new IllegalArgumentException("Unknown permission: " + permName);
            }

            isRuntimePermission = bp.isRuntime();
            if (isRuntimePermission || bp.isDevelopment()) {
                // Good.
            } else if (bp.isRole()) {
                if (!mayRevokeRolePermission) {
                    throw new SecurityException("Permission " + permName + " is managed by role");
                }
            } else {
                throw new SecurityException("Permission " + permName + " requested by "
                        + pkg.getPackageName() + " is not a changeable permission type");
            }

            final UidPermissionState uidState = getUidStateLocked(pkg, userId);
            if (uidState == null) {
                Slog.e(TAG, "Missing permissions state for " + pkg.getPackageName() + " and user "
                        + userId);
                return;
            }

            if (!(uidState.hasPermissionState(permName)
                    || pkg.getRequestedPermissions().contains(permName))) {
                throw new SecurityException("Package " + pkg.getPackageName()
                        + " has not requested permission " + permName);
            }

            // If a permission review is required for legacy apps we represent
            // their permissions as always granted runtime ones since we need
            // to keep the review required permission flag per user while an
            // install permission's state is shared across all users.
            if (pkg.getTargetSdkVersion() < Build.VERSION_CODES.M && bp.isRuntime()) {
                return;
            }

            final int flags = uidState.getPermissionFlags(permName);
            // Only the system may revoke SYSTEM_FIXED permissions.
            if ((flags & PackageManager.FLAG_PERMISSION_SYSTEM_FIXED) != 0
                    && UserHandle.getCallingAppId() != Process.SYSTEM_UID) {
                throw new SecurityException("Non-System UID cannot revoke system fixed permission "
                        + permName + " for package " + packageName);
            }
            if (!overridePolicy && (flags & PackageManager.FLAG_PERMISSION_POLICY_FIXED) != 0) {
                throw new SecurityException("Cannot revoke policy fixed permission "
                        + permName + " for package " + packageName);
            }

            // Development permissions must be handled specially, since they are not
            // normal runtime permissions.  For now they apply to all users.
            // TODO(zhanghai): We are breaking the behavior above by making all permission state
            //  per-user. It isn't documented behavior and relatively rarely used anyway.
            if (!uidState.revokePermission(bp)) {
                return;
            }
        }

        if (isRuntimePermission) {
            logPermission(MetricsEvent.ACTION_PERMISSION_REVOKED, permName, packageName);
        }

        if (callback != null) {
            if (isRuntimePermission) {
                callback.onPermissionRevoked(UserHandle.getUid(userId, pkg.getUid()), userId,
                        reason);
            } else {
                mDefaultPermissionCallback.onInstallPermissionRevoked();
            }
        }

        if (isRuntimePermission) {
            notifyRuntimePermissionStateChanged(packageName, userId);
        }
    }

    private boolean mayManageRolePermission(int uid) {
        final PackageManager packageManager = mContext.getPackageManager();
        final String[] packageNames = packageManager.getPackagesForUid(uid);
        if (packageNames == null) {
            return false;
        }
        final String permissionControllerPackageName =
                packageManager.getPermissionControllerPackageName();
        return Arrays.asList(packageNames).contains(permissionControllerPackageName);
    }

    /**
     * Reverts user permission state changes (permissions and flags).
     *
     * @param pkg The package for which to reset.
     * @param userId The device user for which to do a reset.
     */
    private void resetRuntimePermissionsInternal(@NonNull AndroidPackage pkg,
            @UserIdInt int userId) {
        final String packageName = pkg.getPackageName();

        // These are flags that can change base on user actions.
        final int userSettableMask = FLAG_PERMISSION_USER_SET
                | FLAG_PERMISSION_USER_FIXED
                | FLAG_PERMISSION_REVOKED_COMPAT
                | FLAG_PERMISSION_REVIEW_REQUIRED
                | FLAG_PERMISSION_ONE_TIME
                | FLAG_PERMISSION_SELECTED_LOCATION_ACCURACY;

        final int policyOrSystemFlags = FLAG_PERMISSION_SYSTEM_FIXED
                | FLAG_PERMISSION_POLICY_FIXED;

        // Delay and combine non-async permission callbacks
        final int permissionCount = ArrayUtils.size(pkg.getRequestedPermissions());
        final boolean[] permissionRemoved = new boolean[1];
        final ArraySet<Long> revokedPermissions = new ArraySet<>();
        final IntArray syncUpdatedUsers = new IntArray(permissionCount);
        final IntArray asyncUpdatedUsers = new IntArray(permissionCount);

        PermissionCallback delayingPermCallback = new PermissionCallback() {
            public void onGidsChanged(int appId, int userId) {
                mDefaultPermissionCallback.onGidsChanged(appId, userId);
            }

            public void onPermissionChanged() {
                mDefaultPermissionCallback.onPermissionChanged();
            }

            public void onPermissionGranted(int uid, int userId) {
                mDefaultPermissionCallback.onPermissionGranted(uid, userId);
            }

            public void onInstallPermissionGranted() {
                mDefaultPermissionCallback.onInstallPermissionGranted();
            }

            public void onPermissionRevoked(int uid, int userId, String reason) {
                revokedPermissions.add(IntPair.of(uid, userId));

                syncUpdatedUsers.add(userId);
            }

            public void onInstallPermissionRevoked() {
                mDefaultPermissionCallback.onInstallPermissionRevoked();
            }

            public void onPermissionUpdated(int[] updatedUserIds, boolean sync) {
                for (int userId : updatedUserIds) {
                    if (sync) {
                        syncUpdatedUsers.add(userId);
                        asyncUpdatedUsers.remove(userId);
                    } else {
                        // Don't override sync=true by sync=false
                        if (syncUpdatedUsers.indexOf(userId) == -1) {
                            asyncUpdatedUsers.add(userId);
                        }
                    }
                }
            }

            public void onPermissionRemoved() {
                permissionRemoved[0] = true;
            }

            public void onInstallPermissionUpdated() {
                mDefaultPermissionCallback.onInstallPermissionUpdated();
            }

            public void onPermissionUpdatedNotifyListener(@UserIdInt int[] updatedUserIds,
                    boolean sync, int uid) {
                onPermissionUpdated(updatedUserIds, sync);
                mOnPermissionChangeListeners.onPermissionsChanged(uid);
            }

            public void onInstallPermissionUpdatedNotifyListener(int uid) {
                mDefaultPermissionCallback.onInstallPermissionUpdatedNotifyListener(uid);
            }
        };

        for (int i = 0; i < permissionCount; i++) {
            final String permName = pkg.getRequestedPermissions().get(i);

            final boolean isRuntimePermission;
            synchronized (mLock) {
                final Permission permission = mRegistry.getPermission(permName);
                if (permission == null) {
                    continue;
                }

                if (permission.isRemoved()) {
                    continue;
                }
                isRuntimePermission = permission.isRuntime();
            }

            // If shared user we just reset the state to which only this app contributed.
            final String[] pkgNames = mPackageManagerInt.getSharedUserPackagesForPackage(
                    pkg.getPackageName(), userId);
            if (pkgNames.length > 0) {
                boolean used = false;
                for (String sharedPkgName : pkgNames) {
                    final AndroidPackage sharedPkg =
                            mPackageManagerInt.getPackage(sharedPkgName);
                    if (sharedPkg != null && !sharedPkg.getPackageName().equals(packageName)
                            && sharedPkg.getRequestedPermissions().contains(permName)) {
                        used = true;
                        break;
                    }
                }
                if (used) {
                    continue;
                }
            }

            final int oldFlags =
                    getPermissionFlagsInternal(packageName, permName, Process.SYSTEM_UID, userId);

            // Always clear the user settable flags.
            // If permission review is enabled and this is a legacy app, mark the
            // permission as requiring a review as this is the initial state.
            final int uid = mPackageManagerInt.getPackageUid(packageName, 0, userId);
            final int targetSdk = mPackageManagerInt.getUidTargetSdkVersion(uid);
            final int flags = (targetSdk < Build.VERSION_CODES.M && isRuntimePermission)
                    ? FLAG_PERMISSION_REVIEW_REQUIRED | FLAG_PERMISSION_REVOKED_COMPAT
                    : 0;

            updatePermissionFlagsInternal(
                    packageName, permName, userSettableMask, flags, Process.SYSTEM_UID, userId,
                    false, delayingPermCallback);

            // Below is only runtime permission handling.
            if (!isRuntimePermission) {
                continue;
            }

            // Never clobber system or policy.
            if ((oldFlags & policyOrSystemFlags) != 0) {
                continue;
            }

            // If this permission was granted by default or role, make sure it is.
            if ((oldFlags & FLAG_PERMISSION_GRANTED_BY_DEFAULT) != 0
                    || (oldFlags & FLAG_PERMISSION_GRANTED_BY_ROLE) != 0) {
                // PermissionPolicyService will handle the app op for runtime permissions later.
                grantRuntimePermissionInternal(packageName, permName, false,
                        Process.SYSTEM_UID, userId, delayingPermCallback);
            // In certain cases we should leave the state unchanged:
            // -- If permission review is enabled the permissions for a legacy apps
            // are represented as constantly granted runtime ones
            // -- If the permission was split from a non-runtime permission
            } else if ((flags & FLAG_PERMISSION_REVIEW_REQUIRED) == 0
                    && !isPermissionSplitFromNonRuntime(permName, targetSdk)) {
                // Otherwise, reset the permission.
                revokeRuntimePermissionInternal(packageName, permName, false, Process.SYSTEM_UID,
                        userId, null, delayingPermCallback);
            }
        }

        // Execute delayed callbacks
        if (permissionRemoved[0]) {
            mDefaultPermissionCallback.onPermissionRemoved();
        }

        // Slight variation on the code in mPermissionCallback.onPermissionRevoked() as we cannot
        // kill uid while holding mPackages-lock
        if (!revokedPermissions.isEmpty()) {
            int numRevokedPermissions = revokedPermissions.size();
            for (int i = 0; i < numRevokedPermissions; i++) {
                int revocationUID = IntPair.first(revokedPermissions.valueAt(i));
                int revocationUserId = IntPair.second(revokedPermissions.valueAt(i));

                mOnPermissionChangeListeners.onPermissionsChanged(revocationUID);

                // Kill app later as we are holding mPackages
                mHandler.post(() -> killUid(UserHandle.getAppId(revocationUID), revocationUserId,
                        KILL_APP_REASON_PERMISSIONS_REVOKED));
            }
        }

        mPackageManagerInt.writePermissionSettings(syncUpdatedUsers.toArray(), false);
        mPackageManagerInt.writePermissionSettings(asyncUpdatedUsers.toArray(), true);
    }

    /**
     * Determine if the given permission should be treated as split from a
     * non-runtime permission for an application targeting the given SDK level.
     */
    private boolean isPermissionSplitFromNonRuntime(String permName, int targetSdk) {
        final List<PermissionManager.SplitPermissionInfo> splitPerms = getSplitPermissionInfos();
        final int size = splitPerms.size();
        for (int i = 0; i < size; i++) {
            final PermissionManager.SplitPermissionInfo splitPerm = splitPerms.get(i);
            if (targetSdk < splitPerm.getTargetSdk()
                    && splitPerm.getNewPermissions().contains(permName)) {
                synchronized (mLock) {
                    final Permission perm =
                            mRegistry.getPermission(splitPerm.getSplitPermission());
                    return perm != null && !perm.isRuntime();
                }
            }
        }
        return false;
    }

    /**
     * This change makes it so that apps are told to show rationale for asking for background
     * location access every time they request.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.Q)
    private static final long BACKGROUND_RATIONALE_CHANGE_ID = 147316723L;

    @Override
    public boolean shouldShowRequestPermissionRationale(String packageName, String permName,
            @UserIdInt int userId) {
        final int callingUid = Binder.getCallingUid();
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                    "canShowRequestPermissionRationale for user " + userId);
        }

        final int uid =
                mPackageManagerInt.getPackageUid(packageName, MATCH_DEBUG_TRIAGED_MISSING, userId);
        if (UserHandle.getAppId(callingUid) != UserHandle.getAppId(uid)) {
            return false;
        }

        if (checkPermission(packageName, permName, userId)
                == PackageManager.PERMISSION_GRANTED) {
            return false;
        }

        final int flags;

        final long identity = Binder.clearCallingIdentity();
        try {
            flags = getPermissionFlagsInternal(packageName, permName, callingUid, userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }

        final int fixedFlags = PackageManager.FLAG_PERMISSION_SYSTEM_FIXED
                | PackageManager.FLAG_PERMISSION_POLICY_FIXED
                | PackageManager.FLAG_PERMISSION_USER_FIXED;

        if ((flags & fixedFlags) != 0) {
            return false;
        }

        synchronized (mLock) {
            final Permission permission = mRegistry.getPermission(permName);
            if (permission == null) {
                return false;
            }
            if (permission.isHardRestricted()
                    && (flags & FLAGS_PERMISSION_RESTRICTION_ANY_EXEMPT) == 0) {
                return false;
            }
        }

        final long token = Binder.clearCallingIdentity();
        try {
            if (permName.equals(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    && mPlatformCompat.isChangeEnabledByPackageName(BACKGROUND_RATIONALE_CHANGE_ID,
                    packageName, userId)) {
                return true;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to check if compatibility change is enabled.", e);
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        return (flags & PackageManager.FLAG_PERMISSION_USER_SET) != 0;
    }

    @Override
    public boolean isPermissionRevokedByPolicy(String packageName, String permName, int userId) {
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                    "isPermissionRevokedByPolicy for user " + userId);
        }

        if (checkPermission(packageName, permName, userId) == PackageManager.PERMISSION_GRANTED) {
            return false;
        }

        final int callingUid = Binder.getCallingUid();
        if (mPackageManagerInt.filterAppAccess(packageName, callingUid, userId)) {
            return false;
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            final int flags = getPermissionFlagsInternal(packageName, permName, callingUid, userId);
            return (flags & PackageManager.FLAG_PERMISSION_POLICY_FIXED) != 0;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Get the state of the runtime permissions as xml file.
     *
     * <p>Can not be called on main thread.
     *
     * @param userId The user ID the data should be extracted for
     *
     * @return The state as a xml file
     */
    @Nullable
    private byte[] backupRuntimePermissions(@UserIdInt int userId) {
        CompletableFuture<byte[]> backup = new CompletableFuture<>();
        mPermissionControllerManager.getRuntimePermissionBackup(UserHandle.of(userId),
                mContext.getMainExecutor(), backup::complete);

        try {
            return backup.get(BACKUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException  | TimeoutException e) {
            Slog.e(TAG, "Cannot create permission backup for user " + userId, e);
            return null;
        }
    }

    /**
     * Restore a permission state previously backed up via {@link #backupRuntimePermissions}.
     *
     * <p>If not all state can be restored, the un-appliable state will be delayed and can be
     * applied via {@link #restoreDelayedRuntimePermissions}.
     *
     * @param backup The state as an xml file
     * @param userId The user ID the data should be restored for
     */
    private void restoreRuntimePermissions(@NonNull byte[] backup, @UserIdInt int userId) {
        synchronized (mLock) {
            mHasNoDelayedPermBackup.delete(userId);
        }
        mPermissionControllerManager.stageAndApplyRuntimePermissionsBackup(backup,
                UserHandle.of(userId));
    }

    /**
     * Try to apply permission backup that was previously not applied.
     *
     * <p>Can not be called on main thread.
     *
     * @param packageName The package that is newly installed
     * @param userId The user ID the package is installed for
     *
     * @see #restoreRuntimePermissions
     */
    private void restoreDelayedRuntimePermissions(@NonNull String packageName,
            @UserIdInt int userId) {
        synchronized (mLock) {
            if (mHasNoDelayedPermBackup.get(userId, false)) {
                return;
            }
        }
        mPermissionControllerManager.applyStagedRuntimePermissionBackup(packageName,
                UserHandle.of(userId), mContext.getMainExecutor(), (hasMoreBackup) -> {
                    if (hasMoreBackup) {
                        return;
                    }
                    synchronized (mLock) {
                        mHasNoDelayedPermBackup.put(userId, true);
                    }
                });
    }

    private void addOnRuntimePermissionStateChangedListener(@NonNull
            OnRuntimePermissionStateChangedListener listener) {
        synchronized (mLock) {
            mRuntimePermissionStateChangedListeners.add(listener);
        }
    }

    private void removeOnRuntimePermissionStateChangedListener(@NonNull
            OnRuntimePermissionStateChangedListener listener) {
        synchronized (mLock) {
            mRuntimePermissionStateChangedListeners.remove(listener);
        }
    }

    private void notifyRuntimePermissionStateChanged(@NonNull String packageName,
            @UserIdInt int userId) {
        FgThread.getHandler().sendMessage(PooledLambda.obtainMessage
                (PermissionManagerService::doNotifyRuntimePermissionStateChanged,
                        PermissionManagerService.this, packageName, userId));
    }

    private void doNotifyRuntimePermissionStateChanged(@NonNull String packageName,
            @UserIdInt int userId) {
        final ArrayList<OnRuntimePermissionStateChangedListener> listeners;
        synchronized (mLock) {
            if (mRuntimePermissionStateChangedListeners.isEmpty()) {
                return;
            }
            listeners = new ArrayList<>(mRuntimePermissionStateChangedListeners);
        }
        final int listenerCount = listeners.size();
        for (int i = 0; i < listenerCount; i++) {
            listeners.get(i).onRuntimePermissionStateChanged(packageName, userId);
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

    /**
     * If the app is updated, and has scoped storage permissions, then it is possible that the
     * app updated in an attempt to get unscoped storage. If so, revoke all storage permissions.
     * @param newPackage The new package that was installed
     * @param oldPackage The old package that was updated
     */
    private void revokeStoragePermissionsIfScopeExpandedInternal(
            @NonNull AndroidPackage newPackage,
            @NonNull AndroidPackage oldPackage) {
        boolean downgradedSdk = oldPackage.getTargetSdkVersion() >= Build.VERSION_CODES.Q
                && newPackage.getTargetSdkVersion() < Build.VERSION_CODES.Q;
        boolean upgradedSdk = oldPackage.getTargetSdkVersion() < Build.VERSION_CODES.Q
                && newPackage.getTargetSdkVersion() >= Build.VERSION_CODES.Q;
        boolean newlyRequestsLegacy = !upgradedSdk && !oldPackage.isRequestLegacyExternalStorage()
                && newPackage.isRequestLegacyExternalStorage();

        if (!newlyRequestsLegacy && !downgradedSdk) {
            return;
        }

        final int callingUid = Binder.getCallingUid();
        for (int userId: getAllUserIds()) {
            int numRequestedPermissions = newPackage.getRequestedPermissions().size();
            for (int i = 0; i < numRequestedPermissions; i++) {
                PermissionInfo permInfo = getPermissionInfo(
                        newPackage.getRequestedPermissions().get(i),
                        newPackage.getPackageName(), 0);
                if (permInfo == null || !STORAGE_PERMISSIONS.contains(permInfo.name)) {
                    continue;
                }

                EventLog.writeEvent(0x534e4554, "171430330", newPackage.getUid(),
                        "Revoking permission " + permInfo.name + " from package "
                                + newPackage.getPackageName() + " as either the sdk downgraded "
                                + downgradedSdk + " or newly requested legacy full storage "
                                + newlyRequestsLegacy);

                try {
                    revokeRuntimePermissionInternal(newPackage.getPackageName(), permInfo.name,
                            false, callingUid, userId, null, mDefaultPermissionCallback);
                } catch (IllegalStateException | SecurityException e) {
                    Log.e(TAG, "unable to revoke " + permInfo.name + " for "
                            + newPackage.getPackageName() + " user " + userId, e);
                }
            }
        }

    }

    /**
     * We might auto-grant permissions if any permission of the group is already granted. Hence if
     * the group of a granted permission changes we need to revoke it to avoid having permissions of
     * the new group auto-granted.
     *
     * @param newPackage The new package that was installed
     * @param oldPackage The old package that was updated
     */
    private void revokeRuntimePermissionsIfGroupChangedInternal(@NonNull AndroidPackage newPackage,
            @NonNull AndroidPackage oldPackage) {
        final int numOldPackagePermissions = ArrayUtils.size(oldPackage.getPermissions());
        final ArrayMap<String, String> oldPermissionNameToGroupName
                = new ArrayMap<>(numOldPackagePermissions);

        for (int i = 0; i < numOldPackagePermissions; i++) {
            final ParsedPermission permission = oldPackage.getPermissions().get(i);

            if (permission.getParsedPermissionGroup() != null) {
                oldPermissionNameToGroupName.put(permission.getName(),
                        permission.getParsedPermissionGroup().getName());
            }
        }

        final int callingUid = Binder.getCallingUid();
        final int numNewPackagePermissions = ArrayUtils.size(newPackage.getPermissions());
        for (int newPermissionNum = 0; newPermissionNum < numNewPackagePermissions;
                newPermissionNum++) {
            final ParsedPermission newPermission =
                    newPackage.getPermissions().get(newPermissionNum);
            final int newProtection = newPermission.getProtection();

            if ((newProtection & PermissionInfo.PROTECTION_DANGEROUS) != 0) {
                final String permissionName = newPermission.getName();
                final String newPermissionGroupName =
                        newPermission.getParsedPermissionGroup() == null
                                ? null : newPermission.getParsedPermissionGroup().getName();
                final String oldPermissionGroupName = oldPermissionNameToGroupName.get(
                        permissionName);

                if (newPermissionGroupName != null
                        && !newPermissionGroupName.equals(oldPermissionGroupName)) {
                    final int[] userIds = mUserManagerInt.getUserIds();
                    mPackageManagerInt.forEachPackage(pkg -> {
                        final String packageName = pkg.getPackageName();
                        for (final int userId : userIds) {
                            final int permissionState = checkPermission(packageName, permissionName,
                                    userId);
                            if (permissionState == PackageManager.PERMISSION_GRANTED) {
                                EventLog.writeEvent(0x534e4554, "72710897",
                                        newPackage.getUid(),
                                        "Revoking permission " + permissionName +
                                        " from package " + packageName +
                                        " as the group changed from " + oldPermissionGroupName +
                                        " to " + newPermissionGroupName);

                                try {
                                    revokeRuntimePermissionInternal(packageName, permissionName,
                                            false, callingUid, userId, null,
                                            mDefaultPermissionCallback);
                                } catch (IllegalArgumentException e) {
                                    Slog.e(TAG, "Could not revoke " + permissionName + " from "
                                            + packageName, e);
                                }
                            }
                        }
                    });
                }
            }
        }
    }

    /**
     * If permissions are upgraded to runtime, or their owner changes to the system, then any
     * granted permissions must be revoked.
     *
     * @param permissionsToRevoke A list of permission names to revoke
     */
    private void revokeRuntimePermissionsIfPermissionDefinitionChangedInternal(
            @NonNull List<String> permissionsToRevoke) {
        final int[] userIds = mUserManagerInt.getUserIds();
        final int numPermissions = permissionsToRevoke.size();
        final int callingUid = Binder.getCallingUid();

        for (int permNum = 0; permNum < numPermissions; permNum++) {
            final String permName = permissionsToRevoke.get(permNum);
            synchronized (mLock) {
                final Permission bp = mRegistry.getPermission(permName);
                if (bp == null || !bp.isRuntime()) {
                    continue;
                }
            }
            mPackageManagerInt.forEachPackage(pkg -> {
                final String packageName = pkg.getPackageName();
                final int appId = pkg.getUid();
                if (appId < Process.FIRST_APPLICATION_UID) {
                    // do not revoke from system apps
                    return;
                }
                for (final int userId : userIds) {
                    final int permissionState = checkPermissionImpl(packageName, permName,
                            userId);
                    final int flags = getPermissionFlags(packageName, permName, userId);
                    final int flagMask = FLAG_PERMISSION_SYSTEM_FIXED
                            | FLAG_PERMISSION_POLICY_FIXED
                            | FLAG_PERMISSION_GRANTED_BY_DEFAULT
                            | FLAG_PERMISSION_GRANTED_BY_ROLE;
                    if (permissionState == PackageManager.PERMISSION_GRANTED
                            && (flags & flagMask) == 0) {
                        final int uid = UserHandle.getUid(userId, appId);
                        EventLog.writeEvent(0x534e4554, "154505240", uid,
                                "Revoking permission " + permName + " from package "
                                        + packageName + " due to definition change");
                        EventLog.writeEvent(0x534e4554, "168319670", uid,
                                "Revoking permission " + permName + " from package "
                                        + packageName + " due to definition change");
                        Slog.e(TAG, "Revoking permission " + permName + " from package "
                                + packageName + " due to definition change");
                        try {
                            revokeRuntimePermissionInternal(packageName, permName,
                                    false, callingUid, userId, null, mDefaultPermissionCallback);
                        } catch (Exception e) {
                            Slog.e(TAG, "Could not revoke " + permName + " from "
                                    + packageName, e);
                        }
                    }
                }
            });
        }
    }

    private List<String> addAllPermissionsInternal(@NonNull AndroidPackage pkg) {
        final int N = ArrayUtils.size(pkg.getPermissions());
        ArrayList<String> definitionChangedPermissions = new ArrayList<>();
        for (int i=0; i<N; i++) {
            ParsedPermission p = pkg.getPermissions().get(i);

            // Assume by default that we did not install this permission into the system.
            p.setFlags(p.getFlags() & ~PermissionInfo.FLAG_INSTALLED);

            final PermissionInfo permissionInfo;
            final Permission oldPermission;
            synchronized (mLock) {
                // Now that permission groups have a special meaning, we ignore permission
                // groups for legacy apps to prevent unexpected behavior. In particular,
                // permissions for one app being granted to someone just because they happen
                // to be in a group defined by another app (before this had no implications).
                if (pkg.getTargetSdkVersion() > Build.VERSION_CODES.LOLLIPOP_MR1) {
                    p.setParsedPermissionGroup(mRegistry.getPermissionGroup(p.getGroup()));
                    // Warn for a permission in an unknown group.
                    if (DEBUG_PERMISSIONS
                            && p.getGroup() != null && p.getParsedPermissionGroup() == null) {
                        Slog.i(TAG, "Permission " + p.getName() + " from package "
                                + p.getPackageName() + " in an unknown group " + p.getGroup());
                    }
                }

                permissionInfo = PackageInfoUtils.generatePermissionInfo(p,
                        PackageManager.GET_META_DATA);
                oldPermission = p.isTree() ? mRegistry.getPermissionTree(p.getName())
                        : mRegistry.getPermission(p.getName());
            }
            // TODO(zhanghai): Maybe we should store whether a permission is owned by system inside
            //  itself.
            final boolean isOverridingSystemPermission = Permission.isOverridingSystemPermission(
                    oldPermission, permissionInfo, mPackageManagerInt);
            synchronized (mLock) {
                final Permission permission = Permission.createOrUpdate(oldPermission,
                        permissionInfo, pkg, mRegistry.getPermissionTrees(),
                        isOverridingSystemPermission);
                if (p.isTree()) {
                    mRegistry.addPermissionTree(permission);
                } else {
                    mRegistry.addPermission(permission);
                }
                if (permission.isInstalled()) {
                    p.setFlags(p.getFlags() | PermissionInfo.FLAG_INSTALLED);
                }
                if (permission.isDefinitionChanged()) {
                    definitionChangedPermissions.add(p.getName());
                    permission.setDefinitionChanged(false);
                }
            }
        }
        return definitionChangedPermissions;
    }

    private void addAllPermissionGroupsInternal(@NonNull AndroidPackage pkg) {
        synchronized (mLock) {
            final int N = ArrayUtils.size(pkg.getPermissionGroups());
            StringBuilder r = null;
            for (int i = 0; i < N; i++) {
                final ParsedPermissionGroup pg = pkg.getPermissionGroups().get(i);
                final ParsedPermissionGroup cur = mRegistry.getPermissionGroup(pg.getName());
                final String curPackageName = (cur == null) ? null : cur.getPackageName();
                final boolean isPackageUpdate = pg.getPackageName().equals(curPackageName);
                if (cur == null || isPackageUpdate) {
                    mRegistry.addPermissionGroup(pg);
                    if (DEBUG_PACKAGE_SCANNING) {
                        if (r == null) {
                            r = new StringBuilder(256);
                        } else {
                            r.append(' ');
                        }
                        if (isPackageUpdate) {
                            r.append("UPD:");
                        }
                        r.append(pg.getName());
                    }
                } else {
                    Slog.w(TAG, "Permission group " + pg.getName() + " from package "
                            + pg.getPackageName() + " ignored: original from "
                            + cur.getPackageName());
                    if (DEBUG_PACKAGE_SCANNING) {
                        if (r == null) {
                            r = new StringBuilder(256);
                        } else {
                            r.append(' ');
                        }
                        r.append("DUP:");
                        r.append(pg.getName());
                    }
                }
            }
            if (r != null && DEBUG_PACKAGE_SCANNING) {
                Log.d(TAG, "  Permission Groups: " + r);
            }
        }
    }

    private void removeAllPermissionsInternal(@NonNull AndroidPackage pkg) {
        synchronized (mLock) {
            int N = ArrayUtils.size(pkg.getPermissions());
            StringBuilder r = null;
            for (int i=0; i<N; i++) {
                ParsedPermission p = pkg.getPermissions().get(i);
                Permission bp = mRegistry.getPermission(p.getName());
                if (bp == null) {
                    bp = mRegistry.getPermissionTree(p.getName());
                }
                if (bp != null && bp.isPermission(p)) {
                    bp.setPermissionInfo(null);
                    if (DEBUG_REMOVE) {
                        if (r == null) {
                            r = new StringBuilder(256);
                        } else {
                            r.append(' ');
                        }
                        r.append(p.getName());
                    }
                }
                if (p.isAppOp()) {
                    // TODO(zhanghai): Should we just remove the entry for this permission directly?
                    mRegistry.removeAppOpPermissionPackage(p.getName(), pkg.getPackageName());
                }
            }
            if (r != null) {
                if (DEBUG_REMOVE) Log.d(TAG, "  Permissions: " + r);
            }

            N = pkg.getRequestedPermissions().size();
            r = null;
            for (int i=0; i<N; i++) {
                final String permissionName = pkg.getRequestedPermissions().get(i);
                final Permission permission = mRegistry.getPermission(permissionName);
                if (permission != null && permission.isAppOp()) {
                    mRegistry.removeAppOpPermissionPackage(permissionName,
                            pkg.getPackageName());
                }
            }
            if (r != null) {
                if (DEBUG_REMOVE) Log.d(TAG, "  Permissions: " + r);
            }
        }
    }

    private void onUserRemoved(@UserIdInt int userId) {
        synchronized (mLock) {
            mState.removeUserState(userId);
        }
    }

    @NonNull
    private Set<String> getGrantedPermissionsInternal(@NonNull String packageName,
            @UserIdInt int userId) {
        final PackageSetting ps = mPackageManagerInt.getPackageSetting(packageName);
        if (ps == null) {
            return Collections.emptySet();
        }

        synchronized (mLock) {
            final UidPermissionState uidState = getUidStateLocked(ps, userId);
            if (uidState == null) {
                Slog.e(TAG, "Missing permissions state for " + packageName + " and user " + userId);
                return Collections.emptySet();
            }
            if (!ps.getInstantApp(userId)) {
                return uidState.getGrantedPermissions();
            } else {
                // Install permission state is shared among all users, but instant app state is
                // per-user, so we can only filter it here unless we make install permission state
                // per-user as well.
                final Set<String> instantPermissions =
                        new ArraySet<>(uidState.getGrantedPermissions());
                instantPermissions.removeIf(permissionName -> {
                    Permission permission = mRegistry.getPermission(permissionName);
                    if (permission == null) {
                        return true;
                    }
                    if (!permission.isInstant()) {
                        EventLog.writeEvent(0x534e4554, "140256621", UserHandle.getUid(userId,
                                ps.getAppId()), permissionName);
                        return true;
                    }
                    return false;
                });
                return instantPermissions;
            }
        }
    }

    @NonNull
    private int[] getPermissionGidsInternal(@NonNull String permissionName, @UserIdInt int userId) {
        synchronized (mLock) {
            Permission permission = mRegistry.getPermission(permissionName);
            if (permission == null) {
                return EmptyArray.INT;
            }
            return permission.computeGids(userId);
        }
    }

    /**
     * Restore the permission state for a package.
     *
     * <ul>
     *     <li>During boot the state gets restored from the disk</li>
     *     <li>During app update the state gets restored from the last version of the app</li>
     * </ul>
     *
     * @param pkg the package the permissions belong to
     * @param replace if the package is getting replaced (this might change the requested
     *                permissions of this package)
     * @param packageOfInterest If this is the name of {@code pkg} add extra logging
     * @param callback Result call back
     * @param filterUserId If not {@link UserHandle.USER_ALL}, only restore the permission state for
     *                     this particular user
     */
    private void restorePermissionState(@NonNull AndroidPackage pkg, boolean replace,
            @Nullable String packageOfInterest, @Nullable PermissionCallback callback,
            @UserIdInt int filterUserId) {
        // IMPORTANT: There are two types of permissions: install and runtime.
        // Install time permissions are granted when the app is installed to
        // all device users and users added in the future. Runtime permissions
        // are granted at runtime explicitly to specific users. Normal and signature
        // protected permissions are install time permissions. Dangerous permissions
        // are install permissions if the app's target SDK is Lollipop MR1 or older,
        // otherwise they are runtime permissions. This function does not manage
        // runtime permissions except for the case an app targeting Lollipop MR1
        // being upgraded to target a newer SDK, in which case dangerous permissions
        // are transformed from install time to runtime ones.

        final PackageSetting ps = (PackageSetting) mPackageManagerInt.getPackageSetting(
                pkg.getPackageName());
        if (ps == null) {
            return;
        }

        final int[] userIds = filterUserId == UserHandle.USER_ALL ? getAllUserIds()
                : new int[] { filterUserId };

        boolean runtimePermissionsRevoked = false;
        int[] updatedUserIds = EMPTY_INT_ARRAY;

        ArraySet<String> isPrivilegedPermissionAllowlisted = null;
        ArraySet<String> shouldGrantSignaturePermission = null;
        ArraySet<String> shouldGrantInternalPermission = null;
        ArraySet<String> shouldGrantPrivilegedPermissionIfWasGranted = new ArraySet<>();
        final List<String> requestedPermissions = pkg.getRequestedPermissions();
        final int requestedPermissionsSize = requestedPermissions.size();
        for (int i = 0; i < requestedPermissionsSize; i++) {
            final String permissionName = pkg.getRequestedPermissions().get(i);

            final Permission permission;
            synchronized (mLock) {
                permission = mRegistry.getPermission(permissionName);
            }
            if (permission == null) {
                continue;
            }
            if (permission.isPrivileged()
                    && checkPrivilegedPermissionAllowlist(pkg, ps, permission)) {
                if (isPrivilegedPermissionAllowlisted == null) {
                    isPrivilegedPermissionAllowlisted = new ArraySet<>();
                }
                isPrivilegedPermissionAllowlisted.add(permissionName);
            }
            if (permission.isSignature() && (shouldGrantPermissionBySignature(pkg, permission)
                    || shouldGrantPermissionByProtectionFlags(pkg, ps, permission,
                            shouldGrantPrivilegedPermissionIfWasGranted))) {
                if (shouldGrantSignaturePermission == null) {
                    shouldGrantSignaturePermission = new ArraySet<>();
                }
                shouldGrantSignaturePermission.add(permissionName);
            }
            if (permission.isInternal()
                    && shouldGrantPermissionByProtectionFlags(pkg, ps, permission,
                            shouldGrantPrivilegedPermissionIfWasGranted)) {
                if (shouldGrantInternalPermission == null) {
                    shouldGrantInternalPermission = new ArraySet<>();
                }
                shouldGrantInternalPermission.add(permissionName);
            }
        }

        final SparseBooleanArray isPermissionPolicyInitialized = new SparseBooleanArray();
        if (mPermissionPolicyInternal != null) {
            for (final int userId : userIds) {
                if (mPermissionPolicyInternal.isInitialized(userId)) {
                    isPermissionPolicyInitialized.put(userId, true);
                }
            }
        }

        synchronized (mLock) {
            for (final int userId : userIds) {
                final UserPermissionState userState = mState.getOrCreateUserState(userId);
                final UidPermissionState uidState = userState.getOrCreateUidState(ps.getAppId());

                if (uidState.isMissing()) {
                    Collection<String> uidRequestedPermissions;
                    int targetSdkVersion;
                    if (!ps.isSharedUser()) {
                        uidRequestedPermissions = pkg.getRequestedPermissions();
                        targetSdkVersion = pkg.getTargetSdkVersion();
                    } else {
                        uidRequestedPermissions = new ArraySet<>();
                        targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT;
                        List<AndroidPackage> packages = ps.getSharedUser().getPackages();
                        int packagesSize = packages.size();
                        for (int i = 0; i < packagesSize; i++) {
                            AndroidPackage sharedUserPackage = packages.get(i);
                            uidRequestedPermissions.addAll(
                                    sharedUserPackage.getRequestedPermissions());
                            targetSdkVersion = Math.min(targetSdkVersion,
                                    sharedUserPackage.getTargetSdkVersion());
                        }
                    }

                    for (String permissionName : uidRequestedPermissions) {
                        Permission permission = mRegistry.getPermission(permissionName);
                        if (permission == null) {
                            continue;
                        }
                        if (Objects.equals(permission.getPackageName(), PLATFORM_PACKAGE_NAME)
                                && permission.isRuntime() && !permission.isRemoved()) {
                            if (permission.isHardOrSoftRestricted()
                                    || permission.isImmutablyRestricted()) {
                                uidState.updatePermissionFlags(permission,
                                        FLAG_PERMISSION_RESTRICTION_UPGRADE_EXEMPT,
                                        FLAG_PERMISSION_RESTRICTION_UPGRADE_EXEMPT);
                            }
                            if (targetSdkVersion < Build.VERSION_CODES.M) {
                                uidState.updatePermissionFlags(permission,
                                        PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED
                                                | PackageManager.FLAG_PERMISSION_REVOKED_COMPAT,
                                        PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED
                                                | PackageManager.FLAG_PERMISSION_REVOKED_COMPAT);
                                uidState.grantPermission(permission);
                            }
                        }
                    }

                    uidState.setMissing(false);
                    updatedUserIds = ArrayUtils.appendInt(updatedUserIds, userId);
                }

                UidPermissionState origState = uidState;

                boolean changedInstallPermission = false;

                if (replace) {
                    userState.setInstallPermissionsFixed(ps.name, false);
                    if (!ps.isSharedUser()) {
                        origState = new UidPermissionState(uidState);
                        uidState.reset();
                    } else {
                        // We need to know only about runtime permission changes since the
                        // calling code always writes the install permissions state but
                        // the runtime ones are written only if changed. The only cases of
                        // changed runtime permissions here are promotion of an install to
                        // runtime and revocation of a runtime from a shared user.
                        if (revokeUnusedSharedUserPermissionsLocked(
                                ps.getSharedUser().getPackages(), uidState)) {
                            updatedUserIds = ArrayUtils.appendInt(updatedUserIds, userId);
                            runtimePermissionsRevoked = true;
                        }
                    }
                }

                ArraySet<String> newImplicitPermissions = new ArraySet<>();
                final String friendlyName = pkg.getPackageName() + "(" + pkg.getUid() + ")";

                for (int i = 0; i < requestedPermissionsSize; i++) {
                    final String permName = requestedPermissions.get(i);

                    final Permission bp = mRegistry.getPermission(permName);
                    final boolean appSupportsRuntimePermissions =
                            pkg.getTargetSdkVersion() >= Build.VERSION_CODES.M;
                    String legacyActivityRecognitionPermission = null;

                    if (DEBUG_INSTALL && bp != null) {
                        Log.i(TAG, "Package " + friendlyName
                                + " checking " + permName + ": " + bp);
                    }

                    // TODO(zhanghai): I don't think we need to check source package setting if
                    //  permission is present, because otherwise the permission should have been
                    //  removed.
                    if (bp == null /*|| getSourcePackageSetting(bp) == null*/) {
                        if (packageOfInterest == null || packageOfInterest.equals(
                                pkg.getPackageName())) {
                            if (DEBUG_PERMISSIONS) {
                                Slog.i(TAG, "Unknown permission " + permName
                                        + " in package " + friendlyName);
                            }
                        }
                        continue;
                    }

                    // Cache newImplicitPermissions before modifing permissionsState as for the
                    // shared uids the original and new state are the same object
                    if (!origState.hasPermissionState(permName)
                            && (pkg.getImplicitPermissions().contains(permName)
                            || (permName.equals(Manifest.permission.ACTIVITY_RECOGNITION)))) {
                        if (pkg.getImplicitPermissions().contains(permName)) {
                            // If permName is an implicit permission, try to auto-grant
                            newImplicitPermissions.add(permName);

                            if (DEBUG_PERMISSIONS) {
                                Slog.i(TAG, permName + " is newly added for " + friendlyName);
                            }
                        } else {
                            // Special case for Activity Recognition permission. Even if AR
                            // permission is not an implicit permission we want to add it to the
                            // list (try to auto-grant it) if the app was installed on a device
                            // before AR permission was split, regardless of if the app now requests
                            // the new AR permission or has updated its target SDK and AR is no
                            // longer implicit to it. This is a compatibility workaround for apps
                            // when AR permission was split in Q.
                            // TODO(zhanghai): This calls into SystemConfig, which generally
                            //  shouldn't  cause deadlock, but maybe we should keep a cache of the
                            //  split permission  list and just eliminate the possibility.
                            final List<PermissionManager.SplitPermissionInfo> permissionList =
                                    getSplitPermissionInfos();
                            int numSplitPerms = permissionList.size();
                            for (int splitPermNum = 0; splitPermNum < numSplitPerms;
                                    splitPermNum++) {
                                PermissionManager.SplitPermissionInfo sp = permissionList.get(
                                        splitPermNum);
                                String splitPermName = sp.getSplitPermission();
                                if (sp.getNewPermissions().contains(permName)
                                        && origState.isPermissionGranted(splitPermName)) {
                                    legacyActivityRecognitionPermission = splitPermName;
                                    newImplicitPermissions.add(permName);

                                    if (DEBUG_PERMISSIONS) {
                                        Slog.i(TAG, permName + " is newly added for "
                                                + friendlyName);
                                    }
                                    break;
                                }
                            }
                        }
                    }

                    // TODO(b/140256621): The package instant app method has been removed
                    //  as part of work in b/135203078, so this has been commented out in the
                    //  meantime
                    // Limit ephemeral apps to ephemeral allowed permissions.
        //            if (/*pkg.isInstantApp()*/ false && !bp.isInstant()) {
        //                if (DEBUG_PERMISSIONS) {
        //                    Log.i(TAG, "Denying non-ephemeral permission " + bp.getName()
        //                            + " for package " + pkg.getPackageName());
        //                }
        //                continue;
        //            }

                    if (bp.isRuntimeOnly() && !appSupportsRuntimePermissions) {
                        if (DEBUG_PERMISSIONS) {
                            Log.i(TAG, "Denying runtime-only permission " + bp.getName()
                                    + " for package " + friendlyName);
                        }
                        continue;
                    }

                    final String perm = bp.getName();

                    // Keep track of app op permissions.
                    if (bp.isAppOp()) {
                        mRegistry.addAppOpPermissionPackage(perm, pkg.getPackageName());
                    }

                    boolean shouldGrantNormalPermission = true;
                    if (bp.isNormal() && !origState.isPermissionGranted(perm)) {
                        // If this is an existing, non-system package, then
                        // we can't add any new permissions to it. Runtime
                        // permissions can be added any time - they are dynamic.
                        if (!ps.isSystem() && userState.areInstallPermissionsFixed(ps.name)) {
                            // Except...  if this is a permission that was added
                            // to the platform (note: need to only do this when
                            // updating the platform).
                            if (!isCompatPlatformPermissionForPackage(perm, pkg)) {
                                shouldGrantNormalPermission = false;
                            }
                        }
                    }

                    if (DEBUG_PERMISSIONS) {
                        Slog.i(TAG, "Considering granting permission " + perm + " to package "
                                + pkg.getPackageName());
                    }

                    if ((bp.isNormal() && shouldGrantNormalPermission)
                            || (bp.isSignature()
                                    && (!bp.isPrivileged() || CollectionUtils.contains(
                                            isPrivilegedPermissionAllowlisted, permName))
                                    && (CollectionUtils.contains(shouldGrantSignaturePermission,
                                            permName)
                                            || (((bp.isPrivileged() && CollectionUtils.contains(
                                                    shouldGrantPrivilegedPermissionIfWasGranted,
                                                    permName)) || bp.isDevelopment() || bp.isRole())
                                                    && origState.isPermissionGranted(permName))))
                            || (bp.isInternal()
                                    && (!bp.isPrivileged() || CollectionUtils.contains(
                                            isPrivilegedPermissionAllowlisted, permName))
                                    && (CollectionUtils.contains(shouldGrantInternalPermission,
                                            permName)
                                            || (((bp.isPrivileged() && CollectionUtils.contains(
                                                    shouldGrantPrivilegedPermissionIfWasGranted,
                                                    permName)) || bp.isDevelopment() || bp.isRole())
                                                    && origState.isPermissionGranted(permName))))) {
                        // Grant an install permission.
                        if (uidState.grantPermission(bp)) {
                            changedInstallPermission = true;
                        }
                    } else if (bp.isRuntime()) {
                        boolean hardRestricted = bp.isHardRestricted();
                        boolean softRestricted = bp.isSoftRestricted();

                        // If permission policy is not ready we don't deal with restricted
                        // permissions as the policy may allowlist some permissions. Once
                        // the policy is initialized we would re-evaluate permissions.
                        final boolean permissionPolicyInitialized =
                                isPermissionPolicyInitialized.get(userId);

                        PermissionState origPermState = origState.getPermissionState(perm);
                        int flags = origPermState != null ? origPermState.getFlags() : 0;

                        boolean wasChanged = false;

                        boolean restrictionExempt =
                                (origState.getPermissionFlags(bp.getName())
                                        & FLAGS_PERMISSION_RESTRICTION_ANY_EXEMPT) != 0;
                        boolean restrictionApplied = (origState.getPermissionFlags(
                                bp.getName()) & FLAG_PERMISSION_APPLY_RESTRICTION) != 0;

                        if (appSupportsRuntimePermissions) {
                            // If hard restricted we don't allow holding it
                            if (permissionPolicyInitialized && hardRestricted) {
                                if (!restrictionExempt) {
                                    if (origPermState != null && origPermState.isGranted()
                                            && uidState.revokePermission(bp)) {
                                        wasChanged = true;
                                    }
                                    if (!restrictionApplied) {
                                        flags |= FLAG_PERMISSION_APPLY_RESTRICTION;
                                        wasChanged = true;
                                    }
                                }
                            // If soft restricted we allow holding in a restricted form
                            } else if (permissionPolicyInitialized && softRestricted) {
                                // Regardless if granted set the restriction flag as it
                                // may affect app treatment based on this permission.
                                if (!restrictionExempt && !restrictionApplied) {
                                    flags |= FLAG_PERMISSION_APPLY_RESTRICTION;
                                    wasChanged = true;
                                }
                            }

                            // Remove review flag as it is not necessary anymore
                            if ((flags & FLAG_PERMISSION_REVIEW_REQUIRED) != 0) {
                                flags &= ~FLAG_PERMISSION_REVIEW_REQUIRED;
                                wasChanged = true;
                            }

                            if ((flags & FLAG_PERMISSION_REVOKED_COMPAT) != 0
                                    && !isPermissionSplitFromNonRuntime(permName,
                                    pkg.getTargetSdkVersion())) {
                                flags &= ~FLAG_PERMISSION_REVOKED_COMPAT;
                                wasChanged = true;
                            // Hard restricted permissions cannot be held.
                            } else if (!permissionPolicyInitialized
                                    || (!hardRestricted || restrictionExempt)) {
                                if ((origPermState != null && origPermState.isGranted())
                                        || legacyActivityRecognitionPermission != null) {
                                    if (!uidState.grantPermission(bp)) {
                                        wasChanged = true;
                                    }
                                }
                            }
                        } else {
                            if (origPermState == null) {
                                // New permission
                                if (PLATFORM_PACKAGE_NAME.equals(
                                        bp.getPackageName())) {
                                    if (!bp.isRemoved()) {
                                        flags |= FLAG_PERMISSION_REVIEW_REQUIRED
                                                | FLAG_PERMISSION_REVOKED_COMPAT;
                                        wasChanged = true;
                                    }
                                }
                            }

                            if (!uidState.isPermissionGranted(bp.getName())
                                    && uidState.grantPermission(bp)) {
                                wasChanged = true;
                            }

                            // If legacy app always grant the permission but if restricted
                            // and not exempt take a note a restriction should be applied.
                            if (permissionPolicyInitialized
                                    && (hardRestricted || softRestricted)
                                            && !restrictionExempt && !restrictionApplied) {
                                flags |= FLAG_PERMISSION_APPLY_RESTRICTION;
                                wasChanged = true;
                            }
                        }

                        // If unrestricted or restriction exempt, don't apply restriction.
                        if (permissionPolicyInitialized) {
                            if (!(hardRestricted || softRestricted) || restrictionExempt) {
                                if (restrictionApplied) {
                                    flags &= ~FLAG_PERMISSION_APPLY_RESTRICTION;
                                    // Dropping restriction on a legacy app implies a review
                                    if (!appSupportsRuntimePermissions) {
                                        flags |= FLAG_PERMISSION_REVIEW_REQUIRED;
                                    }
                                    wasChanged = true;
                                }
                            }
                        }

                        if (wasChanged) {
                            updatedUserIds = ArrayUtils.appendInt(updatedUserIds, userId);
                        }

                        uidState.updatePermissionFlags(bp, MASK_PERMISSION_FLAGS_ALL,
                                flags);
                    } else {
                        if (DEBUG_PERMISSIONS) {
                            boolean wasGranted = uidState.isPermissionGranted(bp.getName());
                            if (wasGranted || bp.isAppOp()) {
                                Slog.i(TAG, (wasGranted ? "Un-granting" : "Not granting")
                                        + " permission " + perm
                                        + " from package " + friendlyName
                                        + " (protectionLevel=" + bp.getProtectionLevel()
                                        + " flags=0x"
                                        + Integer.toHexString(PackageInfoUtils.appInfoFlags(pkg,
                                                ps))
                                        + ")");
                            }
                        }
                        if (uidState.removePermissionState(bp.getName())) {
                            changedInstallPermission = true;
                        }
                    }
                }

                if ((changedInstallPermission || replace)
                        && !userState.areInstallPermissionsFixed(ps.name)
                        && !ps.isSystem() || ps.getPkgState().isUpdatedSystemApp()) {
                    // This is the first that we have heard about this package, so the
                    // permissions we have now selected are fixed until explicitly
                    // changed.
                    userState.setInstallPermissionsFixed(ps.name, true);
                }

                updatedUserIds = revokePermissionsNoLongerImplicitLocked(uidState, pkg,
                        userId, updatedUserIds);
                updatedUserIds = setInitialGrantForNewImplicitPermissionsLocked(origState,
                        uidState, pkg, newImplicitPermissions, userId, updatedUserIds);
            }
        }

        updatedUserIds = checkIfLegacyStorageOpsNeedToBeUpdated(pkg, replace, userIds,
                updatedUserIds);

        // TODO: Kill UIDs whose GIDs or runtime permissions changed. This might be more important
        //  for shared users.
        // Persist the runtime permissions state for users with changes. If permissions
        // were revoked because no app in the shared user declares them we have to
        // write synchronously to avoid losing runtime permissions state.
        if (callback != null) {
            callback.onPermissionUpdated(updatedUserIds, runtimePermissionsRevoked);
        }

        for (int userId : updatedUserIds) {
            notifyRuntimePermissionStateChanged(pkg.getPackageName(), userId);
        }
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
     * Revoke permissions that are not implicit anymore and that have
     * {@link PackageManager#FLAG_PERMISSION_REVOKE_WHEN_REQUESTED} set.
     *
     * @param ps The state of the permissions of the package
     * @param pkg The package that is currently looked at
     * @param userIds All user IDs in the system, must be passed in because this method is locked
     * @param updatedUserIds a list of user ids that needs to be amended if the permission state
     *                       for a user is changed.
     *
     * @return The updated value of the {@code updatedUserIds} parameter
     */
    @GuardedBy("mLock")
    private @NonNull int[] revokePermissionsNoLongerImplicitLocked(@NonNull UidPermissionState ps,
            @NonNull AndroidPackage pkg, int userId, @NonNull int[] updatedUserIds) {
        String pkgName = pkg.getPackageName();
        boolean supportsRuntimePermissions = pkg.getTargetSdkVersion()
                >= Build.VERSION_CODES.M;

        for (String permission : ps.getGrantedPermissions()) {
            if (pkg.getRequestedPermissions().contains(permission)
                    && !pkg.getImplicitPermissions().contains(permission)) {
                Permission bp = mRegistry.getPermission(permission);
                if (bp != null && bp.isRuntime()) {
                    int flags = ps.getPermissionFlags(permission);

                    if ((flags & FLAG_PERMISSION_REVOKE_WHEN_REQUESTED) != 0) {

                        int flagsToRemove = FLAG_PERMISSION_REVOKE_WHEN_REQUESTED;

                        if ((flags & BLOCKING_PERMISSION_FLAGS) == 0
                                && supportsRuntimePermissions) {
                            if (ps.revokePermission(bp)) {
                                if (DEBUG_PERMISSIONS) {
                                    Slog.i(TAG, "Revoking runtime permission "
                                            + permission + " for " + pkgName
                                            + " as it is now requested");
                                }
                            }

                            flagsToRemove |= USER_PERMISSION_FLAGS;
                        }

                        ps.updatePermissionFlags(bp, flagsToRemove, 0);
                        updatedUserIds = ArrayUtils.appendInt(updatedUserIds, userId);
                    }
                }
            }
        }

        return updatedUserIds;
    }

    /**
     * {@code newPerm} is newly added; Inherit the state from {@code sourcePerms}.
     *
     * <p>A single new permission can be split off from several source permissions. In this case
     * the most leniant state is inherited.
     *
     * <p>Warning: This does not handle foreground / background permissions
     *
     * @param sourcePerms The permissions to inherit from
     * @param newPerm The permission to inherit to
     * @param ps The permission state of the package
     * @param pkg The package requesting the permissions
     */
    @GuardedBy("mLock")
    private void inheritPermissionStateToNewImplicitPermissionLocked(
            @NonNull ArraySet<String> sourcePerms, @NonNull String newPerm,
            @NonNull UidPermissionState ps, @NonNull AndroidPackage pkg) {
        String pkgName = pkg.getPackageName();
        boolean isGranted = false;
        int flags = 0;

        int numSourcePerm = sourcePerms.size();
        for (int i = 0; i < numSourcePerm; i++) {
            String sourcePerm = sourcePerms.valueAt(i);
            if (ps.isPermissionGranted(sourcePerm)) {
                if (!isGranted) {
                    flags = 0;
                }

                isGranted = true;
                flags |= ps.getPermissionFlags(sourcePerm);
            } else {
                if (!isGranted) {
                    flags |= ps.getPermissionFlags(sourcePerm);
                }
            }
        }

        if (isGranted) {
            if (DEBUG_PERMISSIONS) {
                Slog.i(TAG, newPerm + " inherits runtime perm grant from " + sourcePerms
                        + " for " + pkgName);
            }

            ps.grantPermission(mRegistry.getPermission(newPerm));
        }

        // Add permission flags
        ps.updatePermissionFlags(mRegistry.getPermission(newPerm), flags, flags);
    }

    /**
     * When the app has requested legacy storage we might need to update
     * {@link android.app.AppOpsManager#OP_LEGACY_STORAGE}. Hence force an update in
     * {@link com.android.server.policy.PermissionPolicyService#synchronizePackagePermissionsAndAppOpsForUser(Context, String, int)}
     *
     * @param pkg The package for which the permissions are updated
     * @param replace If the app is being replaced
     * @param userIds All user IDs in the system, must be passed in because this method is locked
     * @param updatedUserIds The ids of the users that already changed.
     *
     * @return The ids of the users that are changed
     */
    private @NonNull int[] checkIfLegacyStorageOpsNeedToBeUpdated(@NonNull AndroidPackage pkg,
            boolean replace, @NonNull int[] userIds, @NonNull int[] updatedUserIds) {
        if (replace && pkg.isRequestLegacyExternalStorage() && (
                pkg.getRequestedPermissions().contains(READ_EXTERNAL_STORAGE)
                        || pkg.getRequestedPermissions().contains(WRITE_EXTERNAL_STORAGE))) {
            return userIds.clone();
        }

        return updatedUserIds;
    }

    /**
     * Set the state of a implicit permission that is seen for the first time.
     *
     * @param origPs The permission state of the package before the split
     * @param ps The new permission state
     * @param pkg The package the permission belongs to
     * @param userId The user ID
     * @param updatedUserIds List of users for which the permission state has already been changed
     *
     * @return  List of users for which the permission state has been changed
     */
    @GuardedBy("mLock")
    private @NonNull int[] setInitialGrantForNewImplicitPermissionsLocked(
            @NonNull UidPermissionState origPs, @NonNull UidPermissionState ps,
            @NonNull AndroidPackage pkg, @NonNull ArraySet<String> newImplicitPermissions,
            @UserIdInt int userId, @NonNull int[] updatedUserIds) {
        String pkgName = pkg.getPackageName();
        ArrayMap<String, ArraySet<String>> newToSplitPerms = new ArrayMap<>();

        final List<PermissionManager.SplitPermissionInfo> permissionList =
                getSplitPermissionInfos();
        int numSplitPerms = permissionList.size();
        for (int splitPermNum = 0; splitPermNum < numSplitPerms; splitPermNum++) {
            PermissionManager.SplitPermissionInfo spi = permissionList.get(splitPermNum);

            List<String> newPerms = spi.getNewPermissions();
            int numNewPerms = newPerms.size();
            for (int newPermNum = 0; newPermNum < numNewPerms; newPermNum++) {
                String newPerm = newPerms.get(newPermNum);

                ArraySet<String> splitPerms = newToSplitPerms.get(newPerm);
                if (splitPerms == null) {
                    splitPerms = new ArraySet<>();
                    newToSplitPerms.put(newPerm, splitPerms);
                }

                splitPerms.add(spi.getSplitPermission());
            }
        }

        int numNewImplicitPerms = newImplicitPermissions.size();
        for (int newImplicitPermNum = 0; newImplicitPermNum < numNewImplicitPerms;
                newImplicitPermNum++) {
            String newPerm = newImplicitPermissions.valueAt(newImplicitPermNum);
            ArraySet<String> sourcePerms = newToSplitPerms.get(newPerm);

            if (sourcePerms != null) {
                Permission bp = mRegistry.getPermission(newPerm);
                if (bp == null) {
                    throw new IllegalStateException("Unknown new permission in split permission: "
                            + newPerm);
                }
                if (bp.isRuntime()) {

                    if (!newPerm.equals(Manifest.permission.ACTIVITY_RECOGNITION)) {
                        ps.updatePermissionFlags(bp,
                                FLAG_PERMISSION_REVOKE_WHEN_REQUESTED,
                                FLAG_PERMISSION_REVOKE_WHEN_REQUESTED);
                    }
                    updatedUserIds = ArrayUtils.appendInt(updatedUserIds, userId);

                    if (!origPs.hasPermissionState(sourcePerms)) {
                        boolean inheritsFromInstallPerm = false;
                        for (int sourcePermNum = 0; sourcePermNum < sourcePerms.size();
                                sourcePermNum++) {
                            final String sourcePerm = sourcePerms.valueAt(sourcePermNum);
                            Permission sourceBp = mRegistry.getPermission(sourcePerm);
                            if (sourceBp == null) {
                                throw new IllegalStateException("Unknown source permission in split"
                                        + " permission: " + sourcePerm);
                            }
                            if (!sourceBp.isRuntime()) {
                                inheritsFromInstallPerm = true;
                                break;
                            }
                        }

                        if (!inheritsFromInstallPerm) {
                            // Both permissions are new so nothing to inherit.
                            if (DEBUG_PERMISSIONS) {
                                Slog.i(TAG, newPerm + " does not inherit from " + sourcePerms
                                        + " for " + pkgName + " as split permission is also new");
                            }
                            continue;
                        }
                    }

                    // Inherit from new install or existing runtime permissions
                    inheritPermissionStateToNewImplicitPermissionLocked(sourcePerms, newPerm, ps,
                            pkg);
                }
            }
        }

        return updatedUserIds;
    }

    @NonNull
    @Override
    public List<SplitPermissionInfoParcelable> getSplitPermissions() {
        return PermissionManager.splitPermissionInfoListToParcelableList(getSplitPermissionInfos());
    }

    @NonNull
    private List<PermissionManager.SplitPermissionInfo> getSplitPermissionInfos() {
        return SystemConfig.getInstance().getSplitPermissions();
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

    @Override
    public void startOneTimePermissionSession(String packageName, @UserIdInt int userId,
            long timeoutMillis, int importanceToResetTimer, int importanceToKeepSessionAlive) {
        mContext.enforceCallingPermission(Manifest.permission.MANAGE_ONE_TIME_PERMISSION_SESSIONS,
                "Must hold " + Manifest.permission.MANAGE_ONE_TIME_PERMISSION_SESSIONS
                        + " to register permissions as one time.");
        Objects.requireNonNull(packageName);

        final long token = Binder.clearCallingIdentity();
        try {
            getOneTimePermissionUserManager(userId).startPackageOneTimeSession(packageName,
                    timeoutMillis, importanceToResetTimer, importanceToKeepSessionAlive);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void stopOneTimePermissionSession(String packageName, @UserIdInt int userId) {
        mContext.enforceCallingPermission(Manifest.permission.MANAGE_ONE_TIME_PERMISSION_SESSIONS,
                "Must hold " + Manifest.permission.MANAGE_ONE_TIME_PERMISSION_SESSIONS
                        + " to remove permissions as one time.");
        Objects.requireNonNull(packageName);

        final long token = Binder.clearCallingIdentity();
        try {
            getOneTimePermissionUserManager(userId).stopPackageOneTimeSession(packageName);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void registerAttributionSource(@NonNull AttributionSource source) {
        mAttributionSourceRegistry.registerAttributionSource(source);
    }

    @Override
    public boolean isRegisteredAttributionSource(@NonNull AttributionSource source) {
        return mAttributionSourceRegistry.isRegisteredAttributionSource(source);
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

    private static boolean isCompatPlatformPermissionForPackage(String perm, AndroidPackage pkg) {
        boolean allowed = false;
        for (int i = 0, size = CompatibilityPermissionInfo.COMPAT_PERMS.length; i < size; i++) {
            final CompatibilityPermissionInfo info = CompatibilityPermissionInfo.COMPAT_PERMS[i];
            if (info.getName().equals(perm)
                    && pkg.getTargetSdkVersion() < info.sdkVersion) {
                allowed = true;
                Log.i(TAG, "Auto-granting " + perm + " to old pkg "
                        + pkg.getPackageName());
                break;
            }
        }
        return allowed;
    }

    private boolean checkPrivilegedPermissionAllowlist(@NonNull AndroidPackage pkg,
            @NonNull PackageSetting packageSetting, @NonNull Permission permission) {
        if (RoSystemProperties.CONTROL_PRIVAPP_PERMISSIONS_DISABLE) {
            return true;
        }
        final String packageName = pkg.getPackageName();
        if (Objects.equals(packageName, PLATFORM_PACKAGE_NAME)) {
            return true;
        }
        if (!pkg.isPrivileged()) {
            return true;
        }
        if (!mPrivilegedPermissionAllowlistSourcePackageNames
                .contains(permission.getPackageName())) {
            return true;
        }
        final String permissionName = permission.getName();
        if (isInSystemConfigPrivAppPermissions(pkg, permissionName)) {
            return true;
        }
        if (isInSystemConfigPrivAppDenyPermissions(pkg, permissionName)) {
            return false;
        }
        // Updated system apps do not need to be allowlisted
        if (packageSetting.getPkgState().isUpdatedSystemApp()) {
            // Let shouldGrantPermissionByProtectionFlags() decide whether the privileged permission
            // can be granted, because an updated system app may be in a shared UID, and in case a
            // new privileged permission is requested by the updated system app but not the factory
            // app, although this app and permission combination isn't in the allowlist and can't
            // get the permission this way, other apps in the shared UID may still get it. A proper
            // fix for this would be to perform the reconciliation by UID, but for now let's keep
            // the old workaround working, which is to keep granted privileged permissions still
            // granted.
            return true;
        }
        // Only enforce the allowlist on boot
        if (!mSystemReady) {
            final ApexManager apexManager = ApexManager.getInstance();
            final String containingApexPackageName =
                    apexManager.getActiveApexPackageNameContainingPackage(packageName);
            final boolean isInUpdatedApex = containingApexPackageName != null
                    && !apexManager.isFactory(apexManager.getPackageInfo(containingApexPackageName,
                    MATCH_ACTIVE_PACKAGE));
            // Apps that are in updated apexs' do not need to be allowlisted
            if (!isInUpdatedApex) {
                Slog.w(TAG, "Privileged permission " + permissionName + " for package "
                        + packageName + " (" + pkg.getPath()
                        + ") not in privapp-permissions allowlist");
                if (RoSystemProperties.CONTROL_PRIVAPP_PERMISSIONS_ENFORCE) {
                    synchronized (mLock) {
                        if (mPrivappPermissionsViolations == null) {
                            mPrivappPermissionsViolations = new ArraySet<>();
                        }
                        mPrivappPermissionsViolations.add(packageName + " (" + pkg.getPath() + "): "
                                + permissionName);
                    }
                }
            }
        }
        return !RoSystemProperties.CONTROL_PRIVAPP_PERMISSIONS_ENFORCE;
    }

    private boolean isInSystemConfigPrivAppPermissions(@NonNull AndroidPackage pkg,
            @NonNull String permission) {
        final SystemConfig systemConfig = SystemConfig.getInstance();
        final Set<String> permissions;
        if (pkg.isVendor()) {
            permissions = systemConfig.getVendorPrivAppPermissions(pkg.getPackageName());
        } else if (pkg.isProduct()) {
            permissions = systemConfig.getProductPrivAppPermissions(pkg.getPackageName());
        } else if (pkg.isSystemExt()) {
            permissions = systemConfig.getSystemExtPrivAppPermissions(pkg.getPackageName());
        } else {
            permissions = systemConfig.getPrivAppPermissions(pkg.getPackageName());
        }
        return CollectionUtils.contains(permissions, permission);
    }

    private boolean isInSystemConfigPrivAppDenyPermissions(@NonNull AndroidPackage pkg,
            @NonNull String permission) {
        final SystemConfig systemConfig = SystemConfig.getInstance();
        final Set<String> permissions;
        if (pkg.isVendor()) {
            permissions = systemConfig.getVendorPrivAppDenyPermissions(pkg.getPackageName());
        } else if (pkg.isProduct()) {
            permissions = systemConfig.getProductPrivAppDenyPermissions(pkg.getPackageName());
        } else if (pkg.isSystemExt()) {
            permissions = systemConfig.getSystemExtPrivAppDenyPermissions(pkg.getPackageName());
        } else {
            permissions = systemConfig.getPrivAppDenyPermissions(pkg.getPackageName());
        }
        return CollectionUtils.contains(permissions, permission);
    }

    private boolean shouldGrantPermissionBySignature(@NonNull AndroidPackage pkg,
            @NonNull Permission bp) {
        // expect single system package
        String systemPackageName = ArrayUtils.firstOrNull(mPackageManagerInt.getKnownPackageNames(
                PackageManagerInternal.PACKAGE_SYSTEM, UserHandle.USER_SYSTEM));
        final AndroidPackage systemPackage =
                mPackageManagerInt.getPackage(systemPackageName);
        // check if the package is allow to use this signature permission.  A package is allowed to
        // use a signature permission if:
        //     - it has the same set of signing certificates as the source package
        //     - or its signing certificate was rotated from the source package's certificate
        //     - or its signing certificate is a previous signing certificate of the defining
        //       package, and the defining package still trusts the old certificate for permissions
        //     - or it shares a common signing certificate in its lineage with the defining package,
        //       and the defining package still trusts the old certificate for permissions
        //     - or it shares the above relationships with the system package
        final SigningDetails sourceSigningDetails =
                getSourcePackageSigningDetails(bp);
        return sourceSigningDetails.hasCommonSignerWithCapability(
                        pkg.getSigningDetails(),
                        SigningDetails.CertCapabilities.PERMISSION)
                || pkg.getSigningDetails().hasAncestorOrSelf(systemPackage.getSigningDetails())
                || systemPackage.getSigningDetails().checkCapability(
                        pkg.getSigningDetails(),
                        SigningDetails.CertCapabilities.PERMISSION);
    }

    private boolean shouldGrantPermissionByProtectionFlags(@NonNull AndroidPackage pkg,
            @NonNull PackageSetting pkgSetting, @NonNull Permission bp,
            @NonNull ArraySet<String> shouldGrantPrivilegedPermissionIfWasGranted) {
        boolean allowed = false;
        final boolean isPrivilegedPermission = bp.isPrivileged();
        final boolean isOemPermission = bp.isOem();
        if (!allowed && (isPrivilegedPermission || isOemPermission) && pkg.isSystem()) {
            final String permissionName = bp.getName();
            // For updated system applications, a privileged/oem permission
            // is granted only if it had been defined by the original application.
            if (pkgSetting.getPkgState().isUpdatedSystemApp()) {
                final PackageSetting disabledPs = mPackageManagerInt
                        .getDisabledSystemPackage(pkg.getPackageName());
                final AndroidPackage disabledPkg = disabledPs == null ? null : disabledPs.pkg;
                if (disabledPkg != null
                        && ((isPrivilegedPermission && disabledPkg.isPrivileged())
                        || (isOemPermission && canGrantOemPermission(disabledPkg,
                                permissionName)))) {
                    if (disabledPkg.getRequestedPermissions().contains(permissionName)) {
                        allowed = true;
                    } else {
                        // If the original was granted this permission, we take
                        // that grant decision as read and propagate it to the
                        // update.
                        shouldGrantPrivilegedPermissionIfWasGranted.add(permissionName);
                    }
                }
            } else {
                allowed = (isPrivilegedPermission && pkg.isPrivileged())
                        || (isOemPermission && canGrantOemPermission(pkg, permissionName));
            }
            // In any case, don't grant a privileged permission to privileged vendor apps, if
            // the permission's protectionLevel does not have the extra 'vendorPrivileged'
            // flag.
            if (allowed && isPrivilegedPermission && !bp.isVendorPrivileged() && pkg.isVendor()) {
                Slog.w(TAG, "Permission " + permissionName
                        + " cannot be granted to privileged vendor apk " + pkg.getPackageName()
                        + " because it isn't a 'vendorPrivileged' permission.");
                allowed = false;
            }
        }
        if (!allowed && bp.isPre23() && pkg.getTargetSdkVersion() < Build.VERSION_CODES.M) {
            // If this was a previously normal/dangerous permission that got moved
            // to a system permission as part of the runtime permission redesign, then
            // we still want to blindly grant it to old apps.
            allowed = true;
        }
        // TODO (moltmann): The installer now shares the platforms signature. Hence it does not
        //                  need a separate flag anymore. Hence we need to check which
        //                  permissions are needed by the permission controller
        if (!allowed && bp.isInstaller()
                && (ArrayUtils.contains(mPackageManagerInt.getKnownPackageNames(
                        PackageManagerInternal.PACKAGE_INSTALLER, UserHandle.USER_SYSTEM),
                pkg.getPackageName()) || ArrayUtils.contains(
                        mPackageManagerInt.getKnownPackageNames(
                                PackageManagerInternal.PACKAGE_PERMISSION_CONTROLLER,
                UserHandle.USER_SYSTEM), pkg.getPackageName()))) {
            // If this permission is to be granted to the system installer and
            // this app is an installer, then it gets the permission.
            allowed = true;
        }
        if (!allowed && bp.isVerifier()
                && ArrayUtils.contains(mPackageManagerInt.getKnownPackageNames(
                        PackageManagerInternal.PACKAGE_VERIFIER, UserHandle.USER_SYSTEM),
                pkg.getPackageName())) {
            // If this permission is to be granted to the system verifier and
            // this app is a verifier, then it gets the permission.
            allowed = true;
        }
        if (!allowed && bp.isPreInstalled()
                && pkg.isSystem()) {
            // Any pre-installed system app is allowed to get this permission.
            allowed = true;
        }
        if (!allowed && bp.isKnownSigner()) {
            // If the permission is to be granted to a known signer then check if any of this
            // app's signing certificates are in the trusted certificate digest Set.
            allowed = pkg.getSigningDetails().hasAncestorOrSelfWithDigest(bp.getKnownCerts());
        }
        // Deferred to be checked under permission data lock inside restorePermissionState().
        //if (!allowed && bp.isDevelopment()) {
        //    // For development permissions, a development permission
        //    // is granted only if it was already granted.
        //    allowed = origPermissions.isPermissionGranted(permissionName);
        //}
        if (!allowed && bp.isSetup()
                && ArrayUtils.contains(mPackageManagerInt.getKnownPackageNames(
                        PackageManagerInternal.PACKAGE_SETUP_WIZARD, UserHandle.USER_SYSTEM),
                pkg.getPackageName())) {
            // If this permission is to be granted to the system setup wizard and
            // this app is a setup wizard, then it gets the permission.
            allowed = true;
        }
        if (!allowed && bp.isSystemTextClassifier()
                && ArrayUtils.contains(mPackageManagerInt.getKnownPackageNames(
                        PackageManagerInternal.PACKAGE_SYSTEM_TEXT_CLASSIFIER,
                UserHandle.USER_SYSTEM), pkg.getPackageName())) {
            // Special permissions for the system default text classifier.
            allowed = true;
        }
        if (!allowed && bp.isConfigurator()
                && ArrayUtils.contains(mPackageManagerInt.getKnownPackageNames(
                        PackageManagerInternal.PACKAGE_CONFIGURATOR,
                UserHandle.USER_SYSTEM), pkg.getPackageName())) {
            // Special permissions for the device configurator.
            allowed = true;
        }
        if (!allowed && bp.isIncidentReportApprover()
                && ArrayUtils.contains(mPackageManagerInt.getKnownPackageNames(
                        PackageManagerInternal.PACKAGE_INCIDENT_REPORT_APPROVER,
                UserHandle.USER_SYSTEM), pkg.getPackageName())) {
            // If this permission is to be granted to the incident report approver and
            // this app is the incident report approver, then it gets the permission.
            allowed = true;
        }
        if (!allowed && bp.isAppPredictor()
                && ArrayUtils.contains(mPackageManagerInt.getKnownPackageNames(
                        PackageManagerInternal.PACKAGE_APP_PREDICTOR, UserHandle.USER_SYSTEM),
                pkg.getPackageName())) {
            // Special permissions for the system app predictor.
            allowed = true;
        }
        if (!allowed && bp.isCompanion()
                && ArrayUtils.contains(mPackageManagerInt.getKnownPackageNames(
                    PackageManagerInternal.PACKAGE_COMPANION, UserHandle.USER_SYSTEM),
                pkg.getPackageName())) {
            // Special permissions for the system companion device manager.
            allowed = true;
        }
        if (!allowed && bp.isRetailDemo()
                && ArrayUtils.contains(mPackageManagerInt.getKnownPackageNames(
                        PackageManagerInternal.PACKAGE_RETAIL_DEMO, UserHandle.USER_SYSTEM),
                pkg.getPackageName()) && isProfileOwner(pkg.getUid())) {
            // Special permission granted only to the OEM specified retail demo app
            allowed = true;
        }
        if (!allowed && bp.isRecents()
                && ArrayUtils.contains(mPackageManagerInt.getKnownPackageNames(
                PackageManagerInternal.PACKAGE_RECENTS, UserHandle.USER_SYSTEM),
                pkg.getPackageName())) {
            // Special permission for the recents app.
            allowed = true;
        }
        return allowed;
    }

    @NonNull
    private SigningDetails getSourcePackageSigningDetails(
            @NonNull Permission bp) {
        final PackageSetting ps = getSourcePackageSetting(bp);
        if (ps == null) {
            return SigningDetails.UNKNOWN;
        }
        return ps.getSigningDetails();
    }

    @Nullable
    private PackageSetting getSourcePackageSetting(@NonNull Permission bp) {
        final String sourcePackageName = bp.getPackageName();
        return mPackageManagerInt.getPackageSetting(sourcePackageName);
    }

    private static boolean canGrantOemPermission(AndroidPackage pkg, String permission) {
        if (!pkg.isOem()) {
            return false;
        }
        // all oem permissions must explicitly be granted or denied
        final Boolean granted =
                SystemConfig.getInstance().getOemPermissions(pkg.getPackageName()).get(permission);
        if (granted == null) {
            throw new IllegalStateException("OEM permission" + permission + " requested by package "
                    + pkg.getPackageName() + " must be explicitly declared granted or not");
        }
        return Boolean.TRUE == granted;
    }

    private static boolean isProfileOwner(int uid) {
        DevicePolicyManagerInternal dpmInternal =
                LocalServices.getService(DevicePolicyManagerInternal.class);
        //TODO(b/169395065) Figure out if this flow makes sense in Device Owner mode.
        if (dpmInternal != null) {
            return dpmInternal.isActiveProfileOwner(uid) || dpmInternal.isActiveDeviceOwner(uid);
        }
        return false;
    }

    private boolean isPermissionsReviewRequiredInternal(@NonNull String packageName,
            @UserIdInt int userId) {
        final AndroidPackage pkg = mPackageManagerInt.getPackage(packageName);
        if (pkg == null) {
            return false;
        }

        // Permission review applies only to apps not supporting the new permission model.
        if (pkg.getTargetSdkVersion() >= Build.VERSION_CODES.M) {
            return false;
        }

        // Legacy apps have the permission and get user consent on launch.
        synchronized (mLock) {
            final UidPermissionState uidState = getUidStateLocked(pkg, userId);
            if (uidState == null) {
                Slog.e(TAG, "Missing permissions state for " + pkg.getPackageName() + " and user "
                        + userId);
                return false;
            }
            return uidState.isPermissionsReviewRequired();
        }
    }

    private void grantRequestedRuntimePermissionsInternal(@NonNull AndroidPackage pkg,
            @Nullable List<String> permissions, int userId) {
        final int immutableFlags = PackageManager.FLAG_PERMISSION_SYSTEM_FIXED
                | PackageManager.FLAG_PERMISSION_POLICY_FIXED;

        final int compatFlags = PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED
                | PackageManager.FLAG_PERMISSION_REVOKED_COMPAT;

        final boolean supportsRuntimePermissions = pkg.getTargetSdkVersion()
                >= Build.VERSION_CODES.M;

        final boolean instantApp = mPackageManagerInt.isInstantApp(pkg.getPackageName(), userId);

        final int myUid = Process.myUid();

        for (String permission : pkg.getRequestedPermissions()) {
            final boolean shouldGrantPermission;
            synchronized (mLock) {
                final Permission bp = mRegistry.getPermission(permission);
                shouldGrantPermission = bp != null && (bp.isRuntime() || bp.isDevelopment())
                        && (!instantApp || bp.isInstant())
                        && (supportsRuntimePermissions || !bp.isRuntimeOnly())
                        && (permissions == null || permissions.contains(permission));
            }
            if (shouldGrantPermission) {
                final int flags = getPermissionFlagsInternal(pkg.getPackageName(), permission,
                        myUid, userId);
                if (supportsRuntimePermissions) {
                    // Installer cannot change immutable permissions.
                    if ((flags & immutableFlags) == 0) {
                        grantRuntimePermissionInternal(pkg.getPackageName(), permission, false,
                                myUid, userId, mDefaultPermissionCallback);
                    }
                } else {
                    // In permission review mode we clear the review flag and the revoked compat
                    // flag when we are asked to install the app with all permissions granted.
                    if ((flags & compatFlags) != 0) {
                        updatePermissionFlagsInternal(pkg.getPackageName(), permission, compatFlags,
                                0, myUid, userId, false, mDefaultPermissionCallback);
                    }
                }
            }
        }
    }

    private void setAllowlistedRestrictedPermissionsInternal(@NonNull AndroidPackage pkg,
            @Nullable List<String> permissions, @PermissionWhitelistFlags int allowlistFlags,
            @UserIdInt int userId) {
        ArraySet<String> oldGrantedRestrictedPermissions = null;
        boolean updatePermissions = false;
        final int permissionCount = pkg.getRequestedPermissions().size();
        final int myUid = Process.myUid();

        for (int j = 0; j < permissionCount; j++) {
            final String permissionName = pkg.getRequestedPermissions().get(j);

            final boolean isGranted;
            synchronized (mLock) {
                final Permission bp = mRegistry.getPermission(permissionName);
                if (bp == null || !bp.isHardOrSoftRestricted()) {
                    continue;
                }

                final UidPermissionState uidState = getUidStateLocked(pkg, userId);
                if (uidState == null) {
                    Slog.e(TAG, "Missing permissions state for " + pkg.getPackageName()
                            + " and user " + userId);
                    continue;
                }
                isGranted = uidState.isPermissionGranted(permissionName);
            }

            if (isGranted) {
                if (oldGrantedRestrictedPermissions == null) {
                    oldGrantedRestrictedPermissions = new ArraySet<>();
                }
                oldGrantedRestrictedPermissions.add(permissionName);
            }

            final int oldFlags = getPermissionFlagsInternal(pkg.getPackageName(), permissionName,
                    myUid, userId);

            int newFlags = oldFlags;
            int mask = 0;
            int allowlistFlagsCopy = allowlistFlags;
            while (allowlistFlagsCopy != 0) {
                final int flag = 1 << Integer.numberOfTrailingZeros(allowlistFlagsCopy);
                allowlistFlagsCopy &= ~flag;
                switch (flag) {
                    case FLAG_PERMISSION_WHITELIST_SYSTEM: {
                        mask |= FLAG_PERMISSION_RESTRICTION_SYSTEM_EXEMPT;
                        if (permissions != null && permissions.contains(permissionName)) {
                            newFlags |= FLAG_PERMISSION_RESTRICTION_SYSTEM_EXEMPT;
                        } else {
                            newFlags &= ~FLAG_PERMISSION_RESTRICTION_SYSTEM_EXEMPT;
                        }
                    }
                    break;
                    case FLAG_PERMISSION_WHITELIST_UPGRADE: {
                        mask |= FLAG_PERMISSION_RESTRICTION_UPGRADE_EXEMPT;
                        if (permissions != null && permissions.contains(permissionName)) {
                            newFlags |= FLAG_PERMISSION_RESTRICTION_UPGRADE_EXEMPT;
                        } else {
                            newFlags &= ~FLAG_PERMISSION_RESTRICTION_UPGRADE_EXEMPT;
                        }
                    }
                    break;
                    case FLAG_PERMISSION_WHITELIST_INSTALLER: {
                        mask |= FLAG_PERMISSION_RESTRICTION_INSTALLER_EXEMPT;
                        if (permissions != null && permissions.contains(permissionName)) {
                            newFlags |= FLAG_PERMISSION_RESTRICTION_INSTALLER_EXEMPT;
                        } else {
                            newFlags &= ~FLAG_PERMISSION_RESTRICTION_INSTALLER_EXEMPT;
                        }
                    }
                    break;
                }
            }

            if (oldFlags == newFlags) {
                continue;
            }

            updatePermissions = true;

            final boolean wasAllowlisted = (oldFlags
                    & (PackageManager.FLAGS_PERMISSION_RESTRICTION_ANY_EXEMPT)) != 0;
            final boolean isAllowlisted = (newFlags
                    & (PackageManager.FLAGS_PERMISSION_RESTRICTION_ANY_EXEMPT)) != 0;

            // If the permission is policy fixed as granted but it is no longer
            // on any of the allowlists we need to clear the policy fixed flag
            // as allowlisting trumps policy i.e. policy cannot grant a non
            // grantable permission.
            if ((oldFlags & PackageManager.FLAG_PERMISSION_POLICY_FIXED) != 0) {
                if (!isAllowlisted && isGranted) {
                    mask |= PackageManager.FLAG_PERMISSION_POLICY_FIXED;
                    newFlags &= ~PackageManager.FLAG_PERMISSION_POLICY_FIXED;
                }
            }

            // If we are allowlisting an app that does not support runtime permissions
            // we need to make sure it goes through the permission review UI at launch.
            if (pkg.getTargetSdkVersion() < Build.VERSION_CODES.M
                    && !wasAllowlisted && isAllowlisted) {
                mask |= PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED;
                newFlags |= PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED;
            }

            updatePermissionFlagsInternal(pkg.getPackageName(), permissionName, mask, newFlags,
                    myUid, userId, false, null /*callback*/);
        }

        if (updatePermissions) {
            // Update permission of this app to take into account the new allowlist state.
            restorePermissionState(pkg, false, pkg.getPackageName(), mDefaultPermissionCallback,
                    userId);

            // If this resulted in losing a permission we need to kill the app.
            if (oldGrantedRestrictedPermissions == null) {
                return;
            }

            final int oldGrantedCount = oldGrantedRestrictedPermissions.size();
            for (int j = 0; j < oldGrantedCount; j++) {
                final String permissionName = oldGrantedRestrictedPermissions.valueAt(j);
                // Sometimes we create a new permission state instance during update.
                final boolean isGranted;
                synchronized (mLock) {
                    final UidPermissionState uidState = getUidStateLocked(pkg, userId);
                    if (uidState == null) {
                        Slog.e(TAG, "Missing permissions state for " + pkg.getPackageName()
                                + " and user " + userId);
                        continue;
                    }
                    isGranted = uidState.isPermissionGranted(permissionName);
                }
                if (!isGranted) {
                    mDefaultPermissionCallback.onPermissionRevoked(pkg.getUid(), userId, null);
                    break;
                }
            }
        }
    }

    @UserIdInt
    private int revokeSharedUserPermissionsForDeletedPackageInternal(
            @Nullable AndroidPackage pkg, @NonNull List<AndroidPackage> sharedUserPkgs,
            @UserIdInt int userId) {
        if (pkg == null) {
            Slog.i(TAG, "Trying to update info for null package. Just ignoring");
            return UserHandle.USER_NULL;
        }

        // No shared user packages
        if (sharedUserPkgs.isEmpty()) {
            return UserHandle.USER_NULL;
        }

        int affectedUserId = UserHandle.USER_NULL;
        // Update permissions
        for (String eachPerm : pkg.getRequestedPermissions()) {
            // Check if another package in the shared user needs the permission.
            boolean used = false;
            for (AndroidPackage sharedUserpkg : sharedUserPkgs) {
                if (sharedUserpkg != null
                        && !sharedUserpkg.getPackageName().equals(pkg.getPackageName())
                        && sharedUserpkg.getRequestedPermissions().contains(eachPerm)) {
                    used = true;
                    break;
                }
            }
            if (used) {
                continue;
            }

            PackageSetting disabledPs = mPackageManagerInt.getDisabledSystemPackage(
                    pkg.getPackageName());

            // If the package is shadowing is a disabled system package,
            // do not drop permissions that the shadowed package requests.
            if (disabledPs != null) {
                boolean reqByDisabledSysPkg = false;
                for (String permission : disabledPs.pkg.getRequestedPermissions()) {
                    if (permission.equals(eachPerm)) {
                        reqByDisabledSysPkg = true;
                        break;
                    }
                }
                if (reqByDisabledSysPkg) {
                    continue;
                }
            }

            synchronized (mLock) {
                UidPermissionState uidState = getUidStateLocked(pkg, userId);
                if (uidState == null) {
                    Slog.e(TAG, "Missing permissions state for " + pkg.getPackageName()
                            + " and user " + userId);
                    continue;
                }

                Permission bp = mRegistry.getPermission(eachPerm);
                if (bp == null) {
                    continue;
                }

                // TODO(zhanghai): Why are we only killing the UID when GIDs changed, instead of any
                //  permission change?
                if (uidState.removePermissionState(bp.getName()) && bp.hasGids()) {
                    affectedUserId = userId;
                }
            }
        }

        return affectedUserId;
    }

    @GuardedBy("mLock")
    private boolean revokeUnusedSharedUserPermissionsLocked(
            List<AndroidPackage> pkgList, UidPermissionState uidState) {
        // Collect all used permissions in the UID
        final ArraySet<String> usedPermissions = new ArraySet<>();
        if (pkgList == null || pkgList.size() == 0) {
            return false;
        }
        for (AndroidPackage pkg : pkgList) {
            if (pkg.getRequestedPermissions().isEmpty()) {
                continue;
            }
            final int requestedPermCount = pkg.getRequestedPermissions().size();
            for (int j = 0; j < requestedPermCount; j++) {
                String permission = pkg.getRequestedPermissions().get(j);
                Permission bp = mRegistry.getPermission(permission);
                if (bp != null) {
                    usedPermissions.add(permission);
                }
            }
        }

        boolean runtimePermissionChanged = false;

        // Prune permissions
        final List<PermissionState> permissionStates = uidState.getPermissionStates();
        final int permissionStatesSize = permissionStates.size();
        for (int i = permissionStatesSize - 1; i >= 0; i--) {
            PermissionState permissionState = permissionStates.get(i);
            if (!usedPermissions.contains(permissionState.getName())) {
                Permission bp = mRegistry.getPermission(permissionState.getName());
                if (bp != null) {
                    if (uidState.removePermissionState(bp.getName()) && bp.isRuntime()) {
                        runtimePermissionChanged = true;
                    }
                }
            }
        }

        return runtimePermissionChanged;
    }

    /**
     * Update permissions when a package changed.
     *
     * <p><ol>
     *     <li>Reconsider the ownership of permission</li>
     *     <li>Update the state (grant, flags) of the permissions</li>
     * </ol>
     *
     * @param packageName The package that is updated
     * @param pkg The package that is updated, or {@code null} if package is deleted
     */
    private void updatePermissions(@NonNull String packageName, @Nullable AndroidPackage pkg) {
        // If the package is being deleted, update the permissions of all the apps
        final int flags =
                (pkg == null ? UPDATE_PERMISSIONS_ALL | UPDATE_PERMISSIONS_REPLACE_PKG
                        : UPDATE_PERMISSIONS_REPLACE_PKG);
        updatePermissions(
                packageName, pkg, getVolumeUuidForPackage(pkg), flags, mDefaultPermissionCallback);
    }

    /**
     * Update all permissions for all apps.
     *
     * <p><ol>
     *     <li>Reconsider the ownership of permission</li>
     *     <li>Update the state (grant, flags) of the permissions</li>
     * </ol>
     *
     * @param volumeUuid The volume UUID of the packages to be updated
     * @param fingerprintChanged whether the current build fingerprint is different from what it was
     *                           when this volume was last mounted
     */
    private void updateAllPermissions(@NonNull String volumeUuid, boolean fingerprintChanged) {
        PackageManager.corkPackageInfoCache();  // Prevent invalidation storm
        try {
            final int flags = UPDATE_PERMISSIONS_ALL |
                    (fingerprintChanged
                            ? UPDATE_PERMISSIONS_REPLACE_PKG | UPDATE_PERMISSIONS_REPLACE_ALL
                            : 0);
            updatePermissions(null, null, volumeUuid, flags, mDefaultPermissionCallback);
        } finally {
            PackageManager.uncorkPackageInfoCache();
        }
    }

    /**
     * Update all packages on the volume, <u>beside</u> the changing package. If the changing
     * package is set too, all packages are updated.
     */
    private static final int UPDATE_PERMISSIONS_ALL = 1 << 0;
    /** The changing package is replaced. Requires the changing package to be set */
    private static final int UPDATE_PERMISSIONS_REPLACE_PKG = 1 << 1;
    /**
     * Schedule all packages <u>beside</u> the changing package for replacement. Requires
     * UPDATE_PERMISSIONS_ALL to be set
     */
    private static final int UPDATE_PERMISSIONS_REPLACE_ALL = 1 << 2;

    @IntDef(flag = true, prefix = { "UPDATE_PERMISSIONS_" }, value = {
            UPDATE_PERMISSIONS_ALL, UPDATE_PERMISSIONS_REPLACE_PKG,
            UPDATE_PERMISSIONS_REPLACE_ALL })
    @Retention(RetentionPolicy.SOURCE)
    private @interface UpdatePermissionFlags {}

    /**
     * Update permissions when packages changed.
     *
     * <p><ol>
     *     <li>Reconsider the ownership of permission</li>
     *     <li>Update the state (grant, flags) of the permissions</li>
     * </ol>
     *
     * <p>Meaning of combination of package parameters:
     * <table>
     *     <tr><th></th><th>changingPkgName != null</th><th>changingPkgName == null</th></tr>
     *     <tr><th>changingPkg != null</th><td>package is updated</td><td>invalid</td></tr>
     *     <tr><th>changingPkg == null</th><td>package is deleted</td><td>all packages are
     *                                                                    updated</td></tr>
     * </table>
     *
     * @param changingPkgName The package that is updated, or {@code null} if all packages should be
     *                    updated
     * @param changingPkg The package that is updated, or {@code null} if all packages should be
     *                    updated or package is deleted
     * @param replaceVolumeUuid The volume of the packages to be updated are on, {@code null} for
     *                          all volumes
     * @param flags Control permission for which apps should be updated
     * @param callback Callback to call after permission changes
     */
    private void updatePermissions(final @Nullable String changingPkgName,
            final @Nullable AndroidPackage changingPkg,
            final @Nullable String replaceVolumeUuid,
            @UpdatePermissionFlags int flags,
            final @Nullable PermissionCallback callback) {
        // TODO: Most of the methods exposing BasePermission internals [source package name,
        // etc..] shouldn't be needed. Instead, when we've parsed a permission that doesn't
        // have package settings, we should make note of it elsewhere [map between
        // source package name and BasePermission] and cycle through that here. Then we
        // define a single method on BasePermission that takes a PackageSetting, changing
        // package name and a package.
        // NOTE: With this approach, we also don't need to tree trees differently than
        // normal permissions. Today, we need two separate loops because these BasePermission
        // objects are stored separately.
        // Make sure there are no dangling permission trees.
        boolean permissionTreesSourcePackageChanged = updatePermissionTreeSourcePackage(
                changingPkgName, changingPkg);
        // Make sure all dynamic permissions have been assigned to a package,
        // and make sure there are no dangling permissions.
        boolean permissionSourcePackageChanged = updatePermissionSourcePackage(changingPkgName,
                changingPkg, callback);

        if (permissionTreesSourcePackageChanged | permissionSourcePackageChanged) {
            // Permission ownership has changed. This e.g. changes which packages can get signature
            // permissions
            Slog.i(TAG, "Permission ownership changed. Updating all permissions.");
            flags |= UPDATE_PERMISSIONS_ALL;
        }

        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "restorePermissionState");
        // Now update the permissions for all packages.
        if ((flags & UPDATE_PERMISSIONS_ALL) != 0) {
            final boolean replaceAll = ((flags & UPDATE_PERMISSIONS_REPLACE_ALL) != 0);
            mPackageManagerInt.forEachPackage((AndroidPackage pkg) -> {
                if (pkg == changingPkg) {
                    return;
                }
                // Only replace for packages on requested volume
                final String volumeUuid = getVolumeUuidForPackage(pkg);
                final boolean replace = replaceAll && Objects.equals(replaceVolumeUuid, volumeUuid);
                restorePermissionState(pkg, replace, changingPkgName, callback,
                        UserHandle.USER_ALL);
            });
        }

        if (changingPkg != null) {
            // Only replace for packages on requested volume
            final String volumeUuid = getVolumeUuidForPackage(changingPkg);
            final boolean replace = ((flags & UPDATE_PERMISSIONS_REPLACE_PKG) != 0)
                    && Objects.equals(replaceVolumeUuid, volumeUuid);
            restorePermissionState(changingPkg, replace, changingPkgName, callback,
                    UserHandle.USER_ALL);
        }
        Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
    }

    /**
     * Update which app declares a permission.
     *
     * <p>Possible parameter combinations
     * <table>
     *     <tr><th></th><th>packageName != null</th><th>packageName == null</th></tr>
     *     <tr><th>pkg != null</th><td>package is updated</td><td>invalid</td></tr>
     *     <tr><th>pkg == null</th><td>package is deleted</td><td>all packages are updated</td></tr>
     * </table>
     *
     * @param packageName The package that is updated, or {@code null} if all packages should be
     *                    updated
     * @param pkg The package that is updated, or {@code null} if all packages should be updated or
     *            package is deleted
     *
     * @return {@code true} if a permission source package might have changed
     */
    private boolean updatePermissionSourcePackage(@Nullable String packageName,
            @Nullable AndroidPackage pkg,
            final @Nullable PermissionCallback callback) {
        // Always need update if packageName is null
        if (packageName == null) {
            return true;
        }

        boolean changed = false;
        Set<Permission> needsUpdate = null;
        synchronized (mLock) {
            for (final Permission bp : mRegistry.getPermissions()) {
                if (bp.isDynamic()) {
                    bp.updateDynamicPermission(mRegistry.getPermissionTrees());
                }
                if (!packageName.equals(bp.getPackageName())) {
                    // Not checking sourcePackageSetting because it can be null when
                    // the permission source package is the target package and the target package is
                    // being uninstalled,
                    continue;
                }
                // The target package is the source of the current permission
                // Set to changed for either install or uninstall
                changed = true;
                if (needsUpdate == null) {
                    needsUpdate = new ArraySet<>();
                }
                needsUpdate.add(bp);
            }
        }
        if (needsUpdate != null) {
            for (final Permission bp : needsUpdate) {
                // If the target package is being uninstalled, we need to revoke this permission
                // From all other packages
                if (pkg == null || !hasPermission(pkg, bp.getName())) {
                    if (!isPermissionDeclaredByDisabledSystemPkg(bp)) {
                        Slog.i(TAG, "Removing permission " + bp.getName()
                                + " that used to be declared by " + bp.getPackageName());
                        if (bp.isRuntime()) {
                            final int[] userIds = mUserManagerInt.getUserIds();
                            final int numUserIds = userIds.length;
                            for (int userIdNum = 0; userIdNum < numUserIds; userIdNum++) {
                                final int userId = userIds[userIdNum];
                                mPackageManagerInt.forEachPackage((AndroidPackage p) ->
                                        revokePermissionFromPackageForUser(p.getPackageName(),
                                                bp.getName(), true, userId, callback));
                            }
                        } else {
                            mPackageManagerInt.forEachPackage(p -> {
                                final int[] userIds = mUserManagerInt.getUserIds();
                                synchronized (mLock) {
                                    for (final int userId : userIds) {
                                        final UidPermissionState uidState = getUidStateLocked(p,
                                                userId);
                                        if (uidState == null) {
                                            Slog.e(TAG, "Missing permissions state for "
                                                    + p.getPackageName() + " and user " + userId);
                                            continue;
                                        }
                                        uidState.removePermissionState(bp.getName());
                                    }
                                }
                            });
                        }
                    }
                    synchronized (mLock) {
                        mRegistry.removePermission(bp.getName());
                    }
                    continue;
                }
                final AndroidPackage sourcePkg =
                        mPackageManagerInt.getPackage(bp.getPackageName());
                final PackageSetting sourcePs =
                        (PackageSetting) mPackageManagerInt.getPackageSetting(
                                bp.getPackageName());
                synchronized (mLock) {
                    if (sourcePkg != null && sourcePs != null) {
                        continue;
                    }
                    Slog.w(TAG, "Removing dangling permission: " + bp.getName()
                            + " from package " + bp.getPackageName());
                    mRegistry.removePermission(bp.getName());
                }
            }
        }
        return changed;
    }

    private boolean isPermissionDeclaredByDisabledSystemPkg(@NonNull Permission permission) {
        final PackageSetting disabledSourcePs = mPackageManagerInt.getDisabledSystemPackage(
                    permission.getPackageName());
        if (disabledSourcePs != null && disabledSourcePs.getPkg() != null) {
            final String permissionName = permission.getName();
            final List<ParsedPermission> sourcePerms = disabledSourcePs.getPkg().getPermissions();
            for (ParsedPermission sourcePerm : sourcePerms) {
                if (TextUtils.equals(permissionName, sourcePerm.getName())
                        && permission.getProtectionLevel() == sourcePerm.getProtectionLevel()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Revoke a runtime permission from a package for a given user ID.
     */
    private void revokePermissionFromPackageForUser(@NonNull String pName,
            @NonNull String permissionName, boolean overridePolicy, int userId,
            @Nullable PermissionCallback callback) {
        final ApplicationInfo appInfo =
                mPackageManagerInt.getApplicationInfo(pName, 0,
                        Process.SYSTEM_UID, UserHandle.USER_SYSTEM);
        if (appInfo != null
                && appInfo.targetSdkVersion < Build.VERSION_CODES.M) {
            return;
        }

        if (checkPermissionImpl(pName, permissionName, userId)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                revokeRuntimePermissionInternal(
                        pName, permissionName,
                        overridePolicy,
                        Process.SYSTEM_UID,
                        userId,
                        null, callback);
            } catch (IllegalArgumentException e) {
                Slog.e(TAG,
                        "Failed to revoke "
                                + permissionName
                                + " from "
                                + pName,
                        e);
            }
        }
    }
    /**
     * Update which app owns a permission trees.
     *
     * <p>Possible parameter combinations
     * <table>
     *     <tr><th></th><th>packageName != null</th><th>packageName == null</th></tr>
     *     <tr><th>pkg != null</th><td>package is updated</td><td>invalid</td></tr>
     *     <tr><th>pkg == null</th><td>package is deleted</td><td>all packages are updated</td></tr>
     * </table>
     *
     * @param packageName The package that is updated, or {@code null} if all packages should be
     *                    updated
     * @param pkg The package that is updated, or {@code null} if all packages should be updated or
     *            package is deleted
     *
     * @return {@code true} if a permission tree ownership might have changed
     */
    private boolean updatePermissionTreeSourcePackage(@Nullable String packageName,
            @Nullable AndroidPackage pkg) {
        // Always need update if packageName is null
        if (packageName == null) {
            return true;
        }
        boolean changed = false;

        Set<Permission> needsUpdate = null;
        synchronized (mLock) {
            final Iterator<Permission> it = mRegistry.getPermissionTrees().iterator();
            while (it.hasNext()) {
                final Permission bp = it.next();
                if (!packageName.equals(bp.getPackageName())) {
                    // Not checking sourcePackageSetting because it can be null when
                    // the permission source package is the target package and the target package is
                    // being uninstalled,
                    continue;
                }
                // The target package is the source of the current permission tree
                // Set to changed for either install or uninstall
                changed = true;
                if (pkg == null || !hasPermission(pkg, bp.getName())) {
                    Slog.i(TAG, "Removing permission tree " + bp.getName()
                            + " that used to be declared by " + bp.getPackageName());
                    it.remove();
                }
                if (needsUpdate == null) {
                    needsUpdate = new ArraySet<>();
                }
                needsUpdate.add(bp);
            }
        }
        if (needsUpdate != null) {
            for (final Permission bp : needsUpdate) {
                final AndroidPackage sourcePkg =
                        mPackageManagerInt.getPackage(bp.getPackageName());
                final PackageSetting sourcePs =
                        (PackageSetting) mPackageManagerInt.getPackageSetting(
                                bp.getPackageName());
                synchronized (mLock) {
                    if (sourcePkg != null && sourcePs != null) {
                        continue;
                    }
                    Slog.w(TAG, "Removing dangling permission tree: " + bp.getName()
                            + " from package " + bp.getPackageName());
                    mRegistry.removePermission(bp.getName());
                }
            }
        }
        return changed;
    }

    private void enforceGrantRevokeRuntimePermissionPermissions(String message) {
        if (mContext.checkCallingOrSelfPermission(Manifest.permission.GRANT_RUNTIME_PERMISSIONS)
                != PackageManager.PERMISSION_GRANTED
            && mContext.checkCallingOrSelfPermission(Manifest.permission.REVOKE_RUNTIME_PERMISSIONS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(message + " requires "
                    + Manifest.permission.GRANT_RUNTIME_PERMISSIONS + " or "
                    + Manifest.permission.REVOKE_RUNTIME_PERMISSIONS);
        }
    }

    private void enforceGrantRevokeGetRuntimePermissionPermissions(@NonNull String message) {
        if (mContext.checkCallingOrSelfPermission(Manifest.permission.GET_RUNTIME_PERMISSIONS)
                != PackageManager.PERMISSION_GRANTED
            && mContext.checkCallingOrSelfPermission(Manifest.permission.GRANT_RUNTIME_PERMISSIONS)
                != PackageManager.PERMISSION_GRANTED
            && mContext.checkCallingOrSelfPermission(Manifest.permission.REVOKE_RUNTIME_PERMISSIONS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(message + " requires "
                    + Manifest.permission.GRANT_RUNTIME_PERMISSIONS + " or "
                    + Manifest.permission.REVOKE_RUNTIME_PERMISSIONS + " or "
                    + Manifest.permission.GET_RUNTIME_PERMISSIONS);
        }
    }

    /**
     * Enforces the request is from the system or an app that has INTERACT_ACROSS_USERS
     * or INTERACT_ACROSS_USERS_FULL permissions, if the {@code userId} is not for the caller.
     *
     * @param checkShell whether to prevent shell from access if there's a debugging restriction
     * @param message the message to log on security exception
     */
    private void enforceCrossUserPermission(int callingUid, @UserIdInt int userId,
            boolean requireFullPermission, boolean checkShell, @Nullable String message) {
        if (userId < 0) {
            throw new IllegalArgumentException("Invalid userId " + userId);
        }
        if (checkShell) {
            enforceShellRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES, callingUid, userId);
        }
        final int callingUserId = UserHandle.getUserId(callingUid);
        if (checkCrossUserPermission(callingUid, callingUserId, userId, requireFullPermission)) {
            return;
        }
        String errorMessage = buildInvalidCrossUserPermissionMessage(
                callingUid, userId, message, requireFullPermission);
        Slog.w(TAG, errorMessage);
        throw new SecurityException(errorMessage);
    }

    /**
     *  Enforces that if the caller is shell, it does not have the provided user restriction.
     */
    private void enforceShellRestriction(@NonNull String restriction, int callingUid,
            @UserIdInt int userId) {
        if (callingUid == Process.SHELL_UID) {
            if (userId >= 0 && mUserManagerInt.hasUserRestriction(restriction, userId)) {
                throw new SecurityException("Shell does not have permission to access user "
                        + userId);
            } else if (userId < 0) {
                Slog.e(LOG_TAG, "Unable to check shell permission for user "
                        + userId + "\n\t" + Debug.getCallers(3));
            }
        }
    }

    private boolean checkCrossUserPermission(int callingUid, @UserIdInt int callingUserId,
            @UserIdInt int userId, boolean requireFullPermission) {
        if (userId == callingUserId) {
            return true;
        }
        if (callingUid == Process.SYSTEM_UID || callingUid == Process.ROOT_UID) {
            return true;
        }
        if (requireFullPermission) {
            return checkCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS_FULL);
        }
        return checkCallingOrSelfPermission(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)
                || checkCallingOrSelfPermission(android.Manifest.permission.INTERACT_ACROSS_USERS);
    }

    private boolean checkCallingOrSelfPermission(String permission) {
        return mContext.checkCallingOrSelfPermission(permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    @NonNull
    private static String buildInvalidCrossUserPermissionMessage(int callingUid,
            @UserIdInt int userId, @Nullable String message, boolean requireFullPermission) {
        StringBuilder builder = new StringBuilder();
        if (message != null) {
            builder.append(message);
            builder.append(": ");
        }
        builder.append("UID ");
        builder.append(callingUid);
        builder.append(" requires ");
        builder.append(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL);
        if (!requireFullPermission) {
            builder.append(" or ");
            builder.append(android.Manifest.permission.INTERACT_ACROSS_USERS);
        }
        builder.append(" to access user ");
        builder.append(userId);
        builder.append(".");
        return builder.toString();
    }

    @GuardedBy("mLock")
    private int calculateCurrentPermissionFootprintLocked(@NonNull Permission permissionTree) {
        int size = 0;
        for (final Permission permission : mRegistry.getPermissions()) {
            size += permissionTree.calculateFootprint(permission);
        }
        return size;
    }

    @GuardedBy("mLock")
    private void enforcePermissionCapLocked(PermissionInfo info, Permission tree) {
        // We calculate the max size of permissions defined by this uid and throw
        // if that plus the size of 'info' would exceed our stated maximum.
        if (tree.getUid() != Process.SYSTEM_UID) {
            final int curTreeSize = calculateCurrentPermissionFootprintLocked(tree);
            if (curTreeSize + info.calculateFootprint() > MAX_PERMISSION_TREE_FOOTPRINT) {
                throw new SecurityException("Permission tree size cap exceeded");
            }
        }
    }

    private void systemReady() {
        // Now that we've scanned all packages, and granted any default
        // permissions, ensure permissions are updated. Beware of dragons if you
        // try optimizing this.
        updateAllPermissions(StorageManager.UUID_PRIVATE_INTERNAL, false);

        final PermissionPolicyInternal permissionPolicyInternal = LocalServices.getService(
                PermissionPolicyInternal.class);
        permissionPolicyInternal.setOnInitializedCallback(userId ->
                // The SDK updated case is already handled when we run during the ctor.
                updateAllPermissions(StorageManager.UUID_PRIVATE_INTERNAL, false)
        );

        mSystemReady = true;

        synchronized (mLock) {
            if (mPrivappPermissionsViolations != null) {
                throw new IllegalStateException("Signature|privileged permissions not in "
                        + "privapp-permissions allowlist: " + mPrivappPermissionsViolations);
            }
        }

        mPermissionControllerManager = mContext.getSystemService(PermissionControllerManager.class);
        mPermissionPolicyInternal = LocalServices.getService(PermissionPolicyInternal.class);
    }

    private static String getVolumeUuidForPackage(AndroidPackage pkg) {
        if (pkg == null) {
            return StorageManager.UUID_PRIVATE_INTERNAL;
        }
        if (pkg.isExternalStorage()) {
            if (TextUtils.isEmpty(pkg.getVolumeUuid())) {
                return StorageManager.UUID_PRIMARY_PHYSICAL;
            } else {
                return pkg.getVolumeUuid();
            }
        } else {
            return StorageManager.UUID_PRIVATE_INTERNAL;
        }
    }

    private static boolean hasPermission(AndroidPackage pkg, String permName) {
        if (pkg.getPermissions().isEmpty()) {
            return false;
        }

        for (int i = pkg.getPermissions().size() - 1; i >= 0; i--) {
            if (pkg.getPermissions().get(i).getName().equals(permName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Log that a permission request was granted/revoked.
     *
     * @param action the action performed
     * @param name name of the permission
     * @param packageName package permission is for
     */
    private void logPermission(int action, @NonNull String name, @NonNull String packageName) {
        final LogMaker log = new LogMaker(action);
        log.setPackageName(packageName);
        log.addTaggedData(MetricsEvent.FIELD_PERMISSION, name);

        mMetricsLogger.write(log);
    }

    @GuardedBy("mLock")
    @Nullable
    private UidPermissionState getUidStateLocked(@NonNull PackageSetting ps,
            @UserIdInt int userId) {
        return getUidStateLocked(ps.getAppId(), userId);
    }

    @GuardedBy("mLock")
    @Nullable
    private UidPermissionState getUidStateLocked(@NonNull AndroidPackage pkg,
            @UserIdInt int userId) {
        return getUidStateLocked(pkg.getUid(), userId);
    }

    @GuardedBy("mLock")
    @Nullable
    private UidPermissionState getUidStateLocked(@AppIdInt int appId, @UserIdInt int userId) {
        final UserPermissionState userState = mState.getUserState(userId);
        if (userState == null) {
            return null;
        }
        return userState.getUidState(appId);
    }

    private void removeUidStateAndResetPackageInstallPermissionsFixed(@AppIdInt int appId,
            @NonNull String packageName, @UserIdInt int userId) {
        synchronized (mLock) {
            final UserPermissionState userState = mState.getUserState(userId);
            if (userState == null) {
                return;
            }
            userState.removeUidState(appId);
            userState.setInstallPermissionsFixed(packageName, false);
        }
    }

    private void readLegacyPermissionState() {
        final int[] userIds = getAllUserIds();
        mPackageManagerInt.forEachPackageSetting(ps -> {
            final int appId = ps.getAppId();
            final LegacyPermissionState legacyState = ps.getLegacyPermissionState();

            synchronized (mLock) {
                for (final int userId : userIds) {
                    final UserPermissionState userState = mState.getOrCreateUserState(userId);

                    userState.setInstallPermissionsFixed(ps.name, ps.areInstallPermissionsFixed());
                    final UidPermissionState uidState = userState.getOrCreateUidState(appId);
                    uidState.reset();
                    uidState.setMissing(legacyState.isMissing(userId));
                    readLegacyPermissionStatesLocked(uidState,
                            legacyState.getPermissionStates(userId));
                }
            }
        });
    }

    @GuardedBy("mLock")
    private void readLegacyPermissionStatesLocked(@NonNull UidPermissionState uidState,
            @NonNull Collection<LegacyPermissionState.PermissionState> permissionStates) {
        for (final LegacyPermissionState.PermissionState permissionState : permissionStates) {
            final String permissionName = permissionState.getName();
            final Permission permission = mRegistry.getPermission(permissionName);
            if (permission == null) {
                Slog.w(TAG, "Unknown permission: " + permissionName);
                continue;
            }
            uidState.putPermissionState(permission, permissionState.isGranted(),
                    permissionState.getFlags());
        }
    }

    private void writeLegacyPermissionState() {
        final int[] userIds;
        synchronized (mLock) {
            userIds = mState.getUserIds();
        }
        mPackageManagerInt.forEachPackageSetting(ps -> {
            ps.setInstallPermissionsFixed(false);
            final LegacyPermissionState legacyState = ps.getLegacyPermissionState();
            legacyState.reset();
            final int appId = ps.getAppId();

            synchronized (mLock) {
                for (final int userId : userIds) {
                    final UserPermissionState userState = mState.getUserState(userId);
                    if (userState == null) {
                        Slog.e(TAG, "Missing user state for " + userId);
                        continue;
                    }

                    if (userState.areInstallPermissionsFixed(ps.name)) {
                        ps.setInstallPermissionsFixed(true);
                    }

                    final UidPermissionState uidState = userState.getUidState(appId);
                    if (uidState == null) {
                        Slog.e(TAG, "Missing permission state for " + ps.name + " and user "
                                + userId);
                        continue;
                    }

                    legacyState.setMissing(uidState.isMissing(), userId);
                    final List<PermissionState> permissionStates = uidState.getPermissionStates();
                    final int permissionStatesSize = permissionStates.size();
                    for (int i = 0; i < permissionStatesSize; i++) {
                        final PermissionState permissionState = permissionStates.get(i);

                        final LegacyPermissionState.PermissionState legacyPermissionState =
                                new LegacyPermissionState.PermissionState(permissionState.getName(),
                                        permissionState.getPermission().isRuntime(),
                                        permissionState.isGranted(), permissionState.getFlags());
                        legacyState.putPermissionState(legacyPermissionState, userId);
                    }
                }
            }
        });
    }

    private void readLegacyPermissions(@NonNull LegacyPermissionSettings legacyPermissionSettings) {
        for (int readPermissionOrPermissionTree = 0; readPermissionOrPermissionTree < 2;
                readPermissionOrPermissionTree++) {
            final List<LegacyPermission> legacyPermissions = readPermissionOrPermissionTree == 0
                    ? legacyPermissionSettings.getPermissions()
                    : legacyPermissionSettings.getPermissionTrees();
            synchronized (mLock) {
                final int legacyPermissionsSize = legacyPermissions.size();
                for (int i = 0; i < legacyPermissionsSize; i++) {
                    final LegacyPermission legacyPermission = legacyPermissions.get(i);
                    final Permission permission = new Permission(
                            legacyPermission.getPermissionInfo(), legacyPermission.getType());
                    if (readPermissionOrPermissionTree == 0) {
                        // Config permissions are currently read in PermissionManagerService
                        // constructor. The old behavior was to add other attributes to the config
                        // permission in LegacyPermission.read(), so equivalently we can add the
                        // GIDs to the new permissions here, since config permissions created in
                        // PermissionManagerService constructor get only their names and GIDs there.
                        final Permission configPermission = mRegistry.getPermission(
                                permission.getName());
                        if (configPermission != null
                                && configPermission.getType() == Permission.TYPE_CONFIG) {
                            permission.setGids(configPermission.getRawGids(),
                                    configPermission.areGidsPerUser());
                        }
                        mRegistry.addPermission(permission);
                    } else {
                        mRegistry.addPermissionTree(permission);
                    }
                }
            }
        }
    }

    private void writeLegacyPermissions(
            @NonNull LegacyPermissionSettings legacyPermissionSettings) {
        for (int writePermissionOrPermissionTree = 0; writePermissionOrPermissionTree < 2;
                writePermissionOrPermissionTree++) {
            final List<LegacyPermission> legacyPermissions = new ArrayList<>();
            synchronized (mLock) {
                final Collection<Permission> permissions = writePermissionOrPermissionTree == 0
                        ? mRegistry.getPermissions() : mRegistry.getPermissionTrees();
                for (final Permission permission : permissions) {
                    // We don't need to provide UID and GIDs, which are only retrieved when dumping.
                    final LegacyPermission legacyPermission = new LegacyPermission(
                            permission.getPermissionInfo(), permission.getType(), 0,
                            EmptyArray.INT);
                    legacyPermissions.add(legacyPermission);
                }
            }
            if (writePermissionOrPermissionTree == 0) {
                legacyPermissionSettings.replacePermissions(legacyPermissions);
            } else {
                legacyPermissionSettings.replacePermissionTrees(legacyPermissions);
            }
        }
    }

    private void onPackageAddedInternal(@NonNull AndroidPackage pkg, boolean isInstantApp,
            @Nullable AndroidPackage oldPkg) {
        if (!pkg.getAdoptPermissions().isEmpty()) {
            // This package wants to adopt ownership of permissions from
            // another package.
            for (int i = pkg.getAdoptPermissions().size() - 1; i >= 0; i--) {
                final String origName = pkg.getAdoptPermissions().get(i);
                if (canAdoptPermissionsInternal(origName, pkg)) {
                    Slog.i(TAG, "Adopting permissions from " + origName + " to "
                            + pkg.getPackageName());
                    synchronized (mLock) {
                        mRegistry.transferPermissions(origName, pkg.getPackageName());
                    }
                }
            }
        }

        // Don't allow ephemeral applications to define new permissions groups.
        if (isInstantApp) {
            Slog.w(TAG, "Permission groups from package " + pkg.getPackageName()
                    + " ignored: instant apps cannot define new permission groups.");
        } else {
            addAllPermissionGroupsInternal(pkg);
        }

        // If a permission has had its defining app changed, or it has had its protection
        // upgraded, we need to revoke apps that hold it
        final List<String> permissionsWithChangedDefinition;
        // Don't allow ephemeral applications to define new permissions.
        if (isInstantApp) {
            permissionsWithChangedDefinition = null;
            Slog.w(TAG, "Permissions from package " + pkg.getPackageName()
                    + " ignored: instant apps cannot define new permissions.");
        } else {
            permissionsWithChangedDefinition = addAllPermissionsInternal(pkg);
        }

        boolean hasOldPkg = oldPkg != null;
        boolean hasPermissionDefinitionChanges =
                !CollectionUtils.isEmpty(permissionsWithChangedDefinition);
        if (hasOldPkg || hasPermissionDefinitionChanges) {
            // We need to call revokeRuntimePermissionsIfGroupChanged async as permission
            // revoke callbacks from this method might need to kill apps which need the
            // mPackages lock on a different thread. This would dead lock.
            AsyncTask.execute(() -> {
                if (hasOldPkg) {
                    revokeRuntimePermissionsIfGroupChangedInternal(pkg, oldPkg);
                    revokeStoragePermissionsIfScopeExpandedInternal(pkg, oldPkg);
                }
                if (hasPermissionDefinitionChanges) {
                    revokeRuntimePermissionsIfPermissionDefinitionChangedInternal(
                            permissionsWithChangedDefinition);
                }
            });
        }
    }

    private boolean canAdoptPermissionsInternal(@NonNull String oldPackageName,
            @NonNull AndroidPackage newPkg) {
        final PackageSetting oldPs = mPackageManagerInt.getPackageSetting(oldPackageName);
        if (oldPs == null) {
            return false;
        }
        if (!oldPs.isSystem()) {
            Slog.w(TAG, "Unable to update from " + oldPs.name
                    + " to " + newPkg.getPackageName()
                    + ": old package not in system partition");
            return false;
        }
        if (mPackageManagerInt.getPackage(oldPs.name) != null) {
            Slog.w(TAG, "Unable to update from " + oldPs.name
                    + " to " + newPkg.getPackageName()
                    + ": old package still exists");
            return false;
        }
        return true;
    }

    private void onPackageInstalledInternal(@NonNull AndroidPackage pkg,
            @NonNull PermissionManagerServiceInternal.PackageInstalledParams params,
            @UserIdInt int[] userIds) {
        updatePermissions(pkg.getPackageName(), pkg);
        for (final int userId : userIds) {
            addAllowlistedRestrictedPermissionsInternal(pkg,
                    params.getAllowlistedRestrictedPermissions(),
                    FLAG_PERMISSION_WHITELIST_INSTALLER, userId);
            final int autoRevokePermissionsMode = params.getAutoRevokePermissionsMode();
            if (autoRevokePermissionsMode == AppOpsManager.MODE_ALLOWED
                    || autoRevokePermissionsMode == AppOpsManager.MODE_IGNORED) {
                setAutoRevokeExemptedInternal(pkg,
                        autoRevokePermissionsMode == AppOpsManager.MODE_IGNORED, userId);
            }
            grantRequestedRuntimePermissionsInternal(pkg, params.getGrantedPermissions(), userId);
        }
    }

    private void addAllowlistedRestrictedPermissionsInternal(@NonNull AndroidPackage pkg,
            @NonNull List<String> allowlistedRestrictedPermissions,
            @PermissionWhitelistFlags int flags, @UserIdInt int userId) {
        List<String> permissions = getAllowlistedRestrictedPermissionsInternal(pkg, flags, userId);
        if (permissions != null) {
            ArraySet<String> permissionSet = new ArraySet<>(permissions);
            permissionSet.addAll(allowlistedRestrictedPermissions);
            permissions = new ArrayList<>(permissionSet);
        } else {
            permissions = allowlistedRestrictedPermissions;
        }
        setAllowlistedRestrictedPermissionsInternal(pkg, permissions, flags, userId);
    }

    private void onPackageRemovedInternal(@NonNull AndroidPackage pkg) {
        removeAllPermissionsInternal(pkg);
    }

    private void onPackageUninstalledInternal(@NonNull String packageName, int appId,
            @Nullable AndroidPackage pkg, @NonNull List<AndroidPackage> sharedUserPkgs,
            @UserIdInt int[] userIds) {
        // TODO: Move these checks to check PackageState to be more reliable.
        // System packages should always have an available APK.
        if (pkg != null && pkg.isSystem()
                // We may be fully removing invalid system packages during boot, and in that case we
                // do want to remove their permission state. So make sure that the package is only
                // being marked as uninstalled instead of fully removed.
                && mPackageManagerInt.getPackage(packageName) != null) {
            // If we are only marking a system package as uninstalled, we need to keep its
            // pregranted permission state so that it still works once it gets reinstalled, thus
            // only reset the user modifications to its permission state.
            for (final int userId : userIds) {
                resetRuntimePermissionsInternal(pkg, userId);
            }
            return;
        }
        updatePermissions(packageName, null);
        for (final int userId : userIds) {
            if (sharedUserPkgs.isEmpty()) {
                removeUidStateAndResetPackageInstallPermissionsFixed(appId, packageName, userId);
            } else {
                // Remove permissions associated with package. Since runtime
                // permissions are per user we have to kill the removed package
                // or packages running under the shared user of the removed
                // package if revoking the permissions requested only by the removed
                // package is successful and this causes a change in gids.
                final int userIdToKill = revokeSharedUserPermissionsForDeletedPackageInternal(pkg,
                        sharedUserPkgs, userId);
                final boolean shouldKill = userIdToKill != UserHandle.USER_NULL;
                // If gids changed, kill all affected packages.
                if (shouldKill) {
                    mHandler.post(() -> {
                        // This has to happen with no lock held.
                        killUid(appId, UserHandle.USER_ALL, KILL_APP_REASON_GIDS_CHANGED);
                    });
                }
            }
        }
    }

    @NonNull
    private List<LegacyPermission> getLegacyPermissions() {
        synchronized (mLock) {
            final List<LegacyPermission> legacyPermissions = new ArrayList<>();
            for (final Permission permission : mRegistry.getPermissions()) {
                final LegacyPermission legacyPermission = new LegacyPermission(
                        permission.getPermissionInfo(), permission.getType(), permission.getUid(),
                        permission.getRawGids());
                legacyPermissions.add(legacyPermission);
            }
            return legacyPermissions;
        }
    }

    @NonNull
    private Map<String, Set<String>> getAllAppOpPermissionPackages() {
        synchronized (mLock) {
            final ArrayMap<String, ArraySet<String>> appOpPermissionPackages =
                    mRegistry.getAllAppOpPermissionPackages();
            final Map<String, Set<String>> deepClone = new ArrayMap<>();
            final int appOpPermissionPackagesSize = appOpPermissionPackages.size();
            for (int i = 0; i < appOpPermissionPackagesSize; i++) {
                final String appOpPermission = appOpPermissionPackages.keyAt(i);
                final ArraySet<String> packageNames = appOpPermissionPackages.valueAt(i);
                deepClone.put(appOpPermission, new ArraySet<>(packageNames));
            }
            return deepClone;
        }
    }

    @NonNull
    private LegacyPermissionState getLegacyPermissionState(@AppIdInt int appId) {
        final LegacyPermissionState legacyState = new LegacyPermissionState();
        synchronized (mLock) {
            final int[] userIds = mState.getUserIds();
            for (final int userId : userIds) {
                final UidPermissionState uidState = getUidStateLocked(appId, userId);
                if (uidState == null) {
                    Slog.e(TAG, "Missing permissions state for app ID " + appId + " and user ID "
                            + userId);
                    continue;
                }

                final List<PermissionState> permissionStates = uidState.getPermissionStates();
                final int permissionStatesSize = permissionStates.size();
                for (int i = 0; i < permissionStatesSize; i++) {
                    final PermissionState permissionState = permissionStates.get(i);

                    final LegacyPermissionState.PermissionState legacyPermissionState =
                            new LegacyPermissionState.PermissionState(permissionState.getName(),
                                    permissionState.getPermission().isRuntime(),
                                    permissionState.isGranted(), permissionState.getFlags());
                    legacyState.putPermissionState(legacyPermissionState, userId);
                }
            }
        }
        return legacyState;
    }

    @NonNull
    private int[] getGidsForUid(int uid) {
        final int appId = UserHandle.getAppId(uid);
        final int userId = UserHandle.getUserId(uid);
        synchronized (mLock) {
            final UidPermissionState uidState = getUidStateLocked(appId, userId);
            if (uidState == null) {
                Slog.e(TAG, "Missing permissions state for app ID " + appId + " and user ID "
                        + userId);
                return EMPTY_INT_ARRAY;
            }
            return uidState.computeGids(mGlobalGids, userId);
        }
    }

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
        public void onSystemReady() {
            PermissionManagerService.this.systemReady();
        }

        @Override
        public boolean isPermissionsReviewRequired(@NonNull String packageName,
                @UserIdInt int userId) {
            Objects.requireNonNull(packageName, "packageName");
            // TODO(b/173235285): Some caller may pass USER_ALL as userId.
            //Preconditions.checkArgumentNonnegative(userId, "userId");
            return isPermissionsReviewRequiredInternal(packageName, userId);
        }

        @Override
        public void readLegacyPermissionStateTEMP() {
            PermissionManagerService.this.readLegacyPermissionState();
        }
        @Override
        public void writeLegacyPermissionStateTEMP() {
            PermissionManagerService.this.writeLegacyPermissionState();
        }
        @Override
        public void onUserRemoved(@UserIdInt int userId) {
            Preconditions.checkArgumentNonNegative(userId, "userId");
            PermissionManagerService.this.onUserRemoved(userId);
        }
        @NonNull
        @Override
        public Set<String> getGrantedPermissions(@NonNull String packageName,
                @UserIdInt int userId) {
            Objects.requireNonNull(packageName, "packageName");
            Preconditions.checkArgumentNonNegative(userId, "userId");
            return getGrantedPermissionsInternal(packageName, userId);
        }
        @NonNull
        @Override
        public int[] getPermissionGids(@NonNull String permissionName, @UserIdInt int userId) {
            Objects.requireNonNull(permissionName, "permissionName");
            Preconditions.checkArgumentNonNegative(userId, "userId");
            return getPermissionGidsInternal(permissionName, userId);
        }
        @NonNull
        @Override
        public String[] getAppOpPermissionPackages(@NonNull String permissionName) {
            Objects.requireNonNull(permissionName, "permissionName");
            return PermissionManagerService.this.getAppOpPermissionPackagesInternal(permissionName);
        }
        @Override
        public void onStorageVolumeMounted(@Nullable String volumeUuid, boolean fingerprintChanged) {
            updateAllPermissions(volumeUuid, fingerprintChanged);
        }
        @Override
        public void resetRuntimePermissions(@NonNull AndroidPackage pkg, @UserIdInt int userId) {
            Objects.requireNonNull(pkg, "pkg");
            Preconditions.checkArgumentNonNegative(userId, "userId");
            resetRuntimePermissionsInternal(pkg, userId);
        }

        @Override
        public Permission getPermissionTEMP(String permName) {
            synchronized (mLock) {
                return mRegistry.getPermission(permName);
            }
        }

        @Override
        public @NonNull ArrayList<PermissionInfo> getAllPermissionsWithProtection(
                @PermissionInfo.Protection int protection) {
            ArrayList<PermissionInfo> matchingPermissions = new ArrayList<>();

            synchronized (mLock) {
                for (final Permission permission : mRegistry.getPermissions()) {
                    if (permission.getProtection() == protection) {
                        matchingPermissions.add(permission.generatePermissionInfo(0));
                    }
                }
            }

            return matchingPermissions;
        }

        @Override
        public @NonNull ArrayList<PermissionInfo> getAllPermissionsWithProtectionFlags(
                @PermissionInfo.ProtectionFlags int protectionFlags) {
            ArrayList<PermissionInfo> matchingPermissions = new ArrayList<>();

            synchronized (mLock) {
                for (final Permission permission : mRegistry.getPermissions()) {
                    if ((permission.getProtectionFlags() & protectionFlags) == protectionFlags) {
                        matchingPermissions.add(permission.generatePermissionInfo(0));
                    }
                }
            }

            return matchingPermissions;
        }

        @Nullable
        @Override
        public byte[] backupRuntimePermissions(@UserIdInt int userId) {
            Preconditions.checkArgumentNonNegative(userId, "userId");
            return PermissionManagerService.this.backupRuntimePermissions(userId);
        }

        @Override
        public void restoreRuntimePermissions(@NonNull byte[] backup, @UserIdInt int userId) {
            Objects.requireNonNull(backup, "backup");
            Preconditions.checkArgumentNonNegative(userId, "userId");
            PermissionManagerService.this.restoreRuntimePermissions(backup, userId);
        }

        @Override
        public void restoreDelayedRuntimePermissions(@NonNull String packageName,
                @UserIdInt int userId) {
            Objects.requireNonNull(packageName, "packageName");
            Preconditions.checkArgumentNonNegative(userId, "userId");
            PermissionManagerService.this.restoreDelayedRuntimePermissions(packageName, userId);
        }

        @Override
        public void addOnRuntimePermissionStateChangedListener(
                OnRuntimePermissionStateChangedListener listener) {
            PermissionManagerService.this.addOnRuntimePermissionStateChangedListener(
                    listener);
        }

        @Override
        public void removeOnRuntimePermissionStateChangedListener(
                OnRuntimePermissionStateChangedListener listener) {
            PermissionManagerService.this.removeOnRuntimePermissionStateChangedListener(
                    listener);
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
        public void onUserCreated(@UserIdInt int userId) {
            Preconditions.checkArgumentNonNegative(userId, "userId");
            // NOTE: This adds UPDATE_PERMISSIONS_REPLACE_PKG
            updateAllPermissions(StorageManager.UUID_PRIVATE_INTERNAL, true);
        }

        @Override
        public void readLegacyPermissionsTEMP(
                @NonNull LegacyPermissionSettings legacyPermissionSettings) {
            PermissionManagerService.this.readLegacyPermissions(legacyPermissionSettings);
        }

        @Override
        public void writeLegacyPermissionsTEMP(
                @NonNull LegacyPermissionSettings legacyPermissionSettings) {
            PermissionManagerService.this.writeLegacyPermissions(legacyPermissionSettings);
        }

        @Override
        public void onPackageAdded(@NonNull AndroidPackage pkg, boolean isInstantApp,
                @Nullable AndroidPackage oldPkg) {
            Objects.requireNonNull(pkg);
            onPackageAddedInternal(pkg, isInstantApp, oldPkg);
        }

        @Override
        public void onPackageInstalled(@NonNull AndroidPackage pkg,
                @NonNull PackageInstalledParams params, @UserIdInt int userId) {
            Objects.requireNonNull(pkg, "pkg");
            Objects.requireNonNull(params, "params");
            Preconditions.checkArgument(userId >= UserHandle.USER_SYSTEM
                    || userId == UserHandle.USER_ALL, "userId");
            final int[] userIds = userId == UserHandle.USER_ALL ? getAllUserIds()
                    : new int[] { userId };
            onPackageInstalledInternal(pkg, params, userIds);
        }

        @Override
        public void onPackageRemoved(@NonNull AndroidPackage pkg) {
            Objects.requireNonNull(pkg);
            onPackageRemovedInternal(pkg);
        }

        @Override
        public void onPackageUninstalled(@NonNull String packageName, int appId,
                @Nullable AndroidPackage pkg, @NonNull List<AndroidPackage> sharedUserPkgs,
                @UserIdInt int userId) {
            Objects.requireNonNull(packageName, "packageName");
            Objects.requireNonNull(sharedUserPkgs, "sharedUserPkgs");
            Preconditions.checkArgument(userId >= UserHandle.USER_SYSTEM
                    || userId == UserHandle.USER_ALL, "userId");
            final int[] userIds = userId == UserHandle.USER_ALL ? getAllUserIds()
                    : new int[] { userId };
            onPackageUninstalledInternal(packageName, appId, pkg, sharedUserPkgs, userIds);
        }

        @NonNull
        @Override
        public List<LegacyPermission> getLegacyPermissions() {
            return PermissionManagerService.this.getLegacyPermissions();
        }

        @NonNull
        @Override
        public Map<String, Set<String>> getAllAppOpPermissionPackages() {
            return PermissionManagerService.this.getAllAppOpPermissionPackages();
        }

        @NonNull
        @Override
        public LegacyPermissionState getLegacyPermissionState(@AppIdInt int appId) {
            return PermissionManagerService.this.getLegacyPermissionState(appId);
        }

        @NonNull
        @Override
        public int[] getGidsForUid(int uid) {
            return PermissionManagerService.this.getGidsForUid(uid);
        }

        @Override
        public void setHotwordDetectionServiceProvider(HotwordDetectionServiceProvider provider) {
            mHotwordDetectionServiceProvider = provider;
        }

        @Override
        public HotwordDetectionServiceProvider getHotwordDetectionServiceProvider() {
            return mHotwordDetectionServiceProvider;
        }
    }

    /**
     * Callbacks invoked when interesting actions have been taken on a permission.
     * <p>
     * NOTE: The current arguments are merely to support the existing use cases. This
     * needs to be properly thought out with appropriate arguments for each of the
     * callback methods.
     */
    private static class PermissionCallback {
        public void onGidsChanged(@AppIdInt int appId, @UserIdInt int userId) {}
        public void onPermissionChanged() {}
        public void onPermissionGranted(int uid, @UserIdInt int userId) {}
        public void onInstallPermissionGranted() {}
        public void onPermissionRevoked(int uid, @UserIdInt int userId, String reason) {}
        public void onInstallPermissionRevoked() {}
        public void onPermissionUpdated(@UserIdInt int[] updatedUserIds, boolean sync) {}
        public void onPermissionUpdatedNotifyListener(@UserIdInt int[] updatedUserIds, boolean sync,
                int uid) {
            onPermissionUpdated(updatedUserIds, sync);
        }
        public void onPermissionRemoved() {}
        public void onInstallPermissionUpdated() {}
        public void onInstallPermissionUpdatedNotifyListener(int uid) {
            onInstallPermissionUpdated();
        }
    }

    private static final class OnPermissionChangeListeners extends Handler {
        private static final int MSG_ON_PERMISSIONS_CHANGED = 1;

        private final RemoteCallbackList<IOnPermissionsChangeListener> mPermissionListeners =
                new RemoteCallbackList<>();

        OnPermissionChangeListeners(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_ON_PERMISSIONS_CHANGED: {
                    final int uid = msg.arg1;
                    handleOnPermissionsChanged(uid);
                } break;
            }
        }

        public void addListener(IOnPermissionsChangeListener listener) {
            mPermissionListeners.register(listener);
        }

        public void removeListener(IOnPermissionsChangeListener listener) {
            mPermissionListeners.unregister(listener);
        }

        public void onPermissionsChanged(int uid) {
            if (mPermissionListeners.getRegisteredCallbackCount() > 0) {
                obtainMessage(MSG_ON_PERMISSIONS_CHANGED, uid, 0).sendToTarget();
            }
        }

        private void handleOnPermissionsChanged(int uid) {
            final int count = mPermissionListeners.beginBroadcast();
            try {
                for (int i = 0; i < count; i++) {
                    IOnPermissionsChangeListener callback = mPermissionListeners
                            .getBroadcastItem(i);
                    try {
                        callback.onPermissionsChanged(uid);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Permission listener is dead", e);
                    }
                }
            } finally {
                mPermissionListeners.finishBroadcast();
            }
        }
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

            final int callingUid = Binder.getCallingUid();
            if (source.getUid() != callingUid && mContext.checkPermission(
                    Manifest.permission.UPDATE_APP_OPS_STATS, /*pid*/ -1, callingUid)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Cannot register attribution source for uid:"
                        + source.getUid() + " from uid:" + callingUid);
            }

            final PackageManagerInternal packageManagerInternal = LocalServices.getService(
                    PackageManagerInternal.class);
            if (packageManagerInternal.getPackageUid(source.getPackageName(), 0,
                    UserHandle.getUserId(callingUid)) != source.getUid()) {
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
                    appOpsManager.finishOp(accessorSource.getToken(), op,
                            accessorSource.getUid(), resolvedPackageName,
                            accessorSource.getAttributionTag());
                } else {
                    final AttributionSource resolvedAttributionSource =
                            resolveAttributionSource(context, accessorSource);
                    if (resolvedAttributionSource.getPackageName() == null) {
                        return;
                    }
                    appOpsManager.finishProxyOp(AppOpsManager.opToPublicName(op),
                            resolvedAttributionSource, skipCurrentFinish);
                }

                if (next == null || next.getNext() == null) {
                    return;
                }

                RegisteredAttribution registered =
                        sRunningAttributionSources.remove(current.getToken());
                if (registered != null) {
                    registered.unregister();
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
                    if (PLATFORM_PACKAGE_NAME.equals(permissionInfo.packageName)) {
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

                final int opMode = performOpTransaction(context, op, current, message,
                        forDataDelivery, /*startDataDelivery*/ false, skipCurrentChecks,
                        selfAccess, singleReceiverFromDatasource, AppOpsManager.OP_NONE,
                        AppOpsManager.ATTRIBUTION_FLAGS_NONE, AppOpsManager.ATTRIBUTION_FLAGS_NONE,
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
                    if (sPlatformPermissions.contains(permission)
                            && !Manifest.permission.ACCESS_BACKGROUND_LOCATION.equals(permission)) {
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

                final int opMode = performOpTransaction(context, op, current, message,
                        forDataDelivery, startDataDelivery, skipCurrentChecks, selfAccess,
                        singleReceiverFromDatasource, attributedOp, proxyAttributionFlags,
                        proxiedAttributionFlags, attributionChainId);

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

            // Override certain permissions checks for the HotwordDetectionService. This service is
            // an isolated service, which ordinarily cannot hold permissions.
            // There's probably a cleaner, more generalizable way to do this. For now, this is
            // the only use case for this, so simply override here.
            if (!permissionGranted
                    && Process.isIsolated(uid) // simple check which fails-fast for the common case
                    && (permission.equals(RECORD_AUDIO)
                    || permission.equals(CAPTURE_AUDIO_HOTWORD))) {
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

                final int opMode = performOpTransaction(context, op, current, message,
                        forDataDelivery, startDataDelivery, skipCurrentChecks, selfAccess,
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
        private static int performOpTransaction(@NonNull Context context, int op,
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
                                resolvedAttributionSource.getToken(), startedOp,
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
                        startedOpResult = appOpsManager.startProxyOpNoThrow(attributedOp,
                                attributionSource, message, skipProxyOperation,
                                proxyAttributionFlags, proxiedAttributionFlags, attributionChainId);
                    }
                } else {
                    startedOpResult = appOpsManager.startProxyOpNoThrow(startedOp,
                            resolvedAttributionSource, message, skipProxyOperation,
                            proxyAttributionFlags, proxiedAttributionFlags, attributionChainId);
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
                    notedOpResult = appOpsManager.noteProxyOpNoThrow(notedOp,
                            resolvedAttributionSource, message, skipProxyOperation);
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
