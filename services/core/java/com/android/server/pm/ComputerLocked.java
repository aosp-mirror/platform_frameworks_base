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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.KeySet;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.ProcessInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.SigningDetails;
import android.content.pm.VersionedPackage;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageState;
import com.android.server.pm.pkg.PackageStateInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * This subclass is the external interface to the live computer.  Some internal helper
 * methods are overridden to fetch live data instead of snapshot data.  For each
 * Computer interface that is overridden in this class, the override takes the PM lock
 * and then delegates to the live computer engine.  This is required because there are
 * no locks taken in the engine itself.
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
public final class ComputerLocked extends ComputerEngine {
    private final Object mLock;

    ComputerLocked(PackageManagerService.Snapshot args) {
        super(args);
        mLock = mService.mLock;
    }

    protected ComponentName resolveComponentName() {
        return mService.getResolveComponentName();
    }
    protected ActivityInfo instantAppInstallerActivity() {
        return mService.mInstantAppInstallerActivity;
    }
    protected ApplicationInfo androidApplication() {
        return mService.getCoreAndroidApplication();
    }

    public @NonNull List<ResolveInfo> queryIntentServicesInternalBody(Intent intent,
            String resolvedType, int flags, int userId, int callingUid,
            String instantAppPkgName) {
        synchronized (mLock) {
            return super.queryIntentServicesInternalBody(intent, resolvedType, flags, userId,
                    callingUid, instantAppPkgName);
        }
    }
    public @NonNull QueryIntentActivitiesResult queryIntentActivitiesInternalBody(
            Intent intent, String resolvedType, long flags, int filterCallingUid, int userId,
            boolean resolveForStart, boolean allowDynamicSplits, String pkgName,
            String instantAppPkgName) {
        synchronized (mLock) {
            return super.queryIntentActivitiesInternalBody(intent, resolvedType, flags,
                    filterCallingUid, userId, resolveForStart, allowDynamicSplits, pkgName,
                    instantAppPkgName);
        }
    }
    public ActivityInfo getActivityInfoInternalBody(ComponentName component, int flags,
            int filterCallingUid, int userId) {
        synchronized (mLock) {
            return super.getActivityInfoInternalBody(component, flags, filterCallingUid,
                    userId);
        }
    }
    public AndroidPackage getPackage(String packageName) {
        synchronized (mLock) {
            return super.getPackage(packageName);
        }
    }
    public AndroidPackage getPackage(int uid) {
        synchronized (mLock) {
            return super.getPackage(uid);
        }
    }
    public ApplicationInfo getApplicationInfoInternalBody(String packageName, int flags,
            int filterCallingUid, int userId) {
        synchronized (mLock) {
            return super.getApplicationInfoInternalBody(packageName, flags, filterCallingUid,
                    userId);
        }
    }
    public ArrayList<ResolveInfo> filterCandidatesWithDomainPreferredActivitiesLPrBody(
            Intent intent, int matchFlags, List<ResolveInfo> candidates,
            CrossProfileDomainInfo xpDomainInfo, int userId, boolean debug) {
        synchronized (mLock) {
            return super.filterCandidatesWithDomainPreferredActivitiesLPrBody(intent,
                    matchFlags, candidates, xpDomainInfo, userId, debug);
        }
    }
    public PackageInfo getPackageInfoInternalBody(String packageName, long versionCode,
            int flags, int filterCallingUid, int userId) {
        synchronized (mLock) {
            return super.getPackageInfoInternalBody(packageName, versionCode, flags,
                    filterCallingUid, userId);
        }
    }

    @Override
    public String[] getAllAvailablePackageNames() {
        synchronized (mLock) {
            return super.getAllAvailablePackageNames();
        }
    }

    public PackageStateInternal getPackageStateInternal(String packageName, int callingUid) {
        synchronized (mLock) {
            return super.getPackageStateInternal(packageName, callingUid);
        }
    }

    @Nullable
    public PackageState getPackageStateCopied(@NonNull String packageName) {
        synchronized (mLock) {
            return super.getPackageStateCopied(packageName);
        }
    }

    public ParceledListSlice<PackageInfo> getInstalledPackagesBody(int flags, int userId,
            int callingUid) {
        synchronized (mLock) {
            return super.getInstalledPackagesBody(flags, userId, callingUid);
        }
    }
    public ServiceInfo getServiceInfoBody(ComponentName component, int flags, int userId,
            int callingUid) {
        synchronized (mLock) {
            return super.getServiceInfoBody(component, flags, userId, callingUid);
        }
    }
    public String getInstantAppPackageName(int callingUid) {
        synchronized (mLock) {
            return super.getInstantAppPackageName(callingUid);
        }
    }
    public String[] getPackagesForUidInternalBody(int callingUid, int userId, int appId,
            boolean isCallerInstantApp) {
        synchronized (mLock) {
            return super.getPackagesForUidInternalBody(callingUid, userId, appId,
                    isCallerInstantApp);
        }
    }
    public boolean isInstantAppInternalBody(String packageName, @UserIdInt int userId,
            int callingUid) {
        synchronized (mLock) {
            return super.isInstantAppInternalBody(packageName, userId, callingUid);
        }
    }
    public boolean isInstantAppResolutionAllowedBody(Intent intent,
            List<ResolveInfo> resolvedActivities, int userId, boolean skipPackageCheck,
            int flags) {
        synchronized (mLock) {
            return super.isInstantAppResolutionAllowedBody(intent, resolvedActivities, userId,
                    skipPackageCheck, flags);
        }
    }
    public int getPackageUidInternal(String packageName, int flags, int userId,
            int callingUid) {
        synchronized (mLock) {
            return super.getPackageUidInternal(packageName, flags, userId, callingUid);
        }
    }
    public SigningDetails getSigningDetails(@NonNull String packageName) {
        synchronized (mLock) {
            return super.getSigningDetails(packageName);
        }
    }
    public SigningDetails getSigningDetails(int uid) {
        synchronized (mLock) {
            return super.getSigningDetails(uid);
        }
    }
    public boolean filterAppAccess(AndroidPackage pkg, int callingUid, int userId) {
        synchronized (mLock) {
            return super.filterAppAccess(pkg, callingUid, userId);
        }
    }
    public boolean filterAppAccess(String packageName, int callingUid, int userId) {
        synchronized (mLock) {
            return super.filterAppAccess(packageName, callingUid, userId);
        }
    }
    public boolean filterAppAccess(int uid, int callingUid) {
        synchronized (mLock) {
            return super.filterAppAccess(uid, callingUid);
        }
    }
    public void dump(int type, FileDescriptor fd, PrintWriter pw, DumpState dumpState) {
        synchronized (mLock) {
            super.dump(type, fd, pw, dumpState);
        }
    }
    public PackageManagerService.FindPreferredActivityBodyResult findPreferredActivityBody(
            Intent intent, String resolvedType, int flags, List<ResolveInfo> query, boolean always,
            boolean removeMatches, boolean debug, int userId, boolean queryMayBeFiltered,
            int callingUid, boolean isDeviceProvisioned) {
        synchronized (mLock) {
            return super.findPreferredActivityBody(intent, resolvedType, flags, query, always,
                    removeMatches, debug, userId, queryMayBeFiltered, callingUid,
                    isDeviceProvisioned);
        }
    }

    @Override
    public PreferredIntentResolver getPreferredActivities(int userId) {
        synchronized (mLock) {
            return super.getPreferredActivities(userId);
        }
    }

    @NonNull
    @Override
    public ArrayMap<String, ? extends PackageStateInternal> getPackageStates() {
        synchronized (mLock) {
            return super.getPackageStates();
        }
    }

    @Nullable
    @Override
    public String getRenamedPackage(@NonNull String packageName) {
        synchronized (mLock) {
            return super.getRenamedPackage(packageName);
        }
    }

    @NonNull
    @Override
    public ArraySet<String> getNotifyPackagesForReplacedReceived(@NonNull String[] packages) {
        synchronized (mLock) {
            return super.getNotifyPackagesForReplacedReceived(packages);
        }
    }

    @Override
    public int getPackageStartability(boolean safeMode, @NonNull String packageName, int callingUid,
            @UserIdInt int userId) {
        synchronized (mLock) {
            return super.getPackageStartability(safeMode, packageName, callingUid, userId);
        }
    }

    @Override
    public boolean isPackageAvailable(String packageName, @UserIdInt int userId) {
        synchronized (mLock) {
            return super.isPackageAvailable(packageName, userId);
        }
    }

    @Override
    public String[] currentToCanonicalPackageNames(String[] names) {
        synchronized (mLock) {
            return super.currentToCanonicalPackageNames(names);
        }
    }

    @Override
    public String[] canonicalToCurrentPackageNames(String[] names) {
        synchronized (mLock) {
            return super.canonicalToCurrentPackageNames(names);
        }
    }

    @Override
    public int[] getPackageGids(@NonNull String packageName,
            @PackageManager.PackageInfoFlagsBits long flags, @UserIdInt int userId) {
        synchronized (mLock) {
            return super.getPackageGids(packageName, flags, userId);
        }
    }

    @Override
    public int getTargetSdkVersion(@NonNull String packageName) {
        synchronized (mLock) {
            return super.getTargetSdkVersion(packageName);
        }
    }

    @Override
    public boolean activitySupportsIntent(@NonNull ComponentName resolveComponentName,
            @NonNull ComponentName component, @NonNull Intent intent, String resolvedType) {
        synchronized (mLock) {
            return super.activitySupportsIntent(resolveComponentName, component, intent,
                    resolvedType);
        }
    }

    @Nullable
    @Override
    public ActivityInfo getReceiverInfo(@NonNull ComponentName component,
            @PackageManager.ComponentInfoFlagsBits long flags, @UserIdInt int userId) {
        synchronized (mLock) {
            return super.getReceiverInfo(component, flags, userId);
        }
    }

    @Nullable
    @Override
    public ParceledListSlice<SharedLibraryInfo> getSharedLibraries(@NonNull String packageName,
            @PackageManager.PackageInfoFlagsBits long flags, @UserIdInt int userId) {
        synchronized (mLock) {
            return super.getSharedLibraries(packageName, flags, userId);
        }
    }

    @Override
    public boolean canRequestPackageInstalls(@NonNull String packageName, int callingUid,
            @UserIdInt int userId, boolean throwIfPermNotDeclared) {
        synchronized (mLock) {
            return super.canRequestPackageInstalls(packageName, callingUid, userId,
                    throwIfPermNotDeclared);
        }
    }

    @Override
    public List<VersionedPackage> getPackagesUsingSharedLibrary(@NonNull SharedLibraryInfo libInfo,
            @PackageManager.PackageInfoFlagsBits long flags, int callingUid,
            @UserIdInt int userId) {
        synchronized (mLock) {
            return super.getPackagesUsingSharedLibrary(libInfo, flags, callingUid, userId);
        }
    }

    @Nullable
    @Override
    public ParceledListSlice<SharedLibraryInfo> getDeclaredSharedLibraries(
            @NonNull String packageName, @PackageManager.PackageInfoFlagsBits long flags,
            @UserIdInt int userId) {
        synchronized (mLock) {
            return super.getDeclaredSharedLibraries(packageName, flags, userId);
        }
    }

    @Nullable
    @Override
    public ProviderInfo getProviderInfo(@NonNull ComponentName component,
            @PackageManager.ComponentInfoFlagsBits long flags, @UserIdInt int userId) {
        synchronized (mLock) {
            return super.getProviderInfo(component, flags, userId);
        }
    }

    @Nullable
    @Override
    public String[] getSystemSharedLibraryNames() {
        synchronized (mLock) {
            return super.getSystemSharedLibraryNames();
        }
    }

    @Override
    public boolean isPackageStateAvailableAndVisible(@NonNull String packageName, int callingUid,
            @UserIdInt int userId) {
        synchronized (mLock) {
            return super.isPackageStateAvailableAndVisible(packageName, callingUid, userId);
        }
    }

    @Override
    public int checkSignatures(@NonNull String pkg1,
            @NonNull String pkg2) {
        synchronized (mLock) {
            return super.checkSignatures(pkg1, pkg2);
        }
    }

    @Override
    public int checkUidSignatures(int uid1, int uid2) {
        synchronized (mLock) {
            return super.checkUidSignatures(uid1, uid2);
        }
    }

    @Override
    public boolean hasSigningCertificate(@NonNull String packageName, @NonNull byte[] certificate,
            int type) {
        synchronized (mLock) {
            return super.hasSigningCertificate(packageName, certificate, type);
        }
    }

    @Override
    public boolean hasUidSigningCertificate(int uid, @NonNull byte[] certificate, int type) {
        synchronized (mLock) {
            return super.hasUidSigningCertificate(uid, certificate, type);
        }
    }

    @Override
    public List<String> getAllPackages() {
        synchronized (mLock) {
            return super.getAllPackages();
        }
    }

    @Nullable
    @Override
    public String getNameForUid(int uid) {
        synchronized (mLock) {
            return super.getNameForUid(uid);
        }
    }

    @Nullable
    @Override
    public String[] getNamesForUids(int[] uids) {
        synchronized (mLock) {
            return super.getNamesForUids(uids);
        }
    }

    @Override
    public int getUidForSharedUser(@NonNull String sharedUserName) {
        synchronized (mLock) {
            return super.getUidForSharedUser(sharedUserName);
        }
    }

    @Override
    public int getFlagsForUid(int uid) {
        synchronized (mLock) {
            return super.getFlagsForUid(uid);
        }
    }

    @Override
    public int getPrivateFlagsForUid(int uid) {
        synchronized (mLock) {
            return super.getPrivateFlagsForUid(uid);
        }
    }

    @Override
    public boolean isUidPrivileged(int uid) {
        synchronized (mLock) {
            return super.isUidPrivileged(uid);
        }
    }

    @NonNull
    @Override
    public String[] getAppOpPermissionPackages(@NonNull String permissionName) {
        synchronized (mLock) {
            return super.getAppOpPermissionPackages(permissionName);
        }
    }

    @NonNull
    @Override
    public ParceledListSlice<PackageInfo> getPackagesHoldingPermissions(
            @NonNull String[] permissions, @PackageManager.PackageInfoFlagsBits long flags,
            @UserIdInt int userId) {
        synchronized (mLock) {
            return super.getPackagesHoldingPermissions(permissions, flags, userId);
        }
    }

    @NonNull
    @Override
    public List<ApplicationInfo> getInstalledApplications(
            @PackageManager.ApplicationInfoFlagsBits long flags, @UserIdInt int userId,
            int callingUid) {
        synchronized (mLock) {
            return super.getInstalledApplications(flags, userId, callingUid);
        }
    }

    @Nullable
    @Override
    public ProviderInfo resolveContentProvider(@NonNull String name,
            @PackageManager.ResolveInfoFlagsBits long flags, @UserIdInt int userId,
            int callingUid) {
        synchronized (mLock) {
            return super.resolveContentProvider(name, flags, userId, callingUid);
        }
    }

    @Nullable
    @Override
    public ProviderInfo getGrantImplicitAccessProviderInfo(int recipientUid,
            @NonNull String visibleAuthority) {
        synchronized (mLock) {
            return super.getGrantImplicitAccessProviderInfo(recipientUid, visibleAuthority);
        }
    }

    @Override
    public void querySyncProviders(boolean safeMode, @NonNull List<String> outNames,
            @NonNull List<ProviderInfo> outInfo) {
        synchronized (mLock) {
            super.querySyncProviders(safeMode, outNames, outInfo);
        }
    }

    @NonNull
    @Override
    public ParceledListSlice<ProviderInfo> queryContentProviders(@Nullable String processName,
            int uid, @PackageManager.ComponentInfoFlagsBits long flags,
            @Nullable String metaDataKey) {
        synchronized (mLock) {
            return super.queryContentProviders(processName, uid, flags, metaDataKey);
        }
    }

    @Nullable
    @Override
    public InstrumentationInfo getInstrumentationInfo(@NonNull ComponentName component, int flags) {
        synchronized (mLock) {
            return super.getInstrumentationInfo(component, flags);
        }
    }

    @NonNull
    @Override
    public ParceledListSlice<InstrumentationInfo> queryInstrumentation(
            @NonNull String targetPackage, int flags) {
        synchronized (mLock) {
            return super.queryInstrumentation(targetPackage, flags);
        }
    }

    @NonNull
    @Override
    public List<PackageStateInternal> findSharedNonSystemLibraries(
            @NonNull PackageStateInternal pkgSetting) {
        synchronized (mLock) {
            return super.findSharedNonSystemLibraries(pkgSetting);
        }
    }

    @Override
    public boolean getApplicationHiddenSettingAsUser(@NonNull String packageName,
            @UserIdInt int userId) {
        synchronized (mLock) {
            return super.getApplicationHiddenSettingAsUser(packageName, userId);
        }
    }

    @Override
    public boolean isPackageSuspendedForUser(@NonNull String packageName, @UserIdInt int userId) {
        synchronized (mLock) {
            return super.isPackageSuspendedForUser(packageName, userId);
        }
    }

    @Override
    public boolean isSuspendingAnyPackages(@NonNull String suspendingPackage,
            @UserIdInt int userId) {
        synchronized (mLock) {
            return super.isSuspendingAnyPackages(suspendingPackage, userId);
        }
    }

    @NonNull
    @Override
    public ParceledListSlice<IntentFilter> getAllIntentFilters(@NonNull String packageName) {
        synchronized (mLock) {
            return super.getAllIntentFilters(packageName);
        }
    }

    @Override
    public boolean getBlockUninstallForUser(@NonNull String packageName, @UserIdInt int userId) {
        synchronized (mLock) {
            return super.getBlockUninstallForUser(packageName, userId);
        }
    }

    @Nullable
    @Override
    public SparseArray<int[]> getBroadcastAllowList(@NonNull String packageName,
            @UserIdInt int[] userIds, boolean isInstantApp) {
        synchronized (mLock) {
            return super.getBroadcastAllowList(packageName, userIds, isInstantApp);
        }
    }

    @Nullable
    @Override
    public String getInstallerPackageName(@NonNull String packageName) {
        synchronized (mLock) {
            return super.getInstallerPackageName(packageName);
        }
    }

    @Nullable
    @Override
    public InstallSourceInfo getInstallSourceInfo(@NonNull String packageName) {
        synchronized (mLock) {
            return super.getInstallSourceInfo(packageName);
        }
    }

    @Override
    public int getApplicationEnabledSetting(@NonNull String packageName, @UserIdInt int userId) {
        synchronized (mLock) {
            return super.getApplicationEnabledSetting(packageName, userId);
        }
    }

    @Override
    public int getComponentEnabledSetting(@NonNull ComponentName component, int callingUid,
            @UserIdInt int userId) {
        synchronized (mLock) {
            return super.getComponentEnabledSetting(component, callingUid, userId);
        }
    }

    @Override
    public int getComponentEnabledSettingInternal(@NonNull ComponentName component, int callingUid,
            @UserIdInt int userId) {
        synchronized (mLock) {
            return super.getComponentEnabledSettingInternal(component, callingUid, userId);
        }
    }

    @Override
    public boolean isComponentEffectivelyEnabled(@NonNull ComponentInfo componentInfo,
            @UserIdInt int userId) {
        synchronized (mLock) {
            return super.isComponentEffectivelyEnabled(componentInfo, userId);
        }
    }

    @Nullable
    @Override
    public KeySet getKeySetByAlias(@NonNull String packageName, @NonNull String alias) {
        synchronized (mLock) {
            return super.getKeySetByAlias(packageName, alias);
        }
    }

    @Nullable
    @Override
    public KeySet getSigningKeySet(@NonNull String packageName) {
        synchronized (mLock) {
            return super.getSigningKeySet(packageName);
        }
    }

    @Override
    public boolean isPackageSignedByKeySet(@NonNull String packageName, @NonNull KeySet ks) {
        synchronized (mLock) {
            return super.isPackageSignedByKeySet(packageName, ks);
        }
    }

    @Override
    public boolean isPackageSignedByKeySetExactly(@NonNull String packageName, @NonNull KeySet ks) {
        synchronized (mLock) {
            return super.isPackageSignedByKeySetExactly(packageName, ks);
        }
    }

    @Nullable
    @Override
    public int[] getVisibilityAllowList(@NonNull String packageName, @UserIdInt int userId) {
        synchronized (mLock) {
            return super.getVisibilityAllowList(packageName, userId);
        }
    }

    @Override
    public boolean canQueryPackage(int callingUid, @Nullable String targetPackageName) {
        synchronized (mLock) {
            return super.canQueryPackage(callingUid, targetPackageName);
        }
    }

    @Override
    public int getPackageUid(@NonNull String packageName,
            @PackageManager.PackageInfoFlagsBits long flags, @UserIdInt int userId) {
        synchronized (mLock) {
            return super.getPackageUid(packageName, flags, userId);
        }
    }

    @Override
    public boolean canAccessComponent(int callingUid, @NonNull ComponentName component,
            @UserIdInt int userId) {
        synchronized (mLock) {
            return super.canAccessComponent(callingUid, component, userId);
        }
    }

    @Override
    public boolean isCallerInstallerOfRecord(@NonNull AndroidPackage pkg, int callingUid) {
        synchronized (mLock) {
            return super.isCallerInstallerOfRecord(pkg, callingUid);
        }
    }

    @Override
    public int getInstallReason(@NonNull String packageName, @UserIdInt int userId) {
        synchronized (mLock) {
            return super.getInstallReason(packageName, userId);
        }
    }

    @Override
    public boolean canPackageQuery(@NonNull String sourcePackageName,
            @NonNull String targetPackageName, @UserIdInt int userId) {
        synchronized (mLock) {
            return super.canPackageQuery(sourcePackageName, targetPackageName, userId);
        }
    }

    @Override
    public boolean canForwardTo(@NonNull Intent intent, @Nullable String resolvedType,
            @UserIdInt int sourceUserId, @UserIdInt int targetUserId) {
        synchronized (mLock) {
            return super.canForwardTo(intent, resolvedType, sourceUserId, targetUserId);
        }
    }

    @NonNull
    @Override
    public List<ApplicationInfo> getPersistentApplications(boolean safeMode, int flags) {
        synchronized (mLock) {
            return super.getPersistentApplications(safeMode, flags);
        }
    }

    @NonNull
    @Override
    public SparseArray<String> getAppsWithSharedUserIds() {
        synchronized (mLock) {
            return super.getAppsWithSharedUserIds();
        }
    }

    @NonNull
    @Override
    public String[] getSharedUserPackagesForPackage(@NonNull String packageName,
            @UserIdInt int userId) {
        synchronized (mLock) {
            return super.getSharedUserPackagesForPackage(packageName, userId);
        }
    }

    @NonNull
    @Override
    public Set<String> getUnusedPackages(long downgradeTimeThresholdMillis) {
        synchronized (mLock) {
            return super.getUnusedPackages(downgradeTimeThresholdMillis);
        }
    }

    @Nullable
    @Override
    public CharSequence getHarmfulAppWarning(@NonNull String packageName, @UserIdInt int userId) {
        synchronized (mLock) {
            return super.getHarmfulAppWarning(packageName, userId);
        }
    }

    @NonNull
    @Override
    public String[] filterOnlySystemPackages(@Nullable String... pkgNames) {
        synchronized (mLock) {
            return super.filterOnlySystemPackages(pkgNames);
        }
    }

    @NonNull
    @Override
    public List<AndroidPackage> getPackagesForAppId(int appId) {
        synchronized (mLock) {
            return super.getPackagesForAppId(appId);
        }
    }

    @Override
    public int getUidTargetSdkVersion(int uid) {
        synchronized (mLock) {
            return super.getUidTargetSdkVersion(uid);
        }
    }

    @Nullable
    @Override
    public ArrayMap<String, ProcessInfo> getProcessesForUid(int uid) {
        synchronized (mLock) {
            return super.getProcessesForUid(uid);
        }
    }
}
