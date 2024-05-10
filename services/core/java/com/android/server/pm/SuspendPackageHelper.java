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
import android.app.AppOpsManager;
import android.content.Intent;
import android.content.pm.SuspendDialogInfo;
import android.content.pm.UserPackage;
import android.os.Binder;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IntArray;
import android.util.Slog;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;
import com.android.server.pm.pkg.AndroidPackage;
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

    private static final String SYSTEM_EXEMPT_FROM_SUSPENSION = "system_exempt_from_suspension";

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
     * @param suspendingPackage The caller's package name.
     * @param targetUserId The user where packages reside.
     * @param callingUid The caller's uid.
     * @return The names of failed packages.
     */
    @Nullable
    String[] setPackagesSuspended(@NonNull Computer snapshot, @Nullable String[] packageNames,
            boolean suspended, @Nullable PersistableBundle appExtras,
            @Nullable PersistableBundle launcherExtras, @Nullable SuspendDialogInfo dialogInfo,
            @NonNull UserPackage suspendingPackage, @UserIdInt int targetUserId, int callingUid,
            boolean quarantined) {
        if (ArrayUtils.isEmpty(packageNames)) {
            return packageNames;
        }
        if (suspended && !quarantined
                && !isSuspendAllowedForUser(snapshot, targetUserId, callingUid)) {
            Slog.w(TAG, "Cannot suspend due to restrictions on user " + targetUserId);
            return packageNames;
        }

        final SuspendParams newSuspendParams = suspended
                ? new SuspendParams(dialogInfo, appExtras, launcherExtras, quarantined) : null;

        final List<String> unmodifiablePackages = new ArrayList<>(packageNames.length);

        final List<String> notifyPackagesList = new ArrayList<>(packageNames.length);
        final IntArray notifyUids = new IntArray(packageNames.length);
        final ArraySet<String> changedPackagesList = new ArraySet<>(packageNames.length);
        final IntArray changedUids = new IntArray(packageNames.length);

        final boolean[] canSuspend = suspended
                ? canSuspendPackageForUser(snapshot, packageNames, targetUserId, callingUid)
                : null;
        for (int i = 0; i < packageNames.length; i++) {
            final String packageName = packageNames[i];
            if (suspendingPackage.packageName.equals(packageName)
                    && suspendingPackage.userId == targetUserId) {
                Slog.w(TAG, "Suspending package: " + suspendingPackage + " trying to "
                        + (suspended ? "" : "un") + "suspend itself. Ignoring");
                unmodifiablePackages.add(packageName);
                continue;
            }
            final PackageStateInternal packageState = snapshot.getPackageStateInternal(packageName);
            if (packageState == null
                    || !packageState.getUserStateOrDefault(targetUserId).isInstalled()
                    || snapshot.shouldFilterApplication(packageState, callingUid, targetUserId)) {
                Slog.w(TAG, "Could not find package setting for package: " + packageName
                        + ". Skipping suspending/un-suspending.");
                unmodifiablePackages.add(packageName);
                continue;
            }
            if (canSuspend != null && !canSuspend[i]) {
                unmodifiablePackages.add(packageName);
                continue;
            }

            final WatchedArrayMap<UserPackage, SuspendParams> suspendParamsMap =
                    packageState.getUserStateOrDefault(targetUserId).getSuspendParams();
            final SuspendParams oldSuspendParams = suspendParamsMap == null
                    ? null : suspendParamsMap.get(suspendingPackage);
            boolean changed = !Objects.equals(oldSuspendParams, newSuspendParams);

            if (suspended && !changed) {
                // Carried over API behavior, must notify change even if no change
                notifyPackagesList.add(packageName);
                notifyUids.add(
                        UserHandle.getUid(targetUserId, packageState.getAppId()));
                continue;
            }

            // If only the suspendingPackage is suspending this package,
            // it will be unsuspended when this change is committed
            boolean packageUnsuspended = !suspended
                    && CollectionUtils.size(suspendParamsMap) == 1
                    && suspendParamsMap.containsKey(suspendingPackage);
            if (suspended || packageUnsuspended) {
                // Always notify of a suspend call + notify when fully unsuspended
                notifyPackagesList.add(packageName);
                notifyUids.add(UserHandle.getUid(targetUserId, packageState.getAppId()));
            }

            if (changed) {
                changedPackagesList.add(packageName);
                changedUids.add(UserHandle.getUid(targetUserId, packageState.getAppId()));
            } else {
                Slog.w(TAG, "No change is needed for package: " + packageName
                        + ". Skipping suspending/un-suspending.");
            }
        }

        mPm.commitPackageStateMutation(null, mutator -> {
            final int size = changedPackagesList.size();
            for (int index = 0; index < size; index++) {
                final String packageName  = changedPackagesList.valueAt(index);
                final PackageUserStateWrite userState = mutator.forPackage(packageName)
                        .userState(targetUserId);
                if (suspended) {
                    userState.putSuspendParams(suspendingPackage, newSuspendParams);
                } else {
                    userState.removeSuspension(suspendingPackage);
                }
            }
        });

        final Computer newSnapshot = mPm.snapshotComputer();
        if (!notifyPackagesList.isEmpty()) {
            final String[] changedPackages =
                    notifyPackagesList.toArray(new String[0]);
            mBroadcastHelper.sendPackagesSuspendedOrUnsuspendedForUser(newSnapshot,
                    suspended ? Intent.ACTION_PACKAGES_SUSPENDED
                            : Intent.ACTION_PACKAGES_UNSUSPENDED,
                    changedPackages, notifyUids.toArray(), quarantined, targetUserId);
            mBroadcastHelper.sendMyPackageSuspendedOrUnsuspended(newSnapshot, changedPackages,
                    suspended, targetUserId);
            mPm.scheduleWritePackageRestrictions(targetUserId);
        }
        // Send the suspension changed broadcast to ensure suspension state is not stale.
        if (!changedPackagesList.isEmpty()) {
            mBroadcastHelper.sendPackagesSuspendedOrUnsuspendedForUser(newSnapshot,
                    Intent.ACTION_PACKAGES_SUSPENSION_CHANGED,
                    changedPackagesList.toArray(new String[0]), changedUids.toArray(), quarantined,
                    targetUserId);
        }
        return unmodifiablePackages.toArray(new String[0]);
    }

    /**
     * Returns the names in the {@code packageNames} which can not be suspended by the caller.
     *
     * @param packageNames The names of packages to check.
     * @param targetUserId The user where packages reside.
     * @param callingUid The caller's uid.
     * @return The names of packages which are Unsuspendable.
     */
    @NonNull
    String[] getUnsuspendablePackagesForUser(@NonNull Computer snapshot,
            @NonNull String[] packageNames, @UserIdInt int targetUserId, int callingUid) {
        if (!isSuspendAllowedForUser(snapshot, targetUserId, callingUid)) {
            Slog.w(TAG, "Cannot suspend due to restrictions on user " + targetUserId);
            return packageNames;
        }
        final ArraySet<String> unactionablePackages = new ArraySet<>();
        final boolean[] canSuspend = canSuspendPackageForUser(snapshot, packageNames, targetUserId,
                callingUid);
        for (int i = 0; i < packageNames.length; i++) {
            if (!canSuspend[i]) {
                unactionablePackages.add(packageNames[i]);
                continue;
            }
            final PackageStateInternal packageState =
                    snapshot.getPackageStateForInstalledAndFiltered(
                            packageNames[i], callingUid, targetUserId);
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
    static Bundle getSuspendedPackageAppExtras(@NonNull Computer snapshot,
                                               @NonNull String packageName,
                                               int userId,
                                               int callingUid) {
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
     * @param targetUserId The user for which the changes are taking place.
     */
    void removeSuspensionsBySuspendingPackage(@NonNull Computer snapshot,
            @NonNull String[] packagesToChange,
            @NonNull Predicate<UserPackage> suspendingPackagePredicate, int targetUserId) {
        final List<String> unsuspendedPackages = new ArrayList<>();
        final IntArray unsuspendedUids = new IntArray();
        final ArrayMap<String, ArraySet<UserPackage>> pkgToSuspendingPkgsToCommit =
                new ArrayMap<>();
        for (String packageName : packagesToChange) {
            final PackageStateInternal packageState =
                    snapshot.getPackageStateInternal(packageName);
            final PackageUserStateInternal packageUserState = packageState == null
                    ? null : packageState.getUserStateOrDefault(targetUserId);
            if (packageUserState == null || !packageUserState.isSuspended()) {
                continue;
            }

            WatchedArrayMap<UserPackage, SuspendParams> suspendParamsMap =
                    packageUserState.getSuspendParams();
            int countRemoved = 0;
            for (int index = 0; index < suspendParamsMap.size(); index++) {
                UserPackage suspendingPackage = suspendParamsMap.keyAt(index);
                if (suspendingPackagePredicate.test(suspendingPackage)) {
                    ArraySet<UserPackage> suspendingPkgsToCommit =
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
                unsuspendedUids.add(UserHandle.getUid(targetUserId, packageState.getAppId()));
            }
        }

        mPm.commitPackageStateMutation(null, mutator -> {
            for (int mapIndex = 0; mapIndex < pkgToSuspendingPkgsToCommit.size(); mapIndex++) {
                String packageName = pkgToSuspendingPkgsToCommit.keyAt(mapIndex);
                ArraySet<UserPackage> packagesToRemove =
                        pkgToSuspendingPkgsToCommit.valueAt(mapIndex);
                PackageUserStateWrite userState =
                        mutator.forPackage(packageName).userState(targetUserId);
                for (int setIndex = 0; setIndex < packagesToRemove.size(); setIndex++) {
                    userState.removeSuspension(packagesToRemove.valueAt(setIndex));
                }
            }
        });

        mPm.scheduleWritePackageRestrictions(targetUserId);
        final Computer newSnapshot = mPm.snapshotComputer();
        if (!unsuspendedPackages.isEmpty()) {
            final String[] packageArray = unsuspendedPackages.toArray(
                    new String[unsuspendedPackages.size()]);
            mBroadcastHelper.sendMyPackageSuspendedOrUnsuspended(newSnapshot, packageArray,
                    false, targetUserId);
            mBroadcastHelper.sendPackagesSuspendedOrUnsuspendedForUser(newSnapshot,
                    Intent.ACTION_PACKAGES_UNSUSPENDED,
                    packageArray, unsuspendedUids.toArray(), false, targetUserId);
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
    UserPackage getSuspendingPackage(@NonNull Computer snapshot, @NonNull String suspendedPackage,
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

        UserPackage suspendingPackage = null;
        UserPackage suspendedBySystem = null;
        UserPackage qasPackage = null;
        for (int i = 0; i < userState.getSuspendParams().size(); i++) {
            suspendingPackage = userState.getSuspendParams().keyAt(i);
            var suspendParams = userState.getSuspendParams().valueAt(i);
            if (PLATFORM_PACKAGE_NAME.equals(suspendingPackage.packageName)) {
                suspendedBySystem = suspendingPackage;
            }
            if (suspendParams.isQuarantined() && qasPackage == null) {
                qasPackage = suspendingPackage;
            }
        }
        // Precedence: quarantined, then system, then suspending.
        if (qasPackage != null) {
            return qasPackage;
        }
        if (suspendedBySystem != null) {
            return suspendedBySystem;
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
            @NonNull String suspendedPackage, @NonNull UserPackage suspendingPackage, int userId,
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

        final WatchedArrayMap<UserPackage, SuspendParams> suspendParamsMap =
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
     * @param targetUserId The user to check in
     * @param callingUid The caller's uid.
     * @return An array containing results of the checks
     */
    @NonNull
    boolean[] canSuspendPackageForUser(@NonNull Computer snapshot, @NonNull String[] packageNames,
            int targetUserId, int callingUid) {
        final boolean[] canSuspend = new boolean[packageNames.length];
        final boolean isCallerOwner =
                isCallerDeviceOrProfileOwner(snapshot, targetUserId, callingUid);
        final long token = Binder.clearCallingIdentity();
        try {
            final DefaultAppProvider defaultAppProvider = mInjector.getDefaultAppProvider();
            final String activeLauncherPackageName =
                    defaultAppProvider.getDefaultHome(targetUserId);
            final String dialerPackageName = defaultAppProvider.getDefaultDialer(targetUserId);
            final String requiredInstallerPackage =
                    getKnownPackageName(snapshot, KnownPackages.PACKAGE_INSTALLER, targetUserId);
            final String requiredUninstallerPackage =
                    getKnownPackageName(snapshot, KnownPackages.PACKAGE_UNINSTALLER, targetUserId);
            final String requiredVerifierPackage =
                    getKnownPackageName(snapshot, KnownPackages.PACKAGE_VERIFIER, targetUserId);
            final String requiredPermissionControllerPackage =
                    getKnownPackageName(snapshot, KnownPackages.PACKAGE_PERMISSION_CONTROLLER,
                            targetUserId);
            for (int i = 0; i < packageNames.length; i++) {
                canSuspend[i] = false;
                final String packageName = packageNames[i];

                if (mPm.isPackageDeviceAdmin(packageName, targetUserId)) {
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
                if (mProtectedPackages.isPackageStateProtected(targetUserId, packageName)) {
                    Slog.w(TAG, "Cannot suspend package \"" + packageName
                            + "\": protected package");
                    continue;
                }
                if (!isCallerOwner && snapshot.getBlockUninstall(targetUserId, packageName)) {
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
                    final int uid = UserHandle.getUid(targetUserId, packageState.getAppId());
                    // Cannot suspend SDK libs as they are controlled by SDK manager.
                    if (pkg.isSdkLibrary()) {
                        Slog.w(TAG, "Cannot suspend package: " + packageName
                                + " providing SDK library: "
                                + pkg.getSdkLibraryName());
                        continue;
                    }
                    // Cannot suspend static shared libs as they are considered
                    // a part of the using app (emulating static linking). Also
                    // static libs are installed always on internal storage.
                    if (pkg.isStaticSharedLibrary()) {
                        Slog.w(TAG, "Cannot suspend package: " + packageName
                                + " providing static shared library: "
                                + pkg.getStaticSharedLibraryName());
                        continue;
                    }
                    if (exemptFromSuspensionByAppOp(uid, packageName)) {
                        Slog.w(TAG, "Cannot suspend package \"" + packageName
                                + "\": has OP_SYSTEM_EXEMPT_FROM_SUSPENSION set");
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

    private boolean exemptFromSuspensionByAppOp(int uid, String packageName) {
        final AppOpsManager appOpsManager = mInjector.getSystemService(AppOpsManager.class);
        return appOpsManager.checkOpNoThrow(
                AppOpsManager.OP_SYSTEM_EXEMPT_FROM_SUSPENSION, uid, packageName)
                        == AppOpsManager.MODE_ALLOWED;
    }

    private String getKnownPackageName(@NonNull Computer snapshot,
            @KnownPackages.KnownPackage int knownPackage, int userId) {
        final String[] knownPackages =
                mPm.getKnownPackageNamesInternal(snapshot, knownPackage, userId);
        return knownPackages.length > 0 ? knownPackages[0] : null;
    }

    private boolean isCallerDeviceOrProfileOwner(@NonNull Computer snapshot, int targetUserId,
            int callingUid) {
        if (callingUid == SYSTEM_UID) {
            return true;
        }
        final String ownerPackage =
                mProtectedPackages.getDeviceOwnerOrProfileOwnerPackage(targetUserId);
        if (ownerPackage != null) {
            return callingUid == snapshot.getPackageUidInternal(ownerPackage, 0, targetUserId,
                    callingUid);
        }
        return false;
    }
}
