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
import android.util.Slog;

import java.util.Arrays;
import java.util.List;

/** Represents a step to ramp down the vibrator amplitude before turning it off. */
final class RampOffVibratorStep extends AbstractVibratorStep {
    private final float mAmplitudeTarget;
    private final float mAmplitudeDelta;

    RampOffVibratorStep(VibrationStepConductor conductor, long startTime, float amplitudeTarget,
            float amplitudeDelta, VibratorController controller,
            long pendingVibratorOffDeadline) {
        super(conductor, startTime, controller, pendingVibratorOffDeadline);
        mAmplitudeTarget = amplitudeTarget;
        mAmplitudeDelta = amplitudeDelta;
    }

    @Override
    public boolean isCleanUp() {
        return true;
    }

    @NonNull
    @Override
    public List<Step> cancel() {
        return Arrays.asList(new TurnOffVibratorStep(conductor, SystemClock.uptimeMillis(),
                controller, /* isCleanUp= */ true));
    }

    @NonNull
    @Override
    public List<Step> play() {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "RampOffVibratorStep");
        try {
            if (VibrationThread.DEBUG) {
                long latency = SystemClock.uptimeMillis() - startTime;
                Slog.d(VibrationThread.TAG, "Ramp down the vibrator amplitude, step with "
                        + latency + "ms latency.");
            }
            if (mVibratorCompleteCallbackReceived) {
                // Vibration completion callback was received by this step, just turn if off
                // and skip the rest of the steps to ramp down the vibrator amplitude.
                stopVibrating();
                return VibrationStepConductor.EMPTY_STEP_LIST;
            }

            changeAmplitude(mAmplitudeTarget);

            float newAmplitudeTarget = mAmplitudeTarget - mAmplitudeDelta;
            if (newAmplitudeTarget < VibrationStepConductor.RAMP_OFF_AMPLITUDE_MIN) {
                // Vibrator amplitude cannot go further down, just turn it off with the configured
                // deadline that has been adjusted for the scenario when this was triggered by a
                // cancelled vibration.
                return Arrays.asList(new TurnOffVibratorStep(conductor, mPendingVibratorOffDeadline,
                        controller, /* isCleanUp= */ true));
            }
            return Arrays.asList(new RampOffVibratorStep(
                    conductor,
                    startTime + conductor.vibrationSettings.getRampStepDuration(),
                    newAmplitudeTarget, mAmplitudeDelta, controller,
                    mPendingVibratorOffDeadline));
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }
}
