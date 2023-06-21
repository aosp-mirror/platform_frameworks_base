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

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;

import android.annotation.LongDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureGroupInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.SigningDetails;
import android.content.res.TypedArray;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Pair;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.DataClass;
import com.android.internal.util.Parcelling;
import com.android.internal.util.Parcelling.BuiltIn.ForInternedString;
import com.android.server.pm.parsing.PackageInfoUtils;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.AndroidPackageSplit;
import com.android.server.pm.pkg.AndroidPackageSplitImpl;
import com.android.server.pm.pkg.SELinuxUtil;
import com.android.server.pm.pkg.component.ComponentMutateUtils;
import com.android.server.pm.pkg.component.ParsedActivity;
import com.android.server.pm.pkg.component.ParsedActivityImpl;
import com.android.server.pm.pkg.component.ParsedApexSystemService;
import com.android.server.pm.pkg.component.ParsedApexSystemServiceImpl;
import com.android.server.pm.pkg.component.ParsedAttribution;
import com.android.server.pm.pkg.component.ParsedAttributionImpl;
import com.android.server.pm.pkg.component.ParsedComponent;
import com.android.server.pm.pkg.component.ParsedInstrumentation;
import com.android.server.pm.pkg.component.ParsedInstrumentationImpl;
import com.android.server.pm.pkg.component.ParsedIntentInfo;
import com.android.server.pm.pkg.component.ParsedMainComponent;
import com.android.server.pm.pkg.component.ParsedPermission;
import com.android.server.pm.pkg.component.ParsedPermissionGroup;
import com.android.server.pm.pkg.component.ParsedPermissionGroupImpl;
import com.android.server.pm.pkg.component.ParsedPermissionImpl;
import com.android.server.pm.pkg.component.ParsedProcess;
import com.android.server.pm.pkg.component.ParsedProvider;
import com.android.server.pm.pkg.component.ParsedProviderImpl;
import com.android.server.pm.pkg.component.ParsedService;
import com.android.server.pm.pkg.component.ParsedServiceImpl;
import com.android.server.pm.pkg.component.ParsedUsesPermission;
import com.android.server.pm.pkg.component.ParsedUsesPermissionImpl;
import com.android.server.pm.pkg.parsing.ParsingPackage;
import com.android.server.pm.pkg.parsing.ParsingPackageHidden;
import com.android.server.pm.pkg.parsing.ParsingPackageUtils;
import com.android.server.pm.pkg.parsing.ParsingUtils;

import libcore.util.EmptyArray;

import java.io.File;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Extensions to {@link PackageImpl} including fields/state contained in the system server
 * and not exposed to the core SDK.
 *
 * @hide
 */
public class PackageImpl implements ParsedPackage, AndroidPackageInternal,
        AndroidPackageHidden, ParsingPackage, ParsingPackageHidden, Parcelable {

    private static final SparseArray<int[]> EMPTY_INT_ARRAY_SPARSE_ARRAY = new SparseArray<>();
    private static final Comparator<ParsedMainComponent> ORDER_COMPARATOR =
            (first, second) -> Integer.compare(second.getOrder(), first.getOrder());
    public static Parcelling.BuiltIn.ForBoolean sForBoolean = Parcelling.Cache.getOrCreate(
            Parcelling.BuiltIn.ForBoolean.class);
    public static ForInternedString sForInternedString = Parcelling.Cache.getOrCreate(
            ForInternedString.class);
    public static Parcelling.BuiltIn.ForInternedStringArray sForInternedStringArray = Parcelling.Cache.getOrCreate(
            Parcelling.BuiltIn.ForInternedStringArray.class);
    public static Parcelling.BuiltIn.ForInternedStringList sForInternedStringList = Parcelling.Cache.getOrCreate(
            Parcelling.BuiltIn.ForInternedStringList.class);
    public static Parcelling.BuiltIn.ForInternedStringValueMap sForInternedStringValueMap =
            Parcelling.Cache.getOrCreate(Parcelling.BuiltIn.ForInternedStringValueMap.class);
    public static Parcelling.BuiltIn.ForStringSet sForStringSet = Parcelling.Cache.getOrCreate(
            Parcelling.BuiltIn.ForStringSet.class);
    public static Parcelling.BuiltIn.ForInternedStringSet sForInternedStringSet =
            Parcelling.Cache.getOrCreate(Parcelling.BuiltIn.ForInternedStringSet.class);
    protected static ParsingUtils.StringPairListParceler sForIntentInfoPairs =
            new ParsingUtils.StringPairListParceler();
    protected int versionCode;
    protected int versionCodeMajor;
    @NonNull
    @DataClass.ParcelWith(ForInternedString.class)
    protected String packageName;
    @NonNull
    protected String mBaseApkPath;
    @NonNull
    @DataClass.ParcelWith(Parcelling.BuiltIn.ForInternedStringList.class)
    protected List<String> usesLibraries = emptyList();
    @NonNull
    @DataClass.ParcelWith(Parcelling.BuiltIn.ForInternedStringList.class)
    protected List<String> usesOptionalLibraries = emptyList();
    @NonNull
    @DataClass.ParcelWith(Parcelling.BuiltIn.ForInternedStringList.class)
    protected List<String> usesNativeLibraries = emptyList();
    @NonNull
    @DataClass.ParcelWith(Parcelling.BuiltIn.ForInternedStringList.class)
    protected List<String> usesOptionalNativeLibraries = emptyList();
    @NonNull
    @DataClass.ParcelWith(Parcelling.BuiltIn.ForInternedStringList.class)
    protected List<String> originalPackages = emptyList();
    @NonNull
    @DataClass.ParcelWith(Parcelling.BuiltIn.ForInternedStringList.class)
    protected List<String> adoptPermissions = emptyList();
    /**
     * @deprecated consider migrating to {@link #getUsesPermissions} which has
     *             more parsed details, such as flags
     */
    @NonNull
    @Deprecated
    @DataClass.ParcelWith(Parcelling.BuiltIn.ForInternedStringSet.class)
    protected Set<String> requestedPermissions = emptySet();
    @NonNull
    @DataClass.ParcelWith(Parcelling.BuiltIn.ForInternedStringList.class)
    protected List<String> protectedBroadcasts = emptyList();
    @NonNull
    protected List<ParsedActivity> activities = emptyList();
    @NonNull
    protected List<ParsedApexSystemService> apexSystemServices = emptyList();
    @NonNull
    protected List<ParsedActivity> receivers = emptyList();
    @NonNull
    protected List<ParsedService> services = emptyList();
    @NonNull
    protected List<ParsedProvider> providers = emptyList();
    @NonNull
    protected List<ParsedPermission> permissions = emptyList();
    @NonNull
    protected List<ParsedPermissionGroup> permissionGroups = emptyList();
    @NonNull
    protected List<ParsedInstrumentation> instrumentations = emptyList();
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    protected String volumeUuid;
    @NonNull
    @DataClass.ParcelWith(ForInternedString.class)
    protected String mPath;
    @Nullable
    protected String[] splitCodePaths;
    @NonNull
    protected UUID mStorageUuid;
    // These are objects because null represents not explicitly set
    @Nullable
    @DataClass.ParcelWith(Parcelling.BuiltIn.ForBoolean.class)
    private Boolean supportsSmallScreens;
    @Nullable
    @DataClass.ParcelWith(Parcelling.BuiltIn.ForBoolean.class)
    private Boolean supportsNormalScreens;
    @Nullable
    @DataClass.ParcelWith(Parcelling.BuiltIn.ForBoolean.class)
    private Boolean supportsLargeScreens;
    @Nullable
    @DataClass.ParcelWith(Parcelling.BuiltIn.ForBoolean.class)
    private Boolean supportsExtraLargeScreens;
    @Nullable
    @DataClass.ParcelWith(Parcelling.BuiltIn.ForBoolean.class)
    private Boolean resizeable;
    @Nullable
    @DataClass.ParcelWith(Parcelling.BuiltIn.ForBoolean.class)
    private Boolean anyDensity;
    private int baseRevisionCode;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String versionName;
    private int compileSdkVersion;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String compileSdkVersionCodeName;
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
    private String overlayTargetOverlayableName;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String overlayCategory;
    private int overlayPriority;
    @NonNull
    @DataClass.ParcelWith(Parcelling.BuiltIn.ForInternedStringValueMap.class)
    private Map<String, String> overlayables = emptyMap();
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String sdkLibraryName;
    private int sdkLibVersionMajor;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String staticSharedLibraryName;
    private long staticSharedLibVersion;
    @NonNull
    @DataClass.ParcelWith(Parcelling.BuiltIn.ForInternedStringList.class)
    private List<String> libraryNames = emptyList();
    @NonNull
    @DataClass.ParcelWith(Parcelling.BuiltIn.ForInternedStringList.class)
    private List<String> usesStaticLibraries = emptyList();
    @Nullable
    private long[] usesStaticLibrariesVersions;
    @Nullable
    private String[][] usesStaticLibrariesCertDigests;
    @NonNull
    @DataClass.ParcelWith(Parcelling.BuiltIn.ForInternedStringList.class)
    private List<String> usesSdkLibraries = emptyList();
    @Nullable
    private long[] usesSdkLibrariesVersionsMajor;
    @Nullable
    private String[][] usesSdkLibrariesCertDigests;
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
    private List<ParsedUsesPermission> usesPermissions = emptyList();
    @NonNull
    @DataClass.ParcelWith(Parcelling.BuiltIn.ForInternedStringSet.class)
    private Set<String> implicitPermissions = emptySet();
    @NonNull
    private Set<String> upgradeKeySets = emptySet();
    @NonNull
    private Map<String, ArraySet<PublicKey>> keySetMapping = emptyMap();
    @NonNull
    private List<ParsedAttribution> attributions = emptyList();
    @NonNull
//    @DataClass.ParcelWith(ParsingUtils.StringPairListParceler.class)
    private List<Pair<String, ParsedIntentInfo>> preferredActivityFilters = emptyList();
    /**
     * Map from a process name to a {@link ParsedProcess}.
     */
    @NonNull
    private Map<String, ParsedProcess> processes = emptyMap();
    @Nullable
    private Bundle metaData;
    @NonNull
    private Map<String, PackageManager.Property> mProperties = emptyMap();
    @NonNull
    private SigningDetails signingDetails = SigningDetails.UNKNOWN;
    @NonNull
    @DataClass.ParcelWith(Parcelling.BuiltIn.ForInternedStringList.class)
    private List<Intent> queriesIntents = emptyList();
    @NonNull
    @DataClass.ParcelWith(Parcelling.BuiltIn.ForInternedStringList.class)
    private List<String> queriesPackages = emptyList();
    @NonNull
    @DataClass.ParcelWith(Parcelling.BuiltIn.ForInternedStringSet.class)
    private Set<String> queriesProviders = emptySet();
    @Nullable
    @DataClass.ParcelWith(Parcelling.BuiltIn.ForInternedStringArray.class)
    private String[] splitClassLoaderNames;
    @Nullable
    private SparseArray<int[]> splitDependencies;
    @Nullable
    private int[] splitFlags;
    @Nullable
    @DataClass.ParcelWith(Parcelling.BuiltIn.ForInternedStringArray.class)
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
    private int category = ApplicationInfo.CATEGORY_UNDEFINED;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String classLoaderName;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String className;
    private int compatibleWidthLimitDp;
    private int descriptionRes;
    private int fullBackupContent;
    private int dataExtractionRules;
    private int iconRes;
    private int installLocation = ParsingPackageUtils.PARSE_DEFAULT_INSTALL_LOCATION;
    private int labelRes;
    private int largestWidthLimitDp;
    private int logo;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String manageSpaceActivityName;
    private float maxAspectRatio;
    private float minAspectRatio;
    @Nullable
    private SparseIntArray minExtensionVersions;
    private int minSdkVersion = ParsingUtils.DEFAULT_MIN_SDK_VERSION;
    private int maxSdkVersion = ParsingUtils.DEFAULT_MAX_SDK_VERSION;
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
    private int targetSdkVersion = ParsingUtils.DEFAULT_TARGET_SDK_VERSION;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String taskAffinity;
    private int theme;
    private int uiOptions;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String zygotePreloadName;
    /**
     * @see AndroidPackage#getResizeableActivity()
     */
    @Nullable
    @DataClass.ParcelWith(Parcelling.BuiltIn.ForBoolean.class)
    private Boolean resizeableActivity;
    private int autoRevokePermissions;
    @ApplicationInfo.GwpAsanMode
    private int gwpAsanMode;
    @ApplicationInfo.MemtagMode
    private int memtagMode;
    @ApplicationInfo.NativeHeapZeroInitialized
    private int nativeHeapZeroInitialized;
    @Nullable
    @DataClass.ParcelWith(Parcelling.BuiltIn.ForBoolean.class)
    private Boolean requestRawExternalStorageAccess;
    @NonNull
    @DataClass.ParcelWith(Parcelling.BuiltIn.ForInternedStringSet.class)
    private Set<String> mimeGroups = emptySet();
    // Usually there's code to set enabled to true during parsing, but it's possible to install
    // an APK targeting <R that doesn't contain an <application> tag. That code would be skipped
    // and never assign this, so initialize this to true for those cases.
    private long mBooleans = Booleans.ENABLED;
    private long mBooleans2;
    @NonNull
    private Set<String> mKnownActivityEmbeddingCerts = emptySet();
    // Derived fields
    private long mLongVersionCode;
    private int mLocaleConfigRes;

    private List<AndroidPackageSplit> mSplits;

    @NonNull
    private String[] mUsesLibrariesSorted;
    @NonNull
    private String[] mUsesOptionalLibrariesSorted;
    @NonNull
    private String[] mUsesSdkLibrariesSorted;
    @NonNull
    private String[] mUsesStaticLibrariesSorted;

    @NonNull
    public static PackageImpl forParsing(@NonNull String packageName, @NonNull String baseCodePath,
            @NonNull String codePath, @NonNull TypedArray manifestArray, boolean isCoreApp) {
        return new PackageImpl(packageName, baseCodePath, codePath, manifestArray, isCoreApp);
    }

    /**
     * Mock an unavailable {@link AndroidPackage} to use when
     * removing
     * a package from the system.
     * This can occur if the package was installed on a storage device that has since been removed.
     * Since the infrastructure uses {@link AndroidPackage}, but
     * for
     * this case only cares about
     * volumeUuid, just fake it rather than having separate method paths.
     */
    @NonNull
    public static AndroidPackage buildFakeForDeletion(String packageName, String volumeUuid) {
        return PackageImpl.forTesting(packageName)
                .setVolumeUuid(volumeUuid)
                .hideAsParsed()
                .hideAsFinal();
    }

    @NonNull
    @VisibleForTesting
    public static ParsingPackage forTesting(String packageName) {
        return forTesting(packageName, "");
    }

    @NonNull
    @VisibleForTesting
    public static ParsingPackage forTesting(String packageName, String baseCodePath) {
        return new PackageImpl(packageName, baseCodePath, baseCodePath, null, false);
    }

    @NonNull
    @DataClass.ParcelWith(ForInternedString.class)
    private final String manifestPackageName;

    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    protected String nativeLibraryDir;

    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    protected String nativeLibraryRootDir;

    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    protected String primaryCpuAbi;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    protected String secondaryCpuAbi;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    protected String secondaryNativeLibraryDir;

    /**
     * This is an appId, the uid if the userId is == USER_SYSTEM
     */
    private int uid = -1;

    // This is kept around as a boolean to avoid flag calculation
    // during ApplicationInfo generation.
    private boolean nativeLibraryRootRequiresIsa;

    @Override
    public PackageImpl addActivity(ParsedActivity parsedActivity) {
        this.activities = CollectionUtils.add(this.activities, parsedActivity);
        addMimeGroupsFromComponent(parsedActivity);
        return this;
    }

    @Override
    public PackageImpl addAdoptPermission(String adoptPermission) {
        this.adoptPermissions = CollectionUtils.add(this.adoptPermissions,
                TextUtils.safeIntern(adoptPermission));
        return this;
    }

    @Override
    public final PackageImpl addApexSystemService(
            ParsedApexSystemService parsedApexSystemService) {
        this.apexSystemServices = CollectionUtils.add(
                this.apexSystemServices, parsedApexSystemService);
        return this;
    }

    @Override
    public PackageImpl addAttribution(ParsedAttribution attribution) {
        this.attributions = CollectionUtils.add(this.attributions, attribution);
        return this;
    }

    @Override
    public PackageImpl addConfigPreference(ConfigurationInfo configPreference) {
        this.configPreferences = CollectionUtils.add(this.configPreferences, configPreference);
        return this;
    }

    @Override
    public PackageImpl addFeatureGroup(FeatureGroupInfo featureGroup) {
        this.featureGroups = CollectionUtils.add(this.featureGroups, featureGroup);
        return this;
    }

    @Override
    public PackageImpl addImplicitPermission(String permission) {
        addUsesPermission(new ParsedUsesPermissionImpl(permission, 0 /*usesPermissionFlags*/));
        this.implicitPermissions = CollectionUtils.add(this.implicitPermissions,
                TextUtils.safeIntern(permission));
        return this;
    }

    @Override
    public PackageImpl addInstrumentation(ParsedInstrumentation instrumentation) {
        this.instrumentations = CollectionUtils.add(this.instrumentations, instrumentation);
        return this;
    }

    @Override
    public PackageImpl addKeySet(String keySetName, PublicKey publicKey) {
        ArraySet<PublicKey> publicKeys = keySetMapping.get(keySetName);
        if (publicKeys == null) {
            publicKeys = new ArraySet<>();
        }
        publicKeys.add(publicKey);
        keySetMapping = CollectionUtils.add(this.keySetMapping, keySetName, publicKeys);
        return this;
    }

    @Override
    public PackageImpl addLibraryName(String libraryName) {
        this.libraryNames = CollectionUtils.add(this.libraryNames,
                TextUtils.safeIntern(libraryName));
        return this;
    }

    private void addMimeGroupsFromComponent(ParsedComponent component) {
        for (int i = component.getIntents().size() - 1; i >= 0; i--) {
            IntentFilter filter = component.getIntents().get(i).getIntentFilter();
            for (int groupIndex = filter.countMimeGroups() - 1; groupIndex >= 0; groupIndex--) {
                if (mimeGroups != null && mimeGroups.size() > 500) {
                    throw new IllegalStateException("Max limit on number of MIME Groups reached");
                }
                mimeGroups = CollectionUtils.add(mimeGroups, filter.getMimeGroup(groupIndex));
            }
        }
    }

    @Override
    public PackageImpl addOriginalPackage(String originalPackage) {
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
    public PackageImpl addPermission(ParsedPermission permission) {
        this.permissions = CollectionUtils.add(this.permissions, permission);
        return this;
    }

    @Override
    public PackageImpl addPermissionGroup(ParsedPermissionGroup permissionGroup) {
        this.permissionGroups = CollectionUtils.add(this.permissionGroups, permissionGroup);
        return this;
    }

    @Override
    public PackageImpl addPreferredActivityFilter(String className,
            ParsedIntentInfo intentInfo) {
        this.preferredActivityFilters = CollectionUtils.add(this.preferredActivityFilters,
                Pair.create(className, intentInfo));
        return this;
    }

    @Override
    public PackageImpl addProperty(@Nullable PackageManager.Property property) {
        if (property == null) {
            return this;
        }
        this.mProperties = CollectionUtils.add(this.mProperties, property.getName(), property);
        return this;
    }

    @Override
    public PackageImpl addProtectedBroadcast(String protectedBroadcast) {
        if (!this.protectedBroadcasts.contains(protectedBroadcast)) {
            this.protectedBroadcasts = CollectionUtils.add(this.protectedBroadcasts,
                    TextUtils.safeIntern(protectedBroadcast));
        }
        return this;
    }

    @Override
    public PackageImpl addProvider(ParsedProvider parsedProvider) {
        this.providers = CollectionUtils.add(this.providers, parsedProvider);
        addMimeGroupsFromComponent(parsedProvider);
        return this;
    }

    @Override
    public PackageImpl addQueriesIntent(Intent intent) {
        this.queriesIntents = CollectionUtils.add(this.queriesIntents, intent);
        return this;
    }

    @Override
    public PackageImpl addQueriesPackage(String packageName) {
        this.queriesPackages = CollectionUtils.add(this.queriesPackages,
                TextUtils.safeIntern(packageName));
        return this;
    }

    @Override
    public PackageImpl addQueriesProvider(String authority) {
        this.queriesProviders = CollectionUtils.add(this.queriesProviders, authority);
        return this;
    }

    @Override
    public PackageImpl addReceiver(ParsedActivity parsedReceiver) {
        this.receivers = CollectionUtils.add(this.receivers, parsedReceiver);
        addMimeGroupsFromComponent(parsedReceiver);
        return this;
    }

    @Override
    public PackageImpl addReqFeature(FeatureInfo reqFeature) {
        this.reqFeatures = CollectionUtils.add(this.reqFeatures, reqFeature);
        return this;
    }

    @Override
    public PackageImpl addService(ParsedService parsedService) {
        this.services = CollectionUtils.add(this.services, parsedService);
        addMimeGroupsFromComponent(parsedService);
        return this;
    }

    @Override
    public PackageImpl addUsesLibrary(String libraryName) {
        libraryName = TextUtils.safeIntern(libraryName);
        if (!ArrayUtils.contains(this.usesLibraries, libraryName)) {
            this.usesLibraries = CollectionUtils.add(this.usesLibraries, libraryName);
        }
        return this;
    }

    @Override
    public final PackageImpl addUsesNativeLibrary(String libraryName) {
        libraryName = TextUtils.safeIntern(libraryName);
        if (!ArrayUtils.contains(this.usesNativeLibraries, libraryName)) {
            this.usesNativeLibraries = CollectionUtils.add(this.usesNativeLibraries, libraryName);
        }
        return this;
    }

    @Override
    public PackageImpl addUsesOptionalLibrary(String libraryName) {
        libraryName = TextUtils.safeIntern(libraryName);
        if (!ArrayUtils.contains(this.usesOptionalLibraries, libraryName)) {
            this.usesOptionalLibraries = CollectionUtils.add(this.usesOptionalLibraries,
                    libraryName);
        }
        return this;
    }

    @Override
    public final PackageImpl addUsesOptionalNativeLibrary(String libraryName) {
        libraryName = TextUtils.safeIntern(libraryName);
        if (!ArrayUtils.contains(this.usesOptionalNativeLibraries, libraryName)) {
            this.usesOptionalNativeLibraries = CollectionUtils.add(this.usesOptionalNativeLibraries,
                    libraryName);
        }
        return this;
    }

    @Override
    public PackageImpl addUsesPermission(ParsedUsesPermission permission) {
        this.usesPermissions = CollectionUtils.add(this.usesPermissions, permission);

        // Continue populating legacy data structures to avoid performance
        // issues until all that code can be migrated
        this.requestedPermissions = CollectionUtils.add(this.requestedPermissions,
                permission.getName());

        return this;
    }

    @Override
    public PackageImpl addUsesSdkLibrary(String libraryName, long versionMajor,
            String[] certSha256Digests) {
        this.usesSdkLibraries = CollectionUtils.add(this.usesSdkLibraries,
                TextUtils.safeIntern(libraryName));
        this.usesSdkLibrariesVersionsMajor = ArrayUtils.appendLong(
                this.usesSdkLibrariesVersionsMajor, versionMajor, true);
        this.usesSdkLibrariesCertDigests = ArrayUtils.appendElement(String[].class,
                this.usesSdkLibrariesCertDigests, certSha256Digests, true);
        return this;
    }

    @Override
    public PackageImpl addUsesStaticLibrary(String libraryName, long version,
            String[] certSha256Digests) {
        this.usesStaticLibraries = CollectionUtils.add(this.usesStaticLibraries,
                TextUtils.safeIntern(libraryName));
        this.usesStaticLibrariesVersions = ArrayUtils.appendLong(this.usesStaticLibrariesVersions,
                version, true);
        this.usesStaticLibrariesCertDigests = ArrayUtils.appendElement(String[].class,
                this.usesStaticLibrariesCertDigests, certSha256Digests, true);
        return this;
    }

    @Override
    public boolean isAttributionsUserVisible() {
        return getBoolean(Booleans.ATTRIBUTIONS_ARE_USER_VISIBLE);
    }

    @Override
    public PackageImpl asSplit(String[] splitNames, String[] splitCodePaths,
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

    protected void assignDerivedFields() {
        mStorageUuid = StorageManager.convert(volumeUuid);
        mLongVersionCode = PackageInfo.composeLongVersionCode(versionCodeMajor, versionCode);
    }

    /**
     * Create a map from a process name to the custom application class for this process,
     * which comes from <processes><process android:name="xxx">.
     *
     * The original information is stored in {@link #processes}, but it's stored in
     * a form of: [process name] -[1:N]-> [package name] -[1:N]-> [class name].
     * We scan it and collect the process names and their app class names, only for this package.
     *
     * The resulting map only contains processes with a custom application class set.
     */
    @Nullable
    private ArrayMap<String, String> buildAppClassNamesByProcess() {
        if (ArrayUtils.size(processes) == 0) {
            return null;
        }
        final ArrayMap<String, String> ret = new ArrayMap<>(4);
        for (String processName : processes.keySet()) {
            final ParsedProcess process = processes.get(processName);
            final ArrayMap<String, String> appClassesByPackage =
                    process.getAppClassNamesByPackage();

            for (int i = 0; i < appClassesByPackage.size(); i++) {
                final String packageName = appClassesByPackage.keyAt(i);

                if (this.packageName.equals(packageName)) {
                    final String appClassName = appClassesByPackage.valueAt(i);
                    if (!TextUtils.isEmpty(appClassName)) {
                        ret.put(processName, appClassName);
                    }
                }
            }
        }
        return ret;
    }

    @Override
    public List<AndroidPackageSplit> getSplits() {
        if (mSplits == null) {
            var splits = new ArrayList<AndroidPackageSplit>();
            splits.add(new AndroidPackageSplitImpl(
                    null,
                    getBaseApkPath(),
                    getBaseRevisionCode(),
                    isDeclaredHavingCode() ? ApplicationInfo.FLAG_HAS_CODE : 0,
                    getClassLoaderName()
            ));

            if (splitNames != null) {
                for (int index = 0; index < splitNames.length; index++) {
                    splits.add(new AndroidPackageSplitImpl(
                            splitNames[index],
                            splitCodePaths[index],
                            splitRevisionCodes[index],
                            splitFlags[index],
                            splitClassLoaderNames[index]
                    ));
                }
            }

            if (splitDependencies != null) {
                for (int index = 0; index < splitDependencies.size(); index++) {
                    var splitIndex = splitDependencies.keyAt(index);
                    var dependenciesByIndex = splitDependencies.valueAt(index);
                    var dependencies = new ArrayList<AndroidPackageSplit>();
                    for (int dependencyIndex : dependenciesByIndex) {
                        // Legacy holdover, base dependencies are an array of -1 rather than empty
                        if (dependencyIndex >= 0) {
                            dependencies.add(splits.get(dependencyIndex));
                        }
                    }
                    ((AndroidPackageSplitImpl) splits.get(splitIndex))
                            .fillDependencies(Collections.unmodifiableList(dependencies));
                }
            }

            mSplits = Collections.unmodifiableList(splits);
        }
        return mSplits;
    }

    @Override
    public String toString() {
        return "Package{"
                + Integer.toHexString(System.identityHashCode(this))
                + " " + packageName + "}";
    }

    @NonNull
    @Override
    public List<ParsedActivity> getActivities() {
        return activities;
    }

    @NonNull
    @Override
    public List<String> getAdoptPermissions() {
        return adoptPermissions;
    }

    @NonNull
    @Override
    public List<ParsedApexSystemService> getApexSystemServices() {
        return apexSystemServices;
    }

    @Nullable
    @Override
    public String getAppComponentFactory() {
        return appComponentFactory;
    }

    @NonNull
    @Override
    public List<ParsedAttribution> getAttributions() {
        return attributions;
    }

    @Override
    public int getAutoRevokePermissions() {
        return autoRevokePermissions;
    }

    @Nullable
    @Override
    public String getBackupAgentName() {
        return backupAgentName;
    }

    @Override
    public int getBannerResourceId() {
        return banner;
    }

    @NonNull
    @Override
    public String getBaseApkPath() {
        return mBaseApkPath;
    }

    @Override
    public int getBaseRevisionCode() {
        return baseRevisionCode;
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
    public String getApplicationClassName() {
        return className;
    }

    @Override
    public int getCompatibleWidthLimitDp() {
        return compatibleWidthLimitDp;
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
    public List<ConfigurationInfo> getConfigPreferences() {
        return configPreferences;
    }

    @Override
    public int getDataExtractionRulesResourceId() {
        return dataExtractionRules;
    }

    @Override
    public int getDescriptionResourceId() {
        return descriptionRes;
    }

    @NonNull
    @Override
    public List<FeatureGroupInfo> getFeatureGroups() {
        return featureGroups;
    }

    @Override
    public int getFullBackupContentResourceId() {
        return fullBackupContent;
    }

    @ApplicationInfo.GwpAsanMode
    @Override
    public int getGwpAsanMode() {
        return gwpAsanMode;
    }

    @Override
    public int getIconResourceId() {
        return iconRes;
    }

    @NonNull
    @Override
    public Set<String> getImplicitPermissions() {
        return implicitPermissions;
    }

    @Override
    public int getInstallLocation() {
        return installLocation;
    }

    @NonNull
    @Override
    public List<ParsedInstrumentation> getInstrumentations() {
        return instrumentations;
    }

    @NonNull
    @Override
    public Map<String,ArraySet<PublicKey>> getKeySetMapping() {
        return keySetMapping;
    }

    @NonNull
    @Override
    public Set<String> getKnownActivityEmbeddingCerts() {
        return mKnownActivityEmbeddingCerts;
    }

    @Override
    public int getLabelResourceId() {
        return labelRes;
    }

    @Override
    public int getLargestWidthLimitDp() {
        return largestWidthLimitDp;
    }

    @NonNull
    @Override
    public List<String> getLibraryNames() {
        return libraryNames;
    }

    @Override
    public int getLocaleConfigResourceId() {
        return mLocaleConfigRes;
    }

    @Override
    public int getLogoResourceId() {
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
    public int getMaxSdkVersion() {
        return maxSdkVersion;
    }

    @ApplicationInfo.MemtagMode
    @Override
    public int getMemtagMode() {
        return memtagMode;
    }

    @Nullable
    @Override
    public Bundle getMetaData() {
        return metaData;
    }

    @Override
    @Nullable
    public Set<String> getMimeGroups() {
        return mimeGroups;
    }

    @Override
    public float getMinAspectRatio() {
        return minAspectRatio;
    }

    @Nullable
    @Override
    public SparseIntArray getMinExtensionVersions() {
        return minExtensionVersions;
    }

    @Override
    public int getMinSdkVersion() {
        return minSdkVersion;
    }

    @ApplicationInfo.NativeHeapZeroInitialized
    @Override
    public int getNativeHeapZeroInitialized() {
        return nativeHeapZeroInitialized;
    }

    @Override
    public int getNetworkSecurityConfigResourceId() {
        return networkSecurityConfigRes;
    }

    @Nullable
    @Override
    public CharSequence getNonLocalizedLabel() {
        return nonLocalizedLabel;
    }

    @NonNull
    @Override
    public List<String> getOriginalPackages() {
        return originalPackages;
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

    @Nullable
    @Override
    public String getOverlayTarget() {
        return overlayTarget;
    }

    @Nullable
    @Override
    public String getOverlayTargetOverlayableName() {
        return overlayTargetOverlayableName;
    }

    @NonNull
    @Override
    public Map<String,String> getOverlayables() {
        return overlayables;
    }

    @NonNull
    @Override
    public String getPackageName() {
        return packageName;
    }

    @NonNull
    @Override
    public String getPath() {
        return mPath;
    }

    @Nullable
    @Override
    public String getPermission() {
        return permission;
    }

    @NonNull
    @Override
    public List<ParsedPermissionGroup> getPermissionGroups() {
        return permissionGroups;
    }

    @NonNull
    @Override
    public List<ParsedPermission> getPermissions() {
        return permissions;
    }

    @NonNull
    @Override
    public List<Pair<String,ParsedIntentInfo>> getPreferredActivityFilters() {
        return preferredActivityFilters;
    }

    @NonNull
    @Override
    public String getProcessName() {
        return processName != null ? processName : packageName;
    }

    @NonNull
    @Override
    public Map<String,ParsedProcess> getProcesses() {
        return processes;
    }

    @NonNull
    @Override
    public Map<String, PackageManager.Property> getProperties() {
        return mProperties;
    }

    @NonNull
    @Override
    public List<String> getProtectedBroadcasts() {
        return protectedBroadcasts;
    }

    @NonNull
    @Override
    public List<ParsedProvider> getProviders() {
        return providers;
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

    @NonNull
    @Override
    public List<ParsedActivity> getReceivers() {
        return receivers;
    }

    @NonNull
    @Override
    public List<FeatureInfo> getRequestedFeatures() {
        return reqFeatures;
    }

    /**
     * @deprecated consider migrating to {@link #getUsesPermissions} which has
     *             more parsed details, such as flags
     */
    @NonNull
    @Override
    @Deprecated
    public Set<String> getRequestedPermissions() {
        return requestedPermissions;
    }

    @Nullable
    @Override
    public String getRequiredAccountType() {
        return requiredAccountType;
    }

    @Override
    public int getRequiresSmallestWidthDp() {
        return requiresSmallestWidthDp;
    }

    @Nullable
    @Override
    public Boolean getResizeableActivity() {
        return resizeableActivity;
    }

    @Nullable
    @Override
    public byte[] getRestrictUpdateHash() {
        return restrictUpdateHash;
    }

    @Nullable
    @Override
    public String getRestrictedAccountType() {
        return restrictedAccountType;
    }

    @Override
    public int getRoundIconResourceId() {
        return roundIconRes;
    }

    @Nullable
    @Override
    public String getSdkLibraryName() {
        return sdkLibraryName;
    }

    @Override
    public int getSdkLibVersionMajor() {
        return sdkLibVersionMajor;
    }

    @NonNull
    @Override
    public List<ParsedService> getServices() {
        return services;
    }

    @Nullable
    @Override
    public String getSharedUserId() {
        return sharedUserId;
    }

    @Override
    public int getSharedUserLabelResourceId() {
        return sharedUserLabel;
    }

    @NonNull
    @Override
    public SigningDetails getSigningDetails() {
        return signingDetails;
    }

    @NonNull
    @Override
    public String[] getSplitClassLoaderNames() {
        return splitClassLoaderNames == null ? EmptyArray.STRING : splitClassLoaderNames;
    }

    @NonNull
    @Override
    public String[] getSplitCodePaths() {
        return splitCodePaths == null ? EmptyArray.STRING : splitCodePaths;
    }

    @Nullable
    @Override
    public SparseArray<int[]> getSplitDependencies() {
        return splitDependencies == null ? EMPTY_INT_ARRAY_SPARSE_ARRAY : splitDependencies;
    }

    @Nullable
    @Override
    public int[] getSplitFlags() {
        return splitFlags;
    }

    @NonNull
    @Override
    public String[] getSplitNames() {
        return splitNames == null ? EmptyArray.STRING : splitNames;
    }

    @NonNull
    @Override
    public int[] getSplitRevisionCodes() {
        return splitRevisionCodes == null ? EmptyArray.INT : splitRevisionCodes;
    }

    @Nullable
    @Override
    public String getStaticSharedLibraryName() {
        return staticSharedLibraryName;
    }

    @Override
    public long getStaticSharedLibraryVersion() {
        return staticSharedLibVersion;
    }

    @Override
    public UUID getStorageUuid() {
        return mStorageUuid;
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
    public int getThemeResourceId() {
        return theme;
    }

    @Override
    public int getUiOptions() {
        return uiOptions;
    }

    @NonNull
    @Override
    public Set<String> getUpgradeKeySets() {
        return upgradeKeySets;
    }

    @NonNull
    @Override
    public List<String> getUsesLibraries() {
        return usesLibraries;
    }

    @NonNull
    @Override
    public String[] getUsesLibrariesSorted() {
        if (mUsesLibrariesSorted == null) {
            // Note lazy-sorting here doesn't break immutability because it always
            // return the same content. In the case of multi-threading, data race in accessing
            // mUsesLibrariesSorted might result in unnecessary creation of sorted copies
            // which is OK because the case is quite rare.
            mUsesLibrariesSorted = sortLibraries(usesLibraries);
        }
        return mUsesLibrariesSorted;
    }

    @NonNull
    @Override
    public List<String> getUsesNativeLibraries() {
        return usesNativeLibraries;
    }

    @NonNull
    @Override
    public List<String> getUsesOptionalLibraries() {
        return usesOptionalLibraries;
    }

    @NonNull
    @Override
    public String[] getUsesOptionalLibrariesSorted() {
        if (mUsesOptionalLibrariesSorted == null) {
            mUsesOptionalLibrariesSorted = sortLibraries(usesOptionalLibraries);
        }
        return mUsesOptionalLibrariesSorted;
    }

    @NonNull
    @Override
    public List<String> getUsesOptionalNativeLibraries() {
        return usesOptionalNativeLibraries;
    }

    @NonNull
    @Override
    public List<ParsedUsesPermission> getUsesPermissions() {
        return usesPermissions;
    }

    @NonNull
    @Override
    public List<String> getUsesSdkLibraries() { return usesSdkLibraries; }

    @NonNull
    @Override
    public String[] getUsesSdkLibrariesSorted() {
        if (mUsesSdkLibrariesSorted == null) {
            mUsesSdkLibrariesSorted = sortLibraries(usesSdkLibraries);
        }
        return mUsesSdkLibrariesSorted;
    }

    @Nullable
    @Override
    public String[][] getUsesSdkLibrariesCertDigests() { return usesSdkLibrariesCertDigests; }

    @Nullable
    @Override
    public long[] getUsesSdkLibrariesVersionsMajor() { return usesSdkLibrariesVersionsMajor; }

    @NonNull
    @Override
    public List<String> getUsesStaticLibraries() {
        return usesStaticLibraries;
    }

    @NonNull
    @Override
    public String[] getUsesStaticLibrariesSorted() {
        if (mUsesStaticLibrariesSorted == null) {
            mUsesStaticLibrariesSorted = sortLibraries(usesStaticLibraries);
        }
        return mUsesStaticLibrariesSorted;
    }

    @Nullable
    @Override
    public String[][] getUsesStaticLibrariesCertDigests() {
        return usesStaticLibrariesCertDigests;
    }

    @Nullable
    @Override
    public long[] getUsesStaticLibrariesVersions() {
        return usesStaticLibrariesVersions;
    }

    @Override
    public int getVersionCode() {
        return versionCode;
    }

    @Override
    public int getVersionCodeMajor() {
        return versionCodeMajor;
    }

    @Nullable
    @Override
    public String getVersionName() {
        return versionName;
    }

    @Nullable
    @Override
    public String getVolumeUuid() {
        return volumeUuid;
    }

    @Nullable
    @Override
    public String getZygotePreloadName() {
        return zygotePreloadName;
    }

    @Override
    public boolean hasPreserveLegacyExternalStorage() {
        return getBoolean(Booleans.PRESERVE_LEGACY_EXTERNAL_STORAGE);
    }

    @Override
    public boolean hasRequestForegroundServiceExemption() {
        return getBoolean(Booleans.REQUEST_FOREGROUND_SERVICE_EXEMPTION);
    }

    @Nullable
    @Override
    public Boolean hasRequestRawExternalStorageAccess() {
        return requestRawExternalStorageAccess;
    }

    @Override
    public boolean isAllowAudioPlaybackCapture() {
        return getBoolean(Booleans.ALLOW_AUDIO_PLAYBACK_CAPTURE);
    }

    @Override
    public boolean isBackupAllowed() {
        return getBoolean(Booleans.ALLOW_BACKUP);
    }

    @Override
    public boolean isClearUserDataAllowed() {
        return getBoolean(Booleans.ALLOW_CLEAR_USER_DATA);
    }

    @Override
    public boolean isClearUserDataOnFailedRestoreAllowed() {
        return getBoolean(Booleans.ALLOW_CLEAR_USER_DATA_ON_FAILED_RESTORE);
    }

    @Override
    public boolean isAllowNativeHeapPointerTagging() {
        return getBoolean(Booleans.ALLOW_NATIVE_HEAP_POINTER_TAGGING);
    }

    @Override
    public boolean isTaskReparentingAllowed() {
        return getBoolean(Booleans.ALLOW_TASK_REPARENTING);
    }

    public boolean isAnyDensity() {
        if (anyDensity == null) {
            return targetSdkVersion >= Build.VERSION_CODES.DONUT;
        }

        return anyDensity;
    }

    @Override
    public boolean isBackupInForeground() {
        return getBoolean(Booleans.BACKUP_IN_FOREGROUND);
    }

    @Override
    public boolean isHardwareAccelerated() {
        return getBoolean(Booleans.HARDWARE_ACCELERATED);
    }

    @Override
    public boolean isSaveStateDisallowed() {
        return getBoolean(Booleans.CANT_SAVE_STATE);
    }

    @Override
    public boolean isCrossProfile() {
        return getBoolean(Booleans.CROSS_PROFILE);
    }

    @Override
    public boolean isDebuggable() {
        return getBoolean(Booleans.DEBUGGABLE);
    }

    @Override
    public boolean isDefaultToDeviceProtectedStorage() {
        return getBoolean(Booleans.DEFAULT_TO_DEVICE_PROTECTED_STORAGE);
    }

    @Override
    public boolean isDirectBootAware() {
        return getBoolean(Booleans.DIRECT_BOOT_AWARE);
    }

    @Override
    public boolean isEnabled() {
        return getBoolean(Booleans.ENABLED);
    }

    @Override
    public boolean isExternalStorage() {
        return getBoolean(Booleans.EXTERNAL_STORAGE);
    }

    @Override
    public boolean isExtractNativeLibrariesRequested() {
        return getBoolean(Booleans.EXTRACT_NATIVE_LIBS);
    }

    @Override
    public boolean isForceQueryable() {
        return getBoolean(Booleans.FORCE_QUERYABLE);
    }

    @Override
    public boolean isFullBackupOnly() {
        return getBoolean(Booleans.FULL_BACKUP_ONLY);
    }

    @Override
    public boolean isGame() {
        return getBoolean(Booleans.GAME);
    }

    @Override
    public boolean isDeclaredHavingCode() {
        return getBoolean(Booleans.HAS_CODE);
    }

    @Override
    public boolean isHasDomainUrls() {
        return getBoolean(Booleans.HAS_DOMAIN_URLS);
    }

    @Override
    public boolean isUserDataFragile() {
        return getBoolean(Booleans.HAS_FRAGILE_USER_DATA);
    }

    @Override
    public boolean isIsolatedSplitLoading() {
        return getBoolean(Booleans.ISOLATED_SPLIT_LOADING);
    }

    @Override
    public boolean isKillAfterRestoreAllowed() {
        return getBoolean(Booleans.KILL_AFTER_RESTORE);
    }

    @Override
    public boolean isLargeHeap() {
        return getBoolean(Booleans.LARGE_HEAP);
    }

    @Override
    public boolean isLeavingSharedUser() {
        return getBoolean(Booleans.LEAVING_SHARED_UID);
    }

    @Override
    public boolean isMultiArch() {
        return getBoolean(Booleans.MULTI_ARCH);
    }

    @Override
    public boolean isOnBackInvokedCallbackEnabled() {
        return getBoolean(Booleans.ENABLE_ON_BACK_INVOKED_CALLBACK);
    }

    @Override
    public boolean isResourceOverlay() {
        return getBoolean(Booleans.OVERLAY);
    }

    @Override
    public boolean isOverlayIsStatic() {
        return getBoolean(Booleans.OVERLAY_IS_STATIC);
    }

    @Override
    public boolean isPartiallyDirectBootAware() {
        return getBoolean(Booleans.PARTIALLY_DIRECT_BOOT_AWARE);
    }

    @Override
    public boolean isPersistent() {
        return getBoolean(Booleans.PERSISTENT);
    }

    @Override
    public boolean isProfileable() {
        return !getBoolean(Booleans.DISALLOW_PROFILING);
    }

    @Override
    public boolean isProfileableByShell() {
        return isProfileable() && getBoolean(Booleans.PROFILEABLE_BY_SHELL);
    }

    @Override
    public boolean isRequestLegacyExternalStorage() {
        return getBoolean(Booleans.REQUEST_LEGACY_EXTERNAL_STORAGE);
    }

    @Override
    public boolean isRequiredForAllUsers() {
        return getBoolean(Booleans.REQUIRED_FOR_ALL_USERS);
    }

    @Override
    public boolean isResetEnabledSettingsOnAppDataCleared() {
        return getBoolean(Booleans.RESET_ENABLED_SETTINGS_ON_APP_DATA_CLEARED);
    }

    public boolean isResizeable() {
        if (resizeable == null) {
            return targetSdkVersion >= Build.VERSION_CODES.DONUT;
        }

        return resizeable;
    }

    @Override
    public boolean isResizeableActivityViaSdkVersion() {
        return getBoolean(Booleans.RESIZEABLE_ACTIVITY_VIA_SDK_VERSION);
    }

    @Override
    public boolean isRestoreAnyVersion() {
        return getBoolean(Booleans.RESTORE_ANY_VERSION);
    }

    @Override
    public boolean isSdkLibrary() {
        return getBoolean(Booleans.SDK_LIBRARY);
    }

    @Override
    public boolean isStaticSharedLibrary() {
        return getBoolean(Booleans.STATIC_SHARED_LIBRARY);
    }

    public boolean isExtraLargeScreensSupported() {
        if (supportsExtraLargeScreens == null) {
            return targetSdkVersion >= Build.VERSION_CODES.GINGERBREAD;
        }

        return supportsExtraLargeScreens;
    }

    public boolean isLargeScreensSupported() {
        if (supportsLargeScreens == null) {
            return targetSdkVersion >= Build.VERSION_CODES.DONUT;
        }

        return supportsLargeScreens;
    }

    public boolean isNormalScreensSupported() {
        return supportsNormalScreens == null || supportsNormalScreens;
    }

    @Override
    public boolean isRtlSupported() {
        return getBoolean(Booleans.SUPPORTS_RTL);
    }

    public boolean isSmallScreensSupported() {
        if (supportsSmallScreens == null) {
            return targetSdkVersion >= Build.VERSION_CODES.DONUT;
        }

        return supportsSmallScreens;
    }

    @Override
    public boolean isTestOnly() {
        return getBoolean(Booleans.TEST_ONLY);
    }

    @Override
    public boolean is32BitAbiPreferred() {
        return getBoolean(Booleans.USE_32_BIT_ABI);
    }

    @Override
    public boolean isUseEmbeddedDex() {
        return getBoolean(Booleans.USE_EMBEDDED_DEX);
    }

    @Override
    public boolean isCleartextTrafficAllowed() {
        return getBoolean(Booleans.USES_CLEARTEXT_TRAFFIC);
    }

    @Override
    public boolean isNonSdkApiRequested() {
        return getBoolean(Booleans.USES_NON_SDK_API);
    }

    @Override
    public boolean isVisibleToInstantApps() {
        return getBoolean(Booleans.VISIBLE_TO_INSTANT_APPS);
    }

    @Override
    public boolean isVmSafeMode() {
        return getBoolean(Booleans.VM_SAFE_MODE);
    }

    @Override public PackageImpl removeUsesOptionalNativeLibrary(String libraryName) {
        this.usesOptionalNativeLibraries = CollectionUtils.remove(this.usesOptionalNativeLibraries,
                libraryName);
        return this;
    }

    @Override
    public PackageImpl setAllowAudioPlaybackCapture(boolean value) {
        return setBoolean(Booleans.ALLOW_AUDIO_PLAYBACK_CAPTURE, value);
    }

    @Override
    public PackageImpl setBackupAllowed(boolean value) {
        return setBoolean(Booleans.ALLOW_BACKUP, value);
    }

    @Override
    public PackageImpl setClearUserDataAllowed(boolean value) {
        return setBoolean(Booleans.ALLOW_CLEAR_USER_DATA, value);
    }

    @Override
    public PackageImpl setClearUserDataOnFailedRestoreAllowed(boolean value) {
        return setBoolean(Booleans.ALLOW_CLEAR_USER_DATA_ON_FAILED_RESTORE, value);
    }

    @Override
    public PackageImpl setAllowNativeHeapPointerTagging(boolean value) {
        return setBoolean(Booleans.ALLOW_NATIVE_HEAP_POINTER_TAGGING, value);
    }

    @Override
    public PackageImpl setTaskReparentingAllowed(boolean value) {
        return setBoolean(Booleans.ALLOW_TASK_REPARENTING, value);
    }

    @Override
    public PackageImpl setAnyDensity(int anyDensity) {
        if (anyDensity == 1) {
            return this;
        }

        this.anyDensity = anyDensity < 0;
        return this;
    }

    @Override
    public PackageImpl setAppComponentFactory(@Nullable String appComponentFactory) {
        this.appComponentFactory = appComponentFactory;
        return this;
    }

    @Override
    public ParsingPackage setAttributionsAreUserVisible(boolean attributionsAreUserVisible) {
        setBoolean(Booleans.ATTRIBUTIONS_ARE_USER_VISIBLE, attributionsAreUserVisible);
        return this;
    }

    @Override
    public PackageImpl setAutoRevokePermissions(int value) {
        autoRevokePermissions = value;
        return this;
    }

    @Override
    public PackageImpl setBackupAgentName(@Nullable String backupAgentName) {
        this.backupAgentName = backupAgentName;
        return this;
    }

    @Override
    public PackageImpl setBackupInForeground(boolean value) {
        return setBoolean(Booleans.BACKUP_IN_FOREGROUND, value);
    }

    @Override
    public PackageImpl setBannerResourceId(int value) {
        banner = value;
        return this;
    }

    @Override
    public PackageImpl setHardwareAccelerated(boolean value) {
        return setBoolean(Booleans.HARDWARE_ACCELERATED, value);
    }

    @Override
    public PackageImpl setBaseRevisionCode(int value) {
        baseRevisionCode = value;
        return this;
    }

    @Override
    public PackageImpl setSaveStateDisallowed(boolean value) {
        return setBoolean(Booleans.CANT_SAVE_STATE, value);
    }

    @Override
    public PackageImpl setCategory(int value) {
        category = value;
        return this;
    }

    @Override
    public PackageImpl setClassLoaderName(@Nullable String classLoaderName) {
        this.classLoaderName = classLoaderName;
        return this;
    }

    @Override
    public PackageImpl setApplicationClassName(@Nullable String className) {
        this.className = className == null ? null : className.trim();
        return this;
    }

    @Override
    public PackageImpl setCompatibleWidthLimitDp(int value) {
        compatibleWidthLimitDp = value;
        return this;
    }

    @Override
    public PackageImpl setCompileSdkVersion(int value) {
        compileSdkVersion = value;
        return this;
    }

    @Override
    public ParsingPackage setCompileSdkVersionCodeName(String compileSdkVersionCodeName) {
        this.compileSdkVersionCodeName = compileSdkVersionCodeName;
        return this;
    }

    @Override
    public PackageImpl setCrossProfile(boolean value) {
        return setBoolean(Booleans.CROSS_PROFILE, value);
    }

    @Override
    public PackageImpl setDataExtractionRulesResourceId(int value) {
        dataExtractionRules = value;
        return this;
    }

    @Override
    public PackageImpl setDebuggable(boolean value) {
        return setBoolean(Booleans.DEBUGGABLE, value);
    }

    @Override
    public PackageImpl setDescriptionResourceId(int value) {
        descriptionRes = value;
        return this;
    }

    @Override
    public PackageImpl setEnabled(boolean value) {
        return setBoolean(Booleans.ENABLED, value);
    }

    @Override
    public PackageImpl setExternalStorage(boolean value) {
        return setBoolean(Booleans.EXTERNAL_STORAGE, value);
    }

    @Override
    public PackageImpl setExtractNativeLibrariesRequested(boolean value) {
        return setBoolean(Booleans.EXTRACT_NATIVE_LIBS, value);
    }

    @Override
    public PackageImpl setForceQueryable(boolean value) {
        return setBoolean(Booleans.FORCE_QUERYABLE, value);
    }

    @Override
    public PackageImpl setFullBackupContentResourceId(int value) {
        fullBackupContent = value;
        return this;
    }

    @Override
    public PackageImpl setFullBackupOnly(boolean value) {
        return setBoolean(Booleans.FULL_BACKUP_ONLY, value);
    }

    @Override
    public PackageImpl setGame(boolean value) {
        return setBoolean(Booleans.GAME, value);
    }

    @Override
    public PackageImpl setGwpAsanMode(@ApplicationInfo.GwpAsanMode int value) {
        gwpAsanMode = value;
        return this;
    }

    @Override
    public PackageImpl setDeclaredHavingCode(boolean value) {
        return setBoolean(Booleans.HAS_CODE, value);
    }

    @Override
    public PackageImpl setHasDomainUrls(boolean value) {
        return setBoolean(Booleans.HAS_DOMAIN_URLS, value);
    }

    @Override
    public PackageImpl setUserDataFragile(boolean value) {
        return setBoolean(Booleans.HAS_FRAGILE_USER_DATA, value);
    }

    @Override
    public PackageImpl setIconResourceId(int value) {
        iconRes = value;
        return this;
    }

    @Override
    public PackageImpl setInstallLocation(int value) {
        installLocation = value;
        return this;
    }

    @Override
    public PackageImpl setIsolatedSplitLoading(boolean value) {
        return setBoolean(Booleans.ISOLATED_SPLIT_LOADING, value);
    }

    @Override
    public PackageImpl setKillAfterRestoreAllowed(boolean value) {
        return setBoolean(Booleans.KILL_AFTER_RESTORE, value);
    }

    @Override
    public ParsingPackage setKnownActivityEmbeddingCerts(@NonNull Set<String> knownEmbeddingCerts) {
        mKnownActivityEmbeddingCerts = knownEmbeddingCerts;
        return this;
    }

    @Override
    public PackageImpl setLabelResourceId(int value) {
        labelRes = value;
        return this;
    }

    @Override
    public PackageImpl setLargeHeap(boolean value) {
        return setBoolean(Booleans.LARGE_HEAP, value);
    }

    @Override
    public PackageImpl setLargestWidthLimitDp(int value) {
        largestWidthLimitDp = value;
        return this;
    }

    @Override
    public PackageImpl setLeavingSharedUser(boolean value) {
        return setBoolean(Booleans.LEAVING_SHARED_UID, value);
    }

    @Override
    public PackageImpl setLocaleConfigResourceId(int value) {
        mLocaleConfigRes = value;
        return this;
    }

    @Override
    public PackageImpl setLogoResourceId(int value) {
        logo = value;
        return this;
    }

    @Override
    public PackageImpl setManageSpaceActivityName(@Nullable String manageSpaceActivityName) {
        this.manageSpaceActivityName = manageSpaceActivityName;
        return this;
    }

    @Override
    public PackageImpl setMaxAspectRatio(float value) {
        maxAspectRatio = value;
        return this;
    }

    @Override
    public PackageImpl setMaxSdkVersion(int value) {
        maxSdkVersion = value;
        return this;
    }

    @Override
    public PackageImpl setMemtagMode(@ApplicationInfo.MemtagMode int value) {
        memtagMode = value;
        return this;
    }

    @Override
    public PackageImpl setMetaData(@Nullable Bundle value) {
        metaData = value;
        return this;
    }

    @Override
    public PackageImpl setMinAspectRatio(float value) {
        minAspectRatio = value;
        return this;
    }

    @Override
    public PackageImpl setMinExtensionVersions(@Nullable SparseIntArray value) {
        minExtensionVersions = value;
        return this;
    }

    @Override
    public PackageImpl setMinSdkVersion(int value) {
        minSdkVersion = value;
        return this;
    }

    @Override
    public PackageImpl setMultiArch(boolean value) {
        return setBoolean(Booleans.MULTI_ARCH, value);
    }

    @Override
    public PackageImpl setNativeHeapZeroInitialized(
            @ApplicationInfo.NativeHeapZeroInitialized int value) {
        nativeHeapZeroInitialized = value;
        return this;
    }

    @Override
    public PackageImpl setNetworkSecurityConfigResourceId(int value) {
        networkSecurityConfigRes = value;
        return this;
    }

    @Override
    public PackageImpl setNonLocalizedLabel(@Nullable CharSequence value) {
        nonLocalizedLabel = value == null ? null : value.toString().trim();
        return this;
    }

    @Override
    public ParsingPackage setOnBackInvokedCallbackEnabled(boolean value) {
        setBoolean(Booleans.ENABLE_ON_BACK_INVOKED_CALLBACK, value);
        return this;
    }

    @Override
    public PackageImpl setResourceOverlay(boolean value) {
        return setBoolean(Booleans.OVERLAY, value);
    }

    @Override
    public PackageImpl setOverlayCategory(@Nullable String overlayCategory) {
        this.overlayCategory = overlayCategory;
        return this;
    }

    @Override
    public PackageImpl setOverlayIsStatic(boolean value) {
        return setBoolean(Booleans.OVERLAY_IS_STATIC, value);
    }

    @Override
    public PackageImpl setOverlayPriority(int value) {
        overlayPriority = value;
        return this;
    }

    @Override
    public PackageImpl setOverlayTarget(@Nullable String overlayTarget) {
        this.overlayTarget = TextUtils.safeIntern(overlayTarget);
        return this;
    }

    @Override
    public PackageImpl setOverlayTargetOverlayableName(
            @Nullable String overlayTargetOverlayableName) {
        this.overlayTargetOverlayableName = overlayTargetOverlayableName;
        return this;
    }

    @Override
    public PackageImpl setPartiallyDirectBootAware(boolean value) {
        return setBoolean(Booleans.PARTIALLY_DIRECT_BOOT_AWARE, value);
    }

    @Override
    public PackageImpl setPermission(@Nullable String permission) {
        this.permission = permission;
        return this;
    }

    @Override
    public PackageImpl setPreserveLegacyExternalStorage(boolean value) {
        return setBoolean(Booleans.PRESERVE_LEGACY_EXTERNAL_STORAGE, value);
    }

    @Override
    public PackageImpl setProcessName(String processName) {
        this.processName = processName;
        return this;
    }

    @Override
    public PackageImpl setProcesses(@NonNull Map<String,ParsedProcess> value) {
        processes = value;
        return this;
    }

    @Override
    public PackageImpl setProfileable(boolean value) {
        return setBoolean(Booleans.DISALLOW_PROFILING, !value);
    }

    @Override
    public PackageImpl setProfileableByShell(boolean value) {
        return setBoolean(Booleans.PROFILEABLE_BY_SHELL, value);
    }

    @Override
    public PackageImpl setRequestForegroundServiceExemption(boolean value) {
        return setBoolean(Booleans.REQUEST_FOREGROUND_SERVICE_EXEMPTION, value);
    }

    @Override
    public PackageImpl setRequestLegacyExternalStorage(boolean value) {
        return setBoolean(Booleans.REQUEST_LEGACY_EXTERNAL_STORAGE, value);
    }

    @Override
    public PackageImpl setRequestRawExternalStorageAccess(@Nullable Boolean value) {
        requestRawExternalStorageAccess = value;
        return this;
    }

    @Override
    public PackageImpl setRequiredAccountType(@Nullable String requiredAccountType) {
        this.requiredAccountType = TextUtils.nullIfEmpty(requiredAccountType);
        return this;
    }

    @Override
    public PackageImpl setRequiredForAllUsers(boolean value) {
        return setBoolean(Booleans.REQUIRED_FOR_ALL_USERS, value);
    }

    @Override
    public PackageImpl setRequiresSmallestWidthDp(int value) {
        requiresSmallestWidthDp = value;
        return this;
    }

    @Override
    public ParsingPackage setResetEnabledSettingsOnAppDataCleared(
            boolean resetEnabledSettingsOnAppDataCleared) {
        setBoolean(Booleans.RESET_ENABLED_SETTINGS_ON_APP_DATA_CLEARED,
                resetEnabledSettingsOnAppDataCleared);
        return this;
    }

    @Override
    public PackageImpl setResizeable(int resizeable) {
        if (resizeable == 1) {
            return this;
        }

        this.resizeable = resizeable < 0;
        return this;
    }

    @Override
    public PackageImpl setResizeableActivity(@Nullable Boolean value) {
        resizeableActivity = value;
        return this;
    }

    @Override
    public PackageImpl setResizeableActivityViaSdkVersion(boolean value) {
        return setBoolean(Booleans.RESIZEABLE_ACTIVITY_VIA_SDK_VERSION, value);
    }

    @Override
    public PackageImpl setRestoreAnyVersion(boolean value) {
        return setBoolean(Booleans.RESTORE_ANY_VERSION, value);
    }

    @Override
    public PackageImpl setRestrictedAccountType(@Nullable String restrictedAccountType) {
        this.restrictedAccountType = restrictedAccountType;
        return this;
    }

    @Override
    public PackageImpl setRoundIconResourceId(int value) {
        roundIconRes = value;
        return this;
    }

    @Override
    public PackageImpl setSdkLibraryName(String sdkLibraryName) {
        this.sdkLibraryName = TextUtils.safeIntern(sdkLibraryName);
        return this;
    }

    @Override
    public PackageImpl setSdkLibVersionMajor(int sdkLibVersionMajor) {
        this.sdkLibVersionMajor = sdkLibVersionMajor;
        return this;
    }

    @Override
    public PackageImpl setSdkLibrary(boolean value) {
        return setBoolean(Booleans.SDK_LIBRARY, value);
    }

    @Override
    public PackageImpl setSharedUserId(String sharedUserId) {
        this.sharedUserId = TextUtils.safeIntern(sharedUserId);
        return this;
    }

    @Override
    public PackageImpl setSharedUserLabelResourceId(int value) {
        sharedUserLabel = value;
        return this;
    }

    @Override
    public PackageImpl setSplitClassLoaderName(int splitIndex, String classLoaderName) {
        this.splitClassLoaderNames[splitIndex] = classLoaderName;
        return this;
    }

    @Override
    public PackageImpl setSplitHasCode(int splitIndex, boolean splitHasCode) {
        this.splitFlags[splitIndex] = splitHasCode
                ? this.splitFlags[splitIndex] | ApplicationInfo.FLAG_HAS_CODE
                : this.splitFlags[splitIndex] & ~ApplicationInfo.FLAG_HAS_CODE;
        return this;
    }

    @Override
    public PackageImpl setStaticSharedLibraryName(String staticSharedLibraryName) {
        this.staticSharedLibraryName = TextUtils.safeIntern(staticSharedLibraryName);
        return this;
    }

    @Override
    public PackageImpl setStaticSharedLibraryVersion(long value) {
        staticSharedLibVersion = value;
        return this;
    }

    @Override
    public PackageImpl setStaticSharedLibrary(boolean value) {
        return setBoolean(Booleans.STATIC_SHARED_LIBRARY, value);
    }

    @Override
    public PackageImpl setExtraLargeScreensSupported(int supportsExtraLargeScreens) {
        if (supportsExtraLargeScreens == 1) {
            return this;
        }

        this.supportsExtraLargeScreens = supportsExtraLargeScreens < 0;
        return this;
    }

    @Override
    public PackageImpl setLargeScreensSupported(int supportsLargeScreens) {
        if (supportsLargeScreens == 1) {
            return this;
        }

        this.supportsLargeScreens = supportsLargeScreens < 0;
        return this;
    }

    @Override
    public PackageImpl setNormalScreensSupported(int supportsNormalScreens) {
        if (supportsNormalScreens == 1) {
            return this;
        }

        this.supportsNormalScreens = supportsNormalScreens < 0;
        return this;
    }

    @Override
    public PackageImpl setRtlSupported(boolean value) {
        return setBoolean(Booleans.SUPPORTS_RTL, value);
    }

    @Override
    public PackageImpl setSmallScreensSupported(int supportsSmallScreens) {
        if (supportsSmallScreens == 1) {
            return this;
        }

        this.supportsSmallScreens = supportsSmallScreens < 0;
        return this;
    }

    @Override
    public PackageImpl setTargetSandboxVersion(int value) {
        targetSandboxVersion = value;
        return this;
    }

    @Override
    public PackageImpl setTargetSdkVersion(int value) {
        targetSdkVersion = value;
        return this;
    }

    @Override
    public PackageImpl setTaskAffinity(@Nullable String taskAffinity) {
        this.taskAffinity = taskAffinity;
        return this;
    }

    @Override
    public PackageImpl setTestOnly(boolean value) {
        return setBoolean(Booleans.TEST_ONLY, value);
    }

    @Override
    public PackageImpl setThemeResourceId(int value) {
        theme = value;
        return this;
    }

    @Override
    public PackageImpl setUiOptions(int value) {
        uiOptions = value;
        return this;
    }

    @Override
    public PackageImpl setUpgradeKeySets(@NonNull Set<String> value) {
        upgradeKeySets = value;
        return this;
    }

    @Override
    public PackageImpl set32BitAbiPreferred(boolean value) {
        return setBoolean(Booleans.USE_32_BIT_ABI, value);
    }

    @Override
    public PackageImpl setUseEmbeddedDex(boolean value) {
        return setBoolean(Booleans.USE_EMBEDDED_DEX, value);
    }

    @Override
    public PackageImpl setCleartextTrafficAllowed(boolean value) {
        return setBoolean(Booleans.USES_CLEARTEXT_TRAFFIC, value);
    }

    @Override
    public PackageImpl setNonSdkApiRequested(boolean value) {
        return setBoolean(Booleans.USES_NON_SDK_API, value);
    }

    @Override
    public PackageImpl setVersionName(String versionName) {
        this.versionName = versionName;
        return this;
    }

    @Override
    public PackageImpl setVisibleToInstantApps(boolean value) {
        return setBoolean(Booleans.VISIBLE_TO_INSTANT_APPS, value);
    }

    @Override
    public PackageImpl setVmSafeMode(boolean value) {
        return setBoolean(Booleans.VM_SAFE_MODE, value);
    }

    @Override
    public PackageImpl setVolumeUuid(@Nullable String volumeUuid) {
        this.volumeUuid = TextUtils.safeIntern(volumeUuid);
        return this;
    }

    @Override
    public PackageImpl setZygotePreloadName(@Nullable String zygotePreloadName) {
        this.zygotePreloadName = zygotePreloadName;
        return this;
    }

    @Override
    public PackageImpl sortActivities() {
        Collections.sort(this.activities, ORDER_COMPARATOR);
        return this;
    }

    @Override
    public PackageImpl sortReceivers() {
        Collections.sort(this.receivers, ORDER_COMPARATOR);
        return this;
    }

    @Override
    public PackageImpl sortServices() {
        Collections.sort(this.services, ORDER_COMPARATOR);
        return this;
    }

    public ApplicationInfo toAppInfoWithoutStateWithoutFlags() {
        ApplicationInfo appInfo = new ApplicationInfo();

        // Lines that are commented below are state related and should not be assigned here.
        // They are left in as placeholders, since there is no good backwards compatible way to
        // separate these.
        appInfo.appComponentFactory = appComponentFactory;
        appInfo.backupAgentName = backupAgentName;
        appInfo.banner = banner;
        appInfo.category = category;
        appInfo.classLoaderName = classLoaderName;
        appInfo.className = className;
        appInfo.compatibleWidthLimitDp = compatibleWidthLimitDp;
        appInfo.compileSdkVersion = compileSdkVersion;
        appInfo.compileSdkVersionCodename = compileSdkVersionCodeName;
//        appInfo.credentialProtectedDataDir
        appInfo.crossProfile = isCrossProfile();
//        appInfo.dataDir
        appInfo.descriptionRes = descriptionRes;
//        appInfo.deviceProtectedDataDir
        appInfo.enabled = getBoolean(Booleans.ENABLED);
//        appInfo.enabledSetting
        appInfo.fullBackupContent = fullBackupContent;
        appInfo.dataExtractionRulesRes = dataExtractionRules;
        // TODO(b/135203078): See PackageImpl#getHiddenApiEnforcementPolicy
//        appInfo.mHiddenApiPolicy
//        appInfo.hiddenUntilInstalled
        appInfo.icon =
                (ParsingPackageUtils.sUseRoundIcon && roundIconRes != 0) ? roundIconRes : iconRes;
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
//        appInfo.nativeLibraryDir
//        appInfo.nativeLibraryRootDir
//        appInfo.nativeLibraryRootRequiresIsa
        appInfo.networkSecurityConfigRes = networkSecurityConfigRes;
        appInfo.nonLocalizedLabel = nonLocalizedLabel;
        appInfo.packageName = packageName;
        appInfo.permission = permission;
//        appInfo.primaryCpuAbi
        appInfo.processName = getProcessName();
        appInfo.requiresSmallestWidthDp = requiresSmallestWidthDp;
//        appInfo.resourceDirs
//        appInfo.secondaryCpuAbi
//        appInfo.secondaryNativeLibraryDir
//        appInfo.seInfo
//        appInfo.seInfoUser
//        appInfo.sharedLibraryFiles
//        appInfo.sharedLibraryInfos
//        appInfo.showUserIcon
        appInfo.splitClassLoaderNames = splitClassLoaderNames;
        appInfo.splitDependencies = (splitDependencies == null || splitDependencies.size() == 0)
                ? null : splitDependencies;
        appInfo.splitNames = splitNames;
        appInfo.storageUuid = mStorageUuid;
        appInfo.targetSandboxVersion = targetSandboxVersion;
        appInfo.targetSdkVersion = targetSdkVersion;
        appInfo.taskAffinity = taskAffinity;
        appInfo.theme = theme;
//        appInfo.uid
        appInfo.uiOptions = uiOptions;
        appInfo.volumeUuid = volumeUuid;
        appInfo.zygotePreloadName = zygotePreloadName;
        appInfo.setGwpAsanMode(gwpAsanMode);
        appInfo.setMemtagMode(memtagMode);
        appInfo.setNativeHeapZeroInitialized(nativeHeapZeroInitialized);
        appInfo.setRequestRawExternalStorageAccess(requestRawExternalStorageAccess);
        appInfo.setBaseCodePath(mBaseApkPath);
        appInfo.setBaseResourcePath(mBaseApkPath);
        appInfo.setCodePath(mPath);
        appInfo.setResourcePath(mPath);
        appInfo.setSplitCodePaths(ArrayUtils.size(splitCodePaths) == 0 ? null : splitCodePaths);
        appInfo.setSplitResourcePaths(ArrayUtils.size(splitCodePaths) == 0 ? null : splitCodePaths);
        appInfo.setVersionCode(mLongVersionCode);
        appInfo.setAppClassNamesByProcess(buildAppClassNamesByProcess());
        appInfo.setLocaleConfigRes(mLocaleConfigRes);
        if (!mKnownActivityEmbeddingCerts.isEmpty()) {
            appInfo.setKnownActivityEmbeddingCerts(mKnownActivityEmbeddingCerts);
        }

        return appInfo;
    }

    private PackageImpl setBoolean(@Booleans.Flags long flag, boolean value) {
        if (value) {
            mBooleans |= flag;
        } else {
            mBooleans &= ~flag;
        }
        return this;
    }

    private boolean getBoolean(@Booleans.Flags long flag) {
        return (mBooleans & flag) != 0;
    }

    private PackageImpl setBoolean2(@Booleans2.Flags long flag, boolean value) {
        if (value) {
            mBooleans2 |= flag;
        } else {
            mBooleans2 &= ~flag;
        }
        return this;
    }

    private boolean getBoolean2(@Booleans2.Flags long flag) {
        return (mBooleans2 & flag) != 0;
    }

    // Derived fields
    private int mBaseAppInfoFlags;
    private int mBaseAppInfoPrivateFlags;
    private int mBaseAppInfoPrivateFlagsExt;
    private String mBaseAppDataCredentialProtectedDirForSystemUser;
    private String mBaseAppDataDeviceProtectedDirForSystemUser;

    @VisibleForTesting
    public PackageImpl(@NonNull String packageName, @NonNull String baseApkPath,
            @NonNull String path, @Nullable TypedArray manifestArray, boolean isCoreApp) {
        this.packageName = TextUtils.safeIntern(packageName);
        this.mBaseApkPath = baseApkPath;
        this.mPath = path;

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
            setCompileSdkVersionCodeName(manifestArray.getNonConfigurationString(
                    R.styleable.AndroidManifest_compileSdkVersionCodename, 0));

            setIsolatedSplitLoading(manifestArray.getBoolean(
                    R.styleable.AndroidManifest_isolatedSplits, false));

        }
        this.manifestPackageName = this.packageName;
        setBoolean(Booleans.CORE_APP, isCoreApp);
    }

    @Override
    public PackageImpl hideAsParsed() {
        assignDerivedFields();
        return this;
    }

    @Override
    public AndroidPackageInternal hideAsFinal() {
        if (mStorageUuid == null) {
            assignDerivedFields();
        }
        assignDerivedFields2();
        makeImmutable();
        return this;
    }

    private static String[] sortLibraries(List<String> libraryNames) {
        int size = libraryNames.size();
        if (size == 0) {
            return EmptyArray.STRING;
        }
        var arr = libraryNames.toArray(EmptyArray.STRING);
        Arrays.sort(arr);
        return arr;
    }

    private void assignDerivedFields2() {
        mBaseAppInfoFlags = PackageInfoUtils.appInfoFlags(this, null);
        mBaseAppInfoPrivateFlags = PackageInfoUtils.appInfoPrivateFlags(this, null);
        mBaseAppInfoPrivateFlagsExt = PackageInfoUtils.appInfoPrivateFlagsExt(this, null);
        String baseAppDataDir = Environment.getDataDirectoryPath(getVolumeUuid()) + File.separator;
        String systemUserSuffix = File.separator + UserHandle.USER_SYSTEM + File.separator;
        mBaseAppDataCredentialProtectedDirForSystemUser = TextUtils.safeIntern(
                baseAppDataDir + Environment.DIR_USER_CE + systemUserSuffix);
        mBaseAppDataDeviceProtectedDirForSystemUser = TextUtils.safeIntern(
                baseAppDataDir + Environment.DIR_USER_DE + systemUserSuffix);
    }

    private void makeImmutable() {
        usesLibraries = Collections.unmodifiableList(usesLibraries);
        usesOptionalLibraries = Collections.unmodifiableList(usesOptionalLibraries);
        usesNativeLibraries = Collections.unmodifiableList(usesNativeLibraries);
        usesOptionalNativeLibraries = Collections.unmodifiableList(usesOptionalNativeLibraries);
        originalPackages = Collections.unmodifiableList(originalPackages);
        adoptPermissions = Collections.unmodifiableList(adoptPermissions);
        requestedPermissions = Collections.unmodifiableSet(requestedPermissions);
        protectedBroadcasts = Collections.unmodifiableList(protectedBroadcasts);
        apexSystemServices = Collections.unmodifiableList(apexSystemServices);

        activities = Collections.unmodifiableList(activities);
        receivers = Collections.unmodifiableList(receivers);
        services = Collections.unmodifiableList(services);
        providers = Collections.unmodifiableList(providers);
        permissions = Collections.unmodifiableList(permissions);
        permissionGroups = Collections.unmodifiableList(permissionGroups);
        instrumentations = Collections.unmodifiableList(instrumentations);

        overlayables = Collections.unmodifiableMap(overlayables);
        libraryNames = Collections.unmodifiableList(libraryNames);
        usesStaticLibraries = Collections.unmodifiableList(usesStaticLibraries);
        usesSdkLibraries = Collections.unmodifiableList(usesSdkLibraries);
        configPreferences = Collections.unmodifiableList(configPreferences);
        reqFeatures = Collections.unmodifiableList(reqFeatures);
        featureGroups = Collections.unmodifiableList(featureGroups);
        usesPermissions = Collections.unmodifiableList(usesPermissions);
        usesSdkLibraries = Collections.unmodifiableList(usesSdkLibraries);
        implicitPermissions = Collections.unmodifiableSet(implicitPermissions);
        upgradeKeySets = Collections.unmodifiableSet(upgradeKeySets);
        keySetMapping = Collections.unmodifiableMap(keySetMapping);
        attributions = Collections.unmodifiableList(attributions);
        preferredActivityFilters = Collections.unmodifiableList(preferredActivityFilters);
        processes = Collections.unmodifiableMap(processes);
        mProperties = Collections.unmodifiableMap(mProperties);
        queriesIntents = Collections.unmodifiableList(queriesIntents);
        queriesPackages = Collections.unmodifiableList(queriesPackages);
        queriesProviders = Collections.unmodifiableSet(queriesProviders);
        mimeGroups = Collections.unmodifiableSet(mimeGroups);
        mKnownActivityEmbeddingCerts = Collections.unmodifiableSet(mKnownActivityEmbeddingCerts);
    }

    @Override
    public long getLongVersionCode() {
        return PackageInfo.composeLongVersionCode(versionCodeMajor, versionCode);
    }

    @Override
    public PackageImpl removePermission(int index) {
        this.permissions.remove(index);
        return this;
    }

    @Override
    public PackageImpl addUsesOptionalLibrary(int index, String libraryName) {
        this.usesOptionalLibraries = CollectionUtils.add(usesOptionalLibraries, index,
                TextUtils.safeIntern(libraryName));
        return this;
    }

    @Override
    public PackageImpl addUsesLibrary(int index, String libraryName) {
        this.usesLibraries = CollectionUtils.add(usesLibraries, index,
                TextUtils.safeIntern(libraryName));
        return this;
    }

    @Override
    public PackageImpl removeUsesLibrary(String libraryName) {
        this.usesLibraries = CollectionUtils.remove(this.usesLibraries, libraryName);
        return this;
    }

    @Override
    public PackageImpl removeUsesOptionalLibrary(String libraryName) {
        this.usesOptionalLibraries = CollectionUtils.remove(this.usesOptionalLibraries,
                libraryName);
        return this;
    }

    @Override
    public PackageImpl setSigningDetails(@NonNull SigningDetails value) {
        signingDetails = value;
        return this;
    }

    @Override
    public PackageImpl setRestrictUpdateHash(@Nullable byte... value) {
        restrictUpdateHash = value;
        return this;
    }

    @Override
    public PackageImpl setPersistent(boolean value) {
        setBoolean(Booleans.PERSISTENT, value);
        return this;
    }

    @Override
    public PackageImpl setDefaultToDeviceProtectedStorage(boolean value) {
        setBoolean(Booleans.DEFAULT_TO_DEVICE_PROTECTED_STORAGE, value);
        return this;
    }

    @Override
    public PackageImpl setDirectBootAware(boolean value) {
        setBoolean(Booleans.DIRECT_BOOT_AWARE, value);
        return this;
    }

    @Override
    public PackageImpl clearProtectedBroadcasts() {
        protectedBroadcasts.clear();
        return this;
    }

    @Override
    public PackageImpl clearOriginalPackages() {
        originalPackages.clear();
        return this;
    }

    @Override
    public PackageImpl clearAdoptPermissions() {
        adoptPermissions.clear();
        return this;
    }

    @Override
    public PackageImpl setPath(@NonNull String path) {
        this.mPath = path;
        return this;
    }

    // TODO(b/135203078): Move PackageManagerService#renameStaticSharedLibraryPackage
    //  into initial package parsing
    @Override
    public PackageImpl setPackageName(@NonNull String packageName) {
        this.packageName = TextUtils.safeIntern(packageName);

        int permissionsSize = permissions.size();
        for (int index = 0; index < permissionsSize; index++) {
            ComponentMutateUtils.setPackageName(permissions.get(index), this.packageName);
        }

        int permissionGroupsSize = permissionGroups.size();
        for (int index = 0; index < permissionGroupsSize; index++) {
            ComponentMutateUtils.setPackageName(permissionGroups.get(index), this.packageName);
        }

        int activitiesSize = activities.size();
        for (int index = 0; index < activitiesSize; index++) {
            ComponentMutateUtils.setPackageName(activities.get(index), this.packageName);
        }

        int receiversSize = receivers.size();
        for (int index = 0; index < receiversSize; index++) {
            ComponentMutateUtils.setPackageName(receivers.get(index), this.packageName);
        }

        int providersSize = providers.size();
        for (int index = 0; index < providersSize; index++) {
            ComponentMutateUtils.setPackageName(providers.get(index), this.packageName);
        }

        int servicesSize = services.size();
        for (int index = 0; index < servicesSize; index++) {
            ComponentMutateUtils.setPackageName(services.get(index), this.packageName);
        }

        int instrumentationsSize = instrumentations.size();
        for (int index = 0; index < instrumentationsSize; index++) {
            ComponentMutateUtils.setPackageName(instrumentations.get(index), this.packageName);
        }

        return this;
    }

    @Override
    public PackageImpl setAllComponentsDirectBootAware(boolean allComponentsDirectBootAware) {
        int activitiesSize = activities.size();
        for (int index = 0; index < activitiesSize; index++) {
            ComponentMutateUtils.setDirectBootAware(activities.get(index),
                    allComponentsDirectBootAware);
        }

        int receiversSize = receivers.size();
        for (int index = 0; index < receiversSize; index++) {
            ComponentMutateUtils.setDirectBootAware(receivers.get(index),
                    allComponentsDirectBootAware);
        }

        int providersSize = providers.size();
        for (int index = 0; index < providersSize; index++) {
            ComponentMutateUtils.setDirectBootAware(providers.get(index),
                    allComponentsDirectBootAware);
        }

        int servicesSize = services.size();
        for (int index = 0; index < servicesSize; index++) {
            ComponentMutateUtils.setDirectBootAware(services.get(index),
                    allComponentsDirectBootAware);
        }

        return this;
    }

    @Override
    public PackageImpl setBaseApkPath(@NonNull String baseApkPath) {
        this.mBaseApkPath = TextUtils.safeIntern(baseApkPath);
        return this;
    }

    @Override
    public PackageImpl setNativeLibraryDir(@Nullable String nativeLibraryDir) {
        this.nativeLibraryDir = TextUtils.safeIntern(nativeLibraryDir);
        return this;
    }

    @Override
    public PackageImpl setNativeLibraryRootDir(@Nullable String nativeLibraryRootDir) {
        this.nativeLibraryRootDir = TextUtils.safeIntern(nativeLibraryRootDir);
        return this;
    }

    @Override
    public PackageImpl setPrimaryCpuAbi(@Nullable String primaryCpuAbi) {
        this.primaryCpuAbi = TextUtils.safeIntern(primaryCpuAbi);
        return this;
    }

    @Override
    public PackageImpl setSecondaryCpuAbi(@Nullable String secondaryCpuAbi) {
        this.secondaryCpuAbi = TextUtils.safeIntern(secondaryCpuAbi);
        return this;
    }

    @Override
    public PackageImpl setSecondaryNativeLibraryDir(@Nullable String secondaryNativeLibraryDir) {
        this.secondaryNativeLibraryDir = TextUtils.safeIntern(secondaryNativeLibraryDir);
        return this;
    }

    @Override
    public PackageImpl setSplitCodePaths(@Nullable String[] splitCodePaths) {
        this.splitCodePaths = splitCodePaths;
        if (splitCodePaths != null) {
            int size = splitCodePaths.length;
            for (int index = 0; index < size; index++) {
                this.splitCodePaths[index] = TextUtils.safeIntern(this.splitCodePaths[index]);
            }
        }
        return this;
    }

    @Override
    public PackageImpl capPermissionPriorities() {
        int size = permissionGroups.size();
        for (int index = size - 1; index >= 0; --index) {
            // TODO(b/135203078): Builder/immutability
            ComponentMutateUtils.setPriority(permissionGroups.get(index), 0);
        }
        return this;
    }

    @Override
    public PackageImpl markNotActivitiesAsNotExportedIfSingleUser() {
        // ignore export request for single user receivers
        int receiversSize = receivers.size();
        for (int index = 0; index < receiversSize; index++) {
            ParsedActivity receiver = receivers.get(index);
            if ((receiver.getFlags() & ActivityInfo.FLAG_SINGLE_USER) != 0) {
                ComponentMutateUtils.setExported(receiver, false);
            }
        }

        // ignore export request for single user services
        int servicesSize = services.size();
        for (int index = 0; index < servicesSize; index++) {
            ParsedService service = services.get(index);
            if ((service.getFlags() & ActivityInfo.FLAG_SINGLE_USER) != 0) {
                ComponentMutateUtils.setExported(service, false);
            }
        }

        // ignore export request for single user providers
        int providersSize = providers.size();
        for (int index = 0; index < providersSize; index++) {
            ParsedProvider provider = providers.get(index);
            if ((provider.getFlags() & ActivityInfo.FLAG_SINGLE_USER) != 0) {
                ComponentMutateUtils.setExported(provider, false);
            }
        }

        return this;
    }

    @Override
    public PackageImpl setCoreApp(boolean coreApp) {
        return setBoolean(Booleans.CORE_APP, coreApp);
    }

    @Override
    public PackageImpl setVersionCode(int versionCode) {
        this.versionCode = versionCode;
        return this;
    }

    @Override
    public PackageImpl setVersionCodeMajor(int versionCodeMajor) {
        this.versionCodeMajor = versionCodeMajor;
        return this;
    }

    @Override
    public ApplicationInfo toAppInfoWithoutState() {
        ApplicationInfo appInfo = toAppInfoWithoutStateWithoutFlags();
        appInfo.flags = mBaseAppInfoFlags;
        appInfo.privateFlags = mBaseAppInfoPrivateFlags;
        appInfo.privateFlagsExt = mBaseAppInfoPrivateFlagsExt;
        appInfo.nativeLibraryDir = nativeLibraryDir;
        appInfo.nativeLibraryRootDir = nativeLibraryRootDir;
        appInfo.nativeLibraryRootRequiresIsa = nativeLibraryRootRequiresIsa;
        appInfo.primaryCpuAbi = primaryCpuAbi;
        appInfo.secondaryCpuAbi = secondaryCpuAbi;
        appInfo.secondaryNativeLibraryDir = secondaryNativeLibraryDir;
        appInfo.seInfoUser = SELinuxUtil.COMPLETE_STR;
        appInfo.uid = uid;
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
        dest.writeString(this.mBaseApkPath);
        dest.writeString(this.restrictedAccountType);
        dest.writeString(this.requiredAccountType);
        sForInternedString.parcel(this.overlayTarget, dest, flags);
        dest.writeString(this.overlayTargetOverlayableName);
        dest.writeString(this.overlayCategory);
        dest.writeInt(this.overlayPriority);
        sForInternedStringValueMap.parcel(this.overlayables, dest, flags);
        sForInternedString.parcel(this.sdkLibraryName, dest, flags);
        dest.writeInt(this.sdkLibVersionMajor);
        sForInternedString.parcel(this.staticSharedLibraryName, dest, flags);
        dest.writeLong(this.staticSharedLibVersion);
        sForInternedStringList.parcel(this.libraryNames, dest, flags);
        sForInternedStringList.parcel(this.usesLibraries, dest, flags);
        sForInternedStringList.parcel(this.usesOptionalLibraries, dest, flags);
        sForInternedStringList.parcel(this.usesNativeLibraries, dest, flags);
        sForInternedStringList.parcel(this.usesOptionalNativeLibraries, dest, flags);

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

        sForInternedStringList.parcel(this.usesSdkLibraries, dest, flags);
        dest.writeLongArray(this.usesSdkLibrariesVersionsMajor);
        if (this.usesSdkLibrariesCertDigests == null) {
            dest.writeInt(-1);
        } else {
            dest.writeInt(this.usesSdkLibrariesCertDigests.length);
            for (int index = 0; index < this.usesSdkLibrariesCertDigests.length; index++) {
                dest.writeStringArray(this.usesSdkLibrariesCertDigests[index]);
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
        sForInternedStringSet.parcel(this.requestedPermissions, dest, flags);
        ParsingUtils.writeParcelableList(dest, this.usesPermissions);
        sForInternedStringSet.parcel(this.implicitPermissions, dest, flags);
        sForStringSet.parcel(this.upgradeKeySets, dest, flags);
        ParsingPackageUtils.writeKeySetMapping(dest, this.keySetMapping);
        sForInternedStringList.parcel(this.protectedBroadcasts, dest, flags);
        ParsingUtils.writeParcelableList(dest, this.activities);
        ParsingUtils.writeParcelableList(dest, this.apexSystemServices);
        ParsingUtils.writeParcelableList(dest, this.receivers);
        ParsingUtils.writeParcelableList(dest, this.services);
        ParsingUtils.writeParcelableList(dest, this.providers);
        ParsingUtils.writeParcelableList(dest, this.attributions);
        ParsingUtils.writeParcelableList(dest, this.permissions);
        ParsingUtils.writeParcelableList(dest, this.permissionGroups);
        ParsingUtils.writeParcelableList(dest, this.instrumentations);
        sForIntentInfoPairs.parcel(this.preferredActivityFilters, dest, flags);
        dest.writeMap(this.processes);
        dest.writeBundle(this.metaData);
        sForInternedString.parcel(this.volumeUuid, dest, flags);
        dest.writeParcelable(this.signingDetails, flags);
        dest.writeString(this.mPath);
        dest.writeTypedList(this.queriesIntents, flags);
        sForInternedStringList.parcel(this.queriesPackages, dest, flags);
        sForInternedStringSet.parcel(this.queriesProviders, dest, flags);
        dest.writeString(this.appComponentFactory);
        dest.writeString(this.backupAgentName);
        dest.writeInt(this.banner);
        dest.writeInt(this.category);
        dest.writeString(this.classLoaderName);
        dest.writeString(this.className);
        dest.writeInt(this.compatibleWidthLimitDp);
        dest.writeInt(this.descriptionRes);
        dest.writeInt(this.fullBackupContent);
        dest.writeInt(this.dataExtractionRules);
        dest.writeInt(this.iconRes);
        dest.writeInt(this.installLocation);
        dest.writeInt(this.labelRes);
        dest.writeInt(this.largestWidthLimitDp);
        dest.writeInt(this.logo);
        dest.writeString(this.manageSpaceActivityName);
        dest.writeFloat(this.maxAspectRatio);
        dest.writeFloat(this.minAspectRatio);
        dest.writeInt(this.minSdkVersion);
        dest.writeInt(this.maxSdkVersion);
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
        sForBoolean.parcel(this.resizeableActivity, dest, flags);
        dest.writeInt(this.autoRevokePermissions);
        sForInternedStringSet.parcel(this.mimeGroups, dest, flags);
        dest.writeInt(this.gwpAsanMode);
        dest.writeSparseIntArray(this.minExtensionVersions);
        dest.writeMap(this.mProperties);
        dest.writeInt(this.memtagMode);
        dest.writeInt(this.nativeHeapZeroInitialized);
        sForBoolean.parcel(this.requestRawExternalStorageAccess, dest, flags);
        dest.writeInt(this.mLocaleConfigRes);
        sForStringSet.parcel(mKnownActivityEmbeddingCerts, dest, flags);
        sForInternedString.parcel(this.manifestPackageName, dest, flags);
        dest.writeString(this.nativeLibraryDir);
        dest.writeString(this.nativeLibraryRootDir);
        dest.writeBoolean(this.nativeLibraryRootRequiresIsa);
        sForInternedString.parcel(this.primaryCpuAbi, dest, flags);
        sForInternedString.parcel(this.secondaryCpuAbi, dest, flags);
        dest.writeString(this.secondaryNativeLibraryDir);
        dest.writeInt(this.uid);
        dest.writeLong(this.mBooleans);
        dest.writeLong(this.mBooleans2);
    }

    public PackageImpl(Parcel in) {
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
        this.mBaseApkPath = in.readString();
        this.restrictedAccountType = in.readString();
        this.requiredAccountType = in.readString();
        this.overlayTarget = sForInternedString.unparcel(in);
        this.overlayTargetOverlayableName = in.readString();
        this.overlayCategory = in.readString();
        this.overlayPriority = in.readInt();
        this.overlayables = sForInternedStringValueMap.unparcel(in);
        this.sdkLibraryName = sForInternedString.unparcel(in);
        this.sdkLibVersionMajor = in.readInt();
        this.staticSharedLibraryName = sForInternedString.unparcel(in);
        this.staticSharedLibVersion = in.readLong();
        this.libraryNames = sForInternedStringList.unparcel(in);
        this.usesLibraries = sForInternedStringList.unparcel(in);
        this.usesOptionalLibraries = sForInternedStringList.unparcel(in);
        this.usesNativeLibraries = sForInternedStringList.unparcel(in);
        this.usesOptionalNativeLibraries = sForInternedStringList.unparcel(in);

        this.usesStaticLibraries = sForInternedStringList.unparcel(in);
        this.usesStaticLibrariesVersions = in.createLongArray();
        {
            int digestsSize = in.readInt();
            if (digestsSize >= 0) {
                this.usesStaticLibrariesCertDigests = new String[digestsSize][];
                for (int index = 0; index < digestsSize; index++) {
                    this.usesStaticLibrariesCertDigests[index] = sForInternedStringArray.unparcel(
                            in);
                }
            }
        }

        this.usesSdkLibraries = sForInternedStringList.unparcel(in);
        this.usesSdkLibrariesVersionsMajor = in.createLongArray();
        {
            int digestsSize = in.readInt();
            if (digestsSize >= 0) {
                this.usesSdkLibrariesCertDigests = new String[digestsSize][];
                for (int index = 0; index < digestsSize; index++) {
                    this.usesSdkLibrariesCertDigests[index] = sForInternedStringArray.unparcel(in);
                }
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
        this.requestedPermissions = sForInternedStringSet.unparcel(in);
        this.usesPermissions = ParsingUtils.createTypedInterfaceList(in,
                ParsedUsesPermissionImpl.CREATOR);
        this.implicitPermissions = sForInternedStringSet.unparcel(in);
        this.upgradeKeySets = sForStringSet.unparcel(in);
        this.keySetMapping = ParsingPackageUtils.readKeySetMapping(in);
        this.protectedBroadcasts = sForInternedStringList.unparcel(in);

        this.activities = ParsingUtils.createTypedInterfaceList(in, ParsedActivityImpl.CREATOR);
        this.apexSystemServices = ParsingUtils.createTypedInterfaceList(in,
                ParsedApexSystemServiceImpl.CREATOR);
        this.receivers = ParsingUtils.createTypedInterfaceList(in, ParsedActivityImpl.CREATOR);
        this.services = ParsingUtils.createTypedInterfaceList(in, ParsedServiceImpl.CREATOR);
        this.providers = ParsingUtils.createTypedInterfaceList(in, ParsedProviderImpl.CREATOR);
        this.attributions = ParsingUtils.createTypedInterfaceList(in,
                ParsedAttributionImpl.CREATOR);
        this.permissions = ParsingUtils.createTypedInterfaceList(in, ParsedPermissionImpl.CREATOR);
        this.permissionGroups = ParsingUtils.createTypedInterfaceList(in,
                ParsedPermissionGroupImpl.CREATOR);
        this.instrumentations = ParsingUtils.createTypedInterfaceList(in,
                ParsedInstrumentationImpl.CREATOR);
        this.preferredActivityFilters = sForIntentInfoPairs.unparcel(in);
        this.processes = in.readHashMap(ParsedProcess.class.getClassLoader());
        this.metaData = in.readBundle(boot);
        this.volumeUuid = sForInternedString.unparcel(in);
        this.signingDetails = in.readParcelable(boot, android.content.pm.SigningDetails.class);
        this.mPath = in.readString();
        this.queriesIntents = in.createTypedArrayList(Intent.CREATOR);
        this.queriesPackages = sForInternedStringList.unparcel(in);
        this.queriesProviders = sForInternedStringSet.unparcel(in);
        this.appComponentFactory = in.readString();
        this.backupAgentName = in.readString();
        this.banner = in.readInt();
        this.category = in.readInt();
        this.classLoaderName = in.readString();
        this.className = in.readString();
        this.compatibleWidthLimitDp = in.readInt();
        this.descriptionRes = in.readInt();
        this.fullBackupContent = in.readInt();
        this.dataExtractionRules = in.readInt();
        this.iconRes = in.readInt();
        this.installLocation = in.readInt();
        this.labelRes = in.readInt();
        this.largestWidthLimitDp = in.readInt();
        this.logo = in.readInt();
        this.manageSpaceActivityName = in.readString();
        this.maxAspectRatio = in.readFloat();
        this.minAspectRatio = in.readFloat();
        this.minSdkVersion = in.readInt();
        this.maxSdkVersion = in.readInt();
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
        this.resizeableActivity = sForBoolean.unparcel(in);

        this.autoRevokePermissions = in.readInt();
        this.mimeGroups = sForInternedStringSet.unparcel(in);
        this.gwpAsanMode = in.readInt();
        this.minExtensionVersions = in.readSparseIntArray();
        this.mProperties = in.readHashMap(boot);
        this.memtagMode = in.readInt();
        this.nativeHeapZeroInitialized = in.readInt();
        this.requestRawExternalStorageAccess = sForBoolean.unparcel(in);
        this.mLocaleConfigRes = in.readInt();
        this.mKnownActivityEmbeddingCerts = sForStringSet.unparcel(in);
        this.manifestPackageName = sForInternedString.unparcel(in);
        this.nativeLibraryDir = in.readString();
        this.nativeLibraryRootDir = in.readString();
        this.nativeLibraryRootRequiresIsa = in.readBoolean();
        this.primaryCpuAbi = sForInternedString.unparcel(in);
        this.secondaryCpuAbi = sForInternedString.unparcel(in);
        this.secondaryNativeLibraryDir = in.readString();
        this.uid = in.readInt();
        this.mBooleans = in.readLong();
        this.mBooleans2 = in.readLong();

        assignDerivedFields();
        assignDerivedFields2();

        // Do not call makeImmutable here as cached parsing will need
        // to mutate this instance before it's finalized.
    }

    @NonNull
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

    @NonNull
    @Override
    public String getManifestPackageName() {
        return manifestPackageName;
    }

    public boolean isStub() {
        return getBoolean2(Booleans2.STUB);
    }

    @Nullable
    @Override
    public String getNativeLibraryDir() {
        return nativeLibraryDir;
    }

    @Nullable
    @Override
    public String getNativeLibraryRootDir() {
        return nativeLibraryRootDir;
    }

    @Override
    public boolean isNativeLibraryRootRequiresIsa() {
        return nativeLibraryRootRequiresIsa;
    }

    @Nullable
    @Override
    public String getPrimaryCpuAbi() {
        return primaryCpuAbi;
    }

    @Nullable
    @Override
    public String getSecondaryCpuAbi() {
        return secondaryCpuAbi;
    }

    @Nullable
    @Override
    public String getSecondaryNativeLibraryDir() {
        return secondaryNativeLibraryDir;
    }

    @Override
    public boolean isCoreApp() {
        return getBoolean(Booleans.CORE_APP);
    }

    @Override
    public boolean isSystem() {
        return getBoolean(Booleans.SYSTEM);
    }

    @Override
    public boolean isFactoryTest() {
        return getBoolean(Booleans.FACTORY_TEST);
    }

    @Override
    public boolean isApex() {
        return getBoolean2(Booleans2.APEX);
    }

    @Override
    public boolean isSystemExt() {
        return getBoolean(Booleans.SYSTEM_EXT);
    }

    @Override
    public boolean isPrivileged() {
        return getBoolean(Booleans.PRIVILEGED);
    }

    @Override
    public boolean isOem() {
        return getBoolean(Booleans.OEM);
    }

    @Override
    public boolean isVendor() {
        return getBoolean(Booleans.VENDOR);
    }

    @Override
    public boolean isProduct() {
        return getBoolean(Booleans.PRODUCT);
    }

    @Override
    public boolean isOdm() {
        return getBoolean(Booleans.ODM);
    }

    @Override
    public boolean isSignedWithPlatformKey() {
        return getBoolean(Booleans.SIGNED_WITH_PLATFORM_KEY);
    }

    /**
     * This is an appId, the uid if the userId is == USER_SYSTEM
     */
    @Override
    public int getUid() {
        return uid;
    }

    @Override
    public PackageImpl setStub(boolean value) {
        setBoolean2(Booleans2.STUB, value);
        return this;
    }

    @Override
    public PackageImpl setNativeLibraryRootRequiresIsa(boolean value) {
        nativeLibraryRootRequiresIsa = value;
        return this;
    }

    @Override
    public PackageImpl setSystem(boolean value) {
        setBoolean(Booleans.SYSTEM, value);
        return this;
    }

    @Override
    public PackageImpl setFactoryTest(boolean value) {
        setBoolean(Booleans.FACTORY_TEST, value);
        return this;
    }

    @Override
    public PackageImpl setApex(boolean isApex) {
        setBoolean2(Booleans2.APEX, isApex);
        return this;
    }

    @Override
    public PackageImpl setSystemExt(boolean value) {
        setBoolean(Booleans.SYSTEM_EXT, value);
        return this;
    }

    @Override
    public PackageImpl setPrivileged(boolean value) {
        setBoolean(Booleans.PRIVILEGED, value);
        return this;
    }

    @Override
    public PackageImpl setOem(boolean value) {
        setBoolean(Booleans.OEM, value);
        return this;
    }

    @Override
    public PackageImpl setVendor(boolean value) {
        setBoolean(Booleans.VENDOR, value);
        return this;
    }

    @Override
    public PackageImpl setProduct(boolean value) {
        setBoolean(Booleans.PRODUCT, value);
        return this;
    }

    @Override
    public PackageImpl setOdm(boolean value) {
        setBoolean(Booleans.ODM, value);
        return this;
    }

    @Override
    public PackageImpl setSignedWithPlatformKey(boolean value) {
        setBoolean(Booleans.SIGNED_WITH_PLATFORM_KEY, value);
        return this;
    }

    @Override
    public PackageImpl setUid(int value) {
        uid = value;
        return this;
    }

    // The following methods are explicitly not inside any interface. These are hidden under
    // PackageImpl which is only accessible to the system server. This is to prevent/discourage
    // usage of these fields outside of the utility classes.
    public String getBaseAppDataCredentialProtectedDirForSystemUser() {
        return mBaseAppDataCredentialProtectedDirForSystemUser;
    }

    public String getBaseAppDataDeviceProtectedDirForSystemUser() {
        return mBaseAppDataDeviceProtectedDirForSystemUser;
    }

    /**
     * Flags used for a internal bitset. These flags should never be persisted or exposed outside
     * of this class. It is expected that PackageCacher explicitly clears itself whenever the
     * Parcelable implementation changes such that all these flags can be re-ordered or invalidated.
     */
    private static class Booleans {
        @LongDef({
                EXTERNAL_STORAGE,
                HARDWARE_ACCELERATED,
                ALLOW_BACKUP,
                KILL_AFTER_RESTORE,
                RESTORE_ANY_VERSION,
                FULL_BACKUP_ONLY,
                PERSISTENT,
                DEBUGGABLE,
                VM_SAFE_MODE,
                HAS_CODE,
                ALLOW_TASK_REPARENTING,
                ALLOW_CLEAR_USER_DATA,
                LARGE_HEAP,
                USES_CLEARTEXT_TRAFFIC,
                SUPPORTS_RTL,
                TEST_ONLY,
                MULTI_ARCH,
                EXTRACT_NATIVE_LIBS,
                GAME,
                STATIC_SHARED_LIBRARY,
                OVERLAY,
                ISOLATED_SPLIT_LOADING,
                HAS_DOMAIN_URLS,
                PROFILEABLE_BY_SHELL,
                BACKUP_IN_FOREGROUND,
                USE_EMBEDDED_DEX,
                DEFAULT_TO_DEVICE_PROTECTED_STORAGE,
                DIRECT_BOOT_AWARE,
                PARTIALLY_DIRECT_BOOT_AWARE,
                RESIZEABLE_ACTIVITY_VIA_SDK_VERSION,
                ALLOW_CLEAR_USER_DATA_ON_FAILED_RESTORE,
                ALLOW_AUDIO_PLAYBACK_CAPTURE,
                REQUEST_LEGACY_EXTERNAL_STORAGE,
                USES_NON_SDK_API,
                HAS_FRAGILE_USER_DATA,
                CANT_SAVE_STATE,
                ALLOW_NATIVE_HEAP_POINTER_TAGGING,
                PRESERVE_LEGACY_EXTERNAL_STORAGE,
                REQUIRED_FOR_ALL_USERS,
                OVERLAY_IS_STATIC,
                USE_32_BIT_ABI,
                VISIBLE_TO_INSTANT_APPS,
                FORCE_QUERYABLE,
                CROSS_PROFILE,
                ENABLED,
                DISALLOW_PROFILING,
                REQUEST_FOREGROUND_SERVICE_EXEMPTION,
                ATTRIBUTIONS_ARE_USER_VISIBLE,
                RESET_ENABLED_SETTINGS_ON_APP_DATA_CLEARED,
                SDK_LIBRARY,
                CORE_APP,
                SYSTEM,
                FACTORY_TEST,
                SYSTEM_EXT,
                PRIVILEGED,
                OEM,
                VENDOR,
                PRODUCT,
                ODM,
                SIGNED_WITH_PLATFORM_KEY,
                NATIVE_LIBRARY_ROOT_REQUIRES_ISA,
        })
        public @interface Flags {}

        private static final long EXTERNAL_STORAGE = 1L;
        private static final long HARDWARE_ACCELERATED = 1L << 1;
        private static final long ALLOW_BACKUP = 1L << 2;
        private static final long KILL_AFTER_RESTORE = 1L << 3;
        private static final long RESTORE_ANY_VERSION = 1L << 4;
        private static final long FULL_BACKUP_ONLY = 1L << 5;
        private static final long PERSISTENT = 1L << 6;
        private static final long DEBUGGABLE = 1L << 7;
        private static final long VM_SAFE_MODE = 1L << 8;
        private static final long HAS_CODE = 1L << 9;
        private static final long ALLOW_TASK_REPARENTING = 1L << 10;
        private static final long ALLOW_CLEAR_USER_DATA = 1L << 11;
        private static final long LARGE_HEAP = 1L << 12;
        private static final long USES_CLEARTEXT_TRAFFIC = 1L << 13;
        private static final long SUPPORTS_RTL = 1L << 14;
        private static final long TEST_ONLY = 1L << 15;
        private static final long MULTI_ARCH = 1L << 16;
        private static final long EXTRACT_NATIVE_LIBS = 1L << 17;
        private static final long GAME = 1L << 18;
        private static final long STATIC_SHARED_LIBRARY = 1L << 19;
        private static final long OVERLAY = 1L << 20;
        private static final long ISOLATED_SPLIT_LOADING = 1L << 21;
        private static final long HAS_DOMAIN_URLS = 1L << 22;
        private static final long PROFILEABLE_BY_SHELL = 1L << 23;
        private static final long BACKUP_IN_FOREGROUND = 1L << 24;
        private static final long USE_EMBEDDED_DEX = 1L << 25;
        private static final long DEFAULT_TO_DEVICE_PROTECTED_STORAGE = 1L << 26;
        private static final long DIRECT_BOOT_AWARE = 1L << 27;
        private static final long PARTIALLY_DIRECT_BOOT_AWARE = 1L << 28;
        private static final long RESIZEABLE_ACTIVITY_VIA_SDK_VERSION = 1L << 29;
        private static final long ALLOW_CLEAR_USER_DATA_ON_FAILED_RESTORE = 1L << 30;
        private static final long ALLOW_AUDIO_PLAYBACK_CAPTURE = 1L << 31;
        private static final long REQUEST_LEGACY_EXTERNAL_STORAGE = 1L << 32;
        private static final long USES_NON_SDK_API = 1L << 33;
        private static final long HAS_FRAGILE_USER_DATA = 1L << 34;
        private static final long CANT_SAVE_STATE = 1L << 35;
        private static final long ALLOW_NATIVE_HEAP_POINTER_TAGGING = 1L << 36;
        private static final long PRESERVE_LEGACY_EXTERNAL_STORAGE = 1L << 37;
        private static final long REQUIRED_FOR_ALL_USERS = 1L << 38;
        private static final long OVERLAY_IS_STATIC = 1L << 39;
        private static final long USE_32_BIT_ABI = 1L << 40;
        private static final long VISIBLE_TO_INSTANT_APPS = 1L << 41;
        private static final long FORCE_QUERYABLE = 1L << 42;
        private static final long CROSS_PROFILE = 1L << 43;
        private static final long ENABLED = 1L << 44;
        private static final long DISALLOW_PROFILING = 1L << 45;
        private static final long REQUEST_FOREGROUND_SERVICE_EXEMPTION = 1L << 46;
        private static final long ATTRIBUTIONS_ARE_USER_VISIBLE = 1L << 47;
        private static final long RESET_ENABLED_SETTINGS_ON_APP_DATA_CLEARED = 1L << 48;
        private static final long SDK_LIBRARY = 1L << 49;
        private static final long ENABLE_ON_BACK_INVOKED_CALLBACK = 1L << 50;
        private static final long LEAVING_SHARED_UID = 1L << 51;
        private static final long CORE_APP = 1L << 52;
        private static final long SYSTEM = 1L << 53;
        private static final long FACTORY_TEST = 1L << 54;
        private static final long SYSTEM_EXT = 1L << 56;
        private static final long PRIVILEGED = 1L << 57;
        private static final long OEM = 1L << 58;
        private static final long VENDOR = 1L << 59;
        private static final long PRODUCT = 1L << 60;
        private static final long ODM = 1L << 61;
        private static final long SIGNED_WITH_PLATFORM_KEY = 1L << 62;
        private static final long NATIVE_LIBRARY_ROOT_REQUIRES_ISA = 1L << 63;
    }

    private static class Booleans2 {
        @LongDef({
                STUB,
                APEX,
        })
        public @interface Flags {}

        private static final long STUB = 1L;
        private static final long APEX = 1L << 1;
    }
}
