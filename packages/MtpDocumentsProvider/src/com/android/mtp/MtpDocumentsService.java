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

import android.app.Notification;
import android.app.Service;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import java.io.IOException;

/**
 * Service to manage lifetime of DocumentsProvider's process.
 * The service prevents the system from killing the process that holds USB connections. The service
 * starts to run when the first MTP device is opened, and stops when the last MTP device is closed.
 */
public class MtpDocumentsService extends Service {
    static final String ACTION_OPEN_DEVICE = "com.android.mtp.OPEN_DEVICE";
    static final String ACTION_CLOSE_DEVICE = "com.android.mtp.CLOSE_DEVICE";
    static final String ACTION_UPDATE_NOTIFICATION = "com.android.mtp.UPDATE_NOTIFICATION";
    static final String EXTRA_DEVICE = "device";

    NotificationManager mNotificationManager;

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
            return updateForegroundState() ? START_STICKY : START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    /**
     * Updates the foreground state of the service.
     * @return Whether the service is foreground or not.
     */
    private boolean updateForegroundState() {
        final MtpDocumentsProvider provider = MtpDocumentsProvider.getInstance();
        int notificationId = 0;
        Notification notification = null;
        // TODO: Hide notification if the device has already been removed.
        for (final MtpDeviceRecord record : provider.getOpenedDeviceRecordsCache()) {
            final String title = getResources().getString(
                    R.string.accessing_notification_title,
                    record.name);
            notificationId = record.deviceId;
            notification = new Notification.Builder(this)
                    .setLocalOnly(true)
                    .setContentTitle(title)
                    .setSmallIcon(com.android.internal.R.drawable.stat_sys_data_usb)
                    .setCategory(Notification.CATEGORY_SYSTEM)
                    .setPriority(Notification.PRIORITY_LOW)
                    .build();
            mNotificationManager.notify(record.deviceId, notification);
        }

        if (notification != null) {
            startForeground(notificationId, notification);
            return true;
        } else {
            stopForeground(true /* removeNotification */);
            stopSelf();
            return false;
        }
    }

    private static void logErrorMessage(Exception exp) {
        if (exp.getMessage() != null) {
            Log.e(MtpDocumentsProvider.TAG, exp.getMessage());
        } else {
            Log.e(MtpDocumentsProvider.TAG, exp.toString());
        }
    }
}
