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

import static android.content.pm.PackageManager.UNINSTALL_REASON_UNKNOWN;
import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;
import static android.os.incremental.IncrementalManager.isIncrementalPath;
import static android.os.storage.StorageManager.FLAG_STORAGE_CE;
import static android.os.storage.StorageManager.FLAG_STORAGE_DE;
import static android.os.storage.StorageManager.FLAG_STORAGE_EXTERNAL;

import static com.android.server.pm.InstructionSets.getDexCodeInstructionSets;
import static com.android.server.pm.PackageManagerService.DEBUG_INSTALL;
import static com.android.server.pm.PackageManagerService.DEBUG_REMOVE;
import static com.android.server.pm.PackageManagerService.RANDOM_DIR_PREFIX;
import static com.android.server.pm.PackageManagerService.TAG;

import android.annotation.NonNull;
import android.content.pm.PackageManager;
import android.content.pm.parsing.ApkLiteParseUtils;
import android.content.pm.parsing.PackageLite;
import android.content.pm.parsing.result.ParseResult;
import android.content.pm.parsing.result.ParseTypeImpl;
import android.os.Environment;
import android.os.Trace;
import android.os.UserHandle;
import android.os.incremental.IncrementalManager;
import android.util.Log;
import android.util.Slog;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;
import com.android.server.pm.Installer.LegacyDexoptDisabledException;
import com.android.server.pm.parsing.PackageCacher;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;
import com.android.server.pm.parsing.pkg.PackageImpl;
import com.android.server.pm.permission.PermissionManagerServiceInternal;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.component.ParsedInstrumentation;

import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * Removes a package from internal data structures, deletes it data directories if requested,
 * and clears its app profiles
 */
final class RemovePackageHelper {
    private final PackageManagerService mPm;
    private final IncrementalManager mIncrementalManager;
    private final Installer mInstaller;
    private final UserManagerInternal mUserManagerInternal;
    private final PermissionManagerServiceInternal mPermissionManager;
    private final SharedLibrariesImpl mSharedLibraries;
    private final AppDataHelper mAppDataHelper;

    // TODO(b/198166813): remove PMS dependency
    RemovePackageHelper(PackageManagerService pm, AppDataHelper appDataHelper) {
        mPm = pm;
        mIncrementalManager = mPm.mInjector.getIncrementalManager();
        mInstaller = mPm.mInjector.getInstaller();
        mUserManagerInternal = mPm.mInjector.getUserManagerInternal();
        mPermissionManager = mPm.mInjector.getPermissionManagerServiceInternal();
        mSharedLibraries = mPm.mInjector.getSharedLibrariesImpl();
        mAppDataHelper = appDataHelper;
    }

    RemovePackageHelper(PackageManagerService pm) {
        this(pm, new AppDataHelper(pm));
    }

    public void removeCodePath(File codePath) {
        synchronized (mPm.mInstallLock) {
            removeCodePathLI(codePath);
        }
    }

    @GuardedBy("mPm.mInstallLock")
    private void removeCodePathLI(File codePath) {
        if (codePath == null || !codePath.exists()) {
            return;
        }
        if (codePath.isDirectory()) {
            final File codePathParent = codePath.getParentFile();
            final boolean needRemoveParent = codePathParent.getName().startsWith(RANDOM_DIR_PREFIX);
            try {
                final boolean isIncremental = (mIncrementalManager != null && isIncrementalPath(
                        codePath.getAbsolutePath()));
                if (isIncremental) {
                    if (needRemoveParent) {
                        mIncrementalManager.rmPackageDir(codePathParent);
                    } else {
                        mIncrementalManager.rmPackageDir(codePath);
                    }
                }

                final String packageName = codePath.getName();
                mInstaller.rmPackageDir(packageName, codePath.getAbsolutePath());
                if (needRemoveParent) {
                    mInstaller.rmPackageDir(packageName, codePathParent.getAbsolutePath());
                    removeCachedResult(codePathParent);
                }
            } catch (Installer.InstallerException e) {
                Slog.w(TAG, "Failed to remove code path", e);
            }
        } else {
            codePath.delete();
        }
    }

    private void removeCachedResult(@NonNull File codePath) {
        if (mPm.getCacheDir() == null) {
            return;
        }

        final PackageCacher cacher = new PackageCacher(mPm.getCacheDir());
        // Find and delete the cached result belong to the given codePath.
        cacher.cleanCachedResult(codePath);
    }

    public void removePackage(AndroidPackage pkg, boolean chatty) {
        synchronized (mPm.mInstallLock) {
            removePackageLI(pkg, chatty);
        }
    }

    @GuardedBy("mPm.mInstallLock")
    private void removePackageLI(AndroidPackage pkg, boolean chatty) {
        // Remove the parent package setting
        PackageStateInternal ps = mPm.snapshotComputer()
                .getPackageStateInternal(pkg.getPackageName());
        if (ps != null) {
            removePackageLI(ps.getPackageName(), chatty);
        } else if (DEBUG_REMOVE && chatty) {
            Log.d(TAG, "Not removing package " + pkg.getPackageName() + "; mExtras == null");
        }
    }

    @GuardedBy("mPm.mInstallLock")
    private void removePackageLI(String packageName, boolean chatty) {
        if (DEBUG_INSTALL) {
            if (chatty) {
                Log.d(TAG, "Removing package " + packageName);
            }
        }

        // writer
        synchronized (mPm.mLock) {
            final AndroidPackage removedPackage = mPm.mPackages.remove(packageName);
            if (removedPackage != null) {
                // TODO: Use PackageState for isSystem
                cleanPackageDataStructuresLILPw(removedPackage,
                        AndroidPackageUtils.isSystem(removedPackage), chatty);
            }
        }
    }

    @GuardedBy("mPm.mLock")
    private void cleanPackageDataStructuresLILPw(AndroidPackage pkg, boolean isSystemApp,
            boolean chatty) {
        mPm.mComponentResolver.removeAllComponents(pkg, chatty);
        mPermissionManager.onPackageRemoved(pkg);
        mPm.getPackageProperty().removeAllProperties(pkg);

        final int instrumentationSize = ArrayUtils.size(pkg.getInstrumentations());
        StringBuilder r = null;
        int i;
        for (i = 0; i < instrumentationSize; i++) {
            ParsedInstrumentation a = pkg.getInstrumentations().get(i);
            mPm.getInstrumentation().remove(a.getComponentName());
            if (DEBUG_REMOVE && chatty) {
                if (r == null) {
                    r = new StringBuilder(256);
                } else {
                    r.append(' ');
                }
                r.append(a.getName());
            }
        }
        if (r != null) {
            if (DEBUG_REMOVE) Log.d(TAG, "  Instrumentation: " + r);
        }

        r = null;
        if (isSystemApp) {
            // Only system apps can hold shared libraries.
            final int libraryNamesSize = pkg.getLibraryNames().size();
            for (i = 0; i < libraryNamesSize; i++) {
                String name = pkg.getLibraryNames().get(i);
                if (mSharedLibraries.removeSharedLibrary(name, 0)) {
                    if (DEBUG_REMOVE && chatty) {
                        if (r == null) {
                            r = new StringBuilder(256);
                        } else {
                            r.append(' ');
                        }
                        r.append(name);
                    }
                }
            }
        }

        r = null;

        // Any package can hold SDK or static shared libraries.
        if (pkg.getSdkLibraryName() != null) {
            if (mSharedLibraries.removeSharedLibrary(
                    pkg.getSdkLibraryName(), pkg.getSdkLibVersionMajor())) {
                if (DEBUG_REMOVE && chatty) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    r.append(pkg.getSdkLibraryName());
                }
            }
        }
        if (pkg.getStaticSharedLibraryName() != null) {
            if (mSharedLibraries.removeSharedLibrary(pkg.getStaticSharedLibraryName(),
                    pkg.getStaticSharedLibraryVersion())) {
                if (DEBUG_REMOVE && chatty) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    r.append(pkg.getStaticSharedLibraryName());
                }
            }
        }

        if (r != null) {
            if (DEBUG_REMOVE) Log.d(TAG, "  Libraries: " + r);
        }
    }

    public void removePackageData(final PackageSetting deletedPs, @NonNull int[] allUserHandles,
            PackageRemovedInfo outInfo, int flags, boolean writeSettings) {
        synchronized (mPm.mInstallLock) {
            removePackageDataLIF(deletedPs, allUserHandles, outInfo, flags, writeSettings);
        }
    }

    /*
     * This method deletes the package from internal data structures. If the DELETE_KEEP_DATA
     * flag is not set, the data directory is removed as well.
     * make sure this flag is set for partially installed apps. If not its meaningless to
     * delete a partially installed application.
     */
    @GuardedBy("mPm.mInstallLock")
    public void removePackageDataLIF(final PackageSetting deletedPs, @NonNull int[] allUserHandles,
            PackageRemovedInfo outInfo, int flags, boolean writeSettings) {
        String packageName = deletedPs.getPackageName();
        if (DEBUG_REMOVE) Slog.d(TAG, "removePackageDataLI: " + deletedPs);
        // Retrieve object to delete permissions for shared user later on
        final AndroidPackage deletedPkg = deletedPs.getPkg();
        if (outInfo != null) {
            outInfo.mRemovedPackage = packageName;
            outInfo.mInstallerPackageName = deletedPs.getInstallSource().mInstallerPackageName;
            outInfo.mIsStaticSharedLib = deletedPkg != null
                    && deletedPkg.getStaticSharedLibraryName() != null;
            outInfo.populateUsers(deletedPs.queryInstalledUsers(
                    mUserManagerInternal.getUserIds(), true), deletedPs);
            outInfo.mIsExternal = deletedPs.isExternalStorage();
            outInfo.mRemovedPackageVersionCode = deletedPs.getVersionCode();
        }

        removePackageLI(deletedPs.getPackageName(), (flags & PackageManager.DELETE_CHATTY) != 0);

        if ((flags & PackageManager.DELETE_KEEP_DATA) == 0) {
            final AndroidPackage resolvedPkg;
            if (deletedPkg != null) {
                resolvedPkg = deletedPkg;
            } else {
                // We don't have a parsed package when it lives on an ejected
                // adopted storage device, so fake something together
                resolvedPkg = PackageImpl.buildFakeForDeletion(deletedPs.getPackageName(),
                        deletedPs.getVolumeUuid());
            }
            mAppDataHelper.destroyAppDataLIF(resolvedPkg, UserHandle.USER_ALL,
                    FLAG_STORAGE_DE | FLAG_STORAGE_CE | FLAG_STORAGE_EXTERNAL);
            mAppDataHelper.destroyAppProfilesLIF(resolvedPkg);
            if (outInfo != null) {
                outInfo.mDataRemoved = true;
            }
        }

        int removedAppId = -1;

        // writer
        boolean installedStateChanged = false;
        if ((flags & PackageManager.DELETE_KEEP_DATA) == 0) {
            final SparseBooleanArray changedUsers = new SparseBooleanArray();
            synchronized (mPm.mLock) {
                mPm.mDomainVerificationManager.clearPackage(deletedPs.getPackageName());
                mPm.mSettings.getKeySetManagerService().removeAppKeySetDataLPw(packageName);
                final Computer snapshot = mPm.snapshotComputer();
                mPm.mAppsFilter.removePackage(snapshot,
                        snapshot.getPackageStateInternal(packageName));
                removedAppId = mPm.mSettings.removePackageLPw(packageName);
                if (outInfo != null) {
                    outInfo.mRemovedAppId = removedAppId;
                }
                if (!mPm.mSettings.isDisabledSystemPackageLPr(packageName)) {
                    // If we don't have a disabled system package to reinstall, the package is
                    // really gone and its permission state should be removed.
                    SharedUserSetting sus = mPm.mSettings.getSharedUserSettingLPr(deletedPs);
                    List<AndroidPackage> sharedUserPkgs =
                            sus != null ? sus.getPackages() : Collections.emptyList();
                    mPermissionManager.onPackageUninstalled(packageName, deletedPs.getAppId(),
                            deletedPs, deletedPkg, sharedUserPkgs, UserHandle.USER_ALL);
                    // After permissions are handled, check if the shared user can be migrated
                    if (sus != null) {
                        mPm.mSettings.checkAndConvertSharedUserSettingsLPw(sus);
                    }
                }
                mPm.clearPackagePreferredActivitiesLPw(
                        deletedPs.getPackageName(), changedUsers, UserHandle.USER_ALL);

                mPm.mSettings.removeRenamedPackageLPw(deletedPs.getRealName());
            }
            if (changedUsers.size() > 0) {
                final PreferredActivityHelper preferredActivityHelper =
                        new PreferredActivityHelper(mPm);
                preferredActivityHelper.updateDefaultHomeNotLocked(mPm.snapshotComputer(),
                        changedUsers);
                mPm.postPreferredActivityChangedBroadcast(UserHandle.USER_ALL);
            }
        }
        // make sure to preserve per-user disabled state if this removal was just
        // a downgrade of a system app to the factory package
        if (outInfo != null && outInfo.mOrigUsers != null) {
            if (DEBUG_REMOVE) {
                Slog.d(TAG, "Propagating install state across downgrade");
            }
            for (int userId : allUserHandles) {
                final boolean installed = ArrayUtils.contains(outInfo.mOrigUsers, userId);
                if (DEBUG_REMOVE) {
                    Slog.d(TAG, "    user " + userId + " => " + installed);
                }
                if (installed != deletedPs.getInstalled(userId)) {
                    installedStateChanged = true;
                }
                deletedPs.setInstalled(installed, userId);
                if (installed) {
                    deletedPs.setUninstallReason(UNINSTALL_REASON_UNKNOWN, userId);
                }
            }
        }
        synchronized (mPm.mLock) {
            // can downgrade to reader
            if (writeSettings) {
                // Save settings now
                mPm.writeSettingsLPrTEMP();
            }
            if (installedStateChanged) {
                mPm.mSettings.writeKernelMappingLPr(deletedPs);
            }
        }

        if (removedAppId != -1) {
            // A user ID was deleted here. Go through all users and remove it from KeyStore.
            final int appIdToRemove = removedAppId;
            mPm.mInjector.getBackgroundHandler().post(() -> {
                try {
                    Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER,
                            "clearKeystoreData:" + appIdToRemove);
                    mAppDataHelper.clearKeystoreData(UserHandle.USER_ALL, appIdToRemove);
                } finally {
                    Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
                }
            });
        }
    }

    void cleanUpResources(File codeFile, String[] instructionSets) {
        synchronized (mPm.mInstallLock) {
            cleanUpResourcesLI(codeFile, instructionSets);
        }
    }

    // Need installer lock especially for dex file removal.
    @GuardedBy("mPm.mInstallLock")
    private void cleanUpResourcesLI(File codeFile, String[] instructionSets) {
        // Try enumerating all code paths before deleting
        List<String> allCodePaths = Collections.EMPTY_LIST;
        if (codeFile != null && codeFile.exists()) {
            final ParseTypeImpl input = ParseTypeImpl.forDefaultParsing();
            final ParseResult<PackageLite> result = ApkLiteParseUtils.parsePackageLite(
                    input.reset(), codeFile, /* flags */ 0);
            if (result.isSuccess()) {
                // Ignore error; we tried our best
                allCodePaths = result.getResult().getAllApkPaths();
            }
        }

        removeCodePathLI(codeFile);
        removeDexFilesLI(allCodePaths, instructionSets);
    }

    @GuardedBy("mPm.mInstallLock")
    private void removeDexFilesLI(List<String> allCodePaths, String[] instructionSets) {
        if (!allCodePaths.isEmpty()) {
            if (instructionSets == null) {
                throw new IllegalStateException("instructionSet == null");
            }
            String[] dexCodeInstructionSets = getDexCodeInstructionSets(instructionSets);
            for (String codePath : allCodePaths) {
                for (String dexCodeInstructionSet : dexCodeInstructionSets) {
                    // TODO(b/251903639): Call into ART Service.
                    try {
                        mPm.mInstaller.rmdex(codePath, dexCodeInstructionSet);
                    } catch (LegacyDexoptDisabledException e) {
                        throw new RuntimeException(e);
                    } catch (Installer.InstallerException ignored) {
                    }
                }
            }
        }
    }

    void cleanUpForMoveInstall(String volumeUuid, String packageName, String fromCodePath) {
        final String toPathName = new File(fromCodePath).getName();
        final File codeFile = new File(Environment.getDataAppDirectory(volumeUuid), toPathName);
        Slog.d(TAG, "Cleaning up " + packageName + " on " + volumeUuid);
        final int[] userIds = mPm.mUserManager.getUserIds();
        synchronized (mPm.mInstallLock) {
            // Clean up both app data and code
            // All package moves are frozen until finished

            // We purposefully exclude FLAG_STORAGE_EXTERNAL here, since
            // this task was only focused on moving data on internal storage.
            // We don't want ART profiles cleared, because they don't move,
            // so we would be deleting the only copy (b/149200535).
            final int flags = FLAG_STORAGE_DE | FLAG_STORAGE_CE
                    | Installer.FLAG_CLEAR_APP_DATA_KEEP_ART_PROFILES;
            for (int userId : userIds) {
                try {
                    mPm.mInstaller.destroyAppData(volumeUuid, packageName, userId, flags,
                            0);
                } catch (Installer.InstallerException e) {
                    Slog.w(TAG, String.valueOf(e));
                }
            }
            removeCodePathLI(codeFile);
        }
    }
}
