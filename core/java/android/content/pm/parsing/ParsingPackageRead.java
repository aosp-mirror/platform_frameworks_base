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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager.Property;
import android.content.pm.PackageParser;
import android.content.pm.SigningDetails;
import android.content.pm.parsing.component.ParsedAttribution;
import android.content.pm.parsing.component.ParsedIntentInfo;
import android.content.pm.parsing.component.ParsedPermissionGroup;
import android.content.pm.parsing.component.ParsedProcess;
import android.content.pm.parsing.component.ParsedUsesPermission;
import android.os.Bundle;
import android.util.ArraySet;
import android.util.Pair;
import android.util.SparseIntArray;

import java.security.PublicKey;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything written by {@link ParsingPackage} and readable back.
 *
 * @hide
 */
public interface ParsingPackageRead extends PkgWithoutStateAppInfo, PkgWithoutStatePackageInfo,
        ParsingPackageInternal {

    /**
     * The names of packages to adopt ownership of permissions from, parsed under {@link
     * PackageParser#TAG_ADOPT_PERMISSIONS}.
     *
     * @see R.styleable#AndroidManifestOriginalPackage_name
     */
    @NonNull
    List<String> getAdoptPermissions();

    @NonNull
    List<ParsedAttribution> getAttributions();

    /**
     * Permissions requested but not in the manifest. These may have been split or migrated from
     * previous versions/definitions.
     */
    @NonNull
    List<String> getImplicitPermissions();

    @NonNull
    List<ParsedUsesPermission> getUsesPermissions();

    /**
     * For use with {@link com.android.server.pm.KeySetManagerService}. Parsed in {@link
     * PackageParser#TAG_KEY_SETS}.
     *
     * @see R.styleable#AndroidManifestKeySet
     * @see R.styleable#AndroidManifestPublicKey
     */
    @NonNull
    Map<String, ArraySet<PublicKey>> getKeySetMapping();

    /**
     * Library names this package is declared as, for use by other packages with "uses-library".
     *
     * @see R.styleable#AndroidManifestLibrary
     */
    @NonNull
    List<String> getLibraryNames();

    /**
     * TODO(b/135203078): Make all the Bundles immutable (and non-null by shared empty reference?)
     */
    @Nullable
    Bundle getMetaData();

    @Nullable
    Set<String> getMimeGroups();

    /**
     * @see R.styleable#AndroidManifestExtensionSdk
     */
    @Nullable
    SparseIntArray getMinExtensionVersions();

    /**
     * For system use to migrate from an old package name to a new one, moving over data if
     * available.
     *
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
     * @see android.content.pm.PermissionGroupInfo
     */
    @NonNull
    List<ParsedPermissionGroup> getPermissionGroups();

    /**
     * Used to determine the default preferred handler of an {@link Intent}.
     * <p>
     * Map of component className to intent info inside that component. TODO(b/135203078): Is this
     * actually used/working?
     */
    @NonNull
    List<Pair<String, ParsedIntentInfo>> getPreferredActivityFilters();

    /**
     * @see android.content.pm.ProcessInfo
     */
    @NonNull
    Map<String, ParsedProcess> getProcesses();

    /**
     * System protected broadcasts.
     *
     * @see R.styleable#AndroidManifestProtectedBroadcast
     */
    @NonNull
    List<String> getProtectedBroadcasts();

    /**
     * Intents that this package may query or require and thus requires visibility into.
     *
     * @see R.styleable#AndroidManifestQueriesIntent
     */
    @NonNull
    List<Intent> getQueriesIntents();

    /**
     * Other packages that this package may query or require and thus requires visibility into.
     *
     * @see R.styleable#AndroidManifestQueriesPackage
     */
    @NonNull
    List<String> getQueriesPackages();

    /**
     * Authorities that this package may query or require and thus requires visibility into.
     *
     * @see R.styleable#AndroidManifestQueriesProvider
     */
    @NonNull
    Set<String> getQueriesProviders();

    /**
     * SHA-512 hash of the only APK that can be used to update a system package.
     *
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
     * Returns the properties set on the application
     */
    @NonNull
    Map<String, Property> getProperties();

    /**
     * Flags of any split APKs; ordered by parsed splitName
     */
    @Nullable
    int[] getSplitFlags();

    /**
     * @see R.styleable#AndroidManifestStaticLibrary_name
     */
    @Nullable
    String getStaticSharedLibName();

    /**
     * @see R.styleable#AndroidManifestStaticLibrary_version
     */
    long getStaticSharedLibVersion();

    /**
     * For use with {@link com.android.server.pm.KeySetManagerService}. Parsed in {@link
     * PackageParser#TAG_KEY_SETS}.
     *
     * @see R.styleable#AndroidManifestUpgradeKeySet
     */
    @NonNull
    Set<String> getUpgradeKeySets();

    /**
     * @see R.styleable#AndroidManifestUsesLibrary
     */
    @NonNull
    List<String> getUsesLibraries();

    /**
     * @see R.styleable#AndroidManifestUsesNativeLibrary
     */
    @NonNull
    List<String> getUsesNativeLibraries();

    /**
     * Like {@link #getUsesLibraries()}, but marked optional by setting {@link
     * R.styleable#AndroidManifestUsesLibrary_required} to false . Application is expected to handle
     * absence manually.
     *
     * @see R.styleable#AndroidManifestUsesLibrary
     */
    @NonNull
    List<String> getUsesOptionalLibraries();

    /**
     * Like {@link #getUsesNativeLibraries()}, but marked optional by setting {@link
     * R.styleable#AndroidManifestUsesNativeLibrary_required} to false . Application is expected to
     * handle absence manually.
     *
     * @see R.styleable#AndroidManifestUsesNativeLibrary
     */
    @NonNull
    List<String> getUsesOptionalNativeLibraries();

    /**
     * TODO(b/135203078): Move static library stuff to an inner data class
     *
     * @see R.styleable#AndroidManifestUsesStaticLibrary
     */
    @NonNull
    List<String> getUsesStaticLibraries();

    /**
     * @see R.styleable#AndroidManifestUsesStaticLibrary_certDigest
     */
    @Nullable
    String[][] getUsesStaticLibrariesCertDigests();

    /**
     * @see R.styleable#AndroidManifestUsesStaticLibrary_version
     */
    @Nullable
    long[] getUsesStaticLibrariesVersions();

    boolean hasPreserveLegacyExternalStorage();

    /**
     * @see R.styleable#AndroidManifestApplication_forceQueryable
     */
    boolean isForceQueryable();

    /**
     * @see ApplicationInfo#FLAG_IS_GAME
     */
    @Deprecated
    boolean isGame();

    /**
     * The install time abi override to choose 32bit abi's when multiple abi's are present. This is
     * only meaningful for multiarch applications. The use32bitAbi attribute is ignored if
     * cpuAbiOverride is also set.
     *
     * @see R.attr#use32bitAbi
     */
    boolean isUse32BitAbi();

    /**
     * Set if the any of components are visible to instant applications.
     *
     * @see R.styleable#AndroidManifestActivity_visibleToInstantApps
     * @see R.styleable#AndroidManifestProvider_visibleToInstantApps
     * @see R.styleable#AndroidManifestService_visibleToInstantApps
     */
    boolean isVisibleToInstantApps();
}
