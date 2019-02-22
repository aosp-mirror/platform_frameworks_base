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

import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A class representing a GNSS measurement corrections for all used GNSS satellites at the location
 * and time specified
 *
 * @hide
 */
@SystemApi
public final class GnssMeasurementCorrections implements Parcelable {

    /** Represents latitude in degrees at which the corrections are computed. */
    private double mLatitudeDegrees;
    /** Represents longitude in degrees at which the corrections are computed. */
    private double mLongitudeDegrees;
    /**
     * Represents altitude in meters above the WGS 84 reference ellipsoid at which the corrections
     * are computed.
     */
    private double mAltitudeMeters;
    /**
     * Represents the horizontal uncertainty (68% confidence) in meters on the device position at
     * which the corrections are provided.
     *
     * <p> This value is useful for example to judge how accurate the provided corrections are.
     */
    private double mHorizontalPositionUncertaintyMeters;
    /**
     * Represents the vertical uncertainty (68% confidence) in meters on the device position at
     * which the corrections are provided.
     *
     * <p> This value is useful for example to judge how accurate the provided corrections are.
     */
    private double mVerticalPositionUncertaintyMeters;

    /** Time Of Applicability, GPS time of week in nanoseconds. */
    private long mToaGpsNanosecondsOfWeek;

    /**
     * A set of {@link GnssSingleSatCorrection} each containing measurement corrections for a
     * satellite in view.
     */
    private @Nullable List<GnssSingleSatCorrection> mSingleSatCorrectionList;

    private GnssMeasurementCorrections(Builder builder) {
        mLatitudeDegrees = builder.mLatitudeDegrees;
        mLongitudeDegrees = builder.mLongitudeDegrees;
        mAltitudeMeters = builder.mAltitudeMeters;
        mHorizontalPositionUncertaintyMeters = builder.mHorizontalPositionUncertaintyMeters;
        mVerticalPositionUncertaintyMeters = builder.mVerticalPositionUncertaintyMeters;
        mToaGpsNanosecondsOfWeek = builder.mToaGpsNanosecondsOfWeek;
        mSingleSatCorrectionList =
                builder.mSingleSatCorrectionList == null
                        ? null
                        : Collections.unmodifiableList(
                                new ArrayList<>(builder.mSingleSatCorrectionList));
    }

    /** Gets the latitude in degrees at which the corrections are computed. */
    public double getLatitudeDegrees() {
        return mLatitudeDegrees;
    }

    /** Gets the longitude in degrees at which the corrections are computed. */
    public double getLongitudeDegrees() {
        return mLongitudeDegrees;
    }

    /**
     * Gets the altitude in meters above the WGS 84 reference ellipsoid at which the corrections are
     * computed.
     */
    public double getAltitudeMeters() {
        return mAltitudeMeters;
    }

    /**
     * Gets the horizontal uncertainty (68% confidence) in meters on the device position at
     * which the corrections are provided.
     */
    public double getHorizontalPositionUncertaintyMeters() {
        return mHorizontalPositionUncertaintyMeters;
    }

    /**
     * Gets the vertical uncertainty (68% confidence) in meters on the device position at
     * which the corrections are provided.
     */
    public double getVerticalPositionUncertaintyMeters() {
        return mVerticalPositionUncertaintyMeters;
    }

    /** Gets the time of applicability, GPS time of week in nanoseconds. */
    public long getToaGpsNanosecondsOfWeek() {
        return mToaGpsNanosecondsOfWeek;
    }

    /**
     * Gets a set of {@link GnssSingleSatCorrection} each containing measurement corrections for a
     * satellite in view
     */
    public @Nullable List<GnssSingleSatCorrection> getSingleSatelliteCorrectionList() {
        return mSingleSatCorrectionList;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<GnssMeasurementCorrections> CREATOR =
            new Creator<GnssMeasurementCorrections>() {
                @Override
                public GnssMeasurementCorrections createFromParcel(Parcel parcel) {
                    final GnssMeasurementCorrections.Builder gnssMeasurementCorrectons =
                            new Builder()
                                    .setLatitudeDegrees(parcel.readDouble())
                                    .setLongitudeDegrees(parcel.readDouble())
                                    .setAltitudeMeters(parcel.readDouble())
                                    .setHorizontalPositionUncertaintyMeters(parcel.readDouble())
                                    .setVerticalPositionUncertaintyMeters(parcel.readDouble())
                                    .setToaGpsNanosecondsOfWeek(parcel.readLong());
                    List<GnssSingleSatCorrection> singleSatCorrectionList = new ArrayList<>();
                    parcel.readTypedList(singleSatCorrectionList, GnssSingleSatCorrection.CREATOR);
                    gnssMeasurementCorrectons.setSingleSatelliteCorrectionList(
                            singleSatCorrectionList.isEmpty() ? null : singleSatCorrectionList);
                    return gnssMeasurementCorrectons.build();
                }

                @Override
                public GnssMeasurementCorrections[] newArray(int i) {
                    return new GnssMeasurementCorrections[i];
                }
            };

    @Override
    public String toString() {
        final String format = "   %-29s = %s\n";
        StringBuilder builder = new StringBuilder("GnssMeasurementCorrections:\n");
        builder.append(String.format(format, "LatitudeDegrees = ", mLatitudeDegrees));
        builder.append(String.format(format, "LongitudeDegrees = ", mLongitudeDegrees));
        builder.append(String.format(format, "AltitudeMeters = ", mAltitudeMeters));
        builder.append(String.format(format, "HorizontalPositionUncertaintyMeters = ",
                mHorizontalPositionUncertaintyMeters));
        builder.append(String.format(format, "VerticalPositionUncertaintyMeters = ",
                mVerticalPositionUncertaintyMeters));
        builder.append(
                String.format(format, "ToaGpsNanosecondsOfWeek = ", mToaGpsNanosecondsOfWeek));
        builder.append(
                String.format(format, "mSingleSatCorrectionList = ", mSingleSatCorrectionList));
        return builder.toString();
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeDouble(mLatitudeDegrees);
        parcel.writeDouble(mLongitudeDegrees);
        parcel.writeDouble(mAltitudeMeters);
        parcel.writeDouble(mHorizontalPositionUncertaintyMeters);
        parcel.writeDouble(mVerticalPositionUncertaintyMeters);
        parcel.writeLong(mToaGpsNanosecondsOfWeek);
        parcel.writeTypedList(mSingleSatCorrectionList);
    }

    /** Builder for {@link GnssMeasurementCorrections} */
    public static final class Builder {
        /**
         * For documentation of below fields, see corresponding fields in {@link
         * GnssMeasurementCorrections}.
         */
        private double mLatitudeDegrees;
        private double mLongitudeDegrees;
        private double mAltitudeMeters;
        private double mHorizontalPositionUncertaintyMeters;
        private double mVerticalPositionUncertaintyMeters;
        private long mToaGpsNanosecondsOfWeek;
        private List<GnssSingleSatCorrection> mSingleSatCorrectionList;

        /** Sets the latitude in degrees at which the corrections are computed. */
        public Builder setLatitudeDegrees(double latitudeDegrees) {
            mLatitudeDegrees = latitudeDegrees;
            return this;
        }

        /** Sets the longitude in degrees at which the corrections are computed. */
        public Builder setLongitudeDegrees(double longitudeDegrees) {
            mLongitudeDegrees = longitudeDegrees;
            return this;
        }

        /**
         * Sets the altitude in meters above the WGS 84 reference ellipsoid at which the corrections
         * are computed.
         */
        public Builder setAltitudeMeters(double altitudeMeters) {
            mAltitudeMeters = altitudeMeters;
            return this;
        }


        /**
         * Sets the horizontal uncertainty (68% confidence) in meters on the device position at
         * which the corrections are provided.
         */
        public Builder setHorizontalPositionUncertaintyMeters(
                double horizontalPositionUncertaintyMeters) {
            mHorizontalPositionUncertaintyMeters = horizontalPositionUncertaintyMeters;
            return this;
        }

        /**
         * Sets the vertical uncertainty (68% confidence) in meters on the device position at which
         * the corrections are provided.
         */
        public Builder setVerticalPositionUncertaintyMeters(
                double verticalPositionUncertaintyMeters) {
            mVerticalPositionUncertaintyMeters = verticalPositionUncertaintyMeters;
            return this;
        }

        /** Sets the time of applicability, GPS time of week in nanoseconds. */
        public Builder setToaGpsNanosecondsOfWeek(long toaGpsNanosecondsOfWeek) {
            mToaGpsNanosecondsOfWeek = toaGpsNanosecondsOfWeek;
            return this;
        }

        /**
         * Sets a the list of {@link GnssSingleSatCorrection} containing measurement corrections for
         * a satellite in view
         */
        public Builder setSingleSatelliteCorrectionList(
                @Nullable List<GnssSingleSatCorrection> singleSatCorrectionList) {
            if (singleSatCorrectionList == null) {
                mSingleSatCorrectionList = null;
            } else {
                mSingleSatCorrectionList =
                        Collections.unmodifiableList(new ArrayList<>(singleSatCorrectionList));
            }
            return this;
        }

        /** Builds a {@link GnssMeasurementCorrections} instance as specified by this builder. */
        public GnssMeasurementCorrections build() {
            return new GnssMeasurementCorrections(this);
        }
    }
}
