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

package android.net.wifi.rtt;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * The Device Location Configuration Information (LCI) specifies the location information of a peer
 * device (e.g. an Access Point).
 * <p>
 * The information matches the IEEE 802.11-2016 LCI report (Location configuration information
 * report).
 * <p>
 * Note: depending on the mechanism by which this information is returned (i.e. the API which
 * returns an instance of this class) it is possibly Self Reported (by the peer). In such a case
 * the information is NOT validated - use with caution. Consider validating it with other sources
 * of information before using it.
 */
public final class LocationConfigurationInformation implements Parcelable {
    /** @hide */
    @IntDef({
            ALTITUDE_UNKNOWN, ALTITUDE_IN_METERS, ALTITUDE_IN_FLOORS })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AltitudeTypes {
    }

    /**
     * Define an Altitude Type returned by {@link #getAltitudeType()}. Indicates that the location
     * does not specify an altitude or altitude uncertainty. The corresponding methods,
     * {@link #getAltitude()} and {@link #getAltitudeUncertainty()} are not valid and will throw
     * an exception.
     */
    public static final int ALTITUDE_UNKNOWN = 0;

    /**
     * Define an Altitude Type returned by {@link #getAltitudeType()}. Indicates that the location
     * specifies the altitude and altitude uncertainty in meters. The corresponding methods,
     * {@link #getAltitude()} and {@link #getAltitudeUncertainty()} return a valid value in meters.
     */
    public static final int ALTITUDE_IN_METERS = 1;

    /**
     * Define an Altitude Type returned by {@link #getAltitudeType()}. Indicates that the
     * location specifies the altitude in floors, and does not specify an altitude uncertainty.
     * The {@link #getAltitude()} method returns valid value in floors, and the
     * {@link #getAltitudeUncertainty()} method is not valid and will throw an exception.
     */
    public static final int ALTITUDE_IN_FLOORS = 2;

    private final double mLatitude;
    private final double mLatitudeUncertainty;
    private final double mLongitude;
    private final double mLongitudeUncertainty;
    private final int mAltitudeType;
    private final double mAltitude;
    private final double mAltitudeUncertainty;

    /**
     * Parse the raw LCI information element (byte array) and extract the
     * LocationConfigurationInformation structure.
     *
     * Note: any parsing errors or invalid/unexpected errors will result in a null being returned.
     *
     * @hide
     */
    @Nullable
    public static LocationConfigurationInformation parseInformationElement(byte id, byte[] data) {
        // TODO
        return null;
    }

    /** @hide */
    public LocationConfigurationInformation(double latitude, double latitudeUncertainty,
            double longitude, double longitudeUncertainty, @AltitudeTypes int altitudeType,
            double altitude, double altitudeUncertainty) {
        mLatitude = latitude;
        mLatitudeUncertainty = latitudeUncertainty;
        mLongitude = longitude;
        mLongitudeUncertainty = longitudeUncertainty;
        mAltitudeType = altitudeType;
        mAltitude = altitude;
        mAltitudeUncertainty = altitudeUncertainty;
    }

    /**
     * Get latitude in degrees. Values are per WGS 84 reference system. Valid values are between
     * -90 and 90.
     *
     * @return Latitude in degrees.
     */
    public double getLatitude() {
        return mLatitude;
    }

    /**
     * Get the uncertainty of the latitude {@link #getLatitude()} in degrees. A value of 0 indicates
     * an unknown uncertainty.
     *
     * @return Uncertainty of the latitude in degrees.
     */
    public double getLatitudeUncertainty() {
        return mLatitudeUncertainty;
    }

    /**
     * Get longitude in degrees. Values are per WGS 84 reference system. Valid values are between
     * -180 and 180.
     *
     * @return Longitude in degrees.
     */
    public double getLongitude() {
        return mLongitude;
    }

    /**
     * Get the uncertainty of the longitude {@link #getLongitude()} ()} in degrees.  A value of 0
     * indicates an unknown uncertainty.
     *
     * @return Uncertainty of the longitude in degrees.
     */
    public double getLongitudeUncertainty() {
        return mLongitudeUncertainty;
    }

    /**
     * Specifies the type of the altitude measurement returned by {@link #getAltitude()} and
     * {@link #getAltitudeUncertainty()}. The possible values are:
     * <li>{@link #ALTITUDE_UNKNOWN}: The altitude and altitude uncertainty are not provided.
     * <li>{@link #ALTITUDE_IN_METERS}: The altitude and altitude uncertainty are provided in
     * meters. Values are per WGS 84 reference system.
     * <li>{@link #ALTITUDE_IN_FLOORS}: The altitude is provided in floors, the altitude uncertainty
     * is not provided.
     *
     * @return The type of the altitude and altitude uncertainty.
     */
    public @AltitudeTypes int getAltitudeType() {
        return mAltitudeType;
    }

    /**
     * The altitude is interpreted according to the {@link #getAltitudeType()}. The possible values
     * are:
     * <li>{@link #ALTITUDE_UNKNOWN}: The altitude is not provided - this method will throw an
     * exception.
     * <li>{@link #ALTITUDE_IN_METERS}: The altitude is provided in meters. Values are per WGS 84
     * reference system.
     * <li>{@link #ALTITUDE_IN_FLOORS}: The altitude is provided in floors.
     *
     * @return Altitude value whose meaning is specified by {@link #getAltitudeType()}.
     */
    public double getAltitude() {
        if (mAltitudeType == ALTITUDE_UNKNOWN) {
            throw new IllegalStateException(
                    "getAltitude(): invoked on an invalid type: getAltitudeType()==UNKNOWN");
        }
        return mAltitude;
    }

    /**
     * Only valid if the the {@link #getAltitudeType()} is equal to {@link #ALTITUDE_IN_METERS} -
     * otherwise this method will throw an exception.
     * <p>
     * Get the uncertainty of the altitude {@link #getAltitude()} in meters.  A value of 0
     * indicates an unknown uncertainty.
     *
     * @return Uncertainty of the altitude in meters.
     */
    public double getAltitudeUncertainty() {
        if (mAltitudeType != ALTITUDE_IN_METERS) {
            throw new IllegalStateException(
                    "getAltitude(): invoked on an invalid type: getAltitudeType()!=IN_METERS");
        }
        return mAltitudeUncertainty;
    }

    /** @hide */
    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeDouble(mLatitude);
        dest.writeDouble(mLatitudeUncertainty);
        dest.writeDouble(mLongitude);
        dest.writeDouble(mLongitudeUncertainty);
        dest.writeInt(mAltitudeType);
        dest.writeDouble(mAltitude);
        dest.writeDouble(mAltitudeUncertainty);
    }

    public static final Creator<LocationConfigurationInformation> CREATOR =
            new Creator<LocationConfigurationInformation>() {
        @Override
        public LocationConfigurationInformation[] newArray(int size) {
            return new LocationConfigurationInformation[size];
        }

        @Override
        public LocationConfigurationInformation createFromParcel(Parcel in) {
            double latitude = in.readDouble();
            double latitudeUnc = in.readDouble();
            double longitude = in.readDouble();
            double longitudeUnc = in.readDouble();
            int altitudeType = in.readInt();
            double altitude = in.readDouble();
            double altitudeUnc = in.readDouble();

            return new LocationConfigurationInformation(latitude, latitudeUnc, longitude,
                    longitudeUnc, altitudeType, altitude, altitudeUnc);
        }
    };

    /** @hide */
    @Override
    public String toString() {
        return new StringBuilder("LCI: latitude=").append(mLatitude).append(
                ", latitudeUncertainty=").append(mLatitudeUncertainty).append(
                ", longitude=").append(mLongitude).append(", longitudeUncertainty=").append(
                mLongitudeUncertainty).append(", altitudeType=").append(mAltitudeType).append(
                ", altitude=").append(mAltitude).append(", altitudeUncertainty=").append(
                mAltitudeUncertainty).toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof LocationConfigurationInformation)) {
            return false;
        }

        LocationConfigurationInformation lhs = (LocationConfigurationInformation) o;

        return mLatitude == lhs.mLatitude && mLatitudeUncertainty == lhs.mLatitudeUncertainty
                && mLongitude == lhs.mLongitude
                && mLongitudeUncertainty == lhs.mLongitudeUncertainty
                && mAltitudeType == lhs.mAltitudeType && mAltitude == lhs.mAltitude
                && mAltitudeUncertainty == lhs.mAltitudeUncertainty;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mLatitude, mLatitudeUncertainty, mLongitude, mLongitudeUncertainty,
                mAltitudeType, mAltitude, mAltitudeUncertainty);
    }
}
