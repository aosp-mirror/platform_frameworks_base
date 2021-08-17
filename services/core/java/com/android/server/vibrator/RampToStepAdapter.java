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
import java.util.Arrays;
import java.util.List;

/**
 * Adapter that converts ramp segments that to a sequence of fixed step segments.
 *
 * <p>This leaves the list unchanged if the device has compose PWLE capability.
 */
final class RampToStepAdapter implements VibrationEffectAdapters.SegmentsAdapter<VibratorInfo> {

    private final int mStepDuration;

    RampToStepAdapter(int stepDuration) {
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
