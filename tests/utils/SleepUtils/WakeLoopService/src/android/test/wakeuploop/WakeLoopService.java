/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.test.wakeuploop;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;

public class WakeLoopService extends Service {

    private static final String LOG_TAG = WakeLoopService.class.getSimpleName();
    static final String WAKEUP_INTERNAL = "WAKEUP_INTERVAL";
    static final String MAX_LOOP = "MAX_LOOP";
    static final String STOP_CALLBACK = "STOP_CALLBACK";
    static final String THIS_LOOP = "THIS_LOOP";
    static final int MSG_STOP_SERVICE = 0xd1ed1e;

    private final Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            if (msg.what == MSG_STOP_SERVICE) {
                stopSelf();
            } else {
                super.handleMessage(msg);
            }
        };
    };

    @Override
    public IBinder onBind(Intent intent) {
        // no binding, just start via intent
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // get wakeup interval from intent
        long wakeupInterval = intent.getLongExtra(WAKEUP_INTERNAL, 0);
        long maxLoop = intent.getLongExtra(MAX_LOOP, 0);

        if (wakeupInterval == 0) {
            // stop and error
            Log.e(LOG_TAG, "No wakeup interval specified, not starting the service");
            stopSelf();
            return START_NOT_STICKY;
        }
        FileUtil.get().writeDateToFile(new File(Environment.getExternalStorageDirectory(),
                "wakeup-loop-start.txt"));
        Log.d(LOG_TAG, String.format("WakeLoop: STARTED interval = %d, total loop = %d",
                wakeupInterval, maxLoop));
        // calculate when device should be waken up
        long atTime = SystemClock.elapsedRealtime() + wakeupInterval;
        AlarmManager am = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
        Intent wakupIntent = new Intent(WakeUpCall.WAKEUP_CALL)
            .putExtra(WAKEUP_INTERNAL, wakeupInterval)
            .putExtra(MAX_LOOP, maxLoop)
            .putExtra(THIS_LOOP, 0L)
            .putExtra(STOP_CALLBACK, new Messenger(mHandler));
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, wakupIntent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        // set alarm, which will be delivered in form of the wakeupIntent
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, atTime, pi);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(LOG_TAG, "WakeLoop: STOPPED");
        // cancel alarms first
        Intent intent = new Intent(WakeUpCall.WAKEUP_CALL)
            .putExtra(WakeUpCall.CANCEL, "true");
        sendBroadcast(intent);
    }
}
