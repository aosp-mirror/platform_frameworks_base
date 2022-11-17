/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.settingslib.bluetooth;

/* Required to handle timeout notification when phone is suspended */
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;


public class BluetoothDiscoverableTimeoutReceiver extends BroadcastReceiver {
    private static final String TAG = "BluetoothDiscoverableTimeoutReceiver";

    private static final String INTENT_DISCOVERABLE_TIMEOUT =
        "android.bluetooth.intent.DISCOVERABLE_TIMEOUT";

    public static void setDiscoverableAlarm(Context context, long alarmTime) {
        Log.d(TAG, "setDiscoverableAlarm(): alarmTime = " + alarmTime);

        Intent intent = new Intent(INTENT_DISCOVERABLE_TIMEOUT);
        intent.setClass(context, BluetoothDiscoverableTimeoutReceiver.class);
        PendingIntent pending = PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarmManager =
              (AlarmManager) context.getSystemService (Context.ALARM_SERVICE);

        if (pending != null) {
            // Cancel any previous alarms that do the same thing.
            alarmManager.cancel(pending);
            Log.d(TAG, "setDiscoverableAlarm(): cancel prev alarm");
        }
        pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        alarmManager.set(AlarmManager.RTC_WAKEUP, alarmTime, pending);
    }

    public static void cancelDiscoverableAlarm(Context context) {
        Log.d(TAG, "cancelDiscoverableAlarm(): Enter");

        Intent intent = new Intent(INTENT_DISCOVERABLE_TIMEOUT);
        intent.setClass(context, BluetoothDiscoverableTimeoutReceiver.class);
        PendingIntent pending = PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pending != null) {
            // Cancel any previous alarms that do the same thing.
            AlarmManager alarmManager =
              (AlarmManager) context.getSystemService (Context.ALARM_SERVICE);

            alarmManager.cancel(pending);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction() == null || !intent.getAction().equals(INTENT_DISCOVERABLE_TIMEOUT)) {
            return;
        }
        LocalBluetoothAdapter localBluetoothAdapter = LocalBluetoothAdapter.getInstance();
        if(localBluetoothAdapter != null  &&
            localBluetoothAdapter.getState() == BluetoothAdapter.STATE_ON) {
            Log.d(TAG, "Disable discoverable...");
            localBluetoothAdapter.setScanMode(BluetoothAdapter.SCAN_MODE_CONNECTABLE);
        } else {
            Log.e(TAG, "localBluetoothAdapter is NULL!!");
        }
    }
}
