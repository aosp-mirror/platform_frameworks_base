/*
 * Copyright (C) 2020 The Calyx Institute
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

package com.android.settingslib.wifi;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.util.Date;

public class WifiTimeoutReceiver extends BroadcastReceiver {
    private static final String TAG = "WifiTimeoutReceiver";

    private static final String INTENT_TIMEOUT = "android.net.wifi.intent.TIMEOUT";

    public static void setTimeoutAlarm(Context context, long alarmTime) {
        Intent intent = new Intent(INTENT_TIMEOUT);
        intent.setClass(context, WifiTimeoutReceiver.class);
        PendingIntent pending = PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_MUTABLE);
        AlarmManager alarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

        if (alarmTime != 0) {
            alarmTime = System.currentTimeMillis() + alarmTime;
            Log.d(TAG, "setTimeoutAlarm(): alarmTime = " + new Date(alarmTime));
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarmTime, pending);
        } else
            alarmManager.cancel(pending);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction() == null && !intent.getAction().equals(INTENT_TIMEOUT)) {
            return;
        }
        WifiManager wifiManager = context.getSystemService(WifiManager.class);
        if (wifiManager != null) {
            if (wifiManager.isWifiEnabled() && wifiManager.getCurrentNetwork() == null)
                wifiManager.setWifiEnabled(false);
        } else {
            Log.e(TAG, "wifiManager is NULL!!");
        }
    }
}
