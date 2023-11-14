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

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureGroupInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.content.pm.SigningDetails;
import android.os.Bundle;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.R;
import com.android.internal.pm.pkg.component.ParsedActivity;
import com.android.internal.pm.pkg.component.ParsedApexSystemService;
import com.android.internal.pm.pkg.component.ParsedAttribution;
import com.android.internal.pm.pkg.component.ParsedInstrumentation;
import com.android.internal.pm.pkg.component.ParsedIntentInfo;
import com.android.internal.pm.pkg.component.ParsedPermission;
import com.android.internal.pm.pkg.component.ParsedPermissionGroup;
import com.android.internal.pm.pkg.component.ParsedProcess;
import com.android.internal.pm.pkg.component.ParsedProvider;
import com.android.internal.pm.pkg.component.ParsedService;
import com.android.internal.pm.pkg.component.ParsedUsesPermission;
import com.android.server.pm.parsing.pkg.ParsedPackage;

import java.security.PublicKey;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Methods used for mutation during direct package parsing.
 *
 * @hide
 */
@SuppressWarnings("UnusedReturnValue")
public interface ParsingPackage {

    ParsingPackage addActivity(ParsedActivity parsedActivity);

    ParsingPackage addAdoptPermission(String adoptPermission);

    ParsingPackage addApexSystemService(ParsedApexSystemService parsedApexSystemService);

    ParsingPackage addConfigPreference(ConfigurationInfo configPreference);

    ParsingPackage addFeatureGroup(FeatureGroupInfo featureGroup);

    ParsingPackage addImplicitPermission(String permission);

    ParsingPackage addInstrumentation(ParsedInstrumentation instrumentation);

    ParsingPackage addKeySet(String keySetName, PublicKey publicKey);

    ParsingPackage addLibraryName(String libraryName);

    ParsingPackage addOriginalPackage(String originalPackage);

    ParsingPackage addOverlayable(String overlayableName, String actorName);

    ParsingPackage addPermission(ParsedPermission permission);

    ParsingPackage addPermissionGroup(ParsedPermissionGroup permissionGroup);

    ParsingPackage addPreferredActivityFilter(String className, ParsedIntentInfo intentInfo);

    /** Add a property to the application scope */
    ParsingPackage addProperty(PackageManager.Property property);

    ParsingPackage addProtectedBroadcast(String protectedBroadcast);

    ParsingPackage addProvider(ParsedProvider parsedProvider);

    ParsingPackage addAttribution(ParsedAttribution attribution);

    ParsingPackage addReceiver(ParsedActivity parsedReceiver);

    ParsingPackage addReqFeature(FeatureInfo reqFeature);

    ParsingPackage addUsesPermission(ParsedUsesPermission parsedUsesPermission);

    ParsingPackage addService(ParsedService parsedService);

    ParsingPackage addUsesLibrary(String libraryName);

    ParsingPackage addUsesOptionalLibrary(String libraryName);

    ParsingPackage addUsesNativeLibrary(String libraryName);

    ParsingPackage addUsesOptionalNativeLibrary(String libraryName);

    ParsingPackage addUsesSdkLibrary(String libraryName, long versionMajor,
            String[] certSha256Digests);

    ParsingPackage addUsesStaticLibrary(String libraryName, long version,
            String[] certSha256Digests);

    ParsingPackage addQueriesIntent(Intent intent);

    ParsingPackage addQueriesPackage(String packageName);

    ParsingPackage addQueriesProvider(String authority);

    /** Sets a process name -> {@link ParsedProcess} map coming from the <processes> tag. */
    ParsingPackage setProcesses(@NonNull Map<String, ParsedProcess> processes);

    ParsingPackage asSplit(
            String[] splitNames,
            String[] splitCodePaths,
            int[] splitRevisionCodes,
            @Nullable SparseArray<int[]> splitDependencies
    );

    ParsingPackage setMetaData(Bundle metaData);

    ParsingPackage setForceQueryable(boolean forceQueryable);

    ParsingPackage setMaxAspectRatio(float maxAspectRatio);

    ParsingPackage setMinAspectRatio(float minAspectRatio);

    ParsingPackage setPermission(String permission);

    ParsingPackage setProcessName(String processName);

    ParsingPackage setSharedUserId(String sharedUserId);

    ParsingPackage setStaticSharedLibraryName(String staticSharedLibName);

    ParsingPackage setTaskAffinity(String taskAffinity);

    ParsingPackage setTargetSdkVersion(int targetSdkVersion);

    ParsingPackage setUiOptions(int uiOptions);

    ParsingPackage setHardwareAccelerated(boolean hardwareAccelerated);

    ParsingPackage setResizeableActivity(Boolean resizeable);

    ParsingPackage setResizeableActivityViaSdkVersion(boolean resizeableViaSdkVersion);

    ParsingPackage setAllowAudioPlaybackCapture(boolean allowAudioPlaybackCapture);

    ParsingPackage setBackupAllowed(boolean allowBackup);

    ParsingPackage setClearUserDataAllowed(boolean allowClearUserData);

    ParsingPackage setClearUserDataOnFailedRestoreAllowed(
            boolean allowClearUserDataOnFailedRestore);

    ParsingPackage setTaskReparentingAllowed(boolean allowTaskReparenting);

    ParsingPackage setResourceOverlay(boolean isResourceOverlay);

    ParsingPackage setBackupInForeground(boolean backupInForeground);

    ParsingPackage setSaveStateDisallowed(boolean cantSaveState);

    ParsingPackage setDebuggable(boolean debuggable);

    ParsingPackage setDefaultToDeviceProtectedStorage(boolean defaultToDeviceProtectedStorage);

    ParsingPackage setDirectBootAware(boolean directBootAware);

    ParsingPackage setExternalStorage(boolean externalStorage);

    ParsingPackage setExtractNativeLibrariesRequested(boolean extractNativeLibs);

    ParsingPackage setFullBackupOnly(boolean fullBackupOnly);

    ParsingPackage setDeclaredHavingCode(boolean hasCode);

    ParsingPackage setUserDataFragile(boolean hasFragileUserData);

    ParsingPackage setGame(boolean isGame);

    ParsingPackage setIsolatedSplitLoading(boolean isolatedSplitLoading);

    ParsingPackage setKillAfterRestoreAllowed(boolean killAfterRestore);

    ParsingPackage setLargeHeap(boolean largeHeap);

    ParsingPackage setMultiArch(boolean multiArch);

    ParsingPackage setPartiallyDirectBootAware(boolean partiallyDirectBootAware);

    ParsingPackage setPersistent(boolean persistent);

    ParsingPackage setProfileableByShell(boolean profileableByShell);

    ParsingPackage setProfileable(boolean profileable);

    ParsingPackage setRequestLegacyExternalStorage(boolean requestLegacyExternalStorage);

    ParsingPackage setAllowNativeHeapPointerTagging(boolean allowNativeHeapPointerTagging);

    ParsingPackage setAutoRevokePermissions(int autoRevokePermissions);

    ParsingPackage setPreserveLegacyExternalStorage(boolean preserveLegacyExternalStorage);

    ParsingPackage setRestoreAnyVersion(boolean restoreAnyVersion);

    ParsingPackage setSdkLibraryName(String sdkLibName);

    ParsingPackage setSdkLibVersionMajor(int sdkLibVersionMajor);

    ParsingPackage setSdkLibrary(boolean sdkLibrary);

    ParsingPackage setSplitHasCode(int splitIndex, boolean splitHasCode);

    ParsingPackage setStaticSharedLibrary(boolean staticSharedLibrary);

    ParsingPackage setRtlSupported(boolean supportsRtl);

    ParsingPackage setTestOnly(boolean testOnly);

    ParsingPackage setUseEmbeddedDex(boolean useEmbeddedDex);

    ParsingPackage setCleartextTrafficAllowed(boolean usesCleartextTraffic);

    ParsingPackage setNonSdkApiRequested(boolean usesNonSdkApi);

    ParsingPackage setVisibleToInstantApps(boolean visibleToInstantApps);

    ParsingPackage setVmSafeMode(boolean vmSafeMode);

    ParsingPackage removeUsesOptionalLibrary(String libraryName);

    ParsingPackage removeUsesOptionalNativeLibrary(String libraryName);

    ParsingPackage setAnyDensity(int anyDensity);

    ParsingPackage setAppComponentFactory(String appComponentFactory);

    ParsingPackage setBackupAgentName(String backupAgentName);

    ParsingPackage setBannerResourceId(int banner);

    ParsingPackage setCategory(int category);

    ParsingPackage setClassLoaderName(String classLoaderName);

    ParsingPackage setApplicationClassName(String className);

    ParsingPackage setCompatibleWidthLimitDp(int compatibleWidthLimitDp);

    ParsingPackage setDescriptionResourceId(int descriptionRes);

    ParsingPackage setEnabled(boolean enabled);

    ParsingPackage setGwpAsanMode(@ApplicationInfo.GwpAsanMode int gwpAsanMode);

    ParsingPackage setMemtagMode(@ApplicationInfo.MemtagMode int memtagMode);

    ParsingPackage setNativeHeapZeroInitialized(
            @ApplicationInfo.NativeHeapZeroInitialized int nativeHeapZeroInitialized);

    ParsingPackage setRequestRawExternalStorageAccess(
            @Nullable Boolean requestRawExternalStorageAccess);

    ParsingPackage setCrossProfile(boolean crossProfile);

    ParsingPackage setFullBackupContentResourceId(int fullBackupContentRes);

    ParsingPackage setDataExtractionRulesResourceId(int dataExtractionRulesRes);

    ParsingPackage setHasDomainUrls(boolean hasDomainUrls);

    ParsingPackage setIconResourceId(int iconRes);

    ParsingPackage setInstallLocation(int installLocation);

    /** @see R#styleable.AndroidManifest_sharedUserMaxSdkVersion */
    ParsingPackage setLeavingSharedUser(boolean leavingSharedUser);

    ParsingPackage setLabelResourceId(int labelRes);

    ParsingPackage setLargestWidthLimitDp(int largestWidthLimitDp);

    ParsingPackage setLogoResourceId(int logo);

    ParsingPackage setManageSpaceActivityName(String manageSpaceActivityName);

    ParsingPackage setMinExtensionVersions(@Nullable SparseIntArray minExtensionVersions);

    ParsingPackage setMinSdkVersion(int minSdkVersion);

    ParsingPackage setMaxSdkVersion(int maxSdkVersion);

    ParsingPackage setNetworkSecurityConfigResourceId(int networkSecurityConfigRes);

    ParsingPackage setNonLocalizedLabel(CharSequence nonLocalizedLabel);

    ParsingPackage setOverlayCategory(String overlayCategory);

    ParsingPackage setOverlayIsStatic(boolean overlayIsStatic);

    ParsingPackage setOverlayPriority(int overlayPriority);

    ParsingPackage setOverlayTarget(String overlayTarget);

    ParsingPackage setOverlayTargetOverlayableName(String overlayTargetOverlayableName);

    ParsingPackage setRequiredAccountType(String requiredAccountType);

    ParsingPackage setRequiredForAllUsers(boolean requiredForAllUsers);

    ParsingPackage setRequiresSmallestWidthDp(int requiresSmallestWidthDp);

    ParsingPackage setResizeable(int resizeable);

    ParsingPackage setRestrictUpdateHash(byte[] restrictUpdateHash);

    ParsingPackage setRestrictedAccountType(String restrictedAccountType);

    ParsingPackage setRoundIconResourceId(int roundIconRes);

    ParsingPackage setSharedUserLabelResourceId(int sharedUserLabelRes);

    ParsingPackage setSigningDetails(@NonNull SigningDetails signingDetails);

    ParsingPackage setSplitClassLoaderName(int splitIndex, String classLoaderName);

    ParsingPackage setStaticSharedLibraryVersion(long staticSharedLibraryVersion);

    ParsingPackage setLargeScreensSupported(int supportsLargeScreens);

    ParsingPackage setNormalScreensSupported(int supportsNormalScreens);

    ParsingPackage setSmallScreensSupported(int supportsSmallScreens);

    ParsingPackage setExtraLargeScreensSupported(int supportsExtraLargeScreens);

    ParsingPackage setTargetSandboxVersion(int targetSandboxVersion);

    ParsingPackage setThemeResourceId(int theme);

    ParsingPackage setRequestForegroundServiceExemption(boolean requestForegroundServiceExemption);

    ParsingPackage setUpgradeKeySets(@NonNull Set<String> upgradeKeySets);

    ParsingPackage set32BitAbiPreferred(boolean use32BitAbi);

    ParsingPackage setVolumeUuid(@Nullable String volumeUuid);

    ParsingPackage setZygotePreloadName(String zygotePreloadName);

    ParsingPackage sortActivities();

    ParsingPackage sortReceivers();

    ParsingPackage sortServices();

    ParsingPackage setBaseRevisionCode(int baseRevisionCode);

    ParsingPackage setVersionCode(int vesionCode);

    ParsingPackage setVersionCodeMajor(int vesionCodeMajor);

    ParsingPackage setVersionName(String versionName);

    ParsingPackage setCompileSdkVersion(int compileSdkVersion);

    ParsingPackage setCompileSdkVersionCodeName(String compileSdkVersionCodeName);

    ParsingPackage setAttributionsAreUserVisible(boolean attributionsAreUserVisible);

    ParsingPackage setResetEnabledSettingsOnAppDataCleared(
            boolean resetEnabledSettingsOnAppDataCleared);

    ParsingPackage setLocaleConfigResourceId(int localeConfigRes);

    /**
     * Sets the trusted host certificates of apps that are allowed to embed activities of this
     * application.
     */
    ParsingPackage setKnownActivityEmbeddingCerts(Set<String> knownActivityEmbeddingCerts);

    ParsingPackage setOnBackInvokedCallbackEnabled(boolean enableOnBackInvokedCallback);

    @CallSuper
    ParsedPackage hideAsParsed();

    // The remaining methods are copied out of [AndroidPackage] so that the parsing variant does
    // not implement the final API interface and can't accidentally be used without finalizing
    // the parsing process.

    @NonNull
    List<ParsedActivity> getActivities();

    @NonNull
    List<ParsedAttribution> getAttributions();

    @NonNull
    String getBaseApkPath();

    @Nullable
    String getClassLoaderName();

    @NonNull
    List<ConfigurationInfo> getConfigPreferences();

    @NonNull
    List<ParsedInstrumentation> getInstrumentations();

    @NonNull
    Map<String, ArraySet<PublicKey>> getKeySetMapping();

    @NonNull
    List<String> getLibraryNames();

    float getMaxAspectRatio();

    int getMaxSdkVersion();

    @Nullable
    Bundle getMetaData();

    float getMinAspectRatio();

    int getMinSdkVersion();

    String getPackageName();

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
    Set<String> getRequestedPermissions();

    @Nullable
    Boolean getResizeableActivity();

    @Nullable
    String getSdkLibraryName();

    @NonNull
    List<ParsedService> getServices();

    @Nullable
    String getSharedUserId();

    @NonNull
    String[] getSplitCodePaths();

    @NonNull
    String[] getSplitNames();

    @Nullable
    String getStaticSharedLibraryName();

    int getTargetSdkVersion();

    @Nullable
    String getTaskAffinity();

    int getUiOptions();

    @NonNull
    List<String> getUsesLibraries();

    @NonNull
    List<String> getUsesNativeLibraries();

    @NonNull
    List<ParsedUsesPermission> getUsesPermissions();

    @NonNull
    List<String> getUsesSdkLibraries();

    @Nullable
    long[] getUsesSdkLibrariesVersionsMajor();

    @NonNull
    List<String> getUsesStaticLibraries();

    @Nullable
    String getZygotePreloadName();

    boolean isBackupAllowed();

    boolean isTaskReparentingAllowed();

    boolean isAnyDensity();

    boolean isHardwareAccelerated();

    boolean isSaveStateDisallowed();

    boolean isProfileable();

    boolean isProfileableByShell();

    boolean isResizeable();

    boolean isResizeableActivityViaSdkVersion();

    boolean isStaticSharedLibrary();

    boolean isExtraLargeScreensSupported();

    boolean isLargeScreensSupported();

    boolean isNormalScreensSupported();

    boolean isSmallScreensSupported();
}
