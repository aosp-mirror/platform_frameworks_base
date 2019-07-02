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
    @FloatRange(from = -90.0f, to = 90.0f)
    private final double mLatitudeDegrees;
    /** Represents longitude in degrees at which the corrections are computed. */
    @FloatRange(from = -180.0f, to = 180.0f)
    private final double mLongitudeDegrees;
    /**
     * Represents altitude in meters above the WGS 84 reference ellipsoid at which the corrections
     * are computed.
     */
    @FloatRange(from = -1000.0, to = 10000.0f)
    private final double mAltitudeMeters;
    /**
     * Represents the horizontal uncertainty (68% confidence) in meters on the device position at
     * which the corrections are provided.
     *
     * <p> This value is useful for example to judge how accurate the provided corrections are.
     */
    @FloatRange(from = 0.0f)
    private final double mHorizontalPositionUncertaintyMeters;
    /**
     * Represents the vertical uncertainty (68% confidence) in meters on the device position at
     * which the corrections are provided.
     *
     * <p> This value is useful for example to judge how accurate the provided corrections are.
     */
    @FloatRange(from = 0.0f)
    private final double mVerticalPositionUncertaintyMeters;

    /** Time Of Applicability, GPS time of week in nanoseconds. */
    @IntRange(from = 0)
    private final long mToaGpsNanosecondsOfWeek;

    /**
     * A set of {@link GnssSingleSatCorrection} each containing measurement corrections for a
     * satellite in view.
     */
    @NonNull
    private final List<GnssSingleSatCorrection> mSingleSatCorrectionList;

    private GnssMeasurementCorrections(Builder builder) {
        mLatitudeDegrees = builder.mLatitudeDegrees;
        mLongitudeDegrees = builder.mLongitudeDegrees;
        mAltitudeMeters = builder.mAltitudeMeters;
        mHorizontalPositionUncertaintyMeters = builder.mHorizontalPositionUncertaintyMeters;
        mVerticalPositionUncertaintyMeters = builder.mVerticalPositionUncertaintyMeters;
        mToaGpsNanosecondsOfWeek = builder.mToaGpsNanosecondsOfWeek;
        final List<GnssSingleSatCorrection> singleSatCorrList =  builder.mSingleSatCorrectionList;
        Preconditions.checkArgument(singleSatCorrList != null && !singleSatCorrList.isEmpty());
        mSingleSatCorrectionList = Collections.unmodifiableList(new ArrayList<>(singleSatCorrList));
    }

    /** Gets the latitude in degrees at which the corrections are computed. */
    @FloatRange(from = -90.0f, to = 90.0f)
    public double getLatitudeDegrees() {
        return mLatitudeDegrees;
    }

    /** Gets the longitude in degrees at which the corrections are computed. */
    @FloatRange(from = -180.0f, to = 180.0f)
    public double getLongitudeDegrees() {
        return mLongitudeDegrees;
    }

    /**
     * Gets the altitude in meters above the WGS 84 reference ellipsoid at which the corrections are
     * computed.
     */
    @FloatRange(from = -1000.0f, to = 10000.0f)
    public double getAltitudeMeters() {
        return mAltitudeMeters;
    }

    /**
     * Gets the horizontal uncertainty (68% confidence) in meters on the device position at
     * which the corrections are provided.
     */
    @FloatRange(from = 0.0f)
    public double getHorizontalPositionUncertaintyMeters() {
        return mHorizontalPositionUncertaintyMeters;
    }

    /**
     * Gets the vertical uncertainty (68% confidence) in meters on the device position at
     * which the corrections are provided.
     */
    @FloatRange(from = 0.0f)
    public double getVerticalPositionUncertaintyMeters() {
        return mVerticalPositionUncertaintyMeters;
    }

    /** Gets the time of applicability, GPS time of week in nanoseconds. */
    @IntRange(from = 0)
    public long getToaGpsNanosecondsOfWeek() {
        return mToaGpsNanosecondsOfWeek;
    }

    /**
     * Gets a set of {@link GnssSingleSatCorrection} each containing measurement corrections for a
     * satellite in view
     */
    @NonNull
    public List<GnssSingleSatCorrection> getSingleSatelliteCorrectionList() {
        return mSingleSatCorrectionList;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<GnssMeasurementCorrections> CREATOR =
            new Creator<GnssMeasurementCorrections>() {
                @Override
                @NonNull
                public GnssMeasurementCorrections createFromParcel(@NonNull Parcel parcel) {
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
                            singleSatCorrectionList);
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
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
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
        @Nullable private List<GnssSingleSatCorrection> mSingleSatCorrectionList;

        /** Sets the latitude in degrees at which the corrections are computed. */
        @NonNull public Builder setLatitudeDegrees(
                @FloatRange(from = -90.0f, to = 90.0f) double latitudeDegrees) {
            mLatitudeDegrees = latitudeDegrees;
            return this;
        }

        /** Sets the longitude in degrees at which the corrections are computed. */
        @NonNull public Builder setLongitudeDegrees(
                @FloatRange(from = -180.0f, to = 180.0f) double longitudeDegrees) {
            mLongitudeDegrees = longitudeDegrees;
            return this;
        }

        /**
         * Sets the altitude in meters above the WGS 84 reference ellipsoid at which the corrections
         * are computed.
         */
        @NonNull public Builder setAltitudeMeters(
                @FloatRange(from = -1000.0f, to = 10000.0f) double altitudeMeters) {
            mAltitudeMeters = altitudeMeters;
            return this;
        }


        /**
         * Sets the horizontal uncertainty (68% confidence) in meters on the device position at
         * which the corrections are provided.
         */
        @NonNull public Builder setHorizontalPositionUncertaintyMeters(
                @FloatRange(from = 0.0f) double horizontalPositionUncertaintyMeters) {
            mHorizontalPositionUncertaintyMeters = horizontalPositionUncertaintyMeters;
            return this;
        }

        /**
         * Sets the vertical uncertainty (68% confidence) in meters on the device position at which
         * the corrections are provided.
         */
        @NonNull public Builder setVerticalPositionUncertaintyMeters(
                @FloatRange(from = 0.0f) double verticalPositionUncertaintyMeters) {
            mVerticalPositionUncertaintyMeters = verticalPositionUncertaintyMeters;
            return this;
        }

        /** Sets the time of applicability, GPS time of week in nanoseconds. */
        @NonNull public Builder setToaGpsNanosecondsOfWeek(
                @IntRange(from = 0) long toaGpsNanosecondsOfWeek) {
            mToaGpsNanosecondsOfWeek = toaGpsNanosecondsOfWeek;
            return this;
        }

        /**
         * Sets a the list of {@link GnssSingleSatCorrection} containing measurement corrections for
         * a satellite in view
         */
        @NonNull public Builder setSingleSatelliteCorrectionList(
                @NonNull List<GnssSingleSatCorrection> singleSatCorrectionList) {
            mSingleSatCorrectionList = singleSatCorrectionList;
            return this;
        }

        /** Builds a {@link GnssMeasurementCorrections} instance as specified by this builder. */
        @NonNull public GnssMeasurementCorrections build() {
            return new GnssMeasurementCorrections(this);
        }
    }
}
