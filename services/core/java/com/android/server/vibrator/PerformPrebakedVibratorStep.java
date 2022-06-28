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

import android.os.Trace;
import android.os.VibrationEffect;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.util.Slog;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a step to turn the vibrator on with a single prebaked effect.
 *
 * <p>This step automatically falls back by replacing the prebaked segment with
 * {@link VibrationSettings#getFallbackEffect(int)}, if available.
 */
final class PerformPrebakedVibratorStep extends AbstractVibratorStep {

    PerformPrebakedVibratorStep(VibrationStepConductor conductor, long startTime,
            VibratorController controller, VibrationEffect.Composed effect, int index,
            long previousStepVibratorOffTimeout) {
        // This step should wait for the last vibration to finish (with the timeout) and for the
        // intended step start time (to respect the effect delays).
        super(conductor, Math.max(startTime, previousStepVibratorOffTimeout), controller, effect,
                index, previousStepVibratorOffTimeout);
    }

    @Override
    public List<Step> play() {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "PerformPrebakedVibratorStep");
        try {
            VibrationEffectSegment segment = effect.getSegments().get(segmentIndex);
            if (!(segment instanceof PrebakedSegment)) {
                Slog.w(VibrationThread.TAG, "Ignoring wrong segment for a "
                        + "PerformPrebakedVibratorStep: " + segment);
                return skipToNextSteps(/* segmentsSkipped= */ 1);
            }

            PrebakedSegment prebaked = (PrebakedSegment) segment;
            if (VibrationThread.DEBUG) {
                Slog.d(VibrationThread.TAG, "Perform " + VibrationEffect.effectIdToString(
                        prebaked.getEffectId()) + " on vibrator "
                        + controller.getVibratorInfo().getId());
            }

            VibrationEffect fallback = getVibration().getFallback(prebaked.getEffectId());
            mVibratorOnResult = controller.on(prebaked, getVibration().id);

            if (mVibratorOnResult == 0 && prebaked.shouldFallback()
                    && (fallback instanceof VibrationEffect.Composed)) {
                if (VibrationThread.DEBUG) {
                    Slog.d(VibrationThread.TAG, "Playing fallback for effect "
                            + VibrationEffect.effectIdToString(prebaked.getEffectId()));
                }
                AbstractVibratorStep fallbackStep = conductor.nextVibrateStep(startTime, controller,
                        replaceCurrentSegment((VibrationEffect.Composed) fallback),
                        segmentIndex, previousStepVibratorOffTimeout);
                List<Step> fallbackResult = fallbackStep.play();
                // Update the result with the fallback result so this step is seamlessly
                // replaced by the fallback to any outer application of this.
                mVibratorOnResult = fallbackStep.getVibratorOnDuration();
                return fallbackResult;
            }

            return nextSteps(/* segmentsPlayed= */ 1);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    /**
     * Replace segment at {@link #segmentIndex} in {@link #effect} with given fallback segments.
     *
     * @return a copy of {@link #effect} with replaced segment.
     */
    private VibrationEffect.Composed replaceCurrentSegment(VibrationEffect.Composed fallback) {
        List<VibrationEffectSegment> newSegments = new ArrayList<>(effect.getSegments());
        int newRepeatIndex = effect.getRepeatIndex();
        newSegments.remove(segmentIndex);
        newSegments.addAll(segmentIndex, fallback.getSegments());
        if (segmentIndex < effect.getRepeatIndex()) {
            newRepeatIndex += fallback.getSegments().size() - 1;
        }
        return new VibrationEffect.Composed(newSegments, newRepeatIndex);
    }
}
