/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.test;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

public class LocalMediaProjectionService extends Service {

    private Bitmap mTestBitmap;

    private static final String NOTIFICATION_CHANNEL_ID = "Surfacevalidator";
    private static final String CHANNEL_NAME = "ProjectionService";

    static final int MSG_START_FOREGROUND_DONE = 1;
    static final String EXTRA_MESSENGER = "messenger";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(intent);
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (mTestBitmap != null) {
            mTestBitmap.recycle();
            mTestBitmap = null;
        }
        super.onDestroy();
    }

    private Icon createNotificationIcon() {
        mTestBitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(mTestBitmap);
        canvas.drawColor(Color.BLUE);
        return Icon.createWithBitmap(mTestBitmap);
    }

    private void startForeground(Intent intent) {
        final NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                CHANNEL_NAME, NotificationManager.IMPORTANCE_NONE);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);

        final NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.createNotificationChannel(channel);

        final Notification.Builder notificationBuilder =
                new Notification.Builder(this, NOTIFICATION_CHANNEL_ID);

        final Notification notification = notificationBuilder.setOngoing(true)
                .setContentTitle("App is running")
                .setSmallIcon(createNotificationIcon())
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentText("Context")
                .build();

        startForeground(2, notification);

        final Messenger messenger = intent.getParcelableExtra(EXTRA_MESSENGER);
        final Message msg = Message.obtain();
        msg.what = MSG_START_FOREGROUND_DONE;
        try {
            messenger.send(msg);
        } catch (RemoteException e) {
        }
    }

}
