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
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A class that contains information about a GNSS antenna. GNSS antenna characteristics can change
 * with device configuration, such as when a device is folded open or closed. Antenna information is
 * delivered to registered instances of {@link Listener}.
 *
 * <p> Antenna info parameters are measured for each specific device model by the device
 * manufacturers and provided to the Android framework.
 */
public final class GnssAntennaInfo implements Parcelable {
    private final double mCarrierFrequencyMHz;
    private final PhaseCenterOffset mPhaseCenterOffset;
    private final @Nullable SphericalCorrections mPhaseCenterVariationCorrections;
    private final @Nullable SphericalCorrections mSignalGainCorrections;

    /**
     * Used for receiving GNSS antenna info from the GNSS engine.
     */
    public interface Listener {
        /**
         * Invoked on a change to GNSS antenna info.
         */
        void onGnssAntennaInfoReceived(@NonNull List<GnssAntennaInfo> gnssAntennaInfos);
    }

    /**
     * Class containing information about the antenna phase center offset (PCO). PCO is defined with
     * respect to the origin of the Android sensor coordinate system, e.g., center of primary screen
     * for mobiles - see sensor or form factor documents for details. Uncertainties are reported
     * to 1-sigma.
     */
    public static final class PhaseCenterOffset implements Parcelable {
        private final double mOffsetXMm;
        private final double mOffsetXUncertaintyMm;
        private final double mOffsetYMm;
        private final double mOffsetYUncertaintyMm;
        private final double mOffsetZMm;
        private final double mOffsetZUncertaintyMm;

        public PhaseCenterOffset(
                double offsetXMm, double offsetXUncertaintyMm,
                double offsetYMm, double offsetYUncertaintyMm,
                double offsetZMm, double offsetZUncertaintyMm) {
            mOffsetXMm = offsetXMm;
            mOffsetYMm = offsetYMm;
            mOffsetZMm = offsetZMm;
            mOffsetXUncertaintyMm = offsetXUncertaintyMm;
            mOffsetYUncertaintyMm = offsetYUncertaintyMm;
            mOffsetZUncertaintyMm = offsetZUncertaintyMm;
        }

        public static final @NonNull Creator<PhaseCenterOffset> CREATOR =
                new Creator<PhaseCenterOffset>() {
                    @Override
                    public PhaseCenterOffset createFromParcel(Parcel in) {
                        return new PhaseCenterOffset(
                                in.readDouble(),
                                in.readDouble(),
                                in.readDouble(),
                                in.readDouble(),
                                in.readDouble(),
                                in.readDouble()
                        );
                    }

                    @Override
                    public PhaseCenterOffset[] newArray(int size) {
                        return new PhaseCenterOffset[size];
                    }
                };

        /**
         * Returns the x-axis offset of the phase center from the origin of the Android sensor
         * coordinate system, in millimeters.
         */
        @FloatRange()
        public double getXOffsetMm() {
            return mOffsetXMm;
        }

        /**
         * Returns the 1-sigma uncertainty of the x-axis offset of the phase center from the origin
         * of the Android sensor coordinate system, in millimeters.
         */
        @FloatRange()
        public double getXOffsetUncertaintyMm() {
            return mOffsetXUncertaintyMm;
        }

        /**
         * Returns the y-axis offset of the phase center from the origin of the Android sensor
         * coordinate system, in millimeters.
         */
        @FloatRange()
        public double getYOffsetMm() {
            return mOffsetYMm;
        }

        /**
         * Returns the 1-sigma uncertainty of the y-axis offset of the phase center from the origin
         * of the Android sensor coordinate system, in millimeters.
         */
        @FloatRange()
        public double getYOffsetUncertaintyMm() {
            return mOffsetYUncertaintyMm;
        }

        /**
         * Returns the z-axis offset of the phase center from the origin of the Android sensor
         * coordinate system, in millimeters.
         */
        @FloatRange()
        public double getZOffsetMm() {
            return mOffsetZMm;
        }

        /**
         * Returns the 1-sigma uncertainty of the z-axis offset of the phase center from the origin
         * of the Android sensor coordinate system, in millimeters.
         */
        @FloatRange()
        public double getZOffsetUncertaintyMm() {
            return mOffsetZUncertaintyMm;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeDouble(mOffsetXMm);
            dest.writeDouble(mOffsetXUncertaintyMm);
            dest.writeDouble(mOffsetYMm);
            dest.writeDouble(mOffsetYUncertaintyMm);
            dest.writeDouble(mOffsetZMm);
            dest.writeDouble(mOffsetZUncertaintyMm);
        }

        @Override
        public String toString() {
            return "PhaseCenterOffset{"
                    + "OffsetXMm=" + mOffsetXMm + " +/-" + mOffsetXUncertaintyMm
                    + ", OffsetYMm=" + mOffsetYMm + " +/-" + mOffsetYUncertaintyMm
                    + ", OffsetZMm=" + mOffsetZMm + " +/-" + mOffsetZUncertaintyMm
                    + '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof PhaseCenterOffset)) {
                return false;
            }
            PhaseCenterOffset that = (PhaseCenterOffset) o;
            return Double.compare(that.mOffsetXMm, mOffsetXMm) == 0
                    && Double.compare(that.mOffsetXUncertaintyMm, mOffsetXUncertaintyMm) == 0
                    && Double.compare(that.mOffsetYMm, mOffsetYMm) == 0
                    && Double.compare(that.mOffsetYUncertaintyMm, mOffsetYUncertaintyMm) == 0
                    && Double.compare(that.mOffsetZMm, mOffsetZMm) == 0
                    && Double.compare(that.mOffsetZUncertaintyMm, mOffsetZUncertaintyMm) == 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mOffsetXMm, mOffsetYMm, mOffsetZMm);
        }
    }

    /**
     * Represents corrections on a spherical mapping. Corrections are added to measurements to
     * obtain the corrected values.
     *
     * The corrections and associated (1-sigma) uncertainties are represented by respect 2D arrays.
     *
     * Each row (major indices) represents a fixed theta. The first row corresponds to a
     * theta angle of 0 degrees. The last row corresponds to a theta angle of (360 - deltaTheta)
     * degrees, where deltaTheta is the regular spacing between azimuthal angles, i.e., deltaTheta
     * = 360 / (number of rows).
     *
     * The columns (minor indices) represent fixed zenith angles, beginning at 0 degrees and ending
     * at 180 degrees. They are separated by deltaPhi, the regular spacing between zenith angles,
     * i.e., deltaPhi = 180 / (number of columns - 1).
     */
    public static final class SphericalCorrections implements Parcelable {

        private final int mNumRows;
        private final int mNumColumns;
        private final double[][] mCorrections;
        private final double[][] mCorrectionUncertainties;

        public SphericalCorrections(@NonNull double[][] corrections,
                @NonNull double[][] correctionUncertainties) {
            if (corrections.length != correctionUncertainties.length || corrections.length < 1) {
                throw new IllegalArgumentException("correction and uncertainty arrays must have "
                        + "the same (non-zero) dimensions");
            }

            mNumRows = corrections.length;
            mNumColumns = corrections[0].length;
            for (int i = 0; i < corrections.length; i++) {
                if (corrections[i].length != mNumColumns
                        || correctionUncertainties[i].length != mNumColumns || mNumColumns < 2) {
                    throw new IllegalArgumentException("correction and uncertainty arrays must all "
                            + " have the same (greater than 2) number of columns");
                }
            }

            mCorrections = corrections;
            mCorrectionUncertainties = correctionUncertainties;
        }

        private SphericalCorrections(Parcel in) {
            int numRows = in.readInt();
            int numColumns = in.readInt();

            double[][] corrections =
                    new double[numRows][numColumns];
            double[][] correctionUncertainties =
                    new double[numRows][numColumns];

            for (int row = 0; row < numRows; row++) {
                for (int col = 0; col < numColumns; col++) {
                    corrections[row][col] = in.readDouble();
                    correctionUncertainties[row][col] = in.readDouble();
                }
            }

            mNumRows = numRows;
            mNumColumns = numColumns;
            mCorrections = corrections;
            mCorrectionUncertainties = correctionUncertainties;
        }

        /**
         * Array representing corrections on a spherical mapping. Corrections are added to
         * measurements to obtain the corrected values.
         *
         * Each row (major indices) represents a fixed theta. The first row corresponds to a
         * theta angle of 0 degrees. The last row corresponds to a theta angle of (360 - deltaTheta)
         * degrees, where deltaTheta is the regular spacing between azimuthal angles, i.e.,
         * deltaTheta = 360 / (number of rows).
         *
         * The columns (minor indices) represent fixed zenith angles, beginning at 0 degrees and
         * ending at 180 degrees. They are separated by deltaPhi, the regular spacing between zenith
         * angles, i.e., deltaPhi = 180 / (number of columns - 1).
         */
        @NonNull
        public double[][] getCorrectionsArray() {
            return mCorrections;
        }

        /**
         * Array representing uncertainty on corrections on a spherical mapping.
         *
         * Each row (major indices) represents a fixed theta. The first row corresponds to a
         * theta angle of 0 degrees. The last row corresponds to a theta angle of (360 - deltaTheta)
         * degrees, where deltaTheta is the regular spacing between azimuthal angles, i.e.,
         * deltaTheta = 360 / (number of rows).
         *
         * The columns (minor indices) represent fixed zenith angles, beginning at 0 degrees and
         * ending at 180 degrees. They are separated by deltaPhi, the regular spacing between zenith
         * angles, i.e., deltaPhi = 180 / (number of columns - 1).
         */
        @NonNull
        public double[][] getCorrectionUncertaintiesArray() {
            return mCorrectionUncertainties;
        }

        /**
         * The fixed theta angle separation between successive rows.
         */
        @FloatRange(from = 0.0f, to = 360.0f)
        public double getDeltaTheta() {
            return 360.0D / mNumRows;
        }

        /**
         * The fixed phi angle separation between successive columns.
         */
        @FloatRange(from = 0.0f, to = 180.0f)
        public double getDeltaPhi() {
            return 180.0D / (mNumColumns - 1);
        }


        public static final @NonNull Creator<SphericalCorrections> CREATOR =
                new Creator<SphericalCorrections>() {
                    @Override
                    public SphericalCorrections createFromParcel(Parcel in) {
                        return new SphericalCorrections(in);
                    }

                    @Override
                    public SphericalCorrections[] newArray(int size) {
                        return new SphericalCorrections[size];
                    }
                };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeInt(mNumRows);
            dest.writeInt(mNumColumns);
            for (int row = 0; row < mNumRows; row++) {
                for (int col = 0; col < mNumColumns; col++) {
                    dest.writeDouble(mCorrections[row][col]);
                    dest.writeDouble(mCorrectionUncertainties[row][col]);
                }
            }
        }

        @Override
        public String toString() {
            return "SphericalCorrections{"
                    + "Corrections=" + Arrays.toString(mCorrections)
                    + ", CorrectionUncertainties=" + Arrays.toString(mCorrectionUncertainties)
                    + ", DeltaTheta=" + getDeltaTheta()
                    + ", DeltaPhi=" + getDeltaPhi()
                    + '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof SphericalCorrections)) {
                return false;
            }
            SphericalCorrections that = (SphericalCorrections) o;
            return mNumRows == that.mNumRows
                    && mNumColumns == that.mNumColumns
                    && Arrays.deepEquals(mCorrections, that.mCorrections)
                    && Arrays.deepEquals(mCorrectionUncertainties, that.mCorrectionUncertainties);
        }

        @Override
        public int hashCode() {
            int result = Arrays.deepHashCode(mCorrections);
            result = 31 * result + Arrays.deepHashCode(mCorrectionUncertainties);
            return result;
        }
    }

    private GnssAntennaInfo(
            double carrierFrequencyMHz,
            PhaseCenterOffset phaseCenterOffset,
            @Nullable SphericalCorrections phaseCenterVariationCorrections,
            @Nullable SphericalCorrections signalGainCorrectionDbi) {
        mCarrierFrequencyMHz = carrierFrequencyMHz;
        mPhaseCenterOffset = Objects.requireNonNull(phaseCenterOffset);
        mPhaseCenterVariationCorrections = phaseCenterVariationCorrections;
        mSignalGainCorrections = signalGainCorrectionDbi;
    }

    /**
     * Builder class for GnssAntennaInfo.
     */
    public static class Builder {
        private double mCarrierFrequencyMHz;
        private PhaseCenterOffset mPhaseCenterOffset;
        private @Nullable SphericalCorrections mPhaseCenterVariationCorrections;
        private @Nullable SphericalCorrections mSignalGainCorrections;

        /**
         * @deprecated Prefer {@link #Builder(double, PhaseCenterOffset)}.
         */
        @Deprecated
        public Builder() {
            this(0, new PhaseCenterOffset(0, 0, 0, 0, 0, 0));
        }

        public Builder(double carrierFrequencyMHz, @NonNull PhaseCenterOffset phaseCenterOffset) {
            mCarrierFrequencyMHz = carrierFrequencyMHz;
            mPhaseCenterOffset = Objects.requireNonNull(phaseCenterOffset);
        }

        public Builder(@NonNull GnssAntennaInfo antennaInfo) {
            mCarrierFrequencyMHz = antennaInfo.mCarrierFrequencyMHz;
            mPhaseCenterOffset = antennaInfo.mPhaseCenterOffset;
            mPhaseCenterVariationCorrections = antennaInfo.mPhaseCenterVariationCorrections;
            mSignalGainCorrections = antennaInfo.mSignalGainCorrections;
        }

        /**
         * Set antenna carrier frequency (MHz).
         *
         * @param carrierFrequencyMHz antenna carrier frequency (MHz)
         * @return Builder builder object
         */
        @NonNull
        public Builder setCarrierFrequencyMHz(@FloatRange(from = 0.0f) double carrierFrequencyMHz) {
            mCarrierFrequencyMHz = carrierFrequencyMHz;
            return this;
        }

        /**
         * Set antenna phase center offset.
         *
         * @param phaseCenterOffset phase center offset object
         * @return Builder builder object
         */
        @NonNull
        public Builder setPhaseCenterOffset(@NonNull PhaseCenterOffset phaseCenterOffset) {
            mPhaseCenterOffset = Objects.requireNonNull(phaseCenterOffset);
            return this;
        }

        /**
         * Set phase center variation corrections.
         *
         * @param phaseCenterVariationCorrections phase center variation corrections object
         * @return Builder builder object
         */
        @NonNull
        public Builder setPhaseCenterVariationCorrections(
                @Nullable SphericalCorrections phaseCenterVariationCorrections) {
            mPhaseCenterVariationCorrections = phaseCenterVariationCorrections;
            return this;
        }

        /**
         * Set signal gain corrections.
         *
         * @param signalGainCorrections signal gain corrections object
         * @return Builder builder object
         */
        @NonNull
        public Builder setSignalGainCorrections(
                @Nullable SphericalCorrections signalGainCorrections) {
            mSignalGainCorrections = signalGainCorrections;
            return this;
        }

        /**
         * Build GnssAntennaInfo object.
         *
         * @return instance of GnssAntennaInfo
         */
        @NonNull
        public GnssAntennaInfo build() {
            return new GnssAntennaInfo(mCarrierFrequencyMHz, mPhaseCenterOffset,
                    mPhaseCenterVariationCorrections, mSignalGainCorrections);
        }
    }

    @FloatRange(from = 0.0f)
    public double getCarrierFrequencyMHz() {
        return mCarrierFrequencyMHz;
    }

    /**
     * Returns a {@link PhaseCenterOffset} object encapsulating the phase center offset and
     * corresponding uncertainties in millimeters.
     *
     * @return {@link PhaseCenterOffset}
     */
    @NonNull
    public PhaseCenterOffset getPhaseCenterOffset() {
        return mPhaseCenterOffset;
    }

    /**
     * Returns a {@link SphericalCorrections} object encapsulating the phase center variation
     * corrections and corresponding uncertainties in millimeters.
     *
     * @return phase center variation corrections as {@link SphericalCorrections}
     */
    @Nullable
    public SphericalCorrections getPhaseCenterVariationCorrections() {
        return mPhaseCenterVariationCorrections;
    }

    /**
     * Returns a {@link SphericalCorrections} object encapsulating the signal gain
     * corrections and corresponding uncertainties in dBi.
     *
     * @return signal gain corrections as {@link SphericalCorrections}
     */
    @Nullable
    public SphericalCorrections getSignalGainCorrections() {
        return mSignalGainCorrections;
    }

    public static final @NonNull Creator<GnssAntennaInfo> CREATOR = new Creator<GnssAntennaInfo>() {
        @Override
        public GnssAntennaInfo createFromParcel(Parcel in) {
            double carrierFrequencyMHz = in.readDouble();
            PhaseCenterOffset phaseCenterOffset =
                    in.readTypedObject(PhaseCenterOffset.CREATOR);
            SphericalCorrections phaseCenterVariationCorrections =
                    in.readTypedObject(SphericalCorrections.CREATOR);
            SphericalCorrections signalGainCorrections =
                    in.readTypedObject(SphericalCorrections.CREATOR);

            return new GnssAntennaInfo(
                    carrierFrequencyMHz,
                    phaseCenterOffset,
                    phaseCenterVariationCorrections,
                    signalGainCorrections);
        }

        @Override
        public GnssAntennaInfo[] newArray(int size) {
            return new GnssAntennaInfo[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeDouble(mCarrierFrequencyMHz);
        parcel.writeTypedObject(mPhaseCenterOffset, flags);
        parcel.writeTypedObject(mPhaseCenterVariationCorrections, flags);
        parcel.writeTypedObject(mSignalGainCorrections, flags);
    }

    @Override
    public String toString() {
        return "GnssAntennaInfo{"
                + "CarrierFrequencyMHz=" + mCarrierFrequencyMHz
                + ", PhaseCenterOffset=" + mPhaseCenterOffset
                + ", PhaseCenterVariationCorrections=" + mPhaseCenterVariationCorrections
                + ", SignalGainCorrections=" + mSignalGainCorrections
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GnssAntennaInfo)) {
            return false;
        }
        GnssAntennaInfo that = (GnssAntennaInfo) o;
        return Double.compare(that.mCarrierFrequencyMHz, mCarrierFrequencyMHz) == 0
                && mPhaseCenterOffset.equals(that.mPhaseCenterOffset)
                && Objects.equals(mPhaseCenterVariationCorrections,
                    that.mPhaseCenterVariationCorrections)
                && Objects.equals(mSignalGainCorrections, that.mSignalGainCorrections);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mCarrierFrequencyMHz, mPhaseCenterOffset,
                mPhaseCenterVariationCorrections, mSignalGainCorrections);
    }
}
