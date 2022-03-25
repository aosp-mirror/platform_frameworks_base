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

package com.android.systemui.screenshot;

import static android.content.Context.NOTIFICATION_SERVICE;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.UserHandle;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import com.android.internal.messages.nano.SystemMessageProto;
import com.android.systemui.R;
import com.android.systemui.SystemUIApplication;
import com.android.systemui.util.NotificationChannels;

import javax.inject.Inject;

/**
 * Convenience class to handle showing and hiding notifications while taking a screenshot.
 */
public class ScreenshotNotificationsController {
    private static final String TAG = "ScreenshotNotificationManager";

    private final Context mContext;
    private final Resources mResources;
    private final NotificationManager mNotificationManager;

    @Inject
    ScreenshotNotificationsController(Context context, WindowManager windowManager) {
        mContext = context;
        mResources = context.getResources();
        mNotificationManager =
                (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);

        DisplayMetrics displayMetrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(displayMetrics);
    }

    /**
     * Sends a notification that the screenshot capture has failed.
     */
    public void notifyScreenshotError(int msgResId) {
        Resources res = mContext.getResources();
        String errorMsg = res.getString(msgResId);

        // Repurpose the existing notification to notify the user of the error
        Notification.Builder b = new Notification.Builder(mContext, NotificationChannels.ALERTS)
                .setTicker(res.getString(R.string.screenshot_failed_title))
                .setContentTitle(res.getString(R.string.screenshot_failed_title))
                .setContentText(errorMsg)
                .setSmallIcon(R.drawable.stat_notify_image_error)
                .setWhen(System.currentTimeMillis())
                .setVisibility(Notification.VISIBILITY_PUBLIC) // ok to show outside lockscreen
                .setCategory(Notification.CATEGORY_ERROR)
                .setAutoCancel(true)
                .setColor(mContext.getColor(
                        com.android.internal.R.color.system_notification_accent_color));
        final DevicePolicyManager dpm =
                (DevicePolicyManager) mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
        final Intent intent =
                dpm.createAdminSupportIntent(DevicePolicyManager.POLICY_DISABLE_SCREEN_CAPTURE);
        if (intent != null) {
            final PendingIntent pendingIntent = PendingIntent.getActivityAsUser(
                    mContext, 0, intent, PendingIntent.FLAG_IMMUTABLE, null, UserHandle.CURRENT);
            b.setContentIntent(pendingIntent);
        }

        SystemUIApplication.overrideNotificationAppName(mContext, b, true);

        Notification n = new Notification.BigTextStyle(b)
                .bigText(errorMsg)
                .build();
        mNotificationManager.notify(SystemMessageProto.SystemMessage.NOTE_GLOBAL_SCREENSHOT, n);
    }
}
