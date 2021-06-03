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
 * <p>This leaves the list unchanged if the device do not have compose PWLE capability.
 */
final class StepToRampAdapter implements VibrationEffectAdapters.SegmentsAdapter<VibratorInfo> {
    @Override
    public int apply(List<VibrationEffectSegment> segments, int repeatIndex,
            VibratorInfo info) {
        if (!info.hasCapability(IVibrator.CAP_COMPOSE_PWLE_EFFECTS)) {
            // The vibrator do not have PWLE capability, so keep the segments unchanged.
            return repeatIndex;
        }
        int segmentCount = segments.size();
        // Convert steps that require frequency control to ramps.
        for (int i = 0; i < segmentCount; i++) {
            VibrationEffectSegment segment = segments.get(i);
            if ((segment instanceof StepSegment)
                    && ((StepSegment) segment).getFrequency() != 0) {
                segments.set(i, apply((StepSegment) segment));
            }
        }
        // Convert steps that are next to ramps to also become ramps, so they can be composed
        // together in the same PWLE waveform.
        for (int i = 1; i < segmentCount; i++) {
            if (segments.get(i) instanceof RampSegment) {
                for (int j = i - 1; j >= 0 && (segments.get(j) instanceof StepSegment); j--) {
                    segments.set(j, apply((StepSegment) segments.get(j)));
                }
            }
        }
        return repeatIndex;
    }

    private RampSegment apply(StepSegment segment) {
        return new RampSegment(segment.getAmplitude(), segment.getAmplitude(),
                segment.getFrequency(), segment.getFrequency(), (int) segment.getDuration());
    }
}
