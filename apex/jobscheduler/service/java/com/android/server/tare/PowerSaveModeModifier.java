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
import android.os.SystemClock;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;

/** Modifier that makes things more expensive in adaptive and full battery saver are active. */
class PowerSaveModeModifier extends Modifier {
    private static final String TAG = "TARE-" + PowerSaveModeModifier.class.getSimpleName();
    private static final boolean DEBUG = InternalResourceService.DEBUG
            || Log.isLoggable(TAG, Log.DEBUG);

    private final InternalResourceService mIrs;
    private final PowerSaveModeTracker mPowerSaveModeTracker;

    PowerSaveModeModifier(@NonNull InternalResourceService irs) {
        super();
        mIrs = irs;
        mPowerSaveModeTracker = new PowerSaveModeTracker();
    }

    @Override
    public void setup() {
        mPowerSaveModeTracker.startTracking(mIrs.getContext());
    }

    @Override
    public void tearDown() {
        mPowerSaveModeTracker.stopTracking(mIrs.getContext());
    }

    @Override
    long getModifiedCostToProduce(long ctp) {
        if (mPowerSaveModeTracker.mPowerSaveModeEnabled) {
            return (long) (1.5 * ctp);
        }
        // TODO: get adaptive power save mode
        if (mPowerSaveModeTracker.mPowerSaveModeEnabled) {
            return (long) (1.25 * ctp);
        }
        return ctp;
    }

    @Override
    void dump(IndentingPrintWriter pw) {
        pw.print("power save=");
        pw.println(mPowerSaveModeTracker.mPowerSaveModeEnabled);
    }

    // TODO: migrate to relying on PowerSaveState and ServiceType.TARE
    private final class PowerSaveModeTracker extends BroadcastReceiver {
        private boolean mIsSetup = false;

        private final PowerManager mPowerManager;
        private volatile boolean mPowerSaveModeEnabled;

        private PowerSaveModeTracker() {
            mPowerManager = mIrs.getContext().getSystemService(PowerManager.class);
        }

        public void startTracking(@NonNull Context context) {
            if (mIsSetup) {
                return;
            }

            final IntentFilter filter = new IntentFilter();
            filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            context.registerReceiver(this, filter);

            // Initialise tracker state.
            mPowerSaveModeEnabled = mPowerManager.isPowerSaveMode();

            mIsSetup = true;
        }

        public void stopTracking(@NonNull Context context) {
            if (!mIsSetup) {
                return;
            }

            context.unregisterReceiver(this);
            mIsSetup = false;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(action)) {
                final boolean enabled = mPowerManager.isPowerSaveMode();
                if (DEBUG) {
                    Slog.d(TAG, "Power save mode changed to " + enabled
                            + ", fired @ " + SystemClock.elapsedRealtime());
                }
                if (mPowerSaveModeEnabled != enabled) {
                    mPowerSaveModeEnabled = enabled;
                    mIrs.onDeviceStateChanged();
                }
            }
        }
    }
}
