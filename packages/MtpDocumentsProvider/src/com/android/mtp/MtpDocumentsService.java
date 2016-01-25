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
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.os.IBinder;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.io.IOException;

/**
 * Service to manage lifetime of DocumentsProvider's process.
 * The service prevents the system from killing the process that holds USB connections. The service
 * starts to run when the first MTP device is opened, and stops when the last MTP device is closed.
 */
public class MtpDocumentsService extends Service {
    static final String ACTION_OPEN_DEVICE = "com.android.mtp.OPEN_DEVICE";
    static final String ACTION_CLOSE_DEVICE = "com.android.mtp.CLOSE_DEVICE";
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
        if (intent != null) {
            final MtpDocumentsProvider provider = MtpDocumentsProvider.getInstance();
            final UsbDevice device = intent.<UsbDevice>getParcelableExtra(EXTRA_DEVICE);
            try {
                Preconditions.checkNotNull(device);
                switch (intent.getAction()) {
                    case ACTION_OPEN_DEVICE:
                        provider.openDevice(device.getDeviceId());
                        break;

                    case ACTION_CLOSE_DEVICE:
                        mNotificationManager.cancel(device.getDeviceId());
                        provider.closeDevice(device.getDeviceId());
                        break;

                    default:
                        throw new IllegalArgumentException("Received unknown intent action.");
                }
            } catch (IOException | InterruptedException | IllegalArgumentException error) {
                logErrorMessage(error);
            }
        } else {
            // TODO: Fetch devices again.
        }

        return updateForegroundState() ? START_STICKY : START_NOT_STICKY;
    }

    /**
     * Updates the foreground state of the service.
     * @return Whether the service is foreground or not.
     */
    private boolean updateForegroundState() {
        final MtpDocumentsProvider provider = MtpDocumentsProvider.getInstance();
        final int[] deviceIds = provider.getOpenedDeviceIds();
        int notificationId = 0;
        Notification notification = null;
        for (final int deviceId : deviceIds) {
            try {
                final String title = getResources().getString(
                        R.string.accessing_notification_title,
                        provider.getDeviceName(deviceIds[0]));
                final String description = getResources().getString(
                        R.string.accessing_notification_description);
                notificationId = deviceId;
                notification = new Notification.Builder(this)
                        .setLocalOnly(true)
                        .setContentTitle(title)
                        .setContentText(description)
                        .setSmallIcon(com.android.internal.R.drawable.stat_sys_data_usb)
                        .setCategory(Notification.CATEGORY_SYSTEM)
                        .setPriority(Notification.PRIORITY_LOW)
                        .build();
                mNotificationManager.notify(deviceId, notification);
            } catch (IOException exp) {
                logErrorMessage(exp);
                // If we failed to obtain device name, it looks the device is unusable.
                // Because this is the last device we opened, we should hide the notification
                // for the case.
                try {
                    provider.closeDevice(deviceIds[0]);
                } catch (IOException | InterruptedException closeError) {
                    logErrorMessage(closeError);
                }
            }
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
