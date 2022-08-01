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
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureGroupInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.util.SparseArray;

import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.pkg.component.ParsedActivity;
import com.android.server.pm.pkg.component.ParsedAttribution;
import com.android.server.pm.pkg.component.ParsedInstrumentation;
import com.android.server.pm.pkg.component.ParsedPermission;
import com.android.server.pm.pkg.component.ParsedProvider;
import com.android.server.pm.pkg.component.ParsedService;

import java.util.List;

/**
 * Explicit interface used for consumers like mainline who need a {@link SystemApi @SystemApi} form
 * of {@link AndroidPackage}.
 * @hide
 */
//@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
public interface AndroidPackageApi {

    boolean areAttributionsUserVisible();

    @NonNull
    List<ParsedActivity> getActivities();

    @NonNull
    List<String> getAdoptPermissions();

    @Nullable
    String getAppComponentFactory();

    @NonNull
    List<ParsedAttribution> getAttributions();

    int getAutoRevokePermissions();

    @Nullable
    String getBackupAgentName();

    int getBanner();

    @NonNull
    String getBaseApkPath();

    int getBaseRevisionCode();

    int getCategory();

    @Nullable
    String getClassLoaderName();

    @Nullable
    String getClassName();

    int getCompatibleWidthLimitDp();

    int getCompileSdkVersion();

    @Nullable
    String getCompileSdkVersionCodeName();

    @NonNull
    List<ConfigurationInfo> getConfigPreferences();

    int getDataExtractionRules();

    int getDescriptionRes();

    @NonNull
    List<FeatureGroupInfo> getFeatureGroups();

    int getFullBackupContent();

    int getGwpAsanMode();

    int getIconRes();

    @NonNull
    List<String> getImplicitPermissions();

    int getInstallLocation();

    @NonNull
    List<ParsedInstrumentation> getInstrumentations();

    int getLabelRes();

    int getLargestWidthLimitDp();

    int getLogo();

    long getLongVersionCode();

    @Nullable
    String getManageSpaceActivityName();

    float getMaxAspectRatio();

    int getMemtagMode();

    float getMinAspectRatio();

    int getMinSdkVersion();

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

    int getNetworkSecurityConfigRes();

    @Nullable
    CharSequence getNonLocalizedLabel();

    @NonNull
    String getPackageName();

    @NonNull
    String getPath();

    @Nullable
    String getPermission();

    @NonNull
    List<ParsedPermission> getPermissions();

    @NonNull
    String getProcessName();

    @NonNull
    List<ParsedProvider> getProviders();

    @NonNull
    List<ParsedActivity> getReceivers();

    @NonNull
    List<FeatureInfo> getRequestedFeatures();

    @NonNull
    List<String> getRequestedPermissions();

    @Nullable
    String getRequiredAccountType();

    int getRequiresSmallestWidthDp();

    @SuppressLint("AutoBoxing")
    @Nullable
    Boolean getResizeableActivity();

    @Nullable
    String getRestrictedAccountType();

    int getRoundIconRes();

    /**
         * @see ApplicationInfo#secondaryNativeLibraryDir
         */
    @Nullable
    String getSecondaryNativeLibraryDir();

    @NonNull
    List<ParsedService> getServices();

    @Nullable
    String getSharedUserId();

    int getSharedUserLabel();

    @NonNull
    String[] getSplitClassLoaderNames();

    @NonNull
    String[] getSplitCodePaths();

    @Nullable
    SparseArray<int[]> getSplitDependencies();

    @NonNull
    String[] getSplitNames();

    @NonNull
    int[] getSplitRevisionCodes();

    int getTargetSandboxVersion();

    int getTargetSdkVersion();

    @Nullable
    String getTaskAffinity();

    int getTheme();

    int getUiOptions();

    /**
         * This is an appId, the {@link ApplicationInfo#uid} if the user ID is
         * {@link android.os.UserHandle#SYSTEM}.
         *
         * @deprecated Use {@link PackageState#getAppId()} instead.
         */
    @Deprecated
    int getUid();

    @Nullable
    String getVersionName();

    @Nullable
    String getVolumeUuid();

    @Nullable
    String getZygotePreloadName();

    boolean hasRequestForegroundServiceExemption();

    @SuppressLint("AutoBoxing")
    @Nullable
    Boolean hasRequestRawExternalStorageAccess();

    boolean isAllowAudioPlaybackCapture();

    boolean isAllowBackup();

    boolean isAllowClearUserData();

    boolean isAllowClearUserDataOnFailedRestore();

    boolean isAllowNativeHeapPointerTagging();

    boolean isAllowTaskReparenting();

    boolean isAnyDensity();

    boolean isApex();

    boolean isBackupInForeground();

    boolean isBaseHardwareAccelerated();

    boolean isCantSaveState();

    /**
     * @see PackageInfo#coreApp
     */
    boolean isCoreApp();

    boolean isCrossProfile();

    boolean isDebuggable();

    boolean isDefaultToDeviceProtectedStorage();

    boolean isDirectBootAware();

    boolean isEnabled();

    boolean isExternalStorage();

    boolean isExtractNativeLibs();

    /**
         * @see ApplicationInfo#FLAG_FACTORY_TEST
         */
    boolean isFactoryTest();

    boolean isFullBackupOnly();

    boolean isHasCode();

    boolean isHasDomainUrls();

    boolean isHasFragileUserData();

    boolean isIsolatedSplitLoading();

    boolean isKillAfterRestore();

    boolean isLargeHeap();

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

    boolean isOverlay();

    boolean isPartiallyDirectBootAware();

    boolean isPersistent();

    /**
         * @see ApplicationInfo#PRIVATE_FLAG_PRIVILEGED
         */
    boolean isPrivileged();

    /**
         * @see ApplicationInfo#PRIVATE_FLAG_PRODUCT
         */
    boolean isProduct();

    boolean isProfileable();

    boolean isProfileableByShell();

    boolean isRequestLegacyExternalStorage();

    boolean isRequiredForAllUsers();

    boolean isResizeable();

    boolean isResizeableActivityViaSdkVersion();

    boolean isRestoreAnyVersion();

    boolean isSdkLibrary();

    /**
         * @see ApplicationInfo#PRIVATE_FLAG_SIGNED_WITH_PLATFORM_KEY
         */
    boolean isSignedWithPlatformKey();

    boolean isStaticSharedLibrary();

    /**
     * @see PackageInfo#isStub
     */
    boolean isStub();

    boolean isSupportsExtraLargeScreens();

    boolean isSupportsLargeScreens();

    boolean isSupportsNormalScreens();

    boolean isSupportsRtl();

    boolean isSupportsSmallScreens();

    /**
     * @see ApplicationInfo#FLAG_SYSTEM
     */
    boolean isSystem();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_SYSTEM_EXT
     */
    boolean isSystemExt();

    boolean isTestOnly();

    boolean isUseEmbeddedDex();

    boolean isUsesCleartextTraffic();

    boolean isUsesNonSdkApi();

    /**
         * @see ApplicationInfo#PRIVATE_FLAG_VENDOR
         */
    boolean isVendor();

    boolean isVmSafeMode();
}
