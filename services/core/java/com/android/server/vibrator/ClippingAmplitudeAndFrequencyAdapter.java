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
import android.os.vibrator.StepSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.util.MathUtils;
import android.util.Range;

import java.util.List;

/**
 * Adapter that clips frequency values to the ones specified by the
 * {@link VibratorInfo.FrequencyProfile}.
 *
 * <p>Devices with no frequency control will collapse all frequencies to the resonant frequency and
 * leave amplitudes unchanged.
 */
final class ClippingAmplitudeAndFrequencyAdapter
        implements VibrationEffectAdapters.SegmentsAdapter<VibratorInfo> {

    @Override
    public int apply(List<VibrationEffectSegment> segments, int repeatIndex, VibratorInfo info) {
        int segmentCount = segments.size();
        for (int i = 0; i < segmentCount; i++) {
            VibrationEffectSegment segment = segments.get(i);
            if (segment instanceof StepSegment) {
                segments.set(i, apply((StepSegment) segment, info));
            } else if (segment instanceof RampSegment) {
                segments.set(i, apply((RampSegment) segment, info));
            }
        }
        return repeatIndex;
    }

    private StepSegment apply(StepSegment segment, VibratorInfo info) {
        float clampedFrequency = clampFrequency(info, segment.getFrequencyHz());
        return new StepSegment(
                clampAmplitude(info, clampedFrequency, segment.getAmplitude()),
                clampedFrequency,
                (int) segment.getDuration());
    }

    private RampSegment apply(RampSegment segment, VibratorInfo info) {
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
            return info.getResonantFrequencyHz();
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
