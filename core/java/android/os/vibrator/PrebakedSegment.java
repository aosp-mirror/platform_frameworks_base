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

package android.os.vibrator;

import static android.os.VibrationEffect.Composition.PRIMITIVE_CLICK;
import static android.os.VibrationEffect.Composition.PRIMITIVE_THUD;
import static android.os.VibrationEffect.Composition.PRIMITIVE_TICK;
import static android.os.VibrationEffect.EFFECT_CLICK;
import static android.os.VibrationEffect.EFFECT_DOUBLE_CLICK;
import static android.os.VibrationEffect.EFFECT_HEAVY_CLICK;
import static android.os.VibrationEffect.EFFECT_POP;
import static android.os.VibrationEffect.EFFECT_TEXTURE_TICK;
import static android.os.VibrationEffect.EFFECT_THUD;
import static android.os.VibrationEffect.EFFECT_TICK;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorInfo;

import java.util.Objects;

/**
 * Representation of {@link VibrationEffectSegment} that plays a prebaked vibration effect.
 *
 * @hide
 */
@TestApi
public final class PrebakedSegment extends VibrationEffectSegment {

    /** @hide */
    public static final int DEFAULT_STRENGTH = VibrationEffect.EFFECT_STRENGTH_MEDIUM;

    /** @hide */
    public static final boolean DEFAULT_SHOULD_FALLBACK = true;

    private final int mEffectId;
    private final boolean mFallback;
    private final int mEffectStrength;

    PrebakedSegment(@NonNull Parcel in) {
        mEffectId = in.readInt();
        mFallback = in.readByte() != 0;
        mEffectStrength = in.readInt();
    }

    /** @hide */
    public PrebakedSegment(int effectId, boolean shouldFallback, int effectStrength) {
        mEffectId = effectId;
        mFallback = shouldFallback;
        mEffectStrength = effectStrength;
    }

    public int getEffectId() {
        return mEffectId;
    }

    public int getEffectStrength() {
        return mEffectStrength;
    }

    /** Return true if a fallback effect should be played if this effect is not supported. */
    public boolean shouldFallback() {
        return mFallback;
    }

    @Override
    public long getDuration() {
        return -1;
    }

    /** @hide */
    @Override
    public long getDuration(@Nullable VibratorInfo vibratorInfo) {
        if (vibratorInfo == null) {
            return getDuration();
        }
        return switch (mEffectId) {
            case EFFECT_TICK,
                 EFFECT_CLICK,
                 EFFECT_HEAVY_CLICK -> estimateFromPrimitiveDuration(vibratorInfo, PRIMITIVE_CLICK);
            case EFFECT_TEXTURE_TICK -> estimateFromPrimitiveDuration(vibratorInfo, PRIMITIVE_TICK);
            case EFFECT_THUD -> estimateFromPrimitiveDuration(vibratorInfo, PRIMITIVE_THUD);
            case EFFECT_DOUBLE_CLICK -> {
                long clickDuration = vibratorInfo.getPrimitiveDuration(PRIMITIVE_CLICK);
                yield clickDuration > 0 ? 2 * clickDuration : getDuration();
            }
            default -> getDuration();
        };
    }

    private long estimateFromPrimitiveDuration(VibratorInfo vibratorInfo, int primitiveId) {
        int duration = vibratorInfo.getPrimitiveDuration(primitiveId);
        // Unsupported primitives should be ignored here.
        return duration > 0 ? duration : getDuration();
    }

    /** @hide */
    @Override
    public boolean areVibrationFeaturesSupported(@NonNull VibratorInfo vibratorInfo) {
        if (vibratorInfo.isEffectSupported(mEffectId) == Vibrator.VIBRATION_EFFECT_SUPPORT_YES) {
            return true;
        }
        if (!mFallback) {
            // If the Vibrator's support is not `VIBRATION_EFFECT_SUPPORT_YES`, and this effect does
            // not support fallbacks, the effect is considered not supported by the vibrator.
            return false;
        }
        // The vibrator does not have hardware support for the effect, but the effect has fallback
        // support. Check if a fallback will be available for the effect ID.
        return switch (mEffectId) {
            // Any of these effects are always supported via some form of fallback.
            case EFFECT_CLICK,
                 EFFECT_DOUBLE_CLICK,
                 EFFECT_HEAVY_CLICK,
                 EFFECT_TICK -> true;
            default -> false;
        };
    }

    /** @hide */
    @Override
    public boolean isHapticFeedbackCandidate() {
        return switch (mEffectId) {
            case EFFECT_CLICK,
                 EFFECT_DOUBLE_CLICK,
                 EFFECT_HEAVY_CLICK,
                 EFFECT_POP,
                 EFFECT_TEXTURE_TICK,
                 EFFECT_THUD,
                 EFFECT_TICK -> true;
            // VibrationEffect.RINGTONES are not segments that could represent a haptic feedback
            default -> false;
        };
    }

    /** @hide */
    @NonNull
    @Override
    public PrebakedSegment resolve(int defaultAmplitude) {
        return this;
    }

    /** @hide */
    @NonNull
    @Override
    public PrebakedSegment scale(float scaleFactor) {
        // Prebaked effect strength cannot be scaled with this method.
        return this;
    }

    /** @hide */
    @NonNull
    @Override
    public PrebakedSegment scaleLinearly(float scaleFactor) {
        // Prebaked effect strength cannot be scaled with this method.
        return this;
    }

    /** @hide */
    @NonNull
    @Override
    public PrebakedSegment applyEffectStrength(int effectStrength) {
        if (effectStrength != mEffectStrength && isValidEffectStrength(effectStrength)) {
            return new PrebakedSegment(mEffectId, mFallback, effectStrength);
        }
        return this;
    }

    private static boolean isValidEffectStrength(int strength) {
        return switch (strength) {
            case VibrationEffect.EFFECT_STRENGTH_LIGHT,
                 VibrationEffect.EFFECT_STRENGTH_MEDIUM,
                 VibrationEffect.EFFECT_STRENGTH_STRONG -> true;
            default -> false;
        };
    }

    /** @hide */
    @Override
    public void validate() {
        switch (mEffectId) {
            case EFFECT_CLICK:
            case EFFECT_DOUBLE_CLICK:
            case EFFECT_HEAVY_CLICK:
            case EFFECT_POP:
            case EFFECT_TEXTURE_TICK:
            case EFFECT_THUD:
            case EFFECT_TICK:
                break;
            default:
                int[] ringtones = VibrationEffect.RINGTONES;
                if (mEffectId < ringtones[0] || mEffectId > ringtones[ringtones.length - 1]) {
                    throw new IllegalArgumentException(
                            "Unknown prebaked effect type (value=" + mEffectId + ")");
                }
        }
        if (!isValidEffectStrength(mEffectStrength)) {
            throw new IllegalArgumentException(
                    "Unknown prebaked effect strength (value=" + mEffectStrength + ")");
        }
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (!(o instanceof PrebakedSegment)) {
            return false;
        }
        PrebakedSegment other = (PrebakedSegment) o;
        return mEffectId == other.mEffectId
                && mFallback == other.mFallback
                && mEffectStrength == other.mEffectStrength;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mEffectId, mFallback, mEffectStrength);
    }

    @Override
    public String toString() {
        return "Prebaked{effect=" + VibrationEffect.effectIdToString(mEffectId)
                + ", strength=" + VibrationEffect.effectStrengthToString(mEffectStrength)
                + ", fallback=" + mFallback
                + "}";
    }

    /** @hide */
    @Override
    public String toDebugString() {
        return String.format("Prebaked=%s(%s, %s fallback)",
                VibrationEffect.effectIdToString(mEffectId),
                VibrationEffect.effectStrengthToString(mEffectStrength),
                mFallback ? "with" : "no");
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(PARCEL_TOKEN_PREBAKED);
        out.writeInt(mEffectId);
        out.writeByte((byte) (mFallback ? 1 : 0));
        out.writeInt(mEffectStrength);
    }

    @NonNull
    public static final Parcelable.Creator<PrebakedSegment> CREATOR =
            new Parcelable.Creator<PrebakedSegment>() {
                @Override
                public PrebakedSegment createFromParcel(Parcel in) {
                    // Skip the type token
                    in.readInt();
                    return new PrebakedSegment(in);
                }

                @Override
                public PrebakedSegment[] newArray(int size) {
                    return new PrebakedSegment[size];
                }
            };
}
