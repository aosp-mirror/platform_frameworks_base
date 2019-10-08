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
    public static String SCREENSHOTS_LEGACY = "SCN";
    public static String SCREENSHOTS_HEADSUP = "SCN_HEADSUP";
    public static String GENERAL     = "GEN";
    public static String STORAGE     = "DSK";
    public static String TVPIP       = "TPP";
    public static String BATTERY     = "BAT";
    public static String HINTS       = "HNT";

    public static void createAll(Context context) {
        final NotificationManager nm = context.getSystemService(NotificationManager.class);
        final NotificationChannel batteryChannel = new NotificationChannel(BATTERY,
                context.getString(R.string.notification_channel_battery),
                NotificationManager.IMPORTANCE_MAX);
        final String soundPath = Settings.Global.getString(context.getContentResolver(),
                Settings.Global.LOW_BATTERY_SOUND);
        batteryChannel.setSound(Uri.parse("file://" + soundPath), new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .build());
        batteryChannel.setBlockableSystem(true);

        final NotificationChannel alerts = new NotificationChannel(
                ALERTS,
                context.getString(R.string.notification_channel_alerts),
                NotificationManager.IMPORTANCE_HIGH);

        final NotificationChannel general = new NotificationChannel(
                GENERAL,
                context.getString(R.string.notification_channel_general),
                NotificationManager.IMPORTANCE_MIN);

        final NotificationChannel storage = new NotificationChannel(
                STORAGE,
                context.getString(R.string.notification_channel_storage),
                isTv(context)
                        ? NotificationManager.IMPORTANCE_DEFAULT
                        : NotificationManager.IMPORTANCE_LOW);

        final NotificationChannel hint = new NotificationChannel(
                HINTS,
                context.getString(R.string.notification_channel_hints),
                NotificationManager.IMPORTANCE_DEFAULT);
        // No need to bypass DND.

        nm.createNotificationChannels(Arrays.asList(
                alerts,
                general,
                storage,
                createScreenshotChannel(
                        context.getString(R.string.notification_channel_screenshot),
                        nm.getNotificationChannel(SCREENSHOTS_LEGACY)),
                batteryChannel,
                hint
        ));

        // Delete older SS channel if present.
        // Screenshots promoted to heads-up in P, this cleans up the lower priority channel from O.
        // This line can be deleted in Q.
        nm.deleteNotificationChannel(SCREENSHOTS_LEGACY);


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

    /**
     * Set up screenshot channel, respecting any previously committed user settings on legacy
     * channel.
     * @return
     */
    @VisibleForTesting static NotificationChannel createScreenshotChannel(
            String name, NotificationChannel legacySS) {
        NotificationChannel screenshotChannel = new NotificationChannel(SCREENSHOTS_HEADSUP,
                name, NotificationManager.IMPORTANCE_HIGH); // pop on screen

        screenshotChannel.setSound(null, // silent
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build());
        screenshotChannel.setBlockableSystem(true);

        if (legacySS != null) {
            // Respect any user modified fields from the old channel.
            int userlock = legacySS.getUserLockedFields();
            if ((userlock & NotificationChannel.USER_LOCKED_IMPORTANCE) != 0) {
                screenshotChannel.setImportance(legacySS.getImportance());
            }
            if ((userlock & NotificationChannel.USER_LOCKED_SOUND) != 0)  {
                screenshotChannel.setSound(legacySS.getSound(), legacySS.getAudioAttributes());
            }
            if ((userlock & NotificationChannel.USER_LOCKED_VIBRATION) != 0)  {
                screenshotChannel.setVibrationPattern(legacySS.getVibrationPattern());
            }
            if ((userlock & NotificationChannel.USER_LOCKED_LIGHTS) != 0)  {
                screenshotChannel.setLightColor(legacySS.getLightColor());
            }
            // skip show_badge, irrelevant for system channel
        }

        return screenshotChannel;
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
