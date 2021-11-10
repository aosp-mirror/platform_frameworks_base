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

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.INSTALL_FAILED_ALREADY_EXISTS;
import static android.content.pm.PackageManager.INSTALL_FAILED_BAD_PERMISSION_GROUP;
import static android.content.pm.PackageManager.INSTALL_FAILED_DUPLICATE_PACKAGE;
import static android.content.pm.PackageManager.INSTALL_FAILED_DUPLICATE_PERMISSION;
import static android.content.pm.PackageManager.INSTALL_FAILED_DUPLICATE_PERMISSION_GROUP;
import static android.content.pm.PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
import static android.content.pm.PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
import static android.content.pm.PackageManager.INSTALL_FAILED_INVALID_APK;
import static android.content.pm.PackageManager.INSTALL_FAILED_INVALID_INSTALL_LOCATION;
import static android.content.pm.PackageManager.INSTALL_FAILED_PACKAGE_CHANGED;
import static android.content.pm.PackageManager.INSTALL_FAILED_SESSION_INVALID;
import static android.content.pm.PackageManager.INSTALL_FAILED_TEST_ONLY;
import static android.content.pm.PackageManager.INSTALL_FAILED_UID_CHANGED;
import static android.content.pm.PackageManager.INSTALL_FAILED_UPDATE_INCOMPATIBLE;
import static android.content.pm.PackageManager.INSTALL_FAILED_VERSION_DOWNGRADE;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES;
import static android.content.pm.PackageManager.INSTALL_REASON_DEVICE_RESTORE;
import static android.content.pm.PackageManager.INSTALL_REASON_DEVICE_SETUP;
import static android.content.pm.PackageManager.UNINSTALL_REASON_UNKNOWN;
import static android.content.pm.SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V4;
import static android.os.PowerExemptionManager.REASON_PACKAGE_REPLACED;
import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;
import static android.os.incremental.IncrementalManager.isIncrementalPath;
import static android.os.storage.StorageManager.FLAG_STORAGE_CE;
import static android.os.storage.StorageManager.FLAG_STORAGE_DE;
import static android.os.storage.StorageManager.FLAG_STORAGE_EXTERNAL;

import static com.android.internal.content.NativeLibraryHelper.LIB_DIR_NAME;
import static com.android.server.pm.InstructionSets.getAppDexInstructionSets;
import static com.android.server.pm.InstructionSets.getDexCodeInstructionSet;
import static com.android.server.pm.InstructionSets.getPreferredInstructionSet;
import static com.android.server.pm.PackageManagerService.DEBUG_BACKUP;
import static com.android.server.pm.PackageManagerService.DEBUG_COMPRESSION;
import static com.android.server.pm.PackageManagerService.DEBUG_INSTALL;
import static com.android.server.pm.PackageManagerService.DEBUG_PACKAGE_SCANNING;
import static com.android.server.pm.PackageManagerService.DEBUG_REMOVE;
import static com.android.server.pm.PackageManagerService.DEBUG_VERIFY;
import static com.android.server.pm.PackageManagerService.EMPTY_INT_ARRAY;
import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;
import static com.android.server.pm.PackageManagerService.POST_INSTALL;
import static com.android.server.pm.PackageManagerService.PRECOMPILE_LAYOUTS;
import static com.android.server.pm.PackageManagerService.SCAN_AS_APK_IN_APEX;
import static com.android.server.pm.PackageManagerService.SCAN_AS_FULL_APP;
import static com.android.server.pm.PackageManagerService.SCAN_AS_INSTANT_APP;
import static com.android.server.pm.PackageManagerService.SCAN_AS_ODM;
import static com.android.server.pm.PackageManagerService.SCAN_AS_OEM;
import static com.android.server.pm.PackageManagerService.SCAN_AS_PRIVILEGED;
import static com.android.server.pm.PackageManagerService.SCAN_AS_PRODUCT;
import static com.android.server.pm.PackageManagerService.SCAN_AS_SYSTEM;
import static com.android.server.pm.PackageManagerService.SCAN_AS_SYSTEM_EXT;
import static com.android.server.pm.PackageManagerService.SCAN_AS_VENDOR;
import static com.android.server.pm.PackageManagerService.SCAN_AS_VIRTUAL_PRELOAD;
import static com.android.server.pm.PackageManagerService.SCAN_BOOTING;
import static com.android.server.pm.PackageManagerService.SCAN_DONT_KILL_APP;
import static com.android.server.pm.PackageManagerService.SCAN_IGNORE_FROZEN;
import static com.android.server.pm.PackageManagerService.SCAN_INITIAL;
import static com.android.server.pm.PackageManagerService.SCAN_MOVE;
import static com.android.server.pm.PackageManagerService.SCAN_NEW_INSTALL;
import static com.android.server.pm.PackageManagerService.SCAN_NO_DEX;
import static com.android.server.pm.PackageManagerService.SCAN_UPDATE_SIGNATURE;
import static com.android.server.pm.PackageManagerService.TAG;
import static com.android.server.pm.PackageManagerServiceUtils.compareSignatures;
import static com.android.server.pm.PackageManagerServiceUtils.compressedFileExists;
import static com.android.server.pm.PackageManagerServiceUtils.deriveAbiOverride;
import static com.android.server.pm.PackageManagerServiceUtils.logCriticalInfo;
import static com.android.server.pm.PackageManagerServiceUtils.verifySignatures;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppOpsManager;
import android.app.ApplicationPackageManager;
import android.app.backup.IBackupManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
import android.content.pm.DataLoaderType;
import android.content.pm.IPackageInstallObserver2;
import android.content.pm.PackageChangeEvent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInfoLite;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.content.pm.VerifierInfo;
import android.content.pm.dex.DexMetadataHelper;
import android.content.pm.parsing.ParsingPackageUtils;
import android.content.pm.parsing.component.ComponentMutateUtils;
import android.content.pm.parsing.component.ParsedInstrumentation;
import android.content.pm.parsing.component.ParsedPermission;
import android.content.pm.parsing.component.ParsedPermissionGroup;
import android.content.pm.parsing.result.ParseResult;
import android.content.pm.parsing.result.ParseTypeImpl;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.incremental.IncrementalManager;
import android.os.incremental.IncrementalStorage;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.stats.storage.StorageEnums;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.F2fsUtils;
import com.android.internal.content.NativeLibraryHelper;
import com.android.internal.content.PackageHelper;
import com.android.internal.security.VerityUtils;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.EventLogTags;
import com.android.server.Watchdog;
import com.android.server.pm.dex.DexoptOptions;
import com.android.server.pm.parsing.PackageParser2;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;
import com.android.server.pm.parsing.pkg.ParsedPackage;
import com.android.server.pm.permission.Permission;
import com.android.server.pm.permission.PermissionManagerServiceInternal;
import com.android.server.pm.pkg.PackageUserState;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.rollback.RollbackManagerInternal;
import com.android.server.utils.WatchedLongSparseArray;

import dalvik.system.VMRuntime;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.DigestException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class InstallPackageHelper {
    /**
     * Whether verification is enabled by default.
     */
    private static final boolean DEFAULT_VERIFY_ENABLE = true;

    private final PackageManagerService mPm;
    private final AppDataHelper mAppDataHelper;
    private final PackageManagerServiceInjector mInjector;
    private final BroadcastHelper mBroadcastHelper;

    // TODO(b/198166813): remove PMS dependency
    InstallPackageHelper(PackageManagerService pm, AppDataHelper appDataHelper) {
        mPm = pm;
        mInjector = pm.mInjector;
        mAppDataHelper = appDataHelper;
        mBroadcastHelper = new BroadcastHelper(mInjector);
    }

    InstallPackageHelper(PackageManagerService pm) {
        this(pm, new AppDataHelper(pm));
    }

    InstallPackageHelper(PackageManagerService pm, PackageManagerServiceInjector injector) {
        mPm = pm;
        mInjector = injector;
        mAppDataHelper = new AppDataHelper(pm, mInjector);
        mBroadcastHelper = new BroadcastHelper(injector);
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

    public void processInstallRequests(boolean success, List<InstallRequest> installRequests) {
        List<InstallRequest> apexInstallRequests = new ArrayList<>();
        List<InstallRequest> apkInstallRequests = new ArrayList<>();
        for (InstallRequest request : installRequests) {
            if ((request.mArgs.mInstallFlags & PackageManager.INSTALL_APEX) != 0) {
                apexInstallRequests.add(request);
            } else {
                apkInstallRequests.add(request);
            }
        }
        // Note: supporting multi package install of both APEXes and APKs might requir some
        // thinking to ensure atomicity of the install.
        if (!apexInstallRequests.isEmpty() && !apkInstallRequests.isEmpty()) {
            // This should've been caught at the validation step, but for some reason wasn't.
            throw new IllegalStateException(
                    "Attempted to do a multi package install of both APEXes and APKs");
        }
        if (!apexInstallRequests.isEmpty()) {
            if (success) {
                // Since installApexPackages requires talking to external service (apexd), we
                // schedule to run it async. Once it finishes, it will resume the install.
                Thread t = new Thread(() -> installApexPackagesTraced(apexInstallRequests),
                        "installApexPackages");
                t.start();
            } else {
                // Non-staged APEX installation failed somewhere before
                // processInstallRequestAsync. In that case just notify the observer about the
                // failure.
                InstallRequest request = apexInstallRequests.get(0);
                mPm.notifyInstallObserver(request.mInstallResult,
                        request.mArgs.mObserver);
            }
            return;
        }
        if (success) {
            for (InstallRequest request : apkInstallRequests) {
                request.mArgs.doPreInstall(request.mInstallResult.mReturnCode);
            }
            synchronized (mPm.mInstallLock) {
                installPackagesTracedLI(apkInstallRequests);
            }
            for (InstallRequest request : apkInstallRequests) {
                request.mArgs.doPostInstall(
                        request.mInstallResult.mReturnCode, request.mInstallResult.mUid);
            }
        }
        for (InstallRequest request : apkInstallRequests) {
            restoreAndPostInstall(request.mArgs.mUser.getIdentifier(),
                    request.mInstallResult,
                    new PostInstallData(request.mArgs,
                            request.mInstallResult, null));
        }
    }

    private void installApexPackagesTraced(List<InstallRequest> requests) {
        try {
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "installApexPackages");
            installApexPackages(requests);
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    private void installApexPackages(List<InstallRequest> requests) {
        if (requests.isEmpty()) {
            return;
        }
        if (requests.size() != 1) {
            throw new IllegalStateException(
                    "Only a non-staged install of a single APEX is supported");
        }
        InstallRequest request = requests.get(0);
        try {
            // Should directory scanning logic be moved to ApexManager for better test coverage?
            final File dir = request.mArgs.mOriginInfo.mResolvedFile;
            final File[] apexes = dir.listFiles();
            if (apexes == null) {
                throw new PackageManagerException(INSTALL_FAILED_INTERNAL_ERROR,
                        dir.getAbsolutePath() + " is not a directory");
            }
            if (apexes.length != 1) {
                throw new PackageManagerException(INSTALL_FAILED_INTERNAL_ERROR,
                        "Expected exactly one .apex file under " + dir.getAbsolutePath()
                                + " got: " + apexes.length);
            }
            try (PackageParser2 packageParser = mPm.mInjector.getScanningPackageParser()) {
                mPm.mApexManager.installPackage(apexes[0], packageParser);
            }
        } catch (PackageManagerException e) {
            request.mInstallResult.setError("APEX installation failed", e);
        }
        PackageManagerService.invalidatePackageInfoCache();
        mPm.notifyInstallObserver(request.mInstallResult, request.mArgs.mObserver);
    }

    @GuardedBy("mInstallLock")
    private void installPackagesTracedLI(List<InstallRequest> requests) {
        try {
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "installPackages");
            installPackagesLI(requests);
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    /**
     * Installs one or more packages atomically. This operation is broken up into four phases:
     * <ul>
     *     <li><b>Prepare</b>
     *         <br/>Analyzes any current install state, parses the package and does initial
     *         validation on it.</li>
     *     <li><b>Scan</b>
     *         <br/>Interrogates the parsed packages given the context collected in prepare.</li>
     *     <li><b>Reconcile</b>
     *         <br/>Validates scanned packages in the context of each other and the current system
     *         state to ensure that the install will be successful.
     *     <li><b>Commit</b>
     *         <br/>Commits all scanned packages and updates system state. This is the only place
     *         that system state may be modified in the install flow and all predictable errors
     *         must be determined before this phase.</li>
     * </ul>
     *
     * Failure at any phase will result in a full failure to install all packages.
     */
    @GuardedBy("mInstallLock")
    private void installPackagesLI(List<InstallRequest> requests) {
        final Map<String, ScanResult> preparedScans = new ArrayMap<>(requests.size());
        final Map<String, InstallArgs> installArgs = new ArrayMap<>(requests.size());
        final Map<String, PackageInstalledInfo> installResults = new ArrayMap<>(requests.size());
        final Map<String, PrepareResult> prepareResults = new ArrayMap<>(requests.size());
        final Map<String, Settings.VersionInfo> versionInfos = new ArrayMap<>(requests.size());
        final Map<String, PackageSetting> lastStaticSharedLibSettings =
                new ArrayMap<>(requests.size());
        final Map<String, Boolean> createdAppId = new ArrayMap<>(requests.size());
        boolean success = false;
        final ScanPackageHelper scanPackageHelper = new ScanPackageHelper(mPm);
        try {
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "installPackagesLI");
            for (InstallRequest request : requests) {
                // TODO(b/109941548): remove this once we've pulled everything from it and into
                //                    scan, reconcile or commit.
                final PrepareResult prepareResult;
                try {
                    Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "preparePackage");
                    prepareResult =
                            preparePackageLI(request.mArgs, request.mInstallResult);
                } catch (PrepareFailure prepareFailure) {
                    request.mInstallResult.setError(prepareFailure.error,
                            prepareFailure.getMessage());
                    request.mInstallResult.mOrigPackage = prepareFailure.mConflictingPackage;
                    request.mInstallResult.mOrigPermission = prepareFailure.mConflictingPermission;
                    return;
                } finally {
                    Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
                }
                request.mInstallResult.setReturnCode(PackageManager.INSTALL_SUCCEEDED);
                request.mInstallResult.mInstallerPackageName =
                        request.mArgs.mInstallSource.installerPackageName;

                final String packageName = prepareResult.mPackageToScan.getPackageName();
                prepareResults.put(packageName, prepareResult);
                installResults.put(packageName, request.mInstallResult);
                installArgs.put(packageName, request.mArgs);
                try {
                    final ScanResult result = scanPackageHelper.scanPackageTracedLI(
                            prepareResult.mPackageToScan, prepareResult.mParseFlags,
                            prepareResult.mScanFlags, System.currentTimeMillis(),
                            request.mArgs.mUser, request.mArgs.mAbiOverride);
                    if (null != preparedScans.put(result.mPkgSetting.getPkg().getPackageName(),
                            result)) {
                        request.mInstallResult.setError(
                                PackageManager.INSTALL_FAILED_DUPLICATE_PACKAGE,
                                "Duplicate package "
                                        + result.mPkgSetting.getPkg().getPackageName()
                                        + " in multi-package install request.");
                        return;
                    }
                    createdAppId.put(packageName,
                            scanPackageHelper.optimisticallyRegisterAppId(result));
                    versionInfos.put(result.mPkgSetting.getPkg().getPackageName(),
                            mPm.getSettingsVersionForPackage(result.mPkgSetting.getPkg()));
                    if (result.mStaticSharedLibraryInfo != null) {
                        final PackageSetting sharedLibLatestVersionSetting =
                                mPm.getSharedLibLatestVersionSetting(result);
                        if (sharedLibLatestVersionSetting != null) {
                            lastStaticSharedLibSettings.put(
                                    result.mPkgSetting.getPkg().getPackageName(),
                                    sharedLibLatestVersionSetting);
                        }
                    }
                } catch (PackageManagerException e) {
                    request.mInstallResult.setError("Scanning Failed.", e);
                    return;
                }
            }
            ReconcileRequest
                    reconcileRequest = new ReconcileRequest(preparedScans, installArgs,
                    installResults,
                    prepareResults,
                    mPm.mSharedLibraries,
                    Collections.unmodifiableMap(mPm.mPackages), versionInfos,
                    lastStaticSharedLibSettings);
            CommitRequest commitRequest = null;
            synchronized (mPm.mLock) {
                Map<String, ReconciledPackage> reconciledPackages;
                try {
                    Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "reconcilePackages");
                    reconciledPackages = reconcilePackagesLocked(
                            reconcileRequest, mPm.mSettings.getKeySetManagerService(),
                            mPm.mInjector);
                } catch (ReconcileFailure e) {
                    for (InstallRequest request : requests) {
                        request.mInstallResult.setError("Reconciliation failed...", e);
                    }
                    return;
                } finally {
                    Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
                }
                try {
                    Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "commitPackages");
                    commitRequest = new CommitRequest(reconciledPackages,
                            mPm.mUserManager.getUserIds());
                    commitPackagesLocked(commitRequest);
                    success = true;
                } finally {
                    Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
                }
            }
            executePostCommitSteps(commitRequest);
        } finally {
            if (success) {
                for (InstallRequest request : requests) {
                    final InstallArgs args = request.mArgs;
                    if (args.mDataLoaderType != DataLoaderType.INCREMENTAL) {
                        continue;
                    }
                    if (args.mSigningDetails.getSignatureSchemeVersion() != SIGNING_BLOCK_V4) {
                        continue;
                    }
                    // For incremental installs, we bypass the verifier prior to install. Now
                    // that we know the package is valid, send a notice to the verifier with
                    // the root hash of the base.apk.
                    final String baseCodePath = request.mInstallResult.mPkg.getBaseApkPath();
                    final String[] splitCodePaths = request.mInstallResult.mPkg.getSplitCodePaths();
                    final Uri originUri = Uri.fromFile(args.mOriginInfo.mResolvedFile);
                    final int verificationId = mPm.mPendingVerificationToken++;
                    final String rootHashString = PackageManagerServiceUtils
                            .buildVerificationRootHashString(baseCodePath, splitCodePaths);
                    VerificationUtils.broadcastPackageVerified(verificationId, originUri,
                            PackageManager.VERIFICATION_ALLOW, rootHashString,
                            args.mDataLoaderType, args.getUser(), mPm.mContext);
                }
            } else {
                for (ScanResult result : preparedScans.values()) {
                    if (createdAppId.getOrDefault(result.mRequest.mParsedPackage.getPackageName(),
                            false)) {
                        scanPackageHelper.cleanUpAppIdCreation(result);
                    }
                }
                // TODO(b/194319951): create a more descriptive reason than unknown
                // mark all non-failure installs as UNKNOWN so we do not treat them as success
                for (InstallRequest request : requests) {
                    if (request.mInstallResult.mFreezer != null) {
                        request.mInstallResult.mFreezer.close();
                    }
                    if (request.mInstallResult.mReturnCode == PackageManager.INSTALL_SUCCEEDED) {
                        request.mInstallResult.mReturnCode = PackageManager.INSTALL_UNKNOWN;
                    }
                }
            }
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    @GuardedBy("mInstallLock")
    private PrepareResult preparePackageLI(InstallArgs args, PackageInstalledInfo res)
            throws PrepareFailure {
        final int installFlags = args.mInstallFlags;
        final File tmpPackageFile = new File(args.getCodePath());
        final boolean onExternal = args.mVolumeUuid != null;
        final boolean instantApp = ((installFlags & PackageManager.INSTALL_INSTANT_APP) != 0);
        final boolean fullApp = ((installFlags & PackageManager.INSTALL_FULL_APP) != 0);
        final boolean virtualPreload =
                ((installFlags & PackageManager.INSTALL_VIRTUAL_PRELOAD) != 0);
        final boolean isRollback = args.mInstallReason == PackageManager.INSTALL_REASON_ROLLBACK;
        @PackageManagerService.ScanFlags int scanFlags = SCAN_NEW_INSTALL | SCAN_UPDATE_SIGNATURE;
        if (args.mMoveInfo != null) {
            // moving a complete application; perform an initial scan on the new install location
            scanFlags |= SCAN_INITIAL;
        }
        if ((installFlags & PackageManager.INSTALL_DONT_KILL_APP) != 0) {
            scanFlags |= SCAN_DONT_KILL_APP;
        }
        if (instantApp) {
            scanFlags |= SCAN_AS_INSTANT_APP;
        }
        if (fullApp) {
            scanFlags |= SCAN_AS_FULL_APP;
        }
        if (virtualPreload) {
            scanFlags |= SCAN_AS_VIRTUAL_PRELOAD;
        }

        if (DEBUG_INSTALL) Slog.d(TAG, "installPackageLI: path=" + tmpPackageFile);

        // Validity check
        if (instantApp && onExternal) {
            Slog.i(TAG, "Incompatible ephemeral install; external=" + onExternal);
            throw new PrepareFailure(PackageManager.INSTALL_FAILED_SESSION_INVALID);
        }

        // Retrieve PackageSettings and parse package
        @ParsingPackageUtils.ParseFlags final int parseFlags =
                mPm.getDefParseFlags() | ParsingPackageUtils.PARSE_CHATTY
                        | ParsingPackageUtils.PARSE_ENFORCE_CODE
                        | (onExternal ? ParsingPackageUtils.PARSE_EXTERNAL_STORAGE : 0);

        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "parsePackage");
        final ParsedPackage parsedPackage;
        try (PackageParser2 pp = mPm.mInjector.getPreparingPackageParser()) {
            parsedPackage = pp.parsePackage(tmpPackageFile, parseFlags, false);
            AndroidPackageUtils.validatePackageDexMetadata(parsedPackage);
        } catch (PackageManagerException e) {
            throw new PrepareFailure("Failed parse during installPackageLI", e);
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }

        // Instant apps have several additional install-time checks.
        if (instantApp) {
            if (parsedPackage.getTargetSdkVersion() < Build.VERSION_CODES.O) {
                Slog.w(TAG, "Instant app package " + parsedPackage.getPackageName()
                        + " does not target at least O");
                throw new PrepareFailure(INSTALL_FAILED_SESSION_INVALID,
                        "Instant app package must target at least O");
            }
            if (parsedPackage.getSharedUserId() != null) {
                Slog.w(TAG, "Instant app package " + parsedPackage.getPackageName()
                        + " may not declare sharedUserId.");
                throw new PrepareFailure(INSTALL_FAILED_SESSION_INVALID,
                        "Instant app package may not declare a sharedUserId");
            }
        }

        if (parsedPackage.isStaticSharedLibrary()) {
            // Static shared libraries have synthetic package names
            PackageManagerService.renameStaticSharedLibraryPackage(parsedPackage);

            // No static shared libs on external storage
            if (onExternal) {
                Slog.i(TAG, "Static shared libs can only be installed on internal storage.");
                throw new PrepareFailure(INSTALL_FAILED_INVALID_INSTALL_LOCATION,
                        "Packages declaring static-shared libs cannot be updated");
            }
        }

        String pkgName = res.mName = parsedPackage.getPackageName();
        if (parsedPackage.isTestOnly()) {
            if ((installFlags & PackageManager.INSTALL_ALLOW_TEST) == 0) {
                throw new PrepareFailure(INSTALL_FAILED_TEST_ONLY, "installPackageLI");
            }
        }

        // either use what we've been given or parse directly from the APK
        if (args.mSigningDetails != SigningDetails.UNKNOWN) {
            parsedPackage.setSigningDetails(args.mSigningDetails);
        } else {
            final ParseTypeImpl input = ParseTypeImpl.forDefaultParsing();
            final ParseResult<SigningDetails> result = ParsingPackageUtils.getSigningDetails(
                    input, parsedPackage, false /*skipVerify*/);
            if (result.isError()) {
                throw new PrepareFailure("Failed collect during installPackageLI",
                        result.getException());
            }
            parsedPackage.setSigningDetails(result.getResult());
        }

        if (instantApp && parsedPackage.getSigningDetails().getSignatureSchemeVersion()
                < SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V2) {
            Slog.w(TAG, "Instant app package " + parsedPackage.getPackageName()
                    + " is not signed with at least APK Signature Scheme v2");
            throw new PrepareFailure(INSTALL_FAILED_SESSION_INVALID,
                    "Instant app package must be signed with APK Signature Scheme v2 or greater");
        }

        boolean systemApp = false;
        boolean replace = false;
        synchronized (mPm.mLock) {
            // Check if installing already existing package
            if ((installFlags & PackageManager.INSTALL_REPLACE_EXISTING) != 0) {
                String oldName = mPm.mSettings.getRenamedPackageLPr(pkgName);
                if (parsedPackage.getOriginalPackages().contains(oldName)
                        && mPm.mPackages.containsKey(oldName)) {
                    // This package is derived from an original package,
                    // and this device has been updating from that original
                    // name.  We must continue using the original name, so
                    // rename the new package here.
                    parsedPackage.setPackageName(oldName);
                    pkgName = parsedPackage.getPackageName();
                    replace = true;
                    if (DEBUG_INSTALL) {
                        Slog.d(TAG, "Replacing existing renamed package: oldName="
                                + oldName + " pkgName=" + pkgName);
                    }
                } else if (mPm.mPackages.containsKey(pkgName)) {
                    // This package, under its official name, already exists
                    // on the device; we should replace it.
                    replace = true;
                    if (DEBUG_INSTALL) Slog.d(TAG, "Replace existing package: " + pkgName);
                }

                if (replace) {
                    // Prevent apps opting out from runtime permissions
                    AndroidPackage oldPackage = mPm.mPackages.get(pkgName);
                    final int oldTargetSdk = oldPackage.getTargetSdkVersion();
                    final int newTargetSdk = parsedPackage.getTargetSdkVersion();
                    if (oldTargetSdk > Build.VERSION_CODES.LOLLIPOP_MR1
                            && newTargetSdk <= Build.VERSION_CODES.LOLLIPOP_MR1) {
                        throw new PrepareFailure(
                                PackageManager.INSTALL_FAILED_PERMISSION_MODEL_DOWNGRADE,
                                "Package " + parsedPackage.getPackageName()
                                        + " new target SDK " + newTargetSdk
                                        + " doesn't support runtime permissions but the old"
                                        + " target SDK " + oldTargetSdk + " does.");
                    }
                    // Prevent persistent apps from being updated
                    if (oldPackage.isPersistent()
                            && ((installFlags & PackageManager.INSTALL_STAGED) == 0)) {
                        throw new PrepareFailure(PackageManager.INSTALL_FAILED_INVALID_APK,
                                "Package " + oldPackage.getPackageName() + " is a persistent app. "
                                        + "Persistent apps are not updateable.");
                    }
                }
            }

            PackageSetting ps = mPm.mSettings.getPackageLPr(pkgName);
            if (ps != null) {
                if (DEBUG_INSTALL) Slog.d(TAG, "Existing package: " + ps);

                // Static shared libs have same package with different versions where
                // we internally use a synthetic package name to allow multiple versions
                // of the same package, therefore we need to compare signatures against
                // the package setting for the latest library version.
                PackageSetting signatureCheckPs = ps;
                if (parsedPackage.isStaticSharedLibrary()) {
                    SharedLibraryInfo libraryInfo = mPm.getLatestSharedLibraVersionLPr(
                            parsedPackage);
                    if (libraryInfo != null) {
                        signatureCheckPs = mPm.mSettings.getPackageLPr(
                                libraryInfo.getPackageName());
                    }
                }

                // Quick validity check that we're signed correctly if updating;
                // we'll check this again later when scanning, but we want to
                // bail early here before tripping over redefined permissions.
                final KeySetManagerService ksms = mPm.mSettings.getKeySetManagerService();
                if (ksms.shouldCheckUpgradeKeySetLocked(signatureCheckPs, scanFlags)) {
                    if (!ksms.checkUpgradeKeySetLocked(signatureCheckPs, parsedPackage)) {
                        throw new PrepareFailure(INSTALL_FAILED_UPDATE_INCOMPATIBLE, "Package "
                                + parsedPackage.getPackageName() + " upgrade keys do not match the "
                                + "previously installed version");
                    }
                } else {
                    try {
                        final boolean compareCompat =
                                isCompatSignatureUpdateNeeded(parsedPackage);
                        final boolean compareRecover =
                                isRecoverSignatureUpdateNeeded(parsedPackage);
                        // We don't care about disabledPkgSetting on install for now.
                        final boolean compatMatch = verifySignatures(signatureCheckPs, null,
                                parsedPackage.getSigningDetails(), compareCompat, compareRecover,
                                isRollback);
                        // The new KeySets will be re-added later in the scanning process.
                        if (compatMatch) {
                            synchronized (mPm.mLock) {
                                ksms.removeAppKeySetDataLPw(parsedPackage.getPackageName());
                            }
                        }
                    } catch (PackageManagerException e) {
                        throw new PrepareFailure(e.error, e.getMessage());
                    }
                }

                if (ps.getPkg() != null) {
                    systemApp = ps.getPkg().isSystem();
                }
                res.mOrigUsers = ps.queryInstalledUsers(mPm.mUserManager.getUserIds(), true);
            }

            final int numGroups = ArrayUtils.size(parsedPackage.getPermissionGroups());
            for (int groupNum = 0; groupNum < numGroups; groupNum++) {
                final ParsedPermissionGroup group =
                        parsedPackage.getPermissionGroups().get(groupNum);
                final PermissionGroupInfo sourceGroup = mPm.getPermissionGroupInfo(group.getName(),
                        0);

                if (sourceGroup != null && cannotInstallWithBadPermissionGroups(parsedPackage)) {
                    final String sourcePackageName = sourceGroup.packageName;

                    if ((replace || !parsedPackage.getPackageName().equals(sourcePackageName))
                            && !doesSignatureMatchForPermissions(sourcePackageName, parsedPackage,
                            scanFlags)) {
                        EventLog.writeEvent(0x534e4554, "146211400", -1,
                                parsedPackage.getPackageName());

                        throw new PrepareFailure(INSTALL_FAILED_DUPLICATE_PERMISSION_GROUP,
                                "Package "
                                        + parsedPackage.getPackageName()
                                        + " attempting to redeclare permission group "
                                        + group.getName() + " already owned by "
                                        + sourcePackageName);
                    }
                }
            }

            // TODO: Move logic for checking permission compatibility into PermissionManagerService
            final int n = ArrayUtils.size(parsedPackage.getPermissions());
            for (int i = n - 1; i >= 0; i--) {
                final ParsedPermission perm = parsedPackage.getPermissions().get(i);
                final Permission bp = mPm.mPermissionManager.getPermissionTEMP(perm.getName());

                // Don't allow anyone but the system to define ephemeral permissions.
                if ((perm.getProtectionLevel() & PermissionInfo.PROTECTION_FLAG_INSTANT) != 0
                        && !systemApp) {
                    Slog.w(TAG, "Non-System package " + parsedPackage.getPackageName()
                            + " attempting to delcare ephemeral permission "
                            + perm.getName() + "; Removing ephemeral.");
                    ComponentMutateUtils.setProtectionLevel(perm,
                            perm.getProtectionLevel() & ~PermissionInfo.PROTECTION_FLAG_INSTANT);
                }

                // Check whether the newly-scanned package wants to define an already-defined perm
                if (bp != null) {
                    final String sourcePackageName = bp.getPackageName();

                    if (!doesSignatureMatchForPermissions(sourcePackageName, parsedPackage,
                            scanFlags)) {
                        // If the owning package is the system itself, we log but allow
                        // install to proceed; we fail the install on all other permission
                        // redefinitions.
                        if (!sourcePackageName.equals("android")) {
                            throw new PrepareFailure(INSTALL_FAILED_DUPLICATE_PERMISSION,
                                    "Package "
                                            + parsedPackage.getPackageName()
                                            + " attempting to redeclare permission "
                                            + perm.getName() + " already owned by "
                                            + sourcePackageName)
                                    .conflictsWithExistingPermission(perm.getName(),
                                            sourcePackageName);
                        } else {
                            Slog.w(TAG, "Package " + parsedPackage.getPackageName()
                                    + " attempting to redeclare system permission "
                                    + perm.getName() + "; ignoring new declaration");
                            parsedPackage.removePermission(i);
                        }
                    } else if (!PLATFORM_PACKAGE_NAME.equals(parsedPackage.getPackageName())) {
                        // Prevent apps to change protection level to dangerous from any other
                        // type as this would allow a privilege escalation where an app adds a
                        // normal/signature permission in other app's group and later redefines
                        // it as dangerous leading to the group auto-grant.
                        if ((perm.getProtectionLevel() & PermissionInfo.PROTECTION_MASK_BASE)
                                == PermissionInfo.PROTECTION_DANGEROUS) {
                            if (bp != null && !bp.isRuntime()) {
                                Slog.w(TAG, "Package " + parsedPackage.getPackageName()
                                        + " trying to change a non-runtime permission "
                                        + perm.getName()
                                        + " to runtime; keeping old protection level");
                                ComponentMutateUtils.setProtectionLevel(perm,
                                        bp.getProtectionLevel());
                            }
                        }
                    }
                }

                if (perm.getGroup() != null
                        && cannotInstallWithBadPermissionGroups(parsedPackage)) {
                    boolean isPermGroupDefinedByPackage = false;
                    for (int groupNum = 0; groupNum < numGroups; groupNum++) {
                        if (parsedPackage.getPermissionGroups().get(groupNum).getName()
                                .equals(perm.getGroup())) {
                            isPermGroupDefinedByPackage = true;
                            break;
                        }
                    }

                    if (!isPermGroupDefinedByPackage) {
                        final PermissionGroupInfo sourceGroup =
                                mPm.getPermissionGroupInfo(perm.getGroup(), 0);

                        if (sourceGroup == null) {
                            EventLog.writeEvent(0x534e4554, "146211400", -1,
                                    parsedPackage.getPackageName());

                            throw new PrepareFailure(INSTALL_FAILED_BAD_PERMISSION_GROUP,
                                    "Package "
                                            + parsedPackage.getPackageName()
                                            + " attempting to declare permission "
                                            + perm.getName() + " in non-existing group "
                                            + perm.getGroup());
                        } else {
                            String groupSourcePackageName = sourceGroup.packageName;

                            if (!PLATFORM_PACKAGE_NAME.equals(groupSourcePackageName)
                                    && !doesSignatureMatchForPermissions(groupSourcePackageName,
                                    parsedPackage, scanFlags)) {
                                EventLog.writeEvent(0x534e4554, "146211400", -1,
                                        parsedPackage.getPackageName());

                                throw new PrepareFailure(INSTALL_FAILED_BAD_PERMISSION_GROUP,
                                        "Package "
                                                + parsedPackage.getPackageName()
                                                + " attempting to declare permission "
                                                + perm.getName() + " in group "
                                                + perm.getGroup() + " owned by package "
                                                + groupSourcePackageName
                                                + " with incompatible certificate");
                            }
                        }
                    }
                }
            }
        }

        if (systemApp) {
            if (onExternal) {
                // Abort update; system app can't be replaced with app on sdcard
                throw new PrepareFailure(INSTALL_FAILED_INVALID_INSTALL_LOCATION,
                        "Cannot install updates to system apps on sdcard");
            } else if (instantApp) {
                // Abort update; system app can't be replaced with an instant app
                throw new PrepareFailure(INSTALL_FAILED_SESSION_INVALID,
                        "Cannot update a system app with an instant app");
            }
        }

        if (args.mMoveInfo != null) {
            // We did an in-place move, so dex is ready to roll
            scanFlags |= SCAN_NO_DEX;
            scanFlags |= SCAN_MOVE;

            synchronized (mPm.mLock) {
                final PackageSetting ps = mPm.mSettings.getPackageLPr(pkgName);
                if (ps == null) {
                    res.setError(INSTALL_FAILED_INTERNAL_ERROR,
                            "Missing settings for moved package " + pkgName);
                }

                // We moved the entire application as-is, so bring over the
                // previously derived ABI information.
                parsedPackage.setPrimaryCpuAbi(ps.getPrimaryCpuAbi())
                        .setSecondaryCpuAbi(ps.getSecondaryCpuAbi());
            }

        } else {
            // Enable SCAN_NO_DEX flag to skip dexopt at a later stage
            scanFlags |= SCAN_NO_DEX;

            try {
                PackageSetting pkgSetting;
                AndroidPackage oldPackage;
                synchronized (mPm.mLock) {
                    pkgSetting = mPm.mSettings.getPackageLPr(pkgName);
                    oldPackage = mPm.mPackages.get(pkgName);
                }
                boolean isUpdatedSystemAppFromExistingSetting = pkgSetting != null
                        && pkgSetting.getPkgState().isUpdatedSystemApp();
                final String abiOverride = deriveAbiOverride(args.mAbiOverride);
                boolean isUpdatedSystemAppInferred = oldPackage != null && oldPackage.isSystem();
                final Pair<PackageAbiHelper.Abis, PackageAbiHelper.NativeLibraryPaths>
                        derivedAbi = mPm.mInjector.getAbiHelper().derivePackageAbi(parsedPackage,
                        isUpdatedSystemAppFromExistingSetting || isUpdatedSystemAppInferred,
                        abiOverride, mPm.mAppLib32InstallDir);
                derivedAbi.first.applyTo(parsedPackage);
                derivedAbi.second.applyTo(parsedPackage);
            } catch (PackageManagerException pme) {
                Slog.e(TAG, "Error deriving application ABI", pme);
                throw new PrepareFailure(INSTALL_FAILED_INTERNAL_ERROR,
                        "Error deriving application ABI: " + pme.getMessage());
            }
        }

        if (!args.doRename(res.mReturnCode, parsedPackage)) {
            throw new PrepareFailure(INSTALL_FAILED_INSUFFICIENT_STORAGE, "Failed rename");
        }

        try {
            setUpFsVerityIfPossible(parsedPackage);
        } catch (Installer.InstallerException | IOException | DigestException
                | NoSuchAlgorithmException e) {
            throw new PrepareFailure(INSTALL_FAILED_INTERNAL_ERROR,
                    "Failed to set up verity: " + e);
        }

        final PackageFreezer freezer =
                freezePackageForInstall(pkgName, installFlags, "installPackageLI");
        boolean shouldCloseFreezerBeforeReturn = true;
        try {
            final AndroidPackage oldPackage;
            String renamedPackage;
            boolean sysPkg = false;
            int targetScanFlags = scanFlags;
            int targetParseFlags = parseFlags;
            final PackageSetting ps;
            final PackageSetting disabledPs;
            if (replace) {
                final String pkgName11 = parsedPackage.getPackageName();
                synchronized (mPm.mLock) {
                    oldPackage = mPm.mPackages.get(pkgName11);
                }
                if (parsedPackage.isStaticSharedLibrary()) {
                    // Static libs have a synthetic package name containing the version
                    // and cannot be updated as an update would get a new package name,
                    // unless this is installed from adb which is useful for development.
                    if (oldPackage != null
                            && (installFlags & PackageManager.INSTALL_FROM_ADB) == 0) {
                        throw new PrepareFailure(INSTALL_FAILED_DUPLICATE_PACKAGE,
                                "Packages declaring "
                                        + "static-shared libs cannot be updated");
                    }
                }

                final boolean isInstantApp = (scanFlags & SCAN_AS_INSTANT_APP) != 0;

                final int[] allUsers;
                final int[] installedUsers;
                final int[] uninstalledUsers;

                synchronized (mPm.mLock) {
                    if (DEBUG_INSTALL) {
                        Slog.d(TAG,
                                "replacePackageLI: new=" + parsedPackage + ", old=" + oldPackage);
                    }

                    ps = mPm.mSettings.getPackageLPr(pkgName11);
                    disabledPs = mPm.mSettings.getDisabledSystemPkgLPr(ps);

                    // verify signatures are valid
                    final KeySetManagerService ksms = mPm.mSettings.getKeySetManagerService();
                    if (ksms.shouldCheckUpgradeKeySetLocked(ps, scanFlags)) {
                        if (!ksms.checkUpgradeKeySetLocked(ps, parsedPackage)) {
                            throw new PrepareFailure(INSTALL_FAILED_UPDATE_INCOMPATIBLE,
                                    "New package not signed by keys specified by upgrade-keysets: "
                                            + pkgName11);
                        }
                    } else {
                        SigningDetails parsedPkgSigningDetails = parsedPackage.getSigningDetails();
                        SigningDetails oldPkgSigningDetails = oldPackage.getSigningDetails();
                        // default to original signature matching
                        if (!parsedPkgSigningDetails.checkCapability(oldPkgSigningDetails,
                                SigningDetails.CertCapabilities.INSTALLED_DATA)
                                && !oldPkgSigningDetails.checkCapability(parsedPkgSigningDetails,
                                SigningDetails.CertCapabilities.ROLLBACK)) {
                            // Allow the update to proceed if this is a rollback and the parsed
                            // package's current signing key is the current signer or in the lineage
                            // of the old package; this allows a rollback to a previously installed
                            // version after an app's signing key has been rotated without requiring
                            // the rollback capability on the previous signing key.
                            if (!isRollback || !oldPkgSigningDetails.hasAncestorOrSelf(
                                    parsedPkgSigningDetails)) {
                                throw new PrepareFailure(INSTALL_FAILED_UPDATE_INCOMPATIBLE,
                                        "New package has a different signature: " + pkgName11);
                            }
                        }
                    }

                    // don't allow a system upgrade unless the upgrade hash matches
                    if (oldPackage.getRestrictUpdateHash() != null && oldPackage.isSystem()) {
                        final byte[] digestBytes;
                        try {
                            final MessageDigest digest = MessageDigest.getInstance("SHA-512");
                            updateDigest(digest, new File(parsedPackage.getBaseApkPath()));
                            if (!ArrayUtils.isEmpty(parsedPackage.getSplitCodePaths())) {
                                for (String path : parsedPackage.getSplitCodePaths()) {
                                    updateDigest(digest, new File(path));
                                }
                            }
                            digestBytes = digest.digest();
                        } catch (NoSuchAlgorithmException | IOException e) {
                            throw new PrepareFailure(INSTALL_FAILED_INVALID_APK,
                                    "Could not compute hash: " + pkgName11);
                        }
                        if (!Arrays.equals(oldPackage.getRestrictUpdateHash(), digestBytes)) {
                            throw new PrepareFailure(INSTALL_FAILED_INVALID_APK,
                                    "New package fails restrict-update check: " + pkgName11);
                        }
                        // retain upgrade restriction
                        parsedPackage.setRestrictUpdateHash(oldPackage.getRestrictUpdateHash());
                    }

                    // Check for shared user id changes
                    if (!Objects.equals(oldPackage.getSharedUserId(),
                            parsedPackage.getSharedUserId())
                            // Don't mark as invalid if the app is trying to
                            // leave a sharedUserId
                            && parsedPackage.getSharedUserId() != null) {
                        throw new PrepareFailure(INSTALL_FAILED_UID_CHANGED,
                                "Package " + parsedPackage.getPackageName()
                                        + " shared user changed from "
                                        + (oldPackage.getSharedUserId() != null
                                        ? oldPackage.getSharedUserId() : "<nothing>")
                                        + " to " + parsedPackage.getSharedUserId());
                    }

                    // In case of rollback, remember per-user/profile install state
                    allUsers = mPm.mUserManager.getUserIds();
                    installedUsers = ps.queryInstalledUsers(allUsers, true);
                    uninstalledUsers = ps.queryInstalledUsers(allUsers, false);


                    // don't allow an upgrade from full to ephemeral
                    if (isInstantApp) {
                        if (args.mUser == null
                                || args.mUser.getIdentifier() == UserHandle.USER_ALL) {
                            for (int currentUser : allUsers) {
                                if (!ps.getInstantApp(currentUser)) {
                                    // can't downgrade from full to instant
                                    Slog.w(TAG,
                                            "Can't replace full app with instant app: " + pkgName11
                                                    + " for user: " + currentUser);
                                    throw new PrepareFailure(
                                            PackageManager.INSTALL_FAILED_SESSION_INVALID);
                                }
                            }
                        } else if (!ps.getInstantApp(args.mUser.getIdentifier())) {
                            // can't downgrade from full to instant
                            Slog.w(TAG, "Can't replace full app with instant app: " + pkgName11
                                    + " for user: " + args.mUser.getIdentifier());
                            throw new PrepareFailure(
                                    PackageManager.INSTALL_FAILED_SESSION_INVALID);
                        }
                    }
                }

                // Update what is removed
                res.mRemovedInfo = new PackageRemovedInfo(mPm);
                res.mRemovedInfo.mUid = oldPackage.getUid();
                res.mRemovedInfo.mRemovedPackage = oldPackage.getPackageName();
                res.mRemovedInfo.mInstallerPackageName = ps.getInstallSource().installerPackageName;
                res.mRemovedInfo.mIsStaticSharedLib =
                        parsedPackage.getStaticSharedLibName() != null;
                res.mRemovedInfo.mIsUpdate = true;
                res.mRemovedInfo.mOrigUsers = installedUsers;
                res.mRemovedInfo.mInstallReasons = new SparseArray<>(installedUsers.length);
                for (int i = 0; i < installedUsers.length; i++) {
                    final int userId = installedUsers[i];
                    res.mRemovedInfo.mInstallReasons.put(userId, ps.getInstallReason(userId));
                }
                res.mRemovedInfo.mUninstallReasons = new SparseArray<>(uninstalledUsers.length);
                for (int i = 0; i < uninstalledUsers.length; i++) {
                    final int userId = uninstalledUsers[i];
                    res.mRemovedInfo.mUninstallReasons.put(userId, ps.getUninstallReason(userId));
                }

                sysPkg = oldPackage.isSystem();
                if (sysPkg) {
                    // Set the system/privileged/oem/vendor/product flags as needed
                    final boolean privileged = oldPackage.isPrivileged();
                    final boolean oem = oldPackage.isOem();
                    final boolean vendor = oldPackage.isVendor();
                    final boolean product = oldPackage.isProduct();
                    final boolean odm = oldPackage.isOdm();
                    final boolean systemExt = oldPackage.isSystemExt();
                    final @ParsingPackageUtils.ParseFlags int systemParseFlags = parseFlags;
                    final @PackageManagerService.ScanFlags int systemScanFlags = scanFlags
                            | SCAN_AS_SYSTEM
                            | (privileged ? SCAN_AS_PRIVILEGED : 0)
                            | (oem ? SCAN_AS_OEM : 0)
                            | (vendor ? SCAN_AS_VENDOR : 0)
                            | (product ? SCAN_AS_PRODUCT : 0)
                            | (odm ? SCAN_AS_ODM : 0)
                            | (systemExt ? SCAN_AS_SYSTEM_EXT : 0);

                    if (DEBUG_INSTALL) {
                        Slog.d(TAG, "replaceSystemPackageLI: new=" + parsedPackage
                                + ", old=" + oldPackage);
                    }
                    res.setReturnCode(PackageManager.INSTALL_SUCCEEDED);
                    targetParseFlags = systemParseFlags;
                    targetScanFlags = systemScanFlags;
                } else { // non system replace
                    replace = true;
                    if (DEBUG_INSTALL) {
                        Slog.d(TAG,
                                "replaceNonSystemPackageLI: new=" + parsedPackage + ", old="
                                        + oldPackage);
                    }
                }
            } else { // new package install
                ps = null;
                disabledPs = null;
                replace = false;
                oldPackage = null;
                // Remember this for later, in case we need to rollback this install
                String pkgName1 = parsedPackage.getPackageName();

                if (DEBUG_INSTALL) Slog.d(TAG, "installNewPackageLI: " + parsedPackage);

                // TODO(b/194319951): MOVE TO RECONCILE
                synchronized (mPm.mLock) {
                    renamedPackage = mPm.mSettings.getRenamedPackageLPr(pkgName1);
                    if (renamedPackage != null) {
                        // A package with the same name is already installed, though
                        // it has been renamed to an older name.  The package we
                        // are trying to install should be installed as an update to
                        // the existing one, but that has not been requested, so bail.
                        throw new PrepareFailure(INSTALL_FAILED_ALREADY_EXISTS,
                                "Attempt to re-install " + pkgName1
                                        + " without first uninstalling package running as "
                                        + renamedPackage);
                    }
                    if (mPm.mPackages.containsKey(pkgName1)) {
                        // Don't allow installation over an existing package with the same name.
                        throw new PrepareFailure(INSTALL_FAILED_ALREADY_EXISTS,
                                "Attempt to re-install " + pkgName1
                                        + " without first uninstalling.");
                    }
                }
            }
            // we're passing the freezer back to be closed in a later phase of install
            shouldCloseFreezerBeforeReturn = false;

            return new PrepareResult(replace, targetScanFlags, targetParseFlags,
                    oldPackage, parsedPackage, replace /* clearCodeCache */, sysPkg,
                    ps, disabledPs);
        } finally {
            res.mFreezer = freezer;
            if (shouldCloseFreezerBeforeReturn) {
                freezer.close();
            }
        }
    }

    /*
     * Cannot properly check CANNOT_INSTALL_WITH_BAD_PERMISSION_GROUPS using CompatChanges
     * as this only works for packages that are installed
     *
     * TODO: Move logic for permission group compatibility into PermissionManagerService
     */
    @SuppressWarnings("AndroidFrameworkCompatChange")
    private static boolean cannotInstallWithBadPermissionGroups(ParsedPackage parsedPackage) {
        return parsedPackage.getTargetSdkVersion() >= Build.VERSION_CODES.S;
    }

    private boolean doesSignatureMatchForPermissions(@NonNull String sourcePackageName,
            @NonNull ParsedPackage parsedPackage, int scanFlags) {
        // If the defining package is signed with our cert, it's okay.  This
        // also includes the "updating the same package" case, of course.
        // "updating same package" could also involve key-rotation.

        final PackageSetting sourcePackageSetting;
        final KeySetManagerService ksms;
        synchronized (mPm.mLock) {
            sourcePackageSetting = mPm.mSettings.getPackageLPr(sourcePackageName);
            ksms = mPm.mSettings.getKeySetManagerService();
        }

        final SigningDetails sourceSigningDetails = (sourcePackageSetting == null
                ? SigningDetails.UNKNOWN : sourcePackageSetting.getSigningDetails());
        if (sourcePackageName.equals(parsedPackage.getPackageName())
                && (ksms.shouldCheckUpgradeKeySetLocked(
                sourcePackageSetting, scanFlags))) {
            return ksms.checkUpgradeKeySetLocked(sourcePackageSetting, parsedPackage);
        } else {

            // in the event of signing certificate rotation, we need to see if the
            // package's certificate has rotated from the current one, or if it is an
            // older certificate with which the current is ok with sharing permissions
            if (sourceSigningDetails.checkCapability(
                    parsedPackage.getSigningDetails(),
                    SigningDetails.CertCapabilities.PERMISSION)) {
                return true;
            } else if (parsedPackage.getSigningDetails().checkCapability(
                    sourceSigningDetails,
                    SigningDetails.CertCapabilities.PERMISSION)) {
                // the scanned package checks out, has signing certificate rotation
                // history, and is newer; bring it over
                synchronized (mPm.mLock) {
                    sourcePackageSetting.setSigningDetails(parsedPackage.getSigningDetails());
                }
                return true;
            } else {
                return false;
            }
        }
    }

    /**
     * Set up fs-verity for the given package if possible.  This requires a feature flag of system
     * property to be enabled only if the kernel supports fs-verity.
     *
     * <p>When the feature flag is set to legacy mode, only APK is supported (with some experimental
     * kernel patches). In normal mode, all file format can be supported.
     */
    private void setUpFsVerityIfPossible(AndroidPackage pkg) throws Installer.InstallerException,
            PrepareFailure, IOException, DigestException, NoSuchAlgorithmException {
        final boolean standardMode = PackageManagerServiceUtils.isApkVerityEnabled();
        final boolean legacyMode = PackageManagerServiceUtils.isLegacyApkVerityEnabled();
        if (!standardMode && !legacyMode) {
            return;
        }

        if (isIncrementalPath(pkg.getPath()) && IncrementalManager.getVersion()
                < IncrementalManager.MIN_VERSION_TO_SUPPORT_FSVERITY) {
            return;
        }

        // Collect files we care for fs-verity setup.
        ArrayMap<String, String> fsverityCandidates = new ArrayMap<>();
        if (legacyMode) {
            synchronized (mPm.mLock) {
                final PackageSetting ps = mPm.mSettings.getPackageLPr(pkg.getPackageName());
                if (ps != null && ps.isPrivileged()) {
                    fsverityCandidates.put(pkg.getBaseApkPath(), null);
                    if (pkg.getSplitCodePaths() != null) {
                        for (String splitPath : pkg.getSplitCodePaths()) {
                            fsverityCandidates.put(splitPath, null);
                        }
                    }
                }
            }
        } else {
            // NB: These files will become only accessible if the signing key is loaded in kernel's
            // .fs-verity keyring.
            fsverityCandidates.put(pkg.getBaseApkPath(),
                    VerityUtils.getFsveritySignatureFilePath(pkg.getBaseApkPath()));

            final String dmPath = DexMetadataHelper.buildDexMetadataPathForApk(
                    pkg.getBaseApkPath());
            if (new File(dmPath).exists()) {
                fsverityCandidates.put(dmPath, VerityUtils.getFsveritySignatureFilePath(dmPath));
            }

            if (pkg.getSplitCodePaths() != null) {
                for (String path : pkg.getSplitCodePaths()) {
                    fsverityCandidates.put(path, VerityUtils.getFsveritySignatureFilePath(path));

                    final String splitDmPath = DexMetadataHelper.buildDexMetadataPathForApk(path);
                    if (new File(splitDmPath).exists()) {
                        fsverityCandidates.put(splitDmPath,
                                VerityUtils.getFsveritySignatureFilePath(splitDmPath));
                    }
                }
            }
        }

        for (Map.Entry<String, String> entry : fsverityCandidates.entrySet()) {
            final String filePath = entry.getKey();
            final String signaturePath = entry.getValue();

            if (!legacyMode) {
                // fs-verity is optional for now.  Only set up if signature is provided.
                if (new File(signaturePath).exists() && !VerityUtils.hasFsverity(filePath)) {
                    try {
                        VerityUtils.setUpFsverity(filePath, signaturePath);
                    } catch (IOException e) {
                        throw new PrepareFailure(PackageManager.INSTALL_FAILED_BAD_SIGNATURE,
                                "Failed to enable fs-verity: " + e);
                    }
                }
                continue;
            }

            // In legacy mode, fs-verity can only be enabled by process with CAP_SYS_ADMIN.
            final VerityUtils.SetupResult result = VerityUtils.generateApkVeritySetupData(filePath);
            if (result.isOk()) {
                if (Build.IS_DEBUGGABLE) Slog.i(TAG, "Enabling verity to " + filePath);
                final FileDescriptor fd = result.getUnownedFileDescriptor();
                try {
                    final byte[] rootHash = VerityUtils.generateApkVerityRootHash(filePath);
                    try {
                        // A file may already have fs-verity, e.g. when reused during a split
                        // install. If the measurement succeeds, no need to attempt to set up.
                        mPm.mInstaller.assertFsverityRootHashMatches(filePath, rootHash);
                    } catch (Installer.InstallerException e) {
                        mPm.mInstaller.installApkVerity(filePath, fd, result.getContentSize());
                        mPm.mInstaller.assertFsverityRootHashMatches(filePath, rootHash);
                    }
                } finally {
                    IoUtils.closeQuietly(fd);
                }
            } else if (result.isFailed()) {
                throw new PrepareFailure(PackageManager.INSTALL_FAILED_BAD_SIGNATURE,
                        "Failed to generate verity");
            }
        }
    }

    private PackageFreezer freezePackageForInstall(String packageName, int installFlags,
            String killReason) {
        return freezePackageForInstall(packageName, UserHandle.USER_ALL, installFlags, killReason);
    }

    private PackageFreezer freezePackageForInstall(String packageName, int userId, int installFlags,
            String killReason) {
        if ((installFlags & PackageManager.INSTALL_DONT_KILL_APP) != 0) {
            return new PackageFreezer(mPm);
        } else {
            return mPm.freezePackage(packageName, userId, killReason);
        }
    }

    private static void updateDigest(MessageDigest digest, File file) throws IOException {
        try (DigestInputStream digestStream =
                     new DigestInputStream(new FileInputStream(file), digest)) {
            int length, total = 0;
            while ((length = digestStream.read()) != -1) {
                total += length;
            } // just plow through the file
        }
    }

    @GuardedBy("mLock")
    private void commitPackagesLocked(final CommitRequest request) {
        // TODO: remove any expected failures from this method; this should only be able to fail due
        //       to unavoidable errors (I/O, etc.)
        for (ReconciledPackage reconciledPkg : request.mReconciledPackages.values()) {
            final ScanResult scanResult = reconciledPkg.mScanResult;
            final ScanRequest scanRequest = scanResult.mRequest;
            final ParsedPackage parsedPackage = scanRequest.mParsedPackage;
            final String packageName = parsedPackage.getPackageName();
            final PackageInstalledInfo res = reconciledPkg.mInstallResult;
            final RemovePackageHelper removePackageHelper = new RemovePackageHelper(mPm);
            final DeletePackageHelper deletePackageHelper = new DeletePackageHelper(mPm);

            if (reconciledPkg.mPrepareResult.mReplace) {
                AndroidPackage oldPackage = mPm.mPackages.get(packageName);

                // Set the update and install times
                PackageStateInternal deletedPkgSetting = mPm.getPackageStateInternal(
                        oldPackage.getPackageName());
                reconciledPkg.mPkgSetting
                        .setFirstInstallTime(deletedPkgSetting.getFirstInstallTime())
                        .setLastUpdateTime(System.currentTimeMillis());

                res.mRemovedInfo.mBroadcastAllowList = mPm.mAppsFilter.getVisibilityAllowList(
                        reconciledPkg.mPkgSetting, request.mAllUsers,
                        mPm.mSettings.getPackagesLocked());
                if (reconciledPkg.mPrepareResult.mSystem) {
                    // Remove existing system package
                    removePackageHelper.removePackageLI(oldPackage, true);
                    if (!disableSystemPackageLPw(oldPackage)) {
                        // We didn't need to disable the .apk as a current system package,
                        // which means we are replacing another update that is already
                        // installed.  We need to make sure to delete the older one's .apk.
                        res.mRemovedInfo.mArgs = new FileInstallArgs(
                                oldPackage.getPath(),
                                getAppDexInstructionSets(
                                        AndroidPackageUtils.getPrimaryCpuAbi(oldPackage,
                                                deletedPkgSetting),
                                        AndroidPackageUtils.getSecondaryCpuAbi(oldPackage,
                                                deletedPkgSetting)), mPm);
                    } else {
                        res.mRemovedInfo.mArgs = null;
                    }
                } else {
                    try {
                        // Settings will be written during the call to updateSettingsLI().
                        deletePackageHelper.executeDeletePackageLIF(
                                reconciledPkg.mDeletePackageAction, packageName,
                                true, request.mAllUsers, false);
                    } catch (SystemDeleteException e) {
                        if (mPm.mIsEngBuild) {
                            throw new RuntimeException("Unexpected failure", e);
                            // ignore; not possible for non-system app
                        }
                    }
                    // Successfully deleted the old package; proceed with replace.

                    // If deleted package lived in a container, give users a chance to
                    // relinquish resources before killing.
                    if (oldPackage.isExternalStorage()) {
                        if (DEBUG_INSTALL) {
                            Slog.i(TAG, "upgrading pkg " + oldPackage
                                    + " is ASEC-hosted -> UNAVAILABLE");
                        }
                        final int[] uidArray = new int[]{oldPackage.getUid()};
                        final ArrayList<String> pkgList = new ArrayList<>(1);
                        pkgList.add(oldPackage.getPackageName());
                        mBroadcastHelper.sendResourcesChangedBroadcast(
                                false, true, pkgList, uidArray, null);
                    }

                    // Update the in-memory copy of the previous code paths.
                    PackageSetting ps1 = mPm.mSettings.getPackageLPr(
                            reconciledPkg.mPrepareResult.mExistingPackage.getPackageName());
                    if ((reconciledPkg.mInstallArgs.mInstallFlags & PackageManager.DONT_KILL_APP)
                            == 0) {
                        if (ps1.mOldCodePaths == null) {
                            ps1.mOldCodePaths = new ArraySet<>();
                        }
                        Collections.addAll(ps1.mOldCodePaths, oldPackage.getBaseApkPath());
                        if (oldPackage.getSplitCodePaths() != null) {
                            Collections.addAll(ps1.mOldCodePaths, oldPackage.getSplitCodePaths());
                        }
                    } else {
                        ps1.mOldCodePaths = null;
                    }

                    if (reconciledPkg.mInstallResult.mReturnCode
                            == PackageManager.INSTALL_SUCCEEDED) {
                        PackageSetting ps2 = mPm.mSettings.getPackageLPr(
                                parsedPackage.getPackageName());
                        if (ps2 != null) {
                            res.mRemovedInfo.mRemovedForAllUsers =
                                    mPm.mPackages.get(ps2.getPackageName()) == null;
                        }
                    }
                }
            }

            AndroidPackage pkg = commitReconciledScanResultLocked(
                    reconciledPkg, request.mAllUsers);
            updateSettingsLI(pkg, reconciledPkg, request.mAllUsers, res);

            final PackageSetting ps = mPm.mSettings.getPackageLPr(packageName);
            if (ps != null) {
                res.mNewUsers = ps.queryInstalledUsers(mPm.mUserManager.getUserIds(), true);
                ps.setUpdateAvailable(false /*updateAvailable*/);
            }
            if (res.mReturnCode == PackageManager.INSTALL_SUCCEEDED) {
                mPm.updateSequenceNumberLP(ps, res.mNewUsers);
                mPm.updateInstantAppInstallerLocked(packageName);
            }
        }
        ApplicationPackageManager.invalidateGetPackagesForUidCache();
    }

    @GuardedBy("mLock")
    private boolean disableSystemPackageLPw(AndroidPackage oldPkg) {
        return mPm.mSettings.disableSystemPackageLPw(oldPkg.getPackageName(), true);
    }

    private void updateSettingsLI(AndroidPackage newPackage, ReconciledPackage reconciledPkg,
            int[] allUsers, PackageInstalledInfo res) {
        updateSettingsInternalLI(newPackage, reconciledPkg, allUsers, res);
    }

    private void updateSettingsInternalLI(AndroidPackage pkg, ReconciledPackage reconciledPkg,
            int[] allUsers, PackageInstalledInfo res) {
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "updateSettings");

        final String pkgName = pkg.getPackageName();
        final int[] installedForUsers = res.mOrigUsers;
        final InstallArgs installArgs = reconciledPkg.mInstallArgs;
        final int installReason = installArgs.mInstallReason;
        InstallSource installSource = installArgs.mInstallSource;
        final String installerPackageName = installSource.installerPackageName;

        if (DEBUG_INSTALL) Slog.d(TAG, "New package installed in " + pkg.getPath());
        synchronized (mPm.mLock) {
            // For system-bundled packages, we assume that installing an upgraded version
            // of the package implies that the user actually wants to run that new code,
            // so we enable the package.
            final PackageSetting ps = mPm.mSettings.getPackageLPr(pkgName);
            final int userId = installArgs.mUser.getIdentifier();
            if (ps != null) {
                if (pkg.isSystem()) {
                    if (DEBUG_INSTALL) {
                        Slog.d(TAG, "Implicitly enabling system package on upgrade: " + pkgName);
                    }
                    // Enable system package for requested users
                    if (res.mOrigUsers != null) {
                        for (int origUserId : res.mOrigUsers) {
                            if (userId == UserHandle.USER_ALL || userId == origUserId) {
                                ps.setEnabled(COMPONENT_ENABLED_STATE_DEFAULT,
                                        origUserId, installerPackageName);
                            }
                        }
                    }
                    // Also convey the prior install/uninstall state
                    if (allUsers != null && installedForUsers != null) {
                        for (int currentUserId : allUsers) {
                            final boolean installed = ArrayUtils.contains(
                                    installedForUsers, currentUserId);
                            if (DEBUG_INSTALL) {
                                Slog.d(TAG, "    user " + currentUserId + " => " + installed);
                            }
                            ps.setInstalled(installed, currentUserId);
                        }
                        // these install state changes will be persisted in the
                        // upcoming call to mSettings.writeLPr().
                    }

                    if (allUsers != null) {
                        for (int currentUserId : allUsers) {
                            ps.resetOverrideComponentLabelIcon(currentUserId);
                        }
                    }
                }

                // Retrieve the overlays for shared libraries of the package.
                if (!ps.getPkgState().getUsesLibraryInfos().isEmpty()) {
                    for (SharedLibraryInfo sharedLib : ps.getPkgState().getUsesLibraryInfos()) {
                        for (int currentUserId : UserManagerService.getInstance().getUserIds()) {
                            if (!sharedLib.isDynamic()) {
                                // TODO(146804378): Support overlaying static shared libraries
                                continue;
                            }
                            final PackageSetting libPs = mPm.mSettings.getPackageLPr(
                                    sharedLib.getPackageName());
                            if (libPs == null) {
                                continue;
                            }
                            ps.setOverlayPathsForLibrary(sharedLib.getName(),
                                    libPs.getOverlayPaths(currentUserId), currentUserId);
                        }
                    }
                }

                // It's implied that when a user requests installation, they want the app to be
                // installed and enabled. (This does not apply to USER_ALL, which here means only
                // install on users for which the app is already installed).
                if (userId != UserHandle.USER_ALL) {
                    ps.setInstalled(true, userId);
                    ps.setEnabled(COMPONENT_ENABLED_STATE_DEFAULT, userId, installerPackageName);
                }

                mPm.mSettings.addInstallerPackageNames(ps.getInstallSource());

                // When replacing an existing package, preserve the original install reason for all
                // users that had the package installed before. Similarly for uninstall reasons.
                final Set<Integer> previousUserIds = new ArraySet<>();
                if (res.mRemovedInfo != null && res.mRemovedInfo.mInstallReasons != null) {
                    final int installReasonCount = res.mRemovedInfo.mInstallReasons.size();
                    for (int i = 0; i < installReasonCount; i++) {
                        final int previousUserId = res.mRemovedInfo.mInstallReasons.keyAt(i);
                        final int previousInstallReason =
                                res.mRemovedInfo.mInstallReasons.valueAt(i);
                        ps.setInstallReason(previousInstallReason, previousUserId);
                        previousUserIds.add(previousUserId);
                    }
                }
                if (res.mRemovedInfo != null && res.mRemovedInfo.mUninstallReasons != null) {
                    for (int i = 0; i < res.mRemovedInfo.mUninstallReasons.size(); i++) {
                        final int previousUserId = res.mRemovedInfo.mUninstallReasons.keyAt(i);
                        final int previousReason = res.mRemovedInfo.mUninstallReasons.valueAt(i);
                        ps.setUninstallReason(previousReason, previousUserId);
                    }
                }

                // Set install reason for users that are having the package newly installed.
                final int[] allUsersList = mPm.mUserManager.getUserIds();
                if (userId == UserHandle.USER_ALL) {
                    // TODO(b/152629990): It appears that the package doesn't actually get newly
                    //  installed in this case, so the installReason shouldn't get modified?
                    for (int currentUserId : allUsersList) {
                        if (!previousUserIds.contains(currentUserId)) {
                            ps.setInstallReason(installReason, currentUserId);
                        }
                    }
                } else if (!previousUserIds.contains(userId)) {
                    ps.setInstallReason(installReason, userId);
                }

                // TODO(b/169721400): generalize Incremental States and create a Callback object
                // that can be used for all the packages.
                final String codePath = ps.getPathString();
                if (IncrementalManager.isIncrementalPath(codePath)
                        && mPm.mIncrementalManager != null) {
                    mPm.mIncrementalManager.registerLoadingProgressCallback(codePath,
                            new IncrementalProgressListener(ps.getPackageName(), mPm));
                }

                // Ensure that the uninstall reason is UNKNOWN for users with the package installed.
                for (int currentUserId : allUsersList) {
                    if (ps.getInstalled(currentUserId)) {
                        ps.setUninstallReason(UNINSTALL_REASON_UNKNOWN, currentUserId);
                    }
                }

                mPm.mSettings.writeKernelMappingLPr(ps);

                final PermissionManagerServiceInternal.PackageInstalledParams.Builder
                        permissionParamsBuilder =
                        new PermissionManagerServiceInternal.PackageInstalledParams.Builder();
                final boolean grantPermissions = (installArgs.mInstallFlags
                        & PackageManager.INSTALL_GRANT_RUNTIME_PERMISSIONS) != 0;
                if (grantPermissions) {
                    final List<String> grantedPermissions =
                            installArgs.mInstallGrantPermissions != null
                                    ? Arrays.asList(installArgs.mInstallGrantPermissions)
                                    : pkg.getRequestedPermissions();
                    permissionParamsBuilder.setGrantedPermissions(grantedPermissions);
                }
                final boolean allowlistAllRestrictedPermissions =
                        (installArgs.mInstallFlags
                                & PackageManager.INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS) != 0;
                final List<String> allowlistedRestrictedPermissions =
                        allowlistAllRestrictedPermissions ? pkg.getRequestedPermissions()
                                : installArgs.mAllowlistedRestrictedPermissions;
                if (allowlistedRestrictedPermissions != null) {
                    permissionParamsBuilder.setAllowlistedRestrictedPermissions(
                            allowlistedRestrictedPermissions);
                }
                final int autoRevokePermissionsMode = installArgs.mAutoRevokePermissionsMode;
                permissionParamsBuilder.setAutoRevokePermissionsMode(autoRevokePermissionsMode);
                final ScanResult scanResult = reconciledPkg.mScanResult;
                mPm.mPermissionManager.onPackageInstalled(pkg, scanResult.mPreviousAppId,
                        permissionParamsBuilder.build(), userId);
            }
            res.mName = pkgName;
            res.mUid = pkg.getUid();
            res.mPkg = pkg;
            res.setReturnCode(PackageManager.INSTALL_SUCCEEDED);
            //to update install status
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "writeSettings");
            mPm.writeSettingsLPrTEMP();
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }

        Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
    }

    /**
     * On successful install, executes remaining steps after commit completes and the package lock
     * is released. These are typically more expensive or require calls to installd, which often
     * locks on {@link com.android.server.pm.PackageManagerService.mLock}.
     */
    private void executePostCommitSteps(CommitRequest commitRequest) {
        final ArraySet<IncrementalStorage> incrementalStorages = new ArraySet<>();
        final AppDataHelper appDataHelper = new AppDataHelper(mPm);
        for (ReconciledPackage reconciledPkg : commitRequest.mReconciledPackages.values()) {
            final boolean instantApp = ((reconciledPkg.mScanResult.mRequest.mScanFlags
                    & SCAN_AS_INSTANT_APP) != 0);
            final AndroidPackage pkg = reconciledPkg.mPkgSetting.getPkg();
            final String packageName = pkg.getPackageName();
            final String codePath = pkg.getPath();
            final boolean onIncremental = mPm.mIncrementalManager != null
                    && isIncrementalPath(codePath);
            if (onIncremental) {
                IncrementalStorage storage = mPm.mIncrementalManager.openStorage(codePath);
                if (storage == null) {
                    throw new IllegalArgumentException(
                            "Install: null storage for incremental package " + packageName);
                }
                incrementalStorages.add(storage);
            }
            int previousAppId = 0;
            if (reconciledPkg.mScanResult.needsNewAppId()) {
                // Only set previousAppId if the app is migrating out of shared UID
                previousAppId = reconciledPkg.mScanResult.mPreviousAppId;
            }
            appDataHelper.prepareAppDataPostCommitLIF(pkg, previousAppId);
            if (reconciledPkg.mPrepareResult.mClearCodeCache) {
                appDataHelper.clearAppDataLIF(pkg, UserHandle.USER_ALL,
                        FLAG_STORAGE_DE | FLAG_STORAGE_CE | FLAG_STORAGE_EXTERNAL
                                | Installer.FLAG_CLEAR_CODE_CACHE_ONLY);
            }
            if (reconciledPkg.mPrepareResult.mReplace) {
                mPm.getDexManager().notifyPackageUpdated(pkg.getPackageName(),
                        pkg.getBaseApkPath(), pkg.getSplitCodePaths());
            }

            // Prepare the application profiles for the new code paths.
            // This needs to be done before invoking dexopt so that any install-time profile
            // can be used for optimizations.
            mPm.mArtManagerService.prepareAppProfiles(
                    pkg,
                    mPm.resolveUserIds(reconciledPkg.mInstallArgs.mUser.getIdentifier()),
                    /* updateReferenceProfileContent= */ true);

            // Compute the compilation reason from the installation scenario.
            final int compilationReason =
                    mPm.getDexManager().getCompilationReasonForInstallScenario(
                            reconciledPkg.mInstallArgs.mInstallScenario);

            // Construct the DexoptOptions early to see if we should skip running dexopt.
            //
            // Do not run PackageDexOptimizer through the local performDexOpt
            // method because `pkg` may not be in `mPackages` yet.
            //
            // Also, don't fail application installs if the dexopt step fails.
            final boolean isBackupOrRestore =
                    reconciledPkg.mInstallArgs.mInstallReason == INSTALL_REASON_DEVICE_RESTORE
                            || reconciledPkg.mInstallArgs.mInstallReason
                            == INSTALL_REASON_DEVICE_SETUP;

            final int dexoptFlags = DexoptOptions.DEXOPT_BOOT_COMPLETE
                    | DexoptOptions.DEXOPT_INSTALL_WITH_DEX_METADATA_FILE
                    | (isBackupOrRestore ? DexoptOptions.DEXOPT_FOR_RESTORE : 0);
            DexoptOptions dexoptOptions =
                    new DexoptOptions(packageName, compilationReason, dexoptFlags);

            // Check whether we need to dexopt the app.
            //
            // NOTE: it is IMPORTANT to call dexopt:
            //   - after doRename which will sync the package data from AndroidPackage and
            //     its corresponding ApplicationInfo.
            //   - after installNewPackageLIF or replacePackageLIF which will update result with the
            //     uid of the application (pkg.applicationInfo.uid).
            //     This update happens in place!
            //
            // We only need to dexopt if the package meets ALL of the following conditions:
            //   1) it is not an instant app or if it is then dexopt is enabled via gservices.
            //   2) it is not debuggable.
            //   3) it is not on Incremental File System.
            //
            // Note that we do not dexopt instant apps by default. dexopt can take some time to
            // complete, so we skip this step during installation. Instead, we'll take extra time
            // the first time the instant app starts. It's preferred to do it this way to provide
            // continuous progress to the useur instead of mysteriously blocking somewhere in the
            // middle of running an instant app. The default behaviour can be overridden
            // via gservices.
            //
            // Furthermore, dexopt may be skipped, depending on the install scenario and current
            // state of the device.
            //
            // TODO(b/174695087): instantApp and onIncremental should be removed and their install
            //       path moved to SCENARIO_FAST.
            final boolean performDexopt =
                    (!instantApp || android.provider.Settings.Global.getInt(
                            mPm.mContext.getContentResolver(),
                            android.provider.Settings.Global.INSTANT_APP_DEXOPT_ENABLED, 0) != 0)
                            && !pkg.isDebuggable()
                            && (!onIncremental)
                            && dexoptOptions.isCompilationEnabled();

            if (performDexopt) {
                // Compile the layout resources.
                if (SystemProperties.getBoolean(PRECOMPILE_LAYOUTS, false)) {
                    Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "compileLayouts");
                    mPm.mViewCompiler.compileLayouts(pkg);
                    Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
                }

                Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "dexopt");
                ScanResult result = reconciledPkg.mScanResult;

                // This mirrors logic from commitReconciledScanResultLocked, where the library files
                // needed for dexopt are assigned.
                // TODO: Fix this to have 1 mutable PackageSetting for scan/install. If the previous
                //  setting needs to be passed to have a comparison, hide it behind an immutable
                //  interface. There's no good reason to have 3 different ways to access the real
                //  PackageSetting object, only one of which is actually correct.
                PackageSetting realPkgSetting = result.mExistingSettingCopied
                        ? result.mRequest.mPkgSetting : result.mPkgSetting;
                if (realPkgSetting == null) {
                    realPkgSetting = reconciledPkg.mPkgSetting;
                }

                // Unfortunately, the updated system app flag is only tracked on this PackageSetting
                boolean isUpdatedSystemApp = reconciledPkg.mPkgSetting.getPkgState()
                        .isUpdatedSystemApp();

                realPkgSetting.getPkgState().setUpdatedSystemApp(isUpdatedSystemApp);

                mPm.mPackageDexOptimizer.performDexOpt(pkg, realPkgSetting,
                        null /* instructionSets */,
                        mPm.getOrCreateCompilerPackageStats(pkg),
                        mPm.getDexManager().getPackageUseInfoOrDefault(packageName),
                        dexoptOptions);
                Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
            }

            // Notify BackgroundDexOptService that the package has been changed.
            // If this is an update of a package which used to fail to compile,
            // BackgroundDexOptService will remove it from its denylist.
            // TODO: Layering violation
            BackgroundDexOptService.getService().notifyPackageChanged(packageName);

            notifyPackageChangeObserversOnUpdate(reconciledPkg);
        }
        waitForNativeBinariesExtraction(incrementalStorages);
    }

    private void notifyPackageChangeObserversOnUpdate(ReconciledPackage reconciledPkg) {
        final PackageSetting pkgSetting = reconciledPkg.mPkgSetting;
        final PackageInstalledInfo pkgInstalledInfo = reconciledPkg.mInstallResult;
        final PackageRemovedInfo pkgRemovedInfo = pkgInstalledInfo.mRemovedInfo;

        PackageChangeEvent pkgChangeEvent = new PackageChangeEvent();
        pkgChangeEvent.packageName = pkgSetting.getPkg().getPackageName();
        pkgChangeEvent.version = pkgSetting.getVersionCode();
        pkgChangeEvent.lastUpdateTimeMillis = pkgSetting.getLastUpdateTime();
        pkgChangeEvent.newInstalled = (pkgRemovedInfo == null || !pkgRemovedInfo.mIsUpdate);
        pkgChangeEvent.dataRemoved = (pkgRemovedInfo != null && pkgRemovedInfo.mDataRemoved);
        pkgChangeEvent.isDeleted = false;

        mPm.notifyPackageChangeObservers(pkgChangeEvent);
    }

    private static void waitForNativeBinariesExtraction(
            ArraySet<IncrementalStorage> incrementalStorages) {
        if (incrementalStorages.isEmpty()) {
            return;
        }
        try {
            // Native library extraction may take very long time: each page could potentially
            // wait for either 10s or 100ms (adb vs non-adb data loader), and that easily adds
            // up to a full watchdog timeout of 1 min, killing the system after that. It doesn't
            // make much sense as blocking here doesn't lock up the framework, but only blocks
            // the installation session and the following ones.
            Watchdog.getInstance().pauseWatchingCurrentThread("native_lib_extract");
            for (int i = 0; i < incrementalStorages.size(); ++i) {
                IncrementalStorage storage = incrementalStorages.valueAtUnchecked(i);
                storage.waitForNativeBinariesExtraction();
            }
        } finally {
            Watchdog.getInstance().resumeWatchingCurrentThread("native_lib_extract");
        }
    }

    public int installLocationPolicy(PackageInfoLite pkgLite, int installFlags) {
        String packageName = pkgLite.packageName;
        int installLocation = pkgLite.installLocation;
        // reader
        synchronized (mPm.mLock) {
            // Currently installed package which the new package is attempting to replace or
            // null if no such package is installed.
            AndroidPackage installedPkg = mPm.mPackages.get(packageName);

            if (installedPkg != null) {
                if ((installFlags & PackageManager.INSTALL_REPLACE_EXISTING) != 0) {
                    // Check for updated system application.
                    if (installedPkg.isSystem()) {
                        return PackageHelper.RECOMMEND_INSTALL_INTERNAL;
                    } else {
                        // If current upgrade specifies particular preference
                        if (installLocation == PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY) {
                            // Application explicitly specified internal.
                            return PackageHelper.RECOMMEND_INSTALL_INTERNAL;
                        } else if (
                                installLocation == PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL) {
                            // App explicitly prefers external. Let policy decide
                        } else {
                            // Prefer previous location
                            if (installedPkg.isExternalStorage()) {
                                return PackageHelper.RECOMMEND_INSTALL_EXTERNAL;
                            }
                            return PackageHelper.RECOMMEND_INSTALL_INTERNAL;
                        }
                    }
                } else {
                    // Invalid install. Return error code
                    return PackageHelper.RECOMMEND_FAILED_ALREADY_EXISTS;
                }
            }
        }
        return pkgLite.recommendedInstallLocation;
    }

    Pair<Integer, String> verifyReplacingVersionCode(PackageInfoLite pkgLite,
            long requiredInstalledVersionCode, int installFlags) {
        if ((installFlags & PackageManager.INSTALL_APEX) != 0) {
            return verifyReplacingVersionCodeForApex(
                    pkgLite, requiredInstalledVersionCode, installFlags);
        }

        String packageName = pkgLite.packageName;
        synchronized (mPm.mLock) {
            // Package which currently owns the data that the new package will own if installed.
            // If an app is uninstalled while keeping data (e.g. adb uninstall -k), installedPkg
            // will be null whereas dataOwnerPkg will contain information about the package
            // which was uninstalled while keeping its data.
            AndroidPackage dataOwnerPkg = mPm.mPackages.get(packageName);
            if (dataOwnerPkg  == null) {
                PackageSetting ps = mPm.mSettings.getPackageLPr(packageName);
                if (ps != null) {
                    dataOwnerPkg = ps.getPkg();
                }
            }

            if (requiredInstalledVersionCode != PackageManager.VERSION_CODE_HIGHEST) {
                if (dataOwnerPkg == null) {
                    String errorMsg = "Required installed version code was "
                            + requiredInstalledVersionCode
                            + " but package is not installed";
                    Slog.w(TAG, errorMsg);
                    return Pair.create(
                            PackageManager.INSTALL_FAILED_WRONG_INSTALLED_VERSION, errorMsg);
                }

                if (dataOwnerPkg.getLongVersionCode() != requiredInstalledVersionCode) {
                    String errorMsg = "Required installed version code was "
                            + requiredInstalledVersionCode
                            + " but actual installed version is "
                            + dataOwnerPkg.getLongVersionCode();
                    Slog.w(TAG, errorMsg);
                    return Pair.create(
                            PackageManager.INSTALL_FAILED_WRONG_INSTALLED_VERSION, errorMsg);
                }
            }

            if (dataOwnerPkg != null) {
                if (!PackageManagerServiceUtils.isDowngradePermitted(installFlags,
                        dataOwnerPkg.isDebuggable())) {
                    try {
                        checkDowngrade(dataOwnerPkg, pkgLite);
                    } catch (PackageManagerException e) {
                        String errorMsg = "Downgrade detected: " + e.getMessage();
                        Slog.w(TAG, errorMsg);
                        return Pair.create(
                                PackageManager.INSTALL_FAILED_VERSION_DOWNGRADE, errorMsg);
                    }
                }
            }
        }
        return Pair.create(PackageManager.INSTALL_SUCCEEDED, null);
    }

    private Pair<Integer, String> verifyReplacingVersionCodeForApex(PackageInfoLite pkgLite,
            long requiredInstalledVersionCode, int installFlags) {
        String packageName = pkgLite.packageName;

        final PackageInfo activePackage = mPm.mApexManager.getPackageInfo(packageName,
                ApexManager.MATCH_ACTIVE_PACKAGE);
        if (activePackage == null) {
            String errorMsg = "Attempting to install new APEX package " + packageName;
            Slog.w(TAG, errorMsg);
            return Pair.create(PackageManager.INSTALL_FAILED_PACKAGE_CHANGED, errorMsg);
        }

        final long activeVersion = activePackage.getLongVersionCode();
        if (requiredInstalledVersionCode != PackageManager.VERSION_CODE_HIGHEST
                && activeVersion != requiredInstalledVersionCode) {
            String errorMsg = "Installed version of APEX package " + packageName
                    + " does not match required. Active version: " + activeVersion
                    + " required: " + requiredInstalledVersionCode;
            Slog.w(TAG, errorMsg);
            return Pair.create(PackageManager.INSTALL_FAILED_WRONG_INSTALLED_VERSION, errorMsg);
        }

        final boolean isAppDebuggable = (activePackage.applicationInfo.flags
                & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        final long newVersionCode = pkgLite.getLongVersionCode();
        if (!PackageManagerServiceUtils.isDowngradePermitted(installFlags, isAppDebuggable)
                && newVersionCode < activeVersion) {
            String errorMsg = "Downgrade of APEX package " + packageName
                    + " is not allowed. Active version: " + activeVersion
                    + " attempted: " + newVersionCode;
            Slog.w(TAG, errorMsg);
            return Pair.create(PackageManager.INSTALL_FAILED_VERSION_DOWNGRADE, errorMsg);
        }

        return Pair.create(PackageManager.INSTALL_SUCCEEDED, null);
    }

    /**
     * Check and throw if the given before/after packages would be considered a
     * downgrade.
     */
    private static void checkDowngrade(AndroidPackage before, PackageInfoLite after)
            throws PackageManagerException {
        if (after.getLongVersionCode() < before.getLongVersionCode()) {
            throw new PackageManagerException(INSTALL_FAILED_VERSION_DOWNGRADE,
                    "Update version code " + after.versionCode + " is older than current "
                            + before.getLongVersionCode());
        } else if (after.getLongVersionCode() == before.getLongVersionCode()) {
            if (after.baseRevisionCode < before.getBaseRevisionCode()) {
                throw new PackageManagerException(INSTALL_FAILED_VERSION_DOWNGRADE,
                        "Update base revision code " + after.baseRevisionCode
                                + " is older than current " + before.getBaseRevisionCode());
            }

            if (!ArrayUtils.isEmpty(after.splitNames)) {
                for (int i = 0; i < after.splitNames.length; i++) {
                    final String splitName = after.splitNames[i];
                    final int j = ArrayUtils.indexOf(before.getSplitNames(), splitName);
                    if (j != -1) {
                        if (after.splitRevisionCodes[i] < before.getSplitRevisionCodes()[j]) {
                            throw new PackageManagerException(INSTALL_FAILED_VERSION_DOWNGRADE,
                                    "Update split " + splitName + " revision code "
                                            + after.splitRevisionCodes[i]
                                            + " is older than current "
                                            + before.getSplitRevisionCodes()[j]);
                        }
                    }
                }
            }
        }
    }

    int getUidForVerifier(VerifierInfo verifierInfo) {
        synchronized (mPm.mLock) {
            final AndroidPackage pkg = mPm.mPackages.get(verifierInfo.packageName);
            if (pkg == null) {
                return -1;
            } else if (pkg.getSigningDetails().getSignatures().length != 1) {
                Slog.i(TAG, "Verifier package " + verifierInfo.packageName
                        + " has more than one signature; ignoring");
                return -1;
            }

            /*
             * If the public key of the package's signature does not match
             * our expected public key, then this is a different package and
             * we should skip.
             */

            final byte[] expectedPublicKey;
            try {
                final Signature verifierSig = pkg.getSigningDetails().getSignatures()[0];
                final PublicKey publicKey = verifierSig.getPublicKey();
                expectedPublicKey = publicKey.getEncoded();
            } catch (CertificateException e) {
                return -1;
            }

            final byte[] actualPublicKey = verifierInfo.publicKey.getEncoded();

            if (!Arrays.equals(actualPublicKey, expectedPublicKey)) {
                Slog.i(TAG, "Verifier package " + verifierInfo.packageName
                        + " does not have the expected public key; ignoring");
                return -1;
            }

            return pkg.getUid();
        }
    }

    /**
     * Check whether or not package verification has been enabled.
     *
     * @return true if verification should be performed
     */
    boolean isVerificationEnabled(PackageInfoLite pkgInfoLite, int userId, int installFlags,
            int installerUid) {
        if (!DEFAULT_VERIFY_ENABLE) {
            return false;
        }

        // Check if installing from ADB
        if ((installFlags & PackageManager.INSTALL_FROM_ADB) != 0) {
            if (mPm.isUserRestricted(userId, UserManager.ENSURE_VERIFY_APPS)) {
                return true;
            }
            // Check if the developer wants to skip verification for ADB installs
            if ((installFlags & PackageManager.INSTALL_DISABLE_VERIFICATION) != 0) {
                synchronized (mPm.mLock) {
                    if (mPm.mSettings.getPackageLPr(pkgInfoLite.packageName) == null) {
                        // Always verify fresh install
                        return true;
                    }
                }
                // Only skip when apk is debuggable
                return !pkgInfoLite.debuggable;
            }
            return android.provider.Settings.Global.getInt(mPm.mContext.getContentResolver(),
                    android.provider.Settings.Global.PACKAGE_VERIFIER_INCLUDE_ADB, 1) != 0;
        }

        // only when not installed from ADB, skip verification for instant apps when
        // the installer and verifier are the same.
        if ((installFlags & PackageManager.INSTALL_INSTANT_APP) != 0) {
            if (mPm.mInstantAppInstallerActivity != null
                    && mPm.mInstantAppInstallerActivity.packageName.equals(
                    mPm.mRequiredVerifierPackage)) {
                try {
                    mPm.mInjector.getSystemService(AppOpsManager.class)
                            .checkPackage(installerUid, mPm.mRequiredVerifierPackage);
                    if (DEBUG_VERIFY) {
                        Slog.i(TAG, "disable verification for instant app");
                    }
                    return false;
                } catch (SecurityException ignore) { }
            }
        }
        return true;
    }

    public void sendPendingBroadcasts() {
        String[] packages;
        ArrayList<String>[] components;
        int size = 0;
        int[] uids;
        Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);

        synchronized (mPm.mLock) {
            size = mPm.mPendingBroadcasts.size();
            if (size <= 0) {
                // Nothing to be done. Just return
                return;
            }
            packages = new String[size];
            components = new ArrayList[size];
            uids = new int[size];
            int i = 0;  // filling out the above arrays

            for (int n = 0; n < mPm.mPendingBroadcasts.userIdCount(); n++) {
                final int packageUserId = mPm.mPendingBroadcasts.userIdAt(n);
                final ArrayMap<String, ArrayList<String>> componentsToBroadcast =
                        mPm.mPendingBroadcasts.packagesForUserId(packageUserId);
                final int numComponents = componentsToBroadcast.size();
                for (int index = 0; i < size && index < numComponents; index++) {
                    packages[i] = componentsToBroadcast.keyAt(index);
                    components[i] = componentsToBroadcast.valueAt(index);
                    final PackageSetting ps = mPm.mSettings.getPackageLPr(packages[i]);
                    uids[i] = (ps != null)
                            ? UserHandle.getUid(packageUserId, ps.getAppId())
                            : -1;
                    i++;
                }
            }
            size = i;
            mPm.mPendingBroadcasts.clear();
        }
        // Send broadcasts
        for (int i = 0; i < size; i++) {
            mPm.sendPackageChangedBroadcast(packages[i], true /* dontKillApp */,
                    components[i], uids[i], null /* reason */);
        }
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
    }

    void handlePackagePostInstall(PackageInstalledInfo res, InstallArgs installArgs,
            boolean launchedForRestore) {
        final boolean killApp =
                (installArgs.mInstallFlags & PackageManager.INSTALL_DONT_KILL_APP) == 0;
        final boolean virtualPreload =
                ((installArgs.mInstallFlags & PackageManager.INSTALL_VIRTUAL_PRELOAD) != 0);
        final String installerPackage = installArgs.mInstallSource.installerPackageName;
        final IPackageInstallObserver2 installObserver = installArgs.mObserver;
        final int dataLoaderType = installArgs.mDataLoaderType;
        final boolean succeeded = res.mReturnCode == PackageManager.INSTALL_SUCCEEDED;
        final boolean update = res.mRemovedInfo != null && res.mRemovedInfo.mRemovedPackage != null;
        final String packageName = res.mName;
        final PackageStateInternal pkgSetting =
                succeeded ? mPm.getPackageStateInternal(packageName) : null;
        final boolean removedBeforeUpdate = (pkgSetting == null)
                || (pkgSetting.isSystem() && !pkgSetting.getPath().getPath().equals(
                res.mPkg.getPath()));
        if (succeeded && removedBeforeUpdate) {
            Slog.e(TAG, packageName + " was removed before handlePackagePostInstall "
                    + "could be executed");
            res.mReturnCode = INSTALL_FAILED_PACKAGE_CHANGED;
            res.mReturnMsg = "Package was removed before install could complete.";

            // Remove the update failed package's older resources safely now
            InstallArgs args = res.mRemovedInfo != null ? res.mRemovedInfo.mArgs : null;
            if (args != null) {
                synchronized (mPm.mInstallLock) {
                    args.doPostDeleteLI(true);
                }
            }
            mPm.notifyInstallObserver(res, installObserver);
            return;
        }

        if (succeeded) {
            // Clear the uid cache after we installed a new package.
            mPm.mPerUidReadTimeoutsCache = null;

            // Send the removed broadcasts
            if (res.mRemovedInfo != null) {
                res.mRemovedInfo.sendPackageRemovedBroadcasts(killApp, false /*removedBySystem*/);
            }

            final String installerPackageName =
                    res.mInstallerPackageName != null
                            ? res.mInstallerPackageName
                            : res.mRemovedInfo != null
                                    ? res.mRemovedInfo.mInstallerPackageName
                                    : null;

            synchronized (mPm.mLock) {
                mPm.mInstantAppRegistry.onPackageInstalledLPw(res.mPkg, res.mNewUsers);
            }

            // Determine the set of users who are adding this package for
            // the first time vs. those who are seeing an update.
            int[] firstUserIds = EMPTY_INT_ARRAY;
            int[] firstInstantUserIds = EMPTY_INT_ARRAY;
            int[] updateUserIds = EMPTY_INT_ARRAY;
            int[] instantUserIds = EMPTY_INT_ARRAY;
            final boolean allNewUsers = res.mOrigUsers == null || res.mOrigUsers.length == 0;
            for (int newUser : res.mNewUsers) {
                final boolean isInstantApp = pkgSetting.getUserStateOrDefault(newUser)
                        .isInstantApp();
                if (allNewUsers) {
                    if (isInstantApp) {
                        firstInstantUserIds = ArrayUtils.appendInt(firstInstantUserIds, newUser);
                    } else {
                        firstUserIds = ArrayUtils.appendInt(firstUserIds, newUser);
                    }
                    continue;
                }
                boolean isNew = true;
                for (int origUser : res.mOrigUsers) {
                    if (origUser == newUser) {
                        isNew = false;
                        break;
                    }
                }
                if (isNew) {
                    if (isInstantApp) {
                        firstInstantUserIds = ArrayUtils.appendInt(firstInstantUserIds, newUser);
                    } else {
                        firstUserIds = ArrayUtils.appendInt(firstUserIds, newUser);
                    }
                } else {
                    if (isInstantApp) {
                        instantUserIds = ArrayUtils.appendInt(instantUserIds, newUser);
                    } else {
                        updateUserIds = ArrayUtils.appendInt(updateUserIds, newUser);
                    }
                }
            }

            // Send installed broadcasts if the package is not a static shared lib.
            if (res.mPkg.getStaticSharedLibName() == null) {
                mPm.mProcessLoggingHandler.invalidateBaseApkHash(res.mPkg.getBaseApkPath());

                // Send added for users that see the package for the first time
                // sendPackageAddedForNewUsers also deals with system apps
                int appId = UserHandle.getAppId(res.mUid);
                boolean isSystem = res.mPkg.isSystem();
                mPm.sendPackageAddedForNewUsers(packageName, isSystem || virtualPreload,
                        virtualPreload /*startReceiver*/, appId, firstUserIds, firstInstantUserIds,
                        dataLoaderType);

                // Send added for users that don't see the package for the first time
                Bundle extras = new Bundle(1);
                extras.putInt(Intent.EXTRA_UID, res.mUid);
                if (update) {
                    extras.putBoolean(Intent.EXTRA_REPLACING, true);
                }
                extras.putInt(PackageInstaller.EXTRA_DATA_LOADER_TYPE, dataLoaderType);
                // Send to all running apps.
                final SparseArray<int[]> newBroadcastAllowList;
                synchronized (mPm.mLock) {
                    newBroadcastAllowList = mPm.mAppsFilter.getVisibilityAllowList(
                            mPm.getPackageStateInternal(packageName, Process.SYSTEM_UID),
                            updateUserIds, mPm.mSettings.getPackagesLocked());
                }
                mPm.sendPackageBroadcast(Intent.ACTION_PACKAGE_ADDED, packageName,
                        extras, 0 /*flags*/,
                        null /*targetPackage*/, null /*finishedReceiver*/,
                        updateUserIds, instantUserIds, newBroadcastAllowList, null);
                if (installerPackageName != null) {
                    // Send to the installer, even if it's not running.
                    mPm.sendPackageBroadcast(Intent.ACTION_PACKAGE_ADDED, packageName,
                            extras, 0 /*flags*/,
                            installerPackageName, null /*finishedReceiver*/,
                            updateUserIds, instantUserIds, null /* broadcastAllowList */, null);
                }
                // if the required verifier is defined, but, is not the installer of record
                // for the package, it gets notified
                final boolean notifyVerifier = mPm.mRequiredVerifierPackage != null
                        && !mPm.mRequiredVerifierPackage.equals(installerPackageName);
                if (notifyVerifier) {
                    mPm.sendPackageBroadcast(Intent.ACTION_PACKAGE_ADDED, packageName,
                            extras, 0 /*flags*/,
                            mPm.mRequiredVerifierPackage, null /*finishedReceiver*/,
                            updateUserIds, instantUserIds, null /* broadcastAllowList */, null);
                }
                // If package installer is defined, notify package installer about new
                // app installed
                if (mPm.mRequiredInstallerPackage != null) {
                    mPm.sendPackageBroadcast(Intent.ACTION_PACKAGE_ADDED, packageName,
                            extras, Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND /*flags*/,
                            mPm.mRequiredInstallerPackage, null /*finishedReceiver*/,
                            firstUserIds, instantUserIds, null /* broadcastAllowList */, null);
                }

                // Send replaced for users that don't see the package for the first time
                if (update) {
                    mPm.sendPackageBroadcast(Intent.ACTION_PACKAGE_REPLACED,
                            packageName, extras, 0 /*flags*/,
                            null /*targetPackage*/, null /*finishedReceiver*/,
                            updateUserIds, instantUserIds, res.mRemovedInfo.mBroadcastAllowList,
                            null);
                    if (installerPackageName != null) {
                        mPm.sendPackageBroadcast(Intent.ACTION_PACKAGE_REPLACED, packageName,
                                extras, 0 /*flags*/,
                                installerPackageName, null /*finishedReceiver*/,
                                updateUserIds, instantUserIds, null /*broadcastAllowList*/, null);
                    }
                    if (notifyVerifier) {
                        mPm.sendPackageBroadcast(Intent.ACTION_PACKAGE_REPLACED, packageName,
                                extras, 0 /*flags*/,
                                mPm.mRequiredVerifierPackage, null /*finishedReceiver*/,
                                updateUserIds, instantUserIds, null /*broadcastAllowList*/, null);
                    }
                    mPm.sendPackageBroadcast(Intent.ACTION_MY_PACKAGE_REPLACED,
                            null /*package*/, null /*extras*/, 0 /*flags*/,
                            packageName /*targetPackage*/,
                            null /*finishedReceiver*/, updateUserIds, instantUserIds,
                            null /*broadcastAllowList*/,
                            mBroadcastHelper.getTemporaryAppAllowlistBroadcastOptions(
                                    REASON_PACKAGE_REPLACED).toBundle());
                } else if (launchedForRestore && !res.mPkg.isSystem()) {
                    // First-install and we did a restore, so we're responsible for the
                    // first-launch broadcast.
                    if (DEBUG_BACKUP) {
                        Slog.i(TAG, "Post-restore of " + packageName
                                + " sending FIRST_LAUNCH in " + Arrays.toString(firstUserIds));
                    }
                    mBroadcastHelper.sendFirstLaunchBroadcast(packageName, installerPackage,
                            firstUserIds, firstInstantUserIds);
                }

                // Send broadcast package appeared if external for all users
                if (res.mPkg.isExternalStorage()) {
                    if (!update) {
                        final StorageManager storage = mPm.mInjector.getSystemService(
                                StorageManager.class);
                        VolumeInfo volume =
                                storage.findVolumeByUuid(
                                        StorageManager.convert(
                                                res.mPkg.getVolumeUuid()).toString());
                        int packageExternalStorageType =
                                PackageManagerServiceUtils.getPackageExternalStorageType(volume,
                                        res.mPkg.isExternalStorage());
                        // If the package was installed externally, log it.
                        if (packageExternalStorageType != StorageEnums.UNKNOWN) {
                            FrameworkStatsLog.write(
                                    FrameworkStatsLog.APP_INSTALL_ON_EXTERNAL_STORAGE_REPORTED,
                                    packageExternalStorageType, packageName);
                        }
                    }
                    if (DEBUG_INSTALL) {
                        Slog.i(TAG, "upgrading pkg " + res.mPkg + " is external");
                    }
                    final int[] uidArray = new int[]{res.mPkg.getUid()};
                    ArrayList<String> pkgList = new ArrayList<>(1);
                    pkgList.add(packageName);
                    mBroadcastHelper.sendResourcesChangedBroadcast(
                            true, true, pkgList, uidArray, null);
                }
            } else if (!ArrayUtils.isEmpty(res.mLibraryConsumers)) { // if static shared lib
                for (int i = 0; i < res.mLibraryConsumers.size(); i++) {
                    AndroidPackage pkg = res.mLibraryConsumers.get(i);
                    // send broadcast that all consumers of the static shared library have changed
                    mPm.sendPackageChangedBroadcast(pkg.getPackageName(), false /* dontKillApp */,
                            new ArrayList<>(Collections.singletonList(pkg.getPackageName())),
                            pkg.getUid(), null);
                }
            }

            // Work that needs to happen on first install within each user
            if (firstUserIds.length > 0) {
                for (int userId : firstUserIds) {
                    mPm.restorePermissionsAndUpdateRolesForNewUserInstall(packageName,
                            userId);
                }
            }

            if (allNewUsers && !update) {
                mPm.notifyPackageAdded(packageName, res.mUid);
            } else {
                mPm.notifyPackageChanged(packageName, res.mUid);
            }

            // Log current value of "unknown sources" setting
            EventLog.writeEvent(EventLogTags.UNKNOWN_SOURCES_ENABLED,
                    getUnknownSourcesSettings());

            // Remove the replaced package's older resources safely now
            InstallArgs args = res.mRemovedInfo != null ? res.mRemovedInfo.mArgs : null;
            if (args != null) {
                if (!killApp) {
                    // If we didn't kill the app, defer the deletion of code/resource files, since
                    // they may still be in use by the running application. This mitigates problems
                    // in cases where resources or code is loaded by a new Activity before
                    // ApplicationInfo changes have propagated to all application threads.
                    mPm.scheduleDeferredNoKillPostDelete(args);
                } else {
                    synchronized (mPm.mInstallLock) {
                        args.doPostDeleteLI(true);
                    }
                }
            } else {
                // Force a gc to clear up things. Ask for a background one, it's fine to go on
                // and not block here.
                VMRuntime.getRuntime().requestConcurrentGC();
            }

            // Notify DexManager that the package was installed for new users.
            // The updated users should already be indexed and the package code paths
            // should not change.
            // Don't notify the manager for ephemeral apps as they are not expected to
            // survive long enough to benefit of background optimizations.
            for (int userId : firstUserIds) {
                PackageInfo info = mPm.getPackageInfo(packageName, /*flags*/ 0, userId);
                // There's a race currently where some install events may interleave with an
                // uninstall. This can lead to package info being null (b/36642664).
                if (info != null) {
                    mPm.getDexManager().notifyPackageInstalled(info, userId);
                }
            }
        }

        final boolean deferInstallObserver = succeeded && update && !killApp;
        if (deferInstallObserver) {
            mPm.scheduleDeferredNoKillInstallObserver(res, installObserver);
        } else {
            mPm.notifyInstallObserver(res, installObserver);
        }

        // Prune unused static shared libraries which have been cached a period of time
        mPm.schedulePruneUnusedStaticSharedLibraries(true /* delay */);

        // Log tracing if needed
        if (installArgs.mTraceMethod != null) {
            Trace.asyncTraceEnd(TRACE_TAG_PACKAGE_MANAGER, installArgs.mTraceMethod,
                    installArgs.mTraceCookie);
        }
    }

    /**
     * Get the "allow unknown sources" setting.
     *
     * @return the current "allow unknown sources" setting
     */
    private int getUnknownSourcesSettings() {
        return android.provider.Settings.Secure.getIntForUser(mPm.mContext.getContentResolver(),
                android.provider.Settings.Secure.INSTALL_NON_MARKET_APPS,
                -1, UserHandle.USER_SYSTEM);
    }

    /**
     * Uncompress and install stub applications.
     * <p>In order to save space on the system partition, some applications are shipped in a
     * compressed form. In addition the compressed bits for the full application, the
     * system image contains a tiny stub comprised of only the Android manifest.
     * <p>During the first boot, attempt to uncompress and install the full application. If
     * the application can't be installed for any reason, disable the stub and prevent
     * uncompressing the full application during future boots.
     * <p>In order to forcefully attempt an installation of a full application, go to app
     * settings and enable the application.
     */
    @GuardedBy({"mPm.mLock", "mPm.mInstallLock"})
    void installSystemStubPackages(@NonNull List<String> systemStubPackageNames,
            @PackageManagerService.ScanFlags int scanFlags) {
        for (int i = systemStubPackageNames.size() - 1; i >= 0; --i) {
            final String packageName = systemStubPackageNames.get(i);
            // skip if the system package is already disabled
            if (mPm.mSettings.isDisabledSystemPackageLPr(packageName)) {
                systemStubPackageNames.remove(i);
                continue;
            }
            // skip if the package isn't installed (?!); this should never happen
            final AndroidPackage pkg = mPm.mPackages.get(packageName);
            if (pkg == null) {
                systemStubPackageNames.remove(i);
                continue;
            }
            // skip if the package has been disabled by the user
            final PackageSetting ps = mPm.mSettings.getPackageLPr(packageName);
            if (ps != null) {
                final int enabledState = ps.getEnabled(UserHandle.USER_SYSTEM);
                if (enabledState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
                    systemStubPackageNames.remove(i);
                    continue;
                }
            }

            // install the package to replace the stub on /system
            try {
                installStubPackageLI(pkg, 0, scanFlags);
                ps.setEnabled(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                        UserHandle.USER_SYSTEM, "android");
                systemStubPackageNames.remove(i);
            } catch (PackageManagerException e) {
                Slog.e(TAG, "Failed to parse uncompressed system package: " + e.getMessage());
            }

            // any failed attempt to install the package will be cleaned up later
        }

        // disable any stub still left; these failed to install the full application
        for (int i = systemStubPackageNames.size() - 1; i >= 0; --i) {
            final String pkgName = systemStubPackageNames.get(i);
            final PackageSetting ps = mPm.mSettings.getPackageLPr(pkgName);
            ps.setEnabled(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    UserHandle.USER_SYSTEM, "android");
            logCriticalInfo(Log.ERROR, "Stub disabled; pkg: " + pkgName);
        }
    }

    /**
     * Extract, install and enable a stub package.
     * <p>If the compressed file can not be extracted / installed for any reason, the stub
     * APK will be installed and the package will be disabled. To recover from this situation,
     * the user will need to go into system settings and re-enable the package.
     */
    @GuardedBy({"mPm.mLock", "mPm.mInstallLock"})
    boolean enableCompressedPackage(AndroidPackage stubPkg,
            @NonNull PackageSetting stubPkgSetting) {
        final int parseFlags = mPm.getDefParseFlags() | ParsingPackageUtils.PARSE_CHATTY
                | ParsingPackageUtils.PARSE_ENFORCE_CODE;
        synchronized (mPm.mInstallLock) {
            final AndroidPackage pkg;
            try (PackageFreezer freezer =
                         mPm.freezePackage(stubPkg.getPackageName(), "setEnabledSetting")) {
                pkg = installStubPackageLI(stubPkg, parseFlags, 0 /*scanFlags*/);
                synchronized (mPm.mLock) {
                    mAppDataHelper.prepareAppDataAfterInstallLIF(pkg);
                    try {
                        mPm.updateSharedLibrariesLocked(pkg, stubPkgSetting, null, null,
                                Collections.unmodifiableMap(mPm.mPackages));
                    } catch (PackageManagerException e) {
                        Slog.w(TAG, "updateAllSharedLibrariesLPw failed: ", e);
                    }
                    mPm.mPermissionManager.onPackageInstalled(pkg,
                            Process.INVALID_UID /* previousAppId */,
                            PermissionManagerServiceInternal.PackageInstalledParams.DEFAULT,
                            UserHandle.USER_ALL);
                    mPm.writeSettingsLPrTEMP();
                }
            } catch (PackageManagerException e) {
                // Whoops! Something went very wrong; roll back to the stub and disable the package
                try (PackageFreezer freezer =
                             mPm.freezePackage(stubPkg.getPackageName(), "setEnabledSetting")) {
                    synchronized (mPm.mLock) {
                        // NOTE: Ensure the system package is enabled; even for a compressed stub.
                        // If we don't, installing the system package fails during scan
                        mPm.mSettings.enableSystemPackageLPw(stubPkg.getPackageName());
                    }
                    installPackageFromSystemLIF(stubPkg.getPath(),
                            mPm.mUserManager.getUserIds() /*allUserHandles*/,
                            null /*origUserHandles*/,
                            true /*writeSettings*/);
                } catch (PackageManagerException pme) {
                    // Serious WTF; we have to be able to install the stub
                    Slog.wtf(TAG, "Failed to restore system package:" + stubPkg.getPackageName(),
                            pme);
                } finally {
                    // Disable the package; the stub by itself is not runnable
                    synchronized (mPm.mLock) {
                        final PackageSetting stubPs = mPm.mSettings.getPackageLPr(
                                stubPkg.getPackageName());
                        if (stubPs != null) {
                            stubPs.setEnabled(COMPONENT_ENABLED_STATE_DISABLED,
                                    UserHandle.USER_SYSTEM, "android");
                        }
                        mPm.writeSettingsLPrTEMP();
                    }
                }
                return false;
            }
            mAppDataHelper.clearAppDataLIF(pkg, UserHandle.USER_ALL,
                    FLAG_STORAGE_DE | FLAG_STORAGE_CE | FLAG_STORAGE_EXTERNAL
                            | Installer.FLAG_CLEAR_CODE_CACHE_ONLY);
            mPm.getDexManager().notifyPackageUpdated(pkg.getPackageName(),
                    pkg.getBaseApkPath(), pkg.getSplitCodePaths());
        }
        return true;
    }

    @GuardedBy({"mPm.mLock", "mPm.mInstallLock"})
    private AndroidPackage installStubPackageLI(AndroidPackage stubPkg,
            @ParsingPackageUtils.ParseFlags int parseFlags,
            @PackageManagerService.ScanFlags int scanFlags)
            throws PackageManagerException {
        if (DEBUG_COMPRESSION) {
            Slog.i(TAG, "Uncompressing system stub; pkg: " + stubPkg.getPackageName());
        }
        // uncompress the binary to its eventual destination on /data
        final File scanFile = decompressPackage(stubPkg.getPackageName(), stubPkg.getPath());
        if (scanFile == null) {
            throw new PackageManagerException(
                    "Unable to decompress stub at " + stubPkg.getPath());
        }
        synchronized (mPm.mLock) {
            mPm.mSettings.disableSystemPackageLPw(stubPkg.getPackageName(), true /*replaced*/);
        }
        final RemovePackageHelper removePackageHelper = new RemovePackageHelper(mPm);
        removePackageHelper.removePackageLI(stubPkg, true /*chatty*/);
        final ScanPackageHelper scanPackageHelper = new ScanPackageHelper(mPm);
        try {
            return scanPackageHelper.scanPackageTracedLI(scanFile, parseFlags, scanFlags, 0, null);
        } catch (PackageManagerException e) {
            Slog.w(TAG, "Failed to install compressed system package:" + stubPkg.getPackageName(),
                    e);
            // Remove the failed install
            removePackageHelper.removeCodePathLI(scanFile);
            throw e;
        }
    }

    /**
     * Decompresses the given package on the system image onto
     * the /data partition.
     * @return The directory the package was decompressed into. Otherwise, {@code null}.
     */
    @GuardedBy("mPm.mInstallLock")
    private File decompressPackage(String packageName, String codePath) {
        if (!compressedFileExists(codePath)) {
            if (DEBUG_COMPRESSION) {
                Slog.i(TAG, "No files to decompress at: " + codePath);
            }
            return null;
        }
        final File dstCodePath =
                PackageManagerServiceUtils.getNextCodePath(Environment.getDataAppDirectory(null),
                        packageName);
        int ret = PackageManagerServiceUtils.decompressFiles(codePath, dstCodePath, packageName);
        if (ret == PackageManager.INSTALL_SUCCEEDED) {
            ret = extractNativeBinaries(dstCodePath, packageName);
        }
        if (ret == PackageManager.INSTALL_SUCCEEDED) {
            // NOTE: During boot, we have to delay releasing cblocks for no other reason than
            // we cannot retrieve the setting {@link Secure#RELEASE_COMPRESS_BLOCKS_ON_INSTALL}.
            // When we no longer need to read that setting, cblock release can occur always
            // occur here directly
            if (!mPm.isSystemReady()) {
                if (mPm.mReleaseOnSystemReady == null) {
                    mPm.mReleaseOnSystemReady = new ArrayList<>();
                }
                mPm.mReleaseOnSystemReady.add(dstCodePath);
            } else {
                final ContentResolver resolver = mPm.mContext.getContentResolver();
                F2fsUtils.releaseCompressedBlocks(resolver, dstCodePath);
            }
        } else {
            if (!dstCodePath.exists()) {
                return null;
            }
            new RemovePackageHelper(mPm).removeCodePathLI(dstCodePath);
            return null;
        }

        return dstCodePath;
    }

    private static int extractNativeBinaries(File dstCodePath, String packageName) {
        final File libraryRoot = new File(dstCodePath, LIB_DIR_NAME);
        NativeLibraryHelper.Handle handle = null;
        try {
            handle = NativeLibraryHelper.Handle.create(dstCodePath);
            return NativeLibraryHelper.copyNativeBinariesWithOverride(handle, libraryRoot,
                    null /*abiOverride*/, false /*isIncremental*/);
        } catch (IOException e) {
            logCriticalInfo(Log.ERROR, "Failed to extract native libraries"
                    + "; pkg: " + packageName);
            return PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
        } finally {
            IoUtils.closeQuietly(handle);
        }
    }

    /**
     * Tries to restore the disabled system package after an update has been deleted.
     */
    @GuardedBy({"mPm.mLock", "mPm.mInstallLock"})
    public void restoreDisabledSystemPackageLIF(DeletePackageAction action,
            PackageSetting deletedPs, @NonNull int[] allUserHandles,
            @Nullable PackageRemovedInfo outInfo,
            boolean writeSettings,
            PackageSetting disabledPs)
            throws SystemDeleteException {
        // writer
        synchronized (mPm.mLock) {
            // NOTE: The system package always needs to be enabled; even if it's for
            // a compressed stub. If we don't, installing the system package fails
            // during scan [scanning checks the disabled packages]. We will reverse
            // this later, after we've "installed" the stub.
            // Reinstate the old system package
            mPm.mSettings.enableSystemPackageLPw(disabledPs.getPkg().getPackageName());
            // Remove any native libraries from the upgraded package.
            removeNativeBinariesLI(deletedPs);
        }

        // Install the system package
        if (DEBUG_REMOVE) Slog.d(TAG, "Re-installing system package: " + disabledPs);
        try {
            installPackageFromSystemLIF(disabledPs.getPathString(), allUserHandles,
                    outInfo == null ? null : outInfo.mOrigUsers, writeSettings);
        } catch (PackageManagerException e) {
            Slog.w(TAG, "Failed to restore system package:" + deletedPs.getPackageName() + ": "
                    + e.getMessage());
            // TODO(b/194319951): can we avoid this; throw would come from scan...
            throw new SystemDeleteException(e);
        } finally {
            if (disabledPs.getPkg().isStub()) {
                // We've re-installed the stub; make sure it's disabled here. If package was
                // originally enabled, we'll install the compressed version of the application
                // and re-enable it afterward.
                disableStubPackage(action, deletedPs, allUserHandles);
            }
        }
    }

    @GuardedBy("mPm.mLock")
    private void disableStubPackage(DeletePackageAction action, PackageSetting deletedPs,
            @NonNull int[] allUserHandles) {
        final PackageSetting stubPs = mPm.mSettings.getPackageLPr(
                deletedPs.getPackageName());
        if (stubPs != null) {
            int userId = action.mUser == null
                    ? UserHandle.USER_ALL : action.mUser.getIdentifier();
            if (userId == UserHandle.USER_ALL) {
                for (int aUserId : allUserHandles) {
                    stubPs.setEnabled(COMPONENT_ENABLED_STATE_DISABLED, aUserId, "android");
                }
            } else if (userId >= UserHandle.USER_SYSTEM) {
                stubPs.setEnabled(COMPONENT_ENABLED_STATE_DISABLED, userId, "android");
            }
        }
    }

    private static void removeNativeBinariesLI(PackageSetting ps) {
        if (ps != null) {
            NativeLibraryHelper.removeNativeBinariesLI(ps.getLegacyNativeLibraryPath());
        }
    }

    /**
     * Installs a package that's already on the system partition.
     */
    @GuardedBy({"mPm.mLock", "mPm.mInstallLock"})
    private void installPackageFromSystemLIF(@NonNull String codePathString,
            @NonNull int[] allUserHandles, @Nullable int[] origUserHandles, boolean writeSettings)
            throws PackageManagerException {
        final File codePath = new File(codePathString);
        @ParsingPackageUtils.ParseFlags int parseFlags =
                mPm.getDefParseFlags()
                        | ParsingPackageUtils.PARSE_MUST_BE_APK
                        | ParsingPackageUtils.PARSE_IS_SYSTEM_DIR;
        @PackageManagerService.ScanFlags int scanFlags = mPm.getSystemPackageScanFlags(codePath);
        final ScanPackageHelper scanPackageHelper = new ScanPackageHelper(mPm);
        final AndroidPackage pkg =
                scanPackageHelper.scanPackageTracedLI(
                        codePath, parseFlags, scanFlags, 0 /*currentTime*/, null);

        PackageSetting pkgSetting = mPm.mSettings.getPackageLPr(pkg.getPackageName());

        try {
            // update shared libraries for the newly re-installed system package
            mPm.updateSharedLibrariesLocked(pkg, pkgSetting, null, null,
                    Collections.unmodifiableMap(mPm.mPackages));
        } catch (PackageManagerException e) {
            Slog.e(TAG, "updateAllSharedLibrariesLPw failed: " + e.getMessage());
        }

        mAppDataHelper.prepareAppDataAfterInstallLIF(pkg);

        setPackageInstalledForSystemPackage(pkg, allUserHandles,
                origUserHandles, writeSettings);
    }

    private void setPackageInstalledForSystemPackage(@NonNull AndroidPackage pkg,
            @NonNull int[] allUserHandles, @Nullable int[] origUserHandles, boolean writeSettings) {
        // writer
        synchronized (mPm.mLock) {
            PackageSetting ps = mPm.mSettings.getPackageLPr(pkg.getPackageName());

            final boolean applyUserRestrictions = origUserHandles != null;
            if (applyUserRestrictions) {
                boolean installedStateChanged = false;
                if (DEBUG_REMOVE) {
                    Slog.d(TAG, "Propagating install state across reinstall");
                }
                for (int userId : allUserHandles) {
                    final boolean installed = ArrayUtils.contains(origUserHandles, userId);
                    if (DEBUG_REMOVE) {
                        Slog.d(TAG, "    user " + userId + " => " + installed);
                    }
                    if (installed != ps.getInstalled(userId)) {
                        installedStateChanged = true;
                    }
                    ps.setInstalled(installed, userId);
                    if (installed) {
                        ps.setUninstallReason(UNINSTALL_REASON_UNKNOWN, userId);
                    }
                }
                // Regardless of writeSettings we need to ensure that this restriction
                // state propagation is persisted
                mPm.mSettings.writeAllUsersPackageRestrictionsLPr();
                if (installedStateChanged) {
                    mPm.mSettings.writeKernelMappingLPr(ps);
                }
            }

            // The method below will take care of removing obsolete permissions and granting
            // install permissions.
            mPm.mPermissionManager.onPackageInstalled(pkg,
                    Process.INVALID_UID /* previousAppId */,
                    PermissionManagerServiceInternal.PackageInstalledParams.DEFAULT,
                    UserHandle.USER_ALL);
            for (final int userId : allUserHandles) {
                if (applyUserRestrictions) {
                    mPm.mSettings.writePermissionStateForUserLPr(userId, false);
                }
            }

            // can downgrade to reader here
            if (writeSettings) {
                mPm.writeSettingsLPrTEMP();
            }
        }
    }

}
