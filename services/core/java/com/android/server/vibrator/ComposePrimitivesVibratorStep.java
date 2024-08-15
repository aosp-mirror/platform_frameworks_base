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
import android.os.Trace;
import android.os.VibrationEffect;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.util.Slog;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a step to turn the vibrator on using a composition of primitives.
 *
 * <p>This step will use the maximum supported number of consecutive segments of type
 * {@link PrimitiveSegment} starting at the current index.
 */
final class ComposePrimitivesVibratorStep extends AbstractComposedVibratorStep {
    /**
     * Default limit to the number of primitives in a composition, if none is defined by the HAL,
     * to prevent repeating effects from generating an infinite list.
     */
    private static final int DEFAULT_COMPOSITION_SIZE_LIMIT = 100;

    ComposePrimitivesVibratorStep(VibrationStepConductor conductor, long startTime,
            VibratorController controller, VibrationEffect.Composed effect, int index,
            long pendingVibratorOffDeadline) {
        // This step should wait for the last vibration to finish (with the timeout) and for the
        // intended step start time (to respect the effect delays).
        super(conductor, Math.max(startTime, pendingVibratorOffDeadline), controller, effect,
                index, pendingVibratorOffDeadline);
    }

    @NonNull
    @Override
    public List<Step> play() {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "ComposePrimitivesStep");
        try {
            // Load the next PrimitiveSegments to create a single compose call to the vibrator,
            // limited to the vibrator composition maximum size.
            int limit = controller.getVibratorInfo().getCompositionSizeMax();
            List<PrimitiveSegment> primitives = unrollPrimitiveSegments(effect, segmentIndex,
                    limit > 0 ? limit : DEFAULT_COMPOSITION_SIZE_LIMIT);

            if (primitives.isEmpty()) {
                Slog.w(VibrationThread.TAG, "Ignoring wrong segment for a ComposePrimitivesStep: "
                        + effect.getSegments().get(segmentIndex));
                // Skip this step and play the next one right away.
                return nextSteps(/* segmentsPlayed= */ 1);
            }

            if (VibrationThread.DEBUG) {
                Slog.d(VibrationThread.TAG, "Compose " + primitives + " primitives on vibrator "
                        + getVibratorId());
            }

            PrimitiveSegment[] primitivesArray =
                    primitives.toArray(new PrimitiveSegment[primitives.size()]);
            long vibratorOnResult = controller.on(primitivesArray, getVibration().id);
            handleVibratorOnResult(vibratorOnResult);
            getVibration().stats.reportComposePrimitives(vibratorOnResult, primitivesArray);

            // The next start and off times will be calculated from mVibratorOnResult.
            return nextSteps(/* segmentsPlayed= */ primitives.size());
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    /**
     * Get the primitive segments to be played by this step as a single composition, starting at
     * {@code startIndex} until:
     *
     * <ol>
     *     <li>There are no more segments in the effect;
     *     <li>The first non-primitive segment is found;
     *     <li>The given limit to the composition size is reached.
     * </ol>
     *
     * <p>If the effect is repeating then this method will generate the largest composition within
     * given limit.
     */
    private List<PrimitiveSegment> unrollPrimitiveSegments(VibrationEffect.Composed effect,
            int startIndex, int limit) {
        List<PrimitiveSegment> segments = new ArrayList<>(limit);
        int segmentCount = effect.getSegments().size();
        int repeatIndex = effect.getRepeatIndex();

        for (int i = startIndex; segments.size() < limit; i++) {
            if (i == segmentCount) {
                if (repeatIndex >= 0) {
                    i = repeatIndex;
                } else {
                    // Non-repeating effect, stop collecting primitives.
                    break;
                }
            }
            VibrationEffectSegment segment = effect.getSegments().get(i);
            if (segment instanceof PrimitiveSegment) {
                segments.add((PrimitiveSegment) segment);
            } else {
                // First non-primitive segment, stop collecting primitives.
                break;
            }
        }

        return segments;
    }
}
