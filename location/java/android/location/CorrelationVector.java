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

package android.location;

import android.annotation.FloatRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.util.Arrays;
import java.util.Objects;

/**
 * Contains info about the correlation output of incoming GNSS signal and a local copy of
 * its corresponding spreading code at a given frequency offset.
 *
 * @hide
 */
@SystemApi
public final class CorrelationVector implements Parcelable {

    private final double mSamplingWidthMeters;
    private final double mSamplingStartMeters;
    private final double mFrequencyOffsetMetersPerSecond;
    @NonNull private final int[] mMagnitude;

    /**
     * Returns the space between correlation samples in meters.
     */
    @FloatRange(from = 0.0f, fromInclusive = false)
    public double getSamplingWidthMeters() {
        return mSamplingWidthMeters;
    }

    /**
     * Returns the offset of the first sampling bin in meters.
     *
     * <p>The following sampling bins are located at positive offsets from this value as follows:
     * samplingStartMeters, samplingStartMeters + samplingWidthMeters, ... , samplingStartMeters +
     * (magnitude.size-1) * samplingWidthMeters.
     *
     */
    @FloatRange(from = 0.0f)
    public double getSamplingStartMeters() {
        return mSamplingStartMeters;
    }

    /**
     * Returns the frequency offset from reported pseudorange rate for this CorrelationVector.
     */
    @FloatRange(from = 0.0f)
    public double getFrequencyOffsetMetersPerSecond() {
        return mFrequencyOffsetMetersPerSecond;
    }

    /**
     * Returns the data array representing normalized correlation magnitude values.
     *
     * <p>The data are normalized correlation magnitude values from -1 to 1, the reported value must
     * be encoded as signed 16 bit integer where 1 is represented by 32767 and -1 is represented
     * by -32768.
     *
     */
    @NonNull
    public int[] getMagnitude() {
        return mMagnitude.clone();
    }

    private CorrelationVector(Builder builder) {
        Preconditions.checkNotNull(builder.mMagnitude, "Magnitude array must not be null");
        Preconditions.checkArgumentPositive(builder.mMagnitude.length,
                "Magnitude array must have non-zero length");
        Preconditions.checkArgument(builder.mFrequencyOffsetMetersPerSecond >= 0.0,
                "FrequencyOffsetMetersPerSecond must be non-negative (greater than or equal to 0)");
        Preconditions.checkArgument(builder.mSamplingWidthMeters > 0.0,
                "SamplingWidthMeters must be positive (greater than 0)");
        Preconditions.checkArgument(builder.mSamplingStartMeters >= 0.0,
                "SamplingStartMeters must be non-negative (greater than or equal to 0)");
        mMagnitude = builder.mMagnitude;
        mFrequencyOffsetMetersPerSecond = builder.mFrequencyOffsetMetersPerSecond;
        mSamplingWidthMeters = builder.mSamplingWidthMeters;
        mSamplingStartMeters = builder.mSamplingStartMeters;
    }

    private CorrelationVector(Parcel in) {
        mSamplingWidthMeters = in.readDouble();
        mSamplingStartMeters = in.readDouble();
        mFrequencyOffsetMetersPerSecond = in.readDouble();
        mMagnitude = new int[in.readInt()];
        in.readIntArray(mMagnitude);
    }

    /*
     * Method definitions to support Parcelable operations.
     */
    public static final @NonNull Parcelable.Creator<CorrelationVector> CREATOR =
            new Parcelable.Creator<CorrelationVector>() {
                @Override
                public CorrelationVector createFromParcel(Parcel parcel) {
                    return new CorrelationVector(parcel);
                }

                @Override
                public CorrelationVector[] newArray(int size) {
                    return new CorrelationVector[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    @Override
    public String toString() {
        return "CorrelationVector{"
                + "FrequencyOffsetMetersPerSecond=" + mFrequencyOffsetMetersPerSecond
                + ", SamplingWidthMeters=" + mSamplingWidthMeters
                + ", SamplingStartMeters=" + mSamplingStartMeters
                + ", Magnitude=" + Arrays.toString(mMagnitude)
                + '}';
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeDouble(mSamplingWidthMeters);
        dest.writeDouble(mSamplingStartMeters);
        dest.writeDouble(mFrequencyOffsetMetersPerSecond);
        dest.writeInt(mMagnitude.length);
        dest.writeIntArray(mMagnitude);
    }

    /**
     * Returns true if this {@link CorrelationVector} is equivalent to the given object.
     * Returns false otherwise.
     */
    @Override
    public boolean equals(Object object) {
        if (object == this) {
            return true;
        }
        if (!(object instanceof CorrelationVector)) {
            return false;
        }
        CorrelationVector c = (CorrelationVector) object;
        return Arrays.equals(mMagnitude, c.getMagnitude())
                && Double.compare(mSamplingWidthMeters, c.getSamplingWidthMeters()) == 0
                && Double.compare(mSamplingStartMeters, c.getSamplingStartMeters()) == 0
                && Double.compare(mFrequencyOffsetMetersPerSecond,
                        c.getFrequencyOffsetMetersPerSecond()) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSamplingWidthMeters, mSamplingStartMeters,
                mFrequencyOffsetMetersPerSecond, Arrays.hashCode(mMagnitude));
    }

    /**
     * Builder class for CorrelationVector.
     */
    public static final class Builder {

        private double mSamplingWidthMeters;
        private double mSamplingStartMeters;
        private double mFrequencyOffsetMetersPerSecond;
        @NonNull private int[] mMagnitude;

        /** Sets the space between correlation samples in meters. */
        @NonNull
        public Builder setSamplingWidthMeters(
                @FloatRange(from = 0.0f, fromInclusive = false) double samplingWidthMeters) {
            mSamplingWidthMeters = samplingWidthMeters;
            return this;
        }

        /** Sets the offset of the first sampling bin in meters. */
        @NonNull
        public Builder setSamplingStartMeters(@FloatRange(from = 0.0f) double samplingStartMeters) {
            mSamplingStartMeters = samplingStartMeters;
            return this;
        }

        /** Sets the frequency offset from reported pseudorange rate for this CorrelationVector */
        @NonNull
        public Builder setFrequencyOffsetMetersPerSecond(
                @FloatRange(from = 0.0f) double frequencyOffsetMetersPerSecond) {
            mFrequencyOffsetMetersPerSecond = frequencyOffsetMetersPerSecond;
            return this;
        }

        /** Sets the data array representing normalized correlation magnitude values. */
        @NonNull
        public Builder setMagnitude(@NonNull int[] magnitude) {
            mMagnitude = magnitude;
            return this;
        }

        /**
         * Build CorrelationVector object.
         *
         * @return instance of CorrelationVector
         */
        @NonNull
        public CorrelationVector build() {
            return new CorrelationVector(this);
        }
    }
}
