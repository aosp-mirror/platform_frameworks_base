/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.annotation.Nullable;
import android.media.AudioAttributes;
import android.os.CombinedVibration;
import android.os.IBinder;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.RampSegment;
import android.os.vibrator.StepSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.util.IndentingPrintWriter;
import android.util.proto.ProtoOutputStream;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The base class for all vibrations.
 */
abstract class Vibration {
    private static final DateTimeFormatter DEBUG_TIME_FORMATTER = DateTimeFormatter.ofPattern(
            "HH:mm:ss.SSS");
    private static final DateTimeFormatter DEBUG_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern(
            "MM-dd HH:mm:ss.SSS");

    // Used to generate globally unique vibration ids.
    private static final AtomicInteger sNextVibrationId = new AtomicInteger(1); // 0 = no callback

    public final long id;
    public final VibrationSession.CallerInfo callerInfo;
    public final VibrationStats stats = new VibrationStats();
    public final IBinder callerToken;

    private VibrationSession.Status mStatus;

    Vibration(@NonNull IBinder token, @NonNull VibrationSession.CallerInfo callerInfo) {
        Objects.requireNonNull(token);
        Objects.requireNonNull(callerInfo);
        mStatus = VibrationSession.Status.RUNNING;
        this.id = sNextVibrationId.getAndIncrement();
        this.callerToken = token;
        this.callerInfo = callerInfo;
    }

    VibrationSession.Status getStatus() {
        return mStatus;
    }

    /** Return true is current status is different from {@link VibrationSession.Status#RUNNING}. */
    boolean hasEnded() {
        return mStatus != VibrationSession.Status.RUNNING;
    }

    /**
     * Set the {@link VibrationSession} of this vibration and reports the current system time as
     * this vibration end time, for debugging purposes.
     *
     * <p>This method will only accept given value if the current status is {@link
     * VibrationSession.Status#RUNNING}.
     */
    void end(Vibration.EndInfo endInfo) {
        if (hasEnded()) {
            // Vibration already ended, keep first ending status set and ignore this one.
            return;
        }
        mStatus = endInfo.status;
        stats.reportEnded(endInfo.endedBy);
    }

    /** Return true if vibration is a repeating vibration. */
    abstract boolean isRepeating();

    /** Return {@link VibrationSession.DebugInfo} with read-only debug data about this vibration. */
    abstract VibrationSession.DebugInfo getDebugInfo();

    /** Return {@link VibrationStats.StatsInfo} with read-only metrics about this vibration. */
    abstract VibrationStats.StatsInfo getStatsInfo(long completionUptimeMillis);

    /** Immutable info passed as a signal to end a vibration. */
    static final class EndInfo {
        /** The vibration status to be set when it ends with this info. */
        @NonNull
        public final VibrationSession.Status status;
        /** Info about the process that ended the vibration. */
        public final VibrationSession.CallerInfo endedBy;

        EndInfo(@NonNull VibrationSession.Status status) {
            this(status, null);
        }

        EndInfo(@NonNull VibrationSession.Status status,
                @Nullable VibrationSession.CallerInfo endedBy) {
            this.status = status;
            this.endedBy = endedBy;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof EndInfo)) return false;
            EndInfo that = (EndInfo) o;
            return Objects.equals(endedBy, that.endedBy)
                    && status == that.status;
        }

        @Override
        public int hashCode() {
            return Objects.hash(status, endedBy);
        }

        @Override
        public String toString() {
            return "EndInfo{"
                    + "status=" + status
                    + ", endedBy=" + endedBy
                    + '}';
        }
    }

    /**
     * Holds lightweight debug information about the vibration that could potentially be kept in
     * memory for a long time for bugreport dumpsys operations.
     *
     * Since DebugInfo can be kept in memory for a long time, it shouldn't hold any references to
     * potentially expensive or resource-linked objects, such as {@link IBinder}.
     */
    static final class DebugInfoImpl implements VibrationSession.DebugInfo {
        final VibrationSession.Status mStatus;
        final long mCreateTime;
        final VibrationSession.CallerInfo mCallerInfo;
        @Nullable
        final CombinedVibration mPlayedEffect;

        private final long mStartTime;
        private final long mEndTime;
        private final long mDurationMs;
        @Nullable
        private final CombinedVibration mOriginalEffect;
        private final int mScaleLevel;
        private final float mAdaptiveScale;

        DebugInfoImpl(VibrationSession.Status status, VibrationStats stats,
                @Nullable CombinedVibration playedEffect,
                @Nullable CombinedVibration originalEffect, int scaleLevel,
                float adaptiveScale, @NonNull VibrationSession.CallerInfo callerInfo) {
            Objects.requireNonNull(callerInfo);
            mCreateTime = stats.getCreateTimeDebug();
            mStartTime = stats.getStartTimeDebug();
            mEndTime = stats.getEndTimeDebug();
            mDurationMs = stats.getDurationDebug();
            mPlayedEffect = playedEffect;
            mOriginalEffect = originalEffect;
            mScaleLevel = scaleLevel;
            mAdaptiveScale = adaptiveScale;
            mCallerInfo = callerInfo;
            mStatus = status;
        }

        @Override
        public VibrationSession.Status getStatus() {
            return mStatus;
        }

        @Override
        public long getCreateUptimeMillis() {
            return mCreateTime;
        }

        @Override
        public VibrationSession.CallerInfo getCallerInfo() {
            return mCallerInfo;
        }

        @Nullable
        @Override
        public Object getDumpAggregationKey() {
            return mPlayedEffect;
        }

        @Override
        public String toString() {
            return "createTime: " + formatTime(mCreateTime, /*includeDate=*/ true)
                    + ", startTime: " + formatTime(mStartTime, /*includeDate=*/ true)
                    + ", endTime: " + (mEndTime == 0 ? null : formatTime(mEndTime,
                    /*includeDate=*/ true))
                    + ", durationMs: " + mDurationMs
                    + ", status: " + mStatus.name().toLowerCase(Locale.ROOT)
                    + ", playedEffect: " + mPlayedEffect
                    + ", originalEffect: " + mOriginalEffect
                    + ", scaleLevel: " + VibrationScaler.scaleLevelToString(mScaleLevel)
                    + ", adaptiveScale: " + String.format(Locale.ROOT, "%.2f", mAdaptiveScale)
                    + ", callerInfo: " + mCallerInfo;
        }

        @Override
        public void logMetrics(VibratorFrameworkStatsLogger statsLogger) {
            statsLogger.logVibrationAdaptiveHapticScale(mCallerInfo.uid, mAdaptiveScale);
        }

        @Override
        public void dumpCompact(IndentingPrintWriter pw) {
            boolean isExternalVibration = mPlayedEffect == null;
            String timingsStr = String.format(Locale.ROOT,
                    "%s | %8s | %20s | duration: %5dms | start: %12s | end: %12s",
                    formatTime(mCreateTime, /*includeDate=*/ true),
                    isExternalVibration ? "external" : "effect",
                    mStatus.name().toLowerCase(Locale.ROOT),
                    mDurationMs,
                    mStartTime == 0 ? "" : formatTime(mStartTime, /*includeDate=*/ false),
                    mEndTime == 0 ? "" : formatTime(mEndTime, /*includeDate=*/ false));
            String paramStr = String.format(Locale.ROOT,
                    " | scale: %8s (adaptive=%.2f) | flags: %4s | usage: %s",
                    VibrationScaler.scaleLevelToString(mScaleLevel), mAdaptiveScale,
                    Long.toBinaryString(mCallerInfo.attrs.getFlags()),
                    mCallerInfo.attrs.usageToString());
            // Optional, most vibrations should not be defined via AudioAttributes
            // so skip them to simplify the logs
            String audioUsageStr =
                    mCallerInfo.attrs.getOriginalAudioUsage() != AudioAttributes.USAGE_UNKNOWN
                            ? " | audioUsage=" + AudioAttributes.usageToString(
                            mCallerInfo.attrs.getOriginalAudioUsage())
                            : "";
            String callerStr = String.format(Locale.ROOT,
                    " | %s (uid=%d, deviceId=%d) | reason: %s",
                    mCallerInfo.opPkg, mCallerInfo.uid, mCallerInfo.deviceId, mCallerInfo.reason);
            String effectStr = String.format(Locale.ROOT,
                    " | played: %s | original: %s",
                    mPlayedEffect == null ? null : mPlayedEffect.toDebugString(),
                    mOriginalEffect == null ? null : mOriginalEffect.toDebugString());
            pw.println(timingsStr + paramStr + audioUsageStr + callerStr + effectStr);
        }

        @Override
        public void dump(IndentingPrintWriter pw) {
            pw.println("Vibration:");
            pw.increaseIndent();
            pw.println("status = " + mStatus.name().toLowerCase(Locale.ROOT));
            pw.println("durationMs = " + mDurationMs);
            pw.println("createTime = " + formatTime(mCreateTime, /*includeDate=*/ true));
            pw.println("startTime = " + formatTime(mStartTime, /*includeDate=*/ true));
            pw.println("endTime = " + (mEndTime == 0 ? null
                    : formatTime(mEndTime, /*includeDate=*/ true)));
            pw.println("playedEffect = " + mPlayedEffect);
            pw.println("originalEffect = " + mOriginalEffect);
            pw.println("scale = " + VibrationScaler.scaleLevelToString(mScaleLevel));
            pw.println("adaptiveScale = " + String.format(Locale.ROOT, "%.2f", mAdaptiveScale));
            pw.println("callerInfo = " + mCallerInfo);
            pw.decreaseIndent();
        }

        @Override
        public void dump(ProtoOutputStream proto, long fieldId) {
            final long token = proto.start(fieldId);
            proto.write(VibrationProto.START_TIME, mStartTime);
            proto.write(VibrationProto.END_TIME, mEndTime);
            proto.write(VibrationProto.DURATION_MS, mDurationMs);
            proto.write(VibrationProto.STATUS, mStatus.ordinal());

            final long attrsToken = proto.start(VibrationProto.ATTRIBUTES);
            final VibrationAttributes attrs = mCallerInfo.attrs;
            proto.write(VibrationAttributesProto.USAGE, attrs.getUsage());
            proto.write(VibrationAttributesProto.AUDIO_USAGE, attrs.getAudioUsage());
            proto.write(VibrationAttributesProto.FLAGS, attrs.getFlags());
            proto.end(attrsToken);

            if (mPlayedEffect != null) {
                dumpEffect(proto, VibrationProto.PLAYED_EFFECT, mPlayedEffect);
            }
            if (mOriginalEffect != null) {
                dumpEffect(proto, VibrationProto.ORIGINAL_EFFECT, mOriginalEffect);
            }

            proto.end(token);
        }

        private void dumpEffect(
                ProtoOutputStream proto, long fieldId, CombinedVibration effect) {
            dumpEffect(proto, fieldId,
                    (CombinedVibration.Sequential) CombinedVibration.startSequential()
                            .addNext(effect)
                            .combine());
        }

        private void dumpEffect(
                ProtoOutputStream proto, long fieldId, CombinedVibration.Sequential effect) {
            final long token = proto.start(fieldId);
            for (int i = 0; i < effect.getEffects().size(); i++) {
                CombinedVibration nestedEffect = effect.getEffects().get(i);
                if (nestedEffect instanceof CombinedVibration.Mono) {
                    dumpEffect(proto, CombinedVibrationEffectProto.EFFECTS,
                            (CombinedVibration.Mono) nestedEffect);
                } else if (nestedEffect instanceof CombinedVibration.Stereo) {
                    dumpEffect(proto, CombinedVibrationEffectProto.EFFECTS,
                            (CombinedVibration.Stereo) nestedEffect);
                }
                proto.write(CombinedVibrationEffectProto.DELAYS, effect.getDelays().get(i));
            }
            proto.end(token);
        }

        private void dumpEffect(
                ProtoOutputStream proto, long fieldId, CombinedVibration.Mono effect) {
            final long token = proto.start(fieldId);
            dumpEffect(proto, SyncVibrationEffectProto.EFFECTS, effect.getEffect());
            proto.end(token);
        }

        private void dumpEffect(
                ProtoOutputStream proto, long fieldId, CombinedVibration.Stereo effect) {
            final long token = proto.start(fieldId);
            for (int i = 0; i < effect.getEffects().size(); i++) {
                proto.write(SyncVibrationEffectProto.VIBRATOR_IDS, effect.getEffects().keyAt(i));
                dumpEffect(proto, SyncVibrationEffectProto.EFFECTS, effect.getEffects().valueAt(i));
            }
            proto.end(token);
        }

        private void dumpEffect(
                ProtoOutputStream proto, long fieldId, VibrationEffect effect) {
            if (effect instanceof VibrationEffect.Composed composed) {
                final long token = proto.start(fieldId);
                for (VibrationEffectSegment segment : composed.getSegments()) {
                    dumpEffect(proto, VibrationEffectProto.SEGMENTS, segment);
                }
                proto.write(VibrationEffectProto.REPEAT, composed.getRepeatIndex());
                proto.end(token);
            }
        }

        private void dumpEffect(ProtoOutputStream proto, long fieldId,
                VibrationEffectSegment segment) {
            final long token = proto.start(fieldId);
            if (segment instanceof StepSegment) {
                dumpEffect(proto, SegmentProto.STEP, (StepSegment) segment);
            } else if (segment instanceof RampSegment) {
                dumpEffect(proto, SegmentProto.RAMP, (RampSegment) segment);
            } else if (segment instanceof PrebakedSegment) {
                dumpEffect(proto, SegmentProto.PREBAKED, (PrebakedSegment) segment);
            } else if (segment instanceof PrimitiveSegment) {
                dumpEffect(proto, SegmentProto.PRIMITIVE, (PrimitiveSegment) segment);
            }
            proto.end(token);
        }

        private void dumpEffect(ProtoOutputStream proto, long fieldId, StepSegment segment) {
            final long token = proto.start(fieldId);
            proto.write(StepSegmentProto.DURATION, segment.getDuration());
            proto.write(StepSegmentProto.AMPLITUDE, segment.getAmplitude());
            proto.write(StepSegmentProto.FREQUENCY, segment.getFrequencyHz());
            proto.end(token);
        }

        private void dumpEffect(ProtoOutputStream proto, long fieldId, RampSegment segment) {
            final long token = proto.start(fieldId);
            proto.write(RampSegmentProto.DURATION, segment.getDuration());
            proto.write(RampSegmentProto.START_AMPLITUDE, segment.getStartAmplitude());
            proto.write(RampSegmentProto.END_AMPLITUDE, segment.getEndAmplitude());
            proto.write(RampSegmentProto.START_FREQUENCY, segment.getStartFrequencyHz());
            proto.write(RampSegmentProto.END_FREQUENCY, segment.getEndFrequencyHz());
            proto.end(token);
        }

        private void dumpEffect(ProtoOutputStream proto, long fieldId,
                PrebakedSegment segment) {
            final long token = proto.start(fieldId);
            proto.write(PrebakedSegmentProto.EFFECT_ID, segment.getEffectId());
            proto.write(PrebakedSegmentProto.EFFECT_STRENGTH, segment.getEffectStrength());
            proto.write(PrebakedSegmentProto.FALLBACK, segment.shouldFallback());
            proto.end(token);
        }

        private void dumpEffect(ProtoOutputStream proto, long fieldId,
                PrimitiveSegment segment) {
            final long token = proto.start(fieldId);
            proto.write(PrimitiveSegmentProto.PRIMITIVE_ID, segment.getPrimitiveId());
            proto.write(PrimitiveSegmentProto.SCALE, segment.getScale());
            proto.write(PrimitiveSegmentProto.DELAY, segment.getDelay());
            proto.end(token);
        }

        private String formatTime(long timeInMillis, boolean includeDate) {
            return (includeDate ? DEBUG_DATE_TIME_FORMATTER : DEBUG_TIME_FORMATTER)
                    // Ensure timezone is retrieved at formatting time
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(timeInMillis));
        }
    }
}
