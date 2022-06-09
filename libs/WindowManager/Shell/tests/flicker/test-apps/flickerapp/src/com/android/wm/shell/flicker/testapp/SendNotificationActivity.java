/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.flicker.testapp;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

public class SendNotificationActivity extends Activity {
    private NotificationManager mNotificationManager;
    private String mChannelId = "Channel id";
    private String mChannelName = "Channel name";
    private NotificationChannel mChannel;
    private int mNotifyId = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notification);
        findViewById(R.id.button_send_notification).setOnClickListener(this::sendNotification);

        mChannel = new NotificationChannel(mChannelId, mChannelName,
                NotificationManager.IMPORTANCE_DEFAULT);
        mNotificationManager = getSystemService(NotificationManager.class);
        mNotificationManager.createNotificationChannel(mChannel);
    }

    private void sendNotification(View v) {
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, SendNotificationActivity.class),
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, mChannelId)
                .setContentTitle("Notification App")
                .setContentText("Notification content")
                .setWhen(System.currentTimeMillis())
                .setSmallIcon(R.drawable.ic_message)
                .setContentIntent(pendingIntent)
                .build();

        mNotificationManager.notify(mNotifyId, notification);
    }
}
