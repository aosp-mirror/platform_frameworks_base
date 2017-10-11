/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.mtp;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.app.Service;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.IBinder;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import com.android.internal.util.Preconditions;
import java.util.HashSet;
import java.util.Set;

/**
 * Service to manage lifetime of DocumentsProvider's process.
 * The service prevents the system from killing the process that holds USB connections. The service
 * starts to run when the first MTP device is opened, and stops when the last MTP device is closed.
 */
public class MtpDocumentsService extends Service {
    static final String ACTION_UPDATE_NOTIFICATION = "com.android.mtp.UPDATE_NOTIFICATION";
    static final String EXTRA_DEVICE_IDS = "deviceIds";
    static final String EXTRA_DEVICE_NOTIFICATIONS = "deviceNotifications";

    private NotificationManager mNotificationManager;

    @Override
    public IBinder onBind(Intent intent) {
        // The service is used via intents.
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mNotificationManager = getSystemService(NotificationManager.class);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // If intent is null, the service was restarted.
        if (intent == null || ACTION_UPDATE_NOTIFICATION.equals(intent.getAction())) {
            final int[] ids = intent.hasExtra(EXTRA_DEVICE_IDS) ?
                    intent.getExtras().getIntArray(EXTRA_DEVICE_IDS) : null;
            final Notification[] notifications = intent.hasExtra(EXTRA_DEVICE_NOTIFICATIONS) ?
                    castToNotifications(intent.getExtras().getParcelableArray(
                            EXTRA_DEVICE_NOTIFICATIONS)) : null;
            return updateForegroundState(ids, notifications) ? START_STICKY : START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    /**
     * Updates the foreground state of the service.
     * @return Whether the service is foreground or not.
     */
    private boolean updateForegroundState(
            @Nullable int[] ids, @Nullable Notification[] notifications) {
        final Set<Integer> openedNotification = new HashSet<>();
        final int size = ids != null ? ids.length : 0;
        if (size != 0) {
            Preconditions.checkArgument(ids != null);
            Preconditions.checkArgument(notifications != null);
            Preconditions.checkArgument(ids.length == notifications.length);
        }

        for (int i = 0; i < size; i++) {
            if (i == 0) {
                // Mark this service as foreground with the notification so that the process is
                // not killed by the system while a MTP device is opened.
                startForeground(ids[i], notifications[i]);
            } else {
                // Only one notification can be shown as a foreground notification. We need to
                // show the rest as normal notification.
                mNotificationManager.notify(ids[i], notifications[i]);
            }
            openedNotification.add(ids[i]);
        }

        final StatusBarNotification[] activeNotifications =
                mNotificationManager.getActiveNotifications();
        for (final StatusBarNotification notification : activeNotifications) {
            if (!openedNotification.contains(notification.getId())) {
                mNotificationManager.cancel(notification.getId());
            }
        }

        if (size == 0) {
            // There is no opened device.
            stopForeground(true /* removeNotification */);
            stopSelf();
            return false;
        }

        return true;
    }

    private static @NonNull Notification[] castToNotifications(@NonNull Parcelable[] src) {
        Preconditions.checkNotNull(src);
        final Notification[] notifications = new Notification[src.length];
        for (int i = 0; i < src.length; i++) {
            notifications[i] = (Notification) src[i];
        }
        return notifications;
    }
}
