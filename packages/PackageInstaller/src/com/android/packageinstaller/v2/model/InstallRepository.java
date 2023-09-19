/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.packageinstaller.v2.model;

import static com.android.packageinstaller.v2.model.PackageUtil.isCallerSessionOwner;
import static com.android.packageinstaller.v2.model.PackageUtil.isInstallPermissionGrantedOrRequested;
import static com.android.packageinstaller.v2.model.PackageUtil.isPermissionGranted;
import static com.android.packageinstaller.v2.model.installstagedata.InstallAborted.ABORT_REASON_INTERNAL_ERROR;
import static com.android.packageinstaller.v2.model.installstagedata.InstallAborted.ABORT_REASON_POLICY;

import android.Manifest;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;
import androidx.annotation.Nullable;
import com.android.packageinstaller.v2.model.installstagedata.InstallAborted;
import com.android.packageinstaller.v2.model.installstagedata.InstallStage;
import com.android.packageinstaller.v2.model.installstagedata.InstallStaging;

public class InstallRepository {

    private static final String TAG = InstallRepository.class.getSimpleName();
    private final Context mContext;
    private final PackageManager mPackageManager;
    private final PackageInstaller mPackageInstaller;
    private final UserManager mUserManager;
    private final DevicePolicyManager mDevicePolicyManager;
    private final boolean mLocalLOGV = false;
    private Intent mIntent;
    private boolean mIsSessionInstall;
    private boolean mIsTrustedSource;
    /**
     * Session ID for a session created when caller uses PackageInstaller APIs
     */
    private int mSessionId;
    private int mCallingUid;
    private String mCallingPackage;

    public InstallRepository(Context context) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        mPackageInstaller = mPackageManager.getPackageInstaller();
        mDevicePolicyManager = context.getSystemService(DevicePolicyManager.class);
        mUserManager = context.getSystemService(UserManager.class);
    }

    /**
     * Extracts information from the incoming install intent, checks caller's permission to install
     * packages, verifies that the caller is the install session owner (in case of a session based
     * install) and checks if the current user has restrictions set that prevent app installation,
     * @param intent the incoming {@link Intent} object for installing a package
     * @param callerInfo {@link CallerInfo} that holds the callingUid and callingPackageName
     * @return
     * <p>{@link InstallAborted} if there are errors while performing the checks</p>
     * <p>{@link InstallStaging} after successfully performing the checks</p>
     */
    public InstallStage performPreInstallChecks(Intent intent, CallerInfo callerInfo) {
        mIntent = intent;

        String callingAttributionTag = null;

        mIsSessionInstall =
            PackageInstaller.ACTION_CONFIRM_PRE_APPROVAL.equals(intent.getAction())
                || PackageInstaller.ACTION_CONFIRM_INSTALL.equals(intent.getAction());

        mSessionId = mIsSessionInstall
            ? intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, SessionInfo.INVALID_ID)
            : SessionInfo.INVALID_ID;

        mCallingPackage = callerInfo.getPackageName();

        if (mCallingPackage == null && mSessionId != SessionInfo.INVALID_ID) {
            PackageInstaller.SessionInfo sessionInfo = mPackageInstaller.getSessionInfo(mSessionId);
            mCallingPackage = (sessionInfo != null) ? sessionInfo.getInstallerPackageName() : null;
            callingAttributionTag =
                (sessionInfo != null) ? sessionInfo.getInstallerAttributionTag() : null;
        }

        // Uid of the source package, coming from ActivityManager
        mCallingUid = callerInfo.getUid();
        if (mCallingUid == Process.INVALID_UID) {
            Log.e(TAG, "Could not determine the launching uid.");
        }
        final ApplicationInfo sourceInfo = getSourceInfo(mCallingPackage);
        // Uid of the source package, with a preference to uid from ApplicationInfo
        final int originatingUid = sourceInfo != null ? sourceInfo.uid : mCallingUid;

        if (mCallingUid == Process.INVALID_UID && sourceInfo == null) {
            // Caller's identity could not be determined. Abort the install
            return new InstallAborted.Builder(ABORT_REASON_INTERNAL_ERROR).build();
        }

        if (!isCallerSessionOwner(mPackageInstaller, originatingUid, mSessionId)) {
            return new InstallAborted.Builder(ABORT_REASON_INTERNAL_ERROR).build();
        }

        mIsTrustedSource = isInstallRequestFromTrustedSource(sourceInfo, mIntent, originatingUid);

        if (!isInstallPermissionGrantedOrRequested(mContext, mCallingUid, originatingUid,
            mIsTrustedSource)) {
            return new InstallAborted.Builder(ABORT_REASON_INTERNAL_ERROR).build();
        }

        String restriction = getDevicePolicyRestrictions();
        if (restriction != null) {
            InstallAborted.Builder abortedBuilder =
                new InstallAborted.Builder(ABORT_REASON_POLICY).setMessage(restriction);
            final Intent adminSupportDetailsIntent =
                mDevicePolicyManager.createAdminSupportIntent(restriction);
            if (adminSupportDetailsIntent != null) {
                abortedBuilder.setResultIntent(adminSupportDetailsIntent);
            }
            return abortedBuilder.build();
        }

        maybeRemoveInvalidInstallerPackageName(callerInfo);

        return new InstallStaging();
    }

    /**
     * @return the ApplicationInfo for the installation source (the calling package), if available
     */
    @Nullable
    private ApplicationInfo getSourceInfo(@Nullable String callingPackage) {
        if (callingPackage == null) {
            return null;
        }
        try {
            return mPackageManager.getApplicationInfo(callingPackage, 0);
        } catch (PackageManager.NameNotFoundException ignored) {
            return null;
        }
    }

    private boolean isInstallRequestFromTrustedSource(ApplicationInfo sourceInfo, Intent intent,
        int originatingUid) {
        boolean isNotUnknownSource = intent.getBooleanExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, false);
        return sourceInfo != null && sourceInfo.isPrivilegedApp()
            && (isNotUnknownSource
            || isPermissionGranted(mContext, Manifest.permission.INSTALL_PACKAGES, originatingUid));
    }

    private String getDevicePolicyRestrictions() {
        final String[] restrictions = new String[]{
            UserManager.DISALLOW_INSTALL_APPS,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY
        };

        for (String restriction : restrictions) {
            if (!mUserManager.hasUserRestrictionForUser(restriction, Process.myUserHandle())) {
                continue;
            }
            return restriction;
        }
        return null;
    }

    private void maybeRemoveInvalidInstallerPackageName(CallerInfo callerInfo) {
        final String installerPackageNameFromIntent =
            mIntent.getStringExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME);
        if (installerPackageNameFromIntent == null) {
            return;
        }
        if (!TextUtils.equals(installerPackageNameFromIntent, callerInfo.getPackageName())
            && !isPermissionGranted(mPackageManager, Manifest.permission.INSTALL_PACKAGES,
            callerInfo.getPackageName())) {
            Log.e(TAG, "The given installer package name " + installerPackageNameFromIntent
                + " is invalid. Remove it.");
            EventLog.writeEvent(0x534e4554, "236687884", callerInfo.getUid(),
                "Invalid EXTRA_INSTALLER_PACKAGE_NAME");
            mIntent.removeExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME);
        }
    }

    public interface SessionStageListener {

        void onStagingSuccess(SessionInfo info);

        void onStagingFailure();
    }

    public static class CallerInfo {

        private final String mPackageName;
        private final int mUid;

        public CallerInfo(String packageName, int uid) {
            mPackageName = packageName;
            mUid = uid;
        }

        public String getPackageName() {
            return mPackageName;
        }

        public int getUid() {
            return mUid;
        }
    }
}
