/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.powerstats;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.util.Slog;

/**
 * BatteryTrigger instantiates a BroadcastReceiver that listens for changes
 * to the battery.  When the battery level drops by 1% a message is sent to
 * the PowerStatsLogger to log the rail energy data to on-device storage.
 */
public final class BatteryTrigger extends PowerStatsLogTrigger {
    private static final String TAG = BatteryTrigger.class.getSimpleName();
    private static final boolean DEBUG = false;

    private int mBatteryLevel = 0;

    private final BroadcastReceiver mBatteryLevelReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case Intent.ACTION_BATTERY_CHANGED:
                    int newBatteryLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);

                    if (newBatteryLevel < mBatteryLevel) {
                        if (DEBUG) Slog.d(TAG, "Battery level dropped.  Log rail data");
                        logPowerStatsData(PowerStatsLogger.MSG_LOG_TO_DATA_STORAGE_BATTERY_DROP);
                    }

                    mBatteryLevel = newBatteryLevel;
                    break;
            }
        }
    };

    public BatteryTrigger(Context context, PowerStatsLogger powerStatsLogger,
            boolean triggerEnabled) {
        super(context, powerStatsLogger);

        if (triggerEnabled) {
            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = mContext.registerReceiver(mBatteryLevelReceiver, filter);
            if (batteryStatus != null) {
                mBatteryLevel = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
            }
        }
    }
}
