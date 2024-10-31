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
import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
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
@SystemApi
public final class RadioAlert implements Parcelable {

    /**
     * Actionable by all targeted recipients.
     */
    public static final int STATUS_ACTUAL = 0;

    /**
     *  Actionable only by designated exercise participants.
     */
    public static final int STATUS_EXERCISE = 1;

    /**
     * Technical testing only, all recipients disregard.
     */
    public static final int STATUS_TEST = 2;

    /**
     * The status of the alert message.
     *
     * <p>Status is the appropriate handling of the alert message.
     *
     * @hide
     */
    @IntDef(prefix = { "STATUS_" }, value = {
            STATUS_ACTUAL,
            STATUS_EXERCISE,
            STATUS_TEST,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AlertStatus {}

    /**
     * Initial information requiring attention by targeted recipients.
     */
    public static final int MESSAGE_TYPE_ALERT = 0;

    /**
     * Updates and supersedes the earlier message(s).
     */
    public static final int MESSAGE_TYPE_UPDATE = 1;

    /**
     * Cancels the earlier message(s).
     */
    public static final int MESSAGE_TYPE_CANCEL = 2;

    /**
     * The emergency alert message type.
     *
     * <p>The message type indicates the emergency alert message nature.
     *
     * @hide
     */
    @IntDef(prefix = { "MESSAGE_TYPE_" }, value = {
            MESSAGE_TYPE_ALERT,
            MESSAGE_TYPE_UPDATE,
            MESSAGE_TYPE_CANCEL,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AlertMessageType {}

    /**
     * Alert category related to geophysical (inc. landslide).
     */
    public static final int CATEGORY_GEO = 0;

    /**
     * Alert category related to meteorological (inc. flood).
     */
    public static final int CATEGORY_MET = 1;

    /**
     * Alert category related to general emergency and public safety.
     */
    public static final int CATEGORY_SAFETY = 2;

    /**
     * Alert category related to law enforcement, military, homeland and local/private security.
     */
    public static final int CATEGORY_SECURITY = 3;

    /**
     * Alert category related to rescue and recovery.
     */
    public static final int CATEGORY_RESCUE = 4;

    /**
     * Alert category related to fire suppression and rescue.
     */
    public static final int CATEGORY_FIRE = 5;

    /**
     * Alert category related to medical and public health.
     */
    public static final int CATEGORY_HEALTH = 6;

    /**
     * Alert category related to pollution and other environmental.
     */
    public static final int CATEGORY_ENV = 7;

    /**
     * Alert category related to public and private transportation.
     */
    public static final int CATEGORY_TRANSPORT = 8;

    /**
     * Alert category related to utility, telecommunication, other non-transport infrastructure.
     */
    public static final int CATEGORY_INFRA = 9;

    /**
     * Alert category related to chemical, biological, radiological, nuclear or high-yield
     * explosive threat or attack.
     */
    public static final int CATEGORY_CBRNE = 10;

    /**
     * Alert category of other events.
     */
    public static final int CATEGORY_OTHER = 11;

    /**
     * The category of the subject event of the emergency alert message.
     *
     * @hide
     */
    @IntDef(prefix = { "CATEGORY_" }, value = {
            CATEGORY_GEO,
            CATEGORY_MET,
            CATEGORY_SAFETY,
            CATEGORY_SECURITY,
            CATEGORY_RESCUE,
            CATEGORY_FIRE,
            CATEGORY_HEALTH,
            CATEGORY_ENV,
            CATEGORY_TRANSPORT,
            CATEGORY_INFRA,
            CATEGORY_CBRNE,
            CATEGORY_OTHER,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AlertCategory {}

    /**
     * Urgency indicating that responsive action should be taken immediately.
     */
    public static final int URGENCY_IMMEDIATE = 0;

    /**
     * Urgency indicating that responsive action should be taken soon.
     */
    public static final int URGENCY_EXPECTED = 1;

    /**
     * Urgency indicating that responsive action should be taken in the near future.
     */
    public static final int URGENCY_FUTURE = 2;

    /**
     * Urgency indicating that responsive action is no longer required.
     */
    public static final int URGENCY_PAST = 3;

    /**
     * Unknown Urgency.
     */
    public static final int URGENCY_UNKNOWN = 4;

    /**
     * The urgency of the subject event of the emergency alert message.
     *
     * @hide
     */
    @IntDef(prefix = { "URGENCY_" }, value = {
            URGENCY_IMMEDIATE,
            URGENCY_EXPECTED,
            URGENCY_FUTURE,
            URGENCY_PAST,
            URGENCY_UNKNOWN,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AlertUrgency {}

    /**
     * Severity indicating extraordinary threat to life or property.
     */
    public static final int SEVERITY_EXTREME = 0;

    /**
     * Severity indicating significant threat to life or property.
     */
    public static final int SEVERITY_SEVERE = 1;

    /**
     * Severity indicating possible threat to life or property.
     */
    public static final int SEVERITY_MODERATE = 2;

    /**
     * Severity indicating minimal to no known threat to life or property.
     */
    public static final int SEVERITY_MINOR = 3;

    /**
     * Unknown severity.
     */
    public static final int SEVERITY_UNKNOWN = 4;

    /**
     * The severity of the subject event of the emergency alert message.
     *
     * @hide
     */
    @IntDef(prefix = { "SEVERITY_" }, value = {
            SEVERITY_EXTREME,
            SEVERITY_SEVERE,
            SEVERITY_MODERATE,
            SEVERITY_MINOR,
            SEVERITY_UNKNOWN,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AlertSeverity {}

    /**
     * Certainty indicating that the event is determined to has occurred or to be ongoing.
     */
    public static final int CERTAINTY_OBSERVED = 0;

    /**
     * Certainty indicating that the event is likely (probability > ~50%).
     */
    public static final int CERTAINTY_LIKELY = 1;

    /**
     * Certainty indicating that the event is possible but not likely (probability <= ~50%).
     */
    public static final int CERTAINTY_POSSIBLE = 2;

    /**
     * Certainty indicating that the event is not expected to occur (probability ~ 0).
     */
    public static final int CERTAINTY_UNLIKELY = 3;

    /**
     * Unknown certainty.
     */
    public static final int CERTAINTY_UNKNOWN = 4;

    /**
     * The certainty of the subject event of the emergency alert message.
     *
     * @hide
     */
    @IntDef(prefix = { "CERTAINTY_" }, value = {
            CERTAINTY_OBSERVED,
            CERTAINTY_LIKELY,
            CERTAINTY_POSSIBLE,
            CERTAINTY_UNLIKELY,
            CERTAINTY_UNKNOWN,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AlertCertainty {}

    public static final class Geocode implements Parcelable {

        private final String mValueName;
        private final String mValue;

        /**
         * Constructor of geocode.
         *
         * @param valueName Name of geocode value.
         * @param value Value of geocode.
         *
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

        /**
         * Gets the value name of a geographic code.
         *
         * <p>Value name are acronyms should be represented in all capital
         * letters without periods (e.g., SAME, FIPS, ZIP). See ITU-T X.1303
         * bis for more info).
         *
         * @return Value name of a geographic code.
         */
        @NonNull
        public String getValueName() {
            return mValueName;
        }

        /**
         * Gets the value of a geographic code.
         *
         * @return Value of a geographic code.
         */
        @NonNull
        public String getValue() {
            return mValue;
        }

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
         *
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

        /**
         * Gets the latitude of a coordinate.
         *
         * @return Latitude of a coordinate.
         */
        @FloatRange(from = -90.0, to = 90.0)
        public double getLatitude() {
            return mLatitude;
        }

        /**
         * Gets the longitude of a coordinate.
         *
         * @return Longitude of a coordinate.
         */
        @FloatRange(from = -180.0, to = 180.0)
        public double getLongitude() {
            return mLongitude;
        }

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
         *
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

        /**
         * Gets the coordinates of points defining a polygon.
         *
         * <p>A minimum of 4 coordinates must be present and the first and last
         * coordinates must be the same. See WGS 84 for more information.
         *
         * @return Paired values of points defining a polygon.
         */
        @NonNull
        public List<Coordinate> getCoordinates() {
            return mCoordinates;
        }

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
         *
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

        /**
         * Gets polygons delineating the affected area of the alert message.
         *
         * @return Polygons delineating the affected area of the alert message.
         */
        @NonNull
        public List<Polygon> getPolygons() {
            return mPolygons;
        }

        /**
         * Gets the geographic codes delineating the affected area of the alert
         * message.
         *
         * @return geographic codes delineating the affected area of the alert
         * message.
         */
        @NonNull
        public List<Geocode> getGeocodes() {
            return mGeocodes;
        }

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

        private final @NonNull int[] mCategories;
        private final @AlertUrgency int mUrgency;
        private final @AlertSeverity int mSeverity;
        private final @AlertCertainty int mCertainty;
        private final @NonNull String mTextualMessage;
        private final @NonNull List<AlertArea> mAreaList;
        @Nullable private final String mLanguage;

        /**
         * Constructor for alert info.
         *
         * @param categories Array of categories of the subject event of the alert message
         * @param urgency The urgency of the subject event of the alert message
         * @param severity The severity of the subject event of the alert message
         * @param certainty The certainty of the subject event of the alert message
         * @param textualMessage Textual descriptions of the subject event
         * @param areaList The array of geographic areas to which the alert info segment in which
         *                 it appears applies
         * @param language The optional language field of the alert info
         * @hide
         */
        public AlertInfo(@NonNull int[] categories, @AlertUrgency int urgency,
                @AlertSeverity int severity, @AlertCertainty int certainty,
                String textualMessage, @NonNull List<AlertArea> areaList,
                @Nullable String language) {
            Objects.requireNonNull(categories, "Categories can not be null");
            Arrays.sort(categories);
            mCategories = categories;
            mUrgency = urgency;
            mSeverity = severity;
            mCertainty = certainty;
            mTextualMessage = textualMessage;
            mAreaList = Objects.requireNonNull(areaList, "Area list can not be null");
            mLanguage = language;
        }

        private AlertInfo(Parcel in) {
            mCategories = new int[in.readInt()];
            in.readIntArray(mCategories);
            mUrgency = in.readInt();
            mSeverity = in.readInt();
            mCertainty = in.readInt();
            mTextualMessage = in.readString8();
            mAreaList = new ArrayList<>();
            in.readTypedList(mAreaList, AlertArea.CREATOR);
            boolean hasLanguage = in.readBoolean();
            if (hasLanguage) {
                mLanguage = in.readString8();
            } else {
                mLanguage = null;
            }
        }

        /**
         * Gets categories of the subject event of the alert info.
         *
         * <p>According to ITU-T X.1303, a single alert info block may contains multiple categories.
         *
         * @return Categories of the subject event of the alert info.
         */
        @NonNull
        public int[] getCategories() {
            return mCategories;
        }

        /**
         * Gets the urgency of the subject event of the alert info.
         *
         * <p>Urgency represents the time available to prepare for the alert. See ITU-T X.1303 bis
         * for more info.
         *
         * @return The urgency of the subject event of the alert info.
         */
        @AlertUrgency public int getUrgency() {
            return mUrgency;
        }

        /**
         * Gets the severity of the subject event of the alert info.
         *
         * <p>Severity represents the intensity of impact. See ITU-T X.1303 bis for more info.
         *
         * @return The urgency of the subject event of the alert info
         */
        @AlertSeverity public int getSeverity() {
            return mSeverity;
        }

        /**
         * Gets the certainty of the subject event of the alert info.
         *
         * <p>Certainty represents confidence in the observation or prediction. See ITU-T X.1303
         * bis for more info.
         *
         * @return The certainty of the subject event of the alert info.
         */
        @AlertCertainty public int getCertainty() {
            return mCertainty;
        }

        /**
         * Gets textual descriptions of the subject event.
         *
         * @return Textual descriptions of the subject event.
         */
        @NonNull
        public String getDescription() {
            return mTextualMessage;
        }

        /**
         * Gets geographic areas to which the alert info segment in which it
         * appears applies.
         *
         * @return Areas to which the alert info segment in which it appears applies.
         */
        @NonNull
        public List<AlertArea> getAreas() {
            return mAreaList;
        }

        /**
         * Gets IETF RFC 3066 language code donating the language of the alert message.
         *
         * @return {@code null} if unspecified, otherwise IETF RFC 3066 language code
         */
        @Nullable
        public String getLanguage() {
            return mLanguage;
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
            dest.writeInt(mCategories.length);
            dest.writeIntArray(mCategories);
            dest.writeInt(mUrgency);
            dest.writeInt(mSeverity);
            dest.writeInt(mCertainty);
            dest.writeString8(mTextualMessage);
            dest.writeTypedList(mAreaList);
            if (mLanguage == null) {
                dest.writeBoolean(false);
            } else {
                dest.writeBoolean(true);
                dest.writeString8(mLanguage);
            }
        }

        @NonNull
        @Override
        public String toString() {
            return "AlertInfo [categories=" + Arrays.toString(mCategories) + ", urgency="
                    + mUrgency + ", severity=" + mSeverity + ", certainty=" + mCertainty
                    + ", textualMessage=" + mTextualMessage + ", areaList=" + mAreaList
                    + ", language=" + mLanguage + "]";
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.hashCode(mCategories), mUrgency, mSeverity, mCertainty,
                    mTextualMessage, mAreaList, mLanguage);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof AlertInfo other)) {
                return false;
            }

            return Arrays.equals(mCategories, other.mCategories)
                    && mUrgency == other.mUrgency && mSeverity == other.mSeverity
                    && mCertainty == other.mCertainty
                    && mTextualMessage.equals(other.mTextualMessage)
                    && mAreaList.equals(other.mAreaList)
                    && Objects.equals(mLanguage, other.mLanguage);
        }
    }

    private final @AlertStatus int mStatus;
    private final @AlertMessageType int mMessageType;
    private final List<AlertInfo> mInfoList;

    /**
     * Constructor of radio alert message.
     *
     * @param status Status of alert message
     * @param messageType Message type of alert message
     * @param infoList List of alert info
     * @hide
     */
    public RadioAlert(@AlertStatus int status, @AlertMessageType int messageType,
            @NonNull List<AlertInfo> infoList) {
        mStatus = status;
        mMessageType = messageType;
        mInfoList = Objects.requireNonNull(infoList, "Alert info list can not be null");
    }

    private RadioAlert(Parcel in) {
        mStatus = in.readInt();
        mMessageType = in.readInt();
        mInfoList = in.readParcelableList(new ArrayList<>(), AlertInfo.class.getClassLoader(),
                AlertInfo.class);
    }

    /**
     * Gets the status of the alert message.
     *
     * <p>Status is the appropriate handling of the alert message. See ITU-T X.1303 bis for more
     * info.
     *
     * @return The status of the alert message.
     */
    @AlertStatus public int getStatus() {
        return mStatus;
    }

    /**
     * Gets the message type of the alert message.
     *
     * <p>Message type is The nature of the emergency alert message. See ITU-T X.1303 bis for
     * more info.
     *
     * @return The message type of the alert message.
     */
    @AlertMessageType public int getMessageType() {
        return mMessageType;
    }

    /**
     * Gets the alert info list.
     *
     * <p>See ITU-T X.1303 bis for more info of alert info.
     *
     * @return The alert info list.
     */
    @NonNull
    public List<AlertInfo> getInfoList() {
        return mInfoList;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mStatus);
        dest.writeInt(mMessageType);
        dest.writeParcelableList(mInfoList, /* flags= */ 0);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    @Override
    public String toString() {
        return "RadioAlert [status=" + mStatus + ", messageType=" + mMessageType
                + ", infoList= " + mInfoList + "]";
    }

    @Override
    public int hashCode() {
        return Objects.hash(mStatus, mMessageType, mInfoList);
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
                && mInfoList.equals(other.mInfoList);
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
