/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.os.Process.SYSTEM_UID;

import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;
import static com.android.server.pm.PackageManagerService.TAG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.IActivityManager;
import android.content.Intent;
import android.content.pm.SuspendDialogInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IntArray;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.PackageUserStateInternal;
import com.android.server.pm.pkg.SuspendParams;
import com.android.server.pm.pkg.mutate.PackageUserStateWrite;
import com.android.server.utils.WatchedArrayMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

public final class SuspendPackageHelper {
    // TODO(b/198166813): remove PMS dependency
    private final PackageManagerService mPm;
    private final PackageManagerServiceInjector mInjector;

    private final BroadcastHelper mBroadcastHelper;
    private final ProtectedPackages mProtectedPackages;

    /**
     * Constructor for {@link PackageManagerService}.
     */
    SuspendPackageHelper(PackageManagerService pm, PackageManagerServiceInjector injector,
            BroadcastHelper broadcastHelper, ProtectedPackages protectedPackages) {
        mPm = pm;
        mInjector = injector;
        mBroadcastHelper = broadcastHelper;
        mProtectedPackages = protectedPackages;
    }

    /**
     * Updates the package to the suspended or unsuspended state.
     *
     * @param packageNames The names of the packages to set the suspended status.
     * @param suspended {@code true} to suspend packages, or {@code false} to unsuspend packages.
     * @param appExtras An optional {@link PersistableBundle} that the suspending app can provide
     *                  which will be shared with the apps being suspended. Ignored if
     *                  {@code suspended} is false.
     * @param launcherExtras An optional {@link PersistableBundle} that the suspending app can
     *                       provide which will be shared with the launcher. Ignored if
     *                       {@code suspended} is false.
     * @param dialogInfo An optional {@link SuspendDialogInfo} object describing the dialog that
     *                   should be shown to the user when they try to launch a suspended app.
     *                   Ignored if {@code suspended} is false.
     * @param callingPackage The caller's package name.
     * @param userId The user where packages reside.
     * @param callingUid The caller's uid.
     * @return The names of failed packages.
     */
    @Nullable
    String[] setPackagesSuspended(@NonNull Computer snapshot, @Nullable String[] packageNames,
            boolean suspended, @Nullable PersistableBundle appExtras,
            @Nullable PersistableBundle launcherExtras, @Nullable SuspendDialogInfo dialogInfo,
            @NonNull String callingPackage, @UserIdInt int userId, int callingUid) {
        if (ArrayUtils.isEmpty(packageNames)) {
            return packageNames;
        }
        if (suspended && !isSuspendAllowedForUser(snapshot, userId, callingUid)) {
            Slog.w(TAG, "Cannot suspend due to restrictions on user " + userId);
            return packageNames;
        }

        final SuspendParams newSuspendParams =
                new SuspendParams(dialogInfo, appExtras, launcherExtras);

        final List<String> changedPackagesList = new ArrayList<>(packageNames.length);
        final IntArray changedUids = new IntArray(packageNames.length);
        final IntArray modifiedUids = new IntArray(packageNames.length);
        final List<String> unmodifiablePackages = new ArrayList<>(packageNames.length);

        ArraySet<String> modifiedPackages = new ArraySet<>();

        final boolean[] canSuspend = suspended
                ? canSuspendPackageForUser(snapshot, packageNames, userId, callingUid) : null;
        for (int i = 0; i < packageNames.length; i++) {
            final String packageName = packageNames[i];
            if (callingPackage.equals(packageName)) {
                Slog.w(TAG, "Calling package: " + callingPackage + " trying to "
                        + (suspended ? "" : "un") + "suspend itself. Ignoring");
                unmodifiablePackages.add(packageName);
                continue;
            }
            final PackageStateInternal packageState =
                    snapshot.getPackageStateInternal(packageName);
            if (packageState == null
                    || snapshot.shouldFilterApplication(packageState, callingUid, userId)) {
                Slog.w(TAG, "Could not find package setting for package: " + packageName
                        + ". Skipping suspending/un-suspending.");
                unmodifiablePackages.add(packageName);
                continue;
            }
            if (canSuspend != null && !canSuspend[i]) {
                unmodifiablePackages.add(packageName);
                continue;
            }

            final WatchedArrayMap<String, SuspendParams> suspendParamsMap =
                    packageState.getUserStateOrDefault(userId).getSuspendParams();
            if (suspended) {
                if (suspendParamsMap != null && suspendParamsMap.containsKey(packageName)) {
                    final SuspendParams suspendParams = suspendParamsMap.get(packageName);
                    // Skip if there's no changes
                    if (suspendParams != null
                            && Objects.equals(suspendParams.getDialogInfo(), dialogInfo)
                            && Objects.equals(suspendParams.getAppExtras(), appExtras)
                            && Objects.equals(suspendParams.getLauncherExtras(),
                            launcherExtras)) {
                        // Carried over API behavior, must notify change even if no change
                        changedPackagesList.add(packageName);
                        changedUids.add(UserHandle.getUid(userId, packageState.getAppId()));
                        continue;
                    }
                }
            }

            // If size one, the package will be unsuspended from this call
            boolean packageUnsuspended =
                    !suspended && CollectionUtils.size(suspendParamsMap) <= 1;
            if (suspended || packageUnsuspended) {
                changedPackagesList.add(packageName);
                changedUids.add(UserHandle.getUid(userId, packageState.getAppId()));
            }

            modifiedPackages.add(packageName);
            modifiedUids.add(UserHandle.getUid(userId, packageState.getAppId()));
        }

        mPm.commitPackageStateMutation(null, mutator -> {
            final int size = modifiedPackages.size();
            for (int index = 0; index < size; index++) {
                final String packageName  = modifiedPackages.valueAt(index);
                final PackageUserStateWrite userState = mutator.forPackage(packageName)
                        .userState(userId);
                if (suspended) {
                    userState.putSuspendParams(callingPackage, newSuspendParams);
                } else {
                    userState.removeSuspension(callingPackage);
                }
            }
        });

        final Computer newSnapshot = mPm.snapshotComputer();

        if (!changedPackagesList.isEmpty()) {
            final String[] changedPackages = changedPackagesList.toArray(new String[0]);
            sendPackagesSuspendedForUser(newSnapshot,
                    suspended ? Intent.ACTION_PACKAGES_SUSPENDED
                            : Intent.ACTION_PACKAGES_UNSUSPENDED,
                    changedPackages, changedUids.toArray(), userId);
            sendMyPackageSuspendedOrUnsuspended(changedPackages, suspended, userId);
            mPm.scheduleWritePackageRestrictions(userId);
        }
        // Send the suspension changed broadcast to ensure suspension state is not stale.
        if (!modifiedPackages.isEmpty()) {
            sendPackagesSuspendedForUser(newSnapshot, Intent.ACTION_PACKAGES_SUSPENSION_CHANGED,
                    modifiedPackages.toArray(new String[0]), modifiedUids.toArray(), userId);
        }
        return unmodifiablePackages.toArray(new String[0]);
    }

    /**
     * Returns the names in the {@code packageNames} which can not be suspended by the caller.
     *
     * @param packageNames The names of packages to check.
     * @param userId The user where packages reside.
     * @param callingUid The caller's uid.
     * @return The names of packages which are Unsuspendable.
     */
    @NonNull
    String[] getUnsuspendablePackagesForUser(@NonNull Computer snapshot,
            @NonNull String[] packageNames, @UserIdInt int userId, int callingUid) {
        if (!isSuspendAllowedForUser(snapshot, userId, callingUid)) {
            Slog.w(TAG, "Cannot suspend due to restrictions on user " + userId);
            return packageNames;
        }
        final ArraySet<String> unactionablePackages = new ArraySet<>();
        final boolean[] canSuspend = canSuspendPackageForUser(snapshot, packageNames, userId,
                callingUid);
        for (int i = 0; i < packageNames.length; i++) {
            if (!canSuspend[i]) {
                unactionablePackages.add(packageNames[i]);
                continue;
            }
            final PackageStateInternal packageState =
                    snapshot.getPackageStateForInstalledAndFiltered(
                            packageNames[i], callingUid, userId);
            if (packageState == null) {
                Slog.w(TAG, "Could not find package setting for package: " + packageNames[i]);
                unactionablePackages.add(packageNames[i]);
            }
        }
        return unactionablePackages.toArray(new String[unactionablePackages.size()]);
    }

    /**
     * Returns the app extras of the given suspended package.
     *
     * @param packageName The suspended package name.
     * @param userId The user where the package resides.
     * @param callingUid The caller's uid.
     * @return The app extras of the suspended package.
     */
    @Nullable
    Bundle getSuspendedPackageAppExtras(@NonNull Computer snapshot, @NonNull String packageName,
            int userId, int callingUid) {
        final PackageStateInternal ps = snapshot.getPackageStateInternal(packageName, callingUid);
        if (ps == null) {
            return null;
        }
        final PackageUserStateInternal pus = ps.getUserStateOrDefault(userId);
        final Bundle allExtras = new Bundle();
        if (pus.isSuspended()) {
            for (int i = 0; i < pus.getSuspendParams().size(); i++) {
                final SuspendParams params = pus.getSuspendParams().valueAt(i);
                if (params != null && params.getAppExtras() != null) {
                    allExtras.putAll(params.getAppExtras());
                }
            }
        }
        return (allExtras.size() > 0) ? allExtras : null;
    }

    /**
     * Removes any suspensions on given packages that were added by packages that pass the given
     * predicate.
     *
     * <p> Caller must flush package restrictions if it cares about immediate data consistency.
     *
     * @param packagesToChange The packages on which the suspension are to be removed.
     * @param suspendingPackagePredicate A predicate identifying the suspending packages whose
     *                                   suspensions will be removed.
     * @param userId The user for which the changes are taking place.
     */
    void removeSuspensionsBySuspendingPackage(@NonNull Computer computer,
            @NonNull String[] packagesToChange,
            @NonNull Predicate<String> suspendingPackagePredicate, int userId) {
        final List<String> unsuspendedPackages = new ArrayList<>();
        final IntArray unsuspendedUids = new IntArray();
        final ArrayMap<String, ArraySet<String>> pkgToSuspendingPkgsToCommit = new ArrayMap<>();
        for (String packageName : packagesToChange) {
            final PackageStateInternal packageState =
                    computer.getPackageStateInternal(packageName);
            final PackageUserStateInternal packageUserState = packageState == null
                    ? null : packageState.getUserStateOrDefault(userId);
            if (packageUserState == null || !packageUserState.isSuspended()) {
                continue;
            }

            WatchedArrayMap<String, SuspendParams> suspendParamsMap =
                    packageUserState.getSuspendParams();
            int countRemoved = 0;
            for (int index = 0; index < suspendParamsMap.size(); index++) {
                String suspendingPackage = suspendParamsMap.keyAt(index);
                if (suspendingPackagePredicate.test(suspendingPackage)) {
                    ArraySet<String> suspendingPkgsToCommit =
                            pkgToSuspendingPkgsToCommit.get(packageName);
                    if (suspendingPkgsToCommit == null) {
                        suspendingPkgsToCommit = new ArraySet<>();
                        pkgToSuspendingPkgsToCommit.put(packageName, suspendingPkgsToCommit);
                    }
                    suspendingPkgsToCommit.add(suspendingPackage);
                    countRemoved++;
                }
            }

            // Everything would be removed and package unsuspended
            if (countRemoved == suspendParamsMap.size()) {
                unsuspendedPackages.add(packageState.getPackageName());
                unsuspendedUids.add(UserHandle.getUid(userId, packageState.getAppId()));
            }
        }

        mPm.commitPackageStateMutation(null, mutator -> {
            for (int mapIndex = 0; mapIndex < pkgToSuspendingPkgsToCommit.size(); mapIndex++) {
                String packageName = pkgToSuspendingPkgsToCommit.keyAt(mapIndex);
                ArraySet<String> packagesToRemove = pkgToSuspendingPkgsToCommit.valueAt(mapIndex);
                PackageUserStateWrite userState = mutator.forPackage(packageName).userState(userId);
                for (int setIndex = 0; setIndex < packagesToRemove.size(); setIndex++) {
                    userState.removeSuspension(packagesToRemove.valueAt(setIndex));
                }
            }
        });

        final Computer newSnapshot = mPm.snapshotComputer();

        mPm.scheduleWritePackageRestrictions(userId);
        if (!unsuspendedPackages.isEmpty()) {
            final String[] packageArray = unsuspendedPackages.toArray(
                    new String[unsuspendedPackages.size()]);
            sendMyPackageSuspendedOrUnsuspended(packageArray, false, userId);
            sendPackagesSuspendedForUser(newSnapshot, Intent.ACTION_PACKAGES_UNSUSPENDED,
                    packageArray, unsuspendedUids.toArray(), userId);
        }
    }

    /**
     * Returns the launcher extras for the given suspended package.
     *
     * @param packageName The name of the suspended package.
     * @param userId The user where the package resides.
     * @param callingUid The caller's uid.
     * @return The launcher extras.
     */
    @Nullable
    Bundle getSuspendedPackageLauncherExtras(@NonNull Computer snapshot,
            @NonNull String packageName, int userId, int callingUid) {
        final PackageStateInternal packageState =
                snapshot.getPackageStateInternal(packageName, callingUid);
        if (packageState == null) {
            return null;
        }
        Bundle allExtras = new Bundle();
        PackageUserStateInternal userState = packageState.getUserStateOrDefault(userId);
        if (userState.isSuspended()) {
            for (int i = 0; i < userState.getSuspendParams().size(); i++) {
                final SuspendParams params = userState.getSuspendParams().valueAt(i);
                if (params != null && params.getLauncherExtras() != null) {
                    allExtras.putAll(params.getLauncherExtras());
                }
            }
        }
        return (allExtras.size() > 0) ? allExtras : null;
    }

    /**
     * Return {@code true}, if the given package is suspended.
     *
     * @param packageName The name of package to check.
     * @param userId The user where the package resides.
     * @param callingUid The caller's uid.
     * @return {@code true}, if the given package is suspended.
     */
    boolean isPackageSuspended(@NonNull Computer snapshot, @NonNull String packageName, int userId,
            int callingUid) {
        final PackageStateInternal packageState =
                snapshot.getPackageStateInternal(packageName, callingUid);
        return packageState != null && packageState.getUserStateOrDefault(userId)
                .isSuspended();
    }

    /**
     * Given a suspended package, returns the name of package which invokes suspending to it.
     *
     * @param suspendedPackage The suspended package to check.
     * @param userId The user where the package resides.
     * @param callingUid The caller's uid.
     * @return The name of suspending package.
     */
    @Nullable
    String getSuspendingPackage(@NonNull Computer snapshot, @NonNull String suspendedPackage,
            int userId, int callingUid) {
        final PackageStateInternal packageState = snapshot.getPackageStateInternal(
                suspendedPackage, callingUid);
        if (packageState == null) {
            return  null;
        }

        final PackageUserStateInternal userState = packageState.getUserStateOrDefault(userId);
        if (!userState.isSuspended()) {
            return null;
        }

        String suspendingPackage = null;
        for (int i = 0; i < userState.getSuspendParams().size(); i++) {
            suspendingPackage = userState.getSuspendParams().keyAt(i);
            if (PLATFORM_PACKAGE_NAME.equals(suspendingPackage)) {
                return suspendingPackage;
            }
        }
        return suspendingPackage;
    }

    /**
     *  Returns the dialog info of the given suspended package.
     *
     * @param suspendedPackage The name of the suspended package.
     * @param suspendingPackage The name of the suspending package.
     * @param userId The user where the package resides.
     * @param callingUid The caller's uid.
     * @return The dialog info.
     */
    @Nullable
    SuspendDialogInfo getSuspendedDialogInfo(@NonNull Computer snapshot,
            @NonNull String suspendedPackage, @NonNull String suspendingPackage, int userId,
            int callingUid) {
        final PackageStateInternal packageState = snapshot.getPackageStateInternal(
                suspendedPackage, callingUid);
        if (packageState == null) {
            return  null;
        }

        final PackageUserStateInternal userState = packageState.getUserStateOrDefault(userId);
        if (!userState.isSuspended()) {
            return null;
        }

        final WatchedArrayMap<String, SuspendParams> suspendParamsMap =
                userState.getSuspendParams();
        if (suspendParamsMap == null) {
            return null;
        }

        final SuspendParams suspendParams = suspendParamsMap.get(suspendingPackage);
        return (suspendParams != null) ? suspendParams.getDialogInfo() : null;
    }

    /**
     * Return {@code true} if the user is allowed to suspend packages by the caller.
     *
     * @param userId The user id to check.
     * @param callingUid The caller's uid.
     * @return {@code true} if the user is allowed to suspend packages by the caller.
     */
    boolean isSuspendAllowedForUser(@NonNull Computer snapshot, int userId, int callingUid) {
        final UserManagerService userManager = mInjector.getUserManagerService();
        return isCallerDeviceOrProfileOwner(snapshot, userId, callingUid)
                || (!userManager.hasUserRestriction(UserManager.DISALLOW_APPS_CONTROL, userId)
                && !userManager.hasUserRestriction(UserManager.DISALLOW_UNINSTALL_APPS, userId));
    }

    /**
     * Returns an array of booleans, such that the ith boolean denotes whether the ith package can
     * be suspended or not.
     *
     * @param packageNames  The package names to check suspendability for.
     * @param userId The user to check in
     * @param callingUid The caller's uid.
     * @return An array containing results of the checks
     */
    @NonNull
    boolean[] canSuspendPackageForUser(@NonNull Computer snapshot, @NonNull String[] packageNames,
            int userId, int callingUid) {
        final boolean[] canSuspend = new boolean[packageNames.length];
        final boolean isCallerOwner = isCallerDeviceOrProfileOwner(snapshot, userId, callingUid);
        final long token = Binder.clearCallingIdentity();
        try {
            final DefaultAppProvider defaultAppProvider = mInjector.getDefaultAppProvider();
            final String activeLauncherPackageName = defaultAppProvider.getDefaultHome(userId);
            final String dialerPackageName = defaultAppProvider.getDefaultDialer(userId);
            final String requiredInstallerPackage =
                    getKnownPackageName(snapshot, KnownPackages.PACKAGE_INSTALLER, userId);
            final String requiredUninstallerPackage =
                    getKnownPackageName(snapshot, KnownPackages.PACKAGE_UNINSTALLER, userId);
            final String requiredVerifierPackage =
                    getKnownPackageName(snapshot, KnownPackages.PACKAGE_VERIFIER, userId);
            final String requiredPermissionControllerPackage =
                    getKnownPackageName(snapshot, KnownPackages.PACKAGE_PERMISSION_CONTROLLER,
                            userId);
            for (int i = 0; i < packageNames.length; i++) {
                canSuspend[i] = false;
                final String packageName = packageNames[i];

                if (mPm.isPackageDeviceAdmin(packageName, userId)) {
                    Slog.w(TAG, "Cannot suspend package \"" + packageName
                            + "\": has an active device admin");
                    continue;
                }
                if (packageName.equals(activeLauncherPackageName)) {
                    Slog.w(TAG, "Cannot suspend package \"" + packageName
                            + "\": contains the active launcher");
                    continue;
                }
                if (packageName.equals(requiredInstallerPackage)) {
                    Slog.w(TAG, "Cannot suspend package \"" + packageName
                            + "\": required for package installation");
                    continue;
                }
                if (packageName.equals(requiredUninstallerPackage)) {
                    Slog.w(TAG, "Cannot suspend package \"" + packageName
                            + "\": required for package uninstallation");
                    continue;
                }
                if (packageName.equals(requiredVerifierPackage)) {
                    Slog.w(TAG, "Cannot suspend package \"" + packageName
                            + "\": required for package verification");
                    continue;
                }
                if (packageName.equals(dialerPackageName)) {
                    Slog.w(TAG, "Cannot suspend package \"" + packageName
                            + "\": is the default dialer");
                    continue;
                }
                if (packageName.equals(requiredPermissionControllerPackage)) {
                    Slog.w(TAG, "Cannot suspend package \"" + packageName
                            + "\": required for permissions management");
                    continue;
                }
                if (mProtectedPackages.isPackageStateProtected(userId, packageName)) {
                    Slog.w(TAG, "Cannot suspend package \"" + packageName
                            + "\": protected package");
                    continue;
                }
                if (!isCallerOwner && snapshot.getBlockUninstall(userId, packageName)) {
                    Slog.w(TAG, "Cannot suspend package \"" + packageName
                            + "\": blocked by admin");
                    continue;
                }

                // Cannot suspend static shared libs as they are considered
                // a part of the using app (emulating static linking). Also
                // static libs are installed always on internal storage.
                PackageStateInternal packageState = snapshot.getPackageStateInternal(packageName);
                AndroidPackage pkg = packageState == null ? null : packageState.getPkg();
                if (pkg != null) {
                    // Cannot suspend SDK libs as they are controlled by SDK manager.
                    if (pkg.isSdkLibrary()) {
                        Slog.w(TAG, "Cannot suspend package: " + packageName
                                + " providing SDK library: "
                                + pkg.getSdkLibName());
                        continue;
                    }
                    // Cannot suspend static shared libs as they are considered
                    // a part of the using app (emulating static linking). Also
                    // static libs are installed always on internal storage.
                    if (pkg.isStaticSharedLibrary()) {
                        Slog.w(TAG, "Cannot suspend package: " + packageName
                                + " providing static shared library: "
                                + pkg.getStaticSharedLibName());
                        continue;
                    }
                }
                if (PLATFORM_PACKAGE_NAME.equals(packageName)) {
                    Slog.w(TAG, "Cannot suspend the platform package: " + packageName);
                    continue;
                }
                canSuspend[i] = true;
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return canSuspend;
    }

    /**
     * Send broadcast intents for packages suspension changes.
     *
     * @param intent The action name of the suspension intent.
     * @param pkgList The names of packages which have suspension changes.
     * @param uidList The uids of packages which have suspension changes.
     * @param userId The user where packages reside.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    void sendPackagesSuspendedForUser(@NonNull Computer snapshot, @NonNull String intent,
            @NonNull String[] pkgList, @NonNull int[] uidList, int userId) {
        final List<BroadcastParams> lists = mBroadcastHelper.getBroadcastParams(
                snapshot, pkgList, uidList, userId);
        final Handler handler = mInjector.getHandler();
        for (int i = 0; i < lists.size(); i++) {
            final Bundle extras = new Bundle(3);
            final BroadcastParams list = lists.get(i);
            extras.putStringArray(Intent.EXTRA_CHANGED_PACKAGE_LIST, list.getPackageNames());
            extras.putIntArray(Intent.EXTRA_CHANGED_UID_LIST, list.getUids());
            final SparseArray<int[]> allowList = list.getAllowList().size() == 0
                    ? null : list.getAllowList();
            handler.post(() -> mBroadcastHelper.sendPackageBroadcast(intent, null /* pkg */,
                    extras, Intent.FLAG_RECEIVER_REGISTERED_ONLY, null /* targetPkg */,
                    null /* finishedReceiver */, new int[]{userId}, null /* instantUserIds */,
                    allowList, null /* bOptions */));
        }
    }

    private String getKnownPackageName(@NonNull Computer snapshot,
            @KnownPackages.KnownPackage int knownPackage, int userId) {
        final String[] knownPackages =
                mPm.getKnownPackageNamesInternal(snapshot, knownPackage, userId);
        return knownPackages.length > 0 ? knownPackages[0] : null;
    }

    private boolean isCallerDeviceOrProfileOwner(@NonNull Computer snapshot, int userId,
            int callingUid) {
        if (callingUid == SYSTEM_UID) {
            return true;
        }
        final String ownerPackage = mProtectedPackages.getDeviceOwnerOrProfileOwnerPackage(userId);
        if (ownerPackage != null) {
            return callingUid == snapshot.getPackageUidInternal(ownerPackage, 0, userId,
                    callingUid);
        }
        return false;
    }

    private void sendMyPackageSuspendedOrUnsuspended(String[] affectedPackages, boolean suspended,
            int userId) {
        final Handler handler = mInjector.getHandler();
        final String action = suspended
                ? Intent.ACTION_MY_PACKAGE_SUSPENDED
                : Intent.ACTION_MY_PACKAGE_UNSUSPENDED;
        handler.post(() -> {
            final IActivityManager am = ActivityManager.getService();
            if (am == null) {
                Slog.wtf(TAG, "IActivityManager null. Cannot send MY_PACKAGE_ "
                        + (suspended ? "" : "UN") + "SUSPENDED broadcasts");
                return;
            }
            final int[] targetUserIds = new int[] {userId};
            final Computer snapshot = mPm.snapshotComputer();
            for (String packageName : affectedPackages) {
                final Bundle appExtras = suspended
                        ? getSuspendedPackageAppExtras(snapshot, packageName, userId, SYSTEM_UID)
                        : null;
                final Bundle intentExtras;
                if (appExtras != null) {
                    intentExtras = new Bundle(1);
                    intentExtras.putBundle(Intent.EXTRA_SUSPENDED_PACKAGE_EXTRAS, appExtras);
                } else {
                    intentExtras = null;
                }
                handler.post(() -> mBroadcastHelper.doSendBroadcast(action, null, intentExtras,
                        Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND, packageName, null,
                        targetUserIds, false, null, null));
            }
        });
    }
}
