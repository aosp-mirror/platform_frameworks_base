/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.testing.alarmservice;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import com.android.testing.alarmservice.Alarm.Stub;

public class AlarmImpl extends Stub {

    private static final String LOG_TAG = AlarmImpl.class.getSimpleName();

    private Context mContext;

    public AlarmImpl(Context context) {
        super();
        mContext = context;
    }

    @Override
    public int prepare() throws RemoteException {
        WakeUpController.getController().getWakeLock().acquire();
        Log.d(LOG_TAG, "AlarmService prepared, wake lock acquired");
        return 0;
    }

    @Override
    public int setAlarmAndWait(long timeoutMills) throws RemoteException {
        // calculate when device should be waken up
        long atTime = SystemClock.elapsedRealtime() + timeoutMills;
        AlarmManager am = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        Intent wakupIntent = new Intent(WakeUpCall.WAKEUP_CALL);
        PendingIntent pi = PendingIntent.getBroadcast(mContext, 0, wakupIntent, 0);
        // set alarm, which will be delivered in form of the wakeupIntent
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, atTime, pi);
        Log.d(LOG_TAG, String.format("Alarm set: %d, giving up wake lock", atTime));
        Object lock = WakeUpController.getController().getWakeSync();
        // release wakelock and wait for the lock to be poked from the broadcast receiver
        WakeUpController.getController().getWakeLock().release();
        // does not really matter if device enters suspend before we start waiting on lock
        synchronized (lock) {
            try {
                lock.wait();
            } catch (InterruptedException e) {
            }
        }
        Log.d(LOG_TAG, String.format("Alarm triggered, done waiting"));
        return 0;
    }

    @Override
    public int done() throws RemoteException {
        WakeUpController.getController().getWakeLock().release();
        return 0;
    }

}
