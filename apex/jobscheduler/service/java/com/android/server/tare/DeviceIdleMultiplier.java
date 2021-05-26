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

package com.android.server.tare;

import android.annotation.NonNull;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;
import android.util.IndentingPrintWriter;
import android.util.Log;

/** Multiplier that makes things more expensive in light and deep doze. */
class DeviceIdleMultiplier extends Multiplier {
    private static final String TAG = "TARE-" + DeviceIdleMultiplier.class.getSimpleName();
    private static final boolean DEBUG = InternalResourceService.DEBUG
            || Log.isLoggable(TAG, Log.DEBUG);

    private final InternalResourceService mIrs;
    private final PowerManager mPowerManager;
    private final DeviceIdleTracker mDeviceIdleTracker;

    DeviceIdleMultiplier(@NonNull InternalResourceService irs) {
        super(true, false);
        mIrs = irs;
        mPowerManager = irs.getContext().getSystemService(PowerManager.class);
        mDeviceIdleTracker = new DeviceIdleTracker();
        mDeviceIdleTracker.startTracking(irs.getContext());
    }

    double getCurrentMultiplier() {
        if (mDeviceIdleTracker.mDeviceIdle) {
            return 1.2;
        }
        if (mDeviceIdleTracker.mDeviceLightIdle) {
            return 1.1;
        }
        return 1;
    }

    @Override
    void dump(IndentingPrintWriter pw) {
        pw.print("idle=");
        pw.println(mDeviceIdleTracker.mDeviceIdle);
        pw.print("lightIdle=");
        pw.println(mDeviceIdleTracker.mDeviceLightIdle);
    }

    private final class DeviceIdleTracker extends BroadcastReceiver {

        private volatile boolean mDeviceIdle;
        private volatile boolean mDeviceLightIdle;

        DeviceIdleTracker() {
        }

        void startTracking(@NonNull Context context) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
            filter.addAction(PowerManager.ACTION_LIGHT_DEVICE_IDLE_MODE_CHANGED);
            context.registerReceiver(this, filter);

            // Initialise tracker state.
            mDeviceIdle = mPowerManager.isDeviceIdleMode();
            mDeviceLightIdle = mPowerManager.isLightDeviceIdleMode();
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED.equals(action)) {
                if (mDeviceIdle != mPowerManager.isDeviceIdleMode()) {
                    mDeviceIdle = mPowerManager.isDeviceIdleMode();
                    mIrs.onDeviceStateChanged();
                }
            } else if (PowerManager.ACTION_LIGHT_DEVICE_IDLE_MODE_CHANGED.equals(action)) {
                if (mDeviceIdle != mPowerManager.isLightDeviceIdleMode()) {
                    mDeviceLightIdle = mPowerManager.isLightDeviceIdleMode();
                    mIrs.onDeviceStateChanged();
                }
            }
        }
    }
}
