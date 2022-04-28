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

import android.os.SystemClock;
import android.os.VibrationEffect;
import android.util.Slog;

import java.util.Arrays;
import java.util.List;

/**
 * Represent a step on a single vibrator that plays one or more segments from a
 * {@link VibrationEffect.Composed} effect.
 */
abstract class AbstractVibratorStep extends Step {
    public final VibratorController controller;
    public final VibrationEffect.Composed effect;
    public final int segmentIndex;
    public final long previousStepVibratorOffTimeout;

    long mVibratorOnResult;
    boolean mVibratorCompleteCallbackReceived;

    /**
     * @param conductor          The VibrationStepConductor for these steps.
     * @param startTime          The time to schedule this step in the
     *                           {@link VibrationStepConductor}.
     * @param controller         The vibrator that is playing the effect.
     * @param effect             The effect being played in this step.
     * @param index              The index of the next segment to be played by this step
     * @param previousStepVibratorOffTimeout The time the vibrator is expected to complete any
     *                           previous vibration and turn off. This is used to allow this step to
     *                           be triggered when the completion callback is received, and can
     *                           be used to play effects back-to-back.
     */
    AbstractVibratorStep(VibrationStepConductor conductor, long startTime,
            VibratorController controller, VibrationEffect.Composed effect, int index,
            long previousStepVibratorOffTimeout) {
        super(conductor, startTime);
        this.controller = controller;
        this.effect = effect;
        this.segmentIndex = index;
        this.previousStepVibratorOffTimeout = previousStepVibratorOffTimeout;
    }

    public int getVibratorId() {
        return controller.getVibratorInfo().getId();
    }

    @Override
    public long getVibratorOnDuration() {
        return mVibratorOnResult;
    }

    @Override
    public boolean acceptVibratorCompleteCallback(int vibratorId) {
        boolean isSameVibrator = controller.getVibratorInfo().getId() == vibratorId;
        mVibratorCompleteCallbackReceived |= isSameVibrator;
        // Only activate this step if a timeout was set to wait for the vibration to complete,
        // otherwise we are waiting for the correct time to play the next step.
        return isSameVibrator && (previousStepVibratorOffTimeout > SystemClock.uptimeMillis());
    }

    @Override
    public List<Step> cancel() {
        return Arrays.asList(new CompleteEffectVibratorStep(conductor, SystemClock.uptimeMillis(),
                /* cancelled= */ true, controller, previousStepVibratorOffTimeout));
    }

    @Override
    public void cancelImmediately() {
        if (previousStepVibratorOffTimeout > SystemClock.uptimeMillis()) {
            // Vibrator might be running from previous steps, so turn it off while canceling.
            stopVibrating();
        }
    }

    protected void stopVibrating() {
        if (VibrationThread.DEBUG) {
            Slog.d(VibrationThread.TAG,
                    "Turning off vibrator " + getVibratorId());
        }
        controller.off();
    }

    protected void changeAmplitude(float amplitude) {
        if (VibrationThread.DEBUG) {
            Slog.d(VibrationThread.TAG,
                    "Amplitude changed on vibrator " + getVibratorId() + " to " + amplitude);
        }
        controller.setAmplitude(amplitude);
    }

    /**
     * Return the {@link VibrationStepConductor#nextVibrateStep} with same timings, only jumping
     * the segments.
     */
    protected List<Step> skipToNextSteps(int segmentsSkipped) {
        return nextSteps(startTime, previousStepVibratorOffTimeout, segmentsSkipped);
    }

    /**
     * Return the {@link VibrationStepConductor#nextVibrateStep} with same start and off timings
     * calculated from {@link #getVibratorOnDuration()}, jumping all played segments.
     *
     * <p>This method has same behavior as {@link #skipToNextSteps(int)} when the vibrator
     * result is non-positive, meaning the vibrator has either ignored or failed to turn on.
     */
    protected List<Step> nextSteps(int segmentsPlayed) {
        if (mVibratorOnResult <= 0) {
            // Vibration was not started, so just skip the played segments and keep timings.
            return skipToNextSteps(segmentsPlayed);
        }
        long nextStartTime = SystemClock.uptimeMillis() + mVibratorOnResult;
        long nextVibratorOffTimeout =
                nextStartTime + VibrationStepConductor.CALLBACKS_EXTRA_TIMEOUT;
        return nextSteps(nextStartTime, nextVibratorOffTimeout, segmentsPlayed);
    }

    /**
     * Return the {@link VibrationStepConductor#nextVibrateStep} with given start and off timings,
     * which might be calculated independently, jumping all played segments.
     *
     * <p>This should be used when the vibrator on/off state is not responsible for the steps
     * execution timings, e.g. while playing the vibrator amplitudes.
     */
    protected List<Step> nextSteps(long nextStartTime, long vibratorOffTimeout,
            int segmentsPlayed) {
        int nextSegmentIndex = segmentIndex + segmentsPlayed;
        int effectSize = effect.getSegments().size();
        int repeatIndex = effect.getRepeatIndex();
        if (nextSegmentIndex >= effectSize && repeatIndex >= 0) {
            // Count the loops that were played.
            int loopSize = effectSize - repeatIndex;
            nextSegmentIndex = repeatIndex + ((nextSegmentIndex - effectSize) % loopSize);
        }
        Step nextStep = conductor.nextVibrateStep(nextStartTime, controller, effect,
                nextSegmentIndex, vibratorOffTimeout);
        return nextStep == null ? VibrationStepConductor.EMPTY_STEP_LIST : Arrays.asList(nextStep);
    }
}
