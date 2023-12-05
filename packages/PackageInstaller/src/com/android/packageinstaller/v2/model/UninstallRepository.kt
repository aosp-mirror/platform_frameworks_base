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
import static android.os.UserManager.USER_TYPE_PROFILE_CLONE;
import static android.os.UserManager.USER_TYPE_PROFILE_MANAGED;
import static com.android.packageinstaller.v2.model.PackageUtil.getMaxTargetSdkVersionForUid;
import static com.android.packageinstaller.v2.model.PackageUtil.getPackageNameForUid;
import static com.android.packageinstaller.v2.model.PackageUtil.isPermissionGranted;
import static com.android.packageinstaller.v2.model.PackageUtil.isProfileOfOrSame;
import static com.android.packageinstaller.v2.model.uninstallstagedata.UninstallAborted.ABORT_REASON_APP_UNAVAILABLE;
import static com.android.packageinstaller.v2.model.uninstallstagedata.UninstallAborted.ABORT_REASON_GENERIC_ERROR;
import static com.android.packageinstaller.v2.model.uninstallstagedata.UninstallAborted.ABORT_REASON_USER_NOT_ALLOWED;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.UninstallCompleteCallback;
import android.content.pm.VersionedPackage;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.MutableLiveData;
import com.android.packageinstaller.R;
import com.android.packageinstaller.common.EventResultPersister;
import com.android.packageinstaller.common.UninstallEventReceiver;
import com.android.packageinstaller.v2.model.uninstallstagedata.UninstallAborted;
import com.android.packageinstaller.v2.model.uninstallstagedata.UninstallFailed;
import com.android.packageinstaller.v2.model.uninstallstagedata.UninstallReady;
import com.android.packageinstaller.v2.model.uninstallstagedata.UninstallStage;
import com.android.packageinstaller.v2.model.uninstallstagedata.UninstallSuccess;
import com.android.packageinstaller.v2.model.uninstallstagedata.UninstallUninstalling;
import com.android.packageinstaller.v2.model.uninstallstagedata.UninstallUserActionRequired;
import java.io.IOException;
import java.util.List;

public class UninstallRepository {

    private static final String TAG = UninstallRepository.class.getSimpleName();
    private static final String UNINSTALL_FAILURE_CHANNEL = "uninstall_failure";
    private static final String BROADCAST_ACTION =
        "com.android.packageinstaller.ACTION_UNINSTALL_COMMIT";

    private static final String EXTRA_UNINSTALL_ID =
        "com.android.packageinstaller.extra.UNINSTALL_ID";
    private static final String EXTRA_APP_LABEL =
        "com.android.packageinstaller.extra.APP_LABEL";
    private static final String EXTRA_IS_CLONE_APP =
        "com.android.packageinstaller.extra.IS_CLONE_APP";
    private static final String EXTRA_PACKAGE_NAME =
        "com.android.packageinstaller.extra.EXTRA_PACKAGE_NAME";

    private final Context mContext;
    private final AppOpsManager mAppOpsManager;
    private final PackageManager mPackageManager;
    private final UserManager mUserManager;
    private final NotificationManager mNotificationManager;
    private final MutableLiveData<UninstallStage> mUninstallResult = new MutableLiveData<>();
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
    private int mUninstallId;

    public UninstallRepository(Context context) {
        mContext = context;
        mAppOpsManager = context.getSystemService(AppOpsManager.class);
        mPackageManager = context.getPackageManager();
        mUserManager = context.getSystemService(UserManager.class);
        mNotificationManager = context.getSystemService(NotificationManager.class);
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
        } else if (!mUninstalledUser.equals(myUserHandle)) {
            // Uninstalling user is issuing uninstall for another user
            UserManager customUserManager = mContext.createContextAsUser(mUninstalledUser, 0)
                .getSystemService(UserManager.class);
            String userName = customUserManager.getUserName();

            String uninstalledUserType = getUninstalledUserType(myUserHandle, mUninstalledUser);
            String messageString;
            if (USER_TYPE_PROFILE_MANAGED.equals(uninstalledUserType)) {
                messageString = mContext.getString(
                    R.string.uninstall_application_text_current_user_work_profile, userName);
            } else if (USER_TYPE_PROFILE_CLONE.equals(uninstalledUserType)) {
                mIsClonedApp = true;
                messageString = mContext.getString(
                    R.string.uninstall_application_text_current_user_clone_profile);
            } else {
                messageString = mContext.getString(
                    R.string.uninstall_application_text_user, userName);
            }
            messageBuilder.append(messageString);
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

        boolean suggestToKeepAppData = false;
        try {
            PackageInfo pkgInfo = mPackageManager.getPackageInfo(mTargetPackageName, 0);
            suggestToKeepAppData =
                pkgInfo.applicationInfo != null && pkgInfo.applicationInfo.hasFragileUserData();
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Cannot check hasFragileUserData for " + mTargetPackageName, e);
        }

        long appDataSize = 0;
        if (suggestToKeepAppData) {
            appDataSize = getAppDataSize(mTargetPackageName,
                mUninstallFromAllUsers ? null : mUninstalledUser);
        }
        uarBuilder.setAppDataSize(appDataSize);

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

    /**
     * Returns the type of the user from where an app is being uninstalled. We are concerned with
     * only USER_TYPE_PROFILE_MANAGED and USER_TYPE_PROFILE_CLONE and whether the user and profile
     * belong to the same profile group.
     */
    @Nullable
    private String getUninstalledUserType(UserHandle myUserHandle,
        UserHandle uninstalledUserHandle) {
        if (!mUserManager.isSameProfileGroup(myUserHandle, uninstalledUserHandle)) {
            return null;
        }

        UserManager customUserManager = mContext.createContextAsUser(uninstalledUserHandle, 0)
            .getSystemService(UserManager.class);
        String[] userTypes = {USER_TYPE_PROFILE_MANAGED, USER_TYPE_PROFILE_CLONE};
        for (String userType : userTypes) {
            if (customUserManager.isUserOfType(userType)) {
                return userType;
            }
        }
        return null;
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

    /**
     * Get number of bytes of the app data of the package.
     *
     * @param pkg The package that might have app data.
     * @param user The user the package belongs to or {@code null} if files of all users should
     *     be counted.
     * @return The number of bytes.
     */
    private long getAppDataSize(@NonNull String pkg, @Nullable UserHandle user) {
        if (user != null) {
            return getAppDataSizeForUser(pkg, user);
        }
        // We are uninstalling from all users. Get cumulative app data size for all users.
        List<UserHandle> userHandles = mUserManager.getUserHandles(true);
        long totalAppDataSize = 0;
        int numUsers = userHandles.size();
        for (int i = 0; i < numUsers; i++) {
            totalAppDataSize += getAppDataSizeForUser(pkg, userHandles.get(i));
        }
        return totalAppDataSize;
    }

    /**
     * Get number of bytes of the app data of the package.
     *
     * @param pkg The package that might have app data.
     * @param user The user the package belongs to
     * @return The number of bytes.
     */
    private long getAppDataSizeForUser(@NonNull String pkg, @NonNull UserHandle user) {
        StorageStatsManager storageStatsManager =
            mContext.getSystemService(StorageStatsManager.class);
        try {
            StorageStats stats = storageStatsManager.queryStatsForPackage(
                mPackageManager.getApplicationInfo(pkg, 0).storageUuid, pkg, user);
            return stats.getDataBytes();
        } catch (PackageManager.NameNotFoundException | IOException | SecurityException e) {
            Log.e(TAG, "Cannot determine amount of app data for " + pkg, e);
        }
        return 0;
    }

    public void initiateUninstall(boolean keepData) {
        // Get an uninstallId to track results and show a notification on non-TV devices.
        try {
            mUninstallId = UninstallEventReceiver.addObserver(mContext,
                EventResultPersister.GENERATE_NEW_ID, this::handleUninstallResult);
        } catch (EventResultPersister.OutOfIdsException e) {
            Log.e(TAG, "Failed to start uninstall", e);
            handleUninstallResult(PackageInstaller.STATUS_FAILURE,
                PackageManager.DELETE_FAILED_INTERNAL_ERROR, null, 0);
            return;
        }

        // TODO: Check with UX whether to show UninstallUninstalling dialog / notification?
        mUninstallResult.setValue(new UninstallUninstalling(mTargetAppLabel, mIsClonedApp));

        Bundle uninstallData = new Bundle();
        uninstallData.putInt(EXTRA_UNINSTALL_ID, mUninstallId);
        uninstallData.putString(EXTRA_PACKAGE_NAME, mTargetPackageName);
        uninstallData.putBoolean(Intent.EXTRA_UNINSTALL_ALL_USERS, mUninstallFromAllUsers);
        uninstallData.putCharSequence(EXTRA_APP_LABEL, mTargetAppLabel);
        uninstallData.putBoolean(EXTRA_IS_CLONE_APP, mIsClonedApp);
        Log.i(TAG, "Uninstalling extras = " + uninstallData);

        // Get a PendingIntent for result broadcast and issue an uninstall request
        Intent broadcastIntent = new Intent(BROADCAST_ACTION);
        broadcastIntent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        broadcastIntent.putExtra(EventResultPersister.EXTRA_ID, mUninstallId);
        broadcastIntent.setPackage(mContext.getPackageName());

        PendingIntent pendingIntent =
            PendingIntent.getBroadcast(mContext, mUninstallId, broadcastIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

        if (!startUninstall(mTargetPackageName, mUninstalledUser, pendingIntent,
            mUninstallFromAllUsers, keepData)) {
            handleUninstallResult(PackageInstaller.STATUS_FAILURE,
                PackageManager.DELETE_FAILED_INTERNAL_ERROR, null, 0);
        }
    }

    private void handleUninstallResult(int status, int legacyStatus, @Nullable String message,
        int serviceId) {
        if (mCallback != null) {
            // The caller will be informed about the result via a callback
            mCallback.onUninstallComplete(mTargetPackageName, legacyStatus, message);

            // Since the caller already received the results, just finish the app at this point
            mUninstallResult.setValue(null);
            return;
        }

        boolean returnResult = mIntent.getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false);
        if (returnResult || mCallingActivity != null) {
            Intent intent = new Intent();
            intent.putExtra(Intent.EXTRA_INSTALL_RESULT, legacyStatus);

            if (status == PackageInstaller.STATUS_SUCCESS) {
                UninstallSuccess.Builder successBuilder = new UninstallSuccess.Builder()
                    .setResultIntent(intent)
                    .setActivityResultCode(Activity.RESULT_OK);
                mUninstallResult.setValue(successBuilder.build());
            } else {
                UninstallFailed.Builder failedBuilder = new UninstallFailed.Builder(true)
                    .setResultIntent(intent)
                    .setActivityResultCode(Activity.RESULT_FIRST_USER);
                mUninstallResult.setValue(failedBuilder.build());
            }
            return;
        }

        // Caller did not want the result back. So, we either show a Toast, or a Notification.
        if (status == PackageInstaller.STATUS_SUCCESS) {
            UninstallSuccess.Builder successBuilder = new UninstallSuccess.Builder()
                .setActivityResultCode(legacyStatus)
                .setMessage(mIsClonedApp
                    ? mContext.getString(R.string.uninstall_done_clone_app, mTargetAppLabel)
                    : mContext.getString(R.string.uninstall_done_app, mTargetAppLabel));
            mUninstallResult.setValue(successBuilder.build());
        } else {
            UninstallFailed.Builder failedBuilder = new UninstallFailed.Builder(false);
            Notification.Builder uninstallFailedNotification = null;

            NotificationChannel uninstallFailureChannel = new NotificationChannel(
                UNINSTALL_FAILURE_CHANNEL,
                mContext.getString(R.string.uninstall_failure_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT);
            mNotificationManager.createNotificationChannel(uninstallFailureChannel);

            uninstallFailedNotification = new Notification.Builder(mContext,
                UNINSTALL_FAILURE_CHANNEL);

            UserHandle myUserHandle = Process.myUserHandle();
            switch (legacyStatus) {
                case PackageManager.DELETE_FAILED_DEVICE_POLICY_MANAGER -> {
                    // Find out if the package is an active admin for some non-current user.
                    UserHandle otherBlockingUserHandle =
                        findUserOfDeviceAdmin(myUserHandle, mTargetPackageName);

                    if (otherBlockingUserHandle == null) {
                        Log.d(TAG, "Uninstall failed because " + mTargetPackageName
                            + " is a device admin");

                        addDeviceManagerButton(mContext, uninstallFailedNotification);
                        setBigText(uninstallFailedNotification, mContext.getString(
                            R.string.uninstall_failed_device_policy_manager));
                    } else {
                        Log.d(TAG, "Uninstall failed because " + mTargetPackageName
                            + " is a device admin of user " + otherBlockingUserHandle);

                        String userName =
                            mContext.createContextAsUser(otherBlockingUserHandle, 0)
                                .getSystemService(UserManager.class).getUserName();
                        setBigText(uninstallFailedNotification, String.format(
                            mContext.getString(
                                R.string.uninstall_failed_device_policy_manager_of_user),
                            userName));
                    }
                }
                case PackageManager.DELETE_FAILED_OWNER_BLOCKED -> {
                    UserHandle otherBlockingUserHandle = findBlockingUser(mTargetPackageName);
                    boolean isProfileOfOrSame = isProfileOfOrSame(mUserManager, myUserHandle,
                        otherBlockingUserHandle);

                    if (isProfileOfOrSame) {
                        addDeviceManagerButton(mContext, uninstallFailedNotification);
                    } else {
                        addManageUsersButton(mContext, uninstallFailedNotification);
                    }

                    String bigText = null;
                    if (otherBlockingUserHandle == null) {
                        Log.d(TAG, "Uninstall failed for " + mTargetPackageName +
                            " with code " + status + " no blocking user");
                    } else if (otherBlockingUserHandle == UserHandle.SYSTEM) {
                        bigText = mContext.getString(
                            R.string.uninstall_blocked_device_owner);
                    } else {
                        bigText = mContext.getString(mUninstallFromAllUsers ?
                            R.string.uninstall_all_blocked_profile_owner
                            : R.string.uninstall_blocked_profile_owner);
                    }
                    if (bigText != null) {
                        setBigText(uninstallFailedNotification, bigText);
                    }
                }
                default -> {
                    Log.d(TAG, "Uninstall blocked for " + mTargetPackageName
                        + " with legacy code " + legacyStatus);
                }
            }

            uninstallFailedNotification.setContentTitle(
                mContext.getString(R.string.uninstall_failed_app, mTargetAppLabel));
            uninstallFailedNotification.setOngoing(false);
            uninstallFailedNotification.setSmallIcon(R.drawable.ic_error);
            failedBuilder.setUninstallNotification(mUninstallId,
                uninstallFailedNotification.build());

            mUninstallResult.setValue(failedBuilder.build());
        }
    }

    /**
     * @param myUserHandle {@link UserHandle} of the current user.
     * @param packageName Name of the package being uninstalled.
     * @return the {@link UserHandle} of the user in which a package is a device admin.
     */
    @Nullable
    private UserHandle findUserOfDeviceAdmin(UserHandle myUserHandle, String packageName) {
        for (UserHandle otherUserHandle : mUserManager.getUserHandles(true)) {
            // We only catch the case when the user in question is neither the
            // current user nor its profile.
            if (isProfileOfOrSame(mUserManager, myUserHandle, otherUserHandle)) {
                continue;
            }
            DevicePolicyManager dpm = mContext.createContextAsUser(otherUserHandle, 0)
                    .getSystemService(DevicePolicyManager.class);
            if (dpm.packageHasActiveAdmins(packageName)) {
                return otherUserHandle;
            }
        }
        return null;
    }

    /**
     *
     * @param packageName Name of the package being uninstalled.
     * @return {@link UserHandle} of the user in which a package is blocked from being uninstalled.
     */
    @Nullable
    private UserHandle findBlockingUser(String packageName) {
        for (UserHandle otherUserHandle : mUserManager.getUserHandles(true)) {
            // TODO (b/307399586): Add a negation when the logic of the method
            //  is fixed
            if (mPackageManager.canUserUninstall(packageName, otherUserHandle)) {
                return otherUserHandle;
            }
        }
        return null;
    }

    /**
     * Set big text for the notification.
     *
     * @param builder The builder of the notification
     * @param text The text to set.
     */
    private void setBigText(@NonNull Notification.Builder builder,
        @NonNull CharSequence text) {
        builder.setStyle(new Notification.BigTextStyle().bigText(text));
    }

    /**
     * Add a button to the notification that links to the user management.
     *
     * @param context The context the notification is created in
     * @param builder The builder of the notification
     */
    private void addManageUsersButton(@NonNull Context context,
        @NonNull Notification.Builder builder) {
        builder.addAction((new Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_settings_multiuser),
            context.getString(R.string.manage_users),
            PendingIntent.getActivity(context, 0, getUserSettingsIntent(),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))).build());
    }

    private Intent getUserSettingsIntent() {
        Intent intent = new Intent(Settings.ACTION_USER_SETTINGS);
        intent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    /**
     * Add a button to the notification that links to the device policy management.
     *
     * @param context The context the notification is created in
     * @param builder The builder of the notification
     */
    private void addDeviceManagerButton(@NonNull Context context,
        @NonNull Notification.Builder builder) {
        builder.addAction((new Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_lock),
            context.getString(R.string.manage_device_administrators),
            PendingIntent.getActivity(context, 0, getDeviceManagerIntent(),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))).build());
    }

    private Intent getDeviceManagerIntent() {
        Intent intent = new Intent();
        intent.setClassName("com.android.settings",
            "com.android.settings.Settings$DeviceAdminSettingsActivity");
        intent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    /**
     * Starts an uninstall for the given package.
     *
     * @return {@code true} if there was no exception while uninstalling. This does not represent
     *     the result of the uninstall. Result will be made available in
     *     {@link #handleUninstallResult(int, int, String, int)}
     */
    private boolean startUninstall(String packageName, UserHandle targetUser,
        PendingIntent pendingIntent, boolean uninstallFromAllUsers, boolean keepData) {
        int flags = uninstallFromAllUsers ? PackageManager.DELETE_ALL_USERS : 0;
        flags |= keepData ? PackageManager.DELETE_KEEP_DATA : 0;
        try {
            mContext.createContextAsUser(targetUser, 0)
                .getPackageManager().getPackageInstaller().uninstall(
                    new VersionedPackage(packageName, PackageManager.VERSION_CODE_HIGHEST),
                    flags, pendingIntent.getIntentSender());
            return true;
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Failed to uninstall", e);
            return false;
        }
    }

    public void cancelInstall() {
        if (mCallback != null) {
            mCallback.onUninstallComplete(mTargetPackageName,
                PackageManager.DELETE_FAILED_ABORTED, "Cancelled by user");
        }
    }

    public MutableLiveData<UninstallStage> getUninstallResult() {
        return mUninstallResult;
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
