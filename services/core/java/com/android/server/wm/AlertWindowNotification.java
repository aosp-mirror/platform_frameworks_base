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
 * limitations under the License
 */

package com.android.server.wm;

import static android.app.NotificationManager.IMPORTANCE_MIN;
import static android.app.PendingIntent.FLAG_CANCEL_CURRENT;
import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.content.Context.NOTIFICATION_SERVICE;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.provider.Settings.ACTION_MANAGE_APP_OVERLAY_PERMISSION;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;

import com.android.internal.R;
import com.android.internal.util.ImageUtils;

/** Displays an ongoing notification for a process displaying an alert window */
class AlertWindowNotification {
    private static final String CHANNEL_PREFIX = "com.android.server.wm.AlertWindowNotification - ";
    private static final int NOTIFICATION_ID = 0;

    private static int sNextRequestCode = 0;
    private static NotificationChannelGroup sChannelGroup;
    private final int mRequestCode;
    private final WindowManagerService mService;
    private String mNotificationTag;
    private final NotificationManager mNotificationManager;
    private final String mPackageName;
    private boolean mPosted;

    AlertWindowNotification(WindowManagerService service, String packageName) {
        mService = service;
        mPackageName = packageName;
        mNotificationManager =
                (NotificationManager) mService.mContext.getSystemService(NOTIFICATION_SERVICE);
        mNotificationTag = CHANNEL_PREFIX + mPackageName;
        mRequestCode = sNextRequestCode++;
    }

    void post() {
        // We can't create/post the notification while the window manager lock is held since it will
        // end up calling into activity manager. So, we post a message to do it later.
        mService.mH.post(this::onPostNotification);
    }

    /** Cancels the notification */
    void cancel(boolean deleteChannel) {
        // We can't call into NotificationManager with WM lock held since it might call into AM.
        // So, we post a message to do it later.
        mService.mH.post(() -> onCancelNotification(deleteChannel));
    }

    /** Don't call with the window manager lock held! */
    private void onCancelNotification(boolean deleteChannel) {
        if (!mPosted) {
            // Notification isn't currently posted...
            return;
        }
        mPosted = false;
        mNotificationManager.cancel(mNotificationTag, NOTIFICATION_ID);
        if (deleteChannel) {
            mNotificationManager.deleteNotificationChannel(mNotificationTag);
        }
    }

    /** Don't call with the window manager lock held! */
    private void onPostNotification() {
        if (mPosted) {
            // Notification already posted...
            return;
        }
        mPosted = true;

        final Context context = mService.mContext;
        final PackageManager pm = context.getPackageManager();
        final ApplicationInfo aInfo = getApplicationInfo(pm, mPackageName);
        final String appName = (aInfo != null)
                ? pm.getApplicationLabel(aInfo).toString() : mPackageName;

        createNotificationChannel(context, appName);

        final String message = context.getString(R.string.alert_windows_notification_message,
                appName);
        Bundle extras = new Bundle();
        extras.putStringArray(Notification.EXTRA_FOREGROUND_APPS, new String[] {mPackageName});
        final Notification.Builder builder = new Notification.Builder(context, mNotificationTag)
                .setOngoing(true)
                .setContentTitle(
                        context.getString(R.string.alert_windows_notification_title, appName))
                .setContentText(message)
                .setSmallIcon(R.drawable.alert_window_layer)
                .setColor(context.getColor(R.color.system_notification_accent_color))
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setLocalOnly(true)
                .addExtras(extras)
                .setContentIntent(getContentIntent(context, mPackageName));

        if (aInfo != null) {
            final Drawable drawable = pm.getApplicationIcon(aInfo);
            int size = context.getResources().getDimensionPixelSize(android.R.dimen.app_icon_size);
            final Bitmap bitmap = ImageUtils.buildScaledBitmap(drawable, size, size);
            if (bitmap != null) {
                builder.setLargeIcon(bitmap);
            }
        }

        mNotificationManager.notify(mNotificationTag, NOTIFICATION_ID, builder.build());
    }

    private PendingIntent getContentIntent(Context context, String packageName) {
        final Intent intent = new Intent(ACTION_MANAGE_APP_OVERLAY_PERMISSION,
                Uri.fromParts("package", packageName, null));
        intent.setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK);
        // Calls into activity manager...
        return PendingIntent.getActivity(context, mRequestCode, intent,
                FLAG_CANCEL_CURRENT | FLAG_IMMUTABLE);
    }

    private void createNotificationChannel(Context context, String appName) {
        if (sChannelGroup == null) {
            sChannelGroup = new NotificationChannelGroup(CHANNEL_PREFIX,
                    mService.mContext.getString(
                            R.string.alert_windows_notification_channel_group_name));
            mNotificationManager.createNotificationChannelGroup(sChannelGroup);
        }

        final String nameChannel =
                context.getString(R.string.alert_windows_notification_channel_name, appName);

        NotificationChannel channel = mNotificationManager.getNotificationChannel(mNotificationTag);
        if (channel != null) {
            return;
        }
        channel = new NotificationChannel(mNotificationTag, nameChannel, IMPORTANCE_MIN);
        channel.enableLights(false);
        channel.enableVibration(false);
        channel.setBlockable(true);
        channel.setGroup(sChannelGroup.getId());
        channel.setBypassDnd(true);
        mNotificationManager.createNotificationChannel(channel);
    }


    private ApplicationInfo getApplicationInfo(PackageManager pm, String packageName) {
        try {
            return pm.getApplicationInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }
}
