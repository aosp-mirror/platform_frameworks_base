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
final class ComposePrimitivesVibratorStep extends AbstractVibratorStep {

    ComposePrimitivesVibratorStep(VibrationStepConductor conductor, long startTime,
            VibratorController controller, VibrationEffect.Composed effect, int index,
            long previousStepVibratorOffTimeout) {
        // This step should wait for the last vibration to finish (with the timeout) and for the
        // intended step start time (to respect the effect delays).
        super(conductor, Math.max(startTime, previousStepVibratorOffTimeout), controller, effect,
                index, previousStepVibratorOffTimeout);
    }

    @Override
    public List<Step> play() {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "ComposePrimitivesStep");
        try {
            // Load the next PrimitiveSegments to create a single compose call to the vibrator,
            // limited to the vibrator composition maximum size.
            int limit = controller.getVibratorInfo().getCompositionSizeMax();
            int segmentCount = limit > 0
                    ? Math.min(effect.getSegments().size(), segmentIndex + limit)
                    : effect.getSegments().size();
            List<PrimitiveSegment> primitives = new ArrayList<>();
            for (int i = segmentIndex; i < segmentCount; i++) {
                VibrationEffectSegment segment = effect.getSegments().get(i);
                if (segment instanceof PrimitiveSegment) {
                    primitives.add((PrimitiveSegment) segment);
                } else {
                    break;
                }
            }

            if (primitives.isEmpty()) {
                Slog.w(VibrationThread.TAG, "Ignoring wrong segment for a ComposePrimitivesStep: "
                        + effect.getSegments().get(segmentIndex));
                return skipToNextSteps(/* segmentsSkipped= */ 1);
            }

            if (VibrationThread.DEBUG) {
                Slog.d(VibrationThread.TAG, "Compose " + primitives + " primitives on vibrator "
                        + controller.getVibratorInfo().getId());
            }
            mVibratorOnResult = controller.on(
                    primitives.toArray(new PrimitiveSegment[primitives.size()]),
                    getVibration().id);

            return nextSteps(/* segmentsPlayed= */ primitives.size());
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }
}
