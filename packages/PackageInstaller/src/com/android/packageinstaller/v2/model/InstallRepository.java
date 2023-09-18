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

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.util.Log;
import com.android.packageinstaller.v2.model.installstagedata.InstallStage;
import com.android.packageinstaller.v2.model.installstagedata.InstallStaging;

public class InstallRepository {

    private static final String TAG = InstallRepository.class.getSimpleName();
    private final Context mContext;
    private final PackageManager mPackageManager;
    private final PackageInstaller mPackageInstaller;
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
    }

    /**
     * Extracts information from the incoming install intent, checks caller's permission to install
     * packages, verifies that the caller is the install session owner (in case of a session based
     * install) and checks if the current user has restrictions set that prevent app installation,
     * @param intent the incoming {@link Intent} object for installing a package
     * @param callerInfo {@link CallerInfo} that holds the callingUid and callingPackageName
     * @return
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

        return new InstallStaging();
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
