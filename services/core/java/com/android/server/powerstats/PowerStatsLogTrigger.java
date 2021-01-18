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

package com.android.server.powerstats;

import android.content.Context;
import android.os.Message;

/**
 * PowerStatsLogTrigger is the base class for other trigger classes.
 * It provides the logPowerStatsData() function which sends a message
 * to the PowerStatsLogger to read the rail energy data and log it to
 * on-device storage.  This class is abstract and cannot be instantiated.
 */
public abstract class PowerStatsLogTrigger {
    private static final String TAG = PowerStatsLogTrigger.class.getSimpleName();

    protected Context mContext;
    private PowerStatsLogger mPowerStatsLogger;

    protected void logPowerStatsData(int msgType) {
        Message.obtain(mPowerStatsLogger, msgType).sendToTarget();
    }

    public PowerStatsLogTrigger(Context context, PowerStatsLogger powerStatsLogger) {
        mContext = context;
        mPowerStatsLogger = powerStatsLogger;
    }
}
