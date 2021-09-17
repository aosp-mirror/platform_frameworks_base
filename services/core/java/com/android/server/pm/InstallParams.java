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

import static android.app.AppOpsManager.MODE_DEFAULT;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.INSTALL_FAILED_ALREADY_EXISTS;
import static android.content.pm.PackageManager.INSTALL_FAILED_BAD_PERMISSION_GROUP;
import static android.content.pm.PackageManager.INSTALL_FAILED_DUPLICATE_PACKAGE;
import static android.content.pm.PackageManager.INSTALL_FAILED_DUPLICATE_PERMISSION;
import static android.content.pm.PackageManager.INSTALL_FAILED_DUPLICATE_PERMISSION_GROUP;
import static android.content.pm.PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
import static android.content.pm.PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
import static android.content.pm.PackageManager.INSTALL_FAILED_INVALID_APK;
import static android.content.pm.PackageManager.INSTALL_FAILED_INVALID_INSTALL_LOCATION;
import static android.content.pm.PackageManager.INSTALL_FAILED_SESSION_INVALID;
import static android.content.pm.PackageManager.INSTALL_FAILED_TEST_ONLY;
import static android.content.pm.PackageManager.INSTALL_FAILED_UID_CHANGED;
import static android.content.pm.PackageManager.INSTALL_FAILED_UPDATE_INCOMPATIBLE;
import static android.content.pm.PackageManager.INSTALL_REASON_DEVICE_RESTORE;
import static android.content.pm.PackageManager.INSTALL_REASON_DEVICE_SETUP;
import static android.content.pm.PackageManager.INSTALL_STAGED;
import static android.content.pm.PackageManager.INSTALL_SUCCEEDED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.content.pm.PackageManager.UNINSTALL_REASON_UNKNOWN;
import static android.content.pm.SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V4;
import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;
import static android.os.incremental.IncrementalManager.isIncrementalPath;
import static android.os.storage.StorageManager.FLAG_STORAGE_CE;
import static android.os.storage.StorageManager.FLAG_STORAGE_DE;
import static android.os.storage.StorageManager.FLAG_STORAGE_EXTERNAL;

import static com.android.server.pm.InstructionSets.getAppDexInstructionSets;
import static com.android.server.pm.PackageManagerService.DEBUG_INSTALL;
import static com.android.server.pm.PackageManagerService.DEBUG_INSTANT;
import static com.android.server.pm.PackageManagerService.INIT_COPY;
import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;
import static com.android.server.pm.PackageManagerService.PRECOMPILE_LAYOUTS;
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
import static com.android.server.pm.PackageManagerService.SCAN_DONT_KILL_APP;
import static com.android.server.pm.PackageManagerService.SCAN_INITIAL;
import static com.android.server.pm.PackageManagerService.SCAN_MOVE;
import static com.android.server.pm.PackageManagerService.SCAN_NEW_INSTALL;
import static com.android.server.pm.PackageManagerService.SCAN_NO_DEX;
import static com.android.server.pm.PackageManagerService.SCAN_UPDATE_SIGNATURE;
import static com.android.server.pm.PackageManagerService.TAG;
import static com.android.server.pm.PackageManagerServiceUtils.deriveAbiOverride;
import static com.android.server.pm.PackageManagerServiceUtils.verifySignatures;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ApplicationPackageManager;
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
import android.content.pm.SigningDetails;
import android.content.pm.dex.DexMetadataHelper;
import android.content.pm.parsing.PackageLite;
import android.content.pm.parsing.ParsingPackageUtils;
import android.content.pm.parsing.component.ParsedPermission;
import android.content.pm.parsing.component.ParsedPermissionGroup;
import android.content.pm.parsing.result.ParseResult;
import android.content.pm.parsing.result.ParseTypeImpl;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Message;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.incremental.IncrementalManager;
import android.os.incremental.IncrementalStorage;
import android.os.storage.StorageManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.F2fsUtils;
import com.android.internal.content.PackageHelper;
import com.android.internal.security.VerityUtils;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;
import com.android.server.Watchdog;
import com.android.server.pm.dex.DexoptOptions;
import com.android.server.pm.parsing.PackageParser2;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;
import com.android.server.pm.parsing.pkg.ParsedPackage;
import com.android.server.pm.permission.Permission;
import com.android.server.pm.permission.PermissionManagerServiceInternal;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.DigestException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class InstallParams extends HandlerParams {
    final OriginInfo mOriginInfo;
    final MoveInfo mMoveInfo;
    final IPackageInstallObserver2 mObserver;
    int mInstallFlags;
    @NonNull
    final InstallSource mInstallSource;
    final String mVolumeUuid;
    int mRet;
    final String mPackageAbiOverride;
    final String[] mGrantedRuntimePermissions;
    final List<String> mAllowlistedRestrictedPermissions;
    final int mAutoRevokePermissionsMode;
    final SigningDetails mSigningDetails;
    final int mInstallReason;
    final int mInstallScenario;
    @Nullable
    MultiPackageInstallParams mParentInstallParams;
    final boolean mForceQueryableOverride;
    final int mDataLoaderType;
    final long mRequiredInstalledVersionCode;
    final PackageLite mPackageLite;

    InstallParams(OriginInfo originInfo, MoveInfo moveInfo, IPackageInstallObserver2 observer,
            int installFlags, InstallSource installSource, String volumeUuid,
            UserHandle user, String packageAbiOverride, PackageLite packageLite,
            PackageManagerService pm) {
        super(user, pm);
        mOriginInfo = originInfo;
        mMoveInfo = moveInfo;
        mObserver = observer;
        mInstallFlags = installFlags;
        mInstallSource = Preconditions.checkNotNull(installSource);
        mVolumeUuid = volumeUuid;
        mPackageAbiOverride = packageAbiOverride;

        mGrantedRuntimePermissions = null;
        mAllowlistedRestrictedPermissions = null;
        mAutoRevokePermissionsMode = MODE_DEFAULT;
        mSigningDetails = SigningDetails.UNKNOWN;
        mInstallReason = PackageManager.INSTALL_REASON_UNKNOWN;
        mInstallScenario = PackageManager.INSTALL_SCENARIO_DEFAULT;
        mForceQueryableOverride = false;
        mDataLoaderType = DataLoaderType.NONE;
        mRequiredInstalledVersionCode = PackageManager.VERSION_CODE_HIGHEST;
        mPackageLite = packageLite;
    }

    InstallParams(File stagedDir, IPackageInstallObserver2 observer,
            PackageInstaller.SessionParams sessionParams, InstallSource installSource,
            UserHandle user, SigningDetails signingDetails, int installerUid,
            PackageLite packageLite, PackageManagerService pm) {
        super(user, pm);
        mOriginInfo = OriginInfo.fromStagedFile(stagedDir);
        mMoveInfo = null;
        mInstallReason = fixUpInstallReason(
                installSource.installerPackageName, installerUid, sessionParams.installReason);
        mInstallScenario = sessionParams.installScenario;
        mObserver = observer;
        mInstallFlags = sessionParams.installFlags;
        mInstallSource = installSource;
        mVolumeUuid = sessionParams.volumeUuid;
        mPackageAbiOverride = sessionParams.abiOverride;
        mGrantedRuntimePermissions = sessionParams.grantedRuntimePermissions;
        mAllowlistedRestrictedPermissions = sessionParams.whitelistedRestrictedPermissions;
        mAutoRevokePermissionsMode = sessionParams.autoRevokePermissionsMode;
        mSigningDetails = signingDetails;
        mForceQueryableOverride = sessionParams.forceQueryableOverride;
        mDataLoaderType = (sessionParams.dataLoaderParams != null)
                ? sessionParams.dataLoaderParams.getType() : DataLoaderType.NONE;
        mRequiredInstalledVersionCode = sessionParams.requiredInstalledVersionCode;
        mPackageLite = packageLite;
    }

    @Override
    public String toString() {
        return "InstallParams{" + Integer.toHexString(System.identityHashCode(this))
                + " file=" + mOriginInfo.mFile + "}";
    }

    private int installLocationPolicy(PackageInfoLite pkgLite) {
        String packageName = pkgLite.packageName;
        int installLocation = pkgLite.installLocation;
        // reader
        synchronized (mPm.mLock) {
            // Currently installed package which the new package is attempting to replace or
            // null if no such package is installed.
            AndroidPackage installedPkg = mPm.mPackages.get(packageName);

            if (installedPkg != null) {
                if ((mInstallFlags & PackageManager.INSTALL_REPLACE_EXISTING) != 0) {
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

    /**
     * Override install location based on default policy if needed.
     *
     * Only {@link #mInstallFlags} may mutate in this method.
     *
     * Only {@link PackageManager#INSTALL_INTERNAL} flag may mutate in
     * {@link #mInstallFlags}
     */
    private int overrideInstallLocation(PackageInfoLite pkgLite) {
        final boolean ephemeral = (mInstallFlags & PackageManager.INSTALL_INSTANT_APP) != 0;
        if (DEBUG_INSTANT && ephemeral) {
            Slog.v(TAG, "pkgLite for install: " + pkgLite);
        }

        if (mOriginInfo.mStaged) {
            // If we're already staged, we've firmly committed to an install location
            if (mOriginInfo.mFile != null) {
                mInstallFlags |= PackageManager.INSTALL_INTERNAL;
            } else {
                throw new IllegalStateException("Invalid stage location");
            }
        } else if (pkgLite.recommendedInstallLocation
                == PackageHelper.RECOMMEND_FAILED_INSUFFICIENT_STORAGE) {
            /*
             * If we are not staged and have too little free space, try to free cache
             * before giving up.
             */
            // TODO: focus freeing disk space on the target device
            final StorageManager storage = StorageManager.from(mPm.mContext);
            final long lowThreshold = storage.getStorageLowBytes(
                    Environment.getDataDirectory());

            final long sizeBytes = PackageManagerServiceUtils.calculateInstalledSize(
                    mOriginInfo.mResolvedPath, mPackageAbiOverride);
            if (sizeBytes >= 0) {
                try {
                    mPm.mInstaller.freeCache(null, sizeBytes + lowThreshold, 0, 0);
                    pkgLite = PackageManagerServiceUtils.getMinimalPackageInfo(mPm.mContext,
                            mPackageLite, mOriginInfo.mResolvedPath, mInstallFlags,
                            mPackageAbiOverride);
                } catch (Installer.InstallerException e) {
                    Slog.w(TAG, "Failed to free cache", e);
                }
            }

            /*
             * The cache free must have deleted the file we downloaded to install.
             *
             * TODO: fix the "freeCache" call to not delete the file we care about.
             */
            if (pkgLite.recommendedInstallLocation
                    == PackageHelper.RECOMMEND_FAILED_INVALID_URI) {
                pkgLite.recommendedInstallLocation =
                        PackageHelper.RECOMMEND_FAILED_INSUFFICIENT_STORAGE;
            }
        }

        int ret = INSTALL_SUCCEEDED;
        int loc = pkgLite.recommendedInstallLocation;
        if (loc == PackageHelper.RECOMMEND_FAILED_INVALID_LOCATION) {
            ret = PackageManager.INSTALL_FAILED_INVALID_INSTALL_LOCATION;
        } else if (loc == PackageHelper.RECOMMEND_FAILED_ALREADY_EXISTS) {
            ret = PackageManager.INSTALL_FAILED_ALREADY_EXISTS;
        } else if (loc == PackageHelper.RECOMMEND_FAILED_INSUFFICIENT_STORAGE) {
            ret = PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
        } else if (loc == PackageHelper.RECOMMEND_FAILED_INVALID_APK) {
            ret = PackageManager.INSTALL_FAILED_INVALID_APK;
        } else if (loc == PackageHelper.RECOMMEND_FAILED_INVALID_URI) {
            ret = PackageManager.INSTALL_FAILED_INVALID_URI;
        } else if (loc == PackageHelper.RECOMMEND_MEDIA_UNAVAILABLE) {
            ret = PackageManager.INSTALL_FAILED_MEDIA_UNAVAILABLE;
        } else {
            // Override with defaults if needed.
            loc = installLocationPolicy(pkgLite);

            final boolean onInt = (mInstallFlags & PackageManager.INSTALL_INTERNAL) != 0;

            if (!onInt) {
                // Override install location with flags
                if (loc == PackageHelper.RECOMMEND_INSTALL_EXTERNAL) {
                    // Set the flag to install on external media.
                    mInstallFlags &= ~PackageManager.INSTALL_INTERNAL;
                } else {
                    // Make sure the flag for installing on external
                    // media is unset
                    mInstallFlags |= PackageManager.INSTALL_INTERNAL;
                }
            }
        }
        return ret;
    }

    /*
     * Invoke remote method to get package information and install
     * location values. Override install location based on default
     * policy if needed and then create install arguments based
     * on the install location.
     */
    public void handleStartCopy() {
        if ((mInstallFlags & PackageManager.INSTALL_APEX) != 0) {
            mRet = INSTALL_SUCCEEDED;
            return;
        }
        PackageInfoLite pkgLite = PackageManagerServiceUtils.getMinimalPackageInfo(mPm.mContext,
                mPackageLite, mOriginInfo.mResolvedPath, mInstallFlags, mPackageAbiOverride);

        // For staged session, there is a delay between its verification and install. Device
        // state can change within this delay and hence we need to re-verify certain conditions.
        boolean isStaged = (mInstallFlags & INSTALL_STAGED) != 0;
        if (isStaged) {
            Pair<Integer, String> ret = verifyReplacingVersionCode(
                    pkgLite, mRequiredInstalledVersionCode, mInstallFlags);
            mRet = ret.first;
            if (mRet != INSTALL_SUCCEEDED) {
                return;
            }
        }

        mRet = overrideInstallLocation(pkgLite);
    }

    @Override
    void handleReturnCode() {
        processPendingInstall();
    }

    private void processPendingInstall() {
        InstallArgs args = createInstallArgs(this);
        if (mRet == PackageManager.INSTALL_SUCCEEDED) {
            mRet = args.copyApk();
        }
        if (mRet == PackageManager.INSTALL_SUCCEEDED) {
            F2fsUtils.releaseCompressedBlocks(
                    mPm.mContext.getContentResolver(), new File(args.getCodePath()));
        }
        if (mParentInstallParams != null) {
            mParentInstallParams.tryProcessInstallRequest(args, mRet);
        } else {
            PackageInstalledInfo res = new PackageInstalledInfo(mRet);
            processInstallRequestsAsync(
                    res.mReturnCode == PackageManager.INSTALL_SUCCEEDED,
                    Collections.singletonList(new InstallRequest(args, res)));
        }
    }

    private InstallArgs createInstallArgs(InstallParams params) {
        if (params.mMoveInfo != null) {
            return new MoveInstallArgs(params);
        } else {
            return new FileInstallArgs(params);
        }
    }

    // Queue up an async operation since the package installation may take a little while.
    private void processInstallRequestsAsync(boolean success,
            List<InstallRequest> installRequests) {
        mPm.mHandler.post(() -> {
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
                    mPm.notifyInstallObserver(request.mInstallResult, request.mArgs.mObserver);
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
                mPm.restoreAndPostInstall(request.mArgs.mUser.getIdentifier(),
                        request.mInstallResult,
                        new PackageManagerService.PostInstallData(request.mArgs,
                                request.mInstallResult, null));
            }
        });
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
                    if (null != preparedScans.put(result.mPkgSetting.pkg.getPackageName(),
                            result)) {
                        request.mInstallResult.setError(
                                PackageManager.INSTALL_FAILED_DUPLICATE_PACKAGE,
                                "Duplicate package " + result.mPkgSetting.pkg.getPackageName()
                                        + " in multi-package install request.");
                        return;
                    }
                    createdAppId.put(packageName,
                            scanPackageHelper.optimisticallyRegisterAppId(result));
                    versionInfos.put(result.mPkgSetting.pkg.getPackageName(),
                            mPm.getSettingsVersionForPackage(result.mPkgSetting.pkg));
                    if (result.mStaticSharedLibraryInfo != null) {
                        final PackageSetting sharedLibLatestVersionSetting =
                                mPm.getSharedLibLatestVersionSetting(result);
                        if (sharedLibLatestVersionSetting != null) {
                            lastStaticSharedLibSettings.put(result.mPkgSetting.pkg.getPackageName(),
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
                    reconciledPackages = mPm.reconcilePackagesLocked(
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
                    mPm.broadcastPackageVerified(verificationId, originUri,
                            PackageManager.VERIFICATION_ALLOW, rootHashString,
                            args.mDataLoaderType, args.getUser());
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
                mPm.mDefParseFlags | ParsingPackageUtils.PARSE_CHATTY
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
                        final boolean compareCompat = mPm.isCompatSignatureUpdateNeeded(
                                parsedPackage);
                        final boolean compareRecover = mPm.isRecoverSignatureUpdateNeeded(
                                parsedPackage);
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

                if (ps.pkg != null) {
                    systemApp = ps.pkg.isSystem();
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
                    perm.setProtectionLevel(
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
                                perm.setProtectionLevel(bp.getProtectionLevel());
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
                parsedPackage.setPrimaryCpuAbi(ps.primaryCpuAbiString)
                        .setSecondaryCpuAbi(ps.secondaryCpuAbiString);
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
                res.mRemovedInfo.mInstallerPackageName = ps.installSource.installerPackageName;
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
                    sourcePackageSetting.signatures.mSigningDetails =
                            parsedPackage.getSigningDetails();
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

            if (reconciledPkg.mPrepareResult.mReplace) {
                AndroidPackage oldPackage = mPm.mPackages.get(packageName);

                // Set the update and install times
                PackageSetting deletedPkgSetting = mPm.getPackageSetting(
                        oldPackage.getPackageName());
                reconciledPkg.mPkgSetting.firstInstallTime = deletedPkgSetting.firstInstallTime;
                reconciledPkg.mPkgSetting.lastUpdateTime = System.currentTimeMillis();

                res.mRemovedInfo.mBroadcastAllowList = mPm.mAppsFilter.getVisibilityAllowList(
                        reconciledPkg.mPkgSetting, request.mAllUsers,
                        mPm.mSettings.getPackagesLocked());
                if (reconciledPkg.mPrepareResult.mSystem) {
                    // Remove existing system package
                    mPm.removePackageLI(oldPackage, true);
                    if (!disableSystemPackageLPw(oldPackage)) {
                        // We didn't need to disable the .apk as a current system package,
                        // which means we are replacing another update that is already
                        // installed.  We need to make sure to delete the older one's .apk.
                        res.mRemovedInfo.mArgs = mPm.createInstallArgsForExisting(
                                oldPackage.getPath(),
                                getAppDexInstructionSets(
                                        AndroidPackageUtils.getPrimaryCpuAbi(oldPackage,
                                                deletedPkgSetting),
                                        AndroidPackageUtils.getSecondaryCpuAbi(oldPackage,
                                                deletedPkgSetting)));
                    } else {
                        res.mRemovedInfo.mArgs = null;
                    }
                } else {
                    try {
                        // Settings will be written during the call to updateSettingsLI().
                        mPm.executeDeletePackageLIF(reconciledPkg.mDeletePackageAction, packageName,
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
                        mPm.sendResourcesChangedBroadcast(false, true, pkgList, uidArray, null);
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
                                    mPm.mPackages.get(ps2.name) == null;
                        }
                    }
                }
            }

            AndroidPackage pkg = mPm.commitReconciledScanResultLocked(reconciledPkg,
                    request.mAllUsers);
            updateSettingsLI(pkg, reconciledPkg.mInstallArgs, request.mAllUsers, res);

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

    private void updateSettingsLI(AndroidPackage newPackage, InstallArgs installArgs,
            int[] allUsers, PackageInstalledInfo res) {
        updateSettingsInternalLI(newPackage, installArgs, allUsers, res);
    }

    private void updateSettingsInternalLI(AndroidPackage pkg, InstallArgs installArgs,
            int[] allUsers, PackageInstalledInfo res) {
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "updateSettings");

        final String pkgName = pkg.getPackageName();
        final int[] installedForUsers = res.mOrigUsers;
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

                mPm.mSettings.addInstallerPackageNames(ps.installSource);

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
                    final IncrementalStatesCallback incrementalStatesCallback =
                            new IncrementalStatesCallback(ps.name, mPm);
                    ps.setIncrementalStatesCallback(incrementalStatesCallback);
                    mPm.mIncrementalManager.registerLoadingProgressCallback(codePath,
                            new IncrementalProgressListener(ps.name, mPm));
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
                mPm.mPermissionManager.onPackageInstalled(pkg, permissionParamsBuilder.build(),
                        userId);
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
        for (ReconciledPackage reconciledPkg : commitRequest.mReconciledPackages.values()) {
            final boolean instantApp = ((reconciledPkg.mScanResult.mRequest.mScanFlags
                    & SCAN_AS_INSTANT_APP) != 0);
            final AndroidPackage pkg = reconciledPkg.mPkgSetting.pkg;
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
            mPm.prepareAppDataAfterInstallLIF(pkg);
            if (reconciledPkg.mPrepareResult.mClearCodeCache) {
                mPm.clearAppDataLIF(pkg, UserHandle.USER_ALL, FLAG_STORAGE_DE | FLAG_STORAGE_CE
                        | FLAG_STORAGE_EXTERNAL | Installer.FLAG_CLEAR_CODE_CACHE_ONLY);
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
            BackgroundDexOptService.notifyPackageChanged(packageName);

            notifyPackageChangeObserversOnUpdate(reconciledPkg);
        }
        waitForNativeBinariesExtraction(incrementalStorages);
    }

    private void notifyPackageChangeObserversOnUpdate(ReconciledPackage reconciledPkg) {
        final PackageSetting pkgSetting = reconciledPkg.mPkgSetting;
        final PackageInstalledInfo pkgInstalledInfo = reconciledPkg.mInstallResult;
        final PackageRemovedInfo pkgRemovedInfo = pkgInstalledInfo.mRemovedInfo;

        PackageChangeEvent pkgChangeEvent = new PackageChangeEvent();
        pkgChangeEvent.packageName = pkgSetting.pkg.getPackageName();
        pkgChangeEvent.version = pkgSetting.versionCode;
        pkgChangeEvent.lastUpdateTimeMillis = pkgSetting.lastUpdateTime;
        pkgChangeEvent.newInstalled = (pkgRemovedInfo == null || !pkgRemovedInfo.mIsUpdate);
        pkgChangeEvent.dataRemoved = (pkgRemovedInfo != null && pkgRemovedInfo.mDataRemoved);
        pkgChangeEvent.isDeleted = false;

        mPm.notifyPackageChangeObservers(pkgChangeEvent);
    }

    static void waitForNativeBinariesExtraction(
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

    /**
     * Ensure that the install reason matches what we know about the package installer (e.g. whether
     * it is acting on behalf on an enterprise or the user).
     *
     * Note that the ordering of the conditionals in this method is important. The checks we perform
     * are as follows, in this order:
     *
     * 1) If the install is being performed by a system app, we can trust the app to have set the
     *    install reason correctly. Thus, we pass through the install reason unchanged, no matter
     *    what it is.
     * 2) If the install is being performed by a device or profile owner app, the install reason
     *    should be enterprise policy. However, we cannot be sure that the device or profile owner
     *    set the install reason correctly. If the app targets an older SDK version where install
     *    reasons did not exist yet, or if the app author simply forgot, the install reason may be
     *    unset or wrong. Thus, we force the install reason to be enterprise policy.
     * 3) In all other cases, the install is being performed by a regular app that is neither part
     *    of the system nor a device or profile owner. We have no reason to believe that this app is
     *    acting on behalf of the enterprise admin. Thus, we check whether the install reason was
     *    set to enterprise policy and if so, change it to unknown instead.
     */
    private int fixUpInstallReason(String installerPackageName, int installerUid,
            int installReason) {
        if (mPm.checkUidPermission(android.Manifest.permission.INSTALL_PACKAGES, installerUid)
                == PERMISSION_GRANTED) {
            // If the install is being performed by a system app, we trust that app to have set the
            // install reason correctly.
            return installReason;
        }
        final String ownerPackage = mPm.mProtectedPackages.getDeviceOwnerOrProfileOwnerPackage(
                UserHandle.getUserId(installerUid));
        if (ownerPackage != null && ownerPackage.equals(installerPackageName)) {
            // If the install is being performed by a device or profile owner, the install
            // reason should be enterprise policy.
            return PackageManager.INSTALL_REASON_POLICY;
        }


        if (installReason == PackageManager.INSTALL_REASON_POLICY) {
            // If the install is being performed by a regular app (i.e. neither system app nor
            // device or profile owner), we have no reason to believe that the app is acting on
            // behalf of an enterprise. If the app set the install reason to enterprise policy,
            // change it to unknown instead.
            return PackageManager.INSTALL_REASON_UNKNOWN;
        }

        // If the install is being performed by a regular app and the install reason was set to any
        // value but enterprise policy, leave the install reason unchanged.
        return installReason;
    }

    public void installStage() {
        final Message msg = mPm.mHandler.obtainMessage(INIT_COPY);
        setTraceMethod("installStage").setTraceCookie(System.identityHashCode(this));
        msg.obj = this;

        Trace.asyncTraceBegin(TRACE_TAG_PACKAGE_MANAGER, "installStage",
                System.identityHashCode(msg.obj));
        Trace.asyncTraceBegin(TRACE_TAG_PACKAGE_MANAGER, "queueInstall",
                System.identityHashCode(msg.obj));

        mPm.mHandler.sendMessage(msg);
    }

    public void installStage(List<InstallParams> children)
            throws PackageManagerException {
        final Message msg = mPm.mHandler.obtainMessage(INIT_COPY);
        final MultiPackageInstallParams params =
                new MultiPackageInstallParams(this, children, mPm);
        params.setTraceMethod("installStageMultiPackage")
                .setTraceCookie(System.identityHashCode(params));
        msg.obj = params;

        Trace.asyncTraceBegin(TRACE_TAG_PACKAGE_MANAGER, "installStageMultiPackage",
                System.identityHashCode(msg.obj));
        Trace.asyncTraceBegin(TRACE_TAG_PACKAGE_MANAGER, "queueInstall",
                System.identityHashCode(msg.obj));
        mPm.mHandler.sendMessage(msg);
    }

    public void movePackage() {
        final Message msg = mPm.mHandler.obtainMessage(INIT_COPY);
        setTraceMethod("movePackage").setTraceCookie(System.identityHashCode(this));
        msg.obj = this;

        Trace.asyncTraceBegin(TRACE_TAG_PACKAGE_MANAGER, "movePackage",
                System.identityHashCode(msg.obj));
        Trace.asyncTraceBegin(TRACE_TAG_PACKAGE_MANAGER, "queueInstall",
                System.identityHashCode(msg.obj));
        mPm.mHandler.sendMessage(msg);
    }

    /**
     * Container for a multi-package install which refers to all install sessions and args being
     * committed together.
     */
    final class MultiPackageInstallParams extends HandlerParams {
        private final List<InstallParams> mChildParams;
        private final Map<InstallArgs, Integer> mCurrentState;

        MultiPackageInstallParams(InstallParams parent, List<InstallParams> childParams,
                PackageManagerService pm)
                throws PackageManagerException {
            super(parent.getUser(), pm);
            if (childParams.size() == 0) {
                throw new PackageManagerException("No child sessions found!");
            }
            mChildParams = childParams;
            for (int i = 0; i < childParams.size(); i++) {
                final InstallParams childParam = childParams.get(i);
                childParam.mParentInstallParams = this;
            }
            this.mCurrentState = new ArrayMap<>(mChildParams.size());
        }

        @Override
        void handleStartCopy() {
            for (InstallParams params : mChildParams) {
                params.handleStartCopy();
            }
        }

        @Override
        void handleReturnCode() {
            for (InstallParams params : mChildParams) {
                params.handleReturnCode();
            }
        }

        void tryProcessInstallRequest(InstallArgs args, int currentStatus) {
            mCurrentState.put(args, currentStatus);
            if (mCurrentState.size() != mChildParams.size()) {
                return;
            }
            int completeStatus = PackageManager.INSTALL_SUCCEEDED;
            for (Integer status : mCurrentState.values()) {
                if (status == PackageManager.INSTALL_UNKNOWN) {
                    return;
                } else if (status != PackageManager.INSTALL_SUCCEEDED) {
                    completeStatus = status;
                    break;
                }
            }
            final List<InstallRequest> installRequests = new ArrayList<>(mCurrentState.size());
            for (Map.Entry<InstallArgs, Integer> entry : mCurrentState.entrySet()) {
                installRequests.add(new InstallRequest(entry.getKey(),
                        new PackageInstalledInfo(completeStatus)));
            }
            processInstallRequestsAsync(
                    completeStatus == PackageManager.INSTALL_SUCCEEDED,
                    installRequests);
        }
    }


}
