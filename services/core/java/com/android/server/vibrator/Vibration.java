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
import android.os.CombinedVibration;
import android.os.IBinder;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.RampSegment;
import android.os.vibrator.StepSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.FrameworkStatsLog;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

/** Represents a vibration request to the vibrator service. */
final class Vibration {
    private static final SimpleDateFormat DEBUG_DATE_FORMAT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS");

    /** Vibration status with reference to values from vibratormanagerservice.proto for logging. */
    enum Status {
        UNKNOWN(VibrationProto.UNKNOWN),
        RUNNING(VibrationProto.RUNNING),
        FINISHED(VibrationProto.FINISHED),
        FINISHED_UNEXPECTED(VibrationProto.FINISHED_UNEXPECTED),
        FORWARDED_TO_INPUT_DEVICES(VibrationProto.FORWARDED_TO_INPUT_DEVICES),
        CANCELLED_BINDER_DIED(VibrationProto.CANCELLED_BINDER_DIED),
        CANCELLED_BY_SCREEN_OFF(VibrationProto.CANCELLED_BY_SCREEN_OFF),
        CANCELLED_BY_SETTINGS_UPDATE(VibrationProto.CANCELLED_BY_SETTINGS_UPDATE),
        CANCELLED_BY_USER(VibrationProto.CANCELLED_BY_USER),
        CANCELLED_BY_UNKNOWN_REASON(VibrationProto.CANCELLED_BY_UNKNOWN_REASON),
        CANCELLED_SUPERSEDED(VibrationProto.CANCELLED_SUPERSEDED),
        IGNORED_ERROR_APP_OPS(VibrationProto.IGNORED_ERROR_APP_OPS),
        IGNORED_ERROR_CANCELLING(VibrationProto.IGNORED_ERROR_CANCELLING),
        IGNORED_ERROR_SCHEDULING(VibrationProto.IGNORED_ERROR_SCHEDULING),
        IGNORED_ERROR_TOKEN(VibrationProto.IGNORED_ERROR_TOKEN),
        IGNORED_APP_OPS(VibrationProto.IGNORED_APP_OPS),
        IGNORED_BACKGROUND(VibrationProto.IGNORED_BACKGROUND),
        IGNORED_UNKNOWN_VIBRATION(VibrationProto.IGNORED_UNKNOWN_VIBRATION),
        IGNORED_UNSUPPORTED(VibrationProto.IGNORED_UNSUPPORTED),
        IGNORED_FOR_EXTERNAL(VibrationProto.IGNORED_FOR_EXTERNAL),
        IGNORED_FOR_HIGHER_IMPORTANCE(VibrationProto.IGNORED_FOR_HIGHER_IMPORTANCE),
        IGNORED_FOR_ONGOING(VibrationProto.IGNORED_FOR_ONGOING),
        IGNORED_FOR_POWER(VibrationProto.IGNORED_FOR_POWER),
        IGNORED_FOR_RINGER_MODE(VibrationProto.IGNORED_FOR_RINGER_MODE),
        IGNORED_FOR_SETTINGS(VibrationProto.IGNORED_FOR_SETTINGS),
        IGNORED_SUPERSEDED(VibrationProto.IGNORED_SUPERSEDED),
        IGNORED_FROM_VIRTUAL_DEVICE(VibrationProto.IGNORED_FROM_VIRTUAL_DEVICE);

        private final int mProtoEnumValue;

        Status(int value) {
            mProtoEnumValue = value;
        }

        public int getProtoEnumValue() {
            return mProtoEnumValue;
        }
    }

    public final VibrationAttributes attrs;
    public final long id;
    public final int uid;
    public final int displayId;
    public final String opPkg;
    public final String reason;
    public final IBinder token;
    public final SparseArray<VibrationEffect> mFallbacks = new SparseArray<>();

    /** The actual effect to be played. */
    @Nullable
    private CombinedVibration mEffect;

    /**
     * The original effect that was requested. Typically these two things differ because the effect
     * was scaled based on the users vibration intensity settings.
     */
    @Nullable
    private CombinedVibration mOriginalEffect;

    /** Vibration status. */
    private Vibration.Status mStatus;

    /** Vibration runtime stats. */
    private final VibrationStats mStats = new VibrationStats();

    /** A {@link CountDownLatch} to enable waiting for completion. */
    private final CountDownLatch mCompletionLatch = new CountDownLatch(1);

    Vibration(IBinder token, int id, CombinedVibration effect,
            VibrationAttributes attrs, int uid, int displayId, String opPkg, String reason) {
        this.token = token;
        this.mEffect = effect;
        this.id = id;
        this.attrs = attrs;
        this.uid = uid;
        this.displayId = displayId;
        this.opPkg = opPkg;
        this.reason = reason;
        mStatus = Vibration.Status.RUNNING;
    }

    VibrationStats stats() {
        return mStats;
    }

    /**
     * Set the {@link Status} of this vibration and reports the current system time as this
     * vibration end time, for debugging purposes.
     *
     * <p>This method will only accept given value if the current status is {@link
     * Status#RUNNING}.
     */
    public void end(EndInfo info) {
        if (hasEnded()) {
            // Vibration already ended, keep first ending status set and ignore this one.
            return;
        }
        mStatus = info.status;
        mStats.reportEnded(info.endedByUid, info.endedByUsage);
        mCompletionLatch.countDown();
    }

    /** Waits indefinitely until another thread calls {@link #end} on this vibration. */
    public void waitForEnd() throws InterruptedException {
        mCompletionLatch.await();
    }

    /**
     * Return the effect to be played when given prebaked effect id is not supported by the
     * vibrator.
     */
    @Nullable
    public VibrationEffect getFallback(int effectId) {
        return mFallbacks.get(effectId);
    }

    /**
     * Add a fallback {@link VibrationEffect} to be played when given effect id is not supported,
     * which might be necessary for replacement in realtime.
     */
    public void addFallback(int effectId, VibrationEffect effect) {
        mFallbacks.put(effectId, effect);
    }

    /**
     * Applied update function to the current effect held by this vibration, and to each fallback
     * effect added.
     */
    public void updateEffects(Function<VibrationEffect, VibrationEffect> updateFn) {
        CombinedVibration newEffect = transformCombinedEffect(mEffect, updateFn);
        if (!newEffect.equals(mEffect)) {
            if (mOriginalEffect == null) {
                mOriginalEffect = mEffect;
            }
            mEffect = newEffect;
        }
        for (int i = 0; i < mFallbacks.size(); i++) {
            mFallbacks.setValueAt(i, updateFn.apply(mFallbacks.valueAt(i)));
        }
    }

    /**
     * Creates a new {@link CombinedVibration} by applying the given transformation function
     * to each {@link VibrationEffect}.
     */
    private static CombinedVibration transformCombinedEffect(
            CombinedVibration combinedEffect, Function<VibrationEffect, VibrationEffect> fn) {
        if (combinedEffect instanceof CombinedVibration.Mono) {
            VibrationEffect effect = ((CombinedVibration.Mono) combinedEffect).getEffect();
            return CombinedVibration.createParallel(fn.apply(effect));
        } else if (combinedEffect instanceof CombinedVibration.Stereo) {
            SparseArray<VibrationEffect> effects =
                    ((CombinedVibration.Stereo) combinedEffect).getEffects();
            CombinedVibration.ParallelCombination combination =
                    CombinedVibration.startParallel();
            for (int i = 0; i < effects.size(); i++) {
                combination.addVibrator(effects.keyAt(i), fn.apply(effects.valueAt(i)));
            }
            return combination.combine();
        } else if (combinedEffect instanceof CombinedVibration.Sequential) {
            List<CombinedVibration> effects =
                    ((CombinedVibration.Sequential) combinedEffect).getEffects();
            CombinedVibration.SequentialCombination combination =
                    CombinedVibration.startSequential();
            for (CombinedVibration effect : effects) {
                combination.addNext(transformCombinedEffect(effect, fn));
            }
            return combination.combine();
        } else {
            // Unknown combination, return same effect.
            return combinedEffect;
        }
    }

    /** Return true is current status is different from {@link Status#RUNNING}. */
    public boolean hasEnded() {
        return mStatus != Status.RUNNING;
    }

    /** Return true is effect is a repeating vibration. */
    public boolean isRepeating() {
        return mEffect.getDuration() == Long.MAX_VALUE;
    }

    /** Return the effect that should be played by this vibration. */
    @Nullable
    public CombinedVibration getEffect() {
        return mEffect;
    }

    /** Return {@link Vibration.DebugInfo} with read-only debug information about this vibration. */
    public Vibration.DebugInfo getDebugInfo() {
        return new Vibration.DebugInfo(mStatus, mStats, mEffect, mOriginalEffect, /* scale= */ 0,
                attrs, uid, displayId, opPkg, reason);
    }

    /** Return {@link VibrationStats.StatsInfo} with read-only metrics about this vibration. */
    public VibrationStats.StatsInfo getStatsInfo(long completionUptimeMillis) {
        int vibrationType = isRepeating()
                ? FrameworkStatsLog.VIBRATION_REPORTED__VIBRATION_TYPE__REPEATED
                : FrameworkStatsLog.VIBRATION_REPORTED__VIBRATION_TYPE__SINGLE;
        return new VibrationStats.StatsInfo(
                uid, vibrationType, attrs.getUsage(), mStatus, mStats, completionUptimeMillis);
    }

    /** Immutable info passed as a signal to end a vibration. */
    static final class EndInfo {
        /** The {@link Status} to be set to the vibration when it ends with this info. */
        @NonNull
        public final Status status;
        /** The UID that triggered the vibration that ended this, or -1 if undefined. */
        public final int endedByUid;
        /** The VibrationAttributes.USAGE_* of the vibration that ended this, or -1 if undefined. */
        public final int endedByUsage;

        EndInfo(@NonNull Vibration.Status status) {
            this(status, /* endedByUid= */ -1, /* endedByUsage= */ -1);
        }

        EndInfo(@NonNull Vibration.Status status, int endedByUid, int endedByUsage) {
            this.status = status;
            this.endedByUid = endedByUid;
            this.endedByUsage = endedByUsage;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof EndInfo)) return false;
            EndInfo that = (EndInfo) o;
            return endedByUid == that.endedByUid
                    && endedByUsage == that.endedByUsage
                    && status == that.status;
        }

        @Override
        public int hashCode() {
            return Objects.hash(status, endedByUid, endedByUsage);
        }

        @Override
        public String toString() {
            return "EndInfo{"
                    + "status=" + status
                    + ", endedByUid=" + endedByUid
                    + ", endedByUsage=" + endedByUsage
                    + '}';
        }
    }

    /** Debug information about vibrations. */
    static final class DebugInfo {
        private final long mCreateTime;
        private final long mStartTime;
        private final long mEndTime;
        private final long mDurationMs;
        private final CombinedVibration mEffect;
        private final CombinedVibration mOriginalEffect;
        private final float mScale;
        private final VibrationAttributes mAttrs;
        private final int mUid;
        private final int mDisplayId;
        private final String mOpPkg;
        private final String mReason;
        private final Status mStatus;

        DebugInfo(Status status, VibrationStats stats, @Nullable CombinedVibration effect,
                @Nullable CombinedVibration originalEffect, float scale, VibrationAttributes attrs,
                int uid, int displayId, String opPkg, String reason) {
            mCreateTime = stats.getCreateTimeDebug();
            mStartTime = stats.getStartTimeDebug();
            mEndTime = stats.getEndTimeDebug();
            mDurationMs = stats.getDurationDebug();
            mEffect = effect;
            mOriginalEffect = originalEffect;
            mScale = scale;
            mAttrs = attrs;
            mUid = uid;
            mDisplayId = displayId;
            mOpPkg = opPkg;
            mReason = reason;
            mStatus = status;
        }

        @Override
        public String toString() {
            return new StringBuilder()
                    .append("createTime: ")
                    .append(DEBUG_DATE_FORMAT.format(new Date(mCreateTime)))
                    .append(", startTime: ")
                    .append(DEBUG_DATE_FORMAT.format(new Date(mStartTime)))
                    .append(", endTime: ")
                    .append(mEndTime == 0 ? null
                            : DEBUG_DATE_FORMAT.format(new Date(mEndTime)))
                    .append(", durationMs: ")
                    .append(mDurationMs)
                    .append(", status: ")
                    .append(mStatus.name().toLowerCase())
                    .append(", effect: ")
                    .append(mEffect)
                    .append(", originalEffect: ")
                    .append(mOriginalEffect)
                    .append(", scale: ")
                    .append(String.format("%.2f", mScale))
                    .append(", attrs: ")
                    .append(mAttrs)
                    .append(", uid: ")
                    .append(mUid)
                    .append(", displayId: ")
                    .append(mDisplayId)
                    .append(", opPkg: ")
                    .append(mOpPkg)
                    .append(", reason: ")
                    .append(mReason)
                    .toString();
        }

        /** Write this info into given {@code fieldId} on {@link ProtoOutputStream}. */
        public void dumpProto(ProtoOutputStream proto, long fieldId) {
            final long token = proto.start(fieldId);
            proto.write(VibrationProto.START_TIME, mStartTime);
            proto.write(VibrationProto.END_TIME, mEndTime);
            proto.write(VibrationProto.DURATION_MS, mDurationMs);
            proto.write(VibrationProto.STATUS, mStatus.ordinal());

            final long attrsToken = proto.start(VibrationProto.ATTRIBUTES);
            proto.write(VibrationAttributesProto.USAGE, mAttrs.getUsage());
            proto.write(VibrationAttributesProto.AUDIO_USAGE, mAttrs.getAudioUsage());
            proto.write(VibrationAttributesProto.FLAGS, mAttrs.getFlags());
            proto.end(attrsToken);

            if (mEffect != null) {
                dumpEffect(proto, VibrationProto.EFFECT, mEffect);
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
            final long token = proto.start(fieldId);
            VibrationEffect.Composed composed = (VibrationEffect.Composed) effect;
            for (VibrationEffectSegment segment : composed.getSegments()) {
                dumpEffect(proto, VibrationEffectProto.SEGMENTS, segment);
            }
            proto.write(VibrationEffectProto.REPEAT, composed.getRepeatIndex());
            proto.end(token);
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
    }

}
