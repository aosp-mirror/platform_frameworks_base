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

import android.annotation.IntDef;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.text.format.DateUtils;

import com.android.internal.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Utilities class for the remote bugreport operation.
 */
class RemoteBugreportUtils {

    static final int NOTIFICATION_ID = 678432343;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
        NOTIFICATION_BUGREPORT_STARTED,
        NOTIFICATION_BUGREPORT_ACCEPTED_NOT_FINISHED,
        NOTIFICATION_BUGREPORT_FINISHED_NOT_ACCEPTED
    })
    @interface RemoteBugreportNotificationType {}
    static final int NOTIFICATION_BUGREPORT_STARTED = 1;
    static final int NOTIFICATION_BUGREPORT_ACCEPTED_NOT_FINISHED = 2;
    static final int NOTIFICATION_BUGREPORT_FINISHED_NOT_ACCEPTED = 3;

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

    static Notification buildNotification(Context context,
            @RemoteBugreportNotificationType int type) {
        Notification.Builder builder = new Notification.Builder(context)
                .setSmallIcon(com.android.internal.R.drawable.stat_sys_adb)
                .setOngoing(true)
                .setLocalOnly(true)
                .setColor(context.getColor(
                        com.android.internal.R.color.system_notification_accent_color));

        if (type == NOTIFICATION_BUGREPORT_ACCEPTED_NOT_FINISHED) {
            builder.setContentTitle(context.getString(
                            R.string.sharing_remote_bugreport_notification_title))
                    .setContentText(context.getString(
                            R.string.sharing_remote_bugreport_notification_message))
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setProgress(0, 0, true)
                    .setStyle(new Notification.BigTextStyle().bigText(context.getString(
                            R.string.sharing_remote_bugreport_notification_message)));
        } else {
            PendingIntent pendingIntentAccept = PendingIntent.getBroadcast(context, NOTIFICATION_ID,
                    new Intent(ACTION_REMOTE_BUGREPORT_SHARING_ACCEPTED),
                    PendingIntent.FLAG_CANCEL_CURRENT);
            PendingIntent pendingIntentDecline = PendingIntent.getBroadcast(context,
                    NOTIFICATION_ID, new Intent(ACTION_REMOTE_BUGREPORT_SHARING_DECLINED),
                    PendingIntent.FLAG_CANCEL_CURRENT);
            builder.addAction(new Notification.Action.Builder(null /* icon */, context.getString(
                            R.string.share_remote_bugreport_notification_decline),
                            pendingIntentDecline).build())
                    .addAction(new Notification.Action.Builder(null /* icon */, context.getString(
                            R.string.share_remote_bugreport_notification_accept),
                            pendingIntentAccept).build())
                    .setContentTitle(context.getString(
                            R.string.share_remote_bugreport_notification_title));

            if (type == NOTIFICATION_BUGREPORT_STARTED) {
                builder.setContentText(context.getString(
                                R.string.share_remote_bugreport_notification_message))
                        .setStyle(new Notification.BigTextStyle().bigText(context.getString(
                                R.string.share_remote_bugreport_notification_message)))
                        .setProgress(0, 0, true)
                        .setPriority(Notification.PRIORITY_MAX)
                        .setVibrate(new long[0]);
            } else if (type == NOTIFICATION_BUGREPORT_FINISHED_NOT_ACCEPTED) {
                builder.setContentText(context.getString(
                                R.string.share_finished_remote_bugreport_notification_message))
                        .setStyle(new Notification.BigTextStyle().bigText(context.getString(
                                R.string.share_finished_remote_bugreport_notification_message)))
                        .setPriority(Notification.PRIORITY_HIGH);
            }
        }

        return builder.build();
    }
}

