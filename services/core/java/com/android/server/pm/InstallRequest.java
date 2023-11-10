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

import static android.content.pm.Flags.improveInstallFreeze;
import static android.content.pm.PackageInstaller.SessionParams.USER_ACTION_UNSPECIFIED;
import static android.content.pm.PackageManager.INSTALL_REASON_UNKNOWN;
import static android.content.pm.PackageManager.INSTALL_SCENARIO_DEFAULT;
import static android.content.pm.PackageManager.INSTALL_SUCCEEDED;
import static android.os.Process.INVALID_UID;

import static com.android.server.art.model.DexoptResult.DexContainerFileDexoptResult;
import static com.android.server.art.model.DexoptResult.PackageDexoptResult;
import static com.android.server.pm.PackageManagerService.EMPTY_INT_ARRAY;
import static com.android.server.pm.PackageManagerService.SCAN_AS_INSTANT_APP;
import static com.android.server.pm.PackageManagerService.TAG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.apex.ApexInfo;
import android.app.AppOpsManager;
import android.content.pm.ArchivedPackageParcel;
import android.content.pm.DataLoaderType;
import android.content.pm.Flags;
import android.content.pm.IPackageInstallObserver2;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.SigningDetails;
import android.content.pm.parsing.PackageLite;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ExceptionUtils;
import android.util.Slog;

import com.android.internal.util.ArrayUtils;
import com.android.server.art.model.DexoptResult;
import com.android.server.pm.parsing.pkg.ParsedPackage;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageState;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.parsing.ParsingPackageUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

final class InstallRequest {
    private final int mUserId;
    @Nullable
    private final InstallArgs mInstallArgs;
    @Nullable
    private Runnable mPostInstallRunnable;
    @Nullable
    private PackageRemovedInfo mRemovedInfo;

    @PackageManagerService.ScanFlags
    private int mScanFlags;
    @ParsingPackageUtils.ParseFlags
    private int mParseFlags;
    private boolean mReplace;

    @Nullable /* The original package's name if it is being replaced, otherwise {@code null} */
    private String mExistingPackageName;
    /** parsed package to be scanned */
    @Nullable
    private ParsedPackage mParsedPackage;
    @Nullable
    private ArchivedPackageParcel mArchivedPackage;
    private boolean mClearCodeCache;
    private boolean mSystem;
    @Nullable
    private PackageSetting mOriginalPs;
    @Nullable
    private PackageSetting mDisabledPs;

    /** Package Installed Info */
    @Nullable
    private String mName;
    private int mAppId = INVALID_UID;
    // The set of users that originally had this package installed.
    @Nullable
    private int[] mOrigUsers;
    // The set of users that now have this package installed.
    @Nullable
    private int[] mNewUsers;
    @Nullable
    private AndroidPackage mPkg;
    @Nullable
    private PackageLite mPackageLite;
    private int mReturnCode;
    private int mInternalErrorCode;
    @Nullable
    private String mReturnMsg;
    // The set of packages consuming this shared library or null if no consumers exist.
    @Nullable
    private ArrayList<AndroidPackage> mLibraryConsumers;
    @Nullable
    private PackageFreezer mFreezer;
    /** The package this package replaces */
    @Nullable
    private String mOrigPackage;
    @Nullable
    private String mOrigPermission;
    // The ApexInfo returned by ApexManager#installPackage, used by rebootless APEX install
    @Nullable
    private ApexInfo mApexInfo;

    /**
     * For tracking {@link PackageState#getApexModuleName()}.
     */
    @Nullable
    private String mApexModuleName;

    @Nullable
    private ScanResult mScanResult;

    private boolean mIsInstallInherit;
    private boolean mIsInstallForUsers;

    @Nullable
    private final PackageMetrics mPackageMetrics;
    private final int mSessionId;
    private final int mRequireUserAction;

    private int mDexoptStatus;

    @NonNull
    private int[] mFirstTimeBroadcastUserIds = EMPTY_INT_ARRAY;
    @NonNull
    private int[] mFirstTimeBroadcastInstantUserIds = EMPTY_INT_ARRAY;
    @NonNull
    private int[] mUpdateBroadcastUserIds = EMPTY_INT_ARRAY;
    @NonNull
    private int[] mUpdateBroadcastInstantUserIds = EMPTY_INT_ARRAY;

    @NonNull
    private final ArrayList<String> mWarnings = new ArrayList<>();

    // New install
    InstallRequest(InstallingSession params) {
        mUserId = params.getUser().getIdentifier();
        mInstallArgs = new InstallArgs(params.mOriginInfo, params.mMoveInfo, params.mObserver,
                params.mInstallFlags, params.mDevelopmentInstallFlags, params.mInstallSource,
                params.mVolumeUuid,  params.getUser(), null /*instructionSets*/,
                params.mPackageAbiOverride, params.mPermissionStates,
                params.mAllowlistedRestrictedPermissions, params.mAutoRevokePermissionsMode,
                params.mTraceMethod, params.mTraceCookie, params.mSigningDetails,
                params.mInstallReason, params.mInstallScenario, params.mForceQueryableOverride,
                params.mDataLoaderType, params.mPackageSource,
                params.mApplicationEnabledSettingPersistent);
        mPackageLite = params.mPackageLite;
        mPackageMetrics = new PackageMetrics(this);
        mIsInstallInherit = params.mIsInherit;
        mSessionId = params.mSessionId;
        mRequireUserAction = params.mRequireUserAction;
    }

    // Install existing package as user
    InstallRequest(int userId, int returnCode, AndroidPackage pkg, int[] newUsers,
            Runnable runnable) {
        mUserId = userId;
        mInstallArgs = null;
        mReturnCode = returnCode;
        mPkg = pkg;
        mNewUsers = newUsers;
        mPostInstallRunnable = runnable;
        mPackageMetrics = new PackageMetrics(this);
        mIsInstallForUsers = true;
        mSessionId = -1;
        mRequireUserAction = USER_ACTION_UNSPECIFIED;
    }

    // addForInit
    InstallRequest(ParsedPackage parsedPackage, int parseFlags, int scanFlags,
            @Nullable UserHandle user, ScanResult scanResult, PackageSetting disabledPs) {
        if (user != null) {
            mUserId = user.getIdentifier();
        } else {
            // APEX
            mUserId = UserHandle.USER_SYSTEM;
        }
        mInstallArgs = null;
        mParsedPackage = parsedPackage;
        mArchivedPackage = null;
        mParseFlags = parseFlags;
        mScanFlags = scanFlags;
        mScanResult = scanResult;
        mPackageMetrics = null; // No logging from this code path
        mSessionId = -1;
        mRequireUserAction = USER_ACTION_UNSPECIFIED;
        mDisabledPs = disabledPs;
    }

    @Nullable
    public String getName() {
        return mName;
    }

    @Nullable
    public String getReturnMsg() {
        return mReturnMsg;
    }

    @Nullable
    public OriginInfo getOriginInfo() {
        return mInstallArgs == null ? null : mInstallArgs.mOriginInfo;
    }

    @Nullable
    public PackageRemovedInfo getRemovedInfo() {
        return mRemovedInfo;
    }

    @Nullable
    public String getOrigPackage() {
        return mOrigPackage;
    }

    @Nullable
    public String getOrigPermission() {
        return mOrigPermission;
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
        return mReturnCode;
    }

    public int getInternalErrorCode() {
        return mInternalErrorCode;
    }

    @Nullable
    public IPackageInstallObserver2 getObserver() {
        return mInstallArgs == null ? null : mInstallArgs.mObserver;
    }

    public boolean isInstallMove() {
        return mInstallArgs != null && mInstallArgs.mMoveInfo != null;
    }

    @Nullable
    public String getMoveToUuid() {
        return (mInstallArgs != null && mInstallArgs.mMoveInfo != null)
                ? mInstallArgs.mMoveInfo.mToUuid : null;
    }

    @Nullable
    public String getMovePackageName() {
        return (mInstallArgs != null && mInstallArgs.mMoveInfo != null)
                ? mInstallArgs.mMoveInfo.mPackageName : null;
    }

    @Nullable
    public String getMoveFromCodePath() {
        return (mInstallArgs != null && mInstallArgs.mMoveInfo != null)
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

    public int getDevelopmentInstallFlags() {
        return mInstallArgs == null ? 0 : mInstallArgs.mDevelopmentInstallFlags;
    }

    public int getInstallReason() {
        return mInstallArgs == null ? INSTALL_REASON_UNKNOWN : mInstallArgs.mInstallReason;
    }

    @Nullable
    public String getVolumeUuid() {
        return mInstallArgs == null ? null : mInstallArgs.mVolumeUuid;
    }

    @Nullable
    public AndroidPackage getPkg() {
        return mPkg;
    }

    @Nullable
    public PackageLite getPackageLite() {
        return mPackageLite;
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

    public boolean isArchived() {
        return PackageInstallerSession.isArchivedInstallation(getInstallFlags());
    }

    @Nullable
    public String getRemovedPackage() {
        return mRemovedInfo != null ? mRemovedInfo.mRemovedPackage : null;
    }

    public boolean isInstallExistingForUser() {
        return mInstallArgs == null;
    }

    @Nullable
    public InstallSource getInstallSource() {
        return mInstallArgs == null ? null : mInstallArgs.mInstallSource;
    }

    @Nullable
    public String getInstallerPackageName() {
        return (mInstallArgs != null && mInstallArgs.mInstallSource != null)
                ? mInstallArgs.mInstallSource.mInstallerPackageName : null;
    }

    public int getInstallerPackageUid() {
        return (mInstallArgs != null && mInstallArgs.mInstallSource != null)
                ? mInstallArgs.mInstallSource.mInstallerPackageUid : INVALID_UID;
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
        return mInstallArgs == null ? null : Uri.fromFile(mInstallArgs.mOriginInfo.mResolvedFile);
    }

    @Nullable
    public ApexInfo getApexInfo() {
        return mApexInfo;
    }

    @Nullable
    public String getApexModuleName() {
        return mApexModuleName;
    }
    public boolean isRollback() {
        return mInstallArgs != null
                && mInstallArgs.mInstallReason == PackageManager.INSTALL_REASON_ROLLBACK;
    }

    @Nullable
    public int[] getNewUsers() {
        return mNewUsers;
    }

    @Nullable
    public int[] getOriginUsers() {
        return mOrigUsers;
    }

    public int getAppId() {
        return mAppId;
    }

    @Nullable
    public ArrayMap<String, Integer> getPermissionStates() {
        return mInstallArgs == null ? null : mInstallArgs.mPermissionStates;
    }

    @Nullable
    public ArrayList<AndroidPackage> getLibraryConsumers() {
        return mLibraryConsumers;
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

    @Nullable
    public ParsedPackage getParsedPackage() {
        return mParsedPackage;
    }

    @Nullable
    public ArchivedPackageParcel getArchivedPackage() { return mArchivedPackage; }

    @ParsingPackageUtils.ParseFlags
    public int getParseFlags() {
        return mParseFlags;
    }

    @PackageManagerService.ScanFlags
    public int getScanFlags() {
        return mScanFlags;
    }

    @Nullable
    public String getExistingPackageName() {
        return mExistingPackageName;
    }

    @Nullable
    public AndroidPackage getScanRequestOldPackage() {
        assertScanResultExists();
        return mScanResult.mRequest.mOldPkg;
    }

    public boolean isClearCodeCache() {
        return mClearCodeCache;
    }

    public boolean isInstallReplace() {
        return mReplace;
    }

    public boolean isInstallSystem() {
        return mSystem;
    }

    public boolean isInstallInherit() {
        return mIsInstallInherit;
    }

    public boolean isInstallForUsers() {
        return mIsInstallForUsers;
    }

    public boolean isInstallFromAdb() {
        return mInstallArgs != null
                && (mInstallArgs.mInstallFlags & PackageManager.INSTALL_FROM_ADB) != 0;
    }

    @Nullable
    public PackageSetting getOriginalPackageSetting() {
        return mOriginalPs;
    }

    @Nullable
    public PackageSetting getDisabledPackageSetting() {
        return mDisabledPs;
    }

    @Nullable
    public PackageSetting getScanRequestOldPackageSetting() {
        assertScanResultExists();
        return mScanResult.mRequest.mOldPkgSetting;
    }

    @Nullable
    public PackageSetting getScanRequestOriginalPackageSetting() {
        assertScanResultExists();
        return mScanResult.mRequest.mOriginalPkgSetting;
    }

    @Nullable
    public PackageSetting getScanRequestPackageSetting() {
        assertScanResultExists();
        return mScanResult.mRequest.mPkgSetting;
    }

    @Nullable
    public String getRealPackageName() {
        assertScanResultExists();
        return mScanResult.mRequest.mRealPkgName;
    }

    @Nullable
    public List<String> getChangedAbiCodePath() {
        assertScanResultExists();
        return mScanResult.mChangedAbiCodePath;
    }

    public boolean isApplicationEnabledSettingPersistent() {
        return mInstallArgs == null ? false : mInstallArgs.mApplicationEnabledSettingPersistent;
    }

    public boolean isForceQueryableOverride() {
        return mInstallArgs != null && mInstallArgs.mForceQueryableOverride;
    }

    @Nullable
    public SharedLibraryInfo getSdkSharedLibraryInfo() {
        assertScanResultExists();
        return mScanResult.mSdkSharedLibraryInfo;
    }

    @Nullable
    public SharedLibraryInfo getStaticSharedLibraryInfo() {
        assertScanResultExists();
        return mScanResult.mStaticSharedLibraryInfo;
    }

    @Nullable
    public List<SharedLibraryInfo> getDynamicSharedLibraryInfos() {
        assertScanResultExists();
        return mScanResult.mDynamicSharedLibraryInfos;
    }

    @Nullable
    public PackageSetting getScannedPackageSetting() {
        assertScanResultExists();
        return mScanResult.mPkgSetting;
    }

    @Nullable
    public PackageSetting getRealPackageSetting() {
        // TODO: Fix this to have 1 mutable PackageSetting for scan/install. If the previous
        //  setting needs to be passed to have a comparison, hide it behind an immutable
        //  interface. There's no good reason to have 3 different ways to access the real
        //  PackageSetting object, only one of which is actually correct.
        PackageSetting realPkgSetting = isExistingSettingCopied()
                ? getScanRequestPackageSetting() : getScannedPackageSetting();
        if (realPkgSetting == null) {
            realPkgSetting = getScannedPackageSetting();
        }
        return realPkgSetting;
    }

    public boolean isExistingSettingCopied() {
        assertScanResultExists();
        return mScanResult.mExistingSettingCopied;
    }

    /**
     * Whether the original PackageSetting needs to be updated with
     * a new app ID. Useful when leaving a sharedUserId.
     */
    public boolean needsNewAppId() {
        assertScanResultExists();
        return mScanResult.mPreviousAppId != Process.INVALID_UID;
    }

    public int getPreviousAppId() {
        assertScanResultExists();
        return mScanResult.mPreviousAppId;
    }

    public boolean isPlatformPackage() {
        assertScanResultExists();
        return mScanResult.mRequest.mIsPlatformPackage;
    }

    public boolean isInstantInstall() {
        return (mScanFlags & SCAN_AS_INSTANT_APP) != 0;
    }

    public void assertScanResultExists() {
        if (mScanResult == null) {
            // Should not happen. This indicates a bug in the installation code flow
            if (Build.IS_USERDEBUG || Build.IS_ENG) {
                throw new IllegalStateException("ScanResult cannot be null.");
            } else {
                Slog.e(TAG, "ScanResult is null and it should not happen");
            }
        }
    }

    public int getSessionId() {
        return mSessionId;
    }

    public int getRequireUserAction() {
        return mRequireUserAction;
    }

    public int getDexoptStatus() {
        return mDexoptStatus;
    }

    public boolean isAllNewUsers() {
        return mOrigUsers == null || mOrigUsers.length == 0;
    }
    public int[] getFirstTimeBroadcastUserIds() {
        return mFirstTimeBroadcastUserIds;
    }

    public int[] getFirstTimeBroadcastInstantUserIds() {
        return mFirstTimeBroadcastInstantUserIds;
    }

    public int[] getUpdateBroadcastUserIds() {
        return mUpdateBroadcastUserIds;
    }

    public int[] getUpdateBroadcastInstantUserIds() {
        return mUpdateBroadcastInstantUserIds;
    }

    @NonNull
    public ArrayList<String> getWarnings() {
        return mWarnings;
    }

    public void setScanFlags(int scanFlags) {
        mScanFlags = scanFlags;
    }

    public void closeFreezer() {
        if (mFreezer != null) {
            mFreezer.close();
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
        if (mPackageMetrics != null) {
            mPackageMetrics.onInstallFailed();
        }
    }

    public void setError(PackageManagerException e) {
        setError(null, e);
    }

    public void setError(String msg, PackageManagerException e) {
        mInternalErrorCode = e.internalErrorCode;
        mReturnCode = e.error;
        setReturnMessage(ExceptionUtils.getCompleteMessage(msg, e));
        Slog.w(TAG, msg, e);
        if (mPackageMetrics != null) {
            mPackageMetrics.onInstallFailed();
        }
    }

    public void setReturnCode(int returnCode) {
        mReturnCode = returnCode;
    }

    public void setReturnMessage(String returnMsg) {
        mReturnMsg = returnMsg;
    }

    public void setApexInfo(ApexInfo apexInfo) {
        mApexInfo = apexInfo;
    }

    public void setApexModuleName(@Nullable String apexModuleName) {
        mApexModuleName = apexModuleName;
    }

    public void setPkg(AndroidPackage pkg) {
        mPkg = pkg;
    }

    public void setAppId(int appId) {
        mAppId = appId;
    }

    public void setNewUsers(int[] newUsers) {
        mNewUsers = newUsers;
    }

    public void setOriginPackage(String originPackage) {
        mOrigPackage = originPackage;
    }

    public void setOriginPermission(String originPermission) {
        mOrigPermission = originPermission;
    }

    public void setName(String packageName) {
        mName = packageName;
    }

    public void setOriginUsers(int[] userIds) {
        mOrigUsers = userIds;
    }

    public void setFreezer(PackageFreezer freezer) {
        mFreezer = freezer;
    }

    public void setRemovedInfo(PackageRemovedInfo removedInfo) {
        mRemovedInfo = removedInfo;
    }

    public void setLibraryConsumers(ArrayList<AndroidPackage> libraryConsumers) {
        mLibraryConsumers = libraryConsumers;
    }

    public void setPrepareResult(boolean replace, int scanFlags,
            int parseFlags, PackageState existingPackageState,
            ParsedPackage packageToScan, ArchivedPackageParcel archivedPackage,
            boolean clearCodeCache, boolean system,
            PackageSetting originalPs, PackageSetting disabledPs) {
        mReplace = replace;
        mScanFlags = scanFlags;
        mParseFlags = parseFlags;
        mExistingPackageName =
                existingPackageState != null ? existingPackageState.getPackageName() : null;
        mParsedPackage = packageToScan;
        mArchivedPackage = archivedPackage;
        mClearCodeCache = clearCodeCache;
        mSystem = system;
        mOriginalPs = originalPs;
        mDisabledPs = disabledPs;
    }

    public void setScanResult(@NonNull ScanResult scanResult) {
        mScanResult = scanResult;
    }

    public void setScannedPackageSettingAppId(int appId) {
        assertScanResultExists();
        mScanResult.mPkgSetting.setAppId(appId);
    }

    public void setScannedPackageSettingFirstInstallTimeFromReplaced(
            @Nullable PackageStateInternal replacedPkgSetting, int[] userId) {
        assertScanResultExists();
        mScanResult.mPkgSetting.setFirstInstallTimeFromReplaced(replacedPkgSetting, userId);
    }

    public void setScannedPackageSettingLastUpdateTime(long lastUpdateTim) {
        assertScanResultExists();
        mScanResult.mPkgSetting.setLastUpdateTime(lastUpdateTim);
    }

    public void setRemovedAppId(int appId) {
        if (mRemovedInfo != null) {
            mRemovedInfo.mRemovedAppId = appId;
        }
    }

    /**
     *  Determine the set of users who are adding this package for the first time vs. those who are
     *  seeing an update.
     */
    public void populateBroadcastUsers() {
        assertScanResultExists();
        mFirstTimeBroadcastUserIds = EMPTY_INT_ARRAY;
        mFirstTimeBroadcastInstantUserIds = EMPTY_INT_ARRAY;
        mUpdateBroadcastUserIds = EMPTY_INT_ARRAY;
        mUpdateBroadcastInstantUserIds = EMPTY_INT_ARRAY;

        final boolean allNewUsers = isAllNewUsers();
        if (allNewUsers) {
            // App was not currently installed on any user
            for (int newUser : mNewUsers) {
                final boolean isInstantApp =
                        mScanResult.mPkgSetting.getUserStateOrDefault(newUser).isInstantApp();
                if (isInstantApp) {
                    mFirstTimeBroadcastInstantUserIds =
                            ArrayUtils.appendInt(mFirstTimeBroadcastInstantUserIds, newUser);
                } else {
                    mFirstTimeBroadcastUserIds =
                            ArrayUtils.appendInt(mFirstTimeBroadcastUserIds, newUser);
                }
            }
            return;
        }
        // App was already installed on some users, but is new to some other users
        for (int newUser : mNewUsers) {
            boolean isFirstTimeUser = !ArrayUtils.contains(mOrigUsers, newUser);
            final boolean isInstantApp =
                    mScanResult.mPkgSetting.getUserStateOrDefault(newUser).isInstantApp();
            if (isFirstTimeUser) {
                if (isInstantApp) {
                    mFirstTimeBroadcastInstantUserIds =
                            ArrayUtils.appendInt(mFirstTimeBroadcastInstantUserIds, newUser);
                } else {
                    mFirstTimeBroadcastUserIds =
                            ArrayUtils.appendInt(mFirstTimeBroadcastUserIds, newUser);
                }
            } else {
                if (isInstantApp) {
                    mUpdateBroadcastInstantUserIds =
                            ArrayUtils.appendInt(mUpdateBroadcastInstantUserIds, newUser);
                } else {
                    mUpdateBroadcastUserIds =
                            ArrayUtils.appendInt(mUpdateBroadcastUserIds, newUser);
                }
            }
        }
    }

    public void addWarning(@NonNull String warning) {
        mWarnings.add(warning);
    }

    public void onPrepareStarted() {
        if (mPackageMetrics != null) {
            mPackageMetrics.onStepStarted(PackageMetrics.STEP_PREPARE);
        }
    }

    public void onPrepareFinished() {
        if (mPackageMetrics != null) {
            mPackageMetrics.onStepFinished(PackageMetrics.STEP_PREPARE);
        }
    }

    public void onScanStarted() {
        if (mPackageMetrics != null) {
            mPackageMetrics.onStepStarted(PackageMetrics.STEP_SCAN);
        }
    }

    public void onScanFinished() {
        if (mPackageMetrics != null) {
            mPackageMetrics.onStepFinished(PackageMetrics.STEP_SCAN);
        }
    }

    public void onReconcileStarted() {
        if (mPackageMetrics != null) {
            mPackageMetrics.onStepStarted(PackageMetrics.STEP_RECONCILE);
        }
    }

    public void onReconcileFinished() {
        if (mPackageMetrics != null) {
            mPackageMetrics.onStepFinished(PackageMetrics.STEP_RECONCILE);
        }
    }

    public void onCommitStarted() {
        if (mPackageMetrics != null) {
            mPackageMetrics.onStepStarted(PackageMetrics.STEP_COMMIT);
        }
    }

    public void onCommitFinished() {
        if (mPackageMetrics != null) {
            mPackageMetrics.onStepFinished(PackageMetrics.STEP_COMMIT);
        }
    }

    public void onDexoptFinished(DexoptResult dexoptResult) {
        // Only report external profile warnings when installing from adb. The goal is to warn app
        // developers if they have provided bad external profiles, so it's not beneficial to report
        // those warnings in the normal app install workflow.
        if (isInstallFromAdb() && Flags.useArtServiceV2()) {
            var externalProfileErrors = new LinkedHashSet<String>();
            for (PackageDexoptResult packageResult : dexoptResult.getPackageDexoptResults()) {
                for (DexContainerFileDexoptResult fileResult :
                        packageResult.getDexContainerFileDexoptResults()) {
                    externalProfileErrors.addAll(fileResult.getExternalProfileErrors());
                }
            }
            if (!externalProfileErrors.isEmpty()) {
                addWarning("Error occurred during dexopt when processing external profiles:\n  "
                        + String.join("\n  ", externalProfileErrors));
            }
        }

        // Report dexopt metrics.
        if (mPackageMetrics != null) {
            mDexoptStatus = dexoptResult.getFinalStatus();
            if (mDexoptStatus == DexoptResult.DEXOPT_PERFORMED) {
                long durationMillis = 0;
                for (PackageDexoptResult packageResult : dexoptResult.getPackageDexoptResults()) {
                    for (DexContainerFileDexoptResult fileResult :
                            packageResult.getDexContainerFileDexoptResults()) {
                        durationMillis += fileResult.getDex2oatWallTimeMillis();
                    }
                }
                mPackageMetrics.onStepFinished(PackageMetrics.STEP_DEXOPT, durationMillis);
            }
        }
    }

    public void onInstallCompleted() {
        if (getReturnCode() == INSTALL_SUCCEEDED) {
            if (mPackageMetrics != null) {
                mPackageMetrics.onInstallSucceed();
            }
        }
    }

    public void onFreezeStarted() {
        if (mPackageMetrics != null && improveInstallFreeze()) {
            mPackageMetrics.onStepStarted(PackageMetrics.STEP_FREEZE_INSTALL);
        }
    }

    public void onFreezeCompleted() {
        if (mPackageMetrics != null && improveInstallFreeze()) {
            mPackageMetrics.onStepFinished(PackageMetrics.STEP_FREEZE_INSTALL);
        }
    }
}
