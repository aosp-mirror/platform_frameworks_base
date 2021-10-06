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
import static android.os.incremental.IncrementalManager.isIncrementalPath;
import static android.os.storage.StorageManager.FLAG_STORAGE_CE;
import static android.os.storage.StorageManager.FLAG_STORAGE_DE;
import static android.os.storage.StorageManager.FLAG_STORAGE_EXTERNAL;

import static com.android.server.pm.PackageManagerService.DEBUG_INSTALL;
import static com.android.server.pm.PackageManagerService.DEBUG_REMOVE;
import static com.android.server.pm.PackageManagerService.RANDOM_DIR_PREFIX;
import static com.android.server.pm.PackageManagerService.TAG;

import android.annotation.NonNull;
import android.content.pm.PackageManager;
import android.content.pm.parsing.component.ParsedInstrumentation;
import android.os.UserHandle;
import android.os.incremental.IncrementalManager;
import android.util.Log;
import android.util.Slog;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;
import com.android.server.pm.parsing.PackageCacher;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.PackageImpl;
import com.android.server.pm.permission.PermissionManagerServiceInternal;

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

    // TODO(b/198166813): remove PMS dependency
    RemovePackageHelper(PackageManagerService pm) {
        mPm = pm;
        mIncrementalManager = mPm.mInjector.getIncrementalManager();
        mInstaller = mPm.mInjector.getInstaller();
        mUserManagerInternal = mPm.mInjector.getUserManagerInternal();
        mPermissionManager = mPm.mInjector.getPermissionManagerServiceInternal();
    }

    @GuardedBy("mPm.mInstallLock")
    public void removeCodePathLI(File codePath) {
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

                mInstaller.rmPackageDir(codePath.getAbsolutePath());
                if (needRemoveParent) {
                    mInstaller.rmPackageDir(codePathParent.getAbsolutePath());
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

    public void removePackageLI(AndroidPackage pkg, boolean chatty) {
        // Remove the parent package setting
        PackageSetting ps = mPm.getPackageSetting(pkg.getPackageName());
        if (ps != null) {
            removePackageLI(ps.getPackageName(), chatty);
        } else if (DEBUG_REMOVE && chatty) {
            Log.d(TAG, "Not removing package " + pkg.getPackageName() + "; mExtras == null");
        }
    }

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
                cleanPackageDataStructuresLILPw(removedPackage, chatty);
            }
        }
    }

    private void cleanPackageDataStructuresLILPw(AndroidPackage pkg, boolean chatty) {
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
        if (pkg.isSystem()) {
            // Only system apps can hold shared libraries.
            final int libraryNamesSize = pkg.getLibraryNames().size();
            for (i = 0; i < libraryNamesSize; i++) {
                String name = pkg.getLibraryNames().get(i);
                if (mPm.removeSharedLibraryLPw(name, 0)) {
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

        // Any package can hold static shared libraries.
        if (pkg.getStaticSharedLibName() != null) {
            if (mPm.removeSharedLibraryLPw(pkg.getStaticSharedLibName(),
                    pkg.getStaticSharedLibVersion())) {
                if (DEBUG_REMOVE && chatty) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    r.append(pkg.getStaticSharedLibName());
                }
            }
        }

        if (r != null) {
            if (DEBUG_REMOVE) Log.d(TAG, "  Libraries: " + r);
        }
    }

    /*
     * This method deletes the package from internal data structures. If the DELETE_KEEP_DATA
     * flag is not set, the data directory is removed as well.
     * make sure this flag is set for partially installed apps. If not its meaningless to
     * delete a partially installed application.
     */
    public void removePackageDataLIF(final PackageSetting deletedPs, @NonNull int[] allUserHandles,
            PackageRemovedInfo outInfo, int flags, boolean writeSettings) {
        String packageName = deletedPs.getPackageName();
        if (DEBUG_REMOVE) Slog.d(TAG, "removePackageDataLI: " + deletedPs);
        // Retrieve object to delete permissions for shared user later on
        final AndroidPackage deletedPkg = deletedPs.getPkg();
        if (outInfo != null) {
            outInfo.mRemovedPackage = packageName;
            outInfo.mInstallerPackageName = deletedPs.getInstallSource().installerPackageName;
            outInfo.mIsStaticSharedLib = deletedPkg != null
                    && deletedPkg.getStaticSharedLibName() != null;
            outInfo.populateUsers(
                    deletedPs == null ? null : deletedPs.queryInstalledUsers(
                            mUserManagerInternal.getUserIds(), true), deletedPs);
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
            destroyAppDataLIF(resolvedPkg, UserHandle.USER_ALL,
                    FLAG_STORAGE_DE | FLAG_STORAGE_CE | FLAG_STORAGE_EXTERNAL);
            destroyAppProfilesLIF(resolvedPkg);
            if (outInfo != null) {
                outInfo.mDataRemoved = true;
            }
        }

        int removedAppId = -1;

        // writer
        boolean installedStateChanged = false;
        if (deletedPs != null) {
            if ((flags & PackageManager.DELETE_KEEP_DATA) == 0) {
                final SparseBooleanArray changedUsers = new SparseBooleanArray();
                synchronized (mPm.mLock) {
                    mPm.mDomainVerificationManager.clearPackage(deletedPs.getPackageName());
                    mPm.mSettings.getKeySetManagerService().removeAppKeySetDataLPw(packageName);
                    mPm.mAppsFilter.removePackage(mPm.getPackageSetting(packageName),
                            false /* isReplace */);
                    removedAppId = mPm.mSettings.removePackageLPw(packageName);
                    if (outInfo != null) {
                        outInfo.mRemovedAppId = removedAppId;
                    }
                    if (!mPm.mSettings.isDisabledSystemPackageLPr(packageName)) {
                        // If we don't have a disabled system package to reinstall, the package is
                        // really gone and its permission state should be removed.
                        final SharedUserSetting sus = deletedPs.getSharedUser();
                        List<AndroidPackage> sharedUserPkgs = sus != null ? sus.getPackages()
                                : null;
                        if (sharedUserPkgs == null) {
                            sharedUserPkgs = Collections.emptyList();
                        }
                        mPermissionManager.onPackageUninstalled(packageName, deletedPs.getAppId(),
                                deletedPs.getPkg(), sharedUserPkgs, UserHandle.USER_ALL);
                    }
                    mPm.clearPackagePreferredActivitiesLPw(
                            deletedPs.getPackageName(), changedUsers, UserHandle.USER_ALL);

                    mPm.mSettings.removeRenamedPackageLPw(deletedPs.getRealName());
                }
                if (changedUsers.size() > 0) {
                    mPm.updateDefaultHomeNotLocked(changedUsers);
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
            // A user ID was deleted here. Go through all users and remove it
            // from KeyStore.
            mPm.removeKeystoreDataIfNeeded(
                    mUserManagerInternal, UserHandle.USER_ALL, removedAppId);
        }
    }

    public void destroyAppDataLIF(AndroidPackage pkg, int userId, int flags) {
        if (pkg == null) {
            Slog.wtf(TAG, "Package was null!", new Throwable());
            return;
        }
        destroyAppDataLeafLIF(pkg, userId, flags);
    }

    public void destroyAppDataLeafLIF(AndroidPackage pkg, int userId, int flags) {
        final PackageSetting ps;
        synchronized (mPm.mLock) {
            ps = mPm.mSettings.getPackageLPr(pkg.getPackageName());
        }
        for (int realUserId : mPm.resolveUserIds(userId)) {
            final long ceDataInode = (ps != null) ? ps.getCeDataInode(realUserId) : 0;
            try {
                mInstaller.destroyAppData(pkg.getVolumeUuid(), pkg.getPackageName(), realUserId,
                        flags, ceDataInode);
            } catch (Installer.InstallerException e) {
                Slog.w(TAG, String.valueOf(e));
            }
            mPm.getDexManager().notifyPackageDataDestroyed(pkg.getPackageName(), userId);
        }
    }

    public void destroyAppProfilesLIF(AndroidPackage pkg) {
        if (pkg == null) {
            Slog.wtf(TAG, "Package was null!", new Throwable());
            return;
        }
        destroyAppProfilesLeafLIF(pkg);
    }

    private void destroyAppProfilesLeafLIF(AndroidPackage pkg) {
        try {
            mInstaller.destroyAppProfiles(pkg.getPackageName());
        } catch (Installer.InstallerException e) {
            Slog.w(TAG, String.valueOf(e));
        }
    }
}
