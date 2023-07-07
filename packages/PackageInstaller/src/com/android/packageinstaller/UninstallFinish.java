/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.packageinstaller;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.util.List;

/**
 * Finish an uninstallation and show Toast on success or failure notification.
 */
public class UninstallFinish extends BroadcastReceiver {
    private static final String LOG_TAG = UninstallFinish.class.getSimpleName();

    private static final String UNINSTALL_FAILURE_CHANNEL = "uninstall failure";

    static final String EXTRA_UNINSTALL_ID = "com.android.packageinstaller.extra.UNINSTALL_ID";
    static final String EXTRA_APP_LABEL = "com.android.packageinstaller.extra.APP_LABEL";
    static final String EXTRA_IS_CLONE_APP = "com.android.packageinstaller.extra.IS_CLONE_APP";

    @Override
    public void onReceive(Context context, Intent intent) {
        int returnCode = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, 0);

        Log.i(LOG_TAG, "Uninstall finished extras=" + intent.getExtras());

        if (returnCode == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            context.startActivity(intent.getParcelableExtra(Intent.EXTRA_INTENT));
            return;
        }

        int uninstallId = intent.getIntExtra(EXTRA_UNINSTALL_ID, 0);
        ApplicationInfo appInfo = intent.getParcelableExtra(
                PackageUtil.INTENT_ATTR_APPLICATION_INFO);
        String appLabel = intent.getStringExtra(EXTRA_APP_LABEL);
        boolean allUsers = intent.getBooleanExtra(Intent.EXTRA_UNINSTALL_ALL_USERS, false);

        NotificationManager notificationManager =
                context.getSystemService(NotificationManager.class);
        UserManager userManager = context.getSystemService(UserManager.class);

        NotificationChannel uninstallFailureChannel = new NotificationChannel(
                UNINSTALL_FAILURE_CHANNEL,
                context.getString(R.string.uninstall_failure_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT);
        notificationManager.createNotificationChannel(uninstallFailureChannel);

        Notification.Builder uninstallFailedNotification = new Notification.Builder(context,
                UNINSTALL_FAILURE_CHANNEL);

        switch (returnCode) {
            case PackageInstaller.STATUS_SUCCESS:
                notificationManager.cancel(uninstallId);

                boolean isCloneApp = intent.getBooleanExtra(EXTRA_IS_CLONE_APP, false);
                Toast.makeText(context, isCloneApp
                                ? context.getString(R.string.uninstall_done_clone_app, appLabel)
                                : context.getString(R.string.uninstall_done_app, appLabel),
                        Toast.LENGTH_LONG).show();
                return;
            case PackageInstaller.STATUS_FAILURE_BLOCKED: {
                int legacyStatus = intent.getIntExtra(PackageInstaller.EXTRA_LEGACY_STATUS, 0);

                switch (legacyStatus) {
                    case PackageManager.DELETE_FAILED_DEVICE_POLICY_MANAGER: {
                        // Find out if the package is an active admin for some non-current user.
                        UserHandle myUserHandle = Process.myUserHandle();
                        UserHandle otherBlockingUserHandle = null;
                        for (UserHandle otherUserHandle : userManager.getUserHandles(true)) {
                            // We only catch the case when the user in question is neither the
                            // current user nor its profile.
                            if (isProfileOfOrSame(userManager, myUserHandle, otherUserHandle)) {
                                continue;
                            }
                            DevicePolicyManager dpm =
                                    context.createContextAsUser(otherUserHandle, 0)
                                    .getSystemService(DevicePolicyManager.class);
                            if (dpm.packageHasActiveAdmins(appInfo.packageName)) {
                                otherBlockingUserHandle = otherUserHandle;
                                break;
                            }
                        }
                        if (otherBlockingUserHandle == null) {
                            Log.d(LOG_TAG, "Uninstall failed because " + appInfo.packageName
                                    + " is a device admin");

                            addDeviceManagerButton(context, uninstallFailedNotification);
                            setBigText(uninstallFailedNotification, context.getString(
                                    R.string.uninstall_failed_device_policy_manager));
                        } else {
                            Log.d(LOG_TAG, "Uninstall failed because " + appInfo.packageName
                                    + " is a device admin of user " + otherBlockingUserHandle);

                            String userName =
                                    context.createContextAsUser(otherBlockingUserHandle, 0)
                                            .getSystemService(UserManager.class).getUserName();
                            setBigText(uninstallFailedNotification, String.format(context.getString(
                                    R.string.uninstall_failed_device_policy_manager_of_user),
                                    userName));
                        }
                        break;
                    }
                    case PackageManager.DELETE_FAILED_OWNER_BLOCKED: {
                        PackageManager packageManager = context.getPackageManager();
                        List<UserHandle> userHandles = userManager.getUserHandles(true);
                        UserHandle otherBlockingUserHandle = null;
                        for (int i = 0; i < userHandles.size(); ++i) {
                            final UserHandle handle = userHandles.get(i);
                            if (packageManager.canUserUninstall(appInfo.packageName, handle)) {
                                otherBlockingUserHandle = handle;
                                break;
                            }
                        }

                        UserHandle myUserHandle = Process.myUserHandle();
                        if (isProfileOfOrSame(userManager, myUserHandle, otherBlockingUserHandle)) {
                            addDeviceManagerButton(context, uninstallFailedNotification);
                        } else {
                            addManageUsersButton(context, uninstallFailedNotification);
                        }

                        if (otherBlockingUserHandle == null) {
                            Log.d(LOG_TAG,
                                    "Uninstall failed for " + appInfo.packageName + " with code "
                                            + returnCode + " no blocking user");
                        } else if (otherBlockingUserHandle == UserHandle.SYSTEM) {
                            setBigText(uninstallFailedNotification,
                                    context.getString(R.string.uninstall_blocked_device_owner));
                        } else {
                            if (allUsers) {
                                setBigText(uninstallFailedNotification,
                                        context.getString(
                                                R.string.uninstall_all_blocked_profile_owner));
                            } else {
                                setBigText(uninstallFailedNotification, context.getString(
                                        R.string.uninstall_blocked_profile_owner));
                            }
                        }
                        break;
                    }
                    default:
                        Log.d(LOG_TAG, "Uninstall blocked for " + appInfo.packageName
                                + " with legacy code " + legacyStatus);
                } break;
            }
            default:
                Log.d(LOG_TAG, "Uninstall failed for " + appInfo.packageName + " with code "
                        + returnCode);
                break;
        }

        uninstallFailedNotification.setContentTitle(
                context.getString(R.string.uninstall_failed_app, appLabel));
        uninstallFailedNotification.setOngoing(false);
        uninstallFailedNotification.setSmallIcon(R.drawable.ic_error);
        notificationManager.notify(uninstallId, uninstallFailedNotification.build());
    }

    /**
     * Is a profile part of a user?
     *
     * @param userManager The user manager
     * @param userHandle The handle of the user
     * @param profileHandle The handle of the profile
     *
     * @return If the profile is part of the user or the profile parent of the user
     */
    private boolean isProfileOfOrSame(UserManager userManager, UserHandle userHandle,
            UserHandle profileHandle) {
        if (userHandle.equals(profileHandle)) {
            return true;
        }
        return userManager.getProfileParent(profileHandle) != null
                && userManager.getProfileParent(profileHandle).equals(userHandle);
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
        Intent intent = new Intent(Settings.ACTION_USER_SETTINGS);
        intent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_ACTIVITY_NEW_TASK);

        builder.addAction((new Notification.Action.Builder(
                Icon.createWithResource(context, R.drawable.ic_settings_multiuser),
                context.getString(R.string.manage_users),
                PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE))).build());
    }

    /**
     * Add a button to the notification that links to the device policy management.
     *
     * @param context The context the notification is created in
     * @param builder The builder of the notification
     */
    private void addDeviceManagerButton(@NonNull Context context,
            @NonNull Notification.Builder builder) {
        Intent intent = new Intent();
        intent.setClassName("com.android.settings",
                "com.android.settings.Settings$DeviceAdminSettingsActivity");
        intent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_ACTIVITY_NEW_TASK);

        builder.addAction((new Notification.Action.Builder(
                Icon.createWithResource(context, R.drawable.ic_lock),
                context.getString(R.string.manage_device_administrators),
                PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE))).build());
    }
}
