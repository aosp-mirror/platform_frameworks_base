/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.pm;

import static com.android.server.pm.PackageManagerService.TAG;

import android.content.Context;
import android.content.pm.PackageInfoLite;
import android.content.pm.parsing.PackageLite;
import android.os.Environment;
import android.os.FileUtils;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.storage.IStorageManager;
import android.os.storage.StorageManager;
import android.os.storage.StorageManagerInternal;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.util.Slog;

import com.android.internal.content.InstallLocationUtils;

import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * A helper to clear various types of cached data across the system.
 */
final class FreeStorageHelper {
    private static final long FREE_STORAGE_UNUSED_STATIC_SHARED_LIB_MIN_CACHE_PERIOD =
            TimeUnit.HOURS.toMillis(2); /* two hours */

    /**
     * Wall-clock timeout (in milliseconds) after which we *require* that an fstrim
     * be run on this device.  We use the value in the Settings.Global.MANDATORY_FSTRIM_INTERVAL
     * settings entry if available, otherwise we use the hardcoded default.  If it's been
     * more than this long since the last fstrim, we force one during the boot sequence.
     *
     * This backstops other fstrim scheduling:  if the device is alive at midnight+idle,
     * one gets run at the next available charging+idle time.  This final mandatory
     * no-fstrim check kicks in only of the other scheduling criteria is never met.
     */
    private static final long DEFAULT_MANDATORY_FSTRIM_INTERVAL = 3 * DateUtils.DAY_IN_MILLIS;

    private final PackageManagerService mPm;
    private final Context mContext;
    private final PackageManagerServiceInjector mInjector;
    private final boolean mEnableFreeCacheV2;

    // TODO(b/198166813): remove PMS dependency
    FreeStorageHelper(PackageManagerService pm, PackageManagerServiceInjector injector,
            Context context, boolean enableFreeCacheV2) {
        mPm = pm;
        mInjector = injector;
        mContext = context;
        mEnableFreeCacheV2 = enableFreeCacheV2;
    }

    FreeStorageHelper(PackageManagerService pm) {
        this(pm, pm.mInjector, pm.mContext,
                SystemProperties.getBoolean("fw.free_cache_v2", true));
    }

    /**
     * Blocking call to clear various types of cached data across the system
     * until the requested bytes are available.
     */
    void freeStorage(String volumeUuid, long bytes,
            @StorageManager.AllocateFlags int flags) throws IOException {
        final StorageManager storage = mInjector.getSystemService(StorageManager.class);
        final File file = storage.findPathForUuid(volumeUuid);
        if (file.getUsableSpace() >= bytes) return;

        if (mEnableFreeCacheV2) {
            final boolean internalVolume = Objects.equals(StorageManager.UUID_PRIVATE_INTERNAL,
                    volumeUuid);
            final boolean aggressive = (flags & StorageManager.FLAG_ALLOCATE_AGGRESSIVE) != 0;

            // 1. Pre-flight to determine if we have any chance to succeed
            // 2. Consider preloaded data (after 1w honeymoon, unless aggressive)
            if (internalVolume && (aggressive || SystemProperties
                    .getBoolean("persist.sys.preloads.file_cache_expired", false))) {
                mPm.deletePreloadsFileCache();
                if (file.getUsableSpace() >= bytes) return;
            }

            // 3. Consider parsed APK data (aggressive only)
            if (internalVolume && aggressive) {
                FileUtils.deleteContents(mPm.getCacheDir());
                if (file.getUsableSpace() >= bytes) return;
            }

            // 4. Consider cached app data (above quotas)
            try (PackageManagerTracedLock installLock = mPm.mInstallLock.acquireLock()) {
                mPm.mInstaller.freeCache(volumeUuid, bytes, Installer.FLAG_FREE_CACHE_V2);
            } catch (Installer.InstallerException ignored) {
            }
            if (file.getUsableSpace() >= bytes) return;

            final Computer computer = mPm.snapshotComputer();
            final SharedLibrariesImpl sharedLibraries = mPm.mInjector.getSharedLibrariesImpl();
            // 5. Consider shared libraries with refcount=0 and age>min cache period
            if (internalVolume && sharedLibraries.pruneUnusedStaticSharedLibraries(computer, bytes,
                    android.provider.Settings.Global.getLong(mContext.getContentResolver(),
                            Settings.Global.UNUSED_STATIC_SHARED_LIB_MIN_CACHE_PERIOD,
                            FREE_STORAGE_UNUSED_STATIC_SHARED_LIB_MIN_CACHE_PERIOD))) {
                return;
            }

            // 6. Consider dexopt output (aggressive only)
            // TODO: Implement

            // 7. Consider installed instant apps unused longer than min cache period
            if (internalVolume) {
                if (mPm.mInstantAppRegistry.pruneInstalledInstantApps(computer, bytes,
                        android.provider.Settings.Global.getLong(
                                mContext.getContentResolver(),
                                Settings.Global.INSTALLED_INSTANT_APP_MIN_CACHE_PERIOD,
                                InstantAppRegistry
                                        .DEFAULT_INSTALLED_INSTANT_APP_MIN_CACHE_PERIOD))) {
                    return;
                }
            }

            // 8. Consider cached app data (below quotas)
            try (PackageManagerTracedLock installLock = mPm.mInstallLock.acquireLock()) {
                mPm.mInstaller.freeCache(volumeUuid, bytes,
                        Installer.FLAG_FREE_CACHE_V2 | Installer.FLAG_FREE_CACHE_V2_DEFY_QUOTA);
            } catch (Installer.InstallerException ignored) {
            }
            if (file.getUsableSpace() >= bytes) return;

            // 9. Consider DropBox entries
            // TODO: Implement

            // 10. Consider instant meta-data (uninstalled apps) older that min cache period
            if (internalVolume) {
                if (mPm.mInstantAppRegistry.pruneUninstalledInstantApps(computer, bytes,
                        android.provider.Settings.Global.getLong(
                                mContext.getContentResolver(),
                                Settings.Global.UNINSTALLED_INSTANT_APP_MIN_CACHE_PERIOD,
                                InstantAppRegistry
                                        .DEFAULT_UNINSTALLED_INSTANT_APP_MIN_CACHE_PERIOD))) {
                    return;
                }
            }

            // 11. Free storage service cache
            StorageManagerInternal smInternal =
                    mInjector.getLocalService(StorageManagerInternal.class);
            long freeBytesRequired = bytes - file.getUsableSpace();
            if (freeBytesRequired > 0) {
                smInternal.freeCache(volumeUuid, freeBytesRequired);
            }

            // 12. Clear temp install session files
            mPm.mInstallerService.freeStageDirs(volumeUuid);
        } else {
            try (PackageManagerTracedLock installLock = mPm.mInstallLock.acquireLock()) {
                mPm.mInstaller.freeCache(volumeUuid, bytes, 0);
            } catch (Installer.InstallerException ignored) {
            }
        }
        if (file.getUsableSpace() >= bytes) return;

        throw new IOException("Failed to free " + bytes + " on storage device at " + file);
    }

    int freeCacheForInstallation(int recommendedInstallLocation, PackageLite pkgLite,
            String resolvedPath, String mPackageAbiOverride, int installFlags) {
        // TODO: focus freeing disk space on the target device
        final StorageManager storage = StorageManager.from(mContext);
        final long lowThreshold = storage.getStorageLowBytes(Environment.getDataDirectory());

        final long sizeBytes = PackageManagerServiceUtils.calculateInstalledSize(resolvedPath,
                mPackageAbiOverride);
        if (sizeBytes >= 0) {
            try (PackageManagerTracedLock installLock = mPm.mInstallLock.acquireLock()) {
                mPm.mInstaller.freeCache(null, sizeBytes + lowThreshold, 0);
                PackageInfoLite pkgInfoLite = PackageManagerServiceUtils.getMinimalPackageInfo(
                        mContext, pkgLite, resolvedPath, installFlags,
                        mPackageAbiOverride);
                // The cache free must have deleted the file we downloaded to install.
                if (pkgInfoLite.recommendedInstallLocation
                        == InstallLocationUtils.RECOMMEND_FAILED_INVALID_URI) {
                    pkgInfoLite.recommendedInstallLocation =
                            InstallLocationUtils.RECOMMEND_FAILED_INSUFFICIENT_STORAGE;
                }
                return pkgInfoLite.recommendedInstallLocation;
            } catch (Installer.InstallerException e) {
                Slog.w(TAG, "Failed to free cache", e);
            }
        }
        return recommendedInstallLocation;
    }

    void performFstrimIfNeeded() {
        PackageManagerServiceUtils.enforceSystemOrRoot("Only the system can request fstrim");

        // Before everything else, see whether we need to fstrim.
        try {
            IStorageManager sm = InstallLocationUtils.getStorageManager();
            if (sm != null) {
                boolean doTrim = false;
                final long interval = android.provider.Settings.Global.getLong(
                        mContext.getContentResolver(),
                        android.provider.Settings.Global.FSTRIM_MANDATORY_INTERVAL,
                        DEFAULT_MANDATORY_FSTRIM_INTERVAL);
                if (interval > 0) {
                    final long timeSinceLast = System.currentTimeMillis() - sm.lastMaintenance();
                    if (timeSinceLast > interval) {
                        doTrim = true;
                        Slog.w(TAG, "No disk maintenance in " + timeSinceLast
                                + "; running immediately");
                    }
                }
                if (doTrim) {
                    sm.runMaintenance();
                }
            } else {
                Slog.e(TAG, "storageManager service unavailable!");
            }
        } catch (RemoteException e) {
            // Can't happen; StorageManagerService is local
        }
    }
}
