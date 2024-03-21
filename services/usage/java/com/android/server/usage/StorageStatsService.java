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

package com.android.server.usage;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import static com.android.internal.util.ArrayUtils.defeatNullable;
import static com.android.server.pm.DexOptHelper.getArtManagerLocal;
import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;
import static com.android.server.pm.PackageManagerServiceUtils.getPackageManagerLocal;
import static com.android.server.usage.StorageStatsManagerLocal.StorageStatsAugmenter;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppOpsManager;
import android.app.usage.ExternalStorageStats;
import android.app.usage.Flags;
import android.app.usage.IStorageStatsManager;
import android.app.usage.StorageStats;
import android.app.usage.UsageStatsManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageStats;
import android.content.pm.ParceledListSlice;
import android.content.pm.UserInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelableException;
import android.os.StatFs;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.CrateInfo;
import android.os.storage.CrateMetadata;
import android.os.storage.StorageEventListener;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.DataUnit;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseLongArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;
import com.android.server.art.ArtManagerLocal;
import com.android.server.art.model.ArtManagedFileStats;
import com.android.server.pm.PackageManagerLocal.FilteredSnapshot;
import com.android.server.IoThread;
import com.android.server.LocalManagerRegistry;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.pm.Installer;
import com.android.server.pm.Installer.InstallerException;
import com.android.server.storage.CacheQuotaStrategy;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class StorageStatsService extends IStorageStatsManager.Stub {
    private static final String TAG = "StorageStatsService";

    private static final String PROP_STORAGE_CRATES = "fw.storage_crates";
    private static final String PROP_DISABLE_QUOTA = "fw.disable_quota";
    private static final String PROP_VERIFY_STORAGE = "fw.verify_storage";

    private static final long DELAY_CHECK_STORAGE_DELTA = 30 * DateUtils.SECOND_IN_MILLIS;
    private static final long DELAY_RECALCULATE_QUOTAS = 10 * DateUtils.HOUR_IN_MILLIS;
    private static final long DEFAULT_QUOTA = DataUnit.MEBIBYTES.toBytes(64);

    public static class Lifecycle extends SystemService {
        private StorageStatsService mService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mService = new StorageStatsService(getContext());
            publishBinderService(Context.STORAGE_STATS_SERVICE, mService);
        }
    }

    private final Context mContext;
    private final AppOpsManager mAppOps;
    private final UserManager mUser;
    private final PackageManager mPackage;
    private final StorageManager mStorage;
    private final ArrayMap<String, SparseLongArray> mCacheQuotas;

    private final Installer mInstaller;
    private final H mHandler;

    private final CopyOnWriteArrayList<Pair<String, StorageStatsAugmenter>>
            mStorageStatsAugmenters = new CopyOnWriteArrayList<>();

    @GuardedBy("mLock")
    private int
            mStorageThresholdPercentHigh = StorageManager.DEFAULT_STORAGE_THRESHOLD_PERCENT_HIGH;

    private final Object mLock = new Object();

    public StorageStatsService(Context context) {
        mContext = Preconditions.checkNotNull(context);
        mAppOps = Preconditions.checkNotNull(context.getSystemService(AppOpsManager.class));
        mUser = Preconditions.checkNotNull(context.getSystemService(UserManager.class));
        mPackage = Preconditions.checkNotNull(context.getPackageManager());
        mStorage = Preconditions.checkNotNull(context.getSystemService(StorageManager.class));
        mCacheQuotas = new ArrayMap<>();

        mInstaller = new Installer(context);
        mInstaller.onStart();
        invalidateMounts();

        mHandler = new H(IoThread.get().getLooper());
        mHandler.sendEmptyMessage(H.MSG_LOAD_CACHED_QUOTAS_FROM_FILE);

        mStorage.registerListener(new StorageEventListener() {
            @Override
            public void onVolumeStateChanged(VolumeInfo vol, int oldState, int newState) {
                switch (vol.type) {
                    case VolumeInfo.TYPE_PUBLIC:
                    case VolumeInfo.TYPE_PRIVATE:
                    case VolumeInfo.TYPE_EMULATED:
                        if (newState == VolumeInfo.STATE_MOUNTED) {
                            invalidateMounts();
                        }
                }
            }
        });

        LocalManagerRegistry.addManager(StorageStatsManagerLocal.class, new LocalService());

        IntentFilter prFilter = new IntentFilter();
        prFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        prFilter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        prFilter.addDataScheme("package");
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_PACKAGE_REMOVED.equals(action)
                        || Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(action)) {
                    mHandler.removeMessages(H.MSG_PACKAGE_REMOVED);
                    mHandler.sendEmptyMessage(H.MSG_PACKAGE_REMOVED);
                }
            }
        }, prFilter);

        updateConfig();
        DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_STORAGE_NATIVE_BOOT,
                mContext.getMainExecutor(), properties -> updateConfig());
    }

    private void updateConfig() {
        synchronized (mLock) {
            mStorageThresholdPercentHigh = DeviceConfig.getInt(
                    DeviceConfig.NAMESPACE_STORAGE_NATIVE_BOOT,
                    StorageManager.STORAGE_THRESHOLD_PERCENT_HIGH_KEY,
                    StorageManager.DEFAULT_STORAGE_THRESHOLD_PERCENT_HIGH);
        }
    }

    private void invalidateMounts() {
        try {
            mInstaller.invalidateMounts();
        } catch (InstallerException e) {
            Slog.wtf(TAG, "Failed to invalidate mounts", e);
        }
    }

    private void enforceStatsPermission(int callingUid, String callingPackage) {
        final String errMsg = checkStatsPermission(callingUid, callingPackage, true);
        if (errMsg != null) {
            throw new SecurityException(errMsg);
        }
    }

    private String checkStatsPermission(int callingUid, String callingPackage, boolean noteOp) {
        final int mode;
        if (noteOp) {
            mode = mAppOps.noteOp(AppOpsManager.OP_GET_USAGE_STATS, callingUid, callingPackage);
        } else {
            mode = mAppOps.checkOp(AppOpsManager.OP_GET_USAGE_STATS, callingUid, callingPackage);
        }
        switch (mode) {
            case AppOpsManager.MODE_ALLOWED:
                return null;
            case AppOpsManager.MODE_DEFAULT:
                if (mContext.checkCallingOrSelfPermission(
                        Manifest.permission.PACKAGE_USAGE_STATS) == PERMISSION_GRANTED) {
                    return null;
                } else {
                    return "Caller does not have " + Manifest.permission.PACKAGE_USAGE_STATS
                            + "; callingPackage=" + callingPackage + ", callingUid=" + callingUid;
                }
            default:
                return "Package " + callingPackage + " from UID " + callingUid
                        + " blocked by mode " + mode;
        }
    }

    @Override
    public boolean isQuotaSupported(String volumeUuid, String callingPackage) {
        try {
            return mInstaller.isQuotaSupported(volumeUuid);
        } catch (InstallerException e) {
            throw new ParcelableException(new IOException(e.getMessage()));
        }
    }

    @Override
    public boolean isReservedSupported(String volumeUuid, String callingPackage) {
        if (volumeUuid == StorageManager.UUID_PRIVATE_INTERNAL) {
            return SystemProperties.getBoolean(StorageManager.PROP_HAS_RESERVED, false)
                    || Build.IS_ARC;
        } else {
            return false;
        }
    }

    @Override
    public long getTotalBytes(String volumeUuid, String callingPackage) {
        // NOTE: No permissions required

        if (volumeUuid == StorageManager.UUID_PRIVATE_INTERNAL) {
            // As a safety measure, use the original implementation for the devices
            // with storage size <= 512GB to prevent any potential regressions
            final long roundedUserspaceBytes = mStorage.getPrimaryStorageSize();
            if (roundedUserspaceBytes <= DataUnit.GIGABYTES.toBytes(512)) {
                return roundedUserspaceBytes;
            }

            // Since 1TB devices can actually have either 1000GB or 1024GB,
            // get the block device size and do just a small rounding if any at all
            final long totalBytes = mStorage.getInternalStorageBlockDeviceSize();
            final long totalBytesRounded = FileUtils.roundStorageSize(totalBytes);
            // If the storage size is 997GB-999GB, round it to a 1000GB to show
            // 1TB in UI instead of 0.99TB. Same for 2TB, 4TB, 8TB etc.
            if (totalBytesRounded - totalBytes <= DataUnit.GIGABYTES.toBytes(3)) {
                return totalBytesRounded;
            } else {
                return totalBytes;
            }
        } else {
            final VolumeInfo vol = mStorage.findVolumeByUuid(volumeUuid);
            if (vol == null) {
                throw new ParcelableException(
                        new IOException("Failed to find storage device for UUID " + volumeUuid));
            }
            return FileUtils.roundStorageSize(vol.disk.size);
        }
    }

    @Override
    public long getFreeBytes(String volumeUuid, String callingPackage) {
        // NOTE: No permissions required

        final long token = Binder.clearCallingIdentity();
        try {
            final File path;
            try {
                path = mStorage.findPathForUuid(volumeUuid);
            } catch (FileNotFoundException e) {
                throw new ParcelableException(e);
            }

            // Free space is usable bytes plus any cached data that we're
            // willing to automatically clear. To avoid user confusion, this
            // logic should be kept in sync with getAllocatableBytes().
            long freeBytes;
            if (isQuotaSupported(volumeUuid, PLATFORM_PACKAGE_NAME)) {
                final long cacheTotal = getCacheBytes(volumeUuid, PLATFORM_PACKAGE_NAME);
                final long cacheReserved = mStorage.getStorageCacheBytes(path, 0);
                final long cacheClearable = Math.max(0, cacheTotal - cacheReserved);

                freeBytes = path.getUsableSpace() + cacheClearable;
            } else {
                freeBytes = path.getUsableSpace();
            }

            Slog.d(TAG, "getFreeBytes: " + freeBytes);
            return freeBytes;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public long getCacheBytes(String volumeUuid, String callingPackage) {
        enforceStatsPermission(Binder.getCallingUid(), callingPackage);

        long cacheBytes = 0;
        for (UserInfo user : mUser.getUsers()) {
            final StorageStats stats = queryStatsForUser(volumeUuid, user.id, null);
            cacheBytes += stats.cacheBytes;
        }
        return cacheBytes;
    }

    @Override
    public long getCacheQuotaBytes(String volumeUuid, int uid, String callingPackage) {
        enforceStatsPermission(Binder.getCallingUid(), callingPackage);

        if (mCacheQuotas.containsKey(volumeUuid)) {
            final SparseLongArray uidMap = mCacheQuotas.get(volumeUuid);
            return uidMap.get(uid, DEFAULT_QUOTA);
        }

        return DEFAULT_QUOTA;
    }

    @Override
    public StorageStats queryStatsForPackage(String volumeUuid, String packageName, int userId,
            String callingPackage) {
        if (userId != UserHandle.getCallingUserId()) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS, TAG);
        }

        final ApplicationInfo appInfo;
        try {
            appInfo = mPackage.getApplicationInfoAsUser(packageName,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES, userId);
        } catch (NameNotFoundException e) {
            throw new ParcelableException(e);
        }

        final boolean callerHasStatsPermission;
        if (Binder.getCallingUid() == appInfo.uid) {
            // No permissions required when asking about themselves. We still check since it is
            // needed later on but don't throw if caller doesn't have the permission.
            callerHasStatsPermission = checkStatsPermission(
                    Binder.getCallingUid(), callingPackage, false) == null;
        } else {
            enforceStatsPermission(Binder.getCallingUid(), callingPackage);
            callerHasStatsPermission = true;
        }

        if (defeatNullable(mPackage.getPackagesForUid(appInfo.uid)).length == 1) {
            // Only one package inside UID means we can fast-path
            return queryStatsForUid(volumeUuid, appInfo.uid, callingPackage);
        } else {
            // Multiple packages means we need to go manual
            final int appId = UserHandle.getAppId(appInfo.uid);
            final String[] packageNames = new String[] { packageName };
            final long[] ceDataInodes = new long[1];
            String[] codePaths = new String[0];

            if (appInfo.isSystemApp() && !appInfo.isUpdatedSystemApp()) {
                // We don't count code baked into system image
            } else {
                if (appInfo.getCodePath() != null) {
                    codePaths = ArrayUtils.appendElement(String.class, codePaths,
                        appInfo.getCodePath());
                }
            }

            final PackageStats stats = new PackageStats(TAG);
            try {
                mInstaller.getAppSize(volumeUuid, packageNames, userId, 0,
                        appId, ceDataInodes, codePaths, stats);
            } catch (InstallerException e) {
                throw new ParcelableException(new IOException(e.getMessage()));
            }
            if (volumeUuid == StorageManager.UUID_PRIVATE_INTERNAL) {
                UserHandle userHandle = UserHandle.of(userId);
                forEachStorageStatsAugmenter((storageStatsAugmenter) -> {
                    storageStatsAugmenter.augmentStatsForPackageForUser(stats,
                            packageName, userHandle, callerHasStatsPermission);
                }, "queryStatsForPackage");
            }
            return translate(stats);
        }
    }

    @Override
    public StorageStats queryStatsForUid(String volumeUuid, int uid, String callingPackage) {
        final int userId = UserHandle.getUserId(uid);
        final int appId = UserHandle.getAppId(uid);

        if (userId != UserHandle.getCallingUserId()) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS, TAG);
        }

        final boolean callerHasStatsPermission;
        if (Binder.getCallingUid() == uid) {
            // No permissions required when asking about themselves. We still check since it is
            // needed later on but don't throw if caller doesn't have the permission.
            callerHasStatsPermission = checkStatsPermission(
                    Binder.getCallingUid(), callingPackage, false) == null;
        } else {
            enforceStatsPermission(Binder.getCallingUid(), callingPackage);
            callerHasStatsPermission = true;
        }

        final String[] packageNames = defeatNullable(mPackage.getPackagesForUid(uid));
        final long[] ceDataInodes = new long[packageNames.length];
        String[] codePaths = new String[0];

        final PackageStats stats = new PackageStats(TAG);
        for (int i = 0; i < packageNames.length; i++) {
            try {
                final ApplicationInfo appInfo = mPackage.getApplicationInfoAsUser(packageNames[i],
                        PackageManager.MATCH_UNINSTALLED_PACKAGES, userId);
                if (appInfo.isSystemApp() && !appInfo.isUpdatedSystemApp()) {
                    // We don't count code baked into system image
                } else {
                    if (appInfo.getCodePath() != null) {
                        codePaths = ArrayUtils.appendElement(String.class, codePaths,
                            appInfo.getCodePath());
                    }
                    if (Flags.getAppBytesByDataTypeApi()) {
                        computeAppStatsByDataTypes(
                            stats, appInfo.sourceDir, packageNames[i]);
                    }
                }
            } catch (NameNotFoundException e) {
                throw new ParcelableException(e);
            }
        }

        try {
            mInstaller.getAppSize(volumeUuid, packageNames, userId, getDefaultFlags(),
                    appId, ceDataInodes, codePaths, stats);

            if (SystemProperties.getBoolean(PROP_VERIFY_STORAGE, false)) {
                final PackageStats manualStats = new PackageStats(TAG);
                mInstaller.getAppSize(volumeUuid, packageNames, userId, 0,
                        appId, ceDataInodes, codePaths, manualStats);
                checkEquals("UID " + uid, manualStats, stats);
            }
        } catch (InstallerException e) {
            throw new ParcelableException(new IOException(e.getMessage()));
        }

        if (volumeUuid == StorageManager.UUID_PRIVATE_INTERNAL) {
            forEachStorageStatsAugmenter((storageStatsAugmenter) -> {
                storageStatsAugmenter.augmentStatsForUid(stats, uid, callerHasStatsPermission);
            }, "queryStatsForUid");
        }
        return translate(stats);
    }

    @Override
    public StorageStats queryStatsForUser(String volumeUuid, int userId, String callingPackage) {
        if (userId != UserHandle.getCallingUserId()) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS, TAG);
        }

        // Always require permission to see user-level stats
        enforceStatsPermission(Binder.getCallingUid(), callingPackage);

        final int[] appIds = getAppIds(userId);
        final PackageStats stats = new PackageStats(TAG);
        try {
            mInstaller.getUserSize(volumeUuid, userId, getDefaultFlags(), appIds, stats);

            if (SystemProperties.getBoolean(PROP_VERIFY_STORAGE, false)) {
                final PackageStats manualStats = new PackageStats(TAG);
                mInstaller.getUserSize(volumeUuid, userId, 0, appIds, manualStats);
                checkEquals("User " + userId, manualStats, stats);
            }
        } catch (InstallerException e) {
            throw new ParcelableException(new IOException(e.getMessage()));
        }
        if (volumeUuid == StorageManager.UUID_PRIVATE_INTERNAL) {
            UserHandle userHandle = UserHandle.of(userId);
            forEachStorageStatsAugmenter((storageStatsAugmenter) -> {
                storageStatsAugmenter.augmentStatsForUser(stats, userHandle);
            }, "queryStatsForUser");
        }
        return translate(stats);
    }

    @Override
    public ExternalStorageStats queryExternalStatsForUser(String volumeUuid, int userId,
            String callingPackage) {
        if (userId != UserHandle.getCallingUserId()) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS, TAG);
        }

        // Always require permission to see user-level stats
        enforceStatsPermission(Binder.getCallingUid(), callingPackage);

        final int[] appIds = getAppIds(userId);
        final long[] stats;
        try {
            stats = mInstaller.getExternalSize(volumeUuid, userId, getDefaultFlags(), appIds);

            if (SystemProperties.getBoolean(PROP_VERIFY_STORAGE, false)) {
                final long[] manualStats = mInstaller.getExternalSize(volumeUuid, userId, 0,
                        appIds);
                checkEquals("External " + userId, manualStats, stats);
            }
        } catch (InstallerException e) {
            throw new ParcelableException(new IOException(e.getMessage()));
        }

        final ExternalStorageStats res = new ExternalStorageStats();
        res.totalBytes = stats[0];
        res.audioBytes = stats[1];
        res.videoBytes = stats[2];
        res.imageBytes = stats[3];
        res.appBytes = stats[4];
        res.obbBytes = stats[5];
        return res;
    }

    private int[] getAppIds(int userId) {
        int[] appIds = null;
        for (ApplicationInfo app : mPackage.getInstalledApplicationsAsUser(
                PackageManager.MATCH_UNINSTALLED_PACKAGES, userId)) {
            final int appId = UserHandle.getAppId(app.uid);
            if (!ArrayUtils.contains(appIds, appId)) {
                appIds = ArrayUtils.appendInt(appIds, appId);
            }
        }
        return appIds;
    }

    private static int getDefaultFlags() {
        if (SystemProperties.getBoolean(PROP_DISABLE_QUOTA, false)) {
            return 0;
        } else {
            return Installer.FLAG_USE_QUOTA;
        }
    }

    private static void checkEquals(String msg, long[] a, long[] b) {
        for (int i = 0; i < a.length; i++) {
            checkEquals(msg + "[" + i + "]", a[i], b[i]);
        }
    }

    private static void checkEquals(String msg, PackageStats a, PackageStats b) {
        checkEquals(msg + " codeSize", a.codeSize, b.codeSize);
        checkEquals(msg + " dataSize", a.dataSize, b.dataSize);
        checkEquals(msg + " cacheSize", a.cacheSize, b.cacheSize);
        checkEquals(msg + " externalCodeSize", a.externalCodeSize, b.externalCodeSize);
        checkEquals(msg + " externalDataSize", a.externalDataSize, b.externalDataSize);
        checkEquals(msg + " externalCacheSize", a.externalCacheSize, b.externalCacheSize);
    }

    private static void checkEquals(String msg, long expected, long actual) {
        if (expected != actual) {
            Slog.e(TAG, msg + " expected " + expected + " actual " + actual);
        }
    }

    private static StorageStats translate(PackageStats stats) {
        final StorageStats res = new StorageStats();
        res.codeBytes = stats.codeSize + stats.externalCodeSize;
        res.dataBytes = stats.dataSize + stats.externalDataSize;
        res.cacheBytes = stats.cacheSize + stats.externalCacheSize;
        res.dexoptBytes = stats.dexoptSize;
        res.curProfBytes = stats.curProfSize;
        res.refProfBytes = stats.refProfSize;
        res.apkBytes = stats.apkSize;
        res.libBytes = stats.libSize;
        res.dmBytes = stats.dmSize;
        res.externalCacheBytes = stats.externalCacheSize;
        return res;
    }

    private class H extends Handler {
        private static final int MSG_CHECK_STORAGE_DELTA = 100;
        private static final int MSG_LOAD_CACHED_QUOTAS_FROM_FILE = 101;
        private static final int MSG_RECALCULATE_QUOTAS = 102;
        private static final int MSG_PACKAGE_REMOVED = 103;
        /**
         * By only triggering a re-calculation after the storage has changed sizes, we can avoid
         * recalculating quotas too often. Minimum change delta high and low define the
         * percentage of change we need to see before we recalculate quotas when the device has
         * enough storage space (more than mStorageThresholdPercentHigh of total
         * free) and in low storage condition respectively.
         */
        private static final long MINIMUM_CHANGE_DELTA_PERCENT_HIGH = 5;
        private static final long MINIMUM_CHANGE_DELTA_PERCENT_LOW = 2;
        private static final int UNSET = -1;
        private static final boolean DEBUG = false;

        private final StatFs mStats;
        private long mPreviousBytes;
        private long mTotalBytes;

        public H(Looper looper) {
            super(looper);
            // TODO: Handle all private volumes.
            mStats = new StatFs(Environment.getDataDirectory().getAbsolutePath());
            mPreviousBytes = mStats.getAvailableBytes();
            mTotalBytes = mStats.getTotalBytes();
        }

        public void handleMessage(Message msg) {
            if (DEBUG) {
                Slog.v(TAG, ">>> handling " + msg.what);
            }

            if (!isCacheQuotaCalculationsEnabled(mContext.getContentResolver())) {
                return;
            }

            switch (msg.what) {
                case MSG_CHECK_STORAGE_DELTA: {
                    mStats.restat(Environment.getDataDirectory().getAbsolutePath());
                    long bytesDelta = Math.abs(mPreviousBytes - mStats.getAvailableBytes());
                    long bytesDeltaThreshold;
                    synchronized (mLock) {
                        if (mStats.getAvailableBytes() >  mTotalBytes
                                * mStorageThresholdPercentHigh / 100) {
                            bytesDeltaThreshold = mTotalBytes
                                    * MINIMUM_CHANGE_DELTA_PERCENT_HIGH / 100;
                        } else {
                            bytesDeltaThreshold = mTotalBytes
                                    * MINIMUM_CHANGE_DELTA_PERCENT_LOW / 100;
                        }
                    }
                    if (bytesDelta > bytesDeltaThreshold) {
                        mPreviousBytes = mStats.getAvailableBytes();
                        recalculateQuotas(getInitializedStrategy());
                        notifySignificantDelta();
                    }
                    sendEmptyMessageDelayed(MSG_CHECK_STORAGE_DELTA, DELAY_CHECK_STORAGE_DELTA);
                    break;
                }
                case MSG_LOAD_CACHED_QUOTAS_FROM_FILE: {
                    CacheQuotaStrategy strategy = getInitializedStrategy();
                    mPreviousBytes = UNSET;
                    try {
                        mPreviousBytes = strategy.setupQuotasFromFile();
                    } catch (IOException e) {
                        Slog.e(TAG, "An error occurred while reading the cache quota file.", e);
                    } catch (IllegalStateException e) {
                        Slog.e(TAG, "Cache quota XML file is malformed?", e);
                    }

                    // If errors occurred getting the quotas from disk, let's re-calc them.
                    if (mPreviousBytes < 0) {
                        mStats.restat(Environment.getDataDirectory().getAbsolutePath());
                        mPreviousBytes = mStats.getAvailableBytes();
                        recalculateQuotas(strategy);
                    }
                    sendEmptyMessageDelayed(MSG_CHECK_STORAGE_DELTA, DELAY_CHECK_STORAGE_DELTA);
                    sendEmptyMessageDelayed(MSG_RECALCULATE_QUOTAS, DELAY_RECALCULATE_QUOTAS);
                    break;
                }
                case MSG_RECALCULATE_QUOTAS: {
                    recalculateQuotas(getInitializedStrategy());
                    sendEmptyMessageDelayed(MSG_RECALCULATE_QUOTAS, DELAY_RECALCULATE_QUOTAS);
                    break;
                }
                case MSG_PACKAGE_REMOVED: {
                    // recalculate quotas when package is removed
                    recalculateQuotas(getInitializedStrategy());
                    break;
                }
                default:
                    if (DEBUG) {
                        Slog.v(TAG, ">>> default message case ");
                    }
                    return;
            }
        }

        private void recalculateQuotas(CacheQuotaStrategy strategy) {
            if (DEBUG) {
                Slog.v(TAG, ">>> recalculating quotas ");
            }

            strategy.recalculateQuotas();
        }

        private CacheQuotaStrategy getInitializedStrategy() {
            UsageStatsManagerInternal usageStatsManager =
                    LocalServices.getService(UsageStatsManagerInternal.class);
            return new CacheQuotaStrategy(mContext, usageStatsManager, mInstaller, mCacheQuotas);
        }
    }

    @VisibleForTesting
    static boolean isCacheQuotaCalculationsEnabled(ContentResolver resolver) {
        return Settings.Global.getInt(
                resolver, Settings.Global.ENABLE_CACHE_QUOTA_CALCULATION, 1) != 0;
    }

    /**
     * Hacky way of notifying that disk space has changed significantly; we do
     * this to cause "available space" values to be requeried.
     */
    void notifySignificantDelta() {
        mContext.getContentResolver().notifyChange(
                Uri.parse("content://com.android.externalstorage.documents/"), null, false);
    }

    private static void checkCratesEnable() {
        final boolean enable = SystemProperties.getBoolean(PROP_STORAGE_CRATES, false);
        if (!enable) {
            throw new IllegalStateException("Storage Crate feature is disabled.");
        }
    }

    /**
     * To enforce the calling or self to have the {@link android.Manifest.permission#MANAGE_CRATES}
     * permission.
     * @param callingUid the calling uid
     * @param callingPackage the calling package name
     */
    private void enforceCratesPermission(int callingUid, String callingPackage) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_CRATES,
                callingPackage);
    }

    /**
     * To copy from CrateMetadata instances into CrateInfo instances.
     */
    @NonNull
    private static List<CrateInfo> convertCrateInfoFrom(@Nullable CrateMetadata[] crateMetadatas) {
        if (ArrayUtils.isEmpty(crateMetadatas)) {
            return Collections.EMPTY_LIST;
        }

        ArrayList<CrateInfo> crateInfos = new ArrayList<>();
        for (CrateMetadata crateMetadata : crateMetadatas) {
            if (crateMetadata == null || TextUtils.isEmpty(crateMetadata.id)
                    || TextUtils.isEmpty(crateMetadata.packageName)) {
                continue;
            }

            CrateInfo crateInfo = CrateInfo.copyFrom(crateMetadata.uid,
                    crateMetadata.packageName, crateMetadata.id);
            if (crateInfo == null) {
                continue;
            }

            crateInfos.add(crateInfo);
        }

        return crateInfos;
    }

    @NonNull
    private ParceledListSlice<CrateInfo> getAppCrates(String volumeUuid, String[] packageNames,
            @UserIdInt int userId) {
        try {
            CrateMetadata[] crateMetadatas = mInstaller.getAppCrates(volumeUuid,
                    packageNames, userId);
            return new ParceledListSlice<>(convertCrateInfoFrom(crateMetadatas));
        } catch (InstallerException e) {
            throw new ParcelableException(new IOException(e.getMessage()));
        }
    }

    @NonNull
    @Override
    public ParceledListSlice<CrateInfo> queryCratesForPackage(String volumeUuid,
            @NonNull String packageName, @UserIdInt int userId, @NonNull String callingPackage) {
        checkCratesEnable();
        if (userId != UserHandle.getCallingUserId()) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS, TAG);
        }

        final ApplicationInfo appInfo;
        try {
            appInfo = mPackage.getApplicationInfoAsUser(packageName,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES, userId);
        } catch (NameNotFoundException e) {
            throw new ParcelableException(e);
        }

        if (Binder.getCallingUid() == appInfo.uid) {
            // No permissions required when asking about themselves
        } else {
            enforceCratesPermission(Binder.getCallingUid(), callingPackage);
        }

        final String[] packageNames = new String[] { packageName };
        return getAppCrates(volumeUuid, packageNames, userId);
    }

    @NonNull
    @Override
    public ParceledListSlice<CrateInfo> queryCratesForUid(String volumeUuid, int uid,
            @NonNull String callingPackage) {
        checkCratesEnable();
        final int userId = UserHandle.getUserId(uid);
        if (userId != UserHandle.getCallingUserId()) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS, TAG);
        }

        if (Binder.getCallingUid() == uid) {
            // No permissions required when asking about themselves
        } else {
            enforceCratesPermission(Binder.getCallingUid(), callingPackage);
        }

        final String[] packageNames = defeatNullable(mPackage.getPackagesForUid(uid));
        String[] validatedPackageNames = new String[0];

        for (String packageName : packageNames) {
            if (TextUtils.isEmpty(packageName)) {
                continue;
            }

            try {
                final ApplicationInfo appInfo = mPackage.getApplicationInfoAsUser(packageName,
                        PackageManager.MATCH_UNINSTALLED_PACKAGES, userId);
                if (appInfo == null) {
                    continue;
                }

                validatedPackageNames = ArrayUtils.appendElement(String.class,
                        validatedPackageNames, packageName);
            } catch (NameNotFoundException e) {
                throw new ParcelableException(e);
            }
        }

        return getAppCrates(volumeUuid, validatedPackageNames, userId);
    }

    @NonNull
    @Override
    public ParceledListSlice<CrateInfo> queryCratesForUser(String volumeUuid, int userId,
            @NonNull String callingPackage) {
        checkCratesEnable();
        if (userId != UserHandle.getCallingUserId()) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS, TAG);
        }

        // Always require permission to see user-level stats
        enforceCratesPermission(Binder.getCallingUid(), callingPackage);

        try {
            CrateMetadata[] crateMetadatas = mInstaller.getUserCrates(volumeUuid, userId);
            return new ParceledListSlice<>(convertCrateInfoFrom(crateMetadatas));
        } catch (InstallerException e) {
            throw new ParcelableException(new IOException(e.getMessage()));
        }
    }

    void forEachStorageStatsAugmenter(@NonNull Consumer<StorageStatsAugmenter> consumer,
                @NonNull String queryTag) {
        for (int i = 0, count = mStorageStatsAugmenters.size(); i < count; ++i) {
            final Pair<String, StorageStatsAugmenter> pair = mStorageStatsAugmenters.get(i);
            final String augmenterTag = pair.first;
            final StorageStatsAugmenter storageStatsAugmenter = pair.second;

            Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, queryTag + ":" + augmenterTag);
            try {
                consumer.accept(storageStatsAugmenter);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
            }
        }
    }

    private class LocalService implements StorageStatsManagerLocal {
        @Override
        public void registerStorageStatsAugmenter(
                @NonNull StorageStatsAugmenter storageStatsAugmenter,
                @NonNull String tag) {
            mStorageStatsAugmenters.add(Pair.create(tag, storageStatsAugmenter));
        }
    }

    private long getDirBytes(File dir) {
        if (!dir.isDirectory()) {
            return 0;
        }

        long size = 0;
        try {
            for (File file : dir.listFiles()) {
                if (file.isFile()) {
                    size += file.length();
                    continue;
                }
                if (file.isDirectory()) {
                    size += getDirBytes(file);
                }
            }
        } catch (NullPointerException e) {
            Slog.w(TAG, "Failed to list directory " + dir.getName());
        }

        return size;
    }

    private long getFileBytesInDir(File dir, String suffix) {
        if (!dir.isDirectory()) {
            return 0;
        }

        long size = 0;
        try {
            for (File file : dir.listFiles()) {
                if (file.isFile() && file.getName().endsWith(suffix)) {
                    size += file.length();
                }
            }
        } catch (NullPointerException e) {
             Slog.w(TAG, "Failed to list directory " + dir.getName());
        }

        return size;
    }

    private void computeAppStatsByDataTypes(
        PackageStats stats, String sourceDirName, String packageName) {

        // Get apk, lib, dm file sizes.
        File srcDir = new File(sourceDirName);
        if (srcDir.isFile()) {
            sourceDirName = srcDir.getParent();
            srcDir = new File(sourceDirName);
        }

        stats.apkSize += getFileBytesInDir(srcDir, ".apk");
        stats.dmSize += getFileBytesInDir(srcDir, ".dm");
        stats.libSize += getDirBytes(new File(sourceDirName + "/lib/"));

        // Get dexopt, current profle and reference profile sizes.
        ArtManagedFileStats artManagedFileStats;
        try (var snapshot = getPackageManagerLocal().withFilteredSnapshot()) {
            artManagedFileStats =
                getArtManagerLocal().getArtManagedFileStats(snapshot, packageName);
        }

        stats.dexoptSize +=
            artManagedFileStats
                .getTotalSizeBytesByType(ArtManagedFileStats.TYPE_DEXOPT_ARTIFACT);
        stats.refProfSize +=
            artManagedFileStats
                .getTotalSizeBytesByType(ArtManagedFileStats.TYPE_REF_PROFILE);
        stats.curProfSize +=
            artManagedFileStats
                .getTotalSizeBytesByType(ArtManagedFileStats.TYPE_CUR_PROFILE);
    }
}
