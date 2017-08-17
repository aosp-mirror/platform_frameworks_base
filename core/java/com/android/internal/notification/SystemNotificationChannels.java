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

    public static void createAll(Context context) {
        final NotificationManager nm = context.getSystemService(NotificationManager.class);
        List<NotificationChannel> channelsList = new ArrayList<NotificationChannel>();
        channelsList.add(new NotificationChannel(
                VIRTUAL_KEYBOARD,
                context.getString(R.string.notification_channel_virtual_keyboard),
                NotificationManager.IMPORTANCE_LOW));

        final NotificationChannel physicalKeyboardChannel = new NotificationChannel(
                PHYSICAL_KEYBOARD,
                context.getString(R.string.notification_channel_physical_keyboard),
                NotificationManager.IMPORTANCE_DEFAULT);
        physicalKeyboardChannel.setSound(Settings.System.DEFAULT_NOTIFICATION_URI,
                Notification.AUDIO_ATTRIBUTES_DEFAULT);
        channelsList.add(physicalKeyboardChannel);

        channelsList.add(new NotificationChannel(
                SECURITY,
                context.getString(R.string.notification_channel_security),
                NotificationManager.IMPORTANCE_LOW));

        channelsList.add(new NotificationChannel(
                CAR_MODE,
                context.getString(R.string.notification_channel_car_mode),
                NotificationManager.IMPORTANCE_LOW));

        channelsList.add(newAccountChannel(context));

        channelsList.add(new NotificationChannel(
                DEVELOPER,
                context.getString(R.string.notification_channel_developer),
                NotificationManager.IMPORTANCE_LOW));

        channelsList.add(new NotificationChannel(
                UPDATES,
                context.getString(R.string.notification_channel_updates),
                NotificationManager.IMPORTANCE_LOW));

        channelsList.add(new NotificationChannel(
                NETWORK_STATUS,
                context.getString(R.string.notification_channel_network_status),
                NotificationManager.IMPORTANCE_LOW));

        final NotificationChannel networkAlertsChannel = new NotificationChannel(
                NETWORK_ALERTS,
                context.getString(R.string.notification_channel_network_alerts),
                NotificationManager.IMPORTANCE_HIGH);
        networkAlertsChannel.setSound(Settings.System.DEFAULT_NOTIFICATION_URI,
                Notification.AUDIO_ATTRIBUTES_DEFAULT);
        channelsList.add(networkAlertsChannel);

        channelsList.add(new NotificationChannel(
                NETWORK_AVAILABLE,
                context.getString(R.string.notification_channel_network_available),
                NotificationManager.IMPORTANCE_LOW));

        channelsList.add(new NotificationChannel(
                VPN,
                context.getString(R.string.notification_channel_vpn),
                NotificationManager.IMPORTANCE_LOW));

        channelsList.add(new NotificationChannel(
                DEVICE_ADMIN,
                context.getString(R.string.notification_channel_device_admin),
                NotificationManager.IMPORTANCE_LOW));

        final NotificationChannel alertsChannel = new NotificationChannel(
                ALERTS,
                context.getString(R.string.notification_channel_alerts),
                NotificationManager.IMPORTANCE_DEFAULT);
        alertsChannel.setSound(Settings.System.DEFAULT_NOTIFICATION_URI,
                Notification.AUDIO_ATTRIBUTES_DEFAULT);
        channelsList.add(alertsChannel);

        channelsList.add(new NotificationChannel(
                RETAIL_MODE,
                context.getString(R.string.notification_channel_retail_mode),
                NotificationManager.IMPORTANCE_LOW));

        channelsList.add(new NotificationChannel(
                USB,
                context.getString(R.string.notification_channel_usb),
                NotificationManager.IMPORTANCE_MIN));

        NotificationChannel foregroundChannel = new NotificationChannel(
                FOREGROUND_SERVICE,
                context.getString(R.string.notification_channel_foreground_service),
                NotificationManager.IMPORTANCE_LOW);
        foregroundChannel.setBlockableSystem(true);
        channelsList.add(foregroundChannel);

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
