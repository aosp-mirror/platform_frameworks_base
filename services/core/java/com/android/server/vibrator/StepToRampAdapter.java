/*
 * Copyright (C) 2021 The Android Open Source Project
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
import android.os.vibrator.StepSegment;
import android.os.vibrator.VibrationEffectSegment;

import java.util.List;

/**
 * Adapter that converts step segments that should be handled as PWLEs to ramp segments.
 *
 * <p>Each replaced step will be represented by a ramp with same start and end
 * amplitudes/frequencies, which can then be converted to PWLE compositions.
 *
 * <p>The segments will not be changed if the device doesn't have
 * {@link IVibrator#CAP_COMPOSE_PWLE_EFFECTS}.
 */
final class StepToRampAdapter implements VibrationSegmentsAdapter {

    @Override
    public int adaptToVibrator(VibratorInfo info, List<VibrationEffectSegment> segments,
            int repeatIndex) {
        if (!info.hasCapability(IVibrator.CAP_COMPOSE_PWLE_EFFECTS)) {
            // The vibrator does not have PWLE capability, so keep the segments unchanged.
            return repeatIndex;
        }
        int segmentCount = segments.size();
        // Convert steps that require frequency control to ramps.
        for (int i = 0; i < segmentCount; i++) {
            VibrationEffectSegment segment = segments.get(i);
            if (isStep(segment) && ((StepSegment) segment).getFrequencyHz() != 0) {
                segments.set(i, convertStepToRamp(info, (StepSegment) segment));
            }
        }
        // Convert steps that are next to ramps to also become ramps, so they can be composed
        // together in the same PWLE waveform.
        for (int i = 0; i < segmentCount; i++) {
            if (segments.get(i) instanceof RampSegment) {
                for (int j = i - 1; j >= 0 && isStep(segments.get(j)); j--) {
                    segments.set(j, convertStepToRamp(info, (StepSegment) segments.get(j)));
                }
                for (int j = i + 1; j < segmentCount && isStep(segments.get(j)); j++) {
                    segments.set(j, convertStepToRamp(info, (StepSegment) segments.get(j)));
                }
            }
        }
        return repeatIndex;
    }

    private static RampSegment convertStepToRamp(VibratorInfo info, StepSegment segment) {
        float frequencyHz = fillEmptyFrequency(info, segment.getFrequencyHz());
        return new RampSegment(segment.getAmplitude(), segment.getAmplitude(),
                frequencyHz, frequencyHz, (int) segment.getDuration());
    }

    private static boolean isStep(VibrationEffectSegment segment) {
        return segment instanceof StepSegment;
    }

    private static float fillEmptyFrequency(VibratorInfo info, float frequencyHz) {
        if (Float.isNaN(info.getResonantFrequencyHz())) {
            return frequencyHz;
        }
        return frequencyHz == 0 ? info.getResonantFrequencyHz() : frequencyHz;
    }
}
