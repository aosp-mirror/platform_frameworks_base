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
import static android.content.pm.PackageManager.INSTALL_STAGED;
import static android.content.pm.PackageManager.INSTALL_SUCCEEDED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;

import static com.android.server.pm.PackageManagerService.DEBUG_INSTANT;
import static com.android.server.pm.PackageManagerService.INIT_COPY;
import static com.android.server.pm.PackageManagerService.TAG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.DataLoaderType;
import android.content.pm.IPackageInstallObserver2;
import android.content.pm.PackageInfoLite;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.SigningDetails;
import android.content.pm.parsing.PackageLite;
import android.os.Message;
import android.os.Trace;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.content.F2fsUtils;
import com.android.internal.content.InstallLocationUtils;
import com.android.internal.util.Preconditions;
import com.android.server.pm.parsing.pkg.AndroidPackage;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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
    final int mPackageSource;
    final PackageLite mPackageLite;

    InstallParams(OriginInfo originInfo, MoveInfo moveInfo, IPackageInstallObserver2 observer,
            int installFlags, InstallSource installSource, String volumeUuid,
            UserHandle user, String packageAbiOverride, int packageSource,
            PackageLite packageLite, PackageManagerService pm) {
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
        mPackageSource = packageSource;
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
        mPackageSource = sessionParams.packageSource;
        mPackageLite = packageLite;
    }

    @Override
    public String toString() {
        return "InstallParams{" + Integer.toHexString(System.identityHashCode(this))
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
        synchronized (mPm.mLock) {
            // reader
            AndroidPackage installedPkg = mPm.mPackages.get(packageName);
            if (installedPkg != null) {
                // Currently installed package which the new package is attempting to replace
                recommendedInstallLocation = InstallLocationUtils.installLocationPolicy(
                        installLocation, recommendedInstallLocation, mInstallFlags,
                        installedPkg.isSystem(), installedPkg.isExternalStorage());
            }
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
            mInstallPackageHelper.processInstallRequests(success, installRequests);
        });
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
