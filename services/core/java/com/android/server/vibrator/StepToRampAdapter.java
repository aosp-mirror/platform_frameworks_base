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
 * <p>Each replaced {@link StepSegment} will be represented by a {@link RampSegment} with same
 * start and end amplitudes/frequencies, which can then be converted to PWLE compositions. This
 * adapter leaves the segments unchanged if the device doesn't have the PWLE composition capability.
 *
 * <p>This adapter also applies the ramp down duration config on devices with PWLE support. This
 * prevents the device from ringing when it cannot handle abrupt changes between ON and OFF states.
 * This will not change other types of abrupt amplitude changes in the original effect.
 *
 * <p>The effect overall duration is preserved by this transformation. Waveforms with ON/OFF
 * segments are handled gracefully by the ramp down changes. Each OFF segment preceded by an ON
 * segment will be shortened, and a ramp down will be added to the transition between ON and OFF.
 * The ramps can be shorter than the configured duration in order to preserve the waveform timings,
 * but they will still soften the ringing effect.
 */
final class StepToRampAdapter implements VibrationEffectAdapters.SegmentsAdapter<VibratorInfo> {

    private final int mRampDownDuration;

    StepToRampAdapter(int rampDownDuration) {
        mRampDownDuration = rampDownDuration;
    }

    @Override
    public int apply(List<VibrationEffectSegment> segments, int repeatIndex,
            VibratorInfo info) {
        if (!info.hasCapability(IVibrator.CAP_COMPOSE_PWLE_EFFECTS)) {
            // The vibrator does not have PWLE capability, so keep the segments unchanged.
            return repeatIndex;
        }
        convertStepsToRamps(segments);
        int newRepeatIndex = addRampDownToZeroAmplitudeSegments(segments, repeatIndex);
        newRepeatIndex = addRampDownToLoop(segments, newRepeatIndex);
        return newRepeatIndex;
    }

    private void convertStepsToRamps(List<VibrationEffectSegment> segments) {
        int segmentCount = segments.size();
        if (mRampDownDuration > 0) {
            // Convert all steps to ramps if the device requires ramp down.
            for (int i = 0; i < segmentCount; i++) {
                if (isStep(segments.get(i))) {
                    segments.set(i, apply((StepSegment) segments.get(i)));
                }
            }
            return;
        }
        // Convert steps that require frequency control to ramps.
        for (int i = 0; i < segmentCount; i++) {
            VibrationEffectSegment segment = segments.get(i);
            if (isStep(segment) && ((StepSegment) segment).getFrequency() != 0) {
                segments.set(i, apply((StepSegment) segment));
            }
        }
        // Convert steps that are next to ramps to also become ramps, so they can be composed
        // together in the same PWLE waveform.
        for (int i = 0; i < segmentCount; i++) {
            if (segments.get(i) instanceof RampSegment) {
                for (int j = i - 1; j >= 0 && isStep(segments.get(j)); j--) {
                    segments.set(j, apply((StepSegment) segments.get(j)));
                }
                for (int j = i + 1; j < segmentCount && isStep(segments.get(j)); j++) {
                    segments.set(j, apply((StepSegment) segments.get(j)));
                }
            }
        }
    }

    /**
     * This will add a ramp to zero as follows:
     *
     * <ol>
     *     <li>Remove the {@link VibrationEffectSegment} that starts and ends at zero amplitude
     *         and follows a segment that ends at non-zero amplitude;
     *     <li>Add a ramp down to zero starting at the previous segment end amplitude and frequency,
     *         with min between the removed segment duration and the configured ramp down duration;
     *     <li>Add a zero amplitude segment following the ramp with the remaining duration, if
     *         necessary;
     * </ol>
     */
    private int addRampDownToZeroAmplitudeSegments(List<VibrationEffectSegment> segments,
            int repeatIndex) {
        if (mRampDownDuration <= 0) {
            // Nothing to do, no ramp down duration configured.
            return repeatIndex;
        }
        int newRepeatIndex = repeatIndex;
        int newSegmentCount = segments.size();
        for (int i = 1; i < newSegmentCount; i++) {
            if (!isOffRampSegment(segments.get(i))
                    || !endsWithNonZeroAmplitude(segments.get(i - 1))) {
                continue;
            }

            // We know the previous segment is a ramp that ends at non-zero amplitude.
            float previousAmplitude = ((RampSegment) segments.get(i - 1)).getEndAmplitude();
            float previousFrequency = ((RampSegment) segments.get(i - 1)).getEndFrequency();
            RampSegment ramp = (RampSegment) segments.get(i);

            if (ramp.getDuration() <= mRampDownDuration) {
                // Replace the zero amplitude segment with a ramp down of same duration, to
                // preserve waveform timings and still soften the transition to zero.
                segments.set(i, createRampDown(previousAmplitude, previousFrequency,
                        ramp.getDuration()));
            } else {
                // Make the zero amplitude segment shorter, to preserve waveform timings, and add a
                // ramp down to zero segment right before it.
                segments.set(i, updateDuration(ramp, ramp.getDuration() - mRampDownDuration));
                segments.add(i, createRampDown(previousAmplitude, previousFrequency,
                        mRampDownDuration));
                if (repeatIndex > i) {
                    newRepeatIndex++;
                }
                i++;
                newSegmentCount++;
            }
        }
        return newRepeatIndex;
    }

    /**
     * This will add a ramp to zero at the repeating index of the given effect, if set, only if
     * the last segment ends at a non-zero amplitude and the repeating segment starts and ends at
     * zero amplitude. The update is described as:
     *
     * <ol>
     *     <li>Add a ramp down to zero following the last segment, with the min between the
     *         removed segment duration and the configured ramp down duration;
     *     <li>Skip the zero-amplitude segment by incrementing the repeat index, splitting it if
     *         necessary to skip the correct amount;
     * </ol>
     */
    private int addRampDownToLoop(List<VibrationEffectSegment> segments, int repeatIndex) {
        if (repeatIndex < 0) {
            // Non-repeating compositions should remain unchanged so duration will be preserved.
            return repeatIndex;
        }

        int segmentCount = segments.size();
        if (mRampDownDuration <= 0 || !endsWithNonZeroAmplitude(segments.get(segmentCount - 1))) {
            // Nothing to do, no ramp down duration configured or composition already ends at zero.
            return repeatIndex;
        }

        // We know the last segment is a ramp that ends at non-zero amplitude.
        RampSegment lastRamp = (RampSegment) segments.get(segmentCount - 1);
        float previousAmplitude = lastRamp.getEndAmplitude();
        float previousFrequency = lastRamp.getEndFrequency();

        if (isOffRampSegment(segments.get(repeatIndex))) {
            // Repeating from a non-zero to a zero amplitude segment, we know the next segment is a
            // ramp with zero amplitudes.
            RampSegment nextRamp = (RampSegment) segments.get(repeatIndex);

            if (nextRamp.getDuration() <= mRampDownDuration) {
                // Skip the zero amplitude segment and append a ramp down of same duration to the
                // end of the composition, to preserve waveform timings and still soften the
                // transition to zero.
                // This will update the waveform as follows:
                //  R               R+1
                //  |  ____          | ____
                // _|_/       =>   __|/    \
                segments.add(createRampDown(previousAmplitude, previousFrequency,
                        nextRamp.getDuration()));
                repeatIndex++;
            } else {
                // Append a ramp down to the end of the composition, split the zero amplitude
                // segment and start repeating from the second half, to preserve waveform timings.
                // This will update the waveform as follows:
                //  R              R+1
                //  |   ____        |  ____
                // _|__/       => __|_/    \
                segments.add(createRampDown(previousAmplitude, previousFrequency,
                        mRampDownDuration));
                segments.set(repeatIndex, updateDuration(nextRamp,
                        nextRamp.getDuration() - mRampDownDuration));
                segments.add(repeatIndex, updateDuration(nextRamp, mRampDownDuration));
                repeatIndex++;
            }
        }

        return repeatIndex;
    }

    private static RampSegment apply(StepSegment segment) {
        return new RampSegment(segment.getAmplitude(), segment.getAmplitude(),
                segment.getFrequency(), segment.getFrequency(), (int) segment.getDuration());
    }

    private static RampSegment createRampDown(float amplitude, float frequency, long duration) {
        return new RampSegment(amplitude, /* endAmplitude= */ 0, frequency, frequency,
                (int) duration);
    }

    private static RampSegment updateDuration(RampSegment ramp, long newDuration) {
        return new RampSegment(ramp.getStartAmplitude(), ramp.getEndAmplitude(),
                ramp.getStartFrequency(), ramp.getEndFrequency(), (int) newDuration);
    }

    private static boolean isStep(VibrationEffectSegment segment) {
        return segment instanceof StepSegment;
    }

    /** Returns true if the segment is a ramp that starts and ends at zero amplitude. */
    private static boolean isOffRampSegment(VibrationEffectSegment segment) {
        if (segment instanceof RampSegment) {
            RampSegment ramp = (RampSegment) segment;
            return ramp.getStartAmplitude() == 0 && ramp.getEndAmplitude() == 0;
        }
        return false;
    }

    private static boolean endsWithNonZeroAmplitude(VibrationEffectSegment segment) {
        if (segment instanceof RampSegment) {
            return ((RampSegment) segment).getEndAmplitude() != 0;
        }
        return false;
    }
}
