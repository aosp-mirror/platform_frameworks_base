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

package android.location;

import android.annotation.FlaggedApi;
import android.annotation.FloatRange;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.location.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

/**
 * A class that contains Gnss correction associated with a component (e.g. the Ionospheric error).
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_GNSS_ASSISTANCE_INTERFACE)
@SystemApi
public final class GnssCorrectionComponent implements Parcelable {
    /**
     * Uniquely identifies the source of correction (e.g. "Klobuchar" for ionospheric corrections).
     * Clients should not depend on the value of the source key but, rather, can compare
     * before/after to detect changes.
     */
    @NonNull private final String mSourceKey;

    /** The correction is only applicable during this time interval. */
    @NonNull private final GnssInterval mValidityInterval;

    /** Pseudorange correction. */
    @NonNull private final PseudorangeCorrection mPseudorangeCorrection;

    /**
     * Creates a GnssCorrectionComponent.
     *
     * @param sourceKey Uniquely identifies the source of correction (e.g. "Klobuchar" for
     *     ionospheric corrections). Clients should not depend on the value of the source key but,
     *     rather, can compare before/after to detect changes.
     * @param validityInterval The correction is only applicable during this time interval.
     * @param pseudorangeCorrection Pseudorange correction.
     */
    public GnssCorrectionComponent(
            @NonNull String sourceKey,
            @NonNull GnssInterval validityInterval,
            @NonNull PseudorangeCorrection pseudorangeCorrection) {
        Preconditions.checkNotNull(sourceKey, "SourceKey cannot be null");
        Preconditions.checkNotNull(validityInterval, "ValidityInterval cannot be null");
        Preconditions.checkNotNull(pseudorangeCorrection, "PseudorangeCorrection cannot be null");
        mSourceKey = sourceKey;
        mValidityInterval = validityInterval;
        mPseudorangeCorrection = pseudorangeCorrection;
    }

    /** Returns the source key of the correction. */
    @NonNull
    public String getSourceKey() {
        return mSourceKey;
    }

    /** Returns the validity interval of the correction. */
    @NonNull
    public GnssInterval getValidityInterval() {
        return mValidityInterval;
    }

    /** Returns the pseudorange correction. */
    @NonNull
    public PseudorangeCorrection getPseudorangeCorrection() {
        return mPseudorangeCorrection;
    }

    public static final @NonNull Creator<GnssCorrectionComponent> CREATOR =
            new Creator<GnssCorrectionComponent>() {
                @Override
                @NonNull
                public GnssCorrectionComponent createFromParcel(Parcel in) {
                    String sourceKey = in.readString8();
                    GnssInterval validityInterval = in.readTypedObject(GnssInterval.CREATOR);
                    PseudorangeCorrection pseudorangeCorrection =
                            in.readTypedObject(PseudorangeCorrection.CREATOR);
                    return new GnssCorrectionComponent(
                            sourceKey, validityInterval, pseudorangeCorrection);
                }

                @Override
                public GnssCorrectionComponent[] newArray(int size) {
                    return new GnssCorrectionComponent[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mSourceKey);
        dest.writeTypedObject(mValidityInterval, flags);
        dest.writeTypedObject(mPseudorangeCorrection, flags);
    }

    /**
     * Time interval referenced against the GPS epoch. The start must be less than or equal to the
     * end. When the start equals the end, the interval is empty.
     */
    public static final class GnssInterval implements Parcelable {
        /**
         * Inclusive start of the interval in milliseconds since the GPS epoch. A timestamp matching
         * this interval will have to be the same or after the start. Required as a reference time
         * for the initial correction value and its rate of change over time.
         */
        private final long mStartMillisSinceGpsEpoch;

        /**
         * Exclusive end of the interval in milliseconds since the GPS epoch. If specified, a
         * timestamp matching this interval will have to be before the end.
         */
        private final long mEndMillisSinceGpsEpoch;

        /**
         * Creates a GnssInterval.
         *
         * @param startMillisSinceGpsEpoch Inclusive start of the interval in milliseconds since the
         *     GPS epoch. A timestamp matching this interval will have to be the same or after the
         *     start. Required as a reference time for the initial correction value and its rate of
         *     change over time.
         * @param endMillisSinceGpsEpoch Exclusive end of the interval in milliseconds since the GPS
         *     epoch. If specified, a timestamp matching this interval will have to be before the
         *     end.
         */
        public GnssInterval(
                @IntRange(from = 0) long startMillisSinceGpsEpoch,
                @IntRange(from = 0) long endMillisSinceGpsEpoch) {
            Preconditions.checkArgument(startMillisSinceGpsEpoch >= 0);
            Preconditions.checkArgument(endMillisSinceGpsEpoch >= 0);
            mStartMillisSinceGpsEpoch = startMillisSinceGpsEpoch;
            mEndMillisSinceGpsEpoch = endMillisSinceGpsEpoch;
        }

        /** Returns the inclusive start of the interval in milliseconds since the GPS epoch. */
        @IntRange(from = 0)
        public long getStartMillisSinceGpsEpoch() {
            return mStartMillisSinceGpsEpoch;
        }

        /** Returns the exclusive end of the interval in milliseconds since the GPS epoch. */
        @IntRange(from = 0)
        public long getEndMillisSinceGpsEpoch() {
            return mEndMillisSinceGpsEpoch;
        }

        public static final @NonNull Creator<GnssInterval> CREATOR =
                new Creator<GnssInterval>() {
                    @Override
                    @NonNull
                    public GnssInterval createFromParcel(Parcel in) {
                        return new GnssInterval(in.readLong(), in.readLong());
                    }

                    @Override
                    public GnssInterval[] newArray(int size) {
                        return new GnssInterval[size];
                    }
                };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel parcel, int flags) {
            parcel.writeLong(mStartMillisSinceGpsEpoch);
            parcel.writeLong(mEndMillisSinceGpsEpoch);
        }

        @Override
        @NonNull
        public String toString() {
            StringBuilder builder = new StringBuilder("GnssInterval[");
            builder.append("startMillisSinceGpsEpoch = ").append(mStartMillisSinceGpsEpoch);
            builder.append(", endMillisSinceGpsEpoch = ").append(mEndMillisSinceGpsEpoch);
            builder.append("]");
            return builder.toString();
        }
    }

    /** Pseudorange correction. */
    public static final class PseudorangeCorrection implements Parcelable {

        /** Correction to be added to the measured pseudorange, in meters. */
        private final double mCorrectionMeters;

        /** Uncertainty of the correction, in meters. */
        private final double mCorrectionUncertaintyMeters;

        /**
         * Linear approximation of the change in correction over time. Intended usage is to adjust
         * the correction using the formula: correctionMeters + correctionRateMetersPerSecond *
         * delta_seconds Where `delta_seconds` is the number of elapsed seconds since the beginning
         * of the correction validity interval.
         */
        private final double mCorrectionRateMetersPerSecond;

        /**
         * Creates a PseudorangeCorrection.
         *
         * @param correctionMeters Correction to be added to the measured pseudorange, in meters.
         * @param correctionUncertaintyMeters Uncertainty of the correction, in meters.
         * @param correctionRateMetersPerSecond Linear approximation of the change in correction
         *     over time. Intended usage is to adjust the correction using the formula:
         *     correctionMeters + correctionRateMetersPerSecond * delta_seconds Where
         *     `delta_seconds` is the number of elapsed seconds since the beginning of the
         *     correction validity interval.
         */
        public PseudorangeCorrection(
                double correctionMeters,
                double correctionUncertaintyMeters,
                double correctionRateMetersPerSecond) {
            Preconditions.checkArgument(correctionUncertaintyMeters >= 0);
            mCorrectionMeters = correctionMeters;
            mCorrectionUncertaintyMeters = correctionUncertaintyMeters;
            mCorrectionRateMetersPerSecond = correctionRateMetersPerSecond;
        }

        /** Returns the correction to be added to the measured pseudorange, in meters. */
        public double getCorrectionMeters() {
            return mCorrectionMeters;
        }

        /** Returns the uncertainty of the correction, in meters. */
        @FloatRange(from = 0.0f)
        public double getCorrectionUncertaintyMeters() {
            return mCorrectionUncertaintyMeters;
        }

        /** Returns the linear approximation of the change in correction over time. */
        public double getCorrectionRateMetersPerSecond() {
            return mCorrectionRateMetersPerSecond;
        }

        public static final @NonNull Creator<PseudorangeCorrection> CREATOR =
                new Creator<PseudorangeCorrection>() {
                    @Override
                    @NonNull
                    public PseudorangeCorrection createFromParcel(Parcel in) {
                        return new PseudorangeCorrection(
                                in.readDouble(), in.readDouble(), in.readDouble());
                    }

                    @Override
                    public PseudorangeCorrection[] newArray(int size) {
                        return new PseudorangeCorrection[size];
                    }
                };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel parcel, int flags) {
            parcel.writeDouble(mCorrectionMeters);
            parcel.writeDouble(mCorrectionUncertaintyMeters);
            parcel.writeDouble(mCorrectionRateMetersPerSecond);
        }

        @Override
        @NonNull
        public String toString() {
            StringBuilder builder = new StringBuilder("PseudorangeCorrection[");
            builder.append("correctionMeters = ").append(mCorrectionMeters);
            builder.append(", correctionUncertaintyMeters = ").append(mCorrectionUncertaintyMeters);
            builder.append(", correctionRateMetersPerSecond = ")
                    .append(mCorrectionRateMetersPerSecond);
            builder.append("]");
            return builder.toString();
        }
    }
}
