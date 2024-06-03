/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.internal.messages.nano.SystemMessageProto;
import com.android.systemui.res.R;
import com.android.systemui.util.NotificationChannels;

import javax.inject.Inject;

/**
 * Posts a persistent notification on entry to guest mode
 */
public final class GuestSessionNotification {

    private static final String TAG = GuestSessionNotification.class.getSimpleName();

    private final Context mContext;
    private final NotificationManager mNotificationManager;

    @Inject
    public GuestSessionNotification(Context context,
            NotificationManager notificationManager) {
        mContext = context;
        mNotificationManager = notificationManager;
    }

    private void overrideNotificationAppName(Notification.Builder notificationBuilder) {
        final Bundle extras = new Bundle();
        String appName = mContext.getString(R.string.guest_notification_app_name);

        extras.putString(Notification.EXTRA_SUBSTITUTE_APP_NAME, appName);

        notificationBuilder.addExtras(extras);
    }

    void createPersistentNotification(UserInfo userInfo, boolean isGuestFirstLogin) {
        if (!userInfo.isGuest()) {
            // we create a persistent notification only for guests
            return;
        }
        String contentText;
        if (userInfo.isEphemeral()) {
            contentText = mContext.getString(
                    com.android.settingslib.R.string.guest_notification_ephemeral);
        } else if (isGuestFirstLogin) {
            contentText = mContext.getString(
                    com.android.settingslib.R.string.guest_notification_non_ephemeral);
        } else {
            contentText = mContext.getString(
                    com.android.settingslib.R.string
                        .guest_notification_non_ephemeral_non_first_login);
        }

        final Intent guestExitIntent = new Intent(
                        GuestResetOrExitSessionReceiver.ACTION_GUEST_EXIT);
        final Intent userSettingsIntent = new Intent(Settings.ACTION_USER_SETTINGS);

        PendingIntent guestExitPendingIntent =
                PendingIntent.getBroadcastAsUser(mContext, 0, guestExitIntent,
                    PendingIntent.FLAG_IMMUTABLE,
                    UserHandle.SYSTEM);

        PendingIntent userSettingsPendingIntent =
                PendingIntent.getActivityAsUser(mContext, 0, userSettingsIntent,
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                    null,
                    UserHandle.of(userInfo.id));

        Notification.Builder builder = new Notification.Builder(mContext,
                                                                NotificationChannels.ALERTS)
                .setSmallIcon(com.android.settingslib.R.drawable.ic_account_circle)
                .setContentTitle(mContext.getString(R.string.guest_notification_session_active))
                .setContentText(contentText)
                .setPriority(Notification.PRIORITY_DEFAULT)
                .setOngoing(true)
                .setContentIntent(userSettingsPendingIntent);

        // we show reset button only if this is a 2nd or later login
        if (!isGuestFirstLogin) {
            final Intent guestResetIntent = new Intent(
                            GuestResetOrExitSessionReceiver.ACTION_GUEST_RESET);

            PendingIntent guestResetPendingIntent =
                    PendingIntent.getBroadcastAsUser(mContext, 0, guestResetIntent,
                        PendingIntent.FLAG_IMMUTABLE,
                        UserHandle.SYSTEM);

            builder.addAction(R.drawable.ic_sysbar_home,
                        mContext.getString(
                            com.android.settingslib.R.string.guest_reset_guest_confirm_button),
                        guestResetPendingIntent);
        }
        builder.addAction(R.drawable.ic_sysbar_home,
                        mContext.getString(
                            com.android.settingslib.R.string.guest_exit_button),
                        guestExitPendingIntent);

        overrideNotificationAppName(builder);

        mNotificationManager.notifyAsUser(null,
                                         SystemMessageProto.SystemMessage.NOTE_GUEST_SESSION,
                                         builder.build(),
                                         UserHandle.of(userInfo.id));
    }
}
