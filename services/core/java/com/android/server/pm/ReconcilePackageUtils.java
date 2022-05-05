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

import static android.content.pm.PackageManager.INSTALL_FAILED_UPDATE_INCOMPATIBLE;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES;
import static android.content.pm.SigningDetails.CapabilityMergeRule.MERGE_RESTRICTED_CAPABILITY;

import static com.android.server.pm.PackageManagerService.SCAN_BOOTING;
import static com.android.server.pm.PackageManagerService.SCAN_DONT_KILL_APP;
import static com.android.server.pm.PackageManagerServiceUtils.compareSignatures;

import android.content.pm.PackageManager;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.os.SystemProperties;
import android.util.ArrayMap;
import android.util.Log;

import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.ParsedPackage;
import com.android.server.pm.pkg.parsing.ParsingPackageUtils;
import com.android.server.utils.WatchedLongSparseArray;

import java.util.List;
import java.util.Map;

final class ReconcilePackageUtils {
    public static Map<String, ReconciledPackage> reconcilePackages(
            final ReconcileRequest request, SharedLibrariesImpl sharedLibraries,
            KeySetManagerService ksms, Settings settings)
            throws ReconcileFailure {
        final Map<String, ScanResult> scannedPackages = request.mScannedPackages;

        final Map<String, ReconciledPackage> result = new ArrayMap<>(scannedPackages.size());

        // make a copy of the existing set of packages so we can combine them with incoming packages
        final ArrayMap<String, AndroidPackage> combinedPackages =
                new ArrayMap<>(request.mAllPackages.size() + scannedPackages.size());

        combinedPackages.putAll(request.mAllPackages);

        final Map<String, WatchedLongSparseArray<SharedLibraryInfo>> incomingSharedLibraries =
                new ArrayMap<>();

        for (String installPackageName : scannedPackages.keySet()) {
            final ScanResult scanResult = scannedPackages.get(installPackageName);

            // add / replace existing with incoming packages
            combinedPackages.put(scanResult.mPkgSetting.getPackageName(),
                    scanResult.mRequest.mParsedPackage);

            // in the first pass, we'll build up the set of incoming shared libraries
            final List<SharedLibraryInfo> allowedSharedLibInfos =
                    sharedLibraries.getAllowedSharedLibInfos(scanResult);
            if (allowedSharedLibInfos != null) {
                for (SharedLibraryInfo info : allowedSharedLibInfos) {
                    if (!SharedLibraryUtils.addSharedLibraryToPackageVersionMap(
                            incomingSharedLibraries, info)) {
                        throw new ReconcileFailure("Shared Library " + info.getName()
                                + " is being installed twice in this set!");
                    }
                }
            }

            // the following may be null if we're just reconciling on boot (and not during install)
            final InstallArgs installArgs = request.mInstallArgs.get(installPackageName);
            final PackageInstalledInfo res = request.mInstallResults.get(installPackageName);
            final PrepareResult prepareResult = request.mPreparedPackages.get(installPackageName);
            final boolean isInstall = installArgs != null;
            if (isInstall && (res == null || prepareResult == null)) {
                throw new ReconcileFailure("Reconcile arguments are not balanced for "
                        + installPackageName + "!");
            }

            final DeletePackageAction deletePackageAction;
            // we only want to try to delete for non system apps
            if (isInstall && prepareResult.mReplace && !prepareResult.mSystem) {
                final boolean killApp = (scanResult.mRequest.mScanFlags & SCAN_DONT_KILL_APP) == 0;
                final int deleteFlags = PackageManager.DELETE_KEEP_DATA
                        | (killApp ? 0 : PackageManager.DELETE_DONT_KILL_APP);
                deletePackageAction = DeletePackageHelper.mayDeletePackageLocked(res.mRemovedInfo,
                        prepareResult.mOriginalPs, prepareResult.mDisabledPs,
                        deleteFlags, null /* all users */);
                if (deletePackageAction == null) {
                    throw new ReconcileFailure(
                            PackageManager.INSTALL_FAILED_REPLACE_COULDNT_DELETE,
                            "May not delete " + installPackageName + " to replace");
                }
            } else {
                deletePackageAction = null;
            }

            final int scanFlags = scanResult.mRequest.mScanFlags;
            final int parseFlags = scanResult.mRequest.mParseFlags;
            final ParsedPackage parsedPackage = scanResult.mRequest.mParsedPackage;

            final PackageSetting disabledPkgSetting = scanResult.mRequest.mDisabledPkgSetting;
            final PackageSetting lastStaticSharedLibSetting =
                    scanResult.mStaticSharedLibraryInfo == null ? null
                            : sharedLibraries.getStaticSharedLibLatestVersionSetting(scanResult);
            final PackageSetting signatureCheckPs =
                    (prepareResult != null && lastStaticSharedLibSetting != null)
                            ? lastStaticSharedLibSetting
                            : scanResult.mPkgSetting;
            boolean removeAppKeySetData = false;
            boolean sharedUserSignaturesChanged = false;
            SigningDetails signingDetails = null;
            SharedUserSetting sharedUserSetting = settings.getSharedUserSettingLPr(
                    signatureCheckPs);
            if (ksms.shouldCheckUpgradeKeySetLocked(
                    signatureCheckPs, sharedUserSetting, scanFlags)) {
                if (ksms.checkUpgradeKeySetLocked(signatureCheckPs, parsedPackage)) {
                    // We just determined the app is signed correctly, so bring
                    // over the latest parsed certs.
                } else {
                    if ((parseFlags & ParsingPackageUtils.PARSE_IS_SYSTEM_DIR) == 0) {
                        throw new ReconcileFailure(INSTALL_FAILED_UPDATE_INCOMPATIBLE,
                                "Package " + parsedPackage.getPackageName()
                                        + " upgrade keys do not match the previously installed"
                                        + " version");
                    } else {
                        String msg = "System package " + parsedPackage.getPackageName()
                                + " signature changed; retaining data.";
                        PackageManagerService.reportSettingsProblem(Log.WARN, msg);
                    }
                }
                signingDetails = parsedPackage.getSigningDetails();
            } else {

                try {
                    final Settings.VersionInfo versionInfo =
                            request.mVersionInfos.get(installPackageName);
                    final boolean compareCompat = isCompatSignatureUpdateNeeded(versionInfo);
                    final boolean compareRecover = isRecoverSignatureUpdateNeeded(versionInfo);
                    final boolean isRollback = installArgs != null
                            && installArgs.mInstallReason == PackageManager.INSTALL_REASON_ROLLBACK;
                    final boolean compatMatch =
                            PackageManagerServiceUtils.verifySignatures(signatureCheckPs,
                                    sharedUserSetting, disabledPkgSetting,
                                    parsedPackage.getSigningDetails(), compareCompat,
                                    compareRecover, isRollback);
                    // The new KeySets will be re-added later in the scanning process.
                    if (compatMatch) {
                        removeAppKeySetData = true;
                    }
                    // We just determined the app is signed correctly, so bring
                    // over the latest parsed certs.
                    signingDetails = parsedPackage.getSigningDetails();

                    // if this is is a sharedUser, check to see if the new package is signed by a
                    // newer
                    // signing certificate than the existing one, and if so, copy over the new
                    // details
                    if (sharedUserSetting != null) {
                        // Attempt to merge the existing lineage for the shared SigningDetails with
                        // the lineage of the new package; if the shared SigningDetails are not
                        // returned this indicates the new package added new signers to the lineage
                        // and/or changed the capabilities of existing signers in the lineage.
                        SigningDetails sharedSigningDetails =
                                sharedUserSetting.signatures.mSigningDetails;
                        SigningDetails mergedDetails = sharedSigningDetails.mergeLineageWith(
                                signingDetails);
                        if (mergedDetails != sharedSigningDetails) {
                            // Use the restricted merge rule with the signing lineages from the
                            // other packages in the sharedUserId to ensure if any revoke a
                            // capability from a previous signer then this is reflected in the
                            // shared lineage.
                            for (AndroidPackage androidPackage : sharedUserSetting.getPackages()) {
                                if (androidPackage.getPackageName() != null
                                        && !androidPackage.getPackageName().equals(
                                        parsedPackage.getPackageName())) {
                                    mergedDetails = mergedDetails.mergeLineageWith(
                                            androidPackage.getSigningDetails(),
                                            MERGE_RESTRICTED_CAPABILITY);
                                }
                            }
                            sharedUserSetting.signatures.mSigningDetails =
                                    mergedDetails;
                        }
                        if (sharedUserSetting.signaturesChanged == null) {
                            sharedUserSetting.signaturesChanged = Boolean.FALSE;
                        }
                    }
                } catch (PackageManagerException e) {
                    if ((parseFlags & ParsingPackageUtils.PARSE_IS_SYSTEM_DIR) == 0) {
                        throw new ReconcileFailure(e);
                    }
                    signingDetails = parsedPackage.getSigningDetails();

                    // If the system app is part of a shared user we allow that shared user to
                    // change
                    // signatures as well as part of an OTA. We still need to verify that the
                    // signatures
                    // are consistent within the shared user for a given boot, so only allow
                    // updating
                    // the signatures on the first package scanned for the shared user (i.e. if the
                    // signaturesChanged state hasn't been initialized yet in SharedUserSetting).
                    if (sharedUserSetting != null) {
                        final Signature[] sharedUserSignatures = sharedUserSetting
                                .signatures.mSigningDetails.getSignatures();
                        if (sharedUserSetting.signaturesChanged != null
                                && compareSignatures(sharedUserSignatures,
                                parsedPackage.getSigningDetails().getSignatures())
                                != PackageManager.SIGNATURE_MATCH) {
                            if (SystemProperties.getInt("ro.product.first_api_level", 0) <= 29) {
                                // Mismatched signatures is an error and silently skipping system
                                // packages will likely break the device in unforeseen ways.
                                // However, we allow the device to boot anyway because, prior to Q,
                                // vendors were not expecting the platform to crash in this
                                // situation.
                                // This WILL be a hard failure on any new API levels after Q.
                                throw new ReconcileFailure(
                                        INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES,
                                        "Signature mismatch for shared user: "
                                                + sharedUserSetting);
                            } else {
                                // Treat mismatched signatures on system packages using a shared
                                // UID as
                                // fatal for the system overall, rather than just failing to install
                                // whichever package happened to be scanned later.
                                throw new IllegalStateException(
                                        "Signature mismatch on system package "
                                                + parsedPackage.getPackageName()
                                                + " for shared user "
                                                + sharedUserSetting);
                            }
                        }

                        sharedUserSignaturesChanged = true;
                        sharedUserSetting.signatures.mSigningDetails =
                                parsedPackage.getSigningDetails();
                        sharedUserSetting.signaturesChanged = Boolean.TRUE;
                    }
                    // File a report about this.
                    String msg = "System package " + parsedPackage.getPackageName()
                            + " signature changed; retaining data.";
                    PackageManagerService.reportSettingsProblem(Log.WARN, msg);
                } catch (IllegalArgumentException e) {
                    // should never happen: certs matched when checking, but not when comparing
                    // old to new for sharedUser
                    throw new RuntimeException(
                            "Signing certificates comparison made on incomparable signing details"
                                    + " but somehow passed verifySignatures!", e);
                }
            }

            result.put(installPackageName,
                    new ReconciledPackage(request, installArgs, scanResult.mPkgSetting,
                            res, request.mPreparedPackages.get(installPackageName), scanResult,
                            deletePackageAction, allowedSharedLibInfos, signingDetails,
                            sharedUserSignaturesChanged, removeAppKeySetData));
        }

        for (String installPackageName : scannedPackages.keySet()) {
            // Check all shared libraries and map to their actual file path.
            // We only do this here for apps not on a system dir, because those
            // are the only ones that can fail an install due to this.  We
            // will take care of the system apps by updating all of their
            // library paths after the scan is done. Also during the initial
            // scan don't update any libs as we do this wholesale after all
            // apps are scanned to avoid dependency based scanning.
            final ScanResult scanResult = scannedPackages.get(installPackageName);
            if ((scanResult.mRequest.mScanFlags & SCAN_BOOTING) != 0
                    || (scanResult.mRequest.mParseFlags & ParsingPackageUtils.PARSE_IS_SYSTEM_DIR)
                    != 0) {
                continue;
            }
            try {
                result.get(installPackageName).mCollectedSharedLibraryInfos =
                        sharedLibraries.collectSharedLibraryInfos(
                                scanResult.mRequest.mParsedPackage, combinedPackages,
                                incomingSharedLibraries);
            } catch (PackageManagerException e) {
                throw new ReconcileFailure(e.error, e.getMessage());
            }
        }

        return result;
    }

    /**
     * If the database version for this type of package (internal storage or
     * external storage) is less than the version where package signatures
     * were updated, return true.
     */
    public static boolean isCompatSignatureUpdateNeeded(Settings.VersionInfo ver) {
        return ver.databaseVersion < Settings.DatabaseVersion.SIGNATURE_END_ENTITY;
    }

    public static boolean isRecoverSignatureUpdateNeeded(Settings.VersionInfo ver) {
        return ver.databaseVersion < Settings.DatabaseVersion.SIGNATURE_MALFORMED_RECOVER;
    }
}
