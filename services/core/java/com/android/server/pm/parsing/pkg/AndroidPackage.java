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
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.SigningDetails;
import android.content.pm.parsing.ParsingPackageRead;
import android.content.pm.parsing.ParsingPackageUtils;
import android.content.pm.parsing.component.ParsedAttribution;
import android.content.pm.parsing.component.ParsedIntentInfo;
import android.content.pm.parsing.component.ParsedPermissionGroup;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.ArraySet;
import android.util.Pair;

import com.android.internal.R;

import java.security.PublicKey;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The last state of a package during parsing/install before it is available in
 * {@link com.android.server.pm.PackageManagerService#mPackages}.
 *
 * It is the responsibility of the caller to understand what data is available at what step of the
 * parsing or install process.
 *
 * TODO(b/135203078): Nullability annotations
 * TODO(b/135203078): Remove get/setAppInfo differences
 *
 * @hide
 */
public interface AndroidPackage extends PkgAppInfo, PkgPackageInfo, ParsingPackageRead, Parcelable {

    /**
     * The names of packages to adopt ownership of permissions from, parsed under
     * {@link ParsingPackageUtils#TAG_ADOPT_PERMISSIONS}.
     * @see R.styleable#AndroidManifestOriginalPackage_name
     */
    @NonNull
    List<String> getAdoptPermissions();

    /** Path of base APK */
    @NonNull
    String getBaseApkPath();

    /** Revision code of base APK */
    int getBaseRevisionCode();

    /**
     * The path to the folder containing the base APK and any installed splits.
     */
    @NonNull
    String getPath();

    /**
     * Permissions requested but not in the manifest. These may have been split or migrated from
     * previous versions/definitions.
     */
    @NonNull
    List<String> getImplicitPermissions();

    /**
     * For use with {@link com.android.server.pm.KeySetManagerService}. Parsed in
     * {@link ParsingPackageUtils#TAG_KEY_SETS}.
     * @see R.styleable#AndroidManifestKeySet
     * @see R.styleable#AndroidManifestPublicKey
     */
    @NonNull
    Map<String, ArraySet<PublicKey>> getKeySetMapping();

    /**
     * Library names this package is declared as, for use by other packages with "uses-library".
     * @see R.styleable#AndroidManifestLibrary
     */
    @NonNull
    List<String> getLibraryNames();

    /**
     * The package name as declared in the manifest, since the package can be renamed. For example,
     * static shared libs use synthetic package names.
     */
    @NonNull
    String getManifestPackageName();

    /**
     * We store the application meta-data independently to avoid multiple unwanted references
     * TODO(b/135203078): What does this comment mean?
     * TODO(b/135203078): Make all the Bundles immutable (and non-null by shared empty reference?)
     */
    @Nullable
    Bundle getMetaData();

    /**
     * For system use to migrate from an old package name to a new one, moving over data
     * if available.
     * @see R.styleable#AndroidManifestOriginalPackage}
     */
    @NonNull
    List<String> getOriginalPackages();

    /**
     * Map of overlayable name to actor name.
     */
    @NonNull
    Map<String, String> getOverlayables();

    /**
     * The name of the package as used to identify it in the system. This may be adjusted by the
     * system from the value declared in the manifest, and may not correspond to a Java code
     * package.
     * @see ApplicationInfo#packageName
     * @see PackageInfo#packageName
     */
    @NonNull
    String getPackageName();

    /**
     * @see PermissionGroupInfo
     */
    @NonNull
    List<ParsedPermissionGroup> getPermissionGroups();

    @NonNull
    List<ParsedAttribution> getAttributions();

    /**
     * Used to determine the default preferred handler of an {@link Intent}.
     *
     * Map of component className to intent info inside that component.
     * TODO(b/135203078): Is this actually used/working?
     */
    @NonNull
    List<Pair<String, ParsedIntentInfo>> getPreferredActivityFilters();

    /**
     * System protected broadcasts.
     * @see R.styleable#AndroidManifestProtectedBroadcast
     */
    @NonNull
    List<String> getProtectedBroadcasts();

    /**
     * Intents that this package may query or require and thus requires visibility into.
     * @see R.styleable#AndroidManifestQueriesIntent
     */
    @NonNull
    List<Intent> getQueriesIntents();

    /**
     * Other packages that this package may query or require and thus requires visibility into.
     * @see R.styleable#AndroidManifestQueriesPackage
     */
    @NonNull
    List<String> getQueriesPackages();

    /**
     * If a system app declares {@link #getOriginalPackages()}, and the app was previously installed
     * under one of those original package names, the {@link #getPackageName()} system identifier
     * will be changed to that previously installed name. This will then be non-null, set to the
     * manifest package name, for tracking the package under its true name.
     *
     * TODO(b/135203078): Remove this in favor of checking originalPackages.isEmpty and
     *  getManifestPackageName
     */
    @Nullable
    String getRealPackage();

    /**
     * SHA-512 hash of the only APK that can be used to update a system package.
     * @see R.styleable#AndroidManifestRestrictUpdate
     */
    @Nullable
    byte[] getRestrictUpdateHash();

    /**
     * The signature data of all APKs in this package, which must be exactly the same across the
     * base and splits.
     */
    SigningDetails getSigningDetails();

    /**
     * TODO(b/135203078): Move split stuff to an inner data class
     * @see ApplicationInfo#splitNames
     * @see PackageInfo#splitNames
     */
    @Nullable
    String[] getSplitNames();

    /** Flags of any split APKs; ordered by parsed splitName */
    @Nullable
    int[] getSplitFlags();

    /** @see R.styleable#AndroidManifestStaticLibrary_name */
    @Nullable
    String getStaticSharedLibName();

    /** @see R.styleable#AndroidManifestStaticLibrary_version */
    long getStaticSharedLibVersion();

    /**
     * {@link android.os.storage.StorageManager#convert(String)} version of
     * {@link #getVolumeUuid()}.
     * TODO(b/135203078): All usages call toString() on this. Can the string be returned directly,
     *  or does the parsing logic in StorageManager have to run?
     */
    UUID getStorageUuid();

    /**
     * For use with {@link com.android.server.pm.KeySetManagerService}. Parsed in
     * {@link ParsingPackageUtils#TAG_KEY_SETS}.
     * @see R.styleable#AndroidManifestUpgradeKeySet
     */
    @NonNull
    Set<String> getUpgradeKeySets();

    /** @see R.styleable#AndroidManifestUsesLibrary */
    @NonNull
    List<String> getUsesLibraries();

    /**
     * Like {@link #getUsesLibraries()}, but marked optional by setting
     * {@link R.styleable#AndroidManifestUsesLibrary_required} to false . Application is expected
     * to handle absence manually.
     * @see R.styleable#AndroidManifestUsesLibrary
     */
    @NonNull
    List<String> getUsesOptionalLibraries();

    /** @see R.styleabele#AndroidManifestUsesNativeLibrary */
    @NonNull
    List<String> getUsesNativeLibraries();

    /**
     * Like {@link #getUsesNativeLibraries()}, but marked optional by setting
     * {@link R.styleable#AndroidManifestUsesNativeLibrary_required} to false . Application is
     * expected to handle absence manually.
     * @see R.styleable#AndroidManifestUsesNativeLibrary
     */
    @NonNull
    List<String> getUsesOptionalNativeLibraries();

    /**
     * TODO(b/135203078): Move static library stuff to an inner data class
     * @see R.styleable#AndroidManifestUsesStaticLibrary
     */
    @NonNull
    List<String> getUsesStaticLibraries();

    /** @see R.styleable#AndroidManifestUsesStaticLibrary_certDigest */
    @Nullable
    String[][] getUsesStaticLibrariesCertDigests();

    /** @see R.styleable#AndroidManifestUsesStaticLibrary_version */
    @Nullable
    long[] getUsesStaticLibrariesVersions();

    /** @see R.styleable#AndroidManifestApplication_forceQueryable */
    boolean isForceQueryable();

    boolean isCrossProfile();

    /**
     * The install time abi override to choose 32bit abi's when multiple abi's
     * are present. This is only meaningfull for multiarch applications.
     */
    boolean isUse32BitAbi();

    /**
     * Set if the any of components are visible to instant applications.
     * @see R.styleable#AndroidManifestActivity_visibleToInstantApps
     * @see R.styleable#AndroidManifestProvider_visibleToInstantApps
     * @see R.styleable#AndroidManifestService_visibleToInstantApps
     */
    boolean isVisibleToInstantApps();

    /**
     * Generates an {@link ApplicationInfo} object with only the data available in this object.
     *
     * TODO(b/135203078): Actually add this
     * This does not contain any system or user state data, and should be avoided. Prefer
     * com.android.server.pm.parsing.PackageInfoUtils#generateApplicationInfo(
     * AndroidPackage, int, PackageUserState, int, com.android.server.pm.PackageSetting)
     *
     * @deprecated Access AndroidPackage fields directly.
     */
    @Deprecated
    @NonNull
    ApplicationInfo toAppInfoWithoutState();

    /**
     * Same as toAppInfoWithoutState except it does not compute any flags.
     */
    @NonNull
    ApplicationInfo toAppInfoWithoutStateWithoutFlags();

    /**
     * TODO(b/135203078): Remove usages?
     * @return a mock of what the previous package.applicationInfo would've returned for logging
     * @deprecated don't use this in any new code, just print package name directly
     */
    @Deprecated
    @NonNull
    String toAppInfoToString();
}
