/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.packageinstaller.v2.model;

import static android.app.AppOpsManager.MODE_ALLOWED;
import static com.android.packageinstaller.v2.model.PackageUtil.getMaxTargetSdkVersionForUid;
import static com.android.packageinstaller.v2.model.PackageUtil.getPackageNameForUid;
import static com.android.packageinstaller.v2.model.PackageUtil.isPermissionGranted;
import static com.android.packageinstaller.v2.model.uninstallstagedata.UninstallAborted.ABORT_REASON_APP_UNAVAILABLE;
import static com.android.packageinstaller.v2.model.uninstallstagedata.UninstallAborted.ABORT_REASON_GENERIC_ERROR;
import static com.android.packageinstaller.v2.model.uninstallstagedata.UninstallAborted.ABORT_REASON_USER_NOT_ALLOWED;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.UninstallCompleteCallback;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import com.android.packageinstaller.R;
import com.android.packageinstaller.v2.model.uninstallstagedata.UninstallAborted;
import com.android.packageinstaller.v2.model.uninstallstagedata.UninstallReady;
import com.android.packageinstaller.v2.model.uninstallstagedata.UninstallStage;
import com.android.packageinstaller.v2.model.uninstallstagedata.UninstallUserActionRequired;
import java.util.List;

public class UninstallRepository {

    private static final String TAG = UninstallRepository.class.getSimpleName();
    private final Context mContext;
    private final AppOpsManager mAppOpsManager;
    private final PackageManager mPackageManager;
    private final UserManager mUserManager;
    public UserHandle mUninstalledUser;
    public UninstallCompleteCallback mCallback;
    private ApplicationInfo mTargetAppInfo;
    private ActivityInfo mTargetActivityInfo;
    private Intent mIntent;
    private CharSequence mTargetAppLabel;
    private String mTargetPackageName;
    private String mCallingActivity;
    private boolean mUninstallFromAllUsers;
    private boolean mIsClonedApp;

    public UninstallRepository(Context context) {
        mContext = context;
        mAppOpsManager = context.getSystemService(AppOpsManager.class);
        mPackageManager = context.getPackageManager();
        mUserManager = context.getSystemService(UserManager.class);
    }

    public UninstallStage performPreUninstallChecks(Intent intent, CallerInfo callerInfo) {
        mIntent = intent;

        int callingUid = callerInfo.getUid();
        mCallingActivity = callerInfo.getActivityName();

        if (callingUid == Process.INVALID_UID) {
            Log.e(TAG, "Could not determine the launching uid.");
            return new UninstallAborted(ABORT_REASON_GENERIC_ERROR);
            // TODO: should we give any indication to the user?
        }

        String callingPackage = getPackageNameForUid(mContext, callingUid, null);
        if (callingPackage == null) {
            Log.e(TAG, "Package not found for originating uid " + callingUid);
            return new UninstallAborted(ABORT_REASON_GENERIC_ERROR);
        } else {
            if (mAppOpsManager.noteOpNoThrow(
                AppOpsManager.OPSTR_REQUEST_DELETE_PACKAGES, callingUid, callingPackage)
                != MODE_ALLOWED) {
                Log.e(TAG, "Install from uid " + callingUid + " disallowed by AppOps");
                return new UninstallAborted(ABORT_REASON_GENERIC_ERROR);
            }
        }

        if (getMaxTargetSdkVersionForUid(mContext, callingUid) >= Build.VERSION_CODES.P
            && !isPermissionGranted(mContext, Manifest.permission.REQUEST_DELETE_PACKAGES,
            callingUid)
            && !isPermissionGranted(mContext, Manifest.permission.DELETE_PACKAGES, callingUid)) {
            Log.e(TAG, "Uid " + callingUid + " does not have "
                + Manifest.permission.REQUEST_DELETE_PACKAGES + " or "
                + Manifest.permission.DELETE_PACKAGES);

            return new UninstallAborted(ABORT_REASON_GENERIC_ERROR);
        }

        // Get intent information.
        // We expect an intent with URI of the form package:<packageName>#<className>
        // className is optional; if specified, it is the activity the user chose to uninstall
        final Uri packageUri = intent.getData();
        if (packageUri == null) {
            Log.e(TAG, "No package URI in intent");
            return new UninstallAborted(ABORT_REASON_APP_UNAVAILABLE);
        }
        mTargetPackageName = packageUri.getEncodedSchemeSpecificPart();
        if (mTargetPackageName == null) {
            Log.e(TAG, "Invalid package name in URI: " + packageUri);
            return new UninstallAborted(ABORT_REASON_APP_UNAVAILABLE);
        }

        mUninstallFromAllUsers = intent.getBooleanExtra(Intent.EXTRA_UNINSTALL_ALL_USERS,
            false);
        if (mUninstallFromAllUsers && !mUserManager.isAdminUser()) {
            Log.e(TAG, "Only admin user can request uninstall for all users");
            return new UninstallAborted(ABORT_REASON_USER_NOT_ALLOWED);
        }

        mUninstalledUser = intent.getParcelableExtra(Intent.EXTRA_USER, UserHandle.class);
        if (mUninstalledUser == null) {
            mUninstalledUser = Process.myUserHandle();
        } else {
            List<UserHandle> profiles = mUserManager.getUserProfiles();
            if (!profiles.contains(mUninstalledUser)) {
                Log.e(TAG, "User " + Process.myUserHandle() + " can't request uninstall "
                    + "for user " + mUninstalledUser);
                return new UninstallAborted(ABORT_REASON_USER_NOT_ALLOWED);
            }
        }

        mCallback = intent.getParcelableExtra(PackageInstaller.EXTRA_CALLBACK,
            PackageManager.UninstallCompleteCallback.class);

        try {
            mTargetAppInfo = mPackageManager.getApplicationInfo(mTargetPackageName,
                PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_ANY_USER));
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Unable to get packageName");
        }

        if (mTargetAppInfo == null) {
            Log.e(TAG, "Invalid packageName: " + mTargetPackageName);
            return new UninstallAborted(ABORT_REASON_APP_UNAVAILABLE);
        }

        // The class name may have been specified (e.g. when deleting an app from all apps)
        final String className = packageUri.getFragment();
        if (className != null) {
            try {
                mTargetActivityInfo = mPackageManager.getActivityInfo(
                    new ComponentName(mTargetPackageName, className),
                    PackageManager.ComponentInfoFlags.of(0));
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "Unable to get className");
                // Continue as the ActivityInfo isn't critical.
            }
        }

        return new UninstallReady();
    }

    public UninstallStage generateUninstallDetails() {
        UninstallUserActionRequired.Builder uarBuilder = new UninstallUserActionRequired.Builder();
        StringBuilder messageBuilder = new StringBuilder();

        mTargetAppLabel = mTargetAppInfo.loadSafeLabel(mPackageManager);

        // If the Activity label differs from the App label, then make sure the user
        // knows the Activity belongs to the App being uninstalled.
        if (mTargetActivityInfo != null) {
            final CharSequence activityLabel = mTargetActivityInfo.loadSafeLabel(mPackageManager);
            if (CharSequence.compare(activityLabel, mTargetAppLabel) != 0) {
                messageBuilder.append(
                    mContext.getString(R.string.uninstall_activity_text, activityLabel));
                messageBuilder.append(" ").append(mTargetAppLabel).append(".\n\n");
            }
        }

        final boolean isUpdate =
            (mTargetAppInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        final UserHandle myUserHandle = Process.myUserHandle();
        boolean isSingleUser = isSingleUser();

        if (isUpdate) {
            messageBuilder.append(mContext.getString(
                isSingleUser ? R.string.uninstall_update_text :
                    R.string.uninstall_update_text_multiuser));
        } else if (mUninstallFromAllUsers && !isSingleUser) {
            messageBuilder.append(mContext.getString(
                R.string.uninstall_application_text_all_users));
        } else if (isCloneProfile(mUninstalledUser)) {
            mIsClonedApp = true;
            messageBuilder.append(mContext.getString(
                R.string.uninstall_application_text_current_user_clone_profile));
        } else if (myUserHandle.equals(UserHandle.SYSTEM)
            && hasClonedInstance(mTargetAppInfo.packageName)) {
            messageBuilder.append(mContext.getString(
                R.string.uninstall_application_text_with_clone_instance, mTargetAppLabel));
        } else {
            messageBuilder.append(mContext.getString(R.string.uninstall_application_text));
        }

        uarBuilder.setMessage(messageBuilder.toString());

        if (mIsClonedApp) {
            uarBuilder.setTitle(mContext.getString(R.string.cloned_app_label, mTargetAppLabel));
        } else {
            uarBuilder.setTitle(mTargetAppLabel.toString());
        }

        return uarBuilder.build();
    }

    /**
     * Returns whether there is only one "full" user on this device.
     *
     * <p><b>Note:</b> on devices that use {@link android.os.UserManager#isHeadlessSystemUserMode()
     * headless system user mode}, the system user is not "full", so it's not be considered in the
     * calculation.</p>
     */
    private boolean isSingleUser() {
        final int userCount = mUserManager.getUserCount();
        return userCount == 1 || (UserManager.isHeadlessSystemUserMode() && userCount == 2);
    }

    private boolean hasClonedInstance(String packageName) {
        // Check if clone user is present on the device.
        UserHandle cloneUser = null;
        List<UserHandle> profiles = mUserManager.getUserProfiles();
        for (UserHandle userHandle : profiles) {
            if (!userHandle.equals(UserHandle.SYSTEM) && isCloneProfile(userHandle)) {
                cloneUser = userHandle;
                break;
            }
        }
        // Check if another instance of given package exists in clone user profile.
        try {
            return cloneUser != null
                && mPackageManager.getPackageUidAsUser(packageName,
                PackageManager.PackageInfoFlags.of(0), cloneUser.getIdentifier()) > 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private boolean isCloneProfile(UserHandle userHandle) {
        UserManager customUserManager = mContext.createContextAsUser(userHandle, 0)
            .getSystemService(UserManager.class);
        return customUserManager.isUserOfType(UserManager.USER_TYPE_PROFILE_CLONE);
    }

    public static class CallerInfo {

        private final String mActivityName;
        private final int mUid;

        public CallerInfo(String activityName, int uid) {
            mActivityName = activityName;
            mUid = uid;
        }

        public String getActivityName() {
            return mActivityName;
        }

        public int getUid() {
            return mUid;
        }
    }
}
