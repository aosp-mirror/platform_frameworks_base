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
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;
import android.provider.Settings;
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
        DevicePolicyManager.NOTIFICATION_BUGREPORT_STARTED,
        DevicePolicyManager.NOTIFICATION_BUGREPORT_ACCEPTED_NOT_FINISHED,
        DevicePolicyManager.NOTIFICATION_BUGREPORT_FINISHED_NOT_ACCEPTED
    })
    @interface RemoteBugreportNotificationType {}

    static final long REMOTE_BUGREPORT_TIMEOUT_MILLIS = 10 * DateUtils.MINUTE_IN_MILLIS;

    static final String CTL_STOP = "ctl.stop";
    static final String REMOTE_BUGREPORT_SERVICE = "bugreportremote";

    static final String BUGREPORT_MIMETYPE = "application/vnd.android.bugreport";

    static Notification buildNotification(Context context,
            @RemoteBugreportNotificationType int type) {
        Intent dialogIntent = new Intent(Settings.ACTION_SHOW_REMOTE_BUGREPORT_DIALOG);
        dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        dialogIntent.putExtra(DevicePolicyManager.EXTRA_BUGREPORT_NOTIFICATION_TYPE, type);
        PendingIntent pendingDialogIntent = PendingIntent.getActivityAsUser(context, type,
                dialogIntent, 0, null, UserHandle.CURRENT);

        Notification.Builder builder = new Notification.Builder(context)
                .setSmallIcon(com.android.internal.R.drawable.stat_sys_adb)
                .setOngoing(true)
                .setLocalOnly(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .setContentIntent(pendingDialogIntent)
                .setColor(context.getColor(
                        com.android.internal.R.color.system_notification_accent_color));

        if (type == DevicePolicyManager.NOTIFICATION_BUGREPORT_ACCEPTED_NOT_FINISHED) {
            builder.setContentTitle(context.getString(
                        R.string.sharing_remote_bugreport_notification_title))
                    .setProgress(0, 0, true);
        } else if (type == DevicePolicyManager.NOTIFICATION_BUGREPORT_STARTED) {
            builder.setContentTitle(context.getString(
                        R.string.taking_remote_bugreport_notification_title))
                    .setProgress(0, 0, true);
        } else if (type == DevicePolicyManager.NOTIFICATION_BUGREPORT_FINISHED_NOT_ACCEPTED) {
            PendingIntent pendingIntentAccept = PendingIntent.getBroadcast(context, NOTIFICATION_ID,
                    new Intent(DevicePolicyManager.ACTION_BUGREPORT_SHARING_ACCEPTED),
                    PendingIntent.FLAG_CANCEL_CURRENT);
            PendingIntent pendingIntentDecline = PendingIntent.getBroadcast(context,
                    NOTIFICATION_ID, new Intent(
                            DevicePolicyManager.ACTION_BUGREPORT_SHARING_DECLINED),
                    PendingIntent.FLAG_CANCEL_CURRENT);
            builder.addAction(new Notification.Action.Builder(null /* icon */, context.getString(
                        R.string.decline_remote_bugreport_action), pendingIntentDecline).build())
                    .addAction(new Notification.Action.Builder(null /* icon */, context.getString(
                        R.string.share_remote_bugreport_action), pendingIntentAccept).build())
                    .setContentTitle(context.getString(
                        R.string.share_remote_bugreport_notification_title))
                    .setContentText(context.getString(
                        R.string.share_remote_bugreport_notification_message_finished))
                    .setStyle(new Notification.BigTextStyle().bigText(context.getString(
                        R.string.share_remote_bugreport_notification_message_finished)));
        }

        return builder.build();
    }
}

