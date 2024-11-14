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

import static android.os.VibrationEffect.Composition.DELAY_TYPE_PAUSE;
import static android.os.VibrationEffect.Composition.DELAY_TYPE_RELATIVE_START_OFFSET;

import android.os.VibrationEffect.Composition.DelayType;
import android.os.VibratorInfo;
import android.os.vibrator.Flags;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.VibrationEffectSegment;

import java.util.List;

/**
 * Adapter that converts between {@link DelayType} and the HAL supported pause delays.
 *
 * <p>Primitives that overlap due to the delays being shorter than the previous segments will be
 * dropped from the effect here. Relative timings will still use the dropped primitives to preserve
 * the design intention.
 */
final class PrimitiveDelayAdapter implements VibrationSegmentsAdapter {

    PrimitiveDelayAdapter() {
    }

    @Override
    public int adaptToVibrator(VibratorInfo info, List<VibrationEffectSegment> segments,
            int repeatIndex) {
        if (!Flags.primitiveCompositionAbsoluteDelay()) {
            return repeatIndex;
        }
        int previousStartOffset = 0;
        int segmentCount = segments.size();
        for (int i = 0; i < segmentCount; i++) {
            VibrationEffectSegment segment = segments.get(i);
            if (i == repeatIndex) {
                // Crossed the repeat line, reset start offset so repeating block is independent.
                previousStartOffset = 0;
            }

            if (!(segment instanceof PrimitiveSegment primitive)
                    || (primitive.getDelayType() == DELAY_TYPE_PAUSE)) {
                // Effect will play normally, keep track of its start offset.
                previousStartOffset = -calculateEffectDuration(info, segment);
                continue;
            }

            int pause = calculatePause(primitive, previousStartOffset);
            if (pause >= 0) {
                segments.set(i, toPrimitiveWithPause(primitive, pause));
                // Delay will be ignored from this calculation.
                previousStartOffset = -calculateEffectDuration(info, primitive);
            } else {
                // Primitive overlapping with previous segment, ignore it.
                segments.remove(i);
                if (repeatIndex > i) {
                    repeatIndex--;
                }
                segmentCount--;
                i--;

                // Keep the intended start time for future calculations. Here is an example:
                // 10 20 30 40 50 60 70 | Timeline (D = relative delay, E = effect duration)
                //  D  E  E  E  E       | D=10, E=40 | offset = 0   | pause = 10  | OK
                //     D  E  E          | D=10, E=20 | offset = -40 | pause = -30 | IGNORED
                //        D  E  E       | D=10, E=20 | offset = -30 | pause = -20 | IGNORED
                //           D  E  E    | D=10, E=20 | offset = -20 | pause = -10 | IGNORED
                //              D  E  E | D=10, E=20 | offset = -10 | pause = 0   | OK
                previousStartOffset = pause;
            }
        }
        return repeatIndex;
    }

    private static int calculatePause(PrimitiveSegment primitive, int previousStartOffset) {
        if (primitive.getDelayType() == DELAY_TYPE_RELATIVE_START_OFFSET) {
            return previousStartOffset + primitive.getDelay();
        }
        return primitive.getDelay();
    }

    private static int calculateEffectDuration(VibratorInfo info, VibrationEffectSegment segment) {
        long segmentDuration = segment.getDuration(info);
        if (segmentDuration < 0) {
            // Duration unknown, default to zero.
            return 0;
        }
        int effectDuration = (int) segmentDuration;
        if (segment instanceof PrimitiveSegment primitive) {
            // Ignore primitive delays from effect duration.
            effectDuration -= primitive.getDelay();
        }
        return effectDuration;
    }

    private static PrimitiveSegment toPrimitiveWithPause(PrimitiveSegment primitive, int pause) {
        return new PrimitiveSegment(primitive.getPrimitiveId(), primitive.getScale(),
                pause, DELAY_TYPE_PAUSE);
    }
}
