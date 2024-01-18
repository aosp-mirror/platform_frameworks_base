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


import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageDeleteObserver2;
import android.content.pm.IPackageInstaller;
import android.content.pm.IPackageManager;
import android.content.pm.IPackageStatsObserver;
import android.content.pm.InstallSourceInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.IntentFilterVerificationInfo;
import android.content.pm.KeySet;
import android.content.pm.ModuleInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.VersionedPackage;
import android.content.pm.dex.IArtManager;
import android.os.Binder;
import android.os.Process;
import android.os.RemoteException;
import android.os.Trace;
import android.os.UserHandle;
import android.permission.PermissionManager;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.content.InstallLocationUtils;
import com.android.internal.util.CollectionUtils;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.verify.domain.DomainVerificationManagerInternal;
import com.android.server.pm.verify.domain.proxy.DomainVerificationProxyV1;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Contains all simply proxy methods which need a snapshot instance and just calls a method on it,
 * with no additional logic. Separated with all methods marked final and deprecated to prevent their
 * use from other methods which may need a snapshot for non-trivial reasons.
 */
public abstract class IPackageManagerBase extends IPackageManager.Stub {

    @NonNull
    private final PackageManagerService mService;

    @NonNull
    private final Context mContext;

    @NonNull private final DexOptHelper mDexOptHelper;
    @NonNull private final ModuleInfoProvider mModuleInfoProvider;
    @NonNull private final PreferredActivityHelper mPreferredActivityHelper;
    @NonNull private final ResolveIntentHelper mResolveIntentHelper;

    @NonNull
    private final DomainVerificationManagerInternal mDomainVerificationManager;

    @NonNull
    private final DomainVerificationConnection mDomainVerificationConnection;

    @NonNull
    private final PackageInstallerService mInstallerService;

    @NonNull
    private final PackageProperty mPackageProperty;

    @NonNull
    private final ComponentName mResolveComponentName;

    @Nullable
    private final ComponentName mInstantAppResolverSettingsComponent;

    @Nullable
    private final String mServicesExtensionPackageName;

    @Nullable
    private final String mSharedSystemSharedLibraryPackageName;

    public IPackageManagerBase(@NonNull PackageManagerService service, @NonNull Context context,
            @NonNull DexOptHelper dexOptHelper, @NonNull ModuleInfoProvider moduleInfoProvider,
            @NonNull PreferredActivityHelper preferredActivityHelper,
            @NonNull ResolveIntentHelper resolveIntentHelper,
            @NonNull DomainVerificationManagerInternal domainVerificationManager,
            @NonNull DomainVerificationConnection domainVerificationConnection,
            @NonNull PackageInstallerService installerService,
            @NonNull PackageProperty packageProperty, @NonNull ComponentName resolveComponentName,
            @Nullable ComponentName instantAppResolverSettingsComponent,
            @Nullable String servicesExtensionPackageName,
            @Nullable String sharedSystemSharedLibraryPackageName) {
        mService = service;
        mContext = context;
        mDexOptHelper = dexOptHelper;
        mModuleInfoProvider = moduleInfoProvider;
        mPreferredActivityHelper = preferredActivityHelper;
        mResolveIntentHelper = resolveIntentHelper;
        mDomainVerificationManager = domainVerificationManager;
        mDomainVerificationConnection = domainVerificationConnection;
        mInstallerService = installerService;
        mPackageProperty = packageProperty;
        mResolveComponentName = resolveComponentName;
        mInstantAppResolverSettingsComponent = instantAppResolverSettingsComponent;
        mServicesExtensionPackageName = servicesExtensionPackageName;
        mSharedSystemSharedLibraryPackageName = sharedSystemSharedLibraryPackageName;
    }

    protected Computer snapshot() {
        return mService.snapshotComputer();
    }

    @Override
    @Deprecated
    public final boolean activitySupportsIntentAsUser(ComponentName component, Intent intent,
            String resolvedType, int userId) {
        return snapshot().activitySupportsIntentAsUser(mResolveComponentName, component, intent,
                resolvedType, userId);
    }

    @Override
    @Deprecated
    public final void addCrossProfileIntentFilter(IntentFilter intentFilter, String ownerPackage,
            int sourceUserId, int targetUserId, int flags) {
        mService.addCrossProfileIntentFilter(snapshot(),
                new WatchedIntentFilter(intentFilter), ownerPackage, sourceUserId, targetUserId,
                flags);
    }

    // NOTE: Can't remove due to unsupported app usage
    @Override
    @Deprecated
    public final boolean addPermission(PermissionInfo info) {
        // Because this is accessed via the package manager service AIDL,
        // go through the permission manager service AIDL
        return mContext.getSystemService(PermissionManager.class).addPermission(info, false);
    }

    // NOTE: Can't remove due to unsupported app usage
    @Override
    @Deprecated
    public final boolean addPermissionAsync(PermissionInfo info) {
        // Because this is accessed via the package manager service AIDL,
        // go through the permission manager service AIDL
        return mContext.getSystemService(PermissionManager.class).addPermission(info, true);
    }

    @Override
    @Deprecated
    public final void addPersistentPreferredActivity(IntentFilter filter, ComponentName activity,
            int userId) {
        mPreferredActivityHelper.addPersistentPreferredActivity(new WatchedIntentFilter(filter),
                activity, userId);
    }

    @Override
    @Deprecated
    public final void addPreferredActivity(IntentFilter filter, int match,
            ComponentName[] set, ComponentName activity, int userId, boolean removeExisting) {
        mPreferredActivityHelper.addPreferredActivity(snapshot(),
                new WatchedIntentFilter(filter), match, set, activity, true, userId,
                "Adding preferred", removeExisting);
    }

    /*
     * Returns if intent can be forwarded from the sourceUserId to the targetUserId
     */
    @Override
    @Deprecated
    public final boolean canForwardTo(@NonNull Intent intent, @Nullable String resolvedType,
            @UserIdInt int sourceUserId, @UserIdInt int targetUserId) {
        return snapshot().canForwardTo(intent, resolvedType, sourceUserId, targetUserId);
    }

    @Override
    @Deprecated
    public final boolean canRequestPackageInstalls(String packageName, int userId) {
        return snapshot().canRequestPackageInstalls(packageName, Binder.getCallingUid(), userId,
                true /* throwIfPermNotDeclared*/);
    }

    @Override
    @Deprecated
    public final String[] canonicalToCurrentPackageNames(String[] names) {
        return snapshot().canonicalToCurrentPackageNames(names);
    }

    // NOTE: Can't remove due to unsupported app usage
    @Override
    @Deprecated
    public final int checkPermission(String permName, String pkgName, int userId) {
        return mService.checkPermission(permName, pkgName, userId);
    }

    @Override
    @Deprecated
    public final int checkSignatures(@NonNull String pkg1, @NonNull String pkg2, int userId) {
        return snapshot().checkSignatures(pkg1, pkg2, userId);
    }

    @Override
    @Deprecated
    public final int checkUidPermission(String permName, int uid) {
        return snapshot().checkUidPermission(permName, uid);
    }

    @Override
    @Deprecated
    public final int checkUidSignatures(int uid1, int uid2) {
        return snapshot().checkUidSignatures(uid1, uid2);
    }

    @Override
    @Deprecated
    public final void clearPackagePersistentPreferredActivities(String packageName, int userId) {
        mPreferredActivityHelper.clearPackagePersistentPreferredActivities(packageName, userId);
    }

    @Override
    @Deprecated
    public final void clearPersistentPreferredActivity(IntentFilter filter, int userId) {
        mPreferredActivityHelper.clearPersistentPreferredActivity(filter, userId);
    }

    @Override
    @Deprecated
    public final void clearPackagePreferredActivities(String packageName) {
        mPreferredActivityHelper.clearPackagePreferredActivities(snapshot(),
                packageName);
    }

    @Override
    @Deprecated
    public final String[] currentToCanonicalPackageNames(String[] names) {
        return snapshot().currentToCanonicalPackageNames(names);
    }

    @Override
    @Deprecated
    public final void deleteExistingPackageAsUser(VersionedPackage versionedPackage,
            final IPackageDeleteObserver2 observer, final int userId) {
        mService.deleteExistingPackageAsUser(versionedPackage, observer, userId);
    }

    @Override
    @Deprecated
    public final void deletePackageAsUser(String packageName, int versionCode,
            IPackageDeleteObserver observer, int userId, int flags) {
        deletePackageVersioned(new VersionedPackage(packageName, versionCode),
                new PackageManager.LegacyPackageDeleteObserver(observer).getBinder(), userId,
                flags);
    }

    @Override
    @Deprecated
    public final void deletePackageVersioned(VersionedPackage versionedPackage,
            final IPackageDeleteObserver2 observer, final int userId, final int deleteFlags) {
        mService.deletePackageVersioned(versionedPackage, observer, userId, deleteFlags);
    }

    @Override
    @Deprecated
    public final ResolveInfo findPersistentPreferredActivity(Intent intent, int userId) {
        return mPreferredActivityHelper.findPersistentPreferredActivity(snapshot(), intent, userId);
    }

    @Override
    @Deprecated
    public final ActivityInfo getActivityInfo(ComponentName component,
            @PackageManager.ComponentInfoFlagsBits long flags, int userId) {
        return snapshot().getActivityInfo(component, flags, userId);
    }

    @NonNull
    @Override
    @Deprecated
    public final ParceledListSlice<IntentFilter> getAllIntentFilters(@NonNull String packageName) {
        return snapshot().getAllIntentFilters(packageName);
    }

    @Override
    @Deprecated
    public final List<String> getAllPackages() {
        return snapshot().getAllPackages();
    }

    // NOTE: Can't remove due to unsupported app usage
    @NonNull
    @Override
    @Deprecated
    public final String[] getAppOpPermissionPackages(@NonNull String permissionName, int userId) {
        return snapshot().getAppOpPermissionPackages(permissionName, userId);
    }

    @Override
    @Deprecated
    public final String getAppPredictionServicePackageName() {
        return mService.mAppPredictionServicePackage;
    }

    @PackageManager.EnabledState
    @Override
    @Deprecated
    public final int getApplicationEnabledSetting(@NonNull String packageName,
            @UserIdInt int userId) {
        return snapshot().getApplicationEnabledSetting(packageName, userId);
    }

    /**
     * Returns true if application is not found or there was an error. Otherwise it returns the
     * hidden state of the package for the given user.
     */
    @Override
    @Deprecated
    public final boolean getApplicationHiddenSettingAsUser(@NonNull String packageName,
            @UserIdInt int userId) {
        return snapshot().getApplicationHiddenSettingAsUser(packageName, userId);
    }

    @Override
    @Deprecated
    public final ApplicationInfo getApplicationInfo(String packageName,
            @PackageManager.ApplicationInfoFlagsBits long flags, int userId) {
        return snapshot().getApplicationInfo(packageName, flags, userId);
    }

    @Override
    @Deprecated
    public final IArtManager getArtManager() {
        return mService.mArtManagerService;
    }

    @Override
    @Deprecated
    public final @Nullable
    String getAttentionServicePackageName() {
        return mService.ensureSystemPackageName(snapshot(),
                mService.getPackageFromComponentString(R.string.config_defaultAttentionService));
    }

    @Override
    @Deprecated
    public final boolean getBlockUninstallForUser(@NonNull String packageName,
            @UserIdInt int userId) {
        return snapshot().getBlockUninstallForUser(packageName, userId);
    }

    @Override
    @Deprecated
    public final int getComponentEnabledSetting(@NonNull ComponentName component, int userId) {
        return snapshot().getComponentEnabledSetting(component, Binder.getCallingUid(), userId);
    }

    @Nullable
    @Override
    @Deprecated
    public final ParceledListSlice<SharedLibraryInfo> getDeclaredSharedLibraries(
            @NonNull String packageName, @PackageManager.PackageInfoFlagsBits long flags,
            @NonNull int userId) {
        return snapshot().getDeclaredSharedLibraries(packageName, flags, userId);
    }

    /**
     * Non-Binder method, support for the backup/restore mechanism: write the default browser (etc)
     * settings in its canonical XML format.  Returns the default browser XML representation as a
     * byte array, or null if there is none.
     */
    @Override
    @Deprecated
    public final byte[] getDefaultAppsBackup(int userId) {
        return mPreferredActivityHelper.getDefaultAppsBackup(userId);
    }

    @Override
    @Deprecated
    public final String getDefaultTextClassifierPackageName() {
        return mService.mDefaultTextClassifierPackage;
    }

    @Override
    @Deprecated
    public final int getFlagsForUid(int uid) {
        return snapshot().getFlagsForUid(uid);
    }

    @Nullable
    @Override
    @Deprecated
    public final CharSequence getHarmfulAppWarning(@NonNull String packageName,
            @UserIdInt int userId) {
        return snapshot().getHarmfulAppWarning(packageName, userId);
    }

    @Override
    @Deprecated
    public final ComponentName getHomeActivities(List<ResolveInfo> allHomeCandidates) {
        final Computer snapshot = snapshot();
        if (snapshot.getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return null;
        }
        return snapshot.getHomeActivitiesAsUser(allHomeCandidates,
                UserHandle.getCallingUserId());
    }

    @Deprecated
    public final String getIncidentReportApproverPackageName() {
        return mService.mIncidentReportApproverPackage;
    }

    @Override
    @Deprecated
    public final int getInstallLocation() {
        // allow instant app access
        return android.provider.Settings.Global.getInt(mContext.getContentResolver(),
                android.provider.Settings.Global.DEFAULT_INSTALL_LOCATION,
                InstallLocationUtils.APP_INSTALL_AUTO);
    }

    @PackageManager.InstallReason
    @Override
    @Deprecated
    public final int getInstallReason(@NonNull String packageName, @UserIdInt int userId) {
        return snapshot().getInstallReason(packageName, userId);
    }

    @Override
    @Nullable
    @Deprecated
    public final InstallSourceInfo getInstallSourceInfo(@NonNull String packageName,
            @UserIdInt int userId) {
        return snapshot().getInstallSourceInfo(packageName, userId);
    }

    @Override
    @Deprecated
    public final ParceledListSlice<ApplicationInfo> getInstalledApplications(
            @PackageManager.ApplicationInfoFlagsBits long flags, int userId) {
        final int callingUid = Binder.getCallingUid();
        return new ParceledListSlice<>(
                snapshot().getInstalledApplications(flags, userId, callingUid,
                        /* forceAllowCrossUser= */ false));
    }

    @Override
    @Deprecated
    public final List<ModuleInfo> getInstalledModules(int flags) {
        return mModuleInfoProvider.getInstalledModules(flags);
    }

    @Override
    @Deprecated
    public final ParceledListSlice<PackageInfo> getInstalledPackages(
            @PackageManager.PackageInfoFlagsBits long flags, int userId) {
        return snapshot().getInstalledPackages(flags, userId);
    }

    @Nullable
    @Override
    @Deprecated
    public final String getInstallerPackageName(@NonNull String packageName) {
        return snapshot().getInstallerPackageName(packageName, UserHandle.getCallingUserId());
    }

    @Override
    @Deprecated
    public final ComponentName getInstantAppInstallerComponent() {
        final Computer snapshot = snapshot();
        if (snapshot.getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return null;
        }
        return snapshot.getInstantAppInstallerComponent();
    }

    @Override
    @Deprecated
    public final @Nullable
    ComponentName getInstantAppResolverComponent() {
        final Computer snapshot = snapshot();
        if (snapshot.getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return null;
        }
        return mService.getInstantAppResolver(snapshot);
    }

    @Override
    @Deprecated
    public final ComponentName getInstantAppResolverSettingsComponent() {
        return mInstantAppResolverSettingsComponent;
    }

    @Nullable
    @Override
    @Deprecated
    public final InstrumentationInfo getInstrumentationInfoAsUser(@NonNull ComponentName component,
            int flags, int userId) {
        return snapshot().getInstrumentationInfoAsUser(component, flags, userId);
    }

    @Override
    @Deprecated
    public final @NonNull
    ParceledListSlice<IntentFilterVerificationInfo>
    getIntentFilterVerifications(String packageName) {
        return ParceledListSlice.emptyList();
    }

    @Override
    @Deprecated
    public final int getIntentVerificationStatus(String packageName, int userId) {
        return mDomainVerificationManager.getLegacyState(packageName, userId);
    }

    @Nullable
    @Override
    @Deprecated
    public final KeySet getKeySetByAlias(@NonNull String packageName, @NonNull String alias) {
        return snapshot().getKeySetByAlias(packageName, alias);
    }

    @Override
    @Deprecated
    public final ModuleInfo getModuleInfo(String packageName,
            @PackageManager.ModuleInfoFlags int flags) {
        return mModuleInfoProvider.getModuleInfo(packageName, flags);
    }

    @Nullable
    @Override
    @Deprecated
    public final String getNameForUid(int uid) {
        return snapshot().getNameForUid(uid);
    }

    @Nullable
    @Override
    @Deprecated
    public final String[] getNamesForUids(@NonNull int[] uids) {
        return snapshot().getNamesForUids(uids);
    }

    @Override
    @Deprecated
    public final int[] getPackageGids(String packageName,
            @PackageManager.PackageInfoFlagsBits long flags, int userId) {
        return snapshot().getPackageGids(packageName, flags, userId);
    }

    @Override
    @Deprecated
    public final PackageInfo getPackageInfo(String packageName,
            @PackageManager.PackageInfoFlagsBits long flags, int userId) {
        return snapshot().getPackageInfo(packageName, flags, userId);
    }

    @Override
    @Deprecated
    public final PackageInfo getPackageInfoVersioned(VersionedPackage versionedPackage,
            @PackageManager.PackageInfoFlagsBits long flags, int userId) {
        return snapshot().getPackageInfoInternal(versionedPackage.getPackageName(),
                versionedPackage.getLongVersionCode(), flags, Binder.getCallingUid(), userId);
    }

    @Override
    @Deprecated
    public final IPackageInstaller getPackageInstaller() {
        // Return installer service for internal calls.
        if (PackageManagerServiceUtils.isSystemOrRoot()) {
            return mInstallerService;
        }
        final Computer snapshot = snapshot();
        // Return null for InstantApps.
        if (snapshot.getInstantAppPackageName(Binder.getCallingUid()) != null) {
            Log.w(PackageManagerService.TAG, "Returning null PackageInstaller for InstantApps");
            return null;
        }
        return mInstallerService;
    }

    @Override
    @Deprecated
    public final void getPackageSizeInfo(final String packageName, int userId,
            final IPackageStatsObserver observer) {
        throw new UnsupportedOperationException(
                "Shame on you for calling the hidden API getPackageSizeInfo(). Shame!");
    }

    @Override
    @Deprecated
    public final int getPackageUid(@NonNull String packageName,
            @PackageManager.PackageInfoFlagsBits long flags, @UserIdInt int userId) {
        return snapshot().getPackageUid(packageName, flags, userId);
    }

    /**
     * <em>IMPORTANT:</em> Not all packages returned by this method may be known
     * to the system. There are two conditions in which this may occur:
     * <ol>
     *   <li>The package is on adoptable storage and the device has been removed</li>
     *   <li>The package is being removed and the internal structures are partially updated</li>
     * </ol>
     * The second is an artifact of the current data structures and should be fixed. See
     * b/111075456 for one such instance.
     * This binder API is cached.  If the algorithm in this method changes,
     * or if the underlying objecs (as returned by getSettingLPr()) change
     * then the logic that invalidates the cache must be revisited.  See
     * calls to invalidateGetPackagesForUidCache() to locate the points at
     * which the cache is invalidated.
     */
    @Override
    @Deprecated
    public final String[] getPackagesForUid(int uid) {
        final int callingUid = Binder.getCallingUid();
        final int userId = UserHandle.getUserId(uid);
        snapshot().enforceCrossUserOrProfilePermission(callingUid, userId,
                /* requireFullPermission */ false,
                /* checkShell */ false, "getPackagesForUid");
        return snapshot().getPackagesForUid(uid);
    }

    @Override
    @Deprecated
    public final ParceledListSlice<PackageInfo> getPackagesHoldingPermissions(
            @NonNull String[] permissions, @PackageManager.PackageInfoFlagsBits long flags,
            @UserIdInt int userId) {
        return snapshot().getPackagesHoldingPermissions(permissions, flags, userId);
    }

    // NOTE: Can't remove due to unsupported app usage
    @Override
    @Deprecated
    public final PermissionGroupInfo getPermissionGroupInfo(String groupName, int flags) {
        return mService.getPermissionGroupInfo(groupName, flags);
    }

    @Override
    @Deprecated
    public final @NonNull
    ParceledListSlice<ApplicationInfo> getPersistentApplications(int flags) {
        final Computer snapshot = snapshot();
        if (snapshot.getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return ParceledListSlice.emptyList();
        }
        return new ParceledListSlice<>(snapshot.getPersistentApplications(isSafeMode(), flags));
    }

    @Override
    @Deprecated
    public final int getPreferredActivities(List<IntentFilter> outFilters,
            List<ComponentName> outActivities, String packageName) {
        return mPreferredActivityHelper.getPreferredActivities(snapshot(), outFilters,
                outActivities, packageName);
    }

    /**
     * Non-Binder method, support for the backup/restore mechanism: write the full set of preferred
     * activities in its canonical XML format.  Returns the XML output as a byte array, or null if
     * there is none.
     */
    @Override
    @Deprecated
    public final byte[] getPreferredActivityBackup(int userId) {
        return mPreferredActivityHelper.getPreferredActivityBackup(userId);
    }

    @Override
    @Deprecated
    public final int getPrivateFlagsForUid(int uid) {
        return snapshot().getPrivateFlagsForUid(uid);
    }

    @Override
    @Deprecated
    public final PackageManager.Property getPropertyAsUser(String propertyName, String packageName,
            String className, int userId) {
        Objects.requireNonNull(propertyName);
        Objects.requireNonNull(packageName);
        final int callingUid = Binder.getCallingUid();
        final Computer snapshot = snapshot();
        snapshot.enforceCrossUserOrProfilePermission(callingUid, userId,
                /* requireFullPermission */ false,
                /* checkShell */ false, "getPropertyAsUser");
        PackageStateInternal packageState = snapshot.getPackageStateForInstalledAndFiltered(
                packageName, callingUid, userId);
        if (packageState == null) {
            return null;
        }
        return mPackageProperty.getProperty(propertyName, packageName, className);
    }

    @Nullable
    @Override
    @Deprecated
    public final ProviderInfo getProviderInfo(@NonNull ComponentName component,
            @PackageManager.ComponentInfoFlagsBits long flags, @UserIdInt int userId) {
        return snapshot().getProviderInfo(component, flags, userId);
    }

    @Override
    @Deprecated
    public final ActivityInfo getReceiverInfo(ComponentName component,
            @PackageManager.ComponentInfoFlagsBits long flags, int userId) {
        return snapshot().getReceiverInfo(component, flags, userId);
    }

    @Override
    @Deprecated
    public final @Nullable
    String getRotationResolverPackageName() {
        return mService.ensureSystemPackageName(snapshot(),
                mService.getPackageFromComponentString(
                        R.string.config_defaultRotationResolverService));
    }

    @Nullable
    @Override
    @Deprecated
    public final ServiceInfo getServiceInfo(@NonNull ComponentName component,
            @PackageManager.ComponentInfoFlagsBits long flags, @UserIdInt int userId) {
        return snapshot().getServiceInfo(component, flags, userId);
    }

    @Override
    @Deprecated
    public final @NonNull
    String getServicesSystemSharedLibraryPackageName() {
        return mServicesExtensionPackageName;
    }

    @Override
    @Deprecated
    public final String getSetupWizardPackageName() {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Non-system caller");
        }
        return mService.mSetupWizardPackage;
    }

    @Override
    @Deprecated
    public final ParceledListSlice<SharedLibraryInfo> getSharedLibraries(String packageName,
            @PackageManager.PackageInfoFlagsBits long flags, int userId) {
        return snapshot().getSharedLibraries(packageName, flags, userId);
    }

    @Override
    @Deprecated
    public final @NonNull
    String getSharedSystemSharedLibraryPackageName() {
        return mSharedSystemSharedLibraryPackageName;
    }

    @Nullable
    @Override
    @Deprecated
    public final KeySet getSigningKeySet(@NonNull String packageName) {
        return snapshot().getSigningKeySet(packageName);
    }

    @Override
    @Deprecated
    public final String getSdkSandboxPackageName() {
        return mService.getSdkSandboxPackageName();
    }

    @Override
    @Deprecated
    public final String getSystemCaptionsServicePackageName() {
        return mService.ensureSystemPackageName(snapshot(),
                mService.getPackageFromComponentString(
                        R.string.config_defaultSystemCaptionsManagerService));
    }

    @Nullable
    @Override
    @Deprecated
    public final String[] getSystemSharedLibraryNames() {
        ArrayMap<String, String> namesAndPaths = snapshot().getSystemSharedLibraryNamesAndPaths();
        if (namesAndPaths.isEmpty()) {
            return null;
        }
        final int size = namesAndPaths.size();
        final String[] libs = new String[size];
        for (int i = 0; i < size; i++) {
            libs[i] = namesAndPaths.keyAt(i);
        }
        return libs;
    }

    @Override
    @Deprecated
    public final Map<String, String> getSystemSharedLibraryNamesAndPaths() {
        return snapshot().getSystemSharedLibraryNamesAndPaths();
    }

    @Override
    @Deprecated
    public final String getSystemTextClassifierPackageName() {
        return mService.mSystemTextClassifierPackageName;
    }

    @Override
    @Deprecated
    public final int getTargetSdkVersion(@NonNull String packageName) {
        return snapshot().getTargetSdkVersion(packageName);
    }

    @Override
    @Deprecated
    public final int getUidForSharedUser(@NonNull String sharedUserName) {
        return snapshot().getUidForSharedUser(sharedUserName);
    }

    @SuppressLint("MissingPermission")
    @Override
    @Deprecated
    public final String getWellbeingPackageName() {
        final long identity = Binder.clearCallingIdentity();
        try {
            return CollectionUtils.firstOrNull(
                    mContext.getSystemService(RoleManager.class).getRoleHolders(
                            RoleManager.ROLE_SYSTEM_WELLBEING));
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    // NOTE: Can't remove due to unsupported app usage
    @SuppressLint("MissingPermission")
    @Override
    @Deprecated
    public final void grantRuntimePermission(String packageName, String permName,
            final int userId) {
        // Because this is accessed via the package manager service AIDL,
        // go through the permission manager service AIDL
        mContext.getSystemService(PermissionManager.class)
                .grantRuntimePermission(packageName, permName, UserHandle.of(userId));
    }

    @Override
    @Deprecated
    public final boolean hasSigningCertificate(@NonNull String packageName,
            @NonNull byte[] certificate,
            @PackageManager.CertificateInputType int type) {
        return snapshot().hasSigningCertificate(packageName, certificate, type);
    }

    @Override
    @Deprecated
    public final boolean hasSystemFeature(String name, int version) {
        return mService.hasSystemFeature(name, version);
    }

    @Override
    @Deprecated
    public final boolean hasSystemUidErrors() {
        // allow instant applications
        return false;
    }

    @Override
    @Deprecated
    public final boolean hasUidSigningCertificate(int uid, @NonNull byte[] certificate,
            @PackageManager.CertificateInputType int type) {
        return snapshot().hasUidSigningCertificate(uid, certificate, type);
    }

    @Override
    @Deprecated
    public final boolean isDeviceUpgrading() {
        return mService.isDeviceUpgrading();
    }

    @Override
    @Deprecated
    public final boolean isFirstBoot() {
        return mService.isFirstBoot();
    }

    @Override
    @Deprecated
    public final boolean isInstantApp(String packageName, int userId) {
        return snapshot().isInstantApp(packageName, userId);
    }

    @Override
    @Deprecated
    public final boolean isPackageAvailable(String packageName, int userId) {
        return snapshot().isPackageAvailable(packageName, userId);
    }

    @Override
    @Deprecated
    public final boolean isPackageDeviceAdminOnAnyUser(String packageName) {
        return mService.isPackageDeviceAdminOnAnyUser(snapshot(),
                packageName);
    }

    @Override
    @Deprecated
    public final boolean isPackageSignedByKeySet(@NonNull String packageName, @NonNull KeySet ks) {
        return snapshot().isPackageSignedByKeySet(packageName, ks);
    }

    @Override
    @Deprecated
    public final boolean isPackageSignedByKeySetExactly(@NonNull String packageName,
            @NonNull KeySet ks) {
        return snapshot().isPackageSignedByKeySetExactly(packageName, ks);
    }

    @Override
    @Deprecated
    public final boolean isPackageSuspendedForUser(@NonNull String packageName,
            @UserIdInt int userId) {
        try {
            return snapshot().isPackageSuspendedForUser(packageName, userId);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalArgumentException("Unknown target package: " + packageName);
        }
    }

    @Override
    @Deprecated
    public final boolean isPackageQuarantinedForUser(@NonNull String packageName,
            @UserIdInt int userId) {
        try {
            return snapshot().isPackageQuarantinedForUser(packageName, userId);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalArgumentException("Unknown target package: " + packageName);
        }
    }

    @Override
    public final boolean isPackageStoppedForUser(@NonNull String packageName,
            @UserIdInt int userId) {
        try {
            return snapshot().isPackageStoppedForUser(packageName, userId);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalArgumentException("Unknown target package: " + packageName);
        }
    }

    @Override
    @Deprecated
    public final boolean isSafeMode() {
        // allow instant applications
        return mService.getSafeMode();
    }

    @Override
    @Deprecated
    public final boolean isStorageLow() {
        return mService.isStorageLow();
    }

    @Override
    @Deprecated
    public final boolean isUidPrivileged(int uid) {
        return snapshot().isUidPrivileged(uid);
    }

    /**
     * Ask the package manager to perform a dex-opt with the given compiler filter.
     * <p>
     * Note: exposed only for the shell command to allow moving packages explicitly to a definite
     * state.
     */
    @Override
    @Deprecated
    public final boolean performDexOptMode(String packageName,
            boolean checkProfiles, String targetCompilerFilter, boolean force,
            boolean bootComplete, String splitName) {
        final Computer snapshot = snapshot();
        if (!checkProfiles) {
            // There is no longer a flag to skip profile checking.
            Log.w(PackageManagerService.TAG, "Ignored checkProfiles=false flag");
        }
        return mDexOptHelper.performDexOptMode(
                snapshot, packageName, targetCompilerFilter, force, bootComplete, splitName);
    }

    /**
     * Ask the package manager to perform a dex-opt with the given compiler filter on the secondary
     * dex files belonging to the given package.
     * <p>
     * Note: exposed only for the shell command to allow moving packages explicitly to a definite
     * state.
     */
    @Override
    @Deprecated
    public final boolean performDexOptSecondary(String packageName, String compilerFilter,
            boolean force) {
        return mDexOptHelper.performDexOptSecondary(packageName, compilerFilter, force);
    }

    @Override
    @Deprecated
    public final @NonNull
    ParceledListSlice<ResolveInfo> queryIntentActivities(Intent intent,
            String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags, int userId) {
        try {
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "queryIntentActivities");

            return new ParceledListSlice<>(snapshot().queryIntentActivitiesInternal(intent,
                    resolvedType, flags, userId));
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    @NonNull
    @Override
    @Deprecated
    public final ParceledListSlice<ProviderInfo> queryContentProviders(@Nullable String processName,
            int uid, @PackageManager.ComponentInfoFlagsBits long flags,
            @Nullable String metaDataKey) {
        return snapshot().queryContentProviders(processName, uid, flags, metaDataKey);
    }

    @NonNull
    @Override
    @Deprecated
    public final ParceledListSlice<InstrumentationInfo> queryInstrumentationAsUser(
            @NonNull String targetPackage, int flags, int userId) {
        return snapshot().queryInstrumentationAsUser(targetPackage, flags, userId);
    }

    @Override
    @Deprecated
    public final @NonNull
    ParceledListSlice<ResolveInfo> queryIntentActivityOptions(
            ComponentName caller, Intent[] specifics, String[] specificTypes, Intent intent,
            String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags, int userId) {
        return new ParceledListSlice<>(mResolveIntentHelper.queryIntentActivityOptionsInternal(
                snapshot(), caller, specifics, specificTypes, intent, resolvedType, flags,
                userId));
    }

    @Override
    @Deprecated
    public final @NonNull
    ParceledListSlice<ResolveInfo> queryIntentContentProviders(Intent intent,
            String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags, int userId) {
        return new ParceledListSlice<>(mResolveIntentHelper.queryIntentContentProvidersInternal(
                snapshot(), intent, resolvedType, flags, userId));
    }

    @Override
    @Deprecated
    public final @NonNull
    ParceledListSlice<ResolveInfo> queryIntentReceivers(Intent intent,
            String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags, int userId) {
        return new ParceledListSlice<>(mResolveIntentHelper.queryIntentReceiversInternal(
                snapshot(), intent, resolvedType, flags, userId, Binder.getCallingUid()));
    }

    @Override
    @Deprecated
    public final @NonNull
    ParceledListSlice<ResolveInfo> queryIntentServices(Intent intent,
            String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags, int userId) {
        final int callingUid = Binder.getCallingUid();
        return new ParceledListSlice<>(snapshot().queryIntentServicesInternal(
                intent, resolvedType, flags, userId, callingUid, false /*includeInstantApps*/));
    }

    @Override
    @Deprecated
    public final void querySyncProviders(List<String> outNames, List<ProviderInfo> outInfo) {
        snapshot().querySyncProviders(isSafeMode(), outNames, outInfo);
    }

    @Override
    @Deprecated
    public final void removePermission(String permName) {
        // Because this is accessed via the package manager service AIDL,
        // go through the permission manager service AIDL
        mContext.getSystemService(PermissionManager.class).removePermission(permName);
    }

    @Override
    @Deprecated
    public final void replacePreferredActivity(IntentFilter filter, int match,
            ComponentName[] set, ComponentName activity, int userId) {
        mPreferredActivityHelper.replacePreferredActivity(snapshot(),
                new WatchedIntentFilter(filter), match, set, activity, userId);
    }

    @Override
    @Deprecated
    public final ProviderInfo resolveContentProvider(String name,
            @PackageManager.ResolveInfoFlagsBits long flags, int userId) {
        return snapshot().resolveContentProvider(name, flags, userId, Binder.getCallingUid());
    }

    @Override
    @Deprecated
    public final void resetApplicationPreferences(int userId) {
        mPreferredActivityHelper.resetApplicationPreferences(userId);
    }

    @Override
    @Deprecated
    public final ResolveInfo resolveIntent(Intent intent, String resolvedType,
            @PackageManager.ResolveInfoFlagsBits long flags, int userId) {
        return mResolveIntentHelper.resolveIntentInternal(snapshot(), intent,
                resolvedType, flags, 0 /*privateResolveFlags*/, userId, false,
                Binder.getCallingUid());
    }

    @Override
    @Deprecated
    public final ResolveInfo resolveService(Intent intent, String resolvedType,
            @PackageManager.ResolveInfoFlagsBits long flags, int userId) {
        final int callingUid = Binder.getCallingUid();
        return mResolveIntentHelper.resolveServiceInternal(snapshot(), intent,
                resolvedType, flags, userId, callingUid);
    }

    @Override
    @Deprecated
    public final void restoreDefaultApps(byte[] backup, int userId) {
        mPreferredActivityHelper.restoreDefaultApps(backup, userId);
    }

    @Override
    @Deprecated
    public final void restorePreferredActivities(byte[] backup, int userId) {
        mPreferredActivityHelper.restorePreferredActivities(backup, userId);
    }

    @Override
    @Deprecated
    public final void setHomeActivity(ComponentName comp, int userId) {
        mPreferredActivityHelper.setHomeActivity(snapshot(), comp, userId);
    }

    @Override
    @Deprecated
    public final void setLastChosenActivity(Intent intent, String resolvedType, int flags,
            IntentFilter filter, int match, ComponentName activity) {
        mPreferredActivityHelper.setLastChosenActivity(snapshot(), intent, resolvedType,
                flags, new WatchedIntentFilter(filter), match, activity);
    }

    @Override
    @Deprecated
    public final boolean updateIntentVerificationStatus(String packageName, int status,
            int userId) {
        return mDomainVerificationManager.setLegacyUserState(packageName, userId, status);
    }

    @Override
    @Deprecated
    public final void verifyIntentFilter(int id, int verificationCode, List<String> failedDomains) {
        DomainVerificationProxyV1.queueLegacyVerifyResult(mContext, mDomainVerificationConnection,
                id, verificationCode, failedDomains, Binder.getCallingUid());
    }

    @Override
    @Deprecated
    @NonNull
    public final boolean[] canPackageQuery(@NonNull String sourcePackageName,
            @NonNull String[] targetPackageNames, @UserIdInt int userId) {
        return snapshot().canPackageQuery(sourcePackageName, targetPackageNames, userId);
    }

    @Override
    @Deprecated
    public final void deletePreloadsFileCache() throws RemoteException {
        mService.deletePreloadsFileCache();
    }

    @Override
    @Deprecated
    public final void setSystemAppHiddenUntilInstalled(String packageName, boolean hidden)
            throws RemoteException {
        mService.setSystemAppHiddenUntilInstalled(snapshot(), packageName, hidden);
    }

    @Override
    @Deprecated
    public final boolean setSystemAppInstallState(String packageName,
            boolean installed, int userId) throws RemoteException {
        return mService.setSystemAppInstallState(snapshot(), packageName, installed, userId);
    }

    @Override
    @Deprecated
    public final void finishPackageInstall(int token, boolean didLaunch) throws RemoteException {
        mService.finishPackageInstall(token, didLaunch);
    }
}
