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
    private static final long LOG_PERIOD_MS = 120 * 1000;

    private final Handler mHandler;

    private Runnable mLogData = new Runnable() {
        @Override
        public void run() {
            // Do not wake the device for these messages.  Opportunistically log rail data every
            // LOG_PERIOD_MS.
            mHandler.postDelayed(mLogData, LOG_PERIOD_MS);
            if (DEBUG) Slog.d(TAG, "Received delayed message.  Log rail data");
            logPowerStatsData(PowerStatsLogger.MSG_LOG_TO_DATA_STORAGE_TIMER);
        }
    };

    public TimerTrigger(Context context, PowerStatsLogger powerStatsLogger,
            boolean triggerEnabled) {
        super(context, powerStatsLogger);
        mHandler = mContext.getMainThreadHandler();

        if (triggerEnabled) {
            mLogData.run();
        }
    }
}
