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

import static android.content.pm.PackageManager.INSTALL_REASON_UNKNOWN;
import static android.content.pm.PackageManager.INSTALL_SCENARIO_DEFAULT;

import static com.android.server.pm.PackageManagerService.TAG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.apex.ApexInfo;
import android.app.AppOpsManager;
import android.content.pm.DataLoaderType;
import android.content.pm.IPackageInstallObserver2;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.SigningDetails;
import android.net.Uri;
import android.os.UserHandle;
import android.util.ExceptionUtils;
import android.util.Slog;

import com.android.server.pm.pkg.AndroidPackage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

final class InstallRequest {
    private final int mUserId;
    @Nullable
    private final InstallArgs mInstallArgs;
    @NonNull
    private final PackageInstalledInfo mInstalledInfo;
    @Nullable
    private Runnable mPostInstallRunnable;
    @Nullable
    private PackageRemovedInfo mRemovedInfo;

    // New install
    InstallRequest(InstallingSession params) {
        mUserId = params.getUser().getIdentifier();
        mInstallArgs = new InstallArgs(params.mOriginInfo, params.mMoveInfo, params.mObserver,
                params.mInstallFlags, params.mInstallSource, params.mVolumeUuid,
                params.getUser(), null /*instructionSets*/, params.mPackageAbiOverride,
                params.mGrantedRuntimePermissions, params.mAllowlistedRestrictedPermissions,
                params.mAutoRevokePermissionsMode,
                params.mTraceMethod, params.mTraceCookie, params.mSigningDetails,
                params.mInstallReason, params.mInstallScenario, params.mForceQueryableOverride,
                params.mDataLoaderType, params.mPackageSource);
        mInstalledInfo = new PackageInstalledInfo();
    }

    // Install existing package as user
    InstallRequest(int userId, int returnCode, AndroidPackage pkg, int[] newUsers,
            Runnable runnable) {
        mUserId = userId;
        mInstallArgs = null;
        mInstalledInfo = new PackageInstalledInfo();
        mInstalledInfo.mReturnCode = returnCode;
        mInstalledInfo.mPkg = pkg;
        mInstalledInfo.mNewUsers = newUsers;
        mPostInstallRunnable = runnable;
    }

    private static class PackageInstalledInfo {
        String mName;
        int mUid = -1;
        // The set of users that originally had this package installed.
        int[] mOrigUsers;
        // The set of users that now have this package installed.
        int[] mNewUsers;
        AndroidPackage mPkg;
        int mReturnCode;
        String mReturnMsg;
        String mInstallerPackageName;
        // The set of packages consuming this shared library or null if no consumers exist.
        ArrayList<AndroidPackage> mLibraryConsumers;
        PackageFreezer mFreezer;
        // In some error cases we want to convey more info back to the observer
        String mOrigPackage;
        String mOrigPermission;
        // The ApexInfo returned by ApexManager#installPackage, used by rebootless APEX install
        ApexInfo mApexInfo;
    }

    public String getName() {
        return mInstalledInfo.mName;
    }

    public String getReturnMsg() {
        return mInstalledInfo.mReturnMsg;
    }

    public OriginInfo getOriginInfo() {
        return mInstallArgs == null ? null : mInstallArgs.mOriginInfo;
    }

    public PackageRemovedInfo getRemovedInfo() {
        return mRemovedInfo;
    }

    public String getOrigPackage() {
        return mInstalledInfo.mOrigPackage;
    }

    public String getOrigPermission() {
        return mInstalledInfo.mOrigPermission;
    }

    @Nullable
    public File getCodeFile() {
        return mInstallArgs == null ? null : mInstallArgs.mCodeFile;
    }

    @Nullable
    public String getCodePath() {
        return (mInstallArgs != null && mInstallArgs.mCodeFile != null)
                ? mInstallArgs.mCodeFile.getAbsolutePath() : null;
    }

    @Nullable
    public String getAbiOverride() {
        return mInstallArgs == null ? null : mInstallArgs.mAbiOverride;
    }

    public int getReturnCode() {
        return mInstalledInfo.mReturnCode;
    }

    @Nullable
    public IPackageInstallObserver2 getObserver() {
        return mInstallArgs == null ? null : mInstallArgs.mObserver;
    }

    public boolean isMoveInstall() {
        return mInstallArgs != null && mInstallArgs.mMoveInfo != null;
    }

    @Nullable
    public String getMoveToUuid() {
        return (mInstallArgs != null && mInstallArgs.mMoveInfo != null)
                ? mInstallArgs.mMoveInfo.mToUuid : null;
    }

    @Nullable
    public String getMovePackageName() {
        return  (mInstallArgs != null && mInstallArgs.mMoveInfo != null)
                ? mInstallArgs.mMoveInfo.mPackageName : null;
    }

    @Nullable
    public String getMoveFromCodePath() {
        return  (mInstallArgs != null && mInstallArgs.mMoveInfo != null)
                ? mInstallArgs.mMoveInfo.mFromCodePath : null;
    }

    @Nullable
    public File getOldCodeFile() {
        return (mRemovedInfo != null && mRemovedInfo.mArgs != null)
                ? mRemovedInfo.mArgs.mCodeFile : null;
    }

    @Nullable
    public String[] getOldInstructionSet() {
        return (mRemovedInfo != null && mRemovedInfo.mArgs != null)
                ? mRemovedInfo.mArgs.mInstructionSets : null;
    }

    public UserHandle getUser() {
        return new UserHandle(mUserId);
    }

    public int getUserId() {
        return mUserId;
    }

    public int getInstallFlags() {
        return mInstallArgs == null ? 0 : mInstallArgs.mInstallFlags;
    }

    public int getInstallReason() {
        return mInstallArgs == null ? INSTALL_REASON_UNKNOWN : mInstallArgs.mInstallReason;
    }

    @Nullable
    public String getVolumeUuid() {
        return mInstallArgs == null ? null : mInstallArgs.mVolumeUuid;
    }

    public AndroidPackage getPkg() {
        return mInstalledInfo.mPkg;
    }

    @Nullable
    public String getTraceMethod() {
        return mInstallArgs == null ? null : mInstallArgs.mTraceMethod;
    }

    public int getTraceCookie() {
        return mInstallArgs == null ? 0 : mInstallArgs.mTraceCookie;
    }

    public boolean isUpdate() {
        return mRemovedInfo != null && mRemovedInfo.mRemovedPackage != null;
    }

    @Nullable
    public String getRemovedPackage() {
        return mRemovedInfo != null ? mRemovedInfo.mRemovedPackage : null;
    }

    public boolean isInstallForExistingUser() {
        return mInstallArgs == null;
    }

    @Nullable
    public InstallSource getInstallSource() {
        return mInstallArgs == null ? null : mInstallArgs.mInstallSource;
    }

    @Nullable
    public String getInstallerPackageName() {
        return (mInstallArgs != null && mInstallArgs.mInstallSource != null)
                ? mInstallArgs.mInstallSource.installerPackageName : null;
    }

    public int getDataLoaderType() {
        return mInstallArgs == null ? DataLoaderType.NONE : mInstallArgs.mDataLoaderType;
    }

    public int getSignatureSchemeVersion() {
        return mInstallArgs == null ? SigningDetails.SignatureSchemeVersion.UNKNOWN
                : mInstallArgs.mSigningDetails.getSignatureSchemeVersion();
    }

    @NonNull
    public SigningDetails getSigningDetails() {
        return mInstallArgs == null ? SigningDetails.UNKNOWN : mInstallArgs.mSigningDetails;
    }

    @Nullable
    public Uri getOriginUri() {
        return mInstallArgs == null ?  null : Uri.fromFile(mInstallArgs.mOriginInfo.mResolvedFile);
    }

    public ApexInfo getApexInfo() {
        return mInstalledInfo.mApexInfo;
    }

    public String getSourceInstallerPackageName() {
        return mInstallArgs.mInstallSource.installerPackageName;
    }

    public boolean isRollback() {
        return mInstallArgs != null
                && mInstallArgs.mInstallReason == PackageManager.INSTALL_REASON_ROLLBACK;
    }

    public int[] getNewUsers() {
        return mInstalledInfo.mNewUsers;
    }

    public int[] getOriginUsers() {
        return mInstalledInfo.mOrigUsers;
    }

    public int getUid() {
        return mInstalledInfo.mUid;
    }

    @Nullable
    public String[] getInstallGrantPermissions() {
        return mInstallArgs == null ?  null : mInstallArgs.mInstallGrantPermissions;
    }

    public ArrayList<AndroidPackage> getLibraryConsumers() {
        return mInstalledInfo.mLibraryConsumers;
    }

    @Nullable
    public List<String> getAllowlistedRestrictedPermissions() {
        return mInstallArgs == null ? null : mInstallArgs.mAllowlistedRestrictedPermissions;
    }

    public int getAutoRevokePermissionsMode() {
        return mInstallArgs == null
                ? AppOpsManager.MODE_DEFAULT : mInstallArgs.mAutoRevokePermissionsMode;
    }

    public int getPackageSource() {
        return mInstallArgs == null
                ? PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED : mInstallArgs.mPackageSource;
    }

    public int getInstallScenario() {
        return mInstallArgs == null ? INSTALL_SCENARIO_DEFAULT : mInstallArgs.mInstallScenario;
    }

    public boolean isForceQueryableOverride() {
        return mInstallArgs != null && mInstallArgs.mForceQueryableOverride;
    }

    public void closeFreezer() {
        if (mInstalledInfo.mFreezer != null) {
            mInstalledInfo.mFreezer.close();
        }
    }

    public void runPostInstallRunnable() {
        if (mPostInstallRunnable != null) {
            mPostInstallRunnable.run();
        }
    }

    public void setCodeFile(File codeFile) {
        if (mInstallArgs != null) {
            mInstallArgs.mCodeFile = codeFile;
        }
    }

    public void setError(int code, String msg) {
        setReturnCode(code);
        setReturnMessage(msg);
        Slog.w(TAG, msg);
    }

    public void setError(String msg, PackageManagerException e) {
        mInstalledInfo.mReturnCode = e.error;
        setReturnMessage(ExceptionUtils.getCompleteMessage(msg, e));
        Slog.w(TAG, msg, e);
    }

    public void setReturnCode(int returnCode) {
        mInstalledInfo.mReturnCode = returnCode;
    }

    public void setReturnMessage(String returnMsg) {
        mInstalledInfo.mReturnMsg = returnMsg;
    }

    public void setApexInfo(ApexInfo apexInfo) {
        mInstalledInfo.mApexInfo = apexInfo;
    }

    public void setPkg(AndroidPackage pkg) {
        mInstalledInfo.mPkg = pkg;
    }

    public void setUid(int uid) {
        mInstalledInfo.mUid = uid;
    }

    public void setNewUsers(int[] newUsers) {
        mInstalledInfo.mNewUsers = newUsers;
    }

    public void setOriginPackage(String originPackage) {
        mInstalledInfo.mOrigPackage = originPackage;
    }

    public void setOriginPermission(String originPermission) {
        mInstalledInfo.mOrigPermission = originPermission;
    }

    public void setInstallerPackageName(String installerPackageName) {
        mInstalledInfo.mInstallerPackageName = installerPackageName;
    }

    public void setName(String packageName) {
        mInstalledInfo.mName = packageName;
    }

    public void setOriginUsers(int[] userIds) {
        mInstalledInfo.mOrigUsers = userIds;
    }

    public void setFreezer(PackageFreezer freezer) {
        mInstalledInfo.mFreezer = freezer;
    }

    public void setRemovedInfo(PackageRemovedInfo removedInfo) {
        mRemovedInfo = removedInfo;
    }

    public void setLibraryConsumers(ArrayList<AndroidPackage> libraryConsumers) {
        mInstalledInfo.mLibraryConsumers = libraryConsumers;
    }
}
