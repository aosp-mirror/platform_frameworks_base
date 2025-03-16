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

package android.os.vibrator;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.VibrationEffect;
import android.os.VibratorInfo;

import com.android.internal.util.Preconditions;

import java.util.Locale;
import java.util.Objects;

/**
 * A {@link VibrationEffectSegment} that represents a smooth transition from the starting
 * intensity and sharpness to new values over a specified duration.
 *
 * <p>The intensity and sharpness are expressed by float values in the range [0, 1], where
 * intensity represents the user-perceived strength of the vibration, while sharpness represents
 * the crispness of the vibration.
 *
 * @hide
 */
@TestApi
@FlaggedApi(Flags.FLAG_NORMALIZED_PWLE_EFFECTS)
public final class BasicPwleSegment extends VibrationEffectSegment {
    private final float mStartIntensity;
    private final float mEndIntensity;
    private final float mStartSharpness;
    private final float mEndSharpness;
    private final long mDuration;

    BasicPwleSegment(@NonNull Parcel in) {
        this(in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readLong());
    }

    /** @hide */
    @FlaggedApi(Flags.FLAG_NORMALIZED_PWLE_EFFECTS)
    public BasicPwleSegment(float startIntensity, float endIntensity, float startSharpness,
            float endSharpness, long duration) {
        mStartIntensity = startIntensity;
        mEndIntensity = endIntensity;
        mStartSharpness = startSharpness;
        mEndSharpness = endSharpness;
        mDuration = duration;
    }

    public float getStartIntensity() {
        return mStartIntensity;
    }

    public float getEndIntensity() {
        return mEndIntensity;
    }

    public float getStartSharpness() {
        return mStartSharpness;
    }

    public float getEndSharpness() {
        return mEndSharpness;
    }

    @Override
    public long getDuration() {
        return mDuration;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof BasicPwleSegment)) {
            return false;
        }
        BasicPwleSegment other = (BasicPwleSegment) o;
        return Float.compare(mStartIntensity, other.mStartIntensity) == 0
                && Float.compare(mEndIntensity, other.mEndIntensity) == 0
                && Float.compare(mStartSharpness, other.mStartSharpness) == 0
                && Float.compare(mEndSharpness, other.mEndSharpness) == 0
                && mDuration == other.mDuration;
    }

    /** @hide */
    @Override
    public boolean areVibrationFeaturesSupported(@NonNull VibratorInfo vibratorInfo) {
        return vibratorInfo.areEnvelopeEffectsSupported();
    }

    /** @hide */
    @Override
    public boolean isHapticFeedbackCandidate() {
        return true;
    }

    /** @hide */
    @Override
    public void validate() {
        Preconditions.checkArgumentInRange(mStartSharpness, 0f, 1f, "startSharpness");
        Preconditions.checkArgumentInRange(mEndSharpness, 0f, 1f, "endSharpness");
        Preconditions.checkArgumentInRange(mStartIntensity, 0f, 1f, "startIntensity");
        Preconditions.checkArgumentInRange(mEndIntensity, 0f, 1f, "endIntensity");
        Preconditions.checkArgumentPositive(mDuration, "Time must be greater than zero.");
    }

    /** @hide */
    @NonNull
    @Override
    public BasicPwleSegment resolve(int defaultAmplitude) {
        return this;
    }

    /** @hide */
    @NonNull
    @Override
    public BasicPwleSegment scale(float scaleFactor) {
        float newStartIntensity = VibrationEffect.scale(mStartIntensity, scaleFactor);
        float newEndIntensity = VibrationEffect.scale(mEndIntensity, scaleFactor);
        if (Float.compare(mStartIntensity, newStartIntensity) == 0
                && Float.compare(mEndIntensity, newEndIntensity) == 0) {
            return this;
        }
        return new BasicPwleSegment(newStartIntensity, newEndIntensity, mStartSharpness,
                mEndSharpness,
                mDuration);
    }

    /** @hide */
    @NonNull
    @Override
    public BasicPwleSegment scaleLinearly(float scaleFactor) {
        float newStartIntensity = VibrationEffect.scaleLinearly(mStartIntensity, scaleFactor);
        float newEndIntensity = VibrationEffect.scaleLinearly(mEndIntensity, scaleFactor);
        if (Float.compare(mStartIntensity, newStartIntensity) == 0
                && Float.compare(mEndIntensity, newEndIntensity) == 0) {
            return this;
        }
        return new BasicPwleSegment(newStartIntensity, newEndIntensity, mStartSharpness,
                mEndSharpness,
                mDuration);
    }

    /** @hide */
    @NonNull
    @Override
    public BasicPwleSegment applyEffectStrength(int effectStrength) {
        return this;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mStartIntensity, mEndIntensity, mStartSharpness, mEndSharpness,
                mDuration);
    }

    @Override
    public String toString() {
        return "BasicPwle{startIntensity=" + mStartIntensity
                + ", endIntensity=" + mEndIntensity
                + ", startSharpness=" + mStartSharpness
                + ", endSharpness=" + mEndSharpness
                + ", duration=" + mDuration
                + "}";
    }

    /** @hide */
    @Override
    public String toDebugString() {
        return String.format(Locale.US, "Pwle=%dms(intensity=%.2f @ %.2f to %.2f @ %.2f)",
                mDuration,
                mStartIntensity,
                mStartSharpness,
                mEndIntensity,
                mEndSharpness);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(PARCEL_TOKEN_PWLE);
        dest.writeFloat(mStartIntensity);
        dest.writeFloat(mEndIntensity);
        dest.writeFloat(mStartSharpness);
        dest.writeFloat(mEndSharpness);
        dest.writeLong(mDuration);
    }

    @NonNull
    public static final Creator<BasicPwleSegment> CREATOR =
            new Creator<BasicPwleSegment>() {
                @Override
                public BasicPwleSegment createFromParcel(Parcel in) {
                    // Skip the type token
                    in.readInt();
                    return new BasicPwleSegment(in);
                }

                @Override
                public BasicPwleSegment[] newArray(int size) {
                    return new BasicPwleSegment[size];
                }
            };
}
