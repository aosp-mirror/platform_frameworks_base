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
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_BAD_SHARED_USER_ID;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES;
import static android.content.pm.SigningDetails.CapabilityMergeRule.MERGE_RESTRICTED_CAPABILITY;

import static com.android.server.pm.PackageManagerService.SCAN_AS_APEX;
import static com.android.server.pm.PackageManagerService.SCAN_BOOTING;
import static com.android.server.pm.PackageManagerService.SCAN_DONT_KILL_APP;
import static com.android.server.pm.PackageManagerService.TAG;

import android.content.pm.Flags;
import android.content.pm.PackageManager;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.SigningDetails;
import android.os.Build;
import android.os.SystemProperties;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;

import com.android.internal.pm.parsing.pkg.ParsedPackage;
import com.android.internal.pm.pkg.parsing.ParsingPackageUtils;
import com.android.server.SystemConfig;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.utils.WatchedLongSparseArray;

import java.util.ArrayList;
import java.util.List;
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
final class ReconcilePackageUtils {
    // TODO(b/308573259): with allow-list, we should be able to disallow such installs even in
    // debuggable builds.
    private static final boolean ALLOW_NON_PRELOADS_SYSTEM_SHAREDUIDS = Build.IS_DEBUGGABLE
            || !Flags.restrictNonpreloadsSystemShareduids();

    public static List<ReconciledPackage> reconcilePackages(
            List<InstallRequest> installRequests,
            Map<String, AndroidPackage> allPackages,
            Map<String, Settings.VersionInfo> versionInfos,
            SharedLibrariesImpl sharedLibraries,
            KeySetManagerService ksms, Settings settings, SystemConfig systemConfig)
            throws ReconcileFailure {
        final List<ReconciledPackage> result = new ArrayList<>(installRequests.size());

        // make a copy of the existing set of packages so we can combine them with incoming packages
        final ArrayMap<String, AndroidPackage> combinedPackages =
                new ArrayMap<>(allPackages.size() + installRequests.size());

        combinedPackages.putAll(allPackages);

        final Map<String, WatchedLongSparseArray<SharedLibraryInfo>> incomingSharedLibraries =
                new ArrayMap<>();

        for (InstallRequest installRequest :  installRequests) {
            installRequest.onReconcileStarted();

            // add / replace existing with incoming packages
            combinedPackages.put(installRequest.getScannedPackageSetting().getPackageName(),
                    installRequest.getParsedPackage());

            // in the first pass, we'll build up the set of incoming shared libraries
            final List<SharedLibraryInfo> allowedSharedLibInfos =
                    sharedLibraries.getAllowedSharedLibInfos(installRequest);
            if (allowedSharedLibInfos != null) {
                for (SharedLibraryInfo info : allowedSharedLibInfos) {
                    if (!SharedLibraryUtils.addSharedLibraryToPackageVersionMap(
                            incomingSharedLibraries, info)) {
                        throw ReconcileFailure.ofInternalError(
                                "Shared Library " + info.getName()
                                        + " is being installed twice in this set!",
                                PackageManagerException.INTERNAL_ERROR_SHARED_LIB_INSTALLED_TWICE);
                    }
                }
            }
        }

        final AndroidPackage systemPackage = allPackages.get(KnownPackages.SYSTEM_PACKAGE_NAME);

        for (InstallRequest installRequest : installRequests) {
            final String installPackageName = installRequest.getParsedPackage().getPackageName();
            final List<SharedLibraryInfo> allowedSharedLibInfos =
                    sharedLibraries.getAllowedSharedLibInfos(installRequest);

            final DeletePackageAction deletePackageAction;
            // we only want to try to delete for non system apps
            if (installRequest.isInstallReplace() && !installRequest.isInstallSystem()) {
                final boolean killApp = (installRequest.getScanFlags() & SCAN_DONT_KILL_APP) == 0;
                final int deleteFlags = PackageManager.DELETE_KEEP_DATA
                        | (killApp ? 0 : PackageManager.DELETE_DONT_KILL_APP);
                deletePackageAction = DeletePackageHelper.mayDeletePackageLocked(
                        installRequest.getRemovedInfo(),
                        installRequest.getOriginalPackageSetting(),
                        installRequest.getDisabledPackageSetting(),
                        deleteFlags, null /* all users */);
                if (deletePackageAction == null) {
                    throw new ReconcileFailure(
                            PackageManager.INSTALL_FAILED_REPLACE_COULDNT_DELETE,
                            "May not delete " + installPackageName + " to replace");
                }
            } else {
                deletePackageAction = null;
            }

            final int scanFlags = installRequest.getScanFlags();
            final int parseFlags = installRequest.getParseFlags();
            final ParsedPackage parsedPackage = installRequest.getParsedPackage();
            final PackageSetting disabledPkgSetting = installRequest.getDisabledPackageSetting();
            final PackageSetting lastStaticSharedLibSetting =
                    installRequest.getStaticSharedLibraryInfo() == null ? null
                            : sharedLibraries.getStaticSharedLibLatestVersionSetting(
                                    installRequest);
            final PackageSetting signatureCheckPs =
                    lastStaticSharedLibSetting != null
                            ? lastStaticSharedLibSetting
                            : installRequest.getScannedPackageSetting();
            boolean removeAppKeySetData = false;
            boolean sharedUserSignaturesChanged = false;
            SigningDetails signingDetails = null;
            if (parsedPackage != null) {
                signingDetails = parsedPackage.getSigningDetails();
            }
            final boolean isSystemPackage =
                    ((parseFlags & ParsingPackageUtils.PARSE_IS_SYSTEM_DIR) != 0);
            final boolean isApex = (scanFlags & SCAN_AS_APEX) != 0;
            SharedUserSetting sharedUserSetting = settings.getSharedUserSettingLPr(
                    signatureCheckPs);
            if (ksms.shouldCheckUpgradeKeySetLocked(
                    signatureCheckPs, sharedUserSetting, scanFlags)) {
                if (ksms.checkUpgradeKeySetLocked(signatureCheckPs, parsedPackage)) {
                    // We just determined the app is signed correctly, so bring
                    // over the latest parsed certs.
                } else {
                    if (!isSystemPackage) {
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
            } else {
                try {
                    final Settings.VersionInfo versionInfo = versionInfos.get(installPackageName);
                    final boolean compareCompat = isCompatSignatureUpdateNeeded(versionInfo);
                    final boolean compareRecover = isRecoverSignatureUpdateNeeded(versionInfo);
                    final boolean isRollback = installRequest.isRollback();
                    final boolean compatMatch =
                            PackageManagerServiceUtils.verifySignatures(signatureCheckPs,
                                    sharedUserSetting, disabledPkgSetting,
                                    signingDetails, compareCompat,
                                    compareRecover, isRollback);
                    // The new KeySets will be re-added later in the scanning process.
                    if (compatMatch) {
                        removeAppKeySetData = true;
                    }

                    if (!installRequest.isInstallSystem() && !isSystemPackage && !isApex
                            && signingDetails != null
                            && systemPackage != null && systemPackage.getSigningDetails() != null
                            && systemPackage.getSigningDetails().checkCapability(
                                    signingDetails,
                                    SigningDetails.CertCapabilities.PERMISSION)) {
                        Slog.d(TAG, "Non-preload app associated with system signature: "
                                + signatureCheckPs.getPackageName());
                        if (sharedUserSetting != null && !ALLOW_NON_PRELOADS_SYSTEM_SHAREDUIDS) {
                            // Check the allow-list.
                            var allowList = systemConfig.getPackageToSharedUidAllowList();
                            var sharedUidName = allowList.get(signatureCheckPs.getPackageName());
                            if (sharedUidName == null
                                    || !sharedUserSetting.name.equals(sharedUidName)) {
                                var msg = "Non-preload app " + signatureCheckPs.getPackageName()
                                        + " signed with platform signature and joining shared uid: "
                                        + sharedUserSetting.name;
                                Slog.e(TAG, msg + ", allowList: " + allowList);
                                throw new ReconcileFailure(
                                        INSTALL_PARSE_FAILED_BAD_SHARED_USER_ID, msg);
                            }
                        }
                    }

                    // if this is is a sharedUser, check to see if the new package is signed by a
                    // newer signing certificate than the existing one, and if so, copy over the new
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
                    if (!isSystemPackage) {
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
                        if (sharedUserSetting.signaturesChanged != null
                                && !PackageManagerServiceUtils.canJoinSharedUserId(
                                parsedPackage.getPackageName(), parsedPackage.getSigningDetails(),
                                sharedUserSetting,
                                PackageManagerServiceUtils.SHARED_USER_ID_JOIN_TYPE_SYSTEM)) {
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

            final ReconciledPackage reconciledPackage =
                    new ReconciledPackage(installRequests, allPackages, installRequest,
                            deletePackageAction, allowedSharedLibInfos, signingDetails,
                            sharedUserSignaturesChanged, removeAppKeySetData);

            // Check all shared libraries and map to their actual file path.
            // We only do this here for apps not on a system dir, because those
            // are the only ones that can fail an install due to this.  We
            // will take care of the system apps by updating all of their
            // library paths after the scan is done. Also during the initial
            // scan don't update any libs as we do this wholesale after all
            // apps are scanned to avoid dependency based scanning.
            if ((installRequest.getScanFlags() & SCAN_BOOTING) == 0
                    && (installRequest.getParseFlags() & ParsingPackageUtils.PARSE_IS_SYSTEM_DIR)
                    == 0) {
                try {
                    reconciledPackage.mCollectedSharedLibraryInfos =
                            sharedLibraries.collectSharedLibraryInfos(
                                    installRequest.getParsedPackage(), combinedPackages,
                                    incomingSharedLibraries);
                } catch (PackageManagerException e) {
                    throw new ReconcileFailure(e.error, e.getMessage());
                }
            }

            installRequest.onReconcileFinished();
            result.add(reconciledPackage);
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
