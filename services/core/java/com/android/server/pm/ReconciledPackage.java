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
import android.content.pm.SigningDetails;
import android.util.ArrayMap;

import com.android.server.pm.parsing.pkg.AndroidPackage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A container of all data needed to commit a package to in-memory data structures and to disk.
 * TODO: move most of the data contained here into a PackageSetting for commit.
 */
final class ReconciledPackage {
    public final ReconcileRequest mRequest;
    public final PackageSetting mPkgSetting;
    public final ScanResult mScanResult;
    // TODO: Remove install-specific details from the reconcile result
    public final PackageInstalledInfo mInstallResult;
    @Nullable public final PrepareResult mPrepareResult;
    @Nullable public final InstallArgs mInstallArgs;
    public final DeletePackageAction mDeletePackageAction;
    public final List<SharedLibraryInfo> mAllowedSharedLibraryInfos;
    public final SigningDetails mSigningDetails;
    public final boolean mSharedUserSignaturesChanged;
    public ArrayList<SharedLibraryInfo> mCollectedSharedLibraryInfos;
    public final boolean mRemoveAppKeySetData;

    ReconciledPackage(ReconcileRequest request,
            InstallArgs installArgs,
            PackageSetting pkgSetting,
            PackageInstalledInfo installResult,
            PrepareResult prepareResult,
            ScanResult scanResult,
            DeletePackageAction deletePackageAction,
            List<SharedLibraryInfo> allowedSharedLibraryInfos,
            SigningDetails signingDetails,
            boolean sharedUserSignaturesChanged,
            boolean removeAppKeySetData) {
        mRequest = request;
        mInstallArgs = installArgs;
        mPkgSetting = pkgSetting;
        mInstallResult = installResult;
        mPrepareResult = prepareResult;
        mScanResult = scanResult;
        mDeletePackageAction = deletePackageAction;
        mAllowedSharedLibraryInfos = allowedSharedLibraryInfos;
        mSigningDetails = signingDetails;
        mSharedUserSignaturesChanged = sharedUserSignaturesChanged;
        mRemoveAppKeySetData = removeAppKeySetData;
    }

    /**
     * Returns a combined set of packages containing the packages already installed combined
     * with the package(s) currently being installed. The to-be installed packages take
     * precedence and may shadow already installed packages.
     */
    Map<String, AndroidPackage> getCombinedAvailablePackages() {
        final ArrayMap<String, AndroidPackage> combined =
                new ArrayMap<>(mRequest.mAllPackages.size() + mRequest.mScannedPackages.size());

        combined.putAll(mRequest.mAllPackages);

        for (ScanResult scanResult : mRequest.mScannedPackages.values()) {
            combined.put(scanResult.mPkgSetting.getPackageName(),
                    scanResult.mRequest.mParsedPackage);
        }

        return combined;
    }
}
