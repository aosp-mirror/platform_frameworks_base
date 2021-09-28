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
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.SigningDetails;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageState;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

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
        return mService.mAndroidApplication;
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
            Intent intent, String resolvedType, int flags, int filterCallingUid, int userId,
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
    public PackageSetting getPackageSettingInternal(String packageName, int callingUid) {
        synchronized (mLock) {
            return super.getPackageSettingInternal(packageName, callingUid);
        }
    }

    @Nullable
    public PackageState getPackageState(@NonNull String packageName) {
        synchronized (mLock) {
            return super.getPackageState(packageName);
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
}
