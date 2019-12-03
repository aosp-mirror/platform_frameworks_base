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
import android.os.Trace;

import com.android.systemui.Dumpable;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Tracks the wakefulness lifecycle.
 */
@Singleton
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

    @Inject
    public WakefulnessLifecycle() {
    }

    public @Wakefulness int getWakefulness() {
        return mWakefulness;
    }

    public void dispatchStartedWakingUp() {
        if (getWakefulness() == WAKEFULNESS_WAKING) {
            return;
        }
        setWakefulness(WAKEFULNESS_WAKING);
        dispatch(Observer::onStartedWakingUp);
    }

    public void dispatchFinishedWakingUp() {
        if (getWakefulness() == WAKEFULNESS_AWAKE) {
            return;
        }
        setWakefulness(WAKEFULNESS_AWAKE);
        dispatch(Observer::onFinishedWakingUp);
    }

    public void dispatchStartedGoingToSleep() {
        if (getWakefulness() == WAKEFULNESS_GOING_TO_SLEEP) {
            return;
        }
        setWakefulness(WAKEFULNESS_GOING_TO_SLEEP);
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
