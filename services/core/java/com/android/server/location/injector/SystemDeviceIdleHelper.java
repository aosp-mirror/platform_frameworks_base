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

package com.android.server.location.injector;

import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;

import com.android.server.FgThread;

/**
 * Provides accessors and listeners for device stationary state.
 */
public class SystemDeviceIdleHelper extends DeviceIdleHelper {

    private final Context mContext;
    private final PowerManager mPowerManager;

    private @Nullable BroadcastReceiver mReceiver;

    public SystemDeviceIdleHelper(Context context) {
        mContext = context;
        mPowerManager = context.getSystemService(PowerManager.class);
    }

    @Override
    protected void registerInternal() {
        if (mReceiver == null) {
            mReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    notifyDeviceIdleChanged();
                }
            };

            mContext.registerReceiver(mReceiver,
                    new IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED), null,
                    FgThread.getHandler());
        }
    }

    @Override
    protected void unregisterInternal() {
        if (mReceiver != null) {
            mContext.unregisterReceiver(mReceiver);
        }
    }

    @Override
    public boolean isDeviceIdle() {
        return mPowerManager.isDeviceIdleMode();
    }
}
