/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.pm.pkg;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureGroupInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.SigningDetails;
import android.os.Bundle;
import android.util.ArraySet;
import android.util.Pair;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.R;
import com.android.server.pm.pkg.component.ParsedActivity;
import com.android.server.pm.pkg.component.ParsedApexSystemService;
import com.android.server.pm.pkg.component.ParsedAttribution;
import com.android.server.pm.pkg.component.ParsedInstrumentation;
import com.android.server.pm.pkg.component.ParsedIntentInfo;
import com.android.server.pm.pkg.component.ParsedPermission;
import com.android.server.pm.pkg.component.ParsedPermissionGroup;
import com.android.server.pm.pkg.component.ParsedProcess;
import com.android.server.pm.pkg.component.ParsedProvider;
import com.android.server.pm.pkg.component.ParsedService;
import com.android.server.pm.pkg.component.ParsedUsesPermission;
import com.android.server.pm.pkg.parsing.ParsingPackageUtils;

import java.security.PublicKey;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Explicit interface used for consumers like mainline who need a {@link SystemApi @SystemApi} form
 * of {@link AndroidPackage}.
 *
 * @hide
 */
//@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
public interface AndroidPackage {

    /**
     * @see ApplicationInfo#areAttributionsUserVisible()
     * @see R.styleable#AndroidManifestApplication_attributionsAreUserVisible
     */
    @Nullable
    boolean areAttributionsUserVisible();

    /**
     * Set of Activities parsed from the manifest.
     * <p>
     * This contains minimal system state and does not
     * provide the same information as {@link ActivityInfo}. Effective state can be queried through
     * {@link android.content.pm.PackageManager#getActivityInfo(ComponentName, int)} or by
     * combining state from from com.android.server.pm.pkg.PackageState and
     * {@link PackageUserState}.
     *
     * @see ActivityInfo
     * @see PackageInfo#activities
     */
    @NonNull
    List<ParsedActivity> getActivities();

    /**
     * The names of packages to adopt ownership of permissions from, parsed under {@link
     * ParsingPackageUtils#TAG_ADOPT_PERMISSIONS}.
     *
     * @see R.styleable#AndroidManifestOriginalPackage_name
     */
    @NonNull
    List<String> getAdoptPermissions();

    /**
     * @see R.styleable#AndroidManifestApexSystemService
     */
    @NonNull
    List<ParsedApexSystemService> getApexSystemServices();

    /**
     * @see ApplicationInfo#appComponentFactory
     * @see R.styleable#AndroidManifestApplication_appComponentFactory
     */
    @Nullable
    String getAppComponentFactory();

    @NonNull
    List<ParsedAttribution> getAttributions();

    /**
     * @see ApplicationInfo#AUTO_REVOKE_ALLOWED
     * @see ApplicationInfo#AUTO_REVOKE_DISCOURAGED
     * @see ApplicationInfo#AUTO_REVOKE_DISALLOWED
     */
    int getAutoRevokePermissions();

    /**
     * @see ApplicationInfo#backupAgentName
     * @see R.styleable#AndroidManifestApplication_backupAgent
     */
    @Nullable
    String getBackupAgentName();

    /**
     * @see ApplicationInfo#banner
     * @see R.styleable#AndroidManifestApplication_banner
     */
    int getBanner();

    /**
     * @see ApplicationInfo#sourceDir
     * @see ApplicationInfo#getBaseCodePath
     */
    @NonNull
    String getBaseApkPath();

    /**
     * @see PackageInfo#baseRevisionCode
     */
    int getBaseRevisionCode();

    /**
     * @see ApplicationInfo#category
     * @see R.styleable#AndroidManifestApplication_appCategory
     */
    int getCategory();

    /**
     * @see ApplicationInfo#classLoaderName
     * @see R.styleable#AndroidManifestApplication_classLoader
     */
    @Nullable
    String getClassLoaderName();

    /**
     * @see ApplicationInfo#className
     * @see R.styleable#AndroidManifestApplication_name
     */
    @Nullable
    String getClassName();

    /**
     * @see ApplicationInfo#compatibleWidthLimitDp
     * @see R.styleable#AndroidManifestSupportsScreens_compatibleWidthLimitDp
     */
    int getCompatibleWidthLimitDp();

    /**
     * @see ApplicationInfo#compileSdkVersion
     * @see R.styleable#AndroidManifest_compileSdkVersion
     */
    int getCompileSdkVersion();

    /**
     * @see ApplicationInfo#compileSdkVersionCodename
     * @see R.styleable#AndroidManifest_compileSdkVersionCodename
     */
    @Nullable
    String getCompileSdkVersionCodeName();

    /**
     * @see PackageInfo#configPreferences
     * @see R.styleable#AndroidManifestUsesConfiguration
     */
    @NonNull
    List<ConfigurationInfo> getConfigPreferences();

    /**
     * @see ApplicationInfo#dataExtractionRulesRes
     * @see R.styleable#AndroidManifestApplication_dataExtractionRules
     */
    int getDataExtractionRules();

    /**
     * @see ApplicationInfo#descriptionRes
     * @see R.styleable#AndroidManifestApplication_description
     */
    int getDescriptionRes();

    /**
     * @see PackageInfo#featureGroups
     * @see R.styleable#AndroidManifestUsesFeature
     */
    @NonNull
    List<FeatureGroupInfo> getFeatureGroups();

    /**
     * @see ApplicationInfo#fullBackupContent
     * @see R.styleable#AndroidManifestApplication_fullBackupContent
     */
    int getFullBackupContent();

    /**
     * @see ApplicationInfo#getGwpAsanMode()
     * @see R.styleable#AndroidManifestApplication_gwpAsanMode
     */
    @ApplicationInfo.GwpAsanMode
    int getGwpAsanMode();

    /**
     * @see ApplicationInfo#iconRes
     * @see R.styleable#AndroidManifestApplication_icon
     */
    int getIconRes();

    /**
     * Permissions requested but not in the manifest. These may have been split or migrated from
     * previous versions/definitions.
     */
    @NonNull
    List<String> getImplicitPermissions();

    /**
     * @see ApplicationInfo#installLocation
     * @see R.styleable#AndroidManifest_installLocation
     */
    int getInstallLocation();

    /**
     * @see InstrumentationInfo
     * @see PackageInfo#instrumentation
     */
    @NonNull
    List<ParsedInstrumentation> getInstrumentations();

    /**
     * For use with {@link com.android.server.pm.KeySetManagerService}. Parsed in {@link
     * ParsingPackageUtils#TAG_KEY_SETS}.
     *
     * @see R.styleable#AndroidManifestKeySet
     * @see R.styleable#AndroidManifestPublicKey
     */
    @NonNull
    Map<String, ArraySet<PublicKey>> getKeySetMapping();

    /**
     * @see ApplicationInfo#mKnownActivityEmbeddingCerts
     * @see R.styleable#AndroidManifestApplication_knownActivityEmbeddingCerts
     */
    @SuppressWarnings("JavadocReference")
    @NonNull
    Set<String> getKnownActivityEmbeddingCerts();

    /**
     * @see ApplicationInfo#labelRes
     * @see R.styleable#AndroidManifestApplication_label
     */
    int getLabelRes();

    /**
     * @see ApplicationInfo#largestWidthLimitDp
     * @see R.styleable#AndroidManifestSupportsScreens_largestWidthLimitDp
     */
    int getLargestWidthLimitDp();

    /**
     * Library names this package is declared as, for use by other packages with "uses-library".
     *
     * @see R.styleable#AndroidManifestLibrary
     */
    @NonNull
    List<String> getLibraryNames();

    /**
     * The resource ID used to provide the application's locales configuration.
     *
     * @see R.styleable#AndroidManifestApplication_localeConfig
     */
    int getLocaleConfigRes();

    /**
     * @see ApplicationInfo#logo
     * @see R.styleable#AndroidManifestApplication_logo
     */
    int getLogo();

    /**
     * @see PackageInfo#getLongVersionCode()
     */
    long getLongVersionCode();

    /**
     * @see ApplicationInfo#manageSpaceActivityName
     * @see R.styleable#AndroidManifestApplication_manageSpaceActivity
     */
    @Nullable
    String getManageSpaceActivityName();

    /**
     * The package name as declared in the manifest, since the package can be renamed. For example,
     * static shared libs use synthetic package names.
     */
    @NonNull
    String getManifestPackageName();

    /**
     * @see ApplicationInfo#maxAspectRatio
     * @see R.styleable#AndroidManifestApplication_maxAspectRatio
     */
    float getMaxAspectRatio();

    /**
     * @see R.styleable#AndroidManifestUsesSdk_maxSdkVersion
     */
    int getMaxSdkVersion();

    /**
     * @see ApplicationInfo#getMemtagMode()
     * @see R.styleable#AndroidManifestApplication_memtagMode
     */
    @ApplicationInfo.MemtagMode
    int getMemtagMode();

    /**
     * TODO(b/135203078): Make all the Bundles immutable (and non-null by shared empty reference?)
     */
    @Nullable
    Bundle getMetaData();

    @Nullable
    Set<String> getMimeGroups();

    /**
     * @see ApplicationInfo#minAspectRatio
     * @see R.styleable#AndroidManifestApplication_minAspectRatio
     */
    float getMinAspectRatio();

    /**
     * @see R.styleable#AndroidManifestExtensionSdk
     */
    @Nullable
    SparseIntArray getMinExtensionVersions();

    /**
     * @see ApplicationInfo#minSdkVersion
     * @see R.styleable#AndroidManifestUsesSdk_minSdkVersion
     */
    int getMinSdkVersion();

    /**
     * @see ApplicationInfo#getNativeHeapZeroInitialized()
     * @see R.styleable#AndroidManifestApplication_nativeHeapZeroInitialized
     */
    @ApplicationInfo.NativeHeapZeroInitialized
    int getNativeHeapZeroInitialized();

    /**
     * @see ApplicationInfo#nativeLibraryDir
     */
    @Nullable
    String getNativeLibraryDir();

    /**
     * @see ApplicationInfo#nativeLibraryRootDir
     */
    @Nullable
    String getNativeLibraryRootDir();

    /**
     * @see ApplicationInfo#networkSecurityConfigRes
     * @see R.styleable#AndroidManifestApplication_networkSecurityConfig
     */
    int getNetworkSecurityConfigRes();

    /**
     * If {@link R.styleable#AndroidManifestApplication_label} is a string literal, this is it.
     * Otherwise, it's stored as {@link #getLabelRes()}.
     *
     * @see ApplicationInfo#nonLocalizedLabel
     * @see R.styleable#AndroidManifestApplication_label
     */
    @Nullable
    CharSequence getNonLocalizedLabel();

    /**
     * For system use to migrate from an old package name to a new one, moving over data if
     * available.
     *
     * @see R.styleable#AndroidManifestOriginalPackage}
     */
    @NonNull
    List<String> getOriginalPackages();

    /**
     * @see PackageInfo#overlayCategory
     * @see R.styleable#AndroidManifestResourceOverlay_category
     */
    @Nullable
    String getOverlayCategory();

    /**
     * @see PackageInfo#overlayPriority
     * @see R.styleable#AndroidManifestResourceOverlay_priority
     */
    int getOverlayPriority();

    /**
     * @see PackageInfo#overlayTarget
     * @see R.styleable#AndroidManifestResourceOverlay_targetPackage
     */
    @Nullable
    String getOverlayTarget();

    /**
     * @see PackageInfo#targetOverlayableName
     * @see R.styleable#AndroidManifestResourceOverlay_targetName
     */
    @Nullable
    String getOverlayTargetOverlayableName();

    /**
     * Map of overlayable name to actor name.
     */
    @NonNull
    Map<String, String> getOverlayables();

    /**
     * @see PackageInfo#packageName
     */
    String getPackageName();

    /**
     * @see ApplicationInfo#scanSourceDir
     * @see ApplicationInfo#getCodePath
     */
    @NonNull
    String getPath();

    /**
     * @see ApplicationInfo#permission
     * @see R.styleable#AndroidManifestApplication_permission
     */
    @Nullable
    String getPermission();

    /**
     * @see android.content.pm.PermissionGroupInfo
     */
    @NonNull
    List<ParsedPermissionGroup> getPermissionGroups();

    /**
     * @see PermissionInfo
     * @see PackageInfo#permissions
     */
    @NonNull
    List<ParsedPermission> getPermissions();

    /**
     * Used to determine the default preferred handler of an {@link Intent}.
     * <p>
     * Map of component className to intent info inside that component. TODO(b/135203078): Is this
     * actually used/working?
     */
    @NonNull
    List<Pair<String, ParsedIntentInfo>> getPreferredActivityFilters();

    /**
     * @see ApplicationInfo#processName
     * @see R.styleable#AndroidManifestApplication_process
     */
    @NonNull
    String getProcessName();

    /**
     * @see android.content.pm.ProcessInfo
     */
    @NonNull
    Map<String, ParsedProcess> getProcesses();

    /**
     * Returns the properties set on the application
     */
    @NonNull
    Map<String, PackageManager.Property> getProperties();

    /**
     * System protected broadcasts.
     *
     * @see R.styleable#AndroidManifestProtectedBroadcast
     */
    @NonNull
    List<String> getProtectedBroadcasts();

    /**
     * Set of {@link android.content.ContentProvider ContentProviders} parsed from the manifest.
     * <p>
     * This contains minimal system state and does not
     * provide the same information as {@link ProviderInfo}. Effective state can be queried through
     * {@link PackageManager#getProviderInfo(ComponentName, int)} or by
     * combining state from from com.android.server.pm.pkg.PackageState and
     * {@link PackageUserState}.
     *
     * @see ProviderInfo
     * @see PackageInfo#providers
     */
    @NonNull
    List<ParsedProvider> getProviders();

    /**
     * Intents that this package may query or require and thus requires visibility into.
     *
     * @see R.styleable#AndroidManifestQueriesIntent
     */
    @NonNull
    List<Intent> getQueriesIntents();

    /**
     * Other packages that this package may query or require and thus requires visibility into.
     *
     * @see R.styleable#AndroidManifestQueriesPackage
     */
    @NonNull
    List<String> getQueriesPackages();

    /**
     * Authorities that this package may query or require and thus requires visibility into.
     *
     * @see R.styleable#AndroidManifestQueriesProvider
     */
    @NonNull
    Set<String> getQueriesProviders();

    /**
     * Set of {@link android.content.BroadcastReceiver BroadcastReceivers} parsed from the manifest.
     * <p>
     * This contains minimal system state and does not
     * provide the same information as {@link ActivityInfo}. Effective state can be queried through
     * {@link PackageManager#getReceiverInfo(ComponentName, int)} or by
     * combining state from from com.android.server.pm.pkg.PackageState and
     * {@link PackageUserState}.
     * <p>
     * Since they share several attributes, receivers are parsed as {@link ParsedActivity}, even
     * though they represent different functionality.
     * <p>
     * TODO(b/135203078): Reconsider this and maybe make ParsedReceiver so it's not so confusing
     *
     * @see ActivityInfo
     * @see PackageInfo#receivers
     */
    @NonNull
    List<ParsedActivity> getReceivers();

    /**
     * @see PackageInfo#reqFeatures
     * @see R.styleable#AndroidManifestUsesFeature
     */
    @NonNull
    List<FeatureInfo> getRequestedFeatures();

    /**
     * All the permissions declared. This is an effective set, and may include permissions
     * transformed from split/migrated permissions from previous versions, so may not be exactly
     * what the package declares in its manifest.
     *
     * @see PackageInfo#requestedPermissions
     * @see R.styleable#AndroidManifestUsesPermission
     */
    @NonNull
    List<String> getRequestedPermissions();

    /**
     * @see PackageInfo#requiredAccountType
     * @see R.styleable#AndroidManifestApplication_requiredAccountType
     */
    @Nullable
    String getRequiredAccountType();

    /**
     * @see ApplicationInfo#requiresSmallestWidthDp
     * @see R.styleable#AndroidManifestSupportsScreens_requiresSmallestWidthDp
     */
    int getRequiresSmallestWidthDp();

    /**
     * Whether or not the app requested explicitly resizeable Activities. Null value means nothing
     * was explicitly requested.
     *
     * @see ApplicationInfo#PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE
     * @see ApplicationInfo#PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_UNRESIZEABLE
     */
    @Nullable
    Boolean getResizeableActivity();

    /**
     * SHA-512 hash of the only APK that can be used to update a system package.
     *
     * @see R.styleable#AndroidManifestRestrictUpdate
     */
    @Nullable
    byte[] getRestrictUpdateHash();

    /**
     * The restricted account authenticator type that is used by this application.
     *
     * @see PackageInfo#restrictedAccountType
     * @see R.styleable#AndroidManifestApplication_restrictedAccountType
     */
    @Nullable
    String getRestrictedAccountType();

    /**
     * @see ApplicationInfo#roundIconRes
     * @see R.styleable#AndroidManifestApplication_roundIcon
     */
    int getRoundIconRes();

    /**
     * @see R.styleable#AndroidManifestSdkLibrary_name
     */
    @Nullable
    String getSdkLibName();

    /**
     * @see R.styleable#AndroidManifestSdkLibrary_versionMajor
     */
    int getSdkLibVersionMajor();

    /**
     * @see ApplicationInfo#secondaryNativeLibraryDir
     */
    @Nullable
    String getSecondaryNativeLibraryDir();

    /**
     * Set of {@link android.app.Service Services} parsed from the manifest.
     * <p>
     * This contains minimal system state and does not
     * provide the same information as {@link ServiceInfo}. Effective state can be queried through
     * {@link PackageManager#getServiceInfo(ComponentName, int)} or by
     * combining state from from com.android.server.pm.pkg.PackageState and
     * {@link PackageUserState}.
     *
     * @see ServiceInfo
     * @see PackageInfo#services
     */
    @NonNull
    List<ParsedService> getServices();

    /**
     * @see PackageInfo#sharedUserId
     * @see R.styleable#AndroidManifest_sharedUserId
     */
    @Nullable
    String getSharedUserId();

    /**
     * @see PackageInfo#sharedUserLabel
     * @see R.styleable#AndroidManifest_sharedUserLabel
     */
    int getSharedUserLabel();

    /**
     * The signature data of all APKs in this package, which must be exactly the same across the
     * base and splits.
     */
    @NonNull
    SigningDetails getSigningDetails();

    /**
     * @see ApplicationInfo#splitClassLoaderNames
     * @see R.styleable#AndroidManifestApplication_classLoader
     */
    @Nullable
    String[] getSplitClassLoaderNames();

    /**
     * @see ApplicationInfo#splitSourceDirs
     * @see ApplicationInfo#getSplitCodePaths
     */
    @NonNull
    String[] getSplitCodePaths();

    /**
     * @see ApplicationInfo#splitDependencies
     */
    @NonNull
    SparseArray<int[]> getSplitDependencies();

    /**
     * Flags of any split APKs; ordered by parsed splitName
     */
    @Nullable
    int[] getSplitFlags();

    /**
     * TODO(b/135203078): Move split stuff to an inner data class
     *
     * @see ApplicationInfo#splitNames
     * @see PackageInfo#splitNames
     */
    @NonNull
    String[] getSplitNames();

    /**
     * @see PackageInfo#splitRevisionCodes
     */
    @NonNull
    int[] getSplitRevisionCodes();

    /**
     * @see R.styleable#AndroidManifestStaticLibrary_name
     */
    @Nullable
    String getStaticSharedLibName();

    /**
     * @see R.styleable#AndroidManifestStaticLibrary_version
     */
    long getStaticSharedLibVersion();

    /**
     * @see ApplicationInfo#targetSandboxVersion
     * @see R.styleable#AndroidManifest_targetSandboxVersion
     */
    int getTargetSandboxVersion();

    /**
     * @see ApplicationInfo#targetSdkVersion
     * @see R.styleable#AndroidManifestUsesSdk_targetSdkVersion
     */
    int getTargetSdkVersion();

    /**
     * @see ApplicationInfo#taskAffinity
     * @see R.styleable#AndroidManifestApplication_taskAffinity
     */
    @Nullable
    String getTaskAffinity();

    /**
     * @see ApplicationInfo#theme
     * @see R.styleable#AndroidManifestApplication_theme
     */
    int getTheme();

    /**
     * @see ApplicationInfo#uiOptions
     * @see R.styleable#AndroidManifestApplication_uiOptions
     */
    int getUiOptions();

    /**
     * This is an appId, the {@link ApplicationInfo#uid} if the user ID is
     * {@link android.os.UserHandle#SYSTEM}.
     *
     * @deprecated Use {@link PackageState#getAppId()} instead.
     */
    @Deprecated
    int getUid();

    /**
     * For use with {@link com.android.server.pm.KeySetManagerService}. Parsed in {@link
     * ParsingPackageUtils#TAG_KEY_SETS}.
     *
     * @see R.styleable#AndroidManifestUpgradeKeySet
     */
    @NonNull
    Set<String> getUpgradeKeySets();

    /**
     * @see R.styleable#AndroidManifestUsesLibrary
     */
    @NonNull
    List<String> getUsesLibraries();

    /**
     * @see R.styleable#AndroidManifestUsesNativeLibrary
     */
    @NonNull
    List<String> getUsesNativeLibraries();

    /**
     * Like {@link #getUsesLibraries()}, but marked optional by setting {@link
     * R.styleable#AndroidManifestUsesLibrary_required} to false . Application is expected to handle
     * absence manually.
     *
     * @see R.styleable#AndroidManifestUsesLibrary
     */
    @NonNull
    List<String> getUsesOptionalLibraries();

    /**
     * Like {@link #getUsesNativeLibraries()}, but marked optional by setting {@link
     * R.styleable#AndroidManifestUsesNativeLibrary_required} to false . Application is expected to
     * handle absence manually.
     *
     * @see R.styleable#AndroidManifestUsesNativeLibrary
     */
    @NonNull
    List<String> getUsesOptionalNativeLibraries();

    @NonNull
    List<ParsedUsesPermission> getUsesPermissions();

    /**
     * TODO(b/135203078): Move SDK library stuff to an inner data class
     *
     * @see R.styleable#AndroidManifestUsesSdkLibrary
     */
    @NonNull
    List<String> getUsesSdkLibraries();

    /**
     * @see R.styleable#AndroidManifestUsesSdkLibrary_certDigest
     */
    @Nullable
    String[][] getUsesSdkLibrariesCertDigests();

    /**
     * @see R.styleable#AndroidManifestUsesSdkLibrary_versionMajor
     */
    @Nullable
    long[] getUsesSdkLibrariesVersionsMajor();

    /**
     * TODO(b/135203078): Move static library stuff to an inner data class
     *
     * @see R.styleable#AndroidManifestUsesStaticLibrary
     */
    @NonNull
    List<String> getUsesStaticLibraries();

    /**
     * @see R.styleable#AndroidManifestUsesStaticLibrary_certDigest
     */
    @Nullable
    String[][] getUsesStaticLibrariesCertDigests();

    /**
     * @see R.styleable#AndroidManifestUsesStaticLibrary_version
     */
    @Nullable
    long[] getUsesStaticLibrariesVersions();

    /**
     * @see PackageInfo#versionName
     */
    @Nullable
    String getVersionName();

    /**
     * @see ApplicationInfo#volumeUuid
     */
    @Nullable
    String getVolumeUuid();

    @Nullable
    String getZygotePreloadName();

    boolean hasPreserveLegacyExternalStorage();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_EXT_REQUEST_FOREGROUND_SERVICE_EXEMPTION
     * @see R.styleable#AndroidManifestApplication_requestForegroundServiceExemption
     */
    boolean hasRequestForegroundServiceExemption();

    /**
     * @see ApplicationInfo#getRequestRawExternalStorageAccess()
     * @see R.styleable#AndroidManifestApplication_requestRawExternalStorageAccess
     */
    Boolean hasRequestRawExternalStorageAccess();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_ALLOW_AUDIO_PLAYBACK_CAPTURE
     */
    boolean isAllowAudioPlaybackCapture();

    /**
     * @see ApplicationInfo#FLAG_ALLOW_BACKUP
     */
    boolean isAllowBackup();

    /**
     * @see ApplicationInfo#FLAG_ALLOW_CLEAR_USER_DATA
     */
    boolean isAllowClearUserData();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_ALLOW_CLEAR_USER_DATA_ON_FAILED_RESTORE
     */
    boolean isAllowClearUserDataOnFailedRestore();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_ALLOW_NATIVE_HEAP_POINTER_TAGGING
     */
    boolean isAllowNativeHeapPointerTagging();

    /**
     * @see ApplicationInfo#FLAG_ALLOW_TASK_REPARENTING
     */
    boolean isAllowTaskReparenting();

    /**
     * If omitted from manifest, returns true if {@link #getTargetSdkVersion()} >= {@link
     * android.os.Build.VERSION_CODES#DONUT}.
     *
     * @see R.styleable#AndroidManifestSupportsScreens_anyDensity
     * @see ApplicationInfo#FLAG_SUPPORTS_SCREEN_DENSITIES
     */
    boolean isAnyDensity();

    boolean isApex();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_BACKUP_IN_FOREGROUND
     */
    boolean isBackupInForeground();

    /**
     * @see ApplicationInfo#FLAG_HARDWARE_ACCELERATED
     */
    boolean isBaseHardwareAccelerated();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_CANT_SAVE_STATE
     */
    boolean isCantSaveState();

    /**
     * @see PackageInfo#coreApp
     */
    boolean isCoreApp();

    /**
     * @see ApplicationInfo#crossProfile
     */
    boolean isCrossProfile();

    /**
     * @see ApplicationInfo#FLAG_DEBUGGABLE
     */
    boolean isDebuggable();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_DEFAULT_TO_DEVICE_PROTECTED_STORAGE
     */
    boolean isDefaultToDeviceProtectedStorage();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_DIRECT_BOOT_AWARE
     */
    boolean isDirectBootAware();

    /**
     * @see ApplicationInfo#enabled
     * @see R.styleable#AndroidManifestApplication_enabled
     */
    boolean isEnabled();

    /**
     * @see ApplicationInfo#FLAG_EXTERNAL_STORAGE
     */
    boolean isExternalStorage();

    /**
     * @see ApplicationInfo#FLAG_EXTRACT_NATIVE_LIBS
     */
    boolean isExtractNativeLibs();

    /**
     * @see ApplicationInfo#FLAG_FACTORY_TEST
     */
    boolean isFactoryTest();

    /**
     * @see R.styleable#AndroidManifestApplication_forceQueryable
     */
    boolean isForceQueryable();

    /**
     * @see ApplicationInfo#FLAG_FULL_BACKUP_ONLY
     */
    boolean isFullBackupOnly();

    /**
     * @see ApplicationInfo#FLAG_IS_GAME
     */
    @Deprecated
    boolean isGame();

    /**
     * @see ApplicationInfo#FLAG_HAS_CODE
     */
    boolean isHasCode();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_HAS_DOMAIN_URLS
     */
    boolean isHasDomainUrls();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_HAS_FRAGILE_USER_DATA
     */
    boolean isHasFragileUserData();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_ISOLATED_SPLIT_LOADING
     */
    boolean isIsolatedSplitLoading();

    /**
     * @see ApplicationInfo#FLAG_KILL_AFTER_RESTORE
     */
    boolean isKillAfterRestore();

    /**
     * @see ApplicationInfo#FLAG_LARGE_HEAP
     */
    boolean isLargeHeap();

    /**
     * Returns true if R.styleable#AndroidManifest_sharedUserMaxSdkVersion is set to a value
     * smaller than the current SDK version.
     *
     * @see R.styleable#AndroidManifest_sharedUserMaxSdkVersion
     */
    boolean isLeavingSharedUid();

    /**
     * @see ApplicationInfo#FLAG_MULTIARCH
     */
    boolean isMultiArch();

    /**
     * @see ApplicationInfo#nativeLibraryRootRequiresIsa
     */
    boolean isNativeLibraryRootRequiresIsa();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_ODM
     */
    boolean isOdm();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_OEM
     */
    boolean isOem();

    /**
     * @see R.styleable#AndroidManifestApplication_enableOnBackInvokedCallback
     */
    boolean isOnBackInvokedCallbackEnabled();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_IS_RESOURCE_OVERLAY
     * @see ApplicationInfo#isResourceOverlay()
     */
    boolean isOverlay();

    /**
     * @see PackageInfo#mOverlayIsStatic
     */
    boolean isOverlayIsStatic();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_PARTIALLY_DIRECT_BOOT_AWARE
     */
    boolean isPartiallyDirectBootAware();

    /**
     * @see ApplicationInfo#FLAG_PERSISTENT
     */
    boolean isPersistent();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_PRIVILEGED
     */
    boolean isPrivileged();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_PRODUCT
     */
    boolean isProduct();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_EXT_PROFILEABLE
     */
    boolean isProfileable();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_PROFILEABLE_BY_SHELL
     */
    boolean isProfileableByShell();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_REQUEST_LEGACY_EXTERNAL_STORAGE
     */
    boolean isRequestLegacyExternalStorage();

    /**
     * @see PackageInfo#requiredForAllUsers
     * @see R.styleable#AndroidManifestApplication_requiredForAllUsers
     */
    boolean isRequiredForAllUsers();

    /**
     * Whether the enabled settings of components in the application should be reset to the default,
     * when the application's user data is cleared.
     *
     * @see R.styleable#AndroidManifestApplication_resetEnabledSettingsOnAppDataCleared
     */
    boolean isResetEnabledSettingsOnAppDataCleared();

    /**
     * If omitted from manifest, returns true if {@link #getTargetSdkVersion()} >= {@link
     * android.os.Build.VERSION_CODES#DONUT}.
     *
     * @see R.styleable#AndroidManifestSupportsScreens_resizeable
     * @see ApplicationInfo#FLAG_RESIZEABLE_FOR_SCREENS
     */
    boolean isResizeable();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION
     */
    boolean isResizeableActivityViaSdkVersion();

    /**
     * @see ApplicationInfo#FLAG_RESTORE_ANY_VERSION
     */
    boolean isRestoreAnyVersion();

    /**
     * True means that this package/app contains an SDK library.
     */
    boolean isSdkLibrary();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_SIGNED_WITH_PLATFORM_KEY
     */
    boolean isSignedWithPlatformKey();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_STATIC_SHARED_LIBRARY
     */
    boolean isStaticSharedLibrary();

    /**
     * @see PackageInfo#isStub
     */
    boolean isStub();

    /**
     * If omitted from manifest, returns true if {@link #getTargetSdkVersion()} >= {@link
     * android.os.Build.VERSION_CODES#GINGERBREAD}.
     *
     * @see R.styleable#AndroidManifestSupportsScreens_xlargeScreens
     * @see ApplicationInfo#FLAG_SUPPORTS_XLARGE_SCREENS
     */
    boolean isSupportsExtraLargeScreens();

    /**
     * If omitted from manifest, returns true if {@link #getTargetSdkVersion()} >= {@link
     * android.os.Build.VERSION_CODES#DONUT}.
     *
     * @see R.styleable#AndroidManifestSupportsScreens_largeScreens
     * @see ApplicationInfo#FLAG_SUPPORTS_LARGE_SCREENS
     */
    boolean isSupportsLargeScreens();

    /**
     * If omitted from manifest, returns true.
     *
     * @see R.styleable#AndroidManifestSupportsScreens_normalScreens
     * @see ApplicationInfo#FLAG_SUPPORTS_NORMAL_SCREENS
     */
    boolean isSupportsNormalScreens();

    /**
     * @see ApplicationInfo#FLAG_SUPPORTS_RTL
     */
    boolean isSupportsRtl();

    /**
     * If omitted from manifest, returns true if {@link #getTargetSdkVersion()} >= {@link
     * android.os.Build.VERSION_CODES#DONUT}.
     *
     * @see R.styleable#AndroidManifestSupportsScreens_smallScreens
     * @see ApplicationInfo#FLAG_SUPPORTS_SMALL_SCREENS
     */
    boolean isSupportsSmallScreens();

    /**
     * @see ApplicationInfo#FLAG_SYSTEM
     */
    boolean isSystem();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_SYSTEM_EXT
     */
    boolean isSystemExt();

    /**
     * @see ApplicationInfo#FLAG_TEST_ONLY
     */
    boolean isTestOnly();

    /**
     * The install time abi override to choose 32bit abi's when multiple abi's are present. This is
     * only meaningful for multiarch applications. The use32bitAbi attribute is ignored if
     * cpuAbiOverride is also set.
     *
     * @see R.attr#use32bitAbi
     */
    boolean isUse32BitAbi();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_USE_EMBEDDED_DEX
     */
    boolean isUseEmbeddedDex();

    /**
     * @see ApplicationInfo#FLAG_USES_CLEARTEXT_TRAFFIC
     */
    boolean isUsesCleartextTraffic();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_USES_NON_SDK_API
     */
    boolean isUsesNonSdkApi();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_VENDOR
     */
    boolean isVendor();

    /**
     * Set if the any of components are visible to instant applications.
     *
     * @see R.styleable#AndroidManifestActivity_visibleToInstantApps
     * @see R.styleable#AndroidManifestProvider_visibleToInstantApps
     * @see R.styleable#AndroidManifestService_visibleToInstantApps
     */
    boolean isVisibleToInstantApps();

    /**
     * @see ApplicationInfo#FLAG_VM_SAFE_MODE
     */
    boolean isVmSafeMode();
}
