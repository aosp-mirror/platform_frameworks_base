/**
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

package android.hardware.radio;

import android.annotation.FlaggedApi;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Emergency Alert Message
 *
 * <p>Alert message can be sent from a radio station of technologies such as HD radio to
 * the radio users for some emergency events (see ITU-T X.1303 bis for more info).
 * @hide
 */
@FlaggedApi(Flags.FLAG_HD_RADIO_EMERGENCY_ALERT_SYSTEM)
public final class RadioAlert implements Parcelable {

    public static final class Geocode implements Parcelable {

        private final String mValueName;
        private final String mValue;

        /**
         * Constructor of geocode.
         *
         * @param valueName Name of geocode value
         * @param value Value of geocode
         * @hide
         */
        public Geocode(@NonNull String valueName, @NonNull String value) {
            mValueName = Objects.requireNonNull(valueName, "Geocode value name can not be null");
            mValue = Objects.requireNonNull(value, "Geocode value can not be null");
        }

        private Geocode(Parcel in) {
            mValueName = in.readString8();
            mValue = in.readString8();
        }

        public static final @NonNull Creator<Geocode> CREATOR = new Creator<Geocode>() {
            @Override
            public Geocode createFromParcel(Parcel in) {
                return new Geocode(in);
            }

            @Override
            public Geocode[] newArray(int size) {
                return new Geocode[size];
            }
        };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeString8(mValueName);
            dest.writeString8(mValue);
        }

        @NonNull
        @Override
        public String toString() {
            return "Gecode [valueName=" + mValueName + ", value=" + mValue + "]";
        }

        @Override
        public int hashCode() {
            return Objects.hash(mValueName, mValue);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof Geocode other)) {
                return false;
            }

            return Objects.equals(mValueName, other.mValueName)
                    && Objects.equals(mValue, other.mValue);
        }
    }

    public static final class Coordinate implements Parcelable {
        private final double mLatitude;
        private final double mLongitude;

        /**
         * Constructor of coordinate.
         *
         * @param latitude Latitude of the coordinate
         * @param longitude Longitude of the coordinate
         * @hide
         */
        public Coordinate(double latitude, double longitude) {
            if (latitude < -90.0 || latitude > 90.0) {
                throw new IllegalArgumentException("Latitude value should be between -90 and 90");
            }
            if (longitude < -180.0 || longitude > 180.0) {
                throw new IllegalArgumentException(
                        "Longitude value should be between -180 and 180");
            }
            mLatitude = latitude;
            mLongitude = longitude;
        }

        private Coordinate(Parcel in) {
            mLatitude = in.readDouble();
            mLongitude = in.readDouble();
        }

        public static final @NonNull Creator<Coordinate> CREATOR = new Creator<Coordinate>() {
            @Override
            public Coordinate createFromParcel(Parcel in) {
                return new Coordinate(in);
            }

            @Override
            public Coordinate[] newArray(int size) {
                return new Coordinate[size];
            }
        };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeDouble(mLatitude);
            dest.writeDouble(mLongitude);
        }

        @NonNull
        @Override
        public String toString() {
            return "Coordinate [latitude=" + mLatitude + ", longitude=" + mLongitude + "]";
        }

        @Override
        public int hashCode() {
            return Objects.hash(mLatitude, mLongitude);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof Coordinate other)) {
                return false;
            }
            return mLatitude == other.mLatitude && mLongitude == other.mLongitude;
        }
    }

    public static final class Polygon implements Parcelable {

        private final List<Coordinate> mCoordinates;

        /**
         * Constructor of polygon.
         *
         * @param coordinates Coordinates the polygon is composed of
         * @hide
         */
        public Polygon(@NonNull List<Coordinate> coordinates) {
            Objects.requireNonNull(coordinates, "Coordinates can not be null");
            if (coordinates.size() < 4) {
                throw new IllegalArgumentException("Number of coordinates must be at least 4");
            }
            if (!coordinates.get(0).equals(coordinates.get(coordinates.size() - 1))) {
                throw new IllegalArgumentException(
                        "The last and first coordinates must be the same");
            }
            mCoordinates = coordinates;
        }

        private Polygon(Parcel in) {
            mCoordinates = new ArrayList<>();
            in.readTypedList(mCoordinates, Coordinate.CREATOR);
        }

        public static final @NonNull Creator<Polygon> CREATOR = new Creator<Polygon>() {
            @Override
            public Polygon createFromParcel(Parcel in) {
                return new Polygon(in);
            }

            @Override
            public Polygon[] newArray(int size) {
                return new Polygon[size];
            }
        };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeTypedList(mCoordinates);
        }

        @NonNull
        @Override
        public String toString() {
            return "Polygon [coordinates=" + mCoordinates + "]";
        }

        @Override
        public int hashCode() {
            return Objects.hash(mCoordinates);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof Polygon other)) {
                return false;
            }
            return mCoordinates.equals(other.mCoordinates);
        }
    }

    public static final class AlertArea implements Parcelable {

        private final List<Polygon> mPolygons;
        private final List<Geocode> mGeocodes;

        /**
         * Constructor of alert area.
         *
         * @param polygons Polygons used in alert area
         * @param geocodes Geocodes used in alert area
         * @hide
         */
        public AlertArea(@NonNull List<Polygon> polygons, @NonNull List<Geocode> geocodes) {
            mPolygons = Objects.requireNonNull(polygons, "Polygons can not be null");
            mGeocodes = Objects.requireNonNull(geocodes, "Geocodes can not be null");
        }

        private AlertArea(Parcel in) {
            mPolygons = new ArrayList<>();
            mGeocodes = new ArrayList<>();
            in.readTypedList(mPolygons, Polygon.CREATOR);
            in.readTypedList(mGeocodes, Geocode.CREATOR);
        }

        public static final @NonNull Creator<AlertArea> CREATOR = new Creator<AlertArea>() {
            @Override
            public AlertArea createFromParcel(Parcel in) {
                return new AlertArea(in);
            }

            @Override
            public AlertArea[] newArray(int size) {
                return new AlertArea[size];
            }
        };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeTypedList(mPolygons);
            dest.writeTypedList(mGeocodes);
        }

        @NonNull
        @Override
        public String toString() {
            return "AlertArea [polygons=" + mPolygons + ", geocodes=" + mGeocodes + "]";
        }

        @Override
        public int hashCode() {
            return Objects.hash(mPolygons, mGeocodes);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof AlertArea other)) {
                return false;
            }

            return mPolygons.equals(other.mPolygons) && mGeocodes.equals(other.mGeocodes);
        }
    }

    public static final class AlertInfo implements Parcelable {

        private final List<Integer> mCategoryList;
        private final int mUrgency;
        private final int mSeverity;
        private final int mCertainty;
        private final String mTextualMessage;
        private final List<AlertArea> mAreaList;

        /**
         * Constructor for alert info.
         *
         * @param categoryList Array of categories of the subject event of the alert message
         * @param urgency The urgency of the subject event of the alert message
         * @param severity The severity of the subject event of the alert message
         * @param certainty The certainty of the subject event of the alert message
         * @param textualMessage Textual descriptions of the subject event
         * @param areaList The array of geographic areas to which the alert info segment in which
         *                 it appears applies
         * @hide
         */
        public AlertInfo(@NonNull List<Integer> categoryList, int urgency,
                int severity, int certainty,
                String textualMessage, @NonNull List<AlertArea> areaList) {
            mCategoryList = Objects.requireNonNull(categoryList, "Category list can not be null");
            mUrgency = urgency;
            mSeverity = severity;
            mCertainty = certainty;
            mTextualMessage = textualMessage;
            mAreaList = Objects.requireNonNull(areaList, "Area list can not be null");
        }

        private AlertInfo(Parcel in) {
            mCategoryList = in.readArrayList(Integer.class.getClassLoader(), Integer.class);
            mUrgency = in.readInt();
            mSeverity = in.readInt();
            mCertainty = in.readInt();
            mTextualMessage = in.readString8();
            mAreaList = new ArrayList<>();
            in.readTypedList(mAreaList, AlertArea.CREATOR);
        }

        public static final @NonNull Creator<AlertInfo> CREATOR = new Creator<AlertInfo>() {
            @Override
            public AlertInfo createFromParcel(Parcel in) {
                return new AlertInfo(in);
            }

            @Override
            public AlertInfo[] newArray(int size) {
                return new AlertInfo[size];
            }
        };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeList(mCategoryList);
            dest.writeInt(mUrgency);
            dest.writeInt(mSeverity);
            dest.writeInt(mCertainty);
            dest.writeString8(mTextualMessage);
            dest.writeTypedList(mAreaList);
        }

        @NonNull
        @Override
        public String toString() {
            return "AlertInfo [categoryList=" + mCategoryList + ", urgency=" + mUrgency
                    + ", severity=" + mSeverity + ", certainty=" + mCertainty
                    + ", textualMessage=" + mTextualMessage + ", areaList=" + mAreaList + "]";
        }

        @Override
        public int hashCode() {
            return Objects.hash(mCategoryList, mUrgency, mSeverity, mCertainty, mTextualMessage,
                    mAreaList);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof AlertInfo other)) {
                return false;
            }

            return mCategoryList.equals(other.mCategoryList) && mUrgency == other.mUrgency
                    && mSeverity == other.mSeverity && mCertainty == other.mCertainty
                    && mTextualMessage.equals(other.mTextualMessage)
                    && mAreaList.equals(other.mAreaList);
        }
    }

    private final int mStatus;
    private final int mMessageType;
    private final List<AlertInfo> mInfoList;
    private final int mScope;

    /**
     * Constructor of radio alert message.
     *
     * @param status Status of alert message
     * @param messageType Message type of alert message
     * @param infoList List of alert info
     * @param scope Scope of alert message
     * @hide
     */
    public RadioAlert(int status, int messageType,
            @NonNull List<AlertInfo> infoList, int scope) {
        mStatus = status;
        mMessageType = messageType;
        mInfoList = Objects.requireNonNull(infoList, "Alert info list can not be null");
        mScope = scope;
    }

    private RadioAlert(Parcel in) {
        mStatus = in.readInt();
        mMessageType = in.readInt();
        mInfoList = in.readParcelableList(new ArrayList<>(), AlertInfo.class.getClassLoader(),
                AlertInfo.class);
        mScope = in.readInt();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mStatus);
        dest.writeInt(mMessageType);
        dest.writeParcelableList(mInfoList, /* flags= */ 0);
        dest.writeInt(mScope);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    @Override
    public String toString() {
        return "RadioAlert [status=" + mStatus + ", messageType=" + mMessageType
                + ", infoList= " + mInfoList + ", scope=" + mScope + "]";
    }

    @Override
    public int hashCode() {
        return Objects.hash(mStatus, mMessageType, mInfoList, mScope);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof RadioAlert other)) {
            return false;
        }

        return mStatus == other.mStatus && mMessageType == other.mMessageType
                && mInfoList.equals(other.mInfoList) && mScope == other.mScope;
    }

    public static final @NonNull Creator<RadioAlert> CREATOR = new Creator<RadioAlert>() {
        @Override
        public RadioAlert createFromParcel(Parcel in) {
            return new RadioAlert(in);
        }

        @Override
        public RadioAlert[] newArray(int size) {
            return new RadioAlert[size];
        }
    };
}
