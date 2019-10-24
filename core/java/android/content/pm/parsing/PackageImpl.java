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

import static android.os.Build.VERSION_CODES.DONUT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureGroupInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.parsing.ComponentParseUtils.ParsedActivity;
import android.content.pm.parsing.ComponentParseUtils.ParsedActivityIntentInfo;
import android.content.pm.parsing.ComponentParseUtils.ParsedInstrumentation;
import android.content.pm.parsing.ComponentParseUtils.ParsedIntentInfo;
import android.content.pm.parsing.ComponentParseUtils.ParsedPermission;
import android.content.pm.parsing.ComponentParseUtils.ParsedPermissionGroup;
import android.content.pm.parsing.ComponentParseUtils.ParsedProvider;
import android.content.pm.parsing.ComponentParseUtils.ParsedService;
import android.content.res.TypedArray;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Parcel;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.server.SystemConfig;

import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The backing data for a package that was parsed from disk.
 *
 * TODO(b/135203078): Convert Lists used as sets into Sets, to better express intended use case
 * TODO(b/135203078): Field nullability annotations
 * TODO(b/135203078): Convert = 1 fields into Booleans
 * TODO(b/135203078): Make all lists nullable and Collections.unmodifiable immutable when returned.
 *   Prefer add/set methods if adding is necessary.
 * TODO(b/135203078): Consider comments to disable auto-format and single-line, single-space all the
 *   get/set methods to make this class far more compact. Maybe even separate some logic into parent
 *   classes, assuming there is no overhead.
 * TODO(b/135203078): Copy documentation from PackageParser#Package for the relevant fields included
 *   here. Should clarify and clean up any differences. Also consider renames if it helps make
 *   things clearer.
 * TODO(b/135203078): Intern all possibl e String values? Initial refactor just mirrored old
 *   behavior.
 *
 * @hide
 */
public final class PackageImpl implements ParsingPackage, ParsedPackage, AndroidPackage,
        AndroidPackageWrite {

    private static final String TAG = "PackageImpl";

    // Resource boolean are -1, so 1 means we don't know the value.
    private int supportsSmallScreens = 1;
    private int supportsNormalScreens = 1;
    private int supportsLargeScreens = 1;
    private int supportsXLargeScreens = 1;
    private int resizeable = 1;
    private int anyDensity = 1;

    private long[] lastPackageUsageTimeInMills =
            new long[PackageManager.NOTIFY_PACKAGE_USE_REASONS_COUNT];

    private int versionCode;
    private int versionCodeMajor;
    private int baseRevisionCode;
    private String versionName;

    private boolean coreApp;
    private int compileSdkVersion;
    private String compileSdkVersionCodename;

    private String packageName;
    private String realPackage;
    private String manifestPackageName;
    private String baseCodePath;

    private boolean requiredForAllUsers;
    private String restrictedAccountType;
    private String requiredAccountType;

    private boolean baseHardwareAccelerated;

    private String overlayTarget;
    private String overlayTargetName;
    private String overlayCategory;
    private int overlayPriority;
    private boolean overlayIsStatic;

    private String staticSharedLibName;
    private long staticSharedLibVersion;
    private ArrayList<String> libraryNames;
    private ArrayList<String> usesLibraries;
    private ArrayList<String> usesOptionalLibraries;

    private ArrayList<String> usesStaticLibraries;
    private long[] usesStaticLibrariesVersions;
    private String[][] usesStaticLibrariesCertDigests;

    private String sharedUserId;

    private int sharedUserLabel;
    private ArrayList<ConfigurationInfo> configPreferences;
    private ArrayList<FeatureInfo> reqFeatures;
    private ArrayList<FeatureGroupInfo> featureGroups;

    private byte[] restrictUpdateHash;

    private ArrayList<String> originalPackages;
    private ArrayList<String> adoptPermissions;

    private ArrayList<String> requestedPermissions;
    private ArrayList<String> implicitPermissions;

    private ArraySet<String> upgradeKeySets;
    private Map<String, ArraySet<PublicKey>> keySetMapping;

    private ArrayList<String> protectedBroadcasts;

    @Nullable
    private ArrayList<ComponentParseUtils.ParsedActivity> activities;

    @Nullable
    private ArrayList<ComponentParseUtils.ParsedActivity> receivers;

    @Nullable
    private ArrayList<ComponentParseUtils.ParsedService> services;

    @Nullable
    private ArrayList<ComponentParseUtils.ParsedProvider> providers;

    @Nullable
    private ArrayList<ComponentParseUtils.ParsedPermission> permissions;

    @Nullable
    private ArrayList<ComponentParseUtils.ParsedPermissionGroup> permissionGroups;

    @Nullable
    private ArrayList<ComponentParseUtils.ParsedInstrumentation> instrumentations;

    private ArrayList<ParsedActivityIntentInfo> preferredActivityFilters;

    private Bundle appMetaData;

    private String volumeUuid;
    private String applicationVolumeUuid;
    private PackageParser.SigningDetails signingDetails;

    private String codePath;

    private boolean use32BitAbi;
    private boolean visibleToInstantApps;

    private String cpuAbiOverride;

    private boolean isStub;

    // TODO(b/135203078): Remove, should be unused
    private int preferredOrder;

    private boolean forceQueryable;

    @Nullable
    private ArrayList<Intent> queriesIntents;

    @Nullable
    private ArrayList<String> queriesPackages;

    private String[] splitClassLoaderNames;
    private String[] splitCodePaths;
    private SparseArray<int[]> splitDependencies;
    private int[] splitFlags;
    private String[] splitNames;
    private int[] splitRevisionCodes;

    // TODO(b/135203078): Audit applicationInfo.something usages, which may be different from
    //  package.something usages. There were differing cases of package.field = versus
    //  package.appInfo.field =. This class assumes some obvious ones, like packageName,
    //  were collapsible, but kept the following separate.

    private String applicationInfoBaseResourcePath;
    private String applicationInfoCodePath;
    private String applicationInfoResourcePath;
    private String[] applicationInfoSplitResourcePaths;

    private String appComponentFactory;
    private String backupAgentName;
    private int banner;
    private int category;
    private String classLoaderName;
    private String className;
    private int compatibleWidthLimitDp;
    private String credentialProtectedDataDir;
    private String dataDir;
    private int descriptionRes;
    private String deviceProtectedDataDir;
    private boolean enabled;
    private int flags;
    private int fullBackupContent;
    private boolean hiddenUntilInstalled;
    private int icon;
    private int iconRes;
    private int installLocation = PackageParser.PARSE_DEFAULT_INSTALL_LOCATION;
    private int labelRes;
    private int largestWidthLimitDp;
    private int logo;
    private String manageSpaceActivityName;
    private float maxAspectRatio;
    private float minAspectRatio;
    private int minSdkVersion;
    private String name;
    private String nativeLibraryDir;
    private String nativeLibraryRootDir;
    private boolean nativeLibraryRootRequiresIsa;
    private int networkSecurityConfigRes;
    private CharSequence nonLocalizedLabel;
    private String permission;
    private String primaryCpuAbi;
    private int privateFlags;
    private String processName;
    private int requiresSmallestWidthDp;
    private int roundIconRes;
    private String secondaryCpuAbi;
    private String secondaryNativeLibraryDir;
    private String seInfo;
    private String seInfoUser;
    private int targetSandboxVersion;
    private int targetSdkVersion;
    private String taskAffinity;
    private int theme;
    private int uid = -1;
    private int uiOptions;
    private String[] usesLibraryFiles;
    private List<SharedLibraryInfo> usesLibraryInfos;
    private String zygotePreloadName;

    @VisibleForTesting
    public PackageImpl(
            String packageName,
            String baseCodePath,
            TypedArray manifestArray,
            boolean isCoreApp
    ) {
        this.packageName = TextUtils.safeIntern(packageName);
        this.manifestPackageName = this.packageName;
        this.baseCodePath = baseCodePath;

        this.versionCode = manifestArray.getInteger(R.styleable.AndroidManifest_versionCode, 0);
        this.versionCodeMajor = manifestArray.getInteger(
                R.styleable.AndroidManifest_versionCodeMajor, 0);
        this.baseRevisionCode = manifestArray.getInteger(R.styleable.AndroidManifest_revisionCode,
                0);
        setVersionName(manifestArray.getNonConfigurationString(
                R.styleable.AndroidManifest_versionName, 0));
        this.coreApp = isCoreApp;

        this.compileSdkVersion = manifestArray.getInteger(
                R.styleable.AndroidManifest_compileSdkVersion, 0);
        setCompileSdkVersionCodename(manifestArray.getNonConfigurationString(
                R.styleable.AndroidManifest_compileSdkVersionCodename, 0));
    }

    private PackageImpl(String packageName) {
        this.packageName = TextUtils.safeIntern(packageName);
        this.manifestPackageName = this.packageName;
    }

    @VisibleForTesting
    public static ParsingPackage forParsing(String packageName) {
        return new PackageImpl(packageName);
    }

    @VisibleForTesting
    public static ParsingPackage forParsing(
            String packageName,
            String baseCodePath,
            TypedArray manifestArray,
            boolean isCoreApp) {
        return new PackageImpl(packageName, baseCodePath, manifestArray, isCoreApp);
    }

    /**
     * Mock an unavailable {@link AndroidPackage} to use when removing a package from the system.
     * This can occur if the package was installed on a storage device that has since been removed.
     * Since the infrastructure uses {@link AndroidPackage}, but for this case only cares about
     * volumeUuid, just fake it rather than having separate method paths.
     */
    public static AndroidPackage buildFakeForDeletion(String packageName, String volumeUuid) {
        return new PackageImpl(packageName)
                .setVolumeUuid(volumeUuid)
                .hideAsParsed()
                .hideAsFinal();
    }

    @Override
    public ParsedPackage hideAsParsed() {
        return this;
    }

    @Override
    public AndroidPackage hideAsFinal() {
        updateFlags();
        return this;
    }

    @Override
    @Deprecated
    public AndroidPackageWrite mutate() {
        return this;
    }

    private void updateFlags() {
        if (supportsSmallScreens < 0 || (supportsSmallScreens > 0
                && targetSdkVersion
                >= Build.VERSION_CODES.DONUT)) {
            this.flags |= ApplicationInfo.FLAG_SUPPORTS_SMALL_SCREENS;
        }
        if (supportsNormalScreens != 0) {
            this.flags |= ApplicationInfo.FLAG_SUPPORTS_NORMAL_SCREENS;
        }
        if (supportsLargeScreens < 0 || (supportsLargeScreens > 0
                && targetSdkVersion
                >= Build.VERSION_CODES.DONUT)) {
            this.flags |= ApplicationInfo.FLAG_SUPPORTS_LARGE_SCREENS;
        }
        if (supportsXLargeScreens < 0 || (supportsXLargeScreens > 0
                && targetSdkVersion
                >= Build.VERSION_CODES.GINGERBREAD)) {
            this.flags |= ApplicationInfo.FLAG_SUPPORTS_XLARGE_SCREENS;
        }
        if (resizeable < 0 || (resizeable > 0
                && targetSdkVersion
                >= Build.VERSION_CODES.DONUT)) {
            this.flags |= ApplicationInfo.FLAG_RESIZEABLE_FOR_SCREENS;
        }
        if (anyDensity < 0 || (anyDensity > 0
                && targetSdkVersion
                >= Build.VERSION_CODES.DONUT)) {
            this.flags |= ApplicationInfo.FLAG_SUPPORTS_SCREEN_DENSITIES;
        }
    }

    @Override
    public boolean usesCompatibilityMode() {
        int flags = 0;

        if (supportsSmallScreens < 0 || (supportsSmallScreens > 0
                && targetSdkVersion
                >= Build.VERSION_CODES.DONUT)) {
            flags |= ApplicationInfo.FLAG_SUPPORTS_SMALL_SCREENS;
        }
        if (supportsNormalScreens != 0) {
            flags |= ApplicationInfo.FLAG_SUPPORTS_NORMAL_SCREENS;
        }
        if (supportsLargeScreens < 0 || (supportsLargeScreens > 0
                && targetSdkVersion
                >= Build.VERSION_CODES.DONUT)) {
            flags |= ApplicationInfo.FLAG_SUPPORTS_LARGE_SCREENS;
        }
        if (supportsXLargeScreens < 0 || (supportsXLargeScreens > 0
                && targetSdkVersion
                >= Build.VERSION_CODES.GINGERBREAD)) {
            flags |= ApplicationInfo.FLAG_SUPPORTS_XLARGE_SCREENS;
        }
        if (resizeable < 0 || (resizeable > 0
                && targetSdkVersion
                >= Build.VERSION_CODES.DONUT)) {
            flags |= ApplicationInfo.FLAG_RESIZEABLE_FOR_SCREENS;
        }
        if (anyDensity < 0 || (anyDensity > 0
                && targetSdkVersion
                >= Build.VERSION_CODES.DONUT)) {
            flags |= ApplicationInfo.FLAG_SUPPORTS_SCREEN_DENSITIES;
        }

        return targetSdkVersion < DONUT
                || (flags & (ApplicationInfo.FLAG_SUPPORTS_LARGE_SCREENS
                        | ApplicationInfo.FLAG_SUPPORTS_NORMAL_SCREENS
                        | ApplicationInfo.FLAG_SUPPORTS_SMALL_SCREENS
                        | ApplicationInfo.FLAG_RESIZEABLE_FOR_SCREENS
                        | ApplicationInfo.FLAG_SUPPORTS_SCREEN_DENSITIES
                        | ApplicationInfo.FLAG_SUPPORTS_XLARGE_SCREENS)) == 0;
    }

    @Override
    public String getBaseCodePath() {
        return baseCodePath;
    }

    @Override
    public int getTargetSdkVersion() {
        return targetSdkVersion;
    }

    @Override
    public String getPackageName() {
        return packageName;
    }

    @Override
    public String getProcessName() {
        return processName;
    }

    @Override
    public String getPermission() {
        return permission;
    }

    @Override
    public String getStaticSharedLibName() {
        return staticSharedLibName;
    }

    @Override
    public long getStaticSharedLibVersion() {
        return staticSharedLibVersion;
    }

    @Override
    public String getSharedUserId() {
        return sharedUserId;
    }

    @Override
    public List<String> getRequestedPermissions() {
        return requestedPermissions == null ? Collections.emptyList() : requestedPermissions;
    }

    @Nullable
    @Override
    public List<ParsedInstrumentation> getInstrumentations() {
        return instrumentations;
    }

    @Override
    public Map<String, ArraySet<PublicKey>> getKeySetMapping() {
        return keySetMapping == null ? Collections.emptyMap() : keySetMapping;
    }

    @Override
    public float getMaxAspectRatio() {
        return maxAspectRatio;
    }

    @Override
    public float getMinAspectRatio() {
        return minAspectRatio;
    }

    @NonNull
    @Override
    public List<String> getLibraryNames() {
        return libraryNames == null ? Collections.emptyList() : libraryNames;
    }

    @Override
    public List<ParsedActivity> getActivities() {
        return activities == null ? Collections.emptyList()
                : activities;
    }

    @Override
    public Bundle getAppMetaData() {
        return appMetaData;
    }

    @Nullable
    @Override
    public List<String> getUsesLibraries() {
        return usesLibraries;
    }

    @Nullable
    @Override
    public List<String> getUsesStaticLibraries() {
        return usesStaticLibraries;
    }

    @Override
    public boolean isBaseHardwareAccelerated() {
        return baseHardwareAccelerated;
    }

    @Override
    public int getUiOptions() {
        return uiOptions;
    }

    // TODO(b/135203078): Checking flags directly can be error prone,
    //  consider separate interface methods?
    @Override
    public int getFlags() {
        return flags;
    }

    // TODO(b/135203078): Checking flags directly can be error prone,
    //  consider separate interface methods?
    @Override
    public int getPrivateFlags() {
        return privateFlags;
    }

    @Override
    public String getTaskAffinity() {
        return taskAffinity;
    }

    @Nullable
    @Override
    public List<String> getOriginalPackages() {
        return originalPackages;
    }

    @Override
    public PackageParser.SigningDetails getSigningDetails() {
        return signingDetails;
    }

    @Override
    public String getVolumeUuid() {
        return volumeUuid;
    }

    @Nullable
    @Override
    public List<ParsedPermissionGroup> getPermissionGroups() {
        return permissionGroups;
    }

    @Nullable
    @Override
    public List<ParsedPermission> getPermissions() {
        return permissions;
    }

    @Override
    public String getCpuAbiOverride() {
        return cpuAbiOverride;
    }

    @Override
    public String getPrimaryCpuAbi() {
        return primaryCpuAbi;
    }

    @Override
    public String getSecondaryCpuAbi() {
        return secondaryCpuAbi;
    }

    @Override
    public boolean isUse32BitAbi() {
        return use32BitAbi;
    }

    @Override
    public boolean isForceQueryable() {
        return forceQueryable;
    }

    @Override
    public String getCodePath() {
        return codePath;
    }

    @Override
    public String getNativeLibraryDir() {
        return nativeLibraryDir;
    }

    @Override
    public String getNativeLibraryRootDir() {
        return nativeLibraryRootDir;
    }

    @Override
    public boolean isNativeLibraryRootRequiresIsa() {
        return nativeLibraryRootRequiresIsa;
    }

    // TODO(b/135203078): Does nothing, remove?
    @Override
    public int getPreferredOrder() {
        return preferredOrder;
    }

    @Override
    public long getLongVersionCode() {
        return PackageInfo.composeLongVersionCode(versionCodeMajor, versionCode);
    }

    @Override
    public PackageImpl setIsOverlay(boolean isOverlay) {
        this.privateFlags = isOverlay
                ? this.privateFlags | ApplicationInfo.PRIVATE_FLAG_IS_RESOURCE_OVERLAY
                : this.privateFlags & ~ApplicationInfo.PRIVATE_FLAG_IS_RESOURCE_OVERLAY;
        return this;
    }

    @Override
    public PackageImpl setExternalStorage(boolean externalStorage) {
        this.flags = externalStorage
                ? this.flags | ApplicationInfo.FLAG_EXTERNAL_STORAGE
                : this.flags & ~ApplicationInfo.FLAG_EXTERNAL_STORAGE;
        return this;
    }

    @Override
    public PackageImpl setIsolatedSplitLoading(boolean isolatedSplitLoading) {
        this.privateFlags = isolatedSplitLoading
                ? this.privateFlags | ApplicationInfo.PRIVATE_FLAG_ISOLATED_SPLIT_LOADING
                : this.privateFlags & ~ApplicationInfo.PRIVATE_FLAG_ISOLATED_SPLIT_LOADING;
        return this;
    }

    @Override
    public PackageImpl sortActivities() {
        Collections.sort(this.activities, (a1, a2) -> Integer.compare(a2.order, a1.order));
        return this;
    }

    @Override
    public PackageImpl sortReceivers() {
        Collections.sort(this.receivers, (a1, a2) -> Integer.compare(a2.order, a1.order));
        return this;
    }

    @Override
    public PackageImpl sortServices() {
        Collections.sort(this.services, (a1, a2) -> Integer.compare(a2.order, a1.order));
        return this;
    }

    @Override
    public PackageImpl setBaseRevisionCode(int baseRevisionCode) {
        this.baseRevisionCode = baseRevisionCode;
        return this;
    }

    @Override
    public PackageImpl setPreferredOrder(int preferredOrder) {
        this.preferredOrder = preferredOrder;
        return this;
    }

    @Override
    public PackageImpl setVersionName(String versionName) {
        this.versionName = TextUtils.safeIntern(versionName);
        return this;
    }

    @Override
    public ParsingPackage setCompileSdkVersion(int compileSdkVersion) {
        this.compileSdkVersion = compileSdkVersion;
        return this;
    }

    @Override
    public ParsingPackage setCompileSdkVersionCodename(String compileSdkVersionCodename) {
        this.compileSdkVersionCodename = TextUtils.safeIntern(compileSdkVersionCodename);
        return this;
    }

    @Override
    public PackageImpl setMaxAspectRatio(float maxAspectRatio) {
        this.maxAspectRatio = maxAspectRatio;
        return this;
    }

    @Override
    public PackageImpl setMinAspectRatio(float minAspectRatio) {
        this.minAspectRatio = minAspectRatio;
        return this;
    }

    @Override
    public PackageImpl setMinSdkVersion(int minSdkVersion) {
        this.minSdkVersion = minSdkVersion;
        return this;
    }

    @Override
    public PackageImpl setTargetSdkVersion(int targetSdkVersion) {
        this.targetSdkVersion = targetSdkVersion;
        return this;
    }

    @Override
    public PackageImpl setRealPackage(String realPackage) {
        this.realPackage = realPackage;
        return this;
    }

    @Override
    public PackageImpl addConfigPreference(ConfigurationInfo configPreference) {
        this.configPreferences = ArrayUtils.add(this.configPreferences, configPreference);
        return this;
    }

    @Override
    public PackageImpl addReqFeature(FeatureInfo reqFeature) {
        this.reqFeatures = ArrayUtils.add(this.reqFeatures, reqFeature);
        return this;
    }

    @Override
    public PackageImpl addFeatureGroup(FeatureGroupInfo featureGroup) {
        this.featureGroups = ArrayUtils.add(this.featureGroups, featureGroup);
        return this;
    }

    @Override
    public PackageImpl addProtectedBroadcast(String protectedBroadcast) {
        if (this.protectedBroadcasts == null
                || !this.protectedBroadcasts.contains(protectedBroadcast)) {
            this.protectedBroadcasts = ArrayUtils.add(this.protectedBroadcasts,
                    TextUtils.safeIntern(protectedBroadcast));
        }
        return this;
    }

    @Override
    public PackageImpl addInstrumentation(ParsedInstrumentation instrumentation) {
        this.instrumentations = ArrayUtils.add(this.instrumentations, instrumentation);
        return this;
    }

    @Override
    public PackageImpl addOriginalPackage(String originalPackage) {
        this.originalPackages = ArrayUtils.add(this.originalPackages, originalPackage);
        return this;
    }

    @Override
    public PackageImpl addAdoptPermission(String adoptPermission) {
        this.adoptPermissions = ArrayUtils.add(this.adoptPermissions, adoptPermission);
        return this;
    }

    @Override
    public PackageImpl addPermission(ParsedPermission permission) {
        this.permissions = ArrayUtils.add(this.permissions, permission);
        return this;
    }

    @Override
    public PackageImpl removePermission(int index) {
        this.permissions.remove(index);
        return this;
    }

    @Override
    public PackageImpl addPermissionGroup(ParsedPermissionGroup permissionGroup) {
        this.permissionGroups = ArrayUtils.add(this.permissionGroups, permissionGroup);
        return this;
    }

    @Override
    public PackageImpl addRequestedPermission(String permission) {
        this.requestedPermissions = ArrayUtils.add(this.requestedPermissions,
                TextUtils.safeIntern(permission));
        return this;
    }

    @Override
    public PackageImpl addImplicitPermission(String permission) {
        this.implicitPermissions = ArrayUtils.add(this.implicitPermissions,
                TextUtils.safeIntern(permission));
        return this;
    }

    @Override
    public PackageImpl addKeySet(String keySetName, PublicKey publicKey) {
        if (keySetMapping == null) {
            keySetMapping = new ArrayMap<>();
        }

        ArraySet<PublicKey> publicKeys = keySetMapping.get(keySetName);
        if (publicKeys == null) {
            publicKeys = new ArraySet<>();
            keySetMapping.put(keySetName, publicKeys);
        }

        publicKeys.add(publicKey);

        return this;
    }

    @Override
    public ParsingPackage addActivity(ParsedActivity parsedActivity) {
        this.activities = ArrayUtils.add(this.activities, parsedActivity);
        return this;
    }

    @Override
    public ParsingPackage addReceiver(ParsedActivity parsedReceiver) {
        this.receivers = ArrayUtils.add(this.receivers, parsedReceiver);
        return this;
    }

    @Override
    public ParsingPackage addService(ParsedService parsedService) {
        this.services = ArrayUtils.add(this.services, parsedService);
        return this;
    }

    @Override
    public ParsingPackage addProvider(ParsedProvider parsedProvider) {
        this.providers = ArrayUtils.add(this.providers, parsedProvider);
        return this;
    }

    @Override
    public PackageImpl addLibraryName(String libraryName) {
        this.libraryNames = ArrayUtils.add(this.libraryNames, TextUtils.safeIntern(libraryName));
        return this;
    }

    @Override
    public PackageImpl addUsesLibrary(String libraryName) {
        this.usesLibraries = ArrayUtils.add(this.usesLibraries, TextUtils.safeIntern(libraryName));
        return this;
    }

    @Override
    public PackageImpl addUsesOptionalLibrary(String libraryName) {
        this.usesOptionalLibraries = ArrayUtils.add(this.usesOptionalLibraries,
                TextUtils.safeIntern(libraryName));
        return this;
    }

    @Override
    public PackageImpl removeUsesOptionalLibrary(String libraryName) {
        this.usesOptionalLibraries = ArrayUtils.remove(this.usesOptionalLibraries, libraryName);
        return this;
    }

    @Override
    public PackageImpl addUsesStaticLibrary(String libraryName) {
        this.usesStaticLibraries = ArrayUtils.add(this.usesStaticLibraries,
                TextUtils.safeIntern(libraryName));
        return this;
    }

    @Override
    public PackageImpl addUsesStaticLibraryVersion(long version) {
        this.usesStaticLibrariesVersions = ArrayUtils.appendLong(this.usesStaticLibrariesVersions,
                version, true);
        return this;
    }

    @Override
    public PackageImpl addUsesStaticLibraryCertDigests(String[] certSha256Digests) {
        this.usesStaticLibrariesCertDigests = ArrayUtils.appendElement(String[].class,
                this.usesStaticLibrariesCertDigests, certSha256Digests, true);
        return this;
    }

    @Override
    public PackageImpl addPreferredActivityFilter(
            ParsedActivityIntentInfo parsedActivityIntentInfo) {
        this.preferredActivityFilters = ArrayUtils.add(this.preferredActivityFilters,
                parsedActivityIntentInfo);
        return this;
    }

    @Override
    public PackageImpl addQueriesIntent(Intent intent) {
        this.queriesIntents = ArrayUtils.add(this.queriesIntents, intent);
        return this;
    }

    @Override
    public PackageImpl addQueriesPackage(String packageName) {
        this.queriesPackages = ArrayUtils.add(this.queriesPackages,
                TextUtils.safeIntern(packageName));
        return this;
    }

    @Override
    public PackageImpl setSupportsSmallScreens(int supportsSmallScreens) {
        if (supportsSmallScreens == 1) {
            return this;
        }

        this.supportsSmallScreens = supportsSmallScreens;
        return this;
    }

    @Override
    public PackageImpl setSupportsNormalScreens(int supportsNormalScreens) {
        if (supportsNormalScreens == 1) {
            return this;
        }

        this.supportsNormalScreens = supportsNormalScreens;
        return this;
    }

    @Override
    public PackageImpl setSupportsLargeScreens(int supportsLargeScreens) {
        if (supportsLargeScreens == 1) {
            return this;
        }

        this.supportsLargeScreens = supportsLargeScreens;
        return this;
    }

    @Override
    public PackageImpl setSupportsXLargeScreens(int supportsXLargeScreens) {
        if (supportsXLargeScreens == 1) {
            return this;
        }

        this.supportsXLargeScreens = supportsXLargeScreens;
        return this;
    }

    @Override
    public PackageImpl setResizeable(int resizeable) {
        if (resizeable == 1) {
            return this;
        }

        this.resizeable = resizeable;
        return this;
    }

    @Override
    public PackageImpl setAnyDensity(int anyDensity) {
        if (anyDensity == 1) {
            return this;
        }

        this.anyDensity = anyDensity;
        return this;
    }

    @Override
    public PackageImpl setRequiresSmallestWidthDp(int requiresSmallestWidthDp) {
        this.requiresSmallestWidthDp = requiresSmallestWidthDp;
        return this;
    }

    @Override
    public PackageImpl setCompatibleWidthLimitDp(int compatibleWidthLimitDp) {
        this.compatibleWidthLimitDp = compatibleWidthLimitDp;
        return this;
    }

    @Override
    public PackageImpl setLargestWidthLimitDp(int largestWidthLimitDp) {
        this.largestWidthLimitDp = largestWidthLimitDp;
        return this;
    }

    @Override
    public PackageImpl setInstallLocation(int installLocation) {
        this.installLocation = installLocation;
        return this;
    }

    @Override
    public PackageImpl setTargetSandboxVersion(int targetSandboxVersion) {
        this.targetSandboxVersion = targetSandboxVersion;
        return this;
    }

    @Override
    public PackageImpl setRequiredForAllUsers(boolean requiredForAllUsers) {
        this.requiredForAllUsers = requiredForAllUsers;
        return this;
    }

    @Override
    public PackageImpl setRestrictedAccountType(String restrictedAccountType) {
        this.restrictedAccountType = restrictedAccountType;
        return this;
    }

    @Override
    public PackageImpl setRequiredAccountType(String requiredAccountType) {
        this.requiredAccountType = requiredAccountType;
        return this;
    }

    @Override
    public PackageImpl setBaseHardwareAccelerated(boolean baseHardwareAccelerated) {
        this.baseHardwareAccelerated = baseHardwareAccelerated;

        this.flags = baseHardwareAccelerated
                ? this.flags | ApplicationInfo.FLAG_HARDWARE_ACCELERATED
                : this.flags & ~ApplicationInfo.FLAG_HARDWARE_ACCELERATED;

        return this;
    }

    @Override
    public PackageImpl setHasDomainUrls(boolean hasDomainUrls) {
        this.privateFlags = hasDomainUrls
                ? this.privateFlags | ApplicationInfo.PRIVATE_FLAG_HAS_DOMAIN_URLS
                : this.privateFlags & ~ApplicationInfo.PRIVATE_FLAG_HAS_DOMAIN_URLS;
        return this;
    }

    @Override
    public PackageImpl setAppMetaData(Bundle appMetaData) {
        this.appMetaData = appMetaData;
        return this;
    }

    @Override
    public PackageImpl setOverlayTarget(String overlayTarget) {
        this.overlayTarget = overlayTarget;
        return this;
    }

    @Override
    public PackageImpl setOverlayTargetName(String overlayTargetName) {
        this.overlayTargetName = overlayTargetName;
        return this;
    }

    @Override
    public PackageImpl setOverlayCategory(String overlayCategory) {
        this.overlayCategory = overlayCategory;
        return this;
    }

    @Override
    public PackageImpl setOverlayPriority(int overlayPriority) {
        this.overlayPriority = overlayPriority;
        return this;
    }

    @Override
    public PackageImpl setOverlayIsStatic(boolean overlayIsStatic) {
        this.overlayIsStatic = overlayIsStatic;
        return this;
    }

    @Override
    public PackageImpl setStaticSharedLibName(String staticSharedLibName) {
        this.staticSharedLibName = TextUtils.safeIntern(staticSharedLibName);
        return this;
    }

    @Override
    public PackageImpl setStaticSharedLibVersion(long staticSharedLibVersion) {
        this.staticSharedLibVersion = staticSharedLibVersion;
        return this;
    }

    @Override
    public PackageImpl setSharedUserId(String sharedUserId) {
        this.sharedUserId = TextUtils.safeIntern(sharedUserId);
        return this;
    }

    @Override
    public PackageImpl setSharedUserLabel(int sharedUserLabel) {
        this.sharedUserLabel = sharedUserLabel;
        return this;
    }

    @Override
    public PackageImpl setRestrictUpdateHash(byte[] restrictUpdateHash) {
        this.restrictUpdateHash = restrictUpdateHash;
        return this;
    }

    @Override
    public PackageImpl setUpgradeKeySets(ArraySet<String> upgradeKeySets) {
        this.upgradeKeySets = upgradeKeySets;
        return this;
    }

    @Override
    public PackageImpl setVolumeUuid(String volumeUuid) {
        this.volumeUuid = volumeUuid;
        return this;
    }

    @Deprecated
    @Override
    public PackageImpl setApplicationVolumeUuid(String applicationVolumeUuid) {
        this.applicationVolumeUuid = applicationVolumeUuid;
        return this;
    }

    @Override
    public PackageImpl setSigningDetails(PackageParser.SigningDetails signingDetails) {
        this.signingDetails = signingDetails;
        return this;
    }

    @Override
    public PackageImpl setCodePath(String codePath) {
        this.codePath = codePath;
        return this;
    }

    @Override
    public PackageImpl setUse32BitAbi(boolean use32BitAbi) {
        this.use32BitAbi = use32BitAbi;
        return this;
    }

    @Override
    public PackageImpl setCpuAbiOverride(String cpuAbiOverride) {
        this.cpuAbiOverride = cpuAbiOverride;
        return this;
    }

    @Override
    public PackageImpl setForceQueryable(boolean forceQueryable) {
        this.forceQueryable = forceQueryable;
        return this;
    }

    // TODO(b/135203078): Remove and move PackageManagerService#renameStaticSharedLibraryPackage
    //  into initial package parsing
    @Override
    public PackageImpl setPackageName(String packageName) {
        this.packageName = packageName.intern();

        if (permissions != null) {
            for (ParsedPermission permission : permissions) {
                permission.setPackageName(this.packageName);
            }
        }

        if (permissionGroups != null) {
            for (ParsedPermissionGroup permissionGroup : permissionGroups) {
                permissionGroup.setPackageName(this.packageName);
            }
        }

        if (activities != null) {
            for (ParsedActivity parsedActivity : activities) {
                parsedActivity.setPackageName(this.packageName);
            }
        }

        if (receivers != null) {
            for (ParsedActivity receiver : receivers) {
                receiver.setPackageName(this.packageName);
            }
        }

        if (providers != null) {
            for (ParsedProvider provider : providers) {
                provider.setPackageName(this.packageName);
            }
        }

        if (services != null) {
            for (ParsedService service : services) {
                service.setPackageName(this.packageName);
            }
        }

        if (instrumentations != null) {
            for (ParsedInstrumentation instrumentation : instrumentations) {
                instrumentation.setPackageName(this.packageName);
            }
        }

        return this;
    }

    // Under this is parseBaseApplication

    @Override
    public PackageImpl setAllowBackup(boolean allowBackup) {
        this.flags = allowBackup
                ? this.flags | ApplicationInfo.FLAG_ALLOW_BACKUP
                : this.flags & ~ApplicationInfo.FLAG_ALLOW_BACKUP;
        return this;
    }

    @Override
    public PackageImpl setKillAfterRestore(boolean killAfterRestore) {
        this.flags = killAfterRestore
                ? this.flags | ApplicationInfo.FLAG_KILL_AFTER_RESTORE
                : this.flags & ~ApplicationInfo.FLAG_KILL_AFTER_RESTORE;
        return this;
    }

    @Override
    public PackageImpl setRestoreAnyVersion(boolean restoreAnyVersion) {
        this.flags = restoreAnyVersion
                ? this.flags | ApplicationInfo.FLAG_RESTORE_ANY_VERSION
                : this.flags & ~ApplicationInfo.FLAG_RESTORE_ANY_VERSION;
        return this;
    }

    @Override
    public PackageImpl setFullBackupOnly(boolean fullBackupOnly) {
        this.flags = fullBackupOnly
                ? this.flags | ApplicationInfo.FLAG_FULL_BACKUP_ONLY
                : this.flags & ~ApplicationInfo.FLAG_FULL_BACKUP_ONLY;
        return this;
    }

    @Override
    public PackageImpl setPersistent(boolean persistent) {
        this.flags = persistent
                ? this.flags | ApplicationInfo.FLAG_PERSISTENT
                : this.flags & ~ApplicationInfo.FLAG_PERSISTENT;
        return this;
    }

    @Override
    public PackageImpl setDebuggable(boolean debuggable) {
        this.flags = debuggable
                ? this.flags | ApplicationInfo.FLAG_DEBUGGABLE
                : this.flags & ~ApplicationInfo.FLAG_DEBUGGABLE;
        return this;
    }

    @Override
    public PackageImpl setProfileableByShell(boolean profileableByShell) {
        this.privateFlags = profileableByShell
                ? this.privateFlags | ApplicationInfo.PRIVATE_FLAG_PROFILEABLE_BY_SHELL
                : this.privateFlags & ~ApplicationInfo.PRIVATE_FLAG_PROFILEABLE_BY_SHELL;
        return this;
    }

    @Override
    public PackageImpl setVmSafeMode(boolean vmSafeMode) {
        this.flags = vmSafeMode
                ? this.flags | ApplicationInfo.FLAG_VM_SAFE_MODE
                : this.flags & ~ApplicationInfo.FLAG_VM_SAFE_MODE;
        return this;
    }

    @Override
    public PackageImpl setHasCode(boolean hasCode) {
        this.flags = hasCode
                ? this.flags | ApplicationInfo.FLAG_HAS_CODE
                : this.flags & ~ApplicationInfo.FLAG_HAS_CODE;
        return this;
    }

    @Override
    public PackageImpl setAllowTaskReparenting(boolean allowTaskReparenting) {
        this.flags = allowTaskReparenting
                ? this.flags | ApplicationInfo.FLAG_ALLOW_TASK_REPARENTING
                : this.flags & ~ApplicationInfo.FLAG_ALLOW_TASK_REPARENTING;
        return this;
    }

    @Override
    public PackageImpl setAllowClearUserData(boolean allowClearUserData) {
        this.flags = allowClearUserData
                ? this.flags | ApplicationInfo.FLAG_ALLOW_CLEAR_USER_DATA
                : this.flags & ~ApplicationInfo.FLAG_ALLOW_CLEAR_USER_DATA;
        return this;
    }

    @Override
    public PackageImpl setLargeHeap(boolean largeHeap) {
        this.flags = largeHeap
                ? this.flags | ApplicationInfo.FLAG_LARGE_HEAP
                : this.flags & ~ApplicationInfo.FLAG_LARGE_HEAP;
        return this;
    }

    @Override
    public PackageImpl setUsesCleartextTraffic(boolean usesCleartextTraffic) {
        this.flags = usesCleartextTraffic
                ? this.flags | ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC
                : this.flags & ~ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC;
        return this;
    }

    @Override
    public PackageImpl setSupportsRtl(boolean supportsRtl) {
        this.flags = supportsRtl
                ? this.flags | ApplicationInfo.FLAG_SUPPORTS_RTL
                : this.flags & ~ApplicationInfo.FLAG_SUPPORTS_RTL;
        return this;
    }

    @Override
    public PackageImpl setTestOnly(boolean testOnly) {
        this.flags = testOnly
                ? this.flags | ApplicationInfo.FLAG_TEST_ONLY
                : this.flags & ~ApplicationInfo.FLAG_TEST_ONLY;
        return this;
    }

    @Override
    public PackageImpl setMultiArch(boolean multiArch) {
        this.flags = multiArch
                ? this.flags | ApplicationInfo.FLAG_MULTIARCH
                : this.flags & ~ApplicationInfo.FLAG_MULTIARCH;
        return this;
    }

    @Override
    public PackageImpl setExtractNativeLibs(boolean extractNativeLibs) {
        this.flags = extractNativeLibs
                ? this.flags | ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS
                : this.flags & ~ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS;
        return this;
    }

    @Override
    public PackageImpl setIsGame(boolean isGame) {
        this.flags = isGame
                ? this.flags | ApplicationInfo.FLAG_IS_GAME
                : this.flags & ~ApplicationInfo.FLAG_IS_GAME;
        return this;
    }

    @Override
    public PackageImpl setBackupInForeground(boolean backupInForeground) {
        this.privateFlags = backupInForeground
                ? this.privateFlags | ApplicationInfo.PRIVATE_FLAG_BACKUP_IN_FOREGROUND
                : this.privateFlags & ~ApplicationInfo.PRIVATE_FLAG_BACKUP_IN_FOREGROUND;
        return this;
    }

    @Override
    public PackageImpl setUseEmbeddedDex(boolean useEmbeddedDex) {
        this.privateFlags = useEmbeddedDex
                ? this.privateFlags | ApplicationInfo.PRIVATE_FLAG_USE_EMBEDDED_DEX
                : this.privateFlags & ~ApplicationInfo.PRIVATE_FLAG_USE_EMBEDDED_DEX;
        return this;
    }

    @Override
    public PackageImpl setDefaultToDeviceProtectedStorage(boolean defaultToDeviceProtectedStorage) {
        this.privateFlags = defaultToDeviceProtectedStorage
                ? this.privateFlags | ApplicationInfo
                        .PRIVATE_FLAG_DEFAULT_TO_DEVICE_PROTECTED_STORAGE
                : this.privateFlags & ~ApplicationInfo
                        .PRIVATE_FLAG_DEFAULT_TO_DEVICE_PROTECTED_STORAGE;
        return this;
    }

    @Override
    public PackageImpl setDirectBootAware(boolean directBootAware) {
        this.privateFlags = directBootAware
                ? this.privateFlags | ApplicationInfo.PRIVATE_FLAG_DIRECT_BOOT_AWARE
                : this.privateFlags & ~ApplicationInfo.PRIVATE_FLAG_DIRECT_BOOT_AWARE;
        return this;
    }

    @Override
    public PackageImpl setPartiallyDirectBootAware(boolean partiallyDirectBootAware) {
        this.privateFlags = partiallyDirectBootAware
                ? this.privateFlags | ApplicationInfo.PRIVATE_FLAG_PARTIALLY_DIRECT_BOOT_AWARE
                : this.privateFlags & ~ApplicationInfo.PRIVATE_FLAG_PARTIALLY_DIRECT_BOOT_AWARE;
        return this;
    }

    @Override
    public PackageImpl setActivitiesResizeModeResizeableViaSdkVersion(
            boolean resizeableViaSdkVersion
    ) {
        this.privateFlags = resizeableViaSdkVersion
                ? this.privateFlags | ApplicationInfo
                        .PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION
                : this.privateFlags & ~ApplicationInfo
                        .PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION;
        return this;
    }

    @Override
    public PackageImpl setActivitiesResizeModeResizeable(boolean resizeable) {
        this.privateFlags = resizeable
                ? this.privateFlags | ApplicationInfo
                        .PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE
                : this.privateFlags & ~ApplicationInfo
                        .PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE;

        this.privateFlags = !resizeable
                ? this.privateFlags | ApplicationInfo
                        .PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_UNRESIZEABLE
                : this.privateFlags & ~ApplicationInfo
                        .PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_UNRESIZEABLE;
        return this;
    }

    @Override
    public PackageImpl setAllowClearUserDataOnFailedRestore(
            boolean allowClearUserDataOnFailedRestore
    ) {
        this.privateFlags = allowClearUserDataOnFailedRestore
                ? this.privateFlags | ApplicationInfo
                        .PRIVATE_FLAG_ALLOW_CLEAR_USER_DATA_ON_FAILED_RESTORE
                : this.privateFlags & ~ApplicationInfo
                        .PRIVATE_FLAG_ALLOW_CLEAR_USER_DATA_ON_FAILED_RESTORE;
        return this;
    }

    @Override
    public PackageImpl setAllowAudioPlaybackCapture(boolean allowAudioPlaybackCapture) {
        this.privateFlags = allowAudioPlaybackCapture
                ? this.privateFlags | ApplicationInfo.PRIVATE_FLAG_ALLOW_AUDIO_PLAYBACK_CAPTURE
                : this.privateFlags & ~ApplicationInfo.PRIVATE_FLAG_ALLOW_AUDIO_PLAYBACK_CAPTURE;
        return this;
    }

    @Override
    public PackageImpl setRequestLegacyExternalStorage(boolean requestLegacyExternalStorage) {
        this.privateFlags = requestLegacyExternalStorage
                ? this.privateFlags | ApplicationInfo.PRIVATE_FLAG_REQUEST_LEGACY_EXTERNAL_STORAGE
                : this.privateFlags & ~ApplicationInfo.PRIVATE_FLAG_REQUEST_LEGACY_EXTERNAL_STORAGE;
        return this;
    }

    @Override
    public PackageImpl setUsesNonSdkApi(boolean usesNonSdkApi) {
        this.privateFlags = usesNonSdkApi
                ? this.privateFlags | ApplicationInfo.PRIVATE_FLAG_USES_NON_SDK_API
                : this.privateFlags & ~ApplicationInfo.PRIVATE_FLAG_USES_NON_SDK_API;
        return this;
    }

    @Override
    public PackageImpl setHasFragileUserData(boolean hasFragileUserData) {
        this.privateFlags = hasFragileUserData
                ? this.privateFlags | ApplicationInfo.PRIVATE_FLAG_HAS_FRAGILE_USER_DATA
                : this.privateFlags & ~ApplicationInfo.PRIVATE_FLAG_HAS_FRAGILE_USER_DATA;
        return this;
    }

    @Override
    public PackageImpl setCantSaveState(boolean cantSaveState) {
        this.privateFlags = cantSaveState
                ? this.privateFlags | ApplicationInfo.PRIVATE_FLAG_CANT_SAVE_STATE
                : this.privateFlags & ~ApplicationInfo.PRIVATE_FLAG_CANT_SAVE_STATE;
        return this;
    }

    @Override
    public boolean cantSaveState() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_CANT_SAVE_STATE) != 0;
    }

    @Override
    public boolean isLibrary() {
        return staticSharedLibName != null || !ArrayUtils.isEmpty(libraryNames);
    }

    // TODO(b/135203078): This does nothing until the final stage without applyPolicy being
    //  part of PackageParser
    @Override
    public boolean isSystemApp() {
        return (flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    // TODO(b/135203078): This does nothing until the final stage without applyPolicy being
    //  part of PackageParser
    @Override
    public boolean isSystemExt() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_SYSTEM_EXT) != 0;
    }

    // TODO(b/135203078): This does nothing until the final stage without applyPolicy being
    //  part of PackageParser
    @Override
    public boolean isUpdatedSystemApp() {
        return (flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
    }

    @Override
    public PackageImpl setStaticSharedLibrary(boolean staticSharedLibrary) {
        this.privateFlags = staticSharedLibrary
                ? this.privateFlags | ApplicationInfo.PRIVATE_FLAG_STATIC_SHARED_LIBRARY
                : this.privateFlags & ~ApplicationInfo.PRIVATE_FLAG_STATIC_SHARED_LIBRARY;
        return this;
    }

    @Override
    public boolean isStaticSharedLibrary() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_STATIC_SHARED_LIBRARY) != 0;
    }

    @Override
    public PackageImpl setVisibleToInstantApps(boolean visibleToInstantApps) {
        this.visibleToInstantApps = visibleToInstantApps;
        return this;
    }

    @Override
    public PackageImpl setIconRes(int iconRes) {
        this.iconRes = iconRes;
        return this;
    }

    @Override
    public PackageImpl setRoundIconRes(int roundIconRes) {
        this.roundIconRes = roundIconRes;
        return this;
    }

    @Override
    public PackageImpl setClassName(String className) {
        this.className = className;
        return this;
    }

    @Override
    public PackageImpl setManageSpaceActivityName(String manageSpaceActivityName) {
        this.manageSpaceActivityName = manageSpaceActivityName;
        return this;
    }

    @Override
    public PackageImpl setBackupAgentName(String backupAgentName) {
        this.backupAgentName = backupAgentName;
        return this;
    }

    @Override
    public PackageImpl setFullBackupContent(int fullBackupContent) {
        this.fullBackupContent = fullBackupContent;
        return this;
    }

    @Override
    public PackageImpl setTheme(int theme) {
        this.theme = theme;
        return this;
    }

    @Override
    public PackageImpl setDescriptionRes(int descriptionRes) {
        this.descriptionRes = descriptionRes;
        return this;
    }

    @Override
    public PackageImpl setNetworkSecurityConfigRes(int networkSecurityConfigRes) {
        this.networkSecurityConfigRes = networkSecurityConfigRes;
        return this;
    }

    @Override
    public PackageImpl setCategory(int category) {
        this.category = category;
        return this;
    }

    @Override
    public PackageImpl setPermission(String permission) {
        this.permission = permission;
        return this;
    }

    @Override
    public PackageImpl setTaskAffinity(String taskAffinity) {
        this.taskAffinity = taskAffinity;
        return this;
    }

    @Override
    public PackageImpl setAppComponentFactory(String appComponentFactory) {
        this.appComponentFactory = appComponentFactory;
        return this;
    }

    @Override
    public PackageImpl setProcessName(String processName) {
        if (processName == null) {
            this.processName = packageName;
        } else {
            this.processName = processName;
        }
        return this;
    }

    @Override
    public PackageImpl setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    @Override
    public PackageImpl setUiOptions(int uiOptions) {
        this.uiOptions = uiOptions;
        return this;
    }

    @Override
    public PackageImpl setClassLoaderName(String classLoaderName) {
        this.classLoaderName = classLoaderName;
        return this;
    }

    @Override
    public PackageImpl setZygotePreloadName(String zygotePreloadName) {
        this.zygotePreloadName = zygotePreloadName;
        return this;
    }

    // parsePackageItemInfo

    @Override
    public String getName() {
        return name;
    }

    @Override
    public PackageImpl setName(String name) {
        this.name = name;
        return this;
    }

    @Override
    public PackageImpl setIcon(int icon) {
        this.icon = icon;
        return this;
    }

    @Override
    public PackageImpl setNonLocalizedLabel(CharSequence nonLocalizedLabel) {
        this.nonLocalizedLabel = nonLocalizedLabel;
        return this;
    }

    @Override
    public PackageImpl setLogo(int logo) {
        this.logo = logo;
        return this;
    }

    @Override
    public PackageImpl setBanner(int banner) {
        this.banner = banner;
        return this;
    }

    @Override
    public PackageImpl setLabelRes(int labelRes) {
        this.labelRes = labelRes;
        return this;
    }

    @Override
    public PackageImpl asSplit(
            String[] splitNames,
            String[] splitCodePaths,
            int[] splitRevisionCodes,
            SparseArray<int[]> splitDependencies
    ) {
        this.splitNames = splitNames;

        if (this.splitNames != null) {
            for (int index = 0; index < this.splitNames.length; index++) {
                splitNames[index] = TextUtils.safeIntern(splitNames[index]);
            }
        }

        this.splitCodePaths = splitCodePaths;
        this.splitRevisionCodes = splitRevisionCodes;
        this.splitDependencies = splitDependencies;

        int count = splitNames.length;
        this.splitFlags = new int[count];
        this.splitClassLoaderNames = new String[count];
        return this;
    }

    @Override
    public String[] getSplitNames() {
        return splitNames;
    }

    @Override
    public String[] getSplitCodePaths() {
        return splitCodePaths;
    }

    @Override
    public PackageImpl setSplitHasCode(int splitIndex, boolean splitHasCode) {
        this.splitFlags[splitIndex] = splitHasCode
                ? this.splitFlags[splitIndex] | ApplicationInfo.FLAG_HAS_CODE
                : this.splitFlags[splitIndex] & ~ApplicationInfo.FLAG_HAS_CODE;
        return this;
    }

    @Override
    public PackageImpl setSplitClassLoaderName(int splitIndex, String classLoaderName) {
        this.splitClassLoaderNames[splitIndex] = classLoaderName;
        return this;
    }

    @Override
    public List<String> makeListAllCodePaths() {
        ArrayList<String> paths = new ArrayList<>();
        paths.add(baseCodePath);

        if (!ArrayUtils.isEmpty(splitCodePaths)) {
            Collections.addAll(paths, splitCodePaths);
        }
        return paths;
    }

    @Override
    public PackageImpl setBaseCodePath(String baseCodePath) {
        this.baseCodePath = baseCodePath;
        return this;
    }

    @Override
    public PackageImpl setSplitCodePaths(String[] splitCodePaths) {
        this.splitCodePaths = splitCodePaths;
        return this;
    }

    @Override
    public String toString() {
        return "Package{"
                + Integer.toHexString(System.identityHashCode(this))
                + " " + packageName + "}";
    }

    @Override
    public PackageImpl setPrimaryCpuAbi(String primaryCpuAbi) {
        this.primaryCpuAbi = primaryCpuAbi;
        return this;
    }

    @Override
    public PackageImpl setSecondaryCpuAbi(String secondaryCpuAbi) {
        this.secondaryCpuAbi = secondaryCpuAbi;
        return this;
    }

    @Override
    public PackageImpl setNativeLibraryRootDir(String nativeLibraryRootDir) {
        this.nativeLibraryRootDir = nativeLibraryRootDir;
        return this;
    }

    @Override
    public PackageImpl setNativeLibraryRootRequiresIsa(boolean nativeLibraryRootRequiresIsa) {
        this.nativeLibraryRootRequiresIsa = nativeLibraryRootRequiresIsa;
        return this;
    }

    @Override
    public PackageImpl setNativeLibraryDir(String nativeLibraryDir) {
        this.nativeLibraryDir = nativeLibraryDir;
        return this;
    }

    @Override
    public PackageImpl setSecondaryNativeLibraryDir(String secondaryNativeLibraryDir) {
        this.secondaryNativeLibraryDir = secondaryNativeLibraryDir;
        return this;
    }

    @Deprecated
    @Override
    public PackageImpl setApplicationInfoCodePath(String applicationInfoCodePath) {
        this.applicationInfoCodePath = applicationInfoCodePath;
        return this;
    }

    @Deprecated
    @Override
    public PackageImpl setApplicationInfoResourcePath(String applicationInfoResourcePath) {
        this.applicationInfoResourcePath = applicationInfoResourcePath;
        return this;
    }

    @Deprecated
    @Override
    public PackageImpl setApplicationInfoBaseResourcePath(
            String applicationInfoBaseResourcePath) {
        this.applicationInfoBaseResourcePath = applicationInfoBaseResourcePath;
        return this;
    }

    @Deprecated
    @Override
    public PackageImpl setApplicationInfoSplitResourcePaths(
            String[] applicationInfoSplitResourcePaths) {
        this.applicationInfoSplitResourcePaths = applicationInfoSplitResourcePaths;
        return this;
    }

    @Override
    public boolean isDirectBootAware() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_DIRECT_BOOT_AWARE) != 0;
    }

    @Override
    public PackageImpl setAllComponentsDirectBootAware(boolean allComponentsDirectBootAware) {
        if (activities != null) {
            for (ParsedActivity parsedActivity : activities) {
                parsedActivity.directBootAware = allComponentsDirectBootAware;
            }
        }

        if (receivers != null) {
            for (ParsedActivity parsedReceiver : receivers) {
                parsedReceiver.directBootAware = allComponentsDirectBootAware;
            }
        }

        if (providers != null) {
            for (ParsedProvider parsedProvider : providers) {
                parsedProvider.directBootAware = allComponentsDirectBootAware;
            }
        }

        if (services != null) {
            for (ParsedService parsedService : services) {
                parsedService.directBootAware = allComponentsDirectBootAware;
            }
        }

        return this;
    }

    @Override
    public PackageImpl setSystem(boolean system) {
        this.flags = system
                ? this.flags | ApplicationInfo.FLAG_SYSTEM
                : this.flags & ~ApplicationInfo.FLAG_SYSTEM;
        return this;
    }

    @Override
    public PackageImpl setSystemExt(boolean systemExt) {
        this.privateFlags = systemExt
                ? this.privateFlags | ApplicationInfo.PRIVATE_FLAG_SYSTEM_EXT
                : this.privateFlags & ~ApplicationInfo.PRIVATE_FLAG_SYSTEM_EXT;
        return this;
    }

    @Override
    public PackageImpl setIsStub(boolean isStub) {
        this.isStub = isStub;
        return this;
    }

    @Override
    public PackageImpl setCoreApp(boolean coreApp) {
        this.coreApp = coreApp;
        return this;
    }

    @Override
    public ParsedPackage capPermissionPriorities() {
        if (permissionGroups != null && !permissionGroups.isEmpty()) {
            for (int i = permissionGroups.size() - 1; i >= 0; --i) {
                // TODO(b/135203078): Builder/immutability
                permissionGroups.get(i).priority = 0;
            }
        }
        return this;
    }

    @Override
    public ParsedPackage clearProtectedBroadcasts() {
        if (protectedBroadcasts != null) {
            protectedBroadcasts.clear();
        }
        return this;
    }

    @Override
    public ParsedPackage markNotActivitiesAsNotExportedIfSingleUser() {
        // ignore export request for single user receivers
        if (receivers != null) {
            for (ParsedActivity receiver : receivers) {
                if ((receiver.flags & ActivityInfo.FLAG_SINGLE_USER) != 0) {
                    receiver.exported = false;
                }
            }
        }
        // ignore export request for single user services
        if (services != null) {
            for (ParsedService service : services) {
                if ((service.flags & ServiceInfo.FLAG_SINGLE_USER) != 0) {
                    service.exported = false;
                }
            }
        }
        // ignore export request for single user providers
        if (providers != null) {
            for (ParsedProvider provider : providers) {
                if ((provider.flags & ProviderInfo.FLAG_SINGLE_USER) != 0) {
                    provider.exported = false;
                }
            }
        }

        return this;
    }

    @Override
    public ParsedPackage setPrivileged(boolean privileged) {
        this.privateFlags = privileged
                ? this.privateFlags | ApplicationInfo.PRIVATE_FLAG_PRIVILEGED
                : this.privateFlags & ~ApplicationInfo.PRIVATE_FLAG_PRIVILEGED;
        return this;
    }

    @Override
    public ParsedPackage setOem(boolean oem) {
        this.privateFlags = oem
                ? this.privateFlags | ApplicationInfo.PRIVATE_FLAG_OEM
                : this.privateFlags & ~ApplicationInfo.PRIVATE_FLAG_OEM;
        return this;
    }

    @Override
    public ParsedPackage setVendor(boolean vendor) {
        this.privateFlags = vendor
                ? this.privateFlags | ApplicationInfo.PRIVATE_FLAG_VENDOR
                : this.privateFlags & ~ApplicationInfo.PRIVATE_FLAG_VENDOR;
        return this;
    }

    @Override
    public ParsedPackage setProduct(boolean product) {
        this.privateFlags = product
                ? this.privateFlags | ApplicationInfo.PRIVATE_FLAG_PRODUCT
                : this.privateFlags & ~ApplicationInfo.PRIVATE_FLAG_PRODUCT;
        return this;
    }

    @Override
    public ParsedPackage setOdm(boolean odm) {
        this.privateFlags = odm
                ? this.privateFlags | ApplicationInfo.PRIVATE_FLAG_ODM
                : this.privateFlags & ~ApplicationInfo.PRIVATE_FLAG_ODM;
        return this;
    }

    @Override
    public ParsedPackage setSignedWithPlatformKey(boolean signedWithPlatformKey) {
        this.privateFlags = signedWithPlatformKey
                ? this.privateFlags | ApplicationInfo.PRIVATE_FLAG_SIGNED_WITH_PLATFORM_KEY
                : this.privateFlags & ~ApplicationInfo.PRIVATE_FLAG_SIGNED_WITH_PLATFORM_KEY;
        return this;
    }

    @Override
    public ParsedPackage clearOriginalPackages() {
        if (originalPackages != null) {
            originalPackages.clear();
        }
        return this;
    }

    @Override
    public ParsedPackage clearAdoptPermissions() {
        if (adoptPermissions != null) {
            adoptPermissions.clear();
        }
        return this;
    }

    @Override
    public PackageImpl addUsesLibrary(int index, String libraryName) {
        this.usesLibraries = ArrayUtils.add(usesLibraries, index, libraryName);
        return this;
    }

    @Override
    public ParsedPackage removeUsesLibrary(String libraryName) {
        this.usesLibraries = ArrayUtils.remove(this.usesLibraries, libraryName);
        return this;
    }

    @Override
    public PackageImpl addUsesOptionalLibrary(int index, String libraryName) {
        this.usesOptionalLibraries = ArrayUtils.add(usesOptionalLibraries, index, libraryName);
        return this;
    }

    @Nullable
    @Override
    public List<String> getUsesOptionalLibraries() {
        return usesOptionalLibraries;
    }

    @Override
    public int getVersionCode() {
        return versionCode;
    }

    @Nullable
    @Override
    public long[] getUsesStaticLibrariesVersions() {
        return usesStaticLibrariesVersions;
    }

    @Override
    public PackageImpl setPackageSettingCallback(PackageSettingCallback packageSettingCallback) {
        packageSettingCallback.setAndroidPackage(this);
        return this;
    }

    @Override
    public PackageImpl setUpdatedSystemApp(boolean updatedSystemApp) {
        this.flags = updatedSystemApp
                ? this.flags | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                : this.flags & ~ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
        return this;
    }

    @Override
    public boolean isPrivileged() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED) != 0;
    }

    @Override
    public PackageImpl setSeInfo(String seInfo) {
        this.seInfo = seInfo;
        return this;
    }

    @Override
    public PackageImpl setSeInfoUser(String seInfoUser) {
        this.seInfoUser = seInfoUser;
        return this;
    }

    @Override
    public PackageImpl initForUser(int userId) {
        // TODO(b/135203078): Move this user state to some other data structure
        this.uid = UserHandle.getUid(userId, UserHandle.getAppId(this.uid));

        if ("android".equals(packageName)) {
            dataDir = Environment.getDataSystemDirectory().getAbsolutePath();
            return this;
        }

        deviceProtectedDataDir = Environment
                .getDataUserDePackageDirectory(applicationVolumeUuid, userId, packageName)
                .getAbsolutePath();
        credentialProtectedDataDir = Environment
                .getDataUserCePackageDirectory(applicationVolumeUuid, userId, packageName)
                .getAbsolutePath();

        if ((privateFlags & ApplicationInfo.PRIVATE_FLAG_DEFAULT_TO_DEVICE_PROTECTED_STORAGE) != 0
                && PackageManager.APPLY_DEFAULT_TO_DEVICE_PROTECTED_STORAGE) {
            dataDir = deviceProtectedDataDir;
        } else {
            dataDir = credentialProtectedDataDir;
        }
        return this;
    }

    @Override
    public ParsedPackage setFactoryTest(boolean factoryTest) {
        this.flags = factoryTest
                ? this.flags | ApplicationInfo.FLAG_FACTORY_TEST
                : this.flags & ~ApplicationInfo.FLAG_FACTORY_TEST;
        return this;
    }

    @Override
    public String getManifestPackageName() {
        return manifestPackageName;
    }

    @Override
    public String getRealPackage() {
        return realPackage;
    }

    @Override
    public String getOverlayTarget() {
        return overlayTarget;
    }

    @Override
    public String getOverlayTargetName() {
        return overlayTargetName;
    }

    @Override
    public boolean isOverlayIsStatic() {
        return overlayIsStatic;
    }

    @Override
    public int[] getSplitFlags() {
        return splitFlags;
    }

    @Deprecated
    @Override
    public String getApplicationInfoVolumeUuid() {
        return applicationVolumeUuid;
    }

    @Nullable
    @Override
    public List<String> getProtectedBroadcasts() {
        return protectedBroadcasts;
    }

    @Nullable
    @Override
    public Set<String> getUpgradeKeySets() {
        return upgradeKeySets;
    }

    @Nullable
    @Override
    public String[][] getUsesStaticLibrariesCertDigests() {
        return usesStaticLibrariesCertDigests;
    }

    @Override
    public int getOverlayPriority() {
        return overlayPriority;
    }

    @Deprecated
    @Override
    public String getAppInfoPackageName() {
        return packageName;
    }

    @Override
    public UUID getStorageUuid() {
        return StorageManager.convert(applicationVolumeUuid);
    }

    @Override
    public int getUid() {
        return uid;
    }

    @Override
    public boolean isStub() {
        return isStub;
    }

    @Deprecated
    @Override
    public String getAppInfoCodePath() {
        return applicationInfoCodePath;
    }

    @Override
    public boolean isSystem() {
        return (flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    @Override
    public boolean isMatch(int flags) {
        if ((flags & PackageManager.MATCH_SYSTEM_ONLY) != 0) {
            return isSystem();
        }
        return true;
    }

    @Override
    public boolean isVisibleToInstantApps() {
        return visibleToInstantApps;
    }

    @Override
    public PackageImpl setLastPackageUsageTimeInMills(int reason, long time) {
        lastPackageUsageTimeInMills[reason] = time;
        return this;
    }

    @Override
    public List<SharedLibraryInfo> getUsesLibraryInfos() {
        return usesLibraryInfos;
    }

    @NonNull
    @Override
    public List<String> getAllCodePaths() {
        return makeListAllCodePaths();
    }

    @Nullable
    @Override
    public String[] getUsesLibraryFiles() {
        return usesLibraryFiles;
    }

    @Override
    public PackageImpl setUsesLibraryInfos(
            @Nullable List<SharedLibraryInfo> usesLibraryInfos) {
        this.usesLibraryInfos = usesLibraryInfos;
        return this;
    }

    @Override
    public PackageImpl setUsesLibraryFiles(@Nullable String[] usesLibraryFiles) {
        this.usesLibraryFiles = usesLibraryFiles;
        return this;
    }

    @Override
    public PackageImpl setUid(int uid) {
        this.uid = uid;
        return this;
    }

    @Override
    public List<String> getAdoptPermissions() {
        return adoptPermissions;
    }

    @Override
    public ApplicationInfo toAppInfoWithoutState() {
        updateFlags();

        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.packageName = packageName;
        appInfo.flags = flags;
        appInfo.privateFlags = privateFlags;

        appInfo.appComponentFactory = appComponentFactory;
        appInfo.backupAgentName = backupAgentName;
        appInfo.banner = banner;
        appInfo.category = category;
        appInfo.classLoaderName = classLoaderName;
        appInfo.className = className;
        appInfo.compatibleWidthLimitDp = compatibleWidthLimitDp;
        appInfo.compileSdkVersion = compileSdkVersion;
        appInfo.compileSdkVersionCodename = compileSdkVersionCodename;
        appInfo.credentialProtectedDataDir = credentialProtectedDataDir;
        appInfo.dataDir = dataDir;
        appInfo.descriptionRes = descriptionRes;
        appInfo.deviceProtectedDataDir = deviceProtectedDataDir;
        appInfo.enabled = enabled;
        appInfo.fullBackupContent = fullBackupContent;
        appInfo.hiddenUntilInstalled = hiddenUntilInstalled;
        appInfo.icon = icon;
        appInfo.iconRes = iconRes;
        appInfo.installLocation = installLocation;
        appInfo.labelRes = labelRes;
        appInfo.largestWidthLimitDp = largestWidthLimitDp;
        appInfo.logo = logo;
        appInfo.manageSpaceActivityName = manageSpaceActivityName;
        appInfo.maxAspectRatio = maxAspectRatio;
        appInfo.metaData = appMetaData;
        appInfo.minAspectRatio = minAspectRatio;
        appInfo.minSdkVersion = minSdkVersion;
        appInfo.name = name;
        if (appInfo.name != null) {
            appInfo.name = appInfo.name.trim();
        }
        appInfo.nativeLibraryDir = nativeLibraryDir;
        appInfo.nativeLibraryRootDir = nativeLibraryRootDir;
        appInfo.nativeLibraryRootRequiresIsa = nativeLibraryRootRequiresIsa;
        appInfo.networkSecurityConfigRes = networkSecurityConfigRes;
        appInfo.nonLocalizedLabel = nonLocalizedLabel;
        if (appInfo.nonLocalizedLabel != null) {
            appInfo.nonLocalizedLabel = appInfo.nonLocalizedLabel.toString().trim();
        }
        appInfo.packageName = packageName;
        appInfo.permission = permission;
        appInfo.primaryCpuAbi = primaryCpuAbi;
        appInfo.processName = getProcessName();
        appInfo.requiresSmallestWidthDp = requiresSmallestWidthDp;
        appInfo.roundIconRes = roundIconRes;
        appInfo.secondaryCpuAbi = secondaryCpuAbi;
        appInfo.secondaryNativeLibraryDir = secondaryNativeLibraryDir;
        appInfo.seInfo = seInfo;
        appInfo.seInfoUser = seInfoUser;
        appInfo.sharedLibraryFiles = usesLibraryFiles;
        appInfo.sharedLibraryInfos = ArrayUtils.isEmpty(usesLibraryInfos) ? null : usesLibraryInfos;
        appInfo.splitClassLoaderNames = splitClassLoaderNames;
        appInfo.splitDependencies = splitDependencies;
        appInfo.splitNames = splitNames;
        appInfo.storageUuid = StorageManager.convert(volumeUuid);
        appInfo.targetSandboxVersion = targetSandboxVersion;
        appInfo.targetSdkVersion = targetSdkVersion;
        appInfo.taskAffinity = taskAffinity;
        appInfo.theme = theme;
        appInfo.uid = uid;
        appInfo.uiOptions = uiOptions;
        appInfo.volumeUuid = volumeUuid;
        appInfo.zygotePreloadName = zygotePreloadName;

        appInfo.setBaseCodePath(baseCodePath);
        appInfo.setBaseResourcePath(baseCodePath);
        appInfo.setCodePath(codePath);
        appInfo.setResourcePath(codePath);
        appInfo.setSplitCodePaths(splitCodePaths);
        appInfo.setSplitResourcePaths(splitCodePaths);
        appInfo.setVersionCode(getLongVersionCode());

        // TODO(b/135203078): Can this be removed? Looks only used in ActivityInfo.
//        appInfo.showUserIcon = pkg.getShowUserIcon();
        // TODO(b/135203078): Unused?
//        appInfo.resourceDirs = pkg.getResourceDirs();
        // TODO(b/135203078): Unused?
//        appInfo.enabledSetting = pkg.getEnabledSetting();
        // TODO(b/135203078): See PackageImpl#getHiddenApiEnforcementPolicy
//        appInfo.mHiddenApiPolicy = pkg.getHiddenApiPolicy();

        return appInfo;
    }

    @Override
    public PackageImpl setVersionCode(int versionCode) {
        this.versionCode = versionCode;
        return this;
    }

    @Override
    public PackageImpl setHiddenUntilInstalled(boolean hidden) {
        this.hiddenUntilInstalled = hidden;
        return this;
    }

    @Override
    public String getSeInfo() {
        return seInfo;
    }

    @Deprecated
    @Override
    public String getAppInfoResourcePath() {
        return applicationInfoResourcePath;
    }

    @Override
    public boolean isForwardLocked() {
        // TODO(b/135203078): Unused? Move to debug flag?
        return false;
    }

    @Override
    public byte[] getRestrictUpdateHash() {
        return restrictUpdateHash;
    }

    @Override
    public boolean hasComponentClassName(String className) {
        if (activities != null) {
            for (ParsedActivity parsedActivity : activities) {
                if (Objects.equals(className, parsedActivity.className)) {
                    return true;
                }
            }
        }

        if (receivers != null) {
            for (ParsedActivity receiver : receivers) {
                if (Objects.equals(className, receiver.className)) {
                    return true;
                }
            }
        }

        if (providers != null) {
            for (ParsedProvider provider : providers) {
                if (Objects.equals(className, provider.className)) {
                    return true;
                }
            }
        }

        if (services != null) {
            for (ParsedService service : services) {
                if (Objects.equals(className, service.className)) {
                    return true;
                }
            }
        }

        if (instrumentations != null) {
            for (ParsedInstrumentation instrumentation : instrumentations) {
                if (Objects.equals(className, instrumentation.className)) {
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public boolean isDefaultToDeviceProtectedStorage() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_DEFAULT_TO_DEVICE_PROTECTED_STORAGE)
                != 0;
    }

    @Override
    public boolean isInternal() {
        return (flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) == 0;
    }

    @Override
    public int getBaseRevisionCode() {
        return baseRevisionCode;
    }

    @Override
    public int[] getSplitRevisionCodes() {
        return splitRevisionCodes;
    }

    @Override
    public boolean canHaveOatDir() {
        // The following app types CANNOT have oat directory
        // - non-updated system apps
        return !isSystem() || isUpdatedSystemApp();
    }

    @Override
    public long getLatestPackageUseTimeInMills() {
        long latestUse = 0L;
        for (long use : lastPackageUsageTimeInMills) {
            latestUse = Math.max(latestUse, use);
        }
        return latestUse;
    }

    @Override
    public long getLatestForegroundPackageUseTimeInMills() {
        int[] foregroundReasons = {
                PackageManager.NOTIFY_PACKAGE_USE_ACTIVITY,
                PackageManager.NOTIFY_PACKAGE_USE_FOREGROUND_SERVICE
        };

        long latestUse = 0L;
        for (int reason : foregroundReasons) {
            latestUse = Math.max(latestUse, lastPackageUsageTimeInMills[reason]);
        }
        return latestUse;
    }

    @Override
    public boolean isCoreApp() {
        return coreApp;
    }

    @Override
    public String getVersionName() {
        return versionName;
    }

    @Override
    public PackageImpl setVersionCodeMajor(int versionCodeMajor) {
        this.versionCodeMajor = versionCodeMajor;
        return this;
    }

    @Override
    public long[] getLastPackageUsageTimeInMills() {
        return lastPackageUsageTimeInMills;
    }

    @Override
    public String getDataDir() {
        return dataDir;
    }

    @Override
    public boolean isExternal() {
        return (flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0;
    }

    @Override
    public List<String> getImplicitPermissions() {
        return implicitPermissions == null ? Collections.emptyList() : implicitPermissions;
    }

    /**
     * TODO(b/135203078): Remove, ensure b/140256621 is fixed or irrelevant
     * TODO(b/140256621): Remove after fixing instant app check
     * @deprecated This method always returns false because there's no paired set method
     */
    @Deprecated
    @Override
    public boolean isInstantApp() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_INSTANT) != 0;
    }

    @Override
    public boolean hasRequestedLegacyExternalStorage() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_REQUEST_LEGACY_EXTERNAL_STORAGE) != 0;
    }

    @Override
    public boolean isVendor() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_VENDOR) != 0;
    }

    @Override
    public boolean isProduct() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_PRODUCT) != 0;
    }

    @Override
    public boolean isOem() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_OEM) != 0;
    }

    @Override
    public boolean isEncryptionAware() {
        boolean isPartiallyDirectBootAware =
                (privateFlags & ApplicationInfo.PRIVATE_FLAG_PARTIALLY_DIRECT_BOOT_AWARE) != 0;
        return isDirectBootAware() || isPartiallyDirectBootAware;
    }

    @Override
    public boolean isEmbeddedDexUsed() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_USE_EMBEDDED_DEX) != 0;
    }

    @Deprecated
    @Override
    public String getAppInfoProcessName() {
        return processName;
    }

    @Override
    public List<String> getAllCodePathsExcludingResourceOnly() {
        ArrayList<String> paths = new ArrayList<>();
        if ((flags & ApplicationInfo.FLAG_HAS_CODE) != 0) {
            paths.add(baseCodePath);
        }
        if (!ArrayUtils.isEmpty(splitCodePaths)) {
            for (int i = 0; i < splitCodePaths.length; i++) {
                if ((splitFlags[i] & ApplicationInfo.FLAG_HAS_CODE) != 0) {
                    paths.add(splitCodePaths[i]);
                }
            }
        }
        return paths;
    }

    @Deprecated
    @Override
    public String getAppInfoName() {
        return name;
    }

    private boolean isSignedWithPlatformKey() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_SIGNED_WITH_PLATFORM_KEY) != 0;
    }

    private boolean usesNonSdkApi() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_USES_NON_SDK_API) != 0;
    }

    private boolean isPackageWhitelistedForHiddenApis() {
        return SystemConfig.getInstance().getHiddenApiWhitelistedApps().contains(packageName);
    }

    private boolean isAllowedToUseHiddenApis() {
        if (isSignedWithPlatformKey()) {
            return true;
        } else if (isSystemApp() || isUpdatedSystemApp()) {
            return usesNonSdkApi() || isPackageWhitelistedForHiddenApis();
        } else {
            return false;
        }
    }

    @Override
    public int getHiddenApiEnforcementPolicy() {
        if (isAllowedToUseHiddenApis()) {
            return ApplicationInfo.HIDDEN_API_ENFORCEMENT_DISABLED;
        }

        // TODO(b/135203078): Handle maybeUpdateHiddenApiEnforcementPolicy. Right now it's done
        //  entirely through ApplicationInfo and shouldn't touch this specific class, but that
        //  may not always hold true.
//        if (mHiddenApiPolicy != ApplicationInfo.HIDDEN_API_ENFORCEMENT_DEFAULT) {
//            return mHiddenApiPolicy;
//        }
        return ApplicationInfo.HIDDEN_API_ENFORCEMENT_ENABLED;
    }

    @Nullable
    @Override
    public SparseArray<int[]> getSplitDependencies() {
        return splitDependencies;
    }

    @Override
    public boolean requestsIsolatedSplitLoading() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_ISOLATED_SPLIT_LOADING) != 0;
    }

    @Deprecated
    @Override
    public String getAppInfoClassLoaderName() {
        return classLoaderName;
    }

    @Override
    public String getClassLoaderName() {
        return classLoaderName;
    }

    @Override
    public String[] getSplitClassLoaderNames() {
        return splitClassLoaderNames;
    }

    @Override
    public String getOverlayCategory() {
        return overlayCategory;
    }

    @Override
    public boolean isProfileableByShell() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_PROFILEABLE_BY_SHELL) != 0;
    }

    @Nullable
    @Override
    public List<ParsedActivityIntentInfo> getPreferredActivityFilters() {
        return preferredActivityFilters;
    }

    @Override
    public boolean isHiddenUntilInstalled() {
        return hiddenUntilInstalled;
    }

    @Override
    public int getMinSdkVersion() {
        return minSdkVersion;
    }

    @Override
    public String getRestrictedAccountType() {
        return restrictedAccountType;
    }

    @Override
    public String getRequiredAccountType() {
        return requiredAccountType;
    }

    @Override
    public int getInstallLocation() {
        return installLocation;
    }

    @Override
    public List<ParsedActivity> getReceivers() {
        return receivers;
    }

    @Override
    public List<ParsedService> getServices() {
        return services;
    }

    @Override
    public List<ParsedProvider> getProviders() {
        return providers;
    }

    @Override
    public int getSharedUserLabel() {
        return sharedUserLabel;
    }

    @Override
    public int getVersionCodeMajor() {
        return versionCodeMajor;
    }

    @Override
    public boolean isRequiredForAllUsers() {
        return requiredForAllUsers;
    }

    @Override
    public int getCompileSdkVersion() {
        return compileSdkVersion;
    }

    @Override
    public String getCompileSdkVersionCodeName() {
        return compileSdkVersionCodename;
    }

    @Nullable
    @Override
    public List<ConfigurationInfo> getConfigPreferences() {
        return configPreferences;
    }

    @Nullable
    @Override
    public List<FeatureInfo> getReqFeatures() {
        return reqFeatures;
    }

    @Override
    public List<FeatureGroupInfo> getFeatureGroups() {
        return featureGroups;
    }

    @Override
    public String getDeviceProtectedDataDir() {
        return deviceProtectedDataDir;
    }

    @Override
    public String getCredentialProtectedDataDir() {
        return credentialProtectedDataDir;
    }

    @Override
    public String getSeInfoUser() {
        return seInfoUser;
    }

    @Override
    public String getClassName() {
        return className;
    }

    @Override
    public int getTheme() {
        return theme;
    }

    @Override
    public int getRequiresSmallestWidthDp() {
        return requiresSmallestWidthDp;
    }

    @Override
    public int getCompatibleWidthLimitDp() {
        return compatibleWidthLimitDp;
    }

    @Override
    public int getLargestWidthLimitDp() {
        return largestWidthLimitDp;
    }

    @Override
    public String getScanSourceDir() {
        return applicationInfoCodePath;
    }

    @Override
    public String getScanPublicSourceDir() {
        return applicationInfoResourcePath;
    }

    @Override
    public String getPublicSourceDir() {
        return applicationInfoBaseResourcePath;
    }

    @Override
    public String[] getSplitPublicSourceDirs() {
        return applicationInfoSplitResourcePaths;
    }

    @Override
    public String getSecondaryNativeLibraryDir() {
        return secondaryNativeLibraryDir;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String getManageSpaceActivityName() {
        return manageSpaceActivityName;
    }

    @Override
    public int getDescriptionRes() {
        return descriptionRes;
    }

    @Override
    public String getBackupAgentName() {
        return backupAgentName;
    }

    @Override
    public int getFullBackupContent() {
        return fullBackupContent;
    }

    @Override
    public int getNetworkSecurityConfigRes() {
        return networkSecurityConfigRes;
    }

    @Override
    public int getCategory() {
        return category;
    }

    @Override
    public int getTargetSandboxVersion() {
        return targetSandboxVersion;
    }

    @Override
    public String getAppComponentFactory() {
        return appComponentFactory;
    }

    @Override
    public int getIconRes() {
        return iconRes;
    }

    @Override
    public int getRoundIconRes() {
        return roundIconRes;
    }

    @Override
    public String getZygotePreloadName() {
        return zygotePreloadName;
    }

    @Override
    public int getLabelRes() {
        return labelRes;
    }

    @Override
    public CharSequence getNonLocalizedLabel() {
        return nonLocalizedLabel;
    }

    @Override
    public int getIcon() {
        return icon;
    }

    @Override
    public int getBanner() {
        return banner;
    }

    @Override
    public int getLogo() {
        return logo;
    }

    @Override
    public Bundle getMetaData() {
        return appMetaData;
    }

    @Override
    @Nullable
    public List<Intent> getQueriesIntents() {
        return queriesIntents;
    }

    @Override
    @Nullable
    public List<String> getQueriesPackages() {
        return queriesPackages;
    }

    private static void internStringArrayList(List<String> list) {
        if (list != null) {
            final int N = list.size();
            for (int i = 0; i < N; ++i) {
                list.set(i, list.get(i).intern());
            }
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(this.supportsSmallScreens);
        dest.writeInt(this.supportsNormalScreens);
        dest.writeInt(this.supportsLargeScreens);
        dest.writeInt(this.supportsXLargeScreens);
        dest.writeInt(this.resizeable);
        dest.writeInt(this.anyDensity);
        dest.writeLongArray(this.lastPackageUsageTimeInMills);
        dest.writeInt(this.versionCode);
        dest.writeInt(this.versionCodeMajor);
        dest.writeInt(this.baseRevisionCode);
        dest.writeString(this.versionName);
        dest.writeBoolean(this.coreApp);
        dest.writeInt(this.compileSdkVersion);
        dest.writeString(this.compileSdkVersionCodename);
        dest.writeString(this.packageName);
        dest.writeString(this.realPackage);
        dest.writeString(this.manifestPackageName);
        dest.writeString(this.baseCodePath);
        dest.writeBoolean(this.requiredForAllUsers);
        dest.writeString(this.restrictedAccountType);
        dest.writeString(this.requiredAccountType);
        dest.writeBoolean(this.baseHardwareAccelerated);
        dest.writeString(this.overlayTarget);
        dest.writeString(this.overlayTargetName);
        dest.writeString(this.overlayCategory);
        dest.writeInt(this.overlayPriority);
        dest.writeBoolean(this.overlayIsStatic);
        dest.writeString(this.staticSharedLibName);
        dest.writeLong(this.staticSharedLibVersion);
        dest.writeStringList(this.libraryNames);
        dest.writeStringList(this.usesLibraries);
        dest.writeStringList(this.usesOptionalLibraries);
        dest.writeStringList(this.usesStaticLibraries);
        dest.writeLongArray(this.usesStaticLibrariesVersions);

        if (this.usesStaticLibrariesCertDigests == null) {
            dest.writeInt(-1);
        } else {
            dest.writeInt(this.usesStaticLibrariesCertDigests.length);
            for (int index = 0; index < this.usesStaticLibrariesCertDigests.length; index++) {
                dest.writeStringArray(this.usesStaticLibrariesCertDigests[index]);
            }
        }

        dest.writeString(this.sharedUserId);
        dest.writeInt(this.sharedUserLabel);
        dest.writeTypedList(this.configPreferences);
        dest.writeTypedList(this.reqFeatures);
        dest.writeTypedList(this.featureGroups);
        dest.writeByteArray(this.restrictUpdateHash);
        dest.writeStringList(this.originalPackages);
        dest.writeStringList(this.adoptPermissions);
        dest.writeStringList(this.requestedPermissions);
        dest.writeStringList(this.implicitPermissions);
        dest.writeArraySet(this.upgradeKeySets);
        dest.writeMap(this.keySetMapping);
        dest.writeStringList(this.protectedBroadcasts);
        dest.writeTypedList(this.activities);
        dest.writeTypedList(this.receivers);
        dest.writeTypedList(this.services);
        dest.writeTypedList(this.providers);
        dest.writeTypedList(this.permissions);
        dest.writeTypedList(this.permissionGroups);
        dest.writeTypedList(this.instrumentations);
        ParsedIntentInfo.writeIntentsList(this.preferredActivityFilters, dest, flags);
        dest.writeBundle(this.appMetaData);
        dest.writeString(this.volumeUuid);
        dest.writeString(this.applicationVolumeUuid);
        dest.writeParcelable(this.signingDetails, flags);
        dest.writeString(this.codePath);
        dest.writeBoolean(this.use32BitAbi);
        dest.writeBoolean(this.visibleToInstantApps);
        dest.writeString(this.cpuAbiOverride);
        dest.writeBoolean(this.isStub);
        dest.writeInt(this.preferredOrder);
        dest.writeBoolean(this.forceQueryable);
        dest.writeParcelableList(this.queriesIntents, flags);
        dest.writeStringList(this.queriesPackages);
        dest.writeString(this.applicationInfoBaseResourcePath);
        dest.writeString(this.applicationInfoCodePath);
        dest.writeString(this.applicationInfoResourcePath);
        dest.writeStringArray(this.applicationInfoSplitResourcePaths);
        dest.writeString(this.appComponentFactory);
        dest.writeString(this.backupAgentName);
        dest.writeInt(this.banner);
        dest.writeInt(this.category);
        dest.writeString(this.classLoaderName);
        dest.writeString(this.className);
        dest.writeInt(this.compatibleWidthLimitDp);
        dest.writeString(this.credentialProtectedDataDir);
        dest.writeString(this.dataDir);
        dest.writeInt(this.descriptionRes);
        dest.writeString(this.deviceProtectedDataDir);
        dest.writeBoolean(this.enabled);
        dest.writeInt(this.flags);
        dest.writeInt(this.fullBackupContent);
        dest.writeBoolean(this.hiddenUntilInstalled);
        dest.writeInt(this.icon);
        dest.writeInt(this.iconRes);
        dest.writeInt(this.installLocation);
        dest.writeInt(this.labelRes);
        dest.writeInt(this.largestWidthLimitDp);
        dest.writeInt(this.logo);
        dest.writeString(this.manageSpaceActivityName);
        dest.writeFloat(this.maxAspectRatio);
        dest.writeFloat(this.minAspectRatio);
        dest.writeInt(this.minSdkVersion);
        dest.writeString(this.name);
        dest.writeString(this.nativeLibraryDir);
        dest.writeString(this.nativeLibraryRootDir);
        dest.writeBoolean(this.nativeLibraryRootRequiresIsa);
        dest.writeInt(this.networkSecurityConfigRes);
        dest.writeCharSequence(this.nonLocalizedLabel);
        dest.writeString(this.permission);
        dest.writeString(this.primaryCpuAbi);
        dest.writeInt(this.privateFlags);
        dest.writeString(this.processName);
        dest.writeInt(this.requiresSmallestWidthDp);
        dest.writeInt(this.roundIconRes);
        dest.writeString(this.secondaryCpuAbi);
        dest.writeString(this.secondaryNativeLibraryDir);
        dest.writeString(this.seInfo);
        dest.writeString(this.seInfoUser);
        dest.writeInt(this.targetSandboxVersion);
        dest.writeInt(this.targetSdkVersion);
        dest.writeString(this.taskAffinity);
        dest.writeInt(this.theme);
        dest.writeInt(this.uid);
        dest.writeInt(this.uiOptions);
        dest.writeStringArray(this.usesLibraryFiles);
        dest.writeTypedList(this.usesLibraryInfos);
        dest.writeString(this.zygotePreloadName);
        dest.writeStringArray(this.splitClassLoaderNames);
        dest.writeStringArray(this.splitCodePaths);
        dest.writeSparseArray(this.splitDependencies);
        dest.writeIntArray(this.splitFlags);
        dest.writeStringArray(this.splitNames);
        dest.writeIntArray(this.splitRevisionCodes);
    }

    public PackageImpl(Parcel in) {
        // We use the boot classloader for all classes that we load.
        final ClassLoader boot = Object.class.getClassLoader();
        this.supportsSmallScreens = in.readInt();
        this.supportsNormalScreens = in.readInt();
        this.supportsLargeScreens = in.readInt();
        this.supportsXLargeScreens = in.readInt();
        this.resizeable = in.readInt();
        this.anyDensity = in.readInt();
        this.lastPackageUsageTimeInMills = in.createLongArray();
        this.versionCode = in.readInt();
        this.versionCodeMajor = in.readInt();
        this.baseRevisionCode = in.readInt();
        this.versionName = TextUtils.safeIntern(in.readString());
        this.coreApp = in.readBoolean();
        this.compileSdkVersion = in.readInt();
        this.compileSdkVersionCodename = TextUtils.safeIntern(in.readString());
        this.packageName = TextUtils.safeIntern(in.readString());
        this.realPackage = in.readString();
        this.manifestPackageName = in.readString();
        this.baseCodePath = in.readString();
        this.requiredForAllUsers = in.readBoolean();
        this.restrictedAccountType = in.readString();
        this.requiredAccountType = in.readString();
        this.baseHardwareAccelerated = in.readBoolean();
        this.overlayTarget = in.readString();
        this.overlayTargetName = in.readString();
        this.overlayCategory = in.readString();
        this.overlayPriority = in.readInt();
        this.overlayIsStatic = in.readBoolean();
        this.staticSharedLibName = TextUtils.safeIntern(in.readString());
        this.staticSharedLibVersion = in.readLong();
        this.libraryNames = in.createStringArrayList();
        internStringArrayList(this.libraryNames);
        this.usesLibraries = in.createStringArrayList();
        internStringArrayList(this.usesLibraries);
        this.usesOptionalLibraries = in.createStringArrayList();
        internStringArrayList(this.usesOptionalLibraries);
        this.usesStaticLibraries = in.createStringArrayList();
        internStringArrayList(usesStaticLibraries);
        this.usesStaticLibrariesVersions = in.createLongArray();

        int digestsSize = in.readInt();
        if (digestsSize >= 0) {
            this.usesStaticLibrariesCertDigests = new String[digestsSize][];
            for (int index = 0; index < digestsSize; index++) {
                this.usesStaticLibrariesCertDigests[index] = in.readStringArray();
            }
        }

        this.sharedUserId = TextUtils.safeIntern(in.readString());
        this.sharedUserLabel = in.readInt();
        this.configPreferences = in.createTypedArrayList(ConfigurationInfo.CREATOR);
        this.reqFeatures = in.createTypedArrayList(FeatureInfo.CREATOR);
        this.featureGroups = in.createTypedArrayList(FeatureGroupInfo.CREATOR);
        this.restrictUpdateHash = in.createByteArray();
        this.originalPackages = in.createStringArrayList();
        this.adoptPermissions = in.createStringArrayList();
        this.requestedPermissions = in.createStringArrayList();
        internStringArrayList(this.requestedPermissions);
        this.implicitPermissions = in.createStringArrayList();
        internStringArrayList(this.implicitPermissions);
        this.upgradeKeySets = (ArraySet<String>) in.readArraySet(boot);
        this.keySetMapping = in.readHashMap(boot);
        this.protectedBroadcasts = in.createStringArrayList();
        internStringArrayList(this.protectedBroadcasts);
        this.activities = in.createTypedArrayList(ParsedActivity.CREATOR);
        this.receivers = in.createTypedArrayList(ParsedActivity.CREATOR);
        this.services = in.createTypedArrayList(ParsedService.CREATOR);
        this.providers = in.createTypedArrayList(ParsedProvider.CREATOR);
        this.permissions = in.createTypedArrayList(ParsedPermission.CREATOR);
        this.permissionGroups = in.createTypedArrayList(ParsedPermissionGroup.CREATOR);
        this.instrumentations = in.createTypedArrayList(ParsedInstrumentation.CREATOR);
        this.preferredActivityFilters = ParsedIntentInfo.createIntentsList(in);
        this.appMetaData = in.readBundle(boot);
        this.volumeUuid = in.readString();
        this.applicationVolumeUuid = in.readString();
        this.signingDetails = in.readParcelable(boot);
        this.codePath = in.readString();
        this.use32BitAbi = in.readBoolean();
        this.visibleToInstantApps = in.readBoolean();
        this.cpuAbiOverride = in.readString();
        this.isStub = in.readBoolean();
        this.preferredOrder = in.readInt();
        this.forceQueryable = in.readBoolean();
        this.queriesIntents = in.createTypedArrayList(Intent.CREATOR);
        this.queriesPackages = in.createStringArrayList();
        internStringArrayList(this.queriesPackages);
        this.applicationInfoBaseResourcePath = in.readString();
        this.applicationInfoCodePath = in.readString();
        this.applicationInfoResourcePath = in.readString();
        this.applicationInfoSplitResourcePaths = in.createStringArray();
        this.appComponentFactory = in.readString();
        this.backupAgentName = in.readString();
        this.banner = in.readInt();
        this.category = in.readInt();
        this.classLoaderName = in.readString();
        this.className = in.readString();
        this.compatibleWidthLimitDp = in.readInt();
        this.credentialProtectedDataDir = in.readString();
        this.dataDir = in.readString();
        this.descriptionRes = in.readInt();
        this.deviceProtectedDataDir = in.readString();
        this.enabled = in.readBoolean();
        this.flags = in.readInt();
        this.fullBackupContent = in.readInt();
        this.hiddenUntilInstalled = in.readBoolean();
        this.icon = in.readInt();
        this.iconRes = in.readInt();
        this.installLocation = in.readInt();
        this.labelRes = in.readInt();
        this.largestWidthLimitDp = in.readInt();
        this.logo = in.readInt();
        this.manageSpaceActivityName = in.readString();
        this.maxAspectRatio = in.readFloat();
        this.minAspectRatio = in.readFloat();
        this.minSdkVersion = in.readInt();
        this.name = in.readString();
        this.nativeLibraryDir = in.readString();
        this.nativeLibraryRootDir = in.readString();
        this.nativeLibraryRootRequiresIsa = in.readBoolean();
        this.networkSecurityConfigRes = in.readInt();
        this.nonLocalizedLabel = in.readCharSequence();
        this.permission = TextUtils.safeIntern(in.readString());
        this.primaryCpuAbi = in.readString();
        this.privateFlags = in.readInt();
        this.processName = in.readString();
        this.requiresSmallestWidthDp = in.readInt();
        this.roundIconRes = in.readInt();
        this.secondaryCpuAbi = in.readString();
        this.secondaryNativeLibraryDir = in.readString();
        this.seInfo = in.readString();
        this.seInfoUser = in.readString();
        this.targetSandboxVersion = in.readInt();
        this.targetSdkVersion = in.readInt();
        this.taskAffinity = in.readString();
        this.theme = in.readInt();
        this.uid = in.readInt();
        this.uiOptions = in.readInt();
        this.usesLibraryFiles = in.createStringArray();
        this.usesLibraryInfos = in.createTypedArrayList(SharedLibraryInfo.CREATOR);
        this.zygotePreloadName = in.readString();
        this.splitClassLoaderNames = in.createStringArray();
        this.splitCodePaths = in.createStringArray();
        this.splitDependencies = in.readSparseArray(boot);
        this.splitFlags = in.createIntArray();
        this.splitNames = in.createStringArray();
        this.splitRevisionCodes = in.createIntArray();
    }

    public static final Creator<PackageImpl> CREATOR = new Creator<PackageImpl>() {
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
