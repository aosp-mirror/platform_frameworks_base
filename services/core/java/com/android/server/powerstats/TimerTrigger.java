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
import android.os.Handler;
import android.util.Slog;

/**
 * TimerTrigger sets a 60 second opportunistic timer using postDelayed.
 * When the timer expires a message is sent to the PowerStatsLogger to
 * read the rail energy data and log it to on-device storage.
 */
public final class TimerTrigger extends PowerStatsLogTrigger {
    private static final String TAG = TimerTrigger.class.getSimpleName();
    private static final boolean DEBUG = false;
    // TODO(b/166689029): Make configurable through global settings.
    private static final long LOG_PERIOD_MS_LOW_FREQUENCY = 60 * 60 * 1000; // 1 hour
    private static final long LOG_PERIOD_MS_HIGH_FREQUENCY = 2 * 60 * 1000; // 2 minutes

    private final Handler mHandler;

    private Runnable mLogDataLowFrequency = new Runnable() {
        @Override
        public void run() {
            // Do not wake the device for these messages.  Opportunistically log rail data every
            // LOG_PERIOD_MS_LOW_FREQUENCY.
            mHandler.postDelayed(mLogDataLowFrequency, LOG_PERIOD_MS_LOW_FREQUENCY);
            if (DEBUG) Slog.d(TAG, "Received delayed message.  Log rail data low frequency");
            logPowerStatsData(PowerStatsLogger.MSG_LOG_TO_DATA_STORAGE_LOW_FREQUENCY);
        }
    };

    private Runnable mLogDataHighFrequency = new Runnable() {
        @Override
        public void run() {
            // Do not wake the device for these messages.  Opportunistically log rail data every
            // LOG_PERIOD_MS_HIGH_FREQUENCY.
            mHandler.postDelayed(mLogDataHighFrequency, LOG_PERIOD_MS_HIGH_FREQUENCY);
            if (DEBUG) Slog.d(TAG, "Received delayed message.  Log rail data high frequency");
            logPowerStatsData(PowerStatsLogger.MSG_LOG_TO_DATA_STORAGE_HIGH_FREQUENCY);
        }
    };

    public TimerTrigger(Context context, PowerStatsLogger powerStatsLogger,
            boolean triggerEnabled) {
        super(context, powerStatsLogger);
        mHandler = mContext.getMainThreadHandler();

        if (triggerEnabled) {
            mLogDataLowFrequency.run();
            mLogDataHighFrequency.run();
        }
    }
}
