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

package com.android.server.app;

import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ChangedPackages;
import android.content.pm.FeatureInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageStatsObserver;
import android.content.pm.InstantAppInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.IntentFilterVerificationInfo;
import android.content.pm.KeySet;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.VerifierDeviceIdentity;
import android.content.pm.VersionedPackage;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.UserHandle;
import android.os.storage.VolumeInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

public class MockPackageManager extends PackageManager {
    private static final String TAG = "MockPackageManager";

    private final ApplicationInfo mApplicationInfo = new ApplicationInfo();

    public MockPackageManager() {
        // Mock the ApplicationInfo, so we can treat the test as a "game".
        mApplicationInfo.category = ApplicationInfo.CATEGORY_GAME;
    }

    @Override
    public PackageInfo getPackageInfo(@NonNull String packageName, int flags)
            throws NameNotFoundException {
        return null;
    }

    @Override
    public PackageInfo getPackageInfo(@NonNull VersionedPackage versionedPackage, int flags)
            throws NameNotFoundException {
        return null;
    }

    @Override
    public PackageInfo getPackageInfoAsUser(@NonNull String packageName, int flags, int userId)
            throws NameNotFoundException {
        return null;
    }

    @Override
    public String[] currentToCanonicalPackageNames(@NonNull String[] packageNames) {
        return new String[0];
    }

    @Override
    public String[] canonicalToCurrentPackageNames(@NonNull String[] packageNames) {
        return new String[0];
    }

    @Nullable
    @Override
    public Intent getLaunchIntentForPackage(@NonNull String packageName) {
        return null;
    }

    @Nullable
    @Override
    public Intent getLeanbackLaunchIntentForPackage(@NonNull String packageName) {
        return null;
    }

    @Nullable
    @Override
    public Intent getCarLaunchIntentForPackage(@NonNull String packageName) {
        return null;
    }

    @Override
    public int[] getPackageGids(@NonNull String packageName) throws NameNotFoundException {
        return new int[0];
    }

    @Override
    public int[] getPackageGids(@NonNull String packageName, int flags)
            throws NameNotFoundException {
        return new int[0];
    }

    @Override
    public int getPackageUid(@NonNull String packageName, int flags)
            throws NameNotFoundException {
        return 0;
    }

    @Override
    public int getPackageUidAsUser(@NonNull String packageName, int userId)
            throws NameNotFoundException {
        return 0;
    }

    @Override
    public int getPackageUidAsUser(@NonNull String packageName, int flags, int userId)
            throws NameNotFoundException {
        return 0;
    }

    @Override
    public PermissionInfo getPermissionInfo(@NonNull String permName, int flags)
            throws NameNotFoundException {
        return null;
    }

    @NonNull
    @Override
    public List<PermissionInfo> queryPermissionsByGroup(@NonNull String permissionGroup,
            int flags) throws NameNotFoundException {
        return null;
    }

    @Override
    public boolean arePermissionsIndividuallyControlled() {
        return false;
    }

    @Override
    public boolean isWirelessConsentModeEnabled() {
        return false;
    }

    @NonNull
    @Override
    public PermissionGroupInfo getPermissionGroupInfo(@NonNull String groupName, int flags)
            throws NameNotFoundException {
        return null;
    }

    @NonNull
    @Override
    public List<PermissionGroupInfo> getAllPermissionGroups(int flags) {
        return null;
    }

    @NonNull
    @Override
    public ApplicationInfo getApplicationInfo(@NonNull String packageName, int flags)
            throws NameNotFoundException {
        return null;
    }

    @NonNull
    @Override
    public ApplicationInfo getApplicationInfoAsUser(@NonNull String packageName, int flags,
            int userId) throws NameNotFoundException {
        return mApplicationInfo;
    }

    @NonNull
    @Override
    public ActivityInfo getActivityInfo(@NonNull ComponentName component, int flags)
            throws NameNotFoundException {
        return null;
    }

    @NonNull
    @Override
    public ActivityInfo getReceiverInfo(@NonNull ComponentName component, int flags)
            throws NameNotFoundException {
        return null;
    }

    @NonNull
    @Override
    public ServiceInfo getServiceInfo(@NonNull ComponentName component, int flags)
            throws NameNotFoundException {
        return null;
    }

    @NonNull
    @Override
    public ProviderInfo getProviderInfo(@NonNull ComponentName component, int flags)
            throws NameNotFoundException {
        return null;
    }

    @NonNull
    @Override
    public List<PackageInfo> getInstalledPackages(int flags) {
        return null;
    }

    @NonNull
    @Override
    public List<PackageInfo> getPackagesHoldingPermissions(@NonNull String[] permissions,
            int flags) {
        return null;
    }

    @NonNull
    @Override
    public List<PackageInfo> getInstalledPackagesAsUser(int flags, int userId) {
        return null;
    }

    @Override
    public int checkPermission(@NonNull String permName, @NonNull String packageName) {
        return 0;
    }

    @Override
    public boolean isPermissionRevokedByPolicy(@NonNull String permName,
            @NonNull String packageName) {
        return false;
    }

    @Override
    public boolean addPermission(@NonNull PermissionInfo info) {
        return false;
    }

    @Override
    public boolean addPermissionAsync(@NonNull PermissionInfo info) {
        return false;
    }

    @Override
    public void removePermission(@NonNull String permName) {

    }

    @Override
    public void grantRuntimePermission(@NonNull String packageName, @NonNull String permName,
            @NonNull UserHandle user) {

    }

    @Override
    public void revokeRuntimePermission(@NonNull String packageName, @NonNull String permName,
            @NonNull UserHandle user) {

    }

    @Override
    public int getPermissionFlags(@NonNull String permName, @NonNull String packageName,
            @NonNull UserHandle user) {
        return 0;
    }

    @Override
    public void updatePermissionFlags(@NonNull String permName, @NonNull String packageName,
            int flagMask, int flagValues, @NonNull UserHandle user) {

    }

    @Override
    public boolean shouldShowRequestPermissionRationale(@NonNull String permName) {
        return false;
    }

    @Override
    public int checkSignatures(@NonNull String packageName1, @NonNull String packageName2) {
        return 0;
    }

    @Override
    public int checkSignatures(int uid1, int uid2) {
        return 0;
    }

    @Nullable
    @Override
    public String[] getPackagesForUid(int uid) {
        return new String[0];
    }

    @Nullable
    @Override
    public String getNameForUid(int uid) {
        return null;
    }

    @Nullable
    @Override
    public String[] getNamesForUids(int[] uids) {
        return new String[0];
    }

    @Override
    public int getUidForSharedUser(@NonNull String sharedUserName)
            throws NameNotFoundException {
        return 0;
    }

    @NonNull
    @Override
    public List<ApplicationInfo> getInstalledApplications(int flags) {
        return null;
    }

    @NonNull
    @Override
    public List<ApplicationInfo> getInstalledApplicationsAsUser(int flags, int userId) {
        return null;
    }

    @NonNull
    @Override
    public List<InstantAppInfo> getInstantApps() {
        return null;
    }

    @Nullable
    @Override
    public Drawable getInstantAppIcon(String packageName) {
        return null;
    }

    @Override
    public boolean isInstantApp() {
        return false;
    }

    @Override
    public boolean isInstantApp(@NonNull String packageName) {
        return false;
    }

    @Override
    public int getInstantAppCookieMaxBytes() {
        return 0;
    }

    @Override
    public int getInstantAppCookieMaxSize() {
        return 0;
    }

    @NonNull
    @Override
    public byte[] getInstantAppCookie() {
        return new byte[0];
    }

    @Override
    public void clearInstantAppCookie() {

    }

    @Override
    public void updateInstantAppCookie(@Nullable byte[] cookie) {

    }

    @Override
    public boolean setInstantAppCookie(@Nullable byte[] cookie) {
        return false;
    }

    @Nullable
    @Override
    public String[] getSystemSharedLibraryNames() {
        return new String[0];
    }

    @NonNull
    @Override
    public List<SharedLibraryInfo> getSharedLibraries(int flags) {
        return null;
    }

    @NonNull
    @Override
    public List<SharedLibraryInfo> getSharedLibrariesAsUser(int flags, int userId) {
        return null;
    }

    @NonNull
    @Override
    public String getServicesSystemSharedLibraryPackageName() {
        return null;
    }

    @NonNull
    @Override
    public String getSharedSystemSharedLibraryPackageName() {
        return null;
    }

    @Nullable
    @Override
    public ChangedPackages getChangedPackages(int sequenceNumber) {
        return null;
    }

    @NonNull
    @Override
    public FeatureInfo[] getSystemAvailableFeatures() {
        return new FeatureInfo[0];
    }

    @Override
    public boolean hasSystemFeature(@NonNull String featureName) {
        return false;
    }

    @Override
    public boolean hasSystemFeature(@NonNull String featureName, int version) {
        return false;
    }

    @Nullable
    @Override
    public ResolveInfo resolveActivity(@NonNull Intent intent, int flags) {
        return null;
    }

    @Nullable
    @Override
    public ResolveInfo resolveActivityAsUser(@NonNull Intent intent, int flags, int userId) {
        return null;
    }

    @NonNull
    @Override
    public List<ResolveInfo> queryIntentActivities(@NonNull Intent intent, int flags) {
        return null;
    }

    @NonNull
    @Override
    public List<ResolveInfo> queryIntentActivitiesAsUser(@NonNull Intent intent, int flags,
            int userId) {
        return null;
    }

    @NonNull
    @Override
    public List<ResolveInfo> queryIntentActivityOptions(@Nullable ComponentName caller,
            @Nullable Intent[] specifics, @NonNull Intent intent, int flags) {
        return null;
    }

    @NonNull
    @Override
    public List<ResolveInfo> queryBroadcastReceivers(@NonNull Intent intent, int flags) {
        return null;
    }

    @NonNull
    @Override
    public List<ResolveInfo> queryBroadcastReceiversAsUser(@NonNull Intent intent, int flags,
            int userId) {
        return null;
    }

    @Nullable
    @Override
    public ResolveInfo resolveService(@NonNull Intent intent, int flags) {
        return null;
    }

    @Nullable
    @Override
    public ResolveInfo resolveServiceAsUser(@NonNull Intent intent, int flags, int userId) {
        return null;
    }

    @NonNull
    @Override
    public List<ResolveInfo> queryIntentServices(@NonNull Intent intent, int flags) {
        return null;
    }

    @NonNull
    @Override
    public List<ResolveInfo> queryIntentServicesAsUser(@NonNull Intent intent, int flags,
            int userId) {
        return null;
    }

    @NonNull
    @Override
    public List<ResolveInfo> queryIntentContentProvidersAsUser(@NonNull Intent intent,
            int flags, int userId) {
        return null;
    }

    @NonNull
    @Override
    public List<ResolveInfo> queryIntentContentProviders(@NonNull Intent intent, int flags) {
        return null;
    }

    @Nullable
    @Override
    public ProviderInfo resolveContentProvider(@NonNull String authority, int flags) {
        return null;
    }

    @Nullable
    @Override
    public ProviderInfo resolveContentProviderAsUser(@NonNull String providerName, int flags,
            int userId) {
        return null;
    }

    @NonNull
    @Override
    public List<ProviderInfo> queryContentProviders(@Nullable String processName, int uid,
            int flags) {
        return null;
    }

    @NonNull
    @Override
    public InstrumentationInfo getInstrumentationInfo(@NonNull ComponentName className,
            int flags) throws NameNotFoundException {
        return null;
    }

    @NonNull
    @Override
    public List<InstrumentationInfo> queryInstrumentation(@NonNull String targetPackage,
            int flags) {
        return null;
    }

    @Nullable
    @Override
    public Drawable getDrawable(@NonNull String packageName, int resid,
            @Nullable ApplicationInfo appInfo) {
        return null;
    }

    @NonNull
    @Override
    public Drawable getActivityIcon(@NonNull ComponentName activityName)
            throws NameNotFoundException {
        return null;
    }

    @NonNull
    @Override
    public Drawable getActivityIcon(@NonNull Intent intent) throws NameNotFoundException {
        return null;
    }

    @Nullable
    @Override
    public Drawable getActivityBanner(@NonNull ComponentName activityName)
            throws NameNotFoundException {
        return null;
    }

    @Nullable
    @Override
    public Drawable getActivityBanner(@NonNull Intent intent) throws NameNotFoundException {
        return null;
    }

    @NonNull
    @Override
    public Drawable getDefaultActivityIcon() {
        return null;
    }

    @NonNull
    @Override
    public Drawable getApplicationIcon(@NonNull ApplicationInfo info) {
        return null;
    }

    @NonNull
    @Override
    public Drawable getApplicationIcon(@NonNull String packageName)
            throws NameNotFoundException {
        return null;
    }

    @Nullable
    @Override
    public Drawable getApplicationBanner(@NonNull ApplicationInfo info) {
        return null;
    }

    @Nullable
    @Override
    public Drawable getApplicationBanner(@NonNull String packageName)
            throws NameNotFoundException {
        return null;
    }

    @Nullable
    @Override
    public Drawable getActivityLogo(@NonNull ComponentName activityName)
            throws NameNotFoundException {
        return null;
    }

    @Nullable
    @Override
    public Drawable getActivityLogo(@NonNull Intent intent) throws NameNotFoundException {
        return null;
    }

    @Nullable
    @Override
    public Drawable getApplicationLogo(@NonNull ApplicationInfo info) {
        return null;
    }

    @Nullable
    @Override
    public Drawable getApplicationLogo(@NonNull String packageName)
            throws NameNotFoundException {
        return null;
    }

    @NonNull
    @Override
    public Drawable getUserBadgedIcon(@NonNull Drawable drawable, @NonNull UserHandle user) {
        return null;
    }

    @NonNull
    @Override
    public Drawable getUserBadgedDrawableForDensity(@NonNull Drawable drawable,
            @NonNull UserHandle user, @Nullable Rect badgeLocation, int badgeDensity) {
        return null;
    }

    @Nullable
    @Override
    public Drawable getUserBadgeForDensity(@NonNull UserHandle user, int density) {
        return null;
    }

    @Nullable
    @Override
    public Drawable getUserBadgeForDensityNoBackground(@NonNull UserHandle user, int density) {
        return null;
    }

    @NonNull
    @Override
    public CharSequence getUserBadgedLabel(@NonNull CharSequence label,
            @NonNull UserHandle user) {
        return null;
    }

    @Nullable
    @Override
    public CharSequence getText(@NonNull String packageName, int resid,
            @Nullable ApplicationInfo appInfo) {
        return null;
    }

    @Nullable
    @Override
    public XmlResourceParser getXml(@NonNull String packageName, int resid,
            @Nullable ApplicationInfo appInfo) {
        return null;
    }

    @NonNull
    @Override
    public CharSequence getApplicationLabel(@NonNull ApplicationInfo info) {
        return null;
    }

    @NonNull
    @Override
    public Resources getResourcesForActivity(@NonNull ComponentName activityName)
            throws NameNotFoundException {
        return null;
    }

    @NonNull
    @Override
    public Resources getResourcesForApplication(@NonNull ApplicationInfo app)
            throws NameNotFoundException {
        return null;
    }

    @NonNull
    @Override
    public Resources getResourcesForApplication(@NonNull String packageName)
            throws NameNotFoundException {
        return null;
    }

    @NonNull
    @Override
    public Resources getResourcesForApplicationAsUser(@NonNull String packageName, int userId)
            throws NameNotFoundException {
        return null;
    }

    @Override
    public int installExistingPackage(@NonNull String packageName)
            throws NameNotFoundException {
        return 0;
    }

    @Override
    public int installExistingPackage(@NonNull String packageName, int installReason)
            throws NameNotFoundException {
        return 0;
    }

    @Override
    public int installExistingPackageAsUser(@NonNull String packageName, int userId)
            throws NameNotFoundException {
        return 0;
    }

    @Override
    public void verifyPendingInstall(int id, int verificationCode) {

    }

    @Override
    public void extendVerificationTimeout(int id, int verificationCodeAtTimeout,
            long millisecondsToDelay) {

    }

    @Override
    public void verifyIntentFilter(int verificationId, int verificationCode,
            @NonNull List<String> failedDomains) {

    }

    @Override
    public int getIntentVerificationStatusAsUser(@NonNull String packageName, int userId) {
        return 0;
    }

    @Override
    public boolean updateIntentVerificationStatusAsUser(@NonNull String packageName, int status,
            int userId) {
        return false;
    }

    @NonNull
    @Override
    public List<IntentFilterVerificationInfo> getIntentFilterVerifications(
            @NonNull String packageName) {
        return null;
    }

    @NonNull
    @Override
    public List<IntentFilter> getAllIntentFilters(@NonNull String packageName) {
        return null;
    }

    @Nullable
    @Override
    public String getDefaultBrowserPackageNameAsUser(int userId) {
        return null;
    }

    @Override
    public boolean setDefaultBrowserPackageNameAsUser(@Nullable String packageName,
            int userId) {
        return false;
    }

    @Override
    public void setInstallerPackageName(@NonNull String targetPackage,
            @Nullable String installerPackageName) {

    }

    @Override
    public void setUpdateAvailable(@NonNull String packageName, boolean updateAvaialble) {

    }

    @Override
    public void deletePackage(@NonNull String packageName,
            @Nullable IPackageDeleteObserver observer, int flags) {

    }

    @Override
    public void deletePackageAsUser(@NonNull String packageName,
            @Nullable IPackageDeleteObserver observer, int flags, int userId) {

    }

    @Nullable
    @Override
    public String getInstallerPackageName(@NonNull String packageName) {
        return null;
    }

    @Override
    public void clearApplicationUserData(@NonNull String packageName,
            @Nullable IPackageDataObserver observer) {

    }

    @Override
    public void deleteApplicationCacheFiles(@NonNull String packageName,
            @Nullable IPackageDataObserver observer) {

    }

    @Override
    public void deleteApplicationCacheFilesAsUser(@NonNull String packageName, int userId,
            @Nullable IPackageDataObserver observer) {

    }

    @Override
    public void freeStorageAndNotify(@Nullable String volumeUuid, long freeStorageSize,
            @Nullable IPackageDataObserver observer) {

    }

    @Override
    public void freeStorage(@Nullable String volumeUuid, long freeStorageSize,
            @Nullable IntentSender pi) {

    }

    @Override
    public void getPackageSizeInfoAsUser(@NonNull String packageName, int userId,
            @Nullable IPackageStatsObserver observer) {

    }

    @Override
    public void addPackageToPreferred(@NonNull String packageName) {

    }

    @Override
    public void removePackageFromPreferred(@NonNull String packageName) {

    }

    @NonNull
    @Override
    public List<PackageInfo> getPreferredPackages(int flags) {
        return null;
    }

    @Override
    public void addPreferredActivity(@NonNull IntentFilter filter, int match,
            @Nullable ComponentName[] set, @NonNull ComponentName activity) {

    }

    @Override
    public void replacePreferredActivity(@NonNull IntentFilter filter, int match,
            @Nullable ComponentName[] set, @NonNull ComponentName activity) {

    }

    @Override
    public void clearPackagePreferredActivities(@NonNull String packageName) {

    }

    @Override
    public int getPreferredActivities(@NonNull List<IntentFilter> outFilters,
            @NonNull List<ComponentName> outActivities, @Nullable String packageName) {
        return 0;
    }

    @Nullable
    @Override
    public ComponentName getHomeActivities(@NonNull List<ResolveInfo> outActivities) {
        return null;
    }

    @Override
    public void setComponentEnabledSetting(@NonNull ComponentName componentName, int newState,
            int flags) {

    }

    @Override
    public int getComponentEnabledSetting(@NonNull ComponentName componentName) {
        return 0;
    }

    @Override
    public void setApplicationEnabledSetting(@NonNull String packageName, int newState,
            int flags) {

    }

    @Override
    public int getApplicationEnabledSetting(@NonNull String packageName) {
        return 0;
    }

    @Override
    public void flushPackageRestrictionsAsUser(int userId) {

    }

    @Override
    public boolean setApplicationHiddenSettingAsUser(@NonNull String packageName,
            boolean hidden, @NonNull UserHandle userHandle) {
        return false;
    }

    @Override
    public boolean getApplicationHiddenSettingAsUser(@NonNull String packageName,
            @NonNull UserHandle userHandle) {
        return false;
    }

    @Override
    public boolean isSafeMode() {
        return false;
    }

    @Override
    public void addOnPermissionsChangeListener(@NonNull OnPermissionsChangedListener listener) {

    }

    @Override
    public void removeOnPermissionsChangeListener(
            @NonNull OnPermissionsChangedListener listener) {

    }

    @NonNull
    @Override
    public KeySet getKeySetByAlias(@NonNull String packageName, @NonNull String alias) {
        return null;
    }

    @NonNull
    @Override
    public KeySet getSigningKeySet(@NonNull String packageName) {
        return null;
    }

    @Override
    public boolean isSignedBy(@NonNull String packageName, @NonNull KeySet ks) {
        return false;
    }

    @Override
    public boolean isSignedByExactly(@NonNull String packageName, @NonNull KeySet ks) {
        return false;
    }

    @Override
    public boolean isPackageSuspendedForUser(@NonNull String packageName, int userId) {
        return false;
    }

    @Override
    public void setApplicationCategoryHint(@NonNull String packageName, int categoryHint) {

    }

    @Override
    public int getMoveStatus(int moveId) {
        return 0;
    }

    @Override
    public void registerMoveCallback(@NonNull MoveCallback callback, @NonNull Handler handler) {

    }

    @Override
    public void unregisterMoveCallback(@NonNull MoveCallback callback) {

    }

    @Override
    public int movePackage(@NonNull String packageName, @NonNull VolumeInfo vol) {
        return 0;
    }

    @Nullable
    @Override
    public VolumeInfo getPackageCurrentVolume(@NonNull ApplicationInfo app) {
        return null;
    }

    @NonNull
    @Override
    public List<VolumeInfo> getPackageCandidateVolumes(@NonNull ApplicationInfo app) {
        return null;
    }

    @Override
    public int movePrimaryStorage(@NonNull VolumeInfo vol) {
        return 0;
    }

    @Nullable
    @Override
    public VolumeInfo getPrimaryStorageCurrentVolume() {
        return null;
    }

    @NonNull
    @Override
    public List<VolumeInfo> getPrimaryStorageCandidateVolumes() {
        return null;
    }

    @NonNull
    @Override
    public VerifierDeviceIdentity getVerifierDeviceIdentity() {
        return null;
    }

    @Override
    public boolean isUpgrade() {
        return false;
    }

    @NonNull
    @Override
    public PackageInstaller getPackageInstaller() {
        return null;
    }

    @Override
    public void addCrossProfileIntentFilter(@NonNull IntentFilter filter, int sourceUserId,
            int targetUserId, int flags) {

    }

    @Override
    public void clearCrossProfileIntentFilters(int sourceUserId) {

    }

    @NonNull
    @Override
    public Drawable loadItemIcon(@NonNull PackageItemInfo itemInfo,
            @Nullable ApplicationInfo appInfo) {
        return null;
    }

    @NonNull
    @Override
    public Drawable loadUnbadgedItemIcon(@NonNull PackageItemInfo itemInfo,
            @Nullable ApplicationInfo appInfo) {
        return null;
    }

    @Override
    public boolean isPackageAvailable(@NonNull String packageName) {
        return false;
    }

    @Override
    public int getInstallReason(@NonNull String packageName, @NonNull UserHandle user) {
        return 0;
    }

    @Override
    public boolean canRequestPackageInstalls() {
        return false;
    }

    @Nullable
    @Override
    public ComponentName getInstantAppResolverSettingsComponent() {
        return null;
    }

    @Nullable
    @Override
    public ComponentName getInstantAppInstallerComponent() {
        return null;
    }

    @Nullable
    @Override
    public String getInstantAppAndroidId(@NonNull String packageName,
            @NonNull UserHandle user) {
        return null;
    }

    @Override
    public void registerDexModule(@NonNull String dexModulePath,
            @Nullable DexModuleRegisterCallback callback) {

    }
}
