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

package com.android.server.pm.pkg.parsing;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;

import android.annotation.CallSuper;
import android.annotation.LongDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureGroupInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.Property;
import android.content.pm.SigningDetails;
import android.content.res.TypedArray;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
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
import com.android.internal.util.Parcelling.BuiltIn.ForBoolean;
import com.android.internal.util.Parcelling.BuiltIn.ForInternedString;
import com.android.internal.util.Parcelling.BuiltIn.ForInternedStringArray;
import com.android.internal.util.Parcelling.BuiltIn.ForInternedStringList;
import com.android.internal.util.Parcelling.BuiltIn.ForInternedStringSet;
import com.android.internal.util.Parcelling.BuiltIn.ForInternedStringValueMap;
import com.android.internal.util.Parcelling.BuiltIn.ForStringSet;
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

import libcore.util.EmptyArray;

import java.security.PublicKey;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
public class ParsingPackageImpl implements ParsingPackage, ParsingPackageHidden, Parcelable {

    private static final SparseArray<int[]> EMPTY_INT_ARRAY_SPARSE_ARRAY = new SparseArray<>();
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
    public static ForInternedStringSet sForInternedStringSet =
            Parcelling.Cache.getOrCreate(ForInternedStringSet.class);
    protected static ParsingUtils.StringPairListParceler sForIntentInfoPairs =
            new ParsingUtils.StringPairListParceler();

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

    @NonNull
    protected String mBaseApkPath;

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
    @DataClass.ParcelWith(ForInternedStringValueMap.class)
    private Map<String, String> overlayables = emptyMap();

    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String sdkLibName;
    private int sdkLibVersionMajor;
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
    protected List<String> usesNativeLibraries = emptyList();
    @NonNull
    @DataClass.ParcelWith(ForInternedStringList.class)
    protected List<String> usesOptionalNativeLibraries = emptyList();

    @NonNull
    @DataClass.ParcelWith(ForInternedStringList.class)
    private List<String> usesStaticLibraries = emptyList();
    @Nullable
    private long[] usesStaticLibrariesVersions;
    @Nullable
    private String[][] usesStaticLibrariesCertDigests;

    @NonNull
    @DataClass.ParcelWith(ForInternedStringList.class)
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
    @DataClass.ParcelWith(ForInternedStringList.class)
    protected List<String> originalPackages = emptyList();
    @NonNull
    @DataClass.ParcelWith(ForInternedStringList.class)
    protected List<String> adoptPermissions = emptyList();
    /**
     * @deprecated consider migrating to {@link #getUsesPermissions} which has
     *             more parsed details, such as flags
     */
    @NonNull
    @Deprecated
    @DataClass.ParcelWith(ForInternedStringList.class)
    protected List<String> requestedPermissions = emptyList();

    @NonNull
    private List<ParsedUsesPermission> usesPermissions = emptyList();

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
    protected List<ParsedApexSystemService> apexSystemServices = emptyList();

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
    private Map<String, Property> mProperties = emptyMap();

    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    protected String volumeUuid;
    @Nullable
    private SigningDetails signingDetails;

    @NonNull
    @DataClass.ParcelWith(ForInternedString.class)
    protected String mPath;

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
     * @see ParsingPackageRead#getResizeableActivity()
     */
    @Nullable
    @DataClass.ParcelWith(ForBoolean.class)
    private Boolean resizeableActivity;

    private int autoRevokePermissions;

    @ApplicationInfo.GwpAsanMode
    private int gwpAsanMode;

    @ApplicationInfo.MemtagMode
    private int memtagMode;

    @ApplicationInfo.NativeHeapZeroInitialized
    private int nativeHeapZeroInitialized;

    @Nullable
    @DataClass.ParcelWith(ForBoolean.class)
    private Boolean requestRawExternalStorageAccess;

    // TODO(chiuwinson): Non-null
    @Nullable
    private ArraySet<String> mimeGroups;

    // Usually there's code to set enabled to true during parsing, but it's possible to install
    // an APK targeting <R that doesn't contain an <application> tag. That code would be skipped
    // and never assign this, so initialize this to true for those cases.
    private long mBooleans = Booleans.ENABLED;

    /**
     * Flags used for a internal bitset. These flags should never be persisted or exposed outside
     * of this class. It is expected that PackageCacher explicitly clears itself whenever the
     * Parcelable implementation changes such that all these flags can be re-ordered or invalidated.
     */
    protected static class Booleans {
        @LongDef({
                EXTERNAL_STORAGE,
                BASE_HARDWARE_ACCELERATED,
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
        })
        public @interface Values {}
        private static final long EXTERNAL_STORAGE = 1L;
        private static final long BASE_HARDWARE_ACCELERATED = 1L << 1;
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
    }

    private ParsingPackageImpl setBoolean(@Booleans.Values long flag, boolean value) {
        if (value) {
            mBooleans |= flag;
        } else {
            mBooleans &= ~flag;
        }
        return this;
    }

    private boolean getBoolean(@Booleans.Values long flag) {
        return (mBooleans & flag) != 0;
    }

    @Nullable
    private Set<String> mKnownActivityEmbeddingCerts;

    // Derived fields
    @NonNull
    private UUID mStorageUuid;
    private long mLongVersionCode;

    private int mLocaleConfigRes;

    @VisibleForTesting
    public ParsingPackageImpl(@NonNull String packageName, @NonNull String baseApkPath,
            @NonNull String path, @Nullable TypedArray manifestArray) {
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

    @SuppressLint("MissingSuperCall")
    @CallSuper
    @Override
    public Object hideAsParsed() {
        assignDerivedFields();
        return this;
    }

    private void assignDerivedFields() {
        mStorageUuid = StorageManager.convert(volumeUuid);
        mLongVersionCode = PackageInfo.composeLongVersionCode(versionCodeMajor, versionCode);
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
    public ParsingPackageImpl addProperty(@Nullable Property property) {
        if (property == null) {
            return this;
        }
        this.mProperties = CollectionUtils.add(this.mProperties, property.getName(), property);
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
    public ParsingPackageImpl addUsesPermission(ParsedUsesPermission permission) {
        this.usesPermissions = CollectionUtils.add(this.usesPermissions, permission);

        // Continue populating legacy data structures to avoid performance
        // issues until all that code can be migrated
        this.requestedPermissions = CollectionUtils.add(this.requestedPermissions,
                permission.getName());

        return this;
    }

    @Override
    public ParsingPackageImpl addImplicitPermission(String permission) {
        addUsesPermission(new ParsedUsesPermissionImpl(permission, 0 /*usesPermissionFlags*/));
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
    public final ParsingPackageImpl addApexSystemService(
            ParsedApexSystemService parsedApexSystemService) {
        this.apexSystemServices = CollectionUtils.add(
                this.apexSystemServices, parsedApexSystemService);
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
    public final ParsingPackageImpl addUsesOptionalNativeLibrary(String libraryName) {
        this.usesOptionalNativeLibraries = CollectionUtils.add(this.usesOptionalNativeLibraries,
                TextUtils.safeIntern(libraryName));
        return this;
    }

    @Override
    public final ParsingPackageImpl addUsesNativeLibrary(String libraryName) {
        this.usesNativeLibraries = CollectionUtils.add(this.usesNativeLibraries,
                TextUtils.safeIntern(libraryName));
        return this;
    }

    @Override public ParsingPackageImpl removeUsesOptionalNativeLibrary(String libraryName) {
        this.usesOptionalNativeLibraries = CollectionUtils.remove(this.usesOptionalNativeLibraries,
                libraryName);
        return this;
    }

    @Override
    public ParsingPackageImpl addUsesSdkLibrary(String libraryName, long versionMajor,
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
    public ParsingPackageImpl addUsesStaticLibrary(String libraryName, long version,
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

    @Override
    public ParsingPackageImpl setNonLocalizedLabel(@Nullable CharSequence value) {
        nonLocalizedLabel = value == null ? null : value.toString().trim();
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
        ApplicationInfo appInfo = toAppInfoWithoutStateWithoutFlags();
        appInfo.flags = PackageInfoWithoutStateUtils.appInfoFlags(this);
        appInfo.privateFlags = PackageInfoWithoutStateUtils.appInfoPrivateFlags(this);
        appInfo.privateFlagsExt = PackageInfoWithoutStateUtils.appInfoPrivateFlagsExt(this);
        return appInfo;
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
        // TODO(b/135203078): See ParsingPackageImpl#getHiddenApiEnforcementPolicy
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
        if (mKnownActivityEmbeddingCerts != null) {
            appInfo.setKnownActivityEmbeddingCerts(mKnownActivityEmbeddingCerts);
        }

        return appInfo;
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
        sForInternedString.parcel(this.sdkLibName, dest, flags);
        dest.writeInt(this.sdkLibVersionMajor);
        sForInternedString.parcel(this.staticSharedLibName, dest, flags);
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
        sForInternedStringList.parcel(this.requestedPermissions, dest, flags);
        ParsingUtils.writeParcelableList(dest, this.usesPermissions);
        sForInternedStringList.parcel(this.implicitPermissions, dest, flags);
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
        dest.writeArraySet(this.mimeGroups);
        dest.writeInt(this.gwpAsanMode);
        dest.writeSparseIntArray(this.minExtensionVersions);
        dest.writeLong(this.mBooleans);
        dest.writeMap(this.mProperties);
        dest.writeInt(this.memtagMode);
        dest.writeInt(this.nativeHeapZeroInitialized);
        sForBoolean.parcel(this.requestRawExternalStorageAccess, dest, flags);
        dest.writeInt(this.mLocaleConfigRes);
        sForStringSet.parcel(mKnownActivityEmbeddingCerts, dest, flags);
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
        this.mBaseApkPath = in.readString();
        this.restrictedAccountType = in.readString();
        this.requiredAccountType = in.readString();
        this.overlayTarget = sForInternedString.unparcel(in);
        this.overlayTargetOverlayableName = in.readString();
        this.overlayCategory = in.readString();
        this.overlayPriority = in.readInt();
        this.overlayables = sForInternedStringValueMap.unparcel(in);
        this.sdkLibName = sForInternedString.unparcel(in);
        this.sdkLibVersionMajor = in.readInt();
        this.staticSharedLibName = sForInternedString.unparcel(in);
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
        this.requestedPermissions = sForInternedStringList.unparcel(in);
        this.usesPermissions = ParsingUtils.createTypedInterfaceList(in,
                ParsedUsesPermissionImpl.CREATOR);
        this.implicitPermissions = sForInternedStringList.unparcel(in);
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
        this.signingDetails = in.readParcelable(boot);
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
        this.mimeGroups = (ArraySet<String>) in.readArraySet(boot);
        this.gwpAsanMode = in.readInt();
        this.minExtensionVersions = in.readSparseIntArray();
        this.mBooleans = in.readLong();
        this.mProperties = in.readHashMap(boot);
        this.memtagMode = in.readInt();
        this.nativeHeapZeroInitialized = in.readInt();
        this.requestRawExternalStorageAccess = sForBoolean.unparcel(in);
        this.mLocaleConfigRes = in.readInt();
        this.mKnownActivityEmbeddingCerts = sForStringSet.unparcel(in);
        assignDerivedFields();
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
    public long getLongVersionCode() {
        return mLongVersionCode;
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

    @NonNull
    @Override
    public String getBaseApkPath() {
        return mBaseApkPath;
    }

    @Override
    public boolean isRequiredForAllUsers() {
        return getBoolean(Booleans.REQUIRED_FOR_ALL_USERS);
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
    public String getOverlayTargetOverlayableName() {
        return overlayTargetOverlayableName;
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
        return getBoolean(Booleans.OVERLAY_IS_STATIC);
    }

    @NonNull
    @Override
    public Map<String,String> getOverlayables() {
        return overlayables;
    }

    @Nullable
    @Override
    public String getSdkLibName() {
        return sdkLibName;
    }

    @Override
    public int getSdkLibVersionMajor() {
        return sdkLibVersionMajor;
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
    public List<String> getUsesNativeLibraries() {
        return usesNativeLibraries;
    }

    @NonNull
    @Override
    public List<String> getUsesOptionalNativeLibraries() {
        return usesOptionalNativeLibraries;
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

    @NonNull
    @Override
    public List<String> getUsesSdkLibraries() { return usesSdkLibraries; }

    @Nullable
    @Override
    public long[] getUsesSdkLibrariesVersionsMajor() { return usesSdkLibrariesVersionsMajor; }

    @Nullable
    @Override
    public String[][] getUsesSdkLibrariesCertDigests() { return usesSdkLibrariesCertDigests; }

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
    public List<FeatureInfo> getRequestedFeatures() {
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

    /**
     * @deprecated consider migrating to {@link #getUsesPermissions} which has
     *             more parsed details, such as flags
     */
    @NonNull
    @Override
    @Deprecated
    public List<String> getRequestedPermissions() {
        return requestedPermissions;
    }

    @NonNull
    @Override
    public List<ParsedUsesPermission> getUsesPermissions() {
        return usesPermissions;
    }

    @NonNull
    @Override
    public List<String> getImplicitPermissions() {
        return implicitPermissions;
    }

    @NonNull
    @Override
    public Map<String, Property> getProperties() {
        return mProperties;
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
    public List<ParsedApexSystemService> getApexSystemServices() {
        return apexSystemServices;
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
            IntentFilter filter = component.getIntents().get(i).getIntentFilter();
            for (int groupIndex = filter.countMimeGroups() - 1; groupIndex >= 0; groupIndex--) {
                if (mimeGroups != null && mimeGroups.size() > 500) {
                    throw new IllegalStateException("Max limit on number of MIME Groups reached");
                }
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
    public SigningDetails getSigningDetails() {
        return signingDetails;
    }

    @NonNull
    @Override
    public String getPath() {
        return mPath;
    }

    @Override
    public boolean isUse32BitAbi() {
        return getBoolean(Booleans.USE_32_BIT_ABI);
    }

    @Override
    public boolean isVisibleToInstantApps() {
        return getBoolean(Booleans.VISIBLE_TO_INSTANT_APPS);
    }

    @Override
    public boolean isForceQueryable() {
        return getBoolean(Booleans.FORCE_QUERYABLE);
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
        return getBoolean(Booleans.ENABLED);
    }

    @Override
    public boolean isCrossProfile() {
        return getBoolean(Booleans.CROSS_PROFILE);
    }

    @Override
    public int getFullBackupContent() {
        return fullBackupContent;
    }

    @Override
    public int getDataExtractionRules() {
        return dataExtractionRules;
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

    @Nullable
    @Override
    public SparseIntArray getMinExtensionVersions() {
        return minExtensionVersions;
    }

    @Override
    public int getMinSdkVersion() {
        return minSdkVersion;
    }

    @Override
    public int getMaxSdkVersion() {
        return maxSdkVersion;
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
    public int getTargetSdkVersion() {
        return targetSdkVersion;
    }

    @Override
    public int getTargetSandboxVersion() {
        return targetSandboxVersion;
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
        return getBoolean(Booleans.EXTERNAL_STORAGE);
    }

    @Override
    public boolean isBaseHardwareAccelerated() {
        return getBoolean(Booleans.BASE_HARDWARE_ACCELERATED);
    }

    @Override
    public boolean isAllowBackup() {
        return getBoolean(Booleans.ALLOW_BACKUP);
    }

    @Override
    public boolean isKillAfterRestore() {
        return getBoolean(Booleans.KILL_AFTER_RESTORE);
    }

    @Override
    public boolean isRestoreAnyVersion() {
        return getBoolean(Booleans.RESTORE_ANY_VERSION);
    }

    @Override
    public boolean isFullBackupOnly() {
        return getBoolean(Booleans.FULL_BACKUP_ONLY);
    }

    @Override
    public boolean isPersistent() {
        return getBoolean(Booleans.PERSISTENT);
    }

    @Override
    public boolean isDebuggable() {
        return getBoolean(Booleans.DEBUGGABLE);
    }

    @Override
    public boolean isVmSafeMode() {
        return getBoolean(Booleans.VM_SAFE_MODE);
    }

    @Override
    public boolean isHasCode() {
        return getBoolean(Booleans.HAS_CODE);
    }

    @Override
    public boolean isAllowTaskReparenting() {
        return getBoolean(Booleans.ALLOW_TASK_REPARENTING);
    }

    @Override
    public boolean isAllowClearUserData() {
        return getBoolean(Booleans.ALLOW_CLEAR_USER_DATA);
    }

    @Override
    public boolean isLargeHeap() {
        return getBoolean(Booleans.LARGE_HEAP);
    }

    @Override
    public boolean isUsesCleartextTraffic() {
        return getBoolean(Booleans.USES_CLEARTEXT_TRAFFIC);
    }

    @Override
    public boolean isSupportsRtl() {
        return getBoolean(Booleans.SUPPORTS_RTL);
    }

    @Override
    public boolean isTestOnly() {
        return getBoolean(Booleans.TEST_ONLY);
    }

    @Override
    public boolean isMultiArch() {
        return getBoolean(Booleans.MULTI_ARCH);
    }

    @Override
    public boolean isExtractNativeLibs() {
        return getBoolean(Booleans.EXTRACT_NATIVE_LIBS);
    }

    @Override
    public boolean isGame() {
        return getBoolean(Booleans.GAME);
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
        return getBoolean(Booleans.STATIC_SHARED_LIBRARY);
    }

    @Override
    public boolean isSdkLibrary() {
        return getBoolean(Booleans.SDK_LIBRARY);
    }

    @Override
    public boolean isOverlay() {
        return getBoolean(Booleans.OVERLAY);
    }

    @Override
    public boolean isIsolatedSplitLoading() {
        return getBoolean(Booleans.ISOLATED_SPLIT_LOADING);
    }

    @Override
    public boolean isHasDomainUrls() {
        return getBoolean(Booleans.HAS_DOMAIN_URLS);
    }

    @Override
    public boolean isProfileableByShell() {
        return isProfileable() && getBoolean(Booleans.PROFILEABLE_BY_SHELL);
    }

    @Override
    public boolean isProfileable() {
        return !getBoolean(Booleans.DISALLOW_PROFILING);
    }

    @Override
    public boolean isBackupInForeground() {
        return getBoolean(Booleans.BACKUP_IN_FOREGROUND);
    }

    @Override
    public boolean isUseEmbeddedDex() {
        return getBoolean(Booleans.USE_EMBEDDED_DEX);
    }

    @Override
    public boolean isDefaultToDeviceProtectedStorage() {
        return getBoolean(Booleans.DEFAULT_TO_DEVICE_PROTECTED_STORAGE);
    }

    @Override
    public boolean isDirectBootAware() {
        return getBoolean(Booleans.DIRECT_BOOT_AWARE);
    }

    @ApplicationInfo.GwpAsanMode
    @Override
    public int getGwpAsanMode() {
        return gwpAsanMode;
    }

    @ApplicationInfo.MemtagMode
    @Override
    public int getMemtagMode() {
        return memtagMode;
    }

    @ApplicationInfo.NativeHeapZeroInitialized
    @Override
    public int getNativeHeapZeroInitialized() {
        return nativeHeapZeroInitialized;
    }

    @Override
    public int getLocaleConfigRes() {
        return mLocaleConfigRes;
    }

    @Nullable
    @Override
    public Boolean hasRequestRawExternalStorageAccess() {
        return requestRawExternalStorageAccess;
    }

    @Override
    public boolean isPartiallyDirectBootAware() {
        return getBoolean(Booleans.PARTIALLY_DIRECT_BOOT_AWARE);
    }

    @Override
    public boolean isResizeableActivityViaSdkVersion() {
        return getBoolean(Booleans.RESIZEABLE_ACTIVITY_VIA_SDK_VERSION);
    }

    @Override
    public boolean isAllowClearUserDataOnFailedRestore() {
        return getBoolean(Booleans.ALLOW_CLEAR_USER_DATA_ON_FAILED_RESTORE);
    }

    @Override
    public boolean isAllowAudioPlaybackCapture() {
        return getBoolean(Booleans.ALLOW_AUDIO_PLAYBACK_CAPTURE);
    }

    @Override
    public boolean isRequestLegacyExternalStorage() {
        return getBoolean(Booleans.REQUEST_LEGACY_EXTERNAL_STORAGE);
    }

    @Override
    public boolean isUsesNonSdkApi() {
        return getBoolean(Booleans.USES_NON_SDK_API);
    }

    @Override
    public boolean isHasFragileUserData() {
        return getBoolean(Booleans.HAS_FRAGILE_USER_DATA);
    }

    @Override
    public boolean isCantSaveState() {
        return getBoolean(Booleans.CANT_SAVE_STATE);
    }

    @Override
    public boolean isAllowNativeHeapPointerTagging() {
        return getBoolean(Booleans.ALLOW_NATIVE_HEAP_POINTER_TAGGING);
    }

    @Override
    public int getAutoRevokePermissions() {
        return autoRevokePermissions;
    }

    @Override
    public boolean hasPreserveLegacyExternalStorage() {
        return getBoolean(Booleans.PRESERVE_LEGACY_EXTERNAL_STORAGE);
    }

    @Override
    public boolean hasRequestForegroundServiceExemption() {
        return getBoolean(Booleans.REQUEST_FOREGROUND_SERVICE_EXEMPTION);
    }

    @Override
    public boolean areAttributionsUserVisible() {
        return getBoolean(Booleans.ATTRIBUTIONS_ARE_USER_VISIBLE);
    }

    @Override
    public boolean isResetEnabledSettingsOnAppDataCleared() {
        return getBoolean(Booleans.RESET_ENABLED_SETTINGS_ON_APP_DATA_CLEARED);
    }

    @NonNull
    @Override
    public Set<String> getKnownActivityEmbeddingCerts() {
        return mKnownActivityEmbeddingCerts == null ? Collections.emptySet()
                : mKnownActivityEmbeddingCerts;
    }

    @Override
    public boolean isOnBackInvokedCallbackEnabled() {
        return getBoolean(Booleans.ENABLE_ON_BACK_INVOKED_CALLBACK);
    }

    @Override
    public boolean isLeavingSharedUid() {
        return getBoolean(Booleans.LEAVING_SHARED_UID);
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
        return setBoolean(Booleans.REQUIRED_FOR_ALL_USERS, value);
    }

    @Override
    public ParsingPackageImpl setOverlayPriority(int value) {
        overlayPriority = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setOverlayIsStatic(boolean value) {
        return setBoolean(Booleans.OVERLAY_IS_STATIC, value);
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
    public ParsingPackageImpl setSigningDetails(@Nullable SigningDetails value) {
        signingDetails = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setUse32BitAbi(boolean value) {
        return setBoolean(Booleans.USE_32_BIT_ABI, value);
    }

    @Override
    public ParsingPackageImpl setVisibleToInstantApps(boolean value) {
        return setBoolean(Booleans.VISIBLE_TO_INSTANT_APPS, value);
    }

    @Override
    public ParsingPackageImpl setForceQueryable(boolean value) {
        return setBoolean(Booleans.FORCE_QUERYABLE, value);
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
        return setBoolean(Booleans.ENABLED, value);
    }

    @Override
    public ParsingPackageImpl setCrossProfile(boolean value) {
        return setBoolean(Booleans.CROSS_PROFILE, value);
    }

    @Override
    public ParsingPackageImpl setFullBackupContent(int value) {
        fullBackupContent = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setDataExtractionRules(int value) {
        dataExtractionRules = value;
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
    public ParsingPackageImpl setLeavingSharedUid(boolean value) {
        return setBoolean(Booleans.LEAVING_SHARED_UID, value);
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
    public ParsingPackageImpl setMinExtensionVersions(@Nullable SparseIntArray value) {
        minExtensionVersions = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setMinSdkVersion(int value) {
        minSdkVersion = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setMaxSdkVersion(int value) {
        maxSdkVersion = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setNetworkSecurityConfigRes(int value) {
        networkSecurityConfigRes = value;
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
    public ParsingPackageImpl setRequestForegroundServiceExemption(boolean value) {
        return setBoolean(Booleans.REQUEST_FOREGROUND_SERVICE_EXEMPTION, value);
    }

    @Override
    public ParsingPackageImpl setUiOptions(int value) {
        uiOptions = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setExternalStorage(boolean value) {
        return setBoolean(Booleans.EXTERNAL_STORAGE, value);
    }

    @Override
    public ParsingPackageImpl setBaseHardwareAccelerated(boolean value) {
        return setBoolean(Booleans.BASE_HARDWARE_ACCELERATED, value);
    }

    @Override
    public ParsingPackageImpl setAllowBackup(boolean value) {
        return setBoolean(Booleans.ALLOW_BACKUP, value);
    }

    @Override
    public ParsingPackageImpl setKillAfterRestore(boolean value) {
        return setBoolean(Booleans.KILL_AFTER_RESTORE, value);
    }

    @Override
    public ParsingPackageImpl setRestoreAnyVersion(boolean value) {
        return setBoolean(Booleans.RESTORE_ANY_VERSION, value);
    }

    @Override
    public ParsingPackageImpl setFullBackupOnly(boolean value) {
        return setBoolean(Booleans.FULL_BACKUP_ONLY, value);
    }

    @Override
    public ParsingPackageImpl setPersistent(boolean value) {
        return setBoolean(Booleans.PERSISTENT, value);
    }

    @Override
    public ParsingPackageImpl setDebuggable(boolean value) {
        return setBoolean(Booleans.DEBUGGABLE, value);
    }

    @Override
    public ParsingPackageImpl setVmSafeMode(boolean value) {
        return setBoolean(Booleans.VM_SAFE_MODE, value);
    }

    @Override
    public ParsingPackageImpl setHasCode(boolean value) {
        return setBoolean(Booleans.HAS_CODE, value);
    }

    @Override
    public ParsingPackageImpl setAllowTaskReparenting(boolean value) {
        return setBoolean(Booleans.ALLOW_TASK_REPARENTING, value);
    }

    @Override
    public ParsingPackageImpl setAllowClearUserData(boolean value) {
        return setBoolean(Booleans.ALLOW_CLEAR_USER_DATA, value);
    }

    @Override
    public ParsingPackageImpl setLargeHeap(boolean value) {
        return setBoolean(Booleans.LARGE_HEAP, value);
    }

    @Override
    public ParsingPackageImpl setUsesCleartextTraffic(boolean value) {
        return setBoolean(Booleans.USES_CLEARTEXT_TRAFFIC, value);
    }

    @Override
    public ParsingPackageImpl setSupportsRtl(boolean value) {
        return setBoolean(Booleans.SUPPORTS_RTL, value);
    }

    @Override
    public ParsingPackageImpl setTestOnly(boolean value) {
        return setBoolean(Booleans.TEST_ONLY, value);
    }

    @Override
    public ParsingPackageImpl setMultiArch(boolean value) {
        return setBoolean(Booleans.MULTI_ARCH, value);
    }

    @Override
    public ParsingPackageImpl setExtractNativeLibs(boolean value) {
        return setBoolean(Booleans.EXTRACT_NATIVE_LIBS, value);
    }

    @Override
    public ParsingPackageImpl setGame(boolean value) {
        return setBoolean(Booleans.GAME, value);
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
    public ParsingPackageImpl setSdkLibName(String sdkLibName) {
        this.sdkLibName = TextUtils.safeIntern(sdkLibName);
        return this;
    }

    @Override
    public ParsingPackageImpl setSdkLibVersionMajor(int sdkLibVersionMajor) {
        this.sdkLibVersionMajor = sdkLibVersionMajor;
        return this;
    }

    @Override
    public ParsingPackageImpl setSdkLibrary(boolean value) {
        return setBoolean(Booleans.SDK_LIBRARY, value);
    }

    @Override
    public ParsingPackageImpl setStaticSharedLibrary(boolean value) {
        return setBoolean(Booleans.STATIC_SHARED_LIBRARY, value);
    }

    @Override
    public ParsingPackageImpl setOverlay(boolean value) {
        return setBoolean(Booleans.OVERLAY, value);
    }

    @Override
    public ParsingPackageImpl setIsolatedSplitLoading(boolean value) {
        return setBoolean(Booleans.ISOLATED_SPLIT_LOADING, value);
    }

    @Override
    public ParsingPackageImpl setHasDomainUrls(boolean value) {
        return setBoolean(Booleans.HAS_DOMAIN_URLS, value);
    }

    @Override
    public ParsingPackageImpl setProfileableByShell(boolean value) {
        return setBoolean(Booleans.PROFILEABLE_BY_SHELL, value);
    }

    @Override
    public ParsingPackageImpl setProfileable(boolean value) {
        return setBoolean(Booleans.DISALLOW_PROFILING, !value);
    }

    @Override
    public ParsingPackageImpl setBackupInForeground(boolean value) {
        return setBoolean(Booleans.BACKUP_IN_FOREGROUND, value);
    }

    @Override
    public ParsingPackageImpl setUseEmbeddedDex(boolean value) {
        return setBoolean(Booleans.USE_EMBEDDED_DEX, value);
    }

    @Override
    public ParsingPackageImpl setDefaultToDeviceProtectedStorage(boolean value) {
        return setBoolean(Booleans.DEFAULT_TO_DEVICE_PROTECTED_STORAGE, value);
    }

    @Override
    public ParsingPackageImpl setDirectBootAware(boolean value) {
        return setBoolean(Booleans.DIRECT_BOOT_AWARE, value);
    }

    @Override
    public ParsingPackageImpl setGwpAsanMode(@ApplicationInfo.GwpAsanMode int value) {
        gwpAsanMode = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setMemtagMode(@ApplicationInfo.MemtagMode int value) {
        memtagMode = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setNativeHeapZeroInitialized(
            @ApplicationInfo.NativeHeapZeroInitialized int value) {
        nativeHeapZeroInitialized = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setRequestRawExternalStorageAccess(@Nullable Boolean value) {
        requestRawExternalStorageAccess = value;
        return this;
    }
    @Override
    public ParsingPackageImpl setPartiallyDirectBootAware(boolean value) {
        return setBoolean(Booleans.PARTIALLY_DIRECT_BOOT_AWARE, value);
    }

    @Override
    public ParsingPackageImpl setResizeableActivityViaSdkVersion(boolean value) {
        return setBoolean(Booleans.RESIZEABLE_ACTIVITY_VIA_SDK_VERSION, value);
    }

    @Override
    public ParsingPackageImpl setAllowClearUserDataOnFailedRestore(boolean value) {
        return setBoolean(Booleans.ALLOW_CLEAR_USER_DATA_ON_FAILED_RESTORE, value);
    }

    @Override
    public ParsingPackageImpl setAllowAudioPlaybackCapture(boolean value) {
        return setBoolean(Booleans.ALLOW_AUDIO_PLAYBACK_CAPTURE, value);
    }

    @Override
    public ParsingPackageImpl setRequestLegacyExternalStorage(boolean value) {
        return setBoolean(Booleans.REQUEST_LEGACY_EXTERNAL_STORAGE, value);
    }

    @Override
    public ParsingPackageImpl setUsesNonSdkApi(boolean value) {
        return setBoolean(Booleans.USES_NON_SDK_API, value);
    }

    @Override
    public ParsingPackageImpl setHasFragileUserData(boolean value) {
        return setBoolean(Booleans.HAS_FRAGILE_USER_DATA, value);
    }

    @Override
    public ParsingPackageImpl setCantSaveState(boolean value) {
        return setBoolean(Booleans.CANT_SAVE_STATE, value);
    }

    @Override
    public ParsingPackageImpl setAllowNativeHeapPointerTagging(boolean value) {
        return setBoolean(Booleans.ALLOW_NATIVE_HEAP_POINTER_TAGGING, value);
    }

    @Override
    public ParsingPackageImpl setAutoRevokePermissions(int value) {
        autoRevokePermissions = value;
        return this;
    }

    @Override
    public ParsingPackageImpl setPreserveLegacyExternalStorage(boolean value) {
        return setBoolean(Booleans.PRESERVE_LEGACY_EXTERNAL_STORAGE, value);
    }

    @Override
    public ParsingPackageImpl setVersionName(String versionName) {
        this.versionName = versionName;
        return this;
    }

    @Override
    public ParsingPackage setCompileSdkVersionCodeName(String compileSdkVersionCodeName) {
        this.compileSdkVersionCodeName = compileSdkVersionCodeName;
        return this;
    }

    @Override
    public ParsingPackageImpl setProcessName(String processName) {
        this.processName = processName;
        return this;
    }

    @Override
    public ParsingPackageImpl setRestrictedAccountType(@Nullable String restrictedAccountType) {
        this.restrictedAccountType = restrictedAccountType;
        return this;
    }

    @Override
    public ParsingPackageImpl setOverlayTargetOverlayableName(
            @Nullable String overlayTargetOverlayableName) {
        this.overlayTargetOverlayableName = overlayTargetOverlayableName;
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
        this.className = className == null ? null : className.trim();
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

    @Override
    public ParsingPackage setAttributionsAreUserVisible(boolean attributionsAreUserVisible) {
        setBoolean(Booleans.ATTRIBUTIONS_ARE_USER_VISIBLE, attributionsAreUserVisible);
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
    public ParsingPackageImpl setLocaleConfigRes(int value) {
        mLocaleConfigRes = value;
        return this;
    }

    @Override
    public ParsingPackage setKnownActivityEmbeddingCerts(
            @Nullable Set<String> knownEmbeddingCerts) {
        mKnownActivityEmbeddingCerts = knownEmbeddingCerts;
        return this;
    }

    @Override
    public ParsingPackage setOnBackInvokedCallbackEnabled(boolean value) {
        setBoolean(Booleans.ENABLE_ON_BACK_INVOKED_CALLBACK, value);
        return this;
    }
}
