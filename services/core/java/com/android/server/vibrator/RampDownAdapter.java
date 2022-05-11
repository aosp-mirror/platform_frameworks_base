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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Adapter that applies the ramp down duration config to bring down the vibrator amplitude smoothly.
 *
 * <p>This prevents the device from ringing when it cannot handle abrupt changes between ON and OFF
 * states. This will not change other types of abrupt amplitude changes in the original effect. The
 * effect overall duration is preserved by this transformation.
 *
 * <p>Waveforms with ON/OFF segments are handled gracefully by the ramp down changes. Each OFF
 * segment preceded by an ON segment will be shortened, and a ramp or step down will be added to the
 * transition between ON and OFF. The ramps/steps can be shorter than the configured duration in
 * order to preserve the waveform  timings, but they will still soften the ringing effect.
 *
 * <p>If the segment preceding an OFF segment a {@link RampSegment} then a new ramp segment will be
 * added to bring the amplitude down. If it is a {@link StepSegment} then a sequence of steps will
 * be used to bring the amplitude down to zero. This ensures that the transition from the last
 * amplitude to zero will be handled by the same vibrate method.
 */
final class RampDownAdapter implements VibrationEffectAdapters.SegmentsAdapter<VibratorInfo> {
    private final int mRampDownDuration;
    private final int mStepDuration;

    RampDownAdapter(int rampDownDuration, int stepDuration) {
        mRampDownDuration = rampDownDuration;
        mStepDuration = stepDuration;
    }

    @Override
    public int apply(List<VibrationEffectSegment> segments, int repeatIndex,
            VibratorInfo info) {
        if (mRampDownDuration <= 0) {
            // Nothing to do, no ramp down duration configured.
            return repeatIndex;
        }
        repeatIndex = addRampDownToZeroAmplitudeSegments(segments, repeatIndex);
        repeatIndex = addRampDownToLoop(segments, repeatIndex);
        return repeatIndex;
    }

    /**
     * This will add ramp or steps down to zero as follows:
     *
     * <ol>
     *     <li>Remove the OFF segment that follows a segment of non-zero amplitude;
     *     <li>Add a single {@link RampSegment} or a list of {@link StepSegment} starting at the
     *         previous segment's amplitude and frequency, with min between the configured ramp down
     *         duration or the removed segment's duration;
     *     <li>Add a zero amplitude segment following the steps, if necessary, to fill the remaining
     *         duration;
     * </ol>
     */
    private int addRampDownToZeroAmplitudeSegments(List<VibrationEffectSegment> segments,
            int repeatIndex) {
        int segmentCount = segments.size();
        for (int i = 1; i < segmentCount; i++) {
            VibrationEffectSegment previousSegment = segments.get(i - 1);
            if (!isOffSegment(segments.get(i))
                    || !endsWithNonZeroAmplitude(previousSegment)) {
                continue;
            }

            List<VibrationEffectSegment> replacementSegments = null;
            long offDuration = segments.get(i).getDuration();

            if (previousSegment instanceof StepSegment) {
                float previousAmplitude = ((StepSegment) previousSegment).getAmplitude();
                float previousFrequency = ((StepSegment) previousSegment).getFrequencyHz();

                replacementSegments =
                        createStepsDown(previousAmplitude, previousFrequency, offDuration);
            } else if (previousSegment instanceof RampSegment) {
                float previousAmplitude = ((RampSegment) previousSegment).getEndAmplitude();
                float previousFrequency = ((RampSegment) previousSegment).getEndFrequencyHz();

                if (offDuration <= mRampDownDuration) {
                    // Replace the zero amplitude segment with a ramp down of same duration, to
                    // preserve waveform timings and still soften the transition to zero.
                    replacementSegments = Arrays.asList(
                            createRampDown(previousAmplitude, previousFrequency, offDuration));
                } else {
                    // Replace the zero amplitude segment with a ramp down of configured duration
                    // followed by a shorter off segment.
                    replacementSegments = Arrays.asList(
                            createRampDown(previousAmplitude, previousFrequency, mRampDownDuration),
                            createRampDown(0, previousFrequency, offDuration - mRampDownDuration));
                }
            }

            if (replacementSegments != null) {
                int segmentsAdded = replacementSegments.size() - 1;

                VibrationEffectSegment originalOffSegment = segments.remove(i);
                segments.addAll(i, replacementSegments);
                if (repeatIndex >= i) {
                    if (repeatIndex == i) {
                        // This effect is repeating to the removed off segment: add it back at the
                        // end of the vibration so the loop timings are preserved, and skip it.
                        segments.add(originalOffSegment);
                        repeatIndex++;
                        segmentCount++;
                    }
                    repeatIndex += segmentsAdded;
                }
                i += segmentsAdded;
                segmentCount += segmentsAdded;
            }
        }
        return repeatIndex;
    }

    /**
     * This will ramps down to zero at the repeating index of the given effect, if set, only if
     * the last segment ends at a non-zero amplitude and the repeating segment has zero amplitude.
     * The update is described as:
     *
     * <ol>
     *     <li>Add a ramp or sequence of steps down to zero following the last segment, with the min
     *         between the removed segment duration and the configured ramp down duration;
     *     <li>Skip the zero-amplitude segment by incrementing the repeat index, splitting it if
     *         necessary to skip the correct amount;
     * </ol>
     */
    private int addRampDownToLoop(List<VibrationEffectSegment> segments, int repeatIndex) {
        if (repeatIndex < 0) {
            // Nothing to do, no ramp down duration configured or effect is not repeating.
            return repeatIndex;
        }

        int segmentCount = segments.size();
        if (!endsWithNonZeroAmplitude(segments.get(segmentCount - 1))
                || !isOffSegment(segments.get(repeatIndex))) {
            // Nothing to do, not going back from a positive amplitude to a off segment.
            return repeatIndex;
        }

        VibrationEffectSegment lastSegment = segments.get(segmentCount - 1);
        VibrationEffectSegment offSegment = segments.get(repeatIndex);
        long offDuration = offSegment.getDuration();

        if (offDuration > mRampDownDuration) {
            // Split the zero amplitude segment and start repeating from the second half, to
            // preserve waveform timings. This will update the waveform as follows:
            //  R              R+1
            //  |   ____        |  ____
            // _|__/       => __|_/    \
            segments.set(repeatIndex, updateDuration(offSegment, offDuration - mRampDownDuration));
            segments.add(repeatIndex, updateDuration(offSegment, mRampDownDuration));
        }

        // Skip the zero amplitude segment and append ramp/steps down at the end.
        repeatIndex++;
        if (lastSegment instanceof StepSegment) {
            float previousAmplitude = ((StepSegment) lastSegment).getAmplitude();
            float previousFrequency = ((StepSegment) lastSegment).getFrequencyHz();
            segments.addAll(createStepsDown(previousAmplitude, previousFrequency,
                    Math.min(offDuration, mRampDownDuration)));
        } else if (lastSegment instanceof RampSegment) {
            float previousAmplitude = ((RampSegment) lastSegment).getEndAmplitude();
            float previousFrequency = ((RampSegment) lastSegment).getEndFrequencyHz();
            segments.add(createRampDown(previousAmplitude, previousFrequency,
                    Math.min(offDuration, mRampDownDuration)));
        }

        return repeatIndex;
    }

    private List<VibrationEffectSegment> createStepsDown(float amplitude, float frequency,
            long duration) {
        // Step down for at most the configured ramp duration.
        int stepCount = (int) Math.min(duration, mRampDownDuration) / mStepDuration;
        float amplitudeStep = amplitude / stepCount;
        List<VibrationEffectSegment> steps = new ArrayList<>();
        for (int i = 1; i < stepCount; i++) {
            steps.add(new StepSegment(amplitude - i * amplitudeStep, frequency, mStepDuration));
        }
        int remainingDuration = (int) duration - mStepDuration * (stepCount - 1);
        steps.add(new StepSegment(0, frequency, remainingDuration));
        return steps;
    }

    private static RampSegment createRampDown(float amplitude, float frequency, long duration) {
        return new RampSegment(amplitude, /* endAmplitude= */ 0, frequency, frequency,
                (int) duration);
    }

    private static VibrationEffectSegment updateDuration(VibrationEffectSegment segment,
            long newDuration) {
        if (segment instanceof RampSegment) {
            RampSegment ramp = (RampSegment) segment;
            return new RampSegment(ramp.getStartAmplitude(), ramp.getEndAmplitude(),
                    ramp.getStartFrequencyHz(), ramp.getEndFrequencyHz(), (int) newDuration);
        } else if (segment instanceof StepSegment) {
            StepSegment step = (StepSegment) segment;
            return new StepSegment(step.getAmplitude(), step.getFrequencyHz(), (int) newDuration);
        }
        return segment;
    }

    /** Returns true if the segment is a ramp or a step that starts and ends at zero amplitude. */
    private static boolean isOffSegment(VibrationEffectSegment segment) {
        if (segment instanceof StepSegment) {
            StepSegment ramp = (StepSegment) segment;
            return ramp.getAmplitude() == 0;
        } else if (segment instanceof RampSegment) {
            RampSegment ramp = (RampSegment) segment;
            return ramp.getStartAmplitude() == 0 && ramp.getEndAmplitude() == 0;
        }
        return false;
    }

    /** Returns true if the segment is a ramp or a step that ends at a non-zero amplitude. */
    private static boolean endsWithNonZeroAmplitude(VibrationEffectSegment segment) {
        if (segment instanceof StepSegment) {
            return ((StepSegment) segment).getAmplitude() != 0;
        } else if (segment instanceof RampSegment) {
            return ((RampSegment) segment).getEndAmplitude() != 0;
        }
        return false;
    }
}
