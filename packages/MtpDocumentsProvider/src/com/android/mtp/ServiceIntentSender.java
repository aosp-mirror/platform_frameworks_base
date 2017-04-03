/*
 * Copyright (C) 2016 The Android Open Source Project
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
import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import com.android.internal.util.Preconditions;

/**
 * Sends intent to MtpDocumentsService.
 */
class ServiceIntentSender {
    private Context mContext;

    ServiceIntentSender(Context context) {
        mContext = context;
    }

    /**
     * Notify the change of opened device set.
     * @param records List of opened devices. Can be empty.
     */
    void sendUpdateNotificationIntent(@NonNull MtpDeviceRecord[] records) {
        Preconditions.checkNotNull(records);
        final Intent intent = new Intent(MtpDocumentsService.ACTION_UPDATE_NOTIFICATION);
        intent.setComponent(new ComponentName(mContext, MtpDocumentsService.class));
        if (records.length != 0) {
            final int[] ids = new int[records.length];
            final Notification[] notifications = new Notification[records.length];
            for (int i = 0; i < records.length; i++) {
                ids[i] = records[i].deviceId;
                notifications[i] = createNotification(mContext, records[i]);
            }
            intent.putExtra(MtpDocumentsService.EXTRA_DEVICE_IDS, ids);
            intent.putExtra(MtpDocumentsService.EXTRA_DEVICE_NOTIFICATIONS, notifications);
            mContext.startForegroundService(intent);
        } else {
            mContext.startService(intent);
        }
    }

    private static Notification createNotification(Context context, MtpDeviceRecord device) {
        final String title = context.getResources().getString(
                R.string.accessing_notification_title,
                device.name);
        return new Notification.Builder(context)
                .setLocalOnly(true)
                .setContentTitle(title)
                .setSmallIcon(com.android.internal.R.drawable.stat_sys_data_usb)
                .setCategory(Notification.CATEGORY_SYSTEM)
                .setPriority(Notification.PRIORITY_LOW)
                .setFlag(Notification.FLAG_NO_CLEAR, true)
                .build();
    }
}
