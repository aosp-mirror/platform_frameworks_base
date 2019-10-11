/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.car;

import android.car.userlib.CarUserManagerHelper;
import android.content.Context;
import android.os.CountDownTimer;
import android.util.Log;

import androidx.annotation.GuardedBy;

import com.android.systemui.R;

/**
 * Wrapper for a countdown timer that switches to Guest if the user has been driving with
 * the keyguard up for configurable number of seconds.
 */
public class SwitchToGuestTimer {
    private static final String TAG = "SwitchToGuestTimer";

    // After how many ms CountdownTimer.onTick gets triggered.
    private static final int COUNTDOWN_INTERVAL_MS = 1000;

    private final CarUserManagerHelper mCarUserManagerHelper;
    private final Object mTimerLock;
    private final String mGuestName;
    private final int mTimeoutMs;
    private final boolean mEnabled;

    @GuardedBy("mTimerLock")
    private CountDownTimer mSwitchToGuestTimer;

    public SwitchToGuestTimer(Context context) {
        mCarUserManagerHelper = new CarUserManagerHelper(context);
        mGuestName = context.getResources().getString(R.string.car_guest);
        mTimeoutMs = context.getResources().getInteger(R.integer.driving_on_keyguard_timeout_ms);

        // Lock prevents multiple timers being started.
        mTimerLock = new Object();

        // If milliseconds to switch is a negative number, the feature is disabled.
        mEnabled = mTimeoutMs >= 0;
    }

    /**
     * Starts the timer if it's not already running.
     */
    public void start() {
        if (!mEnabled) {
            logD("Switching to guest after driving on keyguard is disabled.");
            return;
        }

        synchronized (mTimerLock) {
            if (mSwitchToGuestTimer != null) {
                logD("Timer is already running.");
                return;
            }

            mSwitchToGuestTimer = new CountDownTimer(mTimeoutMs, COUNTDOWN_INTERVAL_MS) {
                @Override
                public void onTick(long msUntilFinished) {
                    logD("Ms until switching to guest: " + Long.toString(msUntilFinished));
                }

                @Override
                public void onFinish() {
                    mCarUserManagerHelper.startGuestSession(mGuestName);
                    cancel();
                }
            };

            logI("Starting timer");
            mSwitchToGuestTimer.start();
        }
    }

    /**
     * Cancels the running timer.
     */
    public void cancel() {
        synchronized (mTimerLock) {
            if (mSwitchToGuestTimer != null) {
                logI("Cancelling timer");
                mSwitchToGuestTimer.cancel();
                mSwitchToGuestTimer = null;
            }
        }
    }

    private void logD(String message) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, message);
        }
    }

    private void logI(String message) {
        if (Log.isLoggable(TAG, Log.INFO)) {
            Log.i(TAG, message);
        }
    }
}
