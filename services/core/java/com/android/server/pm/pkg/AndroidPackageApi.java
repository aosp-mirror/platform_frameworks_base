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
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureGroupInfo;
import android.content.pm.FeatureInfo;
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
 * of {@link AndroidPackage}. *
 * @hide
 */
//@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
public interface AndroidPackageApi {

    boolean areAttributionsUserVisible();

    @Nullable
    String getAppComponentFactory();

    int getAutoRevokePermissions();

    @Nullable
    String getBackupAgentName();

    int getBanner();

    @NonNull
    String getBaseApkPath();

    int getCategory();

    @Nullable
    String getClassLoaderName();

    @Nullable
    String getClassName();

    int getCompatibleWidthLimitDp();

    int getDataExtractionRules();

    int getDescriptionRes();

    int getFullBackupContent();

    int getGwpAsanMode();

    int getIconRes();

    int getInstallLocation();

    int getLabelRes();

    int getLargestWidthLimitDp();

    int getLogo();

    @Nullable
    String getManageSpaceActivityName();

    float getMaxAspectRatio();

    int getMemtagMode();

    float getMinAspectRatio();

    int getMinSdkVersion();

    int getNativeHeapZeroInitialized();

    int getNetworkSecurityConfigRes();

    @Nullable
    CharSequence getNonLocalizedLabel();

    @NonNull
    String getPath();

    @Nullable
    String getPermission();

    @NonNull
    String getProcessName();

    int getRequiresSmallestWidthDp();

    @SuppressLint("AutoBoxing")
    @Nullable
    Boolean getResizeableActivity();

    int getRoundIconRes();

    @NonNull
    String[] getSplitClassLoaderNames();

    @NonNull
    String[] getSplitCodePaths();

    @Nullable
    SparseArray<int[]> getSplitDependencies();

    int getTargetSdkVersion();

    int getTargetSandboxVersion();

    @Nullable
    String getTaskAffinity();

    int getTheme();

    int getUiOptions();

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

    boolean isBackupInForeground();

    boolean isBaseHardwareAccelerated();

    boolean isCantSaveState();

    boolean isCrossProfile();

    boolean isDebuggable();

    boolean isDefaultToDeviceProtectedStorage();

    boolean isDirectBootAware();

    boolean isEnabled();

    boolean isExternalStorage();

    boolean isExtractNativeLibs();

    boolean isFullBackupOnly();

    boolean isHasCode();

    boolean isHasDomainUrls();

    boolean isHasFragileUserData();

    boolean isIsolatedSplitLoading();

    boolean isKillAfterRestore();

    boolean isLargeHeap();

    boolean isMultiArch();

    boolean isOverlay();

    boolean isPartiallyDirectBootAware();

    boolean isPersistent();

    boolean isProfileable();

    boolean isProfileableByShell();

    boolean isRequestLegacyExternalStorage();

    boolean isResizeable();

    boolean isResizeableActivityViaSdkVersion();

    boolean isRestoreAnyVersion();

    boolean isStaticSharedLibrary();

    boolean isSdkLibrary();

    boolean isSupportsExtraLargeScreens();

    boolean isSupportsLargeScreens();

    boolean isSupportsNormalScreens();

    boolean isSupportsRtl();

    boolean isSupportsSmallScreens();

    boolean isTestOnly();

    boolean isUseEmbeddedDex();

    boolean isUsesCleartextTraffic();

    boolean isUsesNonSdkApi();

    boolean isVmSafeMode();

    @NonNull
    List<ParsedActivity> getActivities();

    @NonNull
    List<ParsedAttribution> getAttributions();

    @NonNull
    List<String> getAdoptPermissions();

    int getBaseRevisionCode();

    int getCompileSdkVersion();

    @Nullable
    String getCompileSdkVersionCodeName();

    @NonNull
    List<ConfigurationInfo> getConfigPreferences();

    @NonNull
    List<FeatureGroupInfo> getFeatureGroups();

    @NonNull
    List<String> getImplicitPermissions();

    @NonNull
    List<ParsedInstrumentation> getInstrumentations();

    long getLongVersionCode();

    @NonNull
    String getPackageName();

    @NonNull
    List<ParsedPermission> getPermissions();

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

    @Nullable
    String getRestrictedAccountType();

    @NonNull
    List<ParsedService> getServices();

    @Nullable
    String getSharedUserId();

    int getSharedUserLabel();

    @NonNull
    String[] getSplitNames();

    @NonNull
    int[] getSplitRevisionCodes();

    @Nullable
    String getVersionName();

    boolean isRequiredForAllUsers();

    @Nullable
    String getNativeLibraryDir();

    @Nullable
    String getNativeLibraryRootDir();

    @Nullable
    String getSecondaryNativeLibraryDir();

    int getUid();

    boolean isFactoryTest();

    boolean isNativeLibraryRootRequiresIsa();

    boolean isOdm();

    boolean isOem();

    boolean isPrivileged();

    boolean isProduct();

    boolean isSignedWithPlatformKey();

    boolean isSystem();

    boolean isSystemExt();

    boolean isVendor();

    boolean isCoreApp();

    boolean isStub();
}
