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
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.RampSegment;
import android.os.vibrator.StepSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.util.proto.ProtoOutputStream;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;

/**
 * The base class for all vibrations.
 */
class Vibration {
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
