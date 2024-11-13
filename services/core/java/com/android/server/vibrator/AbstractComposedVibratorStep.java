/*
 * Copyright (C) 2024 The Android Open Source Project
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

import java.util.List;

/**
 * Represent a step on a single vibrator that plays one or more segments from a
 * {@link VibrationEffect.Composed} effect.
 */
abstract class AbstractComposedVibratorStep extends AbstractVibratorStep {
    public final VibrationEffect.Composed effect;
    public final int segmentIndex;

    /**
     * @param conductor          The {@link VibrationStepConductor} for these steps.
     * @param startTime          The time to schedule this step in the conductor.
     * @param controller         The vibrator that is playing the effect.
     * @param effect             The effect being played in this step.
     * @param index              The index of the next segment to be played by this step
     * @param pendingVibratorOffDeadline The time the vibrator is expected to complete any
     *                           previous vibration and turn off. This is used to allow this step to
     *                           be triggered when the completion callback is received, and can
     *                           be used to play effects back-to-back.
     */
    AbstractComposedVibratorStep(VibrationStepConductor conductor, long startTime,
            VibratorController controller, VibrationEffect.Composed effect, int index,
            long pendingVibratorOffDeadline) {
        super(conductor, startTime, controller, pendingVibratorOffDeadline);
        this.effect = effect;
        this.segmentIndex = index;
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
            getVibration().stats.reportRepetition(loopSegmentsPlayed / loopSize);
            nextSegmentIndex = repeatIndex + ((nextSegmentIndex - effectSize) % loopSize);
        }
        Step nextStep = conductor.nextVibrateStep(nextStartTime, controller, effect,
                nextSegmentIndex, mPendingVibratorOffDeadline);
        return List.of(nextStep);
    }
}
