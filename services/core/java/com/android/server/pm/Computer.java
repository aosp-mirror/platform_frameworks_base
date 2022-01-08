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

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageState;
import com.android.server.pm.pkg.PackageStateInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Set;

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
            @PackageManager.ResolveInfoFlagsBits long flags,
            @PackageManagerInternal.PrivateResolveFlags long privateResolveFlags,
            int filterCallingUid, int userId, boolean resolveForStart, boolean allowDynamicSplits);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    @NonNull List<ResolveInfo> queryIntentActivitiesInternal(Intent intent, String resolvedType,
            long flags, int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    @NonNull List<ResolveInfo> queryIntentServicesInternal(Intent intent, String resolvedType,
            long flags, int userId, int callingUid, boolean includeInstantApps);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.MANDATORY)
    @NonNull QueryIntentActivitiesResult queryIntentActivitiesInternalBody(Intent intent,
            String resolvedType, long flags, int filterCallingUid, int userId,
            boolean resolveForStart, boolean allowDynamicSplits, String pkgName,
            String instantAppPkgName);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    ActivityInfo getActivityInfo(ComponentName component, long flags, int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    ActivityInfo getActivityInfoInternal(ComponentName component, long flags,
            int filterCallingUid, int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.MANDATORY)
    AndroidPackage getPackage(String packageName);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.MANDATORY)
    AndroidPackage getPackage(int uid);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    ApplicationInfo generateApplicationInfoFromSettings(String packageName, long flags,
            int filterCallingUid, int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    ApplicationInfo getApplicationInfo(String packageName, long flags, int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    ApplicationInfo getApplicationInfoInternal(String packageName, long flags,
            int filterCallingUid, int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    ComponentName getDefaultHomeActivity(int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    ComponentName getHomeActivitiesAsUser(List<ResolveInfo> allHomeCandidates, int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    CrossProfileDomainInfo getCrossProfileDomainPreferredLpr(Intent intent, String resolvedType,
            long flags, int sourceUserId, int parentUserId);
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
    PackageInfo generatePackageInfo(PackageStateInternal ps, long flags, int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    PackageInfo getPackageInfo(String packageName, long flags, int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    PackageInfo getPackageInfoInternal(String packageName, long versionCode, long flags,
            int filterCallingUid, int userId);

    /**
     * @return package names of all available {@link AndroidPackage} instances. This means any
     * known {@link PackageState} instances without a {@link PackageState#getAndroidPackage()}
     * will not be represented.
     */
    @Computer.LiveImplementation(override = Computer.LiveImplementation.MANDATORY)
    String[] getAllAvailablePackageNames();

    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    PackageStateInternal getPackageStateInternal(String packageName);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.MANDATORY)
    PackageStateInternal getPackageStateInternal(String packageName, int callingUid);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.MANDATORY)
    @Nullable PackageState getPackageStateCopied(@NonNull String packageName);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    ParceledListSlice<PackageInfo> getInstalledPackages(long flags, int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    ResolveInfo createForwardingResolveInfoUnchecked(WatchedIntentFilter filter,
            int sourceUserId, int targetUserId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    ServiceInfo getServiceInfo(ComponentName component, long flags, int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    SharedLibraryInfo getSharedLibraryInfo(String name, long version);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.MANDATORY)
    String getInstantAppPackageName(int callingUid);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    String resolveExternalPackageName(AndroidPackage pkg);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    String resolveInternalPackageNameLPr(String packageName, long versionCode);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    String[] getPackagesForUid(int uid);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    UserInfo getProfileParent(int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    boolean canViewInstantApps(int callingUid, int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    boolean filterSharedLibPackage(@Nullable PackageStateInternal ps, int uid, int userId,
            long flags);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    boolean isCallerSameApp(String packageName, int uid);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    boolean isComponentVisibleToInstantApp(@Nullable ComponentName component);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    boolean isComponentVisibleToInstantApp(@Nullable ComponentName component,
            @PackageManager.ComponentType int type);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    boolean isImplicitImageCaptureIntentAndNotSetByDpcLocked(Intent intent, int userId,
            String resolvedType, long flags);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    boolean isInstantApp(String packageName, int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    boolean isInstantAppInternal(String packageName, @UserIdInt int userId, int callingUid);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    boolean isSameProfileGroup(@UserIdInt int callerUserId, @UserIdInt int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    boolean shouldFilterApplication(@Nullable PackageStateInternal ps, int callingUid,
            @Nullable ComponentName component, @PackageManager.ComponentType int componentType,
            int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    boolean shouldFilterApplication(@Nullable PackageStateInternal ps, int callingUid,
            int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    boolean shouldFilterApplication(@NonNull SharedUserSetting sus, int callingUid,
            int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    int checkUidPermission(String permName, int uid);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.MANDATORY)
    int getPackageUidInternal(String packageName, long flags, int userId, int callingUid);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    long updateFlagsForApplication(long flags, int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    long updateFlagsForComponent(long flags, int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    long updateFlagsForPackage(long flags, int userId);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    long updateFlagsForResolve(long flags, int userId, int callingUid, boolean wantInstantApps,
            boolean isImplicitImageCaptureIntentAndNotSetByDpc);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    long updateFlagsForResolve(long flags, int userId, int callingUid, boolean wantInstantApps,
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
            Intent intent, String resolvedType, long flags, List<ResolveInfo> query, boolean always,
            boolean removeMatches, boolean debug, int userId, boolean queryMayBeFiltered);
    @Computer.LiveImplementation(override = Computer.LiveImplementation.NOT_ALLOWED)
    ResolveInfo findPersistentPreferredActivityLP(Intent intent, String resolvedType, long flags,
            List<ResolveInfo> query, boolean debug, int userId);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    PreferredIntentResolver getPreferredActivities(@UserIdInt int userId);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @NonNull
    ArrayMap<String, ? extends PackageStateInternal> getPackageStates();

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @Nullable
    String getRenamedPackage(@NonNull String packageName);

    /**
     * @return set of packages to notify
     */
    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @NonNull
    ArraySet<String> getNotifyPackagesForReplacedReceived(@NonNull String[] packages);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @PackageManagerService.PackageStartability
    int getPackageStartability(boolean safeMode, @NonNull String packageName, int callingUid,
            @UserIdInt int userId);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    boolean isPackageAvailable(String packageName, @UserIdInt int userId);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @NonNull
    String[] currentToCanonicalPackageNames(@NonNull String[] names);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @NonNull
    String[] canonicalToCurrentPackageNames(@NonNull String[] names);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @NonNull
    int[] getPackageGids(@NonNull String packageName,
            @PackageManager.PackageInfoFlagsBits long flags, @UserIdInt int userId);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    int getTargetSdkVersion(@NonNull String packageName);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    boolean activitySupportsIntent(@NonNull ComponentName resolveComponentName,
            @NonNull ComponentName component, @NonNull Intent intent, String resolvedType);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @Nullable
    ActivityInfo getReceiverInfo(@NonNull ComponentName component,
            @PackageManager.ComponentInfoFlagsBits long flags, @UserIdInt int userId);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @Nullable
    ParceledListSlice<SharedLibraryInfo> getSharedLibraries(@NonNull String packageName,
            @PackageManager.PackageInfoFlagsBits long flags, @UserIdInt int userId);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    boolean canRequestPackageInstalls(@NonNull String packageName, int callingUid,
            int userId, boolean throwIfPermNotDeclared);

    @Computer.LiveImplementation(override = LiveImplementation.NOT_ALLOWED)
    boolean isInstallDisabledForPackage(@NonNull String packageName, int uid,
            @UserIdInt int userId);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @Nullable
    List<VersionedPackage> getPackagesUsingSharedLibrary(@NonNull SharedLibraryInfo libInfo,
            @PackageManager.PackageInfoFlagsBits long flags, int callingUid, @UserIdInt int userId);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @Nullable
    ParceledListSlice<SharedLibraryInfo> getDeclaredSharedLibraries(
            @NonNull String packageName, @PackageManager.PackageInfoFlagsBits long flags,
            @UserIdInt int userId);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @Nullable
    ProviderInfo getProviderInfo(@NonNull ComponentName component,
            @PackageManager.ComponentInfoFlagsBits long flags, @UserIdInt int userId);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @Nullable
    String[] getSystemSharedLibraryNames();

    /**
     * @return if the given package has a state and isn't filtered by visibility. Provides no
     * guarantee that the package is in any usable state.
     */
    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    boolean isPackageStateAvailableAndVisible(@NonNull String packageName, int callingUid,
            @UserIdInt int userId);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    int checkSignatures(@NonNull String pkg1, @NonNull String pkg2);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    int checkUidSignatures(int uid1, int uid2);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    boolean hasSigningCertificate(@NonNull String packageName, @NonNull byte[] certificate,
            @PackageManager.CertificateInputType int type);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    boolean hasUidSigningCertificate(int uid, @NonNull byte[] certificate,
            @PackageManager.CertificateInputType int type);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @NonNull
    List<String> getAllPackages();

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @Nullable
    String getNameForUid(int uid);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @Nullable
    String[] getNamesForUids(@NonNull int[] uids);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    int getUidForSharedUser(@NonNull String sharedUserName);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    int getFlagsForUid(int uid);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    int getPrivateFlagsForUid(int uid);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    boolean isUidPrivileged(int uid);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @NonNull
    String[] getAppOpPermissionPackages(@NonNull String permissionName);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @NonNull
    ParceledListSlice<PackageInfo> getPackagesHoldingPermissions(@NonNull String[] permissions,
            @PackageManager.PackageInfoFlagsBits long flags, @UserIdInt int userId);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @NonNull
    List<ApplicationInfo> getInstalledApplications(
            @PackageManager.ApplicationInfoFlagsBits long flags, @UserIdInt int userId,
            int callingUid);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @Nullable
    ProviderInfo resolveContentProvider(@NonNull String name,
            @PackageManager.ResolveInfoFlagsBits long flags, @UserIdInt int userId, int callingUid);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @Nullable
    ProviderInfo getGrantImplicitAccessProviderInfo(int recipientUid,
            @NonNull String visibleAuthority);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    void querySyncProviders(boolean safeMode, @NonNull List<String> outNames,
            @NonNull List<ProviderInfo> outInfo);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @NonNull
    ParceledListSlice<ProviderInfo> queryContentProviders(@Nullable String processName, int uid,
            @PackageManager.ComponentInfoFlagsBits long flags, @Nullable String metaDataKey);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @Nullable
    InstrumentationInfo getInstrumentationInfo(@NonNull ComponentName component, int flags);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @NonNull
    ParceledListSlice<InstrumentationInfo> queryInstrumentation(
            @NonNull String targetPackage, int flags);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @NonNull
    List<PackageStateInternal> findSharedNonSystemLibraries(
            @NonNull PackageStateInternal pkgSetting);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    boolean getApplicationHiddenSettingAsUser(@NonNull String packageName, @UserIdInt int userId);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    boolean isPackageSuspendedForUser(@NonNull String packageName, @UserIdInt int userId);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    boolean isSuspendingAnyPackages(@NonNull String suspendingPackage, @UserIdInt int userId);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @NonNull
    ParceledListSlice<IntentFilter> getAllIntentFilters(@NonNull String packageName);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    boolean getBlockUninstallForUser(@NonNull String packageName, @UserIdInt int userId);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @Nullable
    SparseArray<int[]> getBroadcastAllowList(@NonNull String packageName, @UserIdInt int[] userIds,
            boolean isInstantApp);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @Nullable
    String getInstallerPackageName(@NonNull String packageName);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @Nullable
    InstallSourceInfo getInstallSourceInfo(@NonNull String packageName);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @PackageManager.EnabledState
    int getApplicationEnabledSetting(@NonNull String packageName, @UserIdInt int userId);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @PackageManager.EnabledState
    int getComponentEnabledSetting(@NonNull ComponentName component, int callingUid,
            @UserIdInt int userId);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @PackageManager.EnabledState
    int getComponentEnabledSettingInternal(@NonNull ComponentName component, int callingUid,
            @UserIdInt int userId);

    /**
     * @return true if the runtime app user enabled state, runtime component user enabled state,
     * install-time app manifest enabled state, and install-time component manifest enabled state
     * are all effectively enabled for the given component. Or if the component cannot be found,
     * returns false.
     */
    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    boolean isComponentEffectivelyEnabled(@NonNull ComponentInfo componentInfo,
            @UserIdInt int userId);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @Nullable
    KeySet getKeySetByAlias(@NonNull String packageName, @NonNull String alias);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @Nullable
    KeySet getSigningKeySet(@NonNull String packageName);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    boolean isPackageSignedByKeySet(@NonNull String packageName, @NonNull KeySet ks);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    boolean isPackageSignedByKeySetExactly(@NonNull String packageName, @NonNull KeySet ks);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @Nullable
    int[] getVisibilityAllowList(@NonNull String packageName, @UserIdInt int userId);

    /**
     * Returns whether the given UID either declares &lt;queries&gt; element with the given package
     * name in its app's manifest, has {@link android.Manifest.permission.QUERY_ALL_PACKAGES}, or
     * package visibility filtering is enabled on it. If the UID is part of a shared user ID,
     * return {@code true} if any one application belongs to the shared user ID meets the criteria.
     */
    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    boolean canQueryPackage(int callingUid, @Nullable String targetPackageName);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    int getPackageUid(@NonNull String packageName, @PackageManager.PackageInfoFlagsBits long flags,
            @UserIdInt int userId);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    boolean canAccessComponent(int callingUid, @NonNull ComponentName component,
            @UserIdInt int userId);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    boolean isCallerInstallerOfRecord(@NonNull AndroidPackage pkg, int callingUid);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @PackageManager.InstallReason
    int getInstallReason(@NonNull String packageName, @UserIdInt int userId);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    boolean canPackageQuery(@NonNull String sourcePackageName, @NonNull String targetPackageName,
            @UserIdInt int userId);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    boolean canForwardTo(@NonNull Intent intent, @Nullable String resolvedType,
            @UserIdInt int sourceUserId, @UserIdInt int targetUserId);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @NonNull
    List<ApplicationInfo> getPersistentApplications(boolean safeMode, int flags);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @NonNull
    SparseArray<String> getAppsWithSharedUserIds();

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @NonNull
    String[] getSharedUserPackagesForPackage(@NonNull String packageName, @UserIdInt int userId);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @NonNull
    Set<String> getUnusedPackages(long downgradeTimeThresholdMillis);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @Nullable
    CharSequence getHarmfulAppWarning(@NonNull String packageName, @UserIdInt int userId);

    /**
     * Only keep package names that refer to {@link AndroidPackage#isSystem system} packages.
     *
     * @param pkgNames The packages to filter
     *
     * @return The filtered packages
     */
    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @NonNull
    String[] filterOnlySystemPackages(@Nullable String... pkgNames);

    // The methods in this block should be removed once SettingBase is interface snapshotted
    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @NonNull
    List<AndroidPackage> getPackagesForAppId(int appId);

    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    int getUidTargetSdkVersion(int uid);

    /**
     * @see PackageManagerInternal#getProcessesForUid(int)
     */
    @Computer.LiveImplementation(override = LiveImplementation.MANDATORY)
    @Nullable
    ArrayMap<String, ProcessInfo> getProcessesForUid(int uid);
    // End block
}
