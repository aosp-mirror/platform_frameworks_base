/*
 * Copyright (C) 2019 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.backup.testutils;

import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ChangedPackages;
import android.content.pm.IDexModuleRegisterCallback;
import android.content.pm.IOnPermissionsChangeListener;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageDeleteObserver2;
import android.content.pm.IPackageInstaller;
import android.content.pm.IPackageManager;
import android.content.pm.IPackageMoveObserver;
import android.content.pm.IPackageStatsObserver;
import android.content.pm.InstrumentationInfo;
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
import android.content.pm.SuspendDialogInfo;
import android.content.pm.VerifierDeviceIdentity;
import android.content.pm.VersionedPackage;
import android.content.pm.dex.IArtManager;
import android.graphics.Bitmap;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.os.RemoteException;

import java.util.List;

/**
 * Stub for IPackageManager to use in tests.
 */
public class IPackageManagerStub implements IPackageManager {
    public static PackageInfo sPackageInfo;
    public static int sApplicationEnabledSetting = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;

    @Override
    public PackageInfo getPackageInfo(String packageName, int flags, int userId)
        throws RemoteException {
        return sPackageInfo;
    }

    @Override
    public int getApplicationEnabledSetting(String packageName, int userId) throws RemoteException {
        return sApplicationEnabledSetting;
    }

    @Override
    public void checkPackageStartable(String packageName, int userId) throws RemoteException {

    }

    @Override
    public boolean isPackageAvailable(String packageName, int userId) throws RemoteException {
        return false;
    }

    @Override
    public PackageInfo getPackageInfoVersioned(VersionedPackage versionedPackage, int flags,
        int userId) throws RemoteException {
        return null;
    }

    @Override
    public int getPackageUid(String packageName, int flags, int userId) throws RemoteException {
        return 0;
    }

    @Override
    public int[] getPackageGids(String packageName, int flags, int userId) throws RemoteException {
        return new int[0];
    }

    @Override
    public String[] currentToCanonicalPackageNames(String[] names) throws RemoteException {
        return new String[0];
    }

    @Override
    public String[] canonicalToCurrentPackageNames(String[] names) throws RemoteException {
        return new String[0];
    }

    @Override
    public PermissionInfo getPermissionInfo(String name, String packageName, int flags)
        throws RemoteException {
        return null;
    }

    @Override
    public ParceledListSlice queryPermissionsByGroup(String group, int flags)
        throws RemoteException {
        return null;
    }

    @Override
    public PermissionGroupInfo getPermissionGroupInfo(String name, int flags)
        throws RemoteException {
        return null;
    }

    @Override
    public ParceledListSlice getAllPermissionGroups(int flags) throws RemoteException {
        return null;
    }

    @Override
    public ApplicationInfo getApplicationInfo(String packageName, int flags, int userId)
        throws RemoteException {
        return null;
    }

    @Override
    public ActivityInfo getActivityInfo(ComponentName className, int flags, int userId)
        throws RemoteException {
        return null;
    }

    @Override
    public boolean activitySupportsIntent(ComponentName className, Intent intent,
        String resolvedType)
        throws RemoteException {
        return false;
    }

    @Override
    public ActivityInfo getReceiverInfo(ComponentName className, int flags, int userId)
        throws RemoteException {
        return null;
    }

    @Override
    public ServiceInfo getServiceInfo(ComponentName className, int flags, int userId)
        throws RemoteException {
        return null;
    }

    @Override
    public ProviderInfo getProviderInfo(ComponentName className, int flags, int userId)
        throws RemoteException {
        return null;
    }

    @Override
    public int checkPermission(String permName, String pkgName, int userId) throws RemoteException {
        return 0;
    }

    @Override
    public int checkUidPermission(String permName, int uid) throws RemoteException {
        return 0;
    }

    @Override
    public boolean addPermission(PermissionInfo info) throws RemoteException {
        return false;
    }

    @Override
    public void removePermission(String name) throws RemoteException {

    }

    @Override
    public void grantRuntimePermission(String packageName, String permissionName, int userId)
        throws RemoteException {

    }

    @Override
    public void revokeRuntimePermission(String packageName, String permissionName, int userId)
        throws RemoteException {

    }

    @Override
    public void resetRuntimePermissions() throws RemoteException {

    }

    @Override
    public int getPermissionFlags(String permissionName, String packageName, int userId)
        throws RemoteException {
        return 0;
    }

    @Override
    public void updatePermissionFlags(String permissionName, String packageName, int flagMask,
            int flagValues, boolean checkAdjustPolicyFlagPermission, int userId)
            throws RemoteException {

    }

    @Override
    public void updatePermissionFlagsForAllApps(int flagMask, int flagValues, int userId)
        throws RemoteException {

    }

    @Override
    public boolean shouldShowRequestPermissionRationale(String permissionName, String packageName,
        int userId) throws RemoteException {
        return false;
    }

    @Override
    public boolean isProtectedBroadcast(String actionName) throws RemoteException {
        return false;
    }

    @Override
    public int checkSignatures(String pkg1, String pkg2) throws RemoteException {
        return 0;
    }

    @Override
    public int checkUidSignatures(int uid1, int uid2) throws RemoteException {
        return 0;
    }

    @Override
    public List<String> getAllPackages() throws RemoteException {
        return null;
    }

    @Override
    public String[] getPackagesForUid(int uid) throws RemoteException {
        return new String[0];
    }

    @Override
    public String getNameForUid(int uid) throws RemoteException {
        return null;
    }

    @Override
    public String[] getNamesForUids(int[] uids) throws RemoteException {
        return new String[0];
    }

    @Override
    public int getUidForSharedUser(String sharedUserName) throws RemoteException {
        return 0;
    }

    @Override
    public int getFlagsForUid(int uid) throws RemoteException {
        return 0;
    }

    @Override
    public int getPrivateFlagsForUid(int uid) throws RemoteException {
        return 0;
    }

    @Override
    public boolean isUidPrivileged(int uid) throws RemoteException {
        return false;
    }

    @Override
    public String[] getAppOpPermissionPackages(String permissionName) throws RemoteException {
        return new String[0];
    }

    @Override
    public ResolveInfo resolveIntent(Intent intent, String resolvedType, int flags, int userId)
        throws RemoteException {
        return null;
    }

    @Override
    public ResolveInfo findPersistentPreferredActivity(Intent intent, int userId)
        throws RemoteException {
        return null;
    }

    @Override
    public boolean canForwardTo(Intent intent, String resolvedType, int sourceUserId,
        int targetUserId) throws RemoteException {
        return false;
    }

    @Override
    public ParceledListSlice queryIntentActivities(Intent intent, String resolvedType, int flags,
        int userId) throws RemoteException {
        return null;
    }

    @Override
    public ParceledListSlice queryIntentActivityOptions(ComponentName caller, Intent[] specifics,
        String[] specificTypes, Intent intent, String resolvedType, int flags, int userId)
        throws RemoteException {
        return null;
    }

    @Override
    public ParceledListSlice queryIntentReceivers(Intent intent, String resolvedType, int flags,
        int userId) throws RemoteException {
        return null;
    }

    @Override
    public ResolveInfo resolveService(Intent intent, String resolvedType, int flags, int userId)
        throws RemoteException {
        return null;
    }

    @Override
    public ParceledListSlice queryIntentServices(Intent intent, String resolvedType, int flags,
        int userId) throws RemoteException {
        return null;
    }

    @Override
    public ParceledListSlice queryIntentContentProviders(Intent intent, String resolvedType,
        int flags, int userId) throws RemoteException {
        return null;
    }

    @Override
    public ParceledListSlice getInstalledPackages(int flags, int userId) throws RemoteException {
        return null;
    }

    @Override
    public ParceledListSlice getPackagesHoldingPermissions(String[] permissions, int flags,
        int userId) throws RemoteException {
        return null;
    }

    @Override
    public ParceledListSlice getInstalledApplications(int flags, int userId)
        throws RemoteException {
        return null;
    }

    @Override
    public ParceledListSlice getPersistentApplications(int flags) throws RemoteException {
        return null;
    }

    @Override
    public ProviderInfo resolveContentProvider(String name, int flags, int userId)
        throws RemoteException {
        return null;
    }

    @Override
    public void querySyncProviders(List<String> outNames, List<ProviderInfo> outInfo)
        throws RemoteException {

    }

    @Override
    public ParceledListSlice queryContentProviders(String processName, int uid, int flags,
        String metaDataKey) throws RemoteException {
        return null;
    }

    @Override
    public InstrumentationInfo getInstrumentationInfo(ComponentName className, int flags)
        throws RemoteException {
        return null;
    }

    @Override
    public ParceledListSlice queryInstrumentation(String targetPackage, int flags)
        throws RemoteException {
        return null;
    }

    @Override
    public void finishPackageInstall(int token, boolean didLaunch) throws RemoteException {

    }

    @Override
    public void setInstallerPackageName(String targetPackage, String installerPackageName)
        throws RemoteException {

    }

    @Override
    public void setApplicationCategoryHint(String packageName, int categoryHint,
        String callerPackageName) throws RemoteException {

    }

    @Override
    public void deletePackageAsUser(String packageName, int versionCode,
        IPackageDeleteObserver observer, int userId, int flags) throws RemoteException {

    }

    @Override
    public void deletePackageVersioned(VersionedPackage versionedPackage,
        IPackageDeleteObserver2 observer, int userId, int flags) throws RemoteException {

    }

    @Override
    public String getInstallerPackageName(String packageName) throws RemoteException {
        return null;
    }

    @Override
    public void resetApplicationPreferences(int userId) throws RemoteException {

    }

    @Override
    public ResolveInfo getLastChosenActivity(Intent intent, String resolvedType, int flags)
        throws RemoteException {
        return null;
    }

    @Override
    public void setLastChosenActivity(Intent intent, String resolvedType, int flags,
        IntentFilter filter, int match, ComponentName activity) throws RemoteException {

    }

    @Override
    public void addPreferredActivity(IntentFilter filter, int match, ComponentName[] set,
        ComponentName activity, int userId) throws RemoteException {

    }

    @Override
    public void replacePreferredActivity(IntentFilter filter, int match, ComponentName[] set,
        ComponentName activity, int userId) throws RemoteException {

    }

    @Override
    public void clearPackagePreferredActivities(String packageName) throws RemoteException {

    }

    @Override
    public int getPreferredActivities(List<IntentFilter> outFilters,
        List<ComponentName> outActivities, String packageName) throws RemoteException {
        return 0;
    }

    @Override
    public void addPersistentPreferredActivity(IntentFilter filter, ComponentName activity,
        int userId) throws RemoteException {

    }

    @Override
    public void clearPackagePersistentPreferredActivities(String packageName, int userId)
        throws RemoteException {

    }

    @Override
    public void addCrossProfileIntentFilter(IntentFilter intentFilter, String ownerPackage,
        int sourceUserId, int targetUserId, int flags) throws RemoteException {

    }

    @Override
    public void clearCrossProfileIntentFilters(int sourceUserId, String ownerPackage)
        throws RemoteException {

    }

    @Override
    public String[] setDistractingPackageRestrictionsAsUser(String[] packageNames,
        int restrictionFlags, int userId) throws RemoteException {
        return new String[0];
    }

    @Override
    public String[] setPackagesSuspendedAsUser(String[] packageNames, boolean suspended,
        PersistableBundle appExtras, PersistableBundle launcherExtras, SuspendDialogInfo dialogInfo,
        String callingPackage, int userId) throws RemoteException {
        return new String[0];
    }

    @Override
    public String[] getUnsuspendablePackagesForUser(String[] packageNames, int userId)
        throws RemoteException {
        return new String[0];
    }

    @Override
    public boolean isPackageSuspendedForUser(String packageName, int userId)
        throws RemoteException {
        return false;
    }

    @Override
    public PersistableBundle getSuspendedPackageAppExtras(String packageName, int userId)
        throws RemoteException {
        return null;
    }

    @Override
    public byte[] getPreferredActivityBackup(int userId) throws RemoteException {
        return new byte[0];
    }

    @Override
    public void restorePreferredActivities(byte[] backup, int userId) throws RemoteException {

    }

    @Override
    public byte[] getDefaultAppsBackup(int userId) throws RemoteException {
        return new byte[0];
    }

    @Override
    public void restoreDefaultApps(byte[] backup, int userId) throws RemoteException {

    }

    @Override
    public byte[] getIntentFilterVerificationBackup(int userId) throws RemoteException {
        return new byte[0];
    }

    @Override
    public void restoreIntentFilterVerification(byte[] backup, int userId) throws RemoteException {

    }

    @Override
    public ComponentName getHomeActivities(List<ResolveInfo> outHomeCandidates)
        throws RemoteException {
        return null;
    }

    @Override
    public void setHomeActivity(ComponentName className, int userId) throws RemoteException {

    }

    @Override
    public void setComponentEnabledSetting(ComponentName componentName, int newState, int flags,
        int userId) throws RemoteException {

    }

    @Override
    public int getComponentEnabledSetting(ComponentName componentName, int userId)
        throws RemoteException {
        return 0;
    }

    @Override
    public void setApplicationEnabledSetting(String packageName, int newState, int flags,
        int userId,
        String callingPackage) throws RemoteException {

    }

    @Override
    public void logAppProcessStartIfNeeded(String processName, int uid, String seinfo,
        String apkFile,
        int pid) throws RemoteException {

    }

    @Override
    public void flushPackageRestrictionsAsUser(int userId) throws RemoteException {

    }

    @Override
    public void setPackageStoppedState(String packageName, boolean stopped, int userId)
        throws RemoteException {

    }

    @Override
    public void freeStorageAndNotify(String volumeUuid, long freeStorageSize, int storageFlags,
        IPackageDataObserver observer) throws RemoteException {

    }

    @Override
    public void freeStorage(String volumeUuid, long freeStorageSize, int storageFlags,
        IntentSender pi) throws RemoteException {

    }

    @Override
    public void deleteApplicationCacheFiles(String packageName, IPackageDataObserver observer)
        throws RemoteException {

    }

    @Override
    public void deleteApplicationCacheFilesAsUser(String packageName, int userId,
        IPackageDataObserver observer) throws RemoteException {

    }

    @Override
    public void clearApplicationUserData(String packageName, IPackageDataObserver observer,
        int userId) throws RemoteException {

    }

    @Override
    public void clearApplicationProfileData(String packageName) throws RemoteException {

    }

    @Override
    public void getPackageSizeInfo(String packageName, int userHandle,
        IPackageStatsObserver observer)
        throws RemoteException {

    }

    @Override
    public String[] getSystemSharedLibraryNames() throws RemoteException {
        return new String[0];
    }

    @Override
    public ParceledListSlice getSystemAvailableFeatures() throws RemoteException {
        return null;
    }

    @Override
    public boolean hasSystemFeature(String name, int version) throws RemoteException {
        return false;
    }

    @Override
    public void enterSafeMode() throws RemoteException {

    }

    @Override
    public boolean isSafeMode() throws RemoteException {
        return false;
    }

    @Override
    public void systemReady() throws RemoteException {

    }

    @Override
    public boolean hasSystemUidErrors() throws RemoteException {
        return false;
    }

    @Override
    public void performFstrimIfNeeded() throws RemoteException {

    }

    @Override
    public void updatePackagesIfNeeded() throws RemoteException {

    }

    @Override
    public void notifyPackageUse(String packageName, int reason) throws RemoteException {

    }

    @Override
    public void notifyDexLoad(String loadingPackageName, List<String> classLoadersNames,
        List<String> classPaths, String loaderIsa) throws RemoteException {

    }

    @Override
    public void registerDexModule(String packageName, String dexModulePath, boolean isSharedModule,
        IDexModuleRegisterCallback callback) throws RemoteException {

    }

    @Override
    public boolean performDexOptMode(String packageName, boolean checkProfiles,
        String targetCompilerFilter, boolean force, boolean bootComplete, String splitName)
        throws RemoteException {
        return false;
    }

    @Override
    public boolean performDexOptSecondary(String packageName, String targetCompilerFilter,
        boolean force) throws RemoteException {
        return false;
    }

    @Override
    public boolean compileLayouts(String packageName) throws RemoteException {
        return false;
    }

    @Override
    public void dumpProfiles(String packageName) throws RemoteException {

    }

    @Override
    public void forceDexOpt(String packageName) throws RemoteException {

    }

    @Override
    public boolean runBackgroundDexoptJob(List<String> packageNames) throws RemoteException {
        return false;
    }

    @Override
    public void reconcileSecondaryDexFiles(String packageName) throws RemoteException {

    }

    @Override
    public int getMoveStatus(int moveId) throws RemoteException {
        return 0;
    }

    @Override
    public void registerMoveCallback(IPackageMoveObserver callback) throws RemoteException {

    }

    @Override
    public void unregisterMoveCallback(IPackageMoveObserver callback) throws RemoteException {

    }

    @Override
    public int movePackage(String packageName, String volumeUuid) throws RemoteException {
        return 0;
    }

    @Override
    public int movePrimaryStorage(String volumeUuid) throws RemoteException {
        return 0;
    }

    @Override
    public boolean addPermissionAsync(PermissionInfo info) throws RemoteException {
        return false;
    }

    @Override
    public boolean setInstallLocation(int loc) throws RemoteException {
        return false;
    }

    @Override
    public int getInstallLocation() throws RemoteException {
        return 0;
    }

    @Override
    public int installExistingPackageAsUser(String packageName, int userId, int installFlags,
        int installReason) throws RemoteException {
        return 0;
    }

    @Override
    public void verifyPendingInstall(int id, int verificationCode) throws RemoteException {

    }

    @Override
    public void extendVerificationTimeout(int id, int verificationCodeAtTimeout,
        long millisecondsToDelay) throws RemoteException {

    }

    @Override
    public void verifyIntentFilter(int id, int verificationCode, List<String> failedDomains)
        throws RemoteException {

    }

    @Override
    public int getIntentVerificationStatus(String packageName, int userId) throws RemoteException {
        return 0;
    }

    @Override
    public boolean updateIntentVerificationStatus(String packageName, int status, int userId)
        throws RemoteException {
        return false;
    }

    @Override
    public ParceledListSlice getIntentFilterVerifications(String packageName)
        throws RemoteException {
        return null;
    }

    @Override
    public ParceledListSlice getAllIntentFilters(String packageName) throws RemoteException {
        return null;
    }

    @Override
    public boolean setDefaultBrowserPackageName(String packageName, int userId)
        throws RemoteException {
        return false;
    }

    @Override
    public String getDefaultBrowserPackageName(int userId) throws RemoteException {
        return null;
    }

    @Override
    public VerifierDeviceIdentity getVerifierDeviceIdentity() throws RemoteException {
        return null;
    }

    @Override
    public boolean isFirstBoot() throws RemoteException {
        return false;
    }

    @Override
    public boolean isOnlyCoreApps() throws RemoteException {
        return false;
    }

    @Override
    public boolean isUpgrade() throws RemoteException {
        return false;
    }

    @Override
    public void setPermissionEnforced(String permission, boolean enforced) throws RemoteException {

    }

    @Override
    public boolean isPermissionEnforced(String permission) throws RemoteException {
        return false;
    }

    @Override
    public boolean isStorageLow() throws RemoteException {
        return false;
    }

    @Override
    public boolean setApplicationHiddenSettingAsUser(String packageName, boolean hidden, int userId)
        throws RemoteException {
        return false;
    }

    @Override
    public boolean getApplicationHiddenSettingAsUser(String packageName, int userId)
        throws RemoteException {
        return false;
    }

    @Override
    public void setSystemAppHiddenUntilInstalled(String packageName, boolean hidden)
        throws RemoteException {

    }

    @Override
    public boolean setSystemAppInstallState(String packageName, boolean installed, int userId)
        throws RemoteException {
        return false;
    }

    @Override
    public IPackageInstaller getPackageInstaller() throws RemoteException {
        return null;
    }

    @Override
    public boolean setBlockUninstallForUser(String packageName, boolean blockUninstall, int userId)
        throws RemoteException {
        return false;
    }

    @Override
    public boolean getBlockUninstallForUser(String packageName, int userId) throws RemoteException {
        return false;
    }

    @Override
    public KeySet getKeySetByAlias(String packageName, String alias) throws RemoteException {
        return null;
    }

    @Override
    public KeySet getSigningKeySet(String packageName) throws RemoteException {
        return null;
    }

    @Override
    public boolean isPackageSignedByKeySet(String packageName, KeySet ks) throws RemoteException {
        return false;
    }

    @Override
    public boolean isPackageSignedByKeySetExactly(String packageName, KeySet ks)
        throws RemoteException {
        return false;
    }

    @Override
    public void addOnPermissionsChangeListener(IOnPermissionsChangeListener listener)
        throws RemoteException {

    }

    @Override
    public void removeOnPermissionsChangeListener(IOnPermissionsChangeListener listener)
        throws RemoteException {

    }

    @Override
    public void grantDefaultPermissionsToEnabledCarrierApps(String[] packageNames, int userId)
        throws RemoteException {

    }

    @Override
    public void grantDefaultPermissionsToEnabledImsServices(String[] packageNames, int userId)
        throws RemoteException {

    }

    @Override
    public void grantDefaultPermissionsToEnabledTelephonyDataServices(String[] packageNames,
        int userId) throws RemoteException {

    }

    @Override
    public void revokeDefaultPermissionsFromDisabledTelephonyDataServices(String[] packageNames,
        int userId) throws RemoteException {

    }

    @Override
    public void grantDefaultPermissionsToActiveLuiApp(String packageName, int userId)
        throws RemoteException {

    }

    @Override
    public void revokeDefaultPermissionsFromLuiApps(String[] packageNames, int userId)
        throws RemoteException {

    }

    @Override
    public boolean isPermissionRevokedByPolicy(String permission, String packageName, int userId)
        throws RemoteException {
        return false;
    }

    @Override
    public String getPermissionControllerPackageName() throws RemoteException {
        return null;
    }

    @Override
    public ParceledListSlice getInstantApps(int userId) throws RemoteException {
        return null;
    }

    @Override
    public byte[] getInstantAppCookie(String packageName, int userId) throws RemoteException {
        return new byte[0];
    }

    @Override
    public boolean setInstantAppCookie(String packageName, byte[] cookie, int userId)
        throws RemoteException {
        return false;
    }

    @Override
    public Bitmap getInstantAppIcon(String packageName, int userId) throws RemoteException {
        return null;
    }

    @Override
    public boolean isInstantApp(String packageName, int userId) throws RemoteException {
        return false;
    }

    @Override
    public boolean setRequiredForSystemUser(String packageName, boolean systemUserApp)
        throws RemoteException {
        return false;
    }

    @Override
    public void setUpdateAvailable(String packageName, boolean updateAvaialble)
        throws RemoteException {

    }

    @Override
    public String getServicesSystemSharedLibraryPackageName() throws RemoteException {
        return null;
    }

    @Override
    public String getSharedSystemSharedLibraryPackageName() throws RemoteException {
        return null;
    }

    @Override
    public ChangedPackages getChangedPackages(int sequenceNumber, int userId)
        throws RemoteException {
        return null;
    }

    @Override
    public boolean isPackageDeviceAdminOnAnyUser(String packageName) throws RemoteException {
        return false;
    }

    @Override
    public int getInstallReason(String packageName, int userId) throws RemoteException {
        return 0;
    }

    @Override
    public ParceledListSlice getSharedLibraries(String packageName, int flags, int userId)
        throws RemoteException {
        return null;
    }

    @Override
    public ParceledListSlice getDeclaredSharedLibraries(String packageName, int flags, int userId)
            throws RemoteException {
        return null;
    }

    @Override
    public boolean canRequestPackageInstalls(String packageName, int userId)
        throws RemoteException {
        return false;
    }

    @Override
    public void deletePreloadsFileCache() throws RemoteException {

    }

    @Override
    public ComponentName getInstantAppResolverComponent() throws RemoteException {
        return null;
    }

    @Override
    public ComponentName getInstantAppResolverSettingsComponent() throws RemoteException {
        return null;
    }

    @Override
    public ComponentName getInstantAppInstallerComponent() throws RemoteException {
        return null;
    }

    @Override
    public String getInstantAppAndroidId(String packageName, int userId) throws RemoteException {
        return null;
    }

    @Override
    public IArtManager getArtManager() throws RemoteException {
        return null;
    }

    @Override
    public void setHarmfulAppWarning(String packageName, CharSequence warning, int userId)
        throws RemoteException {

    }

    @Override
    public CharSequence getHarmfulAppWarning(String packageName, int userId)
        throws RemoteException {
        return null;
    }

    @Override
    public boolean hasSigningCertificate(String packageName, byte[] signingCertificate, int flags)
        throws RemoteException {
        return false;
    }

    @Override
    public boolean hasUidSigningCertificate(int uid, byte[] signingCertificate, int flags)
        throws RemoteException {
        return false;
    }

    @Override
    public String getSystemTextClassifierPackageName() throws RemoteException {
        return null;
    }

    @Override
    public String getWellbeingPackageName() throws RemoteException {
        return null;
    }

    @Override
    public String getContentCaptureServicePackageName() throws RemoteException {
        return null;
    }

    public String getIncidentReportApproverPackageName() throws RemoteException {
        return null;
    }

    @Override
    public String getAppPredictionServicePackageName() {
        return null;
    }

    @Override
    public boolean isPackageStateProtected(String packageName, int userId) throws RemoteException {
        return false;
    }

    @Override
    public void sendDeviceCustomizationReadyBroadcast() throws RemoteException {

    }

    @Override
    public List<ModuleInfo> getInstalledModules(int flags) throws RemoteException {
        return null;
    }

    @Override
    public ModuleInfo getModuleInfo(String packageName, int flags) throws RemoteException {
        return null;
    }

    @Override
    public IBinder asBinder() {
        return null;
    }
}
