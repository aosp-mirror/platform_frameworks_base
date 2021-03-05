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
import android.os.CombinedVibrationEffect;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.util.proto.ProtoOutputStream;

import java.text.SimpleDateFormat;
import java.util.Date;

/** Represents a vibration request to the vibrator service. */
final class Vibration {
    private static final String TAG = "Vibration";
    private static final SimpleDateFormat DEBUG_DATE_FORMAT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS");

    enum Status {
        RUNNING,
        FINISHED,
        FORWARDED_TO_INPUT_DEVICES,
        CANCELLED,
        IGNORED_ERROR_APP_OPS,
        IGNORED,
        IGNORED_APP_OPS,
        IGNORED_BACKGROUND,
        IGNORED_RINGTONE,
        IGNORED_UNKNOWN_VIBRATION,
        IGNORED_UNSUPPORTED,
        IGNORED_FOR_ALARM,
        IGNORED_FOR_EXTERNAL,
        IGNORED_FOR_ONGOING,
        IGNORED_FOR_POWER,
        IGNORED_FOR_SETTINGS,
    }

    /** Start time in CLOCK_BOOTTIME base. */
    public final long startTime;
    public final VibrationAttributes attrs;
    public final long id;
    public final int uid;
    public final String opPkg;
    public final String reason;
    public final IBinder token;

    /** The actual effect to be played. */
    @Nullable
    private CombinedVibrationEffect mEffect;

    /**
     * The original effect that was requested. Typically these two things differ because the effect
     * was scaled based on the users vibration intensity settings.
     */
    @Nullable
    private CombinedVibrationEffect mOriginalEffect;

    /**
     * Start/end times in unix epoch time. Only to be used for debugging purposes and to correlate
     * with other system events, any duration calculations should be done use {@link #startTime} so
     * as not to be affected by discontinuities created by RTC adjustments.
     */
    private final long mStartTimeDebug;
    private long mEndTimeDebug;
    private Status mStatus;

    Vibration(IBinder token, int id, CombinedVibrationEffect effect,
            VibrationAttributes attrs, int uid, String opPkg, String reason) {
        this.token = token;
        this.mEffect = effect;
        this.id = id;
        this.startTime = SystemClock.elapsedRealtime();
        this.attrs = attrs;
        this.uid = uid;
        this.opPkg = opPkg;
        this.reason = reason;
        mStartTimeDebug = System.currentTimeMillis();
        mStatus = Status.RUNNING;
    }

    /**
     * Set the {@link Status} of this vibration and the current system time as this
     * vibration end time, for debugging purposes.
     *
     * <p>This method will only accept given value if the current status is {@link
     * Status#RUNNING}.
     */
    public void end(Status status) {
        if (hasEnded()) {
            // Vibration already ended, keep first ending status set and ignore this one.
            return;
        }
        mStatus = status;
        mEndTimeDebug = System.currentTimeMillis();
    }

    /**
     * Replace this vibration effect if given {@code scaledEffect} is different, preserving the
     * original one for debug purposes.
     */
    public void updateEffect(@NonNull CombinedVibrationEffect newEffect) {
        if (newEffect.equals(mEffect)) {
            return;
        }
        if (mOriginalEffect == null) {
            mOriginalEffect = mEffect;
        }
        mEffect = newEffect;
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
    public CombinedVibrationEffect getEffect() {
        return mEffect;
    }

    /** Return {@link Vibration.DebugInfo} with read-only debug information about this vibration. */
    public Vibration.DebugInfo getDebugInfo() {
        return new Vibration.DebugInfo(
                mStartTimeDebug, mEndTimeDebug, mEffect, mOriginalEffect, /* scale= */ 0, attrs,
                uid, opPkg, reason, mStatus);
    }

    /** Debug information about vibrations. */
    static final class DebugInfo {
        private final long mStartTimeDebug;
        private final long mEndTimeDebug;
        private final CombinedVibrationEffect mEffect;
        private final CombinedVibrationEffect mOriginalEffect;
        private final float mScale;
        private final VibrationAttributes mAttrs;
        private final int mUid;
        private final String mOpPkg;
        private final String mReason;
        private final Status mStatus;

        DebugInfo(long startTimeDebug, long endTimeDebug, CombinedVibrationEffect effect,
                CombinedVibrationEffect originalEffect, float scale, VibrationAttributes attrs,
                int uid, String opPkg, String reason, Status status) {
            mStartTimeDebug = startTimeDebug;
            mEndTimeDebug = endTimeDebug;
            mEffect = effect;
            mOriginalEffect = originalEffect;
            mScale = scale;
            mAttrs = attrs;
            mUid = uid;
            mOpPkg = opPkg;
            mReason = reason;
            mStatus = status;
        }

        @Override
        public String toString() {
            return new StringBuilder()
                    .append("startTime: ")
                    .append(DEBUG_DATE_FORMAT.format(new Date(mStartTimeDebug)))
                    .append(", endTime: ")
                    .append(mEndTimeDebug == 0 ? null
                            : DEBUG_DATE_FORMAT.format(new Date(mEndTimeDebug)))
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
                    .append(", opPkg: ")
                    .append(mOpPkg)
                    .append(", reason: ")
                    .append(mReason)
                    .toString();
        }

        /** Write this info into given {@code fieldId} on {@link ProtoOutputStream}. */
        public void dumpProto(ProtoOutputStream proto, long fieldId) {
            final long token = proto.start(fieldId);
            proto.write(VibrationProto.START_TIME, mStartTimeDebug);
            proto.write(VibrationProto.END_TIME, mEndTimeDebug);
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
                ProtoOutputStream proto, long fieldId, CombinedVibrationEffect effect) {
            dumpEffect(proto, fieldId,
                    (CombinedVibrationEffect.Sequential) CombinedVibrationEffect.startSequential()
                            .addNext(effect)
                            .combine());
        }

        private void dumpEffect(
                ProtoOutputStream proto, long fieldId, CombinedVibrationEffect.Sequential effect) {
            final long token = proto.start(fieldId);
            for (int i = 0; i < effect.getEffects().size(); i++) {
                CombinedVibrationEffect nestedEffect = effect.getEffects().get(i);
                if (nestedEffect instanceof CombinedVibrationEffect.Mono) {
                    dumpEffect(proto, CombinedVibrationEffectProto.EFFECTS,
                            (CombinedVibrationEffect.Mono) nestedEffect);
                } else if (nestedEffect instanceof CombinedVibrationEffect.Stereo) {
                    dumpEffect(proto, CombinedVibrationEffectProto.EFFECTS,
                            (CombinedVibrationEffect.Stereo) nestedEffect);
                }
                proto.write(CombinedVibrationEffectProto.DELAYS, effect.getDelays().get(i));
            }
            proto.end(token);
        }

        private void dumpEffect(
                ProtoOutputStream proto, long fieldId, CombinedVibrationEffect.Mono effect) {
            final long token = proto.start(fieldId);
            dumpEffect(proto, SyncVibrationEffectProto.EFFECTS, effect.getEffect());
            proto.end(token);
        }

        private void dumpEffect(
                ProtoOutputStream proto, long fieldId, CombinedVibrationEffect.Stereo effect) {
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
            if (effect instanceof VibrationEffect.OneShot) {
                dumpEffect(proto, VibrationEffectProto.ONESHOT, (VibrationEffect.OneShot) effect);
            } else if (effect instanceof VibrationEffect.Waveform) {
                dumpEffect(proto, VibrationEffectProto.WAVEFORM, (VibrationEffect.Waveform) effect);
            } else if (effect instanceof VibrationEffect.Prebaked) {
                dumpEffect(proto, VibrationEffectProto.PREBAKED, (VibrationEffect.Prebaked) effect);
            } else if (effect instanceof VibrationEffect.Composed) {
                dumpEffect(proto, VibrationEffectProto.COMPOSED, (VibrationEffect.Composed) effect);
            }
            proto.end(token);
        }

        private void dumpEffect(ProtoOutputStream proto, long fieldId,
                VibrationEffect.OneShot effect) {
            final long token = proto.start(fieldId);
            proto.write(OneShotProto.DURATION, (int) effect.getDuration());
            proto.write(OneShotProto.AMPLITUDE, effect.getAmplitude());
            proto.end(token);
        }

        private void dumpEffect(ProtoOutputStream proto, long fieldId,
                VibrationEffect.Waveform effect) {
            final long token = proto.start(fieldId);
            for (long timing : effect.getTimings()) {
                proto.write(WaveformProto.TIMINGS, (int) timing);
            }
            for (int amplitude : effect.getAmplitudes()) {
                proto.write(WaveformProto.AMPLITUDES, amplitude);
            }
            proto.write(WaveformProto.REPEAT, effect.getRepeatIndex() >= 0);
            proto.end(token);
        }

        private void dumpEffect(ProtoOutputStream proto, long fieldId,
                VibrationEffect.Prebaked effect) {
            final long token = proto.start(fieldId);
            proto.write(PrebakedProto.EFFECT_ID, effect.getId());
            proto.write(PrebakedProto.EFFECT_STRENGTH, effect.getEffectStrength());
            proto.write(PrebakedProto.FALLBACK, effect.shouldFallback());
            proto.end(token);
        }

        private void dumpEffect(ProtoOutputStream proto, long fieldId,
                VibrationEffect.Composed effect) {
            final long token = proto.start(fieldId);
            for (VibrationEffect.Composition.PrimitiveEffect primitive :
                    effect.getPrimitiveEffects()) {
                proto.write(ComposedProto.EFFECT_IDS, primitive.id);
                proto.write(ComposedProto.EFFECT_SCALES, primitive.scale);
                proto.write(ComposedProto.DELAYS, primitive.delay);
            }
            proto.end(token);
        }
    }
}
