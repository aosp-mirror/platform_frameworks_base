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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.UserHandle;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.ParsedPackage;
import com.android.server.pm.pkg.parsing.ParsingPackageUtils;

/** A package to be scanned */
@VisibleForTesting
final class ScanRequest {
    /** The parsed package */
    @NonNull public final ParsedPackage mParsedPackage;
    /** The package this package replaces */
    @Nullable public final AndroidPackage mOldPkg;
    /** Shared user settings, if the old package has a shared user */
    @Nullable public final SharedUserSetting mOldSharedUserSetting;
    /**
     * Package settings of the currently installed version.
     * <p><em>IMPORTANT:</em> The contents of this object may be modified
     * during scan.
     */
    @Nullable public final PackageSetting mPkgSetting;
    /** Shared user settings of the currently installed package */
    @Nullable public final SharedUserSetting mSharedUserSetting;
    /** A copy of the settings for the currently installed version */
    @Nullable public final PackageSetting mOldPkgSetting;
    /** Package settings for the disabled version on the /system partition */
    @Nullable public final PackageSetting mDisabledPkgSetting;
    /** Package settings for the installed version under its original package name */
    @Nullable public final PackageSetting mOriginalPkgSetting;
    /** The real package name of a renamed application */
    @Nullable public final String mRealPkgName;
    public final @ParsingPackageUtils.ParseFlags int mParseFlags;
    public final @PackageManagerService.ScanFlags int mScanFlags;
    /** The user for which the package is being scanned */
    @Nullable public final UserHandle mUser;
    /** Whether or not the platform package is being scanned */
    public final boolean mIsPlatformPackage;
    /** Override value for package ABI if set during install */
    @Nullable public final String mCpuAbiOverride;

    ScanRequest(
            @NonNull ParsedPackage parsedPackage,
            @Nullable SharedUserSetting oldSharedUserSetting,
            @Nullable AndroidPackage oldPkg,
            @Nullable PackageSetting pkgSetting,
            @Nullable SharedUserSetting sharedUserSetting,
            @Nullable PackageSetting disabledPkgSetting,
            @Nullable PackageSetting originalPkgSetting,
            @Nullable String realPkgName,
            @ParsingPackageUtils.ParseFlags int parseFlags,
            @PackageManagerService.ScanFlags int scanFlags,
            boolean isPlatformPackage,
            @Nullable UserHandle user,
            @Nullable String cpuAbiOverride) {
        mParsedPackage = parsedPackage;
        mOldPkg = oldPkg;
        mPkgSetting = pkgSetting;
        mOldSharedUserSetting = oldSharedUserSetting;
        mSharedUserSetting = sharedUserSetting;
        mOldPkgSetting = pkgSetting == null ? null : new PackageSetting(pkgSetting);
        mDisabledPkgSetting = disabledPkgSetting;
        mOriginalPkgSetting = originalPkgSetting;
        mRealPkgName = realPkgName;
        mParseFlags = parseFlags;
        mScanFlags = scanFlags;
        mIsPlatformPackage = isPlatformPackage;
        mUser = user;
        mCpuAbiOverride = cpuAbiOverride;
    }
}
