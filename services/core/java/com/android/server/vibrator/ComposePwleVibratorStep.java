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
import android.os.vibrator.RampSegment;
import android.os.vibrator.StepSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.util.Slog;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a step to turn the vibrator on using a composition of PWLE segments.
 *
 * <p>This step will use the maximum supported number of consecutive segments of type
 * {@link StepSegment} or {@link RampSegment} starting at the current index.
 */
final class ComposePwleVibratorStep extends AbstractVibratorStep {

    ComposePwleVibratorStep(VibrationStepConductor conductor, long startTime,
            VibratorController controller, VibrationEffect.Composed effect, int index,
            long previousStepVibratorOffTimeout) {
        // This step should wait for the last vibration to finish (with the timeout) and for the
        // intended step start time (to respect the effect delays).
        super(conductor, Math.max(startTime, previousStepVibratorOffTimeout), controller, effect,
                index, previousStepVibratorOffTimeout);
    }

    @Override
    public List<Step> play() {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "ComposePwleStep");
        try {
            // Load the next RampSegments to create a single composePwle call to the vibrator,
            // limited to the vibrator PWLE maximum size.
            int limit = controller.getVibratorInfo().getPwleSizeMax();
            int segmentCount = limit > 0
                    ? Math.min(effect.getSegments().size(), segmentIndex + limit)
                    : effect.getSegments().size();
            List<RampSegment> pwles = new ArrayList<>();
            for (int i = segmentIndex; i < segmentCount; i++) {
                VibrationEffectSegment segment = effect.getSegments().get(i);
                if (segment instanceof RampSegment) {
                    pwles.add((RampSegment) segment);
                } else {
                    break;
                }
            }

            if (pwles.isEmpty()) {
                Slog.w(VibrationThread.TAG, "Ignoring wrong segment for a ComposePwleStep: "
                        + effect.getSegments().get(segmentIndex));
                return skipToNextSteps(/* segmentsSkipped= */ 1);
            }

            if (VibrationThread.DEBUG) {
                Slog.d(VibrationThread.TAG, "Compose " + pwles + " PWLEs on vibrator "
                        + controller.getVibratorInfo().getId());
            }
            mVibratorOnResult = controller.on(pwles.toArray(new RampSegment[pwles.size()]),
                    getVibration().id);

            return nextSteps(/* segmentsPlayed= */ pwles.size());
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }
}
