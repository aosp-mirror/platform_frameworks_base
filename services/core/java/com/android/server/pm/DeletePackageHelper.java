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

import static android.Manifest.permission.CONTROL_KEYGUARD;
import static android.Manifest.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.DELETE_KEEP_DATA;
import static android.content.pm.PackageManager.DELETE_SUCCEEDED;
import static android.content.pm.PackageManager.MATCH_KNOWN_PACKAGES;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import static com.android.server.pm.InstructionSets.getAppDexInstructionSets;
import static com.android.server.pm.PackageManagerService.DEBUG_COMPRESSION;
import static com.android.server.pm.PackageManagerService.DEBUG_REMOVE;
import static com.android.server.pm.PackageManagerService.EMPTY_INT_ARRAY;
import static com.android.server.pm.PackageManagerService.PACKAGE_SCHEME;
import static com.android.server.pm.PackageManagerService.TAG;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ApplicationExitInfo;
import android.app.ApplicationPackageManager;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.Flags;
import android.content.pm.IPackageDeleteObserver2;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.UserInfo;
import android.content.pm.UserProperties;
import android.content.pm.VersionedPackage;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.ArchiveState;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.PackageUserState;
import com.android.server.wm.ActivityTaskManagerInternal;

import dalvik.system.VMRuntime;

/**
 * Deletes a package. Uninstall if installed, or at least deletes the base directory if it's called
 * from a failed installation. Fixes user state after deletion.
 * Handles special treatments to system apps.
 * Relies on RemovePackageHelper to clear internal data structures and remove app data.
 */
final class DeletePackageHelper {
    private static final boolean DEBUG_CLEAN_APKS = false;
    // ------- apps on sdcard specific code -------
    private static final boolean DEBUG_SD_INSTALL = false;

    private final PackageManagerService mPm;
    private final UserManagerInternal mUserManagerInternal;
    private final RemovePackageHelper mRemovePackageHelper;
    private final BroadcastHelper mBroadcastHelper;

    // TODO(b/198166813): remove PMS dependency
    DeletePackageHelper(PackageManagerService pm, RemovePackageHelper removePackageHelper,
                        BroadcastHelper broadcastHelper) {
        mPm = pm;
        mUserManagerInternal = mPm.mInjector.getUserManagerInternal();
        mRemovePackageHelper = removePackageHelper;
        mBroadcastHelper = broadcastHelper;
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
        final PackageRemovedInfo info = new PackageRemovedInfo();
        final boolean res;

        final int removeUser = (deleteFlags & PackageManager.DELETE_ALL_USERS) != 0
                ? UserHandle.USER_ALL : userId;

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

            if (PackageManagerServiceUtils.isUpdatedSystemApp(uninstalledPs)
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
                if (pkg.getStaticSharedLibraryName() != null) {
                    libraryInfo = computer.getSharedLibraryInfo(pkg.getStaticSharedLibraryName(),
                            pkg.getStaticSharedLibraryVersion());
                } else if (pkg.getSdkLibraryName() != null) {
                    libraryInfo = computer.getSharedLibraryInfo(pkg.getSdkLibraryName(),
                            pkg.getSdkLibVersionMajor());
                }

                if (libraryInfo != null) {
                    boolean flagSdkLibIndependence = Flags.sdkLibIndependence();
                    for (int currUserId : allUsers) {
                        if (removeUser != UserHandle.USER_ALL && removeUser != currUserId) {
                            continue;
                        }
                        var libClientPackagesPair = computer.getPackagesUsingSharedLibrary(
                                libraryInfo, MATCH_KNOWN_PACKAGES, Process.SYSTEM_UID, currUserId);
                        var libClientPackages = libClientPackagesPair.first;
                        var libClientOptional = libClientPackagesPair.second;
                        // We by default don't allow removing a package if the host lib is still be
                        // used by other client packages
                        boolean allowLibIndependence = false;
                        // Only when the sdkLibIndependence flag is enabled we will respect the
                        // "optional" attr in uses-sdk-library. Only allow to remove sdk-lib host
                        // package if no required clients depend on it
                        if ((pkg.getSdkLibraryName() != null)
                                && !ArrayUtils.isEmpty(libClientPackages)
                                && !ArrayUtils.isEmpty(libClientOptional)
                                && (libClientPackages.size() == libClientOptional.size())
                                && flagSdkLibIndependence) {
                            allowLibIndependence = true;
                            for (int i = 0; i < libClientPackages.size(); i++) {
                                boolean usesSdkLibOptional = libClientOptional.get(i);
                                if (!usesSdkLibOptional) {
                                    allowLibIndependence = false;
                                    break;
                                }
                            }
                        }
                        if (!ArrayUtils.isEmpty(libClientPackages) && !allowLibIndependence) {
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

        try (PackageManagerTracedLock installLock = mPm.mInstallLock.acquireLock()) {
            if (DEBUG_REMOVE) Slog.d(TAG, "deletePackageX: pkg=" + packageName + " user=" + userId);
            try (PackageFreezer freezer = mPm.freezePackageForDelete(packageName, freezeUser,
                    deleteFlags, "deletePackageX", ApplicationExitInfo.REASON_OTHER)) {
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
            final boolean isArchived = (deleteFlags & PackageManager.DELETE_ARCHIVE) != 0;
            mBroadcastHelper.sendPackageRemovedBroadcasts(info, mPm, killApp,
                    removedBySystem, isArchived);
            mBroadcastHelper.sendSystemPackageUpdatedBroadcasts(info);
            PackageMetrics.onUninstallSucceeded(info, deleteFlags, removeUser);
        }

        // Force a gc to clear up things.
        // Ask for a background one, it's fine to go on and not block here.
        VMRuntime.getRuntime().requestConcurrentGC();

        // Delete the resources here after sending the broadcast to let
        // other processes clean up before deleting resources.
        try (PackageManagerTracedLock installLock = mPm.mInstallLock.acquireLock()) {
            if (info.mArgs != null) {
                mRemovePackageHelper.cleanUpResources(info.mArgs.getPackageName(),
                        info.mArgs.getCodeFile(), info.mArgs.getInstructionSets());
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
                        mPm.enableCompressedPackage(stubPkg, stubPs);
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

        return res ? DELETE_SUCCEEDED : PackageManager.DELETE_FAILED_INTERNAL_ERROR;
    }

    /** Deletes dexopt artifacts for the given package*/
    private void deleteArtDexoptArtifacts(String packageName) {
        try (PackageManagerLocal.FilteredSnapshot filteredSnapshot =
                     PackageManagerServiceUtils.getPackageManagerLocal()
                             .withFilteredSnapshot()) {
            try {
                DexOptHelper.getArtManagerLocal().deleteDexoptArtifacts(
                        filteredSnapshot, packageName);
            } catch (IllegalArgumentException | IllegalStateException e) {
                Slog.w(TAG, e.toString());
            }
        }
    }

    /*
     * This method handles package deletion in general
     */
    @GuardedBy("mPm.mInstallLock")
    public boolean deletePackageLIF(@NonNull String packageName, UserHandle user,
            boolean deleteCodeAndResources, @NonNull int[] allUserHandles, int flags,
            @NonNull PackageRemovedInfo outInfo, boolean writeSettings) {
        final DeletePackageAction action;
        synchronized (mPm.mLock) {
            final PackageSetting ps = mPm.mSettings.getPackageLPr(packageName);
            if (ps == null) {
                if (DEBUG_REMOVE) {
                    Slog.d(TAG, "Attempted to remove non-existent package " + packageName);
                }
                return false;
            }
            final PackageSetting disabledPs = mPm.mSettings.getDisabledSystemPkgLPr(ps);
            if (PackageManagerServiceUtils.isSystemApp(ps)
                    && mPm.checkPermission(CONTROL_KEYGUARD, packageName, UserHandle.USER_SYSTEM)
                    == PERMISSION_GRANTED) {
                Slog.w(TAG, "Attempt to delete keyguard system package " + packageName);
                return false;
            }
            action = mayDeletePackageLocked(outInfo, ps, disabledPs, flags, user);
        }
        if (DEBUG_REMOVE) Slog.d(TAG, "deletePackageLI: " + packageName + " user " + user);
        if (null == action) {
            if (DEBUG_REMOVE) Slog.d(TAG, "deletePackageLI: action was null");
            return false;
        }

        try {
            executeDeletePackageLIF(action, packageName, deleteCodeAndResources,
                    allUserHandles, writeSettings, /* keepArtProfile= */ false);
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
    public static DeletePackageAction mayDeletePackageLocked(@NonNull PackageRemovedInfo outInfo,
            PackageSetting ps, @Nullable PackageSetting disabledPs,
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

    public void executeDeletePackage(DeletePackageAction action, String packageName,
            boolean deleteCodeAndResources, @NonNull int[] allUserHandles, boolean writeSettings,
            boolean keepArtProfile)  throws SystemDeleteException {
        try (PackageManagerTracedLock installLock = mPm.mInstallLock.acquireLock()) {
            executeDeletePackageLIF(action, packageName, deleteCodeAndResources, allUserHandles,
                    writeSettings, keepArtProfile);
        }
    }

    /** Deletes a package. Only throws when install of a disabled package fails. */
    @GuardedBy("mPm.mInstallLock")
    private void executeDeletePackageLIF(DeletePackageAction action,
            String packageName, boolean deleteCodeAndResources,
            @NonNull int[] allUserHandles, boolean writeSettings, boolean keepArtProfile)
            throws SystemDeleteException {
        final PackageSetting ps = action.mDeletingPs;
        final PackageRemovedInfo outInfo = action.mRemovedInfo;
        final UserHandle user = action.mUser;
        final int flags =
                keepArtProfile ? action.mFlags | Installer.FLAG_CLEAR_APP_DATA_KEEP_ART_PROFILES
                        : action.mFlags;
        final boolean systemApp = PackageManagerServiceUtils.isSystemApp(ps);

        // We need to get the permission state before package state is (potentially) destroyed.
        final SparseBooleanArray hadSuspendAppsPermission = new SparseBooleanArray();
        for (int userId : allUserHandles) {
            hadSuspendAppsPermission.put(userId, mPm.checkPermission(
                    Manifest.permission.SUSPEND_APPS, packageName, userId) == PERMISSION_GRANTED);
        }

        final int userId = user == null ? UserHandle.USER_ALL : user.getIdentifier();
        // Remember which users are affected, before the installed states are modified
        outInfo.mRemovedUsers = userId == UserHandle.USER_ALL
                ? ps.queryUsersInstalledOrHasData(allUserHandles)
                : new int[]{userId};
        outInfo.populateBroadcastUsers(ps);
        outInfo.mDataRemoved = (flags & PackageManager.DELETE_KEEP_DATA) == 0;
        outInfo.mRemovedPackage = ps.getPackageName();
        outInfo.mInstallerPackageName = ps.getInstallSource().mInstallerPackageName;
        outInfo.mIsStaticSharedLib =
                ps.getPkg() != null && ps.getPkg().getStaticSharedLibraryName() != null;
        outInfo.mIsExternal = ps.isExternalStorage();
        outInfo.mRemovedPackageVersionCode = ps.getVersionCode();

        if ((!systemApp || (flags & PackageManager.DELETE_SYSTEM_APP) != 0)
                && userId != UserHandle.USER_ALL) {
            // The caller is asking that the package only be deleted for a single
            // user.  To do this, we just mark its uninstalled state and delete
            // its data. If this is a system app, we only allow this to happen if
            // they have set the special DELETE_SYSTEM_APP which requests different
            // semantics than normal for uninstalling system apps.
            final boolean clearPackageStateAndReturn;
            synchronized (mPm.mLock) {
                markPackageUninstalledForUserLPw(ps, user, flags);
                if (!systemApp) {
                    // Do not uninstall the APK if an app should be cached
                    boolean keepUninstalledPackage =
                            mPm.shouldKeepUninstalledPackageLPr(packageName);
                    if (ps.isInstalledOnAnyOtherUser(
                            mUserManagerInternal.getUserIds(), userId) || keepUninstalledPackage) {
                        // Other users still have this package installed, so all
                        // we need to do is clear this user's data and save that
                        // it is uninstalled.
                        if (DEBUG_REMOVE) Slog.d(TAG, "Still installed by other users");
                        clearPackageStateAndReturn = true;
                    } else {
                        if (DEBUG_REMOVE) Slog.d(TAG, "Not installed by other users, full delete");
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
                mRemovePackageHelper.clearPackageStateForUserLIF(ps, userId, flags);
                // Legacy behavior to report appId as UID here.
                // The final broadcasts will contain a per-user UID.
                outInfo.mUid = ps.getAppId();
                // Only send Intent.ACTION_UID_REMOVED when flag & DELETE_KEEP_DATA is 0
                // i.e. the mDataRemoved is true
                if (outInfo.mDataRemoved) {
                    outInfo.mIsAppIdRemoved = true;
                }
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
            mPm.restoreDisabledSystemPackageLIF(action, allUserHandles, writeSettings);
        } else {
            if (DEBUG_REMOVE) Slog.d(TAG, "Removing non-system package: " + ps.getPackageName());
            if (ps.isIncremental()) {
                // Explicitly delete dexopt artifacts for incremental app because the
                // artifacts are not stored in the same directory as the APKs
                deleteArtDexoptArtifacts(packageName);
            }
            deleteInstalledPackageLIF(ps, userId, deleteCodeAndResources, flags, allUserHandles,
                    outInfo, writeSettings);
        }

        // If the package removed had SUSPEND_APPS, unset any restrictions that might have been in
        // place for all affected users.
        final Computer snapshot = mPm.snapshotComputer();
        for (final int affectedUserId : outInfo.mRemovedUsers) {
            if (hadSuspendAppsPermission.get(affectedUserId)) {
                mPm.unsuspendForSuspendingPackage(snapshot, packageName,
                        affectedUserId /*suspendingUserId*/, true /*inAllUsers*/);
                mPm.removeAllDistractingPackageRestrictions(snapshot, affectedUserId);
            }
        }

        // Take a note whether we deleted the package for all users
        synchronized (mPm.mLock) {
            outInfo.mRemovedForAllUsers = mPm.mPackages.get(ps.getPackageName()) == null;
        }
    }

    @GuardedBy("mPm.mInstallLock")
    private void deleteInstalledPackageLIF(PackageSetting ps, int userId,
            boolean deleteCodeAndResources, int flags, @NonNull int[] allUserHandles,
            @NonNull PackageRemovedInfo outInfo, boolean writeSettings) {
        synchronized (mPm.mLock) {
            // Since the package is being deleted in all users, report appId as the uid
            outInfo.mUid = ps.getAppId();
            outInfo.mBroadcastAllowList = mPm.mAppsFilter.getVisibilityAllowList(
                    mPm.snapshotComputer(), ps, allUserHandles,
                    mPm.mSettings.getPackagesLocked());
        }

        // Delete package data from internal structures and also remove data if flag is set
        mRemovePackageHelper.removePackageDataLIF(
                ps, userId, allUserHandles, outInfo, flags, writeSettings);

        // Delete application code and resources only for parent packages
        if (deleteCodeAndResources) {
            outInfo.mArgs = new CleanUpArgs(ps.getName(),
                    ps.getPathString(), getAppDexInstructionSets(
                            ps.getPrimaryCpuAbiLegacy(), ps.getSecondaryCpuAbiLegacy()));
            if (DEBUG_SD_INSTALL) Slog.i(TAG, "args=" + outInfo.mArgs);
        }
    }

    @GuardedBy("mPm.mLock")
    private void markPackageUninstalledForUserLPw(PackageSetting ps, UserHandle user, int flags) {
        final int[] userIds = (user == null || user.getIdentifier() == UserHandle.USER_ALL)
                ? mUserManagerInternal.getUserIds()
                : new int[] {user.getIdentifier()};
        for (int nextUserId : userIds) {
            if (DEBUG_REMOVE) {
                Slog.d(TAG, "Marking package:" + ps.getPackageName()
                        + " uninstalled for user:" + nextUserId);
            }

            // Keep enabled and disabled components in case of DELETE_KEEP_DATA
            ArraySet<String> enabledComponents = null;
            ArraySet<String> disabledComponents = null;
            if ((flags & PackageManager.DELETE_KEEP_DATA) != 0) {
                enabledComponents = new ArraySet<String>(
                        ps.readUserState(nextUserId).getEnabledComponents());
                disabledComponents = new ArraySet<String>(
                        ps.readUserState(nextUserId).getDisabledComponents());
            }

            // Preserve ArchiveState if this is not a full uninstall
            ArchiveState archiveState =
                    (flags & DELETE_KEEP_DATA) == 0
                            ? null
                            : ps.getUserStateOrDefault(nextUserId).getArchiveState();

            // Preserve firstInstallTime in case of DELETE_KEEP_DATA
            // For full uninstalls, reset firstInstallTime to 0 as if it has never been installed
            final long firstInstallTime = (flags & DELETE_KEEP_DATA) == 0
                    ? 0
                    : ps.getUserStateOrDefault(nextUserId).getFirstInstallTimeMillis();

            ps.setUserState(nextUserId,
                    ps.getCeDataInode(nextUserId),
                    ps.getDeDataInode(nextUserId),
                    COMPONENT_ENABLED_STATE_DEFAULT,
                    false /*installed*/,
                    true /*stopped*/,
                    true /*notLaunched*/,
                    false /*hidden*/,
                    0 /*distractionFlags*/,
                    null /*suspendParams*/,
                    false /*instantApp*/,
                    false /*virtualPreload*/,
                    null /*lastDisableAppCaller*/,
                    enabledComponents,
                    disabledComponents,
                    PackageManager.INSTALL_REASON_UNKNOWN,
                    PackageManager.UNINSTALL_REASON_UNKNOWN,
                    null /*harmfulAppWarning*/,
                    null /*splashScreenTheme*/,
                    firstInstallTime,
                    PackageManager.USER_MIN_ASPECT_RATIO_UNSET,
                    archiveState);
        }
        mPm.mSettings.writeKernelMappingLPr(ps);
    }

    private void deleteInstalledSystemPackage(DeletePackageAction action,
            @NonNull int[] allUserHandles, boolean writeSettings) {
        int flags = action.mFlags;
        final PackageSetting deletedPs = action.mDeletingPs;
        final PackageRemovedInfo outInfo = action.mRemovedInfo;
        final boolean applyUserRestrictions = outInfo.mOrigUsers != null;
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

        // Delete the updated package
        outInfo.mIsRemovedPackageSystemUpdate = true;

        if (disabledPs.getVersionCode() < deletedPs.getVersionCode()
                || disabledPs.getAppId() != deletedPs.getAppId()) {
            // Delete data for downgrades, or when the system app changed appId
            flags &= ~PackageManager.DELETE_KEEP_DATA;
        } else {
            // Preserve data by setting flag
            flags |= PackageManager.DELETE_KEEP_DATA;
        }
        try (PackageManagerTracedLock installLock = mPm.mInstallLock.acquireLock()) {
            deleteInstalledPackageLIF(deletedPs, UserHandle.USER_ALL, true, flags, allUserHandles,
                    outInfo, writeSettings);
        }
    }

    public void deletePackageVersionedInternal(VersionedPackage versionedPackage,
            final IPackageDeleteObserver2 observer, final int userId, final int deleteFlags,
            final boolean allowSilentUninstall) {
        deletePackageVersionedInternal(versionedPackage, observer, userId, deleteFlags,
                Binder.getCallingUid(), allowSilentUninstall);
    }

    public void deletePackageVersionedInternal(VersionedPackage versionedPackage,
            final IPackageDeleteObserver2 observer, final int userId, final int deleteFlags,
            final int callingUid, final boolean allowSilentUninstall) {
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

        if (!isOrphaned(snapshot, internalPackageName)
                && !allowSilentUninstall
                && !isCallerAllowedToSilentlyUninstall(
                        snapshot, callingUid, internalPackageName, userId)) {
            mPm.mHandler.post(() -> {
                try {
                    final Intent intent = new Intent(Intent.ACTION_UNINSTALL_PACKAGE);
                    intent.setData(Uri.fromParts(PACKAGE_SCHEME, packageName, null));
                    intent.putExtra(PackageInstaller.EXTRA_CALLBACK,
                            new PackageManager.UninstallCompleteCallback(observer.asBinder()));
                    if ((deleteFlags & PackageManager.DELETE_ARCHIVE) != 0) {
                        // Delete flags are passed to the uninstaller activity so it can be
                        // preserved in the follow-up uninstall operation after the user
                        // confirmation
                        intent.putExtra(PackageInstaller.EXTRA_DELETE_FLAGS, deleteFlags);
                    }
                    observer.onUserActionRequired(intent);
                } catch (RemoteException re) {
                }
            });
            return;
        }
        final boolean deleteAllUsers = (deleteFlags & PackageManager.DELETE_ALL_USERS) != 0;
        final int[] users = deleteAllUsers ? mUserManagerInternal.getUserIds() : new int[]{userId};
        if (UserHandle.getUserId(callingUid) != userId || (deleteAllUsers && users.length > 1)) {
            mPm.mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                    "deletePackage for user " + userId);
        }

        final long token = Binder.clearCallingIdentity();
        try {
            // If a package is device admin, or is data protected for any user, it should not be
            // uninstalled from that user, or from any users if DELETE_ALL_USERS flag is passed.
            for (int user : users) {
                if (mPm.isPackageDeviceAdmin(packageName, user)) {
                    mPm.mHandler.post(() -> {
                        try {
                            Slog.w(TAG, "Not removing package " + packageName
                                    + ": has active device admin");
                            observer.onPackageDeleted(packageName,
                                    PackageManager.DELETE_FAILED_DEVICE_POLICY_MANAGER, null);
                        } catch (RemoteException e) {
                            // no-op
                        }
                    });
                    return;
                }
                if (mPm.mProtectedPackages.isPackageDataProtected(user, packageName)) {
                    mPm.mHandler.post(() -> {
                        try {
                            Slog.w(TAG, "Attempted to delete protected package: " + packageName);
                            observer.onPackageDeleted(packageName,
                                    PackageManager.DELETE_FAILED_INTERNAL_ERROR, null);
                        } catch (RemoteException re) {
                            // no-op
                        }
                    });
                    return;
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
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

                    // Delete package in child only if successfully deleted in parent.
                    if (returnCode == DELETE_SUCCEEDED && packageState != null) {
                        // Get a list of child user profiles and delete if package is
                        // present in that profile.
                        int[] childUserIds = mUserManagerInternal.getProfileIds(userId, true);
                        int returnCodeOfChild;
                        for (int childId : childUserIds) {
                            if (childId == userId) continue;
                            if (mUserManagerInternal.getProfileParentId(childId) != userId) {
                                continue;
                            }

                            // If package is not present in child then don't attempt to delete.
                            if (!packageState.getUserStateOrDefault(childId).isInstalled()) {
                                continue;
                            }

                            UserProperties userProperties = mUserManagerInternal
                                    .getUserProperties(childId);
                            if (userProperties != null && userProperties.getDeleteAppWithParent()) {
                                returnCodeOfChild = deletePackageX(internalPackageName, versionCode,
                                        childId, deleteFlags, false /*removedBySystem*/);
                                if (returnCodeOfChild != DELETE_SUCCEEDED) {
                                    Slog.w(TAG, "Package delete failed for user " + childId
                                            + ", returnCode " + returnCodeOfChild);
                                    returnCode = PackageManager.DELETE_FAILED_FOR_CHILD_PROFILE;
                                }
                            }
                        }
                    }
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
                                if (returnCode != DELETE_SUCCEEDED) {
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

            // Prune unused static shared libraries which have been cached a period of time
            mPm.schedulePruneUnusedStaticSharedLibraries(true /* delay */);
        });
    }

    private boolean isOrphaned(@NonNull Computer snapshot, String packageName) {
        final PackageStateInternal packageState = snapshot.getPackageStateInternal(packageName);
        return packageState != null && packageState.getInstallSource().mIsOrphaned;
    }

    private boolean isCallerAllowedToSilentlyUninstall(@NonNull Computer snapshot, int callingUid,
            String pkgName, int userId) {
        if (PackageManagerServiceUtils.isRootOrShell(callingUid)
                || UserHandle.getAppId(callingUid) == Process.SYSTEM_UID) {
            return true;
        }
        final int callingUserId = UserHandle.getUserId(callingUid);
        // If the caller installed the pkgName, then allow it to silently uninstall.
        if (callingUid == snapshot.getPackageUid(
                snapshot.getInstallerPackageName(pkgName, userId), 0, callingUserId)) {
            return true;
        }

        // Allow package verifier to silently uninstall.
        for (String verifierPackageName : mPm.mRequiredVerifierPackages) {
            if (callingUid == snapshot.getPackageUid(verifierPackageName, 0, callingUserId)) {
                return true;
            }
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
                    || !TextUtils.isEmpty(ps.getPkg().getStaticSharedLibraryName())
                    || !TextUtils.isEmpty(ps.getPkg().getSdkLibraryName())) {
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
