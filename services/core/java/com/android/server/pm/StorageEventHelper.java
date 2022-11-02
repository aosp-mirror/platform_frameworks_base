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

package com.android.server.pm;

import static android.content.pm.parsing.ApkLiteParseUtils.isApkFile;
import static android.os.storage.StorageManager.FLAG_STORAGE_CE;
import static android.os.storage.StorageManager.FLAG_STORAGE_DE;
import static android.os.storage.StorageManager.FLAG_STORAGE_EXTERNAL;

import static com.android.server.pm.PackageManagerService.DEBUG_INSTALL;
import static com.android.server.pm.PackageManagerService.SCAN_INITIAL;
import static com.android.server.pm.PackageManagerService.TAG;
import static com.android.server.pm.PackageManagerServiceUtils.logCriticalInfo;

import android.annotation.NonNull;
import android.app.ResourcesManager;
import android.content.IIntentReceiver;
import android.content.pm.PackageManager;
import android.content.pm.PackagePartitions;
import android.content.pm.UserInfo;
import android.content.pm.VersionedPackage;
import android.os.Environment;
import android.os.FileUtils;
import android.os.UserHandle;
import android.os.storage.StorageEventListener;
import android.os.storage.StorageManager;
import android.os.storage.StorageManagerInternal;
import android.os.storage.VolumeInfo;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.policy.AttributeCache;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.parsing.ParsingPackageUtils;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/** Helper class to handle storage events and private apps loading */
public final class StorageEventHelper extends StorageEventListener {
    private final PackageManagerService mPm;
    private final BroadcastHelper mBroadcastHelper;
    private final DeletePackageHelper mDeletePackageHelper;
    private final RemovePackageHelper mRemovePackageHelper;

    @GuardedBy("mLoadedVolumes")
    final ArraySet<String> mLoadedVolumes = new ArraySet<>();

    // TODO(b/198166813): remove PMS dependency
    public StorageEventHelper(PackageManagerService pm, DeletePackageHelper deletePackageHelper,
            RemovePackageHelper removePackageHelper) {
        mPm = pm;
        mBroadcastHelper = new BroadcastHelper(mPm.mInjector);
        mDeletePackageHelper = deletePackageHelper;
        mRemovePackageHelper = removePackageHelper;
    }

    @Override
    public void onVolumeStateChanged(VolumeInfo vol, int oldState, int newState) {
        if (vol.type == VolumeInfo.TYPE_PRIVATE) {
            if (vol.state == VolumeInfo.STATE_MOUNTED) {
                final String volumeUuid = vol.getFsUuid();

                // Clean up any users or apps that were removed or recreated
                // while this volume was missing
                mPm.mUserManager.reconcileUsers(volumeUuid);
                reconcileApps(mPm.snapshotComputer(), volumeUuid);

                // Clean up any install sessions that expired or were
                // cancelled while this volume was missing
                mPm.mInstallerService.onPrivateVolumeMounted(volumeUuid);

                loadPrivatePackages(vol);

            } else if (vol.state == VolumeInfo.STATE_EJECTING) {
                unloadPrivatePackages(vol);
            }
        }
    }

    @Override
    public void onVolumeForgotten(String fsUuid) {
        if (TextUtils.isEmpty(fsUuid)) {
            Slog.e(TAG, "Forgetting internal storage is probably a mistake; ignoring");
            return;
        }

        // Remove any apps installed on the forgotten volume
        synchronized (mPm.mLock) {
            final List<? extends PackageStateInternal> packages =
                    mPm.mSettings.getVolumePackagesLPr(fsUuid);
            for (PackageStateInternal ps : packages) {
                Slog.d(TAG, "Destroying " + ps.getPackageName()
                        + " because volume was forgotten");
                mPm.deletePackageVersioned(new VersionedPackage(ps.getPackageName(),
                                PackageManager.VERSION_CODE_HIGHEST),
                        new PackageManager.LegacyPackageDeleteObserver(null).getBinder(),
                        UserHandle.USER_SYSTEM, PackageManager.DELETE_ALL_USERS);
                // Try very hard to release any references to this package
                // so we don't risk the system server being killed due to
                // open FDs
                AttributeCache.instance().removePackage(ps.getPackageName());
            }

            mPm.mSettings.onVolumeForgotten(fsUuid);
            mPm.writeSettingsLPrTEMP();
        }
    }

    private void loadPrivatePackages(final VolumeInfo vol) {
        mPm.mHandler.post(() -> loadPrivatePackagesInner(vol));
    }

    private void loadPrivatePackagesInner(VolumeInfo vol) {
        final String volumeUuid = vol.fsUuid;
        if (TextUtils.isEmpty(volumeUuid)) {
            Slog.e(TAG, "Loading internal storage is probably a mistake; ignoring");
            return;
        }

        final AppDataHelper appDataHelper = new AppDataHelper(mPm);
        final ArrayList<PackageFreezer> freezers = new ArrayList<>();
        final ArrayList<AndroidPackage> loaded = new ArrayList<>();
        final int parseFlags = mPm.getDefParseFlags() | ParsingPackageUtils.PARSE_EXTERNAL_STORAGE;

        final Settings.VersionInfo ver;
        final List<? extends PackageStateInternal> packages;
        final InstallPackageHelper installPackageHelper = new InstallPackageHelper(mPm);
        synchronized (mPm.mLock) {
            ver = mPm.mSettings.findOrCreateVersion(volumeUuid);
            packages = mPm.mSettings.getVolumePackagesLPr(volumeUuid);
        }

        for (PackageStateInternal ps : packages) {
            freezers.add(mPm.freezePackage(ps.getPackageName(), "loadPrivatePackagesInner"));
            synchronized (mPm.mInstallLock) {
                final AndroidPackage pkg;
                try {
                    pkg = installPackageHelper.scanSystemPackageTracedLI(
                            ps.getPath(), parseFlags, SCAN_INITIAL, null);
                    loaded.add(pkg);

                } catch (PackageManagerException e) {
                    Slog.w(TAG, "Failed to scan " + ps.getPath() + ": " + e.getMessage());
                }

                if (!PackagePartitions.FINGERPRINT.equals(ver.fingerprint)) {
                    appDataHelper.clearAppDataLIF(
                            ps.getPkg(), UserHandle.USER_ALL, FLAG_STORAGE_DE | FLAG_STORAGE_CE
                            | FLAG_STORAGE_EXTERNAL | Installer.FLAG_CLEAR_CODE_CACHE_ONLY
                            | Installer.FLAG_CLEAR_APP_DATA_KEEP_ART_PROFILES);
                }
            }
        }

        // Reconcile app data for all started/unlocked users
        final StorageManager sm = mPm.mInjector.getSystemService(StorageManager.class);
        UserManagerInternal umInternal = mPm.mInjector.getUserManagerInternal();
        StorageManagerInternal smInternal = mPm.mInjector.getLocalService(
                StorageManagerInternal.class);
        for (UserInfo user : mPm.mUserManager.getUsers(false /* includeDying */)) {
            final int flags;
            if (StorageManager.isUserKeyUnlocked(user.id)
                    && smInternal.isCeStoragePrepared(user.id)) {
                flags = StorageManager.FLAG_STORAGE_DE | StorageManager.FLAG_STORAGE_CE;
            } else if (umInternal.isUserRunning(user.id)) {
                flags = StorageManager.FLAG_STORAGE_DE;
            } else {
                continue;
            }

            try {
                sm.prepareUserStorage(volumeUuid, user.id, user.serialNumber, flags);
                synchronized (mPm.mInstallLock) {
                    appDataHelper.reconcileAppsDataLI(volumeUuid, user.id, flags,
                            true /* migrateAppData */);
                }
            } catch (RuntimeException e) {
                // The volume was probably already unmounted.  We'll probably process the unmount
                // event momentarily.  TODO(b/256909937): ignoring errors from prepareUserStorage()
                // is very dangerous.  Instead, we should fix the race condition that allows this
                // code to run on an unmounted volume in the first place.
                Slog.w(TAG, "Failed to prepare storage: " + e);
            }
        }

        synchronized (mPm.mLock) {
            final boolean isUpgrade = !PackagePartitions.FINGERPRINT.equals(ver.fingerprint);
            if (isUpgrade) {
                logCriticalInfo(Log.INFO, "Build fingerprint changed from " + ver.fingerprint
                        + " to " + PackagePartitions.FINGERPRINT + "; regranting permissions for "
                        + volumeUuid);
            }
            mPm.mPermissionManager.onStorageVolumeMounted(volumeUuid, isUpgrade);

            // Yay, everything is now upgraded
            ver.forceCurrent();

            mPm.writeSettingsLPrTEMP();
        }

        for (PackageFreezer freezer : freezers) {
            freezer.close();
        }

        if (DEBUG_INSTALL) Slog.d(TAG, "Loaded packages " + loaded);
        sendResourcesChangedBroadcast(true, false, loaded, null);
        synchronized (mLoadedVolumes) {
            mLoadedVolumes.add(vol.getId());
        }
    }

    private void unloadPrivatePackages(final VolumeInfo vol) {
        mPm.mHandler.post(() -> unloadPrivatePackagesInner(vol));
    }

    private void unloadPrivatePackagesInner(VolumeInfo vol) {
        final String volumeUuid = vol.fsUuid;
        if (TextUtils.isEmpty(volumeUuid)) {
            Slog.e(TAG, "Unloading internal storage is probably a mistake; ignoring");
            return;
        }

        final int[] userIds = mPm.mUserManager.getUserIds();
        final ArrayList<AndroidPackage> unloaded = new ArrayList<>();
        synchronized (mPm.mInstallLock) {
            synchronized (mPm.mLock) {
                final List<? extends PackageStateInternal> packages =
                        mPm.mSettings.getVolumePackagesLPr(volumeUuid);
                for (PackageStateInternal ps : packages) {
                    if (ps.getPkg() == null) continue;

                    final AndroidPackage pkg = ps.getPkg();
                    final int deleteFlags = PackageManager.DELETE_KEEP_DATA;
                    final PackageRemovedInfo outInfo = new PackageRemovedInfo(mPm);

                    try (PackageFreezer freezer = mPm.freezePackageForDelete(ps.getPackageName(),
                            deleteFlags, "unloadPrivatePackagesInner")) {
                        if (mDeletePackageHelper.deletePackageLIF(ps.getPackageName(), null, false,
                                userIds, deleteFlags, outInfo, false)) {
                            unloaded.add(pkg);
                        } else {
                            Slog.w(TAG, "Failed to unload " + ps.getPath());
                        }
                    }

                    // Try very hard to release any references to this package
                    // so we don't risk the system server being killed due to
                    // open FDs
                    AttributeCache.instance().removePackage(ps.getPackageName());
                }

                mPm.writeSettingsLPrTEMP();
            }
        }

        if (DEBUG_INSTALL) Slog.d(TAG, "Unloaded packages " + unloaded);
        sendResourcesChangedBroadcast(false, false, unloaded, null);
        synchronized (mLoadedVolumes) {
            mLoadedVolumes.remove(vol.getId());
        }

        // Try very hard to release any references to this path so we don't risk
        // the system server being killed due to open FDs
        ResourcesManager.getInstance().invalidatePath(vol.getPath().getAbsolutePath());

        for (int i = 0; i < 3; i++) {
            System.gc();
            System.runFinalization();
        }
    }

    private void sendResourcesChangedBroadcast(boolean mediaStatus, boolean replacing,
            ArrayList<AndroidPackage> packages, IIntentReceiver finishedReceiver) {
        final int size = packages.size();
        final String[] packageNames = new String[size];
        final int[] packageUids = new int[size];
        for (int i = 0; i < size; i++) {
            final AndroidPackage pkg = packages.get(i);
            packageNames[i] = pkg.getPackageName();
            packageUids[i] = pkg.getUid();
        }
        mBroadcastHelper.sendResourcesChangedBroadcast(mediaStatus, replacing, packageNames,
                packageUids, finishedReceiver);
    }

    /**
     * Examine all apps present on given mounted volume, and destroy apps that
     * aren't expected, either due to uninstallation or reinstallation on
     * another volume.
     */
    public void reconcileApps(@NonNull Computer snapshot, String volumeUuid) {
        List<String> absoluteCodePaths = collectAbsoluteCodePaths(snapshot);
        List<File> filesToDelete = null;

        final File[] files = FileUtils.listFilesOrEmpty(
                Environment.getDataAppDirectory(volumeUuid));
        for (File file : files) {
            final boolean isPackage = (isApkFile(file) || file.isDirectory())
                    && !PackageInstallerService.isStageName(file.getName());
            if (!isPackage) {
                // Ignore entries which are not packages
                continue;
            }

            String absolutePath = file.getAbsolutePath();

            boolean pathValid = false;
            final int absoluteCodePathCount = absoluteCodePaths.size();
            for (int i = 0; i < absoluteCodePathCount; i++) {
                String absoluteCodePath = absoluteCodePaths.get(i);
                if (absoluteCodePath.startsWith(absolutePath)) {
                    pathValid = true;
                    break;
                }
            }

            if (!pathValid) {
                if (filesToDelete == null) {
                    filesToDelete = new ArrayList<>();
                }
                filesToDelete.add(file);
            }
        }

        if (filesToDelete != null) {
            final int fileToDeleteCount = filesToDelete.size();
            for (int i = 0; i < fileToDeleteCount; i++) {
                File fileToDelete = filesToDelete.get(i);
                logCriticalInfo(Log.WARN, "Destroying orphaned at " + fileToDelete);
                synchronized (mPm.mInstallLock) {
                    mRemovePackageHelper.removeCodePathLI(fileToDelete);
                }
            }
        }
    }

    private List<String> collectAbsoluteCodePaths(@NonNull Computer snapshot) {
        List<String> codePaths = new ArrayList<>();
        final ArrayMap<String, ? extends PackageStateInternal> packageStates =
                snapshot.getPackageStates();
        final int packageCount = packageStates.size();
        for (int i = 0; i < packageCount; i++) {
            final PackageStateInternal ps = packageStates.valueAt(i);
            codePaths.add(ps.getPath().getAbsolutePath());
        }
        return codePaths;
    }

    public void dumpLoadedVolumes(@NonNull PrintWriter pw, @NonNull DumpState dumpState) {
        if (dumpState.onTitlePrinted()) {
            pw.println();
        }
        final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ", 120);
        ipw.println();
        ipw.println("Loaded volumes:");
        ipw.increaseIndent();
        synchronized (mLoadedVolumes) {
            if (mLoadedVolumes.size() == 0) {
                ipw.println("(none)");
            } else {
                for (int i = 0; i < mLoadedVolumes.size(); i++) {
                    ipw.println(mLoadedVolumes.valueAt(i));
                }
            }
        }
        ipw.decreaseIndent();
    }
}
