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

import static android.app.admin.flags.Flags.crossUserSuspensionEnabledRo;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.RESTRICTION_NONE;

import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.AuxiliaryResolveInfo;
import android.content.pm.Checksum;
import android.content.pm.IOnChecksumsReadyListener;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ProcessInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.SuspendDialogInfo;
import android.content.pm.UserPackage;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Process;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;

import com.android.internal.pm.pkg.component.ParsedMainComponent;
import com.android.server.pm.dex.DexManager;
import com.android.server.pm.permission.PermissionManagerServiceInternal;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.PackageStateUtils;
import com.android.server.pm.pkg.SharedUserApi;
import com.android.server.pm.pkg.mutate.PackageStateMutator;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Internal manager variant of {@link IPackageManagerBase}. See that class for info.
 * {@link PackageManagerInternal} should eventually passing in a snapshot instance, deprecating
 * this class, but that requires much larger refactor.
 */
abstract class PackageManagerInternalBase extends PackageManagerInternal {

    @NonNull
    private final PackageManagerService mService;

    public PackageManagerInternalBase(@NonNull PackageManagerService service) {
        mService = service;
    }

    @NonNull protected abstract Context getContext();
    @NonNull protected abstract PermissionManagerServiceInternal getPermissionManager();
    @NonNull protected abstract AppDataHelper getAppDataHelper();
    @NonNull protected abstract PackageObserverHelper getPackageObserverHelper();
    @NonNull protected abstract ResolveIntentHelper getResolveIntentHelper();
    @NonNull protected abstract SuspendPackageHelper getSuspendPackageHelper();
    @NonNull protected abstract DistractingPackageHelper getDistractingPackageHelper();
    @NonNull protected abstract ProtectedPackages getProtectedPackages();
    @NonNull protected abstract UserNeedsBadgingCache getUserNeedsBadging();
    @NonNull protected abstract InstantAppRegistry getInstantAppRegistry();
    @NonNull protected abstract ApexManager getApexManager();
    @NonNull protected abstract DexManager getDexManager();

    @Override
    public final Computer snapshot() {
        return mService.snapshotComputer();
    }

    @Override
    @Deprecated
    public final List<ApplicationInfo> getInstalledApplications(
            @PackageManager.ApplicationInfoFlagsBits long flags, int userId, int callingUid) {
        return snapshot().getInstalledApplications(flags, userId, callingUid,
                /* forceAllowCrossUser= */ false);
    }

    @Override
    @Deprecated
    public final List<ApplicationInfo> getInstalledApplicationsCrossUser(
            @PackageManager.ApplicationInfoFlagsBits long flags, int userId, int callingUid) {
        return snapshot().getInstalledApplications(flags, userId, callingUid,
                /* forceAllowCrossUser= */ true);
    }

    @Override
    @Deprecated
    public final boolean isInstantApp(String packageName, int userId) {
        return snapshot().isInstantApp(packageName, userId);
    }

    @Override
    @Deprecated
    public final String getInstantAppPackageName(int uid) {
        return snapshot().getInstantAppPackageName(uid);
    }

    @Override
    @Deprecated
    public final boolean filterAppAccess(AndroidPackage pkg, int callingUid, int userId) {
        return snapshot().filterAppAccess(pkg, callingUid, userId);
    }

    @Override
    @Deprecated
    public final boolean filterAppAccess(String packageName, int callingUid, int userId,
            boolean filterUninstalled) {
        return snapshot().filterAppAccess(packageName, callingUid, userId, filterUninstalled);
    }

    @Override
    @Deprecated
    public final boolean filterAppAccess(int uid, int callingUid) {
        return snapshot().filterAppAccess(uid, callingUid);
    }

    @Nullable
    @Override
    @Deprecated
    public final int[] getVisibilityAllowList(@NonNull String packageName, int userId) {
        return snapshot().getVisibilityAllowList(packageName, userId);
    }

    @Override
    @Deprecated
    public final boolean canQueryPackage(int callingUid, @Nullable String packageName) {
        return snapshot().canQueryPackage(callingUid, packageName);
    }

    @Override
    @Deprecated
    public final AndroidPackage getPackage(String packageName) {
        return snapshot().getPackage(packageName);
    }

    @Nullable
    @Override
    @Deprecated
    public final AndroidPackage getAndroidPackage(@NonNull String packageName) {
        return snapshot().getPackage(packageName);
    }

    @Override
    @Deprecated
    public final AndroidPackage getPackage(int uid) {
        return snapshot().getPackage(uid);
    }

    @Override
    @Deprecated
    public final List<AndroidPackage> getPackagesForAppId(int appId) {
        return snapshot().getPackagesForAppId(appId);
    }

    @Nullable
    @Override
    @Deprecated
    public final PackageStateInternal getPackageStateInternal(String packageName) {
        return snapshot().getPackageStateInternal(packageName);
    }

    @NonNull
    @Override
    @Deprecated
    public final ArrayMap<String, ? extends PackageStateInternal> getPackageStates() {
        return snapshot().getPackageStates();
    }

    @Override
    @Deprecated
    public final void removePackageListObserver(PackageListObserver observer) {
        getPackageObserverHelper().removeObserver(observer);
    }

    @Override
    @Deprecated
    public final PackageStateInternal getDisabledSystemPackage(@NonNull String packageName) {
        return snapshot().getDisabledSystemPackage(packageName);
    }

    @Override
    @Deprecated
    public final @NonNull String[] getKnownPackageNames(int knownPackage, int userId) {
        return mService.getKnownPackageNamesInternal(snapshot(), knownPackage, userId);
    }

    @Override
    @Deprecated
    public final void setKeepUninstalledPackages(final List<String> packageList) {
        mService.setKeepUninstalledPackagesInternal(snapshot(), packageList);
    }

    @Override
    @Deprecated
    public final boolean isPermissionsReviewRequired(String packageName, int userId) {
        return getPermissionManager().isPermissionsReviewRequired(packageName, userId);
    }

    @Override
    @Deprecated
    public final PackageInfo getPackageInfo(String packageName,
            @PackageManager.PackageInfoFlagsBits long flags, int filterCallingUid, int userId) {
        return snapshot().getPackageInfoInternal(packageName,
                PackageManager.VERSION_CODE_HIGHEST, flags, filterCallingUid, userId);
    }

    @Override
    @Deprecated
    public final Bundle getSuspendedPackageLauncherExtras(String packageName, int userId) {
        return getSuspendPackageHelper().getSuspendedPackageLauncherExtras(snapshot(), packageName,
                userId, Binder.getCallingUid());
    }

    @Override
    @Deprecated
    public final boolean isPackageSuspended(String packageName, int userId) {
        return getSuspendPackageHelper().isPackageSuspended(snapshot(), packageName, userId,
                Binder.getCallingUid());
    }

    @Override
    @Deprecated
    public final void removeNonSystemPackageSuspensions(String packageName, int userId) {
        getSuspendPackageHelper().removeSuspensionsBySuspendingPackage(snapshot(),
                new String[]{packageName},
                (suspendingPackage) -> !PackageManagerService.PLATFORM_PACKAGE_NAME.equals(
                        suspendingPackage.packageName),
                userId);
    }

    @Override
    @Deprecated
    public final void removeDistractingPackageRestrictions(String packageName, int userId) {
        getDistractingPackageHelper().removeDistractingPackageRestrictions(snapshot(),
                new String[]{packageName}, userId);
    }

    @Override
    @Deprecated
    public final void removeAllDistractingPackageRestrictions(int userId) {
        mService.removeAllDistractingPackageRestrictions(snapshot(), userId);
    }

    @Override
    @Deprecated
    public final UserPackage getSuspendingPackage(String suspendedPackage, int userId) {
        return getSuspendPackageHelper().getSuspendingPackage(snapshot(), suspendedPackage, userId,
                Binder.getCallingUid());
    }

    @Override
    @Deprecated
    public final SuspendDialogInfo getSuspendedDialogInfo(String suspendedPackage,
            UserPackage suspendingPackage, int userId) {
        return getSuspendPackageHelper().getSuspendedDialogInfo(snapshot(), suspendedPackage,
                suspendingPackage, userId, Binder.getCallingUid());
    }

    @Override
    @Deprecated
    public final int getDistractingPackageRestrictions(String packageName, int userId) {
        final PackageStateInternal packageState = getPackageStateInternal(packageName);
        return (packageState == null) ? RESTRICTION_NONE
                : packageState.getUserStateOrDefault(userId).getDistractionFlags();
    }

    @Override
    @Deprecated
    public final int getPackageUid(String packageName,
            @PackageManager.PackageInfoFlagsBits long flags, int userId) {
        return snapshot().getPackageUidInternal(packageName, flags, userId, Process.SYSTEM_UID);
    }

    @Override
    @Deprecated
    public final ApplicationInfo getApplicationInfo(String packageName,
            @PackageManager.ApplicationInfoFlagsBits long flags, int filterCallingUid, int userId) {
        return snapshot().getApplicationInfoInternal(packageName, flags, filterCallingUid, userId);
    }

    @Override
    @Deprecated
    public final ActivityInfo getActivityInfo(ComponentName component,
            @PackageManager.ComponentInfoFlagsBits long flags, int filterCallingUid, int userId) {
        return snapshot().getActivityInfoInternal(component, flags, filterCallingUid, userId);
    }

    @Override
    @Deprecated
    public final List<ResolveInfo> queryIntentActivities(
            Intent intent, String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags,
            int filterCallingUid, int userId) {
        return snapshot().queryIntentActivitiesInternal(intent, resolvedType, flags,
                filterCallingUid, userId);
    }

    @Override
    @Deprecated
    public final List<ResolveInfo> queryIntentReceivers(Intent intent,
            String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags,
            int filterCallingUid, int userId, boolean forSend) {
        return getResolveIntentHelper().queryIntentReceiversInternal(snapshot(), intent,
                resolvedType, flags, userId, filterCallingUid, forSend);
    }

    @Override
    @Deprecated
    public final List<ResolveInfo> queryIntentServices(
            Intent intent, @PackageManager.ResolveInfoFlagsBits long flags, int callingUid,
            int userId) {
        final String resolvedType = intent.resolveTypeIfNeeded(getContext().getContentResolver());
        return snapshot().queryIntentServicesInternal(intent, resolvedType, flags, userId,
                callingUid, false);
    }

    @Override
    @Deprecated
    public final ComponentName getHomeActivitiesAsUser(List<ResolveInfo> allHomeCandidates,
            int userId) {
        return snapshot().getHomeActivitiesAsUser(allHomeCandidates, userId);
    }

    @Override
    @Deprecated
    public final ComponentName getDefaultHomeActivity(int userId) {
        return snapshot().getDefaultHomeActivity(userId);
    }

    @Override
    @Deprecated
    public final ComponentName getSystemUiServiceComponent() {
        return ComponentName.unflattenFromString(getContext().getResources().getString(
                com.android.internal.R.string.config_systemUIServiceComponent));
    }

    @Override
    @Deprecated
    public final void setOwnerProtectedPackages(
            @UserIdInt int userId, @Nullable List<String> packageNames) {
        getProtectedPackages().setOwnerProtectedPackages(userId, packageNames);
    }

    @Override
    @Deprecated
    public final boolean isPackageDataProtected(int userId, String packageName) {
        return getProtectedPackages().isPackageDataProtected(userId, packageName);
    }

    @Override
    @Deprecated
    public final boolean isPackageStateProtected(String packageName, int userId) {
        return getProtectedPackages().isPackageStateProtected(userId, packageName);
    }

    @Override
    @Deprecated
    public final boolean isPackageEphemeral(int userId, String packageName) {
        final PackageStateInternal packageState = getPackageStateInternal(packageName);
        return packageState != null
                && packageState.getUserStateOrDefault(userId).isInstantApp();
    }

    @Override
    @Deprecated
    public final boolean wasPackageEverLaunched(String packageName, int userId) {
        final PackageStateInternal packageState = getPackageStateInternal(packageName);
        if (packageState == null) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }
        return !packageState.getUserStateOrDefault(userId).isNotLaunched();
    }

    @Override
    @Deprecated
    public final boolean isEnabledAndMatches(ParsedMainComponent component, long flags, int userId) {
        return PackageStateUtils.isEnabledAndMatches(
                getPackageStateInternal(component.getPackageName()), component, flags, userId);
    }

    @Override
    @Deprecated
    public final boolean userNeedsBadging(int userId) {
        return getUserNeedsBadging().get(userId);
    }

    @Override
    @Deprecated
    public final String getNameForUid(int uid) {
        return snapshot().getNameForUid(uid);
    }

    @Override
    @Deprecated
    public final void requestInstantAppResolutionPhaseTwo(AuxiliaryResolveInfo responseObj,
            Intent origIntent, String resolvedType, String callingPackage,
            @Nullable String callingFeatureId, boolean isRequesterInstantApp,
            Bundle verificationBundle, int userId) {
        mService.requestInstantAppResolutionPhaseTwo(responseObj, origIntent,
                resolvedType, callingPackage, callingFeatureId, isRequesterInstantApp,
                verificationBundle, userId);
    }

    @Override
    @Deprecated
    public final void grantImplicitAccess(int userId, Intent intent,
            int recipientAppId, int visibleUid, boolean direct) {
        grantImplicitAccess(userId, intent, recipientAppId, visibleUid, direct,
                false /* retainOnUpdate */);
    }

    @Override
    @Deprecated
    public final void grantImplicitAccess(int userId, Intent intent,
            int recipientAppId, int visibleUid, boolean direct, boolean retainOnUpdate) {
        mService.grantImplicitAccess(snapshot(), userId, intent,
                recipientAppId, visibleUid, direct, retainOnUpdate);
    }

    @Override
    @Deprecated
    public final boolean isInstantAppInstallerComponent(ComponentName component) {
        final ActivityInfo instantAppInstallerActivity = mService.mInstantAppInstallerActivity;
        return instantAppInstallerActivity != null
                && instantAppInstallerActivity.getComponentName().equals(component);
    }

    @Override
    @Deprecated
    public final void pruneInstantApps() {
        getInstantAppRegistry().pruneInstantApps(snapshot());
    }

    @Override
    @Deprecated
    public final String getSetupWizardPackageName() {
        return mService.mSetupWizardPackage;
    }

    @Override
    @Deprecated
    public final ResolveInfo resolveIntent(Intent intent, String resolvedType,
            @PackageManager.ResolveInfoFlagsBits long flags,
            @PackageManagerInternal.PrivateResolveFlags long privateResolveFlags, int userId,
            boolean resolveForStart, int filterCallingUid) {
        return getResolveIntentHelper().resolveIntentInternal(snapshot(),
                intent, resolvedType, flags, privateResolveFlags, userId, resolveForStart,
                filterCallingUid);
    }

    /**
     * @deprecated similar to {@link resolveIntent} but limits the matches to exported components.
     */
    @Override
    @Deprecated
    public final ResolveInfo resolveIntentExported(Intent intent, String resolvedType,
            @PackageManager.ResolveInfoFlagsBits long flags,
            @PackageManagerInternal.PrivateResolveFlags long privateResolveFlags, int userId,
            boolean resolveForStart, int filterCallingUid, int callingPid) {
        return getResolveIntentHelper().resolveIntentInternal(snapshot(),
                intent, resolvedType, flags, privateResolveFlags, userId, resolveForStart,
                filterCallingUid, true, callingPid);
    }

    @Override
    @Deprecated
    public final ResolveInfo resolveService(Intent intent, String resolvedType,
            @PackageManager.ResolveInfoFlagsBits long flags, int userId, int callingUid) {
        return getResolveIntentHelper().resolveServiceInternal(snapshot(), intent,
                resolvedType, flags, userId, callingUid);
    }

    @Override
    @Deprecated
    public final ProviderInfo resolveContentProvider(String name,
            @PackageManager.ResolveInfoFlagsBits long flags, int userId, int callingUid) {
        return snapshot().resolveContentProvider(name, flags, userId,callingUid);
    }

    @Override
    @Deprecated
    public final int getUidTargetSdkVersion(int uid) {
        return snapshot().getUidTargetSdkVersion(uid);
    }

    @Override
    @Deprecated
    public final int getPackageTargetSdkVersion(String packageName) {
        final PackageStateInternal packageState = getPackageStateInternal(packageName);
        if (packageState != null && packageState.getPkg() != null) {
            return packageState.getPkg().getTargetSdkVersion();
        }
        return Build.VERSION_CODES.CUR_DEVELOPMENT;
    }

    @Override
    @Deprecated
    public final boolean canAccessInstantApps(int callingUid, @UserIdInt int userId) {
        return snapshot().canViewInstantApps(callingUid, userId);
    }

    @Override
    @Deprecated
    public final boolean canAccessComponent(int callingUid, @NonNull ComponentName component,
            @UserIdInt int userId) {
        return snapshot().canAccessComponent(callingUid, component, userId);
    }

    @Override
    @Deprecated
    public final boolean hasInstantApplicationMetadata(String packageName, int userId) {
        return getInstantAppRegistry().hasInstantApplicationMetadata(packageName, userId);
    }

    @Override
    @Deprecated
    public final SparseArray<String> getAppsWithSharedUserIds() {
        return snapshot().getAppsWithSharedUserIds();
    }

    @Override
    @NonNull
    @Deprecated
    public final String[] getSharedUserPackagesForPackage(String packageName, int userId) {
        return snapshot().getSharedUserPackagesForPackage(packageName, userId);
    }

    @Override
    @Deprecated
    public final ArrayMap<String, ProcessInfo> getProcessesForUid(int uid) {
        return snapshot().getProcessesForUid(uid);
    }

    @Override
    @Deprecated
    public final int[] getPermissionGids(String permissionName, int userId) {
        return getPermissionManager().getPermissionGids(permissionName, userId);
    }

    @Override
    @Deprecated
    public final void freeStorage(String volumeUuid, long bytes,
            @StorageManager.AllocateFlags int flags) throws IOException {
        mService.freeStorage(volumeUuid, bytes, flags);
    }

    @Override
    @Deprecated
    public final void freeAllAppCacheAboveQuota(@NonNull String volumeUuid) throws IOException {
        mService.freeAllAppCacheAboveQuota(volumeUuid);
    }

    @Override
    @Deprecated
    public final void forEachPackageSetting(Consumer<PackageSetting> actionLocked) {
        mService.forEachPackageSetting(actionLocked);
    }

    @Override
    @Deprecated
    public final void forEachPackageState(Consumer<PackageStateInternal> action) {
        mService.forEachPackageState(snapshot(), action);
    }

    @Override
    @Deprecated
    public final void forEachPackage(Consumer<AndroidPackage> action) {
        mService.forEachPackage(snapshot(), action);
    }

    @Override
    @Deprecated
    public final void forEachInstalledPackage(@NonNull Consumer<AndroidPackage> action,
            @UserIdInt int userId) {
        mService.forEachInstalledPackage(snapshot(), action, userId);
    }

    @Override
    @Deprecated
    public final ArraySet<String> getEnabledComponents(String packageName, int userId) {
        final PackageStateInternal packageState = getPackageStateInternal(packageName);
        if (packageState == null) {
            return new ArraySet<>();
        }
        return packageState.getUserStateOrDefault(userId).getEnabledComponents();
    }

    @Override
    @Deprecated
    public final ArraySet<String> getDisabledComponents(String packageName, int userId) {
        final PackageStateInternal packageState = getPackageStateInternal(packageName);
        if (packageState == null) {
            return new ArraySet<>();
        }
        return packageState.getUserStateOrDefault(userId).getDisabledComponents();
    }

    @Override
    @Deprecated
    public final @PackageManager.EnabledState int getApplicationEnabledState(
            String packageName, int userId) {
        final PackageStateInternal packageState = getPackageStateInternal(packageName);
        if (packageState == null) {
            return COMPONENT_ENABLED_STATE_DEFAULT;
        }
        return packageState.getUserStateOrDefault(userId).getEnabledState();
    }

    @Override
    @Deprecated
    public final @PackageManager.EnabledState int getComponentEnabledSetting(
            @NonNull ComponentName componentName, int callingUid, int userId) {
        return snapshot().getComponentEnabledSettingInternal(
                componentName, callingUid, userId);
    }

    @Override
    @Deprecated
    public final void setEnableRollbackCode(int token, int enableRollbackCode) {
        mService.setEnableRollbackCode(token, enableRollbackCode);
    }

    @Override
    @Deprecated
    public final void finishPackageInstall(int token, boolean didLaunch) {
        mService.finishPackageInstall(token, didLaunch);
    }

    @Override
    @Deprecated
    public final boolean isApexPackage(String packageName) {
        return snapshot().isApexPackage(packageName);
    }

    @Override
    @Deprecated
    public final List<String> getApksInApex(String apexPackageName) {
        return getApexManager().getApksInApex(apexPackageName);
    }

    @Override
    @Deprecated
    public final boolean isCallerInstallerOfRecord(@NonNull AndroidPackage pkg, int callingUid) {
        return snapshot().isCallerInstallerOfRecord(pkg, callingUid);
    }

    @Override
    @Deprecated
    public final List<String> getMimeGroup(String packageName, String mimeGroup) {
        return mService.getMimeGroupInternal(snapshot(), packageName, mimeGroup);
    }

    @Override
    @Deprecated
    public final boolean isSystemPackage(@NonNull String packageName) {
        return packageName.equals(mService.ensureSystemPackageName(snapshot(), packageName));
    }

    @Override
    @Deprecated
    public final void unsuspendAdminSuspendedPackages(int affectedUser) {
        final int suspendingUserId =
                crossUserSuspensionEnabledRo() ? UserHandle.USER_SYSTEM : affectedUser;
        mService.unsuspendForSuspendingPackage(
                snapshot(), PLATFORM_PACKAGE_NAME, suspendingUserId, /* inAllUsers= */ false);
    }

    @Override
    @Deprecated
    public final boolean isAdminSuspendingAnyPackages(int userId) {
        final int suspendingUserId =
                crossUserSuspensionEnabledRo() ? UserHandle.USER_SYSTEM : userId;
        return snapshot().isSuspendingAnyPackages(PLATFORM_PACKAGE_NAME, suspendingUserId, userId);
    }

    @Override
    @Deprecated
    public final void requestChecksums(@NonNull String packageName, boolean includeSplits,
            @Checksum.TypeMask int optional, @Checksum.TypeMask int required,
            @Nullable List trustedInstallers,
            @NonNull IOnChecksumsReadyListener onChecksumsReadyListener, int userId,
            @NonNull Executor executor, @NonNull Handler handler) {
        mService.requestChecksumsInternal(snapshot(), packageName, includeSplits, optional,
                required, trustedInstallers, onChecksumsReadyListener, userId, executor,
                handler);
    }

    @Override
    @Deprecated
    public final boolean isPackageFrozen(@NonNull String packageName,
            int callingUid, int userId) {
        return snapshot().getPackageStartability(mService.getSafeMode(), packageName, callingUid, userId)
                == PackageManagerService.PACKAGE_STARTABILITY_FROZEN;
    }

    @Override
    @Deprecated
    public final long deleteOatArtifactsOfPackage(String packageName) {
        return mService.deleteOatArtifactsOfPackage(snapshot(), packageName);
    }

    @Override
    @Deprecated
    public final void reconcileAppsData(int userId, @StorageManager.StorageFlags int flags,
            boolean migrateAppsData) {
        getAppDataHelper().reconcileAppsData(userId, flags, migrateAppsData);
    }

    @Override
    @NonNull
    public ArraySet<PackageStateInternal> getSharedUserPackages(int sharedUserAppId) {
        return snapshot().getSharedUserPackages(sharedUserAppId);
    }

    @Override
    @Nullable
    public SharedUserApi getSharedUserApi(int sharedUserAppId) {
        return snapshot().getSharedUser(sharedUserAppId);
    }

    @Override
    public boolean isUidPrivileged(int uid) {
        return snapshot().isUidPrivileged(uid);
    }

    @Override
    public int checkUidSignaturesForAllUsers(int uid1, int uid2) {
        return snapshot().checkUidSignaturesForAllUsers(uid1, uid2);
    }

    @Override
    public void setPackageStoppedState(@NonNull String packageName, boolean stopped,
            int userId) {
        mService.setPackageStoppedState(snapshot(), packageName, stopped, userId);
    }

    @Override
    public void notifyComponentUsed(@NonNull String packageName, @UserIdInt int userId,
            @Nullable String recentCallingPackage, @NonNull String debugInfo) {
        mService.notifyComponentUsed(snapshot(), packageName, userId,
                recentCallingPackage, debugInfo);
    }

    @Override
    public boolean isPackageQuarantined(@NonNull String packageName, @UserIdInt int userId)
            throws PackageManager.NameNotFoundException {
        return snapshot().isPackageQuarantinedForUser(packageName, userId);
    }

    @Override
    public boolean isPackageStopped(@NonNull String packageName, @UserIdInt int userId)
            throws PackageManager.NameNotFoundException {
        return snapshot().isPackageStoppedForUser(packageName, userId);
    }

    @NonNull
    @Override
    @Deprecated
    public final PackageStateMutator.InitialState recordInitialState() {
        return mService.recordInitialState();
    }

    @Nullable
    @Override
    @Deprecated
    public final PackageStateMutator.Result commitPackageStateMutation(
            @Nullable PackageStateMutator.InitialState state,
            @NonNull Consumer<PackageStateMutator> consumer) {
        return mService.commitPackageStateMutation(state, consumer);
    }

    @Override
    @Deprecated
    public final void shutdown() {
        mService.shutdown();
    }
}
