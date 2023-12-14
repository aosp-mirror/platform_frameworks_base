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
import android.content.pm.SigningDetails;
import android.os.UserHandle;
import android.util.ArrayMap;

import com.android.internal.util.Preconditions;

import java.io.File;
import java.util.List;

final class InstallArgs {
    File mCodeFile;
    /** @see InstallingSession#mOriginInfo */
    final OriginInfo mOriginInfo;
    /** @see InstallingSession#mMoveInfo */
    final MoveInfo mMoveInfo;

    final IPackageInstallObserver2 mObserver;
    // Always refers to PackageManager flags only
    final int mInstallFlags;
    final int mDevelopmentInstallFlags;
    @NonNull
    final InstallSource mInstallSource;
    final String mVolumeUuid;
    final UserHandle mUser;
    final String mAbiOverride;
    @NonNull
    final ArrayMap<String, Integer> mPermissionStates;
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
    final boolean mApplicationEnabledSettingPersistent;

    // The list of instruction sets supported by this app. This is currently
    // only used during the rmdex() phase to clean up resources. We can get rid of this
    // if we move dex files under the common app path.
    @Nullable
    final String[] mInstructionSets;

    InstallArgs(OriginInfo originInfo, MoveInfo moveInfo, IPackageInstallObserver2 observer,
            int installFlags, int developmentInstallFlags, InstallSource installSource,
            String volumeUuid,  UserHandle user, String[] instructionSets, String abiOverride,
            @NonNull ArrayMap<String, Integer> permissionStates,
            List<String> allowlistedRestrictedPermissions,
            int autoRevokePermissionsMode, String traceMethod, int traceCookie,
            SigningDetails signingDetails, int installReason, int installScenario,
            boolean forceQueryableOverride, int dataLoaderType, int packageSource,
            boolean applicationEnabledSettingPersistent) {
        mOriginInfo = originInfo;
        mMoveInfo = moveInfo;
        mInstallFlags = installFlags;
        mDevelopmentInstallFlags = developmentInstallFlags;
        mObserver = observer;
        mInstallSource = Preconditions.checkNotNull(installSource);
        mVolumeUuid = volumeUuid;
        mUser = user;
        mInstructionSets = instructionSets;
        mAbiOverride = abiOverride;
        mPermissionStates = permissionStates;
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
        mApplicationEnabledSettingPersistent = applicationEnabledSettingPersistent;
    }
}
