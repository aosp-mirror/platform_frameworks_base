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
import android.content.pm.SharedLibraryInfo;
import android.content.pm.SigningDetails;
import android.util.ArrayMap;

import com.android.server.pm.pkg.AndroidPackage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A container of all data needed to commit a package to in-memory data structures and to disk.
 * TODO: move most of the data contained here into a PackageSetting for commit.
 */
final class ReconciledPackage {
    private final List<InstallRequest> mInstallRequests;
    private final Map<String, AndroidPackage> mAllPackages;
    @NonNull public final InstallRequest mInstallRequest;
    public final DeletePackageAction mDeletePackageAction;
    public final List<SharedLibraryInfo> mAllowedSharedLibraryInfos;
    public final SigningDetails mSigningDetails;
    public final boolean mSharedUserSignaturesChanged;
    public ArrayList<SharedLibraryInfo> mCollectedSharedLibraryInfos;
    public final boolean mRemoveAppKeySetData;

    ReconciledPackage(List<InstallRequest> installRequests,
            Map<String, AndroidPackage> allPackages,
            InstallRequest installRequest,
            DeletePackageAction deletePackageAction,
            List<SharedLibraryInfo> allowedSharedLibraryInfos,
            SigningDetails signingDetails,
            boolean sharedUserSignaturesChanged,
            boolean removeAppKeySetData) {
        mInstallRequests = installRequests;
        mAllPackages = allPackages;
        mInstallRequest = installRequest;
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
    @NonNull Map<String, AndroidPackage> getCombinedAvailablePackages() {
        final ArrayMap<String, AndroidPackage> combined =
                new ArrayMap<>(mAllPackages.size() + mInstallRequests.size());

        combined.putAll(mAllPackages);

        for (InstallRequest installRequest : mInstallRequests) {
            combined.put(installRequest.getScannedPackageSetting().getPackageName(),
                    installRequest.getParsedPackage());
        }

        return combined;
    }
}
