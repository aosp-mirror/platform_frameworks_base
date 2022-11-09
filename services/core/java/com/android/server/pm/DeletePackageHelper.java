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

import static android.Manifest.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.MATCH_KNOWN_PACKAGES;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.storage.StorageManager.FLAG_STORAGE_CE;
import static android.os.storage.StorageManager.FLAG_STORAGE_DE;
import static android.os.storage.StorageManager.FLAG_STORAGE_EXTERNAL;

import static com.android.server.pm.InstructionSets.getAppDexInstructionSets;
import static com.android.server.pm.PackageManagerService.DEBUG_COMPRESSION;
import static com.android.server.pm.PackageManagerService.DEBUG_REMOVE;
import static com.android.server.pm.PackageManagerService.EMPTY_INT_ARRAY;
import static com.android.server.pm.PackageManagerService.PACKAGE_SCHEME;
import static com.android.server.pm.PackageManagerService.TAG;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ApplicationPackageManager;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageDeleteObserver2;
import android.content.pm.PackageChangeEvent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.UserInfo;
import android.content.pm.VersionedPackage;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.permission.PermissionManagerServiceInternal;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.PackageUserState;
import com.android.server.wm.ActivityTaskManagerInternal;

import dalvik.system.VMRuntime;

import java.util.Collections;
import java.util.List;

/**
 * Deletes a package. Uninstall if installed, or at least deletes the base directory if it's called
 * from a failed installation. Fixes user state after deletion.
 * Handles special treatments to system apps.
 * Relies on RemovePackageHelper to clear internal data structures.
 */
final class DeletePackageHelper {
    private static final boolean DEBUG_CLEAN_APKS = false;
    // ------- apps on sdcard specific code -------
    private static final boolean DEBUG_SD_INSTALL = false;

    private final PackageManagerService mPm;
    private final UserManagerInternal mUserManagerInternal;
    private final PermissionManagerServiceInternal mPermissionManager;
    private final RemovePackageHelper mRemovePackageHelper;
    private final AppDataHelper mAppDataHelper;

    // TODO(b/198166813): remove PMS dependency
    DeletePackageHelper(PackageManagerService pm, RemovePackageHelper removePackageHelper,
            AppDataHelper appDataHelper) {
        mPm = pm;
        mUserManagerInternal = mPm.mInjector.getUserManagerInternal();
        mPermissionManager = mPm.mInjector.getPermissionManagerServiceInternal();
        mRemovePackageHelper = removePackageHelper;
        mAppDataHelper = appDataHelper;
    }

    DeletePackageHelper(PackageManagerService pm) {
        mPm = pm;
        mAppDataHelper = new AppDataHelper(mPm);
        mUserManagerInternal = mPm.mInjector.getUserManagerInternal();
        mPermissionManager = mPm.mInjector.getPermissionManagerServiceInternal();
        mRemovePackageHelper = new RemovePackageHelper(mPm, mAppDataHelper);
    }

    /**
     *  This method is an internal method that could be invoked either
     *  to delete an installed package or to clean up a failed installation.
     *  After deleting an installed package, a broadcast is sent to notify any
     *  listeners that the package has been removed. For cleaning up a failed
     *  installation, the broadcast is not necessary since the package's
     *  installation wouldn't have sent the initial broadcast either
     *  The key steps in deleting a package are
     *  deleting the package information in internal structures like mPackages,
     *  deleting the packages base directories through installd
     *  updating mSettings to reflect current status
     *  persisting settings for later use
     *  sending a broadcast if necessary
     *
     *  @param removedBySystem A boolean to indicate the package was removed automatically without
     *                         the user-initiated action.
     */
    public int deletePackageX(String packageName, long versionCode, int userId, int deleteFlags,
            boolean removedBySystem) {
        final PackageRemovedInfo info = new PackageRemovedInfo(mPm);
        final boolean res;

        final int removeUser = (deleteFlags & PackageManager.DELETE_ALL_USERS) != 0
                ? UserHandle.USER_ALL : userId;

        if (mPm.isPackageDeviceAdmin(packageName, removeUser)) {
            Slog.w(TAG, "Not removing package " + packageName + ": has active device admin");
            return PackageManager.DELETE_FAILED_DEVICE_POLICY_MANAGER;
        }

        final PackageSetting uninstalledPs;
        final PackageSetting disabledSystemPs;
        final AndroidPackage pkg;

        // for the uninstall-updates case and restricted profiles, remember the per-
        // user handle installed state
        int[] allUsers;
        final int freezeUser;
        final SparseArray<TempUserState> priorUserStates;

        final boolean isInstallerPackage;
        /** enabled state of the uninstalled application */
        synchronized (mPm.mLock) {
            final Computer computer = mPm.snapshotComputer();
            uninstalledPs = mPm.mSettings.getPackageLPr(packageName);
            if (uninstalledPs == null) {
                Slog.w(TAG, "Not removing non-existent package " + packageName);
                return PackageManager.DELETE_FAILED_INTERNAL_ERROR;
            }

            if (versionCode != PackageManager.VERSION_CODE_HIGHEST
                    && uninstalledPs.getVersionCode() != versionCode) {
                Slog.w(TAG, "Not removing package " + packageName + " with versionCode "
                        + uninstalledPs.getVersionCode() + " != " + versionCode);
                return PackageManager.DELETE_FAILED_INTERNAL_ERROR;
            }

            if (PackageManagerServiceUtils.isSystemApp(uninstalledPs)
                    && ((deleteFlags & PackageManager.DELETE_SYSTEM_APP) == 0)) {
                UserInfo userInfo = mUserManagerInternal.getUserInfo(userId);
                if (userInfo == null || (!userInfo.isAdmin() && !mUserManagerInternal.getUserInfo(
                        mUserManagerInternal.getProfileParentId(userId)).isAdmin())) {
                    Slog.w(TAG, "Not removing package " + packageName
                            + " as only admin user (or their profile) may downgrade system apps");
                    EventLog.writeEvent(0x534e4554, "170646036", -1, packageName);
                    return PackageManager.DELETE_FAILED_USER_RESTRICTED;
                }
            }

            disabledSystemPs = mPm.mSettings.getDisabledSystemPkgLPr(packageName);
            // Static shared libs can be declared by any package, so let us not
            // allow removing a package if it provides a lib others depend on.
            pkg = mPm.mPackages.get(packageName);

            allUsers = mUserManagerInternal.getUserIds();

            if (pkg != null) {
                SharedLibraryInfo libraryInfo = null;
                if (pkg.getStaticSharedLibName() != null) {
                    libraryInfo = computer.getSharedLibraryInfo(pkg.getStaticSharedLibName(),
                            pkg.getStaticSharedLibVersion());
                } else if (pkg.getSdkLibName() != null) {
                    libraryInfo = computer.getSharedLibraryInfo(pkg.getSdkLibName(),
                            pkg.getSdkLibVersionMajor());
                }

                if (libraryInfo != null) {
                    for (int currUserId : allUsers) {
                        if (removeUser != UserHandle.USER_ALL && removeUser != currUserId) {
                            continue;
                        }
                        List<VersionedPackage> libClientPackages =
                                computer.getPackagesUsingSharedLibrary(libraryInfo,
                                        MATCH_KNOWN_PACKAGES, Process.SYSTEM_UID, currUserId);
                        if (!ArrayUtils.isEmpty(libClientPackages)) {
                            Slog.w(TAG, "Not removing package " + pkg.getManifestPackageName()
                                    + " hosting lib " + libraryInfo.getName() + " version "
                                    + libraryInfo.getLongVersion() + " used by " + libClientPackages
                                    + " for user " + currUserId);
                            return PackageManager.DELETE_FAILED_USED_SHARED_LIBRARY;
                        }
                    }
                }
            }

            info.mOrigUsers = uninstalledPs.queryInstalledUsers(allUsers, true);

            if (PackageManagerServiceUtils.isUpdatedSystemApp(uninstalledPs)
                    && ((deleteFlags & PackageManager.DELETE_SYSTEM_APP) == 0)) {
                // We're downgrading a system app, which will apply to all users, so
                // freeze them all during the downgrade
                freezeUser = UserHandle.USER_ALL;
                priorUserStates = new SparseArray<>();
                for (int i = 0; i < allUsers.length; i++) {
                    PackageUserState userState = uninstalledPs.readUserState(allUsers[i]);
                    priorUserStates.put(allUsers[i],
                            new TempUserState(userState.getEnabledState(),
                                    userState.getLastDisableAppCaller(), userState.isInstalled()));
                }
            } else {
                freezeUser = removeUser;
                priorUserStates = null;
            }

            isInstallerPackage = mPm.mSettings.isInstallerPackage(packageName);
        }

        synchronized (mPm.mInstallLock) {
            if (DEBUG_REMOVE) Slog.d(TAG, "deletePackageX: pkg=" + packageName + " user=" + userId);
            try (PackageFreezer freezer = mPm.freezePackageForDelete(packageName, freezeUser,
                    deleteFlags, "deletePackageX")) {
                res = deletePackageLIF(packageName, UserHandle.of(removeUser), true, allUsers,
                        deleteFlags | PackageManager.DELETE_CHATTY, info, true);
            }
            if (res && pkg != null) {
                final boolean packageInstalledForSomeUsers;
                synchronized (mPm.mLock) {
                    packageInstalledForSomeUsers = mPm.mPackages.get(pkg.getPackageName()) != null;
                }
                mPm.mInstantAppRegistry.onPackageUninstalled(pkg, uninstalledPs,
                        info.mRemovedUsers, packageInstalledForSomeUsers);
            }
            synchronized (mPm.mLock) {
                if (res) {
                    mPm.updateSequenceNumberLP(uninstalledPs, info.mRemovedUsers);
                    mPm.updateInstantAppInstallerLocked(packageName);
                }
            }
            ApplicationPackageManager.invalidateGetPackagesForUidCache();
        }

        if (res) {
            final boolean killApp = (deleteFlags & PackageManager.DELETE_DONT_KILL_APP) == 0;
            info.sendPackageRemovedBroadcasts(killApp, removedBySystem);
            info.sendSystemPackageUpdatedBroadcasts();
        }

        // Force a gc to clear up things.
        // Ask for a background one, it's fine to go on and not block here.
        VMRuntime.getRuntime().requestConcurrentGC();

        // Delete the resources here after sending the broadcast to let
        // other processes clean up before deleting resources.
        synchronized (mPm.mInstallLock) {
            if (info.mArgs != null) {
                info.mArgs.doPostDeleteLI(true);
            }

            boolean reEnableStub = false;

            if (priorUserStates != null) {
                synchronized (mPm.mLock) {
                    PackageSetting pkgSetting = mPm.getPackageSettingForMutation(packageName);
                    if (pkgSetting != null) {
                        AndroidPackage aPkg = pkgSetting.getPkg();
                        boolean pkgEnabled = aPkg != null && aPkg.isEnabled();
                        for (int i = 0; i < allUsers.length; i++) {
                            TempUserState priorUserState = priorUserStates.get(allUsers[i]);
                            int enabledState = priorUserState.enabledState;
                            pkgSetting.setEnabled(enabledState, allUsers[i],
                                    priorUserState.lastDisableAppCaller);
                            if (!reEnableStub && priorUserState.installed
                                    && (
                                    (enabledState == COMPONENT_ENABLED_STATE_DEFAULT && pkgEnabled)
                                            || enabledState == COMPONENT_ENABLED_STATE_ENABLED)) {
                                reEnableStub = true;
                            }
                        }
                    } else {
                        // This should not happen. If priorUserStates != null, we are uninstalling
                        // an update of a system app. In that case, mPm.mSettings.getPackageLpr()
                        // should return a non-null value for the target packageName because
                        // restoreDisabledSystemPackageLIF() is called during deletePackageLIF().
                        Slog.w(TAG, "Missing PackageSetting after uninstalling the update for"
                                + " system app: " + packageName + ". This should not happen.");
                    }
                    mPm.mSettings.writeAllUsersPackageRestrictionsLPr();
                }
            }

            final AndroidPackage stubPkg =
                    (disabledSystemPs == null) ? null : disabledSystemPs.getPkg();
            if (stubPkg != null && stubPkg.isStub()) {
                final PackageSetting stubPs;
                synchronized (mPm.mLock) {
                    stubPs = mPm.mSettings.getPackageLPr(stubPkg.getPackageName());
                }

                if (stubPs != null) {
                    if (reEnableStub) {
                        if (DEBUG_COMPRESSION) {
                            Slog.i(TAG, "Enabling system stub after removal; pkg: "
                                    + stubPkg.getPackageName());
                        }
                        new InstallPackageHelper(mPm).enableCompressedPackage(stubPkg, stubPs);
                    } else if (DEBUG_COMPRESSION) {
                        Slog.i(TAG, "System stub disabled for all users, leaving uncompressed "
                                + "after removal; pkg: " + stubPkg.getPackageName());
                    }
                }
            }
        }

        if (res && isInstallerPackage) {
            final PackageInstallerService packageInstallerService =
                    mPm.mInjector.getPackageInstallerService();
            packageInstallerService.onInstallerPackageDeleted(uninstalledPs.getAppId(), removeUser);
        }

        return res ? PackageManager.DELETE_SUCCEEDED : PackageManager.DELETE_FAILED_INTERNAL_ERROR;
    }

    /*
     * This method handles package deletion in general
     */
    public boolean deletePackageLIF(@NonNull String packageName, UserHandle user,
            boolean deleteCodeAndResources, @NonNull int[] allUserHandles, int flags,
            PackageRemovedInfo outInfo, boolean writeSettings) {
        final DeletePackageAction action;
        synchronized (mPm.mLock) {
            final PackageSetting ps = mPm.mSettings.getPackageLPr(packageName);
            final PackageSetting disabledPs = mPm.mSettings.getDisabledSystemPkgLPr(ps);
            action = mayDeletePackageLocked(outInfo, ps, disabledPs, flags, user);
        }
        if (DEBUG_REMOVE) Slog.d(TAG, "deletePackageLI: " + packageName + " user " + user);
        if (null == action) {
            if (DEBUG_REMOVE) Slog.d(TAG, "deletePackageLI: action was null");
            return false;
        }

        try {
            executeDeletePackageLIF(action, packageName, deleteCodeAndResources,
                    allUserHandles, writeSettings);
        } catch (SystemDeleteException e) {
            if (DEBUG_REMOVE) Slog.d(TAG, "deletePackageLI: system deletion failure", e);
            return false;
        }
        return true;
    }

    /**
     * @return a {@link DeletePackageAction} if the provided package and related state may be
     * deleted, {@code null} otherwise.
     */
    @Nullable
    public static DeletePackageAction mayDeletePackageLocked(
            PackageRemovedInfo outInfo, PackageSetting ps, @Nullable PackageSetting disabledPs,
            int flags, UserHandle user) {
        if (ps == null) {
            return null;
        }
        if (PackageManagerServiceUtils.isSystemApp(ps)) {
            final boolean deleteSystem = (flags & PackageManager.DELETE_SYSTEM_APP) != 0;
            final boolean deleteAllUsers =
                    user == null || user.getIdentifier() == UserHandle.USER_ALL;
            if ((!deleteSystem || deleteAllUsers) && disabledPs == null) {
                Slog.w(TAG, "Attempt to delete unknown system package "
                        + ps.getPkg().getPackageName());
                return null;
            }
            // Confirmed if the system package has been updated
            // An updated system app can be deleted. This will also have to restore
            // the system pkg from system partition reader
        }
        return new DeletePackageAction(ps, disabledPs, outInfo, flags, user);
    }

    /** Deletes a package. Only throws when install of a disabled package fails. */
    public void executeDeletePackageLIF(DeletePackageAction action,
            String packageName, boolean deleteCodeAndResources,
            @NonNull int[] allUserHandles, boolean writeSettings) throws SystemDeleteException {
        final PackageSetting ps = action.mDeletingPs;
        final PackageRemovedInfo outInfo = action.mRemovedInfo;
        final UserHandle user = action.mUser;
        final int flags = action.mFlags;
        final boolean systemApp = PackageManagerServiceUtils.isSystemApp(ps);

        // We need to get the permission state before package state is (potentially) destroyed.
        final SparseBooleanArray hadSuspendAppsPermission = new SparseBooleanArray();
        for (int userId : allUserHandles) {
            hadSuspendAppsPermission.put(userId, mPm.checkPermission(
                    Manifest.permission.SUSPEND_APPS, packageName, userId) == PERMISSION_GRANTED);
        }

        final int userId = user == null ? UserHandle.USER_ALL : user.getIdentifier();

        if ((!systemApp || (flags & PackageManager.DELETE_SYSTEM_APP) != 0)
                && userId != UserHandle.USER_ALL) {
            // The caller is asking that the package only be deleted for a single
            // user.  To do this, we just mark its uninstalled state and delete
            // its data. If this is a system app, we only allow this to happen if
            // they have set the special DELETE_SYSTEM_APP which requests different
            // semantics than normal for uninstalling system apps.
            final boolean clearPackageStateAndReturn;
            synchronized (mPm.mLock) {
                markPackageUninstalledForUserLPw(ps, user);
                if (!systemApp) {
                    // Do not uninstall the APK if an app should be cached
                    boolean keepUninstalledPackage =
                            mPm.shouldKeepUninstalledPackageLPr(packageName);
                    if (ps.isAnyInstalled(
                            mUserManagerInternal.getUserIds()) || keepUninstalledPackage) {
                        // Other users still have this package installed, so all
                        // we need to do is clear this user's data and save that
                        // it is uninstalled.
                        if (DEBUG_REMOVE) Slog.d(TAG, "Still installed by other users");
                        clearPackageStateAndReturn = true;
                    } else {
                        // We need to set it back to 'installed' so the uninstall
                        // broadcasts will be sent correctly.
                        if (DEBUG_REMOVE) Slog.d(TAG, "Not installed by other users, full delete");
                        ps.setInstalled(true, userId);
                        mPm.mSettings.writeKernelMappingLPr(ps);
                        clearPackageStateAndReturn = false;
                    }
                } else {
                    // This is a system app, so we assume that the
                    // other users still have this package installed, so all
                    // we need to do is clear this user's data and save that
                    // it is uninstalled.
                    if (DEBUG_REMOVE) Slog.d(TAG, "Deleting system app");
                    clearPackageStateAndReturn = true;
                }
            }
            if (clearPackageStateAndReturn) {
                clearPackageStateForUserLIF(ps, userId, outInfo, flags);
                mPm.scheduleWritePackageRestrictions(user);
                return;
            }
        }

        // TODO(b/109941548): break reasons for ret = false out into mayDelete method
        if (systemApp) {
            if (DEBUG_REMOVE) Slog.d(TAG, "Removing system package: " + ps.getPackageName());
            // When an updated system application is deleted we delete the existing resources
            // as well and fall back to existing code in system partition
            deleteInstalledSystemPackage(action, allUserHandles, writeSettings);
            new InstallPackageHelper(mPm).restoreDisabledSystemPackageLIF(
                    action, allUserHandles, writeSettings);
        } else {
            if (DEBUG_REMOVE) Slog.d(TAG, "Removing non-system package: " + ps.getPackageName());
            deleteInstalledPackageLIF(ps, deleteCodeAndResources, flags, allUserHandles,
                    outInfo, writeSettings);
        }

        // If the package removed had SUSPEND_APPS, unset any restrictions that might have been in
        // place for all affected users.
        int[] affectedUserIds = (outInfo != null) ? outInfo.mRemovedUsers : null;
        if (affectedUserIds == null) {
            affectedUserIds = mPm.resolveUserIds(userId);
        }
        final Computer snapshot = mPm.snapshotComputer();
        for (final int affectedUserId : affectedUserIds) {
            if (hadSuspendAppsPermission.get(affectedUserId)) {
                mPm.unsuspendForSuspendingPackage(snapshot, packageName, affectedUserId);
                mPm.removeAllDistractingPackageRestrictions(snapshot, affectedUserId);
            }
        }

        // Take a note whether we deleted the package for all users
        if (outInfo != null) {
            outInfo.mRemovedForAllUsers = mPm.mPackages.get(ps.getPackageName()) == null;
        }
    }

    private void clearPackageStateForUserLIF(PackageSetting ps, int userId,
            PackageRemovedInfo outInfo, int flags) {
        final AndroidPackage pkg;
        final SharedUserSetting sus;
        synchronized (mPm.mLock) {
            pkg = mPm.mPackages.get(ps.getPackageName());
            sus = mPm.mSettings.getSharedUserSettingLPr(ps);
        }

        mAppDataHelper.destroyAppProfilesLIF(pkg);

        final List<AndroidPackage> sharedUserPkgs =
                sus != null ? sus.getPackages() : Collections.emptyList();
        final PreferredActivityHelper preferredActivityHelper = new PreferredActivityHelper(mPm);
        final int[] userIds = (userId == UserHandle.USER_ALL) ? mUserManagerInternal.getUserIds()
                : new int[] {userId};
        for (int nextUserId : userIds) {
            if (DEBUG_REMOVE) {
                Slog.d(TAG, "Updating package:" + ps.getPackageName() + " install state for user:"
                        + nextUserId);
            }
            if ((flags & PackageManager.DELETE_KEEP_DATA) == 0) {
                mAppDataHelper.destroyAppDataLIF(pkg, nextUserId,
                        FLAG_STORAGE_DE | FLAG_STORAGE_CE | FLAG_STORAGE_EXTERNAL);
            }
            mAppDataHelper.clearKeystoreData(nextUserId, ps.getAppId());
            preferredActivityHelper.clearPackagePreferredActivities(ps.getPackageName(),
                    nextUserId);
            mPm.mDomainVerificationManager.clearPackageForUser(ps.getPackageName(), nextUserId);
        }
        mPermissionManager.onPackageUninstalled(ps.getPackageName(), ps.getAppId(), pkg,
                sharedUserPkgs, userId);

        if (outInfo != null) {
            if ((flags & PackageManager.DELETE_KEEP_DATA) == 0) {
                outInfo.mDataRemoved = true;
            }
            outInfo.mRemovedPackage = ps.getPackageName();
            outInfo.mInstallerPackageName = ps.getInstallSource().installerPackageName;
            outInfo.mIsStaticSharedLib = pkg != null && pkg.getStaticSharedLibName() != null;
            outInfo.mRemovedAppId = ps.getAppId();
            outInfo.mRemovedUsers = userIds;
            outInfo.mBroadcastUsers = userIds;
            outInfo.mIsExternal = ps.isExternalStorage();
        }
    }

    private void deleteInstalledPackageLIF(PackageSetting ps,
            boolean deleteCodeAndResources, int flags, @NonNull int[] allUserHandles,
            PackageRemovedInfo outInfo, boolean writeSettings) {
        synchronized (mPm.mLock) {
            if (outInfo != null) {
                outInfo.mUid = ps.getAppId();
                outInfo.mBroadcastAllowList = mPm.mAppsFilter.getVisibilityAllowList(
                        mPm.snapshotComputer(), ps, allUserHandles,
                        mPm.mSettings.getPackagesLocked());
            }
        }

        // Delete package data from internal structures and also remove data if flag is set
        mRemovePackageHelper.removePackageDataLIF(
                ps, allUserHandles, outInfo, flags, writeSettings);

        // Delete application code and resources only for parent packages
        if (deleteCodeAndResources && (outInfo != null)) {
            outInfo.mArgs = new FileInstallArgs(
                    ps.getPathString(), getAppDexInstructionSets(
                            ps.getPrimaryCpuAbi(), ps.getSecondaryCpuAbi()), mPm);
            if (DEBUG_SD_INSTALL) Slog.i(TAG, "args=" + outInfo.mArgs);
        }
    }

    @GuardedBy("mPm.mLock")
    private void markPackageUninstalledForUserLPw(PackageSetting ps, UserHandle user) {
        final int[] userIds = (user == null || user.getIdentifier() == UserHandle.USER_ALL)
                ? mUserManagerInternal.getUserIds()
                : new int[] {user.getIdentifier()};
        for (int nextUserId : userIds) {
            if (DEBUG_REMOVE) {
                Slog.d(TAG, "Marking package:" + ps.getPackageName()
                        + " uninstalled for user:" + nextUserId);
            }
            ps.setUserState(nextUserId, 0, COMPONENT_ENABLED_STATE_DEFAULT,
                    false /*installed*/,
                    true /*stopped*/,
                    true /*notLaunched*/,
                    false /*hidden*/,
                    0 /*distractionFlags*/,
                    null /*suspendParams*/,
                    false /*instantApp*/,
                    false /*virtualPreload*/,
                    null /*lastDisableAppCaller*/,
                    null /*enabledComponents*/,
                    null /*disabledComponents*/,
                    PackageManager.INSTALL_REASON_UNKNOWN,
                    PackageManager.UNINSTALL_REASON_UNKNOWN,
                    null /*harmfulAppWarning*/,
                    null /*splashScreenTheme*/,
                    0 /*firstInstallTime*/);
        }
        mPm.mSettings.writeKernelMappingLPr(ps);
    }

    private void deleteInstalledSystemPackage(DeletePackageAction action,
            @NonNull int[] allUserHandles, boolean writeSettings) {
        int flags = action.mFlags;
        final PackageSetting deletedPs = action.mDeletingPs;
        final PackageRemovedInfo outInfo = action.mRemovedInfo;
        final boolean applyUserRestrictions = outInfo != null && (outInfo.mOrigUsers != null);
        final AndroidPackage deletedPkg = deletedPs.getPkg();
        // Confirm if the system package has been updated
        // An updated system app can be deleted. This will also have to restore
        // the system pkg from system partition
        // reader
        final PackageSetting disabledPs = action.mDisabledPs;
        if (DEBUG_REMOVE) {
            Slog.d(TAG, "deleteSystemPackageLI: newPs=" + deletedPkg.getPackageName()
                    + " disabledPs=" + disabledPs);
        }
        Slog.d(TAG, "Deleting system pkg from data partition");

        if (DEBUG_REMOVE) {
            if (applyUserRestrictions) {
                Slog.d(TAG, "Remembering install states:");
                for (int userId : allUserHandles) {
                    final boolean finstalled = ArrayUtils.contains(outInfo.mOrigUsers, userId);
                    Slog.d(TAG, "   u=" + userId + " inst=" + finstalled);
                }
            }
        }

        if (outInfo != null) {
            // Delete the updated package
            outInfo.mIsRemovedPackageSystemUpdate = true;
        }

        if (disabledPs.getVersionCode() < deletedPs.getVersionCode()
                || disabledPs.getAppId() != deletedPs.getAppId()) {
            // Delete data for downgrades, or when the system app changed appId
            flags &= ~PackageManager.DELETE_KEEP_DATA;
        } else {
            // Preserve data by setting flag
            flags |= PackageManager.DELETE_KEEP_DATA;
        }

        deleteInstalledPackageLIF(deletedPs, true, flags, allUserHandles, outInfo, writeSettings);
    }

    public void deletePackageVersionedInternal(VersionedPackage versionedPackage,
            final IPackageDeleteObserver2 observer, final int userId, final int deleteFlags,
            final boolean allowSilentUninstall) {
        final int callingUid = Binder.getCallingUid();
        mPm.mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.DELETE_PACKAGES, null);
        final Computer snapshot = mPm.snapshotComputer();
        final boolean canViewInstantApps = snapshot.canViewInstantApps(callingUid, userId);
        Preconditions.checkNotNull(versionedPackage);
        Preconditions.checkNotNull(observer);
        Preconditions.checkArgumentInRange(versionedPackage.getLongVersionCode(),
                PackageManager.VERSION_CODE_HIGHEST,
                Long.MAX_VALUE, "versionCode must be >= -1");

        final String packageName = versionedPackage.getPackageName();
        final long versionCode = versionedPackage.getLongVersionCode();

        if (mPm.mProtectedPackages.isPackageDataProtected(userId, packageName)) {
            mPm.mHandler.post(() -> {
                try {
                    Slog.w(TAG, "Attempted to delete protected package: " + packageName);
                    observer.onPackageDeleted(packageName,
                            PackageManager.DELETE_FAILED_INTERNAL_ERROR, null);
                } catch (RemoteException re) {
                }
            });
            return;
        }

        try {
            if (mPm.mInjector.getLocalService(ActivityTaskManagerInternal.class)
                    .isBaseOfLockedTask(packageName)) {
                observer.onPackageDeleted(
                        packageName, PackageManager.DELETE_FAILED_APP_PINNED, null);
                EventLog.writeEvent(0x534e4554, "127605586", -1, "");
                return;
            }
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }

        // Normalize package name to handle renamed packages and static libs
        final String internalPackageName =
                snapshot.resolveInternalPackageName(packageName, versionCode);

        final int uid = Binder.getCallingUid();
        if (!isOrphaned(snapshot, internalPackageName)
                && !allowSilentUninstall
                && !isCallerAllowedToSilentlyUninstall(snapshot, uid, internalPackageName)) {
            mPm.mHandler.post(() -> {
                try {
                    final Intent intent = new Intent(Intent.ACTION_UNINSTALL_PACKAGE);
                    intent.setData(Uri.fromParts(PACKAGE_SCHEME, packageName, null));
                    intent.putExtra(PackageInstaller.EXTRA_CALLBACK, observer.asBinder());
                    observer.onUserActionRequired(intent);
                } catch (RemoteException re) {
                }
            });
            return;
        }
        final boolean deleteAllUsers = (deleteFlags & PackageManager.DELETE_ALL_USERS) != 0;
        final int[] users = deleteAllUsers ? mUserManagerInternal.getUserIds() : new int[]{userId};
        if (UserHandle.getUserId(uid) != userId || (deleteAllUsers && users.length > 1)) {
            mPm.mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                    "deletePackage for user " + userId);
        }

        if (mPm.isUserRestricted(userId, UserManager.DISALLOW_UNINSTALL_APPS)) {
            mPm.mHandler.post(() -> {
                try {
                    observer.onPackageDeleted(packageName,
                            PackageManager.DELETE_FAILED_USER_RESTRICTED, null);
                } catch (RemoteException re) {
                }
            });
            return;
        }

        if (!deleteAllUsers && snapshot.getBlockUninstallForUser(internalPackageName, userId)) {
            mPm.mHandler.post(() -> {
                try {
                    observer.onPackageDeleted(packageName,
                            PackageManager.DELETE_FAILED_OWNER_BLOCKED, null);
                } catch (RemoteException re) {
                }
            });
            return;
        }

        if (DEBUG_REMOVE) {
            Slog.d(TAG, "deletePackageAsUser: pkg=" + internalPackageName + " user=" + userId
                    + " deleteAllUsers: " + deleteAllUsers + " version="
                    + (versionCode == PackageManager.VERSION_CODE_HIGHEST
                    ? "VERSION_CODE_HIGHEST" : versionCode));
        }
        // Queue up an async operation since the package deletion may take a little while.
        mPm.mHandler.post(() -> {
            int returnCode;
            final Computer innerSnapshot = mPm.snapshotComputer();
            final PackageStateInternal packageState =
                    innerSnapshot.getPackageStateInternal(internalPackageName);
            boolean doDeletePackage = true;
            if (packageState != null) {
                final boolean targetIsInstantApp =
                        packageState.getUserStateOrDefault(UserHandle.getUserId(callingUid))
                                .isInstantApp();
                doDeletePackage = !targetIsInstantApp
                        || canViewInstantApps;
            }
            if (doDeletePackage) {
                if (!deleteAllUsers) {
                    returnCode = deletePackageX(internalPackageName, versionCode,
                            userId, deleteFlags, false /*removedBySystem*/);
                } else {
                    int[] blockUninstallUserIds = getBlockUninstallForUsers(innerSnapshot,
                            internalPackageName, users);
                    // If nobody is blocking uninstall, proceed with delete for all users
                    if (ArrayUtils.isEmpty(blockUninstallUserIds)) {
                        returnCode = deletePackageX(internalPackageName, versionCode,
                                userId, deleteFlags, false /*removedBySystem*/);
                    } else {
                        // Otherwise uninstall individually for users with blockUninstalls=false
                        final int userFlags = deleteFlags & ~PackageManager.DELETE_ALL_USERS;
                        for (int userId1 : users) {
                            if (!ArrayUtils.contains(blockUninstallUserIds, userId1)) {
                                returnCode = deletePackageX(internalPackageName, versionCode,
                                        userId1, userFlags, false /*removedBySystem*/);
                                if (returnCode != PackageManager.DELETE_SUCCEEDED) {
                                    Slog.w(TAG, "Package delete failed for user " + userId1
                                            + ", returnCode " + returnCode);
                                }
                            }
                        }
                        // The app has only been marked uninstalled for certain users.
                        // We still need to report that delete was blocked
                        returnCode = PackageManager.DELETE_FAILED_OWNER_BLOCKED;
                    }
                }
            } else {
                returnCode = PackageManager.DELETE_FAILED_INTERNAL_ERROR;
            }
            try {
                observer.onPackageDeleted(packageName, returnCode, null);
            } catch (RemoteException e) {
                Log.i(TAG, "Observer no longer exists.");
            } //end catch
            notifyPackageChangeObserversOnDelete(packageName, versionCode);

            // Prune unused static shared libraries which have been cached a period of time
            mPm.schedulePruneUnusedStaticSharedLibraries(true /* delay */);
        });
    }

    private boolean isOrphaned(@NonNull Computer snapshot, String packageName) {
        final PackageStateInternal packageState = snapshot.getPackageStateInternal(packageName);
        return packageState != null && packageState.getInstallSource().isOrphaned;
    }

    private boolean isCallerAllowedToSilentlyUninstall(@NonNull Computer snapshot, int callingUid,
            String pkgName) {
        if (callingUid == Process.SHELL_UID || callingUid == Process.ROOT_UID
                || UserHandle.getAppId(callingUid) == Process.SYSTEM_UID) {
            return true;
        }
        final int callingUserId = UserHandle.getUserId(callingUid);
        // If the caller installed the pkgName, then allow it to silently uninstall.
        if (callingUid == snapshot.getPackageUid(snapshot.getInstallerPackageName(pkgName), 0,
                callingUserId)) {
            return true;
        }

        // Allow package verifier to silently uninstall.
        if (mPm.mRequiredVerifierPackage != null && callingUid == snapshot
                .getPackageUid(mPm.mRequiredVerifierPackage, 0, callingUserId)) {
            return true;
        }

        // Allow package uninstaller to silently uninstall.
        if (mPm.mRequiredUninstallerPackage != null && callingUid == snapshot
                .getPackageUid(mPm.mRequiredUninstallerPackage, 0, callingUserId)) {
            return true;
        }

        // Allow storage manager to silently uninstall.
        if (mPm.mStorageManagerPackage != null && callingUid == snapshot.getPackageUid(
                mPm.mStorageManagerPackage, 0, callingUserId)) {
            return true;
        }

        // Allow caller having MANAGE_PROFILE_AND_DEVICE_OWNERS permission to silently
        // uninstall for device owner provisioning.
        return snapshot.checkUidPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS, callingUid)
                == PERMISSION_GRANTED;
    }

    private int[] getBlockUninstallForUsers(@NonNull Computer snapshot, String packageName,
            int[] userIds) {
        int[] result = EMPTY_INT_ARRAY;
        for (int userId : userIds) {
            if (snapshot.getBlockUninstallForUser(packageName, userId)) {
                result = ArrayUtils.appendInt(result, userId);
            }
        }
        return result;
    }

    private void notifyPackageChangeObserversOnDelete(String packageName, long version) {
        PackageChangeEvent pkgChangeEvent = new PackageChangeEvent();
        pkgChangeEvent.packageName = packageName;
        pkgChangeEvent.version = version;
        pkgChangeEvent.lastUpdateTimeMillis = 0L;
        pkgChangeEvent.newInstalled = false;
        pkgChangeEvent.dataRemoved = false;
        pkgChangeEvent.isDeleted = true;

        mPm.notifyPackageChangeObservers(pkgChangeEvent);
    }

    private static class TempUserState {
        public final int enabledState;
        @Nullable
        public final String lastDisableAppCaller;
        public final boolean installed;

        private TempUserState(int enabledState, @Nullable String lastDisableAppCaller,
                boolean installed) {
            this.enabledState = enabledState;
            this.lastDisableAppCaller = lastDisableAppCaller;
            this.installed = installed;
        }
    }

    /**
     * We're removing userId and would like to remove any downloaded packages
     * that are no longer in use by any other user.
     * @param userId the user being removed
     */
    @GuardedBy("mPm.mLock")
    public void removeUnusedPackagesLPw(UserManagerService userManager, final int userId) {
        int [] users = userManager.getUserIds();
        final int numPackages = mPm.mSettings.getPackagesLocked().size();
        for (int index = 0; index < numPackages; index++) {
            final PackageSetting ps = mPm.mSettings.getPackagesLocked().valueAt(index);
            if (ps.getPkg() == null) {
                continue;
            }
            final String packageName = ps.getPkg().getPackageName();
            // Skip over if system app, static shared library or and SDK library.
            if ((ps.getFlags() & ApplicationInfo.FLAG_SYSTEM) != 0
                    || !TextUtils.isEmpty(ps.getPkg().getStaticSharedLibName())
                    || !TextUtils.isEmpty(ps.getPkg().getSdkLibName())) {
                continue;
            }
            if (DEBUG_CLEAN_APKS) {
                Slog.i(TAG, "Checking package " + packageName);
            }
            boolean keep = mPm.shouldKeepUninstalledPackageLPr(packageName);
            if (keep) {
                if (DEBUG_CLEAN_APKS) {
                    Slog.i(TAG, "  Keeping package " + packageName + " - requested by DO");
                }
            } else {
                for (int i = 0; i < users.length; i++) {
                    if (users[i] != userId && ps.getInstalled(users[i])) {
                        keep = true;
                        if (DEBUG_CLEAN_APKS) {
                            Slog.i(TAG, "  Keeping package " + packageName + " for user "
                                    + users[i]);
                        }
                        break;
                    }
                }
            }
            if (!keep) {
                if (DEBUG_CLEAN_APKS) {
                    Slog.i(TAG, "  Removing package " + packageName);
                }
                //end run
                mPm.mHandler.post(() -> deletePackageX(
                        packageName, PackageManager.VERSION_CODE_HIGHEST,
                        userId, 0, true /*removedBySystem*/));
            }
        }
    }

    public void deleteExistingPackageAsUser(VersionedPackage versionedPackage,
            final IPackageDeleteObserver2 observer, final int userId) {
        mPm.mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.DELETE_PACKAGES, null);
        Preconditions.checkNotNull(versionedPackage);
        Preconditions.checkNotNull(observer);
        final String packageName = versionedPackage.getPackageName();
        final long versionCode = versionedPackage.getLongVersionCode();

        int installedForUsersCount = 0;
        synchronized (mPm.mLock) {
            // Normalize package name to handle renamed packages and static libs
            final String internalPkgName = mPm.snapshotComputer()
                    .resolveInternalPackageName(packageName, versionCode);
            final PackageSetting ps = mPm.mSettings.getPackageLPr(internalPkgName);
            if (ps != null) {
                int[] installedUsers = ps.queryInstalledUsers(mUserManagerInternal.getUserIds(),
                        true);
                installedForUsersCount = installedUsers.length;
            }
        }

        if (installedForUsersCount > 1) {
            deletePackageVersionedInternal(versionedPackage, observer, userId, 0, true);
        } else {
            try {
                observer.onPackageDeleted(packageName, PackageManager.DELETE_FAILED_INTERNAL_ERROR,
                        null);
            } catch (RemoteException re) {
            }
        }
    }
}
