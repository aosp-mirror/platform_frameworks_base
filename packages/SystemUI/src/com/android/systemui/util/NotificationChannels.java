/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.util;

import android.app.NotificationChannel;
import android.app.NotificationManager;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.net.Uri;
import android.provider.Settings;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;
import com.android.systemui.SystemUI;

import java.util.Arrays;

public class NotificationChannels extends SystemUI {
    public static String ALERTS      = "ALR";
    public static String SCREENSHOTS = "SCN";
    public static String GENERAL     = "GEN";
    public static String STORAGE     = "DSK";
    public static String TVPIP       = "TPP";
    public static String BATTERY     = "BAT";

    @VisibleForTesting
    static void createAll(Context context) {
        final NotificationManager nm = context.getSystemService(NotificationManager.class);
        NotificationChannel batteryChannel = new NotificationChannel(BATTERY,
                context.getString(R.string.notification_channel_battery),
                NotificationManager.IMPORTANCE_MAX);
        final String soundPath = Settings.Global.getString(context.getContentResolver(),
                Settings.Global.LOW_BATTERY_SOUND);
        batteryChannel.setSound(Uri.parse("file://" + soundPath), new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .build());

        nm.createNotificationChannels(Arrays.asList(
                new NotificationChannel(
                        ALERTS,
                        context.getString(R.string.notification_channel_alerts),
                        NotificationManager.IMPORTANCE_HIGH),
                new NotificationChannel(
                        SCREENSHOTS,
                        context.getString(R.string.notification_channel_screenshot),
                        NotificationManager.IMPORTANCE_LOW),
                new NotificationChannel(
                        GENERAL,
                        context.getString(R.string.notification_channel_general),
                        NotificationManager.IMPORTANCE_MIN),
                new NotificationChannel(
                        STORAGE,
                        context.getString(R.string.notification_channel_storage),
                        isTv(context)
                                ? NotificationManager.IMPORTANCE_DEFAULT
                                : NotificationManager.IMPORTANCE_LOW),
                batteryChannel
        ));

        if (isTv(context)) {
            // TV specific notification channel for TV PIP controls.
            // Importance should be {@link NotificationManager#IMPORTANCE_MAX} to have the highest
            // priority, so it can be shown in all times.
            nm.createNotificationChannel(new NotificationChannel(
                    TVPIP,
                    context.getString(R.string.notification_channel_tv_pip),
                    NotificationManager.IMPORTANCE_MAX));
        }
    }

    @Override
    public void start() {
        createAll(mContext);
    }

    private static boolean isTv(Context context) {
        PackageManager packageManager = context.getPackageManager();
        return packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }
}
