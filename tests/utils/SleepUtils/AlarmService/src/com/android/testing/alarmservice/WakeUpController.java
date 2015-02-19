/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.testing.alarmservice;

import android.content.Context;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;

/**
 * A singleton used for controlling and sharing of states/wakelocks
 *
 */
public class WakeUpController {

    private static final String LOG_TAG = WakeUpController.class.getName();
    private static WakeUpController mController = null;
    private WakeLock mWakeLock = null;
    private Object mWakeSync = new Object();

    private WakeUpController() {
        Log.i(LOG_TAG, "Created instance: 0x" + Integer.toHexString(this.hashCode()));
    }

    public static synchronized WakeUpController getController() {
        if (mController == null) {
            mController = new WakeUpController();
        }
        return mController;
    }

    public WakeLock getWakeLock() {
        if (mWakeLock == null) {
            PowerManager pm =
                    (PowerManager) AlarmService.sContext.getSystemService(Context.POWER_SERVICE);
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "testing-alarmservice");
            Log.i(LOG_TAG, "Create wakelock: 0x" + Integer.toHexString(mWakeLock.hashCode()));
        }
        return mWakeLock;
    }

    public Object getWakeSync() {
        return mWakeSync;
    }
}
