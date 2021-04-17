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
import android.os.PowerManager;
import android.os.Trace;

import com.android.systemui.Dumpable;
import com.android.systemui.dagger.SysUISingleton;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import javax.inject.Inject;

/**
 * Tracks the wakefulness lifecycle.
 */
@SysUISingleton
public class WakefulnessLifecycle extends Lifecycle<WakefulnessLifecycle.Observer> implements
        Dumpable {

    @IntDef(prefix = { "WAKEFULNESS_" }, value = {
            WAKEFULNESS_ASLEEP,
            WAKEFULNESS_WAKING,
            WAKEFULNESS_AWAKE,
            WAKEFULNESS_GOING_TO_SLEEP,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Wakefulness {}

    public static final int WAKEFULNESS_ASLEEP = 0;
    public static final int WAKEFULNESS_WAKING = 1;
    public static final int WAKEFULNESS_AWAKE = 2;
    public static final int WAKEFULNESS_GOING_TO_SLEEP = 3;

    private int mWakefulness = WAKEFULNESS_ASLEEP;
    private @PowerManager.WakeReason int mLastWakeReason = PowerManager.WAKE_REASON_UNKNOWN;
    private @PowerManager.GoToSleepReason int mLastSleepReason =
            PowerManager.GO_TO_SLEEP_REASON_MIN;

    @Inject
    public WakefulnessLifecycle() {
    }

    public @Wakefulness int getWakefulness() {
        return mWakefulness;
    }

    /**
     * Returns the most recent reason the device woke up. This is one of PowerManager.WAKE_REASON_*.
     */
    public @PowerManager.WakeReason int getLastWakeReason() {
        return mLastWakeReason;
    }

    /**
     * Returns the most recent reason the device went to sleep up. This is one of
     * PowerManager.GO_TO_SLEEP_REASON_*.
     */
    public @PowerManager.GoToSleepReason int getLastSleepReason() {
        return mLastSleepReason;
    }

    public void dispatchStartedWakingUp(@PowerManager.WakeReason int pmWakeReason) {
        if (getWakefulness() == WAKEFULNESS_WAKING) {
            return;
        }
        setWakefulness(WAKEFULNESS_WAKING);
        mLastWakeReason = pmWakeReason;
        dispatch(Observer::onStartedWakingUp);
    }

    public void dispatchFinishedWakingUp() {
        if (getWakefulness() == WAKEFULNESS_AWAKE) {
            return;
        }
        setWakefulness(WAKEFULNESS_AWAKE);
        dispatch(Observer::onFinishedWakingUp);
    }

    public void dispatchStartedGoingToSleep(@PowerManager.GoToSleepReason int pmSleepReason) {
        if (getWakefulness() == WAKEFULNESS_GOING_TO_SLEEP) {
            return;
        }
        setWakefulness(WAKEFULNESS_GOING_TO_SLEEP);
        mLastSleepReason = pmSleepReason;
        dispatch(Observer::onStartedGoingToSleep);
    }

    public void dispatchFinishedGoingToSleep() {
        if (getWakefulness() == WAKEFULNESS_ASLEEP) {
            return;
        }
        setWakefulness(WAKEFULNESS_ASLEEP);
        dispatch(Observer::onFinishedGoingToSleep);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("WakefulnessLifecycle:");
        pw.println("  mWakefulness=" + mWakefulness);
    }

    private void setWakefulness(@Wakefulness int wakefulness) {
        mWakefulness = wakefulness;
        Trace.traceCounter(Trace.TRACE_TAG_APP, "wakefulness", wakefulness);
    }

    public interface Observer {
        default void onStartedWakingUp() {}
        default void onFinishedWakingUp() {}
        default void onStartedGoingToSleep() {}
        default void onFinishedGoingToSleep() {}
    }
}
