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

import android.hardware.vibrator.IVibrator;
import android.os.VibratorInfo;
import android.os.vibrator.PwleSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.util.MathUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter that splits Pwle segments with longer duration than the device capabilities.
 *
 * <p>This transformation replaces large {@link android.os.vibrator.PwleSegment} entries by a
 * sequence of smaller segments that starts and ends at the same amplitudes/frequencies,
 * interpolating the intermediate values.
 *
 * <p>The segments will not be changed if the device doesn't have
 * {@link IVibrator#CAP_COMPOSE_PWLE_EFFECTS_V2}.
 */
final class SplitPwleSegmentsAdapter implements VibrationSegmentsAdapter {

    @Override
    public int adaptToVibrator(VibratorInfo info, List<VibrationEffectSegment> segments,
            int repeatIndex) {
        if (!info.hasCapability(IVibrator.CAP_COMPOSE_PWLE_EFFECTS_V2)) {
            // The vibrator does not have PWLE v2 capability, so keep the segments unchanged.
            return repeatIndex;
        }
        int maxPwleDuration = (int) info.getMaxEnvelopeEffectDurationMillis();
        if (maxPwleDuration <= 0) {
            // No limit set to PWLE primitive duration.
            return repeatIndex;
        }

        int segmentCount = segments.size();
        for (int i = 0; i < segmentCount; i++) {
            if (!(segments.get(i) instanceof PwleSegment pwleSegment)) {
                continue;
            }
            int splits = ((int) pwleSegment.getDuration() + maxPwleDuration - 1) / maxPwleDuration;
            if (splits <= 1) {
                continue;
            }
            segments.remove(i);
            segments.addAll(i, splitPwleSegment(pwleSegment, splits));
            int addedSegments = splits - 1;
            if (repeatIndex > i) {
                repeatIndex += addedSegments;
            }
            i += addedSegments;
            segmentCount += addedSegments;
        }

        return repeatIndex;
    }

    private static List<PwleSegment> splitPwleSegment(PwleSegment pwleSegment,
            int splits) {
        List<PwleSegment> pwleSegments = new ArrayList<>(splits);
        float startFrequencyHz = pwleSegment.getStartFrequencyHz();
        float endFrequencyHz = pwleSegment.getEndFrequencyHz();
        long splitDuration = pwleSegment.getDuration() / splits;
        float previousAmplitude = pwleSegment.getStartAmplitude();
        float previousFrequencyHz = startFrequencyHz;
        long accumulatedDuration = 0;

        for (int i = 1; i < splits; i++) {
            accumulatedDuration += splitDuration;
            float durationRatio = (float) accumulatedDuration / pwleSegment.getDuration();
            float interpolatedFrequency =
                    MathUtils.lerp(startFrequencyHz, endFrequencyHz, durationRatio);
            float interpolatedAmplitude = MathUtils.lerp(pwleSegment.getStartAmplitude(),
                    pwleSegment.getEndAmplitude(), durationRatio);
            PwleSegment pwleSplit = new PwleSegment(
                    previousAmplitude, interpolatedAmplitude,
                    previousFrequencyHz, interpolatedFrequency,
                    (int) splitDuration);
            pwleSegments.add(pwleSplit);
            previousAmplitude = pwleSplit.getEndAmplitude();
            previousFrequencyHz = pwleSplit.getEndFrequencyHz();
        }

        pwleSegments.add(
                new PwleSegment(previousAmplitude, pwleSegment.getEndAmplitude(),
                        previousFrequencyHz, endFrequencyHz,
                        (int) (pwleSegment.getDuration() - accumulatedDuration)));

        return pwleSegments;
    }
}
