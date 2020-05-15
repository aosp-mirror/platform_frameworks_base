/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.debug;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.internal.notification.SystemNotificationChannels;

/**
 * Utility class for building adb notifications.
 * @hide
 */
public final class AdbNotifications {
    /**
     * Notification channel for tv types.
     */
    private static final String ADB_NOTIFICATION_CHANNEL_ID_TV = "usbdevicemanager.adb.tv";

    /**
     * Builds a notification to show connected state for adb over a transport type.
     * @param context the context
     * @param transportType the adb transport type.
     * @return a newly created Notification for the transport type, or null on error.
     */
    @Nullable
    public static Notification createNotification(@NonNull Context context,
            byte transportType) {
        Resources resources = context.getResources();
        int titleId;
        int messageId;

        if (transportType == AdbTransportType.USB) {
            titleId = com.android.internal.R.string.adb_active_notification_title;
            messageId = com.android.internal.R.string.adb_active_notification_message;
        } else if (transportType == AdbTransportType.WIFI) {
            titleId = com.android.internal.R.string.adbwifi_active_notification_title;
            messageId = com.android.internal.R.string.adbwifi_active_notification_message;
        } else {
            throw new IllegalArgumentException(
                    "createNotification called with unknown transport type=" + transportType);
        }

        CharSequence title = resources.getText(titleId);
        CharSequence message = resources.getText(messageId);

        Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        ResolveInfo resolveInfo = context.getPackageManager().resolveActivity(intent,
                PackageManager.MATCH_SYSTEM_ONLY);
        // Settings app may not be available (e.g. device policy manager removes it)
        PendingIntent pIntent = null;
        if (resolveInfo != null) {
            intent.setPackage(resolveInfo.activityInfo.packageName);
            pIntent = PendingIntent.getActivityAsUser(context, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE, null, UserHandle.CURRENT);
        }


        return new Notification.Builder(context, SystemNotificationChannels.DEVELOPER_IMPORTANT)
                .setSmallIcon(com.android.internal.R.drawable.stat_sys_adb)
                .setWhen(0)
                .setOngoing(true)
                .setTicker(title)
                .setDefaults(0)  // please be quiet
                .setColor(context.getColor(
                            com.android.internal.R.color.system_notification_accent_color))
                .setContentTitle(title)
                .setContentText(message)
                .setContentIntent(pIntent)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .extend(new Notification.TvExtender()
                        .setChannelId(ADB_NOTIFICATION_CHANNEL_ID_TV))
                .build();
    }
}
