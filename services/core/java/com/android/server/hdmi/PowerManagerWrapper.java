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

package com.android.server.hdmi;

import android.content.Context;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;

/**
 * Abstraction around {@link PowerManager} to allow faking PowerManager in tests.
 */
public class PowerManagerWrapper {
    private static final String TAG = "PowerManagerWrapper";

    private final PowerManager mPowerManager;

    public PowerManagerWrapper(Context context) {
        mPowerManager = context.getSystemService(PowerManager.class);
    }

    boolean isInteractive() {
        return mPowerManager.isInteractive();
    }

    void wakeUp(long time, int reason, String details) {
        mPowerManager.wakeUp(time, reason, details);
    }

    void goToSleep(long time, int reason, int flags) {
        mPowerManager.goToSleep(time, reason, flags);
    }

    WakeLock newWakeLock(int levelAndFlags, String tag) {
        return mPowerManager.newWakeLock(levelAndFlags, tag);
    }
}
