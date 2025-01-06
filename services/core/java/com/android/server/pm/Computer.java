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
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Pair;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.pm.pkg.AndroidPackage;
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
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public interface Computer extends PackageDataSnapshot {

    int getVersion();

    /**
     * Administrative statistics: record that the snapshot has been used.  Every call
     * to use() increments the usage counter.
     */
    Computer use();
    /**
     * Fetch the snapshot usage counter.
     * @return The number of times this snapshot was used.
     */
    default int getUsed() {
        return 0;
    }
    @NonNull List<ResolveInfo> queryIntentActivitiesInternal(
            Intent intent, String resolvedType,
            @PackageManager.ResolveInfoFlagsBits long flags,
            @PackageManagerInternal.PrivateResolveFlags long privateResolveFlags,
            int filterCallingUid, int callingPid, int userId,
            boolean resolveForStart, boolean allowDynamicSplits);
    @NonNull List<ResolveInfo> queryIntentActivitiesInternal(Intent intent, String resolvedType,
            long flags, int filterCallingUid, int userId);
    @NonNull List<ResolveInfo> queryIntentActivitiesInternal(Intent intent, String resolvedType,
            long flags, int userId);
    @NonNull List<ResolveInfo> queryIntentServicesInternal(
            Intent intent, String resolvedType, long flags,
            int userId, int callingUid, int callingPid,
            boolean includeInstantApps, boolean resolveForStart);
    @NonNull QueryIntentActivitiesResult queryIntentActivitiesInternalBody(Intent intent,
            String resolvedType, long flags, int filterCallingUid, int userId,
            boolean resolveForStart, boolean allowDynamicSplits, String pkgName,
            String instantAppPkgName);
    ActivityInfo getActivityInfo(ComponentName component, long flags, int userId);

    /**
     * Similar to {@link Computer#getActivityInfo(android.content.ComponentName, long, int)} but
     * only visible as internal service. This method bypass INTERACT_ACROSS_USERS or
     * INTERACT_ACROSS_USERS_FULL permission checks and only to be used for intent resolution across
     * chained cross profiles
     */
    ActivityInfo getActivityInfoCrossProfile(ComponentName component, long flags, int userId);

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
    PackageStateInternal getPackageStateFiltered(@NonNull String packageName, int callingUid,
            @UserIdInt int userId);
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
    /**
     * Returns true if the package name and the uid represent the same app.
     *
     * @param resolveIsolatedUid if true, resolves an isolated uid into the real uid.
     */
    boolean isCallerSameApp(String packageName, int uid, boolean resolveIsolatedUid);
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
            int userId, boolean filterUninstall);
    boolean shouldFilterApplication(@Nullable PackageStateInternal ps, int callingUid,
            @Nullable ComponentName component, @PackageManager.ComponentType int componentType,
            int userId);
    boolean shouldFilterApplication(@Nullable PackageStateInternal ps, int callingUid,
            int userId);
    boolean shouldFilterApplication(@NonNull SharedUserSetting sus, int callingUid,
            int userId);
    /**
     * Different form {@link #shouldFilterApplication(PackageStateInternal, int, int)}, the function
     * returns {@code true} if the target package is not found in the device or uninstalled in the
     * current user. Unless the caller's function needs to handle the package's uninstalled state
     * by itself, using this function to keep the consistent behavior between conditions of package
     * uninstalled and visibility not allowed to avoid the side channel leakage of package
     * existence.
     * <p>
     * Package with {@link PackageManager#SYSTEM_APP_STATE_HIDDEN_UNTIL_INSTALLED_HIDDEN} is not
     * treated as an uninstalled package for the carrier apps customization. Bypassing the
     * uninstalled package check if the caller is system, shell or root uid.
     */
    boolean shouldFilterApplicationIncludingUninstalled(@Nullable PackageStateInternal ps,
            int callingUid, int userId);

    /**
     * Different from
     * {@link #shouldFilterApplicationIncludingUninstalled(PackageStateInternal, int, int)}, the
     * function returns {@code true} if:
     * <ul>
     * <li>The target package is not archived.
     * <li>The package cannot be found in the device or has been uninstalled in the current user.
     * </ul>
     */
    boolean shouldFilterApplicationIncludingUninstalledNotArchived(
            @Nullable PackageStateInternal ps,
            int callingUid, int userId);
    /**
     * Different from {@link #shouldFilterApplication(SharedUserSetting, int, int)}, the function
     * returns {@code true} if packages with the same shared user are all uninstalled in the current
     * user.
     *
     * @see #shouldFilterApplicationIncludingUninstalled(PackageStateInternal, int, int)
     */
    boolean shouldFilterApplicationIncludingUninstalled(@NonNull SharedUserSetting sus,
            int callingUid, int userId);
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
    boolean filterAppAccess(String packageName, int callingUid, int userId,
            boolean filterUninstalled);
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

    @NonNull
    ArrayMap<String, ? extends PackageStateInternal> getDisabledSystemPackageStates();

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

    boolean isApexPackage(String packageName);

    @NonNull
    String[] currentToCanonicalPackageNames(@NonNull String[] names);

    @NonNull
    String[] canonicalToCurrentPackageNames(@NonNull String[] names);

    @NonNull
    int[] getPackageGids(@NonNull String packageName,
            @PackageManager.PackageInfoFlagsBits long flags, @UserIdInt int userId);

    int getTargetSdkVersion(@NonNull String packageName);

    boolean activitySupportsIntentAsUser(@NonNull ComponentName resolveComponentName,
            @NonNull ComponentName component, @NonNull Intent intent, String resolvedType,
            int userId);

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

    /**
     * Returns a Pair that contains a list of packages that depend on the target library and the
     * package library dependency information. The List&lt;VersionedPackage&gt; indicates a list of
     * packages that depend on the target library, it may be null if no package depends on
     * the target library. The List&lt;Boolean&gt; indicates whether each VersionedPackage in
     * the List&lt;VersionedPackage&gt; optionally depends on the target library, where true means
     * optional and false means required. It may be null if no package depends on
     * the target library or without dependency information, e.g. uses-static-library.
     */
    @NonNull
    Pair<List<VersionedPackage>, List<Boolean>> getPackagesUsingSharedLibrary(
            @NonNull SharedLibraryInfo libInfo, @PackageManager.PackageInfoFlagsBits long flags,
            int callingUid, @UserIdInt int userId);

    @Nullable
    ParceledListSlice<SharedLibraryInfo> getDeclaredSharedLibraries(
            @NonNull String packageName, @PackageManager.PackageInfoFlagsBits long flags,
            @UserIdInt int userId);

    @Nullable
    ProviderInfo getProviderInfo(@NonNull ComponentName component,
            @PackageManager.ComponentInfoFlagsBits long flags, @UserIdInt int userId);

    ArrayMap<String, String> getSystemSharedLibraryNamesAndPaths();

    /**
     * @return the state if the given package is installed and isn't filtered by visibility.
     * Provides no guarantee that the package is in any usable state.
     */
    @Nullable
    PackageStateInternal getPackageStateForInstalledAndFiltered(@NonNull String packageName,
            int callingUid, @UserIdInt int userId);

    int checkSignatures(@NonNull String pkg1, @NonNull String pkg2, int userId);

    int checkUidSignatures(int uid1, int uid2);

    int checkUidSignaturesForAllUsers(int uid1, int uid2);

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
    String[] getAppOpPermissionPackages(@NonNull String permissionName, int userId);

    @NonNull
    ParceledListSlice<PackageInfo> getPackagesHoldingPermissions(@NonNull String[] permissions,
            @PackageManager.PackageInfoFlagsBits long flags, @UserIdInt int userId);

    @NonNull
    List<ApplicationInfo> getInstalledApplications(
            @PackageManager.ApplicationInfoFlagsBits long flags, @UserIdInt int userId,
            int callingUid, boolean forceAllowCrossUser);

    @Nullable
    ProviderInfo resolveContentProvider(@NonNull String name,
            @PackageManager.ResolveInfoFlagsBits long flags, @UserIdInt int userId, int callingUid);

    /**
     * Resolves a ContentProvider on behalf of a UID
     * @param name Authority of the content provider
     * @param flags option flags to modify the data returned.
     * @param userId Current user ID
     * @param filterCallingUid UID of the caller who's access to the content provider
     *        is to be checked
     * @return
     */
    @Nullable
    ProviderInfo resolveContentProviderForUid(@NonNull String name,
            @PackageManager.ResolveInfoFlagsBits long flags, @UserIdInt int userId,
            int filterCallingUid);

    @Nullable
    ProviderInfo getGrantImplicitAccessProviderInfo(int recipientUid,
            @NonNull String visibleAuthority);

    void querySyncProviders(boolean safeMode, @NonNull List<String> outNames,
            @NonNull List<ProviderInfo> outInfo);

    @NonNull
    ParceledListSlice<ProviderInfo> queryContentProviders(@Nullable String processName, int uid,
            @PackageManager.ComponentInfoFlagsBits long flags, @Nullable String metaDataKey);

    @Nullable
    InstrumentationInfo getInstrumentationInfoAsUser(@NonNull ComponentName component, int flags,
            int userId);

    @NonNull
    ParceledListSlice<InstrumentationInfo> queryInstrumentationAsUser(
            @NonNull String targetPackage, int flags, int userId);

    @NonNull
    List<PackageStateInternal> findSharedNonSystemLibraries(
            @NonNull PackageStateInternal pkgSetting);

    boolean getApplicationHiddenSettingAsUser(@NonNull String packageName, @UserIdInt int userId);

    boolean isPackageSuspendedForUser(@NonNull String packageName, @UserIdInt int userId)
            throws PackageManager.NameNotFoundException;

    boolean isPackageQuarantinedForUser(@NonNull String packageName, @UserIdInt int userId)
            throws PackageManager.NameNotFoundException;

    /** Check if the package is in a stopped state for a given user. */
    boolean isPackageStoppedForUser(@NonNull String packageName, @UserIdInt int userId)
            throws PackageManager.NameNotFoundException;

    /** Check if the package is suspending any package. */
    boolean isSuspendingAnyPackages(@NonNull String suspendingPackage,
            @UserIdInt int suspendingUserId, int targetUserId);

    @NonNull
    ParceledListSlice<IntentFilter> getAllIntentFilters(@NonNull String packageName);

    boolean getBlockUninstallForUser(@NonNull String packageName, @UserIdInt int userId);

    @Nullable
    String getInstallerPackageName(@NonNull String packageName, @UserIdInt int userId);

    @Nullable
    InstallSourceInfo getInstallSourceInfo(@NonNull String packageName, @UserIdInt int userId);

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
            @NonNull UserHandle userHandle);

    /**
     * @return true if the runtime app user enabled state and the install-time app manifest enabled
     * state are both effectively enabled for the given app. Or if the app cannot be found,
     * returns false.
     */
    boolean isApplicationEffectivelyEnabled(@NonNull String packageName,
            @NonNull UserHandle userHandle);

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

    @NonNull
    boolean[] canPackageQuery(@NonNull String sourcePackageName,
            @NonNull String[] targetPackageNames, @UserIdInt int userId);

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
     * Only keep package names that refer to {@link PackageState#isSystem system} packages.
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

    @NonNull
    UserInfo[] getUserInfos();

    @NonNull
    ArrayMap<String, ? extends SharedUserApi> getSharedUsers();
}
