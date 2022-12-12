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
import static android.content.pm.PackageInstaller.SessionParams.MODE_INHERIT_EXISTING;
import static android.content.pm.PackageInstaller.SessionParams.USER_ACTION_UNSPECIFIED;
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
import android.util.ArraySet;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.content.F2fsUtils;
import com.android.internal.content.InstallLocationUtils;
import com.android.internal.content.NativeLibraryHelper;
import com.android.internal.util.Preconditions;
import com.android.server.pm.parsing.PackageParser2;

import libcore.io.IoUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

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
    final boolean mIsInherit;
    final int mSessionId;
    final int mRequireUserAction;
    final boolean mApplicationEnabledSettingPersistent;

    // For move install
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
        mIsInherit = false;
        mSessionId = -1;
        mRequireUserAction = USER_ACTION_UNSPECIFIED;
        mApplicationEnabledSettingPersistent = false;
    }

    InstallingSession(int sessionId, File stagedDir, IPackageInstallObserver2 observer,
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
                installSource.mInstallerPackageName, installerUid, sessionParams.installReason);
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
        mIsInherit = sessionParams.mode == MODE_INHERIT_EXISTING;
        mSessionId = sessionId;
        mRequireUserAction = sessionParams.requireUserAction;
        mApplicationEnabledSettingPersistent = sessionParams.applicationEnabledSettingPersistent;
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
        var installedPkgState = snapshot.getPackageStateInternal(packageName);
        var installedPkg = installedPkgState == null ? null : installedPkgState.getAndroidPackage();
        if (installedPkg != null) {
            // Currently installed package which the new package is attempting to replace
            recommendedInstallLocation = InstallLocationUtils.installLocationPolicy(
                    installLocation, recommendedInstallLocation, mInstallFlags,
                    installedPkgState.isSystem(), installedPkg.isExternalStorage());
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
    private void handleStartCopy(InstallRequest request) {
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
                request.setError(mRet, "Failed to verify version code");
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
        if (mRet != INSTALL_SUCCEEDED) {
            request.setError(mRet, "Failed to override installation location");
        }
    }

    private void handleReturnCode(InstallRequest installRequest) {
        processPendingInstall(installRequest);
    }

    private void processPendingInstall(InstallRequest installRequest) {
        if (mRet == PackageManager.INSTALL_SUCCEEDED) {
            mRet = copyApk(installRequest);
        }
        if (mRet == PackageManager.INSTALL_SUCCEEDED) {
            F2fsUtils.releaseCompressedBlocks(
                    mPm.mContext.getContentResolver(), new File(installRequest.getCodePath()));
        }
        installRequest.setReturnCode(mRet);
        if (mParentInstallingSession != null) {
            mParentInstallingSession.tryProcessInstallRequest(installRequest);
        } else {
            // Queue up an async operation since the package installation may take a little while.
            mPm.mHandler.post(() -> processInstallRequests(
                    mRet == PackageManager.INSTALL_SUCCEEDED /* success */,
                    Collections.singletonList(installRequest)));
        }
    }

    private int copyApk(InstallRequest request) {
        if (mMoveInfo == null) {
            return copyApkForFileInstall(request);
        } else {
            return copyApkForMoveInstall(request);
        }
    }

    private int copyApkForFileInstall(InstallRequest request) {
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "copyApk");
        try {
            if (mOriginInfo.mStaged) {
                if (DEBUG_INSTALL) {
                    Slog.d(TAG, mOriginInfo.mFile + " already staged; skipping copy");
                }
                request.setCodeFile(mOriginInfo.mFile);
                return PackageManager.INSTALL_SUCCEEDED;
            }
            int ret;
            try {
                final boolean isEphemeral =
                        (mInstallFlags & PackageManager.INSTALL_INSTANT_APP) != 0;
                request.setCodeFile(
                        mPm.mInstallerService.allocateStageDirLegacy(mVolumeUuid, isEphemeral));
            } catch (IOException e) {
                final String errorMessage = "Failed to create copy file";
                Slog.w(TAG, errorMessage + ": " + e);
                ret = PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
                request.setError(ret, errorMessage);
                return ret;
            }

            ret = PackageManagerServiceUtils.copyPackage(
                    mOriginInfo.mFile.getAbsolutePath(), request.getCodeFile());
            if (ret != PackageManager.INSTALL_SUCCEEDED) {
                final String errorMessage = "Failed to copy package";
                Slog.e(TAG, errorMessage);
                request.setError(ret, errorMessage);
                return ret;
            }

            final boolean isIncremental = isIncrementalPath(
                    request.getCodeFile().getAbsolutePath());
            final File libraryRoot = new File(request.getCodeFile(), LIB_DIR_NAME);
            NativeLibraryHelper.Handle handle = null;
            try {
                handle = NativeLibraryHelper.Handle.create(request.getCodeFile());
                ret = NativeLibraryHelper.copyNativeBinariesWithOverride(handle, libraryRoot,
                        request.getAbiOverride(), isIncremental);
            } catch (IOException e) {
                final String errorMessage = "Copying native libraries failed";
                Slog.e(TAG, errorMessage, e);
                ret = PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
                request.setError(ret, errorMessage);
            } finally {
                IoUtils.closeQuietly(handle);
            }

            return ret;
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    private int copyApkForMoveInstall(InstallRequest request) {
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
                final String errorMessage = "Failed to move app";
                final int ret = PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
                request.setError(ret, errorMessage);
                Slog.w(TAG, errorMessage, e);
                return ret;
            }
        }

        final String toPathName = new File(mMoveInfo.mFromCodePath).getName();
        request.setCodeFile(
                new File(Environment.getDataAppDirectory(mMoveInfo.mToUuid), toPathName));
        if (DEBUG_INSTALL) Slog.d(TAG, "codeFile after move is " + request.getCodeFile());

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
        InstallRequest installRequest = new InstallRequest(this);
        handleStartCopy(installRequest);
        handleReturnCode(installRequest);
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
            if ((request.getInstallFlags() & PackageManager.INSTALL_APEX) != 0) {
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
                mPm.notifyInstallObserver(request);
            }
            return;
        }

        processApkInstallRequests(success, installRequests);
    }

    private void processApkInstallRequests(boolean success, List<InstallRequest> installRequests) {
        if (success) {
            for (InstallRequest request : installRequests) {
                if (request.getReturnCode() != PackageManager.INSTALL_SUCCEEDED) {
                    cleanUpForFailedInstall(request);
                }
            }

            mInstallPackageHelper.installPackagesTraced(installRequests);

            for (InstallRequest request : installRequests) {
                request.onInstallCompleted(mUser.getIdentifier());
                doPostInstall(request);
            }
        }
        for (InstallRequest request : installRequests) {
            mInstallPackageHelper.restoreAndPostInstall(request);
        }
    }

    private void doPostInstall(InstallRequest request) {
        if (mMoveInfo != null) {
            if (request.getReturnCode() == PackageManager.INSTALL_SUCCEEDED) {
                mRemovePackageHelper.cleanUpForMoveInstall(mMoveInfo.mFromUuid,
                        mMoveInfo.mPackageName, mMoveInfo.mFromCodePath);
            } else {
                mRemovePackageHelper.cleanUpForMoveInstall(mMoveInfo.mToUuid,
                        mMoveInfo.mPackageName, mMoveInfo.mFromCodePath);
            }
        } else {
            if (request.getReturnCode() != PackageManager.INSTALL_SUCCEEDED) {
                mRemovePackageHelper.removeCodePath(request.getCodeFile());
            }
        }
    }

    private void cleanUpForFailedInstall(InstallRequest request) {
        if (request.isInstallMove()) {
            mRemovePackageHelper.cleanUpForMoveInstall(request.getMoveToUuid(),
                    request.getMovePackageName(), request.getMoveFromCodePath());
        } else {
            mRemovePackageHelper.removeCodePath(request.getCodeFile());
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
            final File dir = request.getOriginInfo().mResolvedFile;
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
                // APEX has been handled successfully by apexd. Let's continue the install flow
                // so it will be scanned and registered with the system.
                // TODO(b/225756739): Improve atomicity of rebootless APEX install.
                // The newly installed APEX will not be reverted even if
                // processApkInstallRequests() fails. Need a way to keep info stored in apexd
                // and PMS in sync in the face of install failures.
                request.setApexInfo(apexInfo);
                mPm.mHandler.post(() -> processApkInstallRequests(true, requests));
                return;
            }
        } catch (PackageManagerException e) {
            request.setError("APEX installation failed", e);
        }
        PackageManagerService.invalidatePackageInfoCache();
        mPm.notifyInstallObserver(request);
    }

    /**
     * Container for a multi-package install which refers to all install sessions and args being
     * committed together.
     */
    private class MultiPackageInstallingSession {
        private final List<InstallingSession> mChildInstallingSessions;
        private final Set<InstallRequest> mCurrentInstallRequests;
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
            mCurrentInstallRequests = new ArraySet<>(mChildInstallingSessions.size());
        }

        public void start() {
            if (DEBUG_INSTALL) Slog.i(TAG, "start " + mUser + ": " + this);
            Trace.asyncTraceEnd(TRACE_TAG_PACKAGE_MANAGER, "queueInstall",
                    System.identityHashCode(this));
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "start");

            final int numChildSessions = mChildInstallingSessions.size();
            final ArrayList<InstallRequest> installRequests = new ArrayList<>(numChildSessions);

            for (int i = 0; i < numChildSessions; i++) {
                final InstallingSession childSession = mChildInstallingSessions.get(i);
                final InstallRequest installRequest = new InstallRequest(childSession);
                installRequests.add(installRequest);
                childSession.handleStartCopy(installRequest);
            }
            for (int i = 0; i < numChildSessions; i++) {
                mChildInstallingSessions.get(i).handleReturnCode(installRequests.get(i));
            }
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }

        public void tryProcessInstallRequest(InstallRequest request) {
            mCurrentInstallRequests.add(request);
            if (mCurrentInstallRequests.size() != mChildInstallingSessions.size()) {
                // Wait until all the installRequests have finished copying
                return;
            }
            int completeStatus = PackageManager.INSTALL_SUCCEEDED;
            for (InstallRequest installRequest : mCurrentInstallRequests) {
                if (installRequest.getReturnCode() == PackageManager.INSTALL_UNKNOWN) {
                    return;
                } else if (installRequest.getReturnCode() != PackageManager.INSTALL_SUCCEEDED) {
                    completeStatus = installRequest.getReturnCode();
                    break;
                }
            }
            final List<InstallRequest> installRequests = new ArrayList<>(
                    mCurrentInstallRequests.size());
            for (InstallRequest installRequest : mCurrentInstallRequests) {
                installRequest.setReturnCode(completeStatus);
                installRequests.add(installRequest);
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
