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
import android.content.pm.SharedLibraryInfo;

import com.android.internal.annotations.VisibleForTesting;

import java.util.List;

/** The result of a package scan. */
@VisibleForTesting
final class ScanResult {
    /** The request that initiated the scan that produced this result. */
    public final ScanRequest mRequest;
    /** Whether or not the package scan was successful */
    public final boolean mSuccess;
    /**
     * Whether or not the original PackageSetting needs to be updated with this result on
     * commit.
     */
    public final boolean mExistingSettingCopied;
    /**
     * Whether or not the original PackageSetting needs to be updated with
     * a new uid. Useful when leaving a sharedUserID.
     */
    public final boolean mNeedsNewAppId;
    /**
     * The final package settings. This may be the same object passed in
     * the {@link ScanRequest}, but, with modified values.
     */
    @Nullable
    public final PackageSetting mPkgSetting;
    /** ABI code paths that have changed in the package scan */
    @Nullable public final List<String> mChangedAbiCodePath;

    public final SharedLibraryInfo mStaticSharedLibraryInfo;

    public final List<SharedLibraryInfo> mDynamicSharedLibraryInfos;

    ScanResult(
            ScanRequest request, boolean success,
            @Nullable PackageSetting pkgSetting,
            @Nullable List<String> changedAbiCodePath, boolean existingSettingCopied,
            boolean needsNewAppId,
            SharedLibraryInfo staticSharedLibraryInfo,
            List<SharedLibraryInfo> dynamicSharedLibraryInfos) {
        mRequest = request;
        mSuccess = success;
        mPkgSetting = pkgSetting;
        mChangedAbiCodePath = changedAbiCodePath;
        mExistingSettingCopied = existingSettingCopied;
        mNeedsNewAppId = needsNewAppId;
        mStaticSharedLibraryInfo = staticSharedLibraryInfo;
        mDynamicSharedLibraryInfos = dynamicSharedLibraryInfos;
    }
}
