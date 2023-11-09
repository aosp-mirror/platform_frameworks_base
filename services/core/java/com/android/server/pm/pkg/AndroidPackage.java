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

import android.annotation.Dimension;
import android.annotation.DrawableRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.annotation.StyleRes;
import android.annotation.SystemApi;
import android.annotation.XmlRes;
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
import android.os.storage.StorageManager;
import android.processor.immutability.Immutable;
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
import java.util.UUID;

/**
 * The representation of an application on disk, as parsed from its split APKs' manifests.
 *
 * Metadata available here is mostly device-state independent and indicates what the application
 * author declared for their app.
 *
 * This is the system server in-process API equivalent of the public API {@link ApplicationInfo}.
 * Note that because {@link ApplicationInfo} is stateful, several methods that exist on it may not
 * be available here and need to be read through {@link PackageState} or {@link PackageUserState}.
 *
 * All instances of {@link AndroidPackage} are associated with a {@link PackageState}, and the
 * only way to retrieve one is through {@link PackageState}. Note that the inverse does not apply
 * and {@link AndroidPackage} may be null in several cases. See
 * {@link PackageState#getAndroidPackage()}.
 *
 * The data available here is immutable and will throw {@link UnsupportedOperationException} if any
 * collection type is mutated.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
@Immutable
public interface AndroidPackage {

    /**
     * @see ApplicationInfo#className
     * @see R.styleable#AndroidManifestApplication_name
     */
    @Nullable
    String getApplicationClassName();

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
    @DrawableRes
    int getBannerResourceId();

    /**
     * @see PackageInfo#baseRevisionCode
     * @see R.styleable#AndroidManifest_revisionCode
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
     * @see ApplicationInfo#compatibleWidthLimitDp
     * @see R.styleable#AndroidManifestSupportsScreens_compatibleWidthLimitDp
     */
    @Dimension(unit = Dimension.DP)
    int getCompatibleWidthLimitDp();

    /**
     * @see ApplicationInfo#dataExtractionRulesRes
     * @see R.styleable#AndroidManifestApplication_dataExtractionRules
     */
    @XmlRes
    int getDataExtractionRulesResourceId();

    /**
     * @see ApplicationInfo#descriptionRes
     * @see R.styleable#AndroidManifestApplication_description
     */
    @StringRes // This is actually format="reference"
    int getDescriptionResourceId();

    /**
     * @see ApplicationInfo#fullBackupContent
     * @see R.styleable#AndroidManifestApplication_fullBackupContent
     */
    @XmlRes
    int getFullBackupContentResourceId();

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
    @DrawableRes
    int getIconResourceId();

    /**
     * @see ApplicationInfo#labelRes
     * @see R.styleable#AndroidManifestApplication_label
     */
    @StringRes
    int getLabelResourceId();

    /**
     * @see ApplicationInfo#largestWidthLimitDp
     * @see R.styleable#AndroidManifestSupportsScreens_largestWidthLimitDp
     */
    @Dimension(unit = Dimension.DP)
    int getLargestWidthLimitDp();

    /**
     * Library names this package is declared as, for use by other packages with "uses-library".
     *
     * @see R.styleable#AndroidManifestLibrary
     */
    @NonNull
    List<String> getLibraryNames();

    /**
     * @see ApplicationInfo#logo
     * @see R.styleable#AndroidManifestApplication_logo
     */
    @DrawableRes
    int getLogoResourceId();

    /**
     * The resource ID used to provide the application's locales configuration.
     *
     * @see R.styleable#AndroidManifestApplication_localeConfig
     */
    @XmlRes
    int getLocaleConfigResourceId();

    /**
     * @see PackageInfo#getLongVersionCode()
     * @see R.styleable#AndroidManifest_versionCode
     * @see R.styleable#AndroidManifest_versionCodeMajor
     */
    long getLongVersionCode();

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
     * @see ApplicationInfo#getNativeHeapZeroInitialized()
     * @see R.styleable#AndroidManifestApplication_nativeHeapZeroInitialized
     */
    @ApplicationInfo.NativeHeapZeroInitialized
    int getNativeHeapZeroInitialized();

    /**
     * @see ApplicationInfo#networkSecurityConfigRes
     * @see R.styleable#AndroidManifestApplication_networkSecurityConfig
     */
    @XmlRes
    int getNetworkSecurityConfigResourceId();

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
    @Dimension(unit = Dimension.DP)
    int getRequiresSmallestWidthDp();

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
    @DrawableRes
    int getRoundIconResourceId();

    /**
     * @see R.styleable#AndroidManifestSdkLibrary_name
     */
    @Nullable
    String getSdkLibraryName();

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
    @StringRes
    int getSharedUserLabelResourceId();

    /**
     * @return List of all splits for a package. Note that base.apk is considered a
     * split and will be provided as index 0 of the list.
     */
    @NonNull
    List<AndroidPackageSplit> getSplits();

    /**
     * @see R.styleable#AndroidManifestStaticLibrary_name
     */
    @Nullable
    String getStaticSharedLibraryName();

    /**
     * @see R.styleable#AndroidManifestStaticLibrary_version
     * @hide
     */
    long getStaticSharedLibraryVersion();

    /**
     * @return The {@link UUID} for use with {@link StorageManager} APIs identifying where this
     * package was installed.
     */
    @NonNull
    UUID getStorageUuid();

    /**
     * @see ApplicationInfo#targetSdkVersion
     * @see R.styleable#AndroidManifestUsesSdk_targetSdkVersion
     */
    int getTargetSdkVersion();

    /**
     * @see ApplicationInfo#theme
     * @see R.styleable#AndroidManifestApplication_theme
     */
    @StyleRes
    int getThemeResourceId();

    /**
     * @see ApplicationInfo#uiOptions
     * @see R.styleable#AndroidManifestApplication_uiOptions
     */
    int getUiOptions();

    /**
     * @see PackageInfo#versionName
     */
    @Nullable
    String getVersionName();

    /**
     * @see ApplicationInfo#zygotePreloadName
     * @see R.styleable#AndroidManifestApplication_zygotePreloadName
     */
    @Nullable
    String getZygotePreloadName();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_ALLOW_AUDIO_PLAYBACK_CAPTURE
     * @see R.styleable#AndroidManifestApplication_allowAudioPlaybackCapture
     */
    boolean isAllowAudioPlaybackCapture();

    /**
     * @see ApplicationInfo#FLAG_ALLOW_BACKUP
     * @see R.styleable#AndroidManifestApplication_allowBackup
     */
    boolean isBackupAllowed();

    /**
     * @see ApplicationInfo#FLAG_ALLOW_CLEAR_USER_DATA
     * @see R.styleable#AndroidManifestApplication_allowClearUserData
     */
    boolean isClearUserDataAllowed();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_ALLOW_CLEAR_USER_DATA_ON_FAILED_RESTORE
     * @see R.styleable#AndroidManifestApplication_allowClearUserDataOnFailedRestore
     */
    boolean isClearUserDataOnFailedRestoreAllowed();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_ALLOW_NATIVE_HEAP_POINTER_TAGGING
     * @see R.styleable#AndroidManifestApplication_allowNativeHeapPointerTagging
     */
    boolean isAllowNativeHeapPointerTagging();

    /**
     * @see ApplicationInfo#FLAG_ALLOW_TASK_REPARENTING
     * @see R.styleable#AndroidManifestApplication_allowTaskReparenting
     */
    boolean isTaskReparentingAllowed();

    /**
     * If omitted from manifest, returns true if {@link #getTargetSdkVersion()} >= {@link
     * android.os.Build.VERSION_CODES#DONUT}.
     *
     * @see R.styleable#AndroidManifestSupportsScreens_anyDensity
     * @see ApplicationInfo#FLAG_SUPPORTS_SCREEN_DENSITIES
     */
    boolean isAnyDensity();

    /**
     * @see ApplicationInfo#areAttributionsUserVisible()
     * @see R.styleable#AndroidManifestApplication_attributionsAreUserVisible
     */
    boolean isAttributionsUserVisible();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_BACKUP_IN_FOREGROUND
     * @see R.styleable#AndroidManifestApplication_backupInForeground
     */
    boolean isBackupInForeground();

    /**
     * @see ApplicationInfo#FLAG_HARDWARE_ACCELERATED
     * @see R.styleable#AndroidManifestApplication_hardwareAccelerated
     */
    boolean isHardwareAccelerated();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_CANT_SAVE_STATE
     * @see R.styleable#AndroidManifestApplication_cantSaveState
     */
    boolean isSaveStateDisallowed();

    /**
     * @see PackageInfo#coreApp
     */
    boolean isCoreApp();

    /**
     * @see ApplicationInfo#crossProfile
     * @see R.styleable#AndroidManifestApplication_crossProfile
     */
    boolean isCrossProfile();

    /**
     * @see ApplicationInfo#FLAG_DEBUGGABLE
     * @see R.styleable#AndroidManifestApplication_debuggable
     */
    boolean isDebuggable();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_DEFAULT_TO_DEVICE_PROTECTED_STORAGE
     * @see R.styleable#AndroidManifestApplication_defaultToDeviceProtectedStorage
     */
    boolean isDefaultToDeviceProtectedStorage();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_DIRECT_BOOT_AWARE
     * @see R.styleable#AndroidManifestApplication_directBootAware
     */
    boolean isDirectBootAware();

    /**
     * @see ApplicationInfo#FLAG_EXTRACT_NATIVE_LIBS
     * @see R.styleable#AndroidManifestApplication_extractNativeLibs
     */
    boolean isExtractNativeLibrariesRequested();

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
     * @see R.styleable#AndroidManifestApplication_fullBackupOnly
     */
    boolean isFullBackupOnly();

    /**
     * @see ApplicationInfo#FLAG_HAS_CODE
     * @see R.styleable#AndroidManifestApplication_hasCode
     */
    boolean isDeclaredHavingCode();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_HAS_FRAGILE_USER_DATA
     * @see R.styleable#AndroidManifestApplication_hasFragileUserData
     */
    boolean isUserDataFragile();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_ISOLATED_SPLIT_LOADING
     * @see R.styleable#AndroidManifest_isolatedSplits
     */
    boolean isIsolatedSplitLoading();

    /**
     * @see ApplicationInfo#FLAG_KILL_AFTER_RESTORE
     * @see R.styleable#AndroidManifestApplication_killAfterRestore
     */
    boolean isKillAfterRestoreAllowed();

    /**
     * @see ApplicationInfo#FLAG_LARGE_HEAP
     * @see R.styleable#AndroidManifestApplication_largeHeap
     */
    boolean isLargeHeap();

    /**
     * Returns true if R.styleable#AndroidManifest_sharedUserMaxSdkVersion is set to a value
     * smaller than the current SDK version, indicating the package wants to leave its declared
     * {@link #getSharedUserId()}. This only occurs on new installs, pretending the app never
     * declared one.
     *
     * @see R.styleable#AndroidManifest_sharedUserMaxSdkVersion
     */
    boolean isLeavingSharedUser();

    /**
     * @see ApplicationInfo#FLAG_MULTIARCH
     * @see R.styleable#AndroidManifestApplication_multiArch
     */
    boolean isMultiArch();

    /**
     * @see ApplicationInfo#nativeLibraryRootRequiresIsa
     */
    boolean isNativeLibraryRootRequiresIsa();

    /**
     * @see R.styleable#AndroidManifestApplication_enableOnBackInvokedCallback
     */
    boolean isOnBackInvokedCallbackEnabled();

    /**
     * @see ApplicationInfo#FLAG_PERSISTENT
     * @see R.styleable#AndroidManifestApplication_persistent
     */
    boolean isPersistent();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_EXT_PROFILEABLE
     * @see R.styleable#AndroidManifestProfileable
     */
    boolean isProfileable();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_PROFILEABLE_BY_SHELL
     * @see R.styleable#AndroidManifestProfileable_shell
     */
    boolean isProfileableByShell();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_REQUEST_LEGACY_EXTERNAL_STORAGE
     * @see R.styleable#AndroidManifestApplication_requestLegacyExternalStorage
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
     * @see ApplicationInfo#PRIVATE_FLAG_IS_RESOURCE_OVERLAY
     * @see ApplicationInfo#isResourceOverlay()
     * @see R.styleable#AndroidManifestResourceOverlay
     */
    boolean isResourceOverlay();

    /**
     * @see ApplicationInfo#FLAG_RESTORE_ANY_VERSION
     * @see R.styleable#AndroidManifestApplication_restoreAnyVersion
     */
    boolean isRestoreAnyVersion();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_SIGNED_WITH_PLATFORM_KEY
     */
    boolean isSignedWithPlatformKey();

    /**
     * If omitted from manifest, returns true if {@link #getTargetSdkVersion()} >= {@link
     * android.os.Build.VERSION_CODES#GINGERBREAD}.
     *
     * @see R.styleable#AndroidManifestSupportsScreens_xlargeScreens
     * @see ApplicationInfo#FLAG_SUPPORTS_XLARGE_SCREENS
     */
    boolean isExtraLargeScreensSupported();

    /**
     * If omitted from manifest, returns true if {@link #getTargetSdkVersion()} >= {@link
     * android.os.Build.VERSION_CODES#DONUT}.
     *
     * @see R.styleable#AndroidManifestSupportsScreens_largeScreens
     * @see ApplicationInfo#FLAG_SUPPORTS_LARGE_SCREENS
     */
    boolean isLargeScreensSupported();

    /**
     * If omitted from manifest, returns true.
     *
     * @see R.styleable#AndroidManifestSupportsScreens_normalScreens
     * @see ApplicationInfo#FLAG_SUPPORTS_NORMAL_SCREENS
     */
    boolean isNormalScreensSupported();

    /**
     * @see ApplicationInfo#FLAG_SUPPORTS_RTL
     * @see R.styleable#AndroidManifestApplication_supportsRtl
     */
    boolean isRtlSupported();

    /**
     * If omitted from manifest, returns true if {@link #getTargetSdkVersion()} >= {@link
     * android.os.Build.VERSION_CODES#DONUT}.
     *
     * @see R.styleable#AndroidManifestSupportsScreens_smallScreens
     * @see ApplicationInfo#FLAG_SUPPORTS_SMALL_SCREENS
     */
    boolean isSmallScreensSupported();

    /**
     * @see ApplicationInfo#FLAG_TEST_ONLY
     * @see R.styleable#AndroidManifestApplication_testOnly
     */
    boolean isTestOnly();

    /**
     * The install time abi override to choose 32bit abi's when multiple abi's are present. This is
     * only meaningful for multiarch applications. The use32bitAbi attribute is ignored if
     * cpuAbiOverride is also set.
     *
     * @see R.attr#use32bitAbi
     */
    boolean is32BitAbiPreferred();

    /**
     * @see ApplicationInfo#FLAG_USES_CLEARTEXT_TRAFFIC
     * @see R.styleable#AndroidManifestApplication_usesCleartextTraffic
     */
    boolean isCleartextTrafficAllowed();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_USE_EMBEDDED_DEX
     * @see R.styleable#AndroidManifestApplication_useEmbeddedDex
     */
    boolean isUseEmbeddedDex();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_USES_NON_SDK_API
     * @see R.styleable#AndroidManifestApplication_usesNonSdkApi
     */
    boolean isNonSdkApiRequested();

    /**
     * @see ApplicationInfo#FLAG_VM_SAFE_MODE
     * @see R.styleable#AndroidManifestApplication_vmSafeMode
     */
    boolean isVmSafeMode();

    // Methods below this comment are not yet exposed as API

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
     * @see R.styleable#AndroidManifestActivity
     * @hide
     */
    @Immutable.Ignore
    @NonNull
    List<ParsedActivity> getActivities();

    /**
     * The names of packages to adopt ownership of permissions from, parsed under {@link
     * ParsingPackageUtils#TAG_ADOPT_PERMISSIONS}.
     *
     * @see R.styleable#AndroidManifestOriginalPackage_name
     * @hide
     */
    @NonNull
    List<String> getAdoptPermissions();

    /**
     * @see R.styleable#AndroidManifestApexSystemService
     * @hide
     */
    @Immutable.Ignore
    @NonNull
    List<ParsedApexSystemService> getApexSystemServices();

    /**
     * @see R.styleable#AndroidManifestAttribution
     * @hide
     */
    @Immutable.Ignore
    @NonNull
    List<ParsedAttribution> getAttributions();

    /**
     * @see ApplicationInfo#AUTO_REVOKE_ALLOWED
     * @see ApplicationInfo#AUTO_REVOKE_DISCOURAGED
     * @see ApplicationInfo#AUTO_REVOKE_DISALLOWED
     * @see R.styleable#AndroidManifestApplication_autoRevokePermissions
     * @hide
     */
    int getAutoRevokePermissions();

    /**
     * @see ApplicationInfo#sourceDir
     * @see ApplicationInfo#getBaseCodePath
     *
     * @deprecated Use {@link #getSplits()}[0].{@link AndroidPackageSplit#getPath() getPath()}
     *
     * @hide
     */
    @Deprecated
    @NonNull
    String getBaseApkPath();

    /**
     * @see ApplicationInfo#compileSdkVersion
     * @see R.styleable#AndroidManifest_compileSdkVersion
     * @hide
     */
    int getCompileSdkVersion();

    /**
     * @see ApplicationInfo#compileSdkVersionCodename
     * @see R.styleable#AndroidManifest_compileSdkVersionCodename
     * @hide
     */
    @Nullable
    String getCompileSdkVersionCodeName();

    /**
     * @see PackageInfo#configPreferences
     * @see R.styleable#AndroidManifestUsesConfiguration
     * @hide
     */
    @Immutable.Ignore
    @NonNull
    List<ConfigurationInfo> getConfigPreferences();

    /**
     * @see PackageInfo#featureGroups
     * @see R.styleable#AndroidManifestUsesFeature
     * @hide
     */
    @Immutable.Ignore
    @NonNull
    List<FeatureGroupInfo> getFeatureGroups();

    /**
     * Permissions requested but not in the manifest. These may have been split or migrated from
     * previous versions/definitions.
     * @hide
     */
    @NonNull
    Set<String> getImplicitPermissions();

    /**
     * @see ApplicationInfo#installLocation
     * @see R.styleable#AndroidManifest_installLocation
     * @hide
     */
    int getInstallLocation();

    /**
     * @see InstrumentationInfo
     * @see PackageInfo#instrumentation
     * @see R.styleable#AndroidManifestInstrumentation
     * @hide
     */
    @Immutable.Ignore
    @NonNull
    List<ParsedInstrumentation> getInstrumentations();

    /**
     * For use with {@link com.android.server.pm.KeySetManagerService}. Parsed in {@link
     * ParsingPackageUtils#TAG_KEY_SETS}.
     *
     * @see R.styleable#AndroidManifestKeySet
     * @see R.styleable#AndroidManifestPublicKey
     * @hide
     */
    @Immutable.Ignore
    @NonNull
    Map<String, ArraySet<PublicKey>> getKeySetMapping();

    /**
     * @see ApplicationInfo#mKnownActivityEmbeddingCerts
     * @see R.styleable#AndroidManifestApplication_knownActivityEmbeddingCerts
     * @hide
     */
    @SuppressWarnings("JavadocReference")
    @NonNull
    Set<String> getKnownActivityEmbeddingCerts();

    /**
     * @see ApplicationInfo#manageSpaceActivityName
     * @see R.styleable#AndroidManifestApplication_manageSpaceActivity
     * @hide
     */
    @Nullable
    String getManageSpaceActivityName();

    /**
     * The package name as declared in the manifest, since the package can be renamed. For example,
     * static shared libs use synthetic package names.
     * @hide
     */
    @NonNull
    String getManifestPackageName();

    /**
     * @see R.styleable#AndroidManifestUsesSdk_maxSdkVersion
     * @hide
     */
    int getMaxSdkVersion();

    /**
     * @see ApplicationInfo#getMemtagMode()
     * @see R.styleable#AndroidManifestApplication_memtagMode
     * @hide
     */
    @ApplicationInfo.MemtagMode
    int getMemtagMode();

    /**
     * TODO(b/135203078): Make all the Bundles immutable (and non-null by shared empty reference?)
     * @see R.styleable#AndroidManifestMetaData
     * @hide
     */
    @Immutable.Ignore
    @Nullable
    Bundle getMetaData();

    /**
     * @see R.attr#mimeGroup
     * @hide
     */
    @Nullable
    Set<String> getMimeGroups();

    /**
     * @see R.styleable#AndroidManifestExtensionSdk
     * @hide
     */
    @Immutable.Ignore
    @Nullable
    SparseIntArray getMinExtensionVersions();

    /**
     * @see ApplicationInfo#minSdkVersion
     * @see R.styleable#AndroidManifestUsesSdk_minSdkVersion
     * @hide
     */
    int getMinSdkVersion();

    /**
     * @see ApplicationInfo#nativeLibraryDir
     * @hide
     */
    @Nullable
    String getNativeLibraryDir();

    /**
     * @see ApplicationInfo#nativeLibraryRootDir
     * @hide
     */
    @Nullable
    String getNativeLibraryRootDir();

    /**
     * If {@link R.styleable#AndroidManifestApplication_label} is a string literal, this is it.
     * Otherwise, it's stored as {@link #getLabelResourceId()}.
     *
     * @see ApplicationInfo#nonLocalizedLabel
     * @see R.styleable#AndroidManifestApplication_label
     * @hide
     */
    @Nullable
    CharSequence getNonLocalizedLabel();

    /**
     * For system use to migrate from an old package name to a new one, moving over data if
     * available.
     *
     * @see R.styleable#AndroidManifestOriginalPackage}
     * @hide
     */
    @NonNull
    List<String> getOriginalPackages();

    /**
     * @see PackageInfo#overlayCategory
     * @see R.styleable#AndroidManifestResourceOverlay_category
     * @hide
     */
    @Nullable
    String getOverlayCategory();

    /**
     * @see PackageInfo#overlayPriority
     * @see R.styleable#AndroidManifestResourceOverlay_priority
     * @hide
     */
    int getOverlayPriority();

    /**
     * @see PackageInfo#overlayTarget
     * @see R.styleable#AndroidManifestResourceOverlay_targetPackage
     * @hide
     */
    @Nullable
    String getOverlayTarget();

    /**
     * @see PackageInfo#targetOverlayableName
     * @see R.styleable#AndroidManifestResourceOverlay_targetName
     * @hide
     */
    @Nullable
    String getOverlayTargetOverlayableName();

    /**
     * Map of overlayable name to actor name.
     * @hide
     */
    @NonNull
    Map<String, String> getOverlayables();

    /**
     * @see PackageInfo#packageName
     * @hide
     */
    String getPackageName();

    /**
     * @see ApplicationInfo#scanSourceDir
     * @see ApplicationInfo#getCodePath
     * @hide
     */
    @NonNull
    String getPath();

    /**
     * @see ApplicationInfo#permission
     * @see R.styleable#AndroidManifestApplication_permission
     * @hide
     */
    @Nullable
    String getPermission();

    /**
     * @see android.content.pm.PermissionGroupInfo
     * @see R.styleable#AndroidManifestPermissionGroup
     * @hide
     */
    @Immutable.Ignore
    @NonNull
    List<ParsedPermissionGroup> getPermissionGroups();

    /**
     * @see PermissionInfo
     * @see PackageInfo#permissions
     * @hide
     */
    @Immutable.Ignore
    @NonNull
    List<ParsedPermission> getPermissions();

    /**
     * Used to determine the default preferred handler of an {@link Intent}.
     * <p>
     * Map of component className to intent info inside that component. TODO(b/135203078): Is this
     * actually used/working?
     * @hide
     */
    @Immutable.Ignore
    @NonNull
    List<Pair<String, ParsedIntentInfo>> getPreferredActivityFilters();

    /**
     * @see ApplicationInfo#processName
     * @see R.styleable#AndroidManifestApplication_process
     * @hide
     */
    @NonNull
    String getProcessName();

    /**
     * @see android.content.pm.ProcessInfo
     * @see R.styleable#AndroidManifestProcess
     * @hide
     */
    @Immutable.Ignore
    @NonNull
    Map<String, ParsedProcess> getProcesses();

    /**
     * Returns the properties set on the application
     * @see R.styleable#AndroidManifestProperty
     * @hide
     */
    @Immutable.Ignore
    @NonNull
    Map<String, PackageManager.Property> getProperties();

    /**
     * System protected broadcasts.
     *
     * @see R.styleable#AndroidManifestProtectedBroadcast
     * @hide
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
     * @see R.styleable#AndroidManifestProvider
     * @hide
     */
    @Immutable.Ignore
    @NonNull
    List<ParsedProvider> getProviders();

    /**
     * Intents that this package may query or require and thus requires visibility into.
     *
     * @see R.styleable#AndroidManifestQueriesIntent
     * @hide
     */
    @Immutable.Ignore
    @NonNull
    List<Intent> getQueriesIntents();

    /**
     * Other packages that this package may query or require and thus requires visibility into.
     *
     * @see R.styleable#AndroidManifestQueriesPackage
     * @hide
     */
    @NonNull
    List<String> getQueriesPackages();

    /**
     * Authorities that this package may query or require and thus requires visibility into.
     *
     * @see R.styleable#AndroidManifestQueriesProvider
     * @hide
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
     * @see R.styleable#AndroidManifestReceiver
     * @hide
     */
    @Immutable.Ignore
    @NonNull
    List<ParsedActivity> getReceivers();

    /**
     * @see PackageInfo#reqFeatures
     * @see R.styleable#AndroidManifestUsesFeature
     * @hide
     */
    @Immutable.Ignore
    @NonNull
    List<FeatureInfo> getRequestedFeatures();

    /**
     * All the permissions declared. This is an effective set, and may include permissions
     * transformed from split/migrated permissions from previous versions, so may not be exactly
     * what the package declares in its manifest.
     *
     * @see PackageInfo#requestedPermissions
     * @see R.styleable#AndroidManifestUsesPermission
     * @hide
     */
    @NonNull
    Set<String> getRequestedPermissions();

    /**
     * Whether or not the app requested explicitly resizeable Activities. Null value means nothing
     * was explicitly requested.
     *
     * @see ApplicationInfo#PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE
     * @see ApplicationInfo#PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_UNRESIZEABLE
     * @see R.styleable#AndroidManifestApplication_resizeableActivity
     * @hide
     */
    @Nullable
    Boolean getResizeableActivity();

    /**
     * SHA-512 hash of the only APK that can be used to update a system package.
     *
     * @see R.styleable#AndroidManifestRestrictUpdate
     * @hide
     */
    @Immutable.Ignore
    @Nullable
    byte[] getRestrictUpdateHash();

    /**
     * @see R.styleable#AndroidManifestSdkLibrary_versionMajor
     * @hide
     */
    int getSdkLibVersionMajor();

    /**
     * @see ApplicationInfo#secondaryNativeLibraryDir
     * @hide
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
     * @see R.styleable#AndroidManifestService
     * @hide
     */
    @Immutable.Ignore
    @NonNull
    List<ParsedService> getServices();

    /**
     * The signature data of all APKs in this package, which must be exactly the same across the
     * base and splits.
     * @hide
     */
    @Immutable.Ignore
    @NonNull
    SigningDetails getSigningDetails();

    /**
     * @see ApplicationInfo#splitClassLoaderNames
     * @see R.styleable#AndroidManifestApplication_classLoader
     * @hide
     */
    @Immutable.Ignore
    @Nullable
    String[] getSplitClassLoaderNames();

    /**
     * @see ApplicationInfo#splitSourceDirs
     * @see ApplicationInfo#getSplitCodePaths
     * @hide
     */
    @Immutable.Ignore
    @NonNull
    String[] getSplitCodePaths();

    /**
     * @see ApplicationInfo#splitDependencies
     * @hide
     */
    @Immutable.Ignore
    @NonNull
    SparseArray<int[]> getSplitDependencies();

    /**
     * Flags of any split APKs; ordered by parsed splitName
     * @hide
     */
    @Immutable.Ignore
    @Nullable
    int[] getSplitFlags();

    /**
     * TODO(b/135203078): Move split stuff to an inner data class
     *
     * @see ApplicationInfo#splitNames
     * @see PackageInfo#splitNames
     * @hide
     */
    @Immutable.Ignore
    @NonNull
    String[] getSplitNames();

    /**
     * @see PackageInfo#splitRevisionCodes
     * @hide
     */
    @Immutable.Ignore
    @NonNull
    int[] getSplitRevisionCodes();

    /**
     * @see ApplicationInfo#targetSandboxVersion
     * @see R.styleable#AndroidManifest_targetSandboxVersion
     * @hide
     */
    int getTargetSandboxVersion();

    /**
     * @see ApplicationInfo#taskAffinity
     * @see R.styleable#AndroidManifestApplication_taskAffinity
     * @hide
     */
    @Nullable
    String getTaskAffinity();

    /**
     * This is an appId, the {@link ApplicationInfo#uid} if the user ID is
     * {@link android.os.UserHandle#SYSTEM}.
     *
     * @deprecated Use {@link PackageState#getAppId()} instead.
     * @hide
     */
    @Deprecated
    int getUid();

    /**
     * For use with {@link com.android.server.pm.KeySetManagerService}. Parsed in {@link
     * ParsingPackageUtils#TAG_KEY_SETS}.
     *
     * @see R.styleable#AndroidManifestUpgradeKeySet
     * @hide
     */
    @NonNull
    Set<String> getUpgradeKeySets();

    /**
     * @see R.styleable#AndroidManifestUsesLibrary
     * @hide
     */
    @NonNull
    List<String> getUsesLibraries();

    /**
     * @see R.styleable#AndroidManifestUsesNativeLibrary
     * @hide
     */
    @NonNull
    List<String> getUsesNativeLibraries();

    /**
     * Like {@link #getUsesLibraries()}, but marked optional by setting {@link
     * R.styleable#AndroidManifestUsesLibrary_required} to false . Application is expected to handle
     * absence manually.
     *
     * @see R.styleable#AndroidManifestUsesLibrary
     * @hide
     */
    @NonNull
    List<String> getUsesOptionalLibraries();

    /**
     * Like {@link #getUsesNativeLibraries()}, but marked optional by setting {@link
     * R.styleable#AndroidManifestUsesNativeLibrary_required} to false . Application is expected to
     * handle absence manually.
     *
     * @see R.styleable#AndroidManifestUsesNativeLibrary
     * @hide
     */
    @NonNull
    List<String> getUsesOptionalNativeLibraries();

    /** @hide */
    @Immutable.Ignore
    @NonNull
    List<ParsedUsesPermission> getUsesPermissions();

    /**
     * TODO(b/135203078): Move SDK library stuff to an inner data class
     *
     * @see R.styleable#AndroidManifestUsesSdkLibrary
     * @hide
     */
    @NonNull
    List<String> getUsesSdkLibraries();

    /**
     * @see R.styleable#AndroidManifestUsesSdkLibrary_certDigest
     * @hide
     */
    @Immutable.Ignore
    @Nullable
    String[][] getUsesSdkLibrariesCertDigests();

    /**
     * @see R.styleable#AndroidManifestUsesSdkLibrary_versionMajor
     * @hide
     */
    @Immutable.Ignore
    @Nullable
    long[] getUsesSdkLibrariesVersionsMajor();

    /**
     * TODO(b/135203078): Move static library stuff to an inner data class
     *
     * @see R.styleable#AndroidManifestUsesStaticLibrary
     * @hide
     */
    @NonNull
    List<String> getUsesStaticLibraries();

    /**
     * @see R.styleable#AndroidManifestUsesStaticLibrary_certDigest
     * @hide
     */
    @Immutable.Ignore
    @Nullable
    String[][] getUsesStaticLibrariesCertDigests();

    /**
     * @see R.styleable#AndroidManifestUsesStaticLibrary_version
     * @hide
     */
    @Immutable.Ignore
    @Nullable
    long[] getUsesStaticLibrariesVersions();

    /**
     * @see ApplicationInfo#volumeUuid
     * @hide
     */
    @Nullable
    String getVolumeUuid();

    /** @hide */
    boolean hasPreserveLegacyExternalStorage();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_EXT_REQUEST_FOREGROUND_SERVICE_EXEMPTION
     * @see R.styleable#AndroidManifestApplication_requestForegroundServiceExemption
     * @hide
     */
    boolean hasRequestForegroundServiceExemption();

    /**
     * @see ApplicationInfo#getRequestRawExternalStorageAccess()
     * @see R.styleable#AndroidManifestApplication_requestRawExternalStorageAccess
     * @hide
     */
    Boolean hasRequestRawExternalStorageAccess();

    /** @hide */
    boolean isApex();


    /**
     * @see R.styleable#AndroidManifestApplication_updatableSystem
     * @hide
     */
    boolean isUpdatableSystem();

    /**
     * @see ApplicationInfo#enabled
     * @see R.styleable#AndroidManifestApplication_enabled
     * @hide
     */
    boolean isEnabled();

    /**
     * @see ApplicationInfo#FLAG_EXTERNAL_STORAGE
     * @hide
     */
    boolean isExternalStorage();

    /**
     * @see ApplicationInfo#FLAG_IS_GAME
     * @see R.styleable#AndroidManifestApplication_isGame
     * @hide
     */
    @Deprecated
    boolean isGame();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_HAS_DOMAIN_URLS
     * @see R.styleable#AndroidManifestIntentFilter
     * @hide
     */
    boolean isHasDomainUrls();

    /**
     * @see PackageInfo#mOverlayIsStatic
     * @hide
     */
    boolean isOverlayIsStatic();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_PARTIALLY_DIRECT_BOOT_AWARE
     * @see R.styleable#AndroidManifestActivity_directBootAware
     * @see R.styleable#AndroidManifestProvider_directBootAware
     * @see R.styleable#AndroidManifestReceiver_directBootAware
     * @see R.styleable#AndroidManifestService_directBootAware
     * @hide
     */
    boolean isPartiallyDirectBootAware();

    /**
     * If omitted from manifest, returns true if {@link #getTargetSdkVersion()} >= {@link
     * android.os.Build.VERSION_CODES#DONUT}.
     *
     * @see R.styleable#AndroidManifestSupportsScreens_resizeable
     * @see ApplicationInfo#FLAG_RESIZEABLE_FOR_SCREENS
     * @hide
     */
    boolean isResizeable();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION
     * @see R.styleable#AppWidgetProviderInfo_resizeMode
     * @hide
     */
    boolean isResizeableActivityViaSdkVersion();

    /**
     * True means that this package/app contains an SDK library.
     * @see R.styleable#AndroidManifestSdkLibrary
     * @hide
     */
    boolean isSdkLibrary();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_STATIC_SHARED_LIBRARY
     * @see R.styleable#AndroidManifestStaticLibrary
     * @hide
     */
    boolean isStaticSharedLibrary();

    /**
     * @see PackageInfo#isStub
     * @hide
     */
    boolean isStub();

    /**
     * Set if the any of components are visible to instant applications.
     *
     * @see R.styleable#AndroidManifestActivity_visibleToInstantApps
     * @see R.styleable#AndroidManifestProvider_visibleToInstantApps
     * @see R.styleable#AndroidManifestService_visibleToInstantApps
     * @hide
     */
    boolean isVisibleToInstantApps();
}
