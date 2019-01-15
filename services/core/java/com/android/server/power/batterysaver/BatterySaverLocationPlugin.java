/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.server.power.batterysaver;

import android.content.Context;
import android.os.PowerManager;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.util.Slog;

import com.android.server.power.batterysaver.BatterySaverController.Plugin;

public class BatterySaverLocationPlugin implements Plugin {
    private static final String TAG = "BatterySaverLocationPlugin";

    private static final boolean DEBUG = BatterySaverController.DEBUG;

    private final Context mContext;

    public BatterySaverLocationPlugin(Context context) {
        mContext = context;
    }

    @Override
    public void onBatterySaverChanged(BatterySaverController caller) {
        if (DEBUG) {
            Slog.d(TAG, "onBatterySaverChanged");
        }
        updateLocationState(caller);
    }

    @Override
    public void onSystemReady(BatterySaverController caller) {
        if (DEBUG) {
            Slog.d(TAG, "onSystemReady");
        }
        updateLocationState(caller);
    }

    private void updateLocationState(BatterySaverController caller) {
        final boolean kill =
                (caller.getBatterySaverPolicy().getGpsMode()
                        == PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF)
                        && !caller.isInteractive();

        if (DEBUG) {
            Slog.d(TAG, "Battery saver " + (kill ? "stopping" : "restoring") + " location.");
        }
        Settings.Global.putInt(mContext.getContentResolver(),
                Global.LOCATION_GLOBAL_KILL_SWITCH, kill ? 1 : 0);
    }
}
