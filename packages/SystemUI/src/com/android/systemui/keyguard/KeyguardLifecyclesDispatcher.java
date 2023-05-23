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

import android.annotation.IntDef;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.TraceNameSupplier;

import androidx.annotation.NonNull;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import javax.inject.Inject;

/**
 * Dispatches the lifecycles keyguard gets from WindowManager on the main thread.
 */
@SysUISingleton
public class KeyguardLifecyclesDispatcher {
    public static final int SCREEN_TURNING_ON = 0;
    public static final int SCREEN_TURNED_ON = 1;
    public static final int SCREEN_TURNING_OFF = 2;
    public static final int SCREEN_TURNED_OFF = 3;

    public static final int STARTED_WAKING_UP = 4;
    public static final int FINISHED_WAKING_UP = 5;
    public static final int STARTED_GOING_TO_SLEEP = 6;
    public static final int FINISHED_GOING_TO_SLEEP = 7;

    @IntDef({
            SCREEN_TURNING_ON,
            SCREEN_TURNED_ON,
            SCREEN_TURNING_OFF,
            SCREEN_TURNED_OFF,
            STARTED_WAKING_UP,
            FINISHED_WAKING_UP,
            STARTED_GOING_TO_SLEEP,
            FINISHED_GOING_TO_SLEEP,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface KeyguardLifecycleMessageType {
    }

    private static String getNameOfMessage(@KeyguardLifecycleMessageType int what) {
        return switch (what) {
            case SCREEN_TURNING_ON -> "SCREEN_TURNING_ON";
            case SCREEN_TURNED_ON -> "SCREEN_TURNED_ON";
            case SCREEN_TURNING_OFF -> "SCREEN_TURNING_OFF";
            case SCREEN_TURNED_OFF -> "SCREEN_TURNED_OFF";
            case STARTED_WAKING_UP -> "STARTED_WAKING_UP";
            case FINISHED_WAKING_UP -> "FINISHED_WAKING_UP";
            case STARTED_GOING_TO_SLEEP -> "STARTED_GOING_TO_SLEEP";
            case FINISHED_GOING_TO_SLEEP -> "FINISHED_GOING_TO_SLEEP";
            default -> "UNKNOWN";
        };
    }

    private final Handler mHandler;

    @Inject
    public KeyguardLifecyclesDispatcher(
            @Main Looper mainLooper,
            ScreenLifecycle screenLifecycle,
            WakefulnessLifecycle wakefulnessLifecycle) {
        mHandler = new KeyguardLifecycleHandler(mainLooper, screenLifecycle, wakefulnessLifecycle);
    }

    protected void dispatch(@KeyguardLifecycleMessageType int what) {
        mHandler.obtainMessage(what).sendToTarget();
    }

    /**
     * @param what Message to send.
     * @param pmReason Reason this message was triggered - this should be a value from either
     * {@link PowerManager.WakeReason} or {@link PowerManager.GoToSleepReason}.
     */
    protected void dispatch(@KeyguardLifecycleMessageType int what, int pmReason) {
        final Message message = mHandler.obtainMessage(what);
        message.arg1 = pmReason;
        message.sendToTarget();
    }

    /**
     * @param what Message to send.
     * @param object Object to send with the message
     */
    protected void dispatch(@KeyguardLifecycleMessageType int what, Object object) {
        mHandler.obtainMessage(what, object).sendToTarget();
    }

    private static class KeyguardLifecycleHandler extends Handler {
        private static final String TAG = "KeyguardLifecycleHandler";
        private final ScreenLifecycle mScreenLifecycle;
        private final WakefulnessLifecycle mWakefulnessLifecycle;

        public KeyguardLifecycleHandler(Looper looper,
                                         ScreenLifecycle screenLifecycle,
                                         WakefulnessLifecycle wakefulnessLifecycle) {
            super(looper);
            mScreenLifecycle = screenLifecycle;
            mWakefulnessLifecycle = wakefulnessLifecycle;
        }

        @NonNull
        @Override
        public String getTraceName(@NonNull Message msg) {
            if (msg.getCallback() instanceof TraceNameSupplier || msg.getCallback() != null) {
                return super.getTraceName(msg);
            }
            return TAG + "#" + getNameOfMessage(msg.what);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            switch (msg.what) {
                case SCREEN_TURNING_ON -> mScreenLifecycle.dispatchScreenTurningOn();
                case SCREEN_TURNED_ON -> mScreenLifecycle.dispatchScreenTurnedOn();
                case SCREEN_TURNING_OFF -> mScreenLifecycle.dispatchScreenTurningOff();
                case SCREEN_TURNED_OFF -> mScreenLifecycle.dispatchScreenTurnedOff();
                case STARTED_WAKING_UP ->
                        mWakefulnessLifecycle.dispatchStartedWakingUp(msg.arg1 /* pmReason */);
                case FINISHED_WAKING_UP -> mWakefulnessLifecycle.dispatchFinishedWakingUp();
                case STARTED_GOING_TO_SLEEP ->
                        mWakefulnessLifecycle.dispatchStartedGoingToSleep(msg.arg1 /* pmReason */);
                case FINISHED_GOING_TO_SLEEP ->
                        mWakefulnessLifecycle.dispatchFinishedGoingToSleep();
                default -> throw new IllegalArgumentException("Unknown message: " + msg);
            }
        }
    }
}
