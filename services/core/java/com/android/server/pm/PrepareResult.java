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

import android.annotation.Nullable;

import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.ParsedPackage;

/**
 * The set of data needed to successfully install the prepared package. This includes data that
 * will be used to scan and reconcile the package.
 */
final class PrepareResult {
    public final boolean mReplace;
    public final int mScanFlags;
    public final int mParseFlags;
    @Nullable /* The original Package if it is being replaced, otherwise {@code null} */
    public final AndroidPackage mExistingPackage;
    public final ParsedPackage mPackageToScan;
    public final boolean mClearCodeCache;
    public final boolean mSystem;
    public final PackageSetting mOriginalPs;
    public final PackageSetting mDisabledPs;

    PrepareResult(boolean replace, int scanFlags,
            int parseFlags, AndroidPackage existingPackage,
            ParsedPackage packageToScan, boolean clearCodeCache, boolean system,
            PackageSetting originalPs, PackageSetting disabledPs) {
        mReplace = replace;
        mScanFlags = scanFlags;
        mParseFlags = parseFlags;
        mExistingPackage = existingPackage;
        mPackageToScan = packageToScan;
        mClearCodeCache = clearCodeCache;
        mSystem = system;
        mOriginalPs = originalPs;
        mDisabledPs = disabledPs;
    }
}
