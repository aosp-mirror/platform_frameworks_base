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
import android.content.pm.SharedLibraryInfo;
import android.os.Process;

import com.android.internal.annotations.VisibleForTesting;

import java.util.List;

/** The result of a package scan. */
@VisibleForTesting
final class ScanResult {
    /** The request that initiated the scan that produced this result. */
    @NonNull public final ScanRequest mRequest;
    /**
     * Whether or not the original PackageSetting needs to be updated with this result on
     * commit.
     */
    public final boolean mExistingSettingCopied;
    /**
     * The previous app ID if the app decided to leave a shared user ID.
     * The value is set *only* if the app is leaving a shared user ID.
     * Default value is Process.INVALID_UID.
     */
    public final int mPreviousAppId;
    /**
     * The final package settings. This may be the same object passed in
     * the {@link ScanRequest}, but, with modified values.
     */
    @Nullable
    public final PackageSetting mPkgSetting;

    // TODO(b/260124949): Check if this can be dropped when the legacy PackageManager dexopt code is
    // cleaned up.
    /** ABI code paths that have changed in the package scan */
    @Nullable public final List<String> mChangedAbiCodePath;

    public final SharedLibraryInfo mSdkSharedLibraryInfo;

    public final SharedLibraryInfo mStaticSharedLibraryInfo;

    public final List<SharedLibraryInfo> mDynamicSharedLibraryInfos;

    ScanResult(
            @NonNull ScanRequest request,
            @Nullable PackageSetting pkgSetting,
            @Nullable List<String> changedAbiCodePath, boolean existingSettingCopied,
            int previousAppId,
            SharedLibraryInfo sdkSharedLibraryInfo,
            SharedLibraryInfo staticSharedLibraryInfo,
            List<SharedLibraryInfo> dynamicSharedLibraryInfos) {
        mRequest = request;
        mPkgSetting = pkgSetting;
        mChangedAbiCodePath = changedAbiCodePath;
        mExistingSettingCopied = existingSettingCopied;
        // Hardcode mPreviousAppId to INVALID_UID (http://b/221088088)
        // This will disable all migration code paths in PMS and PermMS
        mPreviousAppId = Process.INVALID_UID;
        mSdkSharedLibraryInfo = sdkSharedLibraryInfo;
        mStaticSharedLibraryInfo = staticSharedLibraryInfo;
        mDynamicSharedLibraryInfos = dynamicSharedLibraryInfos;
    }

    /**
     * Whether the original PackageSetting needs to be updated with
     * a new app ID. Useful when leaving a sharedUserId.
     */
    public boolean needsNewAppId() {
        return mPreviousAppId != Process.INVALID_UID;
    }
}
