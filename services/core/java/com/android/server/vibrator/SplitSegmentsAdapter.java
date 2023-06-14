/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.hardware.vibrator.IVibrator;
import android.os.VibratorInfo;
import android.os.vibrator.RampSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.util.MathUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter that splits segments with longer duration than the device capabilities.
 *
 * <p>This transformation replaces large {@link RampSegment} entries by a sequence of smaller
 * ramp segments that starts and ends at the same amplitudes/frequencies, interpolating the
 * intermediate values.
 *
 * <p>The segments will not be changed if the device doesn't have
 * {@link IVibrator#CAP_COMPOSE_PWLE_EFFECTS}.
 */
final class SplitSegmentsAdapter implements VibrationSegmentsAdapter {

    @Override
    public int adaptToVibrator(VibratorInfo info, List<VibrationEffectSegment> segments,
            int repeatIndex) {
        if (!info.hasCapability(IVibrator.CAP_COMPOSE_PWLE_EFFECTS)) {
            // The vibrator does not have PWLE capability, so keep the segments unchanged.
            return repeatIndex;
        }
        int maxRampDuration = info.getPwlePrimitiveDurationMax();
        if (maxRampDuration <= 0) {
            // No limit set to PWLE primitive duration.
            return repeatIndex;
        }

        int segmentCount = segments.size();
        for (int i = 0; i < segmentCount; i++) {
            if (!(segments.get(i) instanceof RampSegment)) {
                continue;
            }
            RampSegment ramp = (RampSegment) segments.get(i);
            int splits = ((int) ramp.getDuration() + maxRampDuration - 1) / maxRampDuration;
            if (splits <= 1) {
                continue;
            }
            segments.remove(i);
            segments.addAll(i, splitRampSegment(info, ramp, splits));
            int addedSegments = splits - 1;
            if (repeatIndex > i) {
                repeatIndex += addedSegments;
            }
            i += addedSegments;
            segmentCount += addedSegments;
        }

        return repeatIndex;
    }

    private static List<RampSegment> splitRampSegment(VibratorInfo info, RampSegment ramp,
            int splits) {
        List<RampSegment> ramps = new ArrayList<>(splits);
        // Fill zero frequency values with the device resonant frequency before interpolating.
        float startFrequencyHz = fillEmptyFrequency(info, ramp.getStartFrequencyHz());
        float endFrequencyHz = fillEmptyFrequency(info, ramp.getEndFrequencyHz());
        long splitDuration = ramp.getDuration() / splits;
        float previousAmplitude = ramp.getStartAmplitude();
        float previousFrequencyHz = startFrequencyHz;
        long accumulatedDuration = 0;

        for (int i = 1; i < splits; i++) {
            accumulatedDuration += splitDuration;
            float durationRatio = (float) accumulatedDuration / ramp.getDuration();
            float interpolatedFrequency =
                    MathUtils.lerp(startFrequencyHz, endFrequencyHz, durationRatio);
            float interpolatedAmplitude =
                    MathUtils.lerp(ramp.getStartAmplitude(), ramp.getEndAmplitude(), durationRatio);
            RampSegment rampSplit = new RampSegment(
                    previousAmplitude, interpolatedAmplitude,
                    previousFrequencyHz, interpolatedFrequency,
                    (int) splitDuration);
            ramps.add(rampSplit);
            previousAmplitude = rampSplit.getEndAmplitude();
            previousFrequencyHz = rampSplit.getEndFrequencyHz();
        }

        ramps.add(new RampSegment(previousAmplitude, ramp.getEndAmplitude(), previousFrequencyHz,
                endFrequencyHz, (int) (ramp.getDuration() - accumulatedDuration)));

        return ramps;
    }

    private static float fillEmptyFrequency(VibratorInfo info, float frequencyHz) {
        if (Float.isNaN(info.getResonantFrequencyHz())) {
            return frequencyHz;
        }
        return frequencyHz == 0 ? info.getResonantFrequencyHz() : frequencyHz;
    }
}
