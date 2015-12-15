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

package com.android.server.devicepolicy;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.text.format.DateUtils;

import com.android.internal.R;

/**
 * Utilities class for the remote bugreport operation.
 */
class RemoteBugreportUtils {

    static final int REMOTE_BUGREPORT_CONSENT_NOTIFICATION_ID = 678435657;
    static final int REMOTE_BUGREPORT_IN_PROGRESS_NOTIFICATION_ID = 590907895;

    static final long REMOTE_BUGREPORT_TIMEOUT_MILLIS = 10 * DateUtils.MINUTE_IN_MILLIS;

    static final String CTL_STOP = "ctl.stop";
    static final String REMOTE_BUGREPORT_SERVICE = "bugreportremote";

    static final String ACTION_REMOTE_BUGREPORT_DISPATCH =
            "android.intent.action.REMOTE_BUGREPORT_DISPATCH";
    static final String ACTION_REMOTE_BUGREPORT_SHARING_ACCEPTED =
            "com.android.server.action.REMOTE_BUGREPORT_SHARING_ACCEPTED";
    static final String ACTION_REMOTE_BUGREPORT_SHARING_DECLINED =
            "com.android.server.action.REMOTE_BUGREPORT_SHARING_DECLINED";
    static final String EXTRA_REMOTE_BUGREPORT_HASH = "android.intent.extra.REMOTE_BUGREPORT_HASH";

    static final String BUGREPORT_MIMETYPE = "application/vnd.android.bugreport";

    static Notification buildRemoteBugreportConsentNotification(Context context) {
        PendingIntent pendingIntentAccept = PendingIntent.getBroadcast(
                context, REMOTE_BUGREPORT_CONSENT_NOTIFICATION_ID,
                new Intent(ACTION_REMOTE_BUGREPORT_SHARING_ACCEPTED),
                        PendingIntent.FLAG_CANCEL_CURRENT);
        PendingIntent pendingIntentDecline = PendingIntent.getBroadcast(
                context, REMOTE_BUGREPORT_CONSENT_NOTIFICATION_ID,
                new Intent(ACTION_REMOTE_BUGREPORT_SHARING_DECLINED),
                        PendingIntent.FLAG_CANCEL_CURRENT);

        return new Notification.Builder(context)
                .setSmallIcon(com.android.internal.R.drawable.stat_sys_adb)
                .setContentTitle(context.getString(
                        R.string.share_remote_bugreport_notification_title))
                .setTicker(context.getString(R.string.share_remote_bugreport_notification_title))
                .setContentText(context.getString(
                        R.string.share_remote_bugreport_notification_message))
                .setStyle(new Notification.BigTextStyle().bigText(context.getString(
                        R.string.share_remote_bugreport_notification_message)))
                .addAction(new Notification.Action.Builder(null /* icon */,
                        context.getString(R.string.share_remote_bugreport_notification_decline),
                        pendingIntentDecline).build())
                .addAction(new Notification.Action.Builder(null /* icon */,
                        context.getString(R.string.share_remote_bugreport_notification_accept),
                        pendingIntentAccept).build())
                .setOngoing(true)
                .setLocalOnly(true)
                .setColor(context.getColor(
                        com.android.internal.R.color.system_notification_accent_color))
                .setPriority(Notification.PRIORITY_MAX)
                .setVibrate(new long[0])
                .build();
    }

    static Notification buildRemoteBugreportInProgressNotification(Context context,
            boolean canCancelBugreport) {
        Notification.Builder builder = new Notification.Builder(context)
                .setSmallIcon(com.android.internal.R.drawable.stat_sys_adb)
                .setContentTitle(context.getString(
                        R.string.remote_bugreport_progress_notification_title))
                .setTicker(context.getString(
                        R.string.remote_bugreport_progress_notification_title))
                .setOngoing(true)
                .setLocalOnly(true)
                .setColor(context.getColor(
                        com.android.internal.R.color.system_notification_accent_color))
                .setPriority(Notification.PRIORITY_HIGH);

        if (canCancelBugreport) {
            PendingIntent pendingIntentCancel = PendingIntent.getBroadcast(context,
                    REMOTE_BUGREPORT_IN_PROGRESS_NOTIFICATION_ID,
                    new Intent(ACTION_REMOTE_BUGREPORT_SHARING_DECLINED),
                    PendingIntent.FLAG_CANCEL_CURRENT);
            String message = context.getString(
                    R.string.remote_bugreport_progress_notification_message_can_cancel);
            builder.setContentText(message)
                    .setContentIntent(pendingIntentCancel)
                    .setStyle(new Notification.BigTextStyle().bigText(message));
        }
        return builder.build();
    }
}

