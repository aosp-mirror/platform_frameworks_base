/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.annotation.NonNull;
import android.os.Trace;
import android.os.VibrationEffect;
import android.os.vibrator.Flags;
import android.os.vibrator.PwlePoint;
import android.os.vibrator.PwleSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.util.Slog;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a step to turn the vibrator on using a composition of PWLE segments.
 *
 * <p>This step will use the maximum supported number of consecutive segments of type
 * {@link PwleSegment}, starting at the current index.
 */
final class ComposePwleV2VibratorStep extends AbstractComposedVibratorStep {

    ComposePwleV2VibratorStep(VibrationStepConductor conductor, long startTime,
            VibratorController controller, VibrationEffect.Composed effect, int index,
            long pendingVibratorOffDeadline) {
        // This step should wait for the last vibration to finish (with the timeout) and for the
        // intended step start time (to respect the effect delays).
        super(conductor, Math.max(startTime, pendingVibratorOffDeadline), controller, effect,
                index, pendingVibratorOffDeadline);
    }

    @NonNull
    @Override
    public List<Step> play() {
        if (!Flags.normalizedPwleEffects()) {
            // Skip this step and play the next one right away.
            return nextSteps(/* segmentsPlayed= */ 1);
        }

        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "ComposePwleV2Step");
        try {
            // Load the next PwleSegments to create a single composePwleV2 call to the vibrator,
            // limited to the vibrator's maximum envelope effect size.
            int limit = controller.getVibratorInfo().getMaxEnvelopeEffectSize();
            List<PwlePoint> pwles = unrollPwleSegments(effect, segmentIndex, limit);

            if (pwles.isEmpty()) {
                Slog.w(VibrationThread.TAG, "Ignoring wrong segment for a ComposeEnvelopeStep: "
                        + effect.getSegments().get(segmentIndex));
                // Skip this step and play the next one right away.
                return nextSteps(/* segmentsPlayed= */ 1);
            }

            if (VibrationThread.DEBUG) {
                Slog.d(VibrationThread.TAG, "Compose " + pwles + " PWLEs on vibrator "
                        + controller.getVibratorInfo().getId());
            }
            PwlePoint[] pwlesArray = pwles.toArray(new PwlePoint[pwles.size()]);
            long vibratorOnResult = controller.on(pwlesArray, getVibration().id);
            handleVibratorOnResult(vibratorOnResult);
            getVibration().stats.reportComposePwle(vibratorOnResult, pwlesArray);

            // The next start and off times will be calculated from mVibratorOnResult.
            return nextSteps(/* segmentsPlayed= */ pwles.size());
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    private List<PwlePoint> unrollPwleSegments(VibrationEffect.Composed effect, int startIndex,
            int limit) {
        List<PwlePoint> pwlePoints = new ArrayList<>(limit);
        float bestBreakAmplitude = 1;
        int bestBreakPosition = limit; // Exclusive index.

        int segmentCount = effect.getSegments().size();
        int repeatIndex = effect.getRepeatIndex();

        // Loop once after reaching the limit to see if breaking it will really be necessary, then
        // apply the best break position found, otherwise return the full list as it fits the limit.
        for (int i = startIndex; pwlePoints.size() < limit; i++) {
            if (i == segmentCount) {
                if (repeatIndex >= 0) {
                    i = repeatIndex;
                } else {
                    // Non-repeating effect, stop collecting pwles.
                    break;
                }
            }
            VibrationEffectSegment segment = effect.getSegments().get(i);
            if (segment instanceof PwleSegment pwleSegment) {
                if (pwlePoints.isEmpty()) {
                    // The initial state is defined by the starting amplitude and frequency of the
                    // first PwleSegment. The time parameter is set to zero to indicate this is
                    // the initial condition without any ramp up time.
                    pwlePoints.add(new PwlePoint(pwleSegment.getStartAmplitude(),
                            pwleSegment.getStartFrequencyHz(), /*timeMillis=*/ 0));
                }
                pwlePoints.add(new PwlePoint(pwleSegment.getEndAmplitude(),
                        pwleSegment.getEndFrequencyHz(), (int) pwleSegment.getDuration()));

                if (isBetterBreakPosition(pwlePoints, bestBreakAmplitude, limit)) {
                    // Mark this position as the best one so far to break a long waveform.
                    bestBreakAmplitude = pwleSegment.getEndAmplitude();
                    bestBreakPosition = pwlePoints.size(); // Break after this pwle ends.
                }
            } else {
                // First non-pwle segment, stop collecting pwles.
                break;
            }
        }

        return pwlePoints.size() > limit
                // Remove excessive segments, using the best breaking position recorded.
                ? pwlePoints.subList(0, bestBreakPosition)
                // Return all collected pwle segments.
                : pwlePoints;
    }

    /**
     * Returns true if the current segment list represents a better break position for a PWLE,
     * given the current amplitude being used for breaking it at a smaller size and the size limit.
     */
    private boolean isBetterBreakPosition(List<PwlePoint> segments,
            float currentBestBreakAmplitude, int limit) {
        PwlePoint lastSegment = segments.get(segments.size() - 1);
        float breakAmplitudeCandidate = lastSegment.getAmplitude();
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
