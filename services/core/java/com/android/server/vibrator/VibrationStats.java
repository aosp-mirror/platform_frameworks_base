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

import android.os.SystemClock;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.RampSegment;
import android.util.Slog;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;

/** Holds basic stats about the vibration playback and interaction with the vibrator HAL. */
final class VibrationStats {
    static final String TAG = "VibrationStats";

    // Milestone timestamps, using SystemClock.uptimeMillis(), for calculations.
    // - Create: time a vibration object was created, which is closer to when the service receives a
    //           vibrate request.
    // - Start: time a vibration started to play, which is closer to the time that the
    //          VibrationEffect started playing the very first segment.
    // - End: time a vibration ended, even if it never started to play. This can be as soon as the
    //        vibrator HAL reports it has finished the last command, or before it has even started
    //        when the vibration is ignored or cancelled.
    // Create and end times set by VibratorManagerService only, guarded by its lock.
    // Start times set by VibrationThread only (single-threaded).
    private long mCreateUptimeMillis;
    private long mStartUptimeMillis;
    private long mEndUptimeMillis;

    // Milestone timestamps, using unix epoch time, only to be used for debugging purposes and
    // to correlate with other system events. Any duration calculations should be done with the
    // {create/start/end}UptimeMillis counterparts so as not to be affected by discontinuities
    // created by RTC adjustments.
    // Set together with the *UptimeMillis counterparts.
    private long mCreateTimeDebug;
    private long mStartTimeDebug;
    private long mEndTimeDebug;

    // Vibration interruption tracking.
    // Set by VibratorManagerService only, guarded by its lock.
    private int mEndedByUid;
    private int mEndedByUsage;
    private int mInterruptedUsage;

    // All following counters are set by VibrationThread only (single-threaded):
    // Counts how many times the VibrationEffect was repeated.
    private int mRepeatCount;
    // Total duration, in milliseconds, the vibrator was active with non-zero amplitude.
    private int mVibratorOnTotalDurationMillis;
    // Total number of primitives used in compositions.
    private int mVibrationCompositionTotalSize;
    private int mVibrationPwleTotalSize;
    // Counts how many times each IVibrator method was triggered by this vibration.
    private int mVibratorOnCount;
    private int mVibratorOffCount;
    private int mVibratorSetAmplitudeCount;
    private int mVibratorSetExternalControlCount;
    private int mVibratorPerformCount;
    private int mVibratorComposeCount;
    private int mVibratorComposePwleCount;

    // Ids of vibration effects and primitives used by this vibration, with support flag.
    // Set by VibrationThread only (single-threaded).
    private SparseBooleanArray mVibratorEffectsUsed = new SparseBooleanArray();
    private SparseBooleanArray mVibratorPrimitivesUsed = new SparseBooleanArray();

    VibrationStats() {
        mCreateUptimeMillis = SystemClock.uptimeMillis();
        mCreateTimeDebug = System.currentTimeMillis();
        // Set invalid UID and VibrationAttributes.USAGE values to indicate fields are unset.
        mEndedByUid = -1;
        mEndedByUsage = -1;
        mInterruptedUsage = -1;
    }

    long getCreateUptimeMillis() {
        return mCreateUptimeMillis;
    }

    long getStartUptimeMillis() {
        return mStartUptimeMillis;
    }

    long getEndUptimeMillis() {
        return mEndUptimeMillis;
    }

    long getCreateTimeDebug() {
        return mCreateTimeDebug;
    }

    long getStartTimeDebug() {
        return mStartTimeDebug;
    }

    long getEndTimeDebug() {
        return mEndTimeDebug;
    }

    /**
     * Duration calculated for debugging purposes, between the creation of a vibration and the
     * end time being reported, or -1 if the vibration has not ended.
     */
    long getDurationDebug() {
        return hasEnded() ? (mEndUptimeMillis - mCreateUptimeMillis) : -1;
    }

    /** Return true if vibration reported it has ended. */
    boolean hasEnded() {
        return mEndUptimeMillis > 0;
    }

    /** Return true if vibration reported it has started triggering the vibrator. */
    boolean hasStarted() {
        return mStartUptimeMillis > 0;
    }

    /**
     * Set the current system time as this vibration start time, for debugging purposes.
     *
     * <p>This indicates the vibration has started to interact with the vibrator HAL and the
     * device may start vibrating after this point.
     *
     * <p>This method will only accept given value if the start timestamp was never set.
     */
    void reportStarted() {
        if (hasEnded() || (mStartUptimeMillis != 0)) {
            // Vibration already started or ended, keep first time set and ignore this one.
            return;
        }
        mStartUptimeMillis = SystemClock.uptimeMillis();
        mStartTimeDebug = System.currentTimeMillis();
    }

    /**
     * Set status and end cause for this vibration to end, and the current system time as this
     * vibration end time, for debugging purposes.
     *
     * <p>This might be triggered before {@link #reportStarted()}, which indicates this
     * vibration was cancelled or ignored before it started triggering the vibrator.
     *
     * @return true if the status was accepted. This method will only accept given values if
     * the end timestamp was never set.
     */
    boolean reportEnded(int endedByUid, int endedByUsage) {
        if (hasEnded()) {
            // Vibration already ended, keep first ending stats set and ignore this one.
            return false;
        }
        mEndedByUid = endedByUid;
        mEndedByUsage = endedByUsage;
        mEndUptimeMillis = SystemClock.uptimeMillis();
        mEndTimeDebug = System.currentTimeMillis();
        return true;
    }

    /**
     * Report this vibration has interrupted another vibration.
     *
     * <p>This method will only accept the first value as the one that was interrupted by this
     * vibration, and will ignore all successive calls.
     */
    void reportInterruptedAnotherVibration(int interruptedUsage) {
        if (mInterruptedUsage < 0) {
            mInterruptedUsage = interruptedUsage;
        }
    }

    /** Report the vibration has looped a few more times. */
    void reportRepetition(int loops) {
        mRepeatCount += loops;
    }

    /** Report a call to vibrator method to turn on for given duration. */
    void reportVibratorOn(long halResult) {
        mVibratorOnCount++;

        if (halResult > 0) {
            // If HAL result is positive then it represents the actual duration it will be ON.
            mVibratorOnTotalDurationMillis += (int) halResult;
        }
    }

    /** Report a call to vibrator method to turn off. */
    void reportVibratorOff() {
        mVibratorOffCount++;
    }

    /** Report a call to vibrator method to change the vibration amplitude. */
    void reportSetAmplitude() {
        mVibratorSetAmplitudeCount++;
    }

    /** Report a call to vibrator method to trigger a vibration effect. */
    void reportPerformEffect(long halResult, PrebakedSegment prebaked) {
        mVibratorPerformCount++;

        if (halResult > 0) {
            // If HAL result is positive then it represents the actual duration of the vibration.
            mVibratorEffectsUsed.put(prebaked.getEffectId(), true);
            mVibratorOnTotalDurationMillis += (int) halResult;
        } else {
            // Effect unsupported or request failed.
            mVibratorEffectsUsed.put(prebaked.getEffectId(), false);
        }
    }

    /** Report a call to vibrator method to trigger a vibration as a composition of primitives. */
    void reportComposePrimitives(long halResult, PrimitiveSegment[] primitives) {
        mVibratorComposeCount++;
        mVibrationCompositionTotalSize += primitives.length;

        if (halResult > 0) {
            // If HAL result is positive then it represents the actual duration of the vibration.
            // Remove the requested delays to update the total time the vibrator was ON.
            for (PrimitiveSegment primitive : primitives) {
                halResult -= primitive.getDelay();
                mVibratorPrimitivesUsed.put(primitive.getPrimitiveId(), true);
            }
            if (halResult > 0) {
                mVibratorOnTotalDurationMillis += (int) halResult;
            }
        } else {
            // One or more primitives were unsupported, or request failed.
            for (PrimitiveSegment primitive : primitives) {
                mVibratorPrimitivesUsed.put(primitive.getPrimitiveId(), false);
            }
        }
    }

    /** Report a call to vibrator method to trigger a vibration as a PWLE. */
    void reportComposePwle(long halResult, RampSegment[] segments) {
        mVibratorComposePwleCount++;
        mVibrationPwleTotalSize += segments.length;

        if (halResult > 0) {
            // If HAL result is positive then it represents the actual duration of the vibration.
            // Remove the zero-amplitude segments to update the total time the vibrator was ON.
            for (RampSegment ramp : segments) {
                if ((ramp.getStartAmplitude() == 0) && (ramp.getEndAmplitude() == 0)) {
                    halResult -= ramp.getDuration();
                }
            }
            if (halResult > 0) {
                mVibratorOnTotalDurationMillis += (int) halResult;
            }
        }
    }

    /**
     * Increment the stats for total number of times the {@code setExternalControl} method was
     * triggered in the vibrator HAL.
     */
    void reportSetExternalControl() {
        mVibratorSetExternalControlCount++;
    }

    /**
     * Immutable metrics about this vibration, to be kept in memory until it can be pushed through
     * {@link com.android.internal.util.FrameworkStatsLog} as a
     * {@link com.android.internal.util.FrameworkStatsLog#VIBRATION_REPORTED}.
     */
    static final class StatsInfo {
        public final int uid;
        public final int vibrationType;
        public final int usage;
        public final int status;
        public final boolean endedBySameUid;
        public final int endedByUsage;
        public final int interruptedUsage;
        public final int repeatCount;
        public final int totalDurationMillis;
        public final int vibratorOnMillis;
        public final int startLatencyMillis;
        public final int endLatencyMillis;
        public final int halComposeCount;
        public final int halComposePwleCount;
        public final int halOnCount;
        public final int halOffCount;
        public final int halPerformCount;
        public final int halSetAmplitudeCount;
        public final int halSetExternalControlCount;
        public final int halCompositionSize;
        public final int halPwleSize;
        public final int[] halSupportedCompositionPrimitivesUsed;
        public final int[] halSupportedEffectsUsed;
        public final int[] halUnsupportedCompositionPrimitivesUsed;
        public final int[] halUnsupportedEffectsUsed;
        private boolean mIsWritten;

        StatsInfo(int uid, int vibrationType, int usage, Vibration.Status status,
                VibrationStats stats, long completionUptimeMillis) {
            this.uid = uid;
            this.vibrationType = vibrationType;
            this.usage = usage;
            this.status = status.getProtoEnumValue();
            endedBySameUid = (uid == stats.mEndedByUid);
            endedByUsage = stats.mEndedByUsage;
            interruptedUsage = stats.mInterruptedUsage;
            repeatCount = stats.mRepeatCount;

            // This duration goes from the time this object was created until the time it was
            // completed. We can use latencies to detect the times between first and last
            // interaction with vibrator.
            totalDurationMillis =
                    (int) Math.max(0,  completionUptimeMillis - stats.mCreateUptimeMillis);
            vibratorOnMillis = stats.mVibratorOnTotalDurationMillis;

            if (stats.hasStarted()) {
                // We only measure latencies for vibrations that actually triggered the vibrator.
                startLatencyMillis =
                        (int) Math.max(0, stats.mStartUptimeMillis - stats.mCreateUptimeMillis);
                endLatencyMillis =
                        (int) Math.max(0, completionUptimeMillis - stats.mEndUptimeMillis);
            } else {
                startLatencyMillis = endLatencyMillis = 0;
            }

            halComposeCount = stats.mVibratorComposeCount;
            halComposePwleCount = stats.mVibratorComposePwleCount;
            halOnCount = stats.mVibratorOnCount;
            halOffCount = stats.mVibratorOffCount;
            halPerformCount = stats.mVibratorPerformCount;
            halSetAmplitudeCount = stats.mVibratorSetAmplitudeCount;
            halSetExternalControlCount = stats.mVibratorSetExternalControlCount;
            halCompositionSize = stats.mVibrationCompositionTotalSize;
            halPwleSize = stats.mVibrationPwleTotalSize;
            halSupportedCompositionPrimitivesUsed =
                    filteredKeys(stats.mVibratorPrimitivesUsed, /* supported= */ true);
            halSupportedEffectsUsed =
                    filteredKeys(stats.mVibratorEffectsUsed, /* supported= */ true);
            halUnsupportedCompositionPrimitivesUsed =
                    filteredKeys(stats.mVibratorPrimitivesUsed, /* supported= */ false);
            halUnsupportedEffectsUsed =
                    filteredKeys(stats.mVibratorEffectsUsed, /* supported= */ false);
        }

        @VisibleForTesting
        boolean isWritten() {
            return mIsWritten;
        }

        void writeVibrationReported() {
            if (mIsWritten) {
                Slog.wtf(TAG, "Writing same vibration stats multiple times for uid=" + uid);
            }
            mIsWritten = true;
            // Mapping from this MetricInfo representation and the atom proto VibrationReported.
            FrameworkStatsLog.write_non_chained(
                    FrameworkStatsLog.VIBRATION_REPORTED,
                    uid, null, vibrationType, usage, status, endedBySameUid, endedByUsage,
                    interruptedUsage, repeatCount, totalDurationMillis, vibratorOnMillis,
                    startLatencyMillis, endLatencyMillis, halComposeCount, halComposePwleCount,
                    halOnCount, halOffCount, halPerformCount, halSetAmplitudeCount,
                    halSetExternalControlCount, halSupportedCompositionPrimitivesUsed,
                    halSupportedEffectsUsed, halUnsupportedCompositionPrimitivesUsed,
                    halUnsupportedEffectsUsed, halCompositionSize, halPwleSize);
        }

        private static int[] filteredKeys(SparseBooleanArray supportArray, boolean supported) {
            int count = 0;
            for (int i = 0; i < supportArray.size(); i++) {
                if (supportArray.valueAt(i) == supported) count++;
            }
            if (count == 0) {
                return null;
            }
            int pos = 0;
            int[] res = new int[count];
            for (int i = 0; i < supportArray.size(); i++) {
                if (supportArray.valueAt(i) == supported) {
                    res[pos++] = supportArray.keyAt(i);
                }
            }
            return res;
        }
    }
}
