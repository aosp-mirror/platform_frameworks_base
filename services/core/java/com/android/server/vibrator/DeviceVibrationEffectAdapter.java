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
import java.util.Arrays;
import java.util.List;

/** Adapts a {@link VibrationEffect} to a specific device, taking into account its capabilities. */
final class DeviceVibrationEffectAdapter implements VibrationEffectModifier<VibratorInfo> {

    private static final int RAMP_STEP_DURATION_MILLIS = 5;

    /** Adapts a sequence of {@link VibrationEffectSegment} to device's capabilities. */
    interface SegmentsAdapter {

        /**
         * Modifies the given segments list by adding/removing segments to it based on the
         * device capabilities specified by given {@link VibratorInfo}.
         *
         * @param segments    List of {@link VibrationEffectSegment} to be modified.
         * @param repeatIndex Repeat index of the vibration with given segment list.
         * @param info        The device vibrator info that the segments must be adapted to.
         * @return The new repeat index to be used for the modified list.
         */
        int apply(List<VibrationEffectSegment> segments, int repeatIndex, VibratorInfo info);
    }

    private final SegmentsAdapter mAmplitudeFrequencyAdapter;
    private final SegmentsAdapter mStepToRampAdapter;
    private final SegmentsAdapter mRampToStepsAdapter;

    DeviceVibrationEffectAdapter() {
        this(new ClippingAmplitudeFrequencyAdapter());
    }

    DeviceVibrationEffectAdapter(SegmentsAdapter amplitudeFrequencyAdapter) {
        mAmplitudeFrequencyAdapter = amplitudeFrequencyAdapter;
        mStepToRampAdapter = new StepToRampAdapter();
        mRampToStepsAdapter = new RampToStepsAdapter(RAMP_STEP_DURATION_MILLIS);
    }

    @Override
    public VibrationEffect apply(VibrationEffect effect, VibratorInfo info) {
        if (!(effect instanceof VibrationEffect.Composed)) {
            return effect;
        }

        VibrationEffect.Composed composed = (VibrationEffect.Composed) effect;
        List<VibrationEffectSegment> newSegments = new ArrayList<>(composed.getSegments());
        int newRepeatIndex = composed.getRepeatIndex();

        // Replace ramps with a sequence of fixed steps, or no-op if PWLE capability present.
        newRepeatIndex = mRampToStepsAdapter.apply(newSegments, newRepeatIndex, info);

        // Replace steps that should be handled by PWLE to ramps, or no-op if capability missing.
        // This should be done before frequency is converted from relative to absolute values.
        newRepeatIndex = mStepToRampAdapter.apply(newSegments, newRepeatIndex, info);

        // Adapt amplitude and frequency values to device supported ones, converting frequency
        // to absolute values in Hertz.
        newRepeatIndex = mAmplitudeFrequencyAdapter.apply(newSegments, newRepeatIndex, info);

        // TODO(b/167947076): add filter that removes unsupported primitives
        // TODO(b/167947076): add filter that replaces unsupported prebaked with fallback

        return new VibrationEffect.Composed(newSegments, newRepeatIndex);
    }

    /**
     * Adapter that converts step segments that should be handled as PWLEs to ramp segments.
     *
     * <p>This leaves the list unchanged if the device do not have compose PWLE capability.
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
     * Adapter that converts ramp segments that to a sequence of fixed step segments.
     *
     * <p>This leaves the list unchanged if the device have compose PWLE capability.
     */
    private static final class RampToStepsAdapter implements SegmentsAdapter {
        private final int mStepDuration;

        RampToStepsAdapter(int stepDuration) {
            mStepDuration = stepDuration;
        }

        @Override
        public int apply(List<VibrationEffectSegment> segments, int repeatIndex,
                VibratorInfo info) {
            if (info.hasCapability(IVibrator.CAP_COMPOSE_PWLE_EFFECTS)) {
                // The vibrator have PWLE capability, so keep the segments unchanged.
                return repeatIndex;
            }
            int segmentCount = segments.size();
            for (int i = 0; i < segmentCount; i++) {
                VibrationEffectSegment segment = segments.get(i);
                if (!(segment instanceof RampSegment)) {
                    continue;
                }
                List<StepSegment> steps = apply((RampSegment) segment);
                segments.remove(i);
                segments.addAll(i, steps);
                int addedSegments = steps.size() - 1;
                if (repeatIndex > i) {
                    repeatIndex += addedSegments;
                }
                i += addedSegments;
                segmentCount += addedSegments;
            }
            return repeatIndex;
        }

        private List<StepSegment> apply(RampSegment ramp) {
            if (Float.compare(ramp.getStartAmplitude(), ramp.getEndAmplitude()) == 0) {
                // Amplitude is the same, so return a single step to simulate this ramp.
                return Arrays.asList(
                        new StepSegment(ramp.getStartAmplitude(), ramp.getStartFrequency(),
                                (int) ramp.getDuration()));
            }

            List<StepSegment> steps = new ArrayList<>();
            int stepCount = (int) (ramp.getDuration() + mStepDuration - 1) / mStepDuration;
            for (int i = 0; i < stepCount - 1; i++) {
                float pos = (float) i / stepCount;
                steps.add(new StepSegment(
                        interpolate(ramp.getStartAmplitude(), ramp.getEndAmplitude(), pos),
                        interpolate(ramp.getStartFrequency(), ramp.getEndFrequency(), pos),
                        mStepDuration));
            }
            int duration = (int) ramp.getDuration() - mStepDuration * (stepCount - 1);
            steps.add(new StepSegment(ramp.getEndAmplitude(), ramp.getEndFrequency(), duration));
            return steps;
        }

        private static float interpolate(float start, float end, float position) {
            return start + position * (end - start);
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
