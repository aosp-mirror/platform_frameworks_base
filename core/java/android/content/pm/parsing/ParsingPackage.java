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

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureGroupInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager.Property;
import android.content.pm.PackageParser;
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
import android.util.SparseArray;
import android.util.SparseIntArray;

import java.security.PublicKey;
import java.util.Map;
import java.util.Set;

/**
 * Methods used for mutation during direct package parsing.
 *
 * @hide
 */
@SuppressWarnings("UnusedReturnValue")
public interface ParsingPackage extends ParsingPackageRead {

    ParsingPackage addActivity(ParsedActivity parsedActivity);

    ParsingPackage addAdoptPermission(String adoptPermission);

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
    ParsingPackage addProperty(Property property);

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

    ParsingPackage addUsesStaticLibrary(String libraryName);

    ParsingPackage addUsesStaticLibraryCertDigests(String[] certSha256Digests);

    ParsingPackage addUsesStaticLibraryVersion(long version);

    ParsingPackage addQueriesIntent(Intent intent);

    ParsingPackage addQueriesPackage(String packageName);

    ParsingPackage addQueriesProvider(String authority);

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

    ParsingPackage setStaticSharedLibName(String staticSharedLibName);

    ParsingPackage setTaskAffinity(String taskAffinity);

    ParsingPackage setTargetSdkVersion(int targetSdkVersion);

    ParsingPackage setUiOptions(int uiOptions);

    ParsingPackage setBaseHardwareAccelerated(boolean baseHardwareAccelerated);

    ParsingPackage setResizeableActivity(Boolean resizeable);

    ParsingPackage setResizeableActivityViaSdkVersion(boolean resizeableViaSdkVersion);

    ParsingPackage setAllowAudioPlaybackCapture(boolean allowAudioPlaybackCapture);

    ParsingPackage setAllowBackup(boolean allowBackup);

    ParsingPackage setAllowClearUserData(boolean allowClearUserData);

    ParsingPackage setAllowClearUserDataOnFailedRestore(boolean allowClearUserDataOnFailedRestore);

    ParsingPackage setAllowTaskReparenting(boolean allowTaskReparenting);

    ParsingPackage setOverlay(boolean isOverlay);

    ParsingPackage setBackupInForeground(boolean backupInForeground);

    ParsingPackage setCantSaveState(boolean cantSaveState);

    ParsingPackage setDebuggable(boolean debuggable);

    ParsingPackage setDefaultToDeviceProtectedStorage(boolean defaultToDeviceProtectedStorage);

    ParsingPackage setDirectBootAware(boolean directBootAware);

    ParsingPackage setExternalStorage(boolean externalStorage);

    ParsingPackage setExtractNativeLibs(boolean extractNativeLibs);

    ParsingPackage setFullBackupOnly(boolean fullBackupOnly);

    ParsingPackage setHasCode(boolean hasCode);

    ParsingPackage setHasFragileUserData(boolean hasFragileUserData);

    ParsingPackage setGame(boolean isGame);

    ParsingPackage setIsolatedSplitLoading(boolean isolatedSplitLoading);

    ParsingPackage setKillAfterRestore(boolean killAfterRestore);

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

    ParsingPackage setSplitHasCode(int splitIndex, boolean splitHasCode);

    ParsingPackage setStaticSharedLibrary(boolean staticSharedLibrary);

    ParsingPackage setSupportsRtl(boolean supportsRtl);

    ParsingPackage setTestOnly(boolean testOnly);

    ParsingPackage setUseEmbeddedDex(boolean useEmbeddedDex);

    ParsingPackage setUsesCleartextTraffic(boolean usesCleartextTraffic);

    ParsingPackage setUsesNonSdkApi(boolean usesNonSdkApi);

    ParsingPackage setVisibleToInstantApps(boolean visibleToInstantApps);

    ParsingPackage setVmSafeMode(boolean vmSafeMode);

    ParsingPackage removeUsesOptionalLibrary(String libraryName);

    ParsingPackage removeUsesOptionalNativeLibrary(String libraryName);

    ParsingPackage setAnyDensity(int anyDensity);

    ParsingPackage setAppComponentFactory(String appComponentFactory);

    ParsingPackage setBackupAgentName(String backupAgentName);

    ParsingPackage setBanner(int banner);

    ParsingPackage setCategory(int category);

    ParsingPackage setClassLoaderName(String classLoaderName);

    ParsingPackage setClassName(String className);

    ParsingPackage setCompatibleWidthLimitDp(int compatibleWidthLimitDp);

    ParsingPackage setDescriptionRes(int descriptionRes);

    ParsingPackage setEnabled(boolean enabled);

    ParsingPackage setGwpAsanMode(@ApplicationInfo.GwpAsanMode int gwpAsanMode);

    ParsingPackage setMemtagMode(@ApplicationInfo.MemtagMode int memtagMode);

    ParsingPackage setNativeHeapZeroInitialized(
            @ApplicationInfo.NativeHeapZeroInitialized int nativeHeapZeroInitialized);

    ParsingPackage setRequestRawExternalStorageAccess(
            @Nullable Boolean requestRawExternalStorageAccess);

    ParsingPackage setCrossProfile(boolean crossProfile);

    ParsingPackage setFullBackupContent(int fullBackupContent);

    ParsingPackage setDataExtractionRules(int dataExtractionRules);

    ParsingPackage setHasDomainUrls(boolean hasDomainUrls);

    ParsingPackage setIconRes(int iconRes);

    ParsingPackage setInstallLocation(int installLocation);

    ParsingPackage setLabelRes(int labelRes);

    ParsingPackage setLargestWidthLimitDp(int largestWidthLimitDp);

    ParsingPackage setLogo(int logo);

    ParsingPackage setManageSpaceActivityName(String manageSpaceActivityName);

    ParsingPackage setMinExtensionVersions(@Nullable SparseIntArray minExtensionVersions);

    ParsingPackage setMinSdkVersion(int minSdkVersion);

    ParsingPackage setNetworkSecurityConfigRes(int networkSecurityConfigRes);

    ParsingPackage setNonLocalizedLabel(CharSequence nonLocalizedLabel);

    ParsingPackage setOverlayCategory(String overlayCategory);

    ParsingPackage setOverlayIsStatic(boolean overlayIsStatic);

    ParsingPackage setOverlayPriority(int overlayPriority);

    ParsingPackage setOverlayTarget(String overlayTarget);

    ParsingPackage setOverlayTargetName(String overlayTargetName);

    ParsingPackage setRealPackage(String realPackage);

    ParsingPackage setRequiredAccountType(String requiredAccountType);

    ParsingPackage setRequiredForAllUsers(boolean requiredForAllUsers);

    ParsingPackage setRequiresSmallestWidthDp(int requiresSmallestWidthDp);

    ParsingPackage setResizeable(int resizeable);

    ParsingPackage setRestrictUpdateHash(byte[] restrictUpdateHash);

    ParsingPackage setRestrictedAccountType(String restrictedAccountType);

    ParsingPackage setRoundIconRes(int roundIconRes);

    ParsingPackage setSharedUserLabel(int sharedUserLabel);

    ParsingPackage setSigningDetails(PackageParser.SigningDetails signingDetails);

    ParsingPackage setSplitClassLoaderName(int splitIndex, String classLoaderName);

    ParsingPackage setStaticSharedLibVersion(long staticSharedLibVersion);

    ParsingPackage setSupportsLargeScreens(int supportsLargeScreens);

    ParsingPackage setSupportsNormalScreens(int supportsNormalScreens);

    ParsingPackage setSupportsSmallScreens(int supportsSmallScreens);

    ParsingPackage setSupportsExtraLargeScreens(int supportsExtraLargeScreens);

    ParsingPackage setTargetSandboxVersion(int targetSandboxVersion);

    ParsingPackage setTheme(int theme);

    ParsingPackage setRequestForegroundServiceExemption(boolean requestForegroundServiceExemption);

    ParsingPackage setUpgradeKeySets(@NonNull Set<String> upgradeKeySets);

    ParsingPackage setUse32BitAbi(boolean use32BitAbi);

    ParsingPackage setVolumeUuid(@Nullable String volumeUuid);

    ParsingPackage setZygotePreloadName(String zygotePreloadName);

    ParsingPackage sortActivities();

    ParsingPackage sortReceivers();

    ParsingPackage sortServices();

    ParsingPackage setBaseRevisionCode(int baseRevisionCode);

    ParsingPackage setVersionName(String versionName);

    ParsingPackage setCompileSdkVersion(int compileSdkVersion);

    ParsingPackage setCompileSdkVersionCodename(String compileSdkVersionCodename);

    ParsingPackage setAttributionsAreUserVisible(boolean attributionsAreUserVisible);

    // TODO(b/135203078): This class no longer has access to ParsedPackage, find a replacement
    //  for moving to the next step
    @CallSuper
    Object hideAsParsed();
}
