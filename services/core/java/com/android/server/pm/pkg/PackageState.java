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
import android.annotation.SystemApi;
import android.annotation.UserIdInt;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.SigningInfo;
import android.content.pm.pkg.PackageUserState;

import com.android.internal.R;
import com.android.server.pm.PackageSetting;
import com.android.server.pm.Settings;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The API surface for a {@link PackageSetting}. Methods are expected to return immutable objects.
 * This may mean copying data on each invocation until related classes are refactored to be
 * immutable.
 * <p>
 * Note that until immutability or read-only caching is enabled, {@link PackageSetting} cannot be
 * returned directly, so {@link PackageStateImpl} is used to temporarily copy the data. This is a
 * relatively expensive operation since it has to create an object for every package, but it's much
 * lighter than the alternative of generating {@link PackageInfo} objects.
 * <p>
 * TODO: Documentation TODO: Currently missing, should be exposed as API?
 * <ul>
 *     <li>keySetData</li>
 *     <li>installSource</li>
 *     <li>incrementalStates</li>
 * </ul>
 *
 * @hide
 *
 * TODO(chiuwinson): Delete all of the method defaults
 */
// TODO(b/173807334): Expose API
//@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
public interface PackageState {

    /**
     * This can be null whenever a physical APK on device is missing. This can be the result of
     * removing an external storage device where the APK resides.
     * <p>
     * This will result in the system reading the {@link PackageSetting} from disk, but without
     * being able to parse the base APK's AndroidManifest.xml to read all of its metadata. The data
     * that is written and read in {@link Settings} includes a minimal set of metadata needed to
     * perform other checks in the system.
     * <p>
     * This is important in order to enforce uniqueness within the system, as the package, even if
     * on a removed storage device, is still considered installed. Another package of the same
     * application ID or declaring the same permissions or similar cannot be installed.
     * <p>
     * Re-attaching the storage device to make the APK available should allow the user to use the
     * app once the device reboots or otherwise re-scans it.
     */
    @Nullable
    default AndroidPackageApi getAndroidPackage() { return null; }

    /**
     * The non-user-specific UID
     */
    default int getAppId() { return -1; }

    /**
     * Value set through {@link PackageManager#setApplicationCategoryHint(String, int)}. Only
     * applied if the application itself does not declare a category.
     *
     * @see AndroidPackageApi#getCategory()
     */
    default int getCategoryOverride() { return -1; }

    @Nullable
    default String getCpuAbiOverride() { return null; }

    /**
     * In epoch milliseconds.
     */
    default long getFirstInstallTime() { return -1; }

    /**
     * In epoch milliseconds.
     */
    default long getLastModifiedTime() { return -1; }

    @NonNull
    default long[] getLastPackageUsageTime() { return null; }

    /**
     * In epoch milliseconds.
     */
    default long getLastUpdateTime() { return -1; }

    /**
     * @see AndroidPackageApi#getLongVersionCode()
     */
    default long getLongVersionCode() { return -1; }

    /**
     * Maps mime group name to the set of Mime types in a group. Mime groups declared by app are
     * populated with empty sets at construction. Mime groups can not be created/removed at runtime,
     * thus keys in this map should not change
     */
    @NonNull
    default Map<String, Set<String>> getMimeGroups() { return null; }

    /**
     * @see AndroidPackageApi#getPackageName()
     */
    @NonNull
    default String getPackageName() { return null; }

    /**
     * @see AndroidPackageApi#getPath()
     */
    @NonNull
    default File getPath() { return null; }

    @Nullable
    default String getPrimaryCpuAbi() { return null; }

    @Nullable
    default String getSeInfoOverride() { return null; }

    @Nullable
    default String getSecondaryCpuAbi() { return null; }

    /**
     * Retrieves the shared user ID. Note that the actual shared user data is not available here and
     * must be queried separately.
     *
     * @return the shared user this package is a part of, or null if it's not part of a shared user.
     */
    @Nullable
    default Integer getSharedUserId() { return null; }

    @NonNull
    default SigningInfo getSigningInfo() { return null; }

    /**
     * Valid users for this package, for use with {@link #getUserState(int)}.
     */
    default int[] getUserIds() { return null; }

    /**
     * Retrieves per-user state for this package. Acceptable user IDs are in {@link #getUserIds()}.
     */
    @Nullable
    default PackageUserState getUserState(@UserIdInt int userId) { return null; }

    /**
     * The actual files resolved for each shared library.
     *
     * @see R.styleable#AndroidManifestUsesLibrary
     */
    @NonNull
    default List<String> getUsesLibraryFiles() { return null; }

    /**
     * @see R.styleable#AndroidManifestUsesLibrary
     */
    @NonNull
    default List<SharedLibraryInfo> getUsesLibraryInfos() { return null; }

    /**
     * @see R.styleable#AndroidManifestUsesStaticLibrary
     */
    @NonNull
    default String[] getUsesStaticLibraries() { return null; }

    /**
     * @see R.styleable#AndroidManifestUsesStaticLibrary_version
     */
    @NonNull
    default long[] getUsesStaticLibrariesVersions() { return null; }

    /**
     * @see AndroidPackageApi#getVolumeUuid()
     */
    @Nullable
    default String getVolumeUuid() { return null; }

    /**
     * @see AndroidPackageApi#isExternalStorage()
     */
    default boolean isExternalStorage() { return false; }

    /**
     * Whether a package was installed --force-queryable such that it is always queryable by any
     * package, regardless of their manifest content.
     */
    default boolean isForceQueryableOverride() { return false; }

    /**
     * Whether a package is treated as hidden until it is installed for a user.
     *
     * @see PackageManager#MATCH_HIDDEN_UNTIL_INSTALLED_COMPONENTS
     * @see PackageManager#setSystemAppState
     */
    default boolean isHiddenUntilInstalled() { return false; }

    default boolean isInstallPermissionsFixed() { return false; }

    /**
     * @see AndroidPackageApi#isOdm()
     */
    default boolean isOdm() { return false; }

    /**
     * @see AndroidPackageApi#isOem()
     */
    default boolean isOem() { return false; }

    /**
     * @see AndroidPackageApi#isPrivileged()
     */
    default boolean isPrivileged() { return false; }

    /**
     * @see AndroidPackageApi#isProduct()
     */
    default boolean isProduct() { return false; }

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_REQUIRED_FOR_SYSTEM_USER
     */
    default boolean isRequiredForSystemUser() { return false; }

    /**
     * @see AndroidPackageApi#isSystem()
     */
    default boolean isSystem() { return false; }

    /**
     * @see AndroidPackageApi#isSystemExt()
     */
    default boolean isSystemExt() { return false; }

    /**
     * Whether or not an update is available. Ostensibly only for instant apps.
     */
    default boolean isUpdateAvailable() { return false; }

    /**
     * Whether this app is on the /data partition having been upgraded from a preinstalled app on a
     * system partition.
     */
    default boolean isUpdatedSystemApp() { return false; }

    /**
     * @see AndroidPackageApi#isVendor()
     */
    default boolean isVendor() { return false; }
}
