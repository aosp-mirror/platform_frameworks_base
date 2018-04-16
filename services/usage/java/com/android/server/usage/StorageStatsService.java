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

import static com.android.internal.util.ArrayUtils.defeatNullable;

import android.app.AppOpsManager;
import android.app.usage.ExternalStorageStats;
import android.app.usage.IStorageStatsManager;
import android.app.usage.StorageStats;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageStats;
import android.content.pm.UserInfo;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Binder;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelableException;
import android.os.StatFs;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageEventListener;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.DataUnit;
import android.util.Slog;
import android.util.SparseLongArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;
import com.android.server.IoThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.pm.Installer;
import com.android.server.pm.Installer.InstallerException;
import com.android.server.storage.CacheQuotaStrategy;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public class StorageStatsService extends IStorageStatsManager.Stub {
    private static final String TAG = "StorageStatsService";

    private static final String PROP_DISABLE_QUOTA = "fw.disable_quota";
    private static final String PROP_VERIFY_STORAGE = "fw.verify_storage";

    private static final long DELAY_IN_MILLIS = 30 * DateUtils.SECOND_IN_MILLIS;
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
                    case VolumeInfo.TYPE_PRIVATE:
                    case VolumeInfo.TYPE_EMULATED:
                        if (newState == VolumeInfo.STATE_MOUNTED) {
                            invalidateMounts();
                        }
                }
            }
        });
    }

    private void invalidateMounts() {
        try {
            mInstaller.invalidateMounts();
        } catch (InstallerException e) {
            Slog.wtf(TAG, "Failed to invalidate mounts", e);
        }
    }

    private void enforcePermission(int callingUid, String callingPackage) {
        final int mode = mAppOps.checkOp(AppOpsManager.OP_GET_USAGE_STATS,
                callingUid, callingPackage);
        switch (mode) {
            case AppOpsManager.MODE_ALLOWED:
                return;
            case AppOpsManager.MODE_DEFAULT:
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.PACKAGE_USAGE_STATS, TAG);
                return;
            default:
                throw new SecurityException("Package " + callingPackage + " from UID " + callingUid
                        + " blocked by mode " + mode);
        }
    }

    @Override
    public boolean isQuotaSupported(String volumeUuid, String callingPackage) {
        enforcePermission(Binder.getCallingUid(), callingPackage);

        try {
            return mInstaller.isQuotaSupported(volumeUuid);
        } catch (InstallerException e) {
            throw new ParcelableException(new IOException(e.getMessage()));
        }
    }

    @Override
    public boolean isReservedSupported(String volumeUuid, String callingPackage) {
        enforcePermission(Binder.getCallingUid(), callingPackage);

        if (volumeUuid == StorageManager.UUID_PRIVATE_INTERNAL) {
            return SystemProperties.getBoolean(StorageManager.PROP_HAS_RESERVED, false);
        } else {
            return false;
        }
    }

    @Override
    public long getTotalBytes(String volumeUuid, String callingPackage) {
        // NOTE: No permissions required

        if (volumeUuid == StorageManager.UUID_PRIVATE_INTERNAL) {
            return FileUtils.roundStorageSize(mStorage.getPrimaryStorageSize());
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
            if (isQuotaSupported(volumeUuid, callingPackage)) {
                final long cacheTotal = getCacheBytes(volumeUuid, callingPackage);
                final long cacheReserved = mStorage.getStorageCacheBytes(path, 0);
                final long cacheClearable = Math.max(0, cacheTotal - cacheReserved);

                return path.getUsableSpace() + cacheClearable;
            } else {
                return path.getUsableSpace();
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public long getCacheBytes(String volumeUuid, String callingPackage) {
        enforcePermission(Binder.getCallingUid(), callingPackage);

        long cacheBytes = 0;
        for (UserInfo user : mUser.getUsers()) {
            final StorageStats stats = queryStatsForUser(volumeUuid, user.id, null);
            cacheBytes += stats.cacheBytes;
        }
        return cacheBytes;
    }

    @Override
    public long getCacheQuotaBytes(String volumeUuid, int uid, String callingPackage) {
        enforcePermission(Binder.getCallingUid(), callingPackage);

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

        if (Binder.getCallingUid() == appInfo.uid) {
            // No permissions required when asking about themselves
        } else {
            enforcePermission(Binder.getCallingUid(), callingPackage);
        }

        if (defeatNullable(mPackage.getPackagesForUid(appInfo.uid)).length == 1) {
            // Only one package inside UID means we can fast-path
            return queryStatsForUid(volumeUuid, appInfo.uid, callingPackage);
        } else {
            // Multiple packages means we need to go manual
            final int appId = UserHandle.getUserId(appInfo.uid);
            final String[] packageNames = new String[] { packageName };
            final long[] ceDataInodes = new long[1];
            String[] codePaths = new String[0];

            if (appInfo.isSystemApp() && !appInfo.isUpdatedSystemApp()) {
                // We don't count code baked into system image
            } else {
                codePaths = ArrayUtils.appendElement(String.class, codePaths,
                        appInfo.getCodePath());
            }

            final PackageStats stats = new PackageStats(TAG);
            try {
                mInstaller.getAppSize(volumeUuid, packageNames, userId, 0,
                        appId, ceDataInodes, codePaths, stats);
            } catch (InstallerException e) {
                throw new ParcelableException(new IOException(e.getMessage()));
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

        if (Binder.getCallingUid() == uid) {
            // No permissions required when asking about themselves
        } else {
            enforcePermission(Binder.getCallingUid(), callingPackage);
        }

        final String[] packageNames = defeatNullable(mPackage.getPackagesForUid(uid));
        final long[] ceDataInodes = new long[packageNames.length];
        String[] codePaths = new String[0];

        for (int i = 0; i < packageNames.length; i++) {
            try {
                final ApplicationInfo appInfo = mPackage.getApplicationInfoAsUser(packageNames[i],
                        PackageManager.MATCH_UNINSTALLED_PACKAGES, userId);
                if (appInfo.isSystemApp() && !appInfo.isUpdatedSystemApp()) {
                    // We don't count code baked into system image
                } else {
                    codePaths = ArrayUtils.appendElement(String.class, codePaths,
                            appInfo.getCodePath());
                }
            } catch (NameNotFoundException e) {
                throw new ParcelableException(e);
            }
        }

        final PackageStats stats = new PackageStats(TAG);
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
        return translate(stats);
    }

    @Override
    public StorageStats queryStatsForUser(String volumeUuid, int userId, String callingPackage) {
        if (userId != UserHandle.getCallingUserId()) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS, TAG);
        }

        // Always require permission to see user-level stats
        enforcePermission(Binder.getCallingUid(), callingPackage);

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
        enforcePermission(Binder.getCallingUid(), callingPackage);

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
        return res;
    }

    private class H extends Handler {
        private static final int MSG_CHECK_STORAGE_DELTA = 100;
        private static final int MSG_LOAD_CACHED_QUOTAS_FROM_FILE = 101;
        /**
         * By only triggering a re-calculation after the storage has changed sizes, we can avoid
         * recalculating quotas too often. Minimum change delta defines the percentage of change
         * we need to see before we recalculate.
         */
        private static final double MINIMUM_CHANGE_DELTA = 0.05;
        private static final int UNSET = -1;
        private static final boolean DEBUG = false;

        private final StatFs mStats;
        private long mPreviousBytes;
        private double mMinimumThresholdBytes;

        public H(Looper looper) {
            super(looper);
            // TODO: Handle all private volumes.
            mStats = new StatFs(Environment.getDataDirectory().getAbsolutePath());
            mPreviousBytes = mStats.getAvailableBytes();
            mMinimumThresholdBytes = mStats.getTotalBytes() * MINIMUM_CHANGE_DELTA;
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
                    long bytesDelta = Math.abs(mPreviousBytes - mStats.getAvailableBytes());
                    if (bytesDelta > mMinimumThresholdBytes) {
                        mPreviousBytes = mStats.getAvailableBytes();
                        recalculateQuotas(getInitializedStrategy());
                        notifySignificantDelta();
                    }
                    sendEmptyMessageDelayed(MSG_CHECK_STORAGE_DELTA, DELAY_IN_MILLIS);
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
                        mPreviousBytes = mStats.getAvailableBytes();
                        recalculateQuotas(strategy);
                    }
                    sendEmptyMessageDelayed(MSG_CHECK_STORAGE_DELTA, DELAY_IN_MILLIS);
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
}
