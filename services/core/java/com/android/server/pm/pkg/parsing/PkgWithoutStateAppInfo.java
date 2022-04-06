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

package com.android.server.pm.pkg.parsing;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.ApplicationInfo;
import android.util.SparseArray;

import java.util.Set;

/**
 * Container for fields that are eventually exposed through {@link ApplicationInfo}.
 * <p>
 * The following are dependent on system state and explicitly removed from this interface. They must
 * be accessed by other means:
 * <ul>
 *    <li>{@link ApplicationInfo#credentialProtectedDataDir}</li>
 *    <li>{@link ApplicationInfo#dataDir}</li>
 *    <li>{@link ApplicationInfo#deviceProtectedDataDir}</li>
 *    <li>{@link ApplicationInfo#enabledSetting}</li>
 *    <li>{@link ApplicationInfo#enabled}</li>
 *    <li>{@link ApplicationInfo#FLAG_INSTALLED}</li>
 *    <li>{@link ApplicationInfo#FLAG_STOPPED}</li>
 *    <li>{@link ApplicationInfo#FLAG_SUSPENDED}</li>
 *    <li>{@link ApplicationInfo#FLAG_UPDATED_SYSTEM_APP}</li>
 *    <li>{@link ApplicationInfo#hiddenUntilInstalled}</li>
 *    <li>{@link ApplicationInfo#primaryCpuAbi}</li>
 *    <li>{@link ApplicationInfo#PRIVATE_FLAG_HIDDEN}</li>
 *    <li>{@link ApplicationInfo#PRIVATE_FLAG_INSTANT}</li>
 *    <li>{@link ApplicationInfo#PRIVATE_FLAG_REQUIRED_FOR_SYSTEM_USER}</li>
 *    <li>{@link ApplicationInfo#PRIVATE_FLAG_VIRTUAL_PRELOAD}</li>
 *    <li>{@link ApplicationInfo#resourceDirs}</li>
 *    <li>{@link ApplicationInfo#secondaryCpuAbi}</li>
 *    <li>{@link ApplicationInfo#seInfoUser}</li>
 *    <li>{@link ApplicationInfo#seInfo}</li>
 *    <li>{@link ApplicationInfo#sharedLibraryFiles}</li>
 *    <li>{@link ApplicationInfo#sharedLibraryInfos}</li>
 *    <li>{@link ApplicationInfo#uid}</li>
 * </ul>
 * The following are derived from other fields and thus not provided specifically:
 * <ul>
 *    <li>{@link ApplicationInfo#getBaseResourcePath}</li>
 *    <li>{@link ApplicationInfo#getResourcePath}</li>
 *    <li>{@link ApplicationInfo#getSplitResourcePaths}</li>
 *    <li>{@link ApplicationInfo#publicSourceDir}</li>
 *    <li>{@link ApplicationInfo#scanPublicSourceDir}</li>
 *    <li>{@link ApplicationInfo#splitPublicSourceDirs}</li>
 *    <li>{@link ApplicationInfo#storageUuid}</li>
 * </ul>
 * The following were deprecated at migration time and thus removed from this interface:
 * <ul>
 *    <li>{@link ApplicationInfo#FLAG_IS_GAME}</li>
 *    <li>{@link ApplicationInfo#targetSandboxVersion}</li>
 *    <li>{@link ApplicationInfo#versionCode}</li>
 * </ul>
 * TODO: The following fields are just not available at all. Never filled, even by legacy parsing?
 * <ul>
 *    <li>{@link ApplicationInfo#FLAG_IS_DATA_ONLY}</li>
 * </ul>
 *
 * @hide
 */
public interface PkgWithoutStateAppInfo {

    /**
     * @see ApplicationInfo#areAttributionsUserVisible()
     * @see R.styleable#AndroidManifestApplication_attributionsAreUserVisible
     */
    @Nullable
    boolean areAttributionsUserVisible();

    /**
     * @see ApplicationInfo#appComponentFactory
     * @see R.styleable#AndroidManifestApplication_appComponentFactory
     */
    @Nullable
    String getAppComponentFactory();

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
     * @see ApplicationInfo#longVersionCode
     */
    long getLongVersionCode();

    /**
     * @see ApplicationInfo#manageSpaceActivityName
     * @see R.styleable#AndroidManifestApplication_manageSpaceActivity
     */
    @Nullable
    String getManageSpaceActivityName();

    /**
     * @see ApplicationInfo#maxAspectRatio
     * @see R.styleable#AndroidManifestApplication_maxAspectRatio
     */
    float getMaxAspectRatio();

    /**
     * @see ApplicationInfo#getMemtagMode()
     * @see R.styleable#AndroidManifestApplication_memtagMode
     */
    @ApplicationInfo.MemtagMode
    int getMemtagMode();

    /**
     * @see ApplicationInfo#minAspectRatio
     * @see R.styleable#AndroidManifestApplication_minAspectRatio
     */
    float getMinAspectRatio();

    /**
     * @see ApplicationInfo#minSdkVersion
     * @see R.styleable#AndroidManifestUsesSdk_minSdkVersion
     */
    int getMinSdkVersion();

    /**
     * @see R.styleable#AndroidManifestUsesSdk_maxSdkVersion
     */
    int getMaxSdkVersion();

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
     * @see ApplicationInfo#knownActivityEmbeddingCerts
     * @see R.styleable#AndroidManifestApplication_knownActivityEmbeddingCerts
     */
    @NonNull
    Set<String> getKnownActivityEmbeddingCerts();

    /**
     * @see ApplicationInfo#processName
     * @see R.styleable#AndroidManifestApplication_process
     */
    @NonNull
    String getProcessName();

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
     * @see ApplicationInfo#roundIconRes
     * @see R.styleable#AndroidManifestApplication_roundIcon
     */
    int getRoundIconRes();

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
     * @see ApplicationInfo#targetSdkVersion
     * @see R.styleable#AndroidManifestUsesSdk_targetSdkVersion
     */
    int getTargetSdkVersion();

    /**
     * @see ApplicationInfo#targetSandboxVersion
     * @see R.styleable#AndroidManifest_targetSandboxVersion
     */
    int getTargetSandboxVersion();

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
     * @see ApplicationInfo#volumeUuid
     */
    @Nullable
    String getVolumeUuid();

    /**
     * @see ApplicationInfo#zygotePreloadName
     */
    @Nullable
    String getZygotePreloadName();

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
     * @see ApplicationInfo#FLAG_FULL_BACKUP_ONLY
     */
    boolean isFullBackupOnly();

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
     * @see ApplicationInfo#FLAG_MULTIARCH
     */
    boolean isMultiArch();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_IS_RESOURCE_OVERLAY
     * @see ApplicationInfo#isResourceOverlay()
     */
    boolean isOverlay();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_PARTIALLY_DIRECT_BOOT_AWARE
     */
    boolean isPartiallyDirectBootAware();

    /**
     * @see ApplicationInfo#FLAG_PERSISTENT
     */
    boolean isPersistent();

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
     * @see ApplicationInfo#PRIVATE_FLAG_STATIC_SHARED_LIBRARY
     */
    boolean isStaticSharedLibrary();

    /**
     * True means that this package/app contains an SDK library.
     */
    boolean isSdkLibrary();

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
     * @see ApplicationInfo#FLAG_TEST_ONLY
     */
    boolean isTestOnly();

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
     * @see ApplicationInfo#FLAG_VM_SAFE_MODE
     */
    boolean isVmSafeMode();
}
