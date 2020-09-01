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

package com.android.server.pm.parsing.pkg;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.ApplicationInfo;
import android.util.SparseArray;

import com.android.internal.R;

/**
 * Container for fields that are eventually exposed through {@link ApplicationInfo}.
 *
 * Done to separate the meaningless, re-directed JavaDoc for methods and to separate what's
 * exposed vs not exposed to core.
 *
 * @hide
 */
interface PkgAppInfo {

    /** @see ApplicationInfo#PRIVATE_FLAG_CANT_SAVE_STATE */
    boolean isCantSaveState();

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
     * @see ApplicationInfo#iconRes
     * @see R.styleable#AndroidManifestApplication_icon
     */
    int getIconRes();

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
     * @see ApplicationInfo#minSdkVersion
     * @see R.styleable#AndroidManifestUsesSdk_minSdkVersion
     */
    int getMinSdkVersion();

    /** @see ApplicationInfo#nativeLibraryDir */
    @Nullable
    String getNativeLibraryDir();

    /** @see ApplicationInfo#nativeLibraryRootDir */
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
     * @see ApplicationInfo#nonLocalizedLabel
     * @see R.styleable#AndroidManifestApplication_label
     */
    @Nullable
    CharSequence getNonLocalizedLabel();

    /**
     * @see ApplicationInfo#permission
     * @see R.styleable#AndroidManifestApplication_permission
     */
    @Nullable
    String getPermission();

    /**
     * TODO(b/135203078): Hide this in the utility, should never be accessed directly
     * @see ApplicationInfo#primaryCpuAbi
     */
    @Nullable
    String getPrimaryCpuAbi();

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
     * @see ApplicationInfo#roundIconRes
     * @see R.styleable#AndroidManifestApplication_roundIcon
     */
    int getRoundIconRes();

    /** @see ApplicationInfo#seInfo */
    @Nullable
    String getSeInfo();

    /** @see ApplicationInfo#seInfoUser */
    @Nullable
    String getSeInfoUser();

    /** @see ApplicationInfo#secondaryCpuAbi */
    @Nullable
    String getSecondaryCpuAbi();

    /** @see ApplicationInfo#secondaryNativeLibraryDir */
    @Nullable
    String getSecondaryNativeLibraryDir();

    /**
     * @see ApplicationInfo#installLocation
     * @see R.styleable#AndroidManifest_installLocation
     */
    int getInstallLocation();

    /**
     * @see ApplicationInfo#splitClassLoaderNames
     * @see R.styleable#AndroidManifestApplication_classLoader
     */
    @Nullable
    String[] getSplitClassLoaderNames();

    /** @see ApplicationInfo#splitSourceDirs */
    @Nullable
    String[] getSplitCodePaths();

    /** @see ApplicationInfo#splitDependencies */
    @Nullable
    SparseArray<int[]> getSplitDependencies();

    /**
     * @see ApplicationInfo#targetSandboxVersion
     * @see R.styleable#AndroidManifest_targetSandboxVersion
     */
    @Deprecated
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

    /** @see ApplicationInfo#uid */
    int getUid();

    /** @see ApplicationInfo#longVersionCode */
    long getLongVersionCode();

    /** @see ApplicationInfo#versionCode */
    @Deprecated
    int getVersionCode();

    /** @see ApplicationInfo#volumeUuid */
    @Nullable
    String getVolumeUuid();

    /** @see ApplicationInfo#zygotePreloadName */
    @Nullable
    String getZygotePreloadName();

    /** @see ApplicationInfo#FLAG_HAS_CODE */
    boolean isHasCode();

    /** @see ApplicationInfo#FLAG_ALLOW_TASK_REPARENTING */
    boolean isAllowTaskReparenting();

    /** @see ApplicationInfo#FLAG_MULTIARCH */
    boolean isMultiArch();

    /** @see ApplicationInfo#FLAG_EXTRACT_NATIVE_LIBS */
    boolean isExtractNativeLibs();

    /** @see ApplicationInfo#FLAG_DEBUGGABLE */
    boolean isDebuggable();

    /** @see ApplicationInfo#FLAG_VM_SAFE_MODE */
    boolean isVmSafeMode();

    /** @see ApplicationInfo#FLAG_PERSISTENT */
    boolean isPersistent();

    /** @see ApplicationInfo#FLAG_ALLOW_BACKUP */
    boolean isAllowBackup();

    /** @see ApplicationInfo#FLAG_TEST_ONLY */
    boolean isTestOnly();

    /** @see ApplicationInfo#PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION */
    boolean isResizeableActivityViaSdkVersion();

    /** @see ApplicationInfo#PRIVATE_FLAG_HAS_DOMAIN_URLS */
    boolean isHasDomainUrls();

    /** @see ApplicationInfo#PRIVATE_FLAG_REQUEST_LEGACY_EXTERNAL_STORAGE */
    boolean isRequestLegacyExternalStorage();

    /** @see ApplicationInfo#FLAG_HARDWARE_ACCELERATED */
    boolean isBaseHardwareAccelerated();

    /** @see ApplicationInfo#PRIVATE_FLAG_DEFAULT_TO_DEVICE_PROTECTED_STORAGE */
    boolean isDefaultToDeviceProtectedStorage();

    /** @see ApplicationInfo#PRIVATE_FLAG_DIRECT_BOOT_AWARE */
    boolean isDirectBootAware();

    /** @see ApplicationInfo#PRIVATE_FLAG_PARTIALLY_DIRECT_BOOT_AWARE */
    boolean isPartiallyDirectBootAware();

    /** @see ApplicationInfo#PRIVATE_FLAG_USE_EMBEDDED_DEX */
    boolean isUseEmbeddedDex();

    /** @see ApplicationInfo#FLAG_EXTERNAL_STORAGE */
    boolean isExternalStorage();

    /** @see ApplicationInfo#nativeLibraryRootRequiresIsa */
    boolean isNativeLibraryRootRequiresIsa();

    /** @see ApplicationInfo#PRIVATE_FLAG_ODM */
    boolean isOdm();

    /** @see ApplicationInfo#PRIVATE_FLAG_OEM */
    boolean isOem();

    /** @see ApplicationInfo#PRIVATE_FLAG_PRIVILEGED */
    boolean isPrivileged();

    /** @see ApplicationInfo#PRIVATE_FLAG_PRODUCT */
    boolean isProduct();

    /** @see ApplicationInfo#PRIVATE_FLAG_PROFILEABLE_BY_SHELL */
    boolean isProfileableByShell();

    /** @see ApplicationInfo#PRIVATE_FLAG_STATIC_SHARED_LIBRARY */
    boolean isStaticSharedLibrary();

    /** @see ApplicationInfo#FLAG_SYSTEM */
    boolean isSystem();

    /** @see ApplicationInfo#PRIVATE_FLAG_SYSTEM_EXT */
    boolean isSystemExt();

    /** @see ApplicationInfo#PRIVATE_FLAG_VENDOR */
    boolean isVendor();

    /** @see ApplicationInfo#PRIVATE_FLAG_ISOLATED_SPLIT_LOADING */
    boolean isIsolatedSplitLoading();

    /**
     * @see ApplicationInfo#enabled
     * @see R.styleable#AndroidManifestApplication_enabled
     */
    boolean isEnabled();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_IS_RESOURCE_OVERLAY
     * @see ApplicationInfo#isResourceOverlay()
     */
    boolean isOverlay();

    /** @see ApplicationInfo#PRIVATE_FLAG_USES_NON_SDK_API */
    boolean isUsesNonSdkApi();

    /** @see ApplicationInfo#PRIVATE_FLAG_SIGNED_WITH_PLATFORM_KEY */
    boolean isSignedWithPlatformKey();

    /** @see ApplicationInfo#FLAG_KILL_AFTER_RESTORE */
    boolean isKillAfterRestore();

    /** @see ApplicationInfo#FLAG_RESTORE_ANY_VERSION */
    boolean isRestoreAnyVersion();

    /** @see ApplicationInfo#FLAG_FULL_BACKUP_ONLY */
    boolean isFullBackupOnly();

    /** @see ApplicationInfo#FLAG_ALLOW_CLEAR_USER_DATA */
    boolean isAllowClearUserData();

    /** @see ApplicationInfo#FLAG_LARGE_HEAP */
    boolean isLargeHeap();

    /** @see ApplicationInfo#FLAG_USES_CLEARTEXT_TRAFFIC */
    boolean isUsesCleartextTraffic();

    /** @see ApplicationInfo#FLAG_SUPPORTS_RTL */
    boolean isSupportsRtl();

    /** @see ApplicationInfo#FLAG_IS_GAME */
    @Deprecated
    boolean isGame();

    /** @see ApplicationInfo#FLAG_FACTORY_TEST */
    boolean isFactoryTest();

    /**
     * If omitted from manifest, returns true if {@link #getTargetSdkVersion()} >=
     * {@link android.os.Build.VERSION_CODES#DONUT}.
     * @see R.styleable#AndroidManifestSupportsScreens_smallScreens
     * @see ApplicationInfo#FLAG_SUPPORTS_SMALL_SCREENS
     */
    boolean isSupportsSmallScreens();

    /**
     * If omitted from manifest, returns true.
     * @see R.styleable#AndroidManifestSupportsScreens_normalScreens
     * @see ApplicationInfo#FLAG_SUPPORTS_NORMAL_SCREENS
     */
    boolean isSupportsNormalScreens();

    /**
     * If omitted from manifest, returns true if {@link #getTargetSdkVersion()} >=
     * {@link android.os.Build.VERSION_CODES#DONUT}.
     * @see R.styleable#AndroidManifestSupportsScreens_largeScreens
     * @see ApplicationInfo#FLAG_SUPPORTS_LARGE_SCREENS
     */
    boolean isSupportsLargeScreens();

    /**
     * If omitted from manifest, returns true if {@link #getTargetSdkVersion()} >=
     * {@link android.os.Build.VERSION_CODES#GINGERBREAD}.
     * @see R.styleable#AndroidManifestSupportsScreens_xlargeScreens
     * @see ApplicationInfo#FLAG_SUPPORTS_XLARGE_SCREENS
     */
    boolean isSupportsExtraLargeScreens();

    /**
     * If omitted from manifest, returns true if {@link #getTargetSdkVersion()} >=
     * {@link android.os.Build.VERSION_CODES#DONUT}.
     * @see R.styleable#AndroidManifestSupportsScreens_resizeable
     * @see ApplicationInfo#FLAG_RESIZEABLE_FOR_SCREENS
     */
    boolean isResizeable();

    /**
     * If omitted from manifest, returns true if {@link #getTargetSdkVersion()} >=
     * {@link android.os.Build.VERSION_CODES#DONUT}.
     * @see R.styleable#AndroidManifestSupportsScreens_anyDensity
     * @see ApplicationInfo#FLAG_SUPPORTS_SCREEN_DENSITIES
     */
    boolean isAnyDensity();

    /** @see ApplicationInfo#PRIVATE_FLAG_BACKUP_IN_FOREGROUND */
    boolean isBackupInForeground();

    /** @see ApplicationInfo#PRIVATE_FLAG_ALLOW_CLEAR_USER_DATA_ON_FAILED_RESTORE */
    boolean isAllowClearUserDataOnFailedRestore();

    /** @see ApplicationInfo#PRIVATE_FLAG_ALLOW_AUDIO_PLAYBACK_CAPTURE */
    boolean isAllowAudioPlaybackCapture();

    /** @see ApplicationInfo#PRIVATE_FLAG_HAS_FRAGILE_USER_DATA */
    boolean isHasFragileUserData();
}
