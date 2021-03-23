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

import android.os.VibrationEffect;
import android.os.VibratorInfo;
import android.os.vibrator.RampSegment;
import android.os.vibrator.StepSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.util.MathUtils;
import android.util.Range;

import java.util.ArrayList;
import java.util.List;

/** Adapts a {@link VibrationEffect} to a specific device, taking into account its capabilities. */
final class DeviceVibrationEffectAdapter implements VibrationEffectModifier<VibratorInfo> {

    /**
     * Adapts a sequence of {@link VibrationEffectSegment} to device's absolute frequency values
     * and respective supported amplitudes.
     *
     * <p>This adapter preserves the segment count.
     */
    interface AmplitudeFrequencyAdapter {
        List<VibrationEffectSegment> apply(List<VibrationEffectSegment> segments,
                VibratorInfo info);
    }

    private final AmplitudeFrequencyAdapter mAmplitudeFrequencyAdapter;

    DeviceVibrationEffectAdapter() {
        this(new ClippingAmplitudeFrequencyAdapter());
    }

    DeviceVibrationEffectAdapter(AmplitudeFrequencyAdapter amplitudeFrequencyAdapter) {
        mAmplitudeFrequencyAdapter = amplitudeFrequencyAdapter;
    }

    @Override
    public VibrationEffect apply(VibrationEffect effect, VibratorInfo info) {
        if (!(effect instanceof VibrationEffect.Composed)) {
            return effect;
        }

        VibrationEffect.Composed composed = (VibrationEffect.Composed) effect;
        List<VibrationEffectSegment> mappedSegments = mAmplitudeFrequencyAdapter.apply(
                composed.getSegments(), info);

        // TODO(b/167947076): add ramp to step adapter once PWLE capability is introduced
        // TODO(b/167947076): add filter that removes unsupported primitives
        // TODO(b/167947076): add filter that replaces unsupported prebaked with fallback

        return new VibrationEffect.Composed(mappedSegments, composed.getRepeatIndex());
    }

    /**
     * Adapter that clips frequency values to {@link VibratorInfo#getFrequencyRange()} and
     * amplitude values to respective {@link VibratorInfo#getMaxAmplitude}.
     *
     * <p>Devices with no frequency control will collapse all frequencies to zero and leave
     * amplitudes unchanged.
     */
    private static final class ClippingAmplitudeFrequencyAdapter
            implements AmplitudeFrequencyAdapter {
        @Override
        public List<VibrationEffectSegment> apply(List<VibrationEffectSegment> segments,
                VibratorInfo info) {
            List<VibrationEffectSegment> result = new ArrayList<>();
            int segmentCount = segments.size();
            for (int i = 0; i < segmentCount; i++) {
                VibrationEffectSegment segment = segments.get(i);
                if (segment instanceof StepSegment) {
                    result.add(apply((StepSegment) segment, info));
                } else if (segment instanceof RampSegment) {
                    result.add(apply((RampSegment) segment, info));
                } else {
                    result.add(segment);
                }
            }
            return result;
        }

        private StepSegment apply(StepSegment segment, VibratorInfo info) {
            float clampedFrequency = info.getFrequencyRange().clamp(segment.getFrequency());
            return new StepSegment(
                    MathUtils.min(segment.getAmplitude(), info.getMaxAmplitude(clampedFrequency)),
                    info.getAbsoluteFrequency(clampedFrequency),
                    (int) segment.getDuration());
        }

        private RampSegment apply(RampSegment segment, VibratorInfo info) {
            Range<Float> frequencyRange = info.getFrequencyRange();
            float clampedStartFrequency = frequencyRange.clamp(segment.getStartFrequency());
            float clampedEndFrequency = frequencyRange.clamp(segment.getEndFrequency());
            return new RampSegment(
                    MathUtils.min(segment.getStartAmplitude(),
                            info.getMaxAmplitude(clampedStartFrequency)),
                    MathUtils.min(segment.getEndAmplitude(),
                            info.getMaxAmplitude(clampedEndFrequency)),
                    info.getAbsoluteFrequency(clampedStartFrequency),
                    info.getAbsoluteFrequency(clampedEndFrequency),
                    (int) segment.getDuration());
        }
    }
}
