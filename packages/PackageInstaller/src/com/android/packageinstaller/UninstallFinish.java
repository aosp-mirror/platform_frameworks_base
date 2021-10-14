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

import android.annotation.NonNull;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.admin.IDevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.graphics.drawable.Icon;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import java.util.List;

/**
 * Finish an uninstallation and show Toast on success or failure notification.
 */
public class UninstallFinish extends BroadcastReceiver {
    private static final String LOG_TAG = UninstallFinish.class.getSimpleName();

    private static final String UNINSTALL_FAILURE_CHANNEL = "uninstall failure";

    static final String EXTRA_UNINSTALL_ID = "com.android.packageinstaller.extra.UNINSTALL_ID";
    static final String EXTRA_APP_LABEL = "com.android.packageinstaller.extra.APP_LABEL";

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

                Toast.makeText(context, context.getString(R.string.uninstall_done_app, appLabel),
                        Toast.LENGTH_LONG).show();
                return;
            case PackageInstaller.STATUS_FAILURE_BLOCKED: {
                int legacyStatus = intent.getIntExtra(PackageInstaller.EXTRA_LEGACY_STATUS, 0);

                switch (legacyStatus) {
                    case PackageManager.DELETE_FAILED_DEVICE_POLICY_MANAGER: {
                        IDevicePolicyManager dpm = IDevicePolicyManager.Stub.asInterface(
                                ServiceManager.getService(Context.DEVICE_POLICY_SERVICE));
                        // Find out if the package is an active admin for some non-current user.
                        int myUserId = UserHandle.myUserId();
                        UserInfo otherBlockingUser = null;
                        for (UserInfo user : userManager.getUsers()) {
                            // We only catch the case when the user in question is neither the
                            // current user nor its profile.
                            if (isProfileOfOrSame(userManager, myUserId, user.id)) {
                                continue;
                            }

                            try {
                                if (dpm.packageHasActiveAdmins(appInfo.packageName, user.id)) {
                                    otherBlockingUser = user;
                                    break;
                                }
                            } catch (RemoteException e) {
                                Log.e(LOG_TAG, "Failed to talk to package manager", e);
                            }
                        }
                        if (otherBlockingUser == null) {
                            Log.d(LOG_TAG, "Uninstall failed because " + appInfo.packageName
                                    + " is a device admin");

                            addDeviceManagerButton(context, uninstallFailedNotification);
                            setBigText(uninstallFailedNotification, context.getString(
                                    R.string.uninstall_failed_device_policy_manager));
                        } else {
                            Log.d(LOG_TAG, "Uninstall failed because " + appInfo.packageName
                                    + " is a device admin of user " + otherBlockingUser);

                            setBigText(uninstallFailedNotification, String.format(context.getString(
                                    R.string.uninstall_failed_device_policy_manager_of_user),
                                    otherBlockingUser.name));
                        }
                        break;
                    }
                    case PackageManager.DELETE_FAILED_OWNER_BLOCKED: {
                        IPackageManager packageManager = IPackageManager.Stub.asInterface(
                                ServiceManager.getService("package"));

                        List<UserInfo> users = userManager.getUsers();
                        int blockingUserId = UserHandle.USER_NULL;
                        for (int i = 0; i < users.size(); ++i) {
                            final UserInfo user = users.get(i);
                            try {
                                if (packageManager.getBlockUninstallForUser(appInfo.packageName,
                                        user.id)) {
                                    blockingUserId = user.id;
                                    break;
                                }
                            } catch (RemoteException e) {
                                // Shouldn't happen.
                                Log.e(LOG_TAG, "Failed to talk to package manager", e);
                            }
                        }

                        int myUserId = UserHandle.myUserId();
                        if (isProfileOfOrSame(userManager, myUserId, blockingUserId)) {
                            addDeviceManagerButton(context, uninstallFailedNotification);
                        } else {
                            addManageUsersButton(context, uninstallFailedNotification);
                        }

                        if (blockingUserId == UserHandle.USER_NULL) {
                            Log.d(LOG_TAG,
                                    "Uninstall failed for " + appInfo.packageName + " with code "
                                            + returnCode + " no blocking user");
                        } else if (blockingUserId == UserHandle.USER_SYSTEM) {
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
     * @param userId The id of the user
     * @param profileId The id of the profile
     *
     * @return If the profile is part of the user or the profile parent of the user
     */
    private boolean isProfileOfOrSame(@NonNull UserManager userManager, int userId, int profileId) {
        if (userId == profileId) {
            return true;
        }

        UserInfo parentUser = userManager.getProfileParent(profileId);
        return parentUser != null && parentUser.id == userId;
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
