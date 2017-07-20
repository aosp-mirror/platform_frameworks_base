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
 * limitations under the License.
 */

package com.android.systemui.keyguard;

import android.os.Handler;
import android.os.Message;

import com.android.internal.policy.IKeyguardDrawnCallback;

/**
 * Dispatches the lifecycles keyguard gets from WindowManager on the main thread.
 */
public class KeyguardLifecyclesDispatcher {

    static final int SCREEN_TURNING_ON = 0;
    static final int SCREEN_TURNED_ON = 1;
    static final int SCREEN_TURNING_OFF = 2;
    static final int SCREEN_TURNED_OFF = 3;

    static final int STARTED_WAKING_UP = 4;
    static final int FINISHED_WAKING_UP = 5;
    static final int STARTED_GOING_TO_SLEEP = 6;
    static final int FINISHED_GOING_TO_SLEEP = 7;

    private final ScreenLifecycle mScreenLifecycle;
    private final WakefulnessLifecycle mWakefulnessLifecycle;

    public KeyguardLifecyclesDispatcher(ScreenLifecycle screenLifecycle,
            WakefulnessLifecycle wakefulnessLifecycle) {
        mScreenLifecycle = screenLifecycle;
        mWakefulnessLifecycle = wakefulnessLifecycle;
    }

    void dispatch(int what) {
        mHandler.obtainMessage(what).sendToTarget();
    }

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case SCREEN_TURNING_ON:
                    mScreenLifecycle.dispatchScreenTurningOn();
                    break;
                case SCREEN_TURNED_ON:
                    mScreenLifecycle.dispatchScreenTurnedOn();
                    break;
                case SCREEN_TURNING_OFF:
                    mScreenLifecycle.dispatchScreenTurningOff();
                    break;
                case SCREEN_TURNED_OFF:
                    mScreenLifecycle.dispatchScreenTurnedOff();
                    break;
                case STARTED_WAKING_UP:
                    mWakefulnessLifecycle.dispatchStartedWakingUp();
                    break;
                case FINISHED_WAKING_UP:
                    mWakefulnessLifecycle.dispatchFinishedWakingUp();
                    break;
                case STARTED_GOING_TO_SLEEP:
                    mWakefulnessLifecycle.dispatchStartedGoingToSleep();
                    break;
                case FINISHED_GOING_TO_SLEEP:
                    mWakefulnessLifecycle.dispatchFinishedGoingToSleep();
                    break;
                default:
                    throw new IllegalArgumentException("Unknown message: " + msg);
            }
        }
    };

}
