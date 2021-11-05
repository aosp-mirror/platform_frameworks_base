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
import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;

import static com.android.server.pm.InstructionSets.getDexCodeInstructionSet;
import static com.android.server.pm.InstructionSets.getPreferredInstructionSet;
import static com.android.server.pm.PackageManagerService.DEBUG_INSTALL;
import static com.android.server.pm.PackageManagerService.DEBUG_PACKAGE_SCANNING;
import static com.android.server.pm.PackageManagerService.POST_INSTALL;
import static com.android.server.pm.PackageManagerService.SCAN_AS_APK_IN_APEX;
import static com.android.server.pm.PackageManagerService.SCAN_AS_INSTANT_APP;
import static com.android.server.pm.PackageManagerService.SCAN_BOOTING;
import static com.android.server.pm.PackageManagerService.SCAN_DONT_KILL_APP;
import static com.android.server.pm.PackageManagerService.SCAN_IGNORE_FROZEN;
import static com.android.server.pm.PackageManagerService.TAG;
import static com.android.server.pm.PackageManagerServiceUtils.compareSignatures;
import static com.android.server.pm.PackageManagerServiceUtils.verifySignatures;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.backup.IBackupManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.DataLoaderType;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.content.pm.parsing.ParsingPackageUtils;
import android.content.pm.parsing.component.ComponentMutateUtils;
import android.content.pm.parsing.component.ParsedInstrumentation;
import android.os.Binder;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.incremental.IncrementalManager;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;
import com.android.server.pm.parsing.pkg.ParsedPackage;
import com.android.server.pm.permission.PermissionManagerServiceInternal;
import com.android.server.pm.pkg.PackageUserState;
import com.android.server.rollback.RollbackManagerInternal;
import com.android.server.utils.WatchedLongSparseArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class InstallPackageHelper {
    private final PackageManagerService mPm;
    private final AppDataHelper mAppDataHelper;
    private final PackageManagerServiceInjector mInjector;

    // TODO(b/198166813): remove PMS dependency
    InstallPackageHelper(PackageManagerService pm, AppDataHelper appDataHelper) {
        mPm = pm;
        mInjector = pm.mInjector;
        mAppDataHelper = appDataHelper;
    }

    InstallPackageHelper(PackageManagerService pm) {
        this(pm, new AppDataHelper(pm));
    }

    InstallPackageHelper(PackageManagerService pm, PackageManagerServiceInjector injector) {
        mPm = pm;
        mInjector = injector;
        mAppDataHelper = new AppDataHelper(pm, mInjector);
    }

    @GuardedBy("mPm.mLock")
    public Map<String, ReconciledPackage> reconcilePackagesLocked(
            final ReconcileRequest request, KeySetManagerService ksms,
            PackageManagerServiceInjector injector)
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
                    SharedLibraryHelper.getAllowedSharedLibInfos(scanResult,
                            request.mSharedLibrarySource);
            final SharedLibraryInfo staticLib = scanResult.mStaticSharedLibraryInfo;
            if (allowedSharedLibInfos != null) {
                for (SharedLibraryInfo info : allowedSharedLibInfos) {
                    if (!SharedLibraryHelper.addSharedLibraryToPackageVersionMap(
                            incomingSharedLibraries, info)) {
                        throw new ReconcileFailure("Static Shared Library " + staticLib.getName()
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
                    request.mLastStaticSharedLibSettings.get(installPackageName);
            final PackageSetting signatureCheckPs =
                    (prepareResult != null && lastStaticSharedLibSetting != null)
                            ? lastStaticSharedLibSetting
                            : scanResult.mPkgSetting;
            boolean removeAppKeySetData = false;
            boolean sharedUserSignaturesChanged = false;
            SigningDetails signingDetails = null;
            if (ksms.shouldCheckUpgradeKeySetLocked(signatureCheckPs, scanFlags)) {
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
                    final boolean compatMatch = verifySignatures(signatureCheckPs,
                            disabledPkgSetting, parsedPackage.getSigningDetails(), compareCompat,
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
                    if (signatureCheckPs.getSharedUser() != null) {
                        // Attempt to merge the existing lineage for the shared SigningDetails with
                        // the lineage of the new package; if the shared SigningDetails are not
                        // returned this indicates the new package added new signers to the lineage
                        // and/or changed the capabilities of existing signers in the lineage.
                        SigningDetails sharedSigningDetails =
                                signatureCheckPs.getSharedUser().signatures.mSigningDetails;
                        SigningDetails mergedDetails = sharedSigningDetails.mergeLineageWith(
                                signingDetails);
                        if (mergedDetails != sharedSigningDetails) {
                            signatureCheckPs.getSharedUser().signatures.mSigningDetails =
                                    mergedDetails;
                        }
                        if (signatureCheckPs.getSharedUser().signaturesChanged == null) {
                            signatureCheckPs.getSharedUser().signaturesChanged = Boolean.FALSE;
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
                    if (signatureCheckPs.getSharedUser() != null) {
                        final Signature[] sharedUserSignatures = signatureCheckPs.getSharedUser()
                                .signatures.mSigningDetails.getSignatures();
                        if (signatureCheckPs.getSharedUser().signaturesChanged != null
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
                                                + scanResult.mPkgSetting.getSharedUser());
                            } else {
                                // Treat mismatched signatures on system packages using a shared
                                // UID as
                                // fatal for the system overall, rather than just failing to install
                                // whichever package happened to be scanned later.
                                throw new IllegalStateException(
                                        "Signature mismatch on system package "
                                                + parsedPackage.getPackageName()
                                                + " for shared user "
                                                + scanResult.mPkgSetting.getSharedUser());
                            }
                        }

                        sharedUserSignaturesChanged = true;
                        signatureCheckPs.getSharedUser().signatures.mSigningDetails =
                                parsedPackage.getSigningDetails();
                        signatureCheckPs.getSharedUser().signaturesChanged = Boolean.TRUE;
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
                        SharedLibraryHelper.collectSharedLibraryInfos(
                                scanResult.mRequest.mParsedPackage,
                                combinedPackages, request.mSharedLibrarySource,
                                incomingSharedLibraries, injector.getCompatibility());

            } catch (PackageManagerException e) {
                throw new ReconcileFailure(e.error, e.getMessage());
            }
        }

        return result;
    }

    /**
     * Commits the package scan and modifies system state.
     * <p><em>WARNING:</em> The method may throw an exception in the middle
     * of committing the package, leaving the system in an inconsistent state.
     * This needs to be fixed so, once we get to this point, no errors are
     * possible and the system is not left in an inconsistent state.
     */
    @GuardedBy({"mPm.mLock", "mPm.mInstallLock"})
    public AndroidPackage commitReconciledScanResultLocked(
            @NonNull ReconciledPackage reconciledPkg, int[] allUsers) {
        final ScanResult result = reconciledPkg.mScanResult;
        final ScanRequest request = result.mRequest;
        // TODO(b/135203078): Move this even further away
        ParsedPackage parsedPackage = request.mParsedPackage;
        if ("android".equals(parsedPackage.getPackageName())) {
            // TODO(b/135203078): Move this to initial parse
            parsedPackage.setVersionCode(mPm.getSdkVersion())
                    .setVersionCodeMajor(0);
        }

        final AndroidPackage oldPkg = request.mOldPkg;
        final @ParsingPackageUtils.ParseFlags int parseFlags = request.mParseFlags;
        final @PackageManagerService.ScanFlags int scanFlags = request.mScanFlags;
        final PackageSetting oldPkgSetting = request.mOldPkgSetting;
        final PackageSetting originalPkgSetting = request.mOriginalPkgSetting;
        final UserHandle user = request.mUser;
        final String realPkgName = request.mRealPkgName;
        final List<String> changedAbiCodePath = result.mChangedAbiCodePath;
        final PackageSetting pkgSetting;
        if (request.mPkgSetting != null && request.mPkgSetting.getSharedUser() != null
                && request.mPkgSetting.getSharedUser() != result.mPkgSetting.getSharedUser()) {
            // shared user changed, remove from old shared user
            request.mPkgSetting.getSharedUser().removePackage(request.mPkgSetting);
        }
        if (result.mExistingSettingCopied) {
            pkgSetting = request.mPkgSetting;
            pkgSetting.updateFrom(result.mPkgSetting);
        } else {
            pkgSetting = result.mPkgSetting;
            if (originalPkgSetting != null) {
                mPm.mSettings.addRenamedPackageLPw(
                        AndroidPackageUtils.getRealPackageOrNull(parsedPackage),
                        originalPkgSetting.getPackageName());
                mPm.mTransferredPackages.add(originalPkgSetting.getPackageName());
            } else {
                mPm.mSettings.removeRenamedPackageLPw(parsedPackage.getPackageName());
            }
        }
        if (pkgSetting.getSharedUser() != null) {
            pkgSetting.getSharedUser().addPackage(pkgSetting);
        }
        if (reconciledPkg.mInstallArgs != null
                && reconciledPkg.mInstallArgs.mForceQueryableOverride) {
            pkgSetting.setForceQueryableOverride(true);
        }

        // If this is part of a standard install, set the initiating package name, else rely on
        // previous device state.
        if (reconciledPkg.mInstallArgs != null) {
            InstallSource installSource = reconciledPkg.mInstallArgs.mInstallSource;
            if (installSource.initiatingPackageName != null) {
                final PackageSetting ips = mPm.mSettings.getPackageLPr(
                        installSource.initiatingPackageName);
                if (ips != null) {
                    installSource = installSource.setInitiatingPackageSignatures(
                            ips.getSignatures());
                }
            }
            pkgSetting.setInstallSource(installSource);
        }

        // TODO(toddke): Consider a method specifically for modifying the Package object
        // post scan; or, moving this stuff out of the Package object since it has nothing
        // to do with the package on disk.
        // We need to have this here because addUserToSettingLPw() is sometimes responsible
        // for creating the application ID. If we did this earlier, we would be saving the
        // correct ID.
        parsedPackage.setUid(pkgSetting.getAppId());
        final AndroidPackage pkg = parsedPackage.hideAsFinal();

        mPm.mSettings.writeUserRestrictionsLPw(pkgSetting, oldPkgSetting);

        if (realPkgName != null) {
            mPm.mTransferredPackages.add(pkg.getPackageName());
        }

        if (reconciledPkg.mCollectedSharedLibraryInfos != null) {
            mPm.executeSharedLibrariesUpdateLPr(pkg, pkgSetting, null, null,
                    reconciledPkg.mCollectedSharedLibraryInfos, allUsers);
        }

        final KeySetManagerService ksms = mPm.mSettings.getKeySetManagerService();
        if (reconciledPkg.mRemoveAppKeySetData) {
            ksms.removeAppKeySetDataLPw(pkg.getPackageName());
        }
        if (reconciledPkg.mSharedUserSignaturesChanged) {
            pkgSetting.getSharedUser().signaturesChanged = Boolean.TRUE;
            pkgSetting.getSharedUser().signatures.mSigningDetails = reconciledPkg.mSigningDetails;
        }
        pkgSetting.setSigningDetails(reconciledPkg.mSigningDetails);

        if (changedAbiCodePath != null && changedAbiCodePath.size() > 0) {
            for (int i = changedAbiCodePath.size() - 1; i >= 0; --i) {
                final String codePathString = changedAbiCodePath.get(i);
                try {
                    mPm.mInstaller.rmdex(codePathString,
                            getDexCodeInstructionSet(getPreferredInstructionSet()));
                } catch (Installer.InstallerException ignored) {
                }
            }
        }

        final int userId = user == null ? 0 : user.getIdentifier();
        // Modify state for the given package setting
        commitPackageSettings(pkg, oldPkg, pkgSetting, oldPkgSetting, scanFlags,
                (parseFlags & ParsingPackageUtils.PARSE_CHATTY) != 0 /*chatty*/, reconciledPkg);
        if (pkgSetting.getInstantApp(userId)) {
            mPm.mInstantAppRegistry.addInstantAppLPw(userId, pkgSetting.getAppId());
        }

        if (!IncrementalManager.isIncrementalPath(pkgSetting.getPathString())) {
            pkgSetting.setLoadingProgress(1f);
        }

        return pkg;
    }

    /**
     * Adds a scanned package to the system. When this method is finished, the package will
     * be available for query, resolution, etc...
     */
    private void commitPackageSettings(@NonNull AndroidPackage pkg, @Nullable AndroidPackage oldPkg,
            @NonNull PackageSetting pkgSetting, @Nullable PackageSetting oldPkgSetting,
            final @PackageManagerService.ScanFlags int scanFlags, boolean chatty,
            ReconciledPackage reconciledPkg) {
        final String pkgName = pkg.getPackageName();
        if (mPm.mCustomResolverComponentName != null
                && mPm.mCustomResolverComponentName.getPackageName().equals(pkg.getPackageName())) {
            mPm.setUpCustomResolverActivity(pkg, pkgSetting);
        }

        if (pkg.getPackageName().equals("android")) {
            mPm.setPlatformPackage(pkg, pkgSetting);
        }

        ArrayList<AndroidPackage> clientLibPkgs = null;
        // writer
        synchronized (mPm.mLock) {
            if (!ArrayUtils.isEmpty(reconciledPkg.mAllowedSharedLibraryInfos)) {
                for (SharedLibraryInfo info : reconciledPkg.mAllowedSharedLibraryInfos) {
                    mPm.commitSharedLibraryInfoLocked(info);
                }
                final Map<String, AndroidPackage> combinedSigningDetails =
                        reconciledPkg.getCombinedAvailablePackages();
                try {
                    // Shared libraries for the package need to be updated.
                    mPm.updateSharedLibrariesLocked(pkg, pkgSetting, null, null,
                            combinedSigningDetails);
                } catch (PackageManagerException e) {
                    Slog.e(TAG, "updateSharedLibrariesLPr failed: ", e);
                }
                // Update all applications that use this library. Skip when booting
                // since this will be done after all packages are scaned.
                if ((scanFlags & SCAN_BOOTING) == 0) {
                    clientLibPkgs = mPm.updateAllSharedLibrariesLocked(pkg, pkgSetting,
                            combinedSigningDetails);
                }
            }
        }
        if (reconciledPkg.mInstallResult != null) {
            reconciledPkg.mInstallResult.mLibraryConsumers = clientLibPkgs;
        }

        if ((scanFlags & SCAN_BOOTING) != 0) {
            // No apps can run during boot scan, so they don't need to be frozen
        } else if ((scanFlags & SCAN_DONT_KILL_APP) != 0) {
            // Caller asked to not kill app, so it's probably not frozen
        } else if ((scanFlags & SCAN_IGNORE_FROZEN) != 0) {
            // Caller asked us to ignore frozen check for some reason; they
            // probably didn't know the package name
        } else {
            // We're doing major surgery on this package, so it better be frozen
            // right now to keep it from launching
            mPm.checkPackageFrozen(pkgName);
        }

        // Also need to kill any apps that are dependent on the library.
        if (clientLibPkgs != null) {
            for (int i = 0; i < clientLibPkgs.size(); i++) {
                AndroidPackage clientPkg = clientLibPkgs.get(i);
                mPm.killApplication(clientPkg.getPackageName(),
                        clientPkg.getUid(), "update lib");
            }
        }

        // writer
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "updateSettings");

        synchronized (mPm.mLock) {
            // We don't expect installation to fail beyond this point
            // Add the new setting to mSettings
            mPm.mSettings.insertPackageSettingLPw(pkgSetting, pkg);
            // Add the new setting to mPackages
            mPm.mPackages.put(pkg.getPackageName(), pkg);
            if ((scanFlags & SCAN_AS_APK_IN_APEX) != 0) {
                mPm.mApexManager.registerApkInApex(pkg);
            }

            // Add the package's KeySets to the global KeySetManagerService
            KeySetManagerService ksms = mPm.mSettings.getKeySetManagerService();
            ksms.addScannedPackageLPw(pkg);

            mPm.mComponentResolver.addAllComponents(pkg, chatty);
            final boolean isReplace =
                    reconciledPkg.mPrepareResult != null && reconciledPkg.mPrepareResult.mReplace;
            mPm.mAppsFilter.addPackage(pkgSetting, isReplace);
            mPm.addAllPackageProperties(pkg);

            if (oldPkgSetting == null || oldPkgSetting.getPkg() == null) {
                mPm.mDomainVerificationManager.addPackage(pkgSetting);
            } else {
                mPm.mDomainVerificationManager.migrateState(oldPkgSetting, pkgSetting);
            }

            int collectionSize = ArrayUtils.size(pkg.getInstrumentations());
            StringBuilder r = null;
            int i;
            for (i = 0; i < collectionSize; i++) {
                ParsedInstrumentation a = pkg.getInstrumentations().get(i);
                ComponentMutateUtils.setPackageName(a, pkg.getPackageName());
                mPm.addInstrumentation(a.getComponentName(), a);
                if (chatty) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    r.append(a.getName());
                }
            }
            if (r != null) {
                if (DEBUG_PACKAGE_SCANNING) Log.d(TAG, "  Instrumentation: " + r);
            }

            final List<String> protectedBroadcasts = pkg.getProtectedBroadcasts();
            if (!protectedBroadcasts.isEmpty()) {
                synchronized (mPm.mProtectedBroadcasts) {
                    mPm.mProtectedBroadcasts.addAll(protectedBroadcasts);
                }
            }

            mPm.mPermissionManager.onPackageAdded(pkg,
                    (scanFlags & SCAN_AS_INSTANT_APP) != 0, oldPkg);
        }

        Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
    }

    /**
     * If the database version for this type of package (internal storage or
     * external storage) is less than the version where package signatures
     * were updated, return true.
     */
    public boolean isCompatSignatureUpdateNeeded(AndroidPackage pkg) {
        return isCompatSignatureUpdateNeeded(mPm.getSettingsVersionForPackage(pkg));
    }

    public static boolean isCompatSignatureUpdateNeeded(Settings.VersionInfo ver) {
        return ver.databaseVersion < Settings.DatabaseVersion.SIGNATURE_END_ENTITY;
    }

    public boolean isRecoverSignatureUpdateNeeded(AndroidPackage pkg) {
        return isRecoverSignatureUpdateNeeded(mPm.getSettingsVersionForPackage(pkg));
    }

    public static boolean isRecoverSignatureUpdateNeeded(Settings.VersionInfo ver) {
        return ver.databaseVersion < Settings.DatabaseVersion.SIGNATURE_MALFORMED_RECOVER;
    }

    public int installExistingPackageAsUser(@Nullable String packageName, @UserIdInt int userId,
            @PackageManager.InstallFlags int installFlags,
            @PackageManager.InstallReason int installReason,
            @Nullable List<String> allowlistedRestrictedPermissions,
            @Nullable IntentSender intentSender) {
        if (DEBUG_INSTALL) {
            Log.v(TAG, "installExistingPackageAsUser package=" + packageName + " userId=" + userId
                    + " installFlags=" + installFlags + " installReason=" + installReason
                    + " allowlistedRestrictedPermissions=" + allowlistedRestrictedPermissions);
        }

        final int callingUid = Binder.getCallingUid();
        if (mPm.mContext.checkCallingOrSelfPermission(android.Manifest.permission.INSTALL_PACKAGES)
                != PackageManager.PERMISSION_GRANTED
                && mPm.mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.INSTALL_EXISTING_PACKAGES)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Neither user " + callingUid + " nor current process has "
                    + android.Manifest.permission.INSTALL_PACKAGES + ".");
        }
        PackageSetting pkgSetting;
        mPm.enforceCrossUserPermission(callingUid, userId, true /* requireFullPermission */,
                true /* checkShell */, "installExistingPackage for user " + userId);
        if (mPm.isUserRestricted(userId, UserManager.DISALLOW_INSTALL_APPS)) {
            return PackageManager.INSTALL_FAILED_USER_RESTRICTED;
        }

        final long callingId = Binder.clearCallingIdentity();
        try {
            boolean installed = false;
            final boolean instantApp =
                    (installFlags & PackageManager.INSTALL_INSTANT_APP) != 0;
            final boolean fullApp =
                    (installFlags & PackageManager.INSTALL_FULL_APP) != 0;

            // writer
            synchronized (mPm.mLock) {
                pkgSetting = mPm.mSettings.getPackageLPr(packageName);
                if (pkgSetting == null) {
                    return PackageManager.INSTALL_FAILED_INVALID_URI;
                }
                if (!mPm.canViewInstantApps(callingUid, UserHandle.getUserId(callingUid))) {
                    // only allow the existing package to be used if it's installed as a full
                    // application for at least one user
                    boolean installAllowed = false;
                    for (int checkUserId : mPm.mUserManager.getUserIds()) {
                        installAllowed = !pkgSetting.getInstantApp(checkUserId);
                        if (installAllowed) {
                            break;
                        }
                    }
                    if (!installAllowed) {
                        return PackageManager.INSTALL_FAILED_INVALID_URI;
                    }
                }
                if (!pkgSetting.getInstalled(userId)) {
                    pkgSetting.setInstalled(true, userId);
                    pkgSetting.setHidden(false, userId);
                    pkgSetting.setInstallReason(installReason, userId);
                    pkgSetting.setUninstallReason(PackageManager.UNINSTALL_REASON_UNKNOWN, userId);
                    mPm.mSettings.writePackageRestrictionsLPr(userId);
                    mPm.mSettings.writeKernelMappingLPr(pkgSetting);
                    installed = true;
                } else if (fullApp && pkgSetting.getInstantApp(userId)) {
                    // upgrade app from instant to full; we don't allow app downgrade
                    installed = true;
                }
                mPm.setInstantAppForUser(mInjector, pkgSetting, userId, instantApp, fullApp);
            }

            if (installed) {
                if (pkgSetting.getPkg() != null) {
                    final PermissionManagerServiceInternal.PackageInstalledParams.Builder
                            permissionParamsBuilder =
                            new PermissionManagerServiceInternal.PackageInstalledParams.Builder();
                    if ((installFlags & PackageManager.INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS)
                            != 0) {
                        permissionParamsBuilder.setAllowlistedRestrictedPermissions(
                                pkgSetting.getPkg().getRequestedPermissions());
                    }
                    mPm.mPermissionManager.onPackageInstalled(pkgSetting.getPkg(),
                            Process.INVALID_UID /* previousAppId */,
                            permissionParamsBuilder.build(), userId);
                }

                if (pkgSetting.getPkg() != null) {
                    synchronized (mPm.mInstallLock) {
                        // We don't need to freeze for a brand new install
                        mAppDataHelper.prepareAppDataAfterInstallLIF(pkgSetting.getPkg());
                    }
                }
                mPm.sendPackageAddedForUser(packageName, pkgSetting, userId, DataLoaderType.NONE);
                synchronized (mPm.mLock) {
                    mPm.updateSequenceNumberLP(pkgSetting, new int[]{ userId });
                }
                // start async restore with no post-install since we finish install here
                PackageInstalledInfo res = new PackageInstalledInfo(
                        PackageManager.INSTALL_SUCCEEDED);
                res.mPkg = pkgSetting.getPkg();
                res.mNewUsers = new int[]{ userId };

                PostInstallData postInstallData =
                        new PostInstallData(null, res, () -> {
                            mPm.restorePermissionsAndUpdateRolesForNewUserInstall(packageName,
                                    userId);
                            if (intentSender != null) {
                                onRestoreComplete(res.mReturnCode, mPm.mContext, intentSender);
                            }
                        });
                restoreAndPostInstall(userId, res, postInstallData);
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }

        return PackageManager.INSTALL_SUCCEEDED;
    }

    private static void onRestoreComplete(int returnCode, Context context, IntentSender target) {
        Intent fillIn = new Intent();
        fillIn.putExtra(PackageInstaller.EXTRA_STATUS,
                PackageManager.installStatusToPublicStatus(returnCode));
        try {
            target.sendIntent(context, 0, fillIn, null, null);
        } catch (IntentSender.SendIntentException ignored) {
        }
    }

    /** @param data Post-install is performed only if this is non-null. */
    public void restoreAndPostInstall(
            int userId, PackageInstalledInfo res, @Nullable PostInstallData data) {
        if (DEBUG_INSTALL) {
            Log.v(TAG, "restoreAndPostInstall userId=" + userId + " package=" + res.mPkg);
        }

        // A restore should be requested at this point if (a) the install
        // succeeded, (b) the operation is not an update.
        final boolean update = res.mRemovedInfo != null
                && res.mRemovedInfo.mRemovedPackage != null;
        boolean doRestore = !update && res.mPkg != null;

        // Set up the post-install work request bookkeeping.  This will be used
        // and cleaned up by the post-install event handling regardless of whether
        // there's a restore pass performed.  Token values are >= 1.
        int token;
        if (mPm.mNextInstallToken < 0) mPm.mNextInstallToken = 1;
        token = mPm.mNextInstallToken++;
        if (data != null) {
            mPm.mRunningInstalls.put(token, data);
        } else if (DEBUG_INSTALL) {
            Log.v(TAG, "No post-install required for " + token);
        }

        if (DEBUG_INSTALL) Log.v(TAG, "+ starting restore round-trip " + token);

        if (res.mReturnCode == PackageManager.INSTALL_SUCCEEDED && doRestore) {
            // Pass responsibility to the Backup Manager.  It will perform a
            // restore if appropriate, then pass responsibility back to the
            // Package Manager to run the post-install observer callbacks
            // and broadcasts.
            if (res.mFreezer != null) {
                res.mFreezer.close();
            }
            doRestore = performBackupManagerRestore(userId, token, res);
        }

        // If this is an update to a package that might be potentially downgraded, then we
        // need to check with the rollback manager whether there's any userdata that might
        // need to be snapshotted or restored for the package.
        //
        // TODO(narayan): Get this working for cases where userId == UserHandle.USER_ALL.
        if (res.mReturnCode == PackageManager.INSTALL_SUCCEEDED && !doRestore && update) {
            doRestore = performRollbackManagerRestore(userId, token, res, data);
        }

        if (!doRestore) {
            // No restore possible, or the Backup Manager was mysteriously not
            // available -- just fire the post-install work request directly.
            if (DEBUG_INSTALL) Log.v(TAG, "No restore - queue post-install for " + token);

            Trace.asyncTraceBegin(TRACE_TAG_PACKAGE_MANAGER, "postInstall", token);

            Message msg = mPm.mHandler.obtainMessage(POST_INSTALL, token, 0);
            mPm.mHandler.sendMessage(msg);
        }
    }

    /**
     * Perform Backup Manager restore for a given {@link PackageInstalledInfo}.
     * Returns whether the restore successfully completed.
     */
    private boolean performBackupManagerRestore(int userId, int token, PackageInstalledInfo res) {
        IBackupManager bm = IBackupManager.Stub.asInterface(
                ServiceManager.getService(Context.BACKUP_SERVICE));
        if (bm != null) {
            // For backwards compatibility as USER_ALL previously routed directly to USER_SYSTEM
            // in the BackupManager. USER_ALL is used in compatibility tests.
            if (userId == UserHandle.USER_ALL) {
                userId = UserHandle.USER_SYSTEM;
            }
            if (DEBUG_INSTALL) {
                Log.v(TAG, "token " + token + " to BM for possible restore for user " + userId);
            }
            Trace.asyncTraceBegin(TRACE_TAG_PACKAGE_MANAGER, "restore", token);
            try {
                if (bm.isUserReadyForBackup(userId)) {
                    bm.restoreAtInstallForUser(
                            userId, res.mPkg.getPackageName(), token);
                } else {
                    Slog.w(TAG, "User " + userId + " is not ready. Restore at install "
                            + "didn't take place.");
                    return false;
                }
            } catch (RemoteException e) {
                // can't happen; the backup manager is local
            } catch (Exception e) {
                Slog.e(TAG, "Exception trying to enqueue restore", e);
                return false;
            }
        } else {
            Slog.e(TAG, "Backup Manager not found!");
            return false;
        }
        return true;
    }

    /**
     * Perform Rollback Manager restore for a given {@link PackageInstalledInfo}.
     * Returns whether the restore successfully completed.
     */
    private boolean performRollbackManagerRestore(int userId, int token, PackageInstalledInfo res,
            PostInstallData data) {
        RollbackManagerInternal rm = mInjector.getLocalService(RollbackManagerInternal.class);

        final String packageName = res.mPkg.getPackageName();
        final int[] allUsers = mPm.mUserManager.getUserIds();
        final int[] installedUsers;

        final PackageSetting ps;
        int appId = -1;
        long ceDataInode = -1;
        synchronized (mPm.mLock) {
            ps = mPm.mSettings.getPackageLPr(packageName);
            if (ps != null) {
                appId = ps.getAppId();
                ceDataInode = ps.getCeDataInode(userId);
            }

            // NOTE: We ignore the user specified in the InstallParam because we know this is
            // an update, and hence need to restore data for all installed users.
            installedUsers = ps.queryInstalledUsers(allUsers, true);
        }

        boolean doSnapshotOrRestore = data != null && data.args != null
                && ((data.args.mInstallFlags & PackageManager.INSTALL_ENABLE_ROLLBACK) != 0
                || (data.args.mInstallFlags & PackageManager.INSTALL_REQUEST_DOWNGRADE) != 0);

        if (ps != null && doSnapshotOrRestore) {
            final String seInfo = AndroidPackageUtils.getSeInfo(res.mPkg, ps);
            rm.snapshotAndRestoreUserData(packageName, UserHandle.toUserHandles(installedUsers),
                    appId, ceDataInode, seInfo, token);
            return true;
        }
        return false;
    }
}
