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

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureGroupInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageParser;
import android.content.pm.parsing.component.ParsedActivity;
import android.content.pm.parsing.component.ParsedAttribution;
import android.content.pm.parsing.component.ParsedComponent;
import android.content.pm.parsing.component.ParsedInstrumentation;
import android.content.pm.parsing.component.ParsedIntentInfo;
import android.content.pm.parsing.component.ParsedMainComponent;
import android.content.pm.parsing.component.ParsedPermission;
import android.content.pm.parsing.component.ParsedPermissionGroup;
import android.content.pm.parsing.component.ParsedProcess;
import android.content.pm.parsing.component.ParsedProvider;
import android.content.pm.parsing.component.ParsedService;
import android.content.res.TypedArray;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.storage.StorageManager;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Pair;
import android.util.SparseArray;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.DataClass;
import com.android.internal.util.Parcelling;
import com.android.internal.util.Parcelling.BuiltIn.ForBoolean;
import com.android.internal.util.Parcelling.BuiltIn.ForInternedString;
import com.android.internal.util.Parcelling.BuiltIn.ForInternedStringArray;
import com.android.internal.util.Parcelling.BuiltIn.ForInternedStringList;
import com.android.internal.util.Parcelling.BuiltIn.ForInternedStringSet;
import com.android.internal.util.Parcelling.BuiltIn.ForInternedStringValueMap;
import com.android.internal.util.Parcelling.BuiltIn.ForStringSet;

import java.security.PublicKey;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The backing data for a package that was parsed from disk.
 *
 * The field nullability annotations here are for internal reference. For effective nullability,
 * see the parent interfaces.
 *
 * TODO(b/135203078): Convert Lists used as sets into Sets, to better express intended use case
 *
 * @hide
 */
public class ParsingPackageImpl implements ParsingPackage, Parcelable {

    private static final String TAG = "PackageImpl";

    public static ForBoolean sForBoolean = Parcelling.Cache.getOrCreate(ForBoolean.class);
    public static ForInternedString sForInternedString = Parcelling.Cache.getOrCreate(
            ForInternedString.class);
    public static ForInternedStringArray sForInternedStringArray = Parcelling.Cache.getOrCreate(
            ForInternedStringArray.class);
    public static ForInternedStringList sForInternedStringList = Parcelling.Cache.getOrCreate(
            ForInternedStringList.class);
    public static ForInternedStringValueMap sForInternedStringValueMap =
            Parcelling.Cache.getOrCreate(ForInternedStringValueMap.class);
    public static ForStringSet sForStringSet = Parcelling.Cache.getOrCreate(ForStringSet.class);
    protected static ParsedIntentInfo.StringPairListParceler sForIntentInfoPairs =
            Parcelling.Cache.getOrCreate(ParsedIntentInfo.StringPairListParceler.class);

    private static final Comparator<ParsedMainComponent> ORDER_COMPARATOR =
            (first, second) -> Integer.compare(second.getOrder(), first.getOrder());

    // These are objects because null represents not explicitly set
    @Nullable
    @DataClass.ParcelWith(ForBoolean.class)
    private Boolean supportsSmallScreens;
    @Nullable
    @DataClass.ParcelWith(ForBoolean.class)
    private Boolean supportsNormalScreens;
    @Nullable
    @DataClass.ParcelWith(ForBoolean.class)
    private Boolean supportsLargeScreens;
    @Nullable
    @DataClass.ParcelWith(ForBoolean.class)
    private Boolean supportsExtraLargeScreens;
    @Nullable
    @DataClass.ParcelWith(ForBoolean.class)
    private Boolean resizeable;
    @Nullable
    @DataClass.ParcelWith(ForBoolean.class)
    private Boolean anyDensity;

    protected int versionCode;
    protected int versionCodeMajor;
    private int baseRevisionCode;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String versionName;

    private int compileSdkVersion;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String compileSdkVersionCodeName;

    @NonNull
    @DataClass.ParcelWith(ForInternedString.class)
    protected String packageName;

    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String realPackage;

    @NonNull
    protected String baseCodePath;

    private boolean requiredForAllUsers;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String restrictedAccountType;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String requiredAccountType;

    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String overlayTarget;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String overlayTargetName;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String overlayCategory;
    private int overlayPriority;
    private boolean overlayIsStatic;
    @NonNull
    @DataClass.ParcelWith(ForInternedStringValueMap.class)
    private Map<String, String> overlayables = emptyMap();

    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String staticSharedLibName;
    private long staticSharedLibVersion;
    @NonNull
    @DataClass.ParcelWith(ForInternedStringList.class)
    private List<String> libraryNames = emptyList();
    @NonNull
    @DataClass.ParcelWith(ForInternedStringList.class)
    protected List<String> usesLibraries = emptyList();
    @NonNull
    @DataClass.ParcelWith(ForInternedStringList.class)
    protected List<String> usesOptionalLibraries = emptyList();

    @NonNull
    @DataClass.ParcelWith(ForInternedStringList.class)
    private List<String> usesStaticLibraries = emptyList();
    @Nullable
    private long[] usesStaticLibrariesVersions;

    @Nullable
    private String[][] usesStaticLibrariesCertDigests;

    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String sharedUserId;

    private int sharedUserLabel;
    @NonNull
    private List<ConfigurationInfo> configPreferences = emptyList();
    @NonNull
    private List<FeatureInfo> reqFeatures = emptyList();
    @NonNull
    private List<FeatureGroupInfo> featureGroups = emptyList();

    @Nullable
    private byte[] restrictUpdateHash;

    @NonNull
    @DataClass.ParcelWith(ForInternedStringList.class)
    protected List<String> originalPackages = emptyList();
    @NonNull
    @DataClass.ParcelWith(ForInternedStringList.class)
    protected List<String> adoptPermissions = emptyList();

    @NonNull
    @DataClass.ParcelWith(ForInternedStringList.class)
    private List<String> requestedPermissions = emptyList();
    @NonNull
    @DataClass.ParcelWith(ForInternedStringList.class)
    private List<String> implicitPermissions = emptyList();

    @NonNull
    private Set<String> upgradeKeySets = emptySet();
    @NonNull
    private Map<String, ArraySet<PublicKey>> keySetMapping = emptyMap();

    @NonNull
    @DataClass.ParcelWith(ForInternedStringList.class)
    protected List<String> protectedBroadcasts = emptyList();

    @NonNull
    protected List<ParsedActivity> activities = emptyList();

    @NonNull
    protected List<ParsedActivity> receivers = emptyList();

    @NonNull
    protected List<ParsedService> services = emptyList();

    @NonNull
    protected List<ParsedProvider> providers = emptyList();

    @NonNull
    private List<ParsedAttribution> attributions = emptyList();

    @NonNull
    protected List<ParsedPermission> permissions = emptyList();

    @NonNull
    protected List<ParsedPermissionGroup> permissionGroups = emptyList();

    @NonNull
    protected List<ParsedInstrumentation> instrumentations = emptyList();

    @NonNull
    @DataClass.ParcelWith(ParsedIntentInfo.ListParceler.class)
    private List<Pair<String, ParsedIntentInfo>> preferredActivityFilters = emptyList();

    @NonNull
    private Map<String, ParsedProcess> processes = emptyMap();

    @Nullable
    private Bundle metaData;

    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    protected String volumeUuid;
    @Nullable
    private PackageParser.SigningDetails signingDetails;

    @NonNull
    @DataClass.ParcelWith(ForInternedString.class)
    protected String codePath;

    private boolean use32BitAbi;
    private boolean visibleToInstantApps;

    private boolean forceQueryable;

    @NonNull
    @DataClass.ParcelWith(ForInternedStringList.class)
    private List<Intent> queriesIntents = emptyList();

    @NonNull
    @DataClass.ParcelWith(ForInternedStringList.class)
    private List<String> queriesPackages = emptyList();

    @NonNull
    @DataClass.ParcelWith(ForInternedStringSet.class)
    private Set<String> queriesProviders = emptySet();

    @Nullable
    @DataClass.ParcelWith(ForInternedStringArray.class)
    private String[] splitClassLoaderNames;
    @Nullable
    protected String[] splitCodePaths;
    @Nullable
    private SparseArray<int[]> splitDependencies;
    @Nullable
    private int[] splitFlags;
    @Nullable
    @DataClass.ParcelWith(ForInternedStringArray.class)
    private String[] splitNames;
    @Nullable
    private int[] splitRevisionCodes;

    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String appComponentFactory;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String backupAgentName;
    private int banner;
    private int category;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String classLoaderName;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String className;
    private int compatibleWidthLimitDp;
    private int descriptionRes;
    private boolean enabled;
    private boolean crossProfile;
    private int fullBackupContent;
    private int iconRes;
    private int installLocation = PackageParser.PARSE_DEFAULT_INSTALL_LOCATION;
    private int labelRes;
    private int largestWidthLimitDp;
    private int logo;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String manageSpaceActivityName;
    private float maxAspectRatio;
    private float minAspectRatio;
    private int minSdkVersion;
    private int networkSecurityConfigRes;
    @Nullable
    private CharSequence nonLocalizedLabel;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String permission;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String processName;
    private int requiresSmallestWidthDp;
    private int roundIconRes;
    private int targetSandboxVersion;
    private int targetSdkVersion;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String taskAffinity;
    private int theme;

    private int uiOptions;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String zygotePreloadName;

    private boolean externalStorage;
    private boolean baseHardwareAccelerated;
    private boolean allowBackup;
    private boolean killAfterRestore;
    private boolean restoreAnyVersion;
    private boolean fullBackupOnly;
    private boolean persistent;
    private boolean debuggable;
    private boolean vmSafeMode;
    private boolean hasCode;
    private boolean allowTaskReparenting;
    private boolean allowClearUserData;
    private boolean largeHeap;
    private boolean usesCleartextTraffic;
    private boolean supportsRtl;
    private boolean testOnly;
    private boolean multiArch;
    private boolean extractNativeLibs;
    private boolean game;

    /**
     * @see ParsingPackageRead#getResizeableActivity()
     */
    @Nullable
    @DataClass.ParcelWith(ForBoolean.class)
    private Boolean resizeableActivity;

    private boolean staticSharedLibrary;
    private boolean overlay;
    private boolean isolatedSplitLoading;
    private boolean hasDomainUrls;
    private boolean profileableByShell;
    private boolean backupInForeground;
    private boolean useEmbeddedDex;
    private boolean defaultToDeviceProtectedStorage;
    private boolean directBootAware;
    private boolean partiallyDirectBootAware;
    private boolean resizeableActivityViaSdkVersion;
    private boolean allowClearUserDataOnFailedRestore;
    private boolean allowAudioPlaybackCapture;
    private boolean requestLegacyExternalStorage;
    private boolean usesNonSdkApi;
    private boolean hasFragileUserData;
    private boolean cantSaveState;
    private boolean allowNativeHeapPointerTagging;
    private int autoRevokePermissions;
    private boolean preserveLegacyExternalStorage;

    protected int gwpAsanMode;

    // TODO(chiuwinson): Non-null
    @Nullable
    private ArraySet<String> mimeGroups;

    @VisibleForTesting
    public ParsingPackageImpl(@NonNull String packageName, @NonNull String baseCodePath,
            @NonNull String codePath, @Nullable TypedArray manifestArray) {
        this.packageName = TextUtils.safeIntern(packageName);
        this.baseCodePath = baseCodePath;
        this.codePath = codePath;

        if (manifestArray != null) {
            versionCode = manifestArray.getInteger(R.styleable.AndroidManifest_versionCode, 0);
            versionCodeMajor = manifestArray.getInteger(
                    R.styleable.AndroidManifest_versionCodeMajor, 0);
            setBaseRevisionCode(
                    manifestArray.getInteger(R.styleable.AndroidManifest_revisionCode, 0));
            setVersionName(manifestArray.getNonConfigurationString(
                    R.styleable.AndroidManifest_versionName, 0));

            setCompileSdkVersion(manifestArray.getInteger(
                    R.styleable.AndroidManifest_compileSdkVersion, 0));
            setCompileSdkVersionCodename(manifestArray.getNonConfigurationString(
                    R.styleable.AndroidManifest_compileSdkVersionCodename, 0));

            setIsolatedSplitLoading(manifestArray.getBoolean(
                    R.styleable.AndroidManifest_isolatedSplits, false));

        }
    }

    public boolean isSupportsSmallScreens() {
        if (supportsSmallScreens == null) {
            return targetSdkVersion >= Build.VERSION_CODES.DONUT;
        }

        return supportsSmallScreens;
    }

    public boolean isSupportsNormalScreens() {
        return supportsNormalScreens == null || supportsNormalScreens;
    }

    public boolean isSupportsLargeScreens() {
        if (supportsLargeScreens == null) {
            return targetSdkVersion >= Build.VERSION_CODES.DONUT;
        }

        return supportsLargeScreens;
    }

    public boolean isSupportsExtraLargeScreens() {
        if (supportsExtraLargeScreens == null) {
            return targetSdkVersion >= Build.VERSION_CODES.GINGERBREAD;
        }

        return supportsExtraLargeScreens;
    }

    public boolean isResizeable() {
        if (resizeable == null) {
            return targetSdkVersion >= Build.VERSION_CODES.DONUT;
        }

        return resizeable;
    }

    public boolean isAnyDensity() {
        if (anyDensity == null) {
            return targetSdkVersion >= Build.VERSION_CODES.DONUT;
        }

        return anyDensity;
    }

    @Override
    public ParsingPackageImpl sortActivities() {
        Collections.sort(this.activities, ORDER_COMPARATOR);
        return this;
    }

    @Override
    public ParsingPackageImpl sortReceivers() {
        Collections.sort(this.receivers, ORDER_COMPARATOR);
        return this;
    }

    @Override
    public ParsingPackageImpl sortServices() {
        Collections.sort(this.services, ORDER_COMPARATOR);
        return this;
    }

    @Override
    public Object hideAsParsed() {
        // There is no equivalent for core-only parsing
        throw new UnsupportedOperationException();
    }

    @Override
    public ParsingPackageImpl addConfigPreference(ConfigurationInfo configPreference) {
        this.configPreferences = CollectionUtils.add(this.configPreferences, configPreference);
        return this;
    }

    @Override
    public ParsingPackageImpl addReqFeature(FeatureInfo reqFeature) {
        this.reqFeatures = CollectionUtils.add(this.reqFeatures, reqFeature);
        return this;
    }

    @Override
    public ParsingPackageImpl addFeatureGroup(FeatureGroupInfo featureGroup) {
        this.featureGroups = CollectionUtils.add(this.featureGroups, featureGroup);
        return this;
    }

    @Override
    public ParsingPackageImpl addProtectedBroadcast(String protectedBroadcast) {
        if (!this.protectedBroadcasts.contains(protectedBroadcast)) {
            this.protectedBroadcasts = CollectionUtils.add(this.protectedBroadcasts,
                    TextUtils.safeIntern(protectedBroadcast));
        }
        return this;
    }

    @Override
    public ParsingPackageImpl addInstrumentation(ParsedInstrumentation instrumentation) {
        this.instrumentations = CollectionUtils.add(this.instrumentations, instrumentation);
        return this;
    }

    @Override
    public ParsingPackageImpl addOriginalPackage(String originalPackage) {
        this.originalPackages = CollectionUtils.add(this.originalPackages, originalPackage);
        return this;
    }

    @Override
    public ParsingPackage addOverlayable(String overlayableName, String actorName) {
        this.overlayables = CollectionUtils.add(this.overlayables, overlayableName,
                TextUtils.safeIntern(actorName));
        return this;
    }

    @Override
    public ParsingPackageImpl addAdoptPermission(String adoptPermission) {
        this.adoptPermissions = CollectionUtils.add(this.adoptPermissions,
                TextUtils.safeIntern(adoptPermission));
        return this;
    }

    @Override
    public ParsingPackageImpl addPermission(ParsedPermission permission) {
        this.permissions = CollectionUtils.add(this.permissions, permission);
        return this;
    }

    @Override
    public ParsingPackageImpl addPermissionGroup(ParsedPermissionGroup permissionGroup) {
        this.permissionGroups = CollectionUtils.add(this.permissionGroups, permissionGroup);
        return this;
    }

    @Override
    public ParsingPackageImpl addRequestedPermission(String permission) {
        this.requestedPermissions = CollectionUtils.add(this.requestedPermissions,
                TextUtils.safeIntern(permission));
        return this;
    }

    @Override
    public ParsingPackageImpl addImplicitPermission(String permission) {
        this.implicitPermissions = CollectionUtils.add(this.implicitPermissions,
                TextUtils.safeIntern(permission));
        return this;
    }

    @Override
    public ParsingPackageImpl addKeySet(String keySetName, PublicKey publicKey) {
        ArraySet<PublicKey> publicKeys = keySetMapping.get(keySetName);
        if (publicKeys == null) {
            publicKeys = new ArraySet<>();
        }
        publicKeys.add(publicKey);
        keySetMapping = CollectionUtils.add(this.keySetMapping, keySetName, publicKeys);
        return this;
    }

    @Override
    public ParsingPackageImpl addActivity(ParsedActivity parsedActivity) {
        this.activities = CollectionUtils.add(this.activities, parsedActivity);
        addMimeGroupsFromComponent(parsedActivity);
        return this;
    }

    @Override
    public ParsingPackageImpl addReceiver(ParsedActivity parsedReceiver) {
        this.receivers = CollectionUtils.add(this.receivers, parsedReceiver);
        addMimeGroupsFromComponent(parsedReceiver);
        return this;
    }

    @Override
    public ParsingPackageImpl addService(ParsedService parsedService) {
        this.services = CollectionUtils.add(this.services, parsedService);
        addMimeGroupsFromComponent(parsedService);
        return this;
    }

    @Override
    public ParsingPackageImpl addProvider(ParsedProvider parsedProvider) {
        this.providers = CollectionUtils.add(this.providers, parsedProvider);
        addMimeGroupsFromComponent(parsedProvider);
        return this;
    }

    @Override
    public ParsingPackageImpl addAttribution(ParsedAttribution attribution) {
        this.attributions = CollectionUtils.add(this.attributions, attribution);
        return this;
    }

    @Override
    public ParsingPackageImpl addLibraryName(String libraryName) {
        this.libraryNames = CollectionUtils.add(this.libraryNames,
                TextUtils.safeIntern(libraryName));
        return this;
    }

    @Override
    public ParsingPackageImpl addUsesOptionalLibrary(String libraryName) {
        this.usesOptionalLibraries = CollectionUtils.add(this.usesOptionalLibraries,
                TextUtils.safeIntern(libraryName));
        return this;
    }

    @Override
    public ParsingPackageImpl addUsesLibrary(String libraryName) {
        this.usesLibraries = CollectionUtils.add(this.usesLibraries,
                TextUtils.safeIntern(libraryName));
        return this;
    }

    @Override
    public ParsingPackageImpl removeUsesOptionalLibrary(String libraryName) {
        this.usesOptionalLibraries = CollectionUtils.remove(this.usesOptionalLibraries,
                libraryName);
        return this;
    }

    @Override
    public ParsingPackageImpl addUsesStaticLibrary(String libraryName) {
        this.usesStaticLibraries = CollectionUtils.add(this.usesStaticLibraries,
                TextUtils.safeIntern(libraryName));
        return this;
    }

    @Override
    public ParsingPackageImpl addUsesStaticLibraryVersion(long version) {
        this.usesStaticLibrariesVersions = ArrayUtils.appendLong(this.usesStaticLibrariesVersions,
                version, true);
        return this;
    }

    @Override
    public ParsingPackageImpl addUsesStaticLibraryCertDigests(String[] certSha256Digests) {
        this.usesStaticLibrariesCertDigests = ArrayUtils.appendElement(String[].class,
                this.usesStaticLibrariesCertDigests, certSha256Digests, true);
        return this;
    }

    @Override
    public ParsingPackageImpl addPreferredActivityFilter(String className,
            ParsedIntentInfo intentInfo) {
        this.preferredActivityFilters = CollectionUtils.add(this.preferredActivityFilters,
                Pair.create(className, intentInfo));
        return this;
    }

    @Override
    public ParsingPackageImpl addQueriesIntent(Intent intent) {
        this.queriesIntents = CollectionUtils.add(this.queriesIntents, intent);
        return this;
    }

    @Override
    public ParsingPackageImpl addQueriesPackage(String packageName) {
        this.queriesPackages = CollectionUtils.add(this.queriesPackages,
                TextUtils.safeIntern(packageName));
        return this;
    }

    @Override
    public ParsingPackageImpl addQueriesProvider(String authority) {
        this.queriesProviders = CollectionUtils.add(this.queriesProviders, authority);
        return this;
    }

    @Override
    public ParsingPackageImpl setSupportsSmallScreens(int supportsSmallScreens) {
        if (supportsSmallScreens == 1) {
            return this;
        }

        this.supportsSmallScreens = supportsSmallScreens < 0;
        return this;
    }

    @Override
    public ParsingPackageImpl setSupportsNormalScreens(int supportsNormalScreens) {
        if (supportsNormalScreens == 1) {
            return this;
        }

        this.supportsNormalScreens = supportsNormalScreens < 0;
        return this;
    }

    @Override
    public ParsingPackageImpl setSupportsLargeScreens(int supportsLargeScreens) {
        if (supportsLargeScreens == 1) {
            return this;
        }

        this.supportsLargeScreens = supportsLargeScreens < 0;
        return this;
    }

    @Override
    public ParsingPackageImpl setSupportsExtraLargeScreens(int supportsExtraLargeScreens) {
        if (supportsExtraLargeScreens == 1) {
            return this;
        }

        this.supportsExtraLargeScreens = supportsExtraLargeScreens < 0;
        return this;
    }

    @Override
    public ParsingPackageImpl setResizeable(int resizeable) {
        if (resizeable == 1) {
            return this;
        }

        this.resizeable = resizeable < 0;
        return this;
    }

    @Override
    public ParsingPackageImpl setAnyDensity(int anyDensity) {
        if (anyDensity == 1) {
            return this;
        }

        this.anyDensity = anyDensity < 0;
        return this;
    }

    @Override
    public ParsingPackageImpl asSplit(String[] splitNames, String[] splitCodePaths,
            int[] splitRevisionCodes, SparseArray<int[]> splitDependencies) {
        this.splitNames = splitNames;
        this.splitCodePaths = splitCodePaths;
        this.splitRevisionCodes = splitRevisionCodes;
        this.splitDependencies = splitDependencies;

        int count = splitNames.length;
        this.splitFlags = new int[count];
        this.splitClassLoaderNames = new String[count];
        return this;
    }

    @Override
    public ParsingPackageImpl setSplitHasCode(int splitIndex, boolean splitHasCode) {
        this.splitFlags[splitIndex] = splitHasCode
                ? this.splitFlags[splitIndex] | ApplicationInfo.FLAG_HAS_CODE
                : this.splitFlags[splitIndex] & ~ApplicationInfo.FLAG_HAS_CODE;
        return this;
    }

    @Override
    public ParsingPackageImpl setSplitClassLoaderName(int splitIndex, String classLoaderName) {
        this.splitClassLoaderNames[splitIndex] = classLoaderName;
        return this;
    }

    @Override
    public ParsingPackageImpl setRequiredAccountType(@Nullable String requiredAccountType) {
        this.requiredAccountType = TextUtils.nullIfEmpty(requiredAccountType);
        return this;
    }

    @Override
    public ParsingPackageImpl setOverlayTarget(@Nullable String overlayTarget) {
        this.overlayTarget = TextUtils.safeIntern(overlayTarget);
        return this;
    }

    @Override
    public ParsingPackageImpl setVolumeUuid(@Nullable String volumeUuid) {
        this.volumeUuid = TextUtils.safeIntern(volumeUuid);
        return this;
    }

    @Override
    public ParsingPackageImpl setStaticSharedLibName(String staticSharedLibName) {
        this.staticSharedLibName = TextUtils.safeIntern(staticSharedLibName);
        return this;
    }

    @Override
    public ParsingPackageImpl setSharedUserId(String sharedUserId) {
        this.sharedUserId = TextUtils.safeIntern(sharedUserId);
        return this;
    }

    @NonNull
    @Override
    public String getProcessName() {
        return processName != null ? processName : packageName;
    }

    @Override
    public String toString() {
        return "Package{"
                + Integer.toHexString(System.identityHashCode(this))
                + " " + packageName + "}";
    }

    @Deprecated
    @Override
    public ApplicationInfo toAppInfoWithoutState() {
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.flags = PackageInfoWithoutStateUtils.appInfoFlags(this);
        appInfo.privateFlags = PackageInfoWithoutStateUtils.appInfoPrivateFlags(this);

        appInfo.appComponentFactory = appComponentFactory;
        appInfo.backupAgentName = backupAgentName;
        appInfo.banner = banner;
        appInfo.category = category;
        appInfo.classLoaderName = classLoaderName;
        appInfo.className = className;
        appInfo.compatibleWidthLimitDp = compatibleWidthLimitDp;
        appInfo.compileSdkVersion = compileSdkVersion;
        appInfo.compileSdkVersionCodename = compileSdkVersionCodeName;
//        appInfo.credentialProtectedDataDir = credentialProtectedDataDir;
//        appInfo.dataDir = dataDir;
        appInfo.descriptionRes = descriptionRes;
//        appInfo.deviceProtectedDataDir = deviceProtectedDataDir;
        appInfo.enabled = enabled;
        appInfo.fullBackupContent = fullBackupContent;
//        appInfo.hiddenUntilInstalled = hiddenUntilInstalled;
        appInfo.icon = (PackageParser.sUseRoundIcon && roundIconRes != 0) ? roundIconRes : iconRes;
        appInfo.iconRes = iconRes;
        appInfo.roundIconRes = roundIconRes;
        appInfo.installLocation = installLocation;
        appInfo.labelRes = labelRes;
        appInfo.largestWidthLimitDp = largestWidthLimitDp;
        appInfo.logo = logo;
        appInfo.manageSpaceActivityName = manageSpaceActivityName;
        appInfo.maxAspectRatio = maxAspectRatio;
        appInfo.metaData = metaData;
        appInfo.minAspectRatio = minAspectRatio;
        appInfo.minSdkVersion = minSdkVersion;
        appInfo.name = className;
        if (appInfo.name != null) {
            appInfo.name = appInfo.name.trim();
        }
//        appInfo.nativeLibraryDir = nativeLibraryDir;
//        appInfo.nativeLibraryRootDir = nativeLibraryRootDir;
//        appInfo.nativeLibraryRootRequiresIsa = nativeLibraryRootRequiresIsa;
        appInfo.networkSecurityConfigRes = networkSecurityConfigRes;
        appInfo.nonLocalizedLabel = nonLocalizedLabel;
        if (appInfo.nonLocalizedLabel != null) {
            appInfo.nonLocalizedLabel = appInfo.nonLocalizedLabel.toString().trim();
        }
        appInfo.packageName = packageName;
        appInfo.permission = permission;
//        appInfo.primaryCpuAbi = primaryCpuAbi;
        appInfo.processName = getProcessName();
        appInfo.requiresSmallestWidthDp = requiresSmallestWidthDp;
//        appInfo.secondaryCpuAbi = secondaryCpuAbi;
//        appInfo.secondaryNativeLibraryDir = secondaryNativeLibraryDir;
//        appInfo.seInfo = seInfo;
//        appInfo.seInfoUser = seInfoUser;
//        appInfo.sharedLibraryFiles = usesLibraryFiles.isEmpty()
//                ? null : usesLibraryFiles.toArray(new String[0]);
//        appInfo.sharedLibraryInfos = usesLibraryInfos.isEmpty() ? null : usesLibraryInfos;
        appInfo.splitClassLoaderNames = splitClassLoaderNames;
        appInfo.splitDependencies = splitDependencies;
        appInfo.splitNames = splitNames;
        appInfo.storageUuid = StorageManager.convert(volumeUuid);
        appInfo.targetSandboxVersion = targetSandboxVersion;
        appInfo.targetSdkVersion = targetSdkVersion;
        appInfo.taskAffinity = taskAffinity;
        appInfo.theme = theme;
//        appInfo.uid = uid;
        appInfo.uiOptions = uiOptions;
        appInfo.volumeUuid = volumeUuid;
        appInfo.zygotePreloadName = zygotePreloadName;
        appInfo.crossProfile = isCrossProfile();
        appInfo.setGwpAsanMode(gwpAsanMode);
        appInfo.setBaseCodePath(baseCodePath);
        appInfo.setBaseResourcePath(baseCodePath);
        appInfo.setCodePath(codePath);
        appInfo.setResourcePath(codePath);
        appInfo.setSplitCodePaths(splitCodePaths);
        appInfo.setSplitResourcePaths(splitCodePaths);
        appInfo.setVersionCode(PackageInfo.composeLongVersionCode(versionCodeMajor, versionCode));

        // TODO(b/135203078): Can this be removed? Looks only used in ActivityInfo.
//        appInfo.showUserIcon = pkg.getShowUserIcon();
        // TODO(b/135203078): Unused?
//        appInfo.resourceDirs = pkg.getResourceDirs();
        // TODO(b/135203078): Unused?
//        appInfo.enabledSetting = pkg.getEnabledSetting();
        // TODO(b/135203078): See ParsingPackageImpl#getHiddenApiEnforcementPolicy
//        appInfo.mHiddenApiPolicy = pkg.getHiddenApiPolicy();

        return appInfo;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        sForBoolean.parcel(this.supportsSmallScreens, dest, flags);
        sForBoolean.parcel(this.supportsNormalScreens, dest, flags);
        sForBoolean.parcel(this.supportsLargeScreens, dest, flags);
        sForBoolean.parcel(this.supportsExtraLargeScreens, dest, flags);
        sForBoolean.parcel(this.resizeable, dest, flags);
        sForBoolean.parcel(this.anyDensity, dest, flags);
        dest.writeInt(this.versionCode);
        dest.writeInt(this.versionCodeMajor);
        dest.writeInt(this.baseRevisionCode);
        sForInternedString.parcel(this.versionName, dest, flags);
        dest.writeInt(this.compileSdkVersion);
        dest.writeString(this.compileSdkVersionCodeName);
        sForInternedString.parcel(this.packageName, dest, flags);
        dest.writeString(this.realPackage);
        dest.writeString(this.baseCodePath);
        dest.writeBoolean(this.requiredForAllUsers);
        dest.writeString(this.restrictedAccountType);
        dest.writeString(this.requiredAccountType);
        sForInternedString.parcel(this.overlayTarget, dest, flags);
        dest.writeString(this.overlayTargetName);
        dest.writeString(this.overlayCategory);
        dest.writeInt(this.overlayPriority);
        dest.writeBoolean(this.overlayIsStatic);
        sForInternedStringValueMap.parcel(this.overlayables, dest, flags);
        sForInternedString.parcel(this.staticSharedLibName, dest, flags);
        dest.writeLong(this.staticSharedLibVersion);
        sForInternedStringList.parcel(this.libraryNames, dest, flags);
        sForInternedStringList.parcel(this.usesLibraries, dest, flags);
        sForInternedStringList.parcel(this.usesOptionalLibraries, dest, flags);
        sForInternedStringList.parcel(this.usesStaticLibraries, dest, flags);
        dest.writeLongArray(this.usesStaticLibrariesVersions);

        if (this.usesStaticLibrariesCertDigests == null) {
            dest.writeInt(-1);
        } else {
            dest.writeInt(this.usesStaticLibrariesCertDigests.length);
            for (int index = 0; index < this.usesStaticLibrariesCertDigests.length; index++) {
                dest.writeStringArray(this.usesStaticLibrariesCertDigests[index]);
            }
        }

        sForInternedString.parcel(this.sharedUserId, dest, flags);
        dest.writeInt(this.sharedUserLabel);
        dest.writeTypedList(this.configPreferences);
        dest.writeTypedList(this.reqFeatures);
        dest.writeTypedList(this.featureGroups);
        dest.writeByteArray(this.restrictUpdateHash);
        dest.writeStringList(this.originalPackages);
        sForInternedStringList.parcel(this.adoptPermissions, dest, flags);
        sForInternedStringList.parcel(this.requestedPermissions, dest, flags);
        sForInternedStringList.parcel(this.implicitPermissions, dest, flags);
        sForStringSet.parcel(this.upgradeKeySets, dest, flags);
        dest.writeMap(this.keySetMapping);
        sForInternedStringList.parcel(this.protectedBroadcasts, dest, flags);
        dest.writeTypedList(this.activities);
        dest.writeTypedList(this.receivers);
        dest.writeTypedList(this.services);
        dest.writeTypedList(this.providers);
        dest.writeTypedList(this.attributions);
        dest.writeTypedList(this.permissions);
        dest.writeTypedList(this.permissionGroups);
        dest.writeTypedList(this.instrumentations);
        sForIntentInfoPairs.parcel(this.preferredActivityFilters, dest, flags);
        dest.writeMap(this.processes);
        dest.writeBundle(this.metaData);
        sForInternedString.parcel(this.volumeUuid, dest, flags);
        dest.writeParcelable(this.signingDetails, flags);
        dest.writeString(this.codePath);
        dest.writeBoolean(this.use32BitAbi);
        dest.writeBoolean(this.visibleToInstantApps);
        dest.writeBoolean(this.forceQueryable);
        dest.writeParcelableList(this.queriesIntents, flags);
        sForInternedStringList.parcel(this.queriesPackages, dest, flags);
        dest.writeString(this.appComponentFactory);
        dest.writeString(this.backupAgentName);
        dest.writeInt(this.banner);
        dest.writeInt(this.category);
        dest.writeString(this.classLoaderName);
        dest.writeString(this.className);
        dest.writeInt(this.compatibleWidthLimitDp);
        dest.writeInt(this.descriptionRes);
        dest.writeBoolean(this.enabled);
        dest.writeBoolean(this.crossProfile);
        dest.writeInt(this.fullBackupContent);
        dest.writeInt(this.iconRes);
        dest.writeInt(this.installLocation);
        dest.writeInt(this.labelRes);
        dest.writeInt(this.largestWidthLimitDp);
        dest.writeInt(this.logo);
        dest.writeString(this.manageSpaceActivityName);
        dest.writeFloat(this.maxAspectRatio);
        dest.writeFloat(this.minAspectRatio);
        dest.writeInt(this.minSdkVersion);
        dest.writeInt(this.networkSecurityConfigRes);
        dest.writeCharSequence(this.nonLocalizedLabel);
        dest.writeString(this.permission);
        dest.writeString(this.processName);
        dest.writeInt(this.requiresSmallestWidthDp);
        dest.writeInt(this.roundIconRes);
        dest.writeInt(this.targetSandboxVersion);
        dest.writeInt(this.targetSdkVersion);
        dest.writeString(this.taskAffinity);
        dest.writeInt(this.theme);
        dest.writeInt(this.uiOptions);
        dest.writeString(this.zygotePreloadName);
        dest.writeStringArray(this.splitClassLoaderNames);
        dest.writeStringArray(this.splitCodePaths);
        dest.writeSparseArray(this.splitDependencies);
        dest.writeIntArray(this.splitFlags);
        dest.writeStringArray(this.splitNames);
        dest.writeIntArray(this.splitRevisionCodes);

        dest.writeBoolean(this.externalStorage);
        dest.writeBoolean(this.baseHardwareAccelerated);
        dest.writeBoolean(this.allowBackup);
        dest.writeBoolean(this.killAfterRestore);
        dest.writeBoolean(this.restoreAnyVersion);
        dest.writeBoolean(this.fullBackupOnly);
        dest.writeBoolean(this.persistent);
        dest.writeBoolean(this.debuggable);
        dest.writeBoolean(this.vmSafeMode);
        dest.writeBoolean(this.hasCode);
        dest.writeBoolean(this.allowTaskReparenting);
        dest.writeBoolean(this.allowClearUserData);
        dest.writeBoolean(this.largeHeap);
        dest.writeBoolean(this.usesCleartextTraffic);
        dest.writeBoolean(this.supportsRtl);
        dest.writeBoolean(this.testOnly);
        dest.writeBoolean(this.multiArch);
        dest.writeBoolean(this.extractNativeLibs);
        dest.writeBoolean(this.game);

        sForBoolean.parcel(this.resizeableActivity, dest, flags);

        dest.writeBoolean(this.staticSharedLibrary);
        dest.writeBoolean(this.overlay);
        dest.writeBoolean(this.isolatedSplitLoading);
        dest.writeBoolean(this.hasDomainUrls);
        dest.writeBoolean(this.profileableByShell);
        dest.writeBoolean(this.backupInForeground);
        dest.writeBoolean(this.useEmbeddedDex);
        dest.writeBoolean(this.defaultToDeviceProtectedStorage);
        dest.writeBoolean(this.directBootAware);
        dest.writeBoolean(this.partiallyDirectBootAware);
        dest.writeBoolean(this.resizeableActivityViaSdkVersion);
        dest.writeBoolean(this.allowClearUserDataOnFailedRestore);
        dest.writeBoolean(this.allowAudioPlaybackCapture);
        dest.writeBoolean(this.requestLegacyExternalStorage);
        dest.writeBoolean(this.usesNonSdkApi);
        dest.writeBoolean(this.hasFragileUserData);
        dest.writeBoolean(this.cantSaveState);
        dest.writeBoolean(this.allowNativeHeapPointerTagging);
        dest.writeInt(this.autoRevokePermissions);
        dest.writeBoolean(this.preserveLegacyExternalStorage);
        dest.writeArraySet(this.mimeGroups);
        dest.writeInt(this.gwpAsanMode);
    }

    public ParsingPackageImpl(Parcel in) {
        // We use the boot classloader for all classes that we load.
        final ClassLoader boot = Object.class.getClassLoader();
        this.supportsSmallScreens = sForBoolean.unparcel(in);
        this.supportsNormalScreens = sForBoolean.unparcel(in);
        this.supportsLargeScreens = sForBoolean.unparcel(in);
        this.supportsExtraLargeScreens = sForBoolean.unparcel(in);
        this.resizeable = sForBoolean.unparcel(in);
        this.anyDensity = sForBoolean.unparcel(in);
        this.versionCode = in.readInt();
        this.versionCodeMajor = in.readInt();
        this.baseRevisionCode = in.readInt();
        this.versionName = sForInternedString.unparcel(in);
        this.compileSdkVersion = in.readInt();
        this.compileSdkVersionCodeName = in.readString();
        this.packageName = sForInternedString.unparcel(in);
        this.realPackage = in.readString();
        this.baseCodePath = in.readString();
        this.requiredForAllUsers = in.readBoolean();
        this.restrictedAccountType = in.readString();
        this.requiredAccountType = in.readString();
        this.overlayTarget = sForInternedString.unparcel(in);
        this.overlayTargetName = in.readString();
        this.overlayCategory = in.readString();
        this.overlayPriority = in.readInt();
        this.overlayIsStatic = in.readBoolean();
        this.overlayables = sForInternedStringValueMap.unparcel(in);
        this.staticSharedLibName = sForInternedString.unparcel(in);
        this.staticSharedLibVersion = in.readLong();
        this.libraryNames = sForInternedStringList.unparcel(in);
        this.usesLibraries = sForInternedStringList.unparcel(in);
        this.usesOptionalLibraries = sForInternedStringList.unparcel(in);
        this.usesStaticLibraries = sForInternedStringList.unparcel(in);
        this.usesStaticLibrariesVersions = in.createLongArray();

        int digestsSize = in.readInt();
        if (digestsSize >= 0) {
            this.usesStaticLibrariesCertDigests = new String[digestsSize][];
            for (int index = 0; index < digestsSize; index++) {
                this.usesStaticLibrariesCertDigests[index] = sForInternedStringArray.unparcel(in);
            }
        }

        this.sharedUserId = sForInternedString.unparcel(in);
        this.sharedUserLabel = in.readInt();
        this.configPreferences = in.createTypedArrayList(ConfigurationInfo.CREATOR);
        this.reqFeatures = in.createTypedArrayList(FeatureInfo.CREATOR);
        this.featureGroups = in.createTypedArrayList(FeatureGroupInfo.CREATOR);
        this.restrictUpdateHash = in.createByteArray();
        this.originalPackages = in.createStringArrayList();
        this.adoptPermissions = sForInternedStringList.unparcel(in);
        this.requestedPermissions = sForInternedStringList.unparcel(in);
        this.implicitPermissions = sForInternedStringList.unparcel(in);
        this.upgradeKeySets = sForStringSet.unparcel(in);
        this.keySetMapping = in.readHashMap(boot);
        this.protectedBroadcasts = sForInternedStringList.unparcel(in);

        this.activities = in.createTypedArrayList(ParsedActivity.CREATOR);
        this.receivers = in.createTypedArrayList(ParsedActivity.CREATOR);
        this.services = in.createTypedArrayList(ParsedService.CREATOR);
        this.providers = in.createTypedArrayList(ParsedProvider.CREATOR);
        this.attributions = in.createTypedArrayList(ParsedAttribution.CREATOR);
        this.permissions = in.createTypedArrayList(ParsedPermission.CREATOR);
        this.permissionGroups = in.createTypedArrayList(ParsedPermissionGroup.CREATOR);
        this.instrumentations = in.createTypedArrayList(ParsedInstrumentation.CREATOR);
        this.preferredActivityFilters = sForIntentInfoPairs.unparcel(in);
        this.processes = in.readHashMap(boot);
        this.metaData = in.readBundle(boot);
        this.volumeUuid = sForInternedString.unparcel(in);
        this.signingDetails = in.readParcelable(boot);
        this.codePath = in.readString();
        this.use32BitAbi = in.readBoolean();
        this.visibleToInstantApps = in.readBoolean();
        this.forceQueryable = in.readBoolean();
        this.queriesIntents = in.createTypedArrayList(Intent.CREATOR);
        this.queriesPackages = sForInternedStringList.unparcel(in);
        this.appComponentFactory = in.readString();
        this.backupAgentName = in.readString();
        this.banner = in.readInt();
        this.category = in.readInt();
        this.classLoaderName = in.readString();
        this.className = in.readString();
        this.compatibleWidthLimitDp = in.readInt();
        this.descriptionRes = in.readInt();
        this.enabled = in.readBoolean();
        this.crossProfile = in.readBoolean();
        this.fullBackupContent = in.readInt();
        this.iconRes = in.readInt();
        this.installLocation = in.readInt();
        this.labelRes = in.readInt();
        this.largestWidthLimitDp = in.readInt();
        this.logo = in.readInt();
        this.manageSpaceActivityName = in.readString();
        this.maxAspectRatio = in.readFloat();
        this.minAspectRatio = in.readFloat();
        this.minSdkVersion = in.readInt();
        this.networkSecurityConfigRes = in.readInt();
        this.nonLocalizedLabel = in.readCharSequence();
        this.permission = in.readString();
        this.processName = in.readString();
        this.requiresSmallestWidthDp = in.readInt();
        this.roundIconRes = in.readInt();
        this.targetSandboxVersion = in.readInt();
        this.targetSdkVersion = in.readInt();
        this.taskAffinity = in.readString();
        this.theme = in.readInt();
        this.uiOptions = in.readInt();
        this.zygotePreloadName = in.readString();
        this.splitClassLoaderNames = in.createStringArray();
        this.splitCodePaths = in.createStringArray();
        this.splitDependencies = in.readSparseArray(boot);
        this.splitFlags = in.createIntArray();
        this.splitNames = in.createStringArray();
        this.splitRevisionCodes = in.createIntArray();
        this.externalStorage = in.readBoolean();
        this.baseHardwareAccelerated = in.readBoolean();
        this.allowBackup = in.readBoolean();
        this.killAfterRestore = in.readBoolean();
        this.restoreAnyVersion = in.readBoolean();
        this.fullBackupOnly = in.readBoolean();
        this.persistent = in.readBoolean();
        this.debuggable = in.readBoolean();
        this.vmSafeMode = in.readBoolean();
        this.hasCode = in.readBoolean();
        this.allowTaskReparenting = in.readBoolean();
        this.allowClearUserData = in.readBoolean();
        this.largeHeap = in.readBoolean();
        this.usesCleartextTraffic = in.readBoolean();
        this.supportsRtl = in.readBoolean();
        this.testOnly = in.readBoolean();
        this.multiArch = in.readBoolean();
        this.extractNativeLibs = in.readBoolean();
        this.game = in.readBoolean();
        this.resizeableActivity = sForBoolean.unparcel(in);

        this.staticSharedLibrary = in.readBoolean();
        this.overlay = in.readBoolean();
        this.isolatedSplitLoading = in.readBoolean();
        this.hasDomainUrls = in.readBoolean();
        this.profileableByShell = in.readBoolean();
        this.backupInForeground = in.readBoolean();
        this.useEmbeddedDex = in.readBoolean();
        this.defaultToDeviceProtectedStorage = in.readBoolean();
        this.directBootAware = in.readBoolean();
        this.partiallyDirectBootAware = in.readBoolean();
        this.resizeableActivityViaSdkVersion = in.readBoolean();
        this.allowClearUserDataOnFailedRestore = in.readBoolean();
        this.allowAudioPlaybackCapture = in.readBoolean();
        this.requestLegacyExternalStorage = in.readBoolean();
        this.usesNonSdkApi = in.readBoolean();
        this.hasFragileUserData = in.readBoolean();
        this.cantSaveState = in.readBoolean();
        this.allowNativeHeapPointerTagging = in.readBoolean();
        this.autoRevokePermissions = in.readInt();
        this.preserveLegacyExternalStorage = in.readBoolean();
        this.mimeGroups = (ArraySet<String>) in.readArraySet(boot);
        this.gwpAsanMode = in.readInt();
    }

    public static final Parcelable.Creator<ParsingPackageImpl> CREATOR =
            new Parcelable.Creator<ParsingPackageImpl>() {
                @Override
                public ParsingPackageImpl createFromParcel(Parcel source) {
                    return new ParsingPackageImpl(source);
                }

                @Override
                public ParsingPackageImpl[] newArray(int size) {
                    return new ParsingPackageImpl[size];
                }
            };

    @Override
    public int getVersionCode() {
        return versionCode;
    }

    @Override
    public int getVersionCodeMajor() {
        return versionCodeMajor;
    }

    @Override
    public int getBaseRevisionCode() {
        return baseRevisionCode;
    }

    @Nullable
    @Override
    public String getVersionName() {
        return versionName;
    }

    @Override
    public int getCompileSdkVersion() {
        return compileSdkVersion;
    }

    @Nullable
    @Override
    public String getCompileSdkVersionCodeName() {
        return compileSdkVersionCodeName;
    }

    @NonNull
    @Override
    public String getPackageName() {
        return packageName;
    }

    @Nullable
    @Override
    public String getRealPackage() {
        return realPackage;
    }

    @NonNull
    @Override
    public String getBaseCodePath() {
        return baseCodePath;
    }

    @Override
    public boolean isRequiredForAllUsers() {
        return requiredForAllUsers;
    }

    @Nullable
    @Override
    public String getRestrictedAccountType() {
        return restrictedAccountType;
    }

    @Nullable
    @Override
    public String getRequiredAccountType() {
        return requiredAccountType;
    }

    @Nullable
    @Override
    public String getOverlayTarget() {
        return overlayTarget;
    }

    @Nullable
    @Override
    public String getOverlayTargetName() {
        return overlayTargetName;
    }

    @Nullable
    @Override
    public String getOverlayCategory() {
        return overlayCategory;
    }

    @Override
    public int getOverlayPriority() {
        return overlayPriority;
    }

    @Override
    public boolean isOverlayIsStatic() {
        return overlayIsStatic;
    }

    @NonNull
    @Override
    public Map<String,String> getOverlayables() {
        return overlayables;
    }

    @Nullable
    @Override
    public String getStaticSharedLibName() {
        return staticSharedLibName;
    }

    @Override
    public long getStaticSharedLibVersion() {
        return staticSharedLibVersion;
    }

    @NonNull
    @Override
    public List<String> getLibraryNames() {
        return libraryNames;
    }

    @NonNull
    @Override
    public List<String> getUsesLibraries() {
        return usesLibraries;
    }

    @NonNull
    @Override
    public List<String> getUsesOptionalLibraries() {
        return usesOptionalLibraries;
    }

    @NonNull
    @Override
    public List<String> getUsesStaticLibraries() {
        return usesStaticLibraries;
    }

    @Nullable
    @Override
    public long[] getUsesStaticLibrariesVersions() {
        return usesStaticLibrariesVersions;
    }

    @Nullable
    @Override
    public String[][] getUsesStaticLibrariesCertDigests() {
        return usesStaticLibrariesCertDigests;
    }

    @Nullable
    @Override
    public String getSharedUserId() {
        return sharedUserId;
    }

    @Override
    public int getSharedUserLabel() {
        return sharedUserLabel;
    }

    @NonNull
    @Override
    public List<ConfigurationInfo> getConfigPreferences() {
        return configPreferences;
    }

    @NonNull
    @Override
    public List<FeatureInfo> getReqFeatures() {
        return reqFeatures;
    }

    @NonNull
    @Override
    public List<FeatureGroupInfo> getFeatureGroups() {
        return featureGroups;
    }

    @Nullable
    @Override
    public byte[] getRestrictUpdateHash() {
        return restrictUpdateHash;
    }

    @NonNull
    @Override
    public List<String> getOriginalPackages() {
        return originalPackages;
    }

    @NonNull
    @Override
    public List<String> getAdoptPermissions() {
        return adoptPermissions;
    }

    @NonNull
    @Override
    public List<String> getRequestedPermissions() {
        return requestedPermissions;
    }

    @NonNull
    @Override
    public List<String> getImplicitPermissions() {
        return implicitPermissions;
    }

    @NonNull
    @Override
    public Set<String> getUpgradeKeySets() {
        return upgradeKeySets;
    }

    @NonNull
    @Override
    public Map<String,ArraySet<PublicKey>> getKeySetMapping() {
        return keySetMapping;
    }

    @NonNull
    @Override
    public List<String> getProtectedBroadcasts() {
        return protectedBroadcasts;
    }

    @NonNull
    @Override
    public List<ParsedActivity> getActivities() {
        return activities;
    }

    @NonNull
    @Override
    public List<ParsedActivity> getReceivers() {
        return receivers;
    }

    @NonNull
    @Override
    public List<ParsedService> getServices() {
        return services;
    }

    @NonNull
    @Override
    public List<ParsedProvider> getProviders() {
        return providers;
    }

    @NonNull
    @Override
    public List<ParsedAttribution> getAttributions() {
        return attributions;
    }

    @NonNull
    @Override
    public List<ParsedPermission> getPermissions() {
        return permissions;
    }

    @NonNull
    @Override
    public List<ParsedPermissionGroup> getPermissionGroups() {
        return permissionGroups;
    }

    @NonNull
    @Override
    public List<ParsedInstrumentation> getInstrumentations() {
        return instrumentations;
    }

    @NonNull
    @Override
    public List<Pair<String,ParsedIntentInfo>> getPreferredActivityFilters() {
        return preferredActivityFilters;
    }

    @NonNull
    @Override
    public Map<String,ParsedProcess> getProcesses() {
        return processes;
    }

    @Nullable
    @Override
    public Bundle getMetaData() {
        return metaData;
    }

    private void addMimeGroupsFromComponent(ParsedComponent component) {
        for (int i = component.getIntents().size() - 1; i >= 0; i--) {
            IntentFilter filter = component.getIntents().get(i);
            for (int groupIndex = filter.countMimeGroups() - 1; groupIndex >= 0; groupIndex--) {
                mimeGroups = ArrayUtils.add(mimeGroups, filter.getMimeGroup(groupIndex));
            }
        }
    }

    @Override
    @Nullable
    public Set<String> getMimeGroups() {
        return mimeGroups;
    }

    @Nullable
    @Override
    public String getVolumeUuid() {
        return volumeUuid;
    }

    @Nullable
    @Override
    public PackageParser.SigningDetails getSigningDetails() {
        return signingDetails;
    }

    @NonNull
    @Override
    public String getCodePath() {
        return codePath;
    }

    @Override
    public boolean isUse32BitAbi() {
        return use32BitAbi;
    }

    @Override
    public boolean isVisibleToInstantApps() {
        return visibleToInstantApps;
    }

    @Override
    public boolean isForceQueryable() {
        return forceQueryable;
    }

    @NonNull
    @Override
    public List<Intent> getQueriesIntents() {
        return queriesIntents;
    }

    @NonNull
    @Override
    public List<String> getQueriesPackages() {
        return queriesPackages;
    }

    @NonNull
    @Override
    public Set<String> getQueriesProviders() {
        return queriesProviders;
    }

    @Nullable
    @Override
    public String[] getSplitClassLoaderNames() {
        return splitClassLoaderNames;
    }

    @Nullable
    @Override
    public String[] getSplitCodePaths() {
        return splitCodePaths;
    }

    @Nullable
    @Override
    public SparseArray<int[]> getSplitDependencies() {
        return splitDependencies;
    }

    @Nullable
    @Override
    public int[] getSplitFlags() {
        return splitFlags;
    }

    @Nullable
    @Override
    public String[] getSplitNames() {
        return splitNames;
    }

    @Nullable
    @Override
    public int[] getSplitRevisionCodes() {
        return splitRevisionCodes;
    }

    @Nullable
    @Override
    public String getAppComponentFactory() {
        return appComponentFactory;
    }

    @Nullable
    @Override
    public String getBackupAgentName() {
        return backupAgentName;
    }

    @Override
    public int getBanner() {
        return banner;
    }

    @Override
    public int getCategory() {
        return category;
    }

    @Nullable
    @Override
    public String getClassLoaderName() {
        return classLoaderName;
    }

    @Nullable
    @Override
    public String getClassName() {
        return className;
    }

    @Override
    public int getCompatibleWidthLimitDp() {
        return compatibleWidthLimitDp;
    }

    @Override
    public int getDescriptionRes() {
        return descriptionRes;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean isCrossProfile() {
        return crossProfile;
    }

    @Override
    public int getFullBackupContent() {
        return fullBackupContent;
    }

    @Override
    public int getIconRes() {
        return iconRes;
    }

    @Override
    public int getInstallLocation() {
        return installLocation;
    }

    @Override
    public int getLabelRes() {
        return labelRes;
    }

    @Override
    public int getLargestWidthLimitDp() {
        return largestWidthLimitDp;
    }

    @Override
    public int getLogo() {
        return logo;
    }

    @Nullable
    @Override
    public String getManageSpaceActivityName() {
        return manageSpaceActivityName;
    }

    @Override
    public float getMaxAspectRatio() {
        return maxAspectRatio;
    }

    @Override
    public float getMinAspectRatio() {
        return minAspectRatio;
    }

    @Override
    public int getMinSdkVersion() {
        return minSdkVersion;
    }

    @Override
    public int getNetworkSecurityConfigRes() {
        return networkSecurityConfigRes;
    }

    @Nullable
    @Override
    public CharSequence getNonLocalizedLabel() {
        return nonLocalizedLabel;
    }

    @Nullable
    @Override
    public String getPermission() {
        return permission;
    }

    @Override
    public int getRequiresSmallestWidthDp() {
        return requiresSmallestWidthDp;
    }

    @Override
    public int getRoundIconRes() {
        return roundIconRes;
    }

    @Override
    public int getTargetSandboxVersion() {
        return targetSandboxVersion;
    }

    @Override
    public int getTargetSdkVersion() {
        return targetSdkVersion;
    }

    @Nullable
    @Override
    public String getTaskAffinity() {
        return taskAffinity;
    }

    @Override
    public int getTheme() {
        return theme;
    }

    @Override
    public int getUiOptions() {
        return uiOptions;
    }

    @Nullable
    @Override
    public String getZygotePreloadName() {
        return zygotePreloadName;
    }

    @Override
    public boolean isExternalStorage() {
        return externalStorage;
    }

    @Override
    public boolean isBaseHardwareAccelerated() {
        return baseHardwareAccelerated;
    }

    @Override
    public boolean isAllowBackup() {
        return allowBackup;
    }

    @Override
    public boolean isKillAfterRestore() {
        return killAfterRestore;
    }

    @Override
    public boolean isRestoreAnyVersion() {
        return restoreAnyVersion;
    }

    @Override
    public boolean isFullBackupOnly() {
        return fullBackupOnly;
    }

    @Override
    public boolean isPersistent() {
        return persistent;
    }

    @Override
    public boolean isDebuggable() {
        return debuggable;
    }

    @Override
    public boolean isVmSafeMode() {
        return vmSafeMode;
    }

    @Override
    public boolean isHasCode() {
        return hasCode;
    }

    @Override
    public boolean isAllowTaskReparenting() {
        return allowTaskReparenting;
    }

    @Override
    public boolean isAllowClearUserData() {
        return allowClearUserData;
    }

    @Override
    public boolean isLargeHeap() {
        return largeHeap;
    }

    @Override
    public boolean isUsesCleartextTraffic() {
        return usesCleartextTraffic;
    }

    @Override
    public boolean isSupportsRtl() {
        return supportsRtl;
    }

    @Override
    public boolean isTestOnly() {
        return testOnly;
    }

    @Override
    public boolean isMultiArch() {
        return multiArch;
    }

    @Override
    public boolean isExtractNativeLibs() {
        return extractNativeLibs;
    }

    @Override
    public boolean isGame() {
        return game;
    }

    /**
     * @see ParsingPackageRead#getResizeableActivity()
     */
    @Nullable
    @Override
    public Boolean getResizeableActivity() {
        return resizeableActivity;
    }

    @Override
    public boolean isStaticSharedLibrary() {
        return staticSharedLibrary;
    }

    @Override
    public boolean isOverlay() {
        return overlay;
    }

    @Override
    public boolean isIsolatedSplitLoading() {
        return isolatedSplitLoading;
    }

    @Override
    public boolean isHasDomainUrls() {
        return hasDomainUrls;
    }

    @Override
    public boolean isProfileableByShell() {
        return profileableByShell;
    }

    @Override
    public boolean isBackupInForeground() {
        return backupInForeground;
    }

    @Override
    public boolean isUseEmbeddedDex() {
        return useEmbeddedDex;
    }

    @Override
    public boolean isDefaultToDeviceProtectedStorage() {
        return defaultToDeviceProtectedStorage;
    }

    @Override
    public boolean isDirectBootAware() {
        return directBootAware;
    }

    @Override
    public int getGwpAsanMode() {
        return gwpAsanMode;
    }

    @Override
    public boolean isPartiallyDirectBootAware() {
        return partiallyDirectBootAware;
    }

    @Override
    public boolean isResizeableActivityViaSdkVersion() {
        return resizeableActivityViaSdkVersion;
    }

    @Override
    public boolean isAllowClearUserDataOnFailedRestore() {
        return allowClearUserDataOnFailedRestore;
    }

    @Override
    public boolean isAllowAudioPlaybackCapture() {
        return allowAudioPlaybackCapture;
    }

    @Override
    public boolean isRequestLegacyExternalStorage() {
        return requestLegacyExternalStorage;
    }

    @Override
    public boolean isUsesNonSdkApi() {
        return usesNonSdkApi;
    }

    @Override
    public boolean isHasFragileUserData() {
        return hasFragileUserData;
    }

    @Override
    public boolean isCantSaveState() {
        return cantSaveState;
    }

    @Override
    public boolean isAllowNativeHeapPointerTagging() {
        return allowNativeHeapPointerTagging;
    }

    @Override
    public int getAutoRevokePermissions() {
        return autoRevokePermissions;
    }

    @Override
    public boolean hasPreserveLegacyExternalStorage() {
        return preserveLegacyExternalStorage;
    }

    @Override
    public ParsingPackageImpl setBaseRevisionCode(int value) {
        baseRevisionCode = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setCompileSdkVersion(int value) {
        compileSdkVersion = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setRequiredForAllUsers(boolean value) {
        requiredForAllUsers = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setOverlayPriority(int value) {
        overlayPriority = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setOverlayIsStatic(boolean value) {
        overlayIsStatic = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setStaticSharedLibVersion(long value) {
        staticSharedLibVersion = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setSharedUserLabel(int value) {
        sharedUserLabel = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setRestrictUpdateHash(@Nullable byte... value) {
        restrictUpdateHash = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setUpgradeKeySets(@NonNull Set<String> value) {
        upgradeKeySets = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setProcesses(@NonNull Map<String,ParsedProcess> value) {
        processes = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setMetaData(@Nullable Bundle value) {
        metaData = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setSigningDetails(@Nullable PackageParser.SigningDetails value) {
        signingDetails = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setUse32BitAbi(boolean value) {
        use32BitAbi = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setVisibleToInstantApps(boolean value) {
        visibleToInstantApps = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setForceQueryable(boolean value) {
        forceQueryable = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setBanner(int value) {
        banner = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setCategory(int value) {
        category = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setCompatibleWidthLimitDp(int value) {
        compatibleWidthLimitDp = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setDescriptionRes(int value) {
        descriptionRes = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setEnabled(boolean value) {
        enabled = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setCrossProfile(boolean value) {
        crossProfile = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setFullBackupContent(int value) {
        fullBackupContent = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setIconRes(int value) {
        iconRes = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setInstallLocation(int value) {
        installLocation = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setLabelRes(int value) {
        labelRes = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setLargestWidthLimitDp(int value) {
        largestWidthLimitDp = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setLogo(int value) {
        logo = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setMaxAspectRatio(float value) {
        maxAspectRatio = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setMinAspectRatio(float value) {
        minAspectRatio = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setMinSdkVersion(int value) {
        minSdkVersion = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setNetworkSecurityConfigRes(int value) {
        networkSecurityConfigRes = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setNonLocalizedLabel(@Nullable CharSequence value) {
        nonLocalizedLabel = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setRequiresSmallestWidthDp(int value) {
        requiresSmallestWidthDp = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setRoundIconRes(int value) {
        roundIconRes = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setTargetSandboxVersion(int value) {
        targetSandboxVersion = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setTargetSdkVersion(int value) {
        targetSdkVersion = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setTheme(int value) {
        theme = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setUiOptions(int value) {
        uiOptions = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setExternalStorage(boolean value) {
        externalStorage = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setBaseHardwareAccelerated(boolean value) {
        baseHardwareAccelerated = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setAllowBackup(boolean value) {
        allowBackup = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setKillAfterRestore(boolean value) {
        killAfterRestore = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setRestoreAnyVersion(boolean value) {
        restoreAnyVersion = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setFullBackupOnly(boolean value) {
        fullBackupOnly = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setPersistent(boolean value) {
        persistent = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setDebuggable(boolean value) {
        debuggable = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setVmSafeMode(boolean value) {
        vmSafeMode = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setHasCode(boolean value) {
        hasCode = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setAllowTaskReparenting(boolean value) {
        allowTaskReparenting = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setAllowClearUserData(boolean value) {
        allowClearUserData = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setLargeHeap(boolean value) {
        largeHeap = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setUsesCleartextTraffic(boolean value) {
        usesCleartextTraffic = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setSupportsRtl(boolean value) {
        supportsRtl = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setTestOnly(boolean value) {
        testOnly = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setMultiArch(boolean value) {
        multiArch = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setExtractNativeLibs(boolean value) {
        extractNativeLibs = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setGame(boolean value) {
        game = value;
        return this;
    }

    /**
     * @see ParsingPackageRead#getResizeableActivity()
     */
    @Override
    public ParsingPackageImpl setResizeableActivity(@Nullable Boolean value) {
        resizeableActivity = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setStaticSharedLibrary(boolean value) {
        staticSharedLibrary = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setOverlay(boolean value) {
        overlay = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setIsolatedSplitLoading(boolean value) {
        isolatedSplitLoading = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setHasDomainUrls(boolean value) {
        hasDomainUrls = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setProfileableByShell(boolean value) {
        profileableByShell = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setBackupInForeground(boolean value) {
        backupInForeground = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setUseEmbeddedDex(boolean value) {
        useEmbeddedDex = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setDefaultToDeviceProtectedStorage(boolean value) {
        defaultToDeviceProtectedStorage = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setDirectBootAware(boolean value) {
        directBootAware = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setGwpAsanMode(int value) {
        gwpAsanMode = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setPartiallyDirectBootAware(boolean value) {
        partiallyDirectBootAware = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setResizeableActivityViaSdkVersion(boolean value) {
        resizeableActivityViaSdkVersion = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setAllowClearUserDataOnFailedRestore(boolean value) {
        allowClearUserDataOnFailedRestore = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setAllowAudioPlaybackCapture(boolean value) {
        allowAudioPlaybackCapture = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setRequestLegacyExternalStorage(boolean value) {
        requestLegacyExternalStorage = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setUsesNonSdkApi(boolean value) {
        usesNonSdkApi = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setHasFragileUserData(boolean value) {
        hasFragileUserData = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setCantSaveState(boolean value) {
        cantSaveState = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setAllowNativeHeapPointerTagging(boolean value) {
        allowNativeHeapPointerTagging = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setAutoRevokePermissions(int value) {
        autoRevokePermissions = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setPreserveLegacyExternalStorage(boolean value) {
        preserveLegacyExternalStorage = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setVersionName(String versionName) {
        this.versionName = versionName;
        return this;
    }

    @Override
    public ParsingPackage setCompileSdkVersionCodename(String compileSdkVersionCodename) {
        this.compileSdkVersionCodeName = compileSdkVersionCodename;
        return this;
    }

    @Override
    public ParsingPackageImpl setProcessName(String processName) {
        this.processName = processName;
        return this;
    }

    @Override
    public ParsingPackageImpl setRealPackage(@Nullable String realPackage) {
        this.realPackage = realPackage;
        return this;
    }

    @Override
    public ParsingPackageImpl setRestrictedAccountType(@Nullable String restrictedAccountType) {
        this.restrictedAccountType = restrictedAccountType;
        return this;
    }

    @Override
    public ParsingPackageImpl setOverlayTargetName(@Nullable String overlayTargetName) {
        this.overlayTargetName = overlayTargetName;
        return this;
    }

    @Override
    public ParsingPackageImpl setOverlayCategory(@Nullable String overlayCategory) {
        this.overlayCategory = overlayCategory;
        return this;
    }

    @Override
    public ParsingPackageImpl setAppComponentFactory(@Nullable String appComponentFactory) {
        this.appComponentFactory = appComponentFactory;
        return this;
    }

    @Override
    public ParsingPackageImpl setBackupAgentName(@Nullable String backupAgentName) {
        this.backupAgentName = backupAgentName;
        return this;
    }

    @Override
    public ParsingPackageImpl setClassLoaderName(@Nullable String classLoaderName) {
        this.classLoaderName = classLoaderName;
        return this;
    }

    @Override
    public ParsingPackageImpl setClassName(@Nullable String className) {
        this.className = className;
        return this;
    }

    @Override
    public ParsingPackageImpl setManageSpaceActivityName(@Nullable String manageSpaceActivityName) {
        this.manageSpaceActivityName = manageSpaceActivityName;
        return this;
    }

    @Override
    public ParsingPackageImpl setPermission(@Nullable String permission) {
        this.permission = permission;
        return this;
    }

    @Override
    public ParsingPackageImpl setTaskAffinity(@Nullable String taskAffinity) {
        this.taskAffinity = taskAffinity;
        return this;
    }

    @Override
    public ParsingPackageImpl setZygotePreloadName(@Nullable String zygotePreloadName) {
        this.zygotePreloadName = zygotePreloadName;
        return this;
    }
}
