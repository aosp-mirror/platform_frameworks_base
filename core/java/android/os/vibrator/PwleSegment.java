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
 * amplitude and frequency to new values over a specified duration.
 *
 * <p>The amplitudes are expressed by float values in the range [0, 1], representing the relative
 * output acceleration for the vibrator. The frequencies are expressed in hertz by positive finite
 * float values.
 * @hide
 */
@TestApi
@FlaggedApi(Flags.FLAG_NORMALIZED_PWLE_EFFECTS)
public final class PwleSegment extends VibrationEffectSegment {
    private final float mStartAmplitude;
    private final float mStartFrequencyHz;
    private final float mEndAmplitude;
    private final float mEndFrequencyHz;
    private final long mDuration;

    PwleSegment(@android.annotation.NonNull Parcel in) {
        this(in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readLong());
    }

    /** @hide */
    @FlaggedApi(Flags.FLAG_NORMALIZED_PWLE_EFFECTS)
    public PwleSegment(float startAmplitude, float endAmplitude, float startFrequencyHz,
            float endFrequencyHz, long duration) {
        mStartAmplitude = startAmplitude;
        mEndAmplitude = endAmplitude;
        mStartFrequencyHz = startFrequencyHz;
        mEndFrequencyHz = endFrequencyHz;
        mDuration = duration;
    }

    public float getStartAmplitude() {
        return mStartAmplitude;
    }

    public float getEndAmplitude() {
        return mEndAmplitude;
    }

    public float getStartFrequencyHz() {
        return mStartFrequencyHz;
    }

    public float getEndFrequencyHz() {
        return mEndFrequencyHz;
    }

    @Override
    public long getDuration() {
        return mDuration;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof PwleSegment)) {
            return false;
        }
        PwleSegment other = (PwleSegment) o;
        return Float.compare(mStartAmplitude, other.mStartAmplitude) == 0
                && Float.compare(mEndAmplitude, other.mEndAmplitude) == 0
                && Float.compare(mStartFrequencyHz, other.mStartFrequencyHz) == 0
                && Float.compare(mEndFrequencyHz, other.mEndFrequencyHz) == 0
                && mDuration == other.mDuration;
    }

    /** @hide */
    @Override
    public boolean areVibrationFeaturesSupported(@NonNull VibratorInfo vibratorInfo) {
        boolean areFeaturesSupported = vibratorInfo.areEnvelopeEffectsSupported();

        // Check that the frequency is within the supported range
        float minFrequency = vibratorInfo.getFrequencyProfile().getMinFrequencyHz();
        float maxFrequency = vibratorInfo.getFrequencyProfile().getMaxFrequencyHz();

        areFeaturesSupported &=
                mStartFrequencyHz >= minFrequency && mStartFrequencyHz <= maxFrequency
                        && mEndFrequencyHz >= minFrequency && mEndFrequencyHz <= maxFrequency;

        return areFeaturesSupported;
    }

    /** @hide */
    @Override
    public boolean isHapticFeedbackCandidate() {
        return true;
    }

    /** @hide */
    @Override
    public void validate() {
        Preconditions.checkArgumentPositive(mStartFrequencyHz,
                "Start frequency must be greater than zero.");
        Preconditions.checkArgumentPositive(mEndFrequencyHz,
                "End frequency must be greater than zero.");
        Preconditions.checkArgumentPositive(mDuration, "Time must be greater than zero.");

        Preconditions.checkArgumentInRange(mStartAmplitude, 0f, 1f, "startAmplitude");
        Preconditions.checkArgumentInRange(mEndAmplitude, 0f, 1f, "endAmplitude");
    }

    /** @hide */
    @NonNull
    @Override
    public PwleSegment resolve(int defaultAmplitude) {
        return this;
    }

    /** @hide */
    @NonNull
    @Override
    public PwleSegment scale(float scaleFactor) {
        float newStartAmplitude = VibrationEffect.scale(mStartAmplitude, scaleFactor);
        float newEndAmplitude = VibrationEffect.scale(mEndAmplitude, scaleFactor);
        if (Float.compare(mStartAmplitude, newStartAmplitude) == 0
                && Float.compare(mEndAmplitude, newEndAmplitude) == 0) {
            return this;
        }
        return new PwleSegment(newStartAmplitude, newEndAmplitude, mStartFrequencyHz,
                mEndFrequencyHz,
                mDuration);
    }

    /** @hide */
    @NonNull
    @Override
    public PwleSegment scaleLinearly(float scaleFactor) {
        float newStartAmplitude = VibrationEffect.scaleLinearly(mStartAmplitude, scaleFactor);
        float newEndAmplitude = VibrationEffect.scaleLinearly(mEndAmplitude, scaleFactor);
        if (Float.compare(mStartAmplitude, newStartAmplitude) == 0
                && Float.compare(mEndAmplitude, newEndAmplitude) == 0) {
            return this;
        }
        return new PwleSegment(newStartAmplitude, newEndAmplitude, mStartFrequencyHz,
                mEndFrequencyHz,
                mDuration);
    }

    /** @hide */
    @NonNull
    @Override
    public PwleSegment applyEffectStrength(int effectStrength) {
        return this;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mStartAmplitude, mEndAmplitude, mStartFrequencyHz, mEndFrequencyHz,
                mDuration);
    }

    @Override
    public String toString() {
        return "Pwle{startAmplitude=" + mStartAmplitude
                + ", endAmplitude=" + mEndAmplitude
                + ", startFrequencyHz=" + mStartFrequencyHz
                + ", endFrequencyHz=" + mEndFrequencyHz
                + ", duration=" + mDuration
                + "}";
    }

    /** @hide */
    @Override
    public String toDebugString() {
        return String.format(Locale.US, "Pwle=%dms(amplitude=%.2f @ %.2fHz to %.2f @ %.2fHz)",
                mDuration,
                mStartAmplitude,
                mStartFrequencyHz,
                mEndAmplitude,
                mEndFrequencyHz);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(PARCEL_TOKEN_PWLE);
        dest.writeFloat(mStartAmplitude);
        dest.writeFloat(mEndAmplitude);
        dest.writeFloat(mStartFrequencyHz);
        dest.writeFloat(mEndFrequencyHz);
        dest.writeLong(mDuration);
    }

    @android.annotation.NonNull
    public static final Creator<PwleSegment> CREATOR =
            new Creator<PwleSegment>() {
                @Override
                public PwleSegment createFromParcel(Parcel in) {
                    // Skip the type token
                    in.readInt();
                    return new PwleSegment(in);
                }

                @Override
                public PwleSegment[] newArray(int size) {
                    return new PwleSegment[size];
                }
            };
}
