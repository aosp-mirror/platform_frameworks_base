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
import android.util.Pair;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageState;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.SharedUserApi;
import com.android.server.pm.resolution.ComponentResolverApi;
import com.android.server.pm.snapshot.PackageDataSnapshot;
import com.android.server.utils.WatchedArrayMap;
import com.android.server.utils.WatchedLongSparseArray;

import java.io.FileDescriptor;
import java.io.PrintWriter;
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
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
public interface Computer extends PackageDataSnapshot {

    /**
     * Administrative statistics: record that the snapshot has been used.  Every call
     * to use() increments the usage counter.
     */
    default void use() {
    }
    /**
     * Fetch the snapshot usage counter.
     * @return The number of times this snapshot was used.
     */
    default int getUsed() {
        return 0;
    }
    @NonNull List<ResolveInfo> queryIntentActivitiesInternal(Intent intent, String resolvedType,
            @PackageManager.ResolveInfoFlagsBits long flags,
            @PackageManagerInternal.PrivateResolveFlags long privateResolveFlags,
            int filterCallingUid, int userId, boolean resolveForStart, boolean allowDynamicSplits);
    @NonNull List<ResolveInfo> queryIntentActivitiesInternal(Intent intent, String resolvedType,
            long flags, int userId);
    @NonNull List<ResolveInfo> queryIntentServicesInternal(Intent intent, String resolvedType,
            long flags, int userId, int callingUid, boolean includeInstantApps);
    @NonNull QueryIntentActivitiesResult queryIntentActivitiesInternalBody(Intent intent,
            String resolvedType, long flags, int filterCallingUid, int userId,
            boolean resolveForStart, boolean allowDynamicSplits, String pkgName,
            String instantAppPkgName);
    ActivityInfo getActivityInfo(ComponentName component, long flags, int userId);

    /**
     * Important: The provided filterCallingUid is used exclusively to filter out activities
     * that can be seen based on user state. It's typically the original caller uid prior
     * to clearing. Because it can only be provided by trusted code, its value can be
     * trusted and will be used as-is; unlike userId which will be validated by this method.
     */
    ActivityInfo getActivityInfoInternal(ComponentName component, long flags,
            int filterCallingUid, int userId);
    AndroidPackage getPackage(String packageName);
    AndroidPackage getPackage(int uid);
    ApplicationInfo generateApplicationInfoFromSettings(String packageName, long flags,
            int filterCallingUid, int userId);
    ApplicationInfo getApplicationInfo(String packageName, long flags, int userId);

    /**
     * Important: The provided filterCallingUid is used exclusively to filter out applications
     * that can be seen based on user state. It's typically the original caller uid prior
     * to clearing. Because it can only be provided by trusted code, its value can be
     * trusted and will be used as-is; unlike userId which will be validated by this method.
     */
    ApplicationInfo getApplicationInfoInternal(String packageName, long flags,
            int filterCallingUid, int userId);

    /**
     * Report the 'Home' activity which is currently set as "always use this one". If non is set
     * then reports the most likely home activity or null if there are more than one.
     */
    ComponentName getDefaultHomeActivity(int userId);
    ComponentName getHomeActivitiesAsUser(List<ResolveInfo> allHomeCandidates, int userId);
    CrossProfileDomainInfo getCrossProfileDomainPreferredLpr(Intent intent, String resolvedType,
            long flags, int sourceUserId, int parentUserId);
    Intent getHomeIntent();
    List<CrossProfileIntentFilter> getMatchingCrossProfileIntentFilters(Intent intent,
            String resolvedType, int userId);

    /**
     * Filters out ephemeral activities.
     * <p>When resolving for an ephemeral app, only activities that 1) are defined in the
     * ephemeral app or 2) marked with {@code visibleToEphemeral} are returned.
     *
     * @param resolveInfos The pre-filtered list of resolved activities
     * @param ephemeralPkgName The ephemeral package name. If {@code null}, no filtering
     *          is performed.
     * @param intent
     * @return A filtered list of resolved activities.
     */
    List<ResolveInfo> applyPostResolutionFilter(@NonNull List<ResolveInfo> resolveInfos,
            String ephemeralPkgName, boolean allowDynamicSplits, int filterCallingUid,
            boolean resolveForStart, int userId, Intent intent);
    PackageInfo generatePackageInfo(PackageStateInternal ps, long flags, int userId);
    PackageInfo getPackageInfo(String packageName, long flags, int userId);
    PackageInfo getPackageInfoInternal(String packageName, long versionCode, long flags,
            int filterCallingUid, int userId);

    /**
     * @return package names of all available {@link AndroidPackage} instances. This means any
     * known {@link PackageState} instances without a {@link PackageState#getAndroidPackage()}
     * will not be represented.
     */
    String[] getAllAvailablePackageNames();

    PackageStateInternal getPackageStateInternal(String packageName);
    PackageStateInternal getPackageStateInternal(String packageName, int callingUid);
    ParceledListSlice<PackageInfo> getInstalledPackages(long flags, int userId);
    ResolveInfo createForwardingResolveInfoUnchecked(WatchedIntentFilter filter,
            int sourceUserId, int targetUserId);
    ServiceInfo getServiceInfo(ComponentName component, long flags, int userId);
    SharedLibraryInfo getSharedLibraryInfo(String name, long version);
    String getInstantAppPackageName(int callingUid);
    String resolveExternalPackageName(AndroidPackage pkg);
    String resolveInternalPackageName(String packageName, long versionCode);
    String[] getPackagesForUid(int uid);
    UserInfo getProfileParent(int userId);
    boolean canViewInstantApps(int callingUid, int userId);
    boolean filterSharedLibPackage(@Nullable PackageStateInternal ps, int uid, int userId,
            long flags);
    boolean isCallerSameApp(String packageName, int uid);
    boolean isComponentVisibleToInstantApp(@Nullable ComponentName component);
    boolean isComponentVisibleToInstantApp(@Nullable ComponentName component,
            @PackageManager.ComponentType int type);

    /**
     * From Android R, camera intents have to match system apps. The only exception to this is if
     * the DPC has set the camera persistent preferred activity. This case was introduced
     * because it is important that the DPC has the ability to set both system and non-system
     * camera persistent preferred activities.
     *
     * @return {@code true} if the intent is a camera intent and the persistent preferred
     * activity was not set by the DPC.
     */
    boolean isImplicitImageCaptureIntentAndNotSetByDpc(Intent intent, int userId,
            String resolvedType, long flags);
    boolean isInstantApp(String packageName, int userId);
    boolean isInstantAppInternal(String packageName, @UserIdInt int userId, int callingUid);
    boolean isSameProfileGroup(@UserIdInt int callerUserId, @UserIdInt int userId);
    boolean shouldFilterApplication(@Nullable PackageStateInternal ps, int callingUid,
            @Nullable ComponentName component, @PackageManager.ComponentType int componentType,
            int userId);
    boolean shouldFilterApplication(@Nullable PackageStateInternal ps, int callingUid,
            int userId);
    boolean shouldFilterApplication(@NonNull SharedUserSetting sus, int callingUid,
            int userId);
    int checkUidPermission(String permName, int uid);
    int getPackageUidInternal(String packageName, long flags, int userId, int callingUid);
    long updateFlagsForApplication(long flags, int userId);
    long updateFlagsForComponent(long flags, int userId);
    long updateFlagsForPackage(long flags, int userId);

    /**
     * Update given flags when being used to request {@link ResolveInfo}.
     * <p>Instant apps are resolved specially, depending upon context. Minimally,
     * {@code}flags{@code} must have the {@link PackageManager#MATCH_INSTANT}
     * flag set. However, this flag is only honoured in three circumstances:
     * <ul>
     * <li>when called from a system process</li>
     * <li>when the caller holds the permission {@code android.permission.ACCESS_INSTANT_APPS}</li>
     * <li>when resolution occurs to start an activity with a {@code android.intent.action.VIEW}
     * action and a {@code android.intent.category.BROWSABLE} category</li>
     * </ul>
     */
    long updateFlagsForResolve(long flags, int userId, int callingUid, boolean wantInstantApps,
            boolean isImplicitImageCaptureIntentAndNotSetByDpc);
    long updateFlagsForResolve(long flags, int userId, int callingUid, boolean wantInstantApps,
            boolean onlyExposedExplicitly, boolean isImplicitImageCaptureIntentAndNotSetByDpc);

    /**
     * Checks if the request is from the system or an app that has the appropriate cross-user
     * permissions defined as follows:
     * <ul>
     * <li>INTERACT_ACROSS_USERS_FULL if {@code requireFullPermission} is true.</li>
     * <li>INTERACT_ACROSS_USERS if the given {@code userId} is in a different profile group
     * to the caller.</li>
     * <li>Otherwise, INTERACT_ACROSS_PROFILES if the given {@code userId} is in the same profile
     * group as the caller.</li>
     * </ul>
     *
     * @param checkShell whether to prevent shell from access if there's a debugging restriction
     * @param message the message to log on security exception
     */
    void enforceCrossUserOrProfilePermission(int callingUid, @UserIdInt int userId,
            boolean requireFullPermission, boolean checkShell, String message);

    /**
     * Enforces the request is from the system or an app that has INTERACT_ACROSS_USERS
     * or INTERACT_ACROSS_USERS_FULL permissions, if the {@code userId} is not for the caller.
     *
     * @param checkShell whether to prevent shell from access if there's a debugging restriction
     * @param message the message to log on security exception
     */
    void enforceCrossUserPermission(int callingUid, @UserIdInt int userId,
            boolean requireFullPermission, boolean checkShell, String message);
    void enforceCrossUserPermission(int callingUid, @UserIdInt int userId,
            boolean requireFullPermission, boolean checkShell,
            boolean requirePermissionWhenSameUser, String message);
    SigningDetails getSigningDetails(@NonNull String packageName);
    SigningDetails getSigningDetails(int uid);
    boolean filterAppAccess(AndroidPackage pkg, int callingUid, int userId);
    boolean filterAppAccess(String packageName, int callingUid, int userId);
    boolean filterAppAccess(int uid, int callingUid);
    void dump(int type, FileDescriptor fd, PrintWriter pw, DumpState dumpState);
    PackageManagerService.FindPreferredActivityBodyResult findPreferredActivityInternal(
            Intent intent, String resolvedType, long flags, List<ResolveInfo> query, boolean always,
            boolean removeMatches, boolean debug, int userId, boolean queryMayBeFiltered);
    ResolveInfo findPersistentPreferredActivity(Intent intent, String resolvedType, long flags,
            List<ResolveInfo> query, boolean debug, int userId);

    PreferredIntentResolver getPreferredActivities(@UserIdInt int userId);

    @NonNull
    ArrayMap<String, ? extends PackageStateInternal> getPackageStates();

    @Nullable
    String getRenamedPackage(@NonNull String packageName);

    /**
     * @return set of packages to notify
     */
    @NonNull
    ArraySet<String> getNotifyPackagesForReplacedReceived(@NonNull String[] packages);

    @PackageManagerService.PackageStartability
    int getPackageStartability(boolean safeMode, @NonNull String packageName, int callingUid,
            @UserIdInt int userId);

    boolean isPackageAvailable(String packageName, @UserIdInt int userId);

    @NonNull
    String[] currentToCanonicalPackageNames(@NonNull String[] names);

    @NonNull
    String[] canonicalToCurrentPackageNames(@NonNull String[] names);

    @NonNull
    int[] getPackageGids(@NonNull String packageName,
            @PackageManager.PackageInfoFlagsBits long flags, @UserIdInt int userId);

    int getTargetSdkVersion(@NonNull String packageName);

    boolean activitySupportsIntent(@NonNull ComponentName resolveComponentName,
            @NonNull ComponentName component, @NonNull Intent intent, String resolvedType);

    @Nullable
    ActivityInfo getReceiverInfo(@NonNull ComponentName component,
            @PackageManager.ComponentInfoFlagsBits long flags, @UserIdInt int userId);

    @Nullable
    ParceledListSlice<SharedLibraryInfo> getSharedLibraries(@NonNull String packageName,
            @PackageManager.PackageInfoFlagsBits long flags, @UserIdInt int userId);

    boolean canRequestPackageInstalls(@NonNull String packageName, int callingUid,
            int userId, boolean throwIfPermNotDeclared);

    /**
     * Returns true if the system or user is explicitly preventing an otherwise valid installer to
     * complete an install. This includes checks like unknown sources and user restrictions.
     */
    boolean isInstallDisabledForPackage(@NonNull String packageName, int uid,
            @UserIdInt int userId);

    @Nullable
    List<VersionedPackage> getPackagesUsingSharedLibrary(@NonNull SharedLibraryInfo libInfo,
            @PackageManager.PackageInfoFlagsBits long flags, int callingUid, @UserIdInt int userId);

    @Nullable
    ParceledListSlice<SharedLibraryInfo> getDeclaredSharedLibraries(
            @NonNull String packageName, @PackageManager.PackageInfoFlagsBits long flags,
            @UserIdInt int userId);

    @Nullable
    ProviderInfo getProviderInfo(@NonNull ComponentName component,
            @PackageManager.ComponentInfoFlagsBits long flags, @UserIdInt int userId);

    @Nullable
    String[] getSystemSharedLibraryNames();

    /**
     * @return the state if the given package has a state and isn't filtered by visibility.
     * Provides no guarantee that the package is in any usable state.
     */
    @Nullable
    PackageStateInternal getPackageStateFiltered(@NonNull String packageName, int callingUid,
            @UserIdInt int userId);

    int checkSignatures(@NonNull String pkg1, @NonNull String pkg2);

    int checkUidSignatures(int uid1, int uid2);

    boolean hasSigningCertificate(@NonNull String packageName, @NonNull byte[] certificate,
            @PackageManager.CertificateInputType int type);

    boolean hasUidSigningCertificate(int uid, @NonNull byte[] certificate,
            @PackageManager.CertificateInputType int type);

    @NonNull
    List<String> getAllPackages();

    @Nullable
    String getNameForUid(int uid);

    @Nullable
    String[] getNamesForUids(@NonNull int[] uids);

    int getUidForSharedUser(@NonNull String sharedUserName);

    int getFlagsForUid(int uid);

    int getPrivateFlagsForUid(int uid);

    boolean isUidPrivileged(int uid);

    @NonNull
    String[] getAppOpPermissionPackages(@NonNull String permissionName);

    @NonNull
    ParceledListSlice<PackageInfo> getPackagesHoldingPermissions(@NonNull String[] permissions,
            @PackageManager.PackageInfoFlagsBits long flags, @UserIdInt int userId);

    @NonNull
    List<ApplicationInfo> getInstalledApplications(
            @PackageManager.ApplicationInfoFlagsBits long flags, @UserIdInt int userId,
            int callingUid);

    @Nullable
    ProviderInfo resolveContentProvider(@NonNull String name,
            @PackageManager.ResolveInfoFlagsBits long flags, @UserIdInt int userId, int callingUid);

    @Nullable
    ProviderInfo getGrantImplicitAccessProviderInfo(int recipientUid,
            @NonNull String visibleAuthority);

    void querySyncProviders(boolean safeMode, @NonNull List<String> outNames,
            @NonNull List<ProviderInfo> outInfo);

    @NonNull
    ParceledListSlice<ProviderInfo> queryContentProviders(@Nullable String processName, int uid,
            @PackageManager.ComponentInfoFlagsBits long flags, @Nullable String metaDataKey);

    @Nullable
    InstrumentationInfo getInstrumentationInfo(@NonNull ComponentName component, int flags);

    @NonNull
    ParceledListSlice<InstrumentationInfo> queryInstrumentation(
            @NonNull String targetPackage, int flags);

    @NonNull
    List<PackageStateInternal> findSharedNonSystemLibraries(
            @NonNull PackageStateInternal pkgSetting);

    boolean getApplicationHiddenSettingAsUser(@NonNull String packageName, @UserIdInt int userId);

    boolean isPackageSuspendedForUser(@NonNull String packageName, @UserIdInt int userId);

    boolean isSuspendingAnyPackages(@NonNull String suspendingPackage, @UserIdInt int userId);

    @NonNull
    ParceledListSlice<IntentFilter> getAllIntentFilters(@NonNull String packageName);

    boolean getBlockUninstallForUser(@NonNull String packageName, @UserIdInt int userId);

    @Nullable
    String getInstallerPackageName(@NonNull String packageName);

    @Nullable
    InstallSourceInfo getInstallSourceInfo(@NonNull String packageName);

    @PackageManager.EnabledState
    int getApplicationEnabledSetting(@NonNull String packageName, @UserIdInt int userId);

    @PackageManager.EnabledState
    int getComponentEnabledSetting(@NonNull ComponentName component, int callingUid,
            @UserIdInt int userId);

    @PackageManager.EnabledState
    int getComponentEnabledSettingInternal(@NonNull ComponentName component, int callingUid,
            @UserIdInt int userId);

    /**
     * @return true if the runtime app user enabled state, runtime component user enabled state,
     * install-time app manifest enabled state, and install-time component manifest enabled state
     * are all effectively enabled for the given component. Or if the component cannot be found,
     * returns false.
     */
    boolean isComponentEffectivelyEnabled(@NonNull ComponentInfo componentInfo,
            @UserIdInt int userId);

    @Nullable
    KeySet getKeySetByAlias(@NonNull String packageName, @NonNull String alias);

    @Nullable
    KeySet getSigningKeySet(@NonNull String packageName);

    boolean isPackageSignedByKeySet(@NonNull String packageName, @NonNull KeySet ks);

    boolean isPackageSignedByKeySetExactly(@NonNull String packageName, @NonNull KeySet ks);

    /**
     * See {@link AppsFilterSnapshot#getVisibilityAllowList(PackageStateInternal, int[], ArrayMap)}
     */
    @Nullable
    SparseArray<int[]> getVisibilityAllowLists(@NonNull String packageName,
            @UserIdInt int[] userIds);

    /**
     * See {@link AppsFilterSnapshot#getVisibilityAllowList(PackageStateInternal, int[], ArrayMap)}
     */
    @Nullable
    int[] getVisibilityAllowList(@NonNull String packageName, @UserIdInt int userId);

    /**
     * Returns whether the given UID either declares &lt;queries&gt; element with the given package
     * name in its app's manifest, has {@link android.Manifest.permission.QUERY_ALL_PACKAGES}, or
     * package visibility filtering is enabled on it. If the UID is part of a shared user ID,
     * return {@code true} if any one application belongs to the shared user ID meets the criteria.
     */
    boolean canQueryPackage(int callingUid, @Nullable String targetPackageName);

    int getPackageUid(@NonNull String packageName, @PackageManager.PackageInfoFlagsBits long flags,
            @UserIdInt int userId);

    boolean canAccessComponent(int callingUid, @NonNull ComponentName component,
            @UserIdInt int userId);

    boolean isCallerInstallerOfRecord(@NonNull AndroidPackage pkg, int callingUid);

    @PackageManager.InstallReason
    int getInstallReason(@NonNull String packageName, @UserIdInt int userId);

    boolean canPackageQuery(@NonNull String sourcePackageName, @NonNull String targetPackageName,
            @UserIdInt int userId);

    boolean canForwardTo(@NonNull Intent intent, @Nullable String resolvedType,
            @UserIdInt int sourceUserId, @UserIdInt int targetUserId);

    @NonNull
    List<ApplicationInfo> getPersistentApplications(boolean safeMode, int flags);

    @NonNull
    SparseArray<String> getAppsWithSharedUserIds();

    @NonNull
    String[] getSharedUserPackagesForPackage(@NonNull String packageName, @UserIdInt int userId);

    @NonNull
    Set<String> getUnusedPackages(long downgradeTimeThresholdMillis);

    @Nullable
    CharSequence getHarmfulAppWarning(@NonNull String packageName, @UserIdInt int userId);

    /**
     * Only keep package names that refer to {@link AndroidPackage#isSystem system} packages.
     *
     * @param pkgNames The packages to filter
     *
     * @return The filtered packages
     */
    @NonNull
    String[] filterOnlySystemPackages(@Nullable String... pkgNames);

    // The methods in this block should be removed once SettingBase is interface snapshotted
    @NonNull
    List<AndroidPackage> getPackagesForAppId(int appId);

    int getUidTargetSdkVersion(int uid);

    /**
     * @see PackageManagerInternal#getProcessesForUid(int)
     */
    @Nullable
    ArrayMap<String, ProcessInfo> getProcessesForUid(int uid);
    // End block

    boolean getBlockUninstall(@UserIdInt int userId, @NonNull String packageName);

    @NonNull
    WatchedArrayMap<String, WatchedLongSparseArray<SharedLibraryInfo>> getSharedLibraries();


    @Nullable
    Pair<PackageStateInternal, SharedUserApi> getPackageOrSharedUser(int appId);

    @Nullable
    SharedUserApi getSharedUser(int sharedUserAppIde);

    @NonNull
    ArraySet<PackageStateInternal> getSharedUserPackages(int sharedUserAppId);

    @NonNull
    ComponentResolverApi getComponentResolver();

    @Nullable
    PackageStateInternal getDisabledSystemPackage(@NonNull String packageName);

    @Nullable
    ResolveInfo getInstantAppInstallerInfo();

    @NonNull
    WatchedArrayMap<String, Integer> getFrozenPackages();

    /**
     * Verify that given package is currently frozen.
     */
    void checkPackageFrozen(@NonNull String packageName);

    @Nullable
    ComponentName getInstantAppInstallerComponent();

    void dumpPermissions(@NonNull PrintWriter pw, @NonNull String packageName,
            @NonNull ArraySet<String> permissionNames, @NonNull DumpState dumpState);

    void dumpPackages(PrintWriter pw, @NonNull String packageName,
            @NonNull ArraySet<String> permissionNames, @NonNull DumpState dumpState,
            boolean checkin);

    void dumpKeySet(@NonNull PrintWriter pw, @NonNull String packageName,
            @NonNull DumpState dumpState);

    void dumpSharedUsers(@NonNull PrintWriter pw, @NonNull String packageName,
            @NonNull ArraySet<String> permissionNames,
            @NonNull DumpState dumpState, boolean checkin);

    void dumpSharedUsersProto(@NonNull ProtoOutputStream proto);

    void dumpPackagesProto(@NonNull ProtoOutputStream proto);

    void dumpSharedLibrariesProto(@NonNull ProtoOutputStream protoOutputStream);

    @NonNull
    List<? extends PackageStateInternal> getVolumePackages(@NonNull String volumeUuid);
}
