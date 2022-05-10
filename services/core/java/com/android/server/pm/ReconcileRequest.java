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

import com.android.server.pm.parsing.pkg.AndroidPackage;

import java.util.Collections;
import java.util.Map;

/**
 * Package scan results and related request details used to reconcile the potential addition of
 * one or more packages to the system.
 *
 * Reconcile will take a set of package details that need to be committed to the system and make
 * sure that they are valid in the context of the system and the other installing apps. Any
 * invalid state or app will result in a failed reconciliation and thus whatever operation (such
 * as install) led to the request.
 */
final class ReconcileRequest {
    public final Map<String, ScanResult> mScannedPackages;

    public final Map<String, AndroidPackage> mAllPackages;
    public final Map<String, InstallArgs> mInstallArgs;
    public final Map<String, PackageInstalledInfo> mInstallResults;
    public final Map<String, PrepareResult> mPreparedPackages;
    public final Map<String, Settings.VersionInfo> mVersionInfos;

    ReconcileRequest(Map<String, ScanResult> scannedPackages,
            Map<String, InstallArgs> installArgs,
            Map<String, PackageInstalledInfo> installResults,
            Map<String, PrepareResult> preparedPackages,
            Map<String, AndroidPackage> allPackages,
            Map<String, Settings.VersionInfo> versionInfos) {
        mScannedPackages = scannedPackages;
        mInstallArgs = installArgs;
        mInstallResults = installResults;
        mPreparedPackages = preparedPackages;
        mAllPackages = allPackages;
        mVersionInfos = versionInfos;
    }

    ReconcileRequest(Map<String, ScanResult> scannedPackages,
            Map<String, AndroidPackage> allPackages,
            Map<String, Settings.VersionInfo> versionInfos) {
        this(scannedPackages, Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap(), allPackages, versionInfos);
    }
}
