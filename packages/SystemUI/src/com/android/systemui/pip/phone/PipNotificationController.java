/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.pip.phone;

import static android.app.NotificationManager.IMPORTANCE_MIN;
import static android.app.PendingIntent.FLAG_CANCEL_CURRENT;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.provider.Settings.ACTION_PICTURE_IN_PICTURE_SETTINGS;

import android.app.IActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.util.Log;

import com.android.systemui.R;
import com.android.systemui.SystemUI;

/**
 * Manages the BTW notification that shows whenever an activity enters or leaves picture-in-picture.
 */
public class PipNotificationController {
    private static final String TAG = PipNotificationController.class.getSimpleName();

    private static final String CHANNEL_ID = PipNotificationController.class.getName();
    private static final int BTW_NOTIFICATION_ID = 0;

    private Context mContext;
    private IActivityManager mActivityManager;
    private NotificationManager mNotificationManager;

    public PipNotificationController(Context context, IActivityManager activityManager) {
        mContext = context;
        mActivityManager = activityManager;
        mNotificationManager = NotificationManager.from(context);
        createNotificationChannel();
    }

    public void onActivityPinned(String packageName) {
        // Clear any existing notification
        mNotificationManager.cancel(CHANNEL_ID, BTW_NOTIFICATION_ID);

        // Build a new notification
        final Notification.Builder builder = new Notification.Builder(mContext, CHANNEL_ID)
                .setLocalOnly(true)
                .setOngoing(true)
                .setSmallIcon(R.drawable.pip_notification_icon)
                .setColor(mContext.getColor(
                        com.android.internal.R.color.system_notification_accent_color));
        if (updateNotificationForApp(builder, packageName)) {
            SystemUI.overrideNotificationAppName(mContext, builder);

            // Show the new notification
            mNotificationManager.notify(CHANNEL_ID, BTW_NOTIFICATION_ID, builder.build());
        }
    }

    public void onActivityUnpinned() {
        ComponentName topPipActivity = PipUtils.getTopPinnedActivity(mContext, mActivityManager);
        if (topPipActivity != null) {
            onActivityPinned(topPipActivity.getPackageName());
        } else {
            mNotificationManager.cancel(CHANNEL_ID, BTW_NOTIFICATION_ID);
        }
    }

    /**
     * Create the notification channel for the PiP BTW notifications if necessary.
     */
    private NotificationChannel createNotificationChannel() {
        NotificationChannel channel = mNotificationManager.getNotificationChannel(CHANNEL_ID);
        if (channel == null) {
            channel = new NotificationChannel(CHANNEL_ID,
                    mContext.getString(R.string.pip_notification_channel_name), IMPORTANCE_MIN);
            channel.enableLights(false);
            channel.enableVibration(false);
            mNotificationManager.createNotificationChannel(channel);
        }
        return channel;
    }

    /**
     * Updates the notification builder with app-specific information, returning whether it was
     * successful.
     */
    private boolean updateNotificationForApp(Notification.Builder builder, String packageName) {
        final PackageManager pm = mContext.getPackageManager();
        final ApplicationInfo appInfo;
        try {
            appInfo = pm.getApplicationInfo(packageName, 0);
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Could not update notification for application", e);
            return false;
        }

        if (appInfo != null) {
            final String appName = pm.getApplicationLabel(appInfo).toString();
            final String message = mContext.getString(R.string.pip_notification_message, appName);
            final Intent settingsIntent = new Intent(ACTION_PICTURE_IN_PICTURE_SETTINGS,
                    Uri.fromParts("package", packageName, null));
            settingsIntent.setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK);
            final Icon appIcon = appInfo.icon != 0
                    ? Icon.createWithResource(packageName, appInfo.icon)
                    : Icon.createWithResource(Resources.getSystem(),
                            com.android.internal.R.drawable.sym_def_app_icon);

            builder.setContentTitle(mContext.getString(R.string.pip_notification_title, appName))
                    .setContentText(message)
                    .setContentIntent(PendingIntent.getActivity(mContext, packageName.hashCode(),
                            settingsIntent, FLAG_CANCEL_CURRENT))
                    .setStyle(new Notification.BigTextStyle().bigText(message))
                    .setLargeIcon(appIcon);
            return true;
        }
        return false;
    }
}
