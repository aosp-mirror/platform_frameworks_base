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
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.SigningDetails;
import android.content.pm.UserInfo;

import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageState;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
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
            String resolvedType, int flags,
            @PackageManagerInternal.PrivateResolveFlags int privateResolveFlags,
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
            String resolvedType, int flags, int userId) {
        ThreadComputer current = snapshot();
        try {
            return current.mComputer.queryIntentActivitiesInternal(intent, resolvedType, flags,
                    userId);
        } finally {
            current.release();
        }
    }
    public @NonNull List<ResolveInfo> queryIntentServicesInternal(Intent intent,
            String resolvedType, int flags, int userId, int callingUid,
            boolean includeInstantApps) {
        ThreadComputer current = snapshot();
        try {
            return current.mComputer.queryIntentServicesInternal(intent, resolvedType, flags,
                    userId, callingUid, includeInstantApps);
        } finally {
            current.release();
        }
    }
    public @NonNull QueryIntentActivitiesResult queryIntentActivitiesInternalBody(
            Intent intent,
            String resolvedType, int flags, int filterCallingUid, int userId,
            boolean resolveForStart, boolean allowDynamicSplits, String pkgName,
            String instantAppPkgName) {
        ThreadComputer current = live();
        try {
            return current.mComputer.queryIntentActivitiesInternalBody(intent, resolvedType,
                    flags, filterCallingUid, userId, resolveForStart, allowDynamicSplits,
                    pkgName, instantAppPkgName);
        } finally {
            current.release();
        }
    }
    public ActivityInfo getActivityInfo(ComponentName component, int flags, int userId) {
        ThreadComputer current = snapshot();
        try {
            return current.mComputer.getActivityInfo(component, flags, userId);
        } finally {
            current.release();
        }
    }
    public ActivityInfo getActivityInfoInternal(ComponentName component, int flags,
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
    public ApplicationInfo generateApplicationInfoFromSettingsLPw(String packageName,
            int flags, int filterCallingUid, int userId) {
        ThreadComputer current = live();
        try {
            return current.mComputer.generateApplicationInfoFromSettingsLPw(packageName, flags,
                    filterCallingUid, userId);
        } finally {
            current.release();
        }
    }
    public ApplicationInfo getApplicationInfo(String packageName, int flags, int userId) {
        ThreadComputer current = snapshot();
        try {
            return current.mComputer.getApplicationInfo(packageName, flags, userId);
        } finally {
            current.release();
        }
    }
    public ApplicationInfo getApplicationInfoInternal(String packageName, int flags,
            int filterCallingUid, int userId) {
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
            String resolvedType, int flags, int sourceUserId, int parentUserId) {
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
    public PackageInfo generatePackageInfo(PackageSetting ps, int flags, int userId) {
        ThreadComputer current = live();
        try {
            return current.mComputer.generatePackageInfo(ps, flags, userId);
        } finally {
            current.release();
        }
    }
    public PackageInfo getPackageInfo(String packageName, int flags, int userId) {
        ThreadComputer current = snapshot();
        try {
            return current.mComputer.getPackageInfo(packageName, flags, userId);
        } finally {
            current.release();
        }
    }
    public PackageInfo getPackageInfoInternal(String packageName, long versionCode,
            int flags, int filterCallingUid, int userId) {
        ThreadComputer current = live();
        try {
            return current.mComputer.getPackageInfoInternal(packageName, versionCode, flags,
                    filterCallingUid, userId);
        } finally {
            current.release();
        }
    }
    public PackageSetting getPackageSetting(String packageName) {
        ThreadComputer current = snapshot();
        try {
            return current.mComputer.getPackageSetting(packageName);
        } finally {
            current.release();
        }
    }
    public PackageSetting getPackageSettingInternal(String packageName, int callingUid) {
        ThreadComputer current = live();
        try {
            return current.mComputer.getPackageSettingInternal(packageName, callingUid);
        } finally {
            current.release();
        }
    }

    @Nullable
    public PackageState getPackageState(@NonNull String packageName) {
        ThreadComputer current = live();
        try {
            return current.mComputer.getPackageState(packageName);
        } finally {
            current.release();
        }
    }

    public ParceledListSlice<PackageInfo> getInstalledPackages(int flags, int userId) {
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
    public ServiceInfo getServiceInfo(ComponentName component, int flags, int userId) {
        ThreadComputer current = live();
        try {
            return current.mComputer.getServiceInfo(component, flags, userId);
        } finally {
            current.release();
        }
    }
    public SharedLibraryInfo getSharedLibraryInfoLPr(String name, long version) {
        ThreadComputer current = live();
        try {
            return current.mComputer.getSharedLibraryInfoLPr(name, version);
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
    public String resolveExternalPackageNameLPr(AndroidPackage pkg) {
        ThreadComputer current = live();
        try {
            return current.mComputer.resolveExternalPackageNameLPr(pkg);
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
    public boolean filterSharedLibPackageLPr(@Nullable PackageSetting ps, int uid,
            int userId, int flags) {
        ThreadComputer current = live();
        try {
            return current.mComputer.filterSharedLibPackageLPr(ps, uid, userId, flags);
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
            int userId, String resolvedType, int flags) {
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
    public boolean shouldFilterApplicationLocked(@NonNull SharedUserSetting sus,
            int callingUid, int userId) {
        ThreadComputer current = live();
        try {
            return current.mComputer.shouldFilterApplicationLocked(sus, callingUid, userId);
        } finally {
            current.release();
        }
    }
    public boolean shouldFilterApplicationLocked(@Nullable PackageSetting ps,
            int callingUid, @Nullable ComponentName component,
            @PackageManager.ComponentType int componentType, int userId) {
        ThreadComputer current = live();
        try {
            return current.mComputer.shouldFilterApplicationLocked(ps, callingUid, component,
                    componentType, userId);
        } finally {
            current.release();
        }
    }
    public boolean shouldFilterApplicationLocked(@Nullable PackageSetting ps,
            int callingUid, int userId) {
        ThreadComputer current = live();
        try {
            return current.mComputer.shouldFilterApplicationLocked(ps, callingUid, userId);
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
    public int getPackageUidInternal(String packageName, int flags, int userId,
            int callingUid) {
        ThreadComputer current = live();
        try {
            return current.mComputer.getPackageUidInternal(packageName, flags, userId,
                    callingUid);
        } finally {
            current.release();
        }
    }
    public int updateFlagsForApplication(int flags, int userId) {
        ThreadComputer current = live();
        try {
            return current.mComputer.updateFlagsForApplication(flags, userId);
        } finally {
            current.release();
        }
    }
    public int updateFlagsForComponent(int flags, int userId) {
        ThreadComputer current = live();
        try {
            return current.mComputer.updateFlagsForComponent(flags, userId);
        } finally {
            current.release();
        }
    }
    public int updateFlagsForPackage(int flags, int userId) {
        ThreadComputer current = live();
        try {
            return current.mComputer.updateFlagsForPackage(flags, userId);
        } finally {
            current.release();
        }
    }
    public int updateFlagsForResolve(int flags, int userId, int callingUid,
            boolean wantInstantApps, boolean isImplicitImageCaptureIntentAndNotSetByDpc) {
        ThreadComputer current = live();
        try {
            return current.mComputer.updateFlagsForResolve(flags, userId, callingUid,
                    wantInstantApps, isImplicitImageCaptureIntentAndNotSetByDpc);
        } finally {
            current.release();
        }
    }
    public int updateFlagsForResolve(int flags, int userId, int callingUid,
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
            Intent intent, String resolvedType, int flags, List<ResolveInfo> query, boolean always,
            boolean removeMatches, boolean debug, int userId, boolean queryMayBeFiltered) {
        ThreadComputer current = live();
        try {
            return current.mComputer.findPreferredActivityInternal(intent, resolvedType, flags,
                    query, always, removeMatches, debug, userId, queryMayBeFiltered);
        } finally {
            current.release();
        }
    }
    public ResolveInfo findPersistentPreferredActivityLP(Intent intent,
            String resolvedType, int flags, List<ResolveInfo> query, boolean debug,
            int userId) {
        ThreadComputer current = live();
        try {
            return current.mComputer.findPersistentPreferredActivityLP(intent, resolvedType,
                    flags, query, debug, userId);
        } finally {
            current.release();
        }
    }
}
