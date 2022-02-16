/*
**
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package android.content.pm;

import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ChangedPackages;
import android.content.pm.InstantAppInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.IDexModuleRegisterCallback;
import android.content.pm.InstallSourceInfo;
import android.content.pm.IOnChecksumsReadyListener;
import android.content.pm.IPackageInstaller;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageDeleteObserver2;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageMoveObserver;
import android.content.pm.IPackageStatsObserver;
import android.content.pm.IntentFilterVerificationInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.KeySet;
import android.content.pm.ModuleInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.ProviderInfo;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.SuspendDialogInfo;
import android.content.pm.UserInfo;
import android.content.pm.VerifierDeviceIdentity;
import android.content.pm.VersionedPackage;
import android.content.pm.dex.IArtManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.content.IntentSender;

/**
 *  See {@link PackageManager} for documentation on most of the APIs
 *  here.
 *
 *  {@hide}
 */
interface IPackageManager {
    void checkPackageStartable(String packageName, int userId);
    @UnsupportedAppUsage(trackingBug = 171933273)
    boolean isPackageAvailable(String packageName, int userId);
    @UnsupportedAppUsage
    PackageInfo getPackageInfo(String packageName, int flags, int userId);
    PackageInfo getPackageInfoVersioned(in VersionedPackage versionedPackage,
            int flags, int userId);
    @UnsupportedAppUsage
    int getPackageUid(String packageName, int flags, int userId);
    int[] getPackageGids(String packageName, int flags, int userId);

    @UnsupportedAppUsage
    String[] currentToCanonicalPackageNames(in String[] names);
    @UnsupportedAppUsage
    String[] canonicalToCurrentPackageNames(in String[] names);

    @UnsupportedAppUsage
    ApplicationInfo getApplicationInfo(String packageName, int flags ,int userId);

    /**
     * @return the target SDK for the given package name, or -1 if it cannot be retrieved
     */
    int getTargetSdkVersion(String packageName);

    @UnsupportedAppUsage
    ActivityInfo getActivityInfo(in ComponentName className, int flags, int userId);

    boolean activitySupportsIntent(in ComponentName className, in Intent intent,
            String resolvedType);

    @UnsupportedAppUsage
    ActivityInfo getReceiverInfo(in ComponentName className, int flags, int userId);

    @UnsupportedAppUsage
    ServiceInfo getServiceInfo(in ComponentName className, int flags, int userId);

    @UnsupportedAppUsage
    ProviderInfo getProviderInfo(in ComponentName className, int flags, int userId);

    boolean isProtectedBroadcast(String actionName);

    @UnsupportedAppUsage
    int checkSignatures(String pkg1, String pkg2);

    @UnsupportedAppUsage
    int checkUidSignatures(int uid1, int uid2);

    List<String> getAllPackages();

    @UnsupportedAppUsage
    String[] getPackagesForUid(int uid);

    @UnsupportedAppUsage
    String getNameForUid(int uid);
    String[] getNamesForUids(in int[] uids);

    @UnsupportedAppUsage
    int getUidForSharedUser(String sharedUserName);

    @UnsupportedAppUsage
    int getFlagsForUid(int uid);

    int getPrivateFlagsForUid(int uid);

    @UnsupportedAppUsage
    boolean isUidPrivileged(int uid);

    @UnsupportedAppUsage
    ResolveInfo resolveIntent(in Intent intent, String resolvedType, int flags, int userId);

    ResolveInfo findPersistentPreferredActivity(in Intent intent, int userId);

    boolean canForwardTo(in Intent intent, String resolvedType, int sourceUserId, int targetUserId);

    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    ParceledListSlice queryIntentActivities(in Intent intent,
            String resolvedType, int flags, int userId);

    ParceledListSlice queryIntentActivityOptions(
            in ComponentName caller, in Intent[] specifics,
            in String[] specificTypes, in Intent intent,
            String resolvedType, int flags, int userId);

    ParceledListSlice queryIntentReceivers(in Intent intent,
            String resolvedType, int flags, int userId);

    ResolveInfo resolveService(in Intent intent,
            String resolvedType, int flags, int userId);

    ParceledListSlice queryIntentServices(in Intent intent,
            String resolvedType, int flags, int userId);

    ParceledListSlice queryIntentContentProviders(in Intent intent,
            String resolvedType, int flags, int userId);

    /**
     * This implements getInstalledPackages via a "last returned row"
     * mechanism that is not exposed in the API. This is to get around the IPC
     * limit that kicks in when flags are included that bloat up the data
     * returned.
     */
    @UnsupportedAppUsage
    ParceledListSlice getInstalledPackages(int flags, in int userId);

    /**
     * This implements getPackagesHoldingPermissions via a "last returned row"
     * mechanism that is not exposed in the API. This is to get around the IPC
     * limit that kicks in when flags are included that bloat up the data
     * returned.
     */
    ParceledListSlice getPackagesHoldingPermissions(in String[] permissions,
            int flags, int userId);

    /**
     * This implements getInstalledApplications via a "last returned row"
     * mechanism that is not exposed in the API. This is to get around the IPC
     * limit that kicks in when flags are included that bloat up the data
     * returned.
     */
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    ParceledListSlice getInstalledApplications(int flags, int userId);

    /**
     * Retrieve all applications that are marked as persistent.
     *
     * @return A List&lt;applicationInfo> containing one entry for each persistent
     *         application.
     */
    ParceledListSlice getPersistentApplications(int flags);

    ProviderInfo resolveContentProvider(String name, int flags, int userId);

    /**
     * Retrieve sync information for all content providers.
     *
     * @param outNames Filled in with a list of the root names of the content
     *                 providers that can sync.
     * @param outInfo Filled in with a list of the ProviderInfo for each
     *                name in 'outNames'.
     */
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void querySyncProviders(inout List<String> outNames,
            inout List<ProviderInfo> outInfo);

    ParceledListSlice queryContentProviders(
            String processName, int uid, int flags, String metaDataKey);

    @UnsupportedAppUsage
    InstrumentationInfo getInstrumentationInfo(
            in ComponentName className, int flags);

    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    ParceledListSlice queryInstrumentation(
            String targetPackage, int flags);

    void finishPackageInstall(int token, boolean didLaunch);

    @UnsupportedAppUsage
    void setInstallerPackageName(in String targetPackage, in String installerPackageName);

    void setApplicationCategoryHint(String packageName, int categoryHint, String callerPackageName);

    /** @deprecated rawr, don't call AIDL methods directly! */
    void deletePackageAsUser(in String packageName, int versionCode,
            IPackageDeleteObserver observer, int userId, int flags);

    /**
     * Delete a package for a specific user.
     *
     * @param versionedPackage The package to delete.
     * @param observer a callback to use to notify when the package deletion in finished.
     * @param userId the id of the user for whom to delete the package
     * @param flags - possible values: {@link #DELETE_KEEP_DATA}
     */
    void deletePackageVersioned(in VersionedPackage versionedPackage,
            IPackageDeleteObserver2 observer, int userId, int flags);

    /**
     * Delete a package for a specific user.
     *
     * @param versionedPackage The package to delete.
     * @param observer a callback to use to notify when the package deletion in finished.
     * @param userId the id of the user for whom to delete the package
     */
    void deleteExistingPackageAsUser(in VersionedPackage versionedPackage,
            IPackageDeleteObserver2 observer, int userId);

    @UnsupportedAppUsage
    String getInstallerPackageName(in String packageName);

    InstallSourceInfo getInstallSourceInfo(in String packageName);

    void resetApplicationPreferences(int userId);

    @UnsupportedAppUsage
    ResolveInfo getLastChosenActivity(in Intent intent,
            String resolvedType, int flags);

    @UnsupportedAppUsage
    void setLastChosenActivity(in Intent intent, String resolvedType, int flags,
            in IntentFilter filter, int match, in ComponentName activity);

    void addPreferredActivity(in IntentFilter filter, int match,
            in ComponentName[] set, in ComponentName activity, int userId, boolean removeExisting);

    @UnsupportedAppUsage
    void replacePreferredActivity(in IntentFilter filter, int match,
            in ComponentName[] set, in ComponentName activity, int userId);

    @UnsupportedAppUsage
    void clearPackagePreferredActivities(String packageName);

    @UnsupportedAppUsage
    int getPreferredActivities(out List<IntentFilter> outFilters,
            out List<ComponentName> outActivities, String packageName);

    void addPersistentPreferredActivity(in IntentFilter filter, in ComponentName activity, int userId);

    void clearPackagePersistentPreferredActivities(String packageName, int userId);

    void addCrossProfileIntentFilter(in IntentFilter intentFilter, String ownerPackage,
            int sourceUserId, int targetUserId, int flags);

    void clearCrossProfileIntentFilters(int sourceUserId, String ownerPackage);

    String[] setDistractingPackageRestrictionsAsUser(in String[] packageNames, int restrictionFlags,
            int userId);

    String[] setPackagesSuspendedAsUser(in String[] packageNames, boolean suspended,
            in PersistableBundle appExtras, in PersistableBundle launcherExtras,
            in SuspendDialogInfo dialogInfo, String callingPackage, int userId);

    String[] getUnsuspendablePackagesForUser(in String[] packageNames, int userId);

    boolean isPackageSuspendedForUser(String packageName, int userId);

    Bundle getSuspendedPackageAppExtras(String packageName, int userId);

    /**
     * Backup/restore support - only the system uid may use these.
     */
    byte[] getPreferredActivityBackup(int userId);
    void restorePreferredActivities(in byte[] backup, int userId);
    byte[] getDefaultAppsBackup(int userId);
    void restoreDefaultApps(in byte[] backup, int userId);
    byte[] getDomainVerificationBackup(int userId);
    void restoreDomainVerification(in byte[] backup, int userId);

    /**
     * Report the set of 'Home' activity candidates, plus (if any) which of them
     * is the current "always use this one" setting.
     */
     @UnsupportedAppUsage
     ComponentName getHomeActivities(out List<ResolveInfo> outHomeCandidates);

    void setHomeActivity(in ComponentName className, int userId);

    /**
     * Overrides the label and icon of the component specified by the component name. The component
     * must belong to the calling app.
     *
     * These changes will be reset on the next boot and whenever the package is updated.
     *
     * Only the app defined as com.android.internal.R.config_overrideComponentUiPackage is allowed
     * to call this.
     *
     * @param componentName The component name to override the label/icon of.
     * @param nonLocalizedLabel The label to be displayed.
     * @param icon The icon to be displayed.
     * @param userId The user id.
     */
    void overrideLabelAndIcon(in ComponentName componentName, String nonLocalizedLabel,
            int icon, int userId);

    /**
     * Restores the label and icon of the activity specified by the component name if either has
     * been overridden. The component must belong to the calling app.
     *
     * Only the app defined as com.android.internal.R.config_overrideComponentUiPackage is allowed
     * to call this.
     *
     * @param componentName The component name.
     * @param userId The user id.
     */
    void restoreLabelAndIcon(in ComponentName componentName, int userId);

    /**
     * As per {@link android.content.pm.PackageManager#setComponentEnabledSetting}.
     */
    @UnsupportedAppUsage
    void setComponentEnabledSetting(in ComponentName componentName,
            in int newState, in int flags, int userId);

    /**
     * As per {@link android.content.pm.PackageManager#getComponentEnabledSetting}.
     */
    @UnsupportedAppUsage
    int getComponentEnabledSetting(in ComponentName componentName, int userId);

    /**
     * As per {@link android.content.pm.PackageManager#setApplicationEnabledSetting}.
     */
    @UnsupportedAppUsage
    void setApplicationEnabledSetting(in String packageName, in int newState, int flags,
            int userId, String callingPackage);

    /**
     * As per {@link android.content.pm.PackageManager#getApplicationEnabledSetting}.
     */
    @UnsupportedAppUsage
    int getApplicationEnabledSetting(in String packageName, int userId);

    /**
     * Logs process start information (including APK hash) to the security log.
     */
    void logAppProcessStartIfNeeded(String packageName, String processName, int uid, String seinfo, String apkFile, int pid);

    /**
     * As per {@link android.content.pm.PackageManager#flushPackageRestrictionsAsUser}.
     */
    void flushPackageRestrictionsAsUser(in int userId);

    /**
     * Set whether the given package should be considered stopped, making
     * it not visible to implicit intents that filter out stopped packages.
     */
    @UnsupportedAppUsage
    void setPackageStoppedState(String packageName, boolean stopped, int userId);

    /**
     * Free storage by deleting LRU sorted list of cache files across
     * all applications. If the currently available free storage
     * on the device is greater than or equal to the requested
     * free storage, no cache files are cleared. If the currently
     * available storage on the device is less than the requested
     * free storage, some or all of the cache files across
     * all applications are deleted (based on last accessed time)
     * to increase the free storage space on the device to
     * the requested value. There is no guarantee that clearing all
     * the cache files from all applications will clear up
     * enough storage to achieve the desired value.
     * @param freeStorageSize The number of bytes of storage to be
     * freed by the system. Say if freeStorageSize is XX,
     * and the current free storage is YY,
     * if XX is less than YY, just return. if not free XX-YY number
     * of bytes if possible.
     * @param observer call back used to notify when
     * the operation is completed
     */
     void freeStorageAndNotify(in String volumeUuid, in long freeStorageSize,
             int storageFlags, IPackageDataObserver observer);

    /**
     * Free storage by deleting LRU sorted list of cache files across
     * all applications. If the currently available free storage
     * on the device is greater than or equal to the requested
     * free storage, no cache files are cleared. If the currently
     * available storage on the device is less than the requested
     * free storage, some or all of the cache files across
     * all applications are deleted (based on last accessed time)
     * to increase the free storage space on the device to
     * the requested value. There is no guarantee that clearing all
     * the cache files from all applications will clear up
     * enough storage to achieve the desired value.
     * @param freeStorageSize The number of bytes of storage to be
     * freed by the system. Say if freeStorageSize is XX,
     * and the current free storage is YY,
     * if XX is less than YY, just return. if not free XX-YY number
     * of bytes if possible.
     * @param pi IntentSender call back used to
     * notify when the operation is completed.May be null
     * to indicate that no call back is desired.
     */
     void freeStorage(in String volumeUuid, in long freeStorageSize,
             int storageFlags, in IntentSender pi);

    /**
     * Delete all the cache files in an applications cache directory
     * @param packageName The package name of the application whose cache
     * files need to be deleted
     * @param observer a callback used to notify when the deletion is finished.
     */
    @UnsupportedAppUsage
    void deleteApplicationCacheFiles(in String packageName, IPackageDataObserver observer);

    /**
     * Delete all the cache files in an applications cache directory
     * @param packageName The package name of the application whose cache
     * files need to be deleted
     * @param userId the user to delete application cache for
     * @param observer a callback used to notify when the deletion is finished.
     */
    void deleteApplicationCacheFilesAsUser(in String packageName, int userId, IPackageDataObserver observer);

    /**
     * Clear the user data directory of an application.
     * @param packageName The package name of the application whose cache
     * files need to be deleted
     * @param observer a callback used to notify when the operation is completed.
     */
    void clearApplicationUserData(in String packageName, IPackageDataObserver observer, int userId);

    /**
     * Clear the profile data of an application.
     * @param packageName The package name of the application whose profile data
     * need to be deleted
     */
    void clearApplicationProfileData(in String packageName);

   /**
     * Get package statistics including the code, data and cache size for
     * an already installed package
     * @param packageName The package name of the application
     * @param userHandle Which user the size should be retrieved for
     * @param observer a callback to use to notify when the asynchronous
     * retrieval of information is complete.
     */
    void getPackageSizeInfo(in String packageName, int userHandle, IPackageStatsObserver observer);

    /**
     * Get a list of shared libraries that are available on the
     * system.
     */
    @UnsupportedAppUsage
    String[] getSystemSharedLibraryNames();

    /**
     * Get a list of features that are available on the
     * system.
     */
    ParceledListSlice getSystemAvailableFeatures();

    boolean hasSystemFeature(String name, int version);

    void enterSafeMode();
    @UnsupportedAppUsage
    boolean isSafeMode();
    void systemReady();
    @UnsupportedAppUsage
    boolean hasSystemUidErrors();

    /**
     * Ask the package manager to fstrim the disk if needed.
     */
    void performFstrimIfNeeded();

    /**
     * Ask the package manager to update packages if needed.
     */
    void updatePackagesIfNeeded();

    /**
     * Notify the package manager that a package is going to be used and why.
     *
     * See PackageManager.NOTIFY_PACKAGE_USE_* for reasons.
     */
    oneway void notifyPackageUse(String packageName, int reason);

    /**
     * Notify the package manager that a list of dex files have been loaded.
     *
     * @param loadingPackageName the name of the package who performs the load
     * @param classLoaderContextMap a map from file paths to dex files that have been loaded to
     *     the class loader context that was used to load them.
     * @param loaderIsa the ISA of the loader process
     */
    oneway void notifyDexLoad(String loadingPackageName,
            in Map<String, String> classLoaderContextMap, String loaderIsa);

    /**
     * Register an application dex module with the package manager.
     * The package manager will keep track of the given module for future optimizations.
     *
     * Dex module optimizations will disable the classpath checking at runtime. The client bares
     * the responsibility to ensure that the static assumptions on classes in the optimized code
     * hold at runtime (e.g. there's no duplicate classes in the classpath).
     *
     * Note that the package manager already keeps track of dex modules loaded with
     * {@link dalvik.system.DexClassLoader} and {@link dalvik.system.PathClassLoader}.
     * This can be called for an eager registration.
     *
     * The call might take a while and the results will be posted on the main thread, using
     * the given callback.
     *
     * If the module is intended to be shared with other apps, make sure that the file
     * permissions allow for it.
     * If at registration time the permissions allow for others to read it, the module would
     * be marked as a shared module which might undergo a different optimization strategy.
     * (usually shared modules will generated larger optimizations artifacts,
     * taking more disk space).
     *
     * @param packageName the package name to which the dex module belongs
     * @param dexModulePath the absolute path of the dex module.
     * @param isSharedModule whether or not the module is intended to be used by other apps.
     * @param callback if not null,
     *   {@link android.content.pm.IDexModuleRegisterCallback.IDexModuleRegisterCallback#onDexModuleRegistered}
     *   will be called once the registration finishes.
     */
     oneway void registerDexModule(in String packageName, in String dexModulePath,
             in boolean isSharedModule, IDexModuleRegisterCallback callback);

    /**
     * Ask the package manager to perform a dex-opt with the given compiler filter.
     *
     * Note: exposed only for the shell command to allow moving packages explicitly to a
     *       definite state.
     */
    boolean performDexOptMode(String packageName, boolean checkProfiles,
            String targetCompilerFilter, boolean force, boolean bootComplete, String splitName);

    /**
     * Ask the package manager to perform a dex-opt with the given compiler filter on the
     * secondary dex files belonging to the given package.
     *
     * Note: exposed only for the shell command to allow moving packages explicitly to a
     *       definite state.
     */
    boolean performDexOptSecondary(String packageName,
            String targetCompilerFilter, boolean force);

    /**
     * Ask the package manager to dump profiles associated with a package.
     */
    void dumpProfiles(String packageName);

    void forceDexOpt(String packageName);

    /**
     * Execute the background dexopt job immediately on packages in packageNames.
     * If null, then execute on all packages.
     */
    boolean runBackgroundDexoptJob(in List<String> packageNames);

    /**
     * Reconcile the information we have about the secondary dex files belonging to
     * {@code packagName} and the actual dex files. For all dex files that were
     * deleted, update the internal records and delete the generated oat files.
     */
    void reconcileSecondaryDexFiles(String packageName);

    int getMoveStatus(int moveId);

    void registerMoveCallback(in IPackageMoveObserver callback);
    void unregisterMoveCallback(in IPackageMoveObserver callback);

    int movePackage(in String packageName, in String volumeUuid);
    int movePrimaryStorage(in String volumeUuid);

    boolean setInstallLocation(int loc);
    @UnsupportedAppUsage
    int getInstallLocation();

    int installExistingPackageAsUser(String packageName, int userId, int installFlags,
            int installReason, in List<String> whiteListedPermissions);

    void verifyPendingInstall(int id, int verificationCode);
    void extendVerificationTimeout(int id, int verificationCodeAtTimeout, long millisecondsToDelay);

    /** @deprecated */
    void verifyIntentFilter(int id, int verificationCode, in List<String> failedDomains);
    /** @deprecated */
    int getIntentVerificationStatus(String packageName, int userId);
    /** @deprecated */
    boolean updateIntentVerificationStatus(String packageName, int status, int userId);
    /** @deprecated */
    ParceledListSlice getIntentFilterVerifications(String packageName);
    ParceledListSlice getAllIntentFilters(String packageName);

    VerifierDeviceIdentity getVerifierDeviceIdentity();

    boolean isFirstBoot();
    boolean isOnlyCoreApps();
    boolean isDeviceUpgrading();

    /** Reflects current DeviceStorageMonitorService state */
    @UnsupportedAppUsage
    boolean isStorageLow();

    @UnsupportedAppUsage
    boolean setApplicationHiddenSettingAsUser(String packageName, boolean hidden, int userId);
    boolean getApplicationHiddenSettingAsUser(String packageName, int userId);

    void setSystemAppHiddenUntilInstalled(String packageName, boolean hidden);
    boolean setSystemAppInstallState(String packageName, boolean installed, int userId);

    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    IPackageInstaller getPackageInstaller();

    boolean setBlockUninstallForUser(String packageName, boolean blockUninstall, int userId);
    @UnsupportedAppUsage
    boolean getBlockUninstallForUser(String packageName, int userId);

    KeySet getKeySetByAlias(String packageName, String alias);
    KeySet getSigningKeySet(String packageName);
    boolean isPackageSignedByKeySet(String packageName, in KeySet ks);
    boolean isPackageSignedByKeySetExactly(String packageName, in KeySet ks);

    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    String getPermissionControllerPackageName();

    ParceledListSlice getInstantApps(int userId);
    byte[] getInstantAppCookie(String packageName, int userId);
    boolean setInstantAppCookie(String packageName, in byte[] cookie, int userId);
    Bitmap getInstantAppIcon(String packageName, int userId);
    boolean isInstantApp(String packageName, int userId);

    boolean setRequiredForSystemUser(String packageName, boolean systemUserApp);

    /**
     * Sets whether or not an update is available. Ostensibly for instant apps
     * to force exteranl resolution.
     */
    void setUpdateAvailable(String packageName, boolean updateAvaialble);

    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    String getServicesSystemSharedLibraryPackageName();
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    String getSharedSystemSharedLibraryPackageName();

    ChangedPackages getChangedPackages(int sequenceNumber, int userId);

    boolean isPackageDeviceAdminOnAnyUser(String packageName);

    int getInstallReason(String packageName, int userId);

    ParceledListSlice getSharedLibraries(in String packageName, int flags, int userId);

    ParceledListSlice getDeclaredSharedLibraries(in String packageName, int flags, int userId);

    boolean canRequestPackageInstalls(String packageName, int userId);

    void deletePreloadsFileCache();

    ComponentName getInstantAppResolverComponent();

    ComponentName getInstantAppResolverSettingsComponent();

    ComponentName getInstantAppInstallerComponent();

    String getInstantAppAndroidId(String packageName, int userId);

    IArtManager getArtManager();

    void setHarmfulAppWarning(String packageName, CharSequence warning, int userId);

    CharSequence getHarmfulAppWarning(String packageName, int userId);

    boolean hasSigningCertificate(String packageName, in byte[] signingCertificate, int flags);

    boolean hasUidSigningCertificate(int uid, in byte[] signingCertificate, int flags);

    String getDefaultTextClassifierPackageName();

    String getSystemTextClassifierPackageName();

    String getAttentionServicePackageName();

    String getRotationResolverPackageName();

    String getWellbeingPackageName();

    String getAppPredictionServicePackageName();

    String getSystemCaptionsServicePackageName();

    String getSetupWizardPackageName();

    String getIncidentReportApproverPackageName();

    String getContentCaptureServicePackageName();

    boolean isPackageStateProtected(String packageName, int userId);

    void sendDeviceCustomizationReadyBroadcast();

    List<ModuleInfo> getInstalledModules(int flags);

    ModuleInfo getModuleInfo(String packageName, int flags);

    int getRuntimePermissionsVersion(int userId);

    void setRuntimePermissionsVersion(int version, int userId);

    void notifyPackagesReplacedReceived(in String[] packages);

    void requestChecksums(in String packageName, boolean includeSplits, int optional, int required, in List trustedInstallers, in IOnChecksumsReadyListener onChecksumsReadyListener, int userId);

    //------------------------------------------------------------------------
    //
    // The following binder interfaces have been moved to IPermissionManager
    //
    //------------------------------------------------------------------------

    //------------------------------------------------------------------------
    // We need to keep these in IPackageManager for app compatibility
    //------------------------------------------------------------------------
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    String[] getAppOpPermissionPackages(String permissionName);

    @UnsupportedAppUsage
    PermissionGroupInfo getPermissionGroupInfo(String name, int flags);

    @UnsupportedAppUsage
    boolean addPermission(in PermissionInfo info);

    @UnsupportedAppUsage
    boolean addPermissionAsync(in PermissionInfo info);

    @UnsupportedAppUsage
    void removePermission(String name);

    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    int checkPermission(String permName, String pkgName, int userId);

    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void grantRuntimePermission(String packageName, String permissionName, int userId);

    //------------------------------------------------------------------------
    // We need to keep these in IPackageManager for convenience in splitting
    // out the permission manager. This should be cleaned up, but, will require
    // a large change that modifies many repos.
    //------------------------------------------------------------------------
    int checkUidPermission(String permName, int uid);

    void setMimeGroup(String packageName, String group, in List<String> mimeTypes);

    String getSplashScreenTheme(String packageName, int userId);

    void setSplashScreenTheme(String packageName, String themeName, int userId);

    List<String> getMimeGroup(String packageName, String group);

    boolean isAutoRevokeWhitelisted(String packageName);

    void grantImplicitAccess(int queryingUid, String visibleAuthority);

    IBinder getHoldLockToken();

    void holdLock(in IBinder token, in int durationMs);

    PackageManager.Property getProperty(String propertyName, String packageName, String className);
    ParceledListSlice queryProperty(String propertyName, int componentType);

    void setKeepUninstalledPackages(in List<String> packageList);
}
