/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.util.sensors;

import android.app.AlarmManager;
import android.os.Handler;

import com.android.systemui.util.AlarmTimeout;

/**
 * Monitors a sensor for over-activity and can throttle it down as needed.
 *
 * Currently, this class is designed with the {@link ProximitySensor} in mind.
 */
public class SensorRateLimiter {
    private final LimitableSensor mSensor;
    private final AlarmTimeout mCooldownTimer;
    private final long mCoolDownTriggerMs;
    private final long mCoolDownPeriodMs;
    private long mLastTimestampMs;

    public SensorRateLimiter(
            LimitableSensor sensor,
            AlarmManager alarmManager,
            long coolDownTriggerMs,
            long coolDownPeriodMs,
            String alarmTag) {

        mSensor = sensor;
        mCoolDownTriggerMs = coolDownTriggerMs;
        mCoolDownPeriodMs = coolDownPeriodMs;
        mLastTimestampMs = -1;

        Handler handler = new Handler();
        mCooldownTimer = new AlarmTimeout(alarmManager, this::coolDownComplete, alarmTag, handler);

        mSensor.setRateLimiter(this);
    }


    void onSensorEvent(long timestampMs) {
        if (mLastTimestampMs >= 0 && mCoolDownTriggerMs > 0 && mCoolDownPeriodMs > 0
                && timestampMs - mLastTimestampMs < mCoolDownTriggerMs) {
            scheduleCoolDown();
        }

        mLastTimestampMs = timestampMs;
    }

    private void scheduleCoolDown() {
        mSensor.setRateLimited(true);
        mCooldownTimer.schedule(mCoolDownPeriodMs, AlarmTimeout.MODE_IGNORE_IF_SCHEDULED);
    }

    private void coolDownComplete() {
        mSensor.setRateLimited(false);
    }

    @Override
    public String toString() {
        return String.format("{coolingDown=%s}", mCooldownTimer.isScheduled());
    }
}
