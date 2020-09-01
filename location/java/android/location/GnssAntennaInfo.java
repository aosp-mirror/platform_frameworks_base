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
 */
public final class GnssAntennaInfo implements Parcelable {
    private final double mCarrierFrequencyMHz;
    private final PhaseCenterOffset mPhaseCenterOffset;
    private final SphericalCorrections mPhaseCenterVariationCorrections;
    private final SphericalCorrections mSignalGainCorrections;

    /**
     * Used for receiving GNSS antenna info from the GNSS engine. You can implement this interface
     * and call {@link LocationManager#registerAntennaInfoListener};
     */
    public interface Listener {
        /**
         * Returns the latest GNSS antenna info. This event is triggered when a listener is
         * registered, and whenever the antenna info changes (due to a device configuration change).
         */
        void onGnssAntennaInfoReceived(@NonNull List<GnssAntennaInfo> gnssAntennaInfos);
    }

    /**
     * Class containing information about the antenna phase center offset (PCO). PCO is defined with
     * respect to the origin of the Android sensor coordinate system, e.g., center of primary screen
     * for mobiles - see sensor or form factor documents for details. Uncertainties are reported
     *  to 1-sigma.
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

        @FloatRange()
        public double getXOffsetMm() {
            return mOffsetXMm;
        }

        @FloatRange()
        public double getXOffsetUncertaintyMm() {
            return mOffsetXUncertaintyMm;
        }

        @FloatRange()
        public double getYOffsetMm() {
            return mOffsetYMm;
        }

        @FloatRange()
        public double getYOffsetUncertaintyMm() {
            return mOffsetYUncertaintyMm;
        }

        @FloatRange()
        public double getZOffsetMm() {
            return mOffsetZMm;
        }

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
    public static final class SphericalCorrections implements Parcelable{
        private final double[][] mCorrections;
        private final double[][] mCorrectionUncertainties;
        private final double mDeltaTheta;
        private final double mDeltaPhi;
        private final int mNumRows;
        private final int mNumColumns;

        public SphericalCorrections(@NonNull double[][] corrections,
                @NonNull double[][] correctionUncertainties) {
            if (corrections.length != correctionUncertainties.length
                    || corrections[0].length != correctionUncertainties[0].length) {
                throw new IllegalArgumentException("Correction and correction uncertainty arrays "
                        + "must have the same dimensions.");
            }

            mNumRows = corrections.length;
            if (mNumRows < 1) {
                throw new IllegalArgumentException("Arrays must have at least one row.");
            }

            mNumColumns = corrections[0].length;
            if (mNumColumns < 2) {
                throw new IllegalArgumentException("Arrays must have at least two columns.");
            }

            mCorrections = corrections;
            mCorrectionUncertainties = correctionUncertainties;
            mDeltaTheta = 360.0d / mNumRows;
            mDeltaPhi = 180.0d / (mNumColumns - 1);
        }

        SphericalCorrections(Parcel in) {
            int numRows = in.readInt();
            int numColumns = in.readInt();

            double[][] corrections =
                    new double[numRows][numColumns];
            double[][] correctionUncertainties =
                    new double[numRows][numColumns];

            for (int row = 0; row < numRows; row++) {
                in.readDoubleArray(corrections[row]);
            }

            for (int row = 0; row < numRows; row++) {
                in.readDoubleArray(correctionUncertainties[row]);
            }

            mNumRows = numRows;
            mNumColumns = numColumns;
            mCorrections = corrections;
            mCorrectionUncertainties = correctionUncertainties;
            mDeltaTheta = 360.0d / mNumRows;
            mDeltaPhi = 180.0d / (mNumColumns - 1);
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
            return mDeltaTheta;
        }

        /**
         * The fixed phi angle separation between successive columns.
         */
        @FloatRange(from = 0.0f, to = 180.0f)
        public double getDeltaPhi() {
            return mDeltaPhi;
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
            for (double[] row: mCorrections) {
                dest.writeDoubleArray(row);
            }
            for (double[] row: mCorrectionUncertainties) {
                dest.writeDoubleArray(row);
            }
        }

        @Override
        public String toString() {
            return "SphericalCorrections{"
                    + "Corrections=" + Arrays.toString(mCorrections)
                    + ", CorrectionUncertainties=" + Arrays.toString(mCorrectionUncertainties)
                    + ", DeltaTheta=" + mDeltaTheta
                    + ", DeltaPhi=" + mDeltaPhi
                    + '}';
        }
    }

    private GnssAntennaInfo(
            double carrierFrequencyMHz,
            @NonNull PhaseCenterOffset phaseCenterOffset,
            @Nullable SphericalCorrections phaseCenterVariationCorrections,
            @Nullable SphericalCorrections signalGainCorrectionDbi) {
        if (phaseCenterOffset == null) {
            throw new IllegalArgumentException("Phase Center Offset Coordinates cannot be null.");
        }
        mCarrierFrequencyMHz = carrierFrequencyMHz;
        mPhaseCenterOffset = phaseCenterOffset;
        mPhaseCenterVariationCorrections = phaseCenterVariationCorrections;
        mSignalGainCorrections = signalGainCorrectionDbi;
    }

    /**
     * Builder class for GnssAntennaInfo.
     */
    public static class Builder {
        private double mCarrierFrequencyMHz;
        private PhaseCenterOffset mPhaseCenterOffset;
        private SphericalCorrections mPhaseCenterVariationCorrections;
        private SphericalCorrections mSignalGainCorrections;

        /**
         * Set antenna carrier frequency (MHz).
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

    @NonNull
    public PhaseCenterOffset getPhaseCenterOffset() {
        return mPhaseCenterOffset;
    }

    @Nullable
    public SphericalCorrections getPhaseCenterVariationCorrections() {
        return mPhaseCenterVariationCorrections;
    }

    @Nullable
    public SphericalCorrections getSignalGainCorrections() {
        return mSignalGainCorrections;
    }

    public static final @android.annotation.NonNull
                    Creator<GnssAntennaInfo> CREATOR = new Creator<GnssAntennaInfo>() {
                            @Override
                            public GnssAntennaInfo createFromParcel(Parcel in) {
                                double carrierFrequencyMHz = in.readDouble();

                                ClassLoader classLoader = getClass().getClassLoader();
                                PhaseCenterOffset phaseCenterOffset =
                                        in.readParcelable(classLoader);
                                SphericalCorrections phaseCenterVariationCorrections =
                                        in.readParcelable(classLoader);
                                SphericalCorrections signalGainCorrections =
                                        in.readParcelable(classLoader);

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
        parcel.writeParcelable(mPhaseCenterOffset, flags);
        parcel.writeParcelable(mPhaseCenterVariationCorrections, flags);
        parcel.writeParcelable(mSignalGainCorrections, flags);
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
}
