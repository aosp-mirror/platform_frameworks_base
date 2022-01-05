/*
 * Copyright (C) 2018 The Android Open Source Project
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
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.util.Objects;

/**
 * A container with measurement corrections for a single visible satellite
 *
 * @hide
 */
@SystemApi
public final class GnssSingleSatCorrection implements Parcelable {

    /**
     * Bit mask for {@link #mSingleSatCorrectionFlags} indicating the presence of {@link
     * #mProbSatIsLos}.
     *
     * @hide
     */
    public static final int HAS_PROB_SAT_IS_LOS_MASK = 1 << 0;

    /**
     * Bit mask for {@link #mSingleSatCorrectionFlags} indicating the presence of {@link
     * #mExcessPathLengthMeters}.
     *
     * @hide
     */
    public static final int HAS_EXCESS_PATH_LENGTH_MASK = 1 << 1;

    /**
     * Bit mask for {@link #mSingleSatCorrectionFlags} indicating the presence of {@link
     * #mExcessPathLengthUncertaintyMeters}.
     *
     * @hide
     */
    public static final int HAS_EXCESS_PATH_LENGTH_UNC_MASK = 1 << 2;

    /**
     * Bit mask for {@link #mSingleSatCorrectionFlags} indicating the presence of {@link
     * #mReflectingPlane}.
     *
     * @hide
     */
    public static final int HAS_REFLECTING_PLANE_MASK = 1 << 3;

    /** A bitmask of fields present in this object (see HAS_* constants defined above) */
    private final int mSingleSatCorrectionFlags;

    /** Defines the constellation of the given satellite as defined in {@link GnssStatus}. */
    @GnssStatus.ConstellationType
    private final int mConstellationType;

    /**
     * Satellite vehicle ID number
     *
     * <p>Interpretation depends on {@link GnssStatus#getSvid(int)}.
     */
    @IntRange(from = 0)
    private final int mSatId;

    /**
     * Carrier frequency of the signal to be corrected, for example it can be the GPS center
     * frequency for L1 = 1,575,420,000 Hz, varying GLO channels, etc.
     *
     * <p>For an L1, L5 receiver tracking a satellite on L1 and L5 at the same time, two correction
     * objects will be reported for this same satellite, in one of the correction objects, all the
     * values related to L1 will be filled, and in the other all of the values related to L5 will be
     * filled.
     */
    @FloatRange(from = 0.0f,  fromInclusive = false)
    private final float mCarrierFrequencyHz;

    /**
     * The probability that the satellite is estimated to be in Line-of-Sight condition at the given
     * location.
     */
    @FloatRange(from = 0.0f, to = 1.0f)
    private final float mProbSatIsLos;

    /**
     * Excess path length to be subtracted from pseudorange before using it in calculating location.
     */
    @FloatRange(from = 0.0f)
    private final float mExcessPathLengthMeters;

    /** Error estimate (1-sigma) for the Excess path length estimate */
    @FloatRange(from = 0.0f)
    private final float mExcessPathLengthUncertaintyMeters;

    /**
     * Defines the reflecting plane location and azimuth information
     *
     * <p>The flag HAS_REFLECTING_PLANE will be used to set this value to invalid if the satellite
     * signal goes through multiple reflections or if reflection plane serving is not supported.
     */
    @Nullable
    private final GnssReflectingPlane mReflectingPlane;

    private GnssSingleSatCorrection(int singleSatCorrectionFlags, int constellationType, int satId,
            float carrierFrequencyHz, float probSatIsLos, float excessPathLengthMeters,
            float excessPathLengthUncertaintyMeters, GnssReflectingPlane reflectingPlane) {
        mSingleSatCorrectionFlags = singleSatCorrectionFlags;
        mConstellationType = constellationType;
        mSatId = satId;
        mCarrierFrequencyHz = carrierFrequencyHz;
        mProbSatIsLos = probSatIsLos;
        mExcessPathLengthMeters = excessPathLengthMeters;
        mExcessPathLengthUncertaintyMeters = excessPathLengthUncertaintyMeters;
        mReflectingPlane = reflectingPlane;
    }

    /**
     * Gets a bitmask of fields present in this object
     *
     * @hide
     */
    public int getSingleSatelliteCorrectionFlags() {
        return mSingleSatCorrectionFlags;
    }

    /**
     * Gets the constellation type.
     *
     * <p>The return value is one of those constants with {@code CONSTELLATION_} prefix in {@link
     * GnssStatus}.
     */
    @GnssStatus.ConstellationType
    public int getConstellationType() {
        return mConstellationType;
    }

    /**
     * Gets the satellite ID.
     *
     * <p>Interpretation depends on {@link #getConstellationType()}. See {@link
     * GnssStatus#getSvid(int)}.
     */
    @IntRange(from = 0)
    public int getSatelliteId() {
        return mSatId;
    }

    /**
     * Gets the carrier frequency of the tracked signal.
     *
     * <p>For example it can be the GPS central frequency for L1 = 1575.45 MHz, or L2 = 1227.60 MHz,
     * L5 = 1176.45 MHz, varying GLO channels, etc.
     *
     * <p>For an L1, L5 receiver tracking a satellite on L1 and L5 at the same time, two correction
     * objects will be reported for this same satellite, in one of the correction objects, all the
     * values related to L1 will be filled, and in the other all of the values related to L5 will be
     * filled.
     *
     * @return the carrier frequency of the signal tracked in Hz.
     */
    @FloatRange(from = 0.0f,  fromInclusive = false)
    public float getCarrierFrequencyHz() {
        return mCarrierFrequencyHz;
    }

    /**
     * Returns the probability that the satellite is in line-of-sight condition at the given
     * location.
     */
    @FloatRange(from = 0.0f, to = 1.0f)
    public float getProbabilityLineOfSight() {
        return mProbSatIsLos;
    }

    /**
     * Returns the Excess path length to be subtracted from pseudorange before using it in
     * calculating location.
     */
    @FloatRange(from = 0.0f)
    public float getExcessPathLengthMeters() {
        return mExcessPathLengthMeters;
    }

    /** Returns the error estimate (1-sigma) for the Excess path length estimate */
    @FloatRange(from = 0.0f)
    public float getExcessPathLengthUncertaintyMeters() {
        return mExcessPathLengthUncertaintyMeters;
    }

    /**
     * Returns the reflecting plane characteristics at which the signal has bounced
     *
     * <p>The flag HAS_REFLECTING_PLANE will be used to set this value to invalid if the satellite
     * signal goes through multiple reflections or if reflection plane serving is not supported
     */
    @Nullable
    public GnssReflectingPlane getReflectingPlane() {
        return mReflectingPlane;
    }

    /** Returns {@code true} if {@link #getProbabilityLineOfSight()} is valid. */
    public boolean hasValidSatelliteLineOfSight() {
        return (mSingleSatCorrectionFlags & HAS_PROB_SAT_IS_LOS_MASK) != 0;
    }

    /** Returns {@code true} if {@link #getExcessPathLengthMeters()} is valid. */
    public boolean hasExcessPathLength() {
        return (mSingleSatCorrectionFlags & HAS_EXCESS_PATH_LENGTH_MASK) != 0;
    }

    /** Returns {@code true} if {@link #getExcessPathLengthUncertaintyMeters()} is valid. */
    public boolean hasExcessPathLengthUncertainty() {
        return (mSingleSatCorrectionFlags & HAS_EXCESS_PATH_LENGTH_UNC_MASK) != 0;
    }

    /** Returns {@code true} if {@link #getReflectingPlane()} is valid. */
    public boolean hasReflectingPlane() {
        return (mSingleSatCorrectionFlags & HAS_REFLECTING_PLANE_MASK) != 0;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeInt(mSingleSatCorrectionFlags);
        parcel.writeInt(mConstellationType);
        parcel.writeInt(mSatId);
        parcel.writeFloat(mCarrierFrequencyHz);
        if (hasValidSatelliteLineOfSight()) {
            parcel.writeFloat(mProbSatIsLos);
        }
        if (hasExcessPathLength()) {
            parcel.writeFloat(mExcessPathLengthMeters);
        }
        if (hasExcessPathLengthUncertainty()) {
            parcel.writeFloat(mExcessPathLengthUncertaintyMeters);
        }
        if (hasReflectingPlane()) {
            mReflectingPlane.writeToParcel(parcel, flags);
        }
    }

    public static final Creator<GnssSingleSatCorrection> CREATOR =
            new Creator<GnssSingleSatCorrection>() {
                @Override
                @NonNull
                public GnssSingleSatCorrection createFromParcel(@NonNull Parcel parcel) {
                    int singleSatCorrectionFlags = parcel.readInt();
                    int constellationType = parcel.readInt();
                    int satId = parcel.readInt();
                    float carrierFrequencyHz = parcel.readFloat();
                    float probSatIsLos = (singleSatCorrectionFlags & HAS_PROB_SAT_IS_LOS_MASK) != 0
                            ? parcel.readFloat() : 0;
                    float excessPathLengthMeters =
                            (singleSatCorrectionFlags & HAS_EXCESS_PATH_LENGTH_MASK) != 0
                                    ? parcel.readFloat() : 0;
                    float excessPathLengthUncertaintyMeters =
                            (singleSatCorrectionFlags & HAS_EXCESS_PATH_LENGTH_UNC_MASK) != 0
                                    ? parcel.readFloat() : 0;
                    GnssReflectingPlane reflectingPlane =
                            (singleSatCorrectionFlags & HAS_REFLECTING_PLANE_MASK) != 0
                                    ? GnssReflectingPlane.CREATOR.createFromParcel(parcel) : null;
                    return new GnssSingleSatCorrection(singleSatCorrectionFlags, constellationType,
                            satId, carrierFrequencyHz, probSatIsLos, excessPathLengthMeters,
                            excessPathLengthUncertaintyMeters, reflectingPlane);
                }

                @Override
                public GnssSingleSatCorrection[] newArray(int i) {
                    return new GnssSingleSatCorrection[i];
                }
            };

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof GnssSingleSatCorrection)) {
            return false;
        }

        GnssSingleSatCorrection other = (GnssSingleSatCorrection) obj;
        if (mConstellationType != other.mConstellationType) {
            return false;
        }
        if (mSatId != other.mSatId) {
            return false;
        }
        if (Float.compare(mCarrierFrequencyHz, other.mCarrierFrequencyHz) != 0) {
            return false;
        }

        if (hasValidSatelliteLineOfSight() != other.hasValidSatelliteLineOfSight()) {
            return false;
        }
        if (hasValidSatelliteLineOfSight()
                && Float.compare(mProbSatIsLos, other.mProbSatIsLos) != 0) {
            return false;
        }

        if (hasExcessPathLength() != other.hasExcessPathLength()) {
            return false;
        }
        if (hasExcessPathLength()
                && Float.compare(mExcessPathLengthMeters, other.mExcessPathLengthMeters) != 0) {
            return false;
        }

        if (hasExcessPathLengthUncertainty() != other.hasExcessPathLengthUncertainty()) {
            return false;
        }
        if (hasExcessPathLengthUncertainty() && Float.compare(mExcessPathLengthUncertaintyMeters,
                other.mExcessPathLengthUncertaintyMeters) != 0) {
            return false;
        }

        if (hasReflectingPlane() != other.hasReflectingPlane()) {
            return false;
        }
        if (hasReflectingPlane()
                && !mReflectingPlane.equals(other.mReflectingPlane)) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSingleSatCorrectionFlags,
                mConstellationType,
                mSatId,
                mCarrierFrequencyHz,
                mProbSatIsLos,
                mExcessPathLengthMeters,
                mExcessPathLengthUncertaintyMeters,
                mReflectingPlane);
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("GnssSingleSatCorrection:[");
        builder.append(" ConstellationType=").append(mConstellationType);
        builder.append(" SatId=").append(mSatId);
        builder.append(" CarrierFrequencyHz=").append(mCarrierFrequencyHz);
        if (hasValidSatelliteLineOfSight()) {
            builder.append(" ProbSatIsLos=").append(mProbSatIsLos);
        }
        if (hasExcessPathLength()) {
            builder.append(" ExcessPathLengthMeters=").append(mExcessPathLengthMeters);
        }
        if (hasExcessPathLengthUncertainty()) {
            builder.append(" ExcessPathLengthUncertaintyMeters=").append(
                    mExcessPathLengthUncertaintyMeters);
        }
        if (hasReflectingPlane()) {
            builder.append(" ReflectingPlane=").append(mReflectingPlane);
        }
        builder.append(']');
        return builder.toString();
    }

    /** Builder for {@link GnssSingleSatCorrection} */
    public static final class Builder {

        /**
         * For documentation of below fields, see corresponding fields in {@link
         * GnssSingleSatCorrection}.
         */
        private int mSingleSatCorrectionFlags;

        private int mConstellationType;
        private int mSatId;
        private float mCarrierFrequencyHz;
        private float mProbSatIsLos;
        private float mExcessPathLengthMeters;
        private float mExcessPathLengthUncertaintyMeters;
        @Nullable
        private GnssReflectingPlane mReflectingPlane;

        /** Sets the constellation type. */
        @NonNull public Builder setConstellationType(
                @GnssStatus.ConstellationType int constellationType) {
            mConstellationType = constellationType;
            return this;
        }

        /** Sets the Satellite ID defined in the ICD of the given constellation. */
        @NonNull public Builder setSatelliteId(@IntRange(from = 0) int satId) {
            Preconditions.checkArgumentNonnegative(satId, "satId should be non-negative.");
            mSatId = satId;
            return this;
        }

        /** Sets the Carrier frequency in Hz. */
        @NonNull public Builder setCarrierFrequencyHz(
                @FloatRange(from = 0.0f,  fromInclusive = false) float carrierFrequencyHz) {
            Preconditions.checkArgument(
                    carrierFrequencyHz >= 0, "carrierFrequencyHz should be non-negative.");
            mCarrierFrequencyHz = carrierFrequencyHz;
            return this;
        }

        /**
         * Sets the line-of-sight probability of the satellite at the given location in the range
         * between 0 and 1.
         */
        @NonNull public Builder setProbabilityLineOfSight(
                @FloatRange(from = 0.0f, to = 1.0f) float probSatIsLos) {
            Preconditions.checkArgumentInRange(
                    probSatIsLos, 0, 1, "probSatIsLos should be between 0 and 1.");
            mProbSatIsLos = probSatIsLos;
            mSingleSatCorrectionFlags |= HAS_PROB_SAT_IS_LOS_MASK;
            return this;
        }

        /**
         * Clears the line-of-sight probability of the satellite at the given location.
         *
         * <p>This is to negate {@link #setProbabilityLineOfSight} call.
         */
        @NonNull public Builder clearProbabilityLineOfSight() {
            mProbSatIsLos = 0;
            mSingleSatCorrectionFlags &= ~HAS_PROB_SAT_IS_LOS_MASK;
            return this;
        }

        /**
         * Sets the Excess path length to be subtracted from pseudorange before using it in
         * calculating location.
         */
        @NonNull public Builder setExcessPathLengthMeters(
                @FloatRange(from = 0.0f) float excessPathLengthMeters) {
            Preconditions.checkArgument(excessPathLengthMeters >= 0,
                    "excessPathLengthMeters should be non-negative.");
            mExcessPathLengthMeters = excessPathLengthMeters;
            mSingleSatCorrectionFlags |= HAS_EXCESS_PATH_LENGTH_MASK;
            return this;
        }

        /**
         * Clears the Excess path length.
         *
         * <p>This is to negate {@link #setExcessPathLengthMeters} call.
         */
        @NonNull public Builder clearExcessPathLengthMeters() {
            mExcessPathLengthMeters = 0;
            mSingleSatCorrectionFlags &= ~HAS_EXCESS_PATH_LENGTH_MASK;
            return this;
        }

        /** Sets the error estimate (1-sigma) for the Excess path length estimate */
        @NonNull public Builder setExcessPathLengthUncertaintyMeters(
                @FloatRange(from = 0.0f) float excessPathLengthUncertaintyMeters) {
            Preconditions.checkArgument(excessPathLengthUncertaintyMeters >= 0,
                    "excessPathLengthUncertaintyMeters should be non-negative.");
            mExcessPathLengthUncertaintyMeters = excessPathLengthUncertaintyMeters;
            mSingleSatCorrectionFlags |= HAS_EXCESS_PATH_LENGTH_UNC_MASK;
            return this;
        }

        /**
         * Clears the error estimate (1-sigma) for the Excess path length estimate
         *
         * <p>This is to negate {@link #setExcessPathLengthUncertaintyMeters} call.
         */
        @NonNull public Builder clearExcessPathLengthUncertaintyMeters() {
            mExcessPathLengthUncertaintyMeters = 0;
            mSingleSatCorrectionFlags &= ~HAS_EXCESS_PATH_LENGTH_UNC_MASK;
            return this;
        }

        /** Sets the reflecting plane information */
        @NonNull public Builder setReflectingPlane(@Nullable GnssReflectingPlane reflectingPlane) {
            mReflectingPlane = reflectingPlane;
            if (reflectingPlane != null) {
                mSingleSatCorrectionFlags |= HAS_REFLECTING_PLANE_MASK;
            } else {
                mSingleSatCorrectionFlags &= ~HAS_REFLECTING_PLANE_MASK;
            }
            return this;
        }

        /** Builds a {@link GnssSingleSatCorrection} instance as specified by this builder. */
        @NonNull public GnssSingleSatCorrection build() {
            return new GnssSingleSatCorrection(mSingleSatCorrectionFlags,
                    mConstellationType,
                    mSatId,
                    mCarrierFrequencyHz,
                    mProbSatIsLos,
                    mExcessPathLengthMeters,
                    mExcessPathLengthUncertaintyMeters,
                    mReflectingPlane);
        }
    }
}
