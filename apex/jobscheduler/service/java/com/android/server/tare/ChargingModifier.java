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
import android.os.BatteryManager;
import android.os.SystemClock;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;

/** Modifier that makes things free when the device is charging. */
class ChargingModifier extends Modifier {
    private static final String TAG = "TARE-" + ChargingModifier.class.getSimpleName();
    private static final boolean DEBUG = InternalResourceService.DEBUG
            || Log.isLoggable(TAG, Log.DEBUG);

    private final InternalResourceService mIrs;
    private final ChargingTracker mChargingTracker;

    ChargingModifier(@NonNull InternalResourceService irs) {
        super();
        mIrs = irs;
        mChargingTracker = new ChargingTracker();
        mChargingTracker.startTracking(irs.getContext());
    }

    @Override
    long getModifiedCostToProduce(long ctp) {
        return modifyValue(ctp);
    }

    @Override
    long getModifiedPrice(long price) {
        return modifyValue(price);
    }

    private long modifyValue(long val) {
        if (mChargingTracker.mCharging) {
            return 0;
        }
        return val;
    }

    @Override
    void dump(IndentingPrintWriter pw) {
        pw.print("charging=");
        pw.println(mChargingTracker.mCharging);
    }

    private final class ChargingTracker extends BroadcastReceiver {
        /**
         * Track whether we're "charging", where charging means that we're ready to commit to
         * doing work.
         */
        private volatile boolean mCharging;

        public void startTracking(@NonNull Context context) {
            final IntentFilter filter = new IntentFilter();
            filter.addAction(BatteryManager.ACTION_CHARGING);
            filter.addAction(BatteryManager.ACTION_DISCHARGING);
            context.registerReceiver(this, filter);

            // Initialise tracker state.
            final BatteryManager batteryManager = context.getSystemService(BatteryManager.class);
            mCharging = batteryManager.isCharging();
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BatteryManager.ACTION_CHARGING.equals(action)) {
                if (DEBUG) {
                    Slog.d(TAG, "Received charging intent, fired @ "
                            + SystemClock.elapsedRealtime());
                }
                if (!mCharging) {
                    mCharging = true;
                    mIrs.onDeviceStateChanged();
                }
            } else if (BatteryManager.ACTION_DISCHARGING.equals(action)) {
                if (DEBUG) {
                    Slog.d(TAG, "Disconnected from power.");
                }
                if (mCharging) {
                    mCharging = false;
                    mIrs.onDeviceStateChanged();
                }
            }
        }
    }
}
