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
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.ProcessInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.SigningDetails;
import android.content.pm.UserInfo;
import android.content.pm.VersionedPackage;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;

import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageState;
import com.android.server.pm.pkg.PackageStateInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This subclass delegates to methods in a Computer after reference-counting the computer.
 */
public final class ComputerTracker implements Computer {

    // The number of times a thread reused a computer in its stack instead of fetching
    // a snapshot computer.
    private final AtomicInteger mReusedSnapshot = new AtomicInteger(0);

    // The number of times a thread reused a computer in its stack instead of fetching
    // a live computer.
    private final AtomicInteger mReusedLive = new AtomicInteger(0);

    private final PackageManagerService mService;
    ComputerTracker(PackageManagerService s) {
        mService = s;
    }

    private ThreadComputer live() {
        ThreadComputer current = PackageManagerService.sThreadComputer.get();
        if (current.mRefCount > 0) {
            current.acquire();
            mReusedLive.incrementAndGet();
        } else {
            current.acquire(mService.liveComputer());
        }
        return current;
    }

    private ThreadComputer snapshot() {
        ThreadComputer current = PackageManagerService.sThreadComputer.get();
        if (current.mRefCount > 0) {
            current.acquire();
            mReusedSnapshot.incrementAndGet();
        } else {
            current.acquire(mService.snapshotComputer());
        }
        return current;
    }

    public @NonNull List<ResolveInfo> queryIntentActivitiesInternal(Intent intent,
            String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags,
            @PackageManagerInternal.PrivateResolveFlags long privateResolveFlags,
            int filterCallingUid, int userId, boolean resolveForStart,
            boolean allowDynamicSplits) {
        ThreadComputer current = snapshot();
        try {
            return current.mComputer.queryIntentActivitiesInternal(intent, resolvedType, flags,
                    privateResolveFlags, filterCallingUid, userId, resolveForStart,
                    allowDynamicSplits);
        } finally {
            current.release();
        }
    }
    public @NonNull List<ResolveInfo> queryIntentActivitiesInternal(Intent intent,
            String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags, int userId) {
        ThreadComputer current = snapshot();
        try {
            return current.mComputer.queryIntentActivitiesInternal(intent, resolvedType, flags,
                    userId);
        } finally {
            current.release();
        }
    }
    public @NonNull List<ResolveInfo> queryIntentServicesInternal(Intent intent,
            String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags, int userId,
            int callingUid, boolean includeInstantApps) {
        ThreadComputer current = snapshot();
        try {
            return current.mComputer.queryIntentServicesInternal(intent, resolvedType, flags,
                    userId, callingUid, includeInstantApps);
        } finally {
            current.release();
        }
    }
    public @NonNull QueryIntentActivitiesResult queryIntentActivitiesInternalBody(
            Intent intent, String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags,
            int filterCallingUid, int userId, boolean resolveForStart, boolean allowDynamicSplits,
            String pkgName, String instantAppPkgName) {
        ThreadComputer current = live();
        try {
            return current.mComputer.queryIntentActivitiesInternalBody(intent, resolvedType,
                    flags, filterCallingUid, userId, resolveForStart, allowDynamicSplits,
                    pkgName, instantAppPkgName);
        } finally {
            current.release();
        }
    }
    public ActivityInfo getActivityInfo(ComponentName component,
            @PackageManager.ComponentInfoFlagsBits long flags, int userId) {
        ThreadComputer current = snapshot();
        try {
            return current.mComputer.getActivityInfo(component, flags, userId);
        } finally {
            current.release();
        }
    }
    public ActivityInfo getActivityInfoInternal(ComponentName component,
            @PackageManager.ComponentInfoFlagsBits long flags,
            int filterCallingUid, int userId) {
        ThreadComputer current = live();
        try {
            return current.mComputer.getActivityInfoInternal(component, flags, filterCallingUid,
                    userId);
        } finally {
            current.release();
        }
    }
    public AndroidPackage getPackage(String packageName) {
        ThreadComputer current = snapshot();
        try {
            return current.mComputer.getPackage(packageName);
        } finally {
            current.release();
        }
    }
    public AndroidPackage getPackage(int uid) {
        ThreadComputer current = snapshot();
        try {
            return current.mComputer.getPackage(uid);
        } finally {
            current.release();
        }
    }
    public ApplicationInfo generateApplicationInfoFromSettings(String packageName,
            long flags, int filterCallingUid, int userId) {
        ThreadComputer current = live();
        try {
            return current.mComputer.generateApplicationInfoFromSettings(packageName, flags,
                    filterCallingUid, userId);
        } finally {
            current.release();
        }
    }
    public ApplicationInfo getApplicationInfo(String packageName,
            @PackageManager.ApplicationInfoFlagsBits long flags, int userId) {
        ThreadComputer current = snapshot();
        try {
            return current.mComputer.getApplicationInfo(packageName, flags, userId);
        } finally {
            current.release();
        }
    }
    public ApplicationInfo getApplicationInfoInternal(String packageName,
            @PackageManager.ApplicationInfoFlagsBits long flags, int filterCallingUid, int userId) {
        ThreadComputer current = live();
        try {
            return current.mComputer.getApplicationInfoInternal(packageName, flags,
                    filterCallingUid, userId);
        } finally {
            current.release();
        }
    }
    public ComponentName getDefaultHomeActivity(int userId) {
        ThreadComputer current = live();
        try {
            return current.mComputer.getDefaultHomeActivity(userId);
        } finally {
            current.release();
        }
    }
    public ComponentName getHomeActivitiesAsUser(List<ResolveInfo> allHomeCandidates,
            int userId) {
        ThreadComputer current = live();
        try {
            return current.mComputer.getHomeActivitiesAsUser(allHomeCandidates, userId);
        } finally {
            current.release();
        }
    }
    public CrossProfileDomainInfo getCrossProfileDomainPreferredLpr(Intent intent,
            String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags, int sourceUserId,
            int parentUserId) {
        ThreadComputer current = live();
        try {
            return current.mComputer.getCrossProfileDomainPreferredLpr(intent, resolvedType,
                    flags, sourceUserId, parentUserId);
        } finally {
            current.release();
        }
    }
    public Intent getHomeIntent() {
        ThreadComputer current = live();
        try {
            return current.mComputer.getHomeIntent();
        } finally {
            current.release();
        }
    }
    public List<CrossProfileIntentFilter> getMatchingCrossProfileIntentFilters(
            Intent intent, String resolvedType, int userId) {
        ThreadComputer current = live();
        try {
            return current.mComputer.getMatchingCrossProfileIntentFilters(intent, resolvedType,
                    userId);
        } finally {
            current.release();
        }
    }
    public List<ResolveInfo> applyPostResolutionFilter(
            @NonNull List<ResolveInfo> resolveInfos,
            String ephemeralPkgName, boolean allowDynamicSplits, int filterCallingUid,
            boolean resolveForStart, int userId, Intent intent) {
        ThreadComputer current = live();
        try {
            return current.mComputer.applyPostResolutionFilter(resolveInfos, ephemeralPkgName,
                    allowDynamicSplits, filterCallingUid, resolveForStart, userId, intent);
        } finally {
            current.release();
        }
    }
    public PackageInfo generatePackageInfo(PackageStateInternal ps,
            @PackageManager.PackageInfoFlagsBits long flags, int userId) {
        ThreadComputer current = live();
        try {
            return current.mComputer.generatePackageInfo(ps, flags, userId);
        } finally {
            current.release();
        }
    }
    public PackageInfo getPackageInfo(String packageName,
            @PackageManager.PackageInfoFlagsBits long flags, int userId) {
        ThreadComputer current = snapshot();
        try {
            return current.mComputer.getPackageInfo(packageName, flags, userId);
        } finally {
            current.release();
        }
    }
    public PackageInfo getPackageInfoInternal(String packageName, long versionCode,
            long flags, int filterCallingUid, int userId) {
        ThreadComputer current = live();
        try {
            return current.mComputer.getPackageInfoInternal(packageName, versionCode, flags,
                    filterCallingUid, userId);
        } finally {
            current.release();
        }
    }
    public PackageStateInternal getPackageStateInternal(String packageName) {
        ThreadComputer current = snapshot();
        try {
            return current.mComputer.getPackageStateInternal(packageName);
        } finally {
            current.release();
        }
    }
    public PackageStateInternal getPackageStateInternal(String packageName, int callingUid) {
        ThreadComputer current = live();
        try {
            return current.mComputer.getPackageStateInternal(packageName, callingUid);
        } finally {
            current.release();
        }
    }

    @Nullable
    public PackageState getPackageStateCopied(@NonNull String packageName) {
        ThreadComputer current = live();
        try {
            return current.mComputer.getPackageStateCopied(packageName);
        } finally {
            current.release();
        }
    }

    public ParceledListSlice<PackageInfo> getInstalledPackages(long flags, int userId) {
        ThreadComputer current = snapshot();
        try {
            return current.mComputer.getInstalledPackages(flags, userId);
        } finally {
            current.release();
        }
    }
    public ResolveInfo createForwardingResolveInfoUnchecked(WatchedIntentFilter filter,
            int sourceUserId, int targetUserId) {
        ThreadComputer current = live();
        try {
            return current.mComputer.createForwardingResolveInfoUnchecked(filter, sourceUserId,
                    targetUserId);
        } finally {
            current.release();
        }
    }
    public ServiceInfo getServiceInfo(ComponentName component,
            @PackageManager.ComponentInfoFlagsBits long flags, int userId) {
        ThreadComputer current = live();
        try {
            return current.mComputer.getServiceInfo(component, flags, userId);
        } finally {
            current.release();
        }
    }
    public SharedLibraryInfo getSharedLibraryInfo(String name, long version) {
        ThreadComputer current = snapshot();
        try {
            return current.mComputer.getSharedLibraryInfo(name, version);
        } finally {
            current.release();
        }
    }
    public SigningDetails getSigningDetails(@NonNull String packageName) {
        ThreadComputer current = snapshot();
        try {
            return current.mComputer.getSigningDetails(packageName);
        } finally {
            current.release();
        }
    }
    public SigningDetails getSigningDetails(int uid) {
        ThreadComputer current = snapshot();
        try {
            return current.mComputer.getSigningDetails(uid);
        } finally {
            current.release();
        }
    }
    public String getInstantAppPackageName(int callingUid) {
        ThreadComputer current = snapshot();
        try {
            return current.mComputer.getInstantAppPackageName(callingUid);
        } finally {
            current.release();
        }
    }
    public String resolveExternalPackageName(AndroidPackage pkg) {
        ThreadComputer current = live();
        try {
            return current.mComputer.resolveExternalPackageName(pkg);
        } finally {
            current.release();
        }
    }
    public String resolveInternalPackageNameLPr(String packageName, long versionCode) {
        ThreadComputer current = live();
        try {
            return current.mComputer.resolveInternalPackageNameLPr(packageName, versionCode);
        } finally {
            current.release();
        }
    }
    public String[] getPackagesForUid(int uid) {
        ThreadComputer current = snapshot();
        try {
            return current.mComputer.getPackagesForUid(uid);
        } finally {
            current.release();
        }
    }
    public UserInfo getProfileParent(int userId) {
        ThreadComputer current = live();
        try {
            return current.mComputer.getProfileParent(userId);
        } finally {
            current.release();
        }
    }
    public boolean canViewInstantApps(int callingUid, int userId) {
        ThreadComputer current = live();
        try {
            return current.mComputer.canViewInstantApps(callingUid, userId);
        } finally {
            current.release();
        }
    }
    public boolean filterAppAccess(AndroidPackage pkg, int callingUid, int userId) {
        ThreadComputer current = snapshot();
        try {
            return current.mComputer.filterAppAccess(pkg, callingUid, userId);
        } finally {
            current.release();
        }
    }
    public boolean filterAppAccess(String packageName, int callingUid, int userId) {
        ThreadComputer current = snapshot();
        try {
            return current.mComputer.filterAppAccess(packageName, callingUid, userId);
        } finally {
            current.release();
        }
    }
    public boolean filterAppAccess(int uid, int callingUid) {
        ThreadComputer current = snapshot();
        try {
            return current.mComputer.filterAppAccess(uid, callingUid);
        } finally {
            current.release();
        }
    }
    public boolean filterSharedLibPackage(@Nullable PackageStateInternal ps, int uid,
            int userId, @PackageManager.ComponentInfoFlagsBits long flags) {
        ThreadComputer current = live();
        try {
            return current.mComputer.filterSharedLibPackage(ps, uid, userId, flags);
        } finally {
            current.release();
        }
    }
    public boolean isCallerSameApp(String packageName, int uid) {
        ThreadComputer current = live();
        try {
            return current.mComputer.isCallerSameApp(packageName, uid);
        } finally {
            current.release();
        }
    }
    public boolean isComponentVisibleToInstantApp(@Nullable ComponentName component) {
        ThreadComputer current = live();
        try {
            return current.mComputer.isComponentVisibleToInstantApp(component);
        } finally {
            current.release();
        }
    }
    public boolean isComponentVisibleToInstantApp(@Nullable ComponentName component,
            @PackageManager.ComponentType int type) {
        ThreadComputer current = live();
        try {
            return current.mComputer.isComponentVisibleToInstantApp(component, type);
        } finally {
            current.release();
        }
    }
    public boolean isImplicitImageCaptureIntentAndNotSetByDpcLocked(Intent intent,
            int userId, String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags) {
        ThreadComputer current = live();
        try {
            return current.mComputer.isImplicitImageCaptureIntentAndNotSetByDpcLocked(intent,
                    userId, resolvedType, flags);
        } finally {
            current.release();
        }
    }
    public boolean isInstantApp(String packageName, int userId) {
        ThreadComputer current = snapshot();
        try {
            return current.mComputer.isInstantApp(packageName, userId);
        } finally {
            current.release();
        }
    }
    public boolean isInstantAppInternal(String packageName, @UserIdInt int userId,
            int callingUid) {
        ThreadComputer current = live();
        try {
            return current.mComputer.isInstantAppInternal(packageName, userId, callingUid);
        } finally {
            current.release();
        }
    }
    public boolean isSameProfileGroup(@UserIdInt int callerUserId,
            @UserIdInt int userId) {
        ThreadComputer current = live();
        try {
            return current.mComputer.isSameProfileGroup(callerUserId, userId);
        } finally {
            current.release();
        }
    }
    public boolean shouldFilterApplication(@NonNull SharedUserSetting sus,
            int callingUid, int userId) {
        ThreadComputer current = live();
        try {
            return current.mComputer.shouldFilterApplication(sus, callingUid, userId);
        } finally {
            current.release();
        }
    }
    public boolean shouldFilterApplication(@Nullable PackageStateInternal ps,
            int callingUid, @Nullable ComponentName component,
            @PackageManager.ComponentType int componentType, int userId) {
        ThreadComputer current = live();
        try {
            return current.mComputer.shouldFilterApplication(ps, callingUid, component,
                    componentType, userId);
        } finally {
            current.release();
        }
    }
    public boolean shouldFilterApplication(@Nullable PackageStateInternal ps,
            int callingUid, int userId) {
        ThreadComputer current = live();
        try {
            return current.mComputer.shouldFilterApplication(ps, callingUid, userId);
        } finally {
            current.release();
        }
    }
    public int checkUidPermission(String permName, int uid) {
        ThreadComputer current = snapshot();
        try {
            return current.mComputer.checkUidPermission(permName, uid);
        } finally {
            current.release();
        }
    }
    public int getPackageUidInternal(String packageName,
            @PackageManager.PackageInfoFlagsBits long flags, int userId, int callingUid) {
        ThreadComputer current = live();
        try {
            return current.mComputer.getPackageUidInternal(packageName, flags, userId,
                    callingUid);
        } finally {
            current.release();
        }
    }
    public long updateFlagsForApplication(long flags, int userId) {
        ThreadComputer current = live();
        try {
            return current.mComputer.updateFlagsForApplication(flags, userId);
        } finally {
            current.release();
        }
    }
    public long updateFlagsForComponent(long flags, int userId) {
        ThreadComputer current = live();
        try {
            return current.mComputer.updateFlagsForComponent(flags, userId);
        } finally {
            current.release();
        }
    }
    public long updateFlagsForPackage(long flags, int userId) {
        ThreadComputer current = live();
        try {
            return current.mComputer.updateFlagsForPackage(flags, userId);
        } finally {
            current.release();
        }
    }
    public long updateFlagsForResolve(long flags, int userId, int callingUid,
            boolean wantInstantApps, boolean isImplicitImageCaptureIntentAndNotSetByDpc) {
        ThreadComputer current = snapshot();
        try {
            return current.mComputer.updateFlagsForResolve(flags, userId, callingUid,
                    wantInstantApps, isImplicitImageCaptureIntentAndNotSetByDpc);
        } finally {
            current.release();
        }
    }
    public long updateFlagsForResolve(long flags, int userId, int callingUid,
            boolean wantInstantApps, boolean onlyExposedExplicitly,
            boolean isImplicitImageCaptureIntentAndNotSetByDpc) {
        ThreadComputer current = live();
        try {
            return current.mComputer.updateFlagsForResolve(flags, userId, callingUid,
                    wantInstantApps, onlyExposedExplicitly,
                    isImplicitImageCaptureIntentAndNotSetByDpc);
        } finally {
            current.release();
        }
    }
    public void dump(int type, FileDescriptor fd, PrintWriter pw, DumpState dumpState) {
        ThreadComputer current = live();
        try {
            current.mComputer.dump(type, fd, pw, dumpState);
        } finally {
            current.release();
        }
    }
    public void enforceCrossUserOrProfilePermission(int callingUid, @UserIdInt int userId,
            boolean requireFullPermission, boolean checkShell, String message) {
        ThreadComputer current = live();
        try {
            current.mComputer.enforceCrossUserOrProfilePermission(callingUid, userId,
                    requireFullPermission, checkShell, message);
        } finally {
            current.release();
        }
    }
    public void enforceCrossUserPermission(int callingUid, @UserIdInt int userId,
            boolean requireFullPermission, boolean checkShell, String message) {
        ThreadComputer current = live();
        try {
            current.mComputer.enforceCrossUserPermission(callingUid, userId,
                    requireFullPermission, checkShell, message);
        } finally {
            current.release();
        }
    }
    public void enforceCrossUserPermission(int callingUid, @UserIdInt int userId,
            boolean requireFullPermission, boolean checkShell,
            boolean requirePermissionWhenSameUser, String message) {
        ThreadComputer current = live();
        try {
            current.mComputer.enforceCrossUserPermission(callingUid, userId,
                    requireFullPermission, checkShell, requirePermissionWhenSameUser, message);
        } finally {
            current.release();
        }
    }
    public PackageManagerService.FindPreferredActivityBodyResult findPreferredActivityInternal(
            Intent intent, String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags,
            List<ResolveInfo> query, boolean always, boolean removeMatches, boolean debug,
            int userId, boolean queryMayBeFiltered) {
        ThreadComputer current = live();
        try {
            return current.mComputer.findPreferredActivityInternal(intent, resolvedType, flags,
                    query, always, removeMatches, debug, userId, queryMayBeFiltered);
        } finally {
            current.release();
        }
    }
    public ResolveInfo findPersistentPreferredActivityLP(Intent intent,
            String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags,
            List<ResolveInfo> query, boolean debug, int userId) {
        ThreadComputer current = live();
        try {
            return current.mComputer.findPersistentPreferredActivityLP(intent, resolvedType,
                    flags, query, debug, userId);
        } finally {
            current.release();
        }
    }

    @Override
    public String[] getAllAvailablePackageNames() {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getAllAvailablePackageNames();
        }
    }

    @Override
    public PreferredIntentResolver getPreferredActivities(int userId) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getPreferredActivities(userId);
        }
    }

    @NonNull
    @Override
    public ArrayMap<String, ? extends PackageStateInternal> getPackageStates() {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getPackageStates();
        }
    }

    @Nullable
    @Override
    public String getRenamedPackage(@NonNull String packageName) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getRenamedPackage(packageName);
        }
    }

    @NonNull
    @Override
    public ArraySet<String> getNotifyPackagesForReplacedReceived(@NonNull String[] packages) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getNotifyPackagesForReplacedReceived(packages);
        }
    }

    @Override
    public int getPackageStartability(boolean safeMode, @NonNull String packageName, int callingUid,
            @UserIdInt int userId) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getPackageStartability(safeMode, packageName, callingUid,
                    userId);
        }
    }

    @Override
    public boolean isPackageAvailable(String packageName, @UserIdInt int userId) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.isPackageAvailable(packageName, userId);
        }
    }

    @Override
    public String[] currentToCanonicalPackageNames(String[] names) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.currentToCanonicalPackageNames(names);
        }
    }

    @Override
    public String[] canonicalToCurrentPackageNames(String[] names) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.canonicalToCurrentPackageNames(names);
        }
    }

    @Override
    public int[] getPackageGids(@NonNull String packageName,
            @PackageManager.PackageInfoFlagsBits long flags, @UserIdInt int userId) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getPackageGids(packageName, flags, userId);
        }
    }

    @Override
    public int getTargetSdkVersion(@NonNull String packageName) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getTargetSdkVersion(packageName);
        }
    }

    @Override
    public boolean activitySupportsIntent(@NonNull ComponentName resolveComponentName,
            @NonNull ComponentName component, @NonNull Intent intent, String resolvedType) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.activitySupportsIntent(resolveComponentName, component, intent,
                    resolvedType);
        }
    }

    @Nullable
    @Override
    public ActivityInfo getReceiverInfo(@NonNull ComponentName component,
            @PackageManager.ComponentInfoFlagsBits long flags, @UserIdInt int userId) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getReceiverInfo(component, flags, userId);
        }
    }

    @Nullable
    @Override
    public ParceledListSlice<SharedLibraryInfo> getSharedLibraries(@NonNull String packageName,
            @PackageManager.PackageInfoFlagsBits long flags, @UserIdInt int userId) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getSharedLibraries(packageName, flags, userId);
        }
    }

    @Override
    public boolean canRequestPackageInstalls(@NonNull String packageName, int callingUid,
            @UserIdInt int userId, boolean throwIfPermNotDeclared) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.canRequestPackageInstalls(packageName, callingUid, userId,
                    throwIfPermNotDeclared);
        }
    }

    @Override
    public boolean isInstallDisabledForPackage(@NonNull String packageName, int uid,
            @UserIdInt int userId) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.isInstallDisabledForPackage(packageName, uid, userId);
        }
    }

    @Override
    public List<VersionedPackage> getPackagesUsingSharedLibrary(@NonNull SharedLibraryInfo libInfo,
            @PackageManager.PackageInfoFlagsBits long flags, int callingUid,
            @UserIdInt int userId) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getPackagesUsingSharedLibrary(libInfo, flags, callingUid,
                    userId);
        }
    }

    @Nullable
    @Override
    public ParceledListSlice<SharedLibraryInfo> getDeclaredSharedLibraries(
            @NonNull String packageName, @PackageManager.PackageInfoFlagsBits long flags,
            @UserIdInt int userId) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getDeclaredSharedLibraries(packageName, flags, userId);
        }
    }

    @Nullable
    @Override
    public ProviderInfo getProviderInfo(@NonNull ComponentName component,
            @PackageManager.ComponentInfoFlagsBits long flags, @UserIdInt int userId) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getProviderInfo(component, flags, userId);
        }
    }

    @Nullable
    @Override
    public String[] getSystemSharedLibraryNames() {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getSystemSharedLibraryNames();
        }
    }

    @Override
    public boolean isPackageStateAvailableAndVisible(@NonNull String packageName, int callingUid,
            @UserIdInt int userId) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.isPackageStateAvailableAndVisible(packageName, callingUid,
                    userId);
        }
    }

    @Override
    public int checkSignatures(@NonNull String pkg1,
            @NonNull String pkg2) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.checkSignatures(pkg1, pkg2);
        }
    }

    @Override
    public int checkUidSignatures(int uid1, int uid2) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.checkUidSignatures(uid1, uid2);
        }
    }

    @Override
    public boolean hasSigningCertificate(@NonNull String packageName, @NonNull byte[] certificate,
            int type) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.hasSigningCertificate(packageName, certificate, type);
        }
    }

    @Override
    public boolean hasUidSigningCertificate(int uid, @NonNull byte[] certificate, int type) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.hasUidSigningCertificate(uid, certificate, type);
        }
    }

    @Override
    public List<String> getAllPackages() {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getAllPackages();
        }
    }

    @Nullable
    @Override
    public String getNameForUid(int uid) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getNameForUid(uid);
        }
    }

    @Nullable
    @Override
    public String[] getNamesForUids(int[] uids) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getNamesForUids(uids);
        }
    }

    @Override
    public int getUidForSharedUser(@NonNull String sharedUserName) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getUidForSharedUser(sharedUserName);
        }
    }

    @Override
    public int getFlagsForUid(int uid) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getFlagsForUid(uid);
        }
    }

    @Override
    public int getPrivateFlagsForUid(int uid) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getPrivateFlagsForUid(uid);
        }
    }

    @Override
    public boolean isUidPrivileged(int uid) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.isUidPrivileged(uid);
        }
    }

    @NonNull
    @Override
    public String[] getAppOpPermissionPackages(@NonNull String permissionName) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getAppOpPermissionPackages(permissionName);
        }
    }

    @NonNull
    @Override
    public ParceledListSlice<PackageInfo> getPackagesHoldingPermissions(
            @NonNull String[] permissions, @PackageManager.PackageInfoFlagsBits long flags,
            @UserIdInt int userId) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getPackagesHoldingPermissions(permissions, flags, userId);
        }
    }

    @NonNull
    @Override
    public List<ApplicationInfo> getInstalledApplications(
            @PackageManager.ApplicationInfoFlagsBits long flags, @UserIdInt int userId,
            int callingUid) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getInstalledApplications(flags, userId, callingUid);
        }
    }

    @Nullable
    @Override
    public ProviderInfo resolveContentProvider(@NonNull String name,
            @PackageManager.ResolveInfoFlagsBits long flags, @UserIdInt int userId,
            int callingUid) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.resolveContentProvider(name, flags, userId, callingUid);
        }
    }

    @Nullable
    @Override
    public ProviderInfo getGrantImplicitAccessProviderInfo(int recipientUid,
            @NonNull String visibleAuthority) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getGrantImplicitAccessProviderInfo(recipientUid,
                    visibleAuthority);
        }
    }

    @Override
    public void querySyncProviders(boolean safeMode, @NonNull List<String> outNames,
            @NonNull List<ProviderInfo> outInfo) {
        try (ThreadComputer current = snapshot()) {
            current.mComputer.querySyncProviders(safeMode, outNames, outInfo);
        }
    }

    @NonNull
    @Override
    public ParceledListSlice<ProviderInfo> queryContentProviders(@Nullable String processName,
            int uid, @PackageManager.ComponentInfoFlagsBits long flags,
            @Nullable String metaDataKey) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.queryContentProviders(processName, uid, flags, metaDataKey);
        }
    }

    @Nullable
    @Override
    public InstrumentationInfo getInstrumentationInfo(@NonNull ComponentName component, int flags) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getInstrumentationInfo(component, flags);
        }
    }

    @NonNull
    @Override
    public ParceledListSlice<InstrumentationInfo> queryInstrumentation(
            @NonNull String targetPackage, int flags) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.queryInstrumentation(targetPackage, flags);
        }
    }

    @NonNull
    @Override
    public List<PackageStateInternal> findSharedNonSystemLibraries(
            @NonNull PackageStateInternal pkgSetting) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.findSharedNonSystemLibraries(pkgSetting);
        }
    }

    @Override
    public boolean getApplicationHiddenSettingAsUser(@NonNull String packageName,
            @UserIdInt int userId) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getApplicationHiddenSettingAsUser(packageName, userId);
        }
    }

    @Override
    public boolean isPackageSuspendedForUser(@NonNull String packageName, @UserIdInt int userId) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.isPackageSuspendedForUser(packageName, userId);
        }
    }

    @Override
    public boolean isSuspendingAnyPackages(@NonNull String suspendingPackage,
            @UserIdInt int userId) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.isSuspendingAnyPackages(suspendingPackage, userId);
        }
    }

    @NonNull
    @Override
    public ParceledListSlice<IntentFilter> getAllIntentFilters(@NonNull String packageName) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getAllIntentFilters(packageName);
        }
    }

    @Override
    public boolean getBlockUninstallForUser(@NonNull String packageName, @UserIdInt int userId) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getBlockUninstallForUser(packageName, userId);
        }
    }

    @Nullable
    @Override
    public SparseArray<int[]> getBroadcastAllowList(@NonNull String packageName,
            @UserIdInt int[] userIds, boolean isInstantApp) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getBroadcastAllowList(packageName, userIds, isInstantApp);
        }
    }

    @Nullable
    @Override
    public String getInstallerPackageName(@NonNull String packageName) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getInstallerPackageName(packageName);
        }
    }

    @Nullable
    @Override
    public InstallSourceInfo getInstallSourceInfo(@NonNull String packageName) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getInstallSourceInfo(packageName);
        }
    }

    @Override
    public int getApplicationEnabledSetting(@NonNull String packageName, @UserIdInt int userId) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getApplicationEnabledSetting(packageName, userId);
        }
    }

    @Override
    public int getComponentEnabledSetting(@NonNull ComponentName component, int callingUid,
            @UserIdInt int userId) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getComponentEnabledSetting(component, callingUid, userId);
        }
    }

    @Override
    public int getComponentEnabledSettingInternal(@NonNull ComponentName component, int callingUid,
            @UserIdInt int userId) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getComponentEnabledSettingInternal(
                    component, callingUid, userId);
        }
    }

    @Override
    public boolean isComponentEffectivelyEnabled(@NonNull ComponentInfo componentInfo,
            @UserIdInt int userId) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.isComponentEffectivelyEnabled(componentInfo, userId);
        }
    }

    @Nullable
    @Override
    public KeySet getKeySetByAlias(@NonNull String packageName, @NonNull String alias) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getKeySetByAlias(packageName, alias);
        }
    }

    @Nullable
    @Override
    public KeySet getSigningKeySet(@NonNull String packageName) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getSigningKeySet(packageName);
        }
    }

    @Override
    public boolean isPackageSignedByKeySet(@NonNull String packageName, @NonNull KeySet ks) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.isPackageSignedByKeySet(packageName, ks);
        }
    }

    @Override
    public boolean isPackageSignedByKeySetExactly(@NonNull String packageName, @NonNull KeySet ks) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.isPackageSignedByKeySetExactly(packageName, ks);
        }
    }

    @Nullable
    @Override
    public int[] getVisibilityAllowList(@NonNull String packageName, @UserIdInt int userId) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getVisibilityAllowList(packageName, userId);
        }
    }

    @Override
    public boolean canQueryPackage(int callingUid, @Nullable String targetPackageName) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.canQueryPackage(callingUid, targetPackageName);
        }
    }

    @Override
    public int getPackageUid(@NonNull String packageName,
            @PackageManager.PackageInfoFlagsBits long flags, @UserIdInt int userId) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getPackageUid(packageName, flags, userId);
        }
    }

    @Override
    public boolean canAccessComponent(int callingUid, @NonNull ComponentName component,
            @UserIdInt int userId) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.canAccessComponent(callingUid, component, userId);
        }
    }

    @Override
    public boolean isCallerInstallerOfRecord(@NonNull AndroidPackage pkg, int callingUid) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.isCallerInstallerOfRecord(pkg, callingUid);
        }
    }

    @Override
    public int getInstallReason(@NonNull String packageName, @UserIdInt int userId) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getInstallReason(packageName, userId);
        }
    }

    @Override
    public boolean canPackageQuery(@NonNull String sourcePackageName,
            @NonNull String targetPackageName, @UserIdInt int userId) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.canPackageQuery(sourcePackageName, targetPackageName, userId);
        }
    }

    @Override
    public boolean canForwardTo(@NonNull Intent intent, @Nullable String resolvedType,
            @UserIdInt int sourceUserId, @UserIdInt int targetUserId) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.canForwardTo(intent, resolvedType, sourceUserId, targetUserId);
        }
    }

    @NonNull
    @Override
    public List<ApplicationInfo> getPersistentApplications(boolean safeMode, int flags) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getPersistentApplications(safeMode, flags);
        }
    }

    @NonNull
    @Override
    public SparseArray<String> getAppsWithSharedUserIds() {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getAppsWithSharedUserIds();
        }
    }

    @NonNull
    @Override
    public String[] getSharedUserPackagesForPackage(@NonNull String packageName,
            @UserIdInt int userId) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getSharedUserPackagesForPackage(packageName, userId);
        }
    }

    @NonNull
    @Override
    public Set<String> getUnusedPackages(long downgradeTimeThresholdMillis) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getUnusedPackages(downgradeTimeThresholdMillis);
        }
    }

    @Nullable
    @Override
    public CharSequence getHarmfulAppWarning(@NonNull String packageName, @UserIdInt int userId) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getHarmfulAppWarning(packageName, userId);
        }
    }

    @NonNull
    @Override
    public String[] filterOnlySystemPackages(@Nullable String... pkgNames) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.filterOnlySystemPackages(pkgNames);
        }
    }

    @NonNull
    @Override
    public List<AndroidPackage> getPackagesForAppId(int appId) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getPackagesForAppId(appId);
        }
    }

    @Override
    public int getUidTargetSdkVersion(int uid) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getUidTargetSdkVersion(uid);
        }
    }

    @Nullable
    @Override
    public ArrayMap<String, ProcessInfo> getProcessesForUid(int uid) {
        try (ThreadComputer current = snapshot()) {
            return current.mComputer.getProcessesForUid(uid);
        }
    }
}
