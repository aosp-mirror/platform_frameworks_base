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

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter that converts step segments that should be handled as PWLEs to ramp segments.
 *
 * <p>Each replaced {@link StepSegment} will be represented by a {@link RampSegment} with same
 * start and end amplitudes/frequencies, which can then be converted to PWLE compositions. This
 * adapter leaves the segments unchanged if the device doesn't have the PWLE composition capability.
 */
final class StepToRampAdapter implements VibrationEffectAdapters.SegmentsAdapter<VibratorInfo> {

    @Override
    public int apply(List<VibrationEffectSegment> segments, int repeatIndex,
            VibratorInfo info) {
        if (!info.hasCapability(IVibrator.CAP_COMPOSE_PWLE_EFFECTS)) {
            // The vibrator does not have PWLE capability, so keep the segments unchanged.
            return repeatIndex;
        }
        convertStepsToRamps(segments);
        repeatIndex = splitLongRampSegments(info, segments, repeatIndex);
        return repeatIndex;
    }

    private void convertStepsToRamps(List<VibrationEffectSegment> segments) {
        int segmentCount = segments.size();
        // Convert steps that require frequency control to ramps.
        for (int i = 0; i < segmentCount; i++) {
            VibrationEffectSegment segment = segments.get(i);
            if (isStep(segment) && ((StepSegment) segment).getFrequency() != 0) {
                segments.set(i, convertStepToRamp((StepSegment) segment));
            }
        }
        // Convert steps that are next to ramps to also become ramps, so they can be composed
        // together in the same PWLE waveform.
        for (int i = 0; i < segmentCount; i++) {
            if (segments.get(i) instanceof RampSegment) {
                for (int j = i - 1; j >= 0 && isStep(segments.get(j)); j--) {
                    segments.set(j, convertStepToRamp((StepSegment) segments.get(j)));
                }
                for (int j = i + 1; j < segmentCount && isStep(segments.get(j)); j++) {
                    segments.set(j, convertStepToRamp((StepSegment) segments.get(j)));
                }
            }
        }
    }

    /**
     * Split {@link RampSegment} entries that have duration longer than {@link
     * VibratorInfo#getPwlePrimitiveDurationMax()}.
     */
    private int splitLongRampSegments(VibratorInfo info, List<VibrationEffectSegment> segments,
            int repeatIndex) {
        int maxDuration = info.getPwlePrimitiveDurationMax();
        if (maxDuration <= 0) {
            // No limit set to PWLE primitive duration.
            return repeatIndex;
        }

        int segmentCount = segments.size();
        for (int i = 0; i < segmentCount; i++) {
            if (!(segments.get(i) instanceof RampSegment)) {
                continue;
            }
            RampSegment ramp = (RampSegment) segments.get(i);
            int splits = ((int) ramp.getDuration() + maxDuration - 1) / maxDuration;
            if (splits <= 1) {
                continue;
            }
            segments.remove(i);
            segments.addAll(i, splitRampSegment(ramp, splits));
            int addedSegments = splits - 1;
            if (repeatIndex > i) {
                repeatIndex += addedSegments;
            }
            i += addedSegments;
            segmentCount += addedSegments;
        }

        return repeatIndex;
    }

    private static RampSegment convertStepToRamp(StepSegment segment) {
        return new RampSegment(segment.getAmplitude(), segment.getAmplitude(),
                segment.getFrequency(), segment.getFrequency(), (int) segment.getDuration());
    }

    private static List<RampSegment> splitRampSegment(RampSegment ramp, int splits) {
        List<RampSegment> ramps = new ArrayList<>(splits);
        long splitDuration = ramp.getDuration() / splits;
        float previousAmplitude = ramp.getStartAmplitude();
        float previousFrequency = ramp.getStartFrequency();
        long accumulatedDuration = 0;

        for (int i = 1; i < splits; i++) {
            accumulatedDuration += splitDuration;
            RampSegment rampSplit = new RampSegment(
                    previousAmplitude, interpolateAmplitude(ramp, accumulatedDuration),
                    previousFrequency, interpolateFrequency(ramp, accumulatedDuration),
                    (int) splitDuration);
            ramps.add(rampSplit);
            previousAmplitude = rampSplit.getEndAmplitude();
            previousFrequency = rampSplit.getEndFrequency();
        }

        ramps.add(new RampSegment(previousAmplitude, ramp.getEndAmplitude(), previousFrequency,
                ramp.getEndFrequency(), (int) (ramp.getDuration() - accumulatedDuration)));

        return ramps;
    }

    private static boolean isStep(VibrationEffectSegment segment) {
        return segment instanceof StepSegment;
    }

    private static float interpolateAmplitude(RampSegment ramp, long duration) {
        return interpolate(ramp.getStartAmplitude(), ramp.getEndAmplitude(), duration,
                ramp.getDuration());
    }

    private static float interpolateFrequency(RampSegment ramp, long duration) {
        return interpolate(ramp.getStartFrequency(), ramp.getEndFrequency(), duration,
                ramp.getDuration());
    }

    private static float interpolate(float start, float end, long duration, long totalDuration) {
        float position = (float) duration / totalDuration;
        return start + position * (end - start);
    }
}
