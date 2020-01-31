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
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;
import com.android.server.pm.parsing.pkg.ParsedPackage;

import java.io.File;
import java.util.Set;

// TODO: Move to .parsing sub-package
@VisibleForTesting
public interface PackageAbiHelper {
    /**
     * Derive and get the location of native libraries for the given package,
     * which varies depending on where and how the package was installed.
     */
    NativeLibraryPaths getNativeLibraryPaths(AndroidPackage pkg, PackageSetting pkgSetting,
            File appLib32InstallDir);

    /**
     * Calculate the abis for a bundled app. These can uniquely be determined from the contents of
     * the system partition, i.e whether it contains 64 or 32 bit shared libraries etc. We do not
     * validate any of this information, and instead assume that the system was built sensibly.
     */
    Abis getBundledAppAbis(AndroidPackage pkg);

    /**
     * Derive the ABI of a non-system package located at {@code pkg}. This information
     * is derived purely on the basis of the contents of {@code pkg} and {@code cpuAbiOverride}.
     *
     * If {@code extractLibs} is true, native libraries are extracted from the app if required.
     */
    Pair<Abis, NativeLibraryPaths> derivePackageAbi(AndroidPackage pkg, boolean isUpdatedSystemApp,
            String cpuAbiOverride, boolean extractLibs) throws PackageManagerException;

    /**
     * Calculates adjusted ABIs for a set of packages belonging to a shared user so that they all
     * match. i.e, so that all packages can be run inside a single process if required.
     *
     * Optionally, callers can pass in a parsed package via {@code scannedPackage} in which case
     * this function will either try and make the ABI for all packages in
     * {@code packagesForUser} match {@code scannedPackage} or will update the ABI of
     * {@code scannedPackage} to match the ABI selected for {@code packagesForUser}. This
     * variant is used when installing or updating a package that belongs to a shared user.
     *
     * NOTE: We currently only match for the primary CPU abi string. Matching the secondary
     * adds unnecessary complexity.
     *
     * @return the calculated primary abi that should be set for all non-specified packages
     *         belonging to the shared user.
     */
    @Nullable
    String getAdjustedAbiForSharedUser(
            Set<PackageSetting> packagesForUser, AndroidPackage scannedPackage);

    /**
     * The native library paths and related properties that should be set on a
     * {@link ParsedPackage}.
     */
    final class NativeLibraryPaths {
        public final String nativeLibraryRootDir;
        public final boolean nativeLibraryRootRequiresIsa;
        public final String nativeLibraryDir;
        public final String secondaryNativeLibraryDir;

        @VisibleForTesting
        NativeLibraryPaths(String nativeLibraryRootDir,
                boolean nativeLibraryRootRequiresIsa, String nativeLibraryDir,
                String secondaryNativeLibraryDir) {
            this.nativeLibraryRootDir = nativeLibraryRootDir;
            this.nativeLibraryRootRequiresIsa = nativeLibraryRootRequiresIsa;
            this.nativeLibraryDir = nativeLibraryDir;
            this.secondaryNativeLibraryDir = secondaryNativeLibraryDir;
        }

        public void applyTo(ParsedPackage pkg) {
            pkg.setNativeLibraryRootDir(nativeLibraryRootDir)
                    .setNativeLibraryRootRequiresIsa(nativeLibraryRootRequiresIsa)
                    .setNativeLibraryDir(nativeLibraryDir)
                    .setSecondaryNativeLibraryDir(secondaryNativeLibraryDir);
        }
    }

    /**
     * The primary and secondary ABIs that should be set on a package and its package setting.
     */
    final class Abis {
        public final String primary;
        public final String secondary;

        @VisibleForTesting
        Abis(String primary, String secondary) {
            this.primary = primary;
            this.secondary = secondary;
        }

        Abis(AndroidPackage pkg, PackageSetting pkgSetting)  {
            this(AndroidPackageUtils.getPrimaryCpuAbi(pkg, pkgSetting),
                    AndroidPackageUtils.getSecondaryCpuAbi(pkg, pkgSetting));
        }

        public void applyTo(ParsedPackage pkg) {
            pkg.setPrimaryCpuAbi(primary)
                    .setSecondaryCpuAbi(secondary);
        }
        public void applyTo(PackageSetting pkgSetting) {
            // pkgSetting might be null during rescan following uninstall of updates
            // to a bundled app, so accommodate that possibility.  The settings in
            // that case will be established later from the parsed package.
            //
            // If the settings aren't null, sync them up with what we've derived.
            if (pkgSetting != null) {
                pkgSetting.primaryCpuAbiString = primary;
                pkgSetting.secondaryCpuAbiString = secondary;
            }
        }
    }
}
