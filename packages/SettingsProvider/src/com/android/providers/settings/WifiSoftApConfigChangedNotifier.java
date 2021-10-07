/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.providers.settings;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.internal.messages.nano.SystemMessageProto;
import com.android.internal.notification.SystemNotificationChannels;

import java.util.List;

/**
 * Helper class for sending notifications when the user's Soft AP config was changed upon restore.
 */
public class WifiSoftApConfigChangedNotifier {
    private WifiSoftApConfigChangedNotifier() {}

    /**
     * Send a notification informing the user that their' Soft AP Config was changed upon restore.
     * When the user taps on the notification, they are taken to the Wifi Tethering page in
     * Settings.
     */
    public static void notifyUserOfConfigConversion(Context context) {
        NotificationManager notificationManager =
                context.getSystemService(NotificationManager.class);

        // create channel, or update it if it already exists
        NotificationChannel channel = new NotificationChannel(
                SystemNotificationChannels.NETWORK_STATUS,
                context.getString(
                        com.android.internal.R.string.notification_channel_network_status),
                NotificationManager.IMPORTANCE_LOW);
        notificationManager.createNotificationChannel(channel);

        notificationManager.notify(
                SystemMessageProto.SystemMessage.NOTE_SOFTAP_CONFIG_CHANGED,
                createConversionNotification(context));
    }

    private static Notification createConversionNotification(Context context) {
        Resources resources = context.getResources();
        CharSequence title = resources.getText(R.string.wifi_softap_config_change);
        CharSequence contentSummary = resources.getText(R.string.wifi_softap_config_change_summary);
        int color = resources.getColor(
                android.R.color.system_notification_accent_color, context.getTheme());

        return new Notification.Builder(context, SystemNotificationChannels.NETWORK_STATUS)
                .setSmallIcon(R.drawable.ic_wifi_settings)
                .setPriority(Notification.PRIORITY_HIGH)
                .setCategory(Notification.CATEGORY_SYSTEM)
                .setContentTitle(title)
                .setContentText(contentSummary)
                .setContentIntent(getPendingActivity(context))
                .setTicker(title)
                .setShowWhen(false)
                .setLocalOnly(true)
                .setColor(color)
                .setStyle(new Notification.BigTextStyle()
                        .setBigContentTitle(title)
                        .setSummaryText(contentSummary))
                .setAutoCancel(true)
                .build();
    }

    private static PendingIntent getPendingActivity(Context context) {
        Intent intent = new Intent("com.android.settings.WIFI_TETHER_SETTINGS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .setPackage(getSettingsPackageName(context));
        return PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * @return Get settings package name.
     */
    private static String getSettingsPackageName(Context context) {
        if (context == null) return null;

        Intent intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
        List<ResolveInfo> resolveInfos = context.getPackageManager().queryIntentActivitiesAsUser(
                intent, PackageManager.MATCH_SYSTEM_ONLY | PackageManager.MATCH_DEFAULT_ONLY,
                UserHandle.of(ActivityManager.getCurrentUser()));
        if (resolveInfos == null || resolveInfos.isEmpty()) {
            return "com.android.settings";
        }
        return resolveInfos.get(0).activityInfo.packageName;
    }
}
