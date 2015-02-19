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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;

/**
 * The receiver for the alarm we set
 *
 */
public class WakeUpCall extends BroadcastReceiver {
    private static final String LOG_TAG = WakeUpCall.class.getSimpleName();
    static final String WAKEUP_CALL = "android.test.wakeuploop.WAKEUP";
    static final String CANCEL = "CANCEL";

    @Override
    public void onReceive(Context context, Intent intent) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        boolean cancel = intent.hasExtra(CANCEL);
        if (!cancel) {
            long maxLoop = intent.getLongExtra(WakeLoopService.MAX_LOOP, 0);
            long wakeupInterval = intent.getLongExtra(WakeLoopService.WAKEUP_INTERNAL, 0);
            long thisLoop = intent.getLongExtra(WakeLoopService.THIS_LOOP, -1);
            Log.d(LOG_TAG, String.format("incoming: interval = %d, max loop = %d, this loop = %d",
                    wakeupInterval, maxLoop, thisLoop));
            if (thisLoop == -1) {
                Log.e(LOG_TAG, "no valid loop count received, trying to stop service");
                stopService(intent);
                return;
            }
            if (wakeupInterval == 0) {
                Log.e(LOG_TAG, "no valid wakeup interval received, trying to stop service");
                stopService(intent);
                return;
            }
            thisLoop++;
            Log.d(LOG_TAG, String.format("WakeLoop - iteration %d of %d", thisLoop, maxLoop));
            if (thisLoop == maxLoop) {
                // when maxLoop is 0, we loop forever, so not checking that case
                // here
                Log.d(LOG_TAG, "reached max loop count, stopping service");
                stopService(intent);
                return;
            }
            screenOn(context);
            FileUtil.get().writeDateToFile(
                    new File(Environment.getExternalStorageDirectory(), "wakeup-loop.txt"));
            // calculate when device should be waken up
            long atTime = SystemClock.elapsedRealtime() + wakeupInterval;
            intent.putExtra(WakeLoopService.THIS_LOOP, thisLoop);
            PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            // set alarm, which will be delivered in form of the wakeupIntent
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, atTime, pi);
        } else {
            // cancel alarms
            Log.d(LOG_TAG, "cancelling future alarms on request");
            am.cancel(PendingIntent.getBroadcast(context, 0, intent, 0));
        }
    }

    private void stopService(Intent i) {
        Messenger msgr = i.getParcelableExtra(WakeLoopService.STOP_CALLBACK);
        if (msgr == null) {
            Log.e(LOG_TAG, "no stop service callback found, cannot stop");
        } else {
            Message msg = new Message();
            msg.what = WakeLoopService.MSG_STOP_SERVICE;
            try {
                msgr.send(msg);
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "ignored remoted exception while attempting to stop service", e);
            }
        }
    }

    private void screenOn(Context context) {
        PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        @SuppressWarnings("deprecation")
        WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK |
                PowerManager.ACQUIRE_CAUSES_WAKEUP, LOG_TAG);
        wl.acquire(500);
    }
}
