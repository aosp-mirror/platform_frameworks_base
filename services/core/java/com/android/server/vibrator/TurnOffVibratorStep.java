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
import android.os.SystemClock;
import android.os.Trace;

import java.util.Arrays;
import java.util.List;

/**
 * Represents a step to turn the vibrator off.
 *
 * <p>This runs after a timeout on the expected time the vibrator should have finished playing,
 * and can be brought forward by vibrator complete callbacks. The step shouldn't be skipped, even
 * if the vibrator-complete callback was received, as some implementations still rely on the
 * "off" call to actually stop.
 */
final class TurnOffVibratorStep extends AbstractVibratorStep {

    private final boolean mIsCleanUp;

    TurnOffVibratorStep(VibrationStepConductor conductor, long startTime,
            VibratorController controller, boolean isCleanUp) {
        super(conductor, startTime, controller, startTime);
        mIsCleanUp = isCleanUp;
    }

    @Override
    public boolean isCleanUp() {
        return mIsCleanUp;
    }

    @NonNull
    @Override
    public List<Step> cancel() {
        return Arrays.asList(new TurnOffVibratorStep(conductor, SystemClock.uptimeMillis(),
                controller, /* isCleanUp= */ true));
    }

    @Override
    public void cancelImmediately() {
        stopVibrating();
    }

    @NonNull
    @Override
    public List<Step> play() {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "TurnOffVibratorStep");
        try {
            stopVibrating();
            return VibrationStepConductor.EMPTY_STEP_LIST;
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }
}
