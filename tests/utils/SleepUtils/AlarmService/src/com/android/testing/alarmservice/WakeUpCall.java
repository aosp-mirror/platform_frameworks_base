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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * The receiver for the alarm we set
 *
 */
public class WakeUpCall extends BroadcastReceiver {

    public static final String WAKEUP_CALL = "com.android.testing.alarmservice.WAKEUP";

    @Override
    public void onReceive(Context context, Intent intent) {
        // we acquire wakelock without release because user is supposed to manually release it
        WakeUpController.getController().getWakeLock().acquire();
        Object lock = WakeUpController.getController().getWakeSync();
        synchronized (lock) {
            // poke the lock so the service side can be woken from waiting on the lock
            lock.notifyAll();
        }
    }

}
