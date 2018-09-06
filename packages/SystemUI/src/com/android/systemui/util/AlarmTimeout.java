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
 * limitations under the License
 */

package com.android.systemui.util;

import android.app.AlarmManager;
import android.os.Handler;
import android.os.SystemClock;

/**
 * Schedules a timeout through AlarmManager. Ensures that the timeout is called even when
 * the device is asleep.
 */
public class AlarmTimeout implements AlarmManager.OnAlarmListener {

    public static final int MODE_CRASH_IF_SCHEDULED = 0;
    public static final int MODE_IGNORE_IF_SCHEDULED = 1;
    public static final int MODE_RESCHEDULE_IF_SCHEDULED = 2;

    private final AlarmManager mAlarmManager;
    private final AlarmManager.OnAlarmListener mListener;
    private final String mTag;
    private final Handler mHandler;
    private boolean mScheduled;

    public AlarmTimeout(AlarmManager alarmManager, AlarmManager.OnAlarmListener listener,
            String tag, Handler handler) {
        mAlarmManager = alarmManager;
        mListener = listener;
        mTag = tag;
        mHandler = handler;
    }

    /**
     * Schedules an alarm in {@code timeout} milliseconds in the future.
     *
     * @param timeout How long to wait from now.
     * @param mode {@link #MODE_CRASH_IF_SCHEDULED}, {@link #MODE_IGNORE_IF_SCHEDULED} or
     *             {@link #MODE_RESCHEDULE_IF_SCHEDULED}.
     * @return {@code true} when scheduled successfully, {@code false} otherwise.
     */
    public boolean schedule(long timeout, int mode) {
        switch (mode) {
            case MODE_CRASH_IF_SCHEDULED:
                if (mScheduled) {
                    throw new IllegalStateException(mTag + " timeout is already scheduled");
                }
                break;
            case MODE_IGNORE_IF_SCHEDULED:
                if (mScheduled) {
                    return false;
                }
                break;
            case MODE_RESCHEDULE_IF_SCHEDULED:
                if (mScheduled) {
                    cancel();
                }
                break;
            default:
                throw new IllegalArgumentException("Illegal mode: " + mode);
        }

        mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + timeout, mTag, this, mHandler);
        mScheduled = true;
        return true;
    }

    public boolean isScheduled() {
        return mScheduled;
    }

    public void cancel() {
        if (mScheduled) {
            mAlarmManager.cancel(this);
            mScheduled = false;
        }
    }

    @Override
    public void onAlarm() {
        if (!mScheduled) {
            // We canceled the alarm, but it still fired. Ignore.
            return;
        }
        mScheduled = false;
        mListener.onAlarm();
    }
}
