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

import android.app.AlarmManager.OnAlarmListener;
import android.os.WorkSource;

import com.android.internal.util.Preconditions;

/**
 * Helps manage alarms.
 */
public abstract class AlarmHelper {

    /**
     * Sets a wakeup alarm that will fire after the given delay.
     */
    public final void setDelayedAlarm(long delayMs, OnAlarmListener listener,
            WorkSource workSource) {
        // helps ensure that we're not wasting system resources by setting alarms in the past/now
        Preconditions.checkArgument(delayMs > 0);
        setDelayedAlarmInternal(delayMs, listener, workSource);
    }

    protected abstract void setDelayedAlarmInternal(long delayMs, OnAlarmListener listener,
            WorkSource workSource);

    /**
     * Cancels an alarm.
     */
    public abstract void cancel(OnAlarmListener listener);
}
