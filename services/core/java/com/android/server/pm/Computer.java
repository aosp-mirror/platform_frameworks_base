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

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageState;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

/**
 * A {@link Computer} provides a set of functions that can operate on live data or snapshot
 * data.  At this time, the {@link Computer} is implemented by the
 * {@link ComputerEngine}, which is in turn extended by {@link ComputerLocked}.
 *
 * New functions must be added carefully.
 * <ol>
 * <li> New functions must be true functions with respect to data collected in a
 * {@link PackageManagerService.Snapshot}.  Such data may never be modified from inside a {@link Computer}
 * function.
 * </li>
 *
 * <li> A new function must be implemented in {@link ComputerEngine}.
 * </li>
 *
 * <li> A new function must be overridden in {@link ComputerLocked} if the function
 * cannot safely access live data without holding the PackageManagerService lock.  The
 * form of the {@link ComputerLocked} function must be a single call to the
 * {@link ComputerEngine} implementation, wrapped in a <code>synchronized</code>
 * block.  Functions in {@link ComputerLocked} should never include any other code.
 * </li>
 *
 * Care must be taken when deciding if a function should be overridden in
 * {@link ComputerLocked}.  The complex lock relationships of PackageManagerService
 * and other managers (like PermissionManager) mean deadlock is possible.  On the
 * other hand, not overriding in {@link ComputerLocked} may leave a function walking
 * unstable data.
 *
 * To coax developers to consider such issues carefully, all methods in
 * {@link Computer} must be annotated with <code>@LiveImplementation(override =
 * MANDATORY)</code> or <code>LiveImplementation(locked = NOT_ALLOWED)</code>.  A unit
 * test verifies the annotation and that the annotation corresponds to the code in
 * {@link ComputerEngine} and {@link ComputerLocked}.
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
public interface Computer {
    /**
     * Every method must be annotated.
     */
    @Target({ ElementType.METHOD })
    @Retention(RetentionPolicy.RUNTIME)
    @interface LiveImplementation {
        // A Computer method must be annotated with one of the following values:
        //   MANDATORY - the method must be overridden in ComputerEngineLive.  The
        //     format of the override is a call to the super method, wrapped in a
        //     synchronization block.
        //   NOT_ALLOWED - the method may not appear in the live computer.  It must
        //     be final in the ComputerEngine.
        int MANDATORY = 1;
        int NOT_ALLOWED = 2;
        int override() default MANDATORY;
        String rationale() default "";
    }

    /**
     * Administrative statistics: record that the snapshot has been used.  Every call
     * to use() increments the usage counter.
     */
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    default void use() {
    }
    /**
     * Fetch the snapshot usage counter.
     * @return The number of times this snapshot was used.
     */
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    default int getUsed() {
        return 0;
    }
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    @NonNull List<ResolveInfo> queryIntentActivitiesInternal(Intent intent, String resolvedType,
            int flags, @PackageManagerInternal.PrivateResolveFlags int privateResolveFlags,
            int filterCallingUid, int userId, boolean resolveForStart, boolean allowDynamicSplits);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    @NonNull List<ResolveInfo> queryIntentActivitiesInternal(Intent intent, String resolvedType,
            int flags, int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    @NonNull List<ResolveInfo> queryIntentServicesInternal(Intent intent, String resolvedType,
            int flags, int userId, int callingUid, boolean includeInstantApps);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.MANDATORY)
    @NonNull QueryIntentActivitiesResult queryIntentActivitiesInternalBody(Intent intent,
            String resolvedType, int flags, int filterCallingUid, int userId,
            boolean resolveForStart, boolean allowDynamicSplits, String pkgName,
            String instantAppPkgName);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    ActivityInfo getActivityInfo(ComponentName component, int flags, int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    ActivityInfo getActivityInfoInternal(ComponentName component, int flags,
            int filterCallingUid, int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.MANDATORY)
    AndroidPackage getPackage(String packageName);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.MANDATORY)
    AndroidPackage getPackage(int uid);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    ApplicationInfo generateApplicationInfoFromSettingsLPw(String packageName, int flags,
            int filterCallingUid, int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    ApplicationInfo getApplicationInfo(String packageName, int flags, int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    ApplicationInfo getApplicationInfoInternal(String packageName, int flags,
            int filterCallingUid, int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    ComponentName getDefaultHomeActivity(int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    ComponentName getHomeActivitiesAsUser(List<ResolveInfo> allHomeCandidates, int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    CrossProfileDomainInfo getCrossProfileDomainPreferredLpr(Intent intent, String resolvedType,
            int flags, int sourceUserId, int parentUserId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    Intent getHomeIntent();
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    List<CrossProfileIntentFilter> getMatchingCrossProfileIntentFilters(Intent intent,
            String resolvedType, int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    List<ResolveInfo> applyPostResolutionFilter(@NonNull List<ResolveInfo> resolveInfos,
            String ephemeralPkgName, boolean allowDynamicSplits, int filterCallingUid,
            boolean resolveForStart, int userId, Intent intent);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    PackageInfo generatePackageInfo(PackageSetting ps, int flags, int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    PackageInfo getPackageInfo(String packageName, int flags, int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    PackageInfo getPackageInfoInternal(String packageName, long versionCode, int flags,
            int filterCallingUid, int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    PackageSetting getPackageSetting(String packageName);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.MANDATORY)
    PackageSetting getPackageSettingInternal(String packageName, int callingUid);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.MANDATORY)
    @Nullable PackageState getPackageState(@NonNull String packageName);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    ParceledListSlice<PackageInfo> getInstalledPackages(int flags, int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    ResolveInfo createForwardingResolveInfoUnchecked(WatchedIntentFilter filter,
            int sourceUserId, int targetUserId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    ServiceInfo getServiceInfo(ComponentName component, int flags, int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    SharedLibraryInfo getSharedLibraryInfoLPr(String name, long version);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.MANDATORY)
    String getInstantAppPackageName(int callingUid);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    String resolveExternalPackageNameLPr(AndroidPackage pkg);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    String resolveInternalPackageNameLPr(String packageName, long versionCode);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    String[] getPackagesForUid(int uid);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    UserInfo getProfileParent(int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    boolean canViewInstantApps(int callingUid, int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    boolean filterSharedLibPackageLPr(@Nullable PackageSetting ps, int uid, int userId,
            int flags);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    boolean isCallerSameApp(String packageName, int uid);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    boolean isComponentVisibleToInstantApp(@Nullable ComponentName component);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    boolean isComponentVisibleToInstantApp(@Nullable ComponentName component,
            @PackageManager.ComponentType int type);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    boolean isImplicitImageCaptureIntentAndNotSetByDpcLocked(Intent intent, int userId,
            String resolvedType, int flags);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    boolean isInstantApp(String packageName, int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    boolean isInstantAppInternal(String packageName, @UserIdInt int userId, int callingUid);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    boolean isSameProfileGroup(@UserIdInt int callerUserId, @UserIdInt int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    boolean shouldFilterApplicationLocked(@Nullable PackageSetting ps, int callingUid,
            @Nullable ComponentName component, @PackageManager.ComponentType int componentType,
            int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    boolean shouldFilterApplicationLocked(@Nullable PackageSetting ps, int callingUid,
            int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    boolean shouldFilterApplicationLocked(@NonNull SharedUserSetting sus, int callingUid,
            int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    int checkUidPermission(String permName, int uid);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.MANDATORY)
    int getPackageUidInternal(String packageName, int flags, int userId, int callingUid);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    int updateFlagsForApplication(int flags, int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    int updateFlagsForComponent(int flags, int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    int updateFlagsForPackage(int flags, int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    int updateFlagsForResolve(int flags, int userId, int callingUid, boolean wantInstantApps,
            boolean isImplicitImageCaptureIntentAndNotSetByDpc);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    int updateFlagsForResolve(int flags, int userId, int callingUid, boolean wantInstantApps,
            boolean onlyExposedExplicitly, boolean isImplicitImageCaptureIntentAndNotSetByDpc);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    void enforceCrossUserOrProfilePermission(int callingUid, @UserIdInt int userId,
            boolean requireFullPermission, boolean checkShell, String message);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    void enforceCrossUserPermission(int callingUid, @UserIdInt int userId,
            boolean requireFullPermission, boolean checkShell, String message);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    void enforceCrossUserPermission(int callingUid, @UserIdInt int userId,
            boolean requireFullPermission, boolean checkShell,
            boolean requirePermissionWhenSameUser, String message);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.MANDATORY)
    SigningDetails getSigningDetails(@NonNull String packageName);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.MANDATORY)
    SigningDetails getSigningDetails(int uid);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.MANDATORY)
    boolean filterAppAccess(AndroidPackage pkg, int callingUid, int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.MANDATORY)
    boolean filterAppAccess(String packageName, int callingUid, int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.MANDATORY)
    boolean filterAppAccess(int uid, int callingUid);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.MANDATORY)
    void dump(int type, FileDescriptor fd, PrintWriter pw, DumpState dumpState);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    PackageManagerService.FindPreferredActivityBodyResult findPreferredActivityInternal(
            Intent intent, String resolvedType, int flags, List<ResolveInfo> query, boolean always,
            boolean removeMatches, boolean debug, int userId, boolean queryMayBeFiltered);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    ResolveInfo findPersistentPreferredActivityLP(Intent intent, String resolvedType, int flags,
            List<ResolveInfo> query, boolean debug, int userId);
}
