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

    /**
     * Indicates whether the environment bearing is available.
     */
    private final boolean mHasEnvironmentBearing;

    /**
     * Environment bearing in degrees clockwise from true north, in the direction of user motion.
     * Environment bearing is provided when it is known with high probability that velocity is
     * aligned with an environment feature (such as edge of a building, or road).
     */
    @FloatRange(from = 0.0f, to = 360.0f)
    private final float mEnvironmentBearingDegrees;

    /**
     * Environment bearing uncertainty in degrees.
     */
    @FloatRange(from = 0.0f, to = 180.0f)
    private final float mEnvironmentBearingUncertaintyDegrees;

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
        mHasEnvironmentBearing = builder.mEnvironmentBearingIsSet
                && builder.mEnvironmentBearingUncertaintyIsSet;
        mEnvironmentBearingDegrees = builder.mEnvironmentBearingDegrees;
        mEnvironmentBearingUncertaintyDegrees = builder.mEnvironmentBearingUncertaintyDegrees;
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

    /**
     * If true, environment bearing will be available.
     */
    public boolean hasEnvironmentBearing() {
        return mHasEnvironmentBearing;
    }

    /**
     * Gets the environment bearing in degrees clockwise from true north, in the direction of user
     * motion. Environment bearing is provided when it is known with high probability that
     * velocity is aligned with an environment feature (such as edge of a building, or road).
     *
     * {@link #hasEnvironmentBearing} should be called to check the environment bearing is available
     * before calling this method. The value is undefined if {@link #hasEnvironmentBearing} returns
     * false.
     */
    @FloatRange(from = 0.0f, to = 360.0f)
    public float getEnvironmentBearingDegrees() {
        return mEnvironmentBearingDegrees;
    }

    /**
     * Gets the environment bearing uncertainty in degrees. It represents the standard deviation of
     * the physical structure in the circle of position uncertainty. The uncertainty can take values
     * between 0 and 180 degrees. The {@link #hasEnvironmentBearing} becomes false as the
     * uncertainty value passes a predefined threshold depending on the physical structure around
     * the user.
     *
     * {@link #hasEnvironmentBearing} should be called to check the environment bearing is available
     * before calling this method. The value is undefined if {@link #hasEnvironmentBearing} returns
     * false.
     */
    @FloatRange(from = 0.0f, to = 180.0f)
    public float getEnvironmentBearingUncertaintyDegrees() {
        return mEnvironmentBearingUncertaintyDegrees;
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
                    boolean hasEnvironmentBearing = parcel.readBoolean();
                    if (hasEnvironmentBearing) {
                        gnssMeasurementCorrectons.setEnvironmentBearingDegrees(parcel.readFloat());
                        gnssMeasurementCorrectons.setEnvironmentBearingUncertaintyDegrees(
                                parcel.readFloat());
                    }
                    return gnssMeasurementCorrectons.build();
                }

                @Override
                public GnssMeasurementCorrections[] newArray(int i) {
                    return new GnssMeasurementCorrections[i];
                }
            };

    @NonNull
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
        builder.append(
                String.format(format, "HasEnvironmentBearing = ", mHasEnvironmentBearing));
        builder.append(
                String.format(format, "EnvironmentBearingDegrees = ",
                        mEnvironmentBearingDegrees));
        builder.append(
                String.format(format, "EnvironmentBearingUncertaintyDegrees = ",
                mEnvironmentBearingUncertaintyDegrees));
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
        parcel.writeBoolean(mHasEnvironmentBearing);
        if (mHasEnvironmentBearing) {
            parcel.writeFloat(mEnvironmentBearingDegrees);
            parcel.writeFloat(mEnvironmentBearingUncertaintyDegrees);
        }
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
        private float mEnvironmentBearingDegrees;
        private boolean mEnvironmentBearingIsSet = false;
        private float mEnvironmentBearingUncertaintyDegrees;
        private boolean mEnvironmentBearingUncertaintyIsSet = false;

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

        /**
         * Sets the environment bearing in degrees clockwise from true north, in the direction of
         * user motion. Environment bearing is provided when it is known with high probability
         * that velocity is aligned with an environment feature (such as edge of a building, or
         * road).
         *
         * Both the bearing and uncertainty must be set for the environment bearing to be valid.
         */
        @NonNull public Builder setEnvironmentBearingDegrees(
                @FloatRange(from = 0.0f, to = 360.0f)
                        float environmentBearingDegrees) {
            mEnvironmentBearingDegrees = environmentBearingDegrees;
            mEnvironmentBearingIsSet = true;
            return this;
        }

        /**
         * Sets the environment bearing uncertainty in degrees.
         *
         * Both the bearing and uncertainty must be set for the environment bearing to be valid.
         */
        @NonNull public Builder setEnvironmentBearingUncertaintyDegrees(
                @FloatRange(from = 0.0f, to = 180.0f)
                        float environmentBearingUncertaintyDegrees) {
            mEnvironmentBearingUncertaintyDegrees = environmentBearingUncertaintyDegrees;
            mEnvironmentBearingUncertaintyIsSet = true;
            return this;
        }

        /** Builds a {@link GnssMeasurementCorrections} instance as specified by this builder. */
        @NonNull public GnssMeasurementCorrections build() {
            if (mEnvironmentBearingIsSet ^ mEnvironmentBearingUncertaintyIsSet) {
                throw new IllegalStateException("Both environment bearing and environment bearing "
                        + "uncertainty must be set.");
            }
            return new GnssMeasurementCorrections(this);
        }
    }
}
