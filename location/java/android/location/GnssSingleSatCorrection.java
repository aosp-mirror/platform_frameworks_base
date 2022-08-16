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

import static android.hardware.gnss.measurement_corrections.SingleSatCorrection.SINGLE_SAT_CORRECTION_HAS_COMBINED_ATTENUATION;
import static android.hardware.gnss.measurement_corrections.SingleSatCorrection.SINGLE_SAT_CORRECTION_HAS_COMBINED_EXCESS_PATH_LENGTH;
import static android.hardware.gnss.measurement_corrections.SingleSatCorrection.SINGLE_SAT_CORRECTION_HAS_COMBINED_EXCESS_PATH_LENGTH_UNC;
import static android.hardware.gnss.measurement_corrections.SingleSatCorrection.SINGLE_SAT_CORRECTION_HAS_SAT_IS_LOS_PROBABILITY;

import android.annotation.FloatRange;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A container with measurement corrections for a single visible satellite
 *
 * @hide
 */
@SystemApi
public final class GnssSingleSatCorrection implements Parcelable {

    private static final int HAS_PROB_SAT_IS_LOS_MASK =
            SINGLE_SAT_CORRECTION_HAS_SAT_IS_LOS_PROBABILITY;
    private static final int HAS_COMBINED_EXCESS_PATH_LENGTH_MASK =
            SINGLE_SAT_CORRECTION_HAS_COMBINED_EXCESS_PATH_LENGTH;
    private static final int HAS_COMBINED_EXCESS_PATH_LENGTH_UNC_MASK =
            SINGLE_SAT_CORRECTION_HAS_COMBINED_EXCESS_PATH_LENGTH_UNC;
    private static final int HAS_COMBINED_ATTENUATION_MASK =
            SINGLE_SAT_CORRECTION_HAS_COMBINED_ATTENUATION;

    /* A bitmask of fields present in this object (see HAS_* constants defined above). */
    private final int mSingleSatCorrectionFlags;

    private final int mConstellationType;
    private final int mSatId;
    private final float mCarrierFrequencyHz;
    private final float mProbSatIsLos;
    private final float mCombinedExcessPathLengthMeters;
    private final float mCombinedExcessPathLengthUncertaintyMeters;
    private final float mCombinedAttenuationDb;

    @NonNull
    private final List<GnssExcessPathInfo> mGnssExcessPathInfoList;

    private GnssSingleSatCorrection(int singleSatCorrectionFlags, int constellationType, int satId,
            float carrierFrequencyHz, float probSatIsLos, float excessPathLengthMeters,
            float excessPathLengthUncertaintyMeters,
            float combinedAttenuationDb,
            @NonNull List<GnssExcessPathInfo> gnssExcessPathInfoList) {
        mSingleSatCorrectionFlags = singleSatCorrectionFlags;
        mConstellationType = constellationType;
        mSatId = satId;
        mCarrierFrequencyHz = carrierFrequencyHz;
        mProbSatIsLos = probSatIsLos;
        mCombinedExcessPathLengthMeters = excessPathLengthMeters;
        mCombinedExcessPathLengthUncertaintyMeters = excessPathLengthUncertaintyMeters;
        mCombinedAttenuationDb = combinedAttenuationDb;
        mGnssExcessPathInfoList = gnssExcessPathInfoList;
    }

    /**
     * Gets a bitmask of fields present in this object.
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
     * Returns the combined excess path length to be subtracted from pseudorange before using it in
     * calculating location.
     */
    @FloatRange(from = 0.0f)
    public float getExcessPathLengthMeters() {
        return mCombinedExcessPathLengthMeters;
    }

    /** Returns the error estimate (1-sigma) for the combined excess path length estimate. */
    @FloatRange(from = 0.0f)
    public float getExcessPathLengthUncertaintyMeters() {
        return mCombinedExcessPathLengthUncertaintyMeters;
    }

    /**
     * Returns the combined expected reduction of signal strength for this satellite in
     * non-negative dB.
     */
    @FloatRange(from = 0.0f)
    public float getCombinedAttenuationDb() {
        return mCombinedAttenuationDb;
    }

    /**
     * Returns the reflecting plane characteristics at which the signal has bounced.
     *
     * @deprecated Combined excess path does not have a reflecting plane.
     */
    @Nullable
    @Deprecated
    public GnssReflectingPlane getReflectingPlane() {
        return null;
    }

    /**
     * Returns the list of {@link GnssExcessPathInfo} associated with this satellite signal.
     */
    @NonNull
    public List<GnssExcessPathInfo> getGnssExcessPathInfoList() {
        return mGnssExcessPathInfoList;
    }

    /** Returns {@code true} if {@link #getProbabilityLineOfSight()} is valid. */
    public boolean hasValidSatelliteLineOfSight() {
        return (mSingleSatCorrectionFlags & HAS_PROB_SAT_IS_LOS_MASK) != 0;
    }

    /** Returns {@code true} if {@link #getExcessPathLengthMeters()} is valid. */
    public boolean hasExcessPathLength() {
        return (mSingleSatCorrectionFlags & HAS_COMBINED_EXCESS_PATH_LENGTH_MASK) != 0;
    }

    /** Returns {@code true} if {@link #getExcessPathLengthUncertaintyMeters()} is valid. */
    public boolean hasExcessPathLengthUncertainty() {
        return (mSingleSatCorrectionFlags & HAS_COMBINED_EXCESS_PATH_LENGTH_UNC_MASK) != 0;
    }

    /**
     * Returns {@code true} if {@link #getReflectingPlane()} is valid.
     *
     * @deprecated Combined excess path does not have a reflecting plane.
     */
    @Deprecated
    public boolean hasReflectingPlane() {
        return false;
    }

    /** Returns {@code true} if {@link #getCombinedAttenuationDb()} is valid. */
    public boolean hasCombinedAttenuation() {
        return (mSingleSatCorrectionFlags & HAS_COMBINED_ATTENUATION_MASK) != 0;
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
            parcel.writeFloat(mCombinedExcessPathLengthMeters);
        }
        if (hasExcessPathLengthUncertainty()) {
            parcel.writeFloat(mCombinedExcessPathLengthUncertaintyMeters);
        }
        if (hasCombinedAttenuation()) {
            parcel.writeFloat(mCombinedAttenuationDb);
        }
        parcel.writeTypedList(mGnssExcessPathInfoList);
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
                    float combinedExcessPathLengthMeters =
                            (singleSatCorrectionFlags & HAS_COMBINED_EXCESS_PATH_LENGTH_MASK) != 0
                                    ? parcel.readFloat() : 0;
                    float combinedExcessPathLengthUncertaintyMeters =
                            (singleSatCorrectionFlags & HAS_COMBINED_EXCESS_PATH_LENGTH_UNC_MASK)
                                    != 0 ? parcel.readFloat() : 0;
                    float combinedAttenuationDb =
                            (singleSatCorrectionFlags & HAS_COMBINED_ATTENUATION_MASK) != 0
                                    ? parcel.readFloat() : 0;
                    List<GnssExcessPathInfo> gnssExcessPathInfoList = parcel.createTypedArrayList(
                            GnssExcessPathInfo.CREATOR);
                    return new GnssSingleSatCorrection(singleSatCorrectionFlags, constellationType,
                            satId, carrierFrequencyHz, probSatIsLos, combinedExcessPathLengthMeters,
                            combinedExcessPathLengthUncertaintyMeters, combinedAttenuationDb,
                            gnssExcessPathInfoList);
                }

                @Override
                public GnssSingleSatCorrection[] newArray(int i) {
                    return new GnssSingleSatCorrection[i];
                }
            };

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof GnssSingleSatCorrection) {
            GnssSingleSatCorrection that = (GnssSingleSatCorrection) obj;
            return this.mSingleSatCorrectionFlags == that.mSingleSatCorrectionFlags
                    && this.mConstellationType == that.mConstellationType
                    && this.mSatId == that.mSatId
                    && Float.compare(mCarrierFrequencyHz, that.mCarrierFrequencyHz) == 0
                    && (!hasValidSatelliteLineOfSight() || Float.compare(mProbSatIsLos,
                    that.mProbSatIsLos) == 0)
                    && (!hasExcessPathLength() || Float.compare(mCombinedExcessPathLengthMeters,
                    that.mCombinedExcessPathLengthMeters) == 0)
                    && (!hasExcessPathLengthUncertainty() || Float.compare(
                    mCombinedExcessPathLengthUncertaintyMeters,
                    that.mCombinedExcessPathLengthUncertaintyMeters) == 0)
                    && (!hasCombinedAttenuation() || Float.compare(mCombinedAttenuationDb,
                    that.mCombinedAttenuationDb) == 0)
                    && mGnssExcessPathInfoList.equals(that.mGnssExcessPathInfoList);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSingleSatCorrectionFlags,
                mConstellationType,
                mSatId,
                mCarrierFrequencyHz,
                mProbSatIsLos,
                mCombinedExcessPathLengthMeters,
                mCombinedExcessPathLengthUncertaintyMeters,
                mCombinedAttenuationDb,
                mGnssExcessPathInfoList);
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
            builder.append(" CombinedExcessPathLengthMeters=").append(
                    mCombinedExcessPathLengthMeters);
        }
        if (hasExcessPathLengthUncertainty()) {
            builder.append(" CombinedExcessPathLengthUncertaintyMeters=").append(
                    mCombinedExcessPathLengthUncertaintyMeters);
        }
        if (hasCombinedAttenuation()) {
            builder.append(" CombinedAttenuationDb=").append(
                    mCombinedAttenuationDb);
        }
        if (!mGnssExcessPathInfoList.isEmpty()) {
            builder.append(' ').append(mGnssExcessPathInfoList.toString());
        }
        builder.append(']');
        return builder.toString();
    }

    /** Builder for {@link GnssSingleSatCorrection} */
    public static final class Builder {
        private int mSingleSatCorrectionFlags;
        private int mConstellationType;
        private int mSatId;
        private float mCarrierFrequencyHz;
        private float mProbSatIsLos;
        private float mCombinedExcessPathLengthMeters;
        private float mCombinedExcessPathLengthUncertaintyMeters;
        private float mCombinedAttenuationDb;
        @NonNull
        private List<GnssExcessPathInfo> mGnssExcessInfoList = new ArrayList<>();

        /** Sets the constellation type. */
        @NonNull public Builder setConstellationType(
                @GnssStatus.ConstellationType int constellationType) {
            mConstellationType = constellationType;
            return this;
        }

        /** Sets the satellite ID defined in the ICD of the given constellation. */
        @NonNull public Builder setSatelliteId(@IntRange(from = 0) int satId) {
            Preconditions.checkArgumentNonnegative(satId, "satId should be non-negative.");
            mSatId = satId;
            return this;
        }

        /** Sets the carrier frequency in Hz. */
        @NonNull public Builder setCarrierFrequencyHz(
                @FloatRange(from = 0.0f,  fromInclusive = false) float carrierFrequencyHz) {
            Preconditions.checkArgumentInRange(
                    carrierFrequencyHz, 0, Float.MAX_VALUE, "carrierFrequencyHz");
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
         * Sets the combined excess path length to be subtracted from pseudorange before using it in
         * calculating location.
         */
        @NonNull
        public Builder setExcessPathLengthMeters(
                @FloatRange(from = 0.0f) float combinedExcessPathLengthMeters) {
            Preconditions.checkArgumentInRange(combinedExcessPathLengthMeters, 0, Float.MAX_VALUE,
                    "excessPathLengthMeters");
            mCombinedExcessPathLengthMeters = combinedExcessPathLengthMeters;
            mSingleSatCorrectionFlags |= HAS_COMBINED_EXCESS_PATH_LENGTH_MASK;
            return this;
        }

        /**
         * Clears the combined excess path length.
         *
         * <p>This is to negate {@link #setExcessPathLengthMeters} call.
         */
        @NonNull public Builder clearExcessPathLengthMeters() {
            mCombinedExcessPathLengthMeters = 0;
            mSingleSatCorrectionFlags &= ~HAS_COMBINED_EXCESS_PATH_LENGTH_MASK;
            return this;
        }

        /** Sets the error estimate (1-sigma) for the combined excess path length estimate. */
        @NonNull public Builder setExcessPathLengthUncertaintyMeters(
                @FloatRange(from = 0.0f) float combinedExcessPathLengthUncertaintyMeters) {
            Preconditions.checkArgumentInRange(combinedExcessPathLengthUncertaintyMeters, 0,
                    Float.MAX_VALUE, "excessPathLengthUncertaintyMeters");
            mCombinedExcessPathLengthUncertaintyMeters = combinedExcessPathLengthUncertaintyMeters;
            mSingleSatCorrectionFlags |= HAS_COMBINED_EXCESS_PATH_LENGTH_UNC_MASK;
            return this;
        }

        /**
         * Clears the error estimate (1-sigma) for the combined excess path length estimate.
         *
         * <p>This is to negate {@link #setExcessPathLengthUncertaintyMeters} call.
         */
        @NonNull public Builder clearExcessPathLengthUncertaintyMeters() {
            mCombinedExcessPathLengthUncertaintyMeters = 0;
            mSingleSatCorrectionFlags &= ~HAS_COMBINED_EXCESS_PATH_LENGTH_UNC_MASK;
            return this;
        }

        /**
         * Sets the combined attenuation in Db.
         */
        @NonNull public Builder setCombinedAttenuationDb(
                @FloatRange(from = 0.0f) float combinedAttenuationDb) {
            Preconditions.checkArgumentInRange(combinedAttenuationDb, 0, Float.MAX_VALUE,
                    "combinedAttenuationDb");
            mCombinedAttenuationDb = combinedAttenuationDb;
            mSingleSatCorrectionFlags |= HAS_COMBINED_ATTENUATION_MASK;
            return this;
        }

        /**
         * Clears the combined attenuation.
         *
         * <p>This is to negate {@link #setCombinedAttenuationDb} call.
         */
        @NonNull public Builder clearCombinedAttenuationDb() {
            mCombinedAttenuationDb = 0;
            mSingleSatCorrectionFlags &= ~HAS_COMBINED_ATTENUATION_MASK;
            return this;
        }

        /**
         * Sets the reflecting plane information.
         *
         * @deprecated Combined excess path does not have a reflecting plane.
         */
        @Deprecated
        @NonNull public Builder setReflectingPlane(@Nullable GnssReflectingPlane reflectingPlane) {
            return this;
        }

        /**
         * Sets the collection of {@link GnssExcessPathInfo}.
         */
        @NonNull
        public Builder setGnssExcessPathInfoList(@NonNull List<GnssExcessPathInfo> infoList) {
            mGnssExcessInfoList = new ArrayList<>(infoList);
            return this;
        }

        /** Builds a {@link GnssSingleSatCorrection} instance as specified by this builder. */
        @NonNull public GnssSingleSatCorrection build() {
            return new GnssSingleSatCorrection(mSingleSatCorrectionFlags,
                    mConstellationType,
                    mSatId,
                    mCarrierFrequencyHz,
                    mProbSatIsLos,
                    mCombinedExcessPathLengthMeters,
                    mCombinedExcessPathLengthUncertaintyMeters,
                    mCombinedAttenuationDb,
                    mGnssExcessInfoList);
        }
    }
}
