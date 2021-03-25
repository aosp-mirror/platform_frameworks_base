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

package android.content.pm.parsing;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureGroupInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.Property;
import android.content.pm.PackageParser;
import android.content.pm.ServiceInfo;
import android.content.pm.parsing.component.ParsedActivity;
import android.content.pm.parsing.component.ParsedAttribution;
import android.content.pm.parsing.component.ParsedInstrumentation;
import android.content.pm.parsing.component.ParsedIntentInfo;
import android.content.pm.parsing.component.ParsedPermission;
import android.content.pm.parsing.component.ParsedPermissionGroup;
import android.content.pm.parsing.component.ParsedProcess;
import android.content.pm.parsing.component.ParsedProvider;
import android.content.pm.parsing.component.ParsedService;
import android.content.pm.parsing.component.ParsedUsesPermission;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.ArraySet;
import android.util.Pair;
import android.util.SparseArray;
import android.util.SparseIntArray;

import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything written by {@link ParsingPackage} and readable back.
 *
 * @hide
 */
@SuppressWarnings("UnusedReturnValue")
public interface ParsingPackageRead extends Parcelable {

    /**
     * @see ActivityInfo
     * @see PackageInfo#activities
     */
    @NonNull
    List<ParsedActivity> getActivities();

    /**
     * The names of packages to adopt ownership of permissions from, parsed under
     * {@link ParsingPackageUtils#TAG_ADOPT_PERMISSIONS}.
     * @see R.styleable#AndroidManifestOriginalPackage_name
     */
    @NonNull
    List<String> getAdoptPermissions();

    /**
     * @see PackageInfo#configPreferences
     * @see R.styleable#AndroidManifestUsesConfiguration
     */
    @NonNull
    List<ConfigurationInfo> getConfigPreferences();

    @NonNull
    List<ParsedAttribution> getAttributions();

    /**
     * @see PackageInfo#featureGroups
     * @see R.styleable#AndroidManifestUsesFeature
     */
    @NonNull
    List<FeatureGroupInfo> getFeatureGroups();

    /**
     * Permissions requested but not in the manifest. These may have been split or migrated from
     * previous versions/definitions.
     */
    @NonNull
    List<String> getImplicitPermissions();

    /**
     * @see android.content.pm.InstrumentationInfo
     * @see PackageInfo#instrumentation
     */
    @NonNull
    List<ParsedInstrumentation> getInstrumentations();

    /**
     * For use with {@link com.android.server.pm.KeySetManagerService}. Parsed in
     * {@link ParsingPackageUtils#TAG_KEY_SETS}.
     * @see R.styleable#AndroidManifestKeySet
     * @see R.styleable#AndroidManifestPublicKey
     */
    @NonNull
    Map<String, ArraySet<PublicKey>> getKeySetMapping();

    /**
     * Library names this package is declared as, for use by other packages with "uses-library".
     * @see R.styleable#AndroidManifestLibrary
     */
    @NonNull
    List<String> getLibraryNames();

    /**
     * For system use to migrate from an old package name to a new one, moving over data
     * if available.
     * @see R.styleable#AndroidManifestOriginalPackage}
     */
    @NonNull
    List<String> getOriginalPackages();

    /**
     * Map of overlayable name to actor name.
     */
    @NonNull
    Map<String, String> getOverlayables();

    /**
     * @see android.content.pm.PermissionInfo
     * @see PackageInfo#permissions
     */
    @NonNull
    List<ParsedPermission> getPermissions();

    /**
     * @see android.content.pm.PermissionGroupInfo
     */
    @NonNull
    List<ParsedPermissionGroup> getPermissionGroups();

    /**
     * Used to determine the default preferred handler of an {@link Intent}.
     *
     * Map of component className to intent info inside that component.
     * TODO(b/135203078): Is this actually used/working?
     */
    @NonNull
    List<Pair<String, ParsedIntentInfo>> getPreferredActivityFilters();

    /**
     * System protected broadcasts.
     * @see R.styleable#AndroidManifestProtectedBroadcast
     */
    @NonNull
    List<String> getProtectedBroadcasts();

    /**
     * @see android.content.pm.ProviderInfo
     * @see PackageInfo#providers
     */
    @NonNull
    List<ParsedProvider> getProviders();

    /**
     * @see android.content.pm.ProcessInfo
     */
    @NonNull
    Map<String, ParsedProcess> getProcesses();

    /**
     * Since they share several attributes, receivers are parsed as {@link ParsedActivity}, even
     * though they represent different functionality.
     * TODO(b/135203078): Reconsider this and maybe make ParsedReceiver so it's not so confusing
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
    List<FeatureInfo> getReqFeatures();

    /**
     * @deprecated consider migrating to {@link #getUsesPermissions} which has
     *             more parsed details, such as flags
     */
    @NonNull
    @Deprecated
    List<String> getRequestedPermissions();

    /**
     * All the permissions declared. This is an effective set, and may include permissions
     * transformed from split/migrated permissions from previous versions, so may not be exactly
     * what the package declares in its manifest.
     * @see PackageInfo#requestedPermissions
     * @see R.styleable#AndroidManifestUsesPermission
     */
    @NonNull
    List<ParsedUsesPermission> getUsesPermissions();

    /**
     * Returns the properties set on the application
     */
    @NonNull
    Map<String, Property> getProperties();

    /**
     * Whether or not the app requested explicitly resizeable Activities.
     * A null value means nothing was explicitly requested.
     */
    @Nullable
    Boolean getResizeableActivity();

    /**
     * @see ServiceInfo
     * @see PackageInfo#services
     */
    @NonNull
    List<ParsedService> getServices();

    /** @see R.styleable#AndroidManifestUsesLibrary */
    @NonNull
    List<String> getUsesLibraries();

    /**
     * Like {@link #getUsesLibraries()}, but marked optional by setting
     * {@link R.styleable#AndroidManifestUsesLibrary_required} to false . Application is expected
     * to handle absence manually.
     * @see R.styleable#AndroidManifestUsesLibrary
     */
    @NonNull
    List<String> getUsesOptionalLibraries();

    /** @see R.styleabele#AndroidManifestUsesNativeLibrary */
    @NonNull
    List<String> getUsesNativeLibraries();

    /**
     * Like {@link #getUsesNativeLibraries()}, but marked optional by setting
     * {@link R.styleable#AndroidManifestUsesNativeLibrary_required} to false . Application is
     * expected to handle absence manually.
     * @see R.styleable#AndroidManifestUsesNativeLibrary
     */
    @NonNull
    List<String> getUsesOptionalNativeLibraries();

    /**
     * TODO(b/135203078): Move static library stuff to an inner data class
     * @see R.styleable#AndroidManifestUsesStaticLibrary
     */
    @NonNull
    List<String> getUsesStaticLibraries();

    /** @see R.styleable#AndroidManifestUsesStaticLibrary_certDigest */
    @Nullable
    String[][] getUsesStaticLibrariesCertDigests();

    /** @see R.styleable#AndroidManifestUsesStaticLibrary_version */
    @Nullable
    long[] getUsesStaticLibrariesVersions();

    /**
     * Intents that this package may query or require and thus requires visibility into.
     * @see R.styleable#AndroidManifestQueriesIntent
     */
    @NonNull
    List<Intent> getQueriesIntents();

    /**
     * Other packages that this package may query or require and thus requires visibility into.
     * @see R.styleable#AndroidManifestQueriesPackage
     */
    @NonNull
    List<String> getQueriesPackages();

    /**
     * Authorities that this package may query or require and thus requires visibility into.
     * @see R.styleable#AndroidManifestQueriesProvider
     */
    @NonNull
    Set<String> getQueriesProviders();

    /**
     * We store the application meta-data independently to avoid multiple unwanted references
     * TODO(b/135203078): What does this comment mean?
     * TODO(b/135203078): Make all the Bundles immutable (and non-null by shared empty reference?)
     */
    @Nullable
    Bundle getMetaData();

    /** @see R.styleable#AndroidManifestApplication_forceQueryable */
    boolean isForceQueryable();

    /**
     * @see ApplicationInfo#maxAspectRatio
     * @see R.styleable#AndroidManifestApplication_maxAspectRatio
     */
    float getMaxAspectRatio();

    /**
     * @see ApplicationInfo#minAspectRatio
     * @see R.styleable#AndroidManifestApplication_minAspectRatio
     */
    float getMinAspectRatio();

    /**
     * @see ApplicationInfo#permission
     * @see R.styleable#AndroidManifestApplication_permission
     */
    @Nullable
    String getPermission();

    /**
     * @see ApplicationInfo#processName
     * @see R.styleable#AndroidManifestApplication_process
     */
    @NonNull
    String getProcessName();

    /**
     * @see PackageInfo#sharedUserId
     * @see R.styleable#AndroidManifest_sharedUserId
     */
    @Deprecated
    @Nullable
    String getSharedUserId();

    /** @see R.styleable#AndroidManifestStaticLibrary_name */
    @Nullable
    String getStaticSharedLibName();

    /**
     * @see ApplicationInfo#taskAffinity
     * @see R.styleable#AndroidManifestApplication_taskAffinity
     */
    @Nullable
    String getTaskAffinity();

    /**
     * @see ApplicationInfo#targetSdkVersion
     * @see R.styleable#AndroidManifestUsesSdk_targetSdkVersion
     */
    int getTargetSdkVersion();

    /**
     * @see ApplicationInfo#uiOptions
     * @see R.styleable#AndroidManifestApplication_uiOptions
     */
    int getUiOptions();

    boolean isCrossProfile();

    boolean isResizeableActivityViaSdkVersion();

    /** @see ApplicationInfo#FLAG_HARDWARE_ACCELERATED */
    boolean isBaseHardwareAccelerated();

    /**
     * If omitted from manifest, returns true if {@link #getTargetSdkVersion()} >=
     * {@link android.os.Build.VERSION_CODES#DONUT}.
     * @see R.styleable#AndroidManifestSupportsScreens_resizeable
     * @see ApplicationInfo#FLAG_RESIZEABLE_FOR_SCREENS
     */
    boolean isResizeable();

    /** @see ApplicationInfo#PRIVATE_FLAG_ALLOW_AUDIO_PLAYBACK_CAPTURE */
    boolean isAllowAudioPlaybackCapture();

    /** @see ApplicationInfo#FLAG_ALLOW_BACKUP */
    boolean isAllowBackup();

    /** @see ApplicationInfo#FLAG_ALLOW_CLEAR_USER_DATA */
    boolean isAllowClearUserData();

    /** @see ApplicationInfo#PRIVATE_FLAG_ALLOW_CLEAR_USER_DATA_ON_FAILED_RESTORE */
    boolean isAllowClearUserDataOnFailedRestore();

    /** @see ApplicationInfo#FLAG_ALLOW_TASK_REPARENTING */
    boolean isAllowTaskReparenting();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_IS_RESOURCE_OVERLAY
     * @see ApplicationInfo#isResourceOverlay()
     */
    boolean isOverlay();

    /** @see ApplicationInfo#PRIVATE_FLAG_BACKUP_IN_FOREGROUND */
    boolean isBackupInForeground();

    /** @see ApplicationInfo#PRIVATE_FLAG_CANT_SAVE_STATE */
    boolean isCantSaveState();

    /** @see ApplicationInfo#FLAG_DEBUGGABLE */
    boolean isDebuggable();

    /** @see ApplicationInfo#PRIVATE_FLAG_DEFAULT_TO_DEVICE_PROTECTED_STORAGE */
    boolean isDefaultToDeviceProtectedStorage();

    /** @see ApplicationInfo#PRIVATE_FLAG_DIRECT_BOOT_AWARE */
    boolean isDirectBootAware();

    /** @see ApplicationInfo#FLAG_EXTERNAL_STORAGE */
    boolean isExternalStorage();

    /** @see ApplicationInfo#FLAG_EXTRACT_NATIVE_LIBS */
    boolean isExtractNativeLibs();

    /** @see ApplicationInfo#FLAG_FULL_BACKUP_ONLY */
    boolean isFullBackupOnly();

    /** @see ApplicationInfo#FLAG_HAS_CODE */
    boolean isHasCode();

    /** @see ApplicationInfo#PRIVATE_FLAG_HAS_FRAGILE_USER_DATA */
    boolean isHasFragileUserData();

    /** @see ApplicationInfo#FLAG_IS_GAME */
    @Deprecated
    boolean isGame();

    /** @see ApplicationInfo#PRIVATE_FLAG_ISOLATED_SPLIT_LOADING */
    boolean isIsolatedSplitLoading();

    /** @see ApplicationInfo#FLAG_KILL_AFTER_RESTORE */
    boolean isKillAfterRestore();

    /** @see ApplicationInfo#FLAG_LARGE_HEAP */
    boolean isLargeHeap();

    /** @see ApplicationInfo#FLAG_MULTIARCH */
    boolean isMultiArch();

    /** @see ApplicationInfo#PRIVATE_FLAG_PARTIALLY_DIRECT_BOOT_AWARE */
    boolean isPartiallyDirectBootAware();

    /** @see ApplicationInfo#FLAG_PERSISTENT */
    boolean isPersistent();

    /** @see ApplicationInfo#PRIVATE_FLAG_PROFILEABLE_BY_SHELL */
    boolean isProfileableByShell();

    /** @see ApplicationInfo#PRIVATE_FLAG_REQUEST_LEGACY_EXTERNAL_STORAGE */
    boolean isRequestLegacyExternalStorage();

    /** @see ApplicationInfo#FLAG_RESTORE_ANY_VERSION */
    boolean isRestoreAnyVersion();

    // ParsingPackageRead setSplitHasCode(int splitIndex, boolean splitHasCode);

    /** Flags of any split APKs; ordered by parsed splitName */
    @Nullable
    int[] getSplitFlags();

    /** @see ApplicationInfo#splitSourceDirs */
    @Nullable
    String[] getSplitCodePaths();

    /** @see ApplicationInfo#splitDependencies */
    @Nullable
    SparseArray<int[]> getSplitDependencies();

    /**
     * @see ApplicationInfo#splitNames
     * @see PackageInfo#splitNames
     */
    @Nullable
    String[] getSplitNames();

    /** @see PackageInfo#splitRevisionCodes */
    int[] getSplitRevisionCodes();

    /** @see ApplicationInfo#PRIVATE_FLAG_STATIC_SHARED_LIBRARY */
    boolean isStaticSharedLibrary();

    /** @see ApplicationInfo#FLAG_SUPPORTS_RTL */
    boolean isSupportsRtl();

    /** @see ApplicationInfo#FLAG_TEST_ONLY */
    boolean isTestOnly();

    /** @see ApplicationInfo#PRIVATE_FLAG_USE_EMBEDDED_DEX */
    boolean isUseEmbeddedDex();

    /** @see ApplicationInfo#FLAG_USES_CLEARTEXT_TRAFFIC */
    boolean isUsesCleartextTraffic();

    /** @see ApplicationInfo#PRIVATE_FLAG_USES_NON_SDK_API */
    boolean isUsesNonSdkApi();

    /**
     * Set if the any of components are visible to instant applications.
     * @see R.styleable#AndroidManifestActivity_visibleToInstantApps
     * @see R.styleable#AndroidManifestProvider_visibleToInstantApps
     * @see R.styleable#AndroidManifestService_visibleToInstantApps
     */
    boolean isVisibleToInstantApps();

    /** @see ApplicationInfo#FLAG_VM_SAFE_MODE */
    boolean isVmSafeMode();

    /**
     * If omitted from manifest, returns true if {@link #getTargetSdkVersion()} >=
     * {@link android.os.Build.VERSION_CODES#DONUT}.
     * @see R.styleable#AndroidManifestSupportsScreens_anyDensity
     * @see ApplicationInfo#FLAG_SUPPORTS_SCREEN_DENSITIES
     */
    boolean isAnyDensity();

    /**
     * @see ApplicationInfo#appComponentFactory
     * @see R.styleable#AndroidManifestApplication_appComponentFactory
     */
    @Nullable
    String getAppComponentFactory();

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

    String getPackageName();

    /** Path of base APK */
    String getBaseApkPath();

    /**
     * Path where this package was found on disk. For monolithic packages
     * this is path to single base APK file; for cluster packages this is
     * path to the cluster directory.
     */
    @NonNull
    String getPath();

    /**
     * @see ApplicationInfo#compatibleWidthLimitDp
     * @see R.styleable#AndroidManifestSupportsScreens_compatibleWidthLimitDp
     */
    int getCompatibleWidthLimitDp();

    /**
     * @see ApplicationInfo#descriptionRes
     * @see R.styleable#AndroidManifestApplication_description
     */
    int getDescriptionRes();

    /**
     * @see ApplicationInfo#enabled
     * @see R.styleable#AndroidManifestApplication_enabled
     */
    boolean isEnabled();

    /**
     * @see ApplicationInfo#fullBackupContent
     * @see R.styleable#AndroidManifestApplication_fullBackupContent
     */
    int getFullBackupContent();

    /**
     * @see R.styleable#AndroidManifestApplication_dataExtractionRules
     */
    int getDataExtractionRules();

    /** @see ApplicationInfo#PRIVATE_FLAG_HAS_DOMAIN_URLS */
    boolean isHasDomainUrls();

    /**
     * @see ApplicationInfo#iconRes
     * @see R.styleable#AndroidManifestApplication_icon
     */
    int getIconRes();

    /**
     * @see ApplicationInfo#installLocation
     * @see R.styleable#AndroidManifest_installLocation
     */
    int getInstallLocation();

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
     * @see ApplicationInfo#logo
     * @see R.styleable#AndroidManifestApplication_logo
     */
    int getLogo();

    /**
     * @see ApplicationInfo#manageSpaceActivityName
     * @see R.styleable#AndroidManifestApplication_manageSpaceActivity
     */
    @Nullable
    String getManageSpaceActivityName();

    /**
     * @see ApplicationInfo#minExtensionVersions
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
     * @see ApplicationInfo#networkSecurityConfigRes
     * @see R.styleable#AndroidManifestApplication_networkSecurityConfig
     */
    int getNetworkSecurityConfigRes();

    /**
     * If {@link R.styleable#AndroidManifestApplication_label} is a string literal, this is it.
     * Otherwise, it's stored as {@link #getLabelRes()}.
     * @see ApplicationInfo#nonLocalizedLabel
     * @see R.styleable#AndroidManifestApplication_label
     */
    @Nullable
    CharSequence getNonLocalizedLabel();

    /**
     * @see PackageInfo#overlayCategory
     * @see R.styleable#AndroidManifestResourceOverlay_category
     */
    @Nullable
    String getOverlayCategory();

    /** @see PackageInfo#mOverlayIsStatic */
    boolean isOverlayIsStatic();

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
    String getOverlayTargetName();

    /**
     * If a system app declares {@link #getOriginalPackages()}, and the app was previously installed
     * under one of those original package names, the {@link #getPackageName()} system identifier
     * will be changed to that previously installed name. This will then be non-null, set to the
     * manifest package name, for tracking the package under its true name.
     *
     * TODO(b/135203078): Remove this in favor of checking originalPackages.isEmpty and
     *  getManifestPackageName
     */
    @Nullable
    String getRealPackage();

    /**
     * The required account type without which this application will not function.
     *
     * @see PackageInfo#requiredAccountType
     * @see R.styleable#AndroidManifestApplication_requiredAccountType
     */
    @Nullable
    String getRequiredAccountType();

    /**
     * @see PackageInfo#requiredForAllUsers
     * @see R.styleable#AndroidManifestApplication_requiredForAllUsers
     */
    boolean isRequiredForAllUsers();

    /**
     * @see ApplicationInfo#requiresSmallestWidthDp
     * @see R.styleable#AndroidManifestSupportsScreens_requiresSmallestWidthDp
     */
    int getRequiresSmallestWidthDp();

    /**
     * SHA-512 hash of the only APK that can be used to update a system package.
     * @see R.styleable#AndroidManifestRestrictUpdate
     */
    @Nullable
    byte[] getRestrictUpdateHash();

    /**
     * The restricted account authenticator type that is used by this application
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
     * @see PackageInfo#sharedUserLabel
     * @see R.styleable#AndroidManifest_sharedUserLabel
     */
    @Deprecated
    int getSharedUserLabel();

    /**
     * The signature data of all APKs in this package, which must be exactly the same across the
     * base and splits.
     */
    PackageParser.SigningDetails getSigningDetails();

    /**
     * @see ApplicationInfo#splitClassLoaderNames
     * @see R.styleable#AndroidManifestApplication_classLoader
     */
    @Nullable
    String[] getSplitClassLoaderNames();

    /** @see R.styleable#AndroidManifestStaticLibrary_version */
    long getStaticSharedLibVersion();

    /**
     * If omitted from manifest, returns true if {@link #getTargetSdkVersion()} >=
     * {@link android.os.Build.VERSION_CODES#DONUT}.
     * @see R.styleable#AndroidManifestSupportsScreens_largeScreens
     * @see ApplicationInfo#FLAG_SUPPORTS_LARGE_SCREENS
     */
    boolean isSupportsLargeScreens();

    /**
     * If omitted from manifest, returns true.
     * @see R.styleable#AndroidManifestSupportsScreens_normalScreens
     * @see ApplicationInfo#FLAG_SUPPORTS_NORMAL_SCREENS
     */
    boolean isSupportsNormalScreens();

    /**
     * If omitted from manifest, returns true if {@link #getTargetSdkVersion()} >=
     * {@link android.os.Build.VERSION_CODES#DONUT}.
     * @see R.styleable#AndroidManifestSupportsScreens_smallScreens
     * @see ApplicationInfo#FLAG_SUPPORTS_SMALL_SCREENS
     */
    boolean isSupportsSmallScreens();

    /**
     * If omitted from manifest, returns true if {@link #getTargetSdkVersion()} >=
     * {@link android.os.Build.VERSION_CODES#GINGERBREAD}.
     * @see R.styleable#AndroidManifestSupportsScreens_xlargeScreens
     * @see ApplicationInfo#FLAG_SUPPORTS_XLARGE_SCREENS
     */
    boolean isSupportsExtraLargeScreens();

    /** @see ApplicationInfo#PRIVATE_FLAG_ALLOW_NATIVE_HEAP_POINTER_TAGGING */
    boolean isAllowNativeHeapPointerTagging();

    int getAutoRevokePermissions();

    boolean hasPreserveLegacyExternalStorage();

    /**
     * @see ApplicationInfo#targetSandboxVersion
     * @see R.styleable#AndroidManifest_targetSandboxVersion
     */
    @Deprecated
    int getTargetSandboxVersion();

    /**
     * @see ApplicationInfo#theme
     * @see R.styleable#AndroidManifestApplication_theme
     */
    int getTheme();

    /**
     * For use with {@link com.android.server.pm.KeySetManagerService}. Parsed in
     * {@link ParsingPackageUtils#TAG_KEY_SETS}.
     * @see R.styleable#AndroidManifestUpgradeKeySet
     */
    @NonNull
    Set<String> getUpgradeKeySets();

    /**
     * The install time abi override to choose 32bit abi's when multiple abi's
     * are present. This is only meaningfull for multiarch applications.
     * The use32bitAbi attribute is ignored if cpuAbiOverride is also set.
     */
    boolean isUse32BitAbi();

    /** @see ApplicationInfo#volumeUuid */
    @Nullable
    String getVolumeUuid();

    /** @see ApplicationInfo#zygotePreloadName */
    @Nullable
    String getZygotePreloadName();

    /** Revision code of base APK */
    int getBaseRevisionCode();

    /** @see PackageInfo#versionName */
    @Nullable
    String getVersionName();

    /** @see PackageInfo#versionCodeMajor */
    @Nullable
    int getVersionCode();

    /** @see PackageInfo#versionCodeMajor */
    @Nullable
    int getVersionCodeMajor();

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

    @Nullable
    Set<String> getMimeGroups();

    /**
     * @see ApplicationInfo#gwpAsanMode
     * @see R.styleable#AndroidManifest_gwpAsanMode
     */
    int getGwpAsanMode();

    /**
     * @see ApplicationInfo#memtagMode
     * @see R.styleable#AndroidManifest_memtagMode
     */
    int getMemtagMode();

      /**
     * @see ApplicationInfo#nativeHeapZeroInit
     * @see R.styleable#AndroidManifest_nativeHeapZeroInit
     */
    @Nullable
    Boolean isNativeHeapZeroInit();

    @Nullable
    Boolean hasRequestOptimizedExternalStorageAccess();

    // TODO(b/135203078): Hide and enforce going through PackageInfoUtils
    ApplicationInfo toAppInfoWithoutState();

    /**
     * same as toAppInfoWithoutState except without flag computation.
     */
    ApplicationInfo toAppInfoWithoutStateWithoutFlags();
}
