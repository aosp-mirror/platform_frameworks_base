/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.frameworkperf;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class SchedulerService extends Service {
    private static final String NOTIFICATION_CHANNEL_ID = SchedulerService.class.getSimpleName();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(NOTIFICATION_CHANNEL_ID, NOTIFICATION_CHANNEL_ID,
                        NotificationManager.IMPORTANCE_DEFAULT));
        Notification status = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.stat_happy)
                .setWhen(System.currentTimeMillis())
                .setContentTitle("Scheduler Test running")
                .setContentText("Scheduler Test running")
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, FrameworkPerfActivity.class)
                                .setAction(Intent.ACTION_MAIN)
                                .addCategory(Intent.CATEGORY_LAUNCHER)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_MUTABLE_UNAUDITED))
                .setOngoing(true)
                .build();
        startForeground(1, status);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO Auto-generated method stub
        return null;
    }

}
