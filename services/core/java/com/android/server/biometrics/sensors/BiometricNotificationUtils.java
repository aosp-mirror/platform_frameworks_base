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

package com.android.server.biometrics.sensors;

import android.annotation.NonNull;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.R;

/**
 * Biometric notification helper class.
 */
public class BiometricNotificationUtils {

    private static final String TAG = "BiometricNotificationUtils";
    private static final String RE_ENROLL_NOTIFICATION_TAG = "FaceService";
    private static final String BAD_CALIBRATION_NOTIFICATION_TAG = "FingerprintService";
    private static final int NOTIFICATION_ID = 1;
    private static final long NOTIFICATION_INTERVAL_MS = 24 * 60 * 60 * 1000;
    private static long sLastAlertTime = 0;

    /**
     * Shows a face re-enrollment notification.
     */
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

        showNotificationHelper(context, name, title, content, pendingIntent, channelName,
                RE_ENROLL_NOTIFICATION_TAG);
    }

    /**
     * Shows a fingerprint bad calibration notification.
     */
    public static void showBadCalibrationNotification(@NonNull Context context) {
        final long currentTime = SystemClock.elapsedRealtime();
        final long timeSinceLastAlert = currentTime - sLastAlertTime;

        // Only show the notification if not previously shown or a day has
        // passed since the last notification.
        if (sLastAlertTime != 0 && (timeSinceLastAlert < NOTIFICATION_INTERVAL_MS)) {
            Slog.v(TAG, "Skipping calibration notification : " + timeSinceLastAlert);
            return;
        }

        sLastAlertTime = currentTime;

        final String name =
                context.getString(R.string.fingerprint_recalibrate_notification_name);
        final String title =
                context.getString(R.string.fingerprint_recalibrate_notification_title);
        final String content =
                context.getString(R.string.fingerprint_recalibrate_notification_content);

        final Intent intent = new Intent("android.settings.FINGERPRINT_SETTINGS");
        intent.setPackage("com.android.settings");

        final PendingIntent pendingIntent = PendingIntent.getActivityAsUser(context,
                0 /* requestCode */, intent, PendingIntent.FLAG_IMMUTABLE /* flags */,
                null /* options */, UserHandle.CURRENT);

        final String channelName = "FingerprintBadCalibrationNotificationChannel";

        showNotificationHelper(context, name, title, content, pendingIntent, channelName,
                BAD_CALIBRATION_NOTIFICATION_TAG);
    }

    private static void showNotificationHelper(Context context, String name, String title,
                String content, PendingIntent pendingIntent, String channelName,
                String notificationTag) {
        final NotificationManager notificationManager =
                context.getSystemService(NotificationManager.class);
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
        notificationManager.notifyAsUser(notificationTag, NOTIFICATION_ID, notification,
                UserHandle.CURRENT);
    }

    /**
     * Cancels a face re-enrollment notification
     */
    public static void cancelReEnrollNotification(@NonNull Context context) {
        final NotificationManager notificationManager =
                context.getSystemService(NotificationManager.class);
        notificationManager.cancelAsUser(RE_ENROLL_NOTIFICATION_TAG, NOTIFICATION_ID,
                UserHandle.CURRENT);
    }

    /**
     * Cancels a fingerprint bad calibration notification
     */
    public static void cancelBadCalibrationNotification(@NonNull Context context) {
        final NotificationManager notificationManager =
                context.getSystemService(NotificationManager.class);
        notificationManager.cancelAsUser(BAD_CALIBRATION_NOTIFICATION_TAG, NOTIFICATION_ID,
                UserHandle.CURRENT);
    }

}
