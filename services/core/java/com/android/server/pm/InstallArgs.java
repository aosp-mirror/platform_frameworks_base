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
import android.annotation.Nullable;
import android.content.pm.IPackageInstallObserver2;
import android.content.pm.PackageManager;
import android.content.pm.SigningDetails;
import android.os.UserHandle;

import com.android.internal.util.Preconditions;
import com.android.server.pm.parsing.pkg.ParsedPackage;

import java.util.List;

abstract class InstallArgs {
    /** @see InstallParams#mOriginInfo */
    final OriginInfo mOriginInfo;
    /** @see InstallParams#mMoveInfo */
    final MoveInfo mMoveInfo;

    final IPackageInstallObserver2 mObserver;
    // Always refers to PackageManager flags only
    final int mInstallFlags;
    @NonNull
    final InstallSource mInstallSource;
    final String mVolumeUuid;
    final UserHandle mUser;
    final String mAbiOverride;
    final String[] mInstallGrantPermissions;
    final List<String> mAllowlistedRestrictedPermissions;
    final int mAutoRevokePermissionsMode;
    /** If non-null, drop an async trace when the install completes */
    final String mTraceMethod;
    final int mTraceCookie;
    final SigningDetails mSigningDetails;
    final int mInstallReason;
    final int mInstallScenario;
    final boolean mForceQueryableOverride;
    final int mDataLoaderType;
    final int mPackageSource;

    // The list of instruction sets supported by this app. This is currently
    // only used during the rmdex() phase to clean up resources. We can get rid of this
    // if we move dex files under the common app path.
    @Nullable String[] mInstructionSets;

    @NonNull final PackageManagerService mPm;
    @NonNull final RemovePackageHelper mRemovePackageHelper;

    InstallArgs(OriginInfo originInfo, MoveInfo moveInfo, IPackageInstallObserver2 observer,
            int installFlags, InstallSource installSource, String volumeUuid,
            UserHandle user, String[] instructionSets,
            String abiOverride, String[] installGrantPermissions,
            List<String> allowlistedRestrictedPermissions,
            int autoRevokePermissionsMode,
            String traceMethod, int traceCookie, SigningDetails signingDetails,
            int installReason, int installScenario, boolean forceQueryableOverride,
            int dataLoaderType, int packageSource, PackageManagerService pm) {
        mOriginInfo = originInfo;
        mMoveInfo = moveInfo;
        mInstallFlags = installFlags;
        mObserver = observer;
        mInstallSource = Preconditions.checkNotNull(installSource);
        mVolumeUuid = volumeUuid;
        mUser = user;
        mInstructionSets = instructionSets;
        mAbiOverride = abiOverride;
        mInstallGrantPermissions = installGrantPermissions;
        mAllowlistedRestrictedPermissions = allowlistedRestrictedPermissions;
        mAutoRevokePermissionsMode = autoRevokePermissionsMode;
        mTraceMethod = traceMethod;
        mTraceCookie = traceCookie;
        mSigningDetails = signingDetails;
        mInstallReason = installReason;
        mInstallScenario = installScenario;
        mForceQueryableOverride = forceQueryableOverride;
        mDataLoaderType = dataLoaderType;
        mPackageSource = packageSource;
        mPm = pm;
        mRemovePackageHelper = new RemovePackageHelper(mPm);
    }

    /** New install */
    InstallArgs(InstallParams params) {
        this(params.mOriginInfo, params.mMoveInfo, params.mObserver, params.mInstallFlags,
                params.mInstallSource, params.mVolumeUuid,
                params.getUser(), null /*instructionSets*/, params.mPackageAbiOverride,
                params.mGrantedRuntimePermissions, params.mAllowlistedRestrictedPermissions,
                params.mAutoRevokePermissionsMode,
                params.mTraceMethod, params.mTraceCookie, params.mSigningDetails,
                params.mInstallReason, params.mInstallScenario, params.mForceQueryableOverride,
                params.mDataLoaderType, params.mPackageSource, params.mPm);
    }

    abstract int copyApk();
    abstract int doPreInstall(int status);

    /**
     * Rename package into final resting place. All paths on the given
     * scanned package should be updated to reflect the rename.
     */
    abstract boolean doRename(int status, ParsedPackage parsedPackage);
    abstract int doPostInstall(int status, int uid);

    /** @see PackageSettingBase#getPath() */
    abstract String getCodePath();

    // Need installer lock especially for dex file removal.
    abstract void cleanUpResourcesLI();
    abstract boolean doPostDeleteLI(boolean delete);

    /**
     * Called before the source arguments are copied. This is used mostly
     * for MoveParams when it needs to read the source file to put it in the
     * destination.
     */
    int doPreCopy() {
        return PackageManager.INSTALL_SUCCEEDED;
    }

    /**
     * Called after the source arguments are copied. This is used mostly for
     * MoveParams when it needs to read the source file to put it in the
     * destination.
     */
    int doPostCopy(int uid) {
        return PackageManager.INSTALL_SUCCEEDED;
    }

    protected boolean isEphemeral() {
        return (mInstallFlags & PackageManager.INSTALL_INSTANT_APP) != 0;
    }

    UserHandle getUser() {
        return mUser;
    }
}
