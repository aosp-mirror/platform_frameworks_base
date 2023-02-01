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

import android.annotation.AppIdInt;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.Size;
import android.annotation.SystemApi;
import android.annotation.UserIdInt;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.SigningInfo;
import android.os.UserHandle;
import android.processor.immutability.Immutable;
import android.util.SparseArray;

import com.android.internal.R;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A wrapper containing device-specific state for an application. It wraps the mostly stateless
 * {@link AndroidPackage}, available through {@link #getAndroidPackage()}.
 *
 * Any fields whose values depend on dynamic state, disk location, enforcement policy,
 * cross-package dependencies, system/device owner/admin configuration, etc. are placed in this
 * interface.
 *
 * The backing memory is shared with the internal system server and thus there is no cost to
 * access these objects, unless the public API equivalent {@link PackageInfo} or
 * {@link ApplicationInfo}.
 *
 * This also means the data is immutable and will throw {@link UnsupportedOperationException} if
 * any collection type is mutated.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
@Immutable
public interface PackageState {

    /*
     * Until immutability or read-only caching is enabled, {@link PackageSetting} cannot be
     * returned directly, so {@link PackageStateImpl} is used to temporarily copy the data.
     * This is a relatively expensive operation since it has to create an object for every package,
     * but it's much lighter than the alternative of generating {@link PackageInfo} objects.
     * <p>
     * TODO: Documentation
     * TODO: Currently missing, should be exposed as API?
     *   - keySetData
     *   - installSource
     *   - incrementalStates
     */

    // Non-doc comment invisible to API consumers:
    // Guidelines:
    //  - All return values should prefer non-null, immutable interfaces with only exposed getters
    //    - Unless null itself communicates something important
    //    - If the type is a Java collection type, it must be wrapped with unmodifiable
    //  - All type names must be non-suffixed, with any internal types being refactored to suffix
    //    with _Internal as necessary
    //  - No exposure of raw values that are overridden during parsing, such as CPU ABI
    //  - Mirroring another available system or public API is not enough justification to violate
    //    these guidelines

    /**
     * This can be null whenever a physical APK on device is missing. This can be the result of
     * removing an external storage device where the APK resides.
     * <p/>
     * This will result in the system reading the state from disk, but without being able to parse
     * the base APK's AndroidManifest.xml to read all of its metadata. The only available data that
     * is written and read is the minimal set required to perform other checks in the system.
     * <p/>
     * This is important in order to enforce uniqueness within the system, as the package, even if
     * on a removed storage device, is still considered installed. Another package of the same
     * application ID or declaring the same permissions or similar cannot be installed.
     * <p/>
     * Re-attaching the storage device to make the APK available should allow the user to use the
     * app once the device reboots or otherwise re-scans it.
     * <p/>
     * This can also occur in an device OTA situation where the package is no longer parsable on
     * an updated SDK version, causing it to be rejected, but the state associated with it retained,
     * similarly to if the package had been uninstalled with the --keep-data option.
     */
    @Nullable
    AndroidPackage getAndroidPackage();

    /**
     * The non-user-specific UID, or the UID if the user ID is
     * {@link android.os.UserHandle#SYSTEM}.
     */
    @AppIdInt
    int getAppId();

    /**
     * Retrieves effective hidden API policy for this app. The state can be dependent on
     * {@link #getAndroidPackage()} availability and whether the app is a system app.
     *
     * Note that during process start, this policy may be mutated by device specific process
     * configuration, so this value isn't truly final.
     *
     * @return The (mostly) final {@link ApplicationInfo.HiddenApiEnforcementPolicy} that should be
     * applied to this package.
     */
    @ApplicationInfo.HiddenApiEnforcementPolicy
    int getHiddenApiEnforcementPolicy();

    /**
     * @see PackageInfo#packageName
     * @see AndroidPackage#getPackageName()
     */
    @NonNull
    String getPackageName();

    /**
     * @see ApplicationInfo#primaryCpuAbi
     */
    @Nullable
    String getPrimaryCpuAbi();

    /**
     * @see ApplicationInfo#secondaryCpuAbi
     */
    @Nullable
    String getSecondaryCpuAbi();

    /**
     * @see ApplicationInfo#seInfo
     * @return The SE info for this package, which may be overridden by a system configured value,
     * or null if the package isn't available.
     */
    @Nullable
    String getSeInfo();

    /**
     * @return State for a user or {@link PackageUserState#DEFAULT} if the state doesn't exist.
     */
    @NonNull
    PackageUserState getStateForUser(@NonNull UserHandle user);

    /**
     * List of shared libraries that this package declares a dependency on. This includes all
     * types of libraries, system or app provided and Java or native.
     */
    @NonNull
    List<SharedLibrary> getSharedLibraryDependencies();

    /** Whether this represents an APEX module. This is different from an APK inside an APEX. */
    boolean isApex();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_PRIVILEGED
     */
    boolean isPrivileged();

    /**
     * @see ApplicationInfo#FLAG_SYSTEM
     */
    boolean isSystem();

    /**
     * Whether this app is on the /data partition having been upgraded from a preinstalled app on a
     * system partition.
     */
    boolean isUpdatedSystemApp();

    // Methods below this comment are not yet exposed as API

    /**
     * Value set through {@link PackageManager#setApplicationCategoryHint(String, int)}. Only
     * applied if the application itself does not declare a category.
     *
     * @see AndroidPackage#getCategory()
     * @hide
     */
    int getCategoryOverride();

    /**
     * The install time CPU override, if any. This value is written at install time
     * and doesn't change during the life of an install. If non-null,
     * {@link #getPrimaryCpuAbiLegacy()} will also contain the same value.
     *
     * @hide
     */
    @Nullable
    String getCpuAbiOverride();

    /**
     * In epoch milliseconds. The last modified time of the file directory which houses the app
     * APKs. Only updated on package update; does not track realtime modifications.
     *
     * @hide
     */
    long getLastModifiedTime();

    /**
     * An aggregation across the framework of the last time an app was used for a particular reason.
     * Keys are indexes into the array represented by {@link PackageManager.NotifyReason}, values
     * are in epoch milliseconds.
     *
     * @hide
     */
    @Immutable.Ignore
    @Size(PackageManager.NOTIFY_PACKAGE_USE_REASONS_COUNT)
    @NonNull
    long[] getLastPackageUsageTime();

    /**
     * In epoch milliseconds. The timestamp of the last time the package on device went through
     * an update package installation.
     *
     * @hide
     */
    long getLastUpdateTime();

    /**
     * Cached here in case the physical code directory on device is unmounted.
     * @see AndroidPackage#getLongVersionCode()
     * @hide
     */
    long getVersionCode();

    /**
     * Maps mime group name to the set of Mime types in a group. Mime groups declared by app are
     * populated with empty sets at construction. Mime groups can not be created/removed at runtime,
     * thus keys in this map should not change.
     *
     * @hide
     */
    @NonNull
    Map<String, Set<String>> getMimeGroups();

    /**
     * @see AndroidPackage#getPath()
     * @hide
     */
    @NonNull
    File getPath();

    /**
     * Whether the package shares the same user ID as other packages
     * @hide
     */
    boolean hasSharedUser();

    /**
     * Retrieves the shared user app ID. Note that the actual shared user data is not available here
     * and must be queried separately.
     *
     * @return the app ID of the shared user that this package is a part of, or -1 if it's not part
     * of a shared user.
     * @hide
     */
    int getSharedUserAppId();

    /** @hide */
    @Immutable.Ignore
    @NonNull
    SigningInfo getSigningInfo();

    /** @hide */
    @Immutable.Ignore
    @NonNull
    SparseArray<? extends PackageUserState> getUserStates();

    /**
     * @return the result of {@link #getUserStates()}.get(userId) or
     * {@link PackageUserState#DEFAULT} if the state doesn't exist.
     * @hide
     */
    @NonNull
    default PackageUserState getUserStateOrDefault(@UserIdInt int userId) {
        PackageUserState userState = getUserStates().get(userId);
        return userState == null ? PackageUserState.DEFAULT : userState;
    }

    /**
     * The actual files resolved for each shared library.
     *
     * @see R.styleable#AndroidManifestUsesLibrary
     * @hide
     */
    @NonNull
    List<String> getUsesLibraryFiles();

    /**
     * @see R.styleable#AndroidManifestUsesSdkLibrary
     * @hide
     */
    @Immutable.Ignore
    @NonNull
    String[] getUsesSdkLibraries();

    /**
     * @see R.styleable#AndroidManifestUsesSdkLibrary_versionMajor
     * @hide
     */
    @Immutable.Ignore
    @NonNull
    long[] getUsesSdkLibrariesVersionsMajor();

    /**
     * @see R.styleable#AndroidManifestUsesStaticLibrary
     * @hide
     */
    @Immutable.Ignore
    @NonNull
    String[] getUsesStaticLibraries();

    /**
     * @see R.styleable#AndroidManifestUsesStaticLibrary_version
     * @hide
     */
    @Immutable.Ignore
    @NonNull
    long[] getUsesStaticLibrariesVersions();

    /**
     * @see AndroidPackage#getVolumeUuid()
     * @hide
     */
    @Nullable
    String getVolumeUuid();

    /**
     * @see AndroidPackage#isExternalStorage()
     * @hide
     */
    boolean isExternalStorage();

    /**
     * Whether a package was installed --force-queryable such that it is always queryable by any
     * package, regardless of their manifest content.
     *
     * @hide
     */
    boolean isForceQueryableOverride();

    /**
     * Whether a package is treated as hidden until it is installed for a user.
     *
     * @see PackageManager#MATCH_HIDDEN_UNTIL_INSTALLED_COMPONENTS
     * @see PackageManager#setSystemAppState
     * @hide
     */
    boolean isHiddenUntilInstalled();

    /**
     * @see com.android.server.pm.permission.UserPermissionState
     * @hide
     */
    boolean isInstallPermissionsFixed();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_ODM
     * @hide
     */
    boolean isOdm();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_OEM
     * @hide
     */
    boolean isOem();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_PRODUCT
     * @hide
     */
    boolean isProduct();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_REQUIRED_FOR_SYSTEM_USER
     * @hide
     */
    boolean isRequiredForSystemUser();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_SYSTEM_EXT
     * @hide
     */
    boolean isSystemExt();

    /**
     * Whether or not an update is available. Ostensibly only for instant apps.
     * @hide
     */
    boolean isUpdateAvailable();

    /**
     * Whether this app is packaged in an updated apex.
     *
     * @hide
     */
    boolean isApkInUpdatedApex();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_VENDOR
     * @hide
     */
    boolean isVendor();

    /**
     * The name of the APEX module containing this package, if it is an APEX or APK-in-APEX.
     * @hide
     */
    @Nullable
    String getApexModuleName();
}
