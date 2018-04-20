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

package com.android.internal.notification;

import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.media.AudioAttributes;
import android.os.RemoteException;
import android.provider.Settings;

import com.android.internal.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Manages the NotificationChannels used by the frameworks itself.
public class SystemNotificationChannels {
    public static String VIRTUAL_KEYBOARD  = "VIRTUAL_KEYBOARD";
    public static String PHYSICAL_KEYBOARD = "PHYSICAL_KEYBOARD";
    public static String SECURITY = "SECURITY";
    public static String CAR_MODE = "CAR_MODE";
    public static String ACCOUNT = "ACCOUNT";
    public static String DEVELOPER = "DEVELOPER";
    public static String UPDATES = "UPDATES";
    public static String NETWORK_STATUS = "NETWORK_STATUS";
    public static String NETWORK_ALERTS = "NETWORK_ALERTS";
    public static String NETWORK_AVAILABLE = "NETWORK_AVAILABLE";
    public static String VPN = "VPN";
    public static String DEVICE_ADMIN = "DEVICE_ADMIN";
    public static String ALERTS = "ALERTS";
    public static String RETAIL_MODE = "RETAIL_MODE";
    public static String USB = "USB";
    public static String FOREGROUND_SERVICE = "FOREGROUND_SERVICE";
    public static String HEAVY_WEIGHT_APP = "HEAVY_WEIGHT_APP";
    public static String SYSTEM_CHANGES = "SYSTEM_CHANGES";
    public static String DO_NOT_DISTURB = "DO_NOT_DISTURB";

    public static void createAll(Context context) {
        final NotificationManager nm = context.getSystemService(NotificationManager.class);
        List<NotificationChannel> channelsList = new ArrayList<NotificationChannel>();
        final NotificationChannel keyboard = new NotificationChannel(
                VIRTUAL_KEYBOARD,
                context.getString(R.string.notification_channel_virtual_keyboard),
                NotificationManager.IMPORTANCE_LOW);
        keyboard.setBlockableSystem(true);
        channelsList.add(keyboard);

        final NotificationChannel physicalKeyboardChannel = new NotificationChannel(
                PHYSICAL_KEYBOARD,
                context.getString(R.string.notification_channel_physical_keyboard),
                NotificationManager.IMPORTANCE_DEFAULT);
        physicalKeyboardChannel.setSound(Settings.System.DEFAULT_NOTIFICATION_URI,
                Notification.AUDIO_ATTRIBUTES_DEFAULT);
        physicalKeyboardChannel.setBlockableSystem(true);
        channelsList.add(physicalKeyboardChannel);

        final NotificationChannel security = new NotificationChannel(
                SECURITY,
                context.getString(R.string.notification_channel_security),
                NotificationManager.IMPORTANCE_LOW);
        channelsList.add(security);

        final NotificationChannel car = new NotificationChannel(
                CAR_MODE,
                context.getString(R.string.notification_channel_car_mode),
                NotificationManager.IMPORTANCE_LOW);
        car.setBlockableSystem(true);
        channelsList.add(car);

        channelsList.add(newAccountChannel(context));

        final NotificationChannel developer = new NotificationChannel(
                DEVELOPER,
                context.getString(R.string.notification_channel_developer),
                NotificationManager.IMPORTANCE_LOW);
        developer.setBlockableSystem(true);
        channelsList.add(developer);

        final NotificationChannel updates = new NotificationChannel(
                UPDATES,
                context.getString(R.string.notification_channel_updates),
                NotificationManager.IMPORTANCE_LOW);
        channelsList.add(updates);

        final NotificationChannel network = new NotificationChannel(
                NETWORK_STATUS,
                context.getString(R.string.notification_channel_network_status),
                NotificationManager.IMPORTANCE_LOW);
        channelsList.add(network);

        final NotificationChannel networkAlertsChannel = new NotificationChannel(
                NETWORK_ALERTS,
                context.getString(R.string.notification_channel_network_alerts),
                NotificationManager.IMPORTANCE_HIGH);
        networkAlertsChannel.setBlockableSystem(true);
        channelsList.add(networkAlertsChannel);

        final NotificationChannel networkAvailable = new NotificationChannel(
                NETWORK_AVAILABLE,
                context.getString(R.string.notification_channel_network_available),
                NotificationManager.IMPORTANCE_LOW);
        networkAvailable.setBlockableSystem(true);
        channelsList.add(networkAvailable);

        final NotificationChannel vpn = new NotificationChannel(
                VPN,
                context.getString(R.string.notification_channel_vpn),
                NotificationManager.IMPORTANCE_LOW);
        channelsList.add(vpn);

        final NotificationChannel deviceAdmin = new NotificationChannel(
                DEVICE_ADMIN,
                context.getString(R.string.notification_channel_device_admin),
                NotificationManager.IMPORTANCE_LOW);
        channelsList.add(deviceAdmin);

        final NotificationChannel alertsChannel = new NotificationChannel(
                ALERTS,
                context.getString(R.string.notification_channel_alerts),
                NotificationManager.IMPORTANCE_DEFAULT);
        channelsList.add(alertsChannel);

        final NotificationChannel retail = new NotificationChannel(
                RETAIL_MODE,
                context.getString(R.string.notification_channel_retail_mode),
                NotificationManager.IMPORTANCE_LOW);
        channelsList.add(retail);

        final NotificationChannel usb = new NotificationChannel(
                USB,
                context.getString(R.string.notification_channel_usb),
                NotificationManager.IMPORTANCE_MIN);
        channelsList.add(usb);

        NotificationChannel foregroundChannel = new NotificationChannel(
                FOREGROUND_SERVICE,
                context.getString(R.string.notification_channel_foreground_service),
                NotificationManager.IMPORTANCE_LOW);
        foregroundChannel.setBlockableSystem(true);
        channelsList.add(foregroundChannel);

        NotificationChannel heavyWeightChannel = new NotificationChannel(
                HEAVY_WEIGHT_APP,
                context.getString(R.string.notification_channel_heavy_weight_app),
                NotificationManager.IMPORTANCE_DEFAULT);
        heavyWeightChannel.setShowBadge(false);
        heavyWeightChannel.setSound(null, new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .build());
        channelsList.add(heavyWeightChannel);

        NotificationChannel systemChanges = new NotificationChannel(SYSTEM_CHANGES,
                context.getString(R.string.notification_channel_system_changes),
                NotificationManager.IMPORTANCE_LOW);
        channelsList.add(systemChanges);

        NotificationChannel dndChanges = new NotificationChannel(DO_NOT_DISTURB,
                context.getString(R.string.notification_channel_do_not_disturb),
                NotificationManager.IMPORTANCE_LOW);
        channelsList.add(dndChanges);

        nm.createNotificationChannels(channelsList);
    }

    public static void createAccountChannelForPackage(String pkg, int uid, Context context) {
        final INotificationManager iNotificationManager = NotificationManager.getService();
        try {
            iNotificationManager.createNotificationChannelsForPackage(pkg, uid,
                    new ParceledListSlice(Arrays.asList(newAccountChannel(context))));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private static NotificationChannel newAccountChannel(Context context) {
        return new NotificationChannel(
                ACCOUNT,
                context.getString(R.string.notification_channel_account),
                NotificationManager.IMPORTANCE_LOW);
    }

    private SystemNotificationChannels() {}
}
