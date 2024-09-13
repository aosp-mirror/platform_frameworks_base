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
import android.os.VibrationEffect;
import android.util.Slog;

import java.util.Arrays;
import java.util.List;

/**
 * Represents a step to complete a {@link VibrationEffect}.
 *
 * <p>This runs right at the time the vibration is considered to end and will update the pending
 * vibrators count. This can turn off the vibrator or slowly ramp it down to zero amplitude.
 */
final class CompleteEffectVibratorStep extends AbstractVibratorStep {
    private final boolean mCancelled;

    CompleteEffectVibratorStep(VibrationStepConductor conductor, long startTime, boolean cancelled,
            VibratorController controller, long pendingVibratorOffDeadline) {
        super(conductor, startTime, controller, pendingVibratorOffDeadline);
        mCancelled = cancelled;
    }

    @Override
    public boolean isCleanUp() {
        // If the vibration was cancelled then this is just a clean up to ramp off the vibrator.
        // Otherwise this step is part of the vibration.
        return mCancelled;
    }

    @NonNull
    @Override
    public List<Step> cancel() {
        if (mCancelled) {
            // Double cancelling will just turn off the vibrator right away.
            return Arrays.asList(new TurnOffVibratorStep(conductor, SystemClock.uptimeMillis(),
                    controller, /* isCleanUp= */ true));
        }
        return super.cancel();
    }

    @NonNull
    @Override
    public List<Step> play() {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "CompleteEffectVibratorStep");
        try {
            if (VibrationThread.DEBUG) {
                Slog.d(VibrationThread.TAG,
                        "Running " + (mCancelled ? "cancel" : "complete") + " vibration"
                                + " step on vibrator " + controller.getVibratorInfo().getId());
            }
            if (mVibratorCompleteCallbackReceived) {
                // Vibration completion callback was received by this step, just turn if off
                // and skip any clean-up.
                stopVibrating();
                return VibrationStepConductor.EMPTY_STEP_LIST;
            }

            long now = SystemClock.uptimeMillis();
            float currentAmplitude = controller.getCurrentAmplitude();
            long remainingOnDuration =
                    mPendingVibratorOffDeadline - now
                            - VibrationStepConductor.CALLBACKS_EXTRA_TIMEOUT;
            long rampDownDuration =
                    Math.min(remainingOnDuration,
                            conductor.vibrationSettings.getRampDownDuration());
            long stepDownDuration = conductor.vibrationSettings.getRampStepDuration();
            if (currentAmplitude < VibrationStepConductor.RAMP_OFF_AMPLITUDE_MIN
                    || rampDownDuration <= stepDownDuration) {
                // No need to ramp down the amplitude, just wait to turn it off.
                if (mCancelled) {
                    // Vibration is completing because it was cancelled, turn off right away.
                    stopVibrating();
                    return VibrationStepConductor.EMPTY_STEP_LIST;
                } else {
                    // Vibration is completing normally, turn off after the deadline in case we
                    // don't receive the callback in time (callback also triggers it right away).
                    return Arrays.asList(new TurnOffVibratorStep(conductor,
                            mPendingVibratorOffDeadline, controller, /* isCleanUp= */ false));
                }
            }

            if (VibrationThread.DEBUG) {
                Slog.d(VibrationThread.TAG,
                        "Ramping down vibrator " + controller.getVibratorInfo().getId()
                                + " from amplitude " + currentAmplitude
                                + " for " + rampDownDuration + "ms");
            }

            // If we are cancelling this vibration then make sure the vibrator will be turned off
            // immediately after the ramp off duration. Otherwise, this is a planned ramp off for
            // the remaining ON duration, then just propagate the mPendingVibratorOffDeadline so the
            // turn off step will wait for the vibration completion callback and end gracefully.
            long rampOffVibratorOffDeadline =
                    mCancelled ? (now + rampDownDuration) : mPendingVibratorOffDeadline;
            float amplitudeDelta = currentAmplitude / (rampDownDuration / stepDownDuration);
            float amplitudeTarget = currentAmplitude - amplitudeDelta;
            return Arrays.asList(
                    new RampOffVibratorStep(conductor, startTime, amplitudeTarget, amplitudeDelta,
                            controller, rampOffVibratorOffDeadline));
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }
}
