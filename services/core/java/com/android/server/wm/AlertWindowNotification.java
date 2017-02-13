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

import static android.app.Notification.VISIBILITY_PRIVATE;
import static android.app.NotificationManager.IMPORTANCE_MIN;
import static android.app.PendingIntent.FLAG_CANCEL_CURRENT;
import static android.content.Context.NOTIFICATION_SERVICE;
import static android.content.Intent.EXTRA_PACKAGE_NAME;
import static android.content.Intent.EXTRA_UID;
import static com.android.server.wm.WindowManagerService.ACTION_REVOKE_SYSTEM_ALERT_WINDOW_PERMISSION;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import com.android.internal.R;

/** Displays an ongoing notification for a process displaying an alert window */
class AlertWindowNotification {
    private static final String CHANNEL_PREFIX = "com.android.server.wm.AlertWindowNotification - ";
    private static final int NOTIFICATION_ID = 0;

    private static int sNextRequestCode = 0;
    private final int mRequestCode;
    private final WindowManagerService mService;
    private String mNotificationTag;
    private final NotificationManager mNotificationManager;
    private final String mPackageName;
    private final int mUid;
    private boolean mCancelled;

    AlertWindowNotification(WindowManagerService service, String packageName, int uid) {
        mService = service;
        mPackageName = packageName;
        mUid = uid;
        mNotificationManager =
                (NotificationManager) mService.mContext.getSystemService(NOTIFICATION_SERVICE);
        mNotificationTag = CHANNEL_PREFIX + mPackageName;
        mRequestCode = sNextRequestCode++;

        // We can't create/post the notification while the window manager lock is held since it will
        // end up calling into activity manager. So, we post a message to do it later.
        mService.mH.post(this::postNotification);
    }

    /** Cancels the notification */
    void cancel() {
        mNotificationManager.cancel(mNotificationTag, NOTIFICATION_ID);
        mCancelled = true;
    }

    /** Don't call with the window manager lock held! */
    private void postNotification() {
        final Context context = mService.mContext;
        final PackageManager pm = context.getPackageManager();
        final ApplicationInfo aInfo = getApplicationInfo(pm, mPackageName);
        final String appName = (aInfo != null)
                ? pm.getApplicationLabel(aInfo).toString() : mPackageName;

        createNotificationChannelIfNeeded(context, appName);

        final String message = context.getString(R.string.alert_windows_notification_message);
        final Notification.Builder builder = new Notification.Builder(context, mNotificationTag)
                .setOngoing(true)
                .setContentTitle(
                        context.getString(R.string.alert_windows_notification_title, appName))
                .setContentText(message)
                .setSmallIcon(R.drawable.alert_window_layer)
                .setColor(context.getColor(R.color.system_notification_accent_color))
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setLocalOnly(true)
                .addAction(getTurnOffAction(context, mPackageName, mUid));

        if (aInfo != null) {
            final Bitmap bitmap = ((BitmapDrawable) pm.getApplicationIcon(aInfo)).getBitmap();
            builder.setLargeIcon(bitmap);
        }

        synchronized (mService.mWindowMap) {
            if (mCancelled) {
                // Notification was cancelled, so nothing more to do...
                return;
            }
            mNotificationManager.notify(mNotificationTag, NOTIFICATION_ID, builder.build());
        }
    }

    private Notification.Action getTurnOffAction(Context context, String packageName, int uid) {
        final Intent intent = new Intent(ACTION_REVOKE_SYSTEM_ALERT_WINDOW_PERMISSION);
        intent.putExtra(EXTRA_PACKAGE_NAME, packageName);
        intent.putExtra(EXTRA_UID, uid);
        // Calls into activity manager...
        final PendingIntent pendingIntent = PendingIntent.getBroadcast(context, mRequestCode,
                intent, FLAG_CANCEL_CURRENT);
        return new Notification.Action.Builder(R.drawable.alert_window_layer,
                context.getString(R.string.alert_windows_notification_turn_off_action),
                pendingIntent).build();

    }

    private void createNotificationChannelIfNeeded(Context context, String appName) {
        if (mNotificationManager.getNotificationChannel(mNotificationTag) != null) {
            return;
        }
        final String nameChannel =
                context.getString(R.string.alert_windows_notification_channel_name, appName);
        final NotificationChannel channel =
                new NotificationChannel(mNotificationTag, nameChannel, IMPORTANCE_MIN);
        channel.enableLights(false);
        channel.enableVibration(false);
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
