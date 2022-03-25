/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.vibrator;

import android.annotation.NonNull;
import android.os.CombinedVibration;
import android.os.SystemClock;
import android.os.VibrationEffect;

import java.util.List;

/**
 * Represent a single step for playing a vibration.
 *
 * <p>Every step has a start time, which can be used to apply delays between steps while
 * executing them in sequence.
 */
abstract class Step implements Comparable<Step> {
    public final VibrationStepConductor conductor;
    public final long startTime;

    Step(VibrationStepConductor conductor, long startTime) {
        this.conductor = conductor;
        this.startTime = startTime;
    }

    protected Vibration getVibration() {
        return conductor.getVibration();
    }

    /**
     * Returns true if this step is a clean up step and not part of a {@link VibrationEffect} or
     * {@link CombinedVibration}.
     */
    public boolean isCleanUp() {
        return false;
    }

    /** Play this step, returning a (possibly empty) list of next steps. */
    @NonNull
    public abstract List<Step> play();

    /**
     * Cancel this pending step and return a (possibly empty) list of clean-up steps that should
     * be played to gracefully cancel this step.
     */
    @NonNull
    public abstract List<Step> cancel();

    /** Cancel this pending step immediately, skipping any clean-up. */
    public abstract void cancelImmediately();

    /**
     * Return the duration the vibrator was turned on when this step was played.
     *
     * @return A positive duration that the vibrator was turned on for by this step;
     * Zero if the segment is not supported, the step was not played yet or vibrator was never
     * turned on by this step; A negative value if the vibrator call has failed.
     */
    public long getVibratorOnDuration() {
        return 0;
    }

    /**
     * Return true to run this step right after a vibrator has notified vibration completed,
     * used to resume steps waiting on vibrator callbacks with a timeout.
     */
    public boolean acceptVibratorCompleteCallback(int vibratorId) {
        return false;
    }

    /**
     * Returns the time in millis to wait before playing this step. This is performed
     * while holding the queue lock, so should not rely on potentially slow operations.
     */
    public long calculateWaitTime() {
        if (startTime == Long.MAX_VALUE) {
            // This step don't have a predefined start time, it's just marked to be executed
            // after all other steps have finished.
            return 0;
        }
        return Math.max(0, startTime - SystemClock.uptimeMillis());
    }

    @Override
    public int compareTo(Step o) {
        return Long.compare(startTime, o.startTime);
    }
}
