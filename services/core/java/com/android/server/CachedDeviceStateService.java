/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.BatteryManagerInternal;
import android.os.OsProtoEnums;
import android.os.PowerManager;
import android.util.Slog;

import com.android.internal.os.CachedDeviceState;

/**
 * Tracks changes to the device state (e.g. charging/on battery, screen on/off) to share it with
 * the System Server telemetry services.
 *
 * @hide Only for use within the system server.
 */
public class CachedDeviceStateService extends SystemService {
    private static final String TAG = "CachedDeviceStateService";
    private final CachedDeviceState mDeviceState = new CachedDeviceState();
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case Intent.ACTION_BATTERY_CHANGED:
                    mDeviceState.setCharging(
                            intent.getIntExtra(BatteryManager.EXTRA_PLUGGED,
                                    OsProtoEnums.BATTERY_PLUGGED_NONE)
                                    != OsProtoEnums.BATTERY_PLUGGED_NONE);
                    break;
                case Intent.ACTION_SCREEN_ON:
                    mDeviceState.setScreenInteractive(true);
                    break;
                case Intent.ACTION_SCREEN_OFF:
                    mDeviceState.setScreenInteractive(false);
                    break;
            }
        }
    };

    public CachedDeviceStateService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        publishLocalService(CachedDeviceState.Readonly.class, mDeviceState.getReadonlyClient());
    }

    @Override
    public void onBootPhase(int phase) {
        if (SystemService.PHASE_SYSTEM_SERVICES_READY == phase) {
            final IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_BATTERY_CHANGED);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
            getContext().registerReceiver(mBroadcastReceiver, filter);
            mDeviceState.setCharging(queryIsCharging());
            mDeviceState.setScreenInteractive(queryScreenInteractive(getContext()));
        }
    }

    private boolean queryIsCharging() {
        final BatteryManagerInternal batteryManager =
                LocalServices.getService(BatteryManagerInternal.class);
        if (batteryManager == null) {
            Slog.wtf(TAG, "BatteryManager null while starting CachedDeviceStateService");
            // Default to true to not collect any data.
            return true;
        } else {
            return batteryManager.getPlugType() != OsProtoEnums.BATTERY_PLUGGED_NONE;
        }
    }

    private boolean queryScreenInteractive(Context context) {
        final PowerManager powerManager = context.getSystemService(PowerManager.class);
        if (powerManager == null) {
            Slog.wtf(TAG, "PowerManager null while starting CachedDeviceStateService");
            return false;
        } else {
            return powerManager.isInteractive();
        }
    }
}
