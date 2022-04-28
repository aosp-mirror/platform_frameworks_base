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
import android.os.Parcelable;
import android.os.VibrationEffect;

import com.android.internal.util.Preconditions;

import java.util.Objects;

/**
 * Representation of {@link VibrationEffectSegment} that holds a fixed vibration amplitude and
 * frequency for a specified duration.
 *
 * <p>The amplitude is expressed by a float value in the range [0, 1], representing the relative
 * output acceleration for the vibrator. The frequency is expressed in hertz by a positive finite
 * float value. The special value zero is used here for an unspecified frequency, and will be
 * automatically mapped to the device's default vibration frequency (usually the resonant
 * frequency).
 *
 * @hide
 */
@TestApi
public final class StepSegment extends VibrationEffectSegment {
    private final float mAmplitude;
    private final float mFrequencyHz;
    private final int mDuration;

    StepSegment(@NonNull Parcel in) {
        this(in.readFloat(), in.readFloat(), in.readInt());
    }

    /** @hide */
    public StepSegment(float amplitude, float frequencyHz, int duration) {
        mAmplitude = amplitude;
        mFrequencyHz = frequencyHz;
        mDuration = duration;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof StepSegment)) {
            return false;
        }
        StepSegment other = (StepSegment) o;
        return Float.compare(mAmplitude, other.mAmplitude) == 0
                && Float.compare(mFrequencyHz, other.mFrequencyHz) == 0
                && mDuration == other.mDuration;
    }

    public float getAmplitude() {
        return mAmplitude;
    }

    public float getFrequencyHz() {
        return mFrequencyHz;
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
        // DEFAULT_AMPLITUDE == -1 is still a non-zero amplitude that will be resolved later.
        return Float.compare(mAmplitude, 0) != 0;
    }

    /** @hide */
    @Override
    public void validate() {
        VibrationEffectSegment.checkFrequencyArgument(mFrequencyHz, "frequencyHz");
        VibrationEffectSegment.checkDurationArgument(mDuration, "duration");
        if (Float.compare(mAmplitude, VibrationEffect.DEFAULT_AMPLITUDE) != 0) {
            Preconditions.checkArgumentInRange(mAmplitude, 0f, 1f, "amplitude");
        }
    }

    /** @hide */
    @NonNull
    @Override
    public StepSegment resolve(int defaultAmplitude) {
        if (defaultAmplitude > VibrationEffect.MAX_AMPLITUDE || defaultAmplitude <= 0) {
            throw new IllegalArgumentException(
                    "amplitude must be between 1 and 255 inclusive (amplitude="
                            + defaultAmplitude + ")");
        }
        if (Float.compare(mAmplitude, VibrationEffect.DEFAULT_AMPLITUDE) != 0) {
            return this;
        }
        return new StepSegment((float) defaultAmplitude / VibrationEffect.MAX_AMPLITUDE,
                mFrequencyHz,
                mDuration);
    }

    /** @hide */
    @NonNull
    @Override
    public StepSegment scale(float scaleFactor) {
        if (Float.compare(mAmplitude, VibrationEffect.DEFAULT_AMPLITUDE) == 0) {
            return this;
        }
        return new StepSegment(VibrationEffect.scale(mAmplitude, scaleFactor), mFrequencyHz,
                mDuration);
    }

    /** @hide */
    @NonNull
    @Override
    public StepSegment applyEffectStrength(int effectStrength) {
        return this;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mAmplitude, mFrequencyHz, mDuration);
    }

    @Override
    public String toString() {
        return "Step{amplitude=" + mAmplitude
                + ", frequencyHz=" + mFrequencyHz
                + ", duration=" + mDuration
                + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(PARCEL_TOKEN_STEP);
        out.writeFloat(mAmplitude);
        out.writeFloat(mFrequencyHz);
        out.writeInt(mDuration);
    }

    @NonNull
    public static final Parcelable.Creator<StepSegment> CREATOR =
            new Parcelable.Creator<StepSegment>() {
                @Override
                public StepSegment createFromParcel(Parcel in) {
                    // Skip the type token
                    in.readInt();
                    return new StepSegment(in);
                }

                @Override
                public StepSegment[] newArray(int size) {
                    return new StepSegment[size];
                }
            };
}
