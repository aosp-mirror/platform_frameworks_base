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

package android.content.pm.parsing;

import android.annotation.Nullable;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureGroupInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageParser;
import android.content.pm.PackageUserState;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.parsing.ComponentParseUtils.ParsedActivity;
import android.content.pm.parsing.ComponentParseUtils.ParsedActivityIntentInfo;
import android.content.pm.parsing.ComponentParseUtils.ParsedInstrumentation;
import android.content.pm.parsing.ComponentParseUtils.ParsedPermission;
import android.content.pm.parsing.ComponentParseUtils.ParsedPermissionGroup;
import android.content.pm.parsing.ComponentParseUtils.ParsedProvider;
import android.content.pm.parsing.ComponentParseUtils.ParsedService;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArraySet;
import android.util.SparseArray;

import java.security.PublicKey;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The last state of a package during parsing/install before it is available in
 * {@link com.android.server.pm.PackageManagerService#mPackages}.
 *
 * It is the responsibility of the caller to understand what data is available at what step of the
 * parsing or install process.
 *
 * TODO(b/135203078): Nullability annotations
 * TODO(b/135203078): Remove get/setAppInfo differences
 *
 * @hide
 */
public interface AndroidPackage extends Parcelable {

    /**
     * This will eventually be removed. Avoid calling this at all costs.
     */
    @Deprecated
    AndroidPackageWrite mutate();

    boolean canHaveOatDir();

    boolean cantSaveState();

    List<String> getAdoptPermissions();

    List<String> getAllCodePaths();

    List<String> getAllCodePathsExcludingResourceOnly();

    String getAppComponentFactory();

    /**
     * TODO(b/135203078): Use non-AppInfo method
     * @deprecated use {@link #getClassLoaderName()}
     */
    @Deprecated
    String getAppInfoClassLoaderName();

    /**
     * TODO(b/135203078): Use non-AppInfo method
     * @deprecated use {@link #getCodePath()}
     */
    @Deprecated
    String getAppInfoCodePath();

    /**
     * TODO(b/135203078): Use non-AppInfo method
     * @deprecated use {@link #getName()}
     */
    @Deprecated
    String getAppInfoName();

    /**
     * TODO(b/135203078): Use non-AppInfo method
     * @deprecated use {@link #getPackageName()}
     */
    @Deprecated
    String getAppInfoPackageName();

    /**
     * TODO(b/135203078): Use non-AppInfo method
     * @deprecated use {@link #getProcessName()}
     */
    @Deprecated
    String getAppInfoProcessName();

    /**
     * TODO(b/135203078): Use non-AppInfo method
     * @deprecated use {@link #getCodePath()}
     */
    @Deprecated
    String getAppInfoResourcePath();

    Bundle getAppMetaData();

    /**
     * TODO(b/135203078): Use non-AppInfo method
     * @deprecated use {@link #getVolumeUuid()}
     */
    @Deprecated
    String getApplicationInfoVolumeUuid();

    String getBackupAgentName();

    int getBanner();

    String getBaseCodePath();

    int getBaseRevisionCode();

    int getCategory();

    String getClassLoaderName();

    String getClassName();

    String getCodePath();

    int getCompatibleWidthLimitDp();

    int getCompileSdkVersion();

    String getCompileSdkVersionCodeName();

    @Nullable
    List<ConfigurationInfo> getConfigPreferences();

    String getCpuAbiOverride();

    String getCredentialProtectedDataDir();

    String getDataDir();

    int getDescriptionRes();

    String getDeviceProtectedDataDir();

    List<FeatureGroupInfo> getFeatureGroups();

    int getFlags();

    int getFullBackupContent();

    int getHiddenApiEnforcementPolicy();

    int getIcon();

    int getIconRes();

    List<String> getImplicitPermissions();

    int getInstallLocation();

    Map<String, ArraySet<PublicKey>> getKeySetMapping();

    int getLabelRes();

    int getLargestWidthLimitDp();

    long[] getLastPackageUsageTimeInMills();

    long getLatestForegroundPackageUseTimeInMills();

    long getLatestPackageUseTimeInMills();

    List<String> getLibraryNames();

    int getLogo();

    long getLongVersionCode();

    String getManageSpaceActivityName();

    String getManifestPackageName();

    float getMaxAspectRatio();

    Bundle getMetaData(); // TODO(b/135203078): Make all the Bundles immutable

    float getMinAspectRatio();

    int getMinSdkVersion();

    String getName();

    String getNativeLibraryDir();

    String getNativeLibraryRootDir();

    int getNetworkSecurityConfigRes();

    CharSequence getNonLocalizedLabel();

    @Nullable
    List<String> getOriginalPackages();

    String getOverlayCategory();

    int getOverlayPriority();

    String getOverlayTarget();

    String getOverlayTargetName();

    // TODO(b/135203078): Does this and getAppInfoPackageName have to be separate methods?
    //  The refactor makes them the same value with no known consequences, so should be redundant.
    String getPackageName();

    @Nullable
    List<ParsedActivity> getActivities();

    @Nullable
    List<ParsedInstrumentation> getInstrumentations();

    @Nullable
    List<ParsedPermissionGroup> getPermissionGroups();

    @Nullable
    List<ParsedPermission> getPermissions();

    @Nullable
    List<ParsedProvider> getProviders();

    @Nullable
    List<ParsedActivity> getReceivers();

    @Nullable
    List<ParsedService> getServices();

    String getPermission();

    @Nullable
    List<ParsedActivityIntentInfo> getPreferredActivityFilters();

    int getPreferredOrder();

    String getPrimaryCpuAbi();

    int getPrivateFlags();

    String getProcessName();

    @Nullable
    List<String> getProtectedBroadcasts();

    String getPublicSourceDir();

    List<Intent> getQueriesIntents();

    List<String> getQueriesPackages();

    String getRealPackage();

    // TODO(b/135203078): Rename to getRequiredFeatures? Somewhat ambiguous whether "Req" is
    //  required or requested.
    @Nullable
    List<FeatureInfo> getReqFeatures();

    List<String> getRequestedPermissions();

    String getRequiredAccountType();

    int getRequiresSmallestWidthDp();

    byte[] getRestrictUpdateHash();

    String getRestrictedAccountType();

    int getRoundIconRes();

    String getScanPublicSourceDir();

    String getScanSourceDir();

    String getSeInfo();

    String getSeInfoUser();

    String getSecondaryCpuAbi();

    String getSecondaryNativeLibraryDir();

    String getSharedUserId();

    int getSharedUserLabel();

    PackageParser.SigningDetails getSigningDetails();

    String[] getSplitClassLoaderNames();

    @Nullable
    String[] getSplitCodePaths();

    @Nullable
    SparseArray<int[]> getSplitDependencies();

    int[] getSplitFlags();

    String[] getSplitNames();

    String[] getSplitPublicSourceDirs();

    int[] getSplitRevisionCodes();

    String getStaticSharedLibName();

    long getStaticSharedLibVersion();

    // TODO(b/135203078): Return String directly
    UUID getStorageUuid();

    int getTargetSandboxVersion();

    int getTargetSdkVersion();

    String getTaskAffinity();

    int getTheme();

    int getUiOptions();

    int getUid();

    Set<String> getUpgradeKeySets();

    @Nullable
    List<String> getUsesLibraries();

    @Nullable
    String[] getUsesLibraryFiles();

    List<SharedLibraryInfo> getUsesLibraryInfos();

    @Nullable
    List<String> getUsesOptionalLibraries();

    @Nullable
    List<String> getUsesStaticLibraries();

    @Nullable
    String[][] getUsesStaticLibrariesCertDigests();

    @Nullable
    long[] getUsesStaticLibrariesVersions();

    int getVersionCode();

    int getVersionCodeMajor();

    String getVersionName();

    String getVolumeUuid();

    String getZygotePreloadName();

    boolean hasComponentClassName(String className);

    // App Info

    boolean hasRequestedLegacyExternalStorage();

    boolean isBaseHardwareAccelerated();

    boolean isCoreApp();

    boolean isDefaultToDeviceProtectedStorage();

    boolean isDirectBootAware();

    boolean isEmbeddedDexUsed();

    boolean isEnabled();

    boolean isEncryptionAware();

    boolean isExternal();

    boolean isForceQueryable();

    boolean isForwardLocked();

    boolean isHiddenUntilInstalled();

    boolean isInstantApp();

    boolean isInternal();

    boolean isLibrary();

    // TODO(b/135203078): Should probably be in a utility class
    boolean isMatch(int flags);

    boolean isNativeLibraryRootRequiresIsa();

    boolean isOem();

    boolean isOverlayIsStatic();

    boolean isPrivileged();

    boolean isProduct();

    boolean isProfileableByShell();

    boolean isRequiredForAllUsers();

    boolean isStaticSharedLibrary();

    boolean isStub();

    boolean isSystem(); // TODO(b/135203078): Collapse with isSystemApp, should be exactly the same.

    boolean isSystemApp();

    boolean isSystemExt();

    boolean isUpdatedSystemApp();

    boolean isUse32BitAbi();

    boolean isVendor();

    boolean isVisibleToInstantApps();

    List<String> makeListAllCodePaths(); // TODO(b/135203078): Collapse with getAllCodePaths

    boolean requestsIsolatedSplitLoading();

    /**
     * Generates an {@link ApplicationInfo} object with only the data available in this object.
     *
     * This does not contain any system or user state data, and should be avoided. Prefer
     * {@link PackageInfoUtils#generateApplicationInfo(AndroidPackage, int, PackageUserState, int)}.
     */
    ApplicationInfo toAppInfoWithoutState();

    Creator<PackageImpl> CREATOR = new Creator<PackageImpl>() {
        @Override
        public PackageImpl createFromParcel(Parcel source) {
            return new PackageImpl(source);
        }

        @Override
        public PackageImpl[] newArray(int size) {
            return new PackageImpl[size];
        }
    };
}
