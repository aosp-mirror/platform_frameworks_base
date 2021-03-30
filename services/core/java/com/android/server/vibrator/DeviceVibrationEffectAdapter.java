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

    /** Adapts a sequence of {@link VibrationEffectSegment} to device's capabilities. */
    interface SegmentsAdapter {

        /**
         * Modifies the given segments list by adding/removing segments to it based on the
         * device capabilities specified by given {@link VibratorInfo}.
         *
         * @param segments    List of {@link VibrationEffectSegment} to be adapter to the device.
         * @param repeatIndex Repeat index on the current segment list.
         * @param info        The device vibrator info that the segments must be adapted to.
         * @return The new repeat index on the modifies list.
         */
        int apply(List<VibrationEffectSegment> segments, int repeatIndex, VibratorInfo info);
    }

    private final SegmentsAdapter mAmplitudeFrequencyAdapter;
    private final SegmentsAdapter mStepToRampAdapter;

    DeviceVibrationEffectAdapter() {
        this(new ClippingAmplitudeFrequencyAdapter());
    }

    DeviceVibrationEffectAdapter(SegmentsAdapter amplitudeFrequencyAdapter) {
        mAmplitudeFrequencyAdapter = amplitudeFrequencyAdapter;
        mStepToRampAdapter = new StepToRampAdapter();
    }

    @Override
    public VibrationEffect apply(VibrationEffect effect, VibratorInfo info) {
        if (!(effect instanceof VibrationEffect.Composed)) {
            return effect;
        }

        VibrationEffect.Composed composed = (VibrationEffect.Composed) effect;
        List<VibrationEffectSegment> newSegments = new ArrayList<>(composed.getSegments());
        int newRepeatIndex = composed.getRepeatIndex();

        // Maps steps that should be handled by PWLE to ramps.
        // This should be done before frequency is converted from relative to absolute values.
        newRepeatIndex = mStepToRampAdapter.apply(newSegments, newRepeatIndex, info);

        // Adapt amplitude and frequency values to device supported ones, converting frequency
        // to absolute values in Hertz.
        newRepeatIndex = mAmplitudeFrequencyAdapter.apply(newSegments, newRepeatIndex, info);

        // TODO(b/167947076): add ramp to step adapter
        // TODO(b/167947076): add filter that removes unsupported primitives
        // TODO(b/167947076): add filter that replaces unsupported prebaked with fallback

        return new VibrationEffect.Composed(newSegments, newRepeatIndex);
    }

    /**
     * Adapter that converts step segments that should be handled as PWLEs to ramp segments.
     *
     * <p>This leves the list unchanged if the device do not have compose PWLE capability.
     */
    private static final class StepToRampAdapter implements SegmentsAdapter {
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

    /**
     * Adapter that clips frequency values to {@link VibratorInfo#getFrequencyRange()} and
     * amplitude values to respective {@link VibratorInfo#getMaxAmplitude}.
     *
     * <p>Devices with no frequency control will collapse all frequencies to zero and leave
     * amplitudes unchanged.
     *
     * <p>The frequency value returned in segments will be absolute, conveted with
     * {@link VibratorInfo#getAbsoluteFrequency(float)}.
     */
    private static final class ClippingAmplitudeFrequencyAdapter implements SegmentsAdapter {
        @Override
        public int apply(List<VibrationEffectSegment> segments, int repeatIndex,
                VibratorInfo info) {
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
