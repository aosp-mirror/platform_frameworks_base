/*
 * Copyright (C) 2017 The OmniROM Project
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

package com.android.systemui;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;

/**
 * Performs a number of miscellaneous, non-system-critical actions
 * after the system has finished booting.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "SystemUIBootReceiver";
    private Handler mHandler = new Handler();
    private SettingsObserver mSettingsObserver;
    private Context mContext;

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            mContext.getContentResolver().registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.SHOW_CPU),
                    false, this);
            update();
        }

        @Override
        public void onChange(boolean selfChange) {
            update();
        }

        public void update() {
            Intent cpuinfo = new Intent(mContext, com.android.systemui.CPUInfoService.class);
            if (Settings.Global.getInt(mContext.getContentResolver(), Settings.Global.SHOW_CPU, 0) != 0) {
                mContext.startService(cpuinfo);
            } else {
                mContext.stopService(cpuinfo);
            }
        }
    }

    @Override
    public void onReceive(final Context context, Intent intent) {
        try {
            mContext = context;
            if (mSettingsObserver ==  null) {
                mSettingsObserver = new SettingsObserver(mHandler);
                mSettingsObserver.observe();
            }

            // Start the cpu info overlay, if activated
            if (Settings.Global.getInt(mContext.getContentResolver(), Settings.Global.SHOW_CPU, 0) != 0) {
                Intent cpuinfo = new Intent(mContext, com.android.systemui.CPUInfoService.class);
                mContext.startService(cpuinfo);
            }

        } catch (Exception e) {
            Log.e(TAG, "Can't start load average service", e);
        }
    }
}
