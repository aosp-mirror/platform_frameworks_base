/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.os.Trace;
import android.os.VibrationEffect;
import android.os.vibrator.RampSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.util.Slog;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a step to turn the vibrator on using a composition of PWLE segments.
 *
 * <p>This step will use the maximum supported number of consecutive segments of type
 * {@link RampSegment}, starting at the current index.
 */
final class ComposePwleVibratorStep extends AbstractVibratorStep {
    /**
     * Default limit to the number of PWLE segments, if none is defined by the HAL, to prevent
     * repeating effects from generating an infinite list.
     */
    private static final int DEFAULT_PWLE_SIZE_LIMIT = 100;

    ComposePwleVibratorStep(VibrationStepConductor conductor, long startTime,
            VibratorController controller, VibrationEffect.Composed effect, int index,
            long pendingVibratorOffDeadline) {
        // This step should wait for the last vibration to finish (with the timeout) and for the
        // intended step start time (to respect the effect delays).
        super(conductor, Math.max(startTime, pendingVibratorOffDeadline), controller, effect,
                index, pendingVibratorOffDeadline);
    }

    @Override
    public List<Step> play() {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "ComposePwleStep");
        try {
            // Load the next RampSegments to create a single composePwle call to the vibrator,
            // limited to the vibrator PWLE maximum size.
            int limit = controller.getVibratorInfo().getPwleSizeMax();
            List<RampSegment> pwles = unrollRampSegments(effect, segmentIndex,
                    limit > 0 ? limit : DEFAULT_PWLE_SIZE_LIMIT);

            if (pwles.isEmpty()) {
                Slog.w(VibrationThread.TAG, "Ignoring wrong segment for a ComposePwleStep: "
                        + effect.getSegments().get(segmentIndex));
                // Skip this step and play the next one right away.
                return nextSteps(/* segmentsPlayed= */ 1);
            }

            if (VibrationThread.DEBUG) {
                Slog.d(VibrationThread.TAG, "Compose " + pwles + " PWLEs on vibrator "
                        + controller.getVibratorInfo().getId());
            }
            RampSegment[] pwlesArray = pwles.toArray(new RampSegment[pwles.size()]);
            long vibratorOnResult = controller.on(pwlesArray, getVibration().id);
            handleVibratorOnResult(vibratorOnResult);
            getVibration().stats.reportComposePwle(vibratorOnResult, pwlesArray);

            // The next start and off times will be calculated from mVibratorOnResult.
            return nextSteps(/* segmentsPlayed= */ pwles.size());
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    /**
     * Get the ramp segments to be played by this step for a waveform, starting at
     * {@code startIndex} until:
     *
     * <ol>
     *     <li>There are no more segments in the effect;
     *     <li>The first non-ramp segment is found;
     *     <li>The given limit to the PWLE size is reached.
     * </ol>
     *
     * <p>If the effect is repeating then this method will generate the largest PWLE within given
     * limit. This will also optimize to end the list at a ramp to zero-amplitude, if possible, and
     * avoid braking down the effect in non-zero amplitude.
     */
    private List<RampSegment> unrollRampSegments(VibrationEffect.Composed effect, int startIndex,
            int limit) {
        List<RampSegment> segments = new ArrayList<>(limit);
        float bestBreakAmplitude = 1;
        int bestBreakPosition = limit; // Exclusive index.

        int segmentCount = effect.getSegments().size();
        int repeatIndex = effect.getRepeatIndex();

        // Loop once after reaching the limit to see if breaking it will really be necessary, then
        // apply the best break position found, otherwise return the full list as it fits the limit.
        for (int i = startIndex; segments.size() <= limit; i++) {
            if (i == segmentCount) {
                if (repeatIndex >= 0) {
                    i = repeatIndex;
                } else {
                    // Non-repeating effect, stop collecting ramps.
                    break;
                }
            }
            VibrationEffectSegment segment = effect.getSegments().get(i);
            if (segment instanceof RampSegment) {
                RampSegment rampSegment = (RampSegment) segment;
                segments.add(rampSegment);

                if (isBetterBreakPosition(segments, bestBreakAmplitude, limit)) {
                    // Mark this position as the best one so far to break a long waveform.
                    bestBreakAmplitude = rampSegment.getEndAmplitude();
                    bestBreakPosition = segments.size(); // Break after this ramp ends.
                }
            } else {
                // First non-ramp segment, stop collecting ramps.
                break;
            }
        }

        return segments.size() > limit
                // Remove excessive segments, using the best breaking position recorded.
                ? segments.subList(0, bestBreakPosition)
                // Return all collected ramp segments.
                : segments;
    }

    /**
     * Returns true if the current segment list represents a better break position for a PWLE,
     * given the current amplitude being used for breaking it at a smaller size and the size limit.
     */
    private boolean isBetterBreakPosition(List<RampSegment> segments,
            float currentBestBreakAmplitude, int limit) {
        RampSegment lastSegment = segments.get(segments.size() - 1);
        float breakAmplitudeCandidate = lastSegment.getEndAmplitude();
        int breakPositionCandidate = segments.size();

        if (breakPositionCandidate > limit) {
            // We're beyond limit, last break position found should be used.
            return false;
        }
        if (breakAmplitudeCandidate == 0) {
            // Breaking at amplitude zero at any position is always preferable.
            return true;
        }
        if (breakPositionCandidate < limit / 2) {
            // Avoid breaking at the first half of the allowed maximum size, even if amplitudes are
            // lower, to avoid creating PWLEs that are too small unless it's to break at zero.
            return false;
        }
        // Prefer lower amplitudes at a later position for breaking the PWLE in a more subtle way.
        return breakAmplitudeCandidate <= currentBestBreakAmplitude;
    }
}
