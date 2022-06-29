/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.servicestests.apps.simpleservicetestapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.R;

public class SimpleFgService extends Service {
    private static final String TAG = SimpleFgService.class.getSimpleName();
    private static final String NOTIFICATION_CHANNEL_ID = TAG;
    private static final int NOTIFICATION_ID = 1;

    private static final int MSG_INIT = 0;
    private static final int MSG_DONE = 1;
    private static final int MSG_START_FOREGROUND = 2;
    private static final int MSG_STOP_FOREGROUND = 3;
    private static final int MSG_STOP_SERVICE = 4;

    private static final String ACTION_FGS_STATS_TEST =
            "com.android.servicestests.apps.simpleservicetestapp.ACTION_FGS_STATS_TEST";
    private static final String EXTRA_MESSENGER = "extra_messenger";

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_START_FOREGROUND: {
                    Log.i(TAG, "startForeground");
                    startForeground(NOTIFICATION_ID, mNotification);
                    sendRemoteMessage(MSG_DONE, 0, 0, null);
                } break;
                case MSG_STOP_FOREGROUND: {
                    Log.i(TAG, "stopForeground");
                    stopForeground(true);
                    sendRemoteMessage(MSG_DONE, 0, 0, null);
                } break;
                case MSG_STOP_SERVICE: {
                    Log.i(TAG, "stopSelf");
                    stopSelf();
                    sendRemoteMessage(MSG_DONE, 0, 0, null);
                } break;
            }
        }
    };
    private final Messenger mMessenger = new Messenger(mHandler);

    private Notification mNotification;
    private Messenger mRemoteMessenger;

    @Override
    public void onCreate() {
        Log.i(TAG, "onCreate");
        final NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_ID, NotificationManager.IMPORTANCE_LOW));
        mNotification = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(TAG)
                .setSmallIcon(R.drawable.ic_info)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand");
        startForeground(NOTIFICATION_ID, mNotification);
        if (ACTION_FGS_STATS_TEST.equals(intent.getAction())) {
            mRemoteMessenger = new Messenger(intent.getExtras().getBinder(EXTRA_MESSENGER));
            sendRemoteMessage(MSG_INIT, 0, 0, mMessenger);
        }
        return START_NOT_STICKY;
    }

    private void sendRemoteMessage(int what, int arg1, int arg2, Object obj) {
        final Message msg = Message.obtain();
        msg.what = what;
        msg.arg1 = arg1;
        msg.arg2 = arg2;
        msg.obj = obj;
        try {
            mRemoteMessenger.send(msg);
        } catch (RemoteException e) {
        }
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy");
        mNotification = null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
