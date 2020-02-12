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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

/**
 * A class that contains information about a GNSS antenna. GNSS antenna characteristics can change
 * with device configuration, such as when a device is folded open or closed. Antenna information is
 * delivered to registered instances of {@link Callback}.
 */
public final class GnssAntennaInfo implements Parcelable {
    private final double mCarrierFrequencyMHz;
    private final PhaseCenterOffsetCoordinates mPhaseCenterOffsetCoordinates;
    private final PhaseCenterVariationCorrections mPhaseCenterVariationCorrections;
    private final SignalGainCorrections mSignalGainCorrections;

    /**
     * Used for receiving GNSS antenna info from the GNSS engine. You can implement this interface
     * and call {@link LocationManager#registerAntennaInfoCallback};
     */
    public abstract static class Callback {
        /**
         * The status of GNSS antenna info.
         *
         * @hide
         */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef({STATUS_NOT_SUPPORTED, STATUS_READY, STATUS_LOCATION_DISABLED})
        public @interface GnssAntennaInfoStatus {
        }

        /**
         * The system does not support GNSS antenna info.
         *
         * This status will not change in the future.
         */
        public static final int STATUS_NOT_SUPPORTED = 0;

        /**
         * GNSS antenna info updates are being successfully tracked.
         */
        public static final int STATUS_READY = 1;

        /**
         * GNSS provider or Location is disabled, updated will not be received until they are
         * enabled.
         */
        public static final int STATUS_LOCATION_DISABLED = 2;

        /**
         * Returns the latest GNSS antenna info. This event is triggered when a callback is
         * registered, and whenever the antenna info changes (due to a device configuration change).
         */
        public void onGnssAntennaInfoReceived(@NonNull List<GnssAntennaInfo> gnssAntennaInfos) {}

        /**
         * Returns the latest status of the GNSS antenna info sub-system.
         */
        public void onStatusChanged(@GnssAntennaInfoStatus int status) {}
    }

    /**
     * Class containing information about the antenna phase center offset (PCO). PCO is defined with
     * respect to the origin of the Android sensor coordinate system, e.g., center of primary screen
     * for mobiles - see sensor or form factor documents for details. Uncertainties are reported
     *  to 1-sigma.
     */
    public static final class PhaseCenterOffsetCoordinates implements Parcelable {
        private final double mPhaseCenterOffsetCoordinateXMillimeters;
        private final double mPhaseCenterOffsetCoordinateXUncertaintyMillimeters;
        private final double mPhaseCenterOffsetCoordinateYMillimeters;
        private final double mPhaseCenterOffsetCoordinateYUncertaintyMillimeters;
        private final double mPhaseCenterOffsetCoordinateZMillimeters;
        private final double mPhaseCenterOffsetCoordinateZUncertaintyMillimeters;

        @VisibleForTesting
        public PhaseCenterOffsetCoordinates(double phaseCenterOffsetCoordinateXMillimeters,
                double phaseCenterOffsetCoordinateXUncertaintyMillimeters,
                double phaseCenterOffsetCoordinateYMillimeters,
                double phaseCenterOffsetCoordinateYUncertaintyMillimeters,
                double phaseCenterOffsetCoordinateZMillimeters,
                double phaseCenterOffsetCoordinateZUncertaintyMillimeters) {
            mPhaseCenterOffsetCoordinateXMillimeters = phaseCenterOffsetCoordinateXMillimeters;
            mPhaseCenterOffsetCoordinateYMillimeters = phaseCenterOffsetCoordinateYMillimeters;
            mPhaseCenterOffsetCoordinateZMillimeters = phaseCenterOffsetCoordinateZMillimeters;
            mPhaseCenterOffsetCoordinateXUncertaintyMillimeters =
                    phaseCenterOffsetCoordinateXUncertaintyMillimeters;
            mPhaseCenterOffsetCoordinateYUncertaintyMillimeters =
                    phaseCenterOffsetCoordinateYUncertaintyMillimeters;
            mPhaseCenterOffsetCoordinateZUncertaintyMillimeters =
                    phaseCenterOffsetCoordinateZUncertaintyMillimeters;
        }

        public static final @NonNull Creator<PhaseCenterOffsetCoordinates> CREATOR =
                new Creator<PhaseCenterOffsetCoordinates>() {
                    @Override
                    public PhaseCenterOffsetCoordinates createFromParcel(Parcel in) {
                        return new PhaseCenterOffsetCoordinates(
                                in.readDouble(),
                                in.readDouble(),
                                in.readDouble(),
                                in.readDouble(),
                                in.readDouble(),
                                in.readDouble()
                        );
                    }

                    @Override
                    public PhaseCenterOffsetCoordinates[] newArray(int size) {
                        return new PhaseCenterOffsetCoordinates[size];
                    }
                };

        public double getXCoordMillimeters() {
            return mPhaseCenterOffsetCoordinateXMillimeters;
        }

        public double getXCoordUncertaintyMillimeters() {
            return mPhaseCenterOffsetCoordinateXUncertaintyMillimeters;
        }

        public double getYCoordMillimeters() {
            return mPhaseCenterOffsetCoordinateYMillimeters;
        }

        public double getYCoordUncertaintyMillimeters() {
            return mPhaseCenterOffsetCoordinateYUncertaintyMillimeters;
        }

        public double getZCoordMillimeters() {
            return mPhaseCenterOffsetCoordinateZMillimeters;
        }

        public double getZCoordUncertaintyMillimeters() {
            return mPhaseCenterOffsetCoordinateZUncertaintyMillimeters;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeDouble(mPhaseCenterOffsetCoordinateXMillimeters);
            dest.writeDouble(mPhaseCenterOffsetCoordinateXUncertaintyMillimeters);
            dest.writeDouble(mPhaseCenterOffsetCoordinateYMillimeters);
            dest.writeDouble(mPhaseCenterOffsetCoordinateYUncertaintyMillimeters);
            dest.writeDouble(mPhaseCenterOffsetCoordinateZMillimeters);
            dest.writeDouble(mPhaseCenterOffsetCoordinateZUncertaintyMillimeters);
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder("PhaseCenteroffset:\n");
            builder.append("X: " + mPhaseCenterOffsetCoordinateXMillimeters + " +/- "
                    + mPhaseCenterOffsetCoordinateXUncertaintyMillimeters + "\n");
            builder.append("Y: " + mPhaseCenterOffsetCoordinateYMillimeters + " +/- "
                    + mPhaseCenterOffsetCoordinateYUncertaintyMillimeters + "\n");
            builder.append("Z: " + mPhaseCenterOffsetCoordinateZMillimeters + " +/- "
                    + mPhaseCenterOffsetCoordinateZUncertaintyMillimeters + "\n");
            return builder.toString();
        }
    }

    /**
     * Class containing information about the phase center variation (PCV) corrections. The PCV
     * correction is added to the phase measurement to obtain the corrected value.
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
    public static final class PhaseCenterVariationCorrections extends SphericalCorrections {

        @VisibleForTesting
        public PhaseCenterVariationCorrections(
                @NonNull double[][] phaseCenterVariationCorrectionsMillimeters,
                @NonNull double[][] phaseCenterVariationCorrectionUncertaintiesMillimeters) {
            super(phaseCenterVariationCorrectionsMillimeters,
                    phaseCenterVariationCorrectionUncertaintiesMillimeters);
        }

        private PhaseCenterVariationCorrections(@NonNull Parcel in) {
            super(in);
        }

        /**
         * Get the phase center variation correction in millimeters at the specified row and column
         * in the underlying 2D array.
         * @param row zero-based major index in the array
         * @param column zero-based minor index in the array
         * @return phase center correction in millimeters
         */
        public double getPhaseCenterVariationCorrectionMillimetersAt(int row, int column) {
            return super.getCorrectionAt(row, column);
        }

        /**
         * Get the phase center variation correction uncertainty in millimeters at the specified row
         * and column in the underlying 2D array.
         * @param row zero-based major index in the array
         * @param column zero-based minor index in the array
         * @return 1-sigma phase center correction uncertainty in millimeters
         */
        public double getPhaseCenterVariationCorrectionUncertaintyMillimetersAt(
                int row, int column) {
            return super.getCorrectionUncertaintyAt(row, column);
        }

        public @NonNull double[][] getRawCorrectionsArray() {
            return super.getRawCorrectionsArray().clone();
        }

        public @NonNull double[][] getRawCorrectionUncertaintiesArray() {
            return super.getRawCorrectionUncertaintiesArray().clone();
        }

        public int getNumRows() {
            return super.getNumRows();
        }

        public int getNumColumns() {
            return super.getNumColumns();
        }

        /**
         * The fixed theta angle separation between successive rows.
         */
        public double getDeltaTheta() {
            return super.getDeltaTheta();
        }

        /**
         * The fixed phi angle separation between successive columns.
         */
        public double getDeltaPhi() {
            return super.getDeltaPhi();
        }

        public static final @NonNull Creator<PhaseCenterVariationCorrections> CREATOR =
                new Creator<PhaseCenterVariationCorrections>() {
                    @Override
                    public PhaseCenterVariationCorrections createFromParcel(Parcel in) {
                        return new PhaseCenterVariationCorrections(in);
                    }

                    @Override
                    public PhaseCenterVariationCorrections[] newArray(int size) {
                        return new PhaseCenterVariationCorrections[size];
                    }
                };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder("PhaseCenterVariationCorrections:\n");
            builder.append(super.toString());
            return builder.toString();
        }
    }

    /**
     * Class containing information about the signal gain (SG) corrections. The SG
     * correction is added to the signal gain to obtain the corrected value.
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
    public static final class SignalGainCorrections extends SphericalCorrections {

        @VisibleForTesting
        public SignalGainCorrections(
                @NonNull double[][] signalGainCorrectionsDbi,
                @NonNull double[][] signalGainCorrectionUncertaintiesDbi) {
            super(signalGainCorrectionsDbi,
                    signalGainCorrectionUncertaintiesDbi);
        }

        private SignalGainCorrections(@NonNull Parcel in) {
            super(in);
        }

        /**
         * Get the signal gain correction in dbi at the specified row and column
         * in the underlying 2D array.
         * @param row zero-based major index in the array
         * @param column zero-based minor index in the array
         * @return signal gain correction in dbi
         */
        public double getSignalGainCorrectionDbiAt(int row, int column) {
            return super.getCorrectionAt(row, column);
        }

        /**
         * Get the signal gain correction correction uncertainty in dbi at the specified row
         * and column in the underlying 2D array.
         * @param row zero-based major index in the array
         * @param column zero-based minor index in the array
         * @return 1-sigma signal gain correction uncertainty in dbi
         */
        public double getSignalGainCorrectionUncertaintyDbiAt(int row, int column) {
            return super.getCorrectionUncertaintyAt(row, column);
        }

        public @NonNull double[][] getRawCorrectionsArray() {
            return super.getRawCorrectionsArray().clone();
        }

        public @NonNull double[][] getRawCorrectionUncertaintiesArray() {
            return super.getRawCorrectionUncertaintiesArray().clone();
        }

        public int getNumRows() {
            return super.getNumRows();
        }

        public int getNumColumns() {
            return super.getNumColumns();
        }

        /**
         * The fixed theta angle separation between successive rows.
         */
        public double getDeltaTheta() {
            return super.getDeltaTheta();
        }

        /**
         * The fixed phi angle separation between successive columns.
         */
        public double getDeltaPhi() {
            return super.getDeltaPhi();
        }

        public static final @NonNull Creator<SignalGainCorrections> CREATOR =
                new Creator<SignalGainCorrections>() {
                    @Override
                    public SignalGainCorrections createFromParcel(Parcel in) {
                        return new SignalGainCorrections(in);
                    }

                    @Override
                    public SignalGainCorrections[] newArray(int size) {
                        return new SignalGainCorrections[size];
                    }
                };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder("SignalGainCorrections:\n");
            builder.append(super.toString());
            return builder.toString();
        }
    }

    /**
     * Represents corrections on a spherical mapping.
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
    private abstract static class SphericalCorrections implements Parcelable {
        private final double[][] mCorrections;
        private final double[][] mCorrectionUncertainties;
        private final double mDeltaTheta;
        private final double mDeltaPhi;
        private final int mNumRows;
        private final int mNumColumns;

        SphericalCorrections(@NonNull double[][] corrections,
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

            mNumRows = corrections.length;
            mNumColumns = corrections[0].length;
            mCorrections = corrections;
            mCorrectionUncertainties = correctionUncertainties;
            mDeltaTheta = 360.0d / mNumRows;
            mDeltaPhi = 180.0d / (mNumColumns - 1);
        }

        private double getCorrectionAt(int row, int column) {
            return mCorrections[row][column];
        }

        private double getCorrectionUncertaintyAt(int row, int column) {
            return mCorrectionUncertainties[row][column];
        }

        @NonNull
        private double[][] getRawCorrectionsArray() {
            return mCorrections;
        }

        @NonNull
        private double[][] getRawCorrectionUncertaintiesArray() {
            return mCorrectionUncertainties;
        }

        private int getNumRows() {
            return mNumRows;
        }

        private int getNumColumns() {
            return mNumColumns;
        }

        /**
         * The fixed theta angle separation between successive rows.
         */
        private double getDeltaTheta() {
            return mDeltaTheta;
        }

        /**
         * The fixed phi angle separation between successive columns.
         */
        private double getDeltaPhi() {
            return mDeltaPhi;
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

        private String arrayToString(double[][] array) {
            StringBuilder builder = new StringBuilder();
            for (int row = 0; row < mNumRows; row++) {
                builder.append("[ ");
                for (int column = 0; column < mNumColumns - 1; column++) {
                    builder.append(array[row][column] + ", ");
                }
                builder.append(array[row][mNumColumns - 1] + " ]\n");
            }
            return builder.toString();
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("DeltaTheta: " + mDeltaTheta + "\n");
            builder.append("DeltaPhi: " + mDeltaPhi + "\n");
            builder.append("CorrectionsArray:\n");
            builder.append(arrayToString(mCorrections));
            builder.append("CorrectionUncertaintiesArray:\n");
            builder.append(arrayToString(mCorrectionUncertainties));
            return builder.toString();
        }
    }

    @VisibleForTesting
    public GnssAntennaInfo(
            double carrierFrequencyMHz,
            @NonNull PhaseCenterOffsetCoordinates phaseCenterOffsetCoordinates,
            @Nullable PhaseCenterVariationCorrections phaseCenterVariationCorrections,
            @Nullable SignalGainCorrections signalGainCorrectionDbi) {
        if (phaseCenterOffsetCoordinates == null) {
            throw new IllegalArgumentException("Phase Center Offset Coordinates cannot be null.");
        }
        mCarrierFrequencyMHz = carrierFrequencyMHz;
        mPhaseCenterOffsetCoordinates = phaseCenterOffsetCoordinates;
        mPhaseCenterVariationCorrections = phaseCenterVariationCorrections;
        mSignalGainCorrections = signalGainCorrectionDbi;
    }

    public double getCarrierFrequencyMHz() {
        return mCarrierFrequencyMHz;
    }

    @NonNull
    public PhaseCenterOffsetCoordinates getPhaseCenterOffsetCoordinates() {
        return mPhaseCenterOffsetCoordinates;
    }

    @Nullable
    public PhaseCenterVariationCorrections getPhaseCenterVariationCorrections() {
        return mPhaseCenterVariationCorrections;
    }

    @Nullable
    public SignalGainCorrections getSignalGainCorrections() {
        return mSignalGainCorrections;
    }

    public static final @android.annotation.NonNull
                    Creator<GnssAntennaInfo> CREATOR = new Creator<GnssAntennaInfo>() {
                            @Override
                            public GnssAntennaInfo createFromParcel(Parcel in) {
                                double carrierFrequencyMHz = in.readDouble();

                                ClassLoader classLoader = getClass().getClassLoader();
                                PhaseCenterOffsetCoordinates phaseCenterOffsetCoordinates =
                                        in.readParcelable(classLoader);
                                PhaseCenterVariationCorrections phaseCenterVariationCorrections =
                                        in.readParcelable(classLoader);
                                SignalGainCorrections signalGainCorrections =
                                        in.readParcelable(classLoader);

                                return new GnssAntennaInfo(carrierFrequencyMHz,
                                        phaseCenterOffsetCoordinates,
                                        phaseCenterVariationCorrections, signalGainCorrections);
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

        // Write Phase Center Offset
        parcel.writeParcelable(mPhaseCenterOffsetCoordinates, flags);

        // Write Phase Center Variation Corrections
        parcel.writeParcelable(mPhaseCenterVariationCorrections, flags);

        // Write Signal Gain Corrections
        parcel.writeParcelable(mSignalGainCorrections, flags);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("[ GnssAntennaInfo:\n");
        builder.append("CarrierFrequencyMHz: " + mCarrierFrequencyMHz + "\n");
        builder.append(mPhaseCenterOffsetCoordinates.toString());
        builder.append(mPhaseCenterVariationCorrections == null
                ? "PhaseCenterVariationCorrections: null\n"
                : mPhaseCenterVariationCorrections.toString());
        builder.append(mSignalGainCorrections == null
                ? "SignalGainCorrections: null\n"
                : mSignalGainCorrections.toString());
        builder.append("]");
        return builder.toString();
    }
}
