/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.internal.pm.parsing.pkg;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;

import com.android.internal.pm.pkg.parsing.ParsingPackageHidden;
import com.android.server.pm.pkg.AndroidPackage;

/** @hide */
public class AndroidPackageLegacyUtils {

    private AndroidPackageLegacyUtils() {
    }

    /**
     * Returns the primary ABI as parsed from the package. Used only during parsing and derivation.
     * Otherwise prefer {@link PackageState#getPrimaryCpuAbi()}.
     */
    public static String getRawPrimaryCpuAbi(AndroidPackage pkg) {
        return ((AndroidPackageHidden) pkg).getPrimaryCpuAbi();
    }

    /**
     * Returns the secondary ABI as parsed from the package. Used only during parsing and
     * derivation. Otherwise prefer {@link PackageState#getSecondaryCpuAbi()}.
     */
    public static String getRawSecondaryCpuAbi(@NonNull AndroidPackage pkg) {
        return ((AndroidPackageHidden) pkg).getSecondaryCpuAbi();
    }

    @Deprecated
    @NonNull
    public static ApplicationInfo generateAppInfoWithoutState(AndroidPackage pkg) {
        return ((AndroidPackageHidden) pkg).toAppInfoWithoutState();
    }

    /**
     * Replacement of unnecessary legacy getRealPackage. Only returns a value if the package was
     * actually renamed.
     */
    @Nullable
    public static String getRealPackageOrNull(@NonNull AndroidPackage pkg, boolean isSystem) {
        if (pkg.getOriginalPackages().isEmpty() || !isSystem) {
            return null;
        }

        return pkg.getManifestPackageName();
    }

    public static void fillVersionCodes(@NonNull AndroidPackage pkg, @NonNull PackageInfo info) {
        info.versionCode = ((ParsingPackageHidden) pkg).getVersionCode();
        info.versionCodeMajor = ((ParsingPackageHidden) pkg).getVersionCodeMajor();
    }

    /**
     * @deprecated Use {@link PackageState#isSystem}
     */
    @Deprecated
    public static boolean isSystem(@NonNull AndroidPackage pkg) {
        return ((AndroidPackageHidden) pkg).isSystem();
    }

    /**
     * @deprecated Use {@link PackageState#isSystemExt}
     */
    @Deprecated
    public static boolean isSystemExt(@NonNull AndroidPackage pkg) {
        return ((AndroidPackageHidden) pkg).isSystemExt();
    }

    /**
     * @deprecated Use {@link PackageState#isPrivileged}
     */
    @Deprecated
    public static boolean isPrivileged(@NonNull AndroidPackage pkg) {
        return ((AndroidPackageHidden) pkg).isPrivileged();
    }

    /**
     * @deprecated Use {@link PackageState#isOem}
     */
    @Deprecated
    public static boolean isOem(@NonNull AndroidPackage pkg) {
        return ((AndroidPackageHidden) pkg).isOem();
    }

    /**
     * @deprecated Use {@link PackageState#isVendor}
     */
    @Deprecated
    public static boolean isVendor(@NonNull AndroidPackage pkg) {
        return ((AndroidPackageHidden) pkg).isVendor();
    }

    /**
     * @deprecated Use {@link PackageState#isProduct}
     */
    @Deprecated
    public static boolean isProduct(@NonNull AndroidPackage pkg) {
        return ((AndroidPackageHidden) pkg).isProduct();
    }

    /**
     * @deprecated Use {@link PackageState#isOdm}
     */
    @Deprecated
    public static boolean isOdm(@NonNull AndroidPackage pkg) {
        return ((AndroidPackageHidden) pkg).isOdm();
    }
}
