/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.biometrics.sensors.face;

import android.annotation.NonNull;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;

import com.android.internal.R;

public class ReEnrollNotificationUtils {

    private static final String NOTIFICATION_TAG = "FaceService";
    private static final int NOTIFICATION_ID = 1;

    public static void showReEnrollmentNotification(@NonNull Context context) {
        final NotificationManager notificationManager =
                context.getSystemService(NotificationManager.class);

        final String name =
                context.getString(R.string.face_recalibrate_notification_name);
        final String title =
                context.getString(R.string.face_recalibrate_notification_title);
        final String content =
                context.getString(R.string.face_recalibrate_notification_content);

        final Intent intent = new Intent("android.settings.FACE_SETTINGS");
        intent.setPackage("com.android.settings");

        final PendingIntent pendingIntent = PendingIntent.getActivityAsUser(context,
                0 /* requestCode */, intent, PendingIntent.FLAG_IMMUTABLE /* flags */,
                null /* options */, UserHandle.CURRENT);

        final String channelName = "FaceEnrollNotificationChannel";

        final NotificationChannel channel = new NotificationChannel(channelName, name,
                NotificationManager.IMPORTANCE_HIGH);
        final Notification notification = new Notification.Builder(context, channelName)
                .setSmallIcon(R.drawable.ic_lock)
                .setContentTitle(title)
                .setContentText(content)
                .setSubText(name)
                .setOnlyAlertOnce(true)
                .setLocalOnly(true)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_SYSTEM)
                .setContentIntent(pendingIntent)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .build();

        notificationManager.createNotificationChannel(channel);
        notificationManager.notifyAsUser(NOTIFICATION_TAG,
                NOTIFICATION_ID, notification,
                UserHandle.CURRENT);
    }

    public static void cancelNotification(@NonNull Context context) {
        final NotificationManager notificationManager =
                context.getSystemService(NotificationManager.class);
        notificationManager.cancelAsUser(NOTIFICATION_TAG, NOTIFICATION_ID, UserHandle.CURRENT);
    }

}
