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

    long mVibratorOnResult;
    long mPendingVibratorOffDeadline;
    boolean mVibratorCompleteCallbackReceived;

    /**
     * @param conductor          The VibrationStepConductor for these steps.
     * @param startTime          The time to schedule this step in the
     *                           {@link VibrationStepConductor}.
     * @param controller         The vibrator that is playing the effect.
     * @param effect             The effect being played in this step.
     * @param index              The index of the next segment to be played by this step
     * @param pendingVibratorOffDeadline The time the vibrator is expected to complete any
     *                           previous vibration and turn off. This is used to allow this step to
     *                           be triggered when the completion callback is received, and can
     *                           be used to play effects back-to-back.
     */
    AbstractVibratorStep(VibrationStepConductor conductor, long startTime,
            VibratorController controller, VibrationEffect.Composed effect, int index,
            long pendingVibratorOffDeadline) {
        super(conductor, startTime);
        this.controller = controller;
        this.effect = effect;
        this.segmentIndex = index;
        mPendingVibratorOffDeadline = pendingVibratorOffDeadline;
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
        if (getVibratorId() != vibratorId) {
            return false;
        }

        // Only activate this step if a timeout was set to wait for the vibration to complete,
        // otherwise we are waiting for the correct time to play the next step.
        boolean shouldAcceptCallback = mPendingVibratorOffDeadline > SystemClock.uptimeMillis();
        if (VibrationThread.DEBUG) {
            Slog.d(VibrationThread.TAG,
                    "Received completion callback from " + vibratorId
                            + ", accepted = " + shouldAcceptCallback);
        }

        // The callback indicates this vibrator has stopped, reset the timeout.
        mPendingVibratorOffDeadline = 0;
        mVibratorCompleteCallbackReceived = true;
        return shouldAcceptCallback;
    }

    @Override
    public List<Step> cancel() {
        return Arrays.asList(new CompleteEffectVibratorStep(conductor, SystemClock.uptimeMillis(),
                /* cancelled= */ true, controller, mPendingVibratorOffDeadline));
    }

    @Override
    public void cancelImmediately() {
        if (mPendingVibratorOffDeadline > SystemClock.uptimeMillis()) {
            // Vibrator might be running from previous steps, so turn it off while canceling.
            stopVibrating();
        }
    }

    protected long handleVibratorOnResult(long vibratorOnResult) {
        mVibratorOnResult = vibratorOnResult;
        if (VibrationThread.DEBUG) {
            Slog.d(VibrationThread.TAG,
                    "Turned on vibrator " + getVibratorId() + ", result = " + mVibratorOnResult);
        }
        if (mVibratorOnResult > 0) {
            // Vibrator was turned on by this step, with vibratorOnResult as the duration.
            // Set an extra timeout to wait for the vibrator completion callback.
            mPendingVibratorOffDeadline = SystemClock.uptimeMillis() + mVibratorOnResult
                    + VibrationStepConductor.CALLBACKS_EXTRA_TIMEOUT;
        } else {
            // Vibrator does not support the request or failed to turn on, reset callback deadline.
            mPendingVibratorOffDeadline = 0;
        }
        return mVibratorOnResult;
    }

    protected void stopVibrating() {
        if (VibrationThread.DEBUG) {
            Slog.d(VibrationThread.TAG,
                    "Turning off vibrator " + getVibratorId());
        }
        controller.off();
        getVibration().stats().reportVibratorOff();
        mPendingVibratorOffDeadline = 0;
    }

    protected void changeAmplitude(float amplitude) {
        if (VibrationThread.DEBUG) {
            Slog.d(VibrationThread.TAG,
                    "Amplitude changed on vibrator " + getVibratorId() + " to " + amplitude);
        }
        controller.setAmplitude(amplitude);
        getVibration().stats().reportSetAmplitude();
    }

    /**
     * Return the {@link VibrationStepConductor#nextVibrateStep} with start and off timings
     * calculated from {@link #getVibratorOnDuration()} based on the current
     * {@link SystemClock#uptimeMillis()} and jumping all played segments from the effect.
     */
    protected List<Step> nextSteps(int segmentsPlayed) {
        // Schedule next steps to run right away.
        long nextStartTime = SystemClock.uptimeMillis();
        if (mVibratorOnResult > 0) {
            // Vibrator was turned on by this step, with mVibratorOnResult as the duration.
            // Schedule next steps for right after the vibration finishes.
            nextStartTime += mVibratorOnResult;
        }
        return nextSteps(nextStartTime, segmentsPlayed);
    }

    /**
     * Return the {@link VibrationStepConductor#nextVibrateStep} with given start time,
     * which might be calculated independently, and jumping all played segments from the effect.
     *
     * <p>This should be used when the vibrator on/off state is not responsible for the step
     * execution timing, e.g. while playing the vibrator amplitudes.
     */
    protected List<Step> nextSteps(long nextStartTime, int segmentsPlayed) {
        int nextSegmentIndex = segmentIndex + segmentsPlayed;
        int effectSize = effect.getSegments().size();
        int repeatIndex = effect.getRepeatIndex();
        if (nextSegmentIndex >= effectSize && repeatIndex >= 0) {
            // Count the loops that were played.
            int loopSize = effectSize - repeatIndex;
            int loopSegmentsPlayed = nextSegmentIndex - repeatIndex;
            getVibration().stats().reportRepetition(loopSegmentsPlayed / loopSize);
            nextSegmentIndex = repeatIndex + ((nextSegmentIndex - effectSize) % loopSize);
        }
        Step nextStep = conductor.nextVibrateStep(nextStartTime, controller, effect,
                nextSegmentIndex, mPendingVibratorOffDeadline);
        return nextStep == null ? VibrationStepConductor.EMPTY_STEP_LIST : Arrays.asList(nextStep);
    }
}
