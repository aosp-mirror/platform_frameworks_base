/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.pm;

import static android.content.pm.PackageManager.INSTALL_FAILED_MISSING_SHARED_LIBRARY;

import static com.android.server.pm.PackageManagerService.TAG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.os.Build;
import android.util.PackageUtils;
import android.util.Slog;

import com.android.server.compat.PlatformCompat;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.ParsedPackage;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.utils.WatchedLongSparseArray;

import libcore.util.HexEncoding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SharedLibraryUtils {
    private static final boolean DEBUG_SHARED_LIBRARIES = false;

    /**
     * Apps targeting Android S and above need to declare dependencies to the public native
     * shared libraries that are defined by the device maker using {@code uses-native-library} tag
     * in its {@code AndroidManifest.xml}.
     *
     * If any of the dependencies cannot be satisfied, i.e. one of the dependency doesn't exist,
     * the package manager rejects to install the app. The dependency can be specified as optional
     * using {@code android:required} attribute in the tag, in which case failing to satisfy the
     * dependency doesn't stop the installation.
     * <p>Once installed, an app is provided with only the native shared libraries that are
     * specified in the app manifest. {@code dlopen}ing a native shared library that doesn't appear
     * in the app manifest will fail even if it actually exists on the device.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.R)
    private static final long ENFORCE_NATIVE_SHARED_LIBRARY_DEPENDENCIES = 142191088;

    /**
     * Compare the newly scanned package with current system state to see which of its declared
     * shared libraries should be allowed to be added to the system.
     */
    public static List<SharedLibraryInfo> getAllowedSharedLibInfos(
            ScanResult scanResult,
            Map<String, WatchedLongSparseArray<SharedLibraryInfo>> existingSharedLibraries) {
        // Let's used the parsed package as scanResult.pkgSetting may be null
        final ParsedPackage parsedPackage = scanResult.mRequest.mParsedPackage;
        if (scanResult.mSdkSharedLibraryInfo == null && scanResult.mStaticSharedLibraryInfo == null
                && scanResult.mDynamicSharedLibraryInfos == null) {
            return null;
        }

        // Any app can add new SDKs and static shared libraries.
        if (scanResult.mSdkSharedLibraryInfo != null) {
            return Collections.singletonList(scanResult.mSdkSharedLibraryInfo);
        }
        if (scanResult.mStaticSharedLibraryInfo != null) {
            return Collections.singletonList(scanResult.mStaticSharedLibraryInfo);
        }
        final boolean hasDynamicLibraries = parsedPackage.isSystem()
                && scanResult.mDynamicSharedLibraryInfos != null;
        if (!hasDynamicLibraries) {
            return null;
        }
        final boolean isUpdatedSystemApp = scanResult.mPkgSetting.getPkgState()
                .isUpdatedSystemApp();
        // We may not yet have disabled the updated package yet, so be sure to grab the
        // current setting if that's the case.
        final PackageSetting updatedSystemPs = isUpdatedSystemApp
                ? scanResult.mRequest.mDisabledPkgSetting == null
                ? scanResult.mRequest.mOldPkgSetting
                : scanResult.mRequest.mDisabledPkgSetting
                : null;
        if (isUpdatedSystemApp && (updatedSystemPs.getPkg() == null
                || updatedSystemPs.getPkg().getLibraryNames() == null)) {
            Slog.w(TAG, "Package " + parsedPackage.getPackageName()
                    + " declares libraries that are not declared on the system image; skipping");
            return null;
        }
        final ArrayList<SharedLibraryInfo> infos =
                new ArrayList<>(scanResult.mDynamicSharedLibraryInfos.size());
        for (SharedLibraryInfo info : scanResult.mDynamicSharedLibraryInfos) {
            final String name = info.getName();
            if (isUpdatedSystemApp) {
                // New library entries can only be added through the
                // system image.  This is important to get rid of a lot
                // of nasty edge cases: for example if we allowed a non-
                // system update of the app to add a library, then uninstalling
                // the update would make the library go away, and assumptions
                // we made such as through app install filtering would now
                // have allowed apps on the device which aren't compatible
                // with it.  Better to just have the restriction here, be
                // conservative, and create many fewer cases that can negatively
                // impact the user experience.
                if (!updatedSystemPs.getPkg().getLibraryNames().contains(name)) {
                    Slog.w(TAG, "Package " + parsedPackage.getPackageName()
                            + " declares library " + name
                            + " that is not declared on system image; skipping");
                    continue;
                }
            }
            if (sharedLibExists(
                    name, SharedLibraryInfo.VERSION_UNDEFINED, existingSharedLibraries)) {
                Slog.w(TAG, "Package " + parsedPackage.getPackageName() + " declares library "
                        + name + " that already exists; skipping");
                continue;
            }
            infos.add(info);
        }
        return infos;
    }

    public static boolean sharedLibExists(final String name, final long version,
            Map<String, WatchedLongSparseArray<SharedLibraryInfo>> librarySource) {
        WatchedLongSparseArray<SharedLibraryInfo> versionedLib = librarySource.get(name);
        return versionedLib != null && versionedLib.indexOfKey(version) >= 0;
    }

    /**
     * Returns false if the adding shared library already exists in the map and so could not be
     * added.
     */
    public static boolean addSharedLibraryToPackageVersionMap(
            Map<String, WatchedLongSparseArray<SharedLibraryInfo>> target,
            SharedLibraryInfo library) {
        final String name = library.getName();
        if (target.containsKey(name)) {
            if (library.getType() != SharedLibraryInfo.TYPE_STATIC) {
                // We've already added this non-version-specific library to the map.
                return false;
            } else if (target.get(name).indexOfKey(library.getLongVersion()) >= 0) {
                // We've already added this version of a version-specific library to the map.
                return false;
            }
        } else {
            target.put(name, new WatchedLongSparseArray<>());
        }
        target.get(name).put(library.getLongVersion(), library);
        return true;
    }

    public static ArrayList<SharedLibraryInfo> collectSharedLibraryInfos(AndroidPackage pkg,
            Map<String, AndroidPackage> availablePackages,
            @NonNull final Map<String, WatchedLongSparseArray<SharedLibraryInfo>> existingLibraries,
            @Nullable final Map<String, WatchedLongSparseArray<SharedLibraryInfo>> newLibraries,
            PlatformCompat platformCompat) throws PackageManagerException {
        if (pkg == null) {
            return null;
        }
        // The collection used here must maintain the order of addition (so
        // that libraries are searched in the correct order) and must have no
        // duplicates.
        ArrayList<SharedLibraryInfo> usesLibraryInfos = null;
        if (!pkg.getUsesLibraries().isEmpty()) {
            usesLibraryInfos = collectSharedLibraryInfos(pkg.getUsesLibraries(), null, null,
                    pkg.getPackageName(), "shared", true, pkg.getTargetSdkVersion(), null,
                    availablePackages, existingLibraries, newLibraries);
        }
        if (!pkg.getUsesStaticLibraries().isEmpty()) {
            usesLibraryInfos = collectSharedLibraryInfos(pkg.getUsesStaticLibraries(),
                    pkg.getUsesStaticLibrariesVersions(), pkg.getUsesStaticLibrariesCertDigests(),
                    pkg.getPackageName(), "static shared", true, pkg.getTargetSdkVersion(),
                    usesLibraryInfos, availablePackages, existingLibraries, newLibraries);
        }
        if (!pkg.getUsesOptionalLibraries().isEmpty()) {
            usesLibraryInfos = collectSharedLibraryInfos(pkg.getUsesOptionalLibraries(), null, null,
                    pkg.getPackageName(), "shared", false, pkg.getTargetSdkVersion(),
                    usesLibraryInfos, availablePackages, existingLibraries, newLibraries);
        }
        if (platformCompat.isChangeEnabledInternal(ENFORCE_NATIVE_SHARED_LIBRARY_DEPENDENCIES,
                pkg.getPackageName(), pkg.getTargetSdkVersion())) {
            if (!pkg.getUsesNativeLibraries().isEmpty()) {
                usesLibraryInfos = collectSharedLibraryInfos(pkg.getUsesNativeLibraries(), null,
                        null, pkg.getPackageName(), "native shared", true,
                        pkg.getTargetSdkVersion(), usesLibraryInfos, availablePackages,
                        existingLibraries, newLibraries);
            }
            if (!pkg.getUsesOptionalNativeLibraries().isEmpty()) {
                usesLibraryInfos = collectSharedLibraryInfos(pkg.getUsesOptionalNativeLibraries(),
                        null, null, pkg.getPackageName(), "native shared", false,
                        pkg.getTargetSdkVersion(), usesLibraryInfos, availablePackages,
                        existingLibraries, newLibraries);
            }
        }
        if (!pkg.getUsesSdkLibraries().isEmpty()) {
            usesLibraryInfos = collectSharedLibraryInfos(pkg.getUsesSdkLibraries(),
                    pkg.getUsesSdkLibrariesVersionsMajor(), pkg.getUsesSdkLibrariesCertDigests(),
                    pkg.getPackageName(), "sdk", true, pkg.getTargetSdkVersion(), usesLibraryInfos,
                    availablePackages, existingLibraries, newLibraries);
        }
        return usesLibraryInfos;
    }

    public static ArrayList<SharedLibraryInfo> collectSharedLibraryInfos(
            @NonNull List<String> requestedLibraries,
            @Nullable long[] requiredVersions, @Nullable String[][] requiredCertDigests,
            @NonNull String packageName, @NonNull String libraryType, boolean required,
            int targetSdk, @Nullable ArrayList<SharedLibraryInfo> outUsedLibraries,
            @NonNull final Map<String, AndroidPackage> availablePackages,
            @NonNull final Map<String, WatchedLongSparseArray<SharedLibraryInfo>> existingLibraries,
            @Nullable final Map<String, WatchedLongSparseArray<SharedLibraryInfo>> newLibraries)
            throws PackageManagerException {
        final int libCount = requestedLibraries.size();
        for (int i = 0; i < libCount; i++) {
            final String libName = requestedLibraries.get(i);
            final long libVersion = requiredVersions != null ? requiredVersions[i]
                    : SharedLibraryInfo.VERSION_UNDEFINED;
            final SharedLibraryInfo libraryInfo =
                    getSharedLibraryInfo(libName, libVersion, existingLibraries, newLibraries);
            if (libraryInfo == null) {
                if (required) {
                    throw new PackageManagerException(INSTALL_FAILED_MISSING_SHARED_LIBRARY,
                            "Package " + packageName + " requires unavailable " + libraryType
                                    + " library " + libName + "; failing!");
                } else if (DEBUG_SHARED_LIBRARIES) {
                    Slog.i(TAG, "Package " + packageName + " desires unavailable " + libraryType
                            + " library " + libName + "; ignoring!");
                }
            } else {
                if (requiredVersions != null && requiredCertDigests != null) {
                    if (libraryInfo.getLongVersion() != requiredVersions[i]) {
                        throw new PackageManagerException(INSTALL_FAILED_MISSING_SHARED_LIBRARY,
                                "Package " + packageName + " requires unavailable " + libraryType
                                        + " library " + libName + " version "
                                        + libraryInfo.getLongVersion() + "; failing!");
                    }
                    AndroidPackage pkg = availablePackages.get(libraryInfo.getPackageName());
                    SigningDetails libPkg = pkg == null ? null : pkg.getSigningDetails();
                    if (libPkg == null) {
                        throw new PackageManagerException(INSTALL_FAILED_MISSING_SHARED_LIBRARY,
                                "Package " + packageName + " requires unavailable " + libraryType
                                        + " library; failing!");
                    }
                    final String[] expectedCertDigests = requiredCertDigests[i];
                    if (expectedCertDigests.length > 1) {
                        // For apps targeting O MR1 we require explicit enumeration of all certs.
                        final String[] libCertDigests = (targetSdk >= Build.VERSION_CODES.O_MR1)
                                ? PackageUtils.computeSignaturesSha256Digests(
                                libPkg.getSignatures())
                                : PackageUtils.computeSignaturesSha256Digests(
                                        new Signature[]{libPkg.getSignatures()[0]});

                        // Take a shortcut if sizes don't match. Note that if an app doesn't
                        // target O we don't parse the "additional-certificate" tags similarly
                        // how we only consider all certs only for apps targeting O (see above).
                        // Therefore, the size check is safe to make.
                        if (expectedCertDigests.length != libCertDigests.length) {
                            throw new PackageManagerException(INSTALL_FAILED_MISSING_SHARED_LIBRARY,
                                    "Package " + packageName + " requires differently signed "
                                            + libraryType + " library; failing!");
                        }

                        // Use a predictable order as signature order may vary
                        Arrays.sort(libCertDigests);
                        Arrays.sort(expectedCertDigests);

                        final int certCount = libCertDigests.length;
                        for (int j = 0; j < certCount; j++) {
                            if (!libCertDigests[j].equalsIgnoreCase(expectedCertDigests[j])) {
                                throw new PackageManagerException(
                                        INSTALL_FAILED_MISSING_SHARED_LIBRARY,
                                        "Package " + packageName + " requires differently signed "
                                                + libraryType + " library; failing!");
                            }
                        }
                    } else {
                        // lib signing cert could have rotated beyond the one expected, check to see
                        // if the new one has been blessed by the old
                        byte[] digestBytes = HexEncoding.decode(
                                expectedCertDigests[0], false /* allowSingleChar */);
                        if (!libPkg.hasSha256Certificate(digestBytes)) {
                            throw new PackageManagerException(INSTALL_FAILED_MISSING_SHARED_LIBRARY,
                                    "Package " + packageName + " requires differently signed "
                                            + libraryType + " library; failing!");
                        }
                    }
                }
                if (outUsedLibraries == null) {
                    outUsedLibraries = new ArrayList<>();
                }
                outUsedLibraries.add(libraryInfo);
            }
        }
        return outUsedLibraries;
    }

    @Nullable
    public static SharedLibraryInfo getSharedLibraryInfo(String name, long version,
            Map<String, WatchedLongSparseArray<SharedLibraryInfo>> existingLibraries,
            @Nullable Map<String, WatchedLongSparseArray<SharedLibraryInfo>> newLibraries) {
        if (newLibraries != null) {
            final WatchedLongSparseArray<SharedLibraryInfo> versionedLib = newLibraries.get(name);
            SharedLibraryInfo info = null;
            if (versionedLib != null) {
                info = versionedLib.get(version);
            }
            if (info != null) {
                return info;
            }
        }
        final WatchedLongSparseArray<SharedLibraryInfo> versionedLib = existingLibraries.get(name);
        if (versionedLib == null) {
            return null;
        }
        return versionedLib.get(version);
    }

    public static List<SharedLibraryInfo> findSharedLibraries(PackageStateInternal pkgSetting) {
        if (!pkgSetting.getTransientState().getUsesLibraryInfos().isEmpty()) {
            ArrayList<SharedLibraryInfo> retValue = new ArrayList<>();
            Set<String> collectedNames = new HashSet<>();
            for (SharedLibraryInfo info : pkgSetting.getTransientState().getUsesLibraryInfos()) {
                findSharedLibrariesRecursive(info, retValue, collectedNames);
            }
            return retValue;
        } else {
            return Collections.emptyList();
        }
    }

    private static void findSharedLibrariesRecursive(SharedLibraryInfo info,
            ArrayList<SharedLibraryInfo> collected, Set<String> collectedNames) {
        if (!collectedNames.contains(info.getName())) {
            collectedNames.add(info.getName());
            collected.add(info);

            if (info.getDependencies() != null) {
                for (SharedLibraryInfo dep : info.getDependencies()) {
                    findSharedLibrariesRecursive(dep, collected, collectedNames);
                }
            }
        }
    }

}
