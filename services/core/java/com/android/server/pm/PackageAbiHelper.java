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
 * limitations under the License.
 */

package com.android.server.pm;

import android.annotation.Nullable;
import android.content.pm.PackageParser;

import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.util.List;
import java.util.Set;

@VisibleForTesting
interface PackageAbiHelper {
    /**
     * Derive and set the location of native libraries for the given package,
     * which varies depending on where and how the package was installed.
     *
     * WARNING: This API enables modifying of the package.
     * TODO(b/137881067): Modify so that caller is responsible for setting pkg fields as necessary
     */
    void setNativeLibraryPaths(PackageParser.Package pkg, File appLib32InstallDir);

    /**
     * Calculate the abis and roots for a bundled app. These can uniquely
     * be determined from the contents of the system partition, i.e whether
     * it contains 64 or 32 bit shared libraries etc. We do not validate any
     * of this information, and instead assume that the system was built
     * sensibly.
     *
     * WARNING: This API enables modifying of the package.
     * TODO(b/137881067): Modify so that caller is responsible for setting pkg fields as necessary
     */
    void setBundledAppAbisAndRoots(PackageParser.Package pkg,
            PackageSetting pkgSetting);

    /**
     * Derive the ABI of a non-system package located at {@code scanFile}. This information
     * is derived purely on the basis of the contents of {@code scanFile} and
     * {@code cpuAbiOverride}.
     *
     * If {@code extractLibs} is true, native libraries are extracted from the app if required.
     *
     * WARNING: This API enables modifying of the package.
     * TODO(b/137881067): Modify so that caller is responsible for setting pkg fields as necessary
     */
    void derivePackageAbi(PackageParser.Package pkg, String cpuAbiOverride,
            boolean extractLibs)
            throws PackageManagerException;

    /**
     * Adjusts ABIs for a set of packages belonging to a shared user so that they all match.
     * i.e, so that all packages can be run inside a single process if required.
     *
     * Optionally, callers can pass in a parsed package via {@code newPackage} in which case
     * this function will either try and make the ABI for all packages in
     * {@code packagesForUser} match {@code scannedPackage} or will update the ABI of
     * {@code scannedPackage} to match the ABI selected for {@code packagesForUser}. This
     * variant is used when installing or updating a package that belongs to a shared user.
     *
     * NOTE: We currently only match for the primary CPU abi string. Matching the secondary
     * adds unnecessary complexity.
     *
     * WARNING: This API enables modifying of the package.
     * TODO(b/137881067): Modify so that caller is responsible for setting pkg fields as necessary
     */
    @Nullable
    List<String> adjustCpuAbisForSharedUser(
            Set<PackageSetting> packagesForUser, PackageParser.Package scannedPackage);
}
