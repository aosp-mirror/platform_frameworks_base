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
import static android.content.pm.PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
import static android.content.pm.PackageManager.INSTALL_STAGED;
import static android.content.pm.PackageManager.INSTALL_SUCCEEDED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;
import static android.os.incremental.IncrementalManager.isIncrementalPath;

import static com.android.internal.content.NativeLibraryHelper.LIB_DIR_NAME;
import static com.android.server.pm.PackageManagerService.DEBUG_INSTALL;
import static com.android.server.pm.PackageManagerService.DEBUG_INSTANT;
import static com.android.server.pm.PackageManagerService.TAG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.apex.ApexInfo;
import android.content.pm.DataLoaderType;
import android.content.pm.IPackageInstallObserver2;
import android.content.pm.PackageInfoLite;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.SigningDetails;
import android.content.pm.parsing.PackageLite;
import android.os.Environment;
import android.os.Trace;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.content.F2fsUtils;
import com.android.internal.content.InstallLocationUtils;
import com.android.internal.content.NativeLibraryHelper;
import com.android.internal.util.Preconditions;
import com.android.server.pm.parsing.PackageParser2;
import com.android.server.pm.pkg.AndroidPackage;

import libcore.io.IoUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

class InstallingSession {
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
    MultiPackageInstallingSession mParentInstallingSession;
    final boolean mForceQueryableOverride;
    final int mDataLoaderType;
    final long mRequiredInstalledVersionCode;
    final int mPackageSource;
    final PackageLite mPackageLite;
    String mTraceMethod;
    int mTraceCookie;
    /** User handle for the user requesting the information or installation. */
    private final UserHandle mUser;
    @NonNull
    final PackageManagerService mPm;
    final InstallPackageHelper mInstallPackageHelper;
    final RemovePackageHelper mRemovePackageHelper;

    InstallingSession(OriginInfo originInfo, MoveInfo moveInfo, IPackageInstallObserver2 observer,
            int installFlags, InstallSource installSource, String volumeUuid,
            UserHandle user, String packageAbiOverride, int packageSource,
            PackageLite packageLite, PackageManagerService pm) {
        mPm = pm;
        mUser = user;
        mInstallPackageHelper = new InstallPackageHelper(mPm);
        mRemovePackageHelper = new RemovePackageHelper(mPm);
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
        mPackageSource = packageSource;
        mPackageLite = packageLite;
    }

    InstallingSession(File stagedDir, IPackageInstallObserver2 observer,
            PackageInstaller.SessionParams sessionParams, InstallSource installSource,
            UserHandle user, SigningDetails signingDetails, int installerUid,
            PackageLite packageLite, PackageManagerService pm) {
        mPm = pm;
        mUser = user;
        mInstallPackageHelper = new InstallPackageHelper(mPm);
        mRemovePackageHelper = new RemovePackageHelper(mPm);
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
        mPackageSource = sessionParams.packageSource;
        mPackageLite = packageLite;
    }

    @Override
    public String toString() {
        return "InstallingSession{" + Integer.toHexString(System.identityHashCode(this))
                + " file=" + mOriginInfo.mFile + "}";
    }

    /**
     * Override install location based on default policy if needed.
     *
     * Only {@link #mInstallFlags} may mutate in this method.
     *
     * Only {@link PackageManager#INSTALL_INTERNAL} flag may mutate in
     * {@link #mInstallFlags}
     */
    private int overrideInstallLocation(String packageName, int recommendedInstallLocation,
            int installLocation) {
        if (mOriginInfo.mStaged) {
            // If we're already staged, we've firmly committed to an install location
            if (mOriginInfo.mFile != null) {
                mInstallFlags |= PackageManager.INSTALL_INTERNAL;
            } else {
                throw new IllegalStateException("Invalid stage location");
            }
        }
        if (recommendedInstallLocation < 0) {
            return InstallLocationUtils.getInstallationErrorCode(recommendedInstallLocation);
        }
        // Override with defaults if needed.
        Computer snapshot = mPm.snapshotComputer();
        AndroidPackage installedPkg = snapshot.getPackage(packageName);
        if (installedPkg != null) {
            // Currently installed package which the new package is attempting to replace
            recommendedInstallLocation = InstallLocationUtils.installLocationPolicy(
                    installLocation, recommendedInstallLocation, mInstallFlags,
                    installedPkg.isSystem(), installedPkg.isExternalStorage());
        }

        final boolean onInt = (mInstallFlags & PackageManager.INSTALL_INTERNAL) != 0;

        if (!onInt) {
            // Override install location with flags
            if (recommendedInstallLocation == InstallLocationUtils.RECOMMEND_INSTALL_EXTERNAL) {
                // Set the flag to install on external media.
                mInstallFlags &= ~PackageManager.INSTALL_INTERNAL;
            } else {
                // Make sure the flag for installing on external media is unset
                mInstallFlags |= PackageManager.INSTALL_INTERNAL;
            }
        }
        return INSTALL_SUCCEEDED;
    }

    /*
     * Invoke remote method to get package information and install
     * location values. Override install location based on default
     * policy if needed and then create install arguments based
     * on the install location.
     */
    private void handleStartCopy() {
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
            Pair<Integer, String> ret = mInstallPackageHelper.verifyReplacingVersionCode(
                    pkgLite, mRequiredInstalledVersionCode, mInstallFlags);
            mRet = ret.first;
            if (mRet != INSTALL_SUCCEEDED) {
                return;
            }
        }

        final boolean ephemeral = (mInstallFlags & PackageManager.INSTALL_INSTANT_APP) != 0;
        if (DEBUG_INSTANT && ephemeral) {
            Slog.v(TAG, "pkgLite for install: " + pkgLite);
        }

        if (!mOriginInfo.mStaged && pkgLite.recommendedInstallLocation
                == InstallLocationUtils.RECOMMEND_FAILED_INSUFFICIENT_STORAGE) {
            // If we are not staged and have too little free space, try to free cache
            // before giving up.
            pkgLite.recommendedInstallLocation = mPm.freeCacheForInstallation(
                    pkgLite.recommendedInstallLocation, mPackageLite,
                    mOriginInfo.mResolvedPath, mPackageAbiOverride, mInstallFlags);
        }
        mRet = overrideInstallLocation(pkgLite.packageName, pkgLite.recommendedInstallLocation,
                pkgLite.installLocation);
    }

    private void handleReturnCode() {
        processPendingInstall();
    }

    private void processPendingInstall() {
        InstallArgs args = new InstallArgs(this);
        if (mRet == PackageManager.INSTALL_SUCCEEDED) {
            mRet = copyApk(args);
        }
        if (mRet == PackageManager.INSTALL_SUCCEEDED) {
            F2fsUtils.releaseCompressedBlocks(
                    mPm.mContext.getContentResolver(), new File(args.getCodePath()));
        }
        if (mParentInstallingSession != null) {
            mParentInstallingSession.tryProcessInstallRequest(args, mRet);
        } else {
            PackageInstalledInfo res = new PackageInstalledInfo(mRet);
            // Queue up an async operation since the package installation may take a little while.
            mPm.mHandler.post(() -> processInstallRequests(
                    res.mReturnCode == PackageManager.INSTALL_SUCCEEDED /* success */,
                    Collections.singletonList(new InstallRequest(args, res))));
        }
    }

    private int copyApk(InstallArgs args) {
        if (mMoveInfo == null) {
            return copyApkForFileInstall(args);
        } else {
            return copyApkForMoveInstall(args);
        }
    }

    private int copyApkForFileInstall(InstallArgs args) {
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "copyApk");
        try {
            if (mOriginInfo.mStaged) {
                if (DEBUG_INSTALL) {
                    Slog.d(TAG, mOriginInfo.mFile + " already staged; skipping copy");
                }
                args.mCodeFile = mOriginInfo.mFile;
                return PackageManager.INSTALL_SUCCEEDED;
            }

            try {
                final boolean isEphemeral =
                        (mInstallFlags & PackageManager.INSTALL_INSTANT_APP) != 0;
                args.mCodeFile =
                        mPm.mInstallerService.allocateStageDirLegacy(mVolumeUuid, isEphemeral);
            } catch (IOException e) {
                Slog.w(TAG, "Failed to create copy file: " + e);
                return PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
            }

            int ret = PackageManagerServiceUtils.copyPackage(
                    mOriginInfo.mFile.getAbsolutePath(), args.mCodeFile);
            if (ret != PackageManager.INSTALL_SUCCEEDED) {
                Slog.e(TAG, "Failed to copy package");
                return ret;
            }

            final boolean isIncremental = isIncrementalPath(args.mCodeFile.getAbsolutePath());
            final File libraryRoot = new File(args.mCodeFile, LIB_DIR_NAME);
            NativeLibraryHelper.Handle handle = null;
            try {
                handle = NativeLibraryHelper.Handle.create(args.mCodeFile);
                ret = NativeLibraryHelper.copyNativeBinariesWithOverride(handle, libraryRoot,
                        args.mAbiOverride, isIncremental);
            } catch (IOException e) {
                Slog.e(TAG, "Copying native libraries failed", e);
                ret = PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
            } finally {
                IoUtils.closeQuietly(handle);
            }

            return ret;
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    private int copyApkForMoveInstall(InstallArgs args) {
        if (DEBUG_INSTALL) {
            Slog.d(TAG, "Moving " + mMoveInfo.mPackageName + " from "
                    + mMoveInfo.mFromUuid + " to " + mMoveInfo.mToUuid);
        }
        synchronized (mPm.mInstallLock) {
            try {
                mPm.mInstaller.moveCompleteApp(mMoveInfo.mFromUuid, mMoveInfo.mToUuid,
                        mMoveInfo.mPackageName, mMoveInfo.mAppId, mMoveInfo.mSeInfo,
                        mMoveInfo.mTargetSdkVersion, mMoveInfo.mFromCodePath);
            } catch (Installer.InstallerException e) {
                Slog.w(TAG, "Failed to move app", e);
                return PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
            }
        }

        final String toPathName = new File(mMoveInfo.mFromCodePath).getName();
        args.mCodeFile = new File(Environment.getDataAppDirectory(mMoveInfo.mToUuid), toPathName);
        if (DEBUG_INSTALL) Slog.d(TAG, "codeFile after move is " + args.mCodeFile);

        return PackageManager.INSTALL_SUCCEEDED;
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
        if (mPm.snapshotComputer().checkUidPermission(android.Manifest.permission.INSTALL_PACKAGES,
                installerUid) == PERMISSION_GRANTED) {
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
        setTraceMethod("installStage").setTraceCookie(System.identityHashCode(this));
        Trace.asyncTraceBegin(TRACE_TAG_PACKAGE_MANAGER, "installStage",
                System.identityHashCode(this));
        Trace.asyncTraceBegin(TRACE_TAG_PACKAGE_MANAGER, "queueInstall",
                System.identityHashCode(this));
        mPm.mHandler.post(this::start);
    }

    public void installStage(List<InstallingSession> children)
            throws PackageManagerException {
        final MultiPackageInstallingSession installingSession =
                new MultiPackageInstallingSession(getUser(), children, mPm);
        setTraceMethod("installStageMultiPackage").setTraceCookie(System.identityHashCode(
                installingSession));

        Trace.asyncTraceBegin(TRACE_TAG_PACKAGE_MANAGER, "installStageMultiPackage",
                System.identityHashCode(installingSession));
        Trace.asyncTraceBegin(TRACE_TAG_PACKAGE_MANAGER, "queueInstall",
                System.identityHashCode(installingSession));
        mPm.mHandler.post(installingSession::start);
    }

    public void movePackage() {
        setTraceMethod("movePackage").setTraceCookie(System.identityHashCode(this));
        Trace.asyncTraceBegin(TRACE_TAG_PACKAGE_MANAGER, "movePackage",
                System.identityHashCode(this));
        Trace.asyncTraceBegin(TRACE_TAG_PACKAGE_MANAGER, "queueInstall",
                System.identityHashCode(this));
        mPm.mHandler.post(this::start);
    }

    public UserHandle getUser() {
        return mUser;
    }

    private void start() {
        if (DEBUG_INSTALL) Slog.i(TAG, "start " + mUser + ": " + this);
        Trace.asyncTraceEnd(TRACE_TAG_PACKAGE_MANAGER, "queueInstall",
                System.identityHashCode(this));
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "startInstall");
        handleStartCopy();
        handleReturnCode();
        Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
    }

    private InstallingSession setTraceMethod(String traceMethod) {
        mTraceMethod = traceMethod;
        return this;
    }

    private void setTraceCookie(int traceCookie) {
        mTraceCookie = traceCookie;
    }

    private void processInstallRequests(boolean success, List<InstallRequest> installRequests) {
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

        processApkInstallRequests(success, installRequests);
    }

    private void processApkInstallRequests(boolean success, List<InstallRequest> installRequests) {
        if (success) {
            for (InstallRequest request : installRequests) {
                if (request.mInstallResult.mReturnCode != PackageManager.INSTALL_SUCCEEDED) {
                    cleanUpForFailedInstall(request.mArgs);
                }
            }

            mInstallPackageHelper.installPackagesTraced(installRequests);

            for (InstallRequest request : installRequests) {
                doPostInstall(request.mInstallResult.mReturnCode, request.mArgs);
            }
        }
        for (InstallRequest request : installRequests) {
            mInstallPackageHelper.restoreAndPostInstall(request.mArgs.mUser.getIdentifier(),
                    request.mInstallResult,
                    new PostInstallData(request.mArgs,
                            request.mInstallResult, null));
        }
    }

    private void doPostInstall(int status, InstallArgs args) {
        if (mMoveInfo != null) {
            if (status == PackageManager.INSTALL_SUCCEEDED) {
                mRemovePackageHelper.cleanUpForMoveInstall(mMoveInfo.mFromUuid,
                        mMoveInfo.mPackageName, mMoveInfo.mFromCodePath);
            } else {
                mRemovePackageHelper.cleanUpForMoveInstall(mMoveInfo.mToUuid,
                        mMoveInfo.mPackageName, mMoveInfo.mFromCodePath);
            }
        } else {
            if (status != PackageManager.INSTALL_SUCCEEDED) {
                mRemovePackageHelper.removeCodePath(args.mCodeFile);
            }
        }
    }

    private void cleanUpForFailedInstall(InstallArgs args) {
        if (args.mMoveInfo != null) {
            mRemovePackageHelper.cleanUpForMoveInstall(args.mMoveInfo.mToUuid,
                    args.mMoveInfo.mPackageName, args.mMoveInfo.mFromCodePath);
        } else {
            mRemovePackageHelper.removeCodePath(args.mCodeFile);
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
                ApexInfo apexInfo = mPm.mApexManager.installPackage(apexes[0]);
                if (ApexPackageInfo.ENABLE_FEATURE_SCAN_APEX) {
                    // APEX has been handled successfully by apexd. Let's continue the install flow
                    // so it will be scanned and registered with the system.
                    // TODO(b/225756739): Improve atomicity of rebootless APEX install.
                    // The newly installed APEX will not be reverted even if
                    // processApkInstallRequests() fails. Need a way to keep info stored in apexd
                    // and PMS in sync in the face of install failures.
                    request.mInstallResult.mApexInfo = apexInfo;
                    mPm.mHandler.post(() -> processApkInstallRequests(true, requests));
                    return;
                } else {
                    mPm.mApexPackageInfo.notifyPackageInstalled(apexInfo, packageParser);
                }
            }
        } catch (PackageManagerException e) {
            request.mInstallResult.setError("APEX installation failed", e);
        }
        PackageManagerService.invalidatePackageInfoCache();
        mPm.notifyInstallObserver(request.mInstallResult, request.mArgs.mObserver);
    }

    /**
     * Container for a multi-package install which refers to all install sessions and args being
     * committed together.
     */
    private class MultiPackageInstallingSession {
        private final List<InstallingSession> mChildInstallingSessions;
        private final Map<InstallArgs, Integer> mCurrentState;
        @NonNull
        final PackageManagerService mPm;
        final UserHandle mUser;

        MultiPackageInstallingSession(UserHandle user,
                List<InstallingSession> childInstallingSessions,
                PackageManagerService pm)
                throws PackageManagerException {
            if (childInstallingSessions.size() == 0) {
                throw new PackageManagerException("No child sessions found!");
            }
            mPm = pm;
            mUser = user;
            mChildInstallingSessions = childInstallingSessions;
            for (int i = 0; i < childInstallingSessions.size(); i++) {
                final InstallingSession childInstallingSession = childInstallingSessions.get(i);
                childInstallingSession.mParentInstallingSession = this;
            }
            this.mCurrentState = new ArrayMap<>(mChildInstallingSessions.size());
        }

        public void start() {
            if (DEBUG_INSTALL) Slog.i(TAG, "start " + mUser + ": " + this);
            Trace.asyncTraceEnd(TRACE_TAG_PACKAGE_MANAGER, "queueInstall",
                    System.identityHashCode(this));
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "start");
            for (InstallingSession childInstallingSession : mChildInstallingSessions) {
                childInstallingSession.handleStartCopy();
            }
            for (InstallingSession childInstallingSession : mChildInstallingSessions) {
                childInstallingSession.handleReturnCode();
            }
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }

        public void tryProcessInstallRequest(InstallArgs args, int currentStatus) {
            mCurrentState.put(args, currentStatus);
            if (mCurrentState.size() != mChildInstallingSessions.size()) {
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
            int finalCompleteStatus = completeStatus;
            mPm.mHandler.post(() -> processInstallRequests(
                    finalCompleteStatus == PackageManager.INSTALL_SUCCEEDED /* success */,
                    installRequests));
        }

        @Override
        public String toString() {
            return "MultiPackageInstallingSession{" + Integer.toHexString(
                    System.identityHashCode(this))
                    + "}";
        }
    }
}
