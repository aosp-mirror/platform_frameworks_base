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

package com.android.server.location.injector;

import static android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP;
import static android.app.AlarmManager.WINDOW_EXACT;

import android.app.AlarmManager;
import android.content.Context;
import android.os.SystemClock;
import android.os.WorkSource;

import com.android.server.FgThread;

import java.util.Objects;

/**
 * Provides helpers for alarms.
 */
public class SystemAlarmHelper extends AlarmHelper {

    private final Context mContext;

    public SystemAlarmHelper(Context context) {
        mContext = context;
    }

    @Override
    public void setDelayedAlarmInternal(long delayMs, AlarmManager.OnAlarmListener listener,
            WorkSource workSource) {
        AlarmManager alarmManager = Objects.requireNonNull(
                mContext.getSystemService(AlarmManager.class));
        alarmManager.set(ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs,
                WINDOW_EXACT, 0, listener, FgThread.getHandler(), workSource);
    }

    @Override
    public void cancel(AlarmManager.OnAlarmListener listener) {
        AlarmManager alarmManager = Objects.requireNonNull(
                mContext.getSystemService(AlarmManager.class));
        alarmManager.cancel(listener);
    }
}
