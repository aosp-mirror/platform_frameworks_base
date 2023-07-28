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

import android.os.VibratorInfo;
import android.os.vibrator.RampSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.util.MathUtils;
import android.util.Range;

import java.util.List;

/**
 * Adapter that clips frequency values to the supported range specified by
 * {@link VibratorInfo.FrequencyProfile}, then clips amplitude values to the max supported one at
 * each frequency.
 *
 * <p>The {@link VibratorInfo.FrequencyProfile} is only applicable to PWLE compositions. This
 * adapter is only applied to {@link RampSegment} and all other segments will remain unchanged.
 */
final class ClippingAmplitudeAndFrequencyAdapter implements VibrationSegmentsAdapter {

    @Override
    public int adaptToVibrator(VibratorInfo info, List<VibrationEffectSegment> segments,
            int repeatIndex) {
        int segmentCount = segments.size();
        for (int i = 0; i < segmentCount; i++) {
            VibrationEffectSegment segment = segments.get(i);
            if (segment instanceof RampSegment) {
                segments.set(i, adaptToVibrator(info, (RampSegment) segment));
            }
        }
        return repeatIndex;
    }

    private RampSegment adaptToVibrator(VibratorInfo info, RampSegment segment) {
        float clampedStartFrequency = clampFrequency(info, segment.getStartFrequencyHz());
        float clampedEndFrequency = clampFrequency(info, segment.getEndFrequencyHz());
        return new RampSegment(
                clampAmplitude(info, clampedStartFrequency, segment.getStartAmplitude()),
                clampAmplitude(info, clampedEndFrequency, segment.getEndAmplitude()),
                clampedStartFrequency,
                clampedEndFrequency,
                (int) segment.getDuration());
    }

    private float clampFrequency(VibratorInfo info, float frequencyHz) {
        Range<Float> frequencyRangeHz = info.getFrequencyProfile().getFrequencyRangeHz();
        if (frequencyHz == 0 || frequencyRangeHz == null)  {
            return Float.isNaN(info.getResonantFrequencyHz()) ? 0 : info.getResonantFrequencyHz();
        }
        return frequencyRangeHz.clamp(frequencyHz);
    }

    private float clampAmplitude(VibratorInfo info, float frequencyHz, float amplitude) {
        VibratorInfo.FrequencyProfile mapping = info.getFrequencyProfile();
        if (mapping.isEmpty()) {
            // No frequency mapping was specified so leave amplitude unchanged.
            // The frequency will be clamped to the device's resonant frequency.
            return amplitude;
        }
        return MathUtils.min(amplitude, mapping.getMaxAmplitude(frequencyHz));
    }
}
