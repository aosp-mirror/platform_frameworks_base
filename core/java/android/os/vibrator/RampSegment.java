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

import android.annotation.NonNull;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.VibrationEffect;

import com.android.internal.util.Preconditions;

import java.util.Objects;

/**
 * Representation of {@link VibrationEffectSegment} that ramps vibration amplitude and/or frequency
 * for a specified duration.
 *
 * <p>The amplitudes are expressed by float values in the range [0, 1], representing the relative
 * output acceleration for the vibrator. The frequencies are expressed in hertz by positive finite
 * float values. The special value zero is used here for an unspecified frequency, and will be
 * automatically mapped to the device's default vibration frequency (usually the resonant
 * frequency).
 *
 * @hide
 */
@TestApi
public final class RampSegment extends VibrationEffectSegment {
    private final float mStartAmplitude;
    private final float mStartFrequencyHz;
    private final float mEndAmplitude;
    private final float mEndFrequencyHz;
    private final int mDuration;

    RampSegment(@NonNull Parcel in) {
        this(in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readInt());
    }

    /** @hide */
    public RampSegment(float startAmplitude, float endAmplitude, float startFrequencyHz,
            float endFrequencyHz, int duration) {
        mStartAmplitude = startAmplitude;
        mEndAmplitude = endAmplitude;
        mStartFrequencyHz = startFrequencyHz;
        mEndFrequencyHz = endFrequencyHz;
        mDuration = duration;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof RampSegment)) {
            return false;
        }
        RampSegment other = (RampSegment) o;
        return Float.compare(mStartAmplitude, other.mStartAmplitude) == 0
                && Float.compare(mEndAmplitude, other.mEndAmplitude) == 0
                && Float.compare(mStartFrequencyHz, other.mStartFrequencyHz) == 0
                && Float.compare(mEndFrequencyHz, other.mEndFrequencyHz) == 0
                && mDuration == other.mDuration;
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

    /** @hide */
    @Override
    public boolean isHapticFeedbackCandidate() {
        return true;
    }

    /** @hide */
    @Override
    public boolean hasNonZeroAmplitude() {
        return mStartAmplitude > 0 || mEndAmplitude > 0;
    }

    /** @hide */
    @Override
    public void validate() {
        VibrationEffectSegment.checkFrequencyArgument(mStartFrequencyHz, "startFrequencyHz");
        VibrationEffectSegment.checkFrequencyArgument(mEndFrequencyHz, "endFrequencyHz");
        VibrationEffectSegment.checkDurationArgument(mDuration, "duration");
        Preconditions.checkArgumentInRange(mStartAmplitude, 0f, 1f, "startAmplitude");
        Preconditions.checkArgumentInRange(mEndAmplitude, 0f, 1f, "endAmplitude");
    }

    /** @hide */
    @NonNull
    @Override
    public RampSegment resolve(int defaultAmplitude) {
        // Default amplitude is not supported for ramping.
        return this;
    }

    /** @hide */
    @NonNull
    @Override
    public RampSegment scale(float scaleFactor) {
        float newStartAmplitude = VibrationEffect.scale(mStartAmplitude, scaleFactor);
        float newEndAmplitude = VibrationEffect.scale(mEndAmplitude, scaleFactor);
        if (Float.compare(mStartAmplitude, newStartAmplitude) == 0
                && Float.compare(mEndAmplitude, newEndAmplitude) == 0) {
            return this;
        }
        return new RampSegment(newStartAmplitude, newEndAmplitude, mStartFrequencyHz,
                mEndFrequencyHz,
                mDuration);
    }

    /** @hide */
    @NonNull
    @Override
    public RampSegment applyEffectStrength(int effectStrength) {
        return this;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mStartAmplitude, mEndAmplitude, mStartFrequencyHz, mEndFrequencyHz,
                mDuration);
    }

    @Override
    public String toString() {
        return "Ramp{startAmplitude=" + mStartAmplitude
                + ", endAmplitude=" + mEndAmplitude
                + ", startFrequencyHz=" + mStartFrequencyHz
                + ", endFrequencyHz=" + mEndFrequencyHz
                + ", duration=" + mDuration
                + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(PARCEL_TOKEN_RAMP);
        out.writeFloat(mStartAmplitude);
        out.writeFloat(mEndAmplitude);
        out.writeFloat(mStartFrequencyHz);
        out.writeFloat(mEndFrequencyHz);
        out.writeInt(mDuration);
    }

    @NonNull
    public static final Creator<RampSegment> CREATOR =
            new Creator<RampSegment>() {
                @Override
                public RampSegment createFromParcel(Parcel in) {
                    // Skip the type token
                    in.readInt();
                    return new RampSegment(in);
                }

                @Override
                public RampSegment[] newArray(int size) {
                    return new RampSegment[size];
                }
            };
}
