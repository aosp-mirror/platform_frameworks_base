/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.settingslib;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncTask;
import android.os.BatteryManager;
import android.os.BatteryStats;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.format.Formatter;
import com.android.internal.os.BatteryStatsHelper;

public class BatteryInfo {

    public String mChargeLabelString;
    public int mBatteryLevel;
    public boolean mDischarging = true;
    public long remainingTimeUs = 0;

    public interface Callback {
        void onBatteryInfoLoaded(BatteryInfo info);
    }

    public static void getBatteryInfo(final Context context, final Callback callback) {
        new AsyncTask<Void, Void, BatteryStats>() {
            @Override
            protected BatteryStats doInBackground(Void... params) {
                BatteryStatsHelper statsHelper = new BatteryStatsHelper(context, true);
                statsHelper.create((Bundle) null);
                return statsHelper.getStats();
            }

            @Override
            protected void onPostExecute(BatteryStats batteryStats) {
                final long elapsedRealtimeUs = SystemClock.elapsedRealtime() * 1000;
                Intent batteryBroadcast = context.registerReceiver(null,
                        new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                BatteryInfo batteryInfo = BatteryInfo.getBatteryInfo(context,
                        batteryBroadcast, batteryStats, elapsedRealtimeUs);
                callback.onBatteryInfoLoaded(batteryInfo);
            }
        }.execute();
    }

    public static BatteryInfo getBatteryInfo(Context context, Intent batteryBroadcast,
            BatteryStats stats, long elapsedRealtimeUs) {
        BatteryInfo info = new BatteryInfo();
        info.mBatteryLevel = Utils.getBatteryLevel(batteryBroadcast);
        String batteryPercentString = Utils.formatPercentage(info.mBatteryLevel);
        if (batteryBroadcast.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == 0) {
            final long drainTime = stats.computeBatteryTimeRemaining(elapsedRealtimeUs);
            if (drainTime > 0) {
                info.remainingTimeUs = drainTime;
                String timeString = Formatter.formatShortElapsedTime(context,
                        drainTime / 1000);
                info.mChargeLabelString = context.getResources().getString(
                        R.string.power_discharging_duration, batteryPercentString, timeString);
            } else {
                info.mChargeLabelString = batteryPercentString;
            }
        } else {
            final long chargeTime = stats.computeChargeTimeRemaining(elapsedRealtimeUs);
            final String statusLabel = Utils.getBatteryStatus(
                    context.getResources(), batteryBroadcast);
            final int status = batteryBroadcast.getIntExtra(BatteryManager.EXTRA_STATUS,
                    BatteryManager.BATTERY_STATUS_UNKNOWN);
            if (chargeTime > 0 && status != BatteryManager.BATTERY_STATUS_FULL) {
                info.mDischarging = false;
                info.remainingTimeUs = chargeTime;
                String timeString = Formatter.formatShortElapsedTime(context,
                        chargeTime / 1000);
                int plugType = batteryBroadcast.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                int resId;
                if (plugType == BatteryManager.BATTERY_PLUGGED_AC) {
                    resId = R.string.power_charging_duration_ac;
                } else if (plugType == BatteryManager.BATTERY_PLUGGED_USB) {
                    resId = R.string.power_charging_duration_usb;
                } else if (plugType == BatteryManager.BATTERY_PLUGGED_WIRELESS) {
                    resId = R.string.power_charging_duration_wireless;
                } else {
                    resId = R.string.power_charging_duration;
                }
                info.mChargeLabelString = context.getResources().getString(
                        resId, batteryPercentString, timeString);
            } else {
                info.mChargeLabelString = context.getResources().getString(
                        R.string.power_charging, batteryPercentString, statusLabel);
            }
        }
        return info;
    }
}
